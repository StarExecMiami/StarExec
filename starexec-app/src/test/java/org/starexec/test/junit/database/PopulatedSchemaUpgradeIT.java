package org.starexec.test.junit.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises a populated historical installation rather than an empty latest schema. */
class PopulatedSchemaUpgradeIT {
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required upgrade test configuration: " + name);
        return value;
    }

    @Test void populatedSchemaUpgradesFrom121To137() throws Exception {
        String controlUrl = required("STAREXEC_UPGRADE_DB_URL");
        assertTrue(controlUrl.matches("jdbc:postgresql://(127[.]0[.]0[.]1|localhost):[0-9]+/postgres"),
            "Upgrade tests require an explicit loopback control database");
        String user = required("STAREXEC_UPGRADE_DB_USER");
        String password = required("STAREXEC_UPGRADE_DB_PASSWORD");
        Path baseline = Path.of(required("STAREXEC_UPGRADE_BASELINE_DIR")).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(baseline), "Historical migration directory must exist");
        String database = "starexec_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String url = controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + database;
        try (Connection control = DriverManager.getConnection(controlUrl, user, password)) {
            try (var statement = control.createStatement()) { statement.execute("CREATE DATABASE " + database); }
            long oid = number(control, "SELECT oid FROM pg_database WHERE datname=? AND datdba=(SELECT usesysid FROM pg_user WHERE usename=current_user)", database);
            try {
                Flyway historical = Flyway.configure().dataSource(url, user, password)
                    .schemas("starexec").defaultSchema("starexec")
                    .locations("filesystem:" + baseline, "classpath:db/upgrade/v121").target("121").load();
                historical.migrate();
                assertEquals(MigrationVersion.fromVersion("121"), historical.info().current().getVersion());
                Map<String, Integer> ids;
                Map<String, List<String>> before;
                Map<MigrationVersion, Integer> oldHistory;
                String payload = " {\"label\":\"naïve\", \"decimal\":1.00, \"source\":\"upgrade\"}\n";
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
                try (Connection connection = DriverManager.getConnection(url, user, password)) {
                    ids = seed(connection, payload, digest);
                    before = snapshot(connection, ids);
                    oldHistory = history(connection);
                    assertNotNull(oldHistory.get(MigrationVersion.fromVersion("121")), "Version121 must actually be applied");
                    assertEquals(0, number(connection, "SELECT count(*) FROM information_schema.tables WHERE table_schema='starexec' AND table_name='solver_build_artifacts'"));
                }

                Flyway upgraded = Flyway.configure().dataSource(url, user, password)
                    .schemas("starexec").defaultSchema("starexec")
                    .locations("classpath:db/migration").target("137").load();
                upgraded.migrate();
                upgraded.validate();
                assertEquals(MigrationVersion.fromVersion("137"), upgraded.info().current().getVersion());
                assertEquals(0, upgraded.info().pending().length, "No repeatable or versioned migration may remain pending");
                try (Connection connection = DriverManager.getConnection(url, user, password)) {
                    assertEquals(before, snapshot(connection, ids), "Existing application data must survive unchanged");
                    Map<MigrationVersion, Integer> afterHistory = history(connection);
                    oldHistory.forEach((version, checksum) -> assertEquals(checksum, afterHistory.get(version),
                        "Historical migration checksum changed: " + version));
                    for (int version = 122; version <= 137; version++) {
                        assertNotNull(afterHistory.get(MigrationVersion.fromVersion(String.valueOf(version))),
                            "Upgrade migration did not execute: " + version);
                    }
                    assertEquals(oldHistory.size() + 16, afterHistory.size());
                    assertEquals(0, number(connection, "SELECT count(*) FROM starexec.flyway_schema_history WHERE NOT success"));
                    assertEquals(0, number(connection, "SELECT count(*) FROM starexec.solver_build_artifacts"),
                        "Legacy solver data must not invent managed build ownership");
                    assertEquals(0, number(connection, "SELECT count(*) FROM starexec.job_output_storage_reservations"));
                    try (var query = connection.prepareStatement("SELECT manifest_payload,manifest_sha256 FROM starexec.job_pair_repro_manifests WHERE pair_id=? AND attempt_no=1")) {
                        query.setInt(1, ids.get("pair"));
                        try (var row = query.executeQuery()) {
                            assertTrue(row.next());
                            assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8), row.getString(1).getBytes(StandardCharsets.UTF_8));
                            assertEquals(digest, row.getString(2));
                            assertEquals(digest, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(row.getString(1).getBytes(StandardCharsets.UTF_8))));
                        }
                    }
                    SQLException immutable = assertThrows(SQLException.class, () -> {
                        try (var update = connection.prepareStatement("UPDATE starexec.job_pair_repro_manifests SET manifest_payload='{}' WHERE pair_id=?")) {
                            update.setInt(1, ids.get("pair"));
                            update.executeUpdate();
                        }
                    });
                    assertEquals("P0001", immutable.getSQLState());
                    assertEquals(before, snapshot(connection, ids));
                }
                assertEquals(0, upgraded.migrate().migrationsExecuted, "A second upgrade must be a no-op");
            } finally {
                assertEquals(oid, number(control, "SELECT oid FROM pg_database WHERE datname=? AND datdba=(SELECT usesysid FROM pg_user WHERE usename=current_user)", database),
                    "Only the database created by this test may be removed");
                try (var statement = control.createStatement()) { statement.execute("DROP DATABASE " + database); }
                assertEquals(0, number(control, "SELECT count(*) FROM pg_database WHERE datname=?", database));
            }
        }
        verifyFreshSchema(controlUrl, user, password);
    }

    private static void verifyFreshSchema(String controlUrl, String user, String password) throws SQLException {
        String database = "starexec_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String url = controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + database;
        try (Connection control = DriverManager.getConnection(controlUrl, user, password)) {
            try (var statement = control.createStatement()) { statement.execute("CREATE DATABASE " + database); }
            long oid = number(control, "SELECT oid FROM pg_database WHERE datname=? AND datdba=(SELECT usesysid FROM pg_user WHERE usename=current_user)", database);
            try {
                Flyway fresh = Flyway.configure().dataSource(url, user, password)
                    .schemas("starexec").defaultSchema("starexec")
                    .locations("classpath:db/migration").target("137").load();
                fresh.migrate();
                fresh.validate();
                assertEquals(MigrationVersion.fromVersion("137"), fresh.info().current().getVersion());
                assertEquals(0, fresh.info().pending().length);
                try (Connection connection = DriverManager.getConnection(url, user, password)) {
                    assertEquals(0, number(connection, "SELECT count(*) FROM starexec.flyway_schema_history WHERE NOT success"));
                    assertEquals(1, number(connection, "SELECT count(*) FROM information_schema.columns WHERE table_schema='starexec' AND table_name='job_pair_repro_manifests' AND column_name='manifest_payload'"));
                    assertEquals(0, number(connection, "SELECT count(*) FROM starexec.job_output_storage_reservations"));
                }
            } finally {
                assertEquals(oid, number(control, "SELECT oid FROM pg_database WHERE datname=? AND datdba=(SELECT usesysid FROM pg_user WHERE usename=current_user)", database));
                try (var statement = control.createStatement()) { statement.execute("DROP DATABASE " + database); }
                assertEquals(0, number(control, "SELECT count(*) FROM pg_database WHERE datname=?", database));
            }
        }
    }

    private static Map<String, Integer> seed(Connection connection, String payload, String digest) throws SQLException {
        Map<String, Integer> ids = new LinkedHashMap<>();
        ids.put("user", insert(connection, "INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,disk_quota,disk_size) VALUES('upgrade@example.invalid','Upgrade','Fixture','Test',CURRENT_TIMESTAMP,'synthetic-test-verifier',1000000,256) RETURNING id"));
        ids.put("space", insert(connection, "INSERT INTO starexec.spaces(name,description) VALUES('upgrade-space','preserved') RETURNING id"));
        ids.put("solver", insert(connection, "INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size) VALUES(?,'upgrade-solver',CURRENT_TIMESTAMP,'/fixtures/upgrade/solver',128) RETURNING id", ids.get("user")));
        ids.put("benchmark", insert(connection, "INSERT INTO starexec.benchmarks(user_id,name,uploaded,path,disk_size) VALUES(?,'upgrade-benchmark',CURRENT_TIMESTAMP,'/fixtures/upgrade/input.p',64) RETURNING id", ids.get("user")));
        ids.put("job", insert(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size) VALUES(?,'upgrade-job',1,64) RETURNING id", ids.get("user")));
        ids.put("pair", insert(connection, "INSERT INTO starexec.job_pairs(job_id,bench_id,bench_name,status_code,primary_jobpair_data) VALUES(?,?,'upgrade-benchmark',0,1) RETURNING id", ids.get("job"), ids.get("benchmark")));
        try (var stage = connection.prepareStatement("INSERT INTO starexec.jobpair_stage_data(stage_number,jobpair_id,solver_id,solver_name,cpu,wallclock,status_code,disk_size) VALUES(1,?,?,'upgrade-solver',1.25,2.5,0,64)")) {
            stage.setInt(1, ids.get("pair")); stage.setInt(2, ids.get("solver")); stage.executeUpdate();
        }
        try (var manifest = connection.prepareStatement("INSERT INTO starexec.job_pair_repro_manifests(pair_id,attempt_no,state,provenance,manifest_json,manifest_sha256,manifest_payload,finalized_at) VALUES(?,1,2,1,?::jsonb,?,?,CURRENT_TIMESTAMP)")) {
            manifest.setInt(1, ids.get("pair")); manifest.setString(2, payload);
            manifest.setString(3, digest); manifest.setString(4, payload); manifest.executeUpdate();
        }
        return ids;
    }

    private static Map<String, List<String>> snapshot(Connection connection, Map<String, Integer> ids) throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        Map<String, String> columns = Map.of(
            "user", "email,first_name,last_name,disk_size,disk_quota",
            "space", "name,description",
            "solver", "user_id,name,path,disk_size,build_status",
            "benchmark", "user_id,name,path,disk_size",
            "job", "user_id,name,total_pairs,disk_size",
            "pair", "job_id,bench_id,bench_name,status_code,primary_jobpair_data");
        Map<String, String> tables = Map.of("user", "users", "space", "spaces", "solver", "solvers",
            "benchmark", "benchmarks", "job", "jobs", "pair", "job_pairs");
        for (var item : columns.entrySet()) {
            result.put(item.getKey(), row(connection, "SELECT " + item.getValue() + " FROM starexec."
                + tables.get(item.getKey()) + " WHERE id=?", ids.get(item.getKey())));
        }
        result.put("stage", row(connection, "SELECT stage_number,solver_id,solver_name,cpu,wallclock,status_code,disk_size FROM starexec.jobpair_stage_data WHERE jobpair_id=?", ids.get("pair")));
        result.put("attempt", row(connection, "SELECT current_attempt_no FROM starexec.job_pair_attempts WHERE pair_id=?", ids.get("pair")));
        result.put("manifest", row(connection, "SELECT state,provenance,manifest_payload,manifest_sha256 FROM starexec.job_pair_repro_manifests WHERE pair_id=?", ids.get("pair")));
        return result;
    }

    private static List<String> row(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) query.setObject(i + 1, values[i]);
            try (ResultSet row = query.executeQuery()) {
                assertTrue(row.next());
                List<String> result = new ArrayList<>();
                for (int i = 1; i <= row.getMetaData().getColumnCount(); i++) result.add(row.getString(i));
                assertFalse(row.next());
                return result;
            }
        }
    }

    private static int insert(Connection connection, String sql, Object... values) throws SQLException {
        try (var query = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) query.setObject(i + 1, values[i]);
            try (var row = query.executeQuery()) { assertTrue(row.next()); return row.getInt(1); }
        }
    }

    private static long number(Connection connection, String sql, Object... values) throws SQLException {
        try (var query = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) query.setObject(i + 1, values[i]);
            try (var row = query.executeQuery()) { assertTrue(row.next()); return row.getLong(1); }
        }
    }

    private static Map<MigrationVersion, Integer> history(Connection connection) throws SQLException {
        Map<MigrationVersion, Integer> versions = new LinkedHashMap<>();
        try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT version,checksum FROM starexec.flyway_schema_history WHERE version IS NOT NULL AND success ORDER BY installed_rank")) {
            while (rows.next()) versions.put(MigrationVersion.fromVersion(rows.getString(1)), rows.getInt(2));
        }
        return versions;
    }
}
