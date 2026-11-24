package org.starexec.util;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
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
import java.util.Enumeration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.file.FileVisitResult.CONTINUE;

/**
 * Safe archive extraction with zip bomb protection and cleanup guarantees.
 */
public class ArchiveExtractor {
    private static final StarLogger log = StarLogger.getLogger(ArchiveExtractor.class);
    
    // Safety limits
    private static final long MAX_UNCOMPRESSED_SIZE_BYTES = 100L * 1024 * 1024 * 1024; // 100GB max
    // Keep this as a long expression. An int expression overflows and makes tiny entries look "too large".
    private static final long MAX_ENTRY_SIZE_BYTES = 10L * 1024 * 1024 * 1024; // 10GB per file
    private static final int BUFFER_SIZE = 65536;
    private static final int MAX_ENTRY_NAME_LENGTH = 255;
    private static final long MAX_TOTAL_TIMEOUT_MULTIPLIER = 4L;

    /** Sentinel for {@link ExtractionSettings#withRemainingQuotaBytes(long)}: no quota cap. */
    public static final long NO_QUOTA_LIMIT = 0L;

    /**
     * Signals that extraction stopped because it would exceed the uploading user's remaining
     * disk quota.
     *
     * <p>This is an accounting limit, not a security event. It is deliberately distinct from the
     * {@link SecurityException} raised by the zip-bomb caps so that a user who has simply run out
     * of space is never told their upload was rejected as malicious.
     */
    public static final class QuotaExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public QuotaExceededException(String message) {
            super(message);
        }
    }

    public static final class ExtractionSettings {
        private final long maxUncompressedSizeBytes;
        private final long remainingQuotaBytes;
        private final int timeoutSeconds;
        private final long maxTotalTimeoutSeconds;
        private final LongSupplier clockMillis;
        private final Runnable progressCallback;
        private final BooleanSupplier cancellationRequested;

        private ExtractionSettings(long maxUncompressedSizeBytes, long remainingQuotaBytes, int timeoutSeconds,
                                   long maxTotalTimeoutSeconds, LongSupplier clockMillis,
                                   Runnable progressCallback, BooleanSupplier cancellationRequested) {
            this.maxUncompressedSizeBytes = maxUncompressedSizeBytes;
            this.remainingQuotaBytes = remainingQuotaBytes;
            this.timeoutSeconds = timeoutSeconds;
            this.maxTotalTimeoutSeconds = maxTotalTimeoutSeconds;
            this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
            this.progressCallback = progressCallback;
            this.cancellationRequested = cancellationRequested;
        }

        public static ExtractionSettings defaults() {
            return new ExtractionSettings(
                MAX_UNCOMPRESSED_SIZE_BYTES,
                NO_QUOTA_LIMIT,
                0,
                0L,
                System::currentTimeMillis,
                null,
                null
            );
        }

        public static ExtractionSettings fromEnvironment() {
            int timeout = EnvironmentConfig.getUploadExtractionTimeoutSeconds();
            return new ExtractionSettings(
                EnvironmentConfig.getUploadExtractionMaxUncompressedBytes(),
                NO_QUOTA_LIMIT,
                timeout,
                defaultMaxTotalTimeoutSeconds(timeout),
                System::currentTimeMillis,
                null,
                null
            );
        }

        private static long defaultMaxTotalTimeoutSeconds(int inactivityTimeoutSeconds) {
            if (inactivityTimeoutSeconds <= 0) {
                return 0L;
            }
            return (long) inactivityTimeoutSeconds * MAX_TOTAL_TIMEOUT_MULTIPLIER;
        }

        private long effectiveMaxTotalTimeoutSeconds() {
            if (maxTotalTimeoutSeconds > 0L) {
                return maxTotalTimeoutSeconds;
            }
            return defaultMaxTotalTimeoutSeconds(timeoutSeconds);
        }

        /**
         * Sets the global anti-zip-bomb cap. This is a security limit shared by every upload; it
         * is not the uploading user's quota -- see {@link #withRemainingQuotaBytes(long)}.
         */
        public ExtractionSettings withMaxUncompressedSizeBytes(long maxBytes) {
            return new ExtractionSettings(maxBytes, remainingQuotaBytes, timeoutSeconds, maxTotalTimeoutSeconds,
                clockMillis, progressCallback, cancellationRequested);
        }

        /**
         * Caps extraction at the uploading user's remaining disk quota. Pass
         * {@link ArchiveExtractor#NO_QUOTA_LIMIT} (or any non-positive value) to extract with no
         * quota cap.
         */
        public ExtractionSettings withRemainingQuotaBytes(long quotaBytes) {
            return new ExtractionSettings(maxUncompressedSizeBytes, quotaBytes, timeoutSeconds, maxTotalTimeoutSeconds,
                clockMillis, progressCallback, cancellationRequested);
        }

        public ExtractionSettings withTimeoutSeconds(int newTimeoutSeconds) {
            return new ExtractionSettings(maxUncompressedSizeBytes, remainingQuotaBytes, newTimeoutSeconds,
                defaultMaxTotalTimeoutSeconds(newTimeoutSeconds), clockMillis, progressCallback, cancellationRequested);
        }

        public ExtractionSettings withMaxTotalTimeoutSeconds(long newMaxTotalTimeoutSeconds) {
            return new ExtractionSettings(maxUncompressedSizeBytes, remainingQuotaBytes, timeoutSeconds,
                newMaxTotalTimeoutSeconds, clockMillis, progressCallback, cancellationRequested);
        }

        public ExtractionSettings withClock(LongSupplier newClockMillis) {
            return new ExtractionSettings(maxUncompressedSizeBytes, remainingQuotaBytes, timeoutSeconds,
                maxTotalTimeoutSeconds, newClockMillis, progressCallback, cancellationRequested);
        }

        public ExtractionSettings withProgressCallback(Runnable newProgressCallback) {
            return new ExtractionSettings(maxUncompressedSizeBytes, remainingQuotaBytes, timeoutSeconds,
                maxTotalTimeoutSeconds, clockMillis, newProgressCallback, cancellationRequested);
        }

        public ExtractionSettings withCancellationRequested(BooleanSupplier newCancellationRequested) {
            return new ExtractionSettings(maxUncompressedSizeBytes, remainingQuotaBytes, timeoutSeconds,
                maxTotalTimeoutSeconds, clockMillis, progressCallback, newCancellationRequested);
        }
    }

    private static final class ExtractionMonitor {
        private final ExtractionSettings settings;
        private final long startMillis;
        private long lastProgressMillis;

        private ExtractionMonitor(ExtractionSettings settings) {
            this.settings = settings;
            this.startMillis = settings.clockMillis.getAsLong();
            this.lastProgressMillis = this.startMillis;
        }

        private void recordProgress() {
            this.lastProgressMillis = settings.clockMillis.getAsLong();
        }

        private void check(Path archivePath, long bytesExtracted) throws IOException {
            validateTotalSize(bytesExtracted, settings);
            if (settings.timeoutSeconds > 0) {
                long now = settings.clockMillis.getAsLong();
                long idleLimitMillis = settings.timeoutSeconds * 1000L;
                if ((now - lastProgressMillis) > idleLimitMillis) {
                    throw new IOException(
                        "Archive extraction timed out after " + settings.timeoutSeconds +
                            " seconds without progress for " + archivePath.getFileName()
                    );
                }
                long maxTotalSeconds = settings.effectiveMaxTotalTimeoutSeconds();
                if (maxTotalSeconds > 0L && (now - startMillis) > (maxTotalSeconds * 1000L)) {
                    throw new IOException(
                        "Archive extraction timed out after exceeding maximum duration of " +
                            maxTotalSeconds + " seconds for " + archivePath.getFileName()
                    );
                }
            }
            if (settings.cancellationRequested != null && settings.cancellationRequested.getAsBoolean()) {
                throw new IOException("Archive extraction cancelled for " + archivePath.getFileName());
            }
        }
    }

    /**
     * Builds the user-facing explanation for an upload that does not fit in the uploader's
     * remaining disk quota.
     *
     * <p>The pre-flight estimate and the streaming check both use this, so the uploader sees the
     * same explanation and the same remedy whichever one trips first.
     *
     * @param requiredBytes bytes the extraction needs, or has already written
     * @param remainingQuotaBytes bytes the uploader has left
     */
    public static String formatQuotaExceededMessage(long requiredBytes, long remainingQuotaBytes) {
        return "Not enough disk quota to extract this archive: it needs at least " + requiredBytes
            + " bytes, but only " + remainingQuotaBytes + " bytes remain in your quota. "
            + "Empty your recycle bin to reclaim space, or ask an administrator to raise your quota.";
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
        } catch (QuotaExceededException e) {
            // Out of disk quota, not a zip bomb. Pass the message through unprefixed so the
            // uploader is told what to reclaim instead of being accused of an attack.
            // Keep the absolute path and the byte counts here: the user-facing message
            // deliberately carries neither the path nor any server detail.
            log.info(method, "Disk quota exhausted while extracting archive " + archivePath
                + ": " + e.getMessage());
            cleanupOnFailure(extractDir);
            throw new IOException(e.getMessage());
        } catch (SecurityException e) {
            // Safety limit exceeded - cleanup partial extraction
            log.warn(method, "Safety limit exceeded for archive: " + archivePath, e);
            cleanupOnFailure(extractDir);
            throw new IOException("Archive exceeds safety limits: " + e.getMessage(), e);
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
        ExtractionMonitor monitor = new ExtractionMonitor(settings);

        try (ZipFile zipFile = new ZipFile(archivePath.toFile())) {
            Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                monitor.check(archivePath, totalUncompressedSize);
                ZipArchiveEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (entryName.length() > MAX_ENTRY_NAME_LENGTH) {
                    throw new SecurityException("Entry name too long: " + entryName);
                }
                if (entry.isUnixSymlink()) {
                    throw new SecurityException("Archive contains unsupported symlink entry: " + entryName);
                }

                Path targetPath = validateTargetPath(extractDir, entryName);

                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                    ArchiveUtil.applyPosixPermissions(targetPath, entry.getUnixMode());
                    monitor.recordProgress();
                    continue;
                }

                Files.createDirectories(targetPath.getParent());
                if (Files.isSymbolicLink(targetPath)) {
                    throw new SecurityException("Target path is a symbolic link: " + entryName);
                }

                try (InputStream is = zipFile.getInputStream(entry);
                     OutputStream os = Files.newOutputStream(targetPath);
                     BufferedOutputStream bos = new BufferedOutputStream(os, BUFFER_SIZE)) {
                    long entryBytes = copyEntryData(is, bos, entryName, archivePath, settings, monitor, totalUncompressedSize);
                    if (entryBytes > MAX_ENTRY_SIZE_BYTES) {
                        throw new SecurityException("Entry too large: " + entryName + " (" + entryBytes + " bytes)");
                    }
                    totalUncompressedSize += entryBytes;
                    validateTotalSize(totalUncompressedSize, settings);
                }
                ArchiveUtil.applyPosixPermissions(targetPath, entry.getUnixMode());
                monitor.recordProgress();

                entryCount++;
                if (extractedCount != null) {
                    extractedCount.set(entryCount);
                }
                invokeProgressCallback(settings);
            }
        }
        
        log.info(method, "Extracted " + entryCount + " entries (" + 
                totalUncompressedSize + " bytes) from " + archivePath);
        
        // Set the extracted count if requested. This is also updated as entries
        // are extracted so callers can expose progress for large archives.
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
        ExtractionMonitor monitor = new ExtractionMonitor(settings);

        try (InputStream fileInput = Files.newInputStream(archivePath);
             BufferedInputStream bufferedInput = new BufferedInputStream(fileInput, BUFFER_SIZE);
             InputStream archiveInput = archivePath.getFileName().toString().toLowerCase().endsWith(".tar")
                 ? bufferedInput
                 : new GzipCompressorInputStream(bufferedInput);
             TarArchiveInputStream tarInput = new TarArchiveInputStream(archiveInput)) {

            TarArchiveEntry entry;
            while ((entry = tarInput.getNextTarEntry()) != null) {
                monitor.check(archivePath, totalUncompressedSize);
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
                    ArchiveUtil.applyPosixPermissions(targetPath, entry.getMode());
                    monitor.recordProgress();
                    continue;
                }

                Files.createDirectories(targetPath.getParent());
                try (OutputStream outputStream = Files.newOutputStream(targetPath);
                     BufferedOutputStream bufferedOutput = new BufferedOutputStream(outputStream, BUFFER_SIZE)) {
                    long entryBytes = copyEntryData(tarInput, bufferedOutput, entryName, archivePath, settings, monitor, totalUncompressedSize);
                    if (entryBytes > MAX_ENTRY_SIZE_BYTES) {
                        throw new SecurityException("Entry too large: " + entryName + " (" + entryBytes + " bytes)");
                    }
                    totalUncompressedSize += entryBytes;
                    validateTotalSize(totalUncompressedSize, settings);
                }
                ArchiveUtil.applyPosixPermissions(targetPath, entry.getMode());
                monitor.recordProgress();

                count++;
                if (extractedCount != null) {
                    extractedCount.set(count);
                }
                invokeProgressCallback(settings);
            }
        }
        
        // Set the extracted count if requested. This is also updated as entries
        // are extracted so callers can expose progress for large archives.
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
        if (entryName == null || entryName.isEmpty() || entryName.indexOf('\0') >= 0) {
            throw new SecurityException("Archive contains invalid entry name: " + entryName);
        }
        Path normalizedBase = extractDir.toAbsolutePath().normalize();
        Path targetPath = normalizedBase.resolve(entryName).normalize();
        if (!targetPath.startsWith(normalizedBase)) {
            throw new SecurityException("Entry attempts path traversal: " + entryName);
        }
        return targetPath;
    }

    private static long copyEntryData(InputStream inputStream, OutputStream outputStream, String entryName,
                                      Path archivePath, ExtractionSettings settings, ExtractionMonitor monitor,
                                      long bytesExtractedSoFar)
        throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long entryBytes = 0L;
        int read;
        monitor.check(archivePath, bytesExtractedSoFar);
        while ((read = inputStream.read(buffer)) != -1) {
            if (read > 0) {
                monitor.recordProgress();
            }
            long projectedEntryBytes = entryBytes + read;
            long projectedTotalBytes = bytesExtractedSoFar + projectedEntryBytes;
            monitor.check(archivePath, projectedTotalBytes);
            if (projectedEntryBytes > MAX_ENTRY_SIZE_BYTES) {
                throw new SecurityException("Entry too large: " + entryName + " (" + projectedEntryBytes + " bytes)");
            }
            outputStream.write(buffer, 0, read);
            entryBytes = projectedEntryBytes;
        }
        return entryBytes;
    }

    private static void validateTotalSize(long totalUncompressedSize, ExtractionSettings settings) {
        long securityLimit = settings.maxUncompressedSizeBytes > 0
            ? settings.maxUncompressedSizeBytes
            : MAX_UNCOMPRESSED_SIZE_BYTES;
        if (totalUncompressedSize > securityLimit) {
            throw new SecurityException("Total uncompressed size exceeds " + securityLimit + " bytes");
        }
        // Checked second and reported separately: running out of quota is the uploader's own
        // accounting, and must not be surfaced as a zip-bomb rejection.
        if (settings.remainingQuotaBytes > 0 && totalUncompressedSize > settings.remainingQuotaBytes) {
            throw new QuotaExceededException(
                formatQuotaExceededMessage(totalUncompressedSize, settings.remainingQuotaBytes)
            );
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
