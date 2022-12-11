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
package org.apache.brooklyn.core.feed;

import java.util.Collection;

import com.google.common.annotations.Beta;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.mgmt.rebind.RebindSupport;
import org.apache.brooklyn.api.mgmt.rebind.mementos.FeedMemento;
import org.apache.brooklyn.api.sensor.Feed;
import org.apache.brooklyn.api.sensor.Sensor;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.BrooklynFeatureEnablement;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.EntityAdjuncts;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.mgmt.rebind.BasicFeedRebindSupport;
import org.apache.brooklyn.core.objs.AbstractEntityAdjunct;
import org.apache.brooklyn.util.javalang.JavaClassNames;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/** 
 * Captures common fields and processes for sensor feeds.
 * These generally poll or subscribe to get sensor values for an entity.
 * They make it easy to poll over http, jmx, etc.
 */
public abstract class AbstractFeed extends AbstractEntityAdjunct implements Feed, EntityAdjuncts.EntityAdjunctProxyable {

    private static final Logger log = LoggerFactory.getLogger(AbstractFeed.class);

    public static final ConfigKey<Boolean> ONLY_IF_SERVICE_UP = ConfigKeys.newBooleanConfigKey("feed.onlyIfServiceUp", "", false);
    
    private final Object pollerStateMutex = new Object();
    private transient volatile Poller<?> poller;
    private transient volatile boolean activated;
    private transient volatile boolean suspended;

    public AbstractFeed() {
    }

    @Beta
    public static <T extends AbstractFeed> T initAndMaybeStart(T feed, Entity entity) {
        return initAndMaybeStart(feed, entity, false);
    }
    @Beta
    public static <T extends AbstractFeed> T initAndMaybeStart(T feed, Entity entity, boolean registerOnEntity) {
        feed.setEntity(checkNotNull((EntityInternal)entity, "entity"));
        if (registerOnEntity) ((EntityInternal) entity).feeds().add(feed);
        if (Entities.isManagedActive(entity)) {
            // start it is entity is already managed (dynamic addition); otherwise rely on EntityManagementSupport to start us (initializer-based addition and after rebind)
            feed.start();
        }
        return feed;
    }

    // Ensure idempotent, as called in builders (in case not registered with entity), and also called
    // when registering with entity
    @Override
    public void setEntity(EntityLocal entity) {
        super.setEntity(entity);
        if (BrooklynFeatureEnablement.isEnabled(BrooklynFeatureEnablement.FEATURE_FEED_REGISTRATION_PROPERTY)) {
            ((EntityInternal)entity).feeds().add(this);
        }
    }

    protected void initUniqueTag(String uniqueTag, Object ...valsForDefault) {
        if (Strings.isNonBlank(uniqueTag)) this.uniqueTag = uniqueTag;
        else this.uniqueTag = getDefaultUniqueTag(valsForDefault);
    }

    protected String getDefaultUniqueTag(Object ...valsForDefault) {
        StringBuilder sb = new StringBuilder();
        sb.append(JavaClassNames.simpleClassName(this));
        if (valsForDefault.length==0) {
            sb.append("@");
            sb.append(hashCode());
        } else if (valsForDefault.length==1 && valsForDefault[0] instanceof Collection){
            sb.append(Strings.toUniqueString(valsForDefault[0], 80));
        } else {
            sb.append("[");
            boolean first = true;
            for (Object x: valsForDefault) {
                if (!first) sb.append(";");
                else first = false;
                sb.append(Strings.toUniqueString(x, 80));
            }
            sb.append("]");
        }
        return sb.toString(); 
    }

    @Override
    public void start() {
        if (log.isDebugEnabled()) log.debug("Starting feed {} for {}", this, entity);
        if (activated) {
            throw new IllegalStateException(String.format("Attempt to start feed %s of entity %s when already running", 
                    this, entity));
        }
        if (poller != null) {
            throw new IllegalStateException(String.format("Attempt to re-start feed %s of entity %s", this, entity));
        }
        
        poller = new Poller<Object>(entity, this, getConfig(ONLY_IF_SERVICE_UP));
        activated = true;
        preStart();
        synchronized (pollerStateMutex) {
            // don't start poller if we are suspended
            if (!suspended) {
                poller.start();
            }
        }
    }

    @Override
    public void suspend() {
        synchronized (pollerStateMutex) {
            if (activated && !suspended) {
                poller.stop();
            }
            suspended = true;
        }
    }
    
    @Override
    public void resume() {
        synchronized (pollerStateMutex) {
            if (activated && suspended) {
                poller.start();
            }
            suspended = false;
        }
    }
    
    @Override
    public void destroy() {
        stop();
    }

    @Override
    public void stop() {
        if (!activated) { 
            log.debug("Ignoring attempt to stop feed {} of entity {} when not running", this, entity);
            return;
        }
        if (log.isDebugEnabled()) log.debug("stopping feed {} for {}", this, entity);
        
        activated = false;
        preStop();
        synchronized (pollerStateMutex) {
            if (!suspended) {
                poller.stop();
            }
        }
        postStop();
        super.destroy();
    }

    @Override
    public boolean isActivated() {
        return activated;
    }
    
    @Override
    public EntityLocal getEntity() {
        return entity;
    }
    
    protected boolean isConnected() {
        // TODO Default impl will result in multiple logs for same error if becomes unreachable
        // (e.g. if ssh gets NoRouteToHostException, then every AttributePollHandler for that
        // feed will log.warn - so if polling for 10 sensors/attributes will get 10 log messages).
        // Would be nice if reduced this logging duplication.
        // (You can reduce it by providing a better 'isConnected' implementation of course.)
        return isRunning() && entity!=null && !((EntityInternal)entity).getManagementSupport().isNoLongerManaged();
    }

    @Override
    public boolean isSuspended() {
        return suspended;
    }

    @Override
    public boolean isRunning() {
        return isActivated() && !isSuspended() && !isDestroyed() && getPoller()!=null && getPoller().isRunning();
    }

    @Override
    public RebindSupport<FeedMemento> getRebindSupport() {
        return new BasicFeedRebindSupport(this);
    }

    @SuppressWarnings("unchecked")
    @Override
    public RelationSupportInternal<Feed> relations() {
        return (RelationSupportInternal<Feed>) super.relations();
    }
    
    @Override
    protected void onChanged() {
    }

    /**
     * For overriding.
     */
    protected void preStart() {
    }

    /**
     * For overriding.
     */
    protected void preStop() {
    }
    
    /**
     * For overriding.
     */
    protected void postStop() {
    }
    
    /**
     * For overriding, where sub-class can change return-type generics!
     */
    protected Poller<?> getPoller() {
        return poller;
    }

    void highlightTriggerPeriod(Duration minPeriod) {
        highlightTriggers("Running every "+minPeriod);
    }

    public void highlightTriggers(String message) {
        super.highlightTriggers(message);
    }

    void onRemoveSensor(Sensor<?> sensor) {
        highlightActionPublishSensor("Clear sensor "+sensor.getName());
    }

    void onPublishSensor(Sensor<?> sensor, Object v) {
        highlightActionPublishSensor(sensor, v);
    }
}
