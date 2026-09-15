package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.constants.PaginationQueries;
import org.starexec.data.database.Common;
import org.starexec.data.database.Jobs;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;
import org.starexec.util.DataTablesQuery;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * A job space's pair listing -- the table and its record counts -- finds the pairs in the space
 * hierarchy without the job's stats having been viewed first (#207).
 *
 * <p>Both queries join {@code job_space_closure}, which is filled on demand by
 * {@code Spaces.updateJobSpaceClosureTable}. Only the stats paths filled it, so a listing opened
 * before them returned no pairs and a count of 0.
 *
 * <p>The fixture is a job whose root space holds one completed pair and has one subspace holding
 * another, both run by the same configuration. Nothing in it touches the stats.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*} variables
 * at a <strong>disposable</strong> database: these tests insert and delete rows.
 */
public class PairListingClosureSqlTest extends Common {

	private int userId;
	private int jobId;
	private int rootSpace;
	private int subSpace;
	private int solverId;
	private int configId;
	private int benchId;
	private int rootPair;
	private int subPair;

	@BeforeClass
	public static void requireDatabase() throws Exception {
		DatabaseTestSupport.assumeDatabaseAvailable("PairListingClosureSqlTest");
		Common.initialize();
		PaginationQueries.loadPaginationQueries();
	}

	@Before
	public void seed() throws SQLException {
		try (Connection con = Common.getConnection()) {
			userId = selectInt(con, "SELECT min(id) FROM starexec.users");
			jobId = returningId(con,
					"INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size)"
							+ " VALUES (?, 'pair-listing-closure-test', 2, 0) RETURNING id",
					userId);
			rootSpace = returningId(con,
					"INSERT INTO starexec.job_spaces (job_id, name) VALUES (?, 'root') RETURNING id", jobId);
			subSpace = returningId(con,
					"INSERT INTO starexec.job_spaces (job_id, name) VALUES (?, 'sub') RETURNING id", jobId);
			try (PreparedStatement ps = con.prepareStatement(
					"INSERT INTO starexec.job_space_assoc (space_id, child_id) VALUES (?, ?)")) {
				ps.setInt(1, rootSpace);
				ps.setInt(2, subSpace);
				assertEquals(1, ps.executeUpdate());
			}
			solverId = returningId(con,
					"INSERT INTO starexec.solvers (user_id, name, path, disk_size)"
							+ " VALUES (?, 'pair-listing-solver', '/s', 0) RETURNING id",
					userId);
			configId = returningId(con,
					"INSERT INTO starexec.configurations (solver_id, name, updated)"
							+ " VALUES (?, 'pair-listing-config', NOW()) RETURNING id",
					solverId);
			benchId = returningId(con,
					"INSERT INTO starexec.benchmarks (user_id, name, uploaded, path, disk_size)"
							+ " VALUES (?, 'pair-listing-bench', NOW(), '/b', 0) RETURNING id",
					userId);
			rootPair = insertPair(con, rootSpace);
			subPair = insertPair(con, subSpace);
		}
		assertEquals("precondition: nothing has filled the closure for the root", 0, closureRows(rootSpace));
		assertEquals("precondition: nothing has filled the closure for the subspace", 0, closureRows(subSpace));
	}

	@After
	public void dropFixture() throws SQLException {
		if (jobId == 0) {
			return;
		}
		try (Connection con = Common.getConnection()) {
			// Stage data, attributes, closure and association rows go with their pair and spaces.
			update(con, "DELETE FROM starexec.job_pair_completion WHERE pair_id IN"
					+ " (SELECT id FROM starexec.job_pairs WHERE job_id = ?)", jobId);
			update(con, "DELETE FROM starexec.job_pairs WHERE job_id = ?", jobId);
			update(con, "DELETE FROM starexec.job_spaces WHERE job_id = ?", jobId);
			update(con, "DELETE FROM starexec.solvers WHERE id = ?", solverId);
			update(con, "DELETE FROM starexec.benchmarks WHERE id = ?", benchId);
			update(con, "DELETE FROM starexec.jobs WHERE id = ?", jobId);
		}
	}

	@Test
	public void theCountFindsTheHierarchysPairs() {
		assertEquals("root: its own pair and the subspace's", 2, count(rootSpace));
		assertEquals("subspace: its own pair", 1, count(subSpace));
	}

	@Test
	public void theTableListsTheHierarchysPairs() {
		assertEquals("root: its own pair and the subspace's", List.of(rootPair, subPair), listedPairs(rootSpace));
		assertEquals("subspace: its own pair", List.of(subPair), listedPairs(subSpace));
	}

	/** Filling the closure on every read does not add rows once they are there. */
	@Test
	public void repeatedListingsDoNotDuplicateClosureRows() throws SQLException {
		for (int i = 0; i < 2; i++) {
			for (int space : new int[] {rootSpace, subSpace}) {
				count(space);
				listedPairs(space);
			}
		}

		assertEquals("root: itself and the subspace", 2, closureRows(rootSpace));
		assertEquals("subspace: itself", 1, closureRows(subSpace));
	}

	private int count(int jobSpaceId) {
		return Jobs.getCountOfJobPairsByConfigInJobSpaceHierarchy(jobSpaceId, configId, "all", "", 0);
	}

	private List<Integer> listedPairs(int jobSpaceId) {
		List<JobPair> pairs = Jobs.getJobPairsForTableInJobSpaceHierarchy(
				jobSpaceId, new DataTablesQuery(0, 10, 0, true, ""), configId, 0, "all");
		assertNotNull("the listing loads", pairs);
		List<Integer> ids = new ArrayList<>();
		for (JobPair pair : pairs) {
			ids.add(pair.getId());
		}
		ids.sort(null);
		return ids;
	}

	private int insertPair(Connection con, int jobSpaceId) throws SQLException {
		int complete = StatusCode.STATUS_COMPLETE.getVal();
		int pairId = returningId(con,
				"INSERT INTO starexec.job_pairs (job_id, job_space_id, bench_id, bench_name,"
						+ " status_code, primary_jobpair_data)"
						+ " VALUES (?, ?, ?, 'pair-listing-bench', ?, 1) RETURNING id",
				jobId, jobSpaceId, benchId, complete);
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.jobpair_stage_data (jobpair_id, stage_number, status_code,"
						+ " solver_id, solver_name, config_id, config_name, wallclock, cpu, disk_size)"
						+ " VALUES (?, 1, ?, ?, 'pair-listing-solver', ?, 'pair-listing-config', 1, 1, 0)")) {
			ps.setInt(1, pairId);
			ps.setInt(2, complete);
			ps.setInt(3, solverId);
			ps.setInt(4, configId);
			assertEquals(1, ps.executeUpdate());
		}
		returningId(con,
				"INSERT INTO starexec.job_pair_completion (pair_id) VALUES (?) RETURNING completion_id", pairId);
		return pairId;
	}

	private static int closureRows(int ancestor) throws SQLException {
		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement(
						"SELECT count(*) FROM starexec.job_space_closure WHERE ancestor = ?")) {
			ps.setInt(1, ancestor);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
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
