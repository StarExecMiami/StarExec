package org.starexec.backend;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventType;
import com.github.dockerjava.api.async.ResultCallbackTemplate;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.starexec.config.EnvironmentConfig;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.Queues;
import org.starexec.data.to.Status;
import org.starexec.logger.StarLogger;

/**
 * Backend implementation for Docker/Podman container execution.
 *
 * This backend uses the Docker-out-of-Docker (DooD) pattern to communicate
 * with the container engine on the host via a mounted socket. This allows
 * StarExec (running in a container) to create sibling containers for job
 * execution without requiring privileged mode.
 *
 * <h2>Architecture</h2>
 * <p>
 * This backend implements the {@link Backend} interface, focusing only on
 * job execution capabilities. Queue and node management methods inherited from
 * the legacy SGE-oriented interface are no-ops, as containerized execution
 * does not use traditional cluster concepts.
 * </p>
 *
 * <h2>Performance Optimization</h2>
 * <p>
 * By default, this backend uses <b>volume mounting</b> instead of per-job
 * image building. Job artifacts (solver, processors, benchmarks) are mounted
 * into a pre-warmed base container at runtime, reducing job startup latency
 * from seconds to milliseconds.
 * </p>
 *
 * <h2>Configuration</h2>
 * <p>
 * All settings are externalized via {@link EnvironmentConfig}:
 * </p>
 * <ul>
 * <li>{@code STAREXEC_CONTAINER_SOCKET} - Container engine socket path</li>
 * <li>{@code STAREXEC_CONTAINER_JOB_IMAGE} - Pre-built job runner image</li>
 * <li>{@code STAREXEC_CONTAINER_USE_PREBUILT} - Enable volume mounting
 * mode</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>
 * Jobs run as non-root user inside containers. The DooD pattern requires
 * mounting the host's container socket, which grants container management
 * access. Ensure proper access controls on the socket.
 * </p>
 *
 * @see Backend
 * @see EnvironmentConfig
 */
public class PodmanBackend implements Backend {

    private static final StarLogger log = StarLogger.getLogger(
        PodmanBackend.class
    );

    // Regex pattern for extracting UID from socket paths (compiled once at class load)
    private static final Pattern UID_PATTERN = Pattern.compile(
        "/run/user/(\\d+)/"
    );

    // Container engine client
    private DockerClient dockerClient;

    // Container name prefix for easy identification
    private static final String CONTAINER_PREFIX = "starexec-job-";

    // Label keys for container management
    private static final String LABEL_PAIR_ID = "starexec.pair.id";
    private static final String LABEL_MANAGED = "starexec.managed";

    // Configuration (loaded from EnvironmentConfig)
    private String containerSocketPath;
    private String baseImage;
    private String jobImage;
    private boolean usePrebuiltImage;
    private long defaultMemoryMb;
    private int defaultCpuLimit;
    private int defaultWallclockLimit;
    private int maxConcurrentJobs = 1;

    // Hard concurrency gate for container submissions.
    // This protects CPU cache locality by preventing unbounded sibling container fan-out.
    private final Object submissionSlotLock = new Object();
    private int activeSubmissionSlots = 0;
    private final Set<Integer> execIdsHoldingSubmissionSlot =
        ConcurrentHashMap.newKeySet();

    // DooD (Docker-outside-of-Docker) path translation
    // Maps container paths to host paths for volume mounts
    private String hostDataPath;
    private String containerDataPath;

    // Thread-safe mapping of execution IDs to container IDs
    private final Map<Integer, String> execIdToContainerId =
        new ConcurrentHashMap<>();

    // Atomic counter for execution IDs
    private int nextExecId = 1000;
    private final Object execIdLock = new Object();

    // Map container ID to pair ID for event tracking with 30-day TTL to prevent memory leaks
    private final Cache<String, Integer> containerIdToPairId = CacheBuilder.newBuilder()
        .maximumSize(10000)
        .expireAfterWrite(30, TimeUnit.DAYS)
        .build();

    // Executor for offloading database updates from the event listener I/O thread
    private final ExecutorService dbUpdateExecutor = new ThreadPoolExecutor(
        2, 2, // core and max pool size
        0L, TimeUnit.MILLISECONDS, // keep-alive time
        new ArrayBlockingQueue<>(10000), // STRICTLY BOUNDED QUEUE
        new java.util.concurrent.ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "PodmanDbUpdater-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        },
        // Never silently discard node/status updates; run in caller thread when saturated.
        new ThreadPoolExecutor.CallerRunsPolicy()
    );

    // Monitor for processing completed container jobs
    private ContainerJobMonitor jobMonitor;

    // Cached node ID for database updates to avoid N+1 queries
    private int cachedNodeId = -1;

    /**
     * Initializes the Docker/Podman client.
     *
     * <p>
     * Configuration is loaded from environment variables via
     * {@link EnvironmentConfig}:
     * </p>
     * <ul>
     * <li>{@code STAREXEC_CONTAINER_SOCKET} - Socket path (default:
     * unix:///var/run/docker.sock)</li>
     * <li>{@code STAREXEC_CONTAINER_JOB_IMAGE} - Pre-built job image</li>
     * <li>{@code STAREXEC_CONTAINER_USE_PREBUILT} - Use volume mounting (default:
     * true)</li>
     * </ul>
     *
     * @param backendRoot Not used for container backend, but required by interface
     */
    @Override
    public void initialize(String backendRoot) {
        try {
            log.info("Initializing PodmanBackend...");

            // Load configuration from environment
            loadConfiguration();

            log.info("Container socket: " + containerSocketPath);
            log.info("Use prebuilt image: " + usePrebuiltImage);
            log.info(
                "Job image: " +
                    (usePrebuiltImage ? jobImage : baseImage + " (JIT build)")
            );

            // Configure Docker client with externalized socket path
            DockerClientConfig config =
                DefaultDockerClientConfig.createDefaultConfigBuilder()
                    .withDockerHost(containerSocketPath)
                    .build();

            // Create HTTP client with zerodep implementation (pure Java, no external dependencies)
            // This avoids intermittent "Broken pipe" errors seen with httpclient5
            ZerodepDockerHttpClient httpClient =
                new ZerodepDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost())
                    .maxConnections(
                        EnvironmentConfig.getContainerMaxConnections()
                    )
                    .connectionTimeout(
                        Duration.ofSeconds(
                            EnvironmentConfig.getContainerConnectionTimeout()
                        )
                    )
                    .responseTimeout(
                        Duration.ofSeconds(
                            EnvironmentConfig.getContainerResponseTimeout()
                        )
                    )
                    .build();

            this.dockerClient = DockerClientImpl.getInstance(
                config,
                httpClient
            );

            // Test connection
            String engineVersion = dockerClient
                .versionCmd()
                .exec()
                .getVersion();
            log.info(
                "PodmanBackend initialized successfully. Engine version: " +
                    engineVersion
            );

            // Check for job runner image if using prebuilt mode
            if (usePrebuiltImage) {
                if (!ensureImageExists(jobImage)) {
                    log.warn(
                        "Job runner image not available. Jobs will fail until image is built."
                    );
                    log.warn("To build locally: make build-job-runner");
                }
            }

            // Ensure a virtual queue exists for container jobs
            ensureContainerQueueExists();

            // Cache the node ID to avoid database queries during job submission.
            // In some startup orders, this node may not yet exist; event handler will retry lazily.
            cachedNodeId = resolveContainerWorkerNodeId();

            // Start the job completion monitor
            this.jobMonitor = new ContainerJobMonitor(this);
            this.jobMonitor.start();
            log.info("ContainerJobMonitor started");

            // Subscribe to container events
            startContainerEventListener();
        } catch (Exception e) {
            // Wrap and throw up stack; logging/handling deferred to caller.
            // Avoid double-logging by NOT logging here + throwing with same message.
            String errorMessage = 
                "Could not connect to the container engine at: " + containerSocketPath + "\n" +
                "Please verify:\n" +
                "  1. Socket service is running: systemctl --user status podman.socket\n" +
                "  2. Your UID matches the socket path: id -u (current) vs " + 
                extractUidFromPath(containerSocketPath) + " (in path)\n" +
                "  3. For school networks with masked sockets, see: docs/TROUBLESHOOTING_PODMAN.md\n" +
                "\nAlternatively, run 'make preflight-podman' for more diagnostics.";
            throw new IllegalStateException(errorMessage, e);
        }
    }

    /**
     * Resolves the virtual Podman worker node ID used for pair host attribution.
     *
     * <p>If the ID cannot be found yet (startup ordering), this returns -1 and
     * callers should retry later.</p>
     */
    private int resolveContainerWorkerNodeId() {
        if (cachedNodeId > 0) {
            return cachedNodeId;
        }

        try {
            int resolved = org.starexec.data.database.Cluster.getNodeIdByName(
                CONTAINER_WORKER_NODE
            );
            if (resolved > 0) {
                cachedNodeId = resolved;
            } else {
                log.warn(
                    "Could not find nodeId for " +
                    CONTAINER_WORKER_NODE +
                    ". Pair host mapping will retry on next container start event."
                );
            }
            return resolved;
        } catch (Exception e) {
            log.error(
                "Error resolving nodeId for " + CONTAINER_WORKER_NODE,
                e
            );
            return -1;
        }
    }

    /**
     * Resolves the container worker node ID with short retries to tolerate
     * startup ordering where the virtual node has not been persisted yet.
     */
    private int resolveContainerWorkerNodeIdWithRetry(
        int maxAttempts,
        long delayMillis
    ) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            int nodeId = resolveContainerWorkerNodeId();
            if (nodeId > 0) {
                return nodeId;
            }

            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
        }
        return -1;
    }

    /**
     * Container queue name used for all container-based job execution.
     * This is a virtual queue that exists only in the database to satisfy
     * the job submission form requirements.
     */
    public static final String CONTAINER_QUEUE_NAME = "container.q";

    /**
     * Virtual worker node name for container-based job execution.
     * This node doesn't represent a physical machine but satisfies the
     * JobManager's requirement for at least one node associated with a queue.
     */
    public static final String CONTAINER_WORKER_NODE = "container-worker-1";

    private void startContainerEventListener() {
        try {
            dockerClient.eventsCmd()
                .withEventTypeFilter("container")
                .withEventFilter("start", "die")
                .exec(new ResultCallbackTemplate<ResultCallbackTemplate<?, Event>, Event>() {
                    @Override
                    public void onNext(Event event) {
                        try {
                            String action = event.getAction();
                            String containerId = event.getId();

                            if (containerId == null || action == null) return;

                            // Only care about containers we track
                            Integer pairId = containerIdToPairId.getIfPresent(containerId);
                            if (pairId == null) return;

                            log.debug("Container event received: action=" + action + " pairId=" + pairId);

                            if ("start".equals(action)) {
                                if (pairId > 0) {
                                    dbUpdateExecutor.submit(() -> {
                                        try {
                                            int nodeId = resolveContainerWorkerNodeIdWithRetry(5, 1000);
                                            if (nodeId > 0) {
                                                boolean hostUpdated = JobPairs.updatePairExecutionHost(
                                                    pairId,
                                                    nodeId
                                                );
                                                if (!hostUpdated) {
                                                    log.warn(
                                                        "Failed to update pair execution host for pair " +
                                                        pairId +
                                                        " on node " +
                                                        nodeId
                                                    );
                                                }
                                            } else {
                                                log.warn(
                                                    "Could not resolve worker node ID after retries for pair " +
                                                    pairId
                                                );
                                            }

                                            // Guard: only transition to STATUS_RUNNING if the pair
                                            // has not yet finished running. A fast-completing
                                            // container can cause the 'start' event to be delivered
                                            // after ContainerJobMonitor has already written a
                                            // terminal status code. UpdatePairStatus is
                                            // unconditional — without this guard it would overwrite
                                            // the terminal code, leaving the pair stuck in
                                            // STATUS_RUNNING indefinitely and the job never
                                            // completing.
                                            int currentStatusCode = JobPairs.getPairStatusCode(pairId);
                                            if (!Status.StatusCode.toStatusCode(currentStatusCode)
                                                                   .finishedRunning()) {
                                                // Mark pair as running so node-level cluster views
                                                // can show in-flight execution.
                                                boolean runningUpdated = JobPairs.setPairStatus(
                                                    pairId,
                                                    Status.StatusCode.STATUS_RUNNING.getVal()
                                                );
                                                if (!runningUpdated) {
                                                    log.warn(
                                                        "Failed to set running status for pair " + pairId
                                                    );
                                                }
                                            } else {
                                                log.debug(
                                                    "Skipping STATUS_RUNNING for pair " + pairId +
                                                    " — current status code " + currentStatusCode +
                                                    " indicates execution has already finished"
                                                );
                                            }
                                        } catch (Exception ex) {
                                            log.error(
                                                "Failed to process start event updates for pair " +
                                                pairId,
                                                ex
                                            );
                                        }
                                    });
                                }
                            } else if ("die".equals(action)) {
                                // Container is dead; completion monitor will pick it up
                                // We keep it in the map until cleanup
                            }
                        } catch (Exception ex) {
                            log.error("Error processing container event", ex);
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        log.error("Container event listener error", throwable);
                        // Re-subscribe on error after delay
                        new Thread(() -> {
                            try {
                                Thread.sleep(5000);
                                startContainerEventListener();
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }).start();
                    }
                });
            log.info("Subscribed to container event stream");
        } catch (Exception e) {
            log.error("Failed to start container event listener", e);
        }
    }

    /**
     * Ensures a virtual queue exists for container job submission.
     * <p>
     * The StarExec job submission UI requires a queue selection, but container
     * backends don't use traditional SGE queues. This method creates a virtual
     * "container.q" queue with generous timeout limits that serves as the
     * target for all container-based jobs.
     * </p>
     */
    private void ensureContainerQueueExists() {
        try {
            // Check if container queue already exists
            if (Queues.getIdByName(CONTAINER_QUEUE_NAME) > 0) {
                log.info(
                    "Container queue '" +
                        CONTAINER_QUEUE_NAME +
                        "' already exists"
                );
                return;
            }

            // Create virtual queue with generous limits
            // CPU and wallclock limits are enforced by the container, not the queue
            int cpuTimeout = 86400; // 24 hours max
            int wallTimeout = 86400; // 24 hours max

            int queueId = Queues.add(
                CONTAINER_QUEUE_NAME,
                cpuTimeout,
                wallTimeout
            );
            if (queueId > 0) {
                // Make queue globally accessible
                Queues.makeGlobal(queueId);
                Queues.setStatus(CONTAINER_QUEUE_NAME, "ACTIVE");
                log.info(
                    "Created container queue '" +
                        CONTAINER_QUEUE_NAME +
                        "' with ID: " +
                        queueId
                );
            } else {
                log.warn(
                    "Failed to create container queue - job submission may fail"
                );
            }
        } catch (Exception e) {
            log.warn("Error creating container queue: " + e.getMessage());
            // Don't fail initialization - the queue might be created by another process
        }
    }

    /**
     * Loads configuration from EnvironmentConfig.
     */
    private void loadConfiguration() {
        containerSocketPath = EnvironmentConfig.getContainerSocketPath();
        baseImage = EnvironmentConfig.getContainerBaseImage();
        jobImage = EnvironmentConfig.getContainerJobImage();
        usePrebuiltImage = EnvironmentConfig.getContainerUsePrebuiltImage();
        defaultMemoryMb = EnvironmentConfig.getContainerDefaultMemoryMb();
        defaultCpuLimit = EnvironmentConfig.getContainerDefaultCpuLimit();
        defaultWallclockLimit =
            EnvironmentConfig.getContainerDefaultWallclockLimit();
        maxConcurrentJobs = EnvironmentConfig.getContainerMaxConcurrentJobs();

        if (maxConcurrentJobs < 1) {
            log.warn(
                "Invalid STAREXEC_CONTAINER_MAX_CONCURRENT_JOBS value: " +
                maxConcurrentJobs +
                ". Falling back to 1."
            );
            maxConcurrentJobs = 1;
        }

        // DooD path translation configuration
        hostDataPath = EnvironmentConfig.getContainerHostDataPath();
        containerDataPath = EnvironmentConfig.getContainerDataPath();

        if (!hostDataPath.isEmpty()) {
            log.info(
                "DooD path translation enabled: " +
                    containerDataPath +
                    " -> " +
                    hostDataPath
            );
        } else {
            log.info(
                "DooD path translation disabled (host and container paths are same)"
            );
        }

        log.info("Container max concurrent jobs: " + maxConcurrentJobs);
    }

    /**
     * Ensures the specified image exists locally, pulling if necessary.
     * If the image cannot be pulled (e.g., network issues, private registry),
     * logs a warning but does not fail initialization.
     *
     * @param imageName The image name to check/pull
     * @return true if image exists or was pulled, false otherwise
     */
    private boolean ensureImageExists(String imageName) {
        try {
            dockerClient.inspectImageCmd(imageName).exec();
            log.info("Image already exists locally: " + imageName);
            return true;
        } catch (NotFoundException e) {
            log.info(
                "Image not found locally, attempting to pull: " + imageName
            );
            try {
                dockerClient.pullImageCmd(imageName).start().awaitCompletion();
                log.info("Image pulled successfully: " + imageName);
                return true;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Image pull interrupted: " + imageName);
                return false;
            } catch (Exception pullEx) {
                log.warn(
                    "Could not pull image '" +
                        imageName +
                        "': " +
                        pullEx.getMessage()
                );
                log.warn(
                    "Container jobs will fail until image is available. " +
                        "Build locally with: make build-job-runner"
                );
                return false;
            }
        }
    }

    /**
     * Acquires one container submission slot, blocking if the backend is at capacity.
     */
    private void acquireSubmissionSlot(int execId) throws InterruptedException {
        synchronized (submissionSlotLock) {
            while (activeSubmissionSlots >= maxConcurrentJobs) {
                log.debug(
                    "Podman submission waiting for available slot (execId=" +
                    execId +
                    ", active=" +
                    activeSubmissionSlots +
                    ", max=" +
                    maxConcurrentJobs +
                    ")"
                );
                submissionSlotLock.wait();
            }

            activeSubmissionSlots++;
            execIdsHoldingSubmissionSlot.add(execId);
            log.debug(
                "Acquired Podman submission slot (execId=" +
                execId +
                ", active=" +
                activeSubmissionSlots +
                ", max=" +
                maxConcurrentJobs +
                ")"
            );
        }
    }

    /**
     * Releases one container submission slot for the given execution id.
     */
    private void releaseSubmissionSlot(int execId, String reason) {
        synchronized (submissionSlotLock) {
            if (!execIdsHoldingSubmissionSlot.remove(execId)) {
                return;
            }

            if (activeSubmissionSlots > 0) {
                activeSubmissionSlots--;
            } else {
                log.warn(
                    "Submission slot underflow prevented while releasing execId=" +
                    execId +
                    " (reason=" +
                    reason +
                    ")"
                );
            }

            submissionSlotLock.notifyAll();

            log.debug(
                "Released Podman submission slot (execId=" +
                execId +
                ", reason=" +
                reason +
                ", active=" +
                activeSubmissionSlots +
                ", max=" +
                maxConcurrentJobs +
                ")"
            );
        }
    }

    /**
     * Removes tracked execution mapping by container id and returns the associated exec id.
     */
    private Integer removeTrackedExecutionByContainerId(String containerId) {
        if (containerId == null) {
            return null;
        }

        for (Map.Entry<Integer, String> entry : execIdToContainerId.entrySet()) {
            if (containerId.equals(entry.getValue())) {
                Integer execId = entry.getKey();
                if (execIdToContainerId.remove(execId, containerId)) {
                    return execId;
                }
            }
        }

        return null;
    }

    /**
     * Starts a container and verifies liveness when start call fails ambiguously.
     *
     * <p>
     * Some container engines may fail the client-side start request even after
     * the daemon has already started the container. In that case we must detect
     * that the container is running and treat the submission as successful to
     * avoid duplicate retries.
     * </p>
     */
    private void startContainerWithVerification(String containerId)
        throws Exception {
        try {
            dockerClient.startContainerCmd(containerId).exec();
            log.info("Container started: " + containerId);
            return;
        } catch (Exception startException) {
            log.warn(
                "Container start command failed for " +
                containerId +
                ". Verifying runtime state before retry.",
                startException
            );

            try {
                var inspection = dockerClient
                    .inspectContainerCmd(containerId)
                    .exec();
                var state = inspection.getState();
                boolean isRunning =
                    state != null && Boolean.TRUE.equals(state.getRunning());
                if (isRunning) {
                    log.warn(
                        "Container " +
                        containerId +
                        " is already running despite start exception; " +
                        "treating submission as successful."
                    );
                    return;
                }
            } catch (NotFoundException notFound) {
                log.warn(
                    "Container " +
                    containerId +
                    " not found during post-start verification."
                );
            } catch (Exception inspectException) {
                log.warn(
                    "Failed to inspect container " +
                    containerId +
                    " after start exception.",
                    inspectException
                );
            }

            // Best-effort cleanup before propagating failure for retry.
            try {
                dockerClient
                    .removeContainerCmd(containerId)
                    .withForce(true)
                    .exec();
                log.info(
                    "Removed container after unsuccessful start verification: " +
                    containerId
                );
            } catch (NotFoundException ignored) {
                // Already gone.
            } catch (Exception cleanupException) {
                log.warn(
                    "Failed to remove container after start failure: " +
                    containerId,
                    cleanupException
                );
            }

            throw startException;
        }
    }

    /**
     * Notifies the completion monitor that a new container was submitted.
     *
     * <p>
     * Notification failures are non-fatal and must not cause submission retries
     * after a container has already started.
     * </p>
     */
    private void notifyMonitorNewWorkSafely() {
        if (jobMonitor == null) {
            return;
        }

        try {
            jobMonitor.notifyNewWorkSubmitted();
        } catch (Exception e) {
            log.warn(
                "Container started successfully but failed to notify job monitor.",
                e
            );
        }
    }

    /**
     * Releases resources and cleans up.
     */
    @Override
    public void destroyIf() {
        // Stop the db update executor
        if (dbUpdateExecutor != null) {
            dbUpdateExecutor.shutdown();
            try {
                if (!dbUpdateExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Forcing shutdown of dbUpdateExecutor");
                    dbUpdateExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.error("Shutdown of dbUpdateExecutor interrupted", e);
                dbUpdateExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Stop the job monitor first
        if (jobMonitor != null) {
            try {
                jobMonitor.stop();
                log.info("ContainerJobMonitor stopped");
            } catch (Exception e) {
                log.warn("Error stopping ContainerJobMonitor", e);
            }
        }

        if (dockerClient != null) {
            try {
                // Clean up any orphaned StarExec containers
                cleanupOrphanedContainers();

                dockerClient.close();
                dockerClient = null;
                log.info("PodmanBackend destroyed successfully.");
            } catch (Exception e) {
                log.warn("Error during PodmanBackend cleanup", e);
            }
        }
    }

    @Override
    public boolean isError(int execCode) {
        return execCode < 0;
    }

    /**
     * Submits a job script for execution in a container.
     *
     * <p>
     * This method supports two execution modes:
     * </p>
     * <ol>
     * <li><b>Volume Mounting (default, recommended)</b>: Uses a pre-built base
     * image
     * and mounts job artifacts as volumes. This reduces job startup time from
     * seconds to milliseconds.</li>
     * <li><b>JIT Building (legacy)</b>: Builds a container image per job. Use this
     * when the job requires custom dependencies not in the base image.</li>
     * </ol>
     *
     * <p>
     * Mode is controlled by {@code STAREXEC_CONTAINER_USE_PREBUILT} environment
     * variable.
     * </p>
     *
     * @param scriptPath       Path to the entry script (relative to
     *                         workingDirectory)
     * @param workingDirectory Directory containing all job artifacts
     * @param logPath          Directory where output should be written
     * @return Execution ID for tracking, or negative value on error
     */
    @Override
    public int submitScript(
        int pairId,
        String scriptPath,
        String workingDirectory,
        String logPath
    ) {
        int execIdForSlot = -1;
        boolean slotAcquired = false;
        boolean submissionAccepted = false;

        synchronized (execIdLock) {
            execIdForSlot = nextExecId++;
        }

        try {
            acquireSubmissionSlot(execIdForSlot);
            slotAcquired = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error(
                "Interrupted while waiting for an available Podman submission slot"
            );
            return -1;
        }

        final long timestamp = System.currentTimeMillis();
        final String baseJobName = CONTAINER_PREFIX + timestamp;

        // Retry logic for handling intermittent "Broken pipe" errors with Podman
        final int maxRetries = 3;
        final long baseRetryDelay = 500; // milliseconds

        try {
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                // Use unique name for each retry attempt to avoid conflicts
                final String jobName = (attempt == 1)
                    ? baseJobName
                    : baseJobName + "-retry" + attempt;

                try {
                    if (attempt > 1) {
                        log.info(
                            "Retry attempt " +
                                attempt +
                                " of " +
                                maxRetries +
                                " for job: " +
                                jobName
                        );
                    }

                    int execId = doSubmitScript(
                        pairId,
                        jobName,
                        scriptPath,
                        workingDirectory,
                        logPath,
                        timestamp,
                        attempt,
                        execIdForSlot
                    );
                    submissionAccepted = true;
                    return execId;
                } catch (Exception e) {
                    boolean isRetryable =
                        e.getMessage() != null &&
                        (e.getMessage().contains("Broken pipe") ||
                            e.getMessage().contains("Connection reset"));

                    if (isRetryable && attempt < maxRetries) {
                        long delay =
                            baseRetryDelay * (long) Math.pow(2, attempt - 1);
                        log.warn(
                            "Transient error on attempt " +
                                attempt +
                                " (will retry in " +
                                delay +
                                "ms): " +
                                e.getMessage()
                        );
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            log.error("Interrupted during retry delay");
                            return -1;
                        }
                    } else {
                        log.error(
                            "Error submitting job: " +
                                jobName +
                                " (attempt " +
                                attempt +
                                " of " +
                                maxRetries +
                                ")"
                        );
                        log.error("Exception type: " + e.getClass().getName());
                        log.error("Message: " + e.getMessage());
                        if (e.getCause() != null) {
                            log.error(
                                "Cause: " +
                                    e.getCause().getClass().getName() +
                                    " - " +
                                    e.getCause().getMessage()
                            );
                        }
                        return -1;
                    }
                }
            }

            return -1; // Should not reach here
        } finally {
            if (slotAcquired && !submissionAccepted) {
                releaseSubmissionSlot(execIdForSlot, "submission rejected");
            }
        }
    }

    /**
     * Internal method to actually submit the job script.
     */
    private int doSubmitScript(
        int pairId,
        String jobName,
        String scriptPath,
        String workingDirectory,
        String logPath,
        long timestamp,
        int attempt,
        int execId
    ) throws Exception {
        log.info("Submitting job: " + jobName);
        log.debug("Working directory: " + workingDirectory);
        log.debug("Script path: " + scriptPath);
        log.debug("Log path: " + logPath);

        // Ensure log directory exists (use the directory part of logPath, as it may be a file path)
        Path logDir = Paths.get(logPath).getParent();
        if (logDir != null) {
            Files.createDirectories(logDir);
        }

        String imageName;
        if (usePrebuiltImage) {
            // Use pre-built image with volume mounting (fast path)
            imageName = jobImage;
            log.debug(
                "Using prebuilt image with volume mounting: " + imageName
            );
        } else {
            // Build image per job (legacy JIT path)
            imageName = "starexec/job:" + timestamp;
            buildJobImage(workingDirectory, scriptPath, imageName);
        }

        // Configure container with volume mounts
        HostConfig hostConfig = createHostConfig(workingDirectory, logPath);

        // Get output directory for container communication (status.json, etc.)
        String outputDir = logDir != null ? logDir.toString() : "/tmp/output";

        List<String> envVars = createEnvironmentVariables(
            timestamp,
            workingDirectory,
            outputDir
        );
        Map<String, String> labels = createContainerLabels(timestamp);

        // Log container creation parameters for debugging
        log.info("Creating container with:");
        log.info("  Image: " + imageName);
        log.info("  Name: " + jobName);
        log.info("  Working Dir: " + workingDirectory);
        log.info("  Script: " + scriptPath);
        log.info(
            "  Binds: " +
                (hostConfig.getBinds() != null
                    ? java.util.Arrays.toString(hostConfig.getBinds())
                    : "none")
        );
        log.info("  Memory: " + hostConfig.getMemory());
        log.info("  EnvVars count: " + envVars.size());
        log.info("  Entrypoint: [/bin/bash]");
        log.info("  Cmd: [" + scriptPath + "]");

        // Try Java client first, fall back to curl if it fails with "Broken pipe"
        String containerId = null;
        try {
            // Create container that runs the job script directly
            // Override entrypoint to bypass the default image entrypoint
            // Use exec form (array) for proper entrypoint handling
            // Set hostname to match the worker node name in the database
            CreateContainerResponse container = dockerClient
                .createContainerCmd(imageName)
                .withName(jobName)
                .withHostName(CONTAINER_WORKER_NODE)
                .withHostConfig(hostConfig)
                .withEnv(envVars)
                .withLabels(labels)
                .withEntrypoint(new String[] { "/bin/bash" }) // Override image entrypoint as array
                .withCmd(scriptPath) // Pass script as argument to bash
                .withWorkingDir(workingDirectory)
                .exec();
            containerId = container.getId();
            log.info("Container created via Java client: " + containerId);
        } catch (Exception e) {
            // Check for transient errors in exception chain (message or cause)
            boolean isTransient = isTransientConnectionError(e);
            log.warn(
                "Container creation failed. Transient error: " +
                    isTransient +
                    ", Exception: " +
                    e.getClass().getName()
            );

            if (isTransient) {
                log.warn(
                    "Java client failed with transient error, falling back to curl..."
                );
                containerId = createContainerWithCurl(
                    jobName,
                    imageName,
                    hostConfig,
                    envVars,
                    labels,
                    scriptPath,
                    workingDirectory
                );
                log.info("Container created via curl: " + containerId);
            } else {
                throw e;
            }
        }

        // Put pair ID in tracking map BEFORE starting the container to avoid race condition
        if (pairId > 0 && containerId != null) {
            containerIdToPairId.put(containerId, pairId);
        }

        // Start container (with post-failure verification to avoid duplicate retries)
        startContainerWithVerification(containerId);

        // Track execution only after successful start
        execIdToContainerId.put(execId, containerId);

        // Notify the job monitor that new work has been submitted.
        // Failure here is non-fatal and must not trigger submission retries.
        notifyMonitorNewWorkSafely();

        log.info(
            "Job submitted successfully. ExecId: " +
                execId +
                ", ContainerId: " +
                containerId
        );
        return execId;
    }

    /**
     * Builds a container image for the job (legacy JIT mode).
     *
     * <p>
     * This method is only called when {@code usePrebuiltImage} is false.
     * For production workloads, prefer volume mounting with a prebuilt image.
     * </p>
     */
    private void buildJobImage(
        String workingDirectory,
        String scriptPath,
        String imageName
    ) throws IOException {
        // Create Dockerfile dynamically
        String dockerfileContent = generateDockerfile(scriptPath);
        Path dockerfilePath = Paths.get(workingDirectory, "Dockerfile");
        Files.writeString(dockerfilePath, dockerfileContent);
        log.debug("Dockerfile created at: " + dockerfilePath);

        // Build the container image
        log.info("Building container image (JIT mode): " + imageName);
        String imageId = dockerClient
            .buildImageCmd()
            .withDockerfile(dockerfilePath.toFile())
            .withBaseDirectory(new File(workingDirectory))
            .withTags(Collections.singleton(imageName))
            .withNoCache(false)
            .withPull(false)
            .start()
            .awaitImageId();

        log.info("Image built successfully: " + imageId);
    }

    /**
     * Generates a Dockerfile for the job container (JIT mode only).
     *
     * <p>
     * This Dockerfile is only used when
     * {@code STAREXEC_CONTAINER_USE_PREBUILT=false}.
     * For production, use a pre-built image with volume mounting instead.
     * </p>
     *
     * @deprecated JIT image building is deprecated. Use the pre-built Alpine image
     *             from GHCR instead: ghcr.io/starexecmiami/starexec-job-runner:latest
     */
    @Deprecated
    private String generateDockerfile(String scriptPath) {
        // Detect if base image is Alpine or Ubuntu-based to use correct commands
        boolean isAlpine = baseImage.contains("alpine");

        String installDeps = isAlpine
            ? "RUN apk add --no-cache bash coreutils gcompat libstdc++ libgcc"
            : "RUN apt-get update && apt-get install -y --no-install-recommends bash coreutils && rm -rf /var/lib/apt/lists/*";

        String createUser = isAlpine
            ? "RUN adduser -D -s /bin/bash starexec_user"
            : "RUN useradd --create-home --shell /bin/bash starexec_user";

        return String.join(
            "\n",
            "FROM " + baseImage,
            "",
            "# Install minimal runtime dependencies",
            installDeps,
            "",
            "# Create non-root user for security",
            createUser,
            "",
            "# Set up working directory",
            "WORKDIR /starexec",
            "",
            "# Copy all job artifacts",
            "COPY --chown=starexec_user:starexec_user . /starexec/",
            "",
            "# Make scripts executable",
            "RUN find /starexec -type f -name '*.sh' -exec chmod +x {} \\; || true",
            "RUN find /starexec -type f -name 'starexec_run' -exec chmod +x {} \\; || true",
            "RUN chmod +x /starexec/entrypoint.sh 2>/dev/null || true",
            "",
            "# Switch to non-root user",
            "USER starexec_user",
            "",
            "# Set entrypoint",
            "ENTRYPOINT [\"/bin/bash\", \"/starexec/entrypoint.sh\"]"
        );
    }

    /**
     * Translates a container path to a host path for DooD scenarios.
     *
     * <p>
     * When running StarExec inside a container with a mounted container socket (DooD pattern),
     * bind mounts for job containers must reference paths on the HOST filesystem, not paths
     * inside the StarExec container. This method translates container paths to host paths.
     * </p>
     *
     * @param containerPath Path inside the StarExec container (e.g., /app/data/jobin/job_1)
     * @return Corresponding host path, or the original path if translation is disabled
     */
    private String translateToHostPath(String containerPath) {
        if (
            hostDataPath == null ||
            hostDataPath.isEmpty() ||
            containerDataPath == null
        ) {
            return containerPath;
        }

        if (containerPath.startsWith(containerDataPath)) {
            String relativePath = containerPath.substring(
                containerDataPath.length()
            );
            String hostPath = hostDataPath + relativePath;
            log.debug(
                "DooD path translation: " + containerPath + " -> " + hostPath
            );
            return hostPath;
        }

        log.warn(
            "Path not under data directory, cannot translate: " + containerPath
        );
        return containerPath;
    }

    /**
     * Creates a container using curl as a fallback when the Java client fails.
     *
     * <p>
     * This is a workaround for intermittent "Broken pipe" errors from the docker-java client
     * when communicating with Podman's Docker-compatible API. Curl doesn't have this issue.
     * </p>
     *
     * @return The container ID
     */
    private String createContainerWithCurl(
        String name,
        String image,
        HostConfig hostConfig,
        List<String> env,
        Map<String, String> labels,
        String scriptPath,
        String workDir
    ) throws Exception {
        // Build JSON payload for container creation
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"Image\":\"").append(escapeJson(image)).append("\",");
        json
            .append("\"Hostname\":\"")
            .append(escapeJson(CONTAINER_WORKER_NODE))
            .append("\",");
        json.append("\"Entrypoint\":[\"/bin/bash\"],");
        json
            .append("\"Cmd\":[\"")
            .append(escapeJson(scriptPath))
            .append("\"],");
        json
            .append("\"WorkingDir\":\"")
            .append(escapeJson(workDir))
            .append("\",");

        // Environment variables
        json.append("\"Env\":[");
        for (int i = 0; i < env.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(escapeJson(env.get(i))).append("\"");
        }
        json.append("],");

        // Labels
        json.append("\"Labels\":{");
        int labelIdx = 0;
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            if (labelIdx > 0) json.append(",");
            json.append("\"").append(escapeJson(entry.getKey())).append("\":");
            json.append("\"").append(escapeJson(entry.getValue())).append("\"");
            labelIdx++;
        }
        json.append("},");

        // HostConfig
        json.append("\"HostConfig\":{");

        // Binds
        Bind[] binds = hostConfig.getBinds();
        if (binds != null && binds.length > 0) {
            json.append("\"Binds\":[");
            for (int i = 0; i < binds.length; i++) {
                if (i > 0) json.append(",");
                String bindStr =
                    binds[i].getPath() + ":" + binds[i].getVolume().getPath();
                // Check if read-only via access mode
                if (
                    binds[i].getAccessMode() ==
                    com.github.dockerjava.api.model.AccessMode.ro
                ) {
                    bindStr += ":ro";
                }
                json.append("\"").append(escapeJson(bindStr)).append("\"");
            }
            json.append("],");
        }

        // Network mode
        if (hostConfig.getNetworkMode() != null) {
            json
                .append("\"NetworkMode\":\"")
                .append(escapeJson(hostConfig.getNetworkMode()))
                .append("\",");
        }

        // Memory limits
        if (hostConfig.getMemory() != null) {
            json
                .append("\"Memory\":")
                .append(hostConfig.getMemory())
                .append(",");
        }
        if (hostConfig.getMemorySwap() != null) {
            json
                .append("\"MemorySwap\":")
                .append(hostConfig.getMemorySwap())
                .append(",");
        }

        // CPU limits
        if (hostConfig.getCpuPeriod() != null) {
            json
                .append("\"CpuPeriod\":")
                .append(hostConfig.getCpuPeriod())
                .append(",");
        }
        if (hostConfig.getCpuQuota() != null) {
            json
                .append("\"CpuQuota\":")
                .append(hostConfig.getCpuQuota())
                .append(",");
        }

        // Remove trailing comma and close HostConfig
        String jsonStr = json.toString();
        if (jsonStr.endsWith(",")) {
            jsonStr = jsonStr.substring(0, jsonStr.length() - 1);
        }
        jsonStr += "}}";

        log.debug("Container creation JSON: " + jsonStr);

        // Get socket path (strip unix:// prefix if present)
        String socketPath = containerSocketPath;
        if (socketPath.startsWith("unix://")) {
            socketPath = socketPath.substring(7);
        }

        // Execute curl command
        ProcessBuilder pb = new ProcessBuilder(
            "curl",
            "-s",
            "-X",
            "POST",
            "--unix-socket",
            socketPath,
            "-H",
            "Content-Type: application/json",
            "-d",
            jsonStr,
            "http://localhost/v1.41/containers/create?name=" + name
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output;
        try (
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream())
            )
        ) {
            output = reader
                .lines()
                .collect(java.util.stream.Collectors.joining("\n"));
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new Exception(
                "curl failed with exit code " + exitCode + ": " + output
            );
        }

        log.debug("curl response: " + output);

        // Parse container ID from JSON response: {"Id":"...", "Warnings":[...]}
        String containerId = null;
        if (output.contains("\"Id\":")) {
            int start = output.indexOf("\"Id\":\"") + 6;
            int end = output.indexOf("\"", start);
            if (start > 5 && end > start) {
                containerId = output.substring(start, end);
            }
        }

        if (containerId == null || containerId.isEmpty()) {
            throw new Exception(
                "Failed to parse container ID from response: " + output
            );
        }

        return containerId;
    }

    /**
     * Escapes a string for use in JSON.
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    /**
     * Checks if an exception represents a transient connection error that should trigger
     * fallback to curl-based container creation.
     *
     * <p>
     * This method recursively checks the exception chain (cause) for known transient errors
     * like "Broken pipe" or "Connection reset" that occur intermittently with the docker-java
     * client when communicating with Podman's API.
     * </p>
     *
     * @param e The exception to check
     * @return true if this is a transient connection error
     */
    private boolean isTransientConnectionError(Throwable e) {
        if (e == null) {
            return false;
        }

        String message = e.getMessage();
        if (message != null) {
            String lowerMessage = message.toLowerCase();
            if (
                lowerMessage.contains("broken pipe") ||
                lowerMessage.contains("connection reset") ||
                lowerMessage.contains("connection refused") ||
                lowerMessage.contains("socket closed")
            ) {
                return true;
            }
        }

        // Check for IOException types that indicate connection issues
        if (e instanceof java.io.IOException) {
            String className = e.getClass().getSimpleName().toLowerCase();
            if (
                className.contains("brokenpipe") || className.contains("socket")
            ) {
                return true;
            }
        }

        // Recursively check cause
        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            return isTransientConnectionError(cause);
        }

        // Also check suppressed exceptions
        for (Throwable suppressed : e.getSuppressed()) {
            if (isTransientConnectionError(suppressed)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Creates host configuration with resource limits and volume mounts.
     *
     * <p>
     * When using prebuilt images (default), job artifacts are mounted as volumes
     * rather than being baked into the image. This significantly improves startup
     * time.
     * </p>
     *
     * <p>
     * In DooD (Docker-outside-of-Docker) scenarios, paths are translated from
     * container paths to host paths using {@link #translateToHostPath(String)}.
     * </p>
     *
     * @param workingDirectory Directory containing job artifacts (solver,
     *                         processor, benchmark)
     * @param logPath          Directory where output should be written
     */
    private HostConfig createHostConfig(
        String workingDirectory,
        String logPath
    ) {
        List<Bind> binds = new ArrayList<>();

        // Translate paths for DooD scenarios
        String hostLogPath = translateToHostPath(logPath);

        // Mount the entire data directory so job scripts have access to all artifacts
        // (Solvers, Benchmarks, jobin scripts, etc.)
        String hostDataPath = translateToHostPath(containerDataPath);
        Volume dataVolume = new Volume(containerDataPath);
        binds.add(new Bind(hostDataPath, dataVolume, false));
        log.debug(
            "Mounting data directory: " +
                hostDataPath +
                " -> " +
                containerDataPath
        );

        // Always mount output directory for logs
        // Note: logPath is a file path, we mount its parent directory
        Path logDir = Paths.get(logPath).getParent();
        String hostLogDir = translateToHostPath(logDir.toString());
        if (!hostLogDir.startsWith(hostDataPath)) {
            // Only add separate mount if logs are outside data directory
            Volume outputVolume = new Volume(logDir.toString());
            binds.add(new Bind(hostLogDir, outputVolume));
        }

        // Get the network mode for job containers
        // Use podman-default-kube-network to connect to the same network as the app pod
        String networkMode = EnvironmentConfig.getContainerNetworkMode();
        log.debug("Job container network mode: " + networkMode);

        return new HostConfig()
            .withBinds(binds.toArray(new Bind[0]))
            .withNetworkMode(networkMode)
            .withMemory(defaultMemoryMb * 1024 * 1024) // Convert MB to bytes
            .withMemorySwap(defaultMemoryMb * 1024 * 1024) // Disable swap
            .withCpuPeriod(100000L)
            .withCpuQuota(100000L) // 1 CPU core
            .withAutoRemove(false); // Keep container for inspection after completion
    }

    /**
     * Adds a bind mount if the source directory exists.
     *
     * @param binds           List to add the bind mount to
     * @param containerBaseDir Base directory inside the StarExec container (for existence check)
     * @param hostBaseDir     Corresponding base directory on the host (for actual mount)
     * @param subDir          Subdirectory to mount
     * @param targetPath      Target path inside the job container
     * @param readOnly        Whether to mount as read-only
     */
    private void mountIfExists(
        List<Bind> binds,
        String containerBaseDir,
        String hostBaseDir,
        String subDir,
        String targetPath,
        boolean readOnly
    ) {
        Path containerPath = Paths.get(containerBaseDir, subDir);
        if (Files.exists(containerPath)) {
            Path hostPath = Paths.get(hostBaseDir, subDir);
            Volume volume = new Volume(targetPath);
            binds.add(new Bind(hostPath.toString(), volume, readOnly));
            log.debug(
                "Mounting " +
                    hostPath +
                    " -> " +
                    targetPath +
                    " (checked: " +
                    containerPath +
                    ")"
            );
        }
    }

    /**
     * Creates environment variables for the container.
     *
     * <p>
     * Paths are passed as environment variables to decouple the entrypoint.sh
     * script from hardcoded paths, improving maintainability.
     * </p>
     *
     * @param pairId           The job pair ID
     * @param workingDirectory The job's working directory on the host
     * @param outputDir        Directory where the job should write status.json and other output files
     */
    private List<String> createEnvironmentVariables(
        long pairId,
        String workingDirectory,
        String outputDir
    ) {
        List<String> envVars = new ArrayList<>(
            Arrays.asList(
                "STAREXEC_PAIR_ID=" + pairId,
                "STAREXEC_CPU_LIMIT=" + defaultCpuLimit,
                "STAREXEC_WALLCLOCK_LIMIT=" + defaultWallclockLimit,
                "STAREXEC_MEM_LIMIT=" + defaultMemoryMb,
                "CONTAINER_MODE=true", // Signal to job script to use file-based communication
                "STAREXEC_OUTPUT_DIR=" + outputDir
            )
        ); // Where to write status.json, stats.json, attributes.txt

        // Pass paths as environment variables for decoupling
        if (usePrebuiltImage) {
            envVars.add("STAREXEC_SOLVER_PATH=/starexec/solver/starexec_run");
            envVars.add(
                "STAREXEC_PRE_PROCESSOR_PATH=/starexec/pre-processor/starexec_run"
            );
            envVars.add(
                "STAREXEC_POST_PROCESSOR_PATH=/starexec/post-processor/starexec_run"
            );
            envVars.add("STAREXEC_BENCHMARK_PATH=/starexec/input/benchmark");
            // Note: STAREXEC_OUTPUT_DIR is already set above with the correct path
        }

        return envVars;
    }

    /**
     * Creates labels for container management.
     */
    private Map<String, String> createContainerLabels(long pairId) {
        Map<String, String> labels = new HashMap<>();
        labels.put(LABEL_MANAGED, "true");
        labels.put(LABEL_PAIR_ID, String.valueOf(pairId));
        return labels;
    }

    /**
     * Kills a specific job pair by execution ID.
     */
    @Override
    public boolean killPair(int execId) {
        String containerId = execIdToContainerId.get(execId);
        if (containerId == null) {
            log.warn("Container not found for execId: " + execId);
            return false;
        }

        try {
            log.info("Killing container for execId: " + execId);

            // Stop container (with timeout)
            try {
                dockerClient
                    .stopContainerCmd(containerId)
                    .withTimeout(10)
                    .exec();
            } catch (NotModifiedException e) {
                // Container already stopped
                log.debug("Container already stopped: " + containerId);
            }

            // Remove container
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();

            execIdToContainerId.remove(execId);
            releaseSubmissionSlot(execId, "killed pair");
            log.info("Container killed successfully: " + containerId);
            return true;
        } catch (NotFoundException e) {
            log.warn("Container not found (already removed?): " + containerId);
            execIdToContainerId.remove(execId);
            releaseSubmissionSlot(execId, "container already missing during kill");
            return true;
        } catch (Exception e) {
            log.error("Error killing container: " + containerId, e);
            return false;
        }
    }

    /**
     * Kills all StarExec job containers.
     */
    @Override
    public boolean killAll() {
        log.info("Killing all StarExec containers...");
        boolean success = true;

        // Kill tracked containers
        for (Integer execId : new ArrayList<>(execIdToContainerId.keySet())) {
            if (!killPair(execId)) {
                success = false;
            }
        }

        // Also clean up any orphaned containers
        cleanupOrphanedContainers();

        return success;
    }

    /**
     * Cleans up any StarExec containers that might have been orphaned.
     */
    private void cleanupOrphanedContainers() {
        try {
            List<Container> containers = dockerClient
                .listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(
                    Collections.singletonMap(LABEL_MANAGED, "true")
                )
                .exec();

            for (Container container : containers) {
                try {
                    Integer execId = removeTrackedExecutionByContainerId(
                        container.getId()
                    );
                    if (execId != null) {
                        releaseSubmissionSlot(execId, "orphan cleanup");
                    }

                    log.info(
                        "Cleaning up orphaned container: " + container.getId()
                    );
                    dockerClient
                        .removeContainerCmd(container.getId())
                        .withForce(true)
                        .exec();
                } catch (Exception e) {
                    log.warn(
                        "Failed to remove orphaned container: " +
                            container.getId(),
                        e
                    );
                }
            }
        } catch (Exception e) {
            log.warn("Error during orphaned container cleanup", e);
        }
    }

    /**
     * Returns status information about running job containers.
     */
    @Override
    public String getRunningJobsStatus() {
        try {
            StringBuilder status = new StringBuilder();
            status.append("=== StarExec Container Backend Status ===\n");

            List<Container> containers = dockerClient
                .listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(
                    Collections.singletonMap(LABEL_MANAGED, "true")
                )
                .exec();

            if (containers.isEmpty()) {
                status.append("No StarExec containers currently running.\n");
            } else {
                for (Container container : containers) {
                    status.append(
                        String.format(
                            "Container: %s | Name: %s | Status: %s | State: %s\n",
                            container.getId().substring(0, 12),
                            String.join(",", container.getNames()),
                            container.getStatus(),
                            container.getState()
                        )
                    );
                }
            }

            status
                .append("\nTracked executions: ")
                .append(execIdToContainerId.size())
                .append("\n");

            return status.toString();
        } catch (Exception e) {
            log.error("Error getting running jobs status", e);
            return "Error retrieving container status: " + e.getMessage();
        }
    }

    /**
     * Returns the set of active execution IDs.
     */
    @Override
    public Set<Integer> getActiveExecutionIds() throws IOException {
        // Refresh the map by checking actual container states
        Set<Integer> activeIds = new HashSet<>();

        for (Map.Entry<Integer, String> entry : new HashMap<>(
            execIdToContainerId
        ).entrySet()) {
            try {
                var inspection = dockerClient
                    .inspectContainerCmd(entry.getValue())
                    .exec();
                var state = inspection.getState();
                if (state != null && Boolean.TRUE.equals(state.getRunning())) {
                    activeIds.add(entry.getKey());
                } else {
                    // Container finished, remove from tracking
                    execIdToContainerId.remove(entry.getKey());
                    releaseSubmissionSlot(entry.getKey(), "container no longer running");
                }
            } catch (NotFoundException e) {
                // Container no longer exists
                execIdToContainerId.remove(entry.getKey());
                releaseSubmissionSlot(entry.getKey(), "container missing during active scan");
            }
        }

        return activeIds;
    }

    /**
     * Information about a completed container job.
     */
    public static class CompletedContainerInfo {

        public final String containerId;
        public final int pairId;
        public final String outputDir;
        public final int exitCode;

        public CompletedContainerInfo(
            String containerId,
            int pairId,
            String outputDir,
            int exitCode
        ) {
            this.containerId = containerId;
            this.pairId = pairId;
            this.outputDir = outputDir;
            this.exitCode = exitCode;
        }
    }

    /**
     * Returns a list of completed containers with their job information.
     * Used by ContainerJobMonitor to process completed jobs.
     *
     * @return List of completed container info
     */
    public List<CompletedContainerInfo> getCompletedContainers() {
        List<CompletedContainerInfo> completed = new ArrayList<>();

        List<Container> containers;
        try {
            // Podman only supports "exited" status (not "dead" like Docker)
            containers = dockerClient
                .listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(
                    Collections.singletonMap(LABEL_MANAGED, "true")
                )
                .withStatusFilter(Collections.singletonList("exited"))
                .exec();
        } catch (Exception e) {
            log.warn("Error listing containers: " + e.getMessage());
            return completed;
        }

        for (Container container : containers) {
            try {
                String pairIdLabel = container.getLabels().get(LABEL_PAIR_ID);
                if (pairIdLabel == null) continue;

                // The label may contain a timestamp instead of actual pair ID
                // We'll use -1 as a placeholder and let ContainerJobMonitor read
                // the real pair ID from status.json
                int pairId = -1;
                try {
                    // Check if it's a reasonable pair ID (under 10 million)
                    long labelValue = Long.parseLong(pairIdLabel);
                    if (labelValue < 10_000_000) {
                        pairId = (int) labelValue;
                    }
                } catch (NumberFormatException e) {
                    log.warn(
                        "Invalid pair ID format in container label: " +
                            pairIdLabel
                    );
                }

                // Get output directory from container inspection
                var inspection = dockerClient
                    .inspectContainerCmd(container.getId())
                    .exec();
                var mounts = inspection.getMounts();
                var state = inspection.getState();

                int exitCode = 0;
                if (state != null && state.getExitCodeLong() != null) {
                    exitCode = state.getExitCodeLong().intValue();
                }

                String outputDir = null;
                // First, try to get STAREXEC_OUTPUT_DIR from container's environment
                var config = inspection.getConfig();
                if (config != null && config.getEnv() != null) {
                    for (String env : config.getEnv()) {
                        if (env.startsWith("STAREXEC_OUTPUT_DIR=")) {
                            outputDir = env.substring(
                                "STAREXEC_OUTPUT_DIR=".length()
                            );
                            break;
                        }
                    }
                }

                // If not found, fall back to data mount
                if (outputDir == null && mounts != null) {
                    for (var mount : mounts) {
                        // Look for the data mount
                        var destination = mount.getDestination();
                        if (destination != null) {
                            String destPath = destination.getPath();
                            if (
                                destPath != null &&
                                destPath.contains("/app/data")
                            ) {
                                // getSource() returns the host path as a String
                                outputDir = mount.getSource();
                                break;
                            }
                        }
                    }
                }

                if (outputDir != null) {
                    completed.add(
                        new CompletedContainerInfo(
                            container.getId(),
                            pairId,
                            outputDir,
                            exitCode
                        )
                    );
                    log.debug(
                        "Found completed container for pair " +
                            pairId +
                            ": exitCode=" +
                            exitCode +
                            ", outputDir=" +
                            outputDir
                    );
                }
            } catch (Exception e) {
                log.warn("Error processing container: " + e.getMessage());
            }
        }

        return completed;
    }

    /**
     * Removes a completed container after processing.
     *
     * @param containerId The container ID to remove
     */
    public void removeCompletedContainer(String containerId) {
        Integer execId = removeTrackedExecutionByContainerId(containerId);
        if (execId != null) {
            releaseSubmissionSlot(execId, "container completed");
        }

        containerIdToPairId.invalidate(containerId);
        try {
            dockerClient
                .removeContainerCmd(containerId)
                .withForce(true)
                .withRemoveVolumes(false)
                .exec();
            log.debug("Removed completed container: " + containerId);
        } catch (Exception e) {
            log.warn(
                "Failed to remove container " +
                    containerId +
                    ": " +
                    e.getMessage()
            );
        }
    }

    // --- Methods not applicable to container backend ---

    @Override
    public String[] getWorkerNodes() {
        // Return a virtual worker node to satisfy JobManager's requirement
        // for at least one node associated with the container queue
        log.debug("getWorkerNodes: Returning virtual container worker node");
        return new String[] { CONTAINER_WORKER_NODE };
    }

    @Override
    public String[] getQueues() {
        // Return the virtual container queue so Cluster.loadQueueDetails() keeps it active
        log.debug(
            "getQueues: Returning container queue: " + CONTAINER_QUEUE_NAME
        );
        return new String[] { CONTAINER_QUEUE_NAME };
    }

    @Override
    public Map<String, String> getNodeQueueAssociations() {
        // Return the association between the virtual worker node and container queue
        // This enables Cluster.setQueueAssociationsInDb() to populate the queue_assoc table
        Map<String, String> associations = new HashMap<>();
        associations.put(CONTAINER_WORKER_NODE, CONTAINER_QUEUE_NAME);
        log.debug(
            "getNodeQueueAssociations: Returning virtual node-queue association"
        );
        return associations;
    }

    @Override
    public boolean clearNodeErrorStates() {
        return true;
    }

    @Override
    public void deleteQueue(String queueName) {
        log.debug("deleteQueue: Not applicable for container backend");
    }

    @Override
    public boolean createQueue(
        String newQueueName,
        String[] nodeNames,
        String[] sourceQueueNames
    ) {
        log.debug("createQueue: Not applicable for container backend");
        return true;
    }

    @Override
    public boolean createQueueWithSlots(
        String newQueueName,
        String[] nodeNames,
        String[] sourceQueueNames,
        Integer slots
    ) {
        log.debug("createQueueWithSlots: Not applicable for container backend");
        return true;
    }

    @Override
    public void moveNodes(
        String destQueueName,
        String[] nodeNames,
        String[] sourceQueueNames
    ) {
        log.debug("moveNodes: Not applicable for container backend");
    }

    @Override
    public void moveNode(String nodeName, String queueName) {
        log.debug("moveNode: Not applicable for container backend");
    }

    @Override
    public void clearPairTracking(int pairId) {
        // PodmanBackend does not track pair state - no-op
    }

    /**
     * Extracts UID from a socket path like /run/user/1000/podman/podman.sock
     * Returns "unknown" if extraction fails.
     */
    private String extractUidFromPath(String socketPath) {
        if (socketPath == null || socketPath.isEmpty()) {
            return "unknown";
        }
        try {
            var matcher = UID_PATTERN.matcher(socketPath);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            log.debug("Could not extract UID from socket path", e);
        }
        return "unknown";
    }
}
