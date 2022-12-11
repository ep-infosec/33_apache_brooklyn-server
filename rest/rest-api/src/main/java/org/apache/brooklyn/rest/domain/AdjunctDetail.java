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

import java.util.Map;
import java.util.Set;

import org.apache.brooklyn.api.objs.EntityAdjunct;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.collections.MutableSet;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

public class AdjunctDetail extends AdjunctSummary {

    private static final long serialVersionUID = -5086680835225136768L;

    @JsonInclude(Include.NON_EMPTY)
    private String functionallyUniqueIdentifier;
    @JsonInclude(Include.NON_EMPTY)
    private Set<Object> tags;
    @JsonInclude(Include.NON_EMPTY)
    final Set<ConfigSummary> parameters = MutableSet.of();
    final Map<String,Object> config = MutableMap.of();

    // for json
    protected AdjunctDetail() {}

    public AdjunctDetail(EntityAdjunct a) {
        super(a);
        this.functionallyUniqueIdentifier = a.getUniqueTag();
        this.tags = a.tags().getTags();
    }
    
    public String getFunctionallyUniqueIdentifier() {
        return functionallyUniqueIdentifier;
    }
    
    public Set<Object> getTags() {
        return tags;
    }
    
    public Map<String, Object> getConfig() {
        return config;
    }
    
    public Set<ConfigSummary> getParameters() {
        return parameters;
    }

    public AdjunctDetail parameter(ConfigSummary p) {
        parameters.add(p); return this;
    }

    public AdjunctDetail config(String key, Object val) {
        config.put(key, val); return this;
    }

    public AdjunctDetail config(Map<String,Object> vals) {
        config.putAll(vals); return this;
    }
    
}
