package org.starexec.backend;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import org.starexec.logger.StarLogger;

/**
 * Runtime and ingestion-boundary CPU allocation attestation for {@link LocalBackend} (#295).
 *
 * <p>Prevents silent scientific corruption when {@code cpuset.cpus.effective}, process
 * {@code Cpus_allowed_list}, or CPU online state changes after a job's lease is validated or
 * while the solver is running, without conflating transient {@code /proc} or {@code sysfs}
 * read failures with permanent CPU loss.</p>
 */
public final class LocalCpuAttestation {

    private static final StarLogger log = StarLogger.getLogger(LocalCpuAttestation.class);

    static final Path DEFAULT_PROC_SELF_STATUS = Path.of("/proc/self/status");
    static final Path DEFAULT_CPU_ONLINE = Path.of("/sys/devices/system/cpu/online");
    static final Path DEFAULT_CGROUP_CPUSET_EFFECTIVE = Path.of(
        "/sys/fs/cgroup/cpuset.cpus.effective"
    );
    static final Path DEFAULT_PROC_ROOT = Path.of("/proc");

    private LocalCpuAttestation() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Abstraction over host CPU/cpuset files for deterministic unit testing. */
    public interface CpuEnvironmentReader {
        List<Integer> readEffectiveAffinity() throws CpuStateReadException;

        List<Integer> readOnlineCpus() throws CpuStateReadException;

        Optional<List<Integer>> readCgroupEffectiveCpuset() throws CpuStateReadException;

        Optional<List<Integer>> readProcessAllowedCpus(long pid) throws CpuStateReadException;

        default SystemSnapshot readSystemSnapshot() throws CpuStateReadException {
            List<Integer> affinity = readEffectiveAffinity();
            List<Integer> online = readOnlineCpus();
            Optional<List<Integer>> cgroup = readCgroupEffectiveCpuset();
            return new SystemSnapshot(affinity, online, cgroup.orElse(null));
        }
    }

    /** Transient failure reading global or per-process CPU state from {@code /proc} or sysfs. */
    public static final class CpuStateReadException extends Exception {
        private static final long serialVersionUID = 1L;

        public CpuStateReadException(String message) {
            super(message);
        }

        public CpuStateReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Deterministic violation of the CPU allocation invariant; blocks result ingestion. */
    public static final class CpuAllocationViolationException extends Exception {
        private static final long serialVersionUID = 1L;

        private final CpuAttestationRecord record;

        public CpuAllocationViolationException(String message, CpuAttestationRecord record) {
            super(message);
            this.record = record;
        }

        public CpuAttestationRecord getRecord() {
            return record;
        }
    }

    /** Consistent point-in-time observation of kernel CPU scheduling constraints. */
    public static final class SystemSnapshot {
        private final Set<Integer> effectiveAffinityCpus;
        private final Set<Integer> onlineCpus;
        private final Set<Integer> cgroupEffectiveCpus;
        private final String effectiveAffinityStr;
        private final String onlineStr;
        private final String cgroupEffectiveStr;
        private final String combinedEffectiveStr;

        public SystemSnapshot(
                List<Integer> effectiveAffinity,
                List<Integer> onlineCpus,
                List<Integer> cgroupEffectiveCpus) {
            if (effectiveAffinity == null || effectiveAffinity.isEmpty()) {
                throw new IllegalArgumentException("effectiveAffinity must not be empty");
            }
            if (onlineCpus == null || onlineCpus.isEmpty()) {
                throw new IllegalArgumentException("onlineCpus must not be empty");
            }
            this.effectiveAffinityCpus = Collections.unmodifiableSet(
                new LinkedHashSet<>(effectiveAffinity)
            );
            this.onlineCpus = Collections.unmodifiableSet(new LinkedHashSet<>(onlineCpus));
            this.effectiveAffinityStr = CpuPartitionManager.compressCpuset(effectiveAffinity);
            this.onlineStr = CpuPartitionManager.compressCpuset(onlineCpus);
            if (cgroupEffectiveCpus != null && !cgroupEffectiveCpus.isEmpty()) {
                this.cgroupEffectiveCpus = Collections.unmodifiableSet(
                    new LinkedHashSet<>(cgroupEffectiveCpus)
                );
                this.cgroupEffectiveStr = CpuPartitionManager.compressCpuset(cgroupEffectiveCpus);
                Set<Integer> intersection = new LinkedHashSet<>(this.effectiveAffinityCpus);
                intersection.retainAll(this.cgroupEffectiveCpus);
                this.combinedEffectiveStr = intersection.isEmpty()
                    ? ""
                    : CpuPartitionManager.compressCpuset(List.copyOf(intersection));
            } else {
                this.cgroupEffectiveCpus = null;
                this.cgroupEffectiveStr = null;
                this.combinedEffectiveStr = this.effectiveAffinityStr;
            }
        }

        public boolean isCpuValid(int cpu) {
            if (!onlineCpus.contains(cpu) || !effectiveAffinityCpus.contains(cpu)) {
                return false;
            }
            return cgroupEffectiveCpus == null || cgroupEffectiveCpus.contains(cpu);
        }

        public boolean hasCgroupCpuset() {
            return cgroupEffectiveCpus != null;
        }

        public String getEffectiveAffinityStr() {
            return effectiveAffinityStr;
        }

        public String getOnlineStr() {
            return onlineStr;
        }

        public String getCgroupEffectiveStr() {
            return cgroupEffectiveStr;
        }

        public String getCombinedEffectiveStr() {
            return combinedEffectiveStr;
        }
    }

    /** Linux {@code /proc} and {@code /sys} implementation of {@link CpuEnvironmentReader}. */
    public static final class LinuxCpuEnvironmentReader implements CpuEnvironmentReader {
        public static final LinuxCpuEnvironmentReader DEFAULT = new LinuxCpuEnvironmentReader(
            DEFAULT_PROC_SELF_STATUS,
            DEFAULT_CPU_ONLINE,
            DEFAULT_CGROUP_CPUSET_EFFECTIVE,
            DEFAULT_PROC_ROOT
        );

        private final Path procSelfStatusPath;
        private final Path cpuOnlinePath;
        private final Path cgroupCpusetEffectivePath;
        private final Path procRoot;

        public LinuxCpuEnvironmentReader(
                Path procSelfStatusPath,
                Path cpuOnlinePath,
                Path cgroupCpusetEffectivePath,
                Path procRoot) {
            this.procSelfStatusPath = Objects.requireNonNull(
                procSelfStatusPath,
                "procSelfStatusPath"
            );
            this.cpuOnlinePath = Objects.requireNonNull(cpuOnlinePath, "cpuOnlinePath");
            this.cgroupCpusetEffectivePath = cgroupCpusetEffectivePath;
            this.procRoot = Objects.requireNonNull(procRoot, "procRoot");
        }

        @Override
        public List<Integer> readEffectiveAffinity() throws CpuStateReadException {
            return readCpusAllowedList(procSelfStatusPath, "effective CPU affinity");
        }

        @Override
        public List<Integer> readOnlineCpus() throws CpuStateReadException {
            return readCpusetFile(cpuOnlinePath, "online CPU list");
        }

        @Override
        public Optional<List<Integer>> readCgroupEffectiveCpuset() throws CpuStateReadException {
            if (cgroupCpusetEffectivePath == null || !Files.exists(cgroupCpusetEffectivePath)) {
                return Optional.empty();
            }
            return Optional.of(readCpusetFile(cgroupCpusetEffectivePath, "cgroup effective cpuset"));
        }

        @Override
        public Optional<List<Integer>> readProcessAllowedCpus(long pid)
                throws CpuStateReadException {
            if (pid <= 0) {
                return Optional.empty();
            }
            Path statusPath = procRoot.resolve(Long.toString(pid)).resolve("status");
            try {
                return Optional.of(readCpusAllowedList(statusPath, "process " + pid + " affinity"));
            } catch (CpuStateReadException e) {
                if (hasCause(e, NoSuchFileException.class)) {
                    return Optional.empty();
                }
                throw e;
            }
        }

        private static boolean hasCause(Throwable failure, Class<? extends Throwable> expected) {
            for (Throwable t = failure; t != null; t = t.getCause()) {
                if (expected.isInstance(t)) {
                    return true;
                }
            }
            return false;
        }

        private static List<Integer> readCpusetFile(Path path, String description)
                throws CpuStateReadException {
            try {
                String content = Files.readString(path).trim();
                if (content.isEmpty()) {
                    throw new CpuStateReadException(
                        "Cannot read " + description + ": " + path + " is empty"
                    );
                }
                return Collections.unmodifiableList(CpuPartitionManager.expandCpuset(content));
            } catch (IOException | IllegalArgumentException e) {
                throw new CpuStateReadException(
                    "Cannot read " + description + " from " + path + ": " + e.getMessage(),
                    e
                );
            }
        }

        private static List<Integer> readCpusAllowedList(Path statusPath, String description)
                throws CpuStateReadException {
            final String field = "Cpus_allowed_list:";
            try (BufferedReader reader = Files.newBufferedReader(statusPath)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith(field)) {
                        String cpuset = line.substring(field.length()).trim();
                        if (cpuset.isEmpty()) {
                            throw new CpuStateReadException(
                                "Cannot read " + description + ": empty Cpus_allowed_list in "
                                    + statusPath
                            );
                        }
                        return Collections.unmodifiableList(
                            CpuPartitionManager.expandCpuset(cpuset)
                        );
                    }
                }
            } catch (IOException | IllegalArgumentException e) {
                throw new CpuStateReadException(
                    "Cannot read " + description + " from " + statusPath + ": " + e.getMessage(),
                    e
                );
            }
            throw new CpuStateReadException(
                "Cannot read " + description + ": " + statusPath + " lacks Cpus_allowed_list"
            );
        }
    }

    /**
     * Per-attempt CPU invariant tracker shared between {@link LocalBackend} and
     * {@link LocalJobMonitor}.
     */
    public static final class AttemptTracker {
        static final long DEFAULT_PROCESS_STARTUP_GRACE_NANOS = 250_000_000L;

        private final int pairId;
        private final Integer attemptNo;
        private final String nodeName;
        private final String isolationMode;
        private final int leasedCore;
        private final SystemSnapshot initialSnapshot;
        private final CpuEnvironmentReader reader;
        private final LongSupplier nanoTime;
        private final long launchNanos;
        private final long processStartupGraceNanos;
        private final String topologySummary;

        private SystemSnapshot lastSnapshot;
        private String observedProcessCpuset;
        private boolean processAffinityConfirmed;
        private String latchedViolationReason;

        public AttemptTracker(
                int pairId,
                Integer attemptNo,
                String nodeName,
                String isolationMode,
                int leasedCore,
                SystemSnapshot initialSnapshot,
                CpuEnvironmentReader reader) {
            this(
                pairId,
                attemptNo,
                nodeName,
                isolationMode,
                leasedCore,
                initialSnapshot,
                reader,
                System::nanoTime,
                DEFAULT_PROCESS_STARTUP_GRACE_NANOS
            );
        }

        public AttemptTracker(
                int pairId,
                Integer attemptNo,
                String nodeName,
                String isolationMode,
                int leasedCore,
                SystemSnapshot initialSnapshot,
                CpuEnvironmentReader reader,
                LongSupplier nanoTime,
                long processStartupGraceNanos) {
            this.pairId = pairId;
            this.attemptNo = attemptNo;
            this.nodeName = nodeName;
            this.isolationMode = isolationMode;
            this.leasedCore = leasedCore;
            this.initialSnapshot = Objects.requireNonNull(initialSnapshot, "initialSnapshot");
            this.lastSnapshot = initialSnapshot;
            this.reader = Objects.requireNonNull(reader, "reader");
            this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
            this.launchNanos = this.nanoTime.getAsLong();
            this.processStartupGraceNanos = Math.max(0L, processStartupGraceNanos);
            this.topologySummary =
                "online=" + initialSnapshot.getOnlineStr()
                    + ";affinity=" + initialSnapshot.getEffectiveAffinityStr()
                    + (initialSnapshot.getCgroupEffectiveStr() != null
                        ? ";cgroup_cpuset=" + initialSnapshot.getCgroupEffectiveStr()
                        : "");
        }

        public int getLeasedCore() {
            return leasedCore;
        }

        public synchronized boolean isViolated() {
            return latchedViolationReason != null;
        }

        /**
         * Observes the global CPU state and child process affinity during execution.
         * Transient read errors do not trigger false violations or evict the lease.
         */
        public synchronized void verifyRuntime(long pid) {
            try {
                SystemSnapshot current = reader.readSystemSnapshot();
                lastSnapshot = current;
                String globalViolation = checkGlobalInvariant(current);
                if (globalViolation != null) {
                    latchViolation(globalViolation);
                }
            } catch (CpuStateReadException e) {
                log.debug(
                    "Transient CPU state read error during runtime attestation for pair "
                        + pairId + " on CPU " + leasedCore + ": " + e.getMessage()
                );
            }

            if (pid > 0) {
                try {
                    Optional<List<Integer>> procCpus = reader.readProcessAllowedCpus(pid);
                    if (procCpus.isPresent()) {
                        List<Integer> allowed = procCpus.get();
                        String compressed = CpuPartitionManager.compressCpuset(allowed);
                        if (allowed.size() == 1 && allowed.contains(leasedCore)) {
                            processAffinityConfirmed = true;
                            observedProcessCpuset = compressed;
                        } else if (processAffinityConfirmed
                                || (nanoTime.getAsLong() - launchNanos)
                                    >= processStartupGraceNanos) {
                            observedProcessCpuset = compressed;
                            latchViolation(
                                "Process PID " + pid + " observed Cpus_allowed_list="
                                    + compressed + " violates single leased CPU " + leasedCore
                            );
                        }
                    }
                } catch (CpuStateReadException e) {
                    log.debug(
                        "Transient process affinity read error for PID " + pid + " (pair "
                            + pairId + "): " + e.getMessage()
                    );
                }
            }
        }

        /**
         * Checks whether the leased CPU is specifically invalidated in the global environment.
         * Returns {@code true} when valid OR when reading state failed transiently so capacity
         * is never discarded on transient read errors.
         */
        public boolean isLeasedCoreGloballyValidOnRelease() {
            try {
                SystemSnapshot current = reader.readSystemSnapshot();
                synchronized (this) {
                    lastSnapshot = current;
                }
                return current.isCpuValid(leasedCore);
            } catch (CpuStateReadException e) {
                log.warn(
                    "Transient error reading CPU state when releasing lease " + leasedCore
                        + "; retaining lease capacity: " + e.getMessage()
                );
                return true;
            }
        }

        /**
         * Verifies the CPU invariant at the {@link LocalJobMonitor} ingestion boundary.
         *
         * @return an {@code ATTESTED} record (or {@code null} when {@code pairId <= 0} or
         *     {@code attemptNo == null})
         * @throws CpuAllocationViolationException if an allocation change occurred at any point
         *     from launch through ingestion
         * @throws CpuStateReadException if global CPU state cannot be read transiently at the
         *     ingestion boundary (caller retries with backoff)
         */
        public synchronized CpuAttestationRecord verifyForIngestion()
                throws CpuAllocationViolationException, CpuStateReadException {
            if (latchedViolationReason != null) {
                throw new CpuAllocationViolationException(
                    latchedViolationReason,
                    buildRecord(CpuAttestationRecord.Status.VIOLATED, latchedViolationReason)
                );
            }

            SystemSnapshot terminal = reader.readSystemSnapshot();
            lastSnapshot = terminal;

            String violation = checkGlobalInvariant(terminal);
            if (violation == null && observedProcessCpuset != null) {
                List<Integer> parsed = CpuPartitionManager.expandCpuset(observedProcessCpuset);
                if (parsed.size() != 1 || !parsed.contains(leasedCore)) {
                    violation =
                        "Observed process Cpus_allowed_list=" + observedProcessCpuset
                            + " does not match single leased CPU " + leasedCore;
                }
            }
            if (violation != null) {
                latchViolation(violation);
                throw new CpuAllocationViolationException(
                    violation,
                    buildRecord(CpuAttestationRecord.Status.VIOLATED, violation)
                );
            }

            return buildRecord(CpuAttestationRecord.Status.ATTESTED, null);
        }

        private String checkGlobalInvariant(SystemSnapshot snapshot) {
            if (!initialSnapshot.isCpuValid(leasedCore)) {
                return "Initial snapshot did not permit leased CPU " + leasedCore;
            }
            if (initialSnapshot.hasCgroupCpuset() && !snapshot.hasCgroupCpuset()) {
                return "cgroup cpuset.cpus.effective became unavailable while running on CPU "
                    + leasedCore;
            }
            if (!snapshot.isCpuValid(leasedCore)) {
                return "Leased CPU " + leasedCore + " is no longer valid in runtime CPU state "
                    + "(online=" + snapshot.getOnlineStr()
                    + ", affinity=" + snapshot.getEffectiveAffinityStr()
                    + (snapshot.getCgroupEffectiveStr() != null
                        ? ", cpuset=" + snapshot.getCgroupEffectiveStr()
                        : "")
                    + ")";
            }
            return null;
        }

        private void latchViolation(String reason) {
            if (latchedViolationReason == null) {
                latchedViolationReason = reason;
                log.error(
                    "CPU allocation invariant violated for pair " + pairId
                        + " (attempt " + attemptNo + ", leased CPU " + leasedCore + "): " + reason
                );
            }
        }

        private CpuAttestationRecord buildRecord(
                CpuAttestationRecord.Status status,
                String violationReason) {
            if (pairId <= 0 || attemptNo == null || attemptNo < 1) {
                return null;
            }
            return new CpuAttestationRecord(
                pairId,
                attemptNo,
                "local",
                nodeName,
                isolationMode,
                Integer.toString(leasedCore),
                initialSnapshot.getCombinedEffectiveStr(),
                lastSnapshot != null ? lastSnapshot.getCombinedEffectiveStr() : null,
                observedProcessCpuset,
                topologySummary,
                status,
                violationReason
            );
        }
    }
}
