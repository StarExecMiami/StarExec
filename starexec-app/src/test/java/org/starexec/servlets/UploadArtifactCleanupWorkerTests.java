package org.starexec.servlets;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.UploadArtifactCleanupRepository;
import org.starexec.data.to.UploadArtifact;
import org.starexec.util.UploadArtifactPathGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class UploadArtifactCleanupWorkerTests {

    @Test
    public void runSweepDeletesFileArtifactAndMarksDeleted() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path archive = root.resolve("3/20260704/upload-session-ok/AllProblems.tgz");
        Files.createDirectories(archive.getParent());
        Files.writeString(archive, "archive");
        UploadArtifact artifact = artifact(1L, "FILE", archive);
        Connection connection = mock(Connection.class);

        try (MockedStatic<UploadArtifactCleanupRepository> repo = Mockito.mockStatic(UploadArtifactCleanupRepository.class)) {
            repo.when(() -> UploadArtifactCleanupRepository.claimDueArtifacts(connection, 10))
                .thenReturn(List.of(artifact));
            repo.when(() -> UploadArtifactCleanupRepository.isArtifactPathOwnedByDatabaseRow(connection, artifact))
                .thenReturn(true);
            repo.when(() -> UploadArtifactCleanupRepository.hasBlockingReferences(connection, artifact))
                .thenReturn(false);

            UploadArtifactCleanupWorker worker = new UploadArtifactCleanupWorker(10, new UploadArtifactPathGuard(root));
            UploadArtifactCleanupWorker.CleanupSummary summary = worker.runSweep(connection);

            assertFalse(Files.exists(archive));
            assertEquals(1, summary.deleted);
            repo.verify(() -> UploadArtifactCleanupRepository.markDeleted(connection, artifact.getId(), false));
        }
    }

    @Test
    public void runSweepMarksMissingArtifactAsMissing() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path archive = root.resolve("3/20260704/upload-session-missing/AllProblems.tgz");
        UploadArtifact artifact = artifact(2L, "FILE", archive);
        Connection connection = mock(Connection.class);

        try (MockedStatic<UploadArtifactCleanupRepository> repo = Mockito.mockStatic(UploadArtifactCleanupRepository.class)) {
            repo.when(() -> UploadArtifactCleanupRepository.claimDueArtifacts(connection, 10))
                .thenReturn(List.of(artifact));
            repo.when(() -> UploadArtifactCleanupRepository.isArtifactPathOwnedByDatabaseRow(connection, artifact))
                .thenReturn(true);
            repo.when(() -> UploadArtifactCleanupRepository.hasBlockingReferences(connection, artifact))
                .thenReturn(false);

            UploadArtifactCleanupWorker worker = new UploadArtifactCleanupWorker(10, new UploadArtifactPathGuard(root));
            UploadArtifactCleanupWorker.CleanupSummary summary = worker.runSweep(connection);

            assertEquals(1, summary.missing);
            repo.verify(() -> UploadArtifactCleanupRepository.markDeleted(connection, artifact.getId(), true));
        }
    }

    @Test
    public void runSweepBlocksReferencedArtifactWithoutDeleting() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path archive = root.resolve("3/20260704/upload-session-ref/AllProblems.tgz");
        Files.createDirectories(archive.getParent());
        Files.writeString(archive, "archive");
        UploadArtifact artifact = artifact(3L, "FILE", archive);
        Connection connection = mock(Connection.class);

        try (MockedStatic<UploadArtifactCleanupRepository> repo = Mockito.mockStatic(UploadArtifactCleanupRepository.class)) {
            repo.when(() -> UploadArtifactCleanupRepository.claimDueArtifacts(connection, 10))
                .thenReturn(List.of(artifact));
            repo.when(() -> UploadArtifactCleanupRepository.isArtifactPathOwnedByDatabaseRow(connection, artifact))
                .thenReturn(true);
            repo.when(() -> UploadArtifactCleanupRepository.hasBlockingReferences(connection, artifact))
                .thenReturn(true);

            UploadArtifactCleanupWorker worker = new UploadArtifactCleanupWorker(10, new UploadArtifactPathGuard(root));
            UploadArtifactCleanupWorker.CleanupSummary summary = worker.runSweep(connection);

            assertEquals(1, summary.blocked);
            repo.verify(() -> UploadArtifactCleanupRepository.markFailed(
                connection,
                artifact.getId(),
                "BLOCKED_REFERENCED",
                "Artifact still has database references"
            ));
        }
    }

    @Test
    public void runSweepRejectsDirectoryArtifactContainingSymlink() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path directory = root.resolve("3/20260704/upload-session-symlink/upload_42_1.extracting");
        Files.createDirectories(directory);
        Path outside = Files.createTempFile("outside-cleanup-target-", ".txt");
        Files.createSymbolicLink(directory.resolve("escape"), outside);
        UploadArtifact artifact = artifact(4L, "DIRECTORY", directory);
        Connection connection = mock(Connection.class);

        try (MockedStatic<UploadArtifactCleanupRepository> repo = Mockito.mockStatic(UploadArtifactCleanupRepository.class)) {
            repo.when(() -> UploadArtifactCleanupRepository.claimDueArtifacts(connection, 10))
                .thenReturn(List.of(artifact));
            repo.when(() -> UploadArtifactCleanupRepository.isArtifactPathOwnedByDatabaseRow(connection, artifact))
                .thenReturn(true);
            repo.when(() -> UploadArtifactCleanupRepository.hasBlockingReferences(connection, artifact))
                .thenReturn(false);

            UploadArtifactCleanupWorker worker = new UploadArtifactCleanupWorker(10, new UploadArtifactPathGuard(root));
            UploadArtifactCleanupWorker.CleanupSummary summary = worker.runSweep(connection);

            assertEquals(1, summary.failed);
            assertTrue("Artifact directory must remain when symlink deletion is rejected", Files.exists(directory));
            assertTrue("Symlink target must not be deleted", Files.exists(outside));
            repo.verify(() -> UploadArtifactCleanupRepository.markFailed(
                Mockito.eq(connection),
                Mockito.eq(artifact.getId()),
                Mockito.eq("DELETE_FAILED"),
                Mockito.contains("symlink")
            ));
        }
    }

    @Test
    public void runSweepDeletesSafeDirectoryArtifactAndMarksDeleted() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path directory = root.resolve("3/20260704/upload-session-dir/upload_42_1.extracting");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("file.p"), "benchmark");
        UploadArtifact artifact = artifact(5L, "DIRECTORY", directory);
        Connection connection = mock(Connection.class);

        try (MockedStatic<UploadArtifactCleanupRepository> repo = Mockito.mockStatic(UploadArtifactCleanupRepository.class)) {
            repo.when(() -> UploadArtifactCleanupRepository.claimDueArtifacts(connection, 10))
                .thenReturn(List.of(artifact));
            repo.when(() -> UploadArtifactCleanupRepository.isArtifactPathOwnedByDatabaseRow(connection, artifact))
                .thenReturn(true);
            repo.when(() -> UploadArtifactCleanupRepository.hasBlockingReferences(connection, artifact))
                .thenReturn(false);

            UploadArtifactCleanupWorker worker = new UploadArtifactCleanupWorker(10, new UploadArtifactPathGuard(root));
            UploadArtifactCleanupWorker.CleanupSummary summary = worker.runSweep(connection);

            assertFalse(Files.exists(directory));
            assertEquals(1, summary.deleted);
            repo.verify(() -> UploadArtifactCleanupRepository.markDeleted(connection, artifact.getId(), false));
        }
    }

    @Test
    public void runSweepMarksUnownedArtifactPathInvalid() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path archive = root.resolve("3/20260704/upload-session-unowned/AllProblems.tgz");
        Files.createDirectories(archive.getParent());
        Files.writeString(archive, "archive");
        UploadArtifact artifact = artifact(6L, "FILE", archive);
        Connection connection = mock(Connection.class);

        try (MockedStatic<UploadArtifactCleanupRepository> repo = Mockito.mockStatic(UploadArtifactCleanupRepository.class)) {
            repo.when(() -> UploadArtifactCleanupRepository.claimDueArtifacts(connection, 10))
                .thenReturn(List.of(artifact));
            repo.when(() -> UploadArtifactCleanupRepository.isArtifactPathOwnedByDatabaseRow(connection, artifact))
                .thenReturn(false);

            UploadArtifactCleanupWorker worker = new UploadArtifactCleanupWorker(10, new UploadArtifactPathGuard(root));
            UploadArtifactCleanupWorker.CleanupSummary summary = worker.runSweep(connection);

            assertEquals(1, summary.failed);
            assertTrue("Unowned artifact must not be deleted", Files.exists(archive));
            repo.verify(() -> UploadArtifactCleanupRepository.markFailed(
                connection,
                artifact.getId(),
                "INVALID_PATH",
                "Artifact path is not owned by its upload job/session row"
            ));
        }
    }

    private UploadArtifact artifact(long id, String pathKind, Path path) {
        UploadArtifact artifact = new UploadArtifact();
        artifact.setId(id);
        artifact.setJobId(42L);
        artifact.setArtifactRole("SOURCE_ARCHIVE");
        artifact.setPathKind(pathKind);
        artifact.setPath(path.toString());
        return artifact;
    }
}
