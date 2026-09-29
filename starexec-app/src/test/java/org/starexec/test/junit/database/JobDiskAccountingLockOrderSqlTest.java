package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.data.database.Common;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * #188 follow-up: every routine that moves disk accounting between a job and its owner must
 * lock job_pairs, then jobs, then users -- the order {@code UpdatePairRunSolverStats} and
 * {@code RerunJobPairsBatchCore} use.
 *
 * <p>Each test holds one routine (the <em>holder</em>) inside its transaction, immediately
 * after it has updated the owner's {@code users} row, using an advisory-lock barrier fired by
 * a trigger. It then starts a stats write or a rerun for a pair owned by the same user
 * (the <em>contender</em>), waits until PostgreSQL reports the contender blocked by the
 * holder, and releases the barrier. A routine that takes {@code users} before {@code jobs}
 * leaves the two waiting on each other, and PostgreSQL aborts one with SQLSTATE 40P01; a
 * routine in the target order lets the contender finish once the holder commits.
 *
 * <p>Every test seeds its own user, job, pairs and node with absolute disk totals, so nothing
 * depends on other suites' rows, and removes them afterwards. All waits are bounded.
 *
 * <p>Skips unless a PostgreSQL instance is configured; point {@code STAREXEC_DB_URL} /
 * {@code STAREXEC_DB_USER} / {@code STAREXEC_DB_PASSWORD} at a <strong>disposable</strong>
 * database.
 */
public class JobDiskAccountingLockOrderSqlTest extends Common {

	private static final String PROBE_PREFIX = "probe_disk_lock_order_";
	private static final long TIMEOUT_SECONDS = 30L;

	// Absolute fixture values: user 10000, job 3072 = pair A 2048 + pair B 1024.
	private static final long USER_START = 10000L;
	private static final long PAIR_A_BYTES = 2048L;
	private static final long PAIR_B_BYTES = 1024L;

	private int userId;
	private int jobId;
	private int pairA;
	private int pairB;
	private int nodeId;
	private String nodeName;
	private String probeTrigger;
	private String probeFunction;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("JobDiskAccountingLockOrderSqlTest");
		Common.initialize();
	}

	@AfterClass
	public static void noProbeObjectsSurvive() throws SQLException {
		if (!DatabaseTestSupport.isDatabaseConfigured()) {
			return;
		}
		try (Connection con = Common.getConnection()) {
			assertEquals("the lock-order trigger must never outlive its test", 0,
					selectLong(con, "SELECT count(*) FROM pg_trigger WHERE left(tgname, "
							+ PROBE_PREFIX.length() + ") = '" + PROBE_PREFIX + "'"));
			assertEquals("the lock-order function must never outlive its test", 0,
					selectLong(con, "SELECT count(*) FROM pg_proc p"
							+ " JOIN pg_namespace n ON n.oid = p.pronamespace"
							+ " WHERE n.nspname = 'starexec' AND left(p.proname, "
							+ PROBE_PREFIX.length() + ") = '" + PROBE_PREFIX + "'"));
		}
	}

	@Before
	public void createFixture() throws SQLException {
		userId = 0;
		jobId = 0;
		nodeId = 0;
		probeTrigger = null;
		probeFunction = null;
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		nodeName = "disk-lock-order-" + suffix;
		try (Connection con = Common.getConnection()) {
			requireAutoCommit(con, "createFixture");
			userId = (int) selectLong(con,
					"INSERT INTO starexec.users (email, first_name, last_name, institution, created,"
							+ " password, disk_quota, disk_size) VALUES ('disk-lock-" + suffix
							+ "@example.invalid', 'Disk', 'Lock', 'test', NOW(), 'x', 1000000, "
							+ USER_START + ") RETURNING id");
			nodeId = (int) selectLong(con, "INSERT INTO starexec.nodes (name, status) VALUES ('"
					+ nodeName + "', 'ACTIVE') RETURNING id");
			jobId = (int) selectLong(con,
					"INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size) VALUES ("
							+ userId + ", 'disk-lock-order-test', 2, "
							+ (PAIR_A_BYTES + PAIR_B_BYTES) + ") RETURNING id");
			pairA = insertPair(con, PAIR_A_BYTES);
			pairB = insertPair(con, PAIR_B_BYTES);
		}
	}

	private int insertPair(Connection con, long stageBytes) throws SQLException {
		int id = (int) selectLong(con,
				"INSERT INTO starexec.job_pairs (job_id, status_code, primary_jobpair_data)"
						+ " VALUES (" + jobId + ", 4, 1) RETURNING id");
		exec(con, "INSERT INTO starexec.jobpair_stage_data (jobpair_id, stage_number, status_code,"
				+ " disk_size) VALUES (" + id + ", 1, 4, " + stageBytes + ")");
		return id;
	}

	@After
	public void dropFixture() throws SQLException {
		SQLException failure = null;
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			requireAutoCommit(con, "dropFixture");
			if (probeTrigger != null) {
				s.execute("DROP TRIGGER IF EXISTS " + probeTrigger + " ON starexec.users");
			}
			if (probeFunction != null) {
				s.execute("DROP FUNCTION IF EXISTS starexec." + probeFunction + "()");
			}
		} catch (SQLException e) {
			failure = e;
		}
		try (Connection con = Common.getConnection()) {
			requireAutoCommit(con, "dropFixture");
			if (userId != 0) {
				// jobs, job_pairs and jobpair_stage_data cascade from the user.
				exec(con, "DELETE FROM starexec.users WHERE id = " + userId);
			}
			if (nodeId != 0) {
				exec(con, "DELETE FROM starexec.nodes WHERE id = " + nodeId);
			}
		} catch (SQLException e) {
			if (failure == null) {
				failure = e;
			} else {
				failure.addSuppressed(e);
			}
		}
		if (failure != null) {
			throw failure;
		}
	}

	// ------------------------------------------------------------------
	// scenarios: holder x contender
	// ------------------------------------------------------------------

	@Test
	public void deleteJobPairVersusStatsForAnotherPair() throws Exception {
		run(con -> call(con, "SELECT starexec.DeleteJobPair(?)", pairA), statsFor(pairB, 4096L));
		// job 3072 - 2048 + 3072; the pair count drops by the deleted pair
		assertTotals(4096L, 11024L);
		assertEquals(1L, scalar("SELECT total_pairs FROM starexec.jobs WHERE id = " + jobId));
		assertEquals(0L, scalar("SELECT count(*) FROM starexec.job_pairs WHERE id = " + pairA));
		assertEquals(4096L, stageBytes(pairB));
	}

	@Test
	public void deleteJobPairVersusRerunOfAnotherPair() throws Exception {
		run(con -> call(con, "SELECT starexec.DeleteJobPair(?)", pairA), rerunFor(pairB));
		assertTotals(0L, USER_START - PAIR_A_BYTES - PAIR_B_BYTES);
		assertEquals(0L, scalar("SELECT count(*) FROM starexec.job_pairs WHERE id = " + pairA));
		assertEquals(0L, stageBytes(pairB));
	}

	@Test
	public void removeJobPairDiskSizeVersusStatsForAnotherPair() throws Exception {
		run(con -> call(con, "SELECT starexec.RemoveJobPairDiskSize(?)", pairA),
				statsFor(pairB, 4096L));
		assertTotals(4096L, 11024L);
		assertEquals(0L, stageBytes(pairA));
		assertEquals(4096L, stageBytes(pairB));
	}

	@Test
	public void removeJobPairDiskSizeVersusRerunOfAnotherPair() throws Exception {
		run(con -> call(con, "SELECT starexec.RemoveJobPairDiskSize(?)", pairA), rerunFor(pairB));
		assertTotals(0L, USER_START - PAIR_A_BYTES - PAIR_B_BYTES);
		assertEquals(0L, stageBytes(pairA));
		assertEquals(0L, stageBytes(pairB));
	}

	/**
	 * The same pair, not just the same job: a stats write holds the pair's stage row while it
	 * waits for the job, so a zeroing that reaches the stage row last, without the pair lock,
	 * closes a cycle through that row.
	 */
	@Test
	public void removeJobPairDiskSizeVersusStatsForTheSamePair() throws Exception {
		run(con -> call(con, "SELECT starexec.RemoveJobPairDiskSize(?)", pairA),
				statsFor(pairA, 4096L));
		// zeroed first (job 1024, user 7952), then the stats write charges the full 4096
		assertTotals(1024L + 4096L, USER_START - PAIR_A_BYTES + 4096L);
		assertEquals(4096L, stageBytes(pairA));
	}

	@Test
	public void deleteJobVersusStatsForAPairOfTheJob() throws Exception {
		run(con -> call(con, "SELECT starexec.DeleteJob(?)", jobId), statsFor(pairB, 4096L));
		// the delete refunds the whole 3072 and zeroes the job; the stats write then adds 3072
		assertTotals(3072L, USER_START);
		assertEquals(1L, scalar("SELECT CASE WHEN deleted THEN 1 ELSE 0 END FROM starexec.jobs"
				+ " WHERE id = " + jobId));
	}

	@Test
	public void updateJobDiskSizeVersusStatsForAPairOfTheJob() throws Exception {
		run(con -> {
			try (PreparedStatement ps = con.prepareStatement(
					"SELECT starexec.UpdateJobDiskSize(?, ?)")) {
				ps.setInt(1, jobId);
				ps.setLong(2, 5000L);
				ps.execute();
			}
		}, statsFor(pairB, 4096L));
		// user 10000 - 3072 + 5000, then + 3072 from the stats write
		assertTotals(5000L + 3072L, 15000L);
	}

	// ------------------------------------------------------------------
	// the two contenders
	// ------------------------------------------------------------------

	private ProbeTransaction statsFor(int pair, long bytes) {
		return con -> {
			try (PreparedStatement ps = con.prepareStatement(
					"CALL starexec.UpdatePairRunSolverStats(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
				ps.setInt(1, pair);
				ps.setString(2, nodeName);
				ps.setDouble(3, 12.0);
				ps.setDouble(4, 10.0);
				ps.setDouble(5, 8.0);
				ps.setDouble(6, 2.0);
				ps.setDouble(7, 256.0);
				ps.setLong(8, 128L);
				ps.setInt(9, 1);
				ps.setLong(10, bytes);
				ps.execute();
			}
		};
	}

	/** A rerun caller locks its pair first, then hands it to the shared reset. */
	private ProbeTransaction rerunFor(int pair) {
		return con -> {
			try (PreparedStatement lock = con.prepareStatement(
					"SELECT id FROM starexec.job_pairs WHERE id = ? FOR UPDATE")) {
				lock.setInt(1, pair);
				try (ResultSet rs = lock.executeQuery()) {
					assertTrue("the rerun caller locks its pair", rs.next());
				}
			}
			try (PreparedStatement ps = con.prepareStatement(
					"SELECT starexec.RerunJobPairsBatchCore(ARRAY[?]::INT[])")) {
				ps.setInt(1, pair);
				ps.execute();
			}
		};
	}

	private static void call(Connection con, String sql, int id) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			ps.setInt(1, id);
			ps.execute();
		}
	}

	// ------------------------------------------------------------------
	// the barrier protocol
	// ------------------------------------------------------------------

	private void run(ProbeTransaction holderBody, ProbeTransaction contenderBody)
			throws Exception {
		long barrierKey = UUID.randomUUID().getMostSignificantBits();
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		String holderApp = "se188f_" + suffix + "_holder";
		String contenderApp = "se188f_" + suffix + "_contender";
		installProbe(barrierKey, suffix, holderApp);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		Connection gate = null;
		boolean gateHeld = false;
		Future<?> holder = null;
		Future<?> contender = null;
		AtomicInteger holderPid = new AtomicInteger();
		AtomicInteger contenderPid = new AtomicInteger();
		try {
			gate = Common.getConnection();
			requireAutoCommit(gate, "advisory gate");
			advisoryLock(gate, barrierKey);
			gateHeld = true;

			holder = executor.submit((Callable<Void>) () -> {
				runTransaction(holderApp, holderPid, holderBody);
				return null;
			});
			try (Connection observer = Common.getConnection()) {
				awaitWait(observer, holderApp, -1, holder);
			}

			contender = executor.submit((Callable<Void>) () -> {
				runTransaction(contenderApp, contenderPid, contenderBody);
				return null;
			});
			try (Connection observer = Common.getConnection()) {
				awaitWait(observer, contenderApp, holderPid.get(), contender);
			}

			releaseAdvisoryLock(gate, barrierKey);
			gateHeld = false;
			awaitSuccessful(holder, "the holder routine");
			awaitSuccessful(contender, "the contending disk-accounting write");
		} finally {
			try {
				if (gate != null) {
					try {
						if (gateHeld) {
							releaseAdvisoryLock(gate, barrierKey);
						}
					} finally {
						try (Statement s = gate.createStatement()) {
							s.execute("SELECT pg_advisory_unlock_all()");
						} finally {
							gate.close();
						}
					}
				}
			} finally {
				executor.shutdown();
				awaitForCleanup(holder);
				awaitForCleanup(contender);
				if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
					executor.shutdownNow();
					assertTrue("the workers must terminate before the probe is dropped",
							executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
				}
			}
		}
	}

	/**
	 * Stops the holder after it has updated the owner's users row, and only the holder: the
	 * contender updates the same row and must not park on the barrier.
	 */
	private void installProbe(long barrierKey, String suffix, String holderApp)
			throws SQLException {
		probeTrigger = PROBE_PREFIX + "trg_" + suffix;
		probeFunction = PROBE_PREFIX + "fn_" + suffix;
		String function = "CREATE FUNCTION starexec." + probeFunction
				+ "() RETURNS trigger AS $$ BEGIN IF NEW.id = " + userId
				+ " AND current_setting('application_name') = '" + holderApp
				+ "' THEN PERFORM pg_advisory_xact_lock(" + barrierKey + "); END IF;"
				+ " RETURN NEW; END; $$ LANGUAGE plpgsql";
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			requireAutoCommit(con, "installProbe");
			s.execute(function);
			s.execute("CREATE TRIGGER " + probeTrigger
					+ " AFTER UPDATE OF disk_size ON starexec.users FOR EACH ROW"
					+ " EXECUTE FUNCTION starexec." + probeFunction + "()");
		}
	}

	@FunctionalInterface
	private interface ProbeTransaction {
		void run(Connection con) throws Exception;
	}

	private static void runTransaction(String applicationName, AtomicInteger backendPid,
			ProbeTransaction body) throws Exception {
		try (Connection con = Common.getConnection()) {
			requireAutoCommit(con, "runTransaction");
			con.setAutoCommit(false);
			boolean finished = false;
			Throwable failure = null;
			try {
				try (PreparedStatement ps = con.prepareStatement(
						"SELECT set_config('application_name', ?, true)")) {
					ps.setString(1, applicationName);
					ps.executeQuery().close();
				}
				try (Statement s = con.createStatement()) {
					s.execute("SET LOCAL statement_timeout = '30s'");
				}
				backendPid.set((int) selectLong(con, "SELECT pg_backend_pid()"));
				body.run(con);
				con.commit();
				finished = true;
			} catch (Throwable primary) {
				failure = primary;
				try {
					con.rollback();
					finished = true;
				} catch (SQLException rollbackFailure) {
					primary.addSuppressed(rollbackFailure);
				}
			} finally {
				// Only after an explicit commit or rollback: flipping autoCommit with an
				// open transaction would commit it.
				if (finished) {
					try {
						con.setAutoCommit(true);
					} catch (SQLException e) {
						if (failure == null) {
							failure = e;
						} else {
							failure.addSuppressed(e);
						}
					}
				}
			}
			if (failure instanceof Exception) {
				throw (Exception) failure;
			}
			if (failure != null) {
				throw (Error) failure;
			}
		}
	}

	/**
	 * Waits, bounded, until the named backend is in a lock wait: on the advisory barrier when
	 * {@code blockerPid} is negative, otherwise blocked by that backend.
	 */
	private static void awaitWait(Connection observer, String applicationName, int blockerPid,
			Future<?> worker) throws Exception {
		boolean advisory = blockerPid < 0;
		if (!advisory) {
			assertTrue("the holder backend pid must be known", blockerPid > 0);
		}
		String sql = advisory
				? "SELECT EXISTS (SELECT 1 FROM pg_stat_activity a WHERE a.application_name = ?"
						+ " AND a.wait_event_type = 'Lock' AND a.wait_event = 'advisory')"
				: "SELECT EXISTS (SELECT 1 FROM pg_stat_activity a WHERE a.application_name = ?"
						+ " AND a.wait_event_type = 'Lock'"
						+ " AND ? = ANY(pg_blocking_pids(a.pid)))";
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
		try (PreparedStatement ps = observer.prepareStatement(sql)) {
			while (System.nanoTime() < deadline) {
				ps.setString(1, applicationName);
				if (!advisory) {
					ps.setInt(2, blockerPid);
				}
				try (ResultSet rs = ps.executeQuery()) {
					assertTrue(rs.next());
					if (rs.getBoolean(1)) {
						return;
					}
				}
				if (worker.isDone()) {
					try {
						worker.get();
						fail(applicationName + " completed before reaching its expected lock wait");
					} catch (ExecutionException failure) {
						throw new AssertionError(applicationName
								+ " failed before reaching its lock wait", failure.getCause());
					}
				}
				Thread.sleep(20L);
			}
		}
		throw new AssertionError("timed out waiting for " + applicationName + " to reach "
				+ (advisory ? "the barrier" : "a wait on the holder"));
	}

	private static void advisoryLock(Connection con, long key) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement("SELECT pg_advisory_lock(?)")) {
			ps.setLong(1, key);
			ps.executeQuery().close();
		}
	}

	private static void releaseAdvisoryLock(Connection con, long key) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement("SELECT pg_advisory_unlock(?)")) {
			ps.setLong(1, key);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next());
				assertTrue("the test gate was held by its controller", rs.getBoolean(1));
			}
		}
	}

	private static void awaitSuccessful(Future<?> worker, String operation) throws Exception {
		try {
			worker.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (ExecutionException failure) {
			assertFalse(operation + " must not deadlock (SQLSTATE 40P01)",
					hasSqlState(failure.getCause(), "40P01"));
			throw new AssertionError(operation + " failed", failure.getCause());
		} catch (TimeoutException timeout) {
			throw new AssertionError(operation + " did not finish after the barrier was released",
					timeout);
		}
	}

	private static void awaitForCleanup(Future<?> worker) throws InterruptedException {
		if (worker == null) {
			return;
		}
		try {
			worker.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (ExecutionException | TimeoutException ignored) {
			// The main assertion reports operation errors; this wait only releases every
			// connection and row lock before the fixture is removed.
		}
	}

	private static boolean hasSqlState(Throwable failure, String sqlState) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException) {
				for (SQLException sql = (SQLException) cause; sql != null;
						sql = sql.getNextException()) {
					if (sqlState.equals(sql.getSQLState())) {
						return true;
					}
				}
			}
		}
		return false;
	}

	// ------------------------------------------------------------------
	// assertions and small helpers
	// ------------------------------------------------------------------

	private void assertTotals(long jobBytes, long userBytes) throws SQLException {
		assertEquals("job disk_size", jobBytes,
				scalar("SELECT disk_size FROM starexec.jobs WHERE id = " + jobId));
		assertEquals("user disk_size", userBytes,
				scalar("SELECT disk_size FROM starexec.users WHERE id = " + userId));
	}

	private long stageBytes(int pair) throws SQLException {
		return scalar("SELECT disk_size FROM starexec.jobpair_stage_data WHERE jobpair_id = "
				+ pair + " AND stage_number = 1");
	}

	private long scalar(String sql) throws SQLException {
		try (Connection con = Common.getConnection()) {
			return selectLong(con, sql);
		}
	}

	private static long selectLong(Connection con, String sql) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
			if (!rs.next()) {
				throw new IllegalStateException("Query returned no rows: " + sql);
			}
			return rs.getLong(1);
		}
	}

	private static void exec(Connection con, String sql) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			ps.executeUpdate();
		}
	}

	/** The pool does not reset connections; fail loudly if one arrives mid-transaction. */
	private static void requireAutoCommit(Connection con, String operation) throws SQLException {
		if (con.getAutoCommit()) {
			return;
		}
		try {
			con.rollback();
		} finally {
			con.setAutoCommit(true);
		}
		fail(operation + " borrowed a pooled connection with autoCommit=false");
	}
}
