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
package org.apache.brooklyn.core.resolve.entity;

import java.util.Set;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.mgmt.classloading.BrooklynClassLoadingContext;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.core.mgmt.BrooklynTags;
import org.apache.brooklyn.core.typereg.BundleUpgradeParser.CatalogUpgrades;
import org.apache.brooklyn.core.typereg.RegisteredTypeLoadingContexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;

public class CatalogEntitySpecResolver extends AbstractEntitySpecResolver {
    private static final Logger log = LoggerFactory.getLogger(CatalogEntitySpecResolver.class);

    private static final String RESOLVER_NAME = "catalog";

    public CatalogEntitySpecResolver() {
        super(RESOLVER_NAME);
    }

    // in 0.9.0 we've changed this *not* to perform
    // symbolicName = DeserializingClassRenamesProvider.findMappedName(symbolicName);
    // in belief that this should only apply to *java* loads
    
    // *type* renames are achieved through catalog upgrades, as follows if initial load fails

    private RegisteredType loadUpgrade(String type) {
        String upgradedType = CatalogUpgrades.getTypeUpgradedIfNecessary(mgmt, type);
        if (!Objects.equal(type, upgradedType)) {
            if (log.isTraceEnabled()) {
                // would be useful to give more context info but it's not available here
                log.trace("Using "+upgradedType+" in request for "+type);
            }
            return mgmt.getTypeRegistry().get(upgradedType);
        }
        return null;
    }

    @Override
    protected boolean canResolve(String type, BrooklynClassLoadingContext loader) {
        String localType = getLocalType(type);
        RegisteredType item = mgmt.getTypeRegistry().get(localType, RegisteredTypeLoadingContexts.loader(loader));
        if (item==null) {
            item = loadUpgrade(localType);
        }
        if (item==null) {
            return false;
        }
        //Keeps behaviour of previous functionality, but caller might be interested if item is disabled
        if (item.isDisabled()) return false;
        
        return true;
    }

    @Override
    public EntitySpec<?> resolve(String type, BrooklynClassLoadingContext loader, Set<String> parentEncounteredTypes) {
        String localType = getLocalType(type);
        RegisteredType item = mgmt.getTypeRegistry().get(localType, RegisteredTypeLoadingContexts.withLoader(RegisteredTypeLoadingContexts.spec(Entity.class), loader));
        boolean upgradeRequired = item==null;
        
        if (upgradeRequired) {
            item = loadUpgrade(localType);
        }

        if (item == null) return null;
        checkUsable(item);

        //Take the symbolicName part of the catalog item only for recursion detection to prevent
        //cross referencing of different versions. Not interested in non-catalog item types.
        //Prevent catalog items self-referencing even if explicitly different version.
        boolean recursiveCall = parentEncounteredTypes.contains(item.getSymbolicName());
        if (recursiveCall) return null;
        
        EntitySpec<?> result = mgmt.getTypeRegistry().createSpec(item, 
            RegisteredTypeLoadingContexts.alreadyEncountered(parentEncounteredTypes), 
            EntitySpec.class);
        
        // tag is used to warn when spec converted to entity
        if (upgradeRequired) result.tag(BrooklynTags.newUpgradedFromTag(localType));
        
        return result;
    }

    private void checkUsable(RegisteredType item) {
        if (item.isDisabled()) {
            throw new IllegalStateException("Illegal use of disabled catalog item "+item.getSymbolicName()+":"+item.getVersion());
        } else if (item.isDeprecated()) {
            if (org.apache.brooklyn.util.javalang.StackTraceSimplifier.toString(new Throwable()).contains("BasicBrooklynCatalog.validate")) {
                // don't warn when adding to the catalog
            } else {
                log.warn("Use of deprecated catalog item "+item.getSymbolicName()+":"+item.getVersion(), new Throwable("source"));
            }
        }
    }

//    protected CatalogItem<Entity,EntitySpec<?>> getCatalogItem(ManagementContext mgmt, String brooklynType) {
//        brooklynType = DeserializingClassRenamesProvider.findMappedName(brooklynType);
//        return CatalogUtils.getCatalogItemOptionalVersion(mgmt, Entity.class,  brooklynType);
//    }

}
