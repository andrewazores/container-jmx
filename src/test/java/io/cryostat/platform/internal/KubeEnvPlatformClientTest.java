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

import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.management.remote.JMXServiceURL;

import io.cryostat.core.log.Logger;
import io.cryostat.core.net.JFRConnectionToolkit;
import io.cryostat.core.sys.Environment;
import io.cryostat.platform.ServiceRef;
import io.cryostat.platform.discovery.AbstractNode;
import io.cryostat.platform.discovery.BaseNodeType;
import io.cryostat.platform.discovery.EnvironmentNode;
import io.cryostat.util.URIUtil;

import org.hamcrest.Matcher;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

@ExtendWith(MockitoExtension.class)
class KubeEnvPlatformClientTest {

    KubeEnvPlatformClient client;
    @Mock JFRConnectionToolkit connectionToolkit;
    @Mock Environment env;
    @Mock Logger logger;

    @BeforeEach
    void setup() {
        client = new KubeEnvPlatformClient(() -> connectionToolkit, env, logger);
    }

    @Nested
    class DiscoverableServicesTests {

        @Test
        void shouldDiscoverNoServicesIfEnvsEmpty() {
            when(env.getEnv()).thenReturn(Collections.emptyMap());
            MatcherAssert.assertThat(client.listDiscoverableServices(), Matchers.empty());
            verifyNoMoreInteractions(env);
        }

        @Test
        void shouldDiscoverNoServicesIfEnvsNotRelevant() {
            when(env.getEnv()).thenReturn(Collections.singletonMap("SOME_OTHER_ENV", "127.0.0.1"));
            MatcherAssert.assertThat(client.listDiscoverableServices(), Matchers.empty());
            verifyNoMoreInteractions(env);
        }

        @Test
        void shouldDiscoverServicesByEnv() throws MalformedURLException, URISyntaxException {
            when(env.getEnv())
                    .thenReturn(
                            Map.of(
                                    "FOO_PORT_1234_TCP_ADDR", "127.0.0.1",
                                    "BAR_PORT_9999_TCP_ADDR", "1.2.3.4",
                                    "BAZ_PORT_9876_UDP_ADDR", "5.6.7.8"));

            Mockito.when(connectionToolkit.createServiceURL(Mockito.anyString(), Mockito.anyInt()))
                    .thenAnswer(
                            new Answer<>() {
                                @Override
                                public JMXServiceURL answer(InvocationOnMock args)
                                        throws Throwable {
                                    String host = args.getArgument(0);
                                    int port = args.getArgument(1);
                                    return new JMXServiceURL(
                                            "rmi",
                                            "",
                                            0,
                                            "/jndi/rmi://" + host + ":" + port + "/jmxrmi");
                                }
                            });

            ServiceRef serv1 =
                    new ServiceRef(
                            URIUtil.convert(connectionToolkit.createServiceURL("127.0.0.1", 1234)),
                            "foo");
            ServiceRef serv2 =
                    new ServiceRef(
                            URIUtil.convert(connectionToolkit.createServiceURL("1.2.3.4", 9999)),
                            "bar");

            List<ServiceRef> services = client.listDiscoverableServices();

            MatcherAssert.assertThat(services, Matchers.containsInAnyOrder(serv1, serv2));
            MatcherAssert.assertThat(services, Matchers.hasSize(2));
            verifyNoMoreInteractions(env);
        }

        @Test
        void shouldDiscoverServicesAsTree() throws MalformedURLException, URISyntaxException {
            when(env.getEnv())
                    .thenReturn(
                            Map.of(
                                    "FOO_PORT_1234_TCP_ADDR", "127.0.0.1",
                                    "BAR_PORT_9999_TCP_ADDR", "1.2.3.4",
                                    "BAZ_PORT_9876_UDP_ADDR", "5.6.7.8"));

            Mockito.when(connectionToolkit.createServiceURL(Mockito.anyString(), Mockito.anyInt()))
                    .thenAnswer(
                            new Answer<>() {
                                @Override
                                public JMXServiceURL answer(InvocationOnMock args)
                                        throws Throwable {
                                    String host = args.getArgument(0);
                                    int port = args.getArgument(1);
                                    return new JMXServiceURL(
                                            "rmi",
                                            "",
                                            0,
                                            "/jndi/rmi://" + host + ":" + port + "/jmxrmi");
                                }
                            });

            ServiceRef serv1 =
                    new ServiceRef(
                            URIUtil.convert(connectionToolkit.createServiceURL("127.0.0.1", 1234)),
                            "foo");
            ServiceRef serv2 =
                    new ServiceRef(
                            URIUtil.convert(connectionToolkit.createServiceURL("1.2.3.4", 9999)),
                            "bar");

            EnvironmentNode realmNode = client.getDiscoveryTree();

            MatcherAssert.assertThat(realmNode.getName(), Matchers.equalTo("KubernetesEnv"));
            MatcherAssert.assertThat(realmNode.getNodeType(), Matchers.equalTo(BaseNodeType.REALM));
            MatcherAssert.assertThat(realmNode.getLabels().size(), Matchers.equalTo(0));
            MatcherAssert.assertThat(realmNode.getChildren(), Matchers.hasSize(2));

            Matcher<AbstractNode> sr1Matcher =
                    Matchers.allOf(
                            Matchers.hasProperty(
                                    "name", Matchers.equalTo(serv1.getServiceUri().toString())),
                            Matchers.hasProperty(
                                    "nodeType", Matchers.equalTo(KubeEnvPlatformClient.NODE_TYPE)),
                            Matchers.hasProperty("target", Matchers.equalTo(serv1)));
            Matcher<AbstractNode> sr2Matcher =
                    Matchers.allOf(
                            Matchers.hasProperty(
                                    "name", Matchers.equalTo(serv2.getServiceUri().toString())),
                            Matchers.hasProperty(
                                    "nodeType", Matchers.equalTo(KubeEnvPlatformClient.NODE_TYPE)),
                            Matchers.hasProperty("target", Matchers.equalTo(serv2)));
            MatcherAssert.assertThat(
                    realmNode.getChildren(), Matchers.hasItems(sr1Matcher, sr2Matcher));
        }
    }
}
