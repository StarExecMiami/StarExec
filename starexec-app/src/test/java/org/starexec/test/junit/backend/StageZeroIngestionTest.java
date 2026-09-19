package org.starexec.test.junit.backend;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.starexec.backend.ContainerJobMonitor;
import org.starexec.backend.LocalJobMonitor;
import org.starexec.backend.StageStatusSnapshots;
import org.starexec.backend.exception.RetryableIngestionException;
import org.starexec.data.to.Status.StatusCode;

import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 * What ingestion does with a terminal status that names no stage.
 *
 * <p>{@code status.json} carries {@code stageNumber: 0} whenever the job script used its
 * pair-level channel -- {@code sendStatus}'s stage argument defaults to 0 and
 * {@code exitJobscript}, {@code limitExceeded} and the processor paths all take that default.
 * The monitor reads that number straight out of the file and hands it to a routine whose
 * contract is "this stage took the result", where 0 means "give NOT_REACHED to every stage".
 *
 * <p>The write is now refused, and what matters here is what the monitor does with the refusal.
 * Three outcomes are all wrong and are asserted against:
 *
 * <ul>
 *   <li>retrying, which loops against input that will never change;</li>
 *   <li>falling through, which records this pair's statistics and attributes against a status
 *       the database declined;</li>
 *   <li>inventing a stage or a solver outcome so that something can be written.</li>
 * </ul>
 *
 * <p>The right outcome is the one the codebase already has for a deterministic artifact
 * defect: {@code InvalidSnapshotException}, which {@code IngestionOutcome} classifies BLOCKED,
 * so the pair is held with its output and an operator is told.
 *
 * <p>In its own class rather than added to {@code LocalJobMonitorTests} so that this change and
 * the other open monitor changes do not collide in the same file.
 */
public class StageZeroIngestionTest {

	private LocalJobMonitor monitor;

	@Before
	public void setUp() {
		monitor = new LocalJobMonitor();
	}

	@After
	public void tearDown() {
		if (monitor != null) {
			monitor.stop();
		}
	}

	private void updateDatabase(int pairId, StatusCode status, int stageNumber) throws Throwable {
		for (Method m : LocalJobMonitor.class.getDeclaredMethods()) {
			if (m.getName().equals("updateDatabase")) {
				m.setAccessible(true);
				try {
					m.invoke(monitor, pairId, status, stageNumber);
				} catch (java.lang.reflect.InvocationTargetException e) {
					throw e.getCause();
				}
				return;
			}
		}
		throw new AssertionError("no such method: updateDatabase");
	}

	// ------------------------------------------------------------------ the invariant

	/**
	 * The classification is the point. {@code InvalidSnapshotException} is BLOCKED -- held,
	 * evidence kept, operator told, never retried. {@code RetryableIngestionException} would be
	 * a permanent loop, because a stage number that names no stage will not name one later.
	 */
	@Test
	public void aTerminalStatusWithNoStageIsRecordedAgainstThePair() throws Throwable {
		// Stage 0 is the pair-level channel now (#165): the pair failed outside any stage, so
		// the status belongs to the pair. Without a database the write cannot be performed, so
		// what is asserted here is that the monitor no longer refuses the record as an artifact
		// defect; PairLevelStatusIngestionTest asserts which write it chooses.
		try {
			updateDatabase(50, StatusCode.STATUS_COMPLETE, 0);
		} catch (StageStatusSnapshots.InvalidSnapshotException refused) {
			fail("a pair-level status must not be refused as a content defect: "
					+ refused.getMessage());
		} catch (RetryableIngestionException expected) {
			// The write failed because there is no database in this test, which is retryable.
		}
	}

	/** Same for a negative stage, which is equally not a stage. */
	@Test
	public void aNegativeStageIsBlockedToo() throws Throwable {
		try {
			updateDatabase(50, StatusCode.STATUS_COMPLETE, -1);
			fail("a status with a negative stage number must not be recorded");
		} catch (StageStatusSnapshots.InvalidSnapshotException expected) {
			// as intended
		} catch (RetryableIngestionException wrong) {
			fail("a negative stage will not become valid on a retry");
		}
	}

	/**
	 * The refusal has to happen before the attribute and statistics writes that follow the
	 * status block, or this pair's measurements are recorded against a status the database
	 * refused. Asserted from the stack rather than from a database, because there is no
	 * database here and the ordering is the property under test.
	 */
	@Test
	public void nothingIsWrittenOnTheWayOutOfARefusal() throws Throwable {
		try {
			updateDatabase(50, StatusCode.STATUS_COMPLETE, -1);
			fail("expected a refusal");
		} catch (StageStatusSnapshots.InvalidSnapshotException expected) {
			for (StackTraceElement frame : expected.getStackTrace()) {
				String where = frame.getClassName() + "." + frame.getMethodName();
				assertFalse("a refused status must not reach the attribute write, but the"
								+ " stack shows " + where,
						where.contains("addJobPairAttributes"));
				assertFalse("a refused status must not reach the statistics write, but the"
								+ " stack shows " + where,
						where.contains("updateRunSolverStats"));
			}
		}
	}

	// ------------------------------------------------------- the container monitor

	/**
	 * Stage 0 is the pair-level channel in the container monitor too (#165): the pair failed
	 * outside any stage, so the status belongs to the pair and must not be refused as a content
	 * defect. Without a database the write cannot be performed, so what is asserted here is only
	 * that the record is no longer rejected; {@code PairLevelStatusIngestionTest} asserts which
	 * write the monitor chooses and with what arguments.
	 */
	@Test
	public void theContainerMonitorRecordsAPairLevelStatusAgainstThePair() throws Throwable {
		try {
			containerUpdateDatabase(50, StatusCode.STATUS_COMPLETE, 0);
		} catch (StageStatusSnapshots.InvalidSnapshotException refused) {
			fail("a pair-level status must not be refused as a content defect: "
					+ refused.getMessage());
		} catch (RetryableIngestionException expected) {
			// The write failed because there is no database in this test, which is retryable.
		}
	}

	/**
	 * The container monitor's positive control, matching the local monitor's below: a stage
	 * that names a stage still reaches the database, so with none configured it comes back as
	 * a transient failure rather than a refusal.
	 */
	@Test
	public void theContainerMonitorStillReachesTheDatabaseForARealStage() throws Throwable {
		try {
			containerUpdateDatabase(50, StatusCode.STATUS_COMPLETE, 1);
			fail("expected the absent database to be reported");
		} catch (StageStatusSnapshots.InvalidSnapshotException wrong) {
			fail("stage 1 names a stage and must not be refused as invalid");
		} catch (RetryableIngestionException expected) {
			// as intended
		}
	}

	private void containerUpdateDatabase(int pairId, StatusCode status, int stageNumber)
			throws Throwable {
		ContainerJobMonitor container = new ContainerJobMonitor(null);
		for (Method m : ContainerJobMonitor.class.getDeclaredMethods()) {
			if (m.getName().equals("updateDatabase") && m.getParameterCount() == 4) {
				m.setAccessible(true);
				try {
					m.invoke(container, pairId, stageNumber, status,
							new java.util.HashMap<Integer, Integer>());
				} catch (java.lang.reflect.InvocationTargetException e) {
					throw e.getCause();
				}
				return;
			}
		}
		throw new AssertionError("no such method: ContainerJobMonitor.updateDatabase/4");
	}

	/**
	 * The control. A stage number that names a stage must still reach the database, which is
	 * absent here -- so it fails as a database problem, not as a refusal. Without this, a guard
	 * that refused everything would pass the tests above.
	 */
	@Test
	public void aStageThatNamesAStageStillReachesTheDatabase() throws Throwable {
		try {
			updateDatabase(50, StatusCode.STATUS_COMPLETE, 1);
			fail("expected the absent database to be reported");
		} catch (StageStatusSnapshots.InvalidSnapshotException wrong) {
			fail("stage 1 names a stage and must not be refused as invalid");
		} catch (RetryableIngestionException expected) {
			// The database is not configured in a unit test, so the write is reported as a
			// transient failure -- which is exactly the classification a real outage gets.
		}
	}
}
