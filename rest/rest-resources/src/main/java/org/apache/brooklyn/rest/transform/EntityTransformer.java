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
package org.apache.brooklyn.rest.transform;

import static com.google.common.collect.Iterables.transform;
import static org.apache.brooklyn.rest.util.WebResourceUtils.serviceUriBuilder;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.core.UriBuilder;

import org.apache.brooklyn.api.catalog.CatalogConfig;
import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.objs.BrooklynObject;
import org.apache.brooklyn.api.objs.SpecParameter;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.catalog.internal.CatalogUtils;
import org.apache.brooklyn.core.objs.BrooklynObjectInternal;
import org.apache.brooklyn.core.typereg.RegisteredTypes;
import org.apache.brooklyn.rest.api.ApplicationApi;
import org.apache.brooklyn.rest.api.CatalogApi;
import org.apache.brooklyn.rest.api.EntityApi;
import org.apache.brooklyn.rest.domain.ConfigSummary;
import org.apache.brooklyn.rest.domain.EnricherConfigSummary;
import org.apache.brooklyn.rest.domain.EntityConfigSummary;
import org.apache.brooklyn.rest.domain.EntitySummary;
import org.apache.brooklyn.rest.domain.PolicyConfigSummary;
import org.apache.brooklyn.rest.util.BrooklynRestResourceUtils;
import org.apache.brooklyn.util.core.config.ConfigBag;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * @author Adam Lowe
 */
public class EntityTransformer {

    public static final Function<? super Entity, EntitySummary> fromEntity(final UriBuilder ub) {
        return new Function<Entity, EntitySummary>() {
            @Override
            public EntitySummary apply(Entity entity) {
                return EntityTransformer.entitySummary(entity, ub);
            }
        };
    };

    public static EntitySummary entitySummary(Entity entity, UriBuilder ub) {
        URI applicationUri = serviceUriBuilder(ub, ApplicationApi.class, "get").build(entity.getApplicationId());
        URI entityUri = serviceUriBuilder(ub, EntityApi.class, "get").build(entity.getApplicationId(), entity.getId());
        ImmutableMap.Builder<String, URI> lb = ImmutableMap.<String, URI>builder()
                .put("self", entityUri);
        if (entity.getParent()!=null) {
            URI parentUri = serviceUriBuilder(ub, EntityApi.class, "get").build(entity.getApplicationId(), entity.getParent().getId());
            lb.put("parent", parentUri);
        }

//        UriBuilder urib = serviceUriBuilder(ub, EntityApi.class, "getChildren").build(entity.getApplicationId(), entity.getId());
        // TODO: change all these as well :S
        lb.put("application", applicationUri)
                .put("children", URI.create(entityUri + "/children"))
                .put("config", URI.create(entityUri + "/config"))
                .put("sensors", URI.create(entityUri + "/sensors"))
                .put("effectors", URI.create(entityUri + "/effectors"))
                .put("adjuncts", URI.create(entityUri + "/adjuncts"))
                .put("policies", URI.create(entityUri + "/policies"))
                .put("activities", URI.create(entityUri + "/activities"))
                .put("locations", URI.create(entityUri + "/locations"))
                .put("tags", URI.create(entityUri + "/tags"))
                .put("expunge", URI.create(entityUri + "/expunge"))
                .put("rename", URI.create(entityUri + "/name"))
                .put("spec", URI.create(entityUri + "/spec"))
            ;

        if (RegisteredTypes.getIconUrl(entity)!=null)
            lb.put("iconUrl", URI.create(entityUri + "/icon"));

        if (entity.getCatalogItemId() != null) {
            String versionedId = entity.getCatalogItemId();
            URI catalogUri;
            String symbolicName = CatalogUtils.getSymbolicNameFromVersionedId(versionedId);
            String version = CatalogUtils.getVersionFromVersionedId(versionedId);
            catalogUri = serviceUriBuilder(ub, CatalogApi.class, "getEntity").build(symbolicName, version);
            lb.put("catalog", catalogUri);
        }

        String type = entity.getEntityType().getName();
        return new EntitySummary(entity.getId(), entity.getDisplayName(), type, entity.getCatalogItemId(), lb.build());
    }

    public static List<EntitySummary> entitySummaries(Iterable<? extends Entity> entities, final UriBuilder ub) {
        return Lists.newArrayList(transform(
            entities,
            new Function<Entity, EntitySummary>() {
                @Override
                public EntitySummary apply(Entity entity) {
                    return EntityTransformer.entitySummary(entity, ub);
                }
            }));
    }

    public static URI applicationUri(Application entity, UriBuilder ub) {
        return serviceUriBuilder(ub, ApplicationApi.class, "get").build(entity.getApplicationId());
    }
    
    public static URI entityUri(Entity entity, UriBuilder ub) {
        return serviceUriBuilder(ub, EntityApi.class, "get").build(entity.getApplicationId(), entity.getId());
    }
    
    /** @deprecated since 1.0.0 use {@link ConfigTransformer} */
    @Deprecated
    public static EntityConfigSummary entityConfigSummary(ConfigKey<?> config, String label, Double priority, Boolean pinned, Map<String, URI> links) {
        return new EntityConfigSummary(config, label, priority, pinned, links);
    }

    /** @deprecated since 1.0.0 use {@link ConfigTransformer} */
    @Deprecated
    public static PolicyConfigSummary policyConfigSummary(ConfigKey<?> config, String label, Double priority, Map<String, URI> links) {
        return new PolicyConfigSummary(config, label, priority, links);
    }

    /** @deprecated since 1.0.0 use {@link ConfigTransformer} */
    @Deprecated
    public static EnricherConfigSummary enricherConfigSummary(ConfigKey<?> config, String label, Double priority, Map<String, URI> links) {
        return new EnricherConfigSummary(config, label, priority, links);
    }

    /** @deprecated since 1.0.0 use {@link ConfigTransformer} */
    @Deprecated
    public static ConfigSummary entityConfigSummary(Entity entity, ConfigKey<?> config, UriBuilder ub) {
        return ConfigTransformer.of(config).on(entity).includeLinks(ub, true, true).transform();
    }

    /** @deprecated since 1.0.0 use {@link ConfigTransformer} */
    @Deprecated
    public static EntityConfigSummary entityConfigSummary(ConfigKey<?> config, Field configKeyField) {
        CatalogConfig catalogConfig = configKeyField!=null ? configKeyField.getAnnotation(CatalogConfig.class) : null;
        String label = catalogConfig==null ? null : catalogConfig.label();
        Double priority = catalogConfig==null ? null : catalogConfig.priority();
        boolean pinned = catalogConfig!=null && catalogConfig.pinned();
        return entityConfigSummary(config, label, priority, pinned, null);
    }

    /** @deprecated since 1.0.0 use {@link ConfigTransformer} */
    @Deprecated
    public static EntityConfigSummary entityConfigSummary(SpecParameter<?> input, AtomicInteger paramPriorityCnt) {
        // Increment the priority because the config container is a set. Server-side we are using an ordered set
        // which results in correctly ordered items on the wire (as a list). Clients which use the java bindings
        // though will push the items in an unordered set - so give them means to recover the correct order.
        Double priority = input.isPinned() ? Double.valueOf(paramPriorityCnt.incrementAndGet()) : null;
        return entityConfigSummary(input.getConfigKey(), input.getLabel(), priority, input.isPinned(), null);
    }

    /** @deprecated since 1.0.0 use {@link ConfigTransformer} */
    @Deprecated
    public static PolicyConfigSummary policyConfigSummary(SpecParameter<?> input) {
        Double priority = input.isPinned() ? Double.valueOf(1d) : null;
        return policyConfigSummary(input.getConfigKey(), input.getLabel(), priority, null);
    }

    /** @deprecated since 1.0.0 use {@link ConfigTransformer} */
    @Deprecated
    public static EnricherConfigSummary enricherConfigSummary(SpecParameter<?> input) {
        Double priority = input.isPinned() ? Double.valueOf(1d) : null;
        return enricherConfigSummary(input.getConfigKey(), input.getLabel(), priority, null);
    }
    
    public static Map<String, Object> getConfigValues(BrooklynRestResourceUtils utils, BrooklynObject obj) {
        // alternatively could do this - should be the same ?
//        for (ConfigKey<?> key: adjunct.config().findKeysPresent(Predicates.alwaysTrue())) {
//            result.config(key.getName(), utils.getStringValueForDisplay( adjunct.config().get(key) ));
//        }
        
        Map<String, Object> source = ConfigBag.newInstance(
            ((BrooklynObjectInternal)obj).config().getInternalConfigMap().getAllConfigInheritedRawValuesIgnoringErrors() ).getAllConfig();
        Map<String, Object> result = Maps.newLinkedHashMap();
        for (Map.Entry<String, Object> ek : source.entrySet()) {
            result.put(ek.getKey(), utils.getStringValueForDisplay(ek.getValue()));
        }
        return result;
    }
}
