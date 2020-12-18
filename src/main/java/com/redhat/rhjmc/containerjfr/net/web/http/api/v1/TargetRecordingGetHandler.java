/*-
 * #%L
 * Container JFR
 * %%
 * Copyright (C) 2020 Red Hat, Inc.
 * %%
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
 * #L%
 */
package com.redhat.rhjmc.containerjfr.net.web.http.api.v1;

import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

import javax.inject.Inject;

import com.redhat.rhjmc.containerjfr.core.log.Logger;
import com.redhat.rhjmc.containerjfr.net.AuthManager;
import com.redhat.rhjmc.containerjfr.net.ConnectionDescriptor;
import com.redhat.rhjmc.containerjfr.net.TargetConnectionManager;
import com.redhat.rhjmc.containerjfr.net.web.http.AbstractAuthenticatedRequestHandler;
import com.redhat.rhjmc.containerjfr.net.web.http.HttpMimeType;
import com.redhat.rhjmc.containerjfr.net.web.http.api.ApiVersion;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.impl.HttpStatusException;

class TargetRecordingGetHandler extends AbstractAuthenticatedRequestHandler {
    protected static final int WRITE_BUFFER_SIZE = 64 * 1024; // 64 KB

    protected final TargetConnectionManager targetConnectionManager;
    protected final Logger logger;

    @Inject
    TargetRecordingGetHandler(
            AuthManager auth, TargetConnectionManager targetConnectionManager, Logger logger) {
        super(auth);
        this.targetConnectionManager = targetConnectionManager;
        this.logger = logger;
    }

    @Override
    public ApiVersion apiVersion() {
        return ApiVersion.V1;
    }

    @Override
    public HttpMethod httpMethod() {
        return HttpMethod.GET;
    }

    @Override
    public String path() {
        return basePath() + "targets/:targetId/recordings/:recordingName";
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public void handleAuthenticated(RoutingContext ctx) throws Exception {
        String recordingName = ctx.pathParam("recordingName");
        if (recordingName != null && recordingName.endsWith(".jfr")) {
            recordingName = recordingName.substring(0, recordingName.length() - 4);
        }
        handleRecordingDownloadRequest(ctx, recordingName);
    }

    void handleRecordingDownloadRequest(RoutingContext ctx, String recordingName) throws Exception {
        ConnectionDescriptor connectionDescriptor = getConnectionDescriptorFromContext(ctx);
        Optional<InputStream> stream =
                targetConnectionManager.executeConnectedTask(
                        connectionDescriptor,
                        conn ->
                                conn.getService().getAvailableRecordings().stream()
                                        .filter(r -> Objects.equals(recordingName, r.getName()))
                                        .map(
                                                desc -> {
                                                    try {
                                                        return conn.getService()
                                                                .openStream(desc, false);
                                                    } catch (Exception e) {
                                                        logger.error(e);
                                                        return null;
                                                    }
                                                })
                                        .filter(Objects::nonNull)
                                        .findFirst());
        if (stream.isEmpty()) {
            throw new HttpStatusException(404, String.format("%s not found", recordingName));
        }

        ctx.response().setChunked(true);
        ctx.response().putHeader(HttpHeaders.CONTENT_TYPE, HttpMimeType.OCTET_STREAM.mime());
        try (InputStream s = stream.get()) {
            byte[] buff = new byte[WRITE_BUFFER_SIZE];
            int n;
            while ((n = s.read(buff)) != -1) {
                // FIXME replace this with Vertx async IO, ie. ReadStream/WriteStream/Pump
                ctx.response().write(Buffer.buffer(n).appendBytes(buff, 0, n));
            }

            ctx.response().end();
        }
    }
}
