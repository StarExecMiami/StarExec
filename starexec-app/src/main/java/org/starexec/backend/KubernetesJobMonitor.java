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

    /** Tracks completion callbacks already emitted to avoid duplicate updates */
    private final Set<Integer> completedExecIds = ConcurrentHashMap.newKeySet();

    /** Tracks jobs already transitioned to STATUS_RUNNING */
    private final Set<Integer> runningExecIds = ConcurrentHashMap.newKeySet();

    /** When each stuck-pending execution id was last warned about, for throttling */
    private final Map<Integer, Long> pendingWarnedAt = new ConcurrentHashMap<>();

    /**
     * Execution ids judged stuck whose transition did not complete, with the reason they
     * were judged stuck.
     *
     * <p>The judgement is not revisited once made. By the time a transition can fail
     * part-way the pair may already carry ERROR_RUNSCRIPT and an end_time, which makes it
     * eligible for RERUN_FAILED_PAIRS; if its pod then started and the monitor let it be
     * reclassified as an ordinary running pair, nothing would ever delete the Job and that
     * pod could write results alongside the rerun.
     */
    private final Map<Integer, StuckPendingRecord> cleanupPending =
        new ConcurrentHashMap<>();

    /** What a stuck-pending transition needs to resume after an incomplete attempt. */
    private static final class StuckPendingRecord {

        private final String jobName;
        private final String reason;

        private StuckPendingRecord(String jobName, String reason) {
            this.jobName = jobName;
            this.reason = reason;
        }
    }

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

        Set<Integer> execIdsInListing = ConcurrentHashMap.newKeySet();

        for (Job job : jobs) {
            Integer execId = extractExecId(job);
            if (execId == null) {
                continue;
            }
            execIdsInListing.add(execId);

            if (completedExecIds.contains(execId)) {
                continue;
            }

            CompletionState completion = getCompletionState(job);
            if (completion == CompletionState.RUNNING) {
                handleInFlightJob(job, execId, pods);
                continue;
            }

            String jobName = jobNameOf(job);
            boolean processed;
            if (completion == CompletionState.SUCCEEDED) {
                processed = callback.onJobComplete(execId, jobName);
            } else {
                processed = callback.onJobFailed(
                    execId,
                    jobName,
                    summarizeFailure(job)
                );
            }

            if (processed) {
                completedExecIds.add(execId);
            }

            runningExecIds.remove(execId);
            pendingWarnedAt.remove(execId);
            cleanupPending.remove(execId);
        }

        drainCleanupPending(execIdsInListing);
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
     * <p>Exec ids still present in the listing are skipped — the main loop handles those,
     * and calling the transition twice in one poll would be pointless work.
     */
    private void drainCleanupPending(Set<Integer> execIdsInListing) {
        for (Map.Entry<Integer, StuckPendingRecord> entry : cleanupPending.entrySet()) {
            int execId = entry.getKey();
            if (execIdsInListing.contains(execId) || completedExecIds.contains(execId)) {
                continue;
            }
            StuckPendingRecord record = entry.getValue();
            completeStuckPending(execId, record.jobName, record.reason);
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
    private void handleInFlightJob(Job job, int execId, PodPhaseView pods) {
        String jobName = jobNameOf(job);

        // A pair already judged stuck is driven to completion, whatever its pod is doing
        // now. Its record may already be terminal and eligible for rerun, so letting a
        // late-starting pod reclassify it as running would leave the Job undeleted and
        // that pod free to write results beside the rerun's.
        StuckPendingRecord pendingCleanup = cleanupPending.get(execId);
        if (pendingCleanup != null) {
            completeStuckPending(execId, jobName, pendingCleanup.reason);
            return;
        }

        if (isRunningOnANode(job, execId, pods)) {
            if (!runningExecIds.contains(execId) && callback.onJobRunning(execId, jobName)) {
                runningExecIds.add(execId);
            }
            pendingWarnedAt.remove(execId);
            return;
        }

        // Without a pod listing there is nothing to judge, and a pod that is absent or in
        // a terminal phase is the Job's business, not this method's.
        if (!pods.isAvailable() || pods.phaseFor(execId) != PodPhaseView.Phase.PENDING) {
            return;
        }

        long createdAt = pods.createdAtMillis(execId);
        if (createdAt == PodPhaseView.UNKNOWN_TIME) {
            return;
        }

        long pendingMillis = clock.getAsLong() - createdAt;
        if (pendingMillis < 0) {
            // Clock skew between this host and the API server. Never read it as age.
            return;
        }

        String reason = pods.describeWhyPending(execId);

        if (pendingTimeoutMillis > 0 && pendingMillis >= pendingTimeoutMillis) {
            completeStuckPending(execId, jobName, reason);
            return;
        }

        if (pendingWarnMillis > 0 && pendingMillis >= pendingWarnMillis) {
            warnAboutStuckPod(execId, jobName, pendingMillis, reason);
        }
    }

    /**
     * Runs the stuck-pending transition, remembering the pair if it did not finish.
     *
     * <p>The callback reports false for any incomplete step — the status write, the end
     * time, or the Job deletion. Recording the pair here is what makes the next poll
     * resume the transition instead of re-deciding what the pair is.
     */
    private void completeStuckPending(int execId, String jobName, String reason) {
        if (callback.onJobStuckPending(execId, jobName, reason)) {
            completedExecIds.add(execId);
            runningExecIds.remove(execId);
            pendingWarnedAt.remove(execId);
            cleanupPending.remove(execId);
            return;
        }
        cleanupPending.put(execId, new StuckPendingRecord(jobName, reason));
    }

    private void warnAboutStuckPod(
        int execId,
        String jobName,
        long pendingMillis,
        String reason
    ) {
        long now = clock.getAsLong();
        Long lastWarned = pendingWarnedAt.get(execId);
        if (lastWarned != null && (now - lastWarned) < pendingWarnMillis) {
            return;
        }
        pendingWarnedAt.put(execId, now);

        log.warn(
            "Kubernetes job " +
            jobName +
            " (execId " +
            execId +
            ") has had a pod waiting to start for " +
            TimeUnit.MILLISECONDS.toMinutes(pendingMillis) +
            " minutes and nothing has run yet. Kubernetes reports: " +
            reason
        );
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
    private boolean isRunningOnANode(Job job, int execId, PodPhaseView pods) {
        if (!pods.isAvailable()) {
            return hasActivePod(job);
        }
        return pods.phaseFor(execId) == PodPhaseView.Phase.RUNNING;
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
         * @param execId Execution ID
         * @param jobName Kubernetes job name
         * @return true when the running transition was handled and should not be retried
         */
        boolean onJobRunning(int execId, String jobName);

        /**
         * Called when a job completes successfully.
         * @param execId Execution ID
         * @param jobName Kubernetes job name
         * @return true when completion handling succeeded and should not be retried
         */
        boolean onJobComplete(int execId, String jobName);

        /**
         * Called when a job fails.
         * @param execId Execution ID
         * @param jobName Kubernetes job name
         * @param reason Failure reason
         * @return true when failure handling succeeded and should not be retried
         */
        boolean onJobFailed(int execId, String jobName, String reason);

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
        boolean onJobStuckPending(int execId, String jobName, String reason);
    }
}
