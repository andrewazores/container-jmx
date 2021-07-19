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
package io.cryostat.net.web.http;

import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.rmi.ConnectIOException;
import java.util.Base64;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.security.sasl.SaslException;

import org.openjdk.jmc.rjmx.ConnectionException;

import io.cryostat.core.net.Credentials;
import io.cryostat.net.AuthManager;
import io.cryostat.net.ConnectionDescriptor;

import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.impl.HttpStatusException;
import org.apache.commons.lang3.exception.ExceptionUtils;

public abstract class AbstractAuthenticatedRequestHandler implements RequestHandler {

    public static final Pattern AUTH_HEADER_PATTERN =
            Pattern.compile("(?<type>[\\w]+)[\\s]+(?<credentials>[\\S]+)");
    public static final String JMX_AUTHENTICATE_HEADER = "X-JMX-Authenticate";
    public static final String JMX_AUTHORIZATION_HEADER = "X-JMX-Authorization";

    protected final AuthManager auth;

    protected AbstractAuthenticatedRequestHandler(AuthManager auth) {
        this.auth = auth;
    }

    public abstract void handleAuthenticated(RoutingContext ctx) throws Exception;

    @Override
    public void handle(RoutingContext ctx) {
        try {
            if (!validateRequestAuthorization(ctx.request()).get()) {
                throw new HttpStatusException(401);
            }
            // set Content-Type: text/plain by default. Handler implementations may replace this.
            ctx.response().putHeader(HttpHeaders.CONTENT_TYPE, HttpMimeType.PLAINTEXT.mime());
            handleAuthenticated(ctx);
        } catch (HttpStatusException e) {
            throw e;
        } catch (ConnectionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SecurityException || cause instanceof SaslException) {
                ctx.response().putHeader(JMX_AUTHENTICATE_HEADER, "Basic");
                throw new HttpStatusException(427, "JMX Authentication Failure", e);
            }
            Throwable rootCause = ExceptionUtils.getRootCause(e);
            if (rootCause instanceof ConnectIOException) {
                throw new HttpStatusException(502, "Target SSL Untrusted", e);
            }
            if (rootCause instanceof UnknownHostException) {
                throw new HttpStatusException(404, "Target Not Found", e);
            }
            throw new HttpStatusException(500, e);
        } catch (Exception e) {
            throw new HttpStatusException(500, e.getMessage(), e);
        }
    }

    protected Future<Boolean> validateRequestAuthorization(HttpServerRequest req) throws Exception {
        return auth.validateHttpHeader(() -> req.getHeader(HttpHeaders.AUTHORIZATION));
    }

    protected ConnectionDescriptor getConnectionDescriptorFromContext(RoutingContext ctx) {
        String targetId = ctx.pathParam("targetId");
        // TODO inject the CredentialsManager here to check for stored credentials
        Credentials credentials = null;
        if (ctx.request().headers().contains(JMX_AUTHORIZATION_HEADER)) {
            String proxyAuth = ctx.request().getHeader(JMX_AUTHORIZATION_HEADER);
            Matcher m = AUTH_HEADER_PATTERN.matcher(proxyAuth);
            if (!m.find()) {
                ctx.response().putHeader(JMX_AUTHENTICATE_HEADER, "Basic");
                throw new HttpStatusException(
                        427, "Invalid " + JMX_AUTHORIZATION_HEADER + " format");
            }
            String t = m.group("type");
            if (!"basic".equals(t.toLowerCase())) {
                ctx.response().putHeader(JMX_AUTHENTICATE_HEADER, "Basic");
                throw new HttpStatusException(
                        427, "Unacceptable " + JMX_AUTHORIZATION_HEADER + " type");
            }
            String c;
            try {
                c =
                        new String(
                                Base64.getUrlDecoder().decode(m.group("credentials")),
                                StandardCharsets.UTF_8);
            } catch (IllegalArgumentException iae) {
                ctx.response().putHeader(JMX_AUTHENTICATE_HEADER, "Basic");
                throw new HttpStatusException(
                        427,
                        JMX_AUTHORIZATION_HEADER
                                + " credentials do not appear to be Base64-encoded",
                        iae);
            }
            String[] parts = c.split(":");
            if (parts.length != 2) {
                ctx.response().putHeader(JMX_AUTHENTICATE_HEADER, "Basic");
                throw new HttpStatusException(
                        427, "Unrecognized " + JMX_AUTHORIZATION_HEADER + " credential format");
            }
            credentials = new Credentials(parts[0], parts[1]);
        }
        return new ConnectionDescriptor(targetId, credentials);
    }
}
