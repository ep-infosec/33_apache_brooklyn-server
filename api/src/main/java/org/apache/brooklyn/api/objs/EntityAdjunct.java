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
package org.apache.brooklyn.api.objs;

import org.apache.brooklyn.api.entity.Entity;

import java.util.Map;

import javax.annotation.Nullable;

/**
 * EntityAdjuncts are supplementary logic that can be attached to Entities, 
 * such as providing sensor enrichment or event-driven policy behavior
 */
public interface EntityAdjunct extends BrooklynObject {
    /**
     * A unique id for this adjunct, typically created by the system with no meaning
     */
    @Override
    String getId();

    /**
     * Whether the adjunct is destroyed
     */
    boolean isDestroyed();
    
    /**
     * Whether the adjunct is available/active, ie started and not stopped or interrupted
     */
    boolean isRunning();
    
    /**
     * An optional tag used to identify adjuncts with a specific purpose, typically created by the caller.
     * This is used to prevent multiple instances with the same purpose from being created,
     * and to access and customize adjuncts so created.
     * <p>
     * This will be included in the call to {@link BrooklynObject#tags()}.
     */
    @Nullable String getUniqueTag();

    Map<String, HighlightTuple> getHighlights();

    interface AutoStartEntityAdjunct extends EntityAdjunct {
        /** for things that should start when the entity is managed, including on rebind;
         *  replaces logic which started things during creation time */
        public void start();
    }
}
