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

import java.util.Set;

import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.config.ConfigKey.HasConfigKey;
import org.apache.brooklyn.config.ConfigMap;

import com.google.common.annotations.Beta;
import com.google.common.base.Predicate;

/**
 * Something that has mutable config, such as an entity or policy.
 * 
 * @author aled
 */
public interface Configurable {

    /**
     * Convenience for calling {@link ConfigurationSupport#get(ConfigKey)},
     * via code like {@code config().get(key)}.
     * 
     * @since 0.9.0
     */
    <T> T getConfig(ConfigKey<T> key);

    ConfigurationSupport config();
    
    @Beta
    public interface ConfigurationSupport {

        /**
         * Gets the given configuration value for this entity, in the following order of precedence:
         * <ol>
         *   <li> value (including null) explicitly set on the entity
         *   <li> value (including null) explicitly set on an ancestor (inherited)
         *   <li> a default value (including null) on the best equivalent static key of the same name declared on the entity
         *        (where best equivalence is defined as preferring a config key which extends another, 
         *        as computed in EntityDynamicType.getConfigKeys)
         *   <li> a default value (including null) on the key itself
         *   <li> null
         * </ol>
         */
        <T> T get(ConfigKey<T> key);
        
        /**
         * @see {@link #get(ConfigKey)}
         */
        <T> T get(HasConfigKey<T> key);

        /**
         * Sets the config to the given value.
         */
        <T> T set(ConfigKey<T> key, T val); 
        
        /**
         * @see {@link #set(ConfigKey, Object)}
         */
        <T> T set(HasConfigKey<T> key, T val);
        
        /**
         * Sets the config to the value returned by the task.
         * 
         * Returns immediately without blocking; subsequent calls to {@link #getConfig(ConfigKey)} 
         * will execute the task, and block until the task completes.
         * 
         * @deprecated since 1.0.0; do not use task because can be evaluated only once, and if 
         *             cancelled will affect all subsequent lookups of the config value.
         *             Consider using a {@link org.apache.brooklyn.api.mgmt.TaskFactory}.
         */
        <T> T set(ConfigKey<T> key, Task<T> val);
        
        /**
         * @see {@link #set(ConfigKey, Task)}
         * 
         * @deprecated since 1.0.0 (see {@link #set(ConfigKey, Task)}
         */
        <T> T set(HasConfigKey<T> key, Task<T> val);
        
        /** @deprecated since 0.11.0 see {@link ConfigMap#findKeys(Predicate)} */
        @Deprecated
        Set<ConfigKey<?>> findKeys(Predicate<? super ConfigKey<?>> filter);

        /** see {@link ConfigMap#findKeysDeclared(Predicate)}  */
        public Set<ConfigKey<?>> findKeysDeclared(Predicate<? super ConfigKey<?>> filter);

        /** see {@link ConfigMap#findKeysPresent(Predicate)}  */
        public Set<ConfigKey<?>> findKeysPresent(Predicate<? super ConfigKey<?>> filter);
    }
}
