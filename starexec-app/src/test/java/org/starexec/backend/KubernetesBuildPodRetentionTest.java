package org.starexec.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.to.SolverBuildStopReceipt;

class KubernetesBuildPodRetentionTest {
    private final UUID token = UUID.randomUUID();
    private final String engine = "kubernetes:starexec:7:job-uid";
    private volatile SolverBuildStopReceipt receipt;
    private boolean refuseCommit;
    private boolean failCommit;

    private static Job retainedJob() {
        Job job = KubernetesBuildLaunchFenceTest.initialJob();
        job.getSpec().setSuspend(false); job.getMetadata().setGeneration(2L);
        job.setStatus(KubernetesBuildStopObserverTest.finishedJob().getStatus());
        job.getSpec().getTemplate().getMetadata().setFinalizers(List.of(KubernetesBuildPodRetention.FINALIZER));
        return job;
    }

    private static Pod retainedPod() {
        Pod pod = KubernetesBuildStopObserverTest.finishedPod();
        pod.getMetadata().setResourceVersion("29");
        pod.getMetadata().setFinalizers(List.of(KubernetesBuildPodRetention.FINALIZER));
        pod.getMetadata().getOwnerReferences().get(0).setBlockOwnerDeletion(true);
        return pod;
    }

    private static KubernetesBuildStopObserver.Identity identity() {
        return KubernetesBuildStopObserver.identify("starexec", retainedJob());
    }

    private final class Api implements AutoCloseable {
        private final KubernetesSerialization json = new KubernetesSerialization();
        private final HttpServer server;
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final List<String> versions = new CopyOnWriteArrayList<>();
        private final List<String> writePaths = new CopyOnWriteArrayList<>();
        private Job job = retainedJob();
        private List<Pod> pods = new ArrayList<>(List.of(retainedPod()));
        private String invalidCensus;
        private String invalidReply;
        private String selector;
        private String refusePod;
        private boolean failAfterWrite;
        private boolean reorderReply;
        private boolean jobDeleted;
        private boolean deletionRequested;

        Api() throws Exception {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String body; int code = 200;
                if (exchange.getRequestMethod().equals("PUT") && path.contains("/pods/")) {
                    Pod requested = json.unmarshal(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Pod.class);
                    versions.add(requested.getMetadata().getResourceVersion()); writePaths.add(path);
                    Pod observed = pods.stream().filter(p -> p.getMetadata().getName().equals(requested.getMetadata().getName()))
                            .findFirst().orElse(null);
                    if (observed == null) { code = 404; body = status("NotFound", code); }
                    else if (requested.getMetadata().getName().equals(refusePod)
                            || !observed.getMetadata().getResourceVersion().equals(requested.getMetadata().getResourceVersion())) {
                        code = 409; body = status("Conflict", code);
                    } else if (receipt == null) { code = 412; body = status("PreconditionFailed", code); }
                    else {
                        Pod updated = new PodBuilder(requested).editMetadata().withResourceVersion("30").endMetadata().build();
                        pods.set(pods.indexOf(observed), updated); events.add("release:" + updated.getMetadata().getName());
                        Pod reply = new PodBuilder(updated).build();
                        if (reorderReply) {
                            var finalizers = new ArrayList<>(reply.getMetadata().getFinalizers());
                            java.util.Collections.reverse(finalizers); reply.getMetadata().setFinalizers(finalizers);
                        }
                        if (invalidReply != null) {
                            switch (invalidReply) {
                                case "uid" -> reply.getMetadata().setUid("other-uid");
                                case "name" -> reply.getMetadata().setName("other-name");
                                case "namespace" -> reply.getMetadata().setNamespace("other-namespace");
                                case "version" -> reply.getMetadata().setResourceVersion("29");
                                case "retained" -> reply.getMetadata().setFinalizers(observed.getMetadata().getFinalizers());
                                case "foreign-finalizer" -> reply.getMetadata().setFinalizers(List.of());
                                default -> throw new AssertionError(invalidReply);
                            }
                        }
                        if (failAfterWrite) { code = 500; body = status("InternalError", code); }
                        else body = json.asJson(reply);
                    }
                } else if (exchange.getRequestMethod().equals("DELETE")) {
                    if (receipt == null || pods.stream().anyMatch(p -> p.getMetadata().getFinalizers()
                            .contains(KubernetesBuildPodRetention.FINALIZER))) {
                        code = 412; body = status("PreconditionFailed", code);
                    } else {
                        deletionRequested = true;
                        jobDeleted = pods.stream().noneMatch(p -> p.getMetadata().getFinalizers() != null
                                && !p.getMetadata().getFinalizers().isEmpty());
                        events.add("delete-job"); body = status("Success", 200);
                    }
                } else if (path.endsWith("/pods")) {
                    selector = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
                    PodList listing = new PodListBuilder().withNewMetadata().withResourceVersion("41").endMetadata().withItems(pods).build();
                    if (invalidCensus != null) {
                        switch (invalidCensus) {
                            case "version" -> listing.getMetadata().setResourceVersion(null);
                            case "page" -> listing.getMetadata().setContinue("next-page");
                            case "remaining" -> listing.getMetadata().setRemainingItemCount(1L);
                            default -> throw new AssertionError(invalidCensus);
                        }
                    }
                    body = json.asJson(listing);
                } else if (path.contains("/pods/")) {
                    Pod pod = pods.stream().filter(p -> path.endsWith("/" + p.getMetadata().getName())).findFirst().orElse(null);
                    if (pod == null) { code = 404; body = status("NotFound", code); }
                    else body = json.asJson(pod);
                } else if (jobDeleted) { code = 404; body = status("NotFound", code); }
                else body = json.asJson(job);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
        }

        KubernetesClient client() {
            Config config = Config.empty(); config.setMasterUrl("http://localhost:" + server.getAddress().getPort());
            config.setNamespace("starexec"); config.setRequestTimeout(2000); config.setConnectionTimeout(2000);
            return new KubernetesClientBuilder().withConfig(config).build();
        }

        @Override public void close() { server.stop(0); }
    }

    private static String status(String reason, int code) {
        return "{\"apiVersion\":\"v1\",\"kind\":\"Status\",\"status\":\"" + (code == 200 ? "Success" : "Failure")
                + "\",\"reason\":\"" + reason + "\",\"code\":" + code + "}";
    }

    private MockedStatic<SolverBuildArtifacts> registered(Api api) throws SQLException {
        var dao = mockStatic(SolverBuildArtifacts.class);
        dao.when(() -> SolverBuildArtifacts.find(42, 1)).thenAnswer(call -> Optional.of(
                new SolverBuildArtifacts.Artifact(5, 42, 1, 9, 9, 10, 1, "/inputs/source", "/outputs/build",
                    null, null, token, engine, receipt == null ? null : Instant.now(), "STAGED")));
        dao.when(() -> SolverBuildArtifacts.findStopReceipt(42, 1)).thenAnswer(call ->
                receipt == null ? Optional.empty() : Optional.of(new SolverBuildArtifacts.StoredStopReceipt(receipt, Instant.now())));
        dao.when(() -> SolverBuildArtifacts.recordStopReceipt(eq(42), eq(1), eq(token), eq(engine), any()))
                .thenAnswer(call -> {
                    if (failCommit) throw new SQLException("synthetic unavailable catalog");
                    if (refuseCommit) return false;
                    var incoming = call.getArgument(4, SolverBuildStopReceipt.class);
                    if (receipt != null && !receipt.payload().equals(incoming.payload())) return false;
                    receipt = incoming; api.events.add("commit"); return true;
                });
        return dao;
    }

    private void conserve(Api api, KubernetesClient client) throws SQLException {
        var pods = KubernetesBuildPodRetention.observe(client, "starexec", identity(), api.job);
        KubernetesBuildStopObserver.record("starexec", identity(), api.job, pods);
    }

    private boolean delete(Api api, KubernetesClient client) throws Exception {
        var backend = new KubernetesNativeBackend();
        for (var entry : Map.of("namespace", (Object) "starexec", "kubernetesClient", client).entrySet()) {
            var field = KubernetesNativeBackend.class.getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(backend, entry.getValue());
        }
        var method = KubernetesNativeBackend.class.getDeclaredMethod("ensureKubernetesJobGone", String.class);
        method.setAccessible(true);
        try { return (boolean) method.invoke(backend, api.job.getMetadata().getName()); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw (Error) failure.getCause();
        }
    }

    @Test void backendCommitsBeforeReleasingOnlyItsFinalizerAndDeletingTheJob() throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client(); var dao = registered(api)) {
            api.pods.get(0).getMetadata().setFinalizers(List.of(KubernetesBuildPodRetention.FINALIZER, "example.org/other-owner"));
            assertFalse(delete(api, client), "a different owner's finalizer still prevents observed Job removal");
            assertTrue(api.deletionRequested); assertFalse(api.jobDeleted);
            assertEquals(List.of("commit", "release:build-pod", "delete-job"), api.events);
            assertEquals(List.of("29"), api.versions);
            assertEquals(List.of("/api/v1/namespaces/starexec/pods/build-pod"), api.writePaths);
            assertEquals(List.of("example.org/other-owner"), api.pods.get(0).getMetadata().getFinalizers());
            assertTrue(api.selector.contains("batch.kubernetes.io/controller-uid=job-uid"));
            api.pods.clear();
            assertTrue(delete(api, client), "owned cleanup finishes once the other owner releases its Pod");
        }
    }

    @Test void noReceiptCannotAuthorizeAnyFinalizerRelease() throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client(); var dao = registered(api)) {
            assertThrows(IllegalStateException.class, () -> KubernetesBuildPodRetention.release(client, "starexec", identity(), api.job));
            assertTrue(api.versions.isEmpty()); assertFalse(api.jobDeleted);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"refused", "unavailable"})
    void anUncommittedObservationRetainsAllResources(String fault) throws Exception {
        refuseCommit = fault.equals("refused"); failCommit = fault.equals("unavailable");
        try (Api api = new Api(); KubernetesClient client = api.client(); var dao = registered(api)) {
            assertFalse(delete(api, client));
            assertNull(receipt); assertTrue(api.versions.isEmpty()); assertFalse(api.jobDeleted);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"version", "page", "remaining"})
    void anIncompleteCensusCannotReleaseEvidence(String fault) throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client(); var dao = registered(api)) {
            conserve(api, client); api.invalidCensus = fault;
            assertThrows(IllegalStateException.class, () -> KubernetesBuildPodRetention.release(client, "starexec", identity(), api.job));
            assertTrue(api.versions.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"uid", "name", "namespace", "version", "retained", "foreign-finalizer"})
    void anInconsistentAcknowledgementCannotAuthorizeJobDeletion(String fault) throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client(); var dao = registered(api)) {
            if (fault.equals("foreign-finalizer")) {
                api.pods.get(0).getMetadata().setFinalizers(List.of(KubernetesBuildPodRetention.FINALIZER, "example.org/other-owner"));
            }
            api.invalidReply = fault;
            assertFalse(delete(api, client)); assertFalse(api.jobDeleted); assertNotNull(receipt);
        }
    }

    @Test void aPodVersionConflictKeepsTheJobAndCanRetryFromTheSameReceipt() throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client(); var dao = registered(api)) {
            conserve(api, client); String payload = receipt.payload(); api.refusePod = "build-pod";
            var conflict = assertThrows(KubernetesClientException.class,
                    () -> KubernetesBuildPodRetention.release(client, "starexec", identity(), api.job));
            assertEquals(409, conflict.getCode()); assertEquals(List.of("29"), api.versions);
            assertFalse(api.jobDeleted); api.refusePod = null;
            assertTrue(delete(api, client)); assertEquals(payload, receipt.payload());
        }
    }

    @Test void aLostAcknowledgementReusesCommittedBytesAfterTheFinalizerWasRemoved() throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client(); var dao = registered(api)) {
            conserve(api, client); String payload = receipt.payload(); api.failAfterWrite = true;
            assertFalse(delete(api, client)); assertFalse(api.jobDeleted);
            api.failAfterWrite = false;
            assertTrue(delete(api, client)); assertEquals(payload, receipt.payload());
        }
    }

    @Test void partialRemovalAndRestartRetainEveryRemainingPodUntilItsOwnRelease() throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client(); var dao = registered(api)) {
            Pod second = new PodBuilder(retainedPod()).editMetadata().withName("build-pod-two").withUid("pod-two").endMetadata().build();
            api.pods.add(second); api.refusePod = "build-pod-two";
            assertFalse(delete(api, client)); assertFalse(api.jobDeleted);
            assertFalse(api.pods.get(0).getMetadata().getFinalizers().contains(KubernetesBuildPodRetention.FINALIZER));
            assertTrue(api.pods.get(1).getMetadata().getFinalizers().contains(KubernetesBuildPodRetention.FINALIZER));
            String payload = receipt.payload(); api.refusePod = null; api.pods.remove(0);
            assertTrue(delete(api, client)); assertEquals(payload, receipt.payload());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"pod", "container", "running", "owner"})
    void aChangedPhysicalObservationCannotBorrowTheOldReceipt(String changed) throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client(); var dao = registered(api)) {
            conserve(api, client); Pod pod = api.pods.get(0);
            switch (changed) {
                case "pod" -> pod.getMetadata().setUid("new-pod");
                case "container" -> {
                    pod.getStatus().getContainerStatuses().get(0).setContainerID("containerd://new-container");
                    pod.getStatus().getContainerStatuses().get(0).getState().getTerminated().setContainerID("containerd://new-container");
                }
                case "running" -> pod.getStatus().getContainerStatuses().get(0).setState(new ContainerStateBuilder().withNewRunning().endRunning().build());
                case "owner" -> pod.getMetadata().getOwnerReferences().get(0).setUid("other-job");
                default -> throw new AssertionError(changed);
            }
            assertThrows(IllegalStateException.class, () -> KubernetesBuildPodRetention.release(client, "starexec", identity(), api.job));
            assertTrue(api.versions.isEmpty());
        }
    }

    @Test void missingRetentionBeforeTheFirstObservationCannotBeRecoveredByGuessing() throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client(); var dao = registered(api)) {
            api.pods.get(0).getMetadata().setFinalizers(List.of());
            assertFalse(delete(api, client)); assertNull(receipt); assertTrue(api.versions.isEmpty());
        }
    }

    @Test void aStillActiveControllerCannotReleaseEvenConservedContainerEvidence() throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client(); var dao = registered(api)) {
            conserve(api, client); api.job.setStatus(null);
            assertThrows(IllegalStateException.class, () -> KubernetesBuildPodRetention.release(client, "starexec", identity(), api.job));
            assertTrue(api.versions.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"namespace", "uid", "selector", "manual", "controller", "typed-controller", "template", "retention", "expressions"})
    void aDifferentControllerCannotReleaseTheConservedPods(String changed) throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client(); var dao = registered(api)) {
            conserve(api, client);
            switch (changed) {
                case "namespace" -> api.job.getMetadata().setNamespace("other-namespace");
                case "uid" -> api.job.getMetadata().setUid("other-job");
                case "selector" -> api.job.getSpec().getSelector().setMatchLabels(Map.of("controller-uid", "other-job"));
                case "manual" -> api.job.getSpec().setManualSelector(true);
                case "controller" -> api.job.getSpec().getAdditionalProperties().put("managedBy", "example.org/controller");
                case "typed-controller" -> api.job.getSpec().setManagedBy("example.org/controller");
                case "template" -> api.job.getSpec().getTemplate().getMetadata().setLabels(Map.of());
                case "retention" -> api.job.getSpec().getTemplate().getMetadata().setFinalizers(List.of());
                case "expressions" -> api.job.getSpec().getSelector().setMatchExpressions(List.of(
                        new LabelSelectorRequirementBuilder().withKey("other").withOperator("Exists").build()));
                default -> throw new AssertionError(changed);
            }
            assertThrows(IllegalStateException.class, () -> KubernetesBuildPodRetention.release(client, "starexec", identity(), api.job));
            assertTrue(api.versions.isEmpty());
        }
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" "})
    void anUnversionedPodCannotBeUpdated(String version) throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client(); var dao = registered(api)) {
            conserve(api, client); api.pods.get(0).getMetadata().setResourceVersion(version);
            assertThrows(IllegalStateException.class, () -> KubernetesBuildPodRetention.release(client, "starexec", identity(), api.job));
            assertTrue(api.versions.isEmpty());
        }
    }

    @Test void aRepeatedPodIdentityIsRejectedBeforeAnyFinalizerIsRemoved() throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client(); var dao = registered(api)) {
            conserve(api, client); api.pods.add(new PodBuilder(api.pods.get(0)).build());
            assertThrows(IllegalStateException.class, () -> KubernetesBuildPodRetention.release(client, "starexec", identity(), api.job));
            assertTrue(api.versions.isEmpty());
        }
    }

    @Test void anAcknowledgementMayReorderOtherOwnersFinalizersButCannotRemoveThem() throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client(); var dao = registered(api)) {
            api.pods.get(0).getMetadata().setFinalizers(List.of(KubernetesBuildPodRetention.FINALIZER,
                    "example.org/owner-one", "example.org/owner-two"));
            api.reorderReply = true;
            assertFalse(delete(api, client));
            assertTrue(api.deletionRequested, "acknowledged removal permits a request, while other owners still block absence");
            assertFalse(api.jobDeleted);
            assertEquals(List.of("example.org/owner-one", "example.org/owner-two"), api.pods.get(0).getMetadata().getFinalizers());
        }
    }
}
