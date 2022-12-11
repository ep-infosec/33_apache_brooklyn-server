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

import java.util.List;
import java.util.concurrent.Callable;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.mgmt.ExecutionContext;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.mgmt.TaskAdaptable;
import org.apache.brooklyn.api.mgmt.TaskFactory;
import org.apache.brooklyn.api.mgmt.TaskQueueingContext;
import org.apache.brooklyn.api.mgmt.TaskWrapper;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.Beta;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;

/** 
 * Contains static methods which detect and use the current {@link TaskQueueingContext} to execute tasks.
 * <p>
 * Queueing is supported by some task contexts (eg {@link DynamicSequentialTask}) to let that task
 * build up a complex sequence of tasks and logic. This utility class gives conveniences to allow:
 * <p>
 * <li> "queue-if-possible-else-submit-async", so that it is backgrounded, using queueing semantics if available;
 * <li> "queue-if-possible-else-submit-blocking", so that it is in the queue if there is one, else it will complete synchronously;
 * <li> "queue-if-possible-else-submit-and-in-both-cases-block", so that it is returned immediately, but waits in its queue if there is one.
 * <p>
 * Over time the last mode has been the most prevalent and {@link #get(TaskAdaptable)} is introduced here
 * as a convenience.  If a timeout is desired then the first should be used.
 * 
 * @since 0.6.0
 */
@Beta
public class DynamicTasks {

    private static final Logger log = LoggerFactory.getLogger(DynamicTasks.class);
    private static final ThreadLocal<TaskQueueingContext> taskQueueingContext = new ThreadLocal<TaskQueueingContext>();
    
    public static void setTaskQueueingContext(TaskQueueingContext newTaskQC) {
        taskQueueingContext.set(newTaskQC);
    }
    
    public static TaskQueueingContext getThreadTaskQueuingContext() {
        return taskQueueingContext.get();
    }
    
    public static TaskQueueingContext getTaskQueuingContext() {
        TaskQueueingContext adder = getThreadTaskQueuingContext();
        if (adder!=null) return adder;
        Task<?> t = Tasks.current();
        if (t instanceof TaskQueueingContext) return (TaskQueueingContext) t;
        return null;
    }

    
    public static void removeTaskQueueingContext() {
        taskQueueingContext.remove();
    }

    /** Convenience for {@link TaskBuilder#of(String, Runnable)} creating a {@link DynamicSequentialTask} instance.
     * See also {@link Tasks#of(String, Runnable)}. */
    public static Task<Void> of(String name, Runnable body) {
        return TaskBuilder.of(name, body).dynamic(true).build();
    }
    /** As {@link #of(String, Runnable)} where the task returns a result. */
    public static <T> Task<T> of(String name, Callable<T> body) {
        return TaskBuilder.of(name, body).dynamic(true).build();
    }

    public static class TaskQueueingResult<T> implements TaskWrapper<T> {
        private final Task<T> task;
        private final boolean wasQueued;
        private ExecutionContext execContext = null;
        
        private TaskQueueingResult(TaskAdaptable<T> task, boolean wasQueued) {
            this.task = task.asTask();
            this.wasQueued = wasQueued;
        }
        @Override
        public Task<T> asTask() {
            return task;
        }
        @Override
        public Task<T> getTask() {
            return task;
        }
        /** returns true if the task was queued */
        public boolean wasQueued() {
            return wasQueued;
        }
        /** returns true if the task either is currently queued or has been submitted */
        public boolean isQueuedOrSubmitted() {
            return wasQueued || Tasks.isQueuedOrSubmitted(task);
        }
        /** specifies an execContext to use if the task has to be explicitly submitted;
         * if omitted it will attempt to find one based on the current thread's context */
        public TaskQueueingResult<T> executionContext(ExecutionContext execContext) {
            this.execContext = execContext;
            return this;
        }
        /** as {@link #executionContext(ExecutionContext)} but inferring from the entity */
        public TaskQueueingResult<T> executionContext(Entity entity) {
            this.execContext = ((EntityInternal)entity).getExecutionContext();
            return this;
        }
        private boolean orSubmitInternal(boolean samethread) {
            if (!wasQueued()) {
                if (isQueuedOrSubmitted()) {
                    log.warn("Redundant call to execute "+getTask()+"; skipping");
                    return false;
                } else {
                    ExecutionContext ec = execContext;
                    if (ec==null)
                        ec = BasicExecutionContext.getCurrentExecutionContext();
                    if (ec==null)
                        throw new IllegalStateException("Cannot execute "+getTask()+" without an execution context; ensure caller is in an ExecutionContext");
                    if (samethread) ec.get(getTask());
                    else ec.submit(getTask());
                    return true;
                }
            } else {
                return false;
            }
        }
        /** Causes the task to be submitted (asynchronously) if it hasn't already been,
         * such as if a previous {@link DynamicTasks#queueIfPossible(TaskAdaptable)} did not have a queueing context.
         * <p>
         * An {@link #executionContext(ExecutionContext)} should typically have been set
         * (or use {@link #orSubmitAsync(Entity)}).
         */
        public TaskQueueingResult<T> orSubmitAsync() {
            orSubmitInternal(false);
            return this;
        }
        /** Convenience for setting {@link #executionContext(Entity)} then {@link #orSubmitAsync()}. */
        public TaskQueueingResult<T> orSubmitAsync(Entity entity) {
            executionContext(entity);
            return orSubmitAsync();
        }
        /** Alternative to {@link #orSubmitAsync()} but where, if the submission is needed
         * (usually because a previous {@link DynamicTasks#queueIfPossible(TaskAdaptable)} did not have a queueing context)
         * it will wait until execution completes (and in fact will execute the task in this thread,
         * as per {@link ExecutionContext#get(TaskAdaptable)}. 
         * <p>
         * If the task is already queued, this method does nothing, not even blocks,
         * to permit cases where a caller is building up a set of tasks to be executed sequentially:
         * with a queueing context the caller can line them all up, but without that the caller needs this task
         * finished before submitting subsequent tasks. 
         * <p>
         * If blocking is desired in all cases and this call should fail on task failure, invoke {@link #andWaitForSuccess()} on the result,
         * or consider using {@link DynamicTasks#get(TaskAdaptable)} instead of this method,
         * or {@link DynamicTasks#get(TaskAdaptable, Entity)} if an execuiton context a la {@link #orSubmitAndBlock(Entity)} is needed. */
        public TaskQueueingResult<T> orSubmitAndBlock() {
            orSubmitInternal(true);
            return this;
        }
        /** Variant of {@link #orSubmitAndBlock()} doing what {@link #orSubmitAsync(Entity)} does for {@link #orSubmitAsync()}. */
        public TaskQueueingResult<T> orSubmitAndBlock(Entity entity) {
            executionContext(entity);
            return orSubmitAndBlock();
        }
        /** Blocks for the task to be completed, throwing if there are any errors
         * and otherwise returning the value.
         * <p>
         * In addition to cases where a result is wanted, this is needed in any context where subsequent commands assume the task has completed.
         * not needed in a context where the task is simply being built up and queued.
         * <p>
         * 
         */
        public T andWaitForSuccess() {
            return task.getUnchecked();
        }
        public void orCancel() {
            if (!wasQueued()) {
                task.cancel(false);
            }
        }
    }
    
    /**
     * Tries to add the task to the current addition context if there is one, otherwise does nothing.
     * <p/>
     * Call {@link TaskQueueingResult#orSubmitAsync()} on the returned
     * {@link TaskQueueingResult TaskQueueingResult} to handle execution of tasks in a
     * {@link BasicExecutionContext}.
     */
    public static <T> TaskQueueingResult<T> queueIfPossible(TaskAdaptable<T> task) {
        return new TaskQueueingResult<T>(task, Tasks.tryQueueing(getTaskQueuingContext(), task));
    }

    /** @see #queueIfPossible(TaskAdaptable) */
    public static <T> TaskQueueingResult<T> queueIfPossible(TaskFactory<? extends TaskAdaptable<T>> task) {
        return queueIfPossible(task.newTask());
    }

    /** adds the given task to the nearest task addition context,
     * either set as a thread-local, or in the current task, or the submitter of the task, etc
     * <p>
     * throws if it cannot add or addition/execution would fail including if calling thread is interrupted */
    public static <T> Task<T> queueInTaskHierarchy(Task<T> task) {
        Preconditions.checkNotNull(task, "Task to queue cannot be null");
        Preconditions.checkState(!Tasks.isQueuedOrSubmitted(task), "Task to queue must not yet be submitted: {}", task);
        
        if (Tasks.tryQueueing(getTaskQueuingContext(), task)) {
            log.debug("Queued task {} at context {} (no hierarchy)", task, getTaskQueuingContext());
            return task;
        }
        
        Task<?> t = Tasks.current();        
        while (t!=null) {
            if (t instanceof TaskQueueingContext) {
                if (Tasks.tryQueueing((TaskQueueingContext)t, task)) {
                    log.debug("Queued task {} at hierarchical context {}", task, t);
                    return task;
                }
            }
            t = t.getSubmittedByTask();
        }
        
        throw new IllegalStateException("No task addition context available in current task hierarchy for adding task "+task);
    }

    /**
     * Queues the given task.
     * <p/>
     * This method is only valid within a dynamic task. Use {@link #queueIfPossible(TaskAdaptable)}
     * and {@link TaskQueueingResult#orSubmitAsync()} if the calling context is a basic task.
     *
     * @param task The task to queue
     * @throws IllegalStateException if no task queueing context is available
     * @return The queued task
     */
    public static <V extends TaskAdaptable<?>> V queue(V task) {
        try {
            Preconditions.checkNotNull(task, "Task to queue cannot be null");
            Preconditions.checkState(!Tasks.isQueued(task), "Task to queue must not yet be queued: %s", task);
            TaskQueueingContext adder = getTaskQueuingContext();
            if (adder==null) {
                throw new IllegalStateException("Task "+task+" cannot be queued here; no queueing context available");
            }
            adder.queue(task.asTask());
            return task;
        } catch (Throwable e) {
            log.warn("Error queueing "+task+" (rethrowing): "+e);
            throw Exceptions.propagate(e);
        }
    }

    /** @see #queue(org.apache.brooklyn.api.mgmt.TaskAdaptable)  */
    public static void queue(TaskAdaptable<?> task1, TaskAdaptable<?> task2, TaskAdaptable<?> ...tasks) {
        queue(task1);
        queue(task2);
        for (TaskAdaptable<?> task: tasks) queue(task);
    }

    /** @see #queue(org.apache.brooklyn.api.mgmt.TaskAdaptable)  */
    public static <T extends TaskAdaptable<?>> T queue(TaskFactory<T> taskFactory) {
        return queue(taskFactory.newTask());
    }

    /** @see #queue(org.apache.brooklyn.api.mgmt.TaskAdaptable)  */
    public static void queue(TaskFactory<?> task1, TaskFactory<?> task2, TaskFactory<?> ...tasks) {
        queue(task1.newTask());
        queue(task2.newTask());
        for (TaskFactory<?> task: tasks) queue(task.newTask());
    }

    /** @see #queue(org.apache.brooklyn.api.mgmt.TaskAdaptable)  */
    public static <T> Task<T> queue(String name, Callable<T> job) {
        return DynamicTasks.queue(Tasks.create(name, job));
    }

    /** @see #queue(org.apache.brooklyn.api.mgmt.TaskAdaptable)  */
    public static <T> Task<T> queue(String name, Runnable job) {
        return DynamicTasks.queue(Tasks.<T>create(name, job));
    }

    /** queues the task if needed, i.e. if it is not yet submitted (so it will run), 
     * or if it is submitted but not queued and we are in a queueing context (so it is available for informational purposes) */
    public static <T extends TaskAdaptable<?>> T queueIfNeeded(T task) {
        if (!Tasks.isQueued(task)) {
            if (Tasks.isSubmitted(task) && getTaskQueuingContext()==null) {
                // already submitted and not in a queueing context, don't try to queue
            } else {
                // needs submitting, put it in the queue
                // (will throw an error if we are not a queueing context)
                queue(task);
            }
        }
        return task;
    }
    
    /** submits/queues the given task if needed, and gets the result (unchecked) */
    public static <T> T get(TaskAdaptable<T> t) {
        return queueIfPossible(t).orSubmitAndBlock().andWaitForSuccess();
    }

    /** As {@link #drain(Duration, boolean)} waiting forever and throwing the first error 
     * (excluding errors in inessential tasks),
     * then returning the last task in the queue (which is guaranteed to have finished without error,
     * if this method returns without throwing) */
    public static Task<?> waitForLast() {
        drain(null, true);
        // this call to last is safe, as the above guarantees everything will have run
        // (on errors the above will throw so we won't come here)
        List<Task<?>> q = DynamicTasks.getTaskQueuingContext().getQueue();
        return q.isEmpty() ? null : Iterables.getLast(q);
    }
    
    /** Calls {@link TaskQueueingContext#drain(Duration, boolean, boolean)} on the current task context */
    public static TaskQueueingContext drain(Duration optionalTimeout, boolean throwFirstError) {
        TaskQueueingContext qc = DynamicTasks.getTaskQueuingContext();
        Preconditions.checkNotNull(qc, "Cannot drain when there is no queueing context");
        qc.drain(optionalTimeout, false, throwFirstError);
        return qc;
    }

    /** as {@link Tasks#swallowChildrenFailures()} but requiring a {@link TaskQueueingContext}. */
    @Beta
    public static void swallowChildrenFailures() {
        Preconditions.checkNotNull(DynamicTasks.getTaskQueuingContext(), "Task queueing context required here");
        Tasks.swallowChildrenFailures();
    }

    /** same as {@link Tasks#markInessential()}
     * (but included here for convenience as it is often used in conjunction with {@link DynamicTasks}) */
    public static void markInessential() {
        Tasks.markInessential();
    }

    /** queues the task if possible, otherwise submits it asynchronously; returns the task for callers to 
     * {@link Task#getUnchecked()} or {@link Task#blockUntilEnded()} */
    public static <T> Task<T> submit(TaskAdaptable<T> task, Entity entity) {
        return queueIfPossible(task).orSubmitAsync(entity).asTask();
    }
    
    /** queues the task if possible and waits for the result, otherwise executes synchronously as per {@link ExecutionContext#get(TaskAdaptable)} */
    public static <T> T get(TaskAdaptable<T> task, Entity e) {
        return queueIfPossible(task).orSubmitAndBlock(e).andWaitForSuccess();
    }

    /** Breaks the parent-child relation between Tasks.current() and the task passed,
     *  making the new task a top-level one at the target entity.
     *  To make it visible in the UI, also tag the task with:
     *    .tag(BrooklynTaskTags.tagForContextEntity(entity))
     *    .tag(BrooklynTaskTags.NON_TRANSIENT_TASK_TAG)
     */
    public static <T> Task<T> submitTopLevelTask(TaskAdaptable<T> task, Entity entity) {
        Task<?> currentTask = BasicExecutionManager.getPerThreadCurrentTask().get();
        BasicExecutionManager.getPerThreadCurrentTask().set(null);
        try {
            return Entities.submit(entity, task).asTask();
        } finally {
            BasicExecutionManager.getPerThreadCurrentTask().set(currentTask);
        }
    }

}
