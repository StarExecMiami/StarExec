package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.data.database.Common;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.JobPairs.StaleSubmissionOutcome;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link JobPairs#settleStaleSubmission}, against the real schema.
 *
 * <p>{@code JobManager.submitJobs} claims a pair (PENDING_SUBMIT to ENQUEUED), has the backend
 * start it, and only then writes the execution id, conditionally. When the job was killed,
 * deleted or paused in between, that write is refused and the execution is stopped. Nothing
 * else used to be written, so the pair stayed ENQUEUED with no execution id: {@code Jobs.kill}
 * could not prove the stop of an execution it could not name, and deleting the job was refused
 * on every retry (E2E check 14.k1, 2026-10-22: pair 43, job 10).
 *
 * <p>Skips unless a PostgreSQL instance is configured, following the convention in
 * {@link JobsSqlRegressionTest}. Point {@code STAREXEC_DB_URL} / {@code STAREXEC_DB_USER} /
 * {@code STAREXEC_DB_PASSWORD} at a <strong>disposable</strong> database: this test inserts and
 * deletes rows.
 */
public class StaleSubmissionSettleSqlTest extends Common {

	private static final int PENDING = StatusCode.STATUS_PENDING_SUBMIT.getVal();
	private static final int ENQUEUED = StatusCode.STATUS_ENQUEUED.getVal();
	private static final int PAUSED = StatusCode.STATUS_PAUSED.getVal();
	private static final int KILLED = StatusCode.STATUS_KILLED.getVal();

	/** The execution the backend created for the stale submission. */
	private static final int EXEC_ID = 1043;

	private int jobId;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("StaleSubmissionSettleSqlTest");
		Common.initialize();
	}

	@After
	public void dropFixture() throws SQLException {
		if (jobId == 0) {
			return;
		}
		try (Connection con = Common.getConnection()) {
			// jobpair_stage_data and job_pair_attempts cascade from job_pairs.
			update(con, "DELETE FROM starexec.job_pairs WHERE job_id = ?", jobId);
			update(con, "DELETE FROM starexec.jobs WHERE id = ?", jobId);
		}
	}

	@Test
	public void aKilledAndDeletedJobsPairIsSettledKilled() throws SQLException {
		// The 14.k1 state: the deletion killed the job while pair 43 was being submitted.
		int pairId = pair(JobState.KILLED_AND_DELETED, ENQUEUED, null);

		assertEquals(StaleSubmissionOutcome.KILLED, JobPairs.settleStaleSubmission(pairId, 0, EXEC_ID, true));
		assertPair(pairId, KILLED, EXEC_ID);
	}

	@Test
	public void aDeletedJobsPairIsSettledKilled() throws SQLException {
		int pairId = pair(JobState.DELETED, ENQUEUED, null);

		assertEquals(StaleSubmissionOutcome.KILLED, JobPairs.settleStaleSubmission(pairId, 0, EXEC_ID, true));
		assertPair(pairId, KILLED, EXEC_ID);
	}

	@Test
	public void aPausedJobsPairGoesBackToPending() throws SQLException {
		int pairId = pair(JobState.PAUSED, ENQUEUED, null);

		assertEquals(StaleSubmissionOutcome.RETURNED_TO_PENDING,
				JobPairs.settleStaleSubmission(pairId, 0, EXEC_ID, true));
		assertPair(pairId, PENDING, EXEC_ID);
	}

	@Test
	public void aJobResumedMeanwhileGetsItsPairBackToPending() throws SQLException {
		int pairId = pair(JobState.ACTIVE, ENQUEUED, null);

		assertEquals(StaleSubmissionOutcome.RETURNED_TO_PENDING,
				JobPairs.settleStaleSubmission(pairId, 0, EXEC_ID, true));
		assertPair(pairId, PENDING, EXEC_ID);
	}

	@Test
	public void anUnprovenStopOnlyRecordsTheExecution() throws SQLException {
		int pairId = pair(JobState.KILLED_AND_DELETED, ENQUEUED, null);

		assertEquals(StaleSubmissionOutcome.EXECUTION_RECORDED,
				JobPairs.settleStaleSubmission(pairId, 0, EXEC_ID, false));
		// Still in flight, and now nameable: Jobs.kill can confirm the stop on its next attempt.
		assertPair(pairId, ENQUEUED, EXEC_ID);
	}

	@Test
	public void aPairPauseAlreadyMovedKeepsItsStatus() throws SQLException {
		// Jobs.pause writes PAUSED for an enqueued pair it cannot name; ResumeJob owns it now.
		int pairId = pair(JobState.PAUSED, PAUSED, null);

		assertEquals(StaleSubmissionOutcome.EXECUTION_RECORDED,
				JobPairs.settleStaleSubmission(pairId, 0, EXEC_ID, true));
		assertPair(pairId, PAUSED, EXEC_ID);
	}

	@Test
	public void aRerunPairIsFencedOnTheExecutionItKept() throws SQLException {
		// RerunJobPairsBatchCore never clears sge_id, so a rerun pair is claimed carrying its last one.
		int pairId = pair(JobState.KILLED, ENQUEUED, 41);

		assertEquals(StaleSubmissionOutcome.KILLED, JobPairs.settleStaleSubmission(pairId, 41, EXEC_ID, true));
		assertPair(pairId, KILLED, EXEC_ID);
	}

	@Test
	public void aPairAnotherExecutionOwnsIsLeftAlone() throws SQLException {
		int pairId = pair(JobState.KILLED, ENQUEUED, 42);

		assertEquals(StaleSubmissionOutcome.STALE, JobPairs.settleStaleSubmission(pairId, 41, EXEC_ID, true));
		assertPair(pairId, ENQUEUED, 42);
	}

	@Test
	public void aPairThatIsGoneIsStale() throws SQLException {
		int pairId = pair(JobState.KILLED_AND_DELETED, ENQUEUED, null);
		try (Connection con = Common.getConnection()) {
			update(con, "DELETE FROM starexec.job_pairs WHERE id = ?", pairId);
		}

		assertEquals(StaleSubmissionOutcome.STALE, JobPairs.settleStaleSubmission(pairId, 0, EXEC_ID, true));
	}

	// ----------------------------------------------------------------- fixture

	/** The flags of the fixture job. */
	private enum JobState {
		ACTIVE(false, false, false),
		PAUSED(false, false, true),
		KILLED(true, false, false),
		DELETED(false, true, false),
		KILLED_AND_DELETED(true, true, false);

		final boolean killed;
		final boolean deleted;
		final boolean paused;

		JobState(boolean killed, boolean deleted, boolean paused) {
			this.killed = killed;
			this.deleted = deleted;
			this.paused = paused;
		}
	}

	/** A pair of a fresh job in state {@code job}, with its stage row, both at {@code status}. */
	private int pair(JobState job, int status, Integer sgeId) throws SQLException {
		try (Connection con = Common.getConnection()) {
			assertTrue("a pooled connection must come back in autocommit mode", con.getAutoCommit());
			int userId = selectInt(con, "SELECT min(id) FROM starexec.users");
			jobId = selectInt(con, "INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size) " +
					"VALUES (?, 'stale-submission-settle-test', 1, 0) RETURNING id", userId);
			update(con, "UPDATE starexec.jobs SET killed = ?, deleted = ?, paused = ? WHERE id = ?",
					job.killed, job.deleted, job.paused, jobId);
			int pairId = selectInt(con, "INSERT INTO starexec.job_pairs " +
					"(job_id, sge_id, status_code, primary_jobpair_data) VALUES (?, ?, ?, 1) RETURNING id",
					jobId, sgeId, status);
			update(con, "INSERT INTO starexec.jobpair_stage_data (jobpair_id, stage_number, status_code, disk_size) " +
					"VALUES (?, 1, ?, 0)", pairId, status);
			return pairId;
		}
	}

	/** The pair and its stage row both carry {@code status}, and the pair carries {@code sgeId}. */
	private static void assertPair(int pairId, int status, int sgeId) throws SQLException {
		try (Connection con = Common.getConnection()) {
			assertEquals("pair status", status,
					selectInt(con, "SELECT status_code FROM starexec.job_pairs WHERE id = ?", pairId));
			assertEquals("stage status", status,
					selectInt(con, "SELECT status_code FROM starexec.jobpair_stage_data WHERE jobpair_id = ?", pairId));
			assertEquals("execution id", sgeId,
					selectInt(con, "SELECT COALESCE(sge_id, 0) FROM starexec.job_pairs WHERE id = ?", pairId));
		}
	}

	/** {@code sql} with {@code params} bound in order. A null binds an SQL NULL integer (sge_id). */
	private static PreparedStatement prepare(Connection con, String sql, Object... params) throws SQLException {
		PreparedStatement ps = con.prepareStatement(sql);
		try {
			for (int i = 0; i < params.length; i++) {
				if (params[i] == null) {
					ps.setNull(i + 1, Types.INTEGER);
				} else {
					ps.setObject(i + 1, params[i]);
				}
			}
			return ps;
		} catch (SQLException | RuntimeException e) {
			ps.close();
			throw e;
		}
	}

	private static int selectInt(Connection con, String sql, Object... params) throws SQLException {
		try (PreparedStatement ps = prepare(con, sql, params); ResultSet rs = ps.executeQuery()) {
			if (!rs.next()) {
				throw new IllegalStateException("Query returned no rows: " + sql);
			}
			return rs.getInt(1);
		}
	}

	private static void update(Connection con, String sql, Object... params) throws SQLException {
		try (PreparedStatement ps = prepare(con, sql, params)) {
			ps.executeUpdate();
		}
	}
}
