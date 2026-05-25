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

import javax.servlet.ServletContextEvent;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
            Path archive = createTarGzArchive(tempRoot.resolve("AllProblems.tgz"), "bench/file1.p", "content");
            UploadJob job = buildUploadJob(archive);
            User user = buildUserWithQuota();
            UploadJobWorker worker = new UploadJobWorker();
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
            Path archive = createTarGzArchive(tempRoot.resolve("AllProblems.tgz"), "bench/file1.p", "content");
            UploadJob job = buildUploadJob(archive);
            User user = buildUserWithQuota();
            UploadJobWorker worker = new UploadJobWorker();

            try (MockedStatic<Users> usersMock = Mockito.mockStatic(Users.class);
                 MockedStatic<UploadJobQueue> queueMock = Mockito.mockStatic(UploadJobQueue.class)) {
                usersMock.when(() -> Users.get(job.getUserId())).thenReturn(user);
                queueMock.when(() -> UploadJobQueue.updateExtractPath(Mockito.eq(job.getId()), Mockito.anyString()))
                    .thenReturn(false);

                try {
                    worker.prepareExtraction(job, archive.toFile(), new AtomicInteger());
                } catch (IOException e) {
                    assertTrue(e.getMessage().contains("Failed to persist extraction path"));
                    assertEquals(0, countDirectories(tempRoot));
                    return;
                }
            }

            throw new AssertionError("Expected prepareExtraction to fail when extract_path persistence fails");
        } finally {
            FileUtils.deleteQuietly(tempRoot.toFile());
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

    private long countDirectories(Path root) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.list(root)) {
            return paths.filter(Files::isDirectory).count();
        }
    }
}
