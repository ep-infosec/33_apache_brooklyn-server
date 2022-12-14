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
package org.apache.brooklyn.entity.software.base;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.entity.drivers.DriverDependentEntity;
import org.apache.brooklyn.api.entity.drivers.EntityDriverManager;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.api.location.MachineProvisioningLocation;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.sensor.EnricherSpec;
import org.apache.brooklyn.api.sensor.SensorEvent;
import org.apache.brooklyn.api.sensor.SensorEventListener;
import org.apache.brooklyn.core.enricher.AbstractEnricher;
import org.apache.brooklyn.core.entity.AbstractEntity;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.BrooklynConfigKeys;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic.ServiceNotUpLogic;
import org.apache.brooklyn.core.location.LocationConfigKeys;
import org.apache.brooklyn.core.location.cloud.CloudLocationConfig;
import org.apache.brooklyn.feed.function.FunctionFeed;
import org.apache.brooklyn.feed.function.FunctionPollConfig;
import org.apache.brooklyn.location.jclouds.networking.NetworkingEffectors;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableSet;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.task.BasicTask;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.ScheduledTask;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.time.CountdownTimer;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import groovy.time.TimeDuration;

/**
 * An {@link Entity} representing a piece of software which can be installed, run, and controlled.
 * A single such entity can only run on a single {@link MachineLocation} at a time (you can have multiple on the machine). 
 * It typically takes config keys for suggested versions, filesystem locations to use, and environment variables to set.
 * <p>
 * It exposes sensors for service state (Lifecycle) and status (String), and for host info, log file location.
 */
public abstract class SoftwareProcessImpl extends AbstractEntity implements SoftwareProcess, DriverDependentEntity {

    private static final Logger LOG = LoggerFactory.getLogger(SoftwareProcessImpl.class);

    private transient SoftwareProcessDriver driver;

    /** @see #connectServiceUpIsRunning() */
    private transient FunctionFeed serviceProcessIsRunning;

    protected boolean connectedSensors = false;

    protected void setProvisioningLocation(MachineProvisioningLocation val) {
        if (getAttribute(PROVISIONING_LOCATION) != null) throw new IllegalStateException("Cannot change provisioning location: existing="+getAttribute(PROVISIONING_LOCATION)+"; new="+val);
        sensors().set(PROVISIONING_LOCATION, val);
    }
    
    protected MachineProvisioningLocation getProvisioningLocation() {
        return getAttribute(PROVISIONING_LOCATION);
    }
    
    @Override
    public SoftwareProcessDriver getDriver() {
        return driver;
    }

    protected SoftwareProcessDriver newDriver(MachineLocation loc){
        EntityDriverManager entityDriverManager = getManagementContext().getEntityDriverManager();
        return (SoftwareProcessDriver)entityDriverManager.build(this, loc);
    }

    protected MachineLocation getMachineOrNull() {
        return Iterables.get(Iterables.filter(getLocations(), MachineLocation.class), 0, null);
    }

    @Override
    public void init() {
        super.init();
        getLifecycleEffectorTasks().attachLifecycleEffectors(this);
        if (Boolean.TRUE.equals(getConfig(ADD_OPEN_INBOUND_PORTS_EFFECTOR))) {
            getMutableEntityType().addEffector(NetworkingEffectors.OPEN_INBOUND_PORTS_IN_SECURITY_GROUP_EFFECTOR);
        }
    }
    
    @Override
    protected void initEnrichers() {
        super.initEnrichers();
        ServiceNotUpLogic.updateNotUpIndicator(this, SERVICE_PROCESS_IS_RUNNING, "No information yet on whether this service is running");
        // add an indicator above so that if is_running comes through, the map is cleared and an update is guaranteed
        enrichers().add(EnricherSpec.create(UpdatingNotUpFromServiceProcessIsRunning.class).uniqueTag("service-process-is-running-updating-not-up"));
        enrichers().add(EnricherSpec.create(ServiceNotUpDiagnosticsCollector.class).uniqueTag("service-not-up-diagnostics-collector"));
    }
    
    /**
     * This class should be considered internal, and not instantiated directly. It is only public 
     * to better support rebind.
     * 
     * @since 0.8.0
     */
    public static class ServiceNotUpDiagnosticsCollector extends AbstractEnricher implements SensorEventListener<Object> {
        public ServiceNotUpDiagnosticsCollector() {
        }
        
        @Override
        public void setEntity(EntityLocal entity) {
            super.setEntity(entity);
            if (!(entity instanceof SoftwareProcess)) {
                throw new IllegalArgumentException("Expected SoftwareProcess, but got entity "+entity);
            }
            subscriptions().subscribe(ImmutableMap.of("notifyOfInitialValue", true), entity, Attributes.SERVICE_STATE_ACTUAL, this);
            subscriptions().subscribe(ImmutableMap.of("notifyOfInitialValue", true), entity, Attributes.SERVICE_UP, this);
        }

        @Override
        public void onEvent(SensorEvent<Object> event) {
            onUpdated();
        }

        protected void onUpdated() {
            Boolean up = entity.getAttribute(SERVICE_UP);
            Lifecycle state = entity.getAttribute(SERVICE_STATE_ACTUAL);
            if (up == null || up) {
                entity.sensors().set(ServiceStateLogic.SERVICE_NOT_UP_DIAGNOSTICS, ImmutableMap.<String, Object>of());
            } else if (state == null || state == Lifecycle.CREATED || state == Lifecycle.STARTING) {
                // not yet started; do nothing
            } else if (state == Lifecycle.STOPPING || state == Lifecycle.STOPPED || state == Lifecycle.DESTROYED) {
                // stopping/stopped, so expect not to be up; get rid of the diagnostics.
                entity.sensors().set(ServiceStateLogic.SERVICE_NOT_UP_DIAGNOSTICS, ImmutableMap.<String, Object>of());
            } else {
                ((SoftwareProcess)entity).populateServiceNotUpDiagnostics();
            }
        }
    }
    
    @Override
    public void populateServiceNotUpDiagnostics() {
        if (getDriver() == null) {
            ServiceStateLogic.updateMapSensorEntry(this, ServiceStateLogic.SERVICE_NOT_UP_DIAGNOSTICS, "driver", "No driver");
            ServiceStateLogic.clearMapSensorEntry(this, ServiceStateLogic.SERVICE_NOT_UP_DIAGNOSTICS, "sshable");
            ServiceStateLogic.clearMapSensorEntry(this, ServiceStateLogic.SERVICE_NOT_UP_DIAGNOSTICS, SERVICE_PROCESS_IS_RUNNING.getName());
            return;
        } else {
            ServiceStateLogic.clearMapSensorEntry(this, ServiceStateLogic.SERVICE_NOT_UP_DIAGNOSTICS, "driver");
        }

        Location loc = getDriver().getLocation();
        if (loc instanceof SshMachineLocation) {
            if (((SshMachineLocation)loc).isSshable()) {
                ServiceStateLogic.clearMapSensorEntry(this, ServiceStateLogic.SERVICE_NOT_UP_DIAGNOSTICS, "sshable");
            } else {
                ServiceStateLogic.updateMapSensorEntry(
                        this, 
                        ServiceStateLogic.SERVICE_NOT_UP_DIAGNOSTICS, 
                        "sshable", 
                        "The machine for this entity does not appear to be sshable");
            }
        }

        boolean processIsRunning = getDriver().isRunning();
        if (processIsRunning) {
            ServiceStateLogic.clearMapSensorEntry(
                    this, 
                    ServiceStateLogic.SERVICE_NOT_UP_DIAGNOSTICS, 
                    SERVICE_PROCESS_IS_RUNNING.getName());
        } else {
            ServiceStateLogic.updateMapSensorEntry(
                    this, 
                    ServiceStateLogic.SERVICE_NOT_UP_DIAGNOSTICS, 
                    SERVICE_PROCESS_IS_RUNNING.getName(), 
                    "The software process for this entity does not appear to be running");
        }
    }

    /**
     * Subscribes to SERVICE_PROCESS_IS_RUNNING and SERVICE_UP; the latter has no effect if the former is set,
     * but to support entities which set SERVICE_UP directly we want to make sure that the absence of 
     * SERVICE_PROCESS_IS_RUNNING does not trigger any not-up indicators.
     */
    public static class UpdatingNotUpFromServiceProcessIsRunning extends AbstractEnricher implements SensorEventListener<Object> {
        public UpdatingNotUpFromServiceProcessIsRunning() {}
        
        @SuppressWarnings("unchecked")
        @Override
        public void setEntity(EntityLocal entity) {
            super.setEntity(entity);
            subscriptions().subscribe(entity, SERVICE_PROCESS_IS_RUNNING, this);
            subscriptions().subscribe(entity, Attributes.SERVICE_UP, this);
            highlightTriggers(MutableList.of(SERVICE_PROCESS_IS_RUNNING, Attributes.SERVICE_UP), entity);
            onUpdated();
        }

        @Override
        public void onEvent(SensorEvent<Object> event) {
            onUpdated();
        }

        protected void onUpdated() {
            Boolean isRunning = entity.getAttribute(SERVICE_PROCESS_IS_RUNNING);
            if (Boolean.FALSE.equals(isRunning)) {
                ServiceNotUpLogic.updateNotUpIndicator(entity, SERVICE_PROCESS_IS_RUNNING, "The software process for this entity does not appear to be running");
                return;
            }
            if (Boolean.TRUE.equals(isRunning)) {
                ServiceNotUpLogic.clearNotUpIndicator(entity, SERVICE_PROCESS_IS_RUNNING);
                return;
            }
            // no info on "isRunning"
            Boolean isUp = entity.getAttribute(Attributes.SERVICE_UP);
            if (Boolean.TRUE.equals(isUp)) {
                // if service explicitly set up, then don't apply our rule
                ServiceNotUpLogic.clearNotUpIndicator(entity, SERVICE_PROCESS_IS_RUNNING);
                return;
            }
            // service not up, or no info
            ServiceNotUpLogic.updateNotUpIndicator(entity, SERVICE_PROCESS_IS_RUNNING, "No information on whether this service is running");
        }
    }
    
    /**
     * Called before driver.start; guarantees the driver will exist, and locations will have been set.
     */
    protected void preStart() {
    }
    
    /**
     * Called after driver.start(). Default implementation is to wait to confirm the driver 
     * definitely started the process.
     */
    protected void postDriverStart() {
        waitForEntityStart();
    }

    /**
     * For binding to the running app (e.g. connecting sensors to registry). Will be called
     * on start() and on rebind().
     * <p>
     * Implementations should be idempotent (ie tell whether sensors already connected),
     * though the framework is pretty good about not calling when already connected. 
     * TODO improve the framework's feed system to detect duplicate additions
     */
    protected void connectSensors() {
        connectedSensors = true;
    }

    /**
     * For connecting the {@link #SERVICE_UP} sensor to the value of the {@code getDriver().isRunning()} expression.
     * <p>
     * Should be called inside {@link #connectSensors()}.
     *
     * @see #disconnectServiceUpIsRunning()
     */
    protected void connectServiceUpIsRunning() {
        Duration period = config().get(SERVICE_PROCESS_IS_RUNNING_POLL_PERIOD);
        serviceProcessIsRunning = FunctionFeed.builder()
                .uniqueTag("check-service-process-is-running")
                .entity(this)
                .period(period)
                .poll(new FunctionPollConfig<Boolean, Boolean>(SERVICE_PROCESS_IS_RUNNING)
                        .suppressDuplicates(true)
                        .onException(Functions.constant(Boolean.FALSE))
                        .callable(new Callable<Boolean>() {
                            @Override
                            public Boolean call() {
                                return getDriver().isRunning();
                            }
                        }))
                .build(false);
    }

    /**
     * For disconnecting the {@link #SERVICE_UP} feed.
     * <p>
     * Should be called from {@link #disconnectSensors()}.
     *
     * @see #connectServiceUpIsRunning()
     */
    protected void disconnectServiceUpIsRunning() {
        if (serviceProcessIsRunning != null) serviceProcessIsRunning.stop();
        // set null so the SERVICE_UP enricher runs (possibly removing it), then remove so everything is removed
        // TODO race because the is-running check may be mid-task
        sensors().set(SERVICE_PROCESS_IS_RUNNING, null);
        sensors().remove(SERVICE_PROCESS_IS_RUNNING);
    }

    /**
     * Called after the rest of start has completed (after {@link #connectSensors()} and {@link #waitForServiceUp()})
     */
    protected void postStart() {
    }
    
    protected void preStopConfirmCustom() {
    }
    
    protected void preStop() {
        // note asymmetry that disconnectSensors is done in the entity not the driver
        // whereas on start the *driver* calls connectSensors, before calling postStart,
        // ie waiting for the entity truly to be started before calling postStart;
        // TODO feels like that confusion could be eliminated with a single place for pre/post logic!)
        LOG.debug("disconnecting sensors for "+this+" in entity.preStop");
        disconnectSensors();
        
        // Must set the serviceProcessIsRunning explicitly to false - we've disconnected the sensors
        // so nothing else will.
        // Otherwise, if restarted, there will be no change to serviceProcessIsRunning, so the
        // serviceUpIndicators will not change, so serviceUp will not be reset.
        // TODO Is there a race where disconnectSensors could leave a task of the feeds still running
        // which could set serviceProcessIsRunning to true again before the task completes and the feed
        // is fully terminated?
        sensors().set(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING, false);
    }

    /**
     * Called after the rest of stop has completed (after VM deprovisioned, but before state set to STOPPED)
     */
    protected void postStop() {
    }

    /**
     * Called before driver.restart; guarantees the driver will exist, and locations will have been set.
     */
    protected void preRestart() {
    }

    protected void postRestart() {
    }

    /**
     * For disconnecting from the running app. Will be called on stop.
     */
    protected void disconnectSensors() {
        connectedSensors = false;
    }

    /**
     * Called after this entity is fully rebound (i.e. it is fully managed).
     */
    protected void postRebind() {
    }
    
    protected void callRebindHooks() {
        Duration configuredMaxDelay = getConfig(MAXIMUM_REBIND_SENSOR_CONNECT_DELAY);
        if (configuredMaxDelay == null || Duration.ZERO.equals(configuredMaxDelay)) {
            connectSensors();
        } else {
            long delay = (long) (Math.random() * configuredMaxDelay.toMilliseconds());
            LOG.debug("Scheduling reconnection of sensors on {} in {}ms", this, delay);
            
            scheduleConnectSensorsOnRebind(Duration.millis(delay));
        }
        
        // don't wait here - it may be long-running, e.g. if remote entity has died, and we don't want to block rebind waiting or cause it to fail
        // the service will subsequently show service not up and thus failure
//        waitForServiceUp();
    }

    protected void scheduleConnectSensorsOnRebind(Duration delay) {
        Callable<Void> job = new Callable<Void>() {
            public Void call() {
                try {
                    if (!getManagementContext().isRunning()) {
                        LOG.debug("Management context not running; entity {} ignoring scheduled connect-sensors on rebind", SoftwareProcessImpl.this);
                        return null;
                    }
                    if (getManagementSupport().isNoLongerManaged()) {
                        LOG.debug("Entity {} no longer managed; ignoring scheduled connect-sensors on rebind", SoftwareProcessImpl.this);
                        return null;
                    }
                    
                    // Don't call connectSensors until the entity is actually managed.
                    // See https://issues.apache.org/jira/browse/BROOKLYN-580
                    boolean rebindActive = getManagementContext().getRebindManager().isRebindActive();
                    if (!getManagementSupport().wasDeployed()) {
                        if (rebindActive) {
                            // We are still rebinding, and entity not yet managed - reschedule.
                            Duration configuredMaxDelay = getConfig(MAXIMUM_REBIND_SENSOR_CONNECT_DELAY);
                            if (configuredMaxDelay == null) configuredMaxDelay = Duration.millis(100);
                            long delay = (long) (Math.random() * configuredMaxDelay.toMilliseconds());
                            delay = Math.max(10, delay);
                            LOG.debug("Entity {} not yet managed; re-scheduling connect-sensors on rebind in {}ms", SoftwareProcessImpl.this, delay);
                            
                            scheduleConnectSensorsOnRebind(Duration.millis(delay));
                            return null;
                        } else {
                            // Not rebinding and yet not managed - presumably means that rebind aborted
                            // (e.g. with a "fail-fast" configuration).
                            LOG.debug("Rebind no longer executing, yet entity {} not managed; not re-scheduling connect-sensors", SoftwareProcessImpl.this);
                            return null;
                        }
                    }
                    
                    connectSensors();
                    
                } catch (Throwable e) {
                    LOG.warn("Problem connecting sensors on rebind of "+SoftwareProcessImpl.this, e);
                    Exceptions.propagateIfFatal(e);
                }
                return null;
            }};
            
        Callable<Task<?>> jobFactory = new Callable<Task<?>>() {
            public Task<?> call() {
                return new BasicTask<Void>(job);
            }};

        // This is functionally equivalent to new scheduledExecutor.schedule(job, delay, TimeUnit.MILLISECONDS).
        // It uses the entity's execution context to schedule and thus execute the job.
        ScheduledTask scheduledTask = ScheduledTask.builder(jobFactory)
                .displayName("Schedule connect sensors on rebind")
                .delay(delay)
                .maxIterations(1)
                .cancelOnException(true)
                .build();

        getExecutionContext().submit(scheduledTask);
    }

    @Override 
    public void onManagementStarting() {
        super.onManagementStarting();
        
        Lifecycle state = getAttribute(SERVICE_STATE_ACTUAL);
        if (state == null || state == Lifecycle.CREATED) {
            // Expect this is a normal start() sequence (i.e. start() will subsequently be called)
            sensors().set(SERVICE_UP, false);
            ServiceStateLogic.setExpectedState(this, Lifecycle.CREATED);
            // force actual to be created because this is expected subsequently
            sensors().set(SERVICE_STATE_ACTUAL, Lifecycle.CREATED);
        }
    }
    
    @Override 
    public void onManagementStarted() {
        super.onManagementStarted();
        
        Lifecycle state = getAttribute(SERVICE_STATE_ACTUAL);
        if (state != null && state != Lifecycle.CREATED) {
            postRebind();
        }
    }
    
    @Override
    public void rebind() {
        //SERVICE_STATE_ACTUAL might be ON_FIRE due to a temporary condition (problems map non-empty)
        //Only if the expected state is ON_FIRE then the entity has permanently failed.
        Lifecycle expectedState = ServiceStateLogic.getExpectedState(this);
        if (expectedState == null || expectedState != Lifecycle.RUNNING) {
            LOG.warn("On rebind of {}, not calling software process rebind hooks because expected state is {}", this, expectedState);
            return;
        }

        Lifecycle actualState = ServiceStateLogic.getActualState(this);
        if (actualState == null || actualState != Lifecycle.RUNNING) {
            LOG.warn("Rebinding entity {}, even though actual state is {}. Expected state is {}", new Object[] { this, actualState, expectedState });
        }

        // e.g. rebinding to a running instance
        // FIXME What if location not set?
        LOG.info("Rebind {} connecting to pre-running service", this);
        
        MachineLocation machine = getMachineOrNull();
        if (machine != null) {
            initDriver(machine);
            driver.rebind();
            LOG.debug("On rebind of {}, re-created driver {}", this, driver);
        } else {
            LOG.info("On rebind of {}, no MachineLocation found (with locations {}) so not generating driver", this, getLocations());
        }
        
        callRebindHooks();
    }
    
    public void waitForServiceUp() {
        Duration timeout = getConfig(BrooklynConfigKeys.START_TIMEOUT);
        waitForServiceUp(timeout);
    }
    public void waitForServiceUp(Duration duration) {
        Entities.waitForServiceUp(this, duration);
    }
    
    /**
     * @deprecated since 0.11.0; explicit groovy utilities/support will be deleted.
     */
    @Deprecated
    public void waitForServiceUp(TimeDuration duration) {
        waitForServiceUp(duration.toMilliseconds(), TimeUnit.MILLISECONDS);
    }
    public void waitForServiceUp(long duration, TimeUnit units) {
        Entities.waitForServiceUp(this, Duration.of(duration, units));
    }

    protected Map<String,Object> obtainProvisioningFlags(MachineProvisioningLocation location) {
        ConfigBag result = ConfigBag.newInstance(location.getProvisioningFlags(ImmutableList.of(getClass().getName())));

        // copy provisioning properties raw in case they contain deferred values
        // normal case is to have provisioning.properties.xxx in the map, so this is how we get it
        Map<String, Object> raw1 = PROVISIONING_PROPERTIES.rawValue(config().getBag().getAllConfigRaw());
        // do this also, just in case a map is stored at the key itself (not sure this is needed, raw1 may include it already)
        Maybe<Object> raw2 = config().getRaw(PROVISIONING_PROPERTIES);
        if (raw2.isPresentAndNonNull()) {
            Object pp = raw2.get();
            if (!(pp instanceof Map)) {
                LOG.debug("When obtaining provisioning properties for "+this+" to deploy to "+location+", detected that coercion was needed, so coercing sooner than we would otherwise");
                pp = config().get(PROVISIONING_PROPERTIES);
            }
            result.putAll((Map<?,?>)pp);
        }
        // finally write raw1 on top
        result.putAll(raw1);

        if (result.get(CloudLocationConfig.INBOUND_PORTS) == null) {
            Collection<Integer> ports = getRequiredOpenPorts();
            Object requiredPorts = result.get(CloudLocationConfig.ADDITIONAL_INBOUND_PORTS);
            if (requiredPorts instanceof Integer) {
                ports.add((Integer) requiredPorts);
            } else if (requiredPorts instanceof Iterable) {
                for (Object o : (Iterable<?>) requiredPorts) {
                    if (o instanceof Integer) ports.add((Integer) o);
                }
            }
            if (ports != null && ports.size() > 0) result.put(CloudLocationConfig.INBOUND_PORTS, ports);
        }
        result.put(LocationConfigKeys.CALLER_CONTEXT, this);
        return result.getAllConfigMutable();
    }

    /**
     * Returns the ports that this entity wants to be opened.
     * @see InboundPortsUtils#getRequiredOpenPorts(Entity, Set, Boolean, String)
     * @see #REQUIRED_OPEN_LOGIN_PORTS
     * @see #INBOUND_PORTS_AUTO_INFER
     * @see #INBOUND_PORTS_CONFIG_REGEX
     */
    protected Collection<Integer> getRequiredOpenPorts() {
        Set<Integer> ports = MutableSet.copyOf(getConfig(REQUIRED_OPEN_LOGIN_PORTS));
        Boolean portsAutoInfer = getConfig(INBOUND_PORTS_AUTO_INFER);
        String portsRegex = getConfig(INBOUND_PORTS_CONFIG_REGEX);
        ports.addAll(InboundPortsUtils.getRequiredOpenPorts(this, config().getBag().getAllConfigAsConfigKeyMap().keySet(), portsAutoInfer, portsRegex));
        return ports;
    }

    protected void initDriver(MachineLocation machine) {
        SoftwareProcessDriver newDriver = doInitDriver(machine);
        if (newDriver == null) {
            throw new UnsupportedOperationException("cannot start "+this+" on "+machine+": no driver available");
        }
        driver = newDriver;
    }

    /**
     * Creates the driver (if does not already exist or needs replaced for some reason). Returns either the existing driver
     * or a new driver. Must not return null.
     */
    protected SoftwareProcessDriver doInitDriver(MachineLocation machine) {
        if (driver!=null) {
            if ((driver instanceof AbstractSoftwareProcessDriver) && machine.equals(((AbstractSoftwareProcessDriver)driver).getLocation())) {
                return driver; //just reuse
            } else {
                LOG.warn("driver/location change is untested for {} at {}; changing driver and continuing", this, machine);
                return newDriver(machine);
            }
        } else {
            return newDriver(machine);
        }
    }
    
    // TODO Find a better way to detect early death of process.
    public void waitForEntityStart() {
        LOG.debug("waiting to ensure {} doesn't abort prematurely", this);
        Duration startTimeout = getConfig(START_TIMEOUT);
        CountdownTimer timer = startTimeout.countdownTimer();
        boolean isRunningResult = false;
        long delay = 100;
        Exception firstFailure = null;
        while (!isRunningResult && timer.isNotExpired()) {
            Time.sleep(delay);
            try {
                isRunningResult = driver.isRunning();
                LOG.debug("checked {}, 'is running' returned: {}", this, isRunningResult);
            } catch (Exception  e) {
                Exceptions.propagateIfFatal(e);

                isRunningResult = false;
                if (driver != null) {
                    String msg = "checked " + this + ", 'is running' threw an exception; logging subsequent exceptions at debug level";
                    if (firstFailure == null) {
                        LOG.error(msg, e);
                    } else {
                        LOG.debug(msg, e);
                    }
                } else {
                    // provide extra context info, as we're seeing this happen in strange circumstances
                    LOG.error(this+" concurrent start and shutdown detected", e);
                }
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
            // slow exponential delay -- 1.1^N means after 40 tries and 50s elapsed, it reaches the max of 5s intervals
            // TODO use Repeater 
            delay = Math.min(delay*11/10, 5000);
        }
        if (!isRunningResult) {
            String msg = "Software process entity "+this+" did not pass is-running check within "+
                    "the required "+startTimeout+" limit ("+timer.getDurationElapsed().toStringRounded()+" elapsed)";
            if (firstFailure != null) {
                msg += "; check failed at least once with exception: " + firstFailure.getMessage() + ", see logs for details";
            }
            LOG.warn(msg+" (throwing)");
            ServiceStateLogic.setExpectedState(this, Lifecycle.RUNNING);
            throw new IllegalStateException(msg, firstFailure);
        }
    }

    /**
     * If custom behaviour is required by sub-classes, consider overriding {@link #preStart()} or {@link #postStart()})}.
     * Also consider adding additional work via tasks, executed using {@link DynamicTasks#queue(String, Callable)}.
     */
    @Override
    public final void start(final Collection<? extends Location> locations) {
        if (DynamicTasks.getTaskQueuingContext() != null) {
            getLifecycleEffectorTasks().start(locations);
        } else {
            Task<?> task = Tasks.builder().displayName("start (sequential)").body(new Runnable() {
                @Override public void run() { getLifecycleEffectorTasks().start(locations); }
            }).build();
            Entities.submit(this, task).getUnchecked();
        }
    }

    /**
     * If custom behaviour is required by sub-classes, consider overriding  {@link #preStop()} or {@link #postStop()}.
     * Also consider adding additional work via tasks, executed using {@link DynamicTasks#queue(String, Callable)}.
     */
    @Override
    public final void stop() {
        // TODO There is a race where we set SERVICE_UP=false while sensor-adapter threads may still be polling.
        // The other thread might reset SERVICE_UP to true immediately after we set it to false here.
        // Deactivating adapters before setting SERVICE_UP reduces the race, and it is reduced further by setting
        // SERVICE_UP to false at the end of stop as well.
        
        // Perhaps we should wait until all feeds have completed here, 
        // or do a SERVICE_STATE check before setting SERVICE_UP to true in a feed (?).

        if (DynamicTasks.getTaskQueuingContext() != null) {
            getLifecycleEffectorTasks().stop(ConfigBag.EMPTY);
        } else {
            Task<?> task = Tasks.builder().displayName("stop").body(new Runnable() {
                @Override public void run() { getLifecycleEffectorTasks().stop(ConfigBag.EMPTY); }
            }).build();
            Entities.submit(this, task).getUnchecked();
        }
    }

    /**
     * If custom behaviour is required by sub-classes, consider overriding {@link #preRestart()} or {@link #postRestart()}.
     * Also consider adding additional work via tasks, executed using {@link DynamicTasks#queue(String, Callable)}.
     */
    @Override
    public final void restart() {
        if (DynamicTasks.getTaskQueuingContext() != null) {
            getLifecycleEffectorTasks().restart(ConfigBag.EMPTY);
        } else {
            Task<?> task = Tasks.builder().displayName("restart").body(new Runnable() {
                @Override public void run() { getLifecycleEffectorTasks().restart(ConfigBag.EMPTY); }
            }).build();
            Entities.submit(this, task).getUnchecked();
        }
    }
    
    protected SoftwareProcessDriverLifecycleEffectorTasks getLifecycleEffectorTasks() {
        return getConfig(LIFECYCLE_EFFECTOR_TASKS);
    }

}
