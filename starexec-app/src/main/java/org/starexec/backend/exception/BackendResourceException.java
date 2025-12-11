package org.starexec.backend.exception;

/**
 * Exception for backend resource exhaustion and allocation failures.
 *
 * Resource exceptions indicate that the backend has run out of critical resources
 * and cannot process more jobs until cleanup occurs. These errors require:
 * 1. Immediate cleanup of orphaned containers/processes
 * 2. Pausing job submission until resources are freed
 * 3. Alerting operations about capacity issues
 * 4. Potential triggering of autoscaling or resource reclamation
 *
 * Resource exhaustion includes:
 * - No available memory for new containers
 * - Disk space exhausted
 * - Maximum number of containers reached
 * - Maximum open file descriptors exceeded
 * - Docker/Podman daemon resource limits exceeded
 * - Database connection pool exhausted
 *
 * These errors SHOULD trigger:
 * 1. Orphaned container cleanup (docker ps -a | grep starexec-job)
 * 2. Temporary pause of job queue (STAREXEC_PAUSE_JOBS)
 * 3. Capacity alerts to operations
 * 4. Metrics collection for capacity planning
 * 5. Automatic scaling (if running on cloud infrastructure)
 *
 * Example:
 * <pre>
 * try {
 *     container.create(jobRequest);
 * } catch (BackendResourceException e) {
 *     log.error("Resource exhaustion - triggering cleanup", e);
 *     backend.cleanupOrphanedContainers();
 *     backend.pauseJobQueue();
 *     alertOps(e, severity=WARNING);
 *     throw e; // Fail fast to prevent cascade failures
 * }
 * </pre>
 *
 * MONITORING:
 * - Track frequency of resource exceptions
 * - Monitor resource type causing exhaustion
 * - Alert when any resource reaches 80% capacity
 * - Use metrics: backend.resource.exhausted (counter),
 *   backend.resource.cleanup (gauge), backend.queue.paused (gauge)
 */
public class BackendResourceException extends BackendCommunicationException {
    private final ResourceType resourceType;
    private final long currentUsage;
    private final long maxCapacity;
    private final String cleanupAction;

    /**
     * Enumeration of resource types that can be exhausted.
     */
    public enum ResourceType {
        MEMORY("Memory"),
        DISK_SPACE("Disk Space"),
        CONTAINERS("Container Count"),
        FILE_DESCRIPTORS("File Descriptors"),
        DATABASE_CONNECTIONS("Database Connections"),
        NETWORK_CONNECTIONS("Network Connections"),
        CPU_QUOTA("CPU Quota"),
        UNKNOWN("Unknown Resource");

        private final String displayName;

        ResourceType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Creates a resource exhaustion exception.
     *
     * @param message Description of the resource exhaustion
     * @param cause The underlying exception
     * @param exitCode Process exit code (-1 if not applicable)
     * @param stderr Stderr output from the failed process
     * @param backendType Type of backend (e.g., "podman", "kubernetes")
     * @param resourceType Type of resource that was exhausted
     * @param currentUsage Current resource usage
     * @param maxCapacity Maximum resource capacity
     * @param cleanupAction Description of cleanup action to perform
     */
    public BackendResourceException(
            String message,
            Throwable cause,
            int exitCode,
            String stderr,
            String backendType,
            ResourceType resourceType,
            long currentUsage,
            long maxCapacity,
            String cleanupAction) {
        super(message, cause, exitCode, stderr, backendType);
        this.resourceType = resourceType;
        this.currentUsage = currentUsage;
        this.maxCapacity = maxCapacity;
        this.cleanupAction = cleanupAction != null ? cleanupAction : "Cleanup required";
    }

    /**
     * Simplified constructor with sensible defaults.
     */
    public BackendResourceException(
            String message,
            ResourceType resourceType,
            long currentUsage,
            long maxCapacity) {
        this(message, null, -1, "", "unknown", resourceType, currentUsage, maxCapacity,
             "Clean up orphaned resources");
    }

    /**
     * Returns the type of resource that was exhausted.
     */
    public ResourceType getResourceType() {
        return resourceType;
    }

    /**
     * Returns the current usage of the exhausted resource.
     */
    public long getCurrentUsage() {
        return currentUsage;
    }

    /**
     * Returns the maximum capacity of the resource.
     */
    public long getMaxCapacity() {
        return maxCapacity;
    }

    /**
     * Returns the percentage of resource capacity currently in use (0-100).
     */
    public double getUsagePercentage() {
        if (maxCapacity == 0) {
            return 0.0;
        }
        return ((double) currentUsage / maxCapacity) * 100.0;
    }

    /**
     * Returns a description of the cleanup action to perform.
     */
    public String getCleanupAction() {
        return cleanupAction;
    }

    /**
     * Returns true if the resource usage is critically high (> 95%).
     */
    public boolean isCritical() {
        return getUsagePercentage() > 95.0;
    }

    /**
     * Returns true if the resource usage is severely high (> 80%).
     */
    public boolean isSevere() {
        return getUsagePercentage() > 80.0;
    }

    /**
     * Returns the amount of free space remaining.
     */
    public long getFreeCapacity() {
        return Math.max(0, maxCapacity - currentUsage);
    }

    /**
     * Creates a detailed resource exhaustion report for operations.
     */
    public String getDetailedReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== BackendResourceException Report ===\n");
        report.append("Message: ").append(getMessage()).append("\n");
        report.append("Resource Type: ").append(resourceType.getDisplayName()).append("\n");
        report.append("Current Usage: ").append(formatBytes(currentUsage)).append("\n");
        report.append("Maximum Capacity: ").append(formatBytes(maxCapacity)).append("\n");
        report.append("Free Capacity: ").append(formatBytes(getFreeCapacity())).append("\n");
        report.append("Usage Percentage: ").append(String.format("%.1f%%", getUsagePercentage())).append("\n");
        report.append("Severity: ").append(isCritical() ? "CRITICAL" : isSevere() ? "SEVERE" : "WARNING").append("\n");
        report.append("Cleanup Action: ").append(cleanupAction).append("\n");
        report.append("Backend Type: ").append(getBackendType()).append("\n");
        if (getCause() != null) {
            report.append("Cause: ").append(getCause().getClass().getSimpleName())
                    .append(" - ").append(getCause().getMessage()).append("\n");
        }
        return report.toString();
    }

    /**
     * Formats bytes as human-readable string (B, KB, MB, GB, TB).
     */
    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format("%.1f %s",
                bytes / Math.pow(1024, digitGroups),
                units[digitGroups]);
    }

    @Override
    public String toString() {
        return "BackendResourceException{" +
                "message='" + getMessage() + '\'' +
                ", resourceType=" + resourceType.getDisplayName() +
                ", usage=" + String.format("%.1f%%", getUsagePercentage()) +
                " (" + formatBytes(currentUsage) + "/" + formatBytes(maxCapacity) + ")" +
                ", severity=" + (isCritical() ? "CRITICAL" : isSevere() ? "SEVERE" : "WARNING") +
                ", backendType='" + getBackendType() + '\'' +
                '}';
    }
}
