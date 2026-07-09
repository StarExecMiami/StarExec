package org.starexec.data.to;

import java.sql.Timestamp;

/**
 * Represents a cleanup-owned upload filesystem artifact.
 */
public class UploadArtifact {
    private long id;
    private Long sessionId;
    private Long jobId;
    private String artifactRole;
    private String pathKind;
    private String path;
    private String cleanupState;
    private Timestamp retentionUntil;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }

    public String getArtifactRole() { return artifactRole; }
    public void setArtifactRole(String artifactRole) { this.artifactRole = artifactRole; }

    public String getPathKind() { return pathKind; }
    public void setPathKind(String pathKind) { this.pathKind = pathKind; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getCleanupState() { return cleanupState; }
    public void setCleanupState(String cleanupState) { this.cleanupState = cleanupState; }

    public Timestamp getRetentionUntil() { return retentionUntil; }
    public void setRetentionUntil(Timestamp retentionUntil) { this.retentionUntil = retentionUntil; }
}
