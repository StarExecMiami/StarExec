package org.starexec.data.database;

import org.starexec.logger.StarLogger;
import org.starexec.data.to.UploadJob;

import java.io.File;
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
    
    // SQL statements
    private static final String INSERT_JOB_SQL = 
        "INSERT INTO upload_jobs (archive_path, user_id, space_id, upload_method, " +
        "benchmark_type_id, downloadable, priority, archive_size) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    
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
        "SELECT starexec.retry_upload_job(?)";
    
    private static final String CANCEL_JOB_SQL = 
        "UPDATE upload_jobs SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP " +
        "WHERE id = ? AND user_id = ? AND status IN ('PENDING', 'PROCESSING')";
    
    /**
     * Enqueues a new upload job for asynchronous processing.
     * 
     * @param archivePath Path to the uploaded archive file
     * @param userId User who initiated the upload
     * @param spaceId Target space for benchmarks
     * @param uploadMethod 'convert' or 'dump'
     * @param benchmarkTypeId Processor type ID
     * @param downloadable Whether benchmarks are downloadable
     * @param priority Job priority (higher = processed sooner)
     * @return The ID of the created job, or -1 on failure
     */
    public static long enqueueJob(String archivePath, int userId, int spaceId, 
                                  String uploadMethod, int benchmarkTypeId, 
                                  boolean downloadable, int priority) {
        // Get archive file size for SJF scheduling
        long archiveSize = 0;
        File archiveFile = new File(archivePath);
        if (archiveFile.exists()) {
            archiveSize = archiveFile.length();
        }
        
        return enqueueJob(archivePath, userId, spaceId, uploadMethod, 
                        benchmarkTypeId, downloadable, priority, archiveSize);
    }
    
    /**
     * Enqueues a new upload job with explicit archive size for SJF scheduling.
     */
    public static long enqueueJob(String archivePath, int userId, int spaceId, 
                                  String uploadMethod, int benchmarkTypeId, 
                                  boolean downloadable, int priority, long archiveSize) {
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet keys = null;
        
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(INSERT_JOB_SQL, Statement.RETURN_GENERATED_KEYS);
            
            ps.setString(1, archivePath);
            ps.setInt(2, userId);
            ps.setInt(3, spaceId);
            ps.setString(4, uploadMethod);
            ps.setInt(5, benchmarkTypeId);
            ps.setBoolean(6, downloadable);
            ps.setInt(7, priority);
            ps.setLong(8, archiveSize);
            
            int affected = ps.executeUpdate();
            if (affected == 0) {
                log.error("Failed to insert upload job");
                return -1;
            }
            
            keys = ps.getGeneratedKeys();
            if (keys.next()) {
                long jobId = keys.getLong(1);
                log.info("enqueueJob", "Enqueued upload job " + jobId + " for user " + userId + " (priority: " + priority + ")");
                return jobId;
            }
            
            return -1;
            
        } catch (SQLException e) {
            log.error("enqueueJob", "Failed to enqueue upload job", e);
            return -1;
        } finally {
            Common.safeClose(keys);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
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
                UploadJob job = new UploadJob();
                job.setId(rs.getLong("id"));
                job.setArchivePath(rs.getString("archive_path"));
                job.setUserId(rs.getInt("user_id"));
                job.setSpaceId(rs.getInt("space_id"));
                job.setUploadMethod(rs.getString("upload_method"));
                job.setBenchmarkTypeId(rs.getInt("benchmark_type_id"));
                job.setDownloadable(rs.getBoolean("downloadable"));
                job.setPriority(rs.getInt("priority"));
                job.setStatus(rs.getString("status"));
                job.setTotalFilesFound(rs.getInt("total_files_found"));
                job.setTotalFilesProcessed(rs.getInt("total_files_processed"));
                job.setTotalSpacesCreated(rs.getInt("total_spaces_created"));
                job.setRetryCount(rs.getInt("retry_count"));
                job.setMaxRetries(rs.getInt("max_retries"));
                job.setCreatedAt(rs.getTimestamp("created_at"));
                job.setStartedAt(rs.getTimestamp("started_at"));
                job.setLastHeartbeat(rs.getTimestamp("last_heartbeat"));
                job.setLastProcessedPath(rs.getString("last_processed_path"));
                job.setLastProcessedIndex(rs.getInt("last_processed_index"));
                job.setExtractPath(rs.getString("extract_path"));
                
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
        Connection con = null;
        CallableStatement cs = null;
        
        try {
            con = Common.getConnection();
            cs = con.prepareCall(COMPLETE_JOB_SQL);
            cs.setLong(1, jobId);
            cs.execute();
            
            log.info("completeJob", "Completed upload job " + jobId);
            return true;
            
        } catch (SQLException e) {
            log.error("completeJob", "Failed to complete job " + jobId, e);
            return false;
        } finally {
            Common.safeClose(cs);
            Common.safeClose(con);
        }
    }
    
    /**
     * Marks a job as failed with an error message.
     * 
     * @param jobId The job ID
     * @param errorMessage Error description
     * @return true if successful, false otherwise
     */
    public static boolean failJob(long jobId, String errorMessage) {
        Connection con = null;
        CallableStatement cs = null;
        
        try {
            con = Common.getConnection();
            cs = con.prepareCall(FAIL_JOB_SQL);
            cs.setLong(1, jobId);
            cs.setString(2, errorMessage);
            cs.execute();
            
            log.error("failJob", "Failed upload job " + jobId + ": " + errorMessage);
            return true;
            
        } catch (SQLException e) {
            log.error("failJob", "Failed to mark job " + jobId + " as failed", e);
            return false;
        } finally {
            Common.safeClose(cs);
            Common.safeClose(con);
        }
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
        Connection con = null;
        CallableStatement cs = null;
        
        try {
            con = Common.getConnection();
            cs = con.prepareCall(RETRY_JOB_SQL);
            cs.setLong(1, jobId);
            
            int affected = cs.executeUpdate();
            if (affected > 0) {
                log.info("retryJob", "Scheduled retry for job " + jobId);
                return true;
            }
            
            return false;
            
        } catch (SQLException e) {
            log.error("retryJob", "Failed to retry job " + jobId, e);
            return false;
        } finally {
            Common.safeClose(cs);
            Common.safeClose(con);
        }
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
        Connection con = null;
        PreparedStatement ps = null;
        
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(CANCEL_JOB_SQL);
            ps.setLong(1, jobId);
            ps.setInt(2, userId);
            
            int affected = ps.executeUpdate();
            if (affected > 0) {
                log.info("cancelJob", "Cancelled job " + jobId + " by user " + userId);
                return true;
            }
            
            return false;
            
        } catch (SQLException e) {
            log.error("cancelJob", "Failed to cancel job " + jobId, e);
            return false;
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
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
        return job;
    }
}