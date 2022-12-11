/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.test.framework;

import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.util.core.flags.SetFromFlag;

import com.google.common.collect.ImmutableMap;

/**
 * Entity that tests a sensor value on another entity
 */
@ImplementedBy(value = TestSensorImpl.class)
public interface TestSensor extends BaseTest {

    @SetFromFlag(nullable = false)
    ConfigKey<String> SENSOR_NAME = ConfigKeys.newConfigKey(String.class, "sensor", "Sensor to evaluate");

    /**
     * Abort-conditions - if matched, the test-case will abort early.
     */
    ConfigKey<Object> ABORT_CONDITIONS = ConfigKeys.newConfigKey(
            Object.class, 
            "abortConditions", 
            "Abort conditions to be evaluated (abort if non-empty and any are true)",
            ImmutableMap.<String, Object>of());
}
