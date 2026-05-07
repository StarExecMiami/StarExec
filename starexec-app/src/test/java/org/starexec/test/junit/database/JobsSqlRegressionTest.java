package org.starexec.test.junit.database;

import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.constants.R;
import org.starexec.data.database.Common;
import org.starexec.data.database.Jobs;
import org.starexec.data.to.Benchmark;
import org.starexec.data.to.Job;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.Solver;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;
import org.starexec.util.DataTablesQuery;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class JobsSqlRegressionTest extends Common {
	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("JobsSqlRegressionTest");
		Common.initialize();
	}

	@Test
	public void testGetJobForMatrixLoadsDetailedPairsWithAliasedSolverAndBenchmarkColumns() throws Exception {
		withFixture(fixture -> {
			Job matrixJob = Jobs.getJobForMatrix(fixture.jobId);

			assertNotNull("Matrix job should load", matrixJob);
			assertEquals("Loaded job id should match fixture", fixture.jobId, matrixJob.getId());
			assertNotNull("Matrix job pairs should be populated", matrixJob.getJobPairs());
			assertEquals("Matrix job should contain exactly one pair", 1, matrixJob.getJobPairs().size());

			assertPairLoaded(matrixJob.getJobPairs().get(0), fixture, false);
		});
	}

	@Test
	public void testGetNewCompletedPairsDetailedLoadsCompletedPairsWithAliasedColumns() throws Exception {
		withFixture(fixture -> {
			List<JobPair> pairs = Jobs.getNewCompletedPairsDetailed(fixture.jobId, 0);

			assertNotNull("Completed job pairs should load", pairs);
			assertEquals("Completed query should return exactly one pair", 1, pairs.size());

			assertPairLoaded(pairs.get(0), fixture, true);
		});
	}

	@Test
	public void testMissingExpectedResultClassifiesAsUnknownInCountAndPagination() throws Exception {
		withFixture(fixture -> {
			insertJobAttribute(fixture, R.STAREXEC_RESULT, "sat");
			assertExclusiveTypeClassification(fixture, "unknown", 1);
		});
	}

	@Test
	public void testExpectedUnknownClassifiesAsUnknownInCountAndPagination() throws Exception {
		withFixture(fixture -> {
			insertBenchmarkAttribute(fixture, R.EXPECTED_RESULT, R.STAREXEC_UNKNOWN);
			insertJobAttribute(fixture, R.STAREXEC_RESULT, "sat");
			assertExclusiveTypeClassification(fixture, "unknown", 1);
		});
	}

	@Test
	public void testActualUnknownWithConcreteExpectedClassifiesAsUnknownInCountAndPagination() throws Exception {
		withFixture(fixture -> {
			insertBenchmarkAttribute(fixture, R.EXPECTED_RESULT, "sat");
			insertJobAttribute(fixture, R.STAREXEC_RESULT, R.STAREXEC_UNKNOWN);
			assertExclusiveTypeClassification(fixture, "unknown", 1);
		});
	}

	@Test
	public void testMissingActualWithConcreteExpectedClassifiesAsWrongInCountAndPagination() throws Exception {
		withFixture(fixture -> {
			insertBenchmarkAttribute(fixture, R.EXPECTED_RESULT, "sat");
			assertExclusiveTypeClassification(fixture, "wrong", 1);
		});
	}

	@Test
	public void testSolvedPrimaryStageClassifiesAsSolvedInCountAndPagination() throws Exception {
		withFixture(fixture -> {
			insertBenchmarkAttribute(fixture, R.EXPECTED_RESULT, "sat");
			insertJobAttribute(fixture, R.STAREXEC_RESULT, "sat");
			assertExclusiveTypeClassification(fixture, "solved", 0);
		});
	}

	private void assertExclusiveTypeClassification(Fixture fixture, String expectedType, int stageNumber) {
		for (String pairType : Arrays.asList("solved", "wrong", "unknown")) {
			int expectedCount = pairType.equals(expectedType) ? 1 : 0;
			assertEquals(
					"Count classification should match for pairType=" + pairType,
					expectedCount,
					Jobs.getCountOfJobPairsByConfigInJobSpaceHierarchy(
							fixture.jobSpaceId,
							fixture.configId,
							pairType,
							"",
							stageNumber));

			List<JobPair> pairs = Jobs.getJobPairsForTableInJobSpaceHierarchy(
					fixture.jobSpaceId,
					new DataTablesQuery(0, 10, 0, true, ""),
					fixture.configId,
					stageNumber,
					pairType);
			assertNotNull("Pagination query should return a non-null list", pairs);
			assertEquals(
					"Pagination classification should match for pairType=" + pairType,
					expectedCount,
					pairs.size());
		}
	}

	private interface SqlFixtureConsumer {
		void accept(Fixture fixture) throws Exception;
	}

	private void withFixture(SqlFixtureConsumer consumer) throws Exception {
		Fixture fixture;
		try (Connection con = Common.getConnection()) {
			con.setAutoCommit(false);
			fixture = createFixture(con);
			con.commit();
		}

		try {
			consumer.accept(fixture);
		} finally {
			cleanupFixture(fixture);
		}
	}

	private Fixture createFixture(Connection con) throws SQLException {
		int userId = selectSingleInt(con, "SELECT id FROM users WHERE email='admin' LIMIT 1");
		int queueId = selectSingleInt(con, "SELECT id FROM queues WHERE name='all.q' LIMIT 1");
		Timestamp now = new Timestamp(System.currentTimeMillis());
		String suffix = UUID.randomUUID().toString();

		int nodeId = insertNode(con, suffix);
		int solverId = insertSolver(con, userId, now, suffix);
		int configId = insertConfiguration(con, solverId, userId, now, suffix);
		int benchmarkId = insertBenchmark(con, userId, now, suffix);
		int jobId = insertJob(con, userId, queueId, suffix);
		int jobSpaceId = insertJobSpace(con, jobId, suffix);
		updateJobPrimarySpace(con, jobId, jobSpaceId);
		int pairId = insertJobPair(con, jobId, benchmarkId, jobSpaceId, nodeId, suffix);
		insertJobPairStage(con, pairId, solverId, configId, jobSpaceId, suffix);
		int completionId = insertCompletion(con, pairId);

		return new Fixture(jobId, pairId, completionId, solverId, configId, benchmarkId, jobSpaceId,
				nodeId, "solver-" + suffix, "config-" + suffix, "bench-" + suffix, userId, now);
	}

	private static int insertNode(Connection con, String suffix) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO nodes (name, status) VALUES (?, ?) RETURNING id")) {
			ps.setString(1, "node-" + suffix);
			ps.setString(2, "available");
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private static int insertSolver(Connection con, int userId, Timestamp uploaded, String suffix) throws SQLException {
		String sql = "INSERT INTO solvers (user_id, name, uploaded, upload_date, path, description, downloadable, disk_size, deleted, recycled, executable_type, build_status) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			ps.setInt(1, userId);
			ps.setString(2, "solver-" + suffix);
			ps.setTimestamp(3, uploaded);
			ps.setTimestamp(4, uploaded);
			ps.setString(5, "/tmp/solver-" + suffix);
			ps.setString(6, "matrix regression solver");
			ps.setBoolean(7, true);
			ps.setLong(8, 4096L);
			ps.setBoolean(9, false);
			ps.setBoolean(10, false);
			ps.setInt(11, 1);
			// BUILT status code (kept literal to avoid coupling this SQL regression
			// test to TO-level enums that are not asserted in this test).
			ps.setInt(12, 1);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private static int insertConfiguration(Connection con, int solverId, int userId, Timestamp uploaded, String suffix)
			throws SQLException {
		String sql = "INSERT INTO configurations (solver_id, name, description, updated, deleted, contents, upload_date, user_id) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			ps.setInt(1, solverId);
			ps.setString(2, "config-" + suffix);
			ps.setString(3, "matrix regression config");
			ps.setTimestamp(4, uploaded);
			ps.setBoolean(5, false);
			ps.setString(6, "-- config contents");
			ps.setTimestamp(7, uploaded);
			ps.setInt(8, userId);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private static int insertBenchmark(Connection con, int userId, Timestamp uploaded, String suffix) throws SQLException {
		String sql = "INSERT INTO benchmarks (user_id, name, bench_type, uploaded, upload_date, path, description, downloadable, disk_size, deleted, recycled) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			ps.setInt(1, userId);
			ps.setString(2, "bench-" + suffix);
			ps.setInt(3, 1);
			ps.setTimestamp(4, uploaded);
			ps.setTimestamp(5, uploaded);
			ps.setString(6, "/tmp/bench-" + suffix + ".smt2");
			ps.setString(7, "matrix regression benchmark");
			ps.setBoolean(8, true);
			ps.setLong(9, 2048L);
			ps.setBoolean(10, false);
			ps.setBoolean(11, false);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private static int insertJob(Connection con, int userId, int queueId, String suffix) throws SQLException {
		String sql = "INSERT INTO jobs (user_id, name, queue_id, description, deleted, paused, killed, seed, cpuTimeout, clockTimeout, kill_delay, soft_time_limit, maximum_memory, primary_space, using_dependencies, suppress_timestamp, buildJob, total_pairs, disk_size, is_high_priority, benchmarking_framework, completed_pairs, errored_pairs, pending_pairs, status_code, max_stages, job_type, suppress_output, node_queued) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			ps.setInt(1, userId);
			ps.setString(2, "matrix-job-" + suffix);
			ps.setInt(3, queueId);
			ps.setString(4, "matrix regression job");
			ps.setBoolean(5, false);
			ps.setBoolean(6, false);
			ps.setBoolean(7, false);
			ps.setLong(8, 0L);
			ps.setInt(9, 60);
			ps.setInt(10, 60);
			ps.setInt(11, 5);
			ps.setInt(12, 60);
			ps.setLong(13, 1024L * 1024L * 1024L);
			ps.setInt(14, 0);
			ps.setBoolean(15, false);
			ps.setBoolean(16, false);
			ps.setBoolean(17, false);
			ps.setInt(18, 1);
			ps.setLong(19, 0L);
			ps.setBoolean(20, false);
			ps.setString(21, "RUNSOLVER");
			ps.setInt(22, 0);
			ps.setInt(23, 0);
			ps.setInt(24, 1);
			ps.setInt(25, StatusCode.STATUS_PENDING_SUBMIT.getVal());
			ps.setInt(26, 1);
			ps.setInt(27, 0);
			ps.setBoolean(28, false);
			ps.setBoolean(29, false);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private static int insertJobSpace(Connection con, int jobId, String suffix) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement("SELECT starexec.AddJobSpace(?, ?)")) {
			ps.setString(1, "job-space-" + suffix);
			ps.setInt(2, jobId);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private static void updateJobPrimarySpace(Connection con, int jobId, int jobSpaceId) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement("UPDATE jobs SET primary_space = ? WHERE id = ?")) {
			ps.setInt(1, jobSpaceId);
			ps.setInt(2, jobId);
			ps.executeUpdate();
		}
	}

	private static int insertJobPair(Connection con, int jobId, int benchmarkId, int jobSpaceId, int nodeId, String suffix)
			throws SQLException {
		String sql = "INSERT INTO job_pairs (job_id, bench_id, bench_name, status_code, node_id, job_space_id, path, sandbox_num, primary_jobpair_data, sge_id, queuesub_time, start_time, end_time) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
		Timestamp now = new Timestamp(System.currentTimeMillis());
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			ps.setInt(1, jobId);
			ps.setInt(2, benchmarkId);
			ps.setString(3, "bench-" + suffix);
			ps.setInt(4, StatusCode.STATUS_COMPLETE.getVal());
			ps.setInt(5, nodeId);
			ps.setInt(6, jobSpaceId);
			ps.setString(7, "/matrix/path/" + suffix + "/");
			ps.setInt(8, 1);
			ps.setInt(9, 1);
			ps.setInt(10, 0);
			ps.setTimestamp(11, now);
			ps.setTimestamp(12, now);
			ps.setTimestamp(13, now);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private static void insertJobPairStage(Connection con, int pairId, int solverId, int configId, int jobSpaceId,
			String suffix) throws SQLException {
		String sql = "INSERT INTO jobpair_stage_data (jobpair_id, stage_id, stage_number, solver_id, solver_name, config_id, config_name, job_space_id, status_code, cpu, wallclock, user_time, system_time, max_vmem, max_res_set, disk_size) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			ps.setInt(1, pairId);
			ps.setNull(2, java.sql.Types.INTEGER);
			ps.setInt(3, 1);
			ps.setInt(4, solverId);
			ps.setString(5, "solver-" + suffix);
			ps.setInt(6, configId);
			ps.setString(7, "config-" + suffix);
			ps.setInt(8, jobSpaceId);
			ps.setInt(9, StatusCode.STATUS_COMPLETE.getVal());
			ps.setDouble(10, 1.5d);
			ps.setDouble(11, 2.5d);
			ps.setDouble(12, 1.0d);
			ps.setDouble(13, 0.5d);
			ps.setDouble(14, 512d);
			ps.setDouble(15, 256d);
			ps.setLong(16, 1234L);
			ps.executeUpdate();
		}
	}

	private static int insertCompletion(Connection con, int pairId) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO job_pair_completion (pair_id) VALUES (?) RETURNING completion_id")) {
			ps.setInt(1, pairId);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private static void insertBenchmarkAttribute(Fixture fixture, String key, String value) throws SQLException {
		try (Connection con = Common.getConnection();
				 PreparedStatement ps = con.prepareStatement(
						 "INSERT INTO bench_attributes (bench_id, attr_key, attr_value) VALUES (?, ?, ?)")) {
			ps.setInt(1, fixture.benchmarkId);
			ps.setString(2, key);
			ps.setString(3, value);
			ps.executeUpdate();
		}
	}

	private static void insertJobAttribute(Fixture fixture, String key, String value) throws SQLException {
		try (Connection con = Common.getConnection();
				 PreparedStatement ps = con.prepareStatement(
						 "INSERT INTO job_attributes (pair_id, attr_key, attr_value, job_id, stage_number) VALUES (?, ?, ?, ?, ?)")) {
			ps.setInt(1, fixture.pairId);
			ps.setString(2, key);
			ps.setString(3, value);
			ps.setInt(4, fixture.jobId);
			ps.setInt(5, 1);
			ps.executeUpdate();
		}
	}

	private static int selectSingleInt(Connection con, String sql) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
			if (!rs.next()) {
				throw new IllegalStateException("Query returned no rows: " + sql);
			}
			return rs.getInt(1);
		}
	}

	private static void cleanupFixture(Fixture fixture) throws SQLException {
		try (Connection con = Common.getConnection()) {
			con.setAutoCommit(false);
			try {
				deleteById(con, "DELETE FROM job_pair_completion WHERE pair_id = ?", fixture.pairId);
				deleteById(con, "DELETE FROM jobpair_stage_data WHERE jobpair_id = ?", fixture.pairId);
				deleteById(con, "DELETE FROM job_pairs WHERE id = ?", fixture.pairId);
				deleteById(con, "DELETE FROM job_spaces WHERE id = ?", fixture.jobSpaceId);
				deleteById(con, "DELETE FROM jobs WHERE id = ?", fixture.jobId);
				deleteById(con, "DELETE FROM configurations WHERE id = ?", fixture.configId);
				deleteById(con, "DELETE FROM solvers WHERE id = ?", fixture.solverId);
				deleteById(con, "DELETE FROM benchmarks WHERE id = ?", fixture.benchmarkId);
				deleteById(con, "DELETE FROM nodes WHERE id = ?", fixture.nodeId);
				con.commit();
			} catch (SQLException e) {
				con.rollback();
				throw e;
			}
		}
	}

	private static void deleteById(Connection con, String sql, int id) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			ps.setInt(1, id);
			ps.executeUpdate();
		}
	}

	private static void assertPairLoaded(JobPair pair, Fixture fixture, boolean expectCompletionId) {
		assertNotNull("Job pair should be present", pair);
		assertEquals("Pair id should match fixture", fixture.pairId, pair.getId());
		assertEquals("Pair job id should match fixture", fixture.jobId, pair.getJobId());
		assertEquals("Pair status should round-trip through the detailed pair query",
				StatusCode.STATUS_COMPLETE, pair.getStatus().getCode());
		assertEquals("Pair job space id should match fixture", fixture.jobSpaceId, pair.getJobSpaceId());
		assertFalse("Pair path should be populated", pair.getPath().isEmpty());

		if (expectCompletionId) {
			assertEquals("Completion id should round-trip through the completed-pair query",
					fixture.completionId, pair.getCompletionId());
		}

		assertNotNull("Primary stage should be present", pair.getPrimaryStage());
		assertEquals("Primary stage number should match fixture", Integer.valueOf(1), pair.getPrimaryStage().getStageNumber());
		assertNotNull("Primary stage solver should be present", pair.getPrimaryStage().getSolver());

		Solver solver = pair.getPrimaryStage().getSolver();
		assertEquals("Solver id should round-trip through the detailed pair query", fixture.solverId, solver.getId());
		assertEquals("Solver name should round-trip through the detailed pair query", fixture.solverName, solver.getName());

		assertEquals("Configuration id should round-trip through the detailed pair query", fixture.configId,
				pair.getPrimaryStage().getConfiguration().getId());
		assertEquals("Configuration name should round-trip through the detailed pair query", fixture.configName,
				pair.getPrimaryStage().getConfiguration().getName());

		Benchmark benchmark = pair.getBench();
		assertNotNull("Benchmark should be present", benchmark);
		assertEquals("Benchmark id should round-trip through the detailed pair query", fixture.benchmarkId, benchmark.getId());
		assertEquals("Benchmark name should round-trip through the detailed pair query", fixture.benchmarkName, benchmark.getName());
	}

	private static final class Fixture {
		private final int jobId;
		private final int pairId;
		private final int completionId;
		private final int solverId;
		private final int configId;
		private final int benchmarkId;
		private final int jobSpaceId;
		private final int nodeId;
		private final String solverName;
		private final String configName;
		private final String benchmarkName;
		private final int userId;
		private final Timestamp uploaded;

		private Fixture(int jobId, int pairId, int completionId, int solverId, int configId, int benchmarkId,
				int jobSpaceId, int nodeId, String solverName, String configName, String benchmarkName, int userId,
				Timestamp uploaded) {
			this.jobId = jobId;
			this.pairId = pairId;
			this.completionId = completionId;
			this.solverId = solverId;
			this.configId = configId;
			this.benchmarkId = benchmarkId;
			this.jobSpaceId = jobSpaceId;
			this.nodeId = nodeId;
			this.solverName = solverName;
			this.configName = configName;
			this.benchmarkName = benchmarkName;
			this.userId = userId;
			this.uploaded = uploaded;
		}
	}
}
