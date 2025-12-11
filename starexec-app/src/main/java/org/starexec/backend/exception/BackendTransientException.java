package org.starexec.backend.exception;

/**
 * Exception for transient/temporary backend failures that can be retried.
 *
 * Transient errors include:
 * - Network timeouts (socket timeout, connection reset)
 * - Temporary service unavailability (container engine restarting)
 * - Resource contention (temporary memory pressure)
 * - Broken pipes (intermittent connection issues)
 *
 * These errors SHOULD trigger automatic retry with exponential backoff.
 *
 * Example:
 * <pre>
 * try {
 *     container.execute(command);
 * } catch (BackendTransientException e) {
 *     log.warn("Transient error, retrying: " + e.getMessage());
 *     return retryWithBackoff(e, maxAttempts=3);
 * }
 * </pre>
 *
 * MONITORING:
 * - Track frequency of transient errors (should be < 1% of requests)
 * - If spike in transient errors, investigate network/container engine health
 * - Use metrics: backend.transient.errors (counter), backend.retry.attempts (histogram)
 */
public class BackendTransientException extends BackendCommunicationException {
    private final int retryAttempt;
    private final long nextRetryDelayMs;

    /**
     * Creates a transient exception that should be retried.
     *
     * @param message Description of the error
     * @param cause The underlying exception
     * @param exitCode Process exit code (-1 if not applicable)
     * @param stderr Stderr output from the failed process
     * @param backendType Type of backend (e.g., "podman", "kubernetes")
     * @param retryAttempt Current retry attempt (0 = first attempt)
     * @param nextRetryDelayMs Suggested delay before retrying (milliseconds)
     */
    public BackendTransientException(
            String message,
            Throwable cause,
            int exitCode,
            String stderr,
            String backendType,
            int retryAttempt,
            long nextRetryDelayMs) {
        super(message, cause, exitCode, stderr, backendType);
        this.retryAttempt = retryAttempt;
        this.nextRetryDelayMs = nextRetryDelayMs;
    }

    /**
     * Simplified constructor with sensible defaults.
     */
    public BackendTransientException(
            String message,
            Throwable cause,
            String backendType) {
        this(message, cause, -1, "", backendType, 0, 1000L);
    }

    /**
     * Returns the current retry attempt number (0 = first attempt).
     */
    public int getRetryAttempt() {
        return retryAttempt;
    }

    /**
     * Returns the suggested delay (in milliseconds) before the next retry.
     * Implements exponential backoff: 1s, 2s, 4s, 8s, etc.
     */
    public long getNextRetryDelayMs() {
        return nextRetryDelayMs;
    }

    /**
     * Returns true if this error is likely due to network issues.
     * Used to distinguish network problems from other transient issues.
     */
    public boolean isNetworkError() {
        if (getCause() == null) {
            return false;
        }
        String message = getCause().getMessage();
        if (message == null) {
            return false;
        }
        message = message.toLowerCase();
        return message.contains("timeout") ||
               message.contains("connection") ||
               message.contains("reset") ||
               message.contains("broken pipe") ||
               message.contains("refused") ||
               message.contains("unreachable");
    }

    /**
     * Creates a new exception with incremented retry attempt.
     * Useful for logging retry chains.
     */
    public BackendTransientException withNextRetry(long delayMs) {
        return new BackendTransientException(
                getMessage(),
                getCause(),
                getExitCode(),
                getStderr(),
                getBackendType(),
                retryAttempt + 1,
                delayMs);
    }

    @Override
    public String toString() {
        return "BackendTransientException{" +
                "message='" + getMessage() + '\'' +
                ", exitCode=" + getExitCode() +
                ", retryAttempt=" + retryAttempt +
                ", nextRetryDelayMs=" + nextRetryDelayMs +
                ", isNetworkError=" + isNetworkError() +
                ", backendType='" + getBackendType() + '\'' +
                ", timestamp=" + getTimestamp() +
                '}';
    }
}
