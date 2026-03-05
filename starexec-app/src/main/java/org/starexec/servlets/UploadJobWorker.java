package org.starexec.servlets;

import org.starexec.constants.R;
import org.starexec.data.database.Benchmarks;
import org.starexec.data.database.Spaces;
import org.starexec.data.database.UploadJobQueue;
import org.starexec.data.processing.BoundedUploadProcessor;
import org.starexec.data.to.Permission;
import org.starexec.data.to.Space;
import org.starexec.data.to.TraversalProgressListener;
import org.starexec.data.to.UploadJob;
import org.starexec.logger.StarLogger;
import org.starexec.util.ArchiveExtractor;
import org.starexec.util.Util;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
    
    private final AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService workerExecutor;
    private Thread workerThread;
    
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        log.info("contextInitialized", "Starting upload job worker");
        
        // CRITICAL: Clean up orphaned extraction directories from previous crashes
        // This handles OOM/SIGKILL scenarios where try-finally doesn't run
        cleanupOrphanedExtractions();
        
        // Create bounded thread pool for concurrent job processing
        // This prevents head-of-line blocking where a large job delays smaller jobs
        workerExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_JOBS, r -> {
            Thread t = new Thread(r, "upload-job-worker");
            t.setDaemon(true);
            return t;
        });
        
        // Start the worker thread
        workerThread = new Thread(this, "upload-job-poller");
        workerThread.setDaemon(true);
        workerThread.start();
        
        log.info("contextInitialized", "Upload job worker started");
    }
    
    /**
     * Cleans up orphaned extraction directories from previous crashes.
     * Scans for old directories and removes those not belonging to active jobs.
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
                    
                    File[] subdirs = userDir.listFiles();
                    if (subdirs == null) continue;
                    
                    for (File subdir : subdirs) {
                        if (!subdir.isDirectory()) continue;
                        
                        // Clean directories older than 24 hours
                        long ageHours = (System.currentTimeMillis() - subdir.lastModified()) / (1000 * 60 * 60);
                        if (ageHours > 24) {
                            try {
                                org.apache.commons.io.FileUtils.deleteDirectory(subdir);
                                cleanedCount++;
                            } catch (Exception e) {
                                log.warn(method, "Failed to clean: " + subdir.getAbsolutePath(), e);
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
        
        // Track extraction count
        final AtomicInteger extractedCount = new AtomicInteger(0);
        
        try {
            // Update progress heartbeat
            UploadJobQueue.updateProgress(job.getId(), 0, 0, 0, null, 0);
            
            // Step 1: Extract archive safely with zip bomb protection
            String archivePath = job.getArchivePath();
            File archiveFile = new File(archivePath);
            
            if (!archiveFile.exists()) {
                throw new IOException("Archive file not found: " + archivePath);
            }
            
            // Create extraction directory under the benchmark storage path.
            // IMPORTANT: This directory must remain on disk permanently because the
            // paths stored in the DB point directly to files inside it. Jobs copy
            // benchmark files from these paths at runtime.
            String extractDirName = "upload_" + job.getId() + "_" + System.currentTimeMillis();
            extractDir = new File(archiveFile.getParent(), extractDirName);
            
            // Extract safely (handles zip bombs, path traversal, cleanup on failure)
            log.info(method, "Extracting archive for job " + job.getId());
            ArchiveExtractor.extractWithCleanup(archivePath, extractDir.toPath(), extractedCount);
            
            // Update progress with total found BEFORE processing begins
            int totalFound = extractedCount.get();
            UploadJobQueue.updateProgress(job.getId(), totalFound, 0, 0, null, 0);
            log.info(method, "Extracted " + totalFound + " files from archive");
            
            // Step 2: Process based on upload method
            // "convert" method requires subspace creation - use legacy synchronous path
            // "dump" method can use the new async processor
            if ("convert".equals(job.getUploadMethod())) {
                log.info(method, "Using legacy path for 'convert' method to create subspaces");
                handleConvertMethod(job, extractDir);
            } else {
                // dump method - use the new async processor
                log.info(method, "Processing benchmarks for job " + job.getId());
                BoundedUploadProcessor processor = new BoundedUploadProcessor(job, extractDir);
                processor.process();
            }
            
            // Step 3: Mark as completed
            UploadJobQueue.completeJob(job.getId());
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
            
        } catch (Exception e) {
            log.error(method, "Failed to process job " + job.getId(), e);
            
            // Mark job as failed
            String errorMessage = e.getMessage();
            if (errorMessage == null || errorMessage.isEmpty()) {
                errorMessage = "Unknown error: " + e.getClass().getSimpleName();
            }
            UploadJobQueue.failJob(job.getId(), errorMessage);
            
        } finally {
            // Only delete the extraction directory when processing FAILED — the files
            // were never registered in the DB so there is nothing to preserve.
            if (!processingSucceeded && extractDir != null) {
                try {
                    ArchiveExtractor.cleanup(extractDir.getAbsolutePath());
                    log.info(method, "Cleaned up extraction directory after failure for job " + job.getId());
                } catch (Exception cleanupEx) {
                    log.warn(method, "Failed to cleanup extraction directory: " + extractDir.getAbsolutePath(), cleanupEx);
                }
            }
        }
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
        
        // Use the legacy method that creates subspaces
        // This mirrors the logic in UploadBenchmark.handleUploadRequest for "convert" method
        Spaces.traverseAndAddBenchmarks(
            extractDir, 
            spaceId, 
            userId, 
            typeId, 
            downloadable, 
            perm, 
            null, // statusId - not used in new system
            false, // usesDeps
            null, // depRootSpaceId
            false, // linked
            listener // progress listener
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
                        null,
                        filesProcessed,
                        spacesCreated,
                        null,
                        null
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
                    null,
                    totalFilesProcessed,
                    totalSpacesCreated,
                    null,
                    null
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
        };
    }
}