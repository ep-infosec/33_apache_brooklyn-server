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
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.entity.EntityType;
import org.apache.brooklyn.api.internal.AbstractBrooklynObjectSpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.objs.EntityAdjunct;
import org.apache.brooklyn.api.objs.SpecParameter;
import org.apache.brooklyn.api.policy.Policy;
import org.apache.brooklyn.api.sensor.Enricher;
import org.apache.brooklyn.api.sensor.Feed;
import org.apache.brooklyn.api.sensor.Sensor;
import org.apache.brooklyn.api.typereg.ManagedBundle;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.camp.brooklyn.spi.creation.CampTypePlanTransformer;
import org.apache.brooklyn.core.entity.EntityDynamicType;
import org.apache.brooklyn.core.mgmt.BrooklynTags;
import org.apache.brooklyn.core.mgmt.BrooklynTags.SpecSummary;
import org.apache.brooklyn.core.mgmt.ha.OsgiBundleInstallationResult;
import org.apache.brooklyn.core.objs.BrooklynTypes;
import org.apache.brooklyn.core.typereg.RegisteredTypePredicates;
import org.apache.brooklyn.core.typereg.RegisteredTypes;
import org.apache.brooklyn.rest.api.BundleApi;
import org.apache.brooklyn.rest.api.TypeApi;
import org.apache.brooklyn.rest.domain.BundleInstallationRestResult;
import org.apache.brooklyn.rest.domain.BundleSummary;
import org.apache.brooklyn.rest.domain.ConfigSummary;
import org.apache.brooklyn.rest.domain.EffectorSummary;
import org.apache.brooklyn.rest.domain.SensorSummary;
import org.apache.brooklyn.rest.domain.SummaryComparators;
import org.apache.brooklyn.rest.domain.TypeDetail;
import org.apache.brooklyn.rest.domain.TypeSummary;
import org.apache.brooklyn.rest.util.BrooklynRestResourceUtils;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.osgi.VersionedName;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;

public class TypeTransformer {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(TypeTransformer.class);
    
    public static <T extends Entity> TypeSummary summary(BrooklynRestResourceUtils b, RegisteredType item, UriBuilder ub) {
        return embellish(new TypeSummary(item), item, false, false, b, ub);
    }

    public static TypeDetail detail(BrooklynRestResourceUtils b, RegisteredType item, UriBuilder ub) {
        return embellish(new TypeDetail(item), item, true, false, b, ub);
    }

    public static TypeDetail detailIncludingLegacyItemFields(BrooklynRestResourceUtils b, RegisteredType item, UriBuilder ub) {
        return embellish(new TypeDetail(item), item, true, true, b, ub);
    }

    private static <T extends TypeSummary> T embellish(T result, RegisteredType item, boolean detail, boolean legacyDetailFields, BrooklynRestResourceUtils b, UriBuilder ub) {
        result.setExtraField("links", makeLinks(item, ub));
        
        if (RegisteredTypes.isTemplate(item)) {
            result.setExtraField("template", true);
        }
        if (item.getIconUrl()!=null) {
            String tidiedUrl = tidyIconLink(b, item, item.getIconUrl(), ub);
            result.setIconUrl(tidiedUrl);
            if (!Objects.equals(item.getIconUrl(), tidiedUrl)) {
                result.setExtraField("iconUrlSource", item.getIconUrl());
            }
        }

        // create summary tag for the current plan
        SpecSummary currentSpec = SpecSummary.builder()
                .format(StringUtils.isBlank(item.getPlan().getPlanFormat()) ? CampTypePlanTransformer.FORMAT : item.getPlan().getPlanFormat())
                // the default type implementation is camp in this location, but hierarchy tag provides the original implementation, so it takes precedence.
                .summary((StringUtils.isBlank(item.getPlan().getPlanFormat()) ? CampTypePlanTransformer.FORMAT : item.getPlan().getPlanFormat()) + " implementation")
                .contents(item.getPlan().getPlanData())
                .build();

        List<SpecSummary> specTag = BrooklynTags.findSpecHierarchyTag(item.getTags());
        List<SpecSummary> specList = MutableList.of(currentSpec);
        if(specTag!= null){
            // put the original spec tags first
            SpecSummary.modifyHeadSpecSummary(specList, s ->
                    s.summary.startsWith(s.format) ? "Converted to "+s.summary :
                    s.summary.contains(s.format) ? s.summary + ", converted" :
                    s.summary + ", converted to "+s.format);
            SpecSummary.pushToList(specList, specTag);
        }
        result.setExtraField("specList", specList);
        
        if (detail) {
            if (RegisteredTypes.isSubtypeOf(item, Entity.class)) {
                embellishEntity(result, item, b);
            } else if (RegisteredTypes.isSubtypeOf(item, EntityAdjunct.class) ||
                    // when implied supertypes are used we won't need the code below
                    RegisteredTypes.isSubtypeOf(item, Policy.class) || RegisteredTypes.isSubtypeOf(item, Enricher.class) || RegisteredTypes.isSubtypeOf(item, Feed.class)
            ) {
                try {
                    Set<ConfigSummary> config = Sets.newLinkedHashSet();

                    AbstractBrooklynObjectSpec<?, ?> spec = b.getTypeRegistry().createSpec(item, null, null);
                    AtomicInteger priority = new AtomicInteger(0);
                    for (final SpecParameter<?> input : spec.getParameters()) {
                        config.add(ConfigTransformer.of(input).uiIncrementAndSetPriorityIfPinned(priority).transform());
                    }

                    result.setExtraField("config", config);
                } catch (Exception e) {
                    Exceptions.propagateIfFatal(e);
                    log.trace("Unable to create spec for " + item + ": " + e, e);
                }

            } else if (RegisteredTypes.isSubtypeOf(item, Location.class)) {
                // TODO include config on location specs?  (wasn't done previously so not needed, but good for completeness)
                result.setExtraField("config", Collections.emptySet());
            }
        }
        if (legacyDetailFields) {
            // for legacy compatibility
            result.setExtraField("planYaml", item.getPlan()==null ? null : item.getPlan().getPlanData());
            result.setExtraField("name", item.getDisplayName());
        }
        return result;
    }

    protected static <T extends TypeSummary> void embellishEntity(T result, RegisteredType item, BrooklynRestResourceUtils b) {
        try {
            Set<ConfigSummary> config = Sets.newLinkedHashSet();
            Set<SensorSummary> sensors = Sets.newTreeSet(SummaryComparators.nameComparator());
            Set<EffectorSummary> effectors = Sets.newTreeSet(SummaryComparators.nameComparator());
      
            EntitySpec<?> spec = b.getTypeRegistry().createSpec(item, null, EntitySpec.class);
            EntityDynamicType typeMap = BrooklynTypes.getDefinedEntityType(spec.getType());
            EntityType type = typeMap.getSnapshot();
   
            AtomicInteger priority = new AtomicInteger();
            for (SpecParameter<?> input: spec.getParameters())
                config.add(ConfigTransformer.of(input).uiIncrementAndSetPriorityIfPinned(priority).transform());
            for (Sensor<?> x: type.getSensors())
                sensors.add(SensorTransformer.sensorSummaryForCatalog(x));
            for (Effector<?> x: type.getEffectors())
                effectors.add(EffectorTransformer.effectorSummaryForCatalog(b.mgmt(), x));
            
            result.setExtraField("config", config);
            result.setExtraField("sensors", sensors);
            result.setExtraField("effectors", effectors);
        
        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);

            // templates with multiple entities can't have spec created in the manner above; just ignore
            if (item.getSuperTypes().contains(Entity.class)) {
                log.warn("Unable to create spec for "+item+": "+e, e);
            }
            if (log.isTraceEnabled()) {
                log.trace("Unable to create spec for "+item+": "+e, e);
            }
        }
    }

    public static BundleSummary bundleSummary(BrooklynRestResourceUtils brooklyn, ManagedBundle b, UriBuilder baseUriBuilder, ManagementContext mgmt, boolean detail) {
        BundleSummary result = new BundleSummary(b);
        if (detail) {
            result.setExtraField("osgiVersion", b.getOsgiVersionString());
            result.setExtraField("checksum", b.getChecksum());            
            if (b.getFormat()!=null) {
                result.setExtraField("format", b.getFormat());
            }
        }
        if (detail) {
            for (RegisteredType t: mgmt.getTypeRegistry().getMatching(RegisteredTypePredicates.containingBundle(b))) {
                result.addType(summary(brooklyn, t, baseUriBuilder));
            }
        }
        return result;
    }
    
    public static BundleSummary bundleDetails(BrooklynRestResourceUtils brooklyn, ManagedBundle b, UriBuilder baseUriBuilder, ManagementContext mgmt) {
        return bundleSummary(brooklyn, b, baseUriBuilder, mgmt, true);
    }

    public static BundleInstallationRestResult bundleInstallationResult(OsgiBundleInstallationResult in, ManagementContext mgmt, BrooklynRestResourceUtils brooklynU, UriInfo ui) {
        BundleInstallationRestResult result = new BundleInstallationRestResult(
                in.getMessage(), in.getVersionedName() != null ? in.getVersionedName().toString() : "", in.getCode());
        for (RegisteredType t: in.getTypesInstalled()) {
            TypeSummary summary = TypeTransformer.summary(brooklynU, t, ui.getBaseUriBuilder());
            result.getTypes().put(t.getId(), summary);
        }
        return result;
    }

    public static BundleInstallationRestResult bundleInstallationResultLegacyItemDetails(OsgiBundleInstallationResult in, ManagementContext mgmt, BrooklynRestResourceUtils brooklynU, UriInfo ui) {
        BundleInstallationRestResult result = new BundleInstallationRestResult(
            in.getMessage(), in.getVersionedName() != null ? in.getVersionedName().toString() : "", in.getCode());
        for (RegisteredType t: in.getTypesInstalled()) {
            TypeSummary summary = TypeTransformer.detailIncludingLegacyItemFields(brooklynU, t, ui.getBaseUriBuilder());
            result.getTypes().put(t.getId(), summary);
        }
        return result;
    }

    protected static Map<String, URI> makeLinks(RegisteredType item, UriBuilder ub) {
        return MutableMap.<String, URI>of().addIfNotNull("self", getSelfLink(item, ub));
    }
    
    private static URI getSelfLink(RegisteredType item, UriBuilder ub) {
        Maybe<VersionedName> bundleM = VersionedName.parseMaybe(item.getContainingBundle(), true);
        if (bundleM.isPresent()) {
            return serviceUriBuilder(ub, BundleApi.class, "getTypeExplicitVersion").build(bundleM.get().getSymbolicName(), bundleM.get().getVersionString(),
                item.getSymbolicName(), item.getVersion());
        } else {
            return serviceUriBuilder(ub, TypeApi.class, "detail").build(item.getSymbolicName(), item.getVersion());
        }
    }
    private static String tidyIconLink(BrooklynRestResourceUtils b, RegisteredType item, String iconUrl, UriBuilder ub) {
        if (b.isUrlServerSideAndSafe(iconUrl)) {
            Maybe<VersionedName> bundleM = VersionedName.parseMaybe(item.getContainingBundle(), true);
            if (bundleM.isAbsent()) {
                return serviceUriBuilder(ub, BundleApi.class, "getTypeExplicitVersionIcon").build(
                        bundleM.get().getSymbolicName(), bundleM.get().getVersionString(),
                        item.getSymbolicName(), item.getVersion()).toString();
            } else {
                return serviceUriBuilder(ub, TypeApi.class, "icon").build(
                        item.getSymbolicName(), item.getVersion()).toString();
            }
        }
        return iconUrl;
    }

}
