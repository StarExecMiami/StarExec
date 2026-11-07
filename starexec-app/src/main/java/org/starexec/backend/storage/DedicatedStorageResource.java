package org.starexec.backend.storage;

import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Observes an operator-provisioned, dedicated ext4 filesystem; never provisions storage. */
public final class DedicatedStorageResource {
    public record Limits(long bytes, long inodes) {
        public Limits {
            if (bytes <= 0 || inodes <= 0) throw new IllegalArgumentException("Storage limits must be positive");
        }
    }

    record Mount(long id, String device, Path root, Path point, String type,
                 List<String> options, List<String> superOptions) { }
    record Capacity(long bytes, long inodes, String filesystemId) { }

    private final Path root;
    private final Limits limits;
    private final List<Path> protectedRoots;
    private final Mount mount;
    private final Capacity capacity;
    private final Object fileKey;
    private final String filesystemOptions;

    private DedicatedStorageResource(Path root, Limits limits, List<Path> protectedRoots,
                                     Mount mount, Capacity capacity, Object fileKey, String filesystemOptions) {
        this.root = root;
        this.limits = limits;
        this.protectedRoots = protectedRoots;
        this.mount = mount;
        this.capacity = capacity;
        this.fileKey = fileKey;
        this.filesystemOptions = filesystemOptions;
    }

    /**
     * The caller must keep mount configuration and all ancestors under trusted control.
     * A lease must exclude other writers before exposing any part of this filesystem.
     * Limits describe the entire filesystem, not free space or a directory estimate.
     */
    public static DedicatedStorageResource inspect(Path root, Limits limits, List<Path> protectedRoots)
            throws IOException {
        normalized(root);
        Objects.requireNonNull(limits, "limits");
        if (protectedRoots == null || protectedRoots.isEmpty()) {
            throw new IOException("Storage inspection requires the protected application roots");
        }
        List<Path> protectedCopy = List.copyOf(protectedRoots);
        for (Path protectedRoot : protectedCopy) {
            normalized(protectedRoot);
            if (root.startsWith(protectedRoot) || protectedRoot.startsWith(root)) {
                throw new IOException("Execution storage overlaps protected application storage");
            }
        }
        try (DescriptorDirectory ignored = DescriptorDirectory.open(root)) {
            BasicFileAttributes before = attributes(root);
            if (before.fileKey() == null || !Files.getPosixFilePermissions(root, LinkOption.NOFOLLOW_LINKS)
                    .equals(PosixFilePermissions.fromString("rwx------"))) {
                throw new IOException("Execution storage requires a private identifiable root");
            }
            List<Mount> mounts = mounts();
            Mount mount = dedicatedMount(mounts, root);
            Object device = Files.getAttribute(root, "unix:dev", LinkOption.NOFOLLOW_LINKS);
            for (Path protectedRoot : protectedCopy) {
                try (DescriptorDirectory checked = DescriptorDirectory.open(protectedRoot)) {
                    if (device.equals(Files.getAttribute(protectedRoot, "unix:dev", LinkOption.NOFOLLOW_LINKS))) {
                        throw new IOException("Execution storage shares the application filesystem");
                    }
                }
            }
            String filesystemOptions = fullOptions(mount.device());
            requireUnfilteredCounters(filesystemOptions);
            Capacity capacity = capacity(stat(root), limits);
            var store = Files.getFileStore(root);
            if (store.isReadOnly() || !store.type().equals("ext4") || store.getTotalSpace() != capacity.bytes()) {
                throw new IOException("Execution storage capacity observations disagree");
            }
            if (!before.fileKey().equals(attributes(root).fileKey())
                    || !mount.equals(dedicatedMount(mounts(), root))
                    || !filesystemOptions.equals(fullOptions(mount.device()))) {
                throw new IOException("Execution storage changed during inspection");
            }
            return new DedicatedStorageResource(root, limits, protectedCopy, mount, capacity, before.fileKey(), filesystemOptions);
        }
    }

    /** Repeat immediately before admission and trusted output reads; an old observation is insufficient. */
    public void requireUnchanged() throws IOException {
        DedicatedStorageResource current = inspect(root, limits, protectedRoots);
        if (!mount.equals(current.mount) || !capacity.equals(current.capacity) || !fileKey.equals(current.fileKey)
                || !filesystemOptions.equals(current.filesystemOptions)) {
            throw new IOException("Execution storage no longer matches its conserved identity and capacity");
        }
    }

    public Path root() { return root; }
    public long capacityBytes() { return capacity.bytes(); }
    public long inodeLimit() { return capacity.inodes(); }

    /** Stable across a mount incarnation; aliases must compete for the same resource. */
    public String filesystemKey() { return capacity.filesystemId(); }

    /** Includes the mount incarnation so an unmount/remount requires explicit recovery. */
    public String identity() {
        return identity(root, mount, capacity, fileKey, filesystemOptions);
    }

    static String identity(Path root, Mount mount, Capacity capacity, Object fileKey, String filesystemOptions) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(root.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(String.join(",", mount.options()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(String.join(",", mount.superOptions()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            String optionsDigest = HexFormat.of().formatHex(digest.digest(filesystemOptions.getBytes(StandardCharsets.UTF_8)));
            return mount.device() + ":" + mount.id() + ":" + capacity.filesystemId()
                    + ":" + capacity.bytes() + ":" + capacity.inodes() + ":" + fileKey + ":" + optionsDigest;
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Required storage identity digest is unavailable", unavailable);
        }
    }

    @Override public String toString() {
        return "DedicatedStorageResource[capacityBytes=" + capacity.bytes() + ", inodes=" + capacity.inodes() + "]";
    }

    private static void normalized(Path path) throws IOException {
        if (path == null || !path.isAbsolute() || path.getParent() == null || !path.equals(path.normalize())) {
            throw new IOException("Storage paths must be absolute normalized non-root directories");
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        var result = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!result.isDirectory() || result.isSymbolicLink()) throw new IOException("Storage root is not a directory");
        return result;
    }

    private static List<Mount> mounts() throws IOException {
        try (var stream = Files.newInputStream(Path.of("/proc/self/mountinfo"))) {
            byte[] bytes = stream.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IOException("Mount table exceeds its inspection limit");
            return parseMounts(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    static List<Mount> parseMounts(String text) throws IOException {
        List<Mount> result = new ArrayList<>();
        for (String line : text.lines().toList()) {
            String[] fields = line.split(" ");
            int separator = Arrays.asList(fields).indexOf("-");
            if (separator < 6 || fields.length != separator + 4 || !fields[2].matches("[0-9]+:[0-9]+")) {
                throw new IOException("Malformed kernel mount observation");
            }
            try {
                long id = Long.parseLong(fields[0]);
                Path root = Path.of(unescape(fields[3]));
                Path point = Path.of(unescape(fields[4]));
                String type = fields[separator + 1];
                // Pseudo-filesystems such as nsfs can use an opaque root name.
                if (id <= 0 || !point.isAbsolute() || !point.equals(point.normalize())
                        || type.equals("ext4") && (!root.isAbsolute() || !root.equals(root.normalize()))) {
                    throw new IllegalArgumentException("Invalid mount identity");
                }
                result.add(new Mount(id, fields[2], root, point, type,
                        List.of(fields[5].split(",")), List.of(fields[separator + 3].split(","))));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid kernel mount identity", invalid);
            }
        }
        return List.copyOf(result);
    }

    private static String unescape(String value) throws IOException {
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\') { decoded.append(c); continue; }
            if (i + 3 >= value.length()) throw new IOException("Truncated mount escape");
            String octal = value.substring(i + 1, i + 4);
            switch (octal) {
                case "040" -> decoded.append(' ');
                case "011" -> decoded.append('\t');
                case "012" -> decoded.append('\n');
                case "134" -> decoded.append('\\');
                default -> throw new IOException("Invalid mount escape");
            }
            i += 3;
        }
        return decoded.toString();
    }

    static Mount dedicatedMount(List<Mount> mounts, Path root) throws IOException {
        List<Mount> matching = mounts.stream().filter(m -> m.point().equals(root)).toList();
        if (matching.size() != 1) throw new IOException("Execution storage must have one unambiguous dedicated mount");
        Mount selected = matching.get(0);
        if (!selected.type().equals("ext4") || !selected.root().equals(root.getRoot())
                || !selected.options().containsAll(List.of("rw", "nodev", "nosuid"))
                || selected.options().contains("ro") || quotaMode(selected.options()) || quotaMode(selected.superOptions())) {
            throw new IOException("Execution storage requires a writable dedicated ext4 root with nodev and nosuid");
        }
        for (Mount mount : mounts) {
            if (mount == selected) continue;
            if (mount.device().equals(selected.device()) || mount.point().startsWith(root)) {
                throw new IOException("Execution storage has an alias or nested mount");
            }
        }
        return selected;
    }

    private static boolean quotaMode(List<String> options) {
        return options.stream().anyMatch(option -> List.of("quota", "usrquota", "grpquota", "prjquota", "pquota").contains(option)
                || option.startsWith("usrjquota=") || option.startsWith("grpjquota=") || option.startsWith("jqfmt="));
    }

    /** ext4 can clip statfs counters to a project soft limit; inspect options including defaults. */
    static void requireUnfilteredCounters(String options) throws IOException {
        List<String> tokens = List.of(options.strip().split("[,\\s]+"));
        if (!tokens.get(0).equals("rw")
                || tokens.stream().noneMatch(t -> List.of("data=ordered", "data=writeback", "data=journal").contains(t))
                || quotaMode(tokens)) {
            throw new IOException("Whole-filesystem capacity requires unfiltered writable ext4 counters");
        }
    }

    static String fullOptions(String device) throws IOException {
        if (!device.matches("[0-9]+:[0-9]+")) throw new IOException("Invalid block device identity");
        String kernelName = Path.of("/sys/dev/block", device).toRealPath().getFileName().toString();
        if (!kernelName.matches("[A-Za-z0-9_.!-]+")) throw new IOException("Unsupported kernel block device name");
        try (var stream = Files.newInputStream(Path.of("/proc/fs/ext4", kernelName, "options"))) {
            byte[] bytes = stream.readNBytes(65537);
            if (bytes.length > 65536) throw new IOException("Filesystem options exceed their inspection limit");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    static Capacity capacity(String observation, Limits limits) throws IOException {
        String[] fields = observation.strip().split(":", -1);
        if (fields.length != 4 || !fields[3].matches("[0-9a-fA-F]+") || fields[3].matches("0+")) {
            throw new IOException("Malformed filesystem capacity observation");
        }
        try {
            for (int i = 0; i < 3; i++) {
                if (!fields[i].matches("[0-9]+")) throw new IllegalArgumentException("Invalid capacity counter");
            }
            long blockSize = Long.parseLong(fields[0]);
            long blocks = Long.parseLong(fields[1]);
            long inodes = Long.parseLong(fields[2]);
            long bytes = Math.multiplyExact(blockSize, blocks);
            if (blockSize <= 0 || blocks <= 0 || inodes <= 0 || bytes > limits.bytes() || inodes > limits.inodes()) {
                throw new IllegalArgumentException("Filesystem exceeds the execution allowance");
            }
            return new Capacity(bytes, inodes, fields[3].toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IOException("Invalid or excessive filesystem capacity", invalid);
        }
    }

    static String stat(Path root) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("/usr/bin/stat", "--file-system", "--format=%S:%b:%c:%i", "--", root.toString());
        builder.environment().clear();
        builder.environment().put("LC_ALL", "C");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                throw new IOException("Filesystem capacity inspection timed out");
            }
            try (var output = process.getInputStream()) {
                byte[] bytes = output.readNBytes(1025);
                if (process.exitValue() != 0 || bytes.length > 1024) {
                    throw new IOException("Filesystem capacity inspection failed");
                }
                return new String(bytes, StandardCharsets.US_ASCII);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Filesystem capacity inspection was interrupted", interrupted);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
