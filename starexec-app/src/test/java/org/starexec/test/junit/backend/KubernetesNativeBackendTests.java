package org.starexec.test.junit.backend;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.NodeSelectorRequirement;
import io.fabric8.kubernetes.api.model.NodeSelectorTerm;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSecurityContext;
import io.fabric8.kubernetes.api.model.SecurityContext;
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
import org.junit.jupiter.api.Assertions;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.backend.Backend;
import org.starexec.backend.ExecutionRef;
import org.starexec.backend.KubernetesNativeBackend;
import org.starexec.backend.KubernetesJobMonitor;
import org.starexec.backend.PodPhaseView;
import org.starexec.backend.StageStatusSnapshots;
import org.starexec.data.database.JobPairs;
import org.starexec.data.to.JobPair;
import org.starexec.data.database.JobPairs.NeverStartedRetryResult;
import org.starexec.data.database.JobPairs.PairStatusLookupState;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.database.StageStatusBatchResult;
import org.starexec.data.to.Status.StatusCode;

/**
 * Focused unit tests for KubernetesNativeBackend core invariants that do not
 * require a live Kubernetes cluster.
 */
public class KubernetesNativeBackendTests {

    private static Path terminalOutput(StatusCode status) throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("k8s-terminal-evidence");
        dir.toFile().deleteOnExit();
        Path record = dir.resolve("status.json");
        java.nio.file.Files.writeString(
            record,
            "{\"status\":" + status.getVal() + ",\"stageNumber\":1}"
        );
        record.toFile().deleteOnExit();
        return dir;
    }

    @Test
    public void missingKubernetesOutputParentHasDistinctDiagnostic() throws Exception {
        assertMissingKubernetesOutputDiagnostic(java.nio.file.Files.createTempDirectory(
            "k8s-missing-parent").resolve("absent"), "output directory is missing");
    }

    @Test
    public void nonDirectoryKubernetesOutputParentHasDistinctDiagnostic() throws Exception {
        assertMissingKubernetesOutputDiagnostic(java.nio.file.Files.createTempFile(
            "k8s-output-file", ".tmp"), "output path is not a directory");
    }

    @Test
    public void readableEmptyKubernetesOutputHasDistinctDiagnostic() throws Exception {
        assertMissingKubernetesOutputDiagnostic(java.nio.file.Files.createTempDirectory(
            "k8s-empty-parent"), "output directory is readable but status.json is missing");
    }

    @Test
    public void deniedKubernetesOutputParentHasDistinctDiagnostic() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("k8s-denied-parent");
        try (org.mockito.MockedStatic<java.nio.file.Files> files = org.mockito.Mockito.mockStatic(
                java.nio.file.Files.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            files.when(() -> java.nio.file.Files.newDirectoryStream(dir))
                .thenThrow(new java.nio.file.AccessDeniedException(dir.toString()));
            assertMissingKubernetesOutputDiagnostic(dir,
                "output directory cannot be read: AccessDeniedException");
            files.verify(() -> java.nio.file.Files.newDirectoryStream(dir));
        }
    }

    private void assertMissingKubernetesOutputDiagnostic(Path dir, String expected) throws Exception {
        KubernetesNativeBackend backend = backendWithOutputDir(986, dir);
        Class<?> type = Class.forName(
            "org.starexec.backend.KubernetesNativeBackend$KubernetesJobCompletionCallback");
        Constructor<?> ctor = type.getDeclaredConstructor(KubernetesNativeBackend.class);
        ctor.setAccessible(true);
        Object callback = ctor.newInstance(backend);
        Method read = type.getDeclaredMethod("readStatusRecord", ExecutionRef.class);
        read.setAccessible(true);
        ExecutionRef ref = execution(986, "job-986");
        Object record = read.invoke(callback, ref);
        assertNull(record);
        Method stage = type.getDeclaredMethod("readStageNumber", ExecutionRef.class,
            com.google.gson.JsonObject.class);
        stage.setAccessible(true);
        try {
            stage.invoke(callback, ref, record);
            Assertions.fail("absent terminal evidence must still be refused");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertTrue(e.getCause() instanceof org.starexec.backend.StageStatusSnapshots.InvalidSnapshotException);
            assertTrue(e.getCause().getMessage().contains(expected), e.getCause().getMessage());
        }
    }

    @Test
    public void ownedExecutionMissingOutputMappingIsNotReportedAsLostOwnership() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("k8s-owned-output");
        KubernetesNativeBackend backend = backendWithOutputDir(987, dir);
        ((Map<?, ?>) getField(backend, "execIdToOutputDir")).clear();
        assertOutputMappingDiagnostic(backend, execution(987, "job-987"),
            "execution owns its tracking but has no output directory mapping");
    }

    @Test
    public void staleExecutionStillReportsLostOwnership() throws Exception {
        assertOutputMappingDiagnostic(new KubernetesNativeBackend(), execution(987, "job-987"),
            "this execution no longer owns its tracking");
    }

    private void assertOutputMappingDiagnostic(KubernetesNativeBackend backend,
            ExecutionRef execution, String expected) throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(KubernetesNativeBackend.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Class<?> type = Class.forName(
                "org.starexec.backend.KubernetesNativeBackend$KubernetesJobCompletionCallback");
            Constructor<?> ctor = type.getDeclaredConstructor(KubernetesNativeBackend.class);
            ctor.setAccessible(true);
            Method method = type.getDeclaredMethod("ingestEarlierStageStatuses",
                ExecutionRef.class, int.class, int.class, Integer.class);
            method.setAccessible(true);
            // Refused and retried, never recorded: the result is EarlierStages.RETRY.
            assertEquals("RETRY",
                String.valueOf(method.invoke(ctor.newInstance(backend), execution, 77, 1, null)));
            assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains(expected)),
                "expected diagnostic: " + expected);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

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

        assertFalse(backend.killAll(), "killAll cannot report success when it proved nothing");
        assertEquals(1, execToJob.size(), "tracking must survive an unprovable killAll");
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
     * The test above reads an output-dir annotation that production never wrote, so after a
     * restart every rebuilt execution fell back to recomputing its directory, and got it
     * wrong. A submitted Job now records the directory it was submitted with.
     */
    @Test
    public void aSubmittedJobRecordsItsOutputDirectory() throws Exception {
        Job job = buildSubmittedJob(707, 77, "/data/joblogs/5/12/pair_707/707.txt");
        assertEquals("/data/joblogs/5/12/pair_707",
            job.getMetadata().getAnnotations().get("starexec.org/output-dir"));
    }

    /**
     * The round trip a restart takes: the Job a submission built, read back by a process
     * that tracks nothing, yields the directory the pod writes status.json to -- with no
     * database lookup, since the Job says it.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void trackingRebuiltFromASubmittedJobFindsItsOutputDirectory() throws Exception {
        Job job = buildSubmittedJob(707, 77, "/data/joblogs/5/12/pair_707/707.txt");
        job.setStatus(new io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder()
            .withActive(1).build());
        KubernetesNativeBackend restarted = new KubernetesNativeBackend();

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.trySetPairRunning(707))
                .thenReturn(JobPairs.ConditionalPairUpdateResult.UPDATED);
            rebuildTrackingFromJob(restarted, job, viewWithPod(77, "Running"));
            jobPairsMock.verify(() -> JobPairs.getPair(Mockito.anyInt()), Mockito.never());
        }

        assertEquals(Path.of("/data/joblogs/5/12/pair_707"),
            ((Map<Integer, Path>) getField(restarted, "execIdToOutputDir")).get(77));
    }

    /**
     * A Job submitted before the annotation existed -- every one running at the first deploy
     * of this fix -- is rebuilt with the directory submission chose: the parent of the pair's
     * log file. It used to be the parent of the pair's stdout, a directory in another tree,
     * and the 48 pairs of the job 5 rerun in flight across one deploy on Quokka lost their
     * results to it.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void aJobWithoutTheAnnotationIsRebuiltWithTheSubmissionDirectory() throws Exception {
        Job job = new JobBuilder()
            .withNewMetadata()
                .withName("starexec-job-78")
                .addToLabels("starexec.org/managed", "true")
                .addToLabels("starexec.org/exec-id", "78")
                .addToLabels("starexec.org/pair-id", "708")
            .endMetadata()
            .withNewStatus()
                .withActive(1)
            .endStatus()
            .build();
        JobPair pair = new JobPair();
        pair.setId(708);
        pair.setJobId(5);
        pair.setJobSpaceId(12);
        KubernetesNativeBackend restarted = new KubernetesNativeBackend();

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock.when(() -> JobPairs.getPair(708)).thenReturn(pair);
            jobPairsMock
                .when(() -> JobPairs.getLogFilePath(pair))
                .thenReturn("/data/joblogs/5/12/pair_708/708.txt");
            // Where the old code looked: the pair's stdout, in the job output tree.
            jobPairsMock
                .when(() -> JobPairs.getStdout(708))
                .thenReturn("/data/joboutput/5/space/bench/708.txt");
            jobPairsMock
                .when(() -> JobPairs.trySetPairRunning(708))
                .thenReturn(JobPairs.ConditionalPairUpdateResult.UPDATED);
            rebuildTrackingFromJob(restarted, job, viewWithPod(78, "Running"));
        }

        assertEquals(Path.of("/data/joblogs/5/12/pair_708"),
            ((Map<Integer, Path>) getField(restarted, "execIdToOutputDir")).get(78));
    }

    /** A Job as submission builds it, for the given pair, execution and log file. */
    private Job buildSubmittedJob(int pairId, int execId, String logPath) throws Exception {
        KubernetesNativeBackend backend = backendWithCpu("31");
        Method build = KubernetesNativeBackend.class.getDeclaredMethod(
            "buildKubernetesJob",
            int.class, int.class, String.class, String.class, String.class, String.class);
        build.setAccessible(true);
        return (Job) build.invoke(
            backend, pairId, execId, "starexec-job-" + execId, "/script.sh", "/work", logPath);
    }

    private static void rebuildTrackingFromJob(
        KubernetesNativeBackend backend, Job job, PodPhaseView pods) throws Exception {
        Method rebuild = KubernetesNativeBackend.class
            .getDeclaredMethod("rebuildTrackingFromJob", Job.class, PodPhaseView.class);
        rebuild.setAccessible(true);
        rebuild.invoke(backend, job, pods);
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

    /**
     * A status.json that shared storage has not exposed yet is retried, not declared absent
     * (#324). On Quokka's NFS a cached negative lookup hid a just-written status.json for up
     * to a minute; the monitor processed the completion 4 s after the Job finished, abandoned
     * the pair, and the broken-pair sweep later filed it as ERROR_SUBMIT_FAIL.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void aStatusJsonThatAppearsLateIsStillIngested() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        setField(backend, "clock", (java.util.function.LongSupplier) now::get);
        Path output = java.nio.file.Files.createTempDirectory("k8s-late-status");
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(31, "job-31");
        ((Map<Integer, Integer>) getField(backend, "execIdToPairId")).put(31, 311);
        ((Map<Integer, Path>) getField(backend, "execIdToOutputDir")).put(31, output);
        givenSafeCluster(backend);
        KubernetesJobMonitor.JobCompletionCallback callback = instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock.when(() -> JobPairs.getPairStatusLookup(311))
                .thenReturn(foundLookup(StatusCode.STATUS_RUNNING.getVal()));
            jobPairsMock.when(() -> JobPairs.setPairStatusPreciseResult(
                    311, 1, StatusCode.STATUS_COMPLETE.getVal(),
                    StatusCode.STATUS_NOT_REACHED.getVal(), false))
                .thenReturn(PairStatusResult.APPLIED);

            assertFalse(callback.onJobComplete(execution(31, "job-31")),
                "not visible yet: retry, do not abandon");
            assertEquals("job-31", ((Map<Integer, String>) getField(backend, "execIdToJobName")).get(31),
                "tracking is held while waiting");

            // The file becomes visible on a later poll, well inside the window.
            now.addAndGet(30_000L);
            Path written = terminalOutput(StatusCode.STATUS_COMPLETE);
            for (Path f : java.nio.file.Files.list(written).toList()) {
                java.nio.file.Files.move(f, output.resolve(f.getFileName()));
            }

            assertTrue(callback.onJobComplete(execution(31, "job-31")));
            jobPairsMock.verify(() -> JobPairs.setPairStatusPreciseResult(
                311, 1, StatusCode.STATUS_COMPLETE.getVal(),
                StatusCode.STATUS_NOT_REACHED.getVal(), false));
        }
    }

    /** After the window, a status.json that never appeared is still treated as absent. */
    @Test
    @SuppressWarnings("unchecked")
    public void aStatusJsonStillMissingAfterTheWindowIsTreatedAsAbsent() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        setField(backend, "clock", (java.util.function.LongSupplier) now::get);
        Path output = java.nio.file.Files.createTempDirectory("k8s-no-status");
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(32, "job-32");
        ((Map<Integer, Integer>) getField(backend, "execIdToPairId")).put(32, 321);
        ((Map<Integer, Path>) getField(backend, "execIdToOutputDir")).put(32, output);
        givenSafeCluster(backend);
        KubernetesJobMonitor.JobCompletionCallback callback = instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock.when(() -> JobPairs.getPairStatusLookup(321))
                .thenReturn(foundLookup(StatusCode.STATUS_RUNNING.getVal()));

            assertFalse(callback.onJobComplete(execution(32, "job-32")));
            now.addAndGet(119_000L);
            assertFalse(callback.onJobComplete(execution(32, "job-32")), "still inside the 120 s window");

            now.addAndGet(2_000L);
            assertTrue(callback.onJobComplete(execution(32, "job-32")),
                "past the window: the existing intervention path, handled");
            assertTrue(((Map<Integer, String>) getField(backend, "execIdToJobName")).isEmpty(),
                "tracking released as before");
            jobPairsMock.verify(() -> JobPairs.setPairStatusPreciseResult(
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.anyBoolean()), Mockito.never());
        }
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

        Path output = terminalOutput(StatusCode.STATUS_COMPLETE);
        execToJob.put(11, "job-11");
        execToPair.put(11, 111);
        execToOut.put(11, output);

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
                            StatusCode.STATUS_COMPLETE.getVal(),
                            StatusCode.STATUS_NOT_REACHED.getVal(),
                            false
                        )
                )
                .thenReturn(PairStatusResult.FAILED, PairStatusResult.APPLIED);

            assertFalse(callback.onJobComplete(execution(11, "job-11")));
            assertEquals("job-11", execToJob.get(11));
            assertEquals(Integer.valueOf(111), execToPair.get(11));
            assertEquals(output, execToOut.get(11));

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

        Path output = terminalOutput(StatusCode.STATUS_COMPLETE);
        execToJob.put(21, "job-21");
        execToPair.put(21, 211);
        execToOut.put(21, output);

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

        Path output = terminalOutput(StatusCode.ERROR_RUNSCRIPT);
        execToJob.put(12, "job-12");
        execToPair.put(12, 222);
        execToOut.put(12, output);

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
            assertEquals(output, execToOut.get(12));

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

        Path output = terminalOutput(StatusCode.STATUS_COMPLETE);
        execToJob.put(13, "job-13");
        execToPair.put(13, 313);
        execToOut.put(13, output);

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
                            StatusCode.STATUS_COMPLETE.getVal(),
                            StatusCode.STATUS_NOT_REACHED.getVal(),
                            false
                        )
                )
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(PairStatusResult.APPLIED);

            assertFalse(callback.onJobComplete(execution(13, "job-13")));
            assertEquals("job-13", execToJob.get(13));
            assertEquals(Integer.valueOf(313), execToPair.get(13));
            assertEquals(output, execToOut.get(13));

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

        Path output = terminalOutput(StatusCode.ERROR_RUNSCRIPT);
        execToJob.put(14, "job-14");
        execToPair.put(14, 414);
        execToOut.put(14, output);

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
            assertEquals(output, execToOut.get(14));

            assertTrue(callback.onJobFailed(execution(14, "job-14"), "BackoffLimitExceeded"));
            assertTrue(execToJob.isEmpty());
            assertTrue(execToPair.isEmpty());
            assertTrue(execToOut.isEmpty());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void evidenceLessFailureDoesNotOverwriteAnExistingScientificResult()
        throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Map<Integer, String> execToJob =
            (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) getField(backend, "execIdToPairId");

        execToJob.put(15, "job-15");
        execToPair.put(15, 415);
        // No owned output directory: the lifecycle event supplies no scientific evidence.
        givenSafeCluster(backend);
        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(415))
                .thenReturn(foundLookup(StatusCode.STATUS_COMPLETE.getVal()));

            assertTrue(callback.onJobFailed(
                execution(15, "job-15"), "BackoffLimitExceeded"));

            jobPairsMock.verify(
                () -> JobPairs.setPairStatusPreciseResult(
                    Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                    Mockito.anyInt(), Mockito.anyBoolean()),
                Mockito.never()
            );
            jobPairsMock.verify(() -> JobPairs.setEndTime(Mockito.anyInt()), Mockito.never());
        }

        assertTrue(execToJob.isEmpty());
        assertTrue(execToPair.isEmpty());
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
                Backend.KillOutcome.UNPROVEN,
                killConfirmed(backend, 7),
                "a pod in phase " + phase + " means the execution may still run"
            );
            assertTrue(
                holdingSlot(backend).contains(7),
                "accounting must be retained while phase=" + phase
            );
            assertFalse(
                trackingMap(backend, "execIdToJobName").isEmpty(),
                "tracking must be retained while phase=" + phase
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
                Backend.KillOutcome.CONFIRMED_SAFE,
                killConfirmed(backend, 8),
                "phase " + phase + " is terminal and therefore safe"
            );
            assertFalse(holdingSlot(backend).contains(8));
        }
    }

    /**
     * A running pod's Job outlives its foreground delete by the time the pod takes to stop.
     * The kill waits for that rather than calling it unproven on the first read. Reported at
     * once, every kill of a running pod was unproven: deleting a running job left its pairs
     * RUNNING with nothing to retire them, and deleting its owner was refused.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void aKillWaitsForTheForegroundDeletionToFinish() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        setField(backend, "killConfirmWaitMillis", 5_000L);
        setField(backend, "killConfirmPollMillis", 1L);
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(42, "job-42");
        // By the time the Job is gone its pod has stopped: that is what Foreground waits for.
        givenJobDeletion(backend, "job-42", true, null, podInPhase(42, "Succeeded"));
        Job terminating = jobWithNoConditions(42);
        Mockito.when(jobResource(backend, "job-42").get())
            .thenReturn(terminating, terminating, null);
        restore(backend, 42);

        assertEquals(Backend.KillOutcome.CONFIRMED_SAFE, killConfirmed(backend, 42));
        assertFalse(holdingSlot(backend).contains(42), "the slot is released once the Job is gone");
    }

    /** The wait is bounded: a Job that outlives it still leaves the kill unproven and held. */
    @Test
    @SuppressWarnings("unchecked")
    public void aKillStaysUnprovenWhenTheJobOutlivesTheWait() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        setField(backend, "killConfirmWaitMillis", 50L);
        setField(backend, "killConfirmPollMillis", 5L);
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(43, "job-43");
        givenJobDeletion(backend, "job-43", true, jobWithNoConditions(43), podInPhase(43, "Running"));
        restore(backend, 43);

        long start = System.nanoTime();
        assertEquals(Backend.KillOutcome.UNPROVEN, killConfirmed(backend, 43));
        assertTrue(System.nanoTime() - start < java.util.concurrent.TimeUnit.SECONDS.toNanos(5),
            "the wait must be bounded");
        assertTrue(holdingSlot(backend).contains(43), "accounting is retained");
    }

    /**
     * A stop requested up front is waited for from that request, not from the confirmation's
     * turn in the batch: N pods hanging in Terminating then cost one wait, not N.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void aRequestedStopIsWaitedForFromTheRequest() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        setField(backend, "killConfirmWaitMillis", 2_000L);
        setField(backend, "killConfirmPollMillis", 10L);
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(44, "job-44");
        givenJobDeletion(backend, "job-44", true, jobWithNoConditions(44), podInPhase(44, "Running"));
        restore(backend, 44);

        backend.requestStop(44);
        Thread.sleep(1_200L); // the confirmations ahead of this one in its batch
        long start = System.nanoTime();
        assertEquals(Backend.KillOutcome.UNPROVEN, killConfirmed(backend, 44));
        long waitedMillis =
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(waitedMillis < 1_600L,
            "waited " + waitedMillis + " ms, but the 2 s wait began at the request");
        // The request itself started the deletion; the confirmation repeated it.
        Mockito.verify(jobResource(backend, "job-44"), Mockito.atLeast(2))
            .withPropagationPolicy(io.fabric8.kubernetes.api.model.DeletionPropagation.FOREGROUND);
    }

    /** The mocked Job resource {@code givenJobDeletion} installed, to script its reads. */
    private ScalableResource<Job> jobResource(KubernetesNativeBackend backend, String jobName)
        throws Exception {
        KubernetesClient client = (KubernetesClient) getField(backend, "kubernetesClient");
        return client.batch().v1().jobs().inNamespace("starexec").withName(jobName);
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
            Backend.KillOutcome.UNPROVEN,
            killConfirmed(backend, 21),
            "a Job with backoffLimit=3 at failed=1 can still create another pod"
        );
        assertTrue(
            holdingSlot(backend).contains(21),
            "its slot must be retained so no unrelated pair lands beside the retry"
        );
        assertFalse(
            trackingMap(backend, "execIdToJobName").isEmpty(),
            "tracking must be retained"
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
                spent ? Backend.KillOutcome.CONFIRMED_SAFE : Backend.KillOutcome.UNPROVEN,
                killConfirmed(backend, 22),
                type + "=" + status + " should" + (spent ? "" : " not") + " make the"
                    + " controller spent"
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
            Backend.KillOutcome.UNPROVEN,
            killConfirmed(backend, 23),
            "succeeded>0 is a pod counter, not a terminal Job condition"
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
            Backend.KillOutcome.UNPROVEN,
            killConfirmed(backend, 24),
            "absence of information is not evidence of safety"
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
            Backend.KillOutcome.UNPROVEN,
            killConfirmed(backend, 25),
            "an unreadable Job listing cannot establish that no controller survives"
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
            Backend.KillOutcome.UNPROVEN,
            killConfirmed(backend, 26),
            "a Failed pod plus a live Job means the next attempt may still start"
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
            Backend.KillOutcome.UNPROVEN,
            killConfirmed(backend, 27),
            "a live Job carrying exec-id=27 is found by label even with no local name"
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
        assertTrue(holdingSlot(backend).contains(28), "the slot is held");
        assertFalse(
            ((Map<Integer, ?>) getField(backend, "unverifiedExecutions")).isEmpty(),
            "and the obligation is recorded, not forgotten"
        );

        // The pod goes away and no controller remains. The sweep re-checks BOTH halves --
        // a pod-only design could not make this transition, because it kept no record of
        // the execution once its pod vanished.
        givenPodsFor((KubernetesClient) getField(backend, "kubernetesClient"));
        invokePrivate(backend, "revisitUnverifiedExecutions");

        assertFalse(holdingSlot(backend).contains(28), "the hold is released");
        assertTrue(
            ((Map<Integer, ?>) getField(backend, "unverifiedExecutions")).isEmpty(),
            "and the record is cleared"
        );
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, ?> unverified(KubernetesNativeBackend backend) throws Exception {
        return (Map<Integer, ?>) getField(backend, "unverifiedExecutions");
    }

    /**
     * Foreground propagation keeps a Job present, behind its finalizer, until its pods are
     * gone, so killing a pair whose pod is still running normally finds the Job still there.
     * That hold needs an owner too. Without one the slot stayed reserved until the JVM
     * restarted: reproduced on microk8s by pausing a job with a running pair, which left the
     * pair RUNNING and, at maxConcurrentJobs=1, dispatch stopped.
     */
    @Test
    public void aKillWhoseJobOutlivesTheDeleteIsRevisitedUntilItIsGone() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(40, "job-40");
        givenJobDeletion(backend, "job-40", true, jobWithNoConditions(40), podInPhase(40, "Running"));
        restore(backend, 40);

        assertEquals(Backend.KillOutcome.UNPROVEN, killConfirmed(backend, 40));
        assertTrue(holdingSlot(backend).contains(40), "the slot is held while the Job is still there");
        assertTrue(unverified(backend).containsKey(40), "and the hold has an owner that will revisit it");

        // The foreground deletion completes: neither the Job nor its pod remains.
        KubernetesClient client = (KubernetesClient) getField(backend, "kubernetesClient");
        givenJobsFor(client);
        givenPodsFor(client);
        invokePrivate(backend, "revisitUnverifiedExecutions");

        assertFalse(holdingSlot(backend).contains(40), "the hold is released");
        assertFalse(unverified(backend).containsKey(40), "and the record is cleared");
    }

    /** The same for the admin pause-all path, which retains what it cannot confirm stopped. */
    @Test
    public void aKillAllThatRetainsAnExecutionHandsItToTheSweep() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(41, "job-41");
        givenJobDeletion(backend, "job-41", true, jobWithNoConditions(41), podInPhase(41, "Running"));
        restore(backend, 41);

        assertFalse(backend.killAll(), "killAll cannot report success while the Job is still there");
        assertTrue(holdingSlot(backend).contains(41));
        assertTrue(unverified(backend).containsKey(41), "the retained execution has an owner");

        KubernetesClient client = (KubernetesClient) getField(backend, "kubernetesClient");
        givenJobsFor(client);
        givenPodsFor(client);
        invokePrivate(backend, "revisitUnverifiedExecutions");

        assertFalse(holdingSlot(backend).contains(41), "the hold is released");
        assertFalse(unverified(backend).containsKey(41), "and the record is cleared");
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
            holdingSlot(backend).contains(29),
            "still running, so the slot stays held"
        );
        assertFalse(
            ((Map<Integer, ?>) getField(backend, "unverifiedExecutions")).isEmpty(),
            "and the obligation stays on the books"
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
            ((Set<Integer>) getField(backend, "legacyKilledExecIds")).contains(31),
            "marking a merely-safe execution as killed cancels the continuation: every"
                + " terminal callback short-circuits on the stopped-execution state and"
                + " returns true, so the monitor drops its cleanup-pending record with the"
                + " DB transition never written and the pair stranded at ENQUEUED"
        );
        assertTrue(
            ((Set<ExecutionRef>) getField(backend, "killedExecutions"))
                .stream()
                .noneMatch(ref -> ref.execId() == 31),
            "and no concrete cancellation may be recorded for it either"
        );
        assertEquals(
            Integer.valueOf(4242),
            ((Map<Integer, Integer>) getField(backend, "execIdToPairId")).get(31),
            "and the continuation still resolves its pair id from this map"
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
            admissionDegraded(backend),
            "a running managed pod with no usable exec-id cannot hold a slot, so admission"
                + " itself has to be the thing that defers"
        );
        assertFalse(
            backend.isQueueDispatchable("all.q"),
            "and the defer must be visible at the dispatch gate"
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

        assertFalse(admissionDegraded(backend), "the condition must be self-healing");
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

        assertTrue(admissionDegraded(backend),
            "a failed observation must never clear a safety hold");
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

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void startupReconciliationWithoutAJobLeavesRunningResultUnresolved()
        throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");

        KubernetesClient client = Mockito.mock(KubernetesClient.class);
        BatchAPIGroupDSL batch = Mockito.mock(BatchAPIGroupDSL.class);
        V1BatchAPIGroupDSL v1 = Mockito.mock(V1BatchAPIGroupDSL.class);
        MixedOperation jobs = Mockito.mock(MixedOperation.class);
        NonNamespaceOperation namespacedJobs = Mockito.mock(NonNamespaceOperation.class);
        io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable managedJobs =
            Mockito.mock(io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable.class);
        JobList noJobs = new JobList();
        noJobs.setItems(List.of());
        Mockito.when(client.batch()).thenReturn(batch);
        Mockito.when(batch.v1()).thenReturn(v1);
        Mockito.when(v1.jobs()).thenReturn(jobs);
        Mockito.when(jobs.inNamespace(Mockito.anyString())).thenReturn(namespacedJobs);
        Mockito.when(namespacedJobs.withLabel(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(managedJobs);
        Mockito.when(managedJobs.list()).thenReturn(noJobs);

        MixedOperation pods = Mockito.mock(MixedOperation.class);
        NonNamespaceOperation namespacedPods = Mockito.mock(NonNamespaceOperation.class);
        io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable managedPods =
            Mockito.mock(io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable.class);
        io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable pairPods =
            Mockito.mock(io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable.class);
        io.fabric8.kubernetes.api.model.PodList noPods =
            new io.fabric8.kubernetes.api.model.PodList();
        noPods.setItems(List.of());
        Mockito.when(client.pods()).thenReturn(pods);
        Mockito.when(pods.inNamespace(Mockito.anyString())).thenReturn(namespacedPods);
        Mockito.when(namespacedPods.withLabel(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(managedPods);
        Mockito.when(namespacedPods.withLabels(Mockito.anyMap())).thenReturn(pairPods);
        Mockito.when(managedPods.list()).thenReturn(noPods);
        Mockito.when(pairPods.list()).thenReturn(noPods);
        setField(backend, "kubernetesClient", client);

        int pairId = 4242;
        try (MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
            jobPairs.when(() -> JobPairs.getPairIdsByStatusCode(
                    StatusCode.STATUS_ENQUEUED.getVal()))
                .thenReturn(List.of());
            jobPairs.when(() -> JobPairs.getPairIdsByStatusCode(
                    StatusCode.STATUS_RUNNING.getVal()))
                .thenReturn(List.of(pairId));

            invokePrivate(backend, "reconcileOrphanedPairs");

            jobPairs.verify(() -> JobPairs.tryMarkRunningAsFailed(Mockito.anyInt()),
                Mockito.never());
            jobPairs.verify(() -> JobPairs.setPairStatusPreciseResult(
                    Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                    Mockito.anyInt(), Mockito.anyBoolean()),
                Mockito.never());
        }
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
                confirmedAbsent(backend, dir.resolve("var.out")),
                "a missing file in a readable directory is proven absent"
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
            Assumptions.abort("POSIX permissions unavailable: " + e);
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
            Assumptions.assumeTrue(
                barrierHolds, "this JVM can read through a 000 directory (running as root?)"
            );

            assertFalse(
                confirmedAbsent(backend, artifact),
                "existence that cannot be determined must never be reported as absence"
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
            Assumptions.abort("symlinks unavailable on this filesystem: " + e);
        }
        try {
            assertFalse(
                confirmedAbsent(backend, link),
                "a symlink standing where an artifact belongs is a surviving artifact"
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
            Backend.KillOutcome.UNPROVEN,
            killConfirmed(backend, 10),
            "a Running pod carrying the exec-id label means this is not safe,"
                + " whatever local tracking says"
        );
        assertTrue(
            holdingSlot(backend).contains(10),
            "a discovered live execution must enter the accounting"
        );
        assertEquals(1, activeCount(backend));
        // Identity is the pod's exec-id LABEL, not a reconstructed Job name. A name cannot
        // be reconstructed -- generateJobName mixes in System.currentTimeMillis() -- and an
        // earlier draft of this code fabricated one anyway, which would have reported a
        // live Job as absent. The retry owner is the recurring pod inventory, which selects
        // by that label.
        assertNull(
            trackingMap(backend, "execIdToJobName").get(10),
            "no Job name may be invented for an execution whose name was lost"
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
        assertFalse(holdingSlot(backend).contains(11), "the surviving reservation must go");
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

        assertEquals(2, holdingSlot(backend).size(), "both live executions must be counted");
        assertEquals(2, activeCount(backend), "the counter must agree with the set");
    }

    @Test
    public void newWorkStillRespectsTheCap() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "maxConcurrentJobs", 1);
        assertTrue(acquire(backend, 20));
        assertFalse(acquire(backend, 21), "a second NEW submission must not fit");
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

    // ---------------------------------------------------------------- active execution ids

    /**
     * A finished Job whose result the monitor has not ingested yet is still active (#324).
     *
     * <p>{@code Jobs.setBrokenPairsToErrorStatus} files every RUNNING pair whose execution is
     * not active as ERROR_SUBMIT_FAIL. On Quokka 8,527 pairs that had run to completion, each
     * with its status.json on disk, were filed that way because their Job had finished a
     * moment before the sweep and the monitor had not reached it yet.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void aFinishedJobAwaitingIngestionIsStillActive() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(31, "job-31");
        ((Map<Integer, String>) getField(backend, "execIdToJobUid")).put(31, "uid-31");
        givenManagedJobs(backend,
            finishedJob(31, "uid-31"));

        assertTrue(backend.getActiveExecutionIds().contains(31),
            "its pair still waits for the monitor");
    }

    /** Once ingestion released the execution, a finished Job is over: the sweep may act. */
    @Test
    public void aFinishedJobNoLongerTrackedIsNotActive() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        givenManagedJobs(backend,
            finishedJob(32, "uid-32"));

        assertFalse(backend.getActiveExecutionIds().contains(32));
    }

    /**
     * A finished Job that is not the execution occupying its id is not kept alive by the
     * other one's tracking: execution ids repeat, Job UIDs never do.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void aFinishedJobOfAnEarlierExecutionIsNotActive() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(33, "job-33");
        ((Map<Integer, String>) getField(backend, "execIdToJobUid")).put(33, "uid-current");
        givenManagedJobs(backend,
            finishedJob(33, "uid-earlier"));

        assertFalse(backend.getActiveExecutionIds().contains(33));
    }

    /** Unchanged: a Job that has not finished is active, tracked or not. */
    @Test
    public void anUnfinishedJobIsActive() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        Job running = jobWithNoConditions(34);
        running.getStatus().setActive(1);
        givenManagedJobs(backend, running);

        assertTrue(backend.getActiveExecutionIds().contains(34));
    }

    /** A Job with a UID that has finished successfully. */
    private Job finishedJob(int execId, String uid) {
        Job job = jobWithNoConditions(execId);
        job.getMetadata().setUid(uid);
        job.getStatus().setSucceeded(1);
        return job;
    }

    /**
     * Gives the backend a client whose managed-Job listing,
     * {@code batch().v1().jobs().inNamespace(..).withLabel(..).list()}, returns {@code jobs}.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void givenManagedJobs(KubernetesNativeBackend backend, Job... jobs) throws Exception {
        KubernetesClient client = Mockito.mock(KubernetesClient.class);
        BatchAPIGroupDSL batch = Mockito.mock(BatchAPIGroupDSL.class);
        V1BatchAPIGroupDSL v1 = Mockito.mock(V1BatchAPIGroupDSL.class);
        MixedOperation jobOps = Mockito.mock(MixedOperation.class);
        NonNamespaceOperation namespaced = Mockito.mock(NonNamespaceOperation.class);
        io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable filtered =
            Mockito.mock(io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable.class);
        JobList listing = new JobList();
        listing.setItems(List.of(jobs));
        Mockito.when(client.batch()).thenReturn(batch);
        Mockito.when(batch.v1()).thenReturn(v1);
        Mockito.when(v1.jobs()).thenReturn(jobOps);
        Mockito.when(jobOps.inNamespace(Mockito.anyString())).thenReturn(namespaced);
        Mockito.when(namespaced.withLabel(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(filtered);
        Mockito.when(filtered.list()).thenReturn(listing);
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
    /** Lifecycle cleanup must not publish any result while a pod may still execute. */
    @Test
    @SuppressWarnings("unchecked")
    public void stuckPendingPublishesNothingWhileThePodMayStillRun() throws Exception {
        // Publishing any terminal result while this pod can still start would let it write
        // output after the monitor had already retired its execution.
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");

        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(41, "job-41");
        ((Map<Integer, Integer>) getField(backend, "execIdToPairId")).put(41, 441);
        givenNeverStartedEvidence(backend, 41, 1);
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
                callback.onJobStuckPending(execution(41, "job-41"), "not scheduled"),
                "the escalation must report incomplete so the monitor retries it"
            );
            jobPairsMock.verify(
                () -> JobPairs.retryNeverStartedPair(
                    Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()),
                Mockito.never()
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
            holdingSlot(backend).contains(41),
            "the slot stays held while the pod may run"
        );
        assertFalse(
            ((Map<Integer, ?>) getField(backend, "unverifiedExecutions")).isEmpty(),
            "and the obligation is recorded for the sweep"
        );
    }

    /**
     * A pod that never started wrote nothing, so there is nothing to ingest and no result to
     * record: the pair is queued again once. This used to go through ingestion, find no
     * status.json, report an ingestion intervention and leave the pair ENQUEUED until
     * FIND_BROKEN_JOB_PAIRS filed it ERROR_SUBMIT_FAIL -- which the automatic rerun never
     * selects, so the retry docs/BACKENDS.md promises never happened.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void stuckPendingWithoutStatusIsQueuedAgainOnce() throws Exception {
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

        Path output = java.nio.file.Files.createTempDirectory("k8s-stuck-no-status");
        output.toFile().deleteOnExit();
        execToJob.put(31, "job-31");
        execToPair.put(31, 431);
        execToOut.put(31, output);
        ((Map<Integer, Integer>) getField(backend, "execIdToAttemptNo")).put(31, 1);
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
                .when(() -> JobPairs.retryNeverStartedPair(431, 31, 1))
                .thenReturn(NeverStartedRetryResult.REQUEUED);
            assertTrue(
                callback.onJobStuckPending(
                    execution(31, "job-31"),
                    "not scheduled (Unschedulable): 0/6 nodes are available"
                )
            );

            jobPairsMock.verify(
                () -> JobPairs.retryNeverStartedPair(431, 31, 1), Mockito.times(1)
            );
            // Nothing ran: no solver result, stage or end time is written for it.
            jobPairsMock.verify(
                () -> JobPairs.setPairStatusPreciseResult(
                    Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                    Mockito.anyInt(), Mockito.anyBoolean()),
                Mockito.never()
            );
            jobPairsMock.verify(() -> JobPairs.setEndTime(Mockito.anyInt()), Mockito.never());
        }

        assertTrue(execToJob.isEmpty());
        assertTrue(execToPair.isEmpty());
        assertTrue(execToOut.isEmpty());
        assertEquals(0, activeJobCount.get());
        assertTrue(holdingSlot.isEmpty());
        assertTrue(java.nio.file.Files.isDirectory(output),
            "nothing the execution owned is deleted");
    }

    /**
     * The retry is spent once. A pod that fails to start a second time is filed as the
     * submit failure it is, now, instead of sitting ENQUEUED for the hours until
     * FIND_BROKEN_JOB_PAIRS -- holding its place in the queue the whole time.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void stuckPendingWhoseRetryIsSpentIsFiledNotLeftEnqueued() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Map<Integer, String> execToJob =
            (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) getField(backend, "execIdToPairId");
        Set<Integer> holdingSlot = (Set<Integer>) getField(backend, "jobsHoldingSlot");
        AtomicInteger activeJobCount =
            (AtomicInteger) getField(backend, "activeJobCount");

        execToJob.put(37, "job-37");
        execToPair.put(37, 437);
        givenNeverStartedEvidence(backend, 37, 1);
        holdingSlot.add(37);
        activeJobCount.set(1);
        givenJobDeletion(backend, "job-37", true, null);

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(437))
                .thenReturn(foundLookup(StatusCode.STATUS_ENQUEUED.getVal()));
            jobPairsMock
                .when(() -> JobPairs.retryNeverStartedPair(437, 37, 1))
                .thenReturn(NeverStartedRetryResult.FILED);

            assertTrue(callback.onJobStuckPending(execution(37, "job-37"), "Unschedulable"));
            jobPairsMock.verify(
                () -> JobPairs.retryNeverStartedPair(437, 37, 1), Mockito.times(1)
            );
            jobPairsMock.verify(
                () -> JobPairs.setPairStatusPreciseResult(
                    Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                    Mockito.anyInt(), Mockito.anyBoolean()),
                Mockito.never()
            );
        }

        assertTrue(execToJob.isEmpty());
        assertEquals(0, activeJobCount.get());
        assertTrue(holdingSlot.isEmpty());
    }

    /**
     * A pair that moved on -- rerun, killed, deleted, or taken by another execution -- is
     * not this execution's to settle. The database reports it and changes nothing; the
     * execution is still over, so its accounting is released.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void stuckPendingForAPairThatMovedOnChangesNothing() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Map<Integer, String> execToJob =
            (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) getField(backend, "execIdToPairId");
        Set<Integer> holdingSlot = (Set<Integer>) getField(backend, "jobsHoldingSlot");
        AtomicInteger activeJobCount =
            (AtomicInteger) getField(backend, "activeJobCount");

        execToJob.put(38, "job-38");
        execToPair.put(38, 438);
        givenNeverStartedEvidence(backend, 38, 1);
        holdingSlot.add(38);
        activeJobCount.set(1);
        givenJobDeletion(backend, "job-38", true, null);

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(438))
                .thenReturn(foundLookup(StatusCode.STATUS_ENQUEUED.getVal()));
            jobPairsMock
                .when(() -> JobPairs.retryNeverStartedPair(438, 38, 1))
                .thenReturn(NeverStartedRetryResult.STALE);

            assertTrue(callback.onJobStuckPending(execution(38, "job-38"), "Unschedulable"));
            jobPairsMock.verify(
                () -> JobPairs.retryNeverStartedPair(438, 38, 1), Mockito.times(1)
            );
        }

        assertTrue(execToJob.isEmpty());
        assertEquals(0, activeJobCount.get());
        assertTrue(holdingSlot.isEmpty());
    }

    /**
     * An unknown outcome proves nothing about the pair, so nothing is released: the
     * tracking and the slot stay, and the monitor's cleanup-pending record brings the
     * execution back until the database answers.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void stuckPendingIsRetriedWhileItsSettlementIsUnknown() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Map<Integer, String> execToJob =
            (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> execToPair =
            (Map<Integer, Integer>) getField(backend, "execIdToPairId");
        Set<Integer> holdingSlot = (Set<Integer>) getField(backend, "jobsHoldingSlot");
        AtomicInteger activeJobCount =
            (AtomicInteger) getField(backend, "activeJobCount");

        execToJob.put(39, "job-39");
        execToPair.put(39, 439);
        givenNeverStartedEvidence(backend, 39, 1);
        holdingSlot.add(39);
        activeJobCount.set(1);
        givenJobDeletion(backend, "job-39", true, null);

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(439))
                .thenReturn(foundLookup(StatusCode.STATUS_ENQUEUED.getVal()));
            jobPairsMock
                .when(() -> JobPairs.retryNeverStartedPair(439, 39, 1))
                .thenReturn(NeverStartedRetryResult.ERROR, NeverStartedRetryResult.REQUEUED);

            assertFalse(callback.onJobStuckPending(execution(39, "job-39"), "Unschedulable"));
            assertEquals("job-39", execToJob.get(39));
            assertEquals(Integer.valueOf(439), execToPair.get(39));
            assertEquals(1, activeJobCount.get());
            assertTrue(holdingSlot.contains(39));

            assertTrue(callback.onJobStuckPending(execution(39, "job-39"), "Unschedulable"));
        }

        assertTrue(execToJob.isEmpty());
        assertEquals(0, activeJobCount.get());
        assertTrue(holdingSlot.isEmpty());
    }

    /**
     * The database fences the retry on the attempt this execution was submitted for, so the
     * attempt it is given must be that one: a rerun that took the pair since has a later
     * attempt and must not be reset by an execution that no longer owns it.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void stuckPendingRetryCarriesTheExecutionsAttempt() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(40, "job-40");
        ((Map<Integer, Integer>) getField(backend, "execIdToPairId")).put(40, 440);
        ((Map<Integer, Integer>) getField(backend, "execIdToAttemptNo")).put(40, 3);
        ((Map<Integer, Path>) getField(backend, "execIdToOutputDir")).put(40, emptyOutputDir());
        ((Set<Integer>) getField(backend, "jobsHoldingSlot")).add(40);
        ((AtomicInteger) getField(backend, "activeJobCount")).set(1);
        givenJobDeletion(backend, "job-40", true, null);

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(440))
                .thenReturn(foundLookup(StatusCode.STATUS_ENQUEUED.getVal()));
            jobPairsMock
                .when(() -> JobPairs.retryNeverStartedPair(440, 40, 3))
                .thenReturn(NeverStartedRetryResult.REQUEUED);

            assertTrue(callback.onJobStuckPending(execution(40, "job-40"), "Unschedulable"));
            jobPairsMock.verify(
                () -> JobPairs.retryNeverStartedPair(440, 40, 3), Mockito.times(1)
            );
        }
    }

    /**
     * A status.json means the pod did start, between the last observation and the delete.
     * What it recorded is evidence, and evidence is ingested -- never discarded by queueing
     * the pair again.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void stuckPendingThatWroteAStatusIsIngestedNotQueuedAgain() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Path output = terminalOutput(StatusCode.ERROR_RUNSCRIPT);
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(42, "job-42");
        ((Map<Integer, Integer>) getField(backend, "execIdToPairId")).put(42, 442);
        ((Map<Integer, Path>) getField(backend, "execIdToOutputDir")).put(42, output);
        ((Set<Integer>) getField(backend, "jobsHoldingSlot")).add(42);
        ((AtomicInteger) getField(backend, "activeJobCount")).set(1);
        givenJobDeletion(backend, "job-42", true, null);

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(442))
                .thenReturn(foundLookup(StatusCode.STATUS_ENQUEUED.getVal()));
            jobPairsMock
                .when(
                    () ->
                        JobPairs.setPairStatusPreciseResult(
                            442,
                            1,
                            StatusCode.ERROR_RUNSCRIPT.getVal(),
                            StatusCode.STATUS_NOT_REACHED.getVal(),
                            false
                        )
                )
                .thenReturn(PairStatusResult.APPLIED);

            assertTrue(callback.onJobStuckPending(execution(42, "job-42"), "Unschedulable"));
            jobPairsMock.verify(
                () -> JobPairs.retryNeverStartedPair(
                    Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()),
                Mockito.never()
            );
        }
    }

    /**
     * An output directory this backend does not own proves nothing about status.json, so the
     * pair is not queued again: that would spend its one retry, and the next attempt would
     * clear whatever evidence is there. It goes through completion ingestion instead.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void stuckPendingWithNoOwnedOutputDirectoryIsNotQueuedAgain() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(45, "job-45");
        ((Map<Integer, Integer>) getField(backend, "execIdToPairId")).put(45, 445);
        ((Map<Integer, Integer>) getField(backend, "execIdToAttemptNo")).put(45, 1);
        ((Set<Integer>) getField(backend, "jobsHoldingSlot")).add(45);
        ((AtomicInteger) getField(backend, "activeJobCount")).set(1);
        givenJobDeletion(backend, "job-45", true, null);

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(445))
                .thenReturn(foundLookup(StatusCode.STATUS_ENQUEUED.getVal()));

            callback.onJobStuckPending(execution(45, "job-45"), "Unschedulable");
            jobPairsMock.verify(
                () -> JobPairs.retryNeverStartedPair(
                    Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()),
                Mockito.never()
            );
        }
    }

    /**
     * An output directory that cannot be read is "cannot tell", not "absent": the same
     * conflation confirmedAbsent exists to avoid. The pair is not queued again.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void stuckPendingWhoseOutputCannotBeReadIsNotQueuedAgain() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Path output = emptyOutputDir();
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(46, "job-46");
        ((Map<Integer, Integer>) getField(backend, "execIdToPairId")).put(46, 446);
        ((Map<Integer, Path>) getField(backend, "execIdToOutputDir")).put(46, output);
        ((Map<Integer, Integer>) getField(backend, "execIdToAttemptNo")).put(46, 1);
        ((Set<Integer>) getField(backend, "jobsHoldingSlot")).add(46);
        ((AtomicInteger) getField(backend, "activeJobCount")).set(1);
        givenJobDeletion(backend, "job-46", true, null);

        try {
            java.nio.file.Files.setPosixFilePermissions(
                output, java.nio.file.attribute.PosixFilePermissions.fromString("---------")
            );
        } catch (UnsupportedOperationException | java.io.IOException e) {
            Assumptions.abort("POSIX permissions unavailable: " + e);
        }
        try {
            // Root ignores the permission bits; confirm the barrier is real first.
            boolean barrierHolds;
            try {
                java.nio.file.Files.readAttributes(
                    output.resolve("status.json"),
                    java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS
                );
                barrierHolds = false;
            } catch (java.nio.file.NoSuchFileException e) {
                barrierHolds = false;
            } catch (java.io.IOException e) {
                barrierHolds = true;
            }
            Assumptions.assumeTrue(
                barrierHolds, "this JVM can read through a 000 directory (running as root?)"
            );

            KubernetesJobMonitor.JobCompletionCallback callback =
                instantiateCompletionCallback(backend);

            try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
                jobPairsMock
                    .when(() -> JobPairs.getPairStatusLookup(446))
                    .thenReturn(foundLookup(StatusCode.STATUS_ENQUEUED.getVal()));

                callback.onJobStuckPending(execution(46, "job-46"), "Unschedulable");
                jobPairsMock.verify(
                    () -> JobPairs.retryNeverStartedPair(
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()),
                    Mockito.never()
                );
            }
        } finally {
            java.nio.file.Files.setPosixFilePermissions(
                output, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
            );
        }
    }

    /**
     * Without the attempt it was submitted for, an execution cannot fence a retry: a
     * re-dispatched pair keeps the previous execution id until the new one is persisted. The
     * pair is left to FIND_BROKEN_JOB_PAIRS, and the execution is still released.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void stuckPendingWithoutAnAttemptIsLeftForTheSweep() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(47, "job-47");
        ((Map<Integer, Integer>) getField(backend, "execIdToPairId")).put(47, 447);
        ((Map<Integer, Path>) getField(backend, "execIdToOutputDir")).put(47, emptyOutputDir());
        Set<Integer> holdingSlot = (Set<Integer>) getField(backend, "jobsHoldingSlot");
        holdingSlot.add(47);
        AtomicInteger activeJobCount = (AtomicInteger) getField(backend, "activeJobCount");
        activeJobCount.set(1);
        givenJobDeletion(backend, "job-47", true, null);

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(447))
                .thenReturn(foundLookup(StatusCode.STATUS_ENQUEUED.getVal()));

            assertTrue(callback.onJobStuckPending(execution(47, "job-47"), "Unschedulable"));
            jobPairsMock.verify(
                () -> JobPairs.retryNeverStartedPair(
                    Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()),
                Mockito.never()
            );
        }

        assertEquals(0, activeJobCount.get());
        assertTrue(holdingSlot.isEmpty());
    }

    /** An output directory a pod that never started leaves behind: there, and empty. */
    private static Path emptyOutputDir() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("k8s-never-started");
        dir.toFile().deleteOnExit();
        return dir;
    }

    /**
     * What a pod that never started leaves for its execution: an empty output directory,
     * which proves status.json absent, beside the attempt the execution was submitted for.
     */
    @SuppressWarnings("unchecked")
    private void givenNeverStartedEvidence(
        KubernetesNativeBackend backend,
        int execId,
        int attempt
    ) throws Exception {
        ((Map<Integer, Path>) getField(backend, "execIdToOutputDir")).put(execId, emptyOutputDir());
        ((Map<Integer, Integer>) getField(backend, "execIdToAttemptNo")).put(execId, attempt);
    }

    /**
     * After a restart the cluster still holds the finished Jobs of pairs the previous process
     * already recorded (they live for ttlSecondsAfterFinished), and this process tracks none
     * of them. Re-ingesting one used to report an ingestion intervention for a pair that
     * already held its result: 122 of them after one deploy on Quokka during the job 5 rerun.
     */
    @Test
    public void aJobRecordedBeforeARestartIsNotReportedAgain() throws Exception {
        List<String> messages = completeUntrackedJob(61, StatusCode.STATUS_COMPLETE);
        assertTrue(messages.stream().noneMatch(m -> m.contains("INTERVENTION")),
            messages.toString());
        assertTrue(messages.stream().anyMatch(
            m -> m.contains("Not ingesting") && m.contains("already holds terminal status 7")), messages.toString());
    }

    /** Any terminal status means the pair was settled, by whichever writer settled it. */
    @Test
    public void aJobWhosePairWasFiledAsASubmitFailureIsNotReportedAgain() throws Exception {
        List<String> messages = completeUntrackedJob(63, StatusCode.ERROR_SUBMIT_FAIL);
        assertTrue(messages.stream().noneMatch(m -> m.contains("INTERVENTION")),
            messages.toString());
    }

    /**
     * The same untracked Job whose pair is still waiting did lose its result, and that is
     * worth an intervention: the guard is for settled pairs only.
     */
    @Test
    public void anUntrackedJobWhosePairIsUnresolvedIsStillReported() throws Exception {
        List<String> messages = completeUntrackedJob(62, StatusCode.STATUS_ENQUEUED);
        assertTrue(messages.stream()
            .anyMatch(m -> m.contains("INGESTION INTERVENTION REQUIRED")), messages.toString());
    }

    /**
     * Completes a finished Job this process does not track, as after a restart: no tracking
     * maps, so the pair comes from the Job's own label (pair id = exec id x 10). Returns
     * everything the backend logged meanwhile.
     */
    private List<String> completeUntrackedJob(int execId, StatusCode pairStatus) throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        givenJobDeletion(backend, "job-" + execId, true, jobWithNoConditions(execId));
        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(KubernetesNativeBackend.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        ch.qos.logback.classic.Level original = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        logger.addAppender(appender);
        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(execId * 10))
                .thenReturn(foundLookup(pairStatus.getVal()));

            assertTrue(callback.onJobComplete(execution(execId, "job-" + execId)),
                "handled, not retried forever");
            // Nothing is written either way: no evidence is read for an untracked execution.
            jobPairsMock.verify(
                () -> JobPairs.setPairStatusPreciseResult(
                    Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                    Mockito.anyInt(), Mockito.anyBoolean()),
                Mockito.never()
            );
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(original);
            appender.stop();
        }
        return appender.list.stream()
            .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
            .toList();
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
        givenNeverStartedEvidence(backend, 32, 1);
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
                .when(() -> JobPairs.retryNeverStartedPair(432, 32, 1))
                .thenReturn(NeverStartedRetryResult.REQUEUED);

            assertTrue(callback.onJobStuckPending(execution(32, "job-32"), "Unschedulable"));
            jobPairsMock.verify(
                () -> JobPairs.setPairStatusPreciseResult(
                    Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                    Mockito.anyInt(), Mockito.anyBoolean()),
                Mockito.never()
            );
            jobPairsMock.verify(() -> JobPairs.setEndTime(Mockito.anyInt()), Mockito.never());
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
        Map<Integer, Path> execToOut =
            (Map<Integer, Path>) getField(backend, "execIdToOutputDir");
        Set<Integer> holdingSlot = (Set<Integer>) getField(backend, "jobsHoldingSlot");
        AtomicInteger activeJobCount =
            (AtomicInteger) getField(backend, "activeJobCount");

        Path output = terminalOutput(StatusCode.ERROR_RUNSCRIPT);
        execToJob.put(33, "job-33");
        execToPair.put(33, 433);
        execToOut.put(33, output);
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

            assertTrue(callback.onJobStuckPending(execution(33, "job-33"), "Unschedulable"));
        }

        assertEquals(0, activeJobCount.get());
        assertTrue(holdingSlot.isEmpty());
        assertTrue(execToJob.isEmpty());
    }

    /** The cleanup-pending record retains evidence ingestion after the Job is deleted. */
    @Test
    @SuppressWarnings("unchecked")
    public void stuckPendingRetainsTrackingWhenEvidenceWriteFails() throws Exception {
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

        Path output = terminalOutput(StatusCode.ERROR_RUNSCRIPT);
        execToJob.put(34, "job-34");
        execToPair.put(34, 434);
        execToOut.put(34, output);
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
                .thenReturn(PairStatusResult.FAILED);

            assertFalse(callback.onJobStuckPending(execution(34, "job-34"), "Unschedulable"));
        }

        assertEquals("job-34", execToJob.get(34));
        assertEquals(1, activeJobCount.get());
        assertTrue(holdingSlot.contains(34));
    }

    /** A lifecycle cleanup that cannot stop the Job may not publish a scientific result. */
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
        givenNeverStartedEvidence(backend, 35, 1);
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
            assertFalse(callback.onJobStuckPending(execution(35, "job-35"), "Unschedulable"));
            jobPairsMock.verify(
                () -> JobPairs.retryNeverStartedPair(
                    Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()),
                Mockito.never()
            );

            jobPairsMock.verify(
                () -> JobPairs.setPairStatusPreciseResult(
                    Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                    Mockito.anyInt(), Mockito.anyBoolean()),
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
        givenNeverStartedEvidence(backend, 36, 1);
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
                .when(() -> JobPairs.retryNeverStartedPair(436, 36, 1))
                .thenReturn(NeverStartedRetryResult.REQUEUED);
            assertTrue(callback.onJobStuckPending(execution(36, "job-36"), "Unschedulable"));
            jobPairsMock.verify(
                () -> JobPairs.setPairStatusPreciseResult(
                    Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                    Mockito.anyInt(), Mockito.anyBoolean()),
                Mockito.never()
            );
            jobPairsMock.verify(() -> JobPairs.setEndTime(Mockito.anyInt()), Mockito.never());
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
                    accepted.contains(label.trim()),
                    "affinity does not accept a label the view calls default: '" + label + "'"
                );
            } else {
                nonDefaultLabels++;
                // The converse matters just as much: if the affinity accepted a label the
                // view treats as a distinct queue, default-queue pods would be scheduled
                // onto hardware reserved for that queue.
                assertFalse(
                    accepted.contains(label.trim()),
                    "affinity accepts '" + label + "', which the view calls queue '"
                        + normalized + "', not the default"
                );
            }
        }
        assertTrue(defaultLabels > 0, "vacuous: no default-mapping label exercised");
        assertTrue(nonDefaultLabels > 0, "vacuous: no distinct-queue label exercised");
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
        assertTrue(isWholeNumberCpuQuantity("1"), "1 CPU");
        assertTrue(
            isWholeNumberCpuQuantity("32"),
            "32 is what production configures -- a whole compute node -- and it must no"
                + " longer be replaced by 1"
        );
        assertTrue(isWholeNumberCpuQuantity("2000m"), "milli-CPU that divides exactly");
        assertTrue(isWholeNumberCpuQuantity(" 16 "), "whitespace tolerated");
    }

    @Test
    public void fractionalCpuRequestsAreRejected() throws Exception {
        // A fractional request cannot receive exclusive cores; Kubernetes gives it a CFS
        // bandwidth quota and the solver's threads float across the whole node.
        assertFalse(isWholeNumberCpuQuantity("1500m"), "1500m is 1.5 CPUs");
        assertFalse(isWholeNumberCpuQuantity("500m"), "half a CPU");
        assertFalse(isWholeNumberCpuQuantity("2.5"), "decimal form");
        assertFalse(isWholeNumberCpuQuantity("2.0"), "even a whole-valued decimal");
    }

    @Test
    public void nonsenseAndNonPositiveCpuRequestsAreRejected() throws Exception {
        assertFalse(isWholeNumberCpuQuantity(null));
        assertFalse(isWholeNumberCpuQuantity(""));
        assertFalse(isWholeNumberCpuQuantity("   "));
        assertFalse(isWholeNumberCpuQuantity("all"));
        assertFalse(isWholeNumberCpuQuantity("0"), "zero CPUs is not a pair's worth");
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
            "32",
            container.getResources().getLimits().get("cpu").toString(),
            "the operator's CPU limit must survive into the pod spec, not be replaced by 1"
        );
        assertEquals(
            "32",
            container.getResources().getRequests().get("cpu").toString(),
            "requests must match limits, or the pod is not Guaranteed and Kubernetes will"
                + " never assign it exclusive cores"
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
            "IfNotPresent",
            container.getImagePullPolicy(),
            "the operator's pull policy must reach the pod, or isolated workers are asked"
                + " to re-resolve an image from a registry they cannot reach"
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
            unset.getImagePullPolicy(),
            "an empty setting must leave the field absent, not stamp a fabricated default"
        );

        Container never = buildContainer("32");
        assertNull(
            never.getImagePullPolicy(),
            "a backend that never loaded the setting must behave the same way"
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
            "32",
            resolveCpuLimitForStrictMode("32"),
            "strict mode must validate the operator's value, never replace it -- a whole"
                + " compute node is the SGE-equivalent allocation and must survive"
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
                expected.getMessage().contains("whole number of CPUs"),
                expected.getMessage()
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
            org.starexec.constants.R.DEFAULT_QUEUE_NAME,
            f.get(null),
            "the backend's default queue must be the canonical R.DEFAULT_QUEUE_NAME, or a"
                + " fresh cluster invents a queue the rest of StarExec does not know"
        );
        assertEquals("all.q", f.get(null));
    }

    @Test
    public void bothSpellingsOfTheDefaultQueueAreRecognised() throws Exception {
        assertTrue(isDefaultQueueName("all.q"), "canonical name");
        assertTrue(
            isDefaultQueueName("all"),
            "the SGE host-group short form, which Queues.removeQueue passes to moveNode;"
                + " unrecognised, it was written as a literal label and became a third queue"
        );
        // Deliberately reversed. This asserted "case tolerated" -- isDefaultQueueName was
        // the only case-insensitive queue comparison in the system, while the node-label
        // list and GetIdByName are both exact. That let an admin create a distinct queue
        // 'ALL.q' whose pairs were then given the default queue's affinity and run on the
        // default queue's hardware. A case variant is a different queue, everywhere.
        assertFalse(isDefaultQueueName("ALL.Q"), "a case variant is a distinct queue");
        assertFalse(isDefaultQueueName("All.q"), "a case variant is a distinct queue");
        assertTrue(isDefaultQueueName(" all.q "), "whitespace tolerated");
    }

    @Test
    public void arealQueueIsNotMistakenForTheDefault() throws Exception {
        assertFalse(isDefaultQueueName("casc.q"));
        assertFalse(isDefaultQueueName("public.q"));
        assertFalse(isDefaultQueueName("default"), "the old invented name is not special");
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
            isNodeSchedulable(Boolean.TRUE, "True"),
            "an operator draining a node must not have work sent to it"
        );
    }

    @Test
    public void anUncordonedButNotReadyNodeIsNotSchedulable() throws Exception {
        // The case a cordon-only check misses: kubelet stopped, disk pressure, a network
        // partition. The node accepts nothing, so a pod sent there pends indefinitely.
        assertFalse(isNodeSchedulable(Boolean.FALSE, "False"));
        assertFalse(isNodeSchedulable(Boolean.FALSE, "Unknown"), "Unknown is not Ready");
        assertFalse(isNodeSchedulable(Boolean.FALSE, null), "no Ready condition at all");
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
            backend.isQueueDispatchable("casc.q"),
            "a transient outage must defer dispatch, not let the pairs through to be"
                + " marked terminally failed"
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
        int execId
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
        Object record;
        try {
            record = read.invoke(callback, ref);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }

        Method m = callbackClass.getDeclaredMethod(
            "readTerminalStatus", ExecutionRef.class, com.google.gson.JsonObject.class
        );
        m.setAccessible(true);
        try {
            return (Integer) m.invoke(callback, ref, record);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
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
            org.starexec.data.to.Status.StatusCode.EXCEED_CPU.getVal(),
            readTerminalStatus(backend, 9001),
            "runsolver reported TIMEOUT=true, so STATUS_COMPLETE from status.json must"
                + " not be what gets recorded"
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
            readTerminalStatus(backend, 9002)
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
            org.starexec.data.to.Status.StatusCode.ERROR_RUNSCRIPT.getVal(),
            readTerminalStatus(backend, 9003),
            "the verdict must only override when runsolver actually reports a breach"
        );
    }

    @Test
    public void explicitRunscriptFailureOutranksRunsolverLimitArtifacts() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("k8s-verdict-runscript");
        java.nio.file.Files.writeString(
            dir.resolve("status.json"), "{\"status\": 11, \"stageNumber\": 3}"
        );
        java.nio.file.Files.writeString(
            dir.resolve("var.out"), "TIMEOUT=true\nMEMOUT=false\n"
        );
        java.nio.file.Files.writeString(
            dir.resolve("watcher.out"), "Maximum CPU time exceeded: ...\n"
        );

        KubernetesNativeBackend backend = backendWithOutputDir(9004, dir);

        assertEquals(
            org.starexec.data.to.Status.StatusCode.ERROR_RUNSCRIPT.getVal(),
            readTerminalStatus(backend, 9004),
            "valid status.json explicitly records a run-script failure; limit artifacts"
                + " must not replace that evidenced result"
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
                java.nio.file.Files.exists(dir.resolve(name)),
                "a previous attempt's " + name + " must not survive into the next attempt"
            );
        }

        // With the directory cleared there is no terminal record to classify. The reader
        // must refuse rather than turning the caller's old default into a scientific result.
        KubernetesNativeBackend fresh = backendWithOutputDir(4242, dir);
        try {
            readTerminalStatus(fresh, 4242);
            fail("an empty rerun attempt must not inherit a successful terminal status");
        } catch (StageStatusSnapshots.InvalidSnapshotException expected) {
            assertTrue(expected.getMessage().contains("result is unknown"));
        }
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
            Assumptions.abort("POSIX permissions unavailable: " + e);
        }

        try {
            KubernetesNativeBackend backend = new KubernetesNativeBackend();
            Method clear = KubernetesNativeBackend.class.getDeclaredMethod(
                "clearStaleAttemptArtifacts", Path.class, int.class
            );
            clear.setAccessible(true);
            boolean allAbsent = (Boolean) clear.invoke(backend, dir, 5150);

            // Running as root defeats the permission bits; skip rather than pass vacuously.
            Assumptions.assumeTrue(
                java.nio.file.Files.exists(stale),
                "cannot simulate an undeletable file as this user"
            );

            assertFalse(
                allAbsent,
                "a surviving stale artifact must be reported as failure, so submitScript"
                    + " refuses to create the Job rather than misclassifying the run"
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
            queues.contains("all.q"),
            "an unlabelled worker node belongs to the default queue everywhere else,"
                + " so getQueues must report it too: " + queues
        );
        assertTrue(queues.contains("sat-comp"),
            "the real second queue is still reported: " + queues);
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
        assertTrue(queues.contains("all.q"),
            "blank label is default-queue membership: " + queues);
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
            nodeName,
            "without this the pod reports its own name as the host, which is not a row in"
                + " nodes, and every pair's measurements are lost to P0002"
        );
        assertNotNull(
            nodeName.getValueFrom(),
            "it must come from the downward API, not a literal: the scheduler picks the"
                + " node after the Job is created"
        );
        assertNotNull(nodeName.getValueFrom().getFieldRef());
        assertEquals(
            "spec.nodeName",
            nodeName.getValueFrom().getFieldRef().getFieldPath(),
            "spec.nodeName is the Node's metadata.name, which is what nodes.name holds"
        );
        assertNull(
            nodeName.getValue(),
            "a literal value would be a guess at where the scheduler will place the pod"
        );
    }

    @Test
    public void statsNodeNameIsNullWhenUnknownRatherThanInvented() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "appNodeName", "");

        Method resolve = null;
        for (Class<?> c : KubernetesNativeBackend.class.getDeclaredClasses()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("resolveStatsNodeName")) {
                    resolve = m;
                    resolve.setAccessible(true);
                }
            }
        }
        assertNotNull(resolve, "resolveStatsNodeName should exist on an inner class");

        // Instantiate the inner class that owns it, via its synthetic outer-instance ctor.
        java.lang.reflect.Constructor<?> ctor =
            resolve.getDeclaringClass().getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object owner = ctor.getParameterCount() == 1
            ? ctor.newInstance(backend)
            : ctor.newInstance();

        assertNull(
            resolve.invoke(owner, (Object) null),
            "an unknown node must yield null, not a placeholder: a name that cannot"
                + " resolve guarantees P0002 and discards the pair's measurements while"
                + " hiding why"
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
            backend.isQueueDispatchable("high-mem.q"),
            "an unlabelled queue must not be deferred silently -- it never resolves"
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
            backend.isQueueDispatchable("kubernetes.q"),
            "31 CPUs against a 32-CPU node leaves room for node DaemonSets and must be"
                + " allowed to dispatch"
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
            backend.isQueueDispatchable("kubernetes.q"),
            "a request equal to allocatable can never be scheduled and must hold dispatch"
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
            backend.isQueueDispatchable("small.q"),
            "every node is smaller than the request, so nothing will ever schedule"
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
            backend.isQueueDispatchable("kubernetes.q"),
            "capacity that could not be established must defer, not dispatch"
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
            (Boolean) schedulable.invoke(null, cordoned),
            "a cordoned node must not lend its CPUs to the capacity ceiling"
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

    /**
     * Job pods run untrusted solvers on the shared data volume, so the pod and its
     * container carry the restrictions #237 asks for: a non-root identity that matches
     * the volume's owner, no privilege escalation, no capabilities, the runtime seccomp
     * profile, and a read-only image filesystem.
     */
    @Test
    public void jobPodsRunAsNonRootWithDroppedCapabilities() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();

        Method podContextMethod =
            KubernetesNativeBackend.class.getDeclaredMethod("jobPodSecurityContext");
        podContextMethod.setAccessible(true);
        PodSecurityContext pod =
            (PodSecurityContext) podContextMethod.invoke(backend);

        assertEquals(Boolean.TRUE, pod.getRunAsNonRoot());
        assertEquals(Long.valueOf(1000), pod.getRunAsUser());
        assertEquals(Long.valueOf(999), pod.getRunAsGroup());
        assertEquals(Long.valueOf(999), pod.getFsGroup());
        assertEquals("OnRootMismatch", pod.getFsGroupChangePolicy());
        assertNotNull(pod.getSeccompProfile());
        assertEquals("RuntimeDefault", pod.getSeccompProfile().getType());

        Method containerContextMethod =
            KubernetesNativeBackend.class.getDeclaredMethod("jobContainerSecurityContext");
        containerContextMethod.setAccessible(true);
        SecurityContext container =
            (SecurityContext) containerContextMethod.invoke(backend);

        assertEquals(Boolean.FALSE, container.getAllowPrivilegeEscalation());
        assertEquals(Boolean.TRUE, container.getReadOnlyRootFilesystem());
        assertEquals(List.of("ALL"), container.getCapabilities().getDrop());
    }

    // ------------------------------------------------ writable paths under a read-only root

    /** Builds the Job for a given backend working directory. */
    private PodSpec podSpecFor(String workingDir) throws Exception {
        KubernetesNativeBackend backend = backendWithCpu("1");
        Method build = KubernetesNativeBackend.class.getDeclaredMethod(
            "buildKubernetesJob",
            int.class, int.class, String.class, String.class, String.class, String.class);
        build.setAccessible(true);
        Job job = (Job) build.invoke(
            backend, 42, 7, "starexec-job-7", "/script.sh", workingDir, "/app/data/logs/log.txt");
        return job.getSpec().getTemplate().getSpec();
    }

    private static Map<String, String> mountsByPath(Container container) {
        Map<String, String> mounts = new java.util.HashMap<>();
        for (VolumeMount m : container.getVolumeMounts()) {
            assertNull(mounts.put(m.getMountPath(), m.getName()),
                "a job mount must not collide with another: " + m.getMountPath());
        }
        return mounts;
    }

    private static Volume volumeNamed(PodSpec pod, String name) {
        return pod.getVolumes().stream().filter(v -> name.equals(v.getName())).findFirst().orElse(null);
    }

    /**
     * The chart's default working directory is on the read-only root. The jobscript creates
     * every pair's private workspace under it, so without a mount of its own each pair failed
     * with ERROR_RUNSCRIPT ("mkdir: cannot create directory '/app/work/sandbox': Read-only
     * file system"), reproduced on microk8s.
     */
    @Test
    public void theDefaultWorkingDirectoryIsAWritableMount() throws Exception {
        PodSpec pod = podSpecFor("/app/work");
        Container container = pod.getContainers().get(0);

        assertEquals("/app/work", container.getWorkingDir(), "the container runs in the working directory");
        assertEquals(Boolean.TRUE,
            container.getSecurityContext().getReadOnlyRootFilesystem(), "the root filesystem stays read-only (#237)");
        String volume = mountsByPath(container).get("/app/work");
        assertNotNull(volume, "the working directory must be mounted");
        assertNotNull(volumeNamed(pod, volume).getEmptyDir(), "as an emptyDir");
    }

    /** A working directory on the data volume (as values-local-dev intends) is writable already. */
    @Test
    public void aWorkingDirectoryOnTheDataVolumeGetsNoSecondMount() throws Exception {
        assertOnlyDataAndTmpMounted(podSpecFor("/app/data/work"));
    }

    /** One under /tmp (as values-ci intends) is on an emptyDir already. */
    @Test
    public void aWorkingDirectoryUnderTmpGetsNoSecondMount() throws Exception {
        assertOnlyDataAndTmpMounted(podSpecFor("/tmp/work"));
    }

    /** Compared by path component: /app/database is not part of the data volume. */
    @Test
    public void aSiblingOfTheDataVolumeIsNotMistakenForIt() throws Exception {
        PodSpec pod = podSpecFor("/app/database/work");

        assertNotNull(mountsByPath(pod.getContainers().get(0)).get("/app/database/work"));
    }

    private static void assertOnlyDataAndTmpMounted(PodSpec pod) {
        Map<String, String> mounts = mountsByPath(pod.getContainers().get(0));
        assertEquals(Set.of("/app/data", "/tmp", "/app/home"),
            mounts.keySet(), "a nested emptyDir would hide what is on the volume at that path");
        assertEquals(3, pod.getVolumes().size());
    }

    /**
     * HOME is the pod's own writable emptyDir, set explicitly so it does not depend on the
     * configured runAsUser's passwd entry, whose home lies on the read-only root: solvers
     * that keep state under ~ (~/.cache, ~/.elan, Java prefs) failed or degraded.
     */
    @Test
    public void homeIsAWritableEmptyDirOfThePodsOwn() throws Exception {
        PodSpec pod = podSpecFor("/app/work");
        Container container = pod.getContainers().get(0);

        String home = container.getEnv().stream()
            .filter(e -> "HOME".equals(e.getName()))
            .map(io.fabric8.kubernetes.api.model.EnvVar::getValue)
            .findFirst().orElse(null);
        assertEquals("/app/home", home, "HOME must be set explicitly, to the pod's own mount");
        String volume = mountsByPath(container).get(home);
        assertNotNull(volume, "HOME must be a mount");
        assertNotNull(volumeNamed(pod, volume).getEmptyDir(), "an emptyDir, so it is per pod");
    }

    /** A working directory configured at or under HOME must not become a second mount there. */
    @Test
    public void aWorkingDirectoryUnderHomeGetsNoSecondMount() throws Exception {
        assertOnlyDataAndTmpMounted(podSpecFor("/app/home/work"));
    }

    // ---- #185: result writes are fenced on the attempt captured at submit ----------------

    private static final int COMPLETE = StatusCode.STATUS_COMPLETE.getVal();
    private static final int NOT_REACHED = StatusCode.STATUS_NOT_REACHED.getVal();

    /** An output directory holding a terminal status.json for {@code stage}, and optionally more. */
    private static Path fencedOutput(int pairId, int status, int stage, boolean statsAndAttrs)
        throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("k8s-fence-evidence");
        dir.toFile().deleteOnExit();
        java.nio.file.Files.writeString(
            dir.resolve("status.json"),
            "{\"status\":" + status + ",\"stageNumber\":" + stage + "}"
        );
        if (statsAndAttrs) {
            java.nio.file.Files.writeString(
                dir.resolve("stats.json"),
                "{\"pairId\":" + pairId + ",\"stageNumber\":" + stage
                    + ",\"wallclockTime\":1.5,\"cpuTime\":1.5,\"userTime\":1.0,\"systemTime\":0.5,"
                    + "\"maxVirtualMemory\":10,\"maxResidentSetSize\":10,\"diskSize\":10,"
                    + "\"hostname\":\"node-a\"}"
            );
            java.nio.file.Files.writeString(
                dir.resolve("attributes.txt"), "starexec-result=sat\n");
        }
        return dir;
    }

    /** Tracks the execution as submit would, then returns a callback for it. */
    @SuppressWarnings("unchecked")
    private KubernetesJobMonitor.JobCompletionCallback armFenced(
        KubernetesNativeBackend backend, int execId, int pairId, Path out, Integer attemptNo
    ) throws Exception {
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(execId, "job-" + execId);
        ((Map<Integer, Integer>) getField(backend, "execIdToPairId")).put(execId, pairId);
        ((Map<Integer, Path>) getField(backend, "execIdToOutputDir")).put(execId, out);
        if (attemptNo != null) {
            ((Map<Integer, Integer>) getField(backend, "execIdToAttemptNo")).put(execId, attemptNo);
        }
        givenSafeCluster(backend);
        return instantiateCompletionCallback(backend);
    }

    @SuppressWarnings("unchecked")
    private void assertNothingTracked(KubernetesNativeBackend backend, int execId)
        throws Exception {
        for (String name : new String[] {
            "execIdToJobName", "execIdToPairId", "execIdToOutputDir", "execIdToAttemptNo" }) {
            assertFalse(((Map<Integer, ?>) getField(backend, name)).containsKey(execId),
                name + " must be released");
        }
    }

    private void stubOpenPair(MockedStatic<JobPairs> jp, int pairId) throws Exception {
        jp.when(() -> JobPairs.getPairStatusLookup(pairId))
            .thenReturn(foundLookup(StatusCode.STATUS_RUNNING.getVal()));
        jp.when(() -> JobPairs.getStageNumbers(pairId)).thenReturn(Set.of(1));
    }

    @Test
    public void attemptCapturedAtSubmitReachesStatusStatsAndAttributeWrites() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Path out = fencedOutput(611, COMPLETE, 1, true);
        KubernetesJobMonitor.JobCompletionCallback callback = armFenced(backend, 61, 611, out, 4);

        try (MockedStatic<JobPairs> jp = Mockito.mockStatic(JobPairs.class)) {
            stubOpenPair(jp, 611);
            jp.when(() -> JobPairs.setPairStatusPreciseResult(611, 1, COMPLETE, NOT_REACHED, false, 4))
                .thenReturn(PairStatusResult.APPLIED);
            jp.when(() -> JobPairs.updateRunSolverStats(
                Mockito.eq(611), Mockito.eq("node-a"), Mockito.anyDouble(), Mockito.anyDouble(),
                Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyLong(),
                Mockito.eq(1), Mockito.anyLong(), Mockito.eq(Integer.valueOf(4)))).thenReturn(true);
            jp.when(() -> JobPairs.addJobPairAttributes(
                Mockito.eq(611), Mockito.eq(1), Mockito.any(java.util.Properties.class),
                Mockito.eq(Integer.valueOf(4)))).thenReturn(true);

            assertTrue(callback.onJobComplete(execution(61, "job-61")));

            jp.verify(() -> JobPairs.setPairStatusPreciseResult(611, 1, COMPLETE, NOT_REACHED, false, 4));
            jp.verify(() -> JobPairs.updateRunSolverStats(
                Mockito.eq(611), Mockito.eq("node-a"), Mockito.anyDouble(), Mockito.anyDouble(),
                Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyLong(),
                Mockito.eq(1), Mockito.anyLong(), Mockito.eq(Integer.valueOf(4))));
            jp.verify(() -> JobPairs.addJobPairAttributes(
                Mockito.eq(611), Mockito.eq(1), Mockito.any(java.util.Properties.class),
                Mockito.eq(Integer.valueOf(4))));
            jp.verify(() -> JobPairs.setPairStatusPreciseResult(
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.anyBoolean()), Mockito.never());
        }
        assertNothingTracked(backend, 61);
    }

    @Test
    public void attemptReachesEarlierStageStatusWrite() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Path out = fencedOutput(621, COMPLETE, 2, false);
        java.nio.file.Files.createDirectories(out.resolve("stage-status"));
        java.nio.file.Files.writeString(
            out.resolve("stage-status").resolve("1.json"),
            "{\"pairId\":621,\"stageNumber\":1,\"status\":" + COMPLETE + "}");
        KubernetesJobMonitor.JobCompletionCallback callback = armFenced(backend, 62, 621, out, 7);

        try (MockedStatic<JobPairs> jp = Mockito.mockStatic(JobPairs.class)) {
            stubOpenPair(jp, 621);
            jp.when(() -> JobPairs.setEarlierStageStatuses(621, Map.of(1, COMPLETE), 7))
                .thenReturn(StageStatusBatchResult.APPLIED);
            jp.when(() -> JobPairs.setPairStatusPreciseResult(621, 2, COMPLETE, NOT_REACHED, false, 7))
                .thenReturn(PairStatusResult.APPLIED);

            assertTrue(callback.onJobComplete(execution(62, "job-62")));

            jp.verify(() -> JobPairs.setEarlierStageStatuses(621, Map.of(1, COMPLETE), 7));
            jp.verify(() -> JobPairs.setPairStatusPreciseResult(621, 2, COMPLETE, NOT_REACHED, false, 7));
        }
        assertNothingTracked(backend, 62);
    }

    @Test
    public void attemptReachesPairLevelStatusWrite() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        int status = StatusCode.ERROR_RUNSCRIPT.getVal();
        Path out = fencedOutput(631, status, 0, false);
        KubernetesJobMonitor.JobCompletionCallback callback = armFenced(backend, 63, 631, out, 2);

        try (MockedStatic<JobPairs> jp = Mockito.mockStatic(JobPairs.class)) {
            stubOpenPair(jp, 631);
            jp.when(() -> JobPairs.setPairLevelStatusResult(631, status, status, 2))
                .thenReturn(PairStatusResult.APPLIED);

            assertTrue(callback.onJobComplete(execution(63, "job-63")));

            jp.verify(() -> JobPairs.setPairLevelStatusResult(631, status, status, 2));
            jp.verify(() -> JobPairs.setPairLevelStatusResult(
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()), Mockito.never());
        }
        assertNothingTracked(backend, 63);
    }

    @Test
    public void staleStatusWriteReleasesTheExecutionWithoutRetryOrFurtherWrites() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Path out = fencedOutput(641, COMPLETE, 1, true);
        KubernetesJobMonitor.JobCompletionCallback callback = armFenced(backend, 64, 641, out, 1);

        try (MockedStatic<JobPairs> jp = Mockito.mockStatic(JobPairs.class)) {
            stubOpenPair(jp, 641);
            jp.when(() -> JobPairs.setPairStatusPreciseResult(641, 1, COMPLETE, NOT_REACHED, false, 1))
                .thenReturn(PairStatusResult.STALE_ATTEMPT);

            assertTrue(callback.onJobComplete(execution(64, "job-64")),
                "a stale refusal is handled, not retried");

            jp.verify(() -> JobPairs.setPairStatusPreciseResult(
                641, 1, COMPLETE, NOT_REACHED, false, 1), Mockito.times(1));
            jp.verify(() -> JobPairs.updateRunSolverStats(
                Mockito.anyInt(), Mockito.any(), Mockito.anyDouble(), Mockito.anyDouble(),
                Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyLong(),
                Mockito.anyInt(), Mockito.anyLong(), Mockito.any()), Mockito.never());
            jp.verify(() -> JobPairs.addJobPairAttributes(
                Mockito.anyInt(), Mockito.anyInt(), Mockito.any(java.util.Properties.class),
                Mockito.any(Integer.class)), Mockito.never());
        }
        assertNothingTracked(backend, 64);
    }

    @Test
    public void staleEarlierStageWriteReleasesTheExecutionAndWritesNoStatus() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Path out = fencedOutput(651, COMPLETE, 2, false);
        java.nio.file.Files.createDirectories(out.resolve("stage-status"));
        java.nio.file.Files.writeString(
            out.resolve("stage-status").resolve("1.json"),
            "{\"pairId\":651,\"stageNumber\":1,\"status\":" + COMPLETE + "}");
        KubernetesJobMonitor.JobCompletionCallback callback = armFenced(backend, 65, 651, out, 3);

        try (MockedStatic<JobPairs> jp = Mockito.mockStatic(JobPairs.class)) {
            stubOpenPair(jp, 651);
            jp.when(() -> JobPairs.setEarlierStageStatuses(651, Map.of(1, COMPLETE), 3))
                .thenReturn(StageStatusBatchResult.STALE_ATTEMPT);

            assertTrue(callback.onJobComplete(execution(65, "job-65")));

            jp.verify(() -> JobPairs.setEarlierStageStatuses(
                Mockito.anyInt(), Mockito.anyMap(), Mockito.any(Integer.class)), Mockito.times(1));
            jp.verify(() -> JobPairs.setPairStatusPreciseResult(
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.anyBoolean(), Mockito.any(Integer.class)), Mockito.never());
        }
        assertNothingTracked(backend, 65);
    }

    @Test
    public void staleStatsRefusalSkipsAttributesAndReleasesTheExecution() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Path out = twoStageOutput(661);
        KubernetesJobMonitor.JobCompletionCallback callback = armFenced(backend, 66, 661, out, 4);

        try (MockedStatic<JobPairs> jp = Mockito.mockStatic(JobPairs.class)) {
            stubTwoStages(jp, 661, 4);
            // The stats writer reports a stale refusal as plain false; the pair has moved on.
            stubStatsWrite(jp, 661, 1, 4, false);
            jp.when(() -> JobPairs.getCurrentAttemptNo(661)).thenReturn(5);

            assertTrue(callback.onJobComplete(execution(66, "job-66")));

            // Stage 1's refusal ends the writes: neither stage 2's stats nor any attribute.
            verifyStatsWrites(jp, 661, Mockito.times(1));
            verifyAttributeWrites(jp, 661, Mockito.never());
        }
        assertNothingTracked(backend, 66);
    }

    @Test
    public void staleAttributeRefusalIsNotAFailure() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Path out = twoStageOutput(671);
        KubernetesJobMonitor.JobCompletionCallback callback = armFenced(backend, 67, 671, out, 4);

        try (MockedStatic<JobPairs> jp = Mockito.mockStatic(JobPairs.class)) {
            stubTwoStages(jp, 671, 4);
            stubStatsWrite(jp, 671, 1, 4, true);
            stubStatsWrite(jp, 671, 2, 4, true);
            stubAttributeWrite(jp, 671, 1, 4, false);
            jp.when(() -> JobPairs.getCurrentAttemptNo(671)).thenReturn(5);

            assertTrue(callback.onJobComplete(execution(67, "job-67")),
                "a stale refusal is handled, not retried");

            // Both stages' stats were written before the attributes were reached, and the
            // refusal of stage 1's attributes stopped the loop: stage 2's was never tried.
            verifyStatsWrites(jp, 671, Mockito.times(2));
            verifyAttributeWrites(jp, 671, Mockito.times(1));
            jp.verify(() -> JobPairs.addJobPairAttributes(
                Mockito.eq(671), Mockito.eq(2), Mockito.any(java.util.Properties.class),
                Mockito.any(Integer.class)), Mockito.never());
        }
        assertNothingTracked(backend, 67);
    }

    /** A stats write that says false while the pair is still on this attempt is a failure. */
    @Test
    public void aFailedStatsWriteOnTheCurrentAttemptIsNotTreatedAsStale() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Path out = twoStageOutput(691);
        KubernetesJobMonitor.JobCompletionCallback callback = armFenced(backend, 69, 691, out, 4);

        try (MockedStatic<JobPairs> jp = Mockito.mockStatic(JobPairs.class)) {
            stubTwoStages(jp, 691, 4);
            stubStatsWrite(jp, 691, 1, 4, false);
            stubStatsWrite(jp, 691, 2, 4, true);
            stubAttributeWrite(jp, 691, 1, 4, true);
            stubAttributeWrite(jp, 691, 2, 4, true);
            jp.when(() -> JobPairs.getCurrentAttemptNo(691)).thenReturn(4);

            assertTrue(callback.onJobComplete(execution(69, "job-69")));

            // The loop went on: stage 2's stats and both stages' attributes were written.
            verifyStatsWrites(jp, 691, Mockito.times(2));
            verifyAttributeWrites(jp, 691, Mockito.times(2));
        }
        assertNothingTracked(backend, 69);
    }

    /** As above for attributes: false on the current attempt must not end the loop. */
    @Test
    public void aFailedAttributeWriteOnTheCurrentAttemptIsNotTreatedAsStale() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Path out = twoStageOutput(701);
        KubernetesJobMonitor.JobCompletionCallback callback = armFenced(backend, 70, 701, out, 4);

        try (MockedStatic<JobPairs> jp = Mockito.mockStatic(JobPairs.class)) {
            stubTwoStages(jp, 701, 4);
            stubStatsWrite(jp, 701, 1, 4, true);
            stubStatsWrite(jp, 701, 2, 4, true);
            stubAttributeWrite(jp, 701, 1, 4, false);
            stubAttributeWrite(jp, 701, 2, 4, true);
            jp.when(() -> JobPairs.getCurrentAttemptNo(701)).thenReturn(4);

            assertTrue(callback.onJobComplete(execution(70, "job-70")));

            verifyAttributeWrites(jp, 701, Mockito.times(2));
        }
        assertNothingTracked(backend, 70);
    }

    /** Terminal at stage 2, stage 1 finished earlier, and a stats and attributes file each. */
    private static Path twoStageOutput(int pairId) throws Exception {
        Path dir = fencedOutput(pairId, COMPLETE, 2, false);
        java.nio.file.Files.createDirectories(dir.resolve("stage-status"));
        java.nio.file.Files.writeString(
            dir.resolve("stage-status").resolve("1.json"),
            "{\"pairId\":" + pairId + ",\"stageNumber\":1,\"status\":" + COMPLETE + "}");
        java.nio.file.Files.createDirectories(dir.resolve("stage-stats"));
        java.nio.file.Files.createDirectories(dir.resolve("stage-attributes"));
        for (int stage = 1; stage <= 2; stage++) {
            java.nio.file.Files.writeString(
                dir.resolve("stage-stats").resolve(stage + ".json"),
                "{\"pairId\":" + pairId + ",\"stageNumber\":" + stage
                    + ",\"wallclockTime\":1.5,\"cpuTime\":1.5,\"userTime\":1.0,\"systemTime\":0.5,"
                    + "\"maxVirtualMemory\":10,\"maxResidentSetSize\":10,\"diskSize\":10,"
                    + "\"hostname\":\"node-a\"}");
            java.nio.file.Files.writeString(
                dir.resolve("stage-attributes").resolve(stage + ".txt"),
                "starexec-result=sat\n");
        }
        return dir;
    }

    private void stubTwoStages(MockedStatic<JobPairs> jp, int pairId, int attempt)
        throws Exception {
        stubOpenPair(jp, pairId);
        jp.when(() -> JobPairs.getStageNumbers(pairId)).thenReturn(Set.of(1, 2));
        jp.when(() -> JobPairs.setEarlierStageStatuses(pairId, Map.of(1, COMPLETE), attempt))
            .thenReturn(StageStatusBatchResult.APPLIED);
        jp.when(() -> JobPairs.setPairStatusPreciseResult(
            pairId, 2, COMPLETE, NOT_REACHED, false, attempt)).thenReturn(PairStatusResult.APPLIED);
    }

    private void stubStatsWrite(
        MockedStatic<JobPairs> jp, int pairId, int stage, int attempt, boolean result) {
        jp.when(() -> JobPairs.updateRunSolverStats(
            Mockito.eq(pairId), Mockito.eq("node-a"), Mockito.anyDouble(), Mockito.anyDouble(),
            Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyLong(),
            Mockito.eq(stage), Mockito.anyLong(), Mockito.eq(Integer.valueOf(attempt))))
            .thenReturn(result);
    }

    private void stubAttributeWrite(
        MockedStatic<JobPairs> jp, int pairId, int stage, int attempt, boolean result) {
        jp.when(() -> JobPairs.addJobPairAttributes(
            Mockito.eq(pairId), Mockito.eq(stage), Mockito.any(java.util.Properties.class),
            Mockito.eq(Integer.valueOf(attempt)))).thenReturn(result);
    }

    private void verifyStatsWrites(
        MockedStatic<JobPairs> jp, int pairId, org.mockito.verification.VerificationMode mode) {
        jp.verify(() -> JobPairs.updateRunSolverStats(
            Mockito.eq(pairId), Mockito.any(), Mockito.anyDouble(), Mockito.anyDouble(),
            Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyLong(),
            Mockito.anyInt(), Mockito.anyLong(), Mockito.any(Integer.class)), mode);
    }

    private void verifyAttributeWrites(
        MockedStatic<JobPairs> jp, int pairId, org.mockito.verification.VerificationMode mode) {
        jp.verify(() -> JobPairs.addJobPairAttributes(
            Mockito.eq(pairId), Mockito.anyInt(), Mockito.any(java.util.Properties.class),
            Mockito.any(Integer.class)), mode);
    }

    @Test
    public void executionWithoutAnAttemptKeepsTheUnfencedWrites() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Path out = fencedOutput(681, COMPLETE, 1, false);
        KubernetesJobMonitor.JobCompletionCallback callback = armFenced(backend, 68, 681, out, null);

        try (MockedStatic<JobPairs> jp = Mockito.mockStatic(JobPairs.class)) {
            stubOpenPair(jp, 681);
            jp.when(() -> JobPairs.setPairStatusPreciseResult(681, 1, COMPLETE, NOT_REACHED, false))
                .thenReturn(PairStatusResult.APPLIED);

            assertTrue(callback.onJobComplete(execution(68, "job-68")));

            jp.verify(() -> JobPairs.setPairStatusPreciseResult(681, 1, COMPLETE, NOT_REACHED, false));
            jp.verify(() -> JobPairs.setPairStatusPreciseResult(
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.anyBoolean(), Mockito.any(Integer.class)), Mockito.never());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void rebuildTrackingReadsTheAttemptLabelAndTreatsItsAbsenceAsUnfenced() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Method rebuild = KubernetesNativeBackend.class
            .getDeclaredMethod("rebuildTrackingFromJob", Job.class, PodPhaseView.class);
        rebuild.setAccessible(true);
        Map<Integer, Integer> attempts =
            (Map<Integer, Integer>) getField(backend, "execIdToAttemptNo");

        try (MockedStatic<JobPairs> jp = Mockito.mockStatic(JobPairs.class)) {
            jp.when(() -> JobPairs.trySetPairRunning(Mockito.anyInt()))
                .thenReturn(JobPairs.ConditionalPairUpdateResult.UPDATED);
            Job labelled = new JobBuilder().withNewMetadata()
                .withName("starexec-job-91")
                .addToLabels("starexec.org/managed", "true")
                .addToLabels("starexec.org/exec-id", "91")
                .addToLabels("starexec.org/pair-id", "901")
                .addToLabels("starexec.org/attempt-no", "6")
                .addToAnnotations("starexec.org/output-dir", "/tmp/starexec/out/901")
                .endMetadata().withNewStatus().withActive(1).endStatus().build();
            Job legacy = new JobBuilder().withNewMetadata()
                .withName("starexec-job-92")
                .addToLabels("starexec.org/managed", "true")
                .addToLabels("starexec.org/exec-id", "92")
                .addToLabels("starexec.org/pair-id", "902")
                .addToAnnotations("starexec.org/output-dir", "/tmp/starexec/out/902")
                .endMetadata().withNewStatus().withActive(1).endStatus().build();

            rebuild.invoke(backend, labelled, viewWithPod(91, "Running"));
            rebuild.invoke(backend, legacy, viewWithPod(92, "Running"));
        }

        assertEquals(Integer.valueOf(6), attempts.get(91));
        assertNull(attempts.get(92), "a Job from before the label is unfenced, never attempt 0");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void destroyReleasesTheAttemptMapToo() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        Map<Integer, Integer> attempts =
            (Map<Integer, Integer>) getField(backend, "execIdToAttemptNo");
        attempts.put(3, 9);

        backend.destroyIf();

        assertTrue(attempts.isEmpty());
    }

    // ---- #185: submitScript captures the attempt --------------------------------------

    /** What a submitScript run did to the mocked cluster. */
    private static final class Submission {
        final List<Job> built = new java.util.ArrayList<>();
        final List<String> events = new java.util.ArrayList<>();
    }

    /**
     * A backend configured to build Jobs against a mocked client. {@code create()} returns the
     * Job it was given with a UID, throws {@code createFailure} when given, and a lookup by
     * name finds {@code presentJob} (null means the Job is absent).
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private KubernetesNativeBackend submittingBackend(
        Submission submission, RuntimeException createFailure, Job presentJob
    ) throws Exception {
        KubernetesNativeBackend backend = backendWithCpu("2");
        setField(backend, "initialized", true);
        setField(backend, "maxConcurrentJobs", 4);
        // Ids in use by earlier tests' worth of state cannot matter, but a recycled id must
        // be reachable: the counter starts at a known place.
        setField(backend, "nextExecId", 5);

        KubernetesClient client = Mockito.mock(KubernetesClient.class);
        BatchAPIGroupDSL batch = Mockito.mock(BatchAPIGroupDSL.class);
        V1BatchAPIGroupDSL v1 = Mockito.mock(V1BatchAPIGroupDSL.class);
        MixedOperation jobs = Mockito.mock(MixedOperation.class);
        NonNamespaceOperation namespaced = Mockito.mock(NonNamespaceOperation.class);
        ScalableResource<Job> created = Mockito.mock(ScalableResource.class);
        ScalableResource<Job> lookup = Mockito.mock(ScalableResource.class);

        Mockito.when(client.batch()).thenReturn(batch);
        Mockito.when(batch.v1()).thenReturn(v1);
        Mockito.when(v1.jobs()).thenReturn(jobs);
        Mockito.when(jobs.inNamespace(Mockito.any())).thenReturn(namespaced);
        Mockito.doAnswer(inv -> {
            submission.built.add((Job) inv.getArgument(0));
            submission.events.add("resource");
            return created;
        }).when(namespaced).resource(Mockito.any(Job.class));
        if (createFailure != null) {
            Mockito.when(created.create()).thenThrow(createFailure);
        } else {
            Mockito.when(created.create()).thenAnswer(inv ->
                new JobBuilder(submission.built.get(submission.built.size() - 1))
                    .editMetadata().withUid("uid-created").endMetadata().build());
        }
        Mockito.when(namespaced.withName(Mockito.anyString())).thenReturn(lookup);
        Mockito.when(lookup.get()).thenReturn(presentJob);
        setField(backend, "kubernetesClient", client);
        return backend;
    }

    private static String logIn(Path dir) {
        return dir.resolve("stdout.txt").toString();
    }

    @Test
    public void theAttemptIsReadAfterTheSlotIsHeldAndBeforeTheJobIsBuilt() throws Exception {
        Submission submission = new Submission();
        KubernetesNativeBackend backend = submittingBackend(submission, null, null);
        Path dir = java.nio.file.Files.createTempDirectory("k8s-submit");

        try (MockedStatic<JobPairs> jp = Mockito.mockStatic(JobPairs.class)) {
            jp.when(() -> JobPairs.getCurrentAttemptNo(801)).thenAnswer(inv -> {
                submission.events.add("attempt slots=" + holdingSlot(backend).size()
                    + " active=" + activeCount(backend));
                return 3;
            });

            int execId = backend.submitScript(801, "/script.sh", "/work", logIn(dir));

            assertTrue(execId > 0);
        }
        assertEquals(
            List.of("attempt slots=1 active=1", "resource"),
            submission.events,
            "one slot must already be held when the attempt is read, and no Job built yet"
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    public void anUnreadableAttemptDefersTheSubmissionAndLeavesNothingBehind() throws Exception {
        Submission submission = new Submission();
        KubernetesNativeBackend backend = submittingBackend(submission, null, null);
        Path dir = java.nio.file.Files.createTempDirectory("k8s-submit");

        try (MockedStatic<JobPairs> jp = Mockito.mockStatic(JobPairs.class)) {
            jp.when(() -> JobPairs.getCurrentAttemptNo(802))
                .thenThrow(new java.sql.SQLException("connection reset"));

            try {
                backend.submitScript(802, "/script.sh", "/work", logIn(dir));
                fail("an unreadable attempt must defer, not submit unfenced");
            } catch (org.starexec.backend.exception.SubmissionDeferredException expected) {
                // The pair stays queued and is retried on the next scheduling pass.
            }
            jp.verify(() -> JobPairs.setStartTime(Mockito.anyInt()), Mockito.never());
        }

        assertEquals(0, activeCount(backend), "the reservation must go back");
        assertTrue(holdingSlot(backend).isEmpty());
        assertTrue(submission.built.isEmpty(), "no Job may be created");
        for (String name : new String[] {
            "execIdToJobName", "execIdToPairId", "execIdToOutputDir", "execIdToAttemptNo",
            "execIdToJobUid" }) {
            assertTrue(((Map<Integer, ?>) getField(backend, name)).isEmpty(), name + " must be empty");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void theBuiltJobCarriesTheAttemptLabelAndTracksTheAttempt() throws Exception {
        Submission submission = new Submission();
        KubernetesNativeBackend backend = submittingBackend(submission, null, null);
        Path dir = java.nio.file.Files.createTempDirectory("k8s-submit");

        try (MockedStatic<JobPairs> jp = Mockito.mockStatic(JobPairs.class)) {
            jp.when(() -> JobPairs.getCurrentAttemptNo(803)).thenReturn(7);

            int execId = backend.submitScript(803, "/script.sh", "/work", logIn(dir));

            assertEquals("7", submission.built.get(0).getMetadata().getLabels()
                .get("starexec.org/attempt-no"));
            assertEquals(Integer.valueOf(7),
                ((Map<Integer, Integer>) getField(backend, "execIdToAttemptNo")).get(execId));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void aNullAttemptWritesNoLabelAndTracksNothing() throws Exception {
        // pairId 0 is the only way the attempt is not read; it must not be labelled 0 either.
        Submission submission = new Submission();
        KubernetesNativeBackend backend = submittingBackend(submission, null, null);
        Path dir = java.nio.file.Files.createTempDirectory("k8s-submit");

        try (MockedStatic<JobPairs> jp = Mockito.mockStatic(JobPairs.class)) {
            int execId = backend.submitScript(0, "/script.sh", "/work", logIn(dir));

            assertFalse(submission.built.get(0).getMetadata().getLabels()
                .containsKey("starexec.org/attempt-no"));
            assertFalse(((Map<Integer, Integer>) getField(backend, "execIdToAttemptNo"))
                .containsKey(execId));
            jp.verify(() -> JobPairs.getCurrentAttemptNo(Mockito.anyInt()), Mockito.never());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void anAmbiguousSubmissionKeepsTheAttempt() throws Exception {
        Submission submission = new Submission();
        // create() fails, and the Job is found: the outcome is resolved as "it exists".
        Job existing = new JobBuilder().withNewMetadata().withName("whatever").endMetadata().build();
        KubernetesNativeBackend backend =
            submittingBackend(submission, new RuntimeException("timeout after create"), existing);
        Path dir = java.nio.file.Files.createTempDirectory("k8s-submit");

        try (MockedStatic<JobPairs> jp = Mockito.mockStatic(JobPairs.class)) {
            jp.when(() -> JobPairs.getCurrentAttemptNo(804)).thenReturn(9);

            int execId = backend.submitScript(804, "/script.sh", "/work", logIn(dir));

            assertTrue(execId > 0, "treated as established");
            assertTrue(((Map<Integer, ?>) getField(backend, "ambiguousSubmissions")).containsKey(execId),
                "this is the ambiguous path");
            assertEquals(Integer.valueOf(9),
                ((Map<Integer, Integer>) getField(backend, "execIdToAttemptNo")).get(execId));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void aRecycledExecIdDoesNotKeepTheEarlierAttempt() throws Exception {
        Submission submission = new Submission();
        KubernetesNativeBackend backend = submittingBackend(submission, null, null);
        Map<Integer, Integer> attempts =
            (Map<Integer, Integer>) getField(backend, "execIdToAttemptNo");
        // Ids 5 and 6 are the next two handed out, each still carrying a dead attempt.
        attempts.put(5, 99);
        attempts.put(6, 99);
        Path dir = java.nio.file.Files.createTempDirectory("k8s-submit");

        try (MockedStatic<JobPairs> jp = Mockito.mockStatic(JobPairs.class)) {
            jp.when(() -> JobPairs.getCurrentAttemptNo(805)).thenReturn(2);

            int first = backend.submitScript(805, "/script.sh", "/work", logIn(dir));
            assertEquals(5, first);
            assertEquals(Integer.valueOf(2),
                attempts.get(5), "the new attempt overwrites the stale one");

            // Id 6 is reused by an execution with no attempt to fence on: the stale entry must
            // not survive to fence it against attempt 99.
            int second = backend.submitScript(0, "/script.sh", "/work", logIn(dir));
            assertEquals(6, second);
            assertFalse(attempts.containsKey(6),
                "no attempt means no entry, not the previous one");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void aLostPairAndAttemptAreRefilledFromTheJobLabels() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", "starexec");
        Job labelled = new JobBuilder().withNewMetadata()
            .withName("job-71")
            .addToLabels("starexec.org/pair-id", "9071")
            .addToLabels("starexec.org/attempt-no", "5")
            .endMetadata().build();
        givenJobDeletion(backend, "job-71", false, labelled);
        // The name alone is what establishes ownership; the pair and attempt entries are gone,
        // as after a partial loss of tracking.
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(71, "job-71");
        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        Method resolve = callback.getClass()
            .getDeclaredMethod("resolvePairId", ExecutionRef.class);
        resolve.setAccessible(true);
        assertEquals(Integer.valueOf(9071), resolve.invoke(callback, execution(71, "job-71")));

        assertEquals(Integer.valueOf(9071),
            ((Map<Integer, Integer>) getField(backend, "execIdToPairId")).get(71));
        assertEquals(Integer.valueOf(5),
            ((Map<Integer, Integer>) getField(backend, "execIdToAttemptNo")).get(71),
            "the attempt is refilled from the same Job as the pair");
    }
}
