package org.starexec.util;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.FileUtils;
import org.starexec.config.EnvironmentConfig;
import org.starexec.logger.StarLogger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.nio.file.FileVisitResult.CONTINUE;

/**
 * Safe archive extraction with zip bomb protection and cleanup guarantees.
 */
public class ArchiveExtractor {
    private static final StarLogger log = StarLogger.getLogger(ArchiveExtractor.class);
    
    // Safety limits
    private static final long MAX_UNCOMPRESSED_SIZE_BYTES = 100L * 1024 * 1024 * 1024; // 100GB max
    private static final long MAX_ENTRY_SIZE_BYTES = 10L * 1024 * 1024 * 1024; // 10GB per file
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_ENTRY_NAME_LENGTH = 255;

    public static final class ExtractionSettings {
        private final long maxUncompressedSizeBytes;
        private final int timeoutSeconds;
        private final long deadlineEpochMillis;
        private final Runnable progressCallback;
        private final BooleanSupplier cancellationRequested;

        private ExtractionSettings(long maxUncompressedSizeBytes, int timeoutSeconds, Runnable progressCallback,
                                   BooleanSupplier cancellationRequested) {
            this.maxUncompressedSizeBytes = maxUncompressedSizeBytes;
            this.timeoutSeconds = timeoutSeconds;
            this.deadlineEpochMillis = timeoutSeconds > 0
                ? System.currentTimeMillis() + (timeoutSeconds * 1000L)
                : Long.MAX_VALUE;
            this.progressCallback = progressCallback;
            this.cancellationRequested = cancellationRequested;
        }

        public static ExtractionSettings defaults() {
            return new ExtractionSettings(MAX_UNCOMPRESSED_SIZE_BYTES, 0, null, null);
        }

        public static ExtractionSettings fromEnvironment() {
            return new ExtractionSettings(
                EnvironmentConfig.getUploadExtractionMaxUncompressedBytes(),
                EnvironmentConfig.getUploadExtractionTimeoutSeconds(),
                null,
                null
            );
        }

        public ExtractionSettings withMaxUncompressedSizeBytes(long maxBytes) {
            return new ExtractionSettings(maxBytes, timeoutSeconds, progressCallback, cancellationRequested);
        }

        public ExtractionSettings withTimeoutSeconds(int newTimeoutSeconds) {
            return new ExtractionSettings(maxUncompressedSizeBytes, newTimeoutSeconds, progressCallback, cancellationRequested);
        }

        public ExtractionSettings withProgressCallback(Runnable newProgressCallback) {
            return new ExtractionSettings(maxUncompressedSizeBytes, timeoutSeconds, newProgressCallback, cancellationRequested);
        }

        public ExtractionSettings withCancellationRequested(BooleanSupplier newCancellationRequested) {
            return new ExtractionSettings(maxUncompressedSizeBytes, timeoutSeconds, progressCallback, newCancellationRequested);
        }
    }
    
    /**
     * Extracts an archive safely with circuit breaker protection.
     * 
     * @param archivePath Path to the archive file
     * @param extractDir Directory to extract to
     * @return Path to the extracted directory
     * @throws IOException if extraction fails or safety limits are exceeded
     */
    public static Path extractSafely(String archivePath, Path extractDir) throws IOException {
        return extractSafely(archivePath, extractDir, null, ExtractionSettings.defaults());
    }
    
    /**
     * Extracts an archive to the specified directory with safety checks.
     * This version also tracks the count of extracted files.
     *
     * @param archivePath Path to the archive file
     * @param extractDir Directory to extract to
     * @param extractedCount Optional AtomicInteger to receive the count of extracted files (can be null)
     * @return Path to the extracted directory
     * @throws IOException if extraction fails or safety limits are exceeded
     */
    public static Path extractSafely(String archivePath, Path extractDir, AtomicInteger extractedCount) throws IOException {
        return extractSafely(archivePath, extractDir, extractedCount, ExtractionSettings.defaults());
    }

    public static Path extractSafely(String archivePath, Path extractDir, AtomicInteger extractedCount,
                                     ExtractionSettings settings) throws IOException {
        String method = "extractSafely";
        
        // Validate archive exists
        File archiveFile = new File(archivePath);
        if (!archiveFile.exists()) {
            throw new IOException("Archive file not found: " + archivePath);
        }
        
        // Determine archive type and extract
        String lowerName = archiveFile.getName().toLowerCase();
        
        try {
            if (lowerName.endsWith(".zip")) {
                return extractZip(archiveFile.toPath(), extractDir, extractedCount, settings);
            } else if (lowerName.endsWith(".tar") || lowerName.endsWith(".tar.gz") ||
                       lowerName.endsWith(".tgz")) {
                return extractTar(archiveFile.toPath(), extractDir, extractedCount, settings);
            } else {
                throw new IOException("Unsupported archive format: " + archiveFile.getName());
            }
        } catch (SecurityException e) {
            // Safety limit exceeded - cleanup partial extraction
            log.warn(method, "Safety limit exceeded for archive: " + archivePath, e);
            cleanupOnFailure(extractDir);
            throw new IOException("Archive exceeds safety limits: " + e.getMessage());
        }
    }
    
    /**
     * Extracts a ZIP file with zip bomb protection.
     */
    private static Path extractZip(Path archivePath, Path extractDir, AtomicInteger extractedCount,
                                   ExtractionSettings settings) throws IOException {
        String method = "extractZip";

        long totalUncompressedSize = 0;
        int entryCount = 0;
        
        try (InputStream is = Files.newInputStream(archivePath);
             BufferedInputStream bis = new BufferedInputStream(is);
             ZipInputStream zis = new ZipInputStream(bis)) {
            
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                checkExtractionState(settings, archivePath, totalUncompressedSize);
                // Validate entry name
                String entryName = entry.getName();
                if (entryName.length() > MAX_ENTRY_NAME_LENGTH) {
                    throw new SecurityException("Entry name too long: " + entryName);
                }
                
                // Skip directory entries
                if (entry.isDirectory()) {
                    continue;
                }
                
                // Extract entry
                Path targetPath = validateTargetPath(extractDir, entryName);
                
                // Create parent directories
                Files.createDirectories(targetPath.getParent());
                
                // Write file
                try (OutputStream os = Files.newOutputStream(targetPath);
                     BufferedOutputStream bos = new BufferedOutputStream(os)) {
                    long entryBytes = copyEntryData(zis, bos, entryName, archivePath, settings, totalUncompressedSize);
                    if (entryBytes > MAX_ENTRY_SIZE_BYTES) {
                        throw new SecurityException("Entry too large: " + entryName + " (" + entryBytes + " bytes)");
                    }
                    totalUncompressedSize += entryBytes;
                    validateTotalSize(totalUncompressedSize, settings);
                }
                
                entryCount++;
                invokeProgressCallback(settings);
                zis.closeEntry();
            }
        }
        
        log.info(method, "Extracted " + entryCount + " entries (" + 
                totalUncompressedSize + " bytes) from " + archivePath);
        
        // Set the extracted count if requested
        if (extractedCount != null) {
            extractedCount.set(entryCount);
        }
        
        return extractDir;
    }
    
    /**
     * Extracts a TAR/TGZ file.
     * Note: For simplicity, this is a placeholder. Production should use Apache Commons Compress.
     */
    private static Path extractTar(Path archivePath, Path extractDir, AtomicInteger extractedCount,
                                   ExtractionSettings settings) throws IOException {
        String method = "extractTar";

        long totalUncompressedSize = 0L;
        int count = 0;

        try (InputStream fileInput = Files.newInputStream(archivePath);
             BufferedInputStream bufferedInput = new BufferedInputStream(fileInput);
             InputStream archiveInput = archivePath.getFileName().toString().toLowerCase().endsWith(".tar")
                 ? bufferedInput
                 : new GzipCompressorInputStream(bufferedInput);
             TarArchiveInputStream tarInput = new TarArchiveInputStream(archiveInput)) {

            TarArchiveEntry entry;
            while ((entry = tarInput.getNextTarEntry()) != null) {
                checkExtractionState(settings, archivePath, totalUncompressedSize);
                String entryName = entry.getName();
                if (entryName == null || entryName.isEmpty()) {
                    continue;
                }
                if (entryName.length() > MAX_ENTRY_NAME_LENGTH) {
                    throw new SecurityException("Entry name too long: " + entryName);
                }
                if (entry.isSymbolicLink() || entry.isLink()) {
                    throw new SecurityException("Archive contains unsupported link entry: " + entryName);
                }

                Path targetPath = validateTargetPath(extractDir, entryName);
                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                    continue;
                }

                Files.createDirectories(targetPath.getParent());
                try (OutputStream outputStream = Files.newOutputStream(targetPath);
                     BufferedOutputStream bufferedOutput = new BufferedOutputStream(outputStream)) {
                    long entryBytes = copyEntryData(tarInput, bufferedOutput, entryName, archivePath, settings, totalUncompressedSize);
                    if (entryBytes > MAX_ENTRY_SIZE_BYTES) {
                        throw new SecurityException("Entry too large: " + entryName + " (" + entryBytes + " bytes)");
                    }
                    totalUncompressedSize += entryBytes;
                    validateTotalSize(totalUncompressedSize, settings);
                }

                count++;
                invokeProgressCallback(settings);
            }
        }
        
        // Set the extracted count if requested
        if (extractedCount != null) {
            extractedCount.set(count);
        }
        
        log.info(method, "Extracted " + count + " files from " + archivePath);
        return extractDir;
    }
    
    /**
     * Counts the number of regular files in a directory tree.
     * Used for tar extraction which doesn't give us a count upfront.
     */
    public static int countExtractedFiles(Path dir) throws IOException {
        final AtomicInteger count = new AtomicInteger(0);
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isDirectory()) {
                    count.incrementAndGet();
                }
                return CONTINUE;
            }
        });
        // Return the final count of visited files
        return count.get();
    }
    
    /**
     * Cleans up extracted files on failure.
     * Uses try-finally pattern guarantee in caller.
     */
    private static void cleanupOnFailure(Path extractDir) {
        String method = "cleanupOnFailure";
        
        try {
            if (Files.exists(extractDir)) {
                FileUtils.deleteDirectory(extractDir.toFile());
                log.info(method, "Cleaned up failed extraction: " + extractDir);
            }
        } catch (IOException e) {
            log.error(method, "Failed to cleanup extraction directory: " + extractDir, e);
        }
    }
    
    /**
     * Extracts with guaranteed cleanup regardless of success or failure.
     * This is the preferred method to use.
     * 
     * @param archivePath Path to the archive file
     * @param extractDir Directory to extract to (will be created if not exists)
     * @return Path to the extracted directory on success
     * @throws IOException if extraction fails
     */
    public static Path extractWithCleanup(String archivePath, Path extractDir) throws IOException {
        // Call the overloaded version with null for count (backward compatible)
        return extractWithCleanup(archivePath, extractDir, null, ExtractionSettings.defaults());
    }
    
    /**
     * Extracts an archive with zip bomb protection and cleanup guarantees.
     * This version also returns the count of extracted files.
     *
     * @param archivePath Path to the archive file
     * @param extractDir Directory to extract to (will be created if not exists)
     * @param extractedCount Optional AtomicInteger to receive the count of extracted files (can be null)
     * @return Path to the extracted directory on success
     * @throws IOException if extraction fails
     */
    public static Path extractWithCleanup(String archivePath, Path extractDir, AtomicInteger extractedCount) throws IOException {
        return extractWithCleanup(archivePath, extractDir, extractedCount, ExtractionSettings.defaults());
    }

    public static Path extractWithCleanup(String archivePath, Path extractDir, AtomicInteger extractedCount,
                                          ExtractionSettings settings) throws IOException {
        String method = "extractWithCleanup";
        
        boolean success = false;
        
        try {
            // Create extraction directory
            Files.createDirectories(extractDir);
            
            // Extract
            Path result = extractSafely(archivePath, extractDir, extractedCount, settings);
            success = true;
            return result;
            
        } catch (IOException e) {
            // Cleanup on any failure
            cleanupOnFailure(extractDir);
            throw e;
        } finally {
            // This finally block is defensive - main cleanup is in catch block
            if (!success) {
                cleanupOnFailure(extractDir);
            }
        }
    }
    
    /**
     * Cleans up extracted directory for a specific job.
     * Should be called after job completion or failure.
     * 
     * @param extractPath Path to the extracted directory
     * @return true if cleanup succeeded, false otherwise
     */
    public static boolean cleanup(String extractPath) {
        String method = "cleanup";
        
        if (extractPath == null || extractPath.isEmpty()) {
            return true;
        }
        
        try {
            File dir = new File(extractPath);
            if (dir.exists()) {
                FileUtils.deleteDirectory(dir);
                log.info(method, "Cleaned up extraction directory: " + extractPath);
                return true;
            }
            return true; // Already doesn't exist, consider it cleaned
            
        } catch (IOException e) {
            log.error(method, "Failed to cleanup extraction directory: " + extractPath, e);
            return false;
        }
    }

    private static Path validateTargetPath(Path extractDir, String entryName) throws IOException {
        Path targetPath = extractDir.resolve(entryName).normalize();
        if (!targetPath.startsWith(extractDir.normalize())) {
            throw new SecurityException("Entry attempts path traversal: " + entryName);
        }
        return targetPath;
    }

    private static long copyEntryData(InputStream inputStream, OutputStream outputStream, String entryName,
                                      Path archivePath, ExtractionSettings settings, long bytesExtractedSoFar)
        throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long entryBytes = 0L;
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            long projectedEntryBytes = entryBytes + read;
            long projectedTotalBytes = bytesExtractedSoFar + projectedEntryBytes;
            checkExtractionState(settings, archivePath, projectedTotalBytes);
            if (projectedEntryBytes > MAX_ENTRY_SIZE_BYTES) {
                throw new SecurityException("Entry too large: " + entryName + " (" + projectedEntryBytes + " bytes)");
            }
            outputStream.write(buffer, 0, read);
            entryBytes = projectedEntryBytes;
        }
        return entryBytes;
    }

    private static void validateTotalSize(long totalUncompressedSize, ExtractionSettings settings) {
        long configuredLimit = settings.maxUncompressedSizeBytes > 0
            ? settings.maxUncompressedSizeBytes
            : MAX_UNCOMPRESSED_SIZE_BYTES;
        if (totalUncompressedSize > configuredLimit) {
            throw new SecurityException("Total uncompressed size exceeds " + configuredLimit + " bytes");
        }
    }

    private static void checkExtractionState(ExtractionSettings settings, Path archivePath, long bytesExtracted)
        throws IOException {
        validateTotalSize(bytesExtracted, settings);
        if (settings.timeoutSeconds > 0 && System.currentTimeMillis() > settings.deadlineEpochMillis) {
            throw new IOException(
                "Archive extraction timed out after " + settings.timeoutSeconds +
                    " seconds for " + archivePath
            );
        }
        if (settings.cancellationRequested != null && settings.cancellationRequested.getAsBoolean()) {
            throw new IOException("Archive extraction cancelled for " + archivePath);
        }
    }

    private static void invokeProgressCallback(ExtractionSettings settings) {
        if (settings.progressCallback != null) {
            settings.progressCallback.run();
        }
    }
    
    /**
     * Gets the total size of an extracted directory.
     * Useful for quota enforcement.
     * 
     * @param extractPath Path to the extracted directory
     * @return Total size in bytes, or -1 on error
     */
    public static long getExtractedSize(String extractPath) {
        try {
            File dir = new File(extractPath);
            if (dir.exists() && dir.isDirectory()) {
                return FileUtils.sizeOfDirectory(dir);
            }
            return 0;
        } catch (Exception e) {
            return -1;
        }
    }
}
