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
package org.apache.brooklyn.location.localhost;

import java.util.Map;

import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.LocationRegistry;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.location.AbstractLocationResolver;
import org.apache.brooklyn.core.location.LocationConfigKeys;
import org.apache.brooklyn.core.location.LocationConfigUtils;
import org.apache.brooklyn.util.core.config.ConfigBag;

/**
 * Examples of valid specs:
 *   <ul>
 *     <li>localhost
 *     <li>localhost()
 *     <li>localhost(name=abc)
 *     <li>localhost(name="abc")
 *   </ul>
 * 
 * @author alex, aled
 */
public class LocalhostLocationResolver extends AbstractLocationResolver {

    private static String BROOKLYN_LOCATION_LOCALHOST_PREFIX = LocationConfigUtils.BROOKLYN_LOCATION_PREFIX + "." + "localhost";
    
    public static ConfigKey<Boolean> LOCALHOST_ENABLED = ConfigKeys.newConfigKeyWithPrefix(BROOKLYN_LOCATION_LOCALHOST_PREFIX+".", LocationConfigKeys.ENABLED);
    
    public static final String LOCALHOST = "localhost";

    @Override
    public String getPrefix() {
        return LOCALHOST;
    }

    @Override
    public boolean isEnabled() {
        // right now these two checks, but in case the generic mechanism in super changes...
        return super.isEnabled() && LocationConfigUtils.isEnabled(managementContext, BROOKLYN_LOCATION_LOCALHOST_PREFIX);
    }

    @Override
    protected Class<? extends Location> getLocationType() {
        return LocalhostMachineProvisioningLocation.class;
    }

    @Override
    protected SpecParser getSpecParser() {
        return new AbstractLocationResolver.SpecParser(getPrefix()).setExampleUsage("\"localhost\" or \"localhost(displayName=abc)\"");
    }

    @Override
    protected Map<String, Object> getFilteredLocationProperties(String provider, String namedLocation, Map<String, ?> prioritisedProperties, Map<String, ?> globalProperties) {
        return new LocalhostPropertiesFromBrooklynProperties().getLocationProperties("localhost", namedLocation, globalProperties);
    }

    @Override
    protected ConfigBag extractConfig(Map<?,?> locationFlags, String spec, LocationRegistry registry) {
        ConfigBag config = super.extractConfig(locationFlags, spec, registry);
        config.putAsStringKeyIfAbsent("name", "localhost");
        return config;
    }
}
