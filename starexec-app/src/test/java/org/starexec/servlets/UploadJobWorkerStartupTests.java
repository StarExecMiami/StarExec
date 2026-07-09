package org.starexec.servlets;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
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

import javax.servlet.ServletContextEvent;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class UploadJobWorkerStartupTests {

    @Test
    public void contextInitializedRunsStartupPhasesInOrder() {
        List<String> phases = new ArrayList<>();

        UploadJobWorker worker = new UploadJobWorker() {
            @Override
            void reconcileStartupState() {
                phases.add("reconcile");
            }

            @Override
            void cleanupStartupArtifacts() {
                phases.add("cleanup");
            }

            @Override
            void startWorkerInfrastructure() {
                phases.add("start");
            }
        };

        worker.contextInitialized((ServletContextEvent) null);

        assertEquals(Arrays.asList("reconcile", "cleanup", "start"), phases);
    }

    @Test
    public void temporaryExtractionDirectoriesAreCleanupCandidates() {
        UploadJobWorker worker = new UploadJobWorker();
        File tempExtractDir = new File(System.getProperty("java.io.tmpdir"), "upload_42_123.extracting");
        File finalExtractDir = new File(System.getProperty("java.io.tmpdir"), "upload_42_123");
        tempExtractDir.mkdirs();
        finalExtractDir.mkdirs();

        tempExtractDir.deleteOnExit();
        finalExtractDir.deleteOnExit();

        assertTrue(worker.isTemporaryExtractionDirectory(tempExtractDir));
        assertFalse(worker.isTemporaryExtractionDirectory(finalExtractDir));
    }

    @Test
    public void prepareExtractionPromotesTempDirectoryAndPersistsExtractPath() throws Exception {
        Path tempRoot = Files.createTempDirectory("upload-job-worker-");
        try {
            Path archive = createTarGzArchive(uploadSessionArchive(tempRoot, "ok"), "bench/file1.p", "content");
            UploadJob job = buildUploadJob(archive);
            User user = buildUserWithQuota();
            UploadJobWorker worker = new UploadJobWorker(new UploadArtifactPathGuard(tempRoot));
            AtomicInteger extractedCount = new AtomicInteger();

            try (MockedStatic<Users> usersMock = Mockito.mockStatic(Users.class);
                 MockedStatic<UploadJobQueue> queueMock = Mockito.mockStatic(UploadJobQueue.class)) {
                usersMock.when(() -> Users.get(job.getUserId())).thenReturn(user);
                queueMock.when(() -> UploadJobQueue.updateExtractPath(Mockito.eq(job.getId()), Mockito.anyString()))
                    .thenReturn(true);

                File extractDir = worker.prepareExtraction(job, archive.toFile(), extractedCount);

                assertNotNull(extractDir);
                assertTrue(extractDir.exists());
                assertFalse(extractDir.getName().endsWith(UploadJobWorker.TEMP_EXTRACTION_SUFFIX));
                assertEquals(1, extractedCount.get());
                assertEquals(extractDir.getAbsolutePath(), job.getExtractPath());
                assertFalse(Files.exists(extractDir.toPath().resolveSibling(extractDir.getName() + UploadJobWorker.TEMP_EXTRACTION_SUFFIX)));
            }
        } finally {
            FileUtils.deleteQuietly(tempRoot.toFile());
        }
    }

    @Test
    public void prepareExtractionCleansFinalDirectoryWhenPersistingExtractPathFails() throws Exception {
        Path tempRoot = Files.createTempDirectory("upload-job-worker-fail-");
        try {
            Path archive = createTarGzArchive(uploadSessionArchive(tempRoot, "fail"), "bench/file1.p", "content");
            UploadJob job = buildUploadJob(archive);
            User user = buildUserWithQuota();
            UploadJobWorker worker = new UploadJobWorker(new UploadArtifactPathGuard(tempRoot));

            try (MockedStatic<Users> usersMock = Mockito.mockStatic(Users.class);
                 MockedStatic<UploadJobQueue> queueMock = Mockito.mockStatic(UploadJobQueue.class)) {
                usersMock.when(() -> Users.get(job.getUserId())).thenReturn(user);
                queueMock.when(() -> UploadJobQueue.updateExtractPath(Mockito.eq(job.getId()), Mockito.anyString()))
                    .thenReturn(false);

                try {
                    worker.prepareExtraction(job, archive.toFile(), new AtomicInteger());
                } catch (IOException e) {
                    assertTrue(e.getMessage().contains("Failed to persist extraction path"));
                    assertEquals(0, countDirectories(archive.getParent()));
                    return;
                }
            }

            throw new AssertionError("Expected prepareExtraction to fail when extract_path persistence fails");
        } finally {
            FileUtils.deleteQuietly(tempRoot.toFile());
        }
    }

    @Test
    public void prepareExtractionIncludesRootCauseWhenArchiveExtractionFails() throws Exception {
        Path tempRoot = Files.createTempDirectory("upload-job-worker-bad-archive-");
        try {
            Path archive = createTarGzArchive(uploadSessionArchive(tempRoot, "bad"), "../evil.p", "content");
            UploadJob job = buildUploadJob(archive);
            User user = buildUserWithQuota();
            UploadJobWorker worker = new UploadJobWorker(new UploadArtifactPathGuard(tempRoot));

            try (MockedStatic<Users> usersMock = Mockito.mockStatic(Users.class)) {
                usersMock.when(() -> Users.get(job.getUserId())).thenReturn(user);

                try {
                    worker.prepareExtraction(job, archive.toFile(), new AtomicInteger());
                } catch (IOException e) {
                    assertTrue(e.getMessage().contains("Failed to extract archive"));
                    assertTrue(e.getMessage().contains("path traversal"));
                    assertEquals(0, countDirectories(archive.getParent()));
                    return;
                }
            }

            throw new AssertionError("Expected prepareExtraction to fail for an unsafe archive entry");
        } finally {
            FileUtils.deleteQuietly(tempRoot.toFile());
        }
    }

    @Test
    public void cleanupOrphanedExtractionsDeletesOnlyAgedMatchingDirectories() throws Exception {
        Path tempRoot = Files.createTempDirectory("upload-job-worker-orphans-");
        try {
            Path oldExtraction = tempRoot.resolve("7/20260704/upload-session-old/upload_42_1.extracting");
            Path recentExtraction = tempRoot.resolve("7/20260704/upload-session-recent/upload_43_1.extracting");
            Path nonMatching = tempRoot.resolve("7/20260704/upload-session-keep/not-upload.extracting");
            Files.createDirectories(oldExtraction);
            Files.createDirectories(recentExtraction);
            Files.createDirectories(nonMatching);
            Files.writeString(oldExtraction.resolve("old.p"), "old");
            Files.writeString(recentExtraction.resolve("recent.p"), "recent");
            Files.writeString(nonMatching.resolve("keep.p"), "keep");
            Files.setLastModifiedTime(oldExtraction, FileTime.fromMillis(System.currentTimeMillis() - (26L * 60 * 60 * 1000)));

            UploadJobWorker worker = new UploadJobWorker(new UploadArtifactPathGuard(tempRoot));
            worker.cleanupOrphanedExtractions();

            assertFalse(Files.exists(oldExtraction));
            assertTrue(Files.exists(recentExtraction));
            assertTrue(Files.exists(nonMatching));
        } finally {
            FileUtils.deleteQuietly(tempRoot.toFile());
        }
    }

    @Test
    public void cleanupOrphanedExtractionsSkipsSymlinkedDirectoriesDuringDiscovery() throws Exception {
        Path tempRoot = Files.createTempDirectory("upload-job-worker-symlink-root-");
        Path outside = Files.createTempDirectory("upload-job-worker-symlink-outside-");
        Path dateLink = tempRoot.resolve("7/20260704");
        try {
            Path externalExtraction = outside.resolve("upload-session-linked/upload_99_1.extracting");
            Files.createDirectories(externalExtraction);
            Files.writeString(externalExtraction.resolve("outside.p"), "outside");
            Files.setLastModifiedTime(externalExtraction, FileTime.fromMillis(System.currentTimeMillis() - (26L * 60 * 60 * 1000)));

            Path userDir = tempRoot.resolve("7");
            Files.createDirectories(userDir);
            Files.createSymbolicLink(dateLink, outside);

            UploadJobWorker worker = new UploadJobWorker(new UploadArtifactPathGuard(tempRoot));
            worker.cleanupOrphanedExtractions();

            assertTrue(Files.exists(externalExtraction));
        } finally {
            Files.deleteIfExists(dateLink);
            FileUtils.deleteQuietly(tempRoot.toFile());
            FileUtils.deleteQuietly(outside.toFile());
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

    private User buildUserWithQuota() {
        User user = new User();
        user.setId(7);
        user.setDiskQuota(1024L * 1024L * 1024L);
        user.setDiskUsage(0L);
        return user;
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

    private Path uploadSessionArchive(Path root, String suffix) {
        return root.resolve("7/20260704/upload-session-" + suffix + "/AllProblems.tgz");
    }

    private long countDirectories(Path root) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.list(root)) {
            return paths.filter(Files::isDirectory).count();
        }
    }
}
