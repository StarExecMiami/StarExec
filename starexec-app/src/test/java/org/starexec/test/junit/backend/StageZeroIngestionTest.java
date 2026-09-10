package org.starexec.test.junit.backend;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.starexec.backend.LocalJobMonitor;
import org.starexec.backend.StageStatusSnapshots;
import org.starexec.backend.exception.RetryableIngestionException;
import org.starexec.data.to.Status.StatusCode;

import java.lang.reflect.Method;
import java.util.Properties;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
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

	/** RunSolverStats is private; an all-zero instance is enough to reach the status write. */
	private Object zeroStats() throws Exception {
		Class<?> type = Class.forName("org.starexec.backend.LocalJobMonitor$RunSolverStats");
		var ctor = type.getDeclaredConstructors()[0];
		ctor.setAccessible(true);
		Object[] args = new Object[ctor.getParameterCount()];
		Class<?>[] types = ctor.getParameterTypes();
		for (int i = 0; i < args.length; i++) {
			if (types[i] == double.class) args[i] = 0.0d;
			else if (types[i] == long.class) args[i] = 0L;
			else if (types[i] == int.class) args[i] = 0;
			else args[i] = null;
		}
		return ctor.newInstance(args);
	}

	private void updateDatabase(int pairId, StatusCode status, int stageNumber) throws Throwable {
		for (Method m : LocalJobMonitor.class.getDeclaredMethods()) {
			if (m.getName().equals("updateDatabase")) {
				m.setAccessible(true);
				try {
					m.invoke(monitor, pairId, status, stageNumber, zeroStats(), new Properties());
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
	public void aTerminalStatusWithNoStageIsBlockedRatherThanRetried() throws Throwable {
		try {
			updateDatabase(50, StatusCode.STATUS_COMPLETE, 0);
			fail("a status with stage number 0 must not be recorded");
		} catch (StageStatusSnapshots.InvalidSnapshotException expected) {
			assertTrue("the refusal must say which stage number was refused: "
							+ expected.getMessage(),
					expected.getMessage().contains("stage number 0"));
		} catch (RetryableIngestionException wrong) {
			fail("stage 0 will not become valid on a retry; this must not be retryable");
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
			updateDatabase(50, StatusCode.STATUS_COMPLETE, 0);
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
