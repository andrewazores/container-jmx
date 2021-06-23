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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EnvironmentNode extends AbstractNode implements Comparable<EnvironmentNode> {
    private List<AbstractNode> children;

    public EnvironmentNode(NodeType nodeType, Map<String, String> labels) {
        super(nodeType, labels);
        this.children = new ArrayList();
    }

    public int compareTo(EnvironmentNode node) {
        if (this.nodeType.ordinal() > node.getNodeType().ordinal()) return 1;
        else if (this.nodeType.ordinal() == node.getNodeType().ordinal()) return 0;
        else return -1;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) return false;
        if (this == o) return true;
        if (o instanceof EnvironmentNode) {
            if (((EnvironmentNode) o).getNodeType() != this.nodeType) return false;
            String objectName = ((EnvironmentNode) o).getLabels().get("name");
            if (!objectName.equals(this.labels.get("name"))) return false;
            return true;
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        int code = 0;
        String name = this.labels.get("name");
        if (name != null) {
            code += name.hashCode();
        }
        code += this.nodeType.ordinal();
        return code;
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    public Map<String, String> getLabels() {
        return this.labels;
    }

    public void addChildNode(AbstractNode child) {
        if (child != null) {
            this.children.add(child);
        }
    }
}
