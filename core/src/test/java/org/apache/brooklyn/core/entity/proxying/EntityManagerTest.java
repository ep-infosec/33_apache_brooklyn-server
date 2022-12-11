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
package org.apache.brooklyn.core.entity.proxying;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.mgmt.EntityManager;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.mgmt.internal.*;
import org.apache.brooklyn.core.objs.proxy.EntityProxy;
import org.apache.brooklyn.core.test.BrooklynAppUnitTestSupport;
import org.apache.brooklyn.core.test.entity.TestApplication;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.core.test.entity.TestEntityImpl;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.text.Identifiers;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

public class EntityManagerTest extends BrooklynAppUnitTestSupport {

    private static final Logger LOG = LoggerFactory.getLogger(EntityManagerTest.class);

    private EntityManager entityManager;

    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        entityManager = mgmt.getEntityManager();
    }
    
    @Test
    public void testCreateEntityUsingSpec() {
        TestEntity entity = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        TestEntity child = entity.addChild(EntitySpec.create(TestEntity.class).displayName("mychildname"));

        assertTrue(entity instanceof EntityProxy, "entity="+entity);
        assertFalse(entity instanceof TestEntityImpl, "entity="+entity);

        assertTrue(child instanceof EntityProxy, "child="+child);
        assertFalse(child instanceof TestEntityImpl, "child="+child);
        assertTrue(entity.getChildren().contains(child), "child="+child+"; children="+entity.getChildren());
        assertEquals(child.getDisplayName(), "mychildname");

        assertTrue(Entities.isManaged(entity));
        assertTrue(Entities.isManaged(child));

        Asserts.assertNotNull(entity.getId());
        Asserts.assertNotNull(mgmt.getEntityManager().getEntity(entity.getId()));
        Asserts.assertNotNull(mgmt.getEntityManager().getEntity(child.getId()));
    }

    @Test
    public void testCreateEntityUsingSpecDryRun() {
        TestEntity entity = mgmt.getEntityManager().createEntity(EntitySpec.create(TestEntity.class).configure(TestEntity.CONF_NAME, "foo"), new EntityManager.EntityCreationOptions() {
            @Override
            public boolean isDryRun() {
                return true;
            }
        });

        assertFalse(entity instanceof EntityProxy, "entity="+entity);
        assertTrue(entity instanceof TestEntityImpl, "entity="+entity);

        Asserts.assertNotNull(entity.getId());

        assertFalse(Entities.isManaged(entity));
        Asserts.assertNull(mgmt.getEntityManager().getEntity(entity.getId()));

        Asserts.assertEquals(entity.config().getRaw(TestEntity.CONF_NAME).orNull(), "foo");

        Asserts.assertFailsWith(() -> entity.getConfig(TestEntity.CONF_NAME),
                e -> Asserts.expectedFailureContainsIgnoreCase(e, "manage"));
    }

    @Test
    public void testCreateEntityUsingMapAndType() {
        TestEntity entity = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        TestEntity child = entity.addChild(EntitySpec.create(MutableMap.of("displayName", "mychildname"), TestEntity.class));
        assertTrue(child instanceof EntityProxy, "child="+child);
        assertFalse(child instanceof TestEntityImpl, "child="+child);
        assertTrue(entity.getChildren().contains(child), "child="+child+"; children="+entity.getChildren());
        assertEquals(child.getDisplayName(), "mychildname");
    }
    
    @Test
    public void testCreateEntityUsingPrivateConstructorFails() {
        try {
            TestEntity entity = app.createAndManageChild(EntitySpec.create(TestEntity.class).impl(TestEntityPrivateConstructorImpl.class));
            Asserts.shouldHaveFailedPreviously("entity="+entity);
        } catch (Exception e) {
            Asserts.expectedFailureContains(e, "must have a no-argument constructor");
        }
    }
    private static class TestEntityPrivateConstructorImpl extends TestEntityImpl {
        private TestEntityPrivateConstructorImpl() {
        }
    }

    @Test
    public void testGetEntities() {
        TestApplication app2 = mgmt.getEntityManager().createEntity(EntitySpec.create(TestApplication.class));
        TestEntity entity = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        TestEntity child = entity.createAndManageChild(EntitySpec.create(TestEntity.class));
        
        Asserts.assertEqualsIgnoringOrder(entityManager.getEntitiesInApplication(app), ImmutableList.of(app, entity, child));
        Asserts.assertEqualsIgnoringOrder(entityManager.getEntities(), ImmutableList.of(app, entity, child, app2));
        Asserts.assertEqualsIgnoringOrder(entityManager.findEntities(Predicates.instanceOf(TestApplication.class)), ImmutableList.of(app, app2));
        Asserts.assertEqualsIgnoringOrder(entityManager.findEntitiesInApplication(app, Predicates.instanceOf(TestApplication.class)), ImmutableList.of(app));
    }
    
    @Test
    public void testCreateEntitiesWithDuplicateIdFails() {
        TestApplication origApp = app;
        Entity origDeproxiedApp = Entities.deproxy(app);
        
        try {
            TestApplication app2 = ((EntityManagerInternal)entityManager).createEntity(EntitySpec.create(TestApplication.class), Optional.of(app.getId()));
            Asserts.shouldHaveFailedPreviously("app2="+app2);
        } catch (IdAlreadyExistsException e) {
            // success
        }
        
        // Should not have affected the existing app!
        Entity postApp = entityManager.getEntity(app.getId());
        assertSame(postApp, origApp);
        assertSame(Entities.deproxy(postApp), origDeproxiedApp);
    }
    
    @Test
    public void testDiscardPremanaged() {
        String id = Identifiers.makeRandomId(12);
        TestEntityImpl entity = mgmt.getEntityFactory().constructEntity(TestEntityImpl.class, ImmutableList.of(TestEntity.class), id);
        assertTrue(((LocalEntityManager)entityManager).isKnownEntityId(id));
        assertFalse(Entities.isManaged(entity));
        
        ((EntityManagerInternal)entityManager).discardPremanaged(entity);
        assertFalse(((LocalEntityManager)entityManager).isKnownEntityId(id));
        assertFalse(Entities.isManaged(entity));
    }
    

    @Test
    public void testDiscardPremanagedFailsIfManaged() {
        try {
            ((EntityManagerInternal)entityManager).discardPremanaged(app);
            Asserts.shouldHaveFailedPreviously();
        } catch (IllegalStateException e) {
            Asserts.expectedFailureContains(e, "Cannot discard", "it or a descendent is already managed");
        }
        
        // Should have had no effect
        Entities.isManaged(app);
    }
    
    // See https://issues.apache.org/jira/browse/BROOKLYN-352
    // Before the fix, 250ms was sufficient to cause the ConcurrentModificationException
    @Test
    public void testGetAllEntitiesWhileEntitiesAddedAndRemoved() throws Exception {
        runGetAllEntitiesWhileEntitiesAddedAndRemoved(Duration.millis(250));
    }
    
    @Test(groups="Integration")
    public void testGetAllEntitiesWhileEntitiesAddedAndRemovedManyTimes() throws Exception {
        runGetAllEntitiesWhileEntitiesAddedAndRemoved(Duration.seconds(10));
    }
    
    /**
     * See https://issues.apache.org/jira/browse/BROOKLYN-352.
     * 
     * Tests for a {@link ConcurrentModificationException} in 
     * {@link LocalEntityManager#getAllEntitiesInApplication(org.apache.brooklyn.api.entity.Application)}
     * by running multiple threads which continually call that method, while also running multiple
     * threads that add/remove entities (thus modifying the collections being inspected by
     * {@code getAllEntitiesInApplication}.
     */
    protected void runGetAllEntitiesWhileEntitiesAddedAndRemoved(Duration duration) throws Exception {
        final int NUM_GETTER_THREADS = 10;
        final int NUM_ENTITY_LIFECYCLE_THREADS = 10;
        
        final AtomicBoolean running = new AtomicBoolean(true);
        List<ListenableFuture<?>> futures = Lists.newArrayList();
        ListeningExecutorService executor = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
        try {
            for (int i = 0; i < NUM_GETTER_THREADS; i++) {
                ListenableFuture<?> future = executor.submit(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        int numCycles = 0;
                        try {
                            while (running.get()) {
                                ((LocalEntityManager)entityManager).getAllEntitiesInApplication(app);
                                numCycles++;
                            }
                            LOG.info("Executed getAllEntitiesInApplication " + numCycles + " times");
                            return null;
                        } catch (Exception e) {
                            LOG.error("Error in task for getAllEntitiesInApplication, cycle " + numCycles, e);
                            throw e;
                        }
                    }});
                futures.add(future);
            }

            for (int i = 0; i < NUM_ENTITY_LIFECYCLE_THREADS; i++) {
                ListenableFuture<?> future = executor.submit(new Callable<Void>() {
                    @Override
                    public Void call() {
                        List<TestEntity> entities = Lists.newLinkedList();
                        int numCycles = 0;
                        try {
                            while (running.get()) {
                                for (int i = 0; i < 10; i++) {
                                    TestEntity entity = app.addChild(EntitySpec.create(TestEntity.class));
                                    entities.add(entity);
                                    if (!running.get()) break;
                                }
                                for (int i = 0; i < 10; i++) {
                                    Entities.unmanage(entities.remove(0));
                                    if (!running.get()) break;
                                }
                                numCycles++;
                            }
                            LOG.info("Executed add/remove children " + numCycles + " cycles (" + (numCycles*10) + " entities)");
                            return null;
                        } catch (Exception e) {
                            LOG.error("Error in task for add/remove children, cycle " + numCycles, e);
                            throw e;
                        }
                    }});
                futures.add(future);
            }
        
            try {
                Futures.allAsList(futures).get(duration.toMilliseconds(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // This is good; it means we're still running after 10 seconds with no exceptions
            }
            running.set(false);
            Futures.allAsList(futures).get(Asserts.DEFAULT_LONG_TIMEOUT.toMilliseconds(), TimeUnit.MILLISECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test(groups="Integration")  // soak test really
    public void testCreateEntityDryRunNoMemoryLeak() {
        Runnable task = () -> {
            mgmt.getEntityManager().createEntity(EntitySpec.create(TestEntity.class).configure(TestEntity.CONF_NAME,
                    Identifiers.makeRandomId(100*1000)), new EntityManager.EntityCreationOptions() {
                @Override
                public boolean isDryRun() {
                    return true;
                }
            });
        };
        // twice to seed the memory
        task.run();
        task.run();

        mgmt.getBrooklynProperties().put(BrooklynGarbageCollector.DO_SYSTEM_GC, true);
        Asserts.MemoryAssertions memory = Asserts.startMemoryAssertions("entity creation dry-run mode");
        memory.setExtraTaskOnNoteMemory(() -> {
            ((AbstractManagementContext)mgmt).getGarbageCollector().gcIteration();
        });

        // at 100k per entity, 20 x 50 should make 100 MB
        // in practice this stays stable at 11 MB, if we force GC iterations
        // (it fails if we don't)

        for (int i=1; i<=20; i++) {
            LOG.info("Starting batch "+i);
            for (int j=0; j<50; j++) {
                task.run();
            }
            LOG.info("Finished batch "+i);
            memory.assertUsedMemoryMaxDelta("after batch "+i, 50, false);
        }
    }
}
