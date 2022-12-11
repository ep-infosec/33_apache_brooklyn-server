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
import com.google.common.io.Files;
import org.apache.brooklyn.api.mgmt.ha.MementoCopyMode;
import org.apache.brooklyn.api.mgmt.rebind.mementos.BrooklynMementoRawData;
import static org.apache.brooklyn.core.entity.EntityPredicates.displayNameEqualTo;

import org.apache.brooklyn.core.mgmt.internal.LocalManagementContext;
import org.apache.brooklyn.core.mgmt.persist.BrooklynPersistenceUtils;
import org.apache.brooklyn.core.test.entity.TestApplication;

import static org.apache.brooklyn.entity.group.DynamicMultiGroup.BUCKET_EXPRESSION;
import static org.apache.brooklyn.entity.group.DynamicMultiGroup.BUCKET_FUNCTION;
import static org.apache.brooklyn.entity.group.DynamicMultiGroupImpl.bucketFromAttribute;

import org.apache.brooklyn.core.workflow.WorkflowBasicTest;
import org.apache.brooklyn.util.collections.MutableSet;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.testng.Assert;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

import java.util.List;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.entity.Group;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.core.entity.EntityPredicates;
import org.apache.brooklyn.core.mgmt.rebind.RebindOptions;
import org.apache.brooklyn.core.mgmt.rebind.RebindTestFixtureWithApp;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.test.Asserts;
import org.testng.annotations.Test;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

public class DynamicMultiGroupRebindTest extends RebindTestFixtureWithApp {

    private static final AttributeSensor<String> SENSOR = Sensors.newSensor(String.class, "multigroup.test");

    @Override
    protected LocalManagementContext decorateOrigOrNewManagementContext(LocalManagementContext mgmt) {
        LocalManagementContext result = super.decorateOrigOrNewManagementContext(mgmt);
        WorkflowBasicTest.addWorkflowStepTypes(result);
        return result;
    }

    // Previously there was a bug on rebind. The entity's rebind would immediately connec the
    // rescan, which would start executing in another thread. If there were any empty buckets
    // (i.e. groups) that child would be removed. But the rebind-manager would still be executing
    // concurrently. The empty group that was being removed might not have been reconstituted yet.
    // When we then tried to reconstitute it, the abstractEntity.setParent would fail with an 
    // error about being previouslyOwned, causing rebind to fail.
    //
    // To recreate this error, need to have several apps concurrently so that the rebind order
    // of the entities will be interleaved.
    @Test(groups="Integration", invocationCount=10)
    public void testRebindWhenGroupDisappeared() throws Exception {
        doTestRebindWhenGroupDisappeared( ConfigBag.newInstance().configure(BUCKET_FUNCTION, bucketFromAttribute(SENSOR)) );
    }

    @Test(groups="WIP", invocationCount=10)  // TODO workflows don't run when shutting down so if entities removed while shutting down, this can still leak
    public void testRebindWhenGroupDisappearedUsingExpression() throws Exception {
        doTestRebindWhenGroupDisappeared( ConfigBag.newInstance().configure(BUCKET_EXPRESSION, "${entity.sensor['"+SENSOR.getName()+"']}") );
    }

    public void doTestRebindWhenGroupDisappeared(ConfigBag config) throws Exception {
        int NUM_ITERATIONS = 10;
        List<DynamicMultiGroup> dmgs = Lists.newArrayList();
        List<TestEntity> childs = Lists.newArrayList();
        
        // Create lots of DynamicMultiGroups - one entity for each
        for (int i = 0; i < NUM_ITERATIONS; i++) {
            Group group = origApp.createAndManageChild(EntitySpec.create(BasicGroup.class));
            EntitySpec<DynamicMultiGroup> spec = EntitySpec.create(DynamicMultiGroup.class)
                    .displayName("dmg" + i)
                    .configure(DynamicMultiGroup.ENTITY_FILTER, Predicates.and(displayNameEqualTo("child" + i), instanceOf(TestEntity.class)))
                    .configure(DynamicMultiGroup.RESCAN_INTERVAL, 5L)
                    .configure(DynamicMultiGroup.BUCKET_SPEC, EntitySpec.create(BasicGroup.class));
            spec.configure(config.getAllConfig());
            DynamicMultiGroup dmg = origApp.createAndManageChild(spec);
            dmgs.add(dmg);
            
            TestEntity child = group.addChild(EntitySpec.create(TestEntity.class).displayName("child"+i));
            child.sensors().set(SENSOR, "bucketA");
            childs.add(child);
        }
        
        // Wait for all DynamicMultiGroups to have initialised correctly
        for (int i = 0; i < NUM_ITERATIONS; i++) {
            final DynamicMultiGroup dmg = dmgs.get(i);
            final TestEntity child = childs.get(i);
            Asserts.succeedsEventually(new Runnable() {
                @Override
                public void run() {
                    Group bucketA = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketA"), null);
                    assertNotNull(bucketA);
                    assertEquals(ImmutableSet.copyOf(bucketA.getMembers()), ImmutableSet.of(child));
                }
            });
        }

        // Quickly change the child sensors, and shutdown immediately before the DynamicMultiGroups 
        // rescan and update themselves.
        for (int i = 0; i < NUM_ITERATIONS; i++) {
            childs.get(i).sensors().set(SENSOR, "bucketB");
        }
        rebind(RebindOptions.create().terminateOrigManagementContext(true));
        
        // Check that all entities are in the new expected groups
        for (int i = 0; i < NUM_ITERATIONS; i++) {
            final DynamicMultiGroup dmg = (DynamicMultiGroup) newManagementContext.getEntityManager().getEntity(dmgs.get(i).getId());
            final TestEntity child = (TestEntity) newManagementContext.getEntityManager().getEntity(childs.get(i).getId());
            // FIXME Remove timeout; use default
            Asserts.succeedsEventually(new Runnable() {
                @Override
                public void run() {
                    Group bucketA = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketA"), null);
                    Group bucketB = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketB"), null);
                    assertNull(bucketA);
                    assertNotNull(bucketB);
                    assertEquals(ImmutableSet.copyOf(bucketB.getMembers()), ImmutableSet.of(child));
                }
            });
        }
    }

    @Test
    public void testSimplestMultiGroupRebindAndDelete() throws Exception {
        DynamicMultiGroup dmg = origApp.createAndManageChild(EntitySpec.create(DynamicMultiGroup.class)
                .configure(DynamicMultiGroup.ENTITY_FILTER, Predicates.alwaysFalse())

//                .configure(BUCKET_FUNCTION, bucketFromAttribute(SENSOR))
                .configure(BUCKET_EXPRESSION, "${entity.sensor['"+SENSOR.getName()+"']}")

                .configure(DynamicMultiGroup.BUCKET_SPEC, EntitySpec.create(BasicGroup.class)));

        BrooklynMementoRawData state;
        state = BrooklynPersistenceUtils.newStateMemento(mgmt(), MementoCopyMode.LOCAL);
        Assert.assertEquals(state.getEntities().size(), 2);

        TestApplication appRebinded = rebind(RebindOptions.create().terminateOrigManagementContext(true));
        switchOriginalToNewManagementContext();

        state = BrooklynPersistenceUtils.newStateMemento(mgmt(), MementoCopyMode.LOCAL);
        Assert.assertEquals(state.getEntities().size(), 2);

        appRebinded.stop();

        appRebinded = rebind(RebindOptions.create().terminateOrigManagementContext(true));

        Assert.assertNull(appRebinded);
        state = BrooklynPersistenceUtils.newStateMemento(mgmt(), MementoCopyMode.LOCAL);
        Assert.assertEquals(state.getEntities().size(), 0);
        Assert.assertEquals(state.getEnrichers().size(), 0);

        Files.fileTraverser().breadthFirst(mementoDir).forEach(f -> {
            if (!f.isDirectory()) {
                if ( MutableSet.of("planeId").contains(f.getName()) ) {
                    // expect these
                } else {
                    Assert.fail("At least one unexpected file exists after app stopped: " + f);
                }
            }
        });
    }
}
