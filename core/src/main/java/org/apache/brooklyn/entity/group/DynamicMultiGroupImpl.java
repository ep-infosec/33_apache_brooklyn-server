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
package org.apache.brooklyn.entity.group;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.*;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.entity.Group;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.core.entity.BrooklynConfigKeys;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic.ServiceProblemsLogic;
import org.apache.brooklyn.core.workflow.steps.CustomWorkflowStep;
import org.apache.brooklyn.feed.function.FunctionFeed;
import org.apache.brooklyn.feed.function.FunctionPollConfig;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.collections.MutableSet;
import org.apache.brooklyn.util.core.flags.TypeCoercions;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class DynamicMultiGroupImpl extends DynamicGroupImpl implements DynamicMultiGroup {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicMultiGroupImpl.class);

    /**
     * {@link Function} for deriving bucket names from a sensor value.
     */
    public static class BucketFromAttribute implements Function<Entity, String> {
        private final AttributeSensor<?> sensor;
        private final String defaultValue;

        public BucketFromAttribute(AttributeSensor<?> sensor, String defaultValue) {
            this.sensor = Preconditions.checkNotNull(sensor, "sensor");
            this.defaultValue = defaultValue;
        }

        @Override
        public String apply(@Nullable Entity input) {
            Object value = input.getAttribute(sensor);
            if (value == null) {
                return defaultValue;
            } else {
                return String.valueOf(value);
            }
        };
    }

    /**
     * Convenience factory method for the common use-case of deriving the bucket directly from a sensor value.
     *
     * @see DynamicMultiGroup#BUCKET_FUNCTION
     */
    public static Function<Entity, String> bucketFromAttribute(final AttributeSensor<?> sensor, final String defaultValue) {
        return new BucketFromAttribute(sensor, defaultValue);
    }

    /**
     * Convenience factory method for the common use-case of deriving the bucket directly from a sensor value.
     *
     * @see DynamicMultiGroup#BUCKET_FUNCTION
     */
    public static Function<Entity, String> bucketFromAttribute(final AttributeSensor<?> sensor) {
        return bucketFromAttribute(sensor, null);
    }

    private transient FunctionFeed rescan;

    @Override
    public void init() {
        super.init();
        sensors().set(BUCKETS, ImmutableMap.<String, BasicGroup>of());
        connectScanner();
    }

    @Override
    protected void initEnrichers() {
        super.initEnrichers();
        // check states and upness separately so they can be individually replaced if desired
        // problem if any children or members are on fire
        enrichers().add(ServiceStateLogic.newEnricherFromChildrenState()
                .checkChildrenOnly()
                .requireRunningChildren(getConfig(RUNNING_QUORUM_CHECK))
                .suppressDuplicates(true));
        // defaults to requiring at least one member or child who is up
        enrichers().add(ServiceStateLogic.newEnricherFromChildrenUp()
                .checkChildrenOnly()
                .requireUpChildren(getConfig(UP_QUORUM_CHECK))
                .suppressDuplicates(true));
    }
    
    private void connectScanner() {
        Long interval = getConfig(RESCAN_INTERVAL);
        if (interval != null && interval > 0L) {
            rescan = FunctionFeed.builder()
                    .uniqueTag("dynamic-multi-group-scanner")
                    .entity(this)
                    .poll(new FunctionPollConfig<Object, Void>(RESCAN)
                            .period(interval, TimeUnit.SECONDS)
                            .callable(new Callable<Void>() {
                                    @Override
                                    public Void call() throws Exception {
                                        rescanEntities();
                                        return null;
                                    }
                                }))
                    .build(true);
        }
    }

    @Override
    public void rebind() {
        super.rebind();

        if (rescan == null) {
            // The rescan can (in a different thread) cause us to remove the empty groups - i.e. remove 
            // it as a child, and unmanage it. That is dangerous during rebind, because the rebind-thread 
            // may concurrently (or subsequently) be initialising that child entity. It has caused 
            // rebind errors where the child's entity-rebind complains in setParent() that it was
            // "previouslyOwned". Therefore we defer registering/executing our scanner until rebind is
            // complete, so all entities are reconstituted.
            // We don't worry about other managementNodeStates, such as standby: if we were told to
            // rebind then we are free to fully initialise ourselves. But we do double-check that we
            // are still managed before trying to execute.
            
            getExecutionContext().execute(new Runnable() {
                @Override public void run() {
                    LOG.debug("Deferring scanner for {} until management context initialisation complete", DynamicMultiGroupImpl.this);
                    while (!isRebindComplete()) {
                        Time.sleep(100); // avoid thrashing
                    }
                    LOG.debug("Connecting scanner for {}", DynamicMultiGroupImpl.this);
                    connectScanner();
                }
                private boolean isRebindComplete() {
                    // TODO Want to determine if finished rebinding (either success or fail is fine).
                    // But not a clean way to do this that works for both unit tests and live server?!
                    //  * In RebindTestFixtureWithApp tests, mgmt.getHighAvailabilityManager().getNodeState()
                    //    always returns INITIALIZING.
                    //  * The rebind metrics is a hack, and feels very risky for HOT_STANDBY nodes that 
                    //    may have executed the rebind code multiple times.
                    Map<String, Object> metrics = getManagementContext().getRebindManager().getMetrics();
                    Object count = (metrics.get("rebind") instanceof Map) ? ((Map<?,?>)metrics.get("rebind")).get("count") : null;
                    return (count instanceof Number) && ((Number)count).intValue() > 0;
                }});
        }
    }

    @Override
    public void stop() {
        super.stop();

        if (rescan != null && rescan.isActivated()) {
            rescan.stop();
        }
    }

    @Override
    protected void onEntityAdded(Entity item) {
        synchronized (memberChangeMutex) {
            super.onEntityAdded(item);
            distributeEntities();
        }
    }

    @Override
    protected void onEntityRemoved(Entity item) {
        synchronized (memberChangeMutex) {
            super.onEntityRemoved(item);
            distributeEntities();
        }
    }
    
    @Override
    protected void onEntityChanged(Entity item) {
        synchronized (memberChangeMutex) {
            super.onEntityChanged(item);
            distributeEntities();
        }
    }

    @Override
    public void rescanEntities() {
        synchronized (memberChangeMutex) {
            super.rescanEntities();
            distributeEntities();
        }
    }

    @Override
    public void distributeEntities() {
        try {
            synchronized (memberChangeMutex) {
                if (Entities.isUnmanagingOrNoLongerManaged(this)) return;

                Function<Entity, String> bucketFunctionF = getConfig(BUCKET_FUNCTION);
                CustomWorkflowStep bucketFunctionW = getConfig(BUCKET_WORKFLOW);
                String bucketFunctionE = getConfig(BUCKET_EXPRESSION);

                if (bucketFunctionE != null) {
                    if (bucketFunctionW != null) LOG.warn("Ignoring bucket workflow because expression supplied");
                    bucketFunctionW = TypeCoercions.coerce(MutableMap.of("steps", MutableList.of("return " + bucketFunctionE)), CustomWorkflowStep.class);
                }
                if (bucketFunctionW != null) {
                    if (bucketFunctionF != null)
                        LOG.warn("Ignoring bucket function because workflow or expression supplied");
                    bucketFunctionF = new WorkflowFunction(bucketFunctionW);
                }

                Function<Entity, String> bucketIdFunctionF = getConfig(BUCKET_ID_FUNCTION);
                CustomWorkflowStep bucketIdFunctionW = getConfig(BUCKET_ID_WORKFLOW);
                String bucketIdFunctionE = getConfig(BUCKET_ID_EXPRESSION);

                if (bucketIdFunctionE != null) {
                    if (bucketIdFunctionW != null) LOG.warn("Ignoring bucket workflow because expression supplied");
                    bucketIdFunctionW = TypeCoercions.coerce(MutableMap.of("steps", MutableList.of("return " + bucketIdFunctionE)), CustomWorkflowStep.class);
                }
                if (bucketIdFunctionW != null) {
                    if (bucketIdFunctionF != null)
                        LOG.warn("Ignoring bucket function because workflow or expression supplied");
                    bucketIdFunctionF = new WorkflowFunction(bucketIdFunctionW);
                }

                if (bucketFunctionF == null) {
                    if (bucketIdFunctionF!=null) {
                        bucketFunctionF = bucketIdFunctionF;
                    } else {
                        LOG.warn(this + " should have exactly one of: a bucket expression, workflow, or function (optionally coming from the bucket ID function)");
                        return;
                    }
                }

                EntitySpec<? extends BasicGroup> bucketSpec = getConfig(BUCKET_SPEC);
                if (bucketSpec == null) return;

                Map<String, BasicGroup> buckets = MutableMap.copyOf(getAttribute(BUCKETS));

                // Bucketize the members where the function gives a non-null bucket
                Function<Entity, String> bucketFunctionF2 = bucketFunctionF;
                Multimap<String, Entity> entityMapping = getMembers().stream().collect(() -> Multimaps.newSetMultimap(MutableMap.of(), MutableSet::new),
                        (map, entity) -> {
                            String name = bucketFunctionF2.apply(entity);
                            if (Strings.isNonBlank(name)) map.put(name, entity);
                        },
                        (m1, m2) -> m1.putAll(m2));

                // Now fill the buckets
                Collection<Entity> oldChildren = getChildren();
                for (String name : entityMapping.keySet()) {
                    BasicGroup bucket = buckets.get(name);
                    if (bucket == null) {
                        try {
                            EntitySpec<? extends BasicGroup> spec = EntitySpec.create(bucketSpec).displayName(name);
                            if (bucketIdFunctionF != null) {
                                spec.configure(BrooklynConfigKeys.PLAN_ID, bucketIdFunctionF.apply(entityMapping.get(name).iterator().next()));
                            }

                            bucket = addChild(spec);
                        } catch (Exception e) {
                            Exceptions.propagateIfFatal(e);
                            ServiceProblemsLogic.updateProblemsIndicator(this, "children", "Could not add child; removing all new children for now: " + Exceptions.collapseText(e));
                            // if we don't do this, they get added infinitely often
                            MutableSet<Entity> newChildren = MutableSet.copyOf(getChildren());
                            newChildren.removeAll(oldChildren);
                            for (Entity child : newChildren) {
                                removeChild(child);
                            }
                            throw e;
                        }
                        ServiceProblemsLogic.clearProblemsIndicator(this, "children");
                        buckets.put(name, bucket);
                    }
                    bucket.setMembers(entityMapping.get(name));
                }

                // Remove any now-empty buckets
                Set<String> empty = ImmutableSet.copyOf(Sets.difference(buckets.keySet(), entityMapping.keySet()));
                for (String name : empty) {
                    Group removed = buckets.remove(name);
                    LOG.debug(this + " removing empty child-bucket " + name + " -> " + removed);
                    removeChild(removed);
                    Entities.unmanage(removed);
                }

                // Save the bucket mappings
                sensors().set(BUCKETS, ImmutableMap.copyOf(buckets));
            }
        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            if (Entities.isUnmanagingOrNoLongerManaged(this)) {
                LOG.debug("Error in "+this+" when unmanaged, ignoring: "+e);
            } else {
                throw Exceptions.propagate(e);
            }
        }
    }

    private static class WorkflowFunction implements Function<Entity, String> {
        private final CustomWorkflowStep workflow;

        public WorkflowFunction(CustomWorkflowStep bucketFunctionW) {
            this.workflow = bucketFunctionW;
        }

        public String apply(Entity entity) {
            Maybe<Task<Object>> t = workflow.newWorkflowExecution(entity, "Workflow to find bucket name for " + entity, null).getTask(true);
            if (t.isAbsent()) {
                LOG.debug("Entity " + entity + " does not match condition to placed in the dynamic multigroup");
                return null;
            }
            try {
                return TypeCoercions.coerce(DynamicTasks.submit(t.get(), entity).getUnchecked(), String.class);
            } catch (Exception e) {
                Exceptions.propagateIfFatal(e);
                LOG.warn("Entity " + entity + " failed when trying to evaluate the bucket it goes in; not putting into a bucket");
                return null;
            }
        }
    }
}
