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

import org.apache.brooklyn.api.internal.BrooklynLoggingCategories;
import org.apache.brooklyn.api.mgmt.ExecutionContext;
import org.apache.brooklyn.core.BrooklynLogging;
import org.apache.brooklyn.core.BrooklynLogging.LoggingLevel;
import static org.apache.brooklyn.util.JavaGroovyEquivalents.elvis;
import static org.apache.brooklyn.util.JavaGroovyEquivalents.groovyTruth;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.core.mgmt.BrooklynTaskTags;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Duration;

import com.google.common.annotations.Beta;
import com.google.common.base.Throwables;

/**
 * A task which runs with a fixed period.
 * <p>
 * Note that some termination logic, including {@link #addListener(Runnable, java.util.concurrent.Executor)},
 * is not precisely defined. 
 */
// TODO ScheduledTask is a very pragmatic implementation; would be nice to tighten, 
// reduce external assumptions about internal structure, and clarify "done" semantics
public class ScheduledTask extends BasicTask<Object> {
    
    final Callable<Task<?>> taskFactory;

    /**
     * Initial delay before running, set as flag in constructor; defaults to 0
     */
    protected Duration delay;

    /**
     * The time to wait between executions, or null if not to repeat (default), set as flag to constructor;
     * this may be modified for subsequent submissions by a running task generated by the factory 
     * using {@link #getSubmittedByTask().setPeriod(Duration)}
     */
    protected Duration period = null;

    /**
     * Optional, set as flag in constructor; defaults to null meaning no limit.
     */
    protected Integer maxIterations = null;

    /**
     * Set false if the task should be rescheduled after throwing an exception; defaults to true.
     */
    protected boolean cancelOnException = true;

    protected ExecutionContext executionContext;
    protected int runCount=0;
    protected Task<?> recentRun, nextRun;
    Class<? extends Exception> lastThrownType;

    public int getRunCount() { return runCount; }
    public ScheduledFuture<?> getNextScheduled() { return (ScheduledFuture<?>)internalFuture; }

    public ScheduledTask(Callable<Task<?>> taskFactory) {
        this(MutableMap.of(), taskFactory);
    }

    /**
     * @deprecated since 0.11.0; instead use {@link #ScheduledTask(Callable)}.
     * @see {@link #ScheduledTask(Map, Task)}
     */
    @Deprecated
    public ScheduledTask(final Task<?> task) {
        this(MutableMap.of(), task);
    }

    /**
     * @deprecated since 0.11.0; instead use {@link #ScheduledTask(Map, Callable)}. If using this method,
     *             the task will be executed only once (ignoring any additional config such as "period").
     *             This is because the task object is reused for the second execution, but it is
     *             already "done" so does not re-execute.
     * 
     * @see {@link https://issues.apache.org/jira/browse/BROOKLYN-446}
     */
    @Deprecated
    public ScheduledTask(Map<?,?> flags, final Task<?> task){
        this(flags, new Callable<Task<?>>(){
            @Override
            public Task<?> call() throws Exception {
                return task;
            }});
    }

    public ScheduledTask(Map<?,?> flags, Callable<Task<?>> taskFactory) {
        super(flags);
        this.taskFactory = taskFactory;
        
        delay = Duration.of(elvis(flags.remove("delay"), 0));
        period = Duration.of(elvis(flags.remove("period"), null));
        maxIterations = (Integer) elvis(flags.remove("maxIterations"), null);
        Object cancelFlag = flags.remove("cancelOnException");
        cancelOnException = cancelFlag == null || Boolean.TRUE.equals(cancelFlag);
    }
    
    public static Builder builder(Callable<Task<?>> val) {
        return new Builder(val);
    }
    
    public static class Builder {
        Callable<Task<?>> factory;

        String displayName;
        List<Object> tags = MutableList.of();
        Duration delay, period;
        Integer maxInterations;
        boolean cancelOnException = true;
        Map<String,Object> flags = MutableMap.of();
        
        public Builder(Callable<Task<?>> val) { this.factory = val; }
        
        public ScheduledTask build() {
            return new ScheduledTask(MutableMap.copyOf(flags)
                    .addIfNotNull("displayName", displayName) 
                    .addIfNotNull("tags", tags.isEmpty() ? null : tags)
                    .addIfNotNull("delay", delay) 
                    .addIfNotNull("period", period) 
                    .addIfNotNull("maxIterations", maxInterations) 
                    .addIfNotNull("cancelOnException", cancelOnException) 
                , factory);
        }
        
        public Builder displayName(String val) { this.displayName = val; return this; }
        public Builder tag(Object val) { if (val!=null) this.tags.add(val); return this; }
        public Builder tagTransient() { return tag(BrooklynTaskTags.TRANSIENT_TASK_TAG); }
        public Builder delay(Duration val) { this.delay = val; return this; }
        public Builder period(Duration val) { this.period = val; return this; }
        public Builder maxIterations(Integer val) { this.maxInterations = val; return this; }
        public Builder cancelOnException(boolean val) { this.cancelOnException = val; return this; }
        public Builder addFlags(Map<String,?> val) { this.flags.putAll(val); return this; }

    }

    public static String prefixScheduledName(String taskName){
        return "scheduled:["+taskName+"]";
    }

    public ScheduledTask delay(Duration d) {
        this.delay = d;
        return this;
    }

    public ScheduledTask delay(long val) {
        return delay(Duration.millis(val));
    }

    public ScheduledTask period(Duration d) {
        this.period = d;
        return this;
    }

    public ScheduledTask period(long val) {
        return period(Duration.millis(val));
    }

    public ScheduledTask maxIterations(int val) {
        this.maxIterations = val;
        return this;
    }

    public ScheduledTask cancelOnException(boolean cancel) {
        this.cancelOnException = cancel;
        return this;
    }

    public Callable<Task<?>> getTaskFactory() {
        return taskFactory;
    }

    public Task<?> newTask() {
        try {
            return taskFactory.call();
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }
    
    @Override
    protected void computeStatusStringActive(int verbosity, StatusStringData data) {
        if (Strings.isNonBlank(data.mainShortSummary)) {
            data.appendToSummary("; scheduled task");
        } else {
            data.setSummary("Scheduler");
        }

        if (verbosity>=1) {
            if (runCount > 0) {
                data.appendToSummary(", iteration "+(runCount + 1));
            }
            if (recentRun != null) {
                Duration start = Duration.sinceUtc(recentRun.getStartTimeUtc());
                data.appendToSummary(", last run "+start+" ago");
            }
            ScheduledFuture<?> nextScheduled = getNextScheduled();
            if (nextScheduled!=null) {
                if (nextScheduled.isDone() || nextScheduled.isCancelled()) {
                    data.appendToSummary(", not scheduled to run again");
                }
                Duration untilNext = Duration.millis(nextScheduled.getDelay(TimeUnit.MILLISECONDS));
                if (untilNext.isPositive())
                    data.appendToSummary(", next in "+untilNext);
                else
                    data.appendToSummary(", next imminent");
            } else {
                data.appendToSummary(", nothing scheduled");
            }
        }
    }
    
    @Override
    public boolean isDone(boolean andTaskNoLongerRunning) {
        boolean done = isCancelled() || (maxIterations!=null && maxIterations <= runCount) || (period==null && nextRun!=null && nextRun.isDone());
        if (andTaskNoLongerRunning) {
            return done && super.isDone(true);
        } else {
            return done;
        }
    }
    
    public synchronized void blockUntilFirstScheduleStarted() {
        // TODO Assumes that maxIterations is not negative!
        while (true) {
            if (isCancelled()) throw new CancellationException();
            if (recentRun==null)
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Throwables.propagate(e);
                }
            if (recentRun!=null) return;
        }
    }
    
    @Override
    public void blockUntilEnded() {
        while (!isDone()) super.blockUntilEnded();
    }

    /** @return The value of the most recently run task */
    @Override
    public Object get() throws InterruptedException, ExecutionException {
        blockUntilStarted();
        blockUntilFirstScheduleStarted();
        return (groovyTruth(recentRun)) ? recentRun.get() : internalFuture.get();
    }
    
    @Override
    protected boolean doCancel(org.apache.brooklyn.util.core.task.TaskInternal.TaskCancellationMode mode) {
        BrooklynLogging.log(BrooklynLoggingCategories.TASK_LIFECYCLE_LOG, LoggingLevel.DEBUG, "Cancelling scheduled task "+this);
        if (nextRun!=null) {
            ((TaskInternal<?>)nextRun).cancel(mode);
            try {
                ((TaskInternal<?>)nextRun).getJob().call();
                nextRun = null;
            } catch (CancellationException e) {
                // expected, ignore
            } catch (Exception e) {
                throw Exceptions.propagateAnnotated("Error cancelling scheduled task "+this, e);
            }
        }
        return super.doCancel(mode);
    }
    
    /**
     * Internal method used to allow callers to wait for underlying tasks to finished in the case of cancellation.
     * @param timeout maximum time to wait
     */
    @Beta
    public boolean blockUntilNextRunFinished(Duration timeout) {
        return Tasks.blockUntilInternalTasksEnded(nextRun, timeout);
    }
}
