/*
 * KubernetesNativeBackend - Next-generation Kubernetes-native job execution
 *
 * ============================================================================
 * SCAFFOLDING FOR FUTURE DEVELOPMENT - December 2025
 * ============================================================================
 *
 * This backend is designed for true Kubernetes-native job execution where each
 * StarExec job pair runs as a Kubernetes Job resource. Unlike the legacy
 * KubernetesBackend (which runs scripts locally and uses kubectl for management),
 * this backend:
 *
 * 1. Creates Kubernetes Job resources for each job pair
 * 2. Uses the Kubernetes Java client (fabric8io/kubernetes-client) for API access
 * 3. Monitors job completion via Kubernetes polling today, with informer-based
 *    monitoring still planned
 * 4. Enables Kubernetes-native execution experiments across multiple nodes
 * 5. Leverages Kubernetes features: resource limits, node selectors, tolerations
 *
 * Architecture:
 * ┌─────────────────────────────────────────────────────────────────────────────┐
 * │                       KubernetesNativeBackend                                │
 * │  ┌───────────────────────────────────────────────────────────────────────┐  │
 * │  │  KubernetesClient (fabric8io)                                         │  │
 * │  │    - In-cluster config (ServiceAccount) or kubeconfig                 │  │
 * │  │    - Connection pooling handled by client                             │  │
 * │  └───────────────────────────────────────────────────────────────────────┘  │
 * │                                    ↓                                         │
 * │  ┌───────────────────────────────────────────────────────────────────────┐  │
 * │  │  Job Creation                                                         │  │
 * │  │    - Creates K8s Job with starexec/job-runner image                   │  │
 * │  │    - Mounts PVC for data directory                                    │  │
 * │  │    - Sets resource limits (memory, CPU)                               │  │
 * │  │    - Uses worker-node selectors for current job placement            │  │
 * │  └───────────────────────────────────────────────────────────────────────┘  │
 * │                                    ↓                                         │
 * │  ┌───────────────────────────────────────────────────────────────────────┐  │
 * │  │  KubernetesJobMonitor (polling mode today)                            │  │
 * │  │    - Polls for Job completion                                         │  │
 * │  │    - Parses output files from PVC                                     │  │
 * │  │    - Updates database via JobPairs API                                │  │
 * │  │    - Cleans up completed Job resources                                │  │
 * │  └───────────────────────────────────────────────────────────────────────┘  │
 * │                                    ↓                                         │
 * │  ┌───────────────────────────────────────────────────────────────────────┐  │
 * │  │  Queue Abstraction via Labels                                         │  │
 * │  │    - Queue = Node label (starexec/queue=<name>)                       │  │
 * │  │    - Queue labels support node grouping and queue administration       │  │
 * │  │    - Job placement currently uses worker-node selectors only           │  │
 * │  └───────────────────────────────────────────────────────────────────────┘  │
 * └─────────────────────────────────────────────────────────────────────────────┘
 *
 * Dependency:
 *   <dependency>
 *     <groupId>io.fabric8</groupId>
 *     <artifactId>kubernetes-client</artifactId>
 *     <version>6.10.0</version>
 *   </dependency>
 *
 * Configuration (environment variables):
 *   STAREXEC_K8S_NAMESPACE       - Kubernetes namespace (default: starexec).
 *                                   Must contain the shared data PVC and the
 *                                   job ServiceAccount used by Kubernetes jobs.
 *   STAREXEC_K8S_JOB_IMAGE       - Job runner image (default: starexec/job-runner:latest)
 *   STAREXEC_K8S_DATA_PVC        - PVC name for data volume (default: starexec-data)
 *   STAREXEC_K8S_DATA_PVC_ACCESS_MODE
 *                                 - Access mode for the shared data PVC.
 *                                   ReadWriteOnce-style modes pin jobs to the
 *                                   StarExec app node.
 *   STAREXEC_K8S_SERVICE_ACCOUNT - ServiceAccount for jobs (default: starexec-job)
 *   STAREXEC_K8S_APP_NODE_NAME    - Node currently hosting the StarExec app pod
 *   STAREXEC_K8S_QUEUE_LABEL     - Label key for queue discovery and node grouping
 *                                   (default: starexec/queue)
 *
 * Future TODOs:
 *   - [ ] Implement KubernetesJobMonitor with Watch pattern
 *   - [ ] Add Helm chart templates for RBAC (ServiceAccount, Role, RoleBinding)
 *   - [ ] Add PVC templates for shared data volume
 *   - [ ] Implement job priority and preemption support
 *   - [ ] Add metrics export for Prometheus
 *   - [ ] Support for GPU jobs (nvidia.com/gpu resources)
 *
 * @author StarExec Team
 * @since 2.0.0
 */

package org.starexec.backend;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.NodeSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import org.starexec.config.EnvironmentConfig;
import org.starexec.constants.R;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.logger.StarLogger;

/**
 * Kubernetes-native backend for StarExec job execution.
 *
 * <p>This backend creates Kubernetes Job resources for each StarExec job pair.
 * It is still an experimental path and should not be described as production-ready
 * without cluster validation.</p>
 *
 * <p><b>Status: EXPERIMENTAL</b> - Core submission flow exists; monitoring still polls.</p>
 */
public class KubernetesNativeBackend implements Backend {

    private static final StarLogger log = StarLogger.getLogger(
        KubernetesNativeBackend.class
    );

    // =========================================================================
    // Configuration Constants
    // =========================================================================

    /** Default Kubernetes namespace for StarExec jobs */
    private static final String DEFAULT_NAMESPACE = "starexec";

    /** Label key used to identify StarExec queues on nodes */
    private static final String DEFAULT_QUEUE_LABEL = "starexec/queue";

    /** Label prefix for StarExec-managed resources */
    private static final String LABEL_PREFIX = "starexec.org/";

    /** Label key indicating a Kubernetes Job is managed by StarExec */
    private static final String MANAGED_LABEL = LABEL_PREFIX + "managed";

    /** Label key for execution ID */
    private static final String EXEC_ID_LABEL = LABEL_PREFIX + "exec-id";

    /** Label key for job pair ID */
    private static final String PAIR_ID_LABEL = LABEL_PREFIX + "pair-id";

    /** Label key for StarExec label schema version */
    private static final String LABEL_VERSION = LABEL_PREFIX + "label-version";

    /** Current label schema version */
    private static final String CURRENT_LABEL_VERSION = "2";

    /** Label key distinguishing job-pair resources from future maintenance jobs */
    private static final String KIND_LABEL = LABEL_PREFIX + "kind";

    /** Label value for regular job-pair execution resources */
    private static final String KIND_JOB_PAIR = "job-pair";

    /** Annotation key for the output directory on the shared PVC */
    private static final String OUTPUT_DIR_ANNOTATION = LABEL_PREFIX + "output-dir";

    /** Label key for worker nodes */
    private static final String WORKER_LABEL = LABEL_PREFIX + "worker";

    /** Default queue name for nodes without queue label */
    private static final String DEFAULT_QUEUE_NAME = "default";

    /** Fallback node name when Kubernetes stats do not report a hostname */
    private static final String DEFAULT_WORKER_NODE_NAME = "kubernetes-worker";

    // =========================================================================
    // Runtime State
    // =========================================================================

    /** Maps StarExec execution IDs to Kubernetes Job names */
    private final Map<Integer, String> execIdToJobName =
        new ConcurrentHashMap<>();

    /** Maps StarExec execution IDs to StarExec pair IDs */
    private final Map<Integer, Integer> execIdToPairId =
        new ConcurrentHashMap<>();

    /** Maps StarExec execution IDs to output directories */
    private final Map<Integer, Path> execIdToOutputDir =
        new ConcurrentHashMap<>();

    /** Execution ID generator */
    private int nextExecId = 1;

    /** Lock for ID generation */
    private final Object idLock = new Object();

    /** Kubernetes client instance */
    private KubernetesClient kubernetesClient;

    /** Job monitor */
    private KubernetesJobMonitor jobMonitor;

    /** Flag indicating if backend is initialized */
    private volatile boolean initialized = false;

    /** Flag set during graceful shutdown to reject new submissions */
    private volatile boolean shuttingDown = false;

    /** Hard concurrency cap to prevent unbounded K8s Job creation */
    private int maxConcurrentJobs = 50;

    /** Periodic orphan sweep interval in milliseconds */
    private int orphanSweepIntervalMs = 300000;

    /** Tracks active K8s Jobs against the concurrency cap */
    private final AtomicInteger activeJobCount = new AtomicInteger(0);

    /** Exec IDs currently holding a concurrency slot */
    private final Set<Integer> jobsHoldingSlot = ConcurrentHashMap.newKeySet();

    /** Exec IDs that have been killed to prevent stale completion callbacks */
    private final Set<Integer> killedExecIds = ConcurrentHashMap.newKeySet();

    /** Periodic cleanup task that deletes orphaned managed K8s Jobs */
    private ScheduledExecutorService orphanSweepExecutor;

    // =========================================================================
    // Configuration (loaded from environment)
    // =========================================================================

    private String namespace;
    private String jobImage;
    private String dataPvcName;
    private String dataPvcAccessMode;
    private String serviceAccountName;
    private String appNodeName;
    private String queueLabelKey;
    private String memoryLimit;
    private String cpuLimit;
    private int ttlSecondsAfterFinished;
    private int backoffLimit;
    private boolean strictOnePairPerCpu;

    /** Optional node selector key for worker nodes */
    private String workerNodeSelectorKey;

    /** Optional node selector value for worker nodes */
    private String workerNodeSelectorValue;

    // =========================================================================
    // Constructor
    // =========================================================================

    public KubernetesNativeBackend() {
        log.info("KubernetesNativeBackend instantiated");
    }

    // =========================================================================
    // Backend Interface Implementation
    // =========================================================================

    /**
     * Initialize the Kubernetes backend.
     *
     * <p>This method:</p>
     * <ul>
     *   <li>Loads configuration from environment variables</li>
     *   <li>Creates the Kubernetes client (in-cluster or kubeconfig)</li>
     *   <li>Validates cluster connectivity</li>
     *   <li>Starts the job monitor</li>
     * </ul>
     *
     * @param BACKEND_ROOT Ignored for Kubernetes backend
     */
    @Override
    public void initialize(String BACKEND_ROOT) {
        log.info("Initializing KubernetesNativeBackend...");

        // Load configuration from environment
        loadConfiguration();

        try {
            kubernetesClient = new KubernetesClientBuilder().build();
            log.info(
                "Connected to Kubernetes API at " +
                kubernetesClient.getConfiguration().getMasterUrl()
            );

            ensureNamespaceAccessible();

            reconcileOrphanedPairs();

            jobMonitor = new KubernetesJobMonitor(
                kubernetesClient,
                namespace,
                new KubernetesJobCompletionCallback()
            );
            jobMonitor.start();

            startOrphanSweepScheduler();

            initialized = true;
            log.info("KubernetesNativeBackend initialized successfully");
        } catch (Exception e) {
            initialized = false;
            log.error("Failed to initialize KubernetesNativeBackend", e);
            throw new RuntimeException("KubernetesNativeBackend initialization failed", e);
        }
    }

    /**
     * Load configuration from environment variables.
     */
    private void loadConfiguration() {
        namespace = getEnv("STAREXEC_K8S_NAMESPACE", DEFAULT_NAMESPACE);
        jobImage = getEnv(
            "STAREXEC_K8S_JOB_IMAGE",
            EnvironmentConfig.getContainerJobImage()
        );
        dataPvcName = getEnv("STAREXEC_K8S_DATA_PVC", "starexec-data");
        dataPvcAccessMode = getEnv("STAREXEC_K8S_DATA_PVC_ACCESS_MODE", "ReadWriteMany");
        serviceAccountName = getEnv(
            "STAREXEC_K8S_SERVICE_ACCOUNT",
            "starexec-job"
        );
        appNodeName = getEnv("STAREXEC_K8S_APP_NODE_NAME", "");
        queueLabelKey = getEnv("STAREXEC_K8S_QUEUE_LABEL", DEFAULT_QUEUE_LABEL);
        memoryLimit = getEnv("STAREXEC_K8S_MEMORY_LIMIT", "2Gi");
        cpuLimit = getEnv("STAREXEC_K8S_CPU_LIMIT", "1");
        ttlSecondsAfterFinished = getEnvInt("STAREXEC_K8S_JOB_TTL_SECONDS", 3600);
        backoffLimit = getEnvInt("STAREXEC_K8S_JOB_BACKOFF_LIMIT", 0);
        maxConcurrentJobs = getEnvInt("STAREXEC_K8S_MAX_CONCURRENT_JOBS", 50);
        if (maxConcurrentJobs < 1) {
            log.warn(
                "Invalid STAREXEC_K8S_MAX_CONCURRENT_JOBS value: " +
                maxConcurrentJobs +
                ". Falling back to 1."
            );
            maxConcurrentJobs = 1;
        }
        orphanSweepIntervalMs = getEnvInt("STAREXEC_K8S_ORPHAN_SWEEP_INTERVAL_MS", 300000);
        if (orphanSweepIntervalMs < 0) {
            log.warn(
                "Invalid STAREXEC_K8S_ORPHAN_SWEEP_INTERVAL_MS value: " +
                orphanSweepIntervalMs +
                ". Falling back to 0 (disabled)."
            );
            orphanSweepIntervalMs = 0;
        }
        strictOnePairPerCpu = getEnvBoolean(
            "STAREXEC_K8S_STRICT_ONE_PAIR_PER_CPU",
            true
        );
        workerNodeSelectorKey = getEnv("STAREXEC_K8S_WORKER_SELECTOR_KEY", WORKER_LABEL);
        workerNodeSelectorValue = getEnv("STAREXEC_K8S_WORKER_SELECTOR_VALUE", "true");

        // Academic reproducibility policy: a job pair must get whole physical cores that
        // nothing else runs on, so its measurements are not perturbed by a neighbour.
        //
        // This used to force cpuLimit to "1" whenever the flag was set, discarding the
        // operator's configured value with only a log line. Production sets
        // STAREXEC_K8S_CPU_LIMIT=32 (a whole compute node) together with this flag, so
        // the intent -- one pair per node -- was silently replaced by a one-CPU request,
        // and what actually kept pairs off each other was the 250Gi memory request
        // exhausting the node. Isolation by accident, and it disappears the moment the
        // memory limit is lowered.
        //
        // The flag now means what its name says: assert that the request can yield
        // exclusive cores, rather than shrink it to one. Kubernetes grants exclusive CPUs
        // only for a Guaranteed pod whose cpu request is a whole number, and only when
        // the kubelet runs --cpu-manager-policy=static. Requests already equal limits in
        // buildKubernetesJob, so the remaining requirement is the integer.
        if (strictOnePairPerCpu) {
            cpuLimit = resolveCpuLimitForStrictMode(cpuLimit);
            // Not detectable from the API -- kubelet configuration is not exposed on the
            // Node object -- so it is stated rather than checked. Without it the request
            // below is a quota and the pinning is a fiction.
            log.info(
                "STAREXEC_K8S_STRICT_ONE_PAIR_PER_CPU=true with cpu=" + cpuLimit +
                ". This grants exclusive cores ONLY if worker nodes run kubelet with" +
                " --cpu-manager-policy=static. Without it Kubernetes applies a CFS" +
                " bandwidth quota instead, solver threads float across all cores, and" +
                " recorded timings are perturbed by co-scheduled work."
            );
        }

        log.info(
            "K8s Configuration: namespace=" +
                namespace +
                ", image=" +
                jobImage +
                ", pvc=" +
                dataPvcName +
                " (" +
                dataPvcAccessMode +
                ")" +
                ", cpu=" +
                cpuLimit +
                ", memory=" +
                memoryLimit +
                ", appNode=" +
                appNodeName +
                ", ttlSeconds=" +
                ttlSecondsAfterFinished +
                ", maxConcurrentJobs=" +
                maxConcurrentJobs +
                ", orphanSweepIntervalMs=" +
                orphanSweepIntervalMs +
                ", strictOnePairPerCpu=" +
                strictOnePairPerCpu
        );
    }

    /**
     * Helper to get environment variable with default.
     */
    private String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }

    private int getEnvInt(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid integer for env " + key + ": '" + value + "'. Using default " + defaultValue);
            return defaultValue;
        }
    }

    private boolean getEnvBoolean(String key, boolean defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /**
     * Returns the CPU quantity strict mode should use — which is the configured one,
     * unchanged.
     *
     * <p>The return value is the point of this method. Strict mode previously *replaced*
     * the operator's value with {@code "1"}, so a deployment asking for a whole 32-CPU
     * node silently got one CPU. That the value passes through untouched is now an
     * asserted contract rather than merely the absence of an assignment, which is
     * something a test can hold on to: a test that only inspects the generated pod spec
     * cannot see an assignment made during configuration loading.
     *
     * @throws IllegalStateException if the quantity could never yield exclusive cores
     */
    static String resolveCpuLimitForStrictMode(String configuredCpuLimit) {
        if (!isWholeNumberCpuQuantity(configuredCpuLimit)) {
            throw new IllegalStateException(
                "STAREXEC_K8S_STRICT_ONE_PAIR_PER_CPU=true requires" +
                " STAREXEC_K8S_CPU_LIMIT to be a whole number of CPUs, but it is '" +
                configuredCpuLimit + "'. Kubernetes only assigns exclusive cores to a" +
                " Guaranteed pod requesting integer CPUs; a fractional request is a" +
                " bandwidth quota and the solver would float across the node's cores," +
                " perturbing its own measurements and its neighbours'."
            );
        }
        return configuredCpuLimit;
    }

    /**
     * True if a Kubernetes CPU quantity denotes a whole number of CPUs.
     *
     * <p>This is the condition Kubernetes requires before it will assign exclusive cores:
     * the CPU Manager's static policy only pins a Guaranteed pod whose cpu request is an
     * integer. {@code "2"} qualifies; {@code "1500m"}, {@code "0.5"} and {@code "2.5"} do
     * not, and such a pod receives a bandwidth quota instead, floating across the node.
     *
     * <p>{@code "2000m"} is accepted because milli-CPU is exact here — 2000m is two whole
     * CPUs — and rejecting a legitimate spelling would be a trap rather than a guard.
     *
     * @param quantity the raw value of STAREXEC_K8S_CPU_LIMIT
     */
    static boolean isWholeNumberCpuQuantity(String quantity) {
        if (quantity == null) {
            return false;
        }
        String value = quantity.trim();
        if (value.isEmpty()) {
            return false;
        }
        try {
            if (value.endsWith("m")) {
                long milli = Long.parseLong(value.substring(0, value.length() - 1));
                return milli > 0 && milli % 1000 == 0;
            }
            // Reject "2.0" as well as "2.5": a decimal point in a cpu quantity is a
            // signal that someone is thinking in fractions, and the next edit is likely
            // to make it fractional. Integers only.
            if (value.indexOf('.') >= 0) {
                return false;
            }
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void ensureNamespaceAccessible() {
        if (kubernetesClient.namespaces().withName(namespace).get() == null) {
            throw new IllegalStateException(
                "Kubernetes namespace does not exist or is inaccessible: " + namespace
            );
        }
    }

    /**
     * Clean up resources when shutting down.
     *
     * <p>Shutdown ordering follows the same pattern as PodmanBackend:</p>
     * <ol>
     *   <li>Stop accepting new submissions</li>
     *   <li>Run one final poll for terminal K8s Jobs</li>
     *   <li>Stop the job monitor</li>
     *   <li>Close the Kubernetes client</li>
     *   <li>Clear in-memory tracking maps</li>
     *   <li><b>Do not</b> delete managed K8s Jobs —
     *       startup reconciliation will recover them</li>
     * </ol>
     */
    @Override
    public void destroyIf() {
        log.info("Shutting down KubernetesNativeBackend...");

        // 1. Stop accepting new submissions so no K8s Jobs are created during
        //    shutdown. Any submitScript() that sees shuttingDown after this
        //    point returns -1 immediately.
        shuttingDown = true;

        // 2. Run one final poll for terminal K8s Jobs so any Job that
        //    completed just before shutdown gets its DB update.
        if (jobMonitor != null && kubernetesClient != null) {
            try {
                log.info("Running final completed-K8s-Job scan before shutdown...");
                drainTerminalJobs();
            } catch (Exception e) {
                log.warn("Error during final K8s job scan before shutdown", e);
            }
        }

        // 3. Stop the monitor.
        if (jobMonitor != null) {
            try {
                jobMonitor.stop();
                log.info("KubernetesJobMonitor stopped");
            } catch (Exception e) {
                log.warn("Error stopping KubernetesJobMonitor", e);
            }
        }

        if (orphanSweepExecutor != null) {
            orphanSweepExecutor.shutdownNow();
            orphanSweepExecutor = null;
        }

        // 4. Close the Kubernetes client.
        //    K8s Jobs are left in place so startup reconciliation can find
        //    and recover them on the next boot.
        if (kubernetesClient != null) {
            try {
                kubernetesClient.close();
                kubernetesClient = null;
                log.info("KubernetesNativeBackend destroyed successfully (K8s Jobs preserved for recovery).");
            } catch (Exception e) {
                log.warn("Error while closing Kubernetes client", e);
            }
        }

        // 5. Clear tracking maps and release concurrency slots.
        releaseAllSlots();
        execIdToJobName.clear();
        execIdToPairId.clear();
        execIdToOutputDir.clear();
        initialized = false;
        log.info("KubernetesNativeBackend shut down");
    }

    /**
     * Starts the periodic orphan sweep that deletes managed K8s Jobs whose
     * DB pair rows have been removed while the backend is running.
     */
    private void startOrphanSweepScheduler() {
        if (orphanSweepIntervalMs <= 0) {
            log.info("Kubernetes orphan sweep disabled");
            return;
        }

        orphanSweepExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "k8s-orphan-sweep");
            t.setDaemon(true);
            return t;
        });

        orphanSweepExecutor.scheduleAtFixedRate(
            this::sweepOrphanedKubernetesJobs,
            orphanSweepIntervalMs,
            orphanSweepIntervalMs,
            TimeUnit.MILLISECONDS
        );
        log.info(
            "Scheduled Kubernetes orphan sweep every " +
            orphanSweepIntervalMs +
            " ms"
        );
    }

    /**
     * Deletes managed K8s Jobs whose DB pair rows have disappeared.
     * This keeps the cluster from accumulating orphaned Jobs when pairs are
     * deleted while the application remains running.
     */
    private void sweepOrphanedKubernetesJobs() {
        if (!initialized || kubernetesClient == null || shuttingDown) {
            return;
        }

        try {
            JobList jobList = kubernetesClient
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withLabel(MANAGED_LABEL, "true")
                .list();

            int deleted = 0;
            for (Job job : jobList.getItems()) {
                Integer pairId = extractPairId(job);
                Integer execId = extractExecId(job);
                if (pairId == null || execId == null) {
                    continue;
                }

                JobPairs.PairStatusLookupResult lookup =
                    JobPairs.getPairStatusLookup(pairId);
                if (!lookup.isMissing()) {
                    continue;
                }

                deleteKubernetesJob(job);
                execIdToJobName.remove(execId);
                execIdToPairId.remove(execId);
                execIdToOutputDir.remove(execId);
                killedExecIds.add(execId);
                releaseSubmissionSlot(execId);
                deleted++;
            }

            if (deleted > 0) {
                log.info("Kubernetes orphan sweep deleted " + deleted + " orphaned Job(s)");
            }
        } catch (Exception e) {
            log.warn("Kubernetes orphan sweep failed", e);
        }
    }

    /**
     * Runs one final poll for terminal K8s Jobs before shutdown, so any
     * Job that completed just before teardown gets its DB update processed.
     */
    private void drainTerminalJobs() {
        try {
            List<Job> jobs = kubernetesClient
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withLabel(MANAGED_LABEL, "true")
                .list()
                .getItems();

            int processed = 0;
            KubernetesJobCompletionCallback callback =
                new KubernetesJobCompletionCallback();

            for (Job job : jobs) {
                Integer execId = extractExecId(job);
                String jobName = getJobName(job);
                if (execId == null || jobName == null) {
                    continue;
                }

                // Only process terminal jobs — active jobs are left for
                // reconciliation on next startup.
                if (isSucceededJob(job)) {
                    if (callback.onJobComplete(execId, jobName)) {
                        processed++;
                    }
                } else if (isFailedJob(job)) {
                    if (callback.onJobFailed(execId, jobName,
                            summarizeJobFailure(job))) {
                        processed++;
                    }
                }
            }

            if (processed > 0) {
                log.info("Final K8s job scan processed " + processed +
                         " terminal job(s) before shutdown");
            }
        } catch (Exception e) {
            log.error("Failed final K8s job scan before shutdown", e);
        }
    }

    /**
     * Check if an execution code indicates an error.
     *
     * @param execCode The execution code to check
     * @return true if the code indicates an error (negative value)
     */
    @Override
    public boolean isError(int execCode) {
        return execCode <= 0;
    }

    /**
     * Submit a job script for execution as a Kubernetes Job.
     *
     * <p>This method creates a Kubernetes Job resource that:</p>
     * <ul>
     *   <li>Runs the job-runner container image</li>
     *   <li>Mounts the data PVC at the appropriate path</li>
     *   <li>Passes the script path and working directory as arguments</li>
     *   <li>Sets resource limits based on configuration</li>
     * </ul>
     *
     * @param scriptPath Path to the job script (relative to data volume)
     * @param workingDirectoryPath Working directory for the job
     * @param logPath Path for job output logs
     * @return Execution ID (positive) or -1 on error
     */
    @Override
    public int submitScript(
        int pairId,
        String scriptPath,
        String workingDirectoryPath,
        String logPath
    ) {
        if (!initialized) {
            log.error("Backend not initialized");
            return -1;
        }

        // Reject new submissions during graceful shutdown so no K8s Jobs
        // are created after the final drain-and-stop sequence.
        if (shuttingDown) {
            log.warn("Rejecting submission for pair " + pairId +
                     " — backend is shutting down");
            return -1;
        }

        // Enforce concurrency cap to prevent unbounded K8s Job creation.
        if (activeJobCount.get() >= maxConcurrentJobs) {
            log.warn("Rejecting submission for pair " + pairId +
                     " — at concurrency cap (" +
                     activeJobCount.get() +
                     "/" +
                     maxConcurrentJobs +
                     ")");
            return -1;
        }

        int execId = generateExecId();
        String jobName = generateJobName(execId);

        log.info(
            "Submitting K8s Job: execId=" +
                execId +
                ", jobName=" +
                jobName +
                ", script=" +
                scriptPath
        );

        try {
            Job job = buildKubernetesJob(
                pairId,
                execId,
                jobName,
                scriptPath,
                workingDirectoryPath,
                logPath
            );

            kubernetesClient
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .resource(job)
                .create();

            try {
                if (!JobPairs.setStartTime(pairId)) {
                    log.warn(
                        "setStartTime found no row for pair " + pairId +
                        " after Kubernetes Job submission"
                    );
                }
            } catch (Exception e) {
                log.warn(
                    "Failed to set start_time for pair " + pairId +
                    " after Kubernetes Job submission",
                    e
                );
            }

            execIdToJobName.put(execId, jobName);
            execIdToPairId.put(execId, pairId);
            execIdToOutputDir.put(execId, resolveOutputDirectory(logPath));
            activeJobCount.incrementAndGet();
            jobsHoldingSlot.add(execId);
            log.info("K8s Job submitted successfully: " + jobName);
            return execId;
        } catch (Exception e) {
            execIdToJobName.remove(execId);
            execIdToPairId.remove(execId);
            execIdToOutputDir.remove(execId);
            log.error("Failed to submit Kubernetes Job: " + jobName, e);
            return -1;
        }
    }

    private Job buildKubernetesJob(
        int pairId,
        int execId,
        String jobName,
        String scriptPath,
        String workingDirectoryPath,
        String logPath
    ) {
        Map<String, String> labels = new HashMap<>();
        labels.put(MANAGED_LABEL, "true");
        labels.put(EXEC_ID_LABEL, String.valueOf(execId));
        labels.put(PAIR_ID_LABEL, String.valueOf(pairId));

        Path outputDir = resolveOutputDirectory(logPath);

        ResourceRequirementsBuilder resourcesBuilder = new ResourceRequirementsBuilder()
            .addToRequests("memory", new Quantity(memoryLimit))
            .addToRequests("cpu", new Quantity(cpuLimit))
            .addToLimits("memory", new Quantity(memoryLimit))
            .addToLimits("cpu", new Quantity(cpuLimit));

        Map<String, String> nodeSelector = new HashMap<>();
        if (workerNodeSelectorKey != null && !workerNodeSelectorKey.trim().isEmpty()) {
            nodeSelector.put(workerNodeSelectorKey, workerNodeSelectorValue);
        }

        boolean pinToAppNode = requiresSameNodeDataPvc();
        String pinnedNodeName = null;
        if (pinToAppNode && appNodeName != null && !appNodeName.trim().isEmpty()) {
            pinnedNodeName = appNodeName;
        }
        if (pinToAppNode && (appNodeName == null || appNodeName.trim().isEmpty())) {
            log.warn(
                "Shared data PVC uses " +
                dataPvcAccessMode +
                " but STAREXEC_K8S_APP_NODE_NAME is unavailable; job scheduling may fail"
            );
        }

        return new JobBuilder()
            .withNewMetadata()
                .withName(jobName)
                .withNamespace(namespace)
                .addToLabels(labels)
            .endMetadata()
            .withNewSpec()
                .withBackoffLimit(backoffLimit)
                .withTtlSecondsAfterFinished(ttlSecondsAfterFinished)
                .withNewTemplate()
                    .withNewMetadata()
                        .addToLabels(labels)
                    .endMetadata()
                    .withNewSpec()
                        .withServiceAccountName(serviceAccountName)
                        .withRestartPolicy("Never")
                        .withNodeName(pinnedNodeName)
                        .withNodeSelector(nodeSelector)
                        .addNewContainer()
                            .withName("job-runner")
                            .withImage(jobImage)
                            .withCommand("/bin/bash")
                            .withArgs(scriptPath)
                            .withWorkingDir(workingDirectoryPath)
                            .addNewEnv()
                                .withName("STAREXEC_PAIR_ID")
                                .withValue(String.valueOf(pairId))
                            .endEnv()
                            .addNewEnv()
                                .withName("CONTAINER_MODE")
                                .withValue("true")
                            .endEnv()
                            .addNewEnv()
                                .withName("STAREXEC_OUTPUT_DIR")
                                .withValue(outputDir.toString())
                            .endEnv()
                            .addNewVolumeMount()
                                .withName("starexec-data")
                                .withMountPath("/app/data")
                            .endVolumeMount()
                            .withResources(resourcesBuilder.build())
                        .endContainer()
                        .addNewVolume()
                            .withName("starexec-data")
                            .withNewPersistentVolumeClaim()
                                .withClaimName(dataPvcName)
                            .endPersistentVolumeClaim()
                        .endVolume()
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build();
    }

    private boolean requiresSameNodeDataPvc() {
        return "ReadWriteOnce".equals(dataPvcAccessMode) ||
            "ReadWriteOncePod".equals(dataPvcAccessMode);
    }

    private Path resolveOutputDirectory(String logPath) {
        if (logPath == null || logPath.trim().isEmpty()) {
            return Paths.get(R.JOB_OUTPUT_DIRECTORY);
        }

        Path path = Paths.get(logPath);
        Path parent = path.getParent();
        if (parent != null) {
            return parent;
        }
        return Paths.get(R.JOB_OUTPUT_DIRECTORY);
    }

    /**
     * Generate a unique execution ID.
     */
    private int generateExecId() {
        synchronized (idLock) {
            int id = nextExecId++;
            if (nextExecId < 0) {
                nextExecId = 1; // Wrap around
            }
            return id;
        }
    }

    /**
     * Releases the concurrency slot held by {@code execId}, if any.
     */
    private void releaseSubmissionSlot(int execId) {
        if (!jobsHoldingSlot.remove(execId)) {
            return;
        }
        int remaining = activeJobCount.decrementAndGet();
        log.debug(
            "Released K8s concurrency slot (execId=" +
            execId +
            ", active=" +
            remaining +
            ", max=" +
            maxConcurrentJobs +
            ")"
        );
    }

    /**
     * Releases all concurrency slots (killAll / destroyIf shutdown path).
     */
    private void releaseAllSlots() {
        int released = 0;
        for (Integer execId : jobsHoldingSlot) {
            releaseSubmissionSlot(execId);
            released++;
        }
        if (released > 0) {
            log.info("Released " + released +
                     " K8s concurrency slots during bulk teardown");
        }
    }

    /**
     * Rebuilds in-memory execution tracking from Kubernetes Job labels and
     * reconciles stale ENQUEUED/RUNNING database rows after a StarExec restart.
     *
     * <p>Recovery is intentionally conservative:</p>
     * <ul>
     *   <li>active K8s Job evidence rebuilds tracking and marks the pair RUNNING;</li>
     *   <li>terminal K8s Job evidence is processed through the normal completion callback;</li>
     *   <li>ENQUEUED DB rows without Job evidence are reset to PENDING_SUBMIT;</li>
     *   <li>RUNNING DB rows without Job evidence are marked as terminal failure.</li>
     * </ul>
     */
    private void reconcileOrphanedPairs() {
        try {
            log.info("Starting Kubernetes orphaned-pair reconciliation...");

            JobList jobList = kubernetesClient
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withLabel(MANAGED_LABEL, "true")
                .list();

            Map<Integer, Job> pairIdToActiveJob = new HashMap<>();
            Map<Integer, Job> pairIdToTerminalJob = new HashMap<>();
            int malformedJobs = 0;
            int orphanedKubernetesJobs = 0;
            int maxRecoveredExecId = 0;

            for (Job job : jobList.getItems()) {
                Integer pairId = extractPairId(job);
                Integer execId = extractExecId(job);
                if (pairId == null || pairId <= 0 || execId == null || execId <= 0) {
                    malformedJobs++;
                    continue;
                }

                JobPairs.PairStatusLookupResult lookup =
                    JobPairs.getPairStatusLookup(pairId);
                if (lookup.isMissing()) {
                    deleteKubernetesJob(job);
                    orphanedKubernetesJobs++;
                    continue;
                }
                if (lookup.isError()) {
                    log.warn("Skipping reconciliation for pair " + pairId +
                             " because DB status lookup failed");
                    continue;
                }

                if (isTerminalJob(job)) {
                    pairIdToTerminalJob.put(pairId, job);
                } else {
                    pairIdToActiveJob.put(pairId, job);
                }

                if (execId > maxRecoveredExecId) {
                    maxRecoveredExecId = execId;
                }
            }

            List<Integer> enqueuedIds = JobPairs.getPairIdsByStatusCode(
                StatusCode.STATUS_ENQUEUED.getVal());
            List<Integer> runningIds = JobPairs.getPairIdsByStatusCode(
                StatusCode.STATUS_RUNNING.getVal());

            int enqueuedReset = 0, enqueuedProcessed = 0, enqueuedRebuilt = 0;
            int runningFailed = 0, runningProcessed = 0, runningRebuilt = 0;

            for (int pairId : enqueuedIds) {
                Job activeJob = pairIdToActiveJob.get(pairId);
                Job terminalJob = pairIdToTerminalJob.get(pairId);
                if (activeJob != null) {
                    rebuildTrackingFromJob(activeJob);
                    enqueuedRebuilt++;
                } else if (terminalJob != null) {
                    if (processReconciledJobThroughCallback(terminalJob)) {
                        enqueuedProcessed++;
                    }
                } else if (JobPairs.tryResetEnqueuedToPending(pairId)
                        == JobPairs.ConditionalPairUpdateResult.UPDATED) {
                    enqueuedReset++;
                }
            }

            for (int pairId : runningIds) {
                Job activeJob = pairIdToActiveJob.get(pairId);
                Job terminalJob = pairIdToTerminalJob.get(pairId);
                if (activeJob != null) {
                    rebuildTrackingFromJob(activeJob);
                    runningRebuilt++;
                } else if (terminalJob != null) {
                    if (processReconciledJobThroughCallback(terminalJob)) {
                        runningProcessed++;
                    }
                } else if (JobPairs.tryMarkRunningAsFailed(pairId)
                        == JobPairs.ConditionalPairUpdateResult.UPDATED) {
                    runningFailed++;
                }
            }

            if (maxRecoveredExecId > 0) {
                synchronized (idLock) {
                    if (maxRecoveredExecId >= nextExecId) {
                        nextExecId = maxRecoveredExecId + 1;
                    }
                }
            }

            log.info(
                "Kubernetes reconciliation complete: ENQUEUED reset=" +
                    enqueuedReset +
                    " processed=" +
                    enqueuedProcessed +
                    " rebuilt=" +
                    enqueuedRebuilt +
                    "; RUNNING failed=" +
                    runningFailed +
                    " processed=" +
                    runningProcessed +
                    " rebuilt=" +
                    runningRebuilt +
                    "; malformedJobs=" +
                    malformedJobs +
                    "; orphanedKubernetesJobs=" +
                    orphanedKubernetesJobs
            );
        } catch (Exception e) {
            log.error("Failed to reconcile Kubernetes orphaned pairs on startup", e);
        }
    }

    private boolean processReconciledJobThroughCallback(Job job) {
        Integer execId = extractExecId(job);
        String jobName = getJobName(job);
        if (execId == null || jobName == null) {
            return false;
        }

        rebuildTrackingFromJob(job);
        KubernetesJobCompletionCallback callback =
            new KubernetesJobCompletionCallback();
        if (isSucceededJob(job)) {
            return callback.onJobComplete(execId, jobName);
        }
        return callback.onJobFailed(execId, jobName, summarizeJobFailure(job));
    }

    private void rebuildTrackingFromJob(Job job) {
        Integer execId = extractExecId(job);
        Integer pairId = extractPairId(job);
        String jobName = getJobName(job);
        if (execId == null || pairId == null || jobName == null) {
            return;
        }

        execIdToJobName.put(execId, jobName);
        execIdToPairId.put(execId, pairId);
        execIdToOutputDir.put(execId, resolveOutputDirectory(job, pairId));

        // Acquire a concurrency slot so the capacity tracker stays in sync
        // with the number of reconstructed in-memory tracking entries.
        if (jobsHoldingSlot.add(execId)) {
            int count = activeJobCount.incrementAndGet();
            log.debug(
                "Reclaimed K8s concurrency slot during reconciliation (execId=" +
                execId +
                ", active=" +
                count +
                ", max=" +
                maxConcurrentJobs +
                ")"
            );
        }

        if (!isTerminalJob(job) && isActiveJob(job)) {
            markPairRunningSafely(pairId, "startup reconciliation");
        }
    }

    private boolean isActiveJob(Job job) {
        if (job == null || job.getStatus() == null) {
            return false;
        }

        Integer active = job.getStatus().getActive();
        return active != null && active > 0;
    }

    private JobPairs.ConditionalPairUpdateResult markPairRunningSafely(
        int pairId,
        String source
    ) {
        if (pairId <= 0) {
            return JobPairs.ConditionalPairUpdateResult.STALE;
        }

        try {
            JobPairs.ConditionalPairUpdateResult result = JobPairs.trySetPairRunning(pairId);
            if (result == JobPairs.ConditionalPairUpdateResult.UPDATED) {
                log.debug(
                    "Marked pair " + pairId + " as STATUS_RUNNING from " + source
                );
            } else if (result == JobPairs.ConditionalPairUpdateResult.STALE) {
                log.debug(
                    "Skipping STATUS_RUNNING update for stale or completed pair " +
                    pairId +
                    " from " +
                    source
                );
            } else {
                log.warn(
                    "Failed to set running status for pair " + pairId +
                    " from " +
                    source
                );
            }
            return result;
        } catch (Exception e) {
            log.warn(
                "Failed to set running status for pair " + pairId +
                " from " +
                source,
                e
            );
            return JobPairs.ConditionalPairUpdateResult.ERROR;
        }
    }

    private Path resolveOutputDirectory(Job job, int pairId) {
        if (job != null && job.getMetadata() != null
                && job.getMetadata().getAnnotations() != null) {
            String outputDir =
                job.getMetadata().getAnnotations().get(OUTPUT_DIR_ANNOTATION);
            if (outputDir != null && !outputDir.trim().isEmpty()) {
                return Paths.get(outputDir);
            }
        }

        try {
            return resolveOutputDirectory(JobPairs.getStdout(pairId));
        } catch (Exception e) {
            log.warn(
                "Could not reconstruct output directory for pair " + pairId +
                "; using default", e);
            return Paths.get(R.JOB_OUTPUT_DIRECTORY);
        }
    }

    private Integer extractExecId(Job job) {
        return extractIntegerLabel(job, EXEC_ID_LABEL);
    }

    private Integer extractPairId(Job job) {
        return extractIntegerLabel(job, PAIR_ID_LABEL);
    }

    private Integer extractIntegerLabel(Job job, String labelKey) {
        if (job == null || job.getMetadata() == null
                || job.getMetadata().getLabels() == null) {
            return null;
        }
        String value = job.getMetadata().getLabels().get(labelKey);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Malformed Kubernetes label " + labelKey + "='" + value + "'");
            return null;
        }
    }

    private String getJobName(Job job) {
        if (job == null || job.getMetadata() == null) {
            return null;
        }
        return job.getMetadata().getName();
    }

    private boolean isTerminalJob(Job job) {
        return isSucceededJob(job) || isFailedJob(job);
    }

    private boolean isSucceededJob(Job job) {
        if (job == null || job.getStatus() == null) {
            return false;
        }
        Integer succeeded = job.getStatus().getSucceeded();
        if (succeeded != null && succeeded > 0) {
            return true;
        }
        return hasTrueCondition(job, "Complete");
    }

    private boolean isFailedJob(Job job) {
        if (job == null || job.getStatus() == null) {
            return false;
        }
        Integer failed = job.getStatus().getFailed();
        if (failed != null && failed > 0) {
            return true;
        }
        return hasTrueCondition(job, "Failed");
    }

    private boolean hasTrueCondition(Job job, String type) {
        if (job.getStatus() == null || job.getStatus().getConditions() == null) {
            return false;
        }
        for (JobCondition condition : job.getStatus().getConditions()) {
            if (condition == null) {
                continue;
            }
            if (type.equalsIgnoreCase(condition.getType())
                    && "True".equalsIgnoreCase(condition.getStatus())) {
                return true;
            }
        }
        return false;
    }

    private String summarizeJobFailure(Job job) {
        if (job == null || job.getStatus() == null
                || job.getStatus().getConditions() == null) {
            return "Kubernetes Job failed";
        }
        for (JobCondition condition : job.getStatus().getConditions()) {
            if (condition == null || !"Failed".equalsIgnoreCase(condition.getType())) {
                continue;
            }
            String reason = condition.getReason() != null
                ? condition.getReason() : "unknown";
            String message = condition.getMessage() != null
                ? condition.getMessage() : "no message";
            return reason + ": " + message;
        }
        return "Kubernetes Job failed";
    }

    private void deleteKubernetesJob(Job job) {
        String jobName = getJobName(job);
        if (jobName == null) {
            return;
        }
        try {
            kubernetesClient
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withName(jobName)
                .delete();
        } catch (Exception e) {
            log.warn("Failed to delete orphaned Kubernetes Job: " + jobName, e);
        }
    }

    /**
     * Generate a Kubernetes-compliant job name from execution ID.
     */
    private String generateJobName(int execId) {
        return String.format(
            "starexec-job-%d-%d",
            execId,
            System.currentTimeMillis() % 100000
        );
    }

    /**
     * Kill a specific job by execution ID.
     *
     * @param execId The execution ID of the job to kill
     * @return true if successful, false otherwise
     */
    @Override
    public boolean killPair(int execId) {
        String jobName = execIdToJobName.get(execId);
        if (jobName == null) {
            log.info("No job found for execId: " + execId);
            return false;
        }

        if (kubernetesClient == null) {
            log.warn(
                "Kubernetes client not initialized; cannot kill execId: " +
                execId +
                ". Local tracking preserved for retry or destroyIf cleanup."
            );
            return false;
        }

        log.info("Killing K8s Job: execId=" + execId + ", jobName=" + jobName);

        try {
            List<?> deletedResources = kubernetesClient
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withName(jobName)
                .delete();

            boolean deleted = deletedResources != null && !deletedResources.isEmpty();

            execIdToJobName.remove(execId);
            execIdToPairId.remove(execId);
            execIdToOutputDir.remove(execId);
            killedExecIds.add(execId);
            releaseSubmissionSlot(execId);
            if (!deleted) {
                log.warn("Kubernetes API reported no deletion for job: " + jobName);
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to kill Kubernetes job: " + jobName, e);
            return false;
        }
    }

    /**
     * Kill all running jobs.
     *
     * @return true if successful, false otherwise
     */
    @Override
    public boolean killAll() {
        log.info("Killing all K8s Jobs in namespace: " + namespace);

        if (kubernetesClient == null) {
            log.info(
                "Kubernetes client not initialized; clearing local job tracking only"
            );
            releaseAllSlots();
            execIdToJobName.clear();
            execIdToPairId.clear();
            execIdToOutputDir.clear();
            return false;
        }

        try {
            kubernetesClient
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withLabel(MANAGED_LABEL, "true")
                .delete();
            return true;
        } catch (Exception e) {
            log.error("Failed to kill all Kubernetes jobs", e);
            return false;
        } finally {
            releaseAllSlots();
            execIdToJobName.clear();
            execIdToPairId.clear();
            execIdToOutputDir.clear();
        }
    }

    /**
     * Get status of all running jobs.
     *
     * @return Status string describing running jobs
     */
    @Override
    public String getRunningJobsStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Kubernetes Jobs Status ===\n");
        sb.append("Namespace: ").append(namespace).append("\n");

        try {
            JobList jobs = kubernetesClient
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withLabel(MANAGED_LABEL, "true")
                .list();

            sb.append("Tracked Jobs: ").append(jobs.getItems().size()).append("\n\n");
            for (Job job : jobs.getItems()) {
                String jobName = job.getMetadata() != null ? job.getMetadata().getName() : "unknown";
                String status = summarizeJobStatus(job);
                sb.append(jobName).append(": ").append(status).append("\n");
            }
        } catch (Exception e) {
            sb.append("Failed to query jobs: ").append(e.getMessage()).append("\n");
            log.warn("Failed to query running Kubernetes jobs", e);
        }

        return sb.toString();
    }

    private String summarizeJobStatus(Job job) {
        if (job.getStatus() == null) {
            return "pending";
        }

        Integer succeeded = job.getStatus().getSucceeded();
        if (succeeded != null && succeeded > 0) {
            return "succeeded";
        }

        Integer failed = job.getStatus().getFailed();
        if (failed != null && failed > 0) {
            return "failed";
        }

        Integer active = job.getStatus().getActive();
        if (active != null && active > 0) {
            return "running";
        }

        return "pending";
    }

    /**
     * Get all active execution IDs.
     *
     * @return Set of active execution IDs
     */
    @Override
    public Set<Integer> getActiveExecutionIds() throws IOException {
        Set<Integer> activeIds = new HashSet<>();

        try {
            JobList jobs = kubernetesClient
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withLabel(MANAGED_LABEL, "true")
                .list();

            for (Job job : jobs.getItems()) {
                if (job.getStatus() != null) {
                    Integer succeeded = job.getStatus().getSucceeded();
                    Integer failed = job.getStatus().getFailed();
                    if ((succeeded != null && succeeded > 0) || (failed != null && failed > 0)) {
                        continue;
                    }
                }

                if (job.getMetadata() == null || job.getMetadata().getLabels() == null) {
                    continue;
                }

                String execIdValue = job.getMetadata().getLabels().get(EXEC_ID_LABEL);
                if (execIdValue == null || execIdValue.trim().isEmpty()) {
                    continue;
                }

                try {
                    activeIds.add(Integer.parseInt(execIdValue));
                } catch (NumberFormatException e) {
                    log.warn("Skipping job with malformed exec ID label: " + execIdValue);
                }
            }
            return activeIds;
        } catch (Exception e) {
            log.error("Failed to query active execution IDs from Kubernetes", e);
            throw new IOException("Failed to query active execution IDs", e);
        }
    }

    // =========================================================================
    // Node and Queue Management
    // =========================================================================

    /**
     * Get all worker nodes in the Kubernetes cluster.
     *
     * <p>Returns nodes that are labeled for StarExec workloads.</p>
     *
     * @return Array of node names
     */
    @Override
    public String[] getWorkerNodes() {
        log.debug("Getting worker nodes from Kubernetes cluster");

        try {
            NodeList nodes = kubernetesClient
                .nodes()
                .withLabel(workerNodeSelectorKey, workerNodeSelectorValue)
                .list();

            List<String> nodeNames = new ArrayList<>();
            for (Node node : nodes.getItems()) {
                if (node.getMetadata() != null && node.getMetadata().getName() != null) {
                    nodeNames.add(node.getMetadata().getName());
                }
            }
            return nodeNames.toArray(new String[0]);
        } catch (Exception e) {
            log.warn("Failed to read Kubernetes worker nodes", e);
            return new String[0];
        }
    }

    /**
     * Get all queues (derived from node labels).
     *
     * @return Array of queue names
     */
    @Override
    public String[] getQueues() {
        log.debug("Getting queues from Kubernetes node labels");

        Set<String> queues = new HashSet<>();

        try {
            NodeList nodes = kubernetesClient
                .nodes()
                .withLabel(workerNodeSelectorKey, workerNodeSelectorValue)
                .list();

            for (Node node : nodes.getItems()) {
                if (node.getMetadata() == null || node.getMetadata().getLabels() == null) {
                    continue;
                }

                String queue = node.getMetadata().getLabels().get(queueLabelKey);
                if (queue != null && !queue.trim().isEmpty()) {
                    queues.add(queue);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read queue labels from Kubernetes nodes", e);
        }

        // Only add default when no queues discovered from node labels
        if (queues.isEmpty()) {
            queues.add(DEFAULT_QUEUE_NAME);
        }

        return queues.toArray(new String[0]);
    }

    /**
     * Get mapping of nodes to queues.
     *
     * @return Map of node name to queue name
     */
    @Override
    public Map<String, String> getNodeQueueAssociations() {
        Map<String, String> associations = new HashMap<>();

        try {
            NodeList nodes = kubernetesClient
                .nodes()
                .withLabel(workerNodeSelectorKey, workerNodeSelectorValue)
                .list();

            for (Node node : nodes.getItems()) {
                if (node.getMetadata() == null || node.getMetadata().getName() == null) {
                    continue;
                }

                String queueName = DEFAULT_QUEUE_NAME;
                if (node.getMetadata().getLabels() != null) {
                    String queue = node.getMetadata().getLabels().get(queueLabelKey);
                    if (queue != null && !queue.trim().isEmpty()) {
                        queueName = queue;
                    }
                }

                associations.put(node.getMetadata().getName(), queueName);
            }
        } catch (Exception e) {
            log.warn("Failed to read node/queue associations from Kubernetes", e);
        }

        return associations;
    }

    /**
     * Clear any error states on nodes.
     *
     * <p>For Kubernetes, this could uncordon nodes or clear taints.</p>
     *
     * @return true if successful
     */
    @Override
    public boolean clearNodeErrorStates() {
        log.info("clearNodeErrorStates called for Kubernetes backend");

        try {
            NodeList nodes = kubernetesClient
                .nodes()
                .withLabel(workerNodeSelectorKey, workerNodeSelectorValue)
                .list();

            for (Node node : nodes.getItems()) {
                if (node.getMetadata() == null || node.getMetadata().getName() == null) {
                    continue;
                }
                NodeSpec spec = node.getSpec();
                if (spec != null && Boolean.TRUE.equals(spec.getUnschedulable())) {
                    kubernetesClient
                        .nodes()
                        .withName(node.getMetadata().getName())
                        .edit(n -> {
                            if (n.getSpec() != null) {
                                n.getSpec().setUnschedulable(false);
                            }
                            return n;
                        });
                }
            }

            return true;
        } catch (Exception e) {
            log.error("Failed to clear node error states in Kubernetes", e);
            return false;
        }
    }

    /**
     * Delete a queue by removing labels from nodes.
     *
     * @param queueName Name of the queue to delete
     */
    @Override
    public void deleteQueue(String queueName) {
        log.info("Deleting queue label from nodes for queue: " + queueName);

        try {
            NodeList nodes = kubernetesClient
                .nodes()
                .withLabel(workerNodeSelectorKey, workerNodeSelectorValue)
                .withLabel(queueLabelKey, queueName)
                .list();

            for (Node node : nodes.getItems()) {
                if (node.getMetadata() == null || node.getMetadata().getName() == null) {
                    continue;
                }

                kubernetesClient
                    .nodes()
                    .withName(node.getMetadata().getName())
                    .edit(n -> {
                        if (n.getMetadata() != null && n.getMetadata().getLabels() != null) {
                            n.getMetadata().getLabels().remove(queueLabelKey);
                        }
                        return n;
                    });
            }
        } catch (Exception e) {
            log.error("Failed to delete queue from Kubernetes nodes: " + queueName, e);
        }
    }

    /**
     * Create a new queue by labeling nodes.
     *
     * @param newQueueName Name for the new queue
     * @param nodeNames Nodes to assign to the queue
     * @param sourceQueueNames Previous queue assignments (ignored)
     * @return true if successful
     */
    @Override
    public boolean createQueue(
        String newQueueName,
        String[] nodeNames,
        String[] sourceQueueNames
    ) {
        return createQueueWithSlots(
            newQueueName,
            nodeNames,
            sourceQueueNames,
            null
        );
    }

    /**
     * Create a queue with slot configuration.
     *
     * @param newQueueName Name for the new queue
     * @param nodeNames Nodes to assign
     * @param sourceQueueNames Previous assignments (ignored)
     * @param slots Number of slots (stored as annotation)
     * @return true if successful
     */
    @Override
    public boolean createQueueWithSlots(
        String newQueueName,
        String[] nodeNames,
        String[] sourceQueueNames,
        Integer slots
    ) {
        if (nodeNames == null || nodeNames.length == 0) {
            log.warn("No nodes specified for queue creation");
            return false;
        }

        log.info(
            "Creating queue '" +
                newQueueName +
                "' with " +
                nodeNames.length +
                " nodes, " +
                slots +
                " slots"
        );

        try {
            for (String nodeName : nodeNames) {
                kubernetesClient
                    .nodes()
                    .withName(nodeName)
                    .edit(n -> {
                        if (n.getMetadata() == null) {
                            return n;
                        }
                        if (n.getMetadata().getLabels() == null) {
                            n.getMetadata().setLabels(new HashMap<>());
                        }
                        n.getMetadata().getLabels().put(queueLabelKey, newQueueName);
                        if (slots != null) {
                            n.getMetadata().getLabels().put(LABEL_PREFIX + "slots", String.valueOf(slots));
                        }
                        return n;
                    });
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to create queue in Kubernetes: " + newQueueName, e);
            return false;
        }
    }

    /**
     * Move nodes between queues.
     *
     * @param destQueueName Destination queue
     * @param nodeNames Nodes to move
     * @param sourceQueueNames Source queues (ignored)
     */
    @Override
    public void moveNodes(
        String destQueueName,
        String[] nodeNames,
        String[] sourceQueueNames
    ) {
        if (nodeNames == null || nodeNames.length == 0) {
            return;
        }

        log.info(
            "Moving " +
                nodeNames.length +
                " nodes to queue '" +
                destQueueName +
                "'"
        );

        for (String nodeName : nodeNames) {
            try {
                kubernetesClient
                    .nodes()
                    .withName(nodeName)
                    .edit(n -> {
                        if (n.getMetadata() == null) {
                            return n;
                        }
                        if (n.getMetadata().getLabels() == null) {
                            n.getMetadata().setLabels(new HashMap<>());
                        }

                        if (DEFAULT_QUEUE_NAME.equals(destQueueName)) {
                            n.getMetadata().getLabels().remove(queueLabelKey);
                        } else {
                            n.getMetadata().getLabels().put(queueLabelKey, destQueueName);
                        }
                        return n;
                    });
            } catch (Exception e) {
                log.error("Failed to move node to queue: node=" + nodeName + ", queue=" + destQueueName, e);
            }
        }
    }

    /**
     * Move a single node to a queue.
     *
     * @param nodeName Node to move
     * @param queueName Destination queue
     */
    @Override
    public void moveNode(String nodeName, String queueName) {
        moveNodes(queueName, new String[] { nodeName }, null);
    }

    @Override
    public void clearPairTracking(int pairId) {
        // Remove mapping so stale pair IDs are not returned by resolvePairId
        // after a pair has been rerun or removed.
        execIdToPairId.values().removeIf(v -> v.equals(pairId));
    }

    private final class KubernetesJobCompletionCallback
        implements KubernetesJobMonitor.JobCompletionCallback {

        @Override
        public boolean onJobRunning(int execId, String jobName) {
            if (killedExecIds.contains(execId)) {
                log.debug(
                    "Skipping running callback for killed execId " +
                    execId +
                    " (K8s job " +
                    jobName +
                    ")"
                );
                return true;
            }

            Integer pairId = resolvePairId(execId, jobName);
            if (pairId == null) {
                log.warn("Unable to resolve pair ID for active job: " + jobName);
                return false;
            }

            return markPairRunningSafely(pairId, "Kubernetes active-job polling") !=
                JobPairs.ConditionalPairUpdateResult.ERROR;
        }

        @Override
        public boolean onJobComplete(int execId, String jobName) {
            // Skip processing if this execId was killed — the kill path
            // already removed tracking maps and released the concurrency slot.
            if (killedExecIds.contains(execId)) {
                log.debug(
                    "Skipping completion callback for killed execId " +
                    execId +
                    " (K8s job " +
                    jobName +
                    ")"
                );
                killedExecIds.remove(execId);
                return true;
            }

            Integer pairId = resolvePairId(execId, jobName);
            if (pairId == null) {
                log.warn("Unable to resolve pair ID for completed job: " + jobName);
                return false;
            }

            try {
                // Guard: skip DB update when the pair row has disappeared or the
                // job is no longer submit-eligible. Without this check a K8s job
                // that completes after pause/kill/delete writes a status into a
                // stale or absent row, causing P0002 or overwriting a terminal.
                JobPairs.PairStatusLookupResult lookup = JobPairs.getPairStatusLookup(pairId);
                if (lookup.isMissing()) {
                    log.debug(
                        "Skipping completion update for stale pair " +
                        pairId +
                        " (K8s job " +
                        jobName +
                        ")"
                    );
                    execIdToJobName.remove(execId);
                    execIdToPairId.remove(execId);
                    execIdToOutputDir.remove(execId);
                    releaseSubmissionSlot(execId);
                    return true;
                }
                if (lookup.isError()) {
                    log.warn(
                        "Could not determine whether pair " +
                        pairId +
                        " still exists after K8s job completion; retrying"
                    );
                    return false;
                }

                int terminalStatus = readTerminalStatus(execId, StatusCode.STATUS_COMPLETE.getVal());
                int stageNumber = readStageNumber(execId, 1);

                PairStatusResult updated = JobPairs.setPairStatusPreciseResult(
                    pairId,
                    stageNumber,
                    terminalStatus,
                    StatusCode.STATUS_NOT_REACHED.getVal(),
                    false
                );
                if (updated == PairStatusResult.FAILED) {
                    log.warn(
                        "Failed updating completed status for pair " +
                        pairId +
                        "; Kubernetes completion will be retried"
                    );
                    return false;
                }
                if (updated == PairStatusResult.SUPERSEDED) {
                    // Another writer already recorded a different terminal result, so
                    // this pair is finished and retrying can never succeed. Returning
                    // false here would leave the execId out of completedExecIds and the
                    // next poll would process the same job again, forever. Treat it as
                    // handled so the Kubernetes job is cleaned up.
                    log.info(
                        "Pair " + pairId +
                        " already had a different terminal status; keeping the recorded" +
                        " result and cleaning up the Kubernetes job"
                    );
                }

                // Set end_time.
                try {
                    if (!JobPairs.setEndTime(pairId)) {
                        log.warn("setEndTime found no row for pair " + pairId);
                    }
                } catch (Exception e) {
                    log.warn("Failed to set end_time for pair " + pairId, e);
                }

                // Persist run-solver statistics (wallclock, cpu, memory, disk)
                // so K8s-native jobs produce the same data as container jobs.
                persistRunSolverStats(execId, pairId, stageNumber);

                // Persist attributes generated by post-processors
                persistAttributes(execId, pairId, stageNumber);
            } catch (Exception e) {
                log.error("Failed updating completed status for pair " + pairId, e);
                return false;
            }

            execIdToJobName.remove(execId);
            execIdToPairId.remove(execId);
            execIdToOutputDir.remove(execId);
            releaseSubmissionSlot(execId);
            return true;
        }

        @Override
        public boolean onJobFailed(int execId, String jobName, String reason) {
            // Skip processing if this execId was killed — the kill path
            // already removed tracking maps and released the concurrency slot.
            if (killedExecIds.contains(execId)) {
                log.debug(
                    "Skipping failure callback for killed execId " +
                    execId +
                    " (K8s job " +
                    jobName +
                    ")"
                );
                killedExecIds.remove(execId);
                return true;
            }

            Integer pairId = resolvePairId(execId, jobName);
            if (pairId == null) {
                log.warn("Unable to resolve pair ID for failed job: " + jobName + ". Reason: " + reason);
                return false;
            }

            try {
                // Guard: skip DB update when the pair row has disappeared or the
                // job is no longer submit-eligible.
                JobPairs.PairStatusLookupResult lookup = JobPairs.getPairStatusLookup(pairId);
                if (lookup.isMissing()) {
                    log.debug(
                        "Skipping failure update for stale pair " +
                        pairId +
                        " (K8s job " +
                        jobName +
                        ")"
                    );
                    execIdToJobName.remove(execId);
                    execIdToPairId.remove(execId);
                    execIdToOutputDir.remove(execId);
                    releaseSubmissionSlot(execId);
                    return true;
                }
                if (lookup.isError()) {
                    log.warn(
                        "Could not determine whether pair " +
                        pairId +
                        " still exists after K8s job failure; retrying"
                    );
                    return false;
                }

                int stageNumber = readStageNumber(execId, 1);

                boolean updated = JobPairs.setPairStatusPrecise(
                    pairId,
                    stageNumber,
                    StatusCode.ERROR_RUNSCRIPT.getVal(),
                    StatusCode.STATUS_NOT_REACHED.getVal()
                );
                if (!updated) {
                    log.warn(
                        "Failed updating failed status for pair " +
                        pairId +
                        ". Reason: " +
                        reason +
                        "; Kubernetes completion will be retried"
                    );
                    return false;
                }

                // Set end_time for the failed pair.
                try {
                    if (!JobPairs.setEndTime(pairId)) {
                        log.warn("setEndTime found no row for failed pair " + pairId);
                    }
                } catch (Exception e) {
                    log.warn("Failed to set end_time for failed pair " + pairId, e);
                }
            } catch (Exception e) {
                log.error("Failed updating failed status for pair " + pairId + ". Reason: " + reason, e);
                return false;
            }

            execIdToJobName.remove(execId);
            execIdToPairId.remove(execId);
            execIdToOutputDir.remove(execId);
            releaseSubmissionSlot(execId);
            return true;
        }

        private Integer resolvePairId(int execId, String jobName) {
            Integer pairIdFromMap = execIdToPairId.get(execId);
            if (pairIdFromMap != null && pairIdFromMap > 0) {
                return pairIdFromMap;
            }

            try {
                Job job = kubernetesClient
                    .batch()
                    .v1()
                    .jobs()
                    .inNamespace(namespace)
                    .withName(jobName)
                    .get();

                if (job == null || job.getMetadata() == null || job.getMetadata().getLabels() == null) {
                    return null;
                }

                String pairIdValue = job.getMetadata().getLabels().get(PAIR_ID_LABEL);
                if (pairIdValue == null || pairIdValue.trim().isEmpty()) {
                    return null;
                }

                Integer parsed = Integer.parseInt(pairIdValue);
                execIdToPairId.put(execId, parsed);
                return parsed;
            } catch (Exception e) {
                log.warn(
                    "Failed to resolve pair ID for execId=" + execId + ", jobName=" + jobName,
                    e
                );
                return null;
            }
        }

        private int readTerminalStatus(int execId, int defaultStatus) {
            Path statusPath = resolveStatusPath(execId);
            if (statusPath == null || !Files.exists(statusPath)) {
                return defaultStatus;
            }

            try {
                String json = Files.readString(statusPath);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                if (root.has("status") && !root.get("status").isJsonNull()) {
                    return root.get("status").getAsInt();
                }
            } catch (Exception e) {
                log.warn("Failed to parse status.json for execId " + execId, e);
            }

            return defaultStatus;
        }

        private int readStageNumber(int execId, int defaultStage) {
            Path statusPath = resolveStatusPath(execId);
            if (statusPath == null || !Files.exists(statusPath)) {
                return defaultStage;
            }

            try {
                String json = Files.readString(statusPath);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                if (root.has("stageNumber") && !root.get("stageNumber").isJsonNull()) {
                    return root.get("stageNumber").getAsInt();
                }
            } catch (Exception e) {
                log.warn("Failed to parse stage number from status.json for execId " + execId, e);
            }

            return defaultStage;
        }

        private Path resolveStatusPath(int execId) {
            Path outputDir = execIdToOutputDir.get(execId);
            if (outputDir == null) {
                return null;
            }
            return outputDir.resolve("status.json");
        }

        /**
         * Persists run-solver statistics (wallclock, cpu, memory, disk) from
         * the shared data volume so that K8s-native jobs produce the same
         * resource-usage data as container-mode jobs.
         */
        private void persistRunSolverStats(int execId, int pairId, int stageNumber) {
            Path outputDir = execIdToOutputDir.get(execId);
            if (outputDir == null) {
                return;
            }

            Path statsPath = outputDir.resolve("stats.json");
            if (!Files.exists(statsPath)) {
                return;
            }

            ContainerJobMonitor.RunsolverStats stats = new ContainerJobMonitor.RunsolverStats();
            try {
                String json = Files.readString(statsPath);
                parseStatsJsonInto(json, stats);
            } catch (Exception e) {
                log.warn("Could not read stats.json for pair " + pairId, e);
                return;
            }

            try {
                String nodeName = resolveStatsNodeName(stats);
                boolean ok = JobPairs.updateRunSolverStats(
                    pairId,
                    nodeName,
                    stats.wallclockTime,
                    stats.cpuTime,
                    stats.userTime,
                    stats.systemTime,
                    stats.maxVirtualMemory,
                    stats.maxResidentSetSize,
                    stageNumber,
                    stats.diskSize
                );
                if (ok) {
                    log.debug("Persisted run stats for pair " + pairId + ": " + stats);
                } else {
                    log.warn("Failed to persist run stats for pair " + pairId);
                }
            } catch (Exception e) {
                log.warn("Exception persisting run stats for pair " + pairId, e);
            }
        }

        /**
         * Persists attributes generated by post-processors.
         *
         * <p>Empty {@code starexec-result=} values are normalized to
         * {@code starexec-unknown} so downstream correctness logic treats
         * timeout/unknown outcomes consistently.</p>
         */
        private void persistAttributes(int execId, int pairId, int stageNumber) {
            Path outputDir = execIdToOutputDir.get(execId);
            if (outputDir == null) return;

            Path attrsPath = outputDir.resolve("attributes.txt");
            if (!Files.exists(attrsPath)) return;

            Properties props = new Properties();
            try (BufferedReader reader = Files.newBufferedReader(attrsPath)) {
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
                log.warn("Failed to parse attributes.txt for pair " + pairId, e);
                return;
            }

            if (!props.isEmpty()) {
                try {
                    JobPairs.addJobPairAttributes(pairId, stageNumber, props);
                    log.debug("Persisted attributes for pair " + pairId + ": " + props.size());
                } catch (Exception e) {
                    log.warn("Failed to persist attributes for pair " + pairId, e);
                }
            }
        }

        private String resolveStatsNodeName(ContainerJobMonitor.RunsolverStats stats) {
            if (stats.hostname != null && !stats.hostname.trim().isEmpty()) {
                return stats.hostname;
            }
            if (appNodeName != null && !appNodeName.trim().isEmpty()) {
                return appNodeName;
            }
            try {
                String[] workerNodes = KubernetesNativeBackend.this.getWorkerNodes();
                if (workerNodes != null && workerNodes.length > 0) {
                    return workerNodes[0];
                }
            } catch (Exception e) {
                log.debug("Could not resolve Kubernetes worker node for stats fallback", e);
            }
            return DEFAULT_WORKER_NODE_NAME;
        }

        /**
         * Parses the subset of stats.json fields needed for run-solver
         * statistics. Missing or malformed fields gracefully leave the
         * default zero values in place.
         */
        private void parseStatsJsonInto(
            String json,
            ContainerJobMonitor.RunsolverStats stats
        ) {
            JsonObject obj;
            try {
                obj = JsonParser.parseString(json).getAsJsonObject();
            } catch (Exception e) {
                log.warn("stats.json is not valid JSON; skipping stats parse", e);
                return;
            }

            try { if (obj.has("wallclockTime")) stats.wallclockTime = obj.get("wallclockTime").getAsDouble(); } catch (Exception e) { log.warn("stats.json: could not parse wallclockTime", e); }
            try { if (obj.has("cpuTime")) stats.cpuTime = obj.get("cpuTime").getAsDouble(); } catch (Exception e) { log.warn("stats.json: could not parse cpuTime", e); }
            try { if (obj.has("userTime")) stats.userTime = obj.get("userTime").getAsDouble(); } catch (Exception e) { log.warn("stats.json: could not parse userTime", e); }
            try { if (obj.has("systemTime")) stats.systemTime = obj.get("systemTime").getAsDouble(); } catch (Exception e) { log.warn("stats.json: could not parse systemTime", e); }
            try { if (obj.has("maxVirtualMemory")) stats.maxVirtualMemory = obj.get("maxVirtualMemory").getAsDouble(); } catch (Exception e) { log.warn("stats.json: could not parse maxVirtualMemory", e); }
            try { if (obj.has("maxResidentSetSize")) stats.maxResidentSetSize = obj.get("maxResidentSetSize").getAsLong(); } catch (Exception e) { log.warn("stats.json: could not parse maxResidentSetSize", e); }
            try { if (obj.has("diskSize")) stats.diskSize = obj.get("diskSize").getAsLong(); } catch (Exception e) { log.warn("stats.json: could not parse diskSize", e); }
            try { if (obj.has("hostname")) stats.hostname = obj.get("hostname").getAsString(); } catch (Exception e) { log.warn("stats.json: could not parse hostname", e); }
        }
    }
}
