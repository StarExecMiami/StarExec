package org.starexec.data.database;

import com.google.gson.Gson;
import org.starexec.backend.BuildExecutionContext;
import org.starexec.backend.storage.BuildExecutionRuntime;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Durable resource exclusivity; capacity and physical cleanup remain independently observed. */
public final class ExecutionStorageLeases {
    private static final Gson JSON = new Gson();
    private ExecutionStorageLeases() { }

    public record StagingCleanup(UUID token, String stagingIdentity, Instant startedAt, Instant finishedAt, Instant releasedAt) { }

    /** Input identity is a snapshot; engine admission must recheck the current execution. */
    public record DispatchInputs(int ownerId, long generation, Path source, SolverBuildArtifacts.Helper helper) { }

    public record ReleaseDecision(UUID requestToken, String engineIdentity, String runtimePayload,
                                  String runtimeSha256, Instant requestedAt) { }

    /** Call only after trusted kernel validation. A committed existing decision never grants another release. */
    public static boolean claimRelease(Connection connection, BuildExecutionContext build,
                                        DedicatedStorageResource resource, String engine, UUID request,
                                        BuildExecutionRuntime.Observation observation) throws SQLException {
        if (request == null || observation == null) return false;
        return transact(connection, tx -> {
            resource.requireUnchanged();
            boolean claimed = call(tx,"SELECT starexec.claim_solver_build_release(?,?,?,?,?,?,?,?,?,?,?)",
                    build.artifactId(),build.pairId(),build.attemptNo(),build.token(),
                    build.source().toString(),build.staging().toString(),resource.filesystemKey(),resource.identity(),
                    engine,request,JSON.toJson(Map.of("version",1,"observation",observation)));
            resource.requireUnchanged();
            return claimed;
        });
    }

    /** Historical reconciliation only; this observation must never authorize provider I/O. */
    public static Optional<ReleaseDecision> findReleaseDecision(Connection connection, BuildExecutionContext build,
                                                                DedicatedStorageResource resource) throws SQLException {
        if (findStagingCleanup(connection,build,resource).isEmpty()) return Optional.empty();
        try (var statement = connection.prepareStatement("SELECT request_token,engine_identity,runtime_payload,runtime_sha256,requested_at"
                + " FROM starexec.solver_build_release_decisions WHERE artifact_id=? AND execution_token=?"
                + " AND resource_key=? AND resource_identity=?")) {
            statement.setLong(1,build.artifactId()); statement.setObject(2,build.token());
            statement.setString(3,resource.filesystemKey()); statement.setString(4,resource.identity());
            try (var result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new ReleaseDecision(result.getObject(1,UUID.class),result.getString(2),result.getString(3),
                        result.getString(4),instant(result.getTimestamp(5))));
            }
        }
    }

    public static Optional<DispatchInputs> findDispatchInputs(Connection connection, BuildExecutionContext build,
                                                               DedicatedStorageResource resource) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("Build input capture cannot use a foreign transaction");
        try {
            return Common.runTransactional(connection, transaction -> {
                resource.requireUnchanged();
                var lease = findStagingCleanup(transaction, build, resource).orElse(null);
                if (lease == null || lease.releasedAt() != null || lease.token() != null
                        || !reserveInTransaction(transaction, build, resource)) throw new Refused();
                try (var statement = transaction.prepareStatement(
                        "SELECT a.origin_owner_id,a.build_generation,h.benchmark_id,h.owner_id,h.path,h.byte_size"
                        + " FROM starexec.solver_build_artifacts a"
                        + " JOIN starexec.solver_build_helpers h ON h.benchmark_id=a.helper_benchmark_id"
                        + " JOIN starexec.benchmarks b ON b.id=h.benchmark_id"
                        + " WHERE a.id=? AND a.execution_token=? AND a.engine_identity IS NULL"
                        + " AND a.execution_stopped_at IS NULL AND a.cleanup_token IS NULL"
                        + " AND a.state IN ('PLANNED','STAGED') AND h.cleanup_token IS NULL"
                        + " AND h.owner_id=a.origin_owner_id AND b.user_id=h.owner_id AND b.deleted IS NOT TRUE"
                        + " AND b.name='starexec_build' AND b.path=h.path AND b.disk_size=h.byte_size")) {
                    statement.setLong(1, build.artifactId()); statement.setObject(2, build.token());
                    try (var result = statement.executeQuery()) {
                        if (!result.next()) throw new Refused();
                        var helper = new SolverBuildArtifacts.Helper(result.getInt(3),result.getInt(4),
                                result.getString(5),result.getLong(6),null,null);
                        DispatchInputs inputs = new DispatchInputs(result.getInt(1),result.getLong(2),build.source(),helper);
                        resource.requireUnchanged();
                        return Optional.of(inputs);
                    }
                }
            });
        } catch (Refused refused) {
            if (refused.getSuppressed().length != 0) throw refused;
            return Optional.empty();
        }
    }

    /** Matches the complete conserved context; detached live foreign keys do not erase its origin. */
    public static Optional<StagingCleanup> findStagingCleanup(Connection connection, BuildExecutionContext build,
                                                             DedicatedStorageResource resource) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT l.cleanup_token,l.cleanup_started_at,l.cleanup_finished_at,l.released_at,l.cleanup_staging_identity"
                + " FROM starexec.solver_build_storage_leases l JOIN starexec.solver_build_artifacts a ON a.id=l.artifact_id"
                + " JOIN starexec.execution_storage_resources r ON r.resource_key=l.resource_key"
                + " WHERE a.id=? AND a.origin_pair_id=? AND a.attempt_no=? AND a.execution_token=?"
                + " AND a.source_path=? AND a.staging_path=? AND l.execution_token=a.execution_token"
                + " AND r.resource_key=? AND r.observed_identity=? AND r.root_path=?"
                + " AND r.capacity_bytes=? AND r.inode_limit=?")) {
            Object[] args = {build.artifactId(),build.pairId(),build.attemptNo(),build.token(),build.source().toString(),
                    build.staging().toString(),resource.filesystemKey(),resource.identity(),resource.root().toString(),
                    resource.capacityBytes(),resource.inodeLimit()};
            for (int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            try (var result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new StagingCleanup(result.getObject(1,UUID.class),result.getString(5),instant(result.getTimestamp(2)),
                        instant(result.getTimestamp(3)),instant(result.getTimestamp(4))));
            }
        }
    }

    /** The coordinator commits this identity before filesystem removal. */
    public static boolean claimStagingCleanup(Connection connection, BuildExecutionContext build,
                                              DedicatedStorageResource resource, UUID cleanup, String stagingIdentity) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("Staging cleanup claim requires an explicit transaction");
        return call(connection,"SELECT starexec.claim_solver_build_staging_cleanup(?,?,?,?,?,?)",
                build.artifactId(),build.token(),resource.filesystemKey(),resource.identity(),cleanup,stagingIdentity);
    }

    /** Call only after forced removal and positive absence under the retained identity locks. */
    public static boolean finishStagingCleanup(Connection connection, BuildExecutionContext build,
                                               DedicatedStorageResource resource, UUID cleanup, String stagingIdentity) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("Staging cleanup settlement requires an explicit transaction");
        return call(connection,"SELECT starexec.finish_solver_build_staging_cleanup(?,?,?,?,?,?)",
                build.artifactId(),build.token(),resource.filesystemKey(),resource.identity(),cleanup,stagingIdentity);
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public static boolean register(Connection connection, DedicatedStorageResource resource) throws SQLException {
        return transact(connection, tx -> registerInTransaction(tx, resource));
    }

    /** The caller owns the transaction and must roll it back if registration is refused. */
    public static boolean registerInTransaction(Connection connection, DedicatedStorageResource resource) throws SQLException, IOException {
        if (connection.getAutoCommit()) throw new SQLException("Resource registration requires an explicit transaction");
        resource.requireUnchanged();
        return call(connection, "SELECT starexec.register_execution_storage_resource(?,?,?,?,?)",
                resource.filesystemKey(), resource.root().toString(), resource.identity(),
                resource.capacityBytes(), resource.inodeLimit());
    }

    /** Commits an exclusive lease; closing a connection never returns that lease to the pool. */
    public static boolean reserve(Connection connection, BuildExecutionContext build,
                                  DedicatedStorageResource resource) throws SQLException {
        return transact(connection, tx -> reserveInTransaction(tx, build, resource));
    }

    /** Allows preparation to book its allowance and lease in one caller-owned transaction. */
    public static boolean reserveInTransaction(Connection connection, BuildExecutionContext build,
                                               DedicatedStorageResource resource) throws SQLException, IOException {
        if (connection.getAutoCommit()) throw new SQLException("Storage reservation requires an explicit transaction");
        resource.requireUnchanged();
        return call(connection, "SELECT starexec.reserve_solver_build_storage_lease(?,?,?,?,?,?,?,?)",
                build.artifactId(), build.pairId(), build.attemptNo(), build.token(),
                build.source().toString(), build.staging().toString(), resource.filesystemKey(), resource.identity());
    }

    /** Capacity is reobserved before committing both the lease and exact engine admission. */
    public static boolean admit(Connection connection, BuildExecutionContext build,
                                DedicatedStorageResource resource, String engine) throws SQLException {
        return transact(connection, tx -> {
            resource.requireUnchanged();
            return call(tx, "SELECT starexec.admit_leased_solver_build_execution(?,?,?,?,?,?,?,?,?)",
                    build.artifactId(), build.pairId(), build.attemptNo(), build.token(),
                    build.source().toString(), build.staging().toString(), resource.filesystemKey(), resource.identity(), engine);
        });
    }

    /** Requires settled registered cleanup and a positively empty reobserved resource. */
    public static boolean release(Connection connection, BuildExecutionContext build,
                                  DedicatedStorageResource resource) throws SQLException {
        return transact(connection, tx -> {
            resource.requireUnchanged();
            Path root = resource.root();
            try (DescriptorDirectory directory = DescriptorDirectory.open(root)) {
                try (var entries = Files.newDirectoryStream(root)) {
                    if (entries.iterator().hasNext()) throw new IOException("Execution storage is not empty after cleanup");
                }
                directory.force();
                resource.requireUnchanged();
                return call(tx, "SELECT starexec.release_solver_build_storage_lease(?,?,?,?)",
                        build.artifactId(), build.token(), resource.filesystemKey(), resource.identity());
            }
        });
    }

    private static boolean transact(Connection connection, Common.TransactionalWork<Boolean> work) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("Storage leases cannot use a foreign transaction");
        try {
            return Common.runTransactional(connection, tx -> {
                if (!work.run(tx)) throw new Refused();
                return true;
            });
        } catch (Refused refused) {
            if (refused.getSuppressed().length != 0) throw refused;
            return false;
        }
    }

    private static boolean call(Connection connection, String sql, Object... arguments) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) statement.setObject(i + 1, arguments[i]);
            try (var result = statement.executeQuery()) { return result.next() && result.getBoolean(1); }
        }
    }

    private static final class Refused extends SQLException {
        private static final long serialVersionUID = 1L;
        private Refused() { super("Execution storage lease was refused"); }
    }
}
