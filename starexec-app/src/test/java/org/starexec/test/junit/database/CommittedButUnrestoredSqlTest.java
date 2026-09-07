package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.data.database.Common;
import org.starexec.test.util.DatabaseTestSupport;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * What happens when the commit succeeds and the cleanup after it does not.
 *
 * <p>These two facts point opposite ways and both have to survive: the write is durable, and
 * the connection is unusable. Getting either wrong is worse than the original failure --
 * telling the caller nothing was created invites a retry that creates it twice, and returning
 * the connection to the pool hands the next request a broken or half-transactional one.
 *
 * <p>Against the real Tomcat JDBC pool, not a stub. Stubs can prove the ordering --
 * {@code TransactionOwnershipTest} does -- but only the real pool can show whether the
 * poisoned physical connection is recycled, and here it plainly is: without the discard, the
 * next borrower receives it with {@code autoCommit=false}, and the operation after that fails
 * with "given a connection that is already in a transaction". That is the cross-request
 * contamination this pool cannot prevent on its own -- it neither rolls back nor resets
 * autoCommit on return, and {@code testOnBorrow} is throttled by a 30s
 * {@code validationInterval}.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*}
 * variables at a <strong>disposable</strong> database.
 */
public class CommittedButUnrestoredSqlTest extends Common {

	private static final String TAG = "committed-unrestored-probe";

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("CommittedButUnrestoredSqlTest");
		Common.initialize();
	}

	@Before
	public void createProbeTable() throws SQLException {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("CREATE TABLE IF NOT EXISTS starexec.probe_committed_unrestored ("
					+ "id serial PRIMARY KEY, tag text NOT NULL)");
			s.execute("DELETE FROM starexec.probe_committed_unrestored");
		}
	}

	@After
	public void dropProbeTable() throws SQLException {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("DROP TABLE IF EXISTS starexec.probe_committed_unrestored");
		}
	}

	/**
	 * The whole contract in one run: the row is committed once, the caller is told so rather
	 * than told the work was lost, and the connection that carried it does not come back.
	 *
	 * <p>The restoration failure is injected rather than provoked. Killing the backend was the
	 * realistic way to cause it, but the window between {@code commit()} and the
	 * {@code setAutoCommit} after it is too narrow to hit by sleeping -- the kill lands during
	 * the work instead, which is a different scenario. A proxy that fails only that one call
	 * makes the case deterministic; everything else, including the discard, still goes to the
	 * real pooled connection underneath.
	 */
	@Test
	public void aCommitFollowedByAFailedRestorationKeepsTheWriteAndDiscardsTheConnection()
			throws Exception {
		int doomedBackend;
		try (Connection real = Common.getConnection()) {
			doomedBackend = backendPid(real);
			Connection injected = failingRestoration(real);

			try {
				Common.runTransactional(injected, con -> {
					try (Statement s = con.createStatement()) {
						s.execute("INSERT INTO starexec.probe_committed_unrestored (tag)"
								+ " VALUES (\'" + TAG + "\')");
					}
					return null;
				});
				fail("a failed restoration after a successful commit must be reported");
			} catch (Common.CommittedButUnrestoredException expected) {
				// The one outcome that is neither "it worked" nor "it was rolled back".
				assertTrue("the caller must be able to tell the work committed",
						expected.getMessage().contains("committed"));
			}
		}

		assertEquals("the committed row must be present exactly once", 1, probeRows());

		// The poisoned physical connection must not be handed out again. Borrowing several
		// times makes an accidental reuse visible rather than relying on one draw.
		Set<Integer> backendsSeen = new HashSet<>();
		for (int i = 0; i < 5; i++) {
			try (Connection con = Common.getConnection()) {
				assertTrue("the next borrower must get an autoCommit connection",
						con.getAutoCommit());
				assertFalse("the next borrower must get a live connection", con.isClosed());
				int pid = backendPid(con);
				assertNotEquals("the unrestorable backend was recycled", doomedBackend, pid);
				backendsSeen.add(pid);
				assertEquals("the connection must be usable", 1, selectOne(con));
			}
		}
		assertFalse("a healthy backend must have served the retries", backendsSeen.isEmpty());
	}

	/**
	 * The mirror case. When the work itself fails there is nothing durable to preserve, and
	 * the connection is fine, so it must go back into rotation normally -- discarding on every
	 * rollback would empty the pool under load.
	 */
	@Test
	public void anOrdinaryRollbackDoesNotDiscardTheConnection() throws Exception {
		int before = probeRows();
		try {
			Common.inTransaction(con -> {
				try (Statement s = con.createStatement()) {
					s.execute("INSERT INTO starexec.probe_committed_unrestored (tag)"
							+ " VALUES ('" + TAG + "')");
				}
				throw new SQLException("induced application failure");
			});
			fail("the induced failure must surface");
		} catch (Common.CommittedButUnrestoredException wrong) {
			throw new AssertionError("a rolled-back transaction must not report as committed", wrong);
		} catch (SQLException expected) {
			assertEquals("induced application failure", expected.getMessage());
		}

		assertEquals("nothing may be durable after a rollback", before, probeRows());
		try (Connection con = Common.getConnection()) {
			assertTrue(con.getAutoCommit());
			assertEquals(1, selectOne(con));
		}
	}

	// ---------------------------------------------------------------- helpers

	/**
	 * The real connection, except that the {@code setAutoCommit(true)} issued after a
	 * successful commit fails. Everything else -- including {@code unwrap} and {@code abort},
	 * which is how the connection gets discarded -- goes straight through.
	 */
	private Connection failingRestoration(Connection real) {
		boolean[] committed = {false};
		return (Connection) Proxy.newProxyInstance(
				Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
				(proxy, method, args) -> {
					if ("commit".equals(method.getName())) {
						Object r = method.invoke(real, args);
						committed[0] = true;
						return r;
					}
					if ("setAutoCommit".equals(method.getName())
							&& Boolean.TRUE.equals(args[0]) && committed[0]) {
						throw new SQLException("induced restoration failure");
					}
					try {
						return method.invoke(real, args);
					} catch (java.lang.reflect.InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}

	private static int backendPid(Connection con) throws SQLException {
		try (Statement s = con.createStatement();
		     ResultSet rs = s.executeQuery("SELECT pg_backend_pid()")) {
			return rs.next() ? rs.getInt(1) : -1;
		}
	}

	private static int selectOne(Connection con) throws SQLException {
		try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery("SELECT 1")) {
			return rs.next() ? rs.getInt(1) : -1;
		}
	}

	private int probeRows() throws SQLException {
		try (Connection con = Common.getConnection();
		     Statement s = con.createStatement();
		     ResultSet rs = s.executeQuery("SELECT count(*) FROM starexec.probe_committed_unrestored"
				     + " WHERE tag = '" + TAG + "'")) {
			return rs.next() ? rs.getInt(1) : -1;
		}
	}
}
