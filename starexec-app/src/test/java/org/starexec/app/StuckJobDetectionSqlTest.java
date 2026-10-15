package org.starexec.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.app.PeriodicTasks.StuckJob;
import org.starexec.data.database.Common;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

/**
 * {@link PeriodicTasks#findStuckJobs} against the real SQL.
 *
 * <p>A job is stuck when it has unfinished pairs, is not paused, killed or deleted, and no pair
 * of it has started or finished for longer than its own wallclock limit plus ten minutes, and
 * at least thirty. The check used to report any job older than ten minutes with an unfinished
 * pair, so a healthy job that runs for hours was reported as stuck every five minutes.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point {@code STAREXEC_DB_URL} /
 * {@code STAREXEC_DB_USER} / {@code STAREXEC_DB_PASSWORD} at a <strong>disposable</strong>
 * database: these tests insert and delete rows.
 */
public class StuckJobDetectionSqlTest extends Common {

    private final List<Integer> jobIds = new ArrayList<>();
    private final List<Integer> queueIds = new ArrayList<>();
    private int userId;
    private int activeQueue;
    private int inactiveQueue;

    @BeforeClass
    public static void requireDatabase() {
        DatabaseTestSupport.assumeDatabaseAvailable("StuckJobDetectionSqlTest");
        Common.initialize();
    }

    @Before
    public void createQueues() throws SQLException {
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT min(id) FROM starexec.users");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            userId = rs.getInt(1);
        }
        activeQueue = insertQueue("ACTIVE");
        inactiveQueue = insertQueue("INACTIVE");
    }

    @After
    public void dropFixture() throws SQLException {
        try (Connection con = Common.getConnection()) {
            for (int job : jobIds) {
                try (PreparedStatement ps = con.prepareStatement(
                        "DELETE FROM starexec.job_pairs WHERE job_id = ?")) {
                    ps.setInt(1, job);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = con.prepareStatement(
                        "DELETE FROM starexec.jobs WHERE id = ?")) {
                    ps.setInt(1, job);
                    ps.executeUpdate();
                }
            }
            for (int queue : queueIds) {
                try (PreparedStatement ps = con.prepareStatement(
                        "DELETE FROM starexec.queues WHERE id = ?")) {
                    ps.setInt(1, queue);
                    ps.executeUpdate();
                }
            }
        }
        jobIds.clear();
        queueIds.clear();
    }

    /** Quokka's job 1: pending pairs on an inactive queue, nothing done for days. */
    @Test
    public void pendingPairsOnAnInactiveQueueAreStuck() throws SQLException {
        int job = insertJob(inactiveQueue, "2 days", 300, false);
        insertPair(job, StatusCode.STATUS_PENDING_SUBMIT, null, null);
        insertPair(job, StatusCode.STATUS_PENDING_SUBMIT, null, null);

        StuckJob found = stuckJobs().get(job);
        assertNotNull(found);
        assertEquals(2, found.pending);
        assertEquals("INACTIVE", found.queueStatus);
        assertTrue("idle since it was created", found.idleMinutes >= 2 * 24 * 60 - 1);
        assertTrue(PeriodicTasks.describeStuckJob(found).contains("is INACTIVE"));
    }

    @Test
    public void aJobWhosePairsStoppedFinishingIsStuck() throws SQLException {
        int job = insertJob(activeQueue, "2 days", 300, false);
        insertPair(job, StatusCode.STATUS_COMPLETE, "3 hours", "3 hours");
        insertPair(job, StatusCode.STATUS_PENDING_SUBMIT, null, null);

        StuckJob found = stuckJobs().get(job);
        assertNotNull(found);
        assertTrue("idle since its last pair finished", found.idleMinutes >= 179
            && found.idleMinutes <= 181);
    }

    @Test
    public void aJobThatJustStartedAPairIsNotStuck() throws SQLException {
        int job = insertJob(activeQueue, "2 days", 300, false);
        insertPair(job, StatusCode.STATUS_RUNNING, "1 minute", null);
        insertPair(job, StatusCode.STATUS_PENDING_SUBMIT, null, null);
        assertFalse(stuckJobs().containsKey(job));
    }

    /**
     * The old check reported this one: a healthy job whose pairs run for hours. A pair inside
     * its own wallclock limit is not evidence of anything.
     */
    @Test
    public void aLongPairInsideItsWallclockLimitIsNotStuck() throws SQLException {
        int job = insertJob(activeQueue, "2 days", 7200, false);
        insertPair(job, StatusCode.STATUS_RUNNING, "90 minutes", null);
        assertFalse(stuckJobs().containsKey(job));
    }

    @Test
    public void aLongPairPastItsWallclockLimitIsStuck() throws SQLException {
        int job = insertJob(activeQueue, "2 days", 3600, false);
        insertPair(job, StatusCode.STATUS_RUNNING, "90 minutes", null);
        StuckJob found = stuckJobs().get(job);
        assertNotNull(found);
        assertEquals(1, found.running);
    }

    @Test
    public void aPausedJobIsNotStuck() throws SQLException {
        int job = insertJob(activeQueue, "2 days", 300, true);
        insertPair(job, StatusCode.STATUS_PENDING_SUBMIT, null, null);
        assertFalse(stuckJobs().containsKey(job));
    }

    @Test
    public void aYoungJobIsNotStuckYet() throws SQLException {
        int job = insertJob(activeQueue, "5 minutes", 300, false);
        insertPair(job, StatusCode.STATUS_PENDING_SUBMIT, null, null);
        assertFalse(stuckJobs().containsKey(job));
    }

    @Test
    public void aJobWithNothingUnfinishedIsNotStuck() throws SQLException {
        int job = insertJob(activeQueue, "2 days", 300, false);
        insertPair(job, StatusCode.STATUS_COMPLETE, "3 hours", "3 hours");
        assertFalse(stuckJobs().containsKey(job));
    }

    @Test
    public void aJobWhoseQueueIsGoneReportsNoQueue() throws SQLException {
        int job = insertJob(null, "2 days", 300, false);
        insertPair(job, StatusCode.STATUS_PENDING_SUBMIT, null, null);
        StuckJob found = stuckJobs().get(job);
        assertNotNull(found);
        assertNull(found.queueName);
    }

    /** The jobs this test created that the check reports as stuck, by id. */
    private Map<Integer, StuckJob> stuckJobs() throws SQLException {
        Map<Integer, StuckJob> mine = new HashMap<>();
        for (StuckJob j : PeriodicTasks.findStuckJobs()) {
            if (jobIds.contains(j.jobId)) {
                mine.put(j.jobId, j);
            }
        }
        return mine;
    }

    private int insertQueue(String status) throws SQLException {
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement(
                 "INSERT INTO starexec.queues (name, status, global_access) VALUES (?, ?, true)"
                     + " RETURNING id")) {
            ps.setString(1, "stuck-job-test-" + UUID.randomUUID());
            ps.setString(2, status);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                int id = rs.getInt(1);
                queueIds.add(id);
                return id;
            }
        }
    }

    /** A job created the given interval ago, on the given queue (null for none). */
    private int insertJob(Integer queue, String age, int clockTimeout, boolean paused)
            throws SQLException {
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement(
                 "INSERT INTO starexec.jobs"
                     + " (user_id, name, queue_id, total_pairs, disk_size, created, clocktimeout,"
                     + " paused)"
                     + " VALUES (?, 'stuck-job-test', ?, 1, 0, now() - ?::interval, ?, ?)"
                     + " RETURNING id")) {
            ps.setInt(1, userId);
            if (queue == null) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setInt(2, queue);
            }
            ps.setString(3, age);
            ps.setInt(4, clockTimeout);
            ps.setBoolean(5, paused);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                int id = rs.getInt(1);
                jobIds.add(id);
                return id;
            }
        }
    }

    /** A pair that started and finished the given intervals ago (null for never). */
    private void insertPair(int job, StatusCode status, String startedAgo, String endedAgo)
            throws SQLException {
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement(
                 "INSERT INTO starexec.job_pairs"
                     + " (job_id, status_code, start_time, end_time, primary_jobpair_data)"
                     + " VALUES (?, ?, now() - ?::interval, now() - ?::interval, 1)")) {
            ps.setInt(1, job);
            ps.setInt(2, status.getVal());
            ps.setString(3, startedAgo);
            ps.setString(4, endedAgo);
            ps.executeUpdate();
        }
    }
}
