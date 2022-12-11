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
package org.apache.brooklyn.rest.domain;

import java.util.List;
import java.util.Map;

import org.apache.brooklyn.api.typereg.ManagedBundle;
import org.apache.brooklyn.api.typereg.OsgiBundleWithUrl;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.javalang.JavaClassNames;
import org.apache.brooklyn.util.text.NaturalOrderComparator;
import org.apache.brooklyn.util.text.VersionComparator;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.google.common.collect.ComparisonChain;

/** Summary info of {@link ManagedBundle} bundles in the catalog providing types,
 * essentially the symbolic name and version.
 * Extra fields listing the types may be added.
 * <p>
 * These are comparable in alpha-then-version order with most recent preferring non-snapshot versions first,
 * as per {@link VersionComparator}. */
public class BundleSummary implements Comparable<BundleSummary> {

    private final String symbolicName;
    private final String version;

    @JsonInclude(value=Include.ALWAYS)
    private final List<TypeSummary> types = MutableList.of();

    @JsonInclude(value=Include.NON_NULL)
    private final Boolean deleteable;

    // not exported directly, but used to provide other top-level json fields
    // for specific types
    @JsonIgnore
    private final Map<String,Object> others = MutableMap.of();
    
    /** for json deserialization */
    BundleSummary() {
        symbolicName = null;
        version = null;
        deleteable = null;
    }
    
    public BundleSummary(OsgiBundleWithUrl bundle) {
        symbolicName = bundle.getSymbolicName();
        version = bundle.getSuppliedVersionString();
        deleteable = bundle.getDeleteable();
    }
    
    /** Mutable map of other top-level metadata included on this DTO (eg listing config keys or effectors) */ 
    @JsonAnyGetter
    public Map<String,Object> getExtraFields() {
        return others;
    }
    @JsonAnySetter
    public void setExtraField(String name, Object value) {
        others.put(name, value);
    }
    
    public void addType(TypeSummary type) { types.add(type); }
    
    @Override
    public int compareTo(BundleSummary o2) {
        BundleSummary o1 = this;
        return ComparisonChain.start()
            .compare(o1.symbolicName, o2.symbolicName, NaturalOrderComparator.INSTANCE)
            .compare(o2.version, o1.version, VersionComparator.INSTANCE)
            .result();
    }
    
    public String getSymbolicName() {
        return symbolicName;
    }
    
    public String getVersion() {
        return version;
    }
    
    public List<TypeSummary> getTypes() {
        return types;
    }

    public Boolean getDeleteable() { return deleteable; }

    @Override
    public String toString() {
        return JavaClassNames.cleanSimpleClassName(this)+"["+symbolicName+":"+version+"]";
    }
}
