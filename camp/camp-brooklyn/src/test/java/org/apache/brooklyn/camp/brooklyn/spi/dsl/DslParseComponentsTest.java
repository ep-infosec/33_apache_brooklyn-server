/*
 * Copyright 2016 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.brooklyn.camp.brooklyn.spi.dsl;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.camp.BasicCampPlatform;
import org.apache.brooklyn.camp.brooklyn.AbstractYamlTest;
import org.apache.brooklyn.camp.brooklyn.BrooklynCampConstants;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.entity.EntityPredicates;
import org.apache.brooklyn.entity.group.BasicGroup;
import org.apache.brooklyn.entity.stock.BasicApplication;
import org.apache.brooklyn.entity.stock.BasicEntity;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.stream.Streams;
import org.apache.brooklyn.util.yaml.Yamls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import com.google.common.collect.Iterables;

public class DslParseComponentsTest extends AbstractYamlTest {
    
    private static final ConfigKey<Object> DEST = ConfigKeys.newConfigKey(Object.class, "dest");
    private static final ConfigKey<Object> DEST2 = ConfigKeys.newConfigKey(Object.class, "dest2");

    private static final Logger log = LoggerFactory.getLogger(DslParseComponentsTest.class);
    
    Entity app = null;
    
    protected Entity app() throws Exception {
        return app("one");
    }
    protected Entity app(String id) throws Exception {
        if (app==null) {
            app = createAndStartApplication(
                    "services:",
                    "- type: " + BasicApplication.class.getName(),
                    "  id: "+id,
                    "  brooklyn.config:",
                    "    dest: 1",
                    "    dest2: 1",
                    "    pattern: '%s-%s'",
                    "  brooklyn.children:",
                    "  - type: "+BasicEntity.class.getName(),
                    "    id: two",
                    "    brooklyn.config:",
                    "      dest2: 2"
                    );
        }
        return app;
    }

    @AfterMethod(alwaysRun = true)
    @Override
    public void tearDown() throws Exception {
        app = null;
        super.tearDown();
    }
    
    private Entity find(String desiredComponentId) {
        return Iterables.tryFind(mgmt().getEntityManager().getEntities(), EntityPredicates.configEqualTo(BrooklynCampConstants.PLAN_ID, desiredComponentId)).orNull();
    }
    
    public Object parseDslExpression(String input) {
        String s1 = Yamls.getAs( Yamls.parseAll( Streams.reader(Streams.newInputStreamWithContents(input)) ), String.class );
        BasicCampPlatform p = new BasicCampPlatform();
        p.pdp().addInterpreter(new BrooklynDslInterpreter());
        Object out = p.pdp().applyInterpreters(MutableMap.of("key", s1)).get("key");
        log.debug("parsed "+input+" as "+out+" ("+(out==null ? "null" : out.getClass())+")");
        return out;
    }
    
    @Test
    public void testTestSetup() throws Exception {
        app();
        Assert.assertEquals(find("two").getConfig(DEST), 1);
        Assert.assertEquals(find("two").getConfig(DEST2), 2);
    }
    
    @Test
    public void testDslParsingAndFormatStringEvaluation() throws Exception {
        // format string evaluates immediately
        Assert.assertEquals(parseDslExpression("$brooklyn:formatString(\"hello %s\", \"world\")"), "hello world");
    }
    
    @Test
    public void testConfigParsingAndToString() throws Exception {
        String x1 = "$brooklyn:config(\"dest\")";
        Object y1 = parseDslExpression(x1);
        // however config is a deferred supplier, with a toString in canonical form
        Asserts.assertInstanceOf(y1, BrooklynDslDeferredSupplier.class);
        Assert.assertEquals(y1.toString(), x1);
    }

    @Test
    public void testConfigEvaluation() throws Exception {
        app();
        
        Object y1 = parseDslExpression("$brooklyn:config(\"dest\")");
        String y2 = Tasks.resolveValue(y1, String.class, ((EntityInternal) find("two")).getExecutionContext());
        Assert.assertEquals(y2.toString(), "1");
    }

    @Test
    public void testFormatStringWithConfig() throws Exception {
        app();
        
        Object y1 = parseDslExpression("$brooklyn:formatString(\"%s-%s\", config(\"dest\"), $brooklyn:config(\"dest2\"))");
        Assert.assertEquals(y1.toString(), "$brooklyn:formatString(\"%s-%s\", config(\"dest\"), config(\"dest2\"))");
        
        String y2 = Tasks.resolveValue(y1, String.class, ((EntityInternal) find("two")).getExecutionContext());
        Assert.assertEquals(y2.toString(), "1-2");
        
        String y3 = Tasks.resolveValue(y1, String.class, ((EntityInternal) find("one")).getExecutionContext());
        Assert.assertEquals(y3.toString(), "1-1");
    }

    @Test
    public void testFormatStringWithDslPatternEvaluation() throws Exception {
        app();

        Object y1 = parseDslExpression("$brooklyn:formatString($brooklyn:config(\"pattern\"), $brooklyn:config(\"dest\"), $brooklyn:config(\"dest2\"))");
        Assert.assertEquals(y1.toString(), "$brooklyn:formatString(config(\"pattern\"), config(\"dest\"), config(\"dest2\"))");

        String y2 = Tasks.resolveValue(y1, String.class, ((EntityInternal) find("one")).getExecutionContext());
        Assert.assertEquals(y2.toString(), "1-1");
    }

    @Test
    public void testEntityReferenceAndAttributeWhenReady() throws Exception {
        app();
        find("one").sensors().set(Attributes.ADDRESS, "1");
        find("two").sensors().set(Attributes.ADDRESS, "2");
        
        Object y1 = parseDslExpression("$brooklyn:formatString(\"%s-%s\", "
            + "parent().attributeWhenReady(\"host.address\"), "
            + "$brooklyn:attributeWhenReady(\"host.address\"))");
        Assert.assertEquals(y1.toString(), "$brooklyn:formatString(\"%s-%s\", "
            + "parent().attributeWhenReady(\"host.address\"), "
            + "attributeWhenReady(\"host.address\"))");
        
        String y2 = Tasks.resolveValue(y1, String.class, ((EntityInternal) find("two")).getExecutionContext());
        Assert.assertEquals(y2.toString(), "1-2");
        
        Object z1 = parseDslExpression("$brooklyn:formatString(\"%s-%s\", "
            + "entity(\"one\").descendant(\"two\").attributeWhenReady(\"host.address\"), "
            + "component(\"two\").entity(entityId()).attributeWhenReady(\"host.address\"))");
        Assert.assertEquals(z1.toString(), "$brooklyn:formatString(\"%s-%s\", "
            + "entity(\"one\").descendant(\"two\").attributeWhenReady(\"host.address\"), "
            + "entity(entityId()).attributeWhenReady(\"host.address\"))");
        
        String z2 = Tasks.resolveValue(z1, String.class, ((EntityInternal) find("one")).getExecutionContext());
        Assert.assertEquals(z2.toString(), "2-1");
    }

    @Test
    public void testAppReference() throws Exception {
        app("other_app");
        Entity appOther = app;
        app = null;
        app();

        String otherTwoId = appOther.getChildren().iterator().next().getId();
        String localTwoId = app.getChildren().iterator().next().getId();

        Entity result = Tasks.resolveValue(parseDslExpression("$brooklyn:application(\"other_app\").entity(\"two\")"), Entity.class, ((EntityInternal) app).getExecutionContext());
        Asserts.assertEquals(result.getParent(), appOther);

        result = Tasks.resolveValue(parseDslExpression("$brooklyn:application(\"other_app\").entity(\""+otherTwoId+"\")"), Entity.class, ((EntityInternal) app).getExecutionContext());
        Asserts.assertEquals(result.getParent(), appOther);

        // does not get other app
        result = Tasks.resolveValue(parseDslExpression("$brooklyn:entity(\"two\")"), Entity.class, ((EntityInternal) app).getExecutionContext());
        Asserts.assertEquals(result.getParent(), app);

        // does not find without app specified
        Asserts.assertFailsWith(() -> Tasks.resolveValue(parseDslExpression("$brooklyn:entity(\""+otherTwoId+"\")"), Entity.class, ((EntityInternal) app).getExecutionContext()),
                e -> {
                    Asserts.expectedFailureContainsIgnoreCase(e, "no entity match", otherTwoId, app.getId());
                    Asserts.expectedFailureDoesNotContain(e, appOther.getId());
                    return true;
                });

        Asserts.assertFailsWith(() -> Tasks.resolveValue(parseDslExpression("$brooklyn:application(\"other_app\").entity(\""+localTwoId+"\")"), Entity.class, ((EntityInternal) app).getExecutionContext()),
                e -> {
                    Asserts.expectedFailureContainsIgnoreCase(e, "no entity match", localTwoId, appOther.getId());
                    // Asserts.expectedFailureDoesNotContain(e, app.getId());   // does contain app as well, because that is context
                    return true;
                });
    }

    @Test
    public void testMemberReference() throws Exception {
        app();
        BasicGroup group = app.addChild(EntitySpec.create(BasicGroup.class));

        Object memberGroupRef = parseDslExpression("$brooklyn:entity(\"" + group.getId() + "\").member(\"two\")");
        Object memberOnlyGroupRef = parseDslExpression("$brooklyn:entity(\"" + group.getId() + "\").component(\"members_only\", \"two\")");

        // does not find in group when not a member
        Asserts.assertFailsWith(() -> Tasks.resolveValue(memberGroupRef, Entity.class, ((EntityInternal) app).getExecutionContext()),
                e -> Asserts.expectedFailureContainsIgnoreCase(e, "no entity match", "two") );
        Asserts.assertFailsWith(() -> Tasks.resolveValue(memberOnlyGroupRef, Entity.class, ((EntityInternal) app).getExecutionContext()),
                e -> Asserts.expectedFailureContainsIgnoreCase(e, "no entity match", "two") );

        // does find as member relative to root
        Object memberRefRoot = parseDslExpression("$brooklyn:member(\"two\")");
        Entity two = Tasks.resolveValue(memberRefRoot, Entity.class, ((EntityInternal) app).getExecutionContext());
        group.addMember(two);

        // but not as members only from root
        Object memberOnlyRootRef = parseDslExpression("$brooklyn:component(\"members_only\", \"two\")");
        Asserts.assertFailsWith(() -> Tasks.resolveValue(memberOnlyRootRef, Entity.class, ((EntityInternal) app).getExecutionContext()),
                e -> Asserts.expectedFailureContainsIgnoreCase(e, "no entity match", "two") );

        // and then does find in group
        Asserts.assertEquals(Tasks.resolveValue(memberGroupRef, Entity.class, ((EntityInternal) app).getExecutionContext()), two);
        Asserts.assertEquals(Tasks.resolveValue(memberOnlyGroupRef, Entity.class, ((EntityInternal) app).getExecutionContext()), two);

        // but not as child
        Object descendantsOnlyGroupRef = parseDslExpression("$brooklyn:entity(\"" + group.getId() + "\").descendant(\"two\")");
        Asserts.assertFailsWith(() -> Tasks.resolveValue(descendantsOnlyGroupRef, Entity.class, ((EntityInternal) app).getExecutionContext()),
                e -> Asserts.expectedFailureContainsIgnoreCase(e, "no entity match", "two") );

        Object childOnlyGroupRef = parseDslExpression("$brooklyn:entity(\"" + group.getId() + "\").child(\"two\")");
        Asserts.assertFailsWith(() -> Tasks.resolveValue(childOnlyGroupRef, Entity.class, ((EntityInternal) app).getExecutionContext()),
                e -> Asserts.expectedFailureContainsIgnoreCase(e, "no entity match", "two") );
    }

}
