package org.starexec.test.junit.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.BatchAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.ScalableResource;
import io.fabric8.kubernetes.client.dsl.V1BatchAPIGroupDSL;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.starexec.backend.ExecutionRef;
import org.starexec.backend.KubernetesJobMonitor;
import org.starexec.backend.KubernetesNativeBackend;
import org.starexec.data.database.Cluster;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.JobPairs.ConditionalPairUpdateResult;

/**
 * What the Kubernetes backend does with the node a running pod is on.
 *
 * <p>The monitor passes the node on every poll that finds the pod running, until the callback
 * returns true, so the return value is the retry contract: true when the node is recorded or
 * there is nothing to record, false to be asked again on the next poll. These tests pin that
 * contract for the outcomes a database cannot produce on demand. KubernetesStageStatusSqlTest
 * drives the same callback against a real database.
 */
public class KubernetesPairPlacementTests {

    private static final String NAMESPACE = "starexec";
    private static final int EXEC_ID = 501;
    private static final String JOB_NAME = "job-501";
    private static final String JOB_UID = "uid-job-501";
    private static final int PAIR_ID = 7001;
    private static final int NODE_ID = 12;
    private static final String NODE = "worker-a";

    private KubernetesNativeBackend backend;
    private KubernetesJobMonitor.JobCompletionCallback callback;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @Before
    public void setUp() throws Exception {
        backend = new KubernetesNativeBackend();
        setField("namespace", NAMESPACE);
        callback = instantiateCompletionCallback();

        logger = (Logger) LoggerFactory.getLogger(KubernetesNativeBackend.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @After
    public void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    public void theNodeOfATrackedPairIsRecorded() throws Exception {
        track(execution(), PAIR_ID);
        try (MockedStatic<Cluster> cluster = Mockito.mockStatic(Cluster.class);
             MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
            cluster.when(() -> Cluster.getNodeIdByName(NODE)).thenReturn(NODE_ID);
            jobPairs.when(() -> JobPairs.tryUpdatePairExecutionHost(PAIR_ID, NODE_ID))
                .thenReturn(ConditionalPairUpdateResult.UPDATED);

            assertTrue(
                "recorded, so the monitor stops asking",
                callback.onJobPlaced(execution(), NODE)
            );
            jobPairs.verify(() -> JobPairs.tryUpdatePairExecutionHost(PAIR_ID, NODE_ID));
        }
        assertEquals(List.of(), messagesAt(Level.WARN));
    }

    /** StarLogger writes every WARN to error_logs, and the monitor asks every five seconds. */
    @Test
    public void aNodeTheNodesTableDoesNotHoldIsAskedForAgainAndWarnedAboutOnce() throws Exception {
        track(execution(), PAIR_ID);
        try (MockedStatic<Cluster> cluster = Mockito.mockStatic(Cluster.class);
             MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
            cluster.when(() -> Cluster.getNodeIdByName(NODE)).thenReturn(-1);

            for (int poll = 1; poll <= 3; poll++) {
                assertFalse(
                    "poll " + poll + ": not recorded, so asked again",
                    callback.onJobPlaced(execution(), NODE)
                );
            }
            cluster.verify(() -> Cluster.getNodeIdByName(NODE), Mockito.times(3));
            jobPairs.verifyNoInteractions();
        }

        List<String> warnings = messagesAt(Level.WARN);
        assertEquals("one line, not one per poll", 1, warnings.size());
        assertTrue(warnings.get(0), warnings.get(0).contains(NODE));
        assertTrue(warnings.get(0), warnings.get(0).contains("Pair " + PAIR_ID));
    }

    /**
     * The node is recorded once the cluster refresh registers it, which is logged once at INFO.
     * A node that goes missing again later is warned about again.
     */
    @Test
    public void aNodeRegisteredLaterIsRecordedThen() throws Exception {
        ExecutionRef later = new ExecutionRef(EXEC_ID + 1, "job-502", "uid-job-502");
        track(execution(), PAIR_ID);
        track(later, PAIR_ID + 1);
        try (MockedStatic<Cluster> cluster = Mockito.mockStatic(Cluster.class);
             MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
            cluster.when(() -> Cluster.getNodeIdByName(NODE)).thenReturn(-1, -1, NODE_ID, -1);
            jobPairs.when(() -> JobPairs.tryUpdatePairExecutionHost(PAIR_ID, NODE_ID))
                .thenReturn(ConditionalPairUpdateResult.UPDATED);

            assertFalse(callback.onJobPlaced(execution(), NODE));
            assertFalse(callback.onJobPlaced(execution(), NODE));
            assertTrue("registered now", callback.onJobPlaced(execution(), NODE));
            assertFalse("missing again", callback.onJobPlaced(later, NODE));

            jobPairs.verify(() -> JobPairs.tryUpdatePairExecutionHost(PAIR_ID, NODE_ID));
        }

        assertEquals(2, messagesAt(Level.WARN).size());
        List<String> registered = messagesAt(Level.INFO).stream()
            .filter(m -> m.contains("Node " + NODE + " is registered now"))
            .collect(Collectors.toList());
        assertEquals(1, registered.size());
    }

    @Test
    public void aPairThatHasFinishedIsNotAskedForAgain() throws Exception {
        track(execution(), PAIR_ID);
        try (MockedStatic<Cluster> cluster = Mockito.mockStatic(Cluster.class);
             MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
            cluster.when(() -> Cluster.getNodeIdByName(NODE)).thenReturn(NODE_ID);
            jobPairs.when(() -> JobPairs.tryUpdatePairExecutionHost(PAIR_ID, NODE_ID))
                .thenReturn(ConditionalPairUpdateResult.STALE);

            assertTrue(
                "finished, or its job is paused, killed or deleted: nothing to record",
                callback.onJobPlaced(execution(), NODE)
            );
            jobPairs.verify(() -> JobPairs.tryUpdatePairExecutionHost(PAIR_ID, NODE_ID));
        }
        assertEquals(List.of(), messagesAt(Level.WARN));
    }

    @Test
    public void aFailedWriteIsAskedForAgain() throws Exception {
        track(execution(), PAIR_ID);
        try (MockedStatic<Cluster> cluster = Mockito.mockStatic(Cluster.class);
             MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
            cluster.when(() -> Cluster.getNodeIdByName(NODE)).thenReturn(NODE_ID);
            jobPairs.when(() -> JobPairs.tryUpdatePairExecutionHost(PAIR_ID, NODE_ID))
                .thenReturn(ConditionalPairUpdateResult.ERROR);

            assertFalse(callback.onJobPlaced(execution(), NODE));
            jobPairs.verify(() -> JobPairs.tryUpdatePairExecutionHost(PAIR_ID, NODE_ID));
        }
        List<String> warnings = messagesAt(Level.WARN);
        assertEquals(warnings.toString(), 1, warnings.size());
        assertTrue(
            warnings.get(0),
            warnings.get(0).startsWith("Failed to record node " + NODE + " for pair " + PAIR_ID)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    public void aStoppedExecutionsNodeIsNotRecorded() throws Exception {
        track(execution(), PAIR_ID);
        ((Set<ExecutionRef>) getField("killedExecutions")).add(execution());
        try (MockedStatic<Cluster> cluster = Mockito.mockStatic(Cluster.class);
             MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
            assertTrue(
                "stopped: nothing to record and nothing to retry",
                callback.onJobPlaced(execution(), NODE)
            );
            cluster.verifyNoInteractions();
            jobPairs.verifyNoInteractions();
        }
    }

    /** Untracked, and the cluster no longer has the Job: no pair to give the node to yet. */
    @Test
    public void aPairNotResolvedYetIsAskedForAgain() throws Exception {
        givenNoJobNamed(JOB_NAME);
        try (MockedStatic<Cluster> cluster = Mockito.mockStatic(Cluster.class);
             MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
            assertFalse(callback.onJobPlaced(execution(), NODE));
            cluster.verifyNoInteractions();
            jobPairs.verifyNoInteractions();
        }
    }

    private static ExecutionRef execution() {
        return new ExecutionRef(EXEC_ID, JOB_NAME, JOB_UID);
    }

    /** The tracking a submission leaves behind, so the callback resolves the pair from it. */
    @SuppressWarnings("unchecked")
    private void track(ExecutionRef execution, int pairId) throws Exception {
        ((Map<Integer, String>) getField("execIdToJobName"))
            .put(execution.execId(), execution.jobName());
        ((Map<Integer, String>) getField("execIdToJobUid"))
            .put(execution.execId(), execution.jobUid());
        ((Map<Integer, Integer>) getField("execIdToPairId")).put(execution.execId(), pairId);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void givenNoJobNamed(String jobName) throws Exception {
        KubernetesClient client = Mockito.mock(KubernetesClient.class);
        BatchAPIGroupDSL batch = Mockito.mock(BatchAPIGroupDSL.class);
        V1BatchAPIGroupDSL v1 = Mockito.mock(V1BatchAPIGroupDSL.class);
        MixedOperation jobs = Mockito.mock(MixedOperation.class);
        NonNamespaceOperation namespacedJobs = Mockito.mock(NonNamespaceOperation.class);
        ScalableResource job = Mockito.mock(ScalableResource.class);

        Mockito.when(client.batch()).thenReturn(batch);
        Mockito.when(batch.v1()).thenReturn(v1);
        Mockito.when(v1.jobs()).thenReturn(jobs);
        Mockito.when(jobs.inNamespace(NAMESPACE)).thenReturn(namespacedJobs);
        Mockito.when(namespacedJobs.withName(jobName)).thenReturn(job);
        Mockito.when(job.get()).thenReturn(null);

        setField("kubernetesClient", client);
    }

    private List<String> messagesAt(Level level) {
        return appender.list.stream()
            .filter(event -> event.getLevel() == level)
            .map(ILoggingEvent::getFormattedMessage)
            .collect(Collectors.toList());
    }

    private KubernetesJobMonitor.JobCompletionCallback instantiateCompletionCallback()
        throws Exception {
        Class<?> callbackClass = Class.forName(
            "org.starexec.backend.KubernetesNativeBackend$KubernetesJobCompletionCallback"
        );
        Constructor<?> constructor =
            callbackClass.getDeclaredConstructor(KubernetesNativeBackend.class);
        constructor.setAccessible(true);
        return (KubernetesJobMonitor.JobCompletionCallback) constructor.newInstance(backend);
    }

    private Object getField(String name) throws Exception {
        Field field = KubernetesNativeBackend.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(backend);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = KubernetesNativeBackend.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(backend, value);
    }
}
