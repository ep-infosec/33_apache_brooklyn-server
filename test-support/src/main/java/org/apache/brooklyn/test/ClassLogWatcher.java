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
package org.apache.brooklyn.test;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class ClassLogWatcher extends ListAppender<ILoggingEvent> implements AutoCloseable {
    private final String className;

    public ClassLogWatcher(String className) {
        this.className = className;
        startAutomatically();
    }
    public ClassLogWatcher(Class<?> clazz) {
        this(clazz.getName());
    }

    protected void startAutomatically() {
        super.start();
        ((Logger) LoggerFactory.getLogger(className)).addAppender(this);
    }

    @Override
    public void start() {
        throw new IllegalStateException("This should not be started externally.");
    }

    @Override
    public void close() {
        ((Logger) LoggerFactory.getLogger(className)).detachAppender(this);
        stop();
    }

    public List<String> getMessages() {
        return this.list.stream().map(s -> s.getFormattedMessage()).collect(Collectors.toList());
    }
}