package org.starexec.test.junit.database;

import org.junit.Test;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.to.Status.StatusCode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

/**
 * A precise stage write has to name a stage.
 *
 * <h2>What it does when it does not</h2>
 *
 * {@code UpdatePairStatusPrecise} sets the terminal status
 * {@code WHERE stage_number = _stageNumber} and NOT_REACHED
 * {@code WHERE stage_number > _stageNumber}. Stage numbers start at 1, so a stage number of 0
 * gives the status to no row at all and gives NOT_REACHED to <em>every</em> stage the pair has,
 * including stages that genuinely completed and recorded their own result. The pair is then
 * stamped with an {@code end_time} and a completion row, which puts it beyond
 * {@code RERUN_FAILED_PAIRS}. A finished stage silently becomes "stage not reached", for good.
 *
 * <p>0 is not a stray value. {@code sendStatus}'s stage argument defaults to it
 * ({@code local STAGE_NUM=${2:-0}}) and every pair-level path in the job script takes that
 * default, so {@code status.json} carries {@code stageNumber: 0} routinely and the monitors read
 * it straight out of the file.
 *
 * <h2>How these run without a database</h2>
 *
 * That is the assertion, not a limitation. The guard refuses before
 * {@code Common.getConnection()} is called, so with no database configured a rejected call still
 * returns {@link PairStatusResult#REJECTED_INVALID_STAGE} while a call that gets past the guard
 * can only return {@link PairStatusResult#FAILED}. The difference between those two outcomes is
 * therefore direct evidence about whether the database was reached at all -- stronger than
 * observing that some exception was thrown somewhere later.
 *
 * <p>The routine's own guard is exercised against a real database by
 * {@code StageZeroPreciseStatusSqlTest}, because the routine is reachable from psql, from an
 * administrative session and from the test suite without passing through this boundary.
 */
public class StageZeroPreciseStatusTest {

	private static final int PAIR = 4242;
	private static final int COMPLETE = StatusCode.STATUS_COMPLETE.getVal();
	private static final int NOT_REACHED = StatusCode.STATUS_NOT_REACHED.getVal();

	private PairStatusResult write(int stageNumber) {
		return JobPairs.setPairStatusPreciseResult(PAIR, stageNumber, COMPLETE, NOT_REACHED, false);
	}

	// ------------------------------------------------------------------ the invariant

	@Test
	public void stageZeroIsRejectedWithoutReachingTheDatabase() {
		assertEquals(PairStatusResult.REJECTED_INVALID_STAGE, write(0));
	}

	@Test
	public void aNegativeStageIsRejectedWithoutReachingTheDatabase() {
		assertEquals(PairStatusResult.REJECTED_INVALID_STAGE, write(-1));
		assertEquals(PairStatusResult.REJECTED_INVALID_STAGE, write(Integer.MIN_VALUE));
	}

	/**
	 * The control that gives the two above their meaning. A stage number that does name a
	 * stage is not refused here: it goes on to the database, which is absent, so it comes back
	 * FAILED. If the guard were swallowing everything, this would read REJECTED too.
	 */
	@Test
	public void aStageThatNamesAStageIsNotRefusedByTheGuard() {
		for (int stage : new int[]{1, 2, 3, 99, Integer.MAX_VALUE}) {
			assertNotEquals("stage " + stage + " names a stage and must reach the database",
					PairStatusResult.REJECTED_INVALID_STAGE, write(stage));
		}
	}

	/** The boundary itself, stated once so an off-by-one cannot pass unnoticed. */
	@Test
	public void theBoundaryIsExactlyOne() {
		assertEquals(PairStatusResult.REJECTED_INVALID_STAGE, write(0));
		assertNotEquals(PairStatusResult.REJECTED_INVALID_STAGE, write(1));
	}

	// ------------------------------------------------- what the result means to a caller

	/**
	 * A rejected write must not be mistaken for a finished pair. Nothing was recorded, so the
	 * pair still needs a result -- it simply cannot be given one from this input.
	 */
	@Test
	public void aRejectedWriteDoesNotSettleThePair() {
		assertFalse(PairStatusResult.REJECTED_INVALID_STAGE.isSettled());
	}

	/**
	 * And it must not be mistaken for a transient failure, which is the distinction the
	 * constant exists for: FAILED asks the caller to retry, and this input will fail the same
	 * way on every retry.
	 */
	@Test
	public void aRejectedWriteIsDistinctFromATransientFailure() {
		assertNotEquals(PairStatusResult.FAILED, PairStatusResult.REJECTED_INVALID_STAGE);
		assertNotEquals(PairStatusResult.SUPERSEDED, PairStatusResult.REJECTED_INVALID_STAGE);
		assertNotEquals(PairStatusResult.APPLIED, PairStatusResult.REJECTED_INVALID_STAGE);
	}

	/**
	 * The convenience wrapper reports a rejection as "not applied" rather than throwing or
	 * reporting success. Callers that use the boolean form get the safe reading by default.
	 */
	@Test
	public void theBooleanWrapperReportsARejectionAsNotApplied() {
		assertFalse(JobPairs.setPairStatusPrecise(PAIR, 0, COMPLETE, NOT_REACHED, false));
	}
}
