package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.constants.R;
import org.starexec.test.util.DatabaseTestSupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code GetJobStatus} and {@code GetJobStatusDetail} are job-scoped, and the six status
 * functions have exactly one definition whatever order Flyway re-applies the repeatable files in.
 *
 * <p>Two defects are pinned here.
 * <ul>
 * <li>Scan: the functions used {@code _jobId IN (SELECT job_id FROM job_pairs WHERE status_code
 * IN (...))}, an uncorrelated subquery that walked the pending pairs of every job on each call,
 * and they run once per row of every job listing.</li>
 * <li>Duplicates: {@code R__functions.sql} and {@code R__procedures_and_views.sql} both defined
 * {@code GetCompletePairs, GetErrorPairs, GetJobStatusDetail, GetJobStatus, GetPendingPairs} and
 * {@code IsPublic} with different return types. On an upgrade where only {@code R__functions.sql}
 * changed, Flyway re-ran only that file and {@code GetJobStatusDetail} became INTEGER, which
 * {@code Jobs.getJobStatus} cannot read.</li>
 * </ul>
 *
 * <p>Every test runs on a private connection inside a transaction that is always rolled back
 * (DDL included, PostgreSQL DDL is transactional), so nothing is left behind and the pooled
 * connections used by other suites are untouched.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*} variables
 * at a <strong>disposable</strong> database.
 */
public class JobStatusFunctionsSqlTest {

	private static final String[] SIX = {
		"getcompletepairs", "geterrorpairs", "getjobstatusdetail", "getjobstatus",
		"getpendingpairs", "ispublic"
	};

	/** The pending set that GetJobStatus and GetPendingPairs test for. */
	private static final Set<Integer> PENDING = new HashSet<>(Arrays.asList(1, 2, 4, 19, 20, 22));

	private Connection con;
	private int adminUserId;
	private int plainUserId;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("JobStatusFunctionsSqlTest");
	}

	@Before
	public void open() throws SQLException {
		con = DriverManager.getConnection(R.POSTGRES_URL, R.POSTGRES_USERNAME, R.POSTGRES_PASSWORD);
		con.setAutoCommit(false);
		try (Statement st = con.createStatement()) {
			// The same search path Flyway's defaultSchema gives R__functions.sql, whose
			// statements do not qualify job_pairs.
			st.execute("SET LOCAL search_path = starexec, public");
		}
		adminUserId = selectInt("SELECT u.id FROM starexec.users u JOIN starexec.user_roles r"
				+ " ON r.email = u.email WHERE r.role = 'admin' ORDER BY u.id LIMIT 1");
		plainUserId = selectInt("SELECT u.id FROM starexec.users u WHERE NOT EXISTS"
				+ " (SELECT 1 FROM starexec.user_roles r WHERE r.email = u.email"
				+ " AND r.role IN ('admin', 'developer')) ORDER BY u.id LIMIT 1");
	}

	@After
	public void rollBack() throws SQLException {
		if (con != null) {
			try {
				con.rollback();
			} finally {
				con.close();
			}
		}
	}

	// ------------------------------------------------------------------------------------
	// Functional equivalence with the pre-fix definitions
	// ------------------------------------------------------------------------------------

	/** Every status code on its own: same answer as the old bodies, and the expected literal. */
	@Test
	public void everyStatusCodeAnswersLikeTheOldDefinition() throws SQLException {
		createReferenceFunctions();
		for (int code = 0; code <= 27; code++) {
			int job = insertJob(plainUserId, false, false, false);
			insertPairs(job, code, 1);
			assertSameAsReference(job, "single pair with status_code " + code);
			assertEquals("GetJobStatus for status_code " + code,
					PENDING.contains(code) ? "incomplete" : "complete", status(job));
		}
	}

	/** Jobs holding several kinds of pair, no pair at all, and a neighbour that is pending. */
	@Test
	public void mixedAndEmptyJobsAnswerLikeTheOldDefinition() throws SQLException {
		createReferenceFunctions();
		int pendingNeighbour = insertJob(plainUserId, false, false, false);
		insertPairs(pendingNeighbour, 22, 3);

		int empty = insertJob(plainUserId, false, false, false);
		assertSameAsReference(empty, "job without pairs");
		assertEquals("a job with no pairs is complete even though another job is pending",
				"complete", status(empty));
		assertEquals("COMPLETE", detail(empty));

		int finishedOnly = insertJob(plainUserId, false, false, false);
		insertPairs(finishedOnly, 7, 2);
		insertPairs(finishedOnly, 8, 1);
		assertSameAsReference(finishedOnly, "finished pairs beside a pending job");
		assertEquals("complete", status(finishedOnly));

		int oneStraggler = insertJob(plainUserId, false, false, false);
		insertPairs(oneStraggler, 7, 5);
		insertPairs(oneStraggler, 4, 1);
		assertSameAsReference(oneStraggler, "one pending pair among finished ones");
		assertEquals("incomplete", status(oneStraggler));
		assertEquals("RUNNING", detail(oneStraggler));

		assertEquals("PROCESSING", detail(pendingNeighbour));
		assertEquals("unknown job id", "complete", status(2_000_000_000));
		assertEquals("unknown job id, detail", "COMPLETE", detail(2_000_000_000));
		assertNullArgumentMatchesReference();
	}

	/** deleted / killed / paused, alone and combined with pending pairs of each class. */
	@Test
	public void jobFlagsAndPairClassesAnswerLikeTheOldDefinition() throws SQLException {
		createReferenceFunctions();
		int[] pairCodes = {7, 1, 4, 19, 22};
		for (int mask = 0; mask < 8; mask++) {
			for (int code : pairCodes) {
				int job = insertJob(plainUserId, (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0);
				insertPairs(job, code, 2);
				assertSameAsReference(job, "flags mask " + mask + ", pairs at " + code);
			}
		}
		int deleted = insertJob(plainUserId, true, true, true);
		assertEquals("DELETED wins over KILLED and PAUSED", "DELETED", detail(deleted));
		int killed = insertJob(plainUserId, false, true, true);
		assertEquals("KILLED", detail(killed));
		int paused = insertJob(plainUserId, false, false, true);
		assertEquals("PAUSED", detail(paused));
	}

	/** GLOBAL_PAUSE applies to a queued job of a plain user, not to an admin's job. */
	@Test
	public void globalPauseAnswersLikeTheOldDefinition() throws SQLException {
		createReferenceFunctions();
		try (Statement st = con.createStatement()) {
			st.execute("UPDATE starexec.system_flags SET paused = TRUE");
		}
		int plain = insertJob(plainUserId, false, false, false);
		insertPairs(plain, 2, 2);
		int admin = insertJob(adminUserId, false, false, false);
		insertPairs(admin, 2, 2);
		int plainDone = insertJob(plainUserId, false, false, false);
		insertPairs(plainDone, 7, 2);
		assertEquals("GLOBAL_PAUSE", detail(plain));
		assertEquals("an admin's job is exempt from the global pause", "RUNNING", detail(admin));
		assertEquals("a finished job is unaffected", "COMPLETE", detail(plainDone));
		assertSameAsReference(plain, "global pause, plain user");
		assertSameAsReference(admin, "global pause, admin");
		assertSameAsReference(plainDone, "global pause, finished job");
	}

	/** The four counting functions and IsPublic keep the semantics their callers rely on. */
	@Test
	public void countingFunctionsAndIsPublicKeepTheirSemantics() throws SQLException {
		int job = insertJob(plainUserId, false, false, false);
		insertPairs(job, 7, 2);   // complete
		insertPairs(job, 14, 1);  // complete (resource limit)
		insertPairs(job, 25, 1);  // counted as both complete and error
		insertPairs(job, 8, 3);   // error
		insertPairs(job, 4, 4);   // pending
		insertPairs(job, 19, 1);  // pending
		assertEquals(4L, selectLong("SELECT starexec.GetCompletePairs(" + job + ")"));
		assertEquals(4L, selectLong("SELECT starexec.GetErrorPairs(" + job + ")"));
		assertEquals(5L, selectLong("SELECT starexec.GetPendingPairs(" + job + ")"));
		assertEquals(0L, selectLong("SELECT starexec.GetPendingPairs(2000000000)"));
		int spaceId = selectInt("SELECT min(id) FROM starexec.spaces");
		try (Statement st = con.createStatement()) {
			st.execute("UPDATE starexec.spaces SET public_access = TRUE WHERE id = " + spaceId);
			assertTrue(selectBoolean("SELECT starexec.IsPublic(" + spaceId + ")"));
			st.execute("UPDATE starexec.spaces SET public_access = FALSE WHERE id = " + spaceId);
			assertEquals(false, selectBoolean("SELECT starexec.IsPublic(" + spaceId + ")"));
		}
	}

	// ------------------------------------------------------------------------------------
	// Scan bound
	// ------------------------------------------------------------------------------------

	/**
	 * With about 100k pending pairs spread over 2000 jobs, asking one job for its status reads
	 * that job's pairs, not everyone's. Counted with the transaction-local statistics of
	 * job_pairs (rows returned by scans), which do not depend on how fast the machine is:
	 * the old bodies read about 100k rows per call, the job-scoped ones a handful.
	 */
	@Test
	public void statusOfOneJobDoesNotReadThePendingSetOfAllJobs() throws SQLException {
		int jobs = 2000;
		int pairsPerJob = 50;
		int firstJob;
		try (Statement st = con.createStatement()) {
			st.execute("INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size)"
					+ " SELECT " + plainUserId + ", 'jsf-scan-' || g, " + pairsPerJob + ", 0"
					+ " FROM generate_series(1, " + jobs + ") g");
			st.execute("INSERT INTO starexec.job_pairs (job_id, bench_name, status_code)"
					+ " SELECT j.id, 'b', (ARRAY[1, 2, 4, 19, 20, 22])[1 + (g % 6)]"
					+ " FROM starexec.jobs j, generate_series(1, " + pairsPerJob + ") g"
					+ " WHERE j.name LIKE 'jsf-scan-%'");
			st.execute("ANALYZE starexec.job_pairs");
		}
		assertEquals((long) jobs * pairsPerJob, selectLong("SELECT count(*) FROM starexec.job_pairs"
				+ " WHERE bench_name = 'b' AND status_code IN (1, 2, 4, 19, 20, 22)"
				+ " AND job_id IN (SELECT id FROM starexec.jobs WHERE name LIKE 'jsf-scan-%')"));
		firstJob = selectInt("SELECT min(id) FROM starexec.jobs WHERE name LIKE 'jsf-scan-%'");

		int calls = 20;
		long before = rowsReadFromJobPairs();
		for (int i = 0; i < calls; i++) {
			assertEquals("incomplete", status(firstJob + i));
		}
		long statusReads = rowsReadFromJobPairs() - before;

		before = rowsReadFromJobPairs();
		for (int i = 0; i < calls; i++) {
			// every pair of these jobs is pending, and one of them is in status 22
			assertEquals("PROCESSING", detail(firstJob + i));
		}
		long detailReads = rowsReadFromJobPairs() - before;

		// One job owns 50 pairs. Allow generous slack for index entries plus heap rows,
		// and stay orders of magnitude below the 100k rows per call of the uncorrelated form.
		long bound = (long) calls * pairsPerJob * 4;
		assertTrue("GetJobStatus read " + statusReads + " rows for " + calls + " calls, bound "
				+ bound, statusReads <= bound);
		assertTrue("GetJobStatusDetail read " + detailReads + " rows for " + calls + " calls, bound "
				+ bound, detailReads <= bound);
	}

	// ------------------------------------------------------------------------------------
	// Repeatable-migration upgrade path
	// ------------------------------------------------------------------------------------

	/**
	 * A database that holds the pre-fix state (both files applied, TEXT wins) and is upgraded by
	 * re-running only R__functions.sql, as Flyway does when only that file's checksum changed.
	 */
	@Test
	public void rerunningOnlyRFunctionsKeepsTheReturnTypes() throws Exception {
		installPreFixState();
		assertReturnTypes("pre-fix state, as a fresh install leaves it");

		// A cosmetic edit is all it takes to make Flyway re-run this file on its own.
		execScript(resource("/db/migration/R__functions.sql") + "\n-- cosmetic edit\n");
		assertReturnTypes("after re-running only R__functions.sql");
		assertCallersStillWork();
	}

	/** Both files in Flyway's order (description order: functions, then procedures and views). */
	@Test
	public void rerunningBothFilesInFlywayOrderKeepsTheReturnTypes() throws Exception {
		installPreFixState();
		execScript(resource("/db/migration/R__functions.sql"));
		execScript(statusFunctionsSectionOf(resource("/db/migration/R__procedures_and_views.sql")));
		assertReturnTypes("after re-running both files");
		assertCallersStillWork();
	}

	/** And the other way round, so that neither file can win by ordering. */
	@Test
	public void rerunningBothFilesInReverseOrderKeepsTheReturnTypes() throws Exception {
		installPreFixState();
		execScript(statusFunctionsSectionOf(resource("/db/migration/R__procedures_and_views.sql")));
		execScript(resource("/db/migration/R__functions.sql"));
		assertReturnTypes("after re-running both files, reverse order");
		assertCallersStillWork();
	}

	// ------------------------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------------------------

	private void installPreFixState() throws Exception {
		execScript(resource("/db/status-functions-base/R__functions.sql"));
		execScript(resource("/db/status-functions-base/R__procedures_and_views.status_functions.sql"));
	}

	private void assertReturnTypes(String when) throws SQLException {
		assertEquals("GetJobStatusDetail return type " + when, "text", resultType("getjobstatusdetail"));
		assertEquals("GetJobStatus return type " + when, "text", resultType("getjobstatus"));
		assertEquals("GetCompletePairs return type " + when, "bigint", resultType("getcompletepairs"));
		assertEquals("GetErrorPairs return type " + when, "bigint", resultType("geterrorpairs"));
		assertEquals("GetPendingPairs return type " + when, "bigint", resultType("getpendingpairs"));
		assertEquals("IsPublic return type " + when, "boolean", resultType("ispublic"));
		for (String name : SIX) {
			assertEquals("exactly one " + name + " overload " + when, 1L, selectLong(
					"SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
							+ " WHERE n.nspname = 'starexec' AND p.proname = '" + name + "'"));
		}
	}

	/** What Jobs.getJobStatus, NotifyUsersOfJobs and SubscribeUserToJob need from the functions. */
	private void assertCallersStillWork() throws SQLException {
		int job = insertJob(plainUserId, false, false, false);
		assertEquals("COMPLETE", detail(job)); // Jobs.getJobStatus maps this with JobStatus.valueOf
		insertPairs(job, 4, 1);
		assertEquals("RUNNING", detail(job));
		assertEquals("incomplete", status(job));
		try (Statement st = con.createStatement()) {
			st.execute("INSERT INTO starexec.notifications_jobs_users (user_id, job_id, last_seen_status)"
					+ " VALUES (" + plainUserId + ", " + job + ", 'COMPLETE')");
			try (ResultSet rs = st.executeQuery("SELECT status FROM starexec.NotifyUsersOfJobs()"
					+ " WHERE \"job\" = " + job)) {
				assertTrue("NotifyUsersOfJobs reports the job whose status moved", rs.next());
				assertEquals("RUNNING", rs.getString(1));
			}
		}
	}

	private String resultType(String function) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(
				"SELECT pg_get_function_result(p.oid) FROM pg_proc p JOIN pg_namespace n"
						+ " ON n.oid = p.pronamespace WHERE n.nspname = 'starexec' AND p.proname = ?")) {
			ps.setString(1, function);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(function + " exists", rs.next());
				return rs.getString(1);
			}
		}
	}

	/**
	 * The section of R__procedures_and_views.sql that defines the six functions. Both markers must
	 * be present, so a reshuffled file fails loudly instead of testing nothing.
	 */
	private static String statusFunctionsSectionOf(String script) {
		int start = script.indexOf("-- FUNCTIONS from StarFunctions.sql");
		int end = script.indexOf("-- Atomically sets the status of a job pair and its stages with precision");
		assertTrue("section start marker present", start >= 0);
		assertTrue("section end marker present", end > start);
		String section = script.substring(start, end).toLowerCase();
		for (String name : SIX) {
			assertTrue(name + " is defined in the section", section.contains("function starexec." + name));
		}
		return script.substring(start, end);
	}

	private static String resource(String path) throws IOException {
		try (InputStream in = JobStatusFunctionsSqlTest.class.getResourceAsStream(path)) {
			assertNotNull("classpath resource " + path, in);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private void execScript(String sql) throws SQLException {
		try (Statement st = con.createStatement()) {
			st.execute(sql);
		}
	}

	/** The pre-fix bodies, verbatim, as throw-away functions that vanish with the transaction. */
	private void createReferenceFunctions() throws SQLException {
		execScript(
				"CREATE FUNCTION pg_temp.old_status(_jobId INT) RETURNS TEXT AS $$\n"
				+ "DECLARE status TEXT;\n"
				+ "BEGIN\n"
				+ "    SELECT CASE WHEN _jobId IN (\n"
				+ "        SELECT job_id FROM starexec.job_pairs WHERE status_code IN (1, 2, 4, 19, 20, 22)\n"
				+ "    ) THEN 'incomplete' ELSE 'complete' END INTO status;\n"
				+ "    RETURN status;\n"
				+ "END;\n"
				+ "$$ LANGUAGE plpgsql;\n"
				+ "CREATE FUNCTION pg_temp.old_detail(_jobId INT) RETURNS TEXT AS $$\n"
				+ "DECLARE status TEXT;\n"
				+ "BEGIN\n"
				+ "    SELECT CASE\n"
				+ "        WHEN _jobId IN (SELECT id FROM starexec.jobs WHERE deleted) THEN 'DELETED'\n"
				+ "        WHEN _jobId IN (SELECT id FROM starexec.jobs WHERE killed) THEN 'KILLED'\n"
				+ "        WHEN _jobId IN (SELECT id FROM starexec.jobs WHERE paused) THEN 'PAUSED'\n"
				+ "        WHEN _jobId IN (SELECT job_id FROM starexec.job_pairs WHERE status_code = 22) THEN 'PROCESSING'\n"
				+ "        WHEN _jobId IN (SELECT job_id FROM starexec.job_pairs WHERE status_code = 19) THEN 'PROCESSING_RESULTS'\n"
				+ "        WHEN _jobId IN (SELECT job_id FROM starexec.job_pairs WHERE status_code BETWEEN 1 AND 6) THEN\n"
				+ "            CASE\n"
				+ "                WHEN EXISTS (SELECT 1 FROM starexec.system_flags WHERE paused = TRUE)\n"
				+ "                     AND _jobId NOT IN (\n"
				+ "                         SELECT j.id FROM starexec.jobs j\n"
				+ "                         JOIN starexec.users u ON j.user_id = u.id\n"
				+ "                         JOIN starexec.user_roles ur ON ur.email = u.email\n"
				+ "                         WHERE ur.role IN ('admin', 'developer')\n"
				+ "                     )\n"
				+ "                THEN 'GLOBAL_PAUSE'\n"
				+ "                ELSE 'RUNNING'\n"
				+ "            END\n"
				+ "        ELSE 'COMPLETE'\n"
				+ "    END INTO status;\n"
				+ "    RETURN status;\n"
				+ "END;\n"
				+ "$$ LANGUAGE plpgsql;");
	}

	private void assertSameAsReference(int job, String what) throws SQLException {
		assertEquals("GetJobStatus vs old body: " + what,
				selectString("SELECT pg_temp.old_status(" + job + ")"), status(job));
		assertEquals("GetJobStatusDetail vs old body: " + what,
				selectString("SELECT pg_temp.old_detail(" + job + ")"), detail(job));
	}

	private void assertNullArgumentMatchesReference() throws SQLException {
		assertEquals(selectString("SELECT pg_temp.old_status(NULL)"),
				selectString("SELECT starexec.GetJobStatus(NULL)"));
		assertEquals(selectString("SELECT pg_temp.old_detail(NULL)"),
				selectString("SELECT starexec.GetJobStatusDetail(NULL)"));
	}

	private String status(int jobId) throws SQLException {
		return selectString("SELECT starexec.GetJobStatus(" + jobId + ")");
	}

	private String detail(int jobId) throws SQLException {
		return selectString("SELECT starexec.GetJobStatusDetail(" + jobId + ")");
	}

	private long rowsReadFromJobPairs() throws SQLException {
		return selectLong("SELECT pg_stat_get_xact_tuples_returned('starexec.job_pairs'::regclass)");
	}

	private int insertJob(int userId, boolean deleted, boolean killed, boolean paused) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size, deleted, killed, paused)"
						+ " VALUES (?, 'jsf-test', 1, 0, ?, ?, ?) RETURNING id")) {
			ps.setInt(1, userId);
			ps.setBoolean(2, deleted);
			ps.setBoolean(3, killed);
			ps.setBoolean(4, paused);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next());
				return rs.getInt(1);
			}
		}
	}

	private void insertPairs(int jobId, int statusCode, int count) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.job_pairs (job_id, bench_name, status_code)"
						+ " SELECT ?, 'jsf-bench', ? FROM generate_series(1, ?)")) {
			ps.setInt(1, jobId);
			ps.setInt(2, statusCode);
			ps.setInt(3, count);
			assertEquals(count, ps.executeUpdate());
		}
	}

	private String selectString(String sql) throws SQLException {
		try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			assertTrue(sql, rs.next());
			return rs.getString(1);
		}
	}

	private long selectLong(String sql) throws SQLException {
		try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			assertTrue(sql, rs.next());
			return rs.getLong(1);
		}
	}

	private int selectInt(String sql) throws SQLException {
		return (int) selectLong(sql);
	}

	private boolean selectBoolean(String sql) throws SQLException {
		try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			assertTrue(sql, rs.next());
			return rs.getBoolean(1);
		}
	}
}
