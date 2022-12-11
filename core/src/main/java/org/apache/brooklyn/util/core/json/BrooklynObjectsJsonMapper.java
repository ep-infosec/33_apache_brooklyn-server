/*
 * Copyright 2016 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.brooklyn.util.core.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.datatype.jsr310.JSR310Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.mgmt.classloading.BrooklynClassLoadingContext;
import org.apache.brooklyn.core.resolve.jackson.CommonTypesSerialization;
import org.apache.brooklyn.util.time.Duration;

import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Date;

public class BrooklynObjectsJsonMapper {

    private static final Logger LOG = LoggerFactory.getLogger(BrooklynObjectsJsonMapper.class);

    /** TODO {@link org.apache.brooklyn.core.resolve.jackson.BeanWithTypeUtils#newMapper(ManagementContext, boolean, BrooklynClassLoadingContext, boolean)}
     * is better in most ways; ideally that and this should be combined */
    public static ObjectMapper newMapper(ManagementContext mgmt) {
        ConfigurableSerializerProvider sp = new ConfigurableSerializerProvider();
        sp.setUnknownTypeSerializer(new ErrorAndToStringUnknownTypeSerializer());

        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializerProvider(sp);
        mapper.setVisibility(new PossiblyStrictPreferringFieldsVisibilityChecker());

        mapper.registerModule(new JavaTimeModule());
        CommonTypesSerialization.apply(mapper, mgmt);

        SimpleModule mapperModule = new SimpleModule("Brooklyn", new Version(0, 0, 0, "ignored", null, null));

        new BidiSerialization.ManagementContextSerialization(mgmt).install(mapperModule);
        new BidiSerialization.EntitySerialization(mgmt).install(mapperModule);
        new BidiSerialization.LocationSerialization(mgmt).install(mapperModule);
        new BidiSerialization.PolicySerialization(mgmt).install(mapperModule);
        new BidiSerialization.EnricherSerialization(mgmt).install(mapperModule);
        new BidiSerialization.FeedSerialization(mgmt).install(mapperModule);
        new BidiSerialization.TaskSerialization(mgmt).install(mapperModule);
        new BidiSerialization.ClassLoaderSerialization(mgmt).install(mapperModule);

        mapperModule.addSerializer(Duration.class, new DurationSerializer());
        mapperModule.addSerializer(new MultimapSerializer());

        //new CommonTypesSerialization.ByteArrayObjectStreamSerialization().apply(mapperModule);

        mapper.registerModule(mapperModule);

        return mapper;
    }

    public static ObjectMapper newDslToStringSerializingMapper(ManagementContext mgmt) {
        ObjectMapper mapper = newMapper(mgmt);

        SimpleModule module = new SimpleModule("Brooklyn-DSL-ToString");
        if (DslToStringSerialization.BROOKLYN_DSL_INTERFACE!=null) {
            new DslToStringSerialization().apply(module);
        } else {
            LOG.debug("No DSL interface defined; will not serialize DSL expressions in this instance");
        }
        mapper.registerModule(module);

        return mapper;
    }

    public static class DslToStringSerialization extends CommonTypesSerialization.ObjectAsStringSerializerAndDeserializer {
        public static Class BROOKLYN_DSL_INTERFACE = null;

        @Override
        public Class getType() {
            return BROOKLYN_DSL_INTERFACE;
        }

        @Override
        public String convertObjectToString(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            return value.toString();
        }

        @Override
        public Object convertStringToObject(String value, JsonParser p, DeserializationContext ctxt) throws IOException {
            throw new IllegalStateException("DSL deserialization not supported by this serializer");
        }
    }

}
