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
package io.cryostat.net.web.http.api.v1;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.openjdk.jmc.rjmx.services.jfr.IFlightRecorderService;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.sys.Environment;
import io.cryostat.core.sys.FileSystem;
import io.cryostat.net.AuthManager;
import io.cryostat.net.ConnectionDescriptor;
import io.cryostat.net.TargetConnectionManager;
import io.cryostat.net.reports.ReportService;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.impl.HttpStatusException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

@ExtendWith(MockitoExtension.class)
class TargetRecordingUploadPostHandlerTest {

    TargetRecordingUploadPostHandler handler;
    @Mock AuthManager auth;
    @Mock Environment env;
    @Mock TargetConnectionManager targetConnectionManager;
    @Mock WebClient webClient;
    @Mock FileSystem fs;

    @Mock RoutingContext ctx;
    @Mock HttpServerRequest req;
    @Mock HttpServerResponse resp;
    @Mock JFRConnection conn;

    static final String DATASOURCE_URL = "http://localhost:8080";

    @BeforeEach
    void setup() {
        this.handler =
                new TargetRecordingUploadPostHandler(
                        auth, env, targetConnectionManager, webClient, fs);
    }

    @Test
    void shouldHandlePOST() {
        MatcherAssert.assertThat(handler.httpMethod(), Matchers.equalTo(HttpMethod.POST));
    }

    @Test
    void shouldHandleCorrectPath() {
        MatcherAssert.assertThat(
                handler.path(),
                Matchers.equalTo("/api/v1/targets/:targetId/recordings/:recordingName/upload"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "notaurl",
                "almost:/valid",
                "*notok",
                "localhost:",
                "localhost:abc",
                "localhost::9091",
                "http:///localhost:9091",
                "http:://localhost:9091"
            })
    @NullAndEmptySource
    void shouldThrow501IfDatasourceUrlMalformed(String rawUrl) {
        Mockito.when(auth.validateHttpHeader(Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        Mockito.when(env.getEnv("GRAFANA_DATASOURCE_URL")).thenReturn(rawUrl);
        Mockito.when(ctx.response()).thenReturn(resp);
        Mockito.when(
                        resp.putHeader(
                                Mockito.any(CharSequence.class), Mockito.any(CharSequence.class)))
                .thenReturn(resp);

        HttpStatusException ex =
                Assertions.assertThrows(HttpStatusException.class, () -> handler.handle(ctx));
        MatcherAssert.assertThat(ex.getStatusCode(), Matchers.equalTo(501));
    }

    @Test
    void shouldThrowExceptionIfRecordingNotFound() throws Exception {
        Mockito.when(ctx.request()).thenReturn(req);
        Mockito.when(ctx.response()).thenReturn(resp);
        Mockito.when(
                        resp.putHeader(
                                Mockito.any(CharSequence.class), Mockito.any(CharSequence.class)))
                .thenReturn(resp);
        Mockito.when(ctx.pathParam("targetId")).thenReturn("fooHost:1234");
        Mockito.when(req.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        Mockito.when(auth.validateHttpHeader(Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        Mockito.when(
                        targetConnectionManager.executeConnectedTask(
                                Mockito.any(ConnectionDescriptor.class), Mockito.any()))
                .thenAnswer(
                        arg0 ->
                                ((TargetConnectionManager.ConnectedTask<Object>)
                                                arg0.getArgument(1))
                                        .execute(conn));
        IFlightRecorderService svc = Mockito.mock(IFlightRecorderService.class);
        Mockito.when(conn.getService()).thenReturn(svc);
        Mockito.when(svc.getAvailableRecordings()).thenReturn(Collections.emptyList());
        Mockito.when(env.getEnv("GRAFANA_DATASOURCE_URL")).thenReturn(DATASOURCE_URL);

        HttpStatusException ex =
                Assertions.assertThrows(
                        HttpStatusException.class,
                        () -> {
                            handler.handle(ctx);
                        });
        MatcherAssert.assertThat(ex.getStatusCode(), Matchers.equalTo(404));
        MatcherAssert.assertThat(
                ex.getCause(), Matchers.instanceOf(ReportService.RecordingNotFoundException.class));
    }

    @Test
    void shouldDoUpload() throws Exception {
        Mockito.when(auth.validateHttpHeader(Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        Mockito.when(
                        targetConnectionManager.executeConnectedTask(
                                Mockito.any(ConnectionDescriptor.class), Mockito.any()))
                .thenAnswer(
                        arg0 ->
                                ((TargetConnectionManager.ConnectedTask<Object>)
                                                arg0.getArgument(1))
                                        .execute(conn));
        IFlightRecorderService svc = Mockito.mock(IFlightRecorderService.class);
        IRecordingDescriptor rec = Mockito.mock(IRecordingDescriptor.class);
        InputStream stream = Mockito.mock(InputStream.class);
        Mockito.when(conn.getService()).thenReturn(svc);
        Mockito.when(svc.getAvailableRecordings()).thenReturn(List.of(rec));
        Mockito.when(rec.getName()).thenReturn("foo");
        Mockito.when(svc.openStream(Mockito.any(), Mockito.anyBoolean())).thenReturn(stream);
        Mockito.when(env.getEnv("GRAFANA_DATASOURCE_URL")).thenReturn(DATASOURCE_URL);

        Mockito.when(ctx.pathParam("targetId")).thenReturn("fooTarget");
        Mockito.when(ctx.pathParam("recordingName")).thenReturn("foo");

        HttpRequest<Buffer> httpReq = Mockito.mock(HttpRequest.class);
        HttpResponse<Buffer> httpResp = Mockito.mock(HttpResponse.class);
        Mockito.when(webClient.postAbs(Mockito.anyString())).thenReturn(httpReq);
        Mockito.when(httpReq.timeout(Mockito.anyLong())).thenReturn(httpReq);
        Mockito.doAnswer(
                        new Answer<Void>() {
                            @Override
                            public Void answer(InvocationOnMock args) throws Throwable {
                                AsyncResult<HttpResponse<Buffer>> asyncResult =
                                        Mockito.mock(AsyncResult.class);
                                Mockito.when(asyncResult.result()).thenReturn(httpResp);
                                Mockito.when(httpResp.statusCode()).thenReturn(200);
                                Mockito.when(httpResp.statusMessage()).thenReturn("OK");
                                Mockito.when(httpResp.bodyAsString()).thenReturn("HELLO");
                                ((Handler<AsyncResult<HttpResponse<Buffer>>>) args.getArgument(1))
                                        .handle(asyncResult);
                                return null;
                            }
                        })
                .when(httpReq)
                .sendMultipartForm(Mockito.any(), Mockito.any());

        Mockito.when(ctx.request()).thenReturn(req);
        Mockito.when(req.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        Mockito.when(ctx.response()).thenReturn(resp);
        Mockito.when(
                        resp.putHeader(
                                Mockito.any(CharSequence.class), Mockito.any(CharSequence.class)))
                .thenReturn(resp);

        handler.handle(ctx);

        Mockito.verify(resp).setStatusCode(200);
        Mockito.verify(resp).setStatusMessage("OK");
        Mockito.verify(resp).end("HELLO");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(webClient).postAbs(urlCaptor.capture());
        MatcherAssert.assertThat(
                urlCaptor.getValue(), Matchers.equalTo(DATASOURCE_URL.concat("/load")));
    }

    @Test
    void shouldHandleInvalidResponseStatusCode() throws Exception {
        Mockito.when(auth.validateHttpHeader(Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        Mockito.when(
                        targetConnectionManager.executeConnectedTask(
                                Mockito.any(ConnectionDescriptor.class), Mockito.any()))
                .thenAnswer(
                        arg0 ->
                                ((TargetConnectionManager.ConnectedTask<Object>)
                                                arg0.getArgument(1))
                                        .execute(conn));
        IFlightRecorderService svc = Mockito.mock(IFlightRecorderService.class);
        IRecordingDescriptor rec = Mockito.mock(IRecordingDescriptor.class);
        InputStream stream = Mockito.mock(InputStream.class);
        Mockito.when(conn.getService()).thenReturn(svc);
        Mockito.when(svc.getAvailableRecordings()).thenReturn(List.of(rec));
        Mockito.when(rec.getName()).thenReturn("foo");
        Mockito.when(svc.openStream(Mockito.any(), Mockito.anyBoolean())).thenReturn(stream);
        Mockito.when(env.getEnv("GRAFANA_DATASOURCE_URL")).thenReturn(DATASOURCE_URL);

        Mockito.when(ctx.pathParam("targetId")).thenReturn("fooTarget");
        Mockito.when(ctx.pathParam("recordingName")).thenReturn("foo");

        HttpRequest<Buffer> httpReq = Mockito.mock(HttpRequest.class);
        HttpResponse<Buffer> httpResp = Mockito.mock(HttpResponse.class);
        Mockito.when(webClient.postAbs(Mockito.anyString())).thenReturn(httpReq);
        Mockito.when(httpReq.timeout(Mockito.anyLong())).thenReturn(httpReq);
        Mockito.doAnswer(
                        new Answer<Void>() {
                            @Override
                            public Void answer(InvocationOnMock args) throws Throwable {
                                AsyncResult<HttpResponse<Buffer>> asyncResult =
                                        Mockito.mock(AsyncResult.class);
                                Mockito.when(asyncResult.result()).thenReturn(httpResp);
                                Mockito.when(httpResp.statusCode()).thenReturn(418);
                                Mockito.when(httpResp.statusMessage()).thenReturn("I'm a teapot");
                                Mockito.when(httpResp.bodyAsString()).thenReturn("short and stout");
                                ((Handler<AsyncResult<HttpResponse<Buffer>>>) args.getArgument(1))
                                        .handle(asyncResult);
                                return null;
                            }
                        })
                .when(httpReq)
                .sendMultipartForm(Mockito.any(), Mockito.any());

        Mockito.when(ctx.request()).thenReturn(req);
        Mockito.when(req.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        Mockito.when(ctx.response()).thenReturn(resp);
        Mockito.when(
                        resp.putHeader(
                                Mockito.any(CharSequence.class), Mockito.any(CharSequence.class)))
                .thenReturn(resp);

        HttpStatusException e =
                Assertions.assertThrows(HttpStatusException.class, () -> handler.handle(ctx));

        MatcherAssert.assertThat(e.getStatusCode(), Matchers.equalTo(512));
        MatcherAssert.assertThat(
                e.getPayload(),
                Matchers.equalTo(
                        "Invalid response from datasource server; datasource URL may be incorrect, or server may not be functioning properly: 418 I'm a teapot"));

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(webClient).postAbs(urlCaptor.capture());
        MatcherAssert.assertThat(
                urlCaptor.getValue(), Matchers.equalTo(DATASOURCE_URL.concat("/load")));
    }

    @Test
    void shouldHandleNullStatusMessage() throws Exception {
        Mockito.when(auth.validateHttpHeader(Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        Mockito.when(
                        targetConnectionManager.executeConnectedTask(
                                Mockito.any(ConnectionDescriptor.class), Mockito.any()))
                .thenAnswer(
                        arg0 ->
                                ((TargetConnectionManager.ConnectedTask<Object>)
                                                arg0.getArgument(1))
                                        .execute(conn));
        IFlightRecorderService svc = Mockito.mock(IFlightRecorderService.class);
        IRecordingDescriptor rec = Mockito.mock(IRecordingDescriptor.class);
        InputStream stream = Mockito.mock(InputStream.class);
        Mockito.when(conn.getService()).thenReturn(svc);
        Mockito.when(svc.getAvailableRecordings()).thenReturn(List.of(rec));
        Mockito.when(rec.getName()).thenReturn("foo");
        Mockito.when(svc.openStream(Mockito.any(), Mockito.anyBoolean())).thenReturn(stream);
        Mockito.when(env.getEnv("GRAFANA_DATASOURCE_URL")).thenReturn(DATASOURCE_URL);

        Mockito.when(ctx.pathParam("targetId")).thenReturn("fooTarget");
        Mockito.when(ctx.pathParam("recordingName")).thenReturn("foo");

        HttpRequest<Buffer> httpReq = Mockito.mock(HttpRequest.class);
        HttpResponse<Buffer> httpResp = Mockito.mock(HttpResponse.class);
        Mockito.when(webClient.postAbs(Mockito.anyString())).thenReturn(httpReq);
        Mockito.when(httpReq.timeout(Mockito.anyLong())).thenReturn(httpReq);
        Mockito.doAnswer(
                        new Answer<Void>() {
                            @Override
                            public Void answer(InvocationOnMock args) throws Throwable {
                                AsyncResult<HttpResponse<Buffer>> asyncResult =
                                        Mockito.mock(AsyncResult.class);
                                Mockito.when(asyncResult.result()).thenReturn(httpResp);
                                Mockito.when(httpResp.statusCode()).thenReturn(200);
                                Mockito.when(httpResp.statusMessage()).thenReturn(null);
                                Mockito.when(httpResp.bodyAsString()).thenReturn("body");
                                ((Handler<AsyncResult<HttpResponse<Buffer>>>) args.getArgument(1))
                                        .handle(asyncResult);
                                return null;
                            }
                        })
                .when(httpReq)
                .sendMultipartForm(Mockito.any(), Mockito.any());

        Mockito.when(ctx.request()).thenReturn(req);
        Mockito.when(req.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        Mockito.when(ctx.response()).thenReturn(resp);
        Mockito.when(
                        resp.putHeader(
                                Mockito.any(CharSequence.class), Mockito.any(CharSequence.class)))
                .thenReturn(resp);

        HttpStatusException e =
                Assertions.assertThrows(HttpStatusException.class, () -> handler.handle(ctx));

        MatcherAssert.assertThat(e.getStatusCode(), Matchers.equalTo(512));
        MatcherAssert.assertThat(
                e.getPayload(),
                Matchers.equalTo(
                        "Invalid response from datasource server; datasource URL may be incorrect, or server may not be functioning properly: 200 null"));

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(webClient).postAbs(urlCaptor.capture());
        MatcherAssert.assertThat(
                urlCaptor.getValue(), Matchers.equalTo(DATASOURCE_URL.concat("/load")));
    }

    @Test
    void shouldHandleNullResponseBody() throws Exception {
        Mockito.when(auth.validateHttpHeader(Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        Mockito.when(
                        targetConnectionManager.executeConnectedTask(
                                Mockito.any(ConnectionDescriptor.class), Mockito.any()))
                .thenAnswer(
                        arg0 ->
                                ((TargetConnectionManager.ConnectedTask<Object>)
                                                arg0.getArgument(1))
                                        .execute(conn));
        IFlightRecorderService svc = Mockito.mock(IFlightRecorderService.class);
        IRecordingDescriptor rec = Mockito.mock(IRecordingDescriptor.class);
        InputStream stream = Mockito.mock(InputStream.class);
        Mockito.when(conn.getService()).thenReturn(svc);
        Mockito.when(svc.getAvailableRecordings()).thenReturn(List.of(rec));
        Mockito.when(rec.getName()).thenReturn("foo");
        Mockito.when(svc.openStream(Mockito.any(), Mockito.anyBoolean())).thenReturn(stream);
        Mockito.when(env.getEnv("GRAFANA_DATASOURCE_URL")).thenReturn(DATASOURCE_URL);

        Mockito.when(ctx.pathParam("targetId")).thenReturn("fooTarget");
        Mockito.when(ctx.pathParam("recordingName")).thenReturn("foo");

        HttpRequest<Buffer> httpReq = Mockito.mock(HttpRequest.class);
        HttpResponse<Buffer> httpResp = Mockito.mock(HttpResponse.class);
        Mockito.when(webClient.postAbs(Mockito.anyString())).thenReturn(httpReq);
        Mockito.when(httpReq.timeout(Mockito.anyLong())).thenReturn(httpReq);
        Mockito.doAnswer(
                        new Answer<Void>() {
                            @Override
                            public Void answer(InvocationOnMock args) throws Throwable {
                                AsyncResult<HttpResponse<Buffer>> asyncResult =
                                        Mockito.mock(AsyncResult.class);
                                Mockito.when(asyncResult.result()).thenReturn(httpResp);
                                Mockito.when(httpResp.statusCode()).thenReturn(200);
                                Mockito.when(httpResp.statusMessage()).thenReturn("OK");
                                Mockito.when(httpResp.bodyAsString()).thenReturn(null);
                                ((Handler<AsyncResult<HttpResponse<Buffer>>>) args.getArgument(1))
                                        .handle(asyncResult);
                                return null;
                            }
                        })
                .when(httpReq)
                .sendMultipartForm(Mockito.any(), Mockito.any());

        Mockito.when(ctx.request()).thenReturn(req);
        Mockito.when(req.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        Mockito.when(ctx.response()).thenReturn(resp);
        Mockito.when(
                        resp.putHeader(
                                Mockito.any(CharSequence.class), Mockito.any(CharSequence.class)))
                .thenReturn(resp);

        HttpStatusException e =
                Assertions.assertThrows(HttpStatusException.class, () -> handler.handle(ctx));

        MatcherAssert.assertThat(e.getStatusCode(), Matchers.equalTo(512));
        MatcherAssert.assertThat(
                e.getPayload(),
                Matchers.equalTo(
                        "Invalid response from datasource server; datasource URL may be incorrect, or server may not be functioning properly: 200 OK"));

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(webClient).postAbs(urlCaptor.capture());
        MatcherAssert.assertThat(
                urlCaptor.getValue(), Matchers.equalTo(DATASOURCE_URL.concat("/load")));
    }
}
