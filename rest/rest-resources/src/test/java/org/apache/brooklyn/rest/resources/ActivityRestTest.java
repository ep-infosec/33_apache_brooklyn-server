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

import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.google.common.base.Stopwatch;
import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.mgmt.HasTaskChildren;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.core.effector.SampleManyTasksEffector;
import org.apache.brooklyn.core.entity.Dumper;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.mgmt.EntityManagementUtils;
import org.apache.brooklyn.core.mgmt.EntityManagementUtils.CreationResult;
import org.apache.brooklyn.core.mgmt.internal.TestEntityWithEffectors;
import org.apache.brooklyn.entity.stock.BasicApplication;
import org.apache.brooklyn.rest.domain.TaskSummary;
import org.apache.brooklyn.rest.testing.BrooklynRestResourceTest;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.http.HttpAsserts;
import org.apache.brooklyn.util.time.CountdownTimer;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.collect.Iterables;

/** Tests {@link ActivityResource} and activity methods on {@link EntityResource} */
public class ActivityRestTest extends BrooklynRestResourceTest {

    private static final Logger log = LoggerFactory.getLogger(ActivityRestTest.class);

    /* a nice seed, initial run as follows;

Task[eatand]@J90TKfIX: Waiting on Task[eat-sleep-rave-repeat]@QPa5o4kF
  Task[eat-sleep-rave-repeat]@QPa5o4kF: Waiting on Task[rave]@yP9KjuWD
    Task[rave]@yP9KjuWD: Waiting on Task[repeat]@Dd1AqB7Q
      Task[repeat]@Dd1AqB7Q: Waiting on Task[repeat]@remQL5eD
        Task[repeat]@remQL5eD: Waiting on Task[repeat]@g1ReP4BP
          Task[sleep]@iV3iWg2N: Completed, result: slept 46ms
          Task[eat]@fpIttX07: Completed, result: eat
          Task[eat]@w6sxLefq: Completed, result: eat
          Task[repeat]@g1ReP4BP: Waiting on Task[sleep]@zRTOQ4ak
            Task[eat]@TvcdOUx7: Completed, result: eat
            Task[rave]@yJndzNLf: Completed, result: raved with 1 tasks
              Task[eat]@oiJ3eZZQ: Completed, result: eat
            Task[sleep]@zRTOQ4ak: sleeping 74ms
            Task[eat]@qoFRPEfM: Not submitted
            Task[sleep]@fNX16uvi: Not submitted

     */
    private final int SEED = 1;

    private Entity entity;
    private Effector<?> effector;

    private Task<?> lastTask;

    @BeforeClass(alwaysRun = true)
    public void setUp() throws Exception {
        startServer();
    }

    @BeforeMethod(alwaysRun = true)
    public void setUpOneTest() throws Exception {
        initEntity(SEED);
    }

    @SuppressWarnings("deprecation")
    protected void initEntity(int seed) {
        if (entity != null && Entities.isManaged(entity)) {
            Entities.destroy(entity.getApplication(), true);
        }

        CreationResult<BasicApplication, Void> app = EntityManagementUtils.createStarting(getManagementContext(),
                EntitySpec.create(BasicApplication.class)
                        .child(EntitySpec.create(TestEntityWithEffectors.class)));
        app.blockUntilComplete();
        entity = Iterables.getOnlyElement(app.get().getChildren());

        SampleManyTasksEffector manyTasksAdder = new SampleManyTasksEffector(ConfigBag.newInstance().configure(SampleManyTasksEffector.RANDOM_SEED, seed));
        effector = manyTasksAdder.getEffector();
        manyTasksAdder.apply((org.apache.brooklyn.api.entity.EntityLocal) entity);
    }

//    /**
//     * finds a good seed, in case the effector changes
//     */
//    public static void main(String[] args) throws Exception {
//        ActivityRestTest me = new ActivityRestTest();
//        me.setUpClass();
//        int i = 0;
//        do {
//            me.initEntity(i);
//            try {
//                log.info("Trying seed " + i + "...");
//                me.testGood(Duration.millis(200));
//                break;
//            } catch (Throwable e) {
//                log.info("  " + Exceptions.collapseText(e));
//                // e.printStackTrace();
//                // continue
//            }
//            i++;
//        } while (true);
//        Dumper.dumpInfo(me.lastTask);
//        log.info("Seed " + i + " is good ^");
//    }

    @Test
    public void testGood() {
        testGood(Duration.ONE_SECOND);
    }

    void testGood(Duration timeout) {
        lastTask = entity.invoke(effector, null);
        Task<?> leaf = waitForCompletedDescendantWithChildAndSibling(lastTask, lastTask, CountdownTimer.newInstanceStarted(timeout), 0);
        Assert.assertTrue(depthOf(leaf) >= 4, "Not deep enough: " + depthOf(leaf));
    }

    @Test
    public void testGetActivity() {
        Task<?> t = entity.invoke(effector, MutableMap.of(SampleManyTasksEffector.RANDOM_SEED.getName(), 10));

        Response response = client().path("/activities/" + t.getId())
                .accept(MediaType.APPLICATION_JSON)
                .get();
        assertHealthy(response);
        TaskSummary task = response.readEntity(TaskSummary.class);
        Assert.assertEquals(task.getId(), t.getId());
        Asserts.assertThat(task.getTags(), tags -> tags.contains("EFFECTOR"));
        Optional<Object> effectorParams = task.getTags().stream().map(tag -> tag instanceof Map ? ((Map) tag).get("effectorParams") : null).filter(p -> p != null).findAny();
        Asserts.assertTrue(effectorParams.isPresent());
        Asserts.assertEquals(((Map) effectorParams.get()).get(SampleManyTasksEffector.RANDOM_SEED.getName()), 10);
    }

    // See https://issues.apache.org/jira/browse/BROOKLYN-571
    @Test
    public void testGetTaskOfUnmanagedEntity() {
        Task<?> t = entity.invoke(effector, null);
        Entities.unmanage(entity.getParent());

        Response response = client().path("/activities/" + t.getId())
                .accept(MediaType.APPLICATION_JSON)
                .get();
        assertHealthy(response);
        TaskSummary task = response.readEntity(TaskSummary.class);
        Assert.assertEquals(task.getId(), t.getId());
        Assert.assertEquals(task.getEntityId(), entity.getId());
    }

    @Test
    public void testGetActivitiesChildren() {
        Task<?> t = entity.invoke(effector, null);
        Task<?> leaf = waitForCompletedDescendantWithChildAndSibling(t, t, CountdownTimer.newInstanceStarted(Duration.ONE_SECOND), 0);

        Response response = client().path("/activities/" + leaf.getSubmittedByTask().getId() + "/children")
                .accept(MediaType.APPLICATION_JSON)
                .get();
        assertHealthy(response);
        List<TaskSummary> tasks = response.readEntity(new GenericType<List<TaskSummary>>() {
        });
        log.info("Tasks children: " + tasks.size());
        Assert.assertTrue(tasksContain(tasks, leaf), "tasks should have included leaf " + leaf + "; was " + tasks);
    }

    @Test(groups = "WIP")
    // we rejigged how this works, it should have one unique name now, and gives intermittent errors
    public void testGetActivitiesRecursiveAndWithLimit() {
        Task<?> t = entity.invoke(effector, null);
        Task<?> leaf = waitForCompletedDescendantWithChildAndSibling(t, t, CountdownTimer.newInstanceStarted(Duration.ONE_SECOND), 0);
        Task<?> leafParent = leaf.getSubmittedByTask();
        Task<?> leafGrandparent = leafParent.getSubmittedByTask();

        Response response = client().path("/activities/" + leafGrandparent.getId() + "/children")
                .accept(MediaType.APPLICATION_JSON)
                .get();
        assertHealthy(response);
        List<TaskSummary> tasksL = response.readEntity(new GenericType<List<TaskSummary>>() {
        });
        Assert.assertFalse(tasksContain(tasksL, leaf), "non-recursive tasks should not have included leaf " + leaf + "; was " + tasksL);
        Assert.assertTrue(tasksContain(tasksL, leafParent), "non-recursive tasks should have included leaf parent " + leafParent + "; was " + tasksL);
        Assert.assertFalse(tasksContain(tasksL, leafGrandparent), "non-recursive tasks should not have included leaf grandparent " + leafGrandparent + "; was " + tasksL);

        response = client().path("/activities/" + leafGrandparent.getId() + "/children/recurse")
                .accept(MediaType.APPLICATION_JSON)
                .get();
        assertHealthy(response);
        Map<String, TaskSummary> tasks = response.readEntity(new GenericType<Map<String, TaskSummary>>() {
        });
        Assert.assertTrue(tasksContain(tasks, leaf), "recursive tasks should have included leaf " + leaf + "; was " + tasks);
        Assert.assertTrue(tasksContain(tasks, leafParent), "recursive tasks should have included leaf parent " + leafParent + "; was " + tasks);
        Assert.assertFalse(tasksContain(tasks, leafGrandparent), "recursive tasks should not have included leaf grandparent " + leafGrandparent + "; was " + tasks);

        response = client().path("/activities/" + leafGrandparent.getId() + "/children/recurse")
                .query("maxDepth", 1)
                .accept(MediaType.APPLICATION_JSON)
                .get();
        assertHealthy(response);
        tasks = response.readEntity(new GenericType<Map<String, TaskSummary>>() {
        });
        Assert.assertFalse(tasksContain(tasks, leaf), "depth 1 recursive tasks should nont have included leaf " + leaf + "; was " + tasks);
        Assert.assertTrue(tasksContain(tasks, leafParent), "depth 1 recursive tasks should have included leaf parent " + leafParent + "; was " + tasks);

        response = client().path("/activities/" + leafGrandparent.getId() + "/children/recurse")
                .query("maxDepth", 2)
                .accept(MediaType.APPLICATION_JSON)
                .get();
        assertHealthy(response);
        tasks = response.readEntity(new GenericType<Map<String, TaskSummary>>() {
        });
        Assert.assertTrue(tasksContain(tasks, leaf), "depth 2 recursive tasks should have included leaf " + leaf + "; was " + tasks);
        Assert.assertTrue(tasksContain(tasks, leafParent), "depth 2 recursive tasks should have included leaf parent " + leafParent + "; was " + tasks);
        Assert.assertFalse(tasksContain(tasks, leafGrandparent), "depth 2 recursive tasks should not have included leaf grandparent " + leafGrandparent + "; was " + tasks);

        Assert.assertTrue(children(leafGrandparent).size() >= 2, "children: " + children(leafGrandparent));
        response = client().path("/activities/" + leafGrandparent.getId() + "/children/recurse")
                .query("limit", children(leafGrandparent).size())
                .accept(MediaType.APPLICATION_JSON)
                .get();
        assertHealthy(response);
        tasks = response.readEntity(new GenericType<Map<String, TaskSummary>>() {
        });
        Assert.assertEquals(tasks.size(), children(leafGrandparent).size());
        Assert.assertTrue(tasksContain(tasks, leafParent), "count limited recursive tasks should have included leaf parent " + leafParent + "; was " + tasks);
        Assert.assertFalse(tasksContain(tasks, leaf), "count limited recursive tasks should not have included leaf " + leaf + "; was " + tasks);

        response = client().path("/activities/" + leafGrandparent.getId() + "/children/recurse")
                .query("limit", children(leafGrandparent).size() + 1)
                .accept(MediaType.APPLICATION_JSON)
                .get();
        response.bufferEntity();
        assertHealthy(response);
        tasks = response.readEntity(new GenericType<Map<String, TaskSummary>>() {
        });
        Assert.assertEquals(tasks.size(), children(leafGrandparent).size() + 1);
        tasks = response.readEntity(new GenericType<Map<String, TaskSummary>>() {
        });
        Assert.assertTrue(tasksContain(tasks, leafParent), "count+1 limited recursive tasks should have included leaf parent " + leafParent + "; was " + tasks);
        // 2022-05-09 - race can cause this to fail occasionally. example output on failure:
        /*
         * expected: Task[eat]@qlQs4isq
         *
         * was: {
         * ehqkY6bX=TaskSummary{id='ehqkY6bX', displayName='eat', entityId='ngvnrjgey4',
         *       entityDisplayName='TestEntityWithEffectors:ngvn', description='',
         *       tags=[{wrappingType=contextEntity, entity={type=org.apache.brooklyn.api.entity.Entity, id=ngvnrjgey4}},{type=org.apache.brooklyn.api.mgmt.ManagementContext}, SUB-TASK],
         *       submitTimeUtc=1655894929948, startTimeUtc=1655894929948, endTimeUtc=1655894929948, currentStatus='Completed',
         *       result=eat, isError=false, isCancelled=false, children=[],
         *       submittedByTask=LinkWithMetadata{link='/activities/ys1TfWnj', metadata={id=ys1TfWnj, taskName=repeat, entityId=ngvnrjgey4, entityDisplayName=TestEntityWithEffectors:ngvn}},
         *       blockingTask=null, blockingDetails='null', detailedStatus='Completed after 0ms
         *       Result: eat', streams={}, links={self=/activities/ehqkY6bX, children=/activities/ehqkY6bX/children, entity=/applications/frblu0wgjz/entities/ngvnrjgey4}},
         * OfboqeEb=TaskSummary{id='OfboqeEb', displayName='rave', entityId='ngvnrjgey4', entityDisplayName='TestEntityWithEffectors:ngvn', description='', tags=[{wrappingType=contextEntity, entity={type=org.apache.brooklyn.api.entity.Entity, id=ngvnrjgey4}}, {type=org.apache.brooklyn.api.mgmt.ManagementContext}, SUB-TASK], submitTimeUtc=1655894929948, startTimeUtc=1655894929948, endTimeUtc=1655894929948, currentStatus='Completed', result=raved with 1 tasks, isError=false, isCancelled=false,
         *          children=[LinkWithMetadata{link='/activities/CYrgJWOX', metadata={id=CYrgJWOX, taskName=eat, entityId=ngvnrjgey4, entityDisplayName=TestEntityWithEffectors:ngvn}}], submittedByTask=LinkWithMetadata{link='/activities/ys1TfWnj', metadata={id=ys1TfWnj, taskName=repeat, entityId=ngvnrjgey4, entityDisplayName=TestEntityWithEffectors:ngvn}}, blockingTask=null, blockingDetails='null',
         *              detailedStatus='Completed after 0ms Result: raved with 1 tasks', streams={}, links={self=/activities/OfboqeEb,
         *              children=/activities/OfboqeEb/children, entity=/applications/frblu0wgjz/entities/ngvnrjgey4}},
         * PraVlsNu=TaskSummary{id='PraVlsNu', displayName='sleep', entityId='ngvnrjgey4', entityDisplayName='TestEntityWithEffectors:ngvn', description='Sleeping 74ms', tags=[{wrappingType=contextEntity, entity={type=org.apache.brooklyn.api.entity.Entity, id=ngvnrjgey4}}, {type=org.apache.brooklyn.api.mgmt.ManagementContext}, SUB-TASK], submitTimeUtc=1655894929948, startTimeUtc=1655894929948, endTimeUtc=1655894930024, currentStatus='Completed', result=slept 74ms, isError=false, isCancelled=false, children=[], submittedByTask=LinkWithMetadata{link='/activities/ys1TfWnj', metadata={id=ys1TfWnj, taskName=repeat, entityId=ngvnrjgey4, entityDisplayName=TestEntityWithEffectors:ngvn}}, blockingTask=null, blockingDetails='null', detailedStatus='Completed after 76ms
         *          Result: slept 74ms', streams={}, links={self=/activities/PraVlsNu, children=/activities/PraVlsNu/children, entity=/applications/frblu0wgjz/entities/ngvnrjgey4}},
         * rozpPf9w=TaskSummary{id='rozpPf9w', displayName='eat', entityId='ngvnrjgey4', entityDisplayName='TestEntityWithEffectors:ngvn', description='', tags=[{wrappingType=contextEntity, entity={type=org.apache.brooklyn.api.entity.Entity, id=ngvnrjgey4}}, {type=org.apache.brooklyn.api.mgmt.ManagementContext}, SUB-TASK], submitTimeUtc=1655894930024, startTimeUtc=1655894930024, endTimeUtc=1655894930024, currentStatus='Completed', result=eat, isError=false, isCancelled=false, children=[], submittedByTask=LinkWithMetadata{link='/activities/ys1TfWnj', metadata={id=ys1TfWnj, taskName=repeat, entityId=ngvnrjgey4, entityDisplayName=TestEntityWithEffectors:ngvn}}, blockingTask=null, blockingDetails='null', detailedStatus='Completed after 0ms
         *          Result: eat', streams={}, links={self=/activities/rozpPf9w, children=/activities/rozpPf9w/children, entity=/applications/frblu0wgjz/entities/ngvnrjgey4}},
         * q9rmyAYQ=TaskSummary{id='q9rmyAYQ', displayName='sleep', entityId='ngvnrjgey4', entityDisplayName='TestEntityWithEffectors:ngvn', description='Sleeping 89ms', tags=[{wrappingType=contextEntity, entity={type=org.apache.brooklyn.api.entity.Entity, id=ngvnrjgey4}}, {type=org.apache.brooklyn.api.mgmt.ManagementContext}, SUB-TASK], submitTimeUtc=1655894930024, startTimeUtc=1655894930024, endTimeUtc=1655894930119, currentStatus='Completed', result=slept 89ms, isError=false, isCancelled=false, children=[], submittedByTask=LinkWithMetadata{link='/activities/ys1TfWnj', metadata={id=ys1TfWnj, taskName=repeat, entityId=ngvnrjgey4, entityDisplayName=TestEntityWithEffectors:ngvn}}, blockingTask=null, blockingDetails='null', detailedStatus='Completed after 95ms
         *          Result: slept 89ms', streams={}, links={self=/activities/q9rmyAYQ, children=/activities/q9rmyAYQ/children, entity=/applications/frblu0wgjz/entities/ngvnrjgey4}},
         * u1vdPlK0=TaskSummary{id='u1vdPlK0', displayName='repeat', entityId='ngvnrjgey4', entityDisplayName='TestEntityWithEffectors:ngvn', description='', tags=[{wrappingType=contextEntity, entity={type=org.apache.brooklyn.api.entity.Entity, id=ngvnrjgey4}}, {type=org.apache.brooklyn.api.mgmt.ManagementContext}, SUB-TASK], submitTimeUtc=1655894930119, startTimeUtc=1655894930119, endTimeUtc=1655894930119, currentStatus='Completed', result=[eat, eat], isError=false, isCancelled=false,
         *          children=[LinkWithMetadata{link='/activities/qlQs4isq', metadata={id=qlQs4isq, taskName=eat, entityId=ngvnrjgey4, entityDisplayName=TestEntityWithEffectors:ngvn}}, LinkWithMetadata{link='/activities/n84y8H5W', metadata={id=n84y8H5W, taskName=eat, entityId=ngvnrjgey4, entityDisplayName=TestEntityWithEffectors:ngvn}}], submittedByTask=LinkWithMetadata{link='/activities/ys1TfWnj', metadata={id=ys1TfWnj, taskName=repeat, entityId=ngvnrjgey4, entityDisplayName=TestEntityWithEffectors:ngvn}}, blockingTask=null, blockingDetails='null', detailedStatus='Completed after 0ms
         *          Result: [eat, eat]', streams={}, links={self=/activities/u1vdPlK0, children=/activities/u1vdPlK0/children, entity=/applications/frblu0wgjz/entities/ngvnrjgey4}},
         * DQf6nl7F=TaskSummary{id='DQf6nl7F', displayName='sleep', entityId='ngvnrjgey4', entityDisplayName='TestEntityWithEffectors:ngvn', description='Sleeping 345ms', tags=[{wrappingType=contextEntity, entity={type=org.apache.brooklyn.api.entity.Entity, id=ngvnrjgey4}}, {type=org.apache.brooklyn.api.mgmt.ManagementContext}, SUB-TASK], submitTimeUtc=1655894930119, startTimeUtc=1655894930119, endTimeUtc=null, currentStatus='In progress', result=null, isError=false, isCancelled=false, children=[], submittedByTask=LinkWithMetadata{link='/activities/ys1TfWnj', metadata={id=ys1TfWnj, taskName=repeat, entityId=ngvnrjgey4, entityDisplayName=TestEntityWithEffectors:ngvn}}, blockingTask=null, blockingDetails='sleeping 345ms', detailedStatus='sleeping 345ms
         *              Task[sleep]@DQf6nl7F
         *              Submitted by MaybeSupplier[value=Task[repeat]@ys1TfWnj] In progress, thread waiting (timed) on unknown (sleep) At: org.apache.brooklyn.util.time.Time.sleep(Time.java:451) org.apache.brooklyn.util.time.Time.sleep(Time.java:459) org.apache.brooklyn.core.effector.SampleManyTasksEffector$1$2.call(SampleManyTasksEffector.java:102) org.apache.brooklyn.util.core.task.DynamicSequentialTask$DstJob.call(DynamicSequentialTask.java:370) org.apache.brooklyn.util.core.task.BasicExecutionManager$SubmissionCallable.call(BasicExecutionManager.java:874)', streams={}, links={self=/activities/DQf6nl7F, children=/activities/DQf6nl7F/children, entity=/applications/frblu0wgjz/entities/ngvnrjgey4}},
         * MSGthJav=TaskSummary{id='MSGthJav', displayName='repeat', entityId='null', entityDisplayName='null', description='', tags=[SUB-TASK], submitTimeUtc=null, startTimeUtc=null, endTimeUtc=null, currentStatus='Not submitted', result=null, isError=false, isCancelled=false,
         *              children=[LinkWithMetadata{link='/activities/TLiYvXdI',
         *              metadata={id=TLiYvXdI, taskName=eat}}, LinkWithMetadata{link='/activities/cMxssIEB', metadata={id=cMxssIEB, taskName=eat}}], submittedByTask=null, blockingTask=null, blockingDetails='null', detailedStatus='Not submitted', streams={}, links={self=/activities/MSGthJav, children=/activities/MSGthJav/children}},
         * y2hMjkYv=TaskSummary{id='y2hMjkYv', displayName='eat', entityId='null', entityDisplayName='null', description='', tags=[SUB-TASK], submitTimeUtc=null, startTimeUtc=null, endTimeUtc=null, currentStatus='Not submitted', result=null, isError=false, isCancelled=false, children=[], submittedByTask=null, blockingTask=null, blockingDetails='null', detailedStatus='Not submitted', streams={}, links={self=/activities/y2hMjkYv, children=/activities/y2hMjkYv/children}},
         * Hmnbssz2=TaskSummary{id='Hmnbssz2', displayName='sleep', entityId='null', entityDisplayName='null', description='Sleeping 169ms', tags=[SUB-TASK], submitTimeUtc=null, startTimeUtc=null, endTimeUtc=null, currentStatus='Not submitted', result=null, isError=false, isCancelled=false, children=[], submittedByTask=null, blockingTask=null, blockingDetails='null', detailedStatus='Not submitted', streams={}, links={self=/activities/Hmnbssz2, children=/activities/Hmnbssz2/children}},
         * kX5ZAJIb=TaskSummary{id='kX5ZAJIb', displayName='eat', entityId='null', entityDisplayName='null', description='', tags=[SUB-TASK], submitTimeUtc=null, startTimeUtc=null, endTimeUtc=null, currentStatus='Not submitted', result=null, isError=false, isCancelled=false, children=[], submittedByTask=null, blockingTask=null, blockingDetails='null', detailedStatus='Not submitted', streams={}, links={self=/activities/kX5ZAJIb, children=/activities/kX5ZAJIb/children}},
         * CYrgJWOX=TaskSummary{id='CYrgJWOX', displayName='eat', entityId='ngvnrjgey4', entityDisplayName='TestEntityWithEffectors:ngvn', description='', tags=[{wrappingType=contextEntity, entity={type=org.apache.brooklyn.api.entity.Entity, id=ngvnrjgey4}}, {type=org.apache.brooklyn.api.mgmt.ManagementContext}, SUB-TASK], submitTimeUtc=1655894929948, startTimeUtc=1655894929948, endTimeUtc=1655894929948, currentStatus='Completed', result=eat, isError=false, isCancelled=false, children=[], submittedByTask=LinkWithMetadata{link='/activities/OfboqeEb', metadata={id=OfboqeEb, taskName=rave, entityId=ngvnrjgey4, entityDisplayName=TestEntityWithEffectors:ngvn}}, blockingTask=null, blockingDetails='null', detailedStatus='Completed after 0ms
         *              Result: eat', streams={}, links={self=/activities/CYrgJWOX, children=/activities/CYrgJWOX/children, entity=/applications/frblu0wgjz/entities/ngvnrjgey4}}}
         */
        Assert.assertTrue(tasksContain(tasks, leaf), "count+1 limited recursive tasks should have included leaf " + leaf + "; was " + tasks);
    }

    private boolean tasksContain(Map<String, TaskSummary> tasks, Task<?> leaf) {
        return tasks.keySet().contains(leaf.getId());
    }

    private List<Task<?>> children(Task<?> t) {
        return MutableList.copyOf(((HasTaskChildren) t).getChildren());
    }

    @Test
    public void testGetEntityActivitiesAndWithLimit() {
        Task<?> t = entity.invoke(effector, null);
        Task<?> leaf = waitForCompletedDescendantWithChildAndSibling(t, t, CountdownTimer.newInstanceStarted(Duration.ONE_SECOND), 0);

        Response response = client().path("/applications/" + entity.getApplicationId() +
                        "/entities/" + entity.getId() + "/activities")
                .accept(MediaType.APPLICATION_JSON)
                .get();
        assertHealthy(response);
        List<TaskSummary> tasks = response.readEntity(new GenericType<List<TaskSummary>>() {
        });
        log.info("Tasks now: " + tasks.size());
        Assert.assertTrue(tasks.size() > 4, "tasks should have been big; was " + tasks);
        Assert.assertTrue(tasksContain(tasks, leaf), "tasks should have included leaf " + leaf + "; was " + tasks);

        response = client().path("/applications/" + entity.getApplicationId() +
                        "/entities/" + entity.getId() + "/activities")
                .query("limit", 3)
                .accept(MediaType.APPLICATION_JSON)
                .get();
        assertHealthy(response);
        tasks = response.readEntity(new GenericType<List<TaskSummary>>() {
        });
        log.info("Tasks limited: " + tasks.size());
        Assert.assertEquals(tasks.size(), 3, "tasks should have been limited; was " + tasks);
        Assert.assertFalse(tasksContain(tasks, leaf), "tasks should not have included leaf " + leaf + "; was " + tasks);
    }

    private void assertHealthy(Response response) {
        if (!HttpAsserts.isHealthyStatusCode(response.getStatus())) {
            Asserts.fail("Bad response: " + response.getStatus() + " " + response.readEntity(String.class));
        }
    }

    private static boolean tasksContain(List<TaskSummary> tasks, Task<?> leaf) {
        for (TaskSummary ts : tasks) {
            if (ts.getId().equals(leaf.getId())) return true;
        }
        return false;
    }

    private int depthOf(Task<?> t) {
        int depth = -1;
        while (t != null) {
            t = t.getSubmittedByTask();
            depth++;
        }
        return depth;
    }

    private Task<?> waitForCompletedDescendantWithChildAndSibling(Task<?> tRoot, Task<?> t, CountdownTimer timer, int depthSoFar) {
        while (timer.isLive()) {
            Iterable<Task<?>> children = ((HasTaskChildren) t).getChildren();
            Iterator<Task<?>> ci = children.iterator();
            Task<?> bestFinishedDescendant = null;
            while (ci.hasNext()) {
                Task<?> tc = ci.next();
                Task<?> finishedDescendant = waitForCompletedDescendantWithChildAndSibling(tRoot, tc, timer, depthSoFar + 1);
                if (depthOf(finishedDescendant) > depthOf(bestFinishedDescendant)) {
                    bestFinishedDescendant = finishedDescendant;
                }
                int finishedDescendantDepth = depthOf(bestFinishedDescendant);
                // log.info("finished "+tc+", depth "+finishedDescendantDepth);
                if (finishedDescendantDepth < 2) {
                    if (ci.hasNext()) continue;
                    throw new IllegalStateException("not deep enough: " + finishedDescendantDepth);
                }
                if (finishedDescendantDepth == depthSoFar + 1) {
                    // child completed; now check we complete soon, and assert we have siblings
                    if (ci.hasNext()) continue;
                    if (!t.blockUntilEnded(timer.getDurationRemaining())) {
                        Dumper.dumpInfo(tRoot);
                        // log.info("Incomplete after "+t+": "+t.getStatusDetail(false));
                        throw Exceptions.propagate(new TimeoutException("parent didn't complete after child depth " + finishedDescendantDepth));
                    }
                }
                if (finishedDescendantDepth == depthSoFar + 2) {
                    if (Iterables.size(children) < 2) {
                        Dumper.dumpInfo(tRoot);
                        throw new IllegalStateException("finished child's parent has no sibling");
                    }
                }

                return bestFinishedDescendant;
            }
            Thread.yield();

            // leaf nodeå
            if (t.isDone()) return t;
        }
        throw Exceptions.propagate(new TimeoutException("expired waiting for children"));
    }

    static String getTaskDump(Task t) {
        StringWriter sw = new StringWriter();
        try {
            Dumper.dumpInfo(t, sw);
        } catch (IOException e) {
            throw Exceptions.propagate(e);
        }
        return sw.toString();
    }

    @Test
    public void testCancelQuick() {
        Task<?> t = entity.invoke(effector, null);
        // let it run for a bit
        waitForCompletedDescendantWithChildAndSibling(t, t, CountdownTimer.newInstanceStarted(Duration.ONE_SECOND), 0);

        Response response = client().path("/activities/" + t.getId() + "/cancel")
                .accept(MediaType.APPLICATION_JSON)
                .post(null);

        assertHealthy(response);
        boolean cancelled = response.readEntity(Boolean.class);
        Asserts.assertEquals(cancelled, true);
        Asserts.assertThat(t, task -> task.isDone());
        Asserts.assertThat(t, task -> task.isCancelled());

        Stopwatch sw = Stopwatch.createStarted();
        Asserts.eventually(() -> t, task -> task.isDone(true));
        Asserts.assertThat(Duration.of(sw), d -> d.isShorterThan(Duration.ONE_SECOND));
    }

    @Test(groups = "Integration", invocationCount = 100)  // because slow
    public void testCancelElaborate() {
        try {
            SampleManyTasksEffector.OUTPUT = Collections.synchronizedList(MutableList.of());

            Task<?> t = entity.invoke(effector, null);
            // let it run for a bit
            waitForCompletedDescendantWithChildAndSibling(t, t, CountdownTimer.newInstanceStarted(Duration.ONE_SECOND), 0);

            String t0 = getTaskDump(t);
            MutableList<String> output0 = MutableList.copyOf(SampleManyTasksEffector.OUTPUT);

            Response response = client().path("/activities/" + t.getId() + "/cancel")
                    .accept(MediaType.APPLICATION_JSON)
                    .post(null);

            String t1 = getTaskDump(t);
            MutableList<String> output1 = MutableList.copyOf(SampleManyTasksEffector.OUTPUT);

            assertHealthy(response);
            boolean cancelled = response.readEntity(Boolean.class);
            Asserts.assertEquals(cancelled, true);
            Asserts.assertThat(t, task -> task.isDone());
            Asserts.assertThat(t, task -> task.isCancelled());
            Time.sleep(200);

            // check task status and output quiesce within a short while following an interrupt, and all tasks done
            String t2 = getTaskDump(t);
            MutableList<String> output2 = MutableList.copyOf(SampleManyTasksEffector.OUTPUT);
            Asserts.assertThat(t, task -> task.isDone(true));

            // and nothing else running
            Time.sleep(200);
            String t3 = getTaskDump(t);
            MutableList<String> output3 = MutableList.copyOf(SampleManyTasksEffector.OUTPUT);

            Asserts.assertEquals(t2, t3);
            Asserts.assertEquals(output2, output3);

        } finally {
            SampleManyTasksEffector.OUTPUT = null;
        }
    }

    @Test(groups = "Integration", invocationCount = 100)  // because slow
    public void testCancelWithoutInterrupting() {
        try {
            SampleManyTasksEffector.OUTPUT = Collections.synchronizedList(MutableList.of());

            Task<?> t = entity.invoke(effector, null);
            // let it run for a bit
            waitForCompletedDescendantWithChildAndSibling(t, t, CountdownTimer.newInstanceStarted(Duration.ONE_SECOND), 0);

            String t0 = getTaskDump(t);
            MutableList<String> output0 = MutableList.copyOf(SampleManyTasksEffector.OUTPUT);

            Response response = client().path("/activities/" + t.getId() + "/cancel")
                    .query("noInterrupt", true)
                    .accept(MediaType.APPLICATION_JSON)
                    .post(null);

            String t1 = getTaskDump(t);
            MutableList<String> output1 = MutableList.copyOf(SampleManyTasksEffector.OUTPUT);

            assertHealthy(response);
            boolean cancelled = response.readEntity(Boolean.class);
            Asserts.assertEquals(cancelled, true);
            Asserts.assertThat(t, task -> task.isDone());
            Asserts.assertThat(t, task -> task.isCancelled());
            Time.sleep(200);

            Asserts.assertThat(t, task -> !task.isDone(true));

            // should still be running
            String t2 = getTaskDump(t);
            MutableList<String> output2 = MutableList.copyOf(SampleManyTasksEffector.OUTPUT);

            AtomicInteger i = new AtomicInteger(0);

            Asserts.eventually(()->t, (task) -> {
                String t3 = getTaskDump(task);
                MutableList<String> output3 = MutableList.copyOf(SampleManyTasksEffector.OUTPUT);
                System.out.println("TASKS:\n" + t3);

                if (task.isDone(true)) Asserts.fail("Task finished unexpectedly");
                if (t2.equals(t3)) return false;
                if (output2.equals(output3)) return false;
                // both have changed, tasks were active after interrupt, as expected
                return true;
            });

            // then finally we can cancel it
            t.cancel(true);
            Asserts.eventually(()->t, task -> {
                String t3 = getTaskDump(task);
                System.out.println("TASKS (after forced interrupt):\n" + t3);
                return task.isDone(true);
            });

        } finally {
            SampleManyTasksEffector.OUTPUT = null;
        }
    }

}
