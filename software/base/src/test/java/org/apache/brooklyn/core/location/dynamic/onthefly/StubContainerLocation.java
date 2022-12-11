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
package org.apache.brooklyn.core.location.dynamic.onthefly;

import org.apache.brooklyn.api.location.LocationDefinition;
import org.apache.brooklyn.core.location.dynamic.DynamicLocation;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.core.flags.SetFromFlag;

public class StubContainerLocation extends SshMachineLocation implements DynamicLocation<StubContainer, StubContainerLocation> {

    @SetFromFlag("machine")
    private SshMachineLocation machine;

    @Override
    public StubContainer getOwner() {
        return (StubContainer) config().get(OWNER);
    }

    public SshMachineLocation getMachine() {
        return machine;
    }

    @Override
    public LocationDefinition register() {
        throw new UnsupportedOperationException("Container location type definition cannot be persisted");
    }

    @Override
    public void deregister() {
        // no-op
    }
}
