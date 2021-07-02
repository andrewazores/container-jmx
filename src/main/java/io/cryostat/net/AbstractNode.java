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
package io.cryostat.net;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.google.gson.annotations.SerializedName;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;

public abstract class AbstractNode implements Comparable<AbstractNode> {

    protected final String name;

    @SerializedName("kind")
    protected final NodeType nodeType;

    protected final Map<String, String> labels;

    protected AbstractNode(String name, NodeType nodeType, Map<String, String> labels) {
        this.name = name;
        this.nodeType = nodeType;
        this.labels = labels;
    }

    public String getName() {
        return name;
    }

    public NodeType getNodeType() {
        return this.nodeType;
    }

    public Map<String, String> getLabels() {
        return Collections.unmodifiableMap(labels);
    }

    @Override
    public int compareTo(AbstractNode other) {
        int type = nodeType.ordinal() - other.nodeType.ordinal();
        if (type != 0) {
            return type;
        }
        return name.compareTo(other.name);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((labels == null) ? 0 : labels.hashCode());
        result = prime * result + ((name == null) ? 0 : name.hashCode());
        result = prime * result + ((nodeType == null) ? 0 : nodeType.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        AbstractNode other = (AbstractNode) obj;
        if (labels == null) {
            if (other.labels != null) return false;
        } else if (!labels.equals(other.labels)) return false;
        if (name == null) {
            if (other.name != null) return false;
        } else if (!name.equals(other.name)) return false;
        if (nodeType != other.nodeType) return false;
        return true;
    }

    // FIXME this is Kubernetes-specific, but the type should be an interface that various
    // platform-specific types can implement
    public enum NodeType {
        UNIVERSE(""), // represents the entire deployment scenario Cryostat finds itself in
        NAMESPACE("Namespace"),
        STATEFULSET(
                "StatefulSet", c -> n -> c.apps().statefulSets().inNamespace(n).list().getItems()),
        DAEMONSET("DaemonSet", c -> n -> c.apps().daemonSets().inNamespace(n).list().getItems()),
        DEPLOYMENT("Deployment", c -> n -> c.apps().deployments().inNamespace(n).list().getItems()),
        DEPLOYMENTCONFIG("DeploymentConfig"),
        REPLICASET("ReplicaSet", c -> n -> c.apps().replicaSets().inNamespace(n).list().getItems()),
        REPLICATIONCONTROLLER(
                "ReplicationController",
                c -> n -> c.replicationControllers().inNamespace(n).list().getItems()),
        SERVICE("Service", c -> n -> c.services().inNamespace(n).list().getItems()),
        INGRESS("Ingress", c -> n -> c.network().ingress().inNamespace(n).list().getItems()),
        ROUTE("Route"),
        POD("Pod", c -> n -> c.pods().inNamespace(n).list().getItems()),
        CONTAINER("Container"),
        ENDPOINT("Endpoint");

        private final String kubernetesKind;
        private final transient Function<
                        KubernetesClient, Function<String, List<? extends HasMetadata>>>
                getFn;

        NodeType(String kubernetesKind) {
            this(kubernetesKind, client -> namespace -> List.of());
        }

        NodeType(
                String kubernetesKind,
                Function<KubernetesClient, Function<String, List<? extends HasMetadata>>> getFn) {
            this.kubernetesKind = kubernetesKind;
            this.getFn = getFn;
        }

        public String getKind() {
            return kubernetesKind;
        }

        public Function<KubernetesClient, Function<String, List<? extends HasMetadata>>>
                getGetterFunction() {
            return getFn;
        }

        public static NodeType fromKubernetesKind(String kubernetesKind) {
            if (kubernetesKind == null) {
                return null;
            }
            for (NodeType nt : values()) {
                if (kubernetesKind.equalsIgnoreCase(nt.kubernetesKind)) {
                    return nt;
                }
            }
            return null;
        }
    }
}
