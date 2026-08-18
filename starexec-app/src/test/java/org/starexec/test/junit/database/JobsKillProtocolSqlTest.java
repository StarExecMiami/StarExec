package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.backend.Backend;
import org.starexec.constants.R;
import org.starexec.data.database.Common;
import org.starexec.data.database.Jobs;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Caller-level tests for the administrative kill and reset paths, against the real SQL.
 *
 * <p>These paths all shared one defect: they issued a kill, discarded its answer, and then
 * wrote state that claimed the execution had stopped. On Kubernetes a delete does not
 * synchronously stop a pod, so the claim could be false — and the pair would read as
 * finished or become dispatchable while its pod was still running on a node.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*}
 * variables at a <strong>disposable</strong> database.
 */
public class JobsKillProtocolSqlTest extends Common {

    private int jobId;
    private int runningPairId;
    private int enqueuedPairId;
    private Backend originalBackend;

    @BeforeClass
    public static void requireDatabase() {
        DatabaseTestSupport.assumeDatabaseAvailable("JobsKillProtocolSqlTest");
        Common.initialize();
    }

    @Before
    public void createFixture() throws SQLException {
        originalBackend = R.BACKEND;
        try (Connection con = Common.getConnection()) {
            con.setAutoCommit(false);
            try {
                int userId = selectInt(con, "SELECT min(id) FROM starexec.users");
                jobId = selectInt(con,
                        "INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size) VALUES ("
                                + userId + ", 'kill-protocol-test', 2, 0) RETURNING id");
                runningPairId = insertPair(con, 771001, StatusCode.STATUS_RUNNING.getVal());
                enqueuedPairId = insertPair(con, 771002, StatusCode.STATUS_ENQUEUED.getVal());
                con.commit();
            } catch (SQLException e) {
                con.rollback();
                throw e;
            }
        }
    }

    private int insertPair(Connection con, int execId, int status) throws SQLException {
        int pairId = selectInt(con,
                "INSERT INTO starexec.job_pairs (job_id, sge_id, status_code, primary_jobpair_data)"
                        + " VALUES (" + jobId + ", " + execId + ", " + status + ", 1) RETURNING id");
        exec(con, "INSERT INTO starexec.jobpair_stage_data"
                + " (jobpair_id, stage_number, status_code, disk_size) VALUES ("
                + pairId + ", 1, " + status + ", 4096)");
        return pairId;
    }

    @After
    public void dropFixture() throws SQLException {
        R.BACKEND = originalBackend;
        try (Connection con = Common.getConnection()) {
            exec(con, "DELETE FROM starexec.job_pairs WHERE job_id = " + jobId);
            exec(con, "DELETE FROM starexec.jobs WHERE id = " + jobId);
        }
    }

    // ------------------------------------------------------------------
    // Jobs.kill
    // ------------------------------------------------------------------

    /**
     * The bug: {@code KillJob} matched only PENDING_SUBMIT and PAUSED pairs and then RAISED
     * when none existed, which rolled back the killed flag — so killing a job whose pairs
     * were all running was a complete no-op, and the Java loop that could have stopped them
     * never ran because the DAO had already failed.
     */
    @Test
    public void killingAnAllRunningJobStopsItsExecutions() throws Exception {
        R.BACKEND = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);

        assertTrue("the kill must report success", invokeKill(jobId));

        assertTrue("the job must be flagged killed", isKilled(jobId));
        assertEquals("the running pair must be recorded killed",
                StatusCode.STATUS_KILLED.getVal(), statusOf(runningPairId));
        assertEquals("and so must the enqueued one",
                StatusCode.STATUS_KILLED.getVal(), statusOf(enqueuedPairId));
    }

    @Test
    public void anUnprovenKillNeverClaimsThePairWasKilled() throws Exception {
        R.BACKEND = backendReturning(Backend.KillOutcome.UNPROVEN);

        assertFalse("an incomplete kill must not report success", invokeKill(jobId));

        assertEquals("a pair whose execution may still run must keep its status",
                StatusCode.STATUS_RUNNING.getVal(), statusOf(runningPairId));
        assertEquals(StatusCode.STATUS_ENQUEUED.getVal(), statusOf(enqueuedPairId));
    }

    // ------------------------------------------------------------------
    // Jobs.setPairsToPending
    // ------------------------------------------------------------------

    @Test
    public void resettingRunningPairsUsesTheirRealExecutionIds() throws Exception {
        RecordingBackend recorder = new RecordingBackend(Backend.KillOutcome.CONFIRMED_SAFE);
        R.BACKEND = recorder.backend;

        assertTrue(Jobs.setPairsToPending(jobId, StatusCode.STATUS_RUNNING.getVal()));

        assertTrue("the pair's own execution id must be used, never 0",
                recorder.asked.contains(771001));
        assertFalse("execution 0 must never be asked about", recorder.asked.contains(0));
        assertEquals(StatusCode.STATUS_PENDING_SUBMIT.getVal(), statusOf(runningPairId));
    }

    @Test
    public void anUnprovenExecutionIsNotResetAndTheOperationReportsIncomplete()
            throws Exception {
        R.BACKEND = backendReturning(Backend.KillOutcome.UNPROVEN);

        assertFalse("the caller must learn the operation did not complete",
                Jobs.setPairsToPending(jobId, StatusCode.STATUS_RUNNING.getVal()));

        assertEquals("resetting it would let a replacement run beside the surviving execution",
                StatusCode.STATUS_RUNNING.getVal(), statusOf(runningPairId));
    }

    @Test
    public void aPairWithNoExecutionIdIsWithheldRatherThanAssumedStopped() throws Exception {
        exec("UPDATE starexec.job_pairs SET sge_id = NULL WHERE id = " + runningPairId);
        RecordingBackend recorder = new RecordingBackend(Backend.KillOutcome.CONFIRMED_SAFE);
        R.BACKEND = recorder.backend;

        assertFalse(Jobs.setPairsToPending(jobId, StatusCode.STATUS_RUNNING.getVal()));

        assertFalse("a NULL sge_id must not be read as execution 0", recorder.asked.contains(0));
        assertEquals(StatusCode.STATUS_RUNNING.getVal(), statusOf(runningPairId));
    }

    // ------------------------------------------------------------------
    // Jobs.pauseAll
    // ------------------------------------------------------------------

    /**
     * {@code pauseAll} discarded {@code killAll}'s answer and then rewrote every enqueued and
     * running pair to PENDING_SUBMIT, which makes them dispatchable again. Doing that while an
     * execution survives puts a replacement on a node the original has not vacated.
     */
    @Test
    public void pauseAllWithdrawsTheResetWhenAnExecutionCannotBeConfirmedStopped()
            throws Exception {
        Backend backend = org.mockito.Mockito.mock(Backend.class);
        org.mockito.Mockito.when(backend.killAll()).thenReturn(false);
        R.BACKEND = backend;

        try {
            assertFalse("an incomplete kill must be reported, not swallowed", Jobs.pauseAll());
            assertEquals("no pair may be made runnable again while an execution may survive",
                    StatusCode.STATUS_RUNNING.getVal(), statusOf(runningPairId));
            assertEquals(StatusCode.STATUS_ENQUEUED.getVal(), statusOf(enqueuedPairId));
        } finally {
            // pauseAll sets a system-wide flag; leave the scratch database as we found it.
            Jobs.resumeAll();
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** A mock backend that records every execution id it was asked about. */
    private static final class RecordingBackend {
        private final java.util.Set<Integer> asked = new java.util.HashSet<>();
        private final Backend backend;

        private RecordingBackend(Backend.KillOutcome outcome) {
            backend = org.mockito.Mockito.mock(Backend.class);
            org.mockito.Mockito
                    .when(backend.killPairConfirmed(org.mockito.ArgumentMatchers.anyInt()))
                    .thenAnswer(invocation -> {
                        asked.add(invocation.getArgument(0));
                        return outcome;
                    });
        }
    }

    private static Backend backendReturning(Backend.KillOutcome outcome) {
        Backend backend = org.mockito.Mockito.mock(Backend.class);
        org.mockito.Mockito
                .when(backend.killPairConfirmed(org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(outcome);
        return backend;
    }

    private boolean invokeKill(int id) throws Exception {
        java.lang.reflect.Method m =
                Jobs.class.getDeclaredMethod("kill", int.class, Connection.class);
        m.setAccessible(true);
        try (Connection con = Common.getConnection()) {
            return (Boolean) m.invoke(null, id, con);
        }
    }

    private boolean isKilled(int id) throws SQLException {
        return selectInt("SELECT CASE WHEN killed THEN 1 ELSE 0 END FROM starexec.jobs WHERE id = "
                + id) == 1;
    }

    private int statusOf(int pairId) throws SQLException {
        return selectInt("SELECT status_code FROM starexec.job_pairs WHERE id = " + pairId);
    }

    private int selectInt(String sql) throws SQLException {
        try (Connection con = Common.getConnection()) {
            return selectInt(con, sql);
        }
    }

    private static int selectInt(Connection con, String sql) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("Query returned no rows: " + sql);
            }
            return rs.getInt(1);
        }
    }

    private void exec(String sql) throws SQLException {
        try (Connection con = Common.getConnection()) {
            exec(con, sql);
        }
    }

    private static void exec(Connection con, String sql) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
