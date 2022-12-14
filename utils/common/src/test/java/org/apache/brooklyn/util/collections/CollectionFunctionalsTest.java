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
package org.apache.brooklyn.util.collections;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

public class CollectionFunctionalsTest {

    @Test
    public void testListSize() {
        Assert.assertTrue(CollectionFunctionals.sizeEquals(2).apply(ImmutableList.of("x", "y")));
        Assert.assertFalse(CollectionFunctionals.sizeEquals(2).apply(null));
        Assert.assertTrue(CollectionFunctionals.sizeEquals(0).apply(ImmutableList.of()));
        Assert.assertFalse(CollectionFunctionals.sizeEquals(0).apply(null));
    }

    @Test
    public void testMapSize() {
        Assert.assertTrue(CollectionFunctionals.<String>mapSizeEquals(2).apply(ImmutableMap.of("x", "1", "y", "2")));
        Assert.assertFalse(CollectionFunctionals.<String>mapSizeEquals(2).apply(null));
        Assert.assertTrue(CollectionFunctionals.mapSizeEquals(0).apply(ImmutableMap.of()));
        Assert.assertFalse(CollectionFunctionals.mapSizeEquals(0).apply(null));
    }

    @Test
    public void testMapSizeOfNull() {
        Assert.assertEquals(CollectionFunctionals.mapSize().apply(null), null);
        Assert.assertEquals(CollectionFunctionals.mapSize(-1).apply(null), (Integer)(-1));
    }

    @Test
    public void testListEmpty() {
        Assert.assertFalse(CollectionFunctionals.empty().apply(null));
        Assert.assertTrue(CollectionFunctionals.empty().apply(ImmutableList.of()));
        Assert.assertFalse(CollectionFunctionals.empty().apply(ImmutableList.of("x")));
    }

    @Test
    public void testListEmptyOrNull() {
        Assert.assertTrue(CollectionFunctionals.emptyOrNull().apply(null));
        Assert.assertTrue(CollectionFunctionals.emptyOrNull().apply(ImmutableList.of()));
        Assert.assertFalse(CollectionFunctionals.emptyOrNull().apply(ImmutableList.of("x")));
    }

    @Test
    public void testListNotEmpty() {
        Assert.assertFalse(CollectionFunctionals.notEmpty().apply(null));
        Assert.assertFalse(CollectionFunctionals.notEmpty().apply(ImmutableList.of()));
        Assert.assertTrue(CollectionFunctionals.notEmpty().apply(ImmutableList.of("x")));
    }

    @Test
    public void testMapEmptyOrNull() {
        Assert.assertTrue(CollectionFunctionals.mapEmptyOrNull().apply(null));
        Assert.assertTrue(CollectionFunctionals.mapEmptyOrNull().apply(ImmutableMap.of()));
        Assert.assertFalse(CollectionFunctionals.<String>mapEmptyOrNull().apply(ImmutableMap.of("mykey", "myval")));
    }

    @Test
    public void testMapNotEmpty() {
        Assert.assertEquals(CollectionFunctionals.mapNotEmpty().apply(null), false);
        Assert.assertEquals(CollectionFunctionals.mapNotEmpty().apply(ImmutableMap.of()), false);
        Assert.assertEquals(CollectionFunctionals.<String>mapNotEmpty().apply(ImmutableMap.of("mykey", "myval")), true);
    }

    @Test
    public void testFirstElement() {
        Assert.assertEquals(CollectionFunctionals.firstElement().apply(null), null);
        Assert.assertEquals(CollectionFunctionals.firstElement().apply(ImmutableList.of("a")), "a");
        Assert.assertEquals(CollectionFunctionals.firstElement().apply(ImmutableList.of("a", "b", "c")), "a");
    }

    @Test
    public void testAllAndAny() {
        Assert.assertEquals(CollectionFunctionals.all(Predicates.equalTo(1)).apply(
            MutableList.of(1, 1, 1)), true);
        Assert.assertEquals(CollectionFunctionals.all(Predicates.equalTo(1)).apply(
            MutableList.<Integer>of()), true);
        Assert.assertEquals(CollectionFunctionals.all(Predicates.equalTo(1)).apply(
            MutableList.of(1, 0, 1)), false);
        Assert.assertEquals(CollectionFunctionals.all(Predicates.equalTo(1)).apply(
            MutableList.of(0, 0, 0)), false);
        
        Assert.assertEquals(CollectionFunctionals.any(Predicates.equalTo(1)).apply(
            MutableList.of(1, 1, 1)), true);
        Assert.assertEquals(CollectionFunctionals.any(Predicates.equalTo(1)).apply(
            MutableList.<Integer>of()), false);
        Assert.assertEquals(CollectionFunctionals.any(Predicates.equalTo(1)).apply(
            MutableList.of(1, 0, 1)), true);
        Assert.assertEquals(CollectionFunctionals.any(Predicates.equalTo(1)).apply(
            MutableList.of(0, 0, 0)), false);
        
    }

    @Test
    public void testAllEquals() {
        Comparable<?> comparable = "Hello";
        Assert.assertNull(new CollectionFunctionals.AllEqualsFunction(comparable).apply(null));
        Assert.assertNull(new CollectionFunctionals.AllEqualsFunction(comparable).apply(ImmutableList.of()));
        Assert.assertTrue(new CollectionFunctionals.AllEqualsFunction(comparable).apply(ImmutableList.of("Hello")));
        Assert.assertTrue(new CollectionFunctionals.AllEqualsFunction(comparable).apply(ImmutableList.of("Hello", "Hello")));
        Assert.assertFalse(new CollectionFunctionals.AllEqualsFunction(comparable).apply(ImmutableList.of("Hello", "Bye")));
        Assert.assertFalse(new CollectionFunctionals.AllEqualsFunction(comparable).apply(ImmutableList.of("Bye")));
    }

    @Test(expectedExceptions = {IllegalArgumentException.class})
    public void testAllEquals_NullComparable() {
        Assert.assertTrue(new CollectionFunctionals.AllEqualsFunction(null).apply(ImmutableList.of("Hello", "Hello")));
    }

    @Test
    public void testAllTrue() {
        Assert.assertNull(new CollectionFunctionals.AllTrueFunction().apply(null));
        Assert.assertNull(new CollectionFunctionals.AllTrueFunction().apply(ImmutableList.of()));
        Assert.assertTrue(new CollectionFunctionals.AllTrueFunction().apply(ImmutableList.of(Boolean.TRUE)));
        Assert.assertTrue(new CollectionFunctionals.AllTrueFunction().apply(ImmutableList.of(Boolean.TRUE, Boolean.TRUE)));
        Assert.assertFalse(new CollectionFunctionals.AllTrueFunction().apply(ImmutableList.of("true", Boolean.TRUE)));
        Assert.assertFalse(new CollectionFunctionals.AllTrueFunction().apply(ImmutableList.of(Boolean.TRUE, Boolean.FALSE)));
        Assert.assertFalse(new CollectionFunctionals.AllTrueFunction().apply(ImmutableList.of(Boolean.FALSE)));
    }
}
