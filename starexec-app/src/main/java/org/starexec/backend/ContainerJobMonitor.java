package org.starexec.backend;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.starexec.backend.exception.BackendTransientException;
import org.starexec.backend.exception.RetryableIngestionException;
import org.starexec.constants.R;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.database.StageStatusBatchResult;
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
 *   <li>{@code status.json} - Required terminal scientific status and stage evidence</li>
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
    boolean processReconciledJob(PodmanBackend.CompletedContainerInfo info) {
        try {
            processCompletedJob(info);
            return true;
        } catch (StageStatusSnapshots.InvalidSnapshotException e) {
            log.error(
                "INGESTION INTERVENTION REQUIRED: reconciled pair " + info.pairId +
                    " has no valid terminal evidence. The pair is left unresolved and its" +
                    " output is retained at " + info.outputDir + ". No solver status or" +
                    " stage has been invented for this.",
                e
            );
            return false;
        } catch (RetryableIngestionException e) {
            log.warn(
                "Could not ingest retained terminal evidence for reconciled pair " +
                    info.pairId + "; its container and output remain available for retry",
                e
            );
            return false;
        } catch (Exception e) {
            // An implementation or infrastructure failure is not terminal solver evidence.
            // Returning false makes the caller retain the container; the normal monitor can
            // retry it after startup without this path manufacturing a result to converge.
            log.error(
                "INGESTION INTERVENTION REQUIRED: failed to process reconciled pair " +
                    info.pairId + ". The pair is left unresolved and its output is retained" +
                    " at " + info.outputDir + ". No solver status or stage has been invented.",
                e
            );
            return false;
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
                if (ingestionQuarantine.contains(info.containerId)) {
                    continue;
                }
                IngestionAttempt pending = ingestionAttempts.get(info.containerId);
                if (pending != null
                    && System.currentTimeMillis() < pending.nextAttemptMillis) {
                    continue;
                }
                try {
                    processCompletedJob(info);
                    // Only now is the container's output no longer the only copy.
                    backend.removeCompletedContainer(info.containerId);
                    ingestionAttempts.remove(info.containerId);
                    processedCount++;
                } catch (RetryableIngestionException e) {
                    // The results are fine; the platform could not record them. Recording a
                    // solver failure here would falsify the experiment, and deleting the
                    // container would destroy the only copy of a good result. So: keep both,
                    // change nothing in the database, and come back to it.
                    //
                    // The container has definitively exited, so its execution slot is handed
                    // back immediately -- retrying must not cost capacity.
                    backend.releaseSlotForCompletedContainer(info.containerId);
                    recordIngestionFailure(info, e);
                } catch (StageStatusSnapshots.InvalidSnapshotException e) {
                    // The container has no valid terminal evidence, so there is no trusted
                    // result/stage pair to record and no retry that would change these bytes.
                    //
                    // Held on the first attempt rather than after MAX_INGESTION_ATTEMPTS of
                    // backoff: the bounded retry below exists for a platform that might
                    // recover, and this cannot. The container and its output are kept, the
                    // execution slot is handed back, and no status is invented -- the pair
                    // stays unresolved and visible instead of being recorded as a solver
                    // failure it never had.
                    backend.releaseSlotForCompletedContainer(info.containerId);
                    ingestionQuarantine.add(info.containerId);
                    ingestionAttempts.remove(info.containerId);
                    log.error(
                        "INGESTION INTERVENTION REQUIRED: pair " + info.pairId + " has no" +
                            " valid terminal evidence, so it cannot be recorded and retrying" +
                            " cannot help. The pair is left unresolved and its" +
                            " output is retained at " + info.outputDir + " (container " +
                            info.containerId + "). No solver result or stage has been" +
                            " invented for this.",
                        e
                    );
                } catch (Exception e) {
                    // An unexpected implementation or infrastructure failure is not a solver
                    // result. Keep the container and its output, release only the execution
                    // slot, and use the bounded retry/quarantine lifecycle. This also covers
                    // failures after a legitimate result was written: retrying may finish the
                    // remaining metadata, but must never overwrite it with a fabricated
                    // ERROR_RUNSCRIPT/stage 1 fallback.
                    log.error(
                        "Unexpected failure ingesting completed job " + info.pairId +
                            "; terminal evidence is retained and no solver result was invented",
                        e
                    );
                    backend.releaseSlotForCompletedContainer(info.containerId);
                    recordIngestionFailure(info, e);
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
     * Counts an infrastructure failure against a container and decides whether to come back.
     *
     * <p>After {@link #MAX_INGESTION_ATTEMPTS} the container is quarantined rather than
     * force-failed. There is no status in the model that means "the solver was fine but we
     * could not write the result down", and inventing one -- {@code ERROR_RUNSCRIPT}, say --
     * would record a scientific failure that did not happen. Leaving the pair unresolved with
     * its evidence intact is the honest outcome, and it is the one an operator can fix.
     */
    private void recordIngestionFailure(
        PodmanBackend.CompletedContainerInfo info,
        Exception cause
    ) {
        IngestionAttempt state = ingestionAttempts.computeIfAbsent(
            info.containerId,
            key -> new IngestionAttempt()
        );
        state.attempts++;

        if (state.attempts >= MAX_INGESTION_ATTEMPTS) {
            ingestionQuarantine.add(info.containerId);
            log.error(
                "INGESTION INTERVENTION REQUIRED: pair " + info.pairId + " produced results" +
                    " that could not be recorded after " + state.attempts + " attempts." +
                    " The pair is left unresolved and its output is retained at " +
                    info.outputDir + " (container " + info.containerId + ")." +
                    " No solver status has been invented for this. Resolve the cause and" +
                    " restart the application to retry.",
                cause
            );
            return;
        }

        long backoff = Math.min(
            BASE_INGESTION_BACKOFF_MS << (state.attempts - 1),
            MAX_INGESTION_BACKOFF_MS
        );
        state.nextAttemptMillis = System.currentTimeMillis() + backoff;
        log.warn(
            "Could not record results for pair " + info.pairId + " (attempt " +
                state.attempts + " of " + MAX_INGESTION_ATTEMPTS + "); results retained," +
                " retrying in " + (backoff / 1000) + "s",
            cause
        );
    }

    /**
     * How many times ingestion may fail for infrastructure reasons before a container stops
     * being polled. Five attempts with backoff spans several minutes, which covers a database
     * restart or a failover without spinning.
     */
    private static final int MAX_INGESTION_ATTEMPTS = 5;

    /** First backoff step; doubles per attempt, capped by {@link #MAX_INGESTION_BACKOFF_MS}. */
    private static final long BASE_INGESTION_BACKOFF_MS = 15_000L;

    private static final long MAX_INGESTION_BACKOFF_MS = 300_000L;

    /** Per-container retry state. Keyed by container id, cleared once ingestion succeeds. */
    private final Map<String, IngestionAttempt> ingestionAttempts =
        new ConcurrentHashMap<>();

    /**
     * Containers whose ingestion has failed too many times. They are skipped rather than
     * force-failed: their results are still on disk, and inventing a solver status for what
     * is an infrastructure problem would falsify the experiment. An operator resolves the
     * cause and restarts; the monitor then picks them up again.
     */
    private final Set<String> ingestionQuarantine = ConcurrentHashMap.newKeySet();

    private static final class IngestionAttempt {
        int attempts;
        long nextAttemptMillis;
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
        if (info.pairId <= 0) {
            throw new StageStatusSnapshots.InvalidSnapshotException(
                "completed container " + info.containerId + " has no authoritative pair"
                    + " identity; status.json cannot establish ownership of its own output"
            );
        }
        final int pairId = info.pairId;
        // A completed container with no status.json supplies neither a solver result nor a
        // stage identity. Holding it through the same deterministic-refusal path as a malformed
        // record retains its output and prevents both fields from being invented.
        //
        // A file that exists must say which stage it is about. It used to fall back to the
        // same 1 whenever the field was missing, unusable, or a shape gson would coerce, and
        // that number went on to UpdatePairStatusPrecise as an authoritative identity: the
        // terminal status onto that stage, NOT_REACHED onto every stage above it. A malformed
        // file therefore produced a confident write against an invented stage.
        int stageNumber;
        StatusCode statusFromRecord;
        Integer declaredPairId = null;
        Path statusJson = outputPath.resolve("status.json");
        if (!Files.exists(statusJson)) {
            throw new StageStatusSnapshots.InvalidSnapshotException(
                "completed container " + info.containerId + " for pair " + info.pairId
                    + " has no status.json, so neither its result nor stage is known"
            );
        }
        JsonObject obj;
        try {
            String json = Files.readString(statusJson);
            obj = JsonParser.parseString(json).getAsJsonObject();
        } catch (IOException e) {
            // The file is there and could not be read. That is the filesystem, not the
            // contents, so it stays retryable rather than becoming a permanent refusal.
            throw new RetryableIngestionException(
                "Could not read status.json for pair " + info.pairId, e
            );
        } catch (Exception e) {
            // It parsed as something, and that something is not a status record. The same
            // bytes will not parse next time either.
            throw new StageStatusSnapshots.InvalidSnapshotException(
                "status.json for pair " + info.pairId + " exists but is not a status"
                    + " record, so the stage that produced this result is unknown", e);
        }
        if (obj.has("pairId")) {
            // An ownership claim, so not getAsInt: "4242", 4242.5, [4242] and 2^32 + 4242
            // would all pass the label check below, or become the pair (#196).
            try {
                declaredPairId = StrictJsonInt.parse("pairId", obj.get("pairId"));
            } catch (StrictJsonInt.NotAnInt e) {
                throw new StageStatusSnapshots.InvalidSnapshotException(
                    "status.json for pair " + info.pairId + " has a non-integer pairId: it "
                        + e.getMessage());
            }
        }
        // Throws rather than defaulting. Caught in the poll loop by the branch that
        // holds the container, so nothing is invented and nothing is retried.
        stageNumber = FinalStatusStage.requireStageOrPairLevel(obj, "pair " + info.pairId);
        statusFromRecord = StatusCode.toStatusCode(
            FinalStatusStage.requireStatus(obj, "pair " + info.pairId)
        );
        log.debug("Extracted stageNumber from status.json: " + stageNumber);
        // Outside the catch above, so an ownership violation is not swallowed as a
        // parse warning.
        if (declaredPairId != null) {
            if (declaredPairId != pairId) {
                throw new Exception(
                    "status.json claims pair " + declaredPairId +
                        " but the container is labelled pair " + pairId +
                        "; refusing to write and keeping the container for inspection"
                );
            }
        }

        // Per-stage snapshots, for a pair run by a job script that writes them.
        //
        // Read through StageStatusSnapshots, the reader Local and Kubernetes use, so the same bytes
        // are refused the same way on every backend (#200). Bounded by the stage status.json
        // reported: every record is checked for what its bytes say, earlier stages must also hold
        // a terminal execution result, and only those earlier stages are returned. A refusal is
        // an InvalidSnapshotException, which the poll loop holds with the container rather than
        // recording as a solver failure.
        //
        // A pair-level result names no stage, so the bound is all stages rather than the one
        // the record does not name: a stage that finished keeps its own result (#165).
        Map<Integer, Integer> stageSnapshots;
        try {
            stageSnapshots = stageNumber == FinalStatusStage.PAIR_LEVEL
                ? StageStatusSnapshots.readForPairLevelResult(outputPath, pairId)
                : StageStatusSnapshots.read(outputPath, pairId, stageNumber);
        } catch (IOException e) {
            // The filesystem, not the contents: retried, as the other monitors classify it.
            throw new RetryableIngestionException(
                "Could not read the stage snapshots for pair " + pairId, e
            );
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

        // 2. Keep status.json authoritative for non-limit outcomes. Runsolver may correct
        //    a claimed clean completion with its own explicit limit verdict, but a process
        //    or container exit code alone is lifecycle information and cannot manufacture a
        //    scientific result.
        StatusCode status = determineStatus(stats, statusFromRecord);

        // 3. The legacy attributes.txt, which names no stage. Whether it is used at all is
        //    decided with the per-stage files in step 5.
        Properties attributes = parseAttributes(outputPath);

        // 4. Update database
        updateDatabase(
            pairId,
            stageNumber,
            status,
            stageSnapshots
        );

        // 5. Measurements and attributes, each against the stage that produced it. Only after
        //    updateDatabase returned: a refused status throws out of it, and nothing may be
        //    recorded against a result the database declined. The stages eligible are the ones
        //    already known to have finished -- earlier stages, whose snapshots StageStatusSnapshots
        //    required to be terminal, and the terminal stage itself. runsolver's var.out and
        //    watcher.out, read in step 1, decide the terminal status only.
        // Bounded by the reported stage: a pair-level result names no stage, so nothing is
        // published. See the same rule in LocalJobMonitor (#165).
        Set<Integer> finishedEarlier = new TreeSet<>();
        for (Integer stage : stageSnapshots.keySet()) {
            if (stage < stageNumber) {
                finishedEarlier.add(stage);
            }
        }
        recordMeasurements(pairId, outputPath, stageNumber, finishedEarlier, info.partitionIndex);
        recordAttributes(pairId, outputPath, stageNumber, finishedEarlier, attributes);

        log.info("Completed job " + pairId + " processed: status=" + status + " stageNumber=" + stageNumber);
    }

    /**
     * Parses runsolver var.out and watcher.out files.
     * Also checks for stats.json as an alternative format.
     */
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

    /**
     * Determines the job status based on runsolver stats and output files.
     */
    private StatusCode determineStatus(RunsolverStats stats, StatusCode fromStatusFile) {
        if (fromStatusFile != StatusCode.STATUS_COMPLETE) {
            return fromStatusFile;
        }

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
        return fromStatusFile;
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
        StatusCode status,
        Map<Integer, Integer> stageSnapshots
    ) throws Exception {
        // Earlier stages first. UpdatePairStatusPrecise below rewrites the terminal stage
        // and everything after it, so these survive it; writing them afterwards would
        // leave the pair briefly complete with a stale stage beside it. Each goes through
        // the stage-only routine, which touches jobpair_stage_data alone -- no pair
        // status, no job_pair_completion, no end_time -- so pair completion still fires
        // exactly once, below.
        //
        // A pair-level result names no stage, so every stage that finished is earlier than it
        // and belongs in this batch (#165). Bounding by the reported number would bound by 0
        // and discard the results the run did produce.
        int snapshotBound = stageNumber == FinalStatusStage.PAIR_LEVEL
            ? Integer.MAX_VALUE
            : stageNumber;
        Map<Integer, Integer> earlierStages = new TreeMap<>();
        for (Map.Entry<Integer, Integer> snapshot : stageSnapshots.entrySet()) {
            if (snapshot.getKey() < snapshotBound) {
                earlierStages.put(snapshot.getKey(), snapshot.getValue());
            }
        }
        StageStatusBatchResult batch =
            JobPairs.setEarlierStageStatuses(pairId, earlierStages);
        if (batch == StageStatusBatchResult.REJECTED_UNKNOWN_STAGE) {
            // The output names a stage this pair does not have. No retry changes that.
            throw new Exception(
                "Stage batch " + earlierStages + " names a stage pair " + pairId +
                    " does not have"
            );
        }
        if (batch == StageStatusBatchResult.FAILED) {
            // Nothing was written, for an infrastructure reason. The solver's results are
            // still good and still on disk; the caller keeps them and tries again.
            throw new RetryableIngestionException(
                "Could not record earlier stage statuses " + earlierStages +
                    " for pair " + pairId
            );
        }

        // The status about to be made terminal must actually be a terminal one. determineStatus
        // only ever returns such codes today, so this is a guard against a future path -- or a
        // forged artifact -- reaching the pair-level write with STATUS_PROCESSING or PAUSED,
        // which post-processing would later convert into STATUS_COMPLETE.
        if (!status.isTerminalExecutionResult()) {
            throw new Exception(
                "Refusing to record non-terminal status " + status + " as pair " + pairId +
                    " stage " + stageNumber + " result"
            );
        }

        // Stage 0 is the pair-level channel: the pair failed outside any stage, so there is no
        // stage to carry the result and it is recorded against the pair itself (#165).
        PairStatusResult statusResult = stageNumber == FinalStatusStage.PAIR_LEVEL
            ? JobPairs.setPairLevelStatusResult(
                pairId,
                status.getVal(),
                StatusCode.STATUS_NOT_REACHED.getVal())
            : JobPairs.setPairStatusPreciseResult(
                pairId,
                stageNumber,
                status.getVal(),
                StatusCode.STATUS_NOT_REACHED.getVal(),
                false
            );
        if (statusResult == PairStatusResult.REJECTED_INVALID_STAGE) {
            // The stage named is not a stage this pair has, so no retry changes it.
            //
            // InvalidSnapshotException because this is content the container produced and it
            // will read the same way forever. Not RetryableIngestionException, whose own
            // contract names "an unknown stage" as a case it must not be used for, and not a
            // plain Exception, because this is invalid terminal evidence rather than a
            // transient infrastructure failure.
            //
            // The poll loop catches this specifically and holds the container on the first
            // attempt: no retry is spent on input that cannot change, and the output survives
            // for an operator. Same type LocalJobMonitor throws for the same condition, so
            // both monitors classify it the same way.
            throw new StageStatusSnapshots.InvalidSnapshotException(
                "status.json for pair " + pairId + " reports status " + status
                    + " with stage number " + stageNumber + ", which names no stage of that"
                    + " pair; refusing to record it as that pair's precise stage result"
            );
        }
        if (statusResult == PairStatusResult.FAILED) {
            // The status never landed, and the only reasons it can fail are infrastructure
            // ones. Retryable, so the caller keeps the container and its output rather than
            // recording a solver failure that did not happen.
            throw new RetryableIngestionException(
                "Could not record terminal status " + status + " for pair " + pairId
                    + (stageNumber == FinalStatusStage.PAIR_LEVEL
                        ? " at pair level" : " stage " + stageNumber));
        }
        if (statusResult == PairStatusResult.SUPERSEDED) {
            // Someone else recorded a result first. The pair is finished; carry on and
            // let the caller release the container rather than retrying forever.
            log.info("Pair " + pairId + " already had a different terminal status;"
                + " keeping the recorded result");
        }

        // end_time is deliberately NOT set here. UpdatePairStatusPrecise writes it in the
        // same transaction as the status -- guarded on IS NULL, and outside its duplicate
        // branch precisely so a retried write repairs a pair whose first attempt died. This
        // used to call setEndTime unconditionally afterwards, which is a bare
        // "SET end_time = NOW()": harmless on the first pass, but it moved the completion
        // timestamp every time the same output was processed again. Ingestion is now
        // retryable by design, so a replay is expected rather than exceptional, and a pair's
        // recorded finish time must not drift each time one happens.

        log.debug("Updated database for pair " + pairId + ": status=" + status);
    }

    /**
     * Records runsolver measurements against the stages that produced them.
     * {@link StageStatsFiles} decides which files may be believed.
     *
     * <p>This used to record the pair-wide stats.json, merged with var.out and watcher.out,
     * against the terminal stage: every earlier stage's measurements were lost, and a final stage
     * that never reached copyOutput was given the previous stage's. A stage without a believable
     * file now keeps whatever it was enqueued with rather than any other stage's numbers.
     */
    private void recordMeasurements(
        int pairId,
        Path outputPath,
        int stageNumber,
        Set<Integer> finishedEarlier,
        int partitionIndex
    ) throws Exception {
        Map<Integer, StageStatsFiles.Stats> byStage = StageStatsFiles.select(
            outputPath,
            pairId,
            finishedEarlier,
            stageNumber,
            () -> JobPairs.getStageNumbers(pairId)
        );

        for (Map.Entry<Integer, StageStatsFiles.Stats> entry : byStage.entrySet()) {
            StageStatsFiles.Stats stats = entry.getValue();
            try {
                String nodeName = stats.hostname != null
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
                    entry.getKey(),
                    stats.diskSize
                );
                if (ok) {
                    log.debug("Persisted run stats for pair " + pairId + " stage "
                        + entry.getKey() + ": " + stats);
                } else {
                    log.warn("Failed to persist run stats for pair " + pairId + " stage "
                        + entry.getKey());
                }
            } catch (Exception e) {
                log.warn("Exception persisting run stats for pair " + pairId + " stage "
                    + entry.getKey(), e);
            }
        }
    }

    /**
     * Records post-processor attributes against the stages that produced them.
     * {@link StageAttributeFiles} decides which files may be believed.
     */
    private void recordAttributes(
        int pairId,
        Path outputPath,
        int stageNumber,
        Set<Integer> finishedEarlier,
        Properties legacy
    ) throws Exception {
        Map<Integer, Properties> byStage = StageAttributeFiles.select(
            outputPath,
            pairId,
            finishedEarlier,
            stageNumber,
            legacy,
            () -> JobPairs.getStageNumbers(pairId)
        );

        for (Map.Entry<Integer, Properties> entry : byStage.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                JobPairs.addJobPairAttributes(pairId, entry.getKey(), entry.getValue());
            }
        }
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
