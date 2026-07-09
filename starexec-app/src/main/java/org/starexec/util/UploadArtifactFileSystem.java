package org.starexec.util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Symlink-safe filesystem operations for upload-owned artifacts.
 */
public final class UploadArtifactFileSystem {
    private UploadArtifactFileSystem() {}

    public static void deleteFileWithoutFollowingLinks(Path file) throws IOException {
        if (Files.isSymbolicLink(file)) {
            throw new IOException("refusing to delete symlink artifact: " + file);
        }
        Files.deleteIfExists(file);
    }

    public static void deleteDirectoryWithoutFollowingLinks(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file)) {
                    throw new IOException("refusing to delete directory containing symlink: " + file);
                }
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("refusing to delete unsafe directory: " + dir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
