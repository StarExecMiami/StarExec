package org.starexec.backend;

import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.config.EnvironmentConfig;

/** Tests the LocalBackend CPU lease boundary independently of host CPU numbering. */
public class LocalCoreListValidationTest {

    @Test
    public void validCoreListPreservesOperatorOrder() {
        List<Integer> parsed = LocalBackend.parseConfiguredCoreList("6, 2,4");
        List<Integer> validated = LocalBackend.validateConfiguredCoreList(
                parsed,
                Arrays.asList(2, 4, 6, 8),
                Arrays.asList(2, 4, 6, 8),
                singletonTopology(2, 4, 6, 8));

        assertEquals(Arrays.asList(6, 2, 4), validated);
        expectUnsupported(() -> validated.add(8));
    }

    @Test
    public void nonIntegerTokenIsRejected() {
        expectInvalidCoreList("2,not-a-cpu", "not an integer");
    }

    @Test
    public void negativeCpuIsRejected() {
        expectInvalidCoreList("2,-1", "non-negative");
    }

    @Test
    public void everyEmptyTokenPositionIsRejected() {
        for (String value : Arrays.asList(",2", "2,,4", "2,")) {
            expectInvalidCoreList(value, "is empty");
        }
    }

    @Test
    public void duplicateCpuIsRejected() {
        expectInvalidCoreList("4,2,4", "duplicate CPU ID 4");
    }

    @Test
    public void cpuOutsideEffectiveAffinityIsRejected() {
        List<Integer> parsed = LocalBackend.parseConfiguredCoreList("2,6");
        expectIllegalArgument(
                () -> LocalBackend.validateConfiguredCoreList(
                        parsed,
                        Arrays.asList(2, 4),
                        Arrays.asList(2, 4, 6),
                        singletonTopology(2, 4, 6)),
                "outside this process's effective affinity 2,4");
    }

    @Test
    public void configuredCpuMustBeOnlineEvenWhenAffinityAllowsIt() {
        expectIllegalArgument(
                () -> LocalBackend.validateConfiguredCoreList(
                        Collections.singletonList(2),
                        Arrays.asList(2, 4),
                        Collections.singletonList(4),
                        Collections.emptyMap()),
                "STAREXEC_LOCAL_CORE_LIST CPU 2 is offline; online CPUs: 4");
    }

    @Test
    public void smtSiblingsAreRejectedAsSeparateLeases() {
        Map<Integer, List<Integer>> topology = new LinkedHashMap<>();
        topology.put(4, Arrays.asList(4, 12));
        topology.put(12, Arrays.asList(4, 12));

        expectIllegalArgument(
                () -> LocalBackend.validateConfiguredCoreList(
                        LocalBackend.parseConfiguredCoreList("4,12"),
                        Arrays.asList(4, 12),
                        Arrays.asList(4, 12),
                        topology),
                "are SMT siblings on the same physical core");
    }

    @Test
    public void multipleLeasesFailClosedWhenSmtTopologyIsUnavailable() {
        expectIllegalState(
                () -> LocalBackend.validateConfiguredCoreList(
                        LocalBackend.parseConfiguredCoreList("2,4"),
                        Arrays.asList(2, 4),
                        Arrays.asList(2, 4),
                        Collections.emptyMap()),
                "thread_siblings_list topology is unavailable");
    }

    @Test
    public void oneLeaseCannotConflictWithAnUnknownSibling() {
        assertEquals(
                Collections.singletonList(2),
                LocalBackend.validateConfiguredCoreList(
                        LocalBackend.parseConfiguredCoreList("2"),
                        Arrays.asList(2, 4),
                        Arrays.asList(2, 4),
                        Collections.emptyMap()));
    }

    @Test
    public void effectiveAffinityUsesKernelCpusetSyntax() throws Exception {
        Path status = Files.createTempFile("local-core-affinity", ".status");
        Files.writeString(
                status,
                "Name:\tjava\nCpus_allowed_list:\t2-3,8\n");

        assertEquals(
                Arrays.asList(2, 3, 8),
                LocalBackend.readEffectiveCpuAffinity(status));
    }

    @Test
    public void onlineCpuListUsesKernelCpusetSyntax() throws Exception {
        Path online = Files.createTempFile("local-core-online", ".list");
        Files.writeString(online, "2-3,8\n");

        assertEquals(Arrays.asList(2, 3, 8), LocalBackend.readOnlineCpuList(online));
    }

    @Test
    public void missingOnlineCpuListFailsClosed() throws Exception {
        Path directory = Files.createTempDirectory("local-core-online-missing");

        expectIllegalState(
                () -> LocalBackend.readOnlineCpuList(directory.resolve("online")),
                "Cannot read online CPU list");
    }

    @Test
    public void malformedOnlineCpuListFailsClosed() throws Exception {
        Path online = Files.createTempFile("local-core-online-malformed", ".list");
        Files.writeString(online, "2-not-a-cpu\n");

        expectIllegalState(
                () -> LocalBackend.readOnlineCpuList(online),
                "Cannot read online CPU list");
    }

    @Test
    public void defaultCoreSelectionPreservesNonContiguousAffinityOrder() {
        List<Integer> selected = LocalBackend.selectDefaultCoreList(
                2,
                Arrays.asList(3, 7, 11),
                Arrays.asList(3, 7, 11),
                singletonTopology(3, 7, 11));

        assertEquals(Arrays.asList(3, 7), selected);
        expectUnsupported(() -> selected.add(11));
    }

    @Test
    public void defaultCoreSelectionSkipsAllowedSmtSiblings() {
        Map<Integer, List<Integer>> topology = new LinkedHashMap<>();
        topology.put(2, Arrays.asList(2, 8));
        topology.put(8, Arrays.asList(2, 8));
        topology.put(10, Collections.singletonList(10));

        assertEquals(
                Arrays.asList(2, 10),
                LocalBackend.selectDefaultCoreList(
                        2,
                        Arrays.asList(2, 8, 10),
                        Arrays.asList(2, 8, 10),
                        topology));
    }

    @Test
    public void defaultCoreSelectionSkipsOfflineCpusAllowedByAffinity() {
        assertEquals(
                Arrays.asList(7, 11),
                LocalBackend.selectDefaultCoreList(
                        2,
                        Arrays.asList(3, 7, 11),
                        Arrays.asList(7, 11),
                        singletonTopology(3, 7, 11)));
    }

    @Test
    public void defaultCoreSelectionRejectsConcurrencyAboveOnlineIntersection() {
        expectIllegalArgument(
                () -> LocalBackend.selectDefaultCoreList(
                        2,
                        Arrays.asList(2, 4),
                        Collections.singletonList(4),
                        singletonTopology(2, 4)),
                "effective/online CPU intersection 4 has a schedulable CPU count of 1");
    }

    @Test
    public void defaultCoreSelectionFailsClosedWhenNoAllowedCpuIsOnline() {
        expectIllegalState(
                () -> LocalBackend.selectDefaultCoreList(
                        1,
                        Collections.singletonList(2),
                        Collections.singletonList(4),
                        Collections.emptyMap()),
                "effective affinity 2 contains no online CPUs");
    }

    @Test
    public void defaultCoreSelectionRejectsConcurrencyAbovePhysicalCoreCount() {
        Map<Integer, List<Integer>> topology = new LinkedHashMap<>();
        topology.put(2, Arrays.asList(2, 8));
        topology.put(8, Arrays.asList(2, 8));

        expectIllegalArgument(
                () -> LocalBackend.selectDefaultCoreList(
                        2,
                        Arrays.asList(2, 8),
                        Arrays.asList(2, 8),
                        topology),
                "requests 2 physical-core leases, but effective/online CPU intersection " +
                        "2,8 provides only 1 distinct physical core");
    }

    @Test
    public void defaultCoreSelectionRejectsUnavailableTopologyForMultipleLeases() {
        expectIllegalState(
                () -> LocalBackend.selectDefaultCoreList(
                        2,
                        Arrays.asList(2, 8),
                        Arrays.asList(2, 8),
                        Collections.emptyMap()),
                "thread_siblings_list topology is unavailable");
    }

    @Test
    public void defaultCoreSelectionRejectsPartialSiblingTopology() {
        Map<Integer, List<Integer>> topology = new LinkedHashMap<>();
        topology.put(2, Arrays.asList(2, 8));

        expectIllegalState(
                () -> LocalBackend.selectDefaultCoreList(
                        2,
                        Arrays.asList(2, 8),
                        Arrays.asList(2, 8),
                        topology),
                "thread_siblings_list topology is unavailable for CPU 8");
    }

    @Test
    public void defaultCoreSelectionRejectsAsymmetricTopology() {
        Map<Integer, List<Integer>> topology = new LinkedHashMap<>();
        topology.put(2, Arrays.asList(2, 8));
        topology.put(8, Collections.singletonList(8));

        expectIllegalState(
                () -> LocalBackend.selectDefaultCoreList(
                        2,
                        Arrays.asList(2, 8),
                        Arrays.asList(2, 8),
                        topology),
                "asymmetric thread_siblings_list topology");
    }

    @Test
    public void defaultCoreSelectionRejectsTopologyThatOmitsTheCpuItDescribes() {
        Map<Integer, List<Integer>> topology = new LinkedHashMap<>();
        topology.put(2, Collections.singletonList(8));
        topology.put(8, Arrays.asList(2, 8));

        expectIllegalState(
                () -> LocalBackend.selectDefaultCoreList(
                        2,
                        Arrays.asList(2, 8),
                        Arrays.asList(2, 8),
                        topology),
                "thread_siblings_list for CPU 2 does not include CPU 2 itself");
    }

    @Test
    public void defaultCoreSelectionRejectsConcurrencyAboveEffectiveAffinity() {
        expectIllegalArgument(
                () -> LocalBackend.selectDefaultCoreList(
                        3,
                        Arrays.asList(3, 7),
                        Arrays.asList(3, 7),
                        singletonTopology(3, 7)),
                "STAREXEC_LOCAL_CONCURRENCY requests 3 CPU leases, but effective affinity " +
                        "3,7 has an allowed CPU count of 2");
    }

    @Test
    public void initializationRejectsDuplicateBeforeStartingWorkers() {
        LocalBackend backend = new LocalBackend();
        try (MockedStatic<EnvironmentConfig> environment = Mockito.mockStatic(
                EnvironmentConfig.class,
                Mockito.CALLS_REAL_METHODS)) {
            environment.when(EnvironmentConfig::getLocalCoreList).thenReturn("3,3");

            expectIllegalArgument(
                    () -> backend.initialize(""),
                    "duplicate CPU ID 3");
            environment.verify(EnvironmentConfig::getLocalCoreList, Mockito.times(1));
        } finally {
            backend.destroyIf();
        }
    }

    /**
     * Executes a real child process and asks the kernel for that child's affinity. The
     * configuration selects an online CPU from this JVM's effective affinity, so the
     * assertion is independent of how the host numbers or restricts its CPUs.
     */
    @Test
    public void realJobProcessReceivesExactlyItsLeasedCpu() throws Exception {
        List<Integer> effectiveAffinity = LocalBackend.readEffectiveCpuAffinity(
                Path.of("/proc/self/status"));
        List<Integer> onlineCpus = LocalBackend.readOnlineCpuList(
                Path.of("/sys/devices/system/cpu/online"));
        int leasedCpu = LocalBackend
                .selectDefaultCoreList(
                        1,
                        effectiveAffinity,
                        onlineCpus,
                        Collections.emptyMap())
                .get(0);
        LocalBackend backend = new LocalBackend();
        Path directory = Files.createTempDirectory("local-core-e2e");
        Path work = Files.createDirectory(directory.resolve("work"));
        Path script = directory.resolve("report-affinity.sh");
        Path output = directory.resolve("affinity.out");
        Files.writeString(
                script,
                "#!/bin/bash\nawk '/^Cpus_allowed_list:/ { print $2 }' /proc/self/status\n");
        assertTrue(script.toFile().setExecutable(true));

        try (MockedStatic<EnvironmentConfig> environment = Mockito.mockStatic(
                EnvironmentConfig.class,
                Mockito.CALLS_REAL_METHODS)) {
            environment.when(EnvironmentConfig::getLocalCoreList)
                    .thenReturn(String.valueOf(leasedCpu));
            backend.initialize("");
            environment.verify(EnvironmentConfig::getLocalCoreList, Mockito.times(1));
            assertEquals(1, backend.getStats().get("maxConcurrency"));

            backend.submitScript(
                    -1,
                    script.toString(),
                    work.toString(),
                    output.toString());

            await()
                    .atMost(10, TimeUnit.SECONDS)
                    .until(() -> Files.exists(output) && !Files.readString(output).trim().isEmpty());
            assertEquals(String.valueOf(leasedCpu), Files.readString(output).trim());
        } finally {
            backend.destroyIf();
        }
    }

    /** A default lease must remain inside the online portion of inherited affinity. */
    @Test
    public void realJobWithoutCoreListStaysInsideInheritedAffinity() throws Exception {
        List<Integer> effectiveAffinity = LocalBackend.readEffectiveCpuAffinity(
                Path.of("/proc/self/status"));
        List<Integer> onlineCpus = LocalBackend.readOnlineCpuList(
                Path.of("/sys/devices/system/cpu/online"));
        LocalBackend backend = new LocalBackend();
        Path directory = Files.createTempDirectory("local-default-core-e2e");
        Path work = Files.createDirectory(directory.resolve("work"));
        Path script = directory.resolve("report-affinity.sh");
        Path output = directory.resolve("affinity.out");
        Files.writeString(
                script,
                "#!/bin/bash\nawk '/^Cpus_allowed_list:/ { print $2 }' /proc/self/status\n");
        assertTrue(script.toFile().setExecutable(true));

        try (MockedStatic<EnvironmentConfig> environment = Mockito.mockStatic(
                EnvironmentConfig.class,
                Mockito.CALLS_REAL_METHODS)) {
            environment.when(EnvironmentConfig::getLocalCoreList).thenReturn(null);
            backend.initialize("");
            environment.verify(EnvironmentConfig::getLocalCoreList, Mockito.times(1));

            backend.submitScript(
                    -1,
                    script.toString(),
                    work.toString(),
                    output.toString());

            await()
                    .atMost(10, TimeUnit.SECONDS)
                    .until(() -> Files.exists(output) && !Files.readString(output).trim().isEmpty());
            int actualCpu = Integer.parseInt(Files.readString(output).trim());
            assertTrue(
                    "Job CPU " + actualCpu + " must be inside inherited affinity " +
                            effectiveAffinity,
                    effectiveAffinity.contains(actualCpu));
            assertTrue(
                    "Job CPU " + actualCpu + " must be online " + onlineCpus,
                    onlineCpus.contains(actualCpu));
        } finally {
            backend.destroyIf();
        }
    }

    private static Map<Integer, List<Integer>> singletonTopology(Integer... cpus) {
        Map<Integer, List<Integer>> topology = new LinkedHashMap<>();
        for (int cpu : cpus) {
            topology.put(cpu, Collections.singletonList(cpu));
        }
        return topology;
    }

    private static void expectInvalidCoreList(String value, String messageFragment) {
        expectIllegalArgument(
                () -> LocalBackend.parseConfiguredCoreList(value),
                messageFragment);
    }

    private static void expectIllegalArgument(Runnable operation, String messageFragment) {
        try {
            operation.run();
            fail("Expected IllegalArgumentException containing: " + messageFragment);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(messageFragment));
        }
    }

    private static void expectIllegalState(Runnable operation, String messageFragment) {
        try {
            operation.run();
            fail("Expected IllegalStateException containing: " + messageFragment);
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(messageFragment));
        }
    }

    private static void expectUnsupported(Runnable operation) {
        try {
            operation.run();
            fail("Expected an unmodifiable validated core list");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }
}
