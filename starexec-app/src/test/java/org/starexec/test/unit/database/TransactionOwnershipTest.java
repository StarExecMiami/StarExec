package org.starexec.test.unit.database;

import org.junit.Test;
import org.starexec.data.database.Common;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * What the transaction owner does to the connection it borrows.
 *
 * <p>These are the boundary assertions for job creation: after a success, after a failure
 * part-way through, and after a failure while cleaning up, the connection must go back to
 * the pool in the state it arrived in. A connection returned with {@code autoCommit=false}
 * or inside an aborted transaction poisons whichever request borrows it next, which is a
 * failure that appears nowhere near the request that caused it.
 *
 * <p>The connection is a recording proxy rather than a database: what is under test is the
 * ordering and the arithmetic of commit and rollback, and a real connection would let a
 * missing rollback pass unnoticed whenever the work happened not to write anything.
 */
public class TransactionOwnershipTest {

	@Test
	public void successCommitsOnceAndRestoresTheEntryState() throws Exception {
		RecordingConnection recorder = new RecordingConnection(true);

		String result = Common.runTransactional(recorder.connection(), con -> "done");

		assertEquals("done", result);
		assertEquals(Arrays.asList("setAutoCommit(false)", "commit", "setAutoCommit(true)"),
				recorder.calls);
	}

	/**
	 * A connection already inside a transaction belongs to another owner. Committing or
	 * rolling it back here would settle work this method did not do, and the alternative --
	 * savepoints -- is deliberately not implemented, so it is refused before anything runs.
	 */
	@Test
	public void aConnectionAlreadyInATransactionIsRefused() {
		RecordingConnection recorder = new RecordingConnection(false);

		SQLException refused = runExpectingFailure(recorder, con -> {
			fail("the work must not run on a connection this method does not own");
			return null;
		});

		assertTrue(refused.getMessage().contains("does not own"));
		assertEquals("nothing may touch a connection it refused", List.of(), recorder.calls);
	}

	@Test
	public void failureRollsBackOnceAndCommitsNothing() {
		RecordingConnection recorder = new RecordingConnection(true);
		IllegalStateException thrown = new IllegalStateException("stage insertion failed");

		SQLException surfaced = runExpectingFailure(recorder, con -> {
			throw thrown;
		});

		assertEquals(Arrays.asList("setAutoCommit(false)", "rollback", "setAutoCommit(true)"),
				recorder.calls);
		assertSame("the cause must survive so the log says what actually failed",
				thrown, surfaced.getCause());
	}

	/** Two failures in a row must still produce exactly one rollback, not one per boundary. */
	@Test
	public void aFailureAfterPartialWorkStillRollsBackExactlyOnce() {
		RecordingConnection recorder = new RecordingConnection(true);

		runExpectingFailure(recorder, con -> {
			throw new SQLException("pair stage insertion failed");
		});

		assertEquals("rollback must happen once", 1, count(recorder.calls, "rollback"));
		assertEquals("nothing may be committed", 0, count(recorder.calls, "commit"));
	}

	/**
	 * A commit that fails is a failure, not a success. {@code endTransaction} caught it,
	 * rolled back and returned normally, so the caller went on believing its writes were
	 * durable.
	 */
	@Test
	public void aFailedCommitIsReportedRatherThanSwallowed() {
		RecordingConnection recorder = new RecordingConnection(true);
		recorder.failOn = "commit";

		SQLException surfaced = runExpectingFailure(recorder, con -> "unreachable outcome");

		assertEquals(1, count(recorder.calls, "commit"));
		assertEquals("a failed commit must still roll back", 1, count(recorder.calls, "rollback"));
		assertTrue(surfaced.getMessage().contains("commit"));
	}

	/**
	 * Cleanup must not overwrite the reason. If the rollback also fails, the application
	 * failure is what the caller sees, and the rollback failure rides along beside it.
	 */
	@Test
	public void aFailedRollbackIsSuppressedRatherThanMasking() {
		RecordingConnection recorder = new RecordingConnection(true);
		recorder.failOn = "rollback";

		SQLException surfaced = runExpectingFailure(recorder, con -> {
			throw new SQLException("the original failure");
		});

		assertEquals("the original failure", surfaced.getMessage());
		assertEquals(1, surfaced.getSuppressed().length);
		assertTrue(surfaced.getSuppressed()[0].getMessage().contains("rollback"));
	}

	/** Restoring autoCommit must not be skipped just because the work failed. */
	@Test
	public void theEntryStateIsRestoredOnEveryPath() {
		RecordingConnection recorder = new RecordingConnection(true);
		runExpectingFailure(recorder, con -> {
			throw new SQLException("failed");
		});
		assertEquals("autoCommit must be restored last",
				"setAutoCommit(true)", recorder.calls.get(recorder.calls.size() - 1));
	}

	/**
	 * Work and commit succeed, restoration does not. Returning success here would hand the
	 * pool a connection in an unknown state while telling the caller everything is fine.
	 *
	 * <p>This pool makes that consequential: it is configured with neither
	 * {@code rollbackOnReturn} nor a {@code ConnectionState} interceptor, so {@code close()}
	 * resets nothing, and {@code testOnBorrow} is throttled by a 30s
	 * {@code validationInterval} -- a connection returned inside that window is handed to the
	 * next borrower unvalidated.
	 */
	@Test
	public void aRestorationFailureAfterCommitIsReportedAsCommitted() {
		RecordingConnection recorder = new RecordingConnection(true);
		recorder.failOn = "setAutoCommit(true)";

		SQLException surfaced = runExpectingFailure(recorder, con -> "written");

		assertTrue("the caller must be told the work committed, not that it was rolled back",
				surfaced instanceof Common.CommittedButUnrestoredException);
		assertEquals("nothing may be rolled back after a successful commit",
				0, count(recorder.calls, "rollback"));
		assertEquals(1, count(recorder.calls, "commit"));
	}

	/**
	 * A restoration failure on top of an application failure must not become the reported
	 * cause: the caller needs to know why its work failed.
	 */
	@Test
	public void aRestorationFailureAfterAFailureIsSuppressedNotPromoted() {
		RecordingConnection recorder = new RecordingConnection(true);
		recorder.failOn = "setAutoCommit(true)";

		SQLException surfaced = runExpectingFailure(recorder, con -> {
			throw new SQLException("the original failure");
		});

		assertEquals("the original failure", surfaced.getMessage());
		assertTrue("the restoration failure must ride along",
				surfaced.getSuppressed().length >= 1);
	}

	/** Both cleanup steps failing still leaves the application failure primary. */
	@Test
	public void rollbackAndRestorationFailingTogetherStillReportTheOriginal() {
		RecordingConnection recorder = new RecordingConnection(true);
		recorder.failOn = "rollback";
		recorder.alsoFailOn = "setAutoCommit(true)";

		SQLException surfaced = runExpectingFailure(recorder, con -> {
			throw new SQLException("the original failure");
		});

		assertEquals("the original failure", surfaced.getMessage());
		assertEquals("both cleanup failures must be attached", 2, surfaced.getSuppressed().length);
	}

	/** After every path that restores cleanly, the next borrower sees autoCommit=true. */
	@Test
	public void aCleanlyRestoredConnectionIsReusable() throws Exception {
		RecordingConnection ok = new RecordingConnection(true);
		Common.runTransactional(ok.connection(), con -> "fine");
		assertTrue("a committed transaction must leave autoCommit on", ok.autoCommit);

		RecordingConnection failed = new RecordingConnection(true);
		runExpectingFailure(failed, con -> {
			throw new SQLException("failed");
		});
		assertTrue("a rolled-back transaction must leave autoCommit on", failed.autoCommit);
	}

	/** The owner borrows the connection; whoever opened it closes it. */
	@Test
	public void theBorrowedConnectionIsNotClosed() throws Exception {
		RecordingConnection recorder = new RecordingConnection(true);

		Common.runTransactional(recorder.connection(), con -> null);

		assertFalse("runTransactional must not close a connection it does not own",
				recorder.calls.contains("close"));
	}

	// ------------------------------------------------------------------ helpers

	private SQLException runExpectingFailure(RecordingConnection recorder,
	                                         Common.TransactionalWork<String> work) {
		try {
			Common.runTransactional(recorder.connection(), work);
			fail("expected the transaction to fail");
			return null;
		} catch (SQLException expected) {
			return expected;
		}
	}

	private int count(List<String> calls, String call) {
		return (int) calls.stream().filter(call::equals).count();
	}

	/**
	 * Records the connection-lifecycle calls made against it, and can be told to fail one of
	 * them. Everything else answers a harmless default.
	 */
	private static final class RecordingConnection implements InvocationHandler {
		private final List<String> calls = new ArrayList<>();
		private boolean autoCommit;
		private String failOn;
		private String alsoFailOn;

		RecordingConnection(boolean autoCommit) {
			this.autoCommit = autoCommit;
		}

		Connection connection() {
			return (Connection) Proxy.newProxyInstance(
					Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, this);
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws SQLException {
			String name = method.getName();
			switch (name) {
				case "getAutoCommit":
					return autoCommit;
				case "setAutoCommit": {
					String call = "setAutoCommit(" + args[0] + ")";
					calls.add(call);
					if (call.equals(failOn) || call.equals(alsoFailOn)) {
						throw new SQLException(call + " failed");
					}
					autoCommit = (Boolean) args[0];
					return null;
				}
				case "commit":
				case "rollback":
				case "close":
					calls.add(name);
					if (name.equals(failOn) || name.equals(alsoFailOn)) {
						throw new SQLException(name + " failed");
					}
					return null;
				case "toString":
					return "RecordingConnection" + calls;
				case "hashCode":
					return System.identityHashCode(proxy);
				case "equals":
					return proxy == args[0];
				default:
					return null;
			}
		}
	}
}
