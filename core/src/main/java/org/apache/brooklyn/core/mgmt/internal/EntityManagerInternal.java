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
package org.apache.brooklyn.core.mgmt.internal;

import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.mgmt.EntityManager;

import com.google.common.annotations.Beta;
import com.google.common.base.Optional;
import org.apache.brooklyn.util.guava.Maybe;

public interface EntityManagerInternal extends EntityManager, BrooklynObjectManagerInternal<Entity> {

    /** gets all entities currently known to the application, including entities that are not yet managed */
    Iterable<Entity> getAllEntitiesInApplication(Application application);

    public Iterable<String> getEntityIds();
    
    /**
     * Same as {@link #createEntity(EntitySpec)}, but takes an optional entity id that will be 
     * used for the entity.
     *
     * @deprecated since 1.1.0 use the options
     */
    @Beta @Deprecated
    default <T extends Entity> T createEntity(EntitySpec<T> spec, Optional<String> entityId) {
        return createEntity(spec, new EntityCreationOptions() {
            public String getRequiredUniqueId() {
                return entityId.orNull();
            }
        });
    }
    
    /**
     * Similar to {@link #unmanage(Entity)}, but used to discard partially constructed entities.
     * 
     * @throws IllegalStateException if the entity, or any of its descendents are already managed.
     */
    @Beta
    void discardPremanaged(Entity e);
}
