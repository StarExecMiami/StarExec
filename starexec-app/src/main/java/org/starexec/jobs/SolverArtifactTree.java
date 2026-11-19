package org.starexec.jobs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.starexec.constants.R;
import org.starexec.util.DescriptorDirectory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Bounded, verified copies for trusted build publication. The caller must stop
 * source writers and protect both trees' ancestors from untrusted modification.
 * File modes do not establish isolation from another process with the same UID.
 */
public final class SolverArtifactTree {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<PosixFilePermission> PRIVATE = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> DIRECTORY = PosixFilePermissions.fromString("r-xr-xr-x");
    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("r--r--r--");
    private static final Set<PosixFilePermission> EXECUTABLE = PosixFilePermissions.fromString("r-xr-xr-x");

    public record Snapshot(String payload, String sha256, long byteSize, int entryCount) { }
    private record Entry(String path, String kind, long size, String sha256, boolean executable, String target) { }
    private record Manifest(int version, List<Entry> entries) { }

    private SolverArtifactTree() { }

    /** Observes confined input bytes without changing their original modes or authorizing publication. */
    public static Snapshot inspectInputDirectory(Path directory, long maxBytes, int maxEntries) throws IOException {
        if (maxBytes < 0 || maxEntries < 1) throw new IllegalArgumentException("Invalid input observation limits");
        Path input = directory.toAbsolutePath();
        try (DescriptorDirectory ignored = DescriptorDirectory.open(input)) {
            return scan(input.toRealPath(), null, maxBytes, maxEntries, true, false);
        }
    }

    /** Observes optional output, including empty scopes; it never authorizes artifact publication. */
    public static Snapshot inspectOutputDirectory(Path directory, long maxBytes, int maxEntries) throws IOException {
        if (maxBytes < 0 || maxEntries < 0) throw new IllegalArgumentException("Invalid output observation limits");
        Path input=directory.toAbsolutePath();
        try (DescriptorDirectory ignored=DescriptorDirectory.open(input)) {
            return scan(input.toRealPath(),null,maxBytes,maxEntries,false,false);
        }
    }

    /** Creates a new destination; an existing directory or link is never reused. */
    public static Snapshot copy(Path source, Path destination, long maxBytes, int maxEntries) throws IOException {
        return copy(source, destination, maxBytes, maxEntries, false, true);
    }

    /** A durably reserved destination remains available for trusted recovery after a copy failure. */
    static Snapshot copyReserved(Path source, Path destination, long maxBytes, int maxEntries) throws IOException {
        return copy(source, destination, maxBytes, maxEntries, true, true);
    }

    /** Optional ordinary output is non-runnable and may contain no regular files. */
    static Snapshot copyOptionalOutput(Path source,Path destination,long maxBytes,int maxEntries) throws IOException {
        return copy(source,destination,maxBytes,maxEntries,true,false);
    }

    static void verifyOptionalOutput(Path directory,Snapshot expected) throws IOException {
        verify(directory,expected,false);
    }

    /** Copies only saved logs; positive absence is an empty, non-runnable snapshot. */
    static Snapshot copyFailureLogs(Path staging, Path destination, long maxBytes, int maxEntries) throws IOException {
        if (maxBytes < 0 || maxEntries < 1) throw new IllegalArgumentException("Invalid artifact limits");
        try (DescriptorDirectory input = DescriptorDirectory.open(staging);
             DescriptorDirectory parent = DescriptorDirectory.open(destination.getParent())) {
            if (destination.toAbsolutePath().normalize().startsWith(staging.toRealPath())) {
                throw new IOException("Artifact destination is inside its source");
            }
            Path logs = staging.resolve("logs");
            boolean present;
            try {
                if (!Files.readAttributes(logs, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()) {
                    throw new IOException("Saved build logs require a separate directory");
                }
                present = true;
            } catch (NoSuchFileException absent) {
                // An unavailable staging directory is not evidence that optional logs are absent.
                if (!Files.readAttributes(staging, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()) {
                    throw new IOException("Build staging is unavailable", absent);
                }
                present = false;
            }
            Files.createDirectory(destination, PosixFilePermissions.asFileAttribute(PRIVATE));
            if (present) copy(logs, destination.resolve("logs"), maxBytes, maxEntries - 1, true, false);
            Files.setPosixFilePermissions(destination, DIRECTORY);
            try (FileChannel directory = FileChannel.open(destination, StandardOpenOption.READ)) { directory.force(true); }
            Snapshot result = scan(destination.toRealPath(), null, maxBytes, maxEntries, false);
            verifyFailureLogs(destination, result);
            try (FileChannel directory = FileChannel.open(destination.getParent(), StandardOpenOption.READ)) { directory.force(true); }
            return result;
        }
    }

    private static Snapshot copy(Path source, Path destination, long maxBytes, int maxEntries,
                                 boolean retainFailure, boolean requireFile) throws IOException {
        if (maxBytes < 0 || maxEntries < (requireFile ? 1 : 0)) throw new IllegalArgumentException("Invalid artifact limits");
        Path input = source.toAbsolutePath();
        Path output = destination.toAbsolutePath();
        boolean created = false;
        try {
            try (DescriptorDirectory ignored = DescriptorDirectory.open(input);
                 DescriptorDirectory parent = DescriptorDirectory.open(output.getParent())) {
                // Do not normalize before checking every ancestor for symbolic links.
                input = input.toRealPath();
                if (output.normalize().startsWith(input)) throw new IOException("Artifact destination is inside its source");
                Files.createDirectory(output, PosixFilePermissions.asFileAttribute(PRIVATE));
                created = true;
            }
            Snapshot result = scan(input, output, maxBytes, maxEntries, requireFile);
            verify(output, result, requireFile);
            try (FileChannel parent = FileChannel.open(output.getParent(), StandardOpenOption.READ)) {
                parent.force(true);
            }
            return result;
        } catch (IOException | RuntimeException e) {
            if (created && !retainFailure) {
                try { removeOwnedCopy(output); }
                catch (IOException cleanup) { e.addSuppressed(cleanup); }
            }
            throw e;
        }
    }

    /** Recomputes the tree; the digest is over the exact UTF-8 manifest payload. */
    public static void verify(Path directory, Snapshot expected) throws IOException {
        verify(directory, expected, true);
    }

    static void verifyFailureLogs(Path directory, Snapshot expected) throws IOException {
        verify(directory, expected, false);
    }

    /** Reads only a conserved regular log; the caller pins its applied identity and protects ancestors. */
    public static Optional<String> readLog(Path root, String payload, String expectedDigest, long byteSize) throws IOException {
        if (root == null || !root.isAbsolute() || root.getParent() == null || !root.equals(root.normalize())
                || payload == null || expectedDigest == null || byteSize < 0 || !digest(payload).equals(expectedDigest)) {
            throw new IOException("Saved build log manifest is incomplete or changed");
        }
        Manifest manifest;
        Entry log = null;
        boolean logsDirectory = false;
        try {
            manifest = JSON.fromJson(payload, Manifest.class);
            if (manifest == null || manifest.version() != 1 || manifest.entries() == null) {
                throw new IOException("Unsupported build log manifest");
            }
            for (Entry entry : manifest.entries()) {
                if (entry == null || entry.path() == null) throw new IOException("Invalid build log entry");
                if (entry.path().equals("logs")) {
                    if (logsDirectory || !"directory".equals(entry.kind())) throw new IOException("Invalid saved log directory");
                    logsDirectory = true;
                }
                if (entry.path().equals("logs/" + R.SOLVER_BUILD_OUTPUT)) {
                    if (log != null || !"file".equals(entry.kind()) || entry.sha256() == null
                            || entry.size() < 0 || entry.size() > byteSize || entry.size() > Integer.MAX_VALUE) {
                        throw new IOException("Saved build log is not a bounded regular file");
                    }
                    log = entry;
                }
            }
        } catch (RuntimeException invalid) { throw new IOException("Invalid saved build log manifest", invalid); }
        if (log != null && !logsDirectory) throw new IOException("Saved log directory is not recorded");
        try (DescriptorDirectory ignored = DescriptorDirectory.open(root)) {
            Path directory = root.resolve("logs");
            try {
                if (!Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()
                        || !logsDirectory) throw new IOException("Saved log directory differs from its manifest");
            } catch (NoSuchFileException absent) {
                if (logsDirectory || log != null) throw absent;
                return Optional.empty();
            }
            try (DescriptorDirectory logs = DescriptorDirectory.open(directory)) {
                Path file = directory.resolve(R.SOLVER_BUILD_OUTPUT);
                BasicFileAttributes before;
                try { before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
                catch (NoSuchFileException absent) {
                    if (log != null) throw absent;
                    return Optional.empty();
                }
                if (log == null || !before.isRegularFile() || before.size() != log.size()
                        || ((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1
                        || !Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).equals(log.executable() ? EXECUTABLE : FILE)) {
                    throw new IOException("Saved build log differs from its manifest");
                }
                MessageDigest hash = sha256();
                ByteArrayOutputStream contents = new ByteArrayOutputStream();
                long count = 0;
                try (FileChannel input = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    ByteBuffer buffer = ByteBuffer.allocate(65536);
                    for (int read; (read = input.read(buffer)) != -1; buffer.clear()) {
                        if (read > log.size() - count) throw new IOException("Saved build log grew during reading");
                        hash.update(buffer.array(), 0, read); contents.write(buffer.array(), 0, read); count += read;
                    }
                }
                BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!after.isRegularFile() || count != log.size() || after.size() != count
                        || !before.lastModifiedTime().equals(after.lastModifiedTime())
                        || !java.util.Objects.equals(before.fileKey(), after.fileKey())
                        || !HexFormat.of().formatHex(hash.digest()).equals(log.sha256())) {
                    throw new IOException("Saved build log bytes failed verification");
                }
                return Optional.of(contents.toString(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }

    private static void verify(Path directory, Snapshot expected, boolean requireFile) throws IOException {
        if (expected == null || expected.payload() == null || expected.sha256() == null
                || expected.byteSize() < 0 || expected.entryCount() < (requireFile ? 1 : 0)) {
            throw new IOException("Artifact manifest is incomplete");
        }
        if (!digest(expected.payload()).equals(expected.sha256())) throw new IOException("Artifact manifest digest differs");
        try (DescriptorDirectory ignored = DescriptorDirectory.open(directory)) {
            Snapshot actual = scan(directory.toRealPath(), null, expected.byteSize(), expected.entryCount(), requireFile);
            if (!actual.equals(expected)) throw new IOException("Artifact tree differs from its validated manifest");
        }
    }

    private static Snapshot scan(Path root, Path destination, long maxBytes, int maxEntries, boolean requireFile) throws IOException {
        return scan(root, destination, maxBytes, maxEntries, requireFile, true);
    }

    private static Snapshot scan(Path root, Path destination, long maxBytes, int maxEntries,
                                 boolean requireFile, boolean frozen) throws IOException {
        List<Entry> entries = new ArrayList<>();
        List<Path> directories = new ArrayList<>();
        long[] bytes = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            private String relative(Path file) throws IOException {
                Path relative = root.relativize(file);
                if (!root.getFileSystem().getPath(relative.toString()).equals(relative)) {
                    throw new IOException("Artifact name is not representable as UTF-8");
                }
                return relative.toString().replace(file.getFileSystem().getSeparator(), "/");
            }

            private void reserve(long count) throws IOException {
                if (entries.size() >= maxEntries || count > maxBytes - bytes[0]) {
                    throw new IOException("Artifact exceeds its byte or entry limit");
                }
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                if (destination == null && frozen && !Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS).equals(DIRECTORY)) {
                    throw new IOException("Artifact directory permissions differ");
                }
                if (!dir.equals(root)) {
                    reserve(0);
                    entries.add(new Entry(relative(dir), "directory", 0, null, false, null));
                    if (destination != null) Files.createDirectory(destination.resolve(root.relativize(dir)),
                            PosixFilePermissions.asFileAttribute(PRIVATE));
                }
                if (destination != null) directories.add(destination.resolve(root.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String name = relative(file);
                Path target = destination == null ? null : destination.resolve(root.relativize(file));
                reserve(0);
                if (attributes.isSymbolicLink()) {
                    Path link = Files.readSymbolicLink(file);
                    if (link.isAbsolute() || !file.toRealPath().startsWith(root)) {
                        throw new IOException("Artifact link escapes its tree");
                    }
                    long length = link.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    reserve(length);
                    bytes[0] += length;
                    entries.add(new Entry(name, "link", length, null, false, link.toString()));
                    if (target != null) Files.createSymbolicLink(target, link);
                } else if (attributes.isRegularFile()) {
                    if (((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) {
                        throw new IOException("Artifact contains a hard-linked file");
                    }
                    reserve(attributes.size());
                    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
                    boolean executable = permissions.contains(PosixFilePermission.OWNER_EXECUTE)
                            || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                            || permissions.contains(PosixFilePermission.OTHERS_EXECUTE);
                    if (destination == null && frozen && !permissions.equals(executable ? EXECUTABLE : FILE)) {
                        throw new IOException("Artifact file permissions differ");
                    }
                    MessageDigest hash = sha256();
                    long size = readFile(file, target, hash, maxBytes - bytes[0], executable ? EXECUTABLE : FILE);
                    BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!after.isRegularFile() || attributes.size() != size || after.size() != size
                            || !attributes.lastModifiedTime().equals(after.lastModifiedTime())
                            || !java.util.Objects.equals(attributes.fileKey(), after.fileKey())) {
                        throw new IOException("Artifact source changed during copying");
                    }
                    bytes[0] += size;
                    entries.add(new Entry(name, "file", size, HexFormat.of().formatHex(hash.digest()), executable, null));
                } else {
                    throw new IOException("Artifact contains a special file");
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (requireFile && entries.stream().noneMatch(e -> e.kind().equals("file"))) throw new IOException("Artifact has no regular files");
        entries.sort(Comparator.comparing(Entry::path));
        if (destination != null) {
            // Check the recreated link graph, not merely its spelling in the input tree.
            for (Entry entry : entries) {
                if (entry.kind().equals("link") && !destination.resolve(entry.path()).toRealPath()
                        .startsWith(destination.toRealPath())) throw new IOException("Copied artifact link escapes its tree");
            }
            for (int i = directories.size() - 1; i >= 0; i--) {
                Files.setPosixFilePermissions(directories.get(i), DIRECTORY);
                try (FileChannel directory = FileChannel.open(directories.get(i), StandardOpenOption.READ)) {
                    directory.force(true);
                }
            }
        }
        String payload = JSON.toJson(new Manifest(1, entries));
        return new Snapshot(payload, digest(payload), bytes[0], entries.size());
    }

    private static long readFile(Path file, Path target, MessageDigest hash, long limit,
                                 Set<PosixFilePermission> permissions) throws IOException {
        try (FileChannel input = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             FileChannel output = target == null ? null : FileChannel.open(target,
                     StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate(65536);
            long count = 0;
            for (int read; (read = input.read(buffer)) != -1;) {
                if (read > limit - count) throw new IOException("Artifact exceeds its byte limit");
                count += read;
                buffer.flip(); hash.update(buffer.asReadOnlyBuffer());
                if (output != null) while (buffer.hasRemaining()) output.write(buffer);
                buffer.clear();
            }
            if (output != null) {
                Files.setPosixFilePermissions(target, permissions);
                output.force(true);
            }
            return count;
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String digest(String text) {
        return HexFormat.of().formatHex(sha256().digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static void removeOwnedCopy(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.setPosixFilePermissions(dir, PRIVATE);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
}
