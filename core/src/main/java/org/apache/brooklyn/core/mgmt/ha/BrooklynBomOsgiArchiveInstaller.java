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
package org.apache.brooklyn.core.mgmt.ha;

import com.google.common.annotations.Beta;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import java.io.*;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.annotation.Nullable;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.typereg.ManagedBundle;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.BrooklynVersion;
import org.apache.brooklyn.core.catalog.internal.BasicBrooklynCatalog;
import org.apache.brooklyn.core.catalog.internal.CatalogInitialization;
import org.apache.brooklyn.core.mgmt.ha.OsgiBundleInstallationResult.ResultCode;
import org.apache.brooklyn.core.mgmt.internal.ManagementContextInternal;
import org.apache.brooklyn.core.server.BrooklynServerConfig;
import org.apache.brooklyn.core.typereg.BasicBrooklynTypeRegistry;
import org.apache.brooklyn.core.typereg.BasicManagedBundle;
import org.apache.brooklyn.core.typereg.BundleUpgradeParser.CatalogUpgrades;
import org.apache.brooklyn.core.typereg.RegisteredTypePredicates;
import org.apache.brooklyn.core.typereg.RegisteredTypes;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.core.osgi.BundleMaker;
import org.apache.brooklyn.util.core.osgi.Osgis;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.exceptions.ReferenceWithError;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.osgi.VersionedName;
import org.apache.brooklyn.util.stream.InputStreamSource.InputStreamSourceFromFile;
import org.apache.brooklyn.util.stream.Streams;
import org.apache.brooklyn.util.text.BrooklynVersionSyntax;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.text.VersionComparator;
import org.apache.commons.lang3.tuple.Pair;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Beta
public class BrooklynBomOsgiArchiveInstaller {

    private static final Logger log = LoggerFactory.getLogger(BrooklynBomOsgiArchiveInstaller.class);
    
    public static final ConfigKey<String> PERSIST_MANAGED_BUNDLE_WHITELIST_REGEX = BrooklynServerConfig.PERSIST_MANAGED_BUNDLE_WHITELIST_REGEX;
    
    public static final ConfigKey<String> PERSIST_MANAGED_BUNDLE_BLACKLIST_REGEX = BrooklynServerConfig.PERSIST_MANAGED_BUNDLE_BLACKLIST_REGEX;

    final private OsgiManager osgiManager;
    private ManagedBundle suppliedKnownBundleMetadata;
    private InputStream zipIn;
    
    private boolean start = true;
    private boolean loadCatalogBom = true;
    private String format = null;
    private String bomText = null;
    private boolean force = false;
    private boolean deferredStart = false;
    private boolean validateTypes = true;

    public static class FileWithTempInfo<T extends File> {
        public FileWithTempInfo(T f, boolean isTemp) { this.f = f; this.isTemp = isTemp; }
        private T f;
        public boolean isTemp;

        public void deleteIfTemp() {
            if (isTemp && getFile() !=null) {
                getFile().delete();
                f = null;
            }
        }

        public T getFile() {
            return f;
        }
    }

    private FileWithTempInfo<File> zipFile;
    private boolean isBringingExistingOsgiInstalledBundleUnderBrooklynManagement = false;
    private Manifest discoveredManifest;
    private VersionedName discoveredBomVersionedName;
    OsgiBundleInstallationResult result;
    
    private ManagedBundle inferredMetadata;
    private final boolean inputStreamSupplied;
    
    private volatile Predicate<ManagedBundle> blacklistBundlePersistencePredicate;
    
    public BrooklynBomOsgiArchiveInstaller(OsgiManager osgiManager, ManagedBundle knownBundleMetadata, InputStream zipIn) {
        this.osgiManager = osgiManager;
        this.suppliedKnownBundleMetadata = knownBundleMetadata;
        this.zipIn = zipIn;
        inputStreamSupplied = zipIn!=null;
    }

    public void setStart(boolean start) {
        this.start = start;
    }
    
    public void setLoadCatalogBom(boolean loadCatalogBom) {
        this.loadCatalogBom = loadCatalogBom;
    }

    /** If BOM is generated by a bundle resolver we need to know the generated BOM, and the format for subsequent bundle reload */
    public void setCatalogBomText(String format, String bomText) {
        this.format = format;
        this.bomText = bomText;
    }

    public void setForce(boolean force) {
        this.force = force;
    }

    public void setDeferredStart(boolean deferredStart) {
        this.deferredStart = deferredStart;
    }
    
    public void setValidateTypes(boolean validateTypes) {
        this.validateTypes = validateTypes;
    }

    private ManagementContextInternal mgmt() {
        return (ManagementContextInternal) osgiManager.getManagementContext();
    }
    
    private synchronized void init() {
        if (result!=null) {
            if (zipFile!=null || zipIn==null) return;
            throw new IllegalStateException("This installer instance has already been used and the input stream discarded");
        }
        result = new OsgiBundleInstallationResult();
        inferredMetadata = suppliedKnownBundleMetadata==null ? new BasicManagedBundle() : suppliedKnownBundleMetadata;
    }
    
    private synchronized void makeLocalZipFileFromInputStreamOrUrl() {
        PrepareInstallResult pr = prepareInstall(mgmt(), suppliedKnownBundleMetadata, inferredMetadata, () -> zipIn, force, result);
        if (pr.isBringingExistingOsgiInstalledBundleUnderBrooklynManagement) {
            isBringingExistingOsgiInstalledBundleUnderBrooklynManagement = true;
        }
        if (pr.existingBundle!=null) {
            result.bundle = pr.existingBundle;
        }
        if (pr.zipFile!=null) {
            zipFile = pr.zipFile;
        }
    }

    public static class PrepareInstallResult {
        /** set if there is a bundle matching the given metadata */
        public Bundle existingBundle;
        /** result object ready for returning to a user */
        public OsgiBundleInstallationResult resultObject = null;
        /** set if already installed to OSGi but not to Brooklyn */
        public boolean isBringingExistingOsgiInstalledBundleUnderBrooklynManagement = false;
        /** set if we anticipate a caller may want to read from a ZIP file;
         * this is for the case where no input stream was supplied but
         * OSGi bundle co-ordinates were supplied and can be used to find a ZIP */
        public FileWithTempInfo<File> zipFile;
    }
    public static PrepareInstallResult prepareInstall(ManagementContext mgmt, ManagedBundle suppliedKnownBundleMetadata, ManagedBundle optionalAdditionalInferredMetadata,
                                                      Supplier<InputStream> zipInS, boolean force, @Nullable OsgiBundleInstallationResult resultObject) {
        InputStream zipIn = zipInS == null ? null : zipInS.get();
        PrepareInstallResult prepareInstallResult = new PrepareInstallResult();
        prepareInstallResult.resultObject = resultObject;
        try {
            Maybe<Bundle> existingOsgiInstalledBundle = Maybe.absent();
            Maybe<ManagedBundle> existingBrooklynInstalledBundle = Maybe.absent();
            OsgiManager osgiManager = ((ManagementContextInternal) mgmt).getOsgiManager().get();

            if (suppliedKnownBundleMetadata != null) {
                // Before even trying to find or download the bundle, check if it is supposed to be forcibly replaced.
                // If so, return the replacement (if any).
                if (optionalAdditionalInferredMetadata==null) {
                    optionalAdditionalInferredMetadata = suppliedKnownBundleMetadata;
                }

                if (suppliedKnownBundleMetadata.isNameResolved()) {
                    Maybe<VersionedName> forcedReplacementBundle = CatalogUpgrades.tryGetBundleForcedReplaced(mgmt, suppliedKnownBundleMetadata.getVersionedName());
                    if (forcedReplacementBundle.isPresent()) {
                        if (prepareInstallResult.resultObject==null) {
                            prepareInstallResult.resultObject = new OsgiBundleInstallationResult();
                        }
                        setResultForciblyRemovedResult(osgiManager, suppliedKnownBundleMetadata.getVersionedName(), optionalAdditionalInferredMetadata,
                                forcedReplacementBundle, prepareInstallResult.resultObject);
                        return prepareInstallResult;
                    }
                } else if (suppliedKnownBundleMetadata.getUrl() != null && suppliedKnownBundleMetadata.getUrl().toLowerCase().startsWith("mvn:")) {
                    // This inference is not guaranteed to get the right answer - you can put whatever
                    // you want in the MANIFEST.MF. Also, the maven-bundle-plugin does some surprising
                    // transforms, but we take a simpler approach here.
                    // If folk want it to work for such edge-cases, they should include the
                    // name:version explicitly in the `brooklyn.libraries list`.
                    Optional<VersionedName> inferredName = inferBundleNameFromMvnUrl(suppliedKnownBundleMetadata.getUrl());
                    if (inferredName.isPresent()) {
                        Maybe<VersionedName> forcedReplacementBundle = CatalogUpgrades.tryGetBundleForcedReplaced(mgmt, inferredName.get());
                        if (forcedReplacementBundle.isPresent()) {
                            if (prepareInstallResult.resultObject==null) {
                                prepareInstallResult.resultObject = new OsgiBundleInstallationResult();
                            }
                            setResultForciblyRemovedResult(osgiManager, inferredName.get(), optionalAdditionalInferredMetadata,
                                    forcedReplacementBundle, prepareInstallResult.resultObject);
                            return prepareInstallResult;
                        }
                    }
                }

                if (zipIn == null) {
                    // if no input stream (zipIn), look for a URL and/or a matching bundle
                    if (!suppliedKnownBundleMetadata.isNameResolved()) {
                        existingBrooklynInstalledBundle = Maybe.ofDisallowingNull(osgiManager.getManagedBundleFromUrl(suppliedKnownBundleMetadata.getUrl()));
                        if (existingBrooklynInstalledBundle.isPresent()) {
                            // user supplied just a URL (eg brooklyn.libraries), but we recognise it,
                            // so don't try to reload it, just record the info we know about it to retrieve the bundle
                            ((BasicManagedBundle) suppliedKnownBundleMetadata).setSymbolicName(existingBrooklynInstalledBundle.get().getSymbolicName());
                            ((BasicManagedBundle) suppliedKnownBundleMetadata).setVersion(existingBrooklynInstalledBundle.get().getSuppliedVersionString());
                        }
                    }
                    if (existingOsgiInstalledBundle.isAbsent() && suppliedKnownBundleMetadata.getOsgiUniqueUrl() != null) {
                        existingOsgiInstalledBundle = Osgis.bundleFinder(osgiManager.getFramework()).requiringFromUrl(suppliedKnownBundleMetadata.getOsgiUniqueUrl()).find();
                    }
                    if (existingOsgiInstalledBundle.isAbsent() && suppliedKnownBundleMetadata.getUrl() != null) {
                        existingOsgiInstalledBundle = Osgis.bundleFinder(osgiManager.getFramework()).requiringFromUrl(suppliedKnownBundleMetadata.getUrl()).find();
                    }
                    if (existingOsgiInstalledBundle.isAbsent() && suppliedKnownBundleMetadata.isNameResolved()) {
                        existingOsgiInstalledBundle = Osgis.bundleFinder(osgiManager.getFramework()).symbolicName(suppliedKnownBundleMetadata.getSymbolicName()).version(suppliedKnownBundleMetadata.getSuppliedVersionString()).find();
                    }
                    if (existingOsgiInstalledBundle.isPresent()) {
                        if (existingBrooklynInstalledBundle.isAbsent()) {
                            // try to find as brooklyn bundle based on knowledge of OSGi bundle
                            existingBrooklynInstalledBundle = Maybe.ofDisallowingNull(osgiManager.getManagedBundle(new VersionedName(existingOsgiInstalledBundle.get())));
                        }
                        if (suppliedKnownBundleMetadata.getUrl() == null) {
                            // installer did not supply a usable URL, just coords
                            // but bundle is installed at least to OSGi
                            if (existingBrooklynInstalledBundle.isPresent()) {
                                log.debug("Detected bundle " + suppliedKnownBundleMetadata + " installed to Brooklyn already; no URL or stream supplied, so re-using existing installation");
                                // if bundle is brooklyn-managed simply say "already installed"
                                //                            prepareInstallResult.existingBundleMetadata = existingBrooklynInstalledBundle.get();
                                if (prepareInstallResult.resultObject==null) {
                                    prepareInstallResult.resultObject = new OsgiBundleInstallationResult();
                                }
                                prepareInstallResult.resultObject.metadata = existingBrooklynInstalledBundle.get();
                                prepareInstallResult.resultObject.setIgnoringAlreadyInstalled();

                            } else {
                                // if bundle is not brooklyn-managed we want to make it be so
                                // and for that we need to find a URL.
                                // the getLocation() _might_ be usable, or might be totally opaque;
                                // in tests we rely on the block below (see system:file:) and things
                                // being explicitly set, but in live and rebind deployments the URL
                                // in practice with karaf how we package it is of the form mvn:...
                                // which _does_ work in this block, so we will be able to do most
                                // things which rely on taking osgi-installed bundles into brooklyn mgmt
                                // (and if not don't think it's a big deal, we just uninstall and reinstall
                                // sometimes or fail with a reasonable error message)
                                String candidateUrl = existingOsgiInstalledBundle.get().getLocation();
                                log.debug("Detected bundle " + suppliedKnownBundleMetadata + " installed to OSGi but not Brooklyn; trying to find a URL to get bundle binary, candidate " + candidateUrl);
                                if (Strings.isBlank(candidateUrl)) {
                                    throw new IllegalArgumentException("No input stream available and no URL could be found: no way to promote " + suppliedKnownBundleMetadata + " from " + existingOsgiInstalledBundle.get() + " to Brooklyn management");
                                }
                                try {
                                    // do this in special try block, not below, so we can give a better error
                                    // (the user won't understand the URL)
                                    zipIn = ResourceUtils.create(mgmt).getResourceFromUrl(candidateUrl);
                                    prepareInstallResult.isBringingExistingOsgiInstalledBundleUnderBrooklynManagement = true;
                                } catch (Exception e) {
                                    Exceptions.propagateIfFatal(e);
                                    throw new IllegalArgumentException("Could not find binary for already installed OSGi bundle " + existingOsgiInstalledBundle.get() + " (location " + candidateUrl + ") when trying to promote " + suppliedKnownBundleMetadata + " to Brooklyn management", e);
                                }
                            }
                        }
                    } else if (suppliedKnownBundleMetadata.getUrl() == null) {
                        // not installed anywhere and no URL
                        throw new IllegalArgumentException("No input stream available and no URL could be found: no way to install " + suppliedKnownBundleMetadata);
                    }

                    if (zipIn != null) {
                        // found input stream for existing osgi bundle

                    } else if (existingBrooklynInstalledBundle.isAbsent() || force) {
                        // reload
                        String url = suppliedKnownBundleMetadata.getUrl();
                        if (url == null) {
                            throw new IllegalStateException("should have found a stream or inferred a URL or already installed");
                        }
                        if (BrooklynVersion.isDevelopmentEnvironment() && url.startsWith("system:file:")) {
                            // in live dists the url is usually mvn: but in dev/test karaf will prefix it with system;
                            // leave the url alone so we correctly dedupe when considering whether to update, but create a zip file
                            // so that things work consistently in dev/test (in particular ClassLoaderUtilsTest passes).
                            // pretty sure we have to do this, even if not replacing the osgi bundle, because we need to
                            // get a handle on the zip file (although we could skip if not doing persistence - but that feels even worse than this!)
                            try {
                                url = Strings.removeFromStart(url, "system:");
                                File zipTemp = new BundleMaker(ResourceUtils.create(mgmt)).createJarFromClasspathDir(url);
                                zipIn = new FileInputStream(zipTemp);
                            } catch (FileNotFoundException e) {
                                throw Exceptions.propagate(e);
                            }
                        } else {
                            zipIn = ResourceUtils.create(mgmt).getResourceFromUrl(url, suppliedKnownBundleMetadata.getUrlCredential());
                        }
                    } else {
                        // already installed, not forced, just say already installed
                        // (even if snapshot as this is a reference by URL, not uploaded content)
                        //                    prepareInstallResult.existingBundleMetadata = existingBrooklynInstalledBundle.get();
                        if (prepareInstallResult.resultObject==null) {
                            prepareInstallResult.resultObject = new OsgiBundleInstallationResult();
                        }
                        prepareInstallResult.resultObject.metadata = existingBrooklynInstalledBundle.get();
                        prepareInstallResult.resultObject.setIgnoringAlreadyInstalled();

                    }
                }
            }

            prepareInstallResult.existingBundle = existingOsgiInstalledBundle.orNull();

            if (zipIn != null) {
                if (zipInS instanceof InputStreamSourceFromFile) {
                    prepareInstallResult.zipFile = new FileWithTempInfo<File>( ((InputStreamSourceFromFile) zipInS).getFile(), false );
                } else {
                    prepareInstallResult.zipFile = new FileWithTempInfo<File>( Os.newTempFile("brooklyn-bundle-transient-" + suppliedKnownBundleMetadata, "zip"), true );
                    try {
                        FileOutputStream fos = new FileOutputStream(prepareInstallResult.zipFile.getFile());
                        Streams.copyClose(zipIn, fos);
                        try (ZipFile zf = new ZipFile(prepareInstallResult.zipFile.getFile())) {
                            // validate it is a valid ZIP, otherwise errors are more obscure later.
                            // can happen esp if user supplies a file://path/to/folder/ as the URL.openStream returns a list of that folder (!)
                            // the error thrown by the below is useful enough, and caller will wrap with suppliedKnownBundleMetadata details
                            zf.entries();
                        }
                    } catch (Exception e) {
                        try {
                            prepareInstallResult.zipFile.deleteIfTemp();
                        } catch (Exception e2) {
                            Exceptions.propagateIfFatal(e2);
                            log.warn("Error deleting ZIP file but ignoring because handling error "+e+": "+e2);
                        }
                        throw Exceptions.propagate(e);
                    }
                }
            }
        } finally {
            Streams.closeQuietly(zipIn);
            zipIn = null;
        }

        return prepareInstallResult;
    }

    private void discoverManifestFromCatalogBom(boolean isCatalogBomRequired) {
        discoveredManifest = new BundleMaker(mgmt()).getManifest(zipFile.getFile());

        if (Strings.isNonBlank(bomText)) {
            discoveredBomVersionedName = BasicBrooklynCatalog.getVersionedName(BasicBrooklynCatalog.getCatalogMetadata(bomText), false );
            return;
        }

        ZipFile zf = null;
        try {
            try {
                zf = new ZipFile(zipFile.getFile());
            } catch (IOException e) {
                throw new IllegalArgumentException("Invalid ZIP/JAR archive: "+e);
            }
            ZipEntry bom = zf.getEntry(BasicBrooklynCatalog.CATALOG_BOM);
            if (bom==null) {
                bom = zf.getEntry("/"+BasicBrooklynCatalog.CATALOG_BOM);
            }
            if (bom==null) {
                if (isCatalogBomRequired) {
                    throw new IllegalArgumentException("Archive must contain a catalog.bom file in the root");
                } else {
                    return;
                }
            }
            try {
                bomText = Streams.readFullyString(zf.getInputStream(bom));
            } catch (IOException e) {
                throw new IllegalArgumentException("Error reading catalog.bom from ZIP/JAR archive: "+e);
            }
            discoveredBomVersionedName = BasicBrooklynCatalog.getVersionedName( BasicBrooklynCatalog.getCatalogMetadata(bomText), false );
        } finally {
            Streams.closeQuietly(zf);
        }
    }
    
    private void updateManifestFromAllSourceInformation() {
        if (discoveredBomVersionedName !=null) {
            matchSetOrFail("catalog.bom in archive", discoveredBomVersionedName.getSymbolicName(), discoveredBomVersionedName.getVersionString());
        }
        
        boolean manifestNeedsUpdating = false;
        if (discoveredManifest==null) {
            discoveredManifest = new Manifest();
            manifestNeedsUpdating = true;
        }
        if (!matchSetOrFail("MANIFEST.MF in archive", discoveredManifest.getMainAttributes().getValue(Constants.BUNDLE_SYMBOLICNAME),
                discoveredManifest.getMainAttributes().getValue(Constants.BUNDLE_VERSION))) {
            manifestNeedsUpdating = true;                
            discoveredManifest.getMainAttributes().putValue(Constants.BUNDLE_SYMBOLICNAME, inferredMetadata.getSymbolicName());
            discoveredManifest.getMainAttributes().putValue(Constants.BUNDLE_VERSION, inferredMetadata.getOsgiVersionString());
        }
        if (Strings.isBlank(inferredMetadata.getSymbolicName())) {
            throw new IllegalArgumentException("Missing bundle symbolic name in BOM or MANIFEST");
        }
        if (Strings.isBlank(inferredMetadata.getSuppliedVersionString())) {
            throw new IllegalArgumentException("Missing bundle version in BOM or MANIFEST");
        }
        if (discoveredManifest.getMainAttributes().getValue(Attributes.Name.MANIFEST_VERSION)==null) {
            discoveredManifest.getMainAttributes().putValue(Attributes.Name.MANIFEST_VERSION.toString(), BasicBrooklynCatalog.OSGI_MANIFEST_VERSION_VALUE);
            manifestNeedsUpdating = true;
        }
        if (discoveredManifest.getMainAttributes().getValue(Constants.BUNDLE_MANIFESTVERSION)==null ||
                (""+discoveredManifest.getMainAttributes().getValue(Constants.BUNDLE_MANIFESTVERSION)).startsWith("1")) {
            // manifest version must be 2 otherwise cannot install to osgi
            discoveredManifest.getMainAttributes().putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
            manifestNeedsUpdating = true;                
        }
        if (manifestNeedsUpdating) {
            File zf2 = new BundleMaker(mgmt()).copyAddingManifest(zipFile.getFile(), discoveredManifest);
            zipFile.deleteIfTemp();
            zipFile = new FileWithTempInfo<>(zf2, zipFile.isTemp);
        }
    }
    
    private synchronized void close() {
        if (zipFile!=null) {
            zipFile.deleteIfTemp();
            zipFile = null;
        }
    }
    
    /**
     * Installs a bundle, taking from ZIP input stream if supplied, falling back to URL in the {@link ManagedBundle} metadata supplied.
     * It will take metadata from any of: a MANIFEST.MF in the ZIP; a catalog.bom in the ZIP; the {@link ManagedBundle} metadata supplied.
     * If metadata is supplied in multiple such places, it must match.
     * Appropriate metadata will be added to the ZIP and installation attempted.
     * <p>
     * If a matching bundle is already installed, the installation will stop with a {@link ResultCode#IGNORING_BUNDLE_AREADY_INSTALLED}
     * unless the bundle is a snapshot or "force" is specified.
     * In the latter two cases, if there is an installed matching bundle, that bundle will be updated with the input stream here,
     * with any catalog items from the old bundle removed and those from this bundle installed.
     * <p>
     * Default behaviour is {@link #setLoadCatalogBom(boolean)} true and {@link #setForce(boolean)} false.
     * <p>
     * The return value is extensive but should be self-evident, and will include a list of any registered types (catalog items) installed. 
     */
    public ReferenceWithError<OsgiBundleInstallationResult> install() {
        boolean startedInstallation = false;
        
        try {
            init();

            makeLocalZipFileFromInputStreamOrUrl();
            if (result.code!=null) return ReferenceWithError.newInstanceWithoutError(result);
            discoverManifestFromCatalogBom(false);
            if (result.code!=null) return ReferenceWithError.newInstanceWithoutError(result);
            updateManifestFromAllSourceInformation();
            if (result.code!=null) return ReferenceWithError.newInstanceWithoutError(result);
            assert inferredMetadata.isNameResolved() : "Should have resolved "+inferredMetadata;
            assert inferredMetadata instanceof BasicManagedBundle : "Only BasicManagedBundles supported";
            ((BasicManagedBundle)inferredMetadata).setChecksum(getChecksum(new ZipFile(zipFile.getFile())));
            ((BasicManagedBundle)inferredMetadata).setFormat(format);

            final boolean updating;
            result.metadata = osgiManager.getManagedBundle(inferredMetadata.getVersionedName());
            if (result.getMetadata()!=null) {
                
                // already have a managed bundle - check if this is using a new/different URL
                if (suppliedKnownBundleMetadata!=null && suppliedKnownBundleMetadata.getUrl()!=null) {
                    String knownIdForThisUrl = osgiManager.managedBundlesRecord.getManagedBundleIdFromUrl(suppliedKnownBundleMetadata.getUrl());
                    if (knownIdForThisUrl==null) {
                        // it's a new URL, but a bundle we already know about
                        log.warn("Request to install from "+suppliedKnownBundleMetadata.getUrl()+" which is not recognized but "+
                            "appears to match "+result.getMetadata()+"; now associating with the latter");
                        osgiManager.managedBundlesRecord.setManagedBundleUrl(suppliedKnownBundleMetadata.getUrl(), result.getMetadata().getId());
                    } else if (!knownIdForThisUrl.equals(result.getMetadata().getId())) {
                        log.warn("Request to install from "+suppliedKnownBundleMetadata.getUrl()+" which is associated to "+knownIdForThisUrl+" but "+
                            "appears to match "+result.getMetadata()+"; now associating with the latter");
                        osgiManager.managedBundlesRecord.setManagedBundleUrl(suppliedKnownBundleMetadata.getUrl(), result.getMetadata().getId());
                    }
                }

                log.debug("Request to install "+inferredMetadata.getVersionedName()+" (checksum "+inferredMetadata.getChecksum()+", OSGi URL "+inferredMetadata.getOsgiUniqueUrl()+") in the presence of "+result.getMetadata().getVersionedName()+" (checksum "+result.getMetadata().getChecksum()+", OSGi URL "+result.getMetadata().getOsgiUniqueUrl()+")");

                result.bundle = osgiManager.getFramework().getBundleContext().getBundle(result.getMetadata().getOsgiUniqueUrl());

                // Check if exactly this bundle is already installed
                if (result.bundle != null && checksumsMatch(result.getMetadata(), inferredMetadata)) {
                    // e.g. repeatedly installing the same bundle
                    log.trace("Bundle "+inferredMetadata+" matches already installed managed bundle "+result.getMetadata()
                            +"; install is no-op");
                    result.setIgnoringAlreadyInstalled();
                    return ReferenceWithError.newInstanceWithoutError(result);

                }

                List<Bundle> matchingVsnBundles = findBundlesBySymbolicNameAndVersion(osgiManager, inferredMetadata);

                List<Bundle> sameContentBundles = matchingVsnBundles.stream().filter(b -> isBundleSameOsgiUrlOrSameContents(b, inferredMetadata, zipFile.getFile())).collect(Collectors.toList());
                if (!sameContentBundles.isEmpty()) {
                    // e.g. happens if pre-installed bundle is brought under management, and then add it again via a mvn-style url.
                    // We wouldn't know the checksum from the pre-installed bundle, the osgi locations might be different,
                    // but the contents are the same
                    log.trace("Bundle "+inferredMetadata+" matches metadata of managed bundle "+result.getMetadata()
                            +" (but not OSGi bundle location "+result.getMetadata().getOsgiUniqueUrl()+"), "
                            + "and identified as equivalent to installed OSGi bundle; ; install is no-op");
                    result.setIgnoringAlreadyInstalled();
                    result.bundle = sameContentBundles.iterator().next();
                    return ReferenceWithError.newInstanceWithoutError(result);
                }

                if (canUpdate()) {
                    if (result.bundle == null && !matchingVsnBundles.isEmpty()) {
                        // if we are updating a snapshot bundle or forcing, and somehow we did not manage to preserve the original OSGi location
                        log.info("Updating existing brooklyn-managed bundle "+result+" with "+inferredMetadata+" with different OSGi location and different contents");
                        result.bundle = matchingVsnBundles.iterator().next();
                    }

                    if (result.getBundle() == null) {
                        log.warn("Brooklyn thought it was already managing bundle "+result.getMetadata().getVersionedName()
                                +" but it's not installed to framework at location "+result.getMetadata().getOsgiUniqueUrl()+"; reinstalling it");
                        updating = false;
                    } else {
                        log.trace("Updating existing brooklyn-managed bundle "+result);
                        updating = true;
                    }
                } else {
                    if (matchingVsnBundles.size() > 0 && (result.getMetadata().getChecksum()==null || inferredMetadata.getChecksum()==null)) {
                        // e.g. Checksum would be missing if we brought under management a pre-installed bundle with an unusable url.
                        log.info("Missing bundle checksum data for "+result+"; assuming bundle matches existing brooklyn-managed bundle (not re-installing)");
                        result.setIgnoringAlreadyInstalled();
                        return ReferenceWithError.newInstanceWithoutError(result);
                    } else if (result.bundle != null || matchingVsnBundles.size() > 0) {
                        throw new IllegalArgumentException("Bundle "+result.getMetadata().getVersionedName()+" already installed; "
                                + "cannot install a different bundle with the same non-snapshot version");
                    } else {
                        throw new IllegalArgumentException("Bundle "+result.getMetadata().getVersionedName()+" already a brooklyn-managed bundle, but not found in OSGi framework; "
                                + "will not re-install without use of 'force'");
                    }
                }

                // if proceeding with install, use the new metadata but the old id and osgi url
                // (the osgi url must match because we use "getBundle(...)" to update it)
                result.metadata = BasicManagedBundle.copyFirstWithCoordsOfSecond(inferredMetadata, result.metadata);

            } else {
                // No such Brooklyn-managed bundle.

                // Check if likely-looking bundle already installed to OSGi subsystem, but brooklyn not aware of it.
                // This will often happen on a karaf restart where bundle was cached by karaf, so we need to allow it;
                // can also happen if brooklyn.libraries references an existing bundle.
                //
                // If we're not certain that the bundle is identical 

                // First check if this bundle is forcibly removed (and optionally upgraded).
                // If so, don't install it - return the replacement, if any.
                Maybe<VersionedName> forcedReplacementBundle = CatalogUpgrades.tryGetBundleForcedReplaced(mgmt(), inferredMetadata.getVersionedName());
                if (forcedReplacementBundle.isPresent()) {
                    setResultForciblyRemovedResult(osgiManager, inferredMetadata.getVersionedName(), inferredMetadata, forcedReplacementBundle, result);
                    return ReferenceWithError.newInstanceWithoutError(result);
                }
                
                result.metadata = inferredMetadata;
                
                // search for already-installed bundles.
                List<Bundle> existingBundles = findBundlesBySymbolicNameAndVersion(osgiManager, inferredMetadata);
                Maybe<Bundle> existingEquivalentBundle = tryFindSameOsgiUrlOrSameContentsBundle(existingBundles, inferredMetadata, zipFile.getFile());
                
                if (existingEquivalentBundle.isPresent()) {
                    // Identical bundle (by osgi location or binary content) already installed; just bring that under management.
                    // This will often happen on a karaf restart: bundles from persisted state match those cached by karaf,
                    isBringingExistingOsgiInstalledBundleUnderBrooklynManagement = true;
                    result.bundle = existingEquivalentBundle.get();
                    
                } else if (existingBundles.size() > 0) {
                    Bundle existingBundle = existingBundles.get(0);
                    
                    if (force) {
                        if (isBringingExistingOsgiInstalledBundleUnderBrooklynManagement) {
                            log.debug("Request to install "+inferredMetadata+" was forced, so forcing reinstallation "
                                + "of existing OSGi installed (but not Brooklyn-managed) bundle "+existingBundle);
                            isBringingExistingOsgiInstalledBundleUnderBrooklynManagement = false;
                        }
                    }
                    
                    if (isBringingExistingOsgiInstalledBundleUnderBrooklynManagement) {
                        // We were explicitly asked to bring an existing OSGi bundle under management; 
                        // no equivalence check required
                        result.bundle = existingBundle;
                    } else {
                        // Uninstall and re-install the bundle.
                        // This is a good idea for brooklyn managed bundles that were in the karaf cache (when we can't 
                        // determine that they are definitely identical).
                        // It's less good for pre-installed bundles, but if the user has said to deploy it or has
                        // referenced it in `brooklyn.libraries` then we'll go for it anyway! Let's hope they didn't 
                        // reference `org.apache.brooklyn.core` or some such.
                        // 
                        // We are this extreme because we want rebind to always work! If a user did a `force` install
                        // of a bundle, then we want to do the same on rebind (rather than risk failing).
                        //
                        // Instead of uninstall, we could update the bundle.
                        // Note however either way we won't be able to rollback if there is a failure
                        log.debug("Brooklyn install of "+result.getMetadata().getVersionedName()+" detected already loaded in OSGi; uninstalling that to reinstall as Brooklyn-managed");
                        existingBundle.uninstall();
                        result.bundle = null;
                    }
                }
                
                updating = false;
            }
            
            startedInstallation = true;
            try (InputStream fin = new FileInputStream(zipFile.getFile())) {
                if (!updating) {
                    if (isBringingExistingOsgiInstalledBundleUnderBrooklynManagement) {
                        assert result.getBundle()!=null;
                        log.debug("Brooklyn install of "+result.getMetadata().getVersionedName()+" detected already loaded "+result.getBundle()+" in OSGi can be re-used, skipping OSGi install");
                    } else {
                        assert result.getBundle()==null;
                        log.debug("Installing bundle "+result.getMetadata().getVersionedName()+", using OSGi location "+result.getMetadata().getOsgiUniqueUrl());
                        result.bundle = osgiManager.getFramework().getBundleContext().installBundle(result.getMetadata().getOsgiUniqueUrl(), fin);
                    }
                } else {
                    result.bundle.update(fin);
                }
            }
            
            osgiManager.checkCorrectlyInstalled(result.getMetadata(), result.bundle);
            final File oldZipFile;
            final ManagedBundle oldManagedBundle;
            
            if (!updating) { 
                oldZipFile = null;
                oldManagedBundle = null;
                osgiManager.managedBundlesRecord.addManagedBundle(result, zipFile.getFile());
                result.code = OsgiBundleInstallationResult.ResultCode.INSTALLED_NEW_BUNDLE;
                result.message = "Installed Brooklyn catalog bundle "+result.getMetadata().getVersionedName()+" with ID "+result.getMetadata().getId()+" ["+result.bundle.getBundleId()+"]";
            } else {
                Pair<File, ManagedBundle> olds = osgiManager.managedBundlesRecord.updateManagedBundleFileAndMetadata(result, zipFile.getFile());
                oldZipFile = olds.getLeft();
                oldManagedBundle = olds.getRight();

                result.code = OsgiBundleInstallationResult.ResultCode.UPDATED_EXISTING_BUNDLE;
                result.message = "Updated Brooklyn catalog bundle "+result.getMetadata().getVersionedName()+" as existing ID "+result.getMetadata().getId()+" ["+result.bundle.getBundleId()+"]";
            }
            log.debug(result.message + " (partial): OSGi bundle installed, with bundle start and Brooklyn management to follow");
            // can now delete and close (copy has been made and is available from OsgiManager)
            zipFile.deleteIfTemp();
            zipFile = null;
            
            // setting the above before the code below means if there is a problem starting or loading catalog items
            // a user has to remove then add again, or forcibly reinstall;
            // that seems fine and probably better than allowing bundles to start and catalog items to be installed 
            // when brooklyn isn't aware it is supposed to be managing it
            
            // starting here flags wiring issues earlier
            // but may break some things running from the IDE
            // eg if it doesn't have OSGi deps, or if it doesn't have camp parser,
            // or if caller is installing multiple things that depend on each other
            // eg rebind code, brooklyn.libraries list -- deferred start allows caller to
            // determine whether not to start or to start all after things are installed
            Runnable startRunnable = new Runnable() {
                private void rollbackBundle() {
                    if (updating) {
                        if (oldZipFile==null) {
                            throw new IllegalStateException("Did not have old ZIP file to install");
                        }
                        log.debug("Rolling back bundle "+result.getVersionedName()+" to state "+oldManagedBundle+" from "+oldZipFile);
                        try {
                            File zipFileNow = osgiManager.managedBundlesRecord.rollbackManagedBundleFileAndMetadata(result, oldZipFile, oldManagedBundle);
                            result.bundle.update(new FileInputStream(Preconditions.checkNotNull(zipFileNow, "Couldn't find contents of old version of bundle")));
                        } catch (Exception e) {
                            Exceptions.propagateIfFatal(e);
                            log.error("Error rolling back following failed install of updated "+result.getVersionedName()+"; "
                                + "installation will likely be corrupted and correct version should be manually installed.", e);
                        }
                        
                        if (!isBlacklistedForPersistence(result.getMetadata())) {
                            ((BasicManagedBundle)result.getMetadata()).setPersistenceNeeded(true);
                            mgmt().getRebindManager().getChangeListener().onChanged(result.getMetadata());
                        }
                    } else {
                        if (isBringingExistingOsgiInstalledBundleUnderBrooklynManagement) {
                            log.debug("Uninstalling bundle "+result.getVersionedName()+" from Brooklyn management only (rollback needed but it was already installed to OSGi)");
                        } else {
                            log.debug("Uninstalling bundle "+result.getVersionedName()+" (roll back of failed fresh install, no previous version to revert to)");
                        }                        
                        osgiManager.uninstallUploadedBundle(result.getMetadata(), false, isBringingExistingOsgiInstalledBundleUnderBrooklynManagement);
                        if (!isBlacklistedForPersistence(result.getMetadata())) {
                            ((BasicManagedBundle)result.getMetadata()).setPersistenceNeeded(true);
                            mgmt().getRebindManager().getChangeListener().onUnmanaged(result.getMetadata());
                        }
                    }
                }
                public void run() {
                    if (start) {
                        try {
                            log.debug("Starting bundle "+result.getVersionedName());
                            if (!isBlacklistedForPersistence(result.getMetadata())) {
                                ((BasicManagedBundle)result.getMetadata()).setPersistenceNeeded(true);
                                if (updating) {
                                    mgmt().getRebindManager().getChangeListener().onChanged(result.getMetadata());
                                } else {
                                    mgmt().getRebindManager().getChangeListener().onManaged(result.getMetadata());
                                }
                            }
                            result.bundle.start();
                        } catch (BundleException e) {
                            log.warn("Error starting bundle "+result.getVersionedName()+", uninstalling, restoring any old bundle, then re-throwing error: "+e);
                            try {
                                rollbackBundle();
                            } catch (Throwable t) {
                                Exceptions.propagateIfFatal(t);
                                log.warn("Error rolling back "+result.getVersionedName()+" after bundle start problem; server may be in inconsistent state (swallowing this error and propagating installation error): "+Exceptions.collapseText(t), t);
                                throw Exceptions.propagate(new BundleException("Failure installing and rolling back; server may be in inconsistent state regarding bundle "+result.getVersionedName()+". "
                                    + "Rollback failure ("+Exceptions.collapseText(t)+") detailed in log. Installation error is: "+Exceptions.collapseText(e), e));
                            }
                            
                            throw Exceptions.propagate(e);
                        }
                    }
        
                    if (loadCatalogBom) {
                        Iterable<RegisteredType> itemsFromOldBundle = null;
                        Map<RegisteredType, RegisteredType> itemsReplacedHere = null;
                        try {
                            if (updating) {
                                itemsFromOldBundle = osgiManager.uninstallCatalogItemsFromBundle( result.getVersionedName() );
                                // (ideally removal and addition would be atomic)
                            }
                            itemsReplacedHere = MutableMap.of();
                            osgiManager.loadBrooklynBundleWithCatalogBom(result.bundle, bomText, force, validateTypes, itemsReplacedHere);
                            Iterable<RegisteredType> items = mgmt().getTypeRegistry().getMatching(RegisteredTypePredicates.containingBundle(result.getMetadata()));
                            log.debug("Adding items from bundle "+result.getVersionedName()+": "+items);
                            for (RegisteredType ci: items) {
                                result.addType(ci);
                            }
                        } catch (Exception e) {
                            // unable to install new items; rollback bundles
                            // and reload replaced items

                            if (CatalogInitialization.isRebindReadOnlyShuttingDown(osgiManager.mgmt)) {
                                // very likely when RO mode interrupted - ignore
                                throw Exceptions.propagate(e);
                            }
                            
                            log.warn("Error adding Brooklyn items from bundle "+result.getVersionedName()+", uninstalling, restoring any old bundle and items, then re-throwing error: "+Exceptions.collapseText(e));
                            try {
                                rollbackBundle();
                            } catch (Throwable t) {
                                Exceptions.propagateIfFatal(t);
                                log.warn("Error rolling back "+result.getVersionedName()+" after catalog install problem; server may be in inconsistent state (swallowing this error and propagating installation error): "+Exceptions.collapseText(t), t);
                                throw Exceptions.propagate(new BundleException("Failure loading catalog items, and also failed rolling back; server may be in inconsistent state regarding bundle "+result.getVersionedName()+". "
                                    + "Rollback failure ("+Exceptions.collapseText(t)+") detailed in log. Installation error is: "+Exceptions.collapseText(e), e));
                            }
                            if (itemsFromOldBundle!=null) {
                                // add back all itemsFromOldBundle (when replacing a bundle)
                                for (RegisteredType oldItem: itemsFromOldBundle) {
                                    if (log.isTraceEnabled()) {
                                        log.trace("RESTORING replaced bundle item "+oldItem+"\n"+ RegisteredTypes.getImplementationDataStringForSpec(oldItem));
                                    }
                                    ((BasicBrooklynTypeRegistry)mgmt().getTypeRegistry()).addToLocalUnpersistedTypeRegistry(oldItem, true);
                                }
                            }
                            if (itemsReplacedHere!=null) {
                                // and restore any items from other bundles (eg wrappers) that were replaced
                                MutableList<RegisteredType> replaced = MutableList.copyOf(itemsReplacedHere.values());
                                // in reverse order so if other bundle adds multiple we end up with the real original
                                Collections.reverse(replaced);
                                for (RegisteredType oldItem: replaced) {
                                    if (oldItem!=null) {
                                        if (log.isTraceEnabled()) {
                                            log.trace("RESTORING replaced external item "+oldItem+"\n"+RegisteredTypes.getImplementationDataStringForSpec(oldItem));
                                        }
                                        ((BasicBrooklynTypeRegistry)mgmt().getTypeRegistry()).addToLocalUnpersistedTypeRegistry(oldItem, true);
                                    }
                                }
                            }
                            
                            throw Exceptions.propagate(e);
                        }
                    }
                }
            };
            if (deferredStart) {
                result.deferredStart = startRunnable;
                log.debug(result.message+" (Brooklyn load deferred)");
            } else {
                startRunnable.run();
                if (!result.typesInstalled.isEmpty()) {
                    // show fewer info messages, only for 'interesting' and non-deferred installations
                    // (rebind is deferred, as are tests, but REST is not)
                    final int MAX_TO_LIST_EXPLICITLY = 5;
                    Iterable<String> firstN = Iterables.transform(MutableList.copyOf(Iterables.limit(result.typesInstalled, MAX_TO_LIST_EXPLICITLY)),
                            input -> input.getVersionedName().toString());
                    log.info(result.message+", items: "+firstN+
                        (result.typesInstalled.size() > MAX_TO_LIST_EXPLICITLY ? " (and others, "+result.typesInstalled.size()+" total)" : "") );
                    if (log.isDebugEnabled() && result.typesInstalled.size()>MAX_TO_LIST_EXPLICITLY) {
                        log.debug(result.message+", all items: "+result.typesInstalled);
                    }
                } else {
                    log.debug(result.message+" (complete): bundle started and now managed by Brooklyn, though no catalog items found (may have installed other bundles though)");
                }
            }

            return ReferenceWithError.newInstanceWithoutError(result);
            
        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            result.code = startedInstallation ? OsgiBundleInstallationResult.ResultCode.ERROR_LAUNCHING_BUNDLE : OsgiBundleInstallationResult.ResultCode.ERROR_PREPARING_BUNDLE;
            result.message = "Bundle "+inferredMetadata+" failed "+
                (startedInstallation ? "installation" : "preparation") + ": " + Exceptions.collapseText(e);
            return ReferenceWithError.newInstanceThrowingError(result, new IllegalStateException(result.message, e));
        } finally {
            close();
        }
    }

    @VisibleForTesting
    static Optional<VersionedName> inferBundleNameFromMvnUrl(String url) {
        // Assumes format 'mvn:<groupId>/<artifactId>/<version>'
        // e.g. "mvn:io.brooklyn.etcd/brooklyn-etcd/2.7.0"
        assert url.startsWith("mvn:") : "url="+url;
        String[] parts = url.substring(4).split("/");
        if (parts.length != 3) return Optional.absent();
        if (parts[0].trim().isEmpty() || parts[1].trim().isEmpty() || parts[2].trim().isEmpty()) return Optional.absent();
        return Optional.of(new VersionedName(parts[0]+"."+parts[1], parts[2]));
    }

    private static void setResultForciblyRemovedResult(OsgiManager osgiManager, VersionedName desiredBundle, ManagedBundle inferredMetadata,
                                                       Maybe<VersionedName> replacementBundle, OsgiBundleInstallationResult result) {
        if (replacementBundle.isPresentAndNonNull()) {
            result.metadata = osgiManager.getManagedBundle(replacementBundle.get());
            if (result.getMetadata() != null) {
                result.bundle = osgiManager.getFramework().getBundleContext().getBundle(result.getMetadata().getOsgiUniqueUrl());

                log.debug("Bundle "+inferredMetadata+" forcibly replaced by bundle "+result.getMetadata() +"; install is no-op");
                result.setIgnoringForciblyRemoved(inferredMetadata.getVersionedName(), replacementBundle);
                return;
            } else {
                throw new IllegalArgumentException("Bundle "+inferredMetadata+" forcibly replaced by bundle "
                        +replacementBundle.get()+", but replacement not found");
            }
        } else {
            log.debug("Bundle "+inferredMetadata+" forcibly removed, but no upgrade bundle supplied"
                    + "; install is no-op");
            result.setIgnoringForciblyRemoved(inferredMetadata.getVersionedName(), Maybe.absent());
            return;
        }
    }

    @VisibleForTesting
    boolean isBlacklistedForPersistence(ManagedBundle managedBundle) {
        // We treat as "managed bundles" (to extract their catalog.bom) the contents of:
        //   - org.apache.brooklyn.core
        //   - org.apache.brooklyn.policy
        //   - org.apache.brooklyn.test-framework
        //   - org.apache.brooklyn.software-*
        //   - org.apache.brooklyn.library-catalog
        //   - org.apache.brooklyn.karaf-init (not sure why this one could end up in persisted state!)
        // We don't want to persist the entire brooklyn distro! Therefore default is to blacklist those.
        
        if (blacklistBundlePersistencePredicate == null) {
            String whitelistRegex = mgmt().getConfig().getConfig(PERSIST_MANAGED_BUNDLE_WHITELIST_REGEX);
            String blacklistRegex = mgmt().getConfig().getConfig(PERSIST_MANAGED_BUNDLE_BLACKLIST_REGEX);
            
            final Pattern whitelistPattern = (whitelistRegex != null) ? Pattern.compile(whitelistRegex) : null;
            final Pattern blacklistPattern = (blacklistRegex != null) ? Pattern.compile(blacklistRegex) : null;

            blacklistBundlePersistencePredicate = input -> {
                    String bundleName = input.getSymbolicName();
                    if (whitelistPattern != null && whitelistPattern.matcher(bundleName).matches()) {
                        return false;
                    }
                    if (blacklistPattern != null && blacklistPattern.matcher(bundleName).matches()) {
                        return true;
                    }
                    return false;
            };
        }
        
        return blacklistBundlePersistencePredicate.test(managedBundle);
    }
    
    private static List<Bundle> findBundlesBySymbolicNameAndVersion(OsgiManager osgiManager, ManagedBundle desired) {
        return Osgis.bundleFinder(osgiManager.getFramework())
                .symbolicName(desired.getSymbolicName())
                .version(desired.getOsgiVersionString())
                .findAll();
    }
    
    private static boolean checksumsMatch(ManagedBundle actual, ManagedBundle desired) {
        return actual.getChecksum() != null && Objects.equal(actual.getChecksum(), desired.getChecksum());
    }
    
    private static Maybe<Bundle> tryFindSameOsgiUrlOrSameContentsBundle(Iterable<? extends Bundle> bundles, ManagedBundle desired, File zipFile) {
        for (Bundle bundle : bundles) {
            if (isBundleSameOsgiUrlOrSameContents(bundle, desired, zipFile)) {
                return Maybe.of(bundle);
            }
        }
        
        return Maybe.absent();
    }
    
    private static boolean isBundleSameOsgiUrlOrSameContents(Bundle bundle, ManagedBundle desired, File zipFile) {
        // Would be nice to also use `desired.getChecksum()`, but not clear if we can get
        // MD5 checksum from an installed OSGi bundle.
        
        if (Objects.equal(bundle.getLocation(), desired.getUrl())) {
            // installation request was for identical location, so assume we are simply bringing under mgmt
            log.debug("Request to install "+desired+" from same location "+bundle.getLocation()+
                " as existing OSGi installed (but not Brooklyn-managed) bundle "+bundle+", so skipping reinstall");
            return true;
        } else {
            // different locations, but see if we can compare input stream contents
            // (prevents needless uninstall/reinstall of already installed bundles)
            try {
                if (Streams.compare(new FileInputStream(zipFile), new URL(bundle.getLocation()).openStream())) {
                    log.debug("Request to install "+desired+" has same contents"+
                        " as existing OSGi installed (but not Brooklyn-managed) bundle "+bundle+", so skipping reinstall");
                    return true;
                } else {
                    log.debug("Request to install "+desired+" has different contents"+
                        " as existing OSGi installed (but not Brooklyn-managed) bundle "+bundle+", so will do reinstall (if no other equivalents found)");
                    return false;
                }
            } catch (Exception e) {
                Exceptions.propagateIfFatal(e);
                // probably an invalid URL on installed bundle; that's allowed
                log.debug("Request to install "+desired+" could not compare contents"+
                    " with existing OSGi installed (but not Brooklyn-managed) bundle "+bundle+", so will do reinstall if not other equivalents found (error "+e+" loading from "+bundle.getLocation()+")");
                return false;
            }
        }
    }
    
    private static String getChecksum(ZipFile zf) {
        // checksum should ignore time/date stamps on files - just look at entries and contents. also ignore order.
        // (tests fail without time/date is one reason, but really if a person rebuilds a ZIP that is the same 
        // files we should treat it as identical)
        try {
            Map<String,String> entriesToChecksum = MutableMap.of();
            for (ZipEntry ze: Collections.list(zf.entries())) {
                entriesToChecksum.put(ze.getName(), Streams.getMd5Checksum(zf.getInputStream(ze)));
            }
            return Streams.getMd5Checksum(Streams.newInputStreamWithContents(new TreeMap<>(entriesToChecksum).toString()));
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }

    private boolean canUpdate() {
        // only update if forced, or it's a snapshot for which a byte stream is supplied
        // (IE don't update a snapshot verison every time its URL is referenced in a 'libraries' section)
        return force || (VersionComparator.isSnapshot(inferredMetadata.getSuppliedVersionString()) && inputStreamSupplied);
    }

    /** true if the supplied name and version are complete; updates if the known data is incomplete;
     * throws if there is a mismatch; false if the supplied data is incomplete */
    private boolean matchSetOrFail(String source, String name, String version) {
        boolean suppliedIsComplete = true;
        if (Strings.isBlank(name)) {
            suppliedIsComplete = false;
        } else if (Strings.isBlank(inferredMetadata.getSymbolicName())) {
            ((BasicManagedBundle)inferredMetadata).setSymbolicName(name);
        } else if (!Objects.equal(inferredMetadata.getSymbolicName(), name)){
            // if bundle name is inferred from ID, it is allowed to be overridden by manifest, but with a warning, for legacy and non-bundle BOM compatibility.
            // if bundle name is set using 'bundle' it must match (previous behaviour).
            boolean inferredMetadataCanBeOverridden = false;
            try {
                Map<?, ?> catalogMetadata = BasicBrooklynCatalog.getCatalogMetadata(bomText);
                if (!catalogMetadata.containsKey("bundle") && catalogMetadata.containsKey("id")) {
                    inferredMetadataCanBeOverridden = true;
                    log.warn("Installing bundle '" + inferredMetadata + "' from " + source + ", even though 'id' in its catalog BOM is different ('" + name + "', v '"+version+"'); strongly recommended that the BOM 'id' match the bundle symbolic name");
                }
            } catch (Exception e) {
                Exceptions.propagateIfFatal(e);
            }

            if (!inferredMetadataCanBeOverridden) {
                throw new IllegalArgumentException("Symbolic name mismatch '" + name + "' from " + source + " (expected '" + inferredMetadata.getSymbolicName() + "')");
            }
        }
        
        if (Strings.isBlank(version)) {
            suppliedIsComplete = false;
        } else if (Strings.isBlank(inferredMetadata.getSuppliedVersionString())) {
            ((BasicManagedBundle)inferredMetadata).setVersion(version);
        } else if (!BrooklynVersionSyntax.equalAsOsgiVersions(inferredMetadata.getSuppliedVersionString(), version)) {
            boolean inferredMetadataCanBeOverridden = false;
            try {
                if (BasicBrooklynCatalog.NO_VERSION.equals(version)) {
                    inferredMetadataCanBeOverridden = true;
                } else {
                    Map<?, ?> catalogMetadata = BasicBrooklynCatalog.getCatalogMetadata(bomText);
                    if (!catalogMetadata.containsKey("bundle") && catalogMetadata.containsKey("id")) {
                        inferredMetadataCanBeOverridden = true;
                        log.warn("Installing bundle '" + inferredMetadata + "' from " + source + ", even though 'version' in its catalog BOM is different ('" + name + "', v '" + version + "'); strongly recommended that the BOM 'version' match the bundle version");
                    }
                }
            } catch (Exception e) {
                Exceptions.propagateIfFatal(e);
            }

            if (!inferredMetadataCanBeOverridden) {
                throw new IllegalArgumentException("Bundle version mismatch '" + version + "' from " + source + " (expected '" + inferredMetadata.getSuppliedVersionString() + "')");
            }
        }
        
        return suppliedIsComplete;
    }

}
