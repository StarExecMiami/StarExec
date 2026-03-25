package org.starexec.data.to;

/**
 * Request payload for creating a resumable benchmark upload session.
 */
public class UploadSessionCreateRequest {
    private String fileName;
    private long totalBytes;
    private int spaceId;
    private String uploadMethod;
    private int benchmarkTypeId;
    private boolean downloadable;
    private boolean hasDependencies;
    private Integer depRootSpaceId;
    private boolean linked;

    public String getFileName() {
        return fileName;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public int getSpaceId() {
        return spaceId;
    }

    public String getUploadMethod() {
        return uploadMethod;
    }

    public int getBenchmarkTypeId() {
        return benchmarkTypeId;
    }

    public boolean isDownloadable() {
        return downloadable;
    }

    public boolean isHasDependencies() {
        return hasDependencies;
    }

    public Integer getDepRootSpaceId() {
        return depRootSpaceId;
    }

    public boolean isLinked() {
        return linked;
    }
}
