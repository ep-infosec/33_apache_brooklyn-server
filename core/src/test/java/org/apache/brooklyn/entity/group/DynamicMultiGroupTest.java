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
package org.apache.brooklyn.entity.group;

import static com.google.common.base.Predicates.instanceOf;
import static com.google.common.collect.Iterables.find;
import static org.apache.brooklyn.core.entity.EntityPredicates.displayNameEqualTo;
import static org.apache.brooklyn.entity.group.DynamicGroup.ENTITY_FILTER;
import static org.apache.brooklyn.entity.group.DynamicMultiGroup.*;
import static org.apache.brooklyn.entity.group.DynamicMultiGroupImpl.bucketFromAttribute;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.entity.Group;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.sensor.SensorEvent;
import org.apache.brooklyn.api.sensor.SensorEventListener;
import org.apache.brooklyn.core.entity.BrooklynConfigKeys;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.core.test.BrooklynAppUnitTestSupport;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.core.workflow.WorkflowBasicTest;
import org.apache.brooklyn.core.workflow.steps.CustomWorkflowStep;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.flags.TypeCoercions;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableSet;

public class DynamicMultiGroupTest extends BrooklynAppUnitTestSupport {

    private static final AttributeSensor<String> SENSOR = Sensors.newSensor(String.class, "multigroup.test");

    @Test
    public void testBucketDistributionFromSubscription() {
        doTestBucketDistributionFromSubscription(ConfigBag.newInstance()
                .configure(BUCKET_FUNCTION, bucketFromAttribute(SENSOR))
                .configure(DynamicMultiGroup.BUCKET_ID_FUNCTION, bucketFromAttribute(SENSOR)));
    }

    @Test
    public void testBucketDistributionFromSubscriptionWithWorkflow() {
        WorkflowBasicTest.addWorkflowStepTypes(mgmt);
        doTestBucketDistributionFromSubscription(ConfigBag.newInstance()
                .configure(BUCKET_ID_WORKFLOW,
                    TypeCoercions.coerce(MutableMap.of("steps", MutableList.of("let x = ${entity.sensor['"+SENSOR.getName()+"']} ?? \"\"", "return ${x}")), CustomWorkflowStep.class) ));
    }

    @Test
    public void testBucketDistributionFromSubscriptionWithWorkflowExpression() {
        WorkflowBasicTest.addWorkflowStepTypes(mgmt);
        doTestBucketDistributionFromSubscription(ConfigBag.newInstance()
                .configure(BUCKET_ID_EXPRESSION, "${entity.sensor['"+SENSOR.getName()+"']}"));
    }

    void doTestBucketDistributionFromSubscription(ConfigBag config) {
        Group group = app.createAndManageChild(EntitySpec.create(BasicGroup.class));
        final DynamicMultiGroup dmg = app.createAndManageChild(
                EntitySpec.create(DynamicMultiGroup.class)
                        .configure(ENTITY_FILTER, instanceOf(TestEntity.class))
                        .configure(config.getAllConfig())
        );
        app.subscriptions().subscribeToChildren(group, SENSOR, new SensorEventListener<String>() {
            @Override
            public void onEvent(SensorEvent<String> event) { dmg.rescanEntities(); }
        });

        EntitySpec<TestEntity> childSpec = EntitySpec.create(TestEntity.class);
        TestEntity child1 = group.addChild(EntitySpec.create(childSpec).displayName("child1"));
        TestEntity child2 = group.addChild(EntitySpec.create(childSpec).displayName("child2"));

        checkDistribution(group, dmg, childSpec, child1, child2);
    }

    @Test(groups="Integration") // because takes 4s or so
    public void testBucketDistributionWithRescan() {
        Group group = app.createAndManageChild(EntitySpec.create(BasicGroup.class));
        final DynamicMultiGroup dmg = app.createAndManageChild(
                EntitySpec.create(DynamicMultiGroup.class)
                        .configure(ENTITY_FILTER, instanceOf(TestEntity.class))
                        .configure(BUCKET_FUNCTION, bucketFromAttribute(SENSOR))
                        .configure(DynamicMultiGroup.BUCKET_ID_FUNCTION, bucketFromAttribute(SENSOR))
                        .configure(RESCAN_INTERVAL, 1L)
        );

        EntitySpec<TestEntity> childSpec = EntitySpec.create(TestEntity.class);
        TestEntity child1 = group.addChild(EntitySpec.create(childSpec).displayName("child1"));
        TestEntity child2 = group.addChild(EntitySpec.create(childSpec).displayName("child2"));
        
        checkDistribution(group, dmg, childSpec, child1, child2);
    }

    @Test
    public void testRemovesEmptyBuckets() {
        Group group = app.createAndManageChild(EntitySpec.create(BasicGroup.class));
        final DynamicMultiGroup dmg = app.createAndManageChild(
                EntitySpec.create(DynamicMultiGroup.class)
                        .configure(ENTITY_FILTER, instanceOf(TestEntity.class))
                        .configure(BUCKET_FUNCTION, bucketFromAttribute(SENSOR))
        );
        app.subscriptions().subscribeToChildren(group, SENSOR, new SensorEventListener<String>() {
            @Override
            public void onEvent(SensorEvent<String> event) { dmg.rescanEntities(); }
        });

        EntitySpec<TestEntity> childSpec = EntitySpec.create(TestEntity.class);
        TestEntity child1 = app.createAndManageChild(EntitySpec.create(childSpec).displayName("child1"));
        TestEntity child2 = app.createAndManageChild(EntitySpec.create(childSpec).displayName("child2"));

        // Expect two buckets: bucketA and bucketB 
        child1.sensors().set(SENSOR, "bucketA");
        child2.sensors().set(SENSOR, "bucketB");
        dmg.rescanEntities();
        Group bucketA = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketA"), null);
        Group bucketB = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketB"), null);
        assertNotNull(bucketA);
        assertNotNull(bucketB);
        
        // Expect second bucket to be removed when empty 
        child1.sensors().set(SENSOR, "bucketA");
        child2.sensors().set(SENSOR, "bucketA");
        dmg.rescanEntities();
        bucketA = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketA"), null);
        bucketB = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketB"), null);
        assertNotNull(bucketA);
        assertNull(bucketB);
    }

    private void checkDistribution(final Group group, final DynamicMultiGroup dmg, final EntitySpec<TestEntity> childSpec, final TestEntity child1, final TestEntity child2) {
        // Start with both children in bucket A; there is no bucket B
        child1.sensors().set(SENSOR, "bucketA");
        child2.sensors().set(SENSOR, "bucketA");
        Asserts.succeedsEventually(new Runnable() {
            @Override
            public void run() {
                Group bucketA = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketA"), null);
                Group bucketB = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketB"), null);
                assertNotNull(bucketA);
                assertEquals(bucketA.getConfig(BrooklynConfigKeys.PLAN_ID), "bucketA");
                assertNull(bucketB);
                assertEquals(ImmutableSet.copyOf(bucketA.getMembers()), ImmutableSet.of(child1, child2));
            }
        });

        // Move child 1 into bucket B
        child1.sensors().set(SENSOR, "bucketB");
        Asserts.succeedsEventually(new Runnable() {
            @Override
            public void run() {
                Group bucketA = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketA"), null);
                Group bucketB = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketB"), null);
                assertNotNull(bucketA);
                assertNotNull(bucketB);
                assertEquals(ImmutableSet.copyOf(bucketB.getMembers()), ImmutableSet.of(child1));
                assertEquals(ImmutableSet.copyOf(bucketA.getMembers()), ImmutableSet.of(child2));
            }
        });

        // Move child 2 into bucket B; there is now no bucket A
        child2.sensors().set(SENSOR, "bucketB");
        Asserts.succeedsEventually(new Runnable() {
            @Override
            public void run() {
                Group bucketA = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketA"), null);
                Group bucketB = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketB"), null);
                assertNull(bucketA);
                assertNotNull(bucketB);
                assertEquals(ImmutableSet.copyOf(bucketB.getMembers()), ImmutableSet.of(child1, child2));
            }
        });

        // Add new child 3, associated with new bucket C
        final TestEntity child3 = group.addChild(EntitySpec.create(childSpec).displayName("child3"));
        child3.sensors().set(SENSOR, "bucketC");
        Asserts.succeedsEventually(new Runnable() {
            @Override
            public void run() {
                Group bucketC = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketC"), null);
                assertNotNull(bucketC);
                assertEquals(ImmutableSet.copyOf(bucketC.getMembers()), ImmutableSet.of(child3));
            }
        });

        // Un-set the sensor on child 3 -- gets removed from bucket C, which then
        // disappears as it is empty.
        child3.sensors().set(SENSOR, null);
        Asserts.succeedsEventually(new Runnable() {
            @Override
            public void run() {
                Group bucketB = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketB"), null);
                Group bucketC = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketC"), null);
                assertNotNull(bucketB);
                assertNull(bucketC);
                assertEquals(ImmutableSet.copyOf(bucketB.getMembers()), ImmutableSet.of(child1, child2));
            }
        });

        // Add child 3 back to bucket C -- this should result in a new group entity
        child3.sensors().set(SENSOR, "bucketC");
        Asserts.succeedsEventually(new Runnable() {
            @Override
            public void run() {
                Group bucketC = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketC"), null);
                assertNotNull(bucketC);
                assertEquals(ImmutableSet.copyOf(bucketC.getMembers()), ImmutableSet.of(child3));
            }
        });
    }

}
