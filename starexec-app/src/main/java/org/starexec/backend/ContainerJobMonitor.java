package org.starexec.backend;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.starexec.backend.exception.BackendTransientException;
import org.starexec.constants.R;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.PairStatusResult;
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
     * Runs one final completion scan and then stops the monitor.
     *
     * <p>Call this during graceful shutdown to process any containers that
     * completed just before teardown, preventing pairs from being left in
     * RUNNING status with no container to recover from.</p>
     *
     * <p>Thread safety: clearing running stops another poll being scheduled, and
     * cancelling the pending future stops one that has not started yet. Neither stops a
     * poll that is already executing -- {@code cancel(false)} does not interrupt it --
     * so the mutual exclusion that actually prevents the final scan from running
     * alongside it lives on {@link #checkCompletedJobs()}. This comment previously
     * claimed the cancel was sufficient; it is not.</p>
     */
    public void drainAndStop() {
        log.info("ContainerJobMonitor: draining final completions before stop...");
        running = false;

        // Stops a poll that has not started. One already running is handled by the
        // lock on checkCompletedJobs, which the final scan below must acquire.
        if (scheduledPoll != null) {
            scheduledPoll.cancel(false);
        }

        try {
            checkCompletedJobs();
        } catch (Exception e) {
            log.warn("Error during final completion drain", e);
        }
        stop();
    }

    /**
     * Processes a single completed container that was discovered during
     * startup reconciliation.  Uses the same completion path as the normal
     * monitor so that status.json, stats, and attributes are read and the
     * DB is updated with the actual result.
     *
     * <p>Package-visible so {@code PodmanBackend} can call it during
     * {@code reconcileOrphanedPairs()}.</p>
     *
     * @param info Completed container info from reconciliation
     */
    void processReconciledJob(PodmanBackend.CompletedContainerInfo info) {
        try {
            processCompletedJob(info);
        } catch (Exception e) {
            log.error("Error processing reconciled job " + info.pairId, e);
            // Emergency error marking so the pair doesn't stay stuck
            try {
                JobPairs.setPairStatusPrecise(
                    info.pairId, 1,
                    StatusCode.ERROR_RUNSCRIPT.getVal(),
                    StatusCode.STATUS_NOT_REACHED.getVal());
            } catch (Exception ex) {
                log.error("Failed to set error status for pair " + info.pairId, ex);
            }
        }
    }

    /**
     * Checks for completed containers and processes their results.
     */
    /**
     * Synchronized because two threads can reach it: the scheduler thread on its normal
     * poll, and the shutdown thread through {@link #drainAndStop()}. The scheduler is
     * single-threaded, so polls never overlap each other -- shutdown is the only other
     * entrant.
     *
     * <p>Without this, a drain beginning while a poll was in flight had both threads
     * fetching the same completed containers and processing them concurrently: two sets
     * of database writes for one result, and two attempts to remove the same container.
     * There is no per-container claim or idempotency key here to fall back on.
     * {@code scheduledPoll.cancel(false)} does not help, because it will not interrupt a
     * task that has already started.
     *
     * <p>The cost is that shutdown waits for an in-flight poll to finish. That is what
     * draining means.
     */
    private synchronized void checkCompletedJobs() {
        try {
            // Get all completed containers that haven't been processed
            List<PodmanBackend.CompletedContainerInfo> completedJobs =
                backend.getCompletedContainers();
            Set<String> protectedContainerIds = new HashSet<>();
            for (PodmanBackend.CompletedContainerInfo info : completedJobs) {
                protectedContainerIds.add(info.containerId);
            }

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
                    // Mark as error so we don't keep retrying.
                    // stageNumber is unknown at this point; default to 1 so that
                    // UpdatePairStatusPrecise still fires the job_pair_completion
                    // side effects and marks any stage-2+ rows as NOT_REACHED.
                    try {
                        JobPairs.setPairStatusPrecise(
                            info.pairId,
                            1,
                            StatusCode.ERROR_RUNSCRIPT.getVal(),
                            StatusCode.STATUS_NOT_REACHED.getVal()
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

            int staleCleanupCount = backend.cleanupStaleExitedContainers(
                protectedContainerIds
            );

            // Update adaptive polling based on work found
            if (processedCount > 0 || staleCleanupCount > 0) {
                pollInterval.recordWorkFound(processedCount + staleCleanupCount);
                log.debug(
                    "ContainerJobMonitor: Processed " +
                        processedCount +
                        " completed jobs and swept " +
                        staleCleanupCount +
                        " stale exited containers. " +
                        pollInterval.getStats()
                );
            } else {
                pollInterval.recordIdle();
                log.trace(
                    "ContainerJobMonitor: No completed jobs found. " +
                        pollInterval.getStats()
                );
            }
        } catch (BackendTransientException e) {
            log.warn(
                "Transient error checking completed Podman jobs; resetting to base poll interval for a fast retry",
                e
            );
            pollInterval.resetToBase();
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

        // Read pairId and stageNumber from status.json. Using Gson rather than
        // regex avoids silent breakage on whitespace or field-order changes.
        //
        // The container label is the pair's identity: the application set it when it
        // created the container and nothing running inside can change it. status.json is
        // not identity -- the job script writes it into a directory the solver can also
        // write, because the job container runs everything as root by design
        // (job-runner.Dockerfile declares no USER, and container mode deliberately drops
        // the `sudo -u sandbox` the SGE path uses). So its pairId is checked against the
        // label rather than used in place of it; adopting it, as this did, let a pair's
        // own solver address a different pair's rows.
        int pairId = info.pairId;
        int stageNumber = 1; // safe default if status.json is absent or incomplete
        Integer declaredPairId = null;
        Path statusJson = outputPath.resolve("status.json");
        if (Files.exists(statusJson)) {
            try {
                String json = Files.readString(statusJson);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                if (obj.has("pairId")) {
                    declaredPairId = obj.get("pairId").getAsInt();
                }
                if (obj.has("stageNumber")) {
                    stageNumber = obj.get("stageNumber").getAsInt();
                    log.debug("Extracted stageNumber from status.json: " + stageNumber);
                }
            } catch (Exception e) {
                log.warn(
                    "Failed to read status.json, using label values: pairId=" +
                        info.pairId,
                    e
                );
            }
        }
        // Outside the catch above, so an ownership violation is not swallowed as a
        // parse warning.
        if (declaredPairId != null) {
            if (pairId <= 0) {
                // No usable label on the container. Fall back, as this has always done.
                pairId = declaredPairId;
                log.warn(
                    "Container " + info.containerId +
                        " carries no pair label; using status.json pairId " + pairId
                );
            } else if (declaredPairId != pairId) {
                throw new Exception(
                    "status.json claims pair " + declaredPairId +
                        " but the container is labelled pair " + pairId +
                        "; refusing to write and keeping the container for inspection"
                );
            }
        }

        // Per-stage snapshots, for a pair run by a job script that writes them.
        Map<Integer, Integer> stageSnapshots = readStageSnapshots(outputPath, pairId);

        // An earlier stage still showing RUNNING while a later one finished is lost
        // history, not history to reconstruct. Sequential completion is not a safe
        // inference here: a no-op pipeline stage consumes a stage number without ever
        // owning a jobpair_stage_data row, so the numbers are not contiguous and
        // "stage 3 finished" implies nothing about stage 2. Refuse, and keep the
        // container so the run can be examined.
        for (Map.Entry<Integer, Integer> snapshot : stageSnapshots.entrySet()) {
            if (
                snapshot.getKey() < stageNumber &&
                !StatusCode.toStatusCode(snapshot.getValue()).finishedRunning()
            ) {
                throw new Exception(
                    "Pair " + pairId + " reports stage " + stageNumber +
                        " finished, but stage " + snapshot.getKey() +
                        " is still at status " + snapshot.getValue() +
                        "; refusing to infer its result"
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
        // Only when runsolver did not report one. This assignment was unconditional
        // despite its comment, so the child's real exit status -- read from
        // "Child status: N" in watcher.out -- was always discarded in favour of the
        // container's. A wrapper that exits zero over a failed solver then looked
        // successful. exitCodeReported distinguishes "runsolver said 0" from "runsolver
        // said nothing", which a plain 0 cannot.
        if (!stats.exitCodeReported) {
            stats.exitCode = info.exitCode;
        }

        // 2. Determine job status from stats
        StatusCode status = determineStatus(stats, outputPath);

        // 3. Parse attributes if post-processor ran
        Properties attributes = parseAttributes(outputPath);

        // 4. Update database
        updateDatabase(
            pairId,
            stageNumber,
            stats,
            status,
            attributes,
            info.partitionIndex,
            stageSnapshots
        );

        log.info("Completed job " + pairId + " processed: status=" + status + " stageNumber=" + stageNumber);
    }

    /**
     * Parses runsolver var.out and watcher.out files.
     * Also checks for stats.json as an alternative format.
     */
    /**
     * True if any source yielded a measurement, i.e. we learned something about this run.
     *
     * <p>Every field of {@link RunsolverStats} starts at zero, so an all-zero object is
     * indistinguishable from "nothing was parsed" -- and that is precisely the case in
     * which the values must not be written. A real run always reports a positive
     * wallclock: runsolver measures wall time as a float and no process takes literally
     * zero seconds. {@code exitCodeReported} is included because a solver that exited
     * immediately with a status is a run we did observe.
     */
    private static boolean hasAnyMeasurement(RunsolverStats stats) {
        return stats.wallclockTime > 0
            || stats.cpuTime > 0
            || stats.userTime > 0
            || stats.systemTime > 0
            || stats.maxVirtualMemory > 0
            || stats.maxResidentSetSize > 0
            || stats.diskSize > 0
            || stats.exitCodeReported;
    }

    private RunsolverStats parseRunsolverOutput(Path outputDir) {
        RunsolverStats stats = new RunsolverStats();

        // stats.json (written by functions.bash in container mode) carries timings and
        // sizes. It carries NO resource-limit information -- containerWriteStats writes
        // wallclockTime, cpuTime, userTime, systemTime, maxVirtualMemory,
        // maxResidentSetSize, diskSize and hostname, and nothing else.
        //
        // wallclockExceeded, cpuExceeded and memoryExceeded are set only by
        // parseWatcherLine, from runsolver's own watcher.out. So returning here once
        // stats.json was found left all three false, and determineStatus then fell
        // through to STATUS_COMPLETE: in container mode a solver that exhausted its
        // wallclock, CPU or memory limit was recorded as having finished successfully,
        // whenever the container itself exited zero.
        //
        // The two files are complementary rather than alternatives, so read both.
        // watcher.out exists in container mode -- updateStats in functions.bash awks it
        // for "Child status" and "maximum resident set size" on the same path that
        // writes stats.json -- and the block below already guards on its existence, so
        // an installation that somehow lacks it behaves exactly as before.
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

        // stats.json last, so it stays authoritative for the fields it carries and this
        // remains a purely additive change: in container mode the timings are exactly
        // what they were before, and the only difference is that the limit flags read
        // from watcher.out above now survive instead of being skipped.
        Path statsJson = outputDir.resolve("stats.json");
        if (Files.exists(statsJson)) {
            try {
                String json = Files.readString(statsJson);
                parseStatsJson(json, stats);
                log.debug("Parsed stats from stats.json");
            } catch (IOException e) {
                log.warn("Failed to parse stats.json; using var.out and watcher.out only", e);
            }
        }

        return stats;
    }

    /**
     * Parses stats.json (written by functions.bash in container mode) using Gson.
     *
     * <p>Each field is extracted individually so that a missing or malformed
     * field leaves the corresponding {@link RunsolverStats} default value
     * intact rather than aborting the entire parse. This mirrors the
     * graceful-degradation pattern used for {@code status.json} parsing
     * elsewhere in this class and satisfies the Gson mandate in AGENTS.md §5.2.</p>
     */
    private void parseStatsJson(String json, RunsolverStats stats) {
        JsonObject obj;
        try {
            obj = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            log.warn("stats.json is not valid JSON; skipping stats parse", e);
            return;
        }

        try {
            if (obj.has("wallclockTime")) {
                stats.wallclockTime = obj.get("wallclockTime").getAsDouble();
            }
        } catch (Exception e) {
            log.warn("stats.json: could not parse 'wallclockTime'", e);
        }

        try {
            if (obj.has("cpuTime")) {
                stats.cpuTime = obj.get("cpuTime").getAsDouble();
            }
        } catch (Exception e) {
            log.warn("stats.json: could not parse 'cpuTime'", e);
        }

        try {
            if (obj.has("userTime")) {
                stats.userTime = obj.get("userTime").getAsDouble();
            }
        } catch (Exception e) {
            log.warn("stats.json: could not parse 'userTime'", e);
        }

        try {
            if (obj.has("systemTime")) {
                stats.systemTime = obj.get("systemTime").getAsDouble();
            }
        } catch (Exception e) {
            log.warn("stats.json: could not parse 'systemTime'", e);
        }

        try {
            if (obj.has("maxVirtualMemory")) {
                stats.maxVirtualMemory = obj.get("maxVirtualMemory").getAsDouble();
            }
        } catch (Exception e) {
            log.warn("stats.json: could not parse 'maxVirtualMemory'", e);
        }

        try {
            if (obj.has("maxResidentSetSize")) {
                stats.maxResidentSetSize = obj.get("maxResidentSetSize").getAsLong();
            }
        } catch (Exception e) {
            log.warn("stats.json: could not parse 'maxResidentSetSize'", e);
        }

        try {
            if (obj.has("stageNumber")) {
                stats.stageNumber = obj.get("stageNumber").getAsInt();
            }
        } catch (Exception e) {
            log.warn("stats.json: could not parse 'stageNumber'", e);
        }

        try {
            if (obj.has("diskSize")) {
                stats.diskSize = obj.get("diskSize").getAsLong();
            }
        } catch (Exception e) {
            log.warn("stats.json: could not parse 'diskSize'", e);
        }

        try {
            if (obj.has("hostname")) {
                stats.hostname = obj.get("hostname").getAsString();
            }
        } catch (Exception e) {
            log.warn("stats.json: could not parse 'hostname'", e);
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
        } else if (line.startsWith("TIMEOUT=")) {
            // Watcher.hh:472 writes this with boolalpha, so the value is the lowercase
            // word "true" or "false". Matching on the "TIMEOUT=" prefix rather than a
            // bare "TIMEOUT" is deliberate: the file also carries an explanatory comment
            // line, "# TIMEOUT: did the solver exceed the time limit?", which a looser
            // match would hit.
            stats.timeout = Boolean.parseBoolean(
                line.substring("TIMEOUT=".length()).trim());
        } else if (line.startsWith("MEMOUT=")) {
            // Watcher.hh:475, same form, same reason.
            stats.memout = Boolean.parseBoolean(
                line.substring("MEMOUT=".length()).trim());
        }
    }

    /**
     * Parses a line from watcher.out (runsolver format).
     */
    /**
     * Every literal matched here is quoted from runsolver's own source, which is
     * vendored at {@code org/starexec/config/sge/RunSolverSource/}. The line numbers
     * are given so the next person can re-derive them instead of trusting this comment:
     *
     * <pre>
     *   Watcher.hh:325  cout &lt;&lt; "Child status: " &lt;&lt; WEXITSTATUS(childstatus)
     *   Watcher.hh:396  cout &lt;&lt; "maximum resident set size= " &lt;&lt; r.ru_maxrss
     *   Watcher.hh:717  stopSolver("Maximum CPU time exceeded: ...")
     *   Watcher.hh:720  stopSolver("Maximum wall clock time exceeded: ...")
     *   Watcher.hh:723  stopSolver("Maximum VSize exceeded: ...")
     *   Watcher.hh:726  stopSolver("Maximum memory exceeded: ...")
     * </pre>
     *
     * <p>The RSS line uses <b>=</b>, not <b>:</b>. This matcher previously used a colon
     * and so could never fire against real output; the unit test did not catch it because
     * its fixture had been written from this parser rather than from the producer above.
     * That is the reason for this comment: a fixture or pattern that cannot be traced to
     * a line of runsolver source is not evidence.
     */
    private void parseWatcherLine(String line, RunsolverStats stats) {
        Matcher m;

        if (
            (m = Pattern.compile("Child status: (\\d+)").matcher(line)).find()
        ) {
            stats.exitCode = Integer.parseInt(m.group(1));
            stats.exitCodeReported = true;
        } else if (
            (m = Pattern.compile(
                    "maximum resident set size=\\s*(\\d+)"
                ).matcher(line)).find()
        ) {
            stats.maxResidentSetSize = Long.parseLong(m.group(1));
        } else if (line.contains("wall clock time exceeded")) {
            stats.wallclockExceeded = true;
        } else if (line.contains("CPU time exceeded")) {
            stats.cpuExceeded = true;
        } else if (line.contains("VSize exceeded")) {
            stats.memoryExceeded = true;
        } else if (line.contains("Maximum memory exceeded")) {
            // Watcher.hh:726, raised when runsolver is given -R. That flag is not
            // passed today, so this branch is currently unreachable -- but it costs one
            // line, and without it adding -R later would silently record every memory
            // kill as a clean completion.
            stats.memoryExceeded = true;
        }
    }

    /** Snapshot files the job script writes under {@code stage-status/}, one per stage. */
    private static final Pattern STAGE_SNAPSHOT_NAME = Pattern.compile(
        "^([1-9][0-9]*)\\.json$"
    );

    /**
     * Reads the per-stage status snapshots a finished container left behind.
     *
     * <p>status.json is a single slot and every stage truncates it, so before these
     * existed only the last stage's status survived a multi-stage pair -- every earlier
     * stage kept the status it was enqueued with, however far it actually got. The job
     * script now writes the same record once per stage into {@code stage-status/<n>.json}
     * beside it.
     *
     * <p>Each record is checked against something the solver does not control before it
     * is believed. The pair comes from the container label, not from the file, and the
     * file name has to agree with the stage the record names. That matters because the
     * job container runs the solver as root in the same namespace as the job script, so
     * this directory is writable by solver code; the label is not.
     *
     * <p>A record that fails a check aborts the whole read rather than being skipped. A
     * pair whose output cannot be trusted must not be half recorded, and throwing here
     * leaves the container in place for the next poll to retry.
     *
     * @param outputDir the container's output directory
     * @param pairId    the pair the container is labelled with
     * @return stage number to status code, empty when the directory is absent
     * @throws Exception when a record is malformed or does not belong to this pair
     */
    private Map<Integer, Integer> readStageSnapshots(Path outputDir, int pairId)
        throws Exception {
        Map<Integer, Integer> snapshots = new TreeMap<>();
        Path dir = outputDir.resolve("stage-status");
        if (!Files.isDirectory(dir)) {
            // An older job script, or a pair that recorded nothing. Handled exactly as
            // before, from status.json alone.
            return snapshots;
        }

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                Matcher named = STAGE_SNAPSHOT_NAME.matcher(name);
                if (!named.matches()) {
                    // The writer's own temporary file, or something that is not a
                    // snapshot at all. Ignored rather than guessed at.
                    log.debug("Ignoring non-snapshot file in stage-status: " + name);
                    continue;
                }
                int stageFromName = Integer.parseInt(named.group(1));

                JsonObject obj;
                try {
                    obj = JsonParser
                        .parseString(Files.readString(entry))
                        .getAsJsonObject();
                } catch (Exception e) {
                    throw new Exception("Malformed stage snapshot " + entry, e);
                }
                if (
                    !obj.has("pairId") ||
                    !obj.has("stageNumber") ||
                    !obj.has("status")
                ) {
                    throw new Exception("Incomplete stage snapshot " + entry);
                }

                int recordPairId = obj.get("pairId").getAsInt();
                int recordStage = obj.get("stageNumber").getAsInt();
                int recordStatus = obj.get("status").getAsInt();

                if (recordPairId != pairId) {
                    throw new Exception(
                        "Stage snapshot " + entry + " claims pair " + recordPairId +
                            " but the container is labelled pair " + pairId
                    );
                }
                if (recordStage != stageFromName) {
                    throw new Exception(
                        "Stage snapshot " + entry + " names stage " + recordStage
                    );
                }
                if (
                    StatusCode.toStatusCode(recordStatus) ==
                        StatusCode.STATUS_UNKNOWN &&
                    recordStatus != StatusCode.STATUS_UNKNOWN.getVal()
                ) {
                    throw new Exception(
                        "Stage snapshot " + entry + " carries unknown status " +
                            recordStatus
                    );
                }

                snapshots.put(stageFromName, recordStatus);
            }
        }

        log.debug(
            "Pair " + pairId + ": read " + snapshots.size() + " stage snapshots"
        );
        return snapshots;
    }

    /**
     * Determines the job status based on runsolver stats and output files.
     */
    private StatusCode determineStatus(RunsolverStats stats, Path outputDir) {
        // Detection is runsolver's TIMEOUT=/MEMOUT=; the prose only picks between
        // EXCEED_CPU and EXCEED_RUNTIME. See RunsolverVerdict for why round that way.
        //
        // This used to test the prose flags alone, so a solver killed for exceeding a
        // limit was recorded as STATUS_COMPLETE whenever the sentence failed to match --
        // and it fell through to STATUS_COMPLETE for every SIGKILLed solver anyway,
        // because Watcher.hh:326-331 prints no "Child status:" line on the WIFSIGNALED
        // path, leaving exitCode at 0.
        StatusCode limit = RunsolverVerdict.classify(
            stats.timeout,
            stats.memout,
            stats.cpuExceeded,
            stats.wallclockExceeded,
            stats.memoryExceeded
        );
        if (limit != null) {
            return limit;
        }

        if (stats.exitCode != 0) {
            // Check if var.out exists - if not, likely runscript error
            if (!Files.exists(outputDir.resolve("var.out"))) {
                return StatusCode.ERROR_RUNSCRIPT;
            }
        }
        return StatusCode.STATUS_COMPLETE;
    }

    /**
     * Parses post-processor attributes from attributes.txt.
     *
     * <p>Each non-empty line is expected to have the form {@code key=value}.
     * Empty values are preserved for most attributes, but
     * {@code starexec-result=} is normalized to
     * {@link R#STAREXEC_UNKNOWN} so downstream correctness logic treats timed-out
     * and unknown outcomes consistently. Only lines with a missing or blank
     * <em>key</em> are skipped.</p>
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
                        if (!key.isEmpty()) {
                            if (R.STAREXEC_RESULT.equals(key) && value.isEmpty()) {
                                value = R.STAREXEC_UNKNOWN;
                            }
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
     *
     * <p>Uses {@link JobPairs#setPairStatusPrecise} (single JDBC transaction via
     * {@code UpdatePairStatusPrecise}) to atomically set the terminal stage to
     * {@code status} and all later stages to STATUS_NOT_REACHED, eliminating
     * the dirty-read window in the former double-call pattern.</p>
     *
     * <p>Also sets {@code end_time} on the pair row. In container mode
     * {@code functions.bash} skips the {@code SetPairEndTime} stored-procedure
     * call (it is a no-op there), so without this step the completion timestamp
     * remains NULL for container-executed pairs.</p>
     */
    private void updateDatabase(
        int pairId,
        int stageNumber,
        RunsolverStats stats,
        StatusCode status,
        Properties attributes,
        int partitionIndex,
        Map<Integer, Integer> stageSnapshots
    ) throws Exception {
        // Earlier stages first. UpdatePairStatusPrecise below rewrites the terminal stage
        // and everything after it, so these survive it; writing them afterwards would
        // leave the pair briefly complete with a stale stage beside it. Each goes through
        // the stage-only routine, which touches jobpair_stage_data alone -- no pair
        // status, no job_pair_completion, no end_time -- so pair completion still fires
        // exactly once, below.
        Map<Integer, Integer> earlierStages = new TreeMap<>();
        for (Map.Entry<Integer, Integer> snapshot : stageSnapshots.entrySet()) {
            if (snapshot.getKey() < stageNumber) {
                earlierStages.put(snapshot.getKey(), snapshot.getValue());
            }
        }
        if (!JobPairs.setEarlierStageStatuses(pairId, earlierStages)) {
            // Nothing was written. Throwing keeps the container so the next poll retries;
            // the stage writes are idempotent, so the retry converges.
            throw new Exception(
                "Could not record earlier stage statuses " + earlierStages +
                    " for pair " + pairId
            );
        }

        PairStatusResult statusResult = JobPairs.setPairStatusPreciseResult(
            pairId,
            stageNumber,
            status.getVal(),
            StatusCode.STATUS_NOT_REACHED.getVal(),
            false
        );
        if (statusResult == PairStatusResult.FAILED) {
            // The status never landed. Throwing keeps the container in place so the next
            // poll retries; swallowing this would remove the container and lose the
            // result permanently, since nothing else re-reads its output.
            throw new Exception(
                "Could not record terminal status " + status + " for pair " + pairId
                    + " stage " + stageNumber);
        }
        if (statusResult == PairStatusResult.SUPERSEDED) {
            // Someone else recorded a result first. The pair is finished; carry on and
            // let the caller release the container rather than retrying forever.
            log.info("Pair " + pairId + " already had a different terminal status;"
                + " keeping the recorded result");
        }

        // Set end_time. This call is non-fatal: a failure here does not prevent
        // the rest of the DB update from completing.
        try {
            if (!JobPairs.setEndTime(pairId)) {
                log.warn("setEndTime found no row for pair " + pairId +
                         " (pair may have been deleted)");
            }
        } catch (Exception e) {
            log.warn("Failed to set end_time for pair " + pairId, e);
        }

        // Persist run stats using JobPairs.updateRunSolverStats.
        //
        // Only when we actually parsed something. RunsolverStats initialises every
        // measurement to 0, so if var.out, watcher.out and stats.json were all missing
        // or unparseable, writing unconditionally pushed wallclock=0, cpu=0, max_vmem=0
        // into jobpair_stage_data through UpdatePairRunSolverStats -- a solver that ran
        // for an hour recorded as having taken no time. On a platform whose numbers
        // decide published rankings that is a wrong result, not a missing one, and the
        // only trace it left was a debug line.
        //
        // LocalJobMonitor has always guarded this; the container path -- the one every
        // current deployment uses -- did not.
        if (!hasAnyMeasurement(stats)) {
            // Deliberately warn rather than debug: a completed run that yielded no
            // parseable output is a fault worth seeing, and staying silent about it is
            // how this stayed invisible.
            log.warn(
                "No parseable runsolver output for pair " + pairId +
                " (no var.out, watcher.out or stats.json field was read); leaving the" +
                " recorded measurements untouched rather than overwriting them with zeros"
            );
            return;
        }
        try {
            String nodeName = (stats.hostname != null &&
                    !stats.hostname.isEmpty())
                ? stats.hostname
                : backend.getWorkerNodeNameForPartition(partitionIndex);
            boolean ok = JobPairs.updateRunSolverStats(
                pairId,
                nodeName,
                stats.wallclockTime,
                stats.cpuTime,
                stats.userTime,
                stats.systemTime,
                stats.maxVirtualMemory,
                stats.maxResidentSetSize,
                // The caller's stageNumber, not stats.stageNumber. Both originate from
                // CURRENT_STAGE_NUMBER in functions.bash and normally agree, but
                // stats.stageNumber falls back to 1 when stats.json is absent, while
                // this parameter is the stage read from status.json and already used
                // for the status write above. Using it keeps the stats and the status
                // on the same row by construction, instead of landing the stats on
                // stage 1 or raising "Stage not found" into a swallowed exception.
                stageNumber,
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

        // Persist attributes. parseAttributes() normalizes starexec-result=
        // to starexec-unknown so timeout/unknown outcomes are classified
        // consistently downstream.
        if (!attributes.isEmpty()) {
            // Stage 1 for now — multi-stage pipelines would need enhancement
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
        /** True once runsolver reported a child status; 0 alone cannot say. */
        public boolean exitCodeReported = false;
        /** Set from watcher.out prose; discriminates which limit fired. */
        public boolean wallclockExceeded = false;
        public boolean cpuExceeded = false;
        public boolean memoryExceeded = false;
        /**
         * runsolver's own verdicts from var.out (Watcher.hh:471-475). These, not the
         * prose above, are what detect that a limit fired at all.
         */
        public boolean timeout = false;
        public boolean memout = false;
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
