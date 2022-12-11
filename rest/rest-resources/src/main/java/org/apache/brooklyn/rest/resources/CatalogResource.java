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

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.core.catalog.internal.BasicBrooklynCatalog;
import org.apache.brooklyn.core.catalog.internal.CatalogUtils;
import org.apache.brooklyn.core.mgmt.entitlement.Entitlements;
import org.apache.brooklyn.core.mgmt.entitlement.Entitlements.StringAndArgument;
import org.apache.brooklyn.core.mgmt.ha.OsgiBundleInstallationResult;
import org.apache.brooklyn.core.mgmt.internal.ManagementContextInternal;
import org.apache.brooklyn.core.typereg.BrooklynBomYamlCatalogBundleResolver;
import org.apache.brooklyn.core.typereg.RegisteredTypePredicates;
import org.apache.brooklyn.core.typereg.RegisteredTypes;
import org.apache.brooklyn.rest.api.CatalogApi;
import org.apache.brooklyn.rest.domain.ApiError;
import org.apache.brooklyn.rest.domain.ApiError.Builder;
import org.apache.brooklyn.rest.domain.BundleInstallationRestResult;
import org.apache.brooklyn.rest.domain.CatalogEnricherSummary;
import org.apache.brooklyn.rest.domain.CatalogEntitySummary;
import org.apache.brooklyn.rest.domain.CatalogItemSummary;
import org.apache.brooklyn.rest.domain.CatalogLocationSummary;
import org.apache.brooklyn.rest.domain.CatalogPolicySummary;
import org.apache.brooklyn.rest.filter.HaHotStateRequired;
import org.apache.brooklyn.rest.transform.CatalogTransformer;
import org.apache.brooklyn.rest.transform.TypeTransformer;
import org.apache.brooklyn.rest.util.WebResourceUtils;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableSet;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.exceptions.ReferenceWithError;
import org.apache.brooklyn.util.io.FileUtil;
import org.apache.brooklyn.util.stream.InputStreamSource;
import org.apache.brooklyn.util.text.StringPredicates;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.yaml.Yamls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.Beta;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import io.swagger.annotations.ApiParam;

@HaHotStateRequired
public class CatalogResource extends AbstractBrooklynRestResource implements CatalogApi {

    private static final Logger log = LoggerFactory.getLogger(CatalogResource.class);
    private static final String LATEST = "latest";

    @Deprecated
    private Function<RegisteredType, CatalogItemSummary> toCatalogItemSummary(final UriInfo ui) {
        return new Function<RegisteredType, CatalogItemSummary>() {
            @Override
            public CatalogItemSummary apply(@Nullable RegisteredType input) {
                return CatalogTransformer.catalogItemSummary(brooklyn(), input, ui.getBaseUriBuilder());
            }
        };
    };

    private String processVersion(String version) {
        if (version != null && LATEST.equals(version.toLowerCase())) {
            version = null;
        }
        return version;
    }

    static Set<String> missingIcons = MutableSet.of();

    @Override @Deprecated @Beta
    public Response createFromUpload(byte[] item, boolean forceUpdate) {
        Throwable yamlException = null;
        try {
            MutableList.copyOf( Yamls.parseAll(new InputStreamReader(new ByteArrayInputStream(item))) );
        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            yamlException = e;
        }

        if (yamlException==null) {
            // treat as yaml if it parsed
            return createFromYaml(new String(item), forceUpdate);
        }

        return createFromArchive(item, false, forceUpdate);
    }

    @Override @Deprecated
    public Response create(String yaml, boolean forceUpdate) {
        return createFromYaml(yaml, forceUpdate);
    }

    @Override @Deprecated
    public Response createFromYaml(String yaml, boolean forceUpdate) {
        return create(yaml.getBytes(), BrooklynBomYamlCatalogBundleResolver.FORMAT, false, true, forceUpdate);
    }

    @Override @Deprecated @Beta
    public Response createFromArchive(byte[] zipInput, boolean detail, boolean forceUpdate) {
        return create(zipInput, "", detail, false, forceUpdate);
    }

    @Override
    public Response create(byte[] archive, String format, boolean detail, boolean itemDetails, boolean forceUpdate) {
        InputStreamSource source = InputStreamSource.of("REST bundle upload", archive);
        if(!BrooklynBomYamlCatalogBundleResolver.FORMAT.equals(format) && FileUtil.doesZipContainJavaBinaries(source)){
            if (!Entitlements.isEntitled(mgmt().getEntitlementManager(), Entitlements.ADD_JAVA, null)) {
                throw WebResourceUtils.forbidden("User '%s' is not authorized to add catalog item containing java classes",
                        Entitlements.getEntitlementContext().user());
            }
        }
        if (!Entitlements.isEntitled(mgmt().getEntitlementManager(), Entitlements.ADD_CATALOG_ITEM, null)) {
            throw WebResourceUtils.forbidden("User '%s' is not authorized to add catalog item",
                    Entitlements.getEntitlementContext().user());
        }

        ReferenceWithError<OsgiBundleInstallationResult> result = ((ManagementContextInternal)mgmt()).getOsgiManager().get()
                .install(source, format, forceUpdate, true);

        if (result.hasError()) {
            // (rollback already done as part of install, if necessary)
            if (log.isTraceEnabled()) {
                log.trace("Unable to create, format '"+format+"', returning 400: "+result.getError().getMessage(), result.getError());
            }
            Builder error = ApiError.builder().errorCode(Status.BAD_REQUEST);
            if (result.getWithoutError()!=null) {
                error = error.message(result.getWithoutError().getMessage())
                        .data(TypeTransformer.bundleInstallationResult(result.getWithoutError(), mgmt(), brooklyn(), ui));
            } else {
                error.message(Strings.isNonBlank(result.getError().getMessage()) ? result.getError().getMessage() : result.getError().toString());
            }
            return error.build().asJsonResponse();
        }

        BundleInstallationRestResult resultR = itemDetails ? TypeTransformer.bundleInstallationResultLegacyItemDetails(result.get(), mgmt(), brooklyn(), ui)
                : TypeTransformer.bundleInstallationResult(result.get(), mgmt(), brooklyn(), ui);
        Status status;
        switch (result.get().getCode()) {
            case IGNORING_BUNDLE_AREADY_INSTALLED:
            case IGNORING_BUNDLE_FORCIBLY_REMOVED:
                status = Status.OK;
                break;
            default:
                // already checked that it was not an error; anything else means we created it.
                status = Status.CREATED;
                break;
        }
        return Response.status(status).entity( detail ? resultR : resultR.getTypes() ).build();
    }

    @Override
    @Deprecated
    public void deleteApplication(String symbolicName, String version) throws Exception {
        deleteEntity(symbolicName, version);
    }

    @Override
    @Deprecated
    public void deleteEntity(String symbolicName, String version) throws Exception {
        if (!Entitlements.isEntitled(mgmt().getEntitlementManager(), Entitlements.MODIFY_CATALOG_ITEM, StringAndArgument.of(symbolicName+(Strings.isBlank(version) ? "" : ":"+version), "delete"))) {
            throw WebResourceUtils.forbidden("User '%s' is not authorized to modify catalog",
                Entitlements.getEntitlementContext().user());
        }

        version = processVersion(version);

        RegisteredType item = mgmt().getTypeRegistry().get(symbolicName, version);
        if (item == null) {
            throw WebResourceUtils.notFound("Entity with id '%s:%s' not found", symbolicName, version);
        } else if (!RegisteredTypePredicates.IS_ENTITY.apply(item) && !RegisteredTypePredicates.IS_APPLICATION.apply(item)) {
            throw WebResourceUtils.preconditionFailed("Item with id '%s:%s' not an entity", symbolicName, version);
        } else {
            brooklyn().getCatalog().deleteCatalogItem(item.getSymbolicName(), item.getVersion());
            ((BasicBrooklynCatalog)brooklyn().getCatalog()).uninstallEmptyWrapperBundles();
        }
    }

    @Override
    @Deprecated
    public void deletePolicy(String policyId, String version) throws Exception {
        if (!Entitlements.isEntitled(mgmt().getEntitlementManager(), Entitlements.MODIFY_CATALOG_ITEM, StringAndArgument.of(policyId+(Strings.isBlank(version) ? "" : ":"+version), "delete"))) {
            throw WebResourceUtils.forbidden("User '%s' is not authorized to modify catalog",
                Entitlements.getEntitlementContext().user());
        }

        version = processVersion(version);

        RegisteredType item = mgmt().getTypeRegistry().get(policyId, version);
        if (item == null) {
            throw WebResourceUtils.notFound("Policy with id '%s:%s' not found", policyId, version);
        } else if (!RegisteredTypePredicates.IS_POLICY.apply(item)) {
            throw WebResourceUtils.preconditionFailed("Item with id '%s:%s' not a policy", policyId, version);
        } else {
            brooklyn().getCatalog().deleteCatalogItem(item.getSymbolicName(), item.getVersion());
            ((BasicBrooklynCatalog)brooklyn().getCatalog()).uninstallEmptyWrapperBundles();
        }
    }

    @Override
    @Deprecated
    public void deleteLocation(String locationId, String version) throws Exception {
        if (!Entitlements.isEntitled(mgmt().getEntitlementManager(), Entitlements.MODIFY_CATALOG_ITEM, StringAndArgument.of(locationId+(Strings.isBlank(version) ? "" : ":"+version), "delete"))) {
            throw WebResourceUtils.forbidden("User '%s' is not authorized to modify catalog",
                Entitlements.getEntitlementContext().user());
        }

        version = processVersion(version);

        RegisteredType item = mgmt().getTypeRegistry().get(locationId, version);
        if (item == null) {
            throw WebResourceUtils.notFound("Location with id '%s:%s' not found", locationId, version);
        } else if (!RegisteredTypePredicates.IS_LOCATION.apply(item)) {
            throw WebResourceUtils.preconditionFailed("Item with id '%s:%s' not a location", locationId, version);
        } else {
            brooklyn().getCatalog().deleteCatalogItem(item.getSymbolicName(), item.getVersion());
            ((BasicBrooklynCatalog)brooklyn().getCatalog()).uninstallEmptyWrapperBundles();
        }
    }

    @Override
    @Deprecated
    public List<CatalogEntitySummary> listEntities(String regex, String fragment, boolean allVersions) {
        Predicate<RegisteredType> filter =
                Predicates.and(
                        RegisteredTypePredicates.IS_ENTITY,
                        RegisteredTypePredicates.disabled(false));
        List<CatalogItemSummary> result = getCatalogItemSummariesMatchingRegexFragment(filter, regex, fragment, allVersions);
        return castList(result, CatalogEntitySummary.class);
    }

    @Override
    @Deprecated
    public List<CatalogItemSummary> listApplications(String regex, String fragment, boolean allVersions) {
        @SuppressWarnings("unchecked")
        Predicate<RegisteredType> filter =
                Predicates.and(
                        RegisteredTypePredicates.template(true),
                        RegisteredTypePredicates.deprecated(false),
                        RegisteredTypePredicates.disabled(false));
        return getCatalogItemSummariesMatchingRegexFragment(filter, regex, fragment, allVersions);
    }

    @Override
    @Deprecated
    public CatalogEntitySummary getEntity(String symbolicName, String version) {
        if (!Entitlements.isEntitled(mgmt().getEntitlementManager(), Entitlements.SEE_CATALOG_ITEM, symbolicName+(Strings.isBlank(version)?"":":"+version))) {
            throw WebResourceUtils.forbidden("User '%s' is not authorized to see catalog entry",
                Entitlements.getEntitlementContext().user());
        }

        version = processVersion(version);

        RegisteredType result = brooklyn().getTypeRegistry().get(symbolicName, version);
        if (result==null) {
            throw WebResourceUtils.notFound("Entity with id '%s:%s' not found", symbolicName, version);
        }

        return CatalogTransformer.catalogEntitySummary(brooklyn(), result, ui.getBaseUriBuilder());
    }

    @Override
    @Deprecated
    public CatalogEntitySummary getApplication(String symbolicName, String version) {
        return getEntity(symbolicName, version);
    }

    @Override
    @Deprecated
    public List<CatalogPolicySummary> listPolicies(String regex, String fragment, boolean allVersions) {
        Predicate<RegisteredType> filter =
                Predicates.and(
                        RegisteredTypePredicates.IS_POLICY,
                        RegisteredTypePredicates.disabled(false));
        List<CatalogItemSummary> result = getCatalogItemSummariesMatchingRegexFragment(filter, regex, fragment, allVersions);
        return castList(result, CatalogPolicySummary.class);
    }

    @Override
    @Deprecated
    public CatalogPolicySummary getPolicy(String policyId, String version) throws Exception {
        if (!Entitlements.isEntitled(mgmt().getEntitlementManager(), Entitlements.SEE_CATALOG_ITEM, policyId+(Strings.isBlank(version)?"":":"+version))) {
            throw WebResourceUtils.forbidden("User '%s' is not authorized to see catalog entry",
                Entitlements.getEntitlementContext().user());
        }

        version = processVersion(version);
        RegisteredType result = brooklyn().getTypeRegistry().get(policyId, version);
        if (result==null) {
          throw WebResourceUtils.notFound("Policy with id '%s:%s' not found", policyId, version);
        }

        return CatalogTransformer.catalogPolicySummary(brooklyn(), result, ui.getBaseUriBuilder());
    }

    @Override
    @Deprecated
    public List<CatalogLocationSummary> listLocations(String regex, String fragment, boolean allVersions) {
        Predicate<RegisteredType> filter =
                Predicates.and(
                        RegisteredTypePredicates.IS_LOCATION,
                        RegisteredTypePredicates.disabled(false));
        List<CatalogItemSummary> result = getCatalogItemSummariesMatchingRegexFragment(filter, regex, fragment, allVersions);
        return castList(result, CatalogLocationSummary.class);
    }

    @Override
    @Deprecated
    public CatalogLocationSummary getLocation(String locationId, String version) throws Exception {
        if (!Entitlements.isEntitled(mgmt().getEntitlementManager(), Entitlements.SEE_CATALOG_ITEM, locationId+(Strings.isBlank(version)?"":":"+version))) {
            throw WebResourceUtils.forbidden("User '%s' is not authorized to see catalog entry",
                Entitlements.getEntitlementContext().user());
        }

        version = processVersion(version);
        RegisteredType result = brooklyn().getTypeRegistry().get(locationId, version);
        if (result==null) {
          throw WebResourceUtils.notFound("Location with id '%s:%s' not found", locationId, version);
        }

        return CatalogTransformer.catalogLocationSummary(brooklyn(), result, ui.getBaseUriBuilder());
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Deprecated
    private <T,SpecT> List<CatalogItemSummary> getCatalogItemSummariesMatchingRegexFragment(
            Predicate<RegisteredType> type, String regex, String fragment, boolean allVersions) {
        List<Predicate<RegisteredType>> filters = new ArrayList();
        filters.add(type);
        if (Strings.isNonEmpty(regex))
            filters.add(RegisteredTypePredicates.stringRepresentationMatches(StringPredicates.containsRegex(regex)));
        if (Strings.isNonEmpty(fragment))
            filters.add(RegisteredTypePredicates.stringRepresentationMatches(StringPredicates.containsLiteralIgnoreCase(fragment)));
        if (!allVersions)
            filters.add(RegisteredTypePredicates.isBestVersion(mgmt()));

        filters.add(RegisteredTypePredicates.entitledToSee(mgmt()));

        ImmutableList<RegisteredType> sortedItems =
                FluentIterable.from(brooklyn().getTypeRegistry().getMatching(Predicates.and(filters)))
                    .toSortedList(RegisteredTypes.RegisteredTypeNameThenBestFirstComparator.INSTANCE);
        return Lists.transform(sortedItems, toCatalogItemSummary(ui));
    }

    @Override
    public Response getIcon(String itemId, String version) {
        if (!Entitlements.isEntitled(mgmt().getEntitlementManager(), Entitlements.SEE_CATALOG_ITEM, itemId+(Strings.isBlank(version)?"":":"+version))) {
            throw WebResourceUtils.forbidden("User '%s' is not authorized to see catalog entry",
                Entitlements.getEntitlementContext().user());
        }

        version = processVersion(version);

        return getCatalogItemIcon(mgmt().getTypeRegistry().get(itemId, version));
    }

    @Override
    @Deprecated
    public void setDeprecated(String itemId, boolean deprecated) {
        if (!Entitlements.isEntitled(mgmt().getEntitlementManager(), Entitlements.MODIFY_CATALOG_ITEM, StringAndArgument.of(itemId, "deprecated"))) {
            throw WebResourceUtils.forbidden("User '%s' is not authorized to modify catalog",
                    Entitlements.getEntitlementContext().user());
        }
        CatalogUtils.setDeprecated(mgmt(), itemId, deprecated);
    }

    @Override
    @Deprecated
    public void setDisabled(String itemId, boolean disabled) {
        if (!Entitlements.isEntitled(mgmt().getEntitlementManager(), Entitlements.MODIFY_CATALOG_ITEM, StringAndArgument.of(itemId, "disabled"))) {
            throw WebResourceUtils.forbidden("User '%s' is not authorized to modify catalog",
                    Entitlements.getEntitlementContext().user());
        }
        CatalogUtils.setDisabled(mgmt(), itemId, disabled);
    }

    @Override
    @Deprecated
    public List<CatalogEnricherSummary> listEnrichers(@ApiParam(name = "regex", value = "Regular expression to search for") @DefaultValue("") String regex, @ApiParam(name = "fragment", value = "Substring case-insensitive to search for") @DefaultValue("") String fragment, @ApiParam(name = "allVersions", value = "Include all versions (defaults false, only returning the best version)") @DefaultValue("false") boolean includeAllVersions) {
        Predicate<RegisteredType> filter =
                Predicates.and(
                        RegisteredTypePredicates.IS_ENRICHER,
                        RegisteredTypePredicates.disabled(false));
        List<CatalogItemSummary> result = getCatalogItemSummariesMatchingRegexFragment(filter, regex, fragment, includeAllVersions);
        return castList(result, CatalogEnricherSummary.class);
    }

    @Override
    @Deprecated
    public CatalogEnricherSummary getEnricher(@ApiParam(name = "enricherId", value = "The ID of the enricher to retrieve", required = true) String enricherId, @ApiParam(name = "version", value = "The version identifier of the enricher to retrieve", required = true) String version) throws Exception {
        if (!Entitlements.isEntitled(mgmt().getEntitlementManager(), Entitlements.SEE_CATALOG_ITEM, enricherId+(Strings.isBlank(version)?"":":"+version))) {
            throw WebResourceUtils.forbidden("User '%s' is not authorized to see catalog entry",
                    Entitlements.getEntitlementContext().user());
        }
        version = processVersion(version);
        RegisteredType result = brooklyn().getTypeRegistry().get(enricherId, version);
        if (result==null) {
            throw WebResourceUtils.notFound("Enricher with id '%s:%s' not found", enricherId, version);
        }

        return CatalogTransformer.catalogEnricherSummary(brooklyn(), result, ui.getBaseUriBuilder());
    }

    @Override
    @Deprecated
    public void deleteEnricher(@ApiParam(name = "enricherId", value = "The ID of the enricher to delete", required = true) String enricherId, @ApiParam(name = "version", value = "The version identifier of the enricher to delete", required = true) String version) throws Exception {
        if (!Entitlements.isEntitled(mgmt().getEntitlementManager(), Entitlements.MODIFY_CATALOG_ITEM, StringAndArgument.of(enricherId+(Strings.isBlank(version) ? "" : ":"+version), "delete"))) {
            throw WebResourceUtils.forbidden("User '%s' is not authorized to modify catalog",
                    Entitlements.getEntitlementContext().user());
        }

        RegisteredType item = mgmt().getTypeRegistry().get(enricherId, version);
        if (item == null) {
            throw WebResourceUtils.notFound("Enricher with id '%s:%s' not found", enricherId, version);
        } else if (!RegisteredTypePredicates.IS_ENRICHER.apply(item)) {
            throw WebResourceUtils.preconditionFailed("Item with id '%s:%s' not an enricher", enricherId, version);
        } else {
            brooklyn().getCatalog().deleteCatalogItem(item.getSymbolicName(), item.getVersion());
            ((BasicBrooklynCatalog)brooklyn().getCatalog()).uninstallEmptyWrapperBundles();
        }
    }

    private Response getCatalogItemIcon(RegisteredType result) {
        String url = result.getIconUrl();
        if (url==null) {
            log.debug("No icon available for "+result+"; returning "+Status.NO_CONTENT);
            return Response.status(Status.NO_CONTENT).build();
        }

        if (brooklyn().isUrlServerSideAndSafe(url)) {
            // classpath URL's we will serve IF they end with a recognised image format;
            // paths (ie non-protocol) and
            // NB, for security, file URL's are NOT served
            log.debug("Loading and returning "+url+" as icon for "+result);

            MediaType mime = WebResourceUtils.getImageMediaTypeFromExtension(Files.getFileExtension(url));
            try {
                Object content = ResourceUtils.create(CatalogUtils.newClassLoadingContext(mgmt(), result)).getResourceFromUrl(url);
                return Response.ok(content, mime).build();
            } catch (Exception e) {
                Exceptions.propagateIfFatal(e);
                synchronized (missingIcons) {
                    if (missingIcons.add(url)) {
                        // note: this can be quite common when running from an IDE, as resources may not be copied;
                        // a mvn build should sort it out (the IDE will then find the resources, until you clean or maybe refresh...)
                        log.warn("Missing icon data for "+result.getId()+", expected at: "+url+" (subsequent messages will log debug only)");
                        log.debug("Trace for missing icon data at "+url+": "+e, e);
                    } else {
                        log.debug("Missing icon data for "+result.getId()+", expected at: "+url+" (already logged WARN and error details)");
                    }
                }
                throw WebResourceUtils.notFound("Icon unavailable for %s", result.getId());
            }
        }

        log.debug("Returning redirect to "+url+" as icon for "+result);

        // for anything else we do a redirect (e.g. http / https; perhaps ftp)
        return Response.temporaryRedirect(URI.create(url)).build();
    }

    // TODO Move to an appropriate utility class?
    @SuppressWarnings("unchecked")
    private static <T> List<T> castList(List<? super T> list, Class<T> elementType) {
        List<T> result = Lists.newArrayList();
        Iterator<? super T> li = list.iterator();
        while (li.hasNext()) {
            try {
                result.add((T) li.next());
            } catch (Throwable throwable) {
                if (throwable instanceof NoClassDefFoundError) {
                    // happens if class cannot be loaded for any reason during transformation - don't treat as fatal
                } else {
                    Exceptions.propagateIfFatal(throwable);
                }

                // item cannot be transformed; we will have logged a warning earlier
                log.debug("Ignoring invalid catalog item: "+throwable);
            }
        }
        return result;
    }
}

