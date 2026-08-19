package org.starexec.backend;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
    private static final Path DEFAULT_SYSFS_CPU_PATH = Paths.get(
        "/sys/devices/system/cpu"
    );
    private static final Pattern NODE_DIR_PATTERN = Pattern.compile("node(\\d+)");
    private static final Pattern CPU_DIR_PATTERN = Pattern.compile("cpu(\\d+)");

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
        } catch (UnsafePartitionConfigurationException e) {
            // Deliberately not caught below. Falling back to no pinning is the right
            // response to a machine whose topology cannot be read; it is the wrong
            // response to a configuration a person wrote, because they asked for
            // isolation and would silently receive none while their numbers kept being
            // published. Fail loudly instead.
            log.error(
                "Refusing to start with an unsafe CPU partition configuration: " +
                e.getMessage()
            );
            throw e;
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

    /**
     * Thrown when an operator's explicit partition configuration would corrupt
     * measurements.
     *
     * <p>Its own type, because {@link #discover} otherwise catches everything and falls
     * back to no pinning. Degrading silently is right for a machine whose topology cannot
     * be read; it is wrong for a configuration someone wrote by hand. An operator who set
     * STAREXEC_CPU_PARTITIONS asked for isolation, and quietly giving them none — while
     * their jobs keep running and their numbers keep being published — is the worst of
     * the available outcomes.
     */
    static final class UnsafePartitionConfigurationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnsafePartitionConfigurationException(String message) {
            super(message);
        }
    }

    private static List<CpuPartition> partitionsFromOverride(String raw) {
        return partitionsFromOverride(raw, readThreadSiblings(DEFAULT_SYSFS_CPU_PATH));
    }

    static List<CpuPartition> partitionsFromOverride(
        String raw,
        Map<Integer, List<Integer>> siblings
    ) {
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
        validateExplicitPartitions(partitions, siblings);
        return Collections.unmodifiableList(partitions);
    }

    /**
     * Checks a hand-written partition set for layouts that would corrupt measurements.
     *
     * <p>{@link #subdivide} cannot protect these: an explicit STAREXEC_CPU_PARTITIONS
     * value never passes through it. That matters because
     * {@code docs/cpu-partition-scheduling.md} recommends {@code "0-7 8-15"}, which on the
     * split-half enumeration is exactly one partition holding every sibling of the other.
     * An operator following the documentation configures the worst case by hand.
     *
     * <p>The two checks are graded deliberately. Overlapping partitions and split
     * siblings mean two job pairs contend for the same hardware, which corrupts the
     * measurement, so they abort startup. Including CPU 0 only adds interrupt-handling
     * noise — it degrades a measurement rather than invalidating it — so it warns, and a
     * two-core CI box keeps working.
     */
    static void validateExplicitPartitions(
        List<CpuPartition> partitions,
        Map<Integer, List<Integer>> siblings
    ) {
        Map<Integer, Integer> owner = new HashMap<>();
        for (CpuPartition partition : partitions) {
            if (partition.cpusetCpus == null) {
                continue;
            }
            for (int cpu : expandCpuset(partition.cpusetCpus)) {
                Integer previous = owner.put(cpu, partition.index);
                if (previous != null && previous != partition.index) {
                    throw new UnsafePartitionConfigurationException(
                        "STAREXEC_CPU_PARTITIONS places CPU " + cpu +
                        " in both partition " + previous + " and partition " +
                        partition.index + ". Two job pairs would run on the same CPU," +
                        " so neither measurement would be trustworthy."
                    );
                }
            }
        }

        if (!siblings.isEmpty()) {
            for (Map.Entry<Integer, Integer> entry : owner.entrySet()) {
                int cpu = entry.getKey();
                int partitionIndex = entry.getValue();
                for (int sibling : siblings.getOrDefault(cpu, Collections.emptyList())) {
                    Integer siblingPartition = owner.get(sibling);
                    if (siblingPartition != null && siblingPartition != partitionIndex) {
                        throw new UnsafePartitionConfigurationException(
                            "STAREXEC_CPU_PARTITIONS splits SMT siblings: CPU " + cpu +
                            " is in partition " + partitionIndex + " while its sibling " +
                            "CPU " + sibling + " is in partition " + siblingPartition +
                            ". They share an L1 and L2 cache, so two job pairs would" +
                            " contend for the same cache while appearing isolated." +
                            " Give each partition whole physical cores instead."
                        );
                    }
                }
            }
        } else {
            log.warn(
                "SMT topology unavailable, so STAREXEC_CPU_PARTITIONS could not be" +
                " checked for sibling splits. If this host has hyper-threading, verify" +
                " by hand that no partition holds a sibling of another's CPUs."
            );
        }

        if (owner.containsKey(0)) {
            log.warn(
                "STAREXEC_CPU_PARTITIONS includes CPU 0, which handles interrupts and" +
                " OS work. This adds noise to whatever runs there. Excluding it is" +
                " preferred where the core count allows."
            );
        }
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
     * Splits a cpuset into n partitions, keeping SMT siblings together.
     *
     * @param cpusetStr cpuset string to subdivide
     * @param n number of partitions
     * @return cpuset string per partition
     */
    static List<String> subdivide(String cpusetStr, int n) {
        return subdivide(cpusetStr, n, readThreadSiblings(DEFAULT_SYSFS_CPU_PATH));
    }

    /**
     * Splits a cpuset into n partitions at <em>physical core</em> granularity.
     *
     * <p>This used to split by contiguous logical-CPU index, which is the worst possible
     * layout on the standard Linux enumeration. There, {@code cpu0..cpu(k-1)} are the
     * first thread of each core and {@code cpu(k)..cpu(2k-1)} are their SMT siblings, so
     * splitting an 8-core/16-thread machine in two produced {@code "0-7"} and
     * {@code "8-15"} — and partition 1 was then exactly the sibling set of partition 0.
     * Every pair on one partition shared an L1 and L2 with the pair on the other, which
     * is precisely the contention the partitioning exists to prevent. The layout the
     * documentation recommends, {@code "0-7 8-15"}, is that same collision.
     *
     * <p>Grouping by {@code thread_siblings_list} first means a partition boundary can
     * only fall between physical cores. Siblings are handed out together, to one pair,
     * which is the intended model: a pair gets whole cores and may use all of them.
     *
     * <p>When sibling topology is unavailable — a container without
     * {@code /sys/devices/system/cpu/*}/topology, say — this falls back to the old index
     * split and says so. That is a real loss of isolation, so it is logged at warn rather
     * than passing silently.
     *
     * @param siblings cpu id → all logical CPUs sharing its physical core; empty if the
     *                 topology could not be read
     */
    static List<String> subdivide(
        String cpusetStr,
        int n,
        Map<Integer, List<Integer>> siblings
    ) {
        if (n < 1) {
            throw new IllegalArgumentException("partition count must be >= 1");
        }
        List<Integer> cpus = expandCpuset(cpusetStr);
        if (n > cpus.size()) {
            throw new IllegalArgumentException(
                "partition count " + n + " exceeds CPU count " + cpus.size()
            );
        }

        List<List<Integer>> cores = groupBySiblings(cpus, siblings);

        if (cores.size() < n) {
            // More partitions than physical cores means at least one boundary must fall
            // between siblings. Refuse rather than hand back a layout that shares a
            // cache between two pairs while appearing to isolate them.
            throw new IllegalArgumentException(
                "partition count " + n + " exceeds the " + cores.size() +
                " physical core(s) available in cpuset " + cpusetStr +
                "; splitting further would place SMT siblings in different partitions" +
                " and let two job pairs share an L1/L2 cache"
            );
        }

        int k = cores.size();
        List<String> partitions = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int start = i * k / n;
            int endExclusive = (i + 1) * k / n;
            List<Integer> cpusInPartition = new ArrayList<>();
            for (List<Integer> core : cores.subList(start, endExclusive)) {
                cpusInPartition.addAll(core);
            }
            Collections.sort(cpusInPartition);
            partitions.add(compressCpuset(cpusInPartition));
        }
        return Collections.unmodifiableList(partitions);
    }

    /**
     * Groups logical CPUs into physical cores using sibling topology.
     *
     * <p>Order is by lowest member so the result is deterministic. A CPU with no sibling
     * information becomes its own group, which degrades to the previous behaviour for
     * that CPU rather than dropping it.
     */
    private static List<List<Integer>> groupBySiblings(
        List<Integer> cpus,
        Map<Integer, List<Integer>> siblings
    ) {
        if (siblings == null || siblings.isEmpty()) {
            log.warn(
                "SMT topology unavailable, so CPU partitions are being split by logical" +
                " CPU index. On the standard Linux enumeration that places sibling" +
                " threads in different partitions, letting two job pairs share an L1/L2" +
                " cache. Measurements from this host may be perturbed by co-scheduling."
            );
            List<List<Integer>> singles = new ArrayList<>();
            for (int cpu : cpus) {
                singles.add(Collections.singletonList(cpu));
            }
            return singles;
        }

        Set<Integer> available = new TreeSet<>(cpus);
        Set<Integer> placed = new HashSet<>();
        List<List<Integer>> cores = new ArrayList<>();

        for (int cpu : available) {
            if (placed.contains(cpu)) {
                continue;
            }
            List<Integer> group = new ArrayList<>();
            group.add(cpu);
            for (int sibling : siblings.getOrDefault(cpu, Collections.emptyList())) {
                // Only siblings that are actually in this cpuset; a sibling that is
                // offline or excluded is not ours to hand out.
                if (sibling != cpu && available.contains(sibling)) {
                    group.add(sibling);
                }
            }
            Collections.sort(group);
            placed.addAll(group);
            cores.add(group);
        }
        return cores;
    }

    /**
     * Reads SMT sibling topology from sysfs.
     *
     * @param cpuRootPath usually {@code /sys/devices/system/cpu}
     * @return cpu id → all logical CPUs sharing its physical core, empty when unreadable
     */
    static Map<Integer, List<Integer>> readThreadSiblings(Path cpuRootPath) {
        Map<Integer, List<Integer>> siblings = new TreeMap<>();
        if (cpuRootPath == null || !Files.isDirectory(cpuRootPath)) {
            return siblings;
        }

        try (DirectoryStream<Path> stream =
                 Files.newDirectoryStream(cpuRootPath, "cpu*")) {
            for (Path cpuPath : stream) {
                Matcher matcher = CPU_DIR_PATTERN.matcher(
                    cpuPath.getFileName().toString()
                );
                if (!matcher.matches()) {
                    continue;
                }
                int cpuIndex = Integer.parseInt(matcher.group(1));
                Path listPath = cpuPath
                    .resolve("topology")
                    .resolve("thread_siblings_list");
                try {
                    String list = Files.readString(listPath).trim();
                    if (!list.isEmpty()) {
                        siblings.put(cpuIndex, expandCpuset(list));
                    }
                } catch (IOException | IllegalArgumentException e) {
                    // A CPU without topology is not an error; it just has no siblings
                    // we can prove, and groupBySiblings treats it as its own core.
                    log.debug("No thread_siblings_list for cpu" + cpuIndex);
                }
            }
        } catch (IOException e) {
            log.warn("Could not read CPU topology under " + cpuRootPath, e);
        }
        return siblings;
    }

    static Map<String, String> env(String key, String value) {
        Map<String, String> env = new HashMap<>();
        if (key != null && value != null) {
            env.put(key, value);
        }
        return env;
    }
}
