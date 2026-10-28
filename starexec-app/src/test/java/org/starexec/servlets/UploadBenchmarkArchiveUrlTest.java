package org.starexec.servlets;

import org.junit.Test;
import org.starexec.util.Util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Archive URLs are checked when an upload is submitted (#139), so a bad address is refused with
 * a message at once instead of becoming a failed job minutes later.
 */
public class UploadBenchmarkArchiveUrlTest {

    @Test
    public void httpAndHttpsArchivesCanBeTried() {
        assertNull(UploadBenchmark.archiveUrlProblem("http://example.org/tptp/Problems.tgz"));
        assertNull(UploadBenchmark.archiveUrlProblem("https://example.org/tptp/Problems.tgz"));
        assertNull(UploadBenchmark.archiveUrlProblem("  https://example.org/tptp/Problems.tgz  "));
    }

    @Test
    public void otherSchemesAreRefused() {
        assertEquals("Archive URLs must use http or https",
            UploadBenchmark.archiveUrlProblem("ftp://example.org/tptp/Problems.tgz"));
        assertEquals("Archive URLs must use http or https",
            UploadBenchmark.archiveUrlProblem("file:///etc/Problems.tgz"));
    }

    @Test
    public void credentialsInTheAddressAreRefused() {
        assertEquals("Archive URLs must not contain a user name or password",
            UploadBenchmark.archiveUrlProblem("https://user:secret@example.org/tptp/Problems.tgz"));
    }

    @Test
    public void anAddressThatNamesNoArchiveIsRefused() {
        assertEquals(Util.ARCHIVE_NAME_REQUIRED,
            UploadBenchmark.archiveUrlProblem("https://example.org/tptp/"));
    }

    @Test
    public void somethingThatIsNotAUrlIsRefused() {
        assertEquals("The archive URL is not a valid URL", UploadBenchmark.archiveUrlProblem("Problems.tgz"));
        assertEquals("The archive URL is not a valid URL", UploadBenchmark.archiveUrlProblem("http://exa mple.org/a.tgz"));
        assertEquals("The archive URL is not a valid URL", UploadBenchmark.archiveUrlProblem(null));
    }
}
