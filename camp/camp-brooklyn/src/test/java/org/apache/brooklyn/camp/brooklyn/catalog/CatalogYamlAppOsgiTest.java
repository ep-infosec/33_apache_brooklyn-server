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
package org.apache.brooklyn.camp.brooklyn.catalog;

import org.testng.annotations.Test;

// OSGi variant of parent
@Test
public class CatalogYamlAppOsgiTest extends CatalogYamlAppTest {

    @Override
    protected boolean disableOsgi() {
        return false;
    }

    @Override @Test // here so we can easily test from IDE
    // currently works in parent but fails here because we don't treat unresolved (template with forward ref) 
    // as an acceptable type that can be referenced in a blueprint
    public void testAddTemplateForwardReferenceToEntity() throws Exception {
        super.testAddTemplateForwardReferenceToEntity();
    }
}
