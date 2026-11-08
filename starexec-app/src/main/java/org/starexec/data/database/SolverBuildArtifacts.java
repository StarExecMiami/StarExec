package org.starexec.data.database;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.io.IOException;
import org.starexec.backend.BuildExecutionContext;
import org.starexec.jobs.SolverBuildStaging;
import org.starexec.data.to.SolverBuildStopReceipt;

/** Registry access for trusted build dispatch, engine observation and publication. */
public final class SolverBuildArtifacts {
    public record Artifact(long id, int pairId, int attemptNo, int solverId, Integer liveSolverId,
                           int ownerId, long generation, String sourcePath, String stagingPath,
                           String storageRoot, String solverPath, UUID executionToken,
                           String engineIdentity, Instant stoppedAt, String state) { }

    private SolverBuildArtifacts() { }

    public record StoredStopReceipt(SolverBuildStopReceipt receipt, Instant recordedAt) { }

    public record OutputManifest(String payload, String sha256, long byteSize) { }

    public record Helper(int benchmarkId, int ownerId, String path, long byteSize, UUID token, Instant finishedAt) { }

    public static Optional<Helper> findHelper(Connection connection, int benchmark) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT owner_id,path,byte_size,cleanup_token,cleanup_finished_at FROM starexec.solver_build_helpers WHERE benchmark_id=?")) {
            statement.setInt(1, benchmark);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                Timestamp finished = result.getTimestamp(5);
                return Optional.of(new Helper(benchmark, result.getInt(1), result.getString(2), result.getLong(3),
                        result.getObject(4, UUID.class), finished == null ? null : finished.toInstant()));
            }
        }
    }

    public static boolean claimHelperCleanup(Connection connection, int benchmark, UUID token) throws SQLException {
        return call(connection, "SELECT starexec.claim_solver_build_helper_cleanup(?,?)", benchmark, token);
    }

    /** Trusted callers must confirm the captured file absent before requesting settlement. */
    public static boolean finishHelperCleanup(Connection connection, int benchmark, UUID token) throws SQLException {
        return call(connection, "SELECT starexec.finish_solver_build_helper_cleanup(?,?)", benchmark, token);
    }

    public record Cleanup(long artifactId, int pairId, int attemptNo, UUID executionToken,
                          String sourcePath, String stagingPath, String storageRoot, UUID token,
                          String removedSourcePath, long solverBytes, Instant finishedAt) { }

    /** The caller commits this durable claim before removing any registered files. */
    public static boolean claimCleanup(Connection connection, long artifact, UUID token) throws SQLException {
        return call(connection, "SELECT starexec.claim_solver_build_cleanup(?,?)", artifact, token);
    }

    public static Optional<Cleanup> findCleanup(Connection connection, long artifact) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT origin_pair_id,attempt_no,execution_token,source_path,staging_path,storage_root,"
                + "cleanup_token,cleanup_source_path,cleanup_solver_bytes,cleanup_finished_at"
                + " FROM starexec.solver_build_artifacts WHERE id=?")) {
            statement.setLong(1, artifact);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                Timestamp finished = result.getTimestamp(10);
                return Optional.of(new Cleanup(artifact, result.getInt(1), result.getInt(2),
                        result.getObject(3, UUID.class), result.getString(4), result.getString(5), result.getString(6),
                        result.getObject(7, UUID.class), result.getString(8), result.getLong(9),
                        finished == null ? null : finished.toInstant()));
            }
        }
    }

    /** Call only after every captured filesystem path is positively confirmed absent. */
    public static boolean finishCleanup(Connection connection, long artifact, UUID token) throws SQLException {
        return call(connection, "SELECT starexec.finish_solver_build_cleanup(?,?)", artifact, token);
    }

    /** Reserves immutable output without authorizing publication or replacing an earlier destination. */
    public static boolean reserveOutput(Connection connection, BuildExecutionContext build, String engine,
                                         String receiptDigest, Path root, long maxBytes) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("Output byte reservation must own its transaction");
        try {
            return Common.runTransactional(connection, transaction -> {
                if (!reserveStorageBytes(transaction, build, "OUTPUT", root, maxBytes)
                        || !reserveOutputPath(transaction, build, engine, receiptDigest, root)) {
                    throw new StorageReservationRefused();
                }
                return true;
            });
        } catch (StorageReservationRefused refused) {
            if (refused.getSuppressed().length != 0) throw refused;
            return false;
        }
    }

    /** The caller commits the reservation before enabling any writes to the registered path. */
    public static boolean reserveStorageBytes(Connection connection, BuildExecutionContext build, String kind,
                                               Path path, long maxBytes) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("Byte reservation requires an explicit transaction");
        if (path == null || maxBytes < 0) throw new IllegalArgumentException("Invalid build storage budget");
        return call(connection, "SELECT starexec.reserve_solver_build_storage_bytes(?,?,?,?,?)",
                build.artifactId(), build.token(), kind, path.toString(), maxBytes);
    }

    private static final class StorageReservationRefused extends SQLException {
        private static final long serialVersionUID = 1L;
        private StorageReservationRefused() { super("Build storage reservation was refused"); }
    }

    private static boolean reserveOutputPath(Connection connection, BuildExecutionContext build, String engine,
                                              String receiptDigest, Path root) throws SQLException {
        String sql = "UPDATE starexec.solver_build_artifacts SET state='STAGED',storage_root=?,artifact_path=?"
                + " WHERE id=? AND origin_pair_id=? AND attempt_no=? AND execution_token=?"
                + " AND engine_identity=? AND execution_stopped_at IS NOT NULL AND execution_stop_receipt_sha256=?"
                + " AND state IN ('PLANNED','STAGED')"
                + " AND (storage_root IS NULL AND artifact_path IS NULL OR storage_root=? AND artifact_path=?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, root.toString()); statement.setString(2, root.resolve("solver").toString());
            statement.setLong(3, build.artifactId()); statement.setInt(4, build.pairId());
            statement.setInt(5, build.attemptNo()); statement.setObject(6, build.token());
            statement.setString(7, engine); statement.setString(8, receiptDigest);
            statement.setString(9, root.toString()); statement.setString(10, root.resolve("solver").toString());
            return statement.executeUpdate() == 1;
        }
    }

    /** Conserves exact verified bytes; publication still performs the current-owner checks. */
    public static boolean validateOutput(Connection connection, BuildExecutionContext build, String engine,
                                          String receiptDigest, Path root, OutputManifest manifest) throws SQLException {
        return storeVerifiedOutput(connection, build, engine, receiptDigest, root, manifest, "VALIDATED");
    }

    /** Freezes log bytes without making failed output eligible for solver publication. */
    public static boolean validateFailureLogs(Connection connection, BuildExecutionContext build, String engine,
                                               String receiptDigest, Path root, OutputManifest manifest) throws SQLException {
        return storeVerifiedOutput(connection, build, engine, receiptDigest, root, manifest, "STAGED");
    }

    private static boolean storeVerifiedOutput(Connection connection, BuildExecutionContext build, String engine,
                                                String receiptDigest, Path root, OutputManifest manifest, String state) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("Output verification must own its transaction");
        try {
            return Common.runTransactional(connection, transaction -> {
                // Keep the accounting owner ahead of the artifact in the lock order.
                try (PreparedStatement lock = transaction.prepareStatement("SELECT id FROM starexec.users WHERE id="
                        + "(SELECT origin_owner_id FROM starexec.solver_build_artifacts WHERE id=?) FOR UPDATE NOWAIT")) {
                    lock.setLong(1, build.artifactId());
                    try (ResultSet result = lock.executeQuery()) { if (!result.next()) throw new StorageReservationRefused(); }
                }
                if (!storeOutputManifest(transaction, build, engine, receiptDigest, root, manifest, state)
                        || !call(transaction, "SELECT starexec.reconcile_solver_build_output_bytes(?)", build.artifactId())) {
                    throw new StorageReservationRefused();
                }
                return true;
            });
        } catch (StorageReservationRefused refused) {
            if (refused.getSuppressed().length != 0) throw refused;
            return false;
        }
    }

    private static boolean storeOutputManifest(Connection connection, BuildExecutionContext build, String engine,
                                                String receiptDigest, Path root, OutputManifest manifest, String state) throws SQLException {
        String sql = "UPDATE starexec.solver_build_artifacts SET state=?,byte_size=?,"
                + "manifest_payload=?,manifest_sha256=?,validated_at=clock_timestamp()"
                + " WHERE id=? AND origin_pair_id=? AND attempt_no=? AND execution_token=?"
                + " AND engine_identity=? AND execution_stopped_at IS NOT NULL AND execution_stop_receipt_sha256=?"
                + " AND state='STAGED' AND validated_at IS NULL AND storage_root=? AND artifact_path=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state); statement.setLong(2, manifest.byteSize()); statement.setString(3, manifest.payload());
            statement.setString(4, manifest.sha256()); statement.setLong(5, build.artifactId());
            statement.setInt(6, build.pairId()); statement.setInt(7, build.attemptNo());
            statement.setObject(8, build.token()); statement.setString(9, engine);
            statement.setString(10, receiptDigest); statement.setString(11, root.toString());
            statement.setString(12, root.resolve("solver").toString());
            return statement.executeUpdate() == 1;
        }
    }

    public static Optional<OutputManifest> findOutputManifest(Connection connection, long artifact) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT manifest_payload,manifest_sha256,byte_size"
                + " FROM starexec.solver_build_artifacts WHERE id=? AND validated_at IS NOT NULL")) {
            statement.setLong(1, artifact);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new OutputManifest(result.getString(1), result.getString(2), result.getLong(3)));
            }
        }
    }

    public static boolean publishOutput(Connection connection, long artifact) throws SQLException {
        return call(connection, "SELECT starexec.publish_solver_build_output(?)", artifact);
    }

    public static boolean recordFailedOutput(Connection connection, BuildExecutionContext build, String engine,
                                             String receiptDigest) throws SQLException {
        return call(connection, "SELECT starexec.record_failed_solver_build_output(?,?,?,?)",
                build.artifactId(), build.token(), engine, receiptDigest);
    }

    public static Optional<StoredStopReceipt> findStopReceipt(int pairId, int attemptNo) throws SQLException {
        try (Connection connection = Common.getConnection()) { return findStopReceipt(connection, pairId, attemptNo); }
    }

    public static Optional<StoredStopReceipt> findStopReceipt(Connection connection, int pairId, int attemptNo)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT execution_stop_receipt_payload,execution_stop_receipt_sha256,execution_stop_receipt_recorded_at"
                + " FROM starexec.solver_build_artifacts WHERE origin_pair_id=? AND attempt_no=?")) {
            statement.setInt(1, pairId); statement.setInt(2, attemptNo);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getString(1) == null) return Optional.empty();
                try {
                    return Optional.of(new StoredStopReceipt(SolverBuildStopReceipt.restored(result.getString(1),
                            result.getString(2)), result.getTimestamp(3).toInstant()));
                } catch (IllegalArgumentException invalid) {
                    throw new SQLException("Stored build stop receipt is invalid", invalid);
                }
            }
        }
    }

    public static boolean recordStopReceipt(int pairId, int attemptNo, UUID token, String engine,
                                            SolverBuildStopReceipt receipt) throws SQLException {
        try (Connection connection = Common.getConnection()) {
            return Common.runTransactional(connection,
                    transaction -> recordStopReceipt(transaction, pairId, attemptNo, token, engine, receipt));
        }
    }

    public static boolean recordStopReceipt(Connection connection, int pairId, int attemptNo, UUID token,
                                            String engine, SolverBuildStopReceipt receipt) throws SQLException {
        return call(connection, "SELECT starexec.record_solver_build_stop_receipt(?,?,?,?,?,?)",
                pairId, attemptNo, token, engine, receipt.payload(), receipt.sha256());
    }

    /** Call only after the provider has fenced launch of an execution that never started. */
    public static boolean recordUnstarted(int pairId, int attemptNo, UUID token, String engine,
                                          SolverBuildStopReceipt receipt) throws SQLException {
        try (Connection connection = Common.getConnection()) {
            return Common.runTransactional(connection,
                    transaction -> recordUnstarted(transaction, pairId, attemptNo, token, engine, receipt));
        }
    }

    /** Atomically rejects the output and records non-execution; the caller owns the commit. */
    public static boolean recordUnstarted(Connection connection, int pairId, int attemptNo, UUID token,
                                          String engine, SolverBuildStopReceipt receipt) throws SQLException {
        return call(connection, "SELECT starexec.record_solver_build_unstarted(?,?,?,?,?,?)",
                pairId, attemptNo, token, engine, receipt.payload(), receipt.sha256());
    }

    /** Call only after the trusted observer establishes physical termination following cancellation. */
    public static boolean recordCancelled(int pairId, int attemptNo, UUID token, String engine,
                                          SolverBuildStopReceipt receipt) throws SQLException {
        try (Connection connection = Common.getConnection()) {
            return Common.runTransactional(connection,
                    transaction -> recordCancelled(transaction, pairId, attemptNo, token, engine, receipt));
        }
    }

    /** Rejects cancelled output and conserves its stop proof; the caller owns the commit. */
    public static boolean recordCancelled(Connection connection, int pairId, int attemptNo, UUID token,
                                          String engine, SolverBuildStopReceipt receipt) throws SQLException {
        return call(connection, "SELECT starexec.record_solver_build_cancelled(?,?,?,?,?,?)",
                pairId, attemptNo, token, engine, receipt.payload(), receipt.sha256());
    }

    public static BuildExecutionContext prepareStaging(int pairId, int attemptNo, Path parent)
            throws SQLException, IOException {
        try (Connection con = Common.getConnection()) {
            return SolverBuildStaging.prepare(con, pairId, attemptNo, parent);
        }
    }

    public static Optional<Artifact> find(int pairId, int attemptNo) throws SQLException {
        try (Connection con = Common.getConnection()) { return find(con, pairId, attemptNo); }
    }

    /** The current attempt can change after this read; any mutation must fence it again. */
    public static Optional<Artifact> findCurrent(int pairId) throws SQLException {
        try (Connection connection = Common.getConnection()) { return findCurrent(connection, pairId); }
    }

    public static Optional<Artifact> findCurrent(Connection connection, int pairId) throws SQLException {
        return find(connection, pairId, JobPairs.getCurrentAttemptNo(connection, pairId));
    }

    /** Compatibility preflight only; absence does not authorize a concurrent execution. */
    public static boolean hasManagedHistory(int pairId) throws SQLException {
        try (Connection connection = Common.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT EXISTS (SELECT 1 FROM starexec.solver_build_artifacts WHERE origin_pair_id=?"
                     + " AND (execution_token IS NOT NULL OR staging_path IS NOT NULL OR engine_identity IS NOT NULL"
                     + " OR execution_stopped_at IS NOT NULL OR storage_root IS NOT NULL OR artifact_path IS NOT NULL))")) {
            statement.setInt(1, pairId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Build history observation is unavailable");
                return result.getBoolean(1);
            }
        }
    }

    /** Historical lookup remains possible after the live pair or solver is deleted. */
    public static Optional<Artifact> find(Connection con, int pairId, int attemptNo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM starexec.solver_build_artifacts WHERE origin_pair_id=? AND attempt_no=?")) {
            ps.setInt(1, pairId); ps.setInt(2, attemptNo);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                int solver = rs.getInt("solver_id");
                Integer liveSolver = rs.wasNull() ? null : solver;
                Timestamp stopped = rs.getTimestamp("execution_stopped_at");
                return Optional.of(new Artifact(rs.getLong("id"), rs.getInt("origin_pair_id"),
                        rs.getInt("attempt_no"), rs.getInt("origin_solver_id"), liveSolver,
                        rs.getInt("origin_owner_id"), rs.getLong("build_generation"),
                        rs.getString("source_path"), rs.getString("staging_path"), rs.getString("storage_root"),
                        rs.getString("artifact_path"), rs.getObject("execution_token", UUID.class),
                        rs.getString("engine_identity"), stopped == null ? null : stopped.toInstant(), rs.getString("state")));
            }
        }
    }

    public static boolean prepare(int pairId, int attemptNo, UUID token, Path staging) throws SQLException {
        try (Connection con = Common.getConnection()) { return prepare(con, pairId, attemptNo, token, staging); }
    }

    /** Allocating and protecting the directory is the dispatcher's responsibility. */
    public static boolean prepare(Connection con, int pairId, int attemptNo, UUID token, Path staging) throws SQLException {
        if (staging == null || !staging.isAbsolute() || staging.getParent() == null) {
            throw new IllegalArgumentException("Build staging must be an absolute non-root directory");
        }
        return call(con, "SELECT starexec.prepare_solver_build_storage(?,?,?,?)",
                pairId, attemptNo, token, staging.toString());
    }

    public static boolean bindExecution(int pairId, int attemptNo, UUID token, String engineIdentity) throws SQLException {
        try (Connection con = Common.getConnection()) {
            return bindExecution(con, pairId, attemptNo, token, engineIdentity);
        }
    }

    /** Revalidates the prepared attempt and commits its engine identity before launch. */
    public static boolean admitExecution(BuildExecutionContext build, String engineIdentity) throws SQLException {
        try (Connection connection = Common.getConnection()) {
            try {
                return Common.runTransactional(connection, transaction -> {
                    if (!admitExecution(transaction, build, engineIdentity)) throw new AdmissionRefused();
                    return true;
                });
            } catch (AdmissionRefused refused) {
                if (refused.getSuppressed().length != 0) throw refused;
                return false;
            }
        }
    }

    /** Rolls back refused admission without hiding rollback or connection-restoration failures. */
    private static final class AdmissionRefused extends SQLException {
        private static final long serialVersionUID = 1L;

        private AdmissionRefused() { super("Build execution admission was refused"); }
    }

    /** The caller must commit this transaction before starting the engine resource. */
    public static boolean admitExecution(Connection connection, BuildExecutionContext build,
                                         String engineIdentity) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("Build admission requires an explicit transaction");
        return call(connection, "SELECT starexec.admit_solver_build_execution(?,?,?,?,?,?,?)",
                build.artifactId(), build.pairId(), build.attemptNo(), build.token(),
                build.source().toString(), build.staging().toString(), engineIdentity);
    }

    public static boolean bindExecution(Connection con, int pairId, int attemptNo, UUID token,
                                        String engineIdentity) throws SQLException {
        return call(con, "SELECT starexec.bind_solver_build_execution(?,?,?,?)",
                pairId, attemptNo, token, engineIdentity);
    }

    /** Call only after observing the registered engine resource terminate, not from status.json. */
    public static boolean recordStopped(int pairId, int attemptNo, UUID token, String engineIdentity) throws SQLException {
        try (Connection con = Common.getConnection()) {
            return recordStopped(con, pairId, attemptNo, token, engineIdentity);
        }
    }

    public static boolean recordStopped(Connection con, int pairId, int attemptNo, UUID token,
                                         String engineIdentity) throws SQLException {
        return call(con, "SELECT starexec.record_solver_build_execution_stop(?,?,?,?)",
                pairId, attemptNo, token, engineIdentity);
    }

    private static boolean call(Connection con, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getBoolean(1); }
        }
    }
}
