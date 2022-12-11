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
package org.apache.brooklyn.util.core.osgi;

import com.google.common.annotations.Beta;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import org.apache.brooklyn.api.catalog.CatalogItem.CatalogBundle;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.net.Urls;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.osgi.OsgiUtils;
import org.apache.brooklyn.util.osgi.VersionedName;
import org.apache.brooklyn.util.stream.Streams;
import org.apache.brooklyn.util.text.BrooklynVersionSyntax;
import org.apache.brooklyn.util.text.NaturalOrderComparator;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.text.VersionComparator;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;
import org.osgi.framework.VersionRange;
import org.osgi.framework.launch.Framework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 
 * utilities for working with osgi.
 * osgi support is in early days (June 2014) so this class is beta, subject to change,
 * particularly in how framework is started and bundles installed.
 * 
 * @since 0.7.0  */
@Beta
public class Osgis {
    private static final Logger LOG = LoggerFactory.getLogger(Osgis.class);

    public static class BundleFinder {
        protected final Framework framework;
        protected String symbolicName;
        protected String version;
        protected String url;
        protected boolean urlMandatory = false;
        protected final List<Predicate<? super Bundle>> predicates = MutableList.of();
        
        protected BundleFinder(Framework framework) {
            this.framework = framework;
        }

        public BundleFinder symbolicName(String symbolicName) {
            this.symbolicName = symbolicName;
            return this;
        }

        /** Accepts non-osgi version syntax, converting to OSGi version syntax */
        public BundleFinder version(String version) {
            this.version = version;
            return this;
        }
        
        public BundleFinder id(String symbolicNameOptionallyWithVersion) {
            if (Strings.isBlank(symbolicNameOptionallyWithVersion))
                return this;
            
            Maybe<VersionedName> nv = VersionedName.parseMaybe(symbolicNameOptionallyWithVersion, false);
            return id(nv.get());
        }

        private BundleFinder id(VersionedName nv) {
            symbolicName(nv.getSymbolicName());
            if (nv.getVersionString() != null) {
                version(nv.getVersionString());
            }
            return this;
        }

        public BundleFinder bundle(CatalogBundle bundle) {
            if (bundle.isNameResolved()) {
                symbolicName(bundle.getSymbolicName());
                version(bundle.getSuppliedVersionString());
            }
            if (bundle.getUrl() != null) {
                requiringFromUrl(bundle.getUrl());
            }
            return this;
        }

        /** Looks for a bundle matching the given URL;
         * unlike {@link #requiringFromUrl(String)} however, if the URL does not match any bundles
         * it will return other matching bundles <i>if</if> a {@link #symbolicName(String)} is specified.
         */
        public BundleFinder preferringFromUrl(String url) {
            this.url = url;
            urlMandatory = false;
            return this;
        }

        /** Requires the bundle to have the given URL set as its location. */
        public BundleFinder requiringFromUrl(String url) {
            this.url = url;
            urlMandatory = true;
            return this;
        }

        /** Finds the best matching bundle. */
        public Maybe<Bundle> find() {
            return findOne(false);
        }
        
        /** Finds the matching bundle, requiring it to be unique. */
        public Maybe<Bundle> findUnique() {
            return findOne(true);
        }

        protected Maybe<Bundle> findOne(boolean requireExactlyOne) {
            if (symbolicName==null && url==null)
                throw new IllegalStateException(this+" must be given either a symbolic name or a URL");
            
            List<Bundle> result = findAll();
            if (result.isEmpty())
                return Maybe.absent("No bundle matching "+getConstraintsDescription());
            if (requireExactlyOne && result.size()>1)
                return Maybe.absent("Multiple bundles ("+result.size()+") matching "+getConstraintsDescription());
            
            // take the highest version of the first symbolic name alphabetically
            Bundle r1 = result.get(0);
            for (int i=1; i<result.size(); i++) {
                if (result.get(i).getSymbolicName().equals(r1.getSymbolicName())) {
                    r1 = result.get(i);
                } else {
                    // was in order so no more symbolic names
                    break;
                }
            }
            return Maybe.of(r1);
        }
        
        /** Finds all matching bundles, in decreasing version order. */
        @SuppressWarnings("deprecation")
        public List<Bundle> findAll() {
            boolean urlMatched = false;
            List<Bundle> result = MutableList.of();
            String v=null, vDep = null;
            VersionRange vRange = null;
            if (version!=null) {
                if (isVersionRange(version)) {
                    vRange = VersionRange.valueOf(version);
                } else {
                    v = BrooklynVersionSyntax.toValidOsgiVersion(version);
                    vDep = OsgiUtils.toOsgiVersion(version);
                }
            }
            for (Bundle b: framework.getBundleContext().getBundles()) {
                if (symbolicName!=null && !symbolicName.equals(b.getSymbolicName())) continue;
                if (version!=null) {
                    Version bv = b.getVersion();
                    if (vRange != null) {
                        if (!vRange.includes(bv)) {
                            continue;
                        }
                    } else {
                        String bvString = bv.toString();
                        if (!v.equals(bvString)) {
                            if (!vDep.equals(bvString)) {
                                continue;
                            }
                            LOG.warn("Legacy inferred OSGi version string '"+vDep+"' found to match "+symbolicName+":"+version+"; switch to '"+v+"' format to avoid issues with deprecated version syntax");
                        }
                    }
                }
                if (!Predicates.and(predicates).apply(b)) continue;

                // check url last, because if it isn't mandatory we should only clear if we find a url
                // for which the other items also match
                if (url!=null) {
                    boolean matches = url.equals(b.getLocation());
                    if (urlMandatory) {
                        if (!matches) continue;
                        else urlMatched = true;
                    } else {
                        if (matches) {
                            if (!urlMatched) {
                                result.clear();
                                urlMatched = true;
                            }
                        } else {
                            if (urlMatched) {
                                // can't use this bundle as we have previously found a preferred bundle, with a matching url
                                continue;
                            }
                        }
                    }
                }
                                
                result.add(b);
            }
            
            if (symbolicName==null && url!=null && !urlMatched) {
                // if we only "preferred" the url, and we did not match it, and we did not have a symbolic name,
                // then clear the results list!
                result.clear();
            }

            Collections.sort(result, new Comparator<Bundle>() {
                @Override
                public int compare(Bundle o1, Bundle o2) {
                    int r = NaturalOrderComparator.INSTANCE.compare(o1.getSymbolicName(), o2.getSymbolicName());
                    if (r!=0) return r;
                    return VersionComparator.INSTANCE.compare(o1.getVersion().toString(), o2.getVersion().toString());
                }
            });
            
            return result;
        }
        
        public String getConstraintsDescription() {
            List<String> parts = MutableList.of();
            if (symbolicName!=null) parts.add("symbolicName="+symbolicName);
            if (version!=null) parts.add("version="+version);
            if (url!=null)
                parts.add("url["+(urlMandatory ? "required" : "preferred")+"]="+url);
            if (!predicates.isEmpty())
                parts.add("predicates="+predicates);
            return Joiner.on(";").join(parts);
        }
        
        @Override
        public String toString() {
            return getClass().getCanonicalName()+"["+getConstraintsDescription()+"]";
        }

        public BundleFinder version(final Predicate<Version> versionPredicate) {
            return satisfying(new Predicate<Bundle>() {
                @Override
                public boolean apply(Bundle input) {
                    return versionPredicate.apply(input.getVersion());
                }
            });
        }
        
        public BundleFinder satisfying(Predicate<? super Bundle> predicate) {
            predicates.add(predicate);
            return this;
        }
        
        private boolean isVersionRange(String version) {
            return (version != null) && (version.length() > 2) 
                    && (version.charAt(0) == VersionRange.LEFT_OPEN || version.charAt(0) == VersionRange.LEFT_CLOSED)
                    && (version.charAt(version.length()-1) == VersionRange.RIGHT_OPEN || version.charAt(version.length()-1) == VersionRange.RIGHT_CLOSED);
        }
    }
    
    public static BundleFinder bundleFinder(Framework framework) {
        return new BundleFinder(framework);
    }

    /** 
     * Provides an OSGI framework.
     *
     * When running inside an OSGi container, the container framework is returned.
     * When running standalone a new Apache Felix container is created.
     * 
     * @param felixCacheDir
     * @param clean
     * @return
     * @todo Use felixCacheDir ?
     */
    public static Framework getFramework(String felixCacheDir, boolean clean) {
        return SystemFrameworkLoader.get().getFramework(felixCacheDir, clean);
    }

    /**
     * Stops/ungets the OSGi framework.
     *
     * See {@link #getFramework(java.lang.String, boolean)}
     *
     * @param framework
     */
    public static void ungetFramework(Framework framework) {
        SystemFrameworkLoader.get().ungetFramework(framework);
    }

    /**
     * Installs a bundle from the given URL, doing a check if already installed, and
     * using the {@link ResourceUtils} loader for this project (brooklyn core)
     */
    public static Bundle install(Framework framework, String url) throws BundleException {
        boolean isLocal = isLocalUrl(url);
        String localUrl = url;
        if (!isLocal) {
            localUrl = cacheFile(url);
        }

        try {
            Bundle bundle = getInstalledBundle(framework, localUrl);
            if (bundle != null) {
                return bundle;
            }
    
            // use our URL resolution so we get classpath items
            LOG.debug("Installing bundle into {} from url: {}", framework, url);
            InputStream stream = getUrlStream(localUrl);
            Bundle installedBundle = framework.getBundleContext().installBundle(url, stream);
            
            return installedBundle;
        } finally {
            if (!isLocal) {
                try {
                    new File(new URI(localUrl)).delete();
                } catch (URISyntaxException e) {
                    throw Exceptions.propagate(e);
                }
            }
        }
    }

    private static String cacheFile(String url) {
        InputStream in = getUrlStream(url);
        File cache = Os.writeToTempFile(in, "bundle-cache", "jar");
        return cache.toURI().toString();
    }

    private static boolean isLocalUrl(String url) {
        String protocol = Urls.getProtocol(url);
        return "file".equals(protocol) ||
                "classpath".equals(protocol) ||
                "jar".equals(protocol);
    }

    private static Bundle getInstalledBundle(Framework framework, String url) {
        Bundle bundle = framework.getBundleContext().getBundle(url);
        if (bundle != null) {
            return bundle;
        }

        // We now support same version installed multiple times (avail since OSGi 4.3+).
        // However we do not support overriding *system* bundles, ie anything already on the classpath.
        // If we wanted to disable multiple versions, see comments below, and reference to FRAMEWORK_BSNVERSION_MULTIPLE above.
        
        // Felix already assumes the stream is pointing to a JAR
        JarInputStream stream;
        try {
            stream = new JarInputStream(getUrlStream(url));
        } catch (IOException e) {
            throw Exceptions.propagate(e);
        }
        Manifest manifest = stream.getManifest();
        Streams.closeQuietly(stream);
        if (manifest == null) {
            throw new IllegalStateException("Missing manifest file in bundle or not a jar file.");
        }
        String versionedId = OsgiUtils.getVersionedId(manifest);
        for (Bundle installedBundle : framework.getBundleContext().getBundles()) {
            if (versionedId.equals(OsgiUtils.getVersionedId(installedBundle))) {
                if (SystemFrameworkLoader.get().isSystemBundle(installedBundle)) {
                    LOG.debug("Already have system bundle "+versionedId+" from "+installedBundle+"/"+installedBundle.getLocation()+" when requested "+url+"; not installing");
                    // "System bundles" (ie things on the classpath) cannot be overridden
                    return installedBundle;
                } else {
                    LOG.debug("Already have bundle "+versionedId+" from "+installedBundle+"/"+installedBundle.getLocation()+" when requested "+url+"; but it is not a system bundle so proceeding");
                    // Other bundles can be installed multiple times. To ignore multiples and continue to use the old one, 
                    // just return the installedBundle as done just above for system bundles.
                }
            }
        }
        return null;
    }

    private static InputStream getUrlStream(String url) {
        return ResourceUtils.create(Osgis.class).getResourceFromUrl(url);
    }

    @Beta
    public static Optional<Bundle> getBundleOf(Class<?> clazz) {
        Bundle bundle = org.osgi.framework.FrameworkUtil.getBundle(clazz);
        return Optional.fromNullable(bundle);
    }

    public static ManagementContext getManagementContext() {
        Bundle bundle = Osgis.getBundleOf(Osgis.class).orNull();
        if (bundle == null) return null;
        ServiceReference<ManagementContext> svc = bundle.getBundleContext().getServiceReference(ManagementContext.class);
        if (svc == null) return null;
        return bundle.getBundleContext().getService(svc);
    }

}
