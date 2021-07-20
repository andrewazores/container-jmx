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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Named;

import org.openjdk.jmc.flightrecorder.CouldNotLoadRecordingException;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;

import io.cryostat.MainModule;
import io.cryostat.core.log.Logger;
import io.cryostat.core.sys.FileSystem;
import io.cryostat.messaging.notifications.NotificationFactory;
import io.cryostat.net.AuthManager;
import io.cryostat.net.ConnectionDescriptor;
import io.cryostat.net.HttpServer;
import io.cryostat.net.web.http.AbstractAuthenticatedRequestHandler;
import io.cryostat.net.web.http.HttpMimeType;
import io.cryostat.net.web.http.api.ApiVersion;

import com.google.gson.Gson;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.impl.HttpStatusException;
import org.apache.commons.codec.binary.Base32;

class RecordingsPostHandler extends AbstractAuthenticatedRequestHandler {

    private static final Pattern RECORDING_FILENAME_PATTERN =
            Pattern.compile("([A-Za-z\\d-]*)_([A-Za-z\\d-_]*)_([\\d]*T[\\d]*Z)(\\.[\\d]+)?");

    static final String PATH = "recordings";

    private final Vertx vertx;
    private final FileSystem fs;
    private final Path savedRecordingsPath;
    private final Gson gson;
    private final Logger logger;
    private final NotificationFactory notificationFactory;
    private static final String NOTIFICATION_CATEGORY = "RecordingSaved";

    @Inject
    RecordingsPostHandler(
            AuthManager auth,
            HttpServer httpServer,
            FileSystem fs,
            @Named(MainModule.RECORDINGS_PATH) Path savedRecordingsPath,
            Gson gson,
            Logger logger,
            NotificationFactory notificationFactory) {
        super(auth);
        this.vertx = httpServer.getVertx();
        this.fs = fs;
        this.savedRecordingsPath = savedRecordingsPath;
        this.gson = gson;
        this.logger = logger;
        this.notificationFactory = notificationFactory;
    }

    @Override
    public ApiVersion apiVersion() {
        return ApiVersion.V1;
    }

    @Override
    public int getPriority() {
        return DEFAULT_PRIORITY + 10;
    }

    @Override
    public HttpMethod httpMethod() {
        return HttpMethod.POST;
    }

    @Override
    public String path() {
        return basePath() + PATH;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public boolean isOrdered() {
        return true;
    }

    @Override
    public void handleAuthenticated(RoutingContext ctx) throws Exception {
        if (!fs.isDirectory(savedRecordingsPath)) {
            throw new HttpStatusException(503, "Recording saving not available");
        }

        FileUpload upload = null;
        for (FileUpload fu : ctx.fileUploads()) {
            // ignore unrecognized form fields
            if ("recording".equals(fu.name())) {
                upload = fu;
                break;
            }
        }

        if (upload == null) {
            throw new HttpStatusException(400, "No recording submission");
        }

        String fileName = upload.fileName();
        if (fileName == null || fileName.isEmpty()) {
            throw new HttpStatusException(400, "Recording name must not be empty");
        }

        if (fileName.endsWith(".jfr")) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }

        Matcher m = RECORDING_FILENAME_PATTERN.matcher(fileName);
        if (!m.matches()) {
            throw new HttpStatusException(400, "Incorrect recording file name pattern");
        }

        String targetName = m.group(1);
        String recordingName = m.group(2);
        String timestamp = m.group(3);
        int count =
                m.group(4) == null || m.group(4).isEmpty()
                        ? 0
                        : Integer.parseInt(m.group(4).substring(1));

        ConnectionDescriptor connectionDescriptor = getConnectionDescriptorFromContext(ctx);
        Base32 base32 = new Base32();
        final String encodedServiceUri =
                base32.encodeAsString(connectionDescriptor.getTargetId().getBytes());
        final String basename = String.format("%s_%s_%s", targetName, recordingName, timestamp);
        final String uploadedFileName = upload.uploadedFileName();
        validateRecording(
                upload.uploadedFileName(),
                (res) ->
                        saveRecording(
                                encodedServiceUri,
                                basename,
                                uploadedFileName,
                                count,
                                (res2) -> {
                                    if (res2.failed()) {
                                        ctx.fail(res2.cause());
                                        return;
                                    }

                                    ctx.response()
                                            .putHeader(
                                                    HttpHeaders.CONTENT_TYPE,
                                                    HttpMimeType.JSON.mime())
                                            .end(gson.toJson(Map.of("name", res2.result())));

                                    logger.info("Recording saved as {}", res2.result());

                                    notificationFactory
                                            .createBuilder()
                                            .metaCategory(NOTIFICATION_CATEGORY)
                                            .metaType(HttpMimeType.JSON)
                                            .message(Map.of("recording", res2.result()))
                                            .build()
                                            .send();
                                }));
    }

    private void validateRecording(String recordingFile, Handler<AsyncResult<Void>> handler) {
        vertx.executeBlocking(
                event -> {
                    try {
                        JfrLoaderToolkit.loadEvents(
                                new File(recordingFile)); // try loading events to see if
                        // it's a valid file
                        event.complete();
                    } catch (CouldNotLoadRecordingException | IOException e) {
                        event.fail(e);
                    }
                },
                res -> {
                    if (res.failed()) {
                        Throwable t;
                        if (res.cause() instanceof CouldNotLoadRecordingException) {
                            t =
                                    new HttpStatusException(
                                            400, "Not a valid JFR recording file", res.cause());
                        } else {
                            t = res.cause();
                        }

                        handler.handle(makeFailedAsyncResult(t));
                        return;
                    }

                    handler.handle(makeAsyncResult(null));
                });
    }

    private void saveRecording(
            String encodedServiceUri,
            String basename,
            String tmpFile,
            int counter,
            Handler<AsyncResult<String>> handler) {
        // TODO byte-sized rename limit is arbitrary. Probably plenty since recordings
        // are also differentiated by second-resolution timestamp
        if (counter >= Byte.MAX_VALUE) {
            handler.handle(
                    makeFailedAsyncResult(
                            new IOException(
                                    "Recording could not be saved. File already exists and rename attempts were exhausted.")));
            return;
        }

        String filename = counter > 1 ? basename + "." + counter + ".jfr" : basename + ".jfr";
        Path specificRecordingsPath = savedRecordingsPath.resolve(encodedServiceUri);

        vertx.fileSystem()
                .exists(
                        specificRecordingsPath.resolve(filename).toString(),
                        (res) -> {
                            if (res.failed()) {
                                handler.handle(makeFailedAsyncResult(res.cause()));
                                return;
                            }

                            if (res.result()) {
                                saveRecording(
                                        encodedServiceUri, basename, tmpFile, counter + 1, handler);
                                return;
                            }

                            // verified no name clash at this time
                            vertx.fileSystem()
                                    .move(
                                            tmpFile,
                                            specificRecordingsPath.resolve(filename).toString(),
                                            (res2) -> {
                                                if (res2.failed()) {
                                                    handler.handle(
                                                            makeFailedAsyncResult(res2.cause()));
                                                    return;
                                                }

                                                handler.handle(makeAsyncResult(filename));
                                            });
                        });
    }

    private <T> AsyncResult<T> makeAsyncResult(T result) {
        return new AsyncResult<>() {
            @Override
            public T result() {
                return result;
            }

            @Override
            public Throwable cause() {
                return null;
            }

            @Override
            public boolean succeeded() {
                return true;
            }

            @Override
            public boolean failed() {
                return false;
            }
        };
    }

    private <T> AsyncResult<T> makeFailedAsyncResult(Throwable cause) {
        return new AsyncResult<>() {
            @Override
            public T result() {
                return null;
            }

            @Override
            public Throwable cause() {
                return cause;
            }

            @Override
            public boolean succeeded() {
                return false;
            }

            @Override
            public boolean failed() {
                return true;
            }
        };
    }
}
