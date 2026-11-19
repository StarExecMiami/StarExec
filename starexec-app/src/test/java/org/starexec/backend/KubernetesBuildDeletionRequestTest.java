package org.starexec.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.extension.ExtensibleResource;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Exercises the installed client against a synthetic HTTP API, without cluster configuration. */
class KubernetesBuildDeletionRequestTest {
    private static final class Api implements AutoCloseable {
        final HttpServer server;
        final AtomicReference<JsonObject> deletion = new AtomicReference<>();
        final AtomicReference<URI> deletedUri = new AtomicReference<>();
        boolean conflict;

        Api() throws Exception {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                String body; int status = 200;
                if (exchange.getRequestMethod().equals("DELETE")) {
                    deletion.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
                    deletedUri.set(exchange.getRequestURI());
                    if (conflict) {
                        status = 409;
                        body = "{\"apiVersion\":\"v1\",\"kind\":\"Status\",\"status\":\"Failure\",\"reason\":\"Conflict\",\"code\":409}";
                    } else body = "{\"apiVersion\":\"v1\",\"kind\":\"Status\",\"status\":\"Success\"}";
                } else if (exchange.getRequestURI().getPath().endsWith("/pods")) {
                    body = "{\"apiVersion\":\"v1\",\"kind\":\"PodList\",\"items\":[]}";
                } else if (exchange.getRequestURI().getPath().endsWith("/jobs")) {
                    body = "{\"apiVersion\":\"batch/v1\",\"kind\":\"JobList\",\"items\":[]}";
                } else {
                    status = 404;
                    body = "{\"apiVersion\":\"v1\",\"kind\":\"Status\",\"reason\":\"NotFound\",\"code\":404}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
            });
            server.start();
        }

        KubernetesClient client() {
            Config config = Config.empty();
            config.setMasterUrl("http://localhost:" + server.getAddress().getPort());
            config.setNamespace("starexec");
            return new KubernetesClientBuilder().withConfig(config).build();
        }

        @Override public void close() { server.stop(0); }
    }

    @Test @SuppressWarnings("unchecked")
    void theInstalledClientSendsTheObservedVersionAndForegroundPolicy() throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client()) {
            var resource = client.batch().v1().jobs().inNamespace("starexec")
                    .resource(KubernetesBuildStopObserverTest.finishedJob());
            assertInstanceOf(ExtensibleResource.class, resource);
            ((ExtensibleResource<Job>) resource).withPropagationPolicy(DeletionPropagation.FOREGROUND)
                    .lockResourceVersion("17").delete();
            assertEquals("/apis/batch/v1/namespaces/starexec/jobs/build-job", api.deletedUri.get().getPath());
            assertEquals("Foreground", api.deletion.get().get("propagationPolicy").getAsString());
            assertEquals("17", api.deletion.get().getAsJsonObject("preconditions").get("resourceVersion").getAsString());
        }
    }

    @Test @SuppressWarnings("unchecked")
    void aVersionConflictIsReportedInsteadOfDeletingAReplacement() throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client()) {
            api.conflict = true;
            var resource = (ExtensibleResource<Job>) client.batch().v1().jobs().inNamespace("starexec")
                    .resource(KubernetesBuildStopObserverTest.finishedJob());
            KubernetesClientException conflict = assertThrows(KubernetesClientException.class,
                    () -> resource.withPropagationPolicy(DeletionPropagation.FOREGROUND).lockResourceVersion("17").delete());
            assertEquals(409, conflict.getCode());
        }
    }

    @Test void backendBulkDeletionExcludesEveryMarkedBuild() throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client()) {
            var backend = new KubernetesNativeBackend();
            for (var entry : java.util.Map.of("namespace", (Object) "starexec", "kubernetesClient", client).entrySet()) {
                var field = KubernetesNativeBackend.class.getDeclaredField(entry.getKey());
                field.setAccessible(true); field.set(backend, entry.getValue());
            }
            assertTrue(backend.killAll());
            String query = URLDecoder.decode(api.deletedUri.get().getRawQuery(), StandardCharsets.UTF_8);
            assertTrue(query.contains("starexec.org/managed=true"));
            assertTrue(query.contains("!starexec.org/build-artifact"));
            assertEquals("Foreground", api.deletion.get().get("propagationPolicy").getAsString());
        }
    }
}
