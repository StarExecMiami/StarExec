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
 * The job page's Primary solver summary (stage 0) is built from each pair's primary stage (#206).
 *
 * <p>{@code GetJobPairsInJobSpaceHierarchy} returned {@code job_pair_completion.primary_jobpair_data},
 * a column V0017 added and nothing writes, instead of {@code job_pairs.primary_jobpair_data}. Every
 * pair's primary stage therefore read as 0, {@code Jobs.processPairsToSolverStats} built no stage 0
 * row, and the Primary view -- the job page's default -- was empty for every job.
 *
 * <p>The fixture makes the primary stage something other than the first, so a fix that merely
 * defaulted to stage 1 would still fail: one completed two-stage pair whose primary stage is 2.
 * Stage 1 is run by one configuration and answers wrongly; stage 2 by another and answers
 * correctly. The Primary summary must hold exactly the stage 2 configuration, with stage 2's
 * counts.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*} variables
 * at a <strong>disposable</strong> database: these tests insert and delete rows.
 */
public class PrimaryStageSummarySqlTest extends Common {

	private int userId;
	private int jobId;
	private int spaceId;
	private int solverId;
	private int stageOneConfig;
	private int stageTwoConfig;
	private int benchId;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("PrimaryStageSummarySqlTest");
		Common.initialize();
	}

	@Before
	public void seed() throws SQLException {
		try (Connection con = Common.getConnection()) {
			userId = selectInt(con, "SELECT min(id) FROM starexec.users");
			jobId = returningId(con,
					"INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size)"
							+ " VALUES (?, 'primary-stage-summary-test', 1, 0) RETURNING id",
					userId);
			spaceId = returningId(con,
					"INSERT INTO starexec.job_spaces (job_id, name) VALUES (?, 'root') RETURNING id",
					jobId);
			solverId = returningId(con,
					"INSERT INTO starexec.solvers (user_id, name, path, disk_size)"
							+ " VALUES (?, 'primary-stage-solver', '/s', 0) RETURNING id",
					userId);
			stageOneConfig = returningId(con,
					"INSERT INTO starexec.configurations (solver_id, name, updated)"
							+ " VALUES (?, 'stage-one-config', NOW()) RETURNING id",
					solverId);
			stageTwoConfig = returningId(con,
					"INSERT INTO starexec.configurations (solver_id, name, updated)"
							+ " VALUES (?, 'stage-two-config', NOW()) RETURNING id",
					solverId);
			benchId = returningId(con,
					"INSERT INTO starexec.benchmarks (user_id, name, uploaded, path, disk_size)"
							+ " VALUES (?, 'primary-stage-bench', NOW(), '/b', 0) RETURNING id",
					userId);
			try (PreparedStatement ps = con.prepareStatement(
					"INSERT INTO starexec.bench_attributes (bench_id, attr_key, attr_value)"
							+ " VALUES (?, ?, 'Theorem')")) {
				ps.setInt(1, benchId);
				ps.setString(2, R.EXPECTED_RESULT);
				ps.executeUpdate();
			}

			int complete = StatusCode.STATUS_COMPLETE.getVal();
			int pairId = returningId(con,
					"INSERT INTO starexec.job_pairs (job_id, job_space_id, bench_id, bench_name,"
							+ " status_code, primary_jobpair_data)"
							+ " VALUES (?, ?, ?, 'primary-stage-bench', ?, 2) RETURNING id",
					jobId, spaceId, benchId, complete);
			insertStage(con, pairId, 1, stageOneConfig, "stage-one-config", "CounterSatisfiable");
			insertStage(con, pairId, 2, stageTwoConfig, "stage-two-config", "Theorem");
			returningId(con,
					"INSERT INTO starexec.job_pair_completion (pair_id) VALUES (?) RETURNING completion_id",
					pairId);
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

	/** The pair read for the summary carries the primary stage its job pair row records. */
	@Test
	public void aPairReadForTheSummaryCarriesItsPrimaryStage() {
		List<JobPair> pairs = Jobs.getJobPairsInJobSpaceHierarchy(spaceId, PrimitivesToAnonymize.NONE);
		assertNotNull("the pairs load", pairs);
		assertEquals(1, pairs.size());
		assertEquals(Integer.valueOf(2), pairs.get(0).getPrimaryStageNumber());
	}

	/** The Primary summary is stage 2's configuration with stage 2's result, and nothing else. */
	@Test
	public void thePrimarySummaryIsThePrimaryStagesConfigurationAndCounts() {
		assertEquals(List.of("config=" + stageTwoConfig + " solved=1/1 wrong=0 unknown=0"),
				summary(0));
		assertEquals("stage 1 is still its own row",
				List.of("config=" + stageOneConfig + " solved=0/1 wrong=1 unknown=0"), summary(1));
	}

	private List<String> summary(int stageNumber) {
		Collection<SolverStats> stats = Jobs.getAllJobStatsInJobSpaceHierarchyIncludeDeletedConfigs(
				Spaces.getJobSpace(spaceId), stageNumber, PrimitivesToAnonymize.NONE, false);
		List<String> rows = new ArrayList<>();
		for (SolverStats s : stats) {
			rows.add("config=" + s.getConfiguration().getId() + " solved=" + s.getCorrectOverCompleted()
					+ " wrong=" + s.getIncorrectJobPairs() + " unknown=" + s.getUnknown());
		}
		rows.sort(null);
		return rows;
	}

	private void insertStage(Connection con, int pairId, int stage, int configId, String configName,
			String result) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.jobpair_stage_data (jobpair_id, stage_number, status_code,"
						+ " solver_id, solver_name, config_id, config_name, wallclock, cpu, disk_size)"
						+ " VALUES (?, ?, ?, ?, 'primary-stage-solver', ?, ?, 1, 1, 0)")) {
			ps.setInt(1, pairId);
			ps.setInt(2, stage);
			ps.setInt(3, StatusCode.STATUS_COMPLETE.getVal());
			ps.setInt(4, solverId);
			ps.setInt(5, configId);
			ps.setString(6, configName);
			assertEquals(1, ps.executeUpdate());
		}
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.job_attributes (pair_id, attr_key, attr_value, job_id, stage_number)"
						+ " VALUES (?, ?, ?, ?, ?)")) {
			ps.setInt(1, pairId);
			ps.setString(2, R.STAREXEC_RESULT);
			ps.setString(3, result);
			ps.setInt(4, jobId);
			ps.setInt(5, stage);
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
