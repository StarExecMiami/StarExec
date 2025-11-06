package org.starexec.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Small embedded Flyway launcher that runs using flyway-core on the application's
 * classpath (WEB-INF/lib/*). This avoids bundling the Flyway command-line distribution
 * in the container image.
 *
 * Expected environment variables:
 *   STAREXEC_DB_HOST, STAREXEC_DB_PORT, STAREXEC_DB_NAME, STAREXEC_DB_USER, STAREXEC_DB_PASSWORD
 *
 * Optional system properties / args are ignored for now. Exit codes:
 *   0 = success
 *   1 = configuration / general error
 *   2 = connectivity error
 *   3 = migration failure
 */
public class EmbeddedFlywayLauncher {
    public static void main(String[] args) {
        // Prefer JVM system properties if provided (entrypoint will pass -Dflyway.*),
        // otherwise fall back to environment variables.
        String sysUrl = System.getProperty("flyway.url");
        String sysUser = System.getProperty("flyway.user");
        String sysPass = System.getProperty("flyway.password");

        String host = System.getenv("STAREXEC_DB_HOST");
        String port = System.getenv("STAREXEC_DB_PORT");
        String db = System.getenv("STAREXEC_DB_NAME");
        String user = System.getenv("STAREXEC_DB_USER");
        String pass = System.getenv("STAREXEC_DB_PASSWORD");

        String migrationsLocation = "/opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration";
        Path migrationsPath = Path.of(migrationsLocation);
        if (!Files.exists(migrationsPath)) {
            System.err.println("[EmbeddedFlyway] Migration directory not found: " + migrationsLocation);
            System.exit(1);
        }

        // If system property provided, use it directly; otherwise build from envs.
        String jdbcUrl;
        if (sysUrl != null && !sysUrl.isEmpty()) {
            jdbcUrl = sysUrl;
            // allow system props to override user/pass too
            if (sysUser != null && !sysUser.isEmpty()) user = sysUser;
            if (sysPass != null && !sysPass.isEmpty()) pass = sysPass;
        } else {
            if (host == null || port == null || db == null || user == null) {
                System.err.println("[EmbeddedFlyway] Missing required DB env vars (HOST/PORT/NAME/USER) and no -Dflyway.url provided");
                System.exit(1);
            }

            if (pass == null || pass.isEmpty()) {
                System.err.println("[EmbeddedFlyway] Missing STAREXEC_DB_PASSWORD (cannot run migrations)");
                System.exit(1);
            }

            jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s", host, port, db);
        }

        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(jdbcUrl, user, pass)
                    .locations("filesystem:" + migrationsLocation)
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .load();

            System.out.println("[EmbeddedFlyway] Running migrations against: " + jdbcUrl);
            org.flywaydb.core.api.output.MigrateResult result = flyway.migrate();
            int migrations = result.migrationsExecuted;
            System.out.println("[EmbeddedFlyway] Successfully applied migrations: " + migrations);
            System.exit(0);
        } catch (FlywayException fex) {
            System.err.println("[EmbeddedFlyway][ERROR] Flyway reported an error: " + fex.getMessage());
            // connectivity issues often wrap in FlywayException; be conservative
            System.exit(3);
        } catch (Exception ex) {
            System.err.println("[EmbeddedFlyway][ERROR] Unexpected error: " + ex.getMessage());
            System.exit(1);
        }
    }
}
