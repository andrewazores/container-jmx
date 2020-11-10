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

import static com.redhat.rhjmc.containerjfr.util.HttpStatusCodeIdentifier.isSuccessCode;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import javax.inject.Inject;

import org.apache.commons.validator.routines.UrlValidator;

import com.redhat.rhjmc.containerjfr.core.net.JFRConnection;
import com.redhat.rhjmc.containerjfr.core.sys.Environment;
import com.redhat.rhjmc.containerjfr.core.sys.FileSystem;
import com.redhat.rhjmc.containerjfr.net.AuthManager;
import com.redhat.rhjmc.containerjfr.net.TargetConnectionManager;
import com.redhat.rhjmc.containerjfr.net.reports.ReportService.RecordingNotFoundException;
import com.redhat.rhjmc.containerjfr.net.web.http.AbstractAuthenticatedRequestHandler;
import com.redhat.rhjmc.containerjfr.net.web.http.HttpMimeType;
import com.redhat.rhjmc.containerjfr.net.web.http.api.ApiVersion;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.impl.HttpStatusException;
import io.vertx.ext.web.multipart.MultipartForm;

class TargetRecordingUploadPostHandler extends AbstractAuthenticatedRequestHandler {

    private final Environment env;
    private final TargetConnectionManager targetConnectionManager;
    private final WebClient webClient;
    private final FileSystem fs;
    private static final String GRAFANA_DATASOURCE_ENV = "GRAFANA_DATASOURCE_URL";

    @Inject
    TargetRecordingUploadPostHandler(
            AuthManager auth,
            Environment env,
            TargetConnectionManager targetConnectionManager,
            WebClient webClient,
            FileSystem fs) {
        super(auth);
        this.env = env;
        this.targetConnectionManager = targetConnectionManager;
        this.webClient = webClient;
        this.fs = fs;
    }

    @Override
    public ApiVersion apiVersion() {
        return ApiVersion.V1;
    }

    @Override
    public HttpMethod httpMethod() {
        return HttpMethod.POST;
    }

    @Override
    public String path() {
        return basePath() + "targets/:targetId/recordings/:recordingName/upload";
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public void handleAuthenticated(RoutingContext ctx) throws Exception {
        try {
            URL uploadUrl = new URL(env.getEnv(GRAFANA_DATASOURCE_ENV));
            boolean isValidUploadUrl =
                    new UrlValidator(UrlValidator.ALLOW_LOCAL_URLS).isValid(uploadUrl.toString());
            if (!isValidUploadUrl) {
                throw new HttpStatusException(
                        501,
                        String.format(
                                "$%s=%s is an invalid datasource URL",
                                GRAFANA_DATASOURCE_ENV, uploadUrl.toString()));
            }
            ResponseMessage response = doPost(ctx, uploadUrl);
            if (!isSuccessCode(response.statusCode)
                    || response.statusMessage == null
                    || response.body == null) {
                throw new HttpStatusException(
                        512,
                        String.format(
                                "Invalid response from datasource server; datasource URL may be incorrect, or server may not be functioning properly: %d %s",
                                response.statusCode, response.statusMessage));
            }
            ctx.response().setStatusCode(response.statusCode);
            ctx.response().setStatusMessage(response.statusMessage);
            ctx.response().end(response.body);
        } catch (MalformedURLException e) {
            throw new HttpStatusException(501, e);
        } catch (RecordingNotFoundException e) {
            throw new HttpStatusException(404, e);
        }
    }

    // FindBugs thinks the recordingPath or its properties is null somehow
    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    private ResponseMessage doPost(RoutingContext ctx, URL uploadUrl) throws Exception {
        String targetId = ctx.pathParam("targetId");
        String recordingName = ctx.pathParam("recordingName");
        Path recordingPath =
                targetConnectionManager.executeConnectedTask(
                        getConnectionDescriptorFromContext(ctx),
                        connection ->
                                getRecordingCopyPath(connection, targetId, recordingName)
                                        .orElseThrow(
                                                () ->
                                                        new RecordingNotFoundException(
                                                                targetId, recordingName)));

        MultipartForm form =
                MultipartForm.create()
                        .binaryFileUpload(
                                "file",
                                recordingName,
                                recordingPath.toString(),
                                HttpMimeType.OCTET_STREAM.toString());

        CompletableFuture<ResponseMessage> future = new CompletableFuture<>();
        try {
            webClient
                    .postAbs(uploadUrl.toURI().resolve("/load").normalize().toString())
                    .timeout(30_000L)
                    .sendMultipartForm(
                            form,
                            uploadHandler -> {
                                if (uploadHandler.failed()) {
                                    future.completeExceptionally(uploadHandler.cause());
                                    return;
                                }
                                HttpResponse<Buffer> response = uploadHandler.result();
                                future.complete(
                                        new ResponseMessage(
                                                response.statusCode(),
                                                response.statusMessage(),
                                                response.bodyAsString()));
                            });
            return future.get();
        } finally {
            fs.deleteIfExists(recordingPath);
        }
    }

    Optional<Path> getRecordingCopyPath(
            JFRConnection connection, String targetId, String recordingName) throws Exception {
        return connection.getService().getAvailableRecordings().stream()
                .filter(recording -> recording.getName().equals(recordingName))
                .findFirst()
                .map(
                        descriptor -> {
                            try {
                                // FIXME extract createTempFile wrapper into FileSystem
                                Path tempFile = Files.createTempFile(null, null);
                                try (InputStream stream =
                                        connection.getService().openStream(descriptor, false)) {
                                    fs.copy(stream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                                }
                                return tempFile;
                            } catch (Exception e) {
                                throw new HttpStatusException(500, e);
                            }
                        });
    }

    private static class ResponseMessage {
        final int statusCode;
        final String statusMessage;
        final String body;

        ResponseMessage(int statusCode, String statusMessage, String body) {
            this.statusCode = statusCode;
            this.statusMessage = statusMessage;
            this.body = body;
        }
    }
}
