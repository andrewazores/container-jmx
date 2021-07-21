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

import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.rmi.ConnectIOException;
import java.util.Base64;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openjdk.jmc.rjmx.ConnectionException;

import io.cryostat.core.net.Credentials;
import io.cryostat.net.AuthManager;
import io.cryostat.net.ConnectionDescriptor;
import io.cryostat.net.web.http.HttpMimeType;
import io.cryostat.net.web.http.RequestHandler;
import io.cryostat.net.web.http.api.ApiMeta;
import io.cryostat.net.web.http.api.ApiResponse;
import io.cryostat.net.web.http.api.ApiResultData;

import com.google.gson.Gson;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.apache.commons.lang3.exception.ExceptionUtils;

abstract class AbstractV2RequestHandler<T> implements RequestHandler {

    abstract boolean requiresAuthentication();

    static final Pattern AUTH_HEADER_PATTERN =
            Pattern.compile("(?<type>[\\w]+)[\\s]+(?<credentials>[\\S]+)");
    static final String JMX_AUTHENTICATE_HEADER = "X-JMX-Authenticate";
    static final String JMX_AUTHORIZATION_HEADER = "X-JMX-Authorization";

    protected final AuthManager auth;
    protected final Gson gson;

    protected AbstractV2RequestHandler(AuthManager auth, Gson gson) {
        this.auth = auth;
        this.gson = gson;
    }

    abstract IntermediateResponse<T> handle(RequestParameters requestParams) throws Exception;

    abstract HttpMimeType mimeType();

    @Override
    public final void handle(RoutingContext ctx) {
        RequestParameters requestParams = RequestParameters.from(ctx);
        try {
            if (requiresAuthentication()
                    && !validateRequestAuthorization(
                                    requestParams.getHeaders().get(HttpHeaders.AUTHORIZATION))
                            .get()) {
                throw new ApiException(401, "HTTP Authorization Failure");
            }
            writeResponse(ctx, handle(requestParams));
        } catch (ApiException e) {
            throw e;
        } catch (ConnectionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SecurityException) {
                ctx.response().putHeader(JMX_AUTHENTICATE_HEADER, "Basic");
                // FIXME should be 401, needs web-client to be adapted for V2 format
                throw new ApiException(
                        427, "Authentication Failure", "JMX Authentication Failure", e);
            }
            Throwable rootCause = ExceptionUtils.getRootCause(e);
            if (rootCause instanceof ConnectIOException) {
                throw new ApiException(502, "Connection Failure", "Target SSL Untrusted", e);
            } else if (rootCause instanceof UnknownHostException) {
                throw new ApiException(404, "Connection Failure", "Target Not Found", e);
            }
            throw new ApiException(500, e.getMessage(), e);
        } catch (Exception e) {
            throw new ApiException(500, e.getMessage(), e);
        }
    }

    protected Future<Boolean> validateRequestAuthorization(String authHeader) throws Exception {
        return auth.validateHttpHeader(() -> authHeader);
    }

    protected ConnectionDescriptor getConnectionDescriptorFromParams(RequestParameters params) {
        String targetId = params.getPathParams().get("targetId");
        Credentials credentials = null;
        if (params.getHeaders().contains(JMX_AUTHORIZATION_HEADER)) {
            String proxyAuth = params.getHeaders().get(JMX_AUTHORIZATION_HEADER);
            Matcher m = AUTH_HEADER_PATTERN.matcher(proxyAuth);
            if (!m.find()) {
                params.getHeaders().set(JMX_AUTHENTICATE_HEADER, "Basic");
                throw new ApiException(427, "Invalid " + JMX_AUTHORIZATION_HEADER + " format");
            } else {
                String t = m.group("type");
                if (!"basic".equals(t.toLowerCase())) {
                    params.getHeaders().set(JMX_AUTHENTICATE_HEADER, "Basic");
                    throw new ApiException(
                            427, "Unacceptable " + JMX_AUTHORIZATION_HEADER + " type");
                } else {
                    String c;
                    try {
                        c =
                                new String(
                                        Base64.getUrlDecoder().decode(m.group("credentials")),
                                        StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException iae) {
                        params.getHeaders().set(JMX_AUTHENTICATE_HEADER, "Basic");
                        throw new ApiException(
                                427,
                                JMX_AUTHORIZATION_HEADER
                                        + " credentials do not appear to be Base64-encoded",
                                iae);
                    }
                    String[] parts = c.split(":");
                    if (parts.length != 2) {
                        params.getHeaders().set(JMX_AUTHENTICATE_HEADER, "Basic");
                        throw new ApiException(
                                427,
                                "Unrecognized " + JMX_AUTHORIZATION_HEADER + " credential format");
                    }
                    credentials = new Credentials(parts[0], parts[1]);
                }
            }
        }
        return new ConnectionDescriptor(targetId, credentials);
    }

    protected void writeResponse(RoutingContext ctx, IntermediateResponse<T> intermediateResponse) {
        HttpServerResponse response = ctx.response();
        response.setStatusCode(intermediateResponse.getStatusCode());
        if (intermediateResponse.getStatusMessage() != null) {
            response.setStatusMessage(intermediateResponse.getStatusMessage());
        }
        intermediateResponse.getHeaders().forEach(response::putHeader);
        response.putHeader(HttpHeaders.CONTENT_TYPE, mimeType().mime());

        ApiMeta meta = new ApiMeta(mimeType(), response.getStatusMessage());
        ApiResultData<T> data = new ApiResultData<>(intermediateResponse.getBody());
        ApiResponse<ApiResultData<T>> body = new ApiResponse<>(meta, data);

        response.end(gson.toJson(body));
    }
}
