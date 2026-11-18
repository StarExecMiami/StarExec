package org.starexec.test.junit.backend;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.starexec.backend.KubernetesNativeBackend;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node changes the queue operations make, through a real fabric8 client against an HTTP
 * server standing in for the API server.
 *
 * <p>Not mocks, because the defect these guard against was inside fabric8: its {@code edit()}
 * clones the live Node through Jackson, and with the Jackson this build ships that clone
 * throws on the node's managedFields. Every real node carries them, so on Quokka every queue
 * operation failed, while tests that mocked the client passed. The nodes served here carry
 * managedFields too.
 */
public class KubernetesNodePatchTest {

    private static final String NO_NODES =
        "{\"apiVersion\":\"v1\",\"kind\":\"NodeList\",\"metadata\":{},\"items\":[]}";

    /** One request as the stand-in API server received it. */
    private static final class Request {
        final String method;
        final String path;
        final String contentType;
        final String body;

        Request(String method, String path, String contentType, String body) {
            this.method = method;
            this.path = path;
            this.contentType = contentType;
            this.body = body;
        }
    }

    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile String nodeList = NO_NODES;
    private HttpServer server;
    private KubernetesClient client;

    /** A node as the API server returns one: with the managedFields that broke edit(). */
    private static String node(String name, boolean unschedulable) {
        return "{\"apiVersion\":\"v1\",\"kind\":\"Node\",\"metadata\":{\"name\":\"" + name + "\","
            + "\"labels\":{\"starexec/queue\":\"kubernetes.q\",\"starexec.org/worker\":\"true\"},"
            + "\"managedFields\":[{\"manager\":\"kubectl-label\",\"operation\":\"Update\","
            + "\"apiVersion\":\"v1\",\"time\":\"2026-10-15T12:00:00Z\",\"fieldsType\":\"FieldsV1\","
            + "\"fieldsV1\":{\"f:metadata\":{\"f:labels\":{\"f:starexec/queue\":{}}}}}]},"
            + "\"spec\":{" + (unschedulable ? "\"unschedulable\":true" : "") + "}}";
    }

    private static String nodeList(String... nodes) {
        return "{\"apiVersion\":\"v1\",\"kind\":\"NodeList\",\"metadata\":{},\"items\":["
            + String.join(",", nodes) + "]}";
    }

    @BeforeEach
    public void startApiServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String path = exchange.getRequestURI().getPath();
            requests.add(new Request(exchange.getRequestMethod(), path,
                exchange.getRequestHeaders().getFirst("Content-Type"), body));
            String response;
            if (path.equals("/api/v1/nodes")) {
                response = nodeList;
            } else if (path.startsWith("/api/v1/nodes/")) {
                response = node(path.substring("/api/v1/nodes/".length()), false);
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        Config config = new ConfigBuilder(Config.empty())
            .withMasterUrl("http://127.0.0.1:" + server.getAddress().getPort())
            .withRequestRetryBackoffLimit(0)
            .build();
        client = new KubernetesClientBuilder().withConfig(config).build();
    }

    @AfterEach
    public void stopApiServer() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    private KubernetesNativeBackend backend() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "kubernetesClient", client);
        setField(backend, "queueLabelKey", "starexec/queue");
        setField(backend, "workerNodeSelectorKey", "starexec.org/worker");
        setField(backend, "workerNodeSelectorValue", "true");
        return backend;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = KubernetesNativeBackend.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private List<Request> patches() {
        List<Request> out = new ArrayList<>();
        synchronized (requests) {
            for (Request r : requests) {
                if (r.method.equals("PATCH")) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    private static void assertMergePatch(Request request, String nodeName, String expectedJson) {
        assertEquals("/api/v1/nodes/" + nodeName, request.path);
        assertTrue(request.contentType != null
                && request.contentType.startsWith("application/merge-patch+json"),
            "expected a JSON merge patch, got " + request.contentType);
        assertEquals(JsonParser.parseString(expectedJson), JsonParser.parseString(request.body));
    }

    /** What Geoff tried on Quokka: a queue for three broken workers. It failed every time. */
    @Test
    public void creatingAQueueLabelsEveryChosenNode() throws Exception {
        assertTrue(backend().createQueueWithSlots("dead.q", new String[] {"n024", "n025"}, null, 2));

        List<Request> patches = patches();
        assertEquals(2, patches.size());
        String labels =
            "{\"metadata\":{\"labels\":{\"starexec/queue\":\"dead.q\",\"starexec.org/slots\":\"2\"}}}";
        assertMergePatch(patches.get(0), "n024", labels);
        assertMergePatch(patches.get(1), "n025", labels);
    }

    @Test
    public void movingNodesToANamedQueueSetsTheirLabel() throws Exception {
        backend().moveNodes("dead.q", new String[] {"n032"}, null);

        List<Request> patches = patches();
        assertEquals(1, patches.size());
        assertMergePatch(patches.get(0), "n032", "{\"metadata\":{\"labels\":{\"starexec/queue\":\"dead.q\"}}}");
    }

    /**
     * A default-queue node carries no queue label, so moving one there removes the label. A
     * merge patch removes a key by setting it to null, which therefore has to be sent.
     */
    @Test
    public void movingNodesToTheDefaultQueueRemovesTheirLabel() throws Exception {
        backend().moveNodes("all", new String[] {"n024"}, null);

        List<Request> patches = patches();
        assertEquals(1, patches.size());
        assertMergePatch(patches.get(0), "n024", "{\"metadata\":{\"labels\":{\"starexec/queue\":null}}}");
    }

    @Test
    public void deletingAQueueRemovesTheLabelFromEachOfItsNodes() throws Exception {
        nodeList = nodeList(node("n024", false), node("n025", false));

        backend().deleteQueue("dead.q");

        List<Request> patches = patches();
        assertEquals(2, patches.size());
        String removal = "{\"metadata\":{\"labels\":{\"starexec/queue\":null}}}";
        assertMergePatch(patches.get(0), "n024", removal);
        assertMergePatch(patches.get(1), "n025", removal);
    }

    @Test
    public void clearingNodeErrorsUncordonsOnlyTheCordonedNodes() throws Exception {
        nodeList = nodeList(node("n024", true), node("n021", false));

        assertTrue(backend().clearNodeErrorStates());

        List<Request> patches = patches();
        assertEquals(1, patches.size());
        assertMergePatch(patches.get(0), "n024", "{\"spec\":{\"unschedulable\":false}}");
    }
}
