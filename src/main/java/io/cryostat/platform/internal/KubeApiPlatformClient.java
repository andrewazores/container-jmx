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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.TreeSet;

import io.cryostat.core.log.Logger;
import io.cryostat.core.net.JFRConnectionToolkit;
import io.cryostat.core.net.discovery.JvmDiscoveryClient.EventKind;
import io.cryostat.platform.ServiceRef;
import io.cryostat.platform.ServiceRef.AnnotationKey;
import io.cryostat.util.URIUtil;
import io.cryostat.net.AbstractNode.NodeType;
import io.cryostat.net.EnvironmentNode;

import dagger.Lazy;
import io.fabric8.kubernetes.api.model.EndpointPort;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.client.KubernetesClient;
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
            Map<String, Set<EnvironmentNode>> tempNodes = new HashMap<>();
            List<String> nodeTypes = Arrays.asList("Pod", "ReplicaSet", "ReplicationController", "Deployment", "DeploymentConfig");
            for (String type : nodeTypes) {
                tempNodes.put(type, new TreeSet<>());
            }

            // Create root node (namespace)
            Map<String, String> nsLabels = new HashMap<String,String>();
            nsLabels.put("name", namespace);
            EnvironmentNode nsNode = new EnvironmentNode(NodeType.NAMESPACE, nsLabels);

            // For each endpoint, create parent node(s) in tempNodes
            k8sClient.endpoints().inNamespace(namespace).list().getItems().forEach(
                endpoint -> {
                    List<EndpointSubset> subsets = endpoint.getSubsets();
                    if (subsets.isEmpty()) return;
                    // Maybe should loop through subsets as well
                    List<EndpointAddress> addresses = subsets.stream().findFirst().get().getAddresses();
                    if (addresses.isEmpty()) return;

                    // Create node
                    Map<String, String> labels = new HashMap<String, String>();
                    labels.put("name", endpoint.getMetadata().getName());
                    List<ServiceRef> serviceRefs = getServiceRefs(endpoint);
                    if (serviceRefs.isEmpty()) return;
                    TargetNode node = new TargetNode(NodeType.ENDPOINT, labels, serviceRefs.stream().findFirst().get());

                    boolean foundParent = false;
                    for (EndpointAddress address: addresses) {
                        ObjectReference objectRef = address.getTargetRef();
                        if (objectRef == null) continue;

                        String parentName = objectRef.getName();
                        String parentKind = objectRef.getKind();
                        if (parentKind != "Pod") continue;

                        if (createParent(parentName, parentKind, node, tempNodes, nodeTypes)) {
                            foundParent = true;
                        };
                    }
                    if (!foundParent) {
                        nsNode.addChildNode(node);
                    }
                }
            );

            // Get list of pods, find ownerRef of the relevant ones
            k8sClient.pods().inNamespace(namespace).list().getItems().forEach(
                pod -> {
                    String podName = pod.getMetadata().getName();
                    // Check if its in tempNodes
                    for (EnvironmentNode node : tempNodes.get("Pod")) {
                        if (node.getLabels().get("name").equals(podName)) {
                            List<OwnerReference> ownerRefs = pod.getMetadata().getOwnerReferences();
                            if (ownerRefs.isEmpty()) {
                                nsNode.addChildNode(node);
                                continue;
                            }
                            String parentName = ownerRefs.stream().findFirst().get().getName();
                            String parentKind = ownerRefs.stream().findFirst().get().getKind();

                            if (!createParent(parentName, parentKind, node, tempNodes, nodeTypes)) {
                                nsNode.addChildNode(node);
                            }
                        }
                    }
                }
            );

            // Get list of ReplicaSets, find onwerRef of the relevant ones
            k8sClient.apps().replicaSets().inNamespace(namespace).list().getItems().forEach(
                rs -> {
                    String rsName = rs.getMetadata().getName();
                    // Check if its in tempNodes
                    for (EnvironmentNode node : tempNodes.get("ReplicaSet")) {
                        if (node.getLabels().get("name").equals(rsName)) {
                            List<OwnerReference> ownerRefs = rs.getMetadata().getOwnerReferences();
                            if (ownerRefs.isEmpty()) {
                                nsNode.addChildNode(node);
                                continue;
                            }
                            String parentName = ownerRefs.stream().findFirst().get().getName();
                            String parentKind = ownerRefs.stream().findFirst().get().getKind();

                            if (!createParent(parentName, parentKind, node, tempNodes, nodeTypes)) {
                                nsNode.addChildNode(node);
                            }
                        }
                    }
                }
            );

            // Add deployment nodes to root (namespace)
            for (EnvironmentNode node : tempNodes.get("Deployment")) {
                nsNode.addChildNode(node);
            }

            // NEED TO ADD SIMILAR CODE FOR REPLICATIONCONTROLLERS AND DEPLOYMENTCONFIGS

            return nsNode;
        } catch (Exception e) {
            logger.warn(e);
            return null;
        }
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

    private boolean createParent(String parentName, String parentKind, AbstractNode child,
        Map<String, Set<EnvironmentNode>> tempNodes, List<String> nodeTypes) {

        if (!nodeTypes.contains(parentKind)) {
            return false;
        }
        Set<EnvironmentNode> nodeList = tempNodes.get(parentKind);
        for (EnvironmentNode node : nodeList) {
            if (node.getLabels().get("name").equals(parentName)) {
                // only add child
                node.addChildNode(child);
                return true;
            }
        }
        // If parent doesn't exist, create one
        // Bad implementation, has to be a way to set a string value for each enum that can directly be compared to parentKind
        NodeType type;
        switch(parentKind) {
            case "Pod" :
                type = NodeType.POD;
            case "ReplicaSet" :
                type = NodeType.REPLICASET;
            case "ReplicationController" :
                type = NodeType.REPLICATIONCONTROLLER;
            case "Deployment" :
                type = NodeType.DEPLOYMENT;
            case "DeploymentConfig" :
                type = NodeType.DEPLOYMENTCONFIG;
            default :
                type = null;
        }
        Map<String, String> labels = new HashMap<String, String>();
        labels.put("name", parentName);
        EnvironmentNode parent = new EnvironmentNode(type, labels);
        tempNodes.get(parentKind).add(parent);
        return true;
    }
}
