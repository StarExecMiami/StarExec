package org.starexec.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

/**
 * Embedded Flyway launcher that executes database migrations using flyway-core
 * from the application's classpath (WEB-INF/lib/*).
 *
 * This launcher reads database credentials exclusively from environment variables
 * to avoid exposing sensitive information via command-line arguments or system properties.
 *
 * Required environment variables:
 *   STAREXEC_DB_HOST       - Database hostname or IP
 *   STAREXEC_DB_PORT       - Database port (default: 5432)
 *   STAREXEC_DB_NAME       - Database name
 *   STAREXEC_DB_USER       - Database user
 *   STAREXEC_DB_PASSWORD   - Database password (NEVER pass via CLI)
 *
 * Optional environment variables:
 *   STAREXEC_DB_SCHEMA     - Schema name (default: starexec)
 *   STAREXEC_MIGRATIONS_DIR - Migration directory override (default: auto-detected)
 *
 * Exit codes:
 *   0 = Success: All migrations applied
 *   1 = Configuration error (missing credentials or invalid path)
 *   2 = Connection error (database unreachable)
 *   3 = Migration execution failure (Flyway validation or execution error)
 *
 * Security Design:
 *   - Database credentials are read from environment variables only
 *   - System properties (-D flags) are NOT used for sensitive data
 *   - This prevents credential exposure via 'ps' or '/proc' inspection
 *   - All credential values are logged only as "***REDACTED***"
 *
 * Usage Examples:
 *   # Standard usage (all env vars set in container)
 *   java -cp "WEB-INF/classes:WEB-INF/lib/*" org.starexec.migration.EmbeddedFlywayLauncher
 *
 *   # Override schema name
 *   STAREXEC_DB_SCHEMA=my_schema java -cp ... org.starexec.migration.EmbeddedFlywayLauncher
 */
public class EmbeddedFlywayLauncher {

    // Environment variable names (never modify these)
    private static final String ENV_DB_HOST = "STAREXEC_DB_HOST";
    private static final String ENV_DB_PORT = "STAREXEC_DB_PORT";
    private static final String ENV_DB_NAME = "STAREXEC_DB_NAME";
    private static final String ENV_DB_USER = "STAREXEC_DB_USER";
    private static final String ENV_DB_PASSWORD = "STAREXEC_DB_PASSWORD";
    private static final String ENV_DB_SCHEMA = "STAREXEC_DB_SCHEMA";
    private static final String ENV_MIGRATIONS_DIR = "STAREXEC_MIGRATIONS_DIR";

    // Defaults
    private static final String DEFAULT_PORT = "5432";
    private static final String DEFAULT_SCHEMA = "starexec";
    private static final String DEFAULT_MIGRATIONS_PATH =
        "/opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration";

    public static void main(String[] args) {
        try {
            // Phase 1: Load and validate configuration
            MigrationConfig config = loadConfiguration();

            // Phase 2: Verify migration directory exists
            verifyMigrationDirectory(config.migrationsPath);

            // Phase 3: Execute migrations
            executeMigrations(config);

            System.exit(0);
        } catch (ConfigurationException ex) {
            System.err.println(
                "[EmbeddedFlyway][ERROR] Configuration Error: " +
                    ex.getMessage()
            );
            System.exit(1);
        } catch (ConnectivityException ex) {
            System.err.println(
                "[EmbeddedFlyway][ERROR] Connectivity Error: " + ex.getMessage()
            );
            System.exit(2);
        } catch (MigrationException ex) {
            System.err.println(
                "[EmbeddedFlyway][ERROR] Migration Error: " + ex.getMessage()
            );
            System.exit(3);
        } catch (Exception ex) {
            System.err.println(
                "[EmbeddedFlyway][FATAL] Unexpected Error: " + ex.getMessage()
            );
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /**
     * Load and validate database configuration from environment variables.
     *
     * @return MigrationConfig with validated credentials and paths
     * @throws ConfigurationException if required environment variables are missing
     */
    private static MigrationConfig loadConfiguration()
        throws ConfigurationException {
        System.out.println(
            "[EmbeddedFlyway][CONFIG] Loading database configuration from environment variables..."
        );

        // Read required configuration
        String host = System.getenv(ENV_DB_HOST);
        String port = System.getenv(ENV_DB_PORT);
        String dbName = System.getenv(ENV_DB_NAME);
        String user = System.getenv(ENV_DB_USER);
        String password = System.getenv(ENV_DB_PASSWORD);
        String schema = System.getenv(ENV_DB_SCHEMA);
        String migrationsDir = System.getenv(ENV_MIGRATIONS_DIR);

        // Validate required credentials
        if (host == null || host.trim().isEmpty()) {
            throw new ConfigurationException(
                ENV_DB_HOST + " environment variable is not set"
            );
        }
        if (dbName == null || dbName.trim().isEmpty()) {
            throw new ConfigurationException(
                ENV_DB_NAME + " environment variable is not set"
            );
        }
        if (user == null || user.trim().isEmpty()) {
            throw new ConfigurationException(
                ENV_DB_USER + " environment variable is not set"
            );
        }
        if (password == null || password.isEmpty()) {
            throw new ConfigurationException(
                ENV_DB_PASSWORD +
                    " environment variable is not set (cannot proceed without database password)"
            );
        }

        // Apply defaults
        if (port == null || port.trim().isEmpty()) {
            port = DEFAULT_PORT;
        }
        if (schema == null || schema.trim().isEmpty()) {
            schema = DEFAULT_SCHEMA;
        }
        if (migrationsDir == null || migrationsDir.trim().isEmpty()) {
            migrationsDir = DEFAULT_MIGRATIONS_PATH;
        }

        // Log configuration (credentials redacted)
        System.out.println("[EmbeddedFlyway][CONFIG] Configuration loaded:");
        System.out.println("[EmbeddedFlyway][CONFIG]   Host: " + host);
        System.out.println("[EmbeddedFlyway][CONFIG]   Port: " + port);
        System.out.println("[EmbeddedFlyway][CONFIG]   Database: " + dbName);
        System.out.println("[EmbeddedFlyway][CONFIG]   User: " + user);
        System.out.println(
            "[EmbeddedFlyway][CONFIG]   Password: ***REDACTED***"
        );
        System.out.println("[EmbeddedFlyway][CONFIG]   Schema: " + schema);
        System.out.println(
            "[EmbeddedFlyway][CONFIG]   Migrations: " + migrationsDir
        );

        // Construct JDBC URL
        String jdbcUrl = String.format(
            "jdbc:postgresql://%s:%s/%s",
            host,
            port,
            dbName
        );

        return new MigrationConfig(
            jdbcUrl,
            user,
            password,
            schema,
            migrationsDir
        );
    }

    /**
     * Verify that the migration directory exists and is readable.
     *
     * @param migrationsPath Path to migration directory
     * @throws ConfigurationException if directory does not exist or is not readable
     */
    private static void verifyMigrationDirectory(String migrationsPath)
        throws ConfigurationException {
        System.out.println(
            "[EmbeddedFlyway][VERIFY] Checking migration directory..."
        );

        Path migrationPath = Path.of(migrationsPath);

        if (!Files.exists(migrationPath)) {
            throw new ConfigurationException(
                "Migration directory not found: " +
                    migrationsPath +
                    "\nEnsure the application WAR file includes WEB-INF/classes/db/migration/" +
                    "\nVerify WAR extraction occurred: unzip -l starexec.war | grep 'WEB-INF/classes/db/migration'"
            );
        }

        if (!Files.isDirectory(migrationPath)) {
            throw new ConfigurationException(
                migrationsPath + " exists but is not a directory"
            );
        }

        if (!Files.isReadable(migrationPath)) {
            throw new ConfigurationException(
                migrationsPath + " exists but is not readable"
            );
        }

        // Count migration files
        try {
            long migrationCount = Files.list(migrationPath)
                .filter(p -> p.getFileName().toString().endsWith(".sql"))
                .count();

            System.out.println(
                "[EmbeddedFlyway][VERIFY] ✅ Migration directory verified: " +
                    migrationCount +
                    " SQL files found"
            );
        } catch (Exception ex) {
            throw new ConfigurationException(
                "Error reading migration directory: " + ex.getMessage()
            );
        }
    }

    /**
     * Execute Flyway migrations against the configured database.
     *
     * @param config Migration configuration with credentials and paths
     * @throws ConnectivityException if database is unreachable
     * @throws MigrationException if migration execution fails
     */
    private static void executeMigrations(MigrationConfig config)
        throws ConnectivityException, MigrationException {
        System.out.println(
            "[EmbeddedFlyway][MIGRATE] Starting Flyway migration execution..."
        );
        System.out.println(
            "[EmbeddedFlyway][MIGRATE] Target: " + config.jdbcUrl
        );
        System.out.println(
            "[EmbeddedFlyway][MIGRATE] Schema: " + config.schema
        );

        try {
            // Build Flyway configuration
            Flyway flyway = Flyway.configure()
                // Database connectivity
                .dataSource(config.jdbcUrl, config.user, config.password)
                // Migration location (only filesystem, not classpath)
                .locations("filesystem:" + config.migrationsPath)
                // Schema configuration
                .defaultSchema(config.schema)
                .createSchemas(true)
                // Baseline configuration (allows starting from any point)
                .baselineOnMigrate(true)
                .baselineVersion("1")
                // Load the configured flyway instance
                .load();

            // Execute all pending migrations
            System.out.println(
                "[EmbeddedFlyway][MIGRATE] Executing migrations..."
            );
            org.flywaydb.core.api.output.MigrateResult result =
                flyway.migrate();

            // Report success
            int migrationsExecuted = result.migrationsExecuted;
            System.out.println(
                "[EmbeddedFlyway][SUCCESS] ✅ Migration execution completed"
            );
            System.out.println(
                "[EmbeddedFlyway][SUCCESS] Migrations applied: " +
                    migrationsExecuted
            );
            System.out.println(
                "[EmbeddedFlyway][SUCCESS] Database schema is now up-to-date"
            );
        } catch (org.flywaydb.core.api.FlywayException fex) {
            // Flyway validation or execution error
            String message = fex.getMessage();
            if (
                (message != null &&
                    message.toLowerCase().contains("unable to obtain")) ||
                (message != null && message.contains("Connection refused"))
            ) {
                throw new ConnectivityException(
                    "Unable to connect to database: " + message
                );
            }
            throw new MigrationException("Flyway migration failed: " + message);
        } catch (Exception ex) {
            // Unexpected error
            throw new MigrationException(
                "Unexpected error during migration: " + ex.getMessage()
            );
        }
    }

    /**
     * Container for migration configuration data.
     */
    private static class MigrationConfig {

        final String jdbcUrl;
        final String user;
        final String password;
        final String schema;
        final String migrationsPath;

        MigrationConfig(
            String jdbcUrl,
            String user,
            String password,
            String schema,
            String migrationsPath
        ) {
            this.jdbcUrl = jdbcUrl;
            this.user = user;
            this.password = password;
            this.schema = schema;
            this.migrationsPath = migrationsPath;
        }
    }

    /**
     * Exception thrown when configuration is invalid or missing.
     */
    private static class ConfigurationException extends Exception {

        ConfigurationException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when database connectivity fails.
     */
    private static class ConnectivityException extends Exception {

        ConnectivityException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when migration execution fails.
     */
    private static class MigrationException extends Exception {

        MigrationException(String message) {
            super(message);
        }
    }
}
