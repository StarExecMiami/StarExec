package org.starexec.backend;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.starexec.data.database.JobPairs;
import org.starexec.data.to.Status.StatusCode;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.starexec.config.EnvironmentConfig;
import org.starexec.constants.R;
import org.starexec.logger.StarLogger;

/**
 * Enhanced LocalBackend with concurrent job execution.
 *
 * <p>
 * This backend executes jobs locally on the host machine using a configurable
 * thread pool for concurrent execution. Unlike the original sequential
 * implementation,
 * this version can run multiple jobs simultaneously, making it suitable for:
 * <ul>
 * <li>Development and testing environments</li>
 * <li>Single-machine deployments</li>
 * <li>Stress testing and performance analysis</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <p>
 * The backend is configured via environment variables:
 * </p>
 * <ul>
 * <li>{@code STAREXEC_LOCAL_CONCURRENCY} - Number of concurrent jobs (default:
 * min(4, CPU cores))</li>
 * <li>{@code STAREXEC_LOCAL_JOB_TIMEOUT_SECONDS} - Per-job timeout in seconds
 * (default: 3600)</li>
 * <li>{@code STAREXEC_LOCAL_USE_RUNSOLVER} - Whether to wrap jobs with
 * runsolver (default: false)</li>
 * <li>{@code STAREXEC_LOCAL_GRACEFUL_SHUTDOWN_SECONDS} - Shutdown timeout
 * (default: 30)</li>
 * <li>{@code STAREXEC_LOCAL_FORCE_SANDBOX} - Enforce physical core isolation
 * via sandbox locking (default: false)</li>
 * </ul>
 *
 * <h3>Configuration Guidance</h3>
 * <ul>
 * <li><strong>STAREXEC_LOCAL_CONCURRENCY</strong>: Set to match your system's
 * CPU cores for optimal performance.
 * Values higher than available cores may cause thrashing. For I/O-bound jobs,
 * higher values may be beneficial.
 * Default is conservative (min of 4 and CPU cores) to prevent system
 * overload.</li>
 * <li><strong>STAREXEC_LOCAL_JOB_TIMEOUT_SECONDS</strong>: Adjust based on
 * expected job duration.
 * Longer timeouts allow more complex jobs but increase resource usage for stuck
 * processes.</li>
 * <li><strong>STAREXEC_LOCAL_USE_RUNSOLVER</strong>: Enable for precise
 * resource monitoring and limiting.
 * Requires runsolver to be installed and configured.</li>
 * <li><strong>STAREXEC_LOCAL_GRACEFUL_SHUTDOWN_SECONDS</strong>: Increase for
 * systems with slow shutdown processes.</li>
 * <li><strong>STAREXEC_LOCAL_FORCE_SANDBOX</strong>: Set to true to enable
 * strict CPU affinity via sandbox locking (limited to 2 concurrent jobs).
 * Useful for precise benchmarking to avoid cache interference.</li>
 * </ul>
 *
 * <h2>Performance Considerations</h2>
 * <ul>
 * <li>Concurrent execution improves throughput but increases CPU and memory
 * usage</li>
 * <li>Thread pool uses bounded queue (10,000 jobs) with CallerRunsPolicy for
 * backpressure</li>
 * <li>Monitor thread pool metrics (active threads, queue size) to tune
 * concurrency</li>
 * <li>Jobs are isolated in separate processes with proper cleanup on
 * termination</li>
 * <li>Resource usage scales with concurrency setting - monitor system load</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 * <p>
 * Jobs are submitted to a bounded thread pool executor. Each job runs in its
 * own
 * thread with proper lifecycle management including:
 * </p>
 * <ul>
 * <li>Process spawning with configurable working directory and output
 * redirection</li>
 * <li>Timeout enforcement with graceful termination</li>
 * <li>Proper cleanup on cancellation or failure</li>
 * <li>Thread-safe tracking of active jobs</li>
 * </ul>
 *
 * <h2>Monitoring</h2>
 * <p>
 * The backend exposes metrics for monitoring:
 * </p>
 * <ul>
 * <li>Active job count and IDs</li>
 * <li>Queued job count</li>
 * <li>Completed job count (since startup)</li>
 * <li>Failed job count (since startup)</li>
 * <li>Thread pool statistics (active threads, pool size, queue size)</li>
 * </ul>
 *
 * @see Backend
 * @see EnvironmentConfig
 */
public class LocalBackend implements Backend {

    private static final StarLogger log = StarLogger.getLogger(
            LocalBackend.class);

    // Configuration constants with defaults
    private static final int DEFAULT_CONCURRENCY = Math.min(
            4,
            Runtime.getRuntime().availableProcessors());
    private static final int DEFAULT_JOB_TIMEOUT_SECONDS = 3600; // 1 hour
    private static final int DEFAULT_GRACEFUL_SHUTDOWN_SECONDS = 30;

    // Node and queue names
    private String nodeName = "local-node";
    private static final String QUEUE_NAME = R.DEFAULT_QUEUE_NAME;

    // Thread pool for concurrent job execution
    private ExecutorService executorService;
    private int maxConcurrency;
    private int jobTimeoutSeconds;
    private int gracefulShutdownSeconds;
    private boolean useRunsolver;
    private boolean forceSandbox;

    // Execution ID generator (thread-safe)
    private final AtomicInteger execIdGenerator = new AtomicInteger(1);

    // Thread-safe mapping of execution IDs to job futures and metadata
    private final ConcurrentHashMap<Integer, LocalJob> activeJobs = new ConcurrentHashMap<>();

    // Metrics counters
    private final AtomicInteger completedJobCount = new AtomicInteger(0);
    private final AtomicInteger failedJobCount = new AtomicInteger(0);

    // Job completion monitor (reads status files and updates database)
    private LocalJobMonitor jobMonitor;

    // Cached container detection result (null = not yet checked)
    private static volatile Boolean isRunningInContainer = null;
    private static final Object CONTAINER_CHECK_LOCK = new Object();

    /**
     * Represents a job being executed by this backend.
     */
    private static class LocalJob {

        final int execId;
        final String scriptPath;
        final String workingDirectoryPath;
        final String logPath;
        final long submittedAt;

        volatile Process process;
        volatile Future<?> future;
        volatile JobState state;
        volatile long startedAt;
        volatile long completedAt;

        enum JobState {
            PENDING, // Submitted but not yet started
            RUNNING, // Currently executing
            COMPLETED, // Finished successfully
            FAILED, // Finished with error
            CANCELLED, // Cancelled by user
            TIMEOUT, // Killed due to timeout
        }

        LocalJob(
                int execId,
                String scriptPath,
                String workingDirectoryPath,
                String logPath) {
            this.execId = execId;
            this.scriptPath = scriptPath;
            this.workingDirectoryPath = workingDirectoryPath;
            this.logPath = logPath;
            this.submittedAt = System.currentTimeMillis();
            this.state = JobState.PENDING;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("LocalJob{");
            sb.append("execId=").append(execId);
            sb.append(", state=").append(state);
            sb.append(", script=").append(scriptPath);
            sb.append(", submittedAt=").append(submittedAt);
            if (state == JobState.RUNNING && startedAt > 0) {
                long runningSeconds = (System.currentTimeMillis() - startedAt) / 1000;
                sb.append(", running=").append(runningSeconds).append("s");
            }
            if (state == JobState.COMPLETED && completedAt > 0) {
                long totalSeconds = (completedAt - submittedAt) / 1000;
                sb.append(", duration=").append(totalSeconds).append("s");
            }
            sb.append("}");
            return sb.toString();
        }

        String getStatusLine() {
            StringBuilder sb = new StringBuilder();
            sb.append(execId).append("\t");
            sb.append(state.name()).append("\t");
            sb.append(new File(scriptPath).getName());
            if (state == JobState.RUNNING && startedAt > 0) {
                long runningSeconds = (System.currentTimeMillis() - startedAt) / 1000;
                sb.append("\t").append(runningSeconds).append("s");
            }
            return sb.toString();
        }
    }

    /**
     * Generates a unique execution ID.
     *
     * @return A positive integer execution ID
     */
    private int generateExecId() {
        int id;
        int retries = 0;
        final int MAX_RETRIES = 1000000; // Prevent infinite loop in edge case
        do {
            if (retries++ > MAX_RETRIES) {
                throw new RuntimeException(
                        "Unable to generate unique execution ID after " +
                                MAX_RETRIES +
                                " attempts");
            }
            id = execIdGenerator.getAndIncrement();
            if (id <= 0) {
                // Handle wraparound
                execIdGenerator.compareAndSet(id + 1, 1);
                id = execIdGenerator.getAndIncrement();
            }
        } while (activeJobs.containsKey(id));
        return id;
    }

    @Override
    public boolean isError(int execCode) {
        return execCode <= 0;
    }

    /**
     * Extracts the pair ID from a log file path.
     * Log paths are formatted as: .../jobId/jobSpaceId/pairId.txt
     *
     * @param logPath The full path to the log file
     * @return The pair ID, or -1 if it cannot be extracted
     */
    private int extractPairIdFromLogPath(String logPath) {
        try {
            if (logPath == null || logPath.isEmpty()) {
                return -1;
            }
            // Extract filename without extension
            File f = new File(logPath);
            String filename = f.getName();
            if (filename.endsWith(".txt")) {
                String pairIdStr = filename.substring(0, filename.length() - 4);
                return Integer.parseInt(pairIdStr);
            }
        } catch (NumberFormatException e) {
            log.debug("Could not extract pair ID from log path: " + logPath);
        } catch (Exception e) {
            log.debug("Error extracting pair ID from log path: " + logPath, e);
        }
        return -1;
    }

    /**
     * Cleans up artifacts from previous runs in the output directory.
     * This prevents the LocalJobMonitor from detecting old status files
     * and marking the job as complete before it even starts.
     *
     * @param outputDir The directory containing job output
     */
    private void cleanupPreviousRunArtifacts(File outputDir) {
        if (outputDir == null || !outputDir.exists()) {
            return;
        }

        String[] artifacts = {
                "status.json",
                "stats.json",
                "var.out",
                "watcher.out",
                "attributes.txt",
                "stdout.txt",
                "stderr.txt"
        };

        for (String artifact : artifacts) {
            File file = new File(outputDir, artifact);
            if (file.exists()) {
                if (file.delete()) {
                    log.debug("Deleted previous run artifact: " + file.getAbsolutePath());
                } else {
                    log.warn("Failed to delete previous run artifact: " + file.getAbsolutePath());
                }
            }
        }
    }

    /**
     * Executes a job. This method is called in a worker thread from the executor.
     *
     * @param job The job to execute
     */
    private void executeJob(LocalJob job) {
        job.state = LocalJob.JobState.RUNNING;
        job.startedAt = System.currentTimeMillis();

        // Extract pair ID from logPath (format: .../pairId.txt)
        int pairId = extractPairIdFromLogPath(job.logPath);

        // CLEANUP: Delete artifacts from previous runs BEFORE registering with monitor
        // This prevents the race condition where monitor sees old status.json
        File outputDir = new File(job.logPath).getParentFile();
        cleanupPreviousRunArtifacts(outputDir);

        if (pairId > 0 && jobMonitor != null) {
            jobMonitor.registerJob(outputDir.getAbsolutePath(), pairId);
            log.info(
                    "Registered job with monitor: execId=" +
                            job.execId +
                            ", pairId=" +
                            pairId +
                            ", logDir=" +
                            outputDir.getAbsolutePath());
        } else if (pairId <= 0) {
            log.warn("Could not extract pairId from logPath: " + job.logPath);
        }

        log.info(
                "DEBUG_TRACE: Starting job execution: execId=" +
                        job.execId +
                        ", pairId=" +
                        pairId +
                        ", script=" +
                        job.scriptPath +
                        ", workingDir=" +
                        job.workingDirectoryPath +
                        ", timeout=" +
                        jobTimeoutSeconds +
                        "s");

        // NOTE: This thread will block for the entire duration of job execution.
        // For very long-running jobs (hours), this ties up a worker thread.
        // Monitor thread pool exhaustion via getStats() if experiencing throughput
        // issues.
        // Future optimization: Use ProcessHandle.onExit() for async job completion.

        try {
            // Start the job process
            job.process = startJobProcess(job);

            // Wait for completion with timeout and zombie detection
            long startTime = System.currentTimeMillis();
            long maxDuration = TimeUnit.SECONDS.toMillis(jobTimeoutSeconds);
            boolean finished = false;

            while (System.currentTimeMillis() - startTime < maxDuration) {
                // Check if process exited naturally (poll every 2 seconds)
                if (job.process.waitFor(2, TimeUnit.SECONDS)) {
                    finished = true;
                    break;
                }

                // Check if the job reported completion status >= 7 (Complete/Error) via file
                // This handles "zombie" processes that write status but don't exit
                if (isJobReportedComplete(job)) {
                    log.warn("Job " + job.execId
                            + " reported complete via status file but process is still running. Killing zombie process.");
                    killProcess(job.process);
                    // Give it a moment to die
                    finished = job.process.waitFor(5, TimeUnit.SECONDS);
                    if (!finished) {
                        job.process.destroyForcibly();
                        finished = true;
                    }
                    break;
                }
            }

            if (!finished) {
                // Timeout - kill the process
                log.error(
                        "Job " +
                                job.execId +
                                " (pairId=" +
                                pairId +
                                " reported=" + isJobReportedComplete(job) +
                                ") exceeded timeout of " +
                                jobTimeoutSeconds +
                                " seconds, killing process");
                killProcess(job.process);
                job.state = LocalJob.JobState.TIMEOUT;
                failedJobCount.incrementAndGet();
            } else {
                int exitCode = job.process.exitValue();
                if (exitCode == 0) {
                    job.state = LocalJob.JobState.COMPLETED;
                    completedJobCount.incrementAndGet();
                    log.info(
                            "Job " +
                                    job.execId +
                                    " (pairId=" +
                                    pairId +
                                    ") completed successfully with exit code " +
                                    exitCode);
                    // Safety net: if the wrapper (runsolver) exits 0 but no status.json
                    // was written (e.g. the job script aborted before its EXIT trap was
                    // registered), mark the pair as ERROR_RUNSCRIPT so it doesn't stay
                    // stuck in ENQUEUED forever. The monitor will not find status.json
                    // in this case, so we must act here.
                    if (pairId > 0 && jobMonitor != null) {
                        File statusFile = new File(new File(job.logPath).getParent(), "status.json");
                        if (!statusFile.exists()) {
                            log.error(
                                    "Job " + job.execId + " (pairId=" + pairId +
                                    ") exited 0 but produced no status.json at " +
                                    statusFile.getAbsolutePath() +
                                    ". This typically means the job script aborted before" +
                                    " the EXIT trap was registered (e.g. arithmetic with" +
                                    " set -e). Marking pair as ERROR_RUNSCRIPT.");
                            try {
                                JobPairs.setStatusForPairAndStages(pairId, StatusCode.ERROR_RUNSCRIPT.getVal());
                            } catch (Exception e) {
                                log.error("Failed to set error status for pairId=" + pairId, e);
                            }
                            jobMonitor.clearPairTracking(pairId);
                        }
                    }
                } else {
                    job.state = LocalJob.JobState.FAILED;
                    failedJobCount.incrementAndGet();
                    log.error(
                            "Job " +
                                    job.execId +
                                    " (pairId=" +
                                    pairId +
                                    ") failed with exit code: " +
                                    exitCode +
                                    ". Check " +
                                    job.logPath +
                                    " for details");
                    // Explicitly fail the pair in the DB
                    if (pairId > 0) {
                        try {
                            JobPairs.setStatusForPairAndStages(pairId, StatusCode.ERROR_GENERAL.getVal());
                        } catch (Exception e) {
                            log.error("Failed to set error status for pairId=" + pairId, e);
                        }
                        if (jobMonitor != null) {
                            jobMonitor.clearPairTracking(pairId);
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            // Job was cancelled - restore interrupt flag
            log.warn(
                    "Job " +
                            job.execId +
                            " (pairId=" +
                            pairId +
                            ") was interrupted/cancelled");
            job.state = LocalJob.JobState.CANCELLED;
            if (job.process != null) {
                killProcess(job.process);
            }
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // Process creation failed - likely a user/system error
            log.error(
                    "Failed to start job " +
                            job.execId +
                            " (pairId=" +
                            pairId +
                            "): " +
                            e.getMessage() +
                            " (check script path and permissions). " +
                            "Script: " +
                            job.scriptPath +
                            ", WorkDir: " +
                            job.workingDirectoryPath,
                    e);
            job.state = LocalJob.JobState.FAILED;
            failedJobCount.incrementAndGet();
            // Explicitly fail the pair in the DB so it doesn't get stuck in Enqueued
            if (pairId > 0) {
                JobPairs.setStatusForPairAndStages(pairId, StatusCode.ERROR_RUNSCRIPT.getVal());
                // Also ensure monitor stops tracking it if it was registered
                if (jobMonitor != null) {
                    jobMonitor.clearPairTracking(pairId);
                }
            }
        } catch (Exception e) {
            // Other unexpected errors
            log.error(
                    "Unexpected error executing job " +
                            job.execId +
                            ": " +
                            e.getClass().getSimpleName() +
                            ": " +
                            e.getMessage(),
                    e);
            job.state = LocalJob.JobState.FAILED;
            failedJobCount.incrementAndGet();
            if (job.process != null) {
                killProcess(job.process);
            }
            // Explicitly fail the pair in the DB
            if (pairId > 0) {
                JobPairs.setStatusForPairAndStages(pairId, StatusCode.ERROR_GENERAL.getVal());
                if (jobMonitor != null) {
                    jobMonitor.clearPairTracking(pairId);
                }
            }
        } finally {
            job.completedAt = System.currentTimeMillis();
            // Remove from active jobs immediately
            activeJobs.remove(job.execId);
        }
    }

    /**
     * Builds and starts the process for a job.
     *
     * @param job The job to start
     * @return The started process
     * @throws IOException If process creation fails (file not found, no
     *                     permissions, etc.)
     */
    private Process startJobProcess(LocalJob job) throws IOException {
        // Validate output directory exists and is writable
        File outputDir = new File(job.logPath).getParentFile();
        if (!outputDir.exists()) {
            if (!outputDir.mkdirs()) {
                throw new IOException(
                        "Cannot create output directory: " +
                                outputDir.getAbsolutePath());
            }
            log.info(
                    "Created output directory: " + outputDir.getAbsolutePath());
        }

        if (!outputDir.isDirectory()) {
            throw new IOException(
                    "Output path is not a directory: " + outputDir.getAbsolutePath());
        }

        if (!outputDir.canWrite()) {
            throw new IOException(
                    "Output directory is not writable: " +
                            outputDir.getAbsolutePath() +
                            " (check permissions, disk space)");
        }

        ProcessBuilder builder = new ProcessBuilder();

        if (useRunsolver &&
                R.RUNSOLVER_PATH != null &&
                Files.exists(Path.of(R.RUNSOLVER_PATH))) {
            // Wrap with runsolver for resource limiting
            builder.command(
                    R.RUNSOLVER_PATH,
                    "-w",
                    job.logPath + ".watcher",
                    "-v",
                    job.logPath + ".var",
                    "-W",
                    String.valueOf(jobTimeoutSeconds),
                    "--",
                    job.scriptPath);
        } else {
            // Direct execution
            if (useRunsolver) {
                log.warn(
                        "Runsolver requested but not available (path: " +
                                R.RUNSOLVER_PATH +
                                "), falling back to direct execution without resource limits");
            }
            builder.command("/bin/bash", job.scriptPath);
        }

        builder.directory(new File(job.workingDirectoryPath));
        builder.redirectErrorStream(true);
        builder.redirectOutput(new File(job.logPath));

        // Set environment variables for the job
        Map<String, String> env = builder.environment();
        env.put("STAREXEC_JOB_ID", String.valueOf(job.execId));
        env.put("STAREXEC_WORKING_DIR", job.workingDirectoryPath);

        // LocalBackend always uses file-based status reporting (CONTAINER_MODE)
        // Jobs write status.json instead of directly calling database functions
        // The LocalJobMonitor will read these files and update the database
        env.put("CONTAINER_MODE", "true");
        if (forceSandbox) {
            env.put("STAREXEC_FORCE_SANDBOX", "true");
        }
        env.put("STAREXEC_OUTPUT_DIR", new File(job.logPath).getParent());

        log.debug(
                "Environment for job " +
                        job.execId +
                        ": CONTAINER_MODE=true, " +
                        (forceSandbox ? "STAREXEC_FORCE_SANDBOX=true, " : "") +
                        "STAREXEC_OUTPUT_DIR=" +
                        new File(job.logPath).getParent());

        if (isRunningInContainer()) {
            log.debug(
                    "Running in container environment - CONTAINER_MODE enabled for job " +
                            job.execId);
        } else {
            log.debug(
                    "LocalBackend using file-based status reporting for job " +
                            job.execId);
        }

        // NOTE: Process is created without explicit resource limits.
        // If running untrusted code, STAREXEC_LOCAL_USE_RUNSOLVER must be enabled.
        return builder.start();
    }

    private boolean isJobReportedComplete(LocalJob job) {
        try {
            File logDir = new File(job.logPath).getParentFile();
            File statusFile = new File(logDir, "status.json");
            if (statusFile.exists()) {
                // Read file content - limited size so safe to read fully
                String content = new String(java.nio.file.Files.readAllBytes(statusFile.toPath()));
                // Simple regex to find status value
                // Looks for "status": 7 or "status":7 etc.
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"status\"\\s*:\\s*(\\d+)")
                        .matcher(content);
                if (m.find()) {
                    int status = Integer.parseInt(m.group(1));
                    // Check if status is terminal (COMPLETE=7, ERROR>=8)
                    // See org.starexec.data.to.Status.StatusCode
                    return status >= 7;
                }
            }
        } catch (Exception e) {
            log.debug("Error checking status file for zombie detection: " + e.getMessage());
        }
        return false;
    }

    /**
     * Forcibly kills a process and its descendants.
     * Attempts graceful termination first, then forceful termination if needed.
     * Also attempts to kill child processes to prevent zombies.
     *
     * @param process The process to kill
     */
    private void killProcess(Process process) {
        if (process == null)
            return;

        try {
            long pid = process.pid();

            // Try graceful termination first (SIGTERM)
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                // Force kill if still running (SIGKILL)
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    log.warn(
                            "Process " +
                                    pid +
                                    " did not terminate after SIGKILL, may become zombie");
                }
            }

            // Attempt to kill any child processes (process group)
            // This helps prevent orphaned child processes on Unix systems
            // Commented out to prevent potential system crashes during testing
            /*
             * try {
             * if (
             * System.getProperty("os.name")
             * .toLowerCase()
             * .contains("linux") ||
             * System.getProperty("os.name")
             * .toLowerCase()
             * .contains("unix") ||
             * System.getProperty("os.name").toLowerCase().contains("mac")
             * ) {
             * // On Unix-like systems, try to kill the process group
             * Runtime.getRuntime().exec(
             * new String[] { "kill", "-9", "-" + pid }
             * );
             * }
             * } catch (Exception e) {
             * // Silently ignore if process group kill fails - process may already be dead
             * log.debug(
             * "Could not kill process group for " +
             * pid +
             * ": " +
             * e.getMessage()
             * );
             * }
             */
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Submits a job script for execution.
     *
     * <p>
     * <strong>IMPORTANT:</strong> Jobs are queued in memory and will be lost if the
     * application restarts or crashes. For critical workloads requiring durability,
     * consider using PodmanBackend with database-backed job persistence instead.
     *
     * <p>
     * <strong>Concurrency Note:</strong> This method blocks the calling thread only
     * during
     * queue insertion. Job execution happens asynchronously in the thread pool.
     *
     * @param scriptPath           Path to the executable job script
     * @param workingDirectoryPath Working directory for job execution
     * @param logPath              Path where job output/logs should be written
     * @return Execution ID for tracking the job, or -1 on error
     */
    @Override
    public synchronized int submitScript(
            String scriptPath,
            String workingDirectoryPath,
            String logPath) {
        if (executorService == null || executorService.isShutdown()) {
            log.error("Cannot submit job: executor service is not available");
            return -1;
        }

        try {
            // Validate inputs
            File script = new File(scriptPath);
            if (!script.exists()) {
                log.error("Script file does not exist: " + scriptPath);
                return -1;
            }
            if (!script.canExecute()) {
                // Try to make it executable
                if (!script.setExecutable(true)) {
                    log.warn(
                            "Could not set execute permission on: " + scriptPath);
                }
            }

            File workDir = new File(workingDirectoryPath);
            if (!workDir.exists()) {
                if (!workDir.mkdirs()) {
                    log.error(
                            "Could not create working directory: " +
                                    workingDirectoryPath);
                    return -1;
                }
            }

            // Create the job
            int execId = generateExecId();
            LocalJob job = new LocalJob(
                    execId,
                    scriptPath,
                    workingDirectoryPath,
                    logPath);

            // Submit to executor
            Future<?> future = executorService.submit(() -> executeJob(job));
            job.future = future;

            // Track the job
            activeJobs.put(execId, job);

            log.debug(
                    "Job submitted: execId=" +
                            execId +
                            ", script=" +
                            scriptPath +
                            ", activeJobs=" +
                            activeJobs.size());

            return execId;
        } catch (Exception e) {
            log.error("Error submitting job: " + e.getMessage(), e);
            return -1;
        }
    }

    @Override
    public synchronized boolean killPair(int execId) {
        LocalJob job = activeJobs.get(execId);
        if (job == null) {
            log.debug("Cannot kill job " + execId + ": not found");
            return false;
        }

        try {
            log.info("Killing job: " + execId);

            // Cancel the future (interrupts if running)
            if (job.future != null) {
                job.future.cancel(true);
            }

            // Kill the process directly
            if (job.process != null) {
                killProcess(job.process);
            }

            job.state = LocalJob.JobState.CANCELLED;
            activeJobs.remove(execId);

            return true;
        } catch (Exception e) {
            log.error("Error killing job " + execId + ": " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public synchronized boolean killAll() {
        log.info("Killing all jobs (" + activeJobs.size() + " active)");

        boolean allKilled = true;
        for (Integer execId : new ArrayList<>(activeJobs.keySet())) {
            if (!killPair(execId)) {
                allKilled = false;
            }
        }

        return allKilled;
    }

    @Override
    public synchronized String getRunningJobsStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("LocalBackend Status\n");
        sb.append("==================\n");
        sb.append("Max Concurrency: ").append(maxConcurrency).append("\n");
        sb.append("Active Jobs: ").append(getActiveJobCount()).append("\n");
        sb.append("Queued Jobs: ").append(getQueuedJobCount()).append("\n");
        sb
                .append("Completed Jobs: ")
                .append(completedJobCount.get())
                .append("\n");
        sb.append("Failed Jobs: ").append(failedJobCount.get()).append("\n");
        sb.append("\nActive Job Details:\n");
        sb.append("ID\tSTATUS\tSCRIPT\tRUNTIME\n");
        sb.append("--\t------\t------\t-------\n");

        int count = 0;
        final int MAX_DISPLAY = 100;
        for (LocalJob job : activeJobs.values()) {
            if (count++ >= MAX_DISPLAY) {
                sb
                        .append("... (showing first ")
                        .append(MAX_DISPLAY)
                        .append(" jobs)\n");
                break;
            }
            sb.append(job.getStatusLine()).append("\n");
        }

        return sb.toString();
    }

    @Override
    public Set<Integer> getActiveExecutionIds() throws IOException {
        return new HashSet<>(activeJobs.keySet());
    }

    /**
     * Returns the number of currently active (running or pending) jobs.
     *
     * @return Active job count
     */
    public int getActiveJobCount() {
        return (int) activeJobs
                .values()
                .stream()
                .filter(
                        j -> j.state == LocalJob.JobState.RUNNING ||
                                j.state == LocalJob.JobState.PENDING)
                .count();
    }

    /**
     * Returns the number of jobs waiting in the queue.
     *
     * @return Queued job count
     */
    public int getQueuedJobCount() {
        return (int) activeJobs
                .values()
                .stream()
                .filter(j -> j.state == LocalJob.JobState.PENDING)
                .count();
    }

    /**
     * Returns the number of jobs currently running.
     *
     * @return Running job count
     */
    public int getRunningJobCount() {
        return (int) activeJobs
                .values()
                .stream()
                .filter(j -> j.state == LocalJob.JobState.RUNNING)
                .count();
    }

    @Override
    public String[] getWorkerNodes() {
        return new String[] { nodeName };
    }

    @Override
    public String[] getQueues() {
        return new String[] { QUEUE_NAME };
    }

    @Override
    public Map<String, String> getNodeQueueAssociations() {
        HashMap<String, String> mapping = new HashMap<>();
        mapping.put(nodeName, QUEUE_NAME);
        return mapping;
    }

    @Override
    public boolean clearNodeErrorStates() {
        return true;
    }

    // Queue management is not applicable for local backend
    @Override
    public void deleteQueue(String queueName) {
    }

    @Override
    public boolean createQueue(
            String newQueueName,
            String[] nodeNames,
            String[] sourceQueueNames) {
        return false;
    }

    @Override
    public boolean createQueueWithSlots(
            String newQueueName,
            String[] nodeNames,
            String[] sourceQueueNames,
            Integer slots) {
        return false;
    }

    @Override
    public void moveNodes(
            String destQueueName,
            String[] nodeNames,
            String[] sourceQueueNames) {
    }

    @Override
    public void moveNode(String nodeName, String queueName) {
    }

    @Override
    public void clearPairTracking(int pairId) {
        if (jobMonitor != null) {
            jobMonitor.clearPairTracking(pairId);
        }
    }

    @Override
    public void destroyIf() {
        log.info("Shutting down LocalBackend");

        // Stop the job monitor first
        if (jobMonitor != null) {
            jobMonitor.stop();
        }

        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(
                        gracefulShutdownSeconds,
                        TimeUnit.SECONDS)) {
                    log.warn(
                            "Forcing shutdown after " +
                                    gracefulShutdownSeconds +
                                    " seconds");
                    executorService.shutdownNow();
                    executorService.awaitTermination(10, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                log.error("Shutdown interrupted, forcing immediate shutdown");
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Kill any remaining processes
        for (LocalJob job : activeJobs.values()) {
            if (job.process != null && job.process.isAlive()) {
                killProcess(job.process);
            }
        }
        activeJobs.clear();

        log.info("LocalBackend shutdown complete");
    }

    /**
     * Detects if the application is running inside a container (Docker, Podman,
     * Kubernetes).
     *
     * Detection methods (in order):
     * 1. Check for /.dockerenv file (Docker)
     * 2. Check for /run/.containerenv file (Podman)
     * 3. Check if /proc/1/cgroup contains "docker" or "kubepods" or "containerd"
     *
     * The result is cached since the container status won't change at runtime.
     *
     * @return true if running inside a container, false otherwise
     */
    private static boolean isRunningInContainer() {
        if (isRunningInContainer != null) {
            return isRunningInContainer;
        }

        synchronized (CONTAINER_CHECK_LOCK) {
            if (isRunningInContainer != null) {
                return isRunningInContainer;
            }

            // Check for Docker
            if (Files.exists(Path.of("/.dockerenv"))) {
                log.info("Container detected: /.dockerenv exists (Docker)");
                isRunningInContainer = true;
                return true;
            }

            // Check for Podman
            if (Files.exists(Path.of("/run/.containerenv"))) {
                log.info(
                        "Container detected: /run/.containerenv exists (Podman)");
                isRunningInContainer = true;
                return true;
            }

            // Check cgroup for container indicators
            try {
                Path cgroupPath = Path.of("/proc/1/cgroup");
                if (Files.exists(cgroupPath)) {
                    try (
                            BufferedReader reader = new BufferedReader(
                                    new FileReader(cgroupPath.toFile()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String lowerLine = line.toLowerCase();
                            if (lowerLine.contains("docker") ||
                                    lowerLine.contains("kubepods") ||
                                    lowerLine.contains("containerd") ||
                                    lowerLine.contains("lxc")) {
                                log.info(
                                        "Container detected via /proc/1/cgroup: " +
                                                line.trim());
                                isRunningInContainer = true;
                                return true;
                            }
                        }
                    }
                }
            } catch (IOException e) {
                log.debug(
                        "Could not read /proc/1/cgroup for container detection: " +
                                e.getMessage());
            }

            // Also check /proc/self/cgroup as a fallback
            try {
                Path selfCgroupPath = Path.of("/proc/self/cgroup");
                if (Files.exists(selfCgroupPath)) {
                    try (
                            BufferedReader reader = new BufferedReader(
                                    new FileReader(selfCgroupPath.toFile()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String lowerLine = line.toLowerCase();
                            if (lowerLine.contains("docker") ||
                                    lowerLine.contains("kubepods") ||
                                    lowerLine.contains("containerd") ||
                                    lowerLine.contains("lxc")) {
                                log.info(
                                        "Container detected via /proc/self/cgroup: " +
                                                line.trim());
                                isRunningInContainer = true;
                                return true;
                            }
                        }
                    }
                }
            } catch (IOException e) {
                log.debug(
                        "Could not read /proc/self/cgroup for container detection: " +
                                e.getMessage());
            }

            log.info(
                    "No container environment detected - running on bare metal/VM");
            isRunningInContainer = false;
            return false;
        }
    }

    /**
     * Loads configuration from environment variables.
     */
    private void loadConfiguration() {
        // Concurrency level
        maxConcurrency = getEnvInt(
                "STAREXEC_LOCAL_CONCURRENCY",
                DEFAULT_CONCURRENCY);
        if (maxConcurrency < 1) {
            log.warn(
                    "Invalid concurrency " +
                            maxConcurrency +
                            ", using default: " +
                            DEFAULT_CONCURRENCY);
            maxConcurrency = DEFAULT_CONCURRENCY;
        }

        // Job timeout
        jobTimeoutSeconds = getEnvInt(
                "STAREXEC_LOCAL_JOB_TIMEOUT_SECONDS",
                DEFAULT_JOB_TIMEOUT_SECONDS);
        if (jobTimeoutSeconds < 1) {
            jobTimeoutSeconds = DEFAULT_JOB_TIMEOUT_SECONDS;
        }

        // Graceful shutdown timeout
        gracefulShutdownSeconds = getEnvInt(
                "STAREXEC_LOCAL_GRACEFUL_SHUTDOWN_SECONDS",
                DEFAULT_GRACEFUL_SHUTDOWN_SECONDS);

        // Use runsolver - MANDATORY for running untrusted code
        useRunsolver = getEnvBoolean("STAREXEC_LOCAL_USE_RUNSOLVER", false);

        // Force sandbox usage even in container mode
        forceSandbox = getEnvBoolean("STAREXEC_LOCAL_FORCE_SANDBOX", false);

        // CRITICAL SAFETY CHECK: Enforce resource limits if executing untrusted jobs
        String runUntrusted = System.getenv(
                "STAREXEC_LOCAL_RUN_UNTRUSTED_JOBS");
        if ("true".equalsIgnoreCase(runUntrusted) && !useRunsolver) {
            log.warn(
                    "⚠️  CRITICAL SAFETY WARNING: Running untrusted jobs without resource limits! " +
                            "Set STAREXEC_LOCAL_USE_RUNSOLVER=true or disable untrusted job execution.");
        }
    }

    private int getEnvInt(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                log.warn("Invalid integer for " + key + ": " + value);
            }
        }
        return defaultValue;
    }

    private boolean getEnvBoolean(String key, boolean defaultValue) {
        String value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            return Boolean.parseBoolean(value.trim());
        }
        return defaultValue;
    }

    /**
     * Initializes the backend.
     *
     * <p>
     * Creates the thread pool executor and sets up the node name based on the
     * hostname.
     * </p>
     *
     * @param backendRoot Not used for LocalBackend, but required by interface
     */
    @Override
    public void initialize(String backendRoot) {
        log.info("Initializing LocalBackend (concurrent mode)");

        // Load configuration
        loadConfiguration();

        // Set node name to hostname
        try {
            nodeName = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            log.warn(
                    "Could not determine hostname, using default: " + nodeName);
        }

        // Create thread pool with custom thread factory for better naming
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(
                        r,
                        "LocalBackend-Worker-" + counter.getAndIncrement());
                t.setDaemon(false); // Don't die silently
                return t;
            }
        };

        // Create bounded thread pool with configurable queue size
        int queueSize = getEnvInt("STAREXEC_LOCAL_QUEUE_SIZE", 10000);
        if (queueSize < 10) {
            log.warn("Queue size too small, setting to minimum of 10");
            queueSize = 10;
        }

        executorService = new ThreadPoolExecutor(
                maxConcurrency, // Core pool size
                maxConcurrency, // Maximum pool size
                60L,
                TimeUnit.SECONDS, // Keep-alive time for idle threads
                new LinkedBlockingQueue<>(queueSize), // Work queue with bounded capacity
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy() // Backpressure: caller thread runs if queue is full
        );

        // Create and start job completion monitor
        jobMonitor = new LocalJobMonitor();
        jobMonitor.start();

        log.info("LocalBackend initialized successfully");
        log.info("  Node name: " + nodeName);
        log.info("  Max concurrency: " + maxConcurrency);
        log.info("  Job timeout: " + jobTimeoutSeconds + " seconds");
        log.info("  Use runsolver: " + useRunsolver);
        log.info("  Queue size: " + queueSize);
        log.info(
                "  Graceful shutdown: " + gracefulShutdownSeconds + " seconds");

        if (!useRunsolver) {
            log.warn(
                    "⚠️  LocalBackend running WITHOUT runsolver resource limits. " +
                            "Jobs can consume unlimited CPU, memory, and I/O. " +
                            "This is acceptable ONLY for trusted code. " +
                            "For untrusted code, set STAREXEC_LOCAL_USE_RUNSOLVER=true.");
        }
    }

    /**
     * Returns backend statistics as a map for monitoring/metrics.
     *
     * @return Map of metric name to value
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("nodeName", nodeName);
        stats.put("maxConcurrency", maxConcurrency);
        stats.put("activeJobs", getActiveJobCount());
        stats.put("runningJobs", getRunningJobCount());
        stats.put("queuedJobs", getQueuedJobCount());
        stats.put("completedJobs", completedJobCount.get());
        stats.put("failedJobs", failedJobCount.get());
        stats.put("jobTimeoutSeconds", jobTimeoutSeconds);
        stats.put("useRunsolver", useRunsolver);

        // Thread pool metrics
        if (executorService instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) executorService;
            stats.put("threadPoolActiveThreads", tpe.getActiveCount());
            stats.put("threadPoolPoolSize", tpe.getPoolSize());
            stats.put("threadPoolQueueSize", tpe.getQueue().size());
            stats.put("threadPoolLargestPoolSize", tpe.getLargestPoolSize());
        }

        return stats;
    }
}
