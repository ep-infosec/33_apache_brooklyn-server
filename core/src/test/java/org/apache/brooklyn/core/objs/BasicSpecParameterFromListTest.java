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
package org.apache.brooklyn.core.objs;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.List;

import org.apache.brooklyn.api.mgmt.classloading.BrooklynClassLoadingContext;
import org.apache.brooklyn.api.objs.SpecParameter;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigConstraints;
import org.apache.brooklyn.core.mgmt.classloading.JavaBrooklynClassLoadingContext;
import org.apache.brooklyn.core.test.BrooklynMgmtUnitTestSupport;
import org.apache.brooklyn.util.text.StringPredicates;
import org.testng.annotations.Test;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.reflect.TypeToken;

public class BasicSpecParameterFromListTest extends BrooklynMgmtUnitTestSupport {

    @Test
    public void testInlineName() {
        String name = "minRam";
        SpecParameter<?> input = parseSpecParameterDefinition(name);
        assertEquals(input.getLabel(), name);
        assertTrue(input.isPinned());
        ConfigKey<?> type = input.getConfigKey();
        assertEquals(type.getName(), name);
        assertEquals(type.getTypeToken(), TypeToken.of(String.class));
        assertNull(type.getDefaultValue());
        assertNull(type.getDescription());
        assertTrue(type.getInheritanceByContext().values().isEmpty(), "Unexpected inheritance: "+type.getInheritanceByContext());
        assertConstraint(type.getConstraint(), Predicates.alwaysTrue());
    }

    @Test
    public void testOnlyName() {
        String name = "minRam";
        SpecParameter<?> input = parseSpecParameterDefinition(ImmutableMap.of("name", name));
        assertEquals(input.getLabel(), name);
        assertEquals(input.getConfigKey().getName(), name);
        assertEquals(input.getConfigKey().getTypeToken(), TypeToken.of(String.class));
    }

    @Test
    public void testUnusualName() {
        parseSpecParameterDefinition(ImmutableMap.of("name", "name with spaces"));
    }

    @Test
    public void testFullDefinition() {
        String name = "minRam";
        String label = "Minimum Ram";
        String description = "Some description";
        String inputType = "string";
        String defaultValue = "VALUE";
        Boolean pinned = false;
        Boolean reconfigurable = true;
        String constraint = "required";
        SpecParameter<?> input = parseSpecParameterDefinition(ImmutableMap.builder()
                .put("name", name)
                .put("label", label)
                .put("description", description)
                .put("type", inputType)
                .put("default", defaultValue)
                .put("pinned", pinned)
                .put("reconfigurable", reconfigurable)
                .put("constraints", constraint)
                .build());

        assertEquals(input.getLabel(), label);
        assertFalse(input.isPinned());

        ConfigKey<?> type = input.getConfigKey();
        assertEquals(type.getName(), name);
        assertEquals(type.getTypeToken(), TypeToken.of(String.class));
        assertEquals(type.getDefaultValue(), defaultValue);
        assertEquals(type.getDescription(), description);
        assertTrue(type.getInheritanceByContext().values().isEmpty(), "Unexpected inheritance: "+type.getInheritanceByContext());
        assertTrue(type.isReconfigurable());
        assertConstraint(type.getConstraint(), ConfigConstraints.required());
    }

    @Test
    public void testUnexpectedType() {
        String name = "1234";
        String label = "1234";
        String description = "5678.56";
        String defaultValue = "444.12";
        SpecParameter<?> input = parseSpecParameterDefinition(ImmutableMap.of(
                "name", name,
                "label", label,
                "description", description,
                "default", defaultValue));

        assertEquals(input.getLabel(), name);
        assertTrue(input.isPinned());

        ConfigKey<?> type = input.getConfigKey();
        assertEquals(type.getName(), name);
        assertEquals(type.getDefaultValue(), defaultValue);
        assertEquals(type.getDescription(), description);
        assertTrue(type.getInheritanceByContext().values().isEmpty(), "Unexpected inheritance: "+type.getInheritanceByContext());
    }

    @Test
    public void testConstraintAsArray() {
        String name = "minRam";
        String constraint = "required";
        SpecParameter<?> input = parseSpecParameterDefinition(ImmutableMap.of(
                "name", name,
                "constraints", ImmutableList.of(constraint)));
        ConfigKey<?> type = input.getConfigKey();
        assertConstraint(type.getConstraint(), ConfigConstraints.required());
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testMissingName() {
        parseSpecParameterDefinition(ImmutableMap.of(
                "type", "string"));
    }

    @Test
    public void testJavaType() {
        String name = "minRam";
        SpecParameter<?> input = parseSpecParameterDefinition(ImmutableMap.of(
                "name", name,
                "type", BasicSpecParameterFromListTest.class.getName()));
        assertEquals(input.getConfigKey().getTypeToken(), TypeToken.of(BasicSpecParameterFromListTest.class));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testInvalidType() {
        String name = "minRam";
        parseSpecParameterDefinition(ImmutableMap.of(
                "name", name,
                "type", "missing_type"));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testInvalidConstraint() {
        String name = "minRam";
        parseSpecParameterDefinition(ImmutableMap.of(
                "name", name,
                "type", "missing_type"));
    }

    @Test
    public void testDefaultPinned() {
        String name = "pinned";
        String label = "Is pinned";
        String description = "Is pinned description";
        SpecParameter<?> input = parseSpecParameterDefinition(ImmutableMap.of(
                "name", name,
                "label", label,
                "description", description));

        assertTrue(input.isPinned());
    }

    @Test
    public void testDefaultReconfigurable() {
        String name = "reconfigurable";
        String label = "Is reconfigurable";
        String description = "Is reconfigurable description";
        SpecParameter<?> input = parseSpecParameterDefinition(ImmutableMap.of(
                "name", name,
                "label", label,
                "description", description));

        ConfigKey<?> type = input.getConfigKey();
        // default is false
        assertFalse(type.isReconfigurable());
    }

    @Test
    public void testReconfigurableCoercedFromString() {
        String name = "reconfigurable coerced from string";
        SpecParameter<?> input = parseSpecParameterDefinition(ImmutableMap.of(
                "name", name,
                "reconfigurable","true"));

        ConfigKey<?> type = input.getConfigKey();
        assertTrue(type.isReconfigurable());
    }

    private SpecParameter<?> parseSpecParameterDefinition(Object def) {
        BrooklynClassLoadingContext loader = JavaBrooklynClassLoadingContext.create(mgmt);
        List<SpecParameter<?>> inputs = BasicSpecParameter.parseParameterDefinitionList(ImmutableList.of(def), null, loader);
        return Iterables.getOnlyElement(inputs);
    }

    private void assertConstraint(Predicate<?> actual, Predicate<?> expected) {
        //How to compare predicates correctly, re-creating the same predicate doesn't work
        assertEquals(actual.toString(), expected.toString());
    }

}
