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
package org.apache.brooklyn.api.mgmt;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;

import org.apache.brooklyn.util.time.Duration;

import com.google.common.util.concurrent.ListenableFuture;

/**
 * Represents a unit of work for execution.
 *
 * When used with an {@link ExecutionManager} or {@link ExecutionContext} it will record submission time,
 * execution start time, end time, and any result. A task can be submitted to the ExecutionManager or
 * ExecutionContext, in which case it will be returned, or it may be created by submission
 * of a {@link Runnable} or {@link Callable} and thereafter it can be treated just like a {@link Future}.
 */
public interface Task<T> extends ListenableFuture<T>, TaskAdaptable<T> {
    
    public String getId();
    
    public Set<Object> getTags();
    /** if {@link #isSubmitted()} returns the time when the task was submitted; or -1 otherwise */
    public long getSubmitTimeUtc();
    /** if {@link #isBegun()} returns the time when the task was starts;
     * guaranteed to be >= {@link #getSubmitTimeUtc()} > 0 if started, or -1 otherwise */
    public long getStartTimeUtc();
    /** if {@link #isDone()} (for any reason) returns the time when the task ended;
     * guaranteed to be >= {@link #getStartTimeUtc()} > 0 if ended, or -1 otherwise */
    public long getEndTimeUtc();
    public String getDisplayName();
    public String getDescription();
    
    /** task which submitted this task, if was submitted by a task */
    public Task<?> getSubmittedByTask();
    /** task which submitted this task, if was submitted by a task */
    public String getSubmittedByTaskId();

    /** The thread where the task is running, if it is running. */
    public Thread getThread();

    /**
     * Whether task has been submitted
     *
     * Submitted tasks are normally expected to start running then complete,
     * but unsubmitted tasks are sometimes passed around for someone else to submit them.
     */
    public boolean isSubmitted();

    /**
     * Whether task has started running.
     *
     * Will remain true after normal completion or non-cancellation error.
     * will be true on cancel iff the thread did actually start.
     */
    public boolean isBegun();

    /**
     * Whether the task threw an error, including cancellation (implies {@link #isDone()})
     */
    public boolean isError();

    /**
     * As {@link Future#isDone()}. In particular if cancelled, this will return true
     * as soon as it is cancelled. The thread for this task may still be running,
     * if the cancellation (often an interruption, but may be weaker) has not applied,
     * and submitted threads may also be running depending on cancellation parameters.
     * <p>
     * {@link #get()} is guaranteed to return immediately, throwing in the case of cancellation
     * prior to completion (and including the case above where a thread may still be running).
     * <p>
     * To check whether cancelled threads for this task have completed, use {@link #isDone(boolean))}. 
     * inspect {@link #getEndTimeUtc()}, which is guaranteed to be set when threads complete
     * if the thread is started (as determinable by whether {@link #getStartTimeUtc()} is set).
     * (The threads of submitted/child tasks will usually be independent; to determine their
     * completion requires inspecting the {@link ExecutionManager}.)  
     */
    @Override
    public boolean isDone();

    /**
     * As {@link #isDone()}, identical if the argument is false, but by supplying {@code true} 
     * this will also check {@link #getEndTimeUtc()} if {@link #isBegun()}
     * to guarantee that the task is no longer running.
     * {@link #isDone()} will return true for cancelled tasks even if they are still running.
     * <p>
     * In a task hierarchy, the threads of tasks submitted by this may still be ongoing.
     * To determine their completion, inspect the {@link ExecutionManager}.
     *   
     * @param andTaskHasEnded
     * @return
     */
    public boolean isDone(boolean andTaskNotRunning);
    
    /**
     * Causes calling thread to block until the task is started.
     */
    public void blockUntilStarted();

    /**
     * Causes calling thread to block until the task is ended.
     * <p>
     * Either normally or by cancellation or error, but without throwing error on cancellation or error.
     * (Errors are logged at debug.)
     */
    public void blockUntilEnded();

    /**
     * As {@link #blockUntilEnded()}, but returning after the given timeout;
     * true if the task has ended and false otherwise
     */
    public boolean blockUntilEnded(Duration timeout);

    /**
     * As {@link #blockUntilEnded(Duration)} and {@link #isDone(boolean)}
     */
    public boolean blockUntilEnded(Duration timeout, boolean andTaskNotRunning);

    public String getStatusSummary();

    /**
     * Returns detailed status, suitable for a hover.
     *
     * Plain-text format, with new-lines (and sometimes extra info) if multiline enabled.
     */
    public String getStatusDetail(boolean multiline);

    /** As {@link #get(long, java.util.concurrent.TimeUnit)} */
    public T get(Duration duration) throws InterruptedException, ExecutionException, TimeoutException;
    
    /** As {@link #get()}, but propagating checked exceptions as unchecked for convenience. */
    public T getUnchecked();

    /** As {@link #get()}, but propagating checked exceptions as unchecked for convenience
     * (including a {@link TimeoutException} if the duration expires) */
    public T getUnchecked(Duration duration);

    /** As {@link Future#cancel(boolean)}. Note that {@link #isDone()} and {@link #blockUntilEnded(Duration)} return immediately
     * once a task is cancelled, consistent with the underlying {@link FutureTask} behaviour.  
     * TODO Fine-grained control over underlying jobs, e.g. to ensure anything represented by this task is actually completed,
     * is not (yet) publicly exposed. See the convenience method blockUntilInternalTasksEnded in the Tasks set of helpers
     * for more discussion. */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning);
    
}
