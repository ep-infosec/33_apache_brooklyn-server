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
package org.apache.brooklyn.entity;

import java.util.List;
import java.util.Map;

import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.core.entity.BrooklynConfigKeys;
import org.apache.brooklyn.core.internal.BrooklynProperties;
import org.apache.brooklyn.core.test.BrooklynAppLiveTestSupport;
import org.apache.brooklyn.util.collections.MutableMap;
import org.testng.annotations.Test;

import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * Runs a test with many different distros and versions.
 */
public abstract class AbstractGoogleComputeLiveTest extends BrooklynAppLiveTestSupport {
    
    // TODO See todos in AbstractEc2LiveTest
    
    public static final String PROVIDER = "google-compute-engine";
    public static final String REGION_NAME = null;//"us-central1";
    public static final String LOCATION_SPEC = PROVIDER + (REGION_NAME == null ? "" : ":" + REGION_NAME);
    public static final String STANDARD_HARDWARE_ID = "us-central1-b/n1-standard-1-d";
    private static final int MAX_TAG_LENGTH = 63;

    protected Location jcloudsLocation;
    
    @Override
    protected BrooklynProperties getBrooklynProperties() {
        // Don't let any defaults from brooklyn.properties (except credentials) interfere with test
        List<String> propsToRemove = ImmutableList.of("imageId", "imageDescriptionRegex", "imageNameRegex", "inboundPorts", "hardwareId", "minRam");
        BrooklynProperties result = BrooklynProperties.Factory.newDefault();
        for (String propToRemove : propsToRemove) {
            for (String propVariant : ImmutableList.of(propToRemove, CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_HYPHEN, propToRemove))) {
                result.remove("brooklyn.locations.jclouds."+PROVIDER+"."+propVariant);
                result.remove("brooklyn.locations."+propVariant);
                result.remove("brooklyn.jclouds."+PROVIDER+"."+propVariant);
                result.remove("brooklyn.jclouds."+propVariant);
            }
        }

        // Also removes scriptHeader (e.g. if doing `. ~/.bashrc` and `. ~/.profile`, then that can cause "stdin: is not a tty")
        result.remove(BrooklynConfigKeys.SSH_CONFIG_SCRIPT_HEADER.getName());
        
        return result;
    }
    
    @Test(groups = {"Live"})
    public void test_DefaultImage() throws Exception {
        runTest(ImmutableMap.<String,String>of());
    }

    // most of these not available
    
//    @Test(groups = {"Live"})
//    public void test_GCEL_10_04() throws Exception {
//        // release codename "squeeze"
//        runTest(ImmutableMap.of("imageId", "gcel-10-04-v20130325", "loginUser", "admin", "hardwareId", STANDARD_HARDWARE_ID));
//    }
//
//    @Test(groups = {"Live"})
//    public void test_GCEL_12_04() throws Exception {
//        // release codename "squeeze"
//        runTest(ImmutableMap.of("imageId", "gcel-12-04-v20130325", "loginUser", "admin", "hardwareId", STANDARD_HARDWARE_ID));
//    }
//
//    @Test(groups = {"Live"})
//    public void test_Ubuntu_10_04() throws Exception {
//        // release codename "squeeze"
//        runTest(ImmutableMap.of("imageId", "ubuntu-10-04-v20120912", "loginUser", "admin", "hardwareId", STANDARD_HARDWARE_ID));
//    }
//
//    @Test(groups = {"Live"})
//    public void test_Ubuntu_12_04() throws Exception {
//        // release codename "squeeze"
//        runTest(ImmutableMap.of("imageId", "ubuntu-10-04-v20120912", "loginUser", "admin", "hardwareId", STANDARD_HARDWARE_ID));
//    }
//
//    @Test(groups = {"Live"})
//    public void test_CentOS_6() throws Exception {
//        runTest(ImmutableMap.of("imageId", "centos-6-v20130325", "hardwareId", STANDARD_HARDWARE_ID));
//    }

    protected void runTest(Map<String,?> flags) throws Exception {
        String tag = getClass().getSimpleName().toLowerCase();
        int length = tag.length();
        if (length > MAX_TAG_LENGTH)
            tag = tag.substring(length - MAX_TAG_LENGTH, length);
        Map<String,?> allFlags = MutableMap.<String,Object>builder()
                .put("tags", ImmutableList.of(tag))
                .putAll(flags)
                .build();
        jcloudsLocation = mgmt.getLocationRegistry().getLocationManaged(LOCATION_SPEC, allFlags);

        doTest(jcloudsLocation);
    }

    protected abstract void doTest(Location loc) throws Exception;
}
