package org.starexec.data.database;

import com.google.gson.Gson;
import org.starexec.backend.BuildExecutionContext;
import org.starexec.backend.storage.BuildExecutionRuntime;
import org.starexec.backend.storage.BuildExecutionMounts;
import org.starexec.backend.storage.BuildArtifactStore;
import org.starexec.jobs.SolverBuildPublication;
import org.starexec.jobs.SolverBuildStagingCleanup;
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
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

/** Durable resource exclusivity; capacity and physical cleanup remain independently observed. */
public final class ExecutionStorageLeases {
    private static final Gson JSON = new Gson();
    private ExecutionStorageLeases() { }

    public static List<Path> registeredRootPaths() throws SQLException {
        try (Connection connection=Common.getConnection();var query=connection.prepareStatement(
                "SELECT root_path FROM starexec.execution_storage_resources ORDER BY resource_key")) {
            List<Path> paths=new ArrayList<>();
            try (var rows=query.executeQuery()) { while (rows.next()) paths.add(Path.of(rows.getString(1))); }
            return List.copyOf(paths);
        }
    }

    /** Conserves a released stopped outcome before independently settling its staging lease. */
    public static boolean recordBuildOutcomeAndClean(SolverBuildArtifacts.Artifact expected, BuildArtifactStore store,
                                                     List<Path> protectedRoots) throws SQLException, IOException {
        try (Connection connection=Common.getConnection()) {
            var current=SolverBuildArtifacts.find(connection,expected.pairId(),expected.attemptNo()).orElse(null);
            if (!expected.equals(current) || current.executionToken()==null || current.stoppedAt()==null
                    || current.sourcePath()==null || current.stagingPath()==null || current.engineIdentity()==null) return false;
            var build=new BuildExecutionContext(current.id(),current.pairId(),current.attemptNo(),current.executionToken(),
                    Path.of(current.sourcePath()),Path.of(current.stagingPath()));
            var registration=registeredResource(connection,build).orElse(null);
            if (registration==null) return false;
            var excluded=new ArrayList<>(protectedRoots);excluded.add(store.root());
            var resource=registration.inspect(excluded);
            int entries=(int)Math.min(Integer.MAX_VALUE,resource.inodeLimit());
            var stopped=SolverBuildArtifacts.findStopReceipt(connection,build.pairId(),build.attemptNo()).orElse(null);
            if (stopped==null || !stopped.receipt().matches(build.artifactId(),build.pairId(),build.attemptNo(),current.engineIdentity())) return false;
            var disposition=stopped.receipt().facts().get("disposition");
            if ("DELETED".equals(current.state()) || "REJECTED".equals(current.state()) && disposition!=null
                    && java.util.Set.of("cancelled","never_started").contains(disposition.getAsString())) {
                return SolverBuildStagingCleanup.purge(connection,build,resource,entries);
            }
            if (disposition!=null) return false;
            var release=findReleaseDecision(connection,build,resource).orElse(null);
            if (release==null || !current.engineIdentity().equals(release.engineIdentity())) return false;
            boolean success;
            try (var query=connection.prepareStatement("SELECT status_code=7,starexec.IsTerminalPairStatus(status_code)"
                    +" AND status_code NOT IN (7,21) FROM starexec.job_pairs WHERE id=?")) {
                query.setInt(1,build.pairId());
                try (var row=query.executeQuery()) {
                    if (!row.next() || !row.getBoolean(1) && !row.getBoolean(2)) return false;
                    success=row.getBoolean(1);
                }
            }
            store.requireUnchanged();
            boolean conserved=success?SolverBuildPublication.publish(connection,build,store.root(),resource.capacityBytes(),entries)
                    :SolverBuildPublication.recordFailure(connection,build,store.root(),resource.capacityBytes(),entries);
            if (!conserved) return false;
            store.requireUnchanged();
            return SolverBuildStagingCleanup.purge(connection,build,resource,entries);
        }
    }

    /** Owns the standalone connection; preparation commits its reservation before writing files. */
    public static BuildExecutionContext prepareRegistered(int pairId, List<Path> protectedRoots)
            throws SQLException, IOException {
        try (Connection connection = Common.getConnection()) {
            return org.starexec.jobs.SolverBuildStaging.prepareRegistered(connection,pairId,protectedRoots);
        }
    }

    /** Materializes private controls only after verifying this conserved active lease. */
    public static BuildExecutionMounts prepareMounts(BuildExecutionContext build, DedicatedStorageResource resource,
                                                    BuildExecutionMounts.Controls controls) throws SQLException, IOException {
        try (Connection connection = Common.getConnection()) {
            return BuildExecutionMounts.prepare(connection,build,resource,controls,resource.capacityBytes(),
                    (int)Math.min(Integer.MAX_VALUE,resource.inodeLimit()));
        }
    }

    public static Optional<RegisteredResource> registeredResource(BuildExecutionContext build) throws SQLException {
        try (Connection connection = Common.getConnection()) { return registeredResource(connection,build); }
    }

    /** Publish cancellation's execution locator in the same transaction as engine admission. */
    public static boolean admitPodman(BuildExecutionContext build, DedicatedStorageResource resource,
                                      int execution, String container) throws SQLException {
        try (Connection connection = Common.getConnection()) {
            return admitPodman(connection,build,resource,execution,container);
        }
    }

    public static boolean admitPodman(Connection connection, BuildExecutionContext build,
                                      DedicatedStorageResource resource, int execution, String container) throws SQLException {
        if (execution<1 || container==null || container.isBlank() || container.contains(":")) return false;
        String engine="podman:"+execution+":"+container;
        return transact(connection,transaction -> {
            resource.requireUnchanged();
            if (!call(transaction,"SELECT starexec.admit_leased_solver_build_execution(?,?,?,?,?,?,?,?,?)",
                    build.artifactId(),build.pairId(),build.attemptNo(),build.token(),build.source().toString(),
                    build.staging().toString(),resource.filesystemKey(),resource.identity(),engine)) return false;
            // Admission retains job, pair and current-attempt locks until this locator commits.
            try (var update=transaction.prepareStatement("UPDATE starexec.job_pairs p SET sge_id=?"
                    + " FROM starexec.jobs j,starexec.job_pair_attempts a"
                    + " WHERE p.id=? AND j.id=p.job_id AND a.pair_id=p.id AND a.current_attempt_no=?"
                    + " AND p.status_code=? AND j.paused=FALSE AND j.killed=FALSE AND j.deleted=FALSE")) {
                update.setInt(1,execution);update.setInt(2,build.pairId());update.setInt(3,build.attemptNo());
                update.setInt(4,org.starexec.data.to.Status.StatusCode.STATUS_ENQUEUED.getVal());
                if (update.executeUpdate()!=1) return false;
            }
            resource.requireUnchanged();
            return true;
        });
    }

    /** A paused, cancelled, completed or superseded pair cannot authorize a payload release. */
    public static boolean claimPodmanRelease(BuildExecutionContext build, DedicatedStorageResource resource,
                                             int execution, String container, UUID request,
                                             BuildExecutionRuntime.Observation observation) throws SQLException {
        if (execution<1 || container==null || container.isBlank() || container.contains(":")
                || request==null || observation==null) return false;
        try (Connection connection=Common.getConnection()) {
            return claimPodmanRelease(connection,build,resource,execution,container,request,observation);
        }
    }

    public static boolean claimPodmanRelease(Connection connection, BuildExecutionContext build,
                                             DedicatedStorageResource resource, int execution, String container,
                                             UUID request, BuildExecutionRuntime.Observation observation) throws SQLException {
        if (execution<1 || container==null || container.isBlank() || container.contains(":")
                || request==null || observation==null) return false;
        return transact(connection,transaction -> {
                resource.requireUnchanged();
                String engine="podman:"+execution+":"+container;
                if (!call(transaction,"SELECT starexec.claim_solver_build_release(?,?,?,?,?,?,?,?,?,?,?)",
                        build.artifactId(),build.pairId(),build.attemptNo(),build.token(),
                        build.source().toString(),build.staging().toString(),resource.filesystemKey(),resource.identity(),
                        engine,request,JSON.toJson(Map.of("version",1,"observation",observation)))) return false;
                try (var check=transaction.prepareStatement("SELECT 1 FROM starexec.job_pairs p"
                        + " JOIN starexec.jobs j ON j.id=p.job_id"
                        + " JOIN starexec.job_pair_attempts a ON a.pair_id=p.id"
                        + " WHERE p.id=? AND p.sge_id=? AND a.current_attempt_no=? AND p.status_code IN (?,?)"
                        + " AND j.paused=FALSE AND j.killed=FALSE AND j.deleted=FALSE")) {
                    check.setInt(1,build.pairId());check.setInt(2,execution);check.setInt(3,build.attemptNo());
                    check.setInt(4,org.starexec.data.to.Status.StatusCode.STATUS_ENQUEUED.getVal());
                    check.setInt(5,org.starexec.data.to.Status.StatusCode.STATUS_RUNNING.getVal());
                    try (var rows=check.executeQuery()) { if (!rows.next()) return false; }
                }
                resource.requireUnchanged();
                return true;
        });
    }

    public record StagingCleanup(UUID token, String stagingIdentity, Instant startedAt, Instant finishedAt, Instant releasedAt) { }

    /** Registration is not a physical observation or permission to acquire a lease. */
    public record RegisteredResource(String key, Path root, String identity, long bytes, long inodes) {
        public DedicatedStorageResource inspect(List<Path> protectedRoots) throws IOException {
            var observed = DedicatedStorageResource.inspect(root,
                    new DedicatedStorageResource.Limits(bytes, inodes), protectedRoots);
            if (!key.equals(observed.filesystemKey()) || !identity.equals(observed.identity())
                    || bytes != observed.capacityBytes() || inodes != observed.inodeLimit()) {
                throw new IOException("Registered execution resource differs from its physical observation");
            }
            return observed;
        }
    }

    /** Candidates only; authoritative acquisition rechecks ownership, quota and exclusivity. */
    public static List<RegisteredResource> availableResources(Connection connection, int owner) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT r.resource_key,r.root_path,r.observed_identity,r.capacity_bytes,r.inode_limit"
                + " FROM starexec.execution_storage_resources r JOIN starexec.users u ON u.id=?"
                + " WHERE r.capacity_bytes<=CASE WHEN u.disk_size>=0 AND u.disk_quota>=u.disk_size"
                + " THEN u.disk_quota-u.disk_size END"
                + " AND NOT EXISTS (SELECT 1 FROM starexec.solver_build_storage_leases l"
                + " WHERE l.resource_key=r.resource_key AND l.released_at IS NULL)"
                + " ORDER BY r.capacity_bytes,r.resource_key")) {
            statement.setInt(1, owner);
            try (var rows = statement.executeQuery()) {
                List<RegisteredResource> result = new ArrayList<>();
                while (rows.next()) result.add(registered(rows));
                return List.copyOf(result);
            }
        }
    }

    /** Conserved locator only; callers must independently check current ownership and physical state. */
    public static Optional<RegisteredResource> registeredResource(Connection connection, BuildExecutionContext build)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT r.resource_key,r.root_path,r.observed_identity,r.capacity_bytes,r.inode_limit"
                + " FROM starexec.execution_storage_resources r"
                + " JOIN starexec.solver_build_storage_leases l ON l.resource_key=r.resource_key"
                + " JOIN starexec.solver_build_artifacts a ON a.id=l.artifact_id"
                + " WHERE a.id=? AND a.origin_pair_id=? AND a.attempt_no=? AND a.execution_token=?"
                + " AND a.source_path=? AND a.staging_path=? AND l.execution_token=a.execution_token")) {
            Object[] arguments = {build.artifactId(),build.pairId(),build.attemptNo(),build.token(),
                    build.source().toString(),build.staging().toString()};
            for (int i=0;i<arguments.length;i++) statement.setObject(i+1,arguments[i]);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(registered(rows)) : Optional.empty();
            }
        }
    }

    private static RegisteredResource registered(java.sql.ResultSet rows) throws SQLException {
        return new RegisteredResource(rows.getString(1),Path.of(rows.getString(2)),rows.getString(3),
                rows.getLong(4),rows.getLong(5));
    }

    /** Input identity is a snapshot; engine admission must recheck the current execution. */
    public record DispatchInputs(int ownerId, long generation, Path source, SolverBuildArtifacts.Helper helper) { }

    public record ReleaseDecision(UUID requestToken, String engineIdentity, String runtimePayload,
                                  String runtimeSha256, Instant requestedAt) { }

    public static Optional<ReleaseDecision> findReleaseDecision(BuildExecutionContext build,
                                                                DedicatedStorageResource resource) throws SQLException {
        try (Connection connection=Common.getConnection()) { return findReleaseDecision(connection,build,resource); }
    }

    public static Optional<StagingCleanup> findStagingCleanup(BuildExecutionContext build,
                                                             DedicatedStorageResource resource) throws SQLException {
        try (Connection connection=Common.getConnection()) { return findStagingCleanup(connection,build,resource); }
    }

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
