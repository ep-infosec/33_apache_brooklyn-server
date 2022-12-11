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

import java.io.InputStream;
import java.util.Collection;
import java.util.Map;

import java.util.function.Supplier;
import org.apache.brooklyn.api.catalog.CatalogItem;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.mgmt.rebind.RebindContext;
import org.apache.brooklyn.api.mgmt.rebind.RebindExceptionHandler;
import org.apache.brooklyn.api.mgmt.rebind.mementos.BrooklynMementoPersister.LookupContext;
import org.apache.brooklyn.api.objs.BrooklynObject;
import org.apache.brooklyn.api.policy.Policy;
import org.apache.brooklyn.api.sensor.Enricher;
import org.apache.brooklyn.api.sensor.Feed;
import org.apache.brooklyn.api.typereg.ManagedBundle;
import org.apache.brooklyn.core.mgmt.ha.OsgiBundleInstallationResult;
import org.apache.brooklyn.core.mgmt.internal.ManagementContextInternal;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.osgi.VersionedName;
import org.osgi.framework.BundleException;

import com.google.common.collect.Maps;

public class RebindContextImpl implements RebindContext {

    private final Map<String, Entity> entities = Maps.newLinkedHashMap();
    private final Map<String, Location> locations = Maps.newLinkedHashMap();
    private final Map<String, Policy> policies = Maps.newLinkedHashMap();
    private final Map<String, Enricher> enrichers = Maps.newLinkedHashMap();
    private final Map<String, Feed> feeds = Maps.newLinkedHashMap();
    private final Map<String, CatalogItem<?, ?>> catalogItems = Maps.newLinkedHashMap();
    private final Map<String, ManagedBundle> bundles = Maps.newLinkedHashMap();
    
    private final ClassLoader classLoader;
    private final ManagementContext mgmt;
    private final RebindExceptionHandler exceptionHandler;
    private final LookupContext lookupContext;
    
    private boolean allAreReadOnly = false;
    
    public RebindContextImpl(ManagementContext mgmt, RebindExceptionHandler exceptionHandler, ClassLoader classLoader) {
        this.mgmt = checkNotNull(mgmt, "mgmt");
        this.exceptionHandler = checkNotNull(exceptionHandler, "exceptionHandler");
        this.classLoader = checkNotNull(classLoader, "classLoader");
        this.lookupContext = new RebindContextLookupContext("root-rebind-context", mgmt, this, exceptionHandler);
    }

    public void registerEntity(String id, Entity entity) {
        entities.put(id, entity);
    }
    
    public void registerLocation(String id, Location location) {
        locations.put(id, location);
    }
    
    public void registerPolicy(String id, Policy policy) {
        policies.put(id, policy);
    }
    
    public void registerEnricher(String id, Enricher enricher) {
        enrichers.put(id, enricher);
    }
    
    public void registerFeed(String id, Feed feed) {
        feeds.put(id, feed);
    }
    
    public void registerCatalogItem(String id, CatalogItem<?, ?> catalogItem) {
        catalogItems.put(id, catalogItem);
    }

    public void registerBundle(String versionedName, ManagedBundle bundle) {
        bundles.put(versionedName, bundle);
    }

    /** install the bundles into brooklyn and osgi, but do not start nor validate;
     * caller (rebind) will do that manually, doing each step across all bundles before proceeding 
     * to prevent reference errors */
    public OsgiBundleInstallationResult installBundle(ManagedBundle bundle, Supplier<InputStream> zipInput) {
        return ((ManagementContextInternal)mgmt).getOsgiManager().get().installDeferredStart(bundle, zipInput, false).get();
    }
    public void startBundle(OsgiBundleInstallationResult br) throws BundleException {
        if (br.getDeferredStart()!=null) {
            br.getDeferredStart().run();
        }
    }

    
    public void unregisterPolicy(Policy policy) {
        policies.remove(policy.getId());
    }

    public void unregisterEnricher(Enricher enricher) {
        enrichers.remove(enricher.getId());
    }

    public void unregisterFeed(Feed feed) {
        feeds.remove(feed.getId());
    }

    public void unregisterCatalogItem(CatalogItem<?,?> item) {
        catalogItems.remove(item.getId());
    }

    public void clearCatalogItems() {
        catalogItems.clear();
    }
    
    public Entity getEntity(String id) {
        return entities.get(id);
    }

    public Location getLocation(String id) {
        return locations.get(id);
    }
    
    public Policy getPolicy(String id) {
        return policies.get(id);
    }
    
    public Enricher getEnricher(String id) {
        return enrichers.get(id);
    }

    public Feed getFeed(String id) {
        return feeds.get(id);
    }
    
    public CatalogItem<?, ?> getCatalogItem(String id) {
        return catalogItems.get(id);
    }

    public ManagedBundle getBundle(String id) {
        return bundles.get(id);
    }
    
    @Override
    public Class<?> loadClass(String className) throws ClassNotFoundException {
        return classLoader.loadClass(className);
    }

    @Override
    public RebindExceptionHandler getExceptionHandler() {
        return exceptionHandler;
    }

    public Collection<Location> getLocations() {
        return locations.values();
    }
    
    public Collection<Entity> getEntities() {
        return entities.values();
    }
    
    public Collection<Policy> getPolicies() {
        return policies.values();
    }

    public Collection<Enricher> getEnrichers() {
        return enrichers.values();
    }
    
    public Collection<Feed> getFeeds() {
        return feeds.values();
    }

    public Collection<CatalogItem<?, ?>> getCatalogItems() {
        return catalogItems.values();
    }
    
    public Collection<ManagedBundle> getBundles() {
        return bundles.values();
    }

    @Override
    public Map<String,BrooklynObject> getAllBrooklynObjects() {
        MutableMap<String,BrooklynObject> result = MutableMap.of();
        result.putAll(locations);
        result.putAll(entities);
        result.putAll(policies);
        result.putAll(enrichers);
        result.putAll(feeds);
        result.putAll(catalogItems);
        result.putAll(bundles);
        return result.asUnmodifiable();
    }

    public void setAllReadOnly() {
        allAreReadOnly = true;
    }
    
    @Override
    public boolean isReadOnly(BrooklynObject item) {
        return allAreReadOnly;
    }

    @Override
    public LookupContext lookup() {
        return lookupContext;
    }

}
