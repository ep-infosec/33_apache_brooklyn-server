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
package org.apache.brooklyn.policy.failover;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.Group;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.objs.BrooklynObject;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.sensor.EnricherSpec;
import org.apache.brooklyn.api.sensor.Sensor;
import org.apache.brooklyn.api.sensor.SensorEvent;
import org.apache.brooklyn.api.sensor.SensorEventListener;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.effector.Effectors;
import org.apache.brooklyn.core.entity.AbstractEntity;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.entity.StartableApplication;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.mgmt.BrooklynTaskTags;
import org.apache.brooklyn.core.policy.AbstractPolicy;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.entity.group.DynamicGroup;
import org.apache.brooklyn.policy.failover.ElectPrimaryEffector.ResultCode;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.QuorumCheck.QuorumChecks;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.exceptions.UserFacingException;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.Beta;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.reflect.TypeToken;

/**

The ElectPrimaryPolicy acts to keep exactly one of its children or members as primary, promoting and demoting them when required.

<p>
A simple use case is where we have two children, call them North and South, and we wish for North to be primary.  If North fails, however, we want to promote and fail over to South.  This can be done by:

<ul>
<li> adding this policy at the parent
<li> setting ` ha.primary.weight` on North
<li> optionally defining `promote` on North and South (if action is required there to promote it)
<li> observing the `primary` sensor to see which is primary
<li> optionally setting `propagate.primary.sensors: [ main.uri ]` to publish `main.uri` from whichever of North or South is active
<li> optionally setting `primary.selection.mode: best` to switch back to North if it comes back online
</ul>

The policy works by listening for service-up changes in the target pool (children or members) and listening for `ha.primary.weight` sensor values from those elements.  
On any change, it invokes an effector to perform the primary election.  
By default, the effector invoked is `electPrimary`, but this can be changed with the `primary.election.effector` config key.  If this effector does not exist, the policy will add a default behaviour using `ElectPrimaryEffector`.  Details of the election are described in that effector, but to summarize, it will find an appropriate primary from the target pool and publish a sensor indicating who the new primary is.
If the effector is not defined this policy will add one with the standard election behaviour
({@link ElectPrimaryEffector}).    
That effector will also invoke `promote` and `demote` on the relevant entities.

<p>
All the `primary.*` parameters accepted by that effector can be defined on this policy 
and will be passed to the effector, along with an `event` parameter indicating the sensor which triggered the election.

<p>
If no quorum.up or quorum.running is set on the entity, both will be set to a constant 1.

 */
// TODO support configurable parallelisation of promote/demote (in the code)
// TODO more propagation, effectors, and "all-but" semantics (comments in PPE, though we might want a separate effector propagator)
@Beta
public class ElectPrimaryPolicy extends AbstractPolicy implements ElectPrimaryConfig {

    private static final Logger log = LoggerFactory.getLogger(ElectPrimaryPolicy.class);
    
    public static ConfigKey<String> EFFECTOR_NAME = ConfigKeys.newStringConfigKey("primary.election.effector",
        "The effector to invoke to perform the scan; if not set, it will use electPrimary and create if necessary",
        "electPrimary");
    
    @SuppressWarnings("serial")
    public static ConfigKey<Collection<?>> PROPAGATE_PRIMARY_SENSORS = ConfigKeys.newConfigKey(
        new TypeToken<Collection<?>>() {}, 
        "propagate.primary.sensors");

    private final transient List<String> rescanTriggers = MutableList.of();
    private transient boolean rescanInProgress = false;

    @Override
    public void setEntity(@SuppressWarnings("deprecation") org.apache.brooklyn.api.entity.EntityLocal entity) {
        super.setEntity(entity);

        if (Entities.isReadOnly(entity)) {
            // don't run in hot standby
            return;
        }
        
        checkAndMaybeAddEffector(entity);
        checkQuorums(entity);
        
        Collection<? extends Object> sensorsToPropagate = config().get(PROPAGATE_PRIMARY_SENSORS);
        if (sensorsToPropagate!=null) {
            List<Sensor<?>> realSensors = MutableList.of();
            for (Object s: sensorsToPropagate) {
                if (s instanceof String) s = Sensors.newSensor(Object.class, (String) s);
                if (s instanceof Sensor) {
                    realSensors.add((Sensor<?>)s);
                } else {
                    throw new IllegalArgumentException("Config "+PROPAGATE_PRIMARY_SENSORS.getName()+" had invalid entry "
                        + "'"+s+"'; expected string or sensor");
                }
            }
            entity.enrichers().add(EnricherSpec.create(PropagatePrimaryEnricher.class)
                .configure(PropagatePrimaryEnricher.PROPAGATING, realSensors));
        }
        
        addSubscriptions(entity);
        rescanRequest("policy initialization");
    }

    protected void checkAndMaybeAddEffector(@SuppressWarnings("deprecation") org.apache.brooklyn.api.entity.EntityLocal entity) {
        String effName = config().get(EFFECTOR_NAME);
        if (((EntityInternal)entity).getEffector(effName)==null) {
            // effector not defined
            if (config().getRaw(EFFECTOR_NAME).isAbsent()) {
                log.debug("No effector '"+effName+"' present at "+entity+"; creating default");
                // if not set, we can create the default; passing more config than is strictly necessary,
                // wasteful as this config will be passed to the ssh commands,
                // but that shouldn't normally be a problem; and if it is, caller can create the effector themselves
                new ElectPrimaryEffector(config().getBag()).apply(entity);
                
            } else {
                // otherwise it's an error, fail
                throw new IllegalStateException("No such effector '"+effName+"' on "+entity);
            }
        }
    }

    private void checkQuorums(Entity entity) {
        // set all quorums to 1 if not explicitly set
        if ( ((EntityInternal)entity).config().getRaw(StartableApplication.UP_QUORUM_CHECK).isAbsent() ) {
            entity.config().set(StartableApplication.UP_QUORUM_CHECK, QuorumChecks.newInstance(1, 0.0, false));
        }
        if ( ((EntityInternal)entity).config().getRaw(StartableApplication.RUNNING_QUORUM_CHECK).isAbsent() ) {
            entity.config().set(StartableApplication.RUNNING_QUORUM_CHECK, QuorumChecks.newInstance(1, 0.0, false));
        }
    }

    protected void addSubscriptions(Entity entity) {
        String weightSensorName = config().get(PRIMARY_WEIGHT_NAME);
        TargetMode target = config().get(TARGET_MODE);
        if (target==TargetMode.AUTO) target =  entity instanceof Group ? TargetMode.MEMBERS : TargetMode.CHILDREN;

        highlightTriggers("Listening for "+weightSensorName+" and service up, state on all " + target.name().toLowerCase());
        
        Change<Entity> candidateSetChange = new Change<Entity>();
        Change<Boolean> candidateUpChange = new Change<Boolean>();
        Change<Lifecycle> candidateLifecycleChange = new Change<Lifecycle>();
        Change<Number> candidateWeightChange = new Change<Number>();
        AttributeSensor<Number> weightSensor = Sensors.newSensor(Number.class, weightSensorName);
        
        switch (target) {
        case CHILDREN:
            subscriptions().subscribe(entity, AbstractEntity.CHILD_ADDED, candidateSetChange);
            subscriptions().subscribe(entity, AbstractEntity.CHILD_REMOVED, candidateSetChange);
            subscriptions().subscribeToChildren(entity, Attributes.SERVICE_UP, candidateUpChange);
            subscriptions().subscribeToChildren(entity, Attributes.SERVICE_STATE_ACTUAL, candidateLifecycleChange);
            subscriptions().subscribeToChildren(entity, weightSensor, candidateWeightChange);
            break;
        case MEMBERS:
            subscriptions().subscribe(entity, DynamicGroup.MEMBER_ADDED, candidateSetChange);
            subscriptions().subscribe(entity, DynamicGroup.MEMBER_REMOVED, candidateSetChange);
            subscriptions().subscribeToMembers(((Group)entity), Attributes.SERVICE_UP, candidateUpChange);
            subscriptions().subscribeToMembers(((Group)entity), Attributes.SERVICE_STATE_ACTUAL, candidateLifecycleChange);
            subscriptions().subscribeToMembers(((Group)entity), weightSensor, candidateWeightChange);
            break;
        default:
            throw new IllegalArgumentException("Unexpected target mode "+target);
        }
    }

    public class Change<T> implements SensorEventListener<T> {
        public void onEvent(SensorEvent<T> event) { rescan(event); }
    }
    
    public void rescan(SensorEvent<?> event) {
        rescanRequest(event.getSensor().getName()+" "+displayValue(event.getValue())+" from "+event.getSource().getId());
    }
    
    private String displayValue(Object o) {
        return Strings.maxlenWithEllipsis(String.valueOf(o), 40);
    }
    
    public void rescanRequest(String triggerDescription) {
        synchronized (rescanTriggers) {
            boolean rescanNeeded = rescanTriggers.isEmpty();
            rescanTriggers.add(triggerDescription);
            if (rescanNeeded) {
                getExecutionContext().submit("Scan for primary on "+triggerDescription, () -> { rescanImpl(); return null; });
            }
        }
    }
    
    public void rescanImpl() throws InterruptedException {
        String contextString;
        synchronized (rescanTriggers) {
            while (rescanInProgress) {
                Tasks.setBlockingDetails("Waiting for ongoing scan to complete");
                rescanTriggers.wait();
                Tasks.resetBlockingDetails();
            }
            if (rescanTriggers.isEmpty()) {
                if (log.isTraceEnabled()) {
                    log.trace("Policy "+this+" scheduled rescan unnecessary, trigger already handled");
                }
                return;
            }
            contextString = Strings.join(rescanTriggers, ", ");
            rescanTriggers.clear();
            rescanInProgress = true;
        }
        String code = null;
        try {
            String effName = config().get(EFFECTOR_NAME);
            if (log.isTraceEnabled()) {
                log.trace("Policy "+this+" got event: "+contextString+"; triggering rescan with "+effName);
            }

            // TODO as with during create, would be good to filter what is getting passed, or have another config key to allow it to be restricted/changed
            // (there is no way to prevent these paramters from all being applied, and filtered down through all calls, being serialized for ssh etc;
            // ineffieicnt, and could be a risk of leaking details)
            Task<?> task = Effectors.invocation(entity, Preconditions.checkNotNull( ((EntityInternal)entity).getEffector(effName) ), config().getBag()).asTask();
            BrooklynTaskTags.addTagDynamically(task, BrooklynTaskTags.NON_TRANSIENT_TASK_TAG);
            
            highlight("lastScan", "Running "+effName+"; triggered by "+contextString, task);
            
            Object result = DynamicTasks.get(task);
            if (result instanceof Map) code = Strings.toString( ((Map<?,?>)result).get("code") );
            
            if (ElectPrimaryEffector.ResultCode.NEW_PRIMARY_ELECTED.name().equalsIgnoreCase(code)) {
                highlightAction("New primary elected: "+niceName(((Map<?,?>)result).get("primary")), null);
            }
            if (ElectPrimaryEffector.ResultCode.NO_PRIMARY_AVAILABLE.name().equalsIgnoreCase(code)) {
                highlightViolation("No primary available");
            }
            if (ResultCode.PRIMARY_UNCHANGED.name().equalsIgnoreCase(code)) {
                highlightConfirmation("Primary re-elected: "+niceName(((Map<?,?>)result).get("primary")));
            }
        } catch (Throwable e) {
            Exceptions.propagateIfFatal(e);
            if (!Entities.isManagedActive(entity)) throw Exceptions.propagate(e);
            
            Throwable root = Throwables.getRootCause(e);
            if (root instanceof UserFacingException) {
                // prefer user-facing root cause to history; mainly for SelectionModeStrictFailed.
                // possibly not right if it's another (eg exposition on why promote failed)
                e = root;
            }
            if (e instanceof UserFacingException) {
                log.warn("Error running policy "+this+" on "+entity+": "+Exceptions.collapseText(e));
            } else {
                log.warn("Error running policy "+this+" on "+entity+": "+Exceptions.collapseText(e), e);
            }
        }
        
        synchronized (rescanTriggers) {
            rescanTriggers.notifyAll();
            rescanInProgress = false;
        }
    }

    protected String niceName(Object primary) {
        if (primary instanceof BrooklynObject) {
            if (Strings.isNonBlank( ((BrooklynObject)primary).getDisplayName() )) {
                String name = ((BrooklynObject)primary).getDisplayName();
                if (!name.contains( ((BrooklynObject)primary).getId() )) {
                    name += " ("+((BrooklynObject)primary).getId()+")";
                }
                return name;
            }
        }
        return ""+primary;
    }

}
