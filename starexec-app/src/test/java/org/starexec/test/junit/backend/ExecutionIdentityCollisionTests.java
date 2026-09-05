package org.starexec.test.junit.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.BatchAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.ScalableResource;
import io.fabric8.kubernetes.client.dsl.V1BatchAPIGroupDSL;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.backend.ExecutionRef;
import org.starexec.backend.KubernetesJobMonitor;
import org.starexec.backend.KubernetesNativeBackend;
import org.starexec.backend.PodPhaseView;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.JobPairs.PairStatusLookupState;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.to.Status.StatusCode;

/**
 * The execution-identity collision observed in production on 2026-09-05.
 *
 * <p>{@code nextExecId} restarts at 1 in every application lifetime while
 * {@code job_pairs.sge_id} values from earlier lifetimes survive, so one integer names two
 * different Kubernetes Jobs. Execution A is historical; execution B is dispatched after the
 * counter has come back round to the same value. Every assertion here is about B not being
 * answered for by A.
 *
 * <p>Recorded timeline, all times 2026-09-05:
 * <pre>
 *   04:17     application restarts (Helm rev 22 -&gt; 23); nextExecId resets to 1
 *   05:20:39  the restart safety gate marks historical execId 2 stopped
 *   05:55:48  execId 2 is allocated again and submitted as starexec-job-2-48593
 *   05:55:51  "Skipping running callback for killed execId 2"
 *   05:56:01  "Skipping completion callback for killed execId 2"
 * </pre>
 *
 * <p>The second execution ran to completion and produced complete artifacts. Its result was
 * never ingested.
 */
public class ExecutionIdentityCollisionTests {

    private static final String NAMESPACE = "starexec";
    private static final String MANAGED_LABEL = "starexec.org/managed";
    private static final String EXEC_ID_LABEL = "starexec.org/exec-id";
    private static final String PAIR_ID_LABEL = "starexec.org/pair-id";

    /** The one integer both executions were given. */
    private static final int EXEC_ID = 2;

    private static final String JOB_A = "starexec-job-2-11111";
    private static final String UID_A = "aaaaaaaa-0000-0000-0000-00000000000a";

    private static final String JOB_B = "starexec-job-2-48593";
    private static final String UID_B = "bbbbbbbb-0000-0000-0000-00000000000b";

    private static final int PAIR_B = 42;

    // =====================================================================
    // 1. The backend's cancellation gate
    // =====================================================================

    /**
     * A cancellation recorded for the historical execution must not consume the current
     * one's callbacks.
     *
     * <p>This is the production failure. The safety gate wrote a marker for legacy execId 2
     * at 05:20:39; the callbacks for the Job created at 05:55:48 were then discarded by that
     * marker, so the pair's terminal status was never written.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void historicalCancellationDoesNotSuppressTheCurrentExecution() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", NAMESPACE);
        givenSafeCluster(backend);

        // Execution A: historical, no local tracking, marked stopped by the safety gate.
        ((Set<Integer>) getField(backend, "legacyKilledExecIds")).add(EXEC_ID);

        // Execution B: current, dispatched after the counter came back round.
        trackExecutionB(backend);

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
            jobPairs
                .when(() -> JobPairs.trySetPairRunning(PAIR_B))
                .thenReturn(JobPairs.ConditionalPairUpdateResult.UPDATED);
            jobPairs
                .when(() -> JobPairs.getPairStatusLookup(PAIR_B))
                .thenReturn(foundLookup(StatusCode.STATUS_RUNNING.getVal()));
            jobPairs
                .when(() ->
                    JobPairs.setPairStatusPreciseResult(
                        Mockito.anyInt(),
                        Mockito.anyInt(),
                        Mockito.anyInt(),
                        Mockito.anyInt(),
                        Mockito.anyBoolean()
                    )
                )
                .thenReturn(PairStatusResult.APPLIED);
            jobPairs.when(() -> JobPairs.setEndTime(PAIR_B)).thenReturn(true);

            callback.onJobRunning(executionB());
            callback.onJobComplete(executionB());

            // The running transition must reach the database for B's pair.
            jobPairs.verify(() -> JobPairs.trySetPairRunning(PAIR_B));

            // And so must the terminal one. Asserting the write is attempted, not that the
            // whole callback returned true: the steps after it read artifacts off disk,
            // which this test deliberately does not stage.
            jobPairs.verify(() ->
                JobPairs.setPairStatusPreciseResult(
                    Mockito.eq(PAIR_B),
                    Mockito.anyInt(),
                    Mockito.anyInt(),
                    Mockito.anyInt(),
                    Mockito.anyBoolean()
                )
            );
        }
    }

    // =====================================================================
    // 2. The monitor's suppression state
    // =====================================================================

    /**
     * A terminal callback already emitted for one Job must not stop the monitor delivering
     * the next Job's.
     *
     * <p>{@code completedExecIds} is consulted before anything else in the poll, so an
     * integer left in it by execution A discards execution B's event before the backend can
     * see which Job it came from.
     */
    @Test
    public void monitorDeliversTheSecondJobsCompletion() throws Exception {
        Harness harness = new Harness();
        Mockito
            .when(harness.callback.onJobComplete(Mockito.any(ExecutionRef.class)))
            .thenReturn(true);

        harness.givenJobs(completedJob(JOB_A, UID_A));
        harness.givenPods();
        harness.poll();

        harness.givenJobs(completedJob(JOB_B, UID_B));
        harness.givenPods();
        harness.poll();

        Mockito.verify(harness.callback).onJobComplete(executionA());
        Mockito.verify(harness.callback).onJobComplete(executionB());
    }

    /**
     * A running transition already made for one Job must not stop the next Job's.
     *
     * <p>Same shape as above through {@code runningExecIds}: the pair behind B stays
     * ENQUEUED, which also hides it from {@code GetPairsEnqueuedLongerThan}.
     */
    @Test
    public void monitorDeliversTheSecondJobsRunningTransition() throws Exception {
        Harness harness = new Harness();
        Mockito
            .when(harness.callback.onJobRunning(Mockito.any(ExecutionRef.class)))
            .thenReturn(true);

        harness.givenJobs(activeJob(JOB_A, UID_A));
        harness.givenPods(podFor(UID_A, "Running"));
        harness.poll();

        harness.givenJobs(activeJob(JOB_B, UID_B));
        harness.givenPods(podFor(UID_B, "Running"));
        harness.poll();

        Mockito.verify(harness.callback).onJobRunning(executionA());
        Mockito.verify(harness.callback).onJobRunning(executionB());
    }

    // =====================================================================
    // 3. Pod observation grouping
    // =====================================================================

    /**
     * Two Jobs sharing an exec-id label must not share one pod observation.
     *
     * <p>{@code PodPhaseView.of} groups by the label and merges colliding entries with
     * {@code preferred}, which lets A's Running pod answer for B's Pending one — so B is
     * reported as executing when nothing of B's has been scheduled.
     */
    @Test
    public void podObservationsAreNotMergedAcrossJobs() {
        PodPhaseView view = PodPhaseView.of(
            List.of(podFor(UID_A, "Running"), podFor(UID_B, "Pending")),
            EXEC_ID_LABEL
        );

        assertTrue(view.isAvailable());
        assertEquals(
            "B's own pod has not been scheduled; A's Running pod must not answer for it",
            PodPhaseView.Phase.PENDING,
            view.phaseFor(executionB())
        );
        assertEquals(PodPhaseView.Phase.RUNNING, view.phaseFor(executionA()));
        assertEquals(
            "a bare id names two Jobs here, so it has no answer",
            PodPhaseView.Phase.UNKNOWN,
            view.phaseFor(EXEC_ID)
        );
    }

    /**
     * The negative half of the same rule: two pods under one Job are one execution.
     *
     * <p>A Job may replace its pod without a new logical attempt, so these must still merge.
     */
    @Test
    public void replacementPodsUnderOneJobStayOneObservation() {
        PodPhaseView view = PodPhaseView.of(
            List.of(
                podNamed("pod-old", UID_A, "Failed"),
                podNamed("pod-new", UID_A, "Running")
            ),
            EXEC_ID_LABEL
        );

        assertTrue(view.isAvailable());
        assertEquals(PodPhaseView.Phase.RUNNING, view.phaseFor(executionA()));
        assertEquals(
            "one Job, so the bare id still answers",
            PodPhaseView.Phase.RUNNING,
            view.phaseFor(EXEC_ID)
        );
    }

    /**
     * A concrete cancellation still stops that execution's own late callback.
     *
     * <p>The protection being scoped must not mean it is gone: A's completion arriving after
     * A was killed is exactly what {@code killedExecutions} is for. Nothing may be written
     * for it, and nothing of B's may be touched.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void aCancelledExecutionsLateCompletionIsStillDiscarded() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", NAMESPACE);
        givenSafeCluster(backend);

        ((Set<ExecutionRef>) getField(backend, "killedExecutions")).add(executionA());
        trackExecutionB(backend);

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
            assertTrue(
                "a stale callback is handled, not retried forever",
                callback.onJobComplete(executionA())
            );
            jobPairs.verifyNoInteractions();
        }

        // B keeps its tracking, its pair and its submission slot.
        Map<Integer, String> names = (Map<Integer, String>) getField(backend, "execIdToJobName");
        Map<Integer, Integer> pairs = (Map<Integer, Integer>) getField(backend, "execIdToPairId");
        assertEquals(JOB_B, names.get(EXEC_ID));
        assertEquals(Integer.valueOf(PAIR_B), pairs.get(EXEC_ID));
    }

    /**
     * A late callback from a superseded Job must not write to the pair that now holds the id.
     *
     * <p>Without a cancellation marker the identity check is all there is: {@code
     * execIdToPairId} describes whichever execution occupies the number now, so reading it
     * for A would record A's outcome against B's pair.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void aSupersededExecutionCannotWriteToTheCurrentPair() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", NAMESPACE);
        givenSafeCluster(backend);
        trackExecutionB(backend);

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
            assertTrue(
                "a superseded execution is finished with, not retried forever",
                callback.onJobComplete(executionA())
            );

            // Nothing at all: not B's pair, and not A's own either. The status, stats and
            // attributes would be read from execIdToOutputDir, which now points at B's
            // directory, so publishing anything here records B's run somewhere.
            jobPairs.verifyNoInteractions();
        }

        // And B still holds its submission slot.
        Map<Integer, String> names = (Map<Integer, String>) getField(backend, "execIdToJobName");
        assertEquals(JOB_B, names.get(EXEC_ID));
    }

    /** The same refusal on the other two terminal paths. */
    @Test
    @SuppressWarnings("unchecked")
    public void aSupersededExecutionPublishesNothingOnAnyTerminalPath() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", NAMESPACE);
        givenSafeCluster(backend);
        trackExecutionB(backend);

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
            assertTrue(callback.onJobFailed(executionA(), "BackoffLimitExceeded"));
            assertTrue(callback.onJobStuckPending(executionA(), "Unschedulable"));
            jobPairs.verifyNoInteractions();
        }

        Map<Integer, String> names = (Map<Integer, String>) getField(backend, "execIdToJobName");
        assertEquals("B still holds its tracking", JOB_B, names.get(EXEC_ID));
    }

    /** A terminal callback delivered twice for one Job is handled once by the monitor. */
    @Test
    public void aRepeatedCompletionForOneJobIsDeliveredOnce() throws Exception {
        Harness harness = new Harness();
        Mockito
            .when(harness.callback.onJobComplete(Mockito.any(ExecutionRef.class)))
            .thenReturn(true);

        harness.givenJobs(completedJob(JOB_B, UID_B));
        harness.givenPods();
        harness.poll();
        harness.poll();
        harness.poll();

        Mockito.verify(harness.callback, Mockito.times(1)).onJobComplete(executionB());
    }

    /**
     * A stuck-pending transition left unfinished for one Job must not be run for another.
     *
     * <p>{@code cleanupPending} is the state that drives a pair to a terminal, rerun-eligible
     * status. Keyed on the integer, an unfinished transition for A would have escalated B —
     * a Job whose pod is running perfectly well.
     */
    @Test
    public void anUnfinishedStuckPendingTransitionDoesNotEscalateAnotherJob() throws Exception {
        Harness harness = new Harness();
        // The transition never completes, so the record survives every poll.
        Mockito
            .when(
                harness.callback.onJobStuckPending(
                    Mockito.any(ExecutionRef.class),
                    Mockito.anyString()
                )
            )
            .thenReturn(false);
        Mockito
            .when(harness.callback.onJobRunning(Mockito.any(ExecutionRef.class)))
            .thenReturn(true);

        harness.withClockAt(hoursAfterPodCreation(2));
        harness.givenJobs(activeJob(JOB_A, UID_A));
        harness.givenPods(podFor(UID_A, "Pending"));
        harness.poll();

        Mockito
            .verify(harness.callback)
            .onJobStuckPending(Mockito.eq(executionA()), Mockito.anyString());

        // B arrives with a pod that is running.
        harness.givenJobs(activeJob(JOB_B, UID_B));
        harness.givenPods(podFor(UID_B, "Running"));
        harness.poll();

        Mockito.verify(harness.callback).onJobRunning(executionB());
        Mockito
            .verify(harness.callback, Mockito.never())
            .onJobStuckPending(Mockito.eq(executionB()), Mockito.anyString());
    }

    /**
     * A pod that names no owning Job answers for no execution.
     *
     * <p>It still carries the exec-id label, which is the identity this whole change exists
     * to stop trusting. Letting it answer would report one execution's pod as another's in
     * exactly the case that matters — two Jobs holding one id.
     */
    @Test
    public void anOwnerlessPodAnswersForNoExecution() {
        Pod ownerless = podNamed("pod-ownerless", UID_A, "Running");
        ownerless.getMetadata().setOwnerReferences(java.util.List.of());

        PodPhaseView view = PodPhaseView.of(List.of(ownerless), EXEC_ID_LABEL);

        assertTrue(view.isAvailable());
        assertEquals(PodPhaseView.Phase.UNKNOWN, view.phaseFor(executionA()));
        assertEquals(PodPhaseView.Phase.UNKNOWN, view.phaseFor(executionB()));
        assertEquals(
            PodPhaseView.UNKNOWN_TIME,
            view.createdAtMillis(executionA())
        );
    }

    /**
     * An execution StarExec is not tracking still gets its result applied.
     *
     * <p>The supersession guard refuses when the id has been handed to <em>another</em>
     * execution. Absent tracking is not that: the shutdown drain and startup reconciliation
     * both reach terminal Jobs with nothing tracked, and refusing those would lose results
     * this backend exists to collect.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void anUntrackedExecutionIsStillPublished() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "namespace", NAMESPACE);
        givenSafeCluster(backend);
        // Deliberately no tracking at all for EXEC_ID.

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
            jobPairs
                .when(() -> JobPairs.getPairStatusLookup(PAIR_B))
                .thenReturn(foundLookup(StatusCode.STATUS_RUNNING.getVal()));
            jobPairs
                .when(() ->
                    JobPairs.setPairStatusPreciseResult(
                        Mockito.anyInt(),
                        Mockito.anyInt(),
                        Mockito.anyInt(),
                        Mockito.anyInt(),
                        Mockito.anyBoolean()
                    )
                )
                .thenReturn(PairStatusResult.APPLIED);
            jobPairs.when(() -> JobPairs.setEndTime(PAIR_B)).thenReturn(true);

            callback.onJobComplete(executionB());

            // The pair came from B's own Job label, read from the cluster.
            jobPairs.verify(() ->
                JobPairs.setPairStatusPreciseResult(
                    Mockito.eq(PAIR_B),
                    Mockito.anyInt(),
                    Mockito.anyInt(),
                    Mockito.anyInt(),
                    Mockito.anyBoolean()
                )
            );
        }
    }

    /**
     * An execution that does not own the tracking must not read the artifacts under it.
     *
     * <p>The pair lookup is gated on owning the id; the artifact lookup used to be gated
     * only on nobody <em>else</em> owning it, and those differ exactly where it matters.
     * Absent tracking satisfies the weaker test, and so does the window between a
     * concurrent submission publishing its output directory and publishing the identity
     * that would reveal it — so one execution's status.json could be read as another's
     * result.
     *
     * <p>Here execution A owns nothing, while a directory holding a status.json is
     * registered under the shared execution id. A's completion must record the caller's
     * default, not the status sitting in that directory.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void anExecutionThatOwnsNoTrackingReadsNoArtifacts() throws Exception {
        java.nio.file.Path foreignDir = java.nio.file.Files.createTempDirectory("exec-identity-");
        try {
            java.nio.file.Files.writeString(
                foreignDir.resolve("status.json"),
                "{\"status\": " + StatusCode.EXCEED_CPU.getVal() + ", \"stageNumber\": 7}"
            );

            KubernetesNativeBackend backend = new KubernetesNativeBackend();
            setField(backend, "namespace", NAMESPACE);
            givenSafeCluster(backend);

            // The directory is registered under the shared id, but A owns no tracking:
            // there is no job-name entry, so nothing says the id is A's.
            ((Map<Integer, java.nio.file.Path>) getField(backend, "execIdToOutputDir"))
                .put(EXEC_ID, foreignDir);

            KubernetesJobMonitor.JobCompletionCallback callback =
                instantiateCompletionCallback(backend);

            try (MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
                jobPairs
                    .when(() -> JobPairs.getPairStatusLookup(PAIR_B))
                    .thenReturn(foundLookup(StatusCode.STATUS_RUNNING.getVal()));
                jobPairs
                    .when(() ->
                        JobPairs.setPairStatusPreciseResult(
                            Mockito.anyInt(),
                            Mockito.anyInt(),
                            Mockito.anyInt(),
                            Mockito.anyInt(),
                            Mockito.anyBoolean()
                        )
                    )
                    .thenReturn(PairStatusResult.APPLIED);
                jobPairs.when(() -> JobPairs.setEndTime(PAIR_B)).thenReturn(true);

                callback.onJobComplete(executionA());

                // The caller's default, and stage 1 — neither read from that directory.
                jobPairs.verify(() ->
                    JobPairs.setPairStatusPreciseResult(
                        Mockito.eq(PAIR_B),
                        Mockito.eq(1),
                        Mockito.eq(StatusCode.STATUS_COMPLETE.getVal()),
                        Mockito.anyInt(),
                        Mockito.anyBoolean()
                    )
                );
                jobPairs.verify(() ->
                    JobPairs.setPairStatusPreciseResult(
                        Mockito.anyInt(),
                        Mockito.anyInt(),
                        Mockito.eq(StatusCode.EXCEED_CPU.getVal()),
                        Mockito.anyInt(),
                        Mockito.anyBoolean()
                    ),
                    Mockito.never()
                );
            }
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> walk =
                     java.nio.file.Files.walk(foreignDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> path.toFile().delete());
            }
        }
    }

    // =====================================================================
    // Fixtures
    // =====================================================================

    private static ExecutionRef executionA() {
        return new ExecutionRef(EXEC_ID, JOB_A, UID_A);
    }

    private static ExecutionRef executionB() {
        return new ExecutionRef(EXEC_ID, JOB_B, UID_B);
    }

    /** Tracking exactly as a successful submission of B would have left it. */
    @SuppressWarnings("unchecked")
    private void trackExecutionB(KubernetesNativeBackend backend) throws Exception {
        ((Map<Integer, String>) getField(backend, "execIdToJobName")).put(EXEC_ID, JOB_B);
        ((Map<Integer, String>) getField(backend, "execIdToJobUid")).put(EXEC_ID, UID_B);
        ((Map<Integer, Integer>) getField(backend, "execIdToPairId")).put(EXEC_ID, PAIR_B);
    }

    /** Two hours past the fixture pods' creationTimestamp, well past the stuck threshold. */
    private static long hoursAfterPodCreation(int hours) {
        return java.time.Instant.parse("2026-09-05T05:55:48Z").toEpochMilli()
            + java.util.concurrent.TimeUnit.HOURS.toMillis(hours);
    }

    /** A Kubernetes Job with the identity labels and a UID, carrying no terminal condition. */
    private static Job activeJob(String name, String uid) {
        Job job = new Job();
        job.setMetadata(
            new ObjectMetaBuilder()
                .withName(name)
                .withUid(uid)
                .withLabels(identityLabels())
                .build()
        );
        JobStatus status = new JobStatus();
        status.setActive(1);
        job.setStatus(status);
        return job;
    }

    private static Job completedJob(String name, String uid) {
        Job job = activeJob(name, uid);
        JobCondition complete = new JobCondition();
        complete.setType("Complete");
        complete.setStatus("True");
        job.getStatus().setConditions(List.of(complete));
        job.getStatus().setActive(0);
        return job;
    }

    private static Map<String, String> identityLabels() {
        Map<String, String> labels = new HashMap<>();
        labels.put(MANAGED_LABEL, "true");
        labels.put(EXEC_ID_LABEL, String.valueOf(EXEC_ID));
        labels.put(PAIR_ID_LABEL, String.valueOf(PAIR_B));
        return labels;
    }

    /** A pod owned by the Job with {@code ownerUid}, exactly as the Job controller creates it. */
    private static Pod podFor(String ownerUid, String phase) {
        return podNamed("pod-" + ownerUid, ownerUid, phase);
    }

    private static Pod podNamed(String name, String ownerUid, String phase) {
        Pod pod = new Pod();
        pod.setMetadata(
            new ObjectMetaBuilder()
                .withName(name)
                .withCreationTimestamp("2026-09-05T05:55:48Z")
                .withLabels(identityLabels())
                .withOwnerReferences(
                    new OwnerReferenceBuilder()
                        .withApiVersion("batch/v1")
                        .withKind("Job")
                        .withName("owner-of-" + name)
                        .withUid(ownerUid)
                        .withController(true)
                        .build()
                )
                .build()
        );
        PodStatus status = new PodStatus();
        status.setPhase(phase);
        pod.setStatus(status);
        return pod;
    }

    /** The monitor with its two listings stubbed, so a poll can be driven a step at a time. */
    private static final class Harness {

        private final KubernetesJobMonitor monitor;
        private final KubernetesJobMonitor.JobCompletionCallback callback;
        private final FilterWatchListDeletable<Job, JobList, ScalableResource<Job>> filteredJobs;
        private final FilterWatchListDeletable<Pod, PodList, PodResource> filteredPods;

        @SuppressWarnings("unchecked")
        private Harness() {
            KubernetesClient client = Mockito.mock(KubernetesClient.class);
            BatchAPIGroupDSL batch = Mockito.mock(BatchAPIGroupDSL.class);
            V1BatchAPIGroupDSL v1 = Mockito.mock(V1BatchAPIGroupDSL.class);
            MixedOperation<Job, JobList, ScalableResource<Job>> jobs =
                Mockito.mock(MixedOperation.class);
            NonNamespaceOperation<Job, JobList, ScalableResource<Job>> namespacedJobs =
                Mockito.mock(NonNamespaceOperation.class);
            MixedOperation<Pod, PodList, PodResource> pods = Mockito.mock(MixedOperation.class);
            NonNamespaceOperation<Pod, PodList, PodResource> namespacedPods =
                Mockito.mock(NonNamespaceOperation.class);

            filteredJobs = Mockito.mock(FilterWatchListDeletable.class);
            filteredPods = Mockito.mock(FilterWatchListDeletable.class);
            callback = Mockito.mock(KubernetesJobMonitor.JobCompletionCallback.class);

            Mockito.when(client.batch()).thenReturn(batch);
            Mockito.when(batch.v1()).thenReturn(v1);
            Mockito.when(v1.jobs()).thenReturn(jobs);
            Mockito.when(jobs.inNamespace(NAMESPACE)).thenReturn(namespacedJobs);
            Mockito.when(namespacedJobs.withLabel(MANAGED_LABEL, "true")).thenReturn(filteredJobs);

            Mockito.when(client.pods()).thenReturn(pods);
            Mockito.when(pods.inNamespace(NAMESPACE)).thenReturn(namespacedPods);
            Mockito.when(namespacedPods.withLabel(MANAGED_LABEL, "true")).thenReturn(filteredPods);

            monitor = new KubernetesJobMonitor(client, NAMESPACE, callback);
        }

        private void givenJobs(Job... items) {
            JobList list = new JobList();
            list.setItems(List.of(items));
            Mockito.when(filteredJobs.list()).thenReturn(list);
        }

        private void givenPods(Pod... items) {
            PodList list = new PodList();
            list.setItems(List.of(items));
            Mockito.when(filteredPods.list()).thenReturn(list);
        }

        /** Replaces the monitor's clock, so no test sleeps. */
        private void withClockAt(long millis) throws Exception {
            Field clock = KubernetesJobMonitor.class.getDeclaredField("clock");
            clock.setAccessible(true);
            clock.set(monitor, (java.util.function.LongSupplier) () -> millis);
        }

        private void poll() throws Exception {
            Method pollJobsOnce =
                KubernetesJobMonitor.class.getDeclaredMethod("pollJobsOnce");
            pollJobsOnce.setAccessible(true);
            pollJobsOnce.invoke(monitor);
        }
    }

    /** A cluster in which nothing survives, so a terminal callback may release accounting. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void givenSafeCluster(KubernetesNativeBackend backend) throws Exception {
        KubernetesClient client = Mockito.mock(KubernetesClient.class);
        BatchAPIGroupDSL batch = Mockito.mock(BatchAPIGroupDSL.class);
        V1BatchAPIGroupDSL v1 = Mockito.mock(V1BatchAPIGroupDSL.class);
        MixedOperation jobs = Mockito.mock(MixedOperation.class);
        NonNamespaceOperation<Job, JobList, ScalableResource<Job>> namespacedJobs =
            Mockito.mock(NonNamespaceOperation.class);
        FilterWatchListDeletable filteredJobs = Mockito.mock(FilterWatchListDeletable.class);
        MixedOperation pods = Mockito.mock(MixedOperation.class);
        NonNamespaceOperation namespacedPods = Mockito.mock(NonNamespaceOperation.class);
        FilterWatchListDeletable filteredPods = Mockito.mock(FilterWatchListDeletable.class);

        Mockito.when(client.batch()).thenReturn(batch);
        Mockito.when(batch.v1()).thenReturn(v1);
        Mockito.when(v1.jobs()).thenReturn(jobs);
        Mockito.when(jobs.inNamespace(Mockito.any())).thenReturn(namespacedJobs);
        Mockito.when(namespacedJobs.withLabels(Mockito.anyMap())).thenReturn(filteredJobs);
        Mockito.when(filteredJobs.list()).thenReturn(new JobList());

        // Named lookups answer with the real objects, so a callback with no local tracking
        // can still resolve its pair from its own Job's label.
        ScalableResource<Job> jobA = Mockito.mock(ScalableResource.class);
        ScalableResource<Job> jobB = Mockito.mock(ScalableResource.class);
        Mockito.when(jobA.get()).thenReturn(completedJob(JOB_A, UID_A));
        Mockito.when(jobB.get()).thenReturn(completedJob(JOB_B, UID_B));
        Mockito.when(namespacedJobs.withName(JOB_A)).thenReturn(jobA);
        Mockito.when(namespacedJobs.withName(JOB_B)).thenReturn(jobB);

        Mockito.when(client.pods()).thenReturn(pods);
        Mockito.when(pods.inNamespace(Mockito.any())).thenReturn(namespacedPods);
        Mockito.when(namespacedPods.withLabels(Mockito.anyMap())).thenReturn(filteredPods);
        Mockito.when(filteredPods.list()).thenReturn(new PodList());

        setField(backend, "kubernetesClient", client);
    }

    private KubernetesJobMonitor.JobCompletionCallback instantiateCompletionCallback(
        KubernetesNativeBackend backend
    ) throws Exception {
        Class<?> callbackClass = Class.forName(
            "org.starexec.backend.KubernetesNativeBackend$KubernetesJobCompletionCallback"
        );
        Constructor<?> constructor =
            callbackClass.getDeclaredConstructor(KubernetesNativeBackend.class);
        constructor.setAccessible(true);
        return (KubernetesJobMonitor.JobCompletionCallback) constructor.newInstance(backend);
    }

    private JobPairs.PairStatusLookupResult foundLookup(int statusCode) throws Exception {
        Constructor<JobPairs.PairStatusLookupResult> constructor =
            JobPairs.PairStatusLookupResult.class.getDeclaredConstructor(
                PairStatusLookupState.class,
                int.class
            );
        constructor.setAccessible(true);
        return constructor.newInstance(PairStatusLookupState.FOUND, statusCode);
    }

    private Object getField(Object target, String name) throws Exception {
        Field field = KubernetesNativeBackend.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = KubernetesNativeBackend.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
