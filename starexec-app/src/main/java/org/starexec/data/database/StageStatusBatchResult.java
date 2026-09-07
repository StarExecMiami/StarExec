package org.starexec.data.database;

/**
 * What happened to a batch of reconstructed earlier-stage statuses.
 *
 * <p>Three outcomes rather than a boolean, because the caller has to tell an invalid batch
 * apart from a database that was briefly unavailable. Collapsing them is what let a lock
 * timeout be recorded as a solver failure.
 */
public enum StageStatusBatchResult {

    /** Committed. Individual stages may have been refused for already holding a result. */
    APPLIED,

    /**
     * A stage number in the batch does not belong to this pair, so the whole batch rolled
     * back. The data is wrong and will be wrong on every retry.
     */
    REJECTED_UNKNOWN_STAGE,

    /**
     * The write did not happen for an infrastructure reason -- the database was unreachable,
     * a lock timed out, the stored routine is missing because migrations have not run. Nothing
     * was written, and a later attempt may well succeed.
     */
    FAILED
}
