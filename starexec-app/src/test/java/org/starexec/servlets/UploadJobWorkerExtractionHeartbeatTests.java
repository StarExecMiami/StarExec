package org.starexec.servlets;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.UploadJobQueue;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;

/**
 * Extraction progress reporting runs once per archive entry, so its cost is multiplied by the
 * entry count. For the TPTP Problems distribution that is 26,990 entries, and a database write
 * on almost every one of them made the archive impossible to unpack inside the extraction
 * timeout: measured 13.9 entries/second against 307 for a plain tar of the same file.
 *
 * <p>These tests pin the two halves of the contract that keeps that from coming back: the
 * progress row still advances on entry count, and the heartbeat is bounded by elapsed time
 * rather than by how many entries went past.
 */
public class UploadJobWorkerExtractionHeartbeatTests {

    private static final long JOB_ID = 4242L;

    /** Drives the real callback with a clock the test controls. */
    private Runnable callbackWith(AtomicInteger extractedCount, AtomicLong clockNanos)
            throws Exception {
        UploadJobWorker worker = new UploadJobWorker();
        worker.nanoTime = clockNanos::get;
        Method factory = UploadJobWorker.class.getDeclaredMethod(
            "createExtractionProgressCallback", long.class, AtomicInteger.class);
        factory.setAccessible(true);
        return (Runnable) factory.invoke(worker, JOB_ID, extractedCount);
    }

    /**
     * The regression guard. Ten thousand entries arriving inside a single clock tick must not
     * produce ten thousand database writes. Before this was throttled the same run issued a
     * touchJob for ninety-nine of every hundred entries.
     */
    @Test
    public void heartbeatsAreBoundedByElapsedTimeNotEntryCount() throws Exception {
        AtomicInteger extracted = new AtomicInteger(0);
        AtomicLong clock = new AtomicLong(0L);

        try (MockedStatic<UploadJobQueue> queue = Mockito.mockStatic(UploadJobQueue.class)) {
            Runnable callback = callbackWith(extracted, clock);
            for (int i = 1; i <= 10_000; i++) {
                extracted.set(i);
                callback.run();
            }

            queue.verify(() -> UploadJobQueue.touchJob(Mockito.anyLong()), Mockito.never());
            queue.verify(
                () -> UploadJobQueue.updateProgress(
                    Mockito.eq(JOB_ID), Mockito.anyInt(), Mockito.any(), Mockito.any(),
                    Mockito.any(), Mockito.any()),
                Mockito.times(100)
            );
        }
    }

    /** Time passing, not entries passing, is what releases a heartbeat. */
    @Test
    public void oneHeartbeatIsEmittedPerElapsedInterval() throws Exception {
        AtomicInteger extracted = new AtomicInteger(1);
        AtomicLong clock = new AtomicLong(0L);
        long oneSecond = 1_000_000_000L;

        try (MockedStatic<UploadJobQueue> queue = Mockito.mockStatic(UploadJobQueue.class)) {
            Runnable callback = callbackWith(extracted, clock);

            callback.run();
            queue.verify(() -> UploadJobQueue.touchJob(JOB_ID), Mockito.never());

            clock.addAndGet(oneSecond);
            callback.run();
            callback.run();
            callback.run();
            queue.verify(() -> UploadJobQueue.touchJob(JOB_ID), Mockito.times(1));

            clock.addAndGet(oneSecond);
            callback.run();
            queue.verify(() -> UploadJobQueue.touchJob(JOB_ID), Mockito.times(2));
        }
    }

    /**
     * A progress row also stamps last_heartbeat, so it counts as a heartbeat and restarts the
     * interval. Without that the two writers would alternate and double the traffic.
     */
    @Test
    public void aProgressWriteCountsAsTheHeartbeatForThatInterval() throws Exception {
        AtomicInteger extracted = new AtomicInteger(0);
        AtomicLong clock = new AtomicLong(0L);

        try (MockedStatic<UploadJobQueue> queue = Mockito.mockStatic(UploadJobQueue.class)) {
            Runnable callback = callbackWith(extracted, clock);

            clock.addAndGet(5_000_000_000L);   // long overdue for a heartbeat
            extracted.set(100);                // and exactly on a progress boundary
            callback.run();

            queue.verify(
                () -> UploadJobQueue.updateProgress(
                    Mockito.eq(JOB_ID), Mockito.eq(100), Mockito.any(), Mockito.any(),
                    Mockito.any(), Mockito.any()),
                Mockito.times(1)
            );
            queue.verify(() -> UploadJobQueue.touchJob(Mockito.anyLong()), Mockito.never());
        }
    }

    /**
     * An archive whose first entry is large produces callbacks before any entry completes.
     * The job must still show a heartbeat, or it reads as stuck while it is working.
     */
    @Test
    public void aJobWithNothingExtractedYetStillHeartbeats() throws Exception {
        AtomicInteger extracted = new AtomicInteger(0);
        AtomicLong clock = new AtomicLong(0L);

        try (MockedStatic<UploadJobQueue> queue = Mockito.mockStatic(UploadJobQueue.class)) {
            Runnable callback = callbackWith(extracted, clock);
            clock.addAndGet(2_000_000_000L);
            callback.run();

            queue.verify(() -> UploadJobQueue.touchJob(JOB_ID), Mockito.times(1));
            queue.verify(
                () -> UploadJobQueue.updateProgress(
                    Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any(),
                    Mockito.any(), Mockito.any()),
                Mockito.never()
            );
        }
    }

    /** The whole point: writes must scale with time, not with archive size. */
    @Test
    public void databaseWritesScaleWithTimeNotWithArchiveSize() throws Exception {
        AtomicInteger extracted = new AtomicInteger(0);
        AtomicLong clock = new AtomicLong(0L);
        long perEntryNanos = 3_000_000L;   // ~3 ms/entry, the measured raw extraction rate

        try (MockedStatic<UploadJobQueue> queue = Mockito.mockStatic(UploadJobQueue.class)) {
            Runnable callback = callbackWith(extracted, clock);
            for (int i = 1; i <= 26_990; i++) {   // the real TPTP Problems entry count
                extracted.set(i);
                clock.addAndGet(perEntryNanos);
                callback.run();
            }

            // 26,990 entries over ~81 s: 269 progress rows, and heartbeats only in the gaps
            // between them. The pre-fix code issued about 26,720 touchJob calls here.
            queue.verify(
                () -> UploadJobQueue.updateProgress(
                    Mockito.eq(JOB_ID), Mockito.anyInt(), Mockito.any(), Mockito.any(),
                    Mockito.any(), Mockito.any()),
                Mockito.times(269)
            );
            queue.verify(() -> UploadJobQueue.touchJob(Mockito.anyLong()), Mockito.never());
        }
    }

    /** Guards the constant itself against drifting past what isStuck() tolerates. */
    @Test
    public void theHeartbeatIntervalStaysWellInsideTheStuckThreshold() throws Exception {
        java.lang.reflect.Field f =
            UploadJobWorker.class.getDeclaredField("EXTRACTION_HEARTBEAT_MIN_INTERVAL_MS");
        f.setAccessible(true);
        long intervalMs = (long) f.get(null);
        assertEquals("a one-second floor is what the traversal callback already uses",
            1000L, intervalMs);
        // UploadJob.isStuck() reports stuck after five minutes without a heartbeat.
        org.junit.Assert.assertTrue(
            "heartbeat interval must stay far inside the five-minute stuck threshold",
            intervalMs * 10 < 5 * 60 * 1000L);
    }
}
