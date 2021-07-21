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

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

import io.cryostat.MainModule;
import io.cryostat.core.log.Logger;
import io.cryostat.core.sys.Environment;
import io.cryostat.messaging.notifications.Notification;
import io.cryostat.messaging.notifications.NotificationFactory;
import io.cryostat.net.AuthManager;
import io.cryostat.net.AuthenticatedAction;
import io.cryostat.net.HttpServer;
import io.cryostat.net.web.http.HttpMimeType;

import com.google.gson.Gson;
import io.vertx.core.Handler;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MessagingServerTest {

    MessagingServer server;
    @Mock Environment env;
    @Mock Logger logger;
    @Mock HttpServer httpServer;
    @Mock AuthManager authManager;
    Gson gson = MainModule.provideGson(logger);
    @Mock ServerWebSocket sws;
    @Mock NotificationFactory notificationFactory;
    @Mock Notification notification;
    @Mock Notification.Builder notificationBuilder;
    @Mock AuthenticatedAction authAction;

    @BeforeEach
    void setup() {
        lenient().when(notificationFactory.createBuilder()).thenReturn(notificationBuilder);
        lenient()
                .when(notificationBuilder.metaCategory(Mockito.any()))
                .thenReturn(notificationBuilder);
        lenient()
                .when(notificationBuilder.metaType(Mockito.any(Notification.MetaType.class)))
                .thenReturn(notificationBuilder);
        lenient()
                .when(notificationBuilder.metaType(Mockito.any(HttpMimeType.class)))
                .thenReturn(notificationBuilder);
        lenient().when(notificationBuilder.message(Mockito.any())).thenReturn(notificationBuilder);
        lenient().when(notificationBuilder.build()).thenReturn(notification);

        lenient().when(authAction.onSuccess(Mockito.any())).thenReturn(authAction);
        lenient().when(authAction.onFailure(Mockito.any())).thenReturn(authAction);
        lenient()
                .when(authManager.doAuthenticated(Mockito.any(), Mockito.any()))
                .thenReturn(authAction);

        SocketAddress addr = Mockito.mock(SocketAddress.class);
        lenient().when(addr.toString()).thenReturn("mockaddr");
        lenient().when(sws.remoteAddress()).thenReturn(addr);
        lenient().when(sws.path()).thenReturn("/api/v1/notifications");
        lenient().when(sws.isClosed()).thenReturn(false);

        server =
                new MessagingServer(
                        httpServer, env, authManager, notificationFactory, 2, logger, gson);
    }

    @Test
    void shouldNotWriteToLimboClients() throws Exception {
        server.start();

        ArgumentCaptor<Handler> websocketHandlerCaptor = ArgumentCaptor.forClass(Handler.class);
        Mockito.verify(httpServer).websocketHandler(websocketHandlerCaptor.capture());
        websocketHandlerCaptor.getValue().handle(sws);
        verify(sws).accept();

        TestMessage message = new TestMessage("msgId", "test", "message");
        server.writeMessage(message);
        verify(sws, Mockito.never()).writeTextMessage(gson.toJson(message));
    }

    @Test
    void writeShouldDelegateToAllClients() throws Exception {
        ServerWebSocket sws2 = Mockito.mock(ServerWebSocket.class);
        SocketAddress addr = Mockito.mock(SocketAddress.class);
        when(addr.toString()).thenReturn("mockaddr");
        when(addr.host()).thenReturn("client");
        when(addr.port()).thenReturn(12345);
        when(sws2.remoteAddress()).thenReturn(addr);
        when(sws2.path()).thenReturn("/api/v1/notifications");
        when(sws2.isClosed()).thenReturn(false);
        when(sws2.uri()).thenReturn("uri2");

        server.start();

        ArgumentCaptor<Handler> websocketHandlerCaptor = ArgumentCaptor.forClass(Handler.class);
        Mockito.verify(httpServer).websocketHandler(websocketHandlerCaptor.capture());
        websocketHandlerCaptor.getValue().handle(sws);
        websocketHandlerCaptor.getValue().handle(sws2);
        verify(sws).accept();
        verify(sws2).accept();

        ArgumentCaptor<Handler> textMessageHandlerCaptor = ArgumentCaptor.forClass(Handler.class);
        verify(sws).textMessageHandler(textMessageHandlerCaptor.capture());
        verify(sws2).textMessageHandler(textMessageHandlerCaptor.capture());
        textMessageHandlerCaptor.getAllValues().get(0).handle("irrelevant");
        textMessageHandlerCaptor.getAllValues().get(1).handle("irrelevant");

        ArgumentCaptor<Runnable> authSuccessCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(authAction, Mockito.times(2)).onSuccess(authSuccessCaptor.capture());
        authSuccessCaptor.getAllValues().forEach(Runnable::run);

        TestMessage message = new TestMessage("msgId", "test", "message");
        server.writeMessage(message);
        verify(sws).writeTextMessage(gson.toJson(message));
        verify(sws2).writeTextMessage(gson.toJson(message));
    }

    @Test
    void shouldStopHandlingAfterFirstMessage() throws Exception {
        server.start();

        ArgumentCaptor<Handler> websocketHandlerCaptor = ArgumentCaptor.forClass(Handler.class);
        Mockito.verify(httpServer).websocketHandler(websocketHandlerCaptor.capture());
        websocketHandlerCaptor.getValue().handle(sws);
        verify(sws).accept();

        ArgumentCaptor<Handler> textMessageHandlerCaptor = ArgumentCaptor.forClass(Handler.class);
        verify(sws).textMessageHandler(textMessageHandlerCaptor.capture());
        textMessageHandlerCaptor.getValue().handle("irrelevant");

        ArgumentCaptor<Runnable> authSuccessCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(authAction).onSuccess(authSuccessCaptor.capture());
        authSuccessCaptor.getValue().run();

        verify(sws).textMessageHandler(null);
    }

    @Test
    void authFailureShouldRejectConnection() throws Exception {
        server.start();

        ArgumentCaptor<Handler> websocketHandlerCaptor = ArgumentCaptor.forClass(Handler.class);
        Mockito.verify(httpServer).websocketHandler(websocketHandlerCaptor.capture());
        websocketHandlerCaptor.getValue().handle(sws);
        verify(sws).accept();

        ArgumentCaptor<Handler> textMessageHandlerCaptor = ArgumentCaptor.forClass(Handler.class);
        verify(sws).textMessageHandler(textMessageHandlerCaptor.capture());
        textMessageHandlerCaptor.getValue().handle("irrelevant");

        ArgumentCaptor<Runnable> authFailCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(authAction).onFailure(authFailCaptor.capture());
        authFailCaptor.getValue().run();

        verify(sws).close((short) 1002, "Invalid auth subprotocol");
    }

    @Test
    void closeHandlerShouldCloseWebSocket() throws SocketException, UnknownHostException {
        server.start();

        ArgumentCaptor<Handler> websocketHandlerCaptor = ArgumentCaptor.forClass(Handler.class);
        Mockito.verify(httpServer).websocketHandler(websocketHandlerCaptor.capture());
        websocketHandlerCaptor.getValue().handle(sws);
        verify(sws).accept();

        ArgumentCaptor<Handler> closeHandlerCaptor = ArgumentCaptor.forClass(Handler.class);
        verify(sws).closeHandler(closeHandlerCaptor.capture());

        ArgumentCaptor<Handler> textMessageHandlerCaptor = ArgumentCaptor.forClass(Handler.class);
        verify(sws).textMessageHandler(textMessageHandlerCaptor.capture());
        textMessageHandlerCaptor.getValue().handle("irrelevant");

        ArgumentCaptor<Runnable> authSuccessCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(authAction).onSuccess(authSuccessCaptor.capture());
        authSuccessCaptor.getValue().run();
        verify(sws).textMessageHandler(null);

        closeHandlerCaptor.getValue().handle(null);
        verify(sws).close();
    }

    static class TestMessage extends WsMessage {
        List<String> msgs;

        TestMessage(String... msgs) {
            this.msgs = Arrays.asList(msgs);
        }
    }
}
