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

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.core.BrooklynVersion;
import org.apache.brooklyn.core.config.ConfigPredicates;
import org.apache.brooklyn.core.config.ConfigUtils;
import org.apache.brooklyn.core.config.external.ExternalConfigSupplier;
import org.apache.brooklyn.core.config.external.InPlaceExternalConfigSupplier;
import org.apache.brooklyn.core.internal.BrooklynProperties;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.ClassLoaderUtils;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.javalang.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

/**
 * Simple registry implementation.
 *
 * Permits a number of {@link ExternalConfigSupplier} instances to be registered, each with a unique name, for future
 * (deferred) lookup of configuration values.
 */
public class BasicExternalConfigSupplierRegistry implements ExternalConfigSupplierRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(BasicExternalConfigSupplierRegistry.class);

    public static final String DEMO_SAMPLE_PROVIDER = "brooklyn-demo-sample";
    public static final String DEMO_SAMPLE_PROVIDER_PASSWORD_KEY = "hidden-brooklyn-password";
    public static final String DEMO_SAMPLE_PROVIDER_PASSWORD_VALUE = "br00k11n";
    
    private final Map<String, ExternalConfigSupplier> providersByName = Maps.newLinkedHashMap();
    private final Object providersMapMutex = new Object();

    public BasicExternalConfigSupplierRegistry(ManagementContext mgmt) {
        addProvider(DEMO_SAMPLE_PROVIDER, new InPlaceExternalConfigSupplier(mgmt, DEMO_SAMPLE_PROVIDER,
                MutableMap.of(DEMO_SAMPLE_PROVIDER_PASSWORD_KEY, DEMO_SAMPLE_PROVIDER_PASSWORD_VALUE)));
        updateFromBrooklynProperties(mgmt);
    }

    @Override
    public void addProvider(String name, ExternalConfigSupplier supplier) {
        synchronized (providersMapMutex) {
            if (providersByName.containsKey(name) && !DEMO_SAMPLE_PROVIDER.equals(name)) {
                // allow demo to be overridden
                throw new IllegalArgumentException("Provider already registered with name '" + name + "'");
            }
            providersByName.put(name, supplier);
        }
        LOG.debug("Added external config supplier named '" + name + "': " + supplier);
    }

    @Override
    public void removeProvider(String name) {
        synchronized (providersMapMutex) {
            ExternalConfigSupplier supplier = providersByName.remove(name);
            LOG.info("Removed external config supplier named '" + name + "': " + supplier);
        }
    }

    @Override
    public String getConfig(String providerName, String key) {
        synchronized (providersMapMutex) {
            ExternalConfigSupplier provider = providersByName.get(providerName);
            if (provider == null)
                throw new IllegalArgumentException("No provider found with name '" + providerName + "'");
            return provider.get(key);
        }
    }

    @SuppressWarnings("unchecked")
    private void updateFromBrooklynProperties(ManagementContext mgmt) {
        // form is:
        //     brooklyn.external.<name> : fully.qualified.ClassName
        //     brooklyn.external.<name>.<key> : <value>
        //     brooklyn.external.<name>.<key> : <value>
        //     brooklyn.external.<name>.<key> : <value>

        String EXTERNAL_PROVIDER_PREFIX = "brooklyn.external.";
        Map<String, Object> externalProviderProperties = mgmt.getConfig().submap(ConfigPredicates.nameStartsWith(EXTERNAL_PROVIDER_PREFIX)).asMapWithStringKeys();
        List<Exception> exceptions = new LinkedList<Exception>();

        for (String key : externalProviderProperties.keySet()) {
            String strippedKey = key.substring(EXTERNAL_PROVIDER_PREFIX.length());
            if (strippedKey.contains("."))
                continue;

            String name = strippedKey;
            String providerClassname = (String) externalProviderProperties.get(key);
            BrooklynProperties config = ConfigUtils.filterForPrefixAndStrip(externalProviderProperties, key + ".");

            try {
                Class<ExternalConfigSupplier> supplierClass = (Class<ExternalConfigSupplier>)new ClassLoaderUtils(this, mgmt).loadClass(providerClassname);
                Maybe<ExternalConfigSupplier> configSupplier = Reflections.invokeConstructorFromArgs(supplierClass, mgmt, name, config);
                if (!configSupplier.isPresent()) {
                    configSupplier = Reflections.invokeConstructorFromArgs(supplierClass, mgmt, name, config.asMapWithStringKeys());
                }
                if (!configSupplier.isPresent()) {
                    configSupplier = Reflections.invokeConstructorFromArgs(supplierClass, mgmt, name);
                }
                if (!configSupplier.isPresent()) {
                    throw new IllegalStateException("No matching constructor found in "+providerClassname);
                }
                
                addProvider(name, configSupplier.get());

            } catch (Exception e) {
                LOG.error("Failed to instantiate external config supplier named '" + name + "': " + e, e);
                exceptions.add(e);
            }
        }

        if (!exceptions.isEmpty())
            Exceptions.propagate(exceptions);
    }

}
