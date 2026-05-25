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
