package org.starexec.backend.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Linux {@code /proc/self/mountinfo} reader and longest-prefix mount resolver. */
public final class MountInfo {
    public record Mount(long id, String device, Path root, Path point, String type,
                        List<String> options, List<String> superOptions) { }

    private MountInfo() { }

    public static List<Mount> mounts() throws IOException {
        try (var stream = Files.newInputStream(Path.of("/proc/self/mountinfo"))) {
            byte[] bytes = stream.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) {
                throw new IOException("Mount table exceeds its inspection limit");
            }
            return parseMounts(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    public static List<Mount> parseMounts(String text) throws IOException {
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

    public static Mount sourceMount(List<Mount> mounts, Path source) throws IOException {
        List<Mount> ancestors = mounts.stream().filter(m -> source.startsWith(m.point())).toList();
        int depth = ancestors.stream().mapToInt(m -> m.point().getNameCount()).max().orElse(-1);
        List<Mount> deepest = ancestors.stream().filter(m -> m.point().getNameCount() == depth).toList();
        if (deepest.size() != 1) {
            throw new IOException("Ambiguous source mount topology");
        }
        return deepest.get(0);
    }

    private static String unescape(String value) throws IOException {
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\') {
                decoded.append(c);
                continue;
            }
            if (i + 3 >= value.length()) {
                throw new IOException("Truncated mount escape");
            }
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
}
