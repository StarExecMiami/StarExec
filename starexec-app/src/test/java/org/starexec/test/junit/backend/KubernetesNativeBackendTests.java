package org.starexec.test.junit.backend;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Test;
import org.starexec.backend.KubernetesNativeBackend;

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
    public void killAllClearsTrackingMapsEvenWithoutClient() throws Exception {
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

        execToJob.put(1, "job-1");
        execToPair.put(1, 111);
        execToOut.put(1, Path.of("/tmp/output/1"));

        // No Kubernetes client initialized: killAll will fail API call but must
        // still clear all in-memory tracking in finally.
        assertFalse(backend.killAll());
        assertTrue(execToJob.isEmpty());
        assertTrue(execToPair.isEmpty());
        assertTrue(execToOut.isEmpty());
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
}
