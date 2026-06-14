package org.starexec.backend;

/**
 * Represents a single CPU partition for container job scheduling.
 *
 * <p>A partition is a contiguous or non-contiguous set of logical CPUs assigned
 * exclusively to one virtual StarExec worker node and queue. Containers
 * scheduled on this partition are pinned to its CPU set, preventing
 * cross-partition interference and improving cache locality.</p>
 *
 * <p>On NUMA hardware, each partition typically corresponds to one NUMA node,
 * giving memory bandwidth isolation in addition to CPU isolation. On
 * single-NUMA hardware, partitions still provide CPU-set isolation and fair
 * resource splitting between concurrent jobs.</p>
 */
public final class CpuPartition {

    /** Zero-based partition index (0, 1, 2, …). */
    public final int index;

    /**
     * cpuset string for HostConfig.withCpusetCpus() and Podman --cpuset-cpus.
     * Format: comma-separated ranges, e.g. "0-7,16-23". Null means no CPU
     * pinning and preserves legacy container behavior.
     */
    public final String cpusetCpus;

    /**
     * NUMA memory node for HostConfig.withCpusetMems() and --cpuset-mems. Null
     * means no memory-node binding and preserves legacy container behavior.
     */
    public final String cpusetMems;

    /** StarExec virtual worker node name, e.g. "container-worker-partition-0". */
    public final String workerNodeName;

    /** StarExec virtual queue name, e.g. "partition0.q". */
    public final String queueName;

    /**
     * Creates a named partition using the standard partition node/queue names.
     *
     * @param index zero-based partition index
     * @param cpusetCpus cpuset-cpus value, or null for no pinning
     * @param cpusetMems cpuset-mems value, or null for no memory binding
     */
    public CpuPartition(int index, String cpusetCpus, String cpusetMems) {
        this(
            index,
            cpusetCpus,
            cpusetMems,
            "container-worker-partition-" + index,
            "partition" + index + ".q"
        );
    }

    CpuPartition(
        int index,
        String cpusetCpus,
        String cpusetMems,
        String workerNodeName,
        String queueName
    ) {
        this.index = index;
        this.cpusetCpus = cpusetCpus;
        this.cpusetMems = cpusetMems;
        this.workerNodeName = workerNodeName;
        this.queueName = queueName;
    }

    @Override
    public String toString() {
        return String.format(
            "CpuPartition{index=%d, cpus=%s, mems=%s, node=%s, queue=%s}",
            index,
            cpusetCpus,
            cpusetMems,
            workerNodeName,
            queueName
        );
    }
}
