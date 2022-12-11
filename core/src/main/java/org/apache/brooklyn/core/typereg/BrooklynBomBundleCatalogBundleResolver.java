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
package org.apache.brooklyn.core.typereg;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.function.Supplier;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.core.mgmt.ha.BrooklynBomOsgiArchiveInstaller;
import org.apache.brooklyn.core.mgmt.ha.OsgiBundleInstallationResult;
import org.apache.brooklyn.core.mgmt.internal.ManagementContextInternal;
import org.apache.brooklyn.util.exceptions.ReferenceWithError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BrooklynBomBundleCatalogBundleResolver extends AbstractCatalogBundleResolver {

    private static Logger LOG = LoggerFactory.getLogger(BrooklynBomBundleCatalogBundleResolver.class);

    public static final String FORMAT = "brooklyn-bom-bundle";

    public BrooklynBomBundleCatalogBundleResolver() {
        super(FORMAT, "Brooklyn catalog.bom ZIP", "ZIP with a catalog.bom and/or an OSGi manifest " +
                "(if just an OSGi manifest, types will not be added but the bundle will be installed)");
    }

    @Override
    public BrooklynBomBundleCatalogBundleResolver withManagementContext(ManagementContext mgmt) {
        return (BrooklynBomBundleCatalogBundleResolver) super.withManagementContext(mgmt);
    }

    @Override
    protected double scoreForNullFormat(Supplier<InputStream> f) {
        try (FileTypeDetector detector = new FileTypeDetector(f)) {
            if (detector.isZip()) {
                if (detector.zipFileMatchesGlob("catalog.bom").size() > 0) return 1.0;
                    // add as a plain-old-zip
                else return 0.4;
            }
            // so we get error messages from this
            return 0.01;
        }
    }

    @Override
    public ReferenceWithError<OsgiBundleInstallationResult> install(Supplier<InputStream> input, BundleInstallationOptions options) {
        LOG.debug("Installing bundle from stream - known details: "+(options==null ? null : options.getKnownBundleMetadata()));

        BrooklynBomOsgiArchiveInstaller installer = new BrooklynBomOsgiArchiveInstaller(
                ((ManagementContextInternal)mgmt).getOsgiManager().get(),
                (options==null ? null : options.getKnownBundleMetadata()), input.get());
        installer.setCatalogBomText(FORMAT, null);
        if (options!=null) {
            installer.setStart(options.isStart());
            installer.setLoadCatalogBom(options.isLoadCatalogBom());
            installer.setForce(options.isForceUpdateOfNonSnapshots());
            installer.setDeferredStart(options.isDeferredStart());
            installer.setValidateTypes(options.isValidateTypes());
        }

        return installer.install();
    }

}
