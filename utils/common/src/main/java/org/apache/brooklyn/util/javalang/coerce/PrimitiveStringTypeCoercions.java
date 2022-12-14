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
package org.apache.brooklyn.util.javalang.coerce;

import java.lang.reflect.Method;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.javalang.JavaClassNames;
import org.apache.brooklyn.util.text.StringEscapes.JavaStringEscapes;

import com.google.common.primitives.Primitives;

public class PrimitiveStringTypeCoercions {

    public PrimitiveStringTypeCoercions() {}
    
    @SuppressWarnings({ "unchecked" })
    public static <T> Maybe<T> tryCoerce(Object value, Class<? super T> targetType) {
        Maybe<T> result = null;
        //deal with primitive->primitive casting
        if (isPrimitiveOrBoxer(targetType) && isPrimitiveOrBoxer(value.getClass())) {
            // Don't just rely on Java to do its normal casting later; if caller writes
            // long `l = coerce(new Integer(1), Long.class)` then letting java do its casting will fail,
            // because an Integer will not automatically be unboxed and cast to a long
            result = castPrimitiveMaybe(value, (Class<T>)targetType);
            if (result.isPresent()) return result;
        }

        //deal with string->primitive
        if (value instanceof String && isPrimitiveOrBoxer(targetType)) {
            result = stringToPrimitiveMaybe((String)value, (Class<T>)targetType);
            if (result.isPresent()) return result;
        }

        //deal with primitive->string
        if (isPrimitiveOrBoxer(value.getClass()) && targetType.equals(String.class)) {
            return Maybe.of((T) value.toString());
        }

        //look for value.asType where Type is castable to targetType
        String targetTypeSimpleName = JavaClassNames.verySimpleClassName(targetType);
        if (targetTypeSimpleName!=null && targetTypeSimpleName.length()>0) {
            for (Method m: value.getClass().getMethods()) {
                if (m.getName().startsWith("as") && m.getParameterTypes().length==0 &&
                        targetType.isAssignableFrom(m.getReturnType()) ) {
                    if (m.getName().equals("as"+JavaClassNames.verySimpleClassName(m.getReturnType()))) {
                        try {
                            return Maybe.of((T) m.invoke(value));
                        } catch (Exception e) {
                            Exceptions.propagateIfFatal(e);
                            return Maybe.absent(new ClassCoercionException("Cannot coerce type "+value.getClass()+" to "+targetType.getCanonicalName()+" ("+value+"): "+m.getName()+" adapting failed, "+e));
                        }
                    }
                }
            }
        }
        
        return result;
    }

    /** @deprecated since 1.0.0 use {@link #castPrimitiveMaybe(Object, Class)} */
    @Deprecated
    public static <T> T castPrimitive(Object value, Class<T> targetType) {
        return castPrimitiveMaybe(value, targetType).get();
    }
    /**
     * Sometimes need to explicitly cast primitives, rather than relying on Java casting.
     * For example, when using generics then type-erasure means it doesn't actually cast,
     * which causes tests to fail with 0 != 0.0
     */
    @SuppressWarnings("unchecked")
    public static <T> Maybe<T> castPrimitiveMaybe(Object value, Class<T> targetType) {
        if (value==null) return null;
        assert isPrimitiveOrBoxer(targetType) : "targetType="+targetType;
        assert isPrimitiveOrBoxer(value.getClass()) : "value="+targetType+"; valueType="+value.getClass();

        Class<?> sourceWrapType = Primitives.wrap(value.getClass());
        Class<?> targetWrapType = Primitives.wrap(targetType);
        
        // optimization, for when already correct type
        if (sourceWrapType == targetWrapType) {
            return Maybe.of((T) value);
        }
        
        if (targetWrapType == Boolean.class) {
            // only char can be mapped to boolean
            // (we could say 0=false, nonzero=true, but there is no compelling use case so better
            // to encourage users to write as boolean)
            if (sourceWrapType == Character.class)
                return stringToPrimitiveMaybe(value.toString(), targetType);
            
            return Maybe.absent(new ClassCoercionException("Cannot cast "+sourceWrapType+" ("+value+") to "+targetType));
        } else if (sourceWrapType == Boolean.class) {
            // boolean can't cast to anything else
            
            return Maybe.absent(new ClassCoercionException("Cannot cast "+sourceWrapType+" ("+value+") to "+targetType));
        }

        try {
            // for whole-numbers (where casting to long won't lose anything)...
            long v = 0;
            boolean islong = true;
            if (sourceWrapType == Character.class) {
                v = ((Character) value).charValue();
            } else if (sourceWrapType == Byte.class) {
                v = ((Byte) value).byteValue();
            } else if (sourceWrapType == Short.class) {
                v = ((Short) value).shortValue();
            } else if (sourceWrapType == Integer.class) {
                v = ((Integer) value).intValue();
            } else if (sourceWrapType == Long.class) {
                v = ((Long) value).longValue();
            } else {
                islong = false;
            }
            if (islong) {
                if (targetWrapType == Character.class) return Maybe.of((T) Character.valueOf((char) v));
                if (targetWrapType == Byte.class) return Maybe.of((T) (Byte) Byte.parseByte("" + v));
                if (targetWrapType == Short.class) return Maybe.of((T) (Short) Short.parseShort("" + v));
                if (targetWrapType == Integer.class) return Maybe.of((T) (Integer) Integer.parseInt("" + v));
                if (targetWrapType == Long.class) return Maybe.of((T) Long.valueOf(v));
                if (targetWrapType == Float.class) return Maybe.of((T) Float.valueOf(v));
                if (targetWrapType == Double.class) return Maybe.of((T) Double.valueOf(v));
                return Maybe.absent(new IllegalStateException("Unexpected: sourceType=" + sourceWrapType + "; targetType=" + targetWrapType));
            }

            // for real-numbers (cast to double)...
            double d = 0;
            boolean isdouble = true;
            if (sourceWrapType == Float.class) {
                d = ((Float) value).floatValue();
            } else if (sourceWrapType == Double.class) {
                d = ((Double) value).doubleValue();
            } else {
                isdouble = false;
            }

            if (isdouble) {
                if (targetWrapType == Double.class) return Maybe.of((T) Double.valueOf(d));

                BigDecimal dd = BigDecimal.valueOf(d);
                if (targetWrapType == Float.class) {
                    float candidate = (float) d;
                    if (dd.subtract(BigDecimal.valueOf(candidate)).abs().compareTo(BigDecimal.valueOf(CommonAdaptorTypeCoercions.DELTA_FOR_COERCION)) > 0) {
                        throw new IllegalStateException("Decimal value out of range; cannot convert " + candidate + " to float");
                    }
                    return Maybe.of((T) (Float) candidate);
                }

                if (targetWrapType == Integer.class) return Maybe.of((T) Integer.valueOf((dd.intValueExact())));
                if (targetWrapType == Long.class) return Maybe.of((T) Long.valueOf(dd.longValueExact()));
                if (targetWrapType == Short.class) return Maybe.of((T) Short.valueOf(dd.shortValueExact()));
                if (targetWrapType == Byte.class) return Maybe.of((T) Byte.valueOf(dd.byteValueExact()));

                if (targetWrapType == Character.class)
                    return Maybe.of((T) Character.valueOf((char) dd.intValueExact()));
            }
        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            return Maybe.absent(new IllegalStateException("Unexpected error: sourceType="+sourceWrapType+"; targetType="+targetWrapType+": "+Exceptions.collapseText(e), e));
        }

        return Maybe.absent(new IllegalStateException("Unexpected: sourceType="+sourceWrapType+"; targetType="+targetWrapType));
    }
    
    public static boolean isPrimitiveOrBoxer(Class<?> type) {
        // cf Boxing.isPrimitiveOrBoxerClass
        return Primitives.allPrimitiveTypes().contains(type) || Primitives.allWrapperTypes().contains(type);
    }
    
    /** @deprecated since 1.0.0 use {@link #stringToPrimitiveMaybe(String, Class)} */
    @Deprecated
    public static <T> T stringToPrimitive(String value, Class<T> targetType) {
        return stringToPrimitiveMaybe(value, targetType).get();
    }
    
    @SuppressWarnings("unchecked")
    public static <T> Maybe<T> stringToPrimitiveMaybe(String origValue, Class<T> targetType) {
        assert Primitives.allPrimitiveTypes().contains(targetType) || Primitives.allWrapperTypes().contains(targetType) : "targetType="+targetType;
        // If char, then need to do explicit conversion
        if (targetType == Character.class || targetType == char.class) {
            if (origValue.length() == 1) {
                return Maybe.of((T) (Character) origValue.charAt(0));
            } else if (origValue.length() != 1) {
                throw new ClassCoercionException("Cannot coerce type String to "+targetType.getCanonicalName()+" ("+origValue+"): adapting failed");
            }
        }
        String value = origValue.trim();
        // For boolean we could use valueOf, but that returns false whereas we'd rather throw errors on bad values
        if (targetType == Boolean.class || targetType == boolean.class) {
            if ("true".equalsIgnoreCase(value)) return Maybe.of((T) Boolean.TRUE);
            if ("false".equalsIgnoreCase(value)) return Maybe.of((T) Boolean.FALSE);
            if ("yes".equalsIgnoreCase(value)) return Maybe.of((T) Boolean.TRUE);
            if ("no".equalsIgnoreCase(value)) return Maybe.of((T) Boolean.FALSE);
            if ("t".equalsIgnoreCase(value)) return Maybe.of((T) Boolean.TRUE);
            if ("f".equalsIgnoreCase(value)) return Maybe.of((T) Boolean.FALSE);
            if ("y".equalsIgnoreCase(value)) return Maybe.of((T) Boolean.TRUE);
            if ("n".equalsIgnoreCase(value)) return Maybe.of((T) Boolean.FALSE);
            
            return Maybe.absent(new ClassCoercionException("Cannot coerce type String to "+targetType.getCanonicalName()+" ("+value+"): adapting failed")); 
        }
        
        // Otherwise can use valueOf reflectively
        Class<?> wrappedType;
        if (Primitives.allPrimitiveTypes().contains(targetType)) {
            wrappedType = Primitives.wrap(targetType);
        } else {
            wrappedType = targetType;
        }
        
        Object v;
        try {
            v = wrappedType.getMethod("valueOf", String.class).invoke(null, value);
        } catch (Exception e) {
            ClassCoercionException tothrow = new ClassCoercionException("Cannot coerce "+value.getClass().getSimpleName()+" "+JavaStringEscapes.wrapJavaString(value)+" to "+targetType.getCanonicalName()+": adapting failed");
            tothrow.initCause(e);
            return Maybe.absent(tothrow);
        }

        if (isNanOrInf(v)) {
            return Maybe.absent(() -> new NumberFormatException("Invalid number for "+value+" as "+targetType+": "+v));
        }
        return Maybe.of((T) v);
    }

    public static boolean isNanOrInf(Object o) {
        if (o instanceof Double) return !Double.isFinite((double)o);
        if (o instanceof Float) return !Float.isFinite((float)o);
        return false;
    }
    
}
