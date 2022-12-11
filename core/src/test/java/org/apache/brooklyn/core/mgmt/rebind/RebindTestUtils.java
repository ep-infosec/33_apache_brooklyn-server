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
package org.apache.brooklyn.core.mgmt.rebind;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.mgmt.ha.HighAvailabilityManager;
import org.apache.brooklyn.api.mgmt.ha.HighAvailabilityMode;
import org.apache.brooklyn.api.mgmt.ha.ManagementNodeState;
import org.apache.brooklyn.api.mgmt.ha.ManagementPlaneSyncRecordPersister;
import org.apache.brooklyn.api.mgmt.rebind.RebindExceptionHandler;
import org.apache.brooklyn.api.mgmt.rebind.RebindManager;
import org.apache.brooklyn.api.mgmt.rebind.mementos.BrooklynMemento;
import org.apache.brooklyn.api.mgmt.rebind.mementos.BrooklynMementoPersister;
import org.apache.brooklyn.api.mgmt.rebind.mementos.BrooklynMementoRawData;
import org.apache.brooklyn.api.objs.BrooklynObjectType;
import org.apache.brooklyn.api.objs.Identifiable;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.internal.BrooklynProperties;
import org.apache.brooklyn.core.location.internal.LocationInternal;
import org.apache.brooklyn.core.mgmt.ha.HighAvailabilityManagerImpl;
import org.apache.brooklyn.core.mgmt.ha.ManagementPlaneSyncRecordPersisterToObjectStore;
import org.apache.brooklyn.core.mgmt.internal.BrooklynObjectManagementMode;
import org.apache.brooklyn.core.mgmt.internal.LocalManagementContext;
import org.apache.brooklyn.core.mgmt.internal.ManagementContextInternal;
import org.apache.brooklyn.core.mgmt.internal.ManagementTransitionMode;
import org.apache.brooklyn.core.mgmt.persist.BrooklynMementoPersisterToObjectStore;
import org.apache.brooklyn.core.mgmt.persist.FileBasedObjectStore;
import org.apache.brooklyn.core.mgmt.persist.PersistMode;
import org.apache.brooklyn.core.mgmt.persist.PersistenceObjectStore;
import org.apache.brooklyn.core.mgmt.rebind.Dumpers.Pointer;
import org.apache.brooklyn.core.mgmt.rebind.dto.BrooklynMementoImpl;
import org.apache.brooklyn.core.mgmt.rebind.dto.MementoValidators;
import org.apache.brooklyn.core.server.BrooklynServerConfig;
import org.apache.brooklyn.core.test.entity.LocalManagementContextForTests;
import org.apache.brooklyn.util.io.FileUtil;
import org.apache.brooklyn.util.javalang.Serializers;
import org.apache.brooklyn.util.javalang.Serializers.ObjectReplacer;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

public class RebindTestUtils {

    private static final Logger LOG = LoggerFactory.getLogger(RebindTestUtils.class);

    // Virtualbox sometimes hangs for exactly 30 seconds on rename(3) or delete(3), confirmed by strace.
    // See FileBasedStoreObjectAccessorWriterTest.testSimpleOperationsDelay() for a simple test to reproduce it.
    private static final Duration TIMEOUT = Duration.seconds(40);

    public static <T> T serializeAndDeserialize(T memento) throws Exception {
        ObjectReplacer replacer = new ObjectReplacer() {
            private final Map<Pointer, Object> replaced = Maps.newLinkedHashMap();

            @Override public Object replace(Object toserialize) {
                if (toserialize instanceof Location || toserialize instanceof Entity) {
                    Pointer pointer = new Pointer(((Identifiable)toserialize).getId());
                    replaced.put(pointer, toserialize);
                    return pointer;
                }
                return toserialize;
            }
            @Override public Object resolve(Object todeserialize) {
                if (todeserialize instanceof Pointer) {
                    return checkNotNull(replaced.get(todeserialize), todeserialize);
                }
                return todeserialize;
            }
        };

        try {
            return Serializers.reconstitute(memento, replacer);
        } catch (Exception e) {
            try {
                Dumpers.logUnserializableChains(memento, replacer);
                //Dumpers.deepDumpSerializableness(memento);
            } catch (Throwable t) {
                LOG.warn("Error logging unserializable chains for memento "+memento+" (propagating original exception)", t);
            }
            throw e;
        }
    }
    
    public static void deleteMementoDir(String path) {
        deleteMementoDir(new File(path));
    }

    public static void deleteMementoDir(File f) {
        FileBasedObjectStore.deleteCompletely(f);
    }

    public static void checkMementoSerializable(Application app) throws Exception {
        BrooklynMemento memento = newBrooklynMemento(app.getManagementContext());
        checkMementoSerializable(memento);
    }

    public static void checkMementoSerializable(BrooklynMemento memento) throws Exception {
        serializeAndDeserialize(memento);
    }

    public static LocalManagementContext newPersistingManagementContext(File mementoDir, ClassLoader classLoader) {
        return managementContextBuilder(mementoDir, classLoader).buildStarted();
    }
    public static LocalManagementContext newPersistingManagementContext(File mementoDir, ClassLoader classLoader, long persistPeriodMillis) {
        return managementContextBuilder(mementoDir, classLoader)
                .persistPeriodMillis(persistPeriodMillis)
                .buildStarted();
    }
    
    public static LocalManagementContext newPersistingManagementContextUnstarted(File mementoDir, ClassLoader classLoader) {
        return managementContextBuilder(mementoDir, classLoader).buildUnstarted();
    }

    public static ManagementContextBuilder managementContextBuilder(File mementoDir, ClassLoader classLoader) {
        return new ManagementContextBuilder(classLoader, mementoDir);
    }
    public static ManagementContextBuilder managementContextBuilder(ClassLoader classLoader, File mementoDir) {
        return new ManagementContextBuilder(classLoader, mementoDir);
    }
    public static ManagementContextBuilder managementContextBuilder(ClassLoader classLoader, PersistenceObjectStore objectStore) {
        return new ManagementContextBuilder(classLoader, objectStore);
    }

    public static class ManagementContextBuilder {
        final ClassLoader classLoader;
        BrooklynProperties properties;
        PersistenceObjectStore objectStore;
        Duration persistPeriod = Duration.millis(100);
        PersistMode persistMode = PersistMode.AUTO;
        HighAvailabilityMode haMode = HighAvailabilityMode.DISABLED;
        boolean forLive;
        boolean enableOsgi = false;
        boolean reuseOsgi = true;
        boolean emptyCatalog;
        private boolean enablePersistenceBackups = true;
        
        ManagementContextBuilder(File mementoDir, ClassLoader classLoader) {
            this(classLoader, new FileBasedObjectStore(mementoDir));
        }
        ManagementContextBuilder(ClassLoader classLoader, File mementoDir) {
            this(classLoader, new FileBasedObjectStore(mementoDir));
        }
        ManagementContextBuilder(ClassLoader classLoader, PersistenceObjectStore objStore) {
            this.classLoader = checkNotNull(classLoader, "classLoader");
            this.objectStore = checkNotNull(objStore, "objStore");
        }
        
        public ManagementContextBuilder persistPeriodMillis(long persistPeriodMillis) {
            checkArgument(persistPeriodMillis > 0, "persistPeriodMillis must be greater than 0; was "+persistPeriodMillis);
            return persistPeriod(Duration.millis(persistPeriodMillis));
        }
        public ManagementContextBuilder persistPeriod(Duration persistPeriod) {
            checkNotNull(persistPeriod);
            this.persistPeriod = persistPeriod;
            return this;
        }

        public ManagementContextBuilder properties(BrooklynProperties properties) {
            this.properties = checkNotNull(properties, "properties");
            return this;
        }

        public ManagementContextBuilder forLive(boolean val) {
            this.forLive = val;
            return this;
        }

        public ManagementContextBuilder enablePersistenceBackups(boolean val) {
            this.enablePersistenceBackups  = val;
            return this;
        }
        /** @deprecated since 0.12.0 use {@link #enableOsgiNonReusable()} or {@link #enableOsgiReusable()} */
        @Deprecated
        public ManagementContextBuilder enableOsgi(boolean val) {
            this.enableOsgi = val;
            return this;
        }

        /** as {@link LocalManagementContextForTests.Builder#setOsgiEnablementAndReuse(boolean, boolean)} */
        public ManagementContextBuilder setOsgiEnablementAndReuse(boolean enableOsgi, boolean reuseOsgi) {
            this.enableOsgi = enableOsgi;
            this.reuseOsgi = reuseOsgi;
            return this;
        }
        
        public ManagementContextBuilder enableOsgiReusable() {
            return setOsgiEnablementAndReuse(true, true);
        }
        public ManagementContextBuilder enableOsgiNonReusable() {
            return setOsgiEnablementAndReuse(true, false);
        }

        public ManagementContextBuilder emptyCatalog() {
            this.emptyCatalog = true;
            return this;
        }

        public ManagementContextBuilder emptyCatalog(boolean val) {
            this.emptyCatalog = val;
            return this;
        }

        public ManagementContextBuilder persistMode(PersistMode val) {
            checkNotNull(val, "persistMode");
            this.persistMode = val;
            if (persistMode == PersistMode.DISABLED) {
                haMode(HighAvailabilityMode.DISABLED);
            }
            return this;
        }

        public ManagementContextBuilder haMode(HighAvailabilityMode val) {
            checkNotNull(val, "haMode");
            this.haMode = val;
            return this;
        }

        /**
         * What you could actually want is {@link #buildStarted()} with builder properties set to
         * {@code .persistMode(PersistMode.DISABLED).haMode(HighAvailabilityMode.DISABLED)}
         */
        public LocalManagementContext buildUnstarted() {
            LocalManagementContext unstarted;
            BrooklynProperties properties = this.properties != null
                    ? this.properties
                    : BrooklynProperties.Factory.newDefault();
            if (this.emptyCatalog) {
                properties.putIfAbsent(BrooklynServerConfig.BROOKLYN_CATALOG_URL, ManagementContextInternal.EMPTY_CATALOG_URL);
            }
            if (!enablePersistenceBackups) {
                properties.putIfAbsent(BrooklynServerConfig.PERSISTENCE_BACKUPS_REQUIRED_ON_DEMOTION, false);
                properties.putIfAbsent(BrooklynServerConfig.PERSISTENCE_BACKUPS_REQUIRED_ON_PROMOTION, false);
                properties.putIfAbsent(BrooklynServerConfig.PERSISTENCE_BACKUPS_REQUIRED, false);
            }
            if (forLive) {
                unstarted = new LocalManagementContext(properties);
            } else {
                unstarted = LocalManagementContextForTests.builder(true)
                        .useProperties(properties)
                        .setOsgiEnablementAndReuse(enableOsgi, reuseOsgi)
                        .disablePersistenceBackups(!enablePersistenceBackups)
                        .build();
            }
            
            objectStore.injectManagementContext(unstarted);
            objectStore.prepareForSharedUse(PersistMode.AUTO, haMode);
            BrooklynMementoPersisterToObjectStore newPersister = new BrooklynMementoPersisterToObjectStore(
                    objectStore, 
                    unstarted, 
                    classLoader);
            ((RebindManagerImpl) unstarted.getRebindManager()).setPeriodicPersistPeriod(persistPeriod);
            unstarted.getRebindManager().setPersister(newPersister, PersistenceExceptionHandlerImpl.builder().build());
            // set the HA persister, in case any children want to use HA
            unstarted.getHighAvailabilityManager().setPersister(new ManagementPlaneSyncRecordPersisterToObjectStore(unstarted, objectStore, classLoader));
            return unstarted;
        }

        public LocalManagementContext buildStarted() {
            // semantics were this:
            // return buildStarted(null, false);
            return buildStarted(null, true);
        }

        public LocalManagementContext buildStarted(Consumer<LocalManagementContext> optionalFinalActions) {
            return buildStarted(optionalFinalActions, true);
        }
        public LocalManagementContext buildStarted(Consumer<LocalManagementContext> optionalFinalActions, boolean noteStartupCompleted) {
            LocalManagementContext unstarted = buildUnstarted();
            
            // Follows BasicLauncher logic for initialising persistence.
            // TODO It should really be encapsulated in a common entry point
            if (persistMode == PersistMode.DISABLED) {
                unstarted.generateManagementPlaneId();
                unstarted.getCatalogInitialization().populateInitialCatalogOnly();
                unstarted.getHighAvailabilityManager().disabled(persistMode != PersistMode.DISABLED);
            } else if (haMode == HighAvailabilityMode.DISABLED) {
                unstarted.getRebindManager().rebind(classLoader, null, ManagementNodeState.MASTER);
                unstarted.getRebindManager().startPersistence();
                unstarted.getHighAvailabilityManager().disabled(persistMode != PersistMode.DISABLED);
            } else {
                unstarted.getHighAvailabilityManager().start(haMode);
            }
            if (optionalFinalActions!=null) {
                optionalFinalActions.accept(unstarted);
            }
            if (noteStartupCompleted) unstarted.noteStartupComplete();
            return unstarted;
        }

    }

    /**
     * Convenience for common call; delegates to {@link #rebind(RebindOptions)}
     */
    public static Application rebind(File mementoDir, ClassLoader classLoader) throws Exception {
        return rebind(RebindOptions.create()
                .mementoDir(mementoDir)
                .classLoader(classLoader));
    }
    
    public static Application rebind(RebindOptions options) throws Exception {
        boolean hadApps = true;
        if (options!=null && options.origManagementContext!=null && options.origManagementContext.getApplications().isEmpty()) {
            // clearly had no apps before, so don't treat as an error
            hadApps = false;
        }
        Collection<Application> newApps = rebindAll(options);
        if (newApps.isEmpty()) {
            if (options.haMode != null && options.haMode != HighAvailabilityMode.DISABLED) {
                // will rebind async, when promoted to master.
                // Dont't assert here! Rely on caller to wait.
                return null;
            } else if (hadApps) {
                throw new IllegalStateException("Application could not be found after rebind; serialization probably failed");
            } else {
                // no apps before; probably testing catalog
                return null;
            }
        }
        Function<Collection<Application>, Application> chooser = options.applicationChooserOnRebind;
        if (chooser != null) {
            return chooser.apply(newApps);
        } else {
            return Iterables.getFirst(newApps, null);
        }
    }

    public static Collection<Application> rebindAll(RebindOptions options) throws Exception {
        File mementoDir = options.mementoDir;
        File mementoDirBackup = options.mementoDirBackup;
        ClassLoader classLoader = checkNotNull(options.classLoader, "classLoader");
        ManagementContextInternal origManagementContext = (ManagementContextInternal) options.origManagementContext;
        ManagementContextInternal newManagementContext = (ManagementContextInternal) options.newManagementContext;
        PersistenceObjectStore objectStore = options.objectStore;
        HighAvailabilityMode haMode = (options.haMode == null ? HighAvailabilityMode.DISABLED : options.haMode);
        RebindExceptionHandler exceptionHandler = options.exceptionHandler;
        boolean hasPersister = newManagementContext != null && newManagementContext.getRebindManager().getPersister() != null;
        boolean hasHaPersister = newManagementContext != null && newManagementContext.getHighAvailabilityManager().getPersister() != null;
        boolean checkSerializable = options.checkSerializable;
        boolean terminateOrigManagementContext = options.terminateOrigManagementContext;
        boolean clearOrigManagementContext = options.clearOrigManagementContext;
        Function<BrooklynMementoPersister, Void> stateTransformer = options.stateTransformer;
        
        LOG.info("Rebinding app, using mementoDir " + mementoDir + "; object store " + objectStore);

        if (newManagementContext == null) {
            // TODO Could use empty properties, to save reading brooklyn.properties file.
            // Would that affect any tests?
            newManagementContext = new LocalManagementContextForTests(BrooklynProperties.Factory.newDefault());
        }
        
        if (!hasPersister) {
            if (objectStore == null) {
                objectStore = new FileBasedObjectStore(checkNotNull(mementoDir, "mementoDir and objectStore must not both be null"));
            }
            objectStore.injectManagementContext(newManagementContext);
            objectStore.prepareForSharedUse(PersistMode.AUTO, haMode);
            
            BrooklynMementoPersisterToObjectStore newPersister = new BrooklynMementoPersisterToObjectStore(
                    objectStore,
                    newManagementContext,
                    classLoader);
            newManagementContext.getRebindManager().setPersister(newPersister, PersistenceExceptionHandlerImpl.builder().build());
        } else {
            if (objectStore != null) throw new IllegalStateException("Must not supply ManagementContext with persister and an object store");
        }
        
        if (checkSerializable) {
            checkNotNull(origManagementContext, "must supply origManagementContext with checkSerializable");
            RebindTestUtils.checkCurrentMementoSerializable(origManagementContext);
        }

        if (clearOrigManagementContext) {
            checkNotNull(origManagementContext, "must supply origManagementContext with terminateOrigManagementContext");
            ((HighAvailabilityManagerImpl)origManagementContext.getHighAvailabilityManager()).clearManagedItems(
                    ManagementTransitionMode.transitioning(BrooklynObjectManagementMode.MANAGED_PRIMARY, BrooklynObjectManagementMode.UNMANAGED_PERSISTED) );
        }
        if (terminateOrigManagementContext) {
            checkNotNull(origManagementContext, "must supply origManagementContext with terminateOrigManagementContext");
            origManagementContext.terminate();
        }

        if (mementoDirBackup != null) {
            FileUtil.copyDir(mementoDir, mementoDirBackup);
            FileUtil.setFilePermissionsTo700(mementoDirBackup);
        }
        
        if (stateTransformer != null) {
            BrooklynMementoPersister persister = newManagementContext.getRebindManager().getPersister();
            stateTransformer.apply(persister);
        }
        
        if (haMode == HighAvailabilityMode.DISABLED) {
            HighAvailabilityManager haManager = newManagementContext.getHighAvailabilityManager();
            haManager.disabled(true);
            
            List<Application> newApps = newManagementContext.getRebindManager().rebind(
                    classLoader, 
                    exceptionHandler, 
                    (haMode == HighAvailabilityMode.DISABLED) ? ManagementNodeState.MASTER : ManagementNodeState.of(haMode).get());
            newManagementContext.getRebindManager().startPersistence();
            ((LocalManagementContext)newManagementContext).noteStartupComplete();

            return newApps;

        } else {
            HighAvailabilityManager haManager = newManagementContext.getHighAvailabilityManager();
            
            if (!hasHaPersister) {
                if (hasPersister) throw new IllegalStateException("Must not supply persister for RebindManager but not for HighAvailabilityManager");
                assert objectStore != null;
                
                ManagementPlaneSyncRecordPersister persister =
                    new ManagementPlaneSyncRecordPersisterToObjectStore(newManagementContext,
                        objectStore,
                        newManagementContext.getCatalogClassLoader());
                
                haManager.setPersister(persister);
            }
            
            haManager.start(haMode);
            ((LocalManagementContext)newManagementContext).noteStartupComplete();
            
            // TODO We'll be promoted to master asynchronously; will not yet have done our rebind.
            // Could block here for rebind to complete but do any callers really need us to do that?
            return ImmutableList.of();
        }
    }

    public static void waitForPersisted(Application origApp) throws InterruptedException, TimeoutException {
        waitForPersisted(origApp.getManagementContext());
    }

    public static void waitForPersisted(ManagementContext managementContext) throws InterruptedException, TimeoutException {
        managementContext.getRebindManager().waitForPendingComplete(TIMEOUT, true);
    }

    public static boolean hasPendingPersists(ManagementContext managementContext) {
        return managementContext.getRebindManager().hasPending();
    }

    public static void stopPersistence(Application origApp) throws InterruptedException, TimeoutException {
        stopPersistence(origApp.getManagementContext());
    }

    public static void stopPersistence(ManagementContext managementContext) throws InterruptedException, TimeoutException {
        RebindManager rebindManager = managementContext.getRebindManager();
        rebindManager.waitForPendingComplete(TIMEOUT, true);
        rebindManager.stop();
    }

    public static void checkCurrentMementoSerializable(Application app) throws Exception {
        checkCurrentMementoSerializable(app.getManagementContext());
    }
    
    public static void checkCurrentMementoSerializable(ManagementContext mgmt) throws Exception {
        BrooklynMemento memento = newBrooklynMemento(mgmt);
        serializeAndDeserialize(memento);
    }
    
    /**
     * Dumps out the persisted mementos that are at the given directory.
     * 
     * Binds to the persisted state (as a "hot standby") to load the raw data (as strings), and to write out the
     * entity, location, policy, enricher, feed and catalog-item data.
     * 
     * @param dir The directory containing the persisted state
     */
    public static void dumpMementoDir(File dir) {
        LocalManagementContextForTests mgmt = new LocalManagementContextForTests(BrooklynProperties.Factory.newEmpty());
        FileBasedObjectStore store = null;
        BrooklynMementoPersisterToObjectStore persister = null;
        try {
            store = new FileBasedObjectStore(dir);
            store.injectManagementContext(mgmt);
            store.prepareForSharedUse(PersistMode.AUTO, HighAvailabilityMode.HOT_STANDBY);
            persister = new BrooklynMementoPersisterToObjectStore(store, mgmt, RebindTestUtils.class.getClassLoader());
            BrooklynMementoRawData data = persister.loadMementoRawData(RebindExceptionHandlerImpl.builder().build());
            List<BrooklynObjectType> types = ImmutableList.of(BrooklynObjectType.ENTITY, BrooklynObjectType.LOCATION, 
                    BrooklynObjectType.POLICY, BrooklynObjectType.ENRICHER, BrooklynObjectType.FEED, 
                    BrooklynObjectType.CATALOG_ITEM, BrooklynObjectType.MANAGED_BUNDLE);
            for (BrooklynObjectType type : types) {
                LOG.info(type+" ("+data.getObjectsOfType(type).keySet()+"):");
                for (Map.Entry<String, String> entry : data.getObjectsOfType(type).entrySet()) {
                    LOG.info("\t"+type+" "+entry.getKey()+": "+entry.getValue());
                }
            }
        } finally {
            if (persister != null) persister.stop(false);
            if (store != null) store.close();
            mgmt.terminate();
        }
    }
    
    /**
     * Walks the contents of a ManagementContext, to create a corresponding memento.
     */
    protected static BrooklynMemento newBrooklynMemento(ManagementContext managementContext) {
        BrooklynMementoImpl.Builder builder = BrooklynMementoImpl.builder();
                
        for (Application app : managementContext.getApplications()) {
            builder.applicationId(app.getId());
        }
        for (Entity entity : managementContext.getEntityManager().getEntities()) {
            builder.entity(((EntityInternal)entity).getRebindSupport().getMemento());
        }
        for (Location location : managementContext.getLocationManager().getLocations()) {
            builder.location(((LocationInternal)location).getRebindSupport().getMemento());
            if (location.getParent() == null) {
                builder.topLevelLocationId(location.getId());
            }
        }

        BrooklynMemento result = builder.build();
        MementoValidators.validateMemento(result);
        return result;
    }
}
