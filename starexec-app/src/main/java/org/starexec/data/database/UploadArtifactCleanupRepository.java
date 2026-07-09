package org.starexec.data.database;

import org.starexec.data.to.UploadArtifact;
import org.starexec.logger.StarLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Database access for upload artifact cleanup.
 */
public class UploadArtifactCleanupRepository {
    private static final StarLogger log = StarLogger.getLogger(UploadArtifactCleanupRepository.class);
    private static final long ADVISORY_LOCK_KEY = 0x57504c4f41445550L; // WPLOADUP

    private static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_lock(?)";
    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(?)";

    private static final String INSERT_SOURCE_ARCHIVE_SQL =
        "INSERT INTO starexec.upload_artifacts (session_id, job_id, artifact_role, path_kind, path, " +
        "cleanup_state, retention_until) VALUES (?, ?, 'SOURCE_ARCHIVE', 'FILE', ?, 'RETAINED', NULL)";

    private static final String CLAIM_DUE_ARTIFACTS_SQL =
        "WITH candidates AS (" +
        "  SELECT ua.id FROM starexec.upload_artifacts ua " +
        "  LEFT JOIN starexec.upload_jobs uj ON uj.id = ua.job_id " +
        "  LEFT JOIN starexec.upload_sessions us ON us.id = ua.session_id " +
        "  WHERE ua.deleted_at IS NULL " +
        "    AND (ua.cleanup_state IN ('RETAINED', 'READY', 'DELETE_FAILED', 'MISSING') " +
        "         OR (ua.cleanup_state = 'DELETING' " +
        "             AND ua.last_delete_attempt_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes') " +
        "         OR (ua.cleanup_state = 'BLOCKED_REFERENCED' " +
        "             AND ua.last_delete_attempt_at < CURRENT_TIMESTAMP - INTERVAL '24 hours')) " +
        "    AND ua.retention_until IS NOT NULL " +
        "    AND ua.retention_until <= CURRENT_TIMESTAMP " +
        "    AND (ua.job_id IS NULL OR uj.status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED')) " +
        "    AND (ua.session_id IS NULL OR us.status IN ('COMPLETE', 'ABORTED', 'EXPIRED', 'FAILED')) " +
        "  ORDER BY ua.retention_until ASC, ua.id ASC " +
        "  LIMIT ? FOR UPDATE OF ua SKIP LOCKED" +
        ") " +
        "UPDATE starexec.upload_artifacts ua " +
        "SET cleanup_state = 'DELETING', last_delete_attempt_at = CURRENT_TIMESTAMP, " +
        "    delete_attempt_count = delete_attempt_count + 1, updated_at = CURRENT_TIMESTAMP " +
        "FROM candidates WHERE ua.id = candidates.id RETURNING ua.*";

    private static final String MARK_DELETED_SQL =
        "UPDATE starexec.upload_artifacts SET cleanup_state = ?, deleted_at = CURRENT_TIMESTAMP, " +
        "last_delete_error = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?";

    private static final String MARK_SOURCE_ARCHIVE_DELETED_SQL =
        "UPDATE starexec.upload_artifacts SET cleanup_state = ?, deleted_at = CURRENT_TIMESTAMP, " +
        "last_delete_error = NULL, updated_at = CURRENT_TIMESTAMP " +
        "WHERE job_id = ? AND artifact_role = 'SOURCE_ARCHIVE' AND deleted_at IS NULL";

    private static final String MARK_FAILED_SQL =
        "UPDATE starexec.upload_artifacts SET cleanup_state = ?, last_delete_error = ?, " +
        "updated_at = CURRENT_TIMESTAMP WHERE id = ?";

    private static final String ACTIVATE_SOURCE_ARCHIVE_RETENTION_SQL =
        "UPDATE starexec.upload_artifacts " +
        "SET retention_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 hour'), " +
        "    owner_terminal_at = CURRENT_TIMESTAMP, cleanup_state = 'RETAINED', updated_at = CURRENT_TIMESTAMP " +
        "WHERE job_id = ? AND artifact_role = 'SOURCE_ARCHIVE' " +
        "  AND deleted_at IS NULL AND cleanup_state NOT IN ('DELETED', 'MISSING')";

    private static final String ACTIVATE_TERMINAL_SOURCE_ARCHIVES_SQL =
        "UPDATE starexec.upload_artifacts ua " +
        "SET retention_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 hour'), " +
        "    owner_terminal_at = CURRENT_TIMESTAMP, cleanup_state = 'RETAINED', updated_at = CURRENT_TIMESTAMP " +
        "FROM starexec.upload_jobs uj " +
        "WHERE ua.job_id = uj.id AND ua.artifact_role = 'SOURCE_ARCHIVE' " +
        "  AND ua.deleted_at IS NULL AND ua.cleanup_state NOT IN ('DELETED', 'MISSING') " +
        "  AND ua.retention_until IS NULL " +
        "  AND uj.status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED')";

    private static final String RESET_SOURCE_ARCHIVE_RETENTION_SQL =
        "UPDATE starexec.upload_artifacts " +
        "SET retention_until = NULL, owner_terminal_at = NULL, cleanup_state = 'RETAINED', " +
        "    last_delete_error = NULL, updated_at = CURRENT_TIMESTAMP " +
        "WHERE job_id = ? AND artifact_role = 'SOURCE_ARCHIVE' AND deleted_at IS NULL";

    private static final String FIND_RETRYABLE_SOURCE_ARCHIVE_SQL =
        "SELECT * FROM starexec.upload_artifacts " +
        "WHERE job_id = ? AND artifact_role = 'SOURCE_ARCHIVE' " +
        "  AND deleted_at IS NULL " +
        "  AND cleanup_state IN ('RETAINED', 'READY', 'DELETE_FAILED') " +
        "  AND retention_until > CURRENT_TIMESTAMP " +
        "ORDER BY id DESC LIMIT 1";

    private static final String HAS_BLOCKING_REFERENCES_SQL =
        "SELECT EXISTS (" +
        "  SELECT 1 FROM starexec.benchmarks b " +
        "  WHERE b.deleted = FALSE AND b.recycled = FALSE " +
        "    AND (b.path = ? OR b.path LIKE ? ESCAPE '\\')" +
        ") OR EXISTS (" +
        "  SELECT 1 FROM starexec.upload_jobs uj " +
        "  WHERE uj.id <> COALESCE(?, -1) " +
        "    AND uj.status IN ('PENDING', 'PROCESSING', 'FAILED', 'CANCELLED') " +
        "    AND (uj.archive_path = ? OR uj.extract_path = ? " +
        "         OR uj.archive_path LIKE ? ESCAPE '\\' OR uj.extract_path LIKE ? ESCAPE '\\')" +
        ") OR EXISTS (" +
        "  SELECT 1 FROM starexec.upload_sessions us " +
        "  WHERE us.id <> COALESCE(?, -1) " +
        "    AND us.status IN ('UPLOADING', 'READY', 'FINALIZING', 'COMPLETE') " +
        "    AND (us.staging_path = ? OR us.staging_path LIKE ? ESCAPE '\\')" +
        ")";

    private static final String SOURCE_ARCHIVE_OWNED_BY_JOB_SQL =
        "SELECT EXISTS (" +
        "  SELECT 1 FROM starexec.upload_jobs uj " +
        "  WHERE uj.id = ? AND uj.archive_path = ? " +
        "    AND ((? IS NULL AND uj.upload_session_id IS NULL) OR uj.upload_session_id = ?)" +
        ")";

    private static final String JOB_DIRECTORY_OWNED_BY_JOB_SQL =
        "SELECT EXISTS (" +
        "  SELECT 1 FROM starexec.upload_jobs uj " +
        "  WHERE uj.id = ? AND uj.extract_path = ?" +
        ")";

    private static final String SESSION_ARTIFACT_OWNED_BY_SESSION_SQL =
        "SELECT EXISTS (" +
        "  SELECT 1 FROM starexec.upload_sessions us " +
        "  WHERE us.id = ? AND (" +
        "    (? = 'CHUNK_DIRECTORY' AND us.staging_path || '.chunks' = ?) OR " +
        "    (? = 'ASSEMBLING_FILE' AND us.staging_path || '.assembling' = ?)" +
        "  )" +
        ")";

    public interface CleanupDatabaseWork<T> {
        T run(Connection con) throws Exception;
    }

    public static <T> T withCleanupLock(CleanupDatabaseWork<T> work) throws Exception {
        Connection con = null;
        boolean locked = false;
        try {
            con = Common.getConnection();
            locked = tryAcquireCleanupLock(con);
            if (!locked) {
                return null;
            }
            return work.run(con);
        } finally {
            if (locked && con != null) {
                releaseCleanupLock(con);
            }
            Common.safeClose(con);
        }
    }

    static boolean tryAcquireCleanupLock(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(TRY_LOCK_SQL)) {
            ps.setLong(1, ADVISORY_LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    static void releaseCleanupLock(Connection con) {
        try (PreparedStatement ps = con.prepareStatement(UNLOCK_SQL)) {
            ps.setLong(1, ADVISORY_LOCK_KEY);
            ps.executeQuery();
        } catch (SQLException e) {
            log.warn("releaseCleanupLock", "Failed to release upload cleanup advisory lock", e);
        }
    }

    public static void createSourceArchiveArtifact(Connection con, Long sessionId, long jobId, String archivePath)
        throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(INSERT_SOURCE_ARCHIVE_SQL)) {
            if (sessionId == null) {
                ps.setNull(1, java.sql.Types.BIGINT);
            } else {
                ps.setLong(1, sessionId);
            }
            ps.setLong(2, jobId);
            ps.setString(3, archivePath);
            ps.executeUpdate();
        }
    }

    public static void activateSourceArchiveRetention(Connection con, long jobId, int retentionHours) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(ACTIVATE_SOURCE_ARCHIVE_RETENTION_SQL)) {
            ps.setInt(1, retentionHours);
            ps.setLong(2, jobId);
            ps.executeUpdate();
        }
    }

    public static void activateTerminalSourceArchiveRetentions(Connection con, int retentionHours) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(ACTIVATE_TERMINAL_SOURCE_ARCHIVES_SQL)) {
            ps.setInt(1, retentionHours);
            ps.executeUpdate();
        }
    }

    public static void resetSourceArchiveRetention(Connection con, long jobId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(RESET_SOURCE_ARCHIVE_RETENTION_SQL)) {
            ps.setLong(1, jobId);
            ps.executeUpdate();
        }
    }

    public static Optional<UploadArtifact> findRetryableSourceArchive(Connection con, long jobId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(FIND_RETRYABLE_SOURCE_ARCHIVE_SQL)) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapArtifact(rs));
                }
            }
        }
        return Optional.empty();
    }

    public static List<UploadArtifact> claimDueArtifacts(Connection con, int batchSize) throws SQLException {
        List<UploadArtifact> artifacts = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(CLAIM_DUE_ARTIFACTS_SQL)) {
            ps.setInt(1, batchSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    artifacts.add(mapArtifact(rs));
                }
            }
        }
        return artifacts;
    }

    public static boolean hasBlockingReferences(Connection con, UploadArtifact artifact) throws SQLException {
        String path = artifact.getPath();
        String prefix = escapeLike(path) + "/%";
        try (PreparedStatement ps = con.prepareStatement(HAS_BLOCKING_REFERENCES_SQL)) {
            ps.setString(1, path);
            ps.setString(2, prefix);
            if (artifact.getJobId() == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, artifact.getJobId());
            }
            ps.setString(4, path);
            ps.setString(5, path);
            ps.setString(6, prefix);
            ps.setString(7, prefix);
            if (artifact.getSessionId() == null) {
                ps.setNull(8, java.sql.Types.BIGINT);
            } else {
                ps.setLong(8, artifact.getSessionId());
            }
            ps.setString(9, path);
            ps.setString(10, prefix);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    public static boolean isArtifactPathOwnedByDatabaseRow(Connection con, UploadArtifact artifact) throws SQLException {
        if (artifact == null || artifact.getPath() == null || artifact.getPath().trim().isEmpty()) {
            return false;
        }

        if ("SOURCE_ARCHIVE".equals(artifact.getArtifactRole()) && artifact.getJobId() != null) {
            try (PreparedStatement ps = con.prepareStatement(SOURCE_ARCHIVE_OWNED_BY_JOB_SQL)) {
                ps.setLong(1, artifact.getJobId());
                ps.setString(2, artifact.getPath());
                if (artifact.getSessionId() == null) {
                    ps.setNull(3, java.sql.Types.BIGINT);
                    ps.setNull(4, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(3, artifact.getSessionId());
                    ps.setLong(4, artifact.getSessionId());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        }

        if ("DIRECTORY".equals(artifact.getPathKind()) && artifact.getJobId() != null) {
            try (PreparedStatement ps = con.prepareStatement(JOB_DIRECTORY_OWNED_BY_JOB_SQL)) {
                ps.setLong(1, artifact.getJobId());
                ps.setString(2, artifact.getPath());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        }

        if (artifact.getSessionId() != null &&
            ("CHUNK_DIRECTORY".equals(artifact.getArtifactRole()) || "ASSEMBLING_FILE".equals(artifact.getArtifactRole()))) {
            try (PreparedStatement ps = con.prepareStatement(SESSION_ARTIFACT_OWNED_BY_SESSION_SQL)) {
                ps.setLong(1, artifact.getSessionId());
                ps.setString(2, artifact.getArtifactRole());
                ps.setString(3, artifact.getPath());
                ps.setString(4, artifact.getArtifactRole());
                ps.setString(5, artifact.getPath());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        }

        return false;
    }

    public static void markDeleted(Connection con, long artifactId, boolean missing) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(MARK_DELETED_SQL)) {
            ps.setString(1, missing ? "MISSING" : "DELETED");
            ps.setLong(2, artifactId);
            ps.executeUpdate();
        }
    }

    public static void markSourceArchiveDeleted(Connection con, long jobId, boolean missing) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(MARK_SOURCE_ARCHIVE_DELETED_SQL)) {
            ps.setString(1, missing ? "MISSING" : "DELETED");
            ps.setLong(2, jobId);
            ps.executeUpdate();
        }
    }

    public static void markFailed(Connection con, long artifactId, String state, String error) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(MARK_FAILED_SQL)) {
            ps.setString(1, state);
            ps.setString(2, truncate(error, 1000));
            ps.setLong(3, artifactId);
            ps.executeUpdate();
        }
    }

    private static UploadArtifact mapArtifact(ResultSet rs) throws SQLException {
        UploadArtifact artifact = new UploadArtifact();
        artifact.setId(rs.getLong("id"));
        long sessionId = rs.getLong("session_id");
        artifact.setSessionId(rs.wasNull() ? null : sessionId);
        long jobId = rs.getLong("job_id");
        artifact.setJobId(rs.wasNull() ? null : jobId);
        artifact.setArtifactRole(rs.getString("artifact_role"));
        artifact.setPathKind(rs.getString("path_kind"));
        artifact.setPath(rs.getString("path"));
        artifact.setCleanupState(rs.getString("cleanup_state"));
        artifact.setRetentionUntil(rs.getTimestamp("retention_until"));
        return artifact;
    }

    private static String escapeLike(String path) {
        return path.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
