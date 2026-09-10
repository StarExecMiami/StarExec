package org.starexec.backend;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.starexec.constants.R;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.StageStatusBatchResult;
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

    /**
     * Elapsed-time source for retry deadlines, monotonic rather than wall-clock.
     *
     * <p>Retry eligibility must not move when the system clock does. An NTP correction or a
     * daylight-saving step should never make a pair eligible early, and must never defer one
     * indefinitely. Injectable so tests advance it explicitly instead of sleeping.
     */
    private java.util.function.LongSupplier nanoTime = System::nanoTime;

    // Handle for the currently scheduled poll (for cancellation on interval change)
    private volatile ScheduledFuture<?> scheduledPoll;

    // One entry per pair, keyed by pair ID.
    //
    // Collapsing to a single map is itself part of the fix. This used to be three maps
    // keyed two different ways -- trackedPairs by logDir, processedPairIds and
    // statusParseFailures by pairId -- so "what this pair is currently running" was a
    // fact assembled by hand from three places. A rerun arriving mid-poll could update
    // some of them while the poller was reading the others, and the poller would then
    // write its stale conclusion over the new run: the pair ended up untracked AND
    // marked processed, which stranded it permanently.
    private final ConcurrentHashMap<Integer, PairExecutionState> pairs = new ConcurrentHashMap<>();

    // Monotonic token stamped onto every registration. A poll captures the state it
    // began with and refuses to write anything back unless the generation still
    // matches. That comparison is the entire mechanism keeping run N from clobbering
    // run N+1; nothing else here distinguishes one run of a pair from the next.
    private final AtomicLong generationSequence = new AtomicLong();

    // Consecutive polls on which status.json could not be parsed for a pair. The file is
    // written in place, so a read can land mid-write; that is transient and must not be
    // recorded as a failed run. A file that is genuinely corrupt must not be retried
    // forever either, so give up after this many attempts. The count lives inside
    // PairExecutionState so that it is reset by a rerun as one atomic act with
    // everything else, rather than as a separate mutation that could be missed.
    private static final int MAX_STATUS_PARSE_FAILURES = 3;

    /** Ceiling for ingestion retry backoff: a long outage settles into occasional checks. */
    private static final long MAX_INGESTION_BACKOFF_MS = 300_000L;

    /**
     * Immutable snapshot of one pair's current execution.
     *
     * <p>Immutability is deliberate. A poll holds the instance it started with and
     * compares generations before writing anything back, so there is no window in
     * which a half-updated state is observable. Every mutation goes through
     * {@link ConcurrentHashMap#computeIfPresent}, which is atomic per key.
     */
    private static final class PairExecutionState {
        final String logDir;
        final long generation;
        final int parseFailures;

        /**
         * Ingestion retry state, deliberately carried here rather than in a map keyed by
         * pair id. A pair can be rerun, and a rerun replaces this whole record -- so a new
         * generation starts at zero failures with no inherited deadline and no inherited
         * blocked flag, and the superseded generation's retry state is discarded with it.
         * Keying on pair id alone would leak one run's backoff onto the next.
         */
        final int ingestionFailures;
        final long nextRetryAtNanos;
        final boolean ingestionBlocked;
        final String lastIngestionFailure;

        PairExecutionState(String logDir, long generation, int parseFailures) {
            this(logDir, generation, parseFailures, 0, 0L, false, null);
        }

        PairExecutionState(
                String logDir,
                long generation,
                int parseFailures,
                int ingestionFailures,
                long nextRetryAtNanos,
                boolean ingestionBlocked,
                String lastIngestionFailure) {
            this.logDir = logDir;
            this.generation = generation;
            this.parseFailures = parseFailures;
            this.ingestionFailures = ingestionFailures;
            this.nextRetryAtNanos = nextRetryAtNanos;
            this.ingestionBlocked = ingestionBlocked;
            this.lastIngestionFailure = lastIngestionFailure;
        }

        PairExecutionState withParseFailures(int failures) {
            return new PairExecutionState(logDir, generation, failures,
                    ingestionFailures, nextRetryAtNanos, ingestionBlocked, lastIngestionFailure);
        }

        PairExecutionState withIngestionRetry(long dueAtNanos, String cause) {
            return new PairExecutionState(logDir, generation, parseFailures,
                    ingestionFailures + 1, dueAtNanos, false, cause);
        }

        PairExecutionState blockedForIngestion(String cause) {
            return new PairExecutionState(logDir, generation, parseFailures,
                    ingestionFailures + 1, nextRetryAtNanos, true, cause);
        }
    }

    /**
     * True if {@code pairId} is still on the generation the caller started with, i.e.
     * no rerun has superseded the work in flight.
     */
    private boolean isCurrent(int pairId, PairExecutionState state) {
        PairExecutionState current = pairs.get(pairId);
        return current != null && current.generation == state.generation;
    }

    /**
     * Stops tracking a pair, but only if it has not been re-registered since the caller
     * captured {@code state}.
     *
     * @return true if this call removed the pair; false if a rerun had superseded it,
     *         in which case the caller's result belongs to a run that no longer matters
     */
    private boolean retire(int pairId, PairExecutionState state) {
        final boolean[] retired = { false };
        pairs.computeIfPresent(pairId, (key, current) -> {
            if (current.generation == state.generation) {
                retired[0] = true;
                return null; // returning null removes the entry
            }
            return current;
        });
        return retired[0];
    }

    /**
     * Increments this pair's consecutive parse-failure count.
     *
     * @return the new count, or -1 if a rerun superseded this run, meaning the failure
     *         belongs to output that is no longer of interest
     */
    private int recordParseFailure(int pairId, PairExecutionState state) {
        final int[] failures = { -1 };
        pairs.computeIfPresent(pairId, (key, current) -> {
            if (current.generation != state.generation) {
                return current;
            }
            PairExecutionState next = current.withParseFailures(current.parseFailures + 1);
            failures[0] = next.parseFailures;
            return next;
        });
        return failures[0];
    }

    /**
     * Records the terminal status of every stage before the one that finished the pair.
     *
     * <p>Writes through the stage-only routine, which touches {@code jobpair_stage_data}
     * alone: no pair status, no completion, no end time. Pair completion stays exactly where
     * it was, in the single precise update that follows this. Order matters --
     * {@code UpdatePairStatusPrecise} rewrites the terminal stage and everything after it, so
     * earlier stages have to be in place first.
     *
     * <p>Throws rather than returning a flag, so every failure reaches the outer lifecycle and
     * is classified there. A snapshot this monitor cannot believe is an evidence problem; it
     * must never become a solver status.
     */
    private void ingestEarlierStageStatuses(
            int pairId,
            PairExecutionState state,
            Path outputDir,
            int terminalStage) throws Exception {

        // The terminal stage's own snapshot is not used -- its status comes from the runsolver
        // artifacts -- and while a stage is still running its snapshot legitimately reads
        // STATUS_RUNNING. Both are expressed by the bound, so the read never returns a record
        // this method would have to discard. Selecting here as well would put the rule in two
        // places, which is how the running stage came to be validated at all.
        Map<Integer, Integer> earlier =
                StageStatusSnapshots.read(outputDir, pairId, terminalStage);

        if (earlier.isEmpty()) {
            return;
        }

        // The mutation boundary. Everything above read files; a rerun that landed while it
        // did has already superseded this result, and writing it would record run N's stage
        // history against run N+1.
        if (!isCurrent(pairId, state)) {
            log.info("Monitor: pairId=" + pairId + " was rerun while its stage snapshots were"
                    + " read; discarding the superseded run's stage history");
            return;
        }

        StageStatusBatchResult result = JobPairs.setEarlierStageStatuses(pairId, earlier);
        if (result == StageStatusBatchResult.APPLIED) {
            return;
        }
        if (result == StageStatusBatchResult.REJECTED_UNKNOWN_STAGE) {
            // The output names a stage this pair does not have. No retry changes that, so it
            // is surfaced as a permanently invalid artifact rather than a transient failure.
            throw new StageStatusSnapshots.InvalidSnapshotException(
                    "stage batch " + earlier + " names a stage pair " + pairId + " does not have");
        }
        // FAILED. StageStatusBatchResult already separated a missing routine from a transient
        // fault at the point where the SQLState was still visible, and logged which it was.
        throw new org.starexec.backend.exception.RetryableIngestionException(
                "could not record earlier stage statuses " + earlier + " for pair " + pairId);
    }

    /**
     * Records that a completed pair's results could not be ingested, and decides what next.
     *
     * <p>Generation-guarded throughout: if the pair has been rerun while this poll ran, the
     * failure belongs to output that no longer matters, so the superseded record is retired
     * and the new generation is left entirely alone -- no inherited failure count, no
     * inherited deadline, no inherited blocked flag.
     *
     * <p>No number of failures ever produces a solver status. A retry ceiling that ended in
     * {@code ERROR_RUNSCRIPT} would just be the original defect with extra steps.
     */
    private void recordIngestionFailure(int pairId, PairExecutionState state, Exception cause) {
        if (!isCurrent(pairId, state)) {
            log.warn("Monitor: ingestion failed for pairId=" + pairId
                    + " but it has since been rerun; discarding the superseded run's failure");
            retire(pairId, state);
            return;
        }

        IngestionOutcome outcome = IngestionOutcome.classify(cause);
        String summary = cause.getClass().getSimpleName() + ": " + cause.getMessage();

        if (outcome == IngestionOutcome.BLOCKED) {
            pairs.computeIfPresent(pairId, (key, current) ->
                    current.generation == state.generation
                            ? current.blockedForIngestion(summary)
                            : current);
            log.error("Monitor: INGESTION REQUIRES INTERVENTION for pairId=" + pairId
                    + " in " + state.logDir + ". The results are retained and the pair is left"
                    + " unresolved rather than given a status it did not earn. Cause: "
                    + summary, cause);
            return;
        }

        long delayMs = ingestionBackoffMillis(state.ingestionFailures + 1);
        long dueAt = nanoTime.getAsLong() + delayMs * 1_000_000L;
        pairs.computeIfPresent(pairId, (key, current) ->
                current.generation == state.generation
                        ? current.withIngestionRetry(dueAt, summary)
                        : current);
        log.warn("Monitor: could not record results for pairId=" + pairId + " (attempt "
                + (state.ingestionFailures + 1) + "); results retained, retrying in "
                + delayMs + "ms. Cause: " + summary, cause);
    }

    /**
     * Bounded exponential backoff, derived from the poller's own cadence rather than invented.
     *
     * <p>Starts at the base poll interval, because retrying faster than the loop runs is
     * pointless, and caps at ten times the maximum so a long outage settles into occasional
     * checks instead of a hot loop.
     */
    private long ingestionBackoffMillis(int failures) {
        long base = Math.max(1L, pollInterval.getBaseInterval());

        // The cap is bounded by MAX_INGESTION_BACKOFF_MS rather than taken from the poller
        // alone, and the reason is worth recording. getMaxInterval() is documented in
        // AdaptivePollInterval's own javadoc as defaulting to 10000ms, but
        // EnvironmentConfig.getAdaptivePollMaxInterval() actually defaults to 120000ms --
        // the javadoc is stale. A "ten times the poll maximum" ceiling is therefore twenty
        // minutes, not one hundred seconds, which is how the first version of this doubled
        // past 512s without ever capping. Deriving from the cadence is still right; trusting
        // it unbounded is not.
        long cap = Math.max(base, Math.min(
                pollInterval.getMaxInterval() * 10L, MAX_INGESTION_BACKOFF_MS));

        // Clamped BEFORE the subtraction, which is the part that is easy to get wrong:
        // clamping afterwards still evaluates failures - 1 first, and for
        // Integer.MIN_VALUE that wraps to Integer.MAX_VALUE, so a nonsensical count came
        // back as the maximum delay rather than the minimum. Java also masks a shift
        // distance to (n & 63), so a negative shift would silently produce an enormous
        // delay instead of an error.
        int attempt = Math.max(1, Math.min(failures, 41));
        int shift = attempt - 1;
        long delay = base << shift;
        return delay <= 0 || delay > cap ? cap : delay;
    }

    /** Resets the ingestion retry state after a successful pass, generation permitting. */
    private void clearIngestionFailures(int pairId, PairExecutionState state) {
        pairs.computeIfPresent(pairId, (key, current) ->
                current.generation == state.generation && current.ingestionFailures != 0
                        ? new PairExecutionState(current.logDir, current.generation,
                                current.parseFailures)
                        : current);
    }

    /** Resets the parse-failure count after a successful read, generation permitting. */
    private void clearParseFailures(int pairId, PairExecutionState state) {
        pairs.computeIfPresent(pairId, (key, current) ->
                current.generation == state.generation && current.parseFailures != 0
                        ? current.withParseFailures(0)
                        : current);
    }

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
        // A fresh generation supersedes whatever was in flight: a poll that started
        // before this point finds its generation stale and declines to record its
        // result. This single put replaces what used to be three separate mutations
        // (track the directory, clear the processed marker, clear the parse-failure
        // count), any prefix of which the poller could previously observe.
        //
        // Still synchronized: incrementAndGet and put are individually atomic but not
        // atomic together, so two concurrent registrations of the same pair could
        // otherwise store the lower generation last and leave the map describing an
        // older run than the one actually starting.
        long generation = generationSequence.incrementAndGet();
        pairs.put(pairId, new PairExecutionState(logDir, generation, 0));

        // Reset poll interval to base for responsive detection of new job completion
        pollInterval.resetToBase();

        // Cancel the current scheduled poll and reschedule immediately with the reset
        // interval.
        cancelAndRescheduleNow();

        log.debug(
                "Registered job for monitoring: pairId=" +
                        pairId +
                        ", logDir=" +
                        logDir +
                        ", generation=" +
                        generation);
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
        // One removal now clears everything this pair carried: its directory, its
        // processed status and its parse-failure count were three separate facts and
        // are now one entry. A poll already in flight for the removed generation will
        // find no entry to write back to and discards its result, which is exactly the
        // outcome wanted -- that result describes the run being replaced.
        PairExecutionState removed = pairs.remove(pairId);

        if (removed != null) {
            log.info(
                    "Cleared tracking for pairId=" +
                            pairId +
                            ": logDir=" +
                            removed.logDir +
                            ", generation=" +
                            removed.generation +
                            ". Monitor will reprocess status files on next poll.");
        } else {
            log.warn(
                    "No tracking entry for pairId=" +
                            pairId +
                            ". Pair may not have been registered with monitor yet.");
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

            for (Map.Entry<Integer, PairExecutionState> entry : pairs.entrySet()) {
                int pairId = entry.getKey();
                // The state captured here is what every write below is checked against.
                // Holding it, rather than re-reading the map, is what makes "has this
                // pair been rerun since I started?" answerable at all.
                PairExecutionState state = entry.getValue();
                String logDir = state.logDir;
                checkedCount++;

                Path statusFile = Paths.get(logDir).resolve("status.json");

                if (!Files.exists(statusFile)) {
                    log.trace(
                            "Monitor: No status.json yet for pairId=" +
                                    pairId +
                                    ", logDir=" +
                                    logDir);
                    continue;
                }

                // Held back by an earlier ingestion failure. Checked before the pair is
                // counted as found, so a pair waiting out its backoff does not keep the
                // adaptive poller pinned at its base interval.
                if (state.ingestionBlocked) {
                    log.debug("Monitor: pairId=" + pairId + " is held for intervention;"
                            + " not reprocessing. Last cause: " + state.lastIngestionFailure);
                    continue;
                }
                if (state.ingestionFailures > 0
                        && nanoTime.getAsLong() - state.nextRetryAtNanos < 0) {
                    // Subtraction rather than <, so the comparison is correct across a
                    // nanoTime rollover.
                    continue;
                }

                foundCount++;
                // A pair present in the map is by definition not yet processed: a
                // terminal result retires the entry. The separate processedPairIds set
                // that used to answer this question is gone, and with it the state in
                // which a pair was both untracked and marked processed.
                log.debug("Monitor: found status.json for pairId=" + pairId
                        + ", generation=" + state.generation + ", logDir=" + logDir);

                try {
                    boolean isTerminal = processCompletedJob(pairId, state);
                    // A pass that got through clears any backoff this pair had accumulated:
                    // a still-running pair must not inherit an earlier failure's deadline.
                    clearIngestionFailures(pairId, state);
                    if (!isTerminal) {
                        log.debug("Monitor: Job still running for pairId=" + pairId + ", will re-check later.");
                    } else if (retire(pairId, state)) {
                        log.info("Monitor: Successfully processed pairId=" + pairId);
                    } else {
                        log.info("Monitor: pairId=" + pairId + " was rerun while this poll ran;"
                                + " discarding the superseded run's result and leaving the new run tracked");
                    }
                } catch (Exception e) {
                    // A failure to RECORD a result is not a result.
                    //
                    // This used to mark the pair -- and, through setStatusForPairAndStages,
                    // every one of its stages -- ERROR_RUNSCRIPT and then retire it. A
                    // database that was briefly unavailable therefore turned a good solver
                    // run into a recorded scientific failure. When the database was the
                    // thing that was down, the fabricated write failed too and the pair was
                    // retired with no terminal status at all: stranded, with nothing left
                    // tracking it and no later poll that would ever look again.
                    //
                    // Neither outcome describes the solver, so neither is recorded as one.
                    recordIngestionFailure(pairId, state, e);
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

    private boolean processCompletedJob(int pairId, PairExecutionState state)
            throws Exception {
        Path outputDir = Paths.get(state.logDir);

        // 1. Read status and stageNumber from status.json (single Gson parse)
        StatusAndStage ss = readStatusFile(outputDir, pairId);
        if (ss == null) {
            // Not readable yet, most likely read while the producer was writing it.
            // Leave the pair tracked and unprocessed so the next poll tries again, and
            // only call it a failure once it has stayed unreadable for several polls --
            // a file that is genuinely corrupt must not be retried forever either.
            int failures = recordParseFailure(pairId, state);
            if (failures < 0) {
                log.info("status.json for pairId=" + pairId + " was unreadable, but the pair"
                        + " has been rerun since; discarding the superseded run");
                return false;
            }
            if (failures < MAX_STATUS_PARSE_FAILURES) {
                log.info("status.json for pairId=" + pairId + " unreadable ("
                        + failures + "/" + MAX_STATUS_PARSE_FAILURES + "); retrying next poll");
                return false;
            }
            // NOT a solver status. This used to synthesise ERROR_RUNSCRIPT here, which is
            // the same "N failures becomes a scientific result" pattern removed from the
            // outer catch, one layer further in. A status file that will not parse is an
            // evidence problem: the solver may have run perfectly and the platform simply
            // cannot read what it wrote.
            //
            // Thrown as a deterministic invalid artifact, so the outer lifecycle marks the
            // pair INGESTION_BLOCKED, keeps its output, logs actionably and leaves the pair
            // unresolved. A transient read failure never reaches this point: it returns
            // above and is retried.
            throw new StageStatusSnapshots.InvalidSnapshotException(
                    "status.json for pair " + pairId + " has been unreadable for " + failures
                            + " consecutive polls; refusing to invent a result for it");
        } else {
            clearParseFailures(pairId, state);
        }

        // 2. Parse runsolver stats if available
        RunSolverStats stats = parseRunSolverStats(outputDir);

        // 3. Parse attributes if post-processor ran
        Properties attributes = parseAttributes(outputDir);

        // 4. Re-check the generation immediately before writing. Everything above reads
        //    files and takes real time; a rerun that landed during it has already
        //    superseded this result, and writing it would record run N's outcome
        //    against run N+1 -- a wrong recorded result, not merely a stranded pair.
        //
        //    This narrows the window to the width of the check-then-write; it cannot
        //    close it from inside this process. UpdatePairStatusPrecise refusing to
        //    overwrite an already-terminal status without _forceOverride is the
        //    backstop for what remains.
        if (!isCurrent(pairId, state)) {
            log.info("pairId=" + pairId + " was rerun while its output was being read;"
                    + " discarding the superseded run's result rather than recording it");
            return false;
        }

        // 5. Earlier stages, from the per-stage snapshots the job script writes beside
        //    status.json. That file is one slot the stages take turns truncating, so
        //    without this the pair keeps only its last stage and every earlier one stays
        //    at whatever it was enqueued with.
        //
        //    The pair comes from this monitor's own tracking, never from the files: a
        //    snapshot's pairId is validation data, not routing authority. Ownership is
        //    re-checked inside, immediately before the write, for the same reason step 4
        //    re-checks it -- reading files takes real time and a rerun may have landed.
        ingestEarlierStageStatuses(pairId, state, outputDir, ss.stageNumber);

        // 6. Update database, with runsolver's verdict allowed to correct the status
        //    bash derived by grepping prose.
        updateDatabase(
            pairId,
            reconcileWithRunsolver(pairId, ss.status, stats),
            ss.stageNumber,
            stats,
            attributes
        );

        // 6. Report whether this run reached a terminal status. Retiring the pair is the
        //    caller's job, so that the removal is generation-guarded in one place.
        // isTerminalExecutionResult, not finishedRunning. The latter is val >= 7, which is
        // true of STATUS_PROCESSING_RESULTS(19), STATUS_PAUSED(20) and STATUS_PROCESSING(22) --
        // three states that mean work is still owed. Retiring on any of them would stop
        // tracking a pair that has not finished, and it is the same predicate whose divergence
        // from the database contract allowed status laundering elsewhere.
        if (ss.status.isTerminalExecutionResult()) {
            log.info("Job execution finished for pairId=" + pairId + " with status=" + ss.status);
            return true;
        } else {
            log.debug("Job still running (status=" + ss.status + "), continuing to monitor pairId=" + pairId);
            return false;
        }
    }

    /**
     * Lets runsolver's own verdict correct a status that bash derived by grepping prose.
     *
     * <p>The status in status.json is not an independent observation. {@code jobscript}
     * decides it with {@code grep 'CPU time exceeded' "$WATCHFILE"} and two siblings —
     * the same English sentences the Java parser used to depend on, one layer earlier. If
     * the wording ever changes, every one of those greps misses, status.json says the run
     * completed, and {@code TIMEOUT=true} sits unread in var.out beside it.
     *
     * <p>So when bash reports a clean completion and runsolver reports a limit breach,
     * runsolver wins: it computed its verdict against the limits it enforced, not against
     * a sentence it printed.
     *
     * <p>The override is deliberately one-directional. Any status other than
     * {@code STATUS_COMPLETE} is left alone, because bash sees things runsolver cannot —
     * {@code JOB_PAIR_DEADLOCKED}, {@code ERROR_DISK_QUOTA_EXCEEDED}, and the
     * {@code job error:} marker in the solver's stderr are all conditions with no
     * representation in var.out at all. Runsolver is authoritative for the three limits
     * it enforces, and for nothing else.
     */
    private StatusCode reconcileWithRunsolver(
        int pairId,
        StatusCode fromStatusFile,
        RunSolverStats stats
    ) {
        if (fromStatusFile != StatusCode.STATUS_COMPLETE) {
            return fromStatusFile;
        }

        StatusCode limit = RunsolverVerdict.classify(
            stats.timeout,
            stats.memout,
            stats.cpuExceeded,
            stats.wallclockExceeded,
            stats.memoryExceeded
        );
        if (limit == null) {
            return fromStatusFile;
        }

        log.warn(
            "pairId=" + pairId + ": status.json reported STATUS_COMPLETE but runsolver" +
            " reported a limit breach (TIMEOUT=" + stats.timeout + ", MEMOUT=" +
            stats.memout + "); recording " + limit + " instead. The bash status is" +
            " derived by grepping watcher.out prose, so this usually means the prose" +
            " did not match."
        );
        return limit;
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

        // The three sources are complementary, not alternatives, so all three are read.
        //
        // This used to return as soon as stats.json parsed -- the same defect fixed in
        // ContainerJobMonitor by 316668fd2 -- which left var.out and watcher.out
        // unread whenever stats.json existed. It also meant a stats.json that parsed
        // but was missing fields silently produced zeros, because extractDouble returns
        // 0.0 on no match, with runsolver's own var.out sitting unread beside it.
        //
        // Order matters: var.out and watcher.out first, stats.json last so it stays
        // authoritative for the fields it carries, and the stats.json application below
        // only overwrites a field when the key is actually present.

        // var.out (runsolver's -v output). Keys quoted from RunSolverSource/Watcher.hh:
        //   :454 "WCTIME="   :457 "CPUTIME="   :460 "USERTIME="
        //   :463 "SYSTEMTIME="   :469 "MAXVM="
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
                    } else if (line.startsWith("TIMEOUT=")) {
                        // Watcher.hh:472, written with boolalpha so the value is the
                        // lowercase word true/false. The prefix match also keeps us off
                        // the "# TIMEOUT: ..." comment line above it.
                        stats.timeout = Boolean.parseBoolean(
                                line.substring("TIMEOUT=".length()).trim());
                    } else if (line.startsWith("MEMOUT=")) {
                        // Watcher.hh:475, same form.
                        stats.memout = Boolean.parseBoolean(
                                line.substring("MEMOUT=".length()).trim());
                    }
                }
                log.debug("Parsed stats from var.out: " + stats);
            } catch (IOException | NumberFormatException e) {
                log.warn("Failed to parse var.out", e);
            }
        }

        // watcher.out (runsolver's -w output). RunSolverSource/Watcher.hh:396 writes
        //   cout << "maximum resident set size= " << r.ru_maxrss
        // with an EQUALS sign. functions.bash:869 reads the same line with
        // awk '{print $5}', which agrees. A parser written against a colon here would
        // silently record 0 -- that mistake was made in ContainerJobMonitor and fixed
        // in 623dd295e.
        Path watcherFile = outputDir.resolve("watcher.out");
        if (Files.exists(watcherFile)) {
            try {
                Pattern rss = Pattern.compile(
                        "maximum resident set size=\\s*(\\d+)");
                for (String line : Files.readAllLines(watcherFile)) {
                    Matcher m = rss.matcher(line);
                    if (m.find()) {
                        stats.maxResidentSetSize = Long.parseLong(m.group(1));
                    }
                    // Prose from stopSolver (Watcher.hh:717-726). Used only to say which
                    // limit fired; TIMEOUT=/MEMOUT= in var.out say whether one did.
                    if (line.contains("CPU time exceeded")) {
                        stats.cpuExceeded = true;
                    } else if (line.contains("wall clock time exceeded")) {
                        stats.wallclockExceeded = true;
                    } else if (line.contains("VSize exceeded")
                            || line.contains("Maximum memory exceeded")) {
                        stats.memoryExceeded = true;
                    }
                }
            } catch (IOException | NumberFormatException e) {
                log.warn("Failed to parse watcher.out", e);
            }
        }

        // stats.json last (written by containerWriteStats, functions.bash:47). It
        // carries timings and sizes only -- no exit code and no limit information.
        Path statsJson = outputDir.resolve("stats.json");
        if (Files.exists(statsJson)) {
            try {
                String json = Files.readString(statsJson);
                // Each of these keeps the value already read above when the key is
                // absent, so a truncated stats.json degrades to var.out instead of
                // zeroing a measurement that was successfully read.
                stats.wallclockTime = extractDoubleOr(
                        json, "wallclockTime", stats.wallclockTime);
                stats.cpuTime = extractDoubleOr(json, "cpuTime", stats.cpuTime);
                stats.userTime = extractDoubleOr(json, "userTime", stats.userTime);
                stats.systemTime = extractDoubleOr(
                        json, "systemTime", stats.systemTime);
                stats.maxVirtualMemory = extractDoubleOr(
                        json, "maxVirtualMemory", stats.maxVirtualMemory);
                stats.maxResidentSetSize = extractLongOr(
                        json, "maxResidentSetSize", stats.maxResidentSetSize);
                stats.stageNumber = extractIntOr(
                        json, "stageNumber", stats.stageNumber);

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
            } catch (IOException | NumberFormatException e) {
                // NumberFormatException for the same reason var.out and watcher.out catch
                // it above. The ...Or helpers match on [0-9.]+ / [0-9]+, which admits
                // "1.2.3", "." and digit strings past Integer/Long range -- a garbled
                // value throws out of the parse instead of failing to match, so without
                // this it escapes parseRunSolverStats entirely. Aborting here leaves every
                // remaining field at the value var.out and watcher.out already established
                // (the fallback each extractOr was handed), so the supplemental source
                // degrades on its own rather than taking the measurement down with it.
                log.warn(
                        "Failed to parse stats.json; using var.out and watcher.out only",
                        e);
            }
        }

        return stats;
    }

    /**
     * Returns the JSON value for {@code key}, or {@code fallback} when the key is
     * absent.
     *
     * <p>Distinct from {@link #extractDouble}, which cannot tell "absent" from "zero"
     * and so would overwrite a good value with 0 when a field is missing.
     */
    private double extractDoubleOr(String json, String key, double fallback) {
        Matcher m = Pattern.compile(
                "\"" + key + "\"\\s*:\\s*([0-9.]+)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : fallback;
    }

    /** @see #extractDoubleOr */
    private long extractLongOr(String json, String key, long fallback) {
        Matcher m = Pattern.compile(
                "\"" + key + "\"\\s*:\\s*([0-9]+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : fallback;
    }

    /** @see #extractDoubleOr */
    private int extractIntOr(String json, String key, int fallback) {
        Matcher m = Pattern.compile(
                "\"" + key + "\"\\s*:\\s*([0-9]+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    /**
     * Only still used for diskSize, where 0 and absent are treated alike because the
     * caller guards with {@code if (ds > 0)}. Everything else goes through the
     * {@code ...Or} variants, which can tell the two apart.
     */
    private long extractLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9]+)").matcher(
                json);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        return 0L;
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
        // status.json comes from the job script, in a directory the job itself can write. The
        // protocol has it carry exactly two kinds of pair-level status: STATUS_RUNNING while a
        // stage is in flight, and a terminal execution result once one finishes. Anything else
        // did not come from the protocol, whatever produced it.
        //
        // The three that matter are STATUS_PROCESSING_RESULTS(19), STATUS_PAUSED(20) and
        // STATUS_PROCESSING(22). They mean work is still owed, and a pair left at 22 is selected
        // by the periodic post-processing task, which then sets it to STATUS_COMPLETE -- so a job
        // able to write its own pair status could have a timeout laundered into a clean
        // completion. ContainerJobMonitor guards its pair-level write for exactly this reason;
        // this path did not, and StageStatusSnapshots guards only the per-stage channel.
        //
        // Not finishedRunning() and not a numeric range: the authority is the enumerated
        // predicate the database also enforces, so the two cannot drift.
        if (status != StatusCode.STATUS_RUNNING && !status.isTerminalExecutionResult()) {
            // Deterministic: the same bytes fail the same check on every poll, so this is an
            // artifact defect rather than a transient one. The outer lifecycle holds the pair
            // and keeps its output instead of retrying in a loop or inventing a result.
            throw new StageStatusSnapshots.InvalidSnapshotException(
                    "refusing to record status " + status + " for pair " + pairId
                            + ": a pair-level status may only be STATUS_RUNNING or a terminal"
                            + " execution result");
        }

        log.info(
                "Updating database for pairId=" + pairId + " with status=" + status
                + " stageNumber=" + stageNumber);
        PairStatusResult statusResult = JobPairs.setPairStatusPreciseResult(
                pairId,
                stageNumber,
                status.getVal(),
                StatusCode.STATUS_NOT_REACHED.getVal(),
                false);
        if (statusResult == PairStatusResult.REJECTED_INVALID_STAGE) {
            // status.json named no stage. The job script's pair-level channel defaults to 0
            // -- exitJobscript, limitExceeded and the processor paths all take that default
            // -- and 0 cannot be translated into a precise stage identity without giving
            // NOT_REACHED to every stage the pair has.
            //
            // Thrown rather than logged, and thrown here rather than later, for two reasons.
            // It is deterministic, so IngestionOutcome classifies it BLOCKED and the pair is
            // held with its output instead of retried against bytes that will not change.
            // And the attribute and statistics writes below are unconditional: returning or
            // merely logging would record this pair's measurements against a status the
            // database refused.
            throw new StageStatusSnapshots.InvalidSnapshotException(
                    "status.json for pair " + pairId + " reports status " + status
                            + " with stage number " + stageNumber + ", which names no stage;"
                            + " refusing to record it as that pair's precise stage result");
        }
        if (statusResult == PairStatusResult.FAILED) {
            // Not recorded. The only reasons UpdatePairStatusPrecise reports FAILED are
            // infrastructure ones -- the SQLException behind it is logged and swallowed
            // inside JobPairs, so it cannot be inspected here and the type carries the
            // classification instead. Throwing leaves the pair tracked and scheduled for
            // retry; it must never become a solver status.
            throw new org.starexec.backend.exception.RetryableIngestionException(
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
        return pairs.size();
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
        /** runsolver's own limit verdicts from var.out (Watcher.hh:471-475). */
        public boolean timeout = false;
        public boolean memout = false;
        /** Set from watcher.out prose; discriminates which limit fired. */
        public boolean cpuExceeded = false;
        public boolean wallclockExceeded = false;
        public boolean memoryExceeded = false;
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
