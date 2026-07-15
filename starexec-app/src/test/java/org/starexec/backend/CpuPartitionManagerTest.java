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

    @Test
    public void testSubdivide_16cpus_2parts() {
        assertEquals(
            Arrays.asList("0-7", "8-15"),
            CpuPartitionManager.subdivide("0-15", 2)
        );
    }

    @Test
    public void testSubdivide_16cpus_4parts() {
        assertEquals(
            Arrays.asList("0-3", "4-7", "8-11", "12-15"),
            CpuPartitionManager.subdivide("0-15", 4)
        );
    }

    @Test
    public void testSubdivide_singleCpu_1part() {
        assertEquals(
            Collections.singletonList("0"),
            CpuPartitionManager.subdivide("0", 1)
        );
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
