package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.constants.R;
import org.starexec.data.database.AnonymousLinks.PrimitivesToAnonymize;
import org.starexec.data.database.Common;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Spaces;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.SolverStats;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * A job space whose pairs are not all finished still has a solver summary (#206).
 *
 * <p>{@code GetJobPairsInJobSpaceHierarchy} LEFT JOINs {@code job_pair_completion}, so a pair that
 * has not finished -- pending, enqueued, running -- comes back with a NULL {@code completion_id}.
 * {@code Jobs.processStatResults} passed that to {@code JobPair.setCompletionId(int)}, which threw
 * {@code NullPointerException} unboxing it; {@code getJobPairsInJobSpaceHierarchy} caught it and
 * returned null, and the stats built from that were empty. One unfinished pair emptied the summary
 * of its whole job space, which is to say every running job's.
 *
 * <p>The fixture is one completed pair that solved its benchmark and one pending pair, run by the
 * same configuration: the summary must show the solved pair and count the pending one as
 * incomplete, on stage 1 and on the Primary view.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*} variables
 * at a <strong>disposable</strong> database: these tests insert and delete rows.
 */
public class UnfinishedPairStatsSqlTest extends Common {

	private int userId;
	private int jobId;
	private int spaceId;
	private int solverId;
	private int configId;
	private int benchId;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("UnfinishedPairStatsSqlTest");
		Common.initialize();
	}

	@Before
	public void seed() throws SQLException {
		try (Connection con = Common.getConnection()) {
			userId = selectInt(con, "SELECT min(id) FROM starexec.users");
			jobId = returningId(con,
					"INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size)"
							+ " VALUES (?, 'unfinished-pair-stats-test', 2, 0) RETURNING id",
					userId);
			spaceId = returningId(con,
					"INSERT INTO starexec.job_spaces (job_id, name) VALUES (?, 'root') RETURNING id",
					jobId);
			solverId = returningId(con,
					"INSERT INTO starexec.solvers (user_id, name, path, disk_size)"
							+ " VALUES (?, 'unfinished-pair-solver', '/s', 0) RETURNING id",
					userId);
			configId = returningId(con,
					"INSERT INTO starexec.configurations (solver_id, name, updated)"
							+ " VALUES (?, 'unfinished-pair-config', NOW()) RETURNING id",
					solverId);
			benchId = returningId(con,
					"INSERT INTO starexec.benchmarks (user_id, name, uploaded, path, disk_size)"
							+ " VALUES (?, 'unfinished-pair-bench', NOW(), '/b', 0) RETURNING id",
					userId);
			try (PreparedStatement ps = con.prepareStatement(
					"INSERT INTO starexec.bench_attributes (bench_id, attr_key, attr_value)"
							+ " VALUES (?, ?, 'Theorem')")) {
				ps.setInt(1, benchId);
				ps.setString(2, R.EXPECTED_RESULT);
				ps.executeUpdate();
			}

			int complete = StatusCode.STATUS_COMPLETE.getVal();
			int finished = returningId(con,
					"INSERT INTO starexec.job_pairs (job_id, job_space_id, bench_id, bench_name,"
							+ " status_code, primary_jobpair_data)"
							+ " VALUES (?, ?, ?, 'unfinished-pair-bench', ?, 1) RETURNING id",
					jobId, spaceId, benchId, complete);
			insertStage(con, finished, complete, "Theorem");
			returningId(con,
					"INSERT INTO starexec.job_pair_completion (pair_id) VALUES (?) RETURNING completion_id",
					finished);

			// Not yet run: no result, and no completion row, as for any pair still owed work.
			int pendingStatus = StatusCode.STATUS_PENDING_SUBMIT.getVal();
			int pending = returningId(con,
					"INSERT INTO starexec.job_pairs (job_id, job_space_id, bench_id, bench_name,"
							+ " status_code, primary_jobpair_data)"
							+ " VALUES (?, ?, ?, 'unfinished-pair-bench', ?, 1) RETURNING id",
					jobId, spaceId, benchId, pendingStatus);
			insertStage(con, pending, pendingStatus, null);
		}
	}

	@After
	public void dropFixture() throws SQLException {
		if (jobId == 0) {
			return;
		}
		try (Connection con = Common.getConnection()) {
			// Stage data, attributes, closure rows and cached stats go with their pair and space.
			update(con, "DELETE FROM starexec.job_stats WHERE job_space_id = ?", spaceId);
			update(con, "DELETE FROM starexec.job_pair_completion WHERE pair_id IN"
					+ " (SELECT id FROM starexec.job_pairs WHERE job_id = ?)", jobId);
			update(con, "DELETE FROM starexec.job_pairs WHERE job_id = ?", jobId);
			update(con, "DELETE FROM starexec.job_spaces WHERE id = ?", spaceId);
			update(con, "DELETE FROM starexec.solvers WHERE id = ?", solverId);
			update(con, "DELETE FROM starexec.benchmarks WHERE id = ?", benchId);
			update(con, "DELETE FROM starexec.jobs WHERE id = ?", jobId);
		}
	}

	/** Both pairs load, the unfinished one without a completion id. */
	@Test
	public void anUnfinishedPairLoadsBesideAFinishedOne() {
		List<JobPair> pairs = Jobs.getJobPairsInJobSpaceHierarchy(spaceId, PrimitivesToAnonymize.NONE);
		assertNotNull("one unfinished pair must not stop the space's pairs loading", pairs);
		assertEquals(2, pairs.size());
	}

	@Test
	public void theSummaryCountsTheFinishedPairAndTheUnfinishedOne() {
		String row = "config=" + configId + " solved=1/1 wrong=0 unknown=0 incomplete=1";
		assertEquals(List.of(row), summary(1));
		assertEquals("the Primary view too", List.of(row), summary(0));
	}

	private List<String> summary(int stageNumber) {
		Collection<SolverStats> stats = Jobs.getAllJobStatsInJobSpaceHierarchyIncludeDeletedConfigs(
				Spaces.getJobSpace(spaceId), stageNumber, PrimitivesToAnonymize.NONE, false);
		List<String> rows = new ArrayList<>();
		for (SolverStats s : stats) {
			rows.add("config=" + s.getConfiguration().getId() + " solved=" + s.getCorrectOverCompleted()
					+ " wrong=" + s.getIncorrectJobPairs() + " unknown=" + s.getUnknown()
					+ " incomplete=" + s.getIncompleteJobPairs());
		}
		rows.sort(null);
		return rows;
	}

	private void insertStage(Connection con, int pairId, int status, String result)
			throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.jobpair_stage_data (jobpair_id, stage_number, status_code,"
						+ " solver_id, solver_name, config_id, config_name, wallclock, cpu, disk_size)"
						+ " VALUES (?, 1, ?, ?, 'unfinished-pair-solver', ?, 'unfinished-pair-config',"
						+ " 1, 1, 0)")) {
			ps.setInt(1, pairId);
			ps.setInt(2, status);
			ps.setInt(3, solverId);
			ps.setInt(4, configId);
			assertEquals(1, ps.executeUpdate());
		}
		if (result == null) {
			return;
		}
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.job_attributes (pair_id, attr_key, attr_value, job_id, stage_number)"
						+ " VALUES (?, ?, ?, ?, 1)")) {
			ps.setInt(1, pairId);
			ps.setString(2, R.STAREXEC_RESULT);
			ps.setString(3, result);
			ps.setInt(4, jobId);
			assertEquals(1, ps.executeUpdate());
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
