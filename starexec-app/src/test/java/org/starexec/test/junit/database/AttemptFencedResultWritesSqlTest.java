package org.starexec.test.junit.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.backend.Backend;
import org.starexec.constants.R;
import org.starexec.data.database.Common;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.database.StageStatusBatchResult;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

import java.util.Properties;

/**
 * The attempt fence on result writes (#185), against the real routines.
 *
 * <p>A pair that is rerun moves to a new attempt, and the execution of the old attempt may
 * still deliver its results afterwards -- a late monitor poll, a container that outlived its
 * kill. Without a fence that late write lands on the attempt that replaced it. Each of the
 * five routines that write a pair's results takes the attempt the writer was started for and
 * refuses any other than the current one with SQLSTATE {@code SX185}; the Java wrappers map
 * that to a stale result and never throw for it.
 *
 * <p>The attempt is bumped by a real {@link Jobs#rerunPairAutomatic}, not by writing
 * {@code job_pair_attempts} directly, so the test fails if the rerun and the fence ever
 * disagree about what "current" means.
 *
 * <p>For every routine the test proves: refused with the previous attempt, nothing changed by
 * the refusal, accepted with the current attempt, accepted unfenced (NULL). For the one
 * routine with an override flag it proves the fence holds with the override set.
 *
 * <p>Skips unless a PostgreSQL instance is configured, following
 * {@link JobsRerunProtocolSqlTest}. Point {@code STAREXEC_DB_URL} / {@code STAREXEC_DB_USER} /
 * {@code STAREXEC_DB_PASSWORD} at a <strong>disposable</strong> database: these tests insert
 * and delete rows.
 */
public class AttemptFencedResultWritesSqlTest extends Common {

	private static final String STALE_SQLSTATE = "SX185";
	private static final int PREVIOUS_ATTEMPT = 1;
	private static final int CURRENT_ATTEMPT = 2;
	private static final int RUNNING = StatusCode.STATUS_RUNNING.getVal();
	private static final int COMPLETE = StatusCode.STATUS_COMPLETE.getVal();
	private static final int EXCEED_RUNTIME = StatusCode.EXCEED_RUNTIME.getVal();
	private static final int NOT_REACHED = StatusCode.STATUS_NOT_REACHED.getVal();

	private int userId;
	private int jobId;
	private int pairId;
	private int nodeId;
	private String nodeName;
	private Backend originalBackend;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("AttemptFencedResultWritesSqlTest");
		Common.initialize();
	}

	@Before
	public void createFixture() throws SQLException {
		originalBackend = R.BACKEND;
		nodeName = "attempt-fence-" + UUID.randomUUID();
		try (Connection con = Common.getConnection()) {
			userId = insertReturningId(con,
					"INSERT INTO starexec.users (email, first_name, last_name, institution, created,"
							+ " password, disk_quota, job_pair_quota, disk_size)"
							+ " VALUES (?, 'Attempt', 'Fence', 'test', NOW(), 'x', 10000, 100, 0)"
							+ " RETURNING id",
					"af-" + UUID.randomUUID() + "@example.invalid");
			jobId = insertReturningId(con,
					"INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size)"
							+ " VALUES (?, 'attempt-fence-test', 1, 0) RETURNING id",
					userId);
			// A failed pair inside the rerun window, the state Jobs.rerunPairAutomatic accepts.
			pairId = insertReturningId(con,
					"INSERT INTO starexec.job_pairs (job_id, sge_id, status_code, start_time,"
							+ " end_time, primary_jobpair_data)"
							+ " VALUES (?, 987654, ?, NOW() - INTERVAL '20 minutes',"
							+ " NOW() - INTERVAL '10 minutes', 1) RETURNING id",
					jobId, StatusCode.ERROR_RUNSCRIPT.getVal());
			// Two stages, so a terminal status written at stage 1 has a later stage to mark.
			try (PreparedStatement ps = con.prepareStatement(
					"INSERT INTO starexec.jobpair_stage_data (jobpair_id, stage_number, status_code,"
							+ " disk_size) VALUES (?, 1, ?, 0), (?, 2, ?, 0)")) {
				ps.setInt(1, pairId);
				ps.setInt(2, StatusCode.ERROR_RUNSCRIPT.getVal());
				ps.setInt(3, pairId);
				ps.setInt(4, NOT_REACHED);
				ps.executeUpdate();
			}
			nodeId = insertReturningId(con,
					"INSERT INTO starexec.nodes (name, status) VALUES (?, 'ACTIVE') RETURNING id",
					nodeName);
		}
	}

	@After
	public void dropFixture() throws SQLException {
		R.BACKEND = originalBackend;
		try (Connection con = Common.getConnection()) {
			// job_pair_attempts, stage data, attributes and completion rows cascade from job_pairs.
			update(con, "DELETE FROM starexec.job_pairs WHERE job_id = ?", jobId);
			update(con, "DELETE FROM starexec.jobs WHERE id = ?", jobId);
			update(con, "DELETE FROM starexec.nodes WHERE id = ?", nodeId);
			update(con, "DELETE FROM starexec.users WHERE id = ?", userId);
		}
	}

	// ------------------------------------------------------------------
	// UpdatePairStatusPrecise
	// ------------------------------------------------------------------

	@Test
	public void aPreciseStatusWriteForThePreviousAttemptIsRefusedEvenWhenForced()
			throws SQLException {
		rerunToAttemptTwo();
		String before = snapshot();

		for (boolean force : new boolean[] {false, true}) {
			assertEquals("force=" + force + ": the Java wrapper reports a stale attempt",
					PairStatusResult.STALE_ATTEMPT,
					JobPairs.setPairStatusPreciseResult(pairId, 1, COMPLETE, NOT_REACHED, force,
							PREVIOUS_ATTEMPT));
			assertEquals("force=" + force + ": the routine refuses with the dedicated SQLSTATE",
					STALE_SQLSTATE,
					sqlStateOf("SELECT starexec.UpdatePairStatusPrecise(?, ?, ?, ?, ?, ?)",
							pairId, 1, COMPLETE, NOT_REACHED, force, PREVIOUS_ATTEMPT));
			assertEquals("force=" + force + ": a refusal changes no row", before, snapshot());
		}
	}

	@Test
	public void aPreciseStatusWriteForTheCurrentAttemptIsAppliedAndSoIsAnOverrideOfIt()
			throws SQLException {
		rerunToAttemptTwo();
		String before = snapshot();

		assertEquals(PairStatusResult.APPLIED, JobPairs.setPairStatusPreciseResult(
				pairId, 1, COMPLETE, NOT_REACHED, false, CURRENT_ATTEMPT));
		assertEquals(COMPLETE, pairStatus());
		assertNotEquals("the write changed the pair", before, snapshot());

		// The override still works for the current attempt: the fence is not "force is
		// refused", it is "a superseded attempt is refused".
		assertEquals(PairStatusResult.APPLIED, JobPairs.setPairStatusPreciseResult(
				pairId, 1, EXCEED_RUNTIME, NOT_REACHED, true, CURRENT_ATTEMPT));
		assertEquals(EXCEED_RUNTIME, pairStatus());

		// And the previous attempt still cannot replace that result, forced or not.
		String settled = snapshot();
		assertEquals(PairStatusResult.STALE_ATTEMPT, JobPairs.setPairStatusPreciseResult(
				pairId, 1, COMPLETE, NOT_REACHED, true, PREVIOUS_ATTEMPT));
		assertEquals(settled, snapshot());
	}

	@Test
	public void anUnfencedPreciseStatusWriteIsStillAccepted() throws SQLException {
		rerunToAttemptTwo();

		assertEquals(PairStatusResult.APPLIED, JobPairs.setPairStatusPreciseResult(
				pairId, 1, COMPLETE, NOT_REACHED, false, null));
		assertEquals(COMPLETE, pairStatus());

		// The pre-fence signatures keep resolving: four-argument SQL and the old Java method.
		resetToRunning();
		assertEquals("t", stringOf("SELECT starexec.UpdatePairStatusPrecise(?, ?, ?, ?)",
				pairId, 1, COMPLETE, NOT_REACHED));
		resetToRunning();
		assertEquals(PairStatusResult.APPLIED, JobPairs.setPairStatusPreciseResult(
				pairId, 1, COMPLETE, NOT_REACHED, false));
	}

	// ------------------------------------------------------------------
	// UpdatePairStatusPairLevel
	// ------------------------------------------------------------------

	@Test
	public void aPairLevelStatusWriteIsFencedOnTheAttempt() throws SQLException {
		rerunToAttemptTwo();
		String before = snapshot();

		assertEquals(PairStatusResult.STALE_ATTEMPT, JobPairs.setPairLevelStatusResult(
				pairId, StatusCode.ERROR_RUNSCRIPT.getVal(), NOT_REACHED, PREVIOUS_ATTEMPT));
		assertEquals(STALE_SQLSTATE,
				sqlStateOf("SELECT starexec.UpdatePairStatusPairLevel(?, ?, ?, ?)",
						pairId, StatusCode.ERROR_RUNSCRIPT.getVal(), NOT_REACHED, PREVIOUS_ATTEMPT));
		assertEquals("a refusal changes no row", before, snapshot());

		assertEquals(PairStatusResult.APPLIED, JobPairs.setPairLevelStatusResult(
				pairId, StatusCode.ERROR_RUNSCRIPT.getVal(), NOT_REACHED, CURRENT_ATTEMPT));
		assertEquals(StatusCode.ERROR_RUNSCRIPT.getVal(), pairStatus());

		resetToRunning();
		assertEquals("unfenced still works", PairStatusResult.APPLIED,
				JobPairs.setPairLevelStatusResult(
						pairId, StatusCode.ERROR_RUNSCRIPT.getVal(), NOT_REACHED, null));
		resetToRunning();
		assertEquals("the three-argument overload still works", PairStatusResult.APPLIED,
				JobPairs.setPairLevelStatusResult(
						pairId, StatusCode.ERROR_RUNSCRIPT.getVal(), NOT_REACHED));
	}

	// ------------------------------------------------------------------
	// UpdatePairStageStatusIfUnresolved
	// ------------------------------------------------------------------

	@Test
	public void anEarlierStageStatusWriteIsFencedOnTheAttempt() throws SQLException {
		rerunToAttemptTwo();
		String before = snapshot();

		assertEquals(StageStatusBatchResult.STALE_ATTEMPT, JobPairs.setEarlierStageStatuses(
				pairId, Collections.singletonMap(1, COMPLETE), PREVIOUS_ATTEMPT));
		assertEquals(STALE_SQLSTATE,
				sqlStateOf("SELECT starexec.UpdatePairStageStatusIfUnresolved(?, ?, ?, ?)",
						pairId, 1, COMPLETE, PREVIOUS_ATTEMPT));
		assertEquals("a refusal changes no row", before, snapshot());

		assertEquals(StageStatusBatchResult.APPLIED, JobPairs.setEarlierStageStatuses(
				pairId, Collections.singletonMap(1, COMPLETE), CURRENT_ATTEMPT));
		assertEquals(COMPLETE, stageStatus(1));

		resetToRunning();
		assertEquals("unfenced still works", StageStatusBatchResult.APPLIED,
				JobPairs.setEarlierStageStatuses(pairId, Collections.singletonMap(1, COMPLETE)));
		assertEquals(COMPLETE, stageStatus(1));
		resetToRunning();
		assertEquals("the three-argument SQL form still resolves", "t",
				stringOf("SELECT starexec.UpdatePairStageStatusIfUnresolved(?, ?, ?)",
						pairId, 1, COMPLETE));
	}

	// ------------------------------------------------------------------
	// UpdatePairRunSolverStats
	// ------------------------------------------------------------------

	@Test
	public void runSolverStatsAreFencedOnTheAttemptBeforeAnythingIsCharged() throws SQLException {
		rerunToAttemptTwo();
		String before = snapshot();

		assertFalse("a stale write reports false", JobPairs.updateRunSolverStats(
				pairId, nodeName, 12.0, 10.0, 8.0, 2.0, 256.0, 128L, 1, 2048L, PREVIOUS_ATTEMPT));
		assertEquals(STALE_SQLSTATE, sqlStateOf(
				"CALL starexec.UpdatePairRunSolverStats(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				pairId, nodeName, 12.0, 10.0, 8.0, 2.0, 256.0, 128L, 1, 2048L, PREVIOUS_ATTEMPT));
		assertEquals("a refusal moves neither the node, the stage nor the disk totals",
				before, snapshot());

		assertTrue(JobPairs.updateRunSolverStats(
				pairId, nodeName, 12.0, 10.0, 8.0, 2.0, 256.0, 128L, 1, 2048L, CURRENT_ATTEMPT));
		assertEquals(2048L, longOf("SELECT disk_size FROM starexec.jobpair_stage_data"
				+ " WHERE jobpair_id = ? AND stage_number = 1", pairId));
		assertEquals("the user was charged once", 2048L,
				longOf("SELECT disk_size FROM starexec.users WHERE id = ?", userId));
		assertEquals(nodeId, (int) longOf("SELECT node_id FROM starexec.job_pairs WHERE id = ?",
				pairId));

		resetToRunning();
		assertTrue("unfenced still works", JobPairs.updateRunSolverStats(
				pairId, nodeName, 12.0, 10.0, 8.0, 2.0, 256.0, 128L, 1, 2048L, null));
		resetToRunning();
		assertTrue("the ten-argument overload still works", JobPairs.updateRunSolverStats(
				pairId, nodeName, 12.0, 10.0, 8.0, 2.0, 256.0, 128L, 1, 2048L));
	}

	// ------------------------------------------------------------------
	// AddJobAttr
	// ------------------------------------------------------------------

	@Test
	public void jobAttributesAreFencedOnTheAttempt() throws SQLException {
		rerunToAttemptTwo();
		Properties attrs = new Properties();
		attrs.setProperty("starexec-result", "Satisfiable");
		String before = snapshot();

		assertFalse("a stale write is visible to the caller",
				JobPairs.addJobPairAttributes(pairId, 1, attrs, PREVIOUS_ATTEMPT));
		assertEquals(STALE_SQLSTATE, sqlStateOf("CALL starexec.AddJobAttr(?, ?, ?, ?, ?)",
				pairId, "starexec-result", "Satisfiable", 1, PREVIOUS_ATTEMPT));
		assertEquals("a refusal writes no attribute", before, snapshot());
		assertEquals(0L, attributeCount());

		assertTrue(JobPairs.addJobPairAttributes(pairId, 1, attrs, CURRENT_ATTEMPT));
		assertEquals(1L, attributeCount());

		update("DELETE FROM starexec.job_attributes WHERE pair_id = ?", pairId);
		assertTrue("unfenced still works", JobPairs.addJobPairAttributes(pairId, 1, attrs, (Integer) null));
		assertEquals(1L, attributeCount());

		update("DELETE FROM starexec.job_attributes WHERE pair_id = ?", pairId);
		assertTrue("the pre-fence Java method still works",
				JobPairs.addJobPairAttributes(pairId, 1, attrs));
		update("DELETE FROM starexec.job_attributes WHERE pair_id = ?", pairId);
		sqlStateOf("CALL starexec.AddJobAttr(?, ?, ?, ?)",
				pairId, "starexec-result", "Satisfiable", 1);
		assertEquals("the four-argument SQL form still resolves and is unfenced",
				1L, attributeCount());
	}

	// ------------------------------------------------------------------
	// a pair that was never rerun
	// ------------------------------------------------------------------

	@Test
	public void aPairWithNoAttemptRowIsOnAttemptOneAndTheFenceNeverInsertsOne() throws SQLException {
		update("DELETE FROM starexec.job_pair_attempts WHERE pair_id = ?", pairId);
		assertEquals(0L, attemptRows());
		assertEquals("a missing row means attempt 1", 1, JobPairs.getCurrentAttemptNo(pairId));
		assertEquals("reading the attempt inserts nothing", 0L, attemptRows());

		assertEquals("attempt 1 is the current attempt", null, sqlStateOf(
				"CALL starexec.AddJobAttr(?, ?, ?, ?, ?)", pairId, "k", "v", 1, 1));
		assertEquals("attempt 2 is not", STALE_SQLSTATE, sqlStateOf(
				"CALL starexec.AddJobAttr(?, ?, ?, ?, ?)", pairId, "k2", "v", 1, 2));
		assertEquals(STALE_SQLSTATE, sqlStateOf(
				"SELECT starexec.UpdatePairStageStatusIfUnresolved(?, ?, ?, ?)",
				pairId, 1, COMPLETE, 2));
		assertEquals(null, sqlStateOf(
				"SELECT starexec.UpdatePairStageStatusIfUnresolved(?, ?, ?, ?)",
				pairId, 1, COMPLETE, 1));
		assertEquals("no fence call created a row", 0L, attemptRows());
	}

	@Test
	public void theCurrentAttemptIsReadFromTheRowARerunWrote() throws SQLException {
		assertEquals("never rerun", 1, JobPairs.getCurrentAttemptNo(pairId));
		rerunToAttemptTwo();
		assertEquals(CURRENT_ATTEMPT, JobPairs.getCurrentAttemptNo(pairId));
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/** A real automatic rerun, which moves the pair to attempt 2, then a plausible running state. */
	private void rerunToAttemptTwo() throws SQLException {
		R.BACKEND = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);
		assertEquals(Jobs.RerunOutcome.COMPLETED, Jobs.rerunPairAutomatic(pairId));
		assertEquals("the rerun bumped the attempt", CURRENT_ATTEMPT,
				(int) longOf("SELECT current_attempt_no FROM starexec.job_pair_attempts"
						+ " WHERE pair_id = ?", pairId));
		resetToRunning();
	}

	/** The pair and both stages as an execution in flight leaves them; totals back to zero. */
	private void resetToRunning() throws SQLException {
		update("DELETE FROM starexec.job_pair_completion WHERE pair_id = ?", pairId);
		update("UPDATE starexec.job_pairs SET status_code = ?, end_time = NULL, node_id = NULL"
				+ " WHERE id = ?", RUNNING, pairId);
		update("UPDATE starexec.jobpair_stage_data SET status_code = ?, wallclock = NULL,"
				+ " cpu = NULL, user_time = NULL, system_time = NULL, max_vmem = NULL,"
				+ " max_res_set = NULL, disk_size = 0 WHERE jobpair_id = ?", RUNNING, pairId);
		update("UPDATE starexec.jobs SET disk_size = 0 WHERE id = ?", jobId);
		update("UPDATE starexec.users SET disk_size = 0 WHERE id = ?", userId);
	}

	/** Everything a result write could touch, as one comparable string. */
	private String snapshot() throws SQLException {
		StringBuilder b = new StringBuilder();
		b.append(rows("SELECT status_code, end_time, node_id FROM starexec.job_pairs WHERE id = ?",
				pairId));
		b.append(rows("SELECT stage_number, status_code, wallclock, cpu, user_time, system_time,"
				+ " max_vmem, max_res_set, disk_size FROM starexec.jobpair_stage_data"
				+ " WHERE jobpair_id = ? ORDER BY stage_number", pairId));
		b.append(rows("SELECT attr_key, attr_value, stage_number FROM starexec.job_attributes"
				+ " WHERE pair_id = ? ORDER BY attr_key, stage_number", pairId));
		b.append(rows("SELECT disk_size FROM starexec.jobs WHERE id = ?", jobId));
		b.append(rows("SELECT disk_size FROM starexec.users WHERE id = ?", userId));
		b.append(rows("SELECT count(*) FROM starexec.job_pair_completion WHERE pair_id = ?",
				pairId));
		return b.toString();
	}

	private int pairStatus() throws SQLException {
		return (int) longOf("SELECT status_code FROM starexec.job_pairs WHERE id = ?", pairId);
	}

	private int stageStatus(int stage) throws SQLException {
		return (int) longOf("SELECT status_code FROM starexec.jobpair_stage_data"
				+ " WHERE jobpair_id = ? AND stage_number = ?", pairId, stage);
	}

	private long attributeCount() throws SQLException {
		return longOf("SELECT count(*) FROM starexec.job_attributes WHERE pair_id = ?", pairId);
	}

	private long attemptRows() throws SQLException {
		return longOf("SELECT count(*) FROM starexec.job_pair_attempts WHERE pair_id = ?", pairId);
	}

	/** The SQLSTATE the statement fails with, or null when it succeeds. */
	private static String sqlStateOf(String sql, Object... params) throws SQLException {
		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement(sql)) {
			bind(ps, params);
			ps.execute();
			return null;
		} catch (SQLException e) {
			return e.getSQLState();
		}
	}

	private static String stringOf(String sql, Object... params) throws SQLException {
		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement(sql)) {
			bind(ps, params);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue("no row for " + sql, rs.next());
				return rs.getString(1);
			}
		}
	}

	private static long longOf(String sql, Object... params) throws SQLException {
		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement(sql)) {
			bind(ps, params);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue("no row for " + sql, rs.next());
				return rs.getLong(1);
			}
		}
	}

	private static String rows(String sql, Object... params) throws SQLException {
		StringBuilder b = new StringBuilder("[");
		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement(sql)) {
			bind(ps, params);
			try (ResultSet rs = ps.executeQuery()) {
				int columns = rs.getMetaData().getColumnCount();
				while (rs.next()) {
					b.append('(');
					for (int i = 1; i <= columns; i++) {
						b.append(rs.getObject(i)).append(',');
					}
					b.append(')');
				}
			}
		}
		return b.append(']').toString();
	}

	private static int insertReturningId(Connection con, String sql, Object... params)
			throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			bind(ps, params);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next());
				return rs.getInt(1);
			}
		}
	}

	private static void update(Connection con, String sql, Object... params) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			bind(ps, params);
			ps.executeUpdate();
		}
	}

	private static void update(String sql, Object... params) throws SQLException {
		try (Connection con = Common.getConnection()) {
			update(con, sql, params);
		}
	}

	private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
		for (int i = 0; i < params.length; i++) {
			ps.setObject(i + 1, params[i]);
		}
	}

	private static Backend backendReturning(Backend.KillOutcome outcome) {
		Backend backend = org.mockito.Mockito.mock(Backend.class);
		org.mockito.Mockito
				.when(backend.killPairConfirmed(org.mockito.ArgumentMatchers.anyInt()))
				.thenReturn(outcome);
		return backend;
	}
}
