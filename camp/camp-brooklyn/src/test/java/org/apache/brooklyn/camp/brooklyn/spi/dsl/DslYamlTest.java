/*
 * Copyright 2016 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.brooklyn.camp.brooklyn.spi.dsl;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.camp.brooklyn.AbstractYamlTest;
import org.apache.brooklyn.camp.brooklyn.spi.dsl.methods.DslTestObjects.DslTestCallable;
import org.apache.brooklyn.camp.brooklyn.spi.dsl.methods.DslTestObjects.DslTestSupplierWrapper;
import org.apache.brooklyn.camp.brooklyn.spi.dsl.methods.DslTestObjects.TestDslSupplier;
import org.apache.brooklyn.camp.brooklyn.spi.dsl.methods.DslTestObjects.TestDslSupplierValue;
import org.apache.brooklyn.camp.brooklyn.spi.dsl.methods.custom.UserSuppliedPackageType;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.Dumper;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.core.test.entity.TestApplication;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.entity.group.DynamicCluster;
import org.apache.brooklyn.entity.stock.BasicApplication;
import org.apache.brooklyn.entity.stock.BasicEntity;
import org.apache.brooklyn.entity.stock.BasicStartable;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.guava.Maybe;
import org.testng.annotations.Test;

import java.util.concurrent.ExecutionException;

import static org.testng.Assert.assertEquals;

// Doesn't test executing the DSL from different contexts (i.e. fetching the config from children inheriting it)
public class DslYamlTest extends AbstractYamlTest {
    private static final ConfigKey<Object> DEST = ConfigKeys.newConfigKey(Object.class, "dest");
    private static final ConfigKey<Object> DEST2 = ConfigKeys.newConfigKey(Object.class, "dest2");
    private static final ConfigKey<Object> DEST3 = ConfigKeys.newConfigKey(Object.class, "dest3");

    // See also test-referencing-entities.yaml

    // No tests for entitySpec, object, formatString, external - relying on extensive tests elsewhere

    @Test
    public void testDslSelf() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:self()");
        assertEquals(getConfigEventually(app, DEST), app);
    }

    @Test
    public void testDslEntity() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:entity(\"child\")",
                "  brooklyn.children:",
                "  - type: " + BasicEntity.class.getName(),
                "    id: child");
        assertEquals(getConfigEventually(app, DEST), Iterables.getOnlyElement(app.getChildren()));
    }

    @Test
    public void testDslParent() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.children:",
                "  - type: " + BasicEntity.class.getName(),
                "    brooklyn.config:",
                "      dest: $brooklyn:parent()");
        final Entity child = Iterables.getOnlyElement(app.getChildren());
        assertEquals(getConfigEventually(child, DEST), app);
    }

    @Test
    public void testDslChild() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:child(\"child\")",
                "  brooklyn.children:",
                "  - type: " + BasicEntity.class.getName(),
                "    id: child",
                "  - type: " + BasicEntity.class.getName(),
                "    id: another-child");
        assertEquals(getConfigEventually(app, DEST), app.getChildren().iterator().next());
    }

    @Test
    public void testDslSibling() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.children:",
                "  - type: " + BasicEntity.class.getName(),
                "    id: child",
                "    brooklyn.config:",
                "      dest: $brooklyn:sibling(\"another-child\")",
                "  - type: " + BasicEntity.class.getName(),
                "    id: another-child");
        final Entity child1 = Iterables.get(app.getChildren(), 0);
        final Entity child2 = Iterables.get(app.getChildren(), 1);
        assertEquals(getConfigEventually(child1, DEST), child2);
    }

    @Test
    public void testDslDescendant() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  id: self",
                "  brooklyn.config:",
                "    dest: $brooklyn:descendant(\"child\")",
                "    dest2: $brooklyn:descendant(\"grand-child\")",
                "    dest3: $brooklyn:descendant(\"self\")",
                "  brooklyn.children:",
                "  - type: " + BasicEntity.class.getName(),
                "    id: child",
                "  - type: " + BasicEntity.class.getName(),
                "    id: another-child",
                "    brooklyn.children:",
                "    - type: " + BasicEntity.class.getName(),
                "      id: grand-child");
        final Entity child1 = Iterables.get(app.getChildren(), 0);
        final Entity child2 = Iterables.get(app.getChildren(), 1);
        final Entity grandChild = Iterables.getOnlyElement(child2.getChildren());
        assertEquals(getConfigEventually(app, DEST), child1);
        assertEquals(getConfigEventually(app, DEST2), grandChild);
        try {
            assertEquals(getConfigEventually(app, DEST3), app);
            Asserts.shouldHaveFailedPreviously("Self not in descendant scope");
        } catch (Exception e) {
            Asserts.expectedFailureContainsIgnoreCase(e, "no entity match", "'self'");
        }
    }

    @Test
    public void testDslAncestor() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  id: app",
                "  brooklyn.config:",
                "    dest: $brooklyn:ancestor(\"app\")",
                "  brooklyn.children:",
                "  - type: " + BasicEntity.class.getName(),
                "    brooklyn.config:",
                "      dest: $brooklyn:ancestor(\"app\")",
                "  - type: " + BasicEntity.class.getName(),
                "    brooklyn.config:",
                "      dest: $brooklyn:ancestor(\"app\")",
                "    brooklyn.children:",
                "    - type: " + BasicEntity.class.getName(),
                "      brooklyn.config:",
                "        dest: $brooklyn:ancestor(\"app\")");
        final Entity child1 = Iterables.get(app.getChildren(), 0);
        final Entity child2 = Iterables.get(app.getChildren(), 1);
        final Entity grandChild = Iterables.getOnlyElement(child2.getChildren());
        assertEquals(getConfigEventually(child1, DEST), app);
        assertEquals(getConfigEventually(child2, DEST), app);
        assertEquals(getConfigEventually(grandChild, DEST), app);
        try {
            assertEquals(getConfigEventually(app, DEST), app);
            Asserts.shouldHaveFailedPreviously("App not in ancestor scope");
        } catch (Exception e) {
            Asserts.expectedFailureContainsIgnoreCase(e, "no entity match", "'app'");
        }
    }

    @Test
    public void testDslRoot() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  id: app",
                "  brooklyn.config:",
                "    dest: $brooklyn:root()",
                "  brooklyn.children:",
                "  - type: " + BasicEntity.class.getName(),
                "    brooklyn.config:",
                "      dest: $brooklyn:root()",
                "  - type: " + BasicEntity.class.getName(),
                "    brooklyn.config:",
                "      dest: $brooklyn:root()",
                "    brooklyn.children:",
                "    - type: " + BasicEntity.class.getName(),
                "      brooklyn.config:",
                "        dest: $brooklyn:root()");
        final Entity child1 = Iterables.get(app.getChildren(), 0);
        final Entity child2 = Iterables.get(app.getChildren(), 1);
        final Entity grandChild = Iterables.getOnlyElement(child2.getChildren());
        assertEquals(getConfigEventually(child1, DEST), app);
        assertEquals(getConfigEventually(child2, DEST), app);
        assertEquals(getConfigEventually(grandChild, DEST), app);
        assertEquals(getConfigEventually(app, DEST), app);
    }

    @Test
    public void testDslScopeRoot() throws Exception {
        addCatalogItems(
                "brooklyn.catalog:",
                "  version: " + TEST_VERSION,
                "  items:",
                "  - id: simple-item",
                "    itemType: entity",
                "    item:",
                "      type: "+ BasicEntity.class.getName(),
                "  - id: wrapping-plain",
                "    itemType: entity",
                "    item:",
                "      type: "+ BasicEntity.class.getName(),
                "      brooklyn.children:",
                "      - type: " + BasicEntity.class.getName(),
                "        brooklyn.config:",
                "          dest: $brooklyn:scopeRoot()",
                "  - id: wrapping-simple",
                "    itemType: entity",
                "    item:",
                "      type: "+ BasicEntity.class.getName(),
                "      brooklyn.children:",
                "      - type: simple-item",
                "        brooklyn.config:",
                "          dest: $brooklyn:scopeRoot()");

        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.children:",
                "  - type: wrapping-plain",
                "  - type: wrapping-simple");
        Entity child1 = Iterables.get(app.getChildren(), 0);
        Entity child2 = Iterables.get(app.getChildren(), 1);
        assertChildScopeRootReferenceIs(child1);
        assertChildScopeRootReferenceIs(child2);
    }

    private void assertChildScopeRootReferenceIs(Entity entity) throws Exception {
        Entity child = Iterables.getOnlyElement(entity.getChildren());
        assertEquals(getConfigEventually(child, DEST), entity);
    }

    @Test
    public void testDslScopeRootEdgeCases() throws Exception {
        addCatalogItems(
                "brooklyn.catalog:",
                "  version: " + TEST_VERSION,
                "  items:",
                "  - id: simple-item",
                "    itemType: entity",
                "    item:",
                "      type: "+ BasicEntity.class.getName(),
                "      brooklyn.config:",
                "        v: 1",
                "        refInDsl: $brooklyn:formatString(\"%s\", scopeRoot().config(\"v\"))",
                "        refInMap:",
                "          v: $brooklyn:scopeRoot().config(\"v\")");

        final Entity app = createAndStartApplication(
                "services:",
                "- type: simple-item",
                "brooklyn.config:",
                "  v: 2");
        Entity child = Iterables.get(app.getChildren(), 0);
        // TODO - these should both be 1, but scopeRoot for the simple-item goes to the blueprint where it is used; see notes in CampResolver.fixScopeRoot
        Asserts.assertEquals( child.getConfig(ConfigKeys.newConfigKey(Object.class, "refInDsl")), "2" );
        Asserts.assertEquals( child.getConfig(ConfigKeys.newConfigKey(Object.class, "refInMap")), MutableMap.of("v", 2) );
    }

    @Test
    public void testDslConfig() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    source: myvalue",
                "    dest: $brooklyn:config(\"source\")");
        assertEquals(getConfigEventually(app, DEST), "myvalue");
    }

    @Test
    public void testDslConfigInMap() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    source: myvalue",
                "    dest:",
                "      key: $brooklyn:config(\"source\")");
        assertEquals(getConfigEventually(app, DEST), MutableMap.of("key", "myvalue"));
    }

    @Test
    public void testDslConfigOnEntity() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:entity(\"sourceEntity\").config(\"source\")",
                "  brooklyn.children:",
                "  - type: " + BasicEntity.class.getName(),
                "    id: sourceEntity",
                "    brooklyn.config:",
                "      source: myvalue");
        assertEquals(getConfigEventually(app, DEST), "myvalue");
    }

    @Test
    public void testDslConfigWithDeferredArg() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    source: myvalue",
                "    configName: source",
                "    dest: $brooklyn:config(config(\"configName\"))");
        assertEquals(getConfigEventually(app, DEST), "myvalue");
    }

    @Test
    public void testDslConfigOnEntityWithDeferredArgAsEntityId() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    entityName: sourceEntity",
                "    configName: source",
                "    dest: $brooklyn:entity(config(\"entityName\")).config(config(\"configName\"))",
                "  brooklyn.children:",
                "  - type: " + BasicEntity.class.getName(),
                "    id: sourceEntity",
                "    brooklyn.config:",
                "      source: myvalue");
        assertEquals(getConfigEventually(app, DEST), "myvalue");
    }

    @Test
    public void testDslConfigOnEntityWithDeferredArgAsEntity() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    entityValue: $brooklyn:entity(\"sourceEntity\")",
                "    configName: source",
                "    dest: $brooklyn:entity(config(\"entityValue\")).config(config(\"configName\"))",
                "  brooklyn.children:",
                "  - type: " + BasicEntity.class.getName(),
                "    id: sourceEntity",
                "    brooklyn.config:",
                "      source: myvalue");
        assertEquals(getConfigEventually(app, DEST), "myvalue");
    }

    @Test
    public void testDslAttributeWhenReady() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.initializers:",
                "  - type: org.apache.brooklyn.core.sensor.StaticSensor",
                "    brooklyn.config:",
                "      name: source",
                "      static.value: myvalue",
                "  brooklyn.config:",
                "    dest: $brooklyn:attributeWhenReady(\"source\")");
        assertEquals(getConfigEventually(app, DEST), "myvalue");
    }

    @Test
    public void testDslAttributeWhenReadyOnEntity() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:entity(\"sourceEntity\").attributeWhenReady(\"source\")",
                "  brooklyn.children:",
                "  - type: " + BasicEntity.class.getName(),
                "    id: sourceEntity",
                "    brooklyn.initializers:",
                "    - type: org.apache.brooklyn.core.sensor.StaticSensor",
                "      brooklyn.config:",
                "        name: source",
                "        static.value: myvalue");
        assertEquals(getConfigEventually(app, DEST), "myvalue");
    }

    @Test
    public void testDslAttributeWhenReadyWithDeferredArg() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.initializers:",
                "  - type: org.apache.brooklyn.core.sensor.StaticSensor",
                "    brooklyn.config:",
                "      name: source",
                "      static.value: myvalue",
                "  brooklyn.config:",
                "    configName: source",
                "    dest: $brooklyn:attributeWhenReady(config(\"configName\"))");
        assertEquals(getConfigEventually(app, DEST), "myvalue");
    }

    @Test
    public void testDslAttributeWhenReadyOnEntityWithDeferredArg() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    entityName: sourceEntity",
                "    configName: source",
                "    dest: $brooklyn:entity(config(\"entityName\")).attributeWhenReady(config(\"configName\"))",
                "  brooklyn.children:",
                "  - type: " + BasicEntity.class.getName(),
                "    id: sourceEntity",
                "    brooklyn.initializers:",
                "    - type: org.apache.brooklyn.core.sensor.StaticSensor",
                "      brooklyn.config:",
                "        name: source",
                "        static.value: myvalue");
        assertEquals(getConfigEventually(app, DEST), "myvalue");
    }
    
    @Test
    public void testDslEntityId() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:entityId()");
        assertEquals(getConfigEventually(app, DEST), app.getId());
    }

    @Test
    public void testDslEntityIdOnEntity() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:entity(\"sourceEntity\").entityId()",
                "  brooklyn.children:",
                "  - type: " + BasicEntity.class.getName(),
                "    id: sourceEntity");
        final Entity child = Iterables.getOnlyElement(app.getChildren());
        assertEquals(getConfigEventually(app, DEST), child.getId());
    }

    @Test
    public void testDslSensor() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + TestApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:sensor(\"test.myattribute\")");
        assertEquals(getConfigEventually(app, DEST), TestApplication.MY_ATTRIBUTE);
    }

    @Test
    public void testDslSensorOnEntity() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:entity(\"sourceEntity\").sensor(\"test.myattribute\")",
                "  brooklyn.children:",
                "  - type: " + TestApplication.class.getName(),
                "    id: sourceEntity");
        assertEquals(getConfigEventually(app, DEST), TestApplication.MY_ATTRIBUTE);
    }

    @Test
    public void testDslSensorWithClass() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:sensor(\"org.apache.brooklyn.core.test.entity.TestApplication\", \"test.myattribute\")");
        assertEquals(getConfigEventually(app, DEST), TestApplication.MY_ATTRIBUTE);
    }

    @Test
    public void testDslLiteral() throws Exception {
        final String literal = "custom(), $brooklyn:root(), invalid; syntax";
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + TestApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:literal(\"" + literal + "\")");
        assertEquals(getConfigEventually(app, DEST), literal);
    }

    @Test
    public void testDslRegexReplacement() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + TestApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:regexReplacement(\"Broooklyn\", \"o+\", \"oo\")");
        assertEquals(getConfigEventually(app, DEST), "Brooklyn");
    }

    @Test
    public void testDslRegexReplacementWithDeferredArg() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + TestApplication.class.getName(),
                "  brooklyn.config:",
                "    source: Broooklyn",
                "    pattern: o+",
                "    replacement: oo",
                "    dest: $brooklyn:regexReplacement(config(\"source\"), config(\"pattern\"), config(\"replacement\"))");
        assertEquals(getConfigEventually(app, DEST), "Brooklyn");
    }

    @Test
    public void testDslFunctionRegexReplacement() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + TestApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:function.regexReplacement(\"o+\", \"oo\")");
        @SuppressWarnings("unchecked")
        Function<String, String> replacementFn = (Function<String, String>) getConfigEventually(app, DEST);
        assertEquals(replacementFn.apply("Broooklyn"), "Brooklyn");
    }

    @Test
    public void testDslFunctionRegexReplacementWithDeferredArg() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + TestApplication.class.getName(),
                "  brooklyn.config:",
                "    source: Broooklyn",
                "    pattern: o+",
                "    replacement: oo",
                "    dest: $brooklyn:function.regexReplacement(config(\"pattern\"), config(\"replacement\"))");
        @SuppressWarnings("unchecked")
        Function<String, String> replacementFn = (Function<String, String>) getConfigEventually(app, DEST);
        assertEquals(replacementFn.apply("Broooklyn"), "Brooklyn");
    }

    @Test
    public void testDslNonDeferredInvalidMethod() throws Exception {
        try {
            createAndStartApplication(
                    "services:",
                    "- type: " + BasicApplication.class.getName(),
                    "  brooklyn.config:",
                    "    dest: $brooklyn:self().invalidMethod()");
            Asserts.shouldHaveFailedPreviously("Non-existing non-deferred method should fail deployment");
        } catch (Exception e) {
            Asserts.expectedFailureContains(e, "No such function 'invalidMethod'");
        }
    }

    public static class InaccessibleType {
        public static boolean doesFail() {return true;}
        @DslAccessible
        public static boolean doesSucceed() {return true;}
    }

    @Test
    public void testDeferredDslInaccessibleCall() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:config(\"targetValue\").doesFail()");
        app.config().set(ConfigKeys.newConfigKey(InaccessibleType.class, "targetValue"), new InaccessibleType());
        try {
            getConfigEventually(app, DEST);
            Asserts.shouldHaveFailedPreviously("Outside of allowed package scope");
        } catch (ExecutionException e) {
            Asserts.expectedFailureContains(e, "(outside allowed package scope)");
        }
    }

    @Test
    public void testDeferredDslAccessible() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:config(\"targetValue\").doesSucceed()");
        app.config().set(ConfigKeys.newConfigKey(InaccessibleType.class, "targetValue"), new InaccessibleType());
        assertEquals(getConfigEventually(app, DEST), Boolean.TRUE);
    }

    @Test
    public void testDeferredDslWhiteListPackage() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:config(\"targetValue\").isSupplierEvaluated()");
        app.config().set(ConfigKeys.newConfigKey(TestDslSupplierValue.class, "targetValue"), new TestDslSupplierValue());
        assertEquals(getConfigEventually(app, DEST), Boolean.TRUE);
    }

    @Test
    public void testDeferredDslUserSuppliedPackage() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:config(\"targetValue\").isEvaluated()");
        app.config().set(ConfigKeys.newConfigKey(UserSuppliedPackageType.class, "targetValue"), new UserSuppliedPackageType());
        assertEquals(getConfigEventually(app, DEST), Boolean.TRUE);
    }

    @Test
    public void testDeferredDslChainingOnConfig() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:config(\"targetValue\").isSupplierEvaluated()");
        app.config().set(ConfigKeys.newConfigKey(TestDslSupplierValue.class, "targetValue"), new TestDslSupplierValue());
        assertEquals(getConfigEventually(app, DEST), Boolean.TRUE);
    }

    @Test
    public void testDeferredDslChainingDslComponent() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:config(\"targetValue\").self().attributeWhenReady(\"entity.id\")");
        app.config().set(ConfigKeys.newConfigKey(TestDslSupplierValue.class, "targetValue"), new TestDslSupplierValue());
        assertEquals(getConfigEventually(app, DEST), app.getId());
    }

    @Test
    public void testDeferredDslChainingOnConfigNoFunction() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:config(\"targetValue\").getNonExistent()");
        ConfigKey<TestDslSupplierValue> targetValueKey = ConfigKeys.newConfigKey(TestDslSupplierValue.class, "targetValue");
        app.config().set(targetValueKey, new TestDslSupplierValue());
        try {
            assertEquals(getConfigEventually(app, DEST), app.getId());
            Asserts.shouldHaveFailedPreviously("Expected to fail because method does not exist");
        } catch (Exception e) {
            Asserts.expectedFailureContains(e, "No such function 'getNonExistent'");
            Asserts.expectedFailureDoesNotContain(e, "$brooklyn:$brooklyn:");
        }
    }

    @Test
    public void testDeferredDslChainingOnSensor() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:attributeWhenReady(\"targetValue\").isSupplierEvaluated()");
        AttributeSensor<TestDslSupplierValue> targetValueSensor = Sensors.newSensor(TestDslSupplierValue.class, "targetValue");
        app.sensors().set(targetValueSensor, new TestDslSupplierValue());
        assertEquals(getConfigEventually(app, DEST), Boolean.TRUE);
    }

    @Test(groups="Integration")  // because takes 3 seconds ... not sure why!?
    public void testDeferredDslObjectAsFirstArgument() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  location: localhost",
                "  brooklyn.config:",
                "    dest: $brooklyn:attributeWhenReady(\"targetValue\").config(\"spec.final\")");
        AttributeSensor<Location> targetValueSensor = Sensors.newSensor(Location.class, "targetValue");
        app.sensors().set(targetValueSensor, Iterables.getOnlyElement(app.getLocations()));
        assertEquals(getConfigEventually(app, DEST), "localhost");
    }

    
    @Test
    public void testDeferredDslAttributeFacade() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:attributeWhenReady(\"targetEntity\").attributeWhenReady(\"entity.id\")");
        AttributeSensor<Entity> targetEntitySensor = Sensors.newSensor(Entity.class, "targetEntity");
        app.sensors().set(targetEntitySensor, app);
        assertEquals(getConfigEventually(app, DEST), app.getId());
    }

    @Test
    public void testDeferredDslConfigFacade() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    testValue: myvalue",
                "    targetEntity: $brooklyn:self()",
                "    dest: $brooklyn:config(\"targetEntity\").config(\"testValue\")");
        AttributeSensor<Entity> targetEntitySensor = Sensors.newSensor(Entity.class, "targetEntity");
        app.sensors().set(targetEntitySensor, app);
        assertEquals(getConfigEventually(app, DEST), "myvalue");
    }

    @Test
    public void testDeferredDslConfigFacadeCrossAppFails() throws Exception {
        final Entity app0 = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName());
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:config(\"targetEntity\").attributeWhenReady(\"entity.id\")");
        app.config().set(ConfigKeys.newConfigKey(Entity.class, "targetEntity"), app0);
        try {
            getConfigEventually(app, DEST);
            Asserts.shouldHaveFailedPreviously("Cross-app DSL not allowed");
        } catch (ExecutionException e) {
            Asserts.expectedFailureContains(e, "not in scope 'global'");
        }
    }

    @Test
    public void testDeferredDslAttributeFacadeCrossAppFails() throws Exception {
        final Entity app0 = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName());
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:attributeWhenReady(\"targetEntity\").attributeWhenReady(\"entity.id\")");
        AttributeSensor<Entity> targetEntitySensor = Sensors.newSensor(Entity.class, "targetEntity");
        app.sensors().set(targetEntitySensor, app0);
        try {
            getConfigEventually(app, DEST);
            Asserts.shouldHaveFailedPreviously("Cross-app DSL not allowed");
        } catch (ExecutionException e) {
            Asserts.expectedFailureContains(e, "not in scope 'global'");
        }
    }

    @Test
    public void testDeferredDslChainingOnNullConfig() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:config(\"targetEntity\").getId()");
        try {
            assertEquals(getConfigEventually(app, DEST), app.getId());
            Asserts.shouldHaveFailedPreviously("Expected to fail because targetEntity config is null");
        } catch (Exception e) {
            Asserts.expectedFailureContains(e, "config(\"targetEntity\") evaluates to null");
        }
    }

    @Test
    public void testDeferredDslChainingWithCustomSupplier() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:config(\"customSupplierWrapper\").getSupplier().isSupplierEvaluated()");
        ConfigKey<DslTestSupplierWrapper> customSupplierWrapperKey = ConfigKeys.newConfigKey(DslTestSupplierWrapper.class, "customSupplierWrapper");
        app.config().set(customSupplierWrapperKey, new DslTestSupplierWrapper(new TestDslSupplier(new TestDslSupplierValue())));
        assertEquals(getConfigEventually(app, DEST), Boolean.TRUE);
    }

    // TODO this and the next method used to work with DslTestCallableAlsoSupplier; but now if it is a supplier we get it when resolving,
    // otherwise we don't get the values we want in certain cases; things should be DslFunctionSource or DeferredSupplier but not both.
    // that might however cause problems when we try to evaluate $brooklyn:component("xxx").attributeWhenReady() if we get the entity from the first one
    @Test
    public void testDeferredDslChainingWithCustomCallable() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:config(\"customCallableWrapper\").getSupplier().isSupplierCallable()");
        ConfigKey<DslTestSupplierWrapper> customCallableWrapperKey = ConfigKeys.newConfigKey(DslTestSupplierWrapper.class, "customCallableWrapper");
        app.config().set(customCallableWrapperKey, new DslTestSupplierWrapper(new DslTestCallable()));
        assertEquals(getConfigEventually(app, DEST), Boolean.TRUE);
    }
    @Test
    public void testDeferredDslChainingWithNestedEvaluation() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:config(\"customCallableWrapper\").getSupplier().isSupplierCallable()");
        ConfigKey<TestDslSupplier> customCallableWrapperKey = ConfigKeys.newConfigKey(TestDslSupplier.class, "customCallableWrapper");
        app.config().set(customCallableWrapperKey, new TestDslSupplier(new DslTestSupplierWrapper(new DslTestCallable())));
        assertEquals(getConfigEventually(app, DEST), Boolean.TRUE);
    }

    @Test
    public void testDslTemplate() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    test.sourceName: hello world",
                "    dest: $brooklyn:template(\"${config['test.sourceName']}\")");
        assertEquals(getConfigEventually(app, DEST), "hello world");
    }

    @Test
    public void testDslTemplateSubstitutions() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    test.sourceName: hello world",
                "    test.substituteValue: and all",
                "    dest: ",
                "      $brooklyn:template:",
                "      - ${config['test.sourceName']} ${key}",
                "      - $brooklyn:literal(\"key\"): $brooklyn:config(\"test.substituteValue\")");
        assertEquals(getConfigEventually(app, DEST), "hello world and all");
    }

    @Test
    public void testDslTemplateOnEntity() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:entity(\"configSource\").template(\"${config['test.sourceName']}\")",
                "- type: " + BasicApplication.class.getName(),
                "  id: configSource",
                "  brooklyn.config:",
                "    test.sourceName: hello world");
        assertEquals(getConfigEventually(app.getChildren().iterator().next(), DEST), "hello world");
    }

    @Test
    public void testDslMultilineTemplate() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    test.sourceName: hello world",
                "    dest: ",
                "      $brooklyn:template:",
                "      - |",
                "        ${config['test.sourceName']}");
        assertEquals(getConfigEventually(app, DEST), "hello world");
    }

    @Test
    public void testDslTemplateOverDsl() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    test.value: hello world",
                "    test.sourceDsl: $brooklyn:config(\"test.value\")",
                "    test.sourceTemplate: ${config['test.sourceDsl']}",
                "    dest: $brooklyn:template($brooklyn:config(\"test.sourceTemplate\"))");
        assertEquals(getConfigEventually(app, DEST), "hello world");
    }

    @Test
    public void testDslSelectorFromList() throws Exception {
        final Entity app = createAndStartApplication(
            "services:",
            "- type: " + BasicApplication.class.getName(),
            "  brooklyn.config:",
            "    key1: [a,b,c]",
            "    key2: $brooklyn:config(\"key1\")[1]");
        Dumper.dumpInfo(app);

        assertEquals(getConfigEventually(app, ConfigKeys.newConfigKey(Object.class, "key2")),
            "b");
    }

    @Test
    public void testDslSelectorFromMap() throws Exception {
        final Entity app = createAndStartApplication(
            "services:",
            "- type: " + BasicApplication.class.getName(),
            "  brooklyn.config:",
            "    key1: {a: 3}",
            "    key2: $brooklyn:config(\"key1\")[\"a\"]");
        Dumper.dumpInfo(app);
        assertEquals(
            getConfigEventually(app, ConfigKeys.newConfigKey(Object.class, "key2")),
            3);
    }

    @Test
    public void testDslSelectorFromMapOfLists() throws Exception {
        final Entity app = createAndStartApplication(
            "services:",
            "- type: " + BasicApplication.class.getName(),
            "  brooklyn.config:",
            "    key1: {a: [1,2,3], b: [4,5,6] }",
            "    key2: $brooklyn:config(\"key1\")[\"b\"][2]");
        Dumper.dumpInfo(app);
        assertEquals(
            getConfigEventually(app, ConfigKeys.newConfigKey(Object.class, "key2")),
            6);
    }

    @Test
    public void testDslRecursiveFails() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    dest: $brooklyn:config(\"dest\")");
        try {
            getConfigEventually(app, DEST);
            Asserts.shouldHaveFailedPreviously();
        } catch (Exception e) {
            Asserts.expectedFailureContains(e, "Recursive reference DSL:entity('", "').config('dest')");
        }
    }

    @Test
    public void testDslRecursiveFails2() throws Exception {
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    val: $brooklyn:config(\"dest\")",
                "    dest: $brooklyn:config(\"val\")");
        try {
            getConfigEventually(app, DEST);
            Asserts.shouldHaveFailedPreviously();
        } catch (Exception e) {
            Asserts.expectedFailureContains(e, "Recursive reference DSL:entity('", "').config('val')");
        }
    }

    @Test
    public void testDslFromParentConfigNotRecursive() throws Exception {
        // Broke this in https://github.com/apache/brooklyn-server/pull/971.
        // It thought that the '$brooklyn:parent().config("test.value")' was the
        // same each time, rather than 'parent' resolving differently each time.
        
        final Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicApplication.class.getName(),
                "  brooklyn.config:",
                "    test.value: 0",
                "  brooklyn.children:",
                "    - type: "+BasicStartable.class.getName(),
                "      brooklyn.config:",
                "        test.value: $brooklyn:parent().config(\"test.value\")",
                "      brooklyn.children:",
                "        - type: "+DynamicCluster.class.getName(),
                "          brooklyn.config:",
                "            cluster.initial.size: $brooklyn:parent().config(\"test.value\")",
                "            memberSpec:",
                "              $brooklyn:entitySpec:",
                "                type: "+TestEntity.class.getName());
        
        BasicStartable child = (BasicStartable) Iterables.getOnlyElement(app.getChildren());
        DynamicCluster cluster = (DynamicCluster) Iterables.getOnlyElement(child.getChildren());
        assertEquals(cluster.config().get(DynamicCluster.INITIAL_SIZE), Integer.valueOf(0));
    }

    private static <T> T getConfigEventually(final Entity entity, final ConfigKey<T> configKey) throws Exception {
        return (T) Entities.submit(entity, Tasks.builder().body(() -> {
            T blockingValue = entity.config().get(configKey);
            Maybe<T> immediateValue = ((EntityInternal)entity).config().getNonBlocking(configKey);
            assertEquals(immediateValue.get(), blockingValue);
            return blockingValue;
        }).build()).get();
    }
}
