package org.starexec.servlets;

import org.starexec.config.EnvironmentConfig;
import org.starexec.data.database.UploadArtifactCleanupRepository;
import org.starexec.data.database.Uploads;
import org.starexec.data.to.UploadArtifact;
import org.starexec.logger.StarLogger;
import org.starexec.util.UploadArtifactFileSystem;
import org.starexec.util.UploadArtifactPathGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Safely deletes expired upload-owned artifacts selected by database state.
 */
public class UploadArtifactCleanupWorker implements Runnable {
    private static final StarLogger log = StarLogger.getLogger(UploadArtifactCleanupWorker.class);

    private final int batchSize;
    private final UploadArtifactPathGuard pathGuard;
    private final int historyRetentionDays;

    public UploadArtifactCleanupWorker(int batchSize) throws IOException {
        this(batchSize, new UploadArtifactPathGuard(), EnvironmentConfig.getUploadHistoryRetentionDays());
    }

    UploadArtifactCleanupWorker(int batchSize, UploadArtifactPathGuard pathGuard) {
        this(batchSize, pathGuard, 0);
    }

    protected UploadArtifactCleanupWorker(int batchSize, UploadArtifactPathGuard pathGuard, int historyRetentionDays) {
        this.batchSize = Math.max(1, batchSize);
        this.pathGuard = pathGuard;
        this.historyRetentionDays = Math.max(0, historyRetentionDays);
    }

    @Override
    public void run() {
        try {
            CleanupSummary summary = UploadArtifactCleanupRepository.withCleanupLock(this::runSweep);
            if (summary == null) {
                log.info("run", "Skipping upload artifact cleanup; another instance holds the advisory lock");
                return;
            }
            log.info("run", "Upload artifact cleanup summary: claimed=" + summary.claimed +
                ", deleted=" + summary.deleted + ", missing=" + summary.missing +
                ", blocked=" + summary.blocked + ", failed=" + summary.failed +
                ", asyncJobsPurged=" + summary.asyncJobsPurged +
                ", legacyUploadsPurged=" + summary.legacyUploadsPurged);
        } catch (Exception e) {
            log.error("run", "Upload artifact cleanup sweep failed", e);
        }
    }

    protected CleanupSummary runSweep(Connection con) throws SQLException {
        List<UploadArtifact> artifacts = UploadArtifactCleanupRepository.claimDueArtifacts(con, batchSize);
        CleanupSummary summary = new CleanupSummary();
        summary.claimed = artifacts.size();
        for (UploadArtifact artifact : artifacts) {
            cleanArtifact(con, artifact, pathGuard, summary);
        }
        if (historyRetentionDays > 0) {
            summary.asyncJobsPurged = UploadArtifactCleanupRepository.purgeExpiredTerminalUploadJobs(
                con,
                historyRetentionDays,
                batchSize
            );
            summary.legacyUploadsPurged = Uploads.purgeExpiredLegacyUploads(
                con,
                historyRetentionDays,
                batchSize
            );
        }
        return summary;
    }

    /**
     * Attempts safe PVC cleanup for all uncleaned artifacts owned by a terminal
     * upload job before deleting its database row.
     *
     * @return true only when zero uncleaned artifacts remain for {@code jobId}
     */
    public static boolean cleanArtifactsForJob(Connection con, long jobId, UploadArtifactPathGuard guard)
        throws SQLException {
        int remainingBefore = UploadArtifactCleanupRepository.countUncleanedArtifactsForJob(con, jobId);
        if (remainingBefore == 0) {
            return true;
        }
        if (guard == null) {
            return false;
        }

        List<UploadArtifact> artifacts = UploadArtifactCleanupRepository.claimArtifactsForJob(con, jobId);
        CleanupSummary summary = new CleanupSummary();
        for (UploadArtifact artifact : artifacts) {
            cleanArtifact(con, artifact, guard, summary);
        }
        if (summary.failed > 0 || summary.blocked > 0) {
            return false;
        }
        return UploadArtifactCleanupRepository.countUncleanedArtifactsForJob(con, jobId) == 0;
    }

    private static void cleanArtifact(
        Connection con,
        UploadArtifact artifact,
        UploadArtifactPathGuard guard,
        CleanupSummary summary
    ) throws SQLException {
        try {
            if (!UploadArtifactCleanupRepository.isArtifactPathOwnedByDatabaseRow(con, artifact)) {
                UploadArtifactCleanupRepository.markFailed(
                    con,
                    artifact.getId(),
                    "INVALID_PATH",
                    "Artifact path is not owned by its upload job/session row"
                );
                summary.failed++;
                return;
            }

            if (UploadArtifactCleanupRepository.hasBlockingReferences(con, artifact)) {
                UploadArtifactCleanupRepository.markFailed(con, artifact.getId(), "BLOCKED_REFERENCED",
                    "Artifact still has database references");
                summary.blocked++;
                return;
            }

            Path path;
            try {
                path = guard.validateArtifact(artifact);
            } catch (IOException e) {
                UploadArtifactCleanupRepository.markFailed(con, artifact.getId(), "INVALID_PATH", e.getMessage());
                summary.failed++;
                return;
            }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                UploadArtifactCleanupRepository.markDeleted(con, artifact.getId(), true);
                summary.missing++;
                return;
            }

            deleteArtifact(path, artifact);
            UploadArtifactCleanupRepository.markDeleted(con, artifact.getId(), false);
            summary.deleted++;
        } catch (Exception e) {
            UploadArtifactCleanupRepository.markFailed(con, artifact.getId(), "DELETE_FAILED", e.getMessage());
            log.warn("cleanArtifact", "Failed to clean upload artifact " + artifact.getId() +
                " at " + artifact.getPath() + ": " + e.getMessage());
            summary.failed++;
        }
    }

    private static void deleteArtifact(Path path, UploadArtifact artifact) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("refusing to delete symlink artifact: " + path);
        }

        if ("DIRECTORY".equals(artifact.getPathKind())) {
            UploadArtifactFileSystem.deleteDirectoryWithoutFollowingLinks(path);
        } else {
            UploadArtifactFileSystem.deleteFileWithoutFollowingLinks(path);
        }
    }

    public static class CleanupSummary {
        int claimed;
        int deleted;
        int missing;
        int blocked;
        int failed;
        int asyncJobsPurged;
        int legacyUploadsPurged;
    }
}
