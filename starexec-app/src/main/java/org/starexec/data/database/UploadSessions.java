package org.starexec.data.database;

import org.apache.commons.io.FileUtils;
import org.starexec.config.EnvironmentConfig;
import org.starexec.data.to.UploadSession;
import org.starexec.data.to.UploadSessionCreateRequest;
import org.starexec.logger.StarLogger;
import org.starexec.servlets.UploadBenchmark;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence layer for resumable browser-side upload sessions.
 */
public class UploadSessions {
    private static final StarLogger log = StarLogger.getLogger(UploadSessions.class);
    public static final int DEFAULT_CHUNK_SIZE_BYTES = 2 * 1024 * 1024;

    private static final String INSERT_SESSION_SQL =
        "INSERT INTO upload_sessions (" +
            "user_id, space_id, file_name, staging_path, total_bytes, bytes_received, chunk_size, total_chunks, " +
            "upload_method, benchmark_type_id, downloadable, has_dependencies, dep_root_space_id, linked" +
        ") VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String GET_SESSION_SQL =
        "SELECT * FROM upload_sessions WHERE id = ?";

    private static final String UPDATE_CHUNK_SQL =
        "UPDATE upload_sessions " +
        "SET bytes_received = ?, next_chunk_index = ?, status = ?, updated_at = CURRENT_TIMESTAMP " +
        "WHERE id = ?";

    private static final String CHUNK_EXISTS_SQL =
        "SELECT 1 FROM upload_session_chunks WHERE session_id = ? AND chunk_index = ?";

    private static final String INSERT_CHUNK_SQL =
        "INSERT INTO upload_session_chunks (session_id, chunk_index, chunk_size) " +
        "VALUES (?, ?, ?) ON CONFLICT (session_id, chunk_index) DO NOTHING";

    private static final String UPDATE_PROGRESS_FROM_CHUNKS_SQL =
        "UPDATE upload_sessions s " +
        "SET bytes_received = COALESCE((" +
        "        SELECT SUM(c.chunk_size)::BIGINT " +
        "        FROM upload_session_chunks c " +
        "        WHERE c.session_id = s.id" +
        "    ), 0), " +
        "    next_chunk_index = COALESCE((" +
        "        SELECT MIN(gs) " +
        "        FROM generate_series(0, s.total_chunks - 1) gs " +
        "        LEFT JOIN upload_session_chunks c " +
        "            ON c.session_id = s.id AND c.chunk_index = gs " +
        "        WHERE c.chunk_index IS NULL" +
        "    ), s.total_chunks), " +
        "    status = CASE " +
        "        WHEN COALESCE((" +
        "            SELECT MIN(gs) " +
        "            FROM generate_series(0, s.total_chunks - 1) gs " +
        "            LEFT JOIN upload_session_chunks c " +
        "                ON c.session_id = s.id AND c.chunk_index = gs " +
        "            WHERE c.chunk_index IS NULL" +
        "        ), s.total_chunks) = s.total_chunks THEN 'READY' " +
        "        ELSE 'UPLOADING' " +
        "    END, " +
        "    updated_at = CURRENT_TIMESTAMP " +
        "WHERE s.id = ? " +
        "  AND s.status IN ('UPLOADING', 'READY')";

    private static final String UPDATE_STAGING_PATH_SQL =
        "UPDATE upload_sessions SET staging_path = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";

    private static final String MARK_FINALIZING_SQL =
        "UPDATE upload_sessions SET status = 'FINALIZING', updated_at = CURRENT_TIMESTAMP " +
        "WHERE id = ? AND status IN ('UPLOADING', 'READY')";

    private static final String COMPLETE_SESSION_SQL =
        "UPDATE upload_sessions " +
        "SET status = 'COMPLETE', job_id = ?, completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP, error_message = NULL " +
        "WHERE id = ?";

    private static final String FAIL_SESSION_SQL =
        "UPDATE upload_sessions " +
        "SET status = 'FAILED', error_message = ?, updated_at = CURRENT_TIMESTAMP " +
        "WHERE id = ?";

    private static final String ABORT_SESSION_SQL =
        "UPDATE upload_sessions " +
        "SET status = 'ABORTED', completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP " +
        "WHERE id = ? AND status NOT IN ('COMPLETE', 'ABORTED')";

    private UploadSessions() {}

    public static Optional<UploadSession> createSession(int userId, UploadSessionCreateRequest request) {
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet keys = null;

        String cleanFileName = sanitizeFileName(request.getFileName());
        long totalBytes = request.getTotalBytes();
        int chunkSize = Math.max(1, EnvironmentConfig.getUploadSessionChunkSizeBytes());
        int totalChunks = (int)Math.ceil(totalBytes / (double)chunkSize);

        try {
            File sessionDir = UploadBenchmark.getDirectoryForBenchmarkUpload(
                userId, "upload-session-" + UUID.randomUUID()
            );
            File stagingFile = new File(sessionDir, cleanFileName + ".part");

            con = Common.getConnection();
            ps = con.prepareStatement(INSERT_SESSION_SQL, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, userId);
            ps.setInt(2, request.getSpaceId());
            ps.setString(3, cleanFileName);
            ps.setString(4, stagingFile.getAbsolutePath());
            ps.setLong(5, totalBytes);
            ps.setInt(6, chunkSize);
            ps.setInt(7, totalChunks);
            ps.setString(8, request.getUploadMethod());
            ps.setInt(9, request.getBenchmarkTypeId());
            ps.setBoolean(10, request.isDownloadable());
            ps.setBoolean(11, request.isHasDependencies());
            ps.setObject(12, request.getDepRootSpaceId(), Types.INTEGER);
            ps.setBoolean(13, request.isLinked());
            ps.executeUpdate();

            keys = ps.getGeneratedKeys();
            if (!keys.next()) {
                return Optional.empty();
            }
            return getSession(keys.getLong(1));
        } catch (Exception e) {
            log.error("createSession", "Failed to create upload session for user " + userId, e);
            return Optional.empty();
        } finally {
            Common.safeClose(keys);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    public static Optional<UploadSession> getSession(long sessionId) {
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            con = Common.getConnection();
            ps = con.prepareStatement(GET_SESSION_SQL);
            ps.setLong(1, sessionId);
            rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapResultSet(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            log.error("getSession", "Failed to load upload session " + sessionId, e);
            return Optional.empty();
        } finally {
            Common.safeClose(rs);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    public static boolean recordChunk(long sessionId, long bytesReceived, int nextChunkIndex, String status) {
        Connection con = null;
        PreparedStatement ps = null;

        try {
            con = Common.getConnection();
            ps = con.prepareStatement(UPDATE_CHUNK_SQL);
            ps.setLong(1, bytesReceived);
            ps.setInt(2, nextChunkIndex);
            ps.setString(3, status);
            ps.setLong(4, sessionId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("recordChunk", "Failed to record chunk progress for session " + sessionId, e);
            return false;
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    public static boolean isChunkRecorded(long sessionId, int chunkIndex) {
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            con = Common.getConnection();
            ps = con.prepareStatement(CHUNK_EXISTS_SQL);
            ps.setLong(1, sessionId);
            ps.setInt(2, chunkIndex);
            rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            log.error("isChunkRecorded", "Failed to query chunk state for session " + sessionId + " chunk " + chunkIndex, e);
            return false;
        } finally {
            Common.safeClose(rs);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    public static boolean recordChunkIfAbsent(long sessionId, int chunkIndex, int chunkSizeBytes) {
        Connection con = null;
        PreparedStatement insertPs = null;
        PreparedStatement updatePs = null;

        try {
            con = Common.getConnection();
            Common.beginTransaction(con);

            insertPs = con.prepareStatement(INSERT_CHUNK_SQL);
            insertPs.setLong(1, sessionId);
            insertPs.setInt(2, chunkIndex);
            insertPs.setInt(3, chunkSizeBytes);
            insertPs.executeUpdate();

            updatePs = con.prepareStatement(UPDATE_PROGRESS_FROM_CHUNKS_SQL);
            updatePs.setLong(1, sessionId);
            int updated = updatePs.executeUpdate();

            Common.endTransaction(con);
            return updated > 0;
        } catch (SQLException e) {
            Common.doRollback(con);
            log.error("recordChunkIfAbsent", "Failed to persist chunk for session " + sessionId + " chunk " + chunkIndex, e);
            return false;
        } finally {
            Common.safeClose(insertPs);
            Common.safeClose(updatePs);
            Common.safeClose(con);
        }
    }

    public static boolean updateStagingPath(long sessionId, String stagingPath) {
        Connection con = null;
        PreparedStatement ps = null;

        try {
            con = Common.getConnection();
            ps = con.prepareStatement(UPDATE_STAGING_PATH_SQL);
            ps.setString(1, stagingPath);
            ps.setLong(2, sessionId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("updateStagingPath", "Failed to update staging path for session " + sessionId, e);
            return false;
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    public static boolean markFinalizing(long sessionId) {
        Connection con = null;
        PreparedStatement ps = null;

        try {
            con = Common.getConnection();
            ps = con.prepareStatement(MARK_FINALIZING_SQL);
            ps.setLong(1, sessionId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("markFinalizing", "Failed to mark session " + sessionId + " as finalizing", e);
            return false;
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    public static boolean completeSession(long sessionId, long jobId) {
        Connection con = null;
        PreparedStatement ps = null;

        try {
            con = Common.getConnection();
            ps = con.prepareStatement(COMPLETE_SESSION_SQL);
            ps.setLong(1, jobId);
            ps.setLong(2, sessionId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("completeSession", "Failed to complete session " + sessionId, e);
            return false;
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    public static boolean failSession(long sessionId, String errorMessage) {
        Connection con = null;
        PreparedStatement ps = null;

        try {
            con = Common.getConnection();
            ps = con.prepareStatement(FAIL_SESSION_SQL);
            ps.setString(1, errorMessage);
            ps.setLong(2, sessionId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("failSession", "Failed to fail session " + sessionId, e);
            return false;
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    public static boolean abortSession(long sessionId) {
        Connection con = null;
        PreparedStatement ps = null;

        try {
            con = Common.getConnection();
            ps = con.prepareStatement(ABORT_SESSION_SQL);
            ps.setLong(1, sessionId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("abortSession", "Failed to abort session " + sessionId, e);
            return false;
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    public static void cleanupSessionFiles(UploadSession session) {
        if (session == null || session.getStagingPath() == null || session.getStagingPath().isEmpty()) {
            return;
        }

        File stagingFile = new File(session.getStagingPath());
        File chunkDirectory = new File(session.getStagingPath() + ".chunks");
        File assemblingFile = new File(session.getStagingPath() + ".assembling");
        try {
            if (chunkDirectory.exists()) {
                FileUtils.deleteDirectory(chunkDirectory);
            }
            if (assemblingFile.exists()) {
                FileUtils.deleteQuietly(assemblingFile);
            }
            if (stagingFile.exists()) {
                FileUtils.deleteQuietly(stagingFile);
            }
        } catch (IOException e) {
            log.warn("cleanupSessionFiles", "Failed to cleanup session artifacts for session " + session.getId(), e);
        }
    }

    public static String sanitizeFileName(String fileName) {
        if (fileName == null) {
            return "upload.zip";
        }
        String clean = new File(fileName).getName().trim();
        if (clean.isEmpty()) {
            return "upload.zip";
        }
        return clean.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static UploadSession mapResultSet(ResultSet rs) throws SQLException {
        UploadSession session = new UploadSession();
        session.setId(rs.getLong("id"));
        session.setUserId(rs.getInt("user_id"));
        session.setSpaceId(rs.getInt("space_id"));
        session.setFileName(rs.getString("file_name"));
        session.setStagingPath(rs.getString("staging_path"));
        session.setTotalBytes(rs.getLong("total_bytes"));
        session.setBytesReceived(rs.getLong("bytes_received"));
        session.setChunkSize(rs.getInt("chunk_size"));
        session.setNextChunkIndex(rs.getInt("next_chunk_index"));
        session.setTotalChunks(rs.getInt("total_chunks"));
        session.setUploadMethod(rs.getString("upload_method"));
        session.setBenchmarkTypeId(rs.getInt("benchmark_type_id"));
        session.setDownloadable(rs.getBoolean("downloadable"));
        session.setHasDependencies(rs.getBoolean("has_dependencies"));
        int depRootSpaceId = rs.getInt("dep_root_space_id");
        session.setDepRootSpaceId(rs.wasNull() ? null : depRootSpaceId);
        session.setLinked(rs.getBoolean("linked"));
        session.setStatus(rs.getString("status"));
        long jobId = rs.getLong("job_id");
        session.setJobId(rs.wasNull() ? null : jobId);
        session.setErrorMessage(rs.getString("error_message"));
        session.setCreatedAt(rs.getTimestamp("created_at"));
        session.setUpdatedAt(rs.getTimestamp("updated_at"));
        session.setCompletedAt(rs.getTimestamp("completed_at"));
        session.setExpiresAt(rs.getTimestamp("expires_at"));
        return session;
    }
}
