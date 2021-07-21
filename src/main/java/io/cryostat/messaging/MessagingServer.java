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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Named;

import io.cryostat.core.log.Logger;
import io.cryostat.core.sys.Clock;
import io.cryostat.core.sys.Environment;
import io.cryostat.messaging.notifications.NotificationFactory;
import io.cryostat.net.AuthManager;
import io.cryostat.net.HttpServer;
import io.cryostat.net.web.http.HttpMimeType;

import com.google.gson.Gson;

public class MessagingServer implements AutoCloseable {

    private final Set<WsClient> connections;
    private final HttpServer server;
    private final AuthManager authManager;
    private final NotificationFactory notificationFactory;
    private final ScheduledExecutorService limboPruner;
    private final Clock clock;
    private final int maxConnections;
    private final Logger logger;
    private final Gson gson;

    private Future<?> prunerTask;

    MessagingServer(
            HttpServer server,
            Environment env,
            AuthManager authManager,
            NotificationFactory notificationFactory,
            @Named(MessagingModule.WS_MAX_CONNECTIONS) int maxConnections,
            @Named(MessagingModule.LIMBO_PRUNER) ScheduledExecutorService limboPruner,
            Clock clock,
            Logger logger,
            Gson gson) {
        this.connections = new HashSet<>();
        this.server = server;
        this.authManager = authManager;
        this.notificationFactory = notificationFactory;
        this.maxConnections = maxConnections;
        this.limboPruner = limboPruner;
        this.clock = clock;
        this.logger = logger;
        this.gson = gson;
    }

    public void start() throws SocketException, UnknownHostException {
        logger.info("Max concurrent WebSocket connections: {}", maxConnections);

        prunerTask =
                this.limboPruner.scheduleAtFixedRate(
                        () -> {
                            long now = clock.getMonotonicTime();
                            synchronized (connections) {
                                for (WsClient wsc : connections) {
                                    long expiry =
                                            wsc.getConnectionTime() + TimeUnit.SECONDS.toNanos(10);
                                    boolean isOld = now > expiry;
                                    if (isOld && !wsc.isAccepted()) {
                                        removeConnection(wsc);
                                    }
                                }
                            }
                        },
                        0,
                        1,
                        TimeUnit.SECONDS);

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
                    }
                    logger.info("Connected remote client {}", remoteAddress);

                    WsClient wsc = new WsClient(this.logger, sws, clock);
                    sws.closeHandler((unused) -> removeConnection(wsc));
                    sws.textMessageHandler(
                            msg -> {
                                try {
                                    authManager
                                            .doAuthenticated(
                                                    sws::subProtocol,
                                                    authManager::validateWebSocketSubProtocol)
                                            .onSuccess(
                                                    () -> {
                                                        logger.info(
                                                                "Authenticated remote client {}",
                                                                remoteAddress);
                                                        sws.textMessageHandler(null);
                                                        wsc.setAccepted();
                                                        sendClientActivityNotification(
                                                                remoteAddress, "accepted");
                                                    })
                                            // 1002: WebSocket "Protocol Error" close reason
                                            .onFailure(
                                                    () -> {
                                                        logger.info(
                                                                "Disconnected remote client {} due to authentication failure",
                                                                remoteAddress);
                                                        sendClientActivityNotification(
                                                                remoteAddress, "auth failure");
                                                        sws.close(
                                                                (short) 1002,
                                                                "Invalid auth subprotocol");
                                                    })
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
                    addConnection(wsc);
                    sws.accept();
                    sendClientActivityNotification(remoteAddress, "connected");
                });
    }

    public void writeMessage(WsMessage message) {
        String json = gson.toJson(message);
        logger.info("Outgoing WS message: {}", json);
        synchronized (connections) {
            connections.forEach(c -> c.writeMessage(json));
        }
    }

    @Override
    public void close() {
        if (prunerTask != null) {
            prunerTask.cancel(false);
        }
        synchronized (connections) {
            connections.forEach(this::removeConnection);
            connections.clear();
        }
    }

    private void addConnection(WsClient wsc) {
        synchronized (connections) {
            connections.add(wsc);
        }
    }

    private void removeConnection(WsClient wsc) {
        synchronized (connections) {
            if (connections.remove(wsc)) {
                wsc.close();
                logger.info("Disconnected remote client {}", wsc.getRemoteAddress());
                sendClientActivityNotification(wsc.getRemoteAddress().toString(), "disconnected");
            }
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
