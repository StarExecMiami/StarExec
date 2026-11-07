package org.starexec.util;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Streams source packages without following links or exposing files outside their source tree. */
public final class SolverSourceArchive {
    private SolverSourceArchive() { }

    /** The caller authorizes the solver and protects the source and its ancestors from writers. */
    public static void write(Path source, OutputStream output, String name) throws IOException {
        if (source == null || !source.isAbsolute() || source.getParent() == null || !source.equals(source.normalize())) {
            throw new IOException("Source must be an absolute normalized non-root directory");
        }
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IOException("Invalid source archive name");
        }
        try (DescriptorDirectory ignored = DescriptorDirectory.open(source);
             ZipArchiveOutputStream archive = new ZipArchiveOutputStream(output)) {
            Path root = source.toRealPath();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                private String entryName(Path file) throws IOException {
                    Path relative = root.relativize(file);
                    if (!root.getFileSystem().getPath(relative.toString()).equals(relative)
                            || relative.toString().indexOf('\\') >= 0) {
                        throw new IOException("Source name is not portable UTF-8");
                    }
                    return name + "/" + relative.toString().replace(file.getFileSystem().getSeparator(), "/");
                }

                private ZipArchiveEntry entry(Path file, BasicFileAttributes attributes, int type, long size) throws IOException {
                    ZipArchiveEntry entry = new ZipArchiveEntry(entryName(file) + (type == UnixStat.DIR_FLAG ? "/" : ""));
                    int mode = ((Number) Files.getAttribute(file, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
                    entry.setUnixMode(type | (mode & 0777));
                    entry.setTime(attributes.lastModifiedTime().toMillis()); entry.setSize(size);
                    return entry;
                }

                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    if (!directory.equals(root)) {
                        archive.putArchiveEntry(entry(directory, attributes, UnixStat.DIR_FLAG, 0));
                        archive.closeArchiveEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isSymbolicLink()) {
                        Path target = Files.readSymbolicLink(file);
                        if (target.isAbsolute() || target.toString().indexOf('\\') >= 0
                                || !file.getFileSystem().getPath(target.toString()).equals(target)
                                || !file.toRealPath().startsWith(root)) {
                            throw new IOException("Source link escapes its tree");
                        }
                        byte[] value = target.toString().getBytes(StandardCharsets.UTF_8);
                        archive.putArchiveEntry(entry(file, attributes, UnixStat.LINK_FLAG, value.length));
                        archive.write(value); archive.closeArchiveEntry();
                    } else if (attributes.isRegularFile()) {
                        if (((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) {
                            throw new IOException("Source archive contains a hard-linked file");
                        }
                        archive.putArchiveEntry(entry(file, attributes, UnixStat.FILE_FLAG, attributes.size()));
                        long count = 0;
                        try (var input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                            byte[] buffer = new byte[65536];
                            for (int read; (read = input.read(buffer)) != -1;) {
                                if (read > attributes.size() - count) throw new IOException("Source changed during archiving");
                                archive.write(buffer, 0, read); count += read;
                            }
                        }
                        var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (!after.isRegularFile() || count != attributes.size() || count != after.size()
                                || !attributes.lastModifiedTime().equals(after.lastModifiedTime())
                                || !Objects.equals(attributes.fileKey(), after.fileKey())) {
                            throw new IOException("Source changed during archiving");
                        }
                        archive.closeArchiveEntry();
                    } else throw new IOException("Source archive contains a special file");
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
