package org.starexec.util;

import java.io.IOException;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * File creation bound to an open directory, without following symbolic-link ancestors.
 * Later operations that use ordinary pathnames still require protected ancestors.
 */
public final class DescriptorDirectory implements AutoCloseable {
    private final SecureDirectoryStream<Path> directory;
    private final FileSystem fileSystem;
    private final Path path;

    private DescriptorDirectory(SecureDirectoryStream<Path> directory, FileSystem fileSystem, Path path) {
        this.directory = directory;
        this.fileSystem = fileSystem;
        this.path = path;
    }

    /**
     * Opens every path component relative to its already open parent, refusing links.
     * A filesystem without secure directory streams is refused rather than downgraded.
     */
    public static DescriptorDirectory open(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        DirectoryStream<Path> opened = Files.newDirectoryStream(absolute.getRoot());
        try {
            if (!(opened instanceof SecureDirectoryStream<Path> current)) {
                throw new IOException("Filesystem does not support descriptor-relative directory access");
            }
            for (Path component : absolute) {
                SecureDirectoryStream<Path> next = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                try {
                    opened.close();
                } catch (IOException e) {
                    try {
                        next.close();
                    } catch (IOException cleanup) {
                        e.addSuppressed(cleanup);
                    }
                    throw e;
                }
                opened = next;
                current = next;
            }
            return new DescriptorDirectory(current, absolute.getFileSystem(), absolute);
        } catch (IOException | RuntimeException e) {
            try {
                opened.close();
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    /** Creates a fresh UTF-8 file; neither an existing file nor a link is replaced. */
    public void writeNewText(String name, String contents) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IOException("File name must be one non-empty path component");
        }
        Path child;
        try {
            child = fileSystem.getPath(name);
        } catch (InvalidPathException e) {
            throw new IOException("Invalid file name", e);
        }
        try (SeekableByteChannel channel = directory.newByteChannel(child,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
             Writer writer = Channels.newWriter(channel, StandardCharsets.UTF_8)) {
            writer.write(contents == null ? "" : contents);
        }
    }

    /** Removes one child tree without following links or changing linked file modes. */
    public void deleteTree(String name, int maxEntries) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || maxEntries < 1) {
            throw new IOException("Cleanup requires one child name and a positive entry limit");
        }
        Path child = fileSystem.getPath(name);
        try {
            if (!directory.getFileAttributeView(child, BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes().isDirectory()) {
                throw new IOException("Registered cleanup root is not a directory");
            }
        } catch (NoSuchFileException absent) { return; }
        remove(directory, child, new int[]{maxEntries}, 0);
        try {
            directory.getFileAttributeView(child, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
            throw new IOException("Registered cleanup tree remains present");
        } catch (NoSuchFileException absent) { }
    }

    /** Unlinks one registered file without following it or removing its parent. */
    public void deleteFile(String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) throw new IOException("Cleanup requires one file name");
        Path child = fileSystem.getPath(name);
        try {
            if (directory.getFileAttributeView(child, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes().isDirectory()) {
                throw new IOException("Registered helper input is not a file");
            }
        } catch (NoSuchFileException absent) { return; }
        directory.deleteFile(child);
    }

    private static void remove(SecureDirectoryStream<Path> parent, Path name, int[] remaining, int depth)
            throws IOException {
        if (--remaining[0] < 0 || depth > 256) throw new IOException("Cleanup tree exceeds its traversal limit");
        var attributes = parent.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
        if (!attributes.isDirectory()) {
            parent.deleteFile(name);
            return;
        }
        try (SecureDirectoryStream<Path> child = parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
            var permissions = child.getFileAttributeView(PosixFileAttributeView.class);
            if (permissions == null) throw new IOException("Cleanup requires descriptor-bound POSIX permissions");
            permissions.setPermissions(PosixFilePermissions.fromString("rwx------"));
            for (Path entry : child) remove(child, entry.getFileName(), remaining, depth + 1);
            parent.deleteDirectory(name);
        }
    }

    /** Forces namespace changes only while the named parent still matches this open directory. */
    public void force() throws IOException {
        Object identity = directory.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey();
        if (identity == null || !identity.equals(Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey())) throw new IOException("Cleanup parent identity is unavailable or changed");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
        if (!identity.equals(Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey())) {
            throw new IOException("Cleanup parent changed before storage was forced");
        }
    }

    @Override
    public void close() throws IOException {
        directory.close();
    }
}
