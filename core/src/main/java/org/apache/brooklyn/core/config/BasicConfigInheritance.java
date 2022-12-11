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
package org.apache.brooklyn.core.config;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.brooklyn.config.ConfigInheritance;
import org.apache.brooklyn.config.ConfigInheritances;
import org.apache.brooklyn.config.ConfigInheritances.BasicConfigValueAtContainer;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.config.ConfigValueAtContainer;
import org.apache.brooklyn.util.collections.CollectionMerger;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.exceptions.ReferenceWithError;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("serial")
public class BasicConfigInheritance implements ConfigInheritance {

    private static final Logger log = LoggerFactory.getLogger(BasicConfigInheritance.class);
    
    private static final long serialVersionUID = -5916548049057961051L;

    public static final String CONFLICT_RESOLUTION_STRATEGY_DEEP_MERGE = "deep_merge";
    public static final String CONFLICT_RESOLUTION_STRATEGY_OVERWRITE = "overwrite";
    
    /** This class allows us to have constant instances of {@link ConfigInheritance}
     * which have no internal fields (ie they don't extend {@link BasicConfigInheritance}) 
     * so are pretty on serialization, but otherwise act identically. */
    public static abstract class DelegatingConfigInheritance implements ConfigInheritance {
        protected abstract ConfigInheritance getDelegate();
        
        @Override @Deprecated public InheritanceMode isInherited(ConfigKey<?> key, Object from, Object to) {
            return getDelegate().isInherited(key, from, to);
        }
        @Override public <TContainer, TValue> boolean isReinheritable(ConfigValueAtContainer<TContainer, TValue> parent, ConfigInheritanceContext context) {
            return getDelegate().isReinheritable(parent, context);
        }
        @Override public <TContainer, TValue> boolean considerParent(ConfigValueAtContainer<TContainer, TValue> local, ConfigValueAtContainer<TContainer, TValue> parent, ConfigInheritanceContext context) {
            return getDelegate().considerParent(local, parent, context);
        }
        @Override
        public <TContainer, TValue> ReferenceWithError<ConfigValueAtContainer<TContainer, TValue>> resolveWithParent(ConfigValueAtContainer<TContainer, TValue> local, ConfigValueAtContainer<TContainer, TValue> resolvedParent, ConfigInheritanceContext context) {
            return getDelegate().resolveWithParent(local, resolvedParent, context);
        }
        @Override
        public boolean equals(Object obj) {
            return super.equals(obj) || getDelegate().equals(obj);
        }

        @Override
        public int hashCode() {
            return getDelegate().hashCode();
        }

        // standard deserialization method
        protected ConfigInheritance readResolve() {
            return returnEquivalentConstant(this);
        }
    }
    
    private static ConfigInheritance returnEquivalentConstant(ConfigInheritance candidate) {
        for (ConfigInheritance knownMode: Arrays.asList(
                NOT_REINHERITED, NOT_REINHERITED_ELSE_DEEP_MERGE, NEVER_INHERITED, OVERWRITE, BasicConfigInheritance.DEEP_MERGE)) {
            if (candidate.equals(knownMode)) return knownMode;
        }
        if (candidate.equals(NEVER_INHERITED_OLD)) {
            return NEVER_INHERITED;
        }
        return candidate;
    }
    
    /*
     * use of delegate is so that stateless classes can be defined to make the serialization nice,
     * both the name and hiding the implementation detail (also making it easier for that detail to change);
     * with aliased type names the field names here could even be aliases for the type names.
     * (we could alternatively have an "alias-for-final-instance" mode in serialization,
     * to optimize where we know that a java instance is final.)   
     */
    
    private static class NotReinherited extends DelegatingConfigInheritance {
        final static BasicConfigInheritance DELEGATE = new BasicConfigInheritance(false, CONFLICT_RESOLUTION_STRATEGY_OVERWRITE, false, true); 
        @Override protected ConfigInheritance getDelegate() { return DELEGATE; }
    }
    /** Indicates that a config key value should not be passed down from a container where it is defined.
     * Unlike {@link #NEVER_INHERITED} these values can be passed down if set as anonymous keys at a container
     * (ie the container does not expect it) to a container which does expect it, but it will not be passed down further. 
     * If the inheritor also defines a value the parent's value is ignored irrespective 
     * (as in {@link #OVERWRITE}; see {@link #NOT_REINHERITED_ELSE_DEEP_MERGE} if merging is desired). */
    public static final ConfigInheritance NOT_REINHERITED = new NotReinherited();
    
    private static class NotReinheritedElseDeepMerge extends DelegatingConfigInheritance {
        final static BasicConfigInheritance DELEGATE = new BasicConfigInheritance(false, CONFLICT_RESOLUTION_STRATEGY_DEEP_MERGE, false, true); 
        @Override protected ConfigInheritance getDelegate() { return DELEGATE; }
    }
    /** As {@link #NOT_REINHERITED} but in cases where a value is inherited because a parent did not recognize it,
     * if the inheritor also defines a value the two values should be merged. */
    public static final ConfigInheritance NOT_REINHERITED_ELSE_DEEP_MERGE = new NotReinheritedElseDeepMerge();
    
    private static class NeverInherited extends DelegatingConfigInheritance {
        final static BasicConfigInheritance DELEGATE = new BasicConfigInheritance(false, CONFLICT_RESOLUTION_STRATEGY_OVERWRITE, true, false);
        @Override protected ConfigInheritance getDelegate() { return DELEGATE; }
    }
    /** Indicates that a key's value should never be inherited, even if inherited from a value set on a container that does not know the key.
     * (Most usages will prefer {@link #NOT_REINHERITED}.) */
    public static final ConfigInheritance NEVER_INHERITED = new NeverInherited();

    /** In case we deserialize an old copy; the last arg (ancestor inherit default) is irrelevant
     * so accept this as a synonym for {@link #NEVER_INHERITED}. */
    private static final ConfigInheritance NEVER_INHERITED_OLD = new BasicConfigInheritance(false, CONFLICT_RESOLUTION_STRATEGY_OVERWRITE, true, true);
    
    private static class Overwrite extends DelegatingConfigInheritance {
        final static BasicConfigInheritance DELEGATE = new BasicConfigInheritance(true, CONFLICT_RESOLUTION_STRATEGY_OVERWRITE, false, true);
        @Override protected ConfigInheritance getDelegate() { return DELEGATE; }
    }
    /** Indicates that if a key has a value at both an ancestor and a descendant, the descendant and his descendants
     * will prefer the value at the descendant. */
    public static final ConfigInheritance OVERWRITE = new Overwrite();
    
    private static class DeepMerge extends DelegatingConfigInheritance {
        final static BasicConfigInheritance DELEGATE = new BasicConfigInheritance(true, CONFLICT_RESOLUTION_STRATEGY_DEEP_MERGE, false, true);
        @Override protected ConfigInheritance getDelegate() { return DELEGATE; }
    }
    /** Indicates that if a key has a value at both an ancestor and a descendant, the descendant and his descendants
     * should attempt to merge the values. If the values are not mergeable behaviour is undefined
     * (and often the descendant's value will simply overwrite). */
    public static final ConfigInheritance DEEP_MERGE = new DeepMerge();

    // support conversion from these legacy fields
    @SuppressWarnings("deprecation")
    private static void registerReplacements() {
        ConfigInheritance.Legacy.registerReplacement(ConfigInheritance.DEEP_MERGE, DEEP_MERGE); 
        ConfigInheritance.Legacy.registerReplacement(ConfigInheritance.ALWAYS, OVERWRITE); 
        ConfigInheritance.Legacy.registerReplacement(ConfigInheritance.NONE, NOT_REINHERITED); 
    }
    static { registerReplacements(); }
    
    /** whether a value on a key defined locally should be inheritable by descendants.
     * if false at a point where a key is defined, 
     * children/descendants/inheritors will not be able to see its value, whether explicit or default.
     * default true:  things are normally reinherited.
     * <p>
     * note that this only takes effect where a key is defined locally.
     * if a key is not defined at an ancestor, a descendant setting this value false will not prevent it 
     * from inheriting values from ancestors.
     * <p> 
     * typical use case for setting this to false is where a key is consumed and descendants should not
     * "reconsume" it.  for example setting files to install on a VM need only be applied once,
     * and if it has <b>runtime management</b> hierarchy descendants which also understand that field they
     * should not install the same files. 
     * (there is normally no reason to set this false in the context of <b>type hierarchy</b> inheritance because
     * an ancestor type cannot "consume" a value.) */
    protected final boolean isReinherited;
    
    /** a symbol indicating a conflict-resolution-strategy understood by the implementation.
     * in {@link BasicConfigInheritance} supported values are
     * {@link #CONFLICT_RESOLUTION_STRATEGY_DEEP_MERGE} and {@link #CONFLICT_RESOLUTION_STRATEGY_OVERWRITE}.
     * subclasses may pass null or a different string if they provide a custom implementation 
     * of {@link #resolveWithParentCustomStrategy(ConfigValueAtContainer, ConfigValueAtContainer, org.apache.brooklyn.config.ConfigInheritance.ConfigInheritanceContext)} */
    @Nullable protected final String conflictResolutionStrategy;
    
    /** @deprecated since 0.11.0 when this was introduced, now renamed {@link #localDefaultResolvesWithAncestorValue} */
    @Deprecated protected final Boolean useLocalDefaultValue;
    /** whether a local default value should be considered for resolution in the presence of an ancestor value.
     * can use true with {@link #CONFLICT_RESOLUTION_STRATEGY_OVERWRITE} to mean don't inherit, 
     * or true with {@link #CONFLICT_RESOLUTION_STRATEGY_DEEP_MERGE} to mean local default merged on top of inherited
     * (but be careful here, if local default is null in a merge it will delete ancestor values).
     * <p>
     * in most cases this is false, meaning a default value is ignored if the parent has a value,
     * but it can be used when a key supplies a default which should conflict-resolve with an ancestor value:
     * a trivial example is when not reinheriting, a default should conflict-resolve (overwriting) an explicit ancestor value.
     * more interesting potentially, this could indicate
     * that a default value is being introduced which should be merged/combined with ancestors;
     * we don't use this (config inheritance is complex enough and we don't have a compelling use case
     * to expose more complexity to users) but having this as a concept, and the related
     * {@link #ancestorDefaultInheritable} specifying (in this case) whether a local default should 
     * resolve/merge/combine with ancestor defaults in addition to ancestor explicit values,
     * means the logic in this refers to the right control dimensions rather than taking shortcuts. 
     * <p>
     * null should not be used. a boxed object is taken (as opposed to a primitive boolean) only in order to support migration.    
     */
    @Nonnull
    protected final Boolean localDefaultResolvesWithAncestorValue;

    /** whether a default set in an ancestor container's key definition will be considered as the
     * local default value at descendants who don't define any other value (nothing set locally and local default is null).
     * <p>
     * if true (now the usual behaviour), if an ancestor defines a default and a descendant doesn't, the ancestor's value will be taken as a default.
     * if it is also the case that localDefaultResolvesWithAncestorValue is true at the <i>ancestor</i> then a descendant who
     * defines a local default value (with this field true) will have its conflict resolution strategy
     * applied with the ancestor's default value.
     * <p>
     * if this is false, ancestor defaults are completely ignored; prior to 0.11.0 this was the normal behaviour,
     * but it caused surprises where default values in parameters did not take effect.
     * <p>
     * as currently we only really use {@link #localDefaultResolvesWithAncestorValue} true when we
     * wish to block reinheritance, this is false in that case,
     * and true in all other cases (where we wish to have ancestor inheritance, but only if there
     * is no local value that should trump);
     * however logically there are other possibilities, such as a local default expecting to
     * merge/combine with an ancestor value, in which case we'd probably want this to be true
     * to indicate the local default should combine with the ancestor default,
     * but it could also make sense for this to be false, meaning the local default would
     * merge with an explicit ancestor value but not with an ancestor default
     * (see javadoc on {@link #localDefaultResolvesWithAncestorValue} for more info).
     * <p>
     * it gets complex and we've tried to simplify the config modes that we actually use,
     * but have made efforts in the code to account for the different logical possibilities,
     * hence the complexity of this.  
     * <p>
     * null should not be used. a boxed object is taken (as opposed to a primitive boolean) only in order to support migration.    
     */
    @Nonnull
    protected final Boolean ancestorDefaultInheritable;
    
    @Deprecated /** @deprecated since 0.11.0 use four-arg constructor */
    protected BasicConfigInheritance(boolean isReinherited, @Nullable String conflictResolutionStrategy, boolean localDefaultResolvesWithAncestorValue) {
        this(isReinherited, conflictResolutionStrategy, localDefaultResolvesWithAncestorValue, true);
    }
    
    protected BasicConfigInheritance(boolean isReinherited, @Nullable String conflictResolutionStrategy, boolean localDefaultResolvesWithAncestorValue, boolean ancestorDefaultInheritable) {
        super();
        this.isReinherited = isReinherited;
        this.conflictResolutionStrategy = conflictResolutionStrategy;
        this.useLocalDefaultValue = null;
        this.localDefaultResolvesWithAncestorValue = localDefaultResolvesWithAncestorValue;
        this.ancestorDefaultInheritable = ancestorDefaultInheritable;
    }

    @Override @Deprecated
    public InheritanceMode isInherited(ConfigKey<?> key, Object from, Object to) {
        return null;
    }
    
    protected <TContainer, TValue> void checkInheritanceContext(ConfigValueAtContainer<TContainer, TValue> local, ConfigInheritanceContext context) {
        ConfigInheritance rightInheritance = ConfigInheritances.findInheritance(local, context, this);
        if (!isSameRootInstanceAs(rightInheritance)) {
            throw new IllegalStateException("Low level inheritance computation error: caller should invoke on "+rightInheritance+" "
                + "(the inheritance at "+local+"), not "+this);
        }
    }

    private boolean isSameRootInstanceAs(ConfigInheritance other) {
        if (other==null) return false;
        if (this==other) return true;
        // we should consider the argument the same if it is a delegate to us,
        // e.g. for when this method is invoked by the delegate comparing against its definition
        if (other instanceof DelegatingConfigInheritance) return isSameRootInstanceAs( ((DelegatingConfigInheritance)other).getDelegate() );
        return false;
    }

    @Override
    public <TContainer, TValue> boolean isReinheritable(ConfigValueAtContainer<TContainer, TValue> parent, ConfigInheritanceContext context) {
        checkInheritanceContext(parent, context);
        return isReinherited();
    }

    @Override
    public <TContainer,TValue> boolean considerParent(
            ConfigValueAtContainer<TContainer,TValue> local,
            ConfigValueAtContainer<TContainer,TValue> parent,
            ConfigInheritanceContext context) {
        checkInheritanceContext(local, context);
        if (parent==null) return false;
        if (CONFLICT_RESOLUTION_STRATEGY_OVERWRITE.equals(conflictResolutionStrategy)) {
            // overwrite means ignore if there's an explicit value, or we're using the local default
            return !local.isValueExplicitlySet() && !getLocalDefaultResolvesWithAncestorValue();
        }
        return true;
    }

    @Override
    public <TContainer,TValue> ReferenceWithError<ConfigValueAtContainer<TContainer,TValue>> resolveWithParent(
            ConfigValueAtContainer<TContainer,TValue> local,
            ConfigValueAtContainer<TContainer,TValue> parent,
            ConfigInheritanceContext context) {
        
        checkInheritanceContext(local, context);
        
        if (!parent.isValueExplicitlySet() && !getLocalDefaultResolvesWithAncestorValue()) {
            if (getAncestorDefaultInheritable() && !local.isValueExplicitlySet() && local.getDefaultValue().isAbsentOrNull() && parent.getDefaultValue().isPresentAndNonNull()) {
                return ReferenceWithError.newInstanceWithoutError(new BasicConfigValueAtContainer<TContainer,TValue>(parent));
            }
            return ReferenceWithError.newInstanceWithoutError(new BasicConfigValueAtContainer<TContainer,TValue>(local));
        }
        
        // parent explicitly set (or we might have to merge defaults), 
        // and by the contract of this method we can assume reinheritable
        if (!local.isValueExplicitlySet() && !getLocalDefaultResolvesWithAncestorValue())
            return ReferenceWithError.newInstanceWithoutError(new BasicConfigValueAtContainer<TContainer,TValue>(parent));

        // both explicitly set or defaults applicable, and not overwriting; it should be merge, or something from child
        
        if (CONFLICT_RESOLUTION_STRATEGY_DEEP_MERGE.equals(conflictResolutionStrategy)) {
            BasicConfigValueAtContainer<TContainer, TValue> result = new BasicConfigValueAtContainer<TContainer,TValue>(local);
            ReferenceWithError<Maybe<? extends TValue>> resolvedValue = deepMerge(
                local.isValueExplicitlySet() ? local.asMaybe() : local.getDefaultValue(), 
                    parent.isValueExplicitlySet() ? parent.asMaybe() : 
                        getAncestorDefaultInheritable() ? parent.getDefaultValue() : Maybe.<TValue>absent());
            result.setValue(resolvedValue.getWithoutError());
            return ReferenceWithError.newInstanceThrowingError(result, resolvedValue.getError());
        }
        
        return resolveWithParentCustomStrategy(local, parent, context);
    }
    
    /** Provided for subclasses to override.  Invoked by {@link #resolveWithParent(ConfigValueAtContainer, ConfigValueAtContainer, org.apache.brooklyn.config.ConfigInheritance.ConfigInheritanceContext)}
     * if there is a value for the parent and the child. Default implementation throws {@link IllegalStateException}. */
    protected <TContainer, TValue> ReferenceWithError<ConfigValueAtContainer<TContainer, TValue>> resolveWithParentCustomStrategy(
            ConfigValueAtContainer<TContainer, TValue> local, ConfigValueAtContainer<TContainer, TValue> parent,
            ConfigInheritanceContext context) {
        throw new IllegalStateException("Unknown config conflict resolution strategy '"+conflictResolutionStrategy+"' evaluating "+local+"/"+parent);
    }
    
    private static <T> ReferenceWithError<Maybe<? extends T>> deepMerge(Maybe<? extends T> val1, Maybe<? extends T> val2) {
        if (val2.isAbsent() || val2.isNull()) {
            return ReferenceWithError.newInstanceWithoutError(val1);
        } else if (val1.isAbsent()) {
            return ReferenceWithError.newInstanceWithoutError(val2);
        } else if (val1.isNull()) {
            return ReferenceWithError.newInstanceWithoutError(val1); // an explicit null means an override; don't merge
        } else if (val1.get() instanceof Map && val2.get() instanceof Map) {
            @SuppressWarnings({ "unchecked", "rawtypes" })
            Maybe<T> result = (Maybe)Maybe.of(CollectionMerger.builder().build().merge((Map<?,?>)val1.get(), (Map<?,?>)val2.get()));
            return ReferenceWithError.newInstanceWithoutError(result);
        } else {
            // cannot merge; just return val1
            return ReferenceWithError.newInstanceThrowingError(val1, new IllegalArgumentException("Cannot merge '"+val1.get()+"' and '"+val2.get()+"'"));
        }
    }
    
    public boolean isReinherited() {
        return isReinherited;
    }
    
    public String getConflictResolutionStrategy() {
        return conflictResolutionStrategy;
    }
    
    @Deprecated /** @deprecated since 0.11.0 when it was introduced, prefer {@link #getLocalDefaultResolvesWithAncestorValue()} */
    public boolean getUseLocalDefaultValue() {
        return getLocalDefaultResolvesWithAncestorValue();
    }

    /** see {@link #localDefaultResolvesWithAncestorValue} */
    public boolean getLocalDefaultResolvesWithAncestorValue() {
        if (localDefaultResolvesWithAncestorValue==null) {
            // in case some legacy path is using an improperly deserialized object
            log.warn("Encountered legacy "+this+" with null localDefaultResolvesWithAncestorValue; transforming", new Throwable("stack trace for legacy "+this));
            readResolve();
        }
        return localDefaultResolvesWithAncestorValue;
    }
    
    public boolean getAncestorDefaultInheritable() {
        if (ancestorDefaultInheritable==null) {
            log.warn("Encountered legacy "+this+" with null ancestorDefaultInheritable; transforming", new Throwable("stack trace for legacy "+this));
            readResolve();
        }
        return ancestorDefaultInheritable;
    }

    // standard deserialization method
    private ConfigInheritance readResolve() {
        try {
            // uses reflection because fields are declared final
            if (useLocalDefaultValue!=null) {
                // move away from useLocalDefaultValue to localDefaultResolvesWithAncestorValue
                
                Field fNew = getClass().getDeclaredField("localDefaultResolvesWithAncestorValue");
                fNew.setAccessible(true);
                Field fOld = getClass().getDeclaredField("useLocalDefaultValue");
                fOld.setAccessible(true);
                
                if (fNew.get(this)==null) {
                    fNew.set(this, useLocalDefaultValue);
                } else {
                    if (!fNew.get(this).equals(useLocalDefaultValue)) {
                        throw new IllegalStateException("Incompatible values detected for "+fOld+" ("+fOld.get(this)+") and "+fNew+" ("+fNew.get(this)+")");
                    }
                }
                fOld.set(this, null);
            }
            
            if (ancestorDefaultInheritable==null) {
                Field f = getClass().getDeclaredField("ancestorDefaultInheritable");
                f.setAccessible(true);
                f.set(this, true);
            }
            
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
                
        return returnEquivalentConstant(this);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conflictResolutionStrategy, isReinherited, localDefaultResolvesWithAncestorValue, ancestorDefaultInheritable);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (obj==null) return false;
        if (obj instanceof DelegatingConfigInheritance) {
            return equals( ((DelegatingConfigInheritance)obj).getDelegate() );
        }
        if (obj instanceof BasicConfigInheritance) {
            return equalsAfterResolvingDelegate((BasicConfigInheritance)obj);
        }
        return false;
    }
    
    protected boolean equalsAfterResolvingDelegate(BasicConfigInheritance b) {
        return (Objects.equals(conflictResolutionStrategy, b.conflictResolutionStrategy) &&
            Objects.equals(isReinherited, b.isReinherited) &&
            Objects.equals(getLocalDefaultResolvesWithAncestorValue(), b.getLocalDefaultResolvesWithAncestorValue()) &&
            Objects.equals(getAncestorDefaultInheritable(), b.getAncestorDefaultInheritable()));        
    }
    
    @Override
    public String toString() {
        return super.toString()+"[reinherit="+isReinherited()+"; strategy="+getConflictResolutionStrategy()+"; localDefaultResolvesWithAncestor="+localDefaultResolvesWithAncestorValue+"]";
    }
    
    public static ConfigInheritance fromString(String val) {
        if (Strings.isBlank(val)) return null;
        // in all cases below the first value is preferred, but to preserve backwards compatibility we
        // need to support some of the other names/strategies; yoml should give us a way to migrate to canonical form
        switch (val.toLowerCase().trim()) {
        case "not_reinherited":
        case "notreinherited":
        case "none":
            return NOT_REINHERITED;
        case "never":
            return NEVER_INHERITED;
        case "overwrite": 
        case "always": 
            return OVERWRITE;
        case "deep_merge":
        case "merge":
        case "deepmerge":
            return DEEP_MERGE;
        default:
            throw new IllegalArgumentException("Invalid config-inheritance '"+val+"' (legal values are none, always or deep_merge)");
        }
    }
    
}
