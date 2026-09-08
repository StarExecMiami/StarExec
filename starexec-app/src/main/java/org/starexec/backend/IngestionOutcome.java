package org.starexec.backend;

import java.sql.SQLException;
import java.sql.SQLTransientException;

/**
 * Why a completed pair's results could not be recorded, and what should follow.
 *
 * <p>The distinction exists because a monitor that treats every failure the same way has only
 * destructive options. {@code LocalJobMonitor} used to mark the pair -- and every one of its
 * stages -- {@code ERROR_RUNSCRIPT} and stop tracking it, so a database that was briefly
 * unavailable turned a good solver run into a recorded scientific failure. Worse, when the
 * database was unavailable the fabricated write failed too, and the pair was dropped with no
 * terminal status at all: stranded rather than merely wrong.
 *
 * <p>Neither outcome is a property of the solver, so neither may be recorded as one.
 */
enum IngestionOutcome {

    /**
     * The platform could not record results that are probably fine. Keep the pair tracked,
     * keep its output, and try again later.
     */
    RETRYABLE,

    /**
     * The results, or the schema needed to record them, will not become valid by waiting: a
     * missing stored routine, an artifact naming another pair, malformed JSON that reads the
     * same way every time. Retrying is a hot loop against a condition only an operator or a
     * deployment can clear, so the pair is held for intervention with its evidence intact.
     */
    BLOCKED;

    /**
     * Classifies a failure conservatively.
     *
     * <p>Conservatively means: an unrecognised {@link SQLException} is {@link #BLOCKED}, not
     * {@link #RETRYABLE}. Guessing "transient" costs a permanent hot loop against something
     * that cannot heal; guessing "blocked" costs an operator alert on a pair whose evidence is
     * still intact. Only the second is recoverable without a deployment.
     *
     * <p>Neither answer ever becomes a solver status. That is the whole point of the type.
     */
    static IngestionOutcome classify(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof org.starexec.backend.exception.RetryableIngestionException) {
                // The caller already established this: the database layer reports a failed
                // write as a status rather than an exception, so the SQLException that caused
                // it is logged and swallowed inside JobPairs and cannot be inspected here.
                return RETRYABLE;
            }
            if (t instanceof SQLException) {
                return classifySql((SQLException) t);
            }
            if (t instanceof java.io.IOException) {
                // The output directory is on local disk and the process that wrote it has
                // exited. A read that fails now is the filesystem, not the contents.
                return RETRYABLE;
            }
            if (t instanceof StageStatusSnapshots.InvalidSnapshotException) {
                // Deterministic: the same bytes will fail the same checks forever.
                return BLOCKED;
            }
        }
        return BLOCKED;
    }

    private static IngestionOutcome classifySql(SQLException e) {
        if (e instanceof SQLTransientException) {
            return RETRYABLE;
        }
        String state = e.getSQLState();
        if (state == null || state.length() < 2) {
            return BLOCKED;
        }
        switch (state) {
            // Serialization failure and deadlock: the transaction is expected to be retried.
            case "40001":
            case "40P01":
            // Generic rollback.
            case "40000":
            // Lock not available.
            case "55P03":
            // The server is starting, shutting down, or refusing new connections.
            case "57P01":
            case "57P02":
            case "57P03":
            // Too many connections / out of memory: load, not correctness.
            case "53300":
            case "53200":
                return RETRYABLE;
            default:
                break;
        }
        // Class 08 is connection exception in its entirety -- transport, not content.
        if (state.startsWith("08")) {
            return RETRYABLE;
        }
        // Class 42 is syntax error or access rule violation, which includes 42883
        // "undefined function": the stage-only routine is missing because migrations have
        // not run. Waiting cannot fix that; a deployment can. Class 23 is integrity
        // violation, which means the data is wrong rather than the moment.
        return BLOCKED;
    }
}
