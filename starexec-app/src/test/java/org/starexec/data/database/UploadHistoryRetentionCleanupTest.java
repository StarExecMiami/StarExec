package org.starexec.data.database;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.config.EnvironmentConfig;
import org.starexec.data.to.BenchmarkUploadStatus;
import org.starexec.data.to.UploadArtifact;
import org.starexec.servlets.UploadArtifactCleanupWorker;
import org.starexec.util.UploadArtifactPathGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies PVC-safe upload deletion, legacy incomplete-age guardrails, and TTL history purging.
 */
public class UploadHistoryRetentionCleanupTest {

    @Test
    public void defaultHistoryRetentionDaysIsThirty() {
        if (System.getenv("STAREXEC_UPLOAD_HISTORY_RETENTION_DAYS") == null) {
            assertEquals(30, EnvironmentConfig.getUploadHistoryRetentionDays());
        }
    }

    @Test
    public void deleteTerminalJobRejectsActivePendingOrProcessingJobs() throws Exception {
        for (String activeStatus : List.of("PENDING", "PROCESSING")) {
            Connection con = mock(Connection.class);
            PreparedStatement statusPs = mock(PreparedStatement.class);
            ResultSet statusRs = mock(ResultSet.class);
            when(con.prepareStatement(anyString())).thenReturn(statusPs);
            when(statusPs.executeQuery()).thenReturn(statusRs);
            when(statusRs.next()).thenReturn(true);
            when(statusRs.getString(1)).thenReturn(activeStatus);

            try (MockedStatic<UploadArtifactCleanupRepository> repo =
                     Mockito.mockStatic(UploadArtifactCleanupRepository.class)) {
                UploadJobQueue.DeleteOutcome outcome = UploadJobQueue.deleteTerminalJob(con, 42L, null);

                assertEquals(UploadJobQueue.DeleteOutcome.ACTIVE_UPLOAD, outcome);
                repo.verifyNoInteractions();
            }
        }
    }

    @Test
    public void deleteTerminalJobCleansPvcArtifactsBeforeDeletingJobRow() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path archive = root.resolve("3/20260704/upload-session-ok/AllProblems.tgz");
        Files.createDirectories(archive.getParent());
        Files.writeString(archive, "archive");
        UploadArtifact artifact = sourceArchiveArtifact(11L, 42L, archive);

        Connection con = mock(Connection.class);
        PreparedStatement statusPs = mock(PreparedStatement.class);
        PreparedStatement deletePs = mock(PreparedStatement.class);
        ResultSet statusRs = mock(ResultSet.class);
        when(con.prepareStatement(anyString())).thenReturn(statusPs, deletePs);
        when(statusPs.executeQuery()).thenReturn(statusRs);
        when(statusRs.next()).thenReturn(true);
        when(statusRs.getString(1)).thenReturn("FAILED");
        when(deletePs.executeUpdate()).thenReturn(1);

        try (MockedStatic<UploadArtifactCleanupRepository> repo =
                 Mockito.mockStatic(UploadArtifactCleanupRepository.class)) {
            repo.when(() -> UploadArtifactCleanupRepository.countUncleanedArtifactsForJob(con, 42L))
                .thenReturn(1, 0);
            repo.when(() -> UploadArtifactCleanupRepository.claimArtifactsForJob(con, 42L))
                .thenReturn(List.of(artifact));
            repo.when(() -> UploadArtifactCleanupRepository.isArtifactPathOwnedByDatabaseRow(con, artifact))
                .thenReturn(true);
            repo.when(() -> UploadArtifactCleanupRepository.hasBlockingReferences(con, artifact))
                .thenReturn(false);

            UploadJobQueue.DeleteOutcome outcome =
                UploadJobQueue.deleteTerminalJob(con, 42L, new UploadArtifactPathGuard(root));

            assertEquals(UploadJobQueue.DeleteOutcome.DELETED, outcome);
            assertFalse(Files.exists(archive));
            InOrder order = Mockito.inOrder(deletePs);
            repo.verify(() -> UploadArtifactCleanupRepository.markDeleted(con, 11L, false));
            order.verify(deletePs).setLong(1, 42L);
            order.verify(deletePs).executeUpdate();
        }
    }

    @Test
    public void deleteTerminalJobPreservesDbAndArtifactRowsWhenPvcCleanupFails() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path outside = Files.createTempFile("outside-upload-target-", ".tgz");
        UploadArtifact invalidArtifact = sourceArchiveArtifact(12L, 42L, outside);

        Connection con = mock(Connection.class);
        PreparedStatement statusPs = mock(PreparedStatement.class);
        ResultSet statusRs = mock(ResultSet.class);
        when(con.prepareStatement(anyString())).thenReturn(statusPs);
        when(statusPs.executeQuery()).thenReturn(statusRs);
        when(statusRs.next()).thenReturn(true);
        when(statusRs.getString(1)).thenReturn("COMPLETED");

        try (MockedStatic<UploadArtifactCleanupRepository> repo =
                 Mockito.mockStatic(UploadArtifactCleanupRepository.class)) {
            repo.when(() -> UploadArtifactCleanupRepository.countUncleanedArtifactsForJob(con, 42L))
                .thenReturn(1);
            repo.when(() -> UploadArtifactCleanupRepository.claimArtifactsForJob(con, 42L))
                .thenReturn(List.of(invalidArtifact));
            repo.when(() -> UploadArtifactCleanupRepository.isArtifactPathOwnedByDatabaseRow(con, invalidArtifact))
                .thenReturn(true);
            repo.when(() -> UploadArtifactCleanupRepository.hasBlockingReferences(con, invalidArtifact))
                .thenReturn(false);

            UploadJobQueue.DeleteOutcome outcome =
                UploadJobQueue.deleteTerminalJob(con, 42L, new UploadArtifactPathGuard(root));

            assertEquals(UploadJobQueue.DeleteOutcome.PVC_CLEANUP_FAILED, outcome);
            assertTrue(Files.exists(outside), "Escaped path must not be deleted");
            repo.verify(() -> UploadArtifactCleanupRepository.markFailed(
                Mockito.eq(con),
                Mockito.eq(12L),
                Mockito.eq("INVALID_PATH"),
                Mockito.contains("outside benchmark root")
            ));
            // Only the SELECT status statement may be prepared; DELETE must never run.
            Mockito.verify(con, Mockito.times(1)).prepareStatement(anyString());
        }
    }

    @Test
    public void legacyUploadOneHourEligibilityRespectsCompletionAndAge() {
        BenchmarkUploadStatus completed = new BenchmarkUploadStatus();
        completed.setEverythingComplete(true);
        completed.setUploadDate(Timestamp.from(Instant.now().minus(5, ChronoUnit.MINUTES)));
        assertTrue(Uploads.isLegacyUploadEligibleForDeletion(completed));

        BenchmarkUploadStatus freshIncomplete = new BenchmarkUploadStatus();
        freshIncomplete.setEverythingComplete(false);
        freshIncomplete.setUploadDate(Timestamp.from(Instant.now().minus(30, ChronoUnit.MINUTES)));
        assertFalse(Uploads.isLegacyUploadEligibleForDeletion(freshIncomplete));

        BenchmarkUploadStatus staleIncomplete = new BenchmarkUploadStatus();
        staleIncomplete.setEverythingComplete(false);
        staleIncomplete.setUploadDate(Timestamp.from(Instant.now().minus(61, ChronoUnit.MINUTES)));
        assertTrue(Uploads.isLegacyUploadEligibleForDeletion(staleIncomplete));
    }

    @Test
    public void cleanupWorkerPurgesExpiredHistoryWhenRetentionPositiveAndSkipsWhenZero() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Connection con = mock(Connection.class);

        try (MockedStatic<UploadArtifactCleanupRepository> repo =
                 Mockito.mockStatic(UploadArtifactCleanupRepository.class);
             MockedStatic<Uploads> uploads = Mockito.mockStatic(Uploads.class)) {
            repo.when(() -> UploadArtifactCleanupRepository.claimDueArtifacts(con, 25))
                .thenReturn(List.of());
            repo.when(() -> UploadArtifactCleanupRepository.purgeExpiredTerminalUploadJobs(con, 30, 25))
                .thenReturn(3);
            uploads.when(() -> Uploads.purgeExpiredLegacyUploads(con, 30, 25))
                .thenReturn(2);

            TestCleanupWorker enabledWorker = new TestCleanupWorker(25, new UploadArtifactPathGuard(root), 30);
            enabledWorker.runForTest(con);

            repo.verify(() -> UploadArtifactCleanupRepository.purgeExpiredTerminalUploadJobs(con, 30, 25));
            uploads.verify(() -> Uploads.purgeExpiredLegacyUploads(con, 30, 25));

            TestCleanupWorker disabledWorker = new TestCleanupWorker(25, new UploadArtifactPathGuard(root), 0);
            disabledWorker.runForTest(con);

            repo.verify(() -> UploadArtifactCleanupRepository.claimDueArtifacts(con, 25), Mockito.times(2));
            repo.verifyNoMoreInteractions();
            uploads.verifyNoMoreInteractions();
        }
    }

    private static UploadArtifact sourceArchiveArtifact(long id, long jobId, Path path) {
        UploadArtifact artifact = new UploadArtifact();
        artifact.setId(id);
        artifact.setJobId(jobId);
        artifact.setArtifactRole("SOURCE_ARCHIVE");
        artifact.setPathKind("FILE");
        artifact.setPath(path.toString());
        return artifact;
    }

    private static class TestCleanupWorker extends UploadArtifactCleanupWorker {
        TestCleanupWorker(int batchSize, UploadArtifactPathGuard guard, int historyRetentionDays) {
            super(batchSize, guard, historyRetentionDays);
        }

        void runForTest(Connection con) throws Exception {
            runSweep(con);
        }
    }
}
