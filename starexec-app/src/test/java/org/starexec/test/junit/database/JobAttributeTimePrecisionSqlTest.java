package org.starexec.test.junit.database;

import org.apache.commons.lang3.tuple.Triple;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.app.RESTHelpers;
import org.starexec.data.database.Common;
import org.starexec.data.database.Jobs;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.data.to.tuples.AttributesTableRow;
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

/**
 * The job attributes page shows each result's wallclock and cpu totals to four decimals, and
 * those totals keep the time below a second (#190).
 *
 * <p>{@code GetSumOfJobAttributes} and {@code GetJobAttributesTable} summed the stages'
 * {@code DOUBLE PRECISION} times and cast the sum to {@code BIGINT}. Every total was rounded to
 * a whole second before Java formatted it with {@code %.4f}, so the page printed
 * {@code .0000} always, and a solver whose runs each finish in under half a second showed 0.
 *
 * <p>The fixture is one configuration and three single-stage pairs with sub-second and
 * fractional times:
 *
 * <pre>
 *            result    wallclock   cpu
 *   pair A   Theorem   0.133995    0.1
 *   pair B   Theorem   1.73894     0.25
 *   pair C   Unknown   0.4         0.049
 * </pre>
 *
 * Theorem totals 1.872935 s wall and 0.35 s cpu; Unknown 0.4 and 0.049. None of the fifth
 * decimals is a 5, so the four-decimal strings do not depend on how a tie is rounded.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*} variables
 * at a <strong>disposable</strong> database: these tests insert and delete rows.
 */
public class JobAttributeTimePrecisionSqlTest extends Common {

	/** What the page prints for each result, as "count wall=W cpu=C". */
	private static final Map<String, String> PAGE = Map.of(
			"Theorem", "2 wall=1.8729 cpu=0.3500",
			"Unknown", "1 wall=0.4000 cpu=0.0490");

	private int userId;
	private int jobId;
	private int spaceId;
	private int solverId;
	private int configId;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("JobAttributeTimePrecisionSqlTest");
		Common.initialize();
	}

	@Before
	public void seed() throws SQLException {
		try (Connection con = Common.getConnection()) {
			userId = selectInt(con, "SELECT min(id) FROM starexec.users");
			jobId = insertReturningId(con,
					"INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size)"
							+ " VALUES (?, 'attribute-time-precision-test', 3, 0) RETURNING id",
					userId);
			spaceId = insertReturningId(con,
					"INSERT INTO starexec.job_spaces (job_id, name) VALUES (?, 'root') RETURNING id",
					jobId);
			solverId = insertReturningId(con,
					"INSERT INTO starexec.solvers (user_id, name, path, disk_size)"
							+ " VALUES (?, 'attribute-time-precision-solver', '/s', 0) RETURNING id",
					userId);
			configId = insertReturningId(con,
					"INSERT INTO starexec.configurations (solver_id, name, updated)"
							+ " VALUES (?, 'attribute-time-precision-config', NOW()) RETURNING id",
					solverId);

			insertSingleStagePair(con, "Theorem", 0.133995, 0.1);
			insertSingleStagePair(con, "Theorem", 1.73894, 0.25);
			insertSingleStagePair(con, "Unknown", 0.4, 0.049);
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

	/** The totals table at the foot of the page. */
	@Test
	public void theTotalsKeepTimeBelowASecond() throws SQLException {
		Map<String, String> totals = new TreeMap<>();
		for (Triple<String, Integer, TimePair> row : Jobs.getJobAttributeTotals(spaceId)) {
			totals.put(row.getLeft(), row.getMiddle()
					+ " wall=" + decimal(row.getRight().getWallclock())
					+ " cpu=" + decimal(row.getRight().getCpu()));
		}
		assertEquals(PAGE, totals);
	}

	/**
	 * The per-configuration table above it, as the page builds it. With one configuration its
	 * cells are the totals.
	 */
	@Test
	public void theAttributesTableKeepsTimeBelowASecond() throws SQLException {
		List<AttributesTableRow> table = RESTHelpers.getAttributesTable(spaceId);
		assertEquals("one configuration, one row", 1, table.size());

		List<String> values = Jobs.getJobAttributeValues(spaceId);
		values.sort(null);
		Map<String, String> cells = new TreeMap<>();
		List<Triple<Integer, String, String>> countAndTimes = table.get(0).countAndTimes;
		assertEquals("a cell per result", values.size(), countAndTimes.size());
		for (int i = 0; i < values.size(); i++) {
			Triple<Integer, String, String> cell = countAndTimes.get(i);
			cells.put(values.get(i), cell.getLeft()
					+ " wall=" + decimal(cell.getMiddle())
					+ " cpu=" + decimal(cell.getRight()));
		}
		assertEquals(PAGE, cells);
	}

	/**
	 * Both readers format with the JVM's default locale, so a comma decimal separator is
	 * normalised rather than failing here.
	 */
	private static String decimal(String formatted) {
		return formatted.replace(',', '.');
	}

	private void insertSingleStagePair(Connection con, String result, double wallclock, double cpu)
			throws SQLException {
		int pairId = insertReturningId(con,
				"INSERT INTO starexec.job_pairs (job_id, job_space_id, status_code, primary_jobpair_data)"
						+ " VALUES (?, ?, ?, 1) RETURNING id",
				jobId, spaceId, StatusCode.STATUS_COMPLETE.getVal());
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.jobpair_stage_data (jobpair_id, stage_number, status_code,"
						+ " solver_id, solver_name, config_id, config_name, wallclock, cpu, disk_size)"
						+ " VALUES (?, 1, ?, ?, 'attribute-time-precision-solver', ?,"
						+ " 'attribute-time-precision-config', ?, ?, 0)")) {
			ps.setInt(1, pairId);
			ps.setInt(2, StatusCode.STATUS_COMPLETE.getVal());
			ps.setInt(3, solverId);
			ps.setInt(4, configId);
			ps.setDouble(5, wallclock);
			ps.setDouble(6, cpu);
			assertEquals(1, ps.executeUpdate());
		}
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.job_attributes (pair_id, attr_key, attr_value, job_id, stage_number)"
						+ " VALUES (?, 'starexec-result', ?, ?, 1)")) {
			ps.setInt(1, pairId);
			ps.setString(2, result);
			ps.setInt(3, jobId);
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
