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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.management.remote.JMXServiceURL;

import io.cryostat.core.log.Logger;
import io.cryostat.core.net.JFRConnectionToolkit;
import io.cryostat.core.net.discovery.JvmDiscoveryClient.EventKind;
import io.cryostat.core.sys.Environment;
import io.cryostat.platform.ServiceRef;
import io.cryostat.platform.ServiceRef.AnnotationKey;
import io.cryostat.platform.TargetDiscoveryEvent;
import io.cryostat.platform.discovery.AbstractNode;
import io.cryostat.platform.discovery.BaseNodeType;
import io.cryostat.platform.discovery.EnvironmentNode;
import io.cryostat.platform.internal.KubeApiPlatformClient.KubernetesNodeType;
import io.cryostat.util.URIUtil;

import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointPort;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.EndpointsList;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import org.hamcrest.Matcher;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

@ExtendWith(MockitoExtension.class)
class KubeApiPlatformClientTest {

    static final String NAMESPACE = "foo-namespace";

    KubeApiPlatformClient platformClient;
    @Mock KubernetesClient k8sClient;
    @Mock JFRConnectionToolkit connectionToolkit;
    @Mock Environment env;
    @Mock Logger logger;

    @BeforeEach
    void setup() throws Exception {
        this.platformClient =
                new KubeApiPlatformClient(NAMESPACE, k8sClient, () -> connectionToolkit, logger);
    }

    @Test
    void shouldReturnEmptyListIfNoEndpointsFound() throws Exception {
        MixedOperation mockNamespaceOperation = Mockito.mock(MixedOperation.class);
        Mockito.when(k8sClient.endpoints()).thenReturn(mockNamespaceOperation);

        NonNamespaceOperation mockOperation = Mockito.mock(NonNamespaceOperation.class);
        Mockito.when(mockNamespaceOperation.inNamespace(Mockito.anyString()))
                .thenReturn(mockOperation);

        EndpointsList mockListable = Mockito.mock(EndpointsList.class);
        Mockito.when(mockOperation.list()).thenReturn(mockListable);

        List<Endpoints> mockEndpoints = Collections.emptyList();
        Mockito.when(mockListable.getItems()).thenReturn(mockEndpoints);

        platformClient.start();
        List<ServiceRef> result = platformClient.listDiscoverableServices();
        MatcherAssert.assertThat(result, Matchers.equalTo(Collections.emptyList()));
    }

    @Test
    void shouldReturnListOfMatchingEndpointRefs() throws Exception {
        PodResource podResource = Mockito.mock(PodResource.class);
        NonNamespaceOperation podsInNamespaceOperation = Mockito.mock(NonNamespaceOperation.class);
        Mockito.when(podsInNamespaceOperation.withName(Mockito.anyString()))
                .thenReturn(podResource);
        MixedOperation mockPodsOperation = Mockito.mock(MixedOperation.class);
        Mockito.when(mockPodsOperation.inNamespace(Mockito.anyString()))
                .thenReturn(podsInNamespaceOperation);
        Mockito.when(k8sClient.pods()).thenReturn(mockPodsOperation);

        MixedOperation mockNamespaceOperation = Mockito.mock(MixedOperation.class);
        Mockito.when(k8sClient.endpoints()).thenReturn(mockNamespaceOperation);

        NonNamespaceOperation mockOperation = Mockito.mock(NonNamespaceOperation.class);
        Mockito.when(mockNamespaceOperation.inNamespace(Mockito.anyString()))
                .thenReturn(mockOperation);

        EndpointsList mockListable = Mockito.mock(EndpointsList.class);
        Mockito.when(mockOperation.list()).thenReturn(mockListable);

        ObjectReference objRef1 = Mockito.mock(ObjectReference.class);
        // Mockito.when(objRef1.getName()).thenReturn("targetA");
        ObjectReference objRef2 = Mockito.mock(ObjectReference.class);
        Mockito.when(objRef2.getName()).thenReturn("targetB");
        Mockito.when(objRef2.getKind()).thenReturn("Pod");
        Mockito.when(objRef2.getNamespace()).thenReturn("myproject");
        ObjectReference objRef3 = Mockito.mock(ObjectReference.class);
        Mockito.when(objRef3.getName()).thenReturn("targetC");
        Mockito.when(objRef3.getKind()).thenReturn("Pod");
        Mockito.when(objRef3.getNamespace()).thenReturn("myproject");
        ObjectReference objRef4 = Mockito.mock(ObjectReference.class);
        Mockito.when(objRef4.getName()).thenReturn("targetD");
        Mockito.when(objRef4.getKind()).thenReturn("Pod");
        Mockito.when(objRef4.getNamespace()).thenReturn("myproject");

        EndpointAddress address1 = Mockito.mock(EndpointAddress.class);
        // Mockito.when(address1.getIp()).thenReturn("127.0.0.1");
        // Mockito.when(address1.getTargetRef()).thenReturn(objRef1);
        EndpointAddress address2 = Mockito.mock(EndpointAddress.class);
        Mockito.when(address2.getIp()).thenReturn("127.0.0.2");
        Mockito.when(address2.getTargetRef()).thenReturn(objRef2);
        EndpointAddress address3 = Mockito.mock(EndpointAddress.class);
        Mockito.when(address3.getIp()).thenReturn("127.0.0.3");
        Mockito.when(address3.getTargetRef()).thenReturn(objRef3);
        EndpointAddress address4 = Mockito.mock(EndpointAddress.class);
        Mockito.when(address4.getIp()).thenReturn("127.0.0.4");
        Mockito.when(address4.getTargetRef()).thenReturn(objRef4);

        EndpointPort port1 = Mockito.mock(EndpointPort.class);
        Mockito.when(port1.getPort()).thenReturn(80);
        Mockito.when(port1.getName()).thenReturn("tcp-80");
        EndpointPort port2 = Mockito.mock(EndpointPort.class);
        Mockito.when(port2.getPort()).thenReturn(9999);
        Mockito.when(port2.getName()).thenReturn("jfr-jmx");
        EndpointPort port3 = Mockito.mock(EndpointPort.class);
        Mockito.when(port3.getPort()).thenReturn(9091);
        Mockito.when(port3.getName()).thenReturn("tcp-9091");

        EndpointSubset subset1 = Mockito.mock(EndpointSubset.class);
        // Mockito.when(subset1.getAddresses()).thenReturn(Collections.singletonList(address1));
        Mockito.when(subset1.getPorts()).thenReturn(Collections.singletonList(port1));
        EndpointSubset subset2 = Mockito.mock(EndpointSubset.class);
        Mockito.when(subset2.getAddresses()).thenReturn(Arrays.asList(address2, address3));
        Mockito.when(subset2.getPorts()).thenReturn(Collections.singletonList(port2));
        EndpointSubset subset3 = Mockito.mock(EndpointSubset.class);
        Mockito.when(subset3.getAddresses()).thenReturn(Collections.singletonList(address4));
        Mockito.when(subset3.getPorts()).thenReturn(Collections.singletonList(port3));

        Endpoints endpoint = Mockito.mock(Endpoints.class);
        Mockito.when(endpoint.getSubsets()).thenReturn(Arrays.asList(subset1, subset2, subset3));

        Mockito.when(mockListable.getItems()).thenReturn(Collections.singletonList(endpoint));

        Mockito.when(connectionToolkit.createServiceURL(Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(
                        new Answer<>() {
                            @Override
                            public JMXServiceURL answer(InvocationOnMock args) throws Throwable {
                                String host = args.getArgument(0);
                                int port = args.getArgument(1);
                                return new JMXServiceURL(
                                        "rmi",
                                        "",
                                        0,
                                        "/jndi/rmi://" + host + ":" + port + "/jmxrmi");
                            }
                        });

        platformClient.start();
        List<ServiceRef> result = platformClient.listDiscoverableServices();
        ServiceRef serv1 =
                new ServiceRef(
                        URIUtil.convert(
                                connectionToolkit.createServiceURL(
                                        address2.getIp(), port2.getPort())),
                        address2.getTargetRef().getName());
        serv1.setCryostatAnnotations(
                Map.of(
                        AnnotationKey.HOST, address2.getIp(),
                        AnnotationKey.PORT, Integer.toString(port2.getPort()),
                        AnnotationKey.NAMESPACE, address2.getTargetRef().getNamespace(),
                        AnnotationKey.POD_NAME, address2.getTargetRef().getName()));
        ServiceRef serv2 =
                new ServiceRef(
                        URIUtil.convert(
                                connectionToolkit.createServiceURL(
                                        address3.getIp(), port2.getPort())),
                        address3.getTargetRef().getName());
        serv2.setCryostatAnnotations(
                Map.of(
                        AnnotationKey.HOST, address3.getIp(),
                        AnnotationKey.PORT, Integer.toString(port2.getPort()),
                        AnnotationKey.NAMESPACE, address3.getTargetRef().getNamespace(),
                        AnnotationKey.POD_NAME, address3.getTargetRef().getName()));
        ServiceRef serv3 =
                new ServiceRef(
                        URIUtil.convert(
                                connectionToolkit.createServiceURL(
                                        address4.getIp(), port3.getPort())),
                        address4.getTargetRef().getName());
        serv3.setCryostatAnnotations(
                Map.of(
                        AnnotationKey.HOST, address4.getIp(),
                        AnnotationKey.PORT, Integer.toString(port3.getPort()),
                        AnnotationKey.NAMESPACE, address4.getTargetRef().getNamespace(),
                        AnnotationKey.POD_NAME, address4.getTargetRef().getName()));

        MatcherAssert.assertThat(result, Matchers.equalTo(Arrays.asList(serv1, serv2, serv3)));
    }

    @Test
    void shouldReturnDiscoveryTree() throws Exception {
        MixedOperation mockNamespaceOperation = Mockito.mock(MixedOperation.class);
        Mockito.when(k8sClient.endpoints()).thenReturn(mockNamespaceOperation);

        NonNamespaceOperation mockOperation = Mockito.mock(NonNamespaceOperation.class);
        Mockito.when(mockNamespaceOperation.inNamespace(Mockito.anyString()))
                .thenReturn(mockOperation);

        EndpointsList mockListable = Mockito.mock(EndpointsList.class);
        Mockito.when(mockOperation.list()).thenReturn(mockListable);

        ObjectReference objRef1 = Mockito.mock(ObjectReference.class);
        // Mockito.when(objRef1.getName()).thenReturn("targetA");
        ObjectReference objRef2 = Mockito.mock(ObjectReference.class);
        Mockito.when(objRef2.getName()).thenReturn("targetB");
        Mockito.when(objRef2.getKind()).thenReturn("Route");
        Mockito.when(objRef2.getNamespace()).thenReturn("myproject");
        ObjectReference objRef3 = Mockito.mock(ObjectReference.class);
        Mockito.when(objRef3.getName()).thenReturn("targetC");
        Mockito.when(objRef3.getKind()).thenReturn("Route");
        Mockito.when(objRef3.getNamespace()).thenReturn("myproject");
        ObjectReference objRef4 = Mockito.mock(ObjectReference.class);
        Mockito.when(objRef4.getName()).thenReturn("targetD");
        Mockito.when(objRef4.getKind()).thenReturn("Route");
        Mockito.when(objRef4.getNamespace()).thenReturn("myproject");

        EndpointAddress address1 = Mockito.mock(EndpointAddress.class);
        // Mockito.when(address1.getIp()).thenReturn("127.0.0.1");
        // Mockito.when(address1.getTargetRef()).thenReturn(objRef1);
        EndpointAddress address2 = Mockito.mock(EndpointAddress.class);
        Mockito.when(address2.getIp()).thenReturn("127.0.0.2");
        Mockito.when(address2.getTargetRef()).thenReturn(objRef2);
        EndpointAddress address3 = Mockito.mock(EndpointAddress.class);
        Mockito.when(address3.getIp()).thenReturn("127.0.0.3");
        Mockito.when(address3.getTargetRef()).thenReturn(objRef3);
        EndpointAddress address4 = Mockito.mock(EndpointAddress.class);
        Mockito.when(address4.getIp()).thenReturn("127.0.0.4");
        Mockito.when(address4.getTargetRef()).thenReturn(objRef4);

        EndpointPort port1 = Mockito.mock(EndpointPort.class);
        Mockito.when(port1.getPort()).thenReturn(80);
        Mockito.when(port1.getName()).thenReturn("tcp-80");
        EndpointPort port2 = Mockito.mock(EndpointPort.class);
        Mockito.when(port2.getPort()).thenReturn(9999);
        Mockito.when(port2.getName()).thenReturn("jfr-jmx");
        EndpointPort port3 = Mockito.mock(EndpointPort.class);
        Mockito.when(port3.getPort()).thenReturn(9091);
        Mockito.when(port3.getName()).thenReturn("tcp-9091");

        EndpointSubset subset1 = Mockito.mock(EndpointSubset.class);
        // Mockito.when(subset1.getAddresses()).thenReturn(Collections.singletonList(address1));
        Mockito.when(subset1.getPorts()).thenReturn(Collections.singletonList(port1));
        EndpointSubset subset2 = Mockito.mock(EndpointSubset.class);
        Mockito.when(subset2.getAddresses()).thenReturn(Arrays.asList(address2, address3));
        Mockito.when(subset2.getPorts()).thenReturn(Collections.singletonList(port2));
        EndpointSubset subset3 = Mockito.mock(EndpointSubset.class);
        Mockito.when(subset3.getAddresses()).thenReturn(Collections.singletonList(address4));
        Mockito.when(subset3.getPorts()).thenReturn(Collections.singletonList(port3));

        Endpoints endpoint = Mockito.mock(Endpoints.class);
        Mockito.when(endpoint.getSubsets()).thenReturn(Arrays.asList(subset1, subset2, subset3));

        Mockito.when(mockListable.getItems()).thenReturn(Collections.singletonList(endpoint));

        Mockito.when(connectionToolkit.createServiceURL(Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(
                        new Answer<>() {
                            @Override
                            public JMXServiceURL answer(InvocationOnMock args) throws Throwable {
                                String host = args.getArgument(0);
                                int port = args.getArgument(1);
                                return new JMXServiceURL(
                                        "rmi",
                                        "",
                                        0,
                                        "/jndi/rmi://" + host + ":" + port + "/jmxrmi");
                            }
                        });

        EnvironmentNode realmNode = platformClient.getDiscoveryTree();

        ServiceRef serv1 =
                new ServiceRef(
                        URIUtil.convert(
                                connectionToolkit.createServiceURL(
                                        address2.getIp(), port2.getPort())),
                        address2.getTargetRef().getName());
        serv1.setCryostatAnnotations(
                Map.of(
                        AnnotationKey.HOST, address2.getIp(),
                        AnnotationKey.PORT, Integer.toString(port2.getPort()),
                        AnnotationKey.NAMESPACE, address2.getTargetRef().getNamespace(),
                        AnnotationKey.POD_NAME, address2.getTargetRef().getName()));
        ServiceRef serv2 =
                new ServiceRef(
                        URIUtil.convert(
                                connectionToolkit.createServiceURL(
                                        address3.getIp(), port2.getPort())),
                        address3.getTargetRef().getName());
        serv2.setCryostatAnnotations(
                Map.of(
                        AnnotationKey.HOST, address3.getIp(),
                        AnnotationKey.PORT, Integer.toString(port2.getPort()),
                        AnnotationKey.NAMESPACE, address3.getTargetRef().getNamespace(),
                        AnnotationKey.POD_NAME, address3.getTargetRef().getName()));
        ServiceRef serv3 =
                new ServiceRef(
                        URIUtil.convert(
                                connectionToolkit.createServiceURL(
                                        address4.getIp(), port3.getPort())),
                        address4.getTargetRef().getName());
        serv3.setCryostatAnnotations(
                Map.of(
                        AnnotationKey.HOST, address4.getIp(),
                        AnnotationKey.PORT, Integer.toString(port3.getPort()),
                        AnnotationKey.NAMESPACE, address4.getTargetRef().getNamespace(),
                        AnnotationKey.POD_NAME, address4.getTargetRef().getName()));

        MatcherAssert.assertThat(realmNode.getName(), Matchers.equalTo("KubernetesApi"));
        MatcherAssert.assertThat(realmNode.getNodeType(), Matchers.equalTo(BaseNodeType.REALM));
        MatcherAssert.assertThat(realmNode.getLabels().size(), Matchers.equalTo(0));
        MatcherAssert.assertThat(realmNode.getChildren(), Matchers.hasSize(1));

        AbstractNode realmChild = realmNode.getChildren().first();
        MatcherAssert.assertThat(realmChild, Matchers.instanceOf(EnvironmentNode.class));
        EnvironmentNode namespaceNode = (EnvironmentNode) realmChild;
        MatcherAssert.assertThat(namespaceNode.getName(), Matchers.equalTo(NAMESPACE));
        MatcherAssert.assertThat(
                namespaceNode.getNodeType(), Matchers.equalTo(KubernetesNodeType.NAMESPACE));
        MatcherAssert.assertThat(namespaceNode.getLabels().size(), Matchers.equalTo(0));
        MatcherAssert.assertThat(namespaceNode.getChildren(), Matchers.hasSize(3));

        // FIXME fill in intermediate nodes, ie. Pod, ReplicationController, DeploymentConfig
        Matcher<AbstractNode> sr1Matcher =
                Matchers.allOf(
                        Matchers.hasProperty(
                                "name", Matchers.equalTo(serv1.getServiceUri().toString())),
                        Matchers.hasProperty(
                                "nodeType", Matchers.equalTo(KubernetesNodeType.ENDPOINT)),
                        Matchers.hasProperty("target", Matchers.equalTo(serv1)));
        Matcher<AbstractNode> sr2Matcher =
                Matchers.allOf(
                        Matchers.hasProperty(
                                "name", Matchers.equalTo(serv2.getServiceUri().toString())),
                        Matchers.hasProperty(
                                "nodeType", Matchers.equalTo(KubernetesNodeType.ENDPOINT)),
                        Matchers.hasProperty("target", Matchers.equalTo(serv2)));
        Matcher<AbstractNode> sr3Matcher =
                Matchers.allOf(
                        Matchers.hasProperty(
                                "name", Matchers.equalTo(serv3.getServiceUri().toString())),
                        Matchers.hasProperty(
                                "nodeType", Matchers.equalTo(KubernetesNodeType.ENDPOINT)),
                        Matchers.hasProperty("target", Matchers.equalTo(serv3)));
        MatcherAssert.assertThat(
                namespaceNode.getChildren(), Matchers.hasItems(sr1Matcher, sr2Matcher, sr3Matcher));
    }

    @Test
    public void shouldSubscribeWatchWhenStarted() throws Exception {
        MixedOperation op = Mockito.mock(MixedOperation.class);
        Mockito.when(k8sClient.endpoints()).thenReturn(op);
        Mockito.when(op.inNamespace(Mockito.anyString())).thenReturn(op);

        Mockito.verifyNoInteractions(k8sClient);

        platformClient.start();

        InOrder inOrder = Mockito.inOrder(k8sClient, op);
        inOrder.verify(k8sClient).endpoints();
        inOrder.verify(op).inNamespace(NAMESPACE);
        inOrder.verify(op).watch(Mockito.any(Watcher.class));
        Mockito.verifyNoMoreInteractions(k8sClient);
        Mockito.verifyNoMoreInteractions(op);
    }

    @Test
    public void shouldNotifyOnAsyncAdded() throws Exception {
        PodResource podResource = Mockito.mock(PodResource.class);
        NonNamespaceOperation podsInNamespaceOperation = Mockito.mock(NonNamespaceOperation.class);
        Mockito.when(podsInNamespaceOperation.withName(Mockito.anyString()))
                .thenReturn(podResource);
        MixedOperation mockPodsOperation = Mockito.mock(MixedOperation.class);
        Mockito.when(mockPodsOperation.inNamespace(Mockito.anyString()))
                .thenReturn(podsInNamespaceOperation);
        Mockito.when(k8sClient.pods()).thenReturn(mockPodsOperation);

        MixedOperation op = Mockito.mock(MixedOperation.class);
        Mockito.when(k8sClient.endpoints()).thenReturn(op);
        Mockito.when(op.inNamespace(Mockito.anyString())).thenReturn(op);

        Mockito.when(connectionToolkit.createServiceURL(Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(
                        new Answer<>() {
                            @Override
                            public JMXServiceURL answer(InvocationOnMock args) throws Throwable {
                                String host = args.getArgument(0);
                                int port = args.getArgument(1);
                                return new JMXServiceURL(
                                        "rmi",
                                        "",
                                        0,
                                        "/jndi/rmi://" + host + ":" + port + "/jmxrmi");
                            }
                        });

        ObjectReference objRef = Mockito.mock(ObjectReference.class);
        Mockito.when(objRef.getName()).thenReturn("targetA");
        Mockito.when(objRef.getKind()).thenReturn("Pod");
        Mockito.when(objRef.getNamespace()).thenReturn("myproject");
        EndpointAddress address = Mockito.mock(EndpointAddress.class);
        Mockito.when(address.getIp()).thenReturn("127.0.0.1");
        Mockito.when(address.getTargetRef()).thenReturn(objRef);
        EndpointPort port = Mockito.mock(EndpointPort.class);
        Mockito.when(port.getPort()).thenReturn(9999);
        Mockito.when(port.getName()).thenReturn("jfr-jmx");
        EndpointSubset subset = Mockito.mock(EndpointSubset.class);
        Mockito.when(subset.getAddresses()).thenReturn(Arrays.asList(address));
        Mockito.when(subset.getPorts()).thenReturn(Collections.singletonList(port));

        Endpoints endpoints = Mockito.mock(Endpoints.class);
        Mockito.when(endpoints.getSubsets()).thenReturn(Arrays.asList(subset));

        CompletableFuture<TargetDiscoveryEvent> future = new CompletableFuture<>();
        platformClient.addTargetDiscoveryListener(future::complete);

        platformClient.start();

        ArgumentCaptor<Watcher> watcherCaptor = ArgumentCaptor.forClass(Watcher.class);
        Mockito.verify(op).watch(watcherCaptor.capture());
        Watcher watcher = watcherCaptor.getValue();
        MatcherAssert.assertThat(watcher, Matchers.notNullValue());

        watcher.eventReceived(Action.ADDED, endpoints);

        ServiceRef serviceRef =
                new ServiceRef(
                        URIUtil.convert(
                                connectionToolkit.createServiceURL(
                                        address.getIp(), port.getPort())),
                        address.getTargetRef().getName());
        serviceRef.setCryostatAnnotations(
                Map.of(
                        AnnotationKey.NAMESPACE, "myproject",
                        AnnotationKey.POD_NAME, "targetA",
                        AnnotationKey.PORT, "9999",
                        AnnotationKey.HOST, "127.0.0.1"));
        TargetDiscoveryEvent event = future.get(1, TimeUnit.SECONDS);
        MatcherAssert.assertThat(event.getEventKind(), Matchers.equalTo(EventKind.FOUND));
        MatcherAssert.assertThat(event.getServiceRef(), Matchers.equalTo(serviceRef));
    }

    @Test
    public void shouldNotifyOnAsyncDeleted() throws Exception {
        PodResource podResource = Mockito.mock(PodResource.class);
        NonNamespaceOperation podsInNamespaceOperation = Mockito.mock(NonNamespaceOperation.class);
        Mockito.when(podsInNamespaceOperation.withName(Mockito.anyString()))
                .thenReturn(podResource);
        MixedOperation mockPodsOperation = Mockito.mock(MixedOperation.class);
        Mockito.when(mockPodsOperation.inNamespace(Mockito.anyString()))
                .thenReturn(podsInNamespaceOperation);
        Mockito.when(k8sClient.pods()).thenReturn(mockPodsOperation);

        MixedOperation op = Mockito.mock(MixedOperation.class);
        Mockito.when(k8sClient.endpoints()).thenReturn(op);
        Mockito.when(op.inNamespace(Mockito.anyString())).thenReturn(op);

        Mockito.when(connectionToolkit.createServiceURL(Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(
                        new Answer<JMXServiceURL>() {
                            @Override
                            public JMXServiceURL answer(InvocationOnMock args) throws Throwable {
                                String host = args.getArgument(0);
                                int port = args.getArgument(1);
                                return new JMXServiceURL(
                                        "rmi",
                                        "",
                                        0,
                                        "/jndi/rmi://" + host + ":" + port + "/jmxrmi");
                            }
                        });

        ObjectReference objRef = Mockito.mock(ObjectReference.class);
        Mockito.when(objRef.getName()).thenReturn("targetA");
        Mockito.when(objRef.getKind()).thenReturn("Pod");
        Mockito.when(objRef.getNamespace()).thenReturn("myproject");
        EndpointAddress address = Mockito.mock(EndpointAddress.class);
        Mockito.when(address.getIp()).thenReturn("127.0.0.1");
        Mockito.when(address.getTargetRef()).thenReturn(objRef);
        EndpointPort port = Mockito.mock(EndpointPort.class);
        Mockito.when(port.getPort()).thenReturn(9999);
        Mockito.when(port.getName()).thenReturn("jfr-jmx");
        EndpointSubset subset = Mockito.mock(EndpointSubset.class);
        Mockito.when(subset.getAddresses()).thenReturn(Arrays.asList(address));
        Mockito.when(subset.getPorts()).thenReturn(Collections.singletonList(port));

        Endpoints endpoints = Mockito.mock(Endpoints.class);
        Mockito.when(endpoints.getSubsets()).thenReturn(Arrays.asList(subset));

        CompletableFuture<TargetDiscoveryEvent> future = new CompletableFuture<>();
        platformClient.addTargetDiscoveryListener(future::complete);

        platformClient.start();

        ArgumentCaptor<Watcher> watcherCaptor = ArgumentCaptor.forClass(Watcher.class);
        Mockito.verify(op).watch(watcherCaptor.capture());
        Watcher watcher = watcherCaptor.getValue();
        MatcherAssert.assertThat(watcher, Matchers.notNullValue());

        watcher.eventReceived(Action.DELETED, endpoints);

        ServiceRef serviceRef =
                new ServiceRef(
                        URIUtil.convert(
                                connectionToolkit.createServiceURL(
                                        address.getIp(), port.getPort())),
                        address.getTargetRef().getName());
        serviceRef.setCryostatAnnotations(
                Map.of(
                        AnnotationKey.NAMESPACE, "myproject",
                        AnnotationKey.POD_NAME, "targetA",
                        AnnotationKey.PORT, "9999",
                        AnnotationKey.HOST, "127.0.0.1"));
        TargetDiscoveryEvent event = future.get(1, TimeUnit.SECONDS);
        MatcherAssert.assertThat(event.getEventKind(), Matchers.equalTo(EventKind.LOST));
        MatcherAssert.assertThat(event.getServiceRef(), Matchers.equalTo(serviceRef));
    }

    @Test
    public void shouldNotifyOnAsyncModified() throws Exception {
        PodResource podResource = Mockito.mock(PodResource.class);
        NonNamespaceOperation podsInNamespaceOperation = Mockito.mock(NonNamespaceOperation.class);
        Mockito.when(podsInNamespaceOperation.withName(Mockito.anyString()))
                .thenReturn(podResource);
        MixedOperation mockPodsOperation = Mockito.mock(MixedOperation.class);
        Mockito.when(mockPodsOperation.inNamespace(Mockito.anyString()))
                .thenReturn(podsInNamespaceOperation);
        Mockito.when(k8sClient.pods()).thenReturn(mockPodsOperation);

        MixedOperation op = Mockito.mock(MixedOperation.class);
        Mockito.when(k8sClient.endpoints()).thenReturn(op);
        Mockito.when(op.inNamespace(Mockito.anyString())).thenReturn(op);

        Mockito.when(connectionToolkit.createServiceURL(Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(
                        new Answer<>() {
                            @Override
                            public JMXServiceURL answer(InvocationOnMock args) throws Throwable {
                                String host = args.getArgument(0);
                                int port = args.getArgument(1);
                                return new JMXServiceURL(
                                        "rmi",
                                        "",
                                        0,
                                        "/jndi/rmi://" + host + ":" + port + "/jmxrmi");
                            }
                        });

        ObjectReference objRef = Mockito.mock(ObjectReference.class);
        Mockito.when(objRef.getName()).thenReturn("targetA");
        Mockito.when(objRef.getKind()).thenReturn("Pod");
        Mockito.when(objRef.getNamespace()).thenReturn("myproject");
        EndpointAddress address = Mockito.mock(EndpointAddress.class);
        Mockito.when(address.getIp()).thenReturn("127.0.0.1");
        Mockito.when(address.getTargetRef()).thenReturn(objRef);
        EndpointPort port = Mockito.mock(EndpointPort.class);
        Mockito.when(port.getPort()).thenReturn(9999);
        Mockito.when(port.getName()).thenReturn("jfr-jmx");
        EndpointSubset subset = Mockito.mock(EndpointSubset.class);
        Mockito.when(subset.getAddresses()).thenReturn(Arrays.asList(address));
        Mockito.when(subset.getPorts()).thenReturn(Collections.singletonList(port));

        Endpoints endpoints = Mockito.mock(Endpoints.class);
        Mockito.when(endpoints.getSubsets()).thenReturn(Arrays.asList(subset));

        Consumer listener = Mockito.mock(Consumer.class);
        platformClient.addTargetDiscoveryListener(listener::accept);

        platformClient.start();

        ArgumentCaptor<Watcher> watcherCaptor = ArgumentCaptor.forClass(Watcher.class);
        Mockito.verify(op).watch(watcherCaptor.capture());
        Watcher watcher = watcherCaptor.getValue();
        MatcherAssert.assertThat(watcher, Matchers.notNullValue());

        watcher.eventReceived(Action.MODIFIED, endpoints);

        ServiceRef serviceRef =
                new ServiceRef(
                        URIUtil.convert(
                                connectionToolkit.createServiceURL(
                                        address.getIp(), port.getPort())),
                        address.getTargetRef().getName());
        serviceRef.setCryostatAnnotations(
                Map.of(
                        AnnotationKey.HOST, address.getIp(),
                        AnnotationKey.PORT, Integer.toString(port.getPort()),
                        AnnotationKey.NAMESPACE, address.getTargetRef().getNamespace(),
                        AnnotationKey.POD_NAME, address.getTargetRef().getName()));

        ArgumentCaptor<TargetDiscoveryEvent> eventCaptor =
                ArgumentCaptor.forClass(TargetDiscoveryEvent.class);
        Mockito.verify(listener, Mockito.times(2)).accept(eventCaptor.capture());

        TargetDiscoveryEvent event1 = eventCaptor.getAllValues().get(0);
        TargetDiscoveryEvent event2 = eventCaptor.getAllValues().get(1);
        MatcherAssert.assertThat(event1.getEventKind(), Matchers.equalTo(EventKind.LOST));
        MatcherAssert.assertThat(event1.getServiceRef(), Matchers.equalTo(serviceRef));
        MatcherAssert.assertThat(event2.getEventKind(), Matchers.equalTo(EventKind.FOUND));
        MatcherAssert.assertThat(event2.getServiceRef(), Matchers.equalTo(serviceRef));
    }
}
