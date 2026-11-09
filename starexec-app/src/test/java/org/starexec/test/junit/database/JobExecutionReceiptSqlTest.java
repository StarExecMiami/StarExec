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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Actual PostgreSQL phase history with explicitly synthetic capacity and provider observations. */
class JobExecutionReceiptSqlTest extends Common {
    @TempDir Path files;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IMAGE = "sha256:" + "a".repeat(64);
    private static final String PREPARATION = "b".repeat(64);
    private static final String CONTAINER = "c".repeat(64);
    private static final String ENGINE = "podman:1001:" + CONTAINER;
    private static final String RUNTIME = "{\"version\":1,\"observation\":{\"startTicks\":1,\"memoryBytes\":1024,"
            + "\"pids\":16,\"cpus\":\"0\",\"namespaces\":{\"pid\":\"pid:[1]\",\"ipc\":\"ipc:[2]\","
            + "\"net\":\"net:[3]\",\"mnt\":\"mnt:[4]\",\"user\":\"user:[5]\",\"cgroup\":\"cgroup:[6]\"}}}";
    private Connection connection;
    private int owner, job, pair;
    private UUID token;
    private String key, request;
    private final Map<String, UUID> operations = new HashMap<>();

    @BeforeAll static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("JobExecutionReceiptSqlTest");
        Common.initialize();
    }

    private static Object query(Connection con, String sql, Object... args) throws SQLException {
        try (var statement = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getObject(1); }
        }
    }
    private static void execute(Connection con, String sql, Object... args) throws SQLException {
        try (var statement = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            statement.execute();
        }
    }
    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private boolean call(String sql, Object... args) throws SQLException { return Boolean.TRUE.equals(query(connection, sql, args)); }

    @BeforeEach void seed() throws Exception {
        connection = Common.getConnection(); assertTrue(connection.getAutoCommit());
        owner = ((Number) query(connection, "INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,"
                + "disk_quota,job_pair_quota,disk_size) VALUES (?,'Test','Receipt','synthetic',now(),'synthetic',4194304,100,20) RETURNING id",
                "receipt-" + UUID.randomUUID() + "@example.invalid")).intValue();
        job = ((Number) query(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob)"
                + " VALUES (?,'ordinary-receipt',1,0,false) RETURNING id", owner)).intValue();
        pair = ((Number) query(connection, "INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data)"
                + " VALUES (?,2,1) RETURNING id", job)).intValue();
        token = UUID.randomUUID(); key = UUID.randomUUID().toString().replace("-", "");
        Path root = Files.createDirectory(files.resolve("slot"));
        assertTrue(call("SELECT starexec.register_execution_storage_resource(?,?,?,?,?)",
                key, root.toString(), "synthetic-receipt", 1048576L, 1024L));
        assertTrue(call("SELECT starexec.reserve_job_storage(?,?,?,?,?)", pair, 1, token, key, "synthetic-receipt"));
        assertTrue(call("SELECT starexec.begin_job_storage_use(?,?,?)", token, key, "synthetic-receipt"));
        ObjectNode value = JSON.createObjectNode();
        value.put("Image", IMAGE); value.putObject("HostConfig").put("ReadonlyRootfs", true);
        value.putArray("Entrypoint").add("/bin/bash"); value.putArray("Cmd").add("true"); value.putArray("Env");
        value.put("WorkingDir", "/starexec/execution");
        ObjectNode labels = value.putObject("Labels");
        Map.ofEntries(Map.entry("starexec.managed", "true"), Map.entry("starexec.label.version", "2"),
                Map.entry("starexec.kind", "job-pair"), Map.entry("starexec.job.layout", "private-v1"),
                Map.entry("starexec.job.token", token.toString()), Map.entry("starexec.storage.resource", key),
                Map.entry("starexec.job.inputs", PREPARATION), Map.entry("starexec.pair.id", Integer.toString(pair)),
                Map.entry("starexec.attempt", "1"), Map.entry("starexec.exec.id", "1001")).forEach(labels::put);
        request = value.toString();
    }

    @AfterEach void close() throws Exception {
        if (connection != null) {
            if (!connection.getAutoCommit()) { connection.rollback(); connection.setAutoCommit(true); }
            connection.close();
        }
    }

    private boolean consume() throws Exception {
        return call("SELECT starexec.consume_job_execution_request(?,?,?,?,?,?,?,?,?,?,?,?)",
                token, pair, 1, owner, key, "synthetic-receipt", 1001, IMAGE, PREPARATION, "java", request, digest(request));
    }
    private String savedHash(String kind) throws SQLException {
        return (String) query(connection, "SELECT (SELECT receipt_sha256 FROM starexec.job_execution_receipts"
                + " WHERE execution_token=? AND receipt_kind=?)", token, kind);
    }
    private ObjectNode payload(String kind) throws Exception {
        boolean createdRequest = ((Number) query(connection,
                "SELECT count(*) FROM starexec.job_execution_requests WHERE execution_token=?", token)).longValue() != 0;
        UUID operation = operations.computeIfAbsent(kind.equals("CLEANUP") ? "CLEANUP_REQUEST" : kind, ignored -> UUID.randomUUID());
        ObjectNode value = JSON.createObjectNode();
        value.put("version", 1); value.put("kind", kind); value.put("execution_token", token.toString());
        value.put("pair_id", pair); value.put("attempt_no", 1); value.put("owner_id", owner);
        value.put("resource_key", key); value.put("resource_identity", "synthetic-receipt");
        value.put("operation_token", operation.toString());
        value.put("engine_identity", createdRequest ? ENGINE : null);
        value.put("request_sha256", createdRequest ? digest(request) : null);
        value.put("preparation_sha256", createdRequest ? PREPARATION : null);
        ObjectNode facts = value.putObject("facts");
        switch (kind) {
            case "CREATED" -> {
                facts.put("resource_id", CONTAINER); facts.put("container_name", "starexec-pair-" + pair + "-attempt-1");
                facts.put("image_id", IMAGE);
            }
            case "RELEASE" -> facts.put("runtime_payload", RUNTIME);
            case "STOP" -> {
                facts.put("resource_id", CONTAINER); facts.put("image_id", IMAGE);
                facts.put("status", "exited"); facts.put("pid", 0); facts.put("running", false);
                facts.put("paused", false); facts.put("restarting", false); facts.put("privileged", false);
                facts.put("private_pid_namespace", true); facts.put("restart_policy", "no");
                facts.put("started_at", "2026-01-01T00:00:01Z"); facts.put("finished_at", "2026-01-01T00:00:02Z");
                facts.put("exit_code", 0);
            }
            case "OUTPUT" -> {
                facts.put("stop_sha256", savedHash("STOP")); facts.put("manifest_payload", "{\"files\":[]}");
                facts.put("manifest_sha256", digest("{\"files\":[]}"));
            }
            case "CLEANUP_REQUEST" -> {
                facts.put("staging_identity", "synthetic-directory");
                if (createdRequest) facts.put("stop_sha256", savedHash("STOP"));
                else facts.put("reason", "never_created");
            }
            case "CLEANUP" -> {
                facts.put("staging_identity", "synthetic-directory"); facts.put("entries", 0); facts.put("provider_absent", true);
            }
            default -> throw new AssertionError("Unknown phase");
        }
        return value;
    }
    private boolean record(ObjectNode value) throws Exception {
        String text = value.toString();
        return call("SELECT starexec.record_job_execution_receipt(?,?,?,?,?,?)",
                token, value.get("kind").asText(), UUID.fromString(value.get("operation_token").asText()),
                value.get("engine_identity").isNull() ? null : value.get("engine_identity").asText(), text, digest(text));
    }
    private boolean record(String kind) throws Exception { return record(payload(kind)); }
    private void created() throws Exception { assertTrue(consume()); assertTrue(record("CREATED")); }
    private void released() throws Exception { created(); assertTrue(record("RELEASE")); }
    private void stopped() throws Exception { released(); assertTrue(record("STOP")); }
    private void output() throws Exception { stopped(); assertTrue(record("OUTPUT")); }
    private void intent() throws Exception { output(); assertTrue(record("CLEANUP_REQUEST")); }
    private void before(String kind) throws Exception {
        switch (kind) {
            case "CREATED" -> assertTrue(consume());
            case "RELEASE" -> created();
            case "STOP" -> released();
            case "OUTPUT" -> stopped();
            case "CLEANUP_REQUEST" -> output();
            case "CLEANUP" -> intent();
            default -> throw new AssertionError();
        }
    }
    private void held() throws SQLException {
        assertEquals(1048596L, ((Number) query(connection, "SELECT disk_size FROM starexec.users WHERE id=?", owner)).longValue());
        assertNull(query(connection, "SELECT released_at FROM starexec.execution_storage_claims WHERE execution_token=?", token));
    }

    @Test void ordinaryPhaseProtocolExists() throws Exception {
        assertEquals(1, ((Number) query(connection,
                "SELECT (to_regclass('starexec.job_execution_receipts') IS NOT NULL)::integer")).intValue());
    }

    @ParameterizedTest @ValueSource(strings = {"CREATED", "RELEASE", "STOP", "OUTPUT", "CLEANUP_REQUEST", "CLEANUP"})
    void eachPhaseConservesExactBytesAndRejectsReplay(String kind) throws Exception {
        before(kind); ObjectNode value = payload(kind); value.put("note", "measurement-\u03bb");
        assertTrue(record(value)); String sha = savedHash(kind); assertEquals(digest(value.toString()), sha);
        connection.close(); connection = Common.getConnection();
        assertEquals(value.toString(), query(connection,
                "SELECT receipt_payload FROM starexec.job_execution_receipts WHERE execution_token=? AND receipt_kind=?", token, kind));
        assertFalse(record(value)); assertEquals(sha, savedHash(kind)); held();
    }

    @ParameterizedTest @ValueSource(strings = {"RELEASE", "STOP", "OUTPUT", "CLEANUP_REQUEST", "CLEANUP"})
    void outOfOrderPhasesAreRejected(String kind) throws Exception {
        assertTrue(consume()); assertThrows(SQLException.class, () -> record(kind)); held();
    }

    @ParameterizedTest @ValueSource(strings = {"pair_id", "attempt_no", "owner_id", "execution_token",
            "resource_key", "resource_identity", "request_sha256", "preparation_sha256", "engine_identity", "operation_token"})
    void mismatchedEnvelopeCannotBeConserved(String field) throws Exception {
        created(); ObjectNode value = payload("RELEASE");
        if (value.get(field).isNumber()) value.put(field, value.get(field).asInt() + 1);
        else if (field.equals("operation_token")) value.put(field, "00000000-0000-0000-0000-000000000000");
        else value.put(field, "wrong");
        assertThrows(SQLException.class, () -> record(value)); assertNull(savedHash("RELEASE")); held();
    }

    @ParameterizedTest @ValueSource(strings = {"running", "paused", "restarting", "privileged", "pid",
            "private_pid_namespace", "restart_policy", "image_id", "resource_id", "finished_at"})
    void incompleteOrRunningScopeCannotProduceStop(String field) throws Exception {
        released(); ObjectNode value = payload("STOP"); ObjectNode facts = (ObjectNode) value.get("facts");
        facts.remove(field); assertThrows(SQLException.class, () -> record(value)); assertNull(savedHash("STOP")); held();
    }

    @ParameterizedTest @ValueSource(strings = {"killed", "deleted", "paused", "attempt", "locator"})
    void revokedCurrentAdmissionCannotConsumeRelease(String change) throws Exception {
        created();
        switch (change) {
            case "killed", "deleted", "paused" -> execute(connection, "UPDATE starexec.jobs SET " + change + "=true WHERE id=?", job);
            case "attempt" -> execute(connection, "UPDATE starexec.job_pair_attempts SET current_attempt_no=2 WHERE pair_id=?", pair);
            case "locator" -> execute(connection, "UPDATE starexec.job_pairs SET sge_id=2002 WHERE id=?", pair);
            default -> throw new AssertionError();
        }
        assertThrows(SQLException.class, () -> record("RELEASE")); assertNull(savedHash("RELEASE")); held();
    }

    @Test void cleanupIntentFencesPreviouslyUnconsumedCreation() throws Exception {
        assertTrue(record("CLEANUP_REQUEST")); assertThrows(SQLException.class, this::consume);
        assertTrue(record("CLEANUP")); held();
        assertFalse(call("SELECT starexec.release_unstarted_job_storage(?,?,?)", token, key, "synthetic-receipt"));
    }

    @Test void uncertainConsumedCreationCannotPretendNeverCreated() throws Exception {
        assertTrue(consume()); assertThrows(SQLException.class, () -> record("CLEANUP_REQUEST")); held();
    }

    @Test void cancelledHeldCreationCanStopWithoutPayloadRelease() throws Exception {
        created(); execute(connection, "UPDATE starexec.jobs SET killed=true WHERE id=?", job);
        ObjectNode value = payload("STOP"); ((ObjectNode) value.get("facts")).put("disposition", "cancelled");
        assertTrue(record(value)); assertThrows(SQLException.class, () -> record("RELEASE"));
        assertTrue(record("CLEANUP_REQUEST")); assertTrue(record("CLEANUP")); held();
    }

    @Test void normalOutputMustBeConservedBeforeCleanupIntent() throws Exception {
        stopped(); assertThrows(SQLException.class, () -> record("CLEANUP_REQUEST"));
        assertTrue(record("OUTPUT")); assertTrue(record("CLEANUP_REQUEST")); held();
    }

    @Test void cleanupCompletionRequiresTheSameOperationAndEmptyResource() throws Exception {
        intent(); ObjectNode changed = payload("CLEANUP"); changed.put("operation_token", UUID.randomUUID().toString());
        assertThrows(SQLException.class, () -> record(changed));
        ObjectNode nonempty = payload("CLEANUP"); ((ObjectNode) nonempty.get("facts")).put("entries", 1);
        assertThrows(SQLException.class, () -> record(nonempty));
        ObjectNode present = payload("CLEANUP"); ((ObjectNode) present.get("facts")).put("provider_absent", false);
        assertThrows(SQLException.class, () -> record(present)); held();
    }

    @Test void receiptHistoryCannotBeChangedDeletedOrTruncated() throws Exception {
        created(); String before = savedHash("CREATED");
        assertThrows(SQLException.class, () -> execute(connection,
                "UPDATE starexec.job_execution_receipts SET receipt_payload=receipt_payload||' ' WHERE execution_token=?", token));
        assertThrows(SQLException.class, () -> execute(connection,
                "DELETE FROM starexec.job_execution_receipts WHERE execution_token=?", token));
        assertThrows(SQLException.class, () -> execute(connection, "TRUNCATE starexec.job_execution_receipts"));
        assertEquals(before, savedHash("CREATED")); held();
    }

    @Test void rolledBackPhaseDoesNotConsumeItsOperation() throws Exception {
        created(); connection.setAutoCommit(false); assertTrue(record("RELEASE"));
        connection.rollback(); connection.setAutoCommit(true); assertNull(savedHash("RELEASE"));
        assertTrue(record("RELEASE")); held();
    }

    @Test void contendedClaimCannotPartiallyRecordRelease() throws Exception {
        created();
        try (Connection other = Common.getConnection()) {
            other.setAutoCommit(false);
            try {
                query(other, "SELECT 1 FROM starexec.execution_storage_claims WHERE execution_token=? FOR UPDATE", token);
                SQLException refused = assertThrows(SQLException.class, () -> record("RELEASE"));
                assertEquals("55P03", refused.getSQLState()); assertNull(savedHash("RELEASE")); held();
            } finally { other.rollback(); other.setAutoCommit(true); }
        }
        assertTrue(record("RELEASE"));
    }

    @Test void cleanupReceiptAloneCannotReleaseAUsedClaim() throws Exception {
        intent(); assertTrue(record("CLEANUP"));
        assertThrows(SQLException.class, () -> execute(connection,
                "UPDATE starexec.execution_storage_claims SET released_at=clock_timestamp() WHERE execution_token=?", token));
        held();
    }

    @ParameterizedTest @ValueSource(strings = {"creation", "release", "create-after-cleanup"})
    void staleSnapshotsCannotMissNewCreationOrCleanup(String phase) throws Exception {
        if (phase.equals("release")) created();
        ObjectNode planned = phase.equals("create-after-cleanup") ? null
                : payload(phase.equals("creation") ? "CLEANUP_REQUEST" : "RELEASE");
        try (Connection stale = Common.getConnection()) {
            int isolation = stale.getTransactionIsolation();
            stale.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            stale.setAutoCommit(false);
            Connection original = connection;
            try {
                query(stale, "SELECT count(*) FROM starexec.job_execution_receipts WHERE execution_token=?", token);
                if (phase.equals("creation")) assertTrue(consume());
                else if (phase.equals("create-after-cleanup")) assertTrue(record("CLEANUP_REQUEST"));
                else {
                    ObjectNode stopped = payload("STOP");
                    ((ObjectNode) stopped.get("facts")).put("disposition", "cancelled");
                    assertTrue(record(stopped)); assertTrue(record("CLEANUP_REQUEST"));
                }
                connection = stale;
                SQLException refused = assertThrows(SQLException.class,
                        () -> { if (planned == null) consume(); else record(planned); });
                assertEquals("40001", refused.getSQLState());
            } finally {
                connection = original; stale.rollback(); stale.setAutoCommit(true);
                stale.setTransactionIsolation(isolation);
            }
        }
        held();
    }

    @Test void nullDispositionCannotBypassNormalOutputPreservation() throws Exception {
        released(); ObjectNode value = payload("STOP"); ((ObjectNode) value.get("facts")).putNull("disposition");
        assertThrows(SQLException.class, () -> record(value)); assertNull(savedHash("STOP")); held();
    }
}

