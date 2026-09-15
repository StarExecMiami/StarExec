package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.backend.Backend;
import org.starexec.constants.R;
import org.starexec.data.database.Common;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.Jobs;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.data.to.tuples.AttributesTableData;
import org.starexec.data.to.tuples.TimePair;
import org.apache.commons.lang3.tuple.Triple;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Caller-level tests for the automatic-rerun protocol, against the real SQL.
 *
 * <p>These go through the production Java entry points rather than calling the stored
 * functions directly, because the invariant they protect is a <em>wiring</em> invariant:
 * the {@code pairs_rerun} table records that the <em>automatic</em> rerun allowance was
 * consumed, not that a pair was rerun. A refactor that routed the manual REST path through
 * {@code RerunJobPairAutomatic} would silently start spending users' allowances, and a
 * test that exercised the SQL functions in isolation could not see it.
 *
 * <p>Skips unless a PostgreSQL instance is configured, following the convention in
 * {@link JobsSqlRegressionTest}. Point {@code STAREXEC_DB_URL} / {@code STAREXEC_DB_USER} /
 * {@code STAREXEC_DB_PASSWORD} at a <strong>disposable</strong> database: these tests
 * insert and delete rows.
 */
public class JobsRerunProtocolSqlTest extends Common {

	/** A pair whose end_time is this recent is inside the rerun window. */
	private static final String RECENT_END_TIME = "NOW() - INTERVAL '10 minutes'";

	private int jobId;
	private int pairId;
	private Backend originalBackend;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("JobsRerunProtocolSqlTest");
		Common.initialize();
	}

	/**
	 * The pool hands out connections it does not reset.
	 *
	 * <p>Tomcat JDBC applies {@code defaultAutoCommit} when a physical connection is
	 * created, not when one is borrowed, and this pool is configured without
	 * {@code rollbackOnReturn}. A connection returned with {@code autoCommit=false}
	 * therefore reaches the next borrower still in that mode, carrying whatever
	 * transaction was left open on it -- which is how this class came to hand
	 * JobsKillProtocolSqlTest a connection that made Jobs.kill block on its own
	 * helper connections.</p>
	 */
	private static void requireCleanPooledConnection(Connection con, String operation)
			throws SQLException {
		if (con.getAutoCommit()) {
			return;
		}
		try {
			con.rollback();
		} finally {
			con.setAutoCommit(true);
		}
		fail(operation + " borrowed a pooled connection with autoCommit=false: an earlier"
				+ " caller returned it mid-transaction. It has been rolled back and restored"
				+ " so cleanup can proceed, but the pool was contaminated.");
	}

	/** The state a fixture-owned connection must be in before it returns to the pool. */
	private static void assertConnectionReturnedClean(Connection con, String operation)
			throws SQLException {
		assertTrue(operation + " must explicitly finish its transaction and restore"
				+ " autoCommit before the connection goes back to the pool",
				con.getAutoCommit());
	}

	/** Rows created only by makeTheFixturePairVisibleToGetPairsSimple; 0 when unused. */
	private int visibilitySpaceId;
	private int visibilityBenchId;
	private int visibilitySolverId;
	private int visibilityConfigId;
	/** The node attempt 2's measurements are recorded against; 0 when unused. */
	private int resultsNodeId;

	@Before
	public void createFixture() throws SQLException {
		originalBackend = R.BACKEND;
		try (Connection con = Common.getConnection()) {
			requireCleanPooledConnection(con, "createFixture");
			con.setAutoCommit(false);
			boolean transactionFinished = false;
			try {
				int userId = selectInt(con, "SELECT min(id) FROM starexec.users");
				jobId = selectInt(con,
						"INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size) " +
								"VALUES (" + userId + ", 'rerun-protocol-test', 1, 0) RETURNING id");
				pairId = selectInt(con,
						"INSERT INTO starexec.job_pairs (job_id, sge_id, status_code, start_time, end_time, primary_jobpair_data) " +
								"VALUES (" + jobId + ", 987654, " + StatusCode.ERROR_RUNSCRIPT.getVal() +
								", NOW() - INTERVAL '20 minutes', " + RECENT_END_TIME + ", 1) RETURNING id");
				// GetJobPairById INNER JOINs jobpair_stage_data on
				// stage_number = job_pairs.primary_jobpair_data, so a pair with no stage row
				// is invisible to JobPairs.getPair. Every real pair has one.
				exec(con, "INSERT INTO starexec.jobpair_stage_data " +
						"(jobpair_id, stage_number, status_code, disk_size) VALUES (" +
						pairId + ", 1, " + StatusCode.ERROR_RUNSCRIPT.getVal() + ", 4096)");
				con.commit();
				transactionFinished = true;
			} catch (Throwable primary) {
				try {
					con.rollback();
					transactionFinished = true;
				} catch (SQLException rollbackFailure) {
					primary.addSuppressed(rollbackFailure);
				}
				throw primary;
			} finally {
				// Only after an explicit commit or rollback: JDBC commits an active
				// transaction when autoCommit flips to true, which would publish a
				// half-built fixture on the failure path.
				if (transactionFinished) {
					con.setAutoCommit(true);
				}
			}
			assertConnectionReturnedClean(con, "createFixture");
		}
	}

	@After
	public void dropFixture() throws SQLException {
		R.BACKEND = originalBackend;
		if (jobId == 0) {
			return;
		}
		try (Connection con = Common.getConnection()) {
			requireCleanPooledConnection(con, "dropFixture");
			con.setAutoCommit(false);
			boolean transactionFinished = false;
			try {
				// pairs_rerun, job_pair_attempts and jobpair_stage_data all cascade from job_pairs.
				exec(con, "DELETE FROM starexec.job_pairs WHERE job_id = " + jobId);
				// Only makeTheFixturePairVisibleToGetPairsSimple creates these, and until now
				// nothing removed them; each run leaked one space, benchmark, solver and
				// configuration. Order is child-first and each delete is a no-op when unused.
				if (resultsNodeId != 0) {
					try (PreparedStatement ps = con.prepareStatement(
							"DELETE FROM starexec.nodes WHERE id = ?")) {
						ps.setInt(1, resultsNodeId);
						ps.executeUpdate();
					}
				}
				if (visibilityConfigId != 0) {
					exec(con, "DELETE FROM starexec.configurations WHERE id = " + visibilityConfigId);
				}
				if (visibilitySolverId != 0) {
					exec(con, "DELETE FROM starexec.solvers WHERE id = " + visibilitySolverId);
				}
				if (visibilityBenchId != 0) {
					exec(con, "DELETE FROM starexec.benchmarks WHERE id = " + visibilityBenchId);
				}
				if (visibilitySpaceId != 0) {
					exec(con, "DELETE FROM starexec.job_spaces WHERE id = " + visibilitySpaceId);
				}
				exec(con, "DELETE FROM starexec.jobs WHERE id = " + jobId);
				con.commit();
				transactionFinished = true;
			} catch (Throwable primary) {
				try {
					con.rollback();
					transactionFinished = true;
				} catch (SQLException rollbackFailure) {
					primary.addSuppressed(rollbackFailure);
				}
				throw primary;
			} finally {
				if (transactionFinished) {
					con.setAutoCommit(true);
				}
			}
			assertConnectionReturnedClean(con, "dropFixture");
		}
	}

	// ------------------------------------------------------------------
	// the automatic path
	// ------------------------------------------------------------------

	@Test
	public void automaticRerunResetsAndConsumesTheAllowanceInOneTransaction() throws SQLException {
		R.BACKEND = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);

		assertEquals("a confirmed-safe execution should complete the rerun",
				Jobs.RerunOutcome.COMPLETED, Jobs.rerunPairAutomatic(pairId));

		assertEquals("the pair should be back at PENDING_SUBMIT",
				StatusCode.STATUS_PENDING_SUBMIT.getVal(), statusOf(pairId));
		assertNull("a pair awaiting a new attempt has no end_time", endTimeOf(pairId));
		assertEquals("the automatic allowance should be recorded by the same transaction",
				1, tombstoneCount(pairId));
	}

	@Test
	public void aSecondAutomaticRerunIsRefusedRatherThanRepeated() throws SQLException {
		R.BACKEND = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);
		assertEquals(Jobs.RerunOutcome.COMPLETED, Jobs.rerunPairAutomatic(pairId));

		// Put the pair back at ERROR_RUNSCRIPT so ONLY the recorded allowance can stop a
		// second rerun. This is the lost-response case: the caller committed and never
		// learned it, then asked again.
		exec("UPDATE starexec.job_pairs SET status_code = " + StatusCode.ERROR_RUNSCRIPT.getVal() +
				", end_time = " + RECENT_END_TIME + " WHERE id = " + pairId);

		assertEquals("the allowance was already spent, so this must be refused",
				Jobs.RerunOutcome.ALREADY_CONSUMED, Jobs.rerunPairAutomatic(pairId));
		assertEquals("no second reset may happen",
				StatusCode.ERROR_RUNSCRIPT.getVal(), statusOf(pairId));
		assertEquals("no duplicate acknowledgment", 1, tombstoneCount(pairId));
	}

	// ------------------------------------------------------------------
	// unproven executions
	// ------------------------------------------------------------------

	@Test
	public void unprovenExecutionWritesNothingAndLeavesThePairSelectable() throws SQLException {
		R.BACKEND = backendReturning(Backend.KillOutcome.UNPROVEN);

		assertEquals("an unproven execution must not authorize a replacement",
				Jobs.RerunOutcome.DEFERRED_UNPROVEN_EXECUTION, Jobs.rerunPairAutomatic(pairId));

		assertEquals("the pair keeps its failed status",
				StatusCode.ERROR_RUNSCRIPT.getVal(), statusOf(pairId));
		assertNotNull("and keeps the end_time that makes it selectable", endTimeOf(pairId));
		assertEquals("no allowance may be spent on an attempt that never happened",
				0, tombstoneCount(pairId));

		// The retry owner is this durable state and nothing else — no JVM record is needed,
		// so the pair survives a restart as a candidate.
		assertTrue("the next sweep must select it again", selectedBySweep(pairId));
	}

	@Test
	public void anUnidentifiableExecutionIsNeverReportedSafe() throws SQLException {
		// sge_id is nullable with no default, and JobPair.getBackendExecId() returns 0 for
		// NULL — indistinguishable from a real "execution 0". An execution that cannot be
		// named cannot be proven stopped.
		exec("UPDATE starexec.job_pairs SET sge_id = NULL WHERE id = " + pairId);
		Backend backend = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);
		R.BACKEND = backend;

		assertEquals("an unidentifiable execution must defer",
				Jobs.RerunOutcome.DEFERRED_UNPROVEN_EXECUTION, Jobs.rerunPairAutomatic(pairId));
		org.mockito.Mockito.verify(backend, org.mockito.Mockito.never())
				.killPairConfirmed(org.mockito.ArgumentMatchers.anyInt());
		assertEquals(StatusCode.ERROR_RUNSCRIPT.getVal(), statusOf(pairId));
		assertEquals(0, tombstoneCount(pairId));
	}

	// ------------------------------------------------------------------
	// the manual path must stay a different operation
	// ------------------------------------------------------------------

	@Test
	public void manualRerunResetsWithoutConsumingTheAutomaticAllowance() throws SQLException {
		R.BACKEND = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);

		assertTrue("the manual entry point should report success", Jobs.rerunPair(pairId));

		assertEquals("a manual rerun still resets the pair",
				StatusCode.STATUS_PENDING_SUBMIT.getVal(), statusOf(pairId));
		assertEquals("but it must NOT spend the pair's automatic rerun allowance",
				0, tombstoneCount(pairId));
	}

	@Test
	public void aPairManuallyRerunStillReceivesItsAutomaticRerunWhenItFailsAgain() throws SQLException {
		R.BACKEND = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);
		assertTrue(Jobs.rerunPair(pairId));

		// the manually rerun execution fails the same way
		exec("UPDATE starexec.job_pairs SET status_code = " + StatusCode.ERROR_RUNSCRIPT.getVal() +
				", sge_id = 987655, end_time = " + RECENT_END_TIME + " WHERE id = " + pairId);

		assertTrue("a manual rerun must not have made the pair invisible to the sweep",
				selectedBySweep(pairId));
		assertEquals("and the automatic rerun is still available to it",
				Jobs.RerunOutcome.COMPLETED, Jobs.rerunPairAutomatic(pairId));
		assertEquals(1, tombstoneCount(pairId));
	}

	// ------------------------------------------------------------------
	// B1: the manual path must clear the same execution-safety gate
	// ------------------------------------------------------------------

	@Test
	public void manualRerunIsWithheldWhenTheOldExecutionCannotBeConfirmedStopped()
			throws SQLException {
		// The defect this pins: ERROR_RUNSCRIPT is 11 and STATUS_COMPLETE is 7, so the old
		// `code != PENDING_SUBMIT && code < STATUS_COMPLETE` guard attempted no kill at all
		// for exactly the status a failed Kubernetes pod publishes while its pod may still be
		// terminating -- and the pair was reset anyway.
		R.BACKEND = backendReturning(Backend.KillOutcome.UNPROVEN);

		assertFalse("an unproven execution must not authorize a manual replacement",
				Jobs.rerunPair(pairId));

		assertEquals("the pair must keep its failed status",
				StatusCode.ERROR_RUNSCRIPT.getVal(), statusOf(pairId));
		assertNotNull("and keep the end_time that keeps it visible",
				endTimeOf(pairId));
		assertEquals("a withheld manual rerun spends no automatic allowance",
				0, tombstoneCount(pairId));
	}

	@Test
	public void manualRerunActuallyConsultsTheBackendForAFailedPair() throws SQLException {
		Backend backend = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);
		R.BACKEND = backend;

		assertTrue(Jobs.rerunPair(pairId));

		// The old path never asked, because 11 < 7 is false. Asserting the call happened is
		// what makes the status-band regression impossible to reintroduce silently.
		org.mockito.Mockito.verify(backend).killPairConfirmed(987654);
	}

	@Test
	public void manualRerunOfAPairWithNoExecutionIdIsWithheld() throws SQLException {
		exec("UPDATE starexec.job_pairs SET sge_id = NULL WHERE id = " + pairId);
		Backend backend = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);
		R.BACKEND = backend;

		assertFalse("execution 0 must never be invented for a NULL sge_id",
				Jobs.rerunPair(pairId));
		org.mockito.Mockito.verify(backend, org.mockito.Mockito.never())
				.killPairConfirmed(org.mockito.ArgumentMatchers.anyInt());
		assertEquals(StatusCode.ERROR_RUNSCRIPT.getVal(), statusOf(pairId));
	}

	@Test
	public void manualRerunBatchResetsOnlyThePairsProvenStopped() throws SQLException {
		final int safeExecId = 111111;
		int safePairId = insertPair(safeExecId, StatusCode.ERROR_RUNSCRIPT.getVal());

		Backend backend = org.mockito.Mockito.mock(Backend.class);
		org.mockito.Mockito.when(backend.killPairConfirmed(safeExecId))
				.thenReturn(Backend.KillOutcome.CONFIRMED_SAFE);
		org.mockito.Mockito.when(backend.killPairConfirmed(987654))
				.thenReturn(Backend.KillOutcome.UNPROVEN);
		R.BACKEND = backend;

		List<JobPair> batch = new ArrayList<>();
		batch.add(JobPairs.getPair(safePairId));
		batch.add(JobPairs.getPair(pairId));

		assertEquals("one unproven pair must neither block the proven one nor be reset itself",
				Jobs.ManualRerunOutcome.PARTIALLY_WITHHELD,
				Jobs.rerunPairsBatch(batch, jobId));

		assertEquals("the confirmed-safe pair resets",
				StatusCode.STATUS_PENDING_SUBMIT.getVal(), statusOf(safePairId));
		assertEquals("the unproven pair does not",
				StatusCode.ERROR_RUNSCRIPT.getVal(), statusOf(pairId));
		assertEquals("a manual rerun spends no allowance on either pair",
				0, tombstoneCount(safePairId) + tombstoneCount(pairId));
	}

	/** Adds a second pair to the fixture job so batch behaviour can be observed. */
	private int insertPair(int execId, int statusCode) throws SQLException {
		try (Connection con = Common.getConnection()) {
			requireCleanPooledConnection(con, "insertPair");
			con.setAutoCommit(false);
			boolean transactionFinished = false;
			final int id;
			try {
				id = selectInt(con,
						"INSERT INTO starexec.job_pairs (job_id, sge_id, status_code, start_time," +
								" end_time, primary_jobpair_data) VALUES (" + jobId + ", " + execId +
								", " + statusCode + ", NOW() - INTERVAL \'20 minutes\', " +
								RECENT_END_TIME + ", 1) RETURNING id");
				// getPair INNER JOINs jobpair_stage_data on stage_number =
				// primary_jobpair_data, so a pair with no stage row is invisible to it.
				exec(con, "INSERT INTO starexec.jobpair_stage_data" +
						" (jobpair_id, stage_number, status_code, disk_size) VALUES (" +
						id + ", 1, " + statusCode + ", 4096)");
				con.commit();
				transactionFinished = true;
			} catch (Throwable primary) {
				try {
					con.rollback();
					transactionFinished = true;
				} catch (SQLException rollbackFailure) {
					primary.addSuppressed(rollbackFailure);
				}
				throw primary;
			} finally {
				// Only after an explicit commit or rollback: JDBC commits an active
				// transaction when autoCommit flips to true, which would publish a
				// half-built fixture on the failure path.
				if (transactionFinished) {
					con.setAutoCommit(true);
				}
			}
			assertConnectionReturnedClean(con, "insertPair");
			return id;
		}
	}

	// ------------------------------------------------------------------
	// the gate must read a hydrated execution id, not an unread column
	// ------------------------------------------------------------------

	@Test
	public void setAllPairsToPendingHydratesExecutionIdsBeforeTheSafetyGate()
			throws SQLException {
		// getPairsSimple is backed by GetJobPairsByJobSimple, which INNER JOINs job_spaces
		// and whose RETURNS TABLE has no sge_id column. Both facts matter here: without the
		// space row the pair is invisible to that query and this test would prove nothing,
		// and without hydration every pair reaches the safety gate carrying JobPair's -1
		// default -- "the query never asked", not "there is no execution" -- so the whole
		// endpoint became a deterministic no-op that reported ERROR_DATABASE.
		makeTheFixturePairVisibleToGetPairsSimple();

		assertFalse(
				"precondition: the pair must be visible to getPairsSimple, otherwise this test"
						+ " would pass without exercising the gate at all",
				Jobs.getPairsSimple(jobId).isEmpty());
		assertEquals(
				"precondition: that projection genuinely cannot see sge_id",
				-1, Jobs.getPairsSimple(jobId).get(0).getBackendExecId());

		R.BACKEND = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);

		assertTrue("a job whose executions are confirmed stopped must actually reset",
				Jobs.setAllPairsToPending(jobId));
		assertEquals(StatusCode.STATUS_PENDING_SUBMIT.getVal(), statusOf(pairId));
		assertEquals("and this manual path still spends no automatic allowance",
				0, tombstoneCount(pairId));
	}

	@Test
	public void setAllPairsToPendingStillWithholdsAnUnprovenExecution() throws SQLException {
		makeTheFixturePairVisibleToGetPairsSimple();
		// Same preconditions as its sibling: without them a null getPairsSimple would make
		// setAllPairsToPending return false for the wrong reason and every assertion below
		// would still hold.
		assertFalse("precondition: the pair must be visible to getPairsSimple",
				Jobs.getPairsSimple(jobId).isEmpty());
		assertEquals("precondition: that projection cannot see sge_id",
				-1, Jobs.getPairsSimple(jobId).get(0).getBackendExecId());
		R.BACKEND = backendReturning(Backend.KillOutcome.UNPROVEN);

		assertFalse("hydrating the id must not weaken the gate",
				Jobs.setAllPairsToPending(jobId));
		assertEquals(StatusCode.ERROR_RUNSCRIPT.getVal(), statusOf(pairId));
		assertEquals(0, tombstoneCount(pairId));
	}

	/**
	 * Makes the fixture pair visible to {@code GetJobPairsByJobSimple}, which INNER JOINs
	 * job_spaces and whose consumer reads bench/solver/config ids as primitives -- a NULL in
	 * any of them makes getPairsSimple throw and return null, which would hide the very
	 * behaviour these two tests exist to check.
	 */
	private void makeTheFixturePairVisibleToGetPairsSimple() throws SQLException {
		try (Connection con = Common.getConnection()) {
			requireCleanPooledConnection(con, "makeTheFixturePairVisibleToGetPairsSimple");
			con.setAutoCommit(false);
			boolean transactionFinished = false;
			try {
				int userId = selectInt(con, "SELECT min(id) FROM starexec.users");
				int spaceId = selectInt(con,
						"INSERT INTO starexec.job_spaces (job_id, name) VALUES (" + jobId +
								", \'root\') RETURNING id");
				int benchId = selectInt(con,
						"INSERT INTO starexec.benchmarks (user_id, name, path, disk_size)" +
								" VALUES (" + userId + ", \'b\', \'/b\', 0) RETURNING id");
				int solverId = selectInt(con,
						"INSERT INTO starexec.solvers (user_id, name, path, disk_size)" +
								" VALUES (" + userId + ", \'s\', \'/s\', 0) RETURNING id");
				int configId = selectInt(con,
						"INSERT INTO starexec.configurations (solver_id, name, updated)" +
								" VALUES (" + solverId + ", \'c\', NOW()) RETURNING id");
				visibilitySpaceId = spaceId;
				visibilityBenchId = benchId;
				visibilitySolverId = solverId;
				visibilityConfigId = configId;
				exec(con, "UPDATE starexec.job_pairs SET job_space_id = " + spaceId +
						", bench_id = " + benchId + ", bench_name = \'b\', path = \'/\'" +
						" WHERE id = " + pairId);
				exec(con, "UPDATE starexec.jobpair_stage_data SET solver_id = " + solverId +
						", solver_name = \'s\', config_id = " + configId +
						", config_name = \'c\' WHERE jobpair_id = " + pairId);
				con.commit();
				transactionFinished = true;
			} catch (Throwable primary) {
				try {
					con.rollback();
					transactionFinished = true;
				} catch (SQLException rollbackFailure) {
					primary.addSuppressed(rollbackFailure);
				}
				throw primary;
			} finally {
				// Only after an explicit commit or rollback: JDBC commits an active
				// transaction when autoCommit flips to true, which would publish a
				// half-built fixture on the failure path.
				if (transactionFinished) {
					con.setAutoCommit(true);
				}
			}
			assertConnectionReturnedClean(con, "makeTheFixturePairVisibleToGetPairsSimple");
		}
	}

	// ------------------------------------------------------------------
	// a pair that never reached the backend vs one whose id was lost
	// ------------------------------------------------------------------

	@Test
	public void aSubmissionRejectedBeforeAnyExecutionCanStillBeRerun() throws SQLException {
		// ERROR_SGE_REJECT is written only where the backend returned an error code, and on
		// Kubernetes an error return can only come from a path that created no Job -- an
		// ambiguous create either defers or returns the positive execId. So there is nothing
		// to confirm stopped, and withholding would strand the pair permanently.
		exec("UPDATE starexec.job_pairs SET sge_id = NULL, status_code = " +
				StatusCode.ERROR_SGE_REJECT.getVal() + " WHERE id = " + pairId);
		Backend backend = backendReturning(Backend.KillOutcome.UNPROVEN);
		R.BACKEND = backend;

		assertTrue("a pair that never reached the backend must remain rerunnable",
				Jobs.rerunPair(pairId));
		assertEquals(StatusCode.STATUS_PENDING_SUBMIT.getVal(), statusOf(pairId));
		org.mockito.Mockito.verify(backend, org.mockito.Mockito.never())
				.killPairConfirmed(org.mockito.ArgumentMatchers.anyInt());
	}

	@Test
	public void aSubmitFailureWhoseExecutionIdWasLostIsStillWithheld() throws SQLException {
		// ERROR_SUBMIT_FAIL is the opposite case and must NOT be whitelisted with it: it is
		// written after a submission that SUCCEEDED and whose id could not be persisted, and
		// its cleanup uses the legacy killPair, which establishes nothing. A missing sge_id
		// here can mean a live execution whose identity is unrecoverable.
		exec("UPDATE starexec.job_pairs SET sge_id = NULL, status_code = " +
				StatusCode.ERROR_SUBMIT_FAIL.getVal() + " WHERE id = " + pairId);
		R.BACKEND = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);

		assertFalse("a lost execution id is not evidence that nothing ran",
				Jobs.rerunPair(pairId));
		assertEquals(StatusCode.ERROR_SUBMIT_FAIL.getVal(), statusOf(pairId));
	}

	// ------------------------------------------------------------------
	// the reset must revalidate identity, not just status
	// ------------------------------------------------------------------

	@Test
	public void aPairRedispatchedDuringTheGateIsNotResetOntoItsNewExecution()
			throws SQLException {
		// The concurrent actor lands while the gate is talking to the backend: it resets the
		// pair and re-dispatches it, so the pair now carries a DIFFERENT, live execution.
		// A status-only revalidation passes this through, because ENQUEUED/RUNNING is not
		// PENDING_SUBMIT -- and the pair is reset on top of the new execution.
		Backend backend = org.mockito.Mockito.mock(Backend.class);
		org.mockito.Mockito
				.when(backend.killPairConfirmed(org.mockito.ArgumentMatchers.anyInt()))
				.thenAnswer(invocation -> {
					exec("UPDATE starexec.job_pairs SET sge_id = 424242, status_code = " +
							StatusCode.STATUS_RUNNING.getVal() + " WHERE id = " + pairId);
					return Backend.KillOutcome.CONFIRMED_SAFE;
				});
		R.BACKEND = backend;

		assertFalse("the gate proved the OLD execution stopped; that proof does not transfer",
				Jobs.rerunPair(pairId));
		assertEquals("the pair must be left with its new execution untouched",
				StatusCode.STATUS_RUNNING.getVal(), statusOf(pairId));
		assertEquals("and no allowance may be spent", 0, tombstoneCount(pairId));
	}

	// ------------------------------------------------------------------
	// C-1: the reset count the database returns is the result, not a diagnostic
	// ------------------------------------------------------------------

	/**
	 * The control. Without it, every assertion below could be satisfied by a
	 * {@code setPairsToPending} that had simply stopped working, and none of them would
	 * be evidence that the returned count is what makes the difference.
	 */
	@Test
	public void setPairsToPendingCompletesWhenEveryIdentityStillMatches() throws SQLException {
		R.BACKEND = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);

		assertTrue("nothing withheld and nothing dropped under the lock is a complete reset",
				Jobs.setPairsToPending(jobId, StatusCode.ERROR_RUNSCRIPT.getVal()));
		assertEquals(StatusCode.STATUS_PENDING_SUBMIT.getVal(), statusOf(pairId));
		assertEquals("and this manual path still spends no automatic allowance",
				0, tombstoneCount(pairId));
	}

	@Test
	public void setPairsToPendingReportsIncompleteWhenTheDatabaseRefusesTheReset()
			throws SQLException {
		assertEquals("precondition: the row starts on the execution the gate will be asked about",
				987654, execIdOf(pairId));
		assertEquals("precondition: and at the status the caller selects on",
				StatusCode.ERROR_RUNSCRIPT.getVal(), statusOf(pairId));

		// The concurrent actor lands while the gate is talking to the backend: the pair is
		// reset and re-dispatched, so a DIFFERENT execution owns it by the time the checked
		// function takes the row lock.
		Backend backend = org.mockito.Mockito.mock(Backend.class);
		org.mockito.Mockito
				.when(backend.killPairConfirmed(987654))
				.thenAnswer(invocation -> {
					exec("UPDATE starexec.job_pairs SET sge_id = 424242, status_code = " +
							StatusCode.STATUS_RUNNING.getVal() + " WHERE id = " + pairId);
					return Backend.KillOutcome.CONFIRMED_SAFE;
				});
		R.BACKEND = backend;

		assertFalse("the database reset nothing, so this operation did not complete",
				Jobs.setPairsToPending(jobId, StatusCode.ERROR_RUNSCRIPT.getVal()));

		// Non-vacuity: the gate WAS consulted and it answered CONFIRMED_SAFE, so nothing was
		// withheld on the Java side. The only thing left that can make this false is the
		// count the checked function returned.
		org.mockito.Mockito.verify(backend).killPairConfirmed(987654);
		assertEquals("the pair keeps its new execution", 424242, execIdOf(pairId));
		assertEquals("and is left exactly as the concurrent actor put it",
				StatusCode.STATUS_RUNNING.getVal(), statusOf(pairId));
		assertEquals(0, tombstoneCount(pairId));
	}

	@Test
	public void setPairsToPendingResetsOnlyTheUnchangedPairAndStillReportsIncomplete()
			throws SQLException {
		final int safeExecId = 222222;
		int safePairId = insertPair(safeExecId, StatusCode.ERROR_RUNSCRIPT.getVal());

		Backend backend = org.mockito.Mockito.mock(Backend.class);
		// Pair B: proven stopped, then re-dispatched underneath the proof.
		org.mockito.Mockito
				.when(backend.killPairConfirmed(987654))
				.thenAnswer(invocation -> {
					exec("UPDATE starexec.job_pairs SET sge_id = 424242, status_code = " +
							StatusCode.STATUS_RUNNING.getVal() + " WHERE id = " + pairId);
					return Backend.KillOutcome.CONFIRMED_SAFE;
				});
		// Pair A: proven stopped and still carrying the same execution.
		org.mockito.Mockito
				.when(backend.killPairConfirmed(safeExecId))
				.thenReturn(Backend.KillOutcome.CONFIRMED_SAFE);
		R.BACKEND = backend;

		assertFalse("one pair of two was reset, so the operation is incomplete",
				Jobs.setPairsToPending(jobId, StatusCode.ERROR_RUNSCRIPT.getVal()));

		// A resetting is what proves the checked function ran and that the Java gate
		// withheld nothing: a withheld batch never reaches the SQL at all.
		assertEquals("the pair whose identity is unchanged resets",
				StatusCode.STATUS_PENDING_SUBMIT.getVal(), statusOf(safePairId));
		assertEquals("the re-dispatched pair does not",
				StatusCode.STATUS_RUNNING.getVal(), statusOf(pairId));
		assertEquals("and keeps the execution that owns it now", 424242, execIdOf(pairId));
		assertEquals("a manual rerun spends no allowance on either pair",
				0, tombstoneCount(safePairId) + tombstoneCount(pairId));
	}

	// ------------------------------------------------------------------
	// C-2: the single-pair fallback must read the same count
	// ------------------------------------------------------------------

	@Test
	public void theSinglePairFallbackReportsFalseWhenTheDatabaseResetsNothing()
			throws Exception {
		R.BACKEND = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);
		JobPair pair = JobPairs.getPair(pairId);
		assertEquals("precondition: the row really carries this execution",
				987654, pair.getBackendExecId());

		// The identity the caller proved safe is not the one on the row, so the checked
		// function returns 0. An exception is not the only way this can fail to happen.
		pair.setBackendExecId(999999);

		assertFalse("a reset the database refused must not be reported as done",
				invokeSinglePairFallback(pair));
		assertEquals("and the pair is untouched",
				StatusCode.ERROR_RUNSCRIPT.getVal(), statusOf(pairId));
		assertEquals(987654, execIdOf(pairId));
		assertEquals(0, tombstoneCount(pairId));
	}

	@Test
	public void theSinglePairFallbackReportsTrueWhenTheDatabaseResetsTheRow()
			throws Exception {
		R.BACKEND = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);
		JobPair pair = JobPairs.getPair(pairId);

		assertTrue("a reset the database committed must be reported as done",
				invokeSinglePairFallback(pair));
		assertEquals(StatusCode.STATUS_PENDING_SUBMIT.getVal(), statusOf(pairId));
		assertEquals("and the fallback is still the manual path",
				0, tombstoneCount(pairId));
	}

	/**
	 * {@code rerunSinglePairViaBatchFunction} is private and reached only from
	 * {@code rerunPairsBatch}'s SQLException fallback, which cannot be provoked from a
	 * caller-level test without breaking the connection out from under the batch. The
	 * defect is in what it does with the stored function's return value, so it is called
	 * directly against the real database.
	 */
	private boolean invokeSinglePairFallback(JobPair pair) throws Exception {
		java.lang.reflect.Method m = Jobs.class.getDeclaredMethod(
				"rerunSinglePairViaBatchFunction", JobPair.class, int.class);
		m.setAccessible(true);
		return (Boolean) m.invoke(null, pair, jobId);
	}

	// ------------------------------------------------------------------
	// #183: a rerun must not keep the previous attempt's stage results
	// ------------------------------------------------------------------

	/** Attempt 1's measurements: cpu, wallclock, max_vmem, max_res_set, user, system. */
	private static final List<Double> STAGE_1_MEASUREMENTS =
			Arrays.asList(90.0, 100.0, 1000.0, 11.0, 80.0, 10.0);
	private static final List<Double> STAGE_2_MEASUREMENTS =
			Arrays.asList(40.0, 50.0, 500.0, 7.0, 30.0, 5.0);

	@Test
	public void aManualRerunClearsEveryStagesResults() throws SQLException {
		recordAFirstAttemptOnTwoStages(pairId);
		int attempt = attemptOf(pairId);
		R.BACKEND = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);

		assertTrue(Jobs.rerunPair(pairId));

		assertResultsCleared(pairId, attempt);
	}

	@Test
	public void anAutomaticRerunClearsEveryStagesResults() throws SQLException {
		recordAFirstAttemptOnTwoStages(pairId);
		int attempt = attemptOf(pairId);
		R.BACKEND = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);

		assertEquals(Jobs.RerunOutcome.COMPLETED, Jobs.rerunPairAutomatic(pairId));

		assertResultsCleared(pairId, attempt);
	}

	@Test
	public void aRerunByStatusClearsEveryStagesResults() throws SQLException {
		recordAFirstAttemptOnTwoStages(pairId);
		int attempt = attemptOf(pairId);
		R.BACKEND = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);

		assertTrue(Jobs.setPairsToPending(jobId, StatusCode.ERROR_RUNSCRIPT.getVal()));

		assertResultsCleared(pairId, attempt);
	}

	/**
	 * The user-visible defect. The second attempt stops in stage 1, so it records nothing for
	 * stage 2 -- and stage 2 must then show nothing, not the first attempt's result and
	 * measurements. The job's attribute summaries count attempt 2 alone.
	 */
	@Test
	public void aSecondAttemptThatStopsInStageOneShowsNothingOfTheFirstAttemptsStageTwo()
			throws SQLException {
		recordAFirstAttemptOnTwoStages(pairId);
		makeTheFixturePairVisibleToGetPairsSimple();
		R.BACKEND = backendReturning(Backend.KillOutcome.CONFIRMED_SAFE);
		assertTrue(Jobs.rerunPair(pairId));

		// Attempt 2, through the writers the monitors use: stage 1's result and measurements,
		// then a terminal status at stage 1 that leaves stage 2 not reached.
		Properties stageOne = new Properties();
		stageOne.setProperty("starexec-result", "Satisfiable");
		assertTrue(JobPairs.addJobPairAttributes(pairId, 1, stageOne));
		assertTrue(JobPairs.updateRunSolverStats(
				pairId, resultsNodeName(), 300.0, 200.0, 150.0, 50.0, 4000.0, 30L, 1, 1024L));
		assertEquals(org.starexec.data.database.PairStatusResult.APPLIED,
				JobPairs.setPairStatusPreciseResult(pairId, 1, StatusCode.EXCEED_RUNTIME.getVal(),
						StatusCode.STATUS_NOT_REACHED.getVal(), false));

		assertEquals("stage 1 holds attempt 2's result alone",
				"Satisfiable", attributeOf(pairId, 1, "starexec-result"));
		assertEquals(1, attributeCount(pairId, 1));
		assertEquals(Arrays.asList(200.0, 300.0, 4000.0, 30.0, 150.0, 50.0),
				measurementsOf(pairId, 1));
		assertEquals("stage 2 was not reached, so it has no attributes",
				0, attributeCount(pairId, 2));
		assertEquals("and no measurements",
				Arrays.asList(null, null, null, null, null, null), measurementsOf(pairId, 2));

		List<AttributesTableData> table = Jobs.getJobAttributesTable(visibilitySpaceId);
		assertNotNull(table);
		assertEquals("the attributes table has attempt 2's one result: " + describe(table),
				1, table.size());
		assertEquals("Satisfiable", table.get(0).attrValue);
		assertEquals(Integer.valueOf(1), table.get(0).attrCount);
		assertEquals(300.0, table.get(0).wallclockSum, 0.0);
		assertEquals(200.0, table.get(0).cpuSum, 0.0);

		List<Triple<String, Integer, TimePair>> totals =
				Jobs.getJobAttributeTotals(visibilitySpaceId);
		// Row set only: GetSumOfJobAttributes fans each attribute out over every stage row, so
		// its count and sums are wrong for any multi-stage pair (#186).
		assertEquals("the attribute totals hold attempt 2's result alone: " + describeTotals(totals),
				1, totals.size());
		assertEquals("Satisfiable", totals.get(0).getLeft());
	}

	/**
	 * The control. A pair the checked reset drops under the lock -- re-dispatched while the
	 * gate ran -- keeps its results, while the pair reset in the same call is reset. Without
	 * it, a reset that cleared every pair of the batch would pass the tests above.
	 */
	@Test
	public void aPairTheResetDropsKeepsItsResults() throws SQLException {
		final int safeExecId = 333333;
		int safePairId = insertPair(safeExecId, StatusCode.ERROR_RUNSCRIPT.getVal());
		recordAFirstAttemptOnTwoStages(pairId);
		recordAFirstAttemptOnTwoStages(safePairId);

		Backend backend = org.mockito.Mockito.mock(Backend.class);
		org.mockito.Mockito
				.when(backend.killPairConfirmed(987654))
				.thenAnswer(invocation -> {
					redispatch(pairId, 424242);
					return Backend.KillOutcome.CONFIRMED_SAFE;
				});
		org.mockito.Mockito
				.when(backend.killPairConfirmed(safeExecId))
				.thenReturn(Backend.KillOutcome.CONFIRMED_SAFE);
		R.BACKEND = backend;

		assertFalse(Jobs.setPairsToPending(jobId, StatusCode.ERROR_RUNSCRIPT.getVal()));

		assertEquals("precondition: the same call did reset the other pair",
				StatusCode.STATUS_PENDING_SUBMIT.getVal(), statusOf(safePairId));
		assertEquals("the dropped pair keeps its execution",
				StatusCode.STATUS_RUNNING.getVal(), statusOf(pairId));
		assertEquals("and its attributes", 2, attributeCount(pairId, 1));
		assertEquals(2, attributeCount(pairId, 2));
		assertEquals("and its measurements", STAGE_1_MEASUREMENTS, measurementsOf(pairId, 1));
		assertEquals(STAGE_2_MEASUREMENTS, measurementsOf(pairId, 2));
	}

	/**
	 * What a finished first attempt left behind on a two-stage pair: stage 2's row, both
	 * stages' measurements and two attributes per stage.
	 */
	private void recordAFirstAttemptOnTwoStages(int id) throws SQLException {
		try (Connection con = Common.getConnection()) {
			try (PreparedStatement ps = con.prepareStatement(
					"INSERT INTO starexec.jobpair_stage_data" +
							" (jobpair_id, stage_number, status_code, disk_size) VALUES (?, 2, ?, 2048)")) {
				ps.setInt(1, id);
				ps.setInt(2, StatusCode.STATUS_NOT_REACHED.getVal());
				ps.executeUpdate();
			}
			setMeasurements(con, id, 1, STAGE_1_MEASUREMENTS);
			setMeasurements(con, id, 2, STAGE_2_MEASUREMENTS);
			addAttribute(con, id, 1, "starexec-result", "Theorem");
			addAttribute(con, id, 1, "SZSStatus", "THM");
			addAttribute(con, id, 2, "starexec-result", "Unknown");
			addAttribute(con, id, 2, "SZSOutput", "None");
		}
		assertEquals("precondition: attempt 1's attributes are there", 4, attributeCount(id, 1)
				+ attributeCount(id, 2));
		assertEquals("precondition: and its measurements", STAGE_2_MEASUREMENTS,
				measurementsOf(id, 2));
	}

	/** A concurrent actor re-dispatching the pair onto a new execution. */
	private static void redispatch(int id, int execId) throws SQLException {
		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement(
						"UPDATE starexec.job_pairs SET sge_id = ?, status_code = ? WHERE id = ?")) {
			ps.setInt(1, execId);
			ps.setInt(2, StatusCode.STATUS_RUNNING.getVal());
			ps.setInt(3, id);
			assertEquals(1, ps.executeUpdate());
		}
	}

	private static void setMeasurements(Connection con, int id, int stage, List<Double> m)
			throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(
				"UPDATE starexec.jobpair_stage_data SET cpu = ?, wallclock = ?, max_vmem = ?," +
						" max_res_set = ?, user_time = ?, system_time = ?" +
						" WHERE jobpair_id = ? AND stage_number = ?")) {
			for (int i = 0; i < 6; i++) {
				ps.setDouble(i + 1, m.get(i));
			}
			ps.setInt(7, id);
			ps.setInt(8, stage);
			assertEquals(1, ps.executeUpdate());
		}
	}

	private static void addAttribute(Connection con, int id, int stage, String key, String value)
			throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.job_attributes (pair_id, attr_key, attr_value, job_id, stage_number)" +
						" SELECT ?, ?, ?, job_id, ? FROM starexec.job_pairs WHERE id = ?")) {
			ps.setInt(1, id);
			ps.setString(2, key);
			ps.setString(3, value);
			ps.setInt(4, stage);
			ps.setInt(5, id);
			assertEquals(1, ps.executeUpdate());
		}
	}

	/** Everything the first attempt recorded is gone, and the pair is a fresh attempt. */
	private void assertResultsCleared(int id, int attemptBefore) throws SQLException {
		assertEquals("the pair is back at PENDING_SUBMIT",
				StatusCode.STATUS_PENDING_SUBMIT.getVal(), statusOf(id));
		assertEquals("as its next attempt", attemptBefore + 1, attemptOf(id));
		for (int stage = 1; stage <= 2; stage++) {
			assertEquals("stage " + stage + " keeps no attribute of the previous attempt",
					0, attributeCount(id, stage));
			assertEquals("stage " + stage + " keeps no measurement of the previous attempt",
					Arrays.asList(null, null, null, null, null, null), measurementsOf(id, stage));
			try (Connection con = Common.getConnection();
					PreparedStatement ps = con.prepareStatement(
							"SELECT disk_size, status_code FROM starexec.jobpair_stage_data" +
									" WHERE jobpair_id = ? AND stage_number = ?")) {
				ps.setInt(1, id);
				ps.setInt(2, stage);
				try (ResultSet rs = ps.executeQuery()) {
					assertTrue(rs.next());
					assertEquals("stage " + stage + " disk", 0L, rs.getLong(1));
					assertEquals("stage " + stage + " status",
							StatusCode.STATUS_PENDING_SUBMIT.getVal(), rs.getInt(2));
				}
			}
		}
	}

	/** cpu, wallclock, max_vmem, max_res_set, user_time, system_time; null where NULL. */
	private static List<Double> measurementsOf(int id, int stage) throws SQLException {
		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement(
						"SELECT cpu, wallclock, max_vmem, max_res_set, user_time, system_time" +
								" FROM starexec.jobpair_stage_data WHERE jobpair_id = ? AND stage_number = ?")) {
			ps.setInt(1, id);
			ps.setInt(2, stage);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue("pair " + id + " has a stage " + stage, rs.next());
				List<Double> values = new ArrayList<>();
				for (int i = 1; i <= 6; i++) {
					double v = rs.getDouble(i);
					values.add(rs.wasNull() ? null : v);
				}
				return values;
			}
		}
	}

	private static int attributeCount(int id, int stage) throws SQLException {
		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement(
						"SELECT count(*) FROM starexec.job_attributes WHERE pair_id = ? AND stage_number = ?")) {
			ps.setInt(1, id);
			ps.setInt(2, stage);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private static String attributeOf(int id, int stage, String key) throws SQLException {
		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement(
						"SELECT attr_value FROM starexec.job_attributes" +
								" WHERE pair_id = ? AND stage_number = ? AND attr_key = ?")) {
			ps.setInt(1, id);
			ps.setInt(2, stage);
			ps.setString(3, key);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	private static int attemptOf(int id) throws SQLException {
		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement(
						"SELECT current_attempt_no FROM starexec.job_pair_attempts WHERE pair_id = ?")) {
			ps.setInt(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue("pair " + id + " has an attempt row", rs.next());
				return rs.getInt(1);
			}
		}
	}

	/** A node UpdatePairRunSolverStats can resolve, created once per test and removed after. */
	private String resultsNodeName() throws SQLException {
		String name = "rerun-results-" + pairId;
		if (resultsNodeId == 0) {
			try (Connection con = Common.getConnection();
					PreparedStatement ps = con.prepareStatement(
							"INSERT INTO starexec.nodes (name, status) VALUES (?, 'ACTIVE') RETURNING id")) {
				ps.setString(1, name);
				try (ResultSet rs = ps.executeQuery()) {
					rs.next();
					resultsNodeId = rs.getInt(1);
				}
			}
		}
		return name;
	}

	private static String describe(List<AttributesTableData> table) {
		StringBuilder b = new StringBuilder();
		for (AttributesTableData row : table) {
			b.append('[').append(row.attrValue).append(", ").append(row.attrCount).append(", ")
					.append(row.wallclockSum).append(", ").append(row.cpuSum).append(']');
		}
		return b.toString();
	}

	private static String describeTotals(List<Triple<String, Integer, TimePair>> totals) {
		StringBuilder b = new StringBuilder();
		for (Triple<String, Integer, TimePair> row : totals) {
			b.append('[').append(row.getLeft()).append(", ").append(row.getMiddle()).append(", ")
					.append(row.getRight().getWallclock()).append(", ")
					.append(row.getRight().getCpu()).append(']');
		}
		return b.toString();
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/**
	 * A backend whose only stubbed behaviour is the confirmed-kill verdict. Mockito rather
	 * than a hand-written double so the eighteen unrelated {@link Backend} methods do not
	 * have to be implemented — and so a test can assert the backend was never consulted.
	 */
	private static Backend backendReturning(Backend.KillOutcome outcome) {
		Backend backend = org.mockito.Mockito.mock(Backend.class);
		org.mockito.Mockito
				.when(backend.killPairConfirmed(org.mockito.ArgumentMatchers.anyInt()))
				.thenReturn(outcome);
		return backend;
	}

	private boolean selectedBySweep(int id) throws SQLException {
		List<Integer> selected = JobPairs.getPairIdsByStatusNotRerunAfterDate(
				StatusCode.ERROR_RUNSCRIPT, R.earliestDateToRerunFailedPairs());
		return selected.contains(id);
	}

	private int statusOf(int id) throws SQLException {
		return selectInt("SELECT status_code FROM starexec.job_pairs WHERE id = " + id);
	}

	/** The execution identity on the row, in the COALESCE(sge_id, 0) form the SQL compares. */
	private int execIdOf(int id) throws SQLException {
		return selectInt(
				"SELECT COALESCE(sge_id, 0) FROM starexec.job_pairs WHERE id = " + id);
	}

	private String endTimeOf(int id) throws SQLException {
		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement(
						"SELECT end_time FROM starexec.job_pairs WHERE id = " + id);
				ResultSet rs = ps.executeQuery()) {
			rs.next();
			java.sql.Timestamp t = rs.getTimestamp(1);
			return t == null ? null : t.toString();
		}
	}

	private int tombstoneCount(int id) throws SQLException {
		return selectInt("SELECT count(*) FROM starexec.pairs_rerun WHERE pair_id = " + id);
	}

	private int selectInt(String sql) throws SQLException {
		try (Connection con = Common.getConnection()) {
			return selectInt(con, sql);
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

	private void exec(String sql) throws SQLException {
		try (Connection con = Common.getConnection()) {
			exec(con, sql);
		}
	}

	private static void exec(Connection con, String sql) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			ps.executeUpdate();
		}
	}
}
