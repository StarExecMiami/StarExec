package org.starexec.servlets;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.UploadJobQueue;
import org.starexec.data.database.Users;
import org.starexec.data.to.UploadJob;
import org.starexec.data.to.User;
import org.starexec.util.UploadArtifactPathGuard;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * An upload by URL arrives without its archive: the request only records where to fetch it, and
 * the worker downloads it before extracting (#139). Before this the request threw, so every
 * upload by URL ended in an HTTP 500.
 */
public class UploadJobWorkerUrlFetchTests {

    private static final String URL = "http://example.org/archives/probe.tar.gz";

    private Path root;
    private Path archive;
    private final List<String> fetched = new ArrayList<>();
    private final List<Long> caps = new ArrayList<>();
    private long remainingQuota = 1L << 30;

    @Before
    public void setUp() throws IOException {
        root = Files.createTempDirectory("url-fetch");
        archive = root.resolve("7/20261015-17.00.00.000/probe.tar.gz");
        Files.createDirectories(archive.getParent());
    }

    @After
    public void tearDown() throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    private UploadJob job(String sourceUrl) {
        UploadJob job = new UploadJob();
        job.setId(42L);
        job.setUserId(7);
        job.setArchivePath(archive.toAbsolutePath().toString());
        job.setUploadMethod("dump");
        job.setBenchmarkTypeId(1);
        job.setSourceUrl(sourceUrl);
        return job;
    }

    private User userWithQuota() {
        User user = new User();
        user.setId(7);
        // Exercise the subtraction the worker performs rather than a pristine account.
        user.setDiskQuota(remainingQuota + 4096L);
        user.setDiskUsage(4096L);
        return user;
    }

    /** A worker whose fetcher records the request and writes a one-file archive. */
    private UploadJobWorker workerThatFetches(boolean succeeds) throws IOException {
        UploadJobWorker worker = new UploadJobWorker(new UploadArtifactPathGuard(root));
        worker.archiveFetcher = (URL url, File target, long maxBytes) -> {
            fetched.add(url + " -> " + target.getAbsolutePath());
            caps.add(maxBytes);
            if (!succeeds) {
                return false;
            }
            writeArchive(target.toPath());
            return true;
        };
        return worker;
    }

    private static void writeArchive(Path target) {
        byte[] bytes = "fof(a, axiom, $true).\n".getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = Files.newOutputStream(target);
             GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(out);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            TarArchiveEntry entry = new TarArchiveEntry("PRB001+1.p");
            entry.setSize(bytes.length);
            tar.putArchiveEntry(entry);
            tar.write(bytes);
            tar.closeArchiveEntry();
            tar.finish();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private File prepare(UploadJobWorker worker, UploadJob job, AtomicInteger extracted) throws Exception {
        try (MockedStatic<Users> users = Mockito.mockStatic(Users.class);
             MockedStatic<UploadJobQueue> queue = Mockito.mockStatic(UploadJobQueue.class)) {
            users.when(() -> Users.get(7)).thenReturn(userWithQuota());
            queue.when(() -> UploadJobQueue.updateExtractPath(Mockito.eq(42L), Mockito.anyString()))
                .thenReturn(true);
            return worker.prepareExtraction(job, archive.toFile(), extracted);
        }
    }

    @Test
    public void aMissingArchiveIsFetchedThenExtracted() throws Exception {
        AtomicInteger extracted = new AtomicInteger();

        File dir = prepare(workerThatFetches(true), job(URL), extracted);

        assertEquals(1, fetched.size());
        assertEquals(URL + " -> " + archive.toAbsolutePath(), fetched.get(0));
        assertTrue(new File(dir, "PRB001+1.p").isFile());
        assertEquals(1, extracted.get());
    }

    @Test
    public void aFailedFetchFailsWithoutRepeatingTheAddress() throws Exception {
        try {
            prepare(workerThatFetches(false), job(URL), new AtomicInteger());
            fail("a failed download must fail the job");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Could not download the archive from its URL"));
            assertFalse("the error repeats the uploader's address: " + e.getMessage(),
                e.getMessage().contains("example.org"));
        }
        assertFalse(Files.exists(archive));
    }

    /** A retry after a later failure finds the archive downloaded and must not fetch it again. */
    @Test
    public void anArchiveAlreadyThereIsNotFetchedAgain() throws Exception {
        writeArchive(archive);

        prepare(workerThatFetches(true), job(URL), new AtomicInteger());

        assertTrue(fetched.isEmpty());
    }

    /** The log keeps where the archive came from, not what could authorise fetching it. */
    @Test
    public void theLoggedAddressCarriesNoSecret() throws Exception {
        assertEquals("https://example.org:8443/.../probe.tar.gz", UploadJobWorker.redacted(
            new URL("https://user:secret@example.org:8443/archives/probe.tar.gz?X-Amz-Signature=abc#part")));
        assertEquals("http://example.org/.../probe.tar.gz",
            UploadJobWorker.redacted(new URL(URL)));
    }

    /** A share link can carry its token in a directory of the path, not only in the query. */
    @Test
    public void aTokenInThePathIsNotLogged() throws Exception {
        String logged = UploadJobWorker.redacted(new URL("https://files.example/d/Zx81-secret-token/bench.zip"));
        assertEquals("https://files.example/.../bench.zip", logged);
        assertFalse(logged.contains("Zx81-secret-token"));
        assertEquals("https://files.example/bench.zip",
            UploadJobWorker.redacted(new URL("https://files.example/bench.zip")));
    }

    /** The quota is otherwise checked only after the download, at extraction. */
    @Test
    public void theDownloadIsCappedAtTheRemainingQuota() throws Exception {
        remainingQuota = 10L * 1024 * 1024;

        prepare(workerThatFetches(true), job(URL), new AtomicInteger());

        assertEquals(List.of(remainingQuota), caps);
    }

    @Test
    public void aLargeQuotaIsStillCappedAtTheDownloadLimit() throws Exception {
        remainingQuota = 100L * 1024 * 1024 * 1024;

        prepare(workerThatFetches(true), job(URL), new AtomicInteger());

        assertEquals(List.of(org.starexec.util.Util.ARCHIVE_DOWNLOAD_MAX_BYTES), caps);
    }

    @Test
    public void noQuotaLeftIsRefusedBeforeAnythingIsDownloaded() throws Exception {
        remainingQuota = 0L;
        try {
            prepare(workerThatFetches(true), job(URL), new AtomicInteger());
            fail("a user with no quota left must not download");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Not enough disk quota to download this archive"));
        }
        assertTrue(fetched.isEmpty());
    }

    @Test
    public void anUploadWithoutAUrlStillNeedsItsArchive() throws Exception {
        try {
            prepare(workerThatFetches(true), job(null), new AtomicInteger());
            fail("an upload with neither an archive nor a URL cannot be extracted");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Archive file not found"));
        }
        assertTrue(fetched.isEmpty());
    }
}
