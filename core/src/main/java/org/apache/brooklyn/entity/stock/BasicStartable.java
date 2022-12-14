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
package org.apache.brooklyn.entity.stock;

import java.util.List;

import com.google.common.collect.ImmutableList;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.BrooklynConfigKeys;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.location.Locations;
import org.apache.brooklyn.util.core.flags.SetFromFlag;

/**
 * Provides a pass-through Startable entity used for keeping hierarchies tidy. 
 */
@ImplementedBy(BasicStartableImpl.class)
public interface BasicStartable extends Entity, Startable {

    @SetFromFlag("startLatch")
    ConfigKey<Boolean> START_LATCH = BrooklynConfigKeys.START_LATCH;

    ConfigKey<Locations.LocationsFilter> LOCATIONS_FILTER = ConfigKeys.newConfigKey(
            Locations.LocationsFilter.class,
            "brooklyn.locationsFilter", 
            "Provides a hook for customizing locations to be used for a given context");

}
