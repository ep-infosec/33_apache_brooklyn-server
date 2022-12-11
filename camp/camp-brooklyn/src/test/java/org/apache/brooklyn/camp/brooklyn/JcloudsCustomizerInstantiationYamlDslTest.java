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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.BasicMachineLocationCustomizer;
import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.camp.brooklyn.spi.creation.CampTypePlanTransformer;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.location.Machines;
import org.apache.brooklyn.core.typereg.RegisteredTypeLoadingContexts;
import org.apache.brooklyn.entity.machine.MachineEntity;
import org.apache.brooklyn.location.jclouds.BasicJcloudsLocationCustomizer;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.location.jclouds.JcloudsMachineLocation;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.collections.MutableList;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.compute.options.TemplateOptions;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

/**
 * The test is designed to ensure that when a customizer is configured in
 * yaml with fields that are configured via DSL (forcing brooklyn to
 * return a {@link org.apache.brooklyn.camp.brooklyn.spi.dsl.methods.BrooklynDslCommon.DslObject})
 * that only one customizer is instantiated so that state may be maintained between customize calls.
 *
 * e.g.
 *
 * <pre>
 * {@code
 * brooklyn.config:
 *   provisioning.properties:
 *     customizers:
 *     - $brooklyn:object:
 *       type: org.apache.brooklyn.location.jclouds.networking.SharedLocationSecurityGroupCustomizer
 *       object.fields:
 *         - enabled: $brooklyn:config("kubernetes.sharedsecuritygroup.create")
 * }
 * </pre>
 */
@Test
public class JcloudsCustomizerInstantiationYamlDslTest extends AbstractJcloudsStubYamlTest {

    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        RecordingLocationCustomizer.clear();
        RecordingMachineCustomizer.clear();
        super.setUp();
    }
    
    @AfterMethod(alwaysRun=true)
    @Override
    public void tearDown() throws Exception {
        try {
            super.tearDown();
        } finally {
            RecordingLocationCustomizer.clear();
            RecordingMachineCustomizer.clear();
        }
    }
    
    @Test
    public void testCustomizers() throws Exception {
        String yaml = Joiner.on("\n").join(
                "location: " + LOCATION_CATALOG_ID,
                "services:\n" +
                "- type: " + MachineEntity.class.getName(),
                "  brooklyn.config:",
                "    onbox.base.dir.skipResolution: true",
                "    sshMonitoring.enabled: false",
                "    metrics.usage.retrieve: false",
                "    enabled: true",
                "    provisioning.properties:",
                "      customizers:",
                "        - $brooklyn:object:",
                "            type: " + RecordingLocationCustomizer.class.getName(),
                "            object.fields:",
                "              enabled: $brooklyn:config(\"enabled\")");

        EntitySpec<?> spec = managementContext.getTypeRegistry().createSpecFromPlan(CampTypePlanTransformer.FORMAT, yaml, RegisteredTypeLoadingContexts.spec(Application.class), EntitySpec.class);
        Entity app = managementContext.getEntityManager().createEntity(spec);

        // On start(), assert customize calls are made
        app.invoke(Startable.START, ImmutableMap.<String, Object>of()).get();
        RecordingLocationCustomizer.assertCallsEqual("customize1", "customize2", "customize3", "customize4");

        // Assert same instance used for all calls during provisioning (may be different instance on stop)
        RecordingLocationCustomizer firstInstance = (RecordingLocationCustomizer) RecordingLocationCustomizer.calls.get(0).instance;
        for (CallParams call : RecordingLocationCustomizer.calls) {
            assertSame(call.instance, firstInstance);
        }

        // On stop, assert pre- and post- release are made
        app.invoke(Startable.STOP, ImmutableMap.<String, Object>of()).get();
        RecordingLocationCustomizer.assertCallsEqual("customize1", "customize2", "customize3", "customize4", "preRelease", "postRelease");
    }

    @Test
    public void testMachineCustomizers() throws Exception {
        String yaml = Joiner.on("\n").join(
                "location: " + LOCATION_CATALOG_ID,
                "services:\n" +
                "- type: " + MachineEntity.class.getName(),
                "  brooklyn.config:",
                "    onbox.base.dir.skipResolution: true",
                "    sshMonitoring.enabled: false",
                "    metrics.usage.retrieve: false",
                "    enabled: true",
                "    provisioning.properties:",
                "      machineCustomizers:",
                "        - $brooklyn:object:",
                "            type: " + RecordingMachineCustomizer.class.getName(),
                "            object.fields:",
                "              enabled: $brooklyn:config(\"enabled\")");

        EntitySpec<?> spec = managementContext.getTypeRegistry().createSpecFromPlan(CampTypePlanTransformer.FORMAT, yaml, RegisteredTypeLoadingContexts.spec(Application.class), EntitySpec.class);
        Entity app = managementContext.getEntityManager().createEntity(spec);
        Entity entity = Iterables.getOnlyElement(app.getChildren());

        // On start, assert customize is called
        app.invoke(Startable.START, ImmutableMap.<String, Object>of()).get();
        SshMachineLocation machine = Machines.findUniqueMachineLocation(entity.getLocations(), SshMachineLocation.class).get();

        RecordingMachineCustomizer.assertCallsEqual("customize");
        RecordingMachineCustomizer.calls.get(0).assertCallEquals("customize", ImmutableList.of(machine));

        // On stop, assert preRelease is called
        app.invoke(Startable.STOP, ImmutableMap.<String, Object>of()).get();
        RecordingMachineCustomizer.assertCallsEqual("customize", "preRelease");
        RecordingMachineCustomizer.calls.get(1).assertCallEquals("preRelease", ImmutableList.of(machine));
    }

    public static class RecordingLocationCustomizer extends BasicJcloudsLocationCustomizer {

        public static final List<CallParams> calls = Lists.newCopyOnWriteArrayList();

        public static void clear() {
            calls.clear();
        }

        static void assertCallsEqual(String... values) {
            List<String> expected = ImmutableList.copyOf(values);
            List<String> actual = new ArrayList<>();
            for (CallParams parm : calls) {
                actual.add(parm.method);
            }
            assertEquals(actual, expected, "actual="+actual+"; expected="+expected);
        }

        private Boolean enabled;

        public void setEnabled(Boolean val) {
            this.enabled = val;
        }

        public static TemplateOptions findTemplateOptionsInCustomizerArgs() {
            for (CallParams call : calls) {
                Optional<?> templateOptions = Iterables.tryFind(call.args, Predicates.instanceOf(TemplateOptions.class));
                if (templateOptions.isPresent()) {
                    return (TemplateOptions) templateOptions.get();
                }
            }
            throw new NoSuchElementException();
        }

        @Override
        public void customize(JcloudsLocation location, ComputeService computeService, TemplateBuilder templateBuilder) {
            if (Boolean.TRUE.equals(enabled)) {
                calls.add(new CallParams(this, "customize1", MutableList.of(location, computeService, templateBuilder)));
            }
        }

        @Override
        public void customize(JcloudsLocation location, ComputeService computeService, Template template) {
            if (Boolean.TRUE.equals(enabled)) {
                calls.add(new CallParams(this, "customize2", MutableList.of(location, computeService, template)));
            }
        }

        @Override
        public void customize(JcloudsLocation location, ComputeService computeService, TemplateOptions templateOptions) {
            if (Boolean.TRUE.equals(enabled)) {
                calls.add(new CallParams(this, "customize3", MutableList.of(location, computeService, templateOptions)));
            }
        }

        @Override
        public void customize(JcloudsLocation location, ComputeService computeService, JcloudsMachineLocation machine) {
            if (Boolean.TRUE.equals(enabled)) {
                calls.add(new CallParams(this, "customize4", MutableList.of(location, computeService, machine)));
            }
        }

        @Override
        public void preRelease(JcloudsMachineLocation machine) {
            if (Boolean.TRUE.equals(enabled)) {
                calls.add(new CallParams(this, "preRelease", MutableList.of(machine)));
            }
        }

        @Override
        public void postRelease(JcloudsMachineLocation machine) {
            if (Boolean.TRUE.equals(enabled)) {
                calls.add(new CallParams(this, "postRelease", MutableList.of(machine)));
            }
        }
    }
    
    public static class RecordingMachineCustomizer extends BasicMachineLocationCustomizer {

        public static final List<CallParams> calls = Lists.newCopyOnWriteArrayList();

        public static void clear() {
            calls.clear();
        }

        static void assertCallsEqual(String... values) {
            List<String> expected = ImmutableList.copyOf(values);
            List<String> actual = new ArrayList<>();
            for (CallParams parm : calls) {
                actual.add(parm.method);
            }
            assertEquals(actual, expected, "actual="+actual+"; expected="+expected);
        }

        private Boolean enabled;

        public void setEnabled(Boolean val) {
            this.enabled = val;
        }
        
        @Override
        public void customize(MachineLocation machine) {
            if (Boolean.TRUE.equals(enabled)) {
                calls.add(new CallParams(this, "customize", MutableList.of(machine)));
            }
        }
        
        @Override
        public void preRelease(MachineLocation machine) {
            if (Boolean.TRUE.equals(enabled)) {
                calls.add(new CallParams(this, "preRelease", MutableList.of(machine)));
            }
        }
    }
    
    public static class CallParams {
        Object instance;
        String method;
        List<?> args;

        public CallParams(Object instance, String method, List<?> args) {
            this.instance = instance;
            this.method = method;
            this.args = args;
        }
        
        void assertCallEquals(String expectedMethod, List<?> expectedArgs) {
            assertEquals(method, expectedMethod);
            assertEquals(args, expectedArgs);
        }
        
    }
}