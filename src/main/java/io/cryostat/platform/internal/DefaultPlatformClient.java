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
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.management.remote.JMXServiceURL;

import io.cryostat.core.log.Logger;
import io.cryostat.core.net.discovery.DiscoveredJvmDescriptor;
import io.cryostat.core.net.discovery.JvmDiscoveryClient;
import io.cryostat.core.net.discovery.JvmDiscoveryClient.JvmDiscoveryEvent;
import io.cryostat.net.AbstractNode;
import io.cryostat.net.AbstractNode.NodeType;
import io.cryostat.net.EnvironmentNode;
import io.cryostat.net.TargetNode;
import io.cryostat.platform.ServiceRef;
import io.cryostat.platform.ServiceRef.AnnotationKey;
import io.cryostat.util.URIUtil;

class DefaultPlatformClient extends AbstractPlatformClient implements Consumer<JvmDiscoveryEvent> {

    private final Logger logger;
    private final JvmDiscoveryClient discoveryClient;

    DefaultPlatformClient(Logger logger, JvmDiscoveryClient discoveryClient) {
        this.logger = logger;
        this.discoveryClient = discoveryClient;
    }

    @Override
    public void start() throws IOException {
        discoveryClient.addListener(this);
        discoveryClient.start();
    }

    @Override
    public void accept(JvmDiscoveryEvent evt) {
        try {
            notifyAsyncTargetDiscovery(evt.getEventKind(), convert(evt.getJvmDescriptor()));
        } catch (MalformedURLException | URISyntaxException e) {
            logger.warn(e);
        }
    }

    @Override
    public List<ServiceRef> listDiscoverableServices() {
        return discoveryClient.getDiscoveredJvmDescriptors().stream()
                .map(
                        desc -> {
                            try {
                                return convert(desc);
                            } catch (MalformedURLException | URISyntaxException e) {
                                logger.warn(e);
                                return null;
                            }
                        })
                .filter(s -> s != null)
                .collect(Collectors.toList());
    }

    private static ServiceRef convert(DiscoveredJvmDescriptor desc)
            throws MalformedURLException, URISyntaxException {
        JMXServiceURL serviceUrl = desc.getJmxServiceUrl();
        ServiceRef serviceRef = new ServiceRef(URIUtil.convert(serviceUrl), desc.getMainClass());
        URI rmiTarget = URIUtil.getRmiTarget(serviceUrl);
        serviceRef.setCryostatAnnotations(
                Map.of(
                        AnnotationKey.JAVA_MAIN, desc.getMainClass(),
                        AnnotationKey.HOST, rmiTarget.getHost(),
                        AnnotationKey.PORT, Integer.toString(rmiTarget.getPort())));
        return serviceRef;
    }

    @Override
    public EnvironmentNode getTargetEnvironment() {
        Map<String, String> rootLabels = new HashMap<String, String>();
        rootLabels.put("name", "root");
        EnvironmentNode root = new EnvironmentNode(NodeType.NAMESPACE, rootLabels);
        List<ServiceRef> targets = listDiscoverableServices();
        for (ServiceRef target : targets) {
            Map<String, String> targetLabels = new HashMap<String, String>();
            Optional<String> alias = target.getAlias();
            if (alias.isPresent()) {
                targetLabels.put("name", alias.get());
            }
            TargetNode targetNode =
                    new TargetNode(AbstractNode.NodeType.CONTAINER, targetLabels, target);
            root.addChildNode(targetNode);
        }
        return root;
    }
}
