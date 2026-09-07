package org.starexec.servlets;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.FileUtils;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.UploadJobQueue;
import org.starexec.data.database.Users;
import org.starexec.data.to.UploadJob;
import org.starexec.data.to.User;
import org.starexec.util.UploadArtifactPathGuard;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the disk-quota half of upload extraction.
 *
 * <p>The behaviour under test is the fix for issue #98: a user who has simply run out of disk
 * quota must be told so, in terms that name the remedy, and must never be told their archive was
 * rejected as a zip bomb. The pre-flight check must also not over-reject -- it may only refuse an
 * upload on a figure that the quota will actually charge.
 */
public class UploadJobWorkerQuotaTests {

    private static final String SAFETY_LIMIT_WORDING = "safety limits";
    private static final String ZIP_BOMB_WORDING = "Total uncompressed size exceeds";

    // ---------------------------------------------------------------- pre-flight rejection

    @Test
    public void zipUploadIsRejectedBeforeExtractionWhenItCannotFitInQuota() throws Exception {
        Path tempRoot = Files.createTempDirectory("upload-quota-zip-");
        try {
            // ZIP records every entry's payload size in its headers, so the pre-flight figure is
            // exact and refusing the upload on it is safe.
            Path archive = createZipArchive(sessionArchive(tempRoot, "zip-over", "AllProblems.zip"),
                "bench/file1.p", "content-that-is-a-little-longer");

            IOException failure = expectPrepareExtractionFailure(tempRoot, archive, 8L);

            assertQuotaWordingNotSecurityWording(failure);
            assertEquals("extraction must not have started", 0, countDirectories(archive.getParent()));
        } finally {
            FileUtils.deleteQuietly(tempRoot.toFile());
        }
    }

    @Test
    public void uploadIsRejectedWhenTheCompressedArchiveAloneExceedsQuota() throws Exception {
        Path tempRoot = Files.createTempDirectory("upload-quota-compressed-");
        try {
            Path archive = createTarGzArchive(sessionArchive(tempRoot, "compressed", "AllProblems.tgz"),
                "bench/file1.p", "content");

            assertQuotaWordingNotSecurityWording(expectPrepareExtractionFailure(tempRoot, archive, 1L));
        } finally {
            FileUtils.deleteQuietly(tempRoot.toFile());
        }
    }

    @Test
    public void uploadIsRejectedWithQuotaWordingWhenNoQuotaRemains() throws Exception {
        Path tempRoot = Files.createTempDirectory("upload-quota-exhausted-");
        try {
            Path archive = createTarGzArchive(sessionArchive(tempRoot, "exhausted", "AllProblems.tgz"),
                "bench/file1.p", "content");

            assertQuotaWordingNotSecurityWording(expectPrepareExtractionFailure(tempRoot, archive, 0L));
        } finally {
            FileUtils.deleteQuietly(tempRoot.toFile());
        }
    }

    @Test
    public void uploadThatFitsInQuotaStillExtracts() throws Exception {
        Path tempRoot = Files.createTempDirectory("upload-quota-fits-");
        try {
            Path archive = createTarGzArchive(sessionArchive(tempRoot, "fits", "AllProblems.tgz"),
                "bench/file1.p", "content");

            assertEquals(1, extractSuccessfully(tempRoot, archive, 1024L * 1024L));
        } finally {
            FileUtils.deleteQuietly(tempRoot.toFile());
        }
    }

    /**
     * Regression test for the defect an independent review caught in the first version of this
     * fix. A TAR stream is much larger than the payload it carries -- a 512-byte header per entry,
     * padding to 512-byte boundaries, two end-of-archive blocks and a 10240-byte blocking factor.
     * The quota charges only the payload. Any pre-flight figure taken from the size of the stream
     * (the gzip ISIZE trailer, for instance) therefore over-states the charge without bound, and
     * refusing an upload on it rejects archives that fit.
     */
    @Test
    public void tgzIsNotRejectedForTarStreamOverheadThatTheQuotaDoesNotCharge() throws Exception {
        Path tempRoot = Files.createTempDirectory("upload-quota-overhead-");
        try {
            String payload = "content";
            Path archive = createTarGzArchive(sessionArchive(tempRoot, "overhead", "AllProblems.tgz"),
                "bench/file1.p", payload);

            long tarStreamBytes = inflatedSize(archive);
            long floor = Math.max(payload.length(), Files.size(archive));
            // Sit the quota above the payload and the archive on disk, but below the TAR stream,
            // so only a stream-derived figure could reject this upload. Derived rather than
            // hard-coded: TAR padding depends on the writer's record and block size.
            long quota = (floor + tarStreamBytes) / 2;
            assertTrue("TAR stream must exceed the quota", tarStreamBytes > quota);
            assertTrue("payload must fit inside the quota", payload.length() < quota);
            assertTrue("compressed archive must clear the quota", Files.size(archive) < quota);

            assertEquals("an upload whose payload fits must extract", 1,
                extractSuccessfully(tempRoot, archive, quota));
        } finally {
            FileUtils.deleteQuietly(tempRoot.toFile());
        }
    }

    // -------------------------------------------------- streaming check behind the estimate

    @Test
    public void quotaStillStopsATgzUploadThatHasNoPreFlightEstimate() throws Exception {
        Path tempRoot = Files.createTempDirectory("upload-quota-streaming-");
        try {
            // gzip has no sound cheap pre-flight, so .tgz reaches extraction with the quota as its
            // only limit. This is the path issue #98 actually took, and it works only if
            // prepareExtraction hands the quota to the extractor. Highly compressible, so the
            // archive on disk clears the quota and only the bytes written can exceed it.
            String payload = repeat('a', 400_000);
            Path archive = createTarGzArchive(sessionArchive(tempRoot, "streaming", "AllProblems.tgz"),
                "bench/file1.p", payload);
            long quota = 100_000L;
            assertEquals("fixture must have no pre-flight estimate", -1L,
                new UploadJobWorker().estimateUncompressedSizeBytes(archive.toFile()));
            assertTrue("fixture must clear the compressed-size pre-check", Files.size(archive) < quota);
            assertTrue("fixture must exceed the quota once unpacked", payload.length() > quota);

            IOException failure = expectPrepareExtractionFailure(tempRoot, archive, quota);

            assertQuotaWordingNotSecurityWording(failure);
            assertEquals("partial extraction must be cleaned up", 0,
                countDirectories(archive.getParent()));
        } finally {
            FileUtils.deleteQuietly(tempRoot.toFile());
        }
    }

    @Test
    public void estimatorIsExactForZipAndAbsentForGzip() throws Exception {
        Path tempRoot = Files.createTempDirectory("estimator-routing-");
        try {
            UploadJobWorker worker = new UploadJobWorker();

            // ZIP carries exact per-entry payload sizes.
            Path zip = createZipArchive(tempRoot.resolve("c.zip"), "bench/file1.p", "content");
            assertEquals("content".length(), worker.estimateUncompressedSizeBytes(zip.toFile()));

            // gzip has no sound cheap estimate; every spelling must say so rather than guess.
            assertEquals(-1L, worker.estimateUncompressedSizeBytes(
                createTarGzArchive(tempRoot.resolve("a.tgz"), "bench/file1.p", "content").toFile()));
            assertEquals(-1L, worker.estimateUncompressedSizeBytes(
                createTarGzArchive(tempRoot.resolve("b.tar.gz"), "bench/file1.p", "content").toFile()));
            assertEquals(-1L, worker.estimateUncompressedSizeBytes(
                createTarGzArchive(tempRoot.resolve("d.TGZ"), "bench/file1.p", "content").toFile()));
        } finally {
            FileUtils.deleteQuietly(tempRoot.toFile());
        }
    }

    // ------------------------------------------------------------------- message disclosure

    @Test
    public void failureMessageNamesTheArchiveWithoutLeakingTheServerPath() throws Exception {
        Path tempRoot = Files.createTempDirectory("upload-quota-path-");
        try {
            Path archive = createTarGzArchive(sessionArchive(tempRoot, "traversal", "AllProblems.tgz"),
                "../evil.p", "content");
            UploadJob job = buildUploadJob(archive);
            UploadJobWorker worker = new UploadJobWorker(new UploadArtifactPathGuard(tempRoot));

            try (MockedStatic<Users> usersMock = Mockito.mockStatic(Users.class)) {
                usersMock.when(() -> Users.get(job.getUserId()))
                    .thenReturn(userWithRemainingQuota(1024L * 1024L));
                try {
                    worker.prepareExtraction(job, archive.toFile(), new AtomicInteger());
                } catch (IOException e) {
                    String message = e.getMessage();
                    assertTrue("should name the archive, was: " + message,
                        message.contains("AllProblems.tgz"));
                    assertFalse("should not expose the absolute server path, was: " + message,
                        message.contains(tempRoot.toAbsolutePath().toString()));
                    return;
                }
            }
            throw new AssertionError("Expected prepareExtraction to reject an unsafe archive entry");
        } finally {
            FileUtils.deleteQuietly(tempRoot.toFile());
        }
    }

    // ----------------------------------------------------------------------------- helpers

    private void assertQuotaWordingNotSecurityWording(IOException failure) {
        String message = failure.getMessage();
        assertTrue("should name the limit as a disk quota, was: " + message,
            message.contains("disk quota"));
        assertTrue("should name the remedy, was: " + message,
            message.contains("recycle bin"));
        assertFalse("must not be reported as a security rejection, was: " + message,
            message.contains(SAFETY_LIMIT_WORDING));
        assertFalse("must not reuse the zip-bomb wording, was: " + message,
            message.contains(ZIP_BOMB_WORDING));
    }

    private IOException expectPrepareExtractionFailure(Path tempRoot, Path archive, long remainingQuota)
        throws Exception {
        UploadJob job = buildUploadJob(archive);
        UploadJobWorker worker = new UploadJobWorker(new UploadArtifactPathGuard(tempRoot));

        // UploadJobQueue is stubbed so that a regression which stops enforcing the quota completes
        // extraction and fails on the assertion below, rather than on a stray database call.
        try (MockedStatic<Users> usersMock = Mockito.mockStatic(Users.class);
             MockedStatic<UploadJobQueue> queueMock = Mockito.mockStatic(UploadJobQueue.class)) {
            usersMock.when(() -> Users.get(job.getUserId())).thenReturn(userWithRemainingQuota(remainingQuota));
            queueMock.when(() -> UploadJobQueue.updateExtractPath(Mockito.eq(job.getId()), Mockito.anyString()))
                .thenReturn(true);
            try {
                worker.prepareExtraction(job, archive.toFile(), new AtomicInteger());
            } catch (IOException e) {
                return e;
            }
        }
        throw new AssertionError("Expected prepareExtraction to reject the upload for lack of quota");
    }

    /** Runs prepareExtraction to completion and returns the number of files extracted. */
    private int extractSuccessfully(Path tempRoot, Path archive, long remainingQuota) throws Exception {
        UploadJob job = buildUploadJob(archive);
        UploadJobWorker worker = new UploadJobWorker(new UploadArtifactPathGuard(tempRoot));

        try (MockedStatic<Users> usersMock = Mockito.mockStatic(Users.class);
             MockedStatic<UploadJobQueue> queueMock = Mockito.mockStatic(UploadJobQueue.class)) {
            usersMock.when(() -> Users.get(job.getUserId())).thenReturn(userWithRemainingQuota(remainingQuota));
            queueMock.when(() -> UploadJobQueue.updateExtractPath(Mockito.eq(job.getId()), Mockito.anyString()))
                .thenReturn(true);

            AtomicInteger extractedCount = new AtomicInteger();
            assertTrue(worker.prepareExtraction(job, archive.toFile(), extractedCount).exists());
            return extractedCount.get();
        }
    }

    private UploadJob buildUploadJob(Path archive) {
        UploadJob job = new UploadJob();
        job.setId(42L);
        job.setUserId(7);
        job.setArchivePath(archive.toAbsolutePath().toString());
        job.setUploadMethod("dump");
        job.setBenchmarkTypeId(1);
        return job;
    }

    private User userWithRemainingQuota(long remainingBytes) {
        User user = new User();
        user.setId(7);
        // Exercise the subtraction the worker performs rather than handing it a pristine account.
        user.setDiskQuota(remainingBytes + 4096L);
        user.setDiskUsage(4096L);
        return user;
    }

    private Path sessionArchive(Path root, String suffix, String archiveName) {
        return root.resolve("7/20260618-13.22.18.964/upload-session-" + suffix + "/" + archiveName);
    }

    private Path createTarGzArchive(Path archive, String entryName, String content) throws IOException {
        Files.createDirectories(archive.getParent());
        try (OutputStream fileOutput = Files.newOutputStream(archive);
             GzipCompressorOutputStream gzipOutput = new GzipCompressorOutputStream(fileOutput);
             TarArchiveOutputStream tarOutput = new TarArchiveOutputStream(gzipOutput)) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry entry = new TarArchiveEntry(entryName);
            entry.setSize(bytes.length);
            tarOutput.putArchiveEntry(entry);
            tarOutput.write(bytes);
            tarOutput.closeArchiveEntry();
            tarOutput.finish();
        }
        return archive;
    }

    private Path createZipArchive(Path archive, String entryName, String content) throws IOException {
        Files.createDirectories(archive.getParent());
        try (OutputStream fileOutput = Files.newOutputStream(archive);
             ZipOutputStream zipOutput = new ZipOutputStream(fileOutput)) {
            zipOutput.putNextEntry(new ZipEntry(entryName));
            zipOutput.write(content.getBytes(StandardCharsets.UTF_8));
            zipOutput.closeEntry();
        }
        return archive;
    }

    /** The real inflated size of a gzip file: the TAR stream, not the payload it carries. */
    private long inflatedSize(Path archive) throws IOException {
        try (InputStream fileInput = Files.newInputStream(archive);
             GzipCompressorInputStream gzipInput = new GzipCompressorInputStream(fileInput)) {
            long total = 0;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = gzipInput.read(buffer)) != -1) {
                total += read;
            }
            return total;
        }
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    private long countDirectories(Path root) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.list(root)) {
            return paths.filter(Files::isDirectory).count();
        }
    }
}
