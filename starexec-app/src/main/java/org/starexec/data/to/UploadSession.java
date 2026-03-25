package org.starexec.data.to;

import java.io.Serializable;
import java.sql.Timestamp;

/**
 * Represents a resumable browser-side benchmark upload session.
 */
public class UploadSession implements Serializable {
    private static final long serialVersionUID = 1L;

    private long id;
    private int userId;
    private int spaceId;
    private String fileName;
    private String stagingPath;
    private long totalBytes;
    private long bytesReceived;
    private int chunkSize;
    private int nextChunkIndex;
    private int totalChunks;
    private String uploadMethod;
    private int benchmarkTypeId;
    private boolean downloadable;
    private boolean hasDependencies;
    private Integer depRootSpaceId;
    private boolean linked;
    private String status;
    private Long jobId;
    private String errorMessage;
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private Timestamp completedAt;
    private Timestamp expiresAt;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public int getUserId() { return userId; }
    public void setUserId(int userId) { this.userId = userId; }

    public int getSpaceId() { return spaceId; }
    public void setSpaceId(int spaceId) { this.spaceId = spaceId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getStagingPath() { return stagingPath; }
    public void setStagingPath(String stagingPath) { this.stagingPath = stagingPath; }

    public long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }

    public long getBytesReceived() { return bytesReceived; }
    public void setBytesReceived(long bytesReceived) { this.bytesReceived = bytesReceived; }

    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

    public int getNextChunkIndex() { return nextChunkIndex; }
    public void setNextChunkIndex(int nextChunkIndex) { this.nextChunkIndex = nextChunkIndex; }

    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }

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

    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }

    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }

    public Timestamp getCompletedAt() { return completedAt; }
    public void setCompletedAt(Timestamp completedAt) { this.completedAt = completedAt; }

    public Timestamp getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Timestamp expiresAt) { this.expiresAt = expiresAt; }

    public boolean isUploadOpen() {
        return "UPLOADING".equals(status) || "READY".equals(status);
    }

    public boolean isTerminal() {
        return "COMPLETE".equals(status) || "ABORTED".equals(status) ||
            "EXPIRED".equals(status) || "FAILED".equals(status);
    }

    public int getProgressPercentage() {
        if (totalBytes <= 0) {
            return 0;
        }
        return (int)Math.min(100L, Math.round((bytesReceived * 100.0d) / totalBytes));
    }
}
