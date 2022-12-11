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
package org.apache.brooklyn.core.mgmt;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.common.collect.ImmutableMap;
import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.mgmt.ExecutionContext;
import org.apache.brooklyn.api.mgmt.ExecutionManager;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.mgmt.entitlement.EntitlementContext;
import org.apache.brooklyn.api.objs.EntityAdjunct;
import org.apache.brooklyn.core.config.Sanitizer;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.mgmt.internal.AbstractManagementContext;
import org.apache.brooklyn.core.objs.AbstractEntityAdjunct;
import org.apache.brooklyn.core.workflow.WorkflowExecutionContext;
import org.apache.brooklyn.core.workflow.WorkflowStepInstanceExecutionContext;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.task.BasicExecutionContext;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.TaskTags;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.javalang.MemoryUsageTracker;
import org.apache.brooklyn.util.stream.Streams;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.Beta;
import com.google.common.base.Functions;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;

/** Provides utilities for making Tasks easier to work with in Brooklyn.
 * Main thing at present is to supply (and find) wrapped entities for tasks to understand the
 * relationship of the entity to the task.
 * <p>
 * Eventually it may be better to replace these 'tags' on Tasks with strongly typed context objects.
 * (Tags there are used mainly for determining who called it (caller), what they called it on (target entity),
 * and what type of task it is (effector, schedule/sensor, etc).)
 */
public class BrooklynTaskTags extends TaskTags {

    private static final Logger log = LoggerFactory.getLogger(BrooklynTaskTags.class);

    /** Tag for tasks which are running on behalf of the management server, rather than any entity */
    public static final String BROOKLYN_SERVER_TASK_TAG = "BROOKLYN-SERVER";

    /** Tag for a task which should be treated as a top-level task, for the purpose of listing */
    public static final Object TOP_LEVEL_TASK = "TOP-LEVEL";
    /** Tag for a task which represents entity initialization */
    public static final Object ENTITY_INITIALIZATION = "INITIALIZATION";
    /** Tag for a task which represents entity destruction */
    public static final Object ENTITY_DESTRUCTION = "DESTRUCTION";
    /** Tag for a task which represents an effector */
    public static final String EFFECTOR_TAG = "EFFECTOR";
    /** Tag for a task which represents a sensor being published */
    public static final String SENSOR_TAG = "SENSOR";
    /** Tag for a task which represents a workflow or workflow step; look for a WorkflowTaskTag object to disambiguate */
    public static final String WORKFLOW_TAG = "WORKFLOW";
    /** Tag for a task which *is* interesting, in contrast to {@link #TRANSIENT_TASK_TAG} */
    public static final String NON_TRANSIENT_TASK_TAG = "NON-TRANSIENT";
    /** indicates a task is transient, roughly that is to say it is uninteresting -- 
     * specifically this means it can be GC'd as soon as it is completed, 
     * and that it need not appear in some task lists;
     * often used for framework lifecycle events and sensor polling */
    public static final String TRANSIENT_TASK_TAG = "TRANSIENT";
    /** marks that a task is meant to return immediately, without blocking (or if absolutely necessary blocking for a short while) */
    public static final String IMMEDIATE_TASK_TAG = "IMMEDIATE";

    public static final String ERROR_HANDLED_BY_TASK_TAG = "ERROR_HANDLED_BY";

    // ------------- entity tags -------------------------
    
    public abstract static class WrappedItem<T> {
        /** @deprecated since 1.0.0 going private; use {@link #getWrappingType()} */
        @Deprecated
        public final String wrappingType;
        protected WrappedItem(String wrappingType) {
            Preconditions.checkNotNull(wrappingType);
            this.wrappingType = wrappingType;
        }
        public abstract T unwrap();
        public String getWrappingType() {
            return wrappingType;
        }
        @Override
        public String toString() {
            return "Wrapped["+getWrappingType()+":"+unwrap()+"]";
        }
        @Override
        public int hashCode() {
            return Objects.hashCode(unwrap(), getWrappingType());
        }
        @Override
        public boolean equals(Object obj) {
            if (this==obj) return true;
            if (!(obj instanceof WrappedItem)) return false;
            return 
                Objects.equal(unwrap(), ((WrappedItem<?>)obj).unwrap()) &&
                Objects.equal(getWrappingType(), ((WrappedItem<?>)obj).getWrappingType());
        }
    }
    public static class WrappedEntity extends WrappedItem<Entity> {
        /** @deprecated since 1.0.0 going private; use {@link #unwrap()} */
        @Deprecated
        public final Entity entity;
        protected WrappedEntity(String wrappingType, Entity entity) {
            super(wrappingType);
            this.entity = Preconditions.checkNotNull(entity);
        }
        @Override
        public Entity unwrap() {
            return entity;
        }
    }
    public static class WrappedObject<T> extends WrappedItem<T> {
        private final T object;
        protected WrappedObject(String wrappingType, T object) {
            super(wrappingType);
            this.object = Preconditions.checkNotNull(object);
        }
        @Override
        public T unwrap() {
            return object;
        }        
    }
    
    public static final String CONTEXT_ENTITY = "contextEntity";
    public static final String CALLER_ENTITY = "callerEntity";
    public static final String TARGET_ENTITY = "targetEntity";
    
    public static final String CONTEXT_ADJUNCT = "contextAdjunct";
    
    /**
     * Marks a task as running in the context of the entity. This means
     * resolving any relative/context sensitive values against that entity.
     * Using the entity in APIs where it is implicit - a prominent example
     * being {@link DynamicTasks}.
     *
     * The result from the call should be used only when reading tags (for example
     * to compare whether the tag already exists). The only place where the value is
     * added to the entity tags is {@link AbstractManagementContext#getExecutionContext(Entity)}.
     */
    public static WrappedEntity tagForContextEntity(Entity entity) {
        return new WrappedEntity(CONTEXT_ENTITY, entity);
    }
    
    public static WrappedEntity tagForCallerEntity(Entity entity) {
        return new WrappedEntity(CALLER_ENTITY, entity);
    }
    
    public static WrappedEntity tagForTargetEntity(Entity entity) {
        return new WrappedEntity(TARGET_ENTITY, entity);
    }

    /**
     * As {@link #tagForContextEntity(Entity)} but wrapping an adjunct.
     * Tasks with this tag will also have a {@link #tagForContextEntity(Entity)}.
     */
    public static WrappedObject<EntityAdjunct> tagForContextAdjunct(EntityAdjunct adjunct) {
        return new WrappedObject<>(CONTEXT_ADJUNCT, adjunct);
    }
    

    public static WrappedEntity getWrappedEntityTagOfType(Task<?> t, String wrappingType) {
        if (t==null) return null;
        return getWrappedEntityTagOfType( getTagsFast(t), wrappingType);
    }
    public static WrappedEntity getWrappedEntityTagOfType(Collection<?> tags, String wrappingType) {
        if (tags==null) return null;
        for (Object x: tags)
            if ((x instanceof WrappedEntity) && ((WrappedEntity)x).wrappingType.equals(wrappingType))
                return (WrappedEntity)x;
        return null;
    }
    
    @SuppressWarnings("unchecked")
    public static <T> WrappedObject<T> getWrappedObjectTagOfType(Collection<?> tags, String wrappingType, Class<T> type) {
        if (tags==null) return null;
        for (Object x: tags)
            if ((x instanceof WrappedObject) && ((WrappedObject<?>)x).wrappingType.equals(wrappingType) && type.isInstance( ((WrappedObject<?>)x).object ))
                return (WrappedObject<T>)x;
        return null;
    }
    public static <T> T getUnwrappedObjectTagOfType(Collection<?> tags, String wrappingType, Class<T> type) {
        WrappedObject<T> result = getWrappedObjectTagOfType(tags, wrappingType, type);
        return result!=null ? result.unwrap() : null;
    }

    public static Entity getWrappedEntityOfType(Task<?> t, String wrappingType) {
        WrappedEntity wrapper = getWrappedEntityTagOfType(t, wrappingType);
        return (wrapper == null) ? null : wrapper.entity;
    }
    public static Entity getWrappedEntityOfType(Collection<?> tags, String wrappingType) {
        WrappedEntity wrapper = getWrappedEntityTagOfType(tags, wrappingType);
        return (wrapper == null) ? null : wrapper.entity;
    }

    public static Entity getContextEntity(Task<?> task) {
        return getWrappedEntityOfType(task, CONTEXT_ENTITY);
    }
    public static EntityAdjunct getContextEntityAdjunct(Task<?> task, boolean recursively) {
        WrappedObject<EntityAdjunct> result = getWrappedObjectTagOfType(getTagsFast(task), CONTEXT_ADJUNCT, EntityAdjunct.class);
        if (result==null) {
            if (recursively && task!=null) {
                return getContextEntityAdjunct(task.getSubmittedByTask(), recursively);
            }
            return null;
        }
        return result.object;
    }

    public static Object getTargetOrContextEntityTag(Task<?> task) {
        if (task == null) return null;
        Object result = getWrappedEntityTagOfType(task, CONTEXT_ENTITY);
        if (result!=null) return result;
        result = getWrappedEntityTagOfType(task, TARGET_ENTITY);
        if (result!=null) return result;
        result = Tasks.tag(task, Entity.class, false);
        if (result!=null) return result;
        
        return null;
    }
    
    public static Entity getTargetOrContextEntity(Task<?> t) {
        if (t==null) return null;
        Entity result = getWrappedEntityOfType(t, CONTEXT_ENTITY);
        if (result!=null) return result;
        result = getWrappedEntityOfType(t, TARGET_ENTITY);
        if (result!=null) {
            log.warn("Context entity found by looking at target entity tag, not context entity");
            return result;
        }
        
        result = Tasks.tag(t, Entity.class, false);
        if (result!=null) {
            log.warn("Context entity found by looking at 'Entity' tag, not wrapped entity");
        }
        return result;
    }
    
    public static Set<Task<?>> getTasksInEntityContext(ExecutionManager em, Entity e) {
        return em.getTasksWithTag(tagForContextEntity(e));
    }

    public static Set<Task<?>> getTasksInAdjunctContext(ExecutionManager em, EntityAdjunct a) {
        return em.getTasksWithTag(tagForContextAdjunct(a));
    }

    public static ManagementContext getManagementContext(Task<?> task) {
        if (task==null) return null;
        for (Object tag : getTagsFast(task))
            if ((tag instanceof ManagementContext))
                return (ManagementContext) tag;
        return getManagementContext(task.getSubmittedByTask());
    }

    // ------------- stream tags -------------------------

    public static class WrappedStream {
        public final String streamType;
        public final Supplier<String> streamContents;
        public final Supplier<Integer> streamSize;
        protected WrappedStream(String streamType, Supplier<String> streamContents, Supplier<Integer> streamSize) {
            Preconditions.checkNotNull(streamType);
            Preconditions.checkNotNull(streamContents);
            this.streamType = streamType;
            this.streamContents = streamContents;
            this.streamSize = streamSize != null ? streamSize : Suppliers.<Integer>ofInstance(streamContents.get().length());
        }
        protected WrappedStream(String streamType, ByteArrayOutputStream stream) {
            Preconditions.checkNotNull(streamType);
            Preconditions.checkNotNull(stream);
            this.streamType = streamType;
            this.streamContents = Strings.toStringSupplier(stream);
            this.streamSize = Streams.sizeSupplier(stream);
        }
        // fix for https://github.com/FasterXML/jackson-databind/issues/543 (which also applies to codehaus jackson)
        @JsonProperty
        public Integer getStreamSize() {
            return streamSize.get();
        }
        // there is a stream call on Activity REST api which accesses streamContent.get() directly;
        // so when serializing the tag, abbreviate things
        @JsonProperty("streamContents")
        public String getStreamContentsAbbreviated() {
            // TODO would be nice to just get the first 80 chars but that's a refactoring
            // which might affect persistence.  if stream is very large (100MB+) then we sometimes
            // get OOME without it, so let's abbreviate
            if (streamSize.get()>8192) {
                return "<contents-too-large>";
            }
            return Strings.maxlenWithEllipsis(streamContents.get(), 80);
        }
        @Override
        public String toString() {
            return "Stream["+streamType+"/"+Strings.makeSizeString(streamSize.get())+"]";
        }
        @Override
        public int hashCode() {
            return Objects.hashCode(streamContents, streamType);
        }
        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof WrappedStream)) return false;
            return 
                Objects.equal(streamContents, ((WrappedStream)obj).streamContents) &&
                Objects.equal(streamType, ((WrappedStream)obj).streamType);
        }
    }
    
    public static final String STREAM_STDIN = "stdin";
    public static final String STREAM_STDOUT = "stdout";
    public static final String STREAM_STDERR = "stderr";
    /** not a stream, but inserted with the same mechanism */
    public static final String STREAM_ENV = "env";
    
    private static final Maybe<ByteArrayOutputStream> STREAM_GARBAGE_COLLECTED_MAYBE = Maybe.of(Streams.byteArrayOfString("<contents-garbage-collected>"));

    /** creates a tag suitable for marking a stream available on a task */
    public static WrappedStream tagForStream(String streamType, ByteArrayOutputStream stream) {
        return new WrappedStream(streamType, stream);
    }
    /** creates a tag suitable for marking a stream available on a task, but which might be GC'd */
    // TODO only make it soft if/when stream exceeds a given size eg 1kb ?
    public static WrappedStream tagForStreamSoft(String streamType, ByteArrayOutputStream stream) {
        MemoryUsageTracker.SOFT_REFERENCES.track(stream, stream.size());
        Maybe<ByteArrayOutputStream> softStream = Maybe.softThen(stream, STREAM_GARBAGE_COLLECTED_MAYBE);
        return new WrappedStream(streamType,
            Suppliers.compose(Functions.toStringFunction(), softStream),
            Suppliers.compose(Streams.sizeFunction(), softStream));
    }

    /** creates a tag suitable for marking a stream available on a task */
    public static WrappedStream tagForStream(String streamType, Supplier<String> contents, Supplier<Integer> size) {
        return new WrappedStream(streamType, contents, size);
    }
    
    /**
     * Creates a tag suitable for attaching a snapshot of an environment var map as a "stream" on a task; mainly for use
     * with STREAM_ENV. Sensitive data like passwords is always masked, see {@link Sanitizer#IS_SECRET_PREDICATE}.
     *
     * @param streamEnv Never used
     * @param env The {@link Map} with environment variables
     * @return The {@link WrappedStream}
     * */
    public static WrappedStream tagForEnvStream(String streamEnv, Map<?, ?> env) {
        StringBuilder sb = new StringBuilder();
        Sanitizer.sanitizeMapToString(env, sb);
        // TODO also make soft - this is often larger than the streams themselves
        return BrooklynTaskTags.tagForStream(BrooklynTaskTags.STREAM_ENV, Streams.byteArrayOfString(sb.toString()));
    }

    /** returns the set of tags indicating the streams available on a task */
    public static Set<WrappedStream> streams(Task<?> task) {
        Set<WrappedStream> result = new LinkedHashSet<BrooklynTaskTags.WrappedStream>();
        for (Object tag: getTagsFast(task)) {
            if (tag instanceof WrappedStream) {
                result.add((WrappedStream)tag);
            }
        }
        return ImmutableSet.copyOf(result);
    }

    /** returns the tag for the indicated stream, or null */
    public static WrappedStream stream(Task<?> task, String streamType) {
        if (task==null) return null;
        for (Object tag: getTagsFast(task))
            if ((tag instanceof WrappedStream) && ((WrappedStream)tag).streamType.equals(streamType))
                return (WrappedStream)tag;
        return null;
    }

    // ------ misc
    
    public static <TR,T extends Task<TR>> T setInessential(T task) { return addTagDynamically(task, INESSENTIAL_TASK); }
    public static <TR,T extends Task<TR>> T setTransient(T task) { return addTagDynamically(task, TRANSIENT_TASK_TAG); }
    public static boolean isTransient(Task<?> task) { 
        if (hasTag(task, TRANSIENT_TASK_TAG)) return true;
        if (hasTag(task, NON_TRANSIENT_TASK_TAG)) return false;
        if (task.getSubmittedByTask()!=null) return isTransient(task.getSubmittedByTask());
        return false;
    }
    public static boolean isSubTask(Task<?> task) { return hasTag(task, SUB_TASK_TAG); }
    public static boolean isEffectorTask(Task<?> task) { return hasTag(task, EFFECTOR_TAG); }
    
    // ------ effector tags
    
    public static class EffectorCallTag {
        protected final String entityId;
        protected final String effectorName;
        protected Map<String,Object> effectorParams;
        protected transient ConfigBag parameters;
        protected EffectorCallTag(String entityId, String effectorName, ConfigBag parameters) {
            this.entityId = checkNotNull(entityId, "entityId");
            this.effectorName = checkNotNull(effectorName, "effectorName");
            setParameters(parameters);
        }
        @Override
        public String toString() {
            return EFFECTOR_TAG+"@"+entityId+":"+effectorName;
        }
        @Override
        public int hashCode() {
            return Objects.hashCode(entityId, effectorName);
        }
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof EffectorCallTag)) return false;
            EffectorCallTag other = (EffectorCallTag) obj;
            return 
                Objects.equal(entityId, other.entityId) && 
                Objects.equal(effectorName, other.effectorName) &&
                Objects.equal(effectorParams, other.effectorParams);
        }
        public String getEntityId() {
            return entityId;
        }
        public String getEffectorName() {
            return effectorName;
        }
        /** contains parameters including typed keys, for use during invocation; not serialized (via REST API) */
        public ConfigBag getParameters() {
            return parameters;
        }
        public void setParameters(ConfigBag parameters) {
            this.parameters = parameters;
            effectorParams = MutableMap.copyOf(parameters.getAllConfig());
        }
        /** contains parameters as a map, for use during invocation; serialized (in REST API) */
        public Map<String, Object> getEffectorParams() {
            return effectorParams;
        }
    }
    
    public static EffectorCallTag tagForEffectorCall(Entity entity, String effectorName, ConfigBag parameters) {
        return new EffectorCallTag(entity.getId(), effectorName, parameters);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WorkflowTaskTag {
        protected String applicationId;
        protected String entityId;
        protected String workflowId;

        protected Integer stepIndex;
        // TODO handle these in the UI:
        protected String supersededByTaskId;
        protected String errorHandlerForTask;
        protected Integer errorHandlerIndex;  //same as below if set; we don't need it
        protected Integer subStepIndex;

        public String getApplicationId() {
            return applicationId;
        }

        public String getEntityId() {
            return entityId;
        }

        public String getWorkflowId() {
            return workflowId;
        }

        /** null if it is the task for the overall workflow or sub-workflow*/
        public Integer getStepIndex() {
            return stepIndex;
        }

        /** if not null, this sub-workflow has been superseded, ie replayed in a different workflow;
         * where possible, this refers to the sub-workflow that replaces it; in other cases it refers to the parent task who replaces it */
        public String getSupersededByTaskId() {
            return supersededByTaskId;
        }
        public void setSupersededByTaskId(String supersededByTaskId) {
            this.supersededByTaskId = supersededByTaskId;
        }

        public Integer getErrorHandlerIndex() {
            return errorHandlerIndex;
        }

        @Override
        public String toString() {
            return "WorkflowTaskTag{" +
                    "applicationId='" + applicationId + '\'' +
                    ", entityId='" + entityId + '\'' +
                    ", workflowId='" + workflowId + '\'' +
                    ", stepIndex=" + stepIndex +
                    ", supersededByTaskId='" + supersededByTaskId + '\'' +
                    ", errorHandlerForTask='" + errorHandlerForTask + '\'' +
                    ", errorHandlerIndex=" + errorHandlerIndex +
                    '}';
        }
    }

    public static WorkflowTaskTag tagForWorkflow(WorkflowExecutionContext workflow) {
        WorkflowTaskTag t = new WorkflowTaskTag();
        t.applicationId = workflow.getEntity().getApplicationId();
        t.entityId = workflow.getEntity().getId();
        t.workflowId = workflow.getWorkflowId();
        return t;
    }
    public static WorkflowTaskTag tagForWorkflow(WorkflowStepInstanceExecutionContext workflowStep) {
        WorkflowTaskTag t = tagForWorkflow(workflowStep.getWorkflowExectionContext());
        t.stepIndex = workflowStep.getStepIndex();
        return t;
    }
    public static WorkflowTaskTag tagForWorkflowSubStep(WorkflowStepInstanceExecutionContext parentStep, int subStepIndex) {
        WorkflowTaskTag t = tagForWorkflow(parentStep.getWorkflowExectionContext());
        t.stepIndex = parentStep.getStepIndex();
        t.subStepIndex = subStepIndex;
        return t;
    }

    public static WorkflowTaskTag tagForWorkflowStepErrorHandler(WorkflowStepInstanceExecutionContext workflowStep, Integer errorHandlerIndex, String errorHandlerForTask) {
        WorkflowTaskTag t = tagForWorkflow(workflowStep.getWorkflowExectionContext());
        t.stepIndex = workflowStep!=null ? workflowStep.getStepIndex() : null;
        t.subStepIndex = errorHandlerIndex;
        t.errorHandlerIndex = errorHandlerIndex;
        t.errorHandlerForTask = errorHandlerForTask;
        if (Strings.isBlank(t.errorHandlerForTask)) t.errorHandlerForTask = "task-unavailable";  // ensure not null
        return t;
    }
    public static WorkflowTaskTag tagForWorkflowStepErrorHandler(WorkflowExecutionContext context) {
        WorkflowTaskTag t = tagForWorkflow(context);
        t.errorHandlerForTask = context.getTaskId();
        if (Strings.isBlank(t.errorHandlerForTask)) t.errorHandlerForTask = "task-unavailable";  // ensure not null
        return t;
    }
    public static Map<String,String> tagForErrorHandledBy(Task<?> handler) {
        return ImmutableMap.of(ERROR_HANDLED_BY_TASK_TAG, handler.getId());
    }

    /**
     * checks if the given task is part of the given effector call on the given entity;
     * @param task  the task to check (false if null)
     * @param entity  the entity where this effector task should be running, or any entity if null
     * @param effector  the effector (matching name) where this task should be running, or any effector if null
     * @param allowNestedEffectorCalls  whether to match ancestor effector calls, e.g. if eff1 calls eff2,
     *   and we are checking eff2, whether to match eff1
     * @return whether the given task is part of the given effector
     */
    public static boolean isInEffectorTask(Task<?> task, @Nullable Entity entity, @Nullable Effector<?> effector, boolean allowNestedEffectorCalls) {
        Task<?> t = task;
        while (t!=null) {
            Set<Object> tags = t.getTags();
            if (tags.contains(EFFECTOR_TAG)) {
                for (Object tag: tags) {
                    if (tag instanceof EffectorCallTag) {
                        EffectorCallTag et = (EffectorCallTag)tag;
                        if (entity!=null && !et.getEntityId().equals(entity.getId()))
                            continue;
                        if (effector!=null && !et.getEffectorName().equals(effector.getName()))
                            continue;
                        return true;
                    }
                }
                if (!allowNestedEffectorCalls) return false;
            }
            t = t.getSubmittedByTask();
        }
        return false;
    }

    /**
     * finds the task up the {@code child} hierarchy handling the {@code effector} call,
     * returns null if one doesn't exist. 
     */
    @Beta
    public static Task<?> getClosestEffectorTask(Task<?> child, Effector<?> effector) {
        Task<?> t = child;
        while (t != null) {
            Set<Object> tags = t.getTags();
            if (tags.contains(EFFECTOR_TAG)) {
                for (Object tag: tags) {
                    if (tag instanceof EffectorCallTag) {
                        EffectorCallTag et = (EffectorCallTag) tag;
                        if (effector != null && !et.getEffectorName().equals(effector.getName()))
                            continue;
                        return t;
                    }
                }
            }
            t = t.getSubmittedByTask();
        }
        return null;
    }

    /** finds the first {@link EffectorCallTag} tag on this tag, or optionally on submitters, or null */
    public static EffectorCallTag getEffectorCallTag(Task<?> task, boolean recurse) {
        Task<?> t = task;
        while (t!=null) {
            for (Object tag: getTagsFast(task)) {
                if (tag instanceof EffectorCallTag)
                    return (EffectorCallTag)tag;
            }
            if (!recurse)
                return null;
            t = t.getSubmittedByTask();
        }
        return null;
    }

    public static WorkflowTaskTag getWorkflowTaskTag(Task<?> task, boolean recurse) {
        Task<?> t = task;
        while (t!=null) {
            for (Object tag: getTagsFast(task)) {
                if (tag instanceof WorkflowTaskTag)
                    return (WorkflowTaskTag)tag;
            }
            if (!recurse)
                return null;
            t = t.getSubmittedByTask();
        }
        return null;
    }

    /** finds the first {@link EffectorCallTag} tag on this tag or a submitter, and returns the effector name */
    public static String getEffectorName(Task<?> task) {
        EffectorCallTag result = getEffectorCallTag(task, true);
        return (result == null) ? null : result.getEffectorName();
    }

    public static ConfigBag getEffectorParameters(Task<?> task) {
        EffectorCallTag result = getEffectorCallTag(task, true);
        return (result == null) ? null : result.getParameters();
    }

    public static ConfigBag getCurrentEffectorParameters() {
        return getEffectorParameters(Tasks.current());
    }
    
    public static void setEffectorParameters(Task<?> task, ConfigBag parameters) {
        EffectorCallTag result = getEffectorCallTag(task, true);
        if (result == null) {
            throw new IllegalStateException("No EffectorCallTag found, is the task an effector? Task: " + task);
        }
        result.setParameters(parameters);
    }
    // ---------------- entitlement tags ----------------
    
    public static class EntitlementTag {
        private EntitlementContext entitlementContext;
    }

    public static EntitlementContext getEntitlement(Task<?> task) {
        if (task==null) return null;
        return getEntitlement(getTagsFast(task));
    }
    
    public static EntitlementContext getEntitlement(Collection<?> tags) {
        if (tags==null) return null;
        for (Object tag: tags) {
            if (tag instanceof EntitlementTag) {
                return ((EntitlementTag)tag).entitlementContext;
            }
        }
        return null;
    }
    
    public static EntitlementContext getEntitlement(EntitlementTag tag) {
        if (tag==null) return null;
        return tag.entitlementContext;
    }
    
    public static EntitlementTag tagForEntitlement(EntitlementContext context) {
        EntitlementTag tag = new EntitlementTag();
        tag.entitlementContext = context;
        return tag;
    }

    public static ExecutionContext getExecutionContext(Collection<?> tags) {
        EntityAdjunct ea = getUnwrappedObjectTagOfType(tags, CONTEXT_ADJUNCT, EntityAdjunct.class);
        if (ea instanceof AbstractEntityAdjunct) {
            return ((AbstractEntityAdjunct)ea).getExecutionContext();
        }
        Entity e = getWrappedEntityOfType(tags, CONTEXT_ENTITY);
        if (e==null) e= getWrappedEntityOfType(tags, TARGET_ENTITY);
        if (e instanceof EntityInternal) {
            return ((EntityInternal)e).getExecutionContext();
        }
        
        return null;
    }
    public static ExecutionContext getExecutionContext(Task<?> t) {
        return getExecutionContext(getTagsFast(t));
    }
    /** 
     * This should always be identical to {@link BasicExecutionContext#getCurrentExecutionContext()} as
     * there are two ways to derive the {@link ExecutionContext}:  one is the {@link ThreadLocal} accessed from the above method
     * and set by {@link BasicExecutionContext} submissions; the other is by looking at {@link #getTargetOrContextEntity(Task)}
     * and {@link #CONTEXT_ADJUNCT} tags on the current task.  As {@link BasicExecutionContext} also sets those tags,
     * the only time the two will be different is if {@link ExecutionManager#submit(org.apache.brooklyn.api.mgmt.TaskAdaptable)}
     * is used supplying one or more of these context tags, in which case only this method will determine the execution context.
     */
    public static ExecutionContext getCurrentExecutionContext() {
        Task<?> t = Tasks.current();
        ExecutionContext result = t!=null ? getExecutionContext(t) : null;
        if (result==null) result = BasicExecutionContext.getCurrentExecutionContext();
        return result;
    }
    
}
