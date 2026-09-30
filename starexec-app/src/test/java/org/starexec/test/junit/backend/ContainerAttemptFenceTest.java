package org.starexec.test.junit.backend;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.backend.ContainerJobMonitor;
import org.starexec.backend.PodmanBackend;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.database.StageStatusBatchResult;
import org.starexec.data.to.Status.StatusCode;

/**
 * The PODMAN monitor fences every result write on the attempt carried by the container's
 * {@code starexec.attempt} label (#185), and treats a stale refusal as settled: the caller
 * releases the container, nothing is retried and nothing is quarantined.
 *
 * <p>Pure Mockito: JobPairs is mocked, so the assertion is on the arguments passed.
 */
public class ContainerAttemptFenceTest {

    private static final int PAIR = 88;
    private static final Integer ATTEMPT = 9;

    private ContainerJobMonitor monitor;

    @Before
    public void setUp() {
        monitor = new ContainerJobMonitor(mock(PodmanBackend.class));
    }

    private static Path twoStageOutput() throws Exception {
        Path dir = Files.createTempDirectory("container-attempt");
        dir.toFile().deleteOnExit();
        Files.writeString(dir.resolve("status.json"), "{\"pairId\":" + PAIR + ",\"status\":"
            + StatusCode.STATUS_COMPLETE.getVal() + ",\"stageNumber\":2,\"timestamp\":1}\n");
        Files.createDirectories(dir.resolve("stage-status"));
        Files.writeString(dir.resolve("stage-status/1.json"), "{\"pairId\":" + PAIR
            + ",\"status\":" + StatusCode.STATUS_COMPLETE.getVal()
            + ",\"stageNumber\":1,\"timestamp\":1}\n");
        Files.createDirectories(dir.resolve("stage-stats"));
        Files.writeString(dir.resolve("stage-stats/2.json"), "{\"pairId\":" + PAIR
            + ",\"stageNumber\":2,\"wallclockTime\":1.5,\"cpuTime\":1.25,\"userTime\":1,"
            + "\"systemTime\":0,\"maxVirtualMemory\":1000,\"maxResidentSetSize\":10,"
            + "\"diskSize\":100,\"hostname\":\"node-7\"}\n");
        Files.createDirectories(dir.resolve("stage-attributes"));
        Files.writeString(dir.resolve("stage-attributes/2.txt"), "answer=sat\n");
        return dir;
    }

    /** Runs the completion path; a returned call means "settled", a throw means retry/hold. */
    private void process(Path dir, Integer attemptNo) throws Exception {
        PodmanBackend.CompletedContainerInfo info = new PodmanBackend.CompletedContainerInfo(
            "container-attempt-id", PAIR, dir.toString(), 0, 0, attemptNo);
        Method m = ContainerJobMonitor.class.getDeclaredMethod(
            "processCompletedJob", PodmanBackend.CompletedContainerInfo.class);
        m.setAccessible(true);
        try {
            m.invoke(monitor, info);
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
    public void theContainersAttemptReachesEveryResultWrite() throws Exception {
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
    public void theContainersAttemptReachesThePairLevelWrite() throws Exception {
        Path dir = Files.createTempDirectory("container-attempt-pair");
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
    public void aStaleStatusWriteSettlesWithoutRetryOrFurtherWrites() throws Exception {
        Path dir = twoStageOutput();
        try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
            stubHappyPath(db);
            db.when(() -> JobPairs.setPairStatusPreciseResult(
                    anyInt(), anyInt(), anyInt(), anyInt(), anyBoolean(), any()))
                .thenReturn(PairStatusResult.STALE_ATTEMPT);

            // Returning normally is the assertion: no RetryableIngestionException, no
            // InvalidSnapshotException, so the poll loop removes the container.
            process(dir, ATTEMPT);

            db.verify(() -> JobPairs.updateRunSolverStats(anyInt(), anyString(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong(), anyInt(),
                anyLong(), any()), never());
            db.verify(() -> JobPairs.addJobPairAttributes(
                anyInt(), anyInt(), any(Properties.class), any(Integer.class)), never());
        }
    }

    @Test
    public void aStaleStageBatchSettlesWithoutAStatusWrite() throws Exception {
        Path dir = twoStageOutput();
        try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
            stubHappyPath(db);
            db.when(() -> JobPairs.setEarlierStageStatuses(anyInt(), anyMap(), any()))
                .thenReturn(StageStatusBatchResult.STALE_ATTEMPT);

            process(dir, ATTEMPT);

            db.verify(() -> JobPairs.setPairStatusPreciseResult(
                anyInt(), anyInt(), anyInt(), anyInt(), anyBoolean(), any()), never());
        }
    }

    @Test
    public void aContainerWithoutTheLabelCarriesNoAttempt() {
        PodmanBackend.CompletedContainerInfo legacy =
            new PodmanBackend.CompletedContainerInfo("c", PAIR, "/tmp/x", 0, 0);
        assertEquals(null, legacy.attemptNo);
        assertEquals(ATTEMPT, new PodmanBackend.CompletedContainerInfo(
            "c", PAIR, "/tmp/x", 0, 0, ATTEMPT).attemptNo);
    }
}
