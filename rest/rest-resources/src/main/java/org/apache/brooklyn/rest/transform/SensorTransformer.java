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

import javax.ws.rs.core.UriBuilder;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.objs.BrooklynObject;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.sensor.Sensor;
import org.apache.brooklyn.core.config.render.RendererHints;
import org.apache.brooklyn.rest.api.ApplicationApi;
import org.apache.brooklyn.rest.api.EntityApi;
import org.apache.brooklyn.rest.api.SensorApi;
import org.apache.brooklyn.rest.domain.SensorSummary;
import org.apache.brooklyn.rest.util.EntityAttributesUtils;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterables;

public class SensorTransformer {

    private static final Logger log = LoggerFactory.getLogger(SensorTransformer.class);

    public static SensorSummary sensorSummaryForCatalog(Sensor<?> sensor) {
        return new SensorSummary(sensor.getName(), sensor.getTypeName(),
                sensor.getDescription(), null);
    }

    public static SensorSummary sensorSummary(Entity entity, Sensor<?> sensor, UriBuilder ub) {
        URI applicationUri = serviceUriBuilder(ub, ApplicationApi.class, "get").build(entity.getApplicationId());
        URI entityUri = serviceUriBuilder(ub, EntityApi.class, "get").build(entity.getApplicationId(), entity.getId());
        URI selfUri = serviceUriBuilder(ub, SensorApi.class, "get").build(entity.getApplicationId(), entity.getId(), sensor.getName());

        MutableMap.Builder<String, URI> lb = MutableMap.<String, URI>builder()
                .put("self", selfUri)
                .put("application", applicationUri)
                .put("entity", entityUri)
                .put("action:json", selfUri);

        if (sensor instanceof AttributeSensor) {
            Iterable<RendererHints.NamedAction> hints = Iterables.filter(RendererHints.getHintsFor((AttributeSensor<?>)sensor), RendererHints.NamedAction.class);
            for (RendererHints.NamedAction na : hints) addNamedAction(lb, na , entity, sensor);
        }

        return new SensorSummary(sensor.getName(), sensor.getTypeName(), sensor.getDescription(), lb.build());
    }

    private static <T> void addNamedAction(MutableMap.Builder<String, URI> lb, RendererHints.NamedAction na , Entity entity, Sensor<T> sensor) {
        addNamedAction(lb, na, EntityAttributesUtils.tryGetAttribute(entity, (AttributeSensor<T>) sensor), sensor, entity);
    }
    
    @SuppressWarnings("unchecked")
    static <T> void addNamedAction(MutableMap.Builder<String, URI> lb, RendererHints.NamedAction na, T value, Object contextKeyOrSensor, BrooklynObject contextObject) {
        if (na instanceof RendererHints.NamedActionWithUrl) {
            try {
                String v = ((RendererHints.NamedActionWithUrl<T>) na).getUrlFromValue(value);
                if (Strings.isNonBlank(v)) {
                    String action = na.getActionName().toLowerCase();
                    lb.putIfAbsent("action:"+action, URI.create(v));
                }
            } catch (Exception e) {
                Exceptions.propagateIfFatal(e);
                log.warn("Unable to make action "+na+" from "+contextKeyOrSensor+" on "+contextObject+": "+e, e);
            }
        }
    }
}
