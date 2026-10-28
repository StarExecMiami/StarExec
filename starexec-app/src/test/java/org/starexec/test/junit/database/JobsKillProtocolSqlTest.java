package org.starexec.test.junit.database;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.starexec.backend.Backend;
import org.starexec.constants.R;
import org.starexec.data.database.Common;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.JobPairs;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

    @BeforeAll
    public static void requireDatabase() {
        DatabaseTestSupport.assumeDatabaseAvailable("JobsKillProtocolSqlTest");
        Common.initialize();
    }

    /**
     * The pool hands out connections it does not reset.
     *
     * <p>Tomcat JDBC applies {@code defaultAutoCommit} when a physical connection is
     * created, not when one is borrowed, and this pool is configured without
     * {@code rollbackOnReturn} (see Common.initialize). A connection returned with
     * {@code autoCommit=false} therefore arrives at the next borrower still in that
     * mode, carrying whatever transaction was left open on it.</p>
     *
     * <p>That is not a cosmetic detail here. {@code Jobs.kill(int, Connection)} runs
     * {@code KillJob} on the connection it is given and then borrows further
     * connections from the same pool for getEnqueuedPairs/getRunningPairs/
     * killPairConfirmed. Given an uncommitted connection it blocks its own helpers on
     * the rows it just locked, which was observed as the KillJob backend sitting
     * {@code idle in transaction} while two other backends waited behind it.</p>
     */
    private static void requireCleanPooledConnection(Connection con, String operation)
            throws SQLException {
        if (con.getAutoCommit()) {
            return;
        }
        // Make the connection usable again so cleanup can still run, then fail loudly.
        // Silently normalising it would hide the contamination this test exists to catch.
        try {
            con.rollback();
        } finally {
            con.setAutoCommit(true);
        }
        fail(operation + " borrowed a pooled connection with autoCommit=false: an earlier"
                + " caller returned it mid-transaction. It has been rolled back and restored"
                + " so cleanup can proceed, but the pool was contaminated.");
    }

    /** The state a fixture-owned connection must be in before it returns to the pool. */
    private static void assertConnectionReturnedClean(Connection con, String operation)
            throws SQLException {
        assertTrue(con.getAutoCommit(),
                operation + " must explicitly finish its transaction and restore"
                + " autoCommit before the connection goes back to the pool");
    }

    /**
     * Residue is the observable the defect always violated: before explicit ownership,
     * cleanup ran inside an inherited uncommitted transaction, so its DELETEs rolled
     * back when the connection died and one job with two pairs survived every run.
     *
     * <p>Secondary evidence only. Which physical connection cleanup borrows varies, so
     * this is not a reliable negative control on its own; the deterministic check is the
     * fixture-boundary assertion in {@link #createFixture()}.</p>
     */
    @AfterAll
    public static void fixtureLeavesNothingBehind() throws SQLException {
        // @AfterClass still runs when @BeforeClass skipped the class by assumption, and
        // the pool is not initialised in a no-database run.
        if (!DatabaseTestSupport.isDatabaseConfigured()) {
            return;
        }
        try (Connection con = Common.getConnection()) {
            assertEquals(0, selectInt(con, "SELECT count(*) FROM starexec.jobs"
                            + " WHERE name = 'kill-protocol-test'"),
                    "kill-protocol fixtures must not survive cleanup");
        }
    }

    /**
     * A setup failure must roll back and hand the connection back clean.
     *
     * <p>Guards the path that made restoring autoCommit in a bare finally unsafe: JDBC
     * commits an active transaction when autoCommit becomes true, so a fixture that
     * failed halfway would have been published rather than discarded.</p>
     */
    @Test
    public void aFailedFixtureSetupRollsBackAndRestoresTheConnection() throws SQLException {
        final String probe = "kill-protocol-rollback-probe";
        try (Connection con = Common.getConnection()) {
            requireCleanPooledConnection(con, "rollback probe");
            con.setAutoCommit(false);
            boolean transactionFinished = false;
            try {
                int userId = selectInt(con, "SELECT min(id) FROM starexec.users");
                exec(con, "INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size)"
                        + " VALUES (" + userId + ", '" + probe + "', 1, 0)");
                throw new IllegalStateException("induced failure after a fixture write");
            } catch (IllegalStateException induced) {
                con.rollback();
                transactionFinished = true;
            } finally {
                if (transactionFinished) {
                    con.setAutoCommit(true);
                }
            }

            assertConnectionReturnedClean(con, "rollback probe");
            assertEquals(0, selectInt(con, "SELECT count(*) FROM starexec.jobs WHERE name = '"
                            + probe + "'"),
                    "the partially built fixture must not have been committed");
        }
    }

    @BeforeEach
    public void createFixture() throws SQLException {
        originalBackend = R.BACKEND;
        try (Connection con = Common.getConnection()) {
            requireCleanPooledConnection(con, "createFixture");
            con.setAutoCommit(false);
            boolean transactionFinished = false;
            try {
                int userId = selectInt(con, "SELECT min(id) FROM starexec.users");
                jobId = selectInt(con,
                        "INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size) VALUES ("
                                + userId + ", 'kill-protocol-test', 2, 0) RETURNING id");
                runningPairId = insertPair(con, 771001, StatusCode.STATUS_RUNNING.getVal());
                enqueuedPairId = insertPair(con, 771002, StatusCode.STATUS_ENQUEUED.getVal());
                con.commit();
                transactionFinished = true;
            } catch (Throwable primary) {
                try {
                    con.rollback();
                    transactionFinished = true;
                } catch (SQLException rollbackFailure) {
                    primary.addSuppressed(rollbackFailure);
                }
                throw primary;
            } finally {
                // Only after an explicit commit or rollback. JDBC commits an active
                // transaction when autoCommit flips to true, so restoring it first would
                // publish a half-built fixture on the failure path.
                if (transactionFinished) {
                    con.setAutoCommit(true);
                }
            }
            assertConnectionReturnedClean(con, "createFixture");
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

    @AfterEach
    public void dropFixture() throws SQLException {
        R.BACKEND = originalBackend;
        if (jobId == 0) {
            return;
        }
        try (Connection con = Common.getConnection()) {
            requireCleanPooledConnection(con, "dropFixture");
            con.setAutoCommit(false);
            boolean transactionFinished = false;
            try {
                exec(con, "DELETE FROM starexec.jobpair_stage_data WHERE jobpair_id IN"
                        + " (SELECT id FROM starexec.job_pairs WHERE job_id = " + jobId + ")");
                exec(con, "DELETE FROM starexec.job_pairs WHERE job_id = " + jobId);
                exec(con, "DELETE FROM starexec.jobs WHERE id = " + jobId);
                con.commit();
                transactionFinished = true;
            } catch (Throwable primary) {
                try {
                    con.rollback();
                    transactionFinished = true;
                } catch (SQLException rollbackFailure) {
                    primary.addSuppressed(rollbackFailure);
                }
                throw primary;
            } finally {
                if (transactionFinished) {
                    con.setAutoCommit(true);
                }
            }
            assertConnectionReturnedClean(con, "dropFixture");
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

        assertTrue(invokeKill(jobId), "the kill must report success");

        assertTrue(isKilled(jobId), "the job must be flagged killed");
        assertEquals(StatusCode.STATUS_KILLED.getVal(),
                statusOf(runningPairId), "the running pair must be recorded killed");
        assertEquals(StatusCode.STATUS_KILLED.getVal(),
                statusOf(enqueuedPairId), "and so must the enqueued one");
    }

    @Test
    public void anUnprovenKillNeverClaimsThePairWasKilled() throws Exception {
        R.BACKEND = backendReturning(Backend.KillOutcome.UNPROVEN);

        assertFalse(invokeKill(jobId), "an incomplete kill must not report success");

        assertEquals(StatusCode.STATUS_RUNNING.getVal(),
                statusOf(runningPairId), "a pair whose execution may still run must keep its status");
        assertEquals(StatusCode.STATUS_ENQUEUED.getVal(), statusOf(enqueuedPairId));
    }

    @Test
    public void directJobDeletionPreservesRowsWhenExecutionStopIsUnproven() throws Exception {
        R.BACKEND = backendReturning(Backend.KillOutcome.UNPROVEN);

        assertFalse(Jobs.delete(jobId),
                "deletion must report that an active execution could not be retired");

        assertEquals(1, selectInt("SELECT count(*) FROM starexec.jobs WHERE id = " + jobId),
                "the job row must remain");
        assertEquals(0, selectInt("SELECT count(*) FROM starexec.jobs WHERE id = " + jobId + " AND deleted"),
                "the job must not be marked deleted");
        assertEquals(1, selectInt("SELECT count(*) FROM starexec.job_pairs WHERE id = " + runningPairId),
                "the running pair row must remain");
        assertEquals(StatusCode.STATUS_RUNNING.getVal(),
                statusOf(runningPairId), "the unproven running pair must retain its active status");
        assertEquals(1, selectInt("SELECT count(*) FROM starexec.job_pairs WHERE id = " + enqueuedPairId),
                "the enqueued pair row must remain");
        assertEquals(StatusCode.STATUS_ENQUEUED.getVal(),
                statusOf(enqueuedPairId), "the unproven enqueued pair must retain its active status");
    }

    // ------------------------------------------------------------------
    // refused deletions: retried, and never purged around the stop check
    // ------------------------------------------------------------------

    /**
     * The endpoints mark a job deleted at once and finish the deletion on a thread, after
     * telling the user it is done. A deletion refused because a pair was not confirmed
     * stopped was never tried again: the job stayed hidden with its pairs RUNNING.
     */
    @Test
    public void aRefusedDeletionIsCompletedOnceTheStopIsConfirmed() throws Exception {
        assertTrue(Jobs.setDeletedColumn(jobId), "what the endpoints do first");
        R.BACKEND = backendReturning(Backend.KillOutcome.UNPROVEN);
        assertFalse(Jobs.delete(jobId), "the first deletion is refused");
        assertEquals(2, pairCount());

        R.BACKEND = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);
        assertTrue(Jobs.retryRefusedDeletions() >= 1, "the retry must complete this deletion");

        assertEquals(0, pairCount(), "no pair of the deleted job may remain");
    }

    @Test
    public void theRetryLeavesADeletedJobAloneWhileItsStopIsUnproven() throws Exception {
        assertTrue(Jobs.setDeletedColumn(jobId));
        R.BACKEND = backendReturning(Backend.KillOutcome.UNPROVEN);

        Jobs.retryRefusedDeletions();

        assertEquals(2, pairCount(), "rows stay while an execution may still run");
        assertEquals(StatusCode.STATUS_RUNNING.getVal(), statusOf(runningPairId));
    }

    /** The retry is for deletions only: it must never kill a job nobody deleted. */
    @Test
    public void theRetryNeverTouchesAJobThatIsNotDeleted() throws Exception {
        Backend backend = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);
        R.BACKEND = backend;

        Jobs.retryRefusedDeletions();

        org.mockito.Mockito.verify(backend, org.mockito.Mockito.never()).killPairConfirmed(771001);
        org.mockito.Mockito.verify(backend, org.mockito.Mockito.never()).killPairConfirmed(771002);
        assertEquals(2, pairCount());
        assertEquals(StatusCode.STATUS_RUNNING.getVal(), statusOf(runningPairId));
    }

    /**
     * CLEAN_DATABASE purged deleted jobs with no stop check at all: a job whose deletion had
     * been refused lost its rows (and directory) while a pair might still be running.
     */
    @Test
    public void cleaningDeletedJobsLeavesOneWithPairsInFlightToTheRetry() throws Exception {
        assertTrue(Jobs.setDeletedColumn(jobId));

        assertTrue(Jobs.cleanOrphanedDeletedJobs());

        assertEquals(1, selectInt("SELECT count(*) FROM starexec.jobs WHERE id = " + jobId),
                "the job row must remain");
        assertEquals(2, pairCount(), "its in-flight pairs must remain");
    }

    /**
     * The restraint, pinned: a deleted job with nothing in flight is still purged. Deciding
     * through isJobComplete would have kept every deleted job forever, because
     * GetJobStatusDetail answers DELETED for all of them.
     */
    @Test
    public void cleaningDeletedJobsStillPurgesOneWithNothingInFlight() throws Exception {
        exec("UPDATE starexec.job_pairs SET status_code = " + StatusCode.STATUS_COMPLETE.getVal()
                + " WHERE job_id = " + jobId);
        assertTrue(Jobs.setDeletedColumn(jobId));

        assertTrue(Jobs.cleanOrphanedDeletedJobs());

        assertEquals(0, selectInt("SELECT count(*) FROM starexec.jobs WHERE id = " + jobId),
                "a deleted, orphaned job with nothing in flight is purged");
    }

    private int pairCount() throws SQLException {
        return selectInt("SELECT count(*) FROM starexec.job_pairs WHERE job_id = " + jobId);
    }

    @Test
    public void directDeletionStopsWhenTheDeletedMarkerCannotBeStored() throws Exception {
        R.BACKEND = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "");
        String functionName = "kill_protocol_reject_delete_" + suffix;
        String triggerName = functionName + "_trigger";

        try {
            try (Connection con = Common.getConnection();
                    java.sql.Statement statement = con.createStatement()) {
                statement.execute("CREATE FUNCTION starexec." + functionName
                        + "() RETURNS trigger AS $$ BEGIN"
                        + " RAISE EXCEPTION 'injected deleted-marker failure';"
                        + " END; $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER " + triggerName
                        + " BEFORE UPDATE OF deleted ON starexec.jobs"
                        + " FOR EACH ROW WHEN (NEW.deleted AND NOT OLD.deleted)"
                        + " EXECUTE FUNCTION starexec." + functionName + "()");
            }

            assertFalse(Jobs.delete(jobId),
                    "deletion must stop if the database could not record the deleted marker");
            assertEquals(0, selectInt("SELECT count(*) FROM starexec.jobs WHERE id = "
                            + jobId + " AND deleted"),
                    "the job remains available and undeleted");
            assertEquals(1, selectInt("SELECT count(*) FROM starexec.job_pairs WHERE id = "
                            + runningPairId),
                    "the running pair row must remain");
            assertEquals(1, selectInt("SELECT count(*) FROM starexec.job_pairs WHERE id = "
                            + enqueuedPairId),
                    "the enqueued pair row must remain");
        } finally {
            try (Connection con = Common.getConnection();
                    java.sql.Statement statement = con.createStatement()) {
                statement.execute("DROP TRIGGER IF EXISTS " + triggerName
                        + " ON starexec.jobs");
                statement.execute("DROP FUNCTION IF EXISTS starexec." + functionName + "()");
            }
        }
    }

    @Test
    public void confirmedBackendStopIsNotReportedWhenStatusPersistenceFails() throws Exception {
        R.BACKEND = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "");
        String functionName = "kill_protocol_reject_status_" + suffix;
        String triggerName = functionName + "_trigger";

        try {
            try (Connection con = Common.getConnection();
                    java.sql.Statement statement = con.createStatement()) {
                statement.execute("CREATE FUNCTION starexec." + functionName
                        + "() RETURNS trigger AS $$ BEGIN"
                        + " RAISE EXCEPTION 'injected pair-status failure';"
                        + " END; $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER " + triggerName
                        + " BEFORE UPDATE OF status_code ON starexec.job_pairs"
                        + " FOR EACH ROW WHEN (OLD.id = " + runningPairId
                        + " AND NEW.status_code = "
                        + StatusCode.STATUS_KILLED.getVal() + ")"
                        + " EXECUTE FUNCTION starexec." + functionName + "()");
            }

            assertFalse(JobPairs.killPairConfirmed(runningPairId, 771001),
                    "status persistence failure must not report a retired pair");
            assertEquals(StatusCode.STATUS_RUNNING.getVal(),
                    statusOf(runningPairId), "the failed write leaves the pair visibly in flight");
        } finally {
            try (Connection con = Common.getConnection();
                    java.sql.Statement statement = con.createStatement()) {
                statement.execute("DROP TRIGGER IF EXISTS " + triggerName
                        + " ON starexec.job_pairs");
                statement.execute("DROP FUNCTION IF EXISTS starexec." + functionName + "()");
            }
        }
    }

    // ------------------------------------------------------------------
    // Jobs.setPairsToPending
    // ------------------------------------------------------------------

    @Test
    public void resettingRunningPairsUsesTheirRealExecutionIds() throws Exception {
        RecordingBackend recorder = new RecordingBackend(Backend.KillOutcome.CONFIRMED_SAFE);
        R.BACKEND = recorder.backend;

        assertTrue(Jobs.setPairsToPending(jobId, StatusCode.STATUS_RUNNING.getVal()));

        assertTrue(recorder.asked.contains(771001),
                "the pair's own execution id must be used, never 0");
        assertFalse(recorder.asked.contains(0), "execution 0 must never be asked about");
        assertEquals(StatusCode.STATUS_PENDING_SUBMIT.getVal(), statusOf(runningPairId));
    }

    @Test
    public void anUnprovenExecutionIsNotResetAndTheOperationReportsIncomplete()
            throws Exception {
        R.BACKEND = backendReturning(Backend.KillOutcome.UNPROVEN);

        assertFalse(Jobs.setPairsToPending(jobId, StatusCode.STATUS_RUNNING.getVal()),
                "the caller must learn the operation did not complete");

        assertEquals(StatusCode.STATUS_RUNNING.getVal(),
                statusOf(runningPairId), "resetting it would let a replacement run beside the surviving execution");
    }

    @Test
    public void aPairWithNoExecutionIdIsWithheldRatherThanAssumedStopped() throws Exception {
        exec("UPDATE starexec.job_pairs SET sge_id = NULL WHERE id = " + runningPairId);
        RecordingBackend recorder = new RecordingBackend(Backend.KillOutcome.CONFIRMED_SAFE);
        R.BACKEND = recorder.backend;

        assertFalse(Jobs.setPairsToPending(jobId, StatusCode.STATUS_RUNNING.getVal()));

        assertFalse(recorder.asked.contains(0), "a NULL sge_id must not be read as execution 0");
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
            assertFalse(Jobs.pauseAll(), "an incomplete kill must be reported, not swallowed");
            assertEquals(StatusCode.STATUS_RUNNING.getVal(),
                    statusOf(runningPairId), "no pair may be made runnable again while an execution may survive");
            assertEquals(StatusCode.STATUS_ENQUEUED.getVal(), statusOf(enqueuedPairId));
        } finally {
            // pauseAll sets a system-wide flag; leave the scratch database as we found it.
            Jobs.resumeAll();
        }
    }

    /**
     * Every stop of the batch is requested before any is confirmed, so the confirmations'
     * waits overlap. Confirmed one at a time, a job whose pods hang in Terminating held its
     * caller for the confirmation wait times the number of pairs.
     */
    @Test
    public void aKillRequestsEveryStopBeforeConfirmingAny() throws Exception {
        Backend backend = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);
        R.BACKEND = backend;

        assertTrue(invokeKill(jobId), "the kill must report success");

        org.mockito.Mockito.verify(backend).requestStop(771001);
        org.mockito.Mockito.verify(backend).requestStop(771002);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(backend);
        order.verify(backend, org.mockito.Mockito.times(2))
            .requestStop(org.mockito.ArgumentMatchers.anyInt());
        order.verify(backend, org.mockito.Mockito.times(2))
            .killPairConfirmed(org.mockito.ArgumentMatchers.anyInt());
    }

    /** Pausing resets pairs through the same confirmation, so it requests the stop first too. */
    @Test
    public void pausingRequestsTheStopBeforeConfirmingIt() throws Exception {
        Backend backend = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);
        R.BACKEND = backend;

        assertTrue(Jobs.setPairsToPending(jobId, StatusCode.STATUS_RUNNING.getVal()));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(backend);
        order.verify(backend).requestStop(771001);
        order.verify(backend).killPairConfirmed(771001);
    }

    // ------------------------------------------------------------------
    // Jobs.pause
    // ------------------------------------------------------------------

    /**
     * KillJob's bug, in PauseJob: it RAISED when the job had no PENDING_SUBMIT pair. That
     * rolled back the paused flag, and Jobs.pause returned false before it stopped anything.
     * A job whose pairs were all dispatched, like this fixture's, could not be paused: the
     * user got a database error and the pairs ran on.
     */
    @Test
    public void pausingAJobWithNoPendingPairStopsAndPausesItsPairs() throws Exception {
        Backend backend = org.mockito.Mockito.mock(Backend.class);
        org.mockito.Mockito.when(backend.killPair(org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(true);
        R.BACKEND = backend;

        assertTrue(Jobs.pause(jobId), "the pause must report success");

        assertTrue(isPaused(jobId), "the job must be flagged paused");
        org.mockito.Mockito.verify(backend).killPair(771001);
        org.mockito.Mockito.verify(backend).killPair(771002);
        // Each stopped execution is retired from backend tracking. Otherwise LocalJobMonitor
        // reads the RUNNING that the killed run left in status.json and writes it over PAUSED.
        org.mockito.Mockito.verify(backend).clearPairTracking(runningPairId);
        org.mockito.Mockito.verify(backend).clearPairTracking(enqueuedPairId);
        int paused = StatusCode.STATUS_PAUSED.getVal();
        assertEquals(paused, statusOf(runningPairId), "the running pair must be paused");
        assertEquals(paused, stageStatusOf(runningPairId), "and its stage");
        assertEquals(paused, statusOf(enqueuedPairId), "the enqueued pair must be paused");
        assertEquals(paused, stageStatusOf(enqueuedPairId), "and its stage");
    }

    /** A pair whose stop was not confirmed may still be running, so it is not marked paused. */
    @Test
    public void anUnconfirmedStopLeavesTheRunningPairRunning() throws Exception {
        // An unstubbed mock answers false: no stop is confirmed.
        Backend backend = org.mockito.Mockito.mock(Backend.class);
        R.BACKEND = backend;

        Jobs.pause(jobId);

        assertTrue(isPaused(jobId), "the job is flagged paused, so nothing new is dispatched");
        assertEquals(StatusCode.STATUS_RUNNING.getVal(),
                statusOf(runningPairId), "a pair that may still be running must keep its status");
        assertEquals(StatusCode.STATUS_PAUSED.getVal(),
                statusOf(enqueuedPairId), "an enqueued pair is paused even so");
        // Nothing was stopped, so nothing is retired: an execution that runs on must still
        // have its result recorded.
        org.mockito.Mockito.verify(backend, org.mockito.Mockito.never())
                .clearPairTracking(org.mockito.ArgumentMatchers.anyInt());
    }

    /** PauseJob itself pauses a pending pair, which has no execution to stop. */
    @Test
    public void aPendingPairIsPausedWithoutAStopRequest() throws Exception {
        int pending = StatusCode.STATUS_PENDING_SUBMIT.getVal();
        exec("UPDATE starexec.job_pairs SET sge_id = NULL, status_code = " + pending
                + " WHERE id = " + enqueuedPairId);
        exec("UPDATE starexec.jobpair_stage_data SET status_code = " + pending
                + " WHERE jobpair_id = " + enqueuedPairId);
        Backend backend = org.mockito.Mockito.mock(Backend.class);
        org.mockito.Mockito.when(backend.killPair(org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(true);
        R.BACKEND = backend;

        assertTrue(Jobs.pause(jobId), "the pause must report success");

        assertEquals(StatusCode.STATUS_PAUSED.getVal(),
                statusOf(enqueuedPairId), "the pending pair must be paused");
        org.mockito.Mockito.verify(backend).killPair(771001);
        org.mockito.Mockito.verify(backend, org.mockito.Mockito.times(1))
                .killPair(org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.verify(backend).clearPairTracking(runningPairId);
        org.mockito.Mockito.verify(backend, org.mockito.Mockito.never())
                .clearPairTracking(enqueuedPairId);
    }

    /**
     * Tracking is retired before PAUSED is written. In the other order, a monitor poll that
     * lands between the two writes RUNNING over PAUSED, and nothing writes PAUSED again.
     */
    @Test
    public void trackingIsRetiredBeforeThePairIsMarkedPaused() throws Exception {
        Backend backend = org.mockito.Mockito.mock(Backend.class);
        org.mockito.Mockito.when(backend.killPair(org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(true);
        java.util.Map<Integer, Integer> statusWhenRetired = new java.util.HashMap<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            int pairId = invocation.getArgument(0);
            statusWhenRetired.put(pairId, statusOf(pairId));
            return null;
        }).when(backend).clearPairTracking(org.mockito.ArgumentMatchers.anyInt());
        R.BACKEND = backend;

        assertTrue(Jobs.pause(jobId), "the pause must report success");

        assertEquals(Integer.valueOf(StatusCode.STATUS_RUNNING.getVal()),
                statusWhenRetired.get(runningPairId),
                "tracking must be retired while the pair still reads RUNNING");
        assertEquals(StatusCode.STATUS_PAUSED.getVal(), statusOf(runningPairId));
    }

    /**
     * A stopped pair is paused even when its tracking cannot be retired. Left RUNNING, it
     * would have no process behind it and nothing would ever complete it.
     */
    @Test
    public void aStoppedPairIsPausedEvenWhenItsTrackingCannotBeRetired() throws Exception {
        Backend backend = org.mockito.Mockito.mock(Backend.class);
        org.mockito.Mockito.when(backend.killPair(org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("monitor unavailable"))
                .when(backend).clearPairTracking(org.mockito.ArgumentMatchers.anyInt());
        R.BACKEND = backend;

        assertTrue(Jobs.pause(jobId), "the pause must report success");

        int paused = StatusCode.STATUS_PAUSED.getVal();
        assertEquals(paused, statusOf(runningPairId), "the running pair must be paused");
        assertEquals(paused, statusOf(enqueuedPairId), "the enqueued pair must be paused");
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
            assertTrue(con.getAutoCommit(), "Jobs.kill writes on the connection it is given and then borrows more"
                    + " from the same pool; handing it one mid-transaction makes it block on"
                    + " its own helpers");
            return (Boolean) m.invoke(null, id, con);
        }
    }

    private boolean isKilled(int id) throws SQLException {
        return selectInt("SELECT CASE WHEN killed THEN 1 ELSE 0 END FROM starexec.jobs WHERE id = "
                + id) == 1;
    }

    private boolean isPaused(int id) throws SQLException {
        return selectInt("SELECT CASE WHEN paused THEN 1 ELSE 0 END FROM starexec.jobs WHERE id = "
                + id) == 1;
    }

    private int statusOf(int pairId) throws SQLException {
        return selectInt("SELECT status_code FROM starexec.job_pairs WHERE id = " + pairId);
    }

    private int stageStatusOf(int pairId) throws SQLException {
        return selectInt("SELECT status_code FROM starexec.jobpair_stage_data WHERE jobpair_id = "
                + pairId);
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
