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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.MalformedURLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.management.remote.JMXServiceURL;

import io.cryostat.core.log.Logger;
import io.cryostat.core.net.discovery.DiscoveredJvmDescriptor;
import io.cryostat.core.net.discovery.JvmDiscoveryClient;
import io.cryostat.core.net.discovery.JvmDiscoveryClient.EventKind;
import io.cryostat.core.net.discovery.JvmDiscoveryClient.JvmDiscoveryEvent;
import io.cryostat.platform.ServiceRef;
import io.cryostat.platform.ServiceRef.AnnotationKey;
import io.cryostat.platform.TargetDiscoveryEvent;
import io.cryostat.platform.discovery.AbstractNode;
import io.cryostat.platform.discovery.BaseNodeType;
import io.cryostat.platform.discovery.EnvironmentNode;
import io.cryostat.util.URIUtil;

import org.hamcrest.Matcher;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultPlatformClientTest {

    @Mock Logger logger;
    @Mock JvmDiscoveryClient discoveryClient;
    DefaultPlatformClient client;

    @BeforeEach
    void setup() {
        this.client = new DefaultPlatformClient(logger, discoveryClient);
    }

    @Test
    void testShouldAddListenerAndStartDiscovery() throws Exception {
        verifyNoInteractions(discoveryClient);

        client.start();

        InOrder inOrder = Mockito.inOrder(discoveryClient);
        inOrder.verify(discoveryClient).addListener(client);
        inOrder.verify(discoveryClient).start();
    }

    @Test
    void testDiscoverableServiceMapping() throws Exception {
        DiscoveredJvmDescriptor desc1 = mock(DiscoveredJvmDescriptor.class);
        JMXServiceURL url1 =
                new JMXServiceURL("service:jmx:rmi:///jndi/rmi://cryostat:9091/jmxrmi");
        when(desc1.getMainClass()).thenReturn("com.example.Main");
        when(desc1.getJmxServiceUrl()).thenReturn(url1);

        DiscoveredJvmDescriptor desc2 = mock(DiscoveredJvmDescriptor.class);
        when(desc2.getJmxServiceUrl()).thenThrow(MalformedURLException.class);

        DiscoveredJvmDescriptor desc3 = mock(DiscoveredJvmDescriptor.class);
        JMXServiceURL url2 =
                new JMXServiceURL("service:jmx:rmi:///jndi/rmi://cryostat:9092/jmxrmi");
        when(desc3.getMainClass()).thenReturn("io.cryostat.Cryostat");
        when(desc3.getJmxServiceUrl()).thenReturn(url2);

        when(discoveryClient.getDiscoveredJvmDescriptors())
                .thenReturn(List.of(desc1, desc2, desc3));

        List<ServiceRef> results = client.listDiscoverableServices();

        ServiceRef exp1 =
                new ServiceRef(URIUtil.convert(desc1.getJmxServiceUrl()), desc1.getMainClass());
        exp1.setCryostatAnnotations(
                Map.of(
                        AnnotationKey.JAVA_MAIN, desc1.getMainClass(),
                        AnnotationKey.HOST, "cryostat",
                        AnnotationKey.PORT, "9091"));
        ServiceRef exp2 =
                new ServiceRef(URIUtil.convert(desc3.getJmxServiceUrl()), desc3.getMainClass());
        exp2.setCryostatAnnotations(
                Map.of(
                        AnnotationKey.JAVA_MAIN, desc3.getMainClass(),
                        AnnotationKey.HOST, "cryostat",
                        AnnotationKey.PORT, "9092"));

        assertThat(results, equalTo(List.of(exp1, exp2)));
    }

    @Test
    void testDiscoveryTree() throws Exception {
        DiscoveredJvmDescriptor desc1 = mock(DiscoveredJvmDescriptor.class);
        JMXServiceURL url1 =
                new JMXServiceURL("service:jmx:rmi:///jndi/rmi://cryostat:9091/jmxrmi");
        when(desc1.getMainClass()).thenReturn("com.example.Main");
        when(desc1.getJmxServiceUrl()).thenReturn(url1);

        DiscoveredJvmDescriptor desc2 = mock(DiscoveredJvmDescriptor.class);
        when(desc2.getJmxServiceUrl()).thenThrow(MalformedURLException.class);

        DiscoveredJvmDescriptor desc3 = mock(DiscoveredJvmDescriptor.class);
        JMXServiceURL url2 =
                new JMXServiceURL("service:jmx:rmi:///jndi/rmi://cryostat:9092/jmxrmi");
        when(desc3.getMainClass()).thenReturn("io.cryostat.Cryostat");
        when(desc3.getJmxServiceUrl()).thenReturn(url2);

        when(discoveryClient.getDiscoveredJvmDescriptors())
                .thenReturn(List.of(desc1, desc2, desc3));

        EnvironmentNode realmNode = client.getDiscoveryTree();

        ServiceRef exp1 =
                new ServiceRef(URIUtil.convert(desc1.getJmxServiceUrl()), desc1.getMainClass());
        exp1.setCryostatAnnotations(
                Map.of(
                        AnnotationKey.JAVA_MAIN, desc1.getMainClass(),
                        AnnotationKey.HOST, "cryostat",
                        AnnotationKey.PORT, "9091"));
        ServiceRef exp2 =
                new ServiceRef(URIUtil.convert(desc3.getJmxServiceUrl()), desc3.getMainClass());
        exp2.setCryostatAnnotations(
                Map.of(
                        AnnotationKey.JAVA_MAIN, desc3.getMainClass(),
                        AnnotationKey.HOST, "cryostat",
                        AnnotationKey.PORT, "9092"));

        MatcherAssert.assertThat(realmNode.getName(), Matchers.equalTo("JDP"));
        MatcherAssert.assertThat(realmNode.getNodeType(), Matchers.equalTo(BaseNodeType.REALM));
        MatcherAssert.assertThat(realmNode.getLabels().size(), Matchers.equalTo(0));
        MatcherAssert.assertThat(realmNode.getChildren(), Matchers.hasSize(2));

        Matcher<AbstractNode> sr1Matcher =
                Matchers.allOf(
                        Matchers.hasProperty(
                                "name", Matchers.equalTo(exp1.getServiceUri().toString())),
                        Matchers.hasProperty(
                                "nodeType", Matchers.equalTo(DefaultPlatformClient.NODE_TYPE)),
                        Matchers.hasProperty("target", Matchers.equalTo(exp1)));
        Matcher<AbstractNode> sr2Matcher =
                Matchers.allOf(
                        Matchers.hasProperty(
                                "name", Matchers.equalTo(exp2.getServiceUri().toString())),
                        Matchers.hasProperty(
                                "nodeType", Matchers.equalTo(DefaultPlatformClient.NODE_TYPE)),
                        Matchers.hasProperty("target", Matchers.equalTo(exp2)));
        MatcherAssert.assertThat(
                realmNode.getChildren(), Matchers.hasItems(sr1Matcher, sr2Matcher));
    }

    @Test
    void testAcceptDiscoveryEvent() throws Exception {
        JMXServiceURL url = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://cryostat:9091/jmxrmi");
        String javaMain = "com.example.Main";
        DiscoveredJvmDescriptor desc = mock(DiscoveredJvmDescriptor.class);
        when(desc.getMainClass()).thenReturn(javaMain);
        when(desc.getJmxServiceUrl()).thenReturn(url);
        JvmDiscoveryEvent evt = mock(JvmDiscoveryEvent.class);
        when(evt.getEventKind()).thenReturn(EventKind.FOUND);
        when(evt.getJvmDescriptor()).thenReturn(desc);

        CompletableFuture<TargetDiscoveryEvent> future = new CompletableFuture<>();
        client.addTargetDiscoveryListener(future::complete);

        client.accept(evt);

        verifyNoInteractions(discoveryClient);

        TargetDiscoveryEvent event = future.get(1, TimeUnit.SECONDS);
        MatcherAssert.assertThat(event.getEventKind(), Matchers.equalTo(EventKind.FOUND));
        ServiceRef serviceRef = new ServiceRef(URIUtil.convert(url), javaMain);
        serviceRef.setCryostatAnnotations(
                Map.of(
                        AnnotationKey.JAVA_MAIN, "com.example.Main",
                        AnnotationKey.HOST, "cryostat",
                        AnnotationKey.PORT, "9091"));
        MatcherAssert.assertThat(event.getServiceRef(), Matchers.equalTo(serviceRef));
    }
}
