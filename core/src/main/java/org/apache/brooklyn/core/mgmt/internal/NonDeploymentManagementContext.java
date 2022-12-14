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

import java.net.URI;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.apache.brooklyn.api.catalog.BrooklynCatalog;
import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.drivers.EntityDriverManager;
import org.apache.brooklyn.api.entity.drivers.downloads.DownloadResolverManager;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.LocationRegistry;
import org.apache.brooklyn.api.mgmt.AccessController;
import org.apache.brooklyn.api.mgmt.EntityManager;
import org.apache.brooklyn.api.mgmt.ExecutionContext;
import org.apache.brooklyn.api.mgmt.ExecutionManager;
import org.apache.brooklyn.api.mgmt.LocationManager;
import org.apache.brooklyn.api.mgmt.Scratchpad;
import org.apache.brooklyn.api.mgmt.SubscriptionContext;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.mgmt.entitlement.EntitlementManager;
import org.apache.brooklyn.api.mgmt.ha.HighAvailabilityManager;
import org.apache.brooklyn.api.mgmt.ha.HighAvailabilityMode;
import org.apache.brooklyn.api.mgmt.ha.ManagementNodeState;
import org.apache.brooklyn.api.mgmt.ha.ManagementPlaneSyncRecord;
import org.apache.brooklyn.api.mgmt.ha.ManagementPlaneSyncRecordPersister;
import org.apache.brooklyn.api.mgmt.rebind.ChangeListener;
import org.apache.brooklyn.api.mgmt.rebind.PersistenceExceptionHandler;
import org.apache.brooklyn.api.mgmt.rebind.RebindExceptionHandler;
import org.apache.brooklyn.api.mgmt.rebind.RebindManager;
import org.apache.brooklyn.api.mgmt.rebind.mementos.BrooklynMementoPersister;
import org.apache.brooklyn.api.mgmt.rebind.mementos.BrooklynMementoRawData;
import org.apache.brooklyn.api.objs.BrooklynObject;
import org.apache.brooklyn.api.objs.EntityAdjunct;
import org.apache.brooklyn.api.typereg.BrooklynTypeRegistry;
import org.apache.brooklyn.config.StringConfigMap;
import org.apache.brooklyn.core.catalog.internal.CatalogInitialization;
import org.apache.brooklyn.core.entity.AbstractEntity;
import org.apache.brooklyn.core.internal.BrooklynProperties;
import org.apache.brooklyn.core.internal.storage.BrooklynStorage;
import org.apache.brooklyn.core.mgmt.BrooklynTaskTags;
import org.apache.brooklyn.core.mgmt.ha.OsgiManager;
import org.apache.brooklyn.core.mgmt.usage.UsageManager;
import org.apache.brooklyn.core.objs.proxy.InternalEntityFactory;
import org.apache.brooklyn.core.objs.proxy.InternalLocationFactory;
import org.apache.brooklyn.core.objs.proxy.InternalPolicyFactory;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

public class NonDeploymentManagementContext implements ManagementContextInternal {

    private static final Logger log = LoggerFactory.getLogger(NonDeploymentManagementContext.class);
    
    public enum NonDeploymentManagementContextMode {
        PRE_MANAGEMENT,
        MANAGEMENT_REBINDING,
        MANAGEMENT_STARTING,
        MANAGEMENT_STARTED,
        MANAGEMENT_STOPPING,
        MANAGEMENT_STOPPED;
        
        public boolean isPreManaged() {
            return this == PRE_MANAGEMENT || this == MANAGEMENT_REBINDING;
        }
    }
    
    private final AbstractEntity entity;
    private NonDeploymentManagementContextMode mode;
    private ManagementContextInternal initialManagementContext;
    
    private final QueueingSubscriptionManager qsm;
    private NonDeploymentEntityManager entityManager;
    private NonDeploymentLocationManager locationManager;
    private NonDeploymentAccessManager accessManager;
    private NonDeploymentUsageManager usageManager;

    public NonDeploymentManagementContext(AbstractEntity entity, NonDeploymentManagementContextMode mode) {
        this.entity = checkNotNull(entity, "entity");
        this.mode = checkNotNull(mode, "mode");
        qsm = new QueueingSubscriptionManager();
        
        entityManager = new NonDeploymentEntityManager(null);
        locationManager = new NonDeploymentLocationManager(null);
        accessManager = new NonDeploymentAccessManager(null);
        usageManager = new NonDeploymentUsageManager(null);
    }

    @Override
    @Deprecated
    public String getManagementPlaneId() {
        return (initialManagementContext == null) ? null : initialManagementContext.getManagementPlaneId();
    }
    
    @Override
    public Maybe<String> getManagementPlaneIdMaybe() {
        return (initialManagementContext == null) ? Maybe.<String>absent("Uninitialized non-deployment management context") : initialManagementContext.getManagementPlaneIdMaybe();
    }
    
    @Override
    public String getManagementNodeId() {
        return (initialManagementContext == null) ? null : initialManagementContext.getManagementNodeId();
    }

    @Override
    public Maybe<URI> getManagementNodeUri() {
        return (initialManagementContext == null) ? Maybe.<URI>absent() : initialManagementContext.getManagementNodeUri();
    }

    public void setManagementContext(ManagementContextInternal val) {
        this.initialManagementContext = checkNotNull(val, "initialManagementContext");
        this.entityManager = new NonDeploymentEntityManager(val);
        this.locationManager = new NonDeploymentLocationManager(val);
        this.accessManager = new NonDeploymentAccessManager(val);
        this.usageManager = new NonDeploymentUsageManager(val);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("entity", entity.getId()).add("mode", mode).toString();
    }
    
    public void setMode(NonDeploymentManagementContextMode mode) {
        this.mode = checkNotNull(mode, "mode");
    }
    public NonDeploymentManagementContextMode getMode() {
        return mode;
    }
    
    @Override
    public Collection<Application> getApplications() {
        return Collections.emptyList();
    }

    @Override
    public boolean isRunning() {
        if (getMode()==NonDeploymentManagementContextMode.MANAGEMENT_STOPPED) {
            // already stopped
            return false;
        }
        // Assume that the real management context has not been terminated, so always true
        return true;
    }
    
    @Override
    public boolean isStartupComplete() {
        return false;
    }

    @Override public void waitForManagementStartupComplete(Duration timeout) {
        checkInitialManagementContextReal();
        initialManagementContext.waitForManagementStartupComplete(timeout);
    }

    @Override
    public ManagementNodeState getNodeState() {
        return ManagementNodeState.INITIALIZING;
    }
    
    @Override
    public InternalEntityFactory getEntityFactory() {
        checkInitialManagementContextReal();
        return initialManagementContext.getEntityFactory();
    }

    @Override
    public InternalLocationFactory getLocationFactory() {
        checkInitialManagementContextReal();
        return initialManagementContext.getLocationFactory();
    }

    @Override
    public InternalPolicyFactory getPolicyFactory() {
        checkInitialManagementContextReal();
        return initialManagementContext.getPolicyFactory();
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }
    
    @Override
    public LocationManager getLocationManager() {
        return locationManager;
    }

    @Override
    public AccessManager getAccessManager() {
        return accessManager;
    }

    @Override
    public UsageManager getUsageManager() {
        return usageManager;
    }
    
    @Override
    public Maybe<OsgiManager> getOsgiManager() {
        switch (mode) {
        case PRE_MANAGEMENT:
        case MANAGEMENT_STARTING:
        case MANAGEMENT_STARTED:
            checkInitialManagementContextReal();
            return initialManagementContext.getOsgiManager();
        default:
            return Maybe.absent("Entity " + entity + " is no longer managed; OSGi context no longer available");
        }
    }

    @Override
    public AccessController getAccessController() {
        return getAccessManager().getAccessController();
    }

    @Override
    public ExecutionManager getExecutionManager() {
        checkInitialManagementContextReal();
        return initialManagementContext.getExecutionManager();
    }

    @Override
    public QueueingSubscriptionManager getSubscriptionManager() {
        return qsm;
    }

    @Override
    public synchronized SubscriptionContext getSubscriptionContext(Entity entity) {
        if (!this.entity.equals(entity)) throw new IllegalStateException("Non-deployment context "+this+" can only use a single Entity: has "+this.entity+", but passed "+entity);
        if (mode==NonDeploymentManagementContextMode.MANAGEMENT_STOPPED)
            throw new IllegalStateException("Entity "+entity+" is no longer managed; subscription context not available");
        // see also AbstractManagementContext.getSubscriptionContext - needed for callbacks, to ensure the correct entity context is set
        Map<String, ?> subscriptionFlags = ImmutableMap.of("tags", ImmutableList.of(BrooklynTaskTags.tagForContextEntity(entity)));
        return new BasicSubscriptionContext(subscriptionFlags, qsm, entity);
    }
    
    @Override
    public SubscriptionContext getSubscriptionContext(Entity entity, EntityAdjunct adjunct) {
        if (!this.entity.equals(entity)) throw new IllegalStateException("Non-deployment context "+this+" can only use a single Entity: has "+this.entity+", but passed "+entity);
        if (mode==NonDeploymentManagementContextMode.MANAGEMENT_STOPPED)
            throw new IllegalStateException("Entity "+entity+" is no longer managed; subscription context not available");
        // see also AbstractManagementContext.getSubscriptionContext - needed for callbacks, to ensure the correct entity context is set
        Map<String, ?> subscriptionFlags = ImmutableMap.of("tags", ImmutableList.of(BrooklynTaskTags.tagForContextEntity(entity), BrooklynTaskTags.tagForContextAdjunct(adjunct)));
        return new BasicSubscriptionContext(subscriptionFlags, qsm, entity);
    }

    @Override
    public synchronized SubscriptionContext getSubscriptionContext(Location loc) {
        // Should never be called; the NonDeploymentManagementContext is associated with a particular entity, whereas
        // the #getSubscriptionContext(loc) should only be called in the context of a location.
        throw new UnsupportedOperationException();
    }

    @Override
    public ExecutionContext getExecutionContext(Entity entity) {
        if (!this.entity.equals(entity)) throw new IllegalStateException("Non-deployment context "+this+" can only use a single Entity: has "+this.entity+", but passed "+entity);
        if (mode==NonDeploymentManagementContextMode.MANAGEMENT_STOPPED)
            throw new IllegalStateException("Entity "+entity+" is no longer managed; execution context not available");
        checkInitialManagementContextReal();
        return initialManagementContext.getExecutionContext(entity);
    }

    @Override
    public ExecutionContext getExecutionContext(Entity entity, EntityAdjunct adjunct) {
        if (!this.entity.equals(entity)) throw new IllegalStateException("Non-deployment context "+this+" can only use a single Entity: has "+this.entity+", but passed "+entity);
        if (mode==NonDeploymentManagementContextMode.MANAGEMENT_STOPPED)
            throw new IllegalStateException("Entity "+entity+" is no longer managed; execution context not available");
        checkInitialManagementContextReal();
        return initialManagementContext.getExecutionContext(entity, adjunct);
    }

    @Override
    public ExecutionContext getServerExecutionContext() {
        return initialManagementContext.getServerExecutionContext();
    }

    // TODO the methods below should delegate to the application?
    @Override
    public EntityDriverManager getEntityDriverManager() {
        checkInitialManagementContextReal();
        return initialManagementContext.getEntityDriverManager();
    }

    @Override
    public DownloadResolverManager getEntityDownloadsManager() {
        checkInitialManagementContextReal();
        return initialManagementContext.getEntityDownloadsManager();
    }

    @Override
    public StringConfigMap getConfig() {
        checkInitialManagementContextReal();
        return initialManagementContext.getConfig();
    }

    @Override
    public BrooklynProperties getBrooklynProperties() {
        checkInitialManagementContextReal();
        return initialManagementContext.getBrooklynProperties();
    }

    @Override
    public Scratchpad getScratchpad() {
        checkInitialManagementContextReal();
        return initialManagementContext.getScratchpad();
    }

    @Override
    public BrooklynStorage getStorage() {
        checkInitialManagementContextReal();
        return initialManagementContext.getStorage();
    }
    
    @Override
    public RebindManager getRebindManager() {
        // There was a race where EffectorUtils on invoking an effector calls:
        //     mgmtSupport.getEntityChangeListener().onEffectorCompleted(eff);
        // but where the entity/app may be being unmanaged concurrently (e.g. calling app.stop()).
        // So now we allow the change-listener to be called.
        
        if (isInitialManagementContextReal()) {
            return initialManagementContext.getRebindManager();
        } else {
            return new NonDeploymentRebindManager();
        }
    }

    @Override
    public HighAvailabilityManager getHighAvailabilityManager() {
        if (isInitialManagementContextReal()) {
            return initialManagementContext.getHighAvailabilityManager();
        } else {
            return new NonDeploymentHighAvailabilityManager();
        }
    }

    @Override
    public LocationRegistry getLocationRegistry() {
        checkInitialManagementContextReal();
        return initialManagementContext.getLocationRegistry();
    }

    @Override
    public BrooklynCatalog getCatalog() {
        checkInitialManagementContextReal();
        return initialManagementContext.getCatalog();
    }

    @Override
    public BrooklynTypeRegistry getTypeRegistry() {
        checkInitialManagementContextReal();
        return initialManagementContext.getTypeRegistry();
    }
    
    @Override
    public ClassLoader getCatalogClassLoader() {
        checkInitialManagementContextReal();
        return initialManagementContext.getCatalogClassLoader();
    }
    
    @Override
    public EntitlementManager getEntitlementManager() {
        checkInitialManagementContextReal();
        return initialManagementContext.getEntitlementManager();
    }
    
    @Override
    public <T> T invokeEffectorMethodSync(final Entity entity, final Effector<T> eff, final Map<String,?> args) throws ExecutionException {
        throw new IllegalStateException("Non-deployment context "+this+" is not valid for this operation: cannot invoke effector "+eff+" on entity "+entity);
    }
    
    @Override
    public <T> Task<T> invokeEffector(final Entity entity, final Effector<T> eff, @SuppressWarnings("rawtypes") final Map parameters) {
        throw new IllegalStateException("Non-deployment context "+this+" is not valid for this operation: cannot invoke effector "+eff+" on entity "+entity);
    }

    @Override
    public ClassLoader getBaseClassLoader() {
        checkInitialManagementContextReal();
        return initialManagementContext.getBaseClassLoader();
    }

    @Override
    public Iterable<URL> getBaseClassPathForScanning() {
        checkInitialManagementContextReal();
        return initialManagementContext.getBaseClassPathForScanning();
    }

    @Override
    public void addEntitySetListener(CollectionChangeListener<Entity> listener) {
        checkInitialManagementContextReal();
        initialManagementContext.addEntitySetListener(listener);
    }

    @Override
    public void removeEntitySetListener(CollectionChangeListener<Entity> listener) {
        checkInitialManagementContextReal();
        initialManagementContext.removeEntitySetListener(listener);
    }

    @Override
    public void terminate() {
        if (isInitialManagementContextReal()) {
            initialManagementContext.terminate();
        } else {
            // no-op; the non-deployment management context has nothing needing terminated
        }
    }

    @Override
    public long getTotalEffectorInvocations() {
        if (isInitialManagementContextReal()) {
            return initialManagementContext.getTotalEffectorInvocations();
        } else {
            return 0;
        }
    }

    @Override
    public void setBaseClassPathForScanning(Iterable<URL> urls) {
        checkInitialManagementContextReal();
        initialManagementContext.setBaseClassPathForScanning(urls);
    }

    @Override
    public void setManagementNodeUri(URI uri) {
        checkInitialManagementContextReal();
        initialManagementContext.setManagementNodeUri(uri);
    }

    @Override
    public void prePreManage(Entity entity) {
        // should throw?  but in 0.7.0-SNAPSHOT it was no-op
        log.warn("Ignoring call to prePreManage("+entity+") on "+this);
    }

    @Override
    public void prePreManage(Location location) {
        // should throw?  but in 0.7.0-SNAPSHOT it was no-op
        log.warn("Ignoring call to prePreManage("+location+") on "+this);
    }

    private boolean isInitialManagementContextReal() {
        return (initialManagementContext != null && !(initialManagementContext instanceof NonDeploymentManagementContext));
    }
    
    private void checkInitialManagementContextReal() {
        if (!isInitialManagementContextReal()) {
            throw new IllegalStateException("Non-deployment context "+this+" is not valid for this operation.");
        }
    }
    
    @Override
    public void reloadBrooklynProperties() {
        checkInitialManagementContextReal();
        initialManagementContext.reloadBrooklynProperties();
    }
    
    @Override
    public void addPropertiesReloadListener(PropertiesReloadListener listener) {
        checkInitialManagementContextReal();
        initialManagementContext.addPropertiesReloadListener(listener);
    }
    
    @Override
    public void removePropertiesReloadListener(PropertiesReloadListener listener) {
        checkInitialManagementContextReal();
        initialManagementContext.removePropertiesReloadListener(listener);
    }

    @Override
    public BrooklynObject lookup(String id) {
        checkInitialManagementContextReal();
        return initialManagementContext.lookup(id);
    }

    @Override
    public <T extends BrooklynObject> T lookup(String id, Class<T> type) {
        checkInitialManagementContextReal();
        return initialManagementContext.lookup(id, type);
    }

    @Override
    public <T extends BrooklynObject> T lookup(Predicate<? super T> filter) {
        checkInitialManagementContextReal();
        return initialManagementContext.lookup(filter);
    }

    @Override
    public <T extends BrooklynObject> Collection<T> lookupAll(Predicate<? super T> filter) {
        checkInitialManagementContextReal();
        return initialManagementContext.lookupAll(filter);
    }
    
    @Override
    public List<Throwable> errors() {
        checkInitialManagementContextReal();
        return initialManagementContext.errors();
    }

    @Override
    public CatalogInitialization getCatalogInitialization() {
        checkInitialManagementContextReal();
        return initialManagementContext.getCatalogInitialization();
    }
    
    @Override
    public void setCatalogInitialization(CatalogInitialization catalogInitialization) {
        checkInitialManagementContextReal();
        initialManagementContext.setCatalogInitialization(catalogInitialization);
    }

    @Override
    public ExternalConfigSupplierRegistry getExternalConfigProviderRegistry() {
        checkInitialManagementContextReal();
        return initialManagementContext.getExternalConfigProviderRegistry();
    }

    /**
     * For when the initial management context is not "real"; the changeListener is a no-op, but everything else forbidden.
     * 
     * @author aled
     */
    private class NonDeploymentRebindManager implements RebindManager {

        @Override
        public ChangeListener getChangeListener() {
            return ChangeListener.NOOP;
        }

        @Override
        public void setPersister(BrooklynMementoPersister persister) {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }

        @Override
        public void setPersister(BrooklynMementoPersister persister, PersistenceExceptionHandler exceptionHandler) {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }

        @Override
        public BrooklynMementoPersister getPersister() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }

        @Override
        public PersistenceExceptionHandler getPersisterExceptionHandler() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }

        @Override
        public List<Application> rebind(ClassLoader classLoader, RebindExceptionHandler exceptionHandler, ManagementNodeState mode) {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }

        @Override
        public void startPersistence() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        
        @Override
        public void stopPersistence() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }

        @Override
        public void startReadOnly(ManagementNodeState state) {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public void stopReadOnly() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }

        @Override
        public void stopEntityTasksAndCleanUp(String reason, Duration delayBeforeCancelling, Duration delayBeforeAbandoning) {
            // no-op
        }

        @Override
        public void reset() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }

        @Override
        public void start() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        
        @Override
        public void stop() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }

        @Override
        public void waitForPendingComplete(Duration timeout, boolean canTrigger) throws InterruptedException, TimeoutException {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public boolean hasPending() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public void forcePersistNow(boolean full, PersistenceExceptionHandler exceptionHandler) {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public BrooklynMementoRawData retrieveMementoRawData() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public boolean isAwaitingInitialRebind() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public Map<String, Object> getMetrics() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public boolean isRebindActive() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
    }

    /**
     * For when the initial management context is not "real".
     * 
     * @author aled
     */
    private class NonDeploymentHighAvailabilityManager implements HighAvailabilityManager {
        @Override
        public ManagementNodeState getNodeState() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public boolean isRunning() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public HighAvailabilityManager setPersister(ManagementPlaneSyncRecordPersister persister) {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public void disabled(boolean persistenceEnabled) {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public void start(HighAvailabilityMode startMode) {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public void stop() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public ManagementPlaneSyncRecordPersister getPersister() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public ManagementPlaneSyncRecord getLastManagementPlaneSyncRecord() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public ManagementPlaneSyncRecord loadManagementPlaneSyncRecord(boolean x) {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public void changeMode(HighAvailabilityMode startMode) {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public void setPriority(long priority) {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public long getPriority() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public Map<String, Object> getMetrics() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public void publishClearNonMaster() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public void setNodeIdToRemove(String nodeId) {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
        @Override
        public long getLastStateChange() {
            throw new IllegalStateException("Non-deployment context "+NonDeploymentManagementContext.this+" is not valid for this operation.");
        }
    }
}
