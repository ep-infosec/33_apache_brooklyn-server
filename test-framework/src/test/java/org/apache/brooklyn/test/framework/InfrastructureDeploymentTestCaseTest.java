/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.brooklyn.test.framework;

import static org.apache.brooklyn.core.entity.trait.Startable.SERVICE_UP;
import static org.apache.brooklyn.test.Asserts.fail;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.StartableApplication;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.sensor.AttributeSensorAndConfigKey;
import org.apache.brooklyn.core.test.entity.TestApplication;
import org.apache.brooklyn.entity.stock.BasicApplication;
import org.apache.brooklyn.location.localhost.LocalhostMachineProvisioningLocation;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.test.framework.entity.TestInfrastructure;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;

@SuppressWarnings("serial")
public class InfrastructureDeploymentTestCaseTest {

    private TestApplication app;
    private ManagementContext managementContext;
    private LocalhostMachineProvisioningLocation loc;
    private LocalhostMachineProvisioningLocation infrastructureLoc;
    private String LOC_NAME = "location";
    private String INFRASTRUCTURE_LOC_NAME = "Infrastructure location";

    private static final AttributeSensorAndConfigKey<Location, Location> DEPLOYMENT_LOCATION_SENSOR =
            ConfigKeys.newSensorAndConfigKey(
                    new TypeToken<Location>() {},
                    "deploymentLocationSensor", "The location to deploy to");

    @BeforeMethod
    public void setup() {
        app = TestApplication.Factory.newManagedInstanceForTests();
        managementContext = app.getManagementContext();

        loc = managementContext.getLocationManager()
                .createLocation(LocationSpec.create(LocalhostMachineProvisioningLocation.class)
                        .configure("name", LOC_NAME));

        infrastructureLoc = managementContext.getLocationManager()
                .createLocation(LocationSpec.create(LocalhostMachineProvisioningLocation.class)
                        .configure("name", INFRASTRUCTURE_LOC_NAME));
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        if (app != null) Entities.destroyAll(app.getManagementContext());
    }

    @Test
    public void testVanilla() {
        EntitySpec<TestInfrastructure> infrastructureSpec = EntitySpec.create(TestInfrastructure.class);
        infrastructureSpec.configure(DEPLOYMENT_LOCATION_SENSOR, infrastructureLoc);

        List<EntitySpec<? extends Startable>> testSpecs = ImmutableList.<EntitySpec<? extends Startable>>of(EntitySpec.create(BasicApplication.class));

        InfrastructureDeploymentTestCase infrastructureDeploymentTestCase = app.createAndManageChild(EntitySpec.create(InfrastructureDeploymentTestCase.class));
        infrastructureDeploymentTestCase.config().set(InfrastructureDeploymentTestCase.INFRASTRUCTURE_SPEC, infrastructureSpec);
        infrastructureDeploymentTestCase.config().set(InfrastructureDeploymentTestCase.ENTITY_SPEC_TO_DEPLOY, testSpecs);
        infrastructureDeploymentTestCase.config().set(InfrastructureDeploymentTestCase.DEPLOYMENT_LOCATION_SENSOR_NAME, DEPLOYMENT_LOCATION_SENSOR.getName());

        app.start(ImmutableList.of(loc));

        assertThat(infrastructureDeploymentTestCase.sensors().get(SERVICE_UP)).isTrue();
        assertThat(infrastructureDeploymentTestCase.getChildren().size()).isEqualTo(2);

        boolean seenInfrastructure = false;
        boolean seenEntity = false;

        for (Entity entity : infrastructureDeploymentTestCase.getChildren()) {
            assertThat(entity.getLocations().size()).isEqualTo(1);
            assertThat(entity.sensors().get(SERVICE_UP)).isTrue();

            if (entity instanceof TestInfrastructure  && !seenInfrastructure) {
                assertThat(entity.getLocations().iterator().next().getDisplayName()).isEqualTo(LOC_NAME);
                seenInfrastructure = true;
            } else if (entity instanceof BasicApplication){
                assertThat(entity.getLocations().iterator().next().getDisplayName()).isEqualTo(INFRASTRUCTURE_LOC_NAME);
                seenEntity = true;
            } else {
                fail("Unknown child of InfrastructureDeploymentTestCase");
            }
        }

        assertThat(seenInfrastructure).isTrue();
        assertThat(seenEntity).isTrue();
    }

    @Test
    public void testMultipleSpec() {
        EntitySpec<TestInfrastructure> infrastructureSpec = EntitySpec.create(TestInfrastructure.class);
        infrastructureSpec.configure(DEPLOYMENT_LOCATION_SENSOR, infrastructureLoc);

        List<EntitySpec<? extends Startable>> testSpecs = ImmutableList.<EntitySpec<? extends Startable>>of
                (EntitySpec.create(BasicApplication.class),
                        (EntitySpec.create(BasicApplication.class)));

        InfrastructureDeploymentTestCase infrastructureDeploymentTestCase = app.createAndManageChild(EntitySpec.create(InfrastructureDeploymentTestCase.class));
        infrastructureDeploymentTestCase.config().set(InfrastructureDeploymentTestCase.INFRASTRUCTURE_SPEC, infrastructureSpec);
        infrastructureDeploymentTestCase.config().set(InfrastructureDeploymentTestCase.ENTITY_SPEC_TO_DEPLOY, testSpecs);
        infrastructureDeploymentTestCase.config().set(InfrastructureDeploymentTestCase.DEPLOYMENT_LOCATION_SENSOR_NAME, DEPLOYMENT_LOCATION_SENSOR.getName());

        app.start(ImmutableList.of(loc));

        assertThat(infrastructureDeploymentTestCase.sensors().get(SERVICE_UP)).isTrue();
        assertThat(infrastructureDeploymentTestCase.getChildren().size()).isEqualTo(3);

        boolean seenInfrastructure = false;
        int entitiesSeen = 0;

        for (Entity entity : infrastructureDeploymentTestCase.getChildren()) {
            assertThat(entity.sensors().get(SERVICE_UP)).isTrue();
            assertThat(entity.getLocations().size()).isEqualTo(1);

            if (entity instanceof TestInfrastructure && !seenInfrastructure) {
                assertThat(entity.getLocations().iterator().next().getDisplayName()).isEqualTo(LOC_NAME);
                seenInfrastructure = true;
            } else if (entity instanceof BasicApplication) {
                assertThat(entity.getLocations().iterator().next().getDisplayName()).isEqualTo(INFRASTRUCTURE_LOC_NAME);
                entitiesSeen++;
            } else {
                fail("Unknown child of InfrastructureDeploymentTestCase");
            }
        }

        assertThat(seenInfrastructure).isTrue();
        assertThat(entitiesSeen).isEqualTo(2);
    }

    @Test
    public void testNoInfrastructureSpec() {
        List<EntitySpec<? extends Startable>> testSpecs = ImmutableList.<EntitySpec<? extends Startable>>of(EntitySpec.create(StartableApplication.class));

        InfrastructureDeploymentTestCase infrastructureDeploymentTestCase = app.createAndManageChild(EntitySpec.create(InfrastructureDeploymentTestCase.class));
        infrastructureDeploymentTestCase.config().set(InfrastructureDeploymentTestCase.ENTITY_SPEC_TO_DEPLOY, testSpecs);
        infrastructureDeploymentTestCase.config().set(InfrastructureDeploymentTestCase.DEPLOYMENT_LOCATION_SENSOR_NAME, DEPLOYMENT_LOCATION_SENSOR.getName());

        try {
            app.start(ImmutableList.of(app.newSimulatedLocation()));
            Asserts.shouldHaveFailedPreviously();
        } catch (Throwable throwable) {
            Asserts.expectedFailureContains(throwable, "EntitySpec", "not configured");
        }

        assertThat(infrastructureDeploymentTestCase.sensors().get(SERVICE_UP)).isFalse();
    }

    @Test
    public void testNoEntitySpec() {
        EntitySpec<TestInfrastructure> infrastructureSpec = EntitySpec.create(TestInfrastructure.class);
        infrastructureSpec.configure(DEPLOYMENT_LOCATION_SENSOR, infrastructureLoc);

        InfrastructureDeploymentTestCase infrastructureDeploymentTestCase = app.createAndManageChild(EntitySpec.create(InfrastructureDeploymentTestCase.class));
        infrastructureDeploymentTestCase.config().set(InfrastructureDeploymentTestCase.INFRASTRUCTURE_SPEC, infrastructureSpec);
        infrastructureDeploymentTestCase.config().set(InfrastructureDeploymentTestCase.DEPLOYMENT_LOCATION_SENSOR_NAME, DEPLOYMENT_LOCATION_SENSOR.getName());

        try {
            app.start(ImmutableList.of(app.newSimulatedLocation()));
            Asserts.shouldHaveFailedPreviously();
        } catch (Throwable throwable) {
            Asserts.expectedFailureContains(throwable, "entity.specs", "List", "not configured");
        }

        assertThat(infrastructureDeploymentTestCase.sensors().get(SERVICE_UP)).isFalse();
    }

    @Test
    public void testNoDeploymentLocation() {
        EntitySpec<TestInfrastructure> infrastructureSpec = EntitySpec.create(TestInfrastructure.class);
        infrastructureSpec.configure(DEPLOYMENT_LOCATION_SENSOR, infrastructureLoc);

        List<EntitySpec<? extends Startable>> testSpecs = ImmutableList.<EntitySpec<? extends Startable>>of(EntitySpec.create(BasicApplication.class));

        InfrastructureDeploymentTestCase infrastructureDeploymentTestCase = app.createAndManageChild(EntitySpec.create(InfrastructureDeploymentTestCase.class));
        infrastructureDeploymentTestCase.config().set(InfrastructureDeploymentTestCase.INFRASTRUCTURE_SPEC, infrastructureSpec);
        infrastructureDeploymentTestCase.config().set(InfrastructureDeploymentTestCase.ENTITY_SPEC_TO_DEPLOY, testSpecs);

        try {
            app.start(ImmutableList.of(app.newSimulatedLocation()));
            Asserts.shouldHaveFailedPreviously();
        } catch (Throwable throwable) {
            Asserts.expectedFailure(throwable);
        }

        assertThat(infrastructureDeploymentTestCase.sensors().get(SERVICE_UP)).isFalse();
    }

    @Test
    public void testInfrastrucutreHasNoLocation() {
        EntitySpec<TestInfrastructure> infrastructureSpec = EntitySpec.create(TestInfrastructure.class);

        List<EntitySpec<? extends Startable>> testSpecs = ImmutableList.<EntitySpec<? extends Startable>>of(EntitySpec.create(BasicApplication.class));

        InfrastructureDeploymentTestCase infrastructureDeploymentTestCase = app.createAndManageChild(EntitySpec.create(InfrastructureDeploymentTestCase.class));
        infrastructureDeploymentTestCase.config().set(InfrastructureDeploymentTestCase.INFRASTRUCTURE_SPEC, infrastructureSpec);
        infrastructureDeploymentTestCase.config().set(InfrastructureDeploymentTestCase.ENTITY_SPEC_TO_DEPLOY, testSpecs);
        infrastructureDeploymentTestCase.config().set(InfrastructureDeploymentTestCase.DEPLOYMENT_LOCATION_SENSOR_NAME, DEPLOYMENT_LOCATION_SENSOR.getName());

        try {
            app.start(ImmutableList.of(app.newSimulatedLocation()));
            Asserts.shouldHaveFailedPreviously();
        } catch (Throwable throwable) {
            Asserts.expectedFailure(throwable);
        }

        assertThat(infrastructureDeploymentTestCase.sensors().get(SERVICE_UP)).isFalse();
    }
}