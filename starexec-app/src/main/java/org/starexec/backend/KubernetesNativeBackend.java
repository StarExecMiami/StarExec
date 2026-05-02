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
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.starexec.config.EnvironmentConfig;
import org.starexec.constants.R;
import org.starexec.data.database.JobPairs;
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

    /** Label key for worker nodes */
    private static final String WORKER_LABEL = LABEL_PREFIX + "worker";

    /** Default queue name for nodes without queue label */
    private static final String DEFAULT_QUEUE_NAME = "default";

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

            jobMonitor = new KubernetesJobMonitor(
                kubernetesClient,
                namespace,
                new KubernetesJobCompletionCallback()
            );
            jobMonitor.start();

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
        strictOnePairPerCpu = getEnvBoolean(
            "STAREXEC_K8S_STRICT_ONE_PAIR_PER_CPU",
            true
        );
        workerNodeSelectorKey = getEnv("STAREXEC_K8S_WORKER_SELECTOR_KEY", WORKER_LABEL);
        workerNodeSelectorValue = getEnv("STAREXEC_K8S_WORKER_SELECTOR_VALUE", "true");

        // Academic reproducibility policy:
        // keep one job pair per CPU core to reduce L1/L2 cache interference.
        if (strictOnePairPerCpu && !"1".equals(cpuLimit)) {
            log.warn(
                "Overriding STAREXEC_K8S_CPU_LIMIT='" +
                cpuLimit +
                "' to '1' due to STAREXEC_K8S_STRICT_ONE_PAIR_PER_CPU=true"
            );
            cpuLimit = "1";
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

    private void ensureNamespaceAccessible() {
        if (kubernetesClient.namespaces().withName(namespace).get() == null) {
            throw new IllegalStateException(
                "Kubernetes namespace does not exist or is inaccessible: " + namespace
            );
        }
    }

    /**
     * Clean up resources when shutting down.
     */
    @Override
    public void destroyIf() {
        log.info("Shutting down KubernetesNativeBackend...");

        if (jobMonitor != null) {
            jobMonitor.stop();
        }

        if (kubernetesClient != null) {
            try {
                kubernetesClient.close();
            } catch (Exception e) {
                log.warn("Error while closing Kubernetes client", e);
            }
        }

        initialized = false;
        execIdToJobName.clear();
        execIdToPairId.clear();
        execIdToOutputDir.clear();
        log.info("KubernetesNativeBackend shut down");
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

            execIdToJobName.put(execId, jobName);
            execIdToPairId.put(execId, pairId);
            execIdToOutputDir.put(execId, resolveOutputDirectory(logPath));
            log.info("K8s Job submitted successfully: " + jobName);
            return execId;
        } catch (Exception e) {
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
        queues.add(DEFAULT_QUEUE_NAME);

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
        if (DEFAULT_QUEUE_NAME.equals(queueName)) {
            log.warn("Cannot delete default queue");
            return;
        }

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
        public boolean onJobComplete(int execId, String jobName) {
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

                boolean updated = JobPairs.setPairStatusPrecise(
                    pairId,
                    stageNumber,
                    terminalStatus,
                    StatusCode.STATUS_NOT_REACHED.getVal()
                );
                if (!updated) {
                    log.warn(
                        "Failed updating completed status for pair " +
                        pairId +
                        "; Kubernetes completion will be retried"
                    );
                    return false;
                }

                // Persist run-solver statistics (wallclock, cpu, memory, disk)
                // so K8s-native jobs produce the same data as container jobs.
                persistRunSolverStats(execId, pairId, stageNumber);
            } catch (Exception e) {
                log.error("Failed updating completed status for pair " + pairId, e);
                return false;
            }

            execIdToJobName.remove(execId);
            execIdToPairId.remove(execId);
            execIdToOutputDir.remove(execId);
            return true;
        }

        @Override
        public boolean onJobFailed(int execId, String jobName, String reason) {
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
            } catch (Exception e) {
                log.error("Failed updating failed status for pair " + pairId + ". Reason: " + reason, e);
                return false;
            }

            execIdToJobName.remove(execId);
            execIdToPairId.remove(execId);
            execIdToOutputDir.remove(execId);
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
                String nodeName = PodmanBackend.CONTAINER_WORKER_NODE;
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
