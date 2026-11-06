package org.starexec.util;

import java.io.IOException;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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

    private DescriptorDirectory(SecureDirectoryStream<Path> directory, FileSystem fileSystem) {
        this.directory = directory;
        this.fileSystem = fileSystem;
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
            return new DescriptorDirectory(current, absolute.getFileSystem());
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

    @Override
    public void close() throws IOException {
        directory.close();
    }
}
