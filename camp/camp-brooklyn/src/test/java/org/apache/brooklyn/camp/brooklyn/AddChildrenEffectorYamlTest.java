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
package org.apache.brooklyn.camp.brooklyn;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.effector.AddChildrenEffector;
import org.apache.brooklyn.core.effector.Effectors;
import org.apache.brooklyn.core.resolve.jackson.BeanWithTypePlanTransformer;
import org.apache.brooklyn.core.typereg.BasicTypeImplementationPlan;
import org.apache.brooklyn.entity.stock.BasicApplication;
import org.apache.brooklyn.entity.stock.BasicEntity;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.collections.CollectionFunctionals;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.common.collect.Iterables;

import java.util.function.Supplier;

public class AddChildrenEffectorYamlTest extends AbstractYamlTest {
    private static final Logger log = LoggerFactory.getLogger(AddChildrenEffectorYamlTest.class);

    protected Entity makeAppAndAddChild(boolean includeDeclaredParameters, MutableMap<String,?> effectorInvocationParameters, String ...lines) {
        return makeAppAndAddChild(Strings.lines(
            "  - type: "+AddChildrenEffector.class.getName(),
            "    brooklyn.config:",
            "      name: add",
            (includeDeclaredParameters ? Strings.lines(indent("      ",
                "parameters:",
                "  p.param1:",
                "    defaultValue: default",
                "  p.param2:",
                "    defaultValue: default")) : "")),
            effectorInvocationParameters, lines);
    }

    protected Entity makeAppAndAddChild(String customInitialzerBlock, MutableMap<String,?> effectorInvocationParameters, String ...lines) {
        try {
            Entity app = createAndStartApplication(
                    "services:",
                    "- type: " + BasicApplication.class.getName(),
                    "  brooklyn.config:",
                    "    p.parent: parent",
                    "    p.child: parent",
                    "    p.param1: parent",
                    "  brooklyn.initializers:",
                    customInitialzerBlock,
                    Strings.lines(indent("      ", lines))
            );
            waitForApplicationTasks(app);

            Asserts.assertThat(app.getChildren(), CollectionFunctionals.empty());

            Object result = app.invoke(Effectors.effector(Object.class, "add").buildAbstract(), effectorInvocationParameters).get();
            Asserts.assertThat((Iterable<?>)result, CollectionFunctionals.sizeEquals(1));
            Asserts.assertThat(app.getChildren(), CollectionFunctionals.sizeEquals(1));
            Entity child = Iterables.getOnlyElement(app.getChildren());
            Assert.assertEquals(child.getId(), Iterables.getOnlyElement((Iterable<?>)result));

            return child;

        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }
    
    private String[] indent(String prefix, String ...lines) {
        String[] result = new String[lines.length];
        for (int i=0; i<lines.length; i++) {
            result[i] = prefix + lines[i];
        }
        return result;
    }

    void assertAddsChild(Supplier<Entity> childS) throws Exception {
        Entity child = childS.get();

        Assert.assertEquals(child.getConfig(ConfigKeys.newStringConfigKey("p.parent")), "parent");
        Assert.assertEquals(child.getConfig(ConfigKeys.newStringConfigKey("p.param2")), "default");

        Assert.assertEquals(child.getConfig(ConfigKeys.newStringConfigKey("p.param1")), "effector_param");
    }

    @Test
    public void assertAddsChild() throws Exception {
        assertAddsChild(() ->
                makeAppAndAddChild(true, MutableMap.of("p.param1", "effector_param"),
                    "blueprint_yaml: |",
                    "  services:",
                    "  - type: "+BasicEntity.class.getName()
                    ));
    }
    
    @Test
    public void testAddChildrenFailsWithoutServicesBlock() throws Exception {
        assertAddsChild(() ->
                makeAppAndAddChild(true, MutableMap.of("p.param1", "effector_param"),
                "blueprint_yaml: |",
                "  type: "+BasicEntity.class.getName()
                ));
    }

    @Test
    public void testAddChildrenAcceptsJson() throws Exception {
        Entity child = makeAppAndAddChild(false, MutableMap.<String,String>of(),
            // note no '|' indicator
            "blueprint_yaml:",  
            "  services:",
            "  - type: "+BasicEntity.class.getName(),
            "    brooklyn.config:",
            "      p.child: child"
            );
        Assert.assertEquals(child.getConfig(ConfigKeys.newStringConfigKey("p.child")), "child");
        Assert.assertEquals(child.getConfig(ConfigKeys.newStringConfigKey("p.parent")), "parent");
        // param1 from parent
        Assert.assertEquals(child.getConfig(ConfigKeys.newStringConfigKey("p.param1")), "parent");
        // param2 not set
        Assert.assertEquals(child.getConfig(ConfigKeys.newStringConfigKey("p.param2")), null);
    }
    
    @Test
    public void testAddChildrenWithConfig() throws Exception {
        Entity child = makeAppAndAddChild(true, MutableMap.<String,Object>of(),
            "blueprint_yaml: |",
            "  services:", 
            "  - type: "+BasicEntity.class.getName(),
            "    brooklyn.config:",
            "      p.child: $brooklyn:config(\"p.parent\")");
        Assert.assertEquals(child.getConfig(ConfigKeys.newStringConfigKey("p.param1")), "default");
        Assert.assertEquals(child.getConfig(ConfigKeys.newStringConfigKey("p.child")), "parent");
    }
    
    @Test
    public void testAddChildrenDslInJson() throws Exception {
        Entity child = makeAppAndAddChild(false, MutableMap.<String,String>of(),
            // note no '|' indicator
            "blueprint_yaml:",  
            "  services:", 
            "  - type: "+BasicEntity.class.getName(),
            "    brooklyn.config:",
            "      p.child: $brooklyn:config(\"p.parent\")");
        
        Assert.assertEquals(child.getConfig(ConfigKeys.newStringConfigKey("p.child")), "parent");
    }
    
    @Test
    public void testAddChildrenWithConfigAtRootAndParams() throws Exception {
        Entity child = makeAppAndAddChild(true, MutableMap.of("p.param1", "call"),
            // note no '|' indicator
            "blueprint_yaml:",  
            "  services:", 
            "  - type: "+BasicEntity.class.getName(),
            "  brooklyn.config:",
            "    p.child: child",
            "    p.param1: call",
            "    p.param2: blueprint",
            "    p.param3: blueprint");
        
        Assert.assertEquals(child.getConfig(ConfigKeys.newStringConfigKey("p.child")), "child");
        // this is the order of precedence
        Assert.assertEquals(child.getConfig(ConfigKeys.newStringConfigKey("p.param1")), "call");
        Assert.assertEquals(child.getConfig(ConfigKeys.newStringConfigKey("p.param2")), "default");
        Assert.assertEquals(child.getConfig(ConfigKeys.newStringConfigKey("p.param3")), "blueprint");
    }
    
    @Test
    public void testAddChildrenInJsonWithParameters() throws Exception {
        Entity child = makeAppAndAddChild(true, MutableMap.<String,String>of(),
            // note no '|' indicator, but there are declared parameters with defaults
            "blueprint_yaml:",  
            "  services:", 
            "  - type: "+BasicEntity.class.getName());
        Assert.assertEquals(child.getConfig(ConfigKeys.newStringConfigKey("p.param1")), "default");
    }
    
    @Override
    protected Logger getLogger() {
        return log;
    }

    // ------

    @Test
    public void testAddChildrenWithServicesBlock_RegisteredType() throws Exception {
        addBean("add-child", "1",
                new BasicTypeImplementationPlan(BeanWithTypePlanTransformer.FORMAT,
                        "type: "+AddChildrenEffector.class.getName()+"\n" +
                        "brooklyn.config:\n" +
                        "  name: add\n"+
                        "  blueprint_yaml: OVERRIDE"));
        assertAddsChild(() ->
                makeAppAndAddChild(Strings.lines(
                        "  - type: add-child",
                        "    brooklyn.config:",
                        Strings.lines(indent("      ",
                                "parameters:",
                                "  p.param1:",
                                "    defaultValue: default",
                                "  p.param2:",
                                "    defaultValue: default"))),
                        MutableMap.of("p.param1", "effector_param"),
                        "blueprint_yaml: |",
                        "  services:",
                        "  - type: "+BasicEntity.class.getName()
                ));
    }

    @Test
    public void testAddChildrenWithServicesBlock_Fields() throws Exception {
        assertAddsChild(() ->
                makeAppAndAddChild(Strings.lines(
                        "    - type: "+AddChildrenEffector.class.getName(),
                        "      name: add",
                        Strings.lines(indent("      ",
                                "parameters:",
                                "  p.param1:",
                                "    defaultValue: default",
                                "  p.param2:",
                                "    defaultValue: default"))),
                        MutableMap.of("p.param1", "effector_param"),
                        "blueprint_yaml: |",
                        "  services:",
                        "  - type: "+BasicEntity.class.getName()
                ));
    }

}
