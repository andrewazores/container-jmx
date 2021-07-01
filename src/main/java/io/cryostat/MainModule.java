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
package io.cryostat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.management.remote.JMXServiceURL;

import io.cryostat.configuration.ConfigurationModule;
import io.cryostat.core.log.Logger;
import io.cryostat.core.sys.Environment;
import io.cryostat.core.tui.ClientWriter;
import io.cryostat.messaging.MessagingModule;
import io.cryostat.net.AbstractNode;
import io.cryostat.net.NetworkModule;
import io.cryostat.net.web.http.HttpMimeType;
import io.cryostat.platform.PlatformModule;
import io.cryostat.recordings.RecordingsModule;
import io.cryostat.rules.Rule;
import io.cryostat.rules.RulesModule;
import io.cryostat.sys.SystemModule;
import io.cryostat.templates.TemplatesModule;
import io.cryostat.util.GsonJmxServiceUrlAdapter;
import io.cryostat.util.HttpMimeTypeAdapter;
import io.cryostat.util.NodeTypeAdapter;
import io.cryostat.util.PathTypeAdapter;
import io.cryostat.util.RuleDeserializer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@Module(
        includes = {
            ConfigurationModule.class,
            PlatformModule.class,
            SystemModule.class,
            NetworkModule.class,
            MessagingModule.class,
            TemplatesModule.class,
            RulesModule.class,
            RecordingsModule.class,
        })
public abstract class MainModule {
    public static final String RECORDINGS_PATH = "RECORDINGS_PATH";
    public static final String CONF_DIR = "CONF_DIR";

    @Provides
    @Singleton
    static Logger provideLogger() {
        return Logger.INSTANCE;
    }

    @Provides
    @Singleton
    // FIXME remove this outdated ClientWriter abstraction and simply replace with an injected
    // Logger at all ClientWriter injection sites
    static ClientWriter provideClientWriter(Logger logger) {
        return new ClientWriter() {
            @Override
            public void print(String s) {
                logger.info(s);
            }

            @Override
            public void println(Exception e) {
                logger.warn(e);
            }
        };
    }

    // public since this is useful to use directly in tests
    @Provides
    @Singleton
    public static Gson provideGson(Logger logger) {
        return new GsonBuilder()
                .serializeNulls()
                .disableHtmlEscaping()
                .registerTypeAdapter(JMXServiceURL.class, new GsonJmxServiceUrlAdapter(logger))
                .registerTypeAdapter(HttpMimeType.class, new HttpMimeTypeAdapter())
                .registerTypeHierarchyAdapter(Path.class, new PathTypeAdapter())
                .registerTypeAdapter(Rule.class, new RuleDeserializer())
                .registerTypeAdapter(AbstractNode.NodeType.class, new NodeTypeAdapter())
                .create();
    }

    @SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")
    @Provides
    @Singleton
    @Named(RECORDINGS_PATH)
    static Path provideSavedRecordingsPath(Logger logger, Environment env) {
        String archivePath = env.getEnv("CRYOSTAT_ARCHIVE_PATH", "/flightrecordings");
        logger.info("Local save path for flight recordings set as {}", archivePath);
        return Paths.get(archivePath);
    }

    @Provides
    @Singleton
    @Named(CONF_DIR)
    static Path provideConfigurationPath(Environment env) {
        Path path = Paths.get(env.getEnv(CONF_DIR)).resolve("conf");
        try {
            Files.createDirectory(path);
            return path;
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }
}
