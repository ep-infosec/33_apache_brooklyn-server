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
package org.apache.brooklyn.util.core.internal.ssh.sshj;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.schmizz.concurrent.ExceptionChainer;
import net.schmizz.sshj.common.SSHException;
import net.schmizz.sshj.common.StreamCopier;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.SFTPException;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.userauth.UserAuthException;
import org.apache.brooklyn.core.BrooklynFeatureEnablement;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.core.internal.ssh.ShellTool;
import org.apache.brooklyn.util.core.internal.ssh.SshException;
import org.apache.brooklyn.util.core.internal.ssh.SshTool;
import org.apache.brooklyn.util.core.internal.ssh.SshToolAbstractIntegrationTest;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.exceptions.RuntimeTimeoutException;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.stream.ReaderInputStream;
import org.apache.brooklyn.util.text.Identifiers;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.Time;
import org.apache.commons.io.output.StringBuilderWriter;
import org.apache.commons.io.output.WriterOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.brooklyn.util.core.internal.ssh.ShellTool.PROP_ERR_STREAM;
import static org.apache.brooklyn.util.core.internal.ssh.ShellTool.PROP_OUT_STREAM;
import static org.apache.brooklyn.util.time.Duration.ONE_SECOND;
import static org.testng.Assert.*;

/**
 * Test the operation of the {@link SshjTool} utility class.
 */
public class SshjToolIntegrationTest extends SshToolAbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SshjToolIntegrationTest.class);

    @Override
    protected SshTool newUnregisteredTool(Map<String,?> flags) {
        return new SshjTool(flags);
    }

    // TODO requires vt100 terminal emulation to work?
    @Test(enabled = false, groups = {"Integration"})
    public void testExecShellWithCommandTakingStdin() throws Exception {
        // Uses `tee` to redirect stdin to the given file; cntr-d (i.e. char 4) stops tee with exit code 0
        String content = "blah blah";
        String out = execShellDirectWithTerminalEmulation("tee "+remoteFilePath, content, ""+(char)4, "echo file contents: `cat "+remoteFilePath+"`");

        assertTrue(out.contains("file contents: blah blah"), "out="+out);
    }

    @Test(groups = {"Integration"})
    public void testGivesUpAfterMaxRetries() throws Exception {
        final AtomicInteger callCount = new AtomicInteger();
        final SshTool localtool = new SshjTool(ImmutableMap.of("sshTries", 3, "host", "localhost", "privateKeyFile", "~/.ssh/id_rsa")) {
            @Override
            protected SshAction<Session> newSessionAction() {
                callCount.incrementAndGet();
                throw new RuntimeException("Simulating ssh execution failure");
            }
        };
        
        tools.add(localtool);
        try {
            localtool.execScript(ImmutableMap.<String,Object>of(), ImmutableList.of("true"));
            fail();
        } catch (SshException e) {
            if (!e.toString().contains("out of retries")) throw e;
            assertEquals(callCount.get(), 3);
        }
    }

    @Test(groups = {"Integration"})
    public void testReturnsOnSuccessWhenRetrying() throws Exception {
        final AtomicInteger callCount = new AtomicInteger();
        final int successOnAttempt = 2;
        final SshTool localtool = new SshjTool(ImmutableMap.of("sshTries", 3, "host", "localhost", "privateKeyFile", "~/.ssh/id_rsa")) {
            @Override
            protected SshAction<Session> newSessionAction() {
                callCount.incrementAndGet();
                if (callCount.incrementAndGet() >= successOnAttempt) {
                    return super.newSessionAction();
                } else {
                    throw new RuntimeException("Simulating ssh execution failure");
                }
            }
        };
        
        tools.add(localtool);
        localtool.execScript(ImmutableMap.<String,Object>of(), ImmutableList.of("true"));
        assertEquals(callCount.get(), successOnAttempt);
    }

    @Test(groups = {"Integration"})
    public void testGivesUpAfterMaxTime() throws Exception {
        final AtomicInteger callCount = new AtomicInteger();
        final SshTool localtool = new SshjTool(ImmutableMap.of("sshTriesTimeout", 1000, "host", "localhost", "privateKeyFile", "~/.ssh/id_rsa")) {
            @Override
            protected SshAction<Session> newSessionAction() {
                callCount.incrementAndGet();
                try {
                    Thread.sleep(600);
                } catch (InterruptedException e) {
                    throw Exceptions.propagate(e);
                }
                throw new RuntimeException("Simulating ssh execution failure");
            }
        };
        
        tools.add(localtool);
        try {
            localtool.execScript(ImmutableMap.<String,Object>of(), ImmutableList.of("true"));
            fail();
        } catch (RuntimeTimeoutException e) {
            if (!e.toString().contains("out of time")) throw e;
            assertEquals(callCount.get(), 2);
        }
    }
    
    @Test(groups = {"Integration"})
    public void testUsesCustomLocalTempDir() throws Exception {
        class SshjToolForTest extends SshjTool {
            public SshjToolForTest(Map<String, ?> map) {
                super(map);
            }
            public File getLocalTempDir() {
                return localTempDir;
            }
        };
        
        final SshjToolForTest localtool = new SshjToolForTest(ImmutableMap.<String, Object>of("host", "localhost"));
        assertNotNull(localtool.getLocalTempDir());
        assertEquals(localtool.getLocalTempDir(), new File(Os.tidyPath(SshjTool.PROP_LOCAL_TEMP_DIR.getDefaultValue())));
        
        String customTempDir = Os.tmp();
        final SshjToolForTest localtool2 = new SshjToolForTest(ImmutableMap.of(
                "host", "localhost", 
                SshjTool.PROP_LOCAL_TEMP_DIR.getName(), customTempDir));
        assertEquals(localtool2.getLocalTempDir(), new File(customTempDir));
        
        String customRelativeTempDir = "~/tmp";
        final SshjToolForTest localtool3 = new SshjToolForTest(ImmutableMap.of(
                "host", "localhost", 
                SshjTool.PROP_LOCAL_TEMP_DIR.getName(), customRelativeTempDir));
        assertEquals(localtool3.getLocalTempDir(), new File(Os.tidyPath(customRelativeTempDir)));
    }

    // This is just a sanity check - we actually expect passwordless sudo to work, and the
    // "dummypa55w0rd" to not be right. It will supply that password to sudo's stdin, which
    // doesn't require it and ignores it. To really integration-test this, we'd probably have  
    // to create a new user with sudo-with-password rights, and delete it in tearDown which 
    // seems overkill for testing!
    @Test(groups = {"Integration"})
    public void testRunAsRootWithAuthSudo() {
        final ShellTool localtool = newTool();
        connect(localtool);
        Map<String,Object> props = new LinkedHashMap<String, Object>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        props.put("out", out);
        props.put("err", err);
        props.put(SshTool.PROP_RUN_AS_ROOT.getName(), true);
        props.put(SshTool.PROP_AUTH_SUDO.getName(), true);
        props.put(SshTool.PROP_PASSWORD.getName(), "dummypa55w0rd");
        int exitcode = localtool.execScript(props, Arrays.asList("whoami"), null);
        assertTrue(out.toString().contains("root"), "not running as root; whoami is: "+out+" (err is '"+err+"')");
        assertEquals(0, exitcode);
    }

    @Test(groups = {"Integration"})
    public void testAsyncExecStdoutAndStderr() throws Exception {
        boolean origFeatureEnablement = BrooklynFeatureEnablement.enable(BrooklynFeatureEnablement.FEATURE_SSH_ASYNC_EXEC);
        try {
            // Include a sleep, to ensure that the contents retrieved in first poll and subsequent polls are appended
            List<String> cmds = ImmutableList.of(
                    "echo mystringToStdout",
                    "echo mystringToStderr 1>&2",
                    "sleep 5",
                    "echo mystringPostSleepToStdout",
                    "echo mystringPostSleepToStderr 1>&2");
            
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exitCode = tool.execScript(
                    ImmutableMap.of(
                            "out", out, 
                            "err", err, 
                            SshjTool.PROP_EXEC_ASYNC.getName(), true, 
                            SshjTool.PROP_NO_EXTRA_OUTPUT.getName(), true,
                            SshjTool.PROP_EXEC_ASYNC_POLLING_TIMEOUT.getName(), ONE_SECOND),
                    cmds, 
                    ImmutableMap.<String,String>of());
            String outStr = new String(out.toByteArray());
            String errStr = new String(err.toByteArray());
    
            assertEquals(exitCode, 0);
            assertEquals(outStr.trim(), "mystringToStdout\nmystringPostSleepToStdout");
            assertEquals(errStr.trim(), "mystringToStderr\nmystringPostSleepToStderr");
        } finally {
            BrooklynFeatureEnablement.setEnablement(BrooklynFeatureEnablement.FEATURE_SSH_ASYNC_EXEC, origFeatureEnablement);
        }
    }

    @Test(groups = {"Integration"})
    public void testAsyncExecReturnsExitCode() throws Exception {
        boolean origFeatureEnablement = BrooklynFeatureEnablement.enable(BrooklynFeatureEnablement.FEATURE_SSH_ASYNC_EXEC);
        try {
            int exitCode = tool.execScript(
                    ImmutableMap.of(SshjTool.PROP_EXEC_ASYNC.getName(), true), 
                    ImmutableList.of("exit 123"), 
                    ImmutableMap.<String,String>of());
            assertEquals(exitCode, 123);
        } finally {
            BrooklynFeatureEnablement.setEnablement(BrooklynFeatureEnablement.FEATURE_SSH_ASYNC_EXEC, origFeatureEnablement);
        }
    }

    @Test(groups = {"Integration"})
    public void testAsyncExecTimesOut() throws Exception {
        Stopwatch stopwatch = Stopwatch.createStarted();
        boolean origFeatureEnablement = BrooklynFeatureEnablement.enable(BrooklynFeatureEnablement.FEATURE_SSH_ASYNC_EXEC);
        try {
            tool.execScript(
                ImmutableMap.of(SshjTool.PROP_EXEC_ASYNC.getName(), true, SshjTool.PROP_EXEC_TIMEOUT.getName(), Duration.millis(1)), 
                ImmutableList.of("sleep 60"), 
                ImmutableMap.<String,String>of());
            fail();
        } catch (Exception e) {
            TimeoutException te = Exceptions.getFirstThrowableOfType(e, TimeoutException.class);
            if (te == null) throw e;
        } finally {
            BrooklynFeatureEnablement.setEnablement(BrooklynFeatureEnablement.FEATURE_SSH_ASYNC_EXEC, origFeatureEnablement);
        }
        
        long seconds = stopwatch.elapsed(TimeUnit.SECONDS);
        assertTrue(seconds < 30, "exec took "+seconds+" seconds");
    }

    @Test(groups = {"Integration"})
    public void testAsyncExecAbortsIfProcessFails() throws Exception {
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Stopwatch stopwatch = Stopwatch.createStarted();
                    int exitStatus = tool.execScript(
                        ImmutableMap.of(SshjTool.PROP_EXEC_ASYNC.getName(), true, SshjTool.PROP_EXEC_TIMEOUT.getName(), Duration.millis(1)), 
                        ImmutableList.of("sleep 63"), 
                        ImmutableMap.<String,String>of());
                    
                    assertEquals(exitStatus, 143 /* 128 + Signal number (SIGTERM) */);
                    
                    long seconds = stopwatch.elapsed(TimeUnit.SECONDS);
                    assertTrue(seconds < 30, "exec took "+seconds+" seconds");
                } catch (Throwable t) {
                    error.set(t);
                }
            }});
        
        boolean origFeatureEnablement = BrooklynFeatureEnablement.enable(BrooklynFeatureEnablement.FEATURE_SSH_ASYNC_EXEC);
        try {
            thread.start();
            
            Asserts.succeedsEventually(new Runnable() {
                @Override
                public void run() {
                    int exitStatus = tool.execCommands(ImmutableMap.<String,Object>of(), ImmutableList.of("ps aux| grep \"sleep 63\" | grep -v grep"));
                    assertEquals(exitStatus, 0);
                }});
            
            tool.execCommands(ImmutableMap.<String,Object>of(), ImmutableList.of("ps aux| grep \"sleep 63\" | grep -v grep | awk '{print($2)}' | xargs kill"));
            
            thread.join(30*1000);
            assertFalse(thread.isAlive());
            if (error.get() != null) {
                throw Exceptions.propagate(error.get());
            }
        } finally {
            thread.interrupt();
            BrooklynFeatureEnablement.setEnablement(BrooklynFeatureEnablement.FEATURE_SSH_ASYNC_EXEC, origFeatureEnablement);
        }
    }

    
    protected String execShellDirect(List<String> cmds) {
        return execShellDirect(cmds, ImmutableMap.<String,Object>of());
    }
    
    protected String execShellDirect(List<String> cmds, Map<String,?> env) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitcode = ((SshjTool)tool).execShellDirect(ImmutableMap.of("out", out), cmds, env);
        String outstr = new String(out.toByteArray());
        assertEquals(exitcode, 0, outstr);
        return outstr;
    }

    private String execShellDirectWithTerminalEmulation(String... cmds) {
        return execShellDirectWithTerminalEmulation(Arrays.asList(cmds));
    }
    
    private String execShellDirectWithTerminalEmulation(List<String> cmds) {
        return execShellDirectWithTerminalEmulation(cmds, ImmutableMap.<String,Object>of());
    }
    
    private String execShellDirectWithTerminalEmulation(List<String> cmds, Map<String,?> env) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitcode = ((SshjTool)tool).execShellDirect(ImmutableMap.of("allocatePTY", true, "out", out), cmds, env);
        String outstr = new String(out.toByteArray());
        assertEquals(exitcode, 0, outstr);
        return outstr;
    }

    // useful if we want to understand why SSHJ is swallowing the exceptions;
    // shouldn't be needed once we have a solution to https://github.com/hierynomus/sshj/issues/800
//    static void hackChainerLogging() {
//        Arrays.asList(SSHException.class, ConnectionException.class, TransportException.class, SFTPException.class, UserAuthException.class
//                // , StreamCopier.class   // not a public field
//        ).forEach(clazz -> {
//            try {
//                Field f = clazz.getField("chainer");
//                f.setAccessible(true);
//
//                Field modifiersField = Field.class.getDeclaredField("modifiers");
//                modifiersField.setAccessible(true);
//                modifiersField.setInt(f, f.getModifiers() & ~Modifier.FINAL);
//
//                ExceptionChainer oldValue = (ExceptionChainer) f.get(null);
//                f.set(null, new ExceptionChainer() {
//                    @Override
//                    public Throwable chain(Throwable t) {
//                        if (Exceptions.isRootCauseIsInterruption(t)) {
//                            log.warn("Caught interruption (thread interrupted? "+Thread.currentThread().isInterrupted()+")", t);
//                            log.warn("... caught at", new Throwable("source of catching, in " + clazz));
//                        }
//                        return oldValue.chain(t);
//                    }
//                });
//            } catch (Exception e) {
//                throw Exceptions.propagate(e);
//            }
//        });
//    }
//    static {
//        hackChainerLogging();
//    }

    @Test(groups = {"Integration"})
    public void testSshIsInterrupted() {
        log.info("STARTING");
        final SshTool localTool = new SshjTool(ImmutableMap.of(
                //  "user", "amp",
                  "sshTries", 3,
                "host", "localhost",
                "privateKeyFile", "~/.ssh/id_rsa"));
        try {
            Thread t = new Thread(() -> {
                try {
                    log.info("T2 starting - "+Thread.currentThread());
                    localTool.connect();
                    log.info("T2 executing");
                    //localTool.connect();
                    localTool.execScript(ImmutableMap.of(PROP_OUT_STREAM.getName(), System.out, PROP_ERR_STREAM.getName(), System.err),
                            ImmutableList.of(
                                    "echo hello world",
                                    "ls /path/to/does-not-exist || echo no ls",
                                    "sleep 10",
                                    "echo slept")
                    );
                } catch (Exception e) {
                    log.info("T2 error", e);
                } finally {
                    log.info("T2 ending - "+Thread.currentThread().isInterrupted());
                }
            });
            log.info("STARTING");
            t.start();
            if (Math.random()>0.1) Time.sleep(Duration.millis(3*Math.random()*Math.random()));  // sleep for a small amount of time, up to three seconds, but usually much less, and 10% of time not at all
            log.info("INTERRUPTING");
            t.interrupt();
            Time.sleep(ONE_SECOND);
            Arrays.asList(t.getStackTrace()).forEach(traceElement -> System.out.println(traceElement));
            log.info("JOINING");
            Stopwatch s = Stopwatch.createStarted();
            if (Duration.of(s.elapsed()).isLongerThan(ONE_SECOND)) {
                Asserts.fail("Join should have been immediate as other thread was interrupted, but instead took "+Duration.of(s.elapsed()));
            }
        } catch (Exception e) {
            log.info("FAILED", e);
            Asserts.fail("Shouldn't throw");
        }
        log.info("ENDING");
    }

    @Test(groups = {"Integration"})
    public void testSshPipingInput() throws Exception {
        AtomicReference<OutputStream> inCallback = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        final SshTool localTool = new SshjTool(ImmutableMap.of(
                //  "user", "amp",
                "sshTries", 3,
                "host", "localhost",
                "privateKeyFile", "~/.ssh/id_rsa"));

        StringBuilderWriter stdout = new StringBuilderWriter();

        Thread t = new Thread(() -> {
            try {
                log.info("T2 starting - " + Thread.currentThread());
                localTool.connect();
                log.info("T2 executing");
                // input only works with commands; script is too complicated
//                    localTool.execScript(
                localTool.execCommands(
                        ImmutableMap.of(PROP_OUT_STREAM.getName(), new WriterOutputStream(stdout, Charset.defaultCharset()), PROP_ERR_STREAM.getName(), System.err,
                                SshjTool.PROP_IN_STREAM_CALLBACK.getName(), inCallback),
                        ImmutableList.of(
                                "echo header",
                                "cat -",
                                "echo done")
                );
            } catch (Throwable e) {
                log.info("Error", e);
                error.set(e);
            } finally {
                log.info("T2 ending");
            }
        });
        log.info("STARTING");
        t.start();
        synchronized (inCallback) {
            for (int i = 0; i < 10; i++) {
                inCallback.wait(1000);
                if (inCallback.get() != null) break;
            }
        }
        log.info("GOT CALLBACK");
        Asserts.assertNotNull(inCallback.get());
        String id = Identifiers.makeRandomId(4);
        inCallback.get().write(("hello world "+id+"\n").getBytes(StandardCharsets.UTF_8));
        inCallback.get().close();

        log.info("WROTE");
        t.join();

        Asserts.assertEquals(stdout.toString(), "header\nhello world "+id+"\ndone\n");

        log.info("ENDING");
    }

}
