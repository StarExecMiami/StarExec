package org.starexec.backend.exception;

/**
 * Base exception for backend communication failures.
 *
 * This exception hierarchy allows callers to distinguish between:
 * - Network/transient errors (can retry)
 * - Logic/configuration errors (need investigation)
 * - Resource exhaustion (need cleanup)
 *
 * This observability is crucial for:
 * 1. Automatic retry policies (only for transient errors)
 * 2. Alerting (different thresholds for different error types)
 * 3. Debugging (stack traces categorized by error type)
 *
 * Example usage:
 * <pre>
 * try {
 *     backend.submitScript(scriptPath, jobId, dataDir);
 * } catch (BackendTransientException e) {
 *     // Retry with exponential backoff
 *     retryWithBackoff(e);
 * } catch (BackendLogicException e) {
 *     // Log error and notify admin
 *     log.error("Configuration error in backend", e);
 *     alertOps(e);
 * } catch (BackendResourceException e) {
 *     // Trigger cleanup and queue pause
 *     cleanupOrphanedContainers();
 *     pauseJobQueue();
 * }
 * </pre>
 */
public abstract class BackendCommunicationException extends Exception {
    private final int exitCode;
    private final String stderr;
    private final long timestamp;
    private final String backendType;

    protected BackendCommunicationException(
            String message,
            Throwable cause,
            int exitCode,
            String stderr,
            String backendType) {
        super(message, cause);
        this.exitCode = exitCode;
        this.stderr = stderr != null ? stderr : "";
        this.timestamp = System.currentTimeMillis();
        this.backendType = backendType;
    }

    /**
     * Returns the process exit code (if available).
     * -1 if the process did not complete normally.
     */
    public int getExitCode() {
        return exitCode;
    }

    /**
     * Returns the stderr output from the process.
     * Useful for debugging subprocess failures.
     */
    public String getStderr() {
        return stderr;
    }

    /**
     * Returns the timestamp when this exception was created.
     * Useful for correlating with log entries.
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the backend type (e.g., "podman", "kubernetes", "local").
     * Useful for routing error handling based on backend.
     */
    public String getBackendType() {
        return backendType;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "message='" + getMessage() + '\'' +
                ", exitCode=" + exitCode +
                ", stderrLength=" + stderr.length() +
                ", backendType='" + backendType + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
