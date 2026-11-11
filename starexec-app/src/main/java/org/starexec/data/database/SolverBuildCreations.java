package org.starexec.data.database;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.starexec.backend.BuildExecutionContext;
import org.starexec.backend.storage.DedicatedStorageResource;

/** Acknowledged first consumption permits one create send; historical readback never does. */
public final class SolverBuildCreations {
    public enum Transport { JAVA, CURL }

    public record Creation(BuildExecutionContext build,String resourceKey,String resourceIdentity,int execution,
                           String name,String image,Transport transport,String payload,String sha256) {
        public Creation {
            Objects.requireNonNull(build);Objects.requireNonNull(transport);
            if (execution<1 || !containerName(build).equals(name) || resourceKey==null
                    || !resourceKey.matches("[0-9a-f]+") || resourceKey.matches("0+")
                    || resourceIdentity==null || resourceIdentity.isBlank() || image==null
                    || !image.matches("sha256:[0-9a-f]{64}") || payload==null || payload.length()>65536
                    || payload.getBytes(StandardCharsets.UTF_8).length>65536 || !digest(payload).equals(sha256)) {
                throw new IllegalArgumentException("Build creation identity or request digest differs");
            }
            try {
                var json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(payload);
                if (json==null || !json.isObject() || !json.path("Image").isTextual()
                        || !image.equals(json.path("Image").textValue()) || !json.path("HostConfig").isObject()
                        || !json.path("Entrypoint").isArray() || !json.path("Cmd").isArray()
                        || !json.path("Env").isArray() || !json.path("WorkingDir").isTextual()) {
                    throw new IllegalArgumentException("Build creation request is incomplete");
                }
                var labels=json.path("Labels");
                var expected=java.util.Map.of("starexec.managed","true","starexec.label.version","2",
                        "starexec.kind","job-pair","starexec.build.layout","private-v1",
                        "starexec.build.artifact",Long.toString(build.artifactId()),"starexec.pair.id",Integer.toString(build.pairId()),
                        "starexec.attempt",Integer.toString(build.attemptNo()),"starexec.exec.id",Integer.toString(execution));
                for (var entry:expected.entrySet()) {
                    if (!labels.path(entry.getKey()).isTextual() || !entry.getValue().equals(labels.path(entry.getKey()).textValue())) {
                        throw new IllegalArgumentException("Build creation request ownership differs");
                    }
                }
            } catch (java.io.IOException malformed) {
                throw new IllegalArgumentException("Build creation request is invalid",malformed);
            }
        }
    }

    private SolverBuildCreations() { }

    public static String containerName(BuildExecutionContext build) { return "starexec-build-"+build.artifactId(); }

    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 is unavailable",unavailable); }
    }

    private static final class Refused extends SQLException { }

    /** The caller must not send after refusal or an unknown transaction response. */
    public static boolean claim(BuildExecutionContext build,DedicatedStorageResource resource,int execution,
                                String image,Transport transport,String payload) throws SQLException {
        try (Connection connection=Common.getConnection()) { return claim(connection,build,resource,execution,image,transport,payload); }
    }

    public static boolean claim(Connection connection,BuildExecutionContext build,DedicatedStorageResource resource,
                                int execution,String image,Transport transport,String payload) throws SQLException {
        if (payload==null || payload.length()>65536 || payload.getBytes(StandardCharsets.UTF_8).length>65536) return false;
        final Creation request;
        try {
            request=new Creation(build,resource.filesystemKey(),resource.identity(),execution,containerName(build),
                    image,transport,payload,digest(payload));
        } catch (IllegalArgumentException invalid) { return false; }
        try {
            return Common.runTransactional(connection,transaction->{
                resource.requireUnchanged();
                var artifact=SolverBuildArtifacts.find(transaction,build.pairId(),build.attemptNo()).orElse(null);
                if (artifact==null || artifact.id()!=build.artifactId() || !build.token().equals(artifact.executionToken())
                        || !build.source().toString().equals(artifact.sourcePath()) || !build.staging().toString().equals(artifact.stagingPath())) throw new Refused();
                try (var prepare=transaction.prepareStatement("SELECT starexec.prepare_solver_build_storage(?,?,?,?)")) {
                    prepare.setInt(1,build.pairId());prepare.setInt(2,build.attemptNo());prepare.setObject(3,build.token());prepare.setString(4,build.staging().toString());
                    try (var rows=prepare.executeQuery()) { if (!rows.next() || !rows.getBoolean(1)) throw new Refused(); }
                }
                try (var locked=transaction.prepareStatement("SELECT a.id FROM starexec.solver_build_artifacts a"
                        +" JOIN starexec.solver_build_storage_leases l ON l.artifact_id=a.id"
                        +" JOIN starexec.execution_storage_resources r ON r.resource_key=l.resource_key"
                        +" JOIN starexec.solver_build_storage_charges c ON c.artifact_id=a.id AND c.storage_kind='STAGING'"
                        +" JOIN starexec.users u ON u.id=a.origin_owner_id"
                        +" WHERE a.id=? AND a.execution_token=? AND a.engine_identity IS NULL AND a.execution_stopped_at IS NULL"
                        +" AND a.state IN ('PLANNED','STAGED') AND a.cleanup_token IS NULL"
                        +" AND l.execution_token=a.execution_token AND l.released_at IS NULL AND l.cleanup_token IS NULL"
                        +" AND r.resource_key=? AND r.observed_identity=? AND r.root_path=? AND r.capacity_bytes=? AND r.inode_limit=?"
                        +" AND c.owner_id=a.origin_owner_id AND c.path=a.staging_path AND c.limit_bytes>=r.capacity_bytes"
                        +" AND c.charged_bytes=c.limit_bytes AND c.verified_at IS NULL AND c.released_at IS NULL"
                        +" AND u.disk_size>=(SELECT COALESCE(sum(charged_bytes),0) FROM starexec.solver_build_storage_charges"
                        +" WHERE owner_id=u.id AND released_at IS NULL) FOR SHARE OF a,l,r,c,u NOWAIT")) {
                    Object[] values={build.artifactId(),build.token(),resource.filesystemKey(),resource.identity(),resource.root().toString(),resource.capacityBytes(),resource.inodeLimit()};
                    for (int i=0;i<values.length;i++) locked.setObject(i+1,values[i]);
                    try (var rows=locked.executeQuery()) { if (!rows.next()) throw new Refused(); }
                }
                try (var insert=transaction.prepareStatement("INSERT INTO starexec.solver_build_creation_requests"
                        +" (artifact_id,execution_token,resource_key,resource_identity,execution_id,container_name,image_identity,transport,request_payload,request_sha256)"
                        +" VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING")) {
                    Object[] values={build.artifactId(),build.token(),request.resourceKey(),request.resourceIdentity(),execution,request.name(),image,
                            transport.name().toLowerCase(Locale.ROOT),payload,request.sha256()};
                    for (int i=0;i<values.length;i++) insert.setObject(i+1,values[i]);
                    if (insert.executeUpdate()!=1) throw new Refused();
                }
                try (var locator=transaction.prepareStatement("UPDATE starexec.job_pairs p SET sge_id=?"
                        +" FROM starexec.jobs j,starexec.job_pair_attempts t WHERE p.id=? AND j.id=p.job_id"
                        +" AND t.pair_id=p.id AND t.current_attempt_no=? AND p.status_code=2"
                        +" AND j.buildjob AND j.killed IS NOT TRUE AND j.deleted IS NOT TRUE")) {
                    locator.setInt(1,execution);locator.setInt(2,build.pairId());locator.setInt(3,build.attemptNo());
                    if (locator.executeUpdate()!=1) throw new Refused();
                }
                resource.requireUnchanged();return true;
            });
        } catch (Refused refused) {
            if (refused.getSuppressed().length!=0) throw refused;
            return false;
        }
    }

    public static Optional<Creation> find(Connection connection,long artifact) throws SQLException {
        try (var query=connection.prepareStatement("SELECT q.*,a.origin_pair_id,a.attempt_no,a.source_path,a.staging_path"
                +" FROM starexec.solver_build_creation_requests q JOIN starexec.solver_build_artifacts a ON a.id=q.artifact_id WHERE q.artifact_id=?")) {
            query.setLong(1,artifact);try (var rows=query.executeQuery()) { return rows.next()?Optional.of(creation(rows)):Optional.empty(); }
        }
    }

    public static Optional<Creation> find(long artifact) throws SQLException {
        try (Connection connection=Common.getConnection()) { return find(connection,artifact); }
    }

    /** Conserves a positive never-started observation without admission or a new reservation. */
    public static boolean cancel(Creation expected,DedicatedStorageResource resource,String container,
                                 org.starexec.data.to.SolverBuildStopReceipt receipt) throws SQLException {
        try (Connection connection=Common.getConnection()) { return cancel(connection,expected,resource,container,receipt); }
    }

    public static boolean cancel(Connection connection,Creation expected,DedicatedStorageResource resource,String container,
                                 org.starexec.data.to.SolverBuildStopReceipt receipt) throws SQLException {
        var build=expected.build();
        if (!expected.resourceKey().equals(resource.filesystemKey()) || !expected.resourceIdentity().equals(resource.identity())
                || !ExecutionStorageLeases.isRefusedPodmanCreation(build,expected.execution(),container,receipt)
                || !"creation_unacknowledged".equals(receipt.facts().get("reason").getAsString())
                || !expected.sha256().equals(receipt.facts().get("request_sha256").getAsString())
                || !expected.name().equals(receipt.facts().get("container_name").getAsString())
                || !expected.image().equals(receipt.facts().get("image_id").getAsString())) return false;
        String engine="podman:"+expected.execution()+":"+container;
        try {
            return Common.runTransactional(connection,transaction->{
                resource.requireUnchanged();
                if (!expected.equals(find(transaction,build.artifactId()).orElse(null))) throw new Refused();
                try (var lock=transaction.prepareStatement("SELECT a.execution_stopped_at FROM starexec.solver_build_artifacts a"
                        +" JOIN starexec.solver_build_storage_leases l ON l.artifact_id=a.id"
                        +" JOIN starexec.execution_storage_resources r ON r.resource_key=l.resource_key"
                        +" JOIN starexec.solver_build_storage_charges c ON c.artifact_id=a.id AND c.storage_kind='STAGING'"
                        +" JOIN starexec.users u ON u.id=a.origin_owner_id"
                        +" WHERE a.id=? AND a.execution_token=? AND a.origin_pair_id=? AND a.attempt_no=?"
                        +" AND a.source_path=? AND a.staging_path=? AND a.cleanup_token IS NULL"
                        +" AND ((a.engine_identity IS NULL AND a.execution_stopped_at IS NULL AND a.state IN ('PLANNED','STAGED'))"
                        +" OR (a.engine_identity=? AND a.execution_stopped_at IS NOT NULL AND a.state IN ('REJECTED','DELETED')"
                        +" AND a.execution_stop_receipt_payload=? AND a.execution_stop_receipt_sha256=?))"
                        +" AND l.execution_token=a.execution_token AND l.resource_key=? AND l.released_at IS NULL AND l.cleanup_token IS NULL"
                        +" AND r.observed_identity=? AND r.root_path=? AND r.capacity_bytes=? AND r.inode_limit=?"
                        +" AND c.owner_id=a.origin_owner_id AND c.path=a.staging_path AND c.limit_bytes>=r.capacity_bytes"
                        +" AND c.charged_bytes=c.limit_bytes AND c.verified_at IS NULL AND c.released_at IS NULL"
                        +" AND u.disk_size>=(SELECT COALESCE(sum(charged_bytes),0) FROM starexec.solver_build_storage_charges"
                        +" WHERE owner_id=u.id AND released_at IS NULL)"
                        +" AND NOT EXISTS (SELECT 1 FROM starexec.solver_build_release_decisions d WHERE d.artifact_id=a.id)"
                        +" FOR UPDATE OF a,l NOWAIT FOR SHARE OF r,c,u NOWAIT")) {
                    Object[] values={build.artifactId(),build.token(),build.pairId(),build.attemptNo(),build.source().toString(),
                            build.staging().toString(),engine,receipt.payload(),receipt.sha256(),resource.filesystemKey(),resource.identity(),
                            resource.root().toString(),resource.capacityBytes(),resource.inodeLimit()};
                    for(int i=0;i<values.length;i++) lock.setObject(i+1,values[i]);
                    try (var row=lock.executeQuery()) {
                        if(!row.next()) throw new Refused();
                        if(row.getTimestamp(1)!=null) { resource.requireUnchanged();return true; }
                    }
                }
                if (!expected.resourceKey().equals(resource.filesystemKey()) || !expected.resourceIdentity().equals(resource.identity())
                        || !SolverBuildArtifacts.bindExecution(transaction,build.pairId(),build.attemptNo(),build.token(),engine)
                        || !SolverBuildArtifacts.recordCancelled(transaction,build.pairId(),build.attemptNo(),build.token(),engine,receipt)) throw new Refused();
                resource.requireUnchanged();return true;
            });
        } catch (Refused refused) {
            if(refused.getSuppressed().length!=0) throw refused;
            return false;
        }
    }

    public static List<Creation> pending() throws SQLException {
        try (Connection connection=Common.getConnection();var query=connection.prepareStatement(
                "SELECT q.*,a.origin_pair_id,a.attempt_no,a.source_path,a.staging_path FROM starexec.solver_build_creation_requests q"
                +" JOIN starexec.solver_build_artifacts a ON a.id=q.artifact_id WHERE a.engine_identity IS NULL AND a.execution_stopped_at IS NULL ORDER BY q.artifact_id")) {
            List<Creation> pending=new ArrayList<>();
            try (var rows=query.executeQuery()) { while (rows.next()) pending.add(creation(rows)); }
            return List.copyOf(pending);
        }
    }

    /** Candidates only; a restored caller must recheck the exact current hold before its first release. */
    public static List<Creation> unreleased() throws SQLException {
        try(Connection connection=Common.getConnection();var query=connection.prepareStatement(
                "SELECT q.*,a.origin_pair_id,a.attempt_no,a.source_path,a.staging_path FROM starexec.solver_build_creation_requests q"
                +" JOIN starexec.solver_build_artifacts a ON a.id=q.artifact_id WHERE a.engine_identity LIKE 'podman:%'"
                +" AND a.execution_stopped_at IS NULL AND a.state IN ('PLANNED','STAGED') AND a.cleanup_token IS NULL"
                +" AND NOT EXISTS (SELECT 1 FROM starexec.solver_build_release_decisions d WHERE d.artifact_id=a.id) ORDER BY q.artifact_id")) {
            List<Creation> result=new ArrayList<>();
            try(var rows=query.executeQuery()) {while(rows.next()) result.add(creation(rows));}
            return List.copyOf(result);
        }
    }

    /** Conserved creation IDs must not be reassigned even when their engine outcome is already bound. */
    public static int maximumExecutionId() throws SQLException {
        try (Connection connection=Common.getConnection();var query=connection.prepareStatement(
                "SELECT COALESCE(max(execution_id),0) FROM starexec.solver_build_creation_requests")) {
            try(var row=query.executeQuery()) {
                if(!row.next()) throw new SQLException("Creation identity range is unavailable");
                return row.getInt(1);
            }
        }
    }

    private static Creation creation(ResultSet row) throws SQLException {
        try {
            var build=new BuildExecutionContext(row.getLong("artifact_id"),row.getInt("origin_pair_id"),row.getInt("attempt_no"),
                    row.getObject("execution_token",UUID.class),Path.of(row.getString("source_path")),Path.of(row.getString("staging_path")));
            return new Creation(build,row.getString("resource_key"),row.getString("resource_identity"),row.getInt("execution_id"),
                    row.getString("container_name"),row.getString("image_identity"),Transport.valueOf(row.getString("transport").toUpperCase(Locale.ROOT)),
                    row.getString("request_payload"),row.getString("request_sha256"));
        } catch (IllegalArgumentException | NullPointerException invalid) { throw new SQLException("Stored build creation request is invalid",invalid); }
    }
}
