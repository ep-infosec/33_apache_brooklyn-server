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

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.objs.BrooklynObject;
import org.apache.brooklyn.api.policy.Policy;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.camp.brooklyn.spi.dsl.DslUtils;
import org.apache.brooklyn.camp.brooklyn.spi.dsl.methods.BrooklynDslCommon;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.*;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.core.test.BrooklynAppUnitTestSupport;
import org.apache.brooklyn.core.test.entity.TestApplication;
import org.apache.brooklyn.core.typereg.BasicTypeImplementationPlan;
import org.apache.brooklyn.core.typereg.JavaClassNameTypePlanTransformer;
import org.apache.brooklyn.core.typereg.RegisteredTypes;
import org.apache.brooklyn.core.workflow.*;
import org.apache.brooklyn.core.workflow.steps.flow.LogWorkflowStep;
import org.apache.brooklyn.core.workflow.store.WorkflowStatePersistenceViaSensors;
import org.apache.brooklyn.entity.software.base.EmptySoftwareProcess;
import org.apache.brooklyn.entity.software.base.WorkflowSoftwareProcess;
import org.apache.brooklyn.entity.stock.BasicApplication;
import org.apache.brooklyn.entity.stock.BasicEntity;
import org.apache.brooklyn.location.byon.FixedListMachineProvisioningLocation;
import org.apache.brooklyn.location.localhost.LocalhostMachineProvisioningLocation;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.location.winrm.WinrmWorkflowStep;
import org.apache.brooklyn.tasks.kubectl.ContainerEffectorTest;
import org.apache.brooklyn.tasks.kubectl.ContainerWorkflowStep;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.test.ClassLogWatcher;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.internal.ssh.RecordingSshTool;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.Time;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import static org.apache.brooklyn.util.core.internal.ssh.ExecCmdAsserts.assertExecContains;
import static org.apache.brooklyn.util.core.internal.ssh.ExecCmdAsserts.assertExecsContain;

public class WorkflowYamlTest extends AbstractYamlTest {

    static final String VERSION = "0.1.0-SNAPSHOT";

    @SuppressWarnings("deprecation")
    static RegisteredType addRegisteredTypeBean(ManagementContext mgmt, String symName, Class<?> clazz) {
        return BrooklynAppUnitTestSupport.addRegisteredTypeBean(mgmt, symName, VERSION,
                new BasicTypeImplementationPlan(JavaClassNameTypePlanTransformer.FORMAT, clazz.getName()));
    }

    static RegisteredType addRegisteredTypeSpec(ManagementContext mgmt, String symName, Class<?> clazz, Class<? extends BrooklynObject> superClazz) {
        RegisteredType rt = RegisteredTypes.spec(symName, VERSION,
                new BasicTypeImplementationPlan(JavaClassNameTypePlanTransformer.FORMAT, clazz.getName()));
        RegisteredTypes.addSuperType(rt, superClazz);
        mgmt.getCatalog().validateType(rt, null, false);
        return mgmt.getTypeRegistry().get(rt.getSymbolicName(), rt.getVersion());
    }

    public static void addWorkflowTypes(ManagementContext mgmt) {
        WorkflowBasicTest.addWorkflowStepTypes(mgmt);

        addRegisteredTypeBean(mgmt, "container", ContainerWorkflowStep.class);
        addRegisteredTypeBean(mgmt, "winrm", WinrmWorkflowStep.class);
    }

    @BeforeMethod(alwaysRun = true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        addWorkflowTypes(mgmt());
    }

    @Test
    public void testWorkflowEffector() throws Exception {
        Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicEntity.class.getName(),
                "  brooklyn.initializers:",
                "  - type: workflow-effector",
                "    brooklyn.config:",
                "      name: myWorkflow",
                "      steps:",
                "        - type: no-op",
                "        - type: set-sensor",
                "          input:",
                "            sensor: foo",
                "            value: bar",
                "        - set-sensor integer bar = 1",
                "        - set-config integer foo = 2",
                "");
        waitForApplicationTasks(app);

        Entity entity = Iterables.getOnlyElement(app.getChildren());
        Effector<?> effector = entity.getEntityType().getEffectorByName("myWorkflow").get();

        Task<?> invocation = entity.invoke(effector, null);
        Object result = invocation.getUnchecked();
        Asserts.assertNull(result);
        Dumper.dumpInfo(invocation);

        EntityAsserts.assertAttributeEquals(entity, Sensors.newSensor(Object.class, "foo"), "bar");
        EntityAsserts.assertAttributeEquals(entity, Sensors.newSensor(Object.class, "bar"), 1);
        EntityAsserts.assertConfigEquals(entity, ConfigKeys.newConfigKey(Object.class, "foo"), 2);
    }

    @Test
    public void testWorkflowSensorTrigger() throws Exception {
        doTestWorkflowSensor("triggers: theTrigger", Duration.seconds(1)::isLongerThan);
    }

    @Test(groups="Integration") // because delay
    public void testWorkflowSensorTriggerDoesntRunTooMuch() throws Exception {
        Entity entity = doTestWorkflowSensor("triggers: [ theTrigger, anotherTrigger ]", Duration.seconds(1)::isLongerThan);
        Time.sleep(Duration.millis(500));
        EntityAsserts.assertAttributeEqualsEventually(entity, MY_WORKFLOW_SENSOR, MutableMap.of("foo", "bar", "v", 1));
    }

    @Test(groups="Integration") // because delay
    public void testWorkflowSensorPeriod() throws Exception {
        doTestWorkflowSensor("period: 2s", Duration.seconds(2)::isShorterThan);
    }

    @Test(groups="Integration") // because delay
    public void testWorkflowSensorTriggerWithCondition() throws Exception {
        doTestWorkflowSensor("condition: { sensor: not_exist }\n" + "triggers: theTrigger", null);
    }

    @Test(groups="Integration") // because delay
    public void testWorkflowSensorPeriodWithCondition() throws Exception {
        doTestWorkflowSensor("condition: { sensor: not_exist }\n" + "period: 200 ms", null);
    }

    @Test
    public void testWorkflowPolicyTrigger() throws Exception {
        doTestWorkflowPolicy("triggers: theTrigger", Duration.seconds(1)::isLongerThan);
    }

    @Test(groups="Integration") // because delay
    public void testWorkflowPolicyPeriod() throws Exception {
        doTestWorkflowPolicy("period: 2s", Duration.seconds(2)::isShorterThan);
    }

    @Test(groups="Integration") // because delay
    public void testWorkflowPolicyTriggerWithCondition() throws Exception {
        doTestWorkflowPolicy("condition: { sensor: not_exist }\n" + "triggers: theTrigger", null);
    }

    @Test(groups="Integration") // because delay
    public void testWorkflowPolicyPeriodWithCondition() throws Exception {
        doTestWorkflowPolicy("condition: { sensor: not_exist }\n" + "period: 200 ms", null);
    }

    static final AttributeSensor<Object> MY_WORKFLOW_SENSOR = Sensors.newSensor(Object.class, "myWorkflowSensor");

    Entity doTestWorkflowSensor(String triggers, Predicate<Duration> timeCheckOrNullIfShouldFail) throws Exception {
        Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicEntity.class.getName(),
                "  brooklyn.initializers:",
                "  - type: workflow-sensor",
                "    brooklyn.config:",
                "      sensor: myWorkflowSensor",   // supports old syntax { name: x, targetType: T } or new syntax simple { sensor: Name } or full { sensor: { name: Name, type: T } }
                Strings.indent(6, triggers),
                "      steps:",
                "        - let v = ${entity.sensor.myWorkflowSensor.v} + 1 ?? 0",
                "        - type: let",
                "          variable: out",
                "          value: |",
                "            ignored sample output before doc",
                "            ---",
                "            foo: bar",
                "            v: ${v}",
                "        - transform x = ${out} | yaml",
                "        - return ${x}",
                "");

        Stopwatch sw = Stopwatch.createStarted();
        waitForApplicationTasks(app);
        Duration d1 = Duration.of(sw);

        Entity entity = Iterables.getOnlyElement(app.getChildren());

        if (timeCheckOrNullIfShouldFail!=null) {
            EntityAsserts.assertAttributeEventuallyNonNull(entity, MY_WORKFLOW_SENSOR);
            Duration d2 = Duration.of(sw).subtract(d1);
            // initial set should be soon after startup
            Asserts.assertThat(d2, Duration.millis(500)::isLongerThan);
            EntityAsserts.assertAttributeEqualsEventually(entity, MY_WORKFLOW_SENSOR, MutableMap.of("foo", "bar", "v", 0));

            entity.sensors().set(Sensors.newStringSensor("theTrigger"), "go");
            EntityAsserts.assertAttributeEqualsEventually(entity, MY_WORKFLOW_SENSOR, MutableMap.of("foo", "bar", "v", 1));
            Duration d3 = Duration.of(sw).subtract(d2);
            // the next iteration should obey the time constraint specified above
            if (!timeCheckOrNullIfShouldFail.test(d3)) Asserts.fail("Timing error, took " + d3);

            WorkflowExecutionContext lastWorkflowContext = new WorkflowStatePersistenceViaSensors(mgmt()).getWorkflows(entity).values().iterator().next();
            List<Object> defs = lastWorkflowContext.getStepsDefinition();
            // step definitions should not be resolved by jackson
            defs.forEach(def -> Asserts.assertThat(def, d -> !(d instanceof WorkflowStepDefinition)));
        } else {
            EntityAsserts.assertAttributeEqualsContinually(entity, MY_WORKFLOW_SENSOR, null);
            Asserts.assertThat(new WorkflowStatePersistenceViaSensors(mgmt()).getWorkflows(entity).values(), Collection::isEmpty);
        }
        return entity;
    }

    public void doTestWorkflowPolicy(String triggers, Predicate<Duration> timeCheckOrNullIfShouldFail) throws Exception {
        Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicEntity.class.getName(),
                "  brooklyn.policies:",
                "  - type: workflow-policy",
                "    brooklyn.config:",
                "      name: Set myWorkflowSensor",
                "      id: set-my-workflow-sensor",
                Strings.indent(6, triggers),
                "      steps:",
                "        - let v = ${entity.sensor.myWorkflowSensor.v} + 1 ?? 0",
                "        - type: let",
                "          variable: out",
                "          value: |",
                "            ignored sample output before doc",
                "            ---",
                "            foo: bar",
                "            v: ${v}",
                "        - transform map x = ${out} | yaml",
                "        - set-sensor myWorkflowSensor = ${x}",
                "");

        Stopwatch sw = Stopwatch.createStarted();
        waitForApplicationTasks(app);
        Duration d1 = Duration.of(sw);

        Entity entity = Iterables.getOnlyElement(app.getChildren());
        Policy policy = entity.policies().asList().stream().filter(p -> p instanceof WorkflowPolicy).findAny().get();
        Asserts.assertEquals(policy.getDisplayName(), "Set myWorkflowSensor");
        // should really ID be settable from flag?
        Asserts.assertEquals(policy.getId(), "set-my-workflow-sensor");

        if (timeCheckOrNullIfShouldFail!=null) {
//            EntityAsserts.assertAttributeEventuallyNonNull(entity, s);
            EntityAsserts.assertAttributeEquals(entity, MY_WORKFLOW_SENSOR, null);
            Duration d2 = Duration.of(sw).subtract(d1);
            // initial set should be soon after startup
            Asserts.assertThat(d2, Duration.millis(500)::isLongerThan);
//            EntityAsserts.assertAttributeEqualsEventually(entity, s, MutableMap.of("foo", "bar", "v", 0));

            entity.sensors().set(Sensors.newStringSensor("theTrigger"), "go");
            EntityAsserts.assertAttributeEqualsEventually(entity, MY_WORKFLOW_SENSOR, MutableMap.of("foo", "bar", "v", 0));
//            EntityAsserts.assertAttributeEqualsEventually(entity, s, MutableMap.of("foo", "bar", "v", 1));
            Duration d3 = Duration.of(sw).subtract(d2);
            // the next iteration should obey the time constraint specified above
            if (!timeCheckOrNullIfShouldFail.test(d3)) Asserts.fail("Timing error, took " + d3);
        } else {
            EntityAsserts.assertAttributeEqualsContinually(entity, MY_WORKFLOW_SENSOR, null);
        }
    }

    ClassLogWatcher lastLogWatcher;

    Object invokeWorkflowStepsWithLogging(String ...stepLines) throws Exception {
        try (ClassLogWatcher logWatcher = new ClassLogWatcher(LogWorkflowStep.class)) {
            lastLogWatcher = logWatcher;

            // Declare workflow in a blueprint, add various log steps.
            Entity app = createAndStartApplication(
                    "services:",
                    "- type: " + BasicEntity.class.getName(),
                    "  brooklyn.initializers:",
                    "  - type: workflow-effector",
                    "    brooklyn.config:",
                    "      name: myWorkflow",
                    "      steps:",
                    Strings.indent(8, Strings.lines(stepLines)));
            waitForApplicationTasks(app);

            // Deploy the blueprint.
            Entity entity = Iterables.getOnlyElement(app.getChildren());
            Effector<?> effector = entity.getEntityType().getEffectorByName("myWorkflow").get();
            Task<?> invocation = entity.invoke(effector, null);
            return invocation.getUnchecked();
        }
    }

    void assertLogStepMessages(String ...lines) {
        Assert.assertEquals(lastLogWatcher.getMessages(),
                Arrays.asList(lines));
    }

    @Test
    public void testWorkflowEffectorLogStep() throws Exception {
        invokeWorkflowStepsWithLogging(
                "- log test message 1",
                "- type: log",
                "  id: second",
                "  name: Second Step",
                "  message: test message 2, step '${workflow.current_step.name}' id ${workflow.current_step.step_id} in workflow '${workflow.name}'");

        assertLogStepMessages(
                "test message 1",
                "test message 2, step 'Second Step' id second in workflow 'myWorkflow (workflow effector)'");
    }

    @Test
    public void testWorkflowPropertyNext() throws Exception {
        invokeWorkflowStepsWithLogging(
                "- s: log going to A",
                "  next: A",
                "- s: log now at B",
                "  next: end",
                "  id: B",
                "- s: log now at A",
                "  id: A",
                "  next: B");
        assertLogStepMessages(
                    "going to A",
                    "now at A",
                    "now at B");
    }

//    // TODO test timeout
//    @Test
//    public void testTimeoutWithInfiniteLoop() throws Exception {
//        invokeWorkflowStepsWithLogging(
//                "        - s: log going to A",
//                        "          next: A",
//                        "        - s: log now at B",
//                        "          id: B",
//                        "        - s: sleep 100ms",
//                        "          next: B",
//                        "        - s: log now at A",
//                        "          id: A",
//                        "          next: B");
//        // TODO assert it takes at least 100ms, but less than 5s
//        assertLogStepMessages(
//                ...);
//    }

    void doTestWorkflowCondition(String setCommand, String logAccess, String conditionAccess) throws Exception {
        invokeWorkflowStepsWithLogging(
                    "- log start",
                    "- " + setCommand + " color = blue",
                    "- id: log-color",
                    "  s: log color " + logAccess,
                    "-",
                    "  s: log not blue",
                    "  condition:",
                    "    " + conditionAccess,
                    "    assert: { when: present, java-instance-of: string }",
                    "    not: { equals: blue }",
                    "-",
                    "  type: no-op",
                    "  next: make-red",
                    "  condition:",
                    "    " + conditionAccess,
                    "    equals: blue",
                    "-",
                    "  type: no-op",
                    "  next: log-end",
                    "- id: make-red",
                    "  s: " + setCommand + " color = red",
                    "  next: log-color",
                    "- id: log-end",
                    "  s: log end",
                    "");
        assertLogStepMessages(
                    "start", "color blue", "color red", "not blue", "end");
    }

    @Test
    public void testWorkflowSensorCondition() throws Exception {
        doTestWorkflowCondition("set-sensor", "${entity.sensor.color}", "sensor: color");
    }

    @Test
    public void testWorkflowVariableInCondition() throws Exception {
        doTestWorkflowCondition("let", "${color}", "target: ${color}");
    }

    @Test
    public void testEffectorToSetColorSensorConditionally() throws Exception {
        // Declare workflow in a blueprint, add various log steps.
        Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicEntity.class.getName(),
                "  brooklyn.initializers:",
                "  - type: workflow-effector",
                "    brooklyn.config:",
                "      name: myWorkflow",
                "      parameters:\n" +
                "        color:\n" +
                "          type: string\n" +
                "          description: What color do you want to set?\n" +
                "\n" +
                "      steps:\n" +
//                "        - let old_color = ${(entity.sensor.color)! \"unset\"}\n" +
                        // above does not work. but the below is recommended
                "        - let old_color = ${entity.sensor.color} ?? \"unset\"\n" +

                        // alternative (supported) if not using nullish operator
//                "        - let old_color = unset\n" +
//                "        - s: let old_color = ${entity.sensor.color}\n" +
//                "          condition:\n" +
//                "            sensor: color\n" +
//                "            when: present_non_null\n" +

                "        - log changing color sensor from ${old_color} to ${color}\n" +
                "        - set-sensor color = ${color}\n" +
                "        - s: set-sensor color_is_red = true\n" +
                "          condition:\n" +
                "            sensor: color\n" +
                "            equals: red\n" +
                "          next: end\n" +
                "        - set-sensor color_is_red = false");

        Entity entity = Iterables.getOnlyElement(app.getChildren());
        Effector<?> effector = entity.getEntityType().getEffectorByName("myWorkflow").get();

        entity.invoke(effector, MutableMap.of("color", "red")).get();
        EntityAsserts.assertAttributeEquals(entity, Sensors.newStringSensor("color"), "red");
        EntityAsserts.assertAttributeEquals(entity, Sensors.newStringSensor("color_is_red"), "true");

        entity.invoke(effector, MutableMap.of("color", "blue")).get();
        EntityAsserts.assertAttributeEquals(entity, Sensors.newStringSensor("color"), "blue");
        EntityAsserts.assertAttributeEquals(entity, Sensors.newStringSensor("color_is_red"), "false");

        entity.invoke(effector, MutableMap.of("color", "red")).get();
        EntityAsserts.assertAttributeEquals(entity, Sensors.newStringSensor("color"), "red");
        EntityAsserts.assertAttributeEquals(entity, Sensors.newStringSensor("color_is_red"), "true");

    }

    @Test
    public void testInvalidStepsFailDeployment() throws Exception {
        try {
            createAndStartApplication(
                    "services:",
                    "- type: " + BasicEntity.class.getName(),
                    "  brooklyn.initializers:",
                    "  - type: workflow-effector",
                    "    brooklyn.config:",
                    "      name: myWorkflow",
                            "      steps:\n" +
                            "        - unsupported-type"
            );
            Asserts.shouldHaveFailedPreviously();
        } catch (Exception e) {
            Asserts.expectedFailureContainsIgnoreCase(e, "resolve step", "unsupported-type");
        }
    }

    @Test
    public void testWorkflowSoftwareProcessAsYaml() throws Exception {
        RecordingSshTool.clear();

        FixedListMachineProvisioningLocation loc = mgmt().getLocationManager().createLocation(LocationSpec.create(FixedListMachineProvisioningLocation.class)
                .configure(FixedListMachineProvisioningLocation.MACHINE_SPECS, ImmutableList.<LocationSpec<? extends MachineLocation>>of(
                        LocationSpec.create(SshMachineLocation.class)
                                .configure("address", "1.2.3.4")
                                .configure(SshMachineLocation.SSH_TOOL_CLASS, RecordingSshTool.class.getName()))));

        Application app = createApplicationUnstarted(
                "services:",
                "- type: " + WorkflowSoftwareProcess.class.getName(),
                "  brooklyn.config:",
                "    "+BrooklynConfigKeys.SKIP_ON_BOX_BASE_DIR_RESOLUTION.getName()+": true",
                "    install.workflow:",
                "      steps:",
                "        - ssh installWorkflow",
                "        - set-sensor boolean installed = true",
                "        - type: no-op",
                "    stop.workflow:",
                "      steps:",
                "        - ssh stopWorkflow",
                "        - set-sensor boolean stopped = true"
        );

        Entity child = app.getChildren().iterator().next();
        List<Object> steps = child.config().get(WorkflowSoftwareProcess.INSTALL_WORKFLOW).peekSteps();
        // should not be resolved yet
        steps.forEach(def -> Asserts.assertThat(def, d -> !(d instanceof WorkflowStepDefinition)));

        ((Startable)app).start(MutableList.of(loc));

        assertExecsContain(RecordingSshTool.getExecCmds(), ImmutableList.of(
                "installWorkflow"));

        EntityAsserts.assertAttributeEquals(child, Sensors.newSensor(Boolean.class, "installed"), true);
        EntityAsserts.assertAttributeEquals(child, Sensors.newSensor(Boolean.class, "stopped"), null);

        EntityAsserts.assertAttributeEqualsEventually(child, Attributes.SERVICE_UP, true);
        EntityAsserts.assertAttributeEqualsEventually(child, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);

        WorkflowExecutionContext lastWorkflowContext = new WorkflowStatePersistenceViaSensors(mgmt()).getWorkflows(child).values().iterator().next();
        List<Object> defs = lastWorkflowContext.getStepsDefinition();
        // step definitions should not be resolved by jackson
        defs.forEach(def -> Asserts.assertThat(def, d -> !(d instanceof WorkflowStepDefinition)));

        ((Startable)app).stop();

        EntityAsserts.assertAttributeEquals(child, Sensors.newSensor(Boolean.class, "stopped"), true);
        assertExecContains(RecordingSshTool.getLastExecCmd(), "stopWorkflow");

        EntityAsserts.assertAttributeEqualsEventually(child, Attributes.SERVICE_UP, false);
        EntityAsserts.assertAttributeEqualsEventually(child, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPED);
    }

    @Test
    public void testConditionNormal() throws Exception {
        Asserts.assertEquals(doTestCondition("regex: .*oh no.*"), "expected failure");
    }
    @Test
    public void testConditionBadSerialization() throws Exception {
        Asserts.assertFailsWith(() -> doTestCondition("- regex: .*oh no.*"),
                e -> Asserts.expectedFailureContainsIgnoreCase(e, "unresolveable", "regex"));
    }
    @Test
    public void testBadExpressionAllowedInCondition() throws Exception {
        Asserts.assertEquals(doTestCondition(Strings.lines(
                "any:",
                "- target: ${bad_var}",
                "  when: absent"
        )), "expected failure");
    }
    @Test
    public void testMultilineErrorMessageRegexHandling() throws Exception {
        Asserts.assertEquals(doTestCondition(Strings.lines(
                "any:",
                "- regex: .*oh no.*"
        )), "expected failure");
    }

    Object doTestCondition(String lines) throws Exception {
        Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicEntity.class.getName(),
                "  brooklyn.initializers:",
                "  - type: workflow-effector",
                "    brooklyn.config:",
                "      name: myWorkflow",
                "      steps:",
                "        - step: fail message oh no",
                "          on-error:",
                "          - step: return expected failure",
                "            condition:",
                Strings.indent(14, lines));
        waitForApplicationTasks(app);
        Entity entity = Iterables.getOnlyElement(app.getChildren());
        Effector<?> effector = entity.getEntityType().getEffectorByName("myWorkflow").get();

        Task<?> invocation = entity.invoke(effector, null);
        return invocation.getUnchecked();
    }

    @Test
    public void testAccessEntities() throws Exception {
        Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicEntity.class.getName(),
                "  brooklyn.config:",
                "    e1: $brooklyn:self()",
                "  brooklyn.initializers:",
                "  - type: workflow-effector",
                "    brooklyn.config:",
                "      name: myWorkflow",
                "      input:",
                "        e2: $brooklyn:self()",
                "        e3: $brooklyn:config(\"e1\")",
                "      steps:",
                "        - log e1 is ${entity.config.e1.name}",
                "        - log e2 is ${e2.name}",
                "        - log e3 is ${e3.name}",
                "        - step: log e0-a is ${e0.name}",
                "          input:",
                "            e0: $brooklyn:self()",
                "        - step: log e0-b is ${e0.name}",
                "          input:",
                "            e0: $brooklyn:config(\"e1\")",
                "        - step: log e0-c is ${e0.name}",
                "          input:",
                "            e0: ${e2}",
                "        - step: log e0-d is ${e0.name}",
                "          input:",
                "            e0: ${e3}",
                "");
        waitForApplicationTasks(app);
        Entity entity = Iterables.getOnlyElement(app.getChildren());
        Effector<?> effector = entity.getEntityType().getEffectorByName("myWorkflow").get();

        // none of the above should throw exceptions
        Task<?> invocation = entity.invoke(effector, null);
        invocation.getUnchecked();
    }

    @Test
    public void testConditionSensorAbsent() throws Exception {
        Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicEntity.class.getName(),
                "  brooklyn.initializers:",
                "  - type: workflow-effector",
                "    brooklyn.config:",
                "      name: toTest",   // supports old syntax { name: x, targetType: T } or new syntax simple { sensor: Name } or full { sensor: { name: Name, type: T } }
                "      steps:",
                "        - step: set-sensor foo = 1",
                "          condition:",
                "            sensor: foo",
                "            when: absent",
                "        - let foo = ${entity.sensor.foo} + 1",
                "        - set-sensor foo = ${foo}",
                "        - step: goto start",
                "          condition:",
                "            sensor: foo",
                "            less-than: 10",
                "        - return ${entity.sensor.foo}",
                "");

        Entity entity = Iterables.getOnlyElement(app.getChildren());
        Effector<?> effector = entity.getEntityType().getEffectorByName("toTest").get();

        Task<?> invocation = entity.invoke(effector, null);
        Object result = invocation.getUnchecked();
        Asserts.assertEquals(result, 10);

        invocation = entity.invoke(effector, null);
        result = invocation.getUnchecked();
        Asserts.assertEquals(result, 11);
    }

    @Test(groups="Live")
    public void testContainerEchoBashCommandAsWorkflowEffectorWithVarFromConfig() throws Exception {
        WorkflowBasicTest.addRegisteredTypeBean(mgmt(), "container", ContainerWorkflowStep.class);
        BrooklynDslCommon.registerSerializationHooks();
        final String message = ("hello " + Strings.makeRandomId(10)).toLowerCase();

        EntitySpec<TestApplication> appSpec = EntitySpec.create(TestApplication.class);
        TestApplication app = mgmt().getEntityManager().createEntity(appSpec);

        Object output = ContainerEffectorTest.doTestEchoBashCommand(app, () -> {
            ConfigBag parameters = ConfigBag.newInstance(ImmutableMap.of(
                    WorkflowEffector.EFFECTOR_NAME, "test-container-effector",
                    WorkflowEffector.STEPS, MutableList.of(
                            MutableMap.<String, Object>of("step", "container " + ContainerEffectorTest.BASH_SCRIPT_CONTAINER + " echo " + message + " $VAR",
                                    "input",
                                    MutableMap.of("env", MutableMap.of("VAR", "$brooklyn:config(\"hello\")")),
                                    "output", "${stdout}"))));

            return new WorkflowEffector(parameters);
        }, entity -> entity.config().set(ConfigKeys.newStringConfigKey("hello"), "world"));
        Asserts.assertEquals(output.toString().trim(), message + " world");
    }

    public static class SetDeepTestStep extends WorkflowStepDefinition {
        @Override
        public void populateFromShorthand(String value) {
            // no shorthand
        }

        @Override
        protected Object doTaskBody(WorkflowStepInstanceExecutionContext context) {
            return MutableMap.of("x", DslUtils.parseBrooklynDsl(context.getManagementContext(), "$brooklyn:config(\"arg1\")"));
        }

        @Override
        protected Boolean isDefaultIdempotent() {
            return true;
        }
    }

    @Test(groups="Live")
    public void testSshEchoBashCommandAsWorkflowEffectorWithDeepVarFromConfig() throws Exception {
        WorkflowBasicTest.addRegisteredTypeBean(mgmt(), "container", ContainerWorkflowStep.class);
        WorkflowBasicTest.addRegisteredTypeBean(mgmt(), "set-deep", SetDeepTestStep.class);
        BrooklynDslCommon.registerSerializationHooks();
        final String message = ("hello " + Strings.makeRandomId(10)).toLowerCase();

        EntitySpec<TestApplication> appSpec = EntitySpec.create(TestApplication.class);
        TestApplication app = mgmt().getEntityManager().createEntity(appSpec);

        ConfigBag parameters = ConfigBag.newInstance(ImmutableMap.of(
                WorkflowEffector.EFFECTOR_NAME, "test-ssh-effector",
                WorkflowEffector.PARAMETER_DEFS, MutableMap.of("env", null),
                WorkflowEffector.STEPS, MutableList.of(
                        MutableMap.of("step", "let map env_local", "value", MutableMap.of("VAR1", "$brooklyn:config(\"hello\")", "ENTITY_ID", "$brooklyn:entityId()")),
                        "transform env = ${env} ${env_local} | merge map",
                        "set-deep",
                        "let env.VAR3 = ${workflow.previous_step.output}",
                        MutableMap.<String, Object>of("step", "ssh echo "+ message+" Entity:$ENTITY_ID:$VAR1:$VAR2:$VAR3",
                                "input",
                                MutableMap.of("env", "${env}"),
                                "output", "${stdout}"))));

        WorkflowEffector initializer = new WorkflowEffector(parameters);

        EmptySoftwareProcess child = app.createAndManageChild(EntitySpec.create(EmptySoftwareProcess.class).location(LocationSpec.create(LocalhostMachineProvisioningLocation.class)).
                addInitializer(initializer));
        app.start(ImmutableList.of());
        child.config().set(ConfigKeys.newStringConfigKey("hello"), "world");
        child.config().set(ConfigKeys.newStringConfigKey("arg1"), "Arg1");

        Object output = Entities.invokeEffector(app, child, ((EntityInternal) child).getEffector("test-ssh-effector"), MutableMap.of("env",
                MutableMap.of("VAR2", DslUtils.parseBrooklynDsl(mgmt(), "$brooklyn:config(\"arg1\")")))).getUnchecked(Duration.ONE_MINUTE);

        Asserts.assertEquals(output.toString().trim(), message + " Entity:"+app.getChildren().iterator().next().getId()+":"+"world:Arg1:{\"x\":\"Arg1\"}");
    }

    @Test
    public void testEffectorArgDslInMap() {
        BrooklynDslCommon.registerSerializationHooks();
        BasicApplication app = mgmt().getEntityManager().createEntity(EntitySpec.create(BasicApplication.class).configure("z", "Z"));

        WorkflowEffector eff = new WorkflowEffector(ConfigBag.newInstance()
                .configure(WorkflowEffector.EFFECTOR_NAME, "myWorkflowEffector3")
                .configure(WorkflowEffector.EFFECTOR_PARAMETER_DEFS, MutableMap.of("x", MutableMap.of("type", "map")))
                .configure(WorkflowEffector.STEPS, MutableList.of("return ${x}")));
        eff.apply((EntityLocal)app);
        eff = new WorkflowEffector(ConfigBag.newInstance()
                .configure(WorkflowEffector.EFFECTOR_NAME, "myWorkflowEffector2")
                .configure(WorkflowEffector.EFFECTOR_PARAMETER_DEFS, MutableMap.of("x", MutableMap.of()))
                .configure(WorkflowEffector.STEPS, MutableList.of(MutableMap.of("step", "invoke-effector myWorkflowEffector3",
                        "args", MutableMap.of("x", "${x}")))));
        eff.apply((EntityLocal)app);
        eff = new WorkflowEffector(ConfigBag.newInstance()
                .configure(WorkflowEffector.EFFECTOR_NAME, "myWorkflowEffector1")
                .configure(WorkflowEffector.EFFECTOR_PARAMETER_DEFS, MutableMap.of("x", MutableMap.of()))
                .configure(WorkflowEffector.STEPS, MutableList.of(MutableMap.of("step", "invoke-effector myWorkflowEffector2",
                        "args", MutableMap.of("x", MutableMap.of("y", "$brooklyn:config(\"z\")"))))));
        eff.apply((EntityLocal)app);

        Task<?> invocation = app.invoke(app.getEntityType().getEffectorByName("myWorkflowEffector1").get(), MutableMap.of());
        Asserts.assertEquals(invocation.getUnchecked(), MutableMap.of("y","Z"));
    }

    @Test(groups="Live")
    public void testEffectorSshEnvArgDslInMap() {
        BrooklynDslCommon.registerSerializationHooks();
        TestApplication app = mgmt().getEntityManager().createEntity(EntitySpec.create(TestApplication.class).configure("z", "Z"));

        EmptySoftwareProcess child = app.createAndManageChild(EntitySpec.create(EmptySoftwareProcess.class).location(LocationSpec.create(LocalhostMachineProvisioningLocation.class)));
        app.start(ImmutableList.of());

        WorkflowEffector eff = new WorkflowEffector(ConfigBag.newInstance()
                .configure(WorkflowEffector.EFFECTOR_NAME, "myWorkflowEffector3")
                .configure(WorkflowEffector.EFFECTOR_PARAMETER_DEFS, MutableMap.of("script", MutableMap.of(), "env", MutableMap.of("defaultValue", MutableMap.of())))
                .configure(WorkflowEffector.STEPS, MutableList.of(
                        MutableMap.of("type", "ssh",
                                "command", "bash -c \"${script}\"",
                                "env", "${env}"),
                        "return ${stdout}")));
        eff.apply((EntityLocal)child);
        eff = new WorkflowEffector(ConfigBag.newInstance()
                .configure(WorkflowEffector.EFFECTOR_NAME, "myWorkflowEffector2")
                .configure(WorkflowEffector.EFFECTOR_PARAMETER_DEFS, MutableMap.of("script", MutableMap.of(), "env", MutableMap.of("defaultValue", MutableMap.of())))
                .configure(WorkflowEffector.STEPS, MutableList.of(MutableMap.of("step", "invoke-effector myWorkflowEffector3",
                        "args", MutableMap.of("script", "${script}", "env", "${env}")))));
        eff.apply((EntityLocal)child);
        eff = new WorkflowEffector(ConfigBag.newInstance()
                .configure(WorkflowEffector.EFFECTOR_NAME, "myWorkflowEffector1")
                .configure(WorkflowEffector.STEPS, MutableList.of(MutableMap.of("step", "invoke-effector myWorkflowEffector2",
                        "args", MutableMap.of("script", "echo Y is $Y", "env", MutableMap.of("Y", "$brooklyn:config(\"z\")"))))));
        eff.apply((EntityLocal)child);

        Task<?> invocation = child.invoke(child.getEntityType().getEffectorByName("myWorkflowEffector1").get(), MutableMap.of());
        Asserts.assertEquals(invocation.getUnchecked().toString().trim(), "Y is Z");
    }

    @Test
    public void testInitializer() throws Exception {
        Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicEntity.class.getName(),
                "  brooklyn.initializers:",
                "  - type: workflow-initializer",
                "    brooklyn.config:",
                "      name: myWorkflow",
                "      steps:",
                "        - set-sensor boolean initializer_ran = true");
        waitForApplicationTasks(app);
        Entity entity = Iterables.getOnlyElement(app.getChildren());
        EntityAsserts.assertAttributeEquals(entity, Sensors.newSensor(Object.class, "initializer_ran"), true);
    }

    @Test
    public void testInitializerDelay() throws Exception {
        Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicEntity.class.getName(),
                "  brooklyn.initializers:",
                "  - type: workflow-initializer",
                "    brooklyn.config:",
                "      name: post-init",
                "      delay: async",
                "      steps:",
                "        - let x = ${entity.sensor.x} * 2",
                "        - set-sensor x = ${x}",

                "  - type: workflow-initializer",
                "    brooklyn.config:",
                "      name: pre-init",
                "      steps:",
                "        - set-sensor integer x = 3");
        waitForApplicationTasks(app);
        Entity entity = Iterables.getOnlyElement(app.getChildren());
        EntityAsserts.assertAttributeEquals(entity, Sensors.newIntegerSensor("x"), 6);
    }

    @Test(groups="Integration") //because of 500ms delay
    public void testInitializerDelayDuration() throws Exception {
        Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicEntity.class.getName(),
                "  brooklyn.initializers:",

                "  - type: workflow-initializer",
                "    brooklyn.config:",
                "      name: post-init-2",
                "      delay: 500ms",
                "      steps:",
                "        - let x = ${entity.sensor.x} + 1",  // will cause 7 if runs after the other post-init (desired); problems: 8 if before, and 4 or 6 if they race
                "        - set-sensor x = ${x}",

                "  - type: workflow-initializer",
                "    brooklyn.config:",
                "      name: post-init",
                "      delay: async",
                "      steps:",
                "        - let x = ${entity.sensor.x} * 2",
                "        - set-sensor x = ${x}",

                "  - type: workflow-initializer",
                "    brooklyn.config:",
                "      name: pre-init",
                "      steps:",
                "        - set-sensor integer x = 3");
        waitForApplicationTasks(app);

        Entity entity = Iterables.getOnlyElement(app.getChildren());
        EntityAsserts.assertAttributeEqualsEventually(entity, Sensors.newIntegerSensor("x"), 7);
    }

}
