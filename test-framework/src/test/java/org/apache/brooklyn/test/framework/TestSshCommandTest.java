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
package org.apache.brooklyn.test.framework;

import static org.apache.brooklyn.core.entity.EntityAsserts.assertEntityFailed;
import static org.apache.brooklyn.core.entity.EntityAsserts.assertEntityHealthy;
import static org.apache.brooklyn.test.framework.BaseTest.TIMEOUT;
import static org.apache.brooklyn.test.framework.TargetableTestComponent.TARGET_ENTITY;
import static org.apache.brooklyn.test.framework.TestFrameworkAssertions.CONTAINS;
import static org.apache.brooklyn.test.framework.TestFrameworkAssertions.EQUALS;
import static org.apache.brooklyn.test.framework.TestSshCommand.ASSERT_ERR;
import static org.apache.brooklyn.test.framework.TestSshCommand.ASSERT_OUT;
import static org.apache.brooklyn.test.framework.TestSshCommand.ASSERT_STATUS;
import static org.apache.brooklyn.test.framework.TestSshCommand.BACKOFF_TO_PERIOD;
import static org.apache.brooklyn.test.framework.TestSshCommand.COMMAND;
import static org.apache.brooklyn.test.framework.TestSshCommand.DOWNLOAD_URL;
import static org.apache.brooklyn.test.framework.TestSshCommand.MAX_ATTEMPTS;
import static org.apache.brooklyn.test.framework.TestSshCommand.SHELL_ENVIRONMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.core.test.BrooklynAppUnitTestSupport;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.core.internal.ssh.RecordingSshTool;
import org.apache.brooklyn.util.core.internal.ssh.RecordingSshTool.CustomResponse;
import org.apache.brooklyn.util.core.internal.ssh.RecordingSshTool.ExecCmd;
import org.apache.brooklyn.util.core.internal.ssh.RecordingSshTool.ExecCmdPredicates;
import org.apache.brooklyn.util.core.internal.ssh.RecordingSshTool.ExecParams;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.text.Identifiers;
import org.apache.brooklyn.util.time.Duration;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

public class TestSshCommandTest extends BrooklynAppUnitTestSupport {

    private TestEntity testEntity;
    
    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();

        LocationSpec<SshMachineLocation> machineSpec = LocationSpec.create(SshMachineLocation.class)
                .configure("address", "1.2.3.4")
                .configure("sshToolClass", RecordingSshTool.class.getName());
        testEntity = app.createAndManageChild(EntitySpec.create(TestEntity.class)
                .location(machineSpec));
    }

    @AfterMethod(alwaysRun=true)
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        RecordingSshTool.clear();
    }
    
    @DataProvider(name = "shouldInsistOnJustOneOfCommandAndScript")
    public Object[][] createData1() {
        return new Object[][] {
                { "pwd", "pwd.sh", Boolean.FALSE },
                { null, null, Boolean.FALSE },
                { "pwd", null, Boolean.TRUE },
                { null, "pwd.sh", Boolean.TRUE }
        };
    }

    @Test(dataProvider = "shouldInsistOnJustOneOfCommandAndScript")
    public void shouldInsistOnJustOneOfCommandAndScript(String command, String script, boolean valid) throws Exception {
        Path scriptPath = null;
        String scriptUrl = null;
        if (null != script) {
            scriptPath = createTempScript("pwd", "pwd");
            scriptUrl = "file:" + scriptPath;
        }

        try {
            app.createAndManageChild(EntitySpec.create(TestSshCommand.class)
                    .configure(TARGET_ENTITY, testEntity)
                    .configure(COMMAND, command)
                    .configure(DOWNLOAD_URL, scriptUrl));

            app.start(ImmutableList.<Location>of());
            if (!valid) {
                Asserts.shouldHaveFailedPreviously();
            }

        } catch (Exception e) {
            Asserts.expectedFailureContains(e, "Must specify exactly one of download.url and command");

        } finally {
            if (null != scriptPath) {
                Files.delete(scriptPath);
            }
        }
    }

    @Test
    public void shouldSucceedUsingSuccessfulExitAsDefaultCondition() {
        TestSshCommand test = app.createAndManageChild(EntitySpec.create(TestSshCommand.class)
            .configure(TARGET_ENTITY, testEntity)
            .configure(COMMAND, "uptime"));

        app.start(ImmutableList.<Location>of());

        assertEntityHealthy(test);
        assertThat(RecordingSshTool.getLastExecCmd().commands).isEqualTo(ImmutableList.of("uptime"));
    }

    @Test
    public void shouldFailUsingSuccessfulExitAsDefaultCondition() {
        String cmd = "commandExpectedToFail-" + Identifiers.randomLong();
        RecordingSshTool.setCustomResponse(cmd, new RecordingSshTool.CustomResponse(1, null, null));
        
        TestSshCommand test = app.createAndManageChild(EntitySpec.create(TestSshCommand.class)
            .configure(MAX_ATTEMPTS, 1)
            .configure(TARGET_ENTITY, testEntity)
            .configure(COMMAND, cmd));

        try {
            app.start(ImmutableList.<Location>of());
            Asserts.shouldHaveFailedPreviously();
        } catch (Throwable t) {
            Asserts.expectedFailureContains(t, "exit code expected equals 0 but found 1");
        }

        assertEntityFailed(test);
        assertThat(RecordingSshTool.getLastExecCmd().commands).isEqualTo(ImmutableList.of(cmd));
    }

    @Test
    public void shouldMatchStdoutAndStderr() {
        String cmd = "stdoutAndStderr-" + Identifiers.randomLong();
        RecordingSshTool.setCustomResponse(cmd, new RecordingSshTool.CustomResponse(0, "mystdout", "mystderr"));
        
        TestSshCommand test = app.createAndManageChild(EntitySpec.create(TestSshCommand.class)
            .configure(TARGET_ENTITY, testEntity)
            .configure(COMMAND, cmd)
            .configure(ASSERT_OUT, makeAssertions(ImmutableMap.of(CONTAINS, "mystdout")))
            .configure(ASSERT_ERR, makeAssertions(ImmutableMap.of(CONTAINS, "mystderr"))));

        app.start(ImmutableList.<Location>of());

        assertEntityHealthy(test);
    }

    @Test
    public void shouldFailOnUnmatchedStdout() {
        String cmd = "stdoutAndStderr-" + Identifiers.randomLong();
        RecordingSshTool.setCustomResponse(cmd, new RecordingSshTool.CustomResponse(0, "wrongstdout", null));
        
        TestSshCommand test = app.createAndManageChild(EntitySpec.create(TestSshCommand.class)
            .configure(MAX_ATTEMPTS, 1)
            .configure(TARGET_ENTITY, testEntity)
            .configure(COMMAND, cmd)
            .configure(ASSERT_OUT, makeAssertions(ImmutableMap.of(CONTAINS, "mystdout"))));

        try {
            app.start(ImmutableList.<Location>of());
            Asserts.shouldHaveFailedPreviously();
        } catch (Throwable t) {
            Asserts.expectedFailureContains(t, "stdout expected contains mystdout but found wrongstdout");
        }

        assertEntityFailed(test);
    }

    @Test
    public void shouldFailOnUnmatchedStderr() {
        String cmd = "stdoutAndStderr-" + Identifiers.randomLong();
        RecordingSshTool.setCustomResponse(cmd, new RecordingSshTool.CustomResponse(0, null, "wrongstderr"));
        
        TestSshCommand test = app.createAndManageChild(EntitySpec.create(TestSshCommand.class)
            .configure(MAX_ATTEMPTS, 1)
            .configure(TARGET_ENTITY, testEntity)
            .configure(COMMAND, cmd)
            .configure(ASSERT_ERR, makeAssertions(ImmutableMap.of(CONTAINS, "mystderr"))));

        try {
            app.start(ImmutableList.<Location>of());
            Asserts.shouldHaveFailedPreviously();
        } catch (Throwable t) {
            Asserts.expectedFailureContains(t, "stderr expected contains mystderr but found wrongstderr");
        }

        assertEntityFailed(test);
    }

    @Test
    public void shouldFailOnUnmatchedExitCode() {
        Map<String, ?> equalsOne = ImmutableMap.of(EQUALS, 1);

        Map<String, ?> equals255 = ImmutableMap.of(EQUALS, 255);

        TestSshCommand test = app.createAndManageChild(EntitySpec.create(TestSshCommand.class)
            .configure(MAX_ATTEMPTS, 1)
            .configure(TARGET_ENTITY, testEntity)
            .configure(COMMAND, "uptime")
            .configure(ASSERT_STATUS, makeAssertions(equalsOne, equals255)));

        try {
            app.start(ImmutableList.<Location>of());
            Asserts.shouldHaveFailedPreviously();
        } catch (Exception e) {
            Asserts.expectedFailureContains(e, "exit code expected equals 1 but found 0", "exit code expected equals 255 but found 0");
        }

        assertEntityFailed(test);
    }

    @Test
    public void shouldInvokeScript() throws Exception {
        String text = "hello world";
        Path testScript = createTempScript("script", "echo " + text);

        try {
            Map<String, ?> equalsZero = ImmutableMap.of(EQUALS, 0);

            TestSshCommand test = app.createAndManageChild(EntitySpec.create(TestSshCommand.class)
                .configure(TARGET_ENTITY, testEntity)
                .configure(DOWNLOAD_URL, "file:" + testScript)
                .configure(ASSERT_STATUS, makeAssertions(equalsZero)));

            app.start(ImmutableList.<Location>of());

            assertEntityHealthy(test);
            assertThat(RecordingSshTool.getLastExecCmd().commands.toString()).contains("TestSshCommandTest-script");

        } finally {
            Files.delete(testScript);
        }
    }

    @Test
    public void shouldFailIfTestEntityHasNoMachine() throws Exception {
        TestEntity testEntityWithNoMachine = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        
        TestSshCommand test = app.createAndManageChild(EntitySpec.create(TestSshCommand.class)
            .configure(TARGET_ENTITY, testEntityWithNoMachine)
            .configure(COMMAND, "mycmd"));

        try {
            app.start(ImmutableList.<Location>of());
            Asserts.shouldHaveFailedPreviously();
        } catch (Exception e) {
            Asserts.expectedFailureContains(e, "No instances of class "+SshMachineLocation.class.getName()+" available");
        }

        assertEntityFailed(test);
    }
    
    @Test
    public void shouldFailFastIfNoCommand() throws Exception {
        Duration longTimeout = Asserts.DEFAULT_LONG_TIMEOUT;
        
        Map<String, ?> equalsZero = ImmutableMap.of(EQUALS, 0);
        
        TestSshCommand test = app.createAndManageChild(EntitySpec.create(TestSshCommand.class)
                .configure(TIMEOUT, longTimeout.multiply(2))
                .configure(TARGET_ENTITY, testEntity)
                .configure(ASSERT_STATUS, makeAssertions(equalsZero)));

        Stopwatch stopwatch = Stopwatch.createStarted();
        try {
            app.start(ImmutableList.<Location>of());
            Asserts.shouldHaveFailedPreviously();
        } catch (Exception e) {
            // note: sleep(1000) can take a few millis less than 1000ms, according to a stopwatch.
            Asserts.expectedFailureContains(e, "Must specify exactly one of download.url and command");
            Duration elapsed = Duration.of(stopwatch);
            Asserts.assertTrue(elapsed.isShorterThan(longTimeout.subtract(Duration.millis(20))), "elapsed="+elapsed);
        }

        assertEntityFailed(test);
    }
    
    @Test
    public void shouldIncludeEnv() throws Exception {
        Map<String, Object> env = ImmutableMap.<String, Object>of("ENV1", "val1", "ENV2", "val2");
        
        TestSshCommand test = app.createAndManageChild(EntitySpec.create(TestSshCommand.class)
            .configure(TARGET_ENTITY, testEntity)
            .configure(COMMAND, "mycmd")
            .configure(SHELL_ENVIRONMENT, env));

        app.start(ImmutableList.<Location>of());

        assertEntityHealthy(test);
        
        ExecCmd cmdExecuted = RecordingSshTool.getLastExecCmd();
        assertThat(cmdExecuted.commands).isEqualTo(ImmutableList.of("mycmd"));
        assertThat(cmdExecuted.env).isEqualTo(env);
    }

    @Test
    public void testRetries() {
        String cmd = "commandExpectedToFail-" + Identifiers.randomLong();
        RecordingSshTool.setCustomResponse(cmd, new RecordingSshTool.CustomResponseGenerator() {
            final AtomicInteger counter = new AtomicInteger();
            @Override public CustomResponse generate(ExecParams execParams) throws Exception {
                // First call fails; subsequent calls succeed
                int code = (counter.incrementAndGet() > 1) ? 0 : 1;
                return new RecordingSshTool.CustomResponse(code, null, null);
            }
        });
        
        TestSshCommand test = app.createAndManageChild(EntitySpec.create(TestSshCommand.class)
                .configure(BACKOFF_TO_PERIOD, Duration.millis(1))
                .configure(TIMEOUT, Duration.minutes(1))
                .configure(TARGET_ENTITY, testEntity)
                .configure(COMMAND, cmd));
        
        Stopwatch stopwatch = Stopwatch.createStarted();
        
        app.start(ImmutableList.<Location>of());
        assertEntityHealthy(test);

        Iterable<ExecCmd> calls = Iterables.filter(RecordingSshTool.getExecCmds(), ExecCmdPredicates.containsCmd(cmd));
        assertEquals(Iterables.size(calls), 2, "matchingCalls="+calls);
        
        Duration duration = Duration.of(stopwatch);
        assertTrue(duration.isShorterThan(Asserts.DEFAULT_LONG_TIMEOUT), "duration="+duration);
    }

    @Test
    public void testMaxAttempts() {
        String cmd = "commandExpectedToFail-" + Identifiers.randomLong();
        RecordingSshTool.setCustomResponse(cmd, new RecordingSshTool.CustomResponse(1, null, null));
        
        TestSshCommand test = app.createAndManageChild(EntitySpec.create(TestSshCommand.class)
                .configure(MAX_ATTEMPTS, 2)
                .configure(BACKOFF_TO_PERIOD, Duration.millis(1))
                .configure(TIMEOUT, Duration.minutes(1))
                .configure(TARGET_ENTITY, testEntity)
                .configure(COMMAND, cmd));
        
        Stopwatch stopwatch = Stopwatch.createStarted();
        try {
            app.start(ImmutableList.<Location>of());
            Asserts.shouldHaveFailedPreviously();
        } catch (Throwable t) {
            Asserts.expectedFailureContains(t, "exit code expected equals 0 but found 1");
        }

        assertEntityFailed(test);

        Iterable<ExecCmd> calls = Iterables.filter(RecordingSshTool.getExecCmds(), ExecCmdPredicates.containsCmd(cmd));
        assertEquals(Iterables.size(calls), 2, "matchingCalls="+calls);
        
        Duration duration = Duration.of(stopwatch);
        assertTrue(duration.isShorterThan(Asserts.DEFAULT_LONG_TIMEOUT), "duration="+duration);
    }

    private Path createTempScript(String filename, String contents) {
        try {
            Path tempFile = Files.createTempFile("TestSshCommandTest-" + filename, ".sh");
            Files.write(tempFile, contents.getBytes());
            return tempFile;
        } catch (IOException e) {
            throw Exceptions.propagate(e);
        }
    }
    
    private List<Map<String, ?>> makeAssertions(Map<String, ?> map) {
        return ImmutableList.<Map<String, ?>>of(map);
    }

    private List<Map<String, ?>> makeAssertions(Map<String, ?> map1, Map<String, ?> map2) {
        return ImmutableList.of(map1, map2);
    }
}
