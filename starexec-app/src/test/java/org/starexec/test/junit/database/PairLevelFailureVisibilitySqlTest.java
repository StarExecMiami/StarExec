package org.starexec.test.junit.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.starexec.data.database.AnonymousLinks.PrimitivesToAnonymize;
import org.starexec.data.database.Common;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.database.Spaces;
import org.starexec.data.to.SolverStats;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A pair that fails outside any stage is shown, filtered and counted as the failure it is (#324).
 *
 * <p>The job script reports a failure before the first stage -- a missing benchmark dependency,
 * a workspace it could not set up -- with stage number 0. Every backend recorded that by giving
 * the pair its error and marking every stage STATUS_NOT_REACHED. The pair listings, the
 * complete/incomplete/failed filters and the solver statistics all read the stage, so a job whose
 * every pair failed that way showed "stage not reached (23)" on each row, counted every pair as
 * incomplete, and showed the real error nowhere.
 *
 * <p>Two halves: the write the backends now make, and V0118, which repairs rows written the old
 * way and must leave alone every shape it cannot attribute unambiguously.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*} variables
 * at a <strong>disposable</strong> database: these tests insert and delete rows.
 */
public class PairLevelFailureVisibilitySqlTest extends Common {

	private static final int DEPENDENCY_MISSING = StatusCode.ERROR_BENCH_DEPENDENCY_MISSING.getVal();
	private static final int RUNSCRIPT = StatusCode.ERROR_RUNSCRIPT.getVal();
	private static final int NOT_REACHED = StatusCode.STATUS_NOT_REACHED.getVal();
	private static final int COMPLETE = StatusCode.STATUS_COMPLETE.getVal();
	private static final int RUNNING = StatusCode.STATUS_RUNNING.getVal();

	private int userId;
	private int jobId;
	private int spaceId;
	private int solverId;
	private int configId;
	private int benchId;

	@BeforeAll
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("PairLevelFailureVisibilitySqlTest");
		Common.initialize();
	}

	@BeforeEach
	public void seed() throws SQLException {
		try (Connection con = Common.getConnection()) {
			userId = selectInt(con, "SELECT min(id) FROM starexec.users");
			jobId = returningId(con,
					"INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size)"
							+ " VALUES (?, 'pair-level-visibility-test', 1, 0) RETURNING id",
					userId);
			spaceId = returningId(con,
					"INSERT INTO starexec.job_spaces (job_id, name) VALUES (?, 'root') RETURNING id",
					jobId);
			solverId = returningId(con,
					"INSERT INTO starexec.solvers (user_id, name, path, disk_size)"
							+ " VALUES (?, 'pair-level-solver', '/s', 0) RETURNING id",
					userId);
			configId = returningId(con,
					"INSERT INTO starexec.configurations (solver_id, name, updated)"
							+ " VALUES (?, 'pair-level-config', NOW()) RETURNING id",
					solverId);
			benchId = returningId(con,
					"INSERT INTO starexec.benchmarks (user_id, name, uploaded, path, disk_size)"
							+ " VALUES (?, 'pair-level-bench', NOW(), '/b', 0) RETURNING id",
					userId);
		}
	}

	@AfterEach
	public void dropFixture() throws SQLException {
		if (jobId == 0) {
			return;
		}
		try (Connection con = Common.getConnection()) {
			update(con, "DELETE FROM starexec.job_pair_completion WHERE pair_id IN"
					+ " (SELECT id FROM starexec.job_pairs WHERE job_id = ?)", jobId);
			update(con, "DELETE FROM starexec.job_pairs WHERE job_id = ?", jobId);
			update(con, "DELETE FROM starexec.job_spaces WHERE id = ?", spaceId);
			update(con, "DELETE FROM starexec.solvers WHERE id = ?", solverId);
			update(con, "DELETE FROM starexec.benchmarks WHERE id = ?", benchId);
			update(con, "DELETE FROM starexec.jobs WHERE id = ?", jobId);
		}
	}

	/** The write the backends now make: the stages carry the pair's own error. */
	@Test
	public void aPairLevelFailureIsCountedAsFailedNotIncomplete() throws SQLException {
		int pair = pairWithStages(RUNNING, RUNNING, RUNNING);

		assertEquals(PairStatusResult.APPLIED,
				JobPairs.setPairLevelStatusResult(pair, DEPENDENCY_MISSING, DEPENDENCY_MISSING));

		assertEquals(List.of(DEPENDENCY_MISSING, DEPENDENCY_MISSING), stageStatuses(pair));
		String row = "config=" + configId + " failed=1 incomplete=0";
		assertEquals(List.of(row), summary(1));
		assertEquals(List.of(row), summary(0), "the Primary view too");
	}

	/** A stage that had already finished keeps its result under a later pair-level failure. */
	@Test
	public void aFinishedStageKeepsItsResult() throws SQLException {
		int pair = pairWithStages(RUNNING, COMPLETE, RUNNING);

		assertEquals(PairStatusResult.APPLIED,
				JobPairs.setPairLevelStatusResult(pair, RUNSCRIPT, RUNSCRIPT));

		assertEquals(List.of(COMPLETE, RUNSCRIPT), stageStatuses(pair));
	}

	/** V0118 repairs the shape the old write left: an error on the pair, NOT_REACHED on every stage. */
	@Test
	public void theMigrationRepairsAPairWhoseStagesAreAllNotReached() throws Exception {
		int pair = pairWithStages(DEPENDENCY_MISSING, NOT_REACHED, NOT_REACHED);

		applyV0118();

		assertEquals(List.of(DEPENDENCY_MISSING, DEPENDENCY_MISSING), stageStatuses(pair));
		assertEquals(List.of("config=" + configId + " failed=1 incomplete=0"), summary(0));
	}

	/**
	 * Every shape V0118 cannot attribute unambiguously is left exactly as it was: a stage-level
	 * failure, a failure between two stages, and a pair that did not fail.
	 */
	@Test
	public void theMigrationLeavesEveryOtherShapeAlone() throws Exception {
		int stageLevel = pairWithStages(RUNSCRIPT, RUNSCRIPT, NOT_REACHED);
		int betweenStages = pairWithStages(RUNSCRIPT, COMPLETE, NOT_REACHED);
		int killed = pairWithStages(StatusCode.STATUS_KILLED.getVal(), NOT_REACHED, NOT_REACHED);

		applyV0118();

		assertEquals(List.of(RUNSCRIPT, NOT_REACHED), stageStatuses(stageLevel));
		assertEquals(List.of(COMPLETE, NOT_REACHED), stageStatuses(betweenStages));
		assertEquals(List.of(NOT_REACHED, NOT_REACHED), stageStatuses(killed));
	}

	/** Idempotent, as a migration Flyway records once must be when it is replayed by hand. */
	@Test
	public void theMigrationIsIdempotent() throws Exception {
		int pair = pairWithStages(DEPENDENCY_MISSING, NOT_REACHED, NOT_REACHED);

		applyV0118();
		applyV0118();

		assertEquals(List.of(DEPENDENCY_MISSING, DEPENDENCY_MISSING), stageStatuses(pair));
	}

	private int pairWithStages(int pairStatus, int stageOne, int stageTwo) throws SQLException {
		try (Connection con = Common.getConnection()) {
			int pair = returningId(con,
					"INSERT INTO starexec.job_pairs (job_id, job_space_id, bench_id, bench_name,"
							+ " status_code, primary_jobpair_data)"
							+ " VALUES (?, ?, ?, 'pair-level-bench', ?, 1) RETURNING id",
					jobId, spaceId, benchId, pairStatus);
			insertStage(con, pair, 1, stageOne);
			insertStage(con, pair, 2, stageTwo);
			return pair;
		}
	}

	private void insertStage(Connection con, int pairId, int stage, int status) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.jobpair_stage_data (jobpair_id, stage_number, status_code,"
						+ " solver_id, solver_name, config_id, config_name, wallclock, cpu, disk_size)"
						+ " VALUES (?, ?, ?, ?, 'pair-level-solver', ?, 'pair-level-config', 0, 0, 0)")) {
			ps.setInt(1, pairId);
			ps.setInt(2, stage);
			ps.setInt(3, status);
			ps.setInt(4, solverId);
			ps.setInt(5, configId);
			assertEquals(1, ps.executeUpdate());
		}
	}

	private static List<Integer> stageStatuses(int pairId) throws SQLException {
		List<Integer> statuses = new ArrayList<>();
		try (Connection con = Common.getConnection();
		     PreparedStatement ps = con.prepareStatement(
				     "SELECT status_code FROM starexec.jobpair_stage_data WHERE jobpair_id = ?"
						     + " ORDER BY stage_number")) {
			ps.setInt(1, pairId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					statuses.add(rs.getInt(1));
				}
			}
		}
		return statuses;
	}

	private List<String> summary(int stageNumber) {
		Collection<SolverStats> stats = Jobs.getAllJobStatsInJobSpaceHierarchyIncludeDeletedConfigs(
				Spaces.getJobSpace(spaceId), stageNumber, PrimitivesToAnonymize.NONE, false);
		assertNotNull(stats, "the job space must have a summary");
		List<String> rows = new ArrayList<>();
		for (SolverStats s : stats) {
			rows.add("config=" + s.getConfiguration().getId() + " failed=" + s.getFailedJobPairs()
					+ " incomplete=" + s.getIncompleteJobPairs());
		}
		rows.sort(null);
		return rows;
	}

	/** Runs the migration's own bytes, so the test cannot drift from what Flyway applies. */
	private static void applyV0118() throws IOException, SQLException {
		String sql;
		try (InputStream in = PairLevelFailureVisibilitySqlTest.class.getClassLoader()
				.getResourceAsStream("db/migration/V0118__pair_level_failures_reach_their_stages.sql")) {
			assertNotNull(in, "V0118 must be on the classpath");
			sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		try (Connection con = Common.getConnection(); Statement st = con.createStatement()) {
			st.execute(sql);
		}
	}

	private static int returningId(Connection con, String sql, int... params) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			for (int i = 0; i < params.length; i++) {
				ps.setInt(i + 1, params[i]);
			}
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) {
					throw new IllegalStateException("No id returned: " + sql);
				}
				return rs.getInt(1);
			}
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

	private static void update(Connection con, String sql, int param) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			ps.setInt(1, param);
			ps.executeUpdate();
		}
	}
}
