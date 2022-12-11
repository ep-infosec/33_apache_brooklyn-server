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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import org.apache.brooklyn.util.guava.Maybe;

import com.google.common.annotations.Beta;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

/** Does a configurable deep merge, taking the order from the first argument,
 * and configurable through the builder how to act on lists or conflicts. */
@Beta
public class CollectionMerger {
    
    public static class Builder {
        protected int depth = Integer.MAX_VALUE;
        protected boolean mergeNestedMaps = true;
        protected boolean preferSecondOnConflict = false;
        protected BiFunction<Collection<?>, Collection<?>, Collection<?>> mergeNestedLists = null;
        
        /** Sets effectively infinite {@link #depth(int)} (the default) */
        public Builder deep(boolean val) {
            return depth(val ? Integer.MAX_VALUE : 1);
        }
        /**
         * Depth 1 means a shallow copy - i.e. only looking one layer down (e.g. at the values within the top-level map).
         * Depth 2 would mean going one-deep into the values inside the top-level map/list/set.
         * Default is infinite.
s         * 
         * By default, depth only applies to nested maps. One needs to set {@link #mergeNestedLists(boolean)} for 
         * it to do this to nested iterables.
         */
        public Builder depth(int val) {
            checkArgument(val > 0, "val %s must be positive", val);
            this.depth = val;
            return this;
        }
        /** @deprecated since 1.0, never did anything */
        @Deprecated
        public Builder mergeNestedMaps(boolean val) {
            this.mergeNestedMaps = val;
            return this;
        }
        /** By default lists will not be merged, and either the first or second will be kept 
         * depending on {@link #preferSecondOnConflict(boolean)}. Set this to true to cause
         * lists in the second merge argument to be appended to lists in the first, or false for the default behaviour. 
         * For more complex behaviours use {@link #mergeNestedLists(BiFunction)}.
         */
        public Builder mergeNestedLists(boolean val) {
            if (val) {
                mergeNestedLists((l1,l2) -> {
                    Collection<Object> result = l1 instanceof Set ? MutableSet.of() : MutableList.of();
                    result.addAll(l1);
                    result.addAll(l2);
                    return result;
                });
            } else {
                mergeNestedLists(null);
            }
            return this;
        }
        /** Defines a function to use to determine the result when lists or sets need merging. */ 
        public Builder mergeNestedLists(BiFunction<Collection<?>, Collection<?>, Collection<?>> listMergeFunction) {
            this.mergeNestedLists = listMergeFunction;
            return this;
        }
        /** defaults to false, so if there is an unmergeable conflict, e.g. two strings, the first will be kept */
        public Builder preferSecondOnConflict(boolean val) {
            this.preferSecondOnConflict = val;
            return this;
        }
        public CollectionMerger build() {
            return new CollectionMerger(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
    
    protected final int depth;
    protected final BiFunction<Collection<?>, Collection<?>, Collection<?>> mergeNestedLists;
    protected final boolean preferSecondOnConflict;

    protected CollectionMerger(Builder builder) {
        this.depth = builder.depth;
        this.mergeNestedLists = builder.mergeNestedLists;
        this.preferSecondOnConflict = builder.preferSecondOnConflict;
    }
    
    public Map<?, ?> merge(Map<?, ?> map1, Map<?, ?> map2) {
        checkNotNull(map1, "map1");
        checkNotNull(map2, "map2");
        return (Map<?,?>) mergeImpl(Maybe.of(map1), Maybe.of(map2), depth, new Visited());
    }
    
    protected Object mergeImpl(Maybe<?> val1, Maybe<?> val2, int depthRemaining, Visited visited) {
        if (visited.isVisited(val1.orNull())) {
            throw new IllegalStateException("Recursive self-reference, "+val1.get().getClass()+": "+val1.get());
        }
        if (visited.isVisited(val2.orNull())) {
            throw new IllegalStateException("Recursive self-reference, "+val2.get().getClass()+": "+val2.get());
        }
        visited.recordVisit(val1.orNull());
        visited.recordVisit(val2.orNull());
        
        if (depthRemaining < 0) {
            throw new IllegalStateException("Invalid depth "+depthRemaining);
        }
        if (val2.isAbsent() || val2.isNull()) {
            return (val1.isPresent() ? val1.get() : null);
        }
        if (val1.isAbsent()) {
            return (val2.isPresent() ? val2.get() : null);
        }
        if (val1.isNull()) {
            // An explicit null value is treated as a marker to mean "do-not-merge"
            return val1.get();
        }
        
        Object conflictResult = preferSecondOnConflict ? val2.get() : val1.get(); 
        if (val1.get() instanceof Map) {
            Map<?,?> map1 = (Map<?, ?>) val1.get();
            if (val2.get() instanceof Map) {
                return mergeMapsImpl(map1, (Map<?, ?>) val2.get(), depthRemaining, visited);
            } else {
                // incompatible types; not merging
                return conflictResult;
            }
        }
        if (val1.get() instanceof Iterable) {
            if (mergeNestedLists == null) {
                return conflictResult;
            } else if (!(val2.get() instanceof Iterable)) {
                // incompatible types; not merging
                return conflictResult;
            } else if (depthRemaining < 1) {
                return conflictResult;
            } else {
                Collection<?> iter1 = val1.get() instanceof Collection ? (Collection<?>) val1.get() : MutableList.copyOf((Iterable<?>)val1.get());
                return mergeNestedLists.apply(iter1, MutableList.copyOf((Iterable<?>)val2.get()));
            }
        }
        return conflictResult;
    }

    private Map<?, ?> mergeMapsImpl(Map<?, ?> val1, Map<?, ?> val2, int depthRemaining, Visited visited) {
        if (depthRemaining < 1) {
            return val1;
        }
        MutableMap<Object, Object> result = MutableMap.of();
        for (Object key : Sets.union(val1.keySet(), val2.keySet())) {
            Maybe<?> sub1 = val1.containsKey(key) ? Maybe.of(val1.get(key)) : Maybe.absent();
            Maybe<?> sub2 = val2.containsKey(key) ? Maybe.of(val2.get(key)) : Maybe.absent();
            result.put(key, mergeImpl(sub1, sub2, depthRemaining-1, visited));
        }
        return result;
    }
    
    /**
     * For avoiding infinite loops, we need to know which objects we have already visited. 
     * If we come across that object again, then want to return the same result (rather than
     * re-visiting it). It is based on "same" (i.e. "==").
     */
    protected static class Visited {
        private static final Set<Class<?>> TRIVIAL_CLASSES = ImmutableSet.<Class<?>>of(
                Integer.class, Long.class, Boolean.class, Byte.class, Double.class, Float.class, Character.class, Short.class,
                String.class, BigInteger.class, BigDecimal.class, Date.class);

        protected static class Ref {
            protected final Object obj;
            
            protected Ref(Object obj) {
                this.obj = checkNotNull(obj, "ref");
            }
            
            @Override
            public boolean equals(Object o) {
                if (!(o instanceof Ref)) {
                    return false;
                }
                return obj == ((Ref)o).obj;
            }
            
            @Override
            public int hashCode() {
                return System.identityHashCode(obj);
            }
            
            @Override
            public String toString() {
                return "Ref["+obj+"]";
            }
        }

        protected final Set<Ref> visited = Sets.newLinkedHashSet();

        public boolean isVisited(Object o) {
            if (isTrivial(o)) return false;
            return visited.contains(new Ref(o));
        }

        public void recordVisit(Object o) {
            if (isTrivial(o)) return;
            visited.add(new Ref(o));
        }
        
        protected boolean isTrivial(Object o) {
            if (o == null)  return true;
            if (o instanceof Map && ((Map<?,?>)o).isEmpty()) return true;
            if (o instanceof Iterable && Iterables.isEmpty(((Iterable<?>)o))) return true;
            Class<?> clazz = o.getClass();
            return clazz.isEnum() || clazz.isPrimitive() || TRIVIAL_CLASSES.contains(clazz);
        }
    }
}
