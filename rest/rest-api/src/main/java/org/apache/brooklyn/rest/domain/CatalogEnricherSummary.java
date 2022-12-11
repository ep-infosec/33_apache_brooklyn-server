/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.rest.domain;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.brooklyn.rest.api.TypeApi;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.ImmutableSet;

/** @deprecated since 1.0.0 new {@link TypeApi} returns {@link TypeSummary} */
@Deprecated
public class CatalogEnricherSummary extends CatalogItemSummary {

    private static final long serialVersionUID = -588856488327394445L;

    @JsonSerialize(include = JsonSerialize.Inclusion.NON_EMPTY)
    private final Set<EnricherConfigSummary> config;

    public CatalogEnricherSummary(
            @JsonProperty("symbolicName") String symbolicName,
            @JsonProperty("version") String version,
            @JsonProperty("containingBundle") String containingBundle,
            @JsonProperty("name") String name,
            @JsonProperty("javaType") String javaType,
            @JsonProperty("itemType") String itemType,
            @JsonProperty("planYaml") String planYaml,
            @JsonProperty("description") String description,
            @JsonProperty("iconUrl") String iconUrl,
            @JsonProperty("config") Set<EnricherConfigSummary> config,
            @JsonProperty("tags") Set<Object> tags,
            @JsonProperty("deprecated") boolean deprecated,
            @JsonProperty("links") Map<String, URI> links
        ) {
        super(symbolicName, version, containingBundle, name, javaType, itemType, planYaml, description, iconUrl, tags, deprecated, links);
        // TODO expose config from enrichers
        this.config = (config == null) ? ImmutableSet.<EnricherConfigSummary>of() : config;
    }

    public Set<EnricherConfigSummary> getConfig() {
        return config;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CatalogEnricherSummary)) return false;
        if (!super.equals(o)) return false;
        CatalogEnricherSummary that = (CatalogEnricherSummary) o;
        return Objects.equals(config, that.config);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), config);
    }

    @Override
    public String toString() {
        return "CatalogEnricherSummary{" +
                "config=" + config +
                '}';
    }
}
