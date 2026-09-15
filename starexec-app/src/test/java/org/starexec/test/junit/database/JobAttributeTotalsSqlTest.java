package org.starexec.test.junit.database;

import org.apache.commons.lang3.tuple.Triple;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.data.database.Common;
import org.starexec.data.database.Jobs;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.data.to.tuples.AttributesTableData;
import org.starexec.data.to.tuples.TimePair;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * The job attributes page's totals count each post-processor result once, with only the time of
 * the stage that produced it (#186).
 *
 * <p>{@code GetSumOfJobAttributes} joined every {@code job_attributes} row to every
 * {@code jobpair_stage_data} row of its pair, with no stage match. A result recorded for one
 * stage of a two-stage pair was counted twice and given both stages' wallclock and cpu.
 * {@code GetJobAttributesTable}, which the same page shows beside it, matches the stage, so the
 * two disagreed for any multi-stage job.
 *
 * <p>The fixture is a two-stage pair with a different result on each stage and distinct times
 * per stage, and a single-stage pair sharing stage 1's result, so both the per-stage match and
 * the aggregation across pairs are visible.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*} variables
 * at a <strong>disposable</strong> database: these tests insert and delete rows.
 */
public class JobAttributeTotalsSqlTest extends Common {

	private int userId;
	private int jobId;
	private int spaceId;
	private int solverId;
	private int configId;
	private int twoStagePairId;
	private int oneStagePairId;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("JobAttributeTotalsSqlTest");
		Common.initialize();
	}

	@Before
	public void seed() throws SQLException {
		try (Connection con = Common.getConnection()) {
			userId = selectInt(con, "SELECT min(id) FROM starexec.users");
			jobId = insertReturningId(con,
					"INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size)"
							+ " VALUES (?, 'attribute-totals-test', 2, 0) RETURNING id",
					userId);
			spaceId = insertReturningId(con,
					"INSERT INTO starexec.job_spaces (job_id, name) VALUES (?, 'root') RETURNING id",
					jobId);
			solverId = insertReturningId(con,
					"INSERT INTO starexec.solvers (user_id, name, path, disk_size)"
							+ " VALUES (?, 'attribute-totals-solver', '/s', 0) RETURNING id",
					userId);
			configId = insertReturningId(con,
					"INSERT INTO starexec.configurations (solver_id, name, updated)"
							+ " VALUES (?, 'attribute-totals-config', NOW()) RETURNING id",
					solverId);

			// Stage 1 says Theorem in 100 s wall / 90 s cpu; stage 2 says Unknown in 50 / 40.
			twoStagePairId = insertPair(con);
			insertStage(con, twoStagePairId, 1, 100, 90);
			insertStage(con, twoStagePairId, 2, 50, 40);
			insertResult(con, twoStagePairId, 1, "Theorem");
			insertResult(con, twoStagePairId, 2, "Unknown");

			// A single-stage pair that also says Theorem, in 7 / 6.
			oneStagePairId = insertPair(con);
			insertStage(con, oneStagePairId, 1, 7, 6);
			insertResult(con, oneStagePairId, 1, "Theorem");
		}
	}

	@After
	public void dropFixture() throws SQLException {
		if (jobId == 0) {
			return;
		}
		try (Connection con = Common.getConnection()) {
			// job_attributes and jobpair_stage_data cascade from job_pairs.
			update(con, "DELETE FROM starexec.job_pairs WHERE job_id = ?", jobId);
			update(con, "DELETE FROM starexec.job_spaces WHERE id = ?", spaceId);
			update(con, "DELETE FROM starexec.configurations WHERE id = ?", configId);
			update(con, "DELETE FROM starexec.solvers WHERE id = ?", solverId);
			update(con, "DELETE FROM starexec.jobs WHERE id = ?", jobId);
		}
	}

	/**
	 * Theorem: once on the two-stage pair's stage 1 and once on the single-stage pair, with those
	 * two stages' time. Unknown: once, with stage 2's time alone.
	 */
	@Test
	public void eachResultIsCountedOnceWithOnlyItsOwnStagesTime() throws SQLException {
		Map<String, String> totals = totalsByResult();

		assertEquals(
				"Theorem is recorded on two stages (two-stage pair stage 1, one-stage pair), and"
						+ " Unknown on one (two-stage pair stage 2); each count and each sum is"
						+ " of those stages alone",
				Map.of(
						"Theorem", "2 wall=107.0000 cpu=96.0000",
						"Unknown", "1 wall=50.0000 cpu=40.0000"),
				totals);
	}

	/**
	 * The page shows both queries side by side. Summed per result over solver and configuration,
	 * the attributes table must give exactly the totals.
	 */
	@Test
	public void theTotalsAgreeWithTheAttributesTable() throws SQLException {
		List<AttributesTableData> table = Jobs.getJobAttributesTable(spaceId);
		assertNotNull("getJobAttributesTable logs and returns null on a database error", table);

		Map<String, long[]> fromTable = new TreeMap<>();
		for (AttributesTableData row : table) {
			long[] sum = fromTable.computeIfAbsent(row.attrValue, k -> new long[3]);
			sum[0] += row.attrCount;
			sum[1] += Math.round(row.wallclockSum);
			sum[2] += Math.round(row.cpuSum);
		}
		Map<String, String> tableTotals = new TreeMap<>();
		for (Map.Entry<String, long[]> e : fromTable.entrySet()) {
			tableTotals.put(e.getKey(), String.format("%d wall=%d.0000 cpu=%d.0000",
					e.getValue()[0], e.getValue()[1], e.getValue()[2]));
		}

		assertEquals("precondition: the attributes table matches each result to its stage",
				Map.of(
						"Theorem", "2 wall=107.0000 cpu=96.0000",
						"Unknown", "1 wall=50.0000 cpu=40.0000"),
				tableTotals);
		assertEquals("the totals agree with the attributes table", tableTotals, totalsByResult());
	}

	/**
	 * Jobs.getJobAttributeTotals, as "count wall=W cpu=C" per result. It formats with the JVM's
	 * default locale, so a comma decimal separator is normalised rather than failing here.
	 */
	private Map<String, String> totalsByResult() throws SQLException {
		List<Triple<String, Integer, TimePair>> rows = Jobs.getJobAttributeTotals(spaceId);
		Map<String, String> byResult = new TreeMap<>();
		for (Triple<String, Integer, TimePair> row : rows) {
			byResult.put(row.getLeft(), row.getMiddle()
					+ " wall=" + row.getRight().getWallclock().replace(',', '.')
					+ " cpu=" + row.getRight().getCpu().replace(',', '.'));
		}
		return byResult;
	}

	private int insertPair(Connection con) throws SQLException {
		return insertReturningId(con,
				"INSERT INTO starexec.job_pairs (job_id, job_space_id, status_code, primary_jobpair_data)"
						+ " VALUES (?, ?, ?, 1) RETURNING id",
				jobId, spaceId, StatusCode.STATUS_COMPLETE.getVal());
	}

	private void insertStage(Connection con, int pairId, int stage, double wallclock, double cpu)
			throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.jobpair_stage_data (jobpair_id, stage_number, status_code,"
						+ " solver_id, solver_name, config_id, config_name, wallclock, cpu, disk_size)"
						+ " VALUES (?, ?, ?, ?, 'attribute-totals-solver', ?, 'attribute-totals-config',"
						+ " ?, ?, 0)")) {
			ps.setInt(1, pairId);
			ps.setInt(2, stage);
			ps.setInt(3, StatusCode.STATUS_COMPLETE.getVal());
			ps.setInt(4, solverId);
			ps.setInt(5, configId);
			ps.setDouble(6, wallclock);
			ps.setDouble(7, cpu);
			assertEquals(1, ps.executeUpdate());
		}
	}

	private void insertResult(Connection con, int pairId, int stage, String result)
			throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.job_attributes (pair_id, attr_key, attr_value, job_id, stage_number)"
						+ " VALUES (?, 'starexec-result', ?, ?, ?)")) {
			ps.setInt(1, pairId);
			ps.setString(2, result);
			ps.setInt(3, jobId);
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
