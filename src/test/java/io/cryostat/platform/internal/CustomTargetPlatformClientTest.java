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
package io.cryostat.platform.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.management.remote.JMXServiceURL;

import io.cryostat.MainModule;
import io.cryostat.core.net.discovery.JvmDiscoveryClient.EventKind;
import io.cryostat.core.sys.FileSystem;
import io.cryostat.platform.ServiceRef;
import io.cryostat.platform.TargetDiscoveryEvent;
import io.cryostat.platform.discovery.BaseNodeType;
import io.cryostat.platform.discovery.EnvironmentNode;
import io.cryostat.util.URIUtil;

import com.google.gson.Gson;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomTargetPlatformClientTest {

    CustomTargetPlatformClient client;
    @Mock Path confDir;
    @Mock Path saveFile;
    @Mock FileSystem fs;
    Gson gson = MainModule.provideGson(null);

    static final ServiceRef SERVICE_REF;

    static {
        try {
            SERVICE_REF =
                    new ServiceRef(
                            URIUtil.convert(
                                    new JMXServiceURL(
                                            "service:jmx:rmi:///jndi/rmi://cryostat:9099/jmxrmi")),
                            "TestTarget");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setup() {
        Mockito.when(confDir.resolve(Mockito.anyString())).thenReturn(saveFile);

        this.client = new CustomTargetPlatformClient(confDir, fs, gson);
    }

    @Test
    void shouldReadSaveFileIfPresent() throws IOException {
        Mockito.verifyNoInteractions(fs);
        Mockito.when(fs.isRegularFile(saveFile)).thenReturn(true);
        Mockito.when(fs.isReadable(saveFile)).thenReturn(true);
        BufferedReader reader = new BufferedReader(new StringReader("[]"));
        Mockito.when(fs.readFile(saveFile)).thenReturn(reader);

        client.start();

        Mockito.verify(fs).readFile(saveFile);
        Mockito.verifyNoMoreInteractions(fs);
    }

    @Test
    void shouldPopulateTargetsOnStartup() throws IOException {
        Mockito.verifyNoInteractions(fs);
        Mockito.when(fs.isRegularFile(saveFile)).thenReturn(true);
        Mockito.when(fs.isReadable(saveFile)).thenReturn(true);

        BufferedReader reader =
                new BufferedReader(new StringReader(gson.toJson(List.of(SERVICE_REF))));
        Mockito.when(fs.readFile(saveFile)).thenReturn(reader);

        client.start();
        List<ServiceRef> result = client.listDiscoverableServices();
        MatcherAssert.assertThat(result, Matchers.equalTo(List.of(SERVICE_REF)));
    }

    @Test
    void shouldProduceDiscoveryTree() throws IOException {
        Mockito.verifyNoInteractions(fs);
        Mockito.when(fs.isRegularFile(saveFile)).thenReturn(true);
        Mockito.when(fs.isReadable(saveFile)).thenReturn(true);

        BufferedReader reader =
                new BufferedReader(new StringReader(gson.toJson(List.of(SERVICE_REF))));
        Mockito.when(fs.readFile(saveFile)).thenReturn(reader);

        client.start();

        EnvironmentNode realmNode = client.getDiscoveryTree();

        MatcherAssert.assertThat(realmNode.getName(), Matchers.equalTo("Custom Targets"));
        MatcherAssert.assertThat(realmNode.getNodeType(), Matchers.equalTo(BaseNodeType.REALM));
        MatcherAssert.assertThat(realmNode.getLabels().size(), Matchers.equalTo(0));
        MatcherAssert.assertThat(realmNode.getChildren(), Matchers.hasSize(1));

        MatcherAssert.assertThat(
                realmNode.getChildren(),
                Matchers.hasItem(
                        Matchers.allOf(
                                Matchers.hasProperty(
                                        "name",
                                        Matchers.equalTo(SERVICE_REF.getServiceUri().toString())),
                                Matchers.hasProperty(
                                        "nodeType",
                                        Matchers.equalTo(CustomTargetPlatformClient.NODE_TYPE)),
                                Matchers.hasProperty("target", Matchers.equalTo(SERVICE_REF)))));
    }

    @Test
    void shouldSkipReadSaveFileIfNotPresent() throws IOException {
        Mockito.verifyNoInteractions(fs);

        client.start();

        Mockito.verify(fs).isRegularFile(saveFile);
        Mockito.verifyNoMoreInteractions(fs);
    }

    @Test
    void testAddNewTarget() throws Exception {
        CompletableFuture<TargetDiscoveryEvent> future = new CompletableFuture<>();
        client.addTargetDiscoveryListener(future::complete);

        Assertions.assertTrue(client.addTarget(SERVICE_REF));
        TargetDiscoveryEvent event = future.get(1, TimeUnit.SECONDS);

        Mockito.verify(fs)
                .writeString(
                        saveFile,
                        gson.toJson(List.of(SERVICE_REF)),
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.CREATE);

        MatcherAssert.assertThat(event.getEventKind(), Matchers.equalTo(EventKind.FOUND));
        MatcherAssert.assertThat(event.getServiceRef(), Matchers.equalTo(SERVICE_REF));
    }

    @Test
    void testAddDuplicateTarget() throws Exception {
        Mockito.when(fs.isRegularFile(saveFile)).thenReturn(true);
        Mockito.when(fs.isReadable(saveFile)).thenReturn(true);

        CompletableFuture<TargetDiscoveryEvent> future = Mockito.spy(new CompletableFuture<>());
        client.addTargetDiscoveryListener(future::complete);

        BufferedReader reader =
                new BufferedReader(new StringReader(gson.toJson(List.of(SERVICE_REF))));
        Mockito.when(fs.readFile(saveFile)).thenReturn(reader);

        client.start();
        Assertions.assertFalse(client.addTarget(SERVICE_REF));

        Mockito.verify(fs, Mockito.never())
                .writeString(
                        Mockito.any(), Mockito.anyString(), ArgumentMatchers.<OpenOption>any());
        Mockito.verify(future, Mockito.never()).complete(Mockito.any());
    }

    @Test
    void testRemoveTarget() throws Exception {
        Mockito.when(fs.isRegularFile(saveFile)).thenReturn(true);
        Mockito.when(fs.isReadable(saveFile)).thenReturn(true);

        BufferedReader reader =
                new BufferedReader(new StringReader(gson.toJson(List.of(SERVICE_REF))));
        Mockito.when(fs.readFile(saveFile)).thenReturn(reader);

        CompletableFuture<TargetDiscoveryEvent> future = new CompletableFuture<>();
        client.addTargetDiscoveryListener(future::complete);

        client.start();
        Assertions.assertTrue(client.removeTarget(SERVICE_REF));
        TargetDiscoveryEvent event = future.get(1, TimeUnit.SECONDS);

        Mockito.verify(fs)
                .writeString(
                        saveFile,
                        gson.toJson(List.of()),
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.CREATE);
        MatcherAssert.assertThat(event.getEventKind(), Matchers.equalTo(EventKind.LOST));
        MatcherAssert.assertThat(event.getServiceRef(), Matchers.equalTo(SERVICE_REF));
    }

    @Test
    void testRemoveNonexistentTarget() throws IOException {
        CompletableFuture<TargetDiscoveryEvent> future = Mockito.spy(new CompletableFuture<>());
        client.addTargetDiscoveryListener(future::complete);

        Assertions.assertFalse(client.removeTarget(SERVICE_REF));

        Mockito.verify(fs, Mockito.never())
                .writeString(
                        Mockito.any(), Mockito.anyString(), ArgumentMatchers.<OpenOption>any());
        Mockito.verify(future, Mockito.never()).complete(Mockito.any());
    }

    @Test
    void testRemoveTargetByUrl() throws Exception {
        Mockito.when(fs.isRegularFile(saveFile)).thenReturn(true);
        Mockito.when(fs.isReadable(saveFile)).thenReturn(true);

        BufferedReader reader =
                new BufferedReader(new StringReader(gson.toJson(List.of(SERVICE_REF))));
        Mockito.when(fs.readFile(saveFile)).thenReturn(reader);

        CompletableFuture<TargetDiscoveryEvent> future = new CompletableFuture<>();
        client.addTargetDiscoveryListener(future::complete);

        client.start();
        Assertions.assertTrue(client.removeTarget(SERVICE_REF.getServiceUri()));
        TargetDiscoveryEvent event = future.get(1, TimeUnit.SECONDS);

        Mockito.verify(fs)
                .writeString(
                        saveFile,
                        gson.toJson(List.of()),
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.CREATE);
        MatcherAssert.assertThat(event.getEventKind(), Matchers.equalTo(EventKind.LOST));
        MatcherAssert.assertThat(event.getServiceRef(), Matchers.equalTo(SERVICE_REF));
    }

    @Test
    void testRemoveNonexistentTargetByUrl() throws IOException {
        CompletableFuture<TargetDiscoveryEvent> future = Mockito.spy(new CompletableFuture<>());
        client.addTargetDiscoveryListener(future::complete);

        Assertions.assertFalse(client.removeTarget(SERVICE_REF.getServiceUri()));

        Mockito.verify(fs, Mockito.never())
                .writeString(
                        Mockito.any(), Mockito.anyString(), ArgumentMatchers.<OpenOption>any());
        Mockito.verify(future, Mockito.never()).complete(Mockito.any());
    }
}
