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
package org.apache.brooklyn.camp.brooklyn;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.sensor.Enricher;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.entity.Dumper;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.EntityAdjuncts;
import org.apache.brooklyn.core.entity.EntityAsserts;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.objs.BrooklynObjectInternal;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.core.test.policy.TestEnricher;
import org.apache.brooklyn.enricher.stock.Propagator;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.collections.MutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

@Test
public class EnrichersYamlTest extends AbstractYamlTest {
    private static final Logger log = LoggerFactory.getLogger(EnrichersYamlTest.class);

    @Test
    public void testWithAppEnricher() throws Exception {
        Entity app = createAndStartApplication(loadYaml("test-app-with-enricher.yaml"));
        waitForApplicationTasks(app);
        Assert.assertEquals(app.getDisplayName(), "test-app-with-enricher");
        
        log.info("App started:");
        Dumper.dumpInfo(app);
        
        Assert.assertEquals(EntityAdjuncts.getNonSystemEnrichers(app).size(), 1);
        final Enricher enricher = EntityAdjuncts.getNonSystemEnrichers(app).iterator().next();
        Assert.assertTrue(enricher instanceof TestEnricher, "enricher="+enricher);
        Assert.assertEquals(enricher.getConfig(TestEnricher.CONF_NAME), "Name from YAML");
        Assert.assertEquals(enricher.getConfig(TestEnricher.CONF_FROM_FUNCTION), "$brooklyn: is a fun place");
        
        Entity target = ((EntityInternal)app).getExecutionContext().submit(MutableMap.of(), new Callable<Entity>() {
            @Override
            public Entity call() {
                return enricher.getConfig(TestEnricher.TARGET_ENTITY);
            }}).get();
        Assert.assertNotNull(target);
        Assert.assertEquals(target.getDisplayName(), "testentity");
        Assert.assertEquals(target, app.getChildren().iterator().next());
        Entity targetFromFlag = ((EntityInternal)app).getExecutionContext().submit(MutableMap.of(), new Callable<Entity>() {
            @Override
            public Entity call() {
                return enricher.getConfig(TestEnricher.TARGET_ENTITY_FROM_FLAG);
            }}).get();
        Assert.assertEquals(targetFromFlag, target);

        Map<?, ?> leftoverProperties = ((TestEnricher) enricher).getLeftoverProperties();
//        Assert.assertEquals(leftoverProperties.size(), 2);  // 2022-12 there are never any leftover properties
        Assert.assertEquals(leftoverProperties.size(), 0);

        leftoverProperties = ((BrooklynObjectInternal.ConfigurationSupportInternal)enricher.config()).getBag().getAllConfigMutable();
        Assert.assertEquals(leftoverProperties.get("enricherLiteralValue1"), "Hello");
        Assert.assertEquals(leftoverProperties.get("enricherLiteralValue2"), "World");
    }
    
    @Test
    public void testWithEntityEnricher() throws Exception {
        final Entity app = createAndStartApplication(loadYaml("test-entity-with-enricher.yaml"));
        waitForApplicationTasks(app);
        Assert.assertEquals(app.getDisplayName(), "test-entity-with-enricher");

        log.info("App started:");
        Dumper.dumpInfo(app);

        Assert.assertEquals(EntityAdjuncts.getNonSystemEnrichers(app).size(), 0);
        Assert.assertEquals(app.getChildren().size(), 1);
        final Entity child = app.getChildren().iterator().next();
        Asserts.eventually(new Supplier<Integer>() {
            @Override
            public Integer get() {
                return EntityAdjuncts.getNonSystemEnrichers(child).size();
            }
        }, Predicates.<Integer> equalTo(1));        
        final Enricher enricher = EntityAdjuncts.getNonSystemEnrichers(child).iterator().next();
        Assert.assertNotNull(enricher);
        Assert.assertTrue(enricher instanceof TestEnricher, "enricher=" + enricher + "; type=" + enricher.getClass());
        Assert.assertEquals(enricher.getConfig(TestEnricher.CONF_NAME), "Name from YAML");
        Assert.assertEquals(enricher.getConfig(TestEnricher.CONF_FROM_FUNCTION), "$brooklyn: is a fun place");

        Map<?, ?> leftoverProperties = ((TestEnricher) enricher).getLeftoverProperties();
        //        Assert.assertEquals(leftoverProperties.size(), 2);  // 2022-12 there are never any leftover properties
        Assert.assertEquals(leftoverProperties.size(), 0);

        leftoverProperties = ((BrooklynObjectInternal.ConfigurationSupportInternal)enricher.config()).getBag().getAllConfigMutable();
        Assert.assertEquals(leftoverProperties.get("enricherLiteralValue1"), "Hello");
        Assert.assertEquals(leftoverProperties.get("enricherLiteralValue2"), "World");
    }
    
    @Test
    public void testWithEntityEnricherAndParameters() throws Exception {
        Entity app = createAndStartApplication(loadYaml("test-entity-basic-template.yaml",
                    "  id: parentId",
                    "  brooklyn.parameters:",
                    "    - name: test.fqdn",
                    "      type: string",
                    "      default: \"www.example.org\"",
                    "  brooklyn.enrichers:",
                    "    - enricherType: org.apache.brooklyn.enricher.stock.Transformer",
                    "      brooklyn.config:",
                    "        enricher.triggerSensors:",
                    "          - $brooklyn:sensor(\"test.sequence\")",
                    "        enricher.targetSensor: $brooklyn:sensor(\"main.uri\")",
                    "        enricher.targetValue:",
                    "          $brooklyn:formatString:",
                    "            - \"http://%s:%d/\"",
                    "            - $brooklyn:config(\"test.fqdn\")",
                    "            - $brooklyn:attributeWhenReady(\"test.sequence\")"));
        waitForApplicationTasks(app);
        
        log.info("App started:");
        final Entity parentEntity = app.getChildren().iterator().next();
        Dumper.dumpInfo(app);
        Assert.assertTrue(parentEntity instanceof TestEntity, "Expected parent entity to be TestEntity, found:" + parentEntity);
        parentEntity.sensors().set(TestEntity.SEQUENCE, 1234);
        Asserts.eventually(Entities.attributeSupplier(parentEntity, Sensors.newStringSensor("main.uri")), Predicates.<String>equalTo("http://www.example.org:1234/"));
    }
    
    @Test
    public void testWithTransformerValueFunctionUsingDsl() throws Exception {
        // For simpler $brooklyn:object expressions, the args passed in are evaluated early
        // (i.e. when doing `config().get(TRANSFORMATION_FROM_VALUE)`)
        //
        // However, in this example the DSL is embedded inside a map, so $brooklyn:object
        // does not transform it. The function therefore returns the DSL object. It is the
        // responsibility of the Transformer to then do `resolveImmediately` to turn that DSL
        // into the literal value (or null if it can't be resolved).
        
        AttributeSensor<Object> sourceSensor = Sensors.newSensor(Object.class, "mySourceSensor");
        AttributeSensor<Object> targetSensor = Sensors.newSensor(Object.class, "myTargetSensor");
        AttributeSensor<Object> otherSensor = Sensors.newSensor(Object.class, "myOtherSensor");
        
        Entity app = createAndStartApplication(loadYaml("test-entity-basic-template.yaml",
                    "  id: parentId",
                    "  brooklyn.enrichers:",
                    "    - enricherType: org.apache.brooklyn.enricher.stock.Transformer",
                    "      brooklyn.config:",
                    "        enricher.sourceSensor: $brooklyn:sensor(\""+sourceSensor.getName()+"\")",
                    "        enricher.targetSensor: $brooklyn:sensor(\""+targetSensor.getName()+"\")",
                    "        enricher.transformation:",
                    "          $brooklyn:object:",
                    "            type: "+Functions.class.getName(),
                    "            factoryMethod.name: forMap",
                    "            factoryMethod.args:",
                    "            - \"MASTER\": $brooklyn:attributeWhenReady(\""+otherSensor.getName()+"\")",
                    "            - \"not master\""));
        waitForApplicationTasks(app);
        
        log.info("App started:");
        final TestEntity entity = (TestEntity) app.getChildren().iterator().next();
        Dumper.dumpInfo(app);
        
        entity.sensors().set(sourceSensor, "STANDBY"); // trigger enricher
        EntityAsserts.assertAttributeEqualsEventually(entity, targetSensor, "not master");
        
        entity.sensors().set(otherSensor, "myval");
        entity.sensors().set(sourceSensor, "MASTER"); // trigger enricher
        EntityAsserts.assertAttributeEqualsEventually(entity, targetSensor, "myval");
    }

    @Test
    public void testWithTransformerEventFunctionUsingDsl() throws Exception {
        // See explanation in testWithTransformerValueFunctionUsingDsl (for why this test's DSL 
        // object looks so complicated!)
        
        AttributeSensor<Object> sourceSensor = Sensors.newSensor(Object.class, "mySourceSensor");
        AttributeSensor<Object> targetSensor = Sensors.newSensor(Object.class, "myTargetSensor");
        AttributeSensor<Object> otherSensor = Sensors.newSensor(Object.class, "myOtherSensor");
        
        Entity app = createAndStartApplication(loadYaml("test-entity-basic-template.yaml",
                    "  id: parentId",
                    "  brooklyn.enrichers:",
                    "    - enricherType: org.apache.brooklyn.enricher.stock.Transformer",
                    "      brooklyn.config:",
                    "        enricher.sourceSensor: $brooklyn:sensor(\""+sourceSensor.getName()+"\")",
                    "        enricher.targetSensor: $brooklyn:sensor(\""+targetSensor.getName()+"\")",
                    "        enricher.transformation.fromevent:",
                    "          $brooklyn:object:",
                    "            type: "+EnrichersYamlTest.class.getName(),
                    "            factoryMethod.name: constantOfSingletonMapValue",
                    "            factoryMethod.args:",
                    "            - \"IGNORED\": $brooklyn:attributeWhenReady(\""+otherSensor.getName()+"\")"));
        waitForApplicationTasks(app);
        
        log.info("App started:");
        final TestEntity entity = (TestEntity) app.getChildren().iterator().next();
        Dumper.dumpInfo(app);
        
        entity.sensors().set(otherSensor, "myval");
        entity.sensors().set(sourceSensor, "any-val"); // trigger enricher
        EntityAsserts.assertAttributeEqualsEventually(entity, targetSensor, "myval");
    }
    
    public static <E> Function<Object, E> constantOfSingletonMapValue(Map<?, E> singletonMap) {
        return Functions.constant(Iterables.getOnlyElement(singletonMap.values()));
    }

    @Test
    public void testPropagatingEnricher() throws Exception {
        Entity app = createAndStartApplication(loadYaml("test-propagating-enricher.yaml"));
        waitForApplicationTasks(app);
        Assert.assertEquals(app.getDisplayName(), "test-propagating-enricher");

        log.info("App started:");
        Dumper.dumpInfo(app);
        TestEntity entity = (TestEntity)app.getChildren().iterator().next();
        entity.sensors().set(TestEntity.NAME, "New Name");
        Asserts.eventually(Entities.attributeSupplier(app, TestEntity.NAME), Predicates.<String>equalTo("New Name"));
    }
    
    @Test
    public void testPropogateChildSensor() throws Exception {
        Entity app = createAndStartApplication(loadYaml("test-entity-basic-template.yaml",
                    "  brooklyn.config:",
                    "    test.confName: parent entity",
                    "  id: parentId",
                    "  brooklyn.enrichers:",
                    "  - enricherType: org.apache.brooklyn.enricher.stock.Propagator",
                    "    brooklyn.config:",
                    "      enricher.producer: $brooklyn:component(\"childId\")",
                    "      enricher.propagating.propagatingAll: true",
                    "  brooklyn.children:",
                    "  - serviceType: org.apache.brooklyn.core.test.entity.TestEntity",
                    "    id: childId",
                    "    brooklyn.config:",
                    "      test.confName: Child Name"));
        waitForApplicationTasks(app);
        
        log.info("App started:");
        Dumper.dumpInfo(app);
        Assert.assertEquals(app.getChildren().size(), 1);
        final Entity parentEntity = app.getChildren().iterator().next();
        Assert.assertTrue(parentEntity instanceof TestEntity, "Expected parent entity to be TestEntity, found:" + parentEntity);
        Assert.assertEquals(parentEntity.getChildren().size(), 1);
        Entity childEntity = parentEntity.getChildren().iterator().next();
        Assert.assertTrue(childEntity instanceof TestEntity, "Expected child entity to be TestEntity, found:" + childEntity);
        Asserts.eventually(new Supplier<Integer>() {
            @Override
            public Integer get() {
                return EntityAdjuncts.getNonSystemEnrichers(parentEntity).size();
            }
        }, Predicates.<Integer>equalTo(1));
        Enricher enricher = EntityAdjuncts.getNonSystemEnrichers(parentEntity).iterator().next();
        Asserts.assertTrue(enricher instanceof Propagator, "Expected enricher to be Propagator, found:" + enricher);
        final Propagator propagator = (Propagator)enricher;
        Entity producer = ((EntityInternal)parentEntity).getExecutionContext().submit(MutableMap.of(), new Callable<Entity>() {
            @Override
            public Entity call() {
                return propagator.getConfig(Propagator.PRODUCER);
            }}).get();
        Assert.assertEquals(producer, childEntity);
        Asserts.assertTrue(Boolean.valueOf(propagator.getConfig(Propagator.PROPAGATING_ALL)), "Expected Propagator.PROPAGATING_ALL to be true");
        ((TestEntity)childEntity).sensors().set(TestEntity.NAME, "New Name");
        Asserts.eventually(Entities.attributeSupplier(parentEntity, TestEntity.NAME), Predicates.<String>equalTo("New Name"));
    }

    @Test
    public void testPropogateChildSensorAtRoot() throws Exception {
        Entity app = createAndStartApplication(loadYaml("test-entity-basic-template.yaml",
                "  id: c1",
                "brooklyn.enrichers:",
                "  - type: org.apache.brooklyn.enricher.stock.Propagator",
                "    brooklyn.config:",
                "      enricher.producer: $brooklyn:component(\"c1\")",
                "      enricher.propagating.inclusions: [ main.uri ]"));
        waitForApplicationTasks(app);

        log.info("App started:");
        Dumper.dumpInfo(app);
        Assert.assertEquals(app.getChildren().size(), 1);
        final Entity c1 = app.getChildren().iterator().next();
        AttributeSensor<String> mainUri = Sensors.newSensor(String.class, "main.uri");
        c1.sensors().set(mainUri, "http://foo/");
        EntityAsserts.assertAttributeEqualsEventually(app, mainUri, "http://foo/");
    }

    @Test
    public void testMultipleEnricherReferences() throws Exception {
        final Entity app = createAndStartApplication(loadYaml("test-referencing-enrichers.yaml"));
        waitForApplicationTasks(app);
        
        Entity entity1 = null, entity2 = null, child1 = null, child2 = null, grandchild1 = null, grandchild2 = null;
        
        Assert.assertEquals(app.getChildren().size(), 2);
        for (Entity child : app.getChildren()) {
            if (child.getDisplayName().equals("entity 1"))
                entity1 = child;
            if (child.getDisplayName().equals("entity 2"))
                entity2 = child;
        }
        Assert.assertNotNull(entity1);
        Assert.assertNotNull(entity2);
        
        Assert.assertEquals(entity1.getChildren().size(), 2);
        for (Entity child : entity1.getChildren()) {
            if (child.getDisplayName().equals("child 1"))
                child1 = child;
            if (child.getDisplayName().equals("child 2"))
                child2 = child;
        }
        Assert.assertNotNull(child1);
        Assert.assertNotNull(child2);
        
        Assert.assertEquals(child1.getChildren().size(), 2);
        for (Entity child : child1.getChildren()) {
            if (child.getDisplayName().equals("grandchild 1"))
               grandchild1 = child;
            if (child.getDisplayName().equals("grandchild 2"))
                grandchild2 = child;
        }
        Assert.assertNotNull(grandchild1);
        Assert.assertNotNull(grandchild2);
        
        ImmutableSet<Enricher> enrichers = new ImmutableSet.Builder<Enricher>()
                .add(getEnricher(app))
                .add(getEnricher(entity1))
                .add(getEnricher(entity2))
                .add(getEnricher(child1))
                .add(getEnricher(child2))
                .add(getEnricher(grandchild1))
                .add(getEnricher(grandchild2))
                .build();
        
        Map<ConfigKey<Entity>, Entity> keyToEntity = new ImmutableMap.Builder<ConfigKey<Entity>, Entity>()
                .put(TestReferencingEnricher.TEST_APPLICATION, app)
                .put(TestReferencingEnricher.TEST_ENTITY_1, entity1)
                .put(TestReferencingEnricher.TEST_ENTITY_2, entity2)
                .put(TestReferencingEnricher.TEST_CHILD_1, child1)
                .put(TestReferencingEnricher.TEST_CHILD_2, child2)
                .put(TestReferencingEnricher.TEST_GRANDCHILD_1, grandchild1)
                .put(TestReferencingEnricher.TEST_GRANDCHILD_2, grandchild2)
                .build();
        
        for (Enricher enricher : enrichers)
            checkReferences(enricher, keyToEntity);
    }
    
    private void checkReferences(final Enricher enricher, Map<ConfigKey<Entity>, Entity> keyToEntity) throws Exception {
        for (final ConfigKey<Entity> key : keyToEntity.keySet()) {
            final Entity entity = keyToEntity.get(key); // Grab an entity whose execution context we can use
            Entity fromConfig = ((EntityInternal)entity).getExecutionContext().submit(MutableMap.of(), new Callable<Entity>() {
                @Override
                public Entity call() throws Exception {
                    return enricher.getConfig(key);
                }
            }).get();
            Assert.assertEquals(fromConfig, keyToEntity.get(key));
        }
    }
    
    private Enricher getEnricher(Entity entity) {
        List<Enricher> enrichers = EntityAdjuncts.getNonSystemEnrichers(entity);
        Assert.assertEquals(enrichers.size(), 1, "Wrong number of enrichers: "+enrichers);
        Enricher enricher = enrichers.iterator().next();
        Assert.assertTrue(enricher instanceof TestReferencingEnricher, "Wrong enricher: "+enricher);
        return enricher;
    }
    
    @Override
    protected Logger getLogger() {
        return log;
    }
    
}
