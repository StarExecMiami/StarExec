package org.starexec.data.to;

import java.util.List;

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
    // The permission checkboxes checked on the upload form, by name (e.g. "addBench"). Absent when
    // the page was loaded before the form sent them.
    private List<String> spacePermissions;

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

    public List<String> getSpacePermissions() {
        return spacePermissions;
    }
}
