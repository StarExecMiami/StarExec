package org.starexec.data.database;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.to.UploadArtifact;
import org.starexec.data.to.UploadJob;
import org.starexec.util.UploadArtifactPathGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class UploadJobQueueCleanupTests {

    @Test
    public void enqueueJobInTransactionCreatesSourceArchiveArtifact() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet keys = mock(ResultSet.class);
        UploadJob.UploadJobRequest request = new UploadJob.UploadJobRequest.Builder()
            .archivePath("/tmp/Benchmarks/3/upload-session-a/archive.tgz")
            .userId(3)
            .spaceId(4)
            .uploadSessionId(77L)
            .build();

        when(connection.prepareStatement(anyString(), Mockito.eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        when(statement.getGeneratedKeys()).thenReturn(keys);
        when(keys.next()).thenReturn(true);
        when(keys.getLong(1)).thenReturn(42L);

        try (MockedStatic<UploadArtifactCleanupRepository> artifacts = Mockito.mockStatic(UploadArtifactCleanupRepository.class)) {
            long jobId = UploadJobQueue.enqueueJobInTransaction(connection, request);

            assertEquals(42L, jobId);
            artifacts.verify(() -> UploadArtifactCleanupRepository.createSourceArchiveArtifact(
                connection,
                77L,
                42L,
                "/tmp/Benchmarks/3/upload-session-a/archive.tgz"
            ));
        }
    }

    @Test
    public void enqueueJobInTransactionCreatesSourceArchiveArtifactWithoutUploadSession() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet keys = mock(ResultSet.class);
        UploadJob.UploadJobRequest request = new UploadJob.UploadJobRequest.Builder()
            .archivePath("/tmp/Benchmarks/3/20260705/AllProblems.tgz")
            .userId(3)
            .spaceId(4)
            .build();

        when(connection.prepareStatement(anyString(), Mockito.eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        when(statement.getGeneratedKeys()).thenReturn(keys);
        when(keys.next()).thenReturn(true);
        when(keys.getLong(1)).thenReturn(43L);

        try (MockedStatic<UploadArtifactCleanupRepository> artifacts = Mockito.mockStatic(UploadArtifactCleanupRepository.class)) {
            long jobId = UploadJobQueue.enqueueJobInTransaction(connection, request);

            assertEquals(43L, jobId);
            artifacts.verify(() -> UploadArtifactCleanupRepository.createSourceArchiveArtifact(
                connection,
                null,
                43L,
                "/tmp/Benchmarks/3/20260705/AllProblems.tgz"
            ));
        }
    }

    @Test
    public void retryJobInTransactionReturnsFalseWhenNoRetryArtifactExists() throws Exception {
        Connection connection = mock(Connection.class);

        try (MockedStatic<UploadArtifactCleanupRepository> artifacts = Mockito.mockStatic(UploadArtifactCleanupRepository.class)) {
            artifacts.when(() -> UploadArtifactCleanupRepository.findRetryableSourceArchive(connection, 42L))
                .thenReturn(Optional.empty());

            assertFalse(UploadJobQueue.retryJobInTransaction(
                connection,
                42L,
                new UploadArtifactPathGuard(Files.createTempDirectory("bench-root-"))
            ));
        }
    }

    @Test
    public void retryJobInTransactionMarksMissingArtifactAndFails() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path archive = root.resolve("3/20260704/upload-session-missing/AllProblems.tgz");
        UploadArtifact artifact = sourceArchive(5L, archive);
        Connection connection = mock(Connection.class);

        try (MockedStatic<UploadArtifactCleanupRepository> artifacts = Mockito.mockStatic(UploadArtifactCleanupRepository.class)) {
            artifacts.when(() -> UploadArtifactCleanupRepository.findRetryableSourceArchive(connection, 42L))
                .thenReturn(Optional.of(artifact));
            artifacts.when(() -> UploadArtifactCleanupRepository.isArtifactPathOwnedByDatabaseRow(connection, artifact))
                .thenReturn(true);

            assertFalse(UploadJobQueue.retryJobInTransaction(connection, 42L, new UploadArtifactPathGuard(root)));

            artifacts.verify(() -> UploadArtifactCleanupRepository.markDeleted(connection, 5L, true));
            artifacts.verify(
                () -> UploadArtifactCleanupRepository.resetSourceArchiveRetention(connection, 42L),
                Mockito.never()
            );
        }
    }

    @Test
    public void retryJobInTransactionMarksInvalidArtifactPathAndFails() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path outside = Files.createTempFile("outside-upload-artifact-", ".tgz");
        UploadArtifact artifact = sourceArchive(7L, outside);
        Connection connection = mock(Connection.class);

        try (MockedStatic<UploadArtifactCleanupRepository> artifacts = Mockito.mockStatic(UploadArtifactCleanupRepository.class)) {
            artifacts.when(() -> UploadArtifactCleanupRepository.findRetryableSourceArchive(connection, 42L))
                .thenReturn(Optional.of(artifact));
            artifacts.when(() -> UploadArtifactCleanupRepository.isArtifactPathOwnedByDatabaseRow(connection, artifact))
                .thenReturn(true);

            assertFalse(UploadJobQueue.retryJobInTransaction(connection, 42L, new UploadArtifactPathGuard(root)));

            artifacts.verify(() -> UploadArtifactCleanupRepository.markFailed(
                Mockito.eq(connection),
                Mockito.eq(7L),
                Mockito.eq("INVALID_PATH"),
                Mockito.contains("outside benchmark root")
            ));
            artifacts.verify(
                () -> UploadArtifactCleanupRepository.resetSourceArchiveRetention(connection, 42L),
                Mockito.never()
            );
        }
    }

    @Test
    public void retryJobInTransactionSchedulesRetryAndResetsRetentionWhenArtifactExists() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path archive = root.resolve("3/20260704/upload-session-present/AllProblems.tgz");
        Files.createDirectories(archive.getParent());
        Files.writeString(archive, "archive");
        UploadArtifact artifact = sourceArchive(6L, archive);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        try (MockedStatic<UploadArtifactCleanupRepository> artifacts = Mockito.mockStatic(UploadArtifactCleanupRepository.class)) {
            artifacts.when(() -> UploadArtifactCleanupRepository.findRetryableSourceArchive(connection, 42L))
                .thenReturn(Optional.of(artifact));
            artifacts.when(() -> UploadArtifactCleanupRepository.isArtifactPathOwnedByDatabaseRow(connection, artifact))
                .thenReturn(true);

            assertTrue(UploadJobQueue.retryJobInTransaction(connection, 42L, new UploadArtifactPathGuard(root)));

            verify(statement).setLong(1, 42L);
            verify(statement).executeUpdate();
            artifacts.verify(() -> UploadArtifactCleanupRepository.resetSourceArchiveRetention(connection, 42L));
        }
    }

    @Test
    public void finalizeSessionWithUploadJobInTransactionThrowsWhenCompleteSessionFails() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement lockSession = mock(PreparedStatement.class);
        PreparedStatement updateStaging = mock(PreparedStatement.class);
        PreparedStatement insertJob = mock(PreparedStatement.class);
        PreparedStatement completeSession = mock(PreparedStatement.class);
        ResultSet lockedSession = mock(ResultSet.class);
        ResultSet keys = mock(ResultSet.class);
        UploadJob.UploadJobRequest request = new UploadJob.UploadJobRequest.Builder()
            .archivePath("/tmp/Benchmarks/3/20260705/upload-session-a/AllProblems.tgz")
            .userId(3)
            .spaceId(4)
            .uploadSessionId(77L)
            .build();

        when(connection.prepareStatement(anyString())).thenReturn(lockSession, updateStaging, completeSession);
        when(connection.prepareStatement(anyString(), Mockito.eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(insertJob);
        when(lockSession.executeQuery()).thenReturn(lockedSession);
        when(lockedSession.next()).thenReturn(true);
        when(lockedSession.getLong("job_id")).thenReturn(0L);
        when(lockedSession.wasNull()).thenReturn(true);
        when(lockedSession.getString("status")).thenReturn("FINALIZING");
        when(updateStaging.executeUpdate()).thenReturn(1);
        when(insertJob.executeUpdate()).thenReturn(1);
        when(insertJob.getGeneratedKeys()).thenReturn(keys);
        when(keys.next()).thenReturn(true);
        when(keys.getLong(1)).thenReturn(44L);
        when(completeSession.executeUpdate()).thenReturn(0);

        try (MockedStatic<UploadArtifactCleanupRepository> artifacts = Mockito.mockStatic(UploadArtifactCleanupRepository.class)) {
            try {
                UploadSessions.finalizeSessionWithUploadJobInTransaction(
                    connection,
                    77L,
                    "/tmp/Benchmarks/3/20260705/upload-session-a/AllProblems.tgz",
                    request
                );
                fail("completeSession failure should abort session/job finalization");
            } catch (SQLException expected) {
                assertTrue(expected.getMessage().contains("Failed to complete upload session"));
            }

            artifacts.verify(() -> UploadArtifactCleanupRepository.createSourceArchiveArtifact(
                connection,
                77L,
                44L,
                "/tmp/Benchmarks/3/20260705/upload-session-a/AllProblems.tgz"
            ));
            verify(completeSession).executeUpdate();
        }
    }

    private UploadArtifact sourceArchive(long id, Path archive) {
        UploadArtifact artifact = new UploadArtifact();
        artifact.setId(id);
        artifact.setJobId(42L);
        artifact.setArtifactRole("SOURCE_ARCHIVE");
        artifact.setPathKind("FILE");
        artifact.setPath(archive.toString());
        return artifact;
    }
}
