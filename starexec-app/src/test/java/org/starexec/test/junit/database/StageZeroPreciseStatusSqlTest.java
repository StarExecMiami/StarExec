package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.constants.R;
import org.starexec.data.database.Common;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code UpdatePairStatusPrecise} against a real database, on the one argument that decides
 * which rows it touches.
 *
 * <h2>Why the routine needs its own guard</h2>
 *
 * {@code JobPairs.setPairStatusPreciseResult} refuses a stage number below 1 before it opens a
 * connection, and every production write goes through it. The routine is nevertheless reachable
 * without it -- from {@code psql}, from an administrative session, from
 * {@code StoredRoutineSmokeIT}, and from any caller added later -- so the invariant is enforced
 * in both places and tested in both places.
 *
 * <h2>What the routine does with stage 0</h2>
 *
 * The two updates are keyed on {@code = _stageNumber} and {@code > _stageNumber}. Stage numbers
 * start at 1, so 0 gives the terminal status to nothing and NOT_REACHED to every stage the pair
 * has. {@code negativeControl_theRawUpdateHitsEveryStage} demonstrates that mechanism directly
 * against this fixture, so the tests below assert against a destruction that has been shown to
 * happen rather than one taken on trust.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*}
 * variables at a <strong>disposable</strong> database.
 */
public class StageZeroPreciseStatusSqlTest extends Common {

	private static final String TAG = "stage-zero-probe";

	private static final int USER_ID = 91201;
	private static final int SPACE_ID = 91201;
	private static final int QUEUE_ID = 91201;
	private static final int SOLVER_ID = 91201;
	private static final int CONFIG_ID = 91201;
	private static final int BENCH_ID = 91201;
	private static final int JOB_ID = 91201;
	private static final int PAIR_ID = 91201;

	private static final int ENQUEUED = StatusCode.STATUS_ENQUEUED.getVal();
	private static final int COMPLETE = StatusCode.STATUS_COMPLETE.getVal();
	private static final int EXCEED_CPU = StatusCode.EXCEED_CPU.getVal();
	private static final int NOT_REACHED = StatusCode.STATUS_NOT_REACHED.getVal();

	/** Deliberately distinct per stage, so "everything became NOT_REACHED" is visible. */
	private static final int STAGE_1_STATUS = COMPLETE;
	private static final int STAGE_2_STATUS = EXCEED_CPU;
	private static final int STAGE_3_STATUS = ENQUEUED;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("StageZeroPreciseStatusSqlTest");
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
					+ "'," + QUEUE_ID + ",NOW(),'d',1,0)");
			s.execute("INSERT INTO starexec.job_pairs (id,job_id,bench_id,status_code) VALUES ("
					+ PAIR_ID + "," + JOB_ID + "," + BENCH_ID + "," + ENQUEUED + ")");
			int[] statuses = {STAGE_1_STATUS, STAGE_2_STATUS, STAGE_3_STATUS};
			for (int stage = 1; stage <= 3; stage++) {
				s.execute("INSERT INTO starexec.jobpair_stage_data (jobpair_id,stage_number,"
						+ "status_code,solver_id,config_id,disk_size) VALUES (" + PAIR_ID + ","
						+ stage + "," + statuses[stage - 1] + "," + SOLVER_ID + ","
						+ CONFIG_ID + ",0)");
			}
		}
	}

	@After
	public void cleanUp() throws SQLException {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			quietly(s, "DELETE FROM starexec.job_pair_repro_manifests WHERE pair_id=" + PAIR_ID);
			quietly(s, "DELETE FROM starexec.job_pair_completion WHERE pair_id=" + PAIR_ID);
			quietly(s, "DELETE FROM starexec.jobpair_stage_data WHERE jobpair_id=" + PAIR_ID);
			quietly(s, "DELETE FROM starexec.job_pairs WHERE id=" + PAIR_ID);
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
	 * The mechanism, shown rather than asserted from the SQL text: with a stage number of 0,
	 * {@code stage_number > 0} selects every stage this pair has. Rolled back, so it proves the
	 * destruction without leaving it behind for the tests below.
	 */
	@Test
	public void negativeControl_theRawUpdateHitsEveryStage() throws Exception {
		// A connection of its own, for the same reason assertRefused takes one: this turns
		// autoCommit off and rolls back, and a pooled connection handed back in that state is
		// reported by other tests in this suite as a contaminated pool.
		try (Connection con = DriverManager.getConnection(
				R.POSTGRES_URL, R.POSTGRES_USERNAME, R.POSTGRES_PASSWORD)) {
			con.setAutoCommit(false);
			try (Statement s = con.createStatement()) {
				int rows = s.executeUpdate(
						"UPDATE starexec.jobpair_stage_data SET status_code = " + NOT_REACHED
								+ " WHERE jobpair_id = " + PAIR_ID + " AND stage_number > 0");
				assertEquals("stage_number > 0 selects every stage, which is the defect", 3, rows);

				int targeted = s.executeUpdate(
						"UPDATE starexec.jobpair_stage_data SET status_code = " + COMPLETE
								+ " WHERE jobpair_id = " + PAIR_ID + " AND stage_number = 0");
				assertEquals("...while stage_number = 0 selects none, so nothing takes the"
						+ " status", 0, targeted);
			}
			con.rollback();
		}
		assertEquals("the control must leave the fixture as it found it",
				STAGE_1_STATUS, stageStatus(1));
	}

	// ------------------------------------------------------------------ the invariant

	@Test
	public void stageZeroIsRefusedAndChangesNothing() throws Exception {
		Map<String, Object> before = durableState();

		assertRefused(0);

		assertEquals("no durable value may change", before, durableState());
	}

	@Test
	public void aNegativeStageIsRefusedAndChangesNothing() throws Exception {
		Map<String, Object> before = durableState();

		assertRefused(-1);

		assertEquals("no durable value may change", before, durableState());
	}

	/**
	 * The refusal must land before the pair-level side effects, not merely before the stage
	 * updates: an {@code end_time} or a completion row would put the pair beyond
	 * {@code RERUN_FAILED_PAIRS} even with its stage history intact.
	 */
	@Test
	public void aRefusedWriteLeavesNoEndTimeAndNoCompletionRow() throws Exception {
		assertNull("precondition: the fixture starts with no end_time", endTime());
		assertEquals(0, completionRows());

		assertRefused(0);

		assertNull("a refused write must not stamp an end_time", endTime());
		assertEquals("a refused write must not insert a completion row", 0, completionRows());
	}

	// ------------------------------------------------------------- the positive control

	/**
	 * The control that stops this being "the routine was disabled": a stage number that names a
	 * stage must still do exactly what it did before -- that stage takes the status, later
	 * stages become NOT_REACHED, earlier ones are untouched, and the pair completes.
	 */
	@Test
	public void aValidStageStillAppliesTheFullSemantics() throws Exception {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			try (ResultSet rs = s.executeQuery("SELECT starexec.UpdatePairStatusPrecise("
					+ PAIR_ID + ",2," + COMPLETE + "," + NOT_REACHED + ")")) {
				assertTrue(rs.next());
				assertTrue("a valid stage must be applied", rs.getBoolean(1));
			}
		}

		assertEquals("an earlier stage keeps its own result", STAGE_1_STATUS, stageStatus(1));
		assertEquals("the named stage takes the status", COMPLETE, stageStatus(2));
		assertEquals("a later stage becomes not reached", NOT_REACHED, stageStatus(3));
		assertEquals("the pair takes the status", COMPLETE, pairStatus());
		assertNotNull("a terminal pair carries an end_time", endTime());
		assertEquals(1, completionRows());
	}

	// ----------------------------------------------------------------------- helpers

	/**
	 * Uses a connection of its own rather than a pooled one.
	 *
	 * <p>The refusal aborts the transaction, and a pooled connection handed back in that state
	 * contaminates whichever test borrows it next -- the suite has its own guards that detect
	 * exactly this and report it as "an earlier caller returned it mid-transaction". Owning the
	 * connection outright keeps a test about the routine from becoming a test about the pool.
	 */
	private void assertRefused(int stageNumber) throws Exception {
		try (Connection con = DriverManager.getConnection(
					R.POSTGRES_URL, R.POSTGRES_USERNAME, R.POSTGRES_PASSWORD);
			 Statement s = con.createStatement()) {
			s.executeQuery("SELECT starexec.UpdatePairStatusPrecise(" + PAIR_ID + ","
					+ stageNumber + "," + COMPLETE + "," + NOT_REACHED + ")");
			fail("stage number " + stageNumber + " names no stage and must be refused");
		} catch (SQLException expected) {
			assertEquals("the routine must report an invalid parameter, so a caller can tell"
							+ " this apart from a transient failure",
					"22023", expected.getSQLState());
		}
	}

	/** Every column the routine is able to mutate, read back in one place. */
	private Map<String, Object> durableState() throws SQLException {
		Map<String, Object> state = new LinkedHashMap<>();
		state.put("pair.status_code", pairStatus());
		state.put("pair.end_time", String.valueOf(endTime()));
		for (int stage = 1; stage <= 3; stage++) {
			state.put("stage." + stage + ".status_code", stageStatus(stage));
		}
		state.put("completion.rows", completionRows());
		state.put("job.completed", jobCompleted());
		return state;
	}

	private int pairStatus() throws SQLException {
		return intQuery("SELECT status_code FROM starexec.job_pairs WHERE id=" + PAIR_ID);
	}

	private int stageStatus(int stage) throws SQLException {
		return intQuery("SELECT status_code FROM starexec.jobpair_stage_data WHERE jobpair_id="
				+ PAIR_ID + " AND stage_number=" + stage);
	}

	private int completionRows() throws SQLException {
		return intQuery("SELECT COUNT(*) FROM starexec.job_pair_completion WHERE pair_id="
				+ PAIR_ID);
	}

	private String endTime() throws SQLException {
		return stringQuery("SELECT end_time FROM starexec.job_pairs WHERE id=" + PAIR_ID);
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
