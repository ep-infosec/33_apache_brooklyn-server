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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.Iterables;
import com.google.common.reflect.TypeToken;
import org.apache.brooklyn.api.mgmt.*;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.entity.Dumper;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.task.Tasks.ForTestingAndLegacyCompatibilityOnly.LegacyDeepResolutionMode;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.exceptions.ReferenceWithError;
import org.apache.brooklyn.util.repeat.Repeater;
import org.apache.brooklyn.util.time.CountdownTimer;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public class Tasks {
    
    private static final Logger log = LoggerFactory.getLogger(Tasks.class);
    
    /** convenience for setting "blocking details" on any task where the current thread is running;
     * typically invoked prior to a wait, for transparency to a user;
     * then invoked with 'null' just after the wait */
    public static String setBlockingDetails(String description) {
        Task<?> current = current();
        if (current instanceof TaskInternal)
            return ((TaskInternal<?>)current).setBlockingDetails(description);
        return null;
    }
    public static void resetBlockingDetails() {
        Task<?> current = current();
        if (current instanceof TaskInternal)
            ((TaskInternal<?>)current).resetBlockingDetails(); 
    }
    public static Task<?> setBlockingTask(Task<?> blocker) {
        Task<?> current = current();
        if (current instanceof TaskInternal)
            return ((TaskInternal<?>)current).setBlockingTask(blocker);
        return null;
    }
    public static void resetBlockingTask() {
        Task<?> current = current();
        if (current instanceof TaskInternal)
            ((TaskInternal<?>)current).resetBlockingTask(); 
    }

    public static <T> Task<T> create(String name, Callable<T> job) {
        return Tasks.<T>builder().displayName(name).body(job).build();
    }
    public static <T> Task<T> create(String name, Runnable job) {
        return Tasks.<T>builder().displayName(name).body(job).build();
    }

    /** convenience for setting "blocking details" on any task where the current thread is running,
     * while the passed code is executed; often used from groovy as
     * <pre>{@code withBlockingDetails("sleeping 5s") { Thread.sleep(5000); } }</pre>
     * If code block is null, the description is set until further notice (not cleareed). */
    @SuppressWarnings("rawtypes")
    public static <T> T withBlockingDetails(String description, Callable<T> code) throws Exception {
        Task current = current();
        if (code==null) {
            log.warn("legacy invocation of withBlockingDetails with null code block, ignoring");
            return null;
        }
        String prevBlockingDetails = null;
        if (current instanceof TaskInternal) {
            prevBlockingDetails = ((TaskInternal)current).setBlockingDetails(description);
        } 
        try {
            return code.call();
        } finally {
            if (current instanceof TaskInternal)
                ((TaskInternal)current).setBlockingDetails(prevBlockingDetails); 
        }
    }

    /** the {@link Task} where the current thread is executing, if executing in a Task, otherwise null;
     * if the current task is a proxy, this returns the target of that proxy */
    @SuppressWarnings("rawtypes")
    public static Task current() { 
        return getFinalProxyTarget(BasicExecutionManager.getPerThreadCurrentTask().get());
    }

    public static Task<?> getFinalProxyTarget(Task<?> task) {
        if (task==null) return null;
        Task<?> proxy = ((TaskInternal<?>)task).getProxyTarget();
        if (proxy==null || proxy.equals(task)) return task;
        return getFinalProxyTarget(proxy);
    }
    
    /** creates a {@link ValueResolver} instance which allows significantly more customization than
     * the various {@link #resolveValue(Object, Class, ExecutionContext)} methods here */
    public static <T> ValueResolver<T> resolving(Object v, TypeToken<T> type) {
        return new ValueResolver<>(v, type);
    }
    /** @see #resolving(Object, TypeToken) */
    public static <T> ValueResolver<T> resolving(Object v, Class<T> type) {
        return new ValueResolver<>(v, TypeToken.of(type));
    }

    public static ValueResolver.ResolverBuilderPretype resolving(Object v) {
        return new ValueResolver.ResolverBuilderPretype(v);
    }
    
    public static <T> ValueResolver<T> resolving(ConfigBag config, ConfigKey<T> key) {
        return new ValueResolver.ResolverBuilderPretype(config.getStringKey(key.getName())).as(key.getTypeToken());
    }

    /** @see #resolveValue(Object, TypeToken, ExecutionContext, String) */
    public static <T> T resolveValue(Object v, TypeToken<T> type, @Nullable ExecutionContext exec) throws ExecutionException, InterruptedException {
        return new ValueResolver<>(v, type).context(exec).get();
    }
    /** @see #resolveValue(Object, TypeToken, ExecutionContext) */
    public static <T> T resolveValue(Object v, Class<T> type, @Nullable ExecutionContext exec) throws ExecutionException, InterruptedException {
        return resolveValue(v, TypeToken.of(type), exec);
    }
    
    /** attempt to resolve the given value as the given type, waiting on futures, submitting if necessary,
     * and coercing as allowed by TypeCoercions;
     * contextMessage (optional) will be displayed in status reports while it waits (e.g. the name of the config key being looked up).
     * if no execution context supplied (null) this method will throw an exception if the object is an unsubmitted task */
    public static <T> T resolveValue(Object v, TypeToken<T> type, @Nullable ExecutionContext exec, String contextMessage) throws ExecutionException, InterruptedException {
        return new ValueResolver<>(v, type).context(exec).description(contextMessage).get();
    }
    /** @see #resolveValue(Object, TypeToken, ExecutionContext, String) */
    public static <T> T resolveValue(Object v, Class<T> type, @Nullable ExecutionContext exec, String contextMessage) throws ExecutionException, InterruptedException {
        return resolveValue(v, TypeToken.of(type), exec, contextMessage);
    }

    /** As @see #resolveDeepValueExactly(Object, TypeToken, ExecutionContext, String) but without any coercion
     * (ie resolving {@link DeferredSupplier} values only) */
    public static Object resolveDeepValueWithoutCoercion(Object v, ExecutionContext exec, String contextMessage) throws ExecutionException, InterruptedException {
        return new ValueResolver<>(v, TypeToken.of(Object.class)).context(exec).deep().description(contextMessage).get();
    }
    /** @see #resolveDeepValue(Object, Class, ExecutionContext, String) */
    public static Object resolveDeepValueWithoutCoercion(Object v, ExecutionContext exec) throws ExecutionException, InterruptedException {
        return resolveDeepValueWithoutCoercion(v, exec, null);
    }

    /** As @see #resolveDeepValueExactly(Object, TypeToken, ExecutionContext, String) except the type supplied here is
     * used to coerce non-map/iterable entries inside any encountered map/iterable:.
     *
     * For example, the following will return a list containing a map with "1": "true":
     *
     *   {@code Object result = resolveDeepValue(ImmutableList.of(ImmutableMap.of(1, true)), String.class, exec)}
     *
     * To perform a deep conversion of futures contained within a {@link Collection} or {@link Map} without coercion of each element,
     * the type should normally be Object, not the type of the collection. This differs from
     * {@link #resolveValue(Object, Class, ExecutionContext, String)} which will accept {@link Map} and {@link Collection}
     * as the required type.
     * @deprecated only really used when type is {@link Object} thus use {@link #resolveDeepValueWithoutCoercion(Object, ExecutionContext, String)} */
    @Deprecated
    public static Object resolveDeepValue(Object v, Class<?> type, ExecutionContext exec, String contextMessage) throws ExecutionException, InterruptedException {
        return new ValueResolver<>(v, TypeToken.of(type)).context(exec).deep(true, true, true).description(contextMessage).get();
    }
    /** @see #resolveDeepValue(Object, Class, ExecutionContext, String)
     * @deprecated only really used when type is {@link Object} thus use {@link #resolveDeepValueWithoutCoercion(Object, ExecutionContext)} */
    @Deprecated
    public static Object resolveDeepValue(Object v, Class<?> type, ExecutionContext exec) throws ExecutionException, InterruptedException {
        return resolveDeepValue(v, type, exec, null);
    }

    public static <T> T resolveValueShallow(Object v, TypeToken<T> type, @Nullable ExecutionContext exec, String contextMessage) throws ExecutionException, InterruptedException {
        return new ValueResolver<>(v, type).context(exec).description(contextMessage).deep(false, false, false).get();
    }

    @VisibleForTesting @Beta
    public static class ForTestingAndLegacyCompatibilityOnly {
        public static <T> T withLegacyDeepResolutionMode(LegacyDeepResolutionMode m, Callable<T> r) throws Exception {
            LegacyDeepResolutionMode old = LEGACY_DEEP_RESOLUTION_MODE;
            LEGACY_DEEP_RESOLUTION_MODE = m;
            try {
                return r.call();
            } finally {
                LEGACY_DEEP_RESOLUTION_MODE = old;
            }
        }
        public static void withLegacyDeepResolutionMode(LegacyDeepResolutionMode m, Runnable r) {
            LegacyDeepResolutionMode old = LEGACY_DEEP_RESOLUTION_MODE;
            LEGACY_DEEP_RESOLUTION_MODE = m;
            try {
                r.run();
            } finally {
                LEGACY_DEEP_RESOLUTION_MODE = old;
            }
        }

        @VisibleForTesting @Beta
        public static enum LegacyDeepResolutionMode {
            WARN, DISALLOW_LEGACY, ALLOW_LEGACY, ONLY_LEGACY
        }

        /** Being aggressive at breaking the legacy deep resolution mode, where a map is generic and the value doesn't fit even after coercion;
         * have confirmed the extent of these breakages is minor. And if it's a real problem, there is a way (by a plugin which changes this static),
         * for users to revert to the old behaviour.
         */
        @VisibleForTesting @Beta
        public static LegacyDeepResolutionMode LEGACY_DEEP_RESOLUTION_MODE = LegacyDeepResolutionMode.DISALLOW_LEGACY;
    }

    /**
     * Resolves the given object, blocking on futures and coercing it to the given type. If the object is a
     * map or iterable (or a list of map of maps, etc, etc) then walks these maps/iterables to convert all of
     * their values. This expects a type token parameterized with generics, and those generics
     * will be used to coerce the keys and entries.
     *
     * For example, the following will return a list containing a map with "1": Boolean.TRUE:
     *
     *   {@code Object result = resolveDeepValueCoerced(ImmutableList.of(ImmutableMap.of(1, "true")),
     *      new TypeToken<List<Map<String,Boolean>>>() {}, exec, "sample")}
     *
     * See also {@link #resolveDeepValueWithoutCoercion(Object, ExecutionContext, String)}
     */
    public static <T> T resolveDeepValueCoerced(Object value, TypeToken<T> type, ExecutionContext exec, String contextMessage) throws ExecutionException, InterruptedException {
        if (ValueResolver.supportsDeepResolution(value, null)) {
            Object resultO;
            try {
                if (ForTestingAndLegacyCompatibilityOnly.LEGACY_DEEP_RESOLUTION_MODE != LegacyDeepResolutionMode.ONLY_LEGACY) {
                    resultO = new ValueResolver<>(value, type).context(exec).deep().description(contextMessage).get();
                } else {
                    resultO = resolveDeepValueWithoutCoercion(value, exec, "Resolving deep "+ contextMessage);
                }
            } catch (Exception e) {
                if (ForTestingAndLegacyCompatibilityOnly.LEGACY_DEEP_RESOLUTION_MODE == LegacyDeepResolutionMode.DISALLOW_LEGACY) {
                    throw Exceptions.propagate(e);
                }
                resultO = resolveDeepValueWithoutCoercion(value, exec, "Resolving deep "+ contextMessage);
                if (ForTestingAndLegacyCompatibilityOnly.LEGACY_DEEP_RESOLUTION_MODE == LegacyDeepResolutionMode.WARN) {
                    log.warn("Conversion of '" + contextMessage + "' (" + value + ") to " + type + " failed; leaving as map/list type for legacy compatibility, but this feature may be removed in future");
                }
            }
            return (T) resultO;
        } else {
            return Tasks.resolveValue(value, type, exec, "Resolving "+ contextMessage);
        }
    }
    /** @deprecated use {@link #resolveDeepValueCoerced(Object, TypeToken, ExecutionContext, String)} */ @Deprecated
    public static <T> T resolveDeepValueExactly(Object v, TypeToken<T> type, ExecutionContext exec, String contextMessage) throws ExecutionException, InterruptedException {
        return resolveDeepValueCoerced(v, type, exec, contextMessage);

    }
    /** @deprecated use {@link #resolveDeepValueCoerced(Object, TypeToken, ExecutionContext, String)} */ @Deprecated
    public static <T> T resolveDeepValueExactly(Object v, TypeToken<T> type, ExecutionContext exec) throws ExecutionException, InterruptedException {
        return resolveDeepValueCoerced(v, type, exec, "(n/a)");
    }

    /** sets extra status details on the current task, if possible (otherwise does nothing).
     * the extra status is presented in Task.getStatusDetails(true)
     */
    public static void setExtraStatusDetails(String notes) {
        Task<?> current = current();
        if (current instanceof TaskInternal)
            ((TaskInternal<?>)current).setExtraStatusText(notes); 
    }

    public static <T> TaskBuilder<T> builder() {
        return TaskBuilder.<T>builder();
    }

    /** Convenience for {@link TaskBuilder#of(String, Runnable)} for basic tasks.
     * See also {@link DynamicTasks#of(String, Runnable)}. */
    public static Task<Void> of(String name, Runnable body) {
        return TaskBuilder.of(name, body).dynamic(false).build();
    }
    /** As {@link #of(String, Runnable)} where the task returns a result. */
    public static <T> Task<T> of(String name, Callable<T> body) {
        return TaskBuilder.of(name, body).dynamic(false).build();
    }
    
    private static Task<?>[] asTasks(TaskAdaptable<?> ...tasks) {
        Task<?>[] result = new Task<?>[tasks.length];
        for (int i=0; i<tasks.length; i++)
            result[i] = tasks[i].asTask();
        return result;
    }

    public static Task<List<?>> parallel(TaskAdaptable<?> ...tasks) {
        return parallelInternal("parallelised tasks", asTasks(tasks));
    }
    public static Task<List<?>> parallel(String name, TaskAdaptable<?> ...tasks) {
        return parallelInternal(name, asTasks(tasks));
    }
    public static Task<List<?>> parallel(Iterable<? extends TaskAdaptable<?>> tasks) {
        return parallel(asTasks(Iterables.toArray(tasks, TaskAdaptable.class)));
    }
    public static Task<List<?>> parallel(String name, Iterable<? extends TaskAdaptable<?>> tasks) {
        return parallelInternal(name, asTasks(Iterables.toArray(tasks, TaskAdaptable.class)));
    }
    private static Task<List<?>> parallelInternal(String name, Task<?>[] tasks) {
        return Tasks.<List<?>>builder().displayName(name).parallel(true).add(tasks).build();
    }

    public static Task<List<?>> sequential(TaskAdaptable<?> ...tasks) {
        return sequentialInternal("sequential tasks", asTasks(tasks));
    }
    public static Task<List<?>> sequential(String name, TaskAdaptable<?> ...tasks) {
        return sequentialInternal(name, asTasks(tasks));
    }
    public static TaskFactory<?> sequential(TaskFactory<?> ...taskFactories) {
        return sequentialInternal("sequential tasks", taskFactories);
    }
    public static TaskFactory<?> sequential(String name, TaskFactory<?> ...taskFactories) {
        return sequentialInternal(name, taskFactories);
    }
    public static Task<List<?>> sequential(List<? extends TaskAdaptable<?>> tasks) {
        return sequential(asTasks(Iterables.toArray(tasks, TaskAdaptable.class)));
    }
    public static Task<List<?>> sequential(String name, List<? extends TaskAdaptable<?>> tasks) {
        return sequential(name, asTasks(Iterables.toArray(tasks, TaskAdaptable.class)));
    }
    private static Task<List<?>> sequentialInternal(String name, Task<?>[] tasks) {
        return Tasks.<List<?>>builder().displayName(name).dynamic(false).parallel(false).add(tasks).build();
    }
    private static TaskFactory<?> sequentialInternal(final String name, final TaskFactory<?> ...taskFactories) {
        return new TaskFactory<TaskAdaptable<?>>() {
            @Override
            public TaskAdaptable<?> newTask() {
                TaskBuilder<List<?>> tb = Tasks.<List<?>>builder().displayName(name).parallel(false);
                for (TaskFactory<?> tf: taskFactories)
                    tb.add(tf.newTask().asTask());
                return tb.build();
            }
        };
    }

    /** returns the first tag found on the given task which matches the given type, looking up the submission hierarachy if necessary */
    @SuppressWarnings("unchecked")
    public static <T> T tag(@Nullable Task<?> task, Class<T> type, boolean recurseHierarchy) {
        // support null task to make it easier for callers to walk hierarchies
        if (task==null) return null;
        for (Object tag: TaskTags.getTagsFast(task))
            if (type.isInstance(tag)) return (T)tag;
        if (!recurseHierarchy) return null;
        return tag(task.getSubmittedByTask(), type, true);
    }
    
    public static boolean isAncestorCancelled(Task<?> t) {
        return isAncestor(t, Task::isCancelled);
    }

    public static boolean isAncestor(Task<?> t, Predicate<Task<?>> test) {
        if (t==null) return false;
        if (test.apply(t)) return true;
        return isAncestor(t.getSubmittedByTask(), test);
    }

    public static boolean isQueued(TaskAdaptable<?> task) {
        return ((TaskInternal<?>)task.asTask()).isQueued();
    }

    public static boolean isSubmitted(TaskAdaptable<?> task) {
        return ((TaskInternal<?>)task.asTask()).isSubmitted();
    }
    
    public static boolean isQueuedOrSubmitted(TaskAdaptable<?> task) {
        return ((TaskInternal<?>)task.asTask()).isQueuedOrSubmitted();
    }
    
    /**
     * Adds the given task to the given context. Does not throw an exception if the addition fails or would fail.
     * @return true if the task was added, false otherwise including if context is null or thread is interrupted.
     */
    public static boolean tryQueueing(TaskQueueingContext adder, TaskAdaptable<?> task) {
        if (task==null || adder==null || isQueued(task) || Thread.currentThread().isInterrupted())
            return false;
        try {
            adder.queue(task.asTask());
            return true;
        } catch (Exception e) {
            if (log.isDebugEnabled())
                log.debug("Could not add task "+task+" at "+adder+": "+e);
            return false;
        }        
    }
    
    /** see also {@link #resolving(Object)} which gives much more control about submission, timeout, etc */
    public static <T> Supplier<T> supplier(final TaskAdaptable<T> task) {
        return new Supplier<T>() {
            @Override
            public T get() {
                return task.asTask().getUnchecked();
            }
        };
    }
    
    /** return all children tasks of the given tasks, if it has children, else empty list */
    public static Iterable<Task<?>> children(Task<?> task) {
        if (task instanceof HasTaskChildren)
            return ((HasTaskChildren)task).getChildren();
        return Collections.emptyList();
    }
    
    /** returns failed tasks */
    public static Iterable<Task<?>> failed(Iterable<Task<?>> subtasks) {
        return Iterables.filter(subtasks, new Predicate<Task<?>>() {
            @Override
            public boolean apply(Task<?> input) {
                return input.isError();
            }
        });
    }
    
    /** returns the task, its children, and all its children, and so on;
     * @param root task whose descendants should be iterated
     * @param parentFirst whether to put parents before children or after
     */
    public static Iterable<Task<?>> descendants(Task<?> root, final boolean parentFirst) {
        Iterable<Task<?>> descs = Iterables.concat(Iterables.transform(Tasks.children(root), new Function<Task<?>,Iterable<Task<?>>>() {
            @Override
            public Iterable<Task<?>> apply(Task<?> input) {
                return descendants(input, parentFirst);
            }
        }));
        if (parentFirst) return Iterables.concat(Collections.singleton(root), descs);
        else return Iterables.concat(descs, Collections.singleton(root));
    }

    /** returns the error thrown by the task if {@link Task#isError()}, or null if no error or not done */
    public static Throwable getError(Task<?> t) {
        return getError(t, true);
    }

    public static Throwable getError(Task<?> t, boolean preferCancellationException) {
        if (t==null) return null;
        if (!t.isDone()) return null;
        if (preferCancellationException && t.isCancelled()) return new CancellationException();
        try {
            t.get();
        } catch (Throwable error) {
            // do not propagate as we are pretty much guaranteed above that it wasn't this
            // thread which originally threw the error
            return error;
        }

        if (t.isCancelled()) return new CancellationException();

        return null;
    }
    public static Task<Void> fail(final String name, final Throwable optionalError) {
        return Tasks.<Void>builder().dynamic(false).displayName(name).body(new Runnable() { @Override public void run() { 
            if (optionalError!=null) throw Exceptions.propagate(optionalError); else throw new RuntimeException("Failed: "+name);
        } }).build();
    }
    public static Task<Void> warning(final String message, final Throwable optionalError) {
        return warning(message, optionalError, true);
    }
    public static Task<Void> warning(final String message, final Throwable optionalError, boolean logWarning) {
        if (logWarning) log.warn(message);
        return TaskTags.markInessential(fail(message, optionalError));
    }

    /** marks the current task inessential; this mainly matters if the task is running in a parent
     * {@link TaskQueueingContext} and we don't want the parent to fail if this task fails
     * <p>
     * no-op (silently ignored) if not in a task */
    public static void markInessential() {
        Task<?> task = Tasks.current();
        if (task==null) {
            TaskQueueingContext qc = DynamicTasks.getTaskQueuingContext();
            if (qc!=null) task = qc.asTask();
        }
        if (task!=null) {
            TaskTags.markInessential(task);
        }
    }
    
    /** causes failures in subtasks of the current task not to fail the parent;
     * no-op if not in a {@link TaskQueueingContext}.
     * <p>
     * essentially like a {@link #markInessential()} on all tasks in the current 
     * {@link TaskQueueingContext}, including tasks queued subsequently */
    @Beta
    public static void swallowChildrenFailures() {
        Preconditions.checkNotNull(DynamicTasks.getTaskQueuingContext(), "Task queueing context required here");
        TaskQueueingContext qc = DynamicTasks.getTaskQueuingContext();
        if (qc!=null) {
            qc.swallowChildrenFailures();
        }
    }

    /** as {@link TaskTags#addTagDynamically(TaskAdaptable, Object)} but for current task, skipping if no current task */
    public static void addTagDynamically(Object tag) {
        Task<?> t = Tasks.current();
        if (t!=null) TaskTags.addTagDynamically(t, tag);
    }
    
    /** 
     * Workaround for limitation described at {@link Task#cancel(boolean)};
     * internal method used to allow callers to wait for underlying tasks to finished in the case of cancellation.
     * <p> 
     * It is irritating that {@link FutureTask} sync's object clears the runner thread, 
     * so even if {@link BasicTask#getInternalFuture()} is used, there is no means of determining if the underlying object is done.
     * The {@link Task#getEndTimeUtc()} seems the only way.
     *  
     * @return true if tasks ended; false if timed out
     **/ 
    @Beta
    public static boolean blockUntilInternalTasksEnded(Task<?> t, Duration timeout) {
        CountdownTimer timer = timeout.countdownTimer();
        
        if (t==null)
            return true;
        
        if (t instanceof ScheduledTask) {
            boolean result = ((ScheduledTask)t).blockUntilNextRunFinished(timer.getDurationRemaining());
            if (!result) return false;
        }

        t.blockUntilEnded(timer.getDurationRemaining());
        
        while (true) {
            if (t.getEndTimeUtc()>=0) return true;
            // above should be sufficient; but just in case, trying the below
            Thread tt = t.getThread();
            if (t instanceof ScheduledTask) {
                ((ScheduledTask)t).blockUntilNextRunFinished(timer.getDurationRemaining());
                return true;
            } else {
                if (tt==null || !tt.isAlive()) {
                    if (!t.isCancelled()) {
                        // may happen for a cancelled task, interrupted after submit but before start
                        log.warn("Internal task thread is dead or null ("+tt+") but task not ended: "+t.getEndTimeUtc()+" ("+t+")");
                    }
                    return true;
                }
            }
            if (timer.isExpired())
                return false;
            Time.sleep(Repeater.DEFAULT_REAL_QUICK_PERIOD);
        }
    }

    public static boolean isChildOfSubmitter(Task<?> task, Function<String,Task> parentLookupFunction) {
        String submittedById = task.getSubmittedByTaskId();
        if (submittedById!=null) {
            Task<?> submittedBy = parentLookupFunction.apply(submittedById);
            if (submittedBy != null && submittedBy instanceof HasTaskChildren) {
                if (Iterables.contains(((HasTaskChildren) submittedBy).getChildren(), task)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** returns true if either the current thread or the current task is interrupted/cancelled */
    public static boolean isInterrupted() {
        if (Thread.currentThread().isInterrupted()) return true;
        Task<?> t = current();
        if (t==null) return false;
        return t.isCancelled();
    }

    private static class WaitForRepeaterCallable implements Callable<Boolean> {
        protected Repeater repeater;
        protected boolean requireTrue;

        public WaitForRepeaterCallable(Repeater repeater, boolean requireTrue) {
            this.repeater = repeater;
            this.requireTrue = requireTrue;
        }

        @Override
        public Boolean call() {
            ReferenceWithError<Boolean> result;
            Tasks.setBlockingDetails(repeater.getDescription());
            try {
               result = repeater.runKeepingError();
            } finally {
                Tasks.resetBlockingDetails();
            }

            if (Boolean.TRUE.equals(result.getWithoutError()))
                return true;
            if (result.hasError()) 
                throw Exceptions.propagate(result.getError());
            if (requireTrue)
                throw new IllegalStateException("timeout - "+repeater.getDescription());
            return false;
        }
    }

    /** @return a {@link TaskBuilder} which tests whether the repeater terminates with success in its configured timeframe,
     * returning true or false depending on whether repeater succeed */
    public static TaskBuilder<Boolean> testing(Repeater repeater) {
        return Tasks.<Boolean>builder().body(new WaitForRepeaterCallable(repeater, false))
            .displayName("waiting for condition")
            .description("Testing whether " + getTimeoutString(repeater) + ": "+repeater.getDescription());
    }

    /** @return a {@link TaskBuilder} which requires that the repeater terminate with success in its configured timeframe,
     * throwing if it does not */
    public static TaskBuilder<?> requiring(Repeater repeater) {
        return Tasks.<Boolean>builder().body(new WaitForRepeaterCallable(repeater, true))
            .displayName("waiting for condition")
            .description("Requiring " + getTimeoutString(repeater) + ": " + repeater.getDescription());
    }
    
    private static String getTimeoutString(Repeater repeater) {
        Duration timeout = repeater.getTimeLimit();
        if (timeout==null || Duration.PRACTICALLY_FOREVER.equals(timeout))
            return "eventually";
        return "in "+timeout;
    }


    /**
     * @deprecated since 1.0.0; instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Task<?> t) {
        Dumper.dumpInfo(t);
    }
    
    /**
     * @deprecated since 1.0.0; instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Task<?> t, Writer out) throws IOException {
        Dumper.dumpInfo(t, out);
    }
    
    /**
     * @deprecated since 1.0.0; instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Task<?> t, String currentIndentation, String tab) throws IOException {
        dumpInfo(t, new PrintWriter(System.out), currentIndentation, tab);
    }
    
    /**
     * @deprecated since 1.0.0; instead use {@link Dumper} methods
     */
    @Deprecated
    public static void dumpInfo(Task<?> t, Writer out, String currentIndentation, String tab) throws IOException {
        out.append(currentIndentation+t+": "+t.getStatusDetail(false)+"\n");

        if (t instanceof HasTaskChildren) {
            for (Task<?> child: ((HasTaskChildren)t).getChildren()) {
                dumpInfo(child, out, currentIndentation+tab, tab);
            }
        }
        out.flush();
    }
}
