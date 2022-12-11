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
package org.apache.brooklyn.core.resolve.jackson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.google.common.annotations.Beta;
import com.google.common.reflect.TypeToken;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Predicate;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.mgmt.classloading.BrooklynClassLoadingContext;
import org.apache.brooklyn.core.catalog.internal.CatalogUtils;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.mgmt.BrooklynTaskTags;
import org.apache.brooklyn.core.mgmt.classloading.OsgiBrooklynClassLoadingContext;
import org.apache.brooklyn.core.resolve.jackson.BrooklynJacksonSerializationUtils.ConfigurableBeanDeserializerModifier;
import org.apache.brooklyn.core.resolve.jackson.BrooklynJacksonSerializationUtils.JsonDeserializerForCommonBrooklynThings;
import org.apache.brooklyn.core.typereg.RegisteredTypes;
import org.apache.brooklyn.util.core.task.DeferredSupplier;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.guava.TypeTokens;
import org.apache.brooklyn.util.javalang.Boxing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BeanWithTypeUtils {

    private static final Logger LOG = LoggerFactory.getLogger(BeanWithTypeUtils.class);

    public static final String FORMAT = "bean-with-type";

    /** also see {@link org.apache.brooklyn.util.core.json.BrooklynObjectsJsonMapper#newMapper(ManagementContext)}
     * which isn't as powerful in most ways, but has a few extra things it supports
     * TODO ideally that and this would be combined */
    public static ObjectMapper newMapper(ManagementContext mgmt, boolean allowRegisteredTypes, BrooklynClassLoadingContext loader, boolean allowPojoJavaTypes) {
        return applyCommonMapperConfig(newSimpleMapper(), mgmt, allowRegisteredTypes, loader, allowPojoJavaTypes);
    }

    public static ObjectMapper newYamlMapper(ManagementContext mgmt, boolean allowRegisteredTypes, BrooklynClassLoadingContext loader, boolean allowPojoJavaTypes) {
        ObjectMapper mapper = applyCommonMapperConfig(newSimpleYamlMapper(), mgmt, allowRegisteredTypes, loader, allowPojoJavaTypes);
        mapper = mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    public static ObjectMapper applyCommonMapperConfig(ObjectMapper mapper, ManagementContext mgmt, boolean allowRegisteredTypes, BrooklynClassLoadingContext loader, boolean allowPojoJavaTypes) {
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);

        BrooklynRegisteredTypeJacksonSerialization.apply(mapper, mgmt, allowRegisteredTypes, loader, allowPojoJavaTypes);
        WrappedValuesSerialization.apply(mapper, mgmt);
        mapper = new ConfigurableBeanDeserializerModifier()
                .addDeserializerWrapper(
                        d -> new JsonDeserializerForCommonBrooklynThings(mgmt, d)
                        // see note below, on convert()
                ).apply(mapper);
        CommonTypesSerialization.apply(mapper, mgmt);
        return mapper;
    }

    public static JsonMapper newSimpleMapper() {
        // for use with json maps (no special type resolution, even the field "type" is ignored)
        return JsonMapper.builder()
                // experimented with this, but safer for us to add annotations, and for eg guava it doesn't work due to no parameter names in the compiled code
                //.constructorDetector(ConstructorDetector.USE_DELEGATING)
                .build();
    }

    public static YAMLMapper newSimpleYamlMapper() {
        // for use with json maps (no special type resolution, even the field "type" is ignored);
        // do not split lines as that makes output harder to read
        return YAMLMapper.builder().build().enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)  //otherwise "1" becomes 1
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .disable(YAMLGenerator.Feature.SPLIT_LINES);
    }

    public static boolean isPureJson(Object o) {
        return isJsonAndOthers(o, oo -> false);
    }
    public static boolean isJsonAndOthers(Object o, Predicate<Object> acceptOthers) {
        if (o instanceof String) return true;
        if (o==null) return true;
        if (Boxing.isPrimitiveOrBoxedObject(o)) return true;
        if (o instanceof Collection) {
            for (Object oo : (Collection<?>) o) {
                if (!isJsonAndOthers(oo, acceptOthers)) return false;
            }
            return true;
        }
        if (o instanceof Map) {
            for (Map.Entry<?,?> oo : ((Map<?,?>) o).entrySet()) {
                if (!isJsonAndOthers(oo.getKey(), acceptOthers)) return false;
                if (!isJsonAndOthers(oo.getValue(), acceptOthers)) return false;
            }
            return true;
        }
        return acceptOthers.test(o);
    }
    public static boolean isJsonOrDeferredSupplier(Object o) {
        return isJsonAndOthers(o, oo -> oo instanceof DeferredSupplier);
    }

    /* a lot of consideration over where bean-with-type conversion should take place.
     * it is especially handy for config and for initializers, and sometimes for values _within_ those items.
     * currently these are done:
     * - in BrooklynComponentTemplateResolver for config, initializers etc
     * - on coercion, when accessing config
     * see esp CustomTypeConfigYamlTest.
     *
     * BrooklynComponentTemplateResolver is also responsible for parsing the DSL, which it does first,
     * and DSL objects are preserved by bean-with-type transformation --
     * see in JsonDeserializerForCommonBrooklynThings.  See DslSerializationTest.
     */


    public static <T> T convert(ManagementContext mgmt, Object mapOrListToSerializeThenDeserialize, TypeToken<T> type, boolean allowRegisteredTypes, BrooklynClassLoadingContext loader, boolean allowJavaTypes) throws JsonProcessingException {
        try {
            return convertDeeply(mgmt, mapOrListToSerializeThenDeserialize, type, allowRegisteredTypes, loader, allowJavaTypes);

        } catch (Exception e) {
            try {
                return convertShallow(mgmt, mapOrListToSerializeThenDeserialize, type, allowRegisteredTypes, loader, allowJavaTypes);
            } catch (Exception e2) {
                throw Exceptions.propagate(Arrays.asList(e, e2));
            }
        }
    }


    @Beta
    public static <T> T convertShallow(ManagementContext mgmt, Object mapOrListToSerializeThenDeserialize, TypeToken<T> type, boolean allowRegisteredTypes, BrooklynClassLoadingContext loader, boolean allowJavaTypes) throws JsonProcessingException {
        // try with complex types are saved as objects rather than serialized, but won't work if special deserialization is wanted to apply to a map inside a complex type
        ObjectMapper mapper = YAMLMapper.builder().build();
        mapper = BeanWithTypeUtils.applyCommonMapperConfig(mapper, mgmt, allowRegisteredTypes, loader, allowJavaTypes);
        mapper = new ObjectReferencingSerialization().useAndApplytoMapper(mapper);

        String serialization = mapper.writeValueAsString(mapOrListToSerializeThenDeserialize);
        return mapper.readValue(serialization, BrooklynJacksonType.asJavaType(mapper, type));
    }

    @Beta
    public static <T> T convertDeeply(ManagementContext mgmt, Object mapOrListToSerializeThenDeserialize, TypeToken<T> type, boolean allowRegisteredTypes, BrooklynClassLoadingContext loader, boolean allowJavaTypes) throws JsonProcessingException {
        // try full serialization - but won't work if things being written cannot be deserialized, eg due to unknown type
        ObjectMapper m = newMapper(mgmt, allowRegisteredTypes, loader, allowJavaTypes);
        String serialization = m.writeValueAsString(mapOrListToSerializeThenDeserialize);
        return m.readValue(serialization, BrooklynJacksonType.asJavaType(m, type));
    }

    public static <T> Maybe<T> tryConvertOrAbsentUsingContext(Maybe<Object> input, TypeToken<T> type) {
        Entity entity = BrooklynTaskTags.getContextEntity(Tasks.current());
        ManagementContext mgmt = entity != null ? ((EntityInternal) entity).getManagementContext() : null;
        BrooklynClassLoadingContext loader = entity != null ? RegisteredTypes.getClassLoadingContext(entity) : null;
        return BeanWithTypeUtils.tryConvertOrAbsent(mgmt, input, type, true, loader, true);
    }

    public static <T> Maybe<T> tryConvertOrAbsent(ManagementContext mgmt, Maybe<Object> inputMap, TypeToken<T> type, boolean allowRegisteredTypes, BrooklynClassLoadingContext loader, boolean allowJavaTypes) {
        if (inputMap.isAbsent()) return (Maybe<T>)inputMap;

        Object o = inputMap.get();
        if (!(o instanceof Map) && !(o instanceof List) && !Boxing.isPrimitiveOrBoxedObject(o)) {
            if (type.isSupertypeOf(o.getClass())) {
                return (Maybe<T>)inputMap;
            }  else {
                return Maybe.absent(() -> new RuntimeException("BeanWithType cannot convert from "+o.getClass()+" to "+type));
            }
        }

        Maybe<T> fallback = null;
        if (!BrooklynJacksonType.isRegisteredType(type)) {
            if (type.isSupertypeOf(Object.class)) {
                // the input is already valid, so use it as the fallback result
                fallback = (Maybe<T>) inputMap;

                // there isn't a 'type' key so little obvious point in converting .. might make a difference _inside_ a map or list, but we've not got any generics so it won't
                if (!(o instanceof Map) || !((Map<?, ?>) o).containsKey(BrooklynJacksonSerializationUtils.TYPE)) return fallback;
            } else if (type.isSupertypeOf(Map.class)) {
                // skip conversion for a map if it isn't an object
                return (Maybe<T>) inputMap;
            }
        }

        try {
            return Maybe.of(convert(mgmt, o, type, allowRegisteredTypes, loader, allowJavaTypes));
        } catch (Exception e) {
            if (fallback!=null) return fallback;
            return Maybe.absent("BeanWithType cannot convert given input "+o+" to "+type, e);
        }
    }

    /** Whether there appears to be an incompatibility and conversion might fix it. */
    public static <T> boolean isConversionRecommended(Maybe<Object> input, TypeToken<T> type) {
        return getPotentialConvertibilityScoreInternal(input.orNull(), type) == 1;
    }

    /** Like {@link #isConversionRecommended(Maybe, TypeToken)} but much weaker,
     * in particular if Object is expected, this will return true whereas that will return false.
     * This will return false if there are contents which are simply incompatible. */
    public static <T> boolean isConversionPlausible(Maybe<Object> input, TypeToken<T> type) {
        if (input==null || input.isAbsentOrNull() || type==null) return false;
        return getPotentialConvertibilityScoreInternal(input.get(), type) >= 0;
    }

    /** -1 if conversion _won't_ fix it. 1 if there is a problem that likely _would_ be fixed. 0 if no obvious need or problem. */
    private static <T> int getPotentialConvertibilityScoreInternal(Object t, TypeToken<T> type) {
        // if we want a primitive/string, conversion won't help (coercion would be sufficient); return 0 if type is consistent, or -1 if not
        if (t==null) {
            return 0;
        }
        if (Boxing.isPrimitiveOrBoxedClass(TypeTokens.getRawRawType(type)) || TypeTokens.equalsRaw(String.class, type)) {
            return TypeTokens.equalsRaw(t.getClass(), type) ? 0 : -1;
        }
        // if we want an object, then no need for conversion nor any problem with conversion
        if (TypeTokens.equalsRaw(Object.class, type)) return 0;

        if (TypeTokens.isAssignableFromRaw(Map.class, type)) {
            // if map is wanting, superficially we don't want to convert; but if there are generics we need to recurse
            if (t instanceof Map) {
                List<TypeToken<?>> generics = TypeTokens.getGenericArguments(type);
                if (generics!=null) {
                    for (Entry<?, ?> entry : ((Map<?, ?>) t).entrySet()) {
                        int k = getPotentialConvertibilityScoreInternal(entry.getKey(), generics.get(0));
                        if (k!=0) return k;
                        int v = getPotentialConvertibilityScoreInternal(entry.getValue(), generics.get(1));
                        if (v!=0) return v;
                    }
                }
                return 0;
            } else {
                // conversion won't make a map from a non-map
                return -1;
            }
        }

        if (TypeTokens.isAssignableFromRaw(Collection.class, type)) {
            if (t instanceof Collection) {
                List<TypeToken<?>> generics = TypeTokens.getGenericArguments(type);
                if (generics!=null) {
                    for (Object entry : ((Collection<?>) t)) {
                        int k = getPotentialConvertibilityScoreInternal(entry, generics.get(0));
                        if (k != 0) return k;
                    }
                }
                return 0;
            } else {
                return -1;
            }
        }

        // we want some special object. if we have a map or a string then conversion might sort us out.
        return (t instanceof Map || t instanceof String) ? 1 : -1;
    }
}
