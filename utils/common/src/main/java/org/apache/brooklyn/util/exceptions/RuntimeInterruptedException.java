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
package org.apache.brooklyn.util.exceptions;

/**
 * A {@link RuntimeException} that is thrown when a Thread is interrupted.
 * <p>
 * This exception is useful if a Thread needs to be interrupted, but the {@link InterruptedException} can't be thrown
 * because it is checked.
 * <p>
 * When the {@link RuntimeInterruptedException} is created, it will automatically set the interrupt status on the calling
 * thread.
 *
 * @author Peter Veentjer.
 */
public class RuntimeInterruptedException extends RuntimeException {

    private static final long serialVersionUID = 915050245927866175L;
    long interruptedThreadId;

    public RuntimeInterruptedException(String msg) {
        super(msg);
        checkThreadsAndInterrupt(null);
    }

    public RuntimeInterruptedException(Throwable cause) {
        super(cause);
        checkThreadsAndInterrupt(cause);
    }

    public RuntimeInterruptedException(String msg, Throwable cause) {
        super(msg, cause);
        checkThreadsAndInterrupt(cause);
    }

    protected boolean checkThreadsAndInterrupt(Throwable cause) {
        RuntimeInterruptedException anotherRuntime = cause==null ? null : Exceptions.getFirstThrowableOfType(cause, RuntimeInterruptedException.class);
        if (anotherRuntime==null) {
            interruptedThreadId = Thread.currentThread().getId();
        } else {
            interruptedThreadId = anotherRuntime.interruptedThreadId;
        }
        if (isFromCurrentThread()) {
            Thread.currentThread().interrupt();
            return true;
        } else {
            return false;
        }
    }

    public boolean isFromCurrentThread() {
        return interruptedThreadId == Thread.currentThread().getId();
    }

}
