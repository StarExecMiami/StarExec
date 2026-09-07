package org.starexec.test.unit.util;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.starexec.util.ArchiveExtractor;

import java.io.IOException;
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

public class ArchiveExtractorTest {

    private Path tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("archive-test-");
    }

    @After
    public void tearDown() {
        FileUtils.deleteQuietly(tempDir.toFile());
    }

    @Test
    public void cleanupRemovesExtractionDirectory() throws IOException {
        Path extractDir = tempDir.resolve("extract");
        Files.createDirectories(extractDir);
        Files.createFile(extractDir.resolve("test.txt"));

        assertTrue(ArchiveExtractor.cleanup(extractDir.toString()));
        assertFalse(Files.exists(extractDir));
    }

    @Test
    public void extractTarGzUsesInProcessExtraction() throws Exception {
        Path archive = createTarGzArchive("bench/file1.p", "content-1", "bench/file2.p", "content-2");
        Path extractDir = tempDir.resolve("out");
        AtomicInteger extractedCount = new AtomicInteger();

        ArchiveExtractor.extractWithCleanup(
            archive.toString(),
            extractDir,
            extractedCount,
            ArchiveExtractor.ExtractionSettings.defaults().withTimeoutSeconds(30)
        );

        assertTrue(Files.exists(extractDir.resolve("bench/file1.p")));
        assertTrue(Files.exists(extractDir.resolve("bench/file2.p")));
        assertEquals(2, extractedCount.get());
    }

    @Test
    public void extractTarGzUpdatesExtractedCountBeforeProgressCallback() throws Exception {
        Path archive = createTarGzArchive("bench/file1.p", "content-1", "bench/file2.p", "content-2");
        Path extractDir = tempDir.resolve("progress-out");
        AtomicInteger extractedCount = new AtomicInteger();
        AtomicInteger largestProgressCount = new AtomicInteger();

        ArchiveExtractor.ExtractionSettings settings = ArchiveExtractor.ExtractionSettings.defaults()
            .withTimeoutSeconds(30)
            .withProgressCallback(() -> largestProgressCount.set(
                Math.max(largestProgressCount.get(), extractedCount.get())
            ));

        ArchiveExtractor.extractWithCleanup(archive.toString(), extractDir, extractedCount, settings);

        assertEquals(2, extractedCount.get());
        assertEquals(2, largestProgressCount.get());
    }

    @Test
    public void extractZipAcceptsTinyBenchmarkEntry() throws Exception {
        Path archive = tempDir.resolve("tiny.zip");
        byte[] content = "1234567890123".getBytes(StandardCharsets.UTF_8);
        try (OutputStream fileOutput = Files.newOutputStream(archive);
             ZipOutputStream zipOutput = new ZipOutputStream(fileOutput)) {
            ZipEntry entry = new ZipEntry("Fake/FakeProblem.p");
            zipOutput.putNextEntry(entry);
            zipOutput.write(content);
            zipOutput.closeEntry();
        }

        AtomicInteger extractedCount = new AtomicInteger();
        Path extractDir = tempDir.resolve("tiny-out");

        ArchiveExtractor.extractWithCleanup(
            archive.toString(),
            extractDir,
            extractedCount,
            ArchiveExtractor.ExtractionSettings.defaults().withTimeoutSeconds(30)
        );

        assertTrue(Files.exists(extractDir.resolve("Fake/FakeProblem.p")));
        assertEquals(1, extractedCount.get());
        assertEquals(13, Files.size(extractDir.resolve("Fake/FakeProblem.p")));
    }

    @Test
    public void extractTarGzRejectsPathTraversal() throws Exception {
        Path archive = tempDir.resolve("evil.tgz");
        try (OutputStream fileOutput = Files.newOutputStream(archive);
             GzipCompressorOutputStream gzipOutput = new GzipCompressorOutputStream(fileOutput);
             TarArchiveOutputStream tarOutput = new TarArchiveOutputStream(gzipOutput)) {
            writeTarEntry(tarOutput, "../evil.txt", "boom");
            tarOutput.finish();
        }

        try {
            ArchiveExtractor.extractWithCleanup(archive.toString(), tempDir.resolve("evil-out"));
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("path traversal"));
            return;
        }

        throw new AssertionError("Expected extraction to reject path traversal");
    }

    @Test
    public void extractTarGzHonorsCancellationSetting() throws Exception {
        Path archive = createTarGzArchive("bench/file1.p", "slow-content", "bench/file2.p", "slow-content-2");
        ArchiveExtractor.ExtractionSettings settings = ArchiveExtractor.ExtractionSettings.defaults()
            .withTimeoutSeconds(1)
            .withCancellationRequested(() -> true);

        try {
            ArchiveExtractor.extractWithCleanup(archive.toString(), tempDir.resolve("cancelled"), null, settings);
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("cancelled"));
            // This message reaches the uploader, so it must name the archive, not its location.
            assertFalse("cancellation must not disclose the server path, was: " + e.getMessage(),
                e.getMessage().contains(tempDir.toAbsolutePath().toString()));
            return;
        }

        throw new AssertionError("Expected extraction cancellation");
    }

    @Test
    public void extractTarGzStopsWhenProjectedSizeExceedsLimit() throws Exception {
        Path archive = createTarGzArchive(
            "bench/file1.p",
            "0123456789",
            "bench/file2.p",
            "abcdefghij"
        );

        try {
            ArchiveExtractor.extractWithCleanup(
                archive.toString(),
                tempDir.resolve("quota-limited"),
                null,
                ArchiveExtractor.ExtractionSettings.defaults().withMaxUncompressedSizeBytes(5)
            );
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Total uncompressed size exceeds"));
            assertFalse(Files.exists(tempDir.resolve("quota-limited")));
            return;
        }

        throw new AssertionError("Expected extraction to stop when the configured size limit is exceeded");
    }

    @Test
    public void extractTarGzReportsQuotaExhaustionSeparatelyFromSafetyLimits() throws Exception {
        Path archive = createTarGzArchive(
            "bench/file1.p",
            "0123456789",
            "bench/file2.p",
            "abcdefghij"
        );
        Path extractDir = tempDir.resolve("quota-exhausted");

        try {
            ArchiveExtractor.extractWithCleanup(
                archive.toString(),
                extractDir,
                null,
                ArchiveExtractor.ExtractionSettings.defaults().withRemainingQuotaBytes(5)
            );
        } catch (IOException e) {
            String message = e.getMessage();
            // Issue #98: a user out of quota was being told their archive broke a safety limit.
            assertTrue("should name the limit as a disk quota, was: " + message,
                message.contains("disk quota"));
            assertTrue("should name the remedy, was: " + message, message.contains("recycle bin"));
            assertFalse("must not be reported as a security rejection, was: " + message,
                message.contains("safety limits"));
            assertFalse("must not reuse the zip-bomb wording, was: " + message,
                message.contains("Total uncompressed size exceeds"));
            assertFalse(Files.exists(extractDir));
            return;
        }

        throw new AssertionError("Expected extraction to stop when the disk quota is exhausted");
    }

    @Test
    public void extractTarGzStillReportsTheZipBombCapAsASafetyLimit() throws Exception {
        Path archive = createTarGzArchive(
            "bench/file1.p",
            "0123456789",
            "bench/file2.p",
            "abcdefghij"
        );

        try {
            ArchiveExtractor.extractWithCleanup(
                archive.toString(),
                tempDir.resolve("bomb-capped"),
                null,
                ArchiveExtractor.ExtractionSettings.defaults().withMaxUncompressedSizeBytes(5)
            );
        } catch (IOException e) {
            String message = e.getMessage();
            assertTrue("the security cap keeps its own wording, was: " + message,
                message.contains("Archive exceeds safety limits"));
            assertFalse("the security cap is not a quota problem, was: " + message,
                message.contains("disk quota"));
            return;
        }

        throw new AssertionError("Expected extraction to stop when the security cap is exceeded");
    }

    @Test
    public void quotaAndSecurityCapAreIndependentLimits() throws Exception {
        Path archive = createTarGzArchive(
            "bench/file1.p",
            "0123456789",
            "bench/file2.p",
            "abcdefghij"
        );
        Path extractDir = tempDir.resolve("both-limits");

        // A generous quota must not raise the security cap: the smaller limit still governs.
        try {
            ArchiveExtractor.extractWithCleanup(
                archive.toString(),
                extractDir,
                null,
                ArchiveExtractor.ExtractionSettings.defaults()
                    .withMaxUncompressedSizeBytes(5)
                    .withRemainingQuotaBytes(Long.MAX_VALUE)
            );
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Archive exceeds safety limits"));
            return;
        }

        throw new AssertionError("Expected the security cap to apply regardless of the quota");
    }

    @Test
    public void noQuotaLimitLeavesExtractionUncapped() throws Exception {
        Path archive = createTarGzArchive("bench/file1.p", "content-1", "bench/file2.p", "content-2");
        Path extractDir = tempDir.resolve("uncapped");
        AtomicInteger extractedCount = new AtomicInteger();

        ArchiveExtractor.extractWithCleanup(
            archive.toString(),
            extractDir,
            extractedCount,
            ArchiveExtractor.ExtractionSettings.defaults()
                .withRemainingQuotaBytes(ArchiveExtractor.NO_QUOTA_LIMIT)
        );

        assertEquals(2, extractedCount.get());
    }

    @Test
    public void quotaMessageStatesBothTheRequirementAndTheRemainder() {
        String message = ArchiveExtractor.formatQuotaExceededMessage(9061416960L, 4952489920L);

        assertTrue(message.contains("9061416960"));
        assertTrue(message.contains("4952489920"));
        assertTrue(message.contains("disk quota"));
    }

    private Path createTarGzArchive(String firstEntryName, String firstContent, String secondEntryName, String secondContent)
        throws IOException {
        Path archive = tempDir.resolve("archive.tgz");
        try (OutputStream fileOutput = Files.newOutputStream(archive);
             GzipCompressorOutputStream gzipOutput = new GzipCompressorOutputStream(fileOutput);
             TarArchiveOutputStream tarOutput = new TarArchiveOutputStream(gzipOutput)) {
            writeTarEntry(tarOutput, firstEntryName, firstContent);
            writeTarEntry(tarOutput, secondEntryName, secondContent);
            tarOutput.finish();
        }
        return archive;
    }

    private void writeTarEntry(TarArchiveOutputStream tarOutput, String entryName, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(entryName);
        entry.setSize(bytes.length);
        tarOutput.putArchiveEntry(entry);
        tarOutput.write(bytes);
        tarOutput.closeArchiveEntry();
    }
}
