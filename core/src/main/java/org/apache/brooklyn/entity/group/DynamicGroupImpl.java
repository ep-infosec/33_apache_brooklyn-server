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
package org.apache.brooklyn.entity.group;

import java.util.Collection;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.sensor.Sensor;
import org.apache.brooklyn.api.sensor.SensorEvent;
import org.apache.brooklyn.api.sensor.SensorEventListener;
import org.apache.brooklyn.core.BrooklynLogging;
import org.apache.brooklyn.core.BrooklynLogging.LoggingLevel;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.EntityPredicates;
import org.apache.brooklyn.core.mgmt.internal.CollectionChangeListener;
import org.apache.brooklyn.core.mgmt.internal.ManagementContextInternal;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

public class DynamicGroupImpl extends AbstractGroupImpl implements DynamicGroup {

    private static final Logger log = LoggerFactory.getLogger(DynamicGroupImpl.class);

    protected final Object memberChangeMutex = new Object();

    private volatile MyEntitySetChangeListener setChangeListener = null;

    @Override
    public void init() {
        super.init();
        sensors().set(RUNNING, true);
    }

    @Override
    public void setEntityFilter(Predicate<? super Entity> filter) {
        setConfigEvenIfOwned(ENTITY_FILTER, filter);
        rescanEntities();
    }

    @Override
    public Predicate<? super Entity> entityFilter() {
        return getEntityFilter();
    }

    /**
     * @return
     *      The filter configured in {@link #ENTITY_FILTER} ANDed with a check that the
     *      entity has the same application ID.
     */
    protected Predicate<? super Entity> getEntityFilter() {
        Predicate<? super Entity> entityFilter = getConfig(ENTITY_FILTER);
        if (entityFilter == null) {
            entityFilter = Predicates.alwaysFalse();
        }
        Entity ancestor = getAncestorToScan();
        Predicate<Entity> ancestorFilter;
        if (ancestor==null) ancestorFilter = EntityPredicates.applicationIdEqualTo(getApplicationId());
        else if (ancestor.getParent()==null) ancestorFilter = EntityPredicates.applicationIdEqualTo(ancestor.getId());
        else ancestorFilter = EntityPredicates.isDescendantOf(ancestor);
        return Predicates.and(
                ancestorFilter,
                entityFilter);
    }

    protected Entity getAncestorToScan() {
        Entity ancestor = getConfig(ANCESTOR);
        if (ancestor==null) return getApplication();
        return ancestor;
    }

    private boolean isRunning() {
        return Boolean.TRUE.equals(getAttribute(RUNNING));
    }

    @Override
    public void stop() {
        sensors().set(RUNNING, false);
        if (setChangeListener != null) {
            ((ManagementContextInternal) getManagementContext()).removeEntitySetListener(setChangeListener);
        }
    }

    @Override
    public <T> void addSubscription(Entity producer, Sensor<T> sensor, final Predicate<? super SensorEvent<? super T>> filter) {
        SensorEventListener<T> listener = new SensorEventListener<T>() {
                @Override
                public void onEvent(SensorEvent<T> event) {
                    if (filter.apply(event)) onEntityChanged(event.getSource());
                }
            };
        subscriptions().subscribe(producer, sensor, listener);
    }

    @Override
    public <T> void addSubscription(Entity producer, Sensor<T> sensor) {
        addSubscription(producer, sensor, Predicates.<SensorEvent<? super T>>alwaysTrue());
    }

    protected boolean acceptsEntity(Entity e) {
        return entityFilter().apply(e);
    }

    protected void onEntityAdded(Entity item) {
        synchronized (memberChangeMutex) {
            if (acceptsEntity(item)) {
                if (log.isDebugEnabled()) log.debug("{} detected item add {}", this, item);
                addMember(item);
            }
        }
    }

    protected void onEntityRemoved(Entity item) {
        synchronized (memberChangeMutex) {
            if (removeMember(item))
                if (log.isDebugEnabled()) log.debug("{} detected item removal {}", this, item);
        }
    }

    protected void onEntityChanged(Entity item) {
        synchronized (memberChangeMutex) {
            boolean accepts = acceptsEntity(item);
            boolean has = hasMember(item);
            if (has && !accepts) {
                removeMember(item);
                if (log.isDebugEnabled()) log.debug("{} detected item removal on change of {}", this, item);
            } else if (!has && accepts) {
                if (log.isDebugEnabled()) log.debug("{} detected item add on change of {}", this, item);
                addMember(item);
            }
        }
    }

    private class MyEntitySetChangeListener implements CollectionChangeListener.ListenerWithErrorHandler<Entity> {
        @Override
        public void onItemAdded(Entity item) { onEntityAdded(item); }
        @Override
        public void onItemRemoved(Entity item) { onEntityRemoved(item); }

        @Override
        public void onError(String msg, Throwable trace) {
            // log debug if shutting down
            BrooklynLogging.log(log, Entities.isManagedActive(DynamicGroupImpl.this) ? BrooklynLogging.LoggingLevel.WARN : BrooklynLogging.LoggingLevel.DEBUG,
                    msg, trace);
            throw Exceptions.propagate(trace);
        }
    }

    @Override
    public void onManagementBecomingMaster() {
        if (setChangeListener != null) {
            log.warn("{} becoming master twice", this);
            return;
        }
        setChangeListener = new MyEntitySetChangeListener();
        ((ManagementContextInternal) getManagementContext()).addEntitySetListener(setChangeListener);
        Task<Object> rescan = Tasks.builder().displayName("rescan entities").body(
            new Runnable() {
                @Override
                public void run() {
                    try {
                        rescanEntities();
                    } catch (Exception e) {
                        log.warn("Error rescanning entities on management of "+DynamicGroupImpl.this+"; may be a group set against an unknown entity: "+e);
                        log.debug("Trace for rescan entities error", e);
                        Exceptions.propagateIfFatal(e);
                    }
                }
            }).build();
        getExecutionContext().submit(rescan);
    }

    @Override
    public void onManagementNoLongerMaster() {
        if (setChangeListener == null) {
            log.warn("{} no longer master twice", this);
            return;
        }
        ((ManagementContextInternal) getManagementContext()).removeEntitySetListener(setChangeListener);
        setChangeListener = null;
    }

    @Override
    public void rescanEntities() {
        synchronized (memberChangeMutex) {
            if (!isRunning() || !getManagementSupport().isDeployed()) {
                if (log.isDebugEnabled()) log.debug("{} not scanning for children: stopped", this);
                return;
            }
            if (getAncestorToScan() == null) {
                BrooklynLogging.log(log, BrooklynLogging.levelDependingIfReadOnly(this, LoggingLevel.WARN, LoggingLevel.TRACE, LoggingLevel.TRACE),
                    "{} not (yet) scanning for children: no application defined", this);
                return;
            }
            boolean changed = false;
            Collection<Entity> currentMembers = getMembers();
            Collection<Entity> toRemove = Sets.newLinkedHashSet(currentMembers);

            final Iterable<Entity> unfiltered = Entities.descendantsAndSelf(getAncestorToScan());
            log.debug("{} filtering {} with {}", new Object[]{this, unfiltered, entityFilter()});
            for (Entity it : Iterables.filter(unfiltered, entityFilter())) {
                toRemove.remove(it);
                if (!currentMembers.contains(it)) {
                    if (log.isDebugEnabled()) log.debug("{} rescan detected new item {}", this, it);
                    addMember(it);
                    changed = true;
                }
            }
            for (Entity it : toRemove) {
                if (log.isDebugEnabled()) log.debug("{} rescan detected vanished item {}", this, it);
                removeMember(it);
                changed = true;
            }
            if (changed && log.isDebugEnabled())
                log.debug("{} rescan complete, members now {}", this, getMembers());
        }
    }

}
