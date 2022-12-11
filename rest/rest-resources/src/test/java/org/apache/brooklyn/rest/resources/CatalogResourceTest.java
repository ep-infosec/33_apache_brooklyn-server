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
package org.apache.brooklyn.rest.resources;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.*;

import java.awt.Image;
import java.awt.Toolkit;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.objs.BrooklynObject;
import org.apache.brooklyn.api.objs.Configurable;
import org.apache.brooklyn.api.objs.Identifiable;
import org.apache.brooklyn.api.typereg.BrooklynTypeRegistry;
import org.apache.brooklyn.api.typereg.ManagedBundle;
import org.apache.brooklyn.api.typereg.OsgiBundleWithUrl;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.core.catalog.internal.CatalogUtils;
import org.apache.brooklyn.core.entity.EntityPredicates;
import org.apache.brooklyn.core.mgmt.ha.OsgiManager;
import org.apache.brooklyn.core.mgmt.internal.ManagementContextInternal;
import org.apache.brooklyn.core.mgmt.osgi.OsgiStandaloneTest;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.enricher.stock.Aggregator;
import org.apache.brooklyn.policy.autoscaling.AutoScalerPolicy;
import org.apache.brooklyn.rest.domain.CatalogEnricherSummary;
import org.apache.brooklyn.rest.domain.CatalogEntitySummary;
import org.apache.brooklyn.rest.domain.CatalogItemSummary;
import org.apache.brooklyn.rest.domain.CatalogLocationSummary;
import org.apache.brooklyn.rest.domain.CatalogPolicySummary;
import org.apache.brooklyn.rest.testing.BrooklynRestResourceTest;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.test.support.TestResourceUnavailableException;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.core.osgi.BundleMaker;
import org.apache.brooklyn.util.javalang.JavaClassNames;
import org.apache.brooklyn.util.javalang.Reflections;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.osgi.OsgiTestResources;
import org.apache.brooklyn.util.osgi.VersionedName;
import org.apache.brooklyn.util.stream.InputStreamSource;
import org.apache.brooklyn.util.stream.Streams;
import org.apache.brooklyn.util.text.StringPredicates;
import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.http.HttpHeaders;
import org.apache.http.entity.ContentType;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.reporters.Files;

import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.gson.Gson;

@Test( // by using a different suite name we disallow interleaving other tests between the methods of this test class, which wrecks the test fixtures
        suiteName = "CatalogResourceTest")
public class CatalogResourceTest extends BrooklynRestResourceTest {

    private static final Logger log = LoggerFactory.getLogger(CatalogResourceTest.class);
    
    private static String TEST_VERSION = "0.1.2";
    private static String TEST_LASTEST_VERSION = "0.1.3";

    private Collection<ManagedBundle> initialBundles;

    @Override
    protected boolean useLocalScannedCatalog() {
        return true;
    }

    @Override
    protected void initClass() throws Exception {
        super.initClass();
        // cache initially installed bundles
        OsgiManager osgi = ((ManagementContextInternal)getManagementContext()).getOsgiManager().get();
        initialBundles = osgi.getManagedBundles().values();
    }
    
    protected void initMethod() throws Exception {
        super.initMethod();
        
        // and reset OSGi container
        OsgiManager osgi = ((ManagementContextInternal)getManagementContext()).getOsgiManager().get();
        for (ManagedBundle b: osgi.getManagedBundles().values()) {
            if (!initialBundles.contains(b)) {
                osgi.uninstallUploadedBundle(b);
            }
        }
    }
    
    @Test
    /** based on CampYamlLiteTest */
    public void testRegisterCustomEntityTopLevelSyntaxWithBundleWhereEntityIsFromCoreAndIconFromBundle() {
        TestResourceUnavailableException.throwIfResourceUnavailable(getClass(), OsgiStandaloneTest.BROOKLYN_TEST_OSGI_ENTITIES_PATH);

        String itemSymbolicName = "my.catalog.entity.id";
        String bundleUrl = OsgiStandaloneTest.BROOKLYN_TEST_OSGI_ENTITIES_URL;
        VersionedName bundleName = new VersionedName(OsgiStandaloneTest.BROOKLYN_TEST_OSGI_ENTITIES_SYMBOLIC_NAME_FULL, OsgiStandaloneTest.BROOKLYN_TEST_OSGI_ENTITIES_VERSION);
        String yaml = Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  id: " + itemSymbolicName,
                "  version: " + TEST_VERSION,
                "  itemType: entity",
                "  name: My Catalog App",
                "  description: My description",
                "  icon_url: classpath:/org/apache/brooklyn/test/osgi/entities/icon.gif",
                "  libraries:",
                "  - url: " + bundleUrl,
                "  item:",
                "    type: org.apache.brooklyn.core.test.entity.TestEntity");

        Response response = client().path("/catalog")
                .post(yaml);

        assertEquals(response.getStatus(), Response.Status.CREATED.getStatusCode());

        CatalogSummaryAsserts.newInstance(CatalogItemType.ENTITY, itemSymbolicName, TEST_VERSION)
                .planYamlPredicate(StringPredicates.containsLiteral("org.apache.brooklyn.core.test.entity.TestEntity"))
                .name("My Catalog App")
                .description("My description")
                .expectedInterfaces(Reflections.getAllInterfaces(TestEntity.class))
                .iconData((data) -> {assertEquals(data.length, 43); return true;})
                .applyAsserts(() -> client());

        RegisteredTypeAsserts.newInstance(itemSymbolicName, TEST_VERSION)
                .libraryNames(new VersionedName("my.catalog.entity.id", "0.1.2"), bundleName)
                .libraryUrls(null, bundleUrl)
                .iconUrl("classpath:/org/apache/brooklyn/test/osgi/entities/icon.gif")
                .applyAsserts(getManagementContext().getTypeRegistry());
    }

    @Test
    public void testRegisterOsgiPolicyTopLevelSyntax() {
        TestResourceUnavailableException.throwIfResourceUnavailable(getClass(), OsgiStandaloneTest.BROOKLYN_TEST_OSGI_ENTITIES_PATH);

        String symbolicName = "my.catalog.entity.id."+JavaClassNames.niceClassAndMethod();
        String policyType = "org.apache.brooklyn.test.osgi.entities.SimplePolicy";
        String bundleUrl = OsgiStandaloneTest.BROOKLYN_TEST_OSGI_ENTITIES_URL;

        String yaml = Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  id: " + symbolicName,
                "  version: " + TEST_VERSION,
                "  itemType: policy",
                "  name: My Catalog Policy",
                "  description: My description",
                "  libraries:",
                "  - url: " + bundleUrl,
                "  item:",
                "    type: " + policyType);

        CatalogPolicySummary item = Iterables.getOnlyElement( client().path("/catalog")
                .post(yaml, new GenericType<Map<String,CatalogPolicySummary>>() {}).values() );

        CatalogSummaryAsserts.newInstance(CatalogItemType.POLICY, symbolicName, TEST_VERSION)
                .planYamlPredicate(StringPredicates.containsLiteral(policyType))
                .name("My Catalog Policy")
                .description("My description")
                .applyAsserts(item);
    }

    @Test
    public void testListAllEntities() {
        List<CatalogEntitySummary> entities = client().path("/catalog/entities")
                .get(new GenericType<List<CatalogEntitySummary>>() {});
        log.info("Entities: "+entities);
        assertTrue(entities.size() > 0);
    }

    @Test
    public void testListAllEntitiesAsItem() {
        // ensure things are happily downcasted and unknown properties ignored (e.g. sensors, effectors)
        List<CatalogItemSummary> entities = client().path("/catalog/entities")
                .get(new GenericType<List<CatalogItemSummary>>() {});
        assertTrue(entities.size() > 0);
    }

    @Test
    public void testFilterListOfEntitiesByName() {
        List<CatalogEntitySummary> entities = client().path("/catalog/entities")
                .query("fragment", "vaNIllasOFTWAREpROCESS").get(new GenericType<List<CatalogEntitySummary>>() {});
        log.info("Matching entities: " + entities);
        Asserts.assertSize(entities, 1);

        List<CatalogEntitySummary> entities2 = client().path("/catalog/entities")
                .query("regex", "[Vv]an.[alS]+oftware\\w+").get(new GenericType<List<CatalogEntitySummary>>() {});
        Asserts.assertSize(entities2, 1);

        assertEquals(entities, entities2);
    
        List<CatalogEntitySummary> entities3 = client().path("/catalog/entities")
                .query("fragment", "bweqQzZ").get(new GenericType<List<CatalogEntitySummary>>() {});
        Asserts.assertSize(entities3, 0);

        List<CatalogEntitySummary> entities4 = client().path("/catalog/entities")
                .query("regex", "bweq+z+").get(new GenericType<List<CatalogEntitySummary>>() {});
        Asserts.assertSize(entities4, 0);
    }

    @Test
    public void testGetCatalogEntityIconDetails() throws IOException {
        String catalogItemId = "testGetCatalogEntityIconDetails";
        addTestCatalogItemAsEntity(catalogItemId);
        Response response = client().path(URI.create("/catalog/icon/" + catalogItemId + "/" + TEST_VERSION))
                .get();
        response.bufferEntity();
        Assert.assertEquals(response.getStatus(), 200);
        Assert.assertEquals(response.getMediaType(), MediaType.valueOf("image/png"));
        Image image = Toolkit.getDefaultToolkit().createImage(Files.readFile(response.readEntity(InputStream.class)));
        Assert.assertNotNull(image);
    }

    private void addTestCatalogItemAsEntity(String catalogItemId) {
        addTestCatalogItem(catalogItemId, "entity", TEST_VERSION, "org.apache.brooklyn.rest.resources.DummyIconEntity");
    }

    private void addTestCatalogItem(String catalogItemId, String itemType, String version, String service) {
        String yaml = Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  id: " + catalogItemId,
                "  version: " + TEST_VERSION,
                "  itemType: " + checkNotNull(itemType),
                "  name: My Catalog App",
                "  description: My description",
                "  icon_url: classpath:///bridge-small.png",
                "  version: " + version,
                "  item:",
                "    type: " + service);

        client().path("/catalog").post(yaml);
    }

    private enum DeprecateStyle {
        NEW_STYLE,
        LEGACY_STYLE
    }
    private void deprecateCatalogItem(DeprecateStyle style, String symbolicName, String version, boolean deprecated) {
        String id = String.format("%s:%s", symbolicName, version);
        Response response;
        if (style == DeprecateStyle.NEW_STYLE) {
            response = client().path(String.format("/catalog/entities/%s/deprecated", id))
                    .header(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON)
                    .post(deprecated);
        } else {
            response = client().path(String.format("/catalog/entities/%s/deprecated/%s", id, deprecated))
                    .post(null);
        }
        assertEquals(response.getStatus(), Response.Status.NO_CONTENT.getStatusCode());
    }

    private void disableCatalogItem(String symbolicName, String version, boolean disabled) {
        String id = String.format("%s:%s", symbolicName, version);
        Response getDisableResponse = client().path(String.format("/catalog/entities/%s/disabled", id))
                .header(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON)
                .post(disabled);
        assertEquals(getDisableResponse.getStatus(), Response.Status.NO_CONTENT.getStatusCode());
    }

    @Test
    public void testListPolicies() {
        Set<CatalogPolicySummary> policies = client().path("/catalog/policies")
                .get(new GenericType<Set<CatalogPolicySummary>>() {});

        assertTrue(policies.size() > 0);
        CatalogItemSummary asp = null;
        for (CatalogItemSummary p : policies) {
            if (AutoScalerPolicy.class.getName().equals(p.getType()))
                asp = p;
        }
        Assert.assertNotNull(asp, "didn't find AutoScalerPolicy");
    }

    @Test
    public void testLocationAddGetAndRemove() {
        String symbolicName = "my.catalog.location.id";
        String locationType = "localhost";
        String yaml = Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  id: " + symbolicName,
                "  version: " + TEST_VERSION,
                "  itemType: location",
                "  name: My Catalog Location",
                "  description: My description",
                "  item:",
                "    type: " + locationType);

        // Create location item
        Map<String, CatalogLocationSummary> items = client().path("/catalog")
                .post(yaml, new GenericType<Map<String,CatalogLocationSummary>>() {});
        CatalogLocationSummary locationItem = Iterables.getOnlyElement(items.values());

        CatalogSummaryAsserts.newInstance(CatalogItemType.LOCATION, symbolicName, TEST_VERSION)
                .planYamlPredicate(StringPredicates.containsLiteral(locationType))
                .name("My Catalog Location")
                .description("My description")
                .applyAsserts(locationItem)
                .applyAsserts(() -> client());

        // Retrieve all locations
        Set<CatalogLocationSummary> locations = client().path("/catalog/locations")
                .get(new GenericType<Set<CatalogLocationSummary>>() {});
        boolean found = false;
        for (CatalogLocationSummary contender : locations) {
            if (contender.getSymbolicName().equals(symbolicName)) {
                found = true;
                break;
            }
        }
        Assert.assertTrue(found, "contenders="+locations);
        
        // Delete
        Response deleteResponse = client().path("/catalog/locations/"+symbolicName+"/"+TEST_VERSION)
                .delete();
        assertEquals(deleteResponse.getStatus(), Response.Status.NO_CONTENT.getStatusCode());

        Response getPostDeleteResponse = client().path("/catalog/locations/"+symbolicName+"/"+TEST_VERSION)
                .get();
        assertEquals(getPostDeleteResponse.getStatus(), Response.Status.NOT_FOUND.getStatusCode());
    }

    @Test
    public void testListEnrichers() {
        Set<CatalogEnricherSummary> enrichers = client().path("/catalog/enrichers")
                .get(new GenericType<Set<CatalogEnricherSummary>>() {});

        assertTrue(enrichers.size() > 0);
        CatalogEnricherSummary asp = null;
        for (CatalogEnricherSummary p : enrichers) {
            if (Aggregator.class.getName().equals(p.getType()))
                asp = p;
        }
        Assert.assertNotNull(asp, "didn't find Aggregator");
    }

    @Test
    public void testEnricherAddGetAndRemove() {
        String symbolicName = "my.catalog.enricher.id";
        String enricherType = "org.apache.brooklyn.enricher.stock.Aggregator";
        String yaml = Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  id: " + symbolicName,
                "  version: " + TEST_VERSION,
                "  itemType: enricher",
                "  name: My Catalog Enricher",
                "  description: My description",
                "  item:",
                "    type: " + enricherType);

        // Create location item
        Map<String, CatalogEnricherSummary> items = client().path("/catalog")
                .post(yaml, new GenericType<Map<String, CatalogEnricherSummary>>() {});
        CatalogEnricherSummary enricherItem = Iterables.getOnlyElement(items.values());

        CatalogSummaryAsserts.newInstance(CatalogItemType.ENRICHER, symbolicName, TEST_VERSION)
                .planYamlPredicate(StringPredicates.containsLiteral(enricherType))
                .name("My Catalog Enricher")
                .description("My description")
                .applyAsserts(enricherItem)
                .applyAsserts(() -> client());

        // Retrieve all locations
        Set<CatalogEnricherSummary> enrichers = client().path("/catalog/enrichers")
                .get(new GenericType<Set<CatalogEnricherSummary>>() {});
        boolean found = false;
        for (CatalogEnricherSummary contender : enrichers) {
            if (contender.getSymbolicName().equals(symbolicName)) {
                found = true;
                break;
            }
        }
        Assert.assertTrue(found, "contenders="+enrichers);

        // Delete
        Response deleteResponse = client().path("/catalog/enrichers/"+symbolicName+"/"+TEST_VERSION)
                .delete();
        assertEquals(deleteResponse.getStatus(), Response.Status.NO_CONTENT.getStatusCode());

        Response getPostDeleteResponse = client().path("/catalog/enrichers/"+symbolicName+"/"+TEST_VERSION)
                .get();
        assertEquals(getPostDeleteResponse.getStatus(), Response.Status.NOT_FOUND.getStatusCode());
    }

    @Test
    // osgi may fail in IDE, typically works on mvn CLI though
    public void testRegisterOsgiEnricherTopLevelSyntax() {
        TestResourceUnavailableException.throwIfResourceUnavailable(getClass(), OsgiStandaloneTest.BROOKLYN_TEST_OSGI_ENTITIES_PATH);

        String symbolicName = "my.catalog.enricher.id";
        String enricherType = OsgiTestResources.BROOKLYN_TEST_OSGI_ENTITIES_SIMPLE_ENRICHER;
        String bundleUrl = OsgiStandaloneTest.BROOKLYN_TEST_OSGI_ENTITIES_URL;

        String yaml = Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  id: " + symbolicName,
                "  version: " + TEST_VERSION,
                "  itemType: enricher",
                "  name: My Catalog Enricher",
                "  description: My description",
                "  libraries:",
                "  - url: " + bundleUrl,
                "  item:",
                "    type: " + enricherType);

        CatalogEnricherSummary enricherItem = Iterables.getOnlyElement( client().path("/catalog")
                .post(yaml, new GenericType<Map<String,CatalogEnricherSummary>>() {}).values() );

        CatalogSummaryAsserts.newInstance(CatalogItemType.ENRICHER, symbolicName, TEST_VERSION)
                .planYamlPredicate(StringPredicates.containsLiteral(enricherType))
                .name("My Catalog Enricher")
                .description("My description")
                .applyAsserts(enricherItem)
                .applyAsserts(() -> client());
    }

    @Test
    public void testDeleteCustomEntityFromCatalog() {
        String symbolicName = "my.catalog.app.id.to.subsequently.delete";
        String yaml = Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  id: " + symbolicName,
                "  version: " + TEST_VERSION,
                "  itemType: entity",
                "  name: My Catalog App To Be Deleted",
                "  description: My description",
                "  item:",
                "    type: org.apache.brooklyn.core.test.entity.TestEntity");

        client().path("/catalog")
                .post(yaml);

        Response deleteResponse = client().path("/catalog/entities/"+symbolicName+"/"+TEST_VERSION)
                .delete();

        assertEquals(deleteResponse.getStatus(), Response.Status.NO_CONTENT.getStatusCode());

        Response getPostDeleteResponse = client().path("/catalog/entities/"+symbolicName+"/"+TEST_VERSION)
                .get();
        assertEquals(getPostDeleteResponse.getStatus(), Response.Status.NOT_FOUND.getStatusCode());
    }

    @Test
    public void testSetDeprecated() {
        runSetDeprecated(DeprecateStyle.NEW_STYLE);
    }

    protected void runSetDeprecated(DeprecateStyle style) {
        String symbolicName = "my.catalog.item.id.for.deprecation";
        String serviceType = "org.apache.brooklyn.entity.stock.BasicApplication";
        addTestCatalogItem(symbolicName, "template", TEST_VERSION, serviceType);
        addTestCatalogItem(symbolicName, "template", "2.0", serviceType);
        try {
            List<CatalogEntitySummary> applications = client().path("/catalog/applications")
                    .query("fragment", symbolicName).query("allVersions", "true").get(new GenericType<List<CatalogEntitySummary>>() {});
            assertEquals(applications.size(), 2);
            CatalogItemSummary summary0 = applications.get(0);
            CatalogItemSummary summary1 = applications.get(1);
    
            // Deprecate: that app should be excluded
            deprecateCatalogItem(style, summary0.getSymbolicName(), summary0.getVersion(), true);
    
            List<CatalogEntitySummary> applicationsAfterDeprecation = client().path("/catalog/applications")
                    .query("fragment", "basicapp").query("allVersions", "true").get(new GenericType<List<CatalogEntitySummary>>() {});
    
            assertEquals(applicationsAfterDeprecation.size(), 1);
            assertTrue(applicationsAfterDeprecation.contains(summary1));
    
            // Un-deprecate: that app should be included again
            deprecateCatalogItem(style, summary0.getSymbolicName(), summary0.getVersion(), false);
    
            List<CatalogEntitySummary> applicationsAfterUnDeprecation = client().path("/catalog/applications")
                    .query("fragment", "basicapp").query("allVersions", "true").get(new GenericType<List<CatalogEntitySummary>>() {});
    
            assertEquals(applications, applicationsAfterUnDeprecation);
        } finally {
            client().path("/catalog/entities/"+symbolicName+"/"+TEST_VERSION)
                    .delete();
            client().path("/catalog/entities/"+symbolicName+"/"+"2.0")
                    .delete();
        }
    }

    @Test
    public void testSetDisabled() {
        String symbolicName = "my.catalog.item.id.for.disabling";
        String serviceType = "org.apache.brooklyn.entity.stock.BasicApplication";
        addTestCatalogItem(symbolicName, "template", TEST_VERSION, serviceType);
        addTestCatalogItem(symbolicName, "template", "2.0", serviceType);
        try {
            List<CatalogEntitySummary> applications = client().path("/catalog/applications")
                    .query("fragment", symbolicName).query("allVersions", "true").get(new GenericType<List<CatalogEntitySummary>>() {});
            assertEquals(applications.size(), 2);
            CatalogItemSummary summary0 = applications.get(0);
            CatalogItemSummary summary1 = applications.get(1);
    
            // Disable: that app should be excluded
            disableCatalogItem(summary0.getSymbolicName(), summary0.getVersion(), true);
    
            List<CatalogEntitySummary> applicationsAfterDisabled = client().path("/catalog/applications")
                    .query("fragment", "basicapp").query("allVersions", "true").get(new GenericType<List<CatalogEntitySummary>>() {});
    
            assertEquals(applicationsAfterDisabled.size(), 1);
            assertTrue(applicationsAfterDisabled.contains(summary1));
    
            // Un-disable: that app should be included again
            disableCatalogItem(summary0.getSymbolicName(), summary0.getVersion(), false);
    
            List<CatalogEntitySummary> applicationsAfterUnDisabled = client().path("/catalog/applications")
                    .query("fragment", "basicapp").query("allVersions", "true").get(new GenericType<List<CatalogEntitySummary>>() {});
    
            assertEquals(applications, applicationsAfterUnDisabled);
        } finally {
            client().path("/catalog/entities/"+symbolicName+"/"+TEST_VERSION)
                    .delete();
            client().path("/catalog/entities/"+symbolicName+"/"+"2.0")
                    .delete();
        }
    }

    @Test
    public void testAddUnreachableItem() {
        Object err = addAddCatalogItemWithInvalidBundleUrl("http://0.0.0.0/can-not-connect");
        Asserts.assertStringContainsIgnoreCase(err.toString(), "0.0.0.0/can-not-connect", "connection refused");
    }

    @Test
    public void testAddInvalidItem() {
        //equivalent to HTTP response 200 text/html
        Object err = addAddCatalogItemWithInvalidBundleUrl("classpath://not-a-jar-file.txt");
        Asserts.assertStringContainsIgnoreCase(err.toString(), "classpath://not-a-jar-file.txt", "ZipException");
    }

    @Test
    public void testAddMissingItem() {
        //equivalent to HTTP response 404 text/html
        Object err = addAddCatalogItemWithInvalidBundleUrl("classpath://missing-jar-file.txt");
        Asserts.assertStringContainsIgnoreCase(err.toString(), "classpath://missing-jar-file.txt", "not found");
    }

    @Test
    public void testInvalidArchive() throws Exception {
        File f = Os.newTempFile("osgi", "zip");

        Response response = client().path("/catalog")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-zip")
                .post(Streams.readFully(new FileInputStream(f)));

        assertEquals(response.getStatus(), Response.Status.BAD_REQUEST.getStatusCode());
        Asserts.assertStringContainsIgnoreCase(response.readEntity(String.class), "zip file is empty");
    }

    @Test
    public void testArchiveWithoutBom() throws Exception {
        File f = createZip(ImmutableMap.<String, String>of());

        Response response = client().path("/catalog")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-zip")
                .post(Streams.readFully(new FileInputStream(f)));

        assertEquals(response.getStatus(), Response.Status.BAD_REQUEST.getStatusCode());
        Asserts.assertStringContainsIgnoreCase(response.readEntity(String.class), "Missing bundle symbolic name in BOM or MANIFEST");
    }

    @Test
    public void testArchiveWithoutBundleAndVersion() throws Exception {
        File f = createZip(ImmutableMap.<String, String>of("catalog.bom", Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  itemType: entity",
                "  name: My Catalog App",
                "  description: My description",
                "  icon_url: classpath:/org/apache/brooklyn/test/osgi/entities/icon.gif",
                "  item:",
                "    type: org.apache.brooklyn.core.test.entity.TestEntity")));

        Response response = client().path("/catalog")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-zip")
                .post(Streams.readFully(new FileInputStream(f)));

        assertEquals(response.getStatus(), Response.Status.BAD_REQUEST.getStatusCode());
        Asserts.assertStringContainsIgnoreCase(response.readEntity(String.class), "Missing bundle symbolic name in BOM or MANIFEST");
    }

    @Test
    public void testArchiveWithoutBundle() throws Exception {
        File f = createZip(ImmutableMap.<String, String>of("catalog.bom", Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  version: 0.1.0",
                "  itemType: entity",
                "  name: My Catalog App",
                "  description: My description",
                "  icon_url: classpath:/org/apache/brooklyn/test/osgi/entities/icon.gif",
                "  item:",
                "    type: org.apache.brooklyn.core.test.entity.TestEntity")));

        Response response = client().path("/catalog")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-zip")
                .post(Streams.readFully(new FileInputStream(f)));

        assertEquals(response.getStatus(), Response.Status.BAD_REQUEST.getStatusCode());
        Asserts.assertStringContainsIgnoreCase(response.readEntity(String.class), 
            "Missing bundle symbolic name in BOM or MANIFEST");
    }

    @Test
    public void testArchiveWithoutVersion() throws Exception {
        File f = createZip(ImmutableMap.<String, String>of("catalog.bom", Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  bundle: org.apache.brooklyn.test",
                "  itemType: entity",
                "  name: My Catalog App",
                "  description: My description",
                "  icon_url: classpath:/org/apache/brooklyn/test/osgi/entities/icon.gif",
                "  item:",
                "    type: org.apache.brooklyn.core.test.entity.TestEntity")));

        Response response = client().path("/catalog")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-zip")
                .post(Streams.readFully(new FileInputStream(f)));

        assertEquals(response.getStatus(), Response.Status.BAD_REQUEST.getStatusCode());
        Asserts.assertStringContainsIgnoreCase(response.readEntity(String.class), "BOM", "bundle", "version");
    }

    @Test
    public void testArchiveWithBundleAndVersion() throws Exception {
        String version = "0.1.0";
        String itemSymbolicName = "my-entity";
        
        File f = createZip(ImmutableMap.<String, String>of("catalog.bom", Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  bundle: org.apache.brooklyn.test",
                "  version: " + version,
                "  itemType: entity",
                "  id: " + itemSymbolicName,
                "  name: My Catalog App",
                "  description: My description",
                "  item:",
                "    type: org.apache.brooklyn.core.test.entity.TestEntity")));

        Response response = client().path("/catalog")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-zip")
                .post(Streams.readFully(new FileInputStream(f)));

        assertEquals(response.getStatus(), Response.Status.CREATED.getStatusCode());

        CatalogSummaryAsserts.newInstance(CatalogItemType.ENTITY, itemSymbolicName, version)
                .planYamlPredicate(StringPredicates.containsLiteral("org.apache.brooklyn.core.test.entity.TestEntity"))
                .name("My Catalog App")
                .description("My description")
                .expectedInterfaces(Reflections.getAllInterfaces(TestEntity.class))
                .applyAsserts(() -> client());
        
        RegisteredTypeAsserts.newInstance(itemSymbolicName, version)
                .libraryNames(new VersionedName("org.apache.brooklyn.test", version))
                .libraryUrls((String)null)
                .applyAsserts(getManagementContext().getTypeRegistry());
    }

    @Test
    public void testInstallSameArchiveTwice() throws Exception {
        String version = "0.1.0";
        String itemSymbolicName = "my-entity";
        
        File f = createZip(ImmutableMap.<String, String>of("catalog.bom", Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  bundle: org.apache.brooklyn.test",
                "  version: " + version,
                "  itemType: entity",
                "  id: " + itemSymbolicName,
                "  name: My Catalog App",
                "  item:",
                "    type: org.apache.brooklyn.core.test.entity.TestEntity")));

        // Deploy first time
        Response response = client().path("/catalog")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-zip")
                .post(Streams.readFully(new FileInputStream(f)));

        assertEquals(response.getStatus(), Response.Status.CREATED.getStatusCode());

        RegisteredTypeAsserts.newInstance(itemSymbolicName, version)
                .libraryNames(new VersionedName("org.apache.brooklyn.test", version))
                .applyAsserts(getManagementContext().getTypeRegistry());
        
        // Deploy again
        // Expect 200 (didn't create it, because already existed).
        Response response2 = client().path("/catalog")
                .query("detail", "true")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-zip")
                .post(Streams.readFully(new FileInputStream(f)));
        String response2Body = response2.readEntity(String.class);
        
        Map<?,?> response2Map = new Gson().fromJson(response2Body, Map.class);
        String response2Message = (String) response2Map.get("message");
        String response2Code = (String) response2Map.get("code");
        
        assertEquals(response2.getStatus(), Response.Status.OK.getStatusCode());
        assertTrue(response2Code.equals("IGNORING_BUNDLE_AREADY_INSTALLED"), "body="+response2Body);
        assertTrue(response2Message.toLowerCase().contains("bundle org.apache.brooklyn.test:0.1.0 already installed"), "body="+response2Body);

        RegisteredTypeAsserts.newInstance(itemSymbolicName, version)
                .libraryNames(new VersionedName("org.apache.brooklyn.test", version))
                .applyAsserts(getManagementContext().getTypeRegistry());
    }

    @Test
    public void testJarWithoutMatchingBundle() throws Exception {
        String name = "My Catalog App";
        String bundle = "org.apache.brooklyn.test";
        String version = "0.1.0";
        String wrongBundleName = "org.apache.brooklyn.test2";
        File f = createJar(ImmutableMap.<String, String>of(
                "catalog.bom", Joiner.on("\n").join(
                        "brooklyn.catalog:",
                        "  bundle: " + bundle,
                        "  version: " + version,
                        "  itemType: entity",
                        "  name: " + name,
                        "  description: My description",
                        "  icon_url: classpath:/org/apache/brooklyn/test/osgi/entities/icon.gif",
                        "  item:",
                        "    type: org.apache.brooklyn.core.test.entity.TestEntity"),
                "META-INF/MANIFEST.MF", Joiner.on("\n").join(
                        "Manifest-Version: 1.0",
                        "Bundle-Name: " + name,
                        "Bundle-SymbolicName: "+wrongBundleName,
                        "Bundle-Version: " + version,
                        "Bundle-ManifestVersion: " + version)));

        Response response = client().path("/catalog")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-jar")
                .post(Streams.readFully(new FileInputStream(f)));

        assertEquals(response.getStatus(), Response.Status.BAD_REQUEST.getStatusCode());
        Asserts.assertStringContainsIgnoreCase(response.readEntity(String.class), 
            "symbolic name mismatch",
            wrongBundleName, bundle);
    }

    @Test
    public void testJarWithoutMatchingVersion() throws Exception {
        String name = "My Catalog App";
        String bundle = "org.apache.brooklyn.test";
        String version = "0.1.0";
        String wrongVersion = "0.3.0";
        File f = createJar(ImmutableMap.<String, String>of(
                "catalog.bom", Joiner.on("\n").join(
                        "brooklyn.catalog:",
                        "  bundle: " + bundle,
                        "  version: " + version,
                        "  itemType: entity",
                        "  name: " + name,
                        "  description: My description",
                        "  icon_url: classpath:/org/apache/brooklyn/test/osgi/entities/icon.gif",
                        "  item:",
                        "    type: org.apache.brooklyn.core.test.entity.TestEntity"),
                "META-INF/MANIFEST.MF", Joiner.on("\n").join(
                        "Manifest-Version: 1.0",
                        "Bundle-Name: " + name,
                        "Bundle-SymbolicName: " + bundle,
                        "Bundle-Version: " + wrongVersion,
                        "Bundle-ManifestVersion: " + wrongVersion)));

        Response response = client().path("/catalog")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-jar")
                .post(Streams.readFully(new FileInputStream(f)));

        assertEquals(response.getStatus(), Response.Status.BAD_REQUEST.getStatusCode());
        Asserts.assertStringContainsIgnoreCase(response.readEntity(String.class), 
            "version mismatch",
            wrongVersion, version);
    }

    @Test
    public void testOsgiBundleWithBom() throws Exception {
        TestResourceUnavailableException.throwIfResourceUnavailable(getClass(), OsgiStandaloneTest.BROOKLYN_TEST_OSGI_ENTITIES_PATH);
        final String bundleSymbolicName = OsgiStandaloneTest.BROOKLYN_TEST_OSGI_ENTITIES_SYMBOLIC_NAME_FULL;
        final String version = OsgiStandaloneTest.BROOKLYN_TEST_OSGI_ENTITIES_VERSION;
        final String bundleUrl = OsgiStandaloneTest.BROOKLYN_TEST_OSGI_ENTITIES_URL;
        final String itemSymbolicName = "my-entity";
        BundleMaker bm = new BundleMaker(manager);
        File f = Os.newTempFile("osgi", "jar");
        Files.copyFile(ResourceUtils.create(this).getResourceFromUrl(bundleUrl), f);
        
        String bom = Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  bundle: " + bundleSymbolicName,
                "  version: " + version,
                "  id: " + itemSymbolicName,
                "  itemType: entity",
                "  name: My Catalog App",
                "  description: My description",
                "  icon_url: classpath:/org/apache/brooklyn/test/osgi/entities/icon.gif",
                "  item:",
                "    type: org.apache.brooklyn.core.test.entity.TestEntity");
        
        f = bm.copyAdding(f, MutableMap.of(new ZipEntry("catalog.bom"), (InputStream) new ByteArrayInputStream(bom.getBytes())));

        Response response = client().path("/catalog")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-jar")
                .post(Streams.readFully(new FileInputStream(f)));

        assertEquals(response.getStatus(), Response.Status.CREATED.getStatusCode());

        CatalogSummaryAsserts.newInstance(CatalogItemType.ENTITY, itemSymbolicName, version)
                .planYamlPredicate(StringPredicates.containsLiteral("org.apache.brooklyn.core.test.entity.TestEntity"))
                .name("My Catalog App")
                .description("My description")
                .expectedInterfaces(Reflections.getAllInterfaces(TestEntity.class))
                .iconData((data) -> {assertEquals(data.length, 43); return true;})
                .applyAsserts(() -> client());
        
        RegisteredTypeAsserts.newInstance(itemSymbolicName, version)
                .libraryNames(new VersionedName(bundleSymbolicName, version))
                .libraryUrls((String)null)
                .iconUrl("classpath:/org/apache/brooklyn/test/osgi/entities/icon.gif")
                .applyAsserts(getManagementContext().getTypeRegistry());
    }

    @Test
    public void testOsgiBundleWithBomNotInBrooklynNamespace() throws Exception {
        TestResourceUnavailableException.throwIfResourceUnavailable(getClass(), OsgiTestResources.BROOKLYN_TEST_OSGI_ENTITIES_COM_EXAMPLE_PATH);
        final String bundleSymbolicName = OsgiTestResources.BROOKLYN_TEST_OSGI_ENTITIES_COM_EXAMPLE_SYMBOLIC_NAME_FULL;
        final String version = OsgiTestResources.BROOKLYN_TEST_OSGI_ENTITIES_COM_EXAMPLE_VERSION;
        final String bundleUrl = OsgiTestResources.BROOKLYN_TEST_OSGI_ENTITIES_COM_EXAMPLE_URL;
        final String entityType = OsgiTestResources.BROOKLYN_TEST_OSGI_ENTITIES_COM_EXAMPLE_ENTITY;
        final String iconPath = OsgiTestResources.BROOKLYN_TEST_OSGI_ENTITIES_COM_EXAMPLE_ICON_PATH;
        final String itemSymbolicName = "my-item";
        BundleMaker bm = new BundleMaker(manager);
        File f = Os.newTempFile("osgi", "jar");
        Files.copyFile(ResourceUtils.create(this).getResourceFromUrl(bundleUrl), f);

        String bom = Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  bundle: " + bundleSymbolicName,
                "  version: " + version,
                "  id: " + itemSymbolicName,
                "  itemType: entity",
                "  name: My Catalog App",
                "  description: My description",
                "  icon_url: classpath:" + iconPath,
                "  item:",
                "    type: " + entityType);

        f = bm.copyAdding(f, MutableMap.of(new ZipEntry("catalog.bom"), (InputStream) new ByteArrayInputStream(bom.getBytes())));

        Response response = client().path("/catalog")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-zip")
                .post(Streams.readFully(new FileInputStream(f)));


        assertEquals(response.getStatus(), Response.Status.CREATED.getStatusCode());

        CatalogSummaryAsserts.newInstance(CatalogItemType.ENTITY, itemSymbolicName, version)
                .planYamlPredicate(StringPredicates.containsLiteral(entityType))
                .name("My Catalog App")
                .description("My description")
                .expectedInterfaces(ImmutableList.of(Entity.class, BrooklynObject.class, Identifiable.class, Configurable.class))
                .iconData((data) -> {assertEquals(data.length, 43); return true;})
                .applyAsserts(() -> client());
        
        RegisteredTypeAsserts.newInstance(itemSymbolicName, version)
                .libraryNames(new VersionedName(bundleSymbolicName, version))
                .libraryUrls((String)null)
                .iconUrl("classpath:"+iconPath)
                .applyAsserts(getManagementContext().getTypeRegistry());

        // Check that the catalog item is useable (i.e. can deploy the entity)
        String appYaml = Joiner.on("\n").join(
                "services:",
                "- type: " + itemSymbolicName + ":" + version,
                "  name: myEntityName");

        Response appResponse = client().path("/applications")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-yaml")
                .post(appYaml);

        assertEquals(appResponse.getStatus(), Response.Status.CREATED.getStatusCode());

        Entity entity = Iterables.tryFind(getManagementContext().getEntityManager().getEntities(), EntityPredicates.displayNameEqualTo("myEntityName")).get();
        assertEquals(entity.getEntityType().getName(), entityType);
    }

    private Object addAddCatalogItemWithInvalidBundleUrl(String bundleUrl) {
        String symbolicName = "my.catalog.entity.id";
        String yaml = Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  id: " + symbolicName,
                "  version: " + TEST_VERSION,
                "  itemType: entity",
                "  name: My Catalog App",
                "  description: My description",
                "  icon_url: classpath:/org/apache/brooklyn/test/osgi/entities/icon.gif",
                "  libraries:",
                "  - url: " + bundleUrl,
                "  item:",
                "    type: org.apache.brooklyn.core.test.entity.TestEntity");

        Response response = client().path("/catalog")
                .post(yaml);

        assertEquals(response.getStatus(), HttpStatus.BAD_REQUEST_400);
        return response.readEntity(Object.class);
    }

    private static String ver(String id) {
        return CatalogUtils.getVersionedId(id, TEST_VERSION);
    }

    private static File createZip(Map<String, String> files) throws Exception {
        File f = Os.newTempFile("osgi", "zip");

        ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(f));

        for (Map.Entry<String, String> entry : files.entrySet()) {
            ZipEntry ze = new ZipEntry(entry.getKey());
            zip.putNextEntry(ze);
            zip.write(entry.getValue().getBytes());
        }

        zip.closeEntry();
        zip.flush();
        zip.close();

        return f;
    }

    private static File createJar(Map<String, String> files) throws Exception {
        File f = Os.newTempFile("osgi", "jar");

        JarOutputStream zip = new JarOutputStream(new FileOutputStream(f));

        for (Map.Entry<String, String> entry : files.entrySet()) {
            JarEntry ze = new JarEntry(entry.getKey());
            zip.putNextEntry(ze);
            zip.write(entry.getValue().getBytes());
        }

        zip.closeEntry();
        zip.flush();
        zip.close();

        return f;
    }

    @Test
    public void testGetOnlyLatestApplication() {
        String symbolicName = "latest.catalog.application.id";
        String itemType = "template";
        String serviceType = "org.apache.brooklyn.core.test.entity.TestEntity";

        addTestCatalogItem(symbolicName, itemType, TEST_VERSION, serviceType);
        addTestCatalogItem(symbolicName, itemType, TEST_LASTEST_VERSION, serviceType);

        CatalogItemSummary application = client().path("/catalog/applications/" + symbolicName + "/latest")
                .get(CatalogItemSummary.class);
        assertEquals(application.getVersion(), TEST_LASTEST_VERSION);
    }

    @Test
    public void testGetOnlyLatestDifferentCases() {
        // depends on installation of this
        testGetOnlyLatestApplication();
        
        String symbolicName = "latest.catalog.application.id";

        CatalogItemSummary application = client().path("/catalog/applications/" + symbolicName + "/LaTeSt")
                .get(CatalogItemSummary.class);
        assertEquals(application.getVersion(), TEST_LASTEST_VERSION);

        application = client().path("/catalog/applications/" + symbolicName + "/LATEST")
                .get(CatalogItemSummary.class);
        assertEquals(application.getVersion(), TEST_LASTEST_VERSION);
    }

    @Test
    public void testGetOnlyLatestEntity() {
        String symbolicName = "latest.catalog.entity.id";
        String itemType = "entity";
        String serviceType = "org.apache.brooklyn.core.test.entity.TestEntity";

        addTestCatalogItem(symbolicName, itemType, TEST_VERSION, serviceType);
        addTestCatalogItem(symbolicName, itemType, TEST_LASTEST_VERSION, serviceType);

        CatalogItemSummary application = client().path("/catalog/entities/" + symbolicName + "/latest")
                .get(CatalogItemSummary.class);
        assertEquals(application.getVersion(), TEST_LASTEST_VERSION);
    }

    @Test
    public void testGetOnlyLatestPolicy() {
        String symbolicName = "latest.catalog.policy.id";
        String itemType = "policy";
        String serviceType = "org.apache.brooklyn.policy.autoscaling.AutoScalerPolicy";

        addTestCatalogItem(symbolicName, itemType, TEST_VERSION, serviceType);
        addTestCatalogItem(symbolicName, itemType, TEST_LASTEST_VERSION, serviceType);

        CatalogItemSummary application = client().path("/catalog/policies/" + symbolicName + "/latest")
                .get(CatalogItemSummary.class);
        assertEquals(application.getVersion(), TEST_LASTEST_VERSION);
    }

    @Test
    public void testGetOnlyLatestLocation() {
        String symbolicName = "latest.catalog.location.id";
        String itemType = "location";
        String serviceType = "localhost";

        addTestCatalogItem(symbolicName, itemType, TEST_VERSION, serviceType);
        addTestCatalogItem(symbolicName, itemType, TEST_LASTEST_VERSION, serviceType);

        CatalogItemSummary application = client().path("/catalog/policies/" + symbolicName + "/latest")
                .get(CatalogItemSummary.class);
        assertEquals(application.getVersion(), TEST_LASTEST_VERSION);
    }

    @Test
    public void testDeleteOnlyLatestApplication() throws IOException {
        // depends on installation of this
        testGetOnlyLatestApplication();

        String symbolicName = "latest.catalog.application.id";

        Response deleteResponse = client().path("/catalog/applications/" + symbolicName + "/latest").delete();
        assertEquals(deleteResponse.getStatus(), HttpStatus.NO_CONTENT_204);

        List<CatalogItemSummary> applications = client().path("/catalog/applications").query("fragment", symbolicName)
                .get(new GenericType<List<CatalogItemSummary>>() {});
        assertEquals(applications.size(), 1);
        assertEquals(applications.get(0).getVersion(), TEST_VERSION);
    }

    @Test
    public void testDeleteOnlyLatestEntity() throws IOException {
        // depends on installation of this
        testGetOnlyLatestEntity();
        
        String symbolicName = "latest.catalog.entity.id";

        Response deleteResponse = client().path("/catalog/entities/" + symbolicName + "/latest").delete();
        assertEquals(deleteResponse.getStatus(), HttpStatus.NO_CONTENT_204);

        List<CatalogItemSummary> applications = client().path("/catalog/entities").query("fragment", symbolicName)
                .get(new GenericType<List<CatalogItemSummary>>() {});
        assertEquals(applications.size(), 1);
        assertEquals(applications.get(0).getVersion(), TEST_VERSION);
    }

    @Test
    public void testDeleteOnlyLatestPolicy() throws IOException {
        // depends on installation of this
        testGetOnlyLatestPolicy();
        
        String symbolicName = "latest.catalog.policy.id";

        Response deleteResponse = client().path("/catalog/policies/" + symbolicName + "/latest").delete();
        assertEquals(deleteResponse.getStatus(), HttpStatus.NO_CONTENT_204);

        List<CatalogItemSummary> applications = client().path("/catalog/policies").query("fragment", symbolicName)
                .get(new GenericType<List<CatalogItemSummary>>() {});
        assertEquals(applications.size(), 1);
        assertEquals(applications.get(0).getVersion(), TEST_VERSION);
    }

    @Test
    public void testDeleteOnlyLatestLocation() throws IOException {
        testGetOnlyLatestLocation();
        
        String symbolicName = "latest.catalog.location.id";

        Response deleteResponse = client().path("/catalog/locations/" + symbolicName + "/latest").delete();
        assertEquals(deleteResponse.getStatus(), HttpStatus.NO_CONTENT_204);

        List<CatalogItemSummary> applications = client().path("/catalog/locations").query("fragment", symbolicName)
                .get(new GenericType<List<CatalogItemSummary>>() {});
        assertEquals(applications.size(), 1);
        assertEquals(applications.get(0).getVersion(), TEST_VERSION);
    }

    @Test
    public void testForceUpdateForYAML() {
        String symbolicName = "force.update.catalog.application.id";
        String itemType = "template";
        String initialName = "My Catalog App";
        String initialDescription = "My description";
        String updatedName = initialName + " 2";
        String updatedDescription = initialDescription + " 2";

        String initialYaml = Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  id: " + symbolicName,
                "  version: " + TEST_VERSION,
                "  itemType: " + itemType,
                "  name: " + initialName,
                "  description: " + initialDescription,
                "  icon_url: classpath:///bridge-small.png",
                "  version: " + TEST_VERSION,
                "  item:",
                "    type: org.apache.brooklyn.core.test.entity.TestEntity");
        String updatedYaml = Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  id: " + symbolicName,
                "  version: " + TEST_VERSION,
                "  itemType: " + itemType,
                "  name: " + updatedName,
                "  description: " + updatedDescription,
                "  icon_url: classpath:///bridge-small.png",
                "  version: " + TEST_VERSION,
                "  item:",
                "    type: org.apache.brooklyn.core.test.entity.TestEntity");

        client().path("/catalog").post(initialYaml);

        CatalogSummaryAsserts.newInstance(CatalogItemType.APPLICATION, symbolicName, TEST_VERSION)
                .name(initialName)
                .description(initialDescription)
                .applyAsserts(() -> client());

        Response invalidResponse = client().path("/catalog").post(updatedYaml);

        assertEquals(invalidResponse.getStatus(), Response.Status.BAD_REQUEST.getStatusCode());

        Response validResponse = client().path("/catalog").query("forceUpdate", true).post(updatedYaml);

        assertEquals(validResponse.getStatus(), Response.Status.CREATED.getStatusCode());

        CatalogSummaryAsserts.newInstance(CatalogItemType.APPLICATION, symbolicName, TEST_VERSION)
                .name(updatedName)
                .description(updatedDescription)
                .applyAsserts(() -> client());
    }

    @Test
    public void testForceUpdateForZip() throws Exception {
        final String symbolicName = "force.update.zip.catalog.application.id";
        final String initialName = "My Catalog App";
        final String initialDescription = "My Description";
        final String updatedName = initialName + " 2";
        final String updatedDescription = initialDescription  +" 2";

        File initialZip = createZip(ImmutableMap.<String, String>of("catalog.bom", Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  bundle: " + symbolicName,
                "  version: " + TEST_VERSION,
                "  id: " + symbolicName,
                "  itemType: entity",
                "  name: " + initialName,
                "  description: " + initialDescription,
                "  icon_url: classpath:/org/apache/brooklyn/test/osgi/entities/icon.gif",
                "  item:",
                "    type: org.apache.brooklyn.core.test.entity.TestEntity")));
        File updatedZip = createZip(ImmutableMap.<String, String>of("catalog.bom", Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  bundle: " + symbolicName,
                "  version: " + TEST_VERSION,
                "  id: " + symbolicName,
                "  itemType: entity",
                "  name: " + updatedName,
                "  description: " + updatedDescription,
                "  icon_url: classpath:/org/apache/brooklyn/test/osgi/entities/icon.gif",
                "  item:",
                "    type: org.apache.brooklyn.core.test.entity.TestEntity")));

        client().path("/catalog")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-zip")
                .post(Streams.readFully(new FileInputStream(initialZip)));

        CatalogSummaryAsserts.newInstance(CatalogItemType.ENTITY, symbolicName, TEST_VERSION)
                .name(initialName)
                .description(initialDescription)
                .applyAsserts(() -> client());
        
        Response invalidResponse = client().path("/catalog")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-zip")
                .post(Streams.readFully(new FileInputStream(updatedZip)));

        assertEquals(invalidResponse.getStatus(), Response.Status.BAD_REQUEST.getStatusCode());

        Response validResponse = client().path("/catalog")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-zip")
                .query("forceUpdate", true)
                .post(Streams.readFully(new FileInputStream(updatedZip)));

        assertEquals(validResponse.getStatus(), Response.Status.CREATED.getStatusCode());

        CatalogSummaryAsserts.newInstance(CatalogItemType.ENTITY, symbolicName, TEST_VERSION)
                .name(updatedName)
                .description(updatedDescription)
                .applyAsserts(() -> client());
    }

    @Test
    public void testForceUpdateForJar() throws Exception {
        final String symbolicName = "force.update.jar.catalog.application.id";
        final String initialName = "My Catalog App";
        final String initialDescription = "My Description";
        final String updatedName = initialName + " 2";
        final String updatedDescription = initialDescription  +" 2";

        File initialJar = createJar(ImmutableMap.<String, String>of("catalog.bom", Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  bundle: " + symbolicName,
                "  version: " + TEST_VERSION,
                "  id: " + symbolicName,
                "  itemType: entity",
                "  name: " + initialName,
                "  description: " + initialDescription,
                "  icon_url: classpath:/org/apache/brooklyn/test/osgi/entities/icon.gif",
                "  item:",
                "    type: org.apache.brooklyn.core.test.entity.TestEntity")));
        File updatedJar = createJar(ImmutableMap.<String, String>of("catalog.bom", Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  bundle: " + symbolicName,
                "  version: " + TEST_VERSION,
                "  id: " + symbolicName,
                "  itemType: entity",
                "  name: " + updatedName,
                "  description: " + updatedDescription,
                "  icon_url: classpath:/org/apache/brooklyn/test/osgi/entities/icon.gif",
                "  item:",
                "    type: org.apache.brooklyn.core.test.entity.TestEntity")));

        client().path("/catalog")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-jar")
                .post(Streams.readFully(new FileInputStream(initialJar)));

        CatalogSummaryAsserts.newInstance(CatalogItemType.ENTITY, symbolicName, TEST_VERSION)
                .name(initialName)
                .description(initialDescription)
                .applyAsserts(() -> client());

        Response invalidResponse = client().path("/catalog")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-jar")
                .post(Streams.readFully(new FileInputStream(updatedJar)));

        assertEquals(invalidResponse.getStatus(), Response.Status.BAD_REQUEST.getStatusCode());

        Response validResponse = client().path("/catalog")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-jar")
                .query("forceUpdate", true)
                .post(Streams.readFully(new FileInputStream(updatedJar)));

        assertEquals(validResponse.getStatus(), Response.Status.CREATED.getStatusCode());

        CatalogSummaryAsserts.newInstance(CatalogItemType.ENTITY, symbolicName, TEST_VERSION)
                .name(updatedName)
                .description(updatedDescription)
                .applyAsserts(() -> client());
    }



    enum CatalogItemType {
        APPLICATION("applications", CatalogEntitySummary.class),
        ENTITY("entities", CatalogEntitySummary.class),
        POLICY("policies", CatalogPolicySummary.class),
        ENRICHER("enrichers", CatalogEnricherSummary.class),
        LOCATION("locations", CatalogLocationSummary.class);
        
        private final String urlPart;
        private final Class<? extends CatalogItemSummary> summaryType;
        
        private CatalogItemType(String urlPart, Class<? extends CatalogItemSummary> summaryType) {
            this.urlPart = urlPart;
            this.summaryType = summaryType;
        }
        public String getUrlPart() {
            return urlPart;
        }
        public Class<? extends CatalogItemSummary> getSummaryClass() {
            return summaryType;
        }
    }
    
    static class CatalogSummaryAsserts {
        protected final CatalogItemType itemType;
        protected final VersionedName versionedName;
        protected Predicate<? super String> planYamlPredicate;
        protected String name;
        protected String description;
        protected List<? extends Class<?>> expectedInterfaces;
        protected Predicate<? super byte[]> iconData;
        protected boolean expectsIcon;
        
        public static CatalogSummaryAsserts newInstance(CatalogItemType itemType, String symbolicName, String version) {
            return newInstance(itemType, new VersionedName(symbolicName, version));
        }
        public static CatalogSummaryAsserts newInstance(CatalogItemType itemType, VersionedName versionedName) {
            return new CatalogSummaryAsserts(itemType, versionedName);
        }
        CatalogSummaryAsserts(CatalogItemType itemType, VersionedName versionedName) {
            this.itemType = itemType;
            this.versionedName = versionedName;
        }
        public CatalogSummaryAsserts planYamlPredicate(Predicate<? super String> val) {
            planYamlPredicate = val;
            return this;
        }
        public CatalogSummaryAsserts name(String val) {
            name = val;
            return this;
        }
        public CatalogSummaryAsserts description(String val) {
            description = val;
            return this;
        }
        public CatalogSummaryAsserts expectsIcon() {
            expectsIcon = true;
            return this;
        }
        public CatalogSummaryAsserts iconData(Predicate<? super byte[]> val) {
            expectsIcon = true;
            iconData = val;
            return this;
        }
        public CatalogSummaryAsserts expectedInterfaces(List<? extends Class<?>> vals) {
            expectedInterfaces = vals;
            return this;
        }
        public CatalogSummaryAsserts applyAsserts(Supplier<WebClient> client) {
            CatalogItemSummary item = getSummary(client);
            return applyAsserts(item, client);
        }
        public CatalogSummaryAsserts applyAsserts(CatalogItemSummary item) {
            return applyAsserts(item, null);
        }
        public CatalogSummaryAsserts applyAsserts(CatalogItemSummary item, Supplier<WebClient> client) {
            assertEquals(item.getId(), CatalogUtils.getVersionedId(versionedName.getSymbolicName(), versionedName.getVersionString()));
            assertEquals(item.getSymbolicName(), versionedName.getSymbolicName());
            assertEquals(item.getVersion(), versionedName.getVersionString());
            
            if (planYamlPredicate != null) {
                Assert.assertNotNull(item.getPlanYaml());
                Assert.assertTrue(planYamlPredicate.apply(item.getPlanYaml()), "plan="+item.getPlanYaml());
            }
            if (name != null) {
                Assert.assertEquals(item.getName(), name);
            }
            if (description != null) {
                Assert.assertEquals(item.getDescription(), description);
            }
            if (expectedInterfaces != null) {
                // an InterfacesTag should be created for every catalog item
                Map<String, List<String>> traitsMapTag = Iterables.getOnlyElement(Iterables.filter(item.getTags(), Map.class));
                List<String> actualInterfaces = traitsMapTag.get("traits");
                assertEquals(actualInterfaces.size(), expectedInterfaces.size(), "actual="+actualInterfaces);
                for (Class<?> expectedInterface : expectedInterfaces) {
                    assertTrue(actualInterfaces.contains(expectedInterface.getName()), "actual="+actualInterfaces);
                }
            }
            if (expectsIcon) {
                URI expectedIconUrl = URI.create("http://dummy/catalog/icon/" + versionedName.getSymbolicName() + "/" + versionedName.getVersionString()).normalize();
                assertEquals(item.getIconUrl(), expectedIconUrl.getPath());
                if (iconData != null) {
                    byte[] bytes = client.get().path("/catalog/icon/" + versionedName.getSymbolicName() + "/" + versionedName.getVersionString()).get(byte[].class);
                    assertTrue(iconData.apply(bytes));
                }
            }
            return this;
        }
        private CatalogItemSummary getSummary(Supplier<WebClient> client) {
            return client.get().path("/catalog/"+itemType.urlPart+"/"+versionedName.getSymbolicName() + "/" + versionedName.getVersionString())
                    .get(itemType.getSummaryClass());
        }
    }
    
    static class RegisteredTypeAsserts {
        public static RegisteredTypeAsserts newInstance(String symbolicName, String version) {
            return newInstance(new VersionedName(symbolicName, version));
        }
        public static RegisteredTypeAsserts newInstance(VersionedName versionedName) {
            return new RegisteredTypeAsserts(versionedName);
        }
        private VersionedName versionedName;
        private VersionedName containingBundle;
        private String iconUrl;
        Iterable<VersionedName> libraryNames;
        Iterable<String> libraryUrls;
        
        RegisteredTypeAsserts(VersionedName versionedName) {
            this.versionedName = versionedName;
        }
        public RegisteredTypeAsserts containingBundle(VersionedName val) {
            containingBundle = val;
            return this;
        }
        public RegisteredTypeAsserts iconUrl(String val) {
            iconUrl = val;
            return this;
        }
        public RegisteredTypeAsserts libraryNames(VersionedName... vals) {
            libraryNames = Arrays.asList(vals);
            return this;
        }
        public RegisteredTypeAsserts libraryUrls(String... vals) {
            libraryUrls = Arrays.asList(vals);
            return this;
        }
        public void applyAsserts(BrooklynTypeRegistry typeRegistry) {
            RegisteredType type = typeRegistry.get(versionedName.getSymbolicName(), versionedName.getVersionString());
            Assert.assertNotNull(type);
            if (containingBundle != null) {
                assertEquals(type.getContainingBundle(), containingBundle.getSymbolicName()+":"+containingBundle.getVersionString());
            }
            if (iconUrl != null) {
                assertEquals(type.getIconUrl(), iconUrl);
            }
            if (libraryNames != null || libraryUrls != null) {
                Collection<OsgiBundleWithUrl> libs = type.getLibraries();
                Set<VersionedName> actualLibraryNames = new LinkedHashSet<>();
                Set<String> actualLibraryUrls = new LinkedHashSet<>();
                for (OsgiBundleWithUrl lib : libs) {
                    actualLibraryNames.add(lib.getVersionedName());
                    actualLibraryUrls.add(lib.getUrl());
                }
                if (libraryNames != null) {
                    Asserts.assertSameUnorderedContents(actualLibraryNames, libraryNames);
                }
                if (libraryUrls != null) {
                    Asserts.assertSameUnorderedContents(actualLibraryUrls, libraryUrls);
                }
            }
        }
    }
}
