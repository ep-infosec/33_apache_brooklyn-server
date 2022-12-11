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
package org.apache.brooklyn.core.mgmt.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import org.apache.brooklyn.api.typereg.OsgiBundleWithUrl;
import static org.apache.brooklyn.core.catalog.internal.CatalogUtils.newClassLoadingContextForCatalogItems;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import org.apache.brooklyn.api.catalog.BrooklynCatalog;
import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.drivers.EntityDriverManager;
import org.apache.brooklyn.api.entity.drivers.downloads.DownloadResolverManager;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.LocationRegistry;
import org.apache.brooklyn.api.mgmt.ExecutionContext;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.mgmt.Scratchpad;
import org.apache.brooklyn.api.mgmt.SubscriptionContext;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.mgmt.classloading.BrooklynClassLoadingContext;
import org.apache.brooklyn.api.mgmt.entitlement.EntitlementManager;
import org.apache.brooklyn.api.mgmt.ha.HighAvailabilityManager;
import org.apache.brooklyn.api.mgmt.rebind.RebindManager;
import org.apache.brooklyn.api.objs.BrooklynObject;
import org.apache.brooklyn.api.objs.EntityAdjunct;
import org.apache.brooklyn.api.typereg.BrooklynTypeRegistry;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.config.StringConfigMap;
import org.apache.brooklyn.core.catalog.internal.BasicBrooklynCatalog;
import org.apache.brooklyn.core.catalog.internal.CatalogInitialization;
import org.apache.brooklyn.core.entity.AbstractEntity;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.entity.drivers.BasicEntityDriverManager;
import org.apache.brooklyn.core.entity.drivers.downloads.BasicDownloadsManager;
import org.apache.brooklyn.core.internal.BrooklynProperties;
import org.apache.brooklyn.core.internal.storage.BrooklynStorage;
import org.apache.brooklyn.core.internal.storage.impl.BrooklynStorageImpl;
import org.apache.brooklyn.core.location.BasicLocationRegistry;
import org.apache.brooklyn.core.mgmt.BrooklynTaskTags;
import org.apache.brooklyn.core.mgmt.classloading.BrooklynClassLoadingContextSequential;
import org.apache.brooklyn.core.mgmt.classloading.JavaBrooklynClassLoadingContext;
import org.apache.brooklyn.core.mgmt.entitlement.Entitlements;
import org.apache.brooklyn.core.mgmt.ha.HighAvailabilityManagerImpl;
import org.apache.brooklyn.core.mgmt.rebind.RebindManagerImpl;
import org.apache.brooklyn.core.objs.AbstractEntityAdjunct;
import org.apache.brooklyn.core.typereg.BasicBrooklynTypeRegistry;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.task.BasicExecutionContext;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.javalang.Reflections;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

public abstract class AbstractManagementContext implements ManagementContextInternal {
    private static final Logger log = LoggerFactory.getLogger(AbstractManagementContext.class);

    static {
        ResourceUtils.addClassLoaderProvider(new Function<Object, BrooklynClassLoadingContext>() {
            @Override
            public BrooklynClassLoadingContext apply(@Nullable Object input) {
                if (input instanceof EntityInternal) {
                    EntityInternal internal = (EntityInternal)input;
                    String inputCatalogItemId = internal.getCatalogItemId();
                    if(inputCatalogItemId != null) {
                        RegisteredType item = internal.getManagementContext().getTypeRegistry().get(internal.getCatalogItemId());

                        if (item != null) {
                            final List<String> searchPath = internal.getCatalogItemIdSearchPath();
                            final ManagementContext managementContext = internal.getManagementContext();
                            BrooklynClassLoadingContextSequential seqLoader =
                                new BrooklynClassLoadingContextSequential(managementContext);
                            seqLoader.add(newClassLoadingContextForCatalogItems(managementContext, inputCatalogItemId, searchPath));
                            JavaBrooklynClassLoadingContext entityLoader =
                                JavaBrooklynClassLoadingContext.create(input.getClass().getClassLoader());
                            seqLoader.add(entityLoader);
                            return seqLoader;
                        } else {
                            log.error("Can't find catalog item " + internal.getCatalogItemId() +
                                    " used for instantiating entity " + internal +
                                    ". Falling back to application classpath.");
                        }
                    }
                    return apply(internal.getManagementSupport());
                }
                
                if (input instanceof EntityManagementSupport) {
                    return apply(((EntityManagementSupport) input).getManagementContext());
                }

                if (input instanceof ManagementContext) {
                    return JavaBrooklynClassLoadingContext.create((ManagementContext) input);
                }
                return null;
            }
        });
    }

    private final AtomicLong totalEffectorInvocationCount = new AtomicLong();
    private final String managementNodeId;

    protected DeferredBrooklynProperties configMap;
    protected Scratchpad scratchpad;
    protected BasicLocationRegistry locationRegistry;
    protected final BasicBrooklynCatalog catalog;
    protected final BrooklynTypeRegistry typeRegistry;
    protected ClassLoader baseClassLoader;
    protected Iterable<URL> baseClassPathForScanning;

    private final ManagementNodeStateListenerManager managementNodeStateListenerManager;
    private final RebindManager rebindManager;
    private final HighAvailabilityManager highAvailabilityManager;
    
    protected volatile BrooklynGarbageCollector gc;

    private final EntityDriverManager entityDriverManager;
    protected DownloadResolverManager downloadsManager;

    protected EntitlementManager entitlementManager;

    private final BrooklynStorage storage;

    protected final ExternalConfigSupplierRegistry configSupplierRegistry;

    private volatile boolean running = true;
    protected boolean startupComplete = false;
    protected final List<Throwable> errors = Collections.synchronizedList(MutableList.<Throwable>of());

    protected Maybe<URI> uri = Maybe.absent();
    private CatalogInitialization catalogInitialization;

    public AbstractManagementContext(BrooklynProperties brooklynProperties) {
        this.managementNodeId = Strings.makeRandomId(8);

        this.configMap = new DeferredBrooklynProperties(brooklynProperties, this);
        this.scratchpad = new BasicScratchpad();
        this.entityDriverManager = new BasicEntityDriverManager();
        this.downloadsManager = BasicDownloadsManager.newDefault(configMap);
        
        this.catalog = new BasicBrooklynCatalog(this);
        this.typeRegistry = new BasicBrooklynTypeRegistry(this);
        
        this.storage = new BrooklynStorageImpl();
        this.rebindManager = new RebindManagerImpl(this); // TODO leaking "this" reference; yuck
        this.managementNodeStateListenerManager = new ManagementNodeStateListenerManager(this); // TODO leaking "this" reference; yuck
        this.highAvailabilityManager = new HighAvailabilityManagerImpl(this, managementNodeStateListenerManager); // TODO leaking "this" reference; yuck
        
        this.entitlementManager = Entitlements.newManager(this, brooklynProperties);
        this.configSupplierRegistry = new BasicExternalConfigSupplierRegistry(this); // TODO leaking "this" reference; yuck
    }

    @Override
    public void terminate() {
        highAvailabilityManager.stop();
        running = false;
        rebindManager.stop();
        managementNodeStateListenerManager.terminate();
        storage.terminate();
        // Don't unmanage everything; different entities get given their events at different times 
        // so can cause problems (e.g. a group finds out that a member is unmanaged, before the
        // group itself has been told that it is unmanaged).
    }
    
    @Override
    public boolean isRunning() {
        return running;
    }
    
    @Override
    public boolean isStartupComplete() {
        return startupComplete;
    }

    @Override
    public String getManagementNodeId() {
        return managementNodeId;
    }
    
    @Override
    public BrooklynStorage getStorage() {
        return storage;
    }
    
    @Override
    public RebindManager getRebindManager() {
        return rebindManager;
    }

    @Override
    public HighAvailabilityManager getHighAvailabilityManager() {
        return highAvailabilityManager;
    }

    @Override
    public long getTotalEffectorInvocations() {
        return totalEffectorInvocationCount.get();
    }
    
    @Override
    public ExecutionContext getExecutionContext(Entity e) {
        // BEC is a thin wrapper around EM so fine to create a new one here; but make sure it gets the real entity
        if (e instanceof AbstractEntity) {
            ImmutableSet<Object> tags = ImmutableSet.<Object>of(
                    BrooklynTaskTags.tagForContextEntity(e),
                    this
            );
            return new BasicExecutionContext(getExecutionManager(), tags);
        } else {
            return ((EntityInternal)e).getExecutionContext();
        }
    }
    
    @Override
    public ExecutionContext getExecutionContext(Entity e, EntityAdjunct adjunct) {
        // BEC is a thin wrapper around EM so fine to create a new one here; but make sure it gets the real entity
        if (e instanceof AbstractEntityAdjunct) {
            ImmutableSet<Object> tags = ImmutableSet.<Object>of(
                    BrooklynTaskTags.tagForContextAdjunct(adjunct),
                    BrooklynTaskTags.tagForContextEntity(e),
                    this
            );
            return new BasicExecutionContext(getExecutionManager(), tags);
        } else {
            return ((EntityInternal)e).getExecutionContext();
        }
    }

    @Override
    public ExecutionContext getServerExecutionContext() {
        // BEC is a thin wrapper around EM so fine to create a new one here
        ImmutableSet<Object> tags = ImmutableSet.<Object>of(
                this,
                BrooklynTaskTags.BROOKLYN_SERVER_TASK_TAG
        );
        return new BasicExecutionContext(getExecutionManager(), tags);
    }

    @Override
    public SubscriptionContext getSubscriptionContext(Entity e) {
        // BSC is a thin wrapper around SM so fine to create a new one here
        Map<String, ?> flags = ImmutableMap.of("tags", ImmutableList.of(BrooklynTaskTags.tagForContextEntity(e)));
        return new BasicSubscriptionContext(flags, getSubscriptionManager(), e);
    }
    
    @Override
    public SubscriptionContext getSubscriptionContext(Entity e, EntityAdjunct a) {
        // BSC is a thin wrapper around SM so fine to create a new one here
        Map<String, ?> flags = ImmutableMap.of("tags", ImmutableList.of(BrooklynTaskTags.tagForContextEntity(e), BrooklynTaskTags.tagForContextAdjunct(a)),
            "subscriptionDescription", "adjunct "+a.getId());
        return new BasicSubscriptionContext(flags, getSubscriptionManager(), e);
    }

    @Override
    public SubscriptionContext getSubscriptionContext(Location loc) {
        // BSC is a thin wrapper around SM so fine to create a new one here
        return new BasicSubscriptionContext(getSubscriptionManager(), loc);
    }

    @Override
    public EntityDriverManager getEntityDriverManager() {
        return entityDriverManager;
    }

    @Override
    public DownloadResolverManager getEntityDownloadsManager() {
        return downloadsManager;
    }
    
    @Override
    public EntitlementManager getEntitlementManager() {
        return entitlementManager;
    }
    
    protected abstract void manageIfNecessary(Entity entity, Object context);

    @Override
    public <T> Task<T> invokeEffector(final Entity entity, final Effector<T> eff, @SuppressWarnings("rawtypes") final Map parameters) {
        return runAtEntity(entity, eff, parameters);
    }
    
    @SuppressWarnings("unchecked")
    protected <T> T invokeEffectorMethodLocal(Entity entity, Effector<T> eff, Map<String, ?> args) {
        assert isManagedLocally(entity) : "cannot invoke effector method at "+this+" because it is not managed here";
        totalEffectorInvocationCount.incrementAndGet();
        Object[] transformedArgs = EffectorUtils.prepareArgsForEffector(eff, args);
        
        try {
            Maybe<Object> result = Reflections.invokeMethodFromArgs(entity, eff.getName(), Arrays.asList(transformedArgs));
            if (result.isPresent()) {
                return (T) result.get();
            } else {
                throw new IllegalStateException("Unable to invoke entity effector method "+eff.getName()+" on "+entity+" - not found matching args");
            }
            
        } catch (IllegalAccessException | InvocationTargetException e) {
            // Note that if we do any "nicer" error, such as:
            //     throw Exceptions.propagate("Unable to invoke entity effector method "+eff.getName()+" on "+entity, e)
            // then EffectorBasicTest.testInvokeEffectorErrorCollapsedNicely fails because its call to:
            //     Exceptions.collapseTextInContext
            // does not unwrap this text.
            throw Exceptions.propagate(e);
        }
    }

    /**
     * Method for entity to make effector happen with correct semantics (right place, right task context),
     * when a method is called on that entity.
     * @throws ExecutionException 
     */
    @Override
    public <T> T invokeEffectorMethodSync(final Entity entity, final Effector<T> eff, final Map<String, ?> args) throws ExecutionException {
        try {
            Task<?> current = Tasks.current();
            if (current == null || !entity.equals(BrooklynTaskTags.getContextEntity(current)) || !isManagedLocally(entity)) {
                manageIfNecessary(entity, eff.getName());
                // Wrap in a task if we aren't already in a task that is tagged with this entity
                Task<T> task = runAtEntity( EffectorUtils.getTaskFlagsForEffectorInvocation(entity, eff, 
                            ConfigBag.newInstance().configureStringKey("args", args)),
                        entity, 
                        new Callable<T>() {
                            @Override
                            public T call() {
                                return invokeEffectorMethodLocal(entity, eff, args);
                            }});
                return task.get();
            } else {
                return invokeEffectorMethodLocal(entity, eff, args);
            }
        } catch (Exception e) {
            // don't need to attach any message or warning because the Effector impl hierarchy does that (see calls to EffectorUtils.handleException)
            throw new ExecutionException(e);
        }
    }

    /**
     * Whether the master entity record is local, and sensors and effectors can be properly accessed locally.
     */ 
    public abstract boolean isManagedLocally(Entity e);
    
    /**
     * Causes the indicated runnable to be run at the right location for the given entity.
     *
     * Returns the actual task (if it is local) or a proxy task (if it is remote);
     * if management for the entity has not yet started this may start it.
     * 
     * @deprecated since 0.6.0 use effectors (or support {@code runAtEntity(Entity, Effector, Map)} if something else is needed);
     * (Callable with Map flags is too open-ended, bothersome to support, and not used much) 
     */
    @Deprecated
    protected abstract <T> Task<T> runAtEntity(@SuppressWarnings("rawtypes") Map flags, Entity entity, Callable<T> c);

    /** Runs the given effector in the right place for the given entity.
     * The task is immediately submitted in the background, but also recorded in the queueing context (if present)
     * so it appears as a child, but marked inessential so it does not fail the parent task, who will ordinarily
     * call {@link Task#get()} on the object and may do their own failure handling. 
     */
    protected abstract <T> Task<T> runAtEntity(final Entity entity, final Effector<T> eff, @SuppressWarnings("rawtypes") final Map parameters);

    @Override
    public StringConfigMap getConfig() {
        return configMap;
    }

    @Override
    public BrooklynProperties getBrooklynProperties() {
        return configMap;
    }

    @Override
    public Scratchpad getScratchpad() {
        return scratchpad;
    }

    private final Object locationRegistrySemaphore = new Object();
    
    @Override
    public LocationRegistry getLocationRegistry() {
        // NB: can deadlock if synched on whole LMC
        synchronized (locationRegistrySemaphore) {
            if (locationRegistry==null) locationRegistry = new BasicLocationRegistry(this);
            return locationRegistry;
        }
    }

    @Override
    public BrooklynCatalog getCatalog() {
        return catalog;
    }
    
    @Override
    public BrooklynTypeRegistry getTypeRegistry() {
        return typeRegistry;
    }
    
    @Override
    public ClassLoader getCatalogClassLoader() {
        // catalog does not have to be initialized
        return catalog.getRootClassLoader();
    }

    /**
     * Optional class-loader that this management context should use as its base,
     * as the first-resort in the catalog, and for scanning (if scanning the default in the catalog).
     * In most instances the default classloader (ManagementContext.class.getClassLoader(), assuming
     * this was in the JARs used at boot time) is fine, and in those cases this method normally returns null.
     * (Surefire does some weird stuff, but the default classloader is fine for loading;
     * however it requires a custom base classpath to be set for scanning.)
     */
    @Override
    public ClassLoader getBaseClassLoader() {
        return baseClassLoader;
    }
    
    /** See {@link #getBaseClassLoader()}.  Only settable once and must be invoked before catalog is loaded. */
    public void setBaseClassLoader(ClassLoader cl) {
        if (baseClassLoader==cl) return;
        if (baseClassLoader!=null) throw new IllegalStateException("Cannot change base class loader (in "+this+")");
        if (catalog!=null) throw new IllegalStateException("Cannot set base class after catalog has been loaded (in "+this+")");
        this.baseClassLoader = cl;
    }
    
    /** Optional mechanism for setting the classpath which should be scanned by the catalog, if the catalog
     * is scanning the default classpath.  Usually it infers the right thing, but some classloaders
     * (e.g. surefire) do funny things which the underlying org.reflections.Reflections library can't see in to.
     * <p>
     * This should normally be invoked early in the server startup.  Setting it after the catalog is loaded will not
     * take effect without an explicit internal call to do so.  Once set, it can be changed prior to catalog loading
     * but it cannot be <i>changed</i> once the catalog is loaded.
     * <p>
     * ClasspathHelper.forJavaClassPath() is often a good argument to pass, and is used internally in some places
     * when no items are found on the catalog. */
    @Override
    public void setBaseClassPathForScanning(Iterable<URL> urls) {
        if (Objects.equal(baseClassPathForScanning, urls)) return;
        if (baseClassPathForScanning != null) {
            if (catalog==null)
                log.warn("Changing scan classpath to "+urls+" from "+baseClassPathForScanning);
            else
                throw new IllegalStateException("Cannot change base class path for scanning (in "+this+")");
        }
        this.baseClassPathForScanning = urls;
    }
    /** 
     * @see #setBaseClassPathForScanning(Iterable)
     */
    @Override
    public Iterable<URL> getBaseClassPathForScanning() {
        return baseClassPathForScanning;
    }

    public BrooklynGarbageCollector getGarbageCollector() {
        return gc;
    }

    @Override
    public void setManagementNodeUri(URI uri) {
        this.uri = Maybe.of(checkNotNull(uri, "uri"));
    }

    @Override
    public Maybe<URI> getManagementNodeUri() {
        return uri;
    }
    
    private final Object catalogInitMutex = new Object();
    @Override
    public CatalogInitialization getCatalogInitialization() {
        synchronized (catalogInitMutex) {
            if (catalogInitialization!=null) return catalogInitialization;
            CatalogInitialization ci = new CatalogInitialization();
            setCatalogInitialization(ci);
            return ci;
        }
    }
    
    @Override
    public void setCatalogInitialization(CatalogInitialization catalogInitialization) {
        synchronized (catalogInitMutex) {
            Preconditions.checkNotNull(catalogInitialization, "initialization must not be null");
            if (this.catalogInitialization!=null && this.catalogInitialization != catalogInitialization)
                throw new IllegalStateException("Changing catalog init from "+this.catalogInitialization+" to "+catalogInitialization+"; changes not permitted");
            catalogInitialization.setManagementContext(this);
            this.catalogInitialization = catalogInitialization;
        }
    }
    
    @Override
    public BrooklynObject lookup(String id) {
        return lookup(id, BrooklynObject.class);
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BrooklynObject> T lookup(String id, Class<T> type) {
        if (id==null) return null;
        Object result;
        result = getEntityManager().getEntity(id);
        if (result!=null && type.isInstance(result)) return (T)result;
        
        result = getLocationManager().getLocation(id);
        if (result!=null && type.isInstance(result)) return (T)result;

        return lookup((o) -> { return type.isInstance(o) && Objects.equal(id, o.getId()); });
    }
    
    @Override
    public <T extends BrooklynObject> T lookup(Predicate<? super T> filter) {
        Collection<T> list = lookupAll(filter, true);
        if (list.isEmpty()) return null;
        return list.iterator().next();
    }

    @Override
    public <T extends BrooklynObject> Collection<T> lookupAll(Predicate<? super T> filter) {
        return lookupAll(filter, false);
    }
    
    @SuppressWarnings("unchecked")
    private <T extends BrooklynObject> Collection<T> lookupAll(Predicate<? super T> filter, boolean justOne) {
        List<T> result = MutableList.of();

        final class Scanner {
            public boolean scan(Iterable<? extends BrooklynObject> items) {
                for (BrooklynObject i: items) {
                    try {
                        if (filter.apply((T)i)) {
                            result.add((T)i);
                            if (justOne) return true;
                        }
                    } catch (Exception exc) {
                        Exceptions.propagateIfFatal(exc);
                        // just assume filter isn't for this type, class cast
                        return false;
                    }
                }
                return false;
            }
        }
        Scanner scanner = new Scanner();
        if (scanner.scan( getEntityManager().getEntities() ) && justOne) return result;
        if (scanner.scan( getLocationManager().getLocations() ) && justOne) return result;
        for (Entity e: getEntityManager().getEntities()) {
            if (scanner.scan( e.policies() ) && justOne) return result;
            if (scanner.scan( e.enrichers() ) && justOne) return result;
            if (scanner.scan( ((EntityInternal)e).feeds() ) && justOne) return result;
        }
        
        return result;
    }

    @Override
    public List<Throwable> errors() {
        return errors;
    }

    /** @since 0.8.0 */
    @Override
    public ExternalConfigSupplierRegistry getExternalConfigProviderRegistry() {
        return configSupplierRegistry;
    }

}
