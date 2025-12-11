package org.starexec.backend;

import org.starexec.logger.StarLogger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Manages graceful process termination with SIGTERM → SIGKILL strategy.
 *
 * This class implements a three-phase shutdown sequence to prevent data corruption
 * and resource leaks:
 *
 * 1. SIGTERM Phase (10 seconds) - Allow graceful shutdown
 *    - Process receives SIGTERM signal
 *    - Process can flush buffers, close files, cleanup
 *    - Useful for long-running solvers that need cleanup
 *
 * 2. Hard Kill Phase (5 seconds) - Force termination if needed
 *    - If process doesn't respond to SIGTERM, send SIGKILL
 *    - Immediately terminates the process
 *    - Guarantees process is gone
 *
 * 3. Verification Phase (automatic)
 *    - Verify process is actually dead
 *    - Log warnings if verification fails
 *    - Trigger resource cleanup if needed
 *
 * Usage:
 * <pre>
 * ProcessTerminationManager terminator = new ProcessTerminationManager();
 * Process process = startSolver();
 *
 * try {
 *     int exitCode = process.waitFor(300, TimeUnit.SECONDS); // 5 minute timeout
 *     if (exitCode != 0) {
 *         log.warn("Solver exited with code: " + exitCode);
 *     }
 * } catch (InterruptedException e) {
 *     log.error("Solver execution interrupted", e);
 *     terminator.terminateGracefully(process);
 * }
 * </pre>
 *
 * MONITORING:
 * - Track time spent in SIGTERM phase (should be < 1s average)
 * - Track time spent in SIGKILL phase (should be < 5s)
 * - Alert if processes don't respond to SIGKILL (zombie processes)
 * - Use metrics: process.termination.phase (gauge), process.termination.time_ms (histogram)
 *
 * @author StarExec Team
 */
public class ProcessTerminationManager {
    private static final StarLogger log = StarLogger.getLogger(ProcessTerminationManager.class);

    /**
     * Configuration for termination behavior.
     */
    public static class TerminationConfig {
        private final long sigterm_timeout_ms;  // Time to wait for graceful shutdown
        private final long sigkill_timeout_ms;  // Time to wait for forced kill
        private final boolean verifyDeath;      // Verify process is actually dead
        private final boolean logStackTrace;    // Log stack trace on timeout

        /**
         * Creates termination configuration with custom timeouts.
         *
         * @param sigterm_timeout_ms SIGTERM grace period (milliseconds)
         * @param sigkill_timeout_ms SIGKILL timeout (milliseconds)
         * @param verifyDeath Whether to verify process death
         * @param logStackTrace Whether to log stack trace on timeout
         */
        public TerminationConfig(
                long sigterm_timeout_ms,
                long sigkill_timeout_ms,
                boolean verifyDeath,
                boolean logStackTrace) {
            this.sigterm_timeout_ms = sigterm_timeout_ms;
            this.sigkill_timeout_ms = sigkill_timeout_ms;
            this.verifyDeath = verifyDeath;
            this.logStackTrace = logStackTrace;
        }

        /**
         * Default configuration: 10s SIGTERM, 5s SIGKILL, with verification.
         */
        public static TerminationConfig createDefault() {
            return new TerminationConfig(10000, 5000, true, true);
        }

        /**
         * Fast termination: 2s SIGTERM, 2s SIGKILL (for fast cleanup).
         */
        public static TerminationConfig createFast() {
            return new TerminationConfig(2000, 2000, true, false);
        }

        /**
         * Aggressive termination: No grace period (for stuck processes).
         */
        public static TerminationConfig createAggressive() {
            return new TerminationConfig(0, 5000, true, true);
        }
    }

    private final TerminationConfig config;

    /**
     * Creates a process termination manager with default configuration.
     */
    public ProcessTerminationManager() {
        this(TerminationConfig.createDefault());
    }

    /**
     * Creates a process termination manager with custom configuration.
     */
    public ProcessTerminationManager(TerminationConfig config) {
        this.config = config;
    }

    /**
     * Terminates a process gracefully using SIGTERM → SIGKILL strategy.
     *
     * @param process The process to terminate
     * @throws InterruptedException If the thread is interrupted during termination
     * @throws TimeoutException If the process doesn't respond to signals
     */
    public void terminateGracefully(Process process)
            throws InterruptedException, TimeoutException {

        if (process == null) {
            log.debug("Process is null, nothing to terminate");
            return;
        }

        // Check if process is already terminated
        if (isProcessTerminated(process)) {
            log.debug("Process is already terminated");
            return;
        }

        long startTime = System.currentTimeMillis();
        long phase1Start = startTime;

        try {
            // PHASE 1: Send SIGTERM (graceful shutdown)
            log.info("PHASE 1: Sending SIGTERM to process (grace period: " +
                    config.sigterm_timeout_ms + "ms)");
            process.destroy();

            // Wait for graceful shutdown
            boolean terminated = process.waitFor(
                    config.sigterm_timeout_ms,
                    TimeUnit.MILLISECONDS);

            long phase1Duration = System.currentTimeMillis() - phase1Start;

            if (terminated) {
                // Process responded to SIGTERM
                int exitCode = process.exitValue();
                log.info("PHASE 1 SUCCESS: Process terminated gracefully in " +
                        phase1Duration + "ms with exit code: " + exitCode);
                return;
            }

            // PHASE 2: Send SIGKILL (forced termination)
            log.warn("PHASE 1 TIMEOUT: Process did not respond to SIGTERM in " +
                    phase1Duration + "ms, sending SIGKILL");

            long phase2Start = System.currentTimeMillis();
            process.destroyForcibly();

            // Wait for forced kill
            terminated = process.waitFor(
                    config.sigkill_timeout_ms,
                    TimeUnit.MILLISECONDS);

            long phase2Duration = System.currentTimeMillis() - phase2Start;

            if (terminated) {
                // Process finally died
                int exitCode = process.exitValue();
                log.warn("PHASE 2 SUCCESS: Process killed forcibly in " +
                        phase2Duration + "ms with exit code: " + exitCode);
                return;
            }

            // PHASE 3: Verification (process still alive after SIGKILL)
            log.error("PHASE 2 TIMEOUT: Process did not respond to SIGKILL in " +
                    phase2Duration + "ms - possible zombie process!");

            // Try one more time with additional checks
            Thread.sleep(1000);
            if (!isProcessTerminated(process)) {
                long totalDuration = System.currentTimeMillis() - startTime;
                throw new TimeoutException(
                        "Process did not terminate after SIGTERM + SIGKILL in " + totalDuration + "ms");
            }

        } catch (InterruptedException e) {
            // Thread was interrupted during termination
            log.error("Termination interrupted, forcing immediate kill", e);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /**
     * Terminates a process with custom configuration.
     *
     * @param process The process to terminate
     * @param config Custom termination configuration
     * @throws InterruptedException If the thread is interrupted
     * @throws TimeoutException If the process doesn't respond to signals
     */
    public void terminateGracefully(Process process, TerminationConfig config)
            throws InterruptedException, TimeoutException {
        ProcessTerminationManager customManager = new ProcessTerminationManager(config);
        customManager.terminateGracefully(process);
    }

    /**
     * Checks if a process has terminated.
     *
     * @param process The process to check
     * @return true if the process has terminated, false otherwise
     */
    public boolean isProcessTerminated(Process process) {
        if (process == null) {
            return true;
        }
        try {
            process.exitValue();  // Throws IllegalThreadStateException if still running
            return true;
        } catch (IllegalThreadStateException e) {
            // Process is still running
            return false;
        }
    }

    /**
     * Gets the exit code of a process, or -1 if the process is still running.
     *
     * @param process The process to check
     * @return Exit code, or -1 if still running
     */
    public int getExitCode(Process process) {
        if (process == null) {
            return -1;
        }
        if (isProcessTerminated(process)) {
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException e) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Terminates a process with timeout wrapper for exception handling.
     * Returns the exit code on success, throws exception on failure.
     *
     * @param process The process to terminate
     * @param timeoutSeconds Timeout in seconds
     * @return The process exit code
     * @throws Exception If termination fails
     */
    public int terminateWithTimeout(Process process, int timeoutSeconds) throws Exception {
        try {
            // First, try to wait normally
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (completed) {
                return process.exitValue();
            }

            // If not completed, terminate gracefully
            log.warn("Process did not complete in " + timeoutSeconds + " seconds, terminating");
            terminateGracefully(process);
            return process.exitValue();

        } catch (TimeoutException e) {
            log.error("Failed to terminate process within timeout", e);
            throw new Exception("Process termination timeout after " + timeoutSeconds + " seconds", e);
        } catch (InterruptedException e) {
            log.error("Process termination interrupted", e);
            process.destroyForcibly();
            throw e;
        }
    }

    /**
     * Creates a human-readable status report for a process.
     *
     * @param process The process to report on
     * @param startTime When the process started (milliseconds since epoch)
     * @return Status report string
     */
    public String getProcessStatusReport(Process process, long startTime) {
        StringBuilder report = new StringBuilder();
        report.append("=== Process Status Report ===\n");
        report.append("Alive: ").append(!isProcessTerminated(process)).append("\n");
        report.append("Runtime: ").append(System.currentTimeMillis() - startTime).append(" ms\n");

        if (isProcessTerminated(process)) {
            report.append("Exit Code: ").append(getExitCode(process)).append("\n");
        } else {
            report.append("Exit Code: <process still running>\n");
        }

        return report.toString();
    }
}
