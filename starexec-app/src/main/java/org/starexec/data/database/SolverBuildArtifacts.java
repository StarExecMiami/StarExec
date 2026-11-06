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
            connection.setAutoCommit(false);
            try {
                boolean admitted = admitExecution(connection, build, engineIdentity);
                if (admitted) connection.commit(); else connection.rollback();
                return admitted;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); }
                catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    /** The caller must commit this transaction before starting the engine resource. */
    public static boolean admitExecution(Connection connection, BuildExecutionContext build,
                                         String engineIdentity) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("Build admission requires an explicit transaction");
        Artifact artifact = find(connection, build.pairId(), build.attemptNo()).orElse(null);
        if (artifact == null || artifact.id() != build.artifactId()
                || !build.token().equals(artifact.executionToken())
                || !build.staging().toString().equals(artifact.stagingPath())
                || !build.source().toString().equals(artifact.sourcePath())) return false;
        // The prepare routine locks job, pair, attempt, solver and artifact until commit.
        if (!prepare(connection, build.pairId(), build.attemptNo(), build.token(), build.staging())) return false;
        artifact = find(connection, build.pairId(), build.attemptNo()).orElseThrow();
        if (artifact.stoppedAt() != null) return false;
        return bindExecution(connection, build.pairId(), build.attemptNo(), build.token(), engineIdentity);
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
