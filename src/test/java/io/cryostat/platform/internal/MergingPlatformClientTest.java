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

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Set;

import javax.management.remote.JMXServiceURL;

import io.cryostat.messaging.notifications.NotificationFactory;
import io.cryostat.platform.PlatformClient;
import io.cryostat.platform.ServiceRef;
import io.cryostat.platform.discovery.BaseNodeType;
import io.cryostat.platform.discovery.EnvironmentNode;
import io.cryostat.platform.discovery.TargetNode;
import io.cryostat.util.URIUtil;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MergingPlatformClientTest {

    @Mock NotificationFactory notificationFactory;
    @Mock PlatformClient clientA;
    @Mock PlatformClient clientB;
    MergingPlatformClient mergingClient;

    @BeforeEach
    void setup() {
        this.mergingClient = new MergingPlatformClient(notificationFactory, clientA, clientB);
    }

    @Test
    void testStart() throws IOException {
        Mockito.verify(clientA).addTargetDiscoveryListener(mergingClient);
        Mockito.verify(clientB).addTargetDiscoveryListener(mergingClient);

        mergingClient.start();

        Mockito.verify(clientA).start();
        Mockito.verify(clientB).start();
    }

    @Test
    void testMergedDiscoverableServices() throws MalformedURLException, URISyntaxException {
        ServiceRef serviceA =
                new ServiceRef(
                        URIUtil.convert(
                                new JMXServiceURL(
                                        "service:jmx:rmi:///jndi/rmi://cryostat:9098/jmxrmi")),
                        "ServiceA");
        ServiceRef serviceB =
                new ServiceRef(
                        URIUtil.convert(
                                new JMXServiceURL(
                                        "service:jmx:rmi:///jndi/rmi://cryostat:9099/jmxrmi")),
                        "ServiceB");

        Mockito.when(clientA.listDiscoverableServices()).thenReturn(List.of(serviceA));
        Mockito.when(clientB.listDiscoverableServices()).thenReturn(List.of(serviceB));

        MatcherAssert.assertThat(
                mergingClient.listDiscoverableServices(),
                Matchers.equalTo(List.of(serviceA, serviceB)));

        Mockito.verify(clientA).listDiscoverableServices();
        Mockito.verify(clientB).listDiscoverableServices();
    }

    @Test
    void testMergedDiscoveryTrees() throws MalformedURLException, URISyntaxException {
        ServiceRef serviceA =
                new ServiceRef(
                        URIUtil.convert(
                                new JMXServiceURL(
                                        "service:jmx:rmi:///jndi/rmi://cryostat:9098/jmxrmi")),
                        "ServiceA");
        TargetNode nodeA = new TargetNode(DefaultPlatformClient.NODE_TYPE, serviceA);
        EnvironmentNode envA = new EnvironmentNode("EnvA", BaseNodeType.REALM);
        envA.addChildNode(nodeA);
        ServiceRef serviceB =
                new ServiceRef(
                        URIUtil.convert(
                                new JMXServiceURL(
                                        "service:jmx:rmi:///jndi/rmi://cryostat:9099/jmxrmi")),
                        "ServiceB");
        TargetNode nodeB = new TargetNode(DefaultPlatformClient.NODE_TYPE, serviceB);
        EnvironmentNode envB = new EnvironmentNode("EnvB", BaseNodeType.REALM);
        envB.addChildNode(nodeB);

        Mockito.when(clientA.getDiscoveryTree()).thenReturn(envA);
        Mockito.when(clientB.getDiscoveryTree()).thenReturn(envB);

        EnvironmentNode mergedNode = mergingClient.getDiscoveryTree();

        Mockito.verify(clientA).getDiscoveryTree();
        Mockito.verify(clientB).getDiscoveryTree();

        MatcherAssert.assertThat(mergedNode.getName(), Matchers.equalTo("Universe"));
        MatcherAssert.assertThat(mergedNode.getNodeType(), Matchers.equalTo(BaseNodeType.UNIVERSE));
        MatcherAssert.assertThat(mergedNode.getLabels().size(), Matchers.equalTo(0));
        MatcherAssert.assertThat(mergedNode.getChildren(), Matchers.hasSize(2));
        MatcherAssert.assertThat(mergedNode.getChildren(), Matchers.equalTo(Set.of(envA, envB)));
    }
}
