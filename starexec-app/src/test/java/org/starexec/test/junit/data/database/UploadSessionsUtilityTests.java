package org.starexec.test.junit.data.database;

import org.apache.commons.io.FileUtils;
import org.junit.Test;
import org.starexec.data.database.UploadSessions;
import org.starexec.data.to.UploadSession;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UploadSessionsUtilityTests {

    @Test
    public void sanitizeFileNameDropsPathTraversal() {
        assertEquals("evil.zip", UploadSessions.sanitizeFileName("../../evil.zip"));
    }

    @Test
    public void sanitizeFileNameReplacesUnsafeCharacters() {
        assertEquals("my_file_.tar.gz", UploadSessions.sanitizeFileName("my file?.tar.gz"));
    }

    @Test
    public void sanitizeFileNameFallsBackWhenMissing() {
        assertEquals("upload.zip", UploadSessions.sanitizeFileName(""));
    }

    @Test
    public void cleanupSessionFilesRemovesOnlySessionArtifacts() throws Exception {
        Path tempDir = Files.createTempDirectory("upload-session-cleanup-");
        try {
            File sessionDir = tempDir.resolve("upload-session-1").toFile();
            assertTrue(sessionDir.mkdirs());
            File archive = new File(sessionDir, "AllProblems.tgz");
            File chunks = new File(archive.getAbsolutePath() + ".chunks");
            File assembling = new File(archive.getAbsolutePath() + ".assembling");
            File preserved = new File(sessionDir, "keep.txt");
            assertTrue(archive.createNewFile());
            assertTrue(chunks.mkdirs());
            assertTrue(assembling.createNewFile());
            assertTrue(preserved.createNewFile());

            UploadSession session = new UploadSession();
            session.setId(1L);
            session.setStagingPath(archive.getAbsolutePath());

            UploadSessions.cleanupSessionFiles(session);

            assertFalse(archive.exists());
            assertFalse(chunks.exists());
            assertFalse(assembling.exists());
            assertTrue(preserved.exists());
        } finally {
            FileUtils.deleteQuietly(tempDir.toFile());
        }
    }
}
