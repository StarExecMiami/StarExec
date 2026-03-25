package org.starexec.test.junit.data.database;

import org.junit.Test;
import org.starexec.data.database.UploadSessions;

import static org.junit.Assert.assertEquals;

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
}
