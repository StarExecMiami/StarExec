package org.starexec.backend.exception;

/**
 * Exception for backend configuration and logic errors.
 *
 * Logic errors indicate problems with the backend configuration or implementation
 * that require investigation and fixes. These errors should NOT be retried, as
 * retrying will likely produce the same failure.
 *
 * Logic errors include:
 * - Invalid socket path or container image configuration
 * - Missing required environment variables
 * - Incompatible container image (binary compatibility issues)
 * - Invalid resource limits or scheduling parameters
 * - Misconfigured volume mounts or path translations
 * - Unsupported container features
 *
 * These errors SHOULD trigger:
 * 1. Immediate logging with full context
 * 2. Operational alerts (not auto-retries)
 * 3. Graceful degradation if possible
 * 4. Investigation and manual remediation
 *
 * Example:
 * <pre>
 * try {
 *     backend.initialize(rootDir);
 * } catch (BackendLogicException e) {
 *     log.error("Backend configuration error - requires investigation", e);
 *     alertOps(e, severity=CRITICAL);
 *     // Don't retry - fix configuration and restart
 *     gracefulShutdown();
 * }
 * </pre>
 *
 * MONITORING:
 * - Track count of logic errors (should be zero in production)
 * - Each logic error warrants investigation
 * - Use metrics: backend.logic.errors (counter)
 */
public class BackendLogicException extends BackendCommunicationException {
    private final String configurationContext;
    private final String suggestedFix;

    /**
     * Creates a logic exception with configuration context.
     *
     * @param message Description of the configuration/logic error
     * @param cause The underlying exception
     * @param exitCode Process exit code (-1 if not applicable)
     * @param stderr Stderr output from the failed process
     * @param backendType Type of backend (e.g., "podman", "kubernetes")
     * @param configurationContext Description of the configuration that failed
     * @param suggestedFix Suggested fix for the configuration error
     */
    public BackendLogicException(
            String message,
            Throwable cause,
            int exitCode,
            String stderr,
            String backendType,
            String configurationContext,
            String suggestedFix) {
        super(message, cause, exitCode, stderr, backendType);
        this.configurationContext = configurationContext != null ? configurationContext : "";
        this.suggestedFix = suggestedFix != null ? suggestedFix : "";
    }

    /**
     * Simplified constructor with sensible defaults.
     */
    public BackendLogicException(
            String message,
            String configurationContext,
            String suggestedFix) {
        this(message, null, -1, "", "unknown", configurationContext, suggestedFix);
    }

    /**
     * Returns the configuration context where the error occurred.
     * Example: "Container image initialization"
     */
    public String getConfigurationContext() {
        return configurationContext;
    }

    /**
     * Returns a suggested fix for this configuration error.
     * Useful for displaying to operators or in automated remediation.
     * Example: "Set STAREXEC_CONTAINER_SOCKET to /run/podman/podman.sock"
     */
    public String getSuggestedFix() {
        return suggestedFix;
    }

    /**
     * Returns true if this error is related to missing or invalid configuration.
     */
    public boolean isConfigurationError() {
        String message = getMessage();
        if (message == null) {
            return false;
        }
        message = message.toLowerCase();
        return message.contains("config") ||
               message.contains("environment") ||
               message.contains("socket") ||
               message.contains("path") ||
               message.contains("image") ||
               message.contains("not found");
    }

    /**
     * Returns true if this error is related to binary compatibility issues.
     * Useful for Alpine vs glibc compatibility detection.
     */
    public boolean isCompatibilityError() {
        String message = getMessage();
        if (message == null) {
            return false;
        }
        message = message.toLowerCase();
        return message.contains("binary") ||
               message.contains("compat") ||
               message.contains("glibc") ||
               message.contains("musl") ||
               message.contains("libc") ||
               message.contains("symbol") ||
               message.contains("not found");
    }

    /**
     * Creates a detailed error report for operational investigation.
     */
    public String getDetailedReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== BackendLogicException Report ===\n");
        report.append("Message: ").append(getMessage()).append("\n");
        report.append("Configuration Context: ").append(configurationContext).append("\n");
        report.append("Suggested Fix: ").append(suggestedFix).append("\n");
        report.append("Backend Type: ").append(getBackendType()).append("\n");
        report.append("Exit Code: ").append(getExitCode()).append("\n");
        report.append("Stderr: ").append(getStderr()).append("\n");
        if (getCause() != null) {
            report.append("Cause: ").append(getCause().getClass().getSimpleName())
                    .append(" - ").append(getCause().getMessage()).append("\n");
        }
        report.append("Is Configuration Error: ").append(isConfigurationError()).append("\n");
        report.append("Is Compatibility Error: ").append(isCompatibilityError()).append("\n");
        return report.toString();
    }

    @Override
    public String toString() {
        return "BackendLogicException{" +
                "message='" + getMessage() + '\'' +
                ", configurationContext='" + configurationContext + '\'' +
                ", suggestedFix='" + suggestedFix + '\'' +
                ", backendType='" + getBackendType() + '\'' +
                ", isConfigurationError=" + isConfigurationError() +
                ", isCompatibilityError=" + isCompatibilityError() +
                '}';
    }
}
