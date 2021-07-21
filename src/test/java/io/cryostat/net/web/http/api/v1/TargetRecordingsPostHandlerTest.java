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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.QuantityConversionException;
import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;
import org.openjdk.jmc.rjmx.services.jfr.IFlightRecorderService;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

import io.cryostat.MainModule;
import io.cryostat.core.log.Logger;
import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.templates.TemplateService;
import io.cryostat.core.templates.TemplateType;
import io.cryostat.net.AuthManager;
import io.cryostat.net.ConnectionDescriptor;
import io.cryostat.net.TargetConnectionManager;
import io.cryostat.net.web.WebServer;
import io.cryostat.recordings.RecordingOptionsBuilderFactory;
import io.cryostat.recordings.RecordingTargetHelper;

import com.google.gson.Gson;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.impl.HttpStatusException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TargetRecordingsPostHandlerTest {

    TargetRecordingsPostHandler handler;
    @Mock AuthManager auth;
    @Mock TargetConnectionManager targetConnectionManager;
    @Mock RecordingTargetHelper recordingTargetHelper;
    @Mock RecordingOptionsBuilderFactory recordingOptionsBuilderFactory;
    @Mock WebServer webServer;
    @Mock Logger logger;
    Gson gson = MainModule.provideGson(logger);

    @Mock JFRConnection connection;
    @Mock IFlightRecorderService service;
    @Mock TemplateService templateService;
    @Mock RoutingContext ctx;
    @Mock HttpServerRequest req;
    @Mock HttpServerResponse resp;

    @BeforeEach
    void setup() {
        this.handler =
                new TargetRecordingsPostHandler(
                        auth,
                        targetConnectionManager,
                        recordingTargetHelper,
                        recordingOptionsBuilderFactory,
                        () -> webServer,
                        gson);
    }

    @Test
    void shouldHandlePOST() {
        MatcherAssert.assertThat(handler.httpMethod(), Matchers.equalTo(HttpMethod.POST));
    }

    @Test
    void shouldHandleCorrectPath() {
        MatcherAssert.assertThat(
                handler.path(), Matchers.equalTo("/api/v1/targets/:targetId/recordings"));
    }

    @Test
    void shouldStartRecording() throws Exception {
        Mockito.when(auth.validateHttpHeader(Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        Mockito.when(targetConnectionManager.executeConnectedTask(Mockito.any(), Mockito.any()))
                .thenAnswer(
                        arg0 ->
                                ((TargetConnectionManager.ConnectedTask<Object>)
                                                arg0.getArgument(1))
                                        .execute(connection));
        Mockito.when(connection.getService()).thenReturn(service);
        IConstrainedMap<String> recordingOptions = Mockito.mock(IConstrainedMap.class);
        RecordingOptionsBuilder recordingOptionsBuilder =
                Mockito.mock(RecordingOptionsBuilder.class);
        Mockito.when(recordingOptionsBuilderFactory.create(Mockito.any()))
                .thenReturn(recordingOptionsBuilder);
        Mockito.when(recordingOptionsBuilder.name(Mockito.any()))
                .thenReturn(recordingOptionsBuilder);
        Mockito.when(recordingOptionsBuilder.duration(Mockito.anyLong()))
                .thenReturn(recordingOptionsBuilder);
        Mockito.when(recordingOptionsBuilder.toDisk(Mockito.anyBoolean()))
                .thenReturn(recordingOptionsBuilder);
        Mockito.when(recordingOptionsBuilder.maxAge(Mockito.anyLong()))
                .thenReturn(recordingOptionsBuilder);
        Mockito.when(recordingOptionsBuilder.maxSize(Mockito.anyLong()))
                .thenReturn(recordingOptionsBuilder);
        Mockito.when(recordingOptionsBuilder.build()).thenReturn(recordingOptions);

        Mockito.when(
                        webServer.getDownloadURL(
                                Mockito.any(JFRConnection.class), Mockito.anyString()))
                .thenReturn("example-download-url");
        Mockito.when(webServer.getReportURL(Mockito.any(JFRConnection.class), Mockito.anyString()))
                .thenReturn("example-report-url");

        Mockito.when(ctx.pathParam("targetId")).thenReturn("fooHost:9091");
        MultiMap attrs = MultiMap.caseInsensitiveMultiMap();
        Mockito.when(ctx.request()).thenReturn(req);
        Mockito.when(req.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        Mockito.when(req.formAttributes()).thenReturn(attrs);
        attrs.add("recordingName", "someRecording");
        attrs.add("events", "template=Foo,type=CUSTOM");
        attrs.add("duration", "10");
        attrs.add("toDisk", "true");
        attrs.add("maxAge", "50");
        attrs.add("maxSize", "64");
        Mockito.when(ctx.response()).thenReturn(resp);
        Mockito.when(
                        resp.putHeader(
                                Mockito.any(CharSequence.class), Mockito.any(CharSequence.class)))
                .thenReturn(resp);

        IRecordingDescriptor descriptor = createDescriptor("someRecording");
        Mockito.when(
                        recordingTargetHelper.startRecording(
                                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(descriptor);

        handler.handle(ctx);

        Mockito.verify(recordingOptionsBuilder).name("someRecording");
        Mockito.verify(recordingOptionsBuilder).duration(TimeUnit.SECONDS.toMillis(10));
        Mockito.verify(recordingOptionsBuilder).toDisk(true);
        Mockito.verify(recordingOptionsBuilder).maxAge(50L);
        Mockito.verify(recordingOptionsBuilder).maxSize(64L);

        ArgumentCaptor<ConnectionDescriptor> connectionDescriptorCaptor =
                ArgumentCaptor.forClass(ConnectionDescriptor.class);

        ArgumentCaptor<IConstrainedMap<String>> recordingOptionsCaptor =
                ArgumentCaptor.forClass(IConstrainedMap.class);

        ArgumentCaptor<String> templateNameCaptor = ArgumentCaptor.forClass(String.class);

        ArgumentCaptor<TemplateType> templateTypeCaptor =
                ArgumentCaptor.forClass(TemplateType.class);

        Mockito.verify(recordingTargetHelper)
                .startRecording(
                        connectionDescriptorCaptor.capture(),
                        recordingOptionsCaptor.capture(),
                        templateNameCaptor.capture(),
                        templateTypeCaptor.capture());

        ConnectionDescriptor connectionDescriptor = connectionDescriptorCaptor.getValue();
        MatcherAssert.assertThat(
                connectionDescriptor.getTargetId(), Matchers.equalTo("fooHost:9091"));
        MatcherAssert.assertThat(
                connectionDescriptor.getCredentials().isEmpty(), Matchers.is(true));

        IConstrainedMap<String> actualRecordingOptions = recordingOptionsCaptor.getValue();
        MatcherAssert.assertThat(actualRecordingOptions, Matchers.sameInstance(recordingOptions));

        MatcherAssert.assertThat(templateNameCaptor.getValue(), Matchers.equalTo("Foo"));

        MatcherAssert.assertThat(
                templateTypeCaptor.getValue(), Matchers.equalTo(TemplateType.CUSTOM));

        Mockito.verify(resp).setStatusCode(201);
        Mockito.verify(resp).putHeader(HttpHeaders.LOCATION, "/someRecording");
        Mockito.verify(resp).putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        Mockito.verify(resp)
                .end(
                        "{\"downloadUrl\":\"example-download-url\",\"reportUrl\":\"example-report-url\",\"id\":1,\"name\":\"someRecording\",\"state\":\"STOPPED\",\"startTime\":0,\"duration\":0,\"continuous\":false,\"toDisk\":false,\"maxSize\":0,\"maxAge\":0}");
    }

    @Test
    void shouldHandleNameCollision() throws Exception {
        Mockito.when(auth.validateHttpHeader(Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        Mockito.when(targetConnectionManager.executeConnectedTask(Mockito.any(), Mockito.any()))
                .thenAnswer(
                        arg0 ->
                                ((TargetConnectionManager.ConnectedTask<Object>)
                                                arg0.getArgument(1))
                                        .execute(connection));

        IConstrainedMap<String> recordingOptions = Mockito.mock(IConstrainedMap.class);
        RecordingOptionsBuilder recordingOptionsBuilder =
                Mockito.mock(RecordingOptionsBuilder.class);
        Mockito.when(recordingOptionsBuilderFactory.create(Mockito.any()))
                .thenReturn(recordingOptionsBuilder);
        Mockito.when(recordingOptionsBuilder.name(Mockito.any()))
                .thenReturn(recordingOptionsBuilder);
        Mockito.when(recordingOptionsBuilder.build()).thenReturn(recordingOptions);
        Mockito.when(
                        recordingTargetHelper.startRecording(
                                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenThrow(IllegalArgumentException.class);

        Mockito.when(ctx.pathParam("targetId")).thenReturn("fooHost:9091");
        MultiMap attrs = MultiMap.caseInsensitiveMultiMap();
        Mockito.when(ctx.request()).thenReturn(req);
        Mockito.when(req.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        Mockito.when(req.formAttributes()).thenReturn(attrs);
        Mockito.when(ctx.response()).thenReturn(resp);
        Mockito.when(
                        resp.putHeader(
                                Mockito.any(CharSequence.class), Mockito.any(CharSequence.class)))
                .thenReturn(resp);
        attrs.add("recordingName", "someRecording");
        attrs.add("events", "template=Foo");

        HttpStatusException ex =
                Assertions.assertThrows(HttpStatusException.class, () -> handler.handle(ctx));
        MatcherAssert.assertThat(ex.getStatusCode(), Matchers.equalTo(400));
    }

    @Test
    void shouldHandleException() throws Exception {
        HttpStatusException ex =
                Assertions.assertThrows(HttpStatusException.class, () -> handler.handle(ctx));
        MatcherAssert.assertThat(ex.getStatusCode(), Matchers.equalTo(500));
    }

    private static IRecordingDescriptor createDescriptor(String name)
            throws QuantityConversionException {
        IQuantity zeroQuantity = Mockito.mock(IQuantity.class);
        IRecordingDescriptor descriptor = Mockito.mock(IRecordingDescriptor.class);
        Mockito.lenient().when(descriptor.getId()).thenReturn(1L);
        Mockito.lenient().when(descriptor.getName()).thenReturn(name);
        Mockito.lenient()
                .when(descriptor.getState())
                .thenReturn(IRecordingDescriptor.RecordingState.STOPPED);
        Mockito.lenient().when(descriptor.getStartTime()).thenReturn(zeroQuantity);
        Mockito.lenient().when(descriptor.getDuration()).thenReturn(zeroQuantity);
        Mockito.lenient().when(descriptor.isContinuous()).thenReturn(false);
        Mockito.lenient().when(descriptor.getToDisk()).thenReturn(false);
        Mockito.lenient().when(descriptor.getMaxSize()).thenReturn(zeroQuantity);
        Mockito.lenient().when(descriptor.getMaxAge()).thenReturn(zeroQuantity);
        return descriptor;
    }

    @ParameterizedTest
    @MethodSource("getRequestMaps")
    void shouldThrowInvalidOptionException(Map<String, String> requestValues) throws Exception {
        Mockito.when(auth.validateHttpHeader(Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        IRecordingDescriptor existingRecording = createDescriptor("someRecording");

        Mockito.when(targetConnectionManager.executeConnectedTask(Mockito.any(), Mockito.any()))
                .thenAnswer(
                        arg0 ->
                                ((TargetConnectionManager.ConnectedTask<Object>)
                                                arg0.getArgument(1))
                                        .execute(connection));
        Mockito.when(connection.getService()).thenReturn(service);
        RecordingOptionsBuilder recordingOptionsBuilder =
                Mockito.mock(RecordingOptionsBuilder.class);
        Mockito.when(recordingOptionsBuilderFactory.create(Mockito.any()))
                .thenReturn(recordingOptionsBuilder);
        Mockito.when(recordingOptionsBuilder.name(Mockito.any()))
                .thenReturn(recordingOptionsBuilder);

        Mockito.when(ctx.pathParam("targetId")).thenReturn("fooHost:9091");
        MultiMap attrs = MultiMap.caseInsensitiveMultiMap();
        Mockito.when(ctx.request()).thenReturn(req);
        Mockito.when(ctx.response()).thenReturn(resp);
        Mockito.when(
                        resp.putHeader(
                                Mockito.any(CharSequence.class), Mockito.any(CharSequence.class)))
                .thenReturn(resp);
        Mockito.when(req.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        attrs.addAll(requestValues);
        attrs.add("recordingName", "someRecording");
        attrs.add("events", "template=Foo");
        Mockito.when(req.formAttributes()).thenReturn(attrs);

        HttpStatusException ex =
                Assertions.assertThrows(HttpStatusException.class, () -> handler.handle(ctx));
        MatcherAssert.assertThat(ex.getStatusCode(), Matchers.equalTo(400));
    }

    private static Stream<Map<String, String>> getRequestMaps() {
        return Stream.of(
                Map.of("duration", ""),
                Map.of("duration", "t"),
                Map.of("duration", "90s"),
                Map.of("toDisk", ""),
                Map.of("toDisk", "5"),
                Map.of("toDisk", "T"),
                Map.of("toDisk", "false1"),
                Map.of("maxAge", ""),
                Map.of("maxAge", "true"),
                Map.of("maxAge", "1e3"),
                Map.of("maxAge", "5s"),
                Map.of("maxSize", ""),
                Map.of("maxSize", "0.5"),
                Map.of("maxSize", "s1"));
    }
}
