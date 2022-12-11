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
package org.apache.brooklyn.core.typereg;

import com.google.common.annotations.Beta;
import com.google.common.base.Predicate;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.internal.AbstractBrooklynObjectSpec;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.api.typereg.RegisteredTypeLoadingContext;
import org.apache.brooklyn.core.catalog.internal.BasicBrooklynCatalog;
import org.apache.brooklyn.core.config.Sanitizer;
import org.apache.brooklyn.core.mgmt.BrooklynTags;
import org.apache.brooklyn.core.mgmt.BrooklynTags.SpecSummary;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.task.DeferredSupplier;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.javalang.Boxing;
import org.apache.brooklyn.util.javalang.JavaClassNames;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Convenience supertype for {@link BrooklynTypePlanTransformer} instances.
 * <p>
 * This supplies a default {@link #scoreForType(RegisteredType, RegisteredTypeLoadingContext)}
 * method which returns 1 if the format code matches,
 * and otherwise branches to two methods {@link #scoreForNullFormat(Object, RegisteredType, RegisteredTypeLoadingContext)}
 * and {@link #scoreForNonmatchingNonnullFormat(String, Object, RegisteredType, RegisteredTypeLoadingContext)}
 * which subclasses can implement.  (Often the implementation of the latter is 0.)
 */
public abstract class AbstractTypePlanTransformer implements BrooklynTypePlanTransformer {

    private static final Logger log = LoggerFactory.getLogger(AbstractTypePlanTransformer.class);
    
    protected ManagementContext mgmt;

    @Override
    public void setManagementContext(ManagementContext mgmt) {
        this.mgmt = mgmt;
    }

    private final String format;
    private final String formatName;
    private final String formatDescription;
    
    protected AbstractTypePlanTransformer(String format, String formatName, String formatDescription) {
        this.format = format;
        this.formatName = formatName;
        this.formatDescription = formatDescription;
    }
    
    @Override
    public String getFormatCode() {
        return format;
    }

    @Override
    public String getFormatName() {
        return formatName;
    }

    @Override
    public String getFormatDescription() {
        return formatDescription;
    }

    @Override
    public String toString() {
        return getFormatCode()+":"+JavaClassNames.simpleClassName(this);
    }
    
    @Override
    public double scoreForType(RegisteredType type, RegisteredTypeLoadingContext context) {
        if (getFormatCode().equals(type.getPlan().getPlanFormat())) return 1;
        if (Strings.isBlank(type.getPlan().getPlanFormat()))
            return scoreForNullFormat(type.getPlan().getPlanData(), type, context);
        else
            return scoreForNonmatchingNonnullFormat(type.getPlan().getPlanFormat(), type.getPlan().getPlanData(), type, context);
    }

    protected abstract double scoreForNullFormat(Object planData, RegisteredType type, RegisteredTypeLoadingContext context);
    protected abstract double scoreForNonmatchingNonnullFormat(String planFormat, Object planData, RegisteredType type, RegisteredTypeLoadingContext context);

    /** delegates to more specific abstract create methods,
     * and performs common validation and customisation of the items created.
     * <p>
     * this includes:
     * <li> setting the {@link AbstractBrooklynObjectSpec#catalogItemId(String)}
     */
    @Override
    public Object create(final RegisteredType type, final RegisteredTypeLoadingContext context) {
        try {
            return tryValidate(new RegisteredTypeKindVisitor<Object>() {
                @Override protected Object visitSpec() {
                    try { 
                        AbstractBrooklynObjectSpec<?, ?> result = createSpec(type, context);
                        result.stackCatalogItemId(type.getId());
                        return result;
                    } catch (Exception e) { throw Exceptions.propagate(e); }
                }
                @Override protected Object visitBean() {
                    try { 
                        return createBean(type, context);
                    } catch (Exception e) { throw Exceptions.propagate(e); }
                }
                @Override protected Object visitUnresolved() {
                    try {
                        // don't think there are valid times when this comes here?
                        // currently should only used for "templates" which are always for specs,
                        // but callers of that shouldn't be talking to type plan transformers,
                        // they should be calling to main BBTR methods.
                        // do it and alert just in case however.
                        // TODO remove if we don't see any warnings (or when we sort out semantics for template v app v allowed-unresolved better)
                        log.debug("Request for "+this+" to validate UNRESOLVED kind "+type+"; trying as spec");
                        Object result = visitSpec();
                        log.warn("Request to use "+this+" from UNRESOLVED state succeeded treating is as a spec");
                        log.debug("Trace for request to use "+this+" in UNRESOLVED state succeeding", new Throwable("Location of request to use "+this+" in UNRESOLVED state"));
                        return result;
                    } catch (Exception e) {
                        Exceptions.propagateIfFatal(e);
                        throw new IllegalStateException(type+" is in registry but its definition cannot be resolved", e);
                    }
                }
            }.visit(type.getKind()), type, context).get();
        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            if (!(e instanceof UnsupportedTypePlanException)) {
                Supplier<String> s = () -> "Could not instantiate " + type + " (rethrowing): " + Exceptions.collapseText(e);
                if (BasicBrooklynCatalog.currentlyResolvingType.get()==null) {
                    log.debug(s.get());
                } else if (log.isTraceEnabled()) {
                    log.trace(s.get());
                }
            }
            throw Exceptions.propagate(e);
        }
    }
    
    /** Validates the object. Subclasses may do further validation based on the context. 
     * @throw UnsupportedTypePlanException if we want to quietly abandon this, any other exception to report the problem, when validation fails
     * @return the created object for fluent usage */
    protected <T> Maybe<T> tryValidate(T createdObject, RegisteredType type, RegisteredTypeLoadingContext constraint) {
        return RegisteredTypes.tryValidate(createdObject, type, constraint);
    }

    protected abstract AbstractBrooklynObjectSpec<?,?> createSpec(RegisteredType type, RegisteredTypeLoadingContext context) throws Exception;

    protected abstract Object createBean(RegisteredType type, RegisteredTypeLoadingContext context) throws Exception;

    @Beta
    @Deprecated /** @deprecated since 1.1.0 when introduced */
    protected AbstractBrooklynObjectSpec<?,?> decorateWithCommonTags(AbstractBrooklynObjectSpec<?, ?> spec, RegisteredType type,
                                                                     @Nullable String format, @Nullable String summary,
                                                                     @Nullable Function<String,String> previousSummaryModification) {
        return decorateWithCommonTagsModifyingSpecSummary(spec, type, format, summary,
                previousSummaryModification==null ? null : s -> previousSummaryModification.apply(s.summary));
    }
    @Beta
    protected AbstractBrooklynObjectSpec<?,?> decorateWithCommonTagsModifyingSpecSummary(AbstractBrooklynObjectSpec<?, ?> spec, RegisteredType type,
                                                                     @Nullable String format, @Nullable String summary,
                                                                     @Nullable Function<SpecSummary,String> previousSummaryModification) {
        if (Strings.isBlank(format)) format = getFormatCode();
        final String specSummaryText = Strings.isNonBlank(summary)
                ? summary
                : format + " plan" +
                    (Strings.isNonBlank(type.getSymbolicName())
                            ? " for type "+type.getSymbolicName()
                            : Strings.isNonBlank(type.getDisplayName())
                                ? " for "+type.getDisplayName()
                                : "");

        SpecSummary specSummary = SpecSummary.builder()
                .format(format)
                .summary(specSummaryText)
                .contents(type.getPlan().getPlanData())
                .build();

        List<SpecSummary> specTag = BrooklynTags.findSpecHierarchyTag(spec.getTags());
        if (specTag != null) {
            SpecSummary.modifyHeadSpecSummary(specTag, previousSummaryModification);
            SpecSummary.pushToList(specTag, specSummary);
        } else {
            specTag = MutableList.of(specSummary);
        }

        List<SpecSummary> sources = BrooklynTags.findSpecHierarchyTag(type.getTags());
        if (sources != null) {
            SpecSummary.modifyHeadSpecSummary(specTag, s ->
                    s.summary.startsWith(s.format) ? "Converted for catalog to "+s.summary :
                    s.summary.contains(s.format) ? s.summary + ", converted for catalog" :
                    s.summary + ", converted to "+s.format+" for catalog");
            SpecSummary.pushToList(specTag, sources);
        }
        BrooklynTags.upsertSingleKeyMapValueTag(spec, BrooklynTags.SPEC_HIERARCHY, specTag);

        if (spec instanceof EntitySpec) {
            addDepthTagsWhereMissing( ((EntitySpec<?>)spec).getChildren(), 1 );
        }

        checkSecuritySensitiveFields(spec);

        return spec;
    }

    protected void addDepthTagsWhereMissing(List<EntitySpec<?>> children, int depth) {
        children.forEach(c -> {
            Integer existingDepth = BrooklynTags.getDepthInAncestorTag(c.getTags());
            if (existingDepth==null) {
                c.tag(MutableMap.of(BrooklynTags.DEPTH_IN_ANCESTOR, depth));
                addDepthTagsWhereMissing(c.getChildren(), depth+1);
            }
        });
    }

    @Beta
    public static AbstractBrooklynObjectSpec<?,?> checkSecuritySensitiveFields(AbstractBrooklynObjectSpec<?,?> spec) {
        if (Sanitizer.isSensitiveFieldsPlaintextBlocked()) {
            // if blocking plaintext values, check them before instantiating
            Predicate<Object> predicate = Sanitizer.IS_SECRET_PREDICATE;
            spec.getConfig().forEach( (key,val) -> failOnInsecureValueForSensitiveNamedField(predicate, key.getName(), val) );
            spec.getFlags().forEach( (key,val) -> failOnInsecureValueForSensitiveNamedField(predicate, key, val) );
        }
        return spec;
    }

    public static void failOnInsecureValueForSensitiveNamedField(Predicate<Object> tokens, String key, Object val) {
        if (val==null) {
            // not set; key is irrelevant
            return;
        }

        if (!tokens.apply(key)) {
            // not a sensitive named key
            return;
        }

        if (val instanceof DeferredSupplier || val==null) {
            // allows unless phrase blocked
            failOnExtBlockedPhraseInValueForSensitiveNamedField(key, val);
            return;
        }

        // sensitive named key

        if (val instanceof String) {
            if (((String) val).startsWith("$brooklyn:")) {
                // DSL expression, allow unless phrase blocked
                failOnExtBlockedPhraseInValueForSensitiveNamedField(key, val);
                return;
            }
        }

        if (val instanceof String || Boxing.isPrimitiveOrBoxedClass(val.getClass()) || val instanceof Number) {
            // non-DSL plaintext value
            throw new IllegalStateException("Insecure value supplied for '"+key+"'; external suppliers should be used here");
        }

        // complex values allowed unless phrase blocked
        failOnExtBlockedPhraseInValueForSensitiveNamedField(key, val);
    }

    private static void failOnExtBlockedPhraseInValueForSensitiveNamedField(String key, Object value) {
        List<String> blockedPhrases = Sanitizer.getSensitiveFieldsExtBlockedPhrases();
        if (blockedPhrases==null || blockedPhrases.isEmpty()) return;
        String valS = ""+value;
        for (String blockedPhrase: blockedPhrases) {
            if (valS.contains(blockedPhrase)) {
                throw new IllegalStateException("Improperly secured value supplied for '"+key+"'; value must not contain '"+blockedPhrase+"'");
            }
        }
    }

}
