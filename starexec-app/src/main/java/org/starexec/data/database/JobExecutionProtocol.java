package org.starexec.data.database;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.jobs.JobExecutionPreparation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Exact ordinary protocol history; replay and uncertain commit responses never authorize another send. */
public final class JobExecutionProtocol {
    public enum Transport { JAVA, CURL }
    public enum Phase { CREATED, RELEASE, STOP, OUTPUT, CLEANUP_REQUEST, CLEANUP, OUTPUT_REQUEST }
    private static final int MAX_PAYLOAD = 16 * 1024 * 1024;
    private static final UUID ZERO = new UUID(0, 0);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record Creation(UUID token, int pairId, int attemptNo, int ownerId, String resourceKey,
                           String resourceIdentity, int executionId, String name, String image,
                           String preparationFingerprint, Transport transport, String payload, String sha256) {
        public Creation {
            require(token != null && !ZERO.equals(token) && pairId > 0 && attemptNo > 0 && ownerId > 0 && executionId > 0,
                    "Ordinary creation identity is invalid");
            require(resourceKey != null && resourceKey.matches("[0-9a-f]+") && !resourceKey.matches("0+")
                    && resourceIdentity != null && !resourceIdentity.isBlank(), "Ordinary creation resource is invalid");
            require(("starexec-pair-" + pairId + "-attempt-" + attemptNo).equals(name)
                    && image != null && image.matches("sha256:[0-9a-f]{64}")
                    && preparationFingerprint != null && preparationFingerprint.matches("[0-9a-f]{64}")
                    && transport != null, "Ordinary creation policy is incomplete");
            JsonNode root = parse(payload, sha256);
            require(image.equals(text(root, "Image")) && root.path("HostConfig").isObject()
                    && root.path("HostConfig").path("ReadonlyRootfs").isBoolean()
                    && root.path("HostConfig").path("ReadonlyRootfs").booleanValue()
                    && root.path("Entrypoint").isArray() && root.path("Cmd").isArray() && root.path("Env").isArray()
                    && root.path("WorkingDir").isTextual(), "Ordinary creation request is incomplete");
            for (String field : List.of("Entrypoint", "Cmd", "Env"))
                for (JsonNode entry : root.path(field)) require(entry.isTextual(), "Ordinary command values must be strings");
            var expected = Map.ofEntries(Map.entry("starexec.managed", "true"), Map.entry("starexec.label.version", "2"),
                    Map.entry("starexec.kind", "job-pair"), Map.entry("starexec.job.layout", "private-v1"),
                    Map.entry("starexec.job.token", token.toString()), Map.entry("starexec.storage.resource", resourceKey),
                    Map.entry("starexec.job.inputs", preparationFingerprint), Map.entry("starexec.pair.id", Integer.toString(pairId)),
                    Map.entry("starexec.attempt", Integer.toString(attemptNo)), Map.entry("starexec.exec.id", Integer.toString(executionId)));
            for (var entry : expected.entrySet())
                require(entry.getValue().equals(text(root.path("Labels"), entry.getKey())), "Ordinary request ownership differs");
        }

        /** Missing historical attribution stays unknown; current pair metadata is not a substitute. */
        public java.util.OptionalInt originalJobId() {
            var label=parse(payload,sha256).path("Labels").get("starexec.job.id");
            if(label==null) return java.util.OptionalInt.empty();
            require(label.isTextual() && label.textValue().matches("[1-9][0-9]{0,9}"),"Ordinary original job is invalid");
            try {return java.util.OptionalInt.of(Integer.parseInt(label.textValue()));}
            catch(NumberFormatException invalid) {throw new IllegalArgumentException("Ordinary original job is invalid",invalid);}
        }
    }

    /** Conserved data is not a new physical observation; trusted observers must verify facts before recording them. */
    public record Receipt(UUID token, Phase phase, UUID operation, String engine, String payload, String sha256) {
        public Receipt {
            require(token != null && !ZERO.equals(token) && phase != null && operation != null && !ZERO.equals(operation),
                    "Ordinary receipt identity is invalid");
            require(engine == null || engine.matches("^(podman|kubernetes):.+"), "Ordinary receipt engine is invalid");
            JsonNode root = parse(payload, sha256);
            require(root.path("version").isNumber() && root.path("version").decimalValue().compareTo(java.math.BigDecimal.ONE) == 0
                    && phase.name().equals(text(root, "kind")) && token.toString().equals(text(root, "execution_token"))
                    && operation.toString().equals(text(root, "operation_token")) && root.path("facts").isObject()
                    && (engine == null ? root.path("engine_identity").isNull() : engine.equals(text(root, "engine_identity"))),
                    "Ordinary receipt envelope differs");
        }
    }

    private JobExecutionProtocol() { }
    private static final class Refused extends SQLException { private static final long serialVersionUID = 1L; }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        require(value != null && value.isTextual(), "Ordinary protocol field must be text");
        return value.textValue();
    }
    private static JsonNode parse(String payload, String sha256) {
        require(payload != null && payload.length() <= MAX_PAYLOAD && payload.getBytes(StandardCharsets.UTF_8).length <= MAX_PAYLOAD
                && sha256 != null && sha256.matches("[0-9a-f]{64}") && digest(payload).equals(sha256),
                "Ordinary protocol payload digest or size differs");
        try {
            JsonNode root = JSON.readTree(payload);
            require(root != null && root.isObject(), "Ordinary protocol payload must be an object");
            return root;
        } catch (IOException invalid) { throw new IllegalArgumentException("Ordinary protocol JSON is invalid", invalid); }
    }

    public static String digest(String payload) {
        Objects.requireNonNull(payload);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException missing) { throw new IllegalStateException("SHA-256 is unavailable", missing); }
    }

    private static void standalone(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("Ordinary protocol mutation cannot use a foreign transaction");
        int isolation = connection.getTransactionIsolation();
        if (isolation != Connection.TRANSACTION_READ_COMMITTED && isolation != Connection.TRANSACTION_READ_UNCOMMITTED)
            throw new SQLException("Ordinary protocol mutation requires read committed", "40001");
    }
    private static void liveLease(Connection connection, JobStorageLeases.Lease expected,
                                  DedicatedStorageResource resource) throws SQLException, IOException {
        resource.requireUnchanged();
        if (!expected.equals(JobStorageLeases.find(connection, expected.token()).orElse(null))
                || expected.useStartedAt() == null || expected.releasedAt() != null
                || !expected.resourceKey().equals(resource.filesystemKey()) || !expected.resourceIdentity().equals(resource.identity())
                || !expected.root().equals(resource.root()) || expected.limitBytes() != resource.capacityBytes()
                || expected.inodeLimit() != resource.inodeLimit()) throw new Refused();
    }

    /** Only the first acknowledged TRUE precedes one create; the caller must still verify its private provider policy. */
    public static boolean consumeCreation(Connection connection, JobStorageLeases.Lease expected,
            DedicatedStorageResource resource, JobExecutionPreparation preparation, int execution, String image,
            Transport transport, String payload) throws SQLException {
        standalone(connection);
        if (expected == null || resource == null || preparation == null || expected.useStartedAt() == null
                || expected.releasedAt() != null) return false;
        if (findCreation(connection, expected.token()).isPresent()) return false;
        final Creation intent;
        try {
            intent = new Creation(expected.token(), expected.pairId(), expected.attemptNo(), expected.ownerId(),
                    expected.resourceKey(), expected.resourceIdentity(), execution,
                    "starexec-pair-" + expected.pairId() + "-attempt-" + expected.attemptNo(),
                    image, preparation.fingerprint(), transport, payload, digest(payload));
            if (!preparation.workingDirectory().toString().equals(text(parse(payload, intent.sha256()), "WorkingDir"))) return false;
        } catch (IllegalArgumentException | NullPointerException invalid) { return false; }
        try { preparation.requireCurrent(connection); }
        catch (IOException invalid) { throw new SQLException("Ordinary prepared inputs are no longer current", invalid); }
        try {
            return Common.runTransactional(connection, tx -> {
                liveLease(tx, expected, resource);
                try (var statement = tx.prepareStatement("SELECT starexec.consume_job_execution_request(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    Object[] args = {intent.token(),intent.pairId(),intent.attemptNo(),intent.ownerId(),intent.resourceKey(),
                            intent.resourceIdentity(),intent.executionId(),intent.image(),intent.preparationFingerprint(),
                            intent.transport().name().toLowerCase(Locale.ROOT),intent.payload(),intent.sha256()};
                    for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
                    try (var rows = statement.executeQuery()) { if (!rows.next() || !rows.getBoolean(1)) throw new Refused(); }
                }
                if (!intent.equals(findCreation(tx, intent.token()).orElse(null))) throw new Refused();
                liveLease(tx, expected, resource);
                return true;
            });
        } catch (Refused refused) {
            if (refused.getSuppressed().length != 0) throw refused;
            return false;
        }
    }

    /** Recording validates persistence and identity; only the trusted caller can establish physical facts. */
    public static boolean recordReceipt(Connection connection, JobStorageLeases.Lease expected,
            DedicatedStorageResource resource, Receipt receipt) throws SQLException {
        standalone(connection);
        if (expected == null || resource == null || receipt == null || !expected.token().equals(receipt.token())) return false;
        if (findReceipt(connection, receipt.token(), receipt.phase()).isPresent()) return false;
        try {
            return Common.runTransactional(connection, tx -> {
                liveLease(tx, expected, resource);
                try (var statement = tx.prepareStatement("SELECT starexec.record_job_execution_receipt(?,?,?,?,?,?)")) {
                    Object[] args = {receipt.token(),receipt.phase().name(),receipt.operation(),receipt.engine(),receipt.payload(),receipt.sha256()};
                    for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
                    try (var rows = statement.executeQuery()) { if (!rows.next() || !rows.getBoolean(1)) throw new Refused(); }
                }
                if (!receipt.equals(findReceipt(tx, receipt.token(), receipt.phase()).orElse(null))) throw new Refused();
                liveLease(tx, expected, resource);
                return true;
            });
        } catch (Refused refused) {
            if (refused.getSuppressed().length != 0) throw refused;
            return false;
        }
    }

    public static Optional<Creation> findCreation(Connection connection, UUID token) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM starexec.job_execution_requests WHERE execution_token=?")) {
            statement.setObject(1, token);
            try (var row = statement.executeQuery()) { return row.next() ? Optional.of(creation(row)) : Optional.empty(); }
        }
    }
    public static Optional<Creation> findCreation(Connection connection, int pair, int attempt) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM starexec.job_execution_requests WHERE pair_id=? AND attempt_no=?")) {
            statement.setInt(1, pair); statement.setInt(2, attempt);
            try (var row = statement.executeQuery()) { return row.next() ? Optional.of(creation(row)) : Optional.empty(); }
        }
    }
    public static Optional<Receipt> findReceipt(Connection connection, UUID token, Phase phase) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM starexec.job_execution_receipts WHERE execution_token=? AND receipt_kind=?")) {
            statement.setObject(1, token); statement.setString(2, Objects.requireNonNull(phase).name());
            try (var row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                try {
                    return Optional.of(new Receipt(row.getObject("execution_token", UUID.class),
                            Phase.valueOf(row.getString("receipt_kind")), row.getObject("operation_token", UUID.class),
                            row.getString("engine_identity"), row.getString("receipt_payload"), row.getString("receipt_sha256")));
                } catch (IllegalArgumentException | NullPointerException invalid) {
                    throw new SQLException("Stored ordinary execution receipt is invalid", invalid);
                }
            }
        }
    }

    /** Includes stopped or cleaned requests until accounting is settled; these are recovery candidates, not launch permission. */
    public static List<Creation> activeRequests(Connection connection) throws SQLException {
        List<Creation> result = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT q.* FROM starexec.job_execution_requests q"
                + " JOIN starexec.execution_storage_claims c USING(execution_token) WHERE c.lease_kind='JOB' AND c.released_at IS NULL"
                + " ORDER BY q.pair_id,q.attempt_no"); var rows = statement.executeQuery()) {
            while (rows.next()) result.add(creation(rows));
        }
        return List.copyOf(result);
    }
    public static int maximumExecutionId(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT GREATEST("
                + "(SELECT COALESCE(max(execution_id),0) FROM starexec.job_execution_requests),"
                + "(SELECT COALESCE(max(execution_id),0) FROM starexec.solver_build_creation_requests))");
             var row = statement.executeQuery()) {
            if (!row.next()) throw new SQLException("Ordinary creation identity range is unavailable");
            return row.getInt(1);
        }
    }
    private static Creation creation(ResultSet row) throws SQLException {
        try {
            return new Creation(row.getObject("execution_token",UUID.class),row.getInt("pair_id"),row.getInt("attempt_no"),
                    row.getInt("owner_id"),row.getString("resource_key"),row.getString("resource_identity"),
                    row.getInt("execution_id"),row.getString("container_name"),row.getString("image_identity"),
                    row.getString("preparation_sha256"),Transport.valueOf(row.getString("transport").toUpperCase(Locale.ROOT)),
                    row.getString("request_payload"),row.getString("request_sha256"));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Stored ordinary creation request is invalid", invalid);
        }
    }
}

