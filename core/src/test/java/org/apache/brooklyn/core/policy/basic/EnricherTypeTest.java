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
package org.apache.brooklyn.core.policy.basic;

import static org.testng.Assert.assertEquals;

import org.apache.brooklyn.api.sensor.EnricherSpec;
import org.apache.brooklyn.api.sensor.EnricherType;
import org.apache.brooklyn.core.config.BasicConfigKey;
import org.apache.brooklyn.core.enricher.AbstractEnricher;
import org.apache.brooklyn.core.test.BrooklynAppUnitTestSupport;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableSet;

public class EnricherTypeTest extends BrooklynAppUnitTestSupport {
    private MyEnricher enricher;
    
    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception{
        super.setUp();
        enricher = app.enrichers().add(EnricherSpec.create(MyEnricher.class));
    }

    @Test
    public void testGetConfig() throws Exception {
        EnricherType enricherType = enricher.getEnricherType();
        assertEquals(enricherType.getConfigKeys(), ImmutableSet.of(MyEnricher.CONF1, MyEnricher.CONF2, AbstractEnricher.SUPPRESS_DUPLICATES));
        assertEquals(enricherType.getName(), MyEnricher.class.getCanonicalName());
        assertEquals(enricherType.getConfigKey("test.conf1"), MyEnricher.CONF1);
        assertEquals(enricherType.getConfigKey("test.conf2"), MyEnricher.CONF2);
    }
    
    public static class MyEnricher extends AbstractEnricher {
        public static final BasicConfigKey<String> CONF1 = new BasicConfigKey<String>(String.class, "test.conf1", "my descr, conf1", "defaultval1");
        public static final BasicConfigKey<Integer> CONF2 = new BasicConfigKey<Integer>(Integer.class, "test.conf2", "my descr, conf2", 2);
    }
}
