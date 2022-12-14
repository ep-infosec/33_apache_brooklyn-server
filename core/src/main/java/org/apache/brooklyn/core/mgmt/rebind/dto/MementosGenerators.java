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
package org.apache.brooklyn.core.mgmt.rebind.dto;

import static com.google.common.base.Preconditions.checkNotNull;

import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import org.apache.brooklyn.api.catalog.CatalogItem;
import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.Group;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.mgmt.rebind.mementos.CatalogItemMemento;
import org.apache.brooklyn.api.mgmt.rebind.mementos.EnricherMemento;
import org.apache.brooklyn.api.mgmt.rebind.mementos.EntityMemento;
import org.apache.brooklyn.api.mgmt.rebind.mementos.FeedMemento;
import org.apache.brooklyn.api.mgmt.rebind.mementos.LocationMemento;
import org.apache.brooklyn.api.mgmt.rebind.mementos.ManagedBundleMemento;
import org.apache.brooklyn.api.mgmt.rebind.mementos.Memento;
import org.apache.brooklyn.api.mgmt.rebind.mementos.PolicyMemento;
import org.apache.brooklyn.api.objs.BrooklynObject;
import org.apache.brooklyn.api.objs.EntityAdjunct;
import org.apache.brooklyn.api.policy.Policy;
import org.apache.brooklyn.api.relations.RelationshipType;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.sensor.AttributeSensor.SensorPersistenceMode;
import org.apache.brooklyn.api.sensor.Enricher;
import org.apache.brooklyn.api.sensor.Feed;
import org.apache.brooklyn.api.typereg.ManagedBundle;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.catalog.internal.CatalogItemDo;
import org.apache.brooklyn.core.enricher.AbstractEnricher;
import org.apache.brooklyn.core.entity.EntityDynamicType;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.entity.EntityRelations;
import org.apache.brooklyn.core.feed.AbstractFeed;
import org.apache.brooklyn.core.location.internal.LocationInternal;
import org.apache.brooklyn.core.mgmt.persist.BrooklynPersistenceUtils;
import org.apache.brooklyn.core.mgmt.rebind.AbstractBrooklynObjectRebindSupport;
import org.apache.brooklyn.core.objs.BrooklynObjectInternal;
import org.apache.brooklyn.core.objs.BrooklynTypes;
import org.apache.brooklyn.core.policy.AbstractPolicy;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.collections.MutableSet;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.flags.FlagUtils;
import org.apache.brooklyn.util.core.xstream.OsgiClassPrefixer;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.Beta;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.Sets;

public class MementosGenerators {

    private MementosGenerators() {}

    private static final Logger log = LoggerFactory.getLogger(MementosGenerators.class);
    
    /**
     * Inspects a brooklyn object to create a basic corresponding memento.
     * <p>
     * The memento is "basic" in the sense that it does not tie in to any entity-specific customization;
     * the corresponding memento may subsequently be customized by the caller.
     * <p>
     * This method is intended for use by {@link AbstractBrooklynObjectRebindSupport#getMemento()}
     * and callers wanting a memento for an object should use that, or the
     * {@link BrooklynPersistenceUtils#newObjectMemento(BrooklynObject)} convenience.
     */
    @Beta
    public static Memento newBasicMemento(BrooklynObject instance) {
        if (instance instanceof Entity) {
            return newEntityMemento((Entity)instance);
        } else if (instance instanceof Location) {
            return newLocationMemento((Location)instance);
        } else if (instance instanceof Policy) {
            return newPolicyMemento((Policy)instance);
        } else if (instance instanceof Enricher) {
            return newEnricherMemento((Enricher) instance);
        } else if (instance instanceof Feed) {
            return newFeedMemento((Feed)instance);
        } else if (instance instanceof CatalogItem) {
            return newCatalogItemMemento((CatalogItem<?,?>) instance);
        } else if (instance instanceof ManagedBundle) {
            return newManagedBundleMemento((ManagedBundle) instance);
        } else {
            throw new IllegalArgumentException("Unexpected brooklyn type: "+(instance == null ? "null" : instance.getClass())+" ("+instance+")");
        }
    }

    /**
     * Inspects an entity to create a corresponding memento.
     */
    private static EntityMemento newEntityMemento(Entity entityRaw) {
        EntityInternal entity = (EntityInternal) entityRaw;
        BasicEntityMemento.Builder builder = BasicEntityMemento.builder();
        populateBrooklynObjectMementoBuilder(entity, builder);
        
        EntityDynamicType definedType = BrooklynTypes.getDefinedEntityType(entity.getClass());
                
        // TODO the dynamic attributeKeys and configKeys are computed in the BasicEntityMemento
        // whereas effectors are computed here -- should be consistent! 
        // (probably best to compute attrKeys and configKeys here)
        builder.effectors.addAll(entity.getEntityType().getEffectors());
        builder.effectors.removeAll(definedType.getEffectors().values());
        
        builder.isTopLevelApp = (entity instanceof Application && entity.getParent() == null);

        builder.configKeys.addAll(entity.getEntityType().getConfigKeys());

        Map<ConfigKey<?>, ?> localConfig = entity.config().getAllLocalRaw();
        for (Map.Entry<ConfigKey<?>, ?> entry : localConfig.entrySet()) {
            ConfigKey<?> key = checkNotNull(entry.getKey(), localConfig);
            Object value = configValueToPersistable(entry.getValue(), entityRaw, key.getName());
            builder.config.put(key, value); 
        }
        
        Map<String, Object> localConfigUnmatched = MutableMap.copyOf(entity.config().getLocalBag().getAllConfig());
        for (ConfigKey<?> key : localConfig.keySet()) {
            localConfigUnmatched.remove(key.getName());
        }
        for (Map.Entry<String, Object> entry : localConfigUnmatched.entrySet()) {
            String key = checkNotNull(entry.getKey(), localConfig);
            Object value = entry.getValue();
            // TODO Not transforming; that code is deleted in another pending PR anyway!
            builder.configUnmatched.put(key, value); 
        }
        
        Map<AttributeSensor<?>, Object> allAttributes = entity.sensors().getAll();
        for (Map.Entry<AttributeSensor<?>, Object> entry : allAttributes.entrySet()) {
            AttributeSensor<?> key = checkNotNull(entry.getKey(), allAttributes);
            if (key.getPersistenceMode() != SensorPersistenceMode.NONE) {
                Object value = entry.getValue();
                builder.attributes.put(key, value);
            }
        }
        
        for (Location location : entity.getLocations()) {
            builder.locations.add(location.getId()); 
        }

        for (Entity child : entity.getChildren()) {
            builder.children.add(child.getId()); 
        }
        
        for (Policy policy : entity.policies()) {
            builder.policies.add(policy.getId()); 
        }
        
        for (Enricher enricher : entity.enrichers()) {
            builder.enrichers.add(enricher.getId()); 
        }
        
        for (Feed feed : entity.feeds().getFeeds()) {
            builder.feeds.add(feed.getId()); 
        }
        
        Entity parentEntity = entity.getParent();
        builder.parent = (parentEntity != null) ? parentEntity.getId() : null;

        if (entity instanceof Group) {
            for (Entity member : ((Group)entity).getMembers()) {
                builder.members.add(member.getId()); 
            }
        }

        return builder.build();
    }
 
    /**
     * Given a location, extracts its state for serialization.
     * 
     * For bits of state that are references to other locations, these are treated in a special way:
     * the location reference is replaced by the location id.
     * TODO When we have a cleaner separation of constructor/config for entities and locations, then
     * we will remove this code!
     * 
     * @deprecated since 0.7.0, see {@link #newBasicMemento(BrooklynObject)}
     */
    @Deprecated
    public static LocationMemento newLocationMemento(Location location) {
        return newLocationMementoBuilder(location).build();
    }

    static Set<String> nonPersistableFlagNames(Object typeInstance, boolean forRemoval) {
        MutableSet<String> result = MutableSet.copyOf(MutableMap.<String, Object>builder()
                .putAll(typeInstance == null ? null : FlagUtils.getFieldsWithFlagsWithModifiers(typeInstance, Modifier.TRANSIENT))
                .putAll(typeInstance == null ? null : FlagUtils.getFieldsWithFlagsWithModifiers(typeInstance, Modifier.STATIC))
                .filterValues(Predicates.not(Predicates.instanceOf(ConfigKey.class)))
                .build()
                .keySet());
        if (!forRemoval) {
            result
                .put("id")
                .put("name")
                .put("tags")
                .put("brooklyn.tags");
        }
        return result;
    }

    static Map<String,Object> persistableFlagValues(Object typeInstance) {
        return MutableMap.copyOf(MutableMap.<String, Object>builder()
                .putAll(FlagUtils.getFieldsWithFlagsExcludingModifiers(typeInstance, Modifier.STATIC ^ Modifier.TRANSIENT))
                .removeAll(nonPersistableFlagNames(typeInstance, false))
                .build());
    }

    /**
     * @deprecated since 0.7.0; use {@link #newBasicMemento(BrooklynObject)} instead
     */
    @Deprecated
    public static BasicLocationMemento.Builder newLocationMementoBuilder(Location location) {
        BasicLocationMemento.Builder builder = BasicLocationMemento.builder();
        populateBrooklynObjectMementoBuilder(location, builder);

        ConfigBag persistableConfig = new ConfigBag().copy( ((LocationInternal)location).config().getLocalBag() )
                .removeAll(nonPersistableFlagNames(location, true));

        builder.copyConfig(persistableConfig);
        builder.locationConfig.putAll(persistableFlagValues(location));

        Location parentLocation = location.getParent();
        builder.parent = (parentLocation != null) ? parentLocation.getId() : null;
        
        for (Location child : location.getChildren()) {
            builder.children.add(child.getId()); 
        }
        
        return builder;
    }
    
    /**
     * Given a policy, extracts its state for serialization.
     * 
     * @deprecated since 0.7.0, see {@link #newBasicMemento(BrooklynObject)}
     */
    @Deprecated
    public static PolicyMemento newPolicyMemento(Policy policy) {
        BasicPolicyMemento.Builder builder = BasicPolicyMemento.builder();
        populateBrooklynObjectMementoBuilder(policy, builder);

        // TODO persist config keys as well? Or only support those defined on policy class;
        // current code will lose the ConfigKey type on rebind for anything not defined on class.
        // Whereas entities support that.
        Map<ConfigKey<?>, Object> config = ((AbstractPolicy)policy).config().getInternalConfigMap().getAllConfigLocalRaw();
        for (Map.Entry<ConfigKey<?>, Object> entry : config.entrySet()) {
            ConfigKey<?> key = checkNotNull(entry.getKey(), "config=%s", config);
            Object value = configValueToPersistable(entry.getValue(), policy, key.getName());
            builder.config.put(key.getName(), value); 
        }

        builder.highlights(policy.getHighlights());

        builder.config.putAll(persistableFlagValues(policy));

        return builder.build();
    }
    
    /**
     * Given an enricher, extracts its state for serialization.
     * @deprecated since 0.7.0, see {@link #newBasicMemento(BrooklynObject)}
     */
    @Deprecated
    public static EnricherMemento newEnricherMemento(Enricher enricher) {
        BasicEnricherMemento.Builder builder = BasicEnricherMemento.builder();
        populateBrooklynObjectMementoBuilder(enricher, builder);
        
        // TODO persist config keys as well? Or only support those defined on policy class;
        // current code will lose the ConfigKey type on rebind for anything not defined on class.
        // Whereas entities support that.
        Map<ConfigKey<?>, Object> config = ((AbstractEnricher)enricher).config().getInternalConfigMap().getAllConfigLocalRaw();
        for (Map.Entry<ConfigKey<?>, Object> entry : config.entrySet()) {
            ConfigKey<?> key = checkNotNull(entry.getKey(), "config=%s", config);
            Object value = configValueToPersistable(entry.getValue(), enricher, key.getName());
            builder.config.put(key.getName(), value); 
        }
        
        builder.config.putAll(persistableFlagValues(enricher));

        return builder.build();
    }

    /**
     * Given a feed, extracts its state for serialization.
     * @deprecated since 0.7.0, see {@link #newBasicMemento(BrooklynObject)}
     */
    @Deprecated
    public static FeedMemento newFeedMemento(Feed feed) {
        BasicFeedMemento.Builder builder = BasicFeedMemento.builder();
        populateBrooklynObjectMementoBuilder(feed, builder);
        
        // TODO persist config keys as well? Or only support those defined on policy class;
        // current code will lose the ConfigKey type on rebind for anything not defined on class.
        // Whereas entities support that.
        Map<ConfigKey<?>, Object> config = ((AbstractFeed)feed).config().getInternalConfigMap().getAllConfigLocalRaw();
        for (Map.Entry<ConfigKey<?>, Object> entry : config.entrySet()) {
            ConfigKey<?> key = checkNotNull(entry.getKey(), "config=%s", config);
            Object value = configValueToPersistable(entry.getValue(), feed, key.getName());
            builder.config.put(key.getName(), value); 
        }

        // feed does not include flags
        
        return builder.build();
    }
    
    @SuppressWarnings("deprecation")
    private static CatalogItemMemento newCatalogItemMemento(CatalogItem<?, ?> catalogItem) {
        if (catalogItem instanceof CatalogItemDo<?,?>) {
            catalogItem = ((CatalogItemDo<?,?>)catalogItem).getDto();
        }
        BasicCatalogItemMemento.Builder builder = BasicCatalogItemMemento.builder();
        populateBrooklynObjectMementoBuilder(catalogItem, builder);
        builder.catalogItemJavaType(catalogItem.getCatalogItemJavaType())
            .catalogItemType(catalogItem.getCatalogItemType())
            .containingBundle(catalogItem.getContainingBundle())
            .description(catalogItem.getDescription())
            .iconUrl(catalogItem.getIconUrl())
            .javaType(catalogItem.getJavaType())
            .libraries(catalogItem.getLibraries())
            .symbolicName(catalogItem.getSymbolicName())
            .specType(catalogItem.getSpecType())
            .version(catalogItem.getVersion())
            .planYaml(catalogItem.getPlanYaml())
            .deprecated(catalogItem.isDeprecated())
            .disabled(catalogItem.isDisabled());
        return builder.build();
    }
    
    private static ManagedBundleMemento newManagedBundleMemento(ManagedBundle bundle) {
        BasicManagedBundleMemento.Builder builder = BasicManagedBundleMemento.builder();
        populateBrooklynObjectMementoBuilder(bundle, builder);
        builder.url(bundle.getUrl())
            .symbolicName(bundle.getSymbolicName())
            .version(bundle.getSuppliedVersionString())
            .checksum(bundle.getChecksum())
            .deleteable(bundle.getDeleteable())
            .format(bundle.getFormat());
        return builder.build();
    }
    
    private static void populateBrooklynObjectMementoBuilder(BrooklynObject instance, AbstractMemento.Builder<?> builder) {
        if (Proxy.isProxyClass(instance.getClass())) {
            throw new IllegalStateException("Attempt to create memento from proxy "+instance+" (would fail with wrong type)");
        }
        OsgiClassPrefixer prefixer = new OsgiClassPrefixer();
        Optional<String> typePrefix = prefixer.getPrefix(instance.getClass());

        builder.id = instance.getId();
        builder.displayName = instance.getDisplayName();
        builder.catalogItemId = instance.getCatalogItemId();
        builder.searchPath = instance.getCatalogItemIdSearchPath();
        builder.type = (typePrefix.isPresent() ? typePrefix.get() : "") + instance.getClass().getName();
        builder.typeClass = instance.getClass();
        if (instance instanceof EntityAdjunct) {
            builder.uniqueTag = ((EntityAdjunct)instance).getUniqueTag();
        }
        for (Object tag : instance.tags().getTags()) {
            builder.tags.add(tag); 
        }
        // CatalogItems return empty support, so this is safe even through they don't support relations
        for (RelationshipType<?,? extends BrooklynObject> relationship: instance.relations().getRelationshipTypes()) {
            @SuppressWarnings({ "unchecked", "rawtypes" })
            Set relations = instance.relations().getRelations((RelationshipType)relationship);
            Set<String> relationIds = Sets.newLinkedHashSet();
            for (Object r: relations) relationIds.add( ((BrooklynObject)r).getId() );

            // key is string name if known relationship type, otherwise the relationship type object
            Object relTest = EntityRelations.lookup( ((BrooklynObjectInternal)instance).getManagementContext(), relationship.getRelationshipTypeName() );
            Object rKey = relationship.equals(relTest) ? relationship.getRelationshipTypeName() : relationship;

            builder.relations.put(rKey, relationIds);
        }
    }

    /** @deprecated since 0.10.0; use {@link #configValueToPersistable(Object, BrooklynObject, String)} */ @Deprecated
    protected static Object configValueToPersistable(Object value) {
        return configValueToPersistable(value, null, null);
    }
    
    private static Set<String> WARNED_ON_PERSISTING_TASK_CONFIG = MutableSet.of();
    
    protected static Object configValueToPersistable(Object value, BrooklynObject obj, String keyName) {
        // TODO Swapping an attributeWhenReady task for the actual value, if completed.
        // Long-term, want to just handle task-persistence properly.
        if (value instanceof Task) {
            Task<?> task = (Task<?>) value;
            String contextName = "";
            if (obj!=null) {
                contextName = obj.getCatalogItemId();
                if (Strings.isBlank(contextName)) contextName= obj.getDisplayName();
            }
            if (keyName!=null) {
                if (Strings.isNonBlank(contextName)) contextName += ":";
                contextName += keyName;
            }
            String message = "Persisting "+contextName+" - encountered task "+value;
            Object result = null;
            if (task.isDone() && !task.isError()) {
                result = task.getUnchecked();
                message += "; persisting result "+result;
            } else {
                // TODO how to record a completed but errored task?
                message += "; persisting as null";
                result = null;
            }
            if (WARNED_ON_PERSISTING_TASK_CONFIG.add(contextName)) {
                log.warn(message+" (subsequent values for this key will be at null)");
            } else {
                log.debug(message);
            }
            return result;
        }
        return value;
    }
}
