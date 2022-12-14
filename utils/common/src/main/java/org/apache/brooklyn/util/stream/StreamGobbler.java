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
package org.apache.brooklyn.util.stream;

import org.slf4j.Logger;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** eagerly consumes a stream, writing to another stream; applies some whitespace and replacement character filtering */
public class StreamGobbler extends Thread implements Closeable {

    /** this character is omitted from the stream */
    public static final char REPLACEMENT_CHARACTER = 0xfffd;

    protected final InputStream stream;
    protected final PrintStream out;
    protected final Logger log;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public StreamGobbler(InputStream stream, OutputStream out, Logger log) {
        this(stream, out != null ? new PrintStream(out) : null, log);
    }

    public StreamGobbler(InputStream stream, PrintStream out, Logger log) {
        this.stream = stream;
        this.out = out;
        this.log = log;
    }

    @Override
    public void close() {
        running.set(false);
        interrupt();
    }

    /**
     * @deprecate Use close() instead.
     */
    @Deprecated
    public void shutdown() {
        close();
    }

    String logPrefix = "";
    String printPrefix = "";

    public StreamGobbler setPrefix(String prefix) {
        setLogPrefix(prefix);
        setPrintPrefix(prefix);
        return this;
    }

    public StreamGobbler setPrintPrefix(String prefix) {
        printPrefix = prefix;
        return this;
    }

    public StreamGobbler setLogPrefix(String prefix) {
        logPrefix = prefix;
        return this;
    }

    @Override
    public void run() {
        int c, bytes = 0;
        char[] utfSymbol = new char[2];
        try {
            ByteBuffer bb = ByteBuffer.allocate(4);
            while (running.get() && (c = stream.read()) >= 0) {

                if (bytes == 0) {
                    // Identify utf symbol size by Unicode page.
                    if (c >= 0xF0) {
                        bytes = 4;
                    } else if (c >= 0xE0) {
                        bytes = 3;
                    } else if (c >= 0xC2) {
                        bytes = 2;
                    } else {
                        bytes = 1;
                    }
                }

                bb.put((byte) c);
                bytes--;

                if (bytes == 0) {
                    bb.rewind();
                    StandardCharsets.UTF_8.decode(bb).get(utfSymbol);
                    bb.clear();
                    onChar(utfSymbol[0]);
                    if (utfSymbol[1] != 0) {
                        onChar(utfSymbol[1]);
                    }
                }
            }
            onClose();
        } catch (IOException e) {
            onClose();
            //TODO parametrise log level, for this error, and for normal messages
            if (log!=null && log.isTraceEnabled()) log.trace(logPrefix+"exception reading from stream ("+e+")");
        } finally {
            if (out!=null) out.flush();
        }
    }

    private final StringBuilder lineSoFar = new StringBuilder(16);

    public void onChar(char c) {
        if (c == REPLACEMENT_CHARACTER) return;
        if (c == '\n' || c == '\r') {
            if (lineSoFar.length() > 0)
                // suppress blank lines, so that we can treat either newline char as a line separator
                // (e.g. to show curl updates frequently)
                onLine(lineSoFar.toString());
            lineSoFar.setLength(0);
        } else {
            lineSoFar.append(c);
        }
    }

    public void onLine(String line) {
        //right trim, in case there is \r or other funnies
        while (line.length()>0 && Character.isWhitespace(line.charAt(line.length()-1)))
            line = line.substring(0, line.length()-1);
        //right trim, in case there is \r or other funnies
        while (line.length()>0 && (line.charAt(0)=='\n' || line.charAt(0)=='\r'))
            line = line.substring(1);
        if (!line.isEmpty()) {
            if (out!=null) out.println(printPrefix+line);
            if (log!=null && log.isDebugEnabled()) log.debug(logPrefix+line);
        }
    }

    public void onClose() {
        onLine(lineSoFar.toString());
        if (out!=null) out.flush();
        lineSoFar.setLength(0);
        finished = true;
        synchronized (this) { notifyAll(); }
    }

    private volatile boolean finished = false;

    /** convenience -- equivalent to calling join() */
    public void blockUntilFinished() throws InterruptedException {
        synchronized (this) { while (!finished) wait(); }
    }

    /** convenience -- similar to !Thread.isAlive() */
    public boolean isFinished() {
        return finished;
    }
}
