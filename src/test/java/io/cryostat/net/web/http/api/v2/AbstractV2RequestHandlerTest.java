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
package io.cryostat.net.web.http.api.v2;

import static org.mockito.Mockito.when;

import java.net.UnknownHostException;
import java.rmi.ConnectIOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.openjdk.jmc.rjmx.ConnectionException;

import io.cryostat.MainModule;
import io.cryostat.core.log.Logger;
import io.cryostat.net.AuthManager;
import io.cryostat.net.ConnectionDescriptor;
import io.cryostat.net.web.http.HttpMimeType;
import io.cryostat.net.web.http.RequestHandler;
import io.cryostat.net.web.http.api.ApiVersion;

import com.google.gson.Gson;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AbstractV2RequestHandlerTest {

    RequestHandler handler;
    @Mock RoutingContext ctx;
    MultiMap headers;
    @Mock Map<String, String> pathParams;
    @Mock HttpServerRequest req;
    @Mock HttpServerResponse resp;
    @Mock AuthManager auth;
    @Mock Logger logger;
    Gson gson = MainModule.provideGson(logger);

    @BeforeEach
    void setup() {
        Mockito.lenient().when(ctx.pathParams()).thenReturn(pathParams);
        Mockito.lenient().when(ctx.queryParams()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        Mockito.lenient().when(ctx.fileUploads()).thenReturn(Collections.emptySet());

        this.headers = MultiMap.caseInsensitiveMultiMap();

        HttpServerRequest req = Mockito.mock(HttpServerRequest.class);
        Mockito.lenient().when(req.headers()).thenReturn(headers);
        Mockito.lenient().when(ctx.request()).thenReturn(req);
        Mockito.lenient().when(ctx.response()).thenReturn(resp);

        this.handler = new AuthenticatedHandler(auth, gson);
    }

    @Test
    void shouldThrow401IfAuthFails() {
        when(auth.validateHttpHeader(Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(false));

        ApiException ex = Assertions.assertThrows(ApiException.class, () -> handler.handle(ctx));
        MatcherAssert.assertThat(ex.getStatusCode(), Matchers.equalTo(401));
    }

    @Test
    void shouldThrow500IfAuthThrows() {
        when(auth.validateHttpHeader(Mockito.any()))
                .thenReturn(CompletableFuture.failedFuture(new NullPointerException()));

        ApiException ex = Assertions.assertThrows(ApiException.class, () -> handler.handle(ctx));
        MatcherAssert.assertThat(ex.getStatusCode(), Matchers.equalTo(500));
    }

    @Nested
    class WithHandlerThrownException {

        @BeforeEach
        void setup2() {
            when(auth.validateHttpHeader(Mockito.any()))
                    .thenReturn(CompletableFuture.completedFuture(true));
        }

        @Test
        void shouldPropagateIfHandlerThrowsApiException() {
            Exception expectedException = new ApiException(200);
            handler = new ThrowingAuthenticatedHandler(auth, gson, expectedException);

            ApiException ex =
                    Assertions.assertThrows(ApiException.class, () -> handler.handle(ctx));
            MatcherAssert.assertThat(ex, Matchers.sameInstance(expectedException));
        }

        @Test
        void shouldThrow500IfConnectionFails() {
            Exception expectedException = new ConnectionException("");
            handler = new ThrowingAuthenticatedHandler(auth, gson, expectedException);

            ApiException ex =
                    Assertions.assertThrows(ApiException.class, () -> handler.handle(ctx));
            MatcherAssert.assertThat(ex.getStatusCode(), Matchers.equalTo(500));
        }

        @Test
        void shouldThrow427IfConnectionFailsDueToTargetAuth() {
            Exception cause = new SecurityException();
            Exception expectedException = new ConnectionException("");
            expectedException.initCause(cause);
            handler = new ThrowingAuthenticatedHandler(auth, gson, expectedException);

            ApiException ex =
                    Assertions.assertThrows(ApiException.class, () -> handler.handle(ctx));
            MatcherAssert.assertThat(ex.getStatusCode(), Matchers.equalTo(427));
            Mockito.verify(resp).putHeader("X-JMX-Authenticate", "Basic");
        }

        @Test
        void shouldThrow502IfConnectionFailsDueToSslTrust() {
            Exception cause = new ConnectIOException("SSL trust");
            Exception expectedException = new ConnectionException("");
            expectedException.initCause(cause);
            handler = new ThrowingAuthenticatedHandler(auth, gson, expectedException);

            ApiException ex =
                    Assertions.assertThrows(ApiException.class, () -> handler.handle(ctx));
            MatcherAssert.assertThat(ex.getStatusCode(), Matchers.equalTo(502));
            MatcherAssert.assertThat(
                    ex.getFailureReason(), Matchers.equalTo("Target SSL Untrusted"));
        }

        @Test
        void shouldThrow404IfConnectionFailsDueToInvalidTarget() {
            Exception cause = new UnknownHostException("localhostt");
            Exception expectedException = new ConnectionException("");
            expectedException.initCause(cause);
            handler = new ThrowingAuthenticatedHandler(auth, gson, expectedException);

            ApiException ex =
                    Assertions.assertThrows(ApiException.class, () -> handler.handle(ctx));
            MatcherAssert.assertThat(ex.getStatusCode(), Matchers.equalTo(404));
            MatcherAssert.assertThat(ex.getFailureReason(), Matchers.equalTo("Target Not Found"));
        }

        @Test
        void shouldThrow500IfHandlerThrowsUnexpectedly() {
            Exception expectedException = new NullPointerException();
            handler = new ThrowingAuthenticatedHandler(auth, gson, expectedException);

            ApiException ex =
                    Assertions.assertThrows(ApiException.class, () -> handler.handle(ctx));
            MatcherAssert.assertThat(ex.getStatusCode(), Matchers.equalTo(500));
        }
    }

    @Nested
    class WithTargetAuth {

        ConnectionDescriptorHandler handler;

        @BeforeEach
        void setup3() {
            handler = new ConnectionDescriptorHandler(auth, gson);
            when(auth.validateHttpHeader(Mockito.any()))
                    .thenReturn(CompletableFuture.completedFuture(true));
        }

        @Test
        void shouldUseNoCredentialsWithoutAuthorizationHeader() {
            String targetId = "fooTarget";
            Mockito.when(ctx.pathParams()).thenReturn(Map.of("targetId", targetId));

            handler.handle(ctx);
            ConnectionDescriptor desc = handler.desc;

            MatcherAssert.assertThat(desc.getTargetId(), Matchers.equalTo(targetId));
            Assertions.assertFalse(desc.getCredentials().isPresent());
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "",
                    "credentialsWithoutAuthType",
                })
        void shouldThrow427WithMalformedAuthorizationHeader(String authHeader) {
            String targetId = "fooTarget";
            Mockito.when(ctx.pathParams()).thenReturn(Map.of("targetId", targetId));
            headers.set(AbstractV2RequestHandler.JMX_AUTHORIZATION_HEADER, authHeader);

            ApiException ex =
                    Assertions.assertThrows(ApiException.class, () -> handler.handle(ctx));
            MatcherAssert.assertThat(ex.getStatusCode(), Matchers.equalTo(427));
            MatcherAssert.assertThat(
                    ex.getFailureReason(), Matchers.equalTo("Invalid X-JMX-Authorization format"));
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "Type credentials",
                    "Bearer credentials",
                })
        void shouldThrow427WithBadAuthorizationType(String authHeader) {
            String targetId = "fooTarget";
            Mockito.when(ctx.pathParams()).thenReturn(Map.of("targetId", targetId));
            headers.set(AbstractV2RequestHandler.JMX_AUTHORIZATION_HEADER, authHeader);

            ApiException ex =
                    Assertions.assertThrows(ApiException.class, () -> handler.handle(ctx));
            ex.printStackTrace();
            MatcherAssert.assertThat(ex.getStatusCode(), Matchers.equalTo(427));
            MatcherAssert.assertThat(
                    ex.getFailureReason(),
                    Matchers.equalTo("Unacceptable X-JMX-Authorization type"));
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "Basic bm9zZXBhcmF0b3I=", // credential value of "noseparator"
                    "Basic b25lOnR3bzp0aHJlZQ==", // credential value of "one:two:three"
                })
        void shouldThrow427WithBadCredentialFormat(String authHeader) {
            String targetId = "fooTarget";
            Mockito.when(ctx.pathParams()).thenReturn(Map.of("targetId", targetId));
            headers.set(AbstractV2RequestHandler.JMX_AUTHORIZATION_HEADER, authHeader);

            ApiException ex =
                    Assertions.assertThrows(ApiException.class, () -> handler.handle(ctx));
            MatcherAssert.assertThat(ex.getStatusCode(), Matchers.equalTo(427));
            MatcherAssert.assertThat(
                    ex.getFailureReason(),
                    Matchers.equalTo("Unrecognized X-JMX-Authorization credential format"));
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "Basic foo:bar",
                })
        void shouldThrow427WithUnencodedCredentials(String authHeader) {
            String targetId = "fooTarget";
            Mockito.when(ctx.pathParams()).thenReturn(Map.of("targetId", targetId));
            headers.set(AbstractV2RequestHandler.JMX_AUTHORIZATION_HEADER, authHeader);

            ApiException ex =
                    Assertions.assertThrows(ApiException.class, () -> handler.handle(ctx));
            MatcherAssert.assertThat(ex.getStatusCode(), Matchers.equalTo(427));
            MatcherAssert.assertThat(
                    ex.getFailureReason(),
                    Matchers.equalTo(
                            "X-JMX-Authorization credentials do not appear to be Base64-encoded"));
        }

        @Test
        void shouldIncludeCredentialsFromAppropriateHeader() {
            String targetId = "fooTarget";
            Mockito.when(ctx.pathParams()).thenReturn(Map.of("targetId", targetId));
            headers.set(AbstractV2RequestHandler.JMX_AUTHORIZATION_HEADER, "Basic Zm9vOmJhcg==");

            Assertions.assertDoesNotThrow(() -> handler.handle(ctx));
            ConnectionDescriptor desc = handler.desc;

            MatcherAssert.assertThat(desc.getTargetId(), Matchers.equalTo(targetId));
            Assertions.assertTrue(desc.getCredentials().isPresent());
        }
    }

    static class AuthenticatedHandler extends AbstractV2RequestHandler<String> {
        AuthenticatedHandler(AuthManager auth, Gson gson) {
            super(auth, gson);
        }

        @Override
        public ApiVersion apiVersion() {
            return ApiVersion.V2;
        }

        @Override
        public boolean requiresAuthentication() {
            return true;
        }

        @Override
        public String path() {
            return null;
        }

        @Override
        public HttpMethod httpMethod() {
            return null;
        }

        @Override
        public HttpMimeType mimeType() {
            return HttpMimeType.PLAINTEXT;
        }

        @Override
        public IntermediateResponse<String> handle(RequestParameters params) throws Exception {
            return new IntermediateResponse<String>().body("OK");
        }
    }

    static class ThrowingAuthenticatedHandler extends AuthenticatedHandler {
        private final Exception thrown;

        ThrowingAuthenticatedHandler(AuthManager auth, Gson gson, Exception thrown) {
            super(auth, gson);
            this.thrown = thrown;
        }

        @Override
        public IntermediateResponse<String> handle(RequestParameters params) throws Exception {
            throw thrown;
        }
    }

    static class ConnectionDescriptorHandler extends AuthenticatedHandler {
        ConnectionDescriptor desc;

        ConnectionDescriptorHandler(AuthManager auth, Gson gson) {
            super(auth, gson);
        }

        @Override
        public IntermediateResponse<String> handle(RequestParameters params) throws Exception {
            desc = getConnectionDescriptorFromParams(params);
            return new IntermediateResponse<String>().body("");
        }
    }
}
