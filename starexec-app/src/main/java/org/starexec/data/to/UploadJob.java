package org.starexec.data.to;

import java.sql.Timestamp;

/**
 * Represents an asynchronous upload job in the queue.
 */
public class UploadJob {
    private long id;
    private String archivePath;
    private int userId;
    private int spaceId;
    private String uploadMethod; // 'convert' or 'dump'
    private int benchmarkTypeId;
    private boolean downloadable;
    
    private String status; // PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED
    private int totalFilesFound;
    private int totalFilesProcessed;
    private int totalSpacesCreated;
    private String errorMessage;
    private int retryCount;
    private int maxRetries;
    
    private Timestamp createdAt;
    private Timestamp startedAt;
    private Timestamp completedAt;
    private Timestamp lastHeartbeat;
    private int priority;
    private Timestamp scheduledAfter;
    
    // Idempotency tracking
    private String lastProcessedPath;
    private int lastProcessedIndex;
    private String extractPath;
    
    // Getters and setters
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    
    public String getArchivePath() { return archivePath; }
    public void setArchivePath(String archivePath) { this.archivePath = archivePath; }
    
    public int getUserId() { return userId; }
    public void setUserId(int userId) { this.userId = userId; }
    
    public int getSpaceId() { return spaceId; }
    public void setSpaceId(int spaceId) { this.spaceId = spaceId; }
    
    public String getUploadMethod() { return uploadMethod; }
    public void setUploadMethod(String uploadMethod) { this.uploadMethod = uploadMethod; }
    
    public int getBenchmarkTypeId() { return benchmarkTypeId; }
    public void setBenchmarkTypeId(int benchmarkTypeId) { this.benchmarkTypeId = benchmarkTypeId; }
    
    public boolean isDownloadable() { return downloadable; }
    public void setDownloadable(boolean downloadable) { this.downloadable = downloadable; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public int getTotalFilesFound() { return totalFilesFound; }
    public void setTotalFilesFound(int totalFilesFound) { this.totalFilesFound = totalFilesFound; }
    
    public int getTotalFilesProcessed() { return totalFilesProcessed; }
    public void setTotalFilesProcessed(int totalFilesProcessed) { this.totalFilesProcessed = totalFilesProcessed; }
    
    public int getTotalSpacesCreated() { return totalSpacesCreated; }
    public void setTotalSpacesCreated(int totalSpacesCreated) { this.totalSpacesCreated = totalSpacesCreated; }
    
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
    
    public Timestamp getStartedAt() { return startedAt; }
    public void setStartedAt(Timestamp startedAt) { this.startedAt = startedAt; }
    
    public Timestamp getCompletedAt() { return completedAt; }
    public void setCompletedAt(Timestamp completedAt) { this.completedAt = completedAt; }
    
    public Timestamp getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(Timestamp lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
    
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    
    public Timestamp getScheduledAfter() { return scheduledAfter; }
    public void setScheduledAfter(Timestamp scheduledAfter) { this.scheduledAfter = scheduledAfter; }
    
    public String getLastProcessedPath() { return lastProcessedPath; }
    public void setLastProcessedPath(String lastProcessedPath) { this.lastProcessedPath = lastProcessedPath; }
    
    public int getLastProcessedIndex() { return lastProcessedIndex; }
    public void setLastProcessedIndex(int lastProcessedIndex) { this.lastProcessedIndex = lastProcessedIndex; }
    
    public String getExtractPath() { return extractPath; }
    public void setExtractPath(String extractPath) { this.extractPath = extractPath; }
    
    /**
     * Checks if this is a retry (has previous progress).
     */
    public boolean isRetry() {
        return lastProcessedIndex > 0 || (lastProcessedPath != null && !lastProcessedPath.isEmpty());
    }
    
    /**
     * Checks if the job is in a terminal state.
     */
    public boolean isTerminal() {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }
    
    /**
     * Checks if the job appears to be stuck (no heartbeat in 5 minutes).
     */
    public boolean isStuck() {
        if (lastHeartbeat == null || !"PROCESSING".equals(status)) {
            return false;
        }
        long minutesSinceHeartbeat = (System.currentTimeMillis() - lastHeartbeat.getTime()) / (1000 * 60);
        return minutesSinceHeartbeat > 5;
    }
    
    /**
     * Gets the progress percentage (0-100).
     */
    public int getProgressPercentage() {
        if (totalFilesFound == 0) {
            return 0;
        }
        return (int) ((totalFilesProcessed * 100.0) / totalFilesFound);
    }
    
    @Override
    public String toString() {
        return String.format(
            "UploadJob{id=%d, user=%d, space=%d, status=%s, progress=%d/%d (%d%%)}",
            id, userId, spaceId, status, totalFilesProcessed, totalFilesFound, getProgressPercentage()
        );
    }
}