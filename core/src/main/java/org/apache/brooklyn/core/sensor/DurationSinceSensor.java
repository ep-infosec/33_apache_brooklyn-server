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

package org.apache.brooklyn.core.sensor;

import java.util.concurrent.Callable;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.effector.AddSensor;
import org.apache.brooklyn.core.effector.AddSensorInitializer;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.feed.function.FunctionFeed;
import org.apache.brooklyn.feed.function.FunctionPollConfig;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.time.Duration;

import com.google.common.base.Supplier;
import com.google.common.reflect.TypeToken;

public class DurationSinceSensor extends AddSensorInitializer<Duration> {

    private static final Supplier<Long> CURRENT_TIME_SUPPLIER = new CurrentTimeSupplier();

    @SuppressWarnings("serial")
    public static final ConfigKey<Supplier<Long>> EPOCH_SUPPLIER = ConfigKeys.builder(new TypeToken<Supplier<Long>>() {})
            .name("duration.since.epochsupplier")
            .description("The source of time from which durations are measured. Defaults to System.currentTimeMillis when " +
                    "if no supplier is given or the configured supplier returns null.")
            .defaultValue(CURRENT_TIME_SUPPLIER)
            .build();

    @SuppressWarnings("serial")
    public static final ConfigKey<Supplier<Long>> TIME_SUPPLIER = ConfigKeys.builder(new TypeToken<Supplier<Long>>() {})
            .name("duration.since.timesupplier")
            .description("The source of the current time. Defaults to System.currentTimeMillis if unconfigured or the " +
                    "supplier returns null.")
            .defaultValue(CURRENT_TIME_SUPPLIER)
            .build();

    public DurationSinceSensor() {}
    public DurationSinceSensor(ConfigBag params) { super(params); }

    @Override
    public void apply(final EntityLocal entity) {
        AttributeSensor<Duration> sensor = addSensor(entity);

        AttributeSensor<Long> epochSensor = Sensors.newLongSensor(sensor.getName() + ".epoch");

        if (entity.sensors().get(epochSensor) == null) {
            Long epoch = initParam(EPOCH_SUPPLIER).get();
            if (epoch == null) {
                epoch = CURRENT_TIME_SUPPLIER.get();
            }
            entity.sensors().set(epochSensor, epoch);
        }

        FunctionFeed.builder()
                .entity(entity)
                .poll(new FunctionPollConfig<>(sensor).callable(new UpdateTimeSince(entity, epochSensor, initParam(TIME_SUPPLIER))))
                .period(initParam(SENSOR_PERIOD))
                .build(true);
    }

    private static class CurrentTimeSupplier implements Supplier<Long> {
        @Override
        public Long get() {
            return System.currentTimeMillis();
        }
    }

    private static class UpdateTimeSince implements Callable<Duration> {
        private final Entity entity;
        private final AttributeSensor<Long> epochSensor;
        private final Supplier<Long> timeSupplier;

        private <T> UpdateTimeSince(EntityLocal entity, AttributeSensor<Long> epochSensor, Supplier<Long> timeSupplier) {
            this.entity = entity;
            this.epochSensor = epochSensor;
            this.timeSupplier = timeSupplier;
        }

        @Override
        public Duration call() {
            Long referencePoint = entity.sensors().get(epochSensor);
            // Defensive check. Someone has done something silly if this is false.
            if (referencePoint != null) {
                Long time = timeSupplier.get();
                if (time == null) {
                    time = CURRENT_TIME_SUPPLIER.get();
                }
                return Duration.millis(time - referencePoint);
            } else {
                throw new IllegalStateException("Cannot calculate duration since sensor: " +
                        entity + " missing required value for " + epochSensor);
            }
        }
    }
}
