package org.starexec.data.processing;

import org.starexec.data.database.Benchmarks;
import org.starexec.data.database.Common;
import org.starexec.data.database.UploadJobQueue;
import org.starexec.data.to.Benchmark;
import org.starexec.data.to.Processor;
import org.starexec.data.to.UploadJob;
import org.starexec.exceptions.StarExecException;
import org.starexec.logger.StarLogger;
import org.starexec.util.Validator;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implements a bounded producer-consumer pattern for benchmark upload processing.
 * Uses a blocking queue to decouple file traversal from database insertion
 * with natural backpressure when the database is slow.
 * 
 * IMPORTANT: Files are traversed in deterministic (alphabetical) order to ensure
 * idempotency on retry. The index-based resume works correctly because the 
 * sorted order is consistent across JVM restarts.
 */
public class BoundedUploadProcessor {
    private static final StarLogger log = StarLogger.getLogger(BoundedUploadProcessor.class);
    
    // Configuration constants
    private static final int QUEUE_CAPACITY = 1000;
    private static final int BATCH_SIZE = 50;
    // How often to wake and check that the run is still advancing.
    private static final int PROGRESS_POLL_SECONDS = 10;
    // How long committed progress may stand still before the run is declared stuck.
    // Progress only advances when a batch commits, so this window must comfortably
    // exceed the time to validate and insert one whole batch -- with a real benchmark
    // processor that means BATCH_SIZE sandboxed process executions. A genuine stall is
    // permanent, so a wide window costs only detection latency, whereas a narrow one
    // kills healthy slow work: exactly how the wall-clock budget this replaced failed.
    private static final long STALL_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(60);

    private static final String ADD_AND_ASSOCIATE_SQL =
        "SELECT bench_id, bench_path FROM starexec.AddAndAssociateBenchmarks(?, ?, ?, ?, ?, ?, ?)";
    
    // Thread management
    private final ExecutorService executor;
    private final AtomicBoolean traversalComplete = new AtomicBoolean(false);
    // Seeded from the job's stored total, not zero: update_upload_job_progress merges
    // with GREATEST, so a resumed attempt reporting only its own count would stall the
    // stored total at max(previous, thisAttempt) instead of their sum, and a fully
    // recovered upload would then look partial.
    private final AtomicInteger totalFilesProcessed;
    // Files taken off the queue, whether or not the processor validated them. Drives the
    // resume index: totalFilesProcessed counts only inserted benchmarks, so using it
    // there would rewind past files already dealt with and re-insert them on retry.
    private final AtomicInteger totalFilesConsumed = new AtomicInteger(0);
    // Files the benchmark processor rejected; summarised once when the run finishes.
    private final AtomicInteger totalFilesRejected = new AtomicInteger(0);
    // Paths of those rejected files. They stay on disk during the run because the resume
    // index is positional into a freshly walked file list -- removing one mid-run would
    // shift every later index and resume a retry at the wrong file. The caller deletes
    // them once the job has reached a state it can never resume from.
    private final List<String> rejectedPaths = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger totalSpacesCreated = new AtomicInteger(0);
    private volatile String lastProcessedPath;
    
    // Job context
    private final UploadJob job;
    private final File directory;
    // Carries only the id; Benchmarks.validateForUpload resolves the real processor from
    // it, and raises the standard "Benchmark processor not found" if it is gone.
    private final Processor benchmarkType;

    public BoundedUploadProcessor(UploadJob job, File directory) {
        this.job = job;
        this.directory = directory;
        this.benchmarkType = new Processor();
        this.benchmarkType.setId(job.getBenchmarkTypeId());
        this.totalFilesProcessed = new AtomicInteger(job.getTotalFilesProcessed());
        
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "upload-processor-" + job.getId());
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Processes the upload using deterministic (alphabetical) ordering.
     */
    public void process() throws IOException, SQLException, InterruptedException {
        String method = "process";
        log.info(method, "Starting bounded upload processing for job " + job.getId());
        throwIfCancelled();
        
        // Phase 1: Collect all file paths in deterministic order
        List<String> sortedFilePaths = collectSortedFilePaths();
        log.info(method, "Found " + sortedFilePaths.size() + " files for job " + job.getId());

        // Replace the extraction-time estimate with the real benchmark count. The
        // estimate counts every extracted file, including the ones the walk above
        // discarded, which would leave processed < found on a wholly successful upload
        // and report it as COMPLETED_WITH_ERRORS.
        UploadJobQueue.setTotalFilesFound(job.getId(), sortedFilePaths.size());
        
        // Phase 2: Calculate start index for retry scenarios
        int startIndex = calculateStartIndex(sortedFilePaths);
        if (startIndex > 0) {
            log.info(method, "Resuming from index " + startIndex + " for job " + job.getId());
        }
        
        // Phase 3: Process files with backpressure
        processSortedFiles(sortedFilePaths, startIndex);

        int rejected = totalFilesRejected.get();
        if (rejected > 0) {
            UploadJobQueue.appendError(job.getId(),
                    rejected + " benchmark(s) failed processor validation and were not added");
        }

        log.info(method, "Completed upload processing for job " + job.getId() +
                ", processed " + totalFilesProcessed.get() + " files, rejected " + rejected);
    }
    
    /**
     * Collects all file paths in deterministic (alphabetical) order using TreeSet.
     */
    private List<String> collectSortedFilePaths() throws IOException {
        TreeSet<String> sortedPaths = new TreeSet<>();
        
        Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (UploadJobQueue.isCancelRequested(job.getId())) {
                    throw new IOException("Upload job cancellation requested");
                }
                if (dir.equals(directory.toPath()) || dir.getFileName().toString().equals(".git")) {
                    return FileVisitResult.CONTINUE;
                }
                if ("convert".equals(job.getUploadMethod())) {
                    totalSpacesCreated.incrementAndGet();
                }
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (UploadJobQueue.isCancelRequested(job.getId())) {
                    throw new IOException("Upload job cancellation requested");
                }
                String fileName = file.getFileName().toString();
                if (Validator.shouldIgnoreFile(fileName)) {
                    return FileVisitResult.CONTINUE;
                }
                if (!Validator.isValidBenchName(fileName)) {
                    return FileVisitResult.CONTINUE;
                }
                sortedPaths.add(file.toAbsolutePath().toString());
                return FileVisitResult.CONTINUE;
            }
        });
        
        return new ArrayList<>(sortedPaths);
    }
    
    /**
     * Calculates starting index using last_processed_path (binary search).
     */
    private int calculateStartIndex(List<String> sortedPaths) {
        if (job.getLastProcessedPath() != null && !job.getLastProcessedPath().isEmpty()) {
            int index = Collections.binarySearch(sortedPaths, job.getLastProcessedPath());
            if (index >= 0) {
                return index + 1;
            }
        }
        if (job.getLastProcessedIndex() > 0 && job.getLastProcessedIndex() < sortedPaths.size()) {
            return job.getLastProcessedIndex() + 1;
        }
        return 0;
    }
    
    /**
     * Processes files with backpressure using queue.
     */
        private void processSortedFiles(List<String> sortedPaths, int startIndex) 
            throws SQLException, InterruptedException, IOException {
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        
        try {
            Future<?> producerFuture = executor.submit(() -> {
                try {
                    producePaths(queue, sortedPaths, startIndex);
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            Future<?> consumerFuture = executor.submit(() -> {
                try {
                    consumeAndInsert(queue, startIndex);
                } catch (SQLException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            
            executor.shutdown();

            // A wall-clock budget conflates "stuck" with "slow". The previous formula
            // -- max(30, min(remaining/100, 3600)) -- gave every upload under 3000 files
            // a hard 30-second ceiling and allowed only one second per 100 files beyond
            // that, so a healthy upload against a busy database was killed mid-flight.
            // Watch for a stall in committed progress instead: the job fails only when it
            // stops advancing, however long the whole run legitimately takes.
            int lastProgress = -1;
            long lastProgressAt = System.currentTimeMillis();

            while (!executor.awaitTermination(PROGRESS_POLL_SECONDS, TimeUnit.SECONDS)) {
                // A consumer that died leaves the producer blocked forever on a full
                // queue. Surface its exception now rather than waiting out the stall
                // window; get() returns harmlessly if it finished normally.
                if (consumerFuture.isDone()) {
                    consumerFuture.get();
                }

                // Progress means the run advanced, not that rows were inserted. A batch
                // the processor rejects wholesale inserts nothing and leaves
                // totalFilesProcessed unchanged, so watching that counter would declare a
                // perfectly healthy run stalled as soon as rejections span the timeout
                // window -- and a misconfigured processor rejects every file.
                int consumed = totalFilesConsumed.get();
                if (consumed != lastProgress) {
                    lastProgress = consumed;
                    lastProgressAt = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - lastProgressAt > STALL_TIMEOUT_MS) {
                    throw new IOException("Processing stalled: no progress for "
                            + TimeUnit.MILLISECONDS.toMinutes(STALL_TIMEOUT_MS)
                            + " minutes after " + consumed + " file(s) consumed, "
                            + totalFilesProcessed.get() + " inserted");
                }
            }
            producerFuture.get();
            consumerFuture.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof IOException) {
                throw (IOException)cause;
            }
            if (cause instanceof SQLException) {
                throw (SQLException)cause;
            }
            if (cause instanceof InterruptedException) {
                throw (InterruptedException)cause;
            }
            throw new IOException("Processing failed", cause);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                throw (InterruptedException)e;
            }
            throw new IOException("Processing failed", e);
        } finally {
            // shutdown() only refuses new tasks; it does not interrupt running ones. Any
            // abnormal exit -- including a consumer failure surfaced from inside the poll
            // loop above -- would otherwise leave the producer parked forever on a full
            // queue, leaking its thread and the queued paths for the life of the JVM.
            if (!executor.isTerminated()) {
                executor.shutdownNow();
            }
        }
    }
    
    private void producePaths(BlockingQueue<String> queue, List<String> paths, int startIndex)
            throws IOException, InterruptedException {
        try {
            for (int i = startIndex; i < paths.size(); i++) {
                throwIfCancelled();
                queue.put(paths.get(i));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            traversalComplete.set(true);
        }
    }
    
    private void consumeAndInsert(BlockingQueue<String> queue, int startIndex) throws SQLException, InterruptedException {
        List<Benchmark> batch = new ArrayList<>(BATCH_SIZE);

        while (!traversalComplete.get() || !queue.isEmpty()) {
            throwIfCancelled();
            String path = queue.poll(1, TimeUnit.SECONDS);
            if (path != null) {
                batch.add(createBenchmark(path));

                // Drain more
                List<String> drained = new ArrayList<>();
                queue.drainTo(drained, BATCH_SIZE - batch.size());
                for (String p : drained) {
                    batch.add(createBenchmark(p));
                }

                if (batch.size() >= BATCH_SIZE) {
                    // Each batch checkpoints itself inside its own transaction, so there
                    // is no separate periodic flush to fall behind the committed rows.
                    insertBatch(batch, startIndex);
                    batch.clear();
                }
            }
        }

        if (!batch.isEmpty()) {
            insertBatch(batch, startIndex);
        }

        // Final flush. Redundant after a batch that inserted anything -- that batch
        // checkpointed itself -- but load-bearing when every batch was rejected outright,
        // since those commit no transaction of their own.
        Integer finalLastProcessedIndex = totalFilesConsumed.get() > 0
                ? startIndex + totalFilesConsumed.get() - 1
                : null;
        UploadJobQueue.updateProgress(job.getId(), null,
                totalFilesProcessed.get(), totalSpacesCreated.get(),
                lastProcessedPath, finalLastProcessedIndex);
    }
    
    private Benchmark createBenchmark(String path) {
        Benchmark b = new Benchmark();
        b.setPath(path);
        b.setName(new File(path).getName());
        b.setUserId(job.getUserId());
        b.setDownloadable(job.isDownloadable());
        b.setType(benchmarkType);
        return b;
    }

    /**
     * Validates a batch against the benchmark processor, then persists the survivors.
     *
     * <p>Validation runs first and outside the transaction: the processor forks a
     * sandboxed process per file, which must never happen with a database transaction
     * held open. Files the processor rejects are dropped rather than inserted, matching
     * the synchronous path, which also refuses to add an invalid benchmark. They are
     * counted only in {@code total_files_found}, so the job finishes as
     * COMPLETED_WITH_ERRORS instead of failing the whole upload over one bad file.
     */
    private void insertBatch(List<Benchmark> batch, int startIndex) throws SQLException {
        if (batch.isEmpty()) return;

        final List<Benchmark> valid;
        try {
            valid = validateBatch(batch);
        } catch (IOException | StarExecException e) {
            String errorMsg = "Benchmark validation failed for " + batch.size() + " files: " + e.getMessage();
            log.error(errorMsg, e);
            UploadJobQueue.appendError(job.getId(), errorMsg);
            throw new SQLException(errorMsg, e);
        }

        // Checkpoints advance over the whole batch even when some files were rejected,
        // so a retry resumes past them instead of re-validating them forever.
        final String batchEndPath = batch.get(batch.size() - 1).getPath();
        final int processedAfter = totalFilesProcessed.get() + valid.size();
        final int consumedAfter = totalFilesConsumed.get() + batch.size();

        if (valid.isEmpty()) {
            totalFilesConsumed.set(consumedAfter);
            lastProcessedPath = batchEndPath;
            // Nothing commits for a wholly rejected batch, and the per-batch checkpoint
            // is what now refreshes last_heartbeat. Without this the job would look
            // stalled in the UI while it worked through a long run of rejected files.
            UploadJobQueue.touchJob(job.getId());
            return;
        }

        try {
            Common.runInTransaction((Connection con) -> {
                insertBenchmarksBatch(valid, con);
                Benchmarks.persistAttributesAndDependencies(valid, con);
                // The checkpoint commits with the rows it describes. Written separately,
                // a crash in between would leave the job resuming before benchmarks that
                // were already committed, re-inserting them on retry.
                UploadJobQueue.updateProgressInTransaction(con, job.getId(), null,
                        processedAfter, totalSpacesCreated.get(),
                        batchEndPath, startIndex + consumedAfter - 1);
            });
        } catch (Exception e) {
            String errorMsg = "Batch insert failed for " + valid.size() + " files: " + e.getMessage();
            log.error(errorMsg, e);
            UploadJobQueue.appendError(job.getId(), errorMsg);
            throw new SQLException(errorMsg, e);
        }

        // Only after the commit: these counters must describe durable state, since the
        // stall detector and the final flush both read them.
        totalFilesProcessed.set(processedAfter);
        totalFilesConsumed.set(consumedAfter);
        lastProcessedPath = batchEndPath;
    }

    /**
     * Files the benchmark processor rejected during this run.
     *
     * <p>They were never inserted, so no {@code benchmarks} row accounts for the disk
     * they occupy and no quota was charged for them. Whoever owns the extraction
     * directory must delete them once the job can no longer be resumed, or an upload
     * whose files are all rejected consumes disk permanently and for free.
     *
     * @return an immutable snapshot of the rejected paths
     */
    public List<String> getRejectedPaths() {
        synchronized (rejectedPaths) {
            return List.copyOf(rejectedPaths);
        }
    }

    /**
     * The dependency root for this upload, defaulting to the job's own space.
     *
     * <p>{@code dep_root_space_id} is nullable and the REST layer accepts
     * {@code hasDependencies=true} without it. {@code handleConvertMethod} applies this
     * same fallback; without it the dump path hands a null root to dependency
     * resolution, {@code findDependentBench} can resolve nothing, and every upload that
     * declares dependencies without naming a root fails outright.
     */
    private Integer resolveDepRootSpaceId(boolean usesDeps) {
        Integer depRootSpaceId = job.getDepRootSpaceId();
        if (usesDeps && depRootSpaceId == null) {
            return job.getSpaceId();
        }
        return depRootSpaceId;
    }

    /**
     * Runs the benchmark processor over the batch and returns those it validated.
     *
     * @return the subset of {@code batch} that passed, each carrying its attributes and
     *         any resolved dependencies
     */
    private List<Benchmark> validateBatch(List<Benchmark> batch) throws IOException, StarExecException {
        boolean usesDeps = job.isHasDependencies();
        // The return value reports dependency resolution, which the per-benchmark
        // attribute check below cannot see: validateDependencies stops at the first
        // include path it cannot resolve, leaving benchmarks that still look valid.
        // Inserting them would produce rows with missing or partial bench_dependency
        // entries, and the failure would only surface later when a job pair tried to
        // stage the absent axiom. The synchronous path refuses the upload here, so this
        // one does too.
        if (!Benchmarks.validateForUpload(batch, resolveDepRootSpaceId(usesDeps), job.isLinked(), usesDeps, null)) {
            throw new StarExecException("Benchmark dependencies could not be resolved for this upload; "
                    + "check the dependency root space and the benchmark processor output");
        }

        List<Benchmark> valid = new ArrayList<>(batch.size());
        int rejected = 0;
        for (Benchmark b : batch) {
            if (Benchmarks.isBenchValid(b.getAttributes())) {
                valid.add(b);
            } else {
                rejected++;
                // Kept so the file can be removed once the job completes. It carries no
                // benchmarks row, so nothing else records that it occupies disk.
                rejectedPaths.add(b.getPath());
                log.warn("validateBatch", "Benchmark rejected by processor: " + b.getName());
            }
        }

        if (rejected > 0) {
            // Counted here, reported once by process(). append_upload_job_error
            // concatenates into an unbounded TEXT column, so appending per batch would
            // grow error_message without limit on an upload that rejects many files.
            totalFilesRejected.addAndGet(rejected);
        }
        return valid;
    }

    private void throwIfCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || UploadJobQueue.isCancelRequested(job.getId())) {
            throw new InterruptedException("Upload job cancellation requested");
        }
    }
    
    /**
     * Inserts a batch of benchmarks and links each one to the upload job's space.
     *
     * <p>Delegates to {@code starexec.AddAndAssociateBenchmarks} so that the three
     * invariants of adding a benchmark — charging the user's disk quota, inserting the
     * {@code benchmarks} row, and creating the {@code bench_assoc} link — stay defined
     * in exactly one place. A hand-rolled INSERT here previously satisfied only the
     * second, which left uploaded benchmarks invisible in their space and left
     * {@code users.disk_size} permanently under-counted.
     *
     * <p>Each benchmark is assigned the id the database gave it, so that attributes and
     * dependencies can be written against it in the same transaction.
     *
     * @return the ids of the newly inserted benchmarks
     */
    private List<Integer> insertBenchmarksBatch(List<Benchmark> batch, Connection con) throws SQLException {
        String[] names = new String[batch.size()];
        String[] paths = new String[batch.size()];
        Long[] diskSizes = new Long[batch.size()];

        for (int i = 0; i < batch.size(); i++) {
            Benchmark b = batch.get(i);
            names[i] = b.getName();
            paths[i] = b.getPath();
            diskSizes[i] = new File(b.getPath()).length();
        }

        // Keyed by path, not by position: RETURNING makes no ordering guarantee, which
        // is why the procedure hands back the path alongside the id.
        Map<String, Integer> idsByPath = new HashMap<>(batch.size());
        try (PreparedStatement ps = con.prepareStatement(ADD_AND_ASSOCIATE_SQL)) {
            ps.setArray(1, con.createArrayOf("text", names));
            ps.setArray(2, con.createArrayOf("text", paths));
            ps.setArray(3, con.createArrayOf("bigint", diskSizes));
            ps.setInt(4, job.getUserId());
            ps.setInt(5, job.getBenchmarkTypeId());
            ps.setBoolean(6, job.isDownloadable());
            ps.setInt(7, job.getSpaceId());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    idsByPath.put(rs.getString("bench_path"), rs.getInt("bench_id"));
                }
            }
        }

        if (idsByPath.size() != batch.size()) {
            throw new SQLException("Benchmark insert mismatch: expected " + batch.size()
                    + ", got " + idsByPath.size());
        }

        List<Integer> ids = new ArrayList<>(batch.size());
        for (Benchmark b : batch) {
            Integer id = idsByPath.get(b.getPath());
            if (id == null) {
                throw new SQLException("Insert returned no id for benchmark path " + b.getPath());
            }
            b.setId(id);
            ids.add(id);
        }
        return ids;
    }
}
