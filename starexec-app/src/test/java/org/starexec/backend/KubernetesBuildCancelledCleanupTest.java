package org.starexec.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.to.SolverBuildStopReceipt;
import org.starexec.data.to.Status.StatusCode;

class KubernetesBuildCancelledCleanupTest {
    private final KubernetesNativeBackend backend = new KubernetesNativeBackend();
    private final ExecutionRef execution = new ExecutionRef(7, "build-job", "job-uid");
    private final UUID token = UUID.randomUUID();
    private final String engine = "kubernetes:starexec:7:job-uid";
    @TempDir Path files;

    private static Job job() {
        Job job = KubernetesBuildLaunchFenceTest.initialJob();
        job.getMetadata().setGeneration(2L); job.getSpec().setSuspend(false);
        job.setStatus(KubernetesBuildStopObserverTest.finishedJob().getStatus());
        job.getSpec().getTemplate().getMetadata().setFinalizers(List.of(KubernetesBuildPodRetention.FINALIZER));
        return job;
    }

    private static KubernetesBuildStopObserver.Identity identity() {
        return KubernetesBuildStopObserver.identify("starexec", job());
    }

    private SolverBuildStopReceipt cancelled() {
        JsonObject facts = new JsonObject(); facts.addProperty("disposition", "cancelled");
        facts.addProperty("namespace", "starexec"); facts.addProperty("job_name", "build-job");
        facts.addProperty("job_uid", "job-uid"); facts.addProperty("job_resource_version", "17");
        facts.addProperty("terminal_condition", "Complete");
        JsonArray pods = new JsonArray(); pods.add(KubernetesBuildStopObserver.observePod("starexec", identity(),
                KubernetesBuildStopObserverTest.finishedPod())); facts.add("pods", pods);
        return SolverBuildStopReceipt.observed("kubernetes", 5, 42, 1, engine, "job-uid", Instant.now(), facts);
    }

    private MockedStatic<SolverBuildArtifacts> catalog() throws Exception {
        var receipt = cancelled(); var dao = mockStatic(SolverBuildArtifacts.class);
        var artifact = new SolverBuildArtifacts.Artifact(5, 42, 1, 9, 9, 10, 1, "/inputs/source", "/outputs/build",
                null, null, token, engine, Instant.now(), "REJECTED");
        dao.when(() -> SolverBuildArtifacts.find(42, 1)).thenReturn(Optional.of(artifact));
        dao.when(() -> SolverBuildArtifacts.findCurrent(42)).thenReturn(Optional.of(artifact));
        dao.when(() -> SolverBuildArtifacts.findStopReceipt(42, 1)).thenReturn(Optional.of(
                new SolverBuildArtifacts.StoredStopReceipt(receipt, Instant.now())));
        dao.when(() -> SolverBuildArtifacts.recordStopReceipt(eq(42), eq(1), eq(token), eq(engine), any())).thenReturn(true);
        dao.when(() -> SolverBuildArtifacts.recordCancelled(eq(42), eq(1), eq(token), eq(engine), any())).thenReturn(true);
        return dao;
    }

    @SuppressWarnings("unchecked") private <T> T field(String name) throws Exception {
        var field = KubernetesNativeBackend.class.getDeclaredField(name); field.setAccessible(true); return (T) field.get(backend);
    }

    private Object call(String name, Class<?>[] types, Object... values) throws Exception {
        var method = KubernetesNativeBackend.class.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(backend, values); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw (Error) failure.getCause();
        }
    }

    private void configure(KubernetesClient client) throws Exception {
        for (var entry : Map.of("namespace", (Object) "starexec", "kubernetesClient", client).entrySet()) {
            var field = KubernetesNativeBackend.class.getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(backend, entry.getValue());
        }
    }

    private void prime(KubernetesClient client, Job job) throws Exception {
        configure(client);
        call("rememberBuild", new Class<?>[]{Job.class}, job);
        this.<Set<Integer>>field("jobsHoldingSlot").add(7); this.<AtomicInteger>field("activeJobCount").set(1);
        this.<Map<Integer, String>>field("execIdToJobName").put(7, "build-job");
        this.<Map<Integer, String>>field("execIdToJobUid").put(7, "job-uid");
        this.<Map<Integer, Integer>>field("execIdToPairId").put(7, 42);
        this.<Map<Integer, Integer>>field("execIdToAttemptNo").put(7, 1);
        this.<Map<Integer, Path>>field("execIdToOutputDir").put(7, files);
    }

    private Object callback() throws Exception {
        return callback("onJobComplete");
    }

    private Object callback(String name) throws Exception {
        Class<?> type = Class.forName("org.starexec.backend.KubernetesNativeBackend$KubernetesJobCompletionCallback");
        var constructor = type.getDeclaredConstructor(KubernetesNativeBackend.class); constructor.setAccessible(true);
        boolean completion = name.equals("onJobComplete");
        var method = completion ? type.getDeclaredMethod(name, ExecutionRef.class)
                : type.getDeclaredMethod(name, ExecutionRef.class, String.class);
        method.setAccessible(true);
        try { return completion ? method.invoke(constructor.newInstance(backend), execution)
                : method.invoke(constructor.newInstance(backend), execution, "synthetic lifecycle event"); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw (Error) failure.getCause();
        }
    }

    @Test void aCancelledBuildCannotWriteAScientificPairResult() throws Exception {
        int status = StatusCode.ERROR_RUNSCRIPT.getVal();
        Files.writeString(files.resolve("status.json"), "{\"status\":" + status + ",\"stageNumber\":0}");
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client();
             var dao = catalog(); var pairs = mockStatic(JobPairs.class)) {
            api.current = job(); prime(client, api.current);
            var constructor = JobPairs.PairStatusLookupResult.class.getDeclaredConstructor(JobPairs.PairStatusLookupState.class, int.class);
            constructor.setAccessible(true);
            pairs.when(() -> JobPairs.getPairStatusLookup(42)).thenReturn(constructor.newInstance(
                    JobPairs.PairStatusLookupState.FOUND, StatusCode.STATUS_RUNNING.getVal()));
            Object handled = callback();
            pairs.verify(() -> JobPairs.setPairLevelStatusResult(42, status, status, 1), never());
            assertEquals(true, handled); pairs.verifyNoInteractions();
            assertTrue(api.deleted); assertEquals(0, this.<AtomicInteger>field("activeJobCount").get());
            assertTrue(this.<Map<?, ?>>field("buildJobs").isEmpty());
        }
    }

    @Test void aNewOwnerDuringTheSafetyCensusKeepsItsTrackingAndCapacity() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var dao = catalog()) {
            api.current = job(); prime(client, api.current);
            assertEquals(true, call("conserveBuildStop", new Class<?>[]{KubernetesBuildStopObserver.Identity.class, Job.class}, identity(), api.current));
            Map<Integer, String> names = field("execIdToJobName"); Map<Integer, String> uids = field("execIdToJobUid");
            AtomicBoolean changed = new AtomicBoolean();
            api.onCensus = () -> {
                if (changed.compareAndSet(false, true)) {
                    names.put(7, "replacement-job"); uids.put(7, "replacement-uid");
                    api.current = new JobBuilder(api.current).editMetadata().withName("replacement-job")
                            .withUid("replacement-uid").endMetadata().build();
                }
            };
            call("releaseAccountingIfSafe", new Class<?>[]{ExecutionRef.class, String.class}, execution, "old callback");
            assertTrue(changed.get()); assertEquals("replacement-job", names.get(7));
            assertEquals("replacement-uid", uids.get(7));
            assertEquals(1, this.<AtomicInteger>field("activeJobCount").get());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"onJobComplete", "onJobFailed", "onJobStuckPending"})
    void cancelledCallbacksIgnoreScientificFilesAndLeaveInterventionToItsOwner(String name) throws Exception {
        Files.writeString(files.resolve("status.json"), "invalid untrusted scientific output");
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client();
             var dao = catalog(); var pairs = mockStatic(JobPairs.class)) {
            api.current = job(); prime(client, api.current);
            assertEquals(true, callback(name)); pairs.verifyNoInteractions();
            dao.verify(() -> SolverBuildArtifacts.recordCancelled(eq(42), eq(1), eq(token), eq(engine), any()), atLeastOnce());
            dao.verify(() -> SolverBuildArtifacts.recordStopReceipt(anyInt(), anyInt(), any(), anyString(), any()), never());
            assertTrue(api.deleted); assertEquals(0, this.<AtomicInteger>field("activeJobCount").get());
            assertTrue(this.<Map<?, ?>>field("buildJobs").isEmpty());
            assertTrue(this.<Set<?>>field("killedExecutions").isEmpty(), "handled callbacks must not accumulate stop tombstones");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void conservedCancellationSurvivesAJobThatWasAlreadyRemoved(boolean removed) throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client();
             var dao = catalog(); var pairs = mockStatic(JobPairs.class)) {
            api.current = job(); prime(client, api.current); api.deleted = removed;
            assertEquals(true, callback()); pairs.verifyNoInteractions();
            assertEquals(0, this.<AtomicInteger>field("activeJobCount").get());
            assertTrue(this.<Map<?, ?>>field("buildJobs").isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"refused", "database", "lost-reply"})
    void anUnacknowledgedCancellationRetainsItsResourcesAndCanRetry(String fault) throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client();
             var dao = catalog(); var pairs = mockStatic(JobPairs.class)) {
            api.current = job(); prime(client, api.current);
            dao.when(() -> SolverBuildArtifacts.recordCancelled(eq(42), eq(1), eq(token), eq(engine), any()))
                    .thenAnswer(call -> { if (fault.equals("refused")) return false; throw new java.sql.SQLException(fault); });
            assertEquals(false, callback()); pairs.verifyNoInteractions();
            assertFalse(api.deleted); assertEquals(0, api.deletes.get());
            assertEquals(1, this.<AtomicInteger>field("activeJobCount").get());
            assertFalse(this.<Map<?, ?>>field("buildJobs").isEmpty());
            dao.when(() -> SolverBuildArtifacts.recordCancelled(eq(42), eq(1), eq(token), eq(engine), any())).thenReturn(true);
            assertEquals(true, callback()); pairs.verifyNoInteractions();
            assertTrue(this.<Map<?, ?>>field("buildJobs").isEmpty());
        }
    }

    @Test void deletionFailureRetainsCapacityWithoutInventingAnInterventionResult() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client();
             var dao = catalog(); var pairs = mockStatic(JobPairs.class)) {
            api.current = job(); prime(client, api.current); api.refuseDeletion = true;
            assertEquals(false, callback()); pairs.verifyNoInteractions();
            assertEquals(1, this.<AtomicInteger>field("activeJobCount").get()); assertFalse(api.deleted);
            api.refuseDeletion = false;
            assertEquals(true, callback()); pairs.verifyNoInteractions();
            assertEquals(0, this.<AtomicInteger>field("activeJobCount").get());
            assertTrue(this.<Map<?, ?>>field("buildJobs").isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"unknown", "number", "null", "array", "missing"})
    void anUnknownOrMissingOutcomeCannotBeDowngradedToScientificCompletion(String fault) throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client();
             var dao = catalog(); var pairs = mockStatic(JobPairs.class)) {
            api.current = job(); prime(client, api.current);
            if (fault.equals("missing")) dao.when(() -> SolverBuildArtifacts.findStopReceipt(42, 1)).thenReturn(Optional.empty());
            else {
                JsonObject facts = cancelled().facts();
                switch (fault) {
                    case "unknown" -> facts.addProperty("disposition", "unknown");
                    case "number" -> facts.addProperty("disposition", 1);
                    case "null" -> facts.add("disposition", com.google.gson.JsonNull.INSTANCE);
                    case "array" -> facts.add("disposition", new JsonArray());
                    default -> throw new AssertionError(fault);
                }
                var envelope = com.google.gson.JsonParser.parseString(cancelled().payload()).getAsJsonObject();
                envelope.add("facts", facts);
                String payload = new com.google.gson.GsonBuilder().serializeNulls().create().toJson(envelope);
                String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var bad = SolverBuildStopReceipt.restored(payload, digest);
                dao.when(() -> SolverBuildArtifacts.findStopReceipt(42, 1)).thenReturn(Optional.of(
                        new SolverBuildArtifacts.StoredStopReceipt(bad, Instant.now())));
            }
            assertEquals(false, callback()); pairs.verifyNoInteractions(); assertFalse(api.deleted);
            assertEquals(1, this.<AtomicInteger>field("activeJobCount").get());
        }
    }

    @ParameterizedTest @CsvSource({"enqueued,true", "enqueued,false", "running,true", "running,false"})
    void restartConservesCancellationWithoutResettingOrPublishingThePair(String phase, boolean removed) throws Exception {
        int enqueued = StatusCode.STATUS_ENQUEUED.getVal(); int running = StatusCode.STATUS_RUNNING.getVal();
        int status = phase.equals("enqueued") ? enqueued : running;
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client();
             var dao = catalog(); var pairs = mockStatic(JobPairs.class)) {
            api.current = job(); api.current.getMetadata().setAnnotations(Map.of("starexec.org/output-dir", files.toString()));
            api.deleted = removed; configure(client);
            var lookup = mock(JobPairs.PairStatusLookupResult.class);
            when(lookup.isFound()).thenReturn(true); when(lookup.getStatusCode()).thenReturn(status);
            pairs.when(() -> JobPairs.getPairStatusLookup(42)).thenReturn(lookup);
            pairs.when(() -> JobPairs.getPairIdsByStatusCode(enqueued)).thenReturn(status == enqueued ? List.of(42) : List.of());
            pairs.when(() -> JobPairs.getPairIdsByStatusCode(running)).thenReturn(status == running ? List.of(42) : List.of());
            call("reconcileOrphanedPairs", new Class<?>[]{});
            dao.verify(() -> SolverBuildArtifacts.recordCancelled(eq(42), eq(1), eq(token), eq(engine), any()), atLeastOnce());
            pairs.verify(() -> JobPairs.tryResetEnqueuedToPending(anyInt()), never());
            pairs.verify(() -> JobPairs.retryNeverStartedPair(anyInt(), anyInt(), anyInt()), never());
            pairs.verify(() -> JobPairs.setPairLevelStatusResult(anyInt(), anyInt(), anyInt(), anyInt()), never());
            assertTrue(api.deleted); assertEquals(0, this.<AtomicInteger>field("activeJobCount").get());
            assertTrue(this.<Map<?, ?>>field("buildJobs").isEmpty());
            assertTrue(this.<Integer>field("nextExecId") > 7);
        }
    }

    @Test void aPositiveObservationReplaysCancellationThroughTheAtomicRejectionOperation() throws Exception {
        try (var dao = catalog()) {
            var receipt = KubernetesBuildStopObserver.record("starexec", identity(), job(),
                    new io.fabric8.kubernetes.api.model.PodListBuilder().withItems(KubernetesBuildStopObserverTest.finishedPod()).build());
            assertTrue(KubernetesBuildStopObserver.isCancelled(receipt));
            dao.verify(() -> SolverBuildArtifacts.recordCancelled(eq(42), eq(1), eq(token), eq(engine), any()));
            dao.verify(() -> SolverBuildArtifacts.recordStopReceipt(anyInt(), anyInt(), any(), anyString(), any()), never());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"artifact", "pair", "attempt", "engine", "namespace", "execution", "uid", "stopped", "recorded", "disposition"})
    void restoredCancellationRequiresTheExactRegisteredExecutionAndPositiveProof(String fault) throws Exception {
        String binding = switch (fault) {
            case "engine" -> "podman:other-container";
            case "namespace" -> "kubernetes:other:7:job-uid";
            case "execution" -> "kubernetes:starexec:0:job-uid";
            case "uid" -> "kubernetes:starexec:7:other-uid";
            default -> engine;
        };
        var artifact = new SolverBuildArtifacts.Artifact(fault.equals("artifact") ? 6 : 5,
                fault.equals("pair") ? 43 : 42, fault.equals("attempt") ? 2 : 1,
                9, 9, 10, 1, "/inputs/source", "/outputs/build", null, null, token, binding,
                fault.equals("stopped") ? null : Instant.now(), "REJECTED");
        var receipt = cancelled();
        if (fault.equals("disposition")) {
            var facts = receipt.facts(); facts.remove("disposition");
            receipt = SolverBuildStopReceipt.observed("kubernetes", 5, 42, 1, engine, "job-uid", Instant.now(), facts);
        }
        var stored = new SolverBuildArtifacts.StoredStopReceipt(receipt, fault.equals("recorded") ? null : Instant.now());
        assertThrows(IllegalStateException.class, () -> KubernetesBuildStopObserver.restoreCancelledIdentity("starexec", artifact, stored));
    }
}
