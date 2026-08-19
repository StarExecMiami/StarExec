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
	FAILED;

	/** True when the pair is in a terminal state and needs no further attempt. */
	public boolean isSettled() {
		return this == APPLIED || this == SUPERSEDED;
	}
}
