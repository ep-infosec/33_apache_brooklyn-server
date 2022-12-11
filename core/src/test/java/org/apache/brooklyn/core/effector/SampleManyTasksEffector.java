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
package org.apache.brooklyn.core.effector;

import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;

import com.google.common.annotations.VisibleForTesting;
import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.mgmt.TaskAdaptable;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.effector.EffectorTasks.EffectorTaskFactory;
import org.apache.brooklyn.core.effector.Effectors.EffectorBuilder;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.TaskBuilder;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.Time;

/** Effector which can be used to create a lot of tasks with delays.
 * Mainly used for manual UI testing, with a blueprint such as the following:

<pre>

name: Test with many tasks
location: localhost
services:
- type: org.apache.brooklyn.entity.software.base.VanillaSoftwareProcess
  brooklyn.initializers:
  - type: org.apache.brooklyn.core.effector.SampleManyTasksEffector
  launch.command: |
    echo hello | nc -l 4321 &
    echo $! > $PID_FILE
    ## to experiment with errors or sleeping
    # sleep 10
    # exit 3

</pre>

 */
public class SampleManyTasksEffector extends AddEffectorInitializerAbstract {

    public static final ConfigKey<Integer> RANDOM_SEED = ConfigKeys.newIntegerConfigKey("random.seed");

    private SampleManyTasksEffector() {}
    public SampleManyTasksEffector(ConfigBag params) { super(params); }

    @VisibleForTesting
    public static List<String> OUTPUT;

    public static String output(String msg) {
        List<String> output = OUTPUT;
        if (output!=null) output.add(msg);
        return msg;
    }

    @Override
    protected EffectorBuilder<String> newEffectorBuilder() {
        return Effectors.effector(String.class, initParam(EFFECTOR_NAME)).name("eatand").impl(body(initParams()));
    }

    @Deprecated
    public Effector<?> getEffector() {
        return super.effector();
    }
    
    private static EffectorTaskFactory<String> body(ConfigBag params) {
        Integer seed = params.get(RANDOM_SEED);
        final Random random = seed!=null ? new Random(seed) : new Random();
        
        // NOTE: not nicely serializable
        return new EffectorTaskFactory<String>() {
            @Override
            public TaskAdaptable<String> newTask(Entity entity, Effector<String> effector, ConfigBag parameters) {
                return Tasks.<String>builder().displayName("eat-sleep-rave-repeat").addAll(tasks(0)).build();
            }
            List<Task<Object>> tasks(final int depth) {
                List<Task<Object>> result = MutableList.of();
                do {
                    TaskBuilder<Object> t = Tasks.builder();
                    double x = random.nextDouble();
                    if (depth>4) x *= random.nextDouble();
                    if (depth>6) x *= random.nextDouble();
                    if (x<0.3) {
                        t.displayName("eat").body(new Callable<Object>() { public Object call() { return output("eat"); }});
                    } else if (x<0.6) {
                        final Duration time = Duration.millis(Math.round(10*1000*random.nextDouble()*random.nextDouble()*random.nextDouble()*random.nextDouble()*random.nextDouble()));
                        t.displayName("sleep").description("Sleeping "+time).body(new Callable<Object>() { public Object call() {
                            Tasks.setBlockingDetails("sleeping "+time);
                            Time.sleep(time);
                            return output("slept "+time);
                        }});
                    } else if (x<0.8) {
                        t.displayName("rave").body(new Callable<Object>() { public Object call() {
                            List<Task<Object>> ts = tasks(depth+1);
                            for (Task<Object> tt: ts) {
                                DynamicTasks.queue(tt);
                            }
                            return output("raved with "+ts.size()+" tasks");
                        }});
                    } else {
                        t.displayName("repeat").addAll(tasks(depth+1));
                    }
                    result.add(t.build());
                    
                } while (random.nextDouble()<0.8);
                return result;
            }
        };
    }

}
