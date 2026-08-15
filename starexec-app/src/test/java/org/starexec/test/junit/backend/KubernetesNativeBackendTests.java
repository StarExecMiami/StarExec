package org.starexec.test.junit.backend;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.backend.KubernetesNativeBackend;
import org.starexec.backend.KubernetesJobMonitor;
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
            .getDeclaredMethod("rebuildTrackingFromJob", Job.class);
        rebuildTrackingFromJob.setAccessible(true);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.trySetPairRunning(707))
                .thenReturn(JobPairs.ConditionalPairUpdateResult.UPDATED);

            rebuildTrackingFromJob.invoke(backend, job);

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
            .build();

        Method rebuildTrackingFromJob = KubernetesNativeBackend.class
            .getDeclaredMethod("rebuildTrackingFromJob", Job.class);
        rebuildTrackingFromJob.setAccessible(true);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            rebuildTrackingFromJob.invoke(backend, job);
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

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.trySetPairRunning(515))
                .thenReturn(
                    JobPairs.ConditionalPairUpdateResult.ERROR,
                    JobPairs.ConditionalPairUpdateResult.UPDATED
                );

            assertFalse(callback.onJobRunning(15, "job-15"));
            assertEquals("job-15", execToJob.get(15));
            assertEquals(Integer.valueOf(515), execToPair.get(15));

            assertTrue(callback.onJobRunning(15, "job-15"));
            jobPairsMock.verify(() -> JobPairs.trySetPairRunning(515), Mockito.times(2));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void runningCallbackLeavesKilledMarkerForTerminalCallbacks() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        int execId = 16;
        Set<Integer> killedExecIds =
            (Set<Integer>) getField(backend, "killedExecIds");
        killedExecIds.add(execId);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            assertTrue(callback.onJobRunning(execId, "job-16"));
            assertTrue(killedExecIds.contains(execId));
            jobPairsMock.verifyNoInteractions();

            assertTrue(callback.onJobComplete(execId, "job-16"));
            assertFalse(killedExecIds.contains(execId));
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

            assertFalse(callback.onJobComplete(11, "job-11"));
            assertEquals("job-11", execToJob.get(11));
            assertEquals(Integer.valueOf(111), execToPair.get(11));
            assertEquals(Path.of("/tmp/output/11"), execToOut.get(11));

            assertTrue(callback.onJobComplete(11, "job-11"));
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

            assertTrue(callback.onJobComplete(21, "job-21"));
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

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(222))
                .thenReturn(foundLookup(StatusCode.STATUS_RUNNING.getVal()));
            jobPairsMock
                .when(
                    () ->
                        JobPairs.setPairStatusPrecise(
                            222,
                            1,
                            StatusCode.ERROR_RUNSCRIPT.getVal(),
                            StatusCode.STATUS_NOT_REACHED.getVal()
                        )
                )
                .thenReturn(false, true);

            assertFalse(callback.onJobFailed(12, "job-12", "BackoffLimitExceeded"));
            assertEquals("job-12", execToJob.get(12));
            assertEquals(Integer.valueOf(222), execToPair.get(12));
            assertEquals(Path.of("/tmp/output/12"), execToOut.get(12));

            assertTrue(callback.onJobFailed(12, "job-12", "BackoffLimitExceeded"));
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

            assertFalse(callback.onJobComplete(13, "job-13"));
            assertEquals("job-13", execToJob.get(13));
            assertEquals(Integer.valueOf(313), execToPair.get(13));
            assertEquals(Path.of("/tmp/output/13"), execToOut.get(13));

            assertTrue(callback.onJobComplete(13, "job-13"));
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

        KubernetesJobMonitor.JobCompletionCallback callback =
            instantiateCompletionCallback(backend);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getPairStatusLookup(414))
                .thenReturn(foundLookup(StatusCode.STATUS_RUNNING.getVal()));
            jobPairsMock
                .when(
                    () ->
                        JobPairs.setPairStatusPrecise(
                            414,
                            1,
                            StatusCode.ERROR_RUNSCRIPT.getVal(),
                            StatusCode.STATUS_NOT_REACHED.getVal()
                        )
                )
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(true);

            assertFalse(callback.onJobFailed(14, "job-14", "BackoffLimitExceeded"));
            assertEquals("job-14", execToJob.get(14));
            assertEquals(Integer.valueOf(414), execToPair.get(14));
            assertEquals(Path.of("/tmp/output/14"), execToOut.get(14));

            assertTrue(callback.onJobFailed(14, "job-14", "BackoffLimitExceeded"));
            assertTrue(execToJob.isEmpty());
            assertTrue(execToPair.isEmpty());
            assertTrue(execToOut.isEmpty());
        }
    }

    private Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
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
}
