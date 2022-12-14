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
package org.apache.brooklyn.core.workflow.steps.variables;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Arrays;
import java.util.function.Consumer;

/** Deserialization bean allowing to specify a sensor or config on an entity */
public class TypedValueToSet {

    public TypedValueToSet() {}
    public TypedValueToSet(String name) {
        this.name = name;
    }
    public TypedValueToSet(TypedValueToSet other) {
        this.name = other.name;
        this.type = other.type;
    }

    public String name;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String type;


    @Deprecated // no longer used, given ShorthandProcessor
    public static TypedValueToSet parseFromShorthand(String expression, Consumer<Object> valueSetter) {
        String[] itemValue;
        String expectedForm = "[TYPE] NAME";
        if (valueSetter!=null) {
            expectedForm = expectedForm + " = VALUE";
            itemValue = expression.split("=", 2);
            if (itemValue.length != 2) {
                throw new IllegalArgumentException("Invalid shorthand '" + expression + "'; must be of the form `"+expectedForm+"`. Equals is missing.");
            }
            valueSetter.accept(itemValue[1].trim());
        } else {
            itemValue = new String[] { expression };
        }

        String[] optTypeName = itemValue[0].trim().split(" ", 3);

        TypedValueToSet result = new TypedValueToSet();
        if (optTypeName.length==1) {
            result.name = optTypeName[0];
        } else if (optTypeName.length==2) {
            result.type = optTypeName[0].trim();
            result.name = optTypeName[1].trim();
        } else {
            throw new IllegalArgumentException("Invalid shorthand '"+expression+"'; must be of the form `"+expectedForm+"` (not "+optTypeName.length+" initial arguments "+ Arrays.asList(optTypeName)+")");
        }
        return result;
    }
}