package org.starexec.test.junit.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.backend.LocalJobMonitor;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.database.StageStatusBatchResult;
import org.starexec.data.to.Status.StatusCode;

/**
 * The LOCAL monitor fences every result write on the attempt its pair was registered with
 * (#185), and treats a stale refusal as a settled outcome rather than a failure.
 *
 * <p>Pure Mockito: JobPairs is mocked, so the assertion is on the arguments the monitor passes.
 */
public class LocalAttemptFenceTest {

    private static final int PAIR = 77;
    private static final Integer ATTEMPT = 7;

    private LocalJobMonitor monitor;

    @Before
    public void setUp() {
        monitor = new LocalJobMonitor();
    }

    @After
    public void tearDown() {
        monitor.stop();
    }

    private static String stats(int stage) {
        return "{\"pairId\":" + PAIR + ",\"stageNumber\":" + stage + ",\"wallclockTime\":1.5,"
            + "\"cpuTime\":1.25,\"userTime\":1,\"systemTime\":0,\"maxVirtualMemory\":1000,"
            + "\"maxResidentSetSize\":10,\"diskSize\":100,\"hostname\":\"node-7\"}\n";
    }

    /** Two stages, both finished: stage 1 by snapshot, stage 2 by status.json. */
    private static Path twoStageOutput() throws Exception {
        Path dir = Files.createTempDirectory("local-attempt");
        dir.toFile().deleteOnExit();
        Files.writeString(dir.resolve("status.json"), "{\"pairId\":" + PAIR + ",\"status\":"
            + StatusCode.STATUS_COMPLETE.getVal() + ",\"stageNumber\":2,\"timestamp\":1}\n");
        Files.createDirectories(dir.resolve("stage-status"));
        Files.writeString(dir.resolve("stage-status/1.json"), "{\"pairId\":" + PAIR
            + ",\"status\":" + StatusCode.STATUS_COMPLETE.getVal()
            + ",\"stageNumber\":1,\"timestamp\":1}\n");
        Files.createDirectories(dir.resolve("stage-stats"));
        Files.writeString(dir.resolve("stage-stats/2.json"), stats(2));
        Files.createDirectories(dir.resolve("stage-attributes"));
        Files.writeString(dir.resolve("stage-attributes/2.txt"), "answer=sat\n");
        return dir;
    }

    private boolean process(Path dir, Integer attemptNo) throws Exception {
        monitor.registerJob(dir.toString(), PAIR, attemptNo);
        Field f = LocalJobMonitor.class.getDeclaredField("pairs");
        f.setAccessible(true);
        Object state = ((ConcurrentHashMap<?, ?>) f.get(monitor)).get(PAIR);
        Method m = LocalJobMonitor.class.getDeclaredMethod(
            "processCompletedJob", int.class, state.getClass());
        m.setAccessible(true);
        try {
            return (Boolean) m.invoke(monitor, PAIR, state);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static void stubHappyPath(MockedStatic<JobPairs> db) {
        db.when(() -> JobPairs.getStageNumbers(PAIR)).thenReturn(Set.of(1, 2));
        db.when(() -> JobPairs.setEarlierStageStatuses(anyInt(), anyMap(), any()))
            .thenReturn(StageStatusBatchResult.APPLIED);
        db.when(() -> JobPairs.setPairStatusPreciseResult(
                anyInt(), anyInt(), anyInt(), anyInt(), anyBoolean(), any()))
            .thenReturn(PairStatusResult.APPLIED);
        db.when(() -> JobPairs.updateRunSolverStats(anyInt(), anyString(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong(), anyInt(),
                anyLong(), any()))
            .thenReturn(true);
        db.when(() -> JobPairs.addJobPairAttributes(
                anyInt(), anyInt(), any(Properties.class), any(Integer.class)))
            .thenReturn(true);
    }

    @Test
    public void theRegisteredAttemptReachesEveryResultWrite() throws Exception {
        Path dir = twoStageOutput();
        try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
            stubHappyPath(db);

            process(dir, ATTEMPT);

            db.verify(() -> JobPairs.setEarlierStageStatuses(
                eq(PAIR), eq(Map.of(1, StatusCode.STATUS_COMPLETE.getVal())), eq(ATTEMPT)));
            db.verify(() -> JobPairs.setPairStatusPreciseResult(eq(PAIR), eq(2),
                eq(StatusCode.STATUS_COMPLETE.getVal()),
                eq(StatusCode.STATUS_NOT_REACHED.getVal()), eq(false), eq(ATTEMPT)));
            db.verify(() -> JobPairs.updateRunSolverStats(eq(PAIR), anyString(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong(), eq(2),
                anyLong(), eq(ATTEMPT)));
            db.verify(() -> JobPairs.addJobPairAttributes(
                eq(PAIR), eq(2), any(Properties.class), eq(ATTEMPT)));
        }
    }

    @Test
    public void theRegisteredAttemptReachesThePairLevelWrite() throws Exception {
        Path dir = Files.createTempDirectory("local-attempt-pair");
        dir.toFile().deleteOnExit();
        Files.writeString(dir.resolve("status.json"), "{\"pairId\":" + PAIR + ",\"status\":"
            + StatusCode.ERROR_RUNSCRIPT.getVal() + ",\"stageNumber\":0,\"timestamp\":1}\n");
        try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
            db.when(() -> JobPairs.getStageNumbers(PAIR)).thenReturn(Set.of(1, 2));
            db.when(() -> JobPairs.setPairLevelStatusResult(anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(PairStatusResult.APPLIED);

            process(dir, ATTEMPT);

            db.verify(() -> JobPairs.setPairLevelStatusResult(eq(PAIR),
                eq(StatusCode.ERROR_RUNSCRIPT.getVal()),
                eq(StatusCode.STATUS_NOT_REACHED.getVal()), eq(ATTEMPT)));
        }
    }

    @Test
    public void aStaleStatusWriteReleasesTheTrackingWithoutFurtherWrites() throws Exception {
        Path dir = twoStageOutput();
        try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
            stubHappyPath(db);
            db.when(() -> JobPairs.setPairStatusPreciseResult(
                    anyInt(), anyInt(), anyInt(), anyInt(), anyBoolean(), any()))
                .thenReturn(PairStatusResult.STALE_ATTEMPT);

            // No RetryableIngestionException / InvalidSnapshotException: it returns.
            assertTrue("a stale execution is finished, so the pair is retired",
                process(dir, ATTEMPT));

            db.verify(() -> JobPairs.updateRunSolverStats(anyInt(), anyString(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong(), anyInt(),
                anyLong(), any()), never());
            db.verify(() -> JobPairs.addJobPairAttributes(
                anyInt(), anyInt(), any(Properties.class), any(Integer.class)), never());
        }
    }

    @Test
    public void aStaleStageBatchReleasesTheTrackingWithoutAStatusWrite() throws Exception {
        Path dir = twoStageOutput();
        try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
            stubHappyPath(db);
            db.when(() -> JobPairs.setEarlierStageStatuses(anyInt(), anyMap(), any()))
                .thenReturn(StageStatusBatchResult.STALE_ATTEMPT);

            assertTrue(process(dir, ATTEMPT));

            db.verify(() -> JobPairs.setPairStatusPreciseResult(
                anyInt(), anyInt(), anyInt(), anyInt(), anyBoolean(), any()), never());
            db.verify(() -> JobPairs.updateRunSolverStats(anyInt(), anyString(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong(), anyInt(),
                anyLong(), any()), never());
        }
    }

    @Test
    public void theTwoArgumentRegistrationCarriesNoAttempt() throws Exception {
        monitor.registerJob("/tmp/no-attempt", PAIR);
        Field f = LocalJobMonitor.class.getDeclaredField("pairs");
        f.setAccessible(true);
        Object state = ((ConcurrentHashMap<?, ?>) f.get(monitor)).get(PAIR);
        Field a = state.getClass().getDeclaredField("attemptNo");
        a.setAccessible(true);
        assertEquals(null, a.get(state));
    }
}
