package org.starexec.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.SolverBuildArtifacts;

class KubernetesBuildUnstartedCleanupTest {
    private KubernetesNativeBackend backend = new KubernetesNativeBackend();
    private final UUID token = UUID.randomUUID();
    private final String engine = "kubernetes:starexec:7:job-uid";
    private final ExecutionRef execution = new ExecutionRef(7, "build-job", "job-uid");
    @TempDir Path files;

    private final class Catalog implements AutoCloseable {
        final MockedStatic<SolverBuildArtifacts> dao = mockStatic(SolverBuildArtifacts.class);
        final AtomicReference<SolverBuildArtifacts.Artifact> artifact = new AtomicReference<>(row(null, null, "PLANNED"));
        final AtomicReference<SolverBuildArtifacts.StoredStopReceipt> receipt = new AtomicReference<>();
        final AtomicBoolean committed = new AtomicBoolean();
        String failure;

        Catalog() throws Exception {
            dao.when(() -> SolverBuildArtifacts.find(42, 1)).thenAnswer(call -> Optional.of(artifact.get()));
            dao.when(() -> SolverBuildArtifacts.findCurrent(42)).thenAnswer(call -> Optional.of(artifact.get()));
            dao.when(() -> SolverBuildArtifacts.findStopReceipt(42, 1)).thenAnswer(call -> Optional.ofNullable(receipt.get()));
            dao.when(() -> SolverBuildArtifacts.recordUnstarted(eq(42), eq(1), eq(token), eq(engine), any()))
                    .thenAnswer(call -> conserve(call.getArgument(4), true));
            dao.when(() -> SolverBuildArtifacts.recordStopReceipt(eq(42), eq(1), eq(token), eq(engine), any()))
                    .thenAnswer(call -> conserve(call.getArgument(4), false));
        }

        private boolean conserve(org.starexec.data.to.SolverBuildStopReceipt value, boolean unstarted) throws Exception {
            if ("refused".equals(failure)) return false;
            if ("database".equals(failure)) throw new java.sql.SQLException("unavailable");
            if (receipt.get() != null) return receipt.get().receipt().payload().equals(value.payload());
            var now = Instant.now(); artifact.set(row(engine, now, unstarted ? "REJECTED" : "PLANNED"));
            receipt.set(new SolverBuildArtifacts.StoredStopReceipt(value, now)); committed.set(true);
            if ("lost-reply".equals(failure)) throw new java.sql.SQLException("commit acknowledgement lost");
            return true;
        }

        @Override public void close() { dao.close(); }
    }

    @SuppressWarnings("unchecked") private <T> T field(String name) throws Exception {
        var field = KubernetesNativeBackend.class.getDeclaredField(name); field.setAccessible(true); return (T) field.get(backend);
    }

    private Object call(String name, Class<?>[] types, Object... arguments) throws Exception {
        var method = KubernetesNativeBackend.class.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(backend, arguments); }
        catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof Exception cause) throw cause;
            throw (Error) failed.getCause();
        }
    }

    private void configure(io.fabric8.kubernetes.client.KubernetesClient client) throws Exception {
        for (var entry : Map.of("namespace", (Object) "starexec", "kubernetesClient", client).entrySet()) {
            var field = KubernetesNativeBackend.class.getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(backend, entry.getValue());
        }
    }

    private void prime(io.fabric8.kubernetes.client.KubernetesClient client) throws Exception {
        configure(client);
        call("rememberBuild", new Class<?>[]{io.fabric8.kubernetes.api.model.batch.v1.Job.class}, KubernetesBuildLaunchFenceTest.initialJob());
        this.<Set<Integer>>field("jobsHoldingSlot").add(7); this.<AtomicInteger>field("activeJobCount").set(1);
        this.<Map<Integer, String>>field("execIdToJobName").put(7, "build-job");
        this.<Map<Integer, String>>field("execIdToJobUid").put(7, "job-uid");
        this.<Map<Integer, Integer>>field("execIdToPairId").put(7, 42);
        this.<Map<Integer, Integer>>field("execIdToAttemptNo").put(7, 1);
        this.<Map<Integer, Path>>field("execIdToOutputDir").put(7, Path.of("/outputs/build"));
    }

    private SolverBuildArtifacts.Artifact row(String bound, Instant stopped, String state) {
        return new SolverBuildArtifacts.Artifact(5, 42, 1, 9, 9, 10, 1, "/inputs/source", "/outputs/build",
                null, null, token, bound, stopped, state);
    }

    private void delete(KubernetesBuildLaunchFenceTest.Api api) throws Exception {
        call("deleteKubernetesJob", new Class<?>[]{io.fabric8.kubernetes.api.model.batch.v1.Job.class}, api.current);
    }

    private Object callback(String name, Class<?>[] types, Object... arguments) throws Exception {
        Class<?> type = Class.forName("org.starexec.backend.KubernetesNativeBackend$KubernetesJobCompletionCallback");
        var constructor = type.getDeclaredConstructor(KubernetesNativeBackend.class); constructor.setAccessible(true);
        var method = type.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(constructor.newInstance(backend), arguments); }
        catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof Exception cause) throw cause;
            throw (Error) failed.getCause();
        }
    }

    @Test void noStartCleanupCommitsBeforeDeletingTheFencedVersionAndRetainsTheRetryOwner() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var catalog = new Catalog()) {
            prime(client);
            api.deletionAllowed = catalog.committed::get;
            delete(api);
            assertTrue(api.deleted, "a proven initial non-execution can be cleaned up");
            assertTrue(catalog.committed.get()); assertEquals(java.util.List.of("18"), api.deleteVersions);
            assertFalse(this.<Map<?, ?>>field("buildJobs").isEmpty(), "callback identity must survive until accounting is settled");
            assertEquals(1, this.<AtomicInteger>field("activeJobCount").get());
            call("releaseAccountingIfSafe", new Class<?>[]{ExecutionRef.class, String.class}, execution, "owned cleanup");
            assertEquals(0, this.<AtomicInteger>field("activeJobCount").get());
            assertTrue(this.<Map<?, ?>>field("buildJobs").isEmpty(), "fully retired proof must not accumulate");
        }
    }

    @Test void aNoStartCompletionSettlesTheAttemptWithoutScientificIngestion() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client();
             var catalog = new Catalog(); var pairs = mockStatic(JobPairs.class)) {
            prime(client);
            pairs.when(() -> JobPairs.retryNeverStartedPair(42, 7, 1)).thenReturn(JobPairs.NeverStartedRetryResult.REQUEUED);
            call("deleteKubernetesJob", new Class<?>[]{io.fabric8.kubernetes.api.model.batch.v1.Job.class}, api.current);
            assertTrue(api.deleted);
            assertEquals(true, callback("onJobComplete", new Class<?>[]{ExecutionRef.class}, execution));
            pairs.verify(() -> JobPairs.retryNeverStartedPair(42, 7, 1)); pairs.verifyNoMoreInteractions();
            assertEquals(0, this.<AtomicInteger>field("activeJobCount").get());
            assertTrue(this.<Map<?, ?>>field("buildJobs").isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"refused", "database", "lost-reply"})
    void anUnacknowledgedReceiptRetainsTheJobAndCallbackForRetry(String failure) throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var catalog = new Catalog()) {
            prime(client); catalog.failure = failure; delete(api);
            assertFalse(api.deleted); assertEquals(0, api.deletes.get());
            assertEquals(1, this.<AtomicInteger>field("activeJobCount").get());
            assertFalse(this.<Map<?, ?>>field("buildJobs").isEmpty());
            catalog.failure = null; delete(api);
            assertTrue(api.deleted);
            call("releaseAccountingIfSafe", new Class<?>[]{ExecutionRef.class, String.class}, execution, "owned retry");
            assertEquals(0, this.<AtomicInteger>field("activeJobCount").get());
            assertTrue(this.<Map<?, ?>>field("buildJobs").isEmpty());
        }
    }

    @Test void aRefusedDeleteKeepsIdentityAndReusesTheExactReceipt() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var catalog = new Catalog()) {
            prime(client); api.refuseDeletion = true; delete(api);
            var first = catalog.receipt.get(); assertNotNull(first); assertFalse(api.deleted);
            assertEquals(1, this.<AtomicInteger>field("activeJobCount").get());
            assertFalse(this.<Map<?, ?>>field("buildJobs").isEmpty());
            api.refuseDeletion = false; delete(api);
            assertTrue(api.deleted); assertEquals(first, catalog.receipt.get());
            assertEquals(java.util.List.of("18", "18"), api.deleteVersions);
        }
    }

    @Test void pendingCallbackDoesNotReadAStatusFileForProvenNonExecution() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client();
             var catalog = new Catalog(); var pairs = mockStatic(JobPairs.class)) {
            prime(client); Files.writeString(files.resolve("status.json"), "untrusted output must not be ingested");
            this.<Map<Integer, Path>>field("execIdToOutputDir").put(7, files);
            pairs.when(() -> JobPairs.retryNeverStartedPair(42, 7, 1)).thenReturn(JobPairs.NeverStartedRetryResult.REQUEUED);
            delete(api); assertTrue(api.deleted);
            assertEquals(true, callback("onJobStuckPending", new Class<?>[]{ExecutionRef.class, String.class}, execution, "timeout"));
            pairs.verify(() -> JobPairs.retryNeverStartedPair(42, 7, 1)); pairs.verifyNoMoreInteractions();
            assertEquals(0, this.<AtomicInteger>field("activeJobCount").get());
            assertTrue(this.<Map<?, ?>>field("buildJobs").isEmpty());
        }
    }

    @Test void aDisappearedJobCannotCreateTheFirstReceiptOrFreeCapacity() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var catalog = new Catalog()) {
            prime(client); api.deleted = true;
            call("releaseAccountingIfSafe", new Class<?>[]{ExecutionRef.class, String.class}, execution, "unproven disappearance");
            assertNull(catalog.receipt.get());
            assertEquals(1, this.<AtomicInteger>field("activeJobCount").get());
            assertFalse(this.<Map<?, ?>>field("buildJobs").isEmpty());
        }
    }

    @Test void normalTerminationRetainsProofUntilRemovalEvenWhenAccountingSettlesFirst() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var catalog = new Catalog()) {
            prime(client); api.current = KubernetesBuildStopObserverTest.finishedJob(); api.invalidCensus = "pod";
            catalog.artifact.set(row(engine, null, "PLANNED"));
            call("releaseAccountingIfSafe", new Class<?>[]{ExecutionRef.class, String.class}, execution, "normal completion");
            assertEquals(0, this.<AtomicInteger>field("activeJobCount").get());
            assertFalse(this.<Map<?, ?>>field("buildJobs").isEmpty());
            delete(api); assertTrue(api.deleted);
            assertTrue(this.<Map<?, ?>>field("buildJobs").isEmpty());
        }
    }

    @Test void aRetiredCallbackCannotCancelAHealthyReplacementWithTheSameName() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client(); var catalog = new Catalog()) {
            prime(client); delete(api);
            call("releaseAccountingIfSafe", new Class<?>[]{ExecutionRef.class, String.class}, execution, "retired build");
            assertTrue(this.<Map<?, ?>>field("buildJobs").isEmpty());
            int writes = api.writes.get(); int deletes = api.deletes.get();
            var replacement = KubernetesBuildLaunchFenceTest.initialJob();
            replacement.getMetadata().setLabels(new java.util.HashMap<>(replacement.getMetadata().getLabels()));
            replacement.getMetadata().setUid("replacement-uid"); replacement.getMetadata().setResourceVersion("30");
            replacement.getMetadata().getLabels().put(KubernetesBuildStopObserver.ARTIFACT_LABEL, "6");
            replacement.getMetadata().getLabels().put("starexec.org/pair-id", "43");
            Map<String, String> selector = Map.of("batch.kubernetes.io/controller-uid", "replacement-uid");
            replacement.getSpec().getSelector().setMatchLabels(selector);
            replacement.getSpec().getTemplate().getMetadata().setLabels(selector);
            api.current = replacement; api.deleted = false;
            UUID nextToken = UUID.randomUUID();
            var next = new SolverBuildArtifacts.Artifact(6, 43, 1, 9, 9, 10, 2, "/inputs/source", "/outputs/next",
                    null, null, nextToken, null, null, "PLANNED");
            catalog.dao.when(() -> SolverBuildArtifacts.find(43, 1)).thenReturn(Optional.of(next));
            catalog.dao.when(() -> SolverBuildArtifacts.findStopReceipt(43, 1)).thenReturn(Optional.empty());
            catalog.dao.when(() -> SolverBuildArtifacts.recordUnstarted(eq(43), eq(1), eq(nextToken), anyString(), any())).thenReturn(true);
            assertEquals(false, call("ensureKubernetesJobGone", new Class<?>[]{ExecutionRef.class}, execution));
            assertFalse(api.deleted); assertEquals(writes, api.writes.get()); assertEquals(deletes, api.deletes.get());
            catalog.dao.verify(() -> SolverBuildArtifacts.recordUnstarted(eq(43), eq(1), eq(nextToken), anyString(), any()), never());
        }
    }

    @Test void restartAfterJobRemovalSettlesTheConservedAttemptInsteadOfResettingIt() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client();
             var catalog = new Catalog(); var pairs = mockStatic(JobPairs.class)) {
            prime(client); delete(api); assertTrue(api.deleted);
            backend = new KubernetesNativeBackend(); configure(client);
            pairs.when(() -> JobPairs.getPairIdsByStatusCode(2)).thenReturn(java.util.List.of(42));
            pairs.when(() -> JobPairs.getPairIdsByStatusCode(3)).thenReturn(java.util.List.of());
            pairs.when(() -> JobPairs.retryNeverStartedPair(42, 7, 1)).thenReturn(JobPairs.NeverStartedRetryResult.REQUEUED);
            call("reconcileOrphanedPairs", new Class<?>[]{});
            pairs.verify(() -> JobPairs.retryNeverStartedPair(42, 7, 1));
            pairs.verify(() -> JobPairs.tryResetEnqueuedToPending(anyInt()), never());
            pairs.verify(() -> JobPairs.getPairStatusLookup(anyInt()), never());
            assertEquals(0, this.<AtomicInteger>field("activeJobCount").get());
            assertTrue(this.<Map<?, ?>>field("buildJobs").isEmpty());
            assertTrue(this.<Integer>field("nextExecId") > 7);
        }
    }

    @Test void anUnknownRegisteredBuildCannotUseTheLegacyStartupReset() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client();
             var catalog = new Catalog(); var pairs = mockStatic(JobPairs.class)) {
            configure(client); api.deleted = true;
            catalog.artifact.set(row(engine, null, "PLANNED"));
            pairs.when(() -> JobPairs.getPairIdsByStatusCode(2)).thenReturn(java.util.List.of(42));
            pairs.when(() -> JobPairs.getPairIdsByStatusCode(3)).thenReturn(java.util.List.of());
            call("reconcileOrphanedPairs", new Class<?>[]{});
            pairs.verify(() -> JobPairs.tryResetEnqueuedToPending(anyInt()), never());
            pairs.verify(() -> JobPairs.retryNeverStartedPair(anyInt(), anyInt(), anyInt()), never());
            assertNull(catalog.receipt.get());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void restartRecoversAFencedJobBeforeOrAfterReceiptCommit(boolean receiptCommitted) throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client();
             var catalog = new Catalog(); var pairs = mockStatic(JobPairs.class)) {
            api.current.getMetadata().setAnnotations(Map.of("starexec.org/output-dir", files.toString()));
            if (receiptCommitted) KubernetesBuildUnstartedObserver.record(client, "starexec",
                    KubernetesBuildStopObserver.identify("starexec", api.current), api.current);
            else KubernetesBuildLaunchFence.preventStart(client, "starexec",
                    KubernetesBuildStopObserver.identify("starexec", api.current), api.current);
            backend = new KubernetesNativeBackend(); configure(client);
            var lookup = mock(JobPairs.PairStatusLookupResult.class);
            when(lookup.isFound()).thenReturn(true); when(lookup.getStatusCode()).thenReturn(2);
            pairs.when(() -> JobPairs.getPairStatusLookup(42)).thenReturn(lookup);
            pairs.when(() -> JobPairs.getPairIdsByStatusCode(2)).thenReturn(java.util.List.of(42));
            pairs.when(() -> JobPairs.getPairIdsByStatusCode(3)).thenReturn(java.util.List.of());
            pairs.when(() -> JobPairs.retryNeverStartedPair(42, 7, 1)).thenReturn(JobPairs.NeverStartedRetryResult.REQUEUED);
            call("reconcileOrphanedPairs", new Class<?>[]{});
            assertTrue(api.deleted); assertTrue(catalog.committed.get());
            pairs.verify(() -> JobPairs.retryNeverStartedPair(42, 7, 1));
            pairs.verify(() -> JobPairs.tryResetEnqueuedToPending(anyInt()), never());
            assertEquals(0, this.<AtomicInteger>field("activeJobCount").get());
            assertTrue(this.<Map<?, ?>>field("buildJobs").isEmpty());
        }
    }
}
