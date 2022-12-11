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
package org.apache.brooklyn.core.config;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.io.ByteArrayInputStream;
import java.util.Map;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.stream.Streams;
import org.apache.brooklyn.util.text.Strings;
import static org.testng.Assert.assertEquals;
import org.testng.annotations.Test;

public class SanitizerTest {

    @Test
    public void testSanitize() throws Exception {
        Map<String, Object> map = ImmutableMap.<String, Object>builder()
                .put("PREFIX_password_SUFFIX", "pa55w0rd")
                .put("PREFIX_PASSWORD_SUFFIX", "pa55w0rd")
                .put("PREFIX_passwd_SUFFIX", "pa55w0rd")
                .put("PREFIX_credential_SUFFIX", "pa55w0rd")
                .put("PREFIX_secret_SUFFIX", "pa55w0rd")
                .put("PREFIX_private_SUFFIX", "pa55w0rd")
                .put("PREFIX_access.cert_SUFFIX", "myval")
                .put("PREFIX_access.key_SUFFIX", "myval")
                .put("mykey", "myval")
                .build();
        Map<String, Object> expected = MutableMap.<String, Object>builder()
                .putAll(Maps.transformValues(map, Sanitizer::suppress))
                .put("mykey", "myval")
                .build();
        
        Map<String, Object> sanitized = Sanitizer.sanitize(ConfigBag.newInstance(map));
        assertEquals(sanitized, expected);
        
        Map<String, Object> sanitized2 = Sanitizer.sanitize(map);
        assertEquals(sanitized2, expected);
    }
    
    @Test
    public void testSanitizeWithNullKey() throws Exception {
        MutableMap<?, ?> map = MutableMap.of(null, null);
        Map<?, ?> sanitized = Sanitizer.sanitize(map);
        assertEquals(sanitized, map);
    }
    
    @Test
    public void testSanitizeWithNull() throws Exception {
        assertEquals(Sanitizer.sanitize((ConfigBag)null), null);
        assertEquals(Sanitizer.sanitize((Map<?,?>)null), null);
        assertEquals(Sanitizer.newInstance().apply((Map<?,?>)null), null);
    }

    @Test
    public void testSanitizeMultiline() throws Exception {
        String hashPassword2 = "6CB75F652A9B52798EB6CF2201057C73";
        assertEquals(Sanitizer.sanitizeMultilineString(Strings.lines(
                "public: password",
                "private: password2",
                "private: ",
                "  allowedOnNewLine"
            )), Strings.lines(
                "public: password",
                "private: <suppressed> (MD5 hash: " + hashPassword2.substring(0, 8) + ")",
                "private: ",
                "  allowedOnNewLine"
            ));
        assertEquals(hashPassword2, Streams.getMd5Checksum(new ByteArrayInputStream(("password2").getBytes())));
    }
}
