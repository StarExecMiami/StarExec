package org.starexec.backend.storage;

import org.starexec.config.EnvironmentConfig;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owned upload storage excluded from every declared runner-writable root. */
public final class PrivateUploadStage implements AutoCloseable {
    private record Location(long mount, String device, Path physical) { }
    private record Root(Path path, List<Path> excluded, Map<Path, Map<String, Object>> ancestors,
                        Map<String, Object> identity, Location location, long owner) { }

    private final Root root;
    private final Path directory;
    private final Map<String, Object> identity;
    private boolean removed;
    private boolean closed;

    private PrivateUploadStage(Root root, Path directory, Map<String, Object> identity) {
        this.root = root;
        this.directory = directory;
        this.identity = identity;
    }

    public static PrivateUploadStage configured() throws IOException {
        String configured = EnvironmentConfig.getUploadStagingDirectory();
        if (configured == null || configured.isBlank()) {
            throw new IOException("Private upload storage is not configured");
        }
        try {
            List<Path> excluded = new ArrayList<>(List.of(
                Path.of(EnvironmentConfig.getDataDir()),
                Path.of(EnvironmentConfig.getJobOutputDirectory()),
                Path.of(EnvironmentConfig.getJobLogDirectory())));
            String artifacts = EnvironmentConfig.getBuildArtifactDirectory();
            if (artifacts != null && !artifacts.isBlank()) excluded.add(Path.of(artifacts));
            return create(Path.of(configured), excluded);
        } catch (InvalidPathException invalid) {
            throw new IOException("Private upload storage configuration is invalid", invalid);
        }
    }

    /** Claims a fresh child; never creates or repairs the provisioned root. */
    public static PrivateUploadStage create(Path root, List<Path> runnerRoots) throws IOException {
        Root observed = inspect(root, runnerRoots);
        Path directory = Files.createTempDirectory(root, "upload-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Map<String, Object> identity = identity(directory);
        PrivateUploadStage stage = new PrivateUploadStage(observed, directory, identity);
        stage.requireOwned();
        return stage;
    }

    public synchronized Path directory() throws IOException {
        requireOwned();
        return directory;
    }

    /** Revalidates the conserved root, mount and claim before a trusted application operation. */
    public synchronized void requireOwned() throws IOException {
        if (closed) throw new IOException("Upload staging claim is closed");
        requireRootUnchanged();
        if (removed || !identity.equals(identity(directory))) {
            throw new IOException("Upload staging no longer matches its conserved identity");
        }
        for (var mount : DedicatedStorageResource.mounts()) {
            if (mount.point().startsWith(directory)) {
                throw new IOException("Upload staging contains an unexpected mount");
            }
        }
    }

    /** Removes only this claim, without following links or changing linked file modes. */
    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        if (!removed) {
            requireOwned();
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(path);
                    return FileVisitResult.CONTINUE;
                }
            });
            removed = true;
        } else {
            requireRootUnchanged();
        }
        try (var parent = FileChannel.open(root.path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            parent.force(true);
        }
        try {
            Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            throw new IOException("Upload staging removal is unconfirmed");
        } catch (NoSuchFileException absent) {
            closed = true;
        }
    }

    private void requireRootUnchanged() throws IOException {
        if (!root.equals(inspect(root.path(), root.excluded()))) {
            throw new IOException("Private upload root no longer matches its conserved identity");
        }
    }

    private static Root inspect(Path path, List<Path> runnerRoots) throws IOException {
        normalized(path);
        if (runnerRoots == null || runnerRoots.isEmpty()) {
            throw new IOException("Private upload storage requires declared runner roots");
        }
        List<Path> excluded = new ArrayList<>();
        for (Path runner : runnerRoots) {
            normalized(runner);
            if (overlap(path, runner)) throw new IOException("Private upload storage overlaps runner storage");
            requireNoLinks(runner);
            excluded.add(runner);
        }
        long owner = ((Number) Files.getAttribute(Path.of("/proc/self"), "unix:uid")).longValue();
        Map<Path, Map<String, Object>> ancestors = new LinkedHashMap<>();
        for (Path current = path; current != null; current = current.getParent()) {
            Map<String, Object> observed = identity(current);
            int mode = ((Number) observed.get("mode")).intValue();
            long uid = ((Number) observed.get("uid")).longValue();
            boolean stickyRoot = uid == 0 && (mode & 01000) != 0;
            if ((mode & 0170000) != 0040000 || (uid != 0 && uid != owner)
                    || ((mode & 0022) != 0 && !stickyRoot)) {
                throw new IOException("Private upload storage has an untrusted ancestor");
            }
            ancestors.put(current, observed);
        }
        Map<String, Object> identity = ancestors.get(path);
        if (((Number) identity.get("uid")).longValue() != owner
                || (((Number) identity.get("mode")).intValue() & 07777) != 0700) {
            throw new IOException("Private upload storage requires application ownership and mode 0700");
        }
        var mounts = DedicatedStorageResource.mounts();
        Location location = location(mounts, path);
        for (Path runner : excluded) {
            Location other = location(mounts, runner);
            if (location.device().equals(other.device()) && overlap(location.physical(), other.physical())) {
                throw new IOException("Private upload storage aliases runner storage");
            }
        }
        for (var entry : ancestors.entrySet()) {
            if (!entry.getValue().equals(identity(entry.getKey()))) {
                throw new IOException("Private upload storage changed during observation");
            }
        }
        if (!location.equals(location(DedicatedStorageResource.mounts(), path))) {
            throw new IOException("Private upload storage mount changed during observation");
        }
        return new Root(path, List.copyOf(excluded), Map.copyOf(ancestors), identity, location, owner);
    }

    private static Map<String, Object> identity(Path path) throws IOException {
        return Map.copyOf(Files.readAttributes(path, "unix:dev,ino,mode,uid", LinkOption.NOFOLLOW_LINKS));
    }

    private static Location location(List<DedicatedStorageResource.Mount> mounts, Path path) throws IOException {
        var mount = BuildExecutionRuntime.sourceMount(mounts, path);
        return new Location(mount.id(), mount.device(), mount.root().resolve(mount.point().relativize(path)));
    }

    private static void requireNoLinks(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("Runner storage contains a linked ancestor");
        }
    }

    private static void normalized(Path path) throws IOException {
        if (path == null || !path.isAbsolute() || path.getParent() == null || !path.equals(path.normalize())) {
            throw new IOException("Storage paths must be normalized absolute non-root paths");
        }
    }

    private static boolean overlap(Path left, Path right) {
        return left.startsWith(right) || right.startsWith(left);
    }
}
