package org.starexec.servlets;

import org.starexec.constants.R;
import org.starexec.config.EnvironmentConfig;
import org.starexec.data.database.Spaces;
import org.starexec.data.database.UploadJobQueue;
import org.starexec.data.database.Users;
import org.starexec.data.processing.BoundedUploadProcessor;
import org.starexec.data.to.Permission;
import org.starexec.data.to.TraversalProgressListener;
import org.starexec.data.to.UploadJob;
import org.starexec.data.to.User;
import org.starexec.logger.StarLogger;
import org.starexec.util.ArchiveExtractor;
import org.starexec.util.ArchiveUtil;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background worker that processes upload jobs from the queue.
 * Runs as a daemon thread started by ServletContextListener.
 */
public class UploadJobWorker implements ServletContextListener, Runnable {
    private static final StarLogger log = StarLogger.getLogger(UploadJobWorker.class);
    
    private static final int POLL_INTERVAL_MS = 5000; // 5 seconds
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;
    private static final int MAX_CONCURRENT_JOBS = 3; // Allow up to 3 concurrent job processing
    private static final int EXTRACTION_PROGRESS_UPDATE_FILE_INTERVAL = 100;
    private static final long ORPHAN_RETENTION_HOURS = 24;
    static final String TEMP_EXTRACTION_SUFFIX = ".extracting";
    
    private final AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService workerExecutor;
    private Thread workerThread;

    private static class UploadCancellationException extends IOException {
        private static final long serialVersionUID = 1L;

        UploadCancellationException(String message) {
            super(message);
        }
    }
    
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        log.info("contextInitialized", "Starting upload job worker");

        reconcileStartupState();
        cleanupStartupArtifacts();
        startWorkerInfrastructure();
        
        log.info("contextInitialized", "Upload job worker started");
    }

    void reconcileStartupState() {
        UploadJobQueue.ReconciliationResult reconciliationResult = UploadJobQueue.reconcileStaleProcessingJobs();
        if (reconciliationResult.hasChanges()) {
            log.info(
                "contextInitialized",
                "Startup reconciliation complete: cancelled=" + reconciliationResult.getCancelledCount() +
                    ", failed=" + reconciliationResult.getFailedCount()
            );
        }
    }

    void cleanupStartupArtifacts() {
        // CRITICAL: Clean up orphaned extraction directories from previous crashes.
        // This handles OOM/SIGKILL scenarios where try-finally doesn't run.
        cleanupOrphanedExtractions();
    }

    void startWorkerInfrastructure() {
        // Create bounded thread pool for concurrent job processing.
        // This prevents head-of-line blocking where a large job delays smaller jobs.
        workerExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_JOBS, r -> {
            Thread t = new Thread(r, "upload-job-worker");
            t.setDaemon(true);
            return t;
        });

        // Start the worker thread.
        workerThread = new Thread(this, "upload-job-poller");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    /**
     * Cleans up orphaned extraction directories from previous crashes.
     *
     * Safety constraints:
     * - Only touches directories created by resumable upload sessions
     *   (upload-session-* under benchmark/{userId}/{yyyyMMdd}/)
     * - Only deletes extraction directories with worker-owned prefix (upload_)
     * - Never traverses arbitrary benchmark hierarchy directories
     */
    private void cleanupOrphanedExtractions() {
        String method = "cleanupOrphanedExtractions";
        
        try {
            String benchmarkPath = R.getBenchmarkPath();
            File benchmarkDir = new File(benchmarkPath);
            
            if (!benchmarkDir.exists()) {
                return;
            }
            
            int cleanedCount = 0;
            File[] userDirs = benchmarkDir.listFiles();
            if (userDirs != null) {
                for (File userDir : userDirs) {
                    if (!userDir.isDirectory()) continue;

                    File[] dateDirs = userDir.listFiles();
                    if (dateDirs == null) continue;

                    for (File dateDir : dateDirs) {
                        if (!dateDir.isDirectory() || !looksLikeDateDirectory(dateDir.getName())) {
                            continue;
                        }

                        File[] sessionDirs = dateDir.listFiles();
                        if (sessionDirs == null) continue;

                        for (File sessionDir : sessionDirs) {
                            if (!sessionDir.isDirectory() || !sessionDir.getName().startsWith("upload-session-")) {
                                continue;
                            }

                            File[] extractionDirs = sessionDir.listFiles();
                            if (extractionDirs == null) continue;

                            for (File extractionDir : extractionDirs) {
                                if (!isTemporaryExtractionDirectory(extractionDir)) {
                                    continue;
                                }

                                long ageHours = (System.currentTimeMillis() - extractionDir.lastModified()) / (1000 * 60 * 60);
                                if (ageHours <= ORPHAN_RETENTION_HOURS) {
                                    continue;
                                }

                                try {
                                    org.apache.commons.io.FileUtils.deleteDirectory(extractionDir);
                                    cleanedCount++;
                                } catch (Exception e) {
                                    log.warn(method, "Failed to clean: " + extractionDir.getAbsolutePath(), e);
                                }
                            }
                        }
                    }
                }
            }
            
            if (cleanedCount > 0) {
                log.info(method, "Cleaned up " + cleanedCount + " orphaned extraction directories");
            }
            
        } catch (Exception e) {
            log.error(method, "Error during cleanup", e);
        }
    }

    private boolean looksLikeDateDirectory(String name) {
        if (name == null || !name.matches("\\d{8}")) {
            return false;
        }
        try {
            LocalDate.parse(name, DateTimeFormatter.BASIC_ISO_DATE);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    boolean isTemporaryExtractionDirectory(File extractionDir) {
        return extractionDir != null
            && extractionDir.isDirectory()
            && extractionDir.getName().startsWith("upload_")
            && extractionDir.getName().endsWith(TEMP_EXTRACTION_SUFFIX);
    }
    
    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        log.info("contextDestroyed", "Shutting down upload job worker");
        
        // Signal worker to stop
        running.set(false);
        
        // Interrupt the worker thread if it's sleeping
        if (workerThread != null && workerThread.isAlive()) {
            workerThread.interrupt();
            
            try {
                workerThread.join(SHUTDOWN_TIMEOUT_SECONDS * 1000);
                if (workerThread.isAlive()) {
                    log.warn("contextDestroyed", "Worker thread did not terminate gracefully");
                }
            } catch (InterruptedException e) {
                log.warn("contextDestroyed", "Interrupted while waiting for worker thread", e);
                Thread.currentThread().interrupt();
            }
        }
        
        // Shutdown the worker executor
        if (workerExecutor != null) {
            workerExecutor.shutdown();
            try {
                if (!workerExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("contextDestroyed", "Worker executor did not terminate gracefully");
                    workerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.warn("contextDestroyed", "Interrupted while shutting down worker executor", e);
                workerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        log.info("contextDestroyed", "Upload job worker shutdown complete");
    }
    
    @Override
    public void run() {
        log.info("run", "Upload job poller started");
        
        while (running.get()) {
            try {
                // Poll for available jobs
                Optional<UploadJob> jobOpt = UploadJobQueue.claimJob();
                
                if (jobOpt.isPresent()) {
                    UploadJob job = jobOpt.get();
                    log.info("run", "Processing job " + job.getId());
                    
                    // Submit job for processing (async)
                    workerExecutor.submit(() -> processJob(job));
                } else {
                    // No jobs available, sleep before polling again
                    try {
                        Thread.sleep(POLL_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        if (running.get()) {
                            log.warn("run", "Polling interrupted", e);
                        }
                        // If we're shutting down, break out of the loop
                        break;
                    }
                }
                
            } catch (Exception e) {
                log.error("run", "Error in upload job poller", e);
                
                // Sleep on error to avoid tight loop
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    if (running.get()) {
                        log.warn("run", "Sleep interrupted after error", ie);
                    }
                    break;
                }
            }
        }
        
        log.info("run", "Upload job poller stopped");
    }
    
    /**
     * Processes a single upload job.
     */
    private void processJob(UploadJob job) {
        String method = "processJob";
        log.info(method, "Starting processing for job " + job.getId());
        
        // Track extraction directory for failure-only cleanup
        File extractDir = null;
        boolean processingSucceeded = false;
        boolean preserveArtifactsForRetry = false;
        boolean safeToDeleteExtractDir = true;
        
        // Track extraction count
        final AtomicInteger extractedCount = new AtomicInteger(0);
        
        try {
            UploadJobQueue.touchJob(job.getId());
            ensureNotCancelled(job.getId());

            String archivePath = job.getArchivePath();
            File archiveFile = new File(job.getArchivePath());
            extractDir = prepareExtraction(job, archiveFile, extractedCount);
            ensureNotCancelled(job.getId());

            if (extractedCount.get() > 0) {
                UploadJobQueue.updateProgress(job.getId(), extractedCount.get(), null, null, null, null);
                log.info(method, "Extracted " + extractedCount.get() + " files from archive");
            } else {
                log.info(method, "Reusing extracted directory for retry: " + extractDir.getAbsolutePath());
            }
            
            // Step 2: Process based on upload method
            // "convert" method requires subspace creation - use legacy synchronous path
            // "dump" method can use the new async processor
            if ("convert".equals(job.getUploadMethod())) {
                log.info(method, "Using legacy path for 'convert' method to create subspaces");
                safeToDeleteExtractDir = false;
                handleConvertMethod(job, extractDir);
            } else {
                // dump method - use the new async processor
                log.info(method, "Processing benchmarks for job " + job.getId());
                safeToDeleteExtractDir = false;
                BoundedUploadProcessor processor = new BoundedUploadProcessor(job, extractDir);
                processor.process();
            }

            ensureNotCancelled(job.getId());
            
            // Step 3: Mark as completed
            if (!UploadJobQueue.completeJob(job.getId())) {
                if (UploadJobQueue.isCancelRequested(job.getId())) {
                    throw new UploadCancellationException("Upload job cancellation requested");
                }
                throw new IOException("Failed to mark upload job as completed");
            }
            processingSucceeded = true;
            log.info(method, "Completed job " + job.getId());
            
            // Step 4: Delete only the source archive file now that extraction is done.
            // The extracted directory must NOT be deleted — DB paths point to it.
            try {
                if (archiveFile.exists() && archiveFile.delete()) {
                    log.info(method, "Deleted source archive: " + archivePath);
                }
            } catch (Exception deleteEx) {
                log.warn(method, "Could not delete source archive (non-fatal): " + archivePath, deleteEx);
            }
            
        } catch (UploadCancellationException e) {
            preserveArtifactsForRetry = job.getRetryCount() < job.getMaxRetries();
            UploadJobQueue.markJobCancelled(job.getId());
            log.info(method, "Cancelled job " + job.getId() + " cooperatively");
        } catch (Exception e) {
            if (isCancellationException(e, job.getId())) {
                preserveArtifactsForRetry = job.getRetryCount() < job.getMaxRetries();
                UploadJobQueue.markJobCancelled(job.getId());
                log.info(method, "Cancelled job " + job.getId() + " during processing");
                return;
            }

            log.error(method, "Failed to process job " + job.getId(), e);
            preserveArtifactsForRetry = job.getRetryCount() < job.getMaxRetries();
            
            // Mark job as failed
            String errorMessage = e.getMessage();
            if (errorMessage == null || errorMessage.isEmpty()) {
                errorMessage = "Unknown error: " + e.getClass().getSimpleName();
            }
            UploadJobQueue.failJob(job.getId(), errorMessage);
            
        } finally {
            // Only delete the extraction directory when processing FAILED — the files
            // were never registered in the DB so there is nothing to preserve.
            if (!processingSucceeded && !preserveArtifactsForRetry && extractDir != null && safeToDeleteExtractDir) {
                try {
                    ArchiveExtractor.cleanup(extractDir.getAbsolutePath());
                    log.info(method, "Cleaned up extraction directory after failure for job " + job.getId());
                } catch (Exception cleanupEx) {
                    log.warn(method, "Failed to cleanup extraction directory: " + extractDir.getAbsolutePath(), cleanupEx);
                }
            }
        }
    }

    private boolean isCancellationException(Throwable throwable, long jobId) {
        if (UploadJobQueue.isCancelRequested(jobId)) {
            return true;
        }

        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof UploadCancellationException || cursor instanceof InterruptedException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
    
    /**
     * Handles 'convert' method using legacy synchronous code that creates subspaces.
     * This is required because the async BoundedUploadProcessor doesn't support subspace creation.
     */
    private void handleConvertMethod(UploadJob job, File extractDir) throws Exception {
        String method = "handleConvertMethod";
        
        // Get job parameters
        int spaceId = job.getSpaceId();
        int userId = job.getUserId();
        int typeId = job.getBenchmarkTypeId();
        boolean downloadable = job.isDownloadable();
        
        // Get default permissions for new spaces
        // Default: add solver, bench, user, space, job permissions
        Permission perm = new Permission();
        perm.setAddSolver(true);
        perm.setAddBenchmark(true);
        perm.setAddUser(true);
        perm.setAddSpace(true);
        perm.setAddJob(true);
        
        log.info(method, "Converting directory structure to spaces for job " + job.getId());
        
        // Create progress listener for real-time updates
        TraversalProgressListener listener = createProgressListener(job);
        
        // Use the legacy method that creates subspaces.
        // hasDependencies, depRootSpaceId and linked come from the upload form and were
        // persisted in upload_jobs so that the worker can resolve starexec-dependency-N
        // attributes and insert the corresponding bench_dependency rows.
        boolean usesDeps = job.isHasDependencies();
        Integer depRootSpaceId = job.getDepRootSpaceId();
        // Fall back to the target space when no explicit dep-root was chosen.
        if (usesDeps && depRootSpaceId == null) {
            depRootSpaceId = spaceId;
        }
        boolean linked = job.isLinked();

        Spaces.traverseAndAddBenchmarks(
            extractDir,
            spaceId,
            userId,
            typeId,
            downloadable,
            perm,
            null, // statusId - not used in new system
            usesDeps,
            depRootSpaceId,
            linked,
            listener,
            job.getLastProcessedPath()
        );
        
        log.info(method, "Completed subspace creation for job " + job.getId());
    }
    
    /**
     * Creates a progress listener that updates the upload job progress in real-time.
     */
    private TraversalProgressListener createProgressListener(UploadJob job) {
        return new TraversalProgressListener() {
            private int lastUpdateTime = 0;
            private static final int MIN_UPDATE_INTERVAL_MS = 1000; // Update at most every second
            private String lastCommittedPath = job.getLastProcessedPath();
            
            @Override
            public void onSpaceCreated(int spaceId, String spacePath) {
                log.debug("Space created: " + spacePath + " (id=" + spaceId + ")");
            }
            
            @Override
            public void onBenchmarksProcessed(int processedCount, int totalProcessed) {
                log.debug("Benchmarks processed: " + processedCount + " batch, total: " + totalProcessed);
            }
            
            @Override
            public void onProgress(int directoriesVisited, int filesFound, int filesProcessed, int spacesCreated) {
                long now = System.currentTimeMillis();
                if (now - lastUpdateTime >= MIN_UPDATE_INTERVAL_MS) {
                    lastUpdateTime = (int) now;
                    UploadJobQueue.updateProgress(
                        job.getId(),
                        filesFound,
                        filesProcessed,
                        spacesCreated,
                        lastCommittedPath,
                        filesProcessed > 0 ? filesProcessed - 1 : null
                    );
                    // Also touch the job to update last_heartbeat
                    UploadJobQueue.touchJob(job.getId());
                }
            }
            
            @Override
            public void onComplete(int totalFilesFound, int totalFilesProcessed, int totalSpacesCreated) {
                log.info("Traversal complete: " + totalFilesFound + " files found, " + 
                         totalFilesProcessed + " processed, " + totalSpacesCreated + " spaces created");
                // Final update
                UploadJobQueue.updateProgress(
                    job.getId(),
                    totalFilesFound,
                    totalFilesProcessed,
                    totalSpacesCreated,
                    lastCommittedPath,
                    totalFilesProcessed > 0 ? totalFilesProcessed - 1 : null
                );
            }

            private int errorCount = 0;
            private static final int MAX_ERRORS_TO_LOG = 50;

            @Override
            public void onError(String errorMessage) {
                if (errorCount < MAX_ERRORS_TO_LOG) {
                    UploadJobQueue.appendError(job.getId(), errorMessage);
                    errorCount++;
                } else if (errorCount == MAX_ERRORS_TO_LOG) {
                    UploadJobQueue.appendError(job.getId(), "... further errors suppressed to avoid bloat. Check system logs for full details.");
                    errorCount++;
                }
            }

            @Override
            public void onBatchCommitted(String lastProcessedPath, int totalProcessed) {
                lastCommittedPath = lastProcessedPath;
                UploadJobQueue.updateProgress(
                    job.getId(),
                    null,
                    totalProcessed,
                    null,
                    lastProcessedPath,
                    totalProcessed > 0 ? totalProcessed - 1 : null
                );
            }

            @Override
            public boolean isCancellationRequested() {
                return UploadJobQueue.isCancelRequested(job.getId());
            }
        };
    }

    File prepareExtraction(UploadJob job, File archiveFile, AtomicInteger extractedCount) throws Exception {
        String method = "prepareExtraction";

        if (job.getExtractPath() != null && !job.getExtractPath().isEmpty()) {
            File existingExtractDir = new File(job.getExtractPath());
            if (existingExtractDir.exists() && existingExtractDir.isDirectory() &&
                    existingExtractDir.getAbsolutePath().startsWith(R.getBenchmarkPath())) {
                return existingExtractDir;
            }
        }

        if (!archiveFile.exists()) {
            throw new IOException("Archive file not found: " + job.getArchivePath());
        }

        UploadExtractionQuota quota = calculateExtractionQuota(job, archiveFile);
        String extractDirName = "upload_" + job.getId() + "_" + System.currentTimeMillis();
        File tempExtractDir = new File(archiveFile.getParent(), extractDirName + TEMP_EXTRACTION_SUFFIX);
        File finalExtractDir = new File(archiveFile.getParent(), extractDirName);

        ArchiveExtractor.ExtractionSettings extractionSettings = ArchiveExtractor.ExtractionSettings.fromEnvironment()
            .withTimeoutSeconds(EnvironmentConfig.getUploadExtractionTimeoutSeconds())
            .withMaxUncompressedSizeBytes(quota.maxExtractableBytes)
            .withProgressCallback(createExtractionProgressCallback(job.getId(), extractedCount));

        log.info(method, "Extracting archive for job " + job.getId());
        try {
            ArchiveExtractor.extractWithCleanup(job.getArchivePath(), tempExtractDir.toPath(), extractedCount, extractionSettings);
        } catch (IOException e) {
            throw new IOException(buildExtractionFailureMessage(job.getArchivePath(), e), e);
        }

        moveExtractDirectory(tempExtractDir, finalExtractDir);
        if (!UploadJobQueue.updateExtractPath(job.getId(), finalExtractDir.getAbsolutePath())) {
            ArchiveExtractor.cleanup(finalExtractDir.getAbsolutePath());
            throw new IOException("Failed to persist extraction path for upload job " + job.getId());
        }
        job.setExtractPath(finalExtractDir.getAbsolutePath());
        return finalExtractDir;
    }

    private void moveExtractDirectory(File sourceDir, File targetDir) throws IOException {
        try {
            java.nio.file.Files.move(
                sourceDir.toPath(),
                targetDir.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            );
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(sourceDir.toPath(), targetDir.toPath());
        }
    }

    private Runnable createExtractionProgressCallback(long jobId, AtomicInteger extractedCount) {
        return new Runnable() {
            private int lastPersistedCount = 0;

            @Override
            public void run() {
                int currentCount = extractedCount.get();
                if (currentCount <= 0) {
                    UploadJobQueue.touchJob(jobId);
                    return;
                }
                if (currentCount - lastPersistedCount >= EXTRACTION_PROGRESS_UPDATE_FILE_INTERVAL) {
                    UploadJobQueue.updateProgress(jobId, currentCount, null, null, null, null);
                    lastPersistedCount = currentCount;
                } else {
                    UploadJobQueue.touchJob(jobId);
                }
            }
        };
    }

    private String buildExtractionFailureMessage(String archivePath, IOException error) {
        String causeMessage = error.getMessage();
        if (causeMessage == null || causeMessage.isEmpty()) {
            causeMessage = error.getClass().getSimpleName();
        }
        return "Failed to extract archive: " + archivePath + " - " + causeMessage;
    }

    private UploadExtractionQuota calculateExtractionQuota(UploadJob job, File archiveFile) throws IOException {
        User currentUser = Users.get(job.getUserId());
        if (currentUser == null) {
            throw new IOException("Failed to load upload user for quota validation");
        }

        long remainingQuotaBytes = Math.max(0L, currentUser.getDiskQuota() - currentUser.getDiskUsage());
        if (remainingQuotaBytes <= 0L) {
            throw new IOException("The benchmark upload exceeds the remaining disk quota for this user");
        }

        long archiveSizeBytes = archiveFile.length();
        if (archiveSizeBytes > remainingQuotaBytes) {
            throw new IOException("The uploaded archive exceeds the remaining disk quota for this user");
        }

        long estimatedUncompressedBytes = shouldEstimateArchiveSize(archiveFile)
            ? ArchiveUtil.getArchiveSize(archiveFile.getAbsolutePath())
            : -1L;
        if (estimatedUncompressedBytes > 0L && estimatedUncompressedBytes > remainingQuotaBytes) {
            throw new IOException(
                "The uploaded archive expands to approximately " + estimatedUncompressedBytes +
                    " bytes, which exceeds the remaining disk quota of " + remainingQuotaBytes + " bytes"
            );
        }

        long configuredMaxBytes = EnvironmentConfig.getUploadExtractionMaxUncompressedBytes();
        long maxExtractableBytes = configuredMaxBytes > 0L
            ? Math.min(configuredMaxBytes, remainingQuotaBytes)
            : remainingQuotaBytes;

        return new UploadExtractionQuota(maxExtractableBytes);
    }

    private boolean shouldEstimateArchiveSize(File archiveFile) {
        String fileName = archiveFile.getName().toLowerCase();
        return !(fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz"));
    }

    private static final class UploadExtractionQuota {
        private final long maxExtractableBytes;

        private UploadExtractionQuota(long maxExtractableBytes) {
            this.maxExtractableBytes = maxExtractableBytes;
        }
    }

    private void ensureNotCancelled(long jobId) throws UploadCancellationException {
        if (UploadJobQueue.isCancelRequested(jobId)) {
            throw new UploadCancellationException("Upload job cancellation requested");
        }
    }
}
