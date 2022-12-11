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
package org.apache.brooklyn.launcher.blueprints;

import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.core.entity.Dumper;
import org.apache.brooklyn.entity.stock.BasicEntity;
import org.testng.annotations.Test;

public class SimpleBlueprintTest extends AbstractBlueprintTest {

    @Override
    protected boolean isViewerEnabled() {
        return true;
    }

    @Override
    protected boolean isUsingNewViewerForRebind() {
        return true;
    }

    // only Live because it starts a server
    @Test(groups={"Live"})
    public void testBasicEntity() throws Exception {
        Application app = runTestOnBlueprint("services: [ { type: " + BasicEntity.class.getName() + " } ]");

        // stick a breakpoint on the following line (make sure it is thread-only, not all-threads!)
        // then connect a UI eg brooklyn-ui/app-inspector `make dev` to the API endpoint used
        Dumper.dumpInfo(app);
    }

}
