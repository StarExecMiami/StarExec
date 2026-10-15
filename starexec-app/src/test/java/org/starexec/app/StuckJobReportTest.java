package org.starexec.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.starexec.app.PeriodicTasks.StuckJob;

/**
 * What the stuck-job check says about a job that has stopped making progress.
 *
 * <p>It used to say the same thing about every job -- check LocalBackend's logs, look for
 * missing status.json files, watch LocalJobMonitor -- whatever the backend and whatever was
 * actually wrong, and it said it every five minutes. On Quokka the job it reported had its
 * pairs pending on an inactive queue, which none of that advice mentions.
 */
public class StuckJobReportTest {

    private static StuckJob job(String queue, String status, int pending, int enqueued,
            int running, int processing) {
        return new StuckJob(1, "DRODI", queue, status, pending, enqueued, running, processing, 4321);
    }

    @Test
    public void pendingPairsOnAnInactiveQueueNameTheQueue() {
        String report = PeriodicTasks.describeStuckJob(job("default", "INACTIVE", 35, 0, 0, 0));
        assertEquals(
            "Job 1 (DRODI) has made no progress for 4321 minutes (35 pending, 0 enqueued,"
                + " 0 running, 0 awaiting result processing): its queue 'default' is INACTIVE,"
                + " so its pending pairs cannot be dispatched",
            report);
    }

    @Test
    public void pendingPairsWhoseQueueIsGoneSaySo() {
        assertTrue(PeriodicTasks.describeStuckJob(job(null, null, 3, 0, 0, 0))
            .endsWith("its queue no longer exists, so nothing can dispatch its pending pairs"));
    }

    @Test
    public void pairsInFlightPointAtTheBackend() {
        assertTrue(PeriodicTasks.describeStuckJob(job("kubernetes.q", "ACTIVE", 10, 2, 3, 0))
            .endsWith("pairs are in flight but none has started or finished; check the backend"
                + " and its job monitor"));
    }

    @Test
    public void pendingPairsOnAnActiveQueuePointAtItsNodes() {
        assertTrue(PeriodicTasks.describeStuckJob(job("kubernetes.q", "ACTIVE", 10, 0, 0, 0))
            .endsWith("nothing is being dispatched from queue 'kubernetes.q'; check that the"
                + " queue has nodes the backend can use"));
    }

    @Test
    public void onlyResultProcessingLeftSaysSo() {
        assertTrue(PeriodicTasks.describeStuckJob(job("kubernetes.q", "ACTIVE", 0, 0, 0, 4))
            .endsWith("its pairs are waiting for result processing"));
    }

    /** An inactive queue explains pending pairs, not pairs already in flight on it. */
    @Test
    public void anInactiveQueueIsNotBlamedForPairsAlreadyInFlight() {
        assertTrue(PeriodicTasks.describeStuckJob(job("default", "INACTIVE", 0, 0, 2, 0))
            .endsWith("pairs are in flight but none has started or finished; check the backend"
                + " and its job monitor"));
    }

    @Test
    public void noReportCarriesBackendSpecificAdvice() {
        for (StuckJob j : new StuckJob[] {
                job("default", "INACTIVE", 35, 0, 0, 0), job(null, null, 3, 0, 0, 0),
                job("q", "ACTIVE", 1, 1, 1, 0), job("q", "ACTIVE", 1, 0, 0, 0),
                job("q", "ACTIVE", 0, 0, 0, 1)}) {
            String report = PeriodicTasks.describeStuckJob(j);
            assertFalse(report, report.contains("LocalBackend"));
            assertFalse(report, report.contains("LocalJobMonitor"));
        }
    }

    @Test
    public void aStuckJobIsReportedOnceThenAgainOnlyAfterTheRepeatInterval() {
        Map<Integer, Long> reported = new HashMap<>();
        long t0 = 1_000_000L;
        assertTrue("first sighting", PeriodicTasks.shouldReportStuckJob(reported, 1, t0));
        assertFalse("five minutes later",
            PeriodicTasks.shouldReportStuckJob(reported, 1, t0 + 5 * 60_000L));
        assertFalse("just before the interval", PeriodicTasks.shouldReportStuckJob(
            reported, 1, t0 + PeriodicTasks.STUCK_JOB_REPEAT_MILLIS - 1));
        assertTrue("at the interval", PeriodicTasks.shouldReportStuckJob(
            reported, 1, t0 + PeriodicTasks.STUCK_JOB_REPEAT_MILLIS));
        assertTrue("another job has its own clock",
            PeriodicTasks.shouldReportStuckJob(reported, 2, t0 + 5 * 60_000L));
    }
}
