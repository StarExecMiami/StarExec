package org.starexec.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.starexec.logger.StarLogger;

/**
 * Physical CPU core exclusivity policy, sysfs topology discovery, and cgroup v2 {@code cpuset}
 * isolated partition controller for {@link LocalBackend}.
 *
 * <p>Supports two explicit modes controlled by {@code STAREXEC_LOCAL_ISOLATION_MODE}:
 * <ul>
 *   <li>{@link IsolationMode#PERMISSIVE}: default local-development / throughput profile using
 *       {@code taskset}. Attempts are attested as {@code UNQUALIFIED} with
 *       {@code physical_exclusivity_attested=false} (or {@code VIOLATED} if CPU allocation
 *       breaks).</li>
 *   <li>{@link IsolationMode#STRICT}: scientific profile requiring fail-closed verification of
 *       cgroup v2 {@code cpuset} delegation, full online SMT sibling group reservation in an
 *       {@code isolated} {@code cpuset.cpus.partition}, and {@code taskset} pinning inside that
 *       reserved slot.</li>
 * </ul>
 */
public final class LocalCpuExclusivity {

    private static final StarLogger log = StarLogger.getLogger(LocalCpuExclusivity.class);

    public static final Path DEFAULT_SYSFS_CPU_PATH = Path.of("/sys/devices/system/cpu");
    public static final Path DEFAULT_CGROUP_ROOT = Path.of("/sys/fs/cgroup");
    public static final Path DEFAULT_PROC_SELF_CGROUP = Path.of("/proc/self/cgroup");
    public static final Path DEFAULT_PROC_ROOT = Path.of("/proc");
    static final String JVM_LEAF_CGROUP_NAME = "starexec-jvm";

    private LocalCpuExclusivity() {}

    /** Isolation profile negotiated for {@link LocalBackend}. */
    public enum IsolationMode {
        PERMISSIVE("permissive"),
        STRICT("strict");

        private final String value;

        IsolationMode(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        /**
         * Parses {@code STAREXEC_LOCAL_ISOLATION_MODE}, defaulting blank/null to
         * {@link #PERMISSIVE} and failing fast on unknown values.
         */
        public static IsolationMode parse(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return PERMISSIVE;
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            for (IsolationMode mode : values()) {
                if (mode.value.equals(normalized)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException(
                    "Invalid STAREXEC_LOCAL_ISOLATION_MODE value '"
                            + raw
                            + "'. Supported values: permissive, strict");
        }
    }

    /** Hardware topology metadata read from sysfs for a leased logical CPU. */
    public static final class CoreTopology {
        private final int cpu;
        private final Integer coreId;
        private final Integer packageId;
        private final List<Integer> siblingCpus;
        private final String siblingCpuset;

        public CoreTopology(
                int cpu, Integer coreId, Integer packageId, List<Integer> siblingCpus) {
            this.cpu = cpu;
            this.coreId = coreId;
            this.packageId = packageId;
            List<Integer> copy =
                    siblingCpus == null
                            ? Collections.emptyList()
                            : Collections.unmodifiableList(new ArrayList<>(siblingCpus));
            this.siblingCpus = copy;
            this.siblingCpuset =
                    copy.isEmpty() ? null : CpuPartitionManager.compressCpuset(copy);
        }

        public int getCpu() {
            return cpu;
        }

        public Integer getCoreId() {
            return coreId;
        }

        public Integer getPackageId() {
            return packageId;
        }

        public List<Integer> getSiblingCpus() {
            return siblingCpus;
        }

        public String getSiblingCpuset() {
            return siblingCpuset;
        }

        /** Returns {@code true} iff core_id, physical_package_id, and SMT siblings are valid. */
        public boolean isCompleteStrictTopology() {
            return coreId != null
                    && packageId != null
                    && !siblingCpus.isEmpty()
                    && siblingCpus.contains(cpu);
        }
    }

    /** Per-attempt isolated cgroup v2 slot reservation covering an entire SMT sibling group. */
    public static final class SlotReservation {
        private final int leasedCpu;
        private final String slotId;
        private final Path slotCgroupPath;
        private final String cgroupRelativePath;
        private final String reservedSiblingCpus;
        private final List<Integer> reservedCpuList;

        public SlotReservation(
                int leasedCpu,
                String slotId,
                Path slotCgroupPath,
                String cgroupRelativePath,
                String reservedSiblingCpus,
                List<Integer> reservedCpuList) {
            this.leasedCpu = leasedCpu;
            this.slotId = Objects.requireNonNull(slotId, "slotId");
            this.slotCgroupPath = slotCgroupPath;
            this.cgroupRelativePath = cgroupRelativePath;
            this.reservedSiblingCpus =
                    Objects.requireNonNull(reservedSiblingCpus, "reservedSiblingCpus");
            this.reservedCpuList =
                    Collections.unmodifiableList(new ArrayList<>(reservedCpuList));
        }

        public int getLeasedCpu() {
            return leasedCpu;
        }

        public String getSlotId() {
            return slotId;
        }

        public Path getSlotCgroupPath() {
            return slotCgroupPath;
        }

        public Path getCgroupProcsPath() {
            return slotCgroupPath != null ? slotCgroupPath.resolve("cgroup.procs") : null;
        }

        public String getCgroupRelativePath() {
            return cgroupRelativePath;
        }

        public String getReservedSiblingCpus() {
            return reservedSiblingCpus;
        }

        public List<Integer> getReservedCpuList() {
            return reservedCpuList;
        }
    }

    /** Runtime cgroup v2 slot state read from kernel control files. */
    public static final class SlotCgroupState {
        private final String effectiveCpus;
        private final List<Integer> effectiveCpuList;
        private final String partitionMode;
        private final String effectiveMems;

        public SlotCgroupState(
                String effectiveCpus, String partitionMode, String effectiveMems) {
            this.effectiveCpus = effectiveCpus == null ? "" : effectiveCpus.trim();
            this.effectiveCpuList =
                    this.effectiveCpus.isEmpty()
                            ? Collections.emptyList()
                            : Collections.unmodifiableList(
                                    CpuPartitionManager.expandCpuset(this.effectiveCpus));
            this.partitionMode = partitionMode == null ? "" : partitionMode.trim();
            this.effectiveMems = effectiveMems == null ? "" : effectiveMems.trim();
        }

        public String getEffectiveCpus() {
            return effectiveCpus;
        }

        public List<Integer> getEffectiveCpuList() {
            return effectiveCpuList;
        }

        public String getPartitionMode() {
            return partitionMode;
        }

        public String getEffectiveMems() {
            return effectiveMems;
        }
    }

    /** Reads per-CPU sysfs topology ({@code core_id}, {@code physical_package_id}, siblings). */
    public interface TopologyReader {
        CoreTopology readCoreTopology(int cpu) throws LocalCpuAttestation.CpuStateReadException;
    }

    /** Linux sysfs implementation of {@link TopologyReader}. */
    public static final class LinuxTopologyReader implements TopologyReader {
        public static final LinuxTopologyReader DEFAULT =
                new LinuxTopologyReader(DEFAULT_SYSFS_CPU_PATH);

        private final Path sysfsCpuPath;

        public LinuxTopologyReader(Path sysfsCpuPath) {
            this.sysfsCpuPath = Objects.requireNonNull(sysfsCpuPath, "sysfsCpuPath");
        }

        @Override
        public CoreTopology readCoreTopology(int cpu)
                throws LocalCpuAttestation.CpuStateReadException {
            Path topoDir = sysfsCpuPath.resolve("cpu" + cpu).resolve("topology");
            Integer coreId = readOptionalInt(topoDir.resolve("core_id"), cpu, "core_id");
            Integer packageId =
                    readOptionalInt(
                            topoDir.resolve("physical_package_id"), cpu, "physical_package_id");
            List<Integer> siblings =
                    readOptionalCpuset(
                            topoDir.resolve("thread_siblings_list"), cpu, "thread_siblings_list");
            return new CoreTopology(cpu, coreId, packageId, siblings);
        }

        private static Integer readOptionalInt(Path path, int cpu, String field)
                throws LocalCpuAttestation.CpuStateReadException {
            if (!Files.exists(path)) {
                return null;
            }
            try {
                String raw = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (raw.isEmpty()) {
                    return null;
                }
                return Integer.parseInt(raw);
            } catch (IOException | NumberFormatException e) {
                throw new LocalCpuAttestation.CpuStateReadException(
                        "Failed to read sysfs " + field + " for CPU " + cpu + " at " + path, e);
            }
        }

        private static List<Integer> readOptionalCpuset(Path path, int cpu, String field)
                throws LocalCpuAttestation.CpuStateReadException {
            if (!Files.exists(path)) {
                return Collections.emptyList();
            }
            try {
                String raw = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (raw.isEmpty()) {
                    return Collections.emptyList();
                }
                return CpuPartitionManager.expandCpuset(raw);
            } catch (IOException | IllegalArgumentException e) {
                throw new LocalCpuAttestation.CpuStateReadException(
                        "Failed to parse sysfs " + field + " for CPU " + cpu + " at " + path, e);
            }
        }
    }

    /** Abstraction for cgroup v2 {@code cpuset} isolated partition management. */
    public interface CgroupCpusetController {
        void verifyStrictHostSupport(
                List<Integer> configuredCores,
                Map<Integer, CoreTopology> topologies,
                List<Integer> effectiveAffinity,
                List<Integer> onlineCpus);

        SlotReservation acquireSlot(int execId, int pairId, CoreTopology topology)
                throws IOException;

        void assignPid(SlotReservation reservation, long pid) throws IOException;

        SlotCgroupState readSlotState(SlotReservation reservation)
                throws LocalCpuAttestation.CpuStateReadException;

        Optional<Boolean> isPidInSlot(SlotReservation reservation, long pid)
                throws LocalCpuAttestation.CpuStateReadException;

        void releaseSlot(SlotReservation reservation);
    }

    /** Real Linux cgroups v2 {@code cpuset} isolated partition controller. */
    public static final class LinuxCgroupCpusetController implements CgroupCpusetController {
        public static final LinuxCgroupCpusetController DEFAULT =
                new LinuxCgroupCpusetController(
                        DEFAULT_CGROUP_ROOT, DEFAULT_PROC_SELF_CGROUP, DEFAULT_PROC_ROOT);

        private final Path cgroupRoot;
        private final Path procSelfCgroupPath;
        private final Path procRoot;
        private volatile Path resolvedSlotRoot;

        public LinuxCgroupCpusetController(
                Path cgroupRoot, Path procSelfCgroupPath, Path procRoot) {
            this.cgroupRoot = Objects.requireNonNull(cgroupRoot, "cgroupRoot");
            this.procSelfCgroupPath =
                    Objects.requireNonNull(procSelfCgroupPath, "procSelfCgroupPath");
            this.procRoot = Objects.requireNonNull(procRoot, "procRoot");
        }

        @Override
        public synchronized void verifyStrictHostSupport(
                List<Integer> configuredCores,
                Map<Integer, CoreTopology> topologies,
                List<Integer> effectiveAffinity,
                List<Integer> onlineCpus) {
            Path rootControllers = cgroupRoot.resolve("cgroup.controllers");
            if (!Files.exists(rootControllers)) {
                throw new IllegalStateException(
                        "Strict local CPU isolation requires cgroups v2 unified hierarchy at "
                                + cgroupRoot
                                + " (missing "
                                + rootControllers
                                + "). Set STAREXEC_LOCAL_ISOLATION_MODE=permissive for local dev.");
            }

            Path slotRoot = resolveAndPrepareSlotRoot();
            this.resolvedSlotRoot = slotRoot;

            int probeCore = configuredCores.get(0);
            CoreTopology probeTopology = topologies.get(probeCore);
            Path probeDir = slotRoot.resolve("starexec-probe-" + ProcessHandle.current().pid());
            try {
                deleteIfEmptyDir(probeDir);
                createIsolatedChildCgroup(slotRoot, probeDir, probeTopology);
                log.info(
                        "Verified strict cgroup v2 cpuset isolated partition probe at "
                                + probeDir
                                + " for CPUs "
                                + probeTopology.getSiblingCpuset());
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Strict local CPU isolation probe failed in cgroup "
                                + slotRoot
                                + ": "
                                + e.getMessage()
                                + ". Ensure cgroup v2 cpuset delegation and isolated partitions are permitted.",
                        e);
            } finally {
                deleteIfEmptyDir(probeDir);
            }
        }

        @Override
        public SlotReservation acquireSlot(int execId, int pairId, CoreTopology topology)
                throws IOException {
            if (topology == null || !topology.isCompleteStrictTopology()) {
                throw new IOException(
                        "Cannot reserve strict CPU slot without complete sysfs topology");
            }
            Path slotRoot = resolvedSlotRoot != null ? resolvedSlotRoot : resolveAndPrepareSlotRoot();
            String slotId =
                    "starexec-slot-exec"
                            + execId
                            + "-pair"
                            + pairId
                            + "-cpu"
                            + topology.getCpu();
            Path slotDir = slotRoot.resolve(slotId);
            deleteIfEmptyDir(slotDir);
            createIsolatedChildCgroup(slotRoot, slotDir, topology);
            String relPath = toCgroupRelativePath(slotDir);
            return new SlotReservation(
                    topology.getCpu(),
                    slotId,
                    slotDir,
                    relPath,
                    topology.getSiblingCpuset(),
                    topology.getSiblingCpus());
        }

        @Override
        public void assignPid(SlotReservation reservation, long pid) throws IOException {
            if (reservation == null || reservation.getSlotCgroupPath() == null || pid <= 0) {
                return;
            }
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) {
                return;
            }
            Path procsFile = reservation.getCgroupProcsPath();
            try {
                Files.writeString(procsFile, pid + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    throw new IOException(
                            "Failed to assign live PID "
                                    + pid
                                    + " to isolated slot cgroup "
                                    + procsFile
                                    + ": "
                                    + e.getMessage(),
                            e);
                }
            }
        }

        @Override
        public SlotCgroupState readSlotState(SlotReservation reservation)
                throws LocalCpuAttestation.CpuStateReadException {
            if (reservation == null || reservation.getSlotCgroupPath() == null) {
                throw new LocalCpuAttestation.CpuStateReadException(
                        "Missing slot cgroup reservation path");
            }
            Path slotDir = reservation.getSlotCgroupPath();
            try {
                String effCpus =
                        Files.readString(slotDir.resolve("cpuset.cpus.effective"), StandardCharsets.UTF_8)
                                .trim();
                String partition =
                        Files.readString(slotDir.resolve("cpuset.cpus.partition"), StandardCharsets.UTF_8)
                                .trim();
                Path memsEff = slotDir.resolve("cpuset.mems.effective");
                String effMems =
                        Files.exists(memsEff)
                                ? Files.readString(memsEff, StandardCharsets.UTF_8).trim()
                                : "";
                return new SlotCgroupState(effCpus, partition, effMems);
            } catch (IOException | IllegalArgumentException e) {
                throw new LocalCpuAttestation.CpuStateReadException(
                        "Failed reading cgroup v2 slot state at " + slotDir + ": " + e.getMessage(),
                        e);
            }
        }

        @Override
        public Optional<Boolean> isPidInSlot(SlotReservation reservation, long pid)
                throws LocalCpuAttestation.CpuStateReadException {
            if (reservation == null || reservation.getCgroupRelativePath() == null || pid <= 0) {
                return Optional.empty();
            }
            Path procCgroup = procRoot.resolve(Long.toString(pid)).resolve("cgroup");
            if (!Files.exists(procCgroup)) {
                return Optional.empty();
            }
            try {
                String content = Files.readString(procCgroup, StandardCharsets.UTF_8);
                String rel = parseV2CgroupRelativePath(content);
                if (rel == null) {
                    return Optional.of(false);
                }
                return Optional.of(
                        rel.equals(reservation.getCgroupRelativePath())
                                || rel.startsWith(reservation.getCgroupRelativePath() + "/"));
            } catch (IOException e) {
                if (!Files.exists(procCgroup)) {
                    return Optional.empty();
                }
                throw new LocalCpuAttestation.CpuStateReadException(
                        "Failed reading " + procCgroup + ": " + e.getMessage(), e);
            }
        }

        @Override
        public void releaseSlot(SlotReservation reservation) {
            if (reservation == null || reservation.getSlotCgroupPath() == null) {
                return;
            }
            Path slotDir = reservation.getSlotCgroupPath();
            try {
                Files.deleteIfExists(slotDir);
            } catch (IOException e) {
                log.warn(
                        "Could not immediately delete cgroup slot directory "
                                + slotDir
                                + ": "
                                + e.getMessage());
            }
        }

        private Path resolveAndPrepareSlotRoot() {
            String selfRel = readSelfV2RelativePath();
            Path selfDir =
                    selfRel.equals("/") ? cgroupRoot : cgroupRoot.resolve(selfRel.substring(1));
            if (!Files.isDirectory(selfDir)) {
                throw new IllegalStateException(
                        "Resolved cgroup directory does not exist: " + selfDir);
            }

            // If a previous initialization already moved the JVM into <slotRoot>/starexec-jvm,
            // reuse <slotRoot> as the parent for isolated worker slots.
            Path parentDir = selfDir.getParent();
            if (JVM_LEAF_CGROUP_NAME.equals(selfDir.getFileName().toString())
                    && parentDir != null
                    && hasToken(parentDir.resolve("cgroup.subtree_control"), "cpuset")
                    && Files.isWritable(parentDir)) {
                return parentDir;
            }

            Path controllersFile = selfDir.resolve("cgroup.controllers");
            if (!hasToken(controllersFile, "cpuset")) {
                throw new IllegalStateException(
                        "Strict local CPU isolation requires delegated 'cpuset' controller in "
                                + controllersFile
                                + " (available controllers: '"
                                + readTrimmedOrEmpty(controllersFile)
                                + "'). On systemd/container hosts delegate 'Delegate=yes' / "
                                + "cpuset controller, or set STAREXEC_LOCAL_ISOLATION_MODE=permissive.");
            }

            if (!Files.isWritable(selfDir)
                    || !Files.isWritable(selfDir.resolve("cgroup.subtree_control"))) {
                throw new IllegalStateException(
                        "Strict local CPU isolation requires writable cgroup directory and "
                                + "cgroup.subtree_control at "
                                + selfDir);
            }

            Path subtreeControl = selfDir.resolve("cgroup.subtree_control");
            if (!hasToken(subtreeControl, "cpuset")) {
                // Linux cgroups v2 "no internal processes" rule: move live processes from selfDir
                // into a dedicated leaf before enabling +cpuset in selfDir/cgroup.subtree_control.
                try {
                    moveSelfProcessesToJvmLeaf(selfDir);
                    Files.writeString(subtreeControl, "+cpuset\n", StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "Failed to delegate '+cpuset' in "
                                    + subtreeControl
                                    + ": "
                                    + e.getMessage(),
                            e);
                }
            }

            if (!hasToken(subtreeControl, "cpuset")) {
                throw new IllegalStateException(
                        "cpuset remained disabled in "
                                + subtreeControl
                                + " after delegation attempt");
            }
            return selfDir;
        }

        private void moveSelfProcessesToJvmLeaf(Path selfDir) throws IOException {
            Path selfProcs = selfDir.resolve("cgroup.procs");
            if (!Files.exists(selfProcs)) {
                return;
            }
            List<String> pids = Files.readAllLines(selfProcs, StandardCharsets.UTF_8);
            boolean hasLive = false;
            for (String p : pids) {
                if (!p.trim().isEmpty()) {
                    hasLive = true;
                    break;
                }
            }
            if (!hasLive) {
                return;
            }
            Path jvmLeaf = selfDir.resolve(JVM_LEAF_CGROUP_NAME);
            if (!Files.exists(jvmLeaf)) {
                Files.createDirectory(jvmLeaf);
            }
            Path leafProcs = jvmLeaf.resolve("cgroup.procs");
            for (String line : pids) {
                String pidStr = line.trim();
                if (!pidStr.isEmpty()) {
                    try {
                        Files.writeString(leafProcs, pidStr + "\n", StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        long pid = Long.parseLong(pidStr);
                        if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                            throw e;
                        }
                    }
                }
            }
        }

        private void createIsolatedChildCgroup(
                Path slotRoot, Path childDir, CoreTopology topology) throws IOException {
            Files.createDirectory(childDir);
            boolean success = false;
            try {
                String mems = resolveEffectiveMems(slotRoot);
                if (!mems.isEmpty()) {
                    Files.writeString(
                            childDir.resolve("cpuset.mems"), mems + "\n", StandardCharsets.UTF_8);
                }
                Files.writeString(
                        childDir.resolve("cpuset.cpus"),
                        topology.getSiblingCpuset() + "\n",
                        StandardCharsets.UTF_8);
                Files.writeString(
                        childDir.resolve("cpuset.cpus.partition"),
                        "isolated\n",
                        StandardCharsets.UTF_8);

                String actualPartition =
                        Files.readString(
                                        childDir.resolve("cpuset.cpus.partition"),
                                        StandardCharsets.UTF_8)
                                .trim();
                if (!"isolated".equals(actualPartition)) {
                    throw new IOException(
                            "Expected cpuset.cpus.partition=isolated in "
                                    + childDir
                                    + " but kernel reported '"
                                    + actualPartition
                                    + "'");
                }
                String actualEffective =
                        Files.readString(
                                        childDir.resolve("cpuset.cpus.effective"),
                                        StandardCharsets.UTF_8)
                                .trim();
                List<Integer> effectiveList =
                        actualEffective.isEmpty()
                                ? Collections.emptyList()
                                : CpuPartitionManager.expandCpuset(actualEffective);
                if (!effectiveList.containsAll(topology.getSiblingCpus())
                        || !topology.getSiblingCpus().containsAll(effectiveList)) {
                    throw new IOException(
                            "Expected cpuset.cpus.effective="
                                    + topology.getSiblingCpuset()
                                    + " in "
                                    + childDir
                                    + " but kernel reported '"
                                    + actualEffective
                                    + "'");
                }
                success = true;
            } finally {
                if (!success) {
                    deleteIfEmptyDir(childDir);
                }
            }
        }

        private String resolveEffectiveMems(Path dir) throws IOException {
            Path eff = dir.resolve("cpuset.mems.effective");
            if (Files.exists(eff)) {
                String val = Files.readString(eff, StandardCharsets.UTF_8).trim();
                if (!val.isEmpty()) {
                    return val;
                }
            }
            Path mems = dir.resolve("cpuset.mems");
            if (Files.exists(mems)) {
                return Files.readString(mems, StandardCharsets.UTF_8).trim();
            }
            return "";
        }

        private String readSelfV2RelativePath() {
            try {
                String content = Files.readString(procSelfCgroupPath, StandardCharsets.UTF_8);
                String rel = parseV2CgroupRelativePath(content);
                if (rel == null) {
                    throw new IllegalStateException(
                            "No cgroups v2 unified hierarchy ('0::...') entry found in "
                                    + procSelfCgroupPath);
                }
                return rel;
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to read " + procSelfCgroupPath + ": " + e.getMessage(), e);
            }
        }

        static String parseV2CgroupRelativePath(String procCgroupContent) {
            if (procCgroupContent == null) {
                return null;
            }
            for (String line : procCgroupContent.split("\\R")) {
                if (line.startsWith("0::")) {
                    String path = line.substring(3).trim();
                    return path.isEmpty() ? "/" : path;
                }
            }
            return null;
        }

        private String toCgroupRelativePath(Path dir) {
            Path normRoot = cgroupRoot.toAbsolutePath().normalize();
            Path normDir = dir.toAbsolutePath().normalize();
            if (normDir.equals(normRoot)) {
                return "/";
            }
            if (normDir.startsWith(normRoot)) {
                return "/" + normRoot.relativize(normDir).toString().replace('\\', '/');
            }
            return normDir.toString();
        }

        private static boolean hasToken(Path file, String token) {
            String raw = readTrimmedOrEmpty(file);
            if (raw.isEmpty()) {
                return false;
            }
            for (String part : raw.split("\\s+")) {
                if (token.equals(part)) {
                    return true;
                }
            }
            return false;
        }

        private static String readTrimmedOrEmpty(Path file) {
            if (!Files.exists(file)) {
                return "";
            }
            try {
                return Files.readString(file, StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                return "";
            }
        }

        private static void deleteIfEmptyDir(Path dir) {
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            try {
                Files.deleteIfExists(dir);
            } catch (IOException ignored) {
                // Best-effort cleanup of empty directory
            }
        }
    }

    /**
     * Reads sysfs topology for each configured CPU. In {@link IsolationMode#STRICT}, incomplete or
     * unreadable topology fails closed immediately; in {@link IsolationMode#PERMISSIVE}, missing
     * sysfs files produce best-effort partial metadata.
     */
    public static Map<Integer, CoreTopology> readTopologies(
            List<Integer> configuredCores, TopologyReader topologyReader, IsolationMode mode) {
        Map<Integer, CoreTopology> result = new LinkedHashMap<>();
        for (int cpu : configuredCores) {
            try {
                CoreTopology topology = topologyReader.readCoreTopology(cpu);
                if (topology == null) {
                    topology = new CoreTopology(cpu, null, null, Collections.emptyList());
                }
                result.put(cpu, topology);
            } catch (LocalCpuAttestation.CpuStateReadException e) {
                if (mode == IsolationMode.STRICT) {
                    throw new IllegalStateException(
                            "Strict local CPU isolation failed reading sysfs topology for CPU "
                                    + cpu
                                    + ": "
                                    + e.getMessage(),
                            e);
                }
                log.debug("Permissive mode continuing without topology for CPU " + cpu + ": " + e);
                result.put(cpu, new CoreTopology(cpu, null, null, Collections.emptyList()));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Fail-closed startup verification for {@link IsolationMode#STRICT}. Ensures complete sysfs
     * topology, full SMT sibling online & affinity availability, distinct physical sibling groups,
     * and working kernel cgroup v2 {@code cpuset} {@code isolated} partition delegation.
     */
    public static void validateAndPrepareStrictMode(
            List<Integer> configuredCores,
            Map<Integer, CoreTopology> topologies,
            List<Integer> effectiveAffinity,
            List<Integer> onlineCpus,
            CgroupCpusetController cgroupController) {
        if (configuredCores == null || configuredCores.isEmpty()) {
            throw new IllegalStateException(
                    "Strict local CPU isolation requires at least one configured CPU core");
        }
        Set<Integer> onlineSet = new LinkedHashSet<>(onlineCpus);
        Set<Integer> affinitySet = new LinkedHashSet<>(effectiveAffinity);
        Set<String> seenSiblingGroups = new LinkedHashSet<>();

        for (int cpu : configuredCores) {
            CoreTopology topology = topologies.get(cpu);
            if (topology == null || !topology.isCompleteStrictTopology()) {
                throw new IllegalStateException(
                        "Strict local CPU isolation requires complete sysfs topology "
                                + "(core_id, physical_package_id, thread_siblings_list) for CPU "
                                + cpu);
            }
            for (int sibling : topology.getSiblingCpus()) {
                if (!onlineSet.contains(sibling)) {
                    throw new IllegalStateException(
                            "Strict local CPU isolation requires all SMT siblings of leased CPU "
                                    + cpu
                                    + " ("
                                    + topology.getSiblingCpuset()
                                    + ") to be online, but sibling "
                                    + sibling
                                    + " is offline");
                }
                if (!affinitySet.contains(sibling)) {
                    throw new IllegalStateException(
                            "Strict local CPU isolation requires all SMT siblings of leased CPU "
                                    + cpu
                                    + " ("
                                    + topology.getSiblingCpuset()
                                    + ") to be present in effective CPU affinity "
                                    + CpuPartitionManager.compressCpuset(effectiveAffinity)
                                    + ", but sibling "
                                    + sibling
                                    + " is excluded");
                }
            }
            if (!seenSiblingGroups.add(topology.getSiblingCpuset())) {
                throw new IllegalStateException(
                        "Strict local CPU isolation prohibits leasing multiple logical CPUs "
                                + "from sibling group "
                                + topology.getSiblingCpuset());
            }
        }

        cgroupController.verifyStrictHostSupport(
                configuredCores, topologies, effectiveAffinity, onlineCpus);
    }
}
