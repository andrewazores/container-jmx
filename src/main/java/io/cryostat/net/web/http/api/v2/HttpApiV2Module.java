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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.function.Function;

import javax.inject.Named;
import javax.inject.Singleton;

import io.cryostat.net.security.CertificateValidator;
import io.cryostat.net.web.http.RequestHandler;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;

@Module
public abstract class HttpApiV2Module {

    @Binds
    @IntoSet
    abstract RequestHandler bindApiGetHandler(ApiGetHandler handler);

    @Binds
    @IntoSet
    abstract RequestHandler bindTargetsPostHandler(TargetsPostHandler handler);

    @Binds
    @IntoSet
    abstract RequestHandler bindTargetsPostBodyHandler(TargetsPostBodyHandler handler);

    @Binds
    @IntoSet
    abstract RequestHandler bindTargetDeleteHandler(TargetDeleteHandler handler);

    @Binds
    @IntoSet
    abstract RequestHandler bindTargetSnapshotPostHandler(TargetSnapshotPostHandler handler);

    @Binds
    @IntoSet
    abstract RequestHandler bindCertificatePostHandler(CertificatePostHandler handler);

    @Binds
    @IntoSet
    abstract RequestHandler bindTargetRecordingOptionsListGetHandler(
            TargetRecordingOptionsListGetHandler handler);

    @Binds
    @IntoSet
    abstract RequestHandler bindTargetEventsSearchGetHandler(TargetEventsSearchGetHandler handler);

    @Provides
    @Singleton
    @Named("OutputStreamFunction")
    static Function<File, FileOutputStream> provideOutputStreamFunction() throws RuntimeException {
        return (File file) -> {
            try {
                return new FileOutputStream(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
    }

    @Provides
    static CertificateValidator provideCertificateValidator() {
        return new CertificateValidator();
    }

    @Binds
    @IntoSet
    abstract RequestHandler bindCertificatePostBodyHandler(CertificatePostBodyHandler handler);

    @Binds
    @IntoSet
    abstract RequestHandler bindTargetCredentialsPostHandler(TargetCredentialsPostHandler handler);

    @Binds
    @IntoSet
    abstract RequestHandler bindTargetCredentialsPostBodyHandler(
            TargetCredentialsPostBodyHandler handler);

    @Binds
    @IntoSet
    abstract RequestHandler bindTargetCredentialsDeleteHandler(
            TargetCredentialsDeleteHandler handler);

    @Binds
    @IntoSet
    abstract RequestHandler bindRulesGetHandler(RulesGetHandler handler);

    @Binds
    @IntoSet
    abstract RequestHandler bindRuleGetHandler(RuleGetHandler handler);

    @Binds
    @IntoSet
    abstract RequestHandler bindRuleDeleteHandler(RuleDeleteHandler handler);

    @Binds
    @IntoSet
    abstract RequestHandler bindRulesPostHandler(RulesPostHandler handler);

    @Binds
    @IntoSet
    abstract RequestHandler bindRulesPostBodyHandler(RulesPostBodyHandler handler);
}
