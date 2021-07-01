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

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public abstract class AbstractNode {
    protected NodeType nodeType;
    protected Map<String, String> labels;

    protected AbstractNode(NodeType nodeType, Map<String, String> labels) {
        this.nodeType = nodeType;
        this.labels = labels;
    }

    protected NodeType getNodeType() {
        return this.nodeType;
    }

    protected Map<String, String> getLabels() {
        return this.labels;
    }

    // FIXME this is Kubernetes-specific, but the type should be an interface that various
    // platform-specific types can implement
    public enum NodeType {
        UNIVERSE(""), // represents the entire deployment scenario Cryostat finds itself in
        NAMESPACE("Namespace", UNIVERSE),
        DEPLOYMENT("Deployment", NAMESPACE),
        DEPLOYMENTCONFIG("DeploymentConfig", DEPLOYMENT),
        REPLICASET("ReplicaSet", DEPLOYMENT),
        REPLICATIONCONTROLLER("ReplicationController", NAMESPACE),
        POD("Pod", REPLICASET, REPLICATIONCONTROLLER),
        CONTAINER("Container", POD),
        ENDPOINT("Endpoint", POD);

        private final String kubernetesKind;
        private Set<NodeType> parentTypes;

        NodeType(String kubernetesKind, NodeType... parentTypes) {
            this.kubernetesKind = kubernetesKind;
            this.parentTypes = Set.of(parentTypes);
        }

        public String getKind() {
            return kubernetesKind;
        }

        public Set<NodeType> getParentTypes() {
            return parentTypes;
        }

        public static NodeType fromKubernetesKind(String kubernetesKind) {
            for (NodeType nt : values()) {
                if (Objects.equals(nt.getKind(), kubernetesKind)) {
                    return nt;
                }
            }
            return null;
        }

        static {
            for (NodeType nt : values()) {
                if (nt.parentTypes == null || nt.parentTypes.isEmpty()) {
                    nt.parentTypes = EnumSet.noneOf(NodeType.class);
                } else {
                    nt.parentTypes = EnumSet.copyOf(nt.parentTypes);
                }
            }
        }
    }
}
