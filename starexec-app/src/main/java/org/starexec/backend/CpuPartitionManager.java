package org.starexec.backend;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.starexec.logger.StarLogger;

/**
 * Discovers or configures CPU partitions for container scheduling.
 *
 * <p>Discovery priority (highest wins):</p>
 * <ol>
 *   <li>{@code STAREXEC_CPU_PARTITIONS}: explicit cpuset strings separated by
 *       whitespace or semicolons, e.g. {@code "0-7 8-15"}</li>
 *   <li>{@code STAREXEC_CPU_PARTITION_COUNT=N}: subdivide all CPUs into N equal
 *       groups</li>
 *   <li>{@code STAREXEC_CPU_PARTITION_COUNT=auto}: use NUMA nodes when more
 *       than one is visible; otherwise preserve legacy no-pinning behavior</li>
 *   <li>{@code STAREXEC_CPU_PARTITION_COUNT=1}: preserve legacy no-pinning
 *       behavior</li>
 * </ol>
 */
public final class CpuPartitionManager {

    private static final StarLogger log = StarLogger.getLogger(
        CpuPartitionManager.class
    );
    private static final Path DEFAULT_SYSFS_NODE_PATH = Paths.get(
        "/sys/devices/system/node"
    );
    private static final Path DEFAULT_CPU_ONLINE_PATH = Paths.get(
        "/sys/devices/system/cpu/online"
    );
    private static final Pattern NODE_DIR_PATTERN = Pattern.compile("node(\\d+)");

    private CpuPartitionManager() {
        throw new UnsupportedOperationException("Cannot instantiate utility class");
    }

    /**
     * Discovers or configures CPU partitions for container scheduling.
     *
     * <p>A no-pinning partition has {@code cpusetCpus == null} and
     * {@code cpusetMems == null}. PodmanBackend must treat null cpusets as
     * "no pinning" and skip HostConfig cpuset configuration.</p>
     *
     * @return unmodifiable list of CpuPartition, length >= 1, never null
     */
    public static List<CpuPartition> discover() {
        return discover(
            System.getenv(),
            DEFAULT_SYSFS_NODE_PATH,
            DEFAULT_CPU_ONLINE_PATH
        );
    }

    static List<CpuPartition> discover(
        Map<String, String> environment,
        Path sysfsNodePath,
        Path cpuOnlinePath
    ) {
        try {
            Map<String, String> env = environment == null
                ? Collections.emptyMap()
                : environment;
            String raw = env.get("STAREXEC_CPU_PARTITIONS");
            if (raw != null && !raw.trim().isEmpty()) {
                return partitionsFromOverride(raw);
            }

            String countStr = env.get("STAREXEC_CPU_PARTITION_COUNT");
            if (countStr == null || countStr.trim().isEmpty() || "auto".equalsIgnoreCase(countStr.trim())) {
                Map<Integer, String> numaNodes = readNumaNodesFromSysfs(sysfsNodePath);
                if (numaNodes.size() > 1) {
                    List<CpuPartition> partitions = new ArrayList<>();
                    int partitionIndex = 0;
                    for (Map.Entry<Integer, String> entry : numaNodes.entrySet()) {
                        partitions.add(
                            new CpuPartition(
                                partitionIndex,
                                entry.getValue(),
                                String.valueOf(entry.getKey())
                            )
                        );
                        partitionIndex++;
                    }
                    return Collections.unmodifiableList(partitions);
                }
                return singleNoPinningPartitions();
            }

            int count;
            try {
                count = Integer.parseInt(countStr.trim());
            } catch (NumberFormatException e) {
                log.warn(
                    "Invalid STAREXEC_CPU_PARTITION_COUNT='" + countStr +
                    "'; falling back to legacy no-pinning partition",
                    e
                );
                return singleNoPinningPartitions();
            }

            if (count <= 1) {
                return singleNoPinningPartitions();
            }

            String allCpus = readAllAvailableCpus(cpuOnlinePath);
            List<String> cpusets = subdivide(allCpus, count);
            List<CpuPartition> partitions = new ArrayList<>();
            for (int i = 0; i < cpusets.size(); i++) {
                partitions.add(new CpuPartition(i, cpusets.get(i), "0"));
            }
            return Collections.unmodifiableList(partitions);
        } catch (Exception e) {
            log.warn(
                "CPU partition discovery failed; falling back to legacy no-pinning partition",
                e
            );
            return singleNoPinningPartitions();
        }
    }

    /**
     * Returns a human-readable topology summary for startup logging.
     *
     * @param partitions partition list returned by {@link #discover()}
     * @return multi-line topology summary
     */
    public static String describeTopology(List<CpuPartition> partitions) {
        List<CpuPartition> safePartitions = (partitions == null || partitions.isEmpty())
            ? singleNoPinningPartitions()
            : partitions;

        String source = safePartitions.size() == 1 && safePartitions.get(0).cpusetCpus == null
            ? "legacy no-pinning configuration"
            : "CPU partition configuration";
        StringBuilder summary = new StringBuilder();
        summary
            .append("CpuPartitionManager: ")
            .append(safePartitions.size())
            .append(safePartitions.size() == 1 ? " partition from " : " partitions from ")
            .append(source);
        for (CpuPartition partition : safePartitions) {
            summary
                .append(System.lineSeparator())
                .append("  partition-")
                .append(partition.index)
                .append(": cpus=")
                .append(partition.cpusetCpus == null ? "all" : partition.cpusetCpus)
                .append(" mems=")
                .append(partition.cpusetMems == null ? "all" : partition.cpusetMems)
                .append(" node=")
                .append(partition.workerNodeName)
                .append(" queue=")
                .append(partition.queueName);
        }
        return summary.toString();
    }

    private static List<CpuPartition> partitionsFromOverride(String raw) {
        String[] tokens = raw.trim().split("[\\s;]+");
        List<CpuPartition> partitions = new ArrayList<>();
        for (String token : tokens) {
            String cpuset = token.trim();
            if (cpuset.isEmpty()) {
                continue;
            }
            cpuset = compressCpuset(expandCpuset(cpuset));
            partitions.add(new CpuPartition(partitions.size(), cpuset, "0"));
        }
        if (partitions.isEmpty()) {
            log.warn(
                "STAREXEC_CPU_PARTITIONS did not contain any usable cpuset strings; " +
                "falling back to legacy no-pinning partition"
            );
            return singleNoPinningPartitions();
        }
        return Collections.unmodifiableList(partitions);
    }

    private static List<CpuPartition> singleNoPinningPartitions() {
        return Collections.unmodifiableList(
            Collections.singletonList(singleNoPinningPartition())
        );
    }

    /**
     * Returns the legacy single-partition configuration that preserves pre-partition
     * behavior exactly: no CPU pinning, container-worker-1 node, container.q queue.
     *
     * <p>This method references {@link PodmanBackend#CONTAINER_WORKER_NODE} and
     * {@link PodmanBackend#CONTAINER_QUEUE_NAME} intentionally. Those constants are
     * marked {@code @Deprecated} to signal that new code should use the partition list
     * rather than the bare strings, but they must remain in {@code PodmanBackend} as
     * the canonical source of truth for backward-compatible names. If this creates a
     * classloading issue in the future, extract both strings to a shared constants
     * class (e.g., {@code ContainerBackendConstants}).</p>
     */
    static CpuPartition singleNoPinningPartition() {
        return new CpuPartition(
            0,
            null,
            null,
            legacyContainerWorkerNodeName(),
            sharedContainerQueueName()
        );
    }

    @SuppressWarnings("deprecation")
    static String legacyContainerWorkerNodeName() {
        return PodmanBackend.CONTAINER_WORKER_NODE;
    }

    @SuppressWarnings("deprecation")
    static String sharedContainerQueueName() {
        return PodmanBackend.CONTAINER_QUEUE_NAME;
    }

    /**
     * Reads NUMA nodes from {@code /sys/devices/system/node/nodeN/cpulist}.
     *
     * @return map from NUMA node index to cpuset string
     */
    private static Map<Integer, String> readNumaNodesFromSysfs() {
        return readNumaNodesFromSysfs(DEFAULT_SYSFS_NODE_PATH);
    }

    static Map<Integer, String> readNumaNodesFromSysfs(Path sysfsNodePath) {
        Map<Integer, String> nodes = new TreeMap<>();
        if (sysfsNodePath == null) {
            log.warn("Cannot read NUMA topology: sysfs node path is null");
            return nodes;
        }
        if (!Files.isDirectory(sysfsNodePath)) {
            log.warn("Cannot read NUMA topology: " + sysfsNodePath + " is not a directory");
            return nodes;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sysfsNodePath, "node*")) {
            for (Path nodePath : stream) {
                Matcher matcher = NODE_DIR_PATTERN.matcher(
                    nodePath.getFileName().toString()
                );
                if (!matcher.matches()) {
                    continue;
                }
                int nodeIndex = Integer.parseInt(matcher.group(1));
                Path cpulistPath = nodePath.resolve("cpulist");
                try {
                    String cpulist = Files.readString(cpulistPath).trim();
                    if (!cpulist.isEmpty()) {
                        nodes.put(nodeIndex, compressCpuset(expandCpuset(cpulist)));
                    }
                } catch (IOException | IllegalArgumentException e) {
                    log.warn("Could not read NUMA cpulist from " + cpulistPath, e);
                }
            }
        } catch (IOException e) {
            log.warn("Could not list NUMA nodes under " + sysfsNodePath, e);
        }
        return nodes;
    }

    /**
     * Reads /sys/devices/system/cpu/online.
     *
     * @return online CPU cpuset string, or a Runtime.availableProcessors fallback
     */
    private static String readAllAvailableCpus() {
        return readAllAvailableCpus(DEFAULT_CPU_ONLINE_PATH);
    }

    static String readAllAvailableCpus(Path cpuOnlinePath) {
        try {
            if (cpuOnlinePath != null && Files.isRegularFile(cpuOnlinePath)) {
                String online = Files.readString(cpuOnlinePath).trim();
                if (!online.isEmpty()) {
                    return compressCpuset(expandCpuset(online));
                }
            }
            log.warn(
                "Could not read CPU online list from " + cpuOnlinePath +
                "; falling back to Runtime.availableProcessors()"
            );
        } catch (IOException | IllegalArgumentException e) {
            log.warn(
                "Could not read CPU online list from " + cpuOnlinePath +
                "; falling back to Runtime.availableProcessors()",
                e
            );
        }

        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        return "0-" + (processors - 1);
    }

    /**
     * Expands a cpuset string like {@code "0-7,16-23"} into sorted CPU IDs.
     *
     * @param cpusetStr cpuset string
     * @return sorted list of CPU IDs
     */
    static List<Integer> expandCpuset(String cpusetStr) {
        if (cpusetStr == null || cpusetStr.trim().isEmpty()) {
            throw new IllegalArgumentException("cpuset string must not be blank");
        }

        List<Integer> cpus = new ArrayList<>();
        String[] parts = cpusetStr.trim().split(",");
        for (String rawPart : parts) {
            String part = rawPart.trim();
            if (part.isEmpty()) {
                throw new IllegalArgumentException(
                    "cpuset contains an empty segment: " + cpusetStr
                );
            }
            int dash = part.indexOf('-');
            if (dash >= 0) {
                int start = parseCpuNumber(part.substring(0, dash), cpusetStr);
                int end = parseCpuNumber(part.substring(dash + 1), cpusetStr);
                if (end < start) {
                    throw new IllegalArgumentException(
                        "cpuset range end is before start: " + part
                    );
                }
                for (int cpu = start; cpu <= end; cpu++) {
                    cpus.add(cpu);
                }
            } else {
                cpus.add(parseCpuNumber(part, cpusetStr));
            }
        }

        Collections.sort(cpus);
        List<Integer> unique = new ArrayList<>();
        Integer previous = null;
        for (Integer cpu : cpus) {
            if (!cpu.equals(previous)) {
                unique.add(cpu);
            }
            previous = cpu;
        }
        return unique;
    }

    private static int parseCpuNumber(String value, String cpusetStr) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0) {
                throw new IllegalArgumentException(
                    "CPU IDs must be non-negative in cpuset: " + cpusetStr
                );
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid CPU ID in cpuset '" + cpusetStr + "': " + value,
                e
            );
        }
    }

    /**
     * Compresses a sorted CPU ID list into minimal cpuset range notation.
     *
     * @param cpus CPU IDs, sorted or unsorted
     * @return cpuset string
     */
    static String compressCpuset(List<Integer> cpus) {
        if (cpus == null || cpus.isEmpty()) {
            throw new IllegalArgumentException("CPU list must not be empty");
        }

        List<Integer> sorted = new ArrayList<>(cpus);
        Collections.sort(sorted);

        List<String> ranges = new ArrayList<>();
        int rangeStart = sorted.get(0);
        int previous = rangeStart;
        for (int i = 1; i < sorted.size(); i++) {
            int current = sorted.get(i);
            if (current == previous) {
                continue;
            }
            if (current == previous + 1) {
                previous = current;
                continue;
            }
            ranges.add(formatRange(rangeStart, previous));
            rangeStart = current;
            previous = current;
        }
        ranges.add(formatRange(rangeStart, previous));
        return String.join(",", ranges);
    }

    private static String formatRange(int start, int end) {
        return start == end ? String.valueOf(start) : start + "-" + end;
    }

    /**
     * Splits a cpuset into n roughly equal partitions.
     *
     * @param cpusetStr cpuset string to subdivide
     * @param n number of partitions
     * @return cpuset string per partition
     */
    static List<String> subdivide(String cpusetStr, int n) {
        if (n < 1) {
            throw new IllegalArgumentException("partition count must be >= 1");
        }
        List<Integer> cpus = expandCpuset(cpusetStr);
        if (n > cpus.size()) {
            throw new IllegalArgumentException(
                "partition count " + n + " exceeds CPU count " + cpus.size()
            );
        }

        int k = cpus.size();
        List<String> partitions = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int start = i * k / n;
            int endExclusive = (i + 1) * k / n;
            partitions.add(compressCpuset(cpus.subList(start, endExclusive)));
        }
        return Collections.unmodifiableList(partitions);
    }

    static Map<String, String> env(String key, String value) {
        Map<String, String> env = new HashMap<>();
        if (key != null && value != null) {
            env.put(key, value);
        }
        return env;
    }
}
