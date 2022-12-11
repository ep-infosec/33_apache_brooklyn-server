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

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.annotation.Nullable;

import org.apache.brooklyn.util.exceptions.Exceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

public class MutableSet<V> extends LinkedHashSet<V> {
    
    private static final long serialVersionUID = 2330133488446834595L;
    private static final Logger log = LoggerFactory.getLogger(MutableSet.class);

    public static <V> MutableSet<V> of() {
        return new MutableSet<V>();
    }
    
    public static <V> MutableSet<V> of(V v1) {
        MutableSet<V> result = new MutableSet<V>();
        result.add(v1);
        return result;
    }
    
    public static <V> MutableSet<V> of(V v1, V v2) {
        MutableSet<V> result = new MutableSet<V>();
        result.add(v1);
        result.add(v2);
        return result;
    }
    
    public static <V> MutableSet<V> of(V v1, V v2, V v3, @SuppressWarnings("unchecked") V ...vMore) {
        MutableSet<V> result = new MutableSet<V>();
        result.add(v1);
        result.add(v2);
        result.add(v3);
        if (vMore==null) {
            result.add(null);
        } else {
            for (V vi : vMore) result.add(vi);
        }
        return result;
    }

    public static <V> MutableSet<V> copyOf(@Nullable Iterable<? extends V> orig) {
        return orig==null ? new MutableSet<V>() : new MutableSet<V>(orig);
    }
    
    public static <V> MutableSet<V> copyOf(@Nullable Iterator<? extends V> elements) {
        if (elements == null || !elements.hasNext()) {
            return of();
        }
        return new MutableSet.Builder<V>().addAll(elements).build();
    }
    
    public MutableSet() {
    }
    
    public MutableSet(Iterable<? extends V> source) {
        super((source instanceof Collection) ? (Collection<? extends V>)source : Sets.newLinkedHashSet(source));
    }
    
    /** as {@link MutableList#asImmutableCopy()()} */
    public Set<V> asImmutableCopy() {
        try {
            return ImmutableSet.copyOf(this);
        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            log.warn("Error converting list to Immutable, using unmodifiable instead: "+e, e);
            return asUnmodifiableCopy();
        }
    }
    /** as {@link MutableList#asUnmodifiable()} */
    public Set<V> asUnmodifiable() {
        return Collections.unmodifiableSet(this);
    }
    /** as {@link MutableList#asUnmodifiableCopy()} */
    public Set<V> asUnmodifiableCopy() {
        return Collections.unmodifiableSet(MutableSet.copyOf(this));
    }
    
    public static <V> Builder<V> builder() {
        return new Builder<V>();
    }

    /**
     * @see guava's ImmutableSet.Builder
     */
    public static class Builder<V> {
        final MutableSet<V> result = new MutableSet<V>();

        public Builder() {}

        public Builder<V> addIfNotNull(V value) {
            if (value != null) result.add(value);
            return this;
        }

        public Builder<V> add(V value) {
            result.add(value);
            return this;
        }

        public Builder<V> add(V v1, V v2, @SuppressWarnings("unchecked") V ...values) {
            result.add(v1);
            result.add(v2);
            if (values==null) {
                result.add(null);
            } else {
                for (V value : values) result.add(value);
            }
            return this;
        }

        public Builder<V> remove(V val) {
            result.remove(val);
            return this;
        }
        
        public Builder<V> addAll(V[] values) {
            for (V v : values) {
                result.add(v);
            }
            return this;
        }
        public Builder<V> addAll(Iterable<? extends V> iterable) {
            if (iterable instanceof Collection) {
                result.addAll((Collection<? extends V>) iterable);
            } else {
                for (V v : iterable) {
                    result.add(v);
                }
            }
            return this;
        }

        public Builder<V> addAll(Iterator<? extends V> iter) {
            while (iter.hasNext()) {
                add(iter.next());
            }
            return this;
        }

        public Builder<V> removeAll(Iterable<? extends V> iterable) {
            if (iterable instanceof Collection) {
                result.removeAll((Collection<? extends V>) iterable);
            } else {
                for (V v : iterable) {
                    result.remove(v);
                }
            }
            return this;
        }
        
        public Builder<V> retainAll(Iterable<? extends V> iterable) {
            if (iterable instanceof Collection) {
                result.retainAll((Collection<? extends V>) iterable);
            } else {
                Set<V> toretain = Sets.newHashSet(iterable);
                result.retainAll(toretain);
            }
            return this;
        }
        
        public MutableSet<V> build() {
          return new MutableSet<V>(result);
        }
        
    }
    
    public boolean addIfNotNull(V e) {
        if (e!=null) return add(e);
        return false;
    }

    public boolean addAll(Iterable<? extends V> setToAdd) {
        // copy of parent, but accepting Iterable and null
        if (setToAdd==null) return false;
        boolean modified = false;
        Iterator<? extends V> e = setToAdd.iterator();
        while (e.hasNext()) {
            if (add(e.next()))
                modified = true;
        }
        return modified;
    }
    
    /** as {@link #addAll(Collection)} but fluent style and permitting null */
    public MutableSet<V> putAll(Iterable<? extends V> setToAdd) {
        if (setToAdd!=null) addAll(setToAdd);
        return this;
    }

    /** as {@link #add(V)} but fluent style */
    public MutableSet<V> put(V e) {
        add(e);
        return this;
    }

    /** as {@link #addIfNotNull(V)} but fluent style */
    public MutableSet<V> putIfNotNull(V e) {
        if (e!=null) add(e);
        return this;
    }

    public boolean removeIfNotNull(V item) {
        if (item==null) return false;
        return remove(item);
    }

}
