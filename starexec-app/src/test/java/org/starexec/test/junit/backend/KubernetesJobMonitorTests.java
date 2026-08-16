package org.starexec.test.junit.backend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodConditionBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobConditionBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.BatchAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.ScalableResource;
import io.fabric8.kubernetes.client.dsl.V1BatchAPIGroupDSL;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.starexec.backend.KubernetesJobMonitor;

public class KubernetesJobMonitorTests {

    private static final String NAMESPACE = "starexec";
    private static final int EXEC_ID = 101;
    private static final String JOB_NAME = "job-101";
    private static final String MANAGED_LABEL = "starexec.org/managed";
    private static final String EXEC_ID_LABEL = "starexec.org/exec-id";

    private KubernetesClient kubernetesClient;
    private BatchAPIGroupDSL batchApiGroup;
    private V1BatchAPIGroupDSL v1BatchApiGroup;
    private MixedOperation<Job, JobList, ScalableResource<Job>> jobsOperation;
    private NonNamespaceOperation<Job, JobList, ScalableResource<Job>> namespacedJobs;
    private FilterWatchListDeletable<Job, JobList, ScalableResource<Job>> filteredJobs;
    private MixedOperation<Pod, PodList, PodResource> podsOperation;
    private NonNamespaceOperation<Pod, PodList, PodResource> namespacedPods;
    private FilterWatchListDeletable<Pod, PodList, PodResource> filteredPods;
    private KubernetesJobMonitor.JobCompletionCallback callback;
    private KubernetesJobMonitor monitor;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() {
        kubernetesClient = mock(KubernetesClient.class);
        batchApiGroup = mock(BatchAPIGroupDSL.class);
        v1BatchApiGroup = mock(V1BatchAPIGroupDSL.class);
        jobsOperation = mock(MixedOperation.class);
        namespacedJobs = mock(NonNamespaceOperation.class);
        filteredJobs = mock(FilterWatchListDeletable.class);
        podsOperation = mock(MixedOperation.class);
        namespacedPods = mock(NonNamespaceOperation.class);
        filteredPods = mock(FilterWatchListDeletable.class);
        callback = mock(KubernetesJobMonitor.JobCompletionCallback.class);

        when(kubernetesClient.batch()).thenReturn(batchApiGroup);
        when(batchApiGroup.v1()).thenReturn(v1BatchApiGroup);
        when(v1BatchApiGroup.jobs()).thenReturn(jobsOperation);
        when(jobsOperation.inNamespace(NAMESPACE)).thenReturn(namespacedJobs);
        when(namespacedJobs.withLabel(MANAGED_LABEL, "true")).thenReturn(filteredJobs);

        when(kubernetesClient.pods()).thenReturn(podsOperation);
        when(podsOperation.inNamespace(NAMESPACE)).thenReturn(namespacedPods);
        when(namespacedPods.withLabel(MANAGED_LABEL, "true")).thenReturn(filteredPods);
        givenPods();

        monitor = new KubernetesJobMonitor(kubernetesClient, NAMESPACE, callback);
    }

    /** Stubs the managed-pod listing the monitor performs once per poll. */
    private void givenPods(Pod... pods) {
        PodList list = new PodList();
        list.setItems(List.of(pods));
        when(filteredPods.list()).thenReturn(list);
    }

    private Pod podFor(int execId, String phase) {
        return new PodBuilder()
            .withNewMetadata()
            .withName("pod-" + execId)
            .withCreationTimestamp("2026-08-15T12:00:00Z")
            .addToLabels(MANAGED_LABEL, "true")
            .addToLabels(EXEC_ID_LABEL, String.valueOf(execId))
            .endMetadata()
            .withNewStatus()
            .withPhase(phase)
            .withConditions(
                new PodConditionBuilder()
                    .withType("PodScheduled")
                    .withStatus("Running".equals(phase) ? "True" : "False")
                    .withReason("Running".equals(phase) ? null : "Unschedulable")
                    .withMessage(
                        "Running".equals(phase)
                            ? null
                            : "0/6 nodes are available: 6 node(s) didn't match Pod's node affinity/selector."
                    )
                    .build()
            )
            .endStatus()
            .build();
    }

    private Job activeJobFixture() {
        return new JobBuilder()
            .withNewMetadata()
            .withName(JOB_NAME)
            .addToLabels(MANAGED_LABEL, "true")
            .addToLabels(EXEC_ID_LABEL, String.valueOf(EXEC_ID))
            .endMetadata()
            .withNewStatus()
            .withActive(1)
            .endStatus()
            .build();
    }

    private void givenJobs(Job... jobs) {
        JobList list = new JobList();
        list.setItems(List.of(jobs));
        when(filteredJobs.list()).thenReturn(list);
    }

    /**
     * JobStatus.active is "the number of pending and running pods", so a pod the scheduler
     * has never placed satisfies active > 0. Reporting that pair as RUNNING also hides it
     * from GetPairsEnqueuedLongerThan, which only looks at STATUS_ENQUEUED.
     */
    @Test
    public void pendingPodIsNotReportedAsRunning() throws Exception {
        givenJobs(activeJobFixture());
        givenPods(podFor(EXEC_ID, "Pending"));

        invokePollJobsOnce();

        verify(callback, times(0)).onJobRunning(anyInt(), anyString());
        verify(callback, times(0)).onJobComplete(anyInt(), anyString());
        verify(callback, times(0)).onJobFailed(anyInt(), anyString(), anyString());
    }

    /** The correction must not become a dispatch stop: a real pod still reports running. */
    @Test
    public void runningPodIsStillReportedAsRunning() throws Exception {
        givenJobs(activeJobFixture());
        givenPods(podFor(EXEC_ID, "Running"));
        when(callback.onJobRunning(EXEC_ID, JOB_NAME)).thenReturn(true);

        invokePollJobsOnce();
        invokePollJobsOnce();

        verify(callback, times(1)).onJobRunning(EXEC_ID, JOB_NAME);
    }

    /**
     * The chart's Role granted batch/jobs alone before this feature existed, so an
     * installation whose RBAC has not been updated gets a 403 here. It must behave exactly
     * as it did before rather than stop dispatching.
     */
    @Test
    public void podListFailureFallsBackToJobLevelActiveCount() throws Exception {
        givenJobs(activeJobFixture());
        when(filteredPods.list()).thenThrow(new RuntimeException("pods is forbidden"));
        when(callback.onJobRunning(EXEC_ID, JOB_NAME)).thenReturn(true);

        invokePollJobsOnce();

        verify(callback, times(1)).onJobRunning(EXEC_ID, JOB_NAME);
    }

    @Test
    public void pollJobsOnce_RetriesCompletedJobUntilCallbackSucceeds()
        throws Exception {
        Job completedJob = new JobBuilder()
            .withNewMetadata()
            .withName(JOB_NAME)
            .addToLabels("starexec.org/managed", "true")
            .addToLabels("starexec.org/exec-id", String.valueOf(EXEC_ID))
            .endMetadata()
            .withNewStatus()
            .withSucceeded(1)
            .endStatus()
            .build();

        JobList completedJobs = new JobList();
        completedJobs.setItems(List.of(completedJob));
        when(filteredJobs.list()).thenReturn(completedJobs);
        when(callback.onJobComplete(EXEC_ID, JOB_NAME)).thenReturn(false, true);

        invokePollJobsOnce();
        invokePollJobsOnce();
        invokePollJobsOnce();

        verify(callback, times(2)).onJobComplete(EXEC_ID, JOB_NAME);
        assertTrue(getCompletedExecIds().contains(EXEC_ID));
    }

    @Test
    public void pollJobsOnce_MarksActiveJobRunningOnlyOnce() throws Exception {
        givenJobs(activeJobFixture());
        givenPods(podFor(EXEC_ID, "Running"));
        when(callback.onJobRunning(EXEC_ID, JOB_NAME)).thenReturn(true);

        invokePollJobsOnce();
        invokePollJobsOnce();
        invokePollJobsOnce();

        verify(callback, times(1)).onJobRunning(EXEC_ID, JOB_NAME);
        verify(callback, times(0)).onJobComplete(anyInt(), anyString());
        verify(callback, times(0)).onJobFailed(anyInt(), anyString(), anyString());
    }

    @Test
    public void pollJobsOnce_RetriesRunningJobUntilCallbackSucceeds() throws Exception {
        givenJobs(activeJobFixture());
        givenPods(podFor(EXEC_ID, "Running"));
        when(callback.onJobRunning(EXEC_ID, JOB_NAME)).thenReturn(false, true);

        invokePollJobsOnce();
        invokePollJobsOnce();
        invokePollJobsOnce();

        verify(callback, times(2)).onJobRunning(EXEC_ID, JOB_NAME);
    }

    @Test
    public void pollJobsOnce_IgnoresPendingJobWithoutActivePods() throws Exception {
        Job pendingJob = new JobBuilder()
            .withNewMetadata()
            .withName(JOB_NAME)
            .addToLabels("starexec.org/managed", "true")
            .addToLabels("starexec.org/exec-id", String.valueOf(EXEC_ID))
            .endMetadata()
            .build();

        JobList pendingJobs = new JobList();
        pendingJobs.setItems(List.of(pendingJob));
        when(filteredJobs.list()).thenReturn(pendingJobs);

        invokePollJobsOnce();

        verifyNoInteractions(callback);
    }

    @Test
    public void pollJobsOnce_RetriesFailedJobUntilCallbackSucceeds()
        throws Exception {
        Job failedJob = new JobBuilder()
            .withNewMetadata()
            .withName(JOB_NAME)
            .addToLabels("starexec.org/managed", "true")
            .addToLabels("starexec.org/exec-id", String.valueOf(EXEC_ID))
            .endMetadata()
            .withNewStatus()
            .withFailed(1)
            .withConditions(
                new JobConditionBuilder()
                    .withType("Failed")
                    .withStatus("True")
                    .withReason("BackoffLimitExceeded")
                    .withMessage("Pod exited with status 1")
                    .build()
            )
            .endStatus()
            .build();

        JobList failedJobs = new JobList();
        failedJobs.setItems(List.of(failedJob));
        when(filteredJobs.list()).thenReturn(failedJobs);
        when(
            callback.onJobFailed(
                EXEC_ID,
                JOB_NAME,
                "BackoffLimitExceeded: Pod exited with status 1"
            )
        )
            .thenReturn(false, true);

        invokePollJobsOnce();
        invokePollJobsOnce();
        invokePollJobsOnce();

        verify(callback, times(2))
            .onJobFailed(
                EXEC_ID,
                JOB_NAME,
                "BackoffLimitExceeded: Pod exited with status 1"
            );
        assertTrue(getCompletedExecIds().contains(EXEC_ID));
    }

    // =========================================================================
    // Stale-pending detection
    // =========================================================================

    /** Matches the creationTimestamp every fixture pod carries. */
    private static final long POD_CREATED_AT =
        java.time.Instant.parse("2026-08-15T12:00:00Z").toEpochMilli();

    private void setClockMinutesAfterPodCreation(long minutes) throws Exception {
        Field clockField = KubernetesJobMonitor.class.getDeclaredField("clock");
        clockField.setAccessible(true);
        long now = POD_CREATED_AT + minutes * 60_000L;
        clockField.set(monitor, (java.util.function.LongSupplier) () -> now);
    }

    /** Below the acting threshold the pair is reported, never touched. */
    @Test
    public void podPendingPastWarnThresholdIsLoggedNotFailed() throws Exception {
        givenJobs(activeJobFixture());
        givenPods(podFor(EXEC_ID, "Pending"));
        setClockMinutesAfterPodCreation(6);

        invokePollJobsOnce();

        verify(callback, times(0)).onJobStuckPending(anyInt(), anyString(), anyString());
        verify(callback, times(0)).onJobRunning(anyInt(), anyString());
        assertFalse(getCompletedExecIds().contains(EXEC_ID));
    }

    /** A pod still waiting well before the warning threshold is left entirely alone. */
    @Test
    public void freshlyPendingPodIsNotEvenWarnedAbout() throws Exception {
        givenJobs(activeJobFixture());
        givenPods(podFor(EXEC_ID, "Pending"));
        setClockMinutesAfterPodCreation(1);

        invokePollJobsOnce();

        verifyNoInteractions(callback);
    }

    @Test
    public void podPendingPastTimeoutIsFailedForRerun() throws Exception {
        givenJobs(activeJobFixture());
        givenPods(podFor(EXEC_ID, "Pending"));
        setClockMinutesAfterPodCreation(61);
        when(callback.onJobStuckPending(eq(EXEC_ID), eq(JOB_NAME), anyString()))
            .thenReturn(true);

        invokePollJobsOnce();

        verify(callback, times(1)).onJobStuckPending(eq(EXEC_ID), eq(JOB_NAME), anyString());
        assertTrue(getCompletedExecIds().contains(EXEC_ID));
    }

    /** The scheduler's own account must reach the callback, for the operator's log. */
    @Test
    public void theSchedulersReasonIsHandedToTheCallback() throws Exception {
        givenJobs(activeJobFixture());
        givenPods(podFor(EXEC_ID, "Pending"));
        setClockMinutesAfterPodCreation(61);
        when(callback.onJobStuckPending(eq(EXEC_ID), eq(JOB_NAME), anyString()))
            .thenReturn(true);

        invokePollJobsOnce();

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(callback).onJobStuckPending(eq(EXEC_ID), eq(JOB_NAME), reason.capture());
        assertTrue(reason.getValue().contains("Unschedulable"));
        assertTrue(reason.getValue().contains("node affinity/selector"));
    }

    @Test
    public void stuckPendingIsRetriedUntilTheCallbackSucceeds() throws Exception {
        givenJobs(activeJobFixture());
        givenPods(podFor(EXEC_ID, "Pending"));
        setClockMinutesAfterPodCreation(61);
        when(callback.onJobStuckPending(eq(EXEC_ID), eq(JOB_NAME), anyString()))
            .thenReturn(false, true);

        invokePollJobsOnce();
        invokePollJobsOnce();
        invokePollJobsOnce();

        verify(callback, times(2)).onJobStuckPending(eq(EXEC_ID), eq(JOB_NAME), anyString());
        assertTrue(getCompletedExecIds().contains(EXEC_ID));
    }

    /**
     * A pod that starts late must be treated as running, not as stuck. Reaching the
     * threshold is not a licence to fail something that is executing.
     */
    @Test
    public void aPodThatStartsLateIsRunning() throws Exception {
        givenJobs(activeJobFixture());
        givenPods(podFor(EXEC_ID, "Running"));
        setClockMinutesAfterPodCreation(120);
        when(callback.onJobRunning(EXEC_ID, JOB_NAME)).thenReturn(true);

        invokePollJobsOnce();

        verify(callback, times(1)).onJobRunning(EXEC_ID, JOB_NAME);
        verify(callback, times(0)).onJobStuckPending(anyInt(), anyString(), anyString());
    }

    /**
     * An unreadable creation timestamp means the pod's age is unknown, and unknown must
     * never be read as old.
     */
    @Test
    public void aPodWithNoUsableAgeIsNeverTimedOut() throws Exception {
        Pod undated = podFor(EXEC_ID, "Pending");
        undated.getMetadata().setCreationTimestamp("not-a-timestamp");
        givenJobs(activeJobFixture());
        givenPods(undated);
        setClockMinutesAfterPodCreation(600);

        invokePollJobsOnce();

        verify(callback, times(0)).onJobStuckPending(anyInt(), anyString(), anyString());
    }

    /** Setting the timeout to zero leaves the monitor reporting only. */
    @Test
    public void aZeroTimeoutDisablesTheTransition() throws Exception {
        monitor = new KubernetesJobMonitor(kubernetesClient, NAMESPACE, callback, 5, 0);
        givenJobs(activeJobFixture());
        givenPods(podFor(EXEC_ID, "Pending"));
        setClockMinutesAfterPodCreation(600);

        invokePollJobsOnce();

        verify(callback, times(0)).onJobStuckPending(anyInt(), anyString(), anyString());
    }

    /** Without a pod listing there is no age to measure, so nothing may be failed. */
    @Test
    public void podListFailureNeverProducesAStuckPendingTransition() throws Exception {
        givenJobs(activeJobFixture());
        when(filteredPods.list()).thenThrow(new RuntimeException("pods is forbidden"));
        setClockMinutesAfterPodCreation(600);
        when(callback.onJobRunning(EXEC_ID, JOB_NAME)).thenReturn(true);

        invokePollJobsOnce();

        verify(callback, times(0)).onJobStuckPending(anyInt(), anyString(), anyString());
    }

    /**
     * Once a pair has been judged stuck, a pod that starts late must not undo that
     * judgement. The pair's record may already be terminal and eligible for rerun, so
     * reclassifying it as running would leave the Job undeleted and let the old pod write
     * results alongside the rerun's.
     */
    @Test
    public void aPairAlreadyJudgedStuckIsNotReclassifiedAsRunning() throws Exception {
        givenJobs(activeJobFixture());
        givenPods(podFor(EXEC_ID, "Pending"));
        setClockMinutesAfterPodCreation(61);
        // The transition does not complete: the callback reports it is not finished.
        when(callback.onJobStuckPending(eq(EXEC_ID), eq(JOB_NAME), anyString()))
            .thenReturn(false);

        invokePollJobsOnce();

        // The pod now starts, which before this would have taken the running branch.
        givenPods(podFor(EXEC_ID, "Running"));
        invokePollJobsOnce();

        verify(callback, times(0)).onJobRunning(anyInt(), anyString());
        verify(callback, times(2)).onJobStuckPending(eq(EXEC_ID), eq(JOB_NAME), anyString());
    }

    /** And it keeps retrying until the transition actually finishes. */
    @Test
    public void stuckPendingCleanupIsDrivenToCompletion() throws Exception {
        givenJobs(activeJobFixture());
        givenPods(podFor(EXEC_ID, "Pending"));
        setClockMinutesAfterPodCreation(61);
        when(callback.onJobStuckPending(eq(EXEC_ID), eq(JOB_NAME), anyString()))
            .thenReturn(false, false, true);

        invokePollJobsOnce();
        invokePollJobsOnce();
        invokePollJobsOnce();
        invokePollJobsOnce();

        verify(callback, times(3)).onJobStuckPending(eq(EXEC_ID), eq(JOB_NAME), anyString());
        assertTrue(getCompletedExecIds().contains(EXEC_ID));
    }

    @SuppressWarnings("unchecked")
    private Set<Integer> getCompletedExecIds() throws Exception {
        Field completedExecIdsField = KubernetesJobMonitor.class.getDeclaredField(
            "completedExecIds"
        );
        completedExecIdsField.setAccessible(true);
        return (Set<Integer>) completedExecIdsField.get(monitor);
    }

    private void invokePollJobsOnce() throws Exception {
        Method pollJobsOnce = KubernetesJobMonitor.class.getDeclaredMethod(
            "pollJobsOnce"
        );
        pollJobsOnce.setAccessible(true);
        pollJobsOnce.invoke(monitor);
    }
}
