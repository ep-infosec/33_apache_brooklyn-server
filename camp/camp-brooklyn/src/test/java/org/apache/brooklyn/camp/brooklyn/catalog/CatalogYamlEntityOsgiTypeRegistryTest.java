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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.camp.brooklyn.spi.creation.CampTypePlanTransformer;
import org.apache.brooklyn.core.catalog.internal.BasicBrooklynCatalog;
import org.apache.brooklyn.core.mgmt.BrooklynTags;
import org.apache.brooklyn.core.mgmt.BrooklynTags.SpecSummary;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.core.typereg.RegisteredTypePredicates;
import org.apache.brooklyn.entity.stock.BasicEntity;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.collections.CollectionFunctionals;
import org.apache.brooklyn.util.osgi.VersionedName;
import org.apache.brooklyn.util.yaml.Yamls;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** Variant of parent tests using OSGi, bundles, and type registry, instead of lightweight non-osgi catalog */
@Test
public class CatalogYamlEntityOsgiTypeRegistryTest extends CatalogYamlEntityTest {

    // use OSGi here
    @Override protected boolean disableOsgi() { return false; }
    
    enum CatalogItemsInstallationMode { 
        ADD_YAML_ITEMS_UNBUNDLED, 
        BUNDLE_BUT_NOT_STARTED, 
        USUAL_OSGI_WAY_AS_BUNDLE_WITH_DEFAULT_NAME, 
        USUAL_OSGI_WAY_AS_ZIP_NO_MANIFEST_NAME_MAYBE_IN_BOM 
    }
    CatalogItemsInstallationMode itemsInstallMode = null;
    
    // use type registry approach
    @Override
    protected Collection<RegisteredType> addCatalogItems(String catalogYaml) {
        boolean skipStart = false;

        switch (itemsInstallMode!=null ? itemsInstallMode : 
            // this is the default because some "bundles" aren't resolvable or library BOMs loadable in test context
            CatalogItemsInstallationMode.BUNDLE_BUT_NOT_STARTED) {
        case ADD_YAML_ITEMS_UNBUNDLED:
            super.addCatalogItems(catalogYaml);
            break;
        case BUNDLE_BUT_NOT_STARTED:
            skipStart = true;
            // continue to below
        case USUAL_OSGI_WAY_AS_BUNDLE_WITH_DEFAULT_NAME:
            String bundle = bundleName();
            String version = bundleVersion();
            Map<?, ?> cy = (Map<?, ?>) Yamls.parseAll(catalogYaml).iterator().next();
            cy = (Map<?, ?>) cy.get("brooklyn.catalog");
            if (cy.containsKey("bundle")) bundle = (String)cy.get("bundle");
            if (cy.containsKey("version")) version = (String)cy.get("version");
            if (skipStart) {
                addCatalogItemsAsOsgiWithoutStartingBundles(mgmt(), catalogYaml, new VersionedName(bundle, version), isForceUpdate());
            } else {
                addCatalogItemsAsOsgiInUsualWay(mgmt(), catalogYaml, new VersionedName(bundle, version), isForceUpdate());
            }
            break;
        case USUAL_OSGI_WAY_AS_ZIP_NO_MANIFEST_NAME_MAYBE_IN_BOM:
            addCatalogItemsAsOsgiInUsualWay(mgmt(), catalogYaml, null, isForceUpdate());
            break;
        }
        return null;
    }

    protected String bundleName() { return "sample-bundle"; }
    protected String bundleVersion() { return BasicBrooklynCatalog.NO_VERSION; }
    
    @Override
    protected void doTestReplacementFailureLeavesPreviousIntact(boolean bundleHasId) throws Exception {
        try {
            itemsInstallMode = bundleHasId ? CatalogItemsInstallationMode.USUAL_OSGI_WAY_AS_ZIP_NO_MANIFEST_NAME_MAYBE_IN_BOM : 
                CatalogItemsInstallationMode.ADD_YAML_ITEMS_UNBUNDLED;
            super.doTestReplacementFailureLeavesPreviousIntact(bundleHasId);
        } finally {
            itemsInstallMode = null;
        }
    }
    
    @Test   // basic test that this approach to adding types works
    public void testAddTypes() throws Exception {
        String symbolicName = "my.catalog.app.id.load";
        addCatalogEntity(IdAndVersion.of(symbolicName, TEST_VERSION), BasicEntity.class.getName());

        Iterable<RegisteredType> itemsInstalled = mgmt().getTypeRegistry().getMatching(RegisteredTypePredicates.containingBundle(new VersionedName(symbolicName, TEST_VERSION)));
        Asserts.assertSize(itemsInstalled, 1);
        RegisteredType item = mgmt().getTypeRegistry().get(symbolicName, TEST_VERSION);
        Asserts.assertEquals(item, Iterables.getOnlyElement(itemsInstalled), "Wrong item; installed: "+itemsInstalled);
    }

    @Test // test disabled as "broken" in super but works here
    public void testSameCatalogReferences() {
        super.testSameCatalogReferences();
    }

    @Test
    public void testUpdatingItemAllowedIfEquivalentUnderRewrite() {
        String symbolicName = "my.catalog.app.id.duplicate";
        // forward reference supported here (but not in super)
        // however the plan is rewritten meaning a second install requires special comparison
        // (RegisteredTypes "equivalent plan" methods)
        addForwardReferencePlan(symbolicName);

        // delete one but not the other to prevent resolution and thus rewrite until later validation phase,
        // thus initial addition will compare unmodified plan from here against modified plan added above;
        // replacement will then succeed only if we've correctly recorded equivalence tags 
        deleteCatalogRegisteredType("forward-referenced-entity");
        
        addForwardReferencePlan(symbolicName);
    }

    protected void addForwardReferencePlan(String symbolicName) {
        addCatalogItems(
            "brooklyn.catalog:",
            "  id: " + symbolicName,
            "  version: " + TEST_VERSION,
            "  itemType: entity",
            "  items:",
            "  - id: " + symbolicName,
            "    itemType: entity",
            "    item:",
            "      type: forward-referenced-entity",
            "  - id: " + "forward-referenced-entity",
            "    itemType: entity",
            "    item:",
            "      type: " + TestEntity.class.getName());
    }
    
    // tags supported only with osgi catalog
    
    @Test
    public void testAddCatalogItemWithTags() throws Exception {
        String symbolicName = "my.catalog.app.id.load";
        addCatalogItems(
            "brooklyn.catalog:",
            "  id: " + symbolicName,
            "  version: " + TEST_VERSION,
            "  tags: [ foo, bar ]",
            "  itemType: entity",
            "  item: " + BasicEntity.class.getName());

        RegisteredType item = mgmt().getTypeRegistry().get(symbolicName, TEST_VERSION);
        Asserts.assertThat(item.getTags(), CollectionFunctionals.contains("foo"));
        Asserts.assertThat(item.getTags(), CollectionFunctionals.contains("bar"));
        Asserts.assertThat(item.getTags(), Predicates.not(CollectionFunctionals.contains("baz")));

        deleteCatalogRegisteredType(symbolicName);
    }
    @Test
    public void testAddCatalogItemWithInheritedTags() throws Exception {
        String symbolicName = "my.catalog.app.id.load";
        addCatalogItems(
            "brooklyn.catalog:",
            "  version: " + TEST_VERSION,
            "  tags: [ foo ]",
            "  items:",
            "  - ",
            "    id: " + symbolicName,
            "    tags: [ bar ]",
            "    itemType: entity",
            "    item: " + BasicEntity.class.getName());

        RegisteredType item = mgmt().getTypeRegistry().get(symbolicName, TEST_VERSION);
        Asserts.assertThat(item.getTags(), CollectionFunctionals.contains("foo"));
        Asserts.assertThat(item.getTags(), CollectionFunctionals.contains("bar"));
        Asserts.assertThat(item.getTags(), Predicates.not(CollectionFunctionals.contains("baz")));

        deleteCatalogRegisteredType(symbolicName);
    }

    @Test
    public void testAddCatalogItemWithNewLinesInTagAndDescription() throws Exception {
        String symbolicName = "my.catalog.app.id.load";
        addCatalogItems(
                "brooklyn.catalog:",
                "  id: " + symbolicName,
                "  version: " + TEST_VERSION,
                "  description: \"new-\\nline\"",
                "  tags:",
                "  - description.md: |",
                "       Line 1",
                "       Line **2**",
                "  itemType: entity",
                "  item: " + BasicEntity.class.getName());

        RegisteredType item = mgmt().getTypeRegistry().get(symbolicName, TEST_VERSION);
        Asserts.assertEquals(item.getDescription(), "new-\nline");
        Object docTag = item.getTags().stream().filter(x -> x instanceof Map && ((Map) x).containsKey("description.md")).findFirst().orElse(null);
        Asserts.assertEquals(((Map) docTag).get("description.md"), "Line 1\nLine **2**\n");

        deleteCatalogRegisteredType(symbolicName);
    }

    @Test
    public void testAddCatalogItemWithHierarchyTag() throws Exception {
        String symbolicName = "my.catalog.app.id.load";
        addCatalogItems(
                "brooklyn.catalog:",
                "  id: " + symbolicName,
                "  version: " + TEST_VERSION,
                "  tags:",
                "  - "+ BrooklynTags.SPEC_HIERARCHY +": ",
                "         - format: " + CampTypePlanTransformer.FORMAT,
                "           summary:  Plan for " + symbolicName,
                "           contents:  | " ,
                "               line 1",
                "               line 2",
                "  itemType: entity",
                "  item: " + BasicEntity.class.getName());

        RegisteredType item = mgmt().getTypeRegistry().get(symbolicName, TEST_VERSION);

        List<SpecSummary> specTag = BrooklynTags.findSpecHierarchyTag(item.getTags());
        Assert.assertNotNull(specTag);
        assertEquals(specTag.size(), 1);

        Asserts.assertEquals(specTag.get(0).format, CampTypePlanTransformer.FORMAT);
        Asserts.assertEquals(specTag.get(0).summary, "Plan for " + symbolicName);
        deleteCatalogRegisteredType(symbolicName);
    }


    // also runs many other tests from super, here using the osgi/type-registry appraoch

    @Test
    public void testCatalogItemIdInReferencedItems() throws Exception {
        super.testCatalogItemIdInReferencedItems();
    }
}
