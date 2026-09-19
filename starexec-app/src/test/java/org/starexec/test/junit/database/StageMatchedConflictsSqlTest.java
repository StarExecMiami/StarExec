package org.starexec.test.junit.database;

import org.apache.commons.lang3.tuple.Triple;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.data.database.Common;
import org.starexec.data.database.Solvers;
import org.starexec.data.to.Benchmark;
import org.starexec.data.to.Configuration;
import org.starexec.data.to.Solver;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * A benchmark's solver results and its conflicts are judged within one stage (#187).
 *
 * <p>A conflict, as the job page counts it and the conflicting-benchmarks page lists it: at a
 * given stage, a benchmark is conflicting when the job's pairs recorded more than one distinct
 * {@code starexec-result} for it there, {@code starexec-unknown} aside; and a configuration's
 * conflicts are the conflicting benchmarks on which it produced a result other than
 * {@code starexec-unknown}.
 *
 * <p>Each function matched the stage in one place and not another. The inner "which benchmarks
 * conflict" query filtered attributes by stage, but the outer query joined a configuration's
 * stage row to its pair's attributes on any stage, and the solver-results query filtered the stage
 * row but not the attributes. Once attributes land on more than one stage of a pair (#179), a
 * configuration was credited with another stage's result.
 *
 * <p>The fixture is one benchmark and three two-stage pairs, each stage run by its own
 * configuration:
 *
 * <pre>
 *            stage 1                        stage 2
 *   pair P   c1p  starexec-unknown          c2p  Satisfiable
 *   pair Q   c1q  Theorem                   c2q  Satisfiable
 *   pair R   c1r  CounterSatisfiable        c2r  Satisfiable
 * </pre>
 *
 * The benchmark conflicts on stage 1 (Theorem against CounterSatisfiable) and not on stage 2.
 * P's stage 1 is unknown, so c1p has no conflict there -- its pair's stage 2 result must not
 * count for it.
 *
 * <p>Conflicts are also judged within one job (#189). The outer query of both conflict functions
 * matched a configuration's pairs in every job, so a result it produced on the same benchmark in
 * another job was counted as a conflict here. Those tests add a second job on the same benchmark
 * and configurations:
 *
 * <pre>
 *            stage 1                        stage 2
 *   pair X   c1p  Theorem                   c2p  Satisfiable
 *   pair Y   c1q  CounterSatisfiable        c2q  Satisfiable
 * </pre>
 *
 * The benchmark conflicts on stage 1 of that job too; c1r never ran there.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*} variables
 * at a <strong>disposable</strong> database: these tests insert and delete rows.
 */
public class StageMatchedConflictsSqlTest extends Common {

	private int userId;
	private int jobId;
	private int spaceId;
	private int benchId;
	private int solverId;
	private int c1p;
	private int c1q;
	private int c1r;
	private int c2p;
	private int c2q;
	private int c2r;
	private int otherJobId;
	private int otherSpaceId;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("StageMatchedConflictsSqlTest");
		Common.initialize();
	}

	@Before
	public void seed() throws SQLException {
		try (Connection con = Common.getConnection()) {
			userId = selectInt(con, "SELECT min(id) FROM starexec.users");
			jobId = insertReturningId(con,
					"INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size)"
							+ " VALUES (?, 'stage-conflicts-test', 3, 0) RETURNING id",
					userId);
			spaceId = insertReturningId(con,
					"INSERT INTO starexec.job_spaces (job_id, name) VALUES (?, 'root') RETURNING id",
					jobId);
			benchId = insertReturningId(con,
					"INSERT INTO starexec.benchmarks (user_id, name, uploaded, path, disk_size)"
							+ " VALUES (?, 'stage-conflicts-bench', NOW(), '/b', 0) RETURNING id",
					userId);
			solverId = insertReturningId(con,
					"INSERT INTO starexec.solvers (user_id, name, uploaded, path, disk_size)"
							+ " VALUES (?, 'stage-conflicts-solver', NOW(), '/s', 0) RETURNING id",
					userId);
			c1p = insertConfig(con, "c1p");
			c1q = insertConfig(con, "c1q");
			c1r = insertConfig(con, "c1r");
			c2p = insertConfig(con, "c2p");
			c2q = insertConfig(con, "c2q");
			c2r = insertConfig(con, "c2r");

			insertTwoStagePair(con, c1p, "starexec-unknown", c2p, "Satisfiable");
			insertTwoStagePair(con, c1q, "Theorem", c2q, "Satisfiable");
			insertTwoStagePair(con, c1r, "CounterSatisfiable", c2r, "Satisfiable");
		}
	}

	@After
	public void dropFixture() throws SQLException {
		if (jobId == 0) {
			return;
		}
		try (Connection con = Common.getConnection()) {
			// job_attributes and jobpair_stage_data cascade from job_pairs; configurations from
			// solvers.
			if (otherJobId != 0) {
				update(con, "DELETE FROM starexec.job_pairs WHERE job_id = ?", otherJobId);
				update(con, "DELETE FROM starexec.job_spaces WHERE id = ?", otherSpaceId);
				update(con, "DELETE FROM starexec.jobs WHERE id = ?", otherJobId);
			}
			update(con, "DELETE FROM starexec.job_pairs WHERE job_id = ?", jobId);
			update(con, "DELETE FROM starexec.job_spaces WHERE id = ?", spaceId);
			update(con, "DELETE FROM starexec.solvers WHERE id = ?", solverId);
			update(con, "DELETE FROM starexec.benchmarks WHERE id = ?", benchId);
			update(con, "DELETE FROM starexec.jobs WHERE id = ?", jobId);
		}
	}

	// ------------------------------------------------------------------ conflict counts

	@Test
	public void eachConfigurationsConflictsAreCountedOnItsOwnStage() throws SQLException {
		assertEquals("c1p's stage-1 result is unknown; its stage-2 result is not its conflict",
				0, conflicts(c1p, 1));
		assertEquals("Theorem against CounterSatisfiable", 1, conflicts(c1q, 1));
		assertEquals(1, conflicts(c1r, 1));

		assertEquals("stage 2 agrees", 0, conflicts(c2p, 2));
		assertEquals(0, conflicts(c2q, 2));
		assertEquals(0, conflicts(c2r, 2));
	}

	/** A configuration asked about a stage it did not run has nothing there. */
	@Test
	public void aConfigurationHasNoConflictsOnAStageItDidNotRun() throws SQLException {
		assertEquals(0, conflicts(c2q, 1));
		assertEquals(0, conflicts(c1q, 2));
	}

	// ------------------------------------------------------------ conflicting benchmarks

	@Test
	public void eachConfigurationsConflictingBenchmarksAreListedForItsOwnStage()
			throws SQLException {
		assertEquals(List.of(), conflictingBenchmarks(c1p, 1));
		assertEquals(List.of(benchId), conflictingBenchmarks(c1q, 1));
		assertEquals(List.of(benchId), conflictingBenchmarks(c1r, 1));

		assertEquals(List.of(), conflictingBenchmarks(c2p, 2));
		assertEquals(List.of(), conflictingBenchmarks(c2q, 2));
		assertEquals(List.of(), conflictingBenchmarks(c2r, 2));

		assertEquals("a stage the configuration did not run",
				List.of(), conflictingBenchmarks(c2q, 1));
	}

	// ------------------------------------------------------------------- another job

	// -------------------------------------------------------------- stage 0 ("Primary")

	/**
	 * Stage 0 is the "Primary" pseudo-stage the job page links to for its Primary column
	 * (#194): each pair's own {@code primary_jobpair_data}, not a literal {@code stage_number}
	 * of 0 that no row ever carries.
	 */
	@Test
	public void stageZeroResolvesToEachPairsOwnPrimaryStage() throws SQLException {
		// The fixture's pairs are all primary at stage 1, so stage 0 must agree with stage 1.
		assertEquals(conflicts(c1q, 1), conflicts(c1q, 0));
		assertEquals(conflicts(c1r, 1), conflicts(c1r, 0));
		assertEquals(conflictingBenchmarks(c1q, 1), conflictingBenchmarks(c1q, 0));
		assertEquals(conflictingBenchmarks(c1r, 1), conflictingBenchmarks(c1r, 0));

		// A configuration that only ran at stage 2 has nothing at the (stage-1) primary.
		assertEquals(0, conflicts(c2q, 0));
		assertEquals(List.of(), conflictingBenchmarks(c2q, 0));
	}

	/**
	 * The same configuration can be primary at different stages of different pairs. Stage 0
	 * must count each pair against its own primary stage, not a job-wide one (#194).
	 */
	@Test
	public void stageZeroCountsConsistentlyWhenPairsDisagreeOnWhichStageIsPrimary()
			throws SQLException {
		int mixedJobId;
		int mixedSpaceId;
		try (Connection con = Common.getConnection()) {
			mixedJobId = insertReturningId(con,
					"INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size)"
							+ " VALUES (?, 'stage-conflicts-mixed-primary-job', 2, 0) RETURNING id",
					userId);
			mixedSpaceId = insertReturningId(con,
					"INSERT INTO starexec.job_spaces (job_id, name) VALUES (?, 'root') RETURNING id",
					mixedJobId);
			// Same configuration ("c1q") on both stages of both pairs, but pair S is primary
			// at stage 1 while pair T is primary at stage 2. Only stage 2 conflicts (Theorem
			// vs CounterSatisfiable); stage 1 agrees (both Satisfiable).
			insertTwoStagePairWithPrimary(con, mixedJobId, mixedSpaceId, 1, c1q, "Satisfiable",
					c1q, "Theorem");
			insertTwoStagePairWithPrimary(con, mixedJobId, mixedSpaceId, 2, c1q, "Satisfiable",
					c1q, "CounterSatisfiable");
		}
		try {
			assertEquals("only the stage-2 result differs between the two pairs' primaries",
					1, conflicts(mixedJobId, c1q, 0));
			assertEquals(List.of(benchId), conflictingBenchmarks(mixedJobId, c1q, 0));
		} finally {
			try (Connection con = Common.getConnection()) {
				update(con, "DELETE FROM starexec.job_pairs WHERE job_id = ?", mixedJobId);
				update(con, "DELETE FROM starexec.job_spaces WHERE id = ?", mixedSpaceId);
				update(con, "DELETE FROM starexec.jobs WHERE id = ?", mixedJobId);
			}
		}
	}

	/**
	 * c1p's result in this job is unknown; its Theorem in the other job is not a conflict here.
	 * And c1r's CounterSatisfiable here is not a conflict in the other job, where it never ran.
	 */
	@Test
	public void anotherJobsResultIsNotThisJobsConflict() throws SQLException {
		seedOtherJob();

		assertEquals("c1p's Theorem is in the other job", 0, conflicts(jobId, c1p, 1));
		assertEquals(List.of(), conflictingBenchmarks(jobId, c1p, 1));

		assertEquals("c1r ran only in this job", 0, conflicts(otherJobId, c1r, 1));
		assertEquals(List.of(), conflictingBenchmarks(otherJobId, c1r, 1));
	}

	/** The other job's pairs leave every count that was already right where it was. */
	@Test
	public void anotherJobsPairsDoNotChangeThisJobsCounts() throws SQLException {
		seedOtherJob();

		assertEquals(1, conflicts(jobId, c1q, 1));
		assertEquals(1, conflicts(jobId, c1r, 1));
		assertEquals(0, conflicts(jobId, c2p, 2));
		assertEquals(0, conflicts(jobId, c2q, 2));
		assertEquals(0, conflicts(jobId, c2r, 2));
		assertEquals(List.of(benchId), conflictingBenchmarks(jobId, c1q, 1));
		assertEquals(List.of(benchId), conflictingBenchmarks(jobId, c1r, 1));
		assertEquals(List.of(), conflictingBenchmarks(jobId, c2q, 2));
		assertEquals(List.of("c1q=Theorem", "c1r=CounterSatisfiable"), results(1));

		assertEquals("the other job conflicts on its own results", 1, conflicts(otherJobId, c1p, 1));
		assertEquals(1, conflicts(otherJobId, c1q, 1));
		assertEquals(0, conflicts(otherJobId, c2p, 2));
	}

	// ------------------------------------------------------------------ solver results

	/**
	 * The conflicting-solvers page for the benchmark: each configuration of the stage once, with
	 * that stage's result. starexec-unknown is left out, as it always was.
	 */
	@Test
	public void theBenchmarksResultsAreEachConfigurationsResultOnThatStage() throws SQLException {
		assertEquals(List.of("c1q=Theorem", "c1r=CounterSatisfiable").stream().sorted().toList(),
				results(1));
		assertEquals(List.of("c2p=Satisfiable", "c2q=Satisfiable", "c2r=Satisfiable"),
				results(2));
	}

	// ------------------------------------------------------------------------- helpers

	private int conflicts(int configId, int stage) throws SQLException {
		return conflicts(jobId, configId, stage);
	}

	private static int conflicts(int job, int configId, int stage) throws SQLException {
		return Solvers.getConflictsForConfigInJobWithStage(job, configId, stage);
	}

	private List<Integer> conflictingBenchmarks(int configId, int stage) throws SQLException {
		return conflictingBenchmarks(jobId, configId, stage);
	}

	private static List<Integer> conflictingBenchmarks(int job, int configId, int stage)
			throws SQLException {
		List<Integer> ids = new ArrayList<>();
		for (Benchmark b : Solvers.getConflictingBenchmarksInJobForStage(job, configId, stage)) {
			ids.add(b.getId());
		}
		Collections.sort(ids);
		return ids;
	}

	/** "configuration=result" per returned row, sorted; a duplicate row stays visible. */
	private List<String> results(int stage) throws SQLException {
		List<String> rows = new ArrayList<>();
		for (Triple<Solver, Configuration, String> row
				: Solvers.getSolverConfigResultsForBenchmarkInJob(jobId, benchId, stage)) {
			rows.add(row.getMiddle().getName() + "=" + row.getRight());
		}
		Collections.sort(rows);
		return rows;
	}

	/** The second job of the class comment, on the same benchmark and configurations. */
	private void seedOtherJob() throws SQLException {
		try (Connection con = Common.getConnection()) {
			otherJobId = insertReturningId(con,
					"INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size)"
							+ " VALUES (?, 'stage-conflicts-other-job', 2, 0) RETURNING id",
					userId);
			otherSpaceId = insertReturningId(con,
					"INSERT INTO starexec.job_spaces (job_id, name) VALUES (?, 'root') RETURNING id",
					otherJobId);
			insertTwoStagePair(con, otherJobId, otherSpaceId, c1p, "Theorem", c2p, "Satisfiable");
			insertTwoStagePair(con, otherJobId, otherSpaceId, c1q, "CounterSatisfiable", c2q,
					"Satisfiable");
		}
	}

	private int insertConfig(Connection con, String name) throws SQLException {
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

	private void insertTwoStagePair(
			Connection con, int stage1Config, String stage1Result, int stage2Config,
			String stage2Result) throws SQLException {
		insertTwoStagePair(con, jobId, spaceId, stage1Config, stage1Result, stage2Config,
				stage2Result);
	}

	private void insertTwoStagePair(
			Connection con, int job, int space, int stage1Config, String stage1Result,
			int stage2Config, String stage2Result) throws SQLException {
		int pairId = insertReturningId(con,
				"INSERT INTO starexec.job_pairs (job_id, job_space_id, bench_id, status_code,"
						+ " primary_jobpair_data) VALUES (?, ?, ?, ?, 1) RETURNING id",
				job, space, benchId, StatusCode.STATUS_COMPLETE.getVal());
		insertStage(con, job, pairId, 1, stage1Config, stage1Result);
		insertStage(con, job, pairId, 2, stage2Config, stage2Result);
	}

	private void insertTwoStagePairWithPrimary(
			Connection con, int job, int space, int primaryStage, int stage1Config,
			String stage1Result, int stage2Config, String stage2Result) throws SQLException {
		int pairId = insertReturningId(con,
				"INSERT INTO starexec.job_pairs (job_id, job_space_id, bench_id, status_code,"
						+ " primary_jobpair_data) VALUES (?, ?, ?, ?, ?) RETURNING id",
				job, space, benchId, StatusCode.STATUS_COMPLETE.getVal(), primaryStage);
		insertStage(con, job, pairId, 1, stage1Config, stage1Result);
		insertStage(con, job, pairId, 2, stage2Config, stage2Result);
	}

	private void insertStage(
			Connection con, int job, int pairId, int stage, int configId, String result)
			throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.jobpair_stage_data (jobpair_id, stage_number, status_code,"
						+ " solver_id, solver_name, config_id, config_name, disk_size)"
						+ " SELECT ?, ?, ?, ?, 'stage-conflicts-solver', id, name, 0"
						+ " FROM starexec.configurations WHERE id = ?")) {
			ps.setInt(1, pairId);
			ps.setInt(2, stage);
			ps.setInt(3, StatusCode.STATUS_COMPLETE.getVal());
			ps.setInt(4, solverId);
			ps.setInt(5, configId);
			assertEquals(1, ps.executeUpdate());
		}
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.job_attributes (pair_id, attr_key, attr_value, job_id, stage_number)"
						+ " VALUES (?, 'starexec-result', ?, ?, ?)")) {
			ps.setInt(1, pairId);
			ps.setString(2, result);
			ps.setInt(3, job);
			ps.setInt(4, stage);
			assertEquals(1, ps.executeUpdate());
		}
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
