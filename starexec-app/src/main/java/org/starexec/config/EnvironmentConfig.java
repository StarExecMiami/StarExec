package org.starexec.config;

/**
 * Configuration class that reads environment variables and provides default values.
 * This replaces the build-time property substitution with runtime environment variable reading.
 *
 * @author GitHub Copilot
 */
public class EnvironmentConfig {

    private EnvironmentConfig() {
        throw new UnsupportedOperationException(
            "Cannot instantiate utility class"
        );
    }

    /**
     * Get an environment variable with a default value
     */
    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.trim().isEmpty())
            ? value
            : defaultValue;
    }

    /**
     * Get an environment variable as integer with a default value
     */
    private static int getEnvInt(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value != null && !value.trim().isEmpty()) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                // Return default if parsing fails
                return defaultValue;
            }
        }
        return defaultValue;
    }

    // Database Configuration
    public static String getDbName() {
        return getEnv("STAREXEC_DB_NAME", "starexec");
    }

    public static String getDbUrl() {
        String host = getEnv("STAREXEC_DB_HOST", "localhost");
        String port = getEnv("STAREXEC_DB_PORT", "5432");
        String dbName = getDbName();
        // Allow full override via STAREXEC_DB_URL, otherwise construct a PostgreSQL JDBC URL.
        return getEnv(
            "STAREXEC_DB_URL",
            "jdbc:postgresql://" + host + ":" + port + "/" + dbName
        );
    }

    public static String getDbUser() {
        // Align Java default with Helm/Makefile default which historically used 'starexec'.
        // Avoid an empty or unexpected user being returned by default in production.
        return getEnv("STAREXEC_DB_USER", "starexec");
    }

    public static String getDbPassword() {
        // Keep default empty for local/dev but require validation in production via
        // validateDatabaseConfig() (called when STAREXEC_VALIDATE_AT_STARTUP=true).
        return getEnv("STAREXEC_DB_PASSWORD", "");
    }

    /**
     * Validate database-related configuration and fail-fast when required values are
     * missing in production-like environments. This method intentionally errs on the
     * side of safety: in k8s / prod we require explicit credentials rather than
     * silently falling back to defaults.
     *
     * Behavior:
     *  - If running in Kubernetes (KUBERNETES_SERVICE_HOST env var present) or
     *    STAREXEC_ENV=prod, the method will throw IllegalStateException when the
     *    DB user or DB password are empty.
     *  - In other environments (dev/ci) missing password is allowed.
     *
     * Control:
     *  - Set STAREXEC_VALIDATE_AT_STARTUP=true to run this validation automatically
     *    during class initialization (helpful in container startup flows).
     */
    public static void validateDatabaseConfig() {
        String env = getEnv("STAREXEC_ENV", "dev").toLowerCase();
        boolean runningInK8s = System.getenv("KUBERNETES_SERVICE_HOST") != null;
        boolean requireSecrets = runningInK8s || "prod".equals(env);

        String user = getDbUser();
        String pass = getDbPassword();

        if (requireSecrets) {
            StringBuilder problems = new StringBuilder();
            if (user == null || user.trim().isEmpty()) {
                problems.append("STAREXEC_DB_USER is empty. ");
            }
            if (pass == null || pass.trim().isEmpty()) {
                problems.append("STAREXEC_DB_PASSWORD is empty. ");
            }
            if (problems.length() > 0) {
                throw new IllegalStateException(
                    "Database configuration invalid: " +
                        problems.toString() +
                        "\nProvide credentials via environment variables (recommended) or Kubernetes secrets."
                );
            }
        }
    }

    // Optional auto-validate when requested by environment (disabled by default)
    static {
        if (
            "true".equalsIgnoreCase(
                System.getenv("STAREXEC_VALIDATE_AT_STARTUP")
            )
        ) {
            try {
                validateDatabaseConfig();
            } catch (RuntimeException ex) {
                // Convert to unchecked and rethrow to fail startup fast
                throw ex;
            }
        }
    }

    public static int getDbPoolMax() {
        return getEnvInt("STAREXEC_DB_POOL_MAX", 125);
    }

    public static int getDbPoolMin() {
        return getEnvInt("STAREXEC_DB_POOL_MIN", 20);
    }

    // Cluster Database Configuration
    public static String getClusterDbUser() {
        return getEnv("STAREXEC_CLUSTER_DB_USER", getDbUser());
    }

    public static String getClusterDbPassword() {
        return getEnv("STAREXEC_CLUSTER_DB_PASSWORD", getDbPassword());
    }

    public static String getClusterDbUrl() {
        return getEnv("STAREXEC_CLUSTER_DB_URL", getDbUrl());
    }

    public static String getReportHost() {
        return getEnv("STAREXEC_REPORT_HOST", "localhost");
    }

    public static int getClusterUpdatePeriod() {
        return getEnvInt("STAREXEC_CLUSTER_UPDATE_PERIOD", 1200);
    }

    // Email Configuration
    public static String getEmailSmtp() {
        return getEnv("STAREXEC_EMAIL_SMTP", "localhost");
    }

    public static int getEmailPort() {
        return getEnvInt("STAREXEC_EMAIL_PORT", 25);
    }

    public static String getEmailUser() {
        return getEnv("STAREXEC_EMAIL_USER", "");
    }

    public static String getEmailPassword() {
        return getEnv("STAREXEC_EMAIL_PASSWORD", "");
    }

    public static String getEmailFrom() {
        return getEnv("STAREXEC_EMAIL_FROM", "starexec@localhost");
    }

    // Local Backend Configuration
    public static String getLocalCoreList() {
        return getEnv("STAREXEC_LOCAL_CORE_LIST", "");
    }
    
    // Backend Configuration
    public static String getBackendType() {
        return getEnv("STAREXEC_BACKEND_TYPE", "local");
    }

    public static String getBackendRoot() {
        return getEnv("STAREXEC_BACKEND_ROOT", "/tmp");
    }

    public static String getBackendWorkingDir() {
        return getEnv("STAREXEC_BACKEND_WORKING_DIR", "/tmp/starexec");
    }

    // Data Directories
    public static String getDataDir() {
        return getEnv("STAREXEC_DATA_DIR", "/tmp/starexec/data");
    }

    public static String getSandboxDir() {
        return getEnv("STAREXEC_SANDBOX_DIR", "/tmp/starexec/sandbox");
    }

    public static String getJobOutputDirectory() {
        return getEnv("STAREXEC_JOB_OUTPUT_DIR", getDataDir() + "/output");
    }

    public static String getJobLogDirectory() {
        return getEnv("STAREXEC_JOB_LOG_DIR", getDataDir() + "/logs");
    }

    // Web Configuration
    public static String getWebAddress() {
        return getEnv("STAREXEC_WEB_ADDRESS", "localhost");
    }

    public static String getWebBaseDirectory() {
        return getEnv("STAREXEC_WEB_BASE_DIR", "/starexec");
    }

    public static String getAppName() {
        return getEnv("STAREXEC_APP_NAME", "starexec");
    }

    // Proxy Configuration
    public static String getProxyAddress() {
        return getEnv("STAREXEC_PROXY_ADDRESS", getWebAddress());
    }

    public static int getProxyPort() {
        return getEnvInt("STAREXEC_PROXY_PORT", 80);
    }

    public static String getProxyScheme() {
        return getEnv("STAREXEC_PROXY_SCHEME", "http://");
    }

    // User Configuration
    public static long getUserDefaultDiskQuota() {
        return Long.parseLong(
            getEnv("STAREXEC_USER_DEFAULT_DISK_QUOTA", "10737418240")
        );
    }

    // Job Configuration
    public static int getJobSubmissionPeriod() {
        return getEnvInt("STAREXEC_JOB_SUBMISSION_PERIOD", 20);
    }

    public static int getJobPairMaxFileWrite() {
        return getEnvInt("STAREXEC_JOB_PAIR_MAX_FILE_WRITE", 600);
    }

    /**
     * Maximum size of a resumable upload chunk in bytes.
     */
    public static int getUploadSessionChunkSizeBytes() {
        return getEnvInt("STAREXEC_UPLOAD_SESSION_CHUNK_SIZE_BYTES", 2 * 1024 * 1024);
    }

    /**
     * Enables or disables live SSE streaming for job pair logs.
     */
    public static boolean isPairLogStreamEnabled() {
        return Boolean.parseBoolean(
            getEnv("STAREXEC_PAIR_LOG_STREAM_ENABLED", "true")
        );
    }

    /**
     * Maximum number of concurrent live log streams.
     */
    public static int getPairLogStreamMaxActive() {
        return getEnvInt("STAREXEC_PAIR_LOG_STREAM_MAX_ACTIVE", 100);
    }

    /**
     * Poll interval for checking newly appended log bytes.
     */
    public static long getPairLogStreamPollIntervalMs() {
        return Long.parseLong(
            getEnv("STAREXEC_PAIR_LOG_STREAM_POLL_INTERVAL_MS", "1000")
        );
    }

    /**
     * Poll interval for checking pair terminal status.
     */
    public static long getPairLogStreamStatusPollIntervalMs() {
        return Long.parseLong(
            getEnv("STAREXEC_PAIR_LOG_STREAM_STATUS_POLL_INTERVAL_MS", "5000")
        );
    }

    /**
     * Heartbeat interval for SSE comment frames.
     */
    public static long getPairLogStreamHeartbeatSeconds() {
        return Long.parseLong(
            getEnv("STAREXEC_PAIR_LOG_STREAM_HEARTBEAT_SECONDS", "15")
        );
    }

    /**
     * Maximum duration for a single live log stream connection.
     */
    public static long getPairLogStreamMaxDurationSeconds() {
        return Long.parseLong(
            getEnv("STAREXEC_PAIR_LOG_STREAM_MAX_DURATION_SECONDS", "1800")
        );
    }

    /**
     * Maximum bytes emitted in a single SSE chunk event.
     */
    public static int getPairLogStreamReadChunkBytes() {
        return getEnvInt("STAREXEC_PAIR_LOG_STREAM_READ_CHUNK_BYTES", 8192);
    }

    /**
     * Retry-After seconds when stream capacity is saturated.
     */
    public static int getPairLogStreamRetryAfterSeconds() {
        return getEnvInt("STAREXEC_PAIR_LOG_STREAM_RETRY_AFTER_SECONDS", 10);
    }

    /**
     * Number of job pairs to submit per job per cycle.
     * Higher values increase throughput but may cause queue congestion.
     * Default: 5
     */
    public static int getNumJobPairsAtATime() {
        return getEnvInt("STAREXEC_NUM_JOB_PAIRS_AT_A_TIME", 5);
    }

    /**
     * Multiplier for target queue depth per worker node.
     * queue_depth = NODE_MULTIPLIER × node_count
     * Default: 16
     */
    public static int getNodeMultiplier() {
        return getEnvInt("STAREXEC_NODE_MULTIPLIER", 16);
    }

    /**
     * Polling interval for ContainerJobMonitor (in milliseconds).
     * Lower values reduce latency but increase CPU usage.
     * Default: 5000 (5 seconds)
     *
     * @deprecated Use {@link #getAdaptivePollBaseInterval()} for adaptive polling.
     *             This method now returns the base interval for backward compatibility.
     */
    public static long getContainerJobMonitorPollInterval() {
        return Long.parseLong(
            getEnv("STAREXEC_CONTAINER_POLL_INTERVAL_MS", "5000")
        );
    }

    // ========================================================================
    // ADAPTIVE POLLING CONFIGURATION
    // ========================================================================
    // The adaptive polling system reduces CPU usage during idle periods by
    // gradually increasing the poll interval when no work is found, and
    // immediately resetting to the base interval when new jobs arrive.
    //
    // Behavior:
    // - Starts at BASE_INTERVAL_MS (default: 1000ms)
    // - After IDLE_THRESHOLD consecutive idle polls, starts backing off
    // - Each backoff multiplies interval by BACKOFF_MULTIPLIER (default: 1.5)
    // - Caps at MAX_INTERVAL_MS (default: 10000ms)
    // - Resets to BASE_INTERVAL_MS immediately when new jobs are registered
    // ========================================================================

    /**
     * Base (minimum) polling interval for adaptive job monitors.
     * This is the interval used when jobs are actively being processed.
     * Default: 1000ms (1 second) for responsive job completion detection.
     */
    public static long getAdaptivePollBaseInterval() {
        return Long.parseLong(getEnv("STAREXEC_POLL_BASE_INTERVAL_MS", "1000"));
    }

    /**
     * Maximum polling interval for adaptive job monitors.
     * The interval will not exceed this value even during extended idle periods.
     * Default: 10000ms (10 seconds) to balance responsiveness with CPU savings.
     */
    public static long getAdaptivePollMaxInterval() {
        // Enforce a sensible ceiling (e.g. 120 seconds max) to prevent SGE head node DDoS
        return Long.parseLong(getEnv("STAREXEC_POLL_MAX_INTERVAL_MS", "120000"));
    }

    /**
     * Backoff multiplier for adaptive polling.
     * After idle threshold is reached, interval = interval * multiplier.
     * Default: 1.5 (50% increase per idle cycle)
     */
    public static double getAdaptivePollBackoffMultiplier() {
        return Double.parseDouble(
            getEnv("STAREXEC_POLL_BACKOFF_MULTIPLIER", "1.5")
        );
    }

    /**
     * Number of consecutive idle polls before backoff begins.
     * Prevents premature backoff during brief gaps between job completions.
     * Default: 3 consecutive idle polls
     */
    public static int getAdaptivePollIdleThreshold() {
        return getEnvInt("STAREXEC_POLL_IDLE_THRESHOLD", 3);
    }

    // Additional path and configuration methods
    public static String getConfigPath() {
        return getEnv("STAREXEC_CONFIG_PATH", "/config");
    }

    public static String getRunsolverPath() {
        return getEnv("STAREXEC_RUNSOLVER_PATH", "/usr/local/bin/runsolver");
    }

    public static String getDownloadFileDir() {
        return getEnv("STAREXEC_DOWNLOAD_FILE_DIR", "/downloads");
    }

    public static String getSpaceXmlSchemaRelativeLoc() {
        return getEnv(
            "STAREXEC_SPACE_XML_SCHEMA_LOC",
            "/schemas/batchSpaceSchema.xsd"
        );
    }

    public static String getJobXmlSchemaRelativeLoc() {
        return getEnv(
            "STAREXEC_JOB_XML_SCHEMA_LOC",
            "/schemas/batchJobSchema.xsd"
        );
    }

    public static String getStarexecUrlPrefix() {
        return getEnv("STAREXEC_URL_PREFIX", "http");
    }

    public static String getJobGraphFileDir() {
        return getEnv("STAREXEC_JOB_GRAPH_DIR", "/jobgraphs");
    }

    public static String getClusterGraphDir() {
        return getEnv("STAREXEC_CLUSTER_GRAPH_DIR", "/secure/clustergraphs");
    }

    public static String getJobSolverCacheClearLogDirectory() {
        return getEnv(
            "STAREXEC_JOB_SOLVER_CACHE_CLEAR_LOG_DIR",
            getDataDir() + "/cache_clear_logs"
        );
    }

    public static String getOldJobOutputDirectory() {
        return getEnv(
            "STAREXEC_OLD_JOB_OUTPUT_DIR",
            getDataDir() + "/old_output"
        );
    }

    public static String getOldJobLogDirectory() {
        return getEnv("STAREXEC_OLD_JOB_LOG_DIR", getDataDir() + "/old_logs");
    }

    // Test configuration
    public static int getTestCommunityId() {
        return getEnvInt("STAREXEC_TEST_COMMUNITY_ID", 1);
    }

    public static boolean getAllowTesting() {
        return Boolean.parseBoolean(getEnv("STAREXEC_ALLOW_TESTING", "false"));
    }

    // Sandbox users
    public static String getClusterUserOne() {
        return getEnv("STAREXEC_CLUSTER_USER_ONE", "starexec1");
    }

    public static String getClusterUserTwo() {
        return getEnv("STAREXEC_CLUSTER_USER_TWO", "starexec2");
    }

    // Contact email
    public static String getContactEmail() {
        return getEnv("STAREXEC_CONTACT_EMAIL", "admin@starexec.org");
    }

    // Build information
    public static String getBuildVersion() {
        return getEnv("STAREXEC_BUILD_VERSION", "dev");
    }

    public static String getBuildUser() {
        return getEnv("STAREXEC_BUILD_USER", "unknown");
    }

    public static String getBuildDate() {
        return getEnv("STAREXEC_BUILD_DATE", "unknown");
    }

    // Backend Root and Working Directory
    public static String getBackendRootDir() {
        return getEnv("STAREXEC_BACKEND_ROOT_DIR", getBackendRoot());
    }

    // Job Pair Execution Prefix
    public static String getJobPairExecutionPrefix() {
        return getEnv("STAREXEC_JOB_PAIR_EXECUTION_PREFIX", "");
    }

    // Proxy URL
    public static String getProxyUrl() {
        return getEnv(
            "STAREXEC_PROXY_URL",
            getProxyScheme() + getProxyAddress()
        );
    }

    // Container Backend Configuration

    /**
     * Get the container engine socket path.
     * Defaults to standard Docker socket, but supports Rootless Podman paths.
     * Examples:
     *   - Standard Docker: unix:///var/run/docker.sock
     *   - Rootless Podman: unix:///run/user/1000/podman/podman.sock
     *   - TCP connection: tcp://localhost:2375
     */
    public static String getContainerSocketPath() {
        return getEnv(
            "STAREXEC_CONTAINER_SOCKET",
            "unix:///var/run/docker.sock"
        );
    }

    /**
     * Get the base image for job containers.
     * This image should contain minimal runtime dependencies.
     */
    public static String getContainerBaseImage() {
        return getEnv("STAREXEC_CONTAINER_BASE_IMAGE", "ubuntu:22.04");
    }

    /**
     * Whether to use pre-built base images (recommended for performance)
     * or build images per-job (legacy behavior for testing).
     */
    public static boolean getContainerUsePrebuiltImage() {
        return Boolean.parseBoolean(
            getEnv("STAREXEC_CONTAINER_USE_PREBUILT", "true")
        );
    }

    /**
     * Pre-built image name for job containers (when STAREXEC_CONTAINER_USE_PREBUILT=true).
     * This image should already have the entrypoint.sh and base dependencies.
     *
     * <p>Default: starexec/job-runner:latest (locally built image)</p>
     *
     * <p>For production, use the GHCR image: ghcr.io/starexecmiami/starexec-job-runner:latest</p>
     * <p>The image is automatically built and published to GitHub Container Registry
     * by the job-runner-publish.yml workflow. For local development, build with:
     * {@code make build-job-runner}</p>
     */
    public static String getContainerJobImage() {
        return getEnv(
            "STAREXEC_CONTAINER_JOB_IMAGE",
            "starexec/job-runner:latest"
        );
    }

    /**
     * Connection timeout for container engine communication (in seconds).
     */
    public static int getContainerConnectionTimeout() {
        return getEnvInt("STAREXEC_CONTAINER_CONNECTION_TIMEOUT", 30);
    }

    /**
     * Response timeout for container engine operations (in seconds).
     */
    public static int getContainerResponseTimeout() {
        return getEnvInt("STAREXEC_CONTAINER_RESPONSE_TIMEOUT", 45);
    }

    /**
     * Maximum concurrent connections to container engine.
     */
    public static int getContainerMaxConnections() {
        return getEnvInt("STAREXEC_CONTAINER_MAX_CONNECTIONS", 100);
    }

    /**
     * Maximum number of concurrently running Podman job containers.
     *
     * <p>
     * This is a hard submission gate in PodmanBackend. It defaults to 1 to
     * preserve cache locality and avoid L1/L2 contention between solver jobs.
     * </p>
     */
    public static int getContainerMaxConcurrentJobs() {
        return getEnvInt("STAREXEC_CONTAINER_MAX_CONCURRENT_JOBS", 1);
    }

    /**
     * Default memory limit for job containers (in MB).
     */
    public static long getContainerDefaultMemoryMb() {
        return Long.parseLong(
            getEnv("STAREXEC_CONTAINER_DEFAULT_MEMORY_MB", "2048")
        );
    }

    /**
     * Default CPU limit for job containers (in seconds).
     */
    public static int getContainerDefaultCpuLimit() {
        return getEnvInt("STAREXEC_CONTAINER_DEFAULT_CPU_LIMIT", 600);
    }

    /**
     * Default wallclock limit for job containers (in seconds).
     */
    public static int getContainerDefaultWallclockLimit() {
        return getEnvInt("STAREXEC_CONTAINER_DEFAULT_WALLCLOCK_LIMIT", 600);
    }

    /**
     * Host path corresponding to STAREXEC_DATA_DIR for DooD (Docker-outside-of-Docker) scenarios.
     *
     * <p>
     * When running the StarExec application inside a container with a mounted container socket
     * (DooD pattern), bind mounts for job containers must reference paths on the HOST, not
     * inside the StarExec container. This variable specifies the host path that maps to
     * STAREXEC_DATA_DIR.
     * </p>
     *
     * <p>
     * For named volumes, this is typically the volume's mountpoint on the host. For bind mounts,
     * this is the same as the host's source path.
     * </p>
     *
     * Examples:
     *   - Podman named volume: /home/user/.local/share/containers/storage/volumes/starexec-data/_data
     *   - Docker named volume: /var/lib/docker/volumes/starexec-data/_data
     *   - Bind mount: /path/on/host/data
     *   - Empty string: Disable DooD path translation (container and host paths are same)
     */
    public static String getContainerHostDataPath() {
        return getEnv("STAREXEC_CONTAINER_HOST_DATA_PATH", "");
    }

    /**
     * Container data path (inside the StarExec container).
     * Used together with getContainerHostDataPath() for path translation.
     */
    public static String getContainerDataPath() {
        return getDataDir();
    }

    /**
     * Network mode for job containers.
     * <p>
     * Specifies how job containers should be networked. Options:
     * </p>
     * <ul>
     *   <li>"host" - Use the host network (simplest, jobs can reach localhost services)</li>
     *   <li>"bridge" - Default bridge network (isolated)</li>
    *   <li>"starexec-net" - Connect to the StarExec Podman bridge network</li>
     *   <li>"container:&lt;name&gt;" - Share network with another container</li>
     * </ul>
     * <p>
     * For the current Podman deployment, use "starexec-net" to allow job containers to
     * communicate with the StarExec application and PostgreSQL containers.
     * </p>
     */
    public static String getContainerNetworkMode() {
        return getEnv(
            "STAREXEC_CONTAINER_NETWORK_MODE",
            "starexec-net"
        );
    }

    /**
     * Database host for job containers.
     * <p>
     * When job containers are on the same network as the PostgreSQL container,
     * they can use "postgres" (container name) or the container's IP address.
     * </p>
     */
    public static String getContainerDbHost() {
        return getEnv("STAREXEC_CONTAINER_DB_HOST", "postgres");
    }
}
