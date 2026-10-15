package org.starexec.test.junit.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.data.database.Common;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.JobPairs.NeverStartedRetryResult;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

/**
 * {@code starexec.RetryNeverStartedPair} against the real SQL, through the production entry
 * point {@link JobPairs#retryNeverStartedPair}.
 *
 * <p>The pair of a Kubernetes pod that never started is queued again once -- spending the
 * same {@code pairs_rerun} allowance RERUN_FAILED_PAIRS spends -- and filed
 * ERROR_SUBMIT_FAIL when that allowance is gone. Every write is fenced on the pair still
 * being ENQUEUED on the execution and attempt the caller's proof was about.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point {@code STAREXEC_DB_URL} /
 * {@code STAREXEC_DB_USER} / {@code STAREXEC_DB_PASSWORD} at a <strong>disposable</strong>
 * database: these tests insert and delete rows.
 */
public class NeverStartedRetrySqlTest extends Common {

    private static final int EXEC_ID = 424242;

    private int jobId;
    private int pairId;

    @BeforeClass
    public static void requireDatabase() {
        DatabaseTestSupport.assumeDatabaseAvailable("NeverStartedRetrySqlTest");
        Common.initialize();
    }

    @Before
    public void createEnqueuedPair() throws SQLException {
        try (Connection con = Common.getConnection()) {
            int userId = queryInt(con, "SELECT min(id) FROM starexec.users");
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size)"
                        + " VALUES (?, 'never-started-retry-test', 1, 0) RETURNING id")) {
                ps.setInt(1, userId);
                jobId = singleInt(ps);
            }
            // As JobManager leaves a submitted pair: ENQUEUED on its execution, not started.
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO starexec.job_pairs"
                        + " (job_id, sge_id, status_code, primary_jobpair_data)"
                        + " VALUES (?, ?, ?, 1) RETURNING id")) {
                ps.setInt(1, jobId);
                ps.setInt(2, EXEC_ID);
                ps.setInt(3, StatusCode.STATUS_ENQUEUED.getVal());
                pairId = singleInt(ps);
            }
            // disk_size 0: nothing ran, and the reset must then reclaim nothing from the
            // fixture user, whose row other suites share.
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO starexec.jobpair_stage_data"
                        + " (jobpair_id, stage_number, status_code, disk_size) VALUES (?, 1, ?, 0)")) {
                ps.setInt(1, pairId);
                ps.setInt(2, StatusCode.STATUS_ENQUEUED.getVal());
                ps.executeUpdate();
            }
        }
    }

    @After
    public void dropFixture() throws SQLException {
        if (jobId == 0) {
            return;
        }
        try (Connection con = Common.getConnection()) {
            // pairs_rerun, job_pair_attempts, job_pair_completion and jobpair_stage_data
            // all cascade from job_pairs.
            try (PreparedStatement ps = con.prepareStatement(
                    "DELETE FROM starexec.job_pairs WHERE job_id = ?")) {
                ps.setInt(1, jobId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "DELETE FROM starexec.jobs WHERE id = ?")) {
                ps.setInt(1, jobId);
                ps.executeUpdate();
            }
        }
        jobId = 0;
    }

    @Test
    public void aPairWhosePodNeverStartedIsQueuedAgainAndSpendsItsAllowance() throws SQLException {
        assertEquals(NeverStartedRetryResult.REQUEUED,
            JobPairs.retryNeverStartedPair(pairId, EXEC_ID, null));

        assertEquals(StatusCode.STATUS_PENDING_SUBMIT.getVal(), pairStatus());
        assertEquals(StatusCode.STATUS_PENDING_SUBMIT.getVal(), stageStatus());
        assertNull("nothing ran, so there is no end time", endTime());
        assertTrue("the automatic allowance is spent", allowanceSpent());
        assertEquals("the reset starts a new attempt", 2, currentAttempt());
        assertFalse("and nothing is completed", completionRecorded());
    }

    @Test
    public void theAllowanceIsTheOneTheAutomaticRerunSpends() throws SQLException {
        assertEquals(NeverStartedRetryResult.REQUEUED,
            JobPairs.retryNeverStartedPair(pairId, EXEC_ID, null));

        // Had it run and failed this time, RERUN_FAILED_PAIRS would find nothing to spend.
        setPairStatus(StatusCode.ERROR_RUNSCRIPT.getVal(), EXEC_ID + 1);
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement(
                 "SELECT starexec.RerunJobPairAutomatic(?, ?)")) {
            ps.setInt(1, pairId);
            ps.setInt(2, StatusCode.ERROR_RUNSCRIPT.getVal());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("ALREADY_CONSUMED", rs.getString(1));
            }
        }
    }

    @Test
    public void aSecondPodThatNeverStartsFilesTheSubmitFailure() throws SQLException {
        assertEquals(NeverStartedRetryResult.REQUEUED,
            JobPairs.retryNeverStartedPair(pairId, EXEC_ID, 1));
        // Resubmitted on a new execution, for the attempt the reset started.
        setPairStatus(StatusCode.STATUS_ENQUEUED.getVal(), EXEC_ID + 1);

        assertEquals(NeverStartedRetryResult.FILED,
            JobPairs.retryNeverStartedPair(pairId, EXEC_ID + 1, 2));

        assertEquals(StatusCode.ERROR_SUBMIT_FAIL.getVal(), pairStatus());
        assertEquals(StatusCode.ERROR_SUBMIT_FAIL.getVal(), stageStatus());
        assertNotNull("a terminal pair carries an end time", endTime());
        assertTrue("and a completion row", completionRecorded());
    }

    @Test
    public void aPairAnotherExecutionOwnsIsNotTouched() throws SQLException {
        assertEquals(NeverStartedRetryResult.STALE,
            JobPairs.retryNeverStartedPair(pairId, EXEC_ID + 1, null));
        assertUntouched();
    }

    @Test
    public void aPairThatIsNoLongerEnqueuedIsNotTouched() throws SQLException {
        // Killed, paused, rerun by hand, or reported running: none of these is this
        // execution's to settle.
        for (StatusCode moved : new StatusCode[] {
                StatusCode.STATUS_PENDING_SUBMIT, StatusCode.STATUS_RUNNING,
                StatusCode.STATUS_KILLED, StatusCode.STATUS_PAUSED}) {
            setPairStatus(moved.getVal(), EXEC_ID);
            assertEquals(moved.name(), NeverStartedRetryResult.STALE,
                JobPairs.retryNeverStartedPair(pairId, EXEC_ID, null));
            assertEquals(moved.name(), moved.getVal(), pairStatus());
            assertFalse(moved.name(), allowanceSpent());
        }
    }

    @Test
    public void anOlderAttemptIsNotTouched() throws SQLException {
        // The pair has been rerun once since: it is on attempt 2. V0105's trigger gave it
        // its attempts row when it was inserted.
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement(
                 "UPDATE starexec.job_pair_attempts SET current_attempt_no = 2 WHERE pair_id = ?")) {
            ps.setInt(1, pairId);
            assertEquals(1, ps.executeUpdate());
        }

        assertEquals(NeverStartedRetryResult.STALE,
            JobPairs.retryNeverStartedPair(pairId, EXEC_ID, 1));
        assertUntouched();

        assertEquals(NeverStartedRetryResult.REQUEUED,
            JobPairs.retryNeverStartedPair(pairId, EXEC_ID, 2));
        assertEquals(3, currentAttempt());
    }

    /**
     * Every pair gets an attempts row from V0105's trigger, so this only covers one that
     * lost it: it is on attempt 1, as EnforcePairAttempt reads it, not unfenced.
     */
    @Test
    public void aPairWithNoAttemptRowIsOnItsFirstAttempt() throws SQLException {
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement(
                 "DELETE FROM starexec.job_pair_attempts WHERE pair_id = ?")) {
            ps.setInt(1, pairId);
            assertEquals(1, ps.executeUpdate());
        }
        assertEquals(NeverStartedRetryResult.STALE,
            JobPairs.retryNeverStartedPair(pairId, EXEC_ID, 2));
        assertUntouched();
        assertEquals(NeverStartedRetryResult.REQUEUED,
            JobPairs.retryNeverStartedPair(pairId, EXEC_ID, 1));
    }

    @Test
    public void repeatingTheCallAfterItCommittedChangesNothing() throws SQLException {
        // What the backend does when it cannot tell whether its first call committed.
        assertEquals(NeverStartedRetryResult.REQUEUED,
            JobPairs.retryNeverStartedPair(pairId, EXEC_ID, null));
        assertEquals(NeverStartedRetryResult.STALE,
            JobPairs.retryNeverStartedPair(pairId, EXEC_ID, null));
        assertEquals(StatusCode.STATUS_PENDING_SUBMIT.getVal(), pairStatus());
        assertEquals(2, currentAttempt());
    }

    @Test
    public void aDeletedPairIsStale() throws SQLException {
        assertEquals(NeverStartedRetryResult.STALE,
            JobPairs.retryNeverStartedPair(Integer.MAX_VALUE, EXEC_ID, null));
    }

    private void assertUntouched() throws SQLException {
        assertEquals(StatusCode.STATUS_ENQUEUED.getVal(), pairStatus());
        assertEquals(StatusCode.STATUS_ENQUEUED.getVal(), stageStatus());
        assertFalse(allowanceSpent());
        assertFalse(completionRecorded());
    }

    private void setPairStatus(int status, int execId) throws SQLException {
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement(
                 "UPDATE starexec.job_pairs SET status_code = ?, sge_id = ? WHERE id = ?")) {
            ps.setInt(1, status);
            ps.setInt(2, execId);
            ps.setInt(3, pairId);
            ps.executeUpdate();
        }
    }

    private int pairStatus() throws SQLException {
        return pairInt("SELECT status_code FROM starexec.job_pairs WHERE id = ?");
    }

    private int stageStatus() throws SQLException {
        return pairInt("SELECT status_code FROM starexec.jobpair_stage_data WHERE jobpair_id = ?");
    }

    private int currentAttempt() throws SQLException {
        return pairInt(
            "SELECT COALESCE((SELECT current_attempt_no FROM starexec.job_pair_attempts"
                + " WHERE pair_id = ?), 1)");
    }

    private boolean allowanceSpent() throws SQLException {
        return pairInt("SELECT count(*) FROM starexec.pairs_rerun WHERE pair_id = ?") > 0;
    }

    private boolean completionRecorded() throws SQLException {
        return pairInt("SELECT count(*) FROM starexec.job_pair_completion WHERE pair_id = ?") > 0;
    }

    private Timestamp endTime() throws SQLException {
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement(
                 "SELECT end_time FROM starexec.job_pairs WHERE id = ?")) {
            ps.setInt(1, pairId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getTimestamp(1);
            }
        }
    }

    private int pairInt(String sql) throws SQLException {
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, pairId);
            return singleInt(ps);
        }
    }

    private static int queryInt(Connection con, String sql) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            return singleInt(ps);
        }
    }

    private static int singleInt(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
