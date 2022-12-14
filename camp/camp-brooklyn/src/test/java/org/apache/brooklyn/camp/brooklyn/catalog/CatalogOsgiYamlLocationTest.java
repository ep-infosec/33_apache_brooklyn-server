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
package org.apache.brooklyn.camp.brooklyn.catalog;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

import java.util.Collection;
import java.util.List;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.LocationDefinition;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.typereg.OsgiBundleWithUrl;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.camp.brooklyn.AbstractYamlTest;
import org.apache.brooklyn.core.config.BasicConfigKey;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.mgmt.osgi.OsgiStandaloneTest;
import org.apache.brooklyn.core.typereg.RegisteredTypePredicates;
import org.apache.brooklyn.core.typereg.RegisteredTypes;
import org.apache.brooklyn.test.support.TestResourceUnavailableException;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.text.StringFunctions;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

public class CatalogOsgiYamlLocationTest extends AbstractYamlTest {
    private static final String SIMPLE_LOCATION_TYPE = "org.apache.brooklyn.test.osgi.entities.SimpleLocation";

    @Override
    protected boolean disableOsgi() {
        return false;
    }

    @AfterMethod(alwaysRun=true)
    @Override
    public void tearDown() throws Exception {
        try {
            for (RegisteredType ci : mgmt().getTypeRegistry().getMatching(RegisteredTypePredicates.IS_LOCATION)) {
                mgmt().getCatalog().deleteCatalogItem(ci.getSymbolicName(), ci.getVersion());
            }
        } finally {
            super.tearDown();
        }
    }
    
    @Test
    public void testAddCatalogItemOsgi() throws Exception {
        assertEquals(countCatalogLocations(), 0);

        String symbolicName = "my.catalog.location.id.load";
        addCatalogLocation(symbolicName, SIMPLE_LOCATION_TYPE, getOsgiLibraries());
        assertAdded(symbolicName, SIMPLE_LOCATION_TYPE);
        assertOsgi(symbolicName);
        removeAndAssert(symbolicName);
        
        // and do it again; the OSGi registry doesn't add it;
        // it only works because the location registry initializes itself on first call
        // by reading from the catalog
        symbolicName = "my.catalog.location.id.load.2";
        addCatalogLocation(symbolicName, SIMPLE_LOCATION_TYPE, getOsgiLibraries());
        assertAdded(symbolicName, SIMPLE_LOCATION_TYPE);
        assertOsgi(symbolicName);
    }

    @Test
    public void testAddCatalogItemOsgiLegacySyntax() throws Exception {
        assertEquals(countCatalogLocations(), 0);

        String symbolicName = "my.catalog.location.id.load";
        addCatalogLocationLegacySyntax(symbolicName, SIMPLE_LOCATION_TYPE, getOsgiLibraries());
        assertAdded(symbolicName, SIMPLE_LOCATION_TYPE);
        assertOsgi(symbolicName);
        removeAndAssert(symbolicName);
    }

    private void assertOsgi(String symbolicName) {
        RegisteredType item = mgmt().getTypeRegistry().get(symbolicName, TEST_VERSION);
        Collection<OsgiBundleWithUrl> libs = item.getLibraries();
        assertEquals(libs.size(), 2);
        assertEquals(MutableList.copyOf(libs).get(1).getUrl(), Iterables.getOnlyElement(getOsgiLibraries()));
    }

    private void assertAdded(String symbolicName, String expectedJavaType) {
        RegisteredType item = mgmt().getTypeRegistry().get(symbolicName, TEST_VERSION);
        assertEquals(item.getSymbolicName(), symbolicName);
        Assert.assertTrue(RegisteredTypes.isSubtypeOf(item, Location.class), "Expected Location, not "+item.getSuperTypes());
        assertEquals(countCatalogLocations(), 1);

        // Item added to catalog should automatically be available in location registry
        LocationDefinition def = mgmt().getLocationRegistry().getDefinedLocationByName(symbolicName);
        assertEquals(def.getId(), symbolicName+":"+TEST_VERSION);
        assertEquals(def.getName(), symbolicName);
        
        LocationSpec<?> spec = mgmt().getTypeRegistry().createSpec(item, null, LocationSpec.class);
        assertEquals(spec.getType().getName(), expectedJavaType);
    }
    
    private void removeAndAssert(String symbolicName) {
        // Deleting item: should be gone from catalog, and from location registry
        deleteCatalogRegisteredType(symbolicName);

        assertEquals(countCatalogLocations(), 0);
        assertNull(mgmt().getLocationRegistry().getDefinedLocationByName(symbolicName));
    }

    @Test
    public void testLaunchApplicationReferencingOsgiLocation() throws Exception {
        String symbolicName = "my.catalog.location.id.launch";
        addCatalogLocation(symbolicName, SIMPLE_LOCATION_TYPE, getOsgiLibraries());
        runLaunchApplicationReferencingLocation(symbolicName, SIMPLE_LOCATION_TYPE);
        
        deleteCatalogRegisteredType(symbolicName);
    }
    
    protected void runLaunchApplicationReferencingLocation(String locTypeInYaml, String locType) throws Exception {
        Entity app = createAndStartApplication(
            "name: simple-app-yaml",
            "location: ",
            "  "+locTypeInYaml+":",
            "    config2: config2 override",
            "    config3: config3",
            "services: ",
            "  - type: org.apache.brooklyn.entity.stock.BasicStartable");

        Entity simpleEntity = Iterables.getOnlyElement(app.getChildren());
        Location location = Iterables.getOnlyElement(Entities.getAllInheritedLocations(simpleEntity));
        assertEquals(location.getClass().getName(), locType);
        assertEquals(location.getConfig(new BasicConfigKey<String>(String.class, "config1")), "config1");
        assertEquals(location.getConfig(new BasicConfigKey<String>(String.class, "config2")), "config2 override");
        assertEquals(location.getConfig(new BasicConfigKey<String>(String.class, "config3")), "config3");
    }

    private List<String> getOsgiLibraries() {
        TestResourceUnavailableException.throwIfResourceUnavailable(getClass(), OsgiStandaloneTest.BROOKLYN_TEST_OSGI_ENTITIES_PATH);
        return ImmutableList.of(OsgiStandaloneTest.BROOKLYN_TEST_OSGI_ENTITIES_URL);
    }
    
    private void addCatalogLocation(String symbolicName, String locationType, List<String> libraries) {
        ImmutableList.Builder<String> yaml = ImmutableList.<String>builder().add(
                "brooklyn.catalog:",
                "  id: " + symbolicName,
                "  version: " + TEST_VERSION,
                "  itemType: location",
                "  name: My Catalog Location",
                "  description: My description");
        if (libraries!=null && libraries.size() > 0) {
            yaml.add("  libraries:")
                .addAll(Lists.transform(libraries, StringFunctions.prepend("  - url: ")));
        }
        yaml.add(
                "  item:",
                "    type: " + locationType,
                "    brooklyn.config:",
                "      config1: config1",
                "      config2: config2");
        
        
        addCatalogItems(yaml.build());
    }

    private void addCatalogLocationLegacySyntax(String symbolicName, String locationType, List<String> libraries) {
        ImmutableList.Builder<String> yaml = ImmutableList.<String>builder().add(
                "brooklyn.catalog:",
                "  id: " + symbolicName,
                "  name: My Catalog Location",
                "  description: My description",
                "  version: " + TEST_VERSION);
        if (libraries!=null && libraries.size() > 0) {
            yaml.add("  libraries:")
                .addAll(Lists.transform(libraries, StringFunctions.prepend("  - url: ")));
        }
        yaml.add(
                "",
                "brooklyn.locations:",
                "- type: " + locationType,
                "  brooklyn.config:",
                "    config1: config1",
                "    config2: config2");
        
        
        addCatalogItems(yaml.build());
    }
}
