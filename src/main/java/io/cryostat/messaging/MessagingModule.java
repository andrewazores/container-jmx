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

import javax.inject.Named;
import javax.inject.Singleton;

import io.cryostat.core.log.Logger;
import io.cryostat.core.sys.Environment;
import io.cryostat.messaging.notifications.NotificationFactory;
import io.cryostat.messaging.notifications.NotificationsModule;
import io.cryostat.net.AuthManager;
import io.cryostat.net.HttpServer;

import com.google.gson.Gson;
import dagger.Module;
import dagger.Provides;

@Module(
        includes = {
            NotificationsModule.class,
        })
public abstract class MessagingModule {

    static final String WS_WORKER_POOL = "WS_WORKER_POOL";
    static final String WS_MAX_CONNECTIONS = "WS_MAX_CONNECTIONS";

    static final String MAX_CONNECTIONS_ENV_VAR = "CRYOSTAT_MAX_WS_CONNECTIONS";
    static final int MIN_CONNECTIONS = 1;
    static final int MAX_CONNECTIONS = 64;
    static final int DEFAULT_MAX_CONNECTIONS = 2;

    @Provides
    @Singleton
    static MessagingServer provideWebSocketMessagingServer(
            HttpServer server,
            Environment env,
            AuthManager authManager,
            NotificationFactory notificationFactory,
            @Named(WS_MAX_CONNECTIONS) int maxConnections,
            Logger logger,
            Gson gson) {
        return new MessagingServer(
                server, env, authManager, notificationFactory, maxConnections, logger, gson);
    }

    @Provides
    @Named(WS_MAX_CONNECTIONS)
    static int provideWebSocketMaxConnections(Environment env, Logger logger) {
        try {
            int maxConn =
                    Integer.parseInt(
                            env.getEnv(
                                    MAX_CONNECTIONS_ENV_VAR,
                                    String.valueOf(DEFAULT_MAX_CONNECTIONS)));
            if (maxConn > MAX_CONNECTIONS) {
                logger.info("Requested maximum WebSocket connections {} is too large.", maxConn);
                return MAX_CONNECTIONS;
            }
            if (maxConn < MIN_CONNECTIONS) {
                logger.info("Requested maximum WebSocket connections {} is too small.", maxConn);
                return MIN_CONNECTIONS;
            }
            return maxConn;
        } catch (NumberFormatException nfe) {
            logger.warn(nfe);
            return DEFAULT_MAX_CONNECTIONS;
        }
    }
}
