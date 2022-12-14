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
package org.apache.brooklyn.core.catalog.internal;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.brooklyn.api.catalog.CatalogItem;
import org.apache.brooklyn.api.mgmt.rebind.RebindSupport;
import org.apache.brooklyn.api.mgmt.rebind.mementos.CatalogItemMemento;
import org.apache.brooklyn.api.typereg.OsgiBundleWithUrl;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.mgmt.rebind.BasicCatalogItemRebindSupport;
import org.apache.brooklyn.core.objs.AbstractBrooklynObject;
import org.apache.brooklyn.core.relations.EmptyRelationSupport;
import org.apache.brooklyn.core.typereg.RegisteredTypeNaming;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableSet;
import org.apache.brooklyn.util.core.flags.FlagUtils;
import org.apache.brooklyn.util.core.flags.SetFromFlag;
import org.apache.brooklyn.util.http.auth.Credentials;
import org.apache.brooklyn.util.http.auth.UsernamePassword;
import org.apache.brooklyn.util.osgi.VersionedName;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

public abstract class CatalogItemDtoAbstract<T, SpecT> extends AbstractBrooklynObject implements CatalogItem<T, SpecT> {

    private static Logger LOG = LoggerFactory.getLogger(CatalogItemDtoAbstract.class);

    private @SetFromFlag String symbolicName;
    private @SetFromFlag String version = BasicBrooklynCatalog.NO_VERSION;
    private @SetFromFlag String containingBundle;

    private @SetFromFlag String displayName;
    private @SetFromFlag String description;
    private @SetFromFlag String iconUrl;

    private @SetFromFlag String javaType;
    /**@deprecated since 0.7.0, left for deserialization backwards compatibility (including xml based catalog format) */
    private @Deprecated @SetFromFlag String type;
    private @SetFromFlag String planYaml;

    private @SetFromFlag Collection<CatalogBundle> libraries;
    private @SetFromFlag Set<Object> tags = Sets.newLinkedHashSet();
    private @SetFromFlag boolean deprecated;
    private @SetFromFlag boolean disabled;

    /**
     * @throws UnsupportedOperationException; Config not supported for catalog item. See {@link #getPlanYaml()}.
     */
    @Override
    public ConfigurationSupportInternal config() {
        throw new UnsupportedOperationException();
    }
    
    /**
     * @throws UnsupportedOperationException; subscriptions are not supported for catalog items
     */
    @Override
    public SubscriptionSupportInternal subscriptions() {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public <U> U getConfig(ConfigKey<U> key) {
        return config().get(key);
    }
    
    @Override
    public String getId() {
        return getCatalogItemId();
    }

    @Override
    public String getCatalogItemId() {
        return CatalogUtils.getVersionedId(getSymbolicName(), getVersion());
    }

    @Override
    public String getJavaType() {
        if (javaType != null) return javaType;
        return type;
    }

    @Override
    public String getContainingBundle() {
        return containingBundle;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getIconUrl() {
        return iconUrl;
    }

    @Override
    public String getSymbolicName() {
        if (symbolicName != null) return symbolicName;
        return getJavaType();
    }

    @Override
    public String getVersion() {
        // The property is set to NO_VERSION when the object is initialized so it's not supposed to be null ever.
        // But xstream doesn't call constructors when reading from the catalog.xml file which results in null value
        // for the version property. That's why we have to fix it in the getter.
        if (version != null) {
            return version;
        } else {
            return BasicBrooklynCatalog.NO_VERSION;
        }
    }

    @Override
    public boolean isDeprecated() {
        return deprecated;
    }

    @Override
    public void setDeprecated(boolean deprecated) {
        this.deprecated = deprecated;
    }

    @Override
    public boolean isDisabled() {
        return disabled;
    }

    @Override
    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    @Nonnull
    @Override
    public Collection<CatalogBundle> getLibraries() {
        if (libraries != null) {
            return ImmutableList.copyOf(libraries);
        } else {
            return Collections.emptyList();
        }
    }

    @Nullable @Override
    public String getPlanYaml() {
        return planYaml;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(symbolicName, containingBundle, planYaml, javaType, nullIfEmpty(libraries), version, getCatalogItemId(),
            getCatalogItemIdSearchPath());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        CatalogItemDtoAbstract<?,?> other = (CatalogItemDtoAbstract<?,?>) obj;
        if (!Objects.equal(symbolicName, other.symbolicName)) return false;
        if (!Objects.equal(containingBundle, other.containingBundle)) return false;
        if (!Objects.equal(planYaml, other.planYaml)) return false;
        if (!Objects.equal(javaType, other.javaType)) return false;
        if (!Objects.equal(nullIfEmpty(libraries), nullIfEmpty(other.libraries))) return false;
        if (!Objects.equal(getCatalogItemId(), other.getCatalogItemId())) return false;
        if (!Objects.equal(getCatalogItemIdSearchPath(), other.getCatalogItemIdSearchPath())) return false;
        if (!Objects.equal(version, other.version)) return false;
        if (!Objects.equal(deprecated, other.deprecated)) return false;
        if (!Objects.equal(description, other.description)) return false;
        if (!Objects.equal(displayName, other.displayName)) return false;
        if (!Objects.equal(iconUrl, other.iconUrl)) return false;
        if (!Objects.equal(tags, other.tags)) return false;
        // 'type' not checked, because deprecated, 
        // and in future we might want to allow it to be removed/blanked in some impls without affecting equality
        // (in most cases it is the same as symbolicName so doesn't matter)
        return true;
    }

    private static <T> Collection<T> nullIfEmpty(Collection<T> coll) {
        if (coll==null || coll.isEmpty()) return null;
        return coll;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()+"["+getId()+"/"+getDisplayName()+"]";
    }

    @Override
    public abstract Class<SpecT> getSpecType();

    @Override
    public RebindSupport<CatalogItemMemento> getRebindSupport() {
        return new BasicCatalogItemRebindSupport(this);
    }

    /**
     * Overrides the parent so that relations are not visible.
     * @return an immutable empty relation support object; relations are not supported,
     * but we do not throw on access to enable reads in a consistent manner
     */
    @Override
    public RelationSupportInternal<CatalogItem<T,SpecT>> relations() {
        return new EmptyRelationSupport<CatalogItem<T,SpecT>>(this);
    }

    @Override
    public void setDisplayName(String newName) {
        this.displayName = newName;
    }

    @Override
    protected CatalogItemDtoAbstract<T, SpecT> configure(Map<?, ?> flags) {
        FlagUtils.setFieldsFromFlags(flags, this);
        return this;
    }

    @Override
    public TagSupport tags() {
        return new BasicTagSupport();
    }

    /*
     * Using a custom tag support class rather than the one in AbstractBrooklynObject because
     * when XStream unmarshals a catalog item with no tags (e.g. from any catalog.xml file)
     * super.tags will be null, and any call to getTags throws a NullPointerException on the
     * synchronized (tags) statement. It can't just be initialised here because super.tags is
     * final.
     */
    private class BasicTagSupport implements TagSupport {

        private void setTagsIfNull() {
            // Possible if the class was unmarshalled by Xstream with no tags
            synchronized (CatalogItemDtoAbstract.this) {
                if (tags == null) {
                    tags = Sets.newLinkedHashSet();
                }
            }
        }

        @Nonnull
        @Override
        public Set<Object> getTags() {
            synchronized (CatalogItemDtoAbstract.this) {
                setTagsIfNull();
                return ImmutableSet.copyOf(tags);
            }
        }

        @Override
        public boolean containsTag(Object tag) {
            synchronized (CatalogItemDtoAbstract.this) {
                setTagsIfNull();
                return tags.contains(tag);
            }
        }

        @Override
        public boolean addTag(Object tag) {
            boolean result;
            synchronized (CatalogItemDtoAbstract.this) {
                setTagsIfNull();
                result = tags.add(tag);
            }
            onTagsChanged();
            return result;
        }

        @Override
        public boolean addTags(Iterable<?> newTags) {
            boolean result;
            synchronized (CatalogItemDtoAbstract.this) {
                setTagsIfNull();
                result = Iterables.addAll(tags, newTags);
            }
            onTagsChanged();
            return result;
        }

        @Override
        public boolean addTagsAtStart(Iterable<?> newTags) {
            boolean result;
            synchronized (tags) {
                MutableSet<Object> oldTags = MutableSet.copyOf(tags);
                tags.clear();
                Iterables.addAll(tags, newTags);
                result = Iterables.addAll(tags, oldTags);
            }
            onTagsChanged();
            return result;
        }

        @Override
        public boolean removeTag(Object tag) {
            boolean result;
            synchronized (CatalogItemDtoAbstract.this) {
                setTagsIfNull();
                result = tags.remove(tag);
            }
            onTagsChanged();
            return result;
        }
    }

    @Override
    @Deprecated
    public void setCatalogItemId(String id) {
        //no op, should be used by rebind code only
    }

    protected void setSymbolicName(String symbolicName) {
        this.symbolicName = symbolicName;
    }

    protected void setVersion(String version) {
        this.version = version;
    }

    public void setContainingBundle(VersionedName versionedName) {
        this.containingBundle = Strings.toString(versionedName);
    }
    
    protected void setDescription(String description) {
        this.description = description;
    }

    protected void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    protected void setJavaType(String javaType) {
        this.javaType = javaType;
        this.type = null;
    }

    protected void setPlanYaml(String planYaml) {
        this.planYaml = planYaml;
    }

    protected void setLibraries(Collection<CatalogBundle> libraries) {
        this.libraries = libraries;
    }

    protected void setTags(Set<Object> tags) {
        this.tags = tags;
    }

    /**
     * Parses an instance of CatalogLibrariesDto from the given List. Expects the list entries
     * to be either Strings or Maps of String -> String or bundles. Will skip items that are not.
     * <p>
     * If a string is supplied, this tries heuristically to identify whether a reference is a bundle or a URL, as follows:
     * - if the string contains a slash, it is treated as a URL (or classpath reference), e.g. <code>/file.txt</code>;
     * - if the string is {@link RegisteredTypeNaming#isGoodBrooklynTypeColonVersion(String))} with an OSGi version it is treated as a bundle, e.g. <code>file:1</code>;
     * - if the string is ambiguous (has a single colon) a warning is given, 
     *   and typically it is treated as a URL because OSGi versions are needed here, e.g. <code>file:v1</code> is a URL,
     *   but for a transitional period (possibly ending in 0.13 as warning is introduced in 0.12) for compatibility with previous versions,
     *   versions starting with a number trigger bundle resolution, e.g. <code>file:1.txt</code> is a bundle for now
     *   (but in all these cases warnings are logged)
     * - otherwise (multiple colons, or no colons) it is treated like a URL
     */
    public static Collection<CatalogBundle> parseLibraries(Collection<?> possibleLibraries) {
        Collection<CatalogBundle> dto = MutableList.of();
        for (Object object : possibleLibraries) {
            if (object instanceof Map) {
                Map<?, ?> entry = (Map<?, ?>) object;
                String name = stringValOrNull(entry, "name");
                String version = stringValOrNull(entry, "version");
                String url = stringValOrNull(entry, "url");
                Credentials cred = null;
                if (entry.containsKey("auth") && entry.get("auth") instanceof Map) {
                    Map<?, ?> auth = (Map<?, ?>) entry.get("auth");
                    cred = new UsernamePassword(stringValOrNull(auth, "username"), stringValOrNull(auth, "password"));
                }
                dto.add(new CatalogBundleDto(name, version, url, cred));
            } else if (object instanceof String) {
                String inlineRef = (String) object;

                final String name;
                final String version;
                final String url;

                //Infer reference type (heuristically)
                if (inlineRef.contains("/") || inlineRef.contains("\\")) {
                    //looks like an url/file path (note these chars now formally disallowed in type names)
                    name = null;
                    version = null;
                    url = inlineRef;
                } else if (RegisteredTypeNaming.isGoodBrooklynTypeColonVersion(inlineRef) || RegisteredTypeNaming.isValidOsgiTypeColonVersion(inlineRef)) {
                    //looks like a name+version ref
                    name = CatalogUtils.getSymbolicNameFromVersionedId(inlineRef);
                    version = CatalogUtils.getVersionFromVersionedId(inlineRef);
                    url = null;
                } else if (CatalogUtils.looksLikeVersionedId(inlineRef)) {
                    LOG.warn("Reference to library "+inlineRef+" is being treated as type but deprecated version syntax "
                        + "means in subsequent versions it will be treated as a URL.");
                    //looks like a name+version ref
                    name = CatalogUtils.getSymbolicNameFromVersionedId(inlineRef);
                    version = CatalogUtils.getVersionFromVersionedId(inlineRef);
                    url = null;
                } else {
                    if (RegisteredTypeNaming.isUsableTypeColonVersion(inlineRef)) {
                        LOG.warn("Ambiguous library reference "+inlineRef+" is being treated as a URL even though"
                            + " it looks a bit like a bundle with a non-osgi-version.  Use strict OSGi versions to treat as a bundle. "
                            + "To suppress this message and force URL use a slash in the reference ");
                    }
                    //assume it to be relative url
                    name = null;
                    version = null;
                    url = inlineRef;
                }

                dto.add(new CatalogBundleDto(name, version, url));
            } else if (object instanceof OsgiBundleWithUrl) {
                final OsgiBundleWithUrl bwu = (OsgiBundleWithUrl) object;
                dto.add(new CatalogBundleDto(
                        bwu.getSymbolicName(),
                        bwu.getSuppliedVersionString(),
                        bwu.getUrl(),
                        bwu.getUrlCredential(),
                        bwu.getDeleteable()));
            } else if (object instanceof VersionedName) {
                dto.add(new CatalogBundleDto(((VersionedName) object).getSymbolicName(), ((VersionedName) object).getVersionString(), null));
            } else {
                LOG.debug("Unexpected entry in libraries list neither string nor map: " + object);
            }
        }
        return dto;
    }

    private static String stringValOrNull(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val != null ? String.valueOf(val) : null;
    }

}
