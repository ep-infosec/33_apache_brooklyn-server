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
package org.apache.brooklyn.util.core.task;

import com.google.common.annotations.Beta;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Callables;
import com.google.common.util.concurrent.ExecutionList;
import com.google.common.util.concurrent.ListenableFuture;
import groovy.lang.Closure;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.mgmt.HasTaskChildren;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.mgmt.BrooklynTaskTags;
import org.apache.brooklyn.core.resolve.jackson.BeanWithTypeUtils;
import org.apache.brooklyn.util.JavaGroovyEquivalents;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.collections.MutableSet;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.javalang.Boxing;
import org.apache.brooklyn.util.text.Identifiers;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.apache.brooklyn.util.JavaGroovyEquivalents.asString;
import static org.apache.brooklyn.util.JavaGroovyEquivalents.elvisString;

/**
 * The basic concrete implementation of a {@link Task} to be executed.
 *
 * A {@link Task} is a wrapper for an executable unit, such as a {@link Runnable} or
 * {@link Callable} ({@link Closure} support is deprecated), and will run in its own {@link Thread}.
 * <p>
 * The task can be given an optional displayName and description in its constructor (as named
 * arguments in the first {@link Map} parameter). It is guaranteed to have {@link Object#notify()} called
 * once whenever the task starts running and once again when the task is about to complete. Due to
 * the way executors work it is ugly to guarantee notification <em>after</em> completion, so instead we
 * notify just before then expect the user to call {@link #get()} - which will throw errors if the underlying job
 * did so - or {@link #blockUntilEnded()} which will not throw errors.
 */
public class BasicTask<T> implements TaskInternal<T> {
    private static final Logger log = LoggerFactory.getLogger(BasicTask.class);

    private String id = Identifiers.makeRandomId(8);
    protected Callable<T> job;
    public final String displayName;
    public final String description;

    // TODO would be nice to make this linked to preserve order, as well as concurrent;
    // but need to take care to support deserialization
    protected final Set<Object> tags = Sets.newConcurrentHashSet();
    // for debugging, to record where tasks were created
//    { tags.add(new Throwable("Creation stack trace")); }
    
    protected Task<?> proxyTargetTask = null;

    protected String blockingDetails = null;
    protected Task<?> blockingTask = null;
    Object extraStatusText = null;

    /** listeners attached at task level; these are stored here, but run on the underlying ListenableFuture */
    protected final ExecutionList listeners = new ExecutionList();
    
    /**
     * Constructor needed to prevent confusion in groovy stubs when looking for default constructor,
     *
     * The generics on {@link Closure} break it if that is first constructor.
     * 
     * @deprecated since 0.11.0; present only as a workaround for Groovy.
     */
    @Deprecated
    protected BasicTask() { this(Collections.emptyMap()); }

    protected BasicTask(Map<?,?> flags) { this(flags, (Callable<T>) null); }

    public BasicTask(Callable<T> job) { this(Collections.emptyMap(), job); }
    
    public BasicTask(Map<?,?> flags, Callable<T> job) {
        this.job = job;

        if (flags.containsKey("tag")) tags.add(flags.remove("tag"));
        Object ftags = flags.remove("tags");
        if (ftags!=null) {
            if (ftags instanceof Iterable) Iterables.addAll(tags, (Iterable<?>)ftags);
            else {
                log.info("deprecated use of non-collection argument for 'tags' ("+ftags+") in "+this, new Throwable("trace of discouraged use of non-collection tags argument"));
                tags.add(ftags);
            }
        }

        description = elvisString(flags.remove("description"), "");
        String d = asString(flags.remove("displayName"));
        displayName = (d==null ? "" : d);
    }

    public BasicTask(Runnable job) {
        this(JavaGroovyEquivalents.toCallable(job));
    }
    
    public BasicTask(Map<?,?> flags, Runnable job) {
        this(flags, JavaGroovyEquivalents.toCallable(job));
    }
    
    @Override
    public String getId() {
        return id;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Task)
            return ((Task<?>)obj).getId().equals(getId());
        return false;
    }

    @Override
    public String toString() {
        // give display name plus id, or job and tags plus id; some jobs have been extended to include nice tostrings 
        return "Task["+
            (Strings.isNonEmpty(displayName) ? 
                displayName : 
                (job + (tags!=null && !tags.isEmpty() ? ";"+tags : "")) ) +
            "]@"+getId();
    }

    @Override
    public Task<T> asTask() {
        return this;
    }
    
    // housekeeping --------------------

    /*
     * These flags are set by BasicExecutionManager.submit.
     *
     * Order is guaranteed to be as shown below, in order of #. Within each # line it is currently in the order specified by commas but this is not guaranteed.
     * (The spaces between the # section indicate longer delays / logical separation ... it should be clear!)
     *
     * # submitter, submit time set, tags and other submit-time fields set
     *
     * # thread set, ThreadLocal getCurrentTask set
     * # start time set, isBegun is true
     * # task end callback run, if supplied
     *
     * # task runs
     *
     * # task end callback run, if supplied
     * # end time set
     * # thread cleared, ThreadLocal getCurrentTask set
     * # Task.notifyAll()
     * # Task.get() (result.get()) available, Task.isDone is true
     *
     * Few _consumers_ should care, but internally we rely on this so that, for example, status is displayed correctly.
     * Tests should catch most things, but be careful if you change any of the above semantics.
     */

    protected long queuedTimeUtc = -1;
    protected long submitTimeUtc = -1;
    protected long startTimeUtc = -1;
    protected long endTimeUtc = -1;
    protected Maybe<Task<?>> submittedByTask;
    protected String submittedByTaskId;

    protected volatile Thread thread = null;
    protected volatile boolean cancelled = false;
    /** normally a {@link ListenableFuture}, except for scheduled tasks when it may be a {@link ScheduledFuture} */
    protected volatile Future<T> internalFuture = null;
    
    @Override
    public synchronized void initInternalFuture(ListenableFuture<T> result) {
        if (this.internalFuture != null) 
            throw new IllegalStateException("task "+this+" is being given a result twice");
        this.internalFuture = result;
        notifyAll();
    }

    // metadata accessors ------------

    @Override
    public Set<Object> getTags() { return Collections.unmodifiableSet(new LinkedHashSet<Object>(tags)); }
    
    /** if the job is queued for submission (e.g. by another task) it can indicate that fact (and time) here;
     * note tasks can (and often are) submitted without any queueing, in which case this value may be -1 */
    @Override
    public long getQueuedTimeUtc() { return queuedTimeUtc; }
    
    @Override
    public long getSubmitTimeUtc() { return submitTimeUtc; }
    
    @Override
    public long getStartTimeUtc() { return startTimeUtc; }
    
    @Override
    public long getEndTimeUtc() { return endTimeUtc; }

    @Override
    public Future<T> getInternalFuture() { return internalFuture; }
    
    @Override
    public Task<?> getSubmittedByTask() { 
        if (submittedByTask==null) return null;
        return submittedByTask.orNull(); 
    }
    @Override
    public String getSubmittedByTaskId() {
        if (submittedByTaskId!=null) return submittedByTaskId;
        if (submittedByTask==null || submittedByTask.isAbsent()) return null;
        throw new IllegalStateException("Task was set up with a submitted task but no task ID");
    }

    /** the thread where the task is running, if it is running */
    @Override
    public Thread getThread() { return thread; }

    // basic fields --------------------

    @Override
    public boolean isQueued() {
        return (queuedTimeUtc >= 0);
    }

    @Override
    public boolean isQueuedOrSubmitted() {
        return isQueued() || isSubmitted();
    }

    @Override
    public boolean isQueuedAndNotSubmitted() {
        return isQueued() && (!isSubmitted());
    }

    @Override
    public boolean isSubmitted() {
        return submitTimeUtc >= 0;
    }

    @Override
    public boolean isBegun() {
        return startTimeUtc >= 0;
    }

    /** marks the task as queued for execution */
    @Override
    public void markQueued() {
        if (queuedTimeUtc<0)
            queuedTimeUtc = System.currentTimeMillis();
    }

    @Override
    public final synchronized boolean cancel() { return cancel(true); }

    /** doesn't resume it, just means if something was cancelled but not submitted it could now be submitted;
     * probably going to be removed and perhaps some mechanism for running again made available
     * @since 0.7.0  */
    @Beta
    public synchronized boolean uncancel() {
        boolean wasCancelled = cancelled;
        cancelled = false; 
        return wasCancelled;
    }
    
    @Override
    public final synchronized boolean cancel(boolean mayInterruptIfRunning) {
        // semantics changed in 2016-01, previously "true" was INTERRUPT_TASK_BUT_NOT_SUBMITTED_TASKS
        return cancel(mayInterruptIfRunning ? TaskCancellationMode.INTERRUPT_TASK_AND_DEPENDENT_SUBMITTED_TASKS
            : TaskCancellationMode.DO_NOT_INTERRUPT);
    }
    
    @Override @Beta
    public synchronized boolean cancel(TaskCancellationMode mode) {
        if (isDone(true)) return false;
        if (log.isTraceEnabled()) {
            log.trace("BT cancelling "+this+" mode "+mode+", from thread "+Thread.currentThread());
        }
        cancelled = true;
        doCancel(mode);
        notifyAll();
        return true;
    }
    
    protected boolean doCancel(TaskCancellationMode mode) {
        if (internalFuture!=null) { 
            if (internalFuture instanceof TaskInternalCancellableWithMode) {
                return ((TaskInternalCancellableWithMode)internalFuture).cancel(mode);
            } else {
                return internalFuture.cancel(mode.isAllowedToInterruptTask());
            }
        }
        return true;
    }

    @Override
    public boolean isCancelled() {
        return cancelled || (internalFuture!=null && internalFuture.isCancelled());
    }

    @Override
    public boolean isDone(boolean andTaskNotRunning) {
        if (!cancelled && !(internalFuture!=null && internalFuture.isDone()) && endTimeUtc<=0) {
            // done if the internal future is done and end time is set
            return false;
        }
        if (andTaskNotRunning && cancelled && isBegun() && endTimeUtc<=0) {
            // if not-running confirmation requested, for cancelled tasks, if begun, wait for endTime to be set
            return false;
        }
        return true;
    }
    
    @Override
    public boolean isDone() {
        return isDone(false);
    }

    /**
     * Returns true if the task has had an error.
     *
     * Only true if calling {@link #get()} will throw an exception when it completes (including cancel).
     * Implementations may set this true before completion if they have that insight, or
     * (the default) they may compute it lazily after completion (returning false before completion).
     */
    @Override
    public boolean isError() {
        if (!isDone()) return false;
        if (isCancelled()) return true;
        try {
            get();
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    // future value --------------------

    @Override
    public T get() throws InterruptedException, ExecutionException {
        try {
            if (!isDone())
                Tasks.setBlockingTask(this);
            blockUntilStarted();
            return internalFuture.get();
        } finally {
            Tasks.resetBlockingTask();
        }
    }

    @Override
    public T getUnchecked() {
        try {
            return get();
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }
    
    @Override
    public synchronized void blockUntilStarted() {
        blockUntilStarted(null);
    }

    @Override
    public synchronized boolean blockUntilStarted(Duration timeout) {
        Long endTime = timeout==null ? null : System.currentTimeMillis() + timeout.toMillisecondsRoundingUp();
        while (true) {
            if (cancelled) throw new CancellationException();
            if (startTimeUtc>0) return true;
            if (internalFuture==null)
                try {
                    if (timeout==null) {
                        // 5s so that it will repeat in case something sets the future without notifying;
                        // can of course repeat here forever if someone calls get on an unsubmitted task,
                        // but that is not hard to discover with a thread dump, seeing it waiting here
                        wait(5*1000);
                    } else {
                        long remaining = endTime - System.currentTimeMillis();
                        if (remaining>0)
                            wait(remaining);
                        else
                            return false;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Throwables.propagate(e);
                }
            if (internalFuture!=null) return true;
        }
    }

    @Override
    public void blockUntilEnded() {
        blockUntilEnded(null);
    }
    
    @Override
    public boolean blockUntilEnded(Duration timeout) {
        return blockUntilEnded(timeout, false);
    }

    @Override
    public boolean blockUntilEnded(Duration timeout, boolean andTaskNotRunning) {
        Long endTime = timeout==null ? null : System.currentTimeMillis() + timeout.toMillisecondsRoundingUp();
        try {
            while (true) {
                try {
                    boolean started = blockUntilStarted(timeout);
                    if (!started) return false;
                } catch (CancellationException cancelled) {
                    // above can fail if started
                    if (isDone(andTaskNotRunning)) return true;
                }
                if (timeout == null) {
                    internalFuture.get();
                } else {
                    long remaining = endTime - System.currentTimeMillis();
                    try {
                        if (remaining > 0)
                            internalFuture.get(remaining, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException|CancellationException e) {
                        // timeout normal, cancellation should be handled as per below
                    }
                    remaining = endTime - System.currentTimeMillis();
                    if (remaining <= 0) {
                        return isDone(andTaskNotRunning);
                    }
                }
                if (isDone(andTaskNotRunning)) return true;

                // should only come here if timeout not exceeded, internalFuture is ready, but tasks not done. wait with a short delay.
                Thread.yield();
                if (isDone(andTaskNotRunning)) return true;
                Time.sleep(20);
            }

        } catch (Throwable t) {
            Exceptions.propagateIfFatal(t);
            if (!(t instanceof TimeoutException) && log.isDebugEnabled())
                log.debug("call from "+Thread.currentThread()+", blocking until '"+this+"' finishes, ended with error: "+t);
            return isDone(andTaskNotRunning);
        }
    }

    @Override
    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return get(new Duration(timeout, unit));
    }
    
    @Override
    public T get(Duration duration) throws InterruptedException, ExecutionException, TimeoutException {
        long start = System.currentTimeMillis();
        Long end  = duration==null ? null : start + duration.toMillisecondsRoundingUp();
        while (end==null || end > System.currentTimeMillis()) {
            if (cancelled) throw new CancellationException();
            if (internalFuture == null) {
                synchronized (this) {
                    long remaining = end - System.currentTimeMillis();
                    if (internalFuture==null && remaining>0)
                        wait(remaining);
                }
            }
            if (internalFuture != null) break;
        }
        Long remaining = end==null ? null : end -  System.currentTimeMillis();
        if (isDone()) {
            // Don't just call internalFuture.get(1ms) - see comment in isDone() about setting of endTimeUtc,
            // and see BROOKLYN-242.
            if (internalFuture == null) {
                assert cancelled: "task="+this+"; endTimeUtc="+endTimeUtc+"; cancelled="+cancelled+"; isDone=true; null internal future";
                throw new CancellationException();
            } else if (remaining == null) {
                return internalFuture.get();
            } else {
                return internalFuture.get(Math.max(remaining, 1000), TimeUnit.MILLISECONDS);
            }
        } else if (remaining == null) {
            return internalFuture.get();
        } else if (remaining > 0) {
            return internalFuture.get(remaining, TimeUnit.MILLISECONDS);
        } else {
            throw new TimeoutException();
        }
    }

    @Override
    public T getUnchecked(Duration duration) {
        try {
            return get(duration);
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }
    
    // ------------------ status ---------------------------
    
    /**
     * Returns a brief status string
     *
     * Plain-text format. Reported status if there is one, otherwise state which will be one of:
     * <ul>
     * <li>Not submitted
     * <li>Submitted for execution
     * <li>Ended by error
     * <li>Ended by cancellation
     * <li>Ended normally
     * <li>Running
     * <li>Waiting
     * </ul>
     */
    @Override
    public String getStatusSummary() {
        return getStatusString(0);
    }

    /**
     * Returns detailed status, suitable for a hover
     *
     * Plain-text format, with new-lines (and sometimes extra info) if multiline enabled.
     */
    @Override
    public String getStatusDetail(boolean multiline) {
        return getStatusString(multiline?2:1);
    }

    protected static class StatusStringData {
        boolean hasBlockingDetails = false;
        String mainShortSummary = null;
        protected void setSummary(String summary) {
            if (mainShortSummary !=null) {
                // irregular, but maybe during a race
                mainShortSummary += "; and " + summary;
            } else {
                mainShortSummary = summary;
            }
        }
        protected void appendToSummary(String detail) {
            if (Strings.isNonBlank(detail)) {
                mainShortSummary = mainShortSummary + detail;
            }
        }
        Set<String> oneLineData = MutableSet.of();
        Set<String> multiLineData = MutableSet.of();
    }

    /**
     * This method is useful for callers to see the status of a task.
     *
     * Also for developers to see best practices for examining status fields etc
     *
     * @param verbosity 0 = brief, 1 = one-line with some detail, 2 = lots of detail
     */
    protected String getStatusString(int verbosity) {
        StatusStringData data = new StatusStringData();
        if (submitTimeUtc <= 0) data.setSummary("Not submitted");
        else if (!isCancelled() && startTimeUtc <= 0) {
            data.setSummary("Submitted for execution");
            if (verbosity>0) {
                data.appendToSummary(" "+Time.makeTimeStringRoundedSince(submitTimeUtc)+" ago");
            }
            if (verbosity >= 2 && getExtraStatusText()!=null) {
                data.multiLineData.add(""+getExtraStatusText());
            }
        } else if (isDone()) {
            long elapsed = endTimeUtc - submitTimeUtc;
            boolean allDone = isDone(true);
            String duration = (elapsed>=0 ? " after "+Time.makeTimeStringRounded(elapsed) : allDone ? " but no end time" : "");
            if (!allDone) duration += " but not ended";

            if (isCancelled()) {
                data.setSummary("Cancelled");
                if (verbosity >= 1) data.appendToSummary(duration);
                computeStatusStringError(verbosity, data);
                computeStatusStringActive(verbosity, data);

            } else if (isError()) {
                data.setSummary("Failed");
                if (verbosity >= 1) data.appendToSummary(duration);
                computeStatusStringError(verbosity, data);
                computeStatusStringActive(verbosity, data);

            } else {
                data.setSummary("Completed");
                if (verbosity>=1) {

                    Callable<String> valueGetter = () -> {
                        Object v = get();
                        if (v == null) {
                            return null;
                        } else if (v instanceof String) {
                            // ensure anything map-like is multiline so sanitization works
                            if (Strings.isMultiLine((String) v) || ((String) v).contains(": ") || ((String) v).contains("="))
                                return "\n" + Strings.indent(2, (String) v);
                            return (String) v;
                        } else if (Boxing.isPrimitiveOrBoxedObject(v)) {
                            return ""+v;
                        } else {
                            String vs;
                            try {
                                vs = BeanWithTypeUtils.newYamlMapper(null, false, null, false).writeValueAsString(v);
                                if (vs.trim().startsWith("---")) vs = Strings.removeFromStart(vs.trim(), "---").trim();
                            } catch (Exception e) {
                                Exceptions.propagateIfFatal(e);
                                vs = v.toString();
                            }
                            return "\n"+Strings.indent(2, vs);
                        }
                    };

                    if (verbosity==1) {
                        try {
                            String v = valueGetter.call();
                            data.appendToSummary(", " +(v==null ? "no return value (null)" : "result: "+abbreviate(v)));
                        } catch (Exception e) {
                            data.appendToSummary(", but error accessing result ["+Exceptions.collapseText(e)+"]"); //shouldn't happen
                        }
                    } else {
                        if (verbosity >= 1) data.appendToSummary(duration);
                        try {
                            String v = valueGetter.call();
                            data.multiLineData.add(v==null ? "No return value (null)" : "Result: "+v);

                        } catch (Exception e) {
                            data.appendToSummary(", but error accessing result"); //shouldn't happen
                            data.multiLineData.add("Error accessing result: "+e);
                        }
                    }

                    computeStatusStringError(verbosity, data);
                    computeStatusStringActive(verbosity, data);
                }
            }
        } else {
            computeStatusStringActive(verbosity, data);
        }

        if (Strings.isBlank(data.mainShortSummary)) data.setSummary("Unknown"); //shouldn't happen
        if (verbosity<=0) return data.mainShortSummary;
        String result = data.mainShortSummary + Strings.join(data.oneLineData, "");
        if (verbosity==1) return result;
        return MutableList.of(result).appendAll(data.multiLineData).stream().filter(Strings::isNonBlank).map(String::trim)
                .collect(Collectors.joining("\n\n"));
    }

    private static String abbreviate(String s0) {
        boolean isMultiline = Strings.isMultiLine(s0);
        String s = Strings.getFirstLine(s0);
        if (Strings.isBlank(s) && isMultiline) s = Strings.getFirstLine(s0.trim());
        if (s.length()>255) s = s.substring(0, 252)+ "...";
        else if (isMultiline) s = s+" ...";
        return s;
    }

    protected void computeStatusStringActive(int verbosity, StatusStringData data) {
        Thread t = getThread();
        boolean done = isDone();
    
        // Normally, it's not possible for thread==null as we were started and not ended
        
        // However, there is a race where the task starts and completes between the calls to getThread()
        // at the start of the method and this call to getThread(), so both return null even though
        // the intermediate checks returned started==true isDone()==false.
        if (t == null) {
            if (done) {
                if (data.mainShortSummary==null) {
                    data.setSummary("Finishing");
                    data.appendToSummary("; just went done, no thread available");
                }
            } else {
                if (data.mainShortSummary==null) {
                    //should only happen for repeating task which is not active
                    data.setSummary("Sleeping");
                    data.appendToSummary("; no thread available");
                }
            }
            computeStatusStringOptionalDetails(verbosity, data);

            return;
        }

        ThreadInfo ti = ManagementFactory.getThreadMXBean().getThreadInfo(t.getId(), (verbosity<=0 ? 0 : verbosity==1 ? 1 : Integer.MAX_VALUE));
        if (getThread()==null) {
            //thread has moved on to a new task; if so, recompute (it should now say "done" or "finishing")
            data.multiLineData.add("Task thread transitioned to null from "+t);
            computeStatusStringOptionalDetails(verbosity, data);
            return;
        }


        if (!done) {
            computeStatusStringOptionalDetails(verbosity, data);
            if (Strings.isBlank(data.mainShortSummary)) data.setSummary("In progress");
            computeStatusStringError(verbosity, data);
        } else if (data.mainShortSummary==null) {
            data.setSummary("Finishing");
            data.appendToSummary("; just went done");
            computeStatusStringOptionalDetails(verbosity, data);
        }

        computeStatusStringThreadInfo(verbosity, data, ti);
    }

    protected void computeStatusStringThreadInfo(int verbosity, StatusStringData data, ThreadInfo ti) {
        if (verbosity>=1) {
            LockInfo lock = ti.getLockInfo();
            String msg;
            if (lock==null && ti.getThreadState()==Thread.State.RUNNABLE) {
                //not blocked
                if (ti.isSuspended()) {
                    msg = "Thread suspended";
                } else {
                    if (verbosity >= 2) {
                        msg = "(" + ti.getThreadState() + ")";
                    } else {
                        msg = null;
                    }
                }
            } else {
                msg = "Thread waiting ";
                if (ti.getThreadState() == Thread.State.BLOCKED) {
                    msg += "(mutex) on "+lookup(lock);
                    //TODO could say who holds it
                } else if (ti.getThreadState() == Thread.State.WAITING) {
                    msg += "(notify) on "+lookup(lock);
                } else if (ti.getThreadState() == Thread.State.TIMED_WAITING) {
                    msg += "(timed) on "+lookup(lock);
                } else {
                    msg += "("+ti.getThreadState()+") on "+lookup(lock);
                }
            }
            if (msg!=null) {
                if (data.hasBlockingDetails) {
                    // if already has blocking details include this with lower priority
                    data.multiLineData.add(msg);
                } else {
                    data.oneLineData.add((msg.startsWith("(") ? "" : ",") + " " + Strings.toInitialLowerCase(msg));
                }
            }
            data.hasBlockingDetails = true;
        }
        if (verbosity>=2) {
            StackTraceElement[] st = ti.getStackTrace();
            st = org.apache.brooklyn.util.javalang.StackTraceSimplifier.cleanStackTrace(st);
            if (st!=null && st.length>0) {
                StringBuilder sb = new StringBuilder();
                sb.append("At: " + st[0]);
                for (int ii = 1; ii < st.length; ii++) {
                    sb.append("\n" + "    " + st[ii]);
                }
                data.multiLineData.add(sb.toString());
            }
        }
    }

    protected void computeStatusStringOptionalDetails(int verbosity, StatusStringData data) {
        if (verbosity<1) return;
        if (Strings.isNonBlank(blockingDetails)) {
            data.hasBlockingDetails = true;
            if (Strings.isBlank(data.mainShortSummary)) data.setSummary(blockingDetails);
            else if (verbosity==1) data.appendToSummary("; "+blockingDetails);
            else data.multiLineData.add("Waiting: "+blockingDetails);
        }

        if (verbosity >= 1 && blockingTask!=null) {
            String msg = "Waiting on: " + blockingTask;
            if (Strings.isBlank(data.mainShortSummary)) data.setSummary(msg);
            else if (verbosity==1) {
                if (!data.hasBlockingDetails) data.appendToSummary("; " + Strings.toInitialLowerCase(msg));
            } else data.multiLineData.add(msg);
            data.hasBlockingDetails = true;
        }

        if (verbosity >= 2) {

            if (getExtraStatusText()!=null) {
                data.multiLineData.add( ""+getExtraStatusText() );
            }

            data.multiLineData.add("Known as: " + toString());

            if (submittedByTask!=null && submittedByTask.isPresent()) {
                data.multiLineData.add("Submitted by: "+submittedByTask.get());
            }

            if (this instanceof HasTaskChildren) {
                // list children tasks for compound tasks
                String msg = "";
                try {
                    Iterable<Task<?>> childrenTasks = ((HasTaskChildren)this).getChildren();
                    if (childrenTasks.iterator().hasNext()) {
                        msg += "Children:\n";
                        for (Task<?> child: childrenTasks) {
                            msg += "  "+child+": "+child.getStatusDetail(false)+"\n";
                        }
                    }
                } catch (ConcurrentModificationException exc) {
                    msg += "(children not available - currently being modified)\n";
                }
                if (Strings.isNonBlank(msg)) data.multiLineData.add(msg);
            }
        }
    }

    private transient String loggedLongStack = null;

    protected void computeStatusStringError(int verbosity, StatusStringData data) {
        Throwable error = Tasks.getError(this, false);
        if (error!=null) {

            if (verbosity >= 1) {
                //remove outer ExecException which is reported by the get(), we want the exception the task threw
                while (error instanceof ExecutionException) error = error.getCause();
                String errorMessage = Exceptions.collapseText(error);
                boolean isCancelled = isCancelled() && error instanceof CancellationException;
                if (!isCancelled) data.oneLineData.add(": " + abbreviate(errorMessage));
                if (verbosity >= 2) {
                    if (loggedLongStack!=null) {
                        data.multiLineData.add(loggedLongStack);
                    } else {
                        StringWriter sw = new StringWriter();
                        error.printStackTrace(new PrintWriter(sw));
                        String sws = sw.toString();
                        if (isCancelled && sws.contains(BasicTask.class.getName() + "." + "computeStatusStringError")) {
                            // don't add the cancellation exception generated by this call
                        } else {
                            if (sws.length() > 80 * 100) {
                                // shorten a bit, if long; esp if there is a circular reference
                                loggedLongStack = sws.substring(0, 40 * 100) + "\n  ...\n  ..." + sws.substring(sws.length() - 40 * 100);
                                log.warn("Long stack trace suppressed when reporting status of task " + getId() + ":\n" + sws);
                                data.multiLineData.add(loggedLongStack);
                            } else {
                                data.multiLineData.add(sws);
                            }
                        }
                    }
                }
            }
        }
    }

    protected String lookup(LockInfo info) {
        return info!=null ? ""+info : "unknown (sleep)";
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    
    /** allows a task user to specify why a task is blocked; for use immediately before a blocking/wait,
     * and typically cleared immediately afterwards; referenced by management api to inspect a task
     * which is blocking
     */
    @Override
    public String setBlockingDetails(String blockingDetails) {
        String old = this.blockingDetails;
        this.blockingDetails = blockingDetails;
        return old;
    }
    
    @Override
    public Task<?> setBlockingTask(Task<?> blockingTask) {
        Task<?> old = this.blockingTask;
        this.blockingTask = blockingTask;
        return old;
    }
    
    @Override
    public void resetBlockingDetails() {
        this.blockingDetails = null;
    }
    
    @Override
    public void resetBlockingTask() {
        this.blockingTask = null;
    }

    /** returns a textual message giving details while the task is blocked */
    @Override
    public String getBlockingDetails() {
        return blockingDetails;
    }
    
    /** returns a task that this task is blocked on */
    @Override
    public Task<?> getBlockingTask() {
        return blockingTask;
    }
    
    @Override
    public void setExtraStatusText(Object extraStatus) {
        this.extraStatusText = extraStatus;
    }
    
    @Override
    public Object getExtraStatusText() {
        return extraStatusText;
    }

    // ---- add a way to warn if task is not run
    
    public interface TaskFinalizer {
        public void onTaskFinalization(Task<?> t);
    }

    public static final TaskFinalizer WARN_IF_NOT_RUN = new TaskFinalizer() {
        @Override
        public void onTaskFinalization(Task<?> t) {
            if (!Tasks.isAncestorCancelled(t) && !t.isSubmitted()) {
                boolean skipWarning = false;
                skipWarning |= t instanceof ScheduledTask && ((ScheduledTask) t).getNextScheduled()!=null;  // scheduled tasks don't set submitted until run one
                skipWarning |= t instanceof TaskInternal && ((TaskInternal) t).getQueuedTimeUtc() > 0;  // skip if queued
                skipWarning |= BrooklynTaskTags.hasTag(t, BrooklynTaskTags.WORKFLOW_TAG);  // workflow tasks are managed by us, and skipped if workflow doesn't run
                if (!skipWarning) {
                    // this might be a sign of a leak; usually created tasks are very very soon queued or submitted
                    log.warn(t + " was never submitted; did the code create it and forget to run it? ('cancel' the task to suppress this message)");
                    log.debug("Detail of unsubmitted task " + t + ":\n" + t.getStatusDetail(true));
                    return;
                }
            }
            if (!t.isDone()) {
                if (!BrooklynTaskTags.getExecutionContext(t).isShutdown()) {
                    // not sure how this could happen
                    log.warn("Task "+t+" was submitted but forgotten before it was run (finalized before completion)");
                }
                return;
            }
        }
    };

    public static final TaskFinalizer NO_OP = new TaskFinalizer() {
        @Override
        public void onTaskFinalization(Task<?> t) {
        }
    };
    
    public void ignoreIfNotRun() {
        setFinalizer(NO_OP);
    }
    
    public void setFinalizer(TaskFinalizer f) {
        TaskFinalizer finalizer = Tasks.tag(this, TaskFinalizer.class, false);
        if (finalizer!=null && finalizer!=f)
            throw new IllegalStateException("Cannot apply multiple finalizers");
        if (isDone())
            throw new IllegalStateException("Finalizer cannot be set on task "+this+" after it is finished");
        tags.add(f);
    }

    @Override
    protected void finalize() throws Throwable {
        TaskFinalizer finalizer = Tasks.tag(this, TaskFinalizer.class, false);
        if (finalizer==null) finalizer = WARN_IF_NOT_RUN;
        finalizer.onTaskFinalization(this);
    }
    
    public static class SubmissionErrorCatchingExecutor implements Executor {
        final Executor target;
        public SubmissionErrorCatchingExecutor(Executor target) {
            this.target = target;
        }
        @Override
        public void execute(Runnable command) {
            if (isShutdown()) {
                log.debug("Skipping execution of task callback hook "+command+" because executor is shutdown.");
                return;
            }
            try {
                target.execute(command);
            } catch (Exception e) {
                if (isShutdown()) {
                    log.debug("Ignoring failed execution of task callback hook "+command+" because executor is shutdown.");
                } else {
                    log.warn("Execution of task callback hook "+command+" failed: "+e, e);
                }
            }
        }
        protected boolean isShutdown() {
            return target instanceof ExecutorService && ((ExecutorService)target).isShutdown();
        }
    }
    
    @Override
    public void addListener(Runnable listener, Executor executor) {
        listeners.add(listener, new SubmissionErrorCatchingExecutor(executor));
    }
    
    @Override
    public void runListeners() {
        listeners.execute();
    }
    
    @Override
    public void setEndTimeUtc(long val) {
        endTimeUtc = val;
    }
    
    @Override
    public void setThread(Thread thread) {
        this.thread = thread;
    }
    
    @Override
    public Callable<T> getJob() {
        return job;
    }
    
    @Override
    public void setJob(Callable<T> job) {
        this.job = job;
    }
    
    @Override
    public ExecutionList getListeners() {
        return listeners;
    }
    
    @Override
    public void setSubmitTimeUtc(long val) {
        submitTimeUtc = val;
    }
    
    @Override
    public void setSubmittedByTask(Task<?> task) {
        setSubmittedByTask(Maybe.ofDisallowingNull(task), task==null ? null : task.getId());
    }
    @Override
    public void setSubmittedByTask(Maybe<Task<?>> taskM, String taskId) {
        submittedByTask = Preconditions.checkNotNull(taskM);
        submittedByTaskId = taskId;
    }
    
    @Override
    public Set<Object> getMutableTags() {
        return tags;
    }
    
    @Override
    public void setStartTimeUtc(long val) {
        startTimeUtc = val;
    }

    @Override
    public void applyTagModifier(Function<Set<Object>,Void> modifier) {
        modifier.apply(tags);
    }

    @Override
    public Task<?> getProxyTarget() {
        return proxyTargetTask;
    }

    public static class PlaceholderTask extends BasicTask {
        private PlaceholderTask(Map flags) {
            super(flags);
        }

        public static PlaceholderTask newPlaceholderForForgottenTask(String id, String displayName) {
            PlaceholderTask result = new PlaceholderTask(MutableMap.of(
                    "displayName", displayName + " (placeholder)",
                    "description", "Details of the original task have been forgotten."
                    ));

            // since 2021-10 claim the ID of the thing we are placeholding so we get treated as an equal
            ((BasicTask)result).id = id;

            result.job = Callables.returning(null);

            // don't really want anyone executing the "gone" task...
            // also if we are GC'ing tasks then cancelled may help with cleanup
            // of sub-tasks that have lost their submitted-by-task reference ?
            // also don't want warnings when it's finalized, this means we don't need ignoreIfNotRun()
            result.cancelled = true;

            return result;
        }
    }
}
