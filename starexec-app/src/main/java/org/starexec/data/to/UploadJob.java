package org.starexec.data.to;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Optional;

/**
 * Represents an asynchronous upload job in the queue.
 */
public class UploadJob {
    /**
     * Immutable request object for enqueuing a new upload job.
     * Uses the Builder pattern to handle the many parameters of an upload.
     */
    public static class UploadJobRequest implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String archivePath;
        private final int userId;
        private final int spaceId;
        private final String uploadMethod;
        private final int benchmarkTypeId;
        private final boolean downloadable;
        private final int priority;
        private final long archiveSize;
        private final boolean hasDependencies;
        private final Integer depRootSpaceId; // Nullable internally
        private final boolean linked;

        private UploadJobRequest(Builder builder) {
            this.archivePath = builder.archivePath;
            this.userId = builder.userId;
            this.spaceId = builder.spaceId;
            this.uploadMethod = builder.uploadMethod;
            this.benchmarkTypeId = builder.benchmarkTypeId;
            this.downloadable = builder.downloadable;
            this.priority = builder.priority;
            this.archiveSize = builder.archiveSize;
            this.hasDependencies = builder.hasDependencies;
            this.depRootSpaceId = builder.depRootSpaceId;
            this.linked = builder.linked;
        }

        public String getArchivePath() { return archivePath; }
        public int getUserId() { return userId; }
        public int getSpaceId() { return spaceId; }
        public String getUploadMethod() { return uploadMethod; }
        public int getBenchmarkTypeId() { return benchmarkTypeId; }
        public boolean isDownloadable() { return downloadable; }
        public int getPriority() { return priority; }
        public long getArchiveSize() { return archiveSize; }
        public boolean isHasDependencies() { return hasDependencies; }
        public Optional<Integer> getDepRootSpaceId() { return Optional.ofNullable(depRootSpaceId); }
        public boolean isLinked() { return linked; }

        public static class Builder {
            private String archivePath;
            private int userId;
            private int spaceId;
            private String uploadMethod = "convert";
            private int benchmarkTypeId;
            private boolean downloadable = true;
            private int priority = 0;
            private long archiveSize;
            private boolean hasDependencies = false;
            private Integer depRootSpaceId;
            private boolean linked = false;

            public Builder archivePath(String val) { this.archivePath = val; return this; }
            public Builder userId(int val) { this.userId = val; return this; }
            public Builder spaceId(int val) { this.spaceId = val; return this; }
            public Builder uploadMethod(String val) { this.uploadMethod = val; return this; }
            public Builder benchmarkTypeId(int val) { this.benchmarkTypeId = val; return this; }
            public Builder downloadable(boolean val) { this.downloadable = val; return this; }
            public Builder priority(int val) { this.priority = val; return this; }
            public Builder archiveSize(long val) { this.archiveSize = val; return this; }
            public Builder hasDependencies(boolean val) { this.hasDependencies = val; return this; }
            public Builder depRootSpaceId(Integer val) { this.depRootSpaceId = val; return this; }
            public Builder linked(boolean val) { this.linked = val; return this; }

            public UploadJobRequest build() {
                if (archivePath == null) throw new IllegalStateException("archivePath required");
                if (userId <= 0) throw new IllegalStateException("valid userId required");
                if (spaceId <= 0) throw new IllegalStateException("valid spaceId required");
                return new UploadJobRequest(this);
            }
        }
    }

    private long id;
    private String archivePath;
    private int userId;
    private int spaceId;
    private String uploadMethod; // 'convert' or 'dump'
    private int benchmarkTypeId;
    private boolean downloadable;
    private boolean hasDependencies;
    private Integer depRootSpaceId;
    private boolean linked;
    
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
    
    public boolean isHasDependencies() { return hasDependencies; }
    public void setHasDependencies(boolean hasDependencies) { this.hasDependencies = hasDependencies; }
    
    public Integer getDepRootSpaceId() { return depRootSpaceId; }
    public void setDepRootSpaceId(Integer depRootSpaceId) { this.depRootSpaceId = depRootSpaceId; }
    
    public boolean isLinked() { return linked; }
    public void setLinked(boolean linked) { this.linked = linked; }
    
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