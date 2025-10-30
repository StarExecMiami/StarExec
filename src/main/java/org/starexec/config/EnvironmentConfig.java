package org.starexec.config;

/**
 * Configuration class that reads environment variables and provides default values.
 * This replaces the build-time property substitution with runtime environment variable reading.
 * 
 * @author GitHub Copilot
 */
public class EnvironmentConfig {
    
    private EnvironmentConfig() {
        throw new UnsupportedOperationException("Cannot instantiate utility class");
    }
    
    /**
     * Get an environment variable with a default value
     */
    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.trim().isEmpty()) ? value : defaultValue;
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
        return getEnv("STAREXEC_DB_URL", "jdbc:postgresql://" + host + ":" + port + "/" + dbName);
    }
    public static String getDbUser() {
        return getEnv("STAREXEC_DB_USER", "se_admin");
    }
    
    public static String getDbPassword() {
        return getEnv("STAREXEC_DB_PASSWORD", "");
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
        return Long.parseLong(getEnv("STAREXEC_USER_DEFAULT_DISK_QUOTA", "10737418240"));
    }
    
    // Job Configuration
    public static int getJobSubmissionPeriod() {
        return getEnvInt("STAREXEC_JOB_SUBMISSION_PERIOD", 20);
    }
    
    public static int getJobPairMaxFileWrite() {
        return getEnvInt("STAREXEC_JOB_PAIR_MAX_FILE_WRITE", 600);
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
        return getEnv("STAREXEC_SPACE_XML_SCHEMA_LOC", "/schemas/batchSpaceSchema.xsd");
    }
    
    public static String getJobXmlSchemaRelativeLoc() {
        return getEnv("STAREXEC_JOB_XML_SCHEMA_LOC", "/schemas/batchJobSchema.xsd");
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
        return getEnv("STAREXEC_JOB_SOLVER_CACHE_CLEAR_LOG_DIR", getDataDir() + "/cache_clear_logs");
    }
    
    public static String getOldJobOutputDirectory() {
        return getEnv("STAREXEC_OLD_JOB_OUTPUT_DIR", getDataDir() + "/old_output");
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
        return getEnv("STAREXEC_PROXY_URL", getProxyScheme() + getProxyAddress());
    }
}
