package org.starexec.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.*;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.starexec.data.database.SolverBuildArtifacts;

class KubernetesBuildLaunchFenceTest {
    static Job initialJob() {
        Job job = KubernetesBuildStopObserverTest.finishedJob();
        job.getMetadata().setGeneration(1L); job.getSpec().setSuspend(true); job.setStatus(null);
        Map<String, String> selector = Map.of("batch.kubernetes.io/controller-uid", "job-uid");
        job.getSpec().setSelector(new LabelSelectorBuilder().withMatchLabels(selector).build());
        job.getSpec().setTemplate(new PodTemplateSpecBuilder().withNewMetadata().withLabels(selector).endMetadata().build());
        return job;
    }

    private static KubernetesBuildStopObserver.Identity identity() {
        return KubernetesBuildStopObserver.identify("starexec", initialJob());
    }

    static final class Api implements AutoCloseable {
        final KubernetesSerialization json = new KubernetesSerialization();
        final HttpServer server;
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger censuses = new AtomicInteger();
        final List<String> writeVersions = new CopyOnWriteArrayList<>();
        final List<String> writePaths = new CopyOnWriteArrayList<>();
        final AtomicInteger deletes = new AtomicInteger();
        final List<String> deleteVersions = new CopyOnWriteArrayList<>();
        final AtomicInteger podWrites = new AtomicInteger();
        final List<String> podWriteVersions = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        volatile List<Pod> podItems = List.of();
        volatile boolean refusePodWrite;
        volatile boolean deleted;
        volatile boolean refuseDeletion;
        volatile BooleanSupplier deletionAllowed = () -> true;
        volatile Job current = initialJob();
        volatile String invalidCensus;
        volatile boolean lateCensus;
        volatile String invalidReply;
        volatile boolean failAfterWrite;
        volatile String selector;
        volatile Runnable onCensus = () -> { };

        Api() throws Exception {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                String body; int code = 200;
                if (exchange.getRequestMethod().equals("PUT") && exchange.getRequestURI().getPath().contains("/pods/")) {
                    podWrites.incrementAndGet();
                    Pod requested = json.unmarshal(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Pod.class);
                    podWriteVersions.add(requested.getMetadata().getResourceVersion());
                    Pod observed = podItems.stream().filter(p -> p.getMetadata().getName().equals(requested.getMetadata().getName()))
                            .findFirst().orElseThrow();
                    if (refusePodWrite || !observed.getMetadata().getResourceVersion().equals(requested.getMetadata().getResourceVersion())) {
                        code = 409; body = status("Conflict", code);
                    } else {
                        Pod updated = new PodBuilder(requested).editMetadata().withResourceVersion(
                                Long.toString(Long.parseLong(observed.getMetadata().getResourceVersion()) + 1)).endMetadata().build();
                        podItems = podItems.stream().map(p -> p == observed ? updated : p).toList();
                        events.add("pod:release"); body = json.asJson(updated);
                    }
                } else if (exchange.getRequestMethod().equals("PUT")) {
                    writes.incrementAndGet();
                    Job requested = json.unmarshal(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Job.class);
                    writeVersions.add(requested.getMetadata().getResourceVersion());
                    writePaths.add(exchange.getRequestURI().getPath());
                    if (!current.getMetadata().getResourceVersion().equals(requested.getMetadata().getResourceVersion())) {
                        code = 409; body = status("Conflict", code);
                    } else {
                        long generation = current.getMetadata().getGeneration() + (current.getSpec().equals(requested.getSpec()) ? 0 : 1);
                        String nextVersion = Long.toString(Long.parseLong(current.getMetadata().getResourceVersion()) + 1);
                        current = new JobBuilder(requested).editMetadata().withResourceVersion(nextVersion).withGeneration(generation).endMetadata().build();
                        events.add("job:update");
                        Job reply = new JobBuilder(current).build();
                        if (invalidReply != null) {
                            switch (invalidReply) {
                                case "uid" -> reply.getMetadata().setUid("different-uid");
                                case "generation" -> reply.getMetadata().setGeneration(2L);
                                case "version" -> reply.getMetadata().setResourceVersion("17");
                                case "marker" -> reply.getMetadata().setAnnotations(Map.of());
                                case "enabled" -> reply.getSpec().setSuspend(false);
                                case "suspended" -> reply.getSpec().setSuspend(true);
                                case "deadline" -> reply.getSpec().setActiveDeadlineSeconds(99L);
                                default -> throw new AssertionError(invalidReply);
                            }
                        }
                        if (failAfterWrite) { code = 500; body = status("InternalError", code); }
                        else body = json.asJson(reply);
                    }
                } else if (exchange.getRequestMethod().equals("DELETE")) {
                    deletes.incrementAndGet();
                    var options = com.google.gson.JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    var preconditions = options.getAsJsonObject("preconditions");
                    String version = preconditions == null || !preconditions.has("resourceVersion") ? null : preconditions.get("resourceVersion").getAsString();
                    deleteVersions.add(version);
                    if (refuseDeletion || !current.getMetadata().getResourceVersion().equals(version)) {
                        code = 409; body = status("Conflict", code);
                    } else if (!deletionAllowed.getAsBoolean()) {
                        code = 412; body = status("PreconditionFailed", code);
                    } else {
                        events.add("job:delete"); deleted = true;
                        podItems = podItems.stream().filter(p -> p.getMetadata().getFinalizers() != null
                                && !p.getMetadata().getFinalizers().isEmpty()).toList();
                        body = "{\"apiVersion\":\"v1\",\"kind\":\"Status\",\"status\":\"Success\"}";
                    }
                } else if (exchange.getRequestURI().getPath().endsWith("/pods")) {
                    onCensus.run();
                    int census = censuses.incrementAndGet();
                    selector = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
                    PodList listing = new PodListBuilder().withNewMetadata().withResourceVersion("41").endMetadata().withItems(podItems).build();
                    if (invalidCensus != null && (!lateCensus || census > 1)) {
                        switch (invalidCensus) {
                            case "version" -> listing.getMetadata().setResourceVersion(null);
                            case "page" -> listing.getMetadata().setContinue("next-page");
                            case "blank-page" -> listing.getMetadata().setContinue(" ");
                            case "remaining" -> listing.getMetadata().setRemainingItemCount(1L);
                            case "pod" -> listing.setItems(List.of(KubernetesBuildStopObserverTest.finishedPod()));
                            default -> throw new AssertionError(invalidCensus);
                        }
                    }
                    body = json.asJson(listing);
                } else if (exchange.getRequestURI().getPath().endsWith("/jobs")) {
                    body = json.asJson(new JobListBuilder().withItems(deleted ? List.<Job>of() : List.of(current)).build());
                } else if (deleted) {
                    code = 404; body = status("NotFound", code);
                } else {
                    body = json.asJson(current);
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.start();
        }

        private static String status(String reason, int code) {
            return "{\"apiVersion\":\"v1\",\"kind\":\"Status\",\"status\":\"Failure\",\"reason\":\"" + reason + "\",\"code\":" + code + "}";
        }

        KubernetesClient client() {
            Config config = Config.empty(); config.setMasterUrl("http://localhost:" + server.getAddress().getPort());
            config.setNamespace("starexec");
            return new KubernetesClientBuilder().withConfig(config).build();
        }

        @Override public void close() { server.stop(0); }
    }

    private static void resume(KubernetesClient client, Job job) throws Exception {
        var backend = new KubernetesNativeBackend();
        for (var entry : Map.of("namespace", (Object) "starexec", "kubernetesClient", client).entrySet()) {
            var field = KubernetesNativeBackend.class.getDeclaredField(entry.getKey());
            field.setAccessible(true); field.set(backend, entry.getValue());
        }
        var method = KubernetesNativeBackend.class.getDeclaredMethod("resumeAdmittedBuild",
                BuildExecutionContext.class, ExecutionRef.class, Job.class);
        method.setAccessible(true);
        BuildExecutionContext build = new BuildExecutionContext(5, 42, 1, UUID.randomUUID(), Path.of("/inputs/source"), Path.of("/outputs/build"));
        try (var dao = mockStatic(SolverBuildArtifacts.class)) {
            dao.when(() -> SolverBuildArtifacts.admitExecution(eq(build), anyString())).thenReturn(true);
            try { method.invoke(backend, build, identity().execution(), job); }
            catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                throw (Error) failure.getCause();
            }
        }
    }

    @Test void cancellationWinningTheVersionRaceInvalidatesAnAlreadyAdmittedResume() throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client()) {
            Job created = initialJob();
            created.getSpec().getTemplate().getMetadata().setFinalizers(List.of(KubernetesBuildPodRetention.FINALIZER));
            api.current = new JobBuilder(created).build();
            var observation = KubernetesBuildLaunchFence.preventStart(client, "starexec", identity(), created);
            assertEquals("18", observation.job().getMetadata().getResourceVersion());
            assertEquals(1L, observation.job().getMetadata().getGeneration());
            assertEquals("17", observation.job().getMetadata().getAnnotations().get(KubernetesBuildLaunchFence.ANNOTATION));
            assertEquals("41", observation.pods().getMetadata().getResourceVersion());
            assertEquals("17", created.getMetadata().getResourceVersion());
            assertFalse(KubernetesBuildLaunchFence.isFenced(created));
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> resume(client, created));
            assertEquals("Build initial observation cannot authorize launch", refused.getMessage());
            assertEquals(1, api.writes.get(), "a freshly observed launch fence must prevent the resume PUT");
            assertEquals(List.of("17"), api.writeVersions, "only the original fence request may write");
            assertEquals("18", api.current.getMetadata().getResourceVersion());
            assertEquals("17", api.current.getMetadata().getAnnotations().get(KubernetesBuildLaunchFence.ANNOTATION));
            assertTrue(api.writePaths.stream().allMatch(path -> path.equals("/apis/batch/v1/namespaces/starexec/jobs/build-job")));
            assertTrue(api.current.getSpec().getSuspend());
            assertTrue(api.selector.contains("batch.kubernetes.io/controller-uid=job-uid"));
        }
    }

    @Test void aResumeWinningFirstCannotBeReclassifiedAsNeverStartedByAnEmptyCensus() throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client()) {
            Job created = initialJob();
            created.getSpec().getTemplate().getMetadata().setFinalizers(List.of(KubernetesBuildPodRetention.FINALIZER));
            api.current = new JobBuilder(created).build();
            resume(client, created);
            assertFalse(api.current.getSpec().getSuspend()); assertEquals(2L, api.current.getMetadata().getGeneration());
            KubernetesClientException conflict = assertThrows(KubernetesClientException.class,
                    () -> KubernetesBuildLaunchFence.preventStart(client, "starexec", identity(), created));
            assertEquals(409, conflict.getCode()); assertEquals(2, api.writes.get());
            assertEquals(List.of("17", "17"), api.writeVersions);
            assertFalse(KubernetesBuildLaunchFence.isFenced(api.current));
        }
    }

    @Test void recoveryReusesTheAcknowledgedFenceWithoutAnotherVersionChange() throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client()) {
            KubernetesBuildLaunchFence.preventStart(client, "starexec", identity(), initialJob());
            Job recovered = client.batch().v1().jobs().inNamespace("starexec").withName("build-job").get();
            var observation = KubernetesBuildLaunchFence.preventStart(client, "starexec", identity(), recovered);
            assertEquals("18", observation.job().getMetadata().getResourceVersion());
            assertEquals(1, api.writes.get()); assertTrue(observation.pods().getItems().isEmpty());
            assertThrows(IllegalStateException.class, () -> resume(client, recovered));
            assertEquals(1, api.writes.get());
        }
    }

    @Test void anAppliedButUnacknowledgedFenceRemainsRecoverable() throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client()) {
            api.failAfterWrite = true;
            assertThrows(KubernetesClientException.class,
                    () -> KubernetesBuildLaunchFence.preventStart(client, "starexec", identity(), initialJob()));
            assertTrue(KubernetesBuildLaunchFence.isFenced(api.current));
            api.failAfterWrite = false; int writes = api.writes.get();
            Job recovered = client.batch().v1().jobs().inNamespace("starexec").withName("build-job").get();
            assertNotNull(KubernetesBuildLaunchFence.preventStart(client, "starexec", identity(), recovered));
            assertEquals(writes, api.writes.get());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"uid", "generation", "version", "marker", "enabled"})
    void anInvalidAcknowledgementCannotConserveNonExecution(String changed) throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client()) {
            api.invalidReply = changed;
            assertThrows(IllegalStateException.class,
                    () -> KubernetesBuildLaunchFence.preventStart(client, "starexec", identity(), initialJob()));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"version", "page", "blank-page", "remaining", "pod"})
    void anUnknownOrNonemptyCensusPreventsTheFence(String changed) throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client()) {
            api.invalidCensus = changed;
            assertThrows(IllegalStateException.class,
                    () -> KubernetesBuildLaunchFence.preventStart(client, "starexec", identity(), initialJob()));
            assertEquals(0, api.writes.get());
        }
    }

    @Test void aPodAppearingAfterTheFencePreventsNonExecutionAcknowledgement() throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client()) {
            api.invalidCensus = "pod"; api.lateCensus = true;
            assertThrows(IllegalStateException.class,
                    () -> KubernetesBuildLaunchFence.preventStart(client, "starexec", identity(), initialJob()));
            assertTrue(KubernetesBuildLaunchFence.isFenced(api.current)); assertEquals(1, api.writes.get());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"generation", "unknown-generation", "version", "deletion", "enabled", "manual", "managed-by", "typed-managed-by",
            "selector", "uid-selector", "conflicting-uids", "template", "start", "completion", "active", "ready", "failed", "succeeded", "terminating", "condition", "uncounted"})
    void onlyAnInitialSuspendedControllerCanReachTheApi(String changed) {
        Job job = initialJob();
        switch (changed) {
            case "generation" -> job.getMetadata().setGeneration(2L);
            case "unknown-generation" -> job.getMetadata().setGeneration(null);
            case "version" -> job.getMetadata().setResourceVersion(null);
            case "deletion" -> job.getMetadata().setDeletionTimestamp("2026-01-01T00:00:00Z");
            case "enabled" -> job.getSpec().setSuspend(false);
            case "manual" -> job.getSpec().setManualSelector(true);
            case "managed-by" -> job.getSpec().setAdditionalProperty("managedBy", "other-controller");
            case "typed-managed-by" -> job.getSpec().setManagedBy("other-controller");
            case "selector" -> job.getSpec().setSelector(null);
            case "uid-selector" -> job.getSpec().getSelector().setMatchLabels(Map.of("app", "builder"));
            case "conflicting-uids" -> {
                Map<String, String> labels = Map.of("batch.kubernetes.io/controller-uid", "job-uid", "controller-uid", "another-uid");
                job.getSpec().getSelector().setMatchLabels(labels);
                job.getSpec().getTemplate().getMetadata().setLabels(labels);
            }
            case "template" -> job.getSpec().getTemplate().getMetadata().setLabels(Map.of());
            case "start" -> job.setStatus(new JobStatusBuilder().withStartTime("2026-01-01T00:00:00Z").build());
            case "completion" -> job.setStatus(new JobStatusBuilder().withCompletionTime("2026-01-01T00:00:00Z").build());
            case "active" -> job.setStatus(new JobStatusBuilder().withActive(1).build());
            case "ready" -> job.setStatus(new JobStatusBuilder().withReady(1).build());
            case "failed" -> job.setStatus(new JobStatusBuilder().withFailed(1).build());
            case "succeeded" -> job.setStatus(new JobStatusBuilder().withSucceeded(1).build());
            case "terminating" -> job.setStatus(new JobStatusBuilder().withTerminating(1).build());
            case "condition" -> job.setStatus(new JobStatusBuilder().addNewCondition().withType("Complete").withStatus("True").endCondition().build());
            case "uncounted" -> job.setStatus(new JobStatusBuilder().withNewUncountedTerminatedPods().withSucceeded("pod-uid").endUncountedTerminatedPods().build());
            default -> throw new AssertionError(changed);
        }
        KubernetesClient client = mock(KubernetesClient.class);
        assertThrows(IllegalStateException.class, () -> KubernetesBuildLaunchFence.preventStart(client, "starexec", identity(), job));
        verifyNoInteractions(client);
    }

    @ParameterizedTest @ValueSource(strings = {"legacy-selector", "suspended-condition", "zero-counts"})
    void supportedInitialControllerObservationsRemainEligible(String shape) {
        Job job = initialJob();
        switch (shape) {
            case "legacy-selector" -> {
                Map<String, String> labels = Map.of("controller-uid", "job-uid");
                job.getSpec().getSelector().setMatchLabels(labels); job.getSpec().getTemplate().getMetadata().setLabels(labels);
            }
            case "suspended-condition" -> job.setStatus(new JobStatusBuilder().addNewCondition().withType("Suspended").withStatus("True").endCondition().build());
            case "zero-counts" -> job.setStatus(new JobStatusBuilder().withActive(0).withSucceeded(0).withFailed(0).withReady(0).withTerminating(0).build());
            default -> throw new AssertionError(shape);
        }
        assertEquals(job.getSpec().getSelector().getMatchLabels(), KubernetesBuildLaunchFence.validate("starexec", identity(), job));
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "17"})
    void anIncompleteRecoveredFenceDoesNotAuthorizeNonExecution(String marker) throws Exception {
        try (Api api = new Api(); KubernetesClient client = api.client()) {
            Job job = initialJob(); job.getMetadata().setAnnotations(Map.of(KubernetesBuildLaunchFence.ANNOTATION, marker));
            assertThrows(IllegalStateException.class, () -> KubernetesBuildLaunchFence.preventStart(client, "starexec", identity(), job));
            assertEquals(0, api.writes.get());
        }
    }
}
