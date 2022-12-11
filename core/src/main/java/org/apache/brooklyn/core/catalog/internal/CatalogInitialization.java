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
package org.apache.brooklyn.core.catalog.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import java.util.function.Supplier;
import org.apache.brooklyn.api.catalog.BrooklynCatalog;
import org.apache.brooklyn.api.catalog.CatalogItem;
import org.apache.brooklyn.api.catalog.CatalogItem.CatalogItemType;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.internal.AbstractBrooklynObjectSpec;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.mgmt.ha.ManagementNodeState;
import org.apache.brooklyn.api.mgmt.rebind.RebindExceptionHandler;
import org.apache.brooklyn.api.objs.BrooklynObjectType;
import org.apache.brooklyn.api.typereg.BrooklynTypeRegistry.RegisteredTypeKind;
import org.apache.brooklyn.api.typereg.ManagedBundle;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.core.mgmt.ManagementContextInjectable;
import org.apache.brooklyn.core.mgmt.ha.OsgiBundleInstallationResult;
import org.apache.brooklyn.core.mgmt.ha.OsgiManager;
import org.apache.brooklyn.core.mgmt.internal.ManagementContextInternal;
import org.apache.brooklyn.core.mgmt.rebind.RebindManagerImpl;
import org.apache.brooklyn.core.objs.BrooklynTypes;
import org.apache.brooklyn.core.server.BrooklynServerConfig;
import org.apache.brooklyn.core.typereg.BundleUpgradeParser;
import org.apache.brooklyn.core.typereg.BundleUpgradeParser.CatalogUpgrades;
import org.apache.brooklyn.core.typereg.RegisteredTypePredicates;
import org.apache.brooklyn.core.typereg.RegisteredTypes;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.collections.MutableSet;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.exceptions.FatalRuntimeException;
import org.apache.brooklyn.util.exceptions.PropagatedRuntimeException;
import org.apache.brooklyn.util.exceptions.RuntimeInterruptedException;
import org.apache.brooklyn.util.exceptions.UserFacingException;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.javalang.JavaClassNames;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.osgi.VersionedName;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Duration;
import org.apache.commons.lang3.tuple.Pair;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.Beta;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

@Beta
public class CatalogInitialization implements ManagementContextInjectable {

    /*

    A1) if not persisting, go to B1
    A2) if there is a persisted catalog, read it and go to C1
    A3) go to B1

    B1) look for --catalog-initial, if so read it, then go to C1
    B2) look for BrooklynServerConfig.BROOKLYN_CATALOG_URL, if so, read it, supporting YAML, then go to C1
    B3) look for ~/.brooklyn/catalog.bom, if exists, read it then go to C1
    B4) read all classpath://brooklyn/default.catalog.bom items, if they exist (and for now they will)
    B5) go to C1

    C1) if persisting, read the rest of the persisted items (entities etc)

     */

    private static final Logger log = LoggerFactory.getLogger(CatalogInitialization.class);

    private String initialUri;

    /** has run the initial catalog initialization */
    private boolean hasRunInitialCatalogInitialization = false; 

    /** has run an official initialization, but it is not a permanent one (e.g. during a hot standby mode, or a run failed) */
    private boolean hasRunPersistenceInitialization = false; 

    /** has run an official initialization which is permanent (node is master, and the new catalog is now set) */
    private boolean hasRunFinalInitialization = false;

    private ManagementContextInternal managementContext;
    private CatalogUpgradeScanner catalogUpgradeScanner;
    private boolean isStartingUp = false;
    private boolean failOnStartupErrors = false;
    
    /** is running a populate method; used to prevent recursive loops */
    private boolean isPopulatingInitial = false;

    private final Object populatingCatalogMutex = new Object();
    
    public CatalogInitialization() {
        this(null);
    }

    public CatalogInitialization(String initialUri) {
        this.initialUri = initialUri;
    }
    
    @Override
    public void setManagementContext(ManagementContext managementContext) {
        checkNotNull(managementContext, "management context");
        if (this.managementContext!=null && managementContext!=this.managementContext)
            throw new IllegalStateException("Cannot switch management context, from "+this.managementContext+" to "+managementContext);
        this.managementContext = (ManagementContextInternal) managementContext;
        catalogUpgradeScanner = new CatalogUpgradeScanner(this.managementContext,
                BundleUpgradeParser::parseBundleManifestForCatalogUpgrades,
                RegisteredTypePredicates::containingBundle,
                RegisteredTypePredicates::containingBundle);
    }
    
    /** Called by the framework to set true while starting up, and false afterwards,
     * in order to assist in appropriate logging and error handling. */
    public void setStartingUp(boolean isStartingUp) {
        this.isStartingUp = isStartingUp;
    }

    public void setFailOnStartupErrors(boolean startupFailOnCatalogErrors) {
        this.failOnStartupErrors = startupFailOnCatalogErrors;
    }

    public ManagementContextInternal getManagementContext() {
        return checkNotNull(managementContext, "management context has not been injected into "+this);
    }

    /** Returns true if the canonical initialization has completed, 
     * that is, an initialization which is done when a node is rebinded as master
     * (or an initialization done by the startup routines when not running persistence);
     * see also {@link #hasRunAnyInitialization()}. */
    private boolean hasRunFinalInitialization() {
        return hasRunFinalInitialization;
    }
    
    private boolean hasRunInitialCatalogInitialization() {
        return hasRunFinalInitialization || hasRunInitialCatalogInitialization;
    }
    
    /**
     * Returns true if we have added catalog bundles/items from persisted state.
     */
    private boolean hasRunPersistenceInitialization() {
        return hasRunFinalInitialization || hasRunPersistenceInitialization;
    }
    
    /**
     * Returns true if the initializer has run at all.
     */
    @VisibleForTesting
    @Beta
    public boolean hasRunAnyInitialization() {
        return hasRunFinalInitialization || hasRunInitialCatalogInitialization || hasRunPersistenceInitialization;
    }

    /**
     * Populates the initial catalog (i.e. from the initial .bom file).
     * 
     * Expected to be called exactly once at startup.
     */
    public void populateInitialCatalogOnly() {
        if (log.isDebugEnabled()) {
            log.debug("Populating only the initial catalog; from "+JavaClassNames.callerNiceClassAndMethod(1));
        }
        synchronized (populatingCatalogMutex) {
            if (hasRunInitialCatalogInitialization()) {
                throw new IllegalStateException("Catalog initialization called to populate only initial, even though it has already run it");
            }

            populateInitialCatalogImpl(true);
            onFinalCatalog();
        }
    }

    /**
     * Clears all record of the brooklyn-managed-bundles (so use with care!).
     * 
     * Used when promoting from HOT_STANDBY to MASTER. Previous actions performed as HOT_STANDBY
     * will have been done in read-only mode. When we rebind in anger as master, we want to do this
     * without a previous cache of managed bundles.
     */
    public void clearBrooklynManagedBundles() {
        Maybe<OsgiManager> osgiManager = managementContext.getOsgiManager();
        if (osgiManager.isPresent()) {
            osgiManager.get().clearManagedBundles();
        }
    }
    
    /**
     * Adds the given persisted catalog items.
     * 
     * Can be called multiple times, e.g.:
     * <ul>
     *   <li>if "hot-standby" then will be called repeatedly, as we rebind to the persisted state
     *   <li> if being promoted to master then we will be called (and may already have been called for "hot-standby").
     * </ul>
     */
    public void populateInitialAndPersistedCatalog(ManagementNodeState mode, PersistedCatalogState persistedState, RebindExceptionHandler exceptionHandler, RebindLogger rebindLogger) {
        if (log.isDebugEnabled()) {
            String message = "Add persisted catalog for "+mode+", persistedBundles="+persistedState.getBundles().size()+", legacyItems="+persistedState.getLegacyCatalogItems().size()+"; from "+JavaClassNames.callerNiceClassAndMethod(1);
            if (!ManagementNodeState.isHotProxy(mode)) {
                log.debug(message);
            } else {
                // in hot modes, make this message trace so we don't get too much output then
                log.trace(message);
            }
        }
        synchronized (populatingCatalogMutex) {
            if (hasRunFinalInitialization()) {
                log.warn("Catalog initialization called to add persisted catalog, even though it has already run the final 'master' initialization; mode="+mode, new Throwable("Source of duplicate catalog initialization"));
                hasRunFinalInitialization = false;
            }
            if (hasRunPersistenceInitialization()) {
                // Multiple calls; will need to reset (only way to clear out the previous persisted state's catalog)
                if (log.isDebugEnabled()) {
                    String message = "Catalog initialization repeated call to add persisted catalog, resetting catalog (including initial) to start from clean slate; mode="+mode;
                    log.debug(message);
                }
            } else if (hasRunInitialCatalogInitialization()) {
                throw new IllegalStateException("Catalog initialization already run for initial catalog by mechanism other than populating persisted state; mode="+mode);      
            }

            installPersistedBundles(persistedState, () -> {
                // now we install and start the bundles from the catalog;
                // 2021-12-03 now this only will look for classes in active bundles, so it won't resolve persisted bundles
                // and we can safely filter them out later
                populateInitialCatalogImpl(true);
            }, exceptionHandler, rebindLogger);

            if (mode == ManagementNodeState.MASTER) {
                // TODO ideally this would remain false until it has *persisted* the changed catalog;
                // if there is a subsequent startup failure the forced additions will not be persisted,
                // but nor will they be loaded on a subsequent run.
                // callers will have to restart a brooklyn, or reach into this class to change this field,
                // or (recommended) manually adjust the catalog.
                // TODO also, if a node comes up in standby, the addition might not take effector for a while
                //
                // however since these options are mainly for use on the very first brooklyn run, it's not such a big deal; 
                // once up and running the typical way to add items is via the REST API
                onFinalCatalog();
            }
        }
    }

    /** shared routine between above "normal" initialization, and special addition via ServerResource.import */
    @Beta
    public void installPersistedBundles(PersistedCatalogState persistedState, Runnable beforeDeferredStartAndSetRunPersistence, RebindExceptionHandler exceptionHandler, RebindLogger rebindLogger) {
        // Always install the bundles from persisted state; installed (but not started) prior to catalog,
        // so that OSGi unique IDs might be picked up when initial catalog is populated
        Map<InstallableManagedBundle, OsgiBundleInstallationResult> persistenceInstalls = installPersistedBundlesDontStart(persistedState.getBundles(), exceptionHandler, rebindLogger);

        if (beforeDeferredStartAndSetRunPersistence!=null) beforeDeferredStartAndSetRunPersistence.run();

        final Maybe<OsgiManager> maybesOsgiManager = managementContext.getOsgiManager();
        if (maybesOsgiManager.isAbsent()) {
            // Can't find any bundles to tell if there are upgrades. Could be running tests; do no filtering.
            CatalogUpgrades.storeInManagementContext(CatalogUpgrades.EMPTY, managementContext);
        } else {
            final OsgiManager osgiManager = maybesOsgiManager.get();
            final BundleContext bundleContext = osgiManager.getFramework().getBundleContext();
            final CatalogUpgrades catalogUpgrades =
                    catalogUpgradeScanner.scan(osgiManager, bundleContext, rebindLogger);
            CatalogUpgrades.storeInManagementContext(catalogUpgrades, managementContext);
        }

        PersistedCatalogState filteredPersistedState = filterBundlesAndCatalogInPersistedState(persistedState, rebindLogger);

        // 2021-09-14 previously we effectively installed here, after populating; but now we do it earlier and then uninstall if needed, to preserve IDs
//            Map<InstallableManagedBundle, OsgiBundleInstallationResult> persistenceInstalls = installPersistedBundlesDontStart(filteredPersistedState.getBundles(), exceptionHandler, rebindLogger);

        try {
            startPersistedBundles(filteredPersistedState, persistenceInstalls, exceptionHandler, rebindLogger);
            BrooklynCatalog catalog = managementContext.getCatalog();
            catalog.addCatalogLegacyItemsOnRebind(filteredPersistedState.getLegacyCatalogItems());
        } finally {
            if (beforeDeferredStartAndSetRunPersistence!=null) hasRunPersistenceInitialization = true;
        }
    }

    /**
     * Populates the initial catalog, but not via an official code-path.
     * 
     * Expected to be called only during tests, where the test has not gone through the same 
     * management-context lifecycle as is done in BasicLauncher.
     * 
     * Subsequent calls will fail to things like {@link #populateInitialCatalogOnly()} or
     * {@link #populateInitialAndPersistedCatalog(ManagementNodeState, PersistedCatalogState, RebindExceptionHandler, RebindLogger)}.
     */
    @VisibleForTesting
    @Beta
    public void unofficialPopulateInitialCatalog() {
        if (log.isDebugEnabled()) {
            log.debug("Unofficially populate initial catalog; should be used only by tests! Called from "+JavaClassNames.callerNiceClassAndMethod(1));
        }
        synchronized (populatingCatalogMutex) {
            if (hasRunInitialCatalogInitialization()) {
                return;
            }

            populateInitialCatalogImpl(true);
        }
    }

    public void handleException(Throwable throwable, Object details) {
        if (throwable instanceof InterruptedException)
            throw new RuntimeInterruptedException((InterruptedException) throwable);
        if (throwable instanceof RuntimeInterruptedException)
            throw (RuntimeInterruptedException) throwable;

        if (details instanceof CatalogItem) {
            if (((CatalogItem<?,?>)details).getCatalogItemId() != null) {
                details = ((CatalogItem<?,?>)details).getCatalogItemId();
            }
        }
        PropagatedRuntimeException wrap = new PropagatedRuntimeException("Error loading catalog item "+details, throwable);
        log.warn(Exceptions.collapseText(wrap));
        log.debug("Trace for: "+wrap, wrap);

        getManagementContext().errors().add(wrap);
        
        if (isStartingUp && failOnStartupErrors) {
            throw new FatalRuntimeException("Unable to load catalog item "+details, wrap);
        }
    }
    
    private void confirmCatalog() {
        // Force load of catalog (so web console is up to date)
        Stopwatch time = Stopwatch.createStarted();
        Iterable<RegisteredType> all = getManagementContext().getTypeRegistry().getAll();
        int errors = 0;
        for (RegisteredType rt: all) {
            if (RegisteredTypes.isTemplate(rt)) {
                // skip validation of templates, they might contain instructions,
                // and additionally they might contain multiple items in which case
                // the validation below won't work anyway (you need to go via a deployment plan)
            } else {
                if (rt.getKind()==RegisteredTypeKind.UNRESOLVED) {
                    errors++;
                    handleException(new UserFacingException("Unresolved type in catalog"), rt);
                }
            }
        }

        // and force resolution of legacy items
        BrooklynCatalog catalog = getManagementContext().getCatalog();
        Iterable<CatalogItem<Object, Object>> items = catalog.getCatalogItemsLegacy();
        for (CatalogItem<Object, Object> item: items) {
            try {
                if (item.getCatalogItemType()==CatalogItemType.TEMPLATE) {
                    // skip validation of templates, they might contain instructions,
                    // and additionally they might contain multiple items in which case
                    // the validation below won't work anyway (you need to go via a deployment plan)
                } else {
                    AbstractBrooklynObjectSpec<?, ?> spec = catalog.peekSpec(item);
                    if (spec instanceof EntitySpec) {
                        BrooklynTypes.getDefinedEntityType(((EntitySpec<?>)spec).getType());
                    }
                    log.debug("Catalog loaded spec "+spec+" for item "+item);
                }
            } catch (Throwable throwable) {
                handleException(throwable, item);
            }
        }
        
        log.debug("Catalog (size "+Iterables.size(all)+", of which "+Iterables.size(items)+" legacy) confirmed in "+Duration.of(time)+(errors>0 ? ", errors found ("+errors+")" : ""));
        // nothing else added here
    }

    private void populateInitialCatalogImpl(boolean reset) {
        assert Thread.holdsLock(populatingCatalogMutex);
        
        if (isPopulatingInitial) {
            // Avoid recursively loops, where getCatalog() calls unofficialPopulateInitialCatalog(), but populateInitialCatalogImpl() also calls getCatalog()
            return;
        }
        
        isPopulatingInitial = true;
        try {
            BasicBrooklynCatalog catalog = (BasicBrooklynCatalog) managementContext.getCatalog();
            if (!catalog.getCatalog().isLoaded()) {
                catalog.load();
            } else {
                if (reset) {
                    catalog.reset(ImmutableList.<CatalogItem<?,?>>of());
                }
            }

            populateViaInitialBomImpl(catalog);

        } catch (Throwable e) {
            if (!Thread.currentThread().isInterrupted() && !isRebindReadOnlyShuttingDown(getManagementContext())) {
                // normal on interruption, esp during tests; only worth remarking here otherwise (we rethrow in any case)
                log.warn("Error populating catalog (rethrowing): "+e, e);
            }
            throw Exceptions.propagate(e);
        } finally {
            isPopulatingInitial = false;
            hasRunInitialCatalogInitialization = true;
        }
    }

    private void onFinalCatalog() {
        assert Thread.holdsLock(populatingCatalogMutex);
        
        hasRunFinalInitialization = true;
        confirmCatalog();
    }

    public void clearForSubsequentCatalogInit() {
        hasRunInitialCatalogInitialization = false;
        hasRunPersistenceInitialization = false;
        hasRunFinalInitialization = false;
    }

    private void populateViaInitialBomImpl(BasicBrooklynCatalog catalog) {
//        B1) look for --catalog-initial, if so read it, then go to C1
//        B2) look for BrooklynServerConfig.BROOKLYN_CATALOG_URL, if so, read it, supporting YAML, then go to C1
//        B3) look for ~/.brooklyn/catalog.bom, if exists, read it then go to C1
//        B4) read all classpath://brooklyn/default.catalog.bom items, if they exist (and for now they will)
//        B5) go to C1

        if (initialUri!=null) {
            populateInitialFromUri(catalog, initialUri);
            return;
        }
        
        String catalogUrl = managementContext.getConfig().getConfig(BrooklynServerConfig.BROOKLYN_CATALOG_URL);
        if (Strings.isNonBlank(catalogUrl)) {
            populateInitialFromUri(catalog, catalogUrl);
            return;
        }
        
        catalogUrl = Os.mergePaths(BrooklynServerConfig.getMgmtBaseDir( managementContext.getConfig() ), "catalog.bom");
        if (new File(catalogUrl).exists()) {
            populateInitialFromUri(catalog, new File(catalogUrl).toURI().toString());
            return;
        }
        
        // otherwise look for for classpath:/brooklyn/default.catalog.bom --
        // there is one on the classpath which says to scan, and provides a few templates;
        // if one is supplied by user in the conf/ dir that will override the item from the classpath
        // (TBD - we might want to scan for all such bom's?)
        
        catalogUrl = "classpath:/brooklyn/default.catalog.bom";
        if (new ResourceUtils(this).doesUrlExist(catalogUrl)) {
            populateInitialFromUri(catalog, catalogUrl);
            return;
        }
        
        log.info("No catalog found on classpath or specified; catalog will not be initialized.");
        return;
    }
    
    private void populateInitialFromUri(BasicBrooklynCatalog catalog, String catalogUrl) {
        log.debug("Loading initial catalog from {}", catalogUrl);

        try {
            String contents = new ResourceUtils(this).getResourceAsString(catalogUrl);

            catalog.reset(MutableList.<CatalogItem<?,?>>of());
            Object result = catalog.addItems(contents);
            
            log.debug("Loaded initial catalog from {}: {}", catalogUrl, result);
            
        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            if (isRebindReadOnlyShuttingDown(getManagementContext())) {
                throw Exceptions.propagate(e);
            }

            log.warn("Error importing catalog from " + catalogUrl + ": " + e, e);
        }
    }

    @Beta
    public static boolean isRebindReadOnlyShuttingDown(ManagementContext mgmt) {
        if (mgmt!=null && mgmt.getRebindManager() instanceof RebindManagerImpl) {
            if (((RebindManagerImpl)mgmt.getRebindManager()).isReadOnlyStopping()) {
                return true;
            }
        }
        return false;
    }

    private Map<InstallableManagedBundle, OsgiBundleInstallationResult> installPersistedBundlesDontStart(Map<VersionedName, InstallableManagedBundle> bundles, RebindExceptionHandler exceptionHandler, RebindLogger rebindLogger) {
        Map<InstallableManagedBundle, OsgiBundleInstallationResult> installs = MutableMap.of();

        // Install the bundles
        Map<VersionedName, InstallableManagedBundle> remaining = MutableMap.copyOf(bundles);
        Set<Pair<Entry<VersionedName, InstallableManagedBundle>, Exception>> errors = MutableSet.of();
        while (!remaining.isEmpty()) {
            int installed = 0;
            for (Entry<VersionedName, InstallableManagedBundle> entry : MutableSet.copyOf(remaining.entrySet())) {
                rebindLogger.debug("RebindManager installing bundle {}", entry.getKey());
                try {
                    installs.put(entry.getValue(), installBundle(entry.getValue().getManagedBundle(), entry.getValue().getInputStreamSource()));
                    remaining.remove(entry.getKey());
                    installed++;
                } catch (Exception e) {
                    rebindLogger.debug("Unable to install bundle " + entry.getKey() + ", but may re-try in case it has a dependency on another bundle (" + e + ")");
                    errors.add(Pair.of(entry, e));
                }
            }
            if (installed == 0) {
                // keep trying until either nothing is installed or nothing is left to install
                break;
            }
        }
        rebindLogger.debug("RebindManager installed bundles {}, {} errors", installs.keySet(), errors.size());
        errors.forEach(err -> exceptionHandler.onCreateFailed(BrooklynObjectType.MANAGED_BUNDLE,
                err.getLeft().getKey().toString(), err.getLeft().getValue().getManagedBundle().getSymbolicName(), err.getRight()));

        return installs;
    }

    private void startPersistedBundles(PersistedCatalogState filteredPersistedState, Map<InstallableManagedBundle, OsgiBundleInstallationResult> installs, RebindExceptionHandler exceptionHandler, RebindLogger rebindLogger) {
        // Start the bundles (now that we've installed them all)

        // start order is:  OSGi and not catalog; then OSGi and catalog; then not catalog nor OSGi; then catalog and not OSGi
        // (we need OSGi and not catalog to start first; the others are less important)
        Set<OsgiBundleInstallationResult> bundlesInOrder = MutableSet.of();
        Set<OsgiBundleInstallationResult> bundlesToRemove = MutableSet.of();
        installs.values().stream().forEach(candidate -> {
            if (filteredPersistedState.getBundles().containsKey(candidate.getVersionedName())) {
                bundlesInOrder.add(candidate);
            } else {
                log.debug("Skipping start of persisted bundle "+candidate+" due to catalog upgrade metadata instructions");
                bundlesToRemove.add(candidate);
            }
        });
        bundlesToRemove.forEach(b -> {
            ManagedBundle mb = getManagementContext().getOsgiManager().get().getManagedBundle(b.getVersionedName());
            if (b.getBundle().getState() >= Bundle.INSTALLED && b.getBundle().getState() < Bundle.STARTING) {
                // we installed it, catalog did not start it, so let's uninstall it
                OsgiBundleInstallationResult result = getManagementContext().getOsgiManager().get().uninstallUploadedBundle(b.getMetadata());
                log.debug("Result of uninstalling "+b+" due to catalog upgrade metadata instructions: "+result);
            }
        });

        MutableSet.copyOf(bundlesInOrder).stream().filter(b -> b.getBundle() != null && b.getBundle().getResource("/catalog.bom") != null).forEach(b -> {
            bundlesInOrder.remove(b);
            bundlesInOrder.add(b); // then move catalog.bom items to the end
        });
        MutableSet.copyOf(bundlesInOrder).stream().filter(b -> b.getBundle() != null && b.getBundle().getResource("/META-INF/MANIFEST.MF") == null).forEach(b -> {
            bundlesInOrder.remove(b);
            bundlesInOrder.add(b); // move non-osgi items to the end
        });
        if (!bundlesInOrder.isEmpty()) {
            log.debug("Rebind bundle start order is: " + bundlesInOrder);
        }
        Set<RegisteredType> installedTypes = MutableSet.of();

        for (OsgiBundleInstallationResult br : bundlesInOrder) {
            try {
                startBundle(br);
                Iterables.addAll(installedTypes, managementContext.getTypeRegistry().getMatching(
                    RegisteredTypePredicates.containingBundle(br.getVersionedName())));
            } catch (Exception e) {
                exceptionHandler.onCreateFailed(BrooklynObjectType.MANAGED_BUNDLE, br.getMetadata().getId(), br.getMetadata().getSymbolicName(), e);
            }
        }
        
        // Validate that they all started successfully
        if (!installedTypes.isEmpty()) {
            validateAllTypes(installedTypes, exceptionHandler);
        }
        
        for (Map.Entry<InstallableManagedBundle, OsgiBundleInstallationResult> entry : installs.entrySet()) {
            ManagedBundle bundle = entry.getKey().getManagedBundle();
            OsgiBundleInstallationResult result = entry.getValue();
            if (result.getCode() == OsgiBundleInstallationResult.ResultCode.IGNORING_BUNDLE_AREADY_INSTALLED 
                    && !result.getMetadata().getId().equals(bundle.getId())) {
                // Bundle was already installed as a "Brooklyn managed bundle" (with different id), 
                // and will thus be persisted with that id.
                // For example, can happen if it is in the "initial catalog" and also in persisted state.
                // Delete this copy from the persisted state as it is a duplicate.
                managementContext.getRebindManager().getChangeListener().onUnmanaged(bundle);
            }
        }
    }

    private void validateAllTypes(Set<RegisteredType> installedTypes, RebindExceptionHandler exceptionHandler) {
        Stopwatch sw = Stopwatch.createStarted();
        log.debug("Getting catalog to validate all types");
        final BrooklynCatalog catalog = this.managementContext.getCatalog();
        log.debug("Got catalog in {} now validate", sw.toString());
        sw.reset(); sw.start();
        Map<RegisteredType, Collection<Throwable>> validationErrors = catalog.validateTypes( installedTypes );
        log.debug("Validation done in {}", sw.toString());
        if (!validationErrors.isEmpty()) {
            Map<VersionedName, Map<RegisteredType,Collection<Throwable>>> errorsByBundle = MutableMap.of();
            for (RegisteredType t: validationErrors.keySet()) {
                VersionedName vn = VersionedName.fromString(t.getContainingBundle());
                Map<RegisteredType, Collection<Throwable>> errorsInBundle = errorsByBundle.get(vn);
                if (errorsInBundle==null) {
                    errorsInBundle = MutableMap.of();
                    errorsByBundle.put(vn, errorsInBundle);
                }
                errorsInBundle.put(t, validationErrors.get(t));
            }
            for (VersionedName vn: errorsByBundle.keySet()) {
                Map<RegisteredType, Collection<Throwable>> errorsInBundle = errorsByBundle.get(vn);
                ManagedBundle b = managementContext.getOsgiManager().get().getManagedBundle(vn);
                String id = b!=null ? b.getId() : /* just in case it was uninstalled concurrently somehow */ vn.toString();
                exceptionHandler.onCreateFailed(BrooklynObjectType.MANAGED_BUNDLE,
                    id,
                    vn.getSymbolicName(),
                    Exceptions.create("Failed to install "+vn+", types "+errorsInBundle.keySet()+" gave errors",
                        Iterables.concat(errorsInBundle.values())));
            }
        }
    }

    /** install the bundles into brooklyn and osgi, but do not start nor validate;
     * caller (rebind) will do that manually, doing each step across all bundles before proceeding 
     * to prevent reference errors */
    private OsgiBundleInstallationResult installBundle(ManagedBundle bundle, Supplier<InputStream> zipInput) {
        return getManagementContext().getOsgiManager().get().installDeferredStart(bundle, zipInput, false).get();
    }
    
    private void startBundle(OsgiBundleInstallationResult br) throws BundleException {
        if (br.getDeferredStart()!=null) {
            br.getDeferredStart().run();
        }
    }

    private PersistedCatalogState filterBundlesAndCatalogInPersistedState(PersistedCatalogState persistedState, RebindLogger rebindLogger) {
        CatalogUpgrades catalogUpgrades = CatalogUpgrades.getFromManagementContext(managementContext);
        
        if (catalogUpgrades.isEmpty()) {
            return persistedState;
        } else {
            rebindLogger.info("Filtering out persisted catalog: removedBundles="+catalogUpgrades.getRemovedBundles()+"; removedLegacyItems="+catalogUpgrades.getRemovedLegacyItems());
        }
        
        Map<VersionedName, InstallableManagedBundle> bundles = new LinkedHashMap<>();
        for (Map.Entry<VersionedName, InstallableManagedBundle> entry : persistedState.getBundles().entrySet()) {
            if (catalogUpgrades.isBundleRemoved(entry.getKey())) {
                rebindLogger.debug("Filtering out persisted bundle "+entry.getKey());
                // will skip persistence while not yet installed, and will remove when uninstalled
            } else {
                bundles.put(entry.getKey(), entry.getValue());
            }
        }

        List<CatalogItem<?, ?>> legacyCatalogItems = new ArrayList<>();
        for (CatalogItem<?, ?> legacyCatalogItem : persistedState.getLegacyCatalogItems()) {
            if (catalogUpgrades.isLegacyItemRemoved(legacyCatalogItem)) {
                rebindLogger.debug("Filtering out persisted legacy catalog item "+legacyCatalogItem.getId());
                // will skip persistence while not yet installed, and will remove when uninstalled
            } else {
                legacyCatalogItems.add(legacyCatalogItem);
            }
        }
        
        return new PersistedCatalogState(bundles, legacyCatalogItems);
    }

    public interface RebindLogger {
        void debug(String message, Object... args);
        void info(String message, Object... args);
    }
    
    public interface InstallableManagedBundle {
        public ManagedBundle getManagedBundle();
        /** The caller is responsible for closing each stream. */
        public Supplier<InputStream> getInputStreamSource() throws IOException;
    }
    
    public static class PersistedCatalogState {
        private final Map<VersionedName, InstallableManagedBundle> bundles;
        private final Collection<CatalogItem<?, ?>> legacyCatalogItems;
        
        public PersistedCatalogState(Map<VersionedName, InstallableManagedBundle> bundles, Collection<CatalogItem<?, ?>> legacyCatalogItems) {
            this.bundles = checkNotNull(bundles, "bundles");
            this.legacyCatalogItems = checkNotNull(legacyCatalogItems, "legacyCatalogItems");
        }

        /**
         * The persisted bundles (from the {@code /bundles} sub-directory of the persisted state).
         */
        public Map<VersionedName, InstallableManagedBundle> getBundles() {
            return bundles;
        }
        
        /**
         * The persisted catalog items (from the {@code /catalog} sub-directory of the persisted state).
         */
        public Collection<CatalogItem<?,?>> getLegacyCatalogItems() {
            return legacyCatalogItems;
        }
    }
}
