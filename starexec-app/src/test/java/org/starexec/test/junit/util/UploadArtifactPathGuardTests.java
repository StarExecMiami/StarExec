package org.starexec.test.junit.util;

import org.junit.Test;
import org.starexec.data.to.UploadSession;
import org.starexec.util.UploadArtifactPathGuard;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class UploadArtifactPathGuardTests {

    @Test
    public void validateAcceptsUploadSessionArtifactUnderRoot() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path archive = root.resolve("3/20260702/upload-session-abc/AllProblems.tgz");
        Files.createDirectories(archive.getParent());
        Files.createFile(archive);

        UploadArtifactPathGuard guard = new UploadArtifactPathGuard(root);

        assertEquals(archive.toAbsolutePath().normalize(), guard.validate(archive.toString()));
    }

    @Test
    public void validateAcceptsChunkDirectoryUnderRoot() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path chunks = root.resolve("3/20260702/upload-session-abc/AllProblems.tgz.part.chunks");

        UploadArtifactPathGuard guard = new UploadArtifactPathGuard(root);

        assertEquals(chunks.toAbsolutePath().normalize(), guard.validate(chunks.toString()));
    }

    @Test
    public void validateAcceptsAssemblingFileUnderRoot() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path assembling = root.resolve("3/20260702/upload-session-abc/AllProblems.tgz.part.assembling");

        UploadArtifactPathGuard guard = new UploadArtifactPathGuard(root);

        assertEquals(assembling.toAbsolutePath().normalize(), guard.validate(assembling.toString()));
    }

    @Test
    public void validateRejectsNullPath() throws Exception {
        UploadArtifactPathGuard guard = new UploadArtifactPathGuard(Files.createTempDirectory("bench-root-"));

        try {
            guard.validate(null);
            fail("null path should be rejected");
        } catch (Exception expected) {
            // Expected.
        }
    }

    @Test
    public void validateRejectsBlankPath() throws Exception {
        UploadArtifactPathGuard guard = new UploadArtifactPathGuard(Files.createTempDirectory("bench-root-"));

        try {
            guard.validate("  ");
            fail("blank path should be rejected");
        } catch (Exception expected) {
            // Expected.
        }
    }

    @Test
    public void validateRejectsPathOutsideRoot() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path outside = Files.createTempFile("upload-session-outside-", ".tgz");
        UploadArtifactPathGuard guard = new UploadArtifactPathGuard(root);

        try {
            guard.validate(outside.toString());
            fail("outside path should be rejected");
        } catch (Exception expected) {
            // Expected.
        }
    }

    @Test
    public void validateRejectsSymlinkComponent() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path target = Files.createTempDirectory("upload-target-");
        Path link = root.resolve("3/20260702/upload-session-link");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, target);
        Path candidate = link.resolve("AllProblems.tgz");

        UploadArtifactPathGuard guard = new UploadArtifactPathGuard(root);

        try {
            guard.validate(candidate.toString());
            fail("symlink component should be rejected");
        } catch (Exception expected) {
            // Expected.
        }
    }

    @Test
    public void validateRejectsNonUploadOwnedPath() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path durable = root.resolve("3/20260702/benchmark.txt");
        Files.createDirectories(durable.getParent());
        Files.createFile(durable);

        UploadArtifactPathGuard guard = new UploadArtifactPathGuard(root);

        try {
            guard.validate(durable.toString());
            fail("non-upload path should be rejected");
        } catch (Exception expected) {
            // Expected.
        }
    }

    @Test
    public void validateSourceArchiveAcceptsDirectLegacyUploadForOwningUser() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path archive = root.resolve("7/20260702/AllProblems.tgz");
        Files.createDirectories(archive.getParent());
        Files.createFile(archive);

        UploadArtifactPathGuard guard = new UploadArtifactPathGuard(root);

        assertEquals(archive.toAbsolutePath().normalize(), guard.validateSourceArchivePath(archive.toString(), 7));
    }

    @Test
    public void validateSourceArchiveAcceptsUserFileNameStartingWithUploadPrefix() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path archive = root.resolve("7/20260702/upload_examples.tgz");
        Files.createDirectories(archive.getParent());
        Files.createFile(archive);

        UploadArtifactPathGuard guard = new UploadArtifactPathGuard(root);

        assertEquals(archive.toAbsolutePath().normalize(), guard.validateSourceArchivePath(archive.toString(), 7));
    }

    @Test
    public void validateSourceArchiveRejectsWrongUser() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path archive = root.resolve("7/20260702/AllProblems.tgz");
        Files.createDirectories(archive.getParent());
        Files.createFile(archive);

        UploadArtifactPathGuard guard = new UploadArtifactPathGuard(root);

        try {
            guard.validateSourceArchivePath(archive.toString(), 8);
            fail("source archive path should be bound to the upload job user");
        } catch (Exception expected) {
            // Expected.
        }
    }

    @Test
    public void validateSessionChunkRejectsPathFromAnotherSessionDirectory() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path sessionDir = root.resolve("7/20260702/upload-session-a");
        Path otherSessionDir = root.resolve("7/20260702/upload-session-b");
        Files.createDirectories(sessionDir);
        Files.createDirectories(otherSessionDir);
        UploadSession session = new UploadSession();
        session.setId(9L);
        session.setUserId(7);
        session.setFileName("AllProblems.tgz");
        session.setStagingPath(sessionDir.resolve("AllProblems.tgz.part").toString());

        UploadArtifactPathGuard guard = new UploadArtifactPathGuard(root);

        try {
            guard.validateUploadSessionChunksDirectory(
                session,
                otherSessionDir.resolve("AllProblems.tgz.part.chunks").toString()
            );
            fail("session chunk directory should be bound to the exact session staging path");
        } catch (Exception expected) {
            // Expected.
        }
    }

    @Test
    public void validateSessionChunksDirectoryAcceptsExactSessionPath() throws Exception {
        Path root = Files.createTempDirectory("bench-root-");
        Path sessionDir = root.resolve("7/20260702/upload-session-exact");
        Files.createDirectories(sessionDir);
        UploadSession session = new UploadSession();
        session.setId(10L);
        session.setUserId(7);
        session.setFileName("AllProblems.tgz");
        session.setStagingPath(sessionDir.resolve("AllProblems.tgz.part").toString());
        Path chunks = sessionDir.resolve("AllProblems.tgz.part.chunks");

        UploadArtifactPathGuard guard = new UploadArtifactPathGuard(root);

        assertEquals(chunks.toAbsolutePath().normalize(), guard.validateUploadSessionChunksDirectory(session, chunks.toString()));
    }
}
