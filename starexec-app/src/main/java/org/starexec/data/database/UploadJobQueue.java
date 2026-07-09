package org.starexec.data.database;

import org.starexec.logger.StarLogger;
import org.starexec.config.EnvironmentConfig;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.to.UploadArtifact;
import org.starexec.data.to.UploadJob;
import org.starexec.util.UploadArtifactPathGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Manages the upload job queue for asynchronous benchmark upload processing.
 * Implements the FOR UPDATE SKIP LOCKED pattern for safe concurrent job processing.
 */
public class UploadJobQueue {
    private static final StarLogger log = StarLogger.getLogger(UploadJobQueue.class);

    public static final class ReconciliationResult {
        private final int cancelledCount;
        private final int failedCount;

        public ReconciliationResult(int cancelledCount, int failedCount) {
            this.cancelledCount = cancelledCount;
            this.failedCount = failedCount;
        }

        public int getCancelledCount() {
            return cancelledCount;
        }

        public int getFailedCount() {
            return failedCount;
        }

        public boolean hasChanges() {
            return cancelledCount > 0 || failedCount > 0;
        }
    }
    
    // SQL statements
    private static final String INSERT_JOB_SQL = 
        "INSERT INTO upload_jobs (archive_path, user_id, space_id, upload_method, " +
        "benchmark_type_id, downloadable, priority, archive_size, " +
        "has_dependencies, dep_root_space_id, linked, upload_session_id) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    
    private static final String CLAIM_JOB_SQL = 
        "UPDATE upload_jobs " +
        "SET status = 'PROCESSING', started_at = CURRENT_TIMESTAMP " +
        "WHERE id = (" +
        "   SELECT id FROM upload_jobs " +
        "   WHERE status = 'PENDING' " +
        "   ORDER BY priority DESC, archive_size ASC, created_at ASC " +
        "   LIMIT 1 FOR UPDATE SKIP LOCKED" +
        ") RETURNING *";
    
    private static final String UPDATE_PROGRESS_SQL = 
        "SELECT starexec.update_upload_job_progress(?, ?, ?, ?, ?, ?)";
    
    private static final String COMPLETE_JOB_SQL = 
        "SELECT starexec.complete_upload_job(?)";
    
    private static final String FAIL_JOB_SQL = 
        "SELECT starexec.fail_upload_job(?, ?)";
    
    private static final String APPEND_ERROR_SQL = 
        "SELECT starexec.append_upload_job_error(?, ?)";
    
    private static final String GET_JOB_BY_ID_SQL = 
        "SELECT * FROM upload_jobs WHERE id = ?";
    
    private static final String GET_USER_JOBS_SQL = 
        "SELECT * FROM upload_jobs WHERE user_id = ? ORDER BY created_at DESC LIMIT ?";
    
    private static final String RETRY_JOB_SQL =
        "UPDATE upload_jobs " +
        "SET status = 'PENDING', retry_count = retry_count + 1, cancel_requested = FALSE, " +
        "started_at = NULL, completed_at = NULL, last_heartbeat = NULL, error_message = NULL " +
        "WHERE id = ? AND status IN ('FAILED', 'CANCELLED') AND retry_count < max_retries";
    
    private static final String CANCEL_PENDING_JOB_SQL =
        "UPDATE upload_jobs SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP, cancel_requested = FALSE " +
        "WHERE id = ? AND user_id = ? AND status = 'PENDING'";

    private static final String REQUEST_CANCEL_JOB_SQL =
        "UPDATE upload_jobs SET cancel_requested = TRUE, last_heartbeat = CURRENT_TIMESTAMP " +
        "WHERE id = ? AND user_id = ? AND status = 'PROCESSING'";

    private static final String CANCEL_PENDING_JOB_AS_ADMIN_SQL =
        "UPDATE upload_jobs SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP, cancel_requested = FALSE " +
        "WHERE id = ? AND status = 'PENDING'";

    private static final String REQUEST_CANCEL_JOB_AS_ADMIN_SQL =
        "UPDATE upload_jobs SET cancel_requested = TRUE, last_heartbeat = CURRENT_TIMESTAMP " +
        "WHERE id = ? AND status = 'PROCESSING'";

    private static final String MARK_JOB_CANCELLED_SQL =
        "UPDATE upload_jobs SET status = 'CANCELLED', cancel_requested = FALSE, completed_at = CURRENT_TIMESTAMP, " +
        "last_heartbeat = CURRENT_TIMESTAMP WHERE id = ? AND status IN ('PROCESSING', 'PENDING')";

    private static final String GET_CANCEL_REQUESTED_SQL =
        "SELECT cancel_requested FROM upload_jobs WHERE id = ?";

    private static final String UPDATE_EXTRACT_PATH_SQL =
        "UPDATE upload_jobs SET extract_path = ? WHERE id = ?";

    private static final String RECONCILE_CANCELLED_PROCESSING_JOBS_SQL =
        "UPDATE upload_jobs SET status = 'CANCELLED', cancel_requested = FALSE, completed_at = CURRENT_TIMESTAMP, " +
        "last_heartbeat = CURRENT_TIMESTAMP WHERE status = 'PROCESSING' AND cancel_requested = TRUE";

    private static final String RECONCILE_FAILED_PROCESSING_JOBS_SQL =
        "UPDATE upload_jobs SET status = 'FAILED', cancel_requested = FALSE, completed_at = CURRENT_TIMESTAMP, " +
        "last_heartbeat = CURRENT_TIMESTAMP, error_message = CASE WHEN error_message IS NULL OR error_message = '' " +
        "THEN ? ELSE error_message || E'\n' || ? END WHERE status = 'PROCESSING' AND cancel_requested = FALSE";

    private static final String RESTART_INTERRUPTED_ERROR_MESSAGE =
        "Upload processing was interrupted by application restart before completion. Please retry the upload job.";
    
    /**
     * Enqueues a new upload job for asynchronous processing using the UploadJobRequest object.
     * 
     * @param request The immutable request containing all job parameters
     * @return The ID of the created job, or -1 on failure
     */
    public static long enqueueJob(UploadJob.UploadJobRequest request) {
        try {
            return Common.<Long, SQLException>runInTransaction(con -> enqueueJobInTransaction(con, request));
        } catch (SQLException e) {
            log.error("enqueueJob", "Failed to enqueue upload job", e);
            return -1;
        }
    }

    static long enqueueJobInTransaction(Connection con, UploadJob.UploadJobRequest request) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(INSERT_JOB_SQL, Statement.RETURN_GENERATED_KEYS)) {
            bindUploadJobRequest(ps, request);

            int affected = ps.executeUpdate();
            if (affected == 0) {
                log.error("Failed to insert upload job");
                return -1;
            }

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    return -1;
                }

                long jobId = keys.getLong(1);
                UploadArtifactCleanupRepository.createSourceArchiveArtifact(
                    con,
                    request.getUploadSessionId().orElse(null),
                    jobId,
                    request.getArchivePath()
                );
                log.info("enqueueJob", "Enqueued upload job " + jobId + " for user " + request.getUserId() + " (priority: " + request.getPriority() + ")");
                return jobId;
            }
        }
    }

    private static void bindUploadJobRequest(PreparedStatement ps, UploadJob.UploadJobRequest request) throws SQLException {
        ps.setString(1, request.getArchivePath());
        ps.setInt(2, request.getUserId());
        ps.setInt(3, request.getSpaceId());
        ps.setString(4, request.getUploadMethod());
        ps.setInt(5, request.getBenchmarkTypeId());
        ps.setBoolean(6, request.isDownloadable());
        ps.setInt(7, request.getPriority());
        ps.setLong(8, request.getArchiveSize());
        ps.setBoolean(9, request.isHasDependencies());
        ps.setObject(10, request.getDepRootSpaceId().orElse(null), java.sql.Types.INTEGER);
        ps.setBoolean(11, request.isLinked());
        ps.setObject(12, request.getUploadSessionId().orElse(null), java.sql.Types.BIGINT);
    }
    
    /**
     * Claims a job for processing using FOR UPDATE SKIP LOCKED pattern.
     * This method is thread-safe and ensures only one worker processes each job.
     * 
     * @return An Optional containing the claimed job, or empty if no jobs available
     */
    public static Optional<UploadJob> claimJob() {
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(CLAIM_JOB_SQL);
            rs = ps.executeQuery();
            
            // Atomic UPDATE ... RETURNING - rows come directly in ResultSet
            if (rs.next()) {
                UploadJob job = mapResultSetToJob(rs);
                log.info("claimJob", "Claimed upload job " + job.getId() + " for processing");
                return Optional.of(job);
            }
            
            return Optional.empty();
            
        } catch (SQLException e) {
            log.error("claimJob", "Failed to claim upload job", e);
            return Optional.empty();
        } finally {
            Common.safeClose(rs);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    /**
     * Updates the heartbeat of a processing job to indicate it is still alive.
     * 
     * @param jobId The job ID
     * @return true if successful, false otherwise
     */
    public static boolean touchJob(long jobId) {
        Connection con = null;
        CallableStatement cs = null;
        
        try {
            con = Common.getConnection();
            cs = con.prepareCall("SELECT starexec.touch_upload_job(?)");
            cs.setLong(1, jobId);
            cs.execute();
            
            return true;
            
        } catch (SQLException e) {
            log.error("touchJob", "Failed to touch job " + jobId, e);
            return false;
        } finally {
            Common.safeClose(cs);
            Common.safeClose(con);
        }
    }
    
    /**
     * Updates the progress of a job with idempotency tracking.
     * 
     * @param jobId The job ID
     * @param totalFilesFound Total files discovered during traversal
     * @param totalFilesProcessed Total files successfully inserted
     * @param totalSpacesCreated Total spaces created (for 'convert' method)
     * @param lastProcessedPath Path of the last successfully processed benchmark
     * @param lastProcessedIndex Index of the last successfully processed benchmark
     * @return true if successful, false otherwise
     */
    public static boolean updateProgress(long jobId, Integer totalFilesFound, 
                                         Integer totalFilesProcessed, 
                                         Integer totalSpacesCreated,
                                         String lastProcessedPath,
                                         Integer lastProcessedIndex) {
        Connection con = null;
        CallableStatement cs = null;
        
        try {
            con = Common.getConnection();
            cs = con.prepareCall(UPDATE_PROGRESS_SQL);
            
            cs.setLong(1, jobId);
            if (totalFilesFound != null) {
                cs.setInt(2, totalFilesFound);
            } else {
                cs.setNull(2, Types.INTEGER);
            }
            if (totalFilesProcessed != null) {
                cs.setInt(3, totalFilesProcessed);
            } else {
                cs.setNull(3, Types.INTEGER);
            }
            if (totalSpacesCreated != null) {
                cs.setInt(4, totalSpacesCreated);
            } else {
                cs.setNull(4, Types.INTEGER);
            }
            if (lastProcessedPath != null) {
                cs.setString(5, lastProcessedPath);
            } else {
                cs.setNull(5, Types.VARCHAR);
            }
            if (lastProcessedIndex != null) {
                cs.setInt(6, lastProcessedIndex);
            } else {
                cs.setNull(6, Types.INTEGER);
            }
            
            cs.execute();
            return true;
            
        } catch (SQLException e) {
            log.error("updateProgress", "Failed to update progress for job " + jobId, e);
            return false;
        } finally {
            Common.safeClose(cs);
            Common.safeClose(con);
        }
    }
    
    /**
     * Marks a job as completed successfully.
     * 
     * @param jobId The job ID
     * @return true if successful, false otherwise
     */
    public static boolean completeJob(long jobId) {
        try {
            boolean completed = Common.<Boolean, SQLException>runInTransaction(con -> completeJobInTransaction(con, jobId));
            if (completed) {
                log.info("completeJob", "Completed upload job " + jobId);
            }
            return completed;
        } catch (SQLException e) {
            log.error("completeJob", "Failed to complete job " + jobId, e);
            return false;
        }
    }

    static boolean completeJobInTransaction(Connection con, long jobId) throws SQLException {
        try (CallableStatement cs = con.prepareCall(COMPLETE_JOB_SQL)) {
            cs.setLong(1, jobId);
            cs.execute();
        }

        UploadJob job = getJobInTransaction(con, jobId).orElse(null);
        if (job != null && ("COMPLETED".equals(job.getStatus()) || "COMPLETED_WITH_ERRORS".equals(job.getStatus()))) {
            activateSourceArchiveRetention(con, jobId);
            return true;
        }
        return false;
    }
    
    /**
     * Marks a job as failed with an error message.
     * 
     * @param jobId The job ID
     * @param errorMessage Error description
     * @return true if successful, false otherwise
     */
    public static boolean failJob(long jobId, String errorMessage) {
        try {
            Common.<SQLException>runInTransaction(con -> failJobInTransaction(con, jobId, errorMessage));
            log.error("failJob", "Failed upload job " + jobId + ": " + errorMessage);
            return true;
        } catch (SQLException e) {
            log.error("failJob", "Failed to mark job " + jobId + " as failed", e);
            return false;
        }
    }

    static void failJobInTransaction(Connection con, long jobId, String errorMessage) throws SQLException {
        try (CallableStatement cs = con.prepareCall(FAIL_JOB_SQL)) {
            cs.setLong(1, jobId);
            cs.setString(2, errorMessage);
            cs.execute();
        }
        activateSourceArchiveRetention(con, jobId);
    }
    
    /**
     * Appends an error message to the job's existing error log.
     * 
     * @param jobId The job ID
     * @param error The error message to append
     * @return true if successful, false otherwise
     */
    public static boolean appendError(long jobId, String error) {
        Connection con = null;
        CallableStatement cs = null;
        
        try {
            con = Common.getConnection();
            cs = con.prepareCall(APPEND_ERROR_SQL);
            cs.setLong(1, jobId);
            cs.setString(2, error);
            cs.execute();
            
            return true;
            
        } catch (SQLException e) {
            log.error("appendError", "Failed to append error to job " + jobId, e);
            return false;
        } finally {
            Common.safeClose(cs);
            Common.safeClose(con);
        }
    }
    
    /**
     * Retrieves the total number of upload jobs for a user.
     * 
     * @param userId The user ID
     * @return Total count of jobs
     */
    public static int getJobCountByUser(int userId) {
        return getJobCountByUser(userId, "");
    }

    /**
     * Retrieves the total number of upload jobs for a user matching a search query.
     */
    public static int getJobCountByUser(int userId, String query) {
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        
        try {
            con = Common.getConnection();
            String sql = "SELECT count(*) FROM upload_jobs WHERE user_id = ?";
            if (query != null && !query.isEmpty()) {
                sql += " AND archive_path LIKE ?";
            }
            
            ps = con.prepareStatement(sql);
            ps.setInt(1, userId);
            if (query != null && !query.isEmpty()) {
                ps.setString(2, "%" + query + "%");
            }
            rs = ps.executeQuery();
            
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
            
        } catch (SQLException e) {
            log.error("getJobCountByUser", "Failed to count jobs for user " + userId, e);
            return 0;
        } finally {
            Common.safeClose(rs);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    /**
     * Retrieves a job by ID.
     */
    public static Optional<UploadJob> getJob(long jobId) {
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(GET_JOB_BY_ID_SQL);
            ps.setLong(1, jobId);
            rs = ps.executeQuery();
            
            if (rs.next()) {
                return Optional.of(mapResultSetToJob(rs));
            }
            
            return Optional.empty();
            
        } catch (SQLException e) {
            log.error("getJob", "Failed to get job " + jobId, e);
            return Optional.empty();
        } finally {
            Common.safeClose(rs);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    static Optional<UploadJob> getJobInTransaction(Connection con, long jobId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(GET_JOB_BY_ID_SQL)) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToJob(rs));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Retrieves recent jobs for a user.
     * 
     * @param userId The user ID
     * @param limit Maximum number of jobs to return
     * @return List of jobs, empty list on error
     */
    public static List<UploadJob> getUserJobs(int userId, int limit) {
        List<UploadJob> jobs = new ArrayList<>();
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(GET_USER_JOBS_SQL);
            ps.setInt(1, userId);
            ps.setInt(2, limit);
            rs = ps.executeQuery();
            
            while (rs.next()) {
                jobs.add(mapResultSetToJob(rs));
            }
            
            return jobs;
            
        } catch (SQLException e) {
            log.error("getUserJobs", "Failed to get jobs for user " + userId, e);
            return new ArrayList<>();
        } finally {
            Common.safeClose(rs);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }
    
    /**
     * Attempts to retry a failed job.
     * 
     * @param jobId The job ID
     * @return true if retry scheduled, false if max retries exceeded or job not found
     */
    public static boolean retryJob(long jobId) {
        try {
            if (Common.<Boolean, Exception>runInTransaction(con -> retryJobInTransaction(con, jobId))) {
                log.info("retryJob", "Scheduled retry for job " + jobId);
                return true;
            }
            return false;
        } catch (SQLException e) {
            log.error("retryJob", "Failed to retry job " + jobId, e);
            return false;
        } catch (IOException e) {
            log.warn("retryJob", "Retry artifact is unavailable for job " + jobId + ": " + e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("retryJob", "Unexpected failure while retrying job " + jobId, e);
            return false;
        }
    }

    static boolean retryJobInTransaction(Connection con, long jobId) throws SQLException, IOException {
        return retryJobInTransaction(con, jobId, new UploadArtifactPathGuard());
    }

    static boolean retryJobInTransaction(Connection con, long jobId, UploadArtifactPathGuard pathGuard)
        throws SQLException, IOException {
        Optional<UploadArtifact> artifact = UploadArtifactCleanupRepository.findRetryableSourceArchive(con, jobId);
        if (artifact.isEmpty()) {
            return false;
        }

        if (!ensureRetryArtifactExists(con, artifact.get(), pathGuard)) {
            return false;
        }

        try (PreparedStatement ps = con.prepareStatement(RETRY_JOB_SQL)) {
            ps.setLong(1, jobId);
            int updated = ps.executeUpdate();
            if (updated <= 0) {
                return false;
            }
        }

        UploadArtifactCleanupRepository.resetSourceArchiveRetention(con, jobId);
        return true;
    }
    
    /**
     * Cancels a pending or processing job.
     * Only the job owner can cancel it.
     * 
     * @param jobId The job ID
     * @param userId The user ID (must own the job)
     * @return true if cancelled, false otherwise
     */
    public static boolean cancelJob(long jobId, int userId) {
        try {
            boolean adminCancel = GeneralSecurity.hasAdminWritePrivileges(userId);
            boolean cancelled = Common.<Boolean, SQLException>runInTransaction(
                con -> cancelJobInTransaction(con, jobId, userId, adminCancel)
            );
            if (cancelled) {
                log.info("cancelJob", "Cancelled or requested cancellation for job " + jobId + " by user " + userId);
            }
            return cancelled;
        } catch (SQLException e) {
            log.error("cancelJob", "Failed to cancel job " + jobId, e);
            return false;
        }
    }

    static boolean cancelJobInTransaction(Connection con, long jobId, int userId, boolean adminCancel) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(adminCancel ? CANCEL_PENDING_JOB_AS_ADMIN_SQL : CANCEL_PENDING_JOB_SQL)) {
            ps.setLong(1, jobId);
            if (!adminCancel) {
                ps.setInt(2, userId);
            }

            int affected = ps.executeUpdate();
            if (affected > 0) {
                activateSourceArchiveRetention(con, jobId);
                return true;
            }
        }

        try (PreparedStatement ps = con.prepareStatement(adminCancel ? REQUEST_CANCEL_JOB_AS_ADMIN_SQL : REQUEST_CANCEL_JOB_SQL)) {
            ps.setLong(1, jobId);
            if (!adminCancel) {
                ps.setInt(2, userId);
            }
            return ps.executeUpdate() > 0;
        }
    }

    public static boolean isCancelRequested(long jobId) {
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            con = Common.getConnection();
            ps = con.prepareStatement(GET_CANCEL_REQUESTED_SQL);
            ps.setLong(1, jobId);
            rs = ps.executeQuery();
            return rs.next() && rs.getBoolean(1);
        } catch (SQLException e) {
            log.error("isCancelRequested", "Failed to check cancellation for job " + jobId, e);
            return false;
        } finally {
            Common.safeClose(rs);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    public static boolean isSourceArchiveAvailableForRetry(long jobId) {
        Connection con = null;

        try {
            con = Common.getConnection();
            Optional<UploadArtifact> artifact = UploadArtifactCleanupRepository.findRetryableSourceArchive(con, jobId);
            if (artifact.isEmpty()) {
                return false;
            }
            return ensureRetryArtifactExists(con, artifact.get(), new UploadArtifactPathGuard());
        } catch (SQLException | IOException e) {
            log.error("isSourceArchiveAvailableForRetry", "Failed to check retry artifact for job " + jobId, e);
            return false;
        } finally {
            Common.safeClose(con);
        }
    }

    private static boolean ensureRetryArtifactExists(Connection con, UploadArtifact artifact, UploadArtifactPathGuard pathGuard)
        throws SQLException {
        if (!UploadArtifactCleanupRepository.isArtifactPathOwnedByDatabaseRow(con, artifact)) {
            UploadArtifactCleanupRepository.markFailed(
                con,
                artifact.getId(),
                "INVALID_PATH",
                "Artifact path is not owned by its upload job/session row"
            );
            return false;
        }

        Path artifactPath;
        try {
            artifactPath = pathGuard.validateArtifact(artifact);
        } catch (IOException e) {
            UploadArtifactCleanupRepository.markFailed(con, artifact.getId(), "INVALID_PATH", e.getMessage());
            return false;
        }
        if (!Files.isRegularFile(artifactPath, LinkOption.NOFOLLOW_LINKS)) {
            UploadArtifactCleanupRepository.markDeleted(con, artifact.getId(), true);
            return false;
        }
        return true;
    }

    public static boolean markJobCancelled(long jobId) {
        try {
            return Common.<Boolean, SQLException>runInTransaction(con -> markJobCancelledInTransaction(con, jobId));
        } catch (SQLException e) {
            log.error("markJobCancelled", "Failed to mark job " + jobId + " as cancelled", e);
            return false;
        }
    }

    static boolean markJobCancelledInTransaction(Connection con, long jobId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(MARK_JOB_CANCELLED_SQL)) {
            ps.setLong(1, jobId);
            boolean cancelled = ps.executeUpdate() > 0;
            if (cancelled) {
                activateSourceArchiveRetention(con, jobId);
            }
            return cancelled;
        }
    }

    public static void markSourceArchiveDeleted(long jobId, boolean missing) {
        Connection con = null;
        try {
            con = Common.getConnection();
            UploadArtifactCleanupRepository.markSourceArchiveDeleted(con, jobId, missing);
        } catch (SQLException e) {
            log.warn("markSourceArchiveDeleted", "Failed to mark source archive cleanup state for job " + jobId, e);
        } finally {
            Common.safeClose(con);
        }
    }

    public static boolean updateExtractPath(long jobId, String extractPath) {
        Connection con = null;
        PreparedStatement ps = null;

        try {
            con = Common.getConnection();
            ps = con.prepareStatement(UPDATE_EXTRACT_PATH_SQL);
            ps.setString(1, extractPath);
            ps.setLong(2, jobId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("updateExtractPath", "Failed to update extract path for job " + jobId, e);
            return false;
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    public static ReconciliationResult reconcileStaleProcessingJobs() {
        try {
            ReconciliationResult result = Common.runInTransaction(UploadJobQueue::reconcileStaleProcessingJobsInTransaction);

            if (result.hasChanges()) {
                log.warn(
                    "reconcileStaleProcessingJobs",
                    "Reconciled stale upload jobs after startup: cancelled=" + result.getCancelledCount() +
                        ", failed=" + result.getFailedCount()
                );
            }

            return result;
        } catch (SQLException e) {
            log.error("reconcileStaleProcessingJobs", "Failed to reconcile stale upload jobs", e);
            return new ReconciliationResult(0, 0);
        }
    }

    static ReconciliationResult reconcileStaleProcessingJobsInTransaction(Connection con) throws SQLException {
        int cancelledCount;
        int failedCount;

        try (PreparedStatement cancelPs = con.prepareStatement(RECONCILE_CANCELLED_PROCESSING_JOBS_SQL)) {
            cancelledCount = cancelPs.executeUpdate();
        }

        try (PreparedStatement failPs = con.prepareStatement(RECONCILE_FAILED_PROCESSING_JOBS_SQL)) {
            failPs.setString(1, RESTART_INTERRUPTED_ERROR_MESSAGE);
            failPs.setString(2, RESTART_INTERRUPTED_ERROR_MESSAGE);
            failedCount = failPs.executeUpdate();
        }

        if (cancelledCount > 0 || failedCount > 0) {
            UploadArtifactCleanupRepository.activateTerminalSourceArchiveRetentions(
                con,
                EnvironmentConfig.getUploadArtifactRetentionHours()
            );
        }

        return new ReconciliationResult(cancelledCount, failedCount);
    }

    private static void activateSourceArchiveRetention(Connection con, long jobId) throws SQLException {
        UploadArtifactCleanupRepository.activateSourceArchiveRetention(
            con,
            jobId,
            EnvironmentConfig.getUploadArtifactRetentionHours()
        );
    }
    
    /**
     * Maps a ResultSet row to an UploadJob object.
     */
    private static UploadJob mapResultSetToJob(ResultSet rs) throws SQLException {
        UploadJob job = new UploadJob();
        job.setId(rs.getLong("id"));
        job.setArchivePath(rs.getString("archive_path"));
        job.setUserId(rs.getInt("user_id"));
        job.setSpaceId(rs.getInt("space_id"));
        job.setUploadMethod(rs.getString("upload_method"));
        job.setBenchmarkTypeId(rs.getInt("benchmark_type_id"));
        job.setDownloadable(rs.getBoolean("downloadable"));
        job.setStatus(rs.getString("status"));
        job.setTotalFilesFound(rs.getInt("total_files_found"));
        job.setTotalFilesProcessed(rs.getInt("total_files_processed"));
        job.setTotalSpacesCreated(rs.getInt("total_spaces_created"));
        job.setErrorMessage(rs.getString("error_message"));
        job.setRetryCount(rs.getInt("retry_count"));
        job.setMaxRetries(rs.getInt("max_retries"));
        job.setCreatedAt(rs.getTimestamp("created_at"));
        job.setStartedAt(rs.getTimestamp("started_at"));
        job.setCompletedAt(rs.getTimestamp("completed_at"));
        job.setLastHeartbeat(rs.getTimestamp("last_heartbeat"));
        job.setPriority(rs.getInt("priority"));
        job.setScheduledAfter(rs.getTimestamp("scheduled_after"));
        job.setHasDependencies(rs.getBoolean("has_dependencies"));
        int depRoot = rs.getInt("dep_root_space_id");
        job.setDepRootSpaceId(rs.wasNull() ? null : depRoot);
        job.setLinked(rs.getBoolean("linked"));
        long uploadSessionId = rs.getLong("upload_session_id");
        job.setUploadSessionId(rs.wasNull() ? null : uploadSessionId);
        job.setLastProcessedPath(rs.getString("last_processed_path"));
        job.setLastProcessedIndex(rs.getInt("last_processed_index"));
        job.setExtractPath(rs.getString("extract_path"));
        job.setCancelRequested(rs.getBoolean("cancel_requested"));
        return job;
    }
}
