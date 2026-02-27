package org.starexec.data.processing;

import org.starexec.data.database.Common;
import org.starexec.data.database.UploadJobQueue;
import org.starexec.data.to.UploadJob;
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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final int PROGRESS_UPDATE_BATCHES = 10;
    
    // Thread management
    private final ExecutorService executor;
    private final AtomicBoolean traversalComplete = new AtomicBoolean(false);
    private final AtomicInteger totalFilesFound = new AtomicInteger(0);
    private final AtomicInteger totalFilesProcessed = new AtomicInteger(0);
    private final AtomicInteger totalSpacesCreated = new AtomicInteger(0);
    
    // Job context
    private final UploadJob job;
    private final File directory;
    
    public BoundedUploadProcessor(UploadJob job, File directory) {
        this.job = job;
        this.directory = directory;
        
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
        
        // Phase 1: Collect all file paths in deterministic order
        List<String> sortedFilePaths = collectSortedFilePaths();
        log.info(method, "Found " + sortedFilePaths.size() + " files for job " + job.getId());
        
        // Phase 2: Calculate start index for retry scenarios
        int startIndex = calculateStartIndex(sortedFilePaths);
        if (startIndex > 0) {
            log.info(method, "Resuming from index " + startIndex + " for job " + job.getId());
        }
        
        // Phase 3: Process files with backpressure
        processSortedFiles(sortedFilePaths, startIndex);
        
        log.info(method, "Completed upload processing for job " + job.getId() + 
                ", processed " + totalFilesProcessed.get() + " files");
    }
    
    /**
     * Collects all file paths in deterministic (alphabetical) order using TreeSet.
     */
    private List<String> collectSortedFilePaths() throws IOException {
        TreeSet<String> sortedPaths = new TreeSet<>();
        
        Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
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
                String fileName = file.getFileName().toString();
                if (Validator.shouldIgnoreFile(fileName)) {
                    return FileVisitResult.CONTINUE;
                }
                if (!Validator.isValidBenchName(fileName)) {
                    return FileVisitResult.CONTINUE;
                }
                sortedPaths.add(file.toAbsolutePath().toString());
                totalFilesFound.incrementAndGet();
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
        return 0;
    }
    
    /**
     * Processes files with backpressure using queue.
     */
        private void processSortedFiles(List<String> sortedPaths, int startIndex) 
            throws SQLException, InterruptedException, IOException {
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        
        try {
            executor.submit(() -> {
                try {
                    producePaths(queue, sortedPaths, startIndex);
                } catch (IOException e) {
                    log.error("producePaths failed", e);
                }
            });
            executor.submit(() -> {
                try {
                    consumeAndInsert(queue);
                } catch (SQLException | InterruptedException e) {
                    log.error("consumeAndInsert failed", e);
                }
            });
            
            executor.shutdown();
            int remaining = sortedPaths.size() - startIndex;
            int timeout = Math.max(30, Math.min(remaining / 100, 3600));
            if (!executor.awaitTermination(timeout, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                throw new IOException("Processing timed out");
            }
        } catch (Exception e) {
            executor.shutdownNow();
            throw new IOException("Processing failed", e);
        }
    }
    
    private void producePaths(BlockingQueue<String> queue, List<String> paths, int startIndex) throws IOException {
        try {
            for (int i = startIndex; i < paths.size(); i++) {
                if (Thread.currentThread().isInterrupted()) break;
                queue.put(paths.get(i));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            traversalComplete.set(true);
        }
    }
    
    private void consumeAndInsert(BlockingQueue<String> queue) throws SQLException, InterruptedException {
        List<BenchmarkMetadata> batch = new ArrayList<>(BATCH_SIZE);
        int batchCount = 0;
        
        try {
            while (!traversalComplete.get() || !queue.isEmpty()) {
                String path = queue.poll(1, TimeUnit.SECONDS);
                if (path != null) {
                    batch.add(createMetadata(path));
                    
                    // Drain more
                    List<String> drained = new ArrayList<>();
                    queue.drainTo(drained, BATCH_SIZE - batch.size());
                    for (String p : drained) {
                        batch.add(createMetadata(p));
                    }
                    
                    if (batch.size() >= BATCH_SIZE) {
                        insertBatch(batch);
                        batchCount++;
                        batch.clear();
                        
                        if (batchCount % PROGRESS_UPDATE_BATCHES == 0) {
                            UploadJobQueue.updateProgress(job.getId(), null,
                                    totalFilesProcessed.get(), totalSpacesCreated.get(),
                                    null, totalFilesProcessed.get());
                            UploadJobQueue.touchJob(job.getId());
                        }
                    }
                }
            }
            
            if (!batch.isEmpty()) {
                insertBatch(batch);
            }
            
            UploadJobQueue.updateProgress(job.getId(), null,
                    totalFilesProcessed.get(), totalSpacesCreated.get(),
                    null, totalFilesProcessed.get());
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private BenchmarkMetadata createMetadata(String path) {
        BenchmarkMetadata m = new BenchmarkMetadata();
        m.setPath(path);
        m.setName(new File(path).getName());
        m.setTypeId(job.getBenchmarkTypeId());
        m.setDownloadable(job.isDownloadable());
        m.setUserId(job.getUserId());
        return m;
    }
    
    private void insertBatch(List<BenchmarkMetadata> batch) throws SQLException {
        if (batch.isEmpty()) return;
        
        try {
            Common.runInTransaction((Connection con) -> {
                List<Integer> ids = insertBenchmarksBatch(batch, con);
                totalFilesProcessed.addAndGet(batch.size());
            });
        } catch (Exception e) {
            String errorMsg = "Batch insert failed for " + batch.size() + " files: " + e.getMessage();
            log.error(errorMsg, e);
            UploadJobQueue.appendError(job.getId(), errorMsg);
            throw new SQLException(errorMsg, e);
        }
    }
    
    private List<Integer> insertBenchmarksBatch(List<BenchmarkMetadata> batch, Connection con) throws SQLException {
        String sql = "INSERT INTO benchmarks (name, path, user_id, bench_type, downloadable, disk_size) VALUES (?, ?, ?, ?, ?, ?)";
        List<Integer> ids = new ArrayList<>();
        
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (BenchmarkMetadata m : batch) {
                ps.setString(1, m.getName());
                ps.setString(2, m.getPath());
                ps.setInt(3, m.getUserId());
                ps.setInt(4, m.getTypeId());
                ps.setBoolean(5, m.isDownloadable());
                ps.setLong(6, new File(m.getPath()).length());
                ps.addBatch();
            }
            
            ps.executeBatch();
            
            try (ResultSet keys = ps.getGeneratedKeys()) {
                while (keys.next()) {
                    ids.add(keys.getInt(1));
                }
            }
            
            if (ids.size() != batch.size()) {
                throw new SQLException("Generated keys mismatch: expected " + batch.size() + ", got " + ids.size());
            }
        }
        return ids;
    }
    
    private boolean isConnectionFailure(SQLException e) {
        String state = e.getSQLState();
        return state != null && (state.startsWith("08") || state.equals("57P01") || state.equals("57P02") || state.equals("57P03"));
    }
    
    private static class BenchmarkMetadata {
        private String path, name;
        private int typeId, userId;
        private boolean downloadable;
        
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getTypeId() { return typeId; }
        public void setTypeId(int typeId) { this.typeId = typeId; }
        public int getUserId() { return userId; }
        public void setUserId(int userId) { this.userId = userId; }
        public boolean isDownloadable() { return downloadable; }
        public void setDownloadable(boolean downloadable) { this.downloadable = downloadable; }
    }
}