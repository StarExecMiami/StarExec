package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.backend.Backend;
import org.starexec.data.database.Common;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.Jobs;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.SolverBuildStatus.SolverBuildStatusCode;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/**
 * The periodic broken-pair sweep, {@link Jobs#setBrokenPairsToErrorStatus(Backend)}, and the
 * query that feeds it.
 *
 * <p>{@code GetJobPairsWithStatus} joined {@code jobpair_stage_data} on
 * {@code jobpair_id = primary_jobpair_data}. That column holds the primary stage's number, so
 * each pair was joined to the stage rows of whichever pair had that number as its id: its
 * solver and configuration columns came from an unrelated pair, and it was returned once per
 * stage row of that pair.
 *
 * <p>For a build job the sweep marked the pair's solver {@code BUILD_FAILED} through
 * {@code p.getPrimarySolver()}. The pairs come from {@link JobPairs#getPairsInBackend()}, which
 * loads no stages, so the solver was null. {@code Solvers.setSolverBuildStatus} caught the
 * resulting NullPointerException and logged it, and the solver kept its old build status.
 *
 * <p>The fixture is one build job with one enqueued two-stage pair whose primary stage is 2.
 * Each stage has its own solver and configuration, so a result taken from the wrong stage
 * shows.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*} variables
 * at a <strong>disposable</strong> database: these tests insert and delete rows.
 */
public class BrokenPairsInBackendSqlTest extends Common {

	private static final int EXEC_ID = 882001;

	private int userId;
	private int jobId;
	private int spaceId;
	private int benchId;
	private int stage1SolverId;
	private int stage2SolverId;
	private int stage1ConfigId;
	private int stage2ConfigId;
	private int pairId;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("BrokenPairsInBackendSqlTest");
		Common.initialize();
	}

	@Before
	public void seed() throws SQLException {
		try (Connection con = Common.getConnection()) {
			userId = selectInt(con, "SELECT min(id) FROM starexec.users");
			jobId = insertReturningId(con,
					"INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size, buildJob)"
							+ " VALUES (?, 'broken-pairs-test', 1, 0, TRUE) RETURNING id",
					userId);
			spaceId = insertReturningId(con,
					"INSERT INTO starexec.job_spaces (job_id, name) VALUES (?, 'root') RETURNING id",
					jobId);
			benchId = insertReturningId(con,
					"INSERT INTO starexec.benchmarks (user_id, name, uploaded, path, disk_size)"
							+ " VALUES (?, 'broken-pairs-bench', NOW(), '/b', 0) RETURNING id",
					userId);
			stage1SolverId = insertUnbuiltSolver(con, "broken-pairs-solver-1");
			stage2SolverId = insertUnbuiltSolver(con, "broken-pairs-solver-2");
			stage1ConfigId = insertConfig(con, stage1SolverId, "broken-pairs-c1");
			stage2ConfigId = insertConfig(con, stage2SolverId, "broken-pairs-c2");

			pairId = insertReturningId(con,
					"INSERT INTO starexec.job_pairs (job_id, job_space_id, bench_id, status_code,"
							+ " sge_id, primary_jobpair_data) VALUES (?, ?, ?, ?, ?, 2) RETURNING id",
					jobId, spaceId, benchId, StatusCode.STATUS_ENQUEUED.getVal(), EXEC_ID);
			insertStage(con, 1, stage1SolverId, stage1ConfigId);
			insertStage(con, 2, stage2SolverId, stage2ConfigId);
		}
	}

	@After
	public void dropFixture() throws SQLException {
		if (jobId == 0) {
			return;
		}
		try (Connection con = Common.getConnection()) {
			// jobpair_stage_data cascades from job_pairs; configurations from solvers.
			update(con, "DELETE FROM starexec.job_pairs WHERE job_id = ?", jobId);
			update(con, "DELETE FROM starexec.job_spaces WHERE id = ?", spaceId);
			update(con, "DELETE FROM starexec.solvers WHERE id = ?", stage1SolverId);
			update(con, "DELETE FROM starexec.solvers WHERE id = ?", stage2SolverId);
			update(con, "DELETE FROM starexec.benchmarks WHERE id = ?", benchId);
			update(con, "DELETE FROM starexec.jobs WHERE id = ?", jobId);
		}
	}

	/** One row for the pair, carrying its own primary stage's solver and configuration. */
	@Test
	public void aPairIsListedOnceWithItsOwnPrimaryStage() throws SQLException {
		int rows = 0;
		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement(
						"SELECT solver_id, config_id FROM starexec.GetJobPairsWithStatus(?)"
								+ " WHERE id = ?")) {
			ps.setInt(1, StatusCode.STATUS_ENQUEUED.getVal());
			ps.setInt(2, pairId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					rows++;
					assertEquals("the primary stage's solver", stage2SolverId, rs.getInt("solver_id"));
					assertEquals("the primary stage's configuration", stage2ConfigId,
							rs.getInt("config_id"));
				}
			}
		}
		assertEquals("exactly one row per pair", 1, rows);

		long listed = JobPairs.getPairsByStatus(StatusCode.STATUS_ENQUEUED.getVal()).stream()
				.filter(p -> p.getId() == pairId)
				.count();
		assertEquals(1, listed);
	}

	/**
	 * A build pair the backend no longer runs is set to ERROR_SUBMIT_FAIL, and its primary
	 * stage's solver is marked BUILD_FAILED. The other stage's solver is left alone.
	 */
	@Test
	public void aBrokenBuildPairMarksItsPrimarySolverBuildFailed() throws Exception {
		Backend backend = org.mockito.Mockito.mock(Backend.class);
		org.mockito.Mockito.when(backend.getActiveExecutionIds())
				.thenReturn(execIdsOfOtherPairsInBackend());

		Jobs.setBrokenPairsToErrorStatus(backend);

		try (Connection con = Common.getConnection()) {
			assertEquals(StatusCode.ERROR_SUBMIT_FAIL.getVal(), selectInt(con,
					"SELECT status_code FROM starexec.job_pairs WHERE id = " + pairId));
			assertEquals("the primary stage's solver failed to build",
					SolverBuildStatusCode.BUILD_FAILED.getVal(), buildStatus(con, stage2SolverId));
			assertEquals("the other stage's solver is not this pair's primary",
					SolverBuildStatusCode.UNBUILT.getVal(), buildStatus(con, stage1SolverId));
		}
	}

	// ------------------------------------------------------------------------- helpers

	/**
	 * Every exec id the sweep could see except this fixture's, so the sweep touches no pair but
	 * ours. 0 is what a NULL sge_id reads as.
	 */
	private Set<Integer> execIdsOfOtherPairsInBackend() throws SQLException {
		Set<Integer> ids = new HashSet<>();
		ids.add(0);
		for (JobPair p : JobPairs.getPairsInBackend()) {
			if (p.getId() != pairId) {
				ids.add(p.getBackendExecId());
			}
		}
		return ids;
	}

	private int insertUnbuiltSolver(Connection con, String name) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.solvers (user_id, name, uploaded, path, disk_size, build_status)"
						+ " VALUES (?, ?, NOW(), '/s', 0, ?) RETURNING id")) {
			ps.setInt(1, userId);
			ps.setString(2, name);
			ps.setInt(3, SolverBuildStatusCode.UNBUILT.getVal());
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private static int insertConfig(Connection con, int solverId, String name)
			throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.configurations (solver_id, name, updated)"
						+ " VALUES (?, ?, NOW()) RETURNING id")) {
			ps.setInt(1, solverId);
			ps.setString(2, name);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private void insertStage(Connection con, int stage, int solverId, int configId)
			throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.jobpair_stage_data (jobpair_id, stage_number, status_code,"
						+ " solver_id, solver_name, config_id, config_name, disk_size)"
						+ " SELECT ?, ?, ?, ?, 'broken-pairs-solver', id, name, 0"
						+ " FROM starexec.configurations WHERE id = ?")) {
			ps.setInt(1, pairId);
			ps.setInt(2, stage);
			ps.setInt(3, StatusCode.STATUS_ENQUEUED.getVal());
			ps.setInt(4, solverId);
			ps.setInt(5, configId);
			assertEquals(1, ps.executeUpdate());
		}
	}

	private static int buildStatus(Connection con, int solverId) throws SQLException {
		return selectInt(con, "SELECT build_status FROM starexec.solvers WHERE id = " + solverId);
	}

	private static int insertReturningId(Connection con, String sql, int... params)
			throws SQLException {
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
