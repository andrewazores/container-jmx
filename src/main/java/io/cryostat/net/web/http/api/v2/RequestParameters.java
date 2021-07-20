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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.vertx.core.MultiMap;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;

public class RequestParameters {

    private final Map<String, String> pathParams;
    private final MultiMap queryParams;
    private final MultiMap headers;
    private final MultiMap formAttributes;
    private final Set<FileUpload> fileUploads;
    private final String body;

    public RequestParameters(
            Map<String, String> pathParams,
            MultiMap queryParams,
            MultiMap headers,
            MultiMap formAttributes,
            Set<FileUpload> fileUploads,
            String body) {
        this.pathParams = new HashMap<>(pathParams);
        this.queryParams = MultiMap.caseInsensitiveMultiMap();
        this.queryParams.addAll(queryParams);
        this.headers = MultiMap.caseInsensitiveMultiMap();
        this.headers.addAll(headers);
        this.formAttributes = MultiMap.caseInsensitiveMultiMap();
        this.formAttributes.addAll(formAttributes);
        this.fileUploads = new HashSet<>(fileUploads);
        this.body = body;
    }

    public static RequestParameters from(RoutingContext ctx) {
        Map<String, String> pathParams = new HashMap<>();
        if (ctx != null && ctx.pathParams() != null) {
            pathParams.putAll(ctx.pathParams());
        }

        MultiMap queryParams = MultiMap.caseInsensitiveMultiMap();
        if (ctx != null && ctx.queryParams() != null) {
            queryParams.addAll(ctx.queryParams());
        }

        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        if (ctx != null && ctx.request() != null && ctx.request().headers() != null) {
            headers.addAll(ctx.request().headers());
        }

        MultiMap formAttributes = MultiMap.caseInsensitiveMultiMap();
        if (ctx != null && ctx.request() != null && ctx.request().formAttributes() != null) {
            formAttributes.addAll(ctx.request().formAttributes());
        }

        Set<FileUpload> fileUploads = new HashSet<>();
        if (ctx != null && ctx.fileUploads() != null) {
            fileUploads.addAll(ctx.fileUploads());
        }

        String body = null;
        if (ctx != null) {
            body = ctx.getBodyAsString();
        }

        return new RequestParameters(
                pathParams, queryParams, headers, formAttributes, fileUploads, body);
    }

    public Map<String, String> getPathParams() {
        return this.pathParams;
    }

    public MultiMap getQueryParams() {
        return this.queryParams;
    }

    public MultiMap getHeaders() {
        return this.headers;
    }

    public MultiMap getFormAttributes() {
        return this.formAttributes;
    }

    public Set<FileUpload> getFileUploads() {
        return this.fileUploads;
    }

    public String getBody() {
        return this.body;
    }
}
