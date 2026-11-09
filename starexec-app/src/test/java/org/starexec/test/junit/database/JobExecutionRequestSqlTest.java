package org.starexec.test.junit.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.starexec.data.database.Common;
import org.starexec.test.util.DatabaseTestSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL protocol tests; capacity and prepared-input fingerprints are synthetic. */
class JobExecutionRequestSqlTest extends Common {
    @TempDir Path files;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long CAPACITY = 16_777_216;
    private static final String IMAGE = "sha256:" + "a".repeat(64);
    private static final String PREPARATION = "b".repeat(64);
    private Connection connection;
    private int owner;
    private int job;
    private int pair;
    private UUID token;
    private String key;
    private String identity;

    @BeforeAll static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("JobExecutionRequestSqlTest");
        Common.initialize();
    }

    private static long number(Connection con, String sql, Object... args) throws SQLException {
        try (var query = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) query.setObject(i + 1, args[i]);
            try (var row = query.executeQuery()) { assertTrue(row.next()); return row.getLong(1); }
        }
    }

    private static void execute(Connection con, String sql, Object... args) throws SQLException {
        try (var query = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) query.setObject(i + 1, args[i]);
            query.execute();
        }
    }

    private static boolean call(Connection con, String sql, Object... args) throws SQLException {
        try (var query = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) query.setObject(i + 1, args[i]);
            try (var row = query.executeQuery()) { assertTrue(row.next()); return row.getBoolean(1); }
        }
    }

    @BeforeEach void seed() throws Exception {
        connection = Common.getConnection();
        assertTrue(connection.getAutoCommit());
        owner = (int) number(connection, "INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,"
                + "disk_quota,job_pair_quota,disk_size) VALUES (?,'Test','Request','synthetic',now(),'synthetic',?,100,20) RETURNING id",
                "request-" + UUID.randomUUID() + "@example.invalid", CAPACITY * 4);
        job = (int) number(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob)"
                + " VALUES (?,'ordinary-request',1,0,false) RETURNING id", owner);
        pair = (int) number(connection, "INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data)"
                + " VALUES (?,2,1) RETURNING id", job);
        token = UUID.randomUUID(); key = UUID.randomUUID().toString().replace("-", "");
        identity = "synthetic-ordinary-request";
        Path root = Files.createDirectory(files.resolve("slot"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        assertTrue(call(connection, "SELECT starexec.register_execution_storage_resource(?,?,?,?,?)",
                key, root.toString(), identity, CAPACITY, 1024L));
        assertTrue(call(connection, "SELECT starexec.reserve_job_storage(?,?,?,?,?)", pair, 1, token, key, identity));
    }

    @AfterEach void close() throws Exception {
        if (connection != null) {
            if (!connection.getAutoCommit()) { connection.rollback(); connection.setAutoCommit(true); }
            connection.close();
        }
    }

    private void use() throws SQLException {
        assertTrue(call(connection, "SELECT starexec.begin_job_storage_use(?,?,?)", token, key, identity));
    }

    private String payload() {
        ObjectNode value = JSON.createObjectNode();
        value.put("Image", IMAGE); value.putObject("HostConfig").put("ReadonlyRootfs", true);
        value.putArray("Entrypoint").add("/bin/bash"); value.putArray("Cmd").add("true"); value.putArray("Env");
        value.put("WorkingDir", "/starexec/execution");
        var labels = value.putObject("Labels");
        Map.ofEntries(Map.entry("starexec.managed", "true"), Map.entry("starexec.label.version", "2"),
                Map.entry("starexec.kind", "job-pair"), Map.entry("starexec.job.layout", "private-v1"),
                Map.entry("starexec.job.token", token.toString()), Map.entry("starexec.storage.resource", key),
                Map.entry("starexec.job.inputs", PREPARATION), Map.entry("starexec.pair.id", Integer.toString(pair)),
                Map.entry("starexec.attempt", "1"), Map.entry("starexec.exec.id", "1001"))
                .forEach(labels::put);
        return value.toString();
    }

    private boolean consume(String payload, String transport) throws SQLException {
        return call(connection, "SELECT starexec.consume_job_execution_request(?,?,?,?,?,?,?,?,?,?,?,"
                + "encode(sha256(convert_to(?,'UTF8')),'hex'))",
                token, pair, 1, owner, key, identity, 1001, IMAGE, PREPARATION, transport, payload, payload);
    }

    private boolean consume() throws SQLException { return consume(payload(), "java"); }
    private long count() throws SQLException {
        return number(connection, "SELECT count(*) FROM starexec.job_execution_requests WHERE execution_token=?", token);
    }
    private long locator() throws SQLException {
        return number(connection, "SELECT COALESCE(sge_id,0) FROM starexec.job_pairs WHERE id=?", pair);
    }
    private long usage() throws SQLException {
        return number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", owner);
    }
    private void held() throws SQLException {
        assertEquals(20 + CAPACITY, usage());
        assertEquals(1, number(connection, "SELECT count(*) FROM starexec.execution_storage_claims"
                + " WHERE execution_token=? AND released_at IS NULL", token));
    }

    @Test void ordinaryCreationProtocolExists() throws Exception {
        assertEquals(1, number(connection,
                "SELECT (to_regclass('starexec.job_execution_requests') IS NOT NULL)::integer"));
    }

    @ParameterizedTest @ValueSource(strings = {"java", "curl"})
    void consumptionConservesExactBytesAndLocatorOnlyOnce(String transport) throws Exception {
        use(); String original = " \n" + payload() + "\n"; assertTrue(consume(original, transport));
        assertEquals(1, count()); assertEquals(1001, locator()); held();
        connection.close(); connection = Common.getConnection();
        try (var query = connection.prepareStatement("SELECT request_payload,transport,"
                + "request_sha256=encode(sha256(convert_to(request_payload,'UTF8')),'hex'),"
                + "consumed_at>=c.use_started_at FROM starexec.job_execution_requests q"
                + " JOIN starexec.execution_storage_claims c USING(execution_token) WHERE execution_token=?")) {
            query.setObject(1, token);
            try (var row = query.executeQuery()) {
                assertTrue(row.next()); assertEquals(original, row.getString(1));
                assertEquals(transport, row.getString(2)); assertTrue(row.getBoolean(3)); assertTrue(row.getBoolean(4));
            }
        }
        assertFalse(consume(original, transport)); assertFalse(consume(payload(), transport));
        assertEquals(1, count()); assertEquals(1001, locator()); held();
    }

    @Test void unusedReservationDoesNotAuthorizeCreation() throws Exception {
        assertThrows(SQLException.class, this::consume);
        assertEquals(0, count()); assertEquals(0, locator()); held();
    }

    @ParameterizedTest @ValueSource(strings = {"killed", "deleted", "paused", "buildjob", "running",
            "terminal", "attempt", "owner", "locator", "funding"})
    void staleOrUnfundedAttemptCannotConsume(String change) throws Exception {
        use();
        switch (change) {
            case "killed", "deleted", "paused", "buildjob" ->
                    execute(connection, "UPDATE starexec.jobs SET " + change + "=true WHERE id=?", job);
            case "running" -> execute(connection, "UPDATE starexec.job_pairs SET status_code=4 WHERE id=?", pair);
            case "terminal" -> execute(connection, "UPDATE starexec.job_pairs SET status_code=7 WHERE id=?", pair);
            case "attempt" -> execute(connection, "UPDATE starexec.job_pair_attempts SET current_attempt_no=2 WHERE pair_id=?", pair);
            case "owner" -> {
                long other = number(connection, "INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,"
                        + "disk_quota,job_pair_quota,disk_size) VALUES (?,'Other','Request','synthetic',now(),'synthetic',?,100,20) RETURNING id",
                        "request-" + UUID.randomUUID() + "@example.invalid", CAPACITY * 4);
                execute(connection, "UPDATE starexec.jobs SET user_id=? WHERE id=?", other, job);
            }
            case "locator" -> execute(connection, "UPDATE starexec.job_pairs SET sge_id=2002 WHERE id=?", pair);
            case "funding" -> execute(connection, "UPDATE starexec.users SET disk_size=20 WHERE id=?", owner);
            default -> fail("Unknown test case");
        }
        try { assertThrows(SQLException.class, this::consume); assertEquals(0, count()); }
        finally {
            if (change.equals("funding")) execute(connection,
                    "UPDATE starexec.users SET disk_size=? WHERE id=?", 20 + CAPACITY, owner);
        }
        assertEquals(change.equals("locator") ? 2002 : 0, locator());
    }

    @ParameterizedTest @ValueSource(strings = {"starexec.managed", "starexec.label.version", "starexec.kind",
            "starexec.job.layout", "starexec.job.token", "starexec.storage.resource", "starexec.job.inputs",
            "starexec.pair.id", "starexec.attempt", "starexec.exec.id"})
    void requestOwnershipLabelsMustMatch(String label) throws Exception {
        use(); ObjectNode altered = (ObjectNode) JSON.readTree(payload());
        ((ObjectNode) altered.get("Labels")).put(label, "wrong");
        assertThrows(SQLException.class, () -> consume(altered.toString(), "java"));
        assertEquals(0, count()); assertEquals(0, locator()); held();
    }

    @ParameterizedTest @ValueSource(strings = {"Image", "HostConfig", "Entrypoint", "Cmd", "Env", "WorkingDir", "Labels"})
    void incompleteRequestsAreNotConsumed(String field) throws Exception {
        use(); ObjectNode altered = (ObjectNode) JSON.readTree(payload()); altered.remove(field);
        assertThrows(SQLException.class, () -> consume(altered.toString(), "java"));
        assertEquals(0, count()); assertEquals(0, locator()); held();
    }

    @ParameterizedTest @ValueSource(strings = {"top", "label", "nested"})
    void duplicateJsonKeysAreRejected(String location) throws Exception {
        use(); String value = payload();
        value = switch (location) {
            case "top" -> "{\"Image\":\"wrong\"," + value.substring(1);
            case "label" -> value.replace("\"Labels\":{", "\"Labels\":{\"starexec.managed\":\"wrong\",");
            case "nested" -> value.substring(0, value.length() - 1) + ",\"Extra\":{\"x\":1,\"x\":2}}";
            default -> throw new AssertionError();
        };
        String duplicate = value;
        assertThrows(SQLException.class, () -> consume(duplicate, "java"));
        assertEquals(0, count()); assertEquals(0, locator()); held();
    }

    @Test void readOnlyRootAndExactImageAreRequired() throws Exception {
        use(); ObjectNode value = (ObjectNode) JSON.readTree(payload()); value.put("Image", "sha256:" + "c".repeat(64));
        assertThrows(SQLException.class, () -> consume(value.toString(), "java"));
        value.put("Image", IMAGE); ((ObjectNode) value.get("HostConfig")).put("ReadonlyRootfs", false);
        assertThrows(SQLException.class, () -> consume(value.toString(), "java"));
        assertEquals(0, count()); held();
    }

    @Test void exactDigestAndResourceIncarnationAreRequired() throws Exception {
        use(); String value = payload();
        assertThrows(SQLException.class, () -> call(connection,
                "SELECT starexec.consume_job_execution_request(?,?,?,?,?,?,?,?,?,?,?,?)",
                token, pair, 1, owner, key, identity, 1001, IMAGE, PREPARATION, "java", value, "0".repeat(64)));
        assertThrows(SQLException.class, () -> call(connection,
                "SELECT starexec.consume_job_execution_request(?,?,?,?,?,?,?,?,?,?,?,"
                        + "encode(sha256(convert_to(?,'UTF8')),'hex'))",
                token, pair, 1, owner, key, "other-incarnation", 1001, IMAGE, PREPARATION, "java", value, value));
        assertEquals(0, count()); assertEquals(0, locator()); held();
    }

    @Test void largeScientificRequestIsPreservedWithoutTruncation() throws Exception {
        use(); ObjectNode value = (ObjectNode) JSON.readTree(payload()); value.put("Dependencies", "x".repeat(131072));
        String large = value.toString(); assertTrue(consume(large, "java"));
        assertEquals(large.length(), number(connection,
                "SELECT octet_length(request_payload) FROM starexec.job_execution_requests WHERE execution_token=?", token));
        held();
    }

    @Test void storageBoundRejectsOversizedRequestWithoutConsumption() throws Exception {
        use(); ObjectNode value = (ObjectNode) JSON.readTree(payload()); value.put("Dependencies", "x".repeat((int) CAPACITY));
        assertThrows(SQLException.class, () -> consume(value.toString(), "java"));
        assertEquals(0, count()); assertEquals(0, locator()); held();
    }

    @ParameterizedTest @ValueSource(strings = {"pair_id=pair_id+1", "attempt_no=attempt_no+1", "owner_id=owner_id+1",
            "resource_key=resource_key||'a'", "resource_identity=resource_identity||'x'", "execution_id=execution_id+1",
            "container_name=container_name||'x'", "image_identity='sha256:'||repeat('c',64)",
            "preparation_sha256=repeat('c',64)", "transport='curl'", "request_payload=request_payload||' '",
            "request_sha256=repeat('c',64)", "consumed_at=consumed_at+interval '1 second'"})
    void historyCannotBeRewritten(String assignment) throws Exception {
        use(); assertTrue(consume());
        assertThrows(SQLException.class, () -> execute(connection,
                "UPDATE starexec.job_execution_requests SET " + assignment + " WHERE execution_token=?", token));
        assertEquals(1, count()); held();
    }

    @Test void historySurvivesCancellationAndRefusedPrematureDeletion() throws Exception {
        use(); assertTrue(consume()); execute(connection, "UPDATE starexec.jobs SET killed=true WHERE id=?", job);
        SQLException premature = assertThrows(SQLException.class, () -> execute(connection,
                "DELETE FROM starexec.job_pairs WHERE id=?", pair));
        assertTrue(premature.getMessage().contains("Active job storage must be settled before deletion"));
        assertEquals(1, count()); assertFalse(consume()); held();
        assertThrows(SQLException.class, () -> execute(connection,
                "DELETE FROM starexec.job_execution_requests WHERE execution_token=?", token));
        assertThrows(SQLException.class, () -> execute(connection, "TRUNCATE starexec.job_execution_requests"));
        assertEquals(1, count());
    }

    @Test void aFailedTransactionDoesNotLeaveRequestOrLocator() throws Exception {
        use(); connection.setAutoCommit(false); assertTrue(consume());
        connection.rollback(); connection.setAutoCommit(true);
        assertEquals(0, count()); assertEquals(0, locator()); held();
        assertTrue(consume());
    }

    @ParameterizedTest @ValueSource(strings = {"job", "pair", "attempt", "owner", "resource", "claim"})
    void contendedOwnershipCannotPartiallyConsume(String lock) throws Exception {
        use();
        String sql = switch (lock) {
            case "job" -> "SELECT id FROM starexec.jobs WHERE id=" + job;
            case "pair" -> "SELECT id FROM starexec.job_pairs WHERE id=" + pair;
            case "attempt" -> "SELECT pair_id FROM starexec.job_pair_attempts WHERE pair_id=" + pair;
            case "owner" -> "SELECT id FROM starexec.users WHERE id=" + owner;
            case "resource" -> "SELECT 1 FROM starexec.execution_storage_resources WHERE resource_key='" + key + "'";
            case "claim" -> "SELECT 1 FROM starexec.execution_storage_claims WHERE execution_token='" + token + "'";
            default -> throw new AssertionError();
        };
        try (Connection other = Common.getConnection()) {
            other.setAutoCommit(false);
            try {
                number(other, sql + " FOR UPDATE");
                SQLException refused = assertThrows(SQLException.class, this::consume);
                assertEquals("55P03", refused.getSQLState());
                assertEquals(0, count()); assertEquals(0, locator()); held();
            } finally { other.rollback(); other.setAutoCommit(true); }
        }
        assertTrue(consume());
    }

    @Test void usedStorageCannotBeReleasedThroughTheUnusedAdapter() throws Exception {
        use(); assertTrue(consume());
        assertFalse(call(connection, "SELECT starexec.release_unstarted_job_storage(?,?,?)", token, key, identity));
        assertThrows(SQLException.class, () -> execute(connection,
                "UPDATE starexec.execution_storage_claims SET released_at=clock_timestamp() WHERE execution_token=?", token));
        held();
    }
}

