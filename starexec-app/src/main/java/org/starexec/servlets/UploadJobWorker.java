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
import org.starexec.util.UploadArtifactFileSystem;
import org.starexec.util.UploadArtifactPathGuard;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

    /** Returned by the size estimator when no cheap estimate is available. */
    private static final long UNKNOWN_UNCOMPRESSED_SIZE = -1L;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;
    private static final int MAX_CONCURRENT_JOBS = 3; // Allow up to 3 concurrent job processing
    private static final int EXTRACTION_PROGRESS_UPDATE_FILE_INTERVAL = 100;

    /**
     * Smallest gap between two heartbeat writes during extraction.
     *
     * <p>Matches MIN_UPDATE_INTERVAL_MS in the traversal callback below: the processing
     * phase has always throttled its progress writes by time, and extraction is the only
     * phase that did not.
     *
     * <p>The bound that actually matters is much looser. The sole reader of heartbeat
     * freshness is {@link org.starexec.data.to.UploadJob#isStuck()}, which reports a job as
     * stuck after five minutes without one, and only to colour an indicator in the upload
     * status page. A one-second floor stays three hundred times inside that.
     */
    private static final long EXTRACTION_HEARTBEAT_MIN_INTERVAL_MS = 1000L;
    private static final long ORPHAN_RETENTION_HOURS = 24;

    /**
     * Monotonic clock for heartbeat throttling. Package-private and replaceable so tests can
     * advance time explicitly rather than sleeping.
     */
    java.util.function.LongSupplier nanoTime = System::nanoTime;
    static final String TEMP_EXTRACTION_SUFFIX = ".extracting";
    
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final UploadArtifactPathGuard uploadPathGuard;
    private ExecutorService workerExecutor;
    private ScheduledExecutorService cleanupExecutor;
    private Thread workerThread;

    public UploadJobWorker() {
        this(null);
    }

    UploadJobWorker(UploadArtifactPathGuard uploadPathGuard) {
        this.uploadPathGuard = uploadPathGuard;
    }

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

        if (EnvironmentConfig.isUploadCleanupEnabled()) {
            cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "upload-artifact-cleanup");
                t.setDaemon(true);
                return t;
            });
            try {
                UploadArtifactCleanupWorker cleanupWorker = new UploadArtifactCleanupWorker(
                    EnvironmentConfig.getUploadCleanupBatchSize()
                );
                int interval = EnvironmentConfig.getUploadCleanupIntervalSeconds();
                cleanupExecutor.scheduleWithFixedDelay(cleanupWorker, 0, interval, TimeUnit.SECONDS);
                log.info("startWorkerInfrastructure", "Scheduled upload artifact cleanup every " + interval + " seconds");
            } catch (IOException e) {
                log.error("startWorkerInfrastructure", "Failed to initialize upload artifact cleanup", e);
                cleanupExecutor.shutdownNow();
                cleanupExecutor = null;
            }
        }
    }

    /**
     * Cleans up orphaned extraction directories from previous crashes.
     *
     * Safety constraints:
     * - Only touches directories created by resumable upload sessions
     *   (upload-session-* under benchmark/{userId}/{upload timestamp}/)
     * - Only deletes extraction directories with worker-owned prefix (upload_)
     * - Never traverses arbitrary benchmark hierarchy directories
     */
    void cleanupOrphanedExtractions() {
        String method = "cleanupOrphanedExtractions";
        
        try {
            Path benchmarkRoot = uploadPathGuard != null ? uploadPathGuard.getBenchmarkRoot() : Path.of(R.getBenchmarkPath());
            if (!Files.exists(benchmarkRoot, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }

            UploadArtifactPathGuard pathGuard = getUploadPathGuard();
            benchmarkRoot = pathGuard.getBenchmarkRoot();
            if (!isSafeDirectory(benchmarkRoot)) {
                return;
            }
            
            int cleanedCount = 0;
            try (DirectoryStream<Path> userDirs = Files.newDirectoryStream(benchmarkRoot)) {
                for (Path userDir : userDirs) {
                    if (!isSafeDirectory(userDir)) continue;

                    try (DirectoryStream<Path> dateDirs = Files.newDirectoryStream(userDir)) {
                        for (Path dateDir : dateDirs) {
                            if (!isSafeDirectory(dateDir) || !looksLikeDateDirectory(dateDir.getFileName().toString())) {
                                continue;
                            }

                            try (DirectoryStream<Path> sessionDirs = Files.newDirectoryStream(dateDir)) {
                                for (Path sessionDir : sessionDirs) {
                                    if (!isSafeDirectory(sessionDir) || !sessionDir.getFileName().toString().startsWith("upload-session-")) {
                                        continue;
                                    }

                                    try (DirectoryStream<Path> extractionDirs = Files.newDirectoryStream(sessionDir)) {
                                        for (Path extractionDir : extractionDirs) {
                                            if (!isTemporaryExtractionDirectory(extractionDir)) {
                                                continue;
                                            }

                                            long ageHours = (System.currentTimeMillis() -
                                                Files.getLastModifiedTime(extractionDir, LinkOption.NOFOLLOW_LINKS).toMillis()) /
                                                (1000 * 60 * 60);
                                            if (ageHours <= ORPHAN_RETENTION_HOURS) {
                                                continue;
                                            }

                                            try {
                                                Path safeExtractionDir = pathGuard.validateTemporaryExtractionDirectory(extractionDir.toString());
                                                if (!Files.isDirectory(safeExtractionDir, LinkOption.NOFOLLOW_LINKS)) {
                                                    continue;
                                                }
                                                UploadArtifactFileSystem.deleteDirectoryWithoutFollowingLinks(safeExtractionDir);
                                                cleanedCount++;
                                            } catch (Exception e) {
                                                log.warn(method, "Failed to clean: " + extractionDir, e);
                                            }
                                        }
                                    }
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

    private boolean isSafeDirectory(Path path) {
        return path != null && !Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean looksLikeDateDirectory(String name) {
        return UploadArtifactPathGuard.isUploadDirectoryName(name);
    }

    boolean isTemporaryExtractionDirectory(File extractionDir) {
        return extractionDir != null
            && extractionDir.getName().startsWith("upload_")
            && extractionDir.getName().endsWith(TEMP_EXTRACTION_SUFFIX);
    }

    private boolean isTemporaryExtractionDirectory(Path extractionDir) {
        return extractionDir != null
            && !Files.isSymbolicLink(extractionDir)
            && Files.isDirectory(extractionDir, LinkOption.NOFOLLOW_LINKS)
            && extractionDir.getFileName().toString().startsWith("upload_")
            && extractionDir.getFileName().toString().endsWith(TEMP_EXTRACTION_SUFFIX);
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

        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("contextDestroyed", "Cleanup executor did not terminate gracefully");
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.warn("contextDestroyed", "Interrupted while shutting down cleanup executor", e);
                cleanupExecutor.shutdownNow();
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
            File archiveFile = getUploadPathGuard().validateSourceArchivePath(job.getArchivePath(), job.getUserId()).toFile();
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
            List<String> rejectedPaths = Collections.emptyList();
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
                rejectedPaths = processor.getRejectedPaths();
            }

            ensureNotCancelled(job.getId());
            
            // Step 3: Mark as completed
            String completionOutcome = UploadJobQueue.completeJob(job.getId());
            if (!UploadJobQueue.isCompletedOutcome(completionOutcome)) {
                if ("CANCEL_REQUESTED".equals(completionOutcome)
                        || UploadJobQueue.isCancelRequested(job.getId())) {
                    throw new UploadCancellationException("Upload job cancellation requested");
                }
                // The outcome names which guard rejected the completion, so a failure
                // here is diagnosable instead of just alarming.
                throw new IOException("Failed to mark upload job as completed: " + completionOutcome);
            }
            processingSucceeded = true;
            log.info(method, "Completed job " + job.getId());

            // The job reached a completed state and canRetry() admits only FAILED or
            // CANCELLED, so it can never resume: the files the processor rejected are now
            // provably garbage. Deleting them earlier would have been unsafe -- the resume
            // index is positional into a freshly walked file list, so removing one
            // mid-run shifts every later index.
            deleteRejectedFiles(job, extractDir, rejectedPaths);
            
            // Step 4: Delete only the source archive file now that extraction is done.
            // The extracted directory must NOT be deleted — DB paths point to it.
            try {
                if (Files.exists(archiveFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    UploadArtifactFileSystem.deleteFileWithoutFollowingLinks(archiveFile.toPath());
                    UploadJobQueue.markSourceArchiveDeleted(job.getId(), false);
                    log.info(method, "Deleted source archive: " + archivePath);
                } else {
                    UploadJobQueue.markSourceArchiveDeleted(job.getId(), true);
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
                    Path safeExtractDir = getUploadPathGuard().validateExtractionDirectoryPath(
                        extractDir.getAbsolutePath(),
                        job.getId(),
                        job.getUserId()
                    );
                    UploadArtifactFileSystem.deleteDirectoryWithoutFollowingLinks(safeExtractDir);
                    log.info(method, "Cleaned up extraction directory after failure for job " + job.getId());
                } catch (Exception cleanupEx) {
                    log.warn(method, "Failed to cleanup extraction directory: " + extractDir.getAbsolutePath(), cleanupEx);
                }
            }
        }
    }

    /**
     * Deletes the files the benchmark processor rejected during an upload.
     *
     * <p>A rejected file was never inserted, so no {@code benchmarks} row references it
     * and nothing charged its bytes against {@code users.disk_size}. The extraction
     * directory itself is deliberately preserved — the rows that were inserted point into
     * it — so without this an upload whose files are all rejected would occupy disk
     * permanently and outside the quota system entirely.
     *
     * <p>Each path is re-checked for containment in this job's own extraction directory.
     * The paths come from this worker's own traversal, but a deletion loop driven by
     * stored strings is worth constraining regardless.
     *
     * <p>Failures are logged, never rethrown: the upload succeeded, and a stray file left
     * behind must not turn a completed job into a failed one.
     */
    private void deleteRejectedFiles(UploadJob job, File extractDir, List<String> rejectedPaths) {
        String method = "deleteRejectedFiles";
        if (rejectedPaths.isEmpty() || extractDir == null) {
            return;
        }

        final Path safeExtractDir;
        try {
            safeExtractDir = getUploadPathGuard().validateExtractionDirectoryPath(
                    extractDir.getAbsolutePath(), job.getId(), job.getUserId()
            ).toAbsolutePath().normalize();
        } catch (Exception e) {
            log.warn(method, "Could not resolve extraction directory for job " + job.getId()
                    + "; leaving " + rejectedPaths.size() + " rejected file(s) in place", e);
            return;
        }

        int deleted = 0;
        for (String rejected : rejectedPaths) {
            try {
                Path candidate = Paths.get(rejected).toAbsolutePath().normalize();
                if (!candidate.startsWith(safeExtractDir)) {
                    log.error(method, "Refusing to delete path outside the extraction directory of job "
                            + job.getId() + ": " + rejected);
                    continue;
                }
                UploadArtifactFileSystem.deleteFileWithoutFollowingLinks(candidate);
                deleted++;
            } catch (Exception e) {
                log.warn(method, "Could not delete rejected file " + rejected, e);
            }
        }

        log.info(method, "Deleted " + deleted + " of " + rejectedPaths.size()
                + " rejected file(s) for job " + job.getId());
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

    private UploadArtifactPathGuard getUploadPathGuard() throws IOException {
        return uploadPathGuard != null ? uploadPathGuard : new UploadArtifactPathGuard();
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
            // Must be long: (int) System.currentTimeMillis() truncates an epoch
            // millisecond value, leaving the elapsed comparison permanently above the
            // interval and defeating the throttle entirely.
            private long lastUpdateTime = 0L;
            private static final long MIN_UPDATE_INTERVAL_MS = 1000L; // Update at most every second
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
                    lastUpdateTime = now;
                    UploadJobQueue.updateProgress(
                        job.getId(),
                        filesFound,
                        filesProcessed,
                        spacesCreated,
                        lastCommittedPath,
                        filesProcessed > 0 ? filesProcessed - 1 : null
                    );
                    // No touchJob here: update_upload_job_progress already sets
                    // last_heartbeat, so a second call would repeat the write it just did.
                }
            }
            
            @Override
            public void onComplete(int totalFilesFound, int totalFilesProcessed, int totalSpacesCreated) {
                log.info("Traversal complete: " + totalFilesFound + " files found, " +
                         totalFilesProcessed + " processed, " + totalSpacesCreated + " spaces created");
                // The traversal has finished, so this count is authoritative and replaces
                // the extraction-time estimate. Passing it through updateProgress would
                // not work: that merges with GREATEST, so the estimate -- which counts
                // files the traversal discarded -- would win and leave a fully successful
                // upload looking like COMPLETED_WITH_ERRORS.
                UploadJobQueue.setTotalFilesFound(job.getId(), totalFilesFound);
                // Final update
                UploadJobQueue.updateProgress(
                    job.getId(),
                    null,
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
        UploadArtifactPathGuard pathGuard = getUploadPathGuard();
        Path safeArchivePath = pathGuard.validateSourceArchivePath(job.getArchivePath(), job.getUserId());

        if (job.getExtractPath() != null && !job.getExtractPath().isEmpty()) {
            try {
                Path existingExtractPath = pathGuard.validateExtractionDirectoryPath(
                    job.getExtractPath(),
                    job.getId(),
                    job.getUserId()
                );
                if (safeArchivePath.getParent().equals(existingExtractPath.getParent()) &&
                    Files.isDirectory(existingExtractPath, LinkOption.NOFOLLOW_LINKS)) {
                    return existingExtractPath.toFile();
                }
            } catch (IOException e) {
                log.warn(method, "Ignoring unsafe persisted extraction path for job " + job.getId(), e);
            }
        }

        archiveFile = safeArchivePath.toFile();
        if (!archiveFile.exists()) {
            throw new IOException("Archive file not found: " + job.getArchivePath());
        }

        UploadExtractionQuota quota = calculateExtractionQuota(job, archiveFile);
        String extractDirName = "upload_" + job.getId() + "_" + System.currentTimeMillis();
        File tempExtractDir = new File(archiveFile.getParent(), extractDirName + TEMP_EXTRACTION_SUFFIX);
        File finalExtractDir = new File(archiveFile.getParent(), extractDirName);
        Path safeTempExtractDir = pathGuard.validateExtractionDirectoryPath(
            tempExtractDir.getAbsolutePath(),
            job.getId(),
            job.getUserId()
        );
        Path safeFinalExtractDir = pathGuard.validateExtractionDirectoryPath(
            finalExtractDir.getAbsolutePath(),
            job.getId(),
            job.getUserId()
        );

        ArchiveExtractor.ExtractionSettings extractionSettings = ArchiveExtractor.ExtractionSettings.fromEnvironment()
            .withTimeoutSeconds(EnvironmentConfig.getUploadExtractionTimeoutSeconds())
            .withRemainingQuotaBytes(quota.remainingQuotaBytes)
            .withProgressCallback(createExtractionProgressCallback(job.getId(), extractedCount));

        log.info(method, "Extracting archive for job " + job.getId());
        try {
            ArchiveExtractor.extractWithCleanup(safeArchivePath.toString(), safeTempExtractDir, extractedCount, extractionSettings);
        } catch (IOException e) {
            throw new IOException(buildExtractionFailureMessage(job.getArchivePath(), e), e);
        }

        moveExtractDirectory(safeTempExtractDir.toFile(), safeFinalExtractDir.toFile());
        if (!UploadJobQueue.updateExtractPath(job.getId(), safeFinalExtractDir.toAbsolutePath().toString())) {
            UploadArtifactFileSystem.deleteDirectoryWithoutFollowingLinks(safeFinalExtractDir);
            throw new IOException("Failed to persist extraction path for upload job " + job.getId());
        }
        job.setExtractPath(safeFinalExtractDir.toAbsolutePath().toString());
        return safeFinalExtractDir.toFile();
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

    /**
     * Progress reporting for archive extraction.
     *
     * <p>ArchiveExtractor runs this once per archive entry, so whatever it does is multiplied
     * by the entry count -- 26,990 times for the TPTP Problems distribution. It used to write
     * to the database on every one of those: a row update for each hundredth entry, and a
     * heartbeat for all the rest. Each heartbeat borrowed a pooled connection (validated on
     * borrow, so an extra round trip), ran an UPDATE and committed it. On a deployment whose
     * database storage commits over NFS that came to roughly seventy milliseconds an entry,
     * against about three milliseconds of actual extraction, and the archive stopped being
     * unpackable inside the extraction timeout.
     *
     * <p>The row update still fires every hundred entries, unchanged -- that is what drives
     * the progress bar. Only the heartbeat is now throttled by time, which is what the
     * traversal callback below has always done. Nothing reads the heartbeat often enough to
     * notice: {@code last_heartbeat} appears in no query predicate anywhere, and its only
     * reader is a five-minute staleness indicator in the UI.
     */
    private Runnable createExtractionProgressCallback(long jobId, AtomicInteger extractedCount) {
        final long minIntervalNanos =
            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(EXTRACTION_HEARTBEAT_MIN_INTERVAL_MS);
        return new Runnable() {
            private int lastPersistedCount = 0;
            private long lastWriteNanos = nanoTime.getAsLong();

            @Override
            public void run() {
                int currentCount = extractedCount.get();

                // The progress row also stamps last_heartbeat (update_upload_job_progress
                // sets it), so a write here is a heartbeat too and restarts the interval.
                if (currentCount > 0
                        && currentCount - lastPersistedCount >= EXTRACTION_PROGRESS_UPDATE_FILE_INTERVAL) {
                    UploadJobQueue.updateProgress(jobId, currentCount, null, null, null, null);
                    lastPersistedCount = currentCount;
                    lastWriteNanos = nanoTime.getAsLong();
                    return;
                }

                long now = nanoTime.getAsLong();
                if (now - lastWriteNanos >= minIntervalNanos) {
                    UploadJobQueue.touchJob(jobId);
                    lastWriteNanos = now;
                }
            }
        };
    }

    private String buildExtractionFailureMessage(String archivePath, IOException error) {
        String causeMessage = error.getMessage();
        if (causeMessage == null || causeMessage.isEmpty()) {
            causeMessage = error.getClass().getSimpleName();
        }
        // Name only: the absolute path is already in the logs, and it is noise to the uploader
        // as well as a needless disclosure of the data volume's internal layout.
        return "Failed to extract archive: " + archiveDisplayName(archivePath) + " - " + causeMessage;
    }

    /**
     * The archive's file name for use in a user-facing message. Never throws: this runs only on a
     * failure path, where a NullPointerException would replace the error being reported.
     */
    private String archiveDisplayName(String archivePath) {
        if (archivePath == null || archivePath.isEmpty()) {
            return "the upload archive";
        }
        Path fileName = Paths.get(archivePath).getFileName();
        return fileName == null ? "the upload archive" : fileName.toString();
    }

    /**
     * Builds the quota rejection and records it for administrators.
     *
     * <p>The thrown message reaches the uploader and so names the archive only. The absolute path
     * lives in this log line, because a pre-flight rejection happens before extraction logs
     * anything and the job row would otherwise be the only record of which artifact was refused.
     */
    private IOException quotaRejection(UploadJob job, File archiveFile, long requiredBytes,
                                       long remainingQuotaBytes) {
        log.info("calculateExtractionQuota", "Rejecting upload job " + job.getId() + " for user "
            + job.getUserId() + ": needs " + requiredBytes + " bytes, quota has "
            + remainingQuotaBytes + " bytes remaining. Archive: " + archiveFile.getAbsolutePath());
        return new IOException(
            ArchiveExtractor.formatQuotaExceededMessage(requiredBytes, remainingQuotaBytes)
        );
    }

    private UploadExtractionQuota calculateExtractionQuota(UploadJob job, File archiveFile) throws IOException {
        User currentUser = Users.get(job.getUserId());
        if (currentUser == null) {
            throw new IOException("Failed to load upload user for quota validation");
        }

        long remainingQuotaBytes = Math.max(0L, currentUser.getDiskQuota() - currentUser.getDiskUsage());
        long archiveSizeBytes = archiveFile.length();
        if (remainingQuotaBytes <= 0L || archiveSizeBytes > remainingQuotaBytes) {
            throw quotaRejection(job, archiveFile, archiveSizeBytes, remainingQuotaBytes);
        }

        long estimatedUncompressedBytes = estimateUncompressedSizeBytes(archiveFile);
        if (estimatedUncompressedBytes > 0L && estimatedUncompressedBytes > remainingQuotaBytes) {
            throw quotaRejection(job, archiveFile, estimatedUncompressedBytes, remainingQuotaBytes);
        }

        return new UploadExtractionQuota(remainingQuotaBytes);
    }

    /**
     * Exact uncompressed size of an upload in bytes, or {@link #UNKNOWN_UNCOMPRESSED_SIZE} when it
     * cannot be established without doing the work the check exists to avoid.
     *
     * <p>ZIP and TAR record every entry's size in its headers, so the exact payload total is cheap
     * to read and {@link ArchiveUtil#getArchiveSize} is used directly.
     *
     * <p>gzip offers nothing equivalent, and there is deliberately no estimate for {@code .tgz} or
     * {@code .tar.gz} here. Two tempting shortcuts are both wrong:
     * <ul>
     *   <li>Inflating the stream to measure it ({@code ArchiveUtil.getTarGzSize}) costs as much as
     *       the extraction it is meant to pre-empt -- minutes, for a multi-gigabyte upload.</li>
     *   <li>The gzip ISIZE trailer is O(1), but it is the size of the <em>TAR stream</em>: entry
     *       headers, per-entry padding to 512 bytes, the end-of-archive blocks and the blocking
     *       factor. The quota counts entry payloads only. ISIZE therefore over-states what the
     *       quota will charge, without bound -- an archive of many tiny files is almost entirely
     *       TAR overhead -- so rejecting on it would refuse uploads that fit.</li>
     * </ul>
     *
     * <p>No sound cheap pre-flight exists for gzip, so these fall through to the streaming quota
     * check in {@link ArchiveExtractor}, which counts the same bytes the quota charges and stops
     * the moment they exceed it.
     */
    // Package-private and non-static so tests can force the "no cheap estimate" case and
    // exercise the streaming quota check behind it.
    long estimateUncompressedSizeBytes(File archiveFile) {
        String fileName = archiveFile.getName().toLowerCase();
        if (fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz")) {
            return UNKNOWN_UNCOMPRESSED_SIZE;
        }
        return ArchiveUtil.getArchiveSize(archiveFile.getAbsolutePath());
    }

    private static final class UploadExtractionQuota {
        private final long remainingQuotaBytes;

        private UploadExtractionQuota(long remainingQuotaBytes) {
            this.remainingQuotaBytes = remainingQuotaBytes;
        }
    }

    private void ensureNotCancelled(long jobId) throws UploadCancellationException {
        if (UploadJobQueue.isCancelRequested(jobId)) {
            throw new UploadCancellationException("Upload job cancellation requested");
        }
    }
}
