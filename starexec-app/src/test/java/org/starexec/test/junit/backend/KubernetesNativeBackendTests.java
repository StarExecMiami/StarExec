package org.starexec.test.junit.backend;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.NodeSelectorRequirement;
import io.fabric8.kubernetes.api.model.NodeSelectorTerm;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.StatusDetails;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.BatchAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.ScalableResource;
import io.fabric8.kubernetes.client.dsl.V1BatchAPIGroupDSL;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.backend.Backend;
import org.starexec.backend.ExecutionRef;
import org.starexec.backend.KubernetesNativeBackend;
import org.starexec.backend.KubernetesJobMonitor;
import org.starexec.backend.PodPhaseView;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.JobPairs.PairStatusLookupState;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.to.Status.StatusCode;

/**
 * Focused unit tests for KubernetesNativeBackend core invariants that do not
 * require a live Kubernetes cluster.
 */
public class KubernetesNativeBackendTests {

    @Test
    public void isErrorTreatsZeroAndNegativeAsError() {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        assertTrue(backend.isError(-1));
        assertTrue(backend.isError(0));
        assertFalse(backend.isError(1));
    }

    @Test
    public void killPairReturnsFalseWhenExecIdUnknown() {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        assertFalse(backend.killPair(12345));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void generatedExecutionIdsArePositiveAndUnique() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Method generateExecId = KubernetesNativeBackend.class
            .getDeclaredMethod("generateExecId");
        generateExecId.setAccessible(true);

        int first = (int) generateExecId.invoke(backend);
        int second = (int) generateExecId.invoke(backend);

        assertTrue(first > 0);
        assertTrue(second > 0);
        assertNotEquals(first, second);

        Field nextExecIdField = KubernetesNativeBackend.class
            .getDeclaredField("nextExecId");
        nextExecIdField.setAccessible(true);
        int currentNext = (int) nextExecIdField.get(backend);
        assertTrue(currentNext > second);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void resolveOutputDirectoryFallsBackWhenLogPathMissing() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Method resolveOutputDirectory = KubernetesNativeBackend.class
            .getDeclaredMethod("resolveOutputDirectory", String.class);
        resolveOutputDirectory.setAccessible(true);

        Path fallback = (Path) resolveOutputDirectory.invoke(backend, "");
        Path withParent = (Path) resolveOutputDirectory.invoke(
            backend,
            "/tmp/starexec/logs/pair.log"
        );

        assertNotNull(fallback);
        assertNotNull(withParent);
        assertEquals(Path.of("/tmp/starexec/logs"), withParent);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void killAllRetainsTrackingWhenNothingCanBeEstablished() throws Exception {
        // Deliberately reversed. This test used to be named
        // "killAllClearsTrackingMapsEvenWithoutClient" and asserted that killAll cleared
        // every map and released every slot when there was no client. "Even without a
        // client" is precisely the case where clearing is unsafe: nothing can be
        // established about any pod, so forgetting the executions would let unrelated
        // pairs be scheduled beside solvers that may still be running -- contaminating
        // THEIR measurements, not just this pair's. killAll's only caller is
        // Jobs.pauseAll, an admin endpoint on a live process, so this is not a
        // shutdown-only path.
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Map<Integer, String> execToJob =
            (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) getField(backend, "execIdToPairId");
        Map<Integer, Path> execToOut =
            (Map<Integer, Path>) getField(backend, "execIdToOutputDir");

        execToJob.put(1, "job-1");
        execToPair.put(1, 111);
        execToOut.put(1, Path.of("/tmp/output/1"));

        assertFalse("killAll cannot report success when it proved nothing", backend.killAll());
        assertEquals("tracking must survive an unprovable killAll", 1, execToJob.size());
        assertEquals(1, execToPair.size());
        assertEquals(1, execToOut.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void destroyClearsTrackingMaps() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Field execIdToJobNameField = KubernetesNativeBackend.class
            .getDeclaredField("execIdToJobName");
        Field execIdToPairIdField = KubernetesNativeBackend.class
            .getDeclaredField("execIdToPairId");
        Field execIdToOutputDirField = KubernetesNativeBackend.class
            .getDeclaredField("execIdToOutputDir");

        execIdToJobNameField.setAccessible(true);
        execIdToPairIdField.setAccessible(true);
        execIdToOutputDirField.setAccessible(true);

        Map<Integer, String> execToJob =
            (Map<Integer, String>) execIdToJobNameField.get(backend);
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) execIdToPairIdField.get(backend);
        Map<Integer, Path> execToOut =
            (Map<Integer, Path>) execIdToOutputDirField.get(backend);

        execToJob.put(2, "job-2");
        execToPair.put(2, 222);
        execToOut.put(2, Path.of("/tmp/output/2"));

        backend.destroyIf();

        assertTrue(execToJob.isEmpty());
        assertTrue(execToPair.isEmpty());
        assertTrue(execToOut.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void rebuildTrackingFromJobRestoresLabelsAndOutputAnnotation() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Job job = new JobBuilder()
            .withNewMetadata()
                .withName("starexec-job-77")
                .addToLabels("starexec.org/managed", "true")
                .addToLabels("starexec.org/exec-id", "77")
                .addToLabels("starexec.org/pair-id", "707")
                .addToAnnotations("starexec.org/output-dir", "/tmp/starexec/out/707")
            .endMetadata()
            .withNewStatus()
                .withActive(1)
            .endStatus()
            .build();

        Method rebuildTrackingFromJob = KubernetesNativeBackend.class
            .getDeclaredMethod("rebuildTrackingFromJob", Job.class, PodPhaseView.class);
        rebuildTrackingFromJob.setAccessible(true);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.trySetPairRunning(707))
                .thenReturn(JobPairs.ConditionalPairUpdateResult.UPDATED);

            rebuildTrackingFromJob.invoke(backend, job, viewWithPod(77, "Running"));

            jobPairsMock.verify(() -> JobPairs.trySetPairRunning(707));
        }

        Map<Integer, String> execToJob =
            (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) getField(backend, "execIdToPairId");
        Map<Integer, Path> execToOut =
            (Map<Integer, Path>) getField(backend, "execIdToOutputDir");

        assertEquals("starexec-job-77", execToJob.get(77));
        assertEquals(Integer.valueOf(707), execToPair.get(77));
        assertEquals(Path.of("/tmp/starexec/out/707"), execToOut.get(77));
    }

    /**
     * A restart during a scheduling failure must not re-apply the mislabel. The Job here
     * reports active=1, which the Kubernetes API defines as counting pending pods as well
     * as running ones, and its pod has never left phase Pending.
     */
    @Test
    public void rebuildTrackingFromJobDoesNotMarkPendingJobsRunning() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Job job = new JobBuilder()
            .withNewMetadata()
                .withName("starexec-job-78")
                .addToLabels("starexec.org/managed", "true")
                .addToLabels("starexec.org/exec-id", "78")
                .addToLabels("starexec.org/pair-id", "708")
                .addToAnnotations("starexec.org/output-dir", "/tmp/starexec/out/708")
            .endMetadata()
            .withNewStatus()
                .withActive(1)
            .endStatus()
            .build();

        Method rebuildTrackingFromJob = KubernetesNativeBackend.class
            .getDeclaredMethod("rebuildTrackingFromJob", Job.class, PodPhaseView.class);
        rebuildTrackingFromJob.setAccessible(true);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            rebuildTrackingFromJob.invoke(backend, job, viewWithPod(78, "Pending"));
            jobPairsMock.verifyNoInteractions();
        }

        Map<Integer, String> execToJob =
            (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) getField(backend, "execIdToPairId");
        Map<Integer, Path> execToOut =
            (Map<Integer, Path>) getField(backend, "execIdToOutputDir");

        assertEquals("starexec-job-78", execToJob.get(78));
        assertEquals(Integer.valueOf(708), execToPair.get(78));
        assertEquals(Path.of("/tmp/starexec/out/708"), execToOut.get(78));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void runningCallbackRetriesWhenDatabaseUpdateFails() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Map<Integer, String> execToJob =
            (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) getField(backend, "execIdToPairId");

        execToJob.put(15, "job-15");
        execToPair.put(15, 515);

        givenSafeCluster(backend);
        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.trySetPairRunning(515))
                .thenReturn(
                    JobPairs.ConditionalPairUpdateResult.ERROR,
                    JobPairs.ConditionalPairUpdateResult.UPDATED
                );

            assertFalse(callback.onJobRunning(execution(15, "job-15")));
            assertEquals("job-15", execToJob.get(15));
            assertEquals(Integer.valueOf(515), execToPair.get(15));

            assertTrue(callback.onJobRunning(execution(15, "job-15")));
            jobPairsMock.verify(() -> JobPairs.trySetPairRunning(515), Mockito.times(2));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void runningCallbackLeavesKilledMarkerForTerminalCallbacks() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        givenSafeCluster(backend);
        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        int execId = 16;
        ExecutionRef execution = execution(execId, "job-16");
        Set<ExecutionRef> killedExecutions =
            (Set<ExecutionRef>) getField(backend, "killedExecutions");
        killedExecutions.add(execution);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            assertTrue(callback.onJobRunning(execution(execId, "job-16")));
            assertTrue(killedExecutions.contains(execution));
            jobPairsMock.verifyNoInteractions();

            assertTrue(callback.onJobComplete(execution(execId, "job-16")));
            assertFalse(killedExecutions.contains(execution));
            jobPairsMock.verifyNoInteractions();
        }
    }

    @Test
    public void terminalJobDetectionUsesSucceededAndFailedStatus() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Method isTerminalJob = KubernetesNativeBackend.class
            .getDeclaredMethod("isTerminalJob", Job.class);
        isTerminalJob.setAccessible(true);

        Job runningJob = new JobBuilder()
            .withNewStatus()
                .withActive(1)
            .endStatus()
            .build();
        Job succeededJob = new JobBuilder()
            .withNewStatus()
                .withSucceeded(1)
            .endStatus()
            .build();
        Job failedJob = new JobBuilder()
            .withNewStatus()
                .withFailed(1)
            .endStatus()
            .build();

        assertFalse((boolean) isTerminalJob.invoke(backend, runningJob));
        assertTrue((boolean) isTerminalJob.invoke(backend, succeededJob));
        assertTrue((boolean) isTerminalJob.invoke(backend, failedJob));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void completionCallbackRetriesWhenDatabaseUpdateFails() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Map<Integer, String> execToJob =
            (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) getField(backend, "execIdToPairId");
        Map<Integer, Path> execToOut =
            (Map<Integer, Path>) getField(backend, "execIdToOutputDir");

        execToJob.put(11, "job-11");
        execToPair.put(11, 111);
        execToOut.put(11, Path.of("/tmp/output/11"));

        givenSafeCluster(backend);
        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(111))
                .thenReturn(foundLookup(StatusCode.STATUS_RUNNING.getVal()));
            jobPairsMock
                .when(
                    () ->
                        JobPairs.setPairStatusPreciseResult(
                            111,
                            1,
                            // ERROR_RUNSCRIPT, not COMPLETE: these fixtures give the
                            // execution an empty output directory, and a run that left
                            // no artifacts is no longer recorded as a completed one.
                            StatusCode.ERROR_RUNSCRIPT.getVal(),
                            StatusCode.STATUS_NOT_REACHED.getVal(),
                            false
                        )
                )
                .thenReturn(PairStatusResult.FAILED, PairStatusResult.APPLIED);

            assertFalse(callback.onJobComplete(execution(11, "job-11")));
            assertEquals("job-11", execToJob.get(11));
            assertEquals(Integer.valueOf(111), execToPair.get(11));
            assertEquals(Path.of("/tmp/output/11"), execToOut.get(11));

            assertTrue(callback.onJobComplete(execution(11, "job-11")));
            assertTrue(execToJob.isEmpty());
            assertTrue(execToPair.isEmpty());
            assertTrue(execToOut.isEmpty());
        }
    }

    /**
     * A pair that already holds a different terminal status must be treated as handled,
     * not retried. Retrying cannot ever succeed -- the status write is refused every time
     * -- and because the caller only records the exec id as complete when this returns
     * true, returning false would make the next poll process the same job again without
     * end. Tracking must still be cleared so the Kubernetes job is cleaned up.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void completionCallbackTreatsSupersededStatusAsHandled() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Map<Integer, String> execToJob =
            (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) getField(backend, "execIdToPairId");
        Map<Integer, Path> execToOut =
            (Map<Integer, Path>) getField(backend, "execIdToOutputDir");

        execToJob.put(21, "job-21");
        execToPair.put(21, 211);
        execToOut.put(21, Path.of("/tmp/output/21"));

        givenSafeCluster(backend);
        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(211))
                .thenReturn(foundLookup(StatusCode.STATUS_RUNNING.getVal()));
            jobPairsMock
                .when(
                    () ->
                        JobPairs.setPairStatusPreciseResult(
                            211,
                            1,
                            StatusCode.STATUS_COMPLETE.getVal(),
                            StatusCode.STATUS_NOT_REACHED.getVal(),
                            false
                        )
                )
                .thenReturn(PairStatusResult.SUPERSEDED);

            assertTrue(callback.onJobComplete(execution(21, "job-21")));
            assertTrue(execToJob.isEmpty());
            assertTrue(execToPair.isEmpty());
            assertTrue(execToOut.isEmpty());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void failedCallbackRetriesWhenDatabaseUpdateFails() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Map<Integer, String> execToJob =
            (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) getField(backend, "execIdToPairId");
        Map<Integer, Path> execToOut =
            (Map<Integer, Path>) getField(backend, "execIdToOutputDir");

        execToJob.put(12, "job-12");
        execToPair.put(12, 222);
        execToOut.put(12, Path.of("/tmp/output/12"));

        givenSafeCluster(backend);
        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(222))
                .thenReturn(foundLookup(StatusCode.STATUS_RUNNING.getVal()));
            jobPairsMock
                .when(
                    () ->
                        JobPairs.setPairStatusPreciseResult(
                            222,
                            1,
                            StatusCode.ERROR_RUNSCRIPT.getVal(),
                            StatusCode.STATUS_NOT_REACHED.getVal(),
                            false
                        )
                )
                .thenReturn(PairStatusResult.FAILED, PairStatusResult.APPLIED);

            assertFalse(callback.onJobFailed(execution(12, "job-12"), "BackoffLimitExceeded"));
            assertEquals("job-12", execToJob.get(12));
            assertEquals(Integer.valueOf(222), execToPair.get(12));
            assertEquals(Path.of("/tmp/output/12"), execToOut.get(12));

            assertTrue(callback.onJobFailed(execution(12, "job-12"), "BackoffLimitExceeded"));
            assertTrue(execToJob.isEmpty());
            assertTrue(execToPair.isEmpty());
            assertTrue(execToOut.isEmpty());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void completionCallbackRetriesWhenDatabaseUpdateThrows() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Map<Integer, String> execToJob =
            (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) getField(backend, "execIdToPairId");
        Map<Integer, Path> execToOut =
            (Map<Integer, Path>) getField(backend, "execIdToOutputDir");

        execToJob.put(13, "job-13");
        execToPair.put(13, 313);
        execToOut.put(13, Path.of("/tmp/output/13"));

        givenSafeCluster(backend);
        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(313))
                .thenReturn(foundLookup(StatusCode.STATUS_RUNNING.getVal()));
            jobPairsMock
                .when(
                    () ->
                        JobPairs.setPairStatusPreciseResult(
                            313,
                            1,
                            // ERROR_RUNSCRIPT, not COMPLETE: these fixtures give the
                            // execution an empty output directory, and a run that left
                            // no artifacts is no longer recorded as a completed one.
                            StatusCode.ERROR_RUNSCRIPT.getVal(),
                            StatusCode.STATUS_NOT_REACHED.getVal(),
                            false
                        )
                )
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(PairStatusResult.APPLIED);

            assertFalse(callback.onJobComplete(execution(13, "job-13")));
            assertEquals("job-13", execToJob.get(13));
            assertEquals(Integer.valueOf(313), execToPair.get(13));
            assertEquals(Path.of("/tmp/output/13"), execToOut.get(13));

            assertTrue(callback.onJobComplete(execution(13, "job-13")));
            assertTrue(execToJob.isEmpty());
            assertTrue(execToPair.isEmpty());
            assertTrue(execToOut.isEmpty());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void failedCallbackRetriesWhenDatabaseUpdateThrows() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Map<Integer, String> execToJob =
            (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) getField(backend, "execIdToPairId");
        Map<Integer, Path> execToOut =
            (Map<Integer, Path>) getField(backend, "execIdToOutputDir");

        execToJob.put(14, "job-14");
        execToPair.put(14, 414);
        execToOut.put(14, Path.of("/tmp/output/14"));

        givenSafeCluster(backend);
        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(414))
                .thenReturn(foundLookup(StatusCode.STATUS_RUNNING.getVal()));
            jobPairsMock
                .when(
                    () ->
                        JobPairs.setPairStatusPreciseResult(
                            414,
                            1,
                            StatusCode.ERROR_RUNSCRIPT.getVal(),
                            StatusCode.STATUS_NOT_REACHED.getVal(),
                            false
                        )
                )
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(PairStatusResult.APPLIED);

            assertFalse(callback.onJobFailed(execution(14, "job-14"), "BackoffLimitExceeded"));
            assertEquals("job-14", execToJob.get(14));
            assertEquals(Integer.valueOf(414), execToPair.get(14));
            assertEquals(Path.of("/tmp/output/14"), execToOut.get(14));

            assertTrue(callback.onJobFailed(execution(14, "job-14"), "BackoffLimitExceeded"));
            assertTrue(execToJob.isEmpty());
            assertTrue(execToPair.isEmpty());
            assertTrue(execToOut.isEmpty());
        }
    }

    /**
     * Gives the backend a Kubernetes client whose Job deletion behaves as asked.
     *
     * <p>Needed because {@code ensureKubernetesJobGone} reports "not gone" when there is
     * no client — it cannot confirm otherwise — and the stuck-pending path now treats that
     * as a reason to retry rather than to release the pair.
     *
     * @param deleted   whether the delete call reports a deletion
     * @param stillThere what a follow-up read finds: the Job, or null if it is really gone
     */
    // =====================================================================
    // Execution-safety invariants.
    //
    // The property under test throughout is "no pod capable of executing or
    // writing results", never "no Job object exists". A stale pod that later
    // runs contaminates OTHER pairs' measurements through CPU and cache
    // contention, so an execution that may be alive keeps its accounting.
    // =====================================================================

    @SuppressWarnings("unchecked")
    private Map<Integer, ?> trackingMap(KubernetesNativeBackend backend, String name)
        throws Exception {
        return (Map<Integer, ?>) getField(backend, name);
    }

    @SuppressWarnings("unchecked")
    private Set<Integer> holdingSlot(KubernetesNativeBackend backend) throws Exception {
        return (Set<Integer>) getField(backend, "jobsHoldingSlot");
    }

    private int activeCount(KubernetesNativeBackend backend) throws Exception {
        return ((java.util.concurrent.atomic.AtomicInteger)
            getField(backend, "activeJobCount")).get();
    }

    private Backend.KillOutcome killConfirmed(KubernetesNativeBackend backend, int execId) {
        return backend.killPairConfirmed(execId);
    }

    @Test
    public void aKillIsUnprovenWhileAPodMayStillRun() throws Exception {
        // Job confirmed gone is NOT sufficient. The pod outlives it under any propagation
        // policy that does not block on dependents, so absence of the Job says nothing.
        for (String phase : List.of("Running", "Pending", "Unknown")) {
            KubernetesNativeBackend backend = new KubernetesNativeBackend();
            setField(backend, "namespace", "starexec");
            ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(7, "job-7");
            givenJobDeletion(backend, "job-7", true, null, podInPhase(7, phase));
            restore(backend, 7);

            assertEquals(
                "a pod in phase " + phase + " means the execution may still run",
                Backend.KillOutcome.UNPROVEN,
                killConfirmed(backend, 7)
            );
            assertTrue(
                "accounting must be retained while phase=" + phase,
                holdingSlot(backend).contains(7)
            );
            assertFalse(
                "tracking must be retained while phase=" + phase,
                trackingMap(backend, "execIdToJobName").isEmpty()
            );
        }
    }

    @Test
    public void aKillIsConfirmedOnlyWhenNoPodCanRun() throws Exception {
        for (String phase : List.of("Succeeded", "Failed")) {
            KubernetesNativeBackend backend = new KubernetesNativeBackend();
            setField(backend, "namespace", "starexec");
            ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(8, "job-8");
            givenJobDeletion(backend, "job-8", true, null, podInPhase(8, phase));
            restore(backend, 8);

            // A terminated pod cannot consume CPU or write further output, so holding the
            // execution for it would wedge admission on a harmless historical object.
            assertEquals(
                "phase " + phase + " is terminal and therefore safe",
                Backend.KillOutcome.CONFIRMED_SAFE,
                killConfirmed(backend, 8)
            );
            assertFalse(holdingSlot(backend).contains(8));
        }
    }

    // ------------------------------------------------------------------
    // controller safety: a batch/v1 Job can create a REPLACEMENT pod
    // ------------------------------------------------------------------

    /**
     * The defect this whole predicate exists for, exercised at the caller.
     *
     * <p>{@code backoffLimit} defaults to 0, which masks it: with no retries a failed pod is
     * the end of the Job. An operator who enables retries reaches the real case, where
     * {@code status.failed == 1} means "the first attempt died and the next is coming" — and
     * the old predicate called that terminal.
     */
    @Test
    public void aRetryingJobIsNotSpentEvenWithNoPodPresent() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(21, "job-21");

        Job retrying = jobWithNoConditions(21);
        retrying.getStatus().setFailed(1);
        retrying.setSpec(new io.fabric8.kubernetes.api.model.batch.v1.JobSpecBuilder()
            .withBackoffLimit(3)
            .build());

        // Job deleted by name, no pods at all -- a pod-only check would call this safe.
        givenJobDeletion(backend, "job-21", true, null);
        givenJobsFor((KubernetesClient) getField(backend, "kubernetesClient"), retrying);
        restore(backend, 21);

        assertEquals(
            "a Job with backoffLimit=3 at failed=1 can still create another pod",
            Backend.KillOutcome.UNPROVEN,
            killConfirmed(backend, 21)
        );
        assertTrue(
            "its slot must be retained so no unrelated pair lands beside the retry",
            holdingSlot(backend).contains(21)
        );
        assertFalse(
            "tracking must be retained",
            trackingMap(backend, "execIdToJobName").isEmpty()
        );
    }

    @Test
    public void onlyTheTerminalJobConditionsMakeAControllerSpent() throws Exception {
        // type, status, expected-spent
        Object[][] cases = {
            { "Complete", "True", true },
            { "Failed", "True", true },
            { "Complete", "False", false },
            { "Failed", "False", false },
            // FailureTarget BEGINS termination; the real Failed condition comes later.
            { "FailureTarget", "True", false },
            { "SuccessCriteriaMet", "True", false },
            { "Suspended", "True", false },
        };
        for (Object[] c : cases) {
            String type = (String) c[0];
            String status = (String) c[1];
            boolean spent = (Boolean) c[2];

            KubernetesNativeBackend backend = new KubernetesNativeBackend();
            setField(backend, "namespace", "starexec");
            ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(22, "job-22");
            givenJobDeletion(backend, "job-22", true, null);
            givenJobsFor(
                (KubernetesClient) getField(backend, "kubernetesClient"),
                jobWithCondition(22, type, status)
            );
            restore(backend, 22);

            assertEquals(
                type + "=" + status + " should" + (spent ? "" : " not") + " make the"
                    + " controller spent",
                spent ? Backend.KillOutcome.CONFIRMED_SAFE : Backend.KillOutcome.UNPROVEN,
                killConfirmed(backend, 22)
            );
        }
    }

    @Test
    public void aSucceededCounterAloneDoesNotMakeAControllerSpent() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(23, "job-23");

        Job counterOnly = jobWithNoConditions(23);
        counterOnly.getStatus().setSucceeded(1);

        givenJobDeletion(backend, "job-23", true, null);
        givenJobsFor((KubernetesClient) getField(backend, "kubernetesClient"), counterOnly);
        restore(backend, 23);

        assertEquals(
            "succeeded>0 is a pod counter, not a terminal Job condition",
            Backend.KillOutcome.UNPROVEN,
            killConfirmed(backend, 23)
        );
    }

    @Test
    public void aJobWithNoStatusIsNeverSpent() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(24, "job-24");

        Job noStatus = jobWithNoConditions(24);
        noStatus.setStatus(null);

        givenJobDeletion(backend, "job-24", true, null);
        givenJobsFor((KubernetesClient) getField(backend, "kubernetesClient"), noStatus);
        restore(backend, 24);

        assertEquals(
            "absence of information is not evidence of safety",
            Backend.KillOutcome.UNPROVEN,
            killConfirmed(backend, 24)
        );
    }

    @Test
    public void aFailedJobListingIsNeverReadAsControllerAbsence() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(25, "job-25");
        givenJobDeletion(backend, "job-25", true, null);
        givenJobListingFails((KubernetesClient) getField(backend, "kubernetesClient"));
        restore(backend, 25);

        assertEquals(
            "an unreadable Job listing cannot establish that no controller survives",
            Backend.KillOutcome.UNPROVEN,
            killConfirmed(backend, 25)
        );
        assertTrue(holdingSlot(backend).contains(25));
    }

    /**
     * A terminated pod is safe with respect to that pod only. If the Job that owns it is
     * still live, the execution is not over — it is between attempts.
     */
    @Test
    public void aTerminatedPodDoesNotOverrideALiveController() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(26, "job-26");
        givenJobDeletion(backend, "job-26", true, null, podInPhase(26, "Failed"));
        givenJobsFor(
            (KubernetesClient) getField(backend, "kubernetesClient"),
            jobWithNoConditions(26)
        );
        restore(backend, 26);

        assertEquals(
            "a Failed pod plus a live Job means the next attempt may still start",
            Backend.KillOutcome.UNPROVEN,
            killConfirmed(backend, 26)
        );
        assertTrue(holdingSlot(backend).contains(26));
    }

    /** The name is never reconstructed; identity comes from the exec-id label. */
    @Test
    public void aControllerIsFoundByLabelWhenTheJobNameIsLost() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        // no execIdToJobName entry at all
        givenJobDeletion(backend, "unused", true, null);
        givenJobsFor(
            (KubernetesClient) getField(backend, "kubernetesClient"),
            jobWithNoConditions(27)
        );

        assertEquals(
            "a live Job carrying exec-id=27 is found by label even with no local name",
            Backend.KillOutcome.UNPROVEN,
            killConfirmed(backend, 27)
        );
    }

    /**
     * An UNPROVEN hold must have an owner that revisits it. Before this, the field the
     * javadoc named as the retry owner was never written to, so a hold survived until the
     * JVM restarted.
     */
    @Test
    public void anUnprovenHoldIsReleasedOnceTheSweepCanEstablishSafety() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(28, "job-28");
        givenJobDeletion(backend, "job-28", true, null, podInPhase(28, "Running"));
        restore(backend, 28);

        assertEquals(Backend.KillOutcome.UNPROVEN, killConfirmed(backend, 28));
        assertTrue("the slot is held", holdingSlot(backend).contains(28));
        assertFalse(
            "and the obligation is recorded, not forgotten",
            ((Map<Integer, ?>) getField(backend, "unverifiedExecutions")).isEmpty()
        );

        // The pod goes away and no controller remains. The sweep re-checks BOTH halves --
        // a pod-only design could not make this transition, because it kept no record of
        // the execution once its pod vanished.
        givenPodsFor((KubernetesClient) getField(backend, "kubernetesClient"));
        invokePrivate(backend, "revisitUnverifiedExecutions");

        assertFalse("the hold is released", holdingSlot(backend).contains(28));
        assertTrue(
            "and the record is cleared",
            ((Map<Integer, ?>) getField(backend, "unverifiedExecutions")).isEmpty()
        );
    }

    @Test
    public void anUnprovenHoldSurvivesASweepThatStillCannotEstablishSafety() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(29, "job-29");
        givenJobDeletion(backend, "job-29", true, null, podInPhase(29, "Running"));
        restore(backend, 29);
        assertEquals(Backend.KillOutcome.UNPROVEN, killConfirmed(backend, 29));

        invokePrivate(backend, "revisitUnverifiedExecutions");

        assertTrue(
            "still running, so the slot stays held",
            holdingSlot(backend).contains(29)
        );
        assertFalse(
            "and the obligation stays on the books",
            ((Map<Integer, ?>) getField(backend, "unverifiedExecutions")).isEmpty()
        );
    }

    /**
     * B2: "this execution is now safe" and "the operation that was in flight completed" are
     * different facts, and this sweep can only ever establish the first.
     */
    @Test
    public void aSweepThatEstablishesSafetyMustNotCancelAnOutstandingContinuation()
        throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(31, "job-31");
        ((Map<Integer, Integer>) getField(backend, "execIdToPairId")).put(31, 4242);
        givenJobDeletion(backend, "job-31", true, null, podInPhase(31, "Running"));
        restore(backend, 31);
        assertEquals(Backend.KillOutcome.UNPROVEN, killConfirmed(backend, 31));

        // The stuck-Pending shape: that callback returned false, so the monitor kept its
        // cleanup-pending record, and that record -- not this sweep -- owns writing the
        // pair's terminal status and end_time.
        recordUnverifiedWithContinuation(backend, 31, "stuck-pending escalation for pair 4242");

        // The pod goes away, so safety becomes establishable.
        givenPodsFor((KubernetesClient) getField(backend, "kubernetesClient"));
        invokePrivate(backend, "revisitUnverifiedExecutions");

        assertFalse(
            "marking a merely-safe execution as killed cancels the continuation: every"
                + " terminal callback short-circuits on the stopped-execution state and"
                + " returns true, so the monitor drops its cleanup-pending record with the"
                + " DB transition never written and the pair stranded at ENQUEUED",
            ((Set<Integer>) getField(backend, "legacyKilledExecIds")).contains(31)
        );
        assertTrue(
            "and no concrete cancellation may be recorded for it either",
            ((Set<ExecutionRef>) getField(backend, "killedExecutions"))
                .stream()
                .noneMatch(ref -> ref.execId() == 31)
        );
        assertEquals(
            "and the continuation still resolves its pair id from this map",
            Integer.valueOf(4242),
            ((Map<Integer, Integer>) getField(backend, "execIdToPairId")).get(31)
        );
    }

    private void recordUnverifiedWithContinuation(Object backend, int execId, String reason)
        throws Exception {
        java.lang.reflect.Method m = backend.getClass().getDeclaredMethod(
            "recordUnverified", int.class, String.class, boolean.class
        );
        m.setAccessible(true);
        m.invoke(backend, execId, reason, true);
    }

    // ------------------------------------------------------------------
    // B3: admission must degrade on an unaccountable managed pod, and the
    // condition must be self-healing and fail-closed
    // ------------------------------------------------------------------

    @Test
    public void anUnaccountableManagedPodDegradesAdmission() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        setField(backend, "initialized", true);
        KubernetesClient client = Mockito.mock(KubernetesClient.class);
        setField(backend, "kubernetesClient", client);

        givenManagedPodListing(client, unlabelledManagedPod("stray-1", "Running"));
        invokePrivate(backend, "inventoryPodsWithoutJobs");

        assertTrue(
            "a running managed pod with no usable exec-id cannot hold a slot, so admission"
                + " itself has to be the thing that defers",
            admissionDegraded(backend)
        );
        assertFalse(
            "and the defer must be visible at the dispatch gate",
            backend.isQueueDispatchable("all.q")
        );
    }

    @Test
    public void aLaterSuccessfulInventoryClearsTheDegradedCondition() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        setField(backend, "initialized", true);
        KubernetesClient client = Mockito.mock(KubernetesClient.class);
        setField(backend, "kubernetesClient", client);

        givenManagedPodListing(client, unlabelledManagedPod("stray-2", "Running"));
        invokePrivate(backend, "inventoryPodsWithoutJobs");
        assertTrue(admissionDegraded(backend));

        // The object is gone and the listing succeeded: that is evidence, so the hold lifts
        // on its own without an operator having to restart anything.
        givenManagedPodListing(client);
        invokePrivate(backend, "inventoryPodsWithoutJobs");

        assertFalse("the condition must be self-healing", admissionDegraded(backend));
    }

    @Test
    public void aFailedInventoryLeavesTheDegradedConditionExactlyAsItWas() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        setField(backend, "initialized", true);
        KubernetesClient client = Mockito.mock(KubernetesClient.class);
        setField(backend, "kubernetesClient", client);

        givenManagedPodListing(client, unlabelledManagedPod("stray-3", "Running"));
        invokePrivate(backend, "inventoryPodsWithoutJobs");
        assertTrue(admissionDegraded(backend));

        // An unreadable listing disproves nothing. Clearing on it would turn a transient
        // API error into permission to dispatch beside an unaccountable solver.
        givenManagedPodListingFailure(client);
        invokePrivate(backend, "inventoryPodsWithoutJobs");

        assertTrue("a failed observation must never clear a safety hold",
            admissionDegraded(backend));
    }

    private boolean admissionDegraded(KubernetesNativeBackend backend) throws Exception {
        return ((java.util.concurrent.atomic.AtomicBoolean)
            getField(backend, "admissionDegraded")).get();
    }

    /** A managed pod carrying no exec-id label at all. */
    private Pod unlabelledManagedPod(String name, String phase) {
        return new PodBuilder()
            .withNewMetadata()
            .withName(name)
            .addToLabels("starexec.org/managed", "true")
            .endMetadata()
            .withNewSpec()
            .withNodeName("node-z")
            .endSpec()
            .withNewStatus()
            .withPhase(phase)
            .endStatus()
            .build();
    }

    /** PodPhaseView.list selects with withLabel(managed, "true"), not withLabels(map). */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void givenManagedPodListing(KubernetesClient client, Pod... pods) {
        MixedOperation podsOp = Mockito.mock(MixedOperation.class);
        NonNamespaceOperation namespacedPods = Mockito.mock(NonNamespaceOperation.class);
        io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable filtered =
            Mockito.mock(io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable.class);
        io.fabric8.kubernetes.api.model.PodList listing =
            new io.fabric8.kubernetes.api.model.PodList();
        listing.setItems(List.of(pods));
        Mockito.when(client.pods()).thenReturn(podsOp);
        Mockito.when(podsOp.inNamespace(Mockito.any())).thenReturn(namespacedPods);
        Mockito.when(namespacedPods.withLabel(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(filtered);
        Mockito.when(filtered.list()).thenReturn(listing);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void givenManagedPodListingFailure(KubernetesClient client) {
        MixedOperation podsOp = Mockito.mock(MixedOperation.class);
        NonNamespaceOperation namespacedPods = Mockito.mock(NonNamespaceOperation.class);
        io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable filtered =
            Mockito.mock(io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable.class);
        Mockito.when(client.pods()).thenReturn(podsOp);
        Mockito.when(podsOp.inNamespace(Mockito.any())).thenReturn(namespacedPods);
        Mockito.when(namespacedPods.withLabel(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(filtered);
        Mockito.when(filtered.list())
            .thenThrow(new io.fabric8.kubernetes.client.KubernetesClientException("boom"));
    }

    private void invokePrivate(Object target, String method) throws Exception {
        java.lang.reflect.Method m = target.getClass().getDeclaredMethod(method);
        m.setAccessible(true);
        m.invoke(target);
    }

    // ------------------------------------------------------------------
    // filesystem absence must fail closed
    // ------------------------------------------------------------------

    private boolean confirmedAbsent(KubernetesNativeBackend backend, Path path)
        throws Exception {
        java.lang.reflect.Method m =
            backend.getClass().getDeclaredMethod("confirmedAbsent", Path.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(backend, path);
    }

    @Test
    public void anAbsentFileIsProvenAbsent() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Path dir = java.nio.file.Files.createTempDirectory("starexec-absence");
        try {
            assertTrue(
                "a missing file in a readable directory is proven absent",
                confirmedAbsent(backend, dir.resolve("var.out"))
            );
        } finally {
            java.nio.file.Files.deleteIfExists(dir);
        }
    }

    @Test
    public void anExistingFileIsNotAbsent() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Path dir = java.nio.file.Files.createTempDirectory("starexec-absence");
        Path artifact = dir.resolve("var.out");
        java.nio.file.Files.writeString(artifact, "previous attempt");
        try {
            assertFalse(confirmedAbsent(backend, artifact));
        } finally {
            java.nio.file.Files.deleteIfExists(artifact);
            java.nio.file.Files.deleteIfExists(dir);
        }
    }

    /**
     * The case Files.exists gets wrong: it answers false for "absent" AND for "cannot tell",
     * so an unreadable output directory used to read as "no stale artifact here" and let a
     * previous attempt's var.out be scored as this attempt's result.
     */
    @Test
    public void anUnreadableDirectoryIsNotTreatedAsAbsence() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Path dir = java.nio.file.Files.createTempDirectory("starexec-absence");
        Path artifact = dir.resolve("var.out");
        java.nio.file.Files.writeString(artifact, "previous attempt");

        // An unsearchable parent makes the artifact's existence genuinely undecidable.
        // (A regular file standing in for a directory does NOT work: the JDK maps ENOTDIR to
        // NoSuchFileException, which is a legitimate proof of absence.)
        try {
            java.nio.file.Files.setPosixFilePermissions(
                dir, java.nio.file.attribute.PosixFilePermissions.fromString("---------")
            );
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.Assume.assumeNoException("POSIX permissions unavailable", e);
        }
        try {
            // Root ignores the permission bits, so confirm the barrier is real before
            // asserting on it -- otherwise this test would pass for the wrong reason.
            boolean barrierHolds;
            try {
                java.nio.file.Files.readAttributes(
                    artifact,
                    java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS
                );
                barrierHolds = false;
            } catch (java.nio.file.NoSuchFileException e) {
                barrierHolds = false;
            } catch (java.io.IOException e) {
                barrierHolds = true;
            }
            org.junit.Assume.assumeTrue(
                "this JVM can read through a 000 directory (running as root?)", barrierHolds
            );

            assertFalse(
                "existence that cannot be determined must never be reported as absence",
                confirmedAbsent(backend, artifact)
            );
        } finally {
            java.nio.file.Files.setPosixFilePermissions(
                dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
            );
            java.nio.file.Files.deleteIfExists(artifact);
            java.nio.file.Files.deleteIfExists(dir);
        }
    }

    /** A dangling symlink is itself a surviving artifact; NOFOLLOW_LINKS must see it. */
    @Test
    public void aSymlinkWhereAnArtifactBelongsIsNotAbsent() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Path dir = java.nio.file.Files.createTempDirectory("starexec-absence");
        Path link = dir.resolve("var.out");
        try {
            java.nio.file.Files.createSymbolicLink(link, dir.resolve("nowhere"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.Assume.assumeNoException("symlinks unavailable on this filesystem", e);
        }
        try {
            assertFalse(
                "a symlink standing where an artifact belongs is a surviving artifact",
                confirmedAbsent(backend, link)
            );
        } finally {
            java.nio.file.Files.deleteIfExists(link);
            java.nio.file.Files.deleteIfExists(dir);
        }
    }

    @Test
    public void aKillIsUnprovenWhenPodsCannotBeListed() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(9, "job-9");
        givenJobDeletion(backend, "job-9", true, null);
        givenPodListingFails((KubernetesClient) getField(backend, "kubernetesClient"));
        restore(backend, 9);

        assertEquals(Backend.KillOutcome.UNPROVEN, killConfirmed(backend, 9));
        assertTrue(holdingSlot(backend).contains(9));
    }

    @Test
    public void aMissingJobNameIsNotProofOfSafety() throws Exception {
        // Absent local bookkeeping is exactly the post-restart state, which is when a pod
        // is most likely running unseen. The cluster must still be asked.
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        givenJobDeletion(backend, "starexec-pair-0-exec-10", true, null, podInPhase(10, "Running"));

        assertEquals(
            "a Running pod carrying the exec-id label means this is not safe,"
                + " whatever local tracking says",
            Backend.KillOutcome.UNPROVEN,
            killConfirmed(backend, 10)
        );
        assertTrue(
            "a discovered live execution must enter the accounting",
            holdingSlot(backend).contains(10)
        );
        assertEquals(1, activeCount(backend));
        // Identity is the pod's exec-id LABEL, not a reconstructed Job name. A name cannot
        // be reconstructed -- generateJobName mixes in System.currentTimeMillis() -- and an
        // earlier draft of this code fabricated one anyway, which would have reported a
        // live Job as absent. The retry owner is the recurring pod inventory, which selects
        // by that label.
        assertNull(
            "no Job name may be invented for an execution whose name was lost",
            trackingMap(backend, "execIdToJobName").get(10)
        );
    }

    @Test
    public void aConfirmedSafeMissingJobNameClearsEverySurvivingTrace() throws Exception {
        // Deliberately inconsistent partial state: the job-name entry is gone but the pair
        // and output-dir entries and the reservation all survived. Partial loss is not
        // selective, so confirmation must clean all of it, idempotently.
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        ((Map<Integer, Integer>) getField(backend, "execIdToPairId")).put(11, 511);
        ((Map<Integer, Path>) getField(backend, "execIdToOutputDir"))
            .put(11, Path.of("/tmp/out/11"));
        givenJobDeletion(backend, "starexec-pair-0-exec-11", true, null);
        restore(backend, 11);

        assertEquals(Backend.KillOutcome.CONFIRMED_SAFE, killConfirmed(backend, 11));
        assertFalse("the surviving reservation must go", holdingSlot(backend).contains(11));
        assertNull(trackingMap(backend, "execIdToPairId").get(11));
        assertNull(trackingMap(backend, "execIdToOutputDir").get(11));
        assertEquals(0, activeCount(backend));
    }

    @Test
    public void discoveredWorkIsAccountedEvenAboveTheCap() throws Exception {
        // The cap limits what StarExec may START, never what it may admit EXISTS.
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "maxConcurrentJobs", 1);
        restore(backend, 10);
        restore(backend, 11);

        assertEquals("both live executions must be counted", 2, holdingSlot(backend).size());
        assertEquals("the counter must agree with the set", 2, activeCount(backend));
    }

    @Test
    public void newWorkStillRespectsTheCap() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "maxConcurrentJobs", 1);
        assertTrue(acquire(backend, 20));
        assertFalse("a second NEW submission must not fit", acquire(backend, 21));
        assertEquals(1, holdingSlot(backend).size());
        assertEquals(1, activeCount(backend));
    }

    private boolean acquire(KubernetesNativeBackend backend, int execId) throws Exception {
        Method m = KubernetesNativeBackend.class
            .getDeclaredMethod("tryAcquireSubmissionSlot", int.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(backend, execId);
    }

    private void restore(KubernetesNativeBackend backend, int execId) throws Exception {
        Method m = KubernetesNativeBackend.class
            .getDeclaredMethod("restoreSubmissionSlot", int.class);
        m.setAccessible(true);
        m.invoke(backend, execId);
    }

    /** The common case: a deletion with no surviving pods for the execution. */
    private void givenJobDeletion(
        KubernetesNativeBackend backend,
        String jobName,
        boolean deleted,
        Job stillThere
    ) throws Exception {
        givenJobDeletion(backend, jobName, deleted, stillThere, new Pod[0]);
    }

    /**
     * As above, but with the pods a census will find.
     *
     * <p>The pods branch is not optional decoration. Without it {@code client.pods()}
     * returns null, {@code PodPhaseView} swallows the NPE as an unavailable listing, and
     * every fail-closed rule reads that as UNDETERMINED — so a test that meant to assert
     * the happy path would silently assert the held path instead.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void givenJobDeletion(
        KubernetesNativeBackend backend,
        String jobName,
        boolean deleted,
        Job stillThere,
        Pod... podsForExecution
    ) throws Exception {
        KubernetesClient client = Mockito.mock(KubernetesClient.class);
        BatchAPIGroupDSL batch = Mockito.mock(BatchAPIGroupDSL.class);
        V1BatchAPIGroupDSL v1 = Mockito.mock(V1BatchAPIGroupDSL.class);
        MixedOperation<Job, JobList, ScalableResource<Job>> jobs =
            Mockito.mock(MixedOperation.class);
        NonNamespaceOperation<Job, JobList, ScalableResource<Job>> namespaced =
            Mockito.mock(NonNamespaceOperation.class);
        ScalableResource<Job> resource = Mockito.mock(ScalableResource.class);
        io.fabric8.kubernetes.client.GracePeriodConfigurable policyApplied =
            Mockito.mock(io.fabric8.kubernetes.client.GracePeriodConfigurable.class);

        Mockito.when(client.batch()).thenReturn(batch);
        Mockito.when(batch.v1()).thenReturn(v1);
        Mockito.when(v1.jobs()).thenReturn(jobs);
        Mockito.when(jobs.inNamespace(Mockito.any())).thenReturn(namespaced);
        Mockito.when(namespaced.withName(jobName)).thenReturn(resource);
        // doReturn, not when(...).thenReturn: withPropagationPolicy is declared
        // PropagationPolicyConfigurable<T> and the wildcard makes thenReturn unassignable.
        Mockito
            .doReturn(policyApplied)
            .when(resource)
            .withPropagationPolicy(io.fabric8.kubernetes.api.model.DeletionPropagation.FOREGROUND);
        Mockito
            .when(policyApplied.delete())
            .thenReturn(deleted ? List.of(new StatusDetails()) : List.of());
        Mockito
            .when(resource.delete())
            .thenReturn(deleted ? List.of(new StatusDetails()) : List.of());
        Mockito.when(resource.get()).thenReturn(stillThere);

        // The controller half. Without this stub jobs().inNamespace(..).withLabels(..)
        // returns null, observeControllerFor swallows the NPE as an unreadable API, and
        // every one of these tests would assert UNPROVEN no matter what it meant to assert.
        //
        // Default is the Job the delete targeted, if it survived, and nothing otherwise —
        // so "deleted, no pods" still means "no controller can create another pod".
        givenJobsFor(client, stillThere == null ? new Job[0] : new Job[] { stillThere });

        givenPodsFor(client, podsForExecution);
        setField(backend, "kubernetesClient", client);
    }

    /**
     * A cluster in which nothing for any execution survives: the Job listing succeeds and
     * returns none, and no pod matches either.
     *
     * <p>Needed by any test that expects a terminal callback to release accounting. A
     * completion no longer clears tracking on its own say-so — it must first establish that
     * no controller can create another Pod and no Pod can still write. With no client stubbed
     * at all, neither can be established and the accounting is correctly retained, so a test
     * asserting the released state has to say what the cluster looks like.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void givenSafeCluster(KubernetesNativeBackend backend) throws Exception {
        KubernetesClient client = Mockito.mock(KubernetesClient.class);
        BatchAPIGroupDSL batch = Mockito.mock(BatchAPIGroupDSL.class);
        V1BatchAPIGroupDSL v1 = Mockito.mock(V1BatchAPIGroupDSL.class);
        MixedOperation jobs = Mockito.mock(MixedOperation.class);
        NonNamespaceOperation namespaced = Mockito.mock(NonNamespaceOperation.class);

        Mockito.when(client.batch()).thenReturn(batch);
        Mockito.when(batch.v1()).thenReturn(v1);
        Mockito.when(v1.jobs()).thenReturn(jobs);
        Mockito.when(jobs.inNamespace(Mockito.any())).thenReturn(namespaced);

        givenJobsFor(client);
        givenPodsFor(client);
        setField(backend, "kubernetesClient", client);
    }

    /**
     * Stubs {@code client.batch().v1().jobs().inNamespace(..).withLabels(..).list()}.
     *
     * <p>This is how an execution's controllers are found: by label, never by a
     * reconstructed name. Pass no jobs for "the listing succeeded and found none", which is
     * positive evidence of controller absence.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void givenJobsFor(KubernetesClient client, Job... jobs) {
        NonNamespaceOperation namespaced =
            client.batch().v1().jobs().inNamespace("any");
        io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable filtered =
            Mockito.mock(io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable.class);

        JobList listing = new JobList();
        listing.setItems(List.of(jobs));

        Mockito.when(namespaced.withLabels(Mockito.anyMap())).thenReturn(filtered);
        Mockito.when(filtered.list()).thenReturn(listing);
    }

    /** Makes the Job listing fail, so controller safety can never be established. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void givenJobListingFails(KubernetesClient client) {
        NonNamespaceOperation namespaced =
            client.batch().v1().jobs().inNamespace("any");
        io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable filtered =
            Mockito.mock(io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable.class);
        Mockito.when(namespaced.withLabels(Mockito.anyMap())).thenReturn(filtered);
        Mockito.when(filtered.list()).thenThrow(new RuntimeException("jobs is forbidden"));
    }

    /**
     * A Job carrying the identity labels, with one condition.
     *
     * @param type   {@code Complete} or {@code Failed} make it spent; anything else
     *               (notably {@code FailureTarget}) must not
     * @param status {@code True} / {@code False}
     */
    private Job jobWithCondition(int execId, String type, String status) {
        Job job = jobWithNoConditions(execId);
        io.fabric8.kubernetes.api.model.batch.v1.JobCondition condition =
            new io.fabric8.kubernetes.api.model.batch.v1.JobCondition();
        condition.setType(type);
        condition.setStatus(status);
        job.getStatus().setConditions(List.of(condition));
        return job;
    }

    /** A live Job: identity labels, a status, but no terminal condition. */
    private Job jobWithNoConditions(int execId) {
        java.util.Map<String, String> labels = new java.util.HashMap<>();
        labels.put("starexec.org/managed", "true");
        labels.put("starexec.org/exec-id", String.valueOf(execId));
        labels.put("starexec.org/pair-id", String.valueOf(execId * 10));
        Job job = new Job();
        job.setMetadata(
            new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
                .withName("job-" + execId)
                .withLabels(labels)
                .build()
        );
        job.setStatus(new io.fabric8.kubernetes.api.model.batch.v1.JobStatus());
        return job;
    }

    /** Stubs {@code client.pods().inNamespace(..).withLabels(..).list()}. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void givenPodsFor(KubernetesClient client, Pod... pods) {
        MixedOperation podsOp = Mockito.mock(MixedOperation.class);
        NonNamespaceOperation namespacedPods = Mockito.mock(NonNamespaceOperation.class);
        io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable filtered =
            Mockito.mock(io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable.class);

        io.fabric8.kubernetes.api.model.PodList listing =
            new io.fabric8.kubernetes.api.model.PodList();
        listing.setItems(List.of(pods));

        Mockito.when(client.pods()).thenReturn(podsOp);
        Mockito.when(podsOp.inNamespace(Mockito.any())).thenReturn(namespacedPods);
        Mockito.when(namespacedPods.withLabels(Mockito.anyMap())).thenReturn(filtered);
        Mockito.when(filtered.list()).thenReturn(listing);
    }

    /** Makes every pod listing fail, so a census can only report UNDETERMINED. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void givenPodListingFails(KubernetesClient client) {
        MixedOperation podsOp = Mockito.mock(MixedOperation.class);
        NonNamespaceOperation namespacedPods = Mockito.mock(NonNamespaceOperation.class);
        io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable filtered =
            Mockito.mock(io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable.class);
        Mockito.when(client.pods()).thenReturn(podsOp);
        Mockito.when(podsOp.inNamespace(Mockito.any())).thenReturn(namespacedPods);
        Mockito.when(namespacedPods.withLabels(Mockito.anyMap())).thenReturn(filtered);
        Mockito.when(filtered.list()).thenThrow(new RuntimeException("pods is forbidden"));
    }

    /**
     * A pod carrying the managed, exec-id and pair-id labels, in the given phase.
     *
     * <p>The pair-id label matters now that startup reconciliation censuses by pair — a pair
     * with no surviving Job has no execution id to offer. Kept consistent with
     * {@link #jobWithNoConditions}: pair id is exec id times ten.
     */
    private Pod podInPhase(int execId, String phase) {
        java.util.Map<String, String> labels = new java.util.HashMap<>();
        labels.put("starexec.org/managed", "true");
        labels.put("starexec.org/exec-id", String.valueOf(execId));
        labels.put("starexec.org/pair-id", String.valueOf(execId * 10));
        Pod pod = new Pod();
        pod.setMetadata(
            new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
                .withName("pod-" + execId)
                .withLabels(labels)
                .build()
        );
        io.fabric8.kubernetes.api.model.PodStatus status =
            new io.fabric8.kubernetes.api.model.PodStatus();
        status.setPhase(phase);
        pod.setStatus(status);
        return pod;
    }

    private Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    /** A pod listing containing one pod for {@code execId} in the given phase. */
    private PodPhaseView viewWithPod(int execId, String phase) {
        return PodPhaseView.of(
            List.of(
                new PodBuilder()
                    .withNewMetadata()
                        .withName("pod-" + execId)
                        .withCreationTimestamp("2026-08-15T12:00:00Z")
                        .addToLabels("starexec.org/exec-id", String.valueOf(execId))
                    .endMetadata()
                    .withNewStatus()
                        .withPhase(phase)
                    .endStatus()
                    .build()
            ),
            "starexec.org/exec-id"
        );
    }

    /**
     * The identity a callback event carries.
     *
     * <p>The UID is synthesised from the execution id here because these tests exercise the
     * backend's handling, not Kubernetes' allocation. What matters is that two executions
     * sharing an id can be told apart, and {@link ExecutionIdentityCollisionTests} is where
     * that is asserted.
     */
    private static ExecutionRef execution(int execId, String jobName) {
        return new ExecutionRef(execId, jobName, "uid-" + jobName);
    }

    private KubernetesJobMonitor.JobCompletionCallback instantiateCompletionCallback(
        KubernetesNativeBackend backend
    ) throws Exception {
        Class<?> callbackClass = Class.forName(
            "org.starexec.backend.KubernetesNativeBackend$KubernetesJobCompletionCallback"
        );
        Constructor<?> constructor = callbackClass.getDeclaredConstructor(
            KubernetesNativeBackend.class
        );
        constructor.setAccessible(true);
        return (KubernetesJobMonitor.JobCompletionCallback) constructor.newInstance(
            backend
        );
    }

    /**
     * Builds a FOUND lookup result for callback tests. Reflection is required
     * because PairStatusLookupResult has no public constructor or factory.
     */
    /**
     * A pod that never started is recorded as ERROR_RUNSCRIPT so RERUN_FAILED_PAIRS picks
     * it up — but that task's query also demands a non-null end_time, so without setEndTime
     * the pair would sit failed and never be rerun. This test is the guard on that.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void stuckPendingPublishesNothingWhileThePodMayStillRun() throws Exception {
        // The status this path writes -- ERROR_RUNSCRIPT with an end_time -- is exactly what
        // makes a pair rerun-eligible. Publishing it while a pod for the execution can still
        // start would let a late pod write results over its own replacement's.
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");

        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(41, "job-41");
        ((Map<Integer, Integer>) getField(backend, "execIdToPairId")).put(41, 441);
        restore(backend, 41);

        // Named Job deleted, but a pod carrying the exec-id label is still Pending.
        givenJobDeletion(backend, "job-41", true, null, podInPhase(41, "Pending"));

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(441))
                .thenReturn(foundLookup(StatusCode.STATUS_ENQUEUED.getVal()));

            assertFalse(
                "the escalation must report incomplete so the monitor retries it",
                callback.onJobStuckPending(execution(41, "job-41"), "not scheduled")
            );

            jobPairsMock.verify(
                () ->
                    JobPairs.setPairStatusPreciseResult(
                        Mockito.anyInt(),
                        Mockito.anyInt(),
                        Mockito.anyInt(),
                        Mockito.anyInt(),
                        Mockito.anyBoolean()
                    ),
                Mockito.never()
            );
            jobPairsMock.verify(() -> JobPairs.setEndTime(Mockito.anyInt()), Mockito.never());
        }

        assertTrue(
            "the slot stays held while the pod may run",
            holdingSlot(backend).contains(41)
        );
        assertFalse(
            "and the obligation is recorded for the sweep",
            ((Map<Integer, ?>) getField(backend, "unverifiedExecutions")).isEmpty()
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    public void stuckPendingPairGetsAnEndTimeSoTheRerunCanFindIt() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Map<Integer, String> execToJob =
            (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) getField(backend, "execIdToPairId");
        Map<Integer, Path> execToOut =
            (Map<Integer, Path>) getField(backend, "execIdToOutputDir");
        Set<Integer> holdingSlot = (Set<Integer>) getField(backend, "jobsHoldingSlot");
        AtomicInteger activeJobCount =
            (AtomicInteger) getField(backend, "activeJobCount");

        execToJob.put(31, "job-31");
        execToPair.put(31, 431);
        execToOut.put(31, Path.of("/tmp/output/31"));
        holdingSlot.add(31);
        activeJobCount.set(1);
        givenJobDeletion(backend, "job-31", true, null);

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(431))
                .thenReturn(foundLookup(StatusCode.STATUS_ENQUEUED.getVal()));
            jobPairsMock
                .when(
                    () ->
                        JobPairs.setPairStatusPreciseResult(
                            431,
                            1,
                            StatusCode.ERROR_RUNSCRIPT.getVal(),
                            StatusCode.STATUS_NOT_REACHED.getVal(),
                            false
                        )
                )
                .thenReturn(PairStatusResult.APPLIED);
            jobPairsMock.when(() -> JobPairs.setEndTime(431)).thenReturn(true);

            assertTrue(
                callback.onJobStuckPending(
                    execution(31, "job-31"),
                    "not scheduled (Unschedulable): 0/6 nodes are available"
                )
            );

            jobPairsMock.verify(
                () ->
                    JobPairs.setPairStatusPreciseResult(
                        431,
                        1,
                        StatusCode.ERROR_RUNSCRIPT.getVal(),
                        StatusCode.STATUS_NOT_REACHED.getVal(),
                        false
                    )
            );
            jobPairsMock.verify(() -> JobPairs.setEndTime(431));
        }

        assertTrue(execToJob.isEmpty());
        assertTrue(execToPair.isEmpty());
        assertTrue(execToOut.isEmpty());
    }

    /**
     * The slot a stuck pair holds is the reason one mislabelled queue can wedge the whole
     * backend: at maxConcurrentJobs held slots, submitScript rejects everything and
     * JobManager turns each rejection into a terminal error.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void stuckPendingReleasesTheConcurrencySlot() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Map<Integer, String> execToJob =
            (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) getField(backend, "execIdToPairId");
        Set<Integer> holdingSlot = (Set<Integer>) getField(backend, "jobsHoldingSlot");
        AtomicInteger activeJobCount =
            (AtomicInteger) getField(backend, "activeJobCount");

        execToJob.put(32, "job-32");
        execToPair.put(32, 432);
        holdingSlot.add(32);
        activeJobCount.set(1);
        givenJobDeletion(backend, "job-32", true, null);

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(432))
                .thenReturn(foundLookup(StatusCode.STATUS_ENQUEUED.getVal()));
            jobPairsMock
                .when(
                    () ->
                        JobPairs.setPairStatusPreciseResult(
                            432,
                            1,
                            StatusCode.ERROR_RUNSCRIPT.getVal(),
                            StatusCode.STATUS_NOT_REACHED.getVal(),
                            false
                        )
                )
                .thenReturn(PairStatusResult.APPLIED);
            jobPairsMock.when(() -> JobPairs.setEndTime(432)).thenReturn(true);

            assertTrue(callback.onJobStuckPending(execution(32, "job-32"), "Unschedulable"));
        }

        assertEquals(0, activeJobCount.get());
        assertTrue(holdingSlot.isEmpty());
    }

    /**
     * A failed status write must not consume the slot or the tracking, or the pair would
     * be dropped without ever reaching a status a rerun can find.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void stuckPendingIsRetriedWhenTheStatusWriteFails() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Map<Integer, String> execToJob =
            (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) getField(backend, "execIdToPairId");
        Set<Integer> holdingSlot = (Set<Integer>) getField(backend, "jobsHoldingSlot");
        AtomicInteger activeJobCount =
            (AtomicInteger) getField(backend, "activeJobCount");

        execToJob.put(33, "job-33");
        execToPair.put(33, 433);
        holdingSlot.add(33);
        activeJobCount.set(1);
        givenJobDeletion(backend, "job-33", true, null);

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(433))
                .thenReturn(foundLookup(StatusCode.STATUS_ENQUEUED.getVal()));
            jobPairsMock
                .when(
                    () ->
                        JobPairs.setPairStatusPreciseResult(
                            433,
                            1,
                            StatusCode.ERROR_RUNSCRIPT.getVal(),
                            StatusCode.STATUS_NOT_REACHED.getVal(),
                            false
                        )
                )
                .thenReturn(PairStatusResult.FAILED, PairStatusResult.APPLIED);

            assertFalse(callback.onJobStuckPending(execution(33, "job-33"), "Unschedulable"));
            assertEquals("job-33", execToJob.get(33));
            assertEquals(1, activeJobCount.get());

            jobPairsMock.when(() -> JobPairs.setEndTime(433)).thenReturn(true);
            assertTrue(callback.onJobStuckPending(execution(33, "job-33"), "Unschedulable"));
        }

        assertEquals(0, activeJobCount.get());
        assertTrue(holdingSlot.isEmpty());
        assertTrue(execToJob.isEmpty());
    }

    /**
     * The Kubernetes Job is this callback's only retry trigger — the monitor iterates Jobs
     * the API returns — so it must outlive every step that can fail. Deleting it first and
     * then failing the end-time write would return false while nothing could ever bring
     * the pair back: it would keep its old status and hold its slot forever.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void stuckPendingKeepsTheJobWhenTheEndTimeWriteFails() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Map<Integer, String> execToJob =
            (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) getField(backend, "execIdToPairId");
        Set<Integer> holdingSlot = (Set<Integer>) getField(backend, "jobsHoldingSlot");
        AtomicInteger activeJobCount =
            (AtomicInteger) getField(backend, "activeJobCount");

        execToJob.put(34, "job-34");
        execToPair.put(34, 434);
        holdingSlot.add(34);
        activeJobCount.set(1);
        givenJobDeletion(backend, "job-34", true, null);

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(434))
                .thenReturn(foundLookup(StatusCode.STATUS_ENQUEUED.getVal()));
            jobPairsMock
                .when(
                    () ->
                        JobPairs.setPairStatusPreciseResult(
                            434,
                            1,
                            StatusCode.ERROR_RUNSCRIPT.getVal(),
                            StatusCode.STATUS_NOT_REACHED.getVal(),
                            false
                        )
                )
                .thenReturn(PairStatusResult.APPLIED);
            jobPairsMock.when(() -> JobPairs.setEndTime(434)).thenReturn(false);

            assertFalse(callback.onJobStuckPending(execution(34, "job-34"), "Unschedulable"));

            // Nothing was deleted and nothing was released, so the next poll sees the Job
            // again and retries.
            jobPairsMock.verify(() -> JobPairs.setEndTime(434));
        }

        assertEquals("job-34", execToJob.get(34));
        assertEquals(1, activeJobCount.get());
        assertTrue(holdingSlot.contains(34));
    }

    /**
     * The invariant: a pair must not become rerun-eligible while its Job still exists.
     *
     * <p>ERROR_RUNSCRIPT plus a non-null end_time is what
     * GetJobPairIdsWithStatusNotRerunAfterDate selects, and nothing kills the old
     * execution when RERUN_FAILED_PAIRS dispatches a new one — rerunPairsBatch only calls
     * killPair for status &lt; STATUS_COMPLETE, and ERROR_RUNSCRIPT is 11. So if deletion
     * fails, neither write may happen at all.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void stuckPendingKeepsThePairWhenTheJobCannotBeDeleted() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Map<Integer, String> execToJob =
            (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) getField(backend, "execIdToPairId");
        Set<Integer> holdingSlot = (Set<Integer>) getField(backend, "jobsHoldingSlot");
        AtomicInteger activeJobCount =
            (AtomicInteger) getField(backend, "activeJobCount");

        execToJob.put(35, "job-35");
        execToPair.put(35, 435);
        holdingSlot.add(35);
        activeJobCount.set(1);
        // Delete reports nothing, and the Job is still there afterwards.
        givenJobDeletion(backend, "job-35", false, new JobBuilder().build());

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(435))
                .thenReturn(foundLookup(StatusCode.STATUS_ENQUEUED.getVal()));
            jobPairsMock
                .when(
                    () ->
                        JobPairs.setPairStatusPreciseResult(
                            435,
                            1,
                            StatusCode.ERROR_RUNSCRIPT.getVal(),
                            StatusCode.STATUS_NOT_REACHED.getVal(),
                            false
                        )
                )
                .thenReturn(PairStatusResult.APPLIED);
            jobPairsMock.when(() -> JobPairs.setEndTime(435)).thenReturn(true);

            assertFalse(callback.onJobStuckPending(execution(35, "job-35"), "Unschedulable"));

            // Neither write may have happened: together they are precisely what makes the
            // pair eligible for an automatic rerun, and the old pod is still out there.
            jobPairsMock.verify(
                () ->
                    JobPairs.setPairStatusPreciseResult(
                        435,
                        1,
                        StatusCode.ERROR_RUNSCRIPT.getVal(),
                        StatusCode.STATUS_NOT_REACHED.getVal(),
                        false
                    ),
                Mockito.never()
            );
            jobPairsMock.verify(() -> JobPairs.setEndTime(435), Mockito.never());
        }

        assertEquals(1, activeJobCount.get());
        assertTrue(holdingSlot.contains(35));
    }

    /**
     * A Job that vanished between the listing and the delete is gone, not undeletable.
     * Treating an empty delete result as failure would strand the pair forever.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void stuckPendingAcceptsAJobThatHadAlreadyVanished() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Map<Integer, String> execToJob =
            (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) getField(backend, "execIdToPairId");
        Set<Integer> holdingSlot = (Set<Integer>) getField(backend, "jobsHoldingSlot");
        AtomicInteger activeJobCount =
            (AtomicInteger) getField(backend, "activeJobCount");

        execToJob.put(36, "job-36");
        execToPair.put(36, 436);
        holdingSlot.add(36);
        activeJobCount.set(1);
        // Delete reports nothing because it was already gone; the read confirms it.
        givenJobDeletion(backend, "job-36", false, null);

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(436))
                .thenReturn(foundLookup(StatusCode.STATUS_ENQUEUED.getVal()));
            jobPairsMock
                .when(
                    () ->
                        JobPairs.setPairStatusPreciseResult(
                            436,
                            1,
                            StatusCode.ERROR_RUNSCRIPT.getVal(),
                            StatusCode.STATUS_NOT_REACHED.getVal(),
                            false
                        )
                )
                .thenReturn(PairStatusResult.APPLIED);
            jobPairsMock.when(() -> JobPairs.setEndTime(436)).thenReturn(true);

            assertTrue(callback.onJobStuckPending(execution(36, "job-36"), "Unschedulable"));
        }

        assertEquals(0, activeJobCount.get());
        assertTrue(holdingSlot.isEmpty());
    }

    /**
     * Membership of the default queue is the absence of a queue label, which a
     * nodeSelector cannot express. Without this a default-queue pair selects on the worker
     * label alone and can be scheduled onto hardware another queue has claimed — the same
     * defect the queue selector exists to prevent, in the one case it could not state.
     */
    @Test
    public void defaultQueuePodsAreConfinedToNodesNoOtherQueueClaims() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "queueLabelKey", "starexec.org/queue");

        Method defaultQueueAffinity =
            KubernetesNativeBackend.class.getDeclaredMethod("defaultQueueAffinity");
        defaultQueueAffinity.setAccessible(true);
        Affinity affinity = (Affinity) defaultQueueAffinity.invoke(backend);

        NodeSelectorRequirement requirement = affinity
            .getNodeAffinity()
            .getRequiredDuringSchedulingIgnoredDuringExecution()
            .getNodeSelectorTerms()
            .get(0)
            .getMatchExpressions()
            .get(0);

        assertEquals("starexec.org/queue", requirement.getKey());
        assertEquals("DoesNotExist", requirement.getOperator());
    }

    /**
     * An empty queue view because no listing has ever succeeded is not evidence that a
     * queue has no nodes. Reading it as such turns a transient API failure at startup into
     * a terminal status on every pair dispatched in that window.
     */
    @Test
    public void queuesAreDeferredNotRejectedBeforeTheViewHasEverLoaded() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "initialized", true);

        // queueViewLoaded is still false and both sets are empty, exactly as after a
        // failed first refresh.
        assertFalse(backend.isQueueDispatchable("all.q"));
    }

    @Test
    public void aLoadedViewStillReportsAnUnlabelledQueueAsDispatchable() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "initialized", true);
        setField(backend, "queueViewLoaded", true);
        setField(backend, "queueViewRefreshedAt", System.currentTimeMillis());

        // Known state, queue genuinely absent: let it reach submitScript and be rejected
        // there, where the misconfiguration is visible.
        assertTrue(backend.isQueueDispatchable("all.q"));
    }

    /**
     * Both runbooks tell operators to label workers starexec/queue=default, so every
     * already-deployed node carries that spelling. Reading it as a queue in its own right
     * puts those nodes in a queue no pair is ever submitted to, while all.q reports no
     * nodes and rejects everything.
     */
    @Test
    public void theLegacyDefaultQueueLabelIsReadAsTheCanonicalQueue() throws Exception {
        Method normalize = KubernetesNativeBackend.class
            .getDeclaredMethod("normalizeQueueLabel", String.class);
        normalize.setAccessible(true);

        assertEquals("all.q", normalize.invoke(null, "default"));
        assertEquals("all.q", normalize.invoke(null, "all"));
        assertEquals("all.q", normalize.invoke(null, "all.q"));
        assertEquals("all.q", normalize.invoke(null, (Object) null));
        assertEquals("all.q", normalize.invoke(null, "   "));
        // A real queue name is left alone.
        assertEquals("sat-comp", normalize.invoke(null, "sat-comp"));
    }

    /**
     * A legacy-labelled node carries the queue label, so a DoesNotExist term alone would
     * exclude precisely the workers the default queue is supposed to use.
     */
    @Test
    public void defaultQueueAffinityAlsoMatchesLegacyLabelledNodes() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "queueLabelKey", "starexec.org/queue");

        Method defaultQueueAffinity =
            KubernetesNativeBackend.class.getDeclaredMethod("defaultQueueAffinity");
        defaultQueueAffinity.setAccessible(true);
        Affinity affinity = (Affinity) defaultQueueAffinity.invoke(backend);

        List<NodeSelectorTerm> terms = affinity
            .getNodeAffinity()
            .getRequiredDuringSchedulingIgnoredDuringExecution()
            .getNodeSelectorTerms();

        // Terms are OR'd: absent label, or one of the default spellings.
        assertEquals(2, terms.size());
        assertEquals(
            "DoesNotExist",
            terms.get(0).getMatchExpressions().get(0).getOperator()
        );

        NodeSelectorRequirement legacy = terms.get(1).getMatchExpressions().get(0);
        assertEquals("In", legacy.getOperator());
        assertTrue(legacy.getValues().contains("default"));
        assertTrue(legacy.getValues().contains("all.q"));
        // normalizeQueueLabel treats a present-but-empty label as the default queue, and
        // Kubernetes permits one, so the scheduler must accept it too. Otherwise the
        // backend reports the queue schedulable while its pods never place.
        assertTrue(legacy.getValues().contains(""));
    }

    /**
     * The queue view and the affinity must classify a node the same way. Any label value
     * the view maps to the default queue has to be one the affinity accepts.
     */
    @Test
    public void everyLabelTheViewCallsDefaultIsAcceptedByTheAffinity() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "queueLabelKey", "starexec.org/queue");

        Method normalize = KubernetesNativeBackend.class
            .getDeclaredMethod("normalizeQueueLabel", String.class);
        normalize.setAccessible(true);
        Method defaultQueueAffinity =
            KubernetesNativeBackend.class.getDeclaredMethod("defaultQueueAffinity");
        defaultQueueAffinity.setAccessible(true);

        List<String> accepted = ((Affinity) defaultQueueAffinity.invoke(backend))
            .getNodeAffinity()
            .getRequiredDuringSchedulingIgnoredDuringExecution()
            .getNodeSelectorTerms()
            .get(1)
            .getMatchExpressions()
            .get(0)
            .getValues();

        // Both directions, and no silent skip. This loop used to be wrapped in a bare
        // `if (normalize(label).equals("all.q"))`, which meant the one case it was written
        // to cover -- 'ALL.Q', where the view is case-sensitive and isDefaultQueueName is
        // not -- fell through the gate and asserted nothing at all. A test that appears to
        // cover a case and does not is worse than no test, so the counters below fail the
        // test if either branch stops being exercised.
        int defaultLabels = 0;
        int nonDefaultLabels = 0;
        for (String label : List.of("", "   ", "default", "all", "all.q", "ALL.Q", "sat-comp")) {
            String normalized = (String) normalize.invoke(null, label);
            if ("all.q".equals(normalized)) {
                defaultLabels++;
                assertTrue(
                    "affinity does not accept a label the view calls default: '" + label + "'",
                    accepted.contains(label.trim())
                );
            } else {
                nonDefaultLabels++;
                // The converse matters just as much: if the affinity accepted a label the
                // view treats as a distinct queue, default-queue pods would be scheduled
                // onto hardware reserved for that queue.
                assertFalse(
                    "affinity accepts '" + label + "', which the view calls queue '"
                        + normalized + "', not the default",
                    accepted.contains(label.trim())
                );
            }
        }
        assertTrue("vacuous: no default-mapping label exercised", defaultLabels > 0);
        assertTrue("vacuous: no distinct-queue label exercised", nonDefaultLabels > 0);
    }

    private JobPairs.PairStatusLookupResult foundLookup(int statusCode)
        throws Exception {
        Constructor<JobPairs.PairStatusLookupResult> constructor =
            JobPairs.PairStatusLookupResult.class.getDeclaredConstructor(
                PairStatusLookupState.class,
                int.class
            );
        constructor.setAccessible(true);
        return constructor.newInstance(PairStatusLookupState.FOUND, statusCode);
    }

    // ---------------------------------------------------------------------
    // strictOnePairPerCpu
    //
    // This used to force cpuLimit to "1" whenever the flag was set, discarding
    // the operator's value with only a log line. Production sets
    // STAREXEC_K8S_CPU_LIMIT=32 (a whole compute node) together with the flag,
    // so the intent -- one pair per node -- became a one-CPU request, and what
    // actually kept pairs apart was the 250Gi memory request exhausting the
    // node. Isolation by accident, which vanishes if that limit is lowered.
    //
    // The flag now asserts the request CAN yield exclusive cores instead of
    // shrinking it: Kubernetes pins a Guaranteed pod only when its cpu request
    // is a whole number.
    // ---------------------------------------------------------------------

    private boolean isWholeNumberCpuQuantity(String quantity) throws Exception {
        Method m = KubernetesNativeBackend.class.getDeclaredMethod(
            "isWholeNumberCpuQuantity", String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, quantity);
    }

    @Test
    public void wholeCpuRequestsAreAcceptedIncludingAWholeNodesWorth() throws Exception {
        assertTrue("1 CPU", isWholeNumberCpuQuantity("1"));
        assertTrue(
            "32 is what production configures -- a whole compute node -- and it must no"
                + " longer be replaced by 1",
            isWholeNumberCpuQuantity("32")
        );
        assertTrue("milli-CPU that divides exactly", isWholeNumberCpuQuantity("2000m"));
        assertTrue("whitespace tolerated", isWholeNumberCpuQuantity(" 16 "));
    }

    @Test
    public void fractionalCpuRequestsAreRejected() throws Exception {
        // A fractional request cannot receive exclusive cores; Kubernetes gives it a CFS
        // bandwidth quota and the solver's threads float across the whole node.
        assertFalse("1500m is 1.5 CPUs", isWholeNumberCpuQuantity("1500m"));
        assertFalse("half a CPU", isWholeNumberCpuQuantity("500m"));
        assertFalse("decimal form", isWholeNumberCpuQuantity("2.5"));
        assertFalse("even a whole-valued decimal", isWholeNumberCpuQuantity("2.0"));
    }

    @Test
    public void nonsenseAndNonPositiveCpuRequestsAreRejected() throws Exception {
        assertFalse(isWholeNumberCpuQuantity(null));
        assertFalse(isWholeNumberCpuQuantity(""));
        assertFalse(isWholeNumberCpuQuantity("   "));
        assertFalse(isWholeNumberCpuQuantity("all"));
        assertFalse("zero CPUs is not a pair's worth", isWholeNumberCpuQuantity("0"));
        assertFalse(isWholeNumberCpuQuantity("-4"));
        assertFalse(isWholeNumberCpuQuantity("0m"));
    }

    // ---------------------------------------------------------------------
    // End-to-end: the configured CPU value must reach the pod spec.
    //
    // The tests above check the validator in isolation, which is not enough:
    // the original defect was not a bad validator but an assignment that threw
    // the operator's value away afterwards. A test that never inspects the
    // generated Job cannot see that, so this one builds the spec and reads the
    // container's resources.
    // ---------------------------------------------------------------------

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = KubernetesNativeBackend.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Configures just enough state for buildKubernetesJob to produce a spec. */
    private KubernetesNativeBackend backendWithCpu(String cpu) throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "cpuLimit", cpu);
        setField(backend, "memoryLimit", "250Gi");
        setField(backend, "namespace", "starexec");
        setField(backend, "jobImage", "ghcr.io/starexecmiami/starexec-job-runner:latest");
        setField(backend, "serviceAccountName", "starexec-job");
        setField(backend, "dataPvcName", "starexec-data");
        setField(backend, "dataPvcAccessMode", "ReadWriteMany");
        setField(backend, "workerNodeSelectorKey", "starexec.org/worker");
        setField(backend, "workerNodeSelectorValue", "true");
        setField(backend, "appNodeName", "");
        setField(backend, "backoffLimit", 0);
        setField(backend, "ttlSecondsAfterFinished", 3600);
        return backend;
    }

    private Container buildContainer(String cpu) throws Exception {
        KubernetesNativeBackend backend = backendWithCpu(cpu);
        Method build = KubernetesNativeBackend.class.getDeclaredMethod(
            "buildKubernetesJob",
            int.class, int.class, String.class, String.class, String.class, String.class);
        build.setAccessible(true);
        Job job = (Job) build.invoke(
            backend, 42, 7, "starexec-job-7", "/script.sh", "/work", "/work/log.txt");
        return job.getSpec().getTemplate().getSpec().getContainers().get(0);
    }

    /**
     * The regression guard. Production configures 32 -- a whole compute node, matching how
     * SGE runs pairs -- and that value was previously overwritten with "1" after
     * validation, so the pod requested one CPU while the logs said 32.
     */
    @Test
    public void theConfiguredCpuValueReachesThePodSpec() throws Exception {
        Container container = buildContainer("32");

        assertEquals(
            "the operator's CPU limit must survive into the pod spec, not be replaced by 1",
            "32",
            container.getResources().getLimits().get("cpu").toString()
        );
        assertEquals(
            "requests must match limits, or the pod is not Guaranteed and Kubernetes will"
                + " never assign it exclusive cores",
            "32",
            container.getResources().getRequests().get("cpu").toString()
        );
    }

    @Test
    public void memoryAlsoRequestsEqualLimitsForGuaranteedQos() throws Exception {
        Container container = buildContainer("32");

        assertEquals(
            container.getResources().getLimits().get("memory").toString(),
            container.getResources().getRequests().get("memory").toString()
        );
    }

    // ---------------------------------------------------------------------
    // Image pull policy.
    //
    // kubernetes.jobImagePullPolicy sat in values-prod.yaml and values-dev.yaml for a long
    // time while no template and no Java code read it, so an operator could set
    // IfNotPresent, see it in the values, and still get a pod that carried no policy at
    // all. These two tests pin both halves of the contract: the setting reaches the pod
    // when configured, and stays absent when it is not.
    // ---------------------------------------------------------------------

    private Container buildContainerWithPullPolicy(String policy) throws Exception {
        return buildContainerWithPullPolicy(policy, null);
    }

    private Container buildContainerWithPullPolicy(String policy, String image)
            throws Exception {
        KubernetesNativeBackend backend = backendWithCpu("32");
        setField(backend, "jobImagePullPolicy", policy);
        if (image != null) {
            setField(backend, "jobImage", image);
        }
        Method build = KubernetesNativeBackend.class.getDeclaredMethod(
            "buildKubernetesJob",
            int.class, int.class, String.class, String.class, String.class, String.class);
        build.setAccessible(true);
        Job job = (Job) build.invoke(
            backend, 42, 7, "starexec-job-7", "/script.sh", "/work", "/work/log.txt");
        return job.getSpec().getTemplate().getSpec().getContainers().get(0);
    }

    @Test
    public void theConfiguredPullPolicyReachesThePodSpec() throws Exception {
        Container container = buildContainerWithPullPolicy("IfNotPresent");

        assertEquals(
            "the operator's pull policy must reach the pod, or isolated workers are asked"
                + " to re-resolve an image from a registry they cannot reach",
            "IfNotPresent",
            container.getImagePullPolicy()
        );
    }

    /**
     * The production pairing, asserted together rather than separately: the pinned digest
     * and IfNotPresent have to arrive on the same container, because it is the combination
     * that lets an isolated worker run an image it already holds.
     */
    @Test
    public void theProductionDigestAndPolicyArriveTogether() throws Exception {
        String digest = "ghcr.io/starexecmiami/starexec-job-runner@sha256:"
            + "63ae82d539f7c26bc70f42036f968897fc110416043d2fedc8a917b828537cdd";
        Container container = buildContainerWithPullPolicy("IfNotPresent", digest);

        assertEquals(digest, container.getImage());
        assertEquals("IfNotPresent", container.getImagePullPolicy());
    }

    /**
     * Proves the value is carried, not recognised. If the plumbing ever grew a whitelist or
     * a coerced default, a policy this backend has no reason to prefer would expose it.
     * Kubernetes API validation remains the guard on what is actually a legal policy.
     */
    @Test
    public void anyConfiguredPolicyIsCarriedVerbatim() throws Exception {
        assertEquals("Always", buildContainerWithPullPolicy("Always").getImagePullPolicy());
        assertEquals("Never", buildContainerWithPullPolicy("Never").getImagePullPolicy());
    }

    /**
     * The negative control. An unconfigured deployment must behave exactly as it did before
     * the setting existed: no policy on the container, so Kubernetes derives one from the
     * image reference. Fabricating a default here would silently change every existing
     * deployment that never asked for one.
     */
    @Test
    public void anUnsetPullPolicyLeavesKubernetesToDeriveItFromTheImageReference()
            throws Exception {
        Container unset = buildContainerWithPullPolicy("");
        assertNull(
            "an empty setting must leave the field absent, not stamp a fabricated default",
            unset.getImagePullPolicy()
        );

        Container never = buildContainer("32");
        assertNull(
            "a backend that never loaded the setting must behave the same way",
            never.getImagePullPolicy()
        );
    }

    // ---------------------------------------------------------------------
    // Strict mode must not change the value it is given.
    //
    // The pod-spec test above cannot guard this: it injects cpuLimit by
    // reflection and never runs loadConfiguration, which is precisely where the
    // original clobber lived. Reintroducing `cpuLimit = "1"` there leaves that
    // test green. So the pass-through is expressed as a return value, which a
    // test can assert on directly.
    // ---------------------------------------------------------------------

    private String resolveCpuLimitForStrictMode(String configured) throws Exception {
        Method m = KubernetesNativeBackend.class.getDeclaredMethod(
            "resolveCpuLimitForStrictMode", String.class);
        m.setAccessible(true);
        try {
            return (String) m.invoke(null, configured);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    public void strictModeReturnsTheConfiguredCpuLimitUnchanged() throws Exception {
        assertEquals(
            "strict mode must validate the operator's value, never replace it -- a whole"
                + " compute node is the SGE-equivalent allocation and must survive",
            "32",
            resolveCpuLimitForStrictMode("32")
        );
        assertEquals("1", resolveCpuLimitForStrictMode("1"));
        assertEquals("2000m", resolveCpuLimitForStrictMode("2000m"));
    }

    @Test
    public void strictModeRejectsAQuantityThatCannotYieldExclusiveCores() throws Exception {
        try {
            resolveCpuLimitForStrictMode("1500m");
            fail("a fractional CPU request cannot receive exclusive cores and must abort");
        } catch (IllegalStateException expected) {
            assertTrue(
                expected.getMessage(),
                expected.getMessage().contains("whole number of CPUs")
            );
        }
    }

    // ---------------------------------------------------------------------
    // Default queue naming
    //
    // Three names existed for one thing: R.DEFAULT_QUEUE_NAME is "all.q" and
    // names the seeded DB row; Queues.getDefaultQueueName() returns "all",
    // which is the SGE host-group spelling (@allhosts) rather than a queue;
    // and this backend invented "default", matching neither. The result was a
    // spurious "default" queue created on a fresh cluster while the real all.q
    // went INACTIVE, and a third queue named "all" minted whenever
    // Queues.removeQueue returned a node to the default.
    // ---------------------------------------------------------------------

    private boolean isDefaultQueueName(String name) throws Exception {
        Method m = KubernetesNativeBackend.class.getDeclaredMethod(
            "isDefaultQueueName", String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, name);
    }

    @Test
    public void theDefaultQueueIsTheCanonicalOneNotAnInventedName() throws Exception {
        Field f = KubernetesNativeBackend.class.getDeclaredField("DEFAULT_QUEUE_NAME");
        f.setAccessible(true);

        assertEquals(
            "the backend's default queue must be the canonical R.DEFAULT_QUEUE_NAME, or a"
                + " fresh cluster invents a queue the rest of StarExec does not know",
            org.starexec.constants.R.DEFAULT_QUEUE_NAME,
            f.get(null)
        );
        assertEquals("all.q", f.get(null));
    }

    @Test
    public void bothSpellingsOfTheDefaultQueueAreRecognised() throws Exception {
        assertTrue("canonical name", isDefaultQueueName("all.q"));
        assertTrue(
            "the SGE host-group short form, which Queues.removeQueue passes to moveNode;"
                + " unrecognised, it was written as a literal label and became a third queue",
            isDefaultQueueName("all")
        );
        // Deliberately reversed. This asserted "case tolerated" -- isDefaultQueueName was
        // the only case-insensitive queue comparison in the system, while the node-label
        // list and GetIdByName are both exact. That let an admin create a distinct queue
        // 'ALL.q' whose pairs were then given the default queue's affinity and run on the
        // default queue's hardware. A case variant is a different queue, everywhere.
        assertFalse("a case variant is a distinct queue", isDefaultQueueName("ALL.Q"));
        assertFalse("a case variant is a distinct queue", isDefaultQueueName("All.q"));
        assertTrue("whitespace tolerated", isDefaultQueueName(" all.q "));
    }

    @Test
    public void arealQueueIsNotMistakenForTheDefault() throws Exception {
        assertFalse(isDefaultQueueName("casc.q"));
        assertFalse(isDefaultQueueName("public.q"));
        assertFalse("the old invented name is not special", isDefaultQueueName("default"));
        assertFalse(isDefaultQueueName(null));
        assertFalse(isDefaultQueueName(""));
    }

    // ---------------------------------------------------------------------
    // B1: node schedulability
    //
    // A node must be BOTH uncordoned and Ready to receive a pod. Checking only
    // the cordon flag would let a NotReady node look available, and a pod sent
    // there pends exactly as it did before -- which is the failure this whole
    // section exists to remove.
    // ---------------------------------------------------------------------

    private boolean isNodeSchedulable(Boolean unschedulable, String readyStatus)
        throws Exception {
        io.fabric8.kubernetes.api.model.NodeBuilder builder =
            new io.fabric8.kubernetes.api.model.NodeBuilder()
                .withNewMetadata().withName("n021").endMetadata()
                .withNewSpec().withUnschedulable(unschedulable).endSpec();
        if (readyStatus == null) {
            builder = builder.withNewStatus().endStatus();
        } else {
            builder = builder.withNewStatus()
                .addNewCondition().withType("Ready").withStatus(readyStatus).endCondition()
                .endStatus();
        }
        Method m = KubernetesNativeBackend.class.getDeclaredMethod(
            "isNodeSchedulable", io.fabric8.kubernetes.api.model.Node.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, builder.build());
    }

    @Test
    public void aReadyUncordonedNodeIsSchedulable() throws Exception {
        assertTrue(isNodeSchedulable(null, "True"));
        assertTrue(isNodeSchedulable(Boolean.FALSE, "True"));
    }

    @Test
    public void aCordonedNodeIsNotSchedulable() throws Exception {
        assertFalse(
            "an operator draining a node must not have work sent to it",
            isNodeSchedulable(Boolean.TRUE, "True")
        );
    }

    @Test
    public void anUncordonedButNotReadyNodeIsNotSchedulable() throws Exception {
        // The case a cordon-only check misses: kubelet stopped, disk pressure, a network
        // partition. The node accepts nothing, so a pod sent there pends indefinitely.
        assertFalse(isNodeSchedulable(Boolean.FALSE, "False"));
        assertFalse("Unknown is not Ready", isNodeSchedulable(Boolean.FALSE, "Unknown"));
        assertFalse("no Ready condition at all", isNodeSchedulable(Boolean.FALSE, null));
    }

    // ---------------------------------------------------------------------
    // B1: the routing gate's two branches
    //
    // The boundary most likely to be got wrong. "No node carries this queue"
    // and "this queue's nodes are all down" look similar and must behave
    // oppositely: the first is permanent and should fail the pair loudly, the
    // second is transient and must defer, because failing pairs through a
    // 30-second drain destroys a benchmark run for a condition that fixes
    // itself.
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private KubernetesNativeBackend backendWithQueueView(
        java.util.Set<String> labelled,
        java.util.Set<String> schedulable
    ) throws Exception {
        // A whole 32-CPU node per queue, asking for 1: these cases are about routing,
        // not capacity, so the capacity gate must be satisfied and out of the way.
        return backendWithQueueView(labelled, schedulable, "1", 32_000L);
    }

    /**
     * As above, but with an explicit CPU request and per-queue allocatable ceiling so a
     * test can drive the capacity gate directly.
     */
    @SuppressWarnings("unchecked")
    private KubernetesNativeBackend backendWithQueueView(
        java.util.Set<String> labelled,
        java.util.Set<String> schedulable,
        String cpuLimit,
        Long maxAllocatableMillisPerQueue
    ) throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "labelledQueues", labelled);
        setField(backend, "schedulableQueues", schedulable);
        setField(backend, "initialized", true);
        setField(backend, "shuttingDown", false);
        setField(backend, "cpuLimit", cpuLimit);
        java.util.Map<String, Long> capacity = new java.util.HashMap<>();
        if (maxAllocatableMillisPerQueue != null) {
            for (String q : schedulable) {
                capacity.put(q, maxAllocatableMillisPerQueue);
            }
        }
        setField(backend, "queueMaxAllocatableCpuMillis", capacity);
        // Keep the cached view fresh so the gate does not try to reach a cluster.
        setField(backend, "queueViewRefreshedAt", System.currentTimeMillis());
        // These cases are all about a view that HAS been read. The separate
        // never-loaded case is covered by its own test.
        setField(backend, "queueViewLoaded", true);
        return backend;
    }

    @Test
    public void aQueueWithSchedulableNodesIsDispatchable() throws Exception {
        KubernetesNativeBackend backend = backendWithQueueView(
            new java.util.HashSet<>(java.util.Arrays.asList("casc.q")),
            new java.util.HashSet<>(java.util.Arrays.asList("casc.q"))
        );

        assertTrue(backend.isQueueDispatchable("casc.q"));
    }

    @Test
    public void aQueueWhoseNodesAreAllDownDefersRatherThanFailing() throws Exception {
        // Labelled but not schedulable: a drain or a NotReady node. Deferring keeps the
        // pairs alive for a later pass.
        KubernetesNativeBackend backend = backendWithQueueView(
            new java.util.HashSet<>(java.util.Arrays.asList("casc.q")),
            java.util.Collections.emptySet()
        );

        assertFalse(
            "a transient outage must defer dispatch, not let the pairs through to be"
                + " marked terminally failed",
            backend.isQueueDispatchable("casc.q")
        );
    }

    // ---------------------------------------------------------------------
    // Measurement fidelity: runsolver's own verdict outranks status.json.
    //
    // status.json's status field is written by jobscript grepping runsolver's
    // English prose out of watcher.out. 73fc0acab replaced that with runsolver's
    // own TIMEOUT=/MEMOUT= booleans for LocalJobMonitor and ContainerJobMonitor
    // but never touched this backend, so the one path used in Kubernetes
    // deployments kept recording a genuine timeout as a clean completion.
    // ---------------------------------------------------------------------

    private int readTerminalStatus(
        KubernetesNativeBackend backend,
        int execId,
        int fallback
    ) throws Exception {
        Class<?> callbackClass = Class.forName(
            "org.starexec.backend.KubernetesNativeBackend$KubernetesJobCompletionCallback"
        );
        Constructor<?> ctor =
            callbackClass.getDeclaredConstructor(KubernetesNativeBackend.class);
        ctor.setAccessible(true);
        Object callback = ctor.newInstance(backend);

        // Mirrors onJobComplete: the status file is parsed once and handed to the reader,
        // so these tests exercise the same path production takes rather than a second one.
        ExecutionRef ref = execution(execId, "job-" + execId);
        Method read = callbackClass.getDeclaredMethod("readStatusRecord", ExecutionRef.class);
        read.setAccessible(true);
        Object record = read.invoke(callback, ref);

        Method m = callbackClass.getDeclaredMethod(
            "readTerminalStatus", ExecutionRef.class, com.google.gson.JsonObject.class, int.class
        );
        m.setAccessible(true);
        return (Integer) m.invoke(callback, ref, record, fallback);
    }

    /**
     * A backend whose tracking says this execution owns {@code dir}.
     *
     * <p>The job name and UID are seeded too, not just the directory: reading an
     * execution's artifacts now requires owning the tracking under its id, so a bare
     * directory entry with nothing claiming it yields no artifacts at all.
     */
    @SuppressWarnings("unchecked")
    private KubernetesNativeBackend backendWithOutputDir(int execId, Path dir)
        throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        ExecutionRef owner = execution(execId, "job-" + execId);

        Field dirs = KubernetesNativeBackend.class.getDeclaredField("execIdToOutputDir");
        dirs.setAccessible(true);
        ((Map<Integer, Path>) dirs.get(backend)).put(execId, dir);

        Field names = KubernetesNativeBackend.class.getDeclaredField("execIdToJobName");
        names.setAccessible(true);
        ((Map<Integer, String>) names.get(backend)).put(execId, owner.jobName());

        Field uids = KubernetesNativeBackend.class.getDeclaredField("execIdToJobUid");
        uids.setAccessible(true);
        ((Map<Integer, String>) uids.get(backend)).put(execId, owner.jobUid());

        return backend;
    }

    @Test
    public void aRunsolverTimeoutOutranksACleanStatusJson() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("k8s-verdict-timeout");
        // What the prose-grep layer concluded: nothing matched, so a clean completion.
        java.nio.file.Files.writeString(
            dir.resolve("status.json"), "{\"status\": 7, \"stageNumber\": 1}"
        );
        // What runsolver itself measured, from the final getrusage after the child exited.
        java.nio.file.Files.writeString(
            dir.resolve("var.out"),
            "WCTIME=12.3\nCPUTIME=600.1\nTIMEOUT=true\nMEMOUT=false\n"
        );
        // No "Maximum CPU time exceeded" line: the watcher poll never fired because the
        // solver exited exactly as the limit was crossed. This is the case the prose
        // grep structurally cannot see.
        java.nio.file.Files.writeString(dir.resolve("watcher.out"), "Child status: 0\n");

        KubernetesNativeBackend backend = backendWithOutputDir(9001, dir);

        assertEquals(
            "runsolver reported TIMEOUT=true, so STATUS_COMPLETE from status.json must"
                + " not be what gets recorded",
            org.starexec.data.to.Status.StatusCode.EXCEED_CPU.getVal(),
            readTerminalStatus(
                backend, 9001,
                org.starexec.data.to.Status.StatusCode.STATUS_COMPLETE.getVal()
            )
        );
    }

    @Test
    public void watcherProseDiscriminatesWallclockFromCpu() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("k8s-verdict-wall");
        java.nio.file.Files.writeString(
            dir.resolve("status.json"), "{\"status\": 7, \"stageNumber\": 1}"
        );
        java.nio.file.Files.writeString(
            dir.resolve("var.out"), "TIMEOUT=true\nMEMOUT=false\n"
        );
        java.nio.file.Files.writeString(
            dir.resolve("watcher.out"),
            "Maximum wall clock time exceeded: sending SIGTERM then SIGKILL\n"
        );

        KubernetesNativeBackend backend = backendWithOutputDir(9002, dir);

        // TIMEOUT= is a disjunction and cannot say which limit fired; the prose can.
        assertEquals(
            org.starexec.data.to.Status.StatusCode.EXCEED_RUNTIME.getVal(),
            readTerminalStatus(
                backend, 9002,
                org.starexec.data.to.Status.StatusCode.STATUS_COMPLETE.getVal()
            )
        );
    }

    @Test
    public void statusJsonStillDecidesWhenRunsolverReportsNoBreach() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("k8s-verdict-clean");
        java.nio.file.Files.writeString(
            dir.resolve("status.json"), "{\"status\": 11, \"stageNumber\": 1}"
        );
        java.nio.file.Files.writeString(
            dir.resolve("var.out"), "TIMEOUT=false\nMEMOUT=false\n"
        );
        java.nio.file.Files.writeString(dir.resolve("watcher.out"), "Child status: 3\n");

        KubernetesNativeBackend backend = backendWithOutputDir(9003, dir);

        // No limit fired, so the verdict abstains and status.json is still authoritative
        // for non-limit outcomes such as a run-script failure.
        assertEquals(
            "the verdict must only override when runsolver actually reports a breach",
            org.starexec.data.to.Status.StatusCode.ERROR_RUNSCRIPT.getVal(),
            readTerminalStatus(
                backend, 9003,
                org.starexec.data.to.Status.StatusCode.STATUS_COMPLETE.getVal()
            )
        );
    }

    @Test
    public void aRerunDoesNotInheritThePreviousAttemptsVerdict() throws Exception {
        // The output directory is derived from the pair's stdout path, so it is keyed by
        // pairId and reused by every rerun. Once var.out outranks status.json, a stale
        // one would classify an attempt that never even reached runsolver.
        Path dir = java.nio.file.Files.createTempDirectory("k8s-stale-attempt");
        java.nio.file.Files.writeString(dir.resolve("var.out"), "TIMEOUT=true\n");
        java.nio.file.Files.writeString(
            dir.resolve("watcher.out"), "Maximum CPU time exceeded: ...\n"
        );
        java.nio.file.Files.writeString(dir.resolve("status.json"), "{\"status\": 14}");
        java.nio.file.Files.writeString(dir.resolve("stats.json"), "{}");

        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Method clear = KubernetesNativeBackend.class.getDeclaredMethod(
            "clearStaleAttemptArtifacts", Path.class, int.class
        );
        clear.setAccessible(true);
        clear.invoke(backend, dir, 4242);

        for (String name : List.of("var.out", "watcher.out", "status.json", "stats.json")) {
            assertFalse(
                "a previous attempt's " + name + " must not survive into the next attempt",
                java.nio.file.Files.exists(dir.resolve(name))
            );
        }

        // With the directory cleared the verdict abstains rather than inventing one. The
        // caller's default no longer stands on its own, though: an execution that left no
        // artifacts at all is not evidence of a completed run, so the status falls to
        // ERROR_RUNSCRIPT -- StarExec's bounded-retry channel -- rather than to COMPLETE.
        // The property this test exists for is unchanged: nothing of the previous attempt
        // survived, which the four assertions above check.
        KubernetesNativeBackend fresh = backendWithOutputDir(4242, dir);
        assertEquals(
            "with no runsolver output and no artifacts this is not a completed run",
            org.starexec.data.to.Status.StatusCode.ERROR_RUNSCRIPT.getVal(),
            readTerminalStatus(
                fresh, 4242,
                org.starexec.data.to.Status.StatusCode.STATUS_COMPLETE.getVal()
            )
        );
    }

    @Test
    public void cleanupReportsFailureWhenAStaleArtifactCannotBeRemoved() throws Exception {
        // Fail closed: the caller decides whether a benchmark may run on the strength of
        // this return value, so it must report absence, not merely that delete() did not
        // throw. Simulated by making the containing directory unwritable, which is what a
        // misconfigured output volume looks like.
        Path dir = java.nio.file.Files.createTempDirectory("k8s-undeletable");
        Path stale = dir.resolve("var.out");
        java.nio.file.Files.writeString(stale, "TIMEOUT=true\n");

        try {
            java.nio.file.Files.setPosixFilePermissions(
                dir, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x")
            );
        } catch (UnsupportedOperationException e) {
            org.junit.Assume.assumeNoException("POSIX permissions unavailable", e);
        }

        try {
            KubernetesNativeBackend backend = new KubernetesNativeBackend();
            Method clear = KubernetesNativeBackend.class.getDeclaredMethod(
                "clearStaleAttemptArtifacts", Path.class, int.class
            );
            clear.setAccessible(true);
            boolean allAbsent = (Boolean) clear.invoke(backend, dir, 5150);

            // Running as root defeats the permission bits; skip rather than pass vacuously.
            org.junit.Assume.assumeTrue(
                "cannot simulate an undeletable file as this user",
                java.nio.file.Files.exists(stale)
            );

            assertFalse(
                "a surviving stale artifact must be reported as failure, so submitScript"
                    + " refuses to create the Job rather than misclassifying the run",
                allAbsent
            );
        } finally {
            java.nio.file.Files.setPosixFilePermissions(
                dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")
            );
            java.nio.file.Files.deleteIfExists(stale);
            java.nio.file.Files.deleteIfExists(dir);
        }
    }

    // ---------------------------------------------------------------------
    // getQueues must agree with every other reader of the queue label.
    //
    // Cluster.loadQueueDetails marks every queue INACTIVE and reactivates only
    // the names getQueues returns, so a name missing here is a queue that stops
    // dispatching with no error anywhere and never self-heals.
    // ---------------------------------------------------------------------

    private io.fabric8.kubernetes.api.model.Node nodeWithQueueLabel(
        String name,
        String queueLabelValue
    ) {
        java.util.Map<String, String> labels = new java.util.HashMap<>();
        labels.put("starexec.org/worker", "true");
        if (queueLabelValue != null) {
            labels.put("starexec.org/queue", queueLabelValue);
        }
        io.fabric8.kubernetes.api.model.Node node =
            new io.fabric8.kubernetes.api.model.Node();
        node.setMetadata(
            new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
                .withName(name)
                .withLabels(labels)
                .build()
        );
        return node;
    }

    private KubernetesNativeBackend backendSeeingNodes(
        io.fabric8.kubernetes.api.model.Node... nodes
    ) throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "queueLabelKey", "starexec.org/queue");
        setField(backend, "workerNodeSelectorKey", "starexec.org/worker");
        setField(backend, "workerNodeSelectorValue", "true");

        io.fabric8.kubernetes.api.model.NodeList list =
            new io.fabric8.kubernetes.api.model.NodeListBuilder()
                .withItems(nodes)
                .build();

        setField(backend, "kubernetesClient", clientListingNodes(list, null));
        return backend;
    }

    /**
     * Mocks {@code client.nodes().withLabel(k, v).list()} one level at a time. Deep stubs
     * cannot do this: {@code withLabel} is declared on a generic interface and the deep
     * stub returns null rather than another mock.
     *
     * @param list  what the listing returns, or null when {@code failure} is set
     * @param failure thrown from {@code list()} instead of returning
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private KubernetesClient clientListingNodes(
        io.fabric8.kubernetes.api.model.NodeList list,
        RuntimeException failure
    ) {
        KubernetesClient client = Mockito.mock(KubernetesClient.class);
        NonNamespaceOperation nodesOp = Mockito.mock(NonNamespaceOperation.class);
        io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable filtered =
            Mockito.mock(io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable.class);

        Mockito.when(client.nodes()).thenReturn(nodesOp);
        Mockito
            .when(nodesOp.withLabel("starexec.org/worker", "true"))
            .thenReturn(filtered);
        if (failure != null) {
            Mockito.when(filtered.list()).thenThrow(failure);
        } else {
            Mockito.when(filtered.list()).thenReturn(list);
        }
        return client;
    }

    @Test
    public void anUnlabelledNodeKeepsTheDefaultQueueAlive() throws Exception {
        // The ordinary state of a cluster where some nodes are the default pool and an
        // admin has just added a second queue. getQueues used to skip the unlabelled
        // node entirely, so all.q vanished from the returned set and loadQueueDetails
        // left it INACTIVE -- permanently, since every later run repeated the omission.
        KubernetesNativeBackend backend = backendSeeingNodes(
            nodeWithQueueLabel("n1", "sat-comp"),
            nodeWithQueueLabel("n2", null)
        );

        List<String> queues = java.util.Arrays.asList(backend.getQueues());

        assertTrue(
            "an unlabelled worker node belongs to the default queue everywhere else,"
                + " so getQueues must report it too: " + queues,
            queues.contains("all.q")
        );
        assertTrue("the real second queue is still reported: " + queues,
            queues.contains("sat-comp"));
    }

    @Test
    public void aBlankQueueLabelAlsoKeepsTheDefaultQueueAlive() throws Exception {
        // `kubectl label node n2 starexec.org/queue=` produces the empty value, which
        // DEFAULT_QUEUE_LABEL_VALUES and the affinity both already treat as default.
        KubernetesNativeBackend backend = backendSeeingNodes(
            nodeWithQueueLabel("n1", "sat-comp"),
            nodeWithQueueLabel("n2", "")
        );

        List<String> queues = java.util.Arrays.asList(backend.getQueues());
        assertTrue("blank label is default-queue membership: " + queues,
            queues.contains("all.q"));
    }

    @Test
    public void aFailedNodeListingRefusesToReportAQueueList() throws Exception {
        // Returning {all.q} on failure would make loadQueueDetails deactivate every real
        // queue in the database on one transient API blip. Same principle as
        // queueViewLoaded: an unread view is not an empty one.
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "queueLabelKey", "starexec.org/queue");
        setField(backend, "workerNodeSelectorKey", "starexec.org/worker");
        setField(backend, "workerNodeSelectorValue", "true");

        setField(
            backend,
            "kubernetesClient",
            clientListingNodes(null, new RuntimeException("API server unreachable"))
        );

        try {
            backend.getQueues();
            fail("a failed node listing must not be reported as a queue list");
        } catch (IllegalStateException expected) {
            // loadQueueDetails catches this and aborts before deactivating anything.
        }
    }

    // ---------------------------------------------------------------------
    // The pod must report the node it ran on.
    //
    // containerWriteStats records "hostname": "$(hostname)", and in a pod that
    // is the POD name -- nothing sets spec.hostname or hostNetwork. That name
    // is never a row in `nodes`, so UpdatePairRunSolverStats raised P0002 and
    // aborted the whole write: every Kubernetes pair lost its wallclock, CPU,
    // memory and disk measurements while still looking successful.
    //
    // Verified against the live cluster: a pod's spec.nodeName is "quokka",
    // byte-identical to the Node's metadata.name, which is the string
    // Cluster.loadWorkerNodes stores in nodes.name.
    // ---------------------------------------------------------------------

    private io.fabric8.kubernetes.api.model.EnvVar envVar(Container c, String name) {
        for (io.fabric8.kubernetes.api.model.EnvVar e : c.getEnv()) {
            if (name.equals(e.getName())) {
                return e;
            }
        }
        return null;
    }

    @Test
    public void thePodIsToldWhichNodeItRunsOn() throws Exception {
        Container container = buildContainer("16");

        io.fabric8.kubernetes.api.model.EnvVar nodeName =
            envVar(container, "STAREXEC_NODE_NAME");

        assertNotNull(
            "without this the pod reports its own name as the host, which is not a row in"
                + " nodes, and every pair's measurements are lost to P0002",
            nodeName
        );
        assertNotNull(
            "it must come from the downward API, not a literal: the scheduler picks the"
                + " node after the Job is created",
            nodeName.getValueFrom()
        );
        assertNotNull(nodeName.getValueFrom().getFieldRef());
        assertEquals(
            "spec.nodeName is the Node's metadata.name, which is what nodes.name holds",
            "spec.nodeName",
            nodeName.getValueFrom().getFieldRef().getFieldPath()
        );
        assertNull(
            "a literal value would be a guess at where the scheduler will place the pod",
            nodeName.getValue()
        );
    }

    @Test
    public void statsNodeNameIsNullWhenUnknownRatherThanInvented() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "appNodeName", "");

        Object stats = Class
            .forName("org.starexec.backend.ContainerJobMonitor$RunsolverStats")
            .getDeclaredConstructor()
            .newInstance();

        Method resolve = null;
        for (Class<?> c : KubernetesNativeBackend.class.getDeclaredClasses()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("resolveStatsNodeName")) {
                    resolve = m;
                    resolve.setAccessible(true);
                }
            }
        }
        assertNotNull("resolveStatsNodeName should exist on an inner class", resolve);

        // Instantiate the inner class that owns it, via its synthetic outer-instance ctor.
        java.lang.reflect.Constructor<?> ctor =
            resolve.getDeclaringClass().getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object owner = ctor.getParameterCount() == 1
            ? ctor.newInstance(backend)
            : ctor.newInstance();

        assertNull(
            "an unknown node must yield null, not a placeholder: a name that cannot"
                + " resolve guarantees P0002 and discards the pair's measurements while"
                + " hiding why",
            resolve.invoke(owner, stats)
        );
    }

    @Test
    public void aQueueNoNodeCarriesIsLeftToFailAtSubmission() throws Exception {
        // Permanent misconfiguration. The gate deliberately lets it through so
        // submitScript can reject it where the failure is visible, rather than deferring
        // for ever and hiding the configuration bug behind a queue that never moves.
        KubernetesNativeBackend backend = backendWithQueueView(
            java.util.Collections.emptySet(),
            java.util.Collections.emptySet()
        );

        assertTrue(
            "an unlabelled queue must not be deferred silently -- it never resolves",
            backend.isQueueDispatchable("high-mem.q")
        );
    }

    // ---------------------------------------------------------------------
    // CPU capacity gate
    //
    // Quokka ran with STAREXEC_K8S_CPU_LIMIT=32 against nodes whose allocatable
    // CPU is exactly 32. calico-node already requests 250m on every node, so
    // 31750m was schedulable and every solver pod stayed Pending for ever.
    // KubernetesJobMonitor then failed each pair for rerun at its timeout and the
    // rerun asked for 32 again -- a livelock that turned a one-line config error
    // into weeks of a queue that never moved.
    //
    // The gate is a static impossibility check: a request that is not strictly
    // smaller than the biggest node the queue can use never fits, no matter how
    // long anything waits. It defers; it must never fail a pair.
    // ---------------------------------------------------------------------

    private long cpuQuantityToMillis(String q) throws Exception {
        Method m = KubernetesNativeBackend.class.getDeclaredMethod(
            "cpuQuantityToMillis", String.class);
        m.setAccessible(true);
        return (Long) m.invoke(null, q);
    }

    @Test
    public void cpuQuantitiesAreComparedNumericallyNotLexically() throws Exception {
        assertEquals(31_000L, cpuQuantityToMillis("31"));
        assertEquals(32_000L, cpuQuantityToMillis("32"));
        assertEquals(1_500L, cpuQuantityToMillis("1500m"));
        assertEquals(500L, cpuQuantityToMillis("500m"));
        // The comparison the gate depends on, and the one a string compare gets wrong:
        // "32" sorts before "4" but is eight times larger.
        assertTrue(cpuQuantityToMillis("32") > cpuQuantityToMillis("4"));
        assertTrue(cpuQuantityToMillis("2") > cpuQuantityToMillis("1500m"));
    }

    @Test
    public void unreadableCpuQuantitiesAreUnknownNotZero() throws Exception {
        // -1 rather than 0 matters: 0 would compare as "smaller than the node" and let an
        // unreadable configuration dispatch.
        assertEquals(-1L, cpuQuantityToMillis(null));
        assertEquals(-1L, cpuQuantityToMillis(""));
        assertEquals(-1L, cpuQuantityToMillis("   "));
        assertEquals(-1L, cpuQuantityToMillis("all-of-them"));
    }

    @Test
    public void aRequestSmallerThanTheNodeIsDispatchable() throws Exception {
        // 31 on a 32-CPU node: the configuration this incident was fixed to.
        KubernetesNativeBackend backend = backendWithQueueView(
            new java.util.HashSet<>(java.util.Arrays.asList("kubernetes.q")),
            new java.util.HashSet<>(java.util.Arrays.asList("kubernetes.q")),
            "31",
            32_000L
        );

        assertTrue(
            "31 CPUs against a 32-CPU node leaves room for node DaemonSets and must be"
                + " allowed to dispatch",
            backend.isQueueDispatchable("kubernetes.q")
        );
    }

    @Test
    public void aRequestEqualToNodeAllocatableIsDeferredNotFailed() throws Exception {
        // The exact Quokka configuration. Equality is unschedulable, because kube-proxy,
        // the CNI DaemonSet and the kubelet's own reservations are all charged against
        // the same allocatable figure.
        KubernetesNativeBackend backend = backendWithQueueView(
            new java.util.HashSet<>(java.util.Arrays.asList("kubernetes.q")),
            new java.util.HashSet<>(java.util.Arrays.asList("kubernetes.q")),
            "32",
            32_000L
        );

        assertFalse(
            "a request equal to allocatable can never be scheduled and must hold dispatch",
            backend.isQueueDispatchable("kubernetes.q")
        );
    }

    @Test
    public void aRequestLargerThanTheNodeIsDeferred() throws Exception {
        KubernetesNativeBackend backend = backendWithQueueView(
            new java.util.HashSet<>(java.util.Arrays.asList("kubernetes.q")),
            new java.util.HashSet<>(java.util.Arrays.asList("kubernetes.q")),
            "33",
            32_000L
        );

        assertFalse(backend.isQueueDispatchable("kubernetes.q"));
    }

    @Test
    public void oneBigEnoughNodeAmongSeveralSatisfiesTheGate() throws Exception {
        // The refresh keeps the MAXIMUM allocatable per queue, so a fleet of small nodes
        // plus one large one still dispatches -- the scheduler only needs one that fits.
        KubernetesNativeBackend backend = backendWithQueueView(
            new java.util.HashSet<>(java.util.Arrays.asList("mixed.q")),
            new java.util.HashSet<>(java.util.Arrays.asList("mixed.q")),
            "31",
            64_000L
        );

        assertTrue(backend.isQueueDispatchable("mixed.q"));
    }

    @Test
    public void aFleetWhereNoNodeIsBigEnoughIsDeferred() throws Exception {
        KubernetesNativeBackend backend = backendWithQueueView(
            new java.util.HashSet<>(java.util.Arrays.asList("small.q")),
            new java.util.HashSet<>(java.util.Arrays.asList("small.q")),
            "31",
            8_000L
        );

        assertFalse(
            "every node is smaller than the request, so nothing will ever schedule",
            backend.isQueueDispatchable("small.q")
        );
    }

    @Test
    public void unknownCapacityFailsClosed() throws Exception {
        // The node listing succeeded for labels but produced no allocatable figure for
        // this queue. "Could not determine" must never read as "fits".
        KubernetesNativeBackend backend = backendWithQueueView(
            new java.util.HashSet<>(java.util.Arrays.asList("kubernetes.q")),
            new java.util.HashSet<>(java.util.Arrays.asList("kubernetes.q")),
            "31",
            null
        );

        assertFalse(
            "capacity that could not be established must defer, not dispatch",
            backend.isQueueDispatchable("kubernetes.q")
        );
    }

    @Test
    public void anUnreadableCpuRequestFailsClosed() throws Exception {
        KubernetesNativeBackend backend = backendWithQueueView(
            new java.util.HashSet<>(java.util.Arrays.asList("kubernetes.q")),
            new java.util.HashSet<>(java.util.Arrays.asList("kubernetes.q")),
            "thirty-one",
            32_000L
        );

        assertFalse(backend.isQueueDispatchable("kubernetes.q"));
    }

    @Test
    public void onlySchedulableNodesContributeAllocatableCpu() throws Exception {
        Method reader = KubernetesNativeBackend.class.getDeclaredMethod(
            "nodeAllocatableCpuMillis", io.fabric8.kubernetes.api.model.Node.class);
        reader.setAccessible(true);

        io.fabric8.kubernetes.api.model.Node big =
            new io.fabric8.kubernetes.api.model.NodeBuilder()
                .withNewMetadata().withName("n021").endMetadata()
                .withNewStatus()
                .addToAllocatable("cpu", new io.fabric8.kubernetes.api.model.Quantity("32"))
                .endStatus()
                .build();
        assertEquals(32_000L, ((Long) reader.invoke(null, big)).longValue());

        // A node with no allocatable block at all is unknown, not zero.
        io.fabric8.kubernetes.api.model.Node blank =
            new io.fabric8.kubernetes.api.model.NodeBuilder()
                .withNewMetadata().withName("n022").endMetadata()
                .build();
        assertEquals(-1L, ((Long) reader.invoke(null, blank)).longValue());

        // Cordoned and NotReady nodes are excluded upstream by isNodeSchedulable, which is
        // what the refresh consults before adding a node's CPUs to a queue's ceiling.
        Method schedulable = KubernetesNativeBackend.class.getDeclaredMethod(
            "isNodeSchedulable", io.fabric8.kubernetes.api.model.Node.class);
        schedulable.setAccessible(true);
        io.fabric8.kubernetes.api.model.Node cordoned =
            new io.fabric8.kubernetes.api.model.NodeBuilder()
                .withNewMetadata().withName("n023").endMetadata()
                .withNewSpec().withUnschedulable(true).endSpec()
                .withNewStatus()
                .addToAllocatable("cpu", new io.fabric8.kubernetes.api.model.Quantity("64"))
                .endStatus()
                .build();
        assertFalse(
            "a cordoned node must not lend its CPUs to the capacity ceiling",
            (Boolean) schedulable.invoke(null, cordoned)
        );
    }

    @Test
    public void theGateDefersWithoutTouchingTheClusterOrFailingAPair() throws Exception {
        // The whole point of gating here rather than in submitScript: a blocked dispatch
        // must create no Kubernetes object and write no terminal pair status. The gate
        // answers from the cached view, so it must not reach the client at all.
        KubernetesClient client = Mockito.mock(KubernetesClient.class);
        KubernetesNativeBackend backend = backendWithQueueView(
            new java.util.HashSet<>(java.util.Arrays.asList("kubernetes.q")),
            new java.util.HashSet<>(java.util.Arrays.asList("kubernetes.q")),
            "32",
            32_000L
        );
        setField(backend, "kubernetesClient", client);

        assertFalse(backend.isQueueDispatchable("kubernetes.q"));

        // No Job created, no pod listed, nothing submitted.
        Mockito.verifyNoInteractions(client);
    }
}
