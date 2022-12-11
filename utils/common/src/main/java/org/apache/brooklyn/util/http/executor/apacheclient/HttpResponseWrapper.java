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
package org.apache.brooklyn.util.http.executor.apacheclient;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.http.HttpToolResponse;
import org.apache.brooklyn.util.http.executor.HttpResponse;
import org.apache.http.HttpEntity;
import org.apache.http.util.EntityUtils;

import com.google.common.annotations.Beta;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

@Beta
public class HttpResponseWrapper implements HttpResponse {

    private final HttpToolResponse delegate;
    private transient volatile byte[] content;
    private transient volatile Multimap<String, String> headers;
    
    public HttpResponseWrapper(HttpToolResponse delegate) {
        this.delegate = checkNotNull(delegate, "response");
    }

    @Override
    public void close() throws IOException {
        Maybe<org.apache.http.HttpResponse> apacheResponse = delegate.getResponse();
        if (apacheResponse.isPresent()) {
            HttpEntity entity = apacheResponse.get().getEntity();
            if (entity != null) {
                EntityUtils.consumeQuietly(apacheResponse.get().getEntity());
            }
        }
    }

    @Override
    public int code() {
        return delegate.getResponseCode();
    }

    @Override
    public String reasonPhrase() {
        return delegate.getReasonPhrase();
    }

    @Override
    public Multimap<String, String> headers() {
        return headersImpl();
        
    }

    @Override
    public long getContentLength() {
        byte[] content = getContentImpl();
        return (content == null) ? -1 : content.length;
    }

    @Override
    public InputStream getContent() {
        byte[] content = getContentImpl();
        return (content == null) ? null : new ByteArrayInputStream(content);
    }
    
    protected byte[] getContentImpl() {
        if (content == null) {
            content = delegate.getContent();
        }
        return content;
    }
    
    protected Multimap<String, String> headersImpl() {
        // The magic number "3" comes from ArrayListMultimap.DEFAULT_VALUES_PER_KEY
        if (headers == null) {
            Map<String, List<String>> headerLists = delegate.getHeaderLists();
            Multimap<String, String> headers = ArrayListMultimap.<String, String>create(headerLists.size(), 3);
            for (Map.Entry<String, List<String>> entry : headerLists.entrySet()) {
                headers.putAll(entry.getKey(), entry.getValue());
            }
            this.headers = Multimaps.unmodifiableMultimap(headers);
        }
        return headers;
    }
}
