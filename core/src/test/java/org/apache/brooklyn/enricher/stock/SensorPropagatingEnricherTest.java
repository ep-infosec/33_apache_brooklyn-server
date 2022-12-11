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
package org.apache.brooklyn.enricher.stock;

import static org.testng.Assert.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.sensor.EnricherSpec;
import org.apache.brooklyn.api.sensor.SensorEvent;
import org.apache.brooklyn.api.sensor.SensorEventListener;
import org.apache.brooklyn.core.entity.EntityAsserts;
import org.apache.brooklyn.core.sensor.BasicNotificationSensor;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.core.test.BrooklynAppUnitTestSupport;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.javalang.AtomicReferences;
import org.apache.brooklyn.util.time.Duration;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

public class SensorPropagatingEnricherTest extends BrooklynAppUnitTestSupport {

    private TestEntity entity;

    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        entity = app.createAndManageChild(EntitySpec.create(TestEntity.class));
    }
    
    @Test
    public void testPropagatesSpecificSensor() {
        app.enrichers().add(Enrichers.builder()
                .propagating(TestEntity.NAME)
                .from(entity)
                .build());

        // name propagated
        entity.sensors().set(TestEntity.NAME, "foo");
        EntityAsserts.assertAttributeEqualsEventually(app, TestEntity.NAME, "foo");
        
        // sequence not propagated
        entity.sensors().set(TestEntity.SEQUENCE, 2);
        EntityAsserts.assertAttributeEqualsContinually(MutableMap.of("timeout", 100), app, TestEntity.SEQUENCE, null);
    }
    
    @Test
    public void testPropagatesCurrentValue() {
        entity.sensors().set(TestEntity.NAME, "foo");
        
        app.enrichers().add(Enrichers.builder()
                .propagating(TestEntity.NAME)
                .from(entity)
                .build());

        // name propagated
        EntityAsserts.assertAttributeEqualsEventually(app, TestEntity.NAME, "foo");
    }
    
    @Test
    public void testPropagatesAllStaticSensors() {
        app.enrichers().add(Enrichers.builder()
                .propagatingAll()
                .from(entity)
                .build());

        // all attributes propagated
        entity.sensors().set(TestEntity.NAME, "foo");
        entity.sensors().set(TestEntity.SEQUENCE, 2);
        
        EntityAsserts.assertAttributeEqualsEventually(app, TestEntity.NAME, "foo");
        EntityAsserts.assertAttributeEqualsEventually(app, TestEntity.SEQUENCE, 2);
        
        // notification-sensor propagated
        final AtomicReference<Integer> notif = new AtomicReference<Integer>();
        app.subscriptions().subscribe(app, TestEntity.MY_NOTIF, new SensorEventListener<Integer>() {
                @Override public void onEvent(SensorEvent<Integer> event) {
                    notif.set(event.getValue());
                }});
        entity.sensors().emit(TestEntity.MY_NOTIF, 7);
        Asserts.eventually(AtomicReferences.supplier(notif), Predicates.equalTo(7));
    }
    
    @Test
    public void testPropagatesAllSensorsIncludesDynamicallyAdded() {
        AttributeSensor<String> dynamicAttribute = Sensors.newStringSensor("test.dynamicsensor.strattrib");
        BasicNotificationSensor<String> dynamicNotificationSensor = new BasicNotificationSensor<String>(String.class, "test.dynamicsensor.strnotif");
        
        app.enrichers().add(Enrichers.builder()
                .propagatingAll()
                .from(entity)
                .build());

        entity.sensors().set(dynamicAttribute, "foo");
        
        EntityAsserts.assertAttributeEqualsEventually(app, dynamicAttribute, "foo");
        
        // notification-sensor propagated
        final AtomicReference<String> notif = new AtomicReference<String>();
        app.subscriptions().subscribe(app, dynamicNotificationSensor, new SensorEventListener<String>() {
                @Override public void onEvent(SensorEvent<String> event) {
                    notif.set(event.getValue());
                }});
        entity.sensors().emit(dynamicNotificationSensor, "mynotifval");
        Asserts.eventually(AtomicReferences.supplier(notif), Predicates.equalTo("mynotifval"));
    }
    
    @Test
    public void testPropagatesAllBut() {
        app.enrichers().add(Enrichers.builder()
                .propagatingAllBut(TestEntity.SEQUENCE)
                .from(entity)
                .build());

        // name propagated
        entity.sensors().set(TestEntity.NAME, "foo");
        EntityAsserts.assertAttributeEqualsEventually(app, TestEntity.NAME, "foo");
        
        // sequence not propagated
        entity.sensors().set(TestEntity.SEQUENCE, 2);
        EntityAsserts.assertAttributeEqualsContinually(MutableMap.of("timeout", 100), app, TestEntity.SEQUENCE, null);
    }
    
    @Test
    public void testPropagatingAsDifferentSensor() {
        final AttributeSensor<String> ANOTHER_ATTRIBUTE = Sensors.newStringSensor("another.attribute", "");
        
        app.enrichers().add(Enrichers.builder()
                .propagating(ImmutableMap.of(TestEntity.NAME, ANOTHER_ATTRIBUTE))
                .from(entity)
                .build());

        // name propagated as different attribute
        entity.sensors().set(TestEntity.NAME, "foo");
        EntityAsserts.assertAttributeEqualsEventually(app, ANOTHER_ATTRIBUTE, "foo");
    }
    
    @Test
    public void testEnricherSpecPropagatesSpecificSensor() throws Exception {
        app.enrichers().add(EnricherSpec.create(Propagator.class)
                .configure(MutableMap.builder()
                        .putIfNotNull(Propagator.PRODUCER, entity)
                        .putIfNotNull(Propagator.PROPAGATING, ImmutableList.of(TestEntity.NAME))
                        .build()));

        // name propagated
        entity.sensors().set(TestEntity.NAME, "foo");
        EntityAsserts.assertAttributeEqualsEventually(app, TestEntity.NAME, "foo");
        
        // sequence not propagated
        entity.sensors().set(TestEntity.SEQUENCE, 2);
        EntityAsserts.assertAttributeEqualsContinually(MutableMap.of("timeout", 100), app, TestEntity.SEQUENCE, null);
    }
    
    @Test
    public void testEnricherSpecPropagatesSpecificSensorAndMapsOthers() throws Exception {
        final AttributeSensor<String> ANOTHER_ATTRIBUTE = Sensors.newStringSensor("another.attribute", "");
        
        app.enrichers().add(EnricherSpec.create(Propagator.class)
                .configure(MutableMap.builder()
                        .putIfNotNull(Propagator.PRODUCER, entity)
                        .putIfNotNull(Propagator.SENSOR_MAPPING, ImmutableMap.of(TestEntity.NAME, ANOTHER_ATTRIBUTE))
                        .putIfNotNull(Propagator.PROPAGATING, ImmutableList.of(TestEntity.SEQUENCE))
                        .build()));

        // name propagated as alternative sensor
        entity.sensors().set(TestEntity.NAME, "foo");
        EntityAsserts.assertAttributeEqualsEventually(app, ANOTHER_ATTRIBUTE, "foo");
        
        // sequence also propagated
        entity.sensors().set(TestEntity.SEQUENCE, 2);
        EntityAsserts.assertAttributeEqualsEventually(app, TestEntity.SEQUENCE, 2);

        // name not propagated as original sensor
        EntityAsserts.assertAttributeEqualsContinually(MutableMap.of("timeout", 100), app, TestEntity.NAME, null);
    }
    
    @Test
    public void testEnricherSpecThrowsOnPropagatesAndPropagatesAllSet() throws Exception {
        try {
            app.enrichers().add(EnricherSpec.create(Propagator.class)
                    .configure(MutableMap.builder()
                            .put(Propagator.PRODUCER, entity)
                            .put(Propagator.PROPAGATING, ImmutableList.of(TestEntity.NAME))
                            .put(Propagator.PROPAGATING_ALL, true)
                            .build()));
        } catch (Exception e) {
            IllegalStateException ise = Exceptions.getFirstThrowableOfType(e, IllegalStateException.class);
            if (ise == null) throw e;
        }
    }

    @Test
    public void testSensorPropagatedWhenMappingUsedSameNameButDifferentType() throws Exception {
        AttributeSensor<String> origSensor = Sensors.newSensor(String.class, "origSensor");
        AttributeSensor<Object> sourceSensorFromYaml = Sensors.newSensor(Object.class, "origSensor");
        AttributeSensor<Object> targetSensor = Sensors.newSensor(Object.class, "newSensor");
        app.enrichers().add(Enrichers.builder()
                .propagating(ImmutableMap.of(sourceSensorFromYaml, targetSensor))
                .from(entity)
                .build());
        entity.sensors().set(origSensor, "myval");
        EntityAsserts.assertAttributeEqualsEventually(app, targetSensor, "myval");
    }
    
    @Test
    public void testPropagateToDynamicSensor() {
        /*

        This test attempts to replicate the following YAML

        location: localhost
        services:
        - type: org.apache.brooklyn.core.test.entity.TestApplication
          brooklyn.children:
          - type: org.apache.brooklyn.core.test.entity.TestEntity
            id: childid

          brooklyn.enrichers:
          - type: org.apache.brooklyn.enricher.stock.Propagator
            brooklyn.config:
              producer: $brooklyn:component("child", "childid")
              propagating:
              - $brooklyn:sensor("test.name")
          - type: org.apache.brooklyn.enricher.stock.Propagator
            brooklyn.config:
              sensorMapping:
                $brooklyn:sensor("test.name"): $brooklyn:sensor("newSensor")
         */
        AttributeSensor<Object> targetSensor = Sensors.newSensor(Object.class, "newSensor");
        AttributeSensor<Object> sourceSensorFromYaml = Sensors.newSensor(Object.class, TestEntity.NAME.getName());
        app.enrichers().add(Enrichers.builder()
                .propagating(Sensors.newSensor(Object.class, TestEntity.NAME.getName()))
                .from(entity)
                .build());
        app.enrichers().add(Enrichers.builder()
                .propagating(ImmutableMap.of(sourceSensorFromYaml, targetSensor))
                .from(app)
                .build());
        EntityAsserts.assertAttributeEqualsEventually(app, targetSensor, entity.sensors().get(TestEntity.NAME));
        entity.sensors().set(TestEntity.NAME, "newName");
        EntityAsserts.assertAttributeEqualsEventually(app, targetSensor, "newName");
    }
    
    @Test
    public void testPropagatorDefaultsToProducerAsSelf() throws Exception {
        AttributeSensor<String> sourceSensor = Sensors.newSensor(String.class, "mySensor");
        AttributeSensor<String> targetSensor = Sensors.newSensor(String.class, "myTarget");

        app.enrichers().add(EnricherSpec.create(Propagator.class)
                .configure(Propagator.PRODUCER, app)
                .configure(Propagator.SENSOR_MAPPING, ImmutableMap.of(sourceSensor, targetSensor)));

        app.sensors().set(sourceSensor, "myval");
        EntityAsserts.assertAttributeEqualsEventually(app, targetSensor, "myval");
    }

    @Test
    public void testPropagatorAvoidsInfiniteLoopInPropagateAllWithImplicitProducer() throws Exception {
        AttributeSensor<String> mySensor = Sensors.newSensor(String.class, "mySensor");

        EnricherSpec<?> spec = EnricherSpec.create(Propagator.class)
                .configure(Propagator.PROPAGATING_ALL, true);

        assertAddEnricherThrowsIllegalStateException(spec, "when publishing to own entity");
        assertAttributeNotRepublished(app, mySensor);
    }
    
    @Test
    public void testPropagatorAvoidsInfiniteLoopInPropagateAll() throws Exception {
        AttributeSensor<String> mySensor = Sensors.newSensor(String.class, "mySensor");

        EnricherSpec<?> spec = EnricherSpec.create(Propagator.class)
                .configure(Propagator.PRODUCER, app)
                .configure(Propagator.PROPAGATING_ALL, true);

        assertAddEnricherThrowsIllegalStateException(spec, "when publishing to own entity");
        assertAttributeNotRepublished(app, mySensor);
    }
    
    @Test
    public void testPropagatorAvoidsInfiniteLoopInPropagateAllBut() throws Exception {
        AttributeSensor<String> mySensor = Sensors.newSensor(String.class, "mySensor");
        AttributeSensor<String> mySensor2 = Sensors.newSensor(String.class, "mySensor2");

        EnricherSpec<?> spec = EnricherSpec.create(Propagator.class)
                .configure(Propagator.PRODUCER, app)
                .configure(Propagator.PROPAGATING_ALL_BUT, ImmutableList.of(mySensor2));

        assertAddEnricherThrowsIllegalStateException(spec, "when publishing to own entity");
        assertAttributeNotRepublished(app, mySensor);
    }
    
    @Test
    public void testPropagatorAvoidsInfiniteLoopInPropagate() throws Exception {
        AttributeSensor<String> mySensor = Sensors.newSensor(String.class, "mySensor");

        EnricherSpec<?> spec = EnricherSpec.create(Propagator.class)
                .configure(Propagator.PRODUCER, app)
                .configure(Propagator.PROPAGATING, ImmutableList.of(mySensor));
        
        assertAddEnricherThrowsIllegalStateException(spec, "when publishing to own entity");
        assertAttributeNotRepublished(app, mySensor);
    }

    @Test
    public void testPropagatorAvoidsInfiniteLoopInSameSensorMapping() throws Exception {
        AttributeSensor<String> mySensor = Sensors.newSensor(String.class, "mySensor");

        EnricherSpec<?> spec = EnricherSpec.create(Propagator.class)
                .configure(Propagator.PRODUCER, app)
                .configure(Propagator.SENSOR_MAPPING, ImmutableMap.of(mySensor, mySensor));
        
        assertAddEnricherThrowsIllegalStateException(spec, "when publishing to own entity");
        assertAttributeNotRepublished(app, mySensor);
    }

    @Test
    public void testPropagatorFailsWithEmptyConfig() throws Exception {
        EnricherSpec<?> spec = EnricherSpec.create(Propagator.class);

        assertAddEnricherThrowsIllegalStateException(spec, "must have");
    }

    protected void assertAttributeNotRepublished(Entity entity, AttributeSensor<String> sensor) {
        final List<SensorEvent<String>> events = Lists.newCopyOnWriteArrayList();
        app.subscriptions().subscribe(entity, sensor, new SensorEventListener<String>() {
            @Override public void onEvent(SensorEvent<String> event) {
                events.add(event);
            }});

        app.sensors().set(sensor, "myval");
        assertSizeEventually(events, 1);
        assertSizeContinually(events, 1, Duration.millis(100));
    }
    
    protected void assertSizeEventually(final List<?> actual, final int expected) {
        Asserts.succeedsEventually(new Runnable() {
            @Override public void run() {
                assertEquals(actual.size(), expected, "actual="+actual);
            }});
    }

    protected void assertSizeContinually(final List<?> actual, final int expected, Duration duration) {
        Asserts.succeedsContinually(ImmutableMap.of("timeout", duration), new Runnable() {
            @Override public void run() {
                assertEquals(actual.size(), expected, "actual="+actual);
            }});
    }
    
    private void assertAddEnricherThrowsIllegalStateException(EnricherSpec<?> spec, String expectedPhrase) {
        try {
            app.enrichers().add(spec);
            Asserts.shouldHaveFailedPreviously();
        } catch (IllegalStateException e) {
            Asserts.expectedFailureContains(e, expectedPhrase);
        }
    }
}
