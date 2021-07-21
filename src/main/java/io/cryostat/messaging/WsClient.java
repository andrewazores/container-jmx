/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.messaging;

import io.cryostat.core.log.Logger;
import io.cryostat.core.sys.Clock;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.SocketAddress;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

class WsClient implements AutoCloseable {

    private final ServerWebSocket sws;
    private final long connectionTime;
    private volatile boolean isAccepted;
    private final Logger logger;

    WsClient(Logger logger, ServerWebSocket sws, Clock clock) {
        this.logger = logger;
        this.sws = sws;
        this.connectionTime = clock.getMonotonicTime();
    }

    void setAccepted() {
        this.isAccepted = true;
    }

    boolean isAccepted() {
        return isAccepted;
    }

    long getConnectionTime() {
        return connectionTime;
    }

    void writeMessage(String message) {
        if (isAccepted() && !this.sws.isClosed()) {
            WsMessageEmitted evt =
                    new WsMessageEmitted(
                            sws.remoteAddress().host(),
                            sws.remoteAddress().port(),
                            sws.uri(),
                            message.length());
            evt.begin();

            try {
                this.sws.writeTextMessage(message);

            } catch (Exception e) {
                logger.warn(e);
                evt.setExceptionThrown(true);

            } finally {
                evt.end();
                if (evt.shouldCommit()) {
                    evt.commit();
                }
            }
        }
    }

    SocketAddress getRemoteAddress() {
        return sws.remoteAddress();
    }

    @Name("io.cryostat.messaging.WsClient.WsMessageEmitted")
    @Label("WebSocket Message Emitted")
    @Category("Cryostat")
    @SuppressFBWarnings(
            value = "URF_UNREAD_FIELD",
            justification = "Event fields are recorded with JFR instead of accessed directly")
    public static class WsMessageEmitted extends Event {
        String host;
        int port;
        String path;
        int msgLen;
        boolean exceptionThrown;

        public WsMessageEmitted(String host, int port, String path, int msgLen) {
            this.host = host;
            this.port = port;
            this.path = path;
            this.msgLen = msgLen;
            this.exceptionThrown = false;
        }

        public void setExceptionThrown(boolean exceptionThrown) {
            this.exceptionThrown = exceptionThrown;
        }
    }

    @Override
    public void close() {
        if (!sws.isClosed()) {
            sws.close();
        }
    }
}
