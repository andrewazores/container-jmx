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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.cryostat.core.log.Logger;
import io.cryostat.core.net.JFRConnectionToolkit;
import io.cryostat.core.net.discovery.JvmDiscoveryClient.EventKind;
import io.cryostat.platform.ServiceRef;
import io.cryostat.platform.ServiceRef.AnnotationKey;
import io.cryostat.platform.discovery.BaseNodeType;
import io.cryostat.platform.discovery.EnvironmentNode;
import io.cryostat.platform.discovery.NodeType;
import io.cryostat.platform.discovery.TargetNode;
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
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import org.apache.commons.lang3.tuple.Pair;

public class KubeApiPlatformClient extends AbstractPlatformClient {

    private final KubernetesClient k8sClient;
    private final Lazy<JFRConnectionToolkit> connectionToolkit;
    private final Logger logger;
    private final String namespace;
    private final Map<Pair<String, String>, Pair<HasMetadata, EnvironmentNode>> discoveryNodeCache =
            new HashMap<>();
    private final Map<Pair<String, String>, Object> queryLocks = new HashMap<>();

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
            return getAllServiceRefs();
        } catch (Exception e) {
            logger.warn(e);
            return Collections.emptyList();
        }
    }

    @Override
    public EnvironmentNode getDiscoveryTree() {
        EnvironmentNode nsNode = new EnvironmentNode(namespace, KubernetesNodeType.NAMESPACE);
        EnvironmentNode realmNode = new EnvironmentNode("KubernetesApi", BaseNodeType.REALM);
        realmNode.addChildNode(nsNode);
        try {
            k8sClient
                    .endpoints()
                    .inNamespace(namespace)
                    .list()
                    .getItems()
                    .parallelStream()
                    .flatMap(endpoints -> getTargetTuples(endpoints).stream())
                    .forEach(tuple -> buildOwnerChain(nsNode, tuple));
        } catch (Exception e) {
            logger.warn(e);
        } finally {
            discoveryNodeCache.clear();
            queryLocks.clear();
        }
        return realmNode;
    }

    private void buildOwnerChain(EnvironmentNode nsNode, TargetTuple targetTuple) {
        ObjectReference target = targetTuple.addr.getTargetRef();
        if (target == null) {
            logger.error(
                    "Address {} for Endpoint {} had null target reference",
                    targetTuple.addr.getIp() != null
                            ? targetTuple.addr.getIp()
                            : targetTuple.addr.getHostname(),
                    targetTuple.objRef.getName());
            return;
        }
        String targetKind = target.getKind();
        KubernetesNodeType targetType = KubernetesNodeType.fromKubernetesKind(targetKind);
        if (targetType == KubernetesNodeType.POD) {
            // if the Endpoint points to a Pod, chase the owner chain up as far as possible, then
            // add that to the Namespace

            Pair<HasMetadata, EnvironmentNode> pod =
                    discoveryNodeCache.computeIfAbsent(cacheKey(target), this::queryForNode);
            pod.getRight()
                    .addChildNode(
                            new TargetNode(
                                    KubernetesNodeType.ENDPOINT, targetTuple.toServiceRef()));

            Pair<HasMetadata, EnvironmentNode> node = pod;
            while (true) {
                Pair<HasMetadata, EnvironmentNode> owner = getOrCreateOwnerNode(node);
                if (owner == null) {
                    break;
                }
                EnvironmentNode ownerNode = owner.getRight();
                ownerNode.addChildNode(node.getRight());
                node = owner;
            }
            nsNode.addChildNode(node.getRight());
        } else {
            // if the Endpoint points to something else(?) than a Pod, just add the target straight
            // to the Namespace
            nsNode.addChildNode(
                    new TargetNode(KubernetesNodeType.ENDPOINT, targetTuple.toServiceRef()));
        }
    }

    private Pair<HasMetadata, EnvironmentNode> getOrCreateOwnerNode(
            Pair<HasMetadata, EnvironmentNode> child) {
        HasMetadata childRef = child.getLeft();
        if (childRef == null) {
            logger.error(
                    "Could not locate node named {} of kind {} while traversing environment",
                    child.getRight().getName(),
                    child.getRight().getNodeType());
            return null;
        }
        List<OwnerReference> owners = childRef.getMetadata().getOwnerReferences();
        // Take first "expected" owner Kind from NodeTypes, or if none, simply use the first owner
        OwnerReference owner =
                owners.stream()
                        .filter(o -> KubernetesNodeType.fromKubernetesKind(o.getKind()) != null)
                        .findFirst()
                        .orElse(owners.get(0));
        return discoveryNodeCache.computeIfAbsent(cacheKey(owner), this::queryForNode);
    }

    private Pair<String, String> cacheKey(OwnerReference resource) {
        return Pair.of(resource.getKind(), resource.getName());
    }

    // Unfortunately, ObjectReference and OwnerReference both independently implement getKind and
    // getName - they don't come from a common base class.
    private Pair<String, String> cacheKey(ObjectReference resource) {
        return Pair.of(resource.getKind(), resource.getName());
    }

    private Pair<HasMetadata, EnvironmentNode> queryForNode(Pair<String, String> lookupKey) {
        KubernetesNodeType nodeType = KubernetesNodeType.fromKubernetesKind(lookupKey.getLeft());
        String nodeName = lookupKey.getRight();
        if (nodeType == null) {
            return null;
        }
        synchronized (queryLocks.computeIfAbsent(lookupKey, k -> new Object())) {
            EnvironmentNode node;
            HasMetadata ownerRef =
                    nodeType.getQueryFunction().apply(k8sClient).apply(namespace).apply(nodeName);
            if (ownerRef != null) {
                node = new EnvironmentNode(nodeName, nodeType, ownerRef.getMetadata().getLabels());
            } else {
                node = new EnvironmentNode(nodeName, nodeType);
            }
            return Pair.of(ownerRef, node);
        }
    }

    private boolean isCompatiblePort(EndpointPort port) {
        return "jfr-jmx".equals(port.getName()) || 9091 == port.getPort();
    }

    private List<ServiceRef> getAllServiceRefs() {
        return k8sClient.endpoints().inNamespace(namespace).list().getItems().stream()
                .flatMap(endpoints -> getServiceRefs(endpoints).stream())
                .collect(Collectors.toList());
    }

    private List<TargetTuple> getTargetTuples(Endpoints endpoints) {
        List<TargetTuple> tts = new ArrayList<>();
        for (EndpointSubset subset : endpoints.getSubsets()) {
            for (EndpointPort port : subset.getPorts()) {
                if (!isCompatiblePort(port)) {
                    continue;
                }
                for (EndpointAddress addr : subset.getAddresses()) {
                    tts.add(new TargetTuple(addr.getTargetRef(), addr, port));
                }
            }
        }
        return tts;
    }

    private List<ServiceRef> getServiceRefs(Endpoints endpoints) {
        return getTargetTuples(endpoints).stream()
                .map(TargetTuple::toServiceRef)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private class TargetTuple {
        ObjectReference objRef;
        EndpointAddress addr;
        EndpointPort port;

        TargetTuple(ObjectReference objRef, EndpointAddress addr, EndpointPort port) {
            this.objRef = objRef;
            this.addr = addr;
            this.port = port;
        }

        ServiceRef toServiceRef() {
            try {
                Pair<HasMetadata, EnvironmentNode> node =
                        discoveryNodeCache.computeIfAbsent(
                                cacheKey(objRef), KubeApiPlatformClient.this::queryForNode);
                String targetName = objRef.getName();
                ServiceRef serviceRef =
                        new ServiceRef(
                                URIUtil.convert(
                                        connectionToolkit
                                                .get()
                                                .createServiceURL(addr.getIp(), port.getPort())),
                                targetName);

                if (node.getRight().getNodeType() == KubernetesNodeType.POD) {
                    HasMetadata podRef = node.getLeft();
                    if (podRef != null) {
                        serviceRef.setLabels(podRef.getMetadata().getLabels());
                        serviceRef.setPlatformAnnotations(podRef.getMetadata().getAnnotations());
                    }
                }
                serviceRef.setCryostatAnnotations(
                        Map.of(
                                AnnotationKey.HOST,
                                addr.getIp(),
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
        }
    }

    public enum KubernetesNodeType implements NodeType {
        NAMESPACE("Namespace", c -> ns -> n -> c.namespaces().withName(n).get()),
        STATEFULSET(
                "StatefulSet",
                c -> ns -> n -> c.apps().statefulSets().inNamespace(ns).withName(n).get()),
        DAEMONSET(
                "DaemonSet",
                c -> ns -> n -> c.apps().daemonSets().inNamespace(ns).withName(n).get()),
        DEPLOYMENT(
                "Deployment",
                c -> ns -> n -> c.apps().deployments().inNamespace(ns).withName(n).get()),
        // FIXME DeploymentConfig is OpenShift-specific
        DEPLOYMENTCONFIG("DeploymentConfig"),
        REPLICASET(
                "ReplicaSet",
                c -> ns -> n -> c.apps().replicaSets().inNamespace(ns).withName(n).get()),
        REPLICATIONCONTROLLER(
                "ReplicationController",
                c -> ns -> n -> c.replicationControllers().inNamespace(ns).withName(n).get()),
        SERVICE("Service", c -> ns -> n -> c.services().inNamespace(ns).withName(n).get()),
        INGRESS("Ingress", c -> ns -> n -> c.network().ingress().inNamespace(ns).withName(n).get()),
        // FIXME Route is OpenShift-specific
        ROUTE("Route"),
        POD("Pod", c -> ns -> n -> c.pods().inNamespace(ns).withName(n).get()),
        ENDPOINT("Endpoint", c -> ns -> n -> c.endpoints().inNamespace(ns).withName(n).get()),
        ;

        private final String kubernetesKind;
        private final transient Function<
                        KubernetesClient, Function<String, Function<String, ? extends HasMetadata>>>
                getFn;

        KubernetesNodeType(String kubernetesKind) {
            this(kubernetesKind, client -> namespace -> name -> null);
        }

        KubernetesNodeType(
                String kubernetesKind,
                Function<
                                KubernetesClient,
                                Function<String, Function<String, ? extends HasMetadata>>>
                        getFn) {
            this.kubernetesKind = kubernetesKind;
            this.getFn = getFn;
        }

        public String getKind() {
            return kubernetesKind;
        }

        public Function<KubernetesClient, Function<String, Function<String, ? extends HasMetadata>>>
                getQueryFunction() {
            return getFn;
        }

        public static KubernetesNodeType fromKubernetesKind(String kubernetesKind) {
            if (kubernetesKind == null) {
                return null;
            }
            for (KubernetesNodeType nt : values()) {
                if (kubernetesKind.equalsIgnoreCase(nt.kubernetesKind)) {
                    return nt;
                }
            }
            return null;
        }
    }
}
