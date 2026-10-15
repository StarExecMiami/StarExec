package org.starexec.test.junit.database;

import static org.junit.Assert.assertEquals;
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
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

/**
 * A rerun makes its job incomplete again, through every rerun entry point.
 *
 * <p>{@code jobs.completed} is stamped once, COALESCE-guarded, by the routines that see a
 * job's last pair finish, and nothing cleared it. A rerun job kept its first completion time
 * while its pairs ran again, and kept it after they finished: job 5 on Quokka showed
 * "completed 2026-10-10" for the whole of its 2026-10-15 rerun. {@code RerunJobPairsBatchCore}
 * now clears it, so the rerun's last pair stamps it afresh.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point {@code STAREXEC_DB_URL} /
 * {@code STAREXEC_DB_USER} / {@code STAREXEC_DB_PASSWORD} at a <strong>disposable</strong>
 * database: these tests insert and delete rows.
 */
public class RerunClearsJobCompletionSqlTest extends Common {

    private static final Timestamp FIRST_COMPLETION = Timestamp.valueOf("2026-01-01 00:00:00");
    private static final int EXEC_A = 515151;
    private static final int EXEC_B = 515152;
    private static final int EXEC_C = 515153;

    private int jobId;
    private int otherJobId;
    private int pairA;
    private int pairB;
    private int pairC;

    @BeforeClass
    public static void requireDatabase() {
        DatabaseTestSupport.assumeDatabaseAvailable("RerunClearsJobCompletionSqlTest");
        Common.initialize();
    }

    /** Two completed jobs: one with pairs A and B, the other with pair C, all finished. */
    @Before
    public void createCompletedJobs() throws SQLException {
        try (Connection con = Common.getConnection()) {
            int userId = queryInt(con, "SELECT min(id) FROM starexec.users");
            jobId = insertCompletedJob(con, userId, "rerun-completion-test");
            otherJobId = insertCompletedJob(con, userId, "rerun-completion-test-other");
            pairA = insertFinishedPair(con, jobId, EXEC_A);
            pairB = insertFinishedPair(con, jobId, EXEC_B);
            pairC = insertFinishedPair(con, otherJobId, EXEC_C);
        }
        assertEquals(FIRST_COMPLETION, completed(jobId));
    }

    @After
    public void dropFixture() throws SQLException {
        try (Connection con = Common.getConnection()) {
            for (int job : new int[] {jobId, otherJobId}) {
                if (job == 0) {
                    continue;
                }
                // pairs_rerun, job_pair_attempts, job_pair_completion and jobpair_stage_data
                // all cascade from job_pairs.
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
        }
        jobId = 0;
        otherJobId = 0;
    }

    @Test
    public void aCheckedManualRerunMakesTheJobIncompleteAgain() throws SQLException {
        assertEquals(1, callInt("SELECT starexec.RerunJobPairsBatchChecked(ARRAY[?], ARRAY[?])",
            pairA, EXEC_A));
        assertNull("its pair runs again, so the job has not completed", completed(jobId));
        assertEquals("another job keeps its completion", FIRST_COMPLETION, completed(otherJobId));
    }

    @Test
    public void anUncheckedManualRerunMakesTheJobIncompleteAgain() throws SQLException {
        assertEquals(1, callInt("SELECT starexec.RerunJobPairsBatch(ARRAY[?])", pairA));
        assertNull(completed(jobId));
    }

    @Test
    public void theAutomaticRerunMakesTheJobIncompleteAgain() throws SQLException {
        setPairStatus(pairA, StatusCode.ERROR_RUNSCRIPT.getVal());
        assertEquals("UPDATED", callText("SELECT starexec.RerunJobPairAutomatic(?, ?)",
            pairA, StatusCode.ERROR_RUNSCRIPT.getVal()));
        assertNull(completed(jobId));
    }

    /** The point of clearing it: the rerun's own last pair stamps the time it really ended. */
    @Test
    public void theRerunsLastPairStampsTheCompletionAfresh() throws SQLException {
        assertEquals(1, callInt("SELECT starexec.RerunJobPairsBatchChecked(ARRAY[?], ARRAY[?])",
            pairA, EXEC_A));
        assertNull(completed(jobId));

        // Finished through a production routine that stamps jobs.completed. B is still done,
        // so A is the job's last unfinished pair.
        callVoid("SELECT starexec.SetBrokenPairStatus(?, ?, ?)", pairA,
            StatusCode.STATUS_PENDING_SUBMIT.getVal(), StatusCode.ERROR_SUBMIT_FAIL.getVal());

        Timestamp restamped = completed(jobId);
        assertNotNull("the job is complete again", restamped);
        assertTrue("and records when the rerun finished, not its first completion",
            restamped.after(FIRST_COMPLETION));
    }

    /** Only the last of the rerun's pairs to finish completes the job. */
    @Test
    public void theFirstRerunPairToFinishDoesNotCompleteTheJob() throws SQLException {
        assertEquals(2, callInt(
            "SELECT starexec.RerunJobPairsBatchChecked(ARRAY[?, ?], ARRAY[?, ?])",
            pairA, pairB, EXEC_A, EXEC_B));

        callVoid("SELECT starexec.SetBrokenPairStatus(?, ?, ?)", pairA,
            StatusCode.STATUS_PENDING_SUBMIT.getVal(), StatusCode.ERROR_SUBMIT_FAIL.getVal());
        assertNull("B is still pending", completed(jobId));

        callVoid("SELECT starexec.SetBrokenPairStatus(?, ?, ?)", pairB,
            StatusCode.STATUS_PENDING_SUBMIT.getVal(), StatusCode.ERROR_SUBMIT_FAIL.getVal());
        assertNotNull("the last one completes it", completed(jobId));
    }

    @Test
    public void aJobThatNeverCompletedStaysIncomplete() throws SQLException {
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement(
                 "UPDATE starexec.jobs SET completed = NULL WHERE id = ?")) {
            ps.setInt(1, jobId);
            ps.executeUpdate();
        }
        assertEquals(1, callInt("SELECT starexec.RerunJobPairsBatchChecked(ARRAY[?], ARRAY[?])",
            pairA, EXEC_A));
        assertNull(completed(jobId));
        assertEquals(FIRST_COMPLETION, completed(otherJobId));
    }

    private static int insertCompletedJob(Connection con, int userId, String name)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size, completed)"
                    + " VALUES (?, ?, 2, 0, ?) RETURNING id")) {
            ps.setInt(1, userId);
            ps.setString(2, name);
            ps.setTimestamp(3, FIRST_COMPLETION);
            return singleInt(ps);
        }
    }

    private static int insertFinishedPair(Connection con, int job, int execId)
            throws SQLException {
        int pair;
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO starexec.job_pairs"
                    + " (job_id, sge_id, status_code, start_time, end_time, primary_jobpair_data)"
                    + " VALUES (?, ?, ?, ?, ?, 1) RETURNING id")) {
            ps.setInt(1, job);
            ps.setInt(2, execId);
            ps.setInt(3, StatusCode.STATUS_COMPLETE.getVal());
            ps.setTimestamp(4, FIRST_COMPLETION);
            ps.setTimestamp(5, FIRST_COMPLETION);
            pair = singleInt(ps);
        }
        // disk_size 0: the reset then reclaims nothing from the shared fixture user.
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO starexec.jobpair_stage_data"
                    + " (jobpair_id, stage_number, status_code, disk_size) VALUES (?, 1, ?, 0)")) {
            ps.setInt(1, pair);
            ps.setInt(2, StatusCode.STATUS_COMPLETE.getVal());
            ps.executeUpdate();
        }
        return pair;
    }

    private void setPairStatus(int pair, int status) throws SQLException {
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement(
                 "UPDATE starexec.job_pairs SET status_code = ? WHERE id = ?")) {
            ps.setInt(1, status);
            ps.setInt(2, pair);
            ps.executeUpdate();
        }
    }

    private Timestamp completed(int job) throws SQLException {
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement(
                 "SELECT completed FROM starexec.jobs WHERE id = ?")) {
            ps.setInt(1, job);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getTimestamp(1);
            }
        }
    }

    private static int callInt(String sql, int... args) throws SQLException {
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setInt(i + 1, args[i]);
            }
            return singleInt(ps);
        }
    }

    private static String callText(String sql, int... args) throws SQLException {
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setInt(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private static void callVoid(String sql, int... args) throws SQLException {
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setInt(i + 1, args[i]);
            }
            ps.executeQuery().close();
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
