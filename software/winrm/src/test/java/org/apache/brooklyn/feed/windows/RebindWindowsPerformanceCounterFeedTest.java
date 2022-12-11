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
package org.apache.brooklyn.feed.windows;

import org.apache.brooklyn.api.objs.BrooklynObjectType;
import org.apache.brooklyn.core.mgmt.rebind.AbstractRebindHistoricTest;
import org.testng.annotations.Test;

public class RebindWindowsPerformanceCounterFeedTest extends AbstractRebindHistoricTest {

    @Test
    public void testWindowsPerformanceCounterFeed_2017_06() throws Exception {
        addMemento(BrooklynObjectType.FEED, "windows-performance-counter-feed", "tu4kk0xvf8");
        rebind();
    }

    @Test
    public void testWindowsPerformanceCounterFeed_2017_06_withoutBundlePrefixes() throws Exception {
        addMemento(BrooklynObjectType.FEED, "windows-performance-counter-feed-no-bundle-prefixies", "ueauyeu41d");
        rebind();
    }
}
