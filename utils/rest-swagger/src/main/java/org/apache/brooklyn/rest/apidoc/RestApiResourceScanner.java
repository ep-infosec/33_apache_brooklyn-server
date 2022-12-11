/*
 * Copyright 2015-2021 The Apache Software Foundation.
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
package org.apache.brooklyn.rest.apidoc;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import javax.servlet.ServletConfig;
import javax.ws.rs.core.Application;

import io.swagger.annotations.Api;
import io.swagger.config.Scanner;
import io.swagger.config.ScannerFactory;
import io.swagger.config.SwaggerConfig;
import io.swagger.jaxrs.config.AbstractScanner;
import io.swagger.jaxrs.config.JaxrsScanner;
import io.swagger.jaxrs.config.SwaggerContextService;
import io.swagger.jaxrs.config.SwaggerScannerLocator;
import io.swagger.models.Info;
import io.swagger.models.Scheme;
import io.swagger.models.Swagger;
import io.swagger.models.auth.ApiKeyAuthDefinition;
import io.swagger.models.auth.BasicAuthDefinition;
import io.swagger.models.auth.In;

/**
 * Scans resources for Swagger API resources. Makes them available to the Swagger scanner.
 * Much like DefaultJaxrsScanner, but looks at annotations of ancestors as well.
 *
 * For instance, if a resource implementation exposes an annotated interface,
 * that interface will be added as well.
 *
 * Unfortunately the Swagger scanner is a static so this {@link #install(Collection)} expects static scope;
 * it works out okay as we want all the resources from different bundles to be available in the API doc page.
 * But it is a bit ugly.
 *
 * Swagger also caches things so, also unfortunately, we need to hack in to the ApiListing calls and
 * {@link #rescanIfNeeded(Runnable)}.
 */
public class RestApiResourceScanner extends AbstractScanner implements JaxrsScanner, SwaggerConfig {

    private boolean scannerDirty = true;

    private final Set<Class<?>> globalClasses = new HashSet<>();

    private final Map<Application,Set<Class<?>>> appCache = new WeakHashMap<>();

    private RestApiResourceScanner() {}

    private RestApiResourceScanner(Collection<Class<?>> resourceClasses) {
        addAnnotatedClasses(globalClasses, resourceClasses);
    }

    private void addAnnotatedClasses(Collection<Class<?>> classes) {
        addAnnotatedClasses(globalClasses, classes);
    }

    private void addAnnotatedClasses(Set<Class<?>> output, Collection<Class<?>> classes) {
        if (classes != null) {
            for (Class<?> clz : classes) {
                if (clz.getAnnotation(Api.class) != null) {
                    output.add(clz);
                }
                addAnnotatedClasses(output, Arrays.asList(clz.getInterfaces()));
            }
        }
    }

    private synchronized Set<Class<?>> buildApiClasses(Application app) {
        Set<Class<?>> cached = appCache.get(app);
        if (cached == null) {
            cached = new HashSet<>();
            if (app != null) {
                Set<Class<?>> classes = app.getClasses();
                if (classes != null) {
                    addAnnotatedClasses(cached, classes);
                }
                Set<Object> singletons = app.getSingletons();
                if (singletons != null) {
                    for (Object o : singletons) {
                        addAnnotatedClasses(cached, Arrays.<Class<?>>asList(o.getClass()));
                    }
                }
            }
            appCache.put(app, cached);
        }
        return cached;
    }

    @Override
    public Set<Class<?>> classesFromContext(Application app, ServletConfig sc) {
        HashSet<Class<?>> all = new HashSet<>(globalClasses);
        all.addAll(buildApiClasses(app));
        return all;
    }

    @Override
    public Set<Class<?>> classes() {
        return new HashSet<>();
    }

    @Override
    public Swagger configure(Swagger swagger) {
        swagger.setBasePath("/v1");
        swagger.setSchemes(Collections.singletonList(Scheme.HTTPS)); // only advertise https

        swagger.info(getSwaggerInfo());

        ApiKeyAuthDefinition security = new ApiKeyAuthDefinition();
        String apiKeyName = "JWT";
        security.setName(apiKeyName);
        security.setIn(In.HEADER);
        security.setType("apiKey");
        swagger.addSecurityDefinition(apiKeyName, security);

        BasicAuthDefinition basicAuthDefinition = new BasicAuthDefinition();
        swagger.addSecurityDefinition("Basic authentication", basicAuthDefinition);

        return swagger;
    }

    private Info getSwaggerInfo() {
        Info info = new Info();
        info.setTitle("Apache Brooklyn API");
        info.setVersion(getClass().getPackage() != null ? getClass().getPackage().getImplementationVersion() : "");
        info.setDescription("API specification for Apache Brooklyn");
        return info;
    }

    @Override
    public String getFilterClass() {
        return null;
    }

    /** install this as the scanner which Swagger will use, with the given classes also installed */
    public static synchronized void install(Collection<Class<?>> resourceClasses) {
        Scanner scanner = ScannerFactory.getScanner();
        if (scanner instanceof RestApiResourceScanner) {
            ((RestApiResourceScanner)scanner).addAnnotatedClasses(resourceClasses);
        } else {
            scanner = new RestApiResourceScanner(resourceClasses);
        }
        ScannerFactory.setScanner(scanner);
        // Above method broken in Swagger 1.6.2:
        // In Swagger 1.6.2 the method SwaggerContextService.getScanner calls to ScannerFactory.getScanner() only
        // when SwaggerScannerLocator.getInstance().getScanner(scannerIdKey) == null, but seeing its implementations,
        // it's impossible to get a null value, so we need to use SwaggerScannerLocator instead.
        SwaggerScannerLocator.getInstance().putScanner(SwaggerContextService.SCANNER_ID_DEFAULT, scanner);

        ((RestApiResourceScanner)ScannerFactory.getScanner()).scannerDirty = true;
    }

    public static void rescanIfNeeded(Runnable r) {
        if (ScannerFactory.getScanner() instanceof RestApiResourceScanner && ((RestApiResourceScanner)ScannerFactory.getScanner()).scannerDirty) {
            r.run();
            ((RestApiResourceScanner)ScannerFactory.getScanner()).scannerDirty = false;
        }
    }

}
