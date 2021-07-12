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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import dagger.Lazy;
import io.cryostat.core.log.Logger;
import io.cryostat.core.net.JFRConnectionToolkit;
import io.cryostat.core.sys.Environment;
import io.cryostat.platform.ServiceRef;
import io.cryostat.platform.internal.DefaultPlatformClient.JDPNodeType;
import io.cryostat.platform.overview.BaseNodeType;
import io.cryostat.platform.overview.EnvironmentNode;
import io.cryostat.platform.overview.NodeType;
import io.cryostat.platform.overview.TargetNode;
import io.cryostat.util.URIUtil;

class KubeEnvPlatformClient extends AbstractPlatformClient {

    private static final Pattern SERVICE_ENV_PATTERN =
            Pattern.compile("([\\S]+)_PORT_([\\d]+)_TCP_ADDR");
    private final Lazy<JFRConnectionToolkit> connectionToolkit;
    private final Environment env;
    private final Logger logger;

    KubeEnvPlatformClient(
            Lazy<JFRConnectionToolkit> connectionToolkit, Environment env, Logger logger) {
        this.connectionToolkit = connectionToolkit;
        this.env = env;
        this.logger = logger;
    }

    @Override
    public void start() throws IOException {}

    @Override
    public List<ServiceRef> listDiscoverableServices() {
        return env.getEnv().entrySet().stream()
                .map(this::envToServiceRef)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public EnvironmentNode getTargetEnvironment() {
        EnvironmentNode root = new EnvironmentNode("KubernetesEnv", BaseNodeType.REALM);
        List<ServiceRef> targets = listDiscoverableServices();
        for (ServiceRef target : targets) {
            TargetNode targetNode = new TargetNode(new JDPNodeType(), target);
            root.addChildNode(targetNode);
        }
        return root;
    }

    private ServiceRef envToServiceRef(Map.Entry<String, String> entry) {
        Matcher matcher = SERVICE_ENV_PATTERN.matcher(entry.getKey());
        if (!matcher.matches()) {
            return null;
        }
        String alias = matcher.group(1).toLowerCase();
        int port = Integer.parseInt(matcher.group(2));
        try {
            return new ServiceRef(
                    URIUtil.convert(
                            connectionToolkit.get().createServiceURL(entry.getValue(), port)),
                    alias);
        } catch (Exception e) {
            logger.warn(e);
            return null;
        }
    }

    public static class KubernetesNodeType implements NodeType {

        public static final String KIND = "KubernetesEnv";

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
