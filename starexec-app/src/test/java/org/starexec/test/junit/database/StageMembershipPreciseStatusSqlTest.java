package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.constants.R;
import org.starexec.data.database.Common;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A precise stage write has to name a stage <em>of the pair it names</em>, not merely a
 * positive number.
 *
 * <h2>What the routine did with a stage the pair does not have</h2>
 *
 * Both stage updates are keyed on the pair and the stage, so a stage the pair does not have
 * matches no row in either. Everything after them still ran: the pair took the terminal
 * status, was stamped with an {@code end_time}, got a completion row, and could stamp
 * {@code jobs.completed}. The pair read finished while not one of its stages carried the
 * result -- and a terminal pair is past {@code RERUN_FAILED_PAIRS}, the only automatic retry
 * path, so nothing brought it back. The routine returned TRUE throughout.
 *
 * <p>{@code negativeControl_aStageThePairDoesNotHaveMatchesNoStageRow} demonstrates that
 * split directly against this fixture, so the assertions below are made against a divergence
 * that has been shown to happen rather than one taken on trust.
 *
 * <h2>Why membership and not a range</h2>
 *
 * Stage numbers are not dense. {@code JobPairs.addJobPairStages} skips no-op stages, so
 * {@code jobpair_stage_data} legitimately has gaps, and a "between 1 and the stage count"
 * test would accept a gap and reject a sparse tail. {@code sparseStages} pins both
 * directions.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*}
 * variables at a <strong>disposable</strong> database.
 */
public class StageMembershipPreciseStatusSqlTest extends Common {

	private static final String TAG = "stage-membership-probe";

	private static final int USER_ID = 91301;
	private static final int SPACE_ID = 91301;
	private static final int QUEUE_ID = 91301;
	private static final int SOLVER_ID = 91301;
	private static final int CONFIG_ID = 91301;
	private static final int BENCH_ID = 91301;
	private static final int JOB_ID = 91301;

	/** Stages 1, 2, 3. */
	private static final int PAIR_ID = 91301;
	/** Stages 1 and 3 only -- stage 2 was a no-op and never got a row. */
	private static final int SPARSE_PAIR_ID = 91302;

	private static final int ENQUEUED = StatusCode.STATUS_ENQUEUED.getVal();
	private static final int COMPLETE = StatusCode.STATUS_COMPLETE.getVal();
	private static final int EXCEED_CPU = StatusCode.EXCEED_CPU.getVal();
	private static final int NOT_REACHED = StatusCode.STATUS_NOT_REACHED.getVal();

	/** Distinct per stage, so an unintended overwrite is visible rather than inferred. */
	private static final int STAGE_1_STATUS = COMPLETE;
	private static final int STAGE_2_STATUS = EXCEED_CPU;
	private static final int STAGE_3_STATUS = ENQUEUED;

	/** Positive, and not a stage of either fixture pair. */
	private static final int ABSENT_STAGE = 99;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("StageMembershipPreciseStatusSqlTest");
		Common.initialize();
	}

	@Before
	public void seed() throws Exception {
		cleanUp();
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("INSERT INTO starexec.users (id,email,first_name,last_name,institution,"
					+ "created,password,disk_quota,disk_size) VALUES (" + USER_ID + ",'"
					+ TAG + "@test','A','P','I',NOW(),'x',1000000000,0)");
			s.execute("INSERT INTO starexec.spaces (id,name,created,locked) VALUES ("
					+ SPACE_ID + ",'" + TAG + "',NOW(),false)");
			s.execute("INSERT INTO starexec.queues (id,name,status) VALUES ("
					+ QUEUE_ID + ",'" + TAG + "','ACTIVE') ON CONFLICT DO NOTHING");
			s.execute("INSERT INTO starexec.solvers (id,user_id,name,uploaded,path,description,"
					+ "downloadable,disk_size) VALUES (" + SOLVER_ID + "," + USER_ID + ",'"
					+ TAG + "',NOW(),'/tmp/" + TAG + "','d',false,0)");
			s.execute("INSERT INTO starexec.configurations (id,solver_id,name,description,updated)"
					+ " VALUES (" + CONFIG_ID + "," + SOLVER_ID + ",'" + TAG + "','d',NOW())");
			s.execute("INSERT INTO starexec.benchmarks (id,user_id,name,uploaded,path,description,"
					+ "downloadable,disk_size) VALUES (" + BENCH_ID + "," + USER_ID + ",'"
					+ TAG + "',NOW(),'/tmp/" + TAG + "','d',false,0)");
			s.execute("INSERT INTO starexec.jobs (id,user_id,name,queue_id,created,description,"
					+ "total_pairs,disk_size) VALUES (" + JOB_ID + "," + USER_ID + ",'" + TAG
					+ "'," + QUEUE_ID + ",NOW(),'d',2,0)");

			s.execute("INSERT INTO starexec.job_pairs (id,job_id,bench_id,status_code) VALUES ("
					+ PAIR_ID + "," + JOB_ID + "," + BENCH_ID + "," + ENQUEUED + ")");
			int[] statuses = {STAGE_1_STATUS, STAGE_2_STATUS, STAGE_3_STATUS};
			for (int stage = 1; stage <= 3; stage++) {
				s.execute("INSERT INTO starexec.jobpair_stage_data (jobpair_id,stage_number,"
						+ "status_code,solver_id,config_id,disk_size) VALUES (" + PAIR_ID + ","
						+ stage + "," + statuses[stage - 1] + "," + SOLVER_ID + ","
						+ CONFIG_ID + ",0)");
			}

			// Stage 2 deliberately absent: a no-op stage is skipped at job creation.
			s.execute("INSERT INTO starexec.job_pairs (id,job_id,bench_id,status_code) VALUES ("
					+ SPARSE_PAIR_ID + "," + JOB_ID + "," + BENCH_ID + "," + ENQUEUED + ")");
			for (int stage : new int[]{1, 3}) {
				s.execute("INSERT INTO starexec.jobpair_stage_data (jobpair_id,stage_number,"
						+ "status_code,solver_id,config_id,disk_size) VALUES (" + SPARSE_PAIR_ID
						+ "," + stage + "," + ENQUEUED + "," + SOLVER_ID + "," + CONFIG_ID + ",0)");
			}
		}
	}

	@After
	public void cleanUp() throws SQLException {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			for (int pair : new int[]{PAIR_ID, SPARSE_PAIR_ID}) {
				quietly(s, "DELETE FROM starexec.job_pair_repro_manifests WHERE pair_id=" + pair);
				quietly(s, "DELETE FROM starexec.job_pair_completion WHERE pair_id=" + pair);
				quietly(s, "DELETE FROM starexec.jobpair_stage_data WHERE jobpair_id=" + pair);
				quietly(s, "DELETE FROM starexec.job_pairs WHERE id=" + pair);
			}
			quietly(s, "DELETE FROM starexec.jobs WHERE id=" + JOB_ID);
			quietly(s, "DELETE FROM starexec.configurations WHERE id=" + CONFIG_ID);
			quietly(s, "DELETE FROM starexec.solvers WHERE id=" + SOLVER_ID);
			quietly(s, "DELETE FROM starexec.benchmarks WHERE id=" + BENCH_ID);
			quietly(s, "DELETE FROM starexec.queues WHERE id=" + QUEUE_ID);
			quietly(s, "DELETE FROM starexec.spaces WHERE id=" + SPACE_ID);
			quietly(s, "DELETE FROM starexec.users WHERE id=" + USER_ID);
		}
	}

	// ---------------------------------------------------------- the negative control

	/**
	 * The mechanism, shown rather than argued from the SQL text: with a stage the pair does
	 * not have, both stage updates select nothing while the pair-level update selects the
	 * pair. That split is the defect -- the pair moves and its stages do not.
	 *
	 * <p>On a connection of its own and rolled back, so it proves the divergence without
	 * leaving it behind, and without handing a pooled connection back with autoCommit off.
	 */
	@Test
	public void negativeControl_aStageThePairDoesNotHaveMatchesNoStageRow() throws Exception {
		try (Connection con = DriverManager.getConnection(
				R.POSTGRES_URL, R.POSTGRES_USERNAME, R.POSTGRES_PASSWORD)) {
			con.setAutoCommit(false);
			try (Statement s = con.createStatement()) {
				int terminal = s.executeUpdate(
						"UPDATE starexec.jobpair_stage_data SET status_code = " + COMPLETE
								+ " WHERE jobpair_id = " + PAIR_ID
								+ " AND stage_number = " + ABSENT_STAGE);
				assertEquals("a stage the pair does not have takes the status nowhere",
						0, terminal);

				int later = s.executeUpdate(
						"UPDATE starexec.jobpair_stage_data SET status_code = " + NOT_REACHED
								+ " WHERE jobpair_id = " + PAIR_ID
								+ " AND stage_number > " + ABSENT_STAGE);
				assertEquals("and nothing above it either", 0, later);

				int pair = s.executeUpdate(
						"UPDATE starexec.job_pairs SET status_code = " + COMPLETE
								+ " WHERE id = " + PAIR_ID);
				assertEquals("while the pair-level write still lands: that is the divergence",
						1, pair);
			}
			con.rollback();
		}
		assertEquals("the control must leave the fixture as it found it",
				STAGE_1_STATUS, stageStatus(PAIR_ID, 1));
	}

	// ------------------------------------------------------------------ the invariant

	/**
	 * Through the real Java entry point, against the real routine. The classification has to
	 * be the refusal and not FAILED: every caller treats FAILED as retryable, and this
	 * argument is refused identically on every attempt.
	 */
	@Test
	public void aStageThePairDoesNotHaveIsRejectedNotFailed() {
		PairStatusResult result = JobPairs.setPairStatusPreciseResult(
				PAIR_ID, ABSENT_STAGE, COMPLETE, NOT_REACHED, false);

		assertEquals(PairStatusResult.REJECTED_INVALID_STAGE, result);
		assertNotEquals("FAILED would ask the caller to retry a permanently invalid argument",
				PairStatusResult.FAILED, result);
		assertNotEquals(PairStatusResult.APPLIED, result);
		assertNotEquals(PairStatusResult.SUPERSEDED, result);
	}

	@Test
	public void aRejectedMembershipWriteChangesNothingDurable() throws Exception {
		Map<String, Object> before = durableState();

		assertEquals(PairStatusResult.REJECTED_INVALID_STAGE,
				JobPairs.setPairStatusPreciseResult(
						PAIR_ID, ABSENT_STAGE, COMPLETE, NOT_REACHED, false));

		assertEquals("no durable value may change", before, durableState());
	}

	/**
	 * Specifically the pair-level side effects, which are what the stage updates missing
	 * cannot undo: an {@code end_time} or a completion row puts the pair beyond
	 * {@code RERUN_FAILED_PAIRS} even with its stage history untouched.
	 */
	@Test
	public void aRejectedMembershipWriteLeavesNoEndTimeAndNoCompletionRow() throws Exception {
		assertNull("precondition: the fixture starts with no end_time", endTime(PAIR_ID));
		assertEquals(0, completionRows(PAIR_ID));

		JobPairs.setPairStatusPreciseResult(PAIR_ID, ABSENT_STAGE, COMPLETE, NOT_REACHED, false);

		assertNull("a refused write must not stamp an end_time", endTime(PAIR_ID));
		assertEquals("a refused write must not insert a completion row",
				0, completionRows(PAIR_ID));
		assertEquals("and must not move the pair's own status", ENQUEUED, pairStatus(PAIR_ID));
	}

	/** An extreme positive value is the same defect, and is the shape an overflowed parse produces. */
	@Test
	public void anExtremePositiveStageIsRejected() throws Exception {
		Map<String, Object> before = durableState();

		assertEquals(PairStatusResult.REJECTED_INVALID_STAGE,
				JobPairs.setPairStatusPreciseResult(
						PAIR_ID, Integer.MAX_VALUE, COMPLETE, NOT_REACHED, false));

		assertEquals(before, durableState());
	}

	/** Range invalidity still behaves as #152 requires, now that membership is checked too. */
	@Test
	public void stageZeroAndNegativesAreStillRejected() throws Exception {
		Map<String, Object> before = durableState();

		assertEquals(PairStatusResult.REJECTED_INVALID_STAGE,
				JobPairs.setPairStatusPreciseResult(PAIR_ID, 0, COMPLETE, NOT_REACHED, false));
		assertEquals(PairStatusResult.REJECTED_INVALID_STAGE,
				JobPairs.setPairStatusPreciseResult(PAIR_ID, -1, COMPLETE, NOT_REACHED, false));

		assertEquals(before, durableState());
	}

	// --------------------------------------------------------------- sparse stage sets

	/**
	 * Both directions of the gap, which is what makes a range test wrong rather than merely
	 * imprecise: stage 2 is within "1..the highest stage" and must be refused, and stage 3 is
	 * beyond "the number of stages" and must be accepted.
	 */
	@Test
	public void sparseStages() throws Exception {
		assertEquals("a gap is not a stage of this pair",
				PairStatusResult.REJECTED_INVALID_STAGE,
				JobPairs.setPairStatusPreciseResult(
						SPARSE_PAIR_ID, 2, COMPLETE, NOT_REACHED, false));

		assertEquals("a sparse tail is a real stage and must still be written",
				PairStatusResult.APPLIED,
				JobPairs.setPairStatusPreciseResult(
						SPARSE_PAIR_ID, 3, COMPLETE, NOT_REACHED, false));
		assertEquals(COMPLETE, stageStatus(SPARSE_PAIR_ID, 3));
	}

	// ------------------------------------------------------------- the positive controls

	/**
	 * Without this the membership check could refuse everything and every test above would
	 * still pass. A valid later stage must behave exactly as it always has.
	 */
	@Test
	public void aValidLaterStageStillAppliesTheWholeSemantics() throws Exception {
		assertEquals(PairStatusResult.APPLIED, JobPairs.setPairStatusPreciseResult(
				PAIR_ID, 2, COMPLETE, NOT_REACHED, false));

		assertEquals("an earlier stage keeps its own recorded result",
				STAGE_1_STATUS, stageStatus(PAIR_ID, 1));
		assertEquals("the named stage takes the terminal status",
				COMPLETE, stageStatus(PAIR_ID, 2));
		assertEquals("stages after it are not reached", NOT_REACHED, stageStatus(PAIR_ID, 3));
		assertEquals("the pair goes terminal", COMPLETE, pairStatus(PAIR_ID));
		assertTrue("and is stamped", endTime(PAIR_ID) != null);
		assertEquals("exactly once", 1, completionRows(PAIR_ID));
	}

	@Test
	public void aValidFirstStageStillApplies() throws Exception {
		assertEquals(PairStatusResult.APPLIED, JobPairs.setPairStatusPreciseResult(
				PAIR_ID, 1, COMPLETE, NOT_REACHED, false));

		assertEquals(COMPLETE, stageStatus(PAIR_ID, 1));
		assertEquals(NOT_REACHED, stageStatus(PAIR_ID, 2));
		assertEquals(NOT_REACHED, stageStatus(PAIR_ID, 3));
		assertEquals(COMPLETE, pairStatus(PAIR_ID));
	}

	// ------------------------------------------------------------------------ helpers

	private Map<String, Object> durableState() throws SQLException {
		Map<String, Object> state = new LinkedHashMap<>();
		state.put("pair.status", pairStatus(PAIR_ID));
		state.put("pair.end_time", String.valueOf(endTime(PAIR_ID)));
		for (int stage = 1; stage <= 3; stage++) {
			state.put("stage." + stage, stageStatus(PAIR_ID, stage));
		}
		state.put("completion.rows", completionRows(PAIR_ID));
		state.put("job.completed", jobCompleted());
		return state;
	}

	private int pairStatus(int pair) throws SQLException {
		return intQuery("SELECT status_code FROM starexec.job_pairs WHERE id=" + pair);
	}

	private int stageStatus(int pair, int stage) throws SQLException {
		return intQuery("SELECT status_code FROM starexec.jobpair_stage_data WHERE jobpair_id="
				+ pair + " AND stage_number=" + stage);
	}

	private int completionRows(int pair) throws SQLException {
		return intQuery("SELECT COUNT(*) FROM starexec.job_pair_completion WHERE pair_id=" + pair);
	}

	private String endTime(int pair) throws SQLException {
		return stringQuery("SELECT end_time FROM starexec.job_pairs WHERE id=" + pair);
	}

	private String jobCompleted() throws SQLException {
		return String.valueOf(
				stringQuery("SELECT completed FROM starexec.jobs WHERE id=" + JOB_ID));
	}

	private int intQuery(String sql) throws SQLException {
		try (Connection con = Common.getConnection();
			 Statement s = con.createStatement();
			 ResultSet rs = s.executeQuery(sql)) {
			assertTrue("expected a row from: " + sql, rs.next());
			return rs.getInt(1);
		}
	}

	private String stringQuery(String sql) throws SQLException {
		try (Connection con = Common.getConnection();
			 Statement s = con.createStatement();
			 ResultSet rs = s.executeQuery(sql)) {
			assertTrue("expected a row from: " + sql, rs.next());
			return rs.getString(1);
		}
	}

	private static void quietly(Statement s, String sql) {
		try {
			s.execute(sql);
		} catch (SQLException ignored) {
			// Teardown of rows that may not exist is not a test result.
		}
	}
}
