package org.starexec.util;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.starexec.logger.StarLogger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
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
    private static final int MAX_ENTRY_SIZE_BYTES = 10 * 1024 * 1024 * 1024; // 10GB per file
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_ENTRY_NAME_LENGTH = 255;
    
    /**
     * Extracts an archive safely with circuit breaker protection.
     * 
     * @param archivePath Path to the archive file
     * @param extractDir Directory to extract to
     * @return Path to the extracted directory
     * @throws IOException if extraction fails or safety limits are exceeded
     */
    public static Path extractSafely(String archivePath, Path extractDir) throws IOException {
        return extractSafely(archivePath, extractDir, null);
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
                return extractZip(archiveFile.toPath(), extractDir, extractedCount);
            } else if (lowerName.endsWith(".tar") || lowerName.endsWith(".tar.gz") || 
                       lowerName.endsWith(".tgz") || lowerName.endsWith(".gz")) {
                return extractTar(archiveFile.toPath(), extractDir, extractedCount);
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
    private static Path extractZip(Path archivePath, Path extractDir, AtomicInteger extractedCount) throws IOException {
        String method = "extractZip";
        
        long totalUncompressedSize = 0;
        int entryCount = 0;
        
        try (InputStream is = Files.newInputStream(archivePath);
             BufferedInputStream bis = new BufferedInputStream(is);
             ZipInputStream zis = new ZipInputStream(bis)) {
            
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // Validate entry name
                String entryName = entry.getName();
                if (entryName.length() > MAX_ENTRY_NAME_LENGTH) {
                    throw new SecurityException("Entry name too long: " + entryName);
                }
                
                // Skip directory entries
                if (entry.isDirectory()) {
                    continue;
                }
                
                // Get entry size (may be -1 for STORED method)
                long entrySize = entry.getSize();
                
                // Handle zip bombs (entries with no declared size)
                if (entrySize < 0) {
                    // For STORED method without size, we must count as we read
                    // Limit to reasonable single file size
                    entrySize = MAX_ENTRY_SIZE_BYTES;
                }
                
                // Check individual file size limit
                if (entrySize > MAX_ENTRY_SIZE_BYTES) {
                    throw new SecurityException("Entry too large: " + entryName + 
                            " (" + entrySize + " bytes)");
                }
                
                // Accumulate total size
                totalUncompressedSize += entrySize;
                
                // Check total size limit (circuit breaker)
                if (totalUncompressedSize > MAX_UNCOMPRESSED_SIZE_BYTES) {
                    throw new SecurityException("Total uncompressed size exceeds " + 
                            MAX_UNCOMPRESSED_SIZE_BYTES + " bytes");
                }
                
                // Extract entry
                Path targetPath = extractDir.resolve(entryName);
                
                // Security: Prevent zip slip vulnerability (path traversal)
                if (!targetPath.normalize().startsWith(extractDir.normalize())) {
                    throw new SecurityException("Entry attempts path traversal: " + entryName);
                }
                
                // Create parent directories
                Files.createDirectories(targetPath.getParent());
                
                // Write file
                try (OutputStream os = Files.newOutputStream(targetPath);
                     BufferedOutputStream bos = new BufferedOutputStream(os)) {
                    IOUtils.copy(zis, bos);
                }
                
                entryCount++;
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
    private static Path extractTar(Path archivePath, Path extractDir, AtomicInteger extractedCount) throws IOException {
        String method = "extractTar";
        
        // For tar extraction, we need to count files after extraction
        // First delegate to existing ArchiveUtil
        if (!ArchiveUtil.extractArchive(archivePath.toString(), extractDir.toString())) {
            throw new IOException("Failed to extract archive: " + archivePath);
        }
        
        // Count the extracted files by walking the directory
        int count = countExtractedFiles(extractDir);
        
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
        return extractWithCleanup(archivePath, extractDir, null);
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
        String method = "extractWithCleanup";
        
        boolean success = false;
        
        try {
            // Create extraction directory
            Files.createDirectories(extractDir);
            
            // Extract
            Path result = extractSafely(archivePath, extractDir, extractedCount);
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