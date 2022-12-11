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
package org.apache.brooklyn.api.mgmt.rebind.mementos;

import javax.annotation.Nullable;

import com.google.common.io.ByteSource;

public interface ManagedBundleMemento extends Memento {

    String getSymbolicName();
    String getVersion();

    String getFormat();

    String getUrl();
    
    @Nullable
    String getChecksum();
    
    ByteSource getJarContent();
    void setJarContent(ByteSource byteSource);

    @Nullable
    /** whether the bundle is known to be able to be permanently deleteable (eg it was installed by a user) */
    Boolean getDeleteable();

}
