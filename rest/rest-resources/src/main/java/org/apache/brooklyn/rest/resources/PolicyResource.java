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
package org.apache.brooklyn.rest.resources;

import java.util.List;
import java.util.Map;

import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityInitializer;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.policy.Policy;
import org.apache.brooklyn.api.policy.PolicySpec;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.core.mgmt.classloading.JavaBrooklynClassLoadingContext;
import org.apache.brooklyn.core.mgmt.entitlement.Entitlements;
import org.apache.brooklyn.core.policy.Policies;
import org.apache.brooklyn.rest.api.PolicyApi;
import org.apache.brooklyn.rest.domain.PolicySummary;
import org.apache.brooklyn.rest.domain.Status;
import org.apache.brooklyn.rest.domain.SummaryComparators;
import org.apache.brooklyn.rest.filter.HaHotStateRequired;
import org.apache.brooklyn.rest.transform.ApplicationTransformer;
import org.apache.brooklyn.rest.transform.PolicyTransformer;
import org.apache.brooklyn.rest.util.WebResourceUtils;
import org.apache.brooklyn.util.core.ClassLoaderUtils;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Maps;

@HaHotStateRequired
@Deprecated
public class PolicyResource extends AbstractBrooklynRestResource implements PolicyApi {

    private static final Logger log = LoggerFactory.getLogger(PolicyResource.class);

    private @Context UriInfo ui;

    @Override
    public List<PolicySummary> list( final String application, final String entityToken ) {
        final Entity entity = brooklyn().getEntity(application, entityToken);
        return FluentIterable.from(entity.policies())
            .transform(new Function<Policy, PolicySummary>() {
                @Override
                public PolicySummary apply(Policy policy) {
                    return PolicyTransformer.policySummary(entity, policy, ui.getBaseUriBuilder());
                }
            })
            .toSortedList(SummaryComparators.nameComparator());
    }

    // TODO support parameters  ?show=value,summary&name=xxx
    // (and in sensors class)
    @Override
    public Map<String, Boolean> batchConfigRead( String application, String entityToken) {
        Entity entity = brooklyn().getEntity(application, entityToken);
        Map<String, Boolean> result = Maps.newLinkedHashMap();
        for (Policy p : entity.policies()) {
            result.put(p.getId(), !p.isSuspended());
        }
        return result;
    }

    // TODO would like to make 'config' arg optional but jersey complains if we do
    @SuppressWarnings("unchecked")
    @Override
    public PolicySummary addPolicy( String application,String entityToken, String policyTypeName,
            Map<String, String> config) {
        Entity entity = brooklyn().getEntity(application, entityToken);

        if (!Entitlements.isEntitled(mgmt().getEntitlementManager(), Entitlements.ADD_POLICY, Entitlements.StringAndArgument.of(policyTypeName, "create"))) {
            throw WebResourceUtils.forbidden("User '%s' is not authorized to add policies",
                    Entitlements.getEntitlementContext().user());
        }

        PolicySpec policySpec;
        try {
            ManagementContext managementContext = mgmt();
            Maybe<RegisteredType> maybePolicy = managementContext.getTypeRegistry()
                    .getMaybe(policyTypeName, null)
                    .map(registeredType -> managementContext.getTypeRegistry().get(policyTypeName));

            if(maybePolicy.isPresent()){
                RegisteredType registeredType = maybePolicy.get();
                policySpec = managementContext.getTypeRegistry().createSpec(registeredType, null, PolicySpec.class);
            }else{
                Class<? extends Policy> policyType;
                policyType = (Class<? extends Policy>) new ClassLoaderUtils(this, mgmt()).loadClass(policyTypeName);
                policySpec = PolicySpec.create(policyType);
            }
        } catch (ClassCastException e) {
            throw WebResourceUtils.badRequest("No policy with type %s found", policyTypeName);
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
        policySpec.configure(config);
        Policy policy = entity.policies().add(policySpec);
        log.debug("REST API added policy " + policy + " to " + entity);

        return PolicyTransformer.policySummary(entity, policy, ui.getBaseUriBuilder());
    }

    @Override
    public Status getStatus(String application, String entityToken, String policyId) {
        Policy policy = brooklyn().getPolicy(application, entityToken, policyId);
        return ApplicationTransformer.statusFromLifecycle(Policies.getPolicyStatus(policy));
    }

    @Override
    public Response start( String application, String entityToken, String policyId) {
        Policy policy = brooklyn().getPolicy(application, entityToken, policyId);

        if (!Entitlements.isEntitled(mgmt().getEntitlementManager(), Entitlements.START_POLICY, policy)) {
            throw WebResourceUtils.forbidden("User '%s' is not authorized to start policy '%s'",
                    Entitlements.getEntitlementContext().user(), policy);
        }

        policy.resume();
        return Response.status(Response.Status.NO_CONTENT).build();
    }

    @Override
    public Response stop(String application, String entityToken, String policyId) {
        Policy policy = brooklyn().getPolicy(application, entityToken, policyId);

        if (!Entitlements.isEntitled(mgmt().getEntitlementManager(), Entitlements.STOP_POLICY, policy)) {
            throw WebResourceUtils.forbidden("User '%s' is not authorized to stop the policy '%s'",
                    Entitlements.getEntitlementContext().user(),policy);
        }

        policy.suspend();
        return Response.status(Response.Status.NO_CONTENT).build();
    }

    @Override
    public Response destroy(String application, String entityToken, String policyToken) {
        Entity entity = brooklyn().getEntity(application, entityToken);
        Policy policy = brooklyn().getPolicy(entity, policyToken);

        if (!Entitlements.isEntitled(mgmt().getEntitlementManager(), Entitlements.DELETE_POLICY, policy)) {
            throw WebResourceUtils.forbidden("User '%s' is not authorized to remove policy '%s'",
                    Entitlements.getEntitlementContext().user(),policy);
        }

        policy.suspend();
        entity.policies().remove(policy);
        return Response.status(Response.Status.NO_CONTENT).build();
    }
}
