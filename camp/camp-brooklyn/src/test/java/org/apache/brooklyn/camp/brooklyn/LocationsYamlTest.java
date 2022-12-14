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
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.util.Collection;
import java.util.List;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.location.byon.FixedListMachineProvisioningLocation;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.location.localhost.LocalhostMachineProvisioningLocation;
import org.apache.brooklyn.location.multi.MultiLocation;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

public class LocationsYamlTest extends AbstractYamlTest {
    private static final Logger log = LoggerFactory.getLogger(LocationsYamlTest.class);

    @Test
    public void testLocationString() throws Exception {
        String yaml = 
                "location: localhost\n"+
                "services:\n"+
                "- type: org.apache.brooklyn.core.test.entity.TestEntity\n";
        
        Entity app = createStartWaitAndLogApplication(yaml);
        LocalhostMachineProvisioningLocation loc = (LocalhostMachineProvisioningLocation) Iterables.getOnlyElement(app.getLocations());
        assertNotNull(loc);
    }

    @Test
    public void testLocationComplexString() throws Exception {
        String yaml = 
                "location: localhost:(name=myname)\n"+
                "services:\n"+
                "- type: org.apache.brooklyn.core.test.entity.TestEntity\n";
        
        Entity app = createStartWaitAndLogApplication(yaml);
        LocalhostMachineProvisioningLocation loc = (LocalhostMachineProvisioningLocation) Iterables.getOnlyElement(app.getLocations());
        assertEquals(loc.getDisplayName(), "myname");
    }

    @Test
    public void testLocationSplitLineWithNoConfig() throws Exception {
        String yaml = 
                "location:\n"+
                "  localhost\n"+
                "services:\n"+
                "- type: org.apache.brooklyn.core.test.entity.TestEntity\n";
        
        Entity app = createStartWaitAndLogApplication(yaml);
        LocalhostMachineProvisioningLocation loc = (LocalhostMachineProvisioningLocation) Iterables.getOnlyElement(app.getLocations());
        assertNotNull(loc);
    }

    @Test
    public void testMultiLocations() throws Exception {
        String yaml = 
                "locations:\n"+
                "- localhost:(name=loc1)\n"+
                "- localhost:(name=loc2)\n"+
                "services:\n"+
                "- type: org.apache.brooklyn.core.test.entity.TestEntity\n";
        
        Entity app = createStartWaitAndLogApplication(yaml);
        List<Location> locs = ImmutableList.copyOf(app.getLocations());
        assertEquals(locs.size(), 2, "locs="+locs);
        LocalhostMachineProvisioningLocation loc1 = (LocalhostMachineProvisioningLocation) locs.get(0);
        LocalhostMachineProvisioningLocation loc2 = (LocalhostMachineProvisioningLocation) locs.get(1);
        assertEquals(loc1.getDisplayName(), "loc1");
        assertEquals(loc2.getDisplayName(), "loc2");
    }

    @Test
    public void testLocationConfig() throws Exception {
        String yaml = 
                "location:\n"+
                "  localhost:\n"+
                "    displayName: myname\n"+
                "    myconfkey: myconfval\n"+
                "services:\n"+
                "- type: org.apache.brooklyn.core.test.entity.TestEntity\n";
        
        Entity app = createStartWaitAndLogApplication(yaml);
        LocalhostMachineProvisioningLocation loc = (LocalhostMachineProvisioningLocation) Iterables.getOnlyElement(app.getLocations());
        assertEquals(loc.getDisplayName(), "myname");
        assertEquals(loc.config().getLocalBag().getStringKey("myconfkey"), "myconfval");
    }

    @Test
    public void testMultiLocationConfig() throws Exception {
        String yaml = 
                "locations:\n"+
                "- localhost:\n"+
                "    displayName: myname1\n"+
                "    myconfkey: myconfval1\n"+
                "- localhost:\n"+
                "    displayName: myname2\n"+
                "    myconfkey: myconfval2\n"+
                "services:\n"+
                "- type: org.apache.brooklyn.core.test.entity.TestEntity\n";
        
        Entity app = createStartWaitAndLogApplication(yaml);
        List<Location> locs = ImmutableList.copyOf(app.getLocations());
        assertEquals(locs.size(), 2, "locs="+locs);
        LocalhostMachineProvisioningLocation loc1 = (LocalhostMachineProvisioningLocation) locs.get(0);
        LocalhostMachineProvisioningLocation loc2 = (LocalhostMachineProvisioningLocation) locs.get(1);
        assertEquals(loc1.getDisplayName(), "myname1");
        assertEquals(loc1.config().getLocalBag().getStringKey("myconfkey"), "myconfval1");
        assertEquals(loc2.getDisplayName(), "myname2");
        assertEquals(loc2.config().getLocalBag().getStringKey("myconfkey"), "myconfval2");
    }

    // TODO Fails because PlanInterpretationContext constructor throws NPE on location's value (using ImmutableMap).
    @Test(groups="WIP")
    public void testLocationBlank() throws Exception {
        String yaml = 
                "location: \n"+
                "services:\n"+
                "- type: org.apache.brooklyn.core.test.entity.TestEntity\n";
        
        Entity app = createStartWaitAndLogApplication(yaml);
        assertTrue(app.getLocations().isEmpty(), "locs="+app.getLocations());
    }

    @Test
    public void testInvalidLocationAndLocations() throws Exception {
        String yaml = 
                "location: localhost\n"+
                "locations:\n"+
                "- localhost\n"+
                "services:\n"+
                "- type: org.apache.brooklyn.core.test.entity.TestEntity\n";
        
        try {
            createStartWaitAndLogApplication(yaml);
        } catch (Exception e) {
            if (!e.toString().contains("Conflicting 'location' and 'locations'")) throw e;
        }
    }

    @Test
    public void testInvalidLocationList() throws Exception {
        // should have used "locations:" instead of "location:"
        String yaml = 
                "location:\n"+
                "- localhost\n"+
                "services:\n"+
                "- type: org.apache.brooklyn.core.test.entity.TestEntity\n";
        
        try {
            createStartWaitAndLogApplication(yaml);
        } catch (Exception e) {
            if (!e.toString().contains("must be a string or map")) throw e;
        }
    }
    
    @Test
    public void testRootLocationPassedToChild() throws Exception {
        String yaml = 
                "locations:\n"+
                "- localhost:(name=loc1)\n"+
                "services:\n"+
                "- type: org.apache.brooklyn.core.test.entity.TestEntity\n";
        
        Entity app = createStartWaitAndLogApplication(yaml);
        Entity child = Iterables.getOnlyElement(app.getChildren());
        LocalhostMachineProvisioningLocation loc = (LocalhostMachineProvisioningLocation) Iterables.getOnlyElement(Entities.getAllInheritedLocations(child));
        assertEquals(loc.getDisplayName(), "loc1");
    }

    @Test
    public void testByonYamlHosts() throws Exception {
        String yaml = 
                "locations:\n"+
                "- byon:\n"+
                "    user: root\n"+
                "    privateKeyFile: /tmp/key_file\n"+
                "    hosts: \n"+
                "    - 127.0.0.1\n"+
                "    - brooklyn@127.0.0.2\n"+
                "services:\n"+
                "- type: org.apache.brooklyn.core.test.entity.TestEntity\n";
        
        Entity app = createStartWaitAndLogApplication(yaml);
        Entity child = Iterables.getOnlyElement(app.getChildren());
        FixedListMachineProvisioningLocation<?> loc = (FixedListMachineProvisioningLocation<?>) Iterables.getOnlyElement(Entities.getAllInheritedLocations(child));
        Assert.assertEquals(loc.getChildren().size(), 2);
        
        SshMachineLocation l1 = (SshMachineLocation)loc.obtain();
        assertUserAddress(l1, "root", "127.0.0.1");
        assertUserAddress(loc.obtain(), "brooklyn", "127.0.0.2");
        Assert.assertEquals(l1.getConfig(SshMachineLocation.PRIVATE_KEY_FILE), "/tmp/key_file");
    }

    @Test
    public void testByonYamlHostsString() throws Exception {
        String yaml = 
                "locations:\n"+
                "- byon:\n"+
                "    user: root\n"+
                "    hosts: \"{127.0.{0,127}.{1-2},brooklyn@127.0.0.127}\"\n"+
                "services:\n"+
                "- type: org.apache.brooklyn.core.test.entity.TestEntity\n";
        
        Entity app = createStartWaitAndLogApplication(yaml);
        Entity child = Iterables.getOnlyElement(app.getChildren());
        FixedListMachineProvisioningLocation<?> loc = (FixedListMachineProvisioningLocation<?>) Iterables.getOnlyElement(Entities.getAllInheritedLocations(child));
        Assert.assertEquals(loc.getChildren().size(), 5);
        
        assertUserAddress(loc.obtain(), "root", "127.0.0.1");
        assertUserAddress(loc.obtain(), "root", "127.0.0.2");
        assertUserAddress(loc.obtain(), "root", "127.0.127.1");
        assertUserAddress(loc.obtain(), "root", "127.0.127.2");
        assertUserAddress(loc.obtain(), "brooklyn", "127.0.0.127");
    }

    @Test
    public void testMultiByonYaml() throws Exception {
        String yaml = 
                "locations:\n"+
                "- multi:\n"+
                "   targets:\n"+
                "   - byon:\n"+
                "      user: root\n"+
                "      hosts: 127.0.{0,127}.{1-2}\n"+
                "   - byon:\n"+
                "      user: brooklyn\n"+
                "      hosts:\n"+
                "      - 127.0.0.127\n"+
                "services:\n"+
                "- type: org.apache.brooklyn.core.test.entity.TestEntity\n";
        
        Entity app = createStartWaitAndLogApplication(yaml);
        Entity child = Iterables.getOnlyElement(app.getChildren());
        MultiLocation<?> loc = (MultiLocation<?>) Iterables.getOnlyElement(Entities.getAllInheritedLocations(child));
        Assert.assertEquals(loc.getSubLocations().size(), 2);
        
        assertUserAddress(loc.obtain(), "root", "127.0.0.1");
        assertUserAddress(loc.obtain(), "root", "127.0.0.2");
        assertUserAddress(loc.obtain(), "root", "127.0.127.1");
        assertUserAddress(loc.obtain(), "root", "127.0.127.2");
        assertUserAddress(loc.obtain(), "brooklyn", "127.0.0.127");
    }

    public static void assertUserAddress(MachineLocation l, String user, String address) {
        Assert.assertEquals(l.getAddress().getHostAddress(), address);
        if (!Strings.isBlank(user)) Assert.assertEquals(((SshMachineLocation)l).getUser(), user);        
    }
    
    @Override
    protected Logger getLogger() {
        return log;
    }

    @Test
    public void testLocationWithTags() throws Exception {
        String yaml =
                "location:\n"+
                "  localhost:\n"+
                "    tags: [ foo ]\n"+
                "services:\n"+
                "- type: org.apache.brooklyn.core.test.entity.TestEntity\n";

        Entity app = createStartWaitAndLogApplication(yaml);
        LocalhostMachineProvisioningLocation loc = (LocalhostMachineProvisioningLocation) Iterables.getOnlyElement(app.getLocations());
        assertNotNull(loc);
        Assert.assertTrue(loc.tags().containsTag("foo"), "location tags missing: "+loc.tags().getTags());
        // ensure tags not set as config
        Assert.assertNull(loc.config().getBag().getStringKey("tags"));
    }

    @Test
    public void testJcloudsLocationWithTagsActsCorrectly() throws Exception {
        // NOTE: 'tags' on jclouds _was_ used to set a config, NOT brooklyn object tags
        // CHANGED 2022-10 to be tags on the location, otherwise spec_hierarchy tags get passed to VMs; use brooklyn.config
        String yaml =
                "location:\n"+
                "  jclouds:aws-ec2:\n"+
                "    tags: [ bar ]\n"+
                "    brooklyn.config:\n"+
                "      tags: [ foo ]\n"+
                "services:\n"+
                "- type: org.apache.brooklyn.core.test.entity.TestEntity\n";

        Entity app = createStartWaitAndLogApplication(yaml);
        JcloudsLocation loc = (JcloudsLocation) Iterables.getOnlyElement(app.getLocations());
        assertNotNull(loc);
        Assert.assertFalse(loc.tags().containsTag("foo"), "location tags for jclouds shouldn't support 'tags' flag: "+loc.tags().getTags());
        Assert.assertTrue(loc.tags().containsTag("bar"));
        Asserts.assertNull(loc.config().getBag().getStringKey("brooklyn.config"));
        Asserts.assertThat(loc.config().get(JcloudsLocation.STRING_TAGS), r -> r instanceof Collection && ((Collection)r).contains("foo"));
        Asserts.assertThat(loc.config().get(JcloudsLocation.STRING_TAGS), r -> r instanceof Collection && !((Collection)r).contains("bar"));
    }

    @Test
    public void testJcloudsLocationWithBrooklynTags() throws Exception {
        // but now (2022-05) all location support 'brooklyn.tags' - this makes it consistent with tags elsewhere
        String yaml =
                "location:\n"+
                        "  jclouds:aws-ec2:\n"+
                        "    brooklyn.tags: [ foo ]\n"+
                        "services:\n"+
                        "- type: org.apache.brooklyn.core.test.entity.TestEntity\n";

        Entity app = createStartWaitAndLogApplication(yaml);
        JcloudsLocation loc = (JcloudsLocation) Iterables.getOnlyElement(app.getLocations());
        assertNotNull(loc);
        Assert.assertTrue(loc.tags().containsTag("foo"), "location tags missing: "+loc.tags().getTags());
    }

    @Test
    public void testLocalhostLocationWithBrooklynTags() throws Exception {
        String yaml =
                "location:\n"+
                        "  localhost:\n"+
                        "    brooklyn.tags: [ foo ]\n"+
                        "services:\n"+
                        "- type: org.apache.brooklyn.core.test.entity.TestEntity\n";

        Entity app = createStartWaitAndLogApplication(yaml);
        LocalhostMachineProvisioningLocation loc = (LocalhostMachineProvisioningLocation) Iterables.getOnlyElement(app.getLocations());
        assertNotNull(loc);
        Assert.assertTrue(loc.tags().containsTag("foo"), "location tags missing: "+loc.tags().getTags());
    }

}
