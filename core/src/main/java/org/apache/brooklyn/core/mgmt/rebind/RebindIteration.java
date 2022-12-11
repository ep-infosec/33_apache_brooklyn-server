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

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.*;
import java.util.function.Supplier;
import static org.apache.brooklyn.core.BrooklynFeatureEnablement.FEATURE_AUTO_FIX_CATALOG_REF_ON_REBIND;
import static org.apache.brooklyn.core.BrooklynFeatureEnablement.FEATURE_BACKWARDS_COMPATIBILITY_INFER_CATALOG_ITEM_ON_REBIND;
import static org.apache.brooklyn.core.catalog.internal.CatalogUtils.newClassLoadingContextForCatalogItems;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.annotations.Beta;
import org.apache.brooklyn.api.catalog.BrooklynCatalog;
import org.apache.brooklyn.api.catalog.CatalogItem;
import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.mgmt.classloading.BrooklynClassLoadingContext;
import org.apache.brooklyn.api.mgmt.ha.ManagementNodeState;
import org.apache.brooklyn.api.mgmt.rebind.RebindContext;
import org.apache.brooklyn.api.mgmt.rebind.RebindExceptionHandler;
import org.apache.brooklyn.api.mgmt.rebind.RebindSupport;
import org.apache.brooklyn.api.mgmt.rebind.mementos.BrooklynMemento;
import org.apache.brooklyn.api.mgmt.rebind.mementos.BrooklynMementoManifest;
import org.apache.brooklyn.api.mgmt.rebind.mementos.BrooklynMementoManifest.EntityMementoManifest;
import org.apache.brooklyn.api.mgmt.rebind.mementos.BrooklynMementoPersister;
import org.apache.brooklyn.api.mgmt.rebind.mementos.BrooklynMementoRawData;
import org.apache.brooklyn.api.mgmt.rebind.mementos.CatalogItemMemento;
import org.apache.brooklyn.api.mgmt.rebind.mementos.EnricherMemento;
import org.apache.brooklyn.api.mgmt.rebind.mementos.EntityMemento;
import org.apache.brooklyn.api.mgmt.rebind.mementos.FeedMemento;
import org.apache.brooklyn.api.mgmt.rebind.mementos.LocationMemento;
import org.apache.brooklyn.api.mgmt.rebind.mementos.ManagedBundleMemento;
import org.apache.brooklyn.api.mgmt.rebind.mementos.Memento;
import org.apache.brooklyn.api.mgmt.rebind.mementos.PolicyMemento;
import org.apache.brooklyn.api.mgmt.rebind.mementos.TreeNode;
import org.apache.brooklyn.api.objs.BrooklynObject;
import org.apache.brooklyn.api.objs.BrooklynObjectType;
import org.apache.brooklyn.api.objs.EntityAdjunct;
import org.apache.brooklyn.api.policy.Policy;
import org.apache.brooklyn.api.sensor.Enricher;
import org.apache.brooklyn.api.sensor.Feed;
import org.apache.brooklyn.api.typereg.BrooklynTypeRegistry;
import org.apache.brooklyn.api.typereg.ManagedBundle;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.core.BrooklynFeatureEnablement;
import org.apache.brooklyn.core.BrooklynLogging;
import org.apache.brooklyn.core.BrooklynLogging.LoggingLevel;
import org.apache.brooklyn.core.catalog.internal.CatalogInitialization;
import org.apache.brooklyn.core.catalog.internal.CatalogInitialization.InstallableManagedBundle;
import org.apache.brooklyn.core.catalog.internal.CatalogUtils;
import org.apache.brooklyn.core.enricher.AbstractEnricher;
import org.apache.brooklyn.core.entity.AbstractApplication;
import org.apache.brooklyn.core.entity.AbstractEntity;
import org.apache.brooklyn.core.entity.EntityAdjuncts;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.feed.AbstractFeed;
import org.apache.brooklyn.core.location.AbstractLocation;
import org.apache.brooklyn.core.location.internal.LocationInternal;
import org.apache.brooklyn.core.mgmt.BrooklynTaskTags;
import org.apache.brooklyn.core.mgmt.classloading.BrooklynClassLoadingContextSequential;
import org.apache.brooklyn.core.mgmt.classloading.JavaBrooklynClassLoadingContext;
import org.apache.brooklyn.core.mgmt.ha.OsgiManager;
import org.apache.brooklyn.core.mgmt.internal.BrooklynObjectManagementMode;
import org.apache.brooklyn.core.mgmt.internal.BrooklynObjectManagerInternal;
import org.apache.brooklyn.core.mgmt.internal.EntityManagerInternal;
import org.apache.brooklyn.core.mgmt.internal.LocalManagementContext;
import org.apache.brooklyn.core.mgmt.internal.LocationManagerInternal;
import org.apache.brooklyn.core.mgmt.internal.ManagementContextInternal;
import org.apache.brooklyn.core.mgmt.internal.ManagementTransitionMode;
import org.apache.brooklyn.core.mgmt.persist.DeserializingClassRenamesProvider;
import org.apache.brooklyn.core.mgmt.persist.PersistenceActivityMetrics;
import org.apache.brooklyn.core.mgmt.rebind.RebindManagerImpl.RebindTracker;
import org.apache.brooklyn.core.objs.AbstractBrooklynObject;
import org.apache.brooklyn.core.objs.BrooklynObjectInternal;
import org.apache.brooklyn.core.objs.proxy.*;
import org.apache.brooklyn.core.policy.AbstractPolicy;
import org.apache.brooklyn.core.typereg.BasicManagedBundle;
import org.apache.brooklyn.core.typereg.BundleUpgradeParser.CatalogUpgrades;
import org.apache.brooklyn.core.typereg.RegisteredTypeNaming;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.ClassLoaderUtils;
import org.apache.brooklyn.util.core.flags.FlagUtils;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.javalang.Reflections;
import org.apache.brooklyn.util.osgi.VersionedName;
import org.apache.brooklyn.util.stream.InputStreamSource;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * Multi-phase deserialization:
 *
 * <ul>
 * <li> 1. load the manifest files and populate the summaries (ID+type) in {@link BrooklynMementoManifest}
 * <li> 2. install bundles, instantiate and reconstruct catalog items
 * <li> 3. instantiate entities+locations -- so that inter-entity references can subsequently
 * be set during deserialize (and entity config/state is set).
 * <li> 4. deserialize the manifests to instantiate the mementos
 * <li> 5. instantiate policies+enrichers+feeds
 * (could probably merge this with (3), depending how they are implemented)
 * <li> 6. reconstruct the locations, policies, etc, then finally entities -- setting all fields and then calling
 * {@link RebindSupport#reconstruct(RebindContext, Memento)}
 * <li> 7. associate policies+enrichers+feeds to all the entities
 * <li> 8. manage the entities
 * </ul>
 * <p>
 * If underlying data-store is changed between first and second manifest read (e.g. to add an
 * entity), then second phase might try to reconstitute an entity that has not been put in
 * the rebindContext. This should not affect normal production usage, because rebind is run
 * against a data-store that is not being written to by other brooklyn instance(s).
 * But clearly it would be desirable to have better locking possible against the backing store.
 *
 * <p>
 * When rebinding to code in OSGi bundles, thecatalog item id context is inferred as follows:
 * most of the time the creator will be passing "my catalog item id"
 * (or API could also take "BrooklynObject me" as a creation context and the
 * receiver query the creator's catalog item id)
 * look at the context entity of Tasks.current() (if set)
 * propagate the catalog item id when doing setEntity, addChild
 * when none of the above work (or they are wrong) let the user specify the catalog item
 * <p>
 * Precedence of setting the catalog item ID:
 * 1. User-supplied catalog item ID.
 * 2. Creating from a catalog item - all items resolved during the creation of a spec
 * from a catalog item receive the catalog item's ID as context.
 * 3. When using the Java API for creating specs get the catalog item ID from the
 * context entity of the Tasks.current() task.
 * 4. Propagate the context catalog item ID to children, adjuncts if they don't have one already.
 */
public abstract class RebindIteration {

    private static final Logger LOG = LoggerFactory.getLogger(RebindIteration.class);

    protected final RebindManagerImpl rebindManager;

    protected final ClassLoader classLoader;
    protected final RebindExceptionHandler exceptionHandler;
    protected final ManagementNodeState mode;
    protected final ManagementContextInternal managementContext;

    protected final Semaphore rebindActive;
    protected final AtomicInteger readOnlyRebindCount;
    protected final PersistenceActivityMetrics rebindMetrics;
    protected final BrooklynMementoPersister persistenceStoreAccess;

    protected final AtomicBoolean iterationStarted = new AtomicBoolean();
    protected final RebindContextImpl rebindContext;
    protected final Reflections reflections;
    protected final BrooklynObjectInstantiator instantiator;

    // populated in the course of a run

    // set on run start

    protected Stopwatch timer;
    /**
     * phase is used to ensure our steps are run as we've expected, and documented (in javadoc at top).
     * it's worth the extra effort due to the complication and the subtleties.
     */
    protected int phase = 0;

    // set in first phase

    protected BrooklynMementoRawData mementoRawData;
    protected BrooklynMementoManifest mementoManifest;
    protected Boolean overwritingMaster;
    protected Boolean isEmpty;

    // set later on

    protected BrooklynMemento memento;

    // set near the end

    protected List<Application> applications;

    public RebindIteration(RebindManagerImpl rebindManager,
                           ManagementNodeState mode,
                           ClassLoader classLoader, RebindExceptionHandler exceptionHandler,
                           Semaphore rebindActive, AtomicInteger readOnlyRebindCount, PersistenceActivityMetrics rebindMetrics, BrooklynMementoPersister persistenceStoreAccess
    ) {
        // NB: there is no particularly deep meaning in what is passed in vs what is looked up from the RebindManager which calls us
        // (this is simply a refactoring of previous code to a new class)

        this.rebindManager = rebindManager;

        this.mode = mode;
        this.classLoader = checkNotNull(classLoader, "classLoader");
        this.exceptionHandler = checkNotNull(exceptionHandler, "exceptionHandler");

        this.rebindActive = rebindActive;
        this.readOnlyRebindCount = readOnlyRebindCount;
        this.rebindMetrics = rebindMetrics;
        this.persistenceStoreAccess = persistenceStoreAccess;

        managementContext = rebindManager.getManagementContext();
        rebindContext = new RebindContextImpl(managementContext, exceptionHandler, classLoader);
        reflections = new Reflections(classLoader).applyClassRenames(DeserializingClassRenamesProvider.INSTANCE.loadDeserializingMapping());
        instantiator = new BrooklynObjectInstantiator(classLoader, rebindContext, reflections);

        if (mode == ManagementNodeState.HOT_STANDBY || mode == ManagementNodeState.HOT_BACKUP) {
            rebindContext.setAllReadOnly();
        } else {
            Preconditions.checkState(mode == ManagementNodeState.MASTER, "Must be either master or read only to rebind (mode " + mode + ")");
        }
    }

    public List<Application> getApplications() {
        return applications;
    }

    RebindContextImpl getRebindContext() {
        return rebindContext;
    }

    protected void doRun() throws Exception {
        if (readOnlyRebindCount.get() > 1) {
            // prevent leaking
            rebindManager.stopEntityTasksAndCleanUp("before next read-only rebind", Duration.seconds(10), Duration.seconds(20));
        }

        loadManifestFiles();
        initPlaneId();
        installBundlesAndRebuildCatalog();
        instantiateLocationsAndEntities();
        instantiateMementos();
        // adjuncts depend on actual mementos; whereas entity works off special memento manifest, 
        // and location, bundles etc just take type and id
        instantiateAdjuncts(instantiator);
        reconstructEverything();
        associateAdjunctsWithEntities();
        manageTheObjects();
        finishingUp();
    }

    protected abstract void loadManifestFiles() throws Exception;

    public void run() {
        if (iterationStarted.getAndSet(true)) {
            throw new IllegalStateException("Iteration " + this + " has already run; create a new instance for another rebind pass.");
        }
        try {
            rebindActive.acquire();
        } catch (InterruptedException e) {
            Exceptions.propagate(e);
        }
        try {
            RebindTracker.setRebinding();
            if (ManagementNodeState.isHotProxy(mode)) {
                readOnlyRebindCount.incrementAndGet();
            }

            timer = Stopwatch.createStarted();
            exceptionHandler.onStart(rebindContext);

            doRun();

            exceptionHandler.onDone();

            rebindMetrics.noteSuccess(Duration.of(timer));
            noteErrors(exceptionHandler, null);

        } catch (Exception e) {
            rebindMetrics.noteFailure(Duration.of(timer));

            Exceptions.propagateIfFatal(e);
            noteErrors(exceptionHandler, e);
            throw exceptionHandler.onFailed(e);

        } finally {
            rebindActive.release();
            RebindTracker.reset();
        }
    }

    protected void checkEnteringPhase(int targetPhase) {
        phase++;
        checkContinuingPhase(targetPhase);
    }

    protected void checkContinuingPhase(int targetPhase) {
        if (targetPhase != phase)
            throw new IllegalStateException("Phase mismatch: should be phase " + targetPhase + " but is currently " + phase);
    }

    protected void preprocessManifestFiles() throws Exception {
        checkContinuingPhase(1);

        Preconditions.checkState(mementoRawData != null, "Memento raw data should be set when calling this");
        Preconditions.checkState(mementoManifest == null, "Memento data should not yet be set when calling this");

        // TODO building the manifests should be part of this class (or parent)
        // it does not have anything to do with the persistence store!
        mementoManifest = persistenceStoreAccess.loadMementoManifest(mementoRawData, exceptionHandler);

        overwritingMaster = false;
        isEmpty = mementoManifest.isEmpty();
    }

    @Beta
    public static class InstallableManagedBundleImpl implements CatalogInitialization.InstallableManagedBundle {
        private final ManagedBundleMemento memento;
        private final ManagedBundle managedBundle;

        public InstallableManagedBundleImpl(ManagedBundleMemento memento, ManagedBundle managedBundle) {
            this.memento = memento;
            this.managedBundle = managedBundle;
        }

        @Override
        public ManagedBundle getManagedBundle() {
            return managedBundle;
        }

        @Override
        public Supplier<InputStream> getInputStreamSource() throws IOException {
            return InputStreamSource.ofRenewableSupplier("JAR for " + memento, () -> {
                try {
                    return memento.getJarContent().openStream();
                } catch (IOException e) {
                    throw Exceptions.propagate(e);
                }
            });
        }
    }

    protected void installBundlesAndRebuildCatalog() {
        // Build catalog early so we can load other things.
        // Reads the persisted catalog contents, and passes it all to CatalogInitialization, which decides what to do with it.
        checkEnteringPhase(2);

        CatalogInitialization.RebindLogger rebindLogger = new CatalogInitialization.RebindLogger() {
            @Override
            public void debug(String message, Object... args) {
                logRebindingDebug(message, args);
            }

            @Override
            public void info(String message, Object... args) {
                logRebindingInfo(message, args);
            }
        };

        Map<VersionedName, InstallableManagedBundle> bundles = new LinkedHashMap<>();
        Collection<CatalogItem<?, ?>> legacyCatalogItems = new ArrayList<>();

        // Find the bundles
        if (rebindManager.persistBundlesEnabled) {
            for (ManagedBundleMemento bundleMemento : mementoManifest.getBundles().values()) {
                ManagedBundle managedBundle = instantiator.newManagedBundle(bundleMemento);
                bundles.put(managedBundle.getVersionedName(), new InstallableManagedBundleImpl(bundleMemento, managedBundle));
                logRebindingDebug("Registering bundle "+bundleMemento.getId()+": "+managedBundle);
                rebindContext.registerBundle(bundleMemento.getId(), managedBundle);
            }
        } else {
            logRebindingDebug("Not rebinding bundles; feature disabled: {}", mementoManifest.getBundleIds());
        }

        // Construct the legacy catalog items; don't add them to the catalog here, 
        // but instead pass them to catalogInitialization.populateCatalog.

        if (rebindManager.persistCatalogItemsEnabled) {
            // Instantiate catalog items
            logRebindingDebug("RebindManager instantiating catalog items: {}", mementoManifest.getCatalogItemIds());
            for (CatalogItemMemento catalogItemMemento : mementoManifest.getCatalogItemMementos().values()) {
                logRebindingDebug("RebindManager instantiating catalog item {}", catalogItemMemento);
                try {
                    CatalogItem<?, ?> catalogItem = instantiator.newCatalogItem(catalogItemMemento);
                    rebindContext.registerCatalogItem(catalogItemMemento.getId(), catalogItem);
                    legacyCatalogItems.add(catalogItem);
                } catch (Exception e) {
                    exceptionHandler.onCreateFailed(BrooklynObjectType.CATALOG_ITEM, catalogItemMemento.getId(), catalogItemMemento.getType(), e);
                }
            }

            // Reconstruct catalog entries
            logRebindingDebug("RebindManager reconstructing catalog items");
            for (CatalogItemMemento catalogItemMemento : mementoManifest.getCatalogItemMementos().values()) {
                CatalogItem<?, ?> item = rebindContext.getCatalogItem(catalogItemMemento.getId());
                logRebindingDebug("RebindManager reconstructing catalog item {}", catalogItemMemento);
                if (item == null) {
                    exceptionHandler.onNotFound(BrooklynObjectType.CATALOG_ITEM, catalogItemMemento.getId());
                } else {
                    try {
                        item.getRebindSupport().reconstruct(rebindContext, catalogItemMemento);
                        if (item instanceof AbstractBrooklynObject) {
                            AbstractBrooklynObject.class.cast(item).setManagementContext(managementContext);
                        }
                    } catch (Exception e) {
                        exceptionHandler.onRebindFailed(BrooklynObjectType.CATALOG_ITEM, item, e);
                    }
                }
            }

        } else {
            logRebindingDebug("Not rebinding catalog; feature disabled: {}", mementoManifest.getCatalogItemIds());
        }


        // Delegates to CatalogInitialization; see notes there.
        CatalogInitialization.PersistedCatalogState persistedCatalogState = new CatalogInitialization.PersistedCatalogState(bundles, legacyCatalogItems);

        CatalogInitialization catInit = managementContext.getCatalogInitialization();
        catInit.clearForSubsequentCatalogInit();
        catInit.populateInitialAndPersistedCatalog(mode, persistedCatalogState, exceptionHandler, rebindLogger);
    }

    protected void instantiateLocationsAndEntities() {

        checkEnteringPhase(3);

        // Instantiate locations
        logRebindingDebug("RebindManager instantiating locations: {}", mementoManifest.getLocationIdToType().keySet());
        for (Map.Entry<String, String> entry : mementoManifest.getLocationIdToType().entrySet()) {
            String locId = entry.getKey();
            String locType = entry.getValue();
            if (LOG.isTraceEnabled()) LOG.trace("RebindManager instantiating location {}", locId);

            try {
                Location location = instantiator.newLocation(locId, locType);
                rebindContext.registerLocation(locId, location);
            } catch (Exception e) {
                exceptionHandler.onCreateFailed(BrooklynObjectType.LOCATION, locId, locType, e);
            }
        }

        // Instantiate entities
        logRebindingDebug("RebindManager instantiating entities: {}", mementoManifest.getEntityIdToManifest().keySet());
        for (Map.Entry<String, EntityMementoManifest> entry : mementoManifest.getEntityIdToManifest().entrySet()) {
            String entityId = entry.getKey();
            EntityMementoManifest entityManifest = entry.getValue();

            if (LOG.isTraceEnabled()) LOG.trace("RebindManager instantiating entity {}", entityId);

            try {
                Entity entity = instantiator.newEntity(entityManifest);
                ((EntityInternal) entity).getManagementSupport().setReadOnly(rebindContext.isReadOnly(entity));
                rebindContext.registerEntity(entityId, entity);

            } catch (Exception e) {
                exceptionHandler.onCreateFailed(BrooklynObjectType.ENTITY, entityId, entityManifest.getType(), e);
            }
        }
    }

    // creation of adjuncts can be called from different threads; it should be rare however, so easiest to synchronize
    protected Map<String,EntityAdjunct> adjunctProxies = Collections.synchronizedMap(MutableMap.of());
    protected <T extends EntityAdjunct> T createAdjunctProxy(Class<T> adjunctType, String id) {
        return (T) adjunctProxies.computeIfAbsent(id, (id2) -> EntityAdjuncts.createProxyForId(adjunctType, id));
    }

    protected void instantiateMementos() throws IOException {

        checkEnteringPhase(4);

        if (!adjunctProxies.isEmpty()) {
            LOG.warn("Had stale adjunct information when rebinding; ignoring: "+adjunctProxies);
        }
        adjunctProxies.clear();

        ((RebindExceptionHandlerImpl)exceptionHandler).setAdjunctProxyCreator(this::createAdjunctProxy);
        memento = persistenceStoreAccess.loadMemento(mementoRawData, rebindContext.lookup(), exceptionHandler);
        ((RebindExceptionHandlerImpl)exceptionHandler).setAdjunctProxyCreator(null);
    }

    protected void initPlaneId() {
        String persistedPlaneId = mementoRawData.getPlaneId();
        if (persistedPlaneId == null) {
            if (!mementoRawData.isEmpty()) {
                LOG.warn("Rebinding against existing persisted state, but no planeId found. Will generate a new one. " +
                        "Expected if this is the first rebind after upgrading to Brooklyn 0.12.0+");
            }
            if (managementContext.getManagementPlaneIdMaybe().isAbsent()) {
                ((LocalManagementContext) managementContext).generateManagementPlaneId();
            }
        } else {
            ((LocalManagementContext) managementContext).setManagementPlaneId(persistedPlaneId);
        }
    }

    protected void instantiateAdjuncts(BrooklynObjectInstantiator instantiator) {

        checkEnteringPhase(5);

        // Instantiate policies
        if (rebindManager.persistPoliciesEnabled) {
            logRebindingDebug("RebindManager instantiating policies: {}", memento.getPolicyIds());
            for (PolicyMemento policyMemento : memento.getPolicyMementos().values()) {
                logRebindingDebug("RebindManager instantiating policy {}", policyMemento);

                try {
                    Policy policy = instantiator.newPolicy(policyMemento);

                    EntityAdjunctProxyImpl.resetDelegate( adjunctProxies.remove(policy.getId()) , policy);

                    rebindContext.registerPolicy(policyMemento.getId(), policy);
                } catch (Exception e) {
                    exceptionHandler.onCreateFailed(BrooklynObjectType.POLICY, policyMemento.getId(), policyMemento.getType(), e);
                }
            }
        } else {
            logRebindingDebug("Not rebinding policies; feature disabled: {}", memento.getPolicyIds());
        }

        // Instantiate enrichers
        if (rebindManager.persistEnrichersEnabled) {
            logRebindingDebug("RebindManager instantiating enrichers: {}", memento.getEnricherIds());
            for (EnricherMemento enricherMemento : memento.getEnricherMementos().values()) {
                logRebindingDebug("RebindManager instantiating enricher {}", enricherMemento);

                try {
                    Enricher enricher = instantiator.newEnricher(enricherMemento);
                    EntityAdjunctProxyImpl.resetDelegate( adjunctProxies.remove(enricher.getId()) , enricher);
                    rebindContext.registerEnricher(enricherMemento.getId(), enricher);
                } catch (Exception e) {
                    exceptionHandler.onCreateFailed(BrooklynObjectType.ENRICHER, enricherMemento.getId(), enricherMemento.getType(), e);
                }
            }
        } else {
            logRebindingDebug("Not rebinding enrichers; feature disabled: {}", memento.getEnricherIds());
        }


        // Instantiate feeds
        if (rebindManager.persistFeedsEnabled) {
            logRebindingDebug("RebindManager instantiating feeds: {}", memento.getFeedIds());
            for (FeedMemento feedMemento : memento.getFeedMementos().values()) {
                if (LOG.isDebugEnabled()) LOG.debug("RebindManager instantiating feed {}", feedMemento);

                try {
                    Feed feed = instantiator.newFeed(feedMemento);
                    EntityAdjunctProxyImpl.resetDelegate( adjunctProxies.remove(feed.getId()) , feed);
                    rebindContext.registerFeed(feedMemento.getId(), feed);
                    // started during associateAdjunctsWithEntities by RebindAdjuncts
                } catch (Exception e) {
                    exceptionHandler.onCreateFailed(BrooklynObjectType.FEED, feedMemento.getId(), feedMemento.getType(), e);
                }
            }
        } else {
            logRebindingDebug("Not rebinding feeds; feature disabled: {}", memento.getFeedIds());
        }

        if (!adjunctProxies.isEmpty()) {
            LOG.warn("Adjunct proxies not empty, likely indicating dangling references: "+adjunctProxies);
            adjunctProxies.entrySet().forEach(entry -> {
                if (entry.getValue() instanceof Policy) exceptionHandler.onDanglingPolicyRef(entry.getKey());
                else if (entry.getValue() instanceof Enricher) exceptionHandler.onDanglingEnricherRef(entry.getKey());
                else {
                    LOG.warn("Adjunct proxy for "+entry.getKey()+" is of unexpected type; "+entry.getValue()+"; reporting as dangling of unknown type");
                    exceptionHandler.onDanglingUntypedItemRef(entry.getKey());
                }
            });
            adjunctProxies.clear();
        }

    }

    protected void reconstructEverything() {

        checkEnteringPhase(6);

        // Reconstruct locations
        logRebindingDebug("RebindManager reconstructing locations");
        for (LocationMemento locMemento : sortParentFirst(memento.getLocationMementos()).values()) {
            Location location = rebindContext.getLocation(locMemento.getId());
            logRebindingDebug("RebindManager reconstructing location {}", locMemento);
            if (location == null) {
                // usually because of creation-failure, when not using fail-fast
                exceptionHandler.onNotFound(BrooklynObjectType.LOCATION, locMemento.getId());
            } else {
                try {
                    ((LocationInternal) location).getRebindSupport().reconstruct(rebindContext, locMemento);
                } catch (Exception e) {
                    exceptionHandler.onRebindFailed(BrooklynObjectType.LOCATION, location, e);
                }
            }
        }

        // Reconstruct policies
        if (rebindManager.persistPoliciesEnabled) {
            logRebindingDebug("RebindManager reconstructing policies");
            for (PolicyMemento policyMemento : memento.getPolicyMementos().values()) {
                Policy policy = rebindContext.getPolicy(policyMemento.getId());
                logRebindingDebug("RebindManager reconstructing policy {}", policyMemento);

                if (policy == null) {
                    // usually because of creation-failure, when not using fail-fast
                    exceptionHandler.onNotFound(BrooklynObjectType.POLICY, policyMemento.getId());
                } else {
                    try {
                        policy.getRebindSupport().reconstruct(rebindContext, policyMemento);
                    } catch (Exception e) {
                        exceptionHandler.onRebindFailed(BrooklynObjectType.POLICY, policy, e);
                        rebindContext.unregisterPolicy(policy);
                    }
                }
            }
        }

        // Reconstruct enrichers
        if (rebindManager.persistEnrichersEnabled) {
            logRebindingDebug("RebindManager reconstructing enrichers");
            for (EnricherMemento enricherMemento : memento.getEnricherMementos().values()) {
                Enricher enricher = rebindContext.getEnricher(enricherMemento.getId());
                logRebindingDebug("RebindManager reconstructing enricher {}", enricherMemento);

                if (enricher == null) {
                    // usually because of creation-failure, when not using fail-fast
                    exceptionHandler.onNotFound(BrooklynObjectType.ENRICHER, enricherMemento.getId());
                } else {
                    try {
                        enricher.getRebindSupport().reconstruct(rebindContext, enricherMemento);
                    } catch (Exception e) {
                        exceptionHandler.onRebindFailed(BrooklynObjectType.ENRICHER, enricher, e);
                        rebindContext.unregisterEnricher(enricher);
                    }
                }
            }
        }

        // Reconstruct feeds
        if (rebindManager.persistFeedsEnabled) {
            logRebindingDebug("RebindManager reconstructing feeds");
            for (FeedMemento feedMemento : memento.getFeedMementos().values()) {
                Feed feed = rebindContext.getFeed(feedMemento.getId());
                logRebindingDebug("RebindManager reconstructing feed {}", feedMemento);

                if (feed == null) {
                    // usually because of creation-failure, when not using fail-fast
                    exceptionHandler.onNotFound(BrooklynObjectType.FEED, feedMemento.getId());
                } else {
                    try {
                        feed.getRebindSupport().reconstruct(rebindContext, feedMemento);
                    } catch (Exception e) {
                        exceptionHandler.onRebindFailed(BrooklynObjectType.FEED, feed, e);
                        rebindContext.unregisterFeed(feed);
                    }
                }

            }
        }

        // Reconstruct entities
        logRebindingDebug("RebindManager reconstructing entities");
        for (EntityMemento entityMemento : sortParentFirst(memento.getEntityMementos()).values()) {
            Entity entity = rebindContext.lookup().lookupEntity(entityMemento.getId());
            logRebindingDebug("RebindManager reconstructing entity {}", entityMemento);

            if (entity == null) {
                // usually because of creation-failure, when not using fail-fast
                exceptionHandler.onNotFound(BrooklynObjectType.ENTITY, entityMemento.getId());
            } else {
                try {
                    entityMemento.injectTypeClass(entity.getClass());
                    ((EntityInternal) entity).getRebindSupport().reconstruct(rebindContext, entityMemento);
                } catch (Exception e) {
                    exceptionHandler.onRebindFailed(BrooklynObjectType.ENTITY, entity, e);
                }
            }
        }
    }

    protected void associateAdjunctsWithEntities() {
        checkEnteringPhase(7);

        logRebindingDebug("RebindManager associating adjuncts to entities");
        for (EntityMemento entityMemento : sortParentFirst(memento.getEntityMementos()).values()) {
            Entity entity = rebindContext.getEntity(entityMemento.getId());
            logRebindingDebug("RebindManager associating adjuncts to entity {}", entityMemento);

            if (entity == null) {
                // usually because of creation-failure, when not using fail-fast
                exceptionHandler.onNotFound(BrooklynObjectType.ENTITY, entityMemento.getId());
            } else {
                // Must execute in entity's context, so policy.setEntity can resolve config (BROOKLYN-549).
                Runnable body = new Runnable() {
                    public void run() {
                        try {
                            entityMemento.injectTypeClass(entity.getClass());
                            // TODO these call to the entity which in turn sets the entity on the underlying feeds and enrichers;
                            // that is taken as the cue to start, but it should not be. start should be a separate call.
                            ((EntityInternal) entity).getRebindSupport().addPolicies(rebindContext, entityMemento);
                            ((EntityInternal) entity).getRebindSupport().addEnrichers(rebindContext, entityMemento);
                            ((EntityInternal) entity).getRebindSupport().addFeeds(rebindContext, entityMemento);
                        } catch (Exception e) {
                            exceptionHandler.onRebindFailed(BrooklynObjectType.ENTITY, entity, e);
                        }
                    }
                };
                ((EntityInternal) entity).getExecutionContext().get(Tasks.<Void>builder()
                        .displayName("Rebind adjuncts for " + entity.getId())
                        .tag(BrooklynTaskTags.ENTITY_INITIALIZATION)
                        .dynamic(false)
                        .body(new RebindAdjuncts(entityMemento, entity, rebindContext, exceptionHandler))
                        .build());
            }
        }
    }

    protected static class RebindAdjuncts implements Runnable {
        private EntityMemento entityMemento;
        private Entity entity;
        private RebindContextImpl rebindContext;
        private RebindExceptionHandler exceptionHandler;

        public RebindAdjuncts(EntityMemento entityMemento, Entity entity, RebindContextImpl rebindContext, RebindExceptionHandler exceptionHandler) {
            this.entityMemento = entityMemento;
            this.entity = entity;
            this.rebindContext = rebindContext;
            this.exceptionHandler = exceptionHandler;
        }

        @Override
        public void run() {
            try {
                entityMemento.injectTypeClass(entity.getClass());
                // TODO these call to the entity which in turn sets the entity on the underlying feeds and enrichers;
                // that is taken as the cue to start, but it should not be. start should be a separate call.
                ((EntityInternal) entity).getRebindSupport().addPolicies(rebindContext, entityMemento);
                ((EntityInternal) entity).getRebindSupport().addEnrichers(rebindContext, entityMemento);
                ((EntityInternal) entity).getRebindSupport().addFeeds(rebindContext, entityMemento);

                entityMemento = null;
                entity = null;
            } catch (Exception e) {
                exceptionHandler.onRebindFailed(BrooklynObjectType.ENTITY, entity, e);
            }
        }
    }

    protected void manageTheObjects() {

        checkEnteringPhase(8);

        logRebindingDebug("RebindManager managing locations");
        LocationManagerInternal locationManager = (LocationManagerInternal) managementContext.getLocationManager();
        Set<String> oldLocations = Sets.newLinkedHashSet(locationManager.getLocationIds());
        for (Location location : rebindContext.getLocations()) {
            ManagementTransitionMode oldMode = updateTransitionMode(locationManager, location);
            if (oldMode != null)
                oldLocations.remove(location.getId());
        }
        for (Location location : rebindContext.getLocations()) {
            if (location.getParent() == null) {
                // manage all root locations
                try {
                    ((LocationManagerInternal) managementContext.getLocationManager()).manageRebindedRoot(location);
                } catch (Exception e) {
                    exceptionHandler.onManageFailed(BrooklynObjectType.LOCATION, location, e);
                }
            }
        }
        // TODO could also see about purging unreferenced locations
        cleanupOldLocations(oldLocations);

        // Manage the top-level apps (causing everything under them to become managed)
        logRebindingDebug("RebindManager managing entities");
        EntityManagerInternal entityManager = (EntityManagerInternal) managementContext.getEntityManager();
        Set<String> oldEntities = Sets.newLinkedHashSet(entityManager.getEntityIds());
        for (Entity entity : rebindContext.getEntities()) {
            ManagementTransitionMode oldMode = updateTransitionMode(entityManager, entity);
            if (oldMode != null)
                oldEntities.remove(entity.getId());
        }
        List<Application> apps = Lists.newArrayList();
        for (String rootId : getMementoRootEntities()) {
            Entity entity = rebindContext.getEntity(rootId);
            if (entity == null) {
                // usually because of creation-failure, when not using fail-fast
                exceptionHandler.onNotFound(BrooklynObjectType.ENTITY, rootId);
            } else {
                try {
                    entityManager.manageRebindedRoot(entity);
                } catch (Exception e) {
                    exceptionHandler.onManageFailed(BrooklynObjectType.ENTITY, entity, e);
                }
                if (entity instanceof Application)
                    apps.add((Application) entity);
            }
        }
        cleanupOldEntities(oldEntities);

        this.applications = apps;
    }

    private <T extends BrooklynObject> ManagementTransitionMode updateTransitionMode(BrooklynObjectManagerInternal<T> boManager, T bo) {
        ManagementTransitionMode oldTransitionMode = boManager.getLastManagementTransitionMode(bo.getId());

        Boolean isNowReadOnly = rebindContext.isReadOnly(bo);
        BrooklynObjectManagementMode modeBefore, modeAfter;
        if (oldTransitionMode == null) {
            modeBefore = BrooklynObjectManagementMode.UNMANAGED_PERSISTED;
        } else {
            modeBefore = oldTransitionMode.getModeAfter();
        }

        if (isRebindingActiveAgain()) {
            Preconditions.checkState(!Boolean.TRUE.equals(isNowReadOnly));
            Preconditions.checkState(modeBefore == BrooklynObjectManagementMode.MANAGED_PRIMARY);
            modeAfter = BrooklynObjectManagementMode.MANAGED_PRIMARY;
        } else if (isNowReadOnly) {
            modeAfter = BrooklynObjectManagementMode.LOADED_READ_ONLY;
        } else {
            modeAfter = BrooklynObjectManagementMode.MANAGED_PRIMARY;
        }

        ManagementTransitionMode newTransitionMode = ManagementTransitionMode.transitioning(modeBefore, modeAfter);
        boManager.setManagementTransitionMode(bo, newTransitionMode);
        return oldTransitionMode;
    }

    protected abstract boolean isRebindingActiveAgain();

    protected Collection<String> getMementoRootEntities() {
        return memento.getApplicationIds();
    }

    protected abstract void cleanupOldLocations(Set<String> oldLocations);

    protected abstract void cleanupOldEntities(Set<String> oldEntities);

    protected void finishingUp() {

        checkContinuingPhase(8);

        if (!isEmpty) {
            BrooklynLogging.log(LOG, shouldLogRebinding() ? LoggingLevel.INFO : LoggingLevel.DEBUG,
                    "Rebind complete" +
                    (!exceptionHandler.getExceptions().isEmpty() ? ", with errors" :
                        !exceptionHandler.getWarnings().isEmpty() ? ", with warnings" : "") +
                            " (" + mode + (readOnlyRebindCount.get() >= 0 ? ", iteration " + readOnlyRebindCount : "") + ")" +
                            " in {}: {} app{}, {} entit{}, {} location{}, {} polic{}, {} enricher{}, {} feed{}, {} catalog item{}, {} catalog bundle{}{}{}",
                    Time.makeTimeStringRounded(timer), applications.size(), Strings.s(applications),
                    rebindContext.getEntities().size(), Strings.ies(rebindContext.getEntities()),
                    rebindContext.getLocations().size(), Strings.s(rebindContext.getLocations()),
                    rebindContext.getPolicies().size(), Strings.ies(rebindContext.getPolicies()),
                    rebindContext.getEnrichers().size(), Strings.s(rebindContext.getEnrichers()),
                    rebindContext.getFeeds().size(), Strings.s(rebindContext.getFeeds()),
                    rebindContext.getCatalogItems().size(), Strings.s(rebindContext.getCatalogItems()),
                    rebindContext.getBundles().size(), Strings.s(rebindContext.getBundles()),
                    (!exceptionHandler.getExceptions().isEmpty() ? "; errors="+exceptionHandler.getExceptions() : ""),
                    (!exceptionHandler.getWarnings().isEmpty() ? "; warnings="+exceptionHandler.getWarnings() : "")
            );
        }

        // Return the top-level applications
        logRebindingDebug("RebindManager complete; apps: {}", getMementoRootEntities());
    }

    protected void noteErrors(final RebindExceptionHandler exceptionHandler, Exception primaryException) {
        List<Exception> exceptions = exceptionHandler.getExceptions();
        List<String> warnings = exceptionHandler.getWarnings();
        if (primaryException != null || !exceptions.isEmpty() || !warnings.isEmpty()) {
            List<String> messages = MutableList.<String>of();
            if (primaryException != null) messages.add(primaryException.toString());
            for (Exception e : exceptions) messages.add(e.toString());
            for (String w : warnings) messages.add(w);
            rebindMetrics.noteError(messages);
        }
    }

    protected class CatalogItemIdAndSearchPath {
        private String catalogItemId;
        private List<String> searchPath;

        public CatalogItemIdAndSearchPath(String catalogItemId, List<String> searchPath) {
            this.catalogItemId = catalogItemId;
            this.searchPath = searchPath;
        }

        public String getCatalogItemId() {
            return catalogItemId;
        }

        public List<String> getSearchPath() {
            return searchPath;
        }
    }

    protected CatalogItemIdAndSearchPath findCatalogItemIds(ClassLoader cl, Map<String,
            EntityMementoManifest> entityIdToManifest, EntityMementoManifest entityManifest) {

        if (entityManifest.getCatalogItemId() != null) {
            return new CatalogItemIdAndSearchPath(entityManifest.getCatalogItemId(),
                    entityManifest.getCatalogItemIdSearchPath());
        }

        if (BrooklynFeatureEnablement.isEnabled(FEATURE_BACKWARDS_COMPATIBILITY_INFER_CATALOG_ITEM_ON_REBIND)) {
            String typeId = null;
            List<String> searchPath = MutableList.of();
            //First check if any of the parent entities has a catalogItemId set.
            EntityMementoManifest ptr = entityManifest;
            while (ptr != null) {
                final String pId = ptr.getCatalogItemId();
                if (pId != null) {
                    RegisteredType type = managementContext.getTypeRegistry().get(pId);
                    if (type != null) {
                        typeId = type.getId();
                    }
                    for (String id : ptr.getCatalogItemIdSearchPath()) {
                        type = managementContext.getTypeRegistry().get(id);
                        if (type != null) {
                            searchPath.add(type.getId());
                        } else {
                            //Couldn't find a catalog item with this id, but add it anyway and
                            //let the caller deal with the error.
                            //TODO under what circumstances is this permitted?
                            searchPath.add(id);
                        }
                    }
                    return new CatalogItemIdAndSearchPath(typeId, searchPath);
                }
                if (ptr.getParent() != null) {
                    ptr = entityIdToManifest.get(ptr.getParent());
                } else {
                    ptr = null;
                }
            }

            //If no parent entity has the catalogItemId set try to match them by the type we are trying to load.
            //The current convention is to set catalog item IDs to the java type (for both plain java or CAMP plan) they represent.
            //This will be applicable only the first time the store is rebinded, while the catalog items don't have the default
            //version appended to their IDs, but then we will have catalogItemId set on entities so not neede further anyways.
            BrooklynTypeRegistry types = managementContext.getTypeRegistry();
            ptr = entityManifest;
            while (ptr != null) {
                RegisteredType t = types.get(ptr.getType(), BrooklynCatalog.DEFAULT_VERSION);
                if (t != null) {
                    LOG.debug("Inferred catalog item ID " + t.getId() + " for " + entityManifest + " from ancestor " + ptr);
                    return new CatalogItemIdAndSearchPath(t.getId(), ImmutableList.<String>of());
                }
                if (ptr.getParent() != null) {
                    ptr = entityIdToManifest.get(ptr.getParent());
                } else {
                    ptr = null;
                }
            }

            //As a last resort go through all catalog items trying to load the type and use the first that succeeds.
            //But first check if can be loaded from the default classpath
            if (JavaBrooklynClassLoadingContext.create(managementContext).tryLoadClass(entityManifest.getType()).isPresent()) {
                return new CatalogItemIdAndSearchPath(null, ImmutableList.<String>of());
            }

            // TODO get to the point when we can deprecate this behaviour!:
            for (RegisteredType item : types.getAll()) {
                BrooklynClassLoadingContext loader = CatalogUtils.newClassLoadingContext(managementContext, item);
                boolean canLoadClass = loader.tryLoadClass(entityManifest.getType()).isPresent();
                if (canLoadClass) {
                    LOG.warn("Missing catalog item for " + entityManifest.getId() + " (" + entityManifest.getType()
                            + "), inferring as " + item.getId() + " because that is able to load the item");
                    return new CatalogItemIdAndSearchPath(item.getId(), ImmutableList.<String>of());
                }
            }
        }
        return new CatalogItemIdAndSearchPath(null, ImmutableList.<String>of());
    }

    protected static class LoadedClass<T extends BrooklynObject> {
        protected final Class<? extends T> clazz;
        protected final String catalogItemId;
        protected final List<String> searchPath;

        protected LoadedClass(Class<? extends T> clazz, String catalogItemId, List<String> searchPath) {
            this.clazz = clazz;
            this.catalogItemId = catalogItemId;
            this.searchPath = searchPath;
        }
    }

    protected class BrooklynObjectInstantiator {

        protected final ClassLoader classLoader;
        protected final RebindContextImpl rebindContext;
        protected final Reflections reflections;

        protected BrooklynObjectInstantiator(ClassLoader classLoader, RebindContextImpl rebindContext, Reflections reflections) {
            this.classLoader = classLoader;
            this.rebindContext = rebindContext;
            this.reflections = reflections;
        }

        protected Entity newEntity(EntityMementoManifest entityManifest) {
            String entityId = entityManifest.getId();
            CatalogItemIdAndSearchPath idPath =
                    findCatalogItemIds(classLoader, mementoManifest.getEntityIdToManifest(), entityManifest);
            String entityType = entityManifest.getType();

            LoadedClass<? extends Entity> loaded =
                    load(Entity.class, entityType, idPath.getCatalogItemId(), idPath.getSearchPath(), entityId);
            Class<? extends Entity> entityClazz = loaded.clazz;

            Entity entity;

            if (InternalFactory.isNewStyle(entityClazz)) {
                // Not using entityManager.createEntity(EntitySpec) because don't want init() to be called.
                // Creates an uninitialized entity, but that has correct id + proxy.
                InternalEntityFactory entityFactory = managementContext.getEntityFactory();
                entity = entityFactory.constructEntity(entityClazz, Reflections.getAllInterfaces(entityClazz), entityId);

            } else {
                LOG.warn("Deprecated rebind of entity without no-arg constructor; " +
                        "this may not be supported in future versions: id=" + entityId + "; type=" + entityType);

                // There are several possibilities for the constructor; find one that works.
                // Prefer passing in the flags because required for Application to set the management context
                // TODO Feels very hacky!

                Map<Object, Object> flags = Maps.newLinkedHashMap();
                flags.put("id", entityId);
                if (AbstractApplication.class.isAssignableFrom(entityClazz)) flags.put("mgmt", managementContext);

                // TODO document the multiple sources of flags, and the reason for setting the mgmt context *and*
                // supplying it as the flag
                // (NB: merge reported conflict as the two things were added separately)
                entity = invokeConstructor(null, entityClazz,
                        new Object[]{flags}, new Object[]{flags, null}, new Object[]{null}, new Object[0]);

                // In case the constructor didn't take the Map arg, then also set it here.
                // e.g. for top-level app instances such as WebClusterDatabaseExampleApp will (often?) not have
                // interface + constructor.
                // TODO On serializing the memento, we should capture which interfaces so can recreate
                // the proxy+spec (including for apps where there's not an obvious interface).
                FlagUtils.setFieldsFromFlags(ImmutableMap.of("id", entityId), entity);
                if (entity instanceof AbstractApplication) {
                    FlagUtils.setFieldsFromFlags(ImmutableMap.of("mgmt", managementContext), entity);
                }
                ((AbstractEntity) entity).setManagementContext(managementContext);
                managementContext.prePreManage(entity);
            }

            setCatalogItemIds(entity, loaded.catalogItemId, loaded.searchPath);

            return entity;
        }

        protected void setCatalogItemIds(BrooklynObject object, String catalogItemId, List<String> searchPath) {
            final BrooklynObjectInternal internal = (BrooklynObjectInternal) object;
            internal.setCatalogItemIdAndSearchPath(catalogItemId, searchPath);
        }


        protected <T extends BrooklynObject> LoadedClass<? extends T> load(Class<T> bType, Memento memento) {
            return load(bType, memento.getType(), memento.getCatalogItemId(), memento.getCatalogItemIdSearchPath(),
                    memento.getId());
        }

        @SuppressWarnings("unchecked")
        // TODO should prefer a registered type as the type to load (in lieu of jType),
        // but note some callers (enrichers etc) use catalogItemId to be the first entry in search path rather than their actual type,
        // so until callers are all updated all we can do here is load the java type with no guarantee the catalogItemId should be the same.
        // (yoml should help a lot with this.)
        protected <T extends BrooklynObject> LoadedClass<? extends T> load(Class<T> bType, String jType,
                                                                           String catalogItemId, List<String> searchPath, String contextSuchAsId) {
            checkNotNull(jType, "Type of %s (%s) must not be null", contextSuchAsId, bType.getSimpleName());

            CatalogUpgrades.markerForCodeThatLoadsJavaTypesButShouldLoadRegisteredType();

            List<String> warnings = MutableList.of();
            List<String> reboundSearchPath = MutableList.of();
            if (searchPath != null && !searchPath.isEmpty()) {
                for (String searchItemId : searchPath) {
                    String fixedSearchItemId = null;
                    VersionedName searchItemVersionedName = VersionedName.fromString(searchItemId);

                    OsgiManager osgi = managementContext.getOsgiManager().orNull();

                    String bundleUpgraded = CatalogUpgrades.getBundleUpgradedIfNecessary(managementContext, searchItemId);
                    if (bundleUpgraded!=null && !bundleUpgraded.equals(searchItemId)) {
                        logRebindingDebug("Upgrading search path entry of " + bType.getSimpleName().toLowerCase() + " " + contextSuchAsId + " from " + searchItemId + " to bundle " + bundleUpgraded);
                        searchItemVersionedName = VersionedName.fromString(bundleUpgraded);
                    }

                    if (osgi != null) {
                        ManagedBundle bundle = osgi.getManagedBundle(searchItemVersionedName);
                        if (bundle != null) {
                            // found as bundle
                            fixedSearchItemId = searchItemVersionedName.toOsgiString();
                            reboundSearchPath.add(fixedSearchItemId);
                            continue;
                        }
                    }

                    // look for as a type now
                    RegisteredType t1 = managementContext.getTypeRegistry().get(searchItemId);
                    if (t1 == null) {
                        String newSearchItemId = CatalogUpgrades.getTypeUpgradedIfNecessary(managementContext, searchItemId);
                        if (!newSearchItemId.equals(searchItemId)) {
                            logRebindingDebug("Upgrading search path entry of " + bType.getSimpleName().toLowerCase() + " " + contextSuchAsId + " from " + searchItemId + " to type " + newSearchItemId);
                            searchItemId = newSearchItemId;
                            t1 = managementContext.getTypeRegistry().get(newSearchItemId);
                        }
                    }
                    if (t1 != null) fixedSearchItemId = t1.getId();
                    if (fixedSearchItemId == null) {
                        CatalogItem<?, ?> ci = findCatalogItemInReboundCatalog(bType, searchItemId, contextSuchAsId);
                        if (ci != null) {
                            fixedSearchItemId = ci.getCatalogItemId();
                            logRebindingWarn("Needed rebind catalog to resolve search path entry " + searchItemId + " (now " + fixedSearchItemId + ") for " + bType.getSimpleName().toLowerCase() + " " + contextSuchAsId +
                                    ", persistence should remove this in future but future versions will not support this and definitions should be fixed");
                        } else {
                            logRebindingWarn("Could not find search path entry " + searchItemId + " for " + bType.getSimpleName().toLowerCase() + " " + contextSuchAsId + ", ignoring");
                        }
                    }
                    if (fixedSearchItemId != null) {
                        reboundSearchPath.add(fixedSearchItemId);
                    } else {
                        warnings.add("unable to resolve search path entry " + searchItemId);
                    }
                }
            }

            if (catalogItemId != null) {
                String transformedCatalogItemId = null;

                Maybe<RegisteredType> contextRegisteredType = managementContext.getTypeRegistry().getMaybe(catalogItemId,
                        // this is context RT, not item we are loading, so bType does not apply here
                        // if we were instantiating from an RT instead of a JT (ideal) then we would use bType to filter
                        null);
                if (contextRegisteredType.isAbsent()) {
                    transformedCatalogItemId = CatalogUpgrades.getTypeUpgradedIfNecessary(managementContext, catalogItemId);
                    if (!transformedCatalogItemId.equals(catalogItemId)) {
                        // catalog item id is sometimes the type of the item, but sometimes just the first part of the search path
                        logRebindingInfo("Upgrading " + bType.getSimpleName().toLowerCase() + " " + contextSuchAsId +
                                " stored catalog item context on rebind" +
                                " from " + catalogItemId + " to " + transformedCatalogItemId);

                        // again ignore bType
                        contextRegisteredType = managementContext.getTypeRegistry().getMaybe(transformedCatalogItemId, null);

                    } else {
                        transformedCatalogItemId = null;
                    }
                }

                if (contextRegisteredType.isPresent()) {
                    transformedCatalogItemId = contextRegisteredType.get().getId();
                } else {
                    CatalogItem<?, ?> catalogItem = findCatalogItemInReboundCatalog(bType, catalogItemId, contextSuchAsId);
                    if (catalogItem != null) {
                        transformedCatalogItemId = catalogItem.getCatalogItemId();
                    }
                }
                if (transformedCatalogItemId != null) {
                    try {
                        BrooklynClassLoadingContextSequential loader =
                                new BrooklynClassLoadingContextSequential(managementContext);
                        loader.add(newClassLoadingContextForCatalogItems(managementContext, transformedCatalogItemId,
                                reboundSearchPath));
                        return new LoadedClass<T>(loader.loadClass(jType, bType), transformedCatalogItemId, reboundSearchPath);
                    } catch (Exception e) {
                        Exceptions.propagateIfFatal(e);
                        warnings.add("unable to load class " + jType + " for resovled context type " + transformedCatalogItemId);
                    }
                } else {
                    // TODO fail, rather than fallback to java?
                    warnings.add("unable to resolve context type " + catalogItemId);
                }
            } else {
                // can happen for enrichers etc added by java, and for BasicApplication when things are deployed;
                // no need to warn
            }

            try {
                Class<T> jTypeC = (Class<T>) loadClass(jType);
                if (!warnings.isEmpty()) {
                    LOG.warn("Loaded java type " + jType + " for " + bType.getSimpleName().toLowerCase() + " " + contextSuchAsId + " but had errors: " + Strings.join(warnings, ";"));
                }
                return new LoadedClass<T>(jTypeC, catalogItemId, reboundSearchPath);
            } catch (Exception e) {
                Exceptions.propagateIfFatal(e);
            }

            if (catalogItemId != null) {
                String msg = "Class " + jType + " not found for " + bType.getSimpleName().toLowerCase() + " " + contextSuchAsId + " (" + catalogItemId + "): " + Strings.join(warnings, ";");
                LOG.warn(msg + " (rethrowing)");
                throw new IllegalStateException(msg);

            } else if (BrooklynFeatureEnablement.isEnabled(FEATURE_BACKWARDS_COMPATIBILITY_INFER_CATALOG_ITEM_ON_REBIND)) {
                //Try loading from whichever catalog bundle succeeds (legacy CI items only; also disabling this, as no longer needed 2017-09)
                BrooklynCatalog catalog = managementContext.getCatalog();
                for (CatalogItem<?, ?> item : catalog.getCatalogItemsLegacy()) {
                    BrooklynClassLoadingContext catalogLoader = CatalogUtils.newClassLoadingContext(managementContext, item);
                    Maybe<Class<?>> catalogClass = catalogLoader.tryLoadClass(jType);
                    if (catalogClass.isPresent()) {
                        LOG.warn("Falling back to java type " + jType + " for " + bType.getSimpleName().toLowerCase() + " " + contextSuchAsId + " using catalog search paths, found on " + item +
                                (warnings.isEmpty() ? "" : ", after errors: " + Strings.join(warnings, ";")));
                        return new LoadedClass<T>((Class<? extends T>) catalogClass.get(), catalogItemId, reboundSearchPath);
                    }
                }
                String msg = "Class " + jType + " not found for " + bType.getSimpleName().toLowerCase() + " " + contextSuchAsId + ", even after legacy global classpath search" +
                        (warnings.isEmpty() ? "" : ": " + Strings.join(warnings, ";"));
                LOG.warn(msg + " (rethrowing)");
                throw new IllegalStateException(msg);

            } else {
                String msg = "Class " + jType + " not found for " + bType.getSimpleName().toLowerCase() + " " + contextSuchAsId +
                        (warnings.isEmpty() ? "" : ": " + Strings.join(warnings, ";"));
                LOG.warn(msg + " (rethrowing)");
                throw new IllegalStateException(msg);
            }
        }

        private <T extends BrooklynObject> CatalogItem<?, ?> findCatalogItemInReboundCatalog(Class<T> bType,
                                                                                             String catalogItemId, String contextSuchAsId) {
            CatalogItem<?, ?> catalogItem = rebindContext.lookup().lookupCatalogItem(catalogItemId);
            if (catalogItem == null) {
                if (BrooklynFeatureEnablement.isEnabled(FEATURE_AUTO_FIX_CATALOG_REF_ON_REBIND)) {
                    // See https://issues.apache.org/jira/browse/BROOKLYN-149
                    // This is a dangling reference to the catalog item (which will have been logged by lookupCatalogItem).
                    // Try loading as any version.
                    if (RegisteredTypeNaming.isUsableTypeColonVersion(catalogItemId) ||
                            // included through 0.12 so legacy type names are accepted (with warning)
                            CatalogUtils.looksLikeVersionedId(catalogItemId)) {
                        String symbolicName = CatalogUtils.getSymbolicNameFromVersionedId(catalogItemId);
                        catalogItem = rebindContext.lookup().lookupCatalogItem(symbolicName);

                        if (catalogItem != null) {
                            LOG.warn("Unable to load catalog item " + catalogItemId + " for " + contextSuchAsId
                                    + " (" + bType.getSimpleName() + "); will auto-upgrade to "
                                    + catalogItem.getCatalogItemId() + ":" + catalogItem.getVersion());
                        }
                    }
                }
            }
            return catalogItem;
        }

        protected Class<?> loadClass(String jType) throws ClassNotFoundException {
            try {
                return reflections.loadClass(jType);
            } catch (Exception e) {
                Exceptions.propagateIfFatal(e);
            }
            return new ClassLoaderUtils(reflections.getClassLoader(), managementContext).loadClass(jType);
        }

        @SuppressWarnings("unchecked")
        public <T> Class<? extends T> loadClass(String classname, Class<T> superType) {
            try {
                return (Class<? extends T>) loadClass(classname);
            } catch (ClassNotFoundException e) {
                throw Exceptions.propagate(e);
            }
        }

        /**
         * Constructs a new location, passing to its constructor the location id and all of memento.getFlags().
         */
        protected Location newLocation(String locationId, String locationType) {
            Class<? extends Location> locationClazz = loadClass(locationType, Location.class);

            if (InternalFactory.isNewStyle(locationClazz)) {
                // Not using loationManager.createLocation(LocationSpec) because don't want init() to be called
                // TODO Need to rationalise this to move code into methods of InternalLocationFactory.
                //      But note that we'll change all locations to be entities at some point!
                // See same code approach used in #newEntity(EntityMemento, Reflections)
                InternalLocationFactory locationFactory = managementContext.getLocationFactory();
                Location location = locationFactory.constructLocation(locationClazz);
                FlagUtils.setFieldsFromFlags(ImmutableMap.of("id", locationId), location);
                managementContext.prePreManage(location);
                ((AbstractLocation) location).setManagementContext(managementContext);

                return location;
            } else {
                LOG.warn("Deprecated rebind of location without no-arg constructor; " +
                        "this may not be supported in future versions: id=" + locationId + "; type=" + locationType);

                // There are several possibilities for the constructor; find one that works.
                // Prefer passing in the flags because required for Application to set the management context
                // TODO Feels very hacky!
                Map<String, ?> flags = MutableMap.of("id", locationId, "deferConstructionChecks", true);

                return invokeConstructor(reflections, locationClazz, new Object[]{flags});
            }
            // note 'used' config keys get marked in BasicLocationRebindSupport
        }

        /**
         * Constructs a new policy, passing to its constructor the policy id and all of memento.getConfig().
         */
        protected Policy newPolicy(PolicyMemento memento) {
            String id = memento.getId();
            LoadedClass<? extends Policy> loaded = load(Policy.class, memento);
            Class<? extends Policy> policyClazz = loaded.clazz;

            Policy policy;
            if (InternalFactory.isNewStyle(policyClazz)) {
                InternalPolicyFactory policyFactory = managementContext.getPolicyFactory();
                policy = policyFactory.constructPolicy(policyClazz);
                FlagUtils.setFieldsFromFlags(ImmutableMap.of("id", id), policy);
                ((AbstractPolicy) policy).setManagementContext(managementContext);
                ((AbstractPolicy) policy).setHighlights(memento.getHighlights());

            } else {
                LOG.warn("Deprecated rebind of policy without no-arg constructor; " +
                        "this may not be supported in future versions: id=" + id + "; type=" + policyClazz);

                // There are several possibilities for the constructor; find one that works.
                // Prefer passing in the flags because required for Application to set the management context
                // TODO Feels very hacky!
                Map<String, Object> flags = MutableMap.<String, Object>of(
                        "id", id,
                        "deferConstructionChecks", true,
                        "noConstructionInit", true);
                flags.putAll(memento.getConfig());

                policy = invokeConstructor(null, policyClazz, new Object[]{flags});
            }

            setCatalogItemIds(policy, loaded.catalogItemId, loaded.searchPath);
            return policy;
        }

        /**
         * Constructs a new enricher, passing to its constructor the enricher id and all of memento.getConfig().
         */
        protected Enricher newEnricher(EnricherMemento memento) {
            String id = memento.getId();
            LoadedClass<? extends Enricher> loaded = load(Enricher.class, memento);
            Class<? extends Enricher> enricherClazz = loaded.clazz;

            Enricher enricher;
            if (InternalFactory.isNewStyle(enricherClazz)) {
                InternalPolicyFactory policyFactory = managementContext.getPolicyFactory();
                enricher = policyFactory.constructEnricher(enricherClazz);
                FlagUtils.setFieldsFromFlags(ImmutableMap.of("id", id), enricher);
                ((AbstractEnricher) enricher).setManagementContext(managementContext);

            } else {
                LOG.warn("Deprecated rebind of enricher without no-arg constructor; " +
                        "this may not be supported in future versions: id=" + id + "; type=" + enricherClazz);

                // There are several possibilities for the constructor; find one that works.
                // Prefer passing in the flags because required for Application to set the management context
                // TODO Feels very hacky!
                Map<String, Object> flags = MutableMap.<String, Object>of(
                        "id", id,
                        "deferConstructionChecks", true,
                        "noConstructionInit", true);
                flags.putAll(memento.getConfig());

                enricher = invokeConstructor(reflections, enricherClazz, new Object[]{flags});
            }

            setCatalogItemIds(enricher, loaded.catalogItemId, loaded.searchPath);
            return enricher;
        }

        /**
         * Constructs a new enricher, passing to its constructor the enricher id and all of memento.getConfig().
         */
        protected Feed newFeed(FeedMemento memento) {
            String id = memento.getId();
            LoadedClass<? extends Feed> loaded = load(Feed.class, memento);
            Class<? extends Feed> feedClazz = loaded.clazz;

            Feed feed;
            if (InternalFactory.isNewStyle(feedClazz)) {
                InternalPolicyFactory policyFactory = managementContext.getPolicyFactory();
                feed = policyFactory.constructFeed(feedClazz);
                FlagUtils.setFieldsFromFlags(ImmutableMap.of("id", id), feed);
                ((AbstractFeed) feed).setManagementContext(managementContext);

            } else {
                throw new IllegalStateException("rebind of feed without no-arg constructor unsupported: id=" + id +
                        "; type=" + feedClazz);
            }

            setCatalogItemIds(feed, loaded.catalogItemId, loaded.searchPath);
            return feed;
        }

        @SuppressWarnings({"rawtypes"})
        protected CatalogItem<?, ?> newCatalogItem(CatalogItemMemento memento) {
            String id = memento.getId();
            // catalog item subtypes are internal to brooklyn, not in osgi
            String itemType = checkNotNull(memento.getType(), "catalog item type of %s must not be null in memento", id);
            Class<? extends CatalogItem> clazz = loadClass(itemType, CatalogItem.class);
            return invokeConstructor(reflections, clazz, new Object[]{});
        }

        protected <T> T invokeConstructor(Reflections reflections, Class<T> clazz, Object[]... possibleArgs) {
            for (Object[] args : possibleArgs) {
                try {
                    Maybe<T> v = Reflections.invokeConstructorFromArgs(clazz, args, true);
                    if (v.isPresent()) {
                        return v.get();
                    }
                } catch (Exception e) {
                    throw Exceptions.propagate(e);
                }
            }
            StringBuilder args = new StringBuilder();
            if (possibleArgs.length < 1) args.append("no possible argument sets supplied; error");
            else if (possibleArgs.length < 2) args.append("args are " + Arrays.asList(possibleArgs[0]));
            else {
                args.append("args are " + Arrays.asList(possibleArgs[0]));
                for (int i = 1; i < possibleArgs.length; i++) {
                    args.append(" or ");
                    args.append(Arrays.asList(possibleArgs[i]));
                }
            }
            throw new IllegalStateException("Cannot instantiate instance of type " + clazz +
                    "; expected constructor signature not found (" + args + ")");
        }

        protected ManagedBundle newManagedBundle(ManagedBundleMemento bundleMemento) {
            return RebindIteration.newManagedBundle(bundleMemento);
        }
    }

    protected BrooklynMementoPersister getPersister() {
        return rebindManager.getPersister();
    }

    protected <T extends TreeNode> Map<String, T> sortParentFirst(Map<String, T> nodes) {
        return RebindManagerImpl.sortParentFirst(nodes);
    }

    /**
     * logs at debug, except during subsequent read-only rebinds, in which it logs trace
     */
    protected void logRebindingDebug(String message, Object... args) {
        if (shouldLogRebinding()) {
            LOG.debug(message, args);
        } else {
            LOG.trace(message, args);
        }
    }

    /**
     * logs at info, except during subsequent read-only rebinds, in which it logs trace
     */
    protected void logRebindingInfo(String message, Object... args) {
        if (shouldLogRebinding()) {
            LOG.info(message, args);
        } else {
            LOG.trace(message, args);
        }
    }

    /**
     * logs at warn, except during subsequent read-only rebinds, in which it logs trace
     */
    protected void logRebindingWarn(String message, Object... args) {
        if (shouldLogRebinding()) {
            LOG.warn(message, args);
        } else {
            LOG.trace(message, args);
        }
    }

    protected boolean shouldLogRebinding() {
        return (readOnlyRebindCount.get() < 5) || (readOnlyRebindCount.get() % 1000 == 0);
    }

    @Beta
    public static ManagedBundle newManagedBundle(ManagedBundleMemento memento) {
        ManagedBundle result = new BasicManagedBundle(memento.getSymbolicName(), memento.getVersion(), memento.getUrl(),
                memento.getFormat(), null, memento.getChecksum(), memento.getDeleteable());
        FlagUtils.setFieldsFromFlags(ImmutableMap.of("id", memento.getId()), result);
        return result;
    }

}
