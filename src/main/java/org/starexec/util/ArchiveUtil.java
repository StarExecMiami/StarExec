package org.starexec.util;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.starexec.constants.R;
import org.starexec.logger.StarLogger;

/**
 * Contains helper methods for dealing with .zip files
 */
public class ArchiveUtil {

    private static final StarLogger log = StarLogger.getLogger(
        ArchiveUtil.class
    );

    /**
     * Gets the uncompressed size of an archive
     *
     * @param filePath The path to the file to get the size of
     * @return The size of the uncompressed archive, in bytes
     * @author Eric Burns
     */
    public static long getArchiveSize(String filePath) {
        if (filePath.endsWith(".zip")) {
            return getZipSize(filePath);
        } else if (filePath.endsWith(".tar")) {
            return getTarSize(filePath);
        } else if (filePath.endsWith(".tgz") || filePath.endsWith(".tar.gz")) {
            return getTarGzSize(filePath);
        } else {
            log.warn(
                String.format(
                    "Unsupported file extension for [%s] attempted to uncompress",
                    filePath
                )
            );
            return -1;
        }
    }

    /**
     * Gets the uncompressed size of a zip archive
     *
     * @param fileName The path to the file to get the size of
     * @return The size of the uncompressed zip archive, in bytes
     * @author Eric Burns
     */
    private static long getZipSize(String fileName) {
        try (ZipFile temp = new ZipFile(fileName)) {
            long answer = 0;
            Enumeration<ZipArchiveEntry> x = temp.getEntries();
            while (x.hasMoreElements()) {
                answer += x.nextElement().getSize();
            }
            return answer;
        } catch (Exception e) {
            log.error("getZipSize", e);
            return -1;
        }
    }

    /**
     * Gets the uncompressed size of a tar archive
     *
     * @param fileName The path to the file to get the size of
     * @return The size of the uncompressed tar archive, in bytes
     * @author Eric Burns
     */
    private static long getTarSize(String fileName) {
        try (
            InputStream is = new FileInputStream(fileName);
            BufferedInputStream bis = new BufferedInputStream(is);
            ArchiveInputStream ais =
                new ArchiveStreamFactory().createArchiveInputStream("tar", bis)
        ) {
            long answer = 0;
            ArchiveEntry entry;
            while ((entry = ais.getNextEntry()) != null) {
                answer += entry.getSize();
            }
            return answer;
        } catch (Exception e) {
            log.error("getTarSize", e);
            return -1;
        }
    }

    /**
     * Gets the APPROXIMATE uncompressed size of a tarGz archive. The actual value returned
     * is the size of the TAR file, which will be slightly larger than the size of the completely
     * unarchived file.
     *
     * @param fileName The path to the file to get the size of
     * @return The size of the uncompressed TAR file, in bytes
     * @author Eric Burns
     */
    //Returns the size of the TAR file and not the size of the un-archived files within
    private static long getTarGzSize(String fileName) {
        try (
            FileInputStream instream = new FileInputStream(fileName);
            GzipCompressorInputStream ginstream = new GzipCompressorInputStream(
                instream
            )
        ) {
            long answer = 0;
            long temp;
            do {
                temp = ginstream.skip(100000000);
                answer += temp;
            } while (temp != 0);
            return answer;
        } catch (Exception e) {
            log.error("getTarGzSize", e);
            return -1;
        }
    }

    /**
     * Extracts an archive as the sandbox user, meaning the files extracted
     * will be owned by the sandbox user
     *
     * @param fileName The absolute path to the archive
     * @param destination The directory to place the output in
     * @return True on success and false otherwise
     */
    public static Boolean extractArchiveAsSandbox(
        String fileName,
        String destination
    ) {
        log.debug("ExtractingArchive for " + fileName);
        try {
            // SECURITY: Check for zip bomb / excessive compression ratio BEFORE extraction
            File archiveFile = new File(fileName);
            long compressedSize = archiveFile.length();
            long uncompressedSize = getArchiveSize(fileName);

            if (uncompressedSize > 0 && compressedSize > 0) {
                long compressionRatio = uncompressedSize / compressedSize;
                // Warn if ratio is suspicious (>100:1), but allow up to 1000:1 for text files
                if (compressionRatio > 1000) {
                    log.error(
                        String.format(
                            "Zip bomb detected: compression ratio %d:1 (compressed: %d bytes, uncompressed: %d bytes)",
                            compressionRatio,
                            compressedSize,
                            uncompressedSize
                        )
                    );
                    throw new SecurityException(
                        "Excessive compression ratio detected - possible zip bomb attack"
                    );
                } else if (compressionRatio > 100) {
                    log.warn(
                        String.format(
                            "High compression ratio detected: %d:1 (compressed: %d bytes, uncompressed: %d bytes)",
                            compressionRatio,
                            compressedSize,
                            uncompressedSize
                        )
                    );
                }
            } else if (uncompressedSize < 0) {
                log.warn(
                    "Could not determine uncompressed size for archive: " +
                        fileName +
                        " - proceeding with caution"
                );
            }

            // Check for the appropriate file extension and hand off to the appropriate method
            if (fileName.endsWith(".zip")) {
                log.debug(
                    "Extracting ZIP using pure Java (Apache Commons Compress)"
                );

                // Ensure destination directory exists
                File destDir = new File(destination);
                if (!destDir.exists()) {
                    destDir.mkdirs();
                    log.debug("Created destination directory: " + destination);
                }

                // Extract using Apache Commons Compress (pure Java, no external dependencies)
                try (ZipFile zipFile = new ZipFile(new File(fileName))) {
                    // Get canonical path for security checks (reuse destDir from above)
                    String destCanonicalPath = destDir.getCanonicalPath();

                    Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
                    while (entries.hasMoreElements()) {
                        ZipArchiveEntry entry = entries.nextElement();

                        // SECURITY: Validate entry name for path traversal attempts
                        String entryName = entry.getName();
                        if (entryName.contains("..")) {
                            log.warn(
                                "Path traversal attempt detected in archive entry: " +
                                    entryName
                            );
                            throw new SecurityException(
                                "Archive contains path traversal sequence (..) in entry: " +
                                    entryName
                            );
                        }

                        File outputFile = new File(destination, entryName);

                        // SECURITY: Double-check with canonical path resolution
                        // This protects against Unicode tricks, case variations, and symbolic links
                        String outputCanonicalPath =
                            outputFile.getCanonicalPath();
                        if (
                            !outputCanonicalPath.startsWith(
                                destCanonicalPath + File.separator
                            ) &&
                            !outputCanonicalPath.equals(destCanonicalPath)
                        ) {
                            log.error(
                                "Path traversal attack blocked: Entry '" +
                                    entryName +
                                    "' resolves to '" +
                                    outputCanonicalPath +
                                    "' outside destination '" +
                                    destCanonicalPath +
                                    "'"
                            );
                            throw new SecurityException(
                                "Archive entry attempts to write outside destination directory: " +
                                    entryName
                            );
                        }

                        // SECURITY: Reject symlinks in archives (they could point outside destination)
                        if (entry.isUnixSymlink()) {
                            log.warn(
                                "Symbolic link detected in archive: " +
                                    entryName
                            );
                            throw new SecurityException(
                                "Archive contains symbolic link, which is not allowed: " +
                                    entryName
                            );
                        }

                        if (entry.isDirectory()) {
                            // Create directory
                            if (!outputFile.exists()) {
                                outputFile.mkdirs();
                                log.debug(
                                    "Created directory: " + entry.getName()
                                );
                            }
                        } else {
                            // Create parent directories if needed
                            File parent = outputFile.getParentFile();
                            if (parent != null && !parent.exists()) {
                                parent.mkdirs();
                            }

                            // Extract file
                            try (
                                InputStream is = zipFile.getInputStream(entry);
                                OutputStream os = new FileOutputStream(
                                    outputFile
                                )
                            ) {
                                IOUtils.copy(is, os);
                                log.debug("Extracted file: " + entry.getName());
                            }
                        }
                    }
                    log.debug(
                        "Java-based ZIP extraction completed successfully"
                    );
                }

                // Set permissions for group access
                // Files remain owned by starexec user for now to allow further processing
                log.debug("Setting group permissions for extracted files");
                String[] chmodCmd = new String[] {
                    "chmod",
                    "-R",
                    "u+rwx,g+rwx",
                    destination,
                };
                String chmodResult = Util.executeCommand(chmodCmd);
                log.debug("chmod result: " + chmodResult);

                log.debug("Removing archive file: " + fileName);
                ArchiveUtil.removeArchive(fileName);
            } else if (
                fileName.endsWith(".tar.gz") ||
                fileName.endsWith(".tgz") ||
                fileName.endsWith(".tar")
            ) {
                // Extract tar archive - use sudo if available, otherwise direct execution
                String[] tarCmd;
                if (Util.isSudoAvailable()) {
                    log.debug("Using sudo for tar extraction");
                    tarCmd = new String[10];
                    tarCmd[0] = "sudo";
                    tarCmd[1] = "-u";
                    tarCmd[2] = R.SANDBOX_USER_ONE;
                    tarCmd[3] = "tar";
                    tarCmd[4] = "--no-same-permissions";
                    tarCmd[5] = "--no-same-owner";
                    tarCmd[6] = "-xf";
                    tarCmd[7] = fileName;
                    tarCmd[8] = "-C";
                    tarCmd[9] = destination;
                } else {
                    // In containerized environments without sudo, run tar directly
                    log.debug(
                        "sudo not available, using direct tar extraction"
                    );
                    tarCmd = new String[7];
                    tarCmd[0] = "tar";
                    tarCmd[1] = "--no-same-permissions";
                    tarCmd[2] = "--no-same-owner";
                    tarCmd[3] = "-xf";
                    tarCmd[4] = fileName;
                    tarCmd[5] = "-C";
                    tarCmd[6] = destination;
                }
                log.debug(
                    "about to execute command tar command with container-safe options"
                );
                Util.executeCommand(tarCmd);
                ArchiveUtil.removeArchive(fileName);
                Util.chmodDirectory(destination, false);
            } else {
                // No valid file type found :(
                log.warn(
                    String.format(
                        "Unsupported file extension for [%s] attempted to uncompress",
                        fileName
                    )
                );
                return false;
            }

            log.debug(
                String.format(
                    "Successfully extracted [%s] to [%s]",
                    fileName,
                    destination
                )
            );
            return true;
        } catch (Exception e) {
            log.error("extractArchiveAsSandbox", e);
        }

        return false;
    }

    /**
     * Extracts/unpacks/uncompresses an archive file to a folder with the same name at the given destination.
     * This method supports .zip, .tar and .tar.gz files. Once the contents are extracted the original archive file
     * is deleted. Note if the extraction failed, some files/folders may have been partially created.
     *
     * @param fileName The full file path to the archive file
     * @param destination The full path to the folder to extract the file to
     * @return True if extraction was successful, false otherwise.
     * @author Tyler Jensen
     */
    public static Boolean extractArchive(String fileName, String destination) {
        log.debug("ExtractingArchive for " + fileName);
        try {
            // SECURITY: Check for zip bomb / excessive compression ratio BEFORE extraction
            File archiveFile = new File(fileName);
            long compressedSize = archiveFile.length();
            long uncompressedSize = getArchiveSize(fileName);

            if (uncompressedSize > 0 && compressedSize > 0) {
                long compressionRatio = uncompressedSize / compressedSize;
                // Warn if ratio is suspicious (>100:1), but allow up to 1000:1 for text files
                if (compressionRatio > 1000) {
                    log.error(
                        String.format(
                            "Zip bomb detected: compression ratio %d:1 (compressed: %d bytes, uncompressed: %d bytes)",
                            compressionRatio,
                            compressedSize,
                            uncompressedSize
                        )
                    );
                    throw new SecurityException(
                        "Excessive compression ratio detected - possible zip bomb attack"
                    );
                } else if (compressionRatio > 100) {
                    log.warn(
                        String.format(
                            "High compression ratio detected: %d:1 (compressed: %d bytes, uncompressed: %d bytes)",
                            compressionRatio,
                            compressedSize,
                            uncompressedSize
                        )
                    );
                }
            } else if (uncompressedSize < 0) {
                log.warn(
                    "Could not determine uncompressed size for archive: " +
                        fileName +
                        " - proceeding with caution"
                );
            }

            // Check for the appropriate file extension and hand off to the appropriate method
            if (fileName.endsWith(".zip")) {
                ArchiveUtil.extractArchiveOfType(
                    fileName,
                    destination,
                    ArchiveType.ZIP
                );
            } else if (fileName.endsWith(".tar")) {
                ArchiveUtil.extractArchiveOfType(
                    fileName,
                    destination,
                    ArchiveType.TAR
                );
            } else if (
                fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz")
            ) {
                // First rename it if it's a .tgz

                String[] lsCmd = new String[3];
                lsCmd[0] = "ls";
                lsCmd[1] = "-l";

                log.debug("destination is " + destination);

                lsCmd[2] = fileName;
                String results = Util.executeCommand(lsCmd);
                log.debug("ls -l of tgz results = " + results);

                lsCmd[2] = destination;
                results = Util.executeCommand(lsCmd);
                log.debug("ls -l destination results = " + results);

                /* it appears that the permissions are not set correctly by tar for tomcat.
				   We need group read permissions for files, because elsewhere,
				   we try to copy these files as the sandbox user, and hence need
				   to be able to read them.  So we do an explicit chmod*/
                String[] tarCmd = new String[5];
                tarCmd[0] = "tar";
                tarCmd[1] = "-xf";
                tarCmd[2] = fileName;
                tarCmd[3] = "-C";
                tarCmd[4] = destination;
                log.debug("about to execute command tar command");
                results = Util.executeCommand(tarCmd);
                log.debug("command was executed, results = " + results);
                log.debug("now removing the archived file " + fileName);
                ArchiveUtil.removeArchive(fileName);

                // now chmod the directory so sandbox can access it

                Util.chmodDirectory(destination, true);

                lsCmd[2] = destination;
                results = Util.executeCommand(lsCmd);
                log.debug(
                    "command was executed - ls -l destination results = " +
                        results
                );
            } else {
                // No valid file type found :(
                log.warn(
                    String.format(
                        "Unsupported file extension for [%s] attempted to uncompress",
                        fileName
                    )
                );
                return false;
            }

            log.debug(
                String.format(
                    "Successfully extracted [%s] to [%s]",
                    fileName,
                    destination
                )
            );
            return true;
        } catch (Exception e) {
            log.error("extractArchive", e);
        }

        return false;
    }

    /**
     * Extracts/unpacks/uncompresses an archive file to the same folder the archive file exists within.
     * This method supports .zip, .tar and .tar.gz files. Once the contents are extracted the original archive file
     * is deleted. Note if the extraction failed, some files/folders may have been partially created.
     *
     * @param fileName The full file path to the archive file
     * @author Tyler Jensen
     */
    public static void extractArchive(String fileName) {
        try {
            String parent =
                new File(fileName).getParentFile().getCanonicalPath() +
                File.separator;
            ArchiveUtil.extractArchive(fileName, parent);
        } catch (Exception e) {
            log.error("extractArchive", e);
        }
    }

    /**
     * Unpacks a tar file and removes the original if the unpack was successful.
     *
     * @param fileName The full path to the file
     * @param destination Where to unpack the contents to
     * @author Tyler Jensen
     */
    private static void extractArchiveOfType(
        String fileName,
        String destination,
        ArchiveType archiveType
    ) throws Exception {
        final String methodName = "extractArchiveOfType";
        // Use the Apache commons compression library to open up the tar file...
        log.debug("extracting " + archiveType);

        // SECURITY: Get canonical path of destination for path traversal protection
        File destDir = new File(destination);
        String destCanonicalPath = destDir.getCanonicalPath();

        try (
            InputStream is = new FileInputStream(fileName);
            BufferedInputStream bis = new BufferedInputStream(is);
            ArchiveInputStream ais =
                new ArchiveStreamFactory().createArchiveInputStream(
                    archiveType.type,
                    bis
                )
        ) {
            ArchiveEntry entry;
            // For each 'file' in the tar file...
            while ((entry = ais.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    // SECURITY: Validate entry name for path traversal attempts
                    String entryName = entry.getName();
                    if (entryName.contains("..")) {
                        log.warn(
                            "Path traversal attempt detected in archive entry: " +
                                entryName
                        );
                        throw new SecurityException(
                            "Archive contains path traversal sequence (..) in entry: " +
                                entryName
                        );
                    }

                    // If it's not a directory...
                    File fileToCreate = new File(destination, entryName);

                    // SECURITY: Double-check with canonical path resolution
                    String outputCanonicalPath =
                        fileToCreate.getCanonicalPath();
                    if (
                        !outputCanonicalPath.startsWith(
                            destCanonicalPath + File.separator
                        ) &&
                        !outputCanonicalPath.equals(destCanonicalPath)
                    ) {
                        log.error(
                            "Path traversal attack blocked: Entry '" +
                                entryName +
                                "' resolves to '" +
                                outputCanonicalPath +
                                "' outside destination '" +
                                destCanonicalPath +
                                "'"
                        );
                        throw new SecurityException(
                            "Archive entry attempts to write outside destination directory: " +
                                entryName
                        );
                    }

                    File dir = new File(fileToCreate.getParent());
                    boolean success = true;
                    if (!dir.exists()) {
                        // And create it if it doesn't exist so we can write a file inside it
                        success = dir.mkdirs();
                        if (!success) {
                            log.warn(
                                "Could not create directory: " +
                                    dir.getAbsolutePath() +
                                    "\n" +
                                    Util.getCurrentStackTrace()
                            );
                            log.warn(
                                methodName,
                                "Did file already exist: " + dir.exists()
                            );
                            log.warn(
                                methodName,
                                "User was: " + System.getProperty("user.name")
                            );
                            log.warn(
                                methodName,
                                "canWrite for file: " + dir.canWrite()
                            );
                        }
                    }
                    if (success) {
                        // Finally, extract the file
                        try (
                            OutputStream out = new FileOutputStream(
                                fileToCreate
                            )
                        ) {
                            IOUtils.copy(ais, out);
                        }
                    }
                }
            }
        }
        ArchiveUtil.removeArchive(fileName);
    }

    /**
     * Checks the global remove archive setting and removes the archive file if the setting is true.
     *
     * @param fileName The path to the archive file to remove
     */
    private static void removeArchive(String fileName) {
        if (R.REMOVE_ARCHIVES) {
            if (new File(fileName).delete()) {
                log.debug("Cleaned up archive file: " + fileName);
            } else {
                log.warn("Failed to cleanup archive file: " + fileName);
            }
        }
    }

    /**
     * Adds a raw string to a zip archive, saving the string in a file specified by zipFileName
     *
     * @param zos
     * @param str
     * @param zipFileName
     * @throws Exception
     */
    public static void addStringToArchive(
        ZipArchiveOutputStream zos,
        String str,
        String zipFileName
    ) throws Exception {
        final byte[] data = str.getBytes(zos.getEncoding());
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        ZipArchiveEntry entry = new ZipArchiveEntry(zipFileName);
        entry.setSize(data.length);
        entry.setCrc(crc.getValue());
        entry.setMethod(ZipArchiveEntry.STORED);
        entry.setInternalAttributes(1);
        zos.putArchiveEntry(entry);
        zos.write(data, 0, data.length);
        zos.closeArchiveEntry();
    }

    /**
     * Adds the given file to the given output stream if and only if it was modified after some timestamp
     *
     * @param zos
     * @param srcFile
     * @param zipFileName
     * @param earlyDate Milliseconds since the epoch. Only get files modified after this (non-inclusive)
     * @return max of timestamp and earlyDate
     * @throws IOException
     */
    public static long addFileToArchive(
        ZipArchiveOutputStream zos,
        File srcFile,
        String zipFileName,
        long earlyDate
    ) throws IOException {
        long timestamp = srcFile.lastModified();
        if (timestamp > earlyDate) {
            addFileToArchive(zos, srcFile, zipFileName);
            return timestamp;
        }
        return earlyDate;
    }

    /**
     * Calculate Unix file permissions for file f
     * @param f File to calculate permissions for
     * @return Unix file permissions
     */
    private static int getUnixMode(File f) throws IOException {
        return (Integer) Files.getAttribute(f.toPath(), "unix:mode");
    }

    /**
     * Adds the given source file to the given zip output stream using the given name
     *
     * @param zos
     * @param srcFile
     * @param zipFileName
     * @return timestamp of file added
     * @throws IOException
     */
    public static long addFileToArchive(
        ZipArchiveOutputStream zos,
        File srcFile,
        String zipFileName
    ) throws IOException {
        ZipArchiveEntry entry = new ZipArchiveEntry(srcFile, zipFileName);
        try {
            long timestamp = srcFile.lastModified();
            zos.putArchiveEntry(entry);
            entry.setUnixMode(getUnixMode(srcFile));
            entry.setSize(srcFile.length());
            //entry.setInternalAttributes(Util.isBinaryFile(srcFile)?0:1);
            try (FileInputStream input = new FileInputStream(srcFile)) {
                IOUtils.copy(input, zos);
            }
            zos.closeArchiveEntry();
            return timestamp;
        } catch (java.io.FileNotFoundException e) {
            if (srcFile.getCanonicalPath().equals(srcFile.getAbsolutePath())) {
                throw e;
            }
            log.debug(
                "File not found exception probably broken symlink for: " +
                    srcFile.getAbsolutePath()
            );
            return -1;
        }
    }

    /**
     * Recursively adds the given directory to the given zipoutputstream, using the given name as the prefix
     * for all files that get added. Only add files that were modified after the specified time.
     *
     * @param zos
     * @param srcFile
     * @param zipFileName
     * @param earlyDate
     * @return max of earlyDate and timestamp of most recently modified file
     * @throws IOException
     */
    public static long addDirToArchive(
        ZipArchiveOutputStream zos,
        File srcFile,
        String zipFileName,
        long earlyDate
    ) throws IOException {
        long maxTime = earlyDate;
        final File[] files = srcFile.listFiles();
        for (File file : files) {
            final long t;
            final String fileName =
                zipFileName + File.separator + file.getName();
            if (file.isDirectory()) {
                t = addDirToArchive(zos, file, fileName, earlyDate);
            } else {
                t = addFileToArchive(zos, file, fileName, earlyDate);
            }

            if (t > maxTime) {
                maxTime = t;
            }
        }
        return maxTime;
    }

    /**
     * See addDirToArchive overload. All files are included with no date filter
     *
     * @param zos
     * @param srcFile
     * @param zipFileName
     * @throws IOException
     */
    public static long addDirToArchive(
        ZipArchiveOutputStream zos,
        File srcFile,
        String zipFileName
    ) throws IOException {
        return addDirToArchive(zos, srcFile, zipFileName, -1);
    }

    /**
     * Writes several files to one zip file at the location indicated by the given outputstream
     *
     * @param paths The list of files to add to the zip
     * @param output The outputstream to write to
     * @param baseName If not null or empty, all files will be in one directory with this name
     * @throws IOException
     */
    public static void createAndOutputZip(
        Iterable<File> paths,
        OutputStream output,
        String baseName
    ) throws IOException {
        createAndOutputZip(paths, output, baseName, false);
    }

    /**
     * Writes several files to one zip file at the location indicated by the given outputstream.
     * If uniquify is true, make sure all file names are unique by appending a number to each.
     *
     * @param paths The list of files to add to the zip
     * @param output The outputstream to write to
     * @param baseName If not null or empty, all files will be in one directory with this name
     * @throws IOException
     */
    public static void createAndOutputZip(
        Iterable<File> paths,
        OutputStream output,
        String baseName,
        boolean uniquify
    ) throws IOException {
        String newFileName = baseName;
        ZipArchiveOutputStream stream = new ZipArchiveOutputStream(output);
        Map<String, Integer> pathsSeen = new HashMap<>();
        for (File f : paths) {
            log.debug("adding new file to zip = " + f.getAbsolutePath());
            log.debug("directory status = " + f.isDirectory());
            if (Util.isNullOrEmpty(baseName)) {
                newFileName = f.getName();
            } else {
                newFileName = baseName + File.separator + f.getName();
            }

            if (pathsSeen.containsKey(newFileName)) {
                pathsSeen.put(newFileName, pathsSeen.get(newFileName) + 1);
            } else {
                pathsSeen.put(newFileName, 0);
            }

            if (uniquify) {
                newFileName = newFileName + "_" + pathsSeen.get(newFileName);
            }

            if (f.isDirectory()) {
                addDirToArchive(stream, f, newFileName);
            } else {
                addFileToArchive(stream, f, newFileName);
            }
        }
        stream.finish();
        stream.flush();
        stream.close();
    }

    /**
     * Writes a directory recursively to a zip file at the location indicated by the given output stream.
     *
     * @param path The directory or file to zip
     * @param output The outputstream to write to
     * @param baseName If not null or empty, all files will be in one directory with this name
     * @param removeTopLevel If true, includes all files in the given directory but not the directory itself. Basename
     * will
     * be IGNORED if this is true. It should be set to false if the desire is to simply rename the top level.
     * @throws Exception
     */
    public static void createAndOutputZip(
        File path,
        OutputStream output,
        String baseName,
        boolean removeTopLevel
    ) throws IOException {
        log.debug("Creating and outputting .zip file...");
        if (removeTopLevel) {
            File[] files = path.listFiles();
            List<File> f = Arrays.asList(files);
            createAndOutputZip(f, output, "");
            return;
        }
        ZipArchiveOutputStream stream = new ZipArchiveOutputStream(output);
        boolean dir = path.isDirectory();
        if (!Util.isNullOrEmpty(baseName)) {
            if (dir) {
                addDirToArchive(stream, path, baseName);
            } else {
                addFileToArchive(
                    stream,
                    path,
                    baseName + File.separator + path.getName()
                );
            }
        } else {
            if (dir) {
                addDirToArchive(stream, path, path.getName());
            } else {
                addFileToArchive(stream, path, path.getName());
            }
        }
        stream.close();
    }

    private enum ArchiveType {
        ZIP("zip"),
        TAR("tar");

        final String type;

        ArchiveType(String type) {
            this.type = type;
        }
    }
}
