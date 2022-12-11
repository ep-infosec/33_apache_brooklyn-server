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
package org.apache.brooklyn.util.exceptions;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.IOException;
import java.util.ConcurrentModificationException;
import java.util.concurrent.ExecutionException;

import org.apache.brooklyn.util.collections.MutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;

public class ExceptionsTest {

    private static final Logger log = LoggerFactory.getLogger(ExceptionsTest.class);
    
    @Test
    public void testPropagateRuntimeException() throws Exception {
        NullPointerException tothrow = new NullPointerException("simulated");
        try {
            throw Exceptions.propagate(tothrow);
        } catch (NullPointerException e) {
            assertEquals(e, tothrow);
        }
    }
    
    @Test
    public void testPropagateCheckedException() throws Exception {
        Exception tothrow = new Exception("simulated");
        try {
            throw Exceptions.propagate(tothrow);
        } catch (RuntimeException e) {
            assertEquals(e.getCause(), tothrow);
        }
    }
    
    @Test
    public void testPropagateCheckedExceptionWithMessage() throws Exception {
        String extraMsg = "my message";
        Exception tothrow = new Exception("simulated");
        try {
            throw Exceptions.propagateAnnotateIfWrapping(extraMsg, tothrow);
        } catch (RuntimeException e) {
            assertEquals(e.getMessage(), "my message");
            assertEquals(e.getCause(), tothrow);
        }
    }
    
    @Test
    public void testPropagateRuntimeExceptionIgnoresMessage() throws Exception {
        NullPointerException tothrow = new NullPointerException("simulated");
        try {
            throw Exceptions.propagateAnnotateIfWrapping("my message", tothrow);
        } catch (NullPointerException e) {
            assertEquals(e, tothrow);
        }
    }
    
    @Test
    public void testPropagateIfFatalPropagatesInterruptedException() throws Exception {
        InterruptedException tothrow = new InterruptedException("simulated");
        try {
            Exceptions.propagateIfFatal(tothrow);
            fail();
        } catch (RuntimeException e) {
            assertTrue(Thread.interrupted()); // note this clears the interrupted flag as well
            assertEquals(e.getCause(), tothrow);
        }
    }
    
    @Test
    public void testPropagateIfFatalPropagatesRuntimeInterruptedException() throws Exception {
        RuntimeInterruptedException tothrow = new RuntimeInterruptedException(new InterruptedException("simulated"));
        try {
            Exceptions.propagateIfFatal(tothrow);
            fail();
        } catch (RuntimeInterruptedException e) {
            assertTrue(Thread.interrupted()); // note this clears the interrupted flag as well
            assertEquals(e, tothrow);
        }
    }
    
    @Test
    public void testPropagateIfFatalPropagatesError() throws Exception {
        Error tothrow = new Error();
        try {
            Exceptions.propagateIfFatal(tothrow);
            fail();
        } catch (Error e) {
            assertEquals(e, tothrow);
        }
    }
    
    @Test
    public void testPropagateIfFatalDoesNotPropagatesNormalException() throws Exception {
        Exception e = new Exception();
        Exceptions.propagateIfFatal(e);
        
        RuntimeException re = new RuntimeException();
        Exceptions.propagateIfFatal(re);
        
        Throwable t = new Throwable();
        Exceptions.propagateIfFatal(t);
    }

    @Test
    public void testPropagateIfInterruptPropagatesInterruptedException() throws Exception {
        InterruptedException tothrow = new InterruptedException("simulated");
        try {
            Exceptions.propagateIfInterrupt(tothrow);
            fail();
        } catch (RuntimeException e) {
            assertTrue(Thread.interrupted()); // note this clears the interrupted flag as well
            assertEquals(e.getCause(), tothrow);
        }
    }
    
    @Test
    public void testPropagateIfInterruptPropagatesRuntimeInterruptedException() throws Exception {
        RuntimeInterruptedException tothrow = new RuntimeInterruptedException(new InterruptedException("simulated"));
        try {
            Exceptions.propagateIfInterrupt(tothrow);
            fail();
        } catch (RuntimeInterruptedException e) {
            assertTrue(Thread.interrupted()); // note this clears the interrupted flag as well
            assertEquals(e, tothrow);
        }
    }
    
    @Test
    public void testPropagateIfInterruptDoesNotPropagateOtherExceptions() throws Exception {
        Exception e = new Exception();
        Exceptions.propagateIfInterrupt(e);
        
        RuntimeException re = new RuntimeException();
        Exceptions.propagateIfInterrupt(re);
        
        Throwable t = new Throwable();
        Exceptions.propagateIfInterrupt(t);
        
        Throwable er = new Error();
        Exceptions.propagateIfInterrupt(er);
    }
    
    @Test
    public void testGetFirstThrowableOfType() throws Exception {
        NullPointerException npe = new NullPointerException("simulated");
        IllegalStateException ise = new IllegalStateException("simulated", npe);
        
        assertEquals(Exceptions.getFirstThrowableOfType(ise, IllegalStateException.class), ise);
        assertEquals(Exceptions.getFirstThrowableOfType(ise, NullPointerException.class), npe);
        assertNull(Exceptions.getFirstThrowableOfType(ise, IndexOutOfBoundsException.class));
    }
    
    @Test
    public void testGetFirstThrowableMatching() throws Exception {
        NullPointerException npe = new NullPointerException("simulated");
        IllegalStateException ise = new IllegalStateException("simulated", npe);
        
        assertEquals(Exceptions.getFirstThrowableMatching(ise, Predicates.instanceOf(IllegalStateException.class)), ise);
        assertEquals(Exceptions.getFirstThrowableMatching(ise, Predicates.instanceOf(NullPointerException.class)), npe);
        assertNull(Exceptions.getFirstThrowableMatching(ise, Predicates.alwaysFalse()));
    }
    
    @Test
    public void testCollapseIncludesRootCause() throws Exception {
        NullPointerException npe = new NullPointerException("simulated2");
        IllegalStateException ise = new IllegalStateException("simulated1", npe);
        
        assertEquals(Exceptions.collapseText(ise), "simulated1: NullPointerException: simulated2");
    }
    
    @Test
    public void testCollapseDoesNotIncludeRootCauseTwiceIfAppendedUncollapsed() throws Exception {
        NullPointerException npe = new NullPointerException("simulated2");
        IllegalStateException ise = new IllegalStateException("simulated1: "+npe, npe);
        
        assertEquals(Exceptions.collapseText(ise), "simulated1: java.lang.NullPointerException: simulated2");
    }
    
    @Test
    public void testCollapseDoesNotIncludeRootCauseTwiceIfAppendedCollapsed() throws Exception {
        NullPointerException npe = new NullPointerException("simulated2");
        IllegalStateException ise = new IllegalStateException("simulated1: "+Exceptions.collapseText(npe), npe);
        
        assertEquals(Exceptions.collapseText(ise), "simulated1: NullPointerException: simulated2");
    }
    
    @Test
    public void test12CollapseCompound() throws Exception {
        RuntimeException e = Exceptions.create("test1", MutableSet.of(new IllegalStateException("test2"), new IllegalStateException("test3")));
        assert12StandardChecks(e, false);
    }
    
    @Test
    public void test12CollapsePropagatedExecutionCompoundBoring() throws Exception {
        RuntimeException e = new PropagatedRuntimeException("test1", 
            new ExecutionException(Exceptions.create(MutableSet.of(new IllegalStateException("test2"), new IllegalStateException("test3")))));
        assert12StandardChecks(e, true);
    }

    @Test
    public void test12CollapsePropagatedCompoundConcMod() throws Exception {
        RuntimeException e = new PropagatedRuntimeException("test1", 
            new ExecutionException(Exceptions.create(MutableSet.of(new ConcurrentModificationException("test2"), new ConcurrentModificationException("test3")))));
        assert12StandardChecks(e, true);
        assertContains(e, "ConcurrentModification");
    }
    
    @Test
    public void testCollapseTextWhenExceptionMessageEmpty() throws Exception {
        String text = Exceptions.collapseText(new ExecutionException(new IllegalStateException()));
        Assert.assertNotNull(text);
    }
    
    @Test
    public void testPropagateNoMessageGivesType() throws Exception {
        Throwable t = new Throwable();
        Assert.assertEquals(Exceptions.collapseText(t), Throwable.class.getName());
        try { Exceptions.propagate(t); } catch (Throwable t2) { t = t2; }
        Assert.assertEquals(Exceptions.collapseText(t), Throwable.class.getName());
    }

    @Test
    public void testPropagateWithoutAnnotationSuppressed() throws Exception {
        Throwable t = new Throwable("test");
        try { Exceptions.propagate(t); } catch (Throwable t2) { t = t2; }
        Assert.assertEquals(Exceptions.collapseText(t), "test");
    }

    @Test
    public void testPropagateWithAnnotationNotExplicitIncludedWhenWrapped() throws Exception {
        Throwable t = new Throwable("test");
        try { Exceptions.propagateAnnotateIfWrapping("important", t); } catch (Throwable t2) { t = t2; }
        Assert.assertEquals(Exceptions.collapseText(t), "important: test");
    }

    @Test
    public void testPropagateWithAnnotationNotExplicitIgnoredWhenNotWrapped() throws Exception {
        Throwable t = new RuntimeException("test");
        try { Exceptions.propagateAnnotateIfWrapping("ignore", t); } catch (Throwable t2) { t = t2; }
        Assert.assertEquals(Exceptions.collapseText(t), "test");
    }

    @Test
    public void testPropagateWithAnnotationExplicitNotSuppressed() throws Exception {
        Throwable t = new RuntimeException("test");
        try { Exceptions.propagateAnnotated("important", t); } catch (Throwable t2) { t = t2; }
        Assert.assertEquals(Exceptions.collapseText(t), "important: test");
    }

    @Test
    public void testPrefixModificationRequired() throws Exception {
        Throwable t = new NoClassDefFoundError("sample");
        Assert.assertEquals(Exceptions.collapseText(t), "Invalid java type: sample");
    }

    @Test
    public void testNestedPropWithMessage() {
        Throwable t;
        t = new IOException("1");
        t = new org.apache.brooklyn.util.exceptions.PropagatedRuntimeException(t);
        t = new org.apache.brooklyn.util.exceptions.PropagatedRuntimeException("A", t);
        Assert.assertEquals(Exceptions.collapseText(t), "A: IOException: 1");
    }
    
    @Test
    public void testExec() {
        Throwable t;
        t = new IOException("1");
        t = new java.util.concurrent.ExecutionException(t);
        Assert.assertEquals(Exceptions.collapseText(t), "IOException: 1");
    }
    
    @Test
    public void testNestedExecAndProp() {
        Throwable t;
        t = new IOException("1");
        t = new org.apache.brooklyn.util.exceptions.PropagatedRuntimeException(t);
        t = new java.util.concurrent.ExecutionException(t);
        Assert.assertEquals(Exceptions.collapseText(t), "IOException: 1");
    }
    
    @Test
    public void testGetCausalChain() throws Exception {
        Exception e1 = new Exception("e1");
        Exception e2 = new Exception("e2", e1);
        assertEquals(Exceptions.getCausalChain(e2), ImmutableList.of(e2, e1));
    }
    
    @Test
    public void testGetCausalChainRecursive() throws Exception {
        Exception e1 = new Exception("e1") {
            private static final long serialVersionUID = 1L;
            public synchronized Throwable getCause() {
                return this;
            }
        };
        Exception e2 = new Exception("e2", e1);
        assertEquals(Exceptions.getCausalChain(e2), ImmutableList.of(e2, e1));
    }
    
    @Test
    public void testComplexJcloudsExample() {
        Throwable t;
        t = new IOException("POST https://ec2.us-east-1.amazonaws.com/ HTTP/1.1 -> HTTP/1.1 401 Unauthorized");
        t = new IllegalStateException("Not authorized to access cloud JcloudsLocation[aws-ec2:foo/aws-ec2@SEk63t8T]", t);
        t = new java.util.concurrent.ExecutionException(t);
        t = new org.apache.brooklyn.util.exceptions.PropagatedRuntimeException(t);
        t = new org.apache.brooklyn.util.exceptions.PropagatedRuntimeException("Error invoking start at EmptySoftwareProcessImpl{id=GVYo7Cth}", t);
        t = new java.util.concurrent.ExecutionException(t);
        t = new org.apache.brooklyn.util.exceptions.PropagatedRuntimeException(t);
        t = new java.util.concurrent.ExecutionException(t);
        t = new org.apache.brooklyn.util.exceptions.PropagatedRuntimeException(t);
        t = new java.util.concurrent.ExecutionException(t);
        t = new org.apache.brooklyn.util.exceptions.PropagatedRuntimeException(t);
        t = new org.apache.brooklyn.util.exceptions.PropagatedRuntimeException("Error invoking start at BasicApplicationImpl{id=fbihp1mo}", t);
        t = new java.util.concurrent.ExecutionException(t);
        
        String collapsed = Exceptions.collapseText(t);
        // should say IOException and POST
        Assert.assertTrue(collapsed.contains("IOException"), collapsed);
        Assert.assertTrue(collapsed.matches(".*POST.*"), collapsed);
        // should not contain propagated or POST twice
        Assert.assertFalse(collapsed.contains("Propagated"), collapsed);
        Assert.assertFalse(collapsed.matches(".*POST.*POST.*"), collapsed);
    }
    
    private void assert12StandardChecks(RuntimeException e, boolean isPropagated) {
        String collapseText = Exceptions.collapseText(e);
        log.info("Exception collapsing got: "+collapseText+" ("+e+")");
        assertContains(e, "test1");
        assertContains(e, "test2");
        
        if (isPropagated)
            assertContains(e, "2 errors, including");
        else
            assertContains(e, "2 errors including");
        
        assertNotContains(e, "IllegalState");
        assertNotContains(e, "CompoundException");
        Assert.assertFalse(collapseText.contains("Propagated"), "should NOT have had Propagated: "+collapseText);
        
        if (isPropagated)
            Assert.assertTrue(e.toString().contains("Propagate"), "SHOULD have had Propagated: "+e);
        else
            Assert.assertFalse(e.toString().contains("Propagate"), "should NOT have had Propagated: "+e);
    }
    
    private static void assertContains(Exception e, String keyword) {
        Assert.assertTrue(e.toString().contains(keyword), "Missing keyword '"+keyword+"' in exception toString: "+e);
        Assert.assertTrue(Exceptions.collapseText(e).contains(keyword), "Missing keyword '"+keyword+"' in collapseText: "+Exceptions.collapseText(e));
    }
    private static void assertNotContains(Exception e, String keyword) {
        Assert.assertFalse(e.toString().contains(keyword), "Unwanted keyword '"+keyword+"' in exception toString: "+e);
        Assert.assertFalse(Exceptions.collapseText(e).contains(keyword), "Unwanted keyword '"+keyword+"' in collapseText: "+Exceptions.collapseText(e));
    }
    
}
