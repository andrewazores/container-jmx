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

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import io.cryostat.MainModule;
import io.cryostat.core.sys.FileSystem;
import io.cryostat.messaging.notifications.NotificationFactory;
import io.cryostat.net.AuthManager;
import io.cryostat.net.reports.ReportService;
import io.cryostat.net.web.http.AbstractAuthenticatedRequestHandler;
import io.cryostat.net.web.http.HttpMimeType;
import io.cryostat.net.web.http.api.ApiVersion;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.impl.HttpStatusException;

public class RecordingDeleteHandler extends AbstractAuthenticatedRequestHandler {

    private final ReportService reportService;
    private final FileSystem fs;
    private final Path savedRecordingsPath;
    private final NotificationFactory notificationFactory;
    private static final String NOTIFICATION_CATEGORY = "RecordingDeleted";

    @Inject
    RecordingDeleteHandler(
            AuthManager auth,
            ReportService reportService,
            FileSystem fs,
            NotificationFactory notificationFactory,
            @Named(MainModule.RECORDINGS_PATH) Path savedRecordingsPath) {
        super(auth);
        this.reportService = reportService;
        this.fs = fs;
        this.savedRecordingsPath = savedRecordingsPath;
        this.notificationFactory = notificationFactory;
    }

    @Override
    public ApiVersion apiVersion() {
        return ApiVersion.V1;
    }

    @Override
    public HttpMethod httpMethod() {
        return HttpMethod.DELETE;
    }

    @Override
    public String path() {
        return basePath() + "recordings/:recordingName";
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public void handleAuthenticated(RoutingContext ctx) throws Exception {
        String recordingName = ctx.pathParam("recordingName");
        fs.listDirectoryChildren(savedRecordingsPath).stream()
                .filter(file -> recordingName.equals(Path.of(file).getFileName().toString()))
                .map(savedRecordingsPath::resolve)
                .findFirst()
                .ifPresentOrElse(
                        filePath -> {
                            try {
                                if (!fs.exists(filePath)) {
                                    throw new HttpStatusException(404, recordingName);
                                }
                                fs.deleteIfExists(filePath);
                                notificationFactory
                                        .createBuilder()
                                        .metaCategory(NOTIFICATION_CATEGORY)
                                        .metaType(HttpMimeType.JSON)
                                        .message(Map.of("recording", recordingName))
                                        .build()
                                        .send();
                            } catch (IOException e) {
                                throw new HttpStatusException(500, e.getMessage(), e);
                            } finally {
                                reportService.delete(recordingName);
                            }
                            ctx.response().setStatusCode(200);
                            ctx.response().end();
                        },
                        () -> {
                            throw new HttpStatusException(404, recordingName);
                        });
    }
}
