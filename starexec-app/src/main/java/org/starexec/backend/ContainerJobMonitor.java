package org.starexec.backend;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.starexec.data.database.JobPairs;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.logger.StarLogger;

/**
 * Monitors completed container jobs and updates the database with results.
 *
 * <p>This class is designed to work with the containerized job execution model
 * where jobs write their results to files instead of directly updating the DB.
 * The monitor periodically checks for completed containers and processes their
 * output files.</p>
 *
 * <h2>Adaptive Polling</h2>
 * <p>This monitor uses adaptive polling to reduce CPU usage during idle periods:</p>
 * <ul>
 *   <li>Starts at a base interval (default: 1 second) for responsive job detection</li>
 *   <li>Backs off to longer intervals when no jobs are completing</li>
 *   <li>Immediately resets to base interval when new containers are submitted</li>
 * </ul>
 *
 * <h2>Output File Format</h2>
 * <p>Jobs are expected to write the following files:</p>
 * <ul>
 *   <li>{@code var.out} - Runsolver variable output (CPU time, wallclock, memory)</li>
 *   <li>{@code watcher.out} - Runsolver watcher output (exit code, resource usage)</li>
 *   <li>{@code attributes.txt} - Post-processor output (key=value pairs)</li>
 *   <li>{@code stdout.txt} - Solver stdout</li>
 *   <li>{@code status.json} - Job completion status (optional)</li>
 * </ul>
 *
 * @see PodmanBackend
 * @see AdaptivePollInterval
 */
public class ContainerJobMonitor {

    private static final StarLogger log = StarLogger.getLogger(
        ContainerJobMonitor.class
    );

    // Executor for async processing
    private final ScheduledExecutorService scheduler;
    private final PodmanBackend backend;
    private volatile boolean running = false;

    // Adaptive polling interval manager
    private final AdaptivePollInterval pollInterval;

    // Handle for the currently scheduled poll (for cancellation on interval change)
    private volatile ScheduledFuture<?> scheduledPoll;

    public ContainerJobMonitor(PodmanBackend backend) {
        this.backend = backend;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ContainerJobMonitor");
            t.setDaemon(true);
            return t;
        });
        this.pollInterval = new AdaptivePollInterval("ContainerJobMonitor");
    }

    /**
     * Starts the monitor with adaptive polling.
     */
    public void start() {
        if (running) {
            log.warn("ContainerJobMonitor already running");
            return;
        }
        running = true;

        // Schedule the first poll
        scheduleNextPoll();

        log.info(
            "ContainerJobMonitor started with adaptive polling: " +
                pollInterval.getStats()
        );
    }

    /**
     * Schedules the next poll based on the current adaptive interval.
     */
    private void scheduleNextPoll() {
        if (!running) {
            return;
        }

        long interval = pollInterval.getCurrentInterval();
        scheduledPoll = scheduler.schedule(
            this::pollAndReschedule,
            interval,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Executes a poll and schedules the next one.
     * This method is called by the scheduler.
     */
    private void pollAndReschedule() {
        try {
            checkCompletedJobs();
        } finally {
            // Always reschedule, even if an exception occurred
            if (running) {
                scheduleNextPoll();
            }
        }
    }

    /**
     * Notifies the monitor that new work has been submitted.
     * Resets the poll interval to base and immediately reschedules the poll
     * for responsive detection.
     *
     * <p>This method cancels any pending poll that may be sleeping at a backed-off
     * interval (up to MAX_INTERVAL) and immediately schedules a new poll at the
     * base interval. This ensures that newly submitted work is detected within
     * BASE_INTERVAL milliseconds, not up to MAX_INTERVAL milliseconds.</p>
     *
     * <p>Call this from PodmanBackend when submitting new containers.</p>
     */
    public void notifyNewWorkSubmitted() {
        pollInterval.resetToBase();

        // Cancel the current scheduled poll and reschedule immediately
        // This avoids the "latency trap" where we're sleeping at MAX_INTERVAL
        // but new work has arrived and should be detected quickly.
        cancelCurrentPollAndReschedule();

        log.debug(
            "ContainerJobMonitor: Reset to base interval and rescheduled due to new work submission"
        );
    }

    /**
     * Cancels the currently scheduled poll (if any) and immediately schedules
     * a new poll at the current interval.
     *
     * <p>This is used to "wake up" the monitor when new work is submitted while
     * the monitor is sleeping at a backed-off interval.</p>
     *
     * <p>Thread safety: This method may be called from HTTP request threads while
     * the scheduler thread is executing pollAndReschedule(). The worst case is
     * a harmless duplicate poll (the cancelled poll's finally block schedules
     * another poll, and we also schedule one here). The ConcurrentHashMap-based
     * tracking handles concurrent access correctly.</p>
     */
    private void cancelCurrentPollAndReschedule() {
        if (!running) {
            return;
        }

        ScheduledFuture<?> currentPoll = scheduledPoll;
        if (currentPoll != null) {
            // Cancel with mayInterruptIfRunning=false - don't interrupt if
            // the poll is currently executing, just prevent it from running
            // if it hasn't started yet.
            currentPoll.cancel(false);
        }

        // Immediately schedule a new poll at the (now reset) base interval
        scheduleNextPoll();
    }

    /**
     * Stops the monitor.
     */
    public void stop() {
        running = false;

        // Cancel any pending poll
        if (scheduledPoll != null) {
            scheduledPoll.cancel(false);
        }

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info(
            "ContainerJobMonitor stopped. Final stats: " +
                pollInterval.getStats()
        );
    }

    /**
     * Checks for completed containers and processes their results.
     */
    private void checkCompletedJobs() {
        try {
            // Get all completed containers that haven't been processed
            List<PodmanBackend.CompletedContainerInfo> completedJobs =
                backend.getCompletedContainers();

            int processedCount = 0;

            for (PodmanBackend.CompletedContainerInfo info : completedJobs) {
                try {
                    processCompletedJob(info);
                    // Remove container after successful processing
                    backend.removeCompletedContainer(info.containerId);
                    processedCount++;
                } catch (Exception e) {
                    log.error(
                        "Error processing completed job " + info.pairId,
                        e
                    );
                    // Mark as error so we don't keep retrying
                    try {
                        JobPairs.setStatusForPairAndStages(
                            info.pairId,
                            StatusCode.ERROR_RUNSCRIPT.getVal()
                        );
                        // Still remove the container to avoid infinite loop
                        backend.removeCompletedContainer(info.containerId);
                    } catch (Exception ex) {
                        log.error(
                            "Failed to set error status for pair " +
                                info.pairId,
                            ex
                        );
                    }
                }
            }

            // Update adaptive polling based on work found
            if (processedCount > 0) {
                pollInterval.recordWorkFound(processedCount);
                log.debug(
                    "ContainerJobMonitor: Processed " +
                        processedCount +
                        " completed jobs. " +
                        pollInterval.getStats()
                );
            } else {
                pollInterval.recordIdle();
                log.trace(
                    "ContainerJobMonitor: No completed jobs found. " +
                        pollInterval.getStats()
                );
            }
        } catch (Exception e) {
            log.error("Error in checkCompletedJobs", e);
            // Still record as idle to allow backoff even on errors
            pollInterval.recordIdle();
        }
    }

    /**
     * Processes a completed job by reading output files and updating the database.
     */
    private void processCompletedJob(PodmanBackend.CompletedContainerInfo info)
        throws Exception {
        Path outputPath = Paths.get(info.outputDir);

        // First, try to get the actual pair ID from status.json
        // This is more reliable than the container label which may have timestamp
        int pairId = info.pairId;
        Path statusJson = outputPath.resolve("status.json");
        if (Files.exists(statusJson)) {
            try {
                String json = Files.readString(statusJson);
                Matcher m = Pattern.compile(
                    "\"pairId\"\\s*:\\s*(\\d+)"
                ).matcher(json);
                if (m.find()) {
                    pairId = Integer.parseInt(m.group(1));
                    log.debug("Extracted pairId from status.json: " + pairId);
                }
            } catch (Exception e) {
                log.warn(
                    "Failed to read pairId from status.json, using label value: " +
                        info.pairId,
                    e
                );
            }
        }

        log.info(
            "Processing completed job: pairId=" +
                pairId +
                ", containerId=" +
                info.containerId.substring(
                    0,
                    Math.min(12, info.containerId.length())
                ) +
                ", outputDir=" +
                info.outputDir +
                ", exitCode=" +
                info.exitCode
        );

        // 1. Parse runsolver output (var.out)
        RunsolverStats stats = parseRunsolverOutput(outputPath);
        stats.exitCode = info.exitCode; // Use container exit code as fallback

        // 2. Determine job status from stats
        StatusCode status = determineStatus(stats, outputPath);

        // 3. Parse attributes if post-processor ran
        Properties attributes = parseAttributes(outputPath);

        // 4. Update database
        updateDatabase(pairId, stats, status, attributes);

        log.info("Completed job " + pairId + " processed: status=" + status);
    }

    /**
     * Parses runsolver var.out and watcher.out files.
     * Also checks for stats.json as an alternative format.
     */
    private RunsolverStats parseRunsolverOutput(Path outputDir) {
        RunsolverStats stats = new RunsolverStats();

        // Try stats.json first (written by functions.bash in container mode)
        Path statsJson = outputDir.resolve("stats.json");
        if (Files.exists(statsJson)) {
            try {
                String json = Files.readString(statsJson);
                parseStatsJson(json, stats);
                log.debug("Parsed stats from stats.json");
                return stats;
            } catch (IOException e) {
                log.warn(
                    "Failed to parse stats.json, falling back to var.out",
                    e
                );
            }
        }

        // Parse var.out
        Path varFile = outputDir.resolve("var.out");
        if (Files.exists(varFile)) {
            try {
                List<String> lines = Files.readAllLines(varFile);
                for (String line : lines) {
                    parseVarLine(line, stats);
                }
            } catch (IOException e) {
                log.warn("Failed to parse var.out", e);
            }
        }

        // Parse watcher.out
        Path watcherFile = outputDir.resolve("watcher.out");
        if (Files.exists(watcherFile)) {
            try {
                List<String> lines = Files.readAllLines(watcherFile);
                for (String line : lines) {
                    parseWatcherLine(line, stats);
                }
            } catch (IOException e) {
                log.warn("Failed to parse watcher.out", e);
            }
        }

        return stats;
    }

    /**
     * Parses stats.json (written by functions.bash in container mode).
     * Simple JSON parsing without external dependencies.
     */
    private void parseStatsJson(String json, RunsolverStats stats) {
        // Simple regex-based JSON parsing
        Matcher m;
        if (
            (m = Pattern.compile("\"wallclockTime\"\\s*:\\s*([0-9.]+)").matcher(
                    json
                )).find()
        ) {
            stats.wallclockTime = Double.parseDouble(m.group(1));
        }
        if (
            (m = Pattern.compile("\"cpuTime\"\\s*:\\s*([0-9.]+)").matcher(
                    json
                )).find()
        ) {
            stats.cpuTime = Double.parseDouble(m.group(1));
        }
        if (
            (m = Pattern.compile("\"userTime\"\\s*:\\s*([0-9.]+)").matcher(
                    json
                )).find()
        ) {
            stats.userTime = Double.parseDouble(m.group(1));
        }
        if (
            (m = Pattern.compile("\"systemTime\"\\s*:\\s*([0-9.]+)").matcher(
                    json
                )).find()
        ) {
            stats.systemTime = Double.parseDouble(m.group(1));
        }
        if (
            (m = Pattern.compile(
                    "\"maxVirtualMemory\"\\s*:\\s*([0-9.]+)"
                ).matcher(json)).find()
        ) {
            stats.maxVirtualMemory = Double.parseDouble(m.group(1));
        }
        if (
            (m = Pattern.compile(
                    "\"maxResidentSetSize\"\\s*:\\s*([0-9]+)"
                ).matcher(json)).find()
        ) {
            stats.maxResidentSetSize = Long.parseLong(m.group(1));
        }

        // Optional: stage number reported by containerized execution (int)
        if (
            (m = Pattern.compile("\"stageNumber\"\\s*:\\s*([0-9]+)").matcher(
                    json
                )).find()
        ) {
            stats.stageNumber = Integer.parseInt(m.group(1));
        }

        // Optional: disk size used (may be reported in bytes)
        if (
            (m = Pattern.compile("\"diskSize\"\\s*:\\s*([0-9]+)").matcher(
                    json
                )).find()
        ) {
            try {
                stats.diskSize = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                // ignore and leave default
            }
        }

        // Optional: hostname of the execution host/container
        if (
            (m = Pattern.compile("\"hostname\"\\s*:\\s*\"([^\"]+)\"").matcher(
                    json
                )).find()
        ) {
            stats.hostname = m.group(1);
        }
    }

    /**
     * Parses a line from var.out (runsolver format).
     */
    private void parseVarLine(String line, RunsolverStats stats) {
        // Format: KEY=value
        Matcher m;

        if (
            (m = Pattern.compile("^WCTIME=([0-9.]+)$").matcher(line)).matches()
        ) {
            stats.wallclockTime = Double.parseDouble(m.group(1));
        } else if (
            (m = Pattern.compile("^CPUTIME=([0-9.]+)$").matcher(line)).matches()
        ) {
            stats.cpuTime = Double.parseDouble(m.group(1));
        } else if (
            (m = Pattern.compile("^USERTIME=([0-9.]+)$").matcher(
                    line
                )).matches()
        ) {
            stats.userTime = Double.parseDouble(m.group(1));
        } else if (
            (m = Pattern.compile("^SYSTEMTIME=([0-9.]+)$").matcher(
                    line
                )).matches()
        ) {
            stats.systemTime = Double.parseDouble(m.group(1));
        } else if (
            (m = Pattern.compile("^MAXVM=([0-9.]+)$").matcher(line)).matches()
        ) {
            stats.maxVirtualMemory = Double.parseDouble(m.group(1));
        }
    }

    /**
     * Parses a line from watcher.out (runsolver format).
     */
    private void parseWatcherLine(String line, RunsolverStats stats) {
        Matcher m;

        if (
            (m = Pattern.compile("Child status: (\\d+)").matcher(line)).find()
        ) {
            stats.exitCode = Integer.parseInt(m.group(1));
        } else if (
            (m = Pattern.compile("maximum resident set size: (\\d+)").matcher(
                    line
                )).find()
        ) {
            stats.maxResidentSetSize = Long.parseLong(m.group(1));
        } else if (line.contains("wall clock time exceeded")) {
            stats.wallclockExceeded = true;
        } else if (line.contains("CPU time exceeded")) {
            stats.cpuExceeded = true;
        } else if (line.contains("VSize exceeded")) {
            stats.memoryExceeded = true;
        }
    }

    /**
     * Determines the job status based on runsolver stats and output files.
     */
    private StatusCode determineStatus(RunsolverStats stats, Path outputDir) {
        if (stats.wallclockExceeded) {
            return StatusCode.EXCEED_RUNTIME;
        } else if (stats.cpuExceeded) {
            return StatusCode.EXCEED_CPU;
        } else if (stats.memoryExceeded) {
            return StatusCode.EXCEED_MEM;
        } else if (stats.exitCode != 0) {
            // Check if var.out exists - if not, likely runscript error
            if (!Files.exists(outputDir.resolve("var.out"))) {
                return StatusCode.ERROR_RUNSCRIPT;
            }
        }
        return StatusCode.STATUS_COMPLETE;
    }

    /**
     * Parses post-processor attributes from attributes.txt.
     */
    private Properties parseAttributes(Path outputDir) {
        Properties props = new Properties();
        Path attrsFile = outputDir.resolve("attributes.txt");

        if (Files.exists(attrsFile)) {
            try (BufferedReader reader = Files.newBufferedReader(attrsFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String key = line.substring(0, eq).trim();
                        String value = line.substring(eq + 1).trim();
                        if (!key.isEmpty() && !value.isEmpty()) {
                            props.setProperty(key, value);
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to parse attributes.txt", e);
            }
        }

        return props;
    }

    /**
     * Updates the database with job results.
     */
    private void updateDatabase(
        int pairId,
        RunsolverStats stats,
        StatusCode status,
        Properties attributes
    ) throws Exception {
        // Update pair status
        JobPairs.setStatusForPairAndStages(pairId, status.getVal());

        // Persist run stats (if available) using JobPairs.updateRunSolverStats
        try {
            String nodeName = (stats.hostname != null &&
                    !stats.hostname.isEmpty())
                ? stats.hostname
                : "unknown";
            boolean ok = JobPairs.updateRunSolverStats(
                pairId,
                nodeName,
                stats.wallclockTime,
                stats.cpuTime,
                stats.userTime,
                stats.systemTime,
                stats.maxVirtualMemory,
                stats.maxResidentSetSize,
                stats.stageNumber,
                stats.diskSize
            );
            if (ok) {
                log.debug(
                    "Persisted run stats for pair " + pairId + ": " + stats
                );
            } else {
                log.warn("Failed to persist run stats for pair " + pairId);
            }
        } catch (Exception e) {
            log.warn("Exception persisting run stats for pair " + pairId, e);
        }

        // Update attributes if any
        if (!attributes.isEmpty()) {
            // Stage 1 for now - multi-stage pipelines would need enhancement
            JobPairs.addJobPairAttributes(pairId, 1, attributes);
        }

        log.debug(
            "Updated database for pair " +
                pairId +
                ": status=" +
                status +
                ", attrs=" +
                attributes.size()
        );
    }

    /**
     * Returns the current poll interval statistics.
     *
     * @return Statistics string for monitoring
     */
    public String getStats() {
        return pollInterval.getStats();
    }

    /**
     * Container for runsolver statistics.
     */
    public static class RunsolverStats {

        public double wallclockTime = 0;
        public double cpuTime = 0;
        public double userTime = 0;
        public double systemTime = 0;
        public double maxVirtualMemory = 0;
        public long maxResidentSetSize = 0;
        public long diskSize = 0;
        public int stageNumber = 1;
        public int exitCode = 0;
        public boolean wallclockExceeded = false;
        public boolean cpuExceeded = false;
        public boolean memoryExceeded = false;
        public String hostname = null;

        @Override
        public String toString() {
            return String.format(
                "RunsolverStats{wall=%.2f, cpu=%.2f, mem=%.0f, rss=%d, disk=%d, stage=%d, exit=%d, " +
                    "wallExceeded=%b, cpuExceeded=%b, memExceeded=%b, host=%s}",
                wallclockTime,
                cpuTime,
                maxVirtualMemory,
                maxResidentSetSize,
                diskSize,
                stageNumber,
                exitCode,
                wallclockExceeded,
                cpuExceeded,
                memoryExceeded,
                hostname == null ? "unknown" : hostname
            );
        }
    }
}
