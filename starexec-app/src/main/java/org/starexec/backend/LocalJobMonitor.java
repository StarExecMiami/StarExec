package org.starexec.backend;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.starexec.constants.R;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.logger.StarLogger;

/**
 * Monitors completed local jobs and updates the database with results.
 *
 * <p>
 * This class is designed to work with the LocalBackend job execution model
 * where jobs write their results to files (status.json, stats.json) instead of
 * directly updating the database. The monitor periodically checks for completed
 * jobs and processes their output files.
 * </p>
 *
 * <h2>Adaptive Polling</h2>
 * <p>
 * This monitor uses adaptive polling to reduce CPU usage during idle periods:
 * </p>
 * <ul>
 * <li>Starts at a base interval (default: 1 second) for responsive job
 * detection</li>
 * <li>Backs off to longer intervals when no jobs are completing</li>
 * <li>Immediately resets to base interval when new jobs are registered</li>
 * </ul>
 *
 * <h2>Output File Format</h2>
 * <p>
 * Jobs are expected to write the following files:
 * </p>
 * <ul>
 * <li>{@code status.json} - Job completion status with pairId and status
 * code</li>
 * <li>{@code stats.json} - Job statistics (CPU time, wallclock, memory)</li>
 * <li>{@code attributes.txt} - Post-processor output (key=value pairs)</li>
 * </ul>
 *
 * @see LocalBackend
 * @see AdaptivePollInterval
 */
public class LocalJobMonitor {

    private static final StarLogger log = StarLogger.getLogger(
            LocalJobMonitor.class);

    // Executor for async processing
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    // Adaptive polling interval manager
    private final AdaptivePollInterval pollInterval;

    // Handle for the currently scheduled poll (for cancellation on interval change)
    private volatile ScheduledFuture<?> scheduledPoll;

    // Maps output directories to pair IDs so we can track which jobs we've seen
    private final ConcurrentHashMap<String, Integer> trackedPairs = new ConcurrentHashMap<>();
    private final Set<Integer> processedPairIds = ConcurrentHashMap.newKeySet();

    // Consecutive polls on which status.json could not be parsed for a pair. The file is
    // written in place, so a read can land mid-write; that is transient and must not be
    // recorded as a failed run. A file that is genuinely corrupt must not be retried
    // forever either, so give up after this many attempts.
    private final ConcurrentHashMap<Integer, Integer> statusParseFailures = new ConcurrentHashMap<>();
    private static final int MAX_STATUS_PARSE_FAILURES = 3;

    public LocalJobMonitor() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LocalJobMonitor");
            t.setDaemon(true);
            return t;
        });
        this.pollInterval = new AdaptivePollInterval("LocalJobMonitor");
    }

    /**
     * Registers a job for monitoring.
     * Also resets the poll interval to base for responsive job detection.
     *
     * @param logDir The directory where job output will be written
     * @param pairId The database pair ID for this job
     */
    public synchronized void registerJob(String logDir, int pairId) {
        trackedPairs.put(logDir, pairId);

        // Crucial fix: when a job is re-run, we must clear its processed status
        // so the monitor will pick up the new run.
        if (processedPairIds.remove(pairId)) {
            log.debug("Cleared processed status cache for re-run pairId: " + pairId);
        }

        // Reset poll interval to base for responsive detection of new job completion
        pollInterval.resetToBase();

        // Cancel the current scheduled poll and reschedule immediately with the reset
        // interval.
        cancelAndRescheduleNow();

        log.debug(
                "Registered job for monitoring: pairId=" +
                        pairId +
                        ", logDir=" +
                        logDir);
    }

    /**
     * Cancels the current scheduled poll and immediately schedules a new one.
     * This ensures the reset interval takes effect without waiting for the
     * previous (potentially long) interval to expire.
     *
     * <p>
     * Thread-safety note: If the poll is currently executing (not just sleeping),
     * we use mayInterruptIfRunning=false to let it complete. The poll's finally
     * block
     * will also call scheduleNextPoll(), potentially resulting in two scheduled
     * polls.
     * This is harmless - the extra poll just does redundant work on thread-safe
     * data
     * structures, and subsequent polls will naturally coalesce to a single chain.
     * </p>
     */
    private void cancelAndRescheduleNow() {
        if (!running) {
            return;
        }

        ScheduledFuture<?> currentPoll = scheduledPoll;
        if (currentPoll != null) {
            // Cancel without interrupting if already running - let it complete gracefully
            boolean cancelled = currentPoll.cancel(false);
            if (cancelled) {
                log.debug(
                        "Cancelled pending poll to apply immediate interval reset");
            }
        }

        // Schedule a new poll with the (now reset) base interval
        scheduleNextPoll();
    }

    /**
     * Starts the monitor with adaptive polling.
     */
    /**
     * Clears a pair's tracking information when it's being rerun.
     * This ensures that when a pair is rerun, the monitor will process
     * the new status.json file instead of skipping it (since it's in
     * processedStatusFiles).
     *
     * <p>
     * This method MUST be called when Jobs.rerunPair() is executed to prevent
     * rerun pairs from getting stuck in ENQUEUED status.
     * </p>
     *
     * <p>
     * <b>ROOT CAUSE FIX:</b> When a pair is rerun, the old status.json file
     * remains in the output directory. The processedStatusFiles set tracks which
     * files have been processed to avoid redundant database updates. Without
     * clearing
     * this tracking, the monitor will skip the new status.json (same path as
     * before),
     * leaving the pair stuck in ENQUEUED status forever.
     * </p>
     *
     * @param pairId The pair ID that is being rerun
     */
    public void clearPairTracking(int pairId) {
        // Find and remove the logDir entry for this pairId
        // This allows the pair to be re-registered when it's submitted again
        String removedLogDir = null;
        Iterator<Map.Entry<String, Integer>> it = trackedPairs
                .entrySet()
                .iterator();
        while (it.hasNext()) {
            Map.Entry<String, Integer> entry = it.next();
            if (entry.getValue() == pairId) {
                removedLogDir = entry.getKey();
                it.remove();
                break;
            }
        }

        // Unconditionally, not only when a logDir entry was found. This removal is what
        // lets a rerun be processed at all, and gating it on the trackedPairs lookup
        // meant that a pair whose logDir entry had gone for any reason stayed in
        // processedPairIds and was skipped forever. A stale parse-failure count would
        // likewise carry into the new run.
        boolean removed = processedPairIds.remove(pairId);
        statusParseFailures.remove(pairId);

        if (removedLogDir != null) {
            log.info(
                    "Cleared tracking for pairId=" +
                            pairId +
                            ": logDir=" +
                            removedLogDir +
                            ", pairId cleared: " +
                            removed +
                            ". Monitor will reprocess status files on next poll.");
        } else {
            log.warn(
                    "Could not find logDir for pairId=" +
                            pairId +
                            " in trackedPairs. " +
                            "Pair may not have been registered with monitor yet.");
        }
    }

    public void start() {
        if (running) {
            log.warn("LocalJobMonitor already running");
            return;
        }

        running = true;
        scheduleNextPoll();

        log.info(
                "LocalJobMonitor started with adaptive polling: " +
                        pollInterval.getStats());
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
                TimeUnit.MILLISECONDS);
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
                "LocalJobMonitor stopped. Final stats: " + pollInterval.getStats());
    }

    /**
     * Checks for completed jobs and processes their results.
     */
    private void checkCompletedJobs() {
        try {
            int checkedCount = 0;
            int foundCount = 0;

            for (Map.Entry<String, Integer> entry : trackedPairs.entrySet()) {
                String logDir = entry.getKey();
                int pairId = entry.getValue();
                checkedCount++;

                Path statusFile = Paths.get(logDir).resolve("status.json");

                // Check if status file exists and hasn't been processed yet
                if (Files.exists(statusFile)) {
                    foundCount++;
                    log.info("DEBUG_TRACE: Monitor checking pairId=" + pairId + " exists=true processed="
                            + processedPairIds.contains(pairId) + " logDir=" + logDir);

                    // Use pairId for tracking instead of path
                    if (!processedPairIds.contains(pairId)) {
                        log.info(
                                "DEBUG_TRACE: Found new status.json for pairId=" +
                                        pairId +
                                        ", logDir=" +
                                        logDir);
                        // Process the job
                        // NOTE: If processing fails, we don't add to processedPairIds
                        // so we can try again next poll
                        try {
                            boolean isTerminal = processCompletedJob(pairId, logDir);
                            log.info("DEBUG_TRACE: processCompletedJob result for pairId=" + pairId + " isTerminal="
                                    + isTerminal);
                            if (isTerminal) {
                                processedPairIds.add(pairId);
                                log.info(
                                        "Monitor: Successfully processed pairId=" +
                                                pairId);
                            } else {
                                log.debug("Monitor: Job still running for pairId=" + pairId + ", will re-check later.");
                            }
                        } catch (Exception e) {
                            log.error(
                                    "Monitor: Error processing pairId=" +
                                            pairId +
                                            ", logDir=" +
                                            logDir,
                                    e);
                            // Mark as error so we don't keep retrying
                            try {
                                JobPairs.setStatusForPairAndStages(
                                        pairId,
                                        StatusCode.ERROR_RUNSCRIPT.getVal());
                                log.warn(
                                        "Monitor: Set ERROR_RUNSCRIPT for pairId=" +
                                                pairId +
                                                " due to processing error");
                                processedPairIds.add(pairId);
                                // Stop tracking the directory too. Left in place it was
                                // counted as work on every later poll, which held the
                                // adaptive interval at its base and never let the poller
                                // back off, while the map grew with every failed job.
                                // Safe now that clearPairTracking no longer depends on
                                // finding this entry to let a rerun through.
                                trackedPairs.remove(logDir);
                                statusParseFailures.remove(pairId);
                            } catch (Exception ex) {
                                log.error(
                                        "Monitor: CRITICAL - Cannot set error status for pairId=" +
                                                pairId,
                                        ex);
                            }
                        }
                    }
                } else {
                    log.trace(
                            "Monitor: No status.json yet for pairId=" +
                                    pairId +
                                    ", logDir=" +
                                    logDir);
                }
            }

            // Update adaptive polling based on work found
            if (foundCount > 0) {
                pollInterval.recordWorkFound(foundCount);
            } else {
                pollInterval.recordIdle();
            }

            // Log summary at debug level
            if (checkedCount > 0) {
                log.debug(
                        "Monitor poll: checked " +
                                checkedCount +
                                " pairs, found " +
                                foundCount +
                                " status files. " +
                                pollInterval.getStats());
            }
        } catch (Exception e) {
            log.error("Monitor: Error in checkCompletedJobs", e);
            // Still record as idle to allow backoff even on errors
            pollInterval.recordIdle();
        }
    }

    /**
     * Processes a completed job by reading output files and updating the database.
     *
     * @param pairId The pair ID
     * @param logDir The log directory containing output files
     * @return true if the job has a terminal status (complete/failed), false if
     *         intermediate (running)
     */
    /** Immutable carrier for the two fields read from status.json. */
    private static final class StatusAndStage {
        final StatusCode status;
        final int stageNumber;
        StatusAndStage(StatusCode status, int stageNumber) {
            this.status = status;
            this.stageNumber = stageNumber;
        }
    }

    private boolean processCompletedJob(int pairId, String logDir)
            throws Exception {
        Path outputDir = Paths.get(logDir);

        // 1. Read status and stageNumber from status.json (single Gson parse)
        StatusAndStage ss = readStatusFile(outputDir, pairId);
        if (ss == null) {
            // Not readable yet, most likely read while the producer was writing it.
            // Leave the pair tracked and unprocessed so the next poll tries again, and
            // only call it a failure once it has stayed unreadable for several polls --
            // a file that is genuinely corrupt must not be retried forever either.
            int failures = statusParseFailures.merge(pairId, 1, Integer::sum);
            if (failures < MAX_STATUS_PARSE_FAILURES) {
                log.info("status.json for pairId=" + pairId + " unreadable ("
                        + failures + "/" + MAX_STATUS_PARSE_FAILURES + "); retrying next poll");
                return false;
            }
            log.error("status.json for pairId=" + pairId + " has been unreadable for "
                    + failures + " consecutive polls; recording it as a runscript error");
            statusParseFailures.remove(pairId);
            ss = new StatusAndStage(StatusCode.ERROR_RUNSCRIPT, 1);
        } else {
            statusParseFailures.remove(pairId);
        }

        // 2. Parse runsolver stats if available
        RunSolverStats stats = parseRunSolverStats(outputDir);

        // 3. Parse attributes if post-processor ran
        Properties attributes = parseAttributes(outputDir);

        // 4. Update database
        updateDatabase(pairId, ss.status, ss.stageNumber, stats, attributes);

        // 5. Check if status is terminal (completed or failed)
        // If so, remove from tracking. If running/processing, keep tracking.
        if (ss.status.finishedRunning() || ss.status.failed() || ss.status == StatusCode.STATUS_COMPLETE) {
            trackedPairs.remove(logDir);
            log.info("Job execution finished for pairId=" + pairId + " with status=" + ss.status);
            return true;
        } else {
            log.debug("Job still running (status=" + ss.status + "), continuing to monitor pairId=" + pairId);
            return false;
        }
    }

    /**
     * Reads status and stageNumber from status.json using Gson.
     *
     * <p>Returns {@link StatusCode#ERROR_RUNSCRIPT} with stageNumber=1 if the
     * file is missing, unreadable, or lacks the required fields — identical
     * sentinel behaviour to the former regex-only readStatus().</p>
     */
    private StatusAndStage readStatusFile(Path outputDir, int pairId) {
        Path statusFile = outputDir.resolve("status.json");
        if (!Files.exists(statusFile)) {
            log.warn("No status.json found for pairId=" + pairId);
            return new StatusAndStage(StatusCode.ERROR_RUNSCRIPT, 1);
        }

        try {
            String json = Files.readString(statusFile);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            if (!obj.has("status")) {
                // Present but without the field yet: the producer writes this file in
                // place, so a read can land mid-write. Treat it as not-ready rather than
                // as a failed run; the caller retries and gives up only after several
                // consecutive attempts.
                log.warn("status.json for pairId=" + pairId + " has no status field yet");
                return null;
            }

            int statusCode = obj.get("status").getAsInt();
            StatusCode resolved = StatusCode.toStatusCode(statusCode);
            int stageNumber = obj.has("stageNumber") ? obj.get("stageNumber").getAsInt() : 1;

            log.debug("Read status " + statusCode + " (" + resolved +
                    ") stageNumber=" + stageNumber + " from status.json for pairId=" + pairId);
            return new StatusAndStage(resolved, stageNumber);
        } catch (IOException | com.google.gson.JsonParseException | IllegalStateException e) {
            // JsonParser.parseString throws JsonSyntaxException -- a RuntimeException --
            // on a truncated file, and getAsJsonObject throws IllegalStateException when
            // the partial content is not yet an object. Neither was caught here, so both
            // escaped to the poll loop's catch(Exception), which recorded ERROR_RUNSCRIPT
            // and marked the pair processed: one unlucky read during a write turned a
            // healthy run into a permanent failure that was never retried.
            //
            // Returning null says "not readable yet" instead. That is not the same as
            // catching the exception and returning the old error sentinel, which would
            // have produced the identical permanent failure by a tidier route.
            log.warn("status.json for pairId=" + pairId + " is not readable yet: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parses post-processor attributes from attributes.txt.
     *
     * <p>Empty {@code starexec-result=} values are normalized to
     * {@link R#STAREXEC_UNKNOWN} so timeout/unknown outcomes are classified
     * consistently downstream.</p>
     */
    private Properties parseAttributes(Path outputDir) {
        Properties props = new Properties();
        Path attrsFile = outputDir.resolve("attributes.txt");

        if (Files.exists(attrsFile)) {
            try (BufferedReader reader = Files.newBufferedReader(attrsFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty())
                        continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String key = line.substring(0, eq).trim();
                        String value = line.substring(eq + 1).trim();
                        if (!key.isEmpty()) {
                            if (R.STAREXEC_RESULT.equals(key) && value.isEmpty()) {
                                value = R.STAREXEC_UNKNOWN;
                            }
                            props.setProperty(key, value);
                        }
                    }
                }
                log.debug(
                        "Parsed " + props.size() + " attributes from attributes.txt");
            } catch (IOException e) {
                log.warn("Failed to parse attributes.txt", e);
            }
        }

        return props;
    }

    /**
     * Parses runsolver statistics from stats.json or var.out.
     */
    private RunSolverStats parseRunSolverStats(Path outputDir) {
        RunSolverStats stats = new RunSolverStats();

        // Try stats.json first (written by functions.bash in container/local mode)
        Path statsJson = outputDir.resolve("stats.json");
        if (Files.exists(statsJson)) {
            try {
                String json = Files.readString(statsJson);
                stats.wallclockTime = extractDouble(json, "wallclockTime");
                stats.cpuTime = extractDouble(json, "cpuTime");
                stats.userTime = extractDouble(json, "userTime");
                stats.systemTime = extractDouble(json, "systemTime");
                stats.maxVirtualMemory = extractDouble(
                        json,
                        "maxVirtualMemory");
                stats.maxResidentSetSize = extractLong(
                        json,
                        "maxResidentSetSize");
                stats.stageNumber = extractInt(json, "stageNumber");

                // Optional: disk size (bytes)
                long ds = extractLong(json, "diskSize");
                if (ds > 0) {
                    stats.diskSize = ds;
                }

                // Optional: hostname of execution host/container
                Matcher m = Pattern.compile(
                        "\"hostname\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                if (m.find()) {
                    stats.hostname = m.group(1);
                }

                log.debug("Parsed stats from stats.json: " + stats);
                return stats;
            } catch (IOException e) {
                log.warn(
                        "Failed to parse stats.json, falling back to var.out",
                        e);
            }
        }

        // Fall back to var.out (runsolver format)
        Path varFile = outputDir.resolve("var.out");
        if (Files.exists(varFile)) {
            try {
                List<String> lines = Files.readAllLines(varFile);
                for (String line : lines) {
                    if (line.startsWith("WCTIME=")) {
                        stats.wallclockTime = Double.parseDouble(
                                line.substring(7));
                    } else if (line.startsWith("CPUTIME=")) {
                        stats.cpuTime = Double.parseDouble(line.substring(8));
                    } else if (line.startsWith("USERTIME=")) {
                        stats.userTime = Double.parseDouble(line.substring(9));
                    } else if (line.startsWith("SYSTEMTIME=")) {
                        stats.systemTime = Double.parseDouble(
                                line.substring(11));
                    } else if (line.startsWith("MAXVM=")) {
                        stats.maxVirtualMemory = Double.parseDouble(
                                line.substring(6));
                    }
                }
                log.debug("Parsed stats from var.out: " + stats);
            } catch (IOException | NumberFormatException e) {
                log.warn("Failed to parse var.out", e);
            }
        }

        return stats;
    }

    private double extractDouble(String json, String key) {
        Matcher m = Pattern.compile(
                "\"" + key + "\"\\s*:\\s*([0-9.]+)").matcher(json);
        if (m.find()) {
            return Double.parseDouble(m.group(1));
        }
        return 0.0;
    }

    private long extractLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9]+)").matcher(
                json);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        return 0L;
    }

    private int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9]+)").matcher(
                json);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    /**
     * Updates the database with job results.
     *
     * <p>Uses {@link JobPairs#setPairStatusPrecise} (single JDBC transaction
     * via {@code UpdatePairStatusPrecise}) to atomically set the terminal stage
     * to {@code status} and all later stages to STATUS_NOT_REACHED, eliminating
     * the dirty-read window present in the former double-call pattern.</p>
     */
    private void updateDatabase(
            int pairId,
            StatusCode status,
            int stageNumber,
            RunSolverStats stats,
            Properties attributes) throws Exception {
        log.info(
                "Updating database for pairId=" + pairId + " with status=" + status
                + " stageNumber=" + stageNumber);
        PairStatusResult statusResult = JobPairs.setPairStatusPreciseResult(
                pairId,
                stageNumber,
                status.getVal(),
                StatusCode.STATUS_NOT_REACHED.getVal(),
                false);
        if (statusResult == PairStatusResult.FAILED) {
            // Not recorded. Throwing leaves the pair tracked so a later poll retries it;
            // returning normally would mark it processed and the result would be lost.
            throw new Exception(
                    "Could not record terminal status " + status + " for pair " + pairId
                            + " stage " + stageNumber);
        }
        if (statusResult == PairStatusResult.SUPERSEDED) {
            log.info("Pair " + pairId + " already had a different terminal status;"
                    + " keeping the recorded result");
        }

        // Update attributes if any
        if (!attributes.isEmpty()) {
            // Stage 1 for now - multi-stage pipelines would need enhancement
            JobPairs.addJobPairAttributes(pairId, 1, attributes);
            log.debug(
                    "Added " +
                            attributes.size() +
                            " attributes for pairId=" +
                            pairId);
        }

        // Persist run stats to DB using stored procedure wrapper
        if (stats.wallclockTime > 0 || stats.cpuTime > 0 || stats.diskSize > 0) {
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
                        stats.diskSize);
                if (ok) {
                    log.debug(
                            "Persisted run stats for pair " + pairId + ": " + stats);
                } else {
                    log.warn("Failed to persist run stats for pair " + pairId);
                }
            } catch (Exception e) {
                log.warn(
                        "Exception persisting run stats for pair " + pairId,
                        e);
            }
        } else {
            log.debug(
                    "No run stats to persist for pairId=" + pairId + ": " + stats);
        }

        log.info(
                "Database updated for pairId=" +
                        pairId +
                        ": status=" +
                        status +
                        ", attrs=" +
                        attributes.size());
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
     * Returns the number of currently tracked pairs.
     *
     * @return Number of tracked pairs
     */
    public int getTrackedPairCount() {
        return trackedPairs.size();
    }

    /**
     * Container for runsolver statistics.
     */
    private static class RunSolverStats {

        public double wallclockTime = 0;
        public double cpuTime = 0;
        public double userTime = 0;
        public double systemTime = 0;
        public double maxVirtualMemory = 0;
        public long maxResidentSetSize = 0;
        public long diskSize = 0;
        public int stageNumber = 1;
        public String hostname = null;

        @Override
        public String toString() {
            return String.format(
                    "RunSolverStats{wall=%.2fs, cpu=%.2fs, user=%.2fs, sys=%.2fs, maxVM=%.0fKB, maxRSS=%dKB, disk=%d, stage=%d, host=%s}",
                    wallclockTime,
                    cpuTime,
                    userTime,
                    systemTime,
                    maxVirtualMemory,
                    maxResidentSetSize,
                    diskSize,
                    stageNumber,
                    hostname == null ? "unknown" : hostname);
        }
    }
}
