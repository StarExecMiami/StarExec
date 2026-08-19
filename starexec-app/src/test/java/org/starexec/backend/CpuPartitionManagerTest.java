package org.starexec.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class CpuPartitionManagerTest {

    @Test
    public void testDiscoverWithExplicitPartitions_twoStrings() throws Exception {
        Path tempDir = Files.createTempDirectory("cpu-partitions-explicit");
        try {
            Map<String, String> env = new HashMap<>();
            env.put("STAREXEC_CPU_PARTITIONS", "0-7 8-15");

            List<CpuPartition> partitions = CpuPartitionManager.discover(
                env,
                tempDir.resolve("node"),
                tempDir.resolve("online")
            );

            assertEquals(2, partitions.size());
            assertEquals("0-7", partitions.get(0).cpusetCpus);
            assertEquals("0", partitions.get(0).cpusetMems);
            assertEquals("container-worker-partition-0", partitions.get(0).workerNodeName);
            assertEquals(PodmanBackend.CONTAINER_QUEUE_NAME, partitions.get(0).queueName);
            assertEquals("8-15", partitions.get(1).cpusetCpus);
            assertEquals("0", partitions.get(1).cpusetMems);
            assertEquals("container-worker-partition-1", partitions.get(1).workerNodeName);
            assertEquals(PodmanBackend.CONTAINER_QUEUE_NAME, partitions.get(1).queueName);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testDiscoverWithNumaOverride_singleNode() throws Exception {
        Path tempDir = Files.createTempDirectory("cpu-partitions-single-numa");
        try {
            Path nodeRoot = tempDir.resolve("node");
            Files.createDirectories(nodeRoot.resolve("node0"));
            Files.writeString(nodeRoot.resolve("node0").resolve("cpulist"), "0-15\n");

            List<CpuPartition> partitions = CpuPartitionManager.discover(
                Collections.singletonMap("STAREXEC_CPU_PARTITION_COUNT", "auto"),
                nodeRoot,
                tempDir.resolve("online")
            );

            assertEquals(1, partitions.size());
            assertNull(partitions.get(0).cpusetCpus);
            assertNull(partitions.get(0).cpusetMems);
            assertEquals(PodmanBackend.CONTAINER_WORKER_NODE, partitions.get(0).workerNodeName);
            assertEquals(PodmanBackend.CONTAINER_QUEUE_NAME, partitions.get(0).queueName);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testDiscoverWithPartitionCountOne_preservesLegacyNoPinning()
        throws Exception {
        Path tempDir = Files.createTempDirectory("cpu-partitions-count-one");
        try {
            Path onlinePath = tempDir.resolve("online");
            Files.writeString(onlinePath, "0-15\n");

            List<CpuPartition> partitions = CpuPartitionManager.discover(
                Collections.singletonMap("STAREXEC_CPU_PARTITION_COUNT", "1"),
                tempDir.resolve("node"),
                onlinePath
            );

            assertEquals(1, partitions.size());
            assertNull(partitions.get(0).cpusetCpus);
            assertNull(partitions.get(0).cpusetMems);
            assertEquals(PodmanBackend.CONTAINER_WORKER_NODE, partitions.get(0).workerNodeName);
            assertEquals(PodmanBackend.CONTAINER_QUEUE_NAME, partitions.get(0).queueName);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testDiscoverWithNumaOverride_dualNode() throws Exception {
        Path tempDir = Files.createTempDirectory("cpu-partitions-dual-numa");
        try {
            Path nodeRoot = tempDir.resolve("node");
            Files.createDirectories(nodeRoot.resolve("node0"));
            Files.createDirectories(nodeRoot.resolve("node1"));
            Files.writeString(nodeRoot.resolve("node0").resolve("cpulist"), "0-7,16-23\n");
            Files.writeString(nodeRoot.resolve("node1").resolve("cpulist"), "8-15,24-31\n");

            List<CpuPartition> partitions = CpuPartitionManager.discover(
                Collections.singletonMap("STAREXEC_CPU_PARTITION_COUNT", "auto"),
                nodeRoot,
                tempDir.resolve("online")
            );

            assertEquals(2, partitions.size());
            assertEquals("0-7,16-23", partitions.get(0).cpusetCpus);
            assertEquals("0", partitions.get(0).cpusetMems);
            assertEquals("container-worker-partition-0", partitions.get(0).workerNodeName);
            assertEquals(PodmanBackend.CONTAINER_QUEUE_NAME, partitions.get(0).queueName);
            assertEquals("8-15,24-31", partitions.get(1).cpusetCpus);
            assertEquals("1", partitions.get(1).cpusetMems);
            assertEquals("container-worker-partition-1", partitions.get(1).workerNodeName);
            assertEquals(PodmanBackend.CONTAINER_QUEUE_NAME, partitions.get(1).queueName);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testExpandCpuset_rangesAndSingletons() {
        assertEquals(
            Arrays.asList(0, 1, 2, 4, 6, 7),
            CpuPartitionManager.expandCpuset("0-2,4,6-7")
        );
    }

    @Test
    public void testCompressCpuset_rangesAndSingletons() {
        assertEquals(
            "0-2,4,6-7",
            CpuPartitionManager.compressCpuset(Arrays.asList(0, 1, 2, 4, 6, 7))
        );
    }

    // ------------------------------------------------------------------
    // subdivide
    //
    // These pass an explicit sibling topology rather than letting subdivide
    // read the host's. The single-argument form reads /sys, so asserting on
    // it here would make the result depend on the machine the suite runs on:
    // this developer box enumerates siblings as adjacent pairs (cpu0's
    // sibling is cpu1), while many machines use the split-half layout (cpu0's
    // sibling is cpu8) and would produce a different, equally correct answer.
    // ------------------------------------------------------------------

    /** cpu i's sibling is cpu i+1 for even i — the adjacent-pair enumeration. */
    private static Map<Integer, List<Integer>> adjacentPairSiblings(int cpuCount) {
        Map<Integer, List<Integer>> m = new java.util.TreeMap<>();
        for (int i = 0; i < cpuCount; i += 2) {
            List<Integer> pair = Arrays.asList(i, i + 1);
            m.put(i, pair);
            m.put(i + 1, pair);
        }
        return m;
    }

    /** cpu i's sibling is cpu i+half — the split-half enumeration. */
    private static Map<Integer, List<Integer>> splitHalfSiblings(int cpuCount) {
        Map<Integer, List<Integer>> m = new java.util.TreeMap<>();
        int half = cpuCount / 2;
        for (int i = 0; i < half; i++) {
            List<Integer> pair = Arrays.asList(i, i + half);
            m.put(i, pair);
            m.put(i + half, pair);
        }
        return m;
    }

    @Test
    public void testSubdivide_16cpus_2parts() {
        assertEquals(
            Arrays.asList("0-7", "8-15"),
            CpuPartitionManager.subdivide("0-15", 2, adjacentPairSiblings(16))
        );
    }

    @Test
    public void testSubdivide_16cpus_4parts() {
        assertEquals(
            Arrays.asList("0-3", "4-7", "8-11", "12-15"),
            CpuPartitionManager.subdivide("0-15", 4, adjacentPairSiblings(16))
        );
    }

    @Test
    public void testSubdivide_singleCpu_1part() {
        assertEquals(
            Collections.singletonList("0"),
            CpuPartitionManager.subdivide("0", 1, Collections.emptyMap())
        );
    }

    /**
     * The defect this replaces. Under the split-half enumeration the old index split
     * returned "0-7" and "8-15" — and partition 1 was then exactly the SMT-sibling set of
     * partition 0, so every pair on one shared an L1 and L2 with the pair on the other.
     * That is the layout docs/cpu-partition-scheduling.md recommends.
     */
    @Test
    public void testSubdivide_splitHalfTopology_keepsSiblingsTogether() {
        List<String> parts =
            CpuPartitionManager.subdivide("0-15", 2, splitHalfSiblings(16));

        assertEquals(
            "each partition must hold whole physical cores, not one thread of each",
            Arrays.asList("0-3,8-11", "4-7,12-15"),
            parts
        );
    }

    @Test
    public void testSubdivide_refusesToSplitMorePartitionsThanPhysicalCores() {
        // 4 CPUs, 2 physical cores. A third partition could only be formed by separating
        // siblings, which would let two pairs share a cache while appearing isolated.
        try {
            CpuPartitionManager.subdivide("0-3", 3, adjacentPairSiblings(4));
            org.junit.Assert.fail(
                "splitting beyond the physical core count must be refused, not silently"
                    + " produce partitions that share a cache"
            );
        } catch (IllegalArgumentException expected) {
            org.junit.Assert.assertTrue(
                "the error should say why: " + expected.getMessage(),
                expected.getMessage().contains("SMT siblings")
            );
        }
    }

    /** With no topology available we fall back to the index split, and still work. */
    @Test
    public void testSubdivide_noTopologyFallsBackToIndexSplit() {
        assertEquals(
            Arrays.asList("0-7", "8-15"),
            CpuPartitionManager.subdivide("0-15", 2, Collections.emptyMap())
        );
    }

    /** A sibling outside the cpuset is not ours to hand out. */
    @Test
    public void testSubdivide_ignoresSiblingsOutsideTheCpuset() {
        // cpuset covers 0-3 only, while the machine pairs 0 with 8, 1 with 9, and so on.
        List<String> parts =
            CpuPartitionManager.subdivide("0-3", 2, splitHalfSiblings(16));

        assertEquals(Arrays.asList("0-1", "2-3"), parts);
    }

    // ------------------------------------------------------------------
    // Validation of an explicit STAREXEC_CPU_PARTITIONS
    //
    // subdivide() cannot protect these -- a hand-written value never passes
    // through it. That matters because docs/cpu-partition-scheduling.md
    // recommends "0-7 8-15", which on the split-half enumeration is one
    // partition holding every sibling of the other.
    // ------------------------------------------------------------------

    /** The exact string the documentation recommends, on a split-half machine. */
    @Test
    public void testExplicitPartitions_rejectsTheDocumentedSiblingSplit() {
        try {
            CpuPartitionManager.partitionsFromOverride("0-7 8-15", splitHalfSiblings(16));
            org.junit.Assert.fail(
                "a configuration that splits SMT siblings across partitions must abort"
                    + " startup, not silently degrade to no isolation"
            );
        } catch (CpuPartitionManager.UnsafePartitionConfigurationException expected) {
            org.junit.Assert.assertTrue(
                "the error should name the hazard: " + expected.getMessage(),
                expected.getMessage().contains("splits SMT siblings")
            );
        }
    }

    /** The same string is correct on an adjacent-pair machine, and must be accepted. */
    @Test
    public void testExplicitPartitions_acceptsASafeLayout() {
        List<CpuPartition> parts =
            CpuPartitionManager.partitionsFromOverride("0-7 8-15", adjacentPairSiblings(16));

        assertEquals(2, parts.size());
        assertEquals("0-7", parts.get(0).cpusetCpus);
        assertEquals("8-15", parts.get(1).cpusetCpus);
    }

    @Test
    public void testExplicitPartitions_rejectsOverlappingPartitions() {
        try {
            CpuPartitionManager.partitionsFromOverride("0-7 4-11", adjacentPairSiblings(16));
            org.junit.Assert.fail("two partitions sharing a CPU must abort startup");
        } catch (CpuPartitionManager.UnsafePartitionConfigurationException expected) {
            org.junit.Assert.assertTrue(
                expected.getMessage(),
                expected.getMessage().contains("both partition")
            );
        }
    }

    /**
     * Including CPU 0 degrades a measurement with interrupt noise rather than
     * invalidating it, so it warns instead of failing — a two-core CI box keeps working.
     */
    @Test
    public void testExplicitPartitions_cpuZeroIsAWarningNotAFailure() {
        List<CpuPartition> parts =
            CpuPartitionManager.partitionsFromOverride("0-1 2-3", adjacentPairSiblings(4));

        assertEquals(2, parts.size());
        assertEquals("0-1", parts.get(0).cpusetCpus);
    }

    /** With no topology we cannot check for splits, so we must not reject blindly. */
    @Test
    public void testExplicitPartitions_withoutTopologyStillAccepts() {
        List<CpuPartition> parts =
            CpuPartitionManager.partitionsFromOverride("0-7 8-15", Collections.emptyMap());

        assertEquals(2, parts.size());
    }

    @Test
    public void testDiscoverFallsBackOnSysfsFailure() throws Exception {
        Path tempDir = Files.createTempDirectory("cpu-partitions-garbage");
        try {
            Path nodeRoot = tempDir.resolve("node");
            Files.createDirectories(nodeRoot.resolve("node0"));
            Files.createDirectories(nodeRoot.resolve("node1"));
            Files.writeString(nodeRoot.resolve("node0").resolve("cpulist"), "garbage\n");
            Files.writeString(nodeRoot.resolve("node1").resolve("cpulist"), "also-garbage\n");

            List<CpuPartition> partitions = CpuPartitionManager.discover(
                Collections.singletonMap("STAREXEC_CPU_PARTITION_COUNT", "auto"),
                nodeRoot,
                tempDir.resolve("online")
            );

            assertEquals(1, partitions.size());
            assertNull(partitions.get(0).cpusetCpus);
            assertNull(partitions.get(0).cpusetMems);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream
                .sorted((left, right) -> right.compareTo(left))
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        }
    }
}
