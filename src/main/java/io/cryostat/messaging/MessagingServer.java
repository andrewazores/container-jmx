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

import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Named;

import io.cryostat.core.log.Logger;
import io.cryostat.core.sys.Environment;
import io.cryostat.messaging.notifications.NotificationFactory;
import io.cryostat.net.AuthManager;
import io.cryostat.net.HttpServer;
import io.cryostat.net.web.http.HttpMimeType;

import com.google.gson.Gson;

public class MessagingServer implements AutoCloseable {

    private final BlockingQueue<String> inQ = new LinkedBlockingQueue<>();
    private final Map<WsClient, ScheduledFuture<?>> connections = new HashMap<>();
    private final HttpServer server;
    private final AuthManager authManager;
    private final NotificationFactory notificationFactory;
    private final int maxConnections;
    private final ScheduledExecutorService workerPool;
    private final Logger logger;
    private final Gson gson;

    MessagingServer(
            HttpServer server,
            Environment env,
            AuthManager authManager,
            NotificationFactory notificationFactory,
            @Named(MessagingModule.WS_MAX_CONNECTIONS) int maxConnections,
            @Named(MessagingModule.WS_WORKER_POOL) ScheduledExecutorService workerPool,
            Logger logger,
            Gson gson) {
        this.server = server;
        this.authManager = authManager;
        this.notificationFactory = notificationFactory;
        this.maxConnections = maxConnections;
        this.workerPool = workerPool;
        this.logger = logger;
        this.gson = gson;
    }

    public void start() throws SocketException, UnknownHostException {
        logger.info("Max concurrent WebSocket connections: {}", maxConnections);

        server.websocketHandler(
                (sws) -> {
                    if ("/api/v1/command".equals(sws.path())) {
                        sws.reject(410);
                        return;
                    } else if (!"/api/v1/notifications".equals(sws.path())) {
                        sws.reject(404);
                        return;
                    }
                    String remoteAddress = sws.remoteAddress().toString();
                    synchronized (connections) {
                        if (connections.size() >= maxConnections) {
                            logger.info(
                                    "Dropping remote client {} due to too many concurrent connections",
                                    remoteAddress);
                            sws.reject();
                            sendClientActivityNotification(remoteAddress, "dropped");
                            return;
                        }
                        logger.info("Connected remote client {}", remoteAddress);

                        WsClient crw = new WsClient(this.logger, sws);
                        sws.closeHandler(
                                (unused) -> {
                                    logger.info("Disconnected remote client {}", remoteAddress);
                                    sendClientActivityNotification(remoteAddress, "disconnected");
                                    removeConnection(crw);
                                });
                        sws.textMessageHandler(
                                msg -> {
                                    try {
                                        String proto = sws.subProtocol();
                                        authManager
                                                .doAuthenticated(
                                                        () -> proto,
                                                        authManager::validateWebSocketSubProtocol)
                                                .onSuccess(() -> crw.handle(msg))
                                                // 1002: WebSocket "Protocol Error" close reason
                                                .onFailure(
                                                        () ->
                                                                sws.close(
                                                                        (short) 1002,
                                                                        String.format(
                                                                                "Invalid subprotocol \"%s\"",
                                                                                proto)))
                                                .execute();
                                    } catch (InterruptedException
                                            | ExecutionException
                                            | TimeoutException e) {
                                        logger.info(e);
                                        // 1011: WebSocket "Internal Error" close reason
                                        sws.close(
                                                (short) 1011,
                                                String.format(
                                                        "Internal error: \"%s\"", e.getMessage()));
                                    }
                                });
                        // FIXME connection should only be added after client sends a message to
                        // authenticate itself
                        addConnection(crw);
                        sendClientActivityNotification(remoteAddress, "connected");
                        sws.accept();
                    }
                });
    }

    public String readMessage() {
        try {
            return inQ.take();
        } catch (InterruptedException e) {
            logger.warn(e);
            return null;
        }
    }

    public void writeMessage(WsMessage message) {
        String json = gson.toJson(message);
        logger.info("Outgoing WS message: {}", json);
        synchronized (connections) {
            connections.keySet().forEach(c -> c.writeMessage(json));
        }
    }

    void addConnection(WsClient crw) {
        synchronized (connections) {
            ScheduledFuture<?> task =
                    workerPool.scheduleWithFixedDelay(
                            () -> {
                                String msg = crw.readMessage();
                                if (msg != null) {
                                    inQ.add(msg);
                                }
                            },
                            0,
                            10,
                            TimeUnit.MILLISECONDS);
            connections.put(crw, task);
        }
    }

    void removeConnection(WsClient crw) {
        synchronized (connections) {
            ScheduledFuture<?> task = connections.remove(crw);
            if (task != null) {
                task.cancel(false);
                crw.close();
            }
        }
    }

    @Override
    public void close() {
        closeConnections();
    }

    private void closeConnections() {
        synchronized (connections) {
            workerPool.shutdown();
            connections.keySet().forEach(WsClient::close);
            connections.clear();
        }
    }

    private void sendClientActivityNotification(String remote, String status) {
        notificationFactory
                .createBuilder()
                .metaCategory("WS_CLIENT_ACTIVITY")
                .metaType(HttpMimeType.JSON)
                .message(Map.of(remote, status))
                .build()
                .send();
    }
}
