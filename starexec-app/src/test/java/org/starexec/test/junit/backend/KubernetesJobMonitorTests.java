package org.starexec.test.junit.backend;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobConditionBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.BatchAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
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

    private KubernetesClient kubernetesClient;
    private BatchAPIGroupDSL batchApiGroup;
    private V1BatchAPIGroupDSL v1BatchApiGroup;
    private MixedOperation<Job, JobList, ScalableResource<Job>> jobsOperation;
    private NonNamespaceOperation<Job, JobList, ScalableResource<Job>> namespacedJobs;
    private FilterWatchListDeletable<Job, JobList, ScalableResource<Job>> filteredJobs;
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
        callback = mock(KubernetesJobMonitor.JobCompletionCallback.class);

        when(kubernetesClient.batch()).thenReturn(batchApiGroup);
        when(batchApiGroup.v1()).thenReturn(v1BatchApiGroup);
        when(v1BatchApiGroup.jobs()).thenReturn(jobsOperation);
        when(jobsOperation.inNamespace(NAMESPACE)).thenReturn(namespacedJobs);
        when(namespacedJobs.withLabel("starexec.org/managed", "true")).thenReturn(filteredJobs);

        monitor = new KubernetesJobMonitor(kubernetesClient, NAMESPACE, callback);
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
        Job activeJob = new JobBuilder()
            .withNewMetadata()
            .withName(JOB_NAME)
            .addToLabels("starexec.org/managed", "true")
            .addToLabels("starexec.org/exec-id", String.valueOf(EXEC_ID))
            .endMetadata()
            .withNewStatus()
            .withActive(1)
            .endStatus()
            .build();

        JobList activeJobs = new JobList();
        activeJobs.setItems(List.of(activeJob));
        when(filteredJobs.list()).thenReturn(activeJobs);
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
        Job activeJob = new JobBuilder()
            .withNewMetadata()
            .withName(JOB_NAME)
            .addToLabels("starexec.org/managed", "true")
            .addToLabels("starexec.org/exec-id", String.valueOf(EXEC_ID))
            .endMetadata()
            .withNewStatus()
            .withActive(1)
            .endStatus()
            .build();

        JobList activeJobs = new JobList();
        activeJobs.setItems(List.of(activeJob));
        when(filteredJobs.list()).thenReturn(activeJobs);
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
