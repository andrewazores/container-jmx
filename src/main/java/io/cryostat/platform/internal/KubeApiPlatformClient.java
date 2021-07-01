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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import io.cryostat.core.log.Logger;
import io.cryostat.core.net.JFRConnectionToolkit;
import io.cryostat.core.net.discovery.JvmDiscoveryClient.EventKind;
import io.cryostat.net.AbstractNode.NodeType;
import io.cryostat.net.EnvironmentNode;
import io.cryostat.net.TargetNode;
import io.cryostat.platform.ServiceRef;
import io.cryostat.platform.ServiceRef.AnnotationKey;
import io.cryostat.util.URIUtil;

import dagger.Lazy;
import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointPort;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;

class KubeApiPlatformClient extends AbstractPlatformClient {

    private final KubernetesClient k8sClient;
    private final Lazy<JFRConnectionToolkit> connectionToolkit;
    private final Logger logger;
    private final String namespace;

    KubeApiPlatformClient(
            String namespace,
            KubernetesClient k8sClient,
            Lazy<JFRConnectionToolkit> connectionToolkit,
            Logger logger) {
        this.namespace = namespace;
        this.k8sClient = k8sClient;
        this.connectionToolkit = connectionToolkit;
        this.logger = logger;
    }

    @Override
    public void start() throws IOException {
        k8sClient
                .endpoints()
                .inNamespace(namespace)
                .watch(
                        new Watcher<Endpoints>() {
                            @Override
                            public void eventReceived(Action action, Endpoints endpoints) {
                                switch (action) {
                                    case MODIFIED:
                                        // FIXME is this correct in all circumstances?
                                        // watch detects undeployed and redeployed as a "DELETED"
                                        // and then a "MODIFIED", so here we will treat "MODIFIED"
                                        // as a "DELETED" and then an "ADDED".
                                        // If the service is actually just modified and not
                                        // redeployed then this remove/add logic should still result
                                        // in the correct end state seen by subscribed notification
                                        // clients.
                                        List<ServiceRef> refs = getServiceRefs(endpoints);
                                        refs.forEach(
                                                serviceRef ->
                                                        notifyAsyncTargetDiscovery(
                                                                EventKind.LOST, serviceRef));
                                        refs.forEach(
                                                serviceRef ->
                                                        notifyAsyncTargetDiscovery(
                                                                EventKind.FOUND, serviceRef));
                                        break;
                                    case ADDED:
                                        getServiceRefs(endpoints)
                                                .forEach(
                                                        serviceRef ->
                                                                notifyAsyncTargetDiscovery(
                                                                        EventKind.FOUND,
                                                                        serviceRef));
                                        break;
                                    case DELETED:
                                        getServiceRefs(endpoints)
                                                .forEach(
                                                        serviceRef ->
                                                                notifyAsyncTargetDiscovery(
                                                                        EventKind.LOST,
                                                                        serviceRef));
                                        break;
                                    case ERROR:
                                    default:
                                        logger.warn(
                                                new IllegalArgumentException(action.toString()));
                                        return;
                                }
                            }

                            @Override
                            public void onClose(WatcherException watcherException) {
                                logger.warn(watcherException);
                            }
                        });
    }

    @Override
    public List<ServiceRef> listDiscoverableServices() {
        try {
            return k8sClient.endpoints().inNamespace(namespace).list().getItems().stream()
                    .flatMap(endpoints -> getServiceRefs(endpoints).stream())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.warn(e);
            return Collections.emptyList();
        }
    }

    @Override
    public EnvironmentNode getTargetEnvironment() {
        try {
            EnvironmentNode nsNode = new EnvironmentNode(namespace, NodeType.NAMESPACE);
            k8sClient
                    .endpoints()
                    .inNamespace(namespace)
                    .list()
                    .getItems()
                    .forEach(
                            endpoint -> {
                                endpoint.getSubsets().stream()
                                        .filter(this::isCompatibleSubset)
                                        .forEach(
                                                subset -> {
                                                    subset.getAddresses()
                                                            .forEach(
                                                                    addr -> {
                                                                        buildSubsetOwnerChain(
                                                                                nsNode, endpoint,
                                                                                addr);
                                                                    });
                                                });
                            });

            return nsNode;
        } catch (Exception e) {
            logger.warn(e);
            return null;
        }
    }

    private void buildSubsetOwnerChain(
            EnvironmentNode nsNode, Endpoints endpoint, EndpointAddress addr) {
        ObjectReference target = addr.getTargetRef();
        if (target == null) {
            logger.error("Address {} for Endpoint {} had null target reference",
                    addr.getIp() != null ? addr.getIp() : addr.getHostname(),
                    endpoint.getMetadata().getName());
            return;
        }
        String targetKind = target.getKind();
        String targetName = target.getName();
        NodeType targetType = NodeType.fromKubernetesKind(targetKind);
        if (targetType == NodeType.POD) {
            // if the Endpoint points to a Pod, chase the owner chain up as far as possible, then
            // add that to the Namespace
            EnvironmentNode pod = new EnvironmentNode(targetName, NodeType.POD);
            getServiceRefs(endpoint).stream()
                    .map(serviceRef -> new TargetNode(NodeType.ENDPOINT, serviceRef))
                    .forEach(pod::addChildNode);

            EnvironmentNode node = pod;
            while (true) {
                EnvironmentNode owner = createOwnerNode(node);
                if (owner == null) {
                    break;
                }
                owner.addChildNode(node);
                node = owner;
            }
            nsNode.addChildNode(node);
        } else {
            // if the Endpoint points to something else(?) than a Pod, just add the target straight
            // to the Namespace
            getServiceRefs(endpoint).stream()
                    .map(serviceRef -> new TargetNode(NodeType.ENDPOINT, serviceRef))
                    .forEach(nsNode::addChildNode);
        }
    }

    private EnvironmentNode createOwnerNode(EnvironmentNode child) {
        List<? extends HasMetadata> refs;
        try {
            refs = child.getNodeType().getGetterFunction().apply(k8sClient).apply(namespace);
        } catch (KubernetesClientException kce) {
            logger.error(kce);
            return null;
        }
        HasMetadata childRef =
                refs.stream()
                        .filter(o -> Objects.equals(o.getMetadata().getName(), child.getName()))
                        .findFirst()
                        .orElse(null);
        if (childRef == null) {
            logger.error(
                    "Could not locate node named {} of kind {} while traversing environment",
                    child.getName(),
                    child.getNodeType());
            return null;
        }
        List<OwnerReference> owners = childRef.getMetadata().getOwnerReferences();
        // Take first "expected" owner Kind from NodeTypes, or if none, simply use the first owner
        OwnerReference owner =
                owners.stream()
                        .filter(o -> NodeType.fromKubernetesKind(o.getKind()) != null)
                        .findFirst()
                        .orElse(owners.get(0));
        String ownerKind = owner.getKind();
        String ownerName = owner.getName();
        NodeType ownerType = NodeType.fromKubernetesKind(ownerKind);
        if (ownerType == null) {
            return null;
        }
        return new EnvironmentNode(ownerName, ownerType);
    }

    private boolean isCompatibleSubset(EndpointSubset subset) {
        return subset.getPorts().stream().anyMatch(this::isCompatiblePort);
    }

    private boolean isCompatiblePort(EndpointPort port) {
        return "jfr-jmx".equals(port.getName()) || 9091 == port.getPort();
    }

    private List<ServiceRef> getServiceRefs(Endpoints endpoints) {
        List<ServiceRef> refs = new ArrayList<>();
        endpoints.getSubsets().stream()
                .forEach(
                        subset ->
                                subset.getPorts().stream()
                                        .filter(this::isCompatiblePort)
                                        .forEach(
                                                port ->
                                                        refs.addAll(
                                                                createServiceRefs(subset, port))));
        return refs;
    }

    private List<ServiceRef> createServiceRefs(EndpointSubset subset, EndpointPort port) {
        return subset.getAddresses().stream()
                .map(
                        addr -> {
                            try {
                                ServiceRef serviceRef =
                                        new ServiceRef(
                                                URIUtil.convert(
                                                        connectionToolkit
                                                                .get()
                                                                .createServiceURL(
                                                                        addr.getIp(),
                                                                        port.getPort())),
                                                addr.getTargetRef().getName());
                                serviceRef.setCryostatAnnotations(
                                        Map.of(
                                                AnnotationKey.HOST, addr.getIp(),
                                                AnnotationKey.PORT,
                                                        Integer.toString(port.getPort()),
                                                AnnotationKey.NAMESPACE,
                                                        addr.getTargetRef().getNamespace(),
                                                AnnotationKey.POD_NAME,
                                                        addr.getTargetRef().getName()));
                                return serviceRef;
                            } catch (Exception e) {
                                logger.warn(e);
                                return null;
                            }
                        })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
