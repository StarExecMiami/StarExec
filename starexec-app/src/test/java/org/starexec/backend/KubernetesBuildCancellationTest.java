package org.starexec.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.to.SolverBuildStopReceipt;

class KubernetesBuildCancellationTest {
    private static final String REQUEST = "starexec.org/build-cancel-request";
    private static final String ENGINE = "kubernetes:starexec:7:job-uid";
    private final UUID token = UUID.randomUUID();
    private final KubernetesNativeBackend backend = new KubernetesNativeBackend();

    private static Job runningJob() {
        Job job = KubernetesBuildLaunchFenceTest.initialJob();
        job.getMetadata().setGeneration(2L); job.getSpec().setSuspend(false);
        job.getSpec().getTemplate().getMetadata().setFinalizers(List.of(KubernetesBuildPodRetention.FINALIZER));
        return job;
    }

    private MockedStatic<SolverBuildArtifacts> catalog() throws Exception {
        return catalog(new AtomicReference<>());
    }

    private MockedStatic<SolverBuildArtifacts> catalog(AtomicReference<SolverBuildArtifacts.StoredStopReceipt> stored) throws Exception {
        var dao = mockStatic(SolverBuildArtifacts.class);
        var artifact = new SolverBuildArtifacts.Artifact(5, 42, 1, 9, 9, 10, 1, "/inputs/source", "/outputs/build",
                null, null, token, ENGINE, null, "PLANNED");
        dao.when(() -> SolverBuildArtifacts.find(42, 1)).thenReturn(Optional.of(artifact));
        dao.when(() -> SolverBuildArtifacts.findStopReceipt(42, 1)).thenAnswer(call -> Optional.ofNullable(stored.get()));
        dao.when(() -> SolverBuildArtifacts.recordStopReceipt(eq(42), eq(1), eq(token), eq(ENGINE), any())).thenAnswer(call -> {
            stored.compareAndSet(null, new SolverBuildArtifacts.StoredStopReceipt(call.getArgument(4), Instant.now())); return true;
        });
        dao.when(() -> SolverBuildArtifacts.recordCancelled(eq(42), eq(1), eq(token), eq(ENGINE), any())).thenAnswer(call -> {
            stored.compareAndSet(null, new SolverBuildArtifacts.StoredStopReceipt(call.getArgument(4), Instant.now())); return true;
        });
        return dao;
    }

    @SuppressWarnings("unchecked") private <T> T field(String name) throws Exception {
        var field = KubernetesNativeBackend.class.getDeclaredField(name); field.setAccessible(true); return (T) field.get(backend);
    }

    private Object call(String name, Class<?>[] types, Object... args) throws Exception {
        var method = KubernetesNativeBackend.class.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(backend, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw (Error) failure.getCause();
        }
    }

    private static KubernetesBuildStopObserver.Identity identity() {
        return KubernetesBuildStopObserver.identify("starexec", runningJob());
    }

    private static Pod retainedPod() {
        return new PodBuilder(KubernetesBuildStopObserverTest.finishedPod()).editMetadata().withResourceVersion("51")
                .withFinalizers(KubernetesBuildPodRetention.FINALIZER).endMetadata().build();
    }

    private static PodList listing(Pod pod) {
        return new PodListBuilder().withNewMetadata().withResourceVersion("41").endMetadata().withItems(pod).build();
    }

    private static Job acknowledgedJob() {
        return new JobBuilder(runningJob()).editMetadata().withGeneration(3L).withResourceVersion("18")
                .addToAnnotations(REQUEST, "17").endMetadata().editSpec().withActiveDeadlineSeconds(1L).endSpec().build();
    }

    private static void finish(KubernetesBuildLaunchFenceTest.Api api) {
        api.current = new JobBuilder(api.current).editMetadata().withResourceVersion("19").endMetadata()
                .withStatus(KubernetesBuildStopObserverTest.finishedJob().getStatus()).build();
        api.current.getStatus().getConditions().get(0).setType("Failed");
        api.current.getStatus().getConditions().get(0).setReason("DeadlineExceeded");
        api.podItems = List.of(retainedPod());
    }

    private void prime(KubernetesBuildLaunchFenceTest.Api api, io.fabric8.kubernetes.client.KubernetesClient client) throws Exception {
        for (var entry : Map.of("namespace", (Object) "starexec", "kubernetesClient", client).entrySet()) {
            var field = KubernetesNativeBackend.class.getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(backend, entry.getValue());
        }
        var remember = KubernetesNativeBackend.class.getDeclaredMethod("rememberBuild", Job.class); remember.setAccessible(true);
        try { remember.invoke(backend, api.current); }
        catch (InvocationTargetException failure) { throw (Exception) failure.getCause(); }
        this.<Set<Integer>>field("jobsHoldingSlot").add(7); this.<AtomicInteger>field("activeJobCount").set(1);
        this.<Map<Integer, String>>field("execIdToJobName").put(7, "build-job");
        this.<Map<Integer, String>>field("execIdToJobUid").put(7, "job-uid");
        this.<Map<Integer, Integer>>field("execIdToPairId").put(7, 42);
        this.<Map<Integer, Integer>>field("execIdToAttemptNo").put(7, 1);
    }

    @Test void requestingAnActiveBuildStopSetsAnExactVersionDeadlineWithoutDeletionOrRelease() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog()) {
            api.current = runningJob(); prime(api, client);
            backend.requestStop(7);
            assertEquals(1, api.writes.get());
            assertEquals(List.of("17"), api.writeVersions);
            assertEquals(1L, api.current.getSpec().getActiveDeadlineSeconds());
            assertEquals("17", api.current.getMetadata().getAnnotations().get(REQUEST));
            assertEquals(0, api.deletes.get()); assertFalse(api.deleted);
            assertEquals(1, this.<AtomicInteger>field("activeJobCount").get());
            dao.verify(() -> SolverBuildArtifacts.recordStopReceipt(anyInt(), anyInt(), any(), anyString(), any()), never());
            dao.verify(() -> SolverBuildArtifacts.recordCancelled(anyInt(), anyInt(), any(), anyString(), any()), never());
        }
    }

    @Test void firstTerminalObservationOfAnAcknowledgedStopConservesCancellation() throws Exception {
        Job job = runningJob(); job.getMetadata().setGeneration(3L); job.getMetadata().setResourceVersion("18");
        job.getMetadata().setAnnotations(Map.of(REQUEST, "17")); job.getSpec().setActiveDeadlineSeconds(1L);
        job.setStatus(KubernetesBuildStopObserverTest.finishedJob().getStatus());
        var pod = KubernetesBuildStopObserverTest.finishedPod();
        pod.getMetadata().setFinalizers(List.of(KubernetesBuildPodRetention.FINALIZER));
        var pods = new PodListBuilder().withNewMetadata().withResourceVersion("41").endMetadata().withItems(pod).build();
        try (var dao = catalog()) {
            var receipt = KubernetesBuildStopObserver.record("starexec",
                    KubernetesBuildStopObserver.identify("starexec", job), job, pods);
            assertTrue(KubernetesBuildStopObserver.isCancelled(receipt));
            dao.verify(() -> SolverBuildArtifacts.recordCancelled(42, 1, token, ENGINE, receipt));
            dao.verify(() -> SolverBuildArtifacts.recordStopReceipt(anyInt(), anyInt(), any(), anyString(), any()), never());
        }
    }

    @Test void requestChangesOnlyTheDeadlineAndItsMetadataMarker() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog()) {
            api.current = runningJob(); api.current.getSpec().setBackoffLimit(3);
            api.current.getSpec().setActiveDeadlineSeconds(600L);
            api.current.getMetadata().setAnnotations(Map.of("example.org/keep", "unchanged"));
            Job original = new JobBuilder(api.current).build();
            Job updated = KubernetesBuildCancellation.request(client, "starexec", identity(), original);
            var expected = new JobBuilder(original).editSpec().withActiveDeadlineSeconds(1L).endSpec().build();
            assertEquals(expected.getSpec(), updated.getSpec());
            assertEquals("unchanged", updated.getMetadata().getAnnotations().get("example.org/keep"));
            assertEquals(600L, original.getSpec().getActiveDeadlineSeconds());
            assertFalse(KubernetesBuildCancellation.isRequested(original));
            assertEquals(3L, updated.getMetadata().getGeneration()); assertEquals(2, api.censuses.get());
        }
    }

    @Test void anExistingOneSecondDeadlineNeedsOnlyAMetadataVersionChange() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog()) {
            api.current = runningJob(); api.current.getSpec().setActiveDeadlineSeconds(1L);
            Job updated = KubernetesBuildCancellation.request(client, "starexec", identity(), api.current);
            assertEquals(2L, updated.getMetadata().getGeneration()); assertEquals("18", updated.getMetadata().getResourceVersion());
            assertEquals("17", updated.getMetadata().getAnnotations().get(REQUEST));
        }
    }

    @Test void aStaleVersionIsNotRetriedAgainstTheNewerJob() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog()) {
            Job observed = runningJob(); api.current = new JobBuilder(observed).editMetadata().withResourceVersion("18").endMetadata().build();
            var failure = assertThrows(KubernetesClientException.class,
                    () -> KubernetesBuildCancellation.request(client, "starexec", identity(), observed));
            assertEquals(409, failure.getCode()); assertEquals(1, api.writes.get());
            assertEquals(List.of("17"), api.writeVersions); assertFalse(KubernetesBuildCancellation.isRequested(api.current));
            assertEquals(0, api.deletes.get());
        }
    }

    @Test void aLostReplyCanRecoverTheSameAcknowledgedMarkerWithoutAnotherWrite() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog()) {
            api.current = runningJob(); api.failAfterWrite = true;
            var failure = assertThrows(KubernetesClientException.class, () -> KubernetesBuildCancellation.request(client, "starexec", identity(), api.current));
            assertEquals(409, failure.getCode()); assertEquals(List.of("17", "17"), api.writeVersions);
            api.failAfterWrite = false;
            Job recovered = client.batch().v1().jobs().inNamespace("starexec").withName("build-job").get();
            assertEquals("17", KubernetesBuildCancellation.request(client, "starexec", identity(), recovered)
                    .getMetadata().getAnnotations().get(REQUEST));
            assertEquals(2, api.writes.get()); assertEquals(List.of("17", "17"), api.writeVersions);
            assertEquals(0, api.deletes.get());
            dao.verify(() -> SolverBuildArtifacts.recordCancelled(anyInt(), anyInt(), any(), anyString(), any()), never());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"uid", "generation", "version", "marker", "suspended", "deadline"})
    void anInvalidUpdateReplyDoesNotAuthorizeCleanup(String fault) throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog()) {
            api.current = runningJob(); api.invalidReply = fault;
            assertThrows(IllegalStateException.class, () -> KubernetesBuildCancellation.request(client, "starexec", identity(), api.current));
            assertEquals(1, api.writes.get()); assertEquals(0, api.deletes.get());
            dao.verify(() -> SolverBuildArtifacts.recordCancelled(anyInt(), anyInt(), any(), anyString(), any()), never());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"version", "page", "blank-page", "remaining"})
    void anIncompleteCensusBeforeTheRequestPreventsWriting(String fault) throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog()) {
            api.current = runningJob(); api.invalidCensus = fault;
            assertThrows(IllegalStateException.class, () -> KubernetesBuildCancellation.request(client, "starexec", identity(), api.current));
            assertEquals(0, api.writes.get()); assertEquals(0, api.deletes.get());
        }
    }

    @Test void anIncompleteCensusAfterTheRequestStillCannotAuthorizeAReceiptOrCleanup() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog()) {
            api.current = runningJob(); api.invalidCensus = "page"; api.lateCensus = true; prime(api, client);
            backend.requestStop(7);
            assertEquals(1, api.writes.get()); assertEquals(0, api.deletes.get());
            assertEquals(1, this.<AtomicInteger>field("activeJobCount").get());
            dao.verify(() -> SolverBuildArtifacts.recordCancelled(anyInt(), anyInt(), any(), anyString(), any()), never());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"namespace", "uid", "generation", "unknown-generation", "version", "deletion",
            "suspended", "fenced", "manual", "managed-by", "retention", "selector", "template", "terminal"})
    void anIneligibleJobIsNotModified(String fault) throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog()) {
            api.current = runningJob();
            switch (fault) {
                case "namespace" -> api.current.getMetadata().setNamespace("another-namespace");
                case "uid" -> api.current.getMetadata().setUid("another-uid");
                case "generation" -> api.current.getMetadata().setGeneration(1L);
                case "unknown-generation" -> api.current.getMetadata().setGeneration(null);
                case "version" -> api.current.getMetadata().setResourceVersion(" ");
                case "deletion" -> api.current.getMetadata().setDeletionTimestamp("2026-01-01T00:00:00Z");
                case "suspended" -> api.current.getSpec().setSuspend(true);
                case "fenced" -> api.current.getMetadata().setAnnotations(Map.of(KubernetesBuildLaunchFence.ANNOTATION, "16"));
                case "manual" -> api.current.getSpec().setManualSelector(true);
                case "managed-by" -> api.current.getSpec().getAdditionalProperties().put("managedBy", "example.org/controller");
                case "retention" -> api.current.getSpec().getTemplate().getMetadata().setFinalizers(List.of());
                case "selector" -> api.current.getSpec().getSelector().setMatchLabels(Map.of("controller-uid", "different-uid"));
                case "template" -> api.current.getSpec().getTemplate().getMetadata().setLabels(Map.of());
                case "terminal" -> api.current.setStatus(KubernetesBuildStopObserverTest.finishedJob().getStatus());
                default -> throw new AssertionError(fault);
            }
            assertThrows(IllegalStateException.class, () -> KubernetesBuildCancellation.request(client, "starexec", identity(), api.current));
            assertEquals(0, api.writes.get()); assertEquals(0, api.deletes.get());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"namespace", "uid", "owner", "name", "duplicate", "retention"})
    void anUnownedOrUnretainedPodPreventsTheRequest(String fault) throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog()) {
            api.current = runningJob(); Pod pod = retainedPod();
            switch (fault) {
                case "namespace" -> pod.getMetadata().setNamespace("different-namespace");
                case "uid" -> pod.getMetadata().setUid(" ");
                case "owner" -> pod.getMetadata().getOwnerReferences().get(0).setUid("different-owner");
                case "name" -> pod.getMetadata().setName(null);
                case "duplicate" -> { }
                case "retention" -> pod.getMetadata().setFinalizers(List.of());
                default -> throw new AssertionError(fault);
            }
            api.podItems = fault.equals("duplicate") ? List.of(pod, pod) : List.of(pod);
            assertThrows(IllegalStateException.class, () -> KubernetesBuildCancellation.request(client, "starexec", identity(), api.current));
            assertEquals(0, api.writes.get()); assertEquals(0, api.deletes.get());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "token", "engine", "artifact"})
    void missingAdmissionNeverSendsAStopUpdate(String fault) throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog()) {
            api.current = runningJob();
            var artifact = new SolverBuildArtifacts.Artifact(fault.equals("artifact") ? 6 : 5, 42, 1, 9, 9, 10, 1,
                    "/inputs/source", "/outputs/build", null, null, fault.equals("token") ? null : token,
                    fault.equals("engine") ? "another-engine" : ENGINE, null, "PLANNED");
            dao.when(() -> SolverBuildArtifacts.find(42, 1)).thenReturn(fault.equals("missing") ? Optional.empty() : Optional.of(artifact));
            assertThrows(IllegalStateException.class, () -> KubernetesBuildCancellation.request(client, "starexec", identity(), api.current));
            assertEquals(0, api.writes.get()); assertEquals(0, api.deletes.get());
        }
    }

    @Test void aPassiveSafetyObservationCannotRequestCancellation() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog()) {
            api.current = runningJob(); prime(api, client);
            assertEquals(Backend.KillOutcome.UNPROVEN, call("observeExecutionSafety", new Class<?>[]{int.class}, 7));
            assertEquals(0, api.writes.get()); assertEquals(0, api.deletes.get());
            assertEquals(1, this.<AtomicInteger>field("activeJobCount").get());
        }
    }

    @Test void firstCancellationIsStoredBeforeFinalizerReleaseDeletionAndCapacityRelease() throws Exception {
        var stored = new AtomicReference<SolverBuildArtifacts.StoredStopReceipt>();
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog(stored)) {
            api.current = runningJob(); prime(api, client); backend.requestStop(7);
            assertNull(stored.get()); assertEquals(1, this.<AtomicInteger>field("activeJobCount").get());
            finish(api);
            api.deletionAllowed = () -> stored.get() != null && api.podItems.stream().noneMatch(p ->
                    p.getMetadata().getFinalizers().contains(KubernetesBuildPodRetention.FINALIZER));
            assertEquals(Backend.KillOutcome.CONFIRMED_SAFE, backend.killPairConfirmed(7));
            assertTrue(KubernetesBuildStopObserver.isCancelled(stored.get().receipt()));
            assertEquals("17", stored.get().receipt().facts().get("cancel_request_resource_version").getAsString());
            assertEquals(1, stored.get().receipt().facts().get("cancel_deadline_seconds").getAsInt());
            assertEquals("41", stored.get().receipt().facts().get("pod_list_resource_version").getAsString());
            assertEquals(List.of("job:update", "pod:release", "job:delete"), api.events);
            assertEquals(List.of("51"), api.podWriteVersions); assertEquals(List.of("19"), api.deleteVersions);
            assertTrue(api.deleted); assertTrue(api.podItems.isEmpty());
            assertEquals(0, this.<AtomicInteger>field("activeJobCount").get());
            assertTrue(this.<Map<?, ?>>field("buildJobs").isEmpty());
            dao.verify(() -> SolverBuildArtifacts.recordStopReceipt(anyInt(), anyInt(), any(), anyString(), any()), never());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"refused", "database", "lost-reply", "pod-conflict", "delete-conflict"})
    void aFailedStopStageKeepsCapacityAndRetriesWithTheSameReceipt(String fault) throws Exception {
        var stored = new AtomicReference<SolverBuildArtifacts.StoredStopReceipt>();
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog(stored)) {
            api.current = runningJob(); prime(api, client); backend.requestStop(7); finish(api);
            if (fault.equals("refused")) dao.when(() -> SolverBuildArtifacts.recordCancelled(eq(42), eq(1), eq(token), eq(ENGINE), any())).thenReturn(false);
            if (fault.equals("database")) dao.when(() -> SolverBuildArtifacts.recordCancelled(eq(42), eq(1), eq(token), eq(ENGINE), any())).thenThrow(new SQLException("synthetic storage failure"));
            if (fault.equals("lost-reply")) dao.when(() -> SolverBuildArtifacts.recordCancelled(eq(42), eq(1), eq(token), eq(ENGINE), any())).thenAnswer(call -> {
                stored.compareAndSet(null, new SolverBuildArtifacts.StoredStopReceipt(call.getArgument(4), Instant.now()));
                throw new SQLException("synthetic lost acknowledgement");
            });
            api.refusePodWrite = fault.equals("pod-conflict"); api.refuseDeletion = fault.equals("delete-conflict");
            assertEquals(Backend.KillOutcome.UNPROVEN, backend.killPairConfirmed(7));
            assertFalse(api.deleted); assertEquals(1, this.<AtomicInteger>field("activeJobCount").get());
            if (!fault.equals("delete-conflict")) assertEquals(0, api.deletes.get());
            if (List.of("refused", "database", "lost-reply").contains(fault)) assertEquals(0, api.podWrites.get());
            var previous = stored.get();
            dao.when(() -> SolverBuildArtifacts.recordCancelled(eq(42), eq(1), eq(token), eq(ENGINE), any())).thenAnswer(call -> {
                stored.compareAndSet(null, new SolverBuildArtifacts.StoredStopReceipt(call.getArgument(4), Instant.now())); return true;
            });
            api.refusePodWrite = false; api.refuseDeletion = false;
            assertEquals(Backend.KillOutcome.CONFIRMED_SAFE, backend.killPairConfirmed(7));
            if (previous != null) assertSame(previous, stored.get());
            assertEquals(1, api.writes.get()); assertTrue(api.deleted);
            assertEquals(0, this.<AtomicInteger>field("activeJobCount").get());
        }
    }

    @Test void theBoundedRemovalWaitAdvancesAKnownBuildAfterControllerTermination() throws Exception {
        var stored = new AtomicReference<SolverBuildArtifacts.StoredStopReceipt>();
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog(stored)) {
            api.current = runningJob(); prime(api, client); backend.requestStop(7); finish(api);
            var poll = KubernetesNativeBackend.class.getDeclaredField("killConfirmPollMillis"); poll.setAccessible(true); poll.setLong(backend, 1L);
            assertEquals(true, call("awaitKubernetesJobGone", new Class<?>[]{String.class, long.class, ExecutionRef.class},
                    "build-job", System.nanoTime() + 2_000_000_000L, identity().execution()));
            assertNotNull(stored.get()); assertTrue(api.deleted);
            assertEquals(1, this.<AtomicInteger>field("activeJobCount").get(), "removal alone must not release accounting");
            assertEquals(Backend.KillOutcome.CONFIRMED_SAFE, backend.killPairConfirmed(7));
            assertEquals(0, this.<AtomicInteger>field("activeJobCount").get());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"marker-blank", "marker-current", "deadline", "version", "generation", "suspended",
            "census-version", "census-page", "census-remaining", "retention", "running-pod", "running-container", "container-id"})
    void incompleteFirstCancellationEvidenceNeverReachesTheCatalog(String fault) throws Exception {
        Job job = acknowledgedJob(); job.setStatus(KubernetesBuildStopObserverTest.finishedJob().getStatus());
        PodList pods = listing(retainedPod()); Pod pod = pods.getItems().get(0);
        switch (fault) {
            case "marker-blank" -> job.getMetadata().setAnnotations(Map.of(REQUEST, " "));
            case "marker-current" -> job.getMetadata().setAnnotations(Map.of(REQUEST, "18"));
            case "deadline" -> job.getSpec().setActiveDeadlineSeconds(2L);
            case "version" -> job.getMetadata().setResourceVersion(null);
            case "generation" -> job.getMetadata().setGeneration(1L);
            case "suspended" -> job.getSpec().setSuspend(true);
            case "census-version" -> pods.getMetadata().setResourceVersion(null);
            case "census-page" -> pods.getMetadata().setContinue("next-page");
            case "census-remaining" -> pods.getMetadata().setRemainingItemCount(1L);
            case "retention" -> pod.getMetadata().setFinalizers(List.of());
            case "running-pod" -> pod.getStatus().setPhase("Running");
            case "running-container" -> pod.getStatus().getContainerStatuses().get(0).getState().setTerminated(null);
            case "container-id" -> pod.getStatus().getContainerStatuses().get(0).setContainerID(null);
            default -> throw new AssertionError(fault);
        }
        try (var dao = catalog()) {
            assertThrows(IllegalStateException.class, () -> KubernetesBuildStopObserver.record("starexec", identity(), job, pods));
            dao.verify(() -> SolverBuildArtifacts.recordCancelled(anyInt(), anyInt(), any(), anyString(), any()), never());
            dao.verify(() -> SolverBuildArtifacts.recordStopReceipt(anyInt(), anyInt(), any(), anyString(), any()), never());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"request-blank", "request-current", "request-number", "request-missing", "deadline-string",
            "deadline-other", "deadline-fraction", "deadline-missing", "census-blank", "census-boolean", "disposition-missing"})
    void malformedConservedRequestProvenanceCannotReleasePodEvidence(String fault) throws Exception {
        var stored = new AtomicReference<SolverBuildArtifacts.StoredStopReceipt>();
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog(stored)) {
            api.current = acknowledgedJob(); finish(api);
            var receipt = KubernetesBuildStopObserver.record("starexec", identity(), api.current, listing(retainedPod()));
            var facts = receipt.facts();
            switch (fault) {
                case "request-blank" -> facts.addProperty("cancel_request_resource_version", " ");
                case "request-current" -> facts.addProperty("cancel_request_resource_version", "19");
                case "request-number" -> facts.addProperty("cancel_request_resource_version", 17);
                case "request-missing" -> facts.remove("cancel_request_resource_version");
                case "deadline-string" -> facts.addProperty("cancel_deadline_seconds", "1");
                case "deadline-other" -> facts.addProperty("cancel_deadline_seconds", 2);
                case "deadline-fraction" -> facts.addProperty("cancel_deadline_seconds", 1.5);
                case "deadline-missing" -> facts.remove("cancel_deadline_seconds");
                case "census-blank" -> facts.addProperty("pod_list_resource_version", " ");
                case "census-boolean" -> facts.addProperty("pod_list_resource_version", true);
                case "disposition-missing" -> facts.remove("disposition");
                default -> throw new AssertionError(fault);
            }
            var invalid = SolverBuildStopReceipt.observed("kubernetes", 5, 42, 1, ENGINE, "job-uid", Instant.now(), facts);
            stored.set(new SolverBuildArtifacts.StoredStopReceipt(invalid, Instant.now()));
            assertNull(KubernetesBuildStopObserver.conservedReceipt("starexec", identity()));
            assertThrows(IllegalStateException.class, () -> KubernetesBuildPodRetention.release(client, "starexec", identity(), api.current));
            assertEquals(0, api.podWrites.get()); assertEquals(0, api.deletes.get());
        }
    }

    @Test void acknowledgedCancellationReplaysPreserveExactReceiptBytesAndTimestamps() throws Exception {
        var stored = new AtomicReference<SolverBuildArtifacts.StoredStopReceipt>();
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog(stored)) {
            api.current = acknowledgedJob(); finish(api);
            var first = KubernetesBuildStopObserver.record("starexec", identity(), api.current, listing(retainedPod()));
            var original = stored.get();
            api.current.getMetadata().setResourceVersion("20");
            var second = KubernetesBuildStopObserver.record("starexec", identity(), api.current, listing(retainedPod()));
            assertEquals(first.payload(), second.payload()); assertEquals(first.sha256(), second.sha256());
            assertSame(original, stored.get());
            api.current.getMetadata().setAnnotations(Map.of(REQUEST, "different-request"));
            assertThrows(IllegalStateException.class, () -> KubernetesBuildStopObserver.record("starexec", identity(), api.current, listing(retainedPod())));
            assertSame(original, stored.get());
        }
    }

    @Test void anEmptyCensusCannotReinterpretANormalReceiptAfterACancellationMarker() throws Exception {
        var stored = new AtomicReference<SolverBuildArtifacts.StoredStopReceipt>();
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog(stored)) {
            Job normal = runningJob(); normal.setStatus(KubernetesBuildStopObserverTest.finishedJob().getStatus());
            var original = KubernetesBuildStopObserver.record("starexec", identity(), normal, listing(retainedPod()));
            assertFalse(KubernetesBuildStopObserver.isCancelled(original));
            api.current = acknowledgedJob(); api.current.setStatus(normal.getStatus()); prime(api, client);
            backend.requestStop(7);
            assertEquals(0, api.deletes.get()); assertEquals(0, api.podWrites.get());
            assertEquals(1, this.<AtomicInteger>field("activeJobCount").get());
            assertEquals(original.payload(), stored.get().receipt().payload());
            dao.verify(() -> SolverBuildArtifacts.recordCancelled(anyInt(), anyInt(), any(), anyString(), any()), never());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"onJobComplete", "onJobFailed", "onJobStuckPending"})
    void aFirstCancelledReceiptBypassesScientificResultIngestion(String callback) throws Exception {
        var stored = new AtomicReference<SolverBuildArtifacts.StoredStopReceipt>();
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog(stored);
             var pairs = mockStatic(JobPairs.class)) {
            api.current = runningJob(); prime(api, client); backend.requestStop(7); finish(api);
            var type = Class.forName("org.starexec.backend.KubernetesNativeBackend$KubernetesJobCompletionCallback");
            var constructor = type.getDeclaredConstructor(KubernetesNativeBackend.class); constructor.setAccessible(true);
            boolean completion = callback.equals("onJobComplete");
            var method = completion ? type.getDeclaredMethod(callback, ExecutionRef.class)
                    : type.getDeclaredMethod(callback, ExecutionRef.class, String.class);
            method.setAccessible(true);
            var execution = identity().execution();
            assertEquals(true, completion ? method.invoke(constructor.newInstance(backend), execution)
                    : method.invoke(constructor.newInstance(backend), execution, "synthetic controller stop"));
            assertNotNull(stored.get()); assertTrue(KubernetesBuildStopObserver.isCancelled(stored.get().receipt()));
            pairs.verifyNoInteractions(); assertTrue(api.deleted);
            assertEquals(0, this.<AtomicInteger>field("activeJobCount").get());
            assertTrue(this.<Map<?, ?>>field("buildJobs").isEmpty());
        }
    }

    @Test void aReplacementOwnerDuringKillConfirmationKeepsItsTrackingAndCapacity() throws Exception {
        var stored = new AtomicReference<SolverBuildArtifacts.StoredStopReceipt>();
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog(stored)) {
            api.current = runningJob(); prime(api, client); backend.requestStop(7); finish(api);
            Map<Integer, String> names = field("execIdToJobName"); Map<Integer, String> uids = field("execIdToJobUid");
            var replaced = new AtomicBoolean();
            api.onCensus = () -> {
                if (api.deleted && replaced.compareAndSet(false, true)) {
                    names.put(7, "replacement-job"); uids.put(7, "replacement-uid");
                }
            };
            assertEquals(Backend.KillOutcome.UNPROVEN, backend.killPairConfirmed(7));
            assertTrue(replaced.get()); assertEquals("replacement-job", names.get(7));
            assertEquals("replacement-uid", uids.get(7));
            assertEquals(1, this.<AtomicInteger>field("activeJobCount").get());
        }
    }
}
