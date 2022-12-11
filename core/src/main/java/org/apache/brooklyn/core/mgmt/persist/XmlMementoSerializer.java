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
package org.apache.brooklyn.core.mgmt.persist;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.SingleValueConverter;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.converters.reflection.ReflectionConverter;
import com.thoughtworks.xstream.core.ReferenceByXPathUnmarshaller;
import com.thoughtworks.xstream.core.ReferencingMarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import com.thoughtworks.xstream.io.path.PathTrackingReader;
import com.thoughtworks.xstream.mapper.Mapper;
import com.thoughtworks.xstream.mapper.MapperWrapper;
import org.apache.brooklyn.api.catalog.CatalogItem;
import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.internal.AbstractBrooklynObjectSpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.mgmt.classloading.BrooklynClassLoadingContext;
import org.apache.brooklyn.api.mgmt.rebind.mementos.BrooklynMementoPersister.LookupContext;
import org.apache.brooklyn.api.objs.EntityAdjunct;
import org.apache.brooklyn.api.objs.Identifiable;
import org.apache.brooklyn.api.policy.Policy;
import org.apache.brooklyn.api.sensor.Enricher;
import org.apache.brooklyn.api.sensor.Feed;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.config.StringConfigMap;
import org.apache.brooklyn.core.catalog.internal.CatalogBundleDto;
import org.apache.brooklyn.core.catalog.internal.CatalogUtils;
import org.apache.brooklyn.core.config.BasicConfigKey;
import org.apache.brooklyn.core.effector.BasicParameterType;
import org.apache.brooklyn.core.effector.EffectorAndBody;
import org.apache.brooklyn.core.effector.EffectorTasks.EffectorBodyTaskFactory;
import org.apache.brooklyn.core.effector.EffectorTasks.EffectorTaskFactory;
import org.apache.brooklyn.core.mgmt.classloading.ClassLoaderFromStackOfBrooklynClassLoadingContext;
import org.apache.brooklyn.core.mgmt.internal.ManagementContextInternal;
import org.apache.brooklyn.core.mgmt.rebind.dto.*;
import org.apache.brooklyn.core.sensor.BasicAttributeSensor;
import org.apache.brooklyn.core.typereg.BundleUpgradeParser.CatalogUpgrades;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.xstream.LambdaPreventionMapper;
import org.apache.brooklyn.util.core.xstream.XmlSerializer;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;

/* uses xml, cleaned up a bit
 * 
 * there is an early attempt at doing this with JSON in pull request #344 but 
 * it is not nicely deserializable, see comments at http://xstream.codehaus.org/json-tutorial.html */  
public class XmlMementoSerializer<T> extends XmlSerializer<T> implements MementoSerializer<T> {

    private static final Logger LOG = LoggerFactory.getLogger(XmlMementoSerializer.class);

    private final ClassLoaderFromStackOfBrooklynClassLoadingContext delegatingClassLoader;
    private LookupContext lookupContext;


    public static class XmlMementoSerializerBuilder<T> {
        public static <T> XmlMementoSerializerBuilder<T> empty() {
            return new XmlMementoSerializerBuilder<>();
        }

        public static <T> XmlMementoSerializerBuilder<T> from(ManagementContext mgmt) {
            return XmlMementoSerializerBuilder.<T>empty()
                    .withClassLoader(mgmt.getClass().getClassLoader())
                    .withConfig( ((ManagementContextInternal)mgmt).getBrooklynProperties());
        }

        public static <T> XmlMementoSerializerBuilder<T> from(StringConfigMap cfg) {
            return XmlMementoSerializerBuilder.<T>empty().withConfig(cfg);
        }

        public static <T> XmlMementoSerializerBuilder<T> from(ConfigBag cfg) {
            return XmlMementoSerializerBuilder.<T>empty().withConfig(cfg);
        }

        ClassLoader classLoader = null;
        Map<String, String> deserializingClassRenames = null;
        Function<MapperWrapper,MapperWrapper> mapperCustomizer = null;

        public XmlMementoSerializer<T> build() {
            return new XmlMementoSerializer<T>(classLoader, deserializingClassRenames, mapperCustomizer);
        }

        public XmlMementoSerializerBuilder<T> withConfig(StringConfigMap cfg) {
            return withConfig(ConfigBag.newInstance().putAll(cfg.getAllConfigLocalRaw()));
        }
        public XmlMementoSerializerBuilder<T> withConfig(ConfigBag cfg) {
            return withMapperCustomizer(LambdaPreventionMapper.factory(cfg));
        }

        public XmlMementoSerializerBuilder<T> withClassLoader(ClassLoader classLoader) {
            this.classLoader = classLoader;
            return this;
        }

        public XmlMementoSerializerBuilder<T> withBrooklynDeserializingClassRenames() {
            return withDeserializingClassRenames(DeserializingClassRenamesProvider.INSTANCE.loadDeserializingMapping());
        }

        public XmlMementoSerializerBuilder<T> withDeserializingClassRenames(Map<String, String> deserializingClassRenames) {
            // only injected in tests; the default one works great
            this.deserializingClassRenames = deserializingClassRenames==null ? ImmutableMap.of() : deserializingClassRenames;
            return this;
        }

        public XmlMementoSerializerBuilder<T> withMapperCustomizer(Function<MapperWrapper, MapperWrapper> mapperCustomizer) {
            this.mapperCustomizer = mapperCustomizer;
            return this;
        }
    }

    @Deprecated /** @deprecated since 1.1, use {@link XmlMementoSerializerBuilder} */
    public XmlMementoSerializer(ClassLoader classLoader) {
        this(classLoader, DeserializingClassRenamesProvider.INSTANCE.loadDeserializingMapping());
    }

    @Deprecated /** @deprecated since 1.1, use {@link XmlMementoSerializerBuilder} */
    public XmlMementoSerializer(ClassLoader classLoader, Map<String, String> deserializingClassRenames) {
        this(classLoader, deserializingClassRenames, null);
    }

    protected XmlMementoSerializer(ClassLoader classLoader, Map<String, String> deserializingClassRenames, Function<MapperWrapper,MapperWrapper> mapperCustomizer) {
        super(new ClassLoaderFromStackOfBrooklynClassLoadingContext(classLoader), deserializingClassRenames, mapperCustomizer);
        this.delegatingClassLoader = (ClassLoaderFromStackOfBrooklynClassLoadingContext) xstream.getClassLoader();
        
        xstream.alias("entity", BasicEntityMemento.class);
        xstream.alias("location", BasicLocationMemento.class);
        xstream.alias("policy", BasicPolicyMemento.class);
        xstream.alias("feed", BasicFeedMemento.class);
        xstream.alias("enricher", BasicEnricherMemento.class);
        xstream.alias("configKey", BasicConfigKey.class);
        xstream.alias("catalogItem", BasicCatalogItemMemento.class);
        xstream.alias("managedBundle", BasicManagedBundleMemento.class);
        xstream.alias("bundle", CatalogBundleDto.class);
        xstream.alias("attributeSensor", BasicAttributeSensor.class);

        xstream.alias("effector", Effector.class);
        xstream.addDefaultImplementation(EffectorAndBody.class, Effector.class);
        xstream.alias("parameter", BasicParameterType.class);
        xstream.addDefaultImplementation(EffectorBodyTaskFactory.class, EffectorTaskFactory.class);
        
        xstream.alias("entityRef", Entity.class);
        xstream.alias("locationRef", Location.class);
        xstream.alias("policyRef", Policy.class);
        xstream.alias("enricherRef", Enricher.class);
        xstream.alias("feedRef", Feed.class);

        xstream.registerConverter(new LocationConverter());
        // xstream.registerConverter(new EntityAdjunctConverter());  // not needed, but kept for a bit in case it is, 2022-11
        xstream.registerConverter(new PolicyConverter());
        xstream.registerConverter(new EnricherConverter());
        xstream.registerConverter(new FeedConverter());
        xstream.registerConverter(new EntityConverter());
        xstream.registerConverter(new CatalogItemConverter());
        xstream.registerConverter(new SpecConverter());

        xstream.registerConverter(new ManagementContextConverter());
        xstream.registerConverter(new TaskConverter(xstream.getMapper()));
    
        //For compatibility with existing persistence stores content.
        xstream.aliasField("registeredTypeName", BasicCatalogItemMemento.class, "symbolicName");
        configureXstreamWithDeprecatedItems();
    }

    @SuppressWarnings("deprecation")
    private void configureXstreamWithDeprecatedItems() {
        xstream.registerLocalConverter(BasicCatalogItemMemento.class, "libraries", new CatalogItemLibrariesConverter());
    }
    
    // Warning: this is called in the super-class constructor, so before this constructor -
    // most fields will not be set, including "xstream" (use a supplier if you need to)
    // See comment on superclass method.
    @Override
    protected MapperWrapper wrapMapperForNormalUsage(Mapper next) {
        MapperWrapper mapper = super.wrapMapperForNormalUsage(next);
        mapper = new CustomMapper(mapper, Entity.class, "entityProxy");
        mapper = new CustomMapper(mapper, Location.class, "locationProxy");

        mapper = new CustomMapper(mapper, Policy.class, "policyRef");
        mapper = new CustomMapper(mapper, Enricher.class, "enricherRef");
        mapper = new CustomMapper(mapper, Feed.class, "feedRef");

        mapper = new UnwantedStateLoggingMapper(mapper);
        return mapper;
    }

    @Override
    public void serialize(Object object, Writer writer) {
        super.serialize(object, writer);
        try {
            writer.append("\n");
        } catch (IOException e) {
            throw Exceptions.propagate(e);
        }
    }

    @Override
    public void setLookupContext(LookupContext lookupContext) {
        this.lookupContext = checkNotNull(lookupContext, "lookupContext");
        delegatingClassLoader.setManagementContext(lookupContext.lookupManagementContext());
    }

    @Override
    public void unsetLookupContext() {
        this.lookupContext = null;
    }
    
    @SuppressWarnings("deprecation")
    protected String getContextDescription(Object contextHinter) {
        List<String> entries = MutableList.of();
        
        entries.add("in");
        entries.add(lookupContext.getContextDescription());
        
        if (contextHinter instanceof ReferencingMarshallingContext)
            entries.add("at "+((ReferencingMarshallingContext)contextHinter).currentPath());
        else if (contextHinter instanceof ReferenceByXPathUnmarshaller) {
            try {
                Method m = ReferenceByXPathUnmarshaller.class.getDeclaredMethod("getCurrentReferenceKey");
                m.setAccessible(true);
                entries.add("at "+m.invoke(contextHinter));
            } catch (Exception e) {
                Exceptions.propagateIfFatal(e);
                // ignore otherwise - we just won't have the position in the file
            }
        }
        
        return Strings.join(entries, " ");
    }

    /**
     * For changing the tag used for anything that implements/extends the given type.
     * Necessary for using EntityRef rather than the default "dynamic-proxy" tag.
     * 
     * @author aled
     */
    public static class CustomMapper extends MapperWrapper {
        private final Class<?> clazz;
        private final String alias;

        public CustomMapper(Mapper wrapped, Class<?> clazz, String alias) {
            super(wrapped);
            this.clazz = checkNotNull(clazz, "clazz");
            this.alias = checkNotNull(alias, "alias");
        }

        public String getAlias() {
            return alias;
        }

        @Override
        public String serializedClass(@SuppressWarnings("rawtypes") Class type) {
            if (type != null && clazz.isAssignableFrom(type)) {
                return alias;
            } else {
                return super.serializedClass(type);
            }
        }

        @Override
        public Class<?> realClass(String elementName) {
            if (elementName.equals(alias)) {
                return clazz;
            } else {
                return super.realClass(elementName);
            }
        }
    }

    public abstract class IdentifiableConverter<IT extends Identifiable> implements SingleValueConverter {
        private final Class<IT> clazz;
        
        IdentifiableConverter(Class<IT> clazz) {
            this.clazz = clazz;
        }
        @Override
        public boolean canConvert(@SuppressWarnings("rawtypes") Class type) {
            boolean result = clazz.isAssignableFrom(type);
            return result;
        }

        @Override
        public String toString(Object obj) {
            return obj == null ? null : ((Identifiable)obj).getId();
        }
        @Override
        public Object fromString(String str) {
            if (lookupContext == null) {
                LOG.warn("Cannot unmarshal from persisted xml {} {}; no lookup context supplied!", clazz.getSimpleName(), str);
                return null;
            } else {
                return lookup(str);
            }
        }
        
        protected abstract IT lookup(String id);
    }

    public class LocationConverter extends IdentifiableConverter<Location> {
        LocationConverter() {
            super(Location.class);
        }
        @Override
        protected Location lookup(String id) {
            return lookupContext.lookupLocation(id);
        }
    }

    public class PolicyConverter extends IdentifiableConverter<Policy> {
        PolicyConverter() {
            super(Policy.class);
        }
        @Override
        protected Policy lookup(String id) {
            return lookupContext.lookupPolicy(id);
        }
    }

    public class EnricherConverter extends IdentifiableConverter<Enricher> {
        EnricherConverter() {
            super(Enricher.class);
        }
        @Override
        protected Enricher lookup(String id) {
            return lookupContext.lookupEnricher(id);
        }
    }
    
    public class FeedConverter extends IdentifiableConverter<Feed> {
        FeedConverter() {
            super(Feed.class);
        }
        @Override
        protected Feed lookup(String id) {
            return lookupContext.lookupFeed(id);
        }
    }

    public class EntityAdjunctConverter extends IdentifiableConverter<EntityAdjunct> {
        EntityAdjunctConverter() {
            super(EntityAdjunct.class);
        }
        @Override
        protected EntityAdjunct lookup(String id) {
            return lookupContext.lookupAnyEntityAdjunct(id);
        }
    }
    
    public class EntityConverter extends IdentifiableConverter<Entity> {
        EntityConverter() {
            super(Entity.class);
        }
        @Override
        protected Entity lookup(String id) {
            return lookupContext.lookupEntity(id);
        }
    }

    @SuppressWarnings("rawtypes")
    public class CatalogItemConverter extends IdentifiableConverter<CatalogItem> {
        CatalogItemConverter() {
            super(CatalogItem.class);
        }
        @Override
        protected CatalogItem<?,?> lookup(String id) {
            return lookupContext.lookupCatalogItem(id);
        }
    }


    static boolean loggedTaskWarning = false;
    public class TaskConverter implements Converter {
        private final Mapper mapper;
        
        TaskConverter(Mapper mapper) {
            this.mapper = mapper;
        }
        @Override
        public boolean canConvert(@SuppressWarnings("rawtypes") Class type) {
            return Task.class.isAssignableFrom(type);
        }
        @Override
        public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
            if (source == null) return;
            if (((Task<?>)source).isDone() && !((Task<?>)source).isError()) {
                try {
                    Object nextItem = ((Task<?>)source).get();
                    if (nextItem != null) {
                        context.convertAnother(nextItem);
                    }
                } catch (InterruptedException e) {
                    throw Exceptions.propagate(e);
                } catch (ExecutionException e) {
                    LOG.warn("Unexpected exception getting done (and non-error) task result for "+source+" "+
                        getContextDescription(context)+"; continuing: "+e, e);
                }
            } else {
                if (!loggedTaskWarning) {
                    // might want to know about others but it ends up being logged on every persist;
                    // solution would be a better framework for recording persistence warnings
                    // (especially if we switch to persisting yoml)
                    LOG.warn("Intercepting and skipping request to serialize a Task"
                        + getContextDescription(context) +
                        " (only logging this once): "+source);
                    loggedTaskWarning = true;
                }
                
                return;
            }
        }
        @Override
        public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
            if (reader.hasMoreChildren()) {
                Class<?> type = readClassType(reader, mapper);
                // could confirm it is subtype of context.getRequiredType()
                reader.moveDown();
                Object result = context.convertAnother(null, type);
                reader.moveUp();
                return result;
            } else {
                return null;
            }
        }
    }

    // TODO: readClassType() and readClassAttribute()
    // Temporarily copied until osgification is finished from bundle-private class
    //   com.thoughtworks.xstream.core.util.HierarchicalStreams
    // Perhaps context.getRequiredType(); can be used instead?
    // Other users of xstream (e.g. jenkinsci) manually check for resoved-to and class attributes
    //   for compatibility with older versions of xstream
    private static Class<?> readClassType(HierarchicalStreamReader reader, Mapper mapper) {
        String classAttribute = readClassAttribute(reader, mapper);
        Class<?> type;
        if (classAttribute == null) {
            type = mapper.realClass(reader.getNodeName());
        } else {
            type = mapper.realClass(classAttribute);
        }
        return type;
    }

    private static String readClassAttribute(HierarchicalStreamReader reader, Mapper mapper) {
        String attributeName = mapper.aliasForSystemAttribute("resolves-to");
        String classAttribute = attributeName == null ? null : reader.getAttribute(attributeName);
        if (classAttribute == null) {
            attributeName = mapper.aliasForSystemAttribute("class");
            if (attributeName != null) {
                classAttribute = reader.getAttribute(attributeName);
            }
        }
        return classAttribute;
    }

    public class ManagementContextConverter implements Converter {
        @Override
        public boolean canConvert(@SuppressWarnings("rawtypes") Class type) {
            return ManagementContext.class.isAssignableFrom(type);
        }
        @Override
        public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
            // write nothing, and always insert the current mgmt context
        }
        @Override
        public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
            return lookupContext.lookupManagementContext();
        }
    }

    /** When reading/writing specs, it checks whether there is a catalog item id set and uses it to load */
    public class SpecConverter extends ReflectionConverter {
        SpecConverter() {
            super(xstream.getMapper(), xstream.getReflectionProvider());
        }
        @Override
        public boolean canConvert(@SuppressWarnings("rawtypes") Class type) {
            return AbstractBrooklynObjectSpec.class.isAssignableFrom(type);
        }
        @Override
        public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
            if (source == null) return;
            AbstractBrooklynObjectSpec<?, ?> spec = (AbstractBrooklynObjectSpec<?, ?>) source;
            String catalogItemId = spec.getCatalogItemId();
            if (Strings.isNonBlank(catalogItemId)) {
                // write this field first, so we can peek at it when we read
                writer.startNode("catalogItemId");
                writer.setValue(catalogItemId);
                writer.endNode();
                
                // we're going to write the catalogItemId field twice :( but that's okay.
                // better solution would be to have mark/reset on reader so we can peek for such a field;
                // see comment below
                super.marshal(source, writer, context);
            } else {
                super.marshal(source, writer, context);
            }
        }
        @Override
        public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
            String catalogItemId = null;
            instantiateNewInstanceSettingCache(reader, context);
            
            if (reader instanceof PathTrackingReader) {
                // have to assume this is first; there is no mark/reset support on these readers
                // (if there were then it would be easier, we could just look for that child anywhere,
                // and not need a custom writer!)
                if ("catalogItemId".equals( ((PathTrackingReader)reader).peekNextChild() )) {
                    // cache the instance
                    
                    reader.moveDown();
                    catalogItemId = reader.getValue();
                    reader.moveUp();
                }
            }
            boolean customLoaderSet = false;
            try {
                if (Strings.isNonBlank(catalogItemId)) {
                    if (lookupContext==null) throw new NullPointerException("lookupContext required to load catalog item "+catalogItemId);
                    RegisteredType cat = lookupContext.lookupManagementContext().getTypeRegistry().get(catalogItemId);
                    if (cat==null) {
                        String upgradedItemId = CatalogUpgrades.getTypeUpgradedIfNecessary(lookupContext.lookupManagementContext(), catalogItemId);
                        if (!Objects.equal(catalogItemId, upgradedItemId)) {
                            LOG.warn("Upgrading spec catalog item id from "+catalogItemId+" to "+upgradedItemId+" on rebind "+getContextDescription(context));
                            cat = lookupContext.lookupManagementContext().getTypeRegistry().get(upgradedItemId);
                            catalogItemId = upgradedItemId;
                        }
                    }
                    if (cat==null) throw new NoSuchElementException("catalog item: "+catalogItemId);
                    BrooklynClassLoadingContext clcNew = CatalogUtils.newClassLoadingContext(lookupContext.lookupManagementContext(), cat);
                    delegatingClassLoader.pushClassLoadingContext(clcNew);
                    customLoaderSet = true;
                    
                    CatalogUpgrades.markerForCodeThatLoadsJavaTypesButShouldLoadRegisteredType();
                }
                
                AbstractBrooklynObjectSpec<?, ?> result = (AbstractBrooklynObjectSpec<?, ?>) super.unmarshal(reader, context);
                // we wrote it twice so this shouldn't be necessary; but if we fix it so we only write once, we'd need this
                result.catalogItemId(catalogItemId);
                return result;
            } finally {
                context.put("SpecConverter.instance", null);
                if (customLoaderSet) {
                    delegatingClassLoader.popClassLoadingContext();
                }
            }
        }

        @Override
        protected Object instantiateNewInstance(HierarchicalStreamReader reader, UnmarshallingContext context) {
            // the super calls getAttribute which requires that we have not yet done moveDown,
            // so we do this earlier and cache it for when we call super.unmarshal.
            // Store this in the UnmarshallingContext. Note that we *must not* use a field of SpecConverter,
            // because that same instance is used by everything calling XmlMementoSerializer (including multiple
            // threads).
            Object instance = context.get("SpecConverter.instance");
            if (instance==null)
                throw new IllegalStateException("Instance should be created and cached");
            return instance;
        }
        
        protected void instantiateNewInstanceSettingCache(HierarchicalStreamReader reader, UnmarshallingContext context) {
            Object instance = super.instantiateNewInstance(reader, context);
            context.put("SpecConverter.instance", instance);
        }
    }
}
