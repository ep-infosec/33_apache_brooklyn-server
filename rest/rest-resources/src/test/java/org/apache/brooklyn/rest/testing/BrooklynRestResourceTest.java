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
package org.apache.brooklyn.rest.testing;

import com.google.common.collect.Iterables;
import java.util.stream.Collectors;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.EntityAsserts;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.google.common.base.Predicate;
import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.rest.domain.ApplicationSpec;
import org.apache.brooklyn.rest.domain.ApplicationSummary;
import org.apache.brooklyn.rest.domain.Status;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.net.Urls;
import org.apache.brooklyn.util.repeat.Repeater;
import org.apache.brooklyn.util.time.Duration;
import org.apache.cxf.BusFactory;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.cxf.jaxrs.utils.ResourceUtils;
import org.apache.cxf.transport.http_jetty.JettyHTTPServerEngine;
import org.apache.cxf.transport.http_jetty.JettyHTTPServerEngineFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

public abstract class BrooklynRestResourceTest extends BrooklynRestApiTest {

    private static final Logger log = LoggerFactory.getLogger(BrooklynRestResourceTest.class);

    private JettyHTTPServerEngine serverEngine;
    private Server server;
    protected List<?> clientProviders;
    
    class DefaultTestApp extends javax.ws.rs.core.Application {
        @Override
        public Set<Class<?>> getClasses() {
            return resourceClasses;
        }

        @Override
        public Set<Object> getSingletons() {
            return resourceBeans;
        }

    };

    @Override
    protected void initClass() throws Exception {
        super.initClass();
        startServer();
    }

    @Override
    protected void destroyClass() throws Exception {
        stopServer();
        super.destroyClass();
    }

    protected synchronized void startServer() throws Exception {
        if (server == null) {
            setUpResources();
            
            // needed to enable session support
            serverEngine = new JettyHTTPServerEngineFactory().createJettyHTTPServerEngine(
                ENDPOINT_ADDRESS_HOST, ENDPOINT_ADDRESS_PORT, "http"); 
            serverEngine.setSessionSupport(true);
            JAXRSServerFactoryBean sf = ResourceUtils.createApplication(createRestApp(), true,false,false, BusFactory.getDefaultBus());
            if (clientProviders == null) {
                clientProviders = sf.getProviders();
            }
            configureCXF(sf);
            
            sf.setAddress(getEndpointAddress());
            sf.setFeatures(ImmutableList.of(new org.apache.cxf.feature.LoggingFeature()));
            server = sf.create();
        }
    }

    private javax.ws.rs.core.Application createRestApp() {
        return new DefaultTestApp();
    }

    /** Allows subclasses to customize the CXF server bean. */
    protected void configureCXF(JAXRSServerFactoryBean sf) {
    }

    public synchronized void stopServer() throws Exception {
        if (server != null) {
            server.stop();
            server.destroy();
            server = null;
        }
        if (serverEngine!=null) {
            serverEngine.shutdown();
            serverEngine = null;
        }
    }


    protected Response clientDeploy(ApplicationSpec spec) {
        try {
            // dropwizard TestClient won't skip deserialization of trivial things like string and byte[] and inputstream
            // if we pass in an object it serializes, so we have to serialize things ourselves
            return client().path("/applications")
                .post(Entity.entity(new ObjectMapper().writer().writeValueAsBytes(spec), MediaType.APPLICATION_OCTET_STREAM));
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }
    
    protected Application waitForApplicationToBeRunning(final URI applicationRef) {
        return waitForApplicationToBeRunning(applicationRef, Duration.minutes(3));
    }
    protected Application waitForApplicationToBeRunning(final URI applicationRef, Duration timeout) {
        if (applicationRef==null)
            throw new NullPointerException("No application URI available (consider using BrooklynRestResourceTest.clientDeploy)");
        
        boolean started = Repeater.create("Wait for application startup")
                .until(new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws Exception {
                        Status status = getApplicationStatus(applicationRef);
                        if (status == Status.ERROR) {
                            Assert.fail("Application failed with ERROR");
                        }
                        return status == Status.RUNNING;
                    }
                })
                .backoffTo(Duration.ONE_SECOND)
                .limitTimeTo(timeout)
                .run();
        
        if (!started) {
            log.warn("Did not start application "+applicationRef+" ("+getApplicationStatus(applicationRef)+"):");
            Collection<Application> apps = getManagementContext().getApplications();
            for (Application app: apps)
                Entities.dumpInfo(app);
        }
        assertTrue(started);

        Application app = Iterables.getOnlyElement(getManagementContext().getApplications().stream().filter(appI -> applicationRef.toString().contains(appI.getId())).collect(Collectors.toList()));
        EntityAsserts.assertAttributeEquals(app, Attributes.SERVICE_UP, true);
        return app;
    }

    protected Status getApplicationStatus(URI uri) {
        return client().path(uri).get(ApplicationSummary.class).getStatus();
    }

    protected Map<?, ?> getApplicationConfig(URI appUri) {
        // appUri in a format like "http://localhost:9998/applications/mwk66lso65/config/current-state"; 
        // Will call /applications/{application}/entities/{entity}/config
        String[] pathParts = appUri.getPath().split("/");
        String appId = pathParts[pathParts.length-1];
        URI configUri = URI.create(Urls.mergePaths(appUri.toString(), "/entities/", appId, "/config/current-state"));
        return client().path(configUri).get(Map.class);
    }

    protected void waitForPageFoundResponse(final String resource, final Class<?> clazz) {
        boolean found = Repeater.create("Wait for page found")
                .until(new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws Exception {
                        try {
                            client().path(resource).get(clazz);
                            return true;
                        } catch (WebApplicationException e) {
                            return false;
                        }
                    }
                })
                .every(1, TimeUnit.SECONDS)
                .limitTimeTo(30, TimeUnit.SECONDS)
                .run();
        assertTrue(found);
    }
    
    protected void waitForPageNotFoundResponse(final String resource, final Class<?> clazz) {
        boolean success = Repeater.create("Wait for page not found")
                .until(new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws Exception {
                        try {
                            client().path(resource).get(clazz);
                            return false;
                        } catch (WebApplicationException e) {
                            return e.getResponse().getStatus() == 404;
                        }
                    }
                })
                .every(1, TimeUnit.SECONDS)
                .limitTimeTo(30, TimeUnit.SECONDS)
                .run();
        assertTrue(success);
    }

    protected static Entity<byte[]> toJsonEntity(Object obj) throws IOException {
        // TODO: figure out how to have CXF actually send empty maps instead of replacing them with nulls without this workaround
        // see cxf's AbstractClient.checkIfBodyEmpty
        return Entity.entity(new ObjectMapper().writer().writeValueAsBytes(obj), MediaType.APPLICATION_JSON);
    }

    public WebClient client() {
        return WebClient.create(getEndpointAddress(), clientProviders);
    }

    // Convenience for finding a Map within a collection, based on the value of one of its keys
    protected static Predicate<? super Map<?,?>> withValueForKey(final Object key, final Object value) {
        return new Predicate<Object>() {
            @Override
            public boolean apply(Object input) {
                if (!(input instanceof Map)) return false;
                return value.equals(((Map<?, ?>) input).get(key));
            }
        };
    }
}
