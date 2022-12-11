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

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.util.JsonParserSequence;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.BeanDeserializerFactory;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.DeserializerFactory;
import com.fasterxml.jackson.databind.deser.ResolvableDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.google.common.annotations.Beta;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.core.flags.TypeCoercions;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.javalang.Boxing;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BrooklynJacksonSerializationUtils {

    private static final Logger log = LoggerFactory.getLogger(BrooklynJacksonSerializationUtils.class);

    public static final String TYPE = "type";
    public static final String VALUE = "value";
    public static final String DEFAULT = "default";


    @Beta
    public static JsonDeserializer<Object> createBeanDeserializer(DeserializationContext ctxt, JavaType t) throws JsonMappingException {
        return createBeanDeserializer(ctxt, t, null, false, true);
    }

    /** Do what ctxt.findRootValueDeserializer does, except don't get special things we've registered, so we can get actual bean deserializers back,
     * e.g. as fallback impls in our custom deserializers */
    @Beta
    public static JsonDeserializer<Object> createBeanDeserializer(DeserializationContext ctxt, JavaType t, BeanDescription optionalBeanDescription,
                                                                  boolean beanFactoryBuildPossible, boolean resolve) throws JsonMappingException {
        if (optionalBeanDescription==null) optionalBeanDescription = ctxt.getConfig().introspect(t);

        DeserializerFactory f = ctxt.getFactory();
        JsonDeserializer<Object> deser;
        if (beanFactoryBuildPossible || f instanceof BeanDeserializerFactory) {
            // go directly to builder to avoid returning ones based on annotations etc
            deser = ((BeanDeserializerFactory)f).buildBeanDeserializer(ctxt, t, optionalBeanDescription);
        } else {
            deser = ctxt.getFactory().createBeanDeserializer(ctxt, t, optionalBeanDescription);
        }
        if (resolve && deser instanceof ResolvableDeserializer) {
            ((ResolvableDeserializer) deser).resolve(ctxt);
        }
        return deser;
    }

    /** per TokenBuffer.asCopyOfValue but if on a field _name_ it buffers all the key-values */
    @Beta
    public static TokenBuffer createBufferForParserCurrentObject(JsonParser parser, DeserializationContext optionalCtxtForFeatures) throws IOException {
        TokenBuffer pb = new TokenBuffer(parser, optionalCtxtForFeatures);

        while (parser.currentToken() == JsonToken.FIELD_NAME) {
            // if on a field name (in an object), we want to buffer the entire object, so take key and value and continue (the initial start_object is fine not to be restored);
            // needed for any deserializer which creates a token buffer inside an object, eg entity/selector predicate
            // see: com.fasterxml.jackson.databind.jsontype.impl.AsPropertyTypeDeserializer.deserializeTypedFromObject
            pb.copyCurrentStructure(parser);  // copies name and value
            parser.nextToken();
        }

        pb.copyCurrentStructure(parser);

        return pb;
    }

    public static JsonParser createParserFromTokenBufferAndParser(TokenBuffer tb, JsonParser p) throws IOException {
        JsonParserSequence pp = JsonParserSequence.createFlattened(false, tb.asParser(p), p);
        pp.nextToken();
        return pp;
    }

    public static Object readObject(DeserializationContext ctxt, JsonParser p) throws IOException {
        // sometimes we do this, but it fails on trailing tokens
        // p.readValueAs(Object.class)
        if (p.currentToken()==JsonToken.END_OBJECT) return new Object();
        return ctxt.findRootValueDeserializer(ctxt.constructType(Object.class)).deserialize(p, ctxt);
    }

    public static class ConfigurableBeanDeserializerModifier extends BeanDeserializerModifier {
        List<Function<JsonDeserializer<?>,JsonDeserializer<?>>> deserializerWrappers = MutableList.of();
        List<Function<Object,Object>> postConstructFunctions = MutableList.of();

        public ConfigurableBeanDeserializerModifier addDeserializerWrapper(Function<JsonDeserializer<?>,JsonDeserializer<?>> ...f) {
            for (Function<JsonDeserializer<?>,JsonDeserializer<?>> fi: f) deserializerWrappers.add(fi);
            return this;
        }

        public ConfigurableBeanDeserializerModifier addPostConstructFunction(Function<Object,Object> f) {
            postConstructFunctions.add(f);
            return this;
        }

        public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config,
                                                      BeanDescription beanDesc,
                                                      JsonDeserializer<?> deserializer) {
            for (Function<JsonDeserializer<?>,JsonDeserializer<?>> d: deserializerWrappers) {
                deserializer = d.apply(deserializer);
            }
            if (!postConstructFunctions.isEmpty()) {
                deserializer = new JsonDeserializerInvokingPostConstruct(postConstructFunctions, deserializer);
            }
            return deserializer;
        }

        public <T extends ObjectMapper> T apply(T mapper) {
            SimpleModule module = new SimpleModule();
            module.setDeserializerModifier(this);
            return (T)mapper.registerModule(module);
        }
    }

    static class JsonDeserializerInvokingPostConstruct extends JacksonBetterDelegatingDeserializer {
        final List<Function<Object,Object>> postConstructFunctions;
        public JsonDeserializerInvokingPostConstruct(List<Function<Object,Object>> postConstructFunctions, JsonDeserializer<?> deserializer) {
            super(deserializer, d -> new JsonDeserializerInvokingPostConstruct(postConstructFunctions, d));
            this.postConstructFunctions = postConstructFunctions;
        }

        @Override
        protected Object deserializeWrapper(JsonParser jp, DeserializationContext ctxt, BiFunctionThrowsIoException<JsonParser, DeserializationContext, Object> nestedDeserialize) throws IOException {
            return postConstructFunctions.stream().reduce(Function::andThen).orElse(x -> x).apply(
                    nestedDeserialize.apply(jp, ctxt) );
        }
    }

    public static class NestedLoggingDeserializer extends JacksonBetterDelegatingDeserializer {
        private final StringBuilder prefix;

        public NestedLoggingDeserializer(StringBuilder prefix, JsonDeserializer<?> deserializer) {
            super(deserializer, d -> new NestedLoggingDeserializer(prefix, d));
            this.prefix = prefix;
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> newDelegatee) {
            prefix.append(".");
            try {
                return constructor.apply(newDelegatee);
            } finally {
                prefix.setLength(prefix.length()-1);
            }
        }

        @Override
        protected Object deserializeWrapper(JsonParser jp, DeserializationContext ctxt, BiFunctionThrowsIoException<JsonParser, DeserializationContext, Object> nestedDeserialize) throws IOException {
            String v = jp.getCurrentToken()==JsonToken.VALUE_STRING ? jp.getValueAsString() : null;
            try {
                prefix.append("  ");
                log.info(prefix+"> "+jp.getCurrentToken());
                Object result = nestedDeserialize.apply(jp, ctxt);
                log.info(prefix+"< "+result);
                return result;
            } catch (Exception e) {
                log.info(prefix+"< "+e);
                throw e;
            } finally {
                prefix.setLength(prefix.length()-2);
            }
        }
    }

    interface RecontextualDeserializer {
        JsonDeserializer<?> recreateContextual();
    }

    public static class JsonDeserializerForCommonBrooklynThings extends JacksonBetterDelegatingDeserializer {
        // injected from CAMP platform; inelegant, but effective
        public static BiFunction<ManagementContext,Object,Object> BROOKLYN_PARSE_DSL_FUNCTION = null;

        private final ManagementContext mgmt;
        public JsonDeserializerForCommonBrooklynThings(ManagementContext mgmt, JsonDeserializer<?> delagatee) {
            super(delagatee, d -> new JsonDeserializerForCommonBrooklynThings(mgmt, d));
            this.mgmt = mgmt;
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> newDelegatee) {
            if (newDelegatee instanceof RecontextualDeserializer) {
                newDelegatee = ((RecontextualDeserializer)newDelegatee).recreateContextual();
            }
            return super.newDelegatingInstance(newDelegatee);
        }

        @Override
        public void resolve(DeserializationContext ctxt) throws JsonMappingException {
            try {
                super.resolve(ctxt);
            } catch (JsonMappingException e) {
                // supplying process or cause causes location to appear multiple times in message,
                // so clumsy way to maintain a good message and the JsonMappingException type
                // (though not sure we need to maintain that exception; we already lose subtypes
                // eg InvalidDefinitionException, but nothing seems to mind)
                throw (JsonMappingException) new JsonMappingException(
                        null,
                        (Strings.isBlank(e.getMessage()) ? e.toString() : e.getMessage()) +
                        (handledType()!=null ? ", processing "+handledType() : ""),
                        (JsonLocation)null).initCause(e);
            }
        }

        @Override
        protected Object deserializeWrapper(JsonParser jp, DeserializationContext ctxt, BiFunctionThrowsIoException<JsonParser, DeserializationContext, Object> nestedDeserialize) throws IOException {
            String v = jp.getCurrentToken()==JsonToken.VALUE_STRING ? jp.getValueAsString() : null;
            try {
                Object result = nestedDeserialize.apply(jp, ctxt);

                if (BROOKLYN_PARSE_DSL_FUNCTION!=null && mgmt!=null && result instanceof Map) {
                    Map<?, ?> rm = (Map<?, ?>) result;
                    if (Object.class.equals(_valueClass) || _valueClass==null) {
                        // this marker indicates that a DSL object was serialized and we need to re-parse it to deserialize it
                        Object brooklynLiteral = rm.get("$brooklyn:literal");
                        if (brooklynLiteral != null) {
                            return BROOKLYN_PARSE_DSL_FUNCTION.apply(mgmt, brooklynLiteral);
                        } else {
                            Object type = rm.get("type");
                            if (type instanceof String && ((String)type).startsWith("org.apache.brooklyn.camp.brooklyn.spi.dsl.")) {
                                // should always have a brooklyn:literal on outermost type, so that even with json pass through (used for steps etc) dsl gets preserved across convertDeeply
                                log.warn("Failed to deserialize "+result+" as DSL; will treat as map");
                            }
                        }
                    }
                }

                return result;

            } catch (Exception e) {
                // if it fails, get the raw object and attempt a coercion?; currently just for strings
                // we could do for maps but it would mean buffering every object, and it could cause recursive nightmares where the coercers tries a Jackson mapper
                if ((String.class.equals(_valueClass) || Boxing.isPrimitiveOrBoxedClass(_valueClass)) && v==null && jp.getCurrentToken()==JsonToken.END_OBJECT) {
                    // primitives declaring just their type are allowed
                    try {
                        return _valueClass.getDeclaredConstructor().newInstance();
                    } catch (Exception e2) {
                        Exceptions.propagateIfFatal(e2);
                        // ignore; use e instead
                    }
                }
                if (v!=null && handledType()!=null) {
                    // attempt type coercion
                    Maybe<?> coercion = TypeCoercions.tryCoerce(v, handledType());
                    if (coercion.isPresent()) return coercion.get();
                }
                if (e instanceof IOException) throw (IOException)e;
                throw Exceptions.propagate(e);
            }
        }
    }


}
