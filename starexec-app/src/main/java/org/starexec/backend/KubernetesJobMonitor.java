/*
 * KubernetesJobMonitor - Watches Kubernetes Jobs for completion
 * 
 * ============================================================================
 * SCAFFOLDING FOR FUTURE DEVELOPMENT - December 2025
 * ============================================================================
 * 
 * This component monitors Kubernetes Job resources for completion and updates
 * the StarExec database accordingly. It uses the Kubernetes Watch/Informer
 * pattern for efficient, event-driven monitoring.
 * 
 * Design Pattern: Watch + Informer
 * ─────────────────────────────────
 * Unlike the PodmanBackend's polling approach, Kubernetes provides efficient
 * event-driven APIs:
 * 
 *   1. Watch API - Stream of events (ADDED, MODIFIED, DELETED)
 *   2. Informers - Higher-level abstraction with local cache + event handlers
 * 
 * Architecture:
 * ┌─────────────────────────────────────────────────────────────────────────────┐
 * │                        KubernetesJobMonitor                                  │
 * │  ┌───────────────────────────────────────────────────────────────────────┐  │
 * │  │  SharedInformerFactory                                                 │  │
 * │  │    - Maintains local cache of Job resources                            │  │
 * │  │    - Handles reconnection on network failures                          │  │
 * │  └───────────────────────────────────────────────────────────────────────┘  │
 * │                                    ↓                                         │
 * │  ┌───────────────────────────────────────────────────────────────────────┐  │
 * │  │  ResourceEventHandler<Job>                                             │  │
 * │  │    - onAdd: New job created (ignore)                                   │  │
 * │  │    - onUpdate: Job status changed → check if complete                  │  │
 * │  │    - onDelete: Job removed (cleanup tracking)                          │  │
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
 *   - Network disconnection: Informer auto-reconnects
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.starexec.logger.StarLogger;

/**
 * Monitors Kubernetes Jobs for completion and updates StarExec database.
 * 
 * <p>Uses the Kubernetes Informer pattern for efficient, event-driven monitoring.</p>
 * 
 * <p><b>Status: SCAFFOLDING</b> - Structure in place, implementation pending.</p>
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
    private final Set<Integer> completedExecIds = new HashSet<>();
    
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
     * @param callback callback for completed jobs
     */
    public KubernetesJobMonitor(
        KubernetesClient kubernetesClient,
        String namespace,
        JobCompletionCallback callback
    ) {
        this.kubernetesClient = kubernetesClient;
        this.namespace = namespace;
        this.callback = callback;
        log.info("KubernetesJobMonitor created for namespace: " + namespace);
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

        for (Job job : jobs) {
            Integer execId = extractExecId(job);
            if (execId == null) {
                continue;
            }

            if (completedExecIds.contains(execId)) {
                continue;
            }

            CompletionState completion = getCompletionState(job);
            if (completion == CompletionState.RUNNING) {
                continue;
            }

            completedExecIds.add(execId);

            String jobName =
                (job.getMetadata() != null) ? job.getMetadata().getName() : "unknown";
            if (completion == CompletionState.SUCCEEDED) {
                callback.onJobComplete(execId, jobName);
            } else {
                callback.onJobFailed(execId, jobName, summarizeFailure(job));
            }
        }
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

    private CompletionState getCompletionState(Job job) {
        JobStatus status = job.getStatus();
        if (status == null) {
            return CompletionState.RUNNING;
        }

        Integer succeeded = status.getSucceeded();
        if (succeeded != null && succeeded > 0) {
            return CompletionState.SUCCEEDED;
        }

        Integer failed = status.getFailed();
        if (failed != null && failed > 0) {
            return CompletionState.FAILED;
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
         * Called when a job completes successfully.
         * @param execId Execution ID
         * @param jobName Kubernetes job name
         */
        void onJobComplete(int execId, String jobName);

        /**
         * Called when a job fails.
         * @param execId Execution ID
         * @param jobName Kubernetes job name
         * @param reason Failure reason
         */
        void onJobFailed(int execId, String jobName, String reason);
    }
}
