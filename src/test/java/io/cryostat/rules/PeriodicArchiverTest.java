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
package io.cryostat.rules;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import io.cryostat.configuration.CredentialsManager;
import io.cryostat.core.log.Logger;
import io.cryostat.platform.ServiceRef;
import io.cryostat.recordings.RecordingArchiveHelper;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PeriodicArchiverTest {

    PeriodicArchiver archiver;
    String jmxUrl = "service:jmx:rmi://localhost:9091/jndi/rmi://fooHost:9091/jmxrmi";
    ServiceRef serviceRef;
    @Mock CredentialsManager credentialsManager;
    Rule rule =
            new Rule.Builder()
                    .name("Test Rule")
                    .description("Automated unit test rule")
                    .targetAlias("com.example.App")
                    .eventSpecifier("template=Continuous")
                    .maxAgeSeconds(30)
                    .maxSizeBytes(1234)
                    .preservedArchives(2)
                    .archivalPeriodSeconds(67)
                    .build();
    @Mock RecordingArchiveHelper recordingArchiveHelper;
    AtomicInteger failureCounter;
    @Mock Logger logger;
    @Mock Queue<String> previousRecordings;

    @BeforeEach
    void setup() throws Exception {
        this.serviceRef = new ServiceRef(new URI(jmxUrl), "com.example.App");
        this.failureCounter = new AtomicInteger();
        this.archiver =
                new PeriodicArchiver(
                        serviceRef,
                        credentialsManager,
                        rule,
                        recordingArchiveHelper,
                        p -> {
                            failureCounter.incrementAndGet();
                            return null;
                        },
                        logger);
    }

    @Test
    void testPerformArchival() throws Exception {
        CompletableFuture<List<ArchivedRecordingInfo>> listFuture = new CompletableFuture<>();
        listFuture.complete(new ArrayList<>());
        Mockito.when(recordingArchiveHelper.getRecordings()).thenReturn(listFuture);

        CompletableFuture<String> stringFuture = new CompletableFuture<>();
        stringFuture.complete("someRecording.jfr");
        Mockito.when(recordingArchiveHelper.saveRecording(Mockito.any(), Mockito.anyString()))
                .thenReturn(stringFuture);

        archiver.run();

        Mockito.verify(credentialsManager).getCredentials(serviceRef);
        Mockito.verify(recordingArchiveHelper).saveRecording(Mockito.any(), Mockito.anyString());
    }

    @Test
    void testNotifyOnExecutionFailure() throws Exception {
        CompletableFuture<List<ArchivedRecordingInfo>> listFuture = new CompletableFuture<>();
        listFuture.complete(new ArrayList<>());
        Mockito.when(recordingArchiveHelper.getRecordings()).thenReturn(listFuture);

        Mockito.doThrow(ExecutionException.class)
                .when(recordingArchiveHelper)
                .saveRecording(Mockito.any(), Mockito.any());
        MatcherAssert.assertThat(failureCounter.intValue(), Matchers.equalTo(0));

        archiver.run();

        MatcherAssert.assertThat(failureCounter.intValue(), Matchers.equalTo(1));
    }

    @Test
    void testNotifyOnConnectionFailure() throws Exception {
        CompletableFuture<List<ArchivedRecordingInfo>> listFuture = new CompletableFuture<>();
        listFuture.complete(new ArrayList<>());
        Mockito.when(recordingArchiveHelper.getRecordings()).thenReturn(listFuture);

        Mockito.doThrow(SecurityException.class)
                .when(recordingArchiveHelper)
                .saveRecording(Mockito.any(), Mockito.any());
        MatcherAssert.assertThat(failureCounter.intValue(), Matchers.equalTo(0));

        archiver.run();

        MatcherAssert.assertThat(failureCounter.intValue(), Matchers.equalTo(1));
    }

    @Test
    void testPruneArchive() throws Exception {
        CompletableFuture<List<ArchivedRecordingInfo>> listFuture = new CompletableFuture<>();
        listFuture.complete(new ArrayList<>());
        Mockito.when(recordingArchiveHelper.getRecordings()).thenReturn(listFuture);

        CompletableFuture<String> stringFuture = new CompletableFuture<>();
        stringFuture.complete("someRecording.jfr");
        Mockito.when(recordingArchiveHelper.saveRecording(Mockito.any(), Mockito.anyString()))
                .thenReturn(stringFuture);

        CompletableFuture<Void> voidFuture = new CompletableFuture<>();
        voidFuture.complete(null);
        Mockito.when(recordingArchiveHelper.deleteRecording(Mockito.any(), Mockito.anyString()))
                 .thenReturn(voidFuture);

        // get the archiver into a state where it has reached its limit of preserved recordings
        for (int i = 0; i < rule.getPreservedArchives(); i++) {
            archiver.run();
        }
        
        archiver.run();

        Mockito.verify(credentialsManager, Mockito.times(3)).getCredentials(serviceRef);
        Mockito.verify(recordingArchiveHelper, Mockito.times(3)).saveRecording(Mockito.any(), Mockito.anyString());
        Mockito.verify(recordingArchiveHelper, Mockito.times(1)).deleteRecording(Mockito.any(), Mockito.anyString());
    }
}
