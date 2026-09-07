/*
 * KubernetesJobMonitor - Watches Kubernetes Jobs for completion
 * 
 * ============================================================================
 * SCAFFOLDING FOR FUTURE DEVELOPMENT - December 2025
 * ============================================================================
 * 
 * This component monitors Kubernetes Job resources for completion and updates
 * the StarExec database accordingly. The current implementation uses a polling
 * loop; informer-based monitoring remains future work.
 * 
 * Current Design: Polling with a future informer migration path
 * ─────────────────────────────────────────────────────────────
 * The monitor currently lists StarExec-managed Jobs at a fixed interval and
 * emits completion callbacks once a Job reports success or failure.
 * 
 * Architecture:
 * ┌─────────────────────────────────────────────────────────────────────────────┐
 * │                        KubernetesJobMonitor                                  │
 * │  ┌───────────────────────────────────────────────────────────────────────┐  │
 * │  │  Polling loop                                                          │  │
 * │  │    - Lists StarExec-managed Job resources                              │  │
 * │  │    - Sleeps between checks                                             │  │
 * │  └───────────────────────────────────────────────────────────────────────┘  │
 * │                                    ↓                                         │
 * │  ┌───────────────────────────────────────────────────────────────────────┐  │
 * │  │  Completion detection                                                  │  │
 * │  │    - Checks status.succeeded / status.failed                           │  │
 * │  │    - Avoids duplicate callbacks per execution ID                       │  │
 * │  └───────────────────────────────────────────────────────────────────────┘  │
 * │                                    ↓                                         │
 * │  ┌───────────────────────────────────────────────────────────────────────┐  │
 * │  │  Completion Handler                                                    │  │
 * │  │    - Read output files from PVC (status.json, stats.json, etc.)       │  │
 * │  │    - Parse job results                                                 │  │
 * │  │    - Update database via JobPairs API                                  │  │
 * │  │    - Clean up completed Job resource                                   │  │
 * │  └───────────────────────────────────────────────────────────────────────┘  │
 * └─────────────────────────────────────────────────────────────────────────────┘
 * 
 * Job Completion Detection:
 * ─────────────────────────
 * A Kubernetes Job is complete when:
 *   - job.status.succeeded >= 1 (successful completion)
 *   - job.status.failed >= 1 (failed completion)
 *   - job.status.conditions contains type=Complete or type=Failed
 * 
 * Output File Handling:
 * ─────────────────────
 * Jobs write output to the shared PVC at:
 *   /app/data/jobs/{jobId}/{pairId}/
 *     - status.json     (completion status)
 *     - stats.json      (resource usage stats)
 *     - attributes.txt  (post-processor output)
 *     - stdout.txt      (captured stdout)
 *     - stderr.txt      (captured stderr)
 * 
 * The monitor reads these files to update the StarExec database.
 * 
 * Error Handling:
 * ───────────────
 *   - Network disconnection: Next poll retries the list operation
 *   - Job stuck: TTL-based cleanup via ttlSecondsAfterFinished
 *   - Parse errors: Log and mark job as failed
 * 
 * Future TODOs:
 *   - [ ] Implement with fabric8 SharedInformerFactory
 *   - [ ] Add metrics for monitoring (jobs_completed, jobs_failed, etc.)
 *   - [ ] Implement graceful shutdown with informer stop
 *   - [ ] Add leader election for HA deployment
 *   - [ ] Consider using a separate pod for monitoring (sidecar pattern)
 * 
 * @author StarExec Team
 * @since 2.0.0
 */

package org.starexec.backend;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.starexec.logger.StarLogger;

/**
 * Monitors Kubernetes Jobs for completion and updates StarExec database.
 * 
 * <p>Uses polling today; informer-based monitoring is still planned.</p>
 * 
 * <p><b>Status: EXPERIMENTAL</b> - Polling implementation is active; informer migration is pending.</p>
 */
public class KubernetesJobMonitor {
    
    private static final StarLogger log = StarLogger.getLogger(KubernetesJobMonitor.class);
    
    // =========================================================================
    // Configuration
    // =========================================================================
    
    /** Label key used for all StarExec-managed Kubernetes Jobs */
    private static final String MANAGED_LABEL_KEY = "starexec.org/managed";

    /** Label key that stores StarExec execution ID */
    private static final String EXEC_ID_LABEL_KEY = "starexec.org/exec-id";

    /** Poll interval while informer integration is pending */
    private static final long POLL_INTERVAL_MS = 5_000;

    /**
     * How long a pod may stay Pending before it is worth an operator's attention.
     *
     * <p>Five minutes clears normal scheduling latency and a cold image pull without ever
     * firing on a healthy cluster.
     */
    public static final int DEFAULT_PENDING_WARN_MINUTES = 5;

    /**
     * How long a pod may stay Pending before its pair is failed for rerun.
     *
     * <p>An hour rides out a short drain but surfaces a mislabelled queue the same working
     * day. Zero disables the transition and leaves only the warning.
     */
    public static final int DEFAULT_PENDING_TIMEOUT_MINUTES = 60;

    // =========================================================================
    // Dependencies
    // =========================================================================
    
    /** Kubernetes client */
    private final KubernetesClient kubernetesClient;
    
    /** Namespace to watch */
    private final String namespace;
    
    /** Callback for job completion */
    private final JobCompletionCallback callback;
    
    // =========================================================================
    // Runtime State
    // =========================================================================
    
    /** Running flag */
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Every collection below is keyed on {@link ExecutionRef}, not on the execution id.
    // The id restarts at 1 in each application lifetime, so a second Job can be given one a
    // first Job's entry is still sitting under; keyed that way, state recorded for the
    // earlier execution decided the later one's events before the backend ever saw them.

    /** Tracks completion callbacks already emitted to avoid duplicate updates */
    private final Set<ExecutionRef> completedExecutions = ConcurrentHashMap.newKeySet();

    /** Tracks jobs already transitioned to STATUS_RUNNING */
    private final Set<ExecutionRef> runningExecutions = ConcurrentHashMap.newKeySet();

    /** When each stuck-pending execution was last warned about, for throttling */
    private final Map<ExecutionRef, Long> pendingWarnedAt = new ConcurrentHashMap<>();

    /**
     * Executions judged stuck whose transition did not complete, with the reason they
     * were judged stuck.
     *
     * <p>The judgement is not revisited once made. By the time a transition can fail
     * part-way the pair may already carry ERROR_RUNSCRIPT and an end_time, which makes it
     * eligible for RERUN_FAILED_PAIRS; if its pod then started and the monitor let it be
     * reclassified as an ordinary running pair, nothing would ever delete the Job and that
     * pod could write results alongside the rerun.
     */
    private final Map<ExecutionRef, String> cleanupPending =
        new ConcurrentHashMap<>();

    /** How long a pod may be Pending before it is logged, in milliseconds */
    private final long pendingWarnMillis;

    /**
     * How long a pod may be Pending before its pair is failed for rerun, in milliseconds.
     * Zero means never.
     */
    private final long pendingTimeoutMillis;

    /**
     * Source of "now". A field so a test can advance time without sleeping; production
     * never replaces it.
     */
    private volatile LongSupplier clock = System::currentTimeMillis;

    /** Monitor thread */
    private Thread monitorThread;
    
    // =========================================================================
    // Constructor
    // =========================================================================
    
    /**
     * Create a new Kubernetes job monitor.
     * 
     * @param kubernetesClient Kubernetes client instance
     * @param namespace Kubernetes namespace to watch
     * @param callback callback for running, completed, and failed jobs
     */
    public KubernetesJobMonitor(
        KubernetesClient kubernetesClient,
        String namespace,
        JobCompletionCallback callback
    ) {
        this(
            kubernetesClient,
            namespace,
            callback,
            DEFAULT_PENDING_WARN_MINUTES,
            DEFAULT_PENDING_TIMEOUT_MINUTES
        );
    }

    /**
     * Create a new Kubernetes job monitor with explicit stale-pending thresholds.
     *
     * @param pendingWarnMinutes    minutes a pod may be Pending before it is logged;
     *                              zero or less disables the warning
     * @param pendingTimeoutMinutes minutes a pod may be Pending before its pair is failed
     *                              for rerun; zero or less disables the transition, which
     *                              leaves the monitor reporting only
     */
    public KubernetesJobMonitor(
        KubernetesClient kubernetesClient,
        String namespace,
        JobCompletionCallback callback,
        int pendingWarnMinutes,
        int pendingTimeoutMinutes
    ) {
        this.kubernetesClient = kubernetesClient;
        this.namespace = namespace;
        this.callback = callback;
        this.pendingWarnMillis = toMillis(pendingWarnMinutes);
        this.pendingTimeoutMillis = toMillis(pendingTimeoutMinutes);
        log.info(
            "KubernetesJobMonitor created for namespace: " +
            namespace +
            " (pending warn=" +
            describeMinutes(pendingWarnMinutes) +
            ", pending timeout=" +
            describeMinutes(pendingTimeoutMinutes) +
            ")"
        );
    }

    private static long toMillis(int minutes) {
        return (minutes <= 0) ? 0L : TimeUnit.MINUTES.toMillis(minutes);
    }

    private static String describeMinutes(int minutes) {
        return (minutes <= 0) ? "disabled" : (minutes + "m");
    }
    
    // =========================================================================
    // Lifecycle Methods
    // =========================================================================
    
    /**
     * Start the job monitor.
     * 
     * <p>This method:</p>
     * <ul>
     *   <li>Creates the SharedInformerFactory</li>
     *   <li>Registers event handlers</li>
     *   <li>Starts the informer</li>
     * </ul>
     */
    public void start() {
        if (running.getAndSet(true)) {
            log.warn("KubernetesJobMonitor already running");
            return;
        }
        
        log.info("Starting KubernetesJobMonitor for namespace: " + namespace);
        
        // Start polling monitor thread.
        // NOTE: Informer wiring can be layered in later without changing callback contract.
        monitorThread = new Thread(this::pollLoop, "k8s-job-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();

        log.info("KubernetesJobMonitor started (polling mode)");
    }
    
    /**
     * Stop the job monitor.
     */
    public void stop() {
        if (!running.getAndSet(false)) {
            log.warn("KubernetesJobMonitor not running");
            return;
        }
        
        log.info("Stopping KubernetesJobMonitor...");
        
        // Interrupt polling thread
        if (monitorThread != null) {
            monitorThread.interrupt();
        }
        
        log.info("KubernetesJobMonitor stopped");
    }
    
    /**
     * Check if monitor is running.
     * 
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }
    
    // =========================================================================
    // Polling implementation
    // =========================================================================

    /**
     * Polling loop until informer integration is enabled.
     */
    private void pollLoop() {
        log.info("Starting Kubernetes job monitor polling loop");

        while (running.get()) {
            try {
                pollJobsOnce();
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                log.info("Polling loop interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in polling loop", e);
            }
        }
        
        log.info("Kubernetes job monitor polling loop exited");
    }

    private void pollJobsOnce() {
        List<Job> jobs = kubernetesClient
            .batch()
            .v1()
            .jobs()
            .inNamespace(namespace)
            .withLabel(MANAGED_LABEL_KEY, "true")
            .list()
            .getItems();

        // One listing per poll, not one per job: the pod template carries the same labels
        // as its Job, so a single labelled call indexes every pod by execution id.
        PodPhaseView pods = PodPhaseView.list(
            kubernetesClient,
            namespace,
            MANAGED_LABEL_KEY,
            EXEC_ID_LABEL_KEY
        );

        Set<ExecutionRef> executionsInListing = ConcurrentHashMap.newKeySet();

        for (Job job : jobs) {
            ExecutionRef execution = identify(job);
            if (execution == null) {
                continue;
            }
            executionsInListing.add(execution);

            if (completedExecutions.contains(execution)) {
                continue;
            }

            CompletionState completion = getCompletionState(job);
            if (completion == CompletionState.RUNNING) {
                handleInFlightJob(job, execution, pods);
                continue;
            }

            boolean processed;
            if (completion == CompletionState.SUCCEEDED) {
                processed = callback.onJobComplete(execution);
            } else {
                processed = callback.onJobFailed(execution, summarizeFailure(job));
            }

            if (processed) {
                completedExecutions.add(execution);
            }

            runningExecutions.remove(execution);
            pendingWarnedAt.remove(execution);
            cleanupPending.remove(execution);
        }

        drainCleanupPending(executionsInListing);
    }

    /**
     * Finishes stuck-pending transitions whose Job is no longer listed.
     *
     * <p>The Job is deleted before the pair's terminal status is written, so that a pair
     * can never be rerun-eligible while a pod that might still start belongs to it. That
     * ordering costs the Job as a retry trigger: if the database write then fails, no
     * listing will ever bring the pair back. This is the replacement trigger, and it is
     * the reason the deletion can safely go first.
     *
     * <p>Executions still present in the listing are skipped — the main loop handles those,
     * and calling the transition twice in one poll would be pointless work.
     */
    private void drainCleanupPending(Set<ExecutionRef> executionsInListing) {
        for (Map.Entry<ExecutionRef, String> entry : cleanupPending.entrySet()) {
            ExecutionRef execution = entry.getKey();
            if (executionsInListing.contains(execution)
                    || completedExecutions.contains(execution)) {
                continue;
            }
            completeStuckPending(execution, entry.getValue());
        }
    }

    /**
     * Decides what an unfinished job is doing: executing, or waiting to be scheduled.
     *
     * <p>The waiting case has no natural end. With backoffLimit 0, restartPolicy Never and
     * no activeDeadlineSeconds, an unschedulable pod — or one that cannot pull its image,
     * which also holds phase Pending — waits indefinitely while its pair holds one of
     * maxConcurrentJobs submission slots. Enough of those and the backend rejects every
     * further submission, which JobManager records as a terminal error on pairs that had
     * nothing wrong with them.
     */
    private void handleInFlightJob(Job job, ExecutionRef execution, PodPhaseView pods) {
        // A pair already judged stuck is driven to completion, whatever its pod is doing
        // now. Its record may already be terminal and eligible for rerun, so letting a
        // late-starting pod reclassify it as running would leave the Job undeleted and
        // that pod free to write results beside the rerun's.
        String pendingCleanup = cleanupPending.get(execution);
        if (pendingCleanup != null) {
            completeStuckPending(execution, pendingCleanup);
            return;
        }

        if (isRunningOnANode(job, execution, pods)) {
            if (!runningExecutions.contains(execution) && callback.onJobRunning(execution)) {
                runningExecutions.add(execution);
            }
            pendingWarnedAt.remove(execution);
            return;
        }

        // Without a pod listing there is nothing to judge, and a pod that is absent or in
        // a terminal phase is the Job's business, not this method's.
        if (!pods.isAvailable() || pods.phaseFor(execution) != PodPhaseView.Phase.PENDING) {
            return;
        }

        long createdAt = pods.createdAtMillis(execution);
        if (createdAt == PodPhaseView.UNKNOWN_TIME) {
            return;
        }

        long pendingMillis = clock.getAsLong() - createdAt;
        if (pendingMillis < 0) {
            // Clock skew between this host and the API server. Never read it as age.
            return;
        }

        String reason = pods.describeWhyPending(execution);

        if (pendingTimeoutMillis > 0 && pendingMillis >= pendingTimeoutMillis) {
            completeStuckPending(execution, reason);
            return;
        }

        if (pendingWarnMillis > 0 && pendingMillis >= pendingWarnMillis) {
            warnAboutStuckPod(execution, pendingMillis, reason);
        }
    }

    /**
     * Runs the stuck-pending transition, remembering the pair if it did not finish.
     *
     * <p>The callback reports false for any incomplete step — the status write, the end
     * time, or the Job deletion. Recording the pair here is what makes the next poll
     * resume the transition instead of re-deciding what the pair is.
     */
    private void completeStuckPending(ExecutionRef execution, String reason) {
        if (callback.onJobStuckPending(execution, reason)) {
            completedExecutions.add(execution);
            runningExecutions.remove(execution);
            pendingWarnedAt.remove(execution);
            cleanupPending.remove(execution);
            return;
        }
        cleanupPending.put(execution, reason);
    }

    private void warnAboutStuckPod(
        ExecutionRef execution,
        long pendingMillis,
        String reason
    ) {
        long now = clock.getAsLong();
        Long lastWarned = pendingWarnedAt.get(execution);
        if (lastWarned != null && (now - lastWarned) < pendingWarnMillis) {
            return;
        }
        pendingWarnedAt.put(execution, now);

        log.warn(
            "Kubernetes " +
            execution +
            " has had a pod waiting to start for " +
            TimeUnit.MILLISECONDS.toMinutes(pendingMillis) +
            " minutes and nothing has run yet. Kubernetes reports: " +
            reason
        );
    }

    /**
     * The concrete identity of a listed Job, or null if it does not carry one.
     *
     * <p>A Job the API server has accepted always has a UID, so a listing entry without one
     * is not something to act on: every downstream decision here either suppresses or
     * publishes an execution's result, and doing that on an identity that cannot be told
     * apart from another Job's is the failure this method exists to prevent.
     */
    private ExecutionRef identify(Job job) {
        Integer execId = extractExecId(job);
        if (execId == null) {
            return null;
        }
        ExecutionRef execution = ExecutionRef.fromJob(execId, job);
        if (execution == null) {
            log.warn(
                "Kubernetes job " + jobNameOf(job) + " (execId " + execId +
                ") carries no object identity; it is not being acted on. OPERATOR ACTION:" +
                " inspect its metadata.uid."
            );
        }
        return execution;
    }

    private String jobNameOf(Job job) {
        return (job != null && job.getMetadata() != null && job.getMetadata().getName() != null)
            ? job.getMetadata().getName()
            : "unknown";
    }

    private Integer extractExecId(Job job) {
        if (job == null || job.getMetadata() == null || job.getMetadata().getLabels() == null) {
            return null;
        }

        String execIdRaw = job.getMetadata().getLabels().get(EXEC_ID_LABEL_KEY);
        if (execIdRaw == null || execIdRaw.trim().isEmpty()) {
            return null;
        }

        try {
            return Integer.parseInt(execIdRaw);
        } catch (NumberFormatException e) {
            log.warn("Invalid execution id label on job: " + execIdRaw);
            return null;
        }
    }

    /**
     * Classifies a Job as terminal <em>only</em> from its terminal conditions.
     *
     * <p>This used to short-circuit on {@code status.failed > 0} before the conditions were
     * read at all. That counter counts failed <em>Pods</em>, not a finished Job: with
     * {@code backoffLimit > 0} a Job sitting at {@code failed == 1} is still live and will
     * create the next Pod. Reporting it FAILED made the caller write a terminal pair status
     * and an {@code end_time} — making the pair rerun-eligible — while the controller was
     * about to start a replacement execution, so two executions for one pair could write
     * results.
     *
     * <p>The symmetric {@code succeeded > 0} shortcut is gone too. It happens to be
     * equivalent today because {@code completions} is never set and defaults to 1, but that
     * is a project-specific assumption propping up an asymmetric rule, and this judgement had
     * already drifted into several disagreeing copies. The cost of removing it is at most one
     * extra poll interval before a completed pair is noticed.
     *
     * <p>{@code FailureTarget} and {@code SuccessCriteriaMet} are deliberately not matched:
     * they <em>begin</em> termination, and the real {@code Failed} / {@code Complete}
     * condition follows.
     */
    private CompletionState getCompletionState(Job job) {
        JobStatus status = job.getStatus();
        if (status == null) {
            return CompletionState.RUNNING;
        }

        List<JobCondition> conditions = status.getConditions();
        if (conditions == null) {
            return CompletionState.RUNNING;
        }

        for (JobCondition condition : conditions) {
            if (condition == null) {
                continue;
            }

            String type = condition.getType();
            String conditionStatus = condition.getStatus();
            if (!"True".equalsIgnoreCase(conditionStatus)) {
                continue;
            }

            if ("Complete".equalsIgnoreCase(type)) {
                return CompletionState.SUCCEEDED;
            }
            if ("Failed".equalsIgnoreCase(type)) {
                return CompletionState.FAILED;
            }
        }

        return CompletionState.RUNNING;
    }

    /**
     * Whether this job has a pod actually executing on a node.
     *
     * <p>Not {@code status.active > 0}, which the Kubernetes API defines as "the number of
     * pending and running pods which are not terminating". A pod the scheduler has never
     * placed satisfies that test, so reading it moved a pair to STATUS_RUNNING before
     * anything ran — and a pair sitting at RUNNING is invisible to
     * {@code GetPairsEnqueuedLongerThan}, which looks for STATUS_ENQUEUED.
     *
     * <p>When pods cannot be listed the old test is all there is, so it is used and the
     * behaviour is exactly what it was before this distinction existed.
     */
    private boolean isRunningOnANode(Job job, ExecutionRef execution, PodPhaseView pods) {
        if (!pods.isAvailable()) {
            return hasActivePod(job);
        }
        return pods.phaseFor(execution) == PodPhaseView.Phase.RUNNING;
    }

    private boolean hasActivePod(Job job) {
        JobStatus status = job.getStatus();
        if (status == null) {
            return false;
        }

        Integer active = status.getActive();
        return active != null && active > 0;
    }

    private String summarizeFailure(Job job) {
        JobStatus status = job.getStatus();
        if (status == null || status.getConditions() == null) {
            return "Job failed without status conditions";
        }

        for (JobCondition condition : status.getConditions()) {
            if (condition == null) {
                continue;
            }
            if (!"Failed".equalsIgnoreCase(condition.getType())) {
                continue;
            }

            String reason = condition.getReason();
            String message = condition.getMessage();
            if (reason == null) {
                reason = "unknown";
            }
            if (message == null) {
                message = "no message";
            }
            return reason + ": " + message;
        }

        return "Job failed";
    }

    private enum CompletionState {
        RUNNING,
        SUCCEEDED,
        FAILED,
    }

    // =========================================================================
    // Callback Interface
    // =========================================================================

    /**
     * Callback interface for job completion events.
     */
    public interface JobCompletionCallback {

        /**
         * Called when a job first becomes active in Kubernetes.
         *
         * <p>Every method here carries {@link ExecutionRef} rather than the execution id and
         * name it used to. The monitor holds the Job the event came from, so the identity is
         * free at this end; passing only the integer made the implementation guess which
         * execution was meant, and after a restart it guessed wrong.
         *
         * @param execution the Kubernetes execution this event belongs to
         * @return true when the running transition was handled and should not be retried
         */
        boolean onJobRunning(ExecutionRef execution);

        /**
         * Called when a job completes successfully.
         * @param execution the Kubernetes execution this event belongs to
         * @return true when completion handling succeeded and should not be retried
         */
        boolean onJobComplete(ExecutionRef execution);

        /**
         * Called when a job fails.
         * @param execution the Kubernetes execution this event belongs to
         * @param reason Failure reason
         * @return true when failure handling succeeded and should not be retried
         */
        boolean onJobFailed(ExecutionRef execution, String reason);

        /**
         * Called when a job's pod has waited to start for longer than the configured
         * timeout, so nothing has run and nothing is going to without intervention.
         *
         * <p>Distinct from {@link #onJobFailed} because the causes and the right answer
         * differ: a failed job produced output and an exit status, while this one never
         * started. Nothing ran, so nothing measured can be contaminated by trying again,
         * and the implementation is expected to delete the Kubernetes Job and release the
         * submission slot the pair has been holding.
         *
         * <p>Deliberately not a default method. There is one implementor, and a default
         * would let a future one inherit silence on the condition that wedges the backend.
         *
         * @param reason Kubernetes' own account of why the pod has not started, for the
         *               log — it is prose and must not be branched on
         * @return true when the transition was recorded and should not be retried
         */
        boolean onJobStuckPending(ExecutionRef execution, String reason);
    }
}
