package org.starexec.test.junit.backend;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.backend.LocalJobMonitor;
import org.starexec.backend.StageStatusSnapshots;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.to.Status.StatusCode;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * A pair that fails outside any stage is recorded against the pair, not against a stage (#165).
 *
 * <p>The job script reports such a failure on the pair-level channel: {@code status.json} carries
 * {@code stageNumber 0}, because no stage was running. Every monitor refused that record, so the
 * pair kept its last status -- RUNNING -- for as long as the application ran, while the monitor
 * re-detected the same finished execution on every poll.
 *
 * <p>These drive {@code LocalJobMonitor.updateDatabase} directly, with the database replaced at
 * its static boundary, so what is asserted is which write the monitor chooses and with what
 * arguments. A stage-numbered result must still take the precise write, and a status the
 * protocol does not allow must still be refused.
 */
public class PairLevelStatusIngestionTest {

	private static final int PAIR = 54;
	private static final int PAIR_LEVEL = 0;
	private static final int NOT_REACHED = StatusCode.STATUS_NOT_REACHED.getVal();

	/** QA's live case: a benchmark dependency the job script could not find, before any stage. */
	private static final StatusCode DEPENDENCY_MISSING = StatusCode.ERROR_BENCH_DEPENDENCY_MISSING;

	@Test
	public void aPairLevelTerminalStatusIsRecordedAgainstThePair() throws Throwable {
		try (MockedStatic<JobPairs> pairs = Mockito.mockStatic(JobPairs.class)) {
			pairs.when(() -> JobPairs.setPairLevelStatusResult(
					Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
					.thenReturn(PairStatusResult.APPLIED);

			updateDatabase(DEPENDENCY_MISSING, PAIR_LEVEL);

			pairs.verify(() -> JobPairs.setPairLevelStatusResult(
					PAIR, DEPENDENCY_MISSING.getVal(), NOT_REACHED));
			pairs.verify(() -> JobPairs.setPairStatusPreciseResult(
					Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
					Mockito.anyBoolean()), Mockito.never());
		}
	}

	/**
	 * The transient record every pair produces: the job script announces RUNNING before it knows
	 * the stage, then again with the stage. A poll landing between the two must not write, and
	 * must not hold the pair.
	 */
	@Test
	public void aPairLevelRunningStatusIsNotWritten() throws Throwable {
		try (MockedStatic<JobPairs> pairs = Mockito.mockStatic(JobPairs.class)) {
			updateDatabase(StatusCode.STATUS_RUNNING, PAIR_LEVEL);

			pairs.verify(() -> JobPairs.setPairLevelStatusResult(
					Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()), Mockito.never());
			pairs.verify(() -> JobPairs.setPairStatusPreciseResult(
					Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
					Mockito.anyBoolean()), Mockito.never());
		}
	}

	/**
	 * Only RUNNING and a terminal execution result are protocol. STATUS_PROCESSING(22) means work
	 * is still owed, and a pair left at 22 is picked up by the post-processing task and completed,
	 * so accepting it on the pair-level channel would launder an unfinished pair into a clean one.
	 */
	@Test
	public void aPairLevelStatusThatIsNotProtocolIsStillRefused() throws Throwable {
		try (MockedStatic<JobPairs> pairs = Mockito.mockStatic(JobPairs.class)) {
			try {
				updateDatabase(StatusCode.STATUS_PROCESSING, PAIR_LEVEL);
				fail("a pair-level status that is neither RUNNING nor terminal must be refused");
			} catch (StageStatusSnapshots.InvalidSnapshotException expected) {
				assertEquals("must be classified BLOCKED, not retried",
						"BLOCKED", classify(expected));
			}
			pairs.verify(() -> JobPairs.setPairLevelStatusResult(
					Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()), Mockito.never());
		}
	}

	/** The control: a result that names a stage still takes the precise write, unchanged. */
	@Test
	public void aStageNumberedResultStillTakesThePreciseWrite() throws Throwable {
		try (MockedStatic<JobPairs> pairs = Mockito.mockStatic(JobPairs.class)) {
			pairs.when(() -> JobPairs.setPairStatusPreciseResult(
					Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
					Mockito.anyBoolean()))
					.thenReturn(PairStatusResult.APPLIED);

			updateDatabase(StatusCode.STATUS_COMPLETE, 2);

			pairs.verify(() -> JobPairs.setPairStatusPreciseResult(
					PAIR, 2, StatusCode.STATUS_COMPLETE.getVal(), NOT_REACHED, false));
			pairs.verify(() -> JobPairs.setPairLevelStatusResult(
					Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()), Mockito.never());
		}
	}

	/** A pair-level write the database could not perform is retryable, never a solver status. */
	@Test
	public void aFailedPairLevelWriteIsRetryable() throws Throwable {
		try (MockedStatic<JobPairs> pairs = Mockito.mockStatic(JobPairs.class)) {
			pairs.when(() -> JobPairs.setPairLevelStatusResult(
					Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
					.thenReturn(PairStatusResult.FAILED);

			try {
				updateDatabase(DEPENDENCY_MISSING, PAIR_LEVEL);
				fail("a failed write must not be treated as recorded");
			} catch (Throwable expected) {
				assertEquals("must be classified RETRY, not blocked",
						"RETRY", classify(expected));
			}
		}
	}

	private static void updateDatabase(StatusCode status, int stageNumber) throws Throwable {
		LocalJobMonitor monitor = new LocalJobMonitor();
		try {
			Method m = LocalJobMonitor.class.getDeclaredMethod(
					"updateDatabase", int.class, StatusCode.class, int.class);
			m.setAccessible(true);
			try {
				m.invoke(monitor, PAIR, status, stageNumber);
			} catch (java.lang.reflect.InvocationTargetException e) {
				throw e.getCause();
			}
		} finally {
			monitor.stop();
		}
	}

	/** {@code IngestionOutcome} is package-private; its classification is the assertion. */
	private static String classify(Throwable failure) throws Exception {
		Class<?> c = Class.forName("org.starexec.backend.IngestionOutcome");
		Method m = c.getDeclaredMethod("classify", Throwable.class);
		m.setAccessible(true);
		return String.valueOf(m.invoke(null, failure));
	}
}
