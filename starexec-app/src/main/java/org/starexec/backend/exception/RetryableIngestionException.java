package org.starexec.backend.exception;

/**
 * Ingesting a finished container's results failed for a reason that may not recur.
 *
 * <p>The distinction this draws is the whole point of it. When {@code ContainerJobMonitor}
 * cannot process a completed container it has to decide between two very different things:
 * the container's results are invalid and never will be valid, or the platform was briefly
 * unable to record results that are perfectly good. The first justifies recording a failure
 * against the pair. The second must not, because the solver did its work correctly and a lock
 * timeout is not a scientific result.
 *
 * <p>Before this existed the monitor treated both the same way: any exception marked the pair
 * {@code ERROR_RUNSCRIPT} and deleted the container, so a database blip during ingestion
 * silently converted a successful multi-stage run into a failed one and destroyed the evidence
 * that would have shown otherwise.
 *
 * <p>Throw this for infrastructure: database unavailability, lock timeouts, a missing stored
 * routine, a transient failure reading the output directory. Do not throw it for content the
 * container produced -- a snapshot naming another pair, an unknown stage, a non-terminal
 * status -- because retrying that reaches the same conclusion forever.
 */
public class RetryableIngestionException extends Exception {

    private static final long serialVersionUID = 1L;

    public RetryableIngestionException(String message) {
        super(message);
    }

    public RetryableIngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
