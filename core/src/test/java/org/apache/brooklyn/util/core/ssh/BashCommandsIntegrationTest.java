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
package org.apache.brooklyn.util.core.ssh;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import org.apache.brooklyn.core.test.BrooklynMgmtUnitTestSupport;
import org.apache.brooklyn.core.test.entity.LocalManagementContextForTests;
import org.apache.brooklyn.location.localhost.LocalhostMachineProvisioningLocation;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.test.DisableOnWindows;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.core.task.BasicExecutionContext;
import org.apache.brooklyn.util.core.task.ssh.SshTasks;
import org.apache.brooklyn.util.core.task.system.ProcessTaskWrapper;
import org.apache.brooklyn.util.javalang.JavaClassNames;
import org.apache.brooklyn.util.net.Networking;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.ssh.BashCommandsConfigurable;
import org.apache.brooklyn.util.text.Identifiers;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Duration;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static java.lang.String.format;
import static org.testng.Assert.*;

public class BashCommandsIntegrationTest extends BrooklynMgmtUnitTestSupport {

    private static final Logger log = LoggerFactory.getLogger(BashCommandsIntegrationTest.class);
    
    private BasicExecutionContext exec;
    
    private File destFile;
    private File sourceNonExistantFile;
    private File sourceFile1;
    private File sourceFile2;
    private File tmpSudoersFile;
    private String sourceNonExistantFileUrl;
    private String sourceFileUrl1;
    private String sourceFileUrl2;
    private SshMachineLocation loc;

    private String localRepoFilename = "localrepofile.txt";
    private File localRepoBasePath;
    private File localRepoEntityBasePath;
    private String localRepoEntityVersionPath;
    private File localRepoEntityFile;

    private final BashCommandsConfigurable bashTestInstance = BashCommandsConfigurable.newInstance();

    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        super.setUp();
        
        exec = new BasicExecutionContext(mgmt.getExecutionManager());
        
        destFile = Os.newTempFile(getClass(), "commoncommands-test-dest.txt");
        
        sourceNonExistantFile = new File("/this/does/not/exist/ERQBETJJIG1234");
        sourceNonExistantFileUrl = sourceNonExistantFile.toURI().toString();
        
        sourceFile1 = Os.newTempFile(getClass(), "commoncommands-test.txt");
        sourceFileUrl1 = sourceFile1.toURI().toString();
        Files.write("mysource1".getBytes(), sourceFile1);
        
        sourceFile2 = Os.newTempFile(getClass(), "commoncommands-test2.txt");
        sourceFileUrl2 = sourceFile2.toURI().toString();
        Files.write("mysource2".getBytes(), sourceFile2);

        localRepoEntityVersionPath = JavaClassNames.simpleClassName(this)+"-test-dest-"+Identifiers.makeRandomId(8);
        localRepoBasePath = new File(format("%s/.brooklyn/repository", System.getProperty("user.home")));
        localRepoEntityBasePath = new File(localRepoBasePath, localRepoEntityVersionPath);
        localRepoEntityFile = new File(localRepoEntityBasePath, localRepoFilename);
        localRepoEntityBasePath.mkdirs();
        Files.write("mylocal1".getBytes(), localRepoEntityFile);

        tmpSudoersFile = Os.newTempFile(getClass(), "sudoers" + Identifiers.makeRandomId(8));

        String sudoers = ResourceUtils.create(this).getResourceAsString("classpath://brooklyn/util/ssh/test_sudoers");
        Files.write(sudoers.getBytes(), tmpSudoersFile);
        
        loc = mgmt.getLocationManager().createLocation(LocalhostMachineProvisioningLocation.spec()).obtain();
    }
    
    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        try {
            if (sourceFile1 != null) sourceFile1.delete();
            if (sourceFile2 != null) sourceFile2.delete();
            if (destFile != null) destFile.delete();
            if (localRepoEntityFile != null) localRepoEntityFile.delete();
            if (tmpSudoersFile != null) tmpSudoersFile.delete();
            if (localRepoEntityBasePath != null) FileUtils.deleteDirectory(localRepoEntityBasePath);
            if (loc != null) loc.close();
        } finally {
            super.tearDown();
        }
    }
    
    @Test(groups="Integration")
    @DisableOnWindows(reason = "Needs a bash shell available on localhost")
    public void testRemoveRequireTtyFromSudoersFile() throws Exception {
        String cmds = bashTestInstance.dontRequireTtyForSudo();

        
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errStream = new ByteArrayOutputStream();

        String cmdsWithReplacedSudoersName = Strings.replaceAllNonRegex(cmds, "/etc/sudoers", tmpSudoersFile.getAbsolutePath());
        int exitcode = loc.execCommands(ImmutableMap.of("out", outStream, "err", errStream), "removeRequireTtyFromSudoersFile", ImmutableList.of(cmdsWithReplacedSudoersName));

        String outstr = new String(outStream.toByteArray());
        String errstr = new String(errStream.toByteArray());

        assertEquals(0, exitcode);
        
        // visudo returns "parsed OK"
        assertTrue(outstr.contains("parsed OK"), "out="+outstr+"; err="+errstr);
        assertTrue(errstr.isEmpty(), "out="+outstr+"; err="+errstr);
    }
    
    @Test(groups="Integration")
    @DisableOnWindows(reason = "Needs a whoami command available on localhost")
    public void testSudo() throws Exception {
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errStream = new ByteArrayOutputStream();
        String cmd = bashTestInstance.sudo("whoami");
        int exitcode = loc.execCommands(ImmutableMap.of("out", outStream, "err", errStream), "test", ImmutableList.of(cmd));
        String outstr = new String(outStream.toByteArray());
        String errstr = new String(errStream.toByteArray());
        
        assertEquals(exitcode, 0, "out="+outstr+"; err="+errstr);
        assertTrue(outstr.contains("root"), "out="+outstr+"; err="+errstr);
    }
    
    public void testDownloadUrl() throws Exception {
        List<String> cmds = bashTestInstance.commandsToDownloadUrlsAs(
                ImmutableList.of(sourceFileUrl1), 
                destFile.getAbsolutePath());
        int exitcode = loc.execCommands("test", cmds);
        
        assertEquals(0, exitcode);
        assertEquals(Files.readLines(destFile, Charsets.UTF_8), ImmutableList.of("mysource1"));
    }
    
    @Test(groups="Integration")
    @DisableOnWindows(reason = "Needs a bash shell available on localhost")
    public void testDownloadFirstSuccessfulFile() throws Exception {
        List<String> cmds = bashTestInstance.commandsToDownloadUrlsAs(
                ImmutableList.of(sourceNonExistantFileUrl, sourceFileUrl1, sourceFileUrl2), 
                destFile.getAbsolutePath());
        int exitcode = loc.execCommands("test", cmds);
        
        assertEquals(0, exitcode);
        assertEquals(Files.readLines(destFile, Charsets.UTF_8), ImmutableList.of("mysource1"));
    }
    
    @Test(groups="Integration")
    @DisableOnWindows(reason = "Needs an ssh server listening on port 22 on localhost")
    public void testDownloadToStdout() throws Exception {
        ProcessTaskWrapper<String> t = SshTasks.newSshExecTaskFactory(loc, 
                "cd "+destFile.getParentFile().getAbsolutePath(),
                bashTestInstance.downloadToStdout(Arrays.asList(sourceFileUrl1))+" | sed s/my/your/")
            .requiringZeroAndReturningStdout().newTask();

        String result = exec.submit(t).get();
        assertTrue(result.trim().equals("yoursource1"), "Wrong contents of stdout download: "+result);
    }

    @Test(groups="Integration")
    @DisableOnWindows(reason = "Needs an ssh server listening on port 22 on localhost")
    public void testAlternativesWhereFirstSucceeds() throws Exception {
        ProcessTaskWrapper<Integer> t = SshTasks.newSshExecTaskFactory(loc)
                .add(bashTestInstance.alternatives(Arrays.asList("echo first", "exit 88")))
                .newTask();

        Integer returnCode = exec.submit(t).get();
        String stdout = t.getStdout();
        String stderr = t.getStderr();
        log.info("alternatives for good first command gave: "+returnCode+"; err="+stderr+"; out="+stdout);
        assertTrue(stdout.contains("first"), "errcode="+returnCode+"; stdout="+stdout+"; stderr="+stderr);
        assertEquals(returnCode, (Integer)0);
    }

    @Test(groups="Integration")
    @DisableOnWindows(reason = "Needs an ssh server listening on port 22 on localhost")
    public void testAlternatives() throws Exception {
        ProcessTaskWrapper<Integer> t = SshTasks.newSshExecTaskFactory(loc)
                .add(bashTestInstance.alternatives(Arrays.asList("asdfj_no_such_command_1", "exit 88")))
                .newTask();

        Integer returnCode = exec.submit(t).get();
        log.info("alternatives for bad commands gave: "+returnCode+"; err="+new String(t.getStderr())+"; out="+new String(t.getStdout()));
        assertEquals(returnCode, (Integer)88);
    }

    @Test(groups="Integration")
    @DisableOnWindows(reason = "Needs an ssh server listening on port 22 on localhost")
    public void testRequireTestHandlesFailure() throws Exception {
        ProcessTaskWrapper<?> t = SshTasks.newSshExecTaskFactory(loc)
            .add(bashTestInstance.requireTest("-f "+sourceNonExistantFile.getPath(),
                    "The requested file does not exist")).newTask();

        exec.submit(t).get();
        assertNotEquals(t.getExitCode(), 0);
        assertTrue(t.getStderr().contains("The requested file"), "Expected message in: "+t.getStderr());
        assertTrue(t.getStdout().contains("The requested file"), "Expected message in: "+t.getStdout());
    }

    @Test(groups="Integration")
    @DisableOnWindows(reason = "Needs an ssh server listening on port 22 on localhost")
    public void testRequireTestHandlesSuccess() throws Exception {
        ProcessTaskWrapper<?> t = SshTasks.newSshExecTaskFactory(loc)
            .add(bashTestInstance.requireTest("-f "+sourceFile1.getPath(),
                    "The requested file does not exist")).newTask();

        exec.submit(t).get();
        assertEquals(t.getExitCode(), (Integer)0);
        assertTrue(t.getStderr().equals(""), "Expected no stderr messages, but got: "+t.getStderr());
    }

    @Test(groups="Integration")
    @DisableOnWindows(reason = "Needs an ssh server listening on port 22 on localhost")
    public void testRequireFileHandlesFailure() throws Exception {
        ProcessTaskWrapper<?> t = SshTasks.newSshExecTaskFactory(loc)
            .add(bashTestInstance.requireFile(sourceNonExistantFile.getPath())).newTask();

        exec.submit(t).get();
        assertNotEquals(t.getExitCode(), 0);
        assertTrue(t.getStderr().contains("required file"), "Expected message in: "+t.getStderr());
        assertTrue(t.getStderr().contains(sourceNonExistantFile.getPath()), "Expected message in: "+t.getStderr());
        assertTrue(t.getStdout().contains("required file"), "Expected message in: "+t.getStdout());
        assertTrue(t.getStdout().contains(sourceNonExistantFile.getPath()), "Expected message in: "+t.getStdout());
    }

    @Test(groups="Integration")
    @DisableOnWindows(reason = "Needs an ssh server listening on port 22 on localhost")
    public void testRequireFileHandlesSuccess() throws Exception {
        ProcessTaskWrapper<?> t = SshTasks.newSshExecTaskFactory(loc)
            .add(bashTestInstance.requireFile(sourceFile1.getPath())).newTask();

        exec.submit(t).get();
        assertEquals(t.getExitCode(), (Integer)0);
        assertTrue(t.getStderr().equals(""), "Expected no stderr messages, but got: "+t.getStderr());
    }

    @Test(groups="Integration")
    @DisableOnWindows(reason = "Needs an ssh server listening on port 22 on localhost")
    public void testRequireFailureExitsImmediately() throws Exception {
        ProcessTaskWrapper<?> t = SshTasks.newSshExecTaskFactory(loc)
            .add(bashTestInstance.requireTest("-f "+sourceNonExistantFile.getPath(),
                    "The requested file does not exist"))
            .add("echo shouldnae come here").newTask();

        exec.submit(t).get();
        assertNotEquals(t.getExitCode(), 0);
        assertTrue(t.getStderr().contains("The requested file"), "Expected message in: "+t.getStderr());
        assertTrue(t.getStdout().contains("The requested file"), "Expected message in: "+t.getStdout());
        Assert.assertFalse(t.getStdout().contains("shouldnae"), "Expected message in: "+t.getStdout());
    }

    @Test(groups="Integration")
    @DisableOnWindows(reason = "Needs a bash shell available on localhost")
    public void testPipeMultiline() throws Exception {
        String output = execRequiringZeroAndReturningStdout(loc,
                bashTestInstance.pipeTextTo("hello world\n"+"and goodbye\n", "wc")).get();

        assertEquals(Strings.replaceAllRegex(output, "\\s+", " ").trim(), "3 4 25");
    }

    @Test(groups="Integration")
    @DisableOnWindows(reason = "Needs a bash shell available on localhost")
    public void testWaitForFileContentsWhenAbortingOnFail() throws Exception {
        String fileContent = "mycontents";
        String cmd = bashTestInstance.waitForFileContents(destFile.getAbsolutePath(), fileContent, Duration.ONE_SECOND, true);

        int exitcode = loc.execCommands("test", ImmutableList.of(cmd));
        assertEquals(exitcode, 1);
        
        Files.write(fileContent, destFile, Charsets.UTF_8);
        int exitcode2 = loc.execCommands("test", ImmutableList.of(cmd));
        assertEquals(exitcode2, 0);
    }

    @Test(groups="Integration")
    @DisableOnWindows(reason = "Needs a bash shell available on localhost")
    public void testWaitForFileContentsWhenNotAbortingOnFail() throws Exception {
        String fileContent = "mycontents";
        String cmd = bashTestInstance.waitForFileContents(destFile.getAbsolutePath(), fileContent, Duration.ONE_SECOND, false);

        String output = execRequiringZeroAndReturningStdout(loc, cmd).get();
        assertTrue(output.contains("Couldn't find"), "output="+output);

        Files.write(fileContent, destFile, Charsets.UTF_8);
        String output2 = execRequiringZeroAndReturningStdout(loc, cmd).get();
        assertFalse(output2.contains("Couldn't find"), "output="+output2);
    }
    
    @Test(groups="Integration")
    @DisableOnWindows(reason = "Needs a bash shell available on localhost")
    public void testWaitForFileContentsWhenContentsAppearAfterStart() throws Exception {
        String fileContent = "mycontents";

        String cmd = bashTestInstance.waitForFileContents(destFile.getAbsolutePath(), fileContent, Duration.THIRTY_SECONDS, false);
        ProcessTaskWrapper<String> t = execRequiringZeroAndReturningStdout(loc, cmd);
        exec.submit(t);
        
        // sleep for long enough to ensure the ssh command is definitely executing
        Thread.sleep(5*1000);
        assertFalse(t.isDone());
        
        Files.write(fileContent, destFile, Charsets.UTF_8);
        String output = t.get();
        assertFalse(output.contains("Couldn't find"), "output="+output);
    }
    
    @Test(groups="Integration")
    @DisableOnWindows(reason = "Needs a bash shell available on localhost")
    public void testWaitForFileExistsWhenAbortingOnFail() throws Exception {
        String cmd = bashTestInstance.waitForFileExists(destFile.getAbsolutePath(), Duration.ONE_SECOND, true);

        int exitcode = loc.execCommands("test", ImmutableList.of(cmd));
        assertEquals(exitcode, 0);
        
        destFile.delete();
        int exitcode2 = loc.execCommands("test", ImmutableList.of(cmd));
        assertEquals(exitcode2, 1);
    }

    @Test(groups="Integration")
    @DisableOnWindows(reason = "Needs a bash shell available on localhost")
    public void testWaitForFileExistsWhenNotAbortingOnFail() throws Exception {
        String cmd = bashTestInstance.waitForFileExists(destFile.getAbsolutePath(), Duration.ONE_SECOND, false);

        String output = execRequiringZeroAndReturningStdout(loc, cmd).get();
        assertFalse(output.contains("Couldn't find"), "output="+output);

        destFile.delete();
        String output2 = execRequiringZeroAndReturningStdout(loc, cmd).get();
        assertTrue(output2.contains("Couldn't find"), "output="+output2);
    }
    
    @Test(groups="Integration", dependsOnMethods="testSudo")
    @DisableOnWindows(reason = "Needs a bash shell available on localhost")
    public void testWaitForPortFreeWhenAbortingOnTimeout() throws Exception {
        ServerSocket serverSocket = openServerSocket();
        try {
            int port = serverSocket.getLocalPort();
            String cmd = bashTestInstance.waitForPortFree(port, Duration.ONE_SECOND, true);
    
            int exitcode = loc.execCommands("test", ImmutableList.of(cmd));
            assertEquals(exitcode, 1);
            
            serverSocket.close();
            assertTrue(Networking.isPortAvailable(port));
            int exitcode2 = loc.execCommands("test", ImmutableList.of(cmd));
            assertEquals(exitcode2, 0);
        } finally {
            serverSocket.close();
        }
    }

    @Test(groups="Integration", dependsOnMethods="testSudo")
    @DisableOnWindows(reason = "Needs a bash shell available on localhost")
    public void testWaitForPortFreeWhenNotAbortingOnTimeout() throws Exception {
        ServerSocket serverSocket = openServerSocket();
        try {
            int port = serverSocket.getLocalPort();
            String cmd = bashTestInstance.waitForPortFree(port, Duration.ONE_SECOND, false);
    
            String output = execRequiringZeroAndReturningStdout(loc, cmd).get();
            assertTrue(output.contains(port+" still in use"), "output="+output);
    
            serverSocket.close();
            assertTrue(Networking.isPortAvailable(port));
            String output2 = execRequiringZeroAndReturningStdout(loc, cmd).get();
            assertFalse(output2.contains("still in use"), "output="+output2);
        } finally {
            serverSocket.close();
        }
    }
    
    @Test(groups="Integration", dependsOnMethods="testSudo")
    @DisableOnWindows(reason = "Needs a bash shell available on localhost")
    public void testWaitForPortFreeWhenFreedAfterStart() throws Exception {
        ServerSocket serverSocket = openServerSocket();
        try {
            int port = serverSocket.getLocalPort();
    
            String cmd = bashTestInstance.waitForPortFree(port, Duration.THIRTY_SECONDS, false);
            ProcessTaskWrapper<String> t = execRequiringZeroAndReturningStdout(loc, cmd);
            exec.submit(t);
            
            // sleep for long enough to ensure the ssh command is definitely executing
            Thread.sleep(5*1000);
            assertFalse(t.isDone());
            
            serverSocket.close();
            assertTrue(Networking.isPortAvailable(port));
            String output = t.get();
            assertFalse(output.contains("still in use"), "output="+output);
        } finally {
            serverSocket.close();
        }
    }

    
    // Disabled by default because of risk of overriding /etc/hosts in really bad way if doesn't work properly!
    // As a manual visual inspection test, consider first manually creating /etc/hostname and /etc/sysconfig/network
    // so that it looks like debian+ubuntu / CentOS/RHEL.
    @Test(groups={"Integration"}, enabled=false)
    public void testSetHostnameUnqualified() throws Exception {
        runSetHostname("br-"+Identifiers.makeRandomId(8).toLowerCase(), null, false);
    }

    @Test(groups={"Integration"}, enabled=false)
    public void testSetHostnameQualified() throws Exception {
        runSetHostname("br-"+Identifiers.makeRandomId(8).toLowerCase()+".brooklyn.incubator.apache.org", null, false);
    }

    @Test(groups={"Integration"}, enabled=false)
    public void testSetHostnameNullDomain() throws Exception {
        runSetHostname("br-"+Identifiers.makeRandomId(8).toLowerCase(), null, true);
    }

    @Test(groups={"Integration"}, enabled=false)
    public void testSetHostnameNonNullDomain() throws Exception {
        runSetHostname("br-"+Identifiers.makeRandomId(8).toLowerCase(), "brooklyn.incubator.apache.org", true);
    }

    protected void runSetHostname(String newHostname, String newDomain, boolean includeDomain) throws Exception {
        String fqdn = (includeDomain && Strings.isNonBlank(newDomain)) ? newHostname + "." + newDomain : newHostname;
        
        LocalManagementContextForTests mgmt = new LocalManagementContextForTests();
        SshMachineLocation loc = mgmt.getLocationManager().createLocation(LocalhostMachineProvisioningLocation.spec()).obtain();

        execRequiringZeroAndReturningStdout(loc, bashTestInstance.sudo("cp /etc/hosts /etc/hosts-orig-testSetHostname")).get();
        execRequiringZeroAndReturningStdout(loc, bashTestInstance.ifFileExistsElse0("/etc/hostname", bashTestInstance.sudo("cp /etc/hostname /etc/hostname-orig-testSetHostname"))).get();
        execRequiringZeroAndReturningStdout(loc, bashTestInstance.ifFileExistsElse0("/etc/sysconfig/network", bashTestInstance.sudo("cp /etc/sysconfig/network /etc/sysconfig/network-orig-testSetHostname"))).get();
        
        String origHostname = getHostnameNoArgs(loc);
        assertTrue(Strings.isNonBlank(origHostname));
        
        try {
            List<String> cmd = (includeDomain) ? bashTestInstance.setHostname(newHostname, newDomain) : bashTestInstance.setHostname(newHostname);
            execRequiringZeroAndReturningStdout(loc, cmd).get();

            String actualHostnameUnqualified = getHostnameUnqualified(loc);
            String actualHostnameFullyQualified = getHostnameFullyQualified(loc);

            // TODO On OS X at least, we aren't actually setting the domain name; we're just letting 
            //      the user pass in what the domain name is. We do add this properly to /etc/hosts
            //      (e.g. first line is "127.0.0.1 br-g4x5wgx8.brooklyn.incubator.apache.org br-g4x5wgx8 localhost")
            //      but subsequent calls to `hostname -f` returns the unqualified. Similarly, `domainname` 
            //      returns blank. Therefore we can't assert that it equals our expected val (because we just made  
            //      it up - "brooklyn.incubator.apache.org").
            //      assertEquals(actualHostnameFullyQualified, fqdn);
            assertEquals(actualHostnameUnqualified, Strings.getFragmentBetween(newHostname, null, "."));
            execRequiringZeroAndReturningStdout(loc, "ping -c1 -n -q "+actualHostnameUnqualified).get();
            execRequiringZeroAndReturningStdout(loc, "ping -c1 -n -q "+actualHostnameFullyQualified).get();
            
            String result = execRequiringZeroAndReturningStdout(loc, "grep -n "+fqdn+" /etc/hosts").get();
            assertTrue(result.contains("localhost"), "line="+result);
            log.info("result="+result);
            
        } finally {
            execRequiringZeroAndReturningStdout(loc, bashTestInstance.sudo("cp /etc/hosts-orig-testSetHostname /etc/hosts")).get();
            execRequiringZeroAndReturningStdout(loc, bashTestInstance.ifFileExistsElse0("/etc/hostname-orig-testSetHostname", bashTestInstance.sudo("cp /etc/hostname-orig-testSetHostname /etc/hostname"))).get();
            execRequiringZeroAndReturningStdout(loc, bashTestInstance.ifFileExistsElse0("/etc/sysconfig/network-orig-testSetHostname", bashTestInstance.sudo("cp /etc/sysconfig/network-orig-testSetHostname /etc/sysconfig/network"))).get();
            execRequiringZeroAndReturningStdout(loc, bashTestInstance.sudo("hostname "+origHostname)).get();
        }
    }

    // Marked disabled because not safe to run on your normal machine! It modifies /etc/hosts, which is dangerous if things go wrong!
    @Test(groups={"Integration"}, enabled=false)
    public void testModifyEtcHosts() throws Exception {
        LocalManagementContextForTests mgmt = new LocalManagementContextForTests();
        SshMachineLocation loc = mgmt.getLocationManager().createLocation(LocalhostMachineProvisioningLocation.spec()).obtain();

        execRequiringZeroAndReturningStdout(loc, bashTestInstance.sudo("cp /etc/hosts /etc/hosts-orig-testModifyEtcHosts")).get();
        int numLinesOrig = Integer.parseInt(execRequiringZeroAndReturningStdout(loc, "wc -l /etc/hosts").get().trim().split("\\s")[0]);
        
        try {
            String cmd = bashTestInstance.prependToEtcHosts("1.2.3.4", "myhostnamefor1234.at.start", "myhostnamefor1234b");
            execRequiringZeroAndReturningStdout(loc, cmd).get();
            
            String cmd2 = bashTestInstance.appendToEtcHosts("5.6.7.8", "myhostnamefor5678.at.end", "myhostnamefor5678");
            execRequiringZeroAndReturningStdout(loc, cmd2).get();
            
            String grepFirst = execRequiringZeroAndReturningStdout(loc, "grep -n myhostnamefor1234 /etc/hosts").get();
            String grepLast = execRequiringZeroAndReturningStdout(loc, "grep -n myhostnamefor5678 /etc/hosts").get();
            int numLinesAfter = Integer.parseInt(execRequiringZeroAndReturningStdout(loc, "wc -l /etc/hosts").get().trim().split("\\s")[0]);
            log.info("result: numLinesBefore="+numLinesOrig+"; numLinesAfter="+numLinesAfter+"; first="+grepFirst+"; last="+grepLast);
            
            assertTrue(grepFirst.startsWith("1:") && grepFirst.contains("1.2.3.4 myhostnamefor1234.at.start myhostnamefor1234"), "first="+grepFirst);
            assertTrue(grepLast.startsWith((numLinesOrig+2)+":") && grepLast.contains("5.6.7.8 myhostnamefor5678.at.end myhostnamefor5678"), "last="+grepLast);
            assertEquals(numLinesOrig + 2, numLinesAfter, "lines orig="+numLinesOrig+", after="+numLinesAfter);
        } finally {
            execRequiringZeroAndReturningStdout(loc, bashTestInstance.sudo("cp /etc/hosts-orig-testModifyEtcHosts /etc/hosts")).get();
        }
    }
    
    private String getHostnameNoArgs(SshMachineLocation machine) {
        String hostnameStdout = execRequiringZeroAndReturningStdout(machine, "echo FOREMARKER; hostname; echo AFTMARKER").get();
        return Strings.getFragmentBetween(hostnameStdout, "FOREMARKER", "AFTMARKER").trim();
    }

    private String getHostnameUnqualified(SshMachineLocation machine) {
        String hostnameStdout = execRequiringZeroAndReturningStdout(machine, "echo FOREMARKER; hostname -s 2> /dev/null || hostname; echo AFTMARKER").get();
        return Strings.getFragmentBetween(hostnameStdout, "FOREMARKER", "AFTMARKER").trim();
    }

    private String getHostnameFullyQualified(SshMachineLocation machine) {
        String hostnameStdout = execRequiringZeroAndReturningStdout(machine, "echo FOREMARKER; hostname --fqdn 2> /dev/null || hostname -f; echo AFTMARKER").get();
        return Strings.getFragmentBetween(hostnameStdout, "FOREMARKER", "AFTMARKER").trim();
    }

    private ProcessTaskWrapper<String> execRequiringZeroAndReturningStdout(SshMachineLocation loc, Collection<String> cmds) {
        return execRequiringZeroAndReturningStdout(loc, cmds.toArray(new String[cmds.size()]));
    }
    
    private ProcessTaskWrapper<String> execRequiringZeroAndReturningStdout(SshMachineLocation loc, String... cmds) {
        ProcessTaskWrapper<String> t = SshTasks.newSshExecTaskFactory(loc, cmds)
                .requiringZeroAndReturningStdout().newTask();
        exec.submit(t);
        return t;
    }

    private ServerSocket openServerSocket() {
        int lowerBound = 40000;
        int upperBound = 40100;
        for (int i = lowerBound; i < upperBound; i++) {
            try {
                return new ServerSocket(i);
            } catch (IOException e) {
                // try next number
            }
        }
        throw new IllegalStateException("No ports available in range "+lowerBound+" to "+upperBound);
    }
}
