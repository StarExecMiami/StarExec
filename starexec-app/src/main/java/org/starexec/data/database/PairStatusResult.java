package org.starexec.data.database;

/**
 * Outcome of an attempt to record a terminal status for a job pair.
 *
 * <p>A boolean cannot express this. {@code false} previously meant both "another writer
 * already recorded a result, so the work is finished" and "the write failed, so it must
 * be retried" -- opposite instructions to the caller. A monitor that tears down on the
 * second loses the result; one that retries on the first never stops.
 */
public enum PairStatusResult {
	/** The status was written. */
	APPLIED,

	/**
	 * Refused: the pair already held a different terminal status, so someone else
	 * recorded the result first. Nothing was written, and nothing should be retried --
	 * the pair is finished. Callers that own a container, pod or process for this pair
	 * should still release it, or it leaks for as long as the process lives.
	 */
	SUPERSEDED,

	/**
	 * The write did not complete: a database error, or a status transition the database
	 * rejected as illegal. The pair's status is whatever it was. Callers should keep the
	 * work discoverable so a later pass can retry it.
	 */
	FAILED,

	/**
	 * Refused before anything was written: the stage number does not identify a stage.
	 *
	 * <p>Stage numbers start at 1, so a value below that names no stage. It is not a near
	 * miss -- {@code UpdatePairStatusPrecise} sets the terminal status
	 * {@code WHERE stage_number = _stageNumber} and NOT_REACHED
	 * {@code WHERE stage_number > _stageNumber}, so 0 gives the status to nothing and
	 * NOT_REACHED to every stage the pair has, including ones that genuinely completed.
	 *
	 * <p>Distinct from {@link #FAILED} because the two ask opposite things of the caller.
	 * The input is wrong and will be wrong on every retry, so retrying is a loop against a
	 * condition that cannot heal. Distinct from {@link #SUPERSEDED} because the pair is not
	 * finished: nothing was recorded, and it still needs a result.
	 */
	REJECTED_INVALID_STAGE,

	/**
	 * Refused before anything was written: the write carried an attempt number other than
	 * the pair's current one (#185), so it came from an execution that a rerun has since
	 * replaced. The pair is not finished and needs no retry of this write -- the current
	 * attempt reports its own result. Not {@link #FAILED}: nothing is wrong with the
	 * database, and retrying can never succeed. Callers that own a container, pod or process
	 * for the superseded execution should still release it.
	 */
	STALE_ATTEMPT;

	/**
	 * True when this write needs no further attempt and the resource tracking it may be
	 * released. {@link #STALE_ATTEMPT} counts: the write can never succeed, and the
	 * execution that made it has been replaced.
	 */
	public boolean isSettled() {
		return this == APPLIED || this == SUPERSEDED || this == STALE_ATTEMPT;
	}
}
