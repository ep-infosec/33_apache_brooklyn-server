/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.policy.action;

import java.util.List;

import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.policy.Policy;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.DurationPredicates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.Beta;
import com.google.common.base.Preconditions;

/**
 * A {@link Policy} that executes an {@link Effector} at specific intervals.
 * <p>
 * The following example shows a pair of policies that resize a cluster
 * from one to ten entities during the day  and back to one at night,:
 * <pre>{@code
 * brooklyn.policies:
 *   - type: org.apache.brooklyn.policy.action.PeriodicEffectorPolicy
 *     brooklyn.config:
 *       effector: resize
 *       args:
 *         desiredSize: 10
 *       period: 1 day
 *       time: 08:00:00
 *   - type: org.apache.brooklyn.policy.action.PeriodicEffectorPolicy
 *     brooklyn.config:
 *       effector: resize
 *       args:
 *         desiredSize: 1
 *       period: 1 day
 *       time: 18:00:00
 * }</pre>
 */
@Beta
public class PeriodicEffectorPolicy extends AbstractScheduledEffectorPolicy {

    @SuppressWarnings("unused")
    private static final Logger LOG = LoggerFactory.getLogger(PeriodicEffectorPolicy.class);

    public static final ConfigKey<Duration> PERIOD = ConfigKeys.builder(Duration.class)
            .name("period")
            .description("The duration between executions of this policy")
            .constraint(DurationPredicates.positive())
            .defaultValue(Duration.hours(1))
            .build();

    public PeriodicEffectorPolicy() {
        super();
    }

    @Override
    public void start() {
        Duration period = Preconditions.checkNotNull(config().get(PERIOD), "The period must be configured for this policy");
        String time = config().get(TIME);
        Duration wait = config().get(WAIT);
        if (time != null) {
            wait = getWaitUntil(time);
        } else if (wait == null) {
            wait = period;
        }

        schedule(wait);
    }

    @Override
    protected List<Long> resubmitOnResume() {
        List<Long> scheduled = super.resubmitOnResume();
        
        if (scheduled.isEmpty()) {
            // We missed an entire period; re-calculate (rather than relying on run's finally block)
            start();
        }
        return scheduled;
    }
    
    @Override
    public synchronized void run() {
        try {
            super.run();
        } finally {
            if (isRunning() && getManagementContext().isRunning()) {
                Duration period = config().get(PERIOD);
                String time = config().get(TIME);
                if (time == null || time.equalsIgnoreCase(NOW) || time.equalsIgnoreCase(IMMEDIATELY)) {
                    schedule(period);
                } else {
                    Duration wait = getWaitUntil(time);
                    schedule(wait.upperBound(period));
                }
            }
        }
    }
}
