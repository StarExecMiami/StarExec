package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.data.database.Common;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.JobPairs.ConditionalPairUpdateResult;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The fence on {@link JobPairs#tryReturnDeferredPairToPending}, against the real schema.
 *
 * <p>{@code JobManager.submitJobs} claims a pair (PENDING_SUBMIT to ENQUEUED) before asking the
 * backend to run it; when the backend defers, the pair must go back to PENDING_SUBMIT or it is
 * stranded. The reset may only apply to the pair as it was claimed: still ENQUEUED, and with the
 * same {@code COALESCE(sge_id, 0)} it had when it was loaded. A changed execution id means
 * something adopted the pair, and it must be left alone. Equality, not {@code sge_id IS NULL},
 * because a rerun pair keeps its previous execution id.
 *
 * <p>Skips unless a PostgreSQL instance is configured, following the convention in
 * {@link JobsSqlRegressionTest}. Point {@code STAREXEC_DB_URL} / {@code STAREXEC_DB_USER} /
 * {@code STAREXEC_DB_PASSWORD} at a <strong>disposable</strong> database: this test inserts and
 * deletes rows.
 */
public class DeferredPairResetSqlTest extends Common {

	private static final int ENQUEUED = StatusCode.STATUS_ENQUEUED.getVal();
	private static final int PENDING = StatusCode.STATUS_PENDING_SUBMIT.getVal();
	private static final int RUNNING = StatusCode.STATUS_RUNNING.getVal();

	private int jobId;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("DeferredPairResetSqlTest");
		Common.initialize();
	}

	@After
	public void dropFixture() throws SQLException {
		if (jobId == 0) {
			return;
		}
		try (Connection con = Common.getConnection()) {
			// jobpair_stage_data and job_pair_attempts cascade from job_pairs.
			exec(con, "DELETE FROM starexec.job_pairs WHERE job_id = " + jobId);
			exec(con, "DELETE FROM starexec.jobs WHERE id = " + jobId);
		}
	}

	@Test
	public void aFreshPairWithNoExecutionIsReturned() throws SQLException {
		int pairId = pair(ENQUEUED, null);

		assertEquals(ConditionalPairUpdateResult.UPDATED, JobPairs.tryReturnDeferredPairToPending(pairId, 0));
		assertStatus(pairId, PENDING);
	}

	@Test
	public void aRerunPairKeepingItsPreviousExecutionIsReturned() throws SQLException {
		int pairId = pair(ENQUEUED, 41);

		assertEquals(ConditionalPairUpdateResult.UPDATED, JobPairs.tryReturnDeferredPairToPending(pairId, 41));
		assertStatus(pairId, PENDING);
	}

	@Test
	public void aPairWhoseExecutionChangedIsLeftAlone() throws SQLException {
		int pairId = pair(ENQUEUED, 42);

		assertEquals(ConditionalPairUpdateResult.STALE, JobPairs.tryReturnDeferredPairToPending(pairId, 41));
		assertStatus(pairId, ENQUEUED);
	}

	@Test
	public void aPairThatGainedAnExecutionIsLeftAlone() throws SQLException {
		int pairId = pair(ENQUEUED, 42);

		assertEquals(ConditionalPairUpdateResult.STALE, JobPairs.tryReturnDeferredPairToPending(pairId, 0));
		assertStatus(pairId, ENQUEUED);
	}

	@Test
	public void aPairNoLongerEnqueuedIsLeftAlone() throws SQLException {
		int pairId = pair(RUNNING, null);

		assertEquals(ConditionalPairUpdateResult.STALE, JobPairs.tryReturnDeferredPairToPending(pairId, 0));
		assertStatus(pairId, RUNNING);
	}

	// ----------------------------------------------------------------- fixture

	/** A pair of a fresh job, with its stage row, in the given state. */
	private int pair(int status, Integer sgeId) throws SQLException {
		try (Connection con = Common.getConnection()) {
			assertTrue("a pooled connection must come back in autocommit mode", con.getAutoCommit());
			int userId = selectInt(con, "SELECT min(id) FROM starexec.users");
			jobId = selectInt(con, "INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size) " +
					"VALUES (" + userId + ", 'deferred-pair-reset-test', 1, 0) RETURNING id");
			int pairId = selectInt(con, "INSERT INTO starexec.job_pairs " +
					"(job_id, sge_id, status_code, primary_jobpair_data) VALUES (" + jobId + ", " +
					(sgeId == null ? "NULL" : sgeId) + ", " + status + ", 1) RETURNING id");
			exec(con, "INSERT INTO starexec.jobpair_stage_data (jobpair_id, stage_number, status_code, disk_size) " +
					"VALUES (" + pairId + ", 1, " + status + ", 0)");
			return pairId;
		}
	}

	/** The pair and its stage row both carry {@code expected}. */
	private static void assertStatus(int pairId, int expected) throws SQLException {
		try (Connection con = Common.getConnection()) {
			assertEquals("pair status", expected,
					selectInt(con, "SELECT status_code FROM starexec.job_pairs WHERE id = " + pairId));
			assertEquals("stage status", expected,
					selectInt(con, "SELECT status_code FROM starexec.jobpair_stage_data WHERE jobpair_id = " + pairId));
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

	private static void exec(Connection con, String sql) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			ps.executeUpdate();
		}
	}
}
