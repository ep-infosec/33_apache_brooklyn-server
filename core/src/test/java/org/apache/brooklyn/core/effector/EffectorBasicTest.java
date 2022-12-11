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
package org.apache.brooklyn.core.effector;

import static org.testng.Assert.assertEquals;

import java.util.List;
import java.util.concurrent.Callable;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.mgmt.HasTaskChildren;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.StartableApplication;
import org.apache.brooklyn.core.entity.trait.FailingEntity;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.location.SimulatedLocation;
import org.apache.brooklyn.core.mgmt.internal.ManagementContextInternal;
import org.apache.brooklyn.core.test.BrooklynAppUnitTestSupport;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Duration;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

public class EffectorBasicTest extends BrooklynAppUnitTestSupport {

    // NB: more tests of effectors in EffectorSayHiTest and EffectorConcatenateTest
    // as well as EntityConfigMapUsageTest and others

    private List<SimulatedLocation> locs;
    
    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        locs = ImmutableList.of(new SimulatedLocation());
    }
    
    @Test
    public void testInvokeEffectorStart() {
        app.start(locs);
        Asserts.assertEqualsIgnoringOrder(locs, app.getLocations());
        // TODO above does not get registered as a task
    }

    @Test
    public void testInvokeEffectorStartWithMap() {
        app.invoke(Startable.START, MutableMap.of("locations", locs)).getUnchecked();
        Asserts.assertEqualsIgnoringOrder(locs, app.getLocations());
    }

    @Test
    public void testInvokeEffectorStartWithArgs() {
        Entities.invokeEffectorWithArgs(app, app, Startable.START, locs).getUnchecked();
        Asserts.assertEqualsIgnoringOrder(locs, app.getLocations());
    }

    @Test
    public void testInvokeEffectorStartWithTwoEntities() {
        TestEntity entity = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        TestEntity entity2 = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        app.start(locs);
        Asserts.assertEqualsIgnoringOrder(locs, app.getLocations());
        Asserts.assertEqualsIgnoringOrder(locs, entity.getLocations());
        Asserts.assertEqualsIgnoringOrder(locs, entity2.getLocations());
    }
    
    @Test
    public void testInvokeEffectorTaskHasTag() {
        Task<Void> starting = app.invoke(Startable.START, MutableMap.of("locations", locs));
//        log.info("TAGS: "+starting.getTags());
        Assert.assertTrue(starting.getTags().contains(ManagementContextInternal.EFFECTOR_TAG));
    }

    @Test
    public void testInvokeEffectorListWithEmpty() throws Exception{
        Entities.invokeEffectorList(app, ImmutableList.<StartableApplication>of(), Startable.STOP).get(Duration.THIRTY_SECONDS); 
    }

    @Test
    public void testInvokeEffectorList() throws Exception{
        List<TestEntity> entities = Lists.newArrayList();
        for (int i = 0; i < 10; i++) {
            entities.add(app.addChild(EntitySpec.create(TestEntity.class)));
        }
        Entities.invokeEffectorList(app, entities, Startable.STOP).get(Duration.THIRTY_SECONDS);
        for (TestEntity entity : entities) {
            assertEquals(entity.getCallHistory(), ImmutableList.of("stop"));
        }
    }

    @Test
    public void testInvokeEffectorListWithEmptyUsingUnmanagedContext() throws Exception {
        // Previously this threw the IllegalStateException directly, because DynamicTasks called
        // ((EntityInternal)entity).getManagementSupport().getExecutionContext();
        // (so it successfully called getManagementSupport, and then hit the exception.
        // Now it calls ((EntityInternal)entity).getExecutionContext(), so the exception happens in
        // the entity-proxy and is thus wrapped.
        TestEntity entity = app.addChild(EntitySpec.create(TestEntity.class));
        Entities.unmanage(entity);
        try {
            Entities.invokeEffectorList(entity, ImmutableList.<StartableApplication>of(), Startable.STOP).get(Duration.THIRTY_SECONDS);
            Asserts.shouldHaveFailedPreviously();
        } catch (Exception e) {
            IllegalStateException e2 = Exceptions.getFirstThrowableOfType(e, IllegalStateException.class);
            if (e2 == null) throw e;
            Asserts.expectedFailureContains(e2, "no longer managed");
        }
    }

    // check various failure situations
    
    private FailingEntity createFailingEntity() {
        FailingEntity entity = app.createAndManageChild(EntitySpec.create(FailingEntity.class)
            .configure(FailingEntity.FAIL_ON_START, true));
        return entity;
    }

    // uncaught failures are propagates
    
    @Test
    public void testInvokeEffectorStartFailing_Method() {
        FailingEntity entity = createFailingEntity();
        assertStartMethodFails(entity);
    }

    @Test
    public void testInvokeEffectorStartFailing_EntityInvoke() {
        FailingEntity entity = createFailingEntity();
        assertTaskFails( entity.invoke(Startable.START, MutableMap.of("locations", locs)) );
    }
     
    @Test
    public void testInvokeEffectorStartFailing_EntitiesInvoke() {
        FailingEntity entity = createFailingEntity();
        assertTaskFails( Entities.invokeEffectorWithArgs(entity, entity, Startable.START, locs) );
    }

    // caught failures are NOT propagated!
    
    @Test
    public void testInvokeEffectorStartFailing_MethodInDynamicTask() {
        Task<Void> task = app.getExecutionContext().submit(Tasks.<Void>builder().dynamic(true).body(new Callable<Void>() {
            @Override public Void call() throws Exception {
                testInvokeEffectorStartFailing_Method();
                return null;
            }
        }).build());
        
        assertTaskSucceeds(task);
        assertTaskHasFailedChild(task);
    }

    @Test
    public void testInvokeEffectorStartFailing_MethodInTask() {
        Task<Void> task = app.getExecutionContext().submit(Tasks.<Void>builder().dynamic(false).body(new Callable<Void>() {
            @Override public Void call() throws Exception {
                testInvokeEffectorStartFailing_Method();
                return null;
            }
        }).build());
        
        assertTaskSucceeds(task);
    }

    @Test
    public void testInvokeEffectorErrorCollapsedNicely() {
        FailingEntity entity = createFailingEntity();
        Task<Void> task = entity.invoke(Startable.START, MutableMap.of("locations", locs));
        Exception e = assertTaskFails( task );
        // normal collapse should report where we started
        String collapsed = Exceptions.collapseText(e);
        Assert.assertFalse(Strings.containsLiteral(collapsed, "Propagated"), "Error too verbose: "+collapsed);
        Assert.assertTrue(Strings.containsLiteral(collapsed, "invoking"), "Error not verbose enough: "+collapsed);
        Assert.assertTrue(Strings.containsLiteral(collapsed, "start"), "Error not verbose enough: "+collapsed);
        Assert.assertTrue(Strings.containsLiteral(collapsed, "FailingEntity"), "Error not verbose enough: "+collapsed);
        Assert.assertTrue(Strings.containsLiteral(collapsed, entity.getId()), "Error not verbose enough: "+collapsed);
        Assert.assertTrue(Strings.containsLiteral(collapsed, "Simulating"), "Error not verbose enough: "+collapsed);
        // in the context of the task we should not report where we started;
        // it instead of
        //    Error invoking start at FailingEntityImpl{id=wv6KwsPh}: Simulating entity stop failure for test
        // show
        //   Simulating entity start failure for test
        collapsed = Exceptions.collapseTextInContext(e, task);
        Assert.assertFalse(Strings.containsLiteral(collapsed, "Propagated"), "Error too verbose: "+collapsed);
        Assert.assertFalse(Strings.containsLiteral(collapsed, "invoking"), "Error too verbose: "+collapsed);
        Assert.assertFalse(Strings.containsLiteral(collapsed, "FailingEntity"), "Error too verbose: "+collapsed);
        Assert.assertFalse(Strings.containsLiteral(collapsed, entity.getId()), "Error too verbose: "+collapsed);
        Assert.assertTrue(Strings.containsLiteral(collapsed, "start"), "Error not verbose enough: "+collapsed);
        Assert.assertTrue(Strings.containsLiteral(collapsed, "Simulating"), "Error not verbose enough: "+collapsed);
    }
     

    private void assertTaskSucceeds(Task<Void> task) {
        task.getUnchecked();
        Assert.assertFalse(task.isError());
    }

    private void assertTaskHasFailedChild(Task<Void> task) {
        Assert.assertTrue(Tasks.failed( ((HasTaskChildren)task).getChildren() ).iterator().hasNext());
    }
        
    private Exception assertStartMethodFails(FailingEntity entity) {
        try {
            entity.start(locs);
            Asserts.shouldHaveFailedPreviously();
        } catch (Exception e) {
            Asserts.expectedFailure(e);
            return e;
        }
        return null;
    }
     
    protected Exception assertTaskFails(Task<?> t) {
        try {
            t.get();
            Assert.fail("Should have failed");
        } catch (Exception e) {
            Asserts.expectedFailure(e);
            return e;
        }
        return null;
    }
    
}
