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
package itest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import itest.bases.ExternalTargetsTest;
import itest.util.Podman;
import itest.util.http.V2Response;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DiscoveryIT extends ExternalTargetsTest {

    private static final Gson gson =
            new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    static final int NUM_EXT_CONTAINERS = 1;
    static final List<String> CONTAINERS = new ArrayList<>();

    @BeforeAll
    static void setup() throws Exception {
        Set<Podman.ImageSpec> specs = new HashSet<>();
        for (int i = 0; i < NUM_EXT_CONTAINERS; i++) {
            specs.add(
                    new Podman.ImageSpec(
                            "quay.io/andrewazores/vertx-fib-demo:0.7.0",
                            Map.of("JMX_PORT", String.valueOf(9093 + i))));
        }
        for (Podman.ImageSpec spec : specs) {
            CONTAINERS.add(Podman.run(spec));
        }
        CompletableFuture.allOf(
                        CONTAINERS.stream()
                                .map(id -> Podman.waitForContainerState(id, "running"))
                                .collect(Collectors.toList())
                                .toArray(new CompletableFuture[0]))
                .join();
        waitForDiscovery(NUM_EXT_CONTAINERS);
    }

    @AfterAll
    static void cleanup() throws Exception {
        for (String id : CONTAINERS) {
            Podman.kill(id);
        }
    }

    @Test
    void testDiscovery() throws Exception {
        CompletableFuture<V2Response<DiscoveryResult>> resp = new CompletableFuture<>();
        webClient
                .get("/api/beta/discovery")
                .send(
                        ar -> {
                            if (assertRequestStatus(ar, resp)) {
                                resp.complete(
                                        gson.fromJson(
                                                ar.result().bodyAsString(),
                                                new TypeToken<
                                                        V2Response<
                                                                DiscoveryResult>>() {}.getType()));
                            }
                        });
        V2Response<DiscoveryResult> actual = resp.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        MatcherAssert.assertThat(actual.meta.status, Matchers.equalTo("OK"));

        // root node should be a Universe type
        Node rootNode = actual.data.result;
        MatcherAssert.assertThat(
                rootNode, Matchers.hasProperty("name", Matchers.equalTo("Universe")));
        MatcherAssert.assertThat(rootNode, Matchers.hasProperty("target", Matchers.nullValue()));
        MatcherAssert.assertThat(
                rootNode, Matchers.hasProperty("children", Matchers.not(Matchers.empty())));

        // with two child Realms: JDP and Custom Targets
        List<Node> realms = rootNode.children;
        MatcherAssert.assertThat(
                realms,
                Matchers.everyItem(Matchers.hasProperty("kind", Matchers.equalTo("Realm"))));
        MatcherAssert.assertThat(
                realms,
                Matchers.allOf(
                        Matchers.hasItem(
                                Matchers.hasProperty("name", Matchers.equalTo("Custom Targets"))),
                        Matchers.hasItem(Matchers.hasProperty("name", Matchers.equalTo("JDP")))));
        MatcherAssert.assertThat(realms, Matchers.hasSize(2));
        Node jdp = realms.stream().filter(node -> "JDP".equals(node.name)).findFirst().get();
        Node customTargets =
                realms.stream()
                        .filter(node -> "Custom Targets".equals(node.name))
                        .findFirst()
                        .get();

        // Custom Targets should have no children or labels, and should not be a target
        // TODO define a custom target and ensure it appears in this part of the response
        MatcherAssert.assertThat(customTargets.children, Matchers.empty());
        MatcherAssert.assertThat(customTargets.labels.keySet(), Matchers.empty());
        MatcherAssert.assertThat(customTargets.target, Matchers.nullValue());

        // JDP should have no labels and should not be a target, but it should have children
        MatcherAssert.assertThat(jdp.labels.keySet(), Matchers.empty());
        MatcherAssert.assertThat(jdp.target, Matchers.nullValue());
        MatcherAssert.assertThat(
                jdp.children,
                Matchers.everyItem(Matchers.hasProperty("kind", Matchers.equalTo("JVM"))));

        // There should be 2 JDP JVMs and both should be targets without further children
        List<Node> jvms = jdp.children;
        MatcherAssert.assertThat(
                jvms, Matchers.everyItem(Matchers.hasProperty("target", Matchers.notNullValue())));
        MatcherAssert.assertThat(
                jvms, Matchers.everyItem(Matchers.hasProperty("children", Matchers.nullValue())));
        MatcherAssert.assertThat(
                jvms,
                Matchers.hasItems(
                        Matchers.allOf(
                                Matchers.hasProperty(
                                        "name",
                                        Matchers.equalTo(
                                                "service:jmx:rmi:///jndi/rmi://cryostat-itests:9091/jmxrmi")),
                                Matchers.hasProperty(
                                        "target",
                                        Matchers.hasProperty(
                                                "connectUrl",
                                                Matchers.equalTo(
                                                        "service:jmx:rmi:///jndi/rmi://cryostat-itests:9091/jmxrmi"))),
                                Matchers.hasProperty(
                                        "target",
                                        Matchers.hasProperty(
                                                "alias",
                                                Matchers.equalTo("io.cryostat.Cryostat")))),
                        Matchers.allOf(
                                Matchers.hasProperty(
                                        "name",
                                        Matchers.equalTo(
                                                "service:jmx:rmi:///jndi/rmi://cryostat-itests:9093/jmxrmi")),
                                Matchers.hasProperty(
                                        "target",
                                        Matchers.hasProperty(
                                                "connectUrl",
                                                Matchers.equalTo(
                                                        "service:jmx:rmi:///jndi/rmi://cryostat-itests:9093/jmxrmi"))),
                                Matchers.hasProperty(
                                        "target",
                                        Matchers.hasProperty(
                                                "alias",
                                                Matchers.equalTo("es.andrewazor.demo.Main"))))));
        MatcherAssert.assertThat(jvms, Matchers.hasSize(2));

        // The 2 JDP JVMs should have the expected Cryostat annotations applied
        Node cryostat =
                jvms.stream()
                        .filter(node -> "io.cryostat.Cryostat".equals(node.target.alias))
                        .findFirst()
                        .get();
        MatcherAssert.assertThat(cryostat.target.annotations.platform, Matchers.equalTo(Map.of()));
        MatcherAssert.assertThat(
                cryostat.target.annotations.cryostat,
                Matchers.equalTo(
                        Map.of(
                                "HOST",
                                Podman.POD_NAME,
                                "PORT",
                                "9091",
                                "JAVA_MAIN",
                                "io.cryostat.Cryostat")));
        Node demoApp =
                jvms.stream()
                        .filter(node -> "es.andrewazor.demo.Main".equals(node.target.alias))
                        .findFirst()
                        .get();
        MatcherAssert.assertThat(demoApp.target.annotations.platform, Matchers.equalTo(Map.of()));
        MatcherAssert.assertThat(
                demoApp.target.annotations.cryostat,
                Matchers.equalTo(
                        Map.of(
                                "HOST",
                                Podman.POD_NAME,
                                "PORT",
                                "9093",
                                "JAVA_MAIN",
                                "es.andrewazor.demo.Main")));
    }

    // public getters in the classes below are needed for Hamcrest Matchers.hasProperty calls
    public static class DiscoveryResult {
        Node result;

        public Node getNode() {
            return result;
        }
    }

    public static class Node {
        String name;
        String kind;
        List<Node> children;
        Map<String, String> labels;
        Target target;

        public String getName() {
            return name;
        }

        public String getKind() {
            return kind;
        }

        public List<Node> getChildren() {
            return children;
        }

        public Map<String, String> getLabels() {
            return labels;
        }

        public Target getTarget() {
            return target;
        }
    }

    public static class Target {
        String connectUrl;
        String alias;
        Annotations annotations;
        Map<String, String> labels;

        public String getConnectUrl() {
            return connectUrl;
        }

        public String getAlias() {
            return alias;
        }

        public Annotations getAnnotations() {
            return annotations;
        }

        public Map<String, String> getLabels() {
            return labels;
        }
    }

    public static class Annotations {
        Map<String, String> platform;
        Map<String, String> cryostat;

        public Map<String, String> getPlatform() {
            return platform;
        }

        public Map<String, String> getCryostat() {
            return cryostat;
        }
    }
}
