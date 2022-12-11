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
package org.apache.brooklyn.tasks.kubectl;

import com.beust.jcommander.internal.Maps;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.brooklyn.core.mgmt.ha.BrooklynBomOsgiArchiveInstaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class KubeJobSpecCreatorTest {
    private static final Logger LOG = LoggerFactory.getLogger(KubeJobSpecCreatorTest.class);

    @Test
    public void testPerlWithArgs() throws  Exception{
        BrooklynBomOsgiArchiveInstaller.FileWithTempInfo<File> yamlJobLocation =
                new KubeJobFileCreator().withImage("perl").withName("perl-args-test")
                        .withArgs(Lists.newArrayList( "echo", "aaa"))
                        .withImagePullPolicy(PullPolicy.ALWAYS) // explicit "Always"
                        .createFile();
        assertNotNull(yamlJobLocation);
        String actual = String.join("\n", Files.readAllLines(yamlJobLocation.getFile().toPath()));
        String expected = "apiVersion: batch/v1\n" +
                "kind: Job\n" +
                "metadata:\n" +
                "  name: perl-args-test\n" +
                "spec:\n" +
                "  backoffLimit: 0\n" +
                "  completions: 1\n" +
                "  parallelism: 1\n" +
                "  template:\n" +
                "    spec:\n" +
                "      automountServiceAccountToken: false\n" +
                "      containers:\n" +
                "      - args:\n" +
                "        - echo\n" +
                "        - aaa\n" +
                "        image: perl\n" +
                "        imagePullPolicy: Always\n" +
                "        name: test\n" +
                "      restartPolicy: Never";
        assertEquals(expected,actual);
    }

    @Test
    public void testPerlWithArgsAndCommand() throws  Exception{
        BrooklynBomOsgiArchiveInstaller.FileWithTempInfo<File> yamlJobLocation =
                new KubeJobFileCreator().withImage("perl").withName("perl-args-and-command-test")
                        .withCommand(Lists.newArrayList("/bin/bash"))
                        .withArgs(Lists.newArrayList("-c", "echo aaa"))
                        .withImagePullPolicy(PullPolicy.NEVER)
                        .createFile();
        assertNotNull(yamlJobLocation);
        String actual = String.join("\n", Files.readAllLines(yamlJobLocation.getFile().toPath()));
        String expected = "apiVersion: batch/v1\n" +
                "kind: Job\n" +
                "metadata:\n" +
                "  name: perl-args-and-command-test\n" +
                "spec:\n" +
                "  backoffLimit: 0\n" +
                "  completions: 1\n" +
                "  parallelism: 1\n" +
                "  template:\n" +
                "    spec:\n" +
                "      automountServiceAccountToken: false\n" +
                "      containers:\n" +
                "      - args:\n" +
                "        - -c\n" +
                "        - echo aaa\n" +
                "        command:\n" +
                "        - /bin/bash\n" +
                "        image: perl\n" +
                "        imagePullPolicy: Never\n" +
                "        name: test\n" +
                "      restartPolicy: Never";
        assertEquals(expected,actual);
    }

    @Test
    public void testPerlCommand() throws  Exception{
        BrooklynBomOsgiArchiveInstaller.FileWithTempInfo<File> yamlJobLocation =
                new KubeJobFileCreator().withImage("perl").withName("perl-command-test")
                        .withCommand(Lists.newArrayList("/bin/bash", "-c", "echo aaa"))
                        .withImagePullPolicy(PullPolicy.IF_NOT_PRESENT)
                        .createFile();
        assertNotNull(yamlJobLocation);
        String actual = String.join("\n", Files.readAllLines(yamlJobLocation.getFile().toPath()));
        String expected = "apiVersion: batch/v1\n" +
                "kind: Job\n" +
                "metadata:\n" +
                "  name: perl-command-test\n" +
                "spec:\n" +
                "  backoffLimit: 0\n" +
                "  completions: 1\n" +
                "  parallelism: 1\n" +
                "  template:\n" +
                "    spec:\n" +
                "      automountServiceAccountToken: false\n" +
                "      containers:\n" +
                "      - command:\n" +
                "        - /bin/bash\n" +
                "        - -c\n" +
                "        - echo aaa\n" +
                "        image: perl\n" +
                "        imagePullPolicy: IfNotPresent\n" +
                "        name: test\n" +
                "      restartPolicy: Never";
        assertEquals(expected,actual);
    }

    @Test
    public void testTerraformWithVolumeJobBuilder() throws  Exception{
        Map<String,Object> volumes = Maps.newHashMap();
        volumes.put("name", "tf-ws");
        volumes.put("hostPath", Maps.newHashMap("path", "/tfws"));
        BrooklynBomOsgiArchiveInstaller.FileWithTempInfo<File> yamlJobLocation = new KubeJobFileCreator().withImage("hashicorp/terraform").withName("tf-version")
                .withVolumes(Sets.newHashSet(volumes))
                .withVolumeMounts(Sets.newHashSet(Maps.newHashMap("name", "tf-ws", "mountPath", "/tfws")))
                .withCommand(Lists.newArrayList("terraform", "version"))
                .withWorkingDir("/tfws/app1")
                .createFile();
        assertNotNull(yamlJobLocation);
        String actual = String.join("\n", Files.readAllLines(yamlJobLocation.getFile().toPath()));
        String expected = "apiVersion: batch/v1\n" +
                "kind: Job\n" +
                "metadata:\n" +
                "  name: tf-version\n" +
                "spec:\n" +
                "  backoffLimit: 0\n" +
                "  completions: 1\n" +
                "  parallelism: 1\n" +
                "  template:\n" +
                "    spec:\n" +
                "      automountServiceAccountToken: false\n" +
                "      containers:\n" +
                "      - command:\n" +
                "        - terraform\n" +
                "        - version\n" +
                "        image: hashicorp/terraform\n" +
                // implicit "Always" for imagePullPolicy, let Kubernetes make the decision
                "        name: test\n" +
                "        volumeMounts:\n" +
                "        - mountPath: /tfws\n" +
                "          name: tf-ws\n" +
                "        workingDir: /tfws/app1\n" +
                "      restartPolicy: Never\n" +
                "      volumes:\n" +
                "      - name: tf-ws\n" +
                "        hostPath:\n" +
                "          path: /tfws";
        assertEquals(expected,actual);
    }
}
