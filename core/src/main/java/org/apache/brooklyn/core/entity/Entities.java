/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.core.entity;

import org.apache.brooklyn.api.entity.Group;
import org.apache.brooklyn.api.objs.BrooklynObject;
import org.apache.brooklyn.core.mgmt.BrooklynTags;
import org.apache.brooklyn.core.mgmt.internal.*;

import static org.apache.brooklyn.util.guava.Functionals.isSatisfied;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.entity.drivers.EntityDriver;
import org.apache.brooklyn.api.entity.drivers.downloads.DownloadResolver;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.mgmt.EntityManager;
import org.apache.brooklyn.api.mgmt.ExecutionContext;
import org.apache.brooklyn.api.mgmt.LocationManager;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.mgmt.TaskAdaptable;
import org.apache.brooklyn.api.mgmt.TaskFactory;
import org.apache.brooklyn.api.policy.Policy;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.sensor.Enricher;
import org.apache.brooklyn.api.sensor.Feed;
import org.apache.brooklyn.api.sensor.Sensor;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.config.ConfigKey.HasConfigKey;
import org.apache.brooklyn.core.config.Sanitizer;
import org.apache.brooklyn.core.effector.Effectors;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.entity.trait.StartableMethods;
import org.apache.brooklyn.core.internal.BrooklynProperties;
import org.apache.brooklyn.core.location.Locations;
import org.apache.brooklyn.core.mgmt.BrooklynTaskTags;
import org.apache.brooklyn.core.objs.proxy.AbstractBrooklynObjectProxyImpl;
import org.apache.brooklyn.core.sensor.DependentConfiguration;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.collections.MutableSet;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.ParallelTask;
import org.apache.brooklyn.util.core.task.TaskTags;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.core.task.system.ProcessTaskWrapper;
import org.apache.brooklyn.util.core.task.system.SystemTasks;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.repeat.Repeater;
import org.apache.brooklyn.util.stream.Streams;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.Beta;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.Atomics;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * Convenience methods for working with entities.
 * <p>
 * Also see the various {@code *Methods} classes for traits,
 * such as {@link StartableMethods} for {@link Startable} implementations.
 */
public class Entities {

    private static final Logger log = LoggerFactory.getLogger(Entities.class);

    // Don't use `new Object()` - deserialization creates a different object from the constant.
    // Instead, use this enum.
    private enum ValueMarkers {
        UNCHANGED,
        REMOVE;
    }
    
    /**
     * Special object used by some setting methods to indicate that a value should be ignored.
     * <p>
     * See specific usages of this field to confirm where.
     */
    public static final Object UNCHANGED = ValueMarkers.UNCHANGED;

    /**
     * Special object used by some setting methods to indicate that a value should be removed.
     * <p>
     * See specific usages of this field to confirm where.
     */
    public static final Object REMOVE = ValueMarkers.REMOVE;

    /**
     * Invokes an {@link Effector} on multiple entities, with the named arguments from the parameters {@link Map}
     * using the context of the provided {@link Entity}.
     * <p>
     * Intended for use only from the callingEntity.
     * <p>
     * Returns a {@link ParallelTask} containing the results from each tasks invocation. Calling
     * {@link java.util.concurrent.Future#get() get()} on this will block until all tasks are complete,
     * and will throw an exception if any task resulted in an error.
     *
     * @return {@link ParallelTask} containing results from each invocation
     */
    public static <T> Task<List<T>> invokeEffectorList(Entity callingEntity, Iterable<? extends Entity> entitiesToCall,
            final Effector<T> effector, final Map<String,?> parameters) {
        // formulation is complicated, but it is building up a list of tasks, without blocking on them initially,
        // but ensuring that when the parallel task is gotten it does block on all of them

        if (entitiesToCall == null){
            entitiesToCall = ImmutableList.of();
        }

        List<TaskAdaptable<T>> tasks = Lists.newArrayList();

        for (final Entity entity : entitiesToCall) {
            tasks.add( Effectors.invocation(entity, effector, parameters) );
        }
        ParallelTask<T> invoke = new ParallelTask<T>(
                MutableMap.of(
                        "displayName", effector.getName()+" (parallel)",
                        "description", "Invoking effector \""+effector.getName()+"\" on "+tasks.size()+(tasks.size() == 1 ? " entity" : " entities"),
                        "tag", BrooklynTaskTags.tagForCallerEntity(callingEntity)),
                tasks);
        TaskTags.markInessential(invoke);
        return DynamicTasks.queueIfPossible(invoke).orSubmitAsync(callingEntity).asTask();
    }

    public static <T> Task<List<T>> invokeEffectorListWithMap(Entity callingEntity, Iterable<? extends Entity> entitiesToCall,
            final Effector<T> effector, final Map<String,?> parameters) {
        return invokeEffectorList(callingEntity, entitiesToCall, effector, parameters);
    }

    @SuppressWarnings("unchecked")
    public static <T> Task<List<T>> invokeEffectorListWithArgs(Entity callingEntity, Iterable<? extends Entity> entitiesToCall,
            final Effector<T> effector, Object ...args) {
        return invokeEffectorListWithMap(callingEntity, entitiesToCall, effector,
                // putting into a map, unnecessarily, as it ends up being the array again...
                EffectorUtils.prepareArgsForEffectorAsMapFromArray(effector, args));
    }

    public static <T> Task<List<T>> invokeEffectorList(Entity callingEntity, Iterable<? extends Entity> entitiesToCall,
            final Effector<T> effector) {
        return invokeEffectorList(callingEntity, entitiesToCall, effector, Collections.<String,Object>emptyMap());
    }

    public static <T> Task<T> invokeEffector(Entity callingEntity, Entity entityToCall,
            final Effector<T> effector, final Map<String,?> parameters) {
        Task<T> t = Effectors.invocation(entityToCall, effector, parameters).asTask();
        TaskTags.markInessential(t);

        // we pass to callingEntity for consistency above, but in exec-context it should be re-dispatched to targetEntity
        // reassign t as the return value may be a wrapper, if it is switching execution contexts; see submitInternal's javadoc
        t = ((EntityInternal)callingEntity).getExecutionContext().submit(
                MutableMap.of("tag", BrooklynTaskTags.tagForCallerEntity(callingEntity)), t);

        if (DynamicTasks.getTaskQueuingContext()!=null) {
            // include it as a child (in the gui), marked inessential, because the caller is invoking programmatically
            DynamicTasks.queue(t);
        }

        return t;
    }

    @SuppressWarnings("unchecked")
    public static <T> Task<T> invokeEffectorWithArgs(Entity callingEntity, Entity entityToCall,
            final Effector<T> effector, Object ...args) {
        return invokeEffector(callingEntity, entityToCall, effector,
                EffectorUtils.prepareArgsForEffectorAsMapFromArray(effector, args));
    }

    public static <T> Task<T> invokeEffector(Entity callingEntity, Entity entityToCall,
            final Effector<T> effector) {
        return invokeEffector(callingEntity, entityToCall, effector, Collections.<String,Object>emptyMap());
    }

    /** Invokes in parallel if multiple, but otherwise invokes the item directly. */
    public static Task<?> invokeEffector(Entity callingEntity, Iterable<? extends Entity> entitiesToCall,
            final Effector<?> effector, final Map<String,?> parameters) {
        if (Iterables.size(entitiesToCall)==1)
            return invokeEffector(callingEntity, entitiesToCall.iterator().next(), effector, parameters);
        else
            return invokeEffectorList(callingEntity, entitiesToCall, effector, parameters);
    }

    /** Invokes in parallel if multiple, but otherwise invokes the item directly. */
    public static Task<?> invokeEffector(Entity callingEntity, Iterable<? extends Entity> entitiesToCall,
            final Effector<?> effector) {
        return invokeEffector(callingEntity, entitiesToCall, effector, Collections.<String,Object>emptyMap());
    }

    /**
     * @deprecated since 0.7; instead use {@link Sanitizer#IS_SECRET_PREDICATE}
     */
    @Deprecated
    public static boolean isSecret(String name) {
        return Sanitizer.IS_SECRET_PREDICATE.apply(name);
    }

    public static boolean isTrivial(Object v) {
        if (v instanceof Maybe) {
            if (!((Maybe<?>)v).isPresent())
                return true;
            v = ((Maybe<?>) v).get();
        }

        return v==null || (v instanceof Map && ((Map<?,?>)v).isEmpty()) ||
                (v instanceof Collection && ((Collection<?>)v).isEmpty()) ||
                (v instanceof CharSequence&& ((CharSequence)v).length() == 0);
    }

    /**
     * @deprecated since 1.0.0; instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Iterable<? extends Entity> entities) {
        Dumper.dumpInfo(entities);
    }

    /**
     * @deprecated since 1.0.0; instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Entity e) {
        Dumper.dumpInfo(e);
    }
    /**
     * @deprecated since 1.0.0; instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Entity e, Writer out) throws IOException {
        Dumper.dumpInfo(e, out);
    }
    
    /**
     * @deprecated since 1.0.0; such fine-grained customization will be removed from API, instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Entity e, String currentIndentation, String tab) throws IOException {
        Dumper.dumpInfo(e, currentIndentation, tab);
    }
    
    /**
     * @deprecated since 1.0.0; such fine-grained customization will be removed from API, instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Entity e, Writer out, String currentIndentation, String tab) throws IOException {
        Dumper.dumpInfo(e, out, currentIndentation, tab);
    }

    /**
     * @deprecated since 1.0.0; instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Location loc) {
        Dumper.dumpInfo(loc);
    }
    
    /**
     * @deprecated since 1.0.0; instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Location loc, Writer out) throws IOException {
        Dumper.dumpInfo(loc, out);
    }
    
    /**
     * @deprecated since 1.0.0; such fine-grained customization will be removed from API, instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Location loc, String currentIndentation, String tab) throws IOException {
        Dumper.dumpInfo(loc, currentIndentation, tab);
    }
    
    /**
     * @deprecated since 1.0.0; such fine-grained customization will be removed from API, instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Location loc, Writer out, String currentIndentation, String tab) throws IOException {
        Dumper.dumpInfo(loc, out, currentIndentation, tab);
    }

    /**
     * @deprecated since 1.0.0; instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Task<?> t) {
        Dumper.dumpInfo(t);
    }
    
    /**
     * @deprecated since 1.0.0; instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Enricher enr) {
        Dumper.dumpInfo(enr);
    }
    
    /**
     * @deprecated since 1.0.0; instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Enricher enr, Writer out) throws IOException {
        Dumper.dumpInfo(enr, out);
    }
    
    /**
     * @deprecated since 1.0.0; such fine-grained customization will be removed from API, instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Enricher enr, String currentIndentation, String tab) throws IOException {
        Dumper.dumpInfo(enr, currentIndentation, tab);
    }
    
    /**
     * @deprecated since 1.0.0; such fine-grained customization will be removed from API, instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Enricher enr, Writer out, String currentIndentation, String tab) throws IOException {
        Dumper.dumpInfo(enr, out, currentIndentation, tab);
    }
    
    /**
     * @deprecated since 1.0.0; such fine-grained customization will be removed from API, instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Feed feed, String currentIndentation, String tab) throws IOException {
        Dumper.dumpInfo(feed, currentIndentation, tab);
    }
    
    /**
     * @deprecated since 1.0.0; such fine-grained customization will be removed from API, instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Feed feed, Writer out, String currentIndentation, String tab) throws IOException {
        Dumper.dumpInfo(feed, out, currentIndentation, tab);
    }

    /**
     * @deprecated since 1.0.0; instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Policy pol) {
        Dumper.dumpInfo(pol);
    }
    
    /**
     * @deprecated since 1.0.0; instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Policy pol, Writer out) throws IOException {
        Dumper.dumpInfo(pol, out);
    }
    
    /**
     * @deprecated since 1.0.0; such fine-grained customization will be removed from API, instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Policy pol, String currentIndentation, String tab) throws IOException {
        Dumper.dumpInfo(pol, currentIndentation, tab);
    }
    
    /**
     * @deprecated since 1.0.0; such fine-grained customization will be removed from API, instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Policy pol, Writer out, String currentIndentation, String tab) throws IOException {
        Dumper.dumpInfo(pol, out, currentIndentation, tab);
    }

    /**
     * Sorts the sensors into alphabetical order by name.
     * @deprecated since 1.0.0; viewed as unnecessary in the API, will be removed
     */
    @Deprecated
    public static List<Sensor<?>> sortSensors(Set<Sensor<?>> sensors) {
        return Dumper.sortSensors(sensors);
    }

    /**
     * Sorts the sensors into alphabetical order by name.
     * @deprecated since 1.0.0; viewed as unnecessary in the API, will be removed
     */
    @Deprecated
    public static List<ConfigKey<?>> sortConfigKeys(Set<ConfigKey<?>> configs) {
        return Dumper.sortConfigKeys(configs);
    }

    /**
     * Sorts the sensors into alphabetical order by name.
     * @deprecated since 1.0.0; viewed as unnecessary in the API, will be removed
     */
    @Deprecated
    public static <T> Map<String, T> sortMap(Map<String, T> map) {
        return Dumper.sortMap(map);
    }

    /**
     * Returns true if the given descendant includes the given ancestor in its chain.
     * Does <i>NOT</i> count a node as its ancestor.
     */
    public static boolean isAncestor(Entity descendant, Entity potentialAncestor) {
        Entity ancestor = descendant.getParent();
        while (ancestor != null) {
            if (ancestor.equals(potentialAncestor)) return true;
            ancestor = ancestor.getParent();
        }
        return false;
    }

    /**
     * Checks whether the descendants of the given ancestor contains the given potentialDescendant.
     * <p>
     * In this test, unlike in {@link #descendants(Entity)}, an entity is not counted as a descendant.
     * note, it is usually preferred to use isAncestor() and swap the order, it is a cheaper method.
     */
    public static boolean isDescendant(Entity ancestor, Entity potentialDescendant) {
        Set<Entity> inspected = Sets.newLinkedHashSet();
        Stack<Entity> toinspect = new Stack<Entity>();
        toinspect.add(ancestor);

        while (!toinspect.isEmpty()) {
            Entity e = toinspect.pop();
            if (e.getChildren().contains(potentialDescendant)) {
                return true;
            }
            inspected.add(e);
            toinspect.addAll(e.getChildren());
            toinspect.removeAll(inspected);
        }

        return false;
    }

    /**
     * Return all descendants of given entity matching the given predicate and optionally the entity itself.
     * 
     * @see EntityPredicates
     */
    @SuppressWarnings("unused")
    public static Iterable<Entity> descendants(Entity root, Predicate<? super Entity> matching, boolean includeSelf) {
        if (false) {
            // Keeping this unused code, so that anonymous inner class numbers don't change!
            Iterable<Entity> descs = Iterables.concat(Iterables.transform(root.getChildren(), new Function<Entity,Iterable<Entity>>() {
                @Override
                public Iterable<Entity> apply(Entity input) {
                    return descendants(input);
                }
            }));
            return Iterables.filter(Iterables.concat(descs, Collections.singleton(root)), matching);
        }
        if (includeSelf) {
            return descendantsAndSelf(root, matching);
        } else {
            return Iterables.filter(descendantsWithoutSelf(root), matching);
        }
    }

    /**
     * Returns the entities matching the given predicate.
     *
     * @see #descendants(Entity, Predicate, boolean)
     */
    public static Iterable<Entity> descendantsAndSelf(Entity root, Predicate<? super Entity> matching) {
        return Iterables.filter(descendantsAndSelf(root), matching);
    }

    /**
     * Returns the entity, its children, and all its children, and so on.
     *
     * @see #descendants(Entity, Predicate, boolean)
     */
    public static Iterable<Entity> descendantsAndSelf(Entity root) {
        Set<Entity> result = Sets.newLinkedHashSet();
        result.add(root);
        descendantsWithoutSelf(root, result, false);
        return result;
    }

    /**
     * Returns the entity's children, its children's children, and so on.
     *
     * @see #descendants(Entity, Predicate, boolean)
     */
    public static Iterable<Entity> descendantsWithoutSelf(Entity root) {
        Set<Entity> result = Sets.newLinkedHashSet();
        descendantsWithoutSelf(root, result, false);
        return result;
    }

    public static Iterable<Entity> descendantsAndMembersWithoutSelf(Entity root) {
        Set<Entity> result = Sets.newLinkedHashSet();
        descendantsWithoutSelf(root, result, true);
        return result;
    }

    /**
     * Return all descendants of given entity of the given type, potentially including the given root.
     *
     * @see #descendants(Entity)
     * @see Iterables#filter(Iterable, Class)
     */
    public static <T extends Entity> Iterable<T> descendantsAndSelf(Entity root, Class<T> ofType) {
        return Iterables.filter(descendantsAndSelf(root), ofType);
    }

    /**
     * Return all descendants of given entity of the given type, potentially including the given root.
     *
     * @see #descendants(Entity)
     * @see Iterables#filter(Iterable, Class)
     */
    public static <T extends Entity> Iterable<T> descendantsAndMembersAndSelf(Entity root, Class<T> ofType) {
        return Iterables.filter(descendantsAndSelf(root), ofType);
    }

    /** Returns the entity, its parent, its parent, and so on. */
    @SuppressWarnings("unused")
    public static Iterable<Entity> ancestorsAndSelf(final Entity root) {
        if (false) {
            // Keeping this unused code, so that anonymous inner class numbers don't change!
            return new Iterable<Entity>() {
                @Override
                public Iterator<Entity> iterator() {
                    return new Iterator<Entity>() {
                        Entity next = root;
                        @Override
                        public boolean hasNext() {
                            return next!=null;
                        }
                        @Override
                        public Entity next() {
                            Entity result = next;
                            next = next.getParent();
                            return result;
                        }
                        @Override
                        public void remove() {
                            throw new UnsupportedOperationException();
                        }
                    };
                }
            };
        }
        Set<Entity> result = Sets.newLinkedHashSet();
        Entity current = root;
        while (current != null) {
            result.add(current);
            current = current.getParent();
        }
        return result;
    }

    /** Returns the entity's parent, its parent's parent, and so on. */
    public static Iterable<Entity> ancestorsWithoutSelf(Entity root) {
        Set<Entity> result = Sets.newLinkedHashSet();
        Entity parent = (root != null) ? root.getParent() : null;
        while (parent != null) {
            result.add(parent);
            parent = parent.getParent();
        }
        return result;
    }

    /**
     * Side-effects {@code result} to return descendants (not including {@code root}).
     */
    private static void descendantsWithoutSelf(Entity root, Collection<Entity> result, boolean includeMembers) {
        Stack<Entity> tovisit = new Stack<Entity>();
        Set<Entity> visited = MutableSet.of();
        tovisit.add(root);

        while (!tovisit.isEmpty()) {
            Entity e = tovisit.pop();
            if (visited.add(e)) {
                result.addAll(e.getChildren());
                tovisit.addAll(e.getChildren());
                if (includeMembers && e instanceof Group) {
                    result.addAll(((Group) e).getMembers());
                    tovisit.addAll(((Group) e).getMembers());
                }
            }
        }
    }
    
    /**
     * @deprecated since 0.10.0; see {@link #descendantsAndSelf(Entity, Predicate)}
     */
    @Deprecated
    public static Iterable<Entity> descendants(Entity root, Predicate<? super Entity> matching) {
        return descendantsAndSelf(root, matching);
    }

    /**
     * @deprecated since 0.10.0; see {@link #descendantsAndSelf(Entity)}
     */
    @Deprecated
    public static Iterable<Entity> descendants(Entity root) {
        return descendantsAndSelf(root);
    }

    /**
     * @deprecated since 0.10.0; see {@link #descendantsAndSelf(Entity)}
     */
    @Deprecated
    public static <T extends Entity> Iterable<T> descendants(Entity root, Class<T> ofType) {
        return descendantsAndSelf(root, ofType);
    }

    /**
     * @deprecated since 0.10.0; see {@link #ancestorsAndSelf(Entity)}
     */
    @Deprecated
    public static Iterable<Entity> ancestors(final Entity root) {
        return ancestorsAndSelf(root);
    }

    /**
     * Registers a {@link BrooklynShutdownHooks#invokeStopOnShutdown(Entity)} to shutdown this entity when the JVM exits.
     * (Convenience method located in this class for easy access.)
     */
    public static void invokeStopOnShutdown(Entity entity) {
        BrooklynShutdownHooks.invokeStopOnShutdown(entity);
    }

    /** convenience for starting an entity, esp a new Startable instance which has been created dynamically
     * (after the application is started) */
    public static void start(Entity e, Collection<? extends Location> locations) {
        if (!isManaged(e) && !manage(e)) {
            log.warn("Using deprecated discouraged mechanism to start management -- Entities.start(Application, Locations) -- caller should create and use the preferred management context");
            startManagement(e);
        }
        if (e instanceof Startable) Entities.invokeEffector(e, e, Startable.START,
                MutableMap.of("locations", locations)).getUnchecked();
    }

    public static void destroy(Entity e) {
        destroy(e, false);
    }
    /**
     * Attempts to stop, destroy, and unmanage the given entity.
     * <p>
     * Actual actions performed will depend on the entity type and its current state.
     */
    public static void destroy(Entity e, boolean unmanageOnErrors) {
        if (isManaged(e)) {
            if (isReadOnly(e)) {
                unmanage(e);
                log.debug("destroyed and unmanaged read-only copy of "+e);
            } else {
                List<Exception> errors = MutableList.of();

                try {
                    if (e instanceof Startable) Entities.invokeEffector(e, e, Startable.STOP).getUnchecked();
                } catch (Exception error) {
                    Exceptions.propagateIfFatal(error);
                    if (!unmanageOnErrors) Exceptions.propagate(error);
                    errors.add(error);
                }
                
                // if destroying gracefully we might also want to do this (currently gets done by GC after unmanage,
                // which is good enough for leaks, but not sure if that's ideal for subscriptions etc)
//                ((LocalEntityManager)e.getApplication().getManagementContext().getEntityManager()).stopTasks(e, null);

                try {
                    if (e instanceof EntityInternal) ((EntityInternal) e).destroy();
                } catch (Exception error) {
                    Exceptions.propagateIfFatal(error);
                    if (!unmanageOnErrors) Exceptions.propagate(error);
                    errors.add(error);
                }

                try {
                    unmanage(e);
                } catch (Exception error) {
                    Exceptions.propagateIfFatal(error);
                    if (!unmanageOnErrors) Exceptions.propagate(error);
                    errors.add(error);
                }

                if (!errors.isEmpty()) {
                    log.warn("destroyed and unmanaged "+e+", with errors; mgmt now "+
                            (e.getApplicationId()==null ? "(no app)" : e.getApplication().getManagementContext())+" - managed? "+isManaged(e)+"; errors: "+errors);
                    throw Exceptions.propagate("Errors destroying "+e, errors);
                }

                log.debug("destroyed and unmanaged "+e+"; mgmt now "+
                    (e.getApplicationId()==null ? "(no app)" : e.getApplication().getManagementContext())+" - managed? "+isManaged(e));
            }
        } else {
            log.debug("skipping destroy of "+e+": not managed");
        }
    }

    /** Same as {@link #destroy(Entity)} but catching all errors. */
    public static void destroyCatching(Entity entity) {
        destroyCatching(entity, false);
    }
    public static void destroyCatching(Entity entity, boolean unmanageOnErrors) {
        try {
            destroy(entity, unmanageOnErrors);
        } catch (Exception e) {
            log.warn("ERROR destroying "+entity+" (ignoring): "+e, e);
            Exceptions.propagateIfFatal(e);
        }
    }

    /** Destroys the given location. */
    public static void destroy(Location loc) {
        // TODO unmanage the location, if possible?
        if (loc instanceof Closeable) {
            Streams.closeQuietly((Closeable)loc);
            log.debug("closed "+loc);
        }
    }

    /** Same as {@link #destroy(Location)} but catching all errors. */
    public static void destroyCatching(Location loc) {
        try {
            destroy(loc);
        } catch (Exception e) {
            log.warn("ERROR destroying "+loc+" (ignoring): "+e, e);
            Exceptions.propagateIfFatal(e);
        }
    }

    /**
     * Stops, destroys, and unmanages all apps in the given context, and then terminates the management context.
     * 
     * Apps will be stopped+destroyed+unmanaged concurrently, waiting for all to complete.
     */
    public static void destroyAll(final ManagementContext mgmt) {
        final int MAX_THREADS = 100;
        
        if (mgmt instanceof NonDeploymentManagementContext) {
            // log here because it is easy for tests to destroyAll(app.getMgmtContext())
            // which will *not* destroy the mgmt context if the app has been stopped!
            log.warn("Entities.destroyAll invoked on non-deployment "+mgmt+" - not likely to have much effect! " +
                    "(This usually means the mgmt context has been taken from an entity that has been destroyed. " +
                    "To destroy other things on the management context ensure you keep a handle to the context " +
                    "before the entity is destroyed, such as by creating the management context first.)");
        }
        if (!mgmt.isRunning()) return;
        
        ListeningExecutorService executor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(MAX_THREADS));
        List<ListenableFuture<?>> futures = Lists.newArrayList();
        final AtomicReference<Exception> error = Atomics.newReference();
        try {
            log.debug("destroying all apps in "+mgmt+": "+mgmt.getApplications());
            for (final Application app: mgmt.getApplications()) {
                futures.add(executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        log.debug("destroying app "+app+" (managed? "+isManaged(app)+"; mgmt is "+mgmt+")");
                        try {
                            destroy(app, true);
                            log.debug("destroyed app "+app+"; mgmt now "+mgmt);
                        } catch (Exception e) {
                            log.warn("problems destroying app "+app+" (mgmt now "+mgmt+", will rethrow at least one exception): "+e);
                            log.debug("Problem was:", e);
                            error.compareAndSet(null, e);
                        }
                    }}));
            }
            Futures.allAsList(futures).get();
            
            for (Location loc : mgmt.getLocationManager().getLocations()) {
                destroyCatching(loc);
            }
            if (mgmt instanceof ManagementContextInternal) {
                ((ManagementContextInternal)mgmt).terminate();
            }
            if (error.get() != null) throw Exceptions.propagate(error.get());
        } catch (Exception e) {
            if (!mgmt.isRunning()) {
                // we've checked this above so it would only happen if a different thread stopped it;
                // this does happen sometimes e.g. in CliTest where the server shutdown occurs concurrently
                log.debug("Destroying apps gave an error, but mgmt context was concurrently stopped so not really a problem; swallowing (unless fatal): "+e);
                Exceptions.propagateIfFatal(e);
            } else {
                throw Exceptions.propagate(e);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /** Same as {@link #destroyAll(ManagementContext)} but catching all errors */
    public static void destroyAllCatching(ManagementContext mgmt) {
        try {
            destroyAll(mgmt);
        } catch (Exception e) {
            log.warn("ERROR destroying "+mgmt+" (ignoring): "+e, e);
            Exceptions.propagateIfFatal(e);
        }
    }

    /** As {@link #isManagedOrReadOnly(Entity)}.  Consider using {@link #isManagedActive(Entity)} instead. */
    public static boolean isManaged(Entity e) {
        return isManagedOrReadOnly(e);
    }

    /** If entity is under management and the management node is the primary for this entity, i.e. not read-only. */
    public static boolean isManagedActive(Entity e) {
        return ((EntityInternal)e).getManagementSupport().isActive() && ((EntityInternal)e).getManagementContext().isRunning() && !isReadOnly(e);
    }

    /** If entity is under management or coming up and the management node is the primary for this entity, i.e. not read-only. */
    public static boolean isManagedActiveOrComingUp(Entity e) {
        return (isManagedActive(e) || !((EntityInternal)e).getManagementSupport().wasDeployed()) && !isReadOnly(e);
    }

    /** If entity is under management either as primary or in read-only (hot-standby) state. */
    public static boolean isManagedOrReadOnly(Entity e) {
        return ((EntityInternal)e).getManagementSupport().isActive() && ((EntityInternal)e).getManagementContext().isRunning();
    }

    public static boolean isNoLongerManaged(Entity e) {
        return isNoLongerRunning(((EntityInternal)e).getManagementContext()) || ((EntityInternal)e).getManagementSupport().isNoLongerManaged();
    }

    public static boolean isUnmanaging(Entity e) {
        return ((EntityInternal)e).getManagementSupport().isUnmanaging();
    }

    public static boolean isUnmanagingOrNoLongerManaged(Entity e) {
        return isNoLongerManaged(e) || isUnmanaging(e);
    }

    public static boolean isNoLongerRunning(ManagementContext mgmt) {
        return !mgmt.isRunning(); // see notes at mgmt.isRunning(), it is misnamed, as it is always true until terminate is called
    }

    /** if entity is managed, but in a read-only state */
    public static boolean isReadOnly(Entity e) {
        return Boolean.TRUE.equals( ((EntityInternal)e).getManagementSupport().isReadOnlyRaw() );
    }

    /** Unwraps a proxy to retrieve the real item, if available.
     * <p>
     * Only intended for use in tests and occasional internal usage, e.g. persistence.
     * For normal operations, callers should ensure the method is available on an interface and accessed via the proxy. */
    @Beta @VisibleForTesting
    public static AbstractEntity deproxy(Entity e) {
        return (AbstractEntity) deproxy((BrooklynObject) e);
    }

    @Beta @VisibleForTesting
    public static BrooklynObject deproxy(BrooklynObject e) {
        if (!(Proxy.isProxyClass(e.getClass()))) {
            // there are a few valid cases where callers might want to deproxy, so don't warn
//            log.warn("Attempt to deproxy non-proxy "+e, new Throwable("Location of attempt to deproxy non-proxy "+e));
            return e;
        }
        return ((AbstractBrooklynObjectProxyImpl<BrooklynObject>)Proxy.getInvocationHandler(e)).getDelegate();
    }
    
    /** 
     * Returns the proxy form (if available) of the entity. If already a proxy, returns unmodified.
     * 
     * If null is passed in, then null is returned.
     * 
     * For legacy entities (that did not use {@link EntitySpec} or YAML for creation), the
     * proxy may not be avilable; in which case the concrete class passed in will be returned.
     */
    @Beta
    @SuppressWarnings("unchecked")
    public static <T extends Entity> T proxy(T e) {
        return (e == null) ? null : e instanceof Proxy ? e : (T) ((AbstractEntity)e).getProxyIfAvailable();
    }
    
    /**
     * Brings this entity under management only if its ancestor is managed.
     * <p>
     * Returns true if successful, otherwise returns false in the expectation that the ancestor
     * will become managed, or throws exception if it has no parent or a non-application root.
     *
     * @throws IllegalStateException if {@literal e} is an {@link Application}.
     * @see #startManagement(Entity)
     * 
     * @deprecated since 0.9.0; entities are automatically managed when created via {@link Entity#addChild(EntitySpec)},
     *             or with {@link EntityManager#createEntity(EntitySpec)} (it is strongly encouraged to include the parent
     *             if using the latter for anything but a top-level app).
     */
    @Deprecated
    public static boolean manage(Entity e) {
        if (Entities.isManaged(e)) {
            return true; // no-op
        }
        
        log.warn("Deprecated use of Entities.manage(Entity), for unmanaged entity "+e);

        Entity o = e.getParent();
        Entity eum = e; // Highest unmanaged ancestor
        if (o==null) throw new IllegalArgumentException("Can't manage "+e+" because it is an orphan");
        while (o.getParent()!=null) {
            if (!isManaged(o)) eum = o;
            o = o.getParent();
        }
        if (isManaged(o)) {
            ManagementContextInternal mgmt = (ManagementContextInternal) ((EntityInternal) o).getManagementContext();
            premanageRecursively(mgmt, eum);
            mgmt.getEntityManager().manage(eum);
            return true;
        }
        if (!(o instanceof Application)) {
            throw new IllegalStateException("Can't manage "+e+" because it is not rooted at an application");
        }
        return false;
    }

    /**
     * Brings this entity under management, creating a local management context if necessary,
     * assuming root is an application.
     * <p>
     * Returns existing management context if there is one (non-deployment) or a new local management
     * context if not, or throws an exception if root is not an application. Callers are recommended
     * to use {@link #manage(Entity)} instead unless they know a plain-vanilla non-root management
     * context is sufficient e.g. in tests.
     * <p>
     * <b>NOTE</b> This method may change, but is provided as a stop-gap to prevent ad-hoc things
     * being done in the code which are even more likely to break!
     * 
     * @deprecated since 0.9.0; entities are automatically managed when created via {@link Entity#addChild(EntitySpec)},
     *             or with {@link EntityManager#createEntity(EntitySpec)}.
     */
    @Deprecated
    @Beta
    public static ManagementContext startManagement(Entity e) {
        log.warn("Deprecated use of Entities.startManagement(Entity), for entity "+e);
        
        Entity o = e;
        Entity eum = e; // Highest unmanaged ancestor
        while (o.getParent()!=null) {
            if (!isManaged(o)) eum = o;
            o = o.getParent();
        }
        if (isManaged(o)) {
            ManagementContext mgmt = ((EntityInternal)o).getManagementContext();
            mgmt.getEntityManager().manage(eum);
            return mgmt;
        }
        if (!(o instanceof Application))
            throw new IllegalStateException("Can't manage "+e+" because it is not rooted at an application");
        
        log.warn("Deprecated invocation of startManagement for "+e+" without a management context present; "
            + "a new local management context is being created! (Not recommended unless you really know what you are doing.)");
        ManagementContext mgmt = new LocalManagementContext();
        mgmt.getEntityManager().manage(o);
        return mgmt;
    }

    /**
     * Starts managing the given (unmanaged) app, using the given management context.
     *
     * @see #startManagement(Entity)
     * 
     * @deprecated since 0.9.0; entities are automatically managed when created with 
     *             {@link EntityManager#createEntity(EntitySpec)}. For top-level apps, use code like
     *             {@code managementContext.getEntityManager().createEntity(EntitySpec.create(...))}.
     */
    @Deprecated
    public static ManagementContext startManagement(Application app, ManagementContext mgmt) {
        log.warn("Deprecated use of Entities.startManagement(Application, ManagementContext), for app "+app);
        
        if (isManaged(app)) {
            if (app.getManagementContext() == mgmt) {
                // no-op; app was presumably auto-managed
                return mgmt;
            } else {
                throw new IllegalStateException("Application "+app+" is already managed by "+app.getManagementContext()+", so cannot be managed by "+mgmt);
            }
        }

        premanageRecursively((ManagementContextInternal)mgmt, app);

        mgmt.getEntityManager().manage(app);
        return mgmt;
    }

    private static void premanageRecursively(ManagementContextInternal mgmt, Entity e) {
        if (Entities.isUnmanagingOrNoLongerManaged(e) || Entities.isManagedActive(e)) {
            log.warn("Skipping premanagement for "+e+", as it has some form of known management");
            // skip for active and unmanaged entities
            return;
        }
        mgmt.prePreManage(e);
        e.getChildren().forEach(e2 -> premanageRecursively(mgmt, e2));
    }

    /**
     * Starts managing the given (unmanaged) app, setting the given brooklyn properties on the new
     * management context.
     *
     * @see #startManagement(Entity)
     * 
     * @deprecated since 0.9.0; entities are automatically managed when created via {@link Entity#addChild(EntitySpec)},
     *             or with {@link EntityManager#createEntity(EntitySpec)}. For top-level apps, use code like
     *             {@code managementContext.getEntityManager().createEntity(EntitySpec.create(...))}.
     */
    @Deprecated
    public static ManagementContext startManagement(Application app, BrooklynProperties props) {
        log.warn("Deprecated use of Entities.startManagement(Application, BrooklynProperties), for app "+app);
        
        if (isManaged(app)) {
            throw new IllegalStateException("Application "+app+" is already managed, so can't set brooklyn properties");
        }
        ManagementContext mgmt = new LocalManagementContext(props);
        mgmt.getEntityManager().manage(app);
        return mgmt;
    }

    public static ManagementContext newManagementContext() {
        return new LocalManagementContext();
    }

    public static ManagementContext newManagementContext(BrooklynProperties props) {
        return new LocalManagementContext(props);
    }

    public static ManagementContext newManagementContext(Map<?,?> props) {
        return new LocalManagementContext( BrooklynProperties.Factory.newEmpty().addFromMap(props));
    }

    public static ManagementContext getManagementContext(Entity entity) {
        return ((EntityInternal) entity).getManagementContext();
    }

    public static void unmanage(Entity entity) {
        if (((EntityInternal)entity).getManagementSupport().isDeployed()) {
            ((EntityInternal)entity).getManagementContext().getEntityManager().unmanage(entity);
        }
    }

    public static DownloadResolver newDownloader(EntityDriver driver) {
        return newDownloader(driver, ImmutableMap.<String,Object>of());
    }

    public static DownloadResolver newDownloader(EntityDriver driver, Map<String,?> properties) {
        EntityInternal internal = (EntityInternal) driver.getEntity();
        return internal.getManagementContext().getEntityDownloadsManager().newDownloader(driver, properties);
    }

    public static DownloadResolver newDownloader(EntityDriver driver, String addon) {
        return newDownloader(driver, addon, ImmutableMap.<String,Object>of());
    }

    public static DownloadResolver newDownloader(EntityDriver driver, String addon, Map<String,?> properties) {
        EntityInternal internal = (EntityInternal) driver.getEntity();
        return internal.getManagementContext().getEntityDownloadsManager().newDownloader(driver, addon, properties);
    }

    public static <T> Supplier<T> attributeSupplier(Entity entity, AttributeSensor<T> sensor) {
        return EntityAndAttribute.create(entity, sensor);
    }

    public static <T> Supplier<T> attributeSupplier(EntityAndAttribute<T> tuple) { return tuple; }

    public static <T> Supplier<T> attributeSupplierWhenReady(EntityAndAttribute<T> tuple) {
        return attributeSupplierWhenReady(tuple.getEntity(), tuple.getAttribute());
    }

    @SuppressWarnings({ "unchecked", "serial" })
    public static <T> Supplier<T> attributeSupplierWhenReady(final Entity entity, final AttributeSensor<T> sensor) {
        final Task<T> task = DependentConfiguration.attributeWhenReady(entity, sensor);
        return new Supplier<T>() {
            @Override public T get() {
                try {
                    TypeToken<T> type = new TypeToken<T>(sensor.getType()) {};
                    return Tasks.resolveValue(task, type, ((EntityInternal) entity).getExecutionContext(), "attributeSupplierWhenReady");
                } catch (Exception e) {
                    throw Exceptions.propagate(e);
                }
            }
        };
    }

    /**
     * @since 0.6.0 Added only for backwards compatibility, where locations are being created directly.
     * @deprecated in 0.6.0; use {@link LocationManager#createLocation(LocationSpec)} instead
     */
    @Deprecated
    public static void manage(Location loc, ManagementContext managementContext) {
        Locations.manage(loc, managementContext);
    }

    /** Fails-fast if value of the given key is null or unresolveable. */
    public static String getRequiredUrlConfig(Entity entity, ConfigKey<String> urlKey) {
        String url = entity.getConfig(urlKey);
        Preconditions.checkNotNull(url, "Key %s on %s should not be null", urlKey, entity);
        if (!ResourceUtils.create(entity).doesUrlExist(url)) {
            throw new IllegalStateException(String.format("Key %s on %s contains unavailable URL %s", urlKey, entity, url));
        }
        return url;
    }

    /** @see #getRequiredUrlConfig(Entity, ConfigKey) */
    public static String getRequiredUrlConfig(Entity entity, HasConfigKey<String> urlKey) {
        return getRequiredUrlConfig(entity, urlKey.getConfigKey());
    }

    /** Fails-fast if value of the given URL is null or unresolveable. */
    public static String checkRequiredUrl(Entity entity, String url) {
        Preconditions.checkNotNull(url, "url");
        if (!ResourceUtils.create(entity).doesUrlExist(url)) {
            throw new IllegalStateException(String.format("URL %s on %s is unavailable", url, entity));
        }
        return url;
    }

    /**
     * Submits a {@link TaskFactory} to construct its task at the entity (in a precursor task) and then to submit it.
     * <p>
     * Important if task construction relies on an entity being in scope (in tags, via {@link BrooklynTaskTags})
     */
    public static <T extends TaskAdaptable<?>> T submit(final Entity entity, final TaskFactory<T> taskFactory) {
        // TODO it is messy to have to do this, but not sure there is a cleaner way :(
        final Semaphore s = new Semaphore(0);
        final AtomicReference<T> result = new AtomicReference<T>();
        final ExecutionContext executionContext = ((EntityInternal)entity).getExecutionContext();
        executionContext.execute(new Runnable() {
            // TODO could give this task a name, like "create task from factory"
            @Override
            public void run() {
                T t = taskFactory.newTask();
                result.set(t);
                s.release();
            }
        });
        try {
            s.acquire();
        } catch (InterruptedException e) {
            throw Exceptions.propagate(e);
        }
        executionContext.submit(result.get().asTask());
        return result.get();
    }

    /**
     * Submits a task to run at the entity.
     * 
     * @return the task passed in, for fluency
     */
    public static <T extends TaskAdaptable<?>> T submit(final Entity entity, final T task) {
        final ExecutionContext executionContext = ((EntityInternal)entity).getExecutionContext();
        executionContext.submit(task.asTask());
        return task;
    }

    /** Logs a warning if an entity has a value for a config key. */
    public static void warnOnIgnoringConfig(Entity entity, ConfigKey<?> key) {
        if (((EntityInternal)entity).config().getRaw(key).isPresentAndNonNull())
            log.warn("Ignoring "+key+" set on "+entity+" ("+entity.getConfig(key)+")");
    }

    /** Waits until the passed entity satisfies the supplied predicate. */
    public static void waitFor(Entity entity, Predicate<Entity> condition, Duration timeout) {
        try {
            String description = "Waiting for " + condition + " on " + entity;
            Tasks.setBlockingDetails(description);

            Repeater repeater = Repeater.create(description)
                .until(isSatisfied(entity, condition))
                .limitTimeTo(timeout)
                .backoffTo(Duration.ONE_SECOND)
                .rethrowException();

            if (!repeater.run()) {
                throw new IllegalStateException("Timeout waiting for " + condition + " on " + entity);
            }

        } finally {
            Tasks.resetBlockingDetails();
        }

        log.debug("Detected {} for {}", condition, entity);
    }

    /** Waits until {@link Startable#SERVICE_UP} is true. */
    public static void waitForServiceUp(final Entity entity, Duration timeout) {
        waitFor(entity, EntityPredicates.isServiceUp(), timeout);
    }
    public static void waitForServiceUp(final Entity entity, long duration, TimeUnit units) {
        waitForServiceUp(entity, Duration.of(duration, units));
    }
    public static void waitForServiceUp(final Entity entity) {
        Duration timeout = entity.getConfig(BrooklynConfigKeys.START_TIMEOUT);
        waitForServiceUp(entity, timeout);
    }

    /**
     * Convenience for creating and submitted a given shell command against the given mgmt context,
     * primarily intended for use in the groovy GUI console.
     */
    @Beta
    public static ProcessTaskWrapper<Integer> shell(ManagementContext mgmt, String command) {
        ProcessTaskWrapper<Integer> t = SystemTasks.exec(command).newTask();
        mgmt.getServerExecutionContext().submit(t).getUnchecked();
        System.out.println(t.getStdout());
        System.err.println(t.getStderr());
        return t;
    }

    public static Entity catalogItemScopeRoot(Entity entity) {
        Entity root = entity;

        Integer depth = BrooklynTags.getDepthInAncestorTag(root.tags().getTags());
        if (depth!=null && depth>0) {
            while (depth>0) {
                root = root.getParent();
                depth--;
            }
            return root;
        }

        while (root.getParent() != null &&
                root != root.getParent() &&
                Objects.equal(root.getParent().getCatalogItemId(), root.getCatalogItemId())) {
            root = root.getParent();
        }
        return root;
    }

    public static Set<Location> getAllInheritedLocations(Entity entity) {
        Set<Location> result = MutableSet.of();
        while (entity!=null) {
            result.addAll(entity.getLocations());
            entity = entity.getParent();
        }
        return result;
    }
    
}
