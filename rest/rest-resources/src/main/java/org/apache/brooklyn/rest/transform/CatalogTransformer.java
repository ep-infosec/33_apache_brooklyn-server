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

import static org.apache.brooklyn.rest.util.WebResourceUtils.serviceUriBuilder;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.core.Application;
import javax.ws.rs.core.UriBuilder;

import org.apache.brooklyn.api.catalog.CatalogItem;
import org.apache.brooklyn.api.catalog.CatalogItem.CatalogItemType;
import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.entity.EntityType;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.objs.SpecParameter;
import org.apache.brooklyn.api.policy.Policy;
import org.apache.brooklyn.api.policy.PolicySpec;
import org.apache.brooklyn.api.sensor.Enricher;
import org.apache.brooklyn.api.sensor.EnricherSpec;
import org.apache.brooklyn.api.sensor.Sensor;
import org.apache.brooklyn.api.typereg.BrooklynTypeRegistry.RegisteredTypeKind;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.core.entity.EntityDynamicType;
import org.apache.brooklyn.core.mgmt.BrooklynTags;
import org.apache.brooklyn.core.objs.BrooklynTypes;
import org.apache.brooklyn.core.typereg.RegisteredTypes;
import org.apache.brooklyn.rest.api.CatalogApi;
import org.apache.brooklyn.rest.domain.CatalogEnricherSummary;
import org.apache.brooklyn.rest.domain.CatalogEntitySummary;
import org.apache.brooklyn.rest.domain.CatalogItemSummary;
import org.apache.brooklyn.rest.domain.CatalogLocationSummary;
import org.apache.brooklyn.rest.domain.CatalogPolicySummary;
import org.apache.brooklyn.rest.domain.EffectorSummary;
import org.apache.brooklyn.rest.domain.EnricherConfigSummary;
import org.apache.brooklyn.rest.domain.EntityConfigSummary;
import org.apache.brooklyn.rest.domain.LocationConfigSummary;
import org.apache.brooklyn.rest.domain.PolicyConfigSummary;
import org.apache.brooklyn.rest.domain.SensorSummary;
import org.apache.brooklyn.rest.domain.SummaryComparators;
import org.apache.brooklyn.rest.util.BrooklynRestResourceUtils;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.collections.MutableSet;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.javalang.Reflections;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

/** @deprecated since 1.0.0 use {@link RegisteredType} methods in {@link TypeTransformer} */
@Deprecated
public class CatalogTransformer {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(CatalogTransformer.class);
    
    public static <T extends Entity> CatalogEntitySummary catalogEntitySummary(BrooklynRestResourceUtils b, RegisteredType item, UriBuilder ub) {
        Set<EntityConfigSummary> config = Sets.newLinkedHashSet();
        Set<SensorSummary> sensors = Sets.newTreeSet(SummaryComparators.nameComparator());
        Set<EffectorSummary> effectors = Sets.newTreeSet(SummaryComparators.nameComparator());

        EntitySpec<?> spec = null;

        try {
            spec = b.getTypeRegistry().createSpec(item, null, EntitySpec.class);
            EntityDynamicType typeMap = BrooklynTypes.getDefinedEntityType(spec.getType());
            EntityType type = typeMap.getSnapshot();

            AtomicInteger priority = new AtomicInteger();
            for (SpecParameter<?> input: spec.getParameters())
                config.add(ConfigTransformer.of(input).uiIncrementAndSetPriorityIfPinned(priority).transformLegacyEntityConfig());
            for (Sensor<?> x: type.getSensors())
                sensors.add(SensorTransformer.sensorSummaryForCatalog(x));
            for (Effector<?> x: type.getEffectors())
                effectors.add(EffectorTransformer.effectorSummaryForCatalog(b.mgmt(), x));

        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            
            // templates with multiple entities can't have spec created in the manner above; just ignore
            if (item.getSuperTypes().contains(Entity.class)) {
                log.warn("Unable to create spec for "+item+": "+e, e);
            }
            if (log.isTraceEnabled()) {
                log.trace("Unable to create spec for "+item+": "+e, e);
            }
        }
        
        return new CatalogEntitySummary(item.getSymbolicName(), item.getVersion(), item.getContainingBundle(), item.getDisplayName(),
            spec!=null ? spec.getType().getName() : item.getSuperTypes().toString(), 
            spec!=null ? 
                CatalogItemType.ofTargetClass(spec.getType()).name() : 
                // RegisteredTypes.isTemplate(item) ? "template" :   // could check this, but more reliable for clients to rely on tag 
                "unknown",
            RegisteredTypes.getImplementationDataStringForSpec(item),
            item.getDescription(), tidyIconLink(b, item, item.getIconUrl(), ub),
            makeTags(spec, item), config, sensors, effectors,
            item.isDeprecated(), makeLinks(item, ub));
    }

    public static CatalogItemSummary catalogItemSummary(BrooklynRestResourceUtils b, RegisteredType item, UriBuilder ub) {
        try {
            if (item.getSuperTypes().contains(Application.class) ||
                    item.getSuperTypes().contains(Entity.class)) {
                return catalogEntitySummary(b, item, ub);
            } else if (item.getSuperTypes().contains(Policy.class)) {
                return catalogPolicySummary(b, item, ub);
            } else if (item.getSuperTypes().contains(Enricher.class)) {
                return catalogEnricherSummary(b, item, ub);
            } else if (item.getSuperTypes().contains(Location.class)) {
                return catalogLocationSummary(b, item, ub);
            } else {
                log.debug("Misc catalog item type when getting self link (supplying generic item): "+item+" "+item.getSuperTypes());
            }
        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            log.warn("Invalid item in catalog when converting REST summaries (supplying generic item), at "+item+": "+e, e);
        }
        return new CatalogItemSummary(item.getSymbolicName(), item.getVersion(), item.getContainingBundle(), item.getDisplayName(),
            item.getSuperTypes().toString(), 
            item.getKind()==RegisteredTypeKind.BEAN ? "bean" : "unknown",
            RegisteredTypes.getImplementationDataStringForSpec(item),
            item.getDescription(), tidyIconLink(b, item, item.getIconUrl(), ub), item.getTags(), item.isDeprecated(), makeLinks(item, ub));
    }

    public static CatalogPolicySummary catalogPolicySummary(BrooklynRestResourceUtils b, RegisteredType item, UriBuilder ub) {
        final Set<PolicyConfigSummary> config = Sets.newLinkedHashSet();
        PolicySpec<?> spec = null;
        try{
            spec = b.getTypeRegistry().createSpec(item, null, PolicySpec.class);
            AtomicInteger priority = new AtomicInteger();
            for (SpecParameter<?> input: spec.getParameters()) {
                config.add(ConfigTransformer.of(input).uiIncrementAndSetPriorityIfPinned(priority).transformLegacyPolicyConfig());
            }
        }catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            log.trace("Unable to create policy spec for "+item+": "+e, e);
        }
        return new CatalogPolicySummary(item.getSymbolicName(), item.getVersion(), item.getContainingBundle(), item.getDisplayName(),
                spec!=null ? spec.getType().getName() : item.getSuperTypes().toString(), 
                CatalogItemType.POLICY.toString(),
                RegisteredTypes.getImplementationDataStringForSpec(item),
                item.getDescription(), tidyIconLink(b, item, item.getIconUrl(), ub), config,
                item.getTags(), item.isDeprecated(), makeLinks(item, ub));
    }

    public static CatalogEnricherSummary catalogEnricherSummary(BrooklynRestResourceUtils b, RegisteredType item, UriBuilder ub) {
        final Set<EnricherConfigSummary> config = Sets.newLinkedHashSet();
        EnricherSpec<?> spec = null;
        try{
            spec = b.getTypeRegistry().createSpec(item, null, EnricherSpec.class);
            AtomicInteger priority = new AtomicInteger();
            for (SpecParameter<?> input: spec.getParameters()) {
                config.add(ConfigTransformer.of(input).uiIncrementAndSetPriorityIfPinned(priority).transformLegacyEnricherConfig());
            }
        }catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            log.trace("Unable to create policy spec for "+item+": "+e, e);
        }
        return new CatalogEnricherSummary(item.getSymbolicName(), item.getVersion(), item.getContainingBundle(), item.getDisplayName(),
                spec!=null ? spec.getType().getName() : item.getSuperTypes().toString(), 
                CatalogItemType.ENRICHER.toString(),
                RegisteredTypes.getImplementationDataStringForSpec(item),
                item.getDescription(), tidyIconLink(b, item, item.getIconUrl(), ub), config,
                item.getTags(), item.isDeprecated(), makeLinks(item, ub));
    }

    public static CatalogLocationSummary catalogLocationSummary(BrooklynRestResourceUtils b, RegisteredType item, UriBuilder ub) {
        Set<LocationConfigSummary> config = ImmutableSet.of();
        return new CatalogLocationSummary(item.getSymbolicName(), item.getVersion(), item.getContainingBundle(), item.getDisplayName(),
                item.getSuperTypes().toString(), 
                CatalogItemType.LOCATION.toString(),
                RegisteredTypes.getImplementationDataStringForSpec(item),
                item.getDescription(), tidyIconLink(b, item, item.getIconUrl(), ub), config,
                item.getTags(), item.isDeprecated(), makeLinks(item, ub));
    }

    protected static Map<String, URI> makeLinks(RegisteredType item, UriBuilder ub) {
        return MutableMap.<String, URI>of().addIfNotNull("self", getSelfLink(item, ub));
    }

    protected static URI getSelfLink(RegisteredType item, UriBuilder ub) {
        String itemId = item.getId();
        if (item.getSuperTypes().contains(Application.class)) {
            return serviceUriBuilder(ub, CatalogApi.class, "getApplication").build(itemId, item.getVersion());
        } else if (item.getSuperTypes().contains(Entity.class)) {
            return serviceUriBuilder(ub, CatalogApi.class, "getEntity").build(itemId, item.getVersion());
        } else if (item.getSuperTypes().contains(Policy.class)) {
            return serviceUriBuilder(ub, CatalogApi.class, "getPolicy").build(itemId, item.getVersion());
        } else if (item.getSuperTypes().contains(Enricher.class)) {
            return serviceUriBuilder(ub, CatalogApi.class, "getEnricher").build(itemId, item.getVersion());
        } else if (item.getSuperTypes().contains(Location.class)) {
            return serviceUriBuilder(ub, CatalogApi.class, "getLocation").build(itemId, item.getVersion());
        } else {
            log.warn("Unexpected catalog item type when getting self link (not supplying self link): "+item+" "+item.getSuperTypes());
            return null;
        }
    }
    private static String tidyIconLink(BrooklynRestResourceUtils b, RegisteredType item, String iconUrl, UriBuilder ub) {
        if (b.isUrlServerSideAndSafe(iconUrl)) {
            return serviceUriBuilder(ub, CatalogApi.class, "getIcon").build(item.getSymbolicName(), item.getVersion()).toString();
        }
        return iconUrl;
    }

    private static Set<Object> makeTags(EntitySpec<?> spec, RegisteredType item) {
        return makeTags(spec, MutableSet.copyOf(item.getTags()));
    }
    private static Set<Object> makeTags(EntitySpec<?> spec, CatalogItem<?, ?> item) {
        return makeTags(spec, MutableSet.copyOf(item.tags().getTags()));
    }
    private static Set<Object> makeTags(EntitySpec<?> spec, Set<Object> tags) {
        // Combine tags on item with an InterfacesTag.
        if (spec != null) {
            Class<?> type;
            if (spec.getImplementation() != null) {
                type = spec.getImplementation();
            } else {
                type = spec.getType();
            }
            if (type != null) {
                tags.add(new BrooklynTags.TraitsTag(Reflections.getAllInterfaces(type)));
            }
        }
        return tags;
    }
    
    /** @deprecated since 0.12.0 use {@link RegisteredType} methods instead */  @Deprecated
    public static <T extends Entity> CatalogEntitySummary catalogEntitySummary(BrooklynRestResourceUtils b, CatalogItem<T,EntitySpec<? extends T>> item, UriBuilder ub) {
        Set<EntityConfigSummary> config = Sets.newLinkedHashSet();
        Set<SensorSummary> sensors = Sets.newTreeSet(SummaryComparators.nameComparator());
        Set<EffectorSummary> effectors = Sets.newTreeSet(SummaryComparators.nameComparator());

        EntitySpec<?> spec = null;

        try {
            spec = (EntitySpec<?>) b.getCatalog().peekSpec(item);
            EntityDynamicType typeMap = BrooklynTypes.getDefinedEntityType(spec.getType());
            EntityType type = typeMap.getSnapshot();

            AtomicInteger priority = new AtomicInteger();
            for (SpecParameter<?> input: spec.getParameters())
                config.add(ConfigTransformer.of(input).uiIncrementAndSetPriorityIfPinned(priority).transformLegacyEntityConfig());
            for (Sensor<?> x: type.getSensors())
                sensors.add(SensorTransformer.sensorSummaryForCatalog(x));
            for (Effector<?> x: type.getEffectors())
                effectors.add(EffectorTransformer.effectorSummaryForCatalog(b.mgmt(), x));

        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            
            // templates with multiple entities can't have spec created in the manner above; just ignore
            if (item.getCatalogItemType()==CatalogItemType.ENTITY) {
                log.warn("Unable to create spec for "+item+": "+e, e);
            }
            if (log.isTraceEnabled()) {
                log.trace("Unable to create spec for "+item+": "+e, e);
            }
        }
        
        return new CatalogEntitySummary(item.getSymbolicName(), item.getVersion(), item.getContainingBundle(), item.getDisplayName(),
            item.getJavaType(), item.getCatalogItemType().toString(), item.getPlanYaml(),
            item.getDescription(), tidyIconLink(b, item, item.getIconUrl(), ub),
            makeTags(spec, item), config, sensors, effectors,
            item.isDeprecated(), makeLinks(item, ub));
    }

    /** @deprecated since 0.12.0 use {@link RegisteredType} methods instead */  @Deprecated
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static CatalogItemSummary catalogItemSummary(BrooklynRestResourceUtils b, CatalogItem item, UriBuilder ub) {
        try {
            switch (item.getCatalogItemType()) {
            case TEMPLATE:
            case APPLICATION:
            case ENTITY:
                return catalogEntitySummary(b, item, ub);
            case POLICY:
                return catalogPolicySummary(b, item, ub);
            case ENRICHER:
                return catalogEnricherSummary(b, item, ub);
            case LOCATION:
                return catalogLocationSummary(b, item, ub);
            default:
                log.warn("Unexpected catalog item type when getting self link (supplying generic item): "+item.getCatalogItemType()+" "+item);
            }
        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            log.warn("Invalid item in catalog when converting REST summaries (supplying generic item), at "+item+": "+e, e);
        }
        return new CatalogItemSummary(item.getSymbolicName(), item.getVersion(), item.getContainingBundle(), item.getDisplayName(),
            item.getJavaType(), item.getCatalogItemType().toString(), item.getPlanYaml(),
            item.getDescription(), tidyIconLink(b, item, item.getIconUrl(), ub), item.tags().getTags(), item.isDeprecated(), makeLinks(item, ub));
    }

    /** @deprecated since 0.12.0 use {@link RegisteredType} methods instead */  @Deprecated
    public static CatalogPolicySummary catalogPolicySummary(BrooklynRestResourceUtils b, CatalogItem<? extends Policy,PolicySpec<?>> item, UriBuilder ub) {
        final Set<PolicyConfigSummary> config = Sets.newLinkedHashSet();
        try{
            final PolicySpec<?> spec = (PolicySpec<?>) b.getCatalog().peekSpec(item);
            AtomicInteger priority = new AtomicInteger();
            for (SpecParameter<?> input: spec.getParameters()) {
                config.add(ConfigTransformer.of(input).uiIncrementAndSetPriorityIfPinned(priority).transformLegacyPolicyConfig());
            }
        }catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            log.trace("Unable to create policy spec for "+item+": "+e, e);
        }
        return new CatalogPolicySummary(item.getSymbolicName(), item.getVersion(), item.getContainingBundle(), item.getDisplayName(),
                item.getJavaType(), item.getCatalogItemType().toString(), item.getPlanYaml(),
                item.getDescription(), tidyIconLink(b, item, item.getIconUrl(), ub), config,
                item.tags().getTags(), item.isDeprecated(), makeLinks(item, ub));
    }

    /** @deprecated since 0.12.0 use {@link RegisteredType} methods instead */  @Deprecated
    public static CatalogEnricherSummary catalogEnricherSummary(BrooklynRestResourceUtils b, CatalogItem<? extends Enricher,EnricherSpec<?>> item, UriBuilder ub) {
        final Set<EnricherConfigSummary> config = Sets.newLinkedHashSet();
        try{
            final EnricherSpec<?> spec = (EnricherSpec<?>) b.getCatalog().peekSpec(item);
            AtomicInteger priority = new AtomicInteger();
            for (SpecParameter<?> input: spec.getParameters()) {
                config.add(ConfigTransformer.of(input).uiIncrementAndSetPriorityIfPinned(priority).transformLegacyEnricherConfig());
            }
        }catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            log.trace("Unable to create policy spec for "+item+": "+e, e);
        }
        return new CatalogEnricherSummary(item.getSymbolicName(), item.getVersion(), item.getContainingBundle(), item.getDisplayName(),
                item.getJavaType(), item.getCatalogItemType().toString(), item.getPlanYaml(),
                item.getDescription(), tidyIconLink(b, item, item.getIconUrl(), ub), config,
                item.tags().getTags(), item.isDeprecated(), makeLinks(item, ub));
    }

    /** @deprecated since 0.12.0 use {@link RegisteredType} methods instead */  @Deprecated
    public static CatalogLocationSummary catalogLocationSummary(BrooklynRestResourceUtils b, CatalogItem<? extends Location,LocationSpec<?>> item, UriBuilder ub) {
        Set<LocationConfigSummary> config = ImmutableSet.of();
        return new CatalogLocationSummary(item.getSymbolicName(), item.getVersion(), item.getContainingBundle(), item.getDisplayName(),
                item.getJavaType(), item.getCatalogItemType().toString(), item.getPlanYaml(),
                item.getDescription(), tidyIconLink(b, item, item.getIconUrl(), ub), config,
                item.tags().getTags(), item.isDeprecated(), makeLinks(item, ub));
    }

    private static Map<String, URI> makeLinks(CatalogItem<?,?> item, UriBuilder ub) {
        return MutableMap.<String, URI>of().addIfNotNull("self", getSelfLink(item, ub));
    }

    private static URI getSelfLink(CatalogItem<?,?> item, UriBuilder ub) {
        String itemId = item.getId();
        switch (item.getCatalogItemType()) {
        case TEMPLATE:
        case APPLICATION:
            return serviceUriBuilder(ub, CatalogApi.class, "getApplication").build(itemId, item.getVersion());
        case ENTITY:
            return serviceUriBuilder(ub, CatalogApi.class, "getEntity").build(itemId, item.getVersion());
        case POLICY:
            return serviceUriBuilder(ub, CatalogApi.class, "getPolicy").build(itemId, item.getVersion());
        case ENRICHER:
            return serviceUriBuilder(ub, CatalogApi.class, "getEnricher").build(itemId, item.getVersion());
        case LOCATION:
            return serviceUriBuilder(ub, CatalogApi.class, "getLocation").build(itemId, item.getVersion());
        default:
            log.warn("Unexpected catalog item type when getting self link (not supplying self link): "+item.getCatalogItemType()+" "+item);
            return null;
        }
    }
    private static String tidyIconLink(BrooklynRestResourceUtils b, CatalogItem<?,?> item, String iconUrl, UriBuilder ub) {
        if (b.isUrlServerSideAndSafe(iconUrl)) {
            return serviceUriBuilder(ub, CatalogApi.class, "getIcon").build(item.getSymbolicName(), item.getVersion()).toString();
        }
        return iconUrl;
    }

}
