package org.starexec.backend;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.starexec.logger.StarLogger;

/**
 * Validates and persists per-attempt physical CPU core attestation for Kubernetes jobs (#286).
 *
 * <p>Reads the pod-generated {@code cpu-topology.json} artifact from the attempt output directory,
 * verifies whether an integer Guaranteed CPU request received an exclusive whole-physical-core
 * {@code cpuset} (such as from kubelet {@code cpuManagerPolicy=static} +
 * {@code full-pcpus-only=true}), and produces a {@link CpuAttestationRecord} compatible with
 * {@code V0138}/{@code V0139}. Non-migrated or unverified clusters are persisted as
 * {@code UNQUALIFIED} ({@code physical_exclusivity_attested=false}) with a concrete diagnostic
 * rather than blocking scientific ingestion.
 */
public final class KubernetesCpuAttestation {

    private static final StarLogger log = StarLogger.getLogger(KubernetesCpuAttestation.class);
    private static final Gson GSON = new Gson();
    public static final String TOPOLOGY_FILENAME = "cpu-topology.json";
    public static final String MECHANISM_STATIC = "k8s-cpuset-static";
    public static final String MECHANISM_SHARED = "k8s-shared";

    private KubernetesCpuAttestation() {}

    /** Gson model for {@code cpu-topology.json}. */
    static final class CpuTopologyArtifact {
        Integer pairId;
        String nodeName;
        String cpusAllowedList;
        String cgroupEffectiveCpuset;
        String cgroupHierarchy;
        String onlineCpus;
        String numaNodes;
        List<CpuEntry> cpus;
    }

    /** Per-CPU sysfs topology entry inside {@code cpu-topology.json}. */
    static final class CpuEntry {
        Integer cpu;
        Integer coreId;
        Integer packageId;
        Integer numaNode;
        String threadSiblings;
    }

    /**
     * Evaluates the Kubernetes attempt's {@code cpu-topology.json} artifact against
     * {@code strictOnePairPerCpu} and {@code cpuLimit} and returns a {@link CpuAttestationRecord}
     * (or {@code null} when {@code pairId <= 0} or {@code attemptNo == null || attemptNo < 1}).
     */
    public static CpuAttestationRecord evaluate(
            Path outputDir,
            int pairId,
            Integer attemptNo,
            String fallbackNodeName,
            boolean strictOnePairPerCpu,
            String cpuLimit) {
        if (pairId <= 0 || attemptNo == null || attemptNo < 1) {
            return null;
        }

        CpuTopologyArtifact artifact = null;
        String parseError = null;
        Path topologyPath = outputDir != null ? outputDir.resolve(TOPOLOGY_FILENAME) : null;
        if (topologyPath != null && Files.exists(topologyPath)) {
            try {
                String rawJson = Files.readString(topologyPath, StandardCharsets.UTF_8);
                artifact = GSON.fromJson(rawJson, CpuTopologyArtifact.class);
                if (artifact == null) {
                    parseError = "Empty " + TOPOLOGY_FILENAME + " artifact";
                }
            } catch (IOException | JsonParseException e) {
                parseError = "Malformed " + TOPOLOGY_FILENAME + " artifact: " + e.getMessage();
            }
        }

        String nodeName = resolveNodeName(artifact, fallbackNodeName);
        String allowedRaw =
                artifact != null && artifact.cpusAllowedList != null
                        ? artifact.cpusAllowedList.trim()
                        : null;
        String cgroupRaw =
                artifact != null && artifact.cgroupEffectiveCpuset != null
                        ? artifact.cgroupEffectiveCpuset.trim()
                        : null;
        String effectiveRaw =
                cgroupRaw != null && !cgroupRaw.isEmpty() ? cgroupRaw : allowedRaw;

        TopologyMetadata topoMeta = extractMetadata(artifact);

        if (!strictOnePairPerCpu) {
            return new CpuAttestationRecord(
                    pairId,
                    attemptNo,
                    "kubernetes",
                    nodeName,
                    "permissive",
                    MECHANISM_SHARED,
                    false,
                    allowedRaw,
                    null,
                    effectiveRaw,
                    effectiveRaw,
                    allowedRaw,
                    topoMeta.singleCoreId,
                    topoMeta.singlePackageId,
                    topoMeta.threadSiblingsSummary,
                    topoMeta.summary,
                    CpuAttestationRecord.Status.UNQUALIFIED,
                    "STAREXEC_K8S_STRICT_ONE_PAIR_PER_CPU is disabled");
        }

        long expectedMillis = KubernetesNativeBackend.cpuQuantityToMillis(cpuLimit);
        if (expectedMillis <= 0 || expectedMillis % 1000L != 0L) {
            return buildUnqualifiedStrict(
                    pairId,
                    attemptNo,
                    nodeName,
                    allowedRaw,
                    effectiveRaw,
                    topoMeta,
                    "Non-integer STAREXEC_K8S_CPU_LIMIT='"
                            + cpuLimit
                            + "' cannot obtain exclusive physical cores");
        }
        int expectedCpuCount = (int) (expectedMillis / 1000L);

        if (topologyPath == null || !Files.exists(topologyPath)) {
            return buildUnqualifiedStrict(
                    pairId,
                    attemptNo,
                    nodeName,
                    allowedRaw,
                    effectiveRaw,
                    topoMeta,
                    "Missing " + TOPOLOGY_FILENAME + " artifact in attempt output directory");
        }
        if (parseError != null) {
            return buildUnqualifiedStrict(
                    pairId,
                    attemptNo,
                    nodeName,
                    allowedRaw,
                    effectiveRaw,
                    topoMeta,
                    parseError);
        }

        List<Integer> allowedCpus = parseCpusetSafe(allowedRaw);
        if (allowedCpus.isEmpty()) {
            return buildUnqualifiedStrict(
                    pairId,
                    attemptNo,
                    nodeName,
                    allowedRaw,
                    effectiveRaw,
                    topoMeta,
                    TOPOLOGY_FILENAME + " lacks valid cpusAllowedList");
        }
        String allowedCompressed = CpuPartitionManager.compressCpuset(allowedCpus);

        List<Integer> cgroupCpus = parseCpusetSafe(cgroupRaw);
        if (!cgroupCpus.isEmpty() && !sameCpuSet(allowedCpus, cgroupCpus)) {
            String cgroupCompressed = CpuPartitionManager.compressCpuset(cgroupCpus);
            return buildUnqualifiedStrict(
                    pairId,
                    attemptNo,
                    nodeName,
                    allowedCompressed,
                    cgroupCompressed,
                    topoMeta,
                    "Pod Cpus_allowed_list ("
                            + allowedCompressed
                            + ") does not match container cgroup cpuset ("
                            + cgroupCompressed
                            + ")");
        }

        if (allowedCpus.size() != expectedCpuCount) {
            return buildUnqualifiedStrict(
                    pairId,
                    attemptNo,
                    nodeName,
                    allowedCompressed,
                    effectiveRaw != null ? effectiveRaw : allowedCompressed,
                    topoMeta,
                    "Pod observed "
                            + allowedCpus.size()
                            + " logical CPU(s) ("
                            + allowedCompressed
                            + ") for integer cpuLimit="
                            + expectedCpuCount
                            + "; worker kubelet likely runs cpuManagerPolicy=none or shared cpuset");
        }

        List<Integer> onlineCpus = parseCpusetSafe(artifact.onlineCpus);
        Set<Integer> onlineSet = new LinkedHashSet<>(onlineCpus);
        if (!onlineSet.isEmpty() && allowedCpus.size() >= onlineSet.size()) {
            return buildUnqualifiedStrict(
                    pairId,
                    attemptNo,
                    nodeName,
                    allowedCompressed,
                    effectiveRaw != null ? effectiveRaw : allowedCompressed,
                    topoMeta,
                    "Pod cpuset ("
                            + allowedCompressed
                            + ") spans all online node CPUs ("
                            + CpuPartitionManager.compressCpuset(onlineCpus)
                            + ") without reserved system CPUs; cannot attest exclusive static allocation");
        }

        Map<Integer, CpuEntry> byCpu = new LinkedHashMap<>();
        if (artifact.cpus != null) {
            for (CpuEntry entry : artifact.cpus) {
                if (entry != null && entry.cpu != null) {
                    byCpu.put(entry.cpu, entry);
                }
            }
        }

        Set<Integer> allowedSet = new LinkedHashSet<>(allowedCpus);
        for (int cpu : allowedCpus) {
            CpuEntry entry = byCpu.get(cpu);
            if (entry == null
                    || entry.coreId == null
                    || entry.packageId == null
                    || entry.threadSiblings == null
                    || entry.threadSiblings.trim().isEmpty()) {
                return buildUnqualifiedStrict(
                        pairId,
                        attemptNo,
                        nodeName,
                        allowedCompressed,
                        effectiveRaw != null ? effectiveRaw : allowedCompressed,
                        topoMeta,
                        "Incomplete sysfs topology in " + TOPOLOGY_FILENAME + " for CPU " + cpu);
            }
            List<Integer> siblings = parseCpusetSafe(entry.threadSiblings);
            if (siblings.isEmpty() || !siblings.contains(cpu)) {
                return buildUnqualifiedStrict(
                        pairId,
                        attemptNo,
                        nodeName,
                        allowedCompressed,
                        effectiveRaw != null ? effectiveRaw : allowedCompressed,
                        topoMeta,
                        "Inconsistent thread_siblings_list '"
                                + entry.threadSiblings
                                + "' for CPU "
                                + cpu);
            }
            List<Integer> missingSiblings = new ArrayList<>();
            for (int sibling : siblings) {
                if (!onlineSet.isEmpty() && !onlineSet.contains(sibling)) {
                    continue;
                }
                if (!allowedSet.contains(sibling)) {
                    missingSiblings.add(sibling);
                }
            }
            if (!missingSiblings.isEmpty()) {
                return buildUnqualifiedStrict(
                        pairId,
                        attemptNo,
                        nodeName,
                        allowedCompressed,
                        effectiveRaw != null ? effectiveRaw : allowedCompressed,
                        topoMeta,
                        "Partial SMT core allocation: CPU "
                                + cpu
                                + " assigned without sibling(s) "
                                + CpuPartitionManager.compressCpuset(missingSiblings)
                                + " (siblings="
                                + entry.threadSiblings
                                + "); enable kubelet full-pcpus-only=true or disable SMT");
            }
        }

        String attestedEffective =
                effectiveRaw != null && !effectiveRaw.isEmpty()
                        ? effectiveRaw
                        : allowedCompressed;
        return new CpuAttestationRecord(
                pairId,
                attemptNo,
                "kubernetes",
                nodeName,
                "strict",
                MECHANISM_STATIC,
                true,
                allowedCompressed,
                allowedCompressed,
                attestedEffective,
                attestedEffective,
                allowedCompressed,
                topoMeta.singleCoreId,
                topoMeta.singlePackageId,
                topoMeta.threadSiblingsSummary,
                topoMeta.summary,
                CpuAttestationRecord.Status.ATTESTED,
                null);
    }

    /** Evaluates and persists the {@link CpuAttestationRecord} via {@link FencedResultWrites}. */
    public static CpuAttestationRecord recordAttemptCpuAttestation(
            Path outputDir,
            int pairId,
            Integer attemptNo,
            String fallbackNodeName,
            boolean strictOnePairPerCpu,
            String cpuLimit) {
        CpuAttestationRecord record =
                evaluate(
                        outputDir,
                        pairId,
                        attemptNo,
                        fallbackNodeName,
                        strictOnePairPerCpu,
                        cpuLimit);
        if (record == null) {
            return null;
        }
        try {
            boolean persisted = FencedResultWrites.recordAttemptCpuAttestation(record);
            if (!persisted) {
                log.warn(
                        "Failed or stale CPU attestation write for pair "
                                + pairId
                                + " attempt "
                                + attemptNo
                                + " (status="
                                + record.getAttestationStatus()
                                + ")");
            } else if (record.isPhysicalExclusivityAttested()) {
                log.info(
                        "Attested exclusive physical-core allocation for pair "
                                + pairId
                                + " attempt "
                                + attemptNo
                                + " on node "
                                + record.getNodeName()
                                + " (cpus="
                                + record.getLeasedCpus()
                                + ")");
            } else {
                log.info(
                        "Recorded UNQUALIFIED CPU attestation for pair "
                                + pairId
                                + " attempt "
                                + attemptNo
                                + " on node "
                                + record.getNodeName()
                                + ": "
                                + record.getViolationReason());
            }
        } catch (Exception e) {
            log.warn(
                    "Exception persisting CPU attestation for pair "
                            + pairId
                            + " attempt "
                            + attemptNo,
                    e);
        }
        return record;
    }

    private static CpuAttestationRecord buildUnqualifiedStrict(
            int pairId,
            int attemptNo,
            String nodeName,
            String allowedCompressed,
            String effectiveRaw,
            TopologyMetadata topoMeta,
            String reason) {
        return new CpuAttestationRecord(
                pairId,
                attemptNo,
                "kubernetes",
                nodeName,
                "strict",
                MECHANISM_SHARED,
                false,
                allowedCompressed,
                null,
                effectiveRaw,
                effectiveRaw,
                allowedCompressed,
                topoMeta.singleCoreId,
                topoMeta.singlePackageId,
                topoMeta.threadSiblingsSummary,
                topoMeta.summary,
                CpuAttestationRecord.Status.UNQUALIFIED,
                reason);
    }

    private static String resolveNodeName(CpuTopologyArtifact artifact, String fallbackNodeName) {
        if (artifact != null
                && artifact.nodeName != null
                && !artifact.nodeName.trim().isEmpty()) {
            return artifact.nodeName.trim();
        }
        if (fallbackNodeName != null && !fallbackNodeName.trim().isEmpty()) {
            return fallbackNodeName.trim();
        }
        return "unknown";
    }

    private static List<Integer> parseCpusetSafe(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return CpuPartitionManager.expandCpuset(spec.trim());
        } catch (IllegalArgumentException e) {
            return Collections.emptyList();
        }
    }

    private static boolean sameCpuSet(List<Integer> a, List<Integer> b) {
        Set<Integer> sa = new LinkedHashSet<>(a);
        Set<Integer> sb = new LinkedHashSet<>(b);
        return sa.equals(sb);
    }

    private static TopologyMetadata extractMetadata(CpuTopologyArtifact artifact) {
        if (artifact == null) {
            return new TopologyMetadata(null, null, null, null);
        }
        Set<Integer> coreIds = new LinkedHashSet<>();
        Set<Integer> pkgIds = new LinkedHashSet<>();
        Set<String> siblings = new LinkedHashSet<>();
        Set<String> physKeys = new LinkedHashSet<>();
        if (artifact.cpus != null) {
            for (CpuEntry entry : artifact.cpus) {
                if (entry == null) {
                    continue;
                }
                if (entry.coreId != null) {
                    coreIds.add(entry.coreId);
                }
                if (entry.packageId != null) {
                    pkgIds.add(entry.packageId);
                }
                if (entry.packageId != null && entry.coreId != null) {
                    physKeys.add(entry.packageId + ":" + entry.coreId);
                }
                if (entry.threadSiblings != null && !entry.threadSiblings.trim().isEmpty()) {
                    siblings.add(entry.threadSiblings.trim());
                }
            }
        }
        Integer singleCore = coreIds.size() == 1 ? coreIds.iterator().next() : null;
        Integer singlePkg = pkgIds.size() == 1 ? pkgIds.iterator().next() : null;
        String siblingSummary = siblings.isEmpty() ? null : String.join(";", siblings);
        StringBuilder sb = new StringBuilder("k8s");
        if (artifact.cgroupHierarchy != null && !artifact.cgroupHierarchy.trim().isEmpty()) {
            sb.append(";hier=").append(artifact.cgroupHierarchy.trim());
        }
        if (artifact.numaNodes != null && !artifact.numaNodes.trim().isEmpty()) {
            sb.append(";numa=").append(artifact.numaNodes.trim());
        }
        if (!physKeys.isEmpty()) {
            sb.append(";cores=").append(String.join(",", physKeys));
        }
        if (artifact.onlineCpus != null && !artifact.onlineCpus.trim().isEmpty()) {
            sb.append(";online=").append(artifact.onlineCpus.trim());
        }
        return new TopologyMetadata(singleCore, singlePkg, siblingSummary, sb.toString());
    }

    private static final class TopologyMetadata {
        final Integer singleCoreId;
        final Integer singlePackageId;
        final String threadSiblingsSummary;
        final String summary;

        TopologyMetadata(
                Integer singleCoreId,
                Integer singlePackageId,
                String threadSiblingsSummary,
                String summary) {
            this.singleCoreId = singleCoreId;
            this.singlePackageId = singlePackageId;
            this.threadSiblingsSummary = threadSiblingsSummary;
            this.summary = summary;
        }
    }
}
