package org.starexec.test.junit.database;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.starexec.constants.R;
import org.starexec.data.database.AnonymousLinks.PrimitivesToAnonymize;
import org.starexec.data.database.Benchmarks;
import org.starexec.data.database.Common;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Spaces;
import org.starexec.data.to.SolverStats;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * A job's solver summary is compiled from its pairs every time it is asked for, and nothing is
 * cached in {@code job_stats} (#202).
 *
 * <p>{@code AddJobStats}' {@code ON CONFLICT} target has matched no unique constraint since V0012
 * added {@code include_unknowns} to the key, so no Flyway-managed database has ever cached a row:
 * every save failed and was logged. Repairing it would have made the cache real, but the stats
 * read each benchmark's expected result live and nothing invalidates them when those change --
 * reprocessing benchmarks, editing a benchmark, clearing its attributes, deleting it -- so a real
 * cache would serve stale solved and wrong counts. The read and the save are removed instead.
 *
 * <p>The fixture is a completed job with one pair whose result is Theorem, on a benchmark whose
 * expected result is Theorem.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*} variables
 * at a <strong>disposable</strong> database: these tests insert and delete rows.
 */
public class JobStatsNotCachedSqlTest extends Common {

	private int userId;
	private int jobId;
	private int spaceId;
	private int solverId;
	private int configId;
	private int benchId;

	private ListAppender<ILoggingEvent> jobsLog;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("JobStatsNotCachedSqlTest");
		Common.initialize();
	}

	@Before
	public void seed() throws SQLException {
		try (Connection con = Common.getConnection()) {
			userId = selectInt(con, "SELECT min(id) FROM starexec.users");
			jobId = returningId(con,
					"INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size)"
							+ " VALUES (?, 'job-stats-not-cached-test', 1, 0) RETURNING id",
					userId);
			spaceId = returningId(con,
					"INSERT INTO starexec.job_spaces (job_id, name) VALUES (?, 'root') RETURNING id",
					jobId);
			solverId = returningId(con,
					"INSERT INTO starexec.solvers (user_id, name, path, disk_size)"
							+ " VALUES (?, 'not-cached-solver', '/s', 0) RETURNING id",
					userId);
			configId = returningId(con,
					"INSERT INTO starexec.configurations (solver_id, name, updated)"
							+ " VALUES (?, 'not-cached-config', NOW()) RETURNING id",
					solverId);
			benchId = returningId(con,
					"INSERT INTO starexec.benchmarks (user_id, name, uploaded, path, disk_size)"
							+ " VALUES (?, 'not-cached-bench', NOW(), '/b', 0) RETURNING id",
					userId);

			int complete = StatusCode.STATUS_COMPLETE.getVal();
			int pairId = returningId(con,
					"INSERT INTO starexec.job_pairs (job_id, job_space_id, bench_id, bench_name,"
							+ " status_code, primary_jobpair_data)"
							+ " VALUES (?, ?, ?, 'not-cached-bench', ?, 1) RETURNING id",
					jobId, spaceId, benchId, complete);
			try (PreparedStatement ps = con.prepareStatement(
					"INSERT INTO starexec.jobpair_stage_data (jobpair_id, stage_number, status_code,"
							+ " solver_id, solver_name, config_id, config_name, wallclock, cpu, disk_size)"
							+ " VALUES (?, 1, ?, ?, 'not-cached-solver', ?, 'not-cached-config', 1, 1, 0)")) {
				ps.setInt(1, pairId);
				ps.setInt(2, complete);
				ps.setInt(3, solverId);
				ps.setInt(4, configId);
				assertEquals(1, ps.executeUpdate());
			}
			try (PreparedStatement ps = con.prepareStatement(
					"INSERT INTO starexec.job_attributes (pair_id, attr_key, attr_value, job_id, stage_number)"
							+ " VALUES (?, ?, 'Theorem', ?, 1)")) {
				ps.setInt(1, pairId);
				ps.setString(2, R.STAREXEC_RESULT);
				ps.setInt(3, jobId);
				assertEquals(1, ps.executeUpdate());
			}
			returningId(con,
					"INSERT INTO starexec.job_pair_completion (pair_id) VALUES (?) RETURNING completion_id",
					pairId);
		}
		Benchmarks.addBenchAttr(benchId, R.EXPECTED_RESULT, "Theorem");
		assertEquals("precondition: the job is complete, so the old path would try to cache it",
				true, Jobs.isJobComplete(jobId));

		jobsLog = new ListAppender<>();
		jobsLog.start();
		((Logger) LoggerFactory.getLogger(Jobs.class)).addAppender(jobsLog);
	}

	@After
	public void dropFixture() throws SQLException {
		if (jobsLog != null) {
			((Logger) LoggerFactory.getLogger(Jobs.class)).detachAppender(jobsLog);
		}
		if (jobId == 0) {
			return;
		}
		try (Connection con = Common.getConnection()) {
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

	/**
	 * Viewing a completed job's stats writes nothing and logs no error. It used to attempt the
	 * save, which failed on every call and logged it.
	 */
	@Test
	public void viewingACompletedJobsStatsSavesNothingAndLogsNoError() throws SQLException {
		assertEquals(List.of(row(1, 0)), summary());

		assertEquals("nothing is cached", 0, cachedRows());
		assertEquals("no save was attempted, so none failed", List.of(), errors());
	}

	/**
	 * A row already in job_stats -- written by a database whose key once matched, or by hand -- is
	 * not served in place of the pairs.
	 */
	@Test
	public void aRowLeftInTheCacheIsNotServed() throws SQLException {
		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement(
						"INSERT INTO starexec.job_stats (job_space_id, config_id, complete, correct,"
								+ " incorrect, failed, conflicts, wallclock, cpu, resource_out, incomplete,"
								+ " stage_number, include_unknowns)"
								+ " VALUES (?, ?, 9, 0, 9, 0, 0, 0, 0, 0, 0, 1, false)")) {
			ps.setInt(1, spaceId);
			ps.setInt(2, configId);
			assertEquals(1, ps.executeUpdate());
		}

		assertEquals("the pairs, not the stale row", List.of(row(1, 0)), summary());
	}

	/**
	 * The guard for the reason the cache was removed rather than repaired: a benchmark's expected
	 * result changed through Benchmarks' own attribute writer is reflected on the next view.
	 */
	@Test
	public void aChangedExpectedResultIsReflectedOnTheNextView() {
		assertEquals(List.of(row(1, 0)), summary());

		Benchmarks.addBenchAttr(benchId, R.EXPECTED_RESULT, "CounterSatisfiable");

		assertEquals("now wrong against the new expected result", List.of(row(0, 1)), summary());
	}

	private String row(int solved, int wrong) {
		return "config=" + configId + " solved=" + solved + "/1 wrong=" + wrong;
	}

	private List<String> summary() {
		List<String> rows = new ArrayList<>();
		for (SolverStats s : Jobs.getAllJobStatsInJobSpaceHierarchyIncludeDeletedConfigs(
				Spaces.getJobSpace(spaceId), 1, PrimitivesToAnonymize.NONE, false)) {
			rows.add("config=" + s.getConfiguration().getId() + " solved=" + s.getCorrectOverCompleted()
					+ " wrong=" + s.getIncorrectJobPairs());
		}
		rows.sort(null);
		return rows;
	}

	private int cachedRows() throws SQLException {
		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement(
						"SELECT count(*) FROM starexec.job_stats WHERE job_space_id = ?")) {
			ps.setInt(1, spaceId);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private List<String> errors() {
		List<String> messages = new ArrayList<>();
		for (ILoggingEvent event : jobsLog.list) {
			if (event.getLevel().isGreaterOrEqual(Level.ERROR)) {
				messages.add(event.getFormattedMessage());
			}
		}
		return messages;
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
