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
import java.io.Reader;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.inject.Named;

import io.cryostat.MainModule;
import io.cryostat.core.net.discovery.JvmDiscoveryClient.EventKind;
import io.cryostat.core.sys.FileSystem;
import io.cryostat.net.AbstractNode.BaseNodeType;
import io.cryostat.net.AbstractNode.NodeType;
import io.cryostat.net.EnvironmentNode;
import io.cryostat.net.TargetNode;
import io.cryostat.platform.ServiceRef;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class CustomTargetPlatformClient extends AbstractPlatformClient {

    public static final CustomTargetNodeType NODE_TYPE = new CustomTargetNodeType();

    static final String SAVEFILE_NAME = "custom_targets.json";

    private final SortedSet<ServiceRef> targets;
    private final Path saveFile;
    private final FileSystem fs;
    private final Gson gson;

    public CustomTargetPlatformClient(
            @Named(MainModule.CONF_DIR) Path confDir, FileSystem fs, Gson gson) {
        this.targets = new TreeSet<>((u1, u2) -> u1.getServiceUri().compareTo(u2.getServiceUri()));
        this.saveFile = confDir.resolve(SAVEFILE_NAME);
        this.fs = fs;
        this.gson = gson;
    }

    @Override
    public void start() throws IOException {
        if (fs.isRegularFile(saveFile) && fs.isReadable(saveFile)) {
            try (Reader reader = fs.readFile(saveFile)) {
                this.targets.addAll(
                        gson.fromJson(reader, new TypeToken<List<ServiceRef>>() {}.getType()));
            }
        }
    }

    public boolean addTarget(ServiceRef serviceRef) throws IOException {
        boolean v = targets.add(serviceRef);
        if (v) {
            fs.writeString(
                    saveFile,
                    gson.toJson(targets),
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.CREATE);
            notifyAsyncTargetDiscovery(EventKind.FOUND, serviceRef);
        }
        return v;
    }

    public boolean removeTarget(ServiceRef serviceRef) throws IOException {
        boolean v = targets.remove(serviceRef);
        if (v) {
            fs.writeString(
                    saveFile,
                    gson.toJson(targets),
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.CREATE);
            notifyAsyncTargetDiscovery(EventKind.LOST, serviceRef);
        }
        return v;
    }

    public boolean removeTarget(URI connectUrl) throws IOException {
        ServiceRef ref = null;
        for (ServiceRef target : targets) {
            if (Objects.equals(connectUrl, target.getServiceUri())) {
                ref = target;
                break;
            }
        }
        if (ref != null) {
            return removeTarget(ref);
        }
        return false;
    }

    @Override
    public List<ServiceRef> listDiscoverableServices() {
        return new ArrayList<>(targets);
    }

    @Override
    public EnvironmentNode getTargetEnvironment() {
        EnvironmentNode customTargetsNode =
                new EnvironmentNode("Custom Targets", BaseNodeType.REALM);
        targets.forEach(
                sr ->
                        customTargetsNode.addChildNode(
                                new TargetNode(new CustomTargetNodeType(), sr)));
        return customTargetsNode;
    }

    public static class CustomTargetNodeType implements NodeType {

        private CustomTargetNodeType() {}

        public static final String KIND = "CustomTarget";

        @Override
        public String getKind() {
            return KIND;
        }

        @Override
        public int ordinal() {
            return 0;
        }
    }
}
