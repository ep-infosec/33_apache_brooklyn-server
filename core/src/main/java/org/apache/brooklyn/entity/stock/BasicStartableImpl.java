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
package org.apache.brooklyn.entity.stock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.core.entity.AbstractEntity;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.EntityPredicates;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.entity.trait.StartableMethods;
import org.apache.brooklyn.core.location.Locations;
import org.apache.brooklyn.util.collections.QuorumCheck;
import org.apache.brooklyn.util.exceptions.Exceptions;

public class BasicStartableImpl extends AbstractStartableImpl implements BasicStartable {

    private static final Logger log = LoggerFactory.getLogger(BasicStartableImpl.class);

    @Override
    protected void doStart(Collection<? extends Location> locations) {
        // essentially does StartableMethods.start(this, locations),
        // but optionally filters locations for each child

        Locations.LocationsFilter filter = getConfig(LOCATIONS_FILTER);
        Iterable<Entity> startables = StartableMethods.filterStartableManagedEntities(getChildren());
        if (!Iterables.isEmpty(startables)) {
            List<Task<?>> tasks = Lists.newArrayListWithCapacity(Iterables.size(startables));
            for (final Entity entity : startables) {
                Collection<? extends Location> l2 = locations;
                if (filter != null) {
                    l2 = filter.filterForContext(new ArrayList<Location>(locations), entity);
                    log.debug("Child " + entity + " of " + this + " being started in filtered location list: " + l2);
                }
                tasks.add(Entities.invokeEffectorWithArgs(this, entity, Startable.START, l2));
            }
            for (Task<?> t : tasks) {
                t.getUnchecked();
            }
        }
    }

    @Override
    protected void doStop() {
        StartableMethods.stop(this);
    }

    @Override
    public void restart() {
        StartableMethods.restart(this);
    }

}
