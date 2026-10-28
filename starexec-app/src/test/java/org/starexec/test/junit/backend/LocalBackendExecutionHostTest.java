package org.starexec.test.junit.backend;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.backend.LocalBackend;
import org.starexec.data.database.Cluster;
import org.starexec.data.database.JobPairs;

/**
 * How LocalBackend records the node a pair runs on, which is what lists the pair in the cluster
 * page's node view while it runs.
 *
 * <p>The node id is cached once it is known and looked up again while it is not. These tests call
 * the recording step directly, on the test thread, because the static mocks that stand in for the
 * database only reach that thread; {@code LocalBackendMultiStageE2ESqlTest} runs the same step
 * through a real pair start against a real database.
 */
public class LocalBackendExecutionHostTest {

    /** The name LocalBackend uses before {@code initialize()} replaces it with the host name. */
    private static final String NODE = "local-node";

    @Test
    public void aNodeUnknownAtStartupIsLookedUpWhenAPairStarts() throws Exception {
        LocalBackend backend = new LocalBackend();
        try (MockedStatic<Cluster> cluster = Mockito.mockStatic(Cluster.class);
                MockedStatic<JobPairs> pairs = Mockito.mockStatic(JobPairs.class)) {
            cluster.when(() -> Cluster.getNodeIdByName(NODE)).thenReturn(7);
            pairs.when(() -> JobPairs.updatePairExecutionHost(42, 7)).thenReturn(true);

            recordExecutionHost(backend, 42);

            pairs.verify(() -> JobPairs.updatePairExecutionHost(42, 7));
            assertEquals(7, cachedNodeId(backend), "a found id is cached");
        }
    }

    @Test
    public void aCachedNodeIsNotLookedUpAgain() throws Exception {
        LocalBackend backend = new LocalBackend();
        setCachedNodeId(backend, 7);
        try (MockedStatic<Cluster> cluster = Mockito.mockStatic(Cluster.class);
                MockedStatic<JobPairs> pairs = Mockito.mockStatic(JobPairs.class)) {
            recordExecutionHost(backend, 42);
            recordExecutionHost(backend, 43);

            cluster.verifyNoInteractions();
            pairs.verify(() -> JobPairs.updatePairExecutionHost(42, 7));
            pairs.verify(() -> JobPairs.updatePairExecutionHost(43, 7));
        }
    }

    @Test
    public void aNodeStillUnregisteredIsLookedUpAgainWhenTheNextPairStarts() throws Exception {
        LocalBackend backend = new LocalBackend();
        try (MockedStatic<Cluster> cluster = Mockito.mockStatic(Cluster.class);
                MockedStatic<JobPairs> pairs = Mockito.mockStatic(JobPairs.class)) {
            cluster.when(() -> Cluster.getNodeIdByName(NODE)).thenReturn(-1, 9);

            recordExecutionHost(backend, 42);
            pairs.verify(
                    () -> JobPairs.updatePairExecutionHost(Mockito.anyInt(), Mockito.anyInt()),
                    Mockito.never());
            assertEquals(-1, cachedNodeId(backend), "a miss is not cached");

            recordExecutionHost(backend, 43);
            pairs.verify(() -> JobPairs.updatePairExecutionHost(43, 9));
            cluster.verify(() -> Cluster.getNodeIdByName(NODE), Mockito.times(2));
            assertEquals(9, cachedNodeId(backend));
        }
    }

    @Test
    public void aFailedHostWriteDoesNotEscapeThePairStart() throws Exception {
        LocalBackend backend = new LocalBackend();
        setCachedNodeId(backend, 7);
        try (MockedStatic<JobPairs> pairs = Mockito.mockStatic(JobPairs.class)) {
            pairs.when(() -> JobPairs.updatePairExecutionHost(42, 7))
                    .thenThrow(new IllegalStateException("database unavailable"));

            recordExecutionHost(backend, 42);

            pairs.verify(() -> JobPairs.updatePairExecutionHost(42, 7));
        }
    }

    private static void recordExecutionHost(LocalBackend backend, int pairId) throws Exception {
        Method method = LocalBackend.class.getDeclaredMethod("recordExecutionHost", int.class);
        method.setAccessible(true);
        try {
            method.invoke(backend, pairId);
        } catch (InvocationTargetException e) {
            // Rethrow what the method threw, so a failure names it rather than the reflection.
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    private static int cachedNodeId(LocalBackend backend) throws Exception {
        return cachedNodeIdField().getInt(backend);
    }

    private static void setCachedNodeId(LocalBackend backend, int nodeId) throws Exception {
        cachedNodeIdField().setInt(backend, nodeId);
    }

    private static Field cachedNodeIdField() throws Exception {
        Field field = LocalBackend.class.getDeclaredField("cachedNodeId");
        field.setAccessible(true);
        return field;
    }
}
