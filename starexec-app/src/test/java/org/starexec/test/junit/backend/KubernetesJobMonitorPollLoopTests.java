package org.starexec.test.junit.backend;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.BatchAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.ScalableResource;
import io.fabric8.kubernetes.client.dsl.V1BatchAPIGroupDSL;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.starexec.backend.KubernetesJobMonitor;

/**
 * The monitor's polling loop waits its interval after every poll, including one that throws.
 *
 * <p>The Kubernetes client retries only a 429, a 5xx or an I/O failure. A refusal such as RBAC's 403 or a
 * revoked token's 401 fails at once, and it keeps failing until someone fixes its cause. A loop that skipped
 * its wait after such a poll would poll again immediately, and every turn logs an ERROR, which StarLogger also
 * inserts into error_logs, so the table would grow for as long as the refusal lasts.
 */
public class KubernetesJobMonitorPollLoopTests {

    private static final String NAMESPACE = "starexec";
    private static final String MANAGED_LABEL = "starexec.org/managed";
    /** How long a test watches the loop: far shorter than its 5 s interval, far longer than one poll. */
    private static final long WATCH_MS = 1_000;
    /**
     * How long the first poll may take to happen: the loop polls as soon as the monitor starts. The wait
     * for it counts at least one call, not exactly one: with the defect, the count is already in the
     * thousands when it is checked, and only the assertion after the watch should report that.
     */
    private static final long FIRST_POLL_MS = 2_000;

    private FilterWatchListDeletable<Job, JobList, ScalableResource<Job>> filteredJobs;
    private KubernetesJobMonitor monitor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setUp() {
        KubernetesClient client = mock(KubernetesClient.class);
        BatchAPIGroupDSL batch = mock(BatchAPIGroupDSL.class);
        V1BatchAPIGroupDSL batchV1 = mock(V1BatchAPIGroupDSL.class);
        MixedOperation<Job, JobList, ScalableResource<Job>> jobs = mock(MixedOperation.class);
        NonNamespaceOperation<Job, JobList, ScalableResource<Job>> namespacedJobs =
            mock(NonNamespaceOperation.class);
        filteredJobs = mock(FilterWatchListDeletable.class);
        MixedOperation<Pod, PodList, PodResource> pods = mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> namespacedPods = mock(NonNamespaceOperation.class);
        FilterWatchListDeletable<Pod, PodList, PodResource> filteredPods = mock(FilterWatchListDeletable.class);

        when(client.batch()).thenReturn(batch);
        when(batch.v1()).thenReturn(batchV1);
        when(batchV1.jobs()).thenReturn(jobs);
        when(jobs.inNamespace(NAMESPACE)).thenReturn(namespacedJobs);
        when(namespacedJobs.withLabel(MANAGED_LABEL, "true")).thenReturn(filteredJobs);
        when(client.pods()).thenReturn(pods);
        when(pods.inNamespace(NAMESPACE)).thenReturn(namespacedPods);
        when(namespacedPods.withLabel(MANAGED_LABEL, "true")).thenReturn(filteredPods);
        PodList noPods = new PodList();
        noPods.setItems(List.of());
        when(filteredPods.list()).thenReturn(noPods);

        monitor = new KubernetesJobMonitor(
            client,
            NAMESPACE,
            mock(KubernetesJobMonitor.JobCompletionCallback.class)
        );
    }

    @AfterEach
    public void tearDown() {
        if (monitor.isRunning()) {
            monitor.stop();
        }
    }

    /** What the API server answers a service account that RBAC does not allow to list Jobs. */
    private static KubernetesClientException forbidden() {
        return new KubernetesClientException("jobs.batch is forbidden", 403, null);
    }

    @Test
    public void aPollThatFailsWaitsTheIntervalBeforeTheNext() throws Exception {
        when(filteredJobs.list()).thenThrow(forbidden());

        monitor.start();
        verify(filteredJobs, timeout(FIRST_POLL_MS).atLeastOnce()).list();
        Thread.sleep(WATCH_MS);

        verify(filteredJobs, times(1)).list();
    }

    @Test
    public void aPollThatSucceedsWaitsTheIntervalBeforeTheNext() throws Exception {
        JobList none = new JobList();
        none.setItems(List.of());
        when(filteredJobs.list()).thenReturn(none);

        monitor.start();
        verify(filteredJobs, timeout(FIRST_POLL_MS).atLeastOnce()).list();
        Thread.sleep(WATCH_MS);

        verify(filteredJobs, times(1)).list();
    }

    @Test
    public void stopEndsTheLoopWithoutWaitingOutTheInterval() throws Exception {
        when(filteredJobs.list()).thenThrow(forbidden());
        monitor.start();
        verify(filteredJobs, timeout(FIRST_POLL_MS).atLeastOnce()).list();
        Thread loop = monitorThread();

        monitor.stop();
        loop.join(WATCH_MS);

        assertFalse(loop.isAlive(), "the polling loop is still running a second after stop()");
    }

    private Thread monitorThread() throws Exception {
        Field field = KubernetesJobMonitor.class.getDeclaredField("monitorThread");
        field.setAccessible(true);
        Thread thread = (Thread) field.get(monitor);
        assertNotNull(thread, "the monitor has no polling thread");
        assertTrue(thread.isAlive(), "the polling thread has already ended");
        return thread;
    }
}
