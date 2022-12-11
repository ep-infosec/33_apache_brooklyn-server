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
package org.apache.brooklyn.core.effector.http;

import com.google.common.annotations.Beta;
import com.google.common.base.Enums;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.io.ByteStreams;
import com.google.common.net.HttpHeaders;
import com.jayway.jsonpath.JsonPath;
import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.config.MapConfigKey;
import org.apache.brooklyn.core.effector.AddEffectorInitializerAbstract;
import org.apache.brooklyn.core.effector.EffectorBody;
import org.apache.brooklyn.core.effector.Effectors.EffectorBuilder;
import org.apache.brooklyn.core.entity.EntityInitializers;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.util.collections.Jsonya;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.javalang.BrooklynHttpConfig;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.http.auth.UsernamePassword;
import org.apache.brooklyn.util.http.executor.HttpExecutor;
import org.apache.brooklyn.util.http.executor.HttpRequest;
import org.apache.brooklyn.util.http.executor.HttpResponse;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Callable;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An {@link Effector} to invoke REST endpoints.
 *
 * It allows to specify the URI, the HTTP verb, credentials for authentication and HTTP headers.
 * 
 * It deals with some {@link HttpHeaders#CONTENT_TYPE} namely 'application/json' (as default) and 'application/x-www-form-urlencoded'.
 * In the latter case, a map payload will be URLEncoded in a single string
 * 
 * With optional JSON_PATH config key, the effector will extract a section of the json response. 
 * 
 * Using JSON_PATHS_AND_SENSORS, it is possible to extract one or more values from a json response, and publish them in sensors
 */
@Beta
public final class HttpCommandEffector extends AddEffectorInitializerAbstract {

    private static final Logger LOG = LoggerFactory.getLogger(HttpCommandEffector.class);

    public static final ConfigKey<String> EFFECTOR_URI = ConfigKeys.newStringConfigKey("uri");
    public static final ConfigKey<String> EFFECTOR_HTTP_VERB = ConfigKeys.newStringConfigKey("httpVerb");
    public static final ConfigKey<String> EFFECTOR_HTTP_USERNAME = ConfigKeys.newStringConfigKey("httpUsername");
    public static final ConfigKey<String> EFFECTOR_HTTP_PASSWORD = ConfigKeys.newStringConfigKey("httpPassword");
    public static final ConfigKey<Map<String, String>> EFFECTOR_HTTP_HEADERS = new MapConfigKey(String.class, "headers");
    public static final ConfigKey<Object> EFFECTOR_HTTP_PAYLOAD = ConfigKeys.newConfigKey(Object.class, "httpPayload");
    public static final ConfigKey<String> JSON_PATH = ConfigKeys.newStringConfigKey("jsonPath", "JSON path to select in HTTP response");
    public static final ConfigKey<Map<String, String>> JSON_PATHS_AND_SENSORS = new MapConfigKey(String.class, "jsonPathAndSensors", "json path selector and corresponding sensor name that will publish the json path extracted value");

    /**
     * @deprecated since 0.12.0
     */
    @Deprecated
    public static final ConfigKey<String> PUBLISH_SENSOR = ConfigKeys.newStringConfigKey("publishSensor", "Sensor name where to store json path extracted value");

    public static final String APPLICATION_JSON = "application/json";
    public static final String APPLICATION_X_WWW_FORM_URLENCODE = "application/x-www-form-urlencoded";

    private enum HttpVerb {
        GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE
    }

    public HttpCommandEffector() {}
    public HttpCommandEffector(ConfigBag params) { super(params); }

    public EffectorBuilder<String> newEffectorBuilder() {
        EffectorBuilder<String> eff = newAbstractEffectorBuilder(String.class);
        eff.impl(new Body(eff.buildAbstract(), initParams()));
        return eff;
    }

    protected static class Body extends EffectorBody<String> {
        private final Effector<?> effector;
        private final ConfigBag params;

        public Body(Effector<?> eff, final ConfigBag params) {
            this.effector = eff;
            checkNotNull(params.getAllConfigRaw().get(EFFECTOR_URI.getName()), "uri must be supplied when defining this effector");
            checkNotNull(params.getAllConfigRaw().get(EFFECTOR_HTTP_VERB.getName()), "HTTP verb must be supplied when defining this effector");
            this.params = params;
        }

        @Override
        public String call(final ConfigBag params) {
            ConfigBag allConfig = ConfigBag.newInstanceCopying(this.params).putAll(params);
            final URI uri = convertToURI(EntityInitializers.resolve(allConfig, EFFECTOR_URI));
            final String httpVerb = isValidHttpVerb(EntityInitializers.resolve(allConfig, EFFECTOR_HTTP_VERB));
            final String httpUsername = EntityInitializers.resolve(allConfig, EFFECTOR_HTTP_USERNAME);
            final String httpPassword = EntityInitializers.resolve(allConfig, EFFECTOR_HTTP_PASSWORD);
            final Map<String, String> headers = EntityInitializers.resolve(allConfig, EFFECTOR_HTTP_HEADERS);
            final Object payload = EntityInitializers.resolve(allConfig, EFFECTOR_HTTP_PAYLOAD);
            final String jsonPath = EntityInitializers.resolve(allConfig, JSON_PATH);
            final String publishSensor = EntityInitializers.resolve(allConfig, PUBLISH_SENSOR);
            final Map<String, String> pathsAndSensors = EntityInitializers.resolve(allConfig, JSON_PATHS_AND_SENSORS);
            
            if(!Strings.isEmpty(jsonPath) && !pathsAndSensors.isEmpty()) {
                throw new IllegalArgumentException("Both jsonPath and pathsAndSensors are defined, please pick just one to resolve the ambiguity");
            }
            final HttpExecutor httpExecutor = BrooklynHttpConfig.newHttpExecutor(entity());

            final HttpRequest request = buildHttpRequest(httpVerb, uri, headers, httpUsername, httpPassword, payload);
            Task t = Tasks.builder().displayName(effector.getName()).body(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try {
                        HttpResponse response = httpExecutor.execute(request);
                        validateResponse(response);
                        ByteStreams.copy(response.getContent(), out);
                        return new String(out.toByteArray());
                    } catch (IOException e) {
                        throw Exceptions.propagate(e);
                    }
                }
            }).build();

            String responseBody = (String) queue(t).getUnchecked();

            if (jsonPath != null) {
                String extractedValue = JsonPath.parse(responseBody).read(jsonPath, String.class);
                if (publishSensor != null) {
                    LOG.warn("`publishSensor` configuration key is deprecated. PLease prefer `pathsAndSensors`, instead");
                    entity().sensors().set(Sensors.newStringSensor(publishSensor), extractedValue);
                }
                return extractedValue;
            }
            
            if (!pathsAndSensors.isEmpty()) {
                for (String path : pathsAndSensors.keySet()) {
                    String jsonPathValue = JsonPath.parse(responseBody).read(path, String.class);
                    entity().sensors().set(Sensors.newStringSensor(pathsAndSensors.get(path)), jsonPathValue);
                }
            }
            // TODO responseBody or else ???
            return responseBody;
        }

        private URI convertToURI(String url) {
            try {
                return new URL(url).toURI();
            } catch (MalformedURLException e) {
                throw Exceptions.propagate(e);
            } catch (URISyntaxException e) {
                throw Exceptions.propagate(e);
            }
        }

        private void validateResponse(HttpResponse response) {
            int statusCode = response.code();
            if (statusCode == 401) {
                throw new RuntimeException("Authorization exception");
            } else if (statusCode == 404) {
                throw new RuntimeException("Resource not found");
            } else if (statusCode >= 500) {
                throw new RuntimeException("Server error");
            }
        }

        private HttpRequest buildHttpRequest(String httpVerb, URI uri, Map<String, String> headers, String httpUsername, String httpPassword, Object payload) {
            HttpRequest.Builder httpRequestBuilder = new HttpRequest.Builder()
                    .uri(uri)
                    .method(httpVerb)
                    .config(BrooklynHttpConfig.httpConfigBuilder(entity()).build());

            if (headers != null) {
                httpRequestBuilder.headers(headers);
            }

            if (payload != null) {
                String body = "";
                String contentType = headers.get(HttpHeaders.CONTENT_TYPE);
                if (contentType == null || contentType.equalsIgnoreCase(APPLICATION_JSON)) {
                    LOG.warn("Content-Type not specified. Using {}, as default (continuing)", APPLICATION_JSON);
                    body = toJsonString(payload);
                } else if (contentType.equalsIgnoreCase(APPLICATION_X_WWW_FORM_URLENCODE)) {
                    if (payload instanceof Map) {
                        for (Map.Entry<String, String> entry : ((Map<String, String>) payload).entrySet()) {
                            try {
                                if (!body.equals("")) body += "&";
                                body += URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8.toString()) + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8.toString());
                            } catch (UnsupportedEncodingException e) {
                                throw Throwables.propagate(e);
                            }

                        }
                    }
                } else if (!contentType.equalsIgnoreCase(APPLICATION_X_WWW_FORM_URLENCODE) && !contentType.equalsIgnoreCase(APPLICATION_JSON)) {
                    LOG.warn("the http request may fail with payload {} and 'Content-Type= {}, (continuing)", payload, contentType);
                    body = payload.toString();
                }
                httpRequestBuilder.body(body.getBytes());
            }

            if (httpUsername != null && httpPassword != null) {
                httpRequestBuilder.credentials(new UsernamePassword(httpUsername, httpPassword));
            }

            return httpRequestBuilder.build();
        }

        private String isValidHttpVerb(String httpVerb) {
            Optional<HttpVerb> state = Enums.getIfPresent(HttpVerb.class, httpVerb.toUpperCase());
            checkArgument(state.isPresent(), "Expected one of %s but was %s", Joiner.on(',').join(HttpVerb.values()), httpVerb);
            return httpVerb;
        }

        private String toJsonString(Object payload) {
            return Jsonya.newInstance().add(payload).toString();
        }

    }
}
