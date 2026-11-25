package org.starexec.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.starexec.config.EnvironmentConfig;
import org.starexec.data.database.Cluster;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.database.StageStatusBatchResult;
import org.starexec.data.to.Status.StatusCode;

/**
 * Tests for {@link LocalCpuExclusivity}, {@code STAREXEC_LOCAL_ISOLATION_MODE}, fail-closed
 * {@code strict} verification, simulated cgroups v2 isolated partition runtime attestation, and
 * conditional real Linux delegated cgroup v2 integration.
 */
public class LocalCpuExclusivityTest {

    @TempDir Path tempDir;

    @Test
    public void isolationModeNegotiationDefaultsPermissiveAndRejectsInvalidValues() {
        assertEquals(
                LocalCpuExclusivity.IsolationMode.PERMISSIVE,
                LocalCpuExclusivity.IsolationMode.parse(null));
        assertEquals(
                LocalCpuExclusivity.IsolationMode.PERMISSIVE,
                LocalCpuExclusivity.IsolationMode.parse(""));
        assertEquals(
                LocalCpuExclusivity.IsolationMode.PERMISSIVE,
                LocalCpuExclusivity.IsolationMode.parse("  permissive "));
        assertEquals(
                LocalCpuExclusivity.IsolationMode.STRICT,
                LocalCpuExclusivity.IsolationMode.parse(" STRICT "));
        assertEquals("permissive", LocalCpuExclusivity.IsolationMode.PERMISSIVE.getValue());
        assertEquals("strict", LocalCpuExclusivity.IsolationMode.STRICT.getValue());

        IllegalArgumentException err =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> LocalCpuExclusivity.IsolationMode.parse("exclusive"));
        assertTrue(err.getMessage().contains("STAREXEC_LOCAL_ISOLATION_MODE"));
    }

    @Test
    public void strictModeFailsClosedWithoutCgroupsV2UnifiedHierarchy() throws Exception {
        int realCpu = detectRealAllowedOnlineCpu();
        Path fakeCgroupRoot = tempDir.resolve("cgroup-missing");
        Files.createDirectories(fakeCgroupRoot);
        Path procSelfCgroup = tempDir.resolve("proc-self-cgroup");
        Files.writeString(procSelfCgroup, "0::/user.slice/app.scope\n", StandardCharsets.UTF_8);

        LocalBackend backend =
                createStrictBackend(
                        List.of(realCpu),
                        new StaticCpuReader(List.of(realCpu), List.of(realCpu)),
                        Map.of(realCpu, new LocalCpuExclusivity.CoreTopology(realCpu, 0, 0, List.of(realCpu))),
                        new LocalCpuExclusivity.LinuxCgroupCpusetController(
                                fakeCgroupRoot, procSelfCgroup, tempDir.resolve("proc")),
                        new LocalJobMonitor());

        try (MockedStatic<EnvironmentConfig> env = mockStatic(EnvironmentConfig.class);
                MockedStatic<Cluster> cluster = mockStatic(Cluster.class)) {
            env.when(EnvironmentConfig::getLocalCoreList).thenReturn(Integer.toString(realCpu));
            cluster.when(() -> Cluster.getNodeIdByName(any())).thenReturn(1);

            IllegalStateException ex =
                    assertThrows(
                            IllegalStateException.class,
                            () -> backend.initialize(tempDir.toString()));
            assertTrue(ex.getMessage().contains("cgroups v2"));
        }
    }

    @Test
    public void strictModeFailsClosedWhenCpusetControllerNotDelegated() throws Exception {
        int realCpu = detectRealAllowedOnlineCpu();
        Path fakeCgroupRoot = tempDir.resolve("cgroup-no-cpuset");
        Path selfDir = fakeCgroupRoot.resolve("user.slice/app.scope");
        Files.createDirectories(selfDir);
        Files.writeString(
                fakeCgroupRoot.resolve("cgroup.controllers"),
                "cpuset cpu memory pids\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                selfDir.resolve("cgroup.controllers"), "memory pids\n", StandardCharsets.UTF_8);
        Files.writeString(selfDir.resolve("cgroup.subtree_control"), "\n", StandardCharsets.UTF_8);
        Path procSelfCgroup = tempDir.resolve("proc-self-cgroup");
        Files.writeString(procSelfCgroup, "0::/user.slice/app.scope\n", StandardCharsets.UTF_8);

        LocalBackend backend =
                createStrictBackend(
                        List.of(realCpu),
                        new StaticCpuReader(List.of(realCpu), List.of(realCpu)),
                        Map.of(realCpu, new LocalCpuExclusivity.CoreTopology(realCpu, 0, 0, List.of(realCpu))),
                        new LocalCpuExclusivity.LinuxCgroupCpusetController(
                                fakeCgroupRoot, procSelfCgroup, tempDir.resolve("proc")),
                        new LocalJobMonitor());

        try (MockedStatic<EnvironmentConfig> env = mockStatic(EnvironmentConfig.class);
                MockedStatic<Cluster> cluster = mockStatic(Cluster.class)) {
            env.when(EnvironmentConfig::getLocalCoreList).thenReturn(Integer.toString(realCpu));
            cluster.when(() -> Cluster.getNodeIdByName(any())).thenReturn(1);

            IllegalStateException ex =
                    assertThrows(
                            IllegalStateException.class,
                            () -> backend.initialize(tempDir.toString()));
            assertTrue(ex.getMessage().contains("cpuset"));
        }
    }

    @Test
    public void strictModeMovesJvmPidToLeafBeforeAttemptingSubtreeDelegation() throws Exception {
        int realCpu = detectRealAllowedOnlineCpu();
        Path fakeCgroupRoot = tempDir.resolve("cgroup-delegated");
        Path selfDir = fakeCgroupRoot.resolve("user.slice/starexec.scope");
        Files.createDirectories(selfDir);
        Files.writeString(
                fakeCgroupRoot.resolve("cgroup.controllers"),
                "cpuset memory pids\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                selfDir.resolve("cgroup.controllers"),
                "cpuset memory pids\n",
                StandardCharsets.UTF_8);
        Files.writeString(selfDir.resolve("cgroup.subtree_control"), "\n", StandardCharsets.UTF_8);
        Files.writeString(selfDir.resolve("cpuset.mems.effective"), "0\n", StandardCharsets.UTF_8);
        long selfPid = ProcessHandle.current().pid();
        Files.writeString(selfDir.resolve("cgroup.procs"), selfPid + "\n", StandardCharsets.UTF_8);

        Path jvmLeaf = selfDir.resolve(LocalCpuExclusivity.JVM_LEAF_CGROUP_NAME);
        Files.createDirectory(jvmLeaf);
        Files.createFile(jvmLeaf.resolve("cgroup.procs"));

        Path procSelfCgroup = tempDir.resolve("proc-self-cgroup");
        Files.writeString(
                procSelfCgroup, "0::/user.slice/starexec.scope\n", StandardCharsets.UTF_8);

        LocalCpuExclusivity.LinuxCgroupCpusetController controller =
                new LocalCpuExclusivity.LinuxCgroupCpusetController(
                        fakeCgroupRoot, procSelfCgroup, tempDir.resolve("proc"));

        // Plain filesystem cannot convert '+cpuset' into kernel-enabled 'cpuset' or populate
        // kernel cpuset.cpus.effective, so initialization fails closed while proving JVM leaf move
        // and '+cpuset' subtree write happened beforehand.
        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                controller.verifyStrictHostSupport(
                                        List.of(realCpu),
                                        Map.of(
                                                realCpu,
                                                new LocalCpuExclusivity.CoreTopology(
                                                        realCpu, 0, 0, List.of(realCpu))),
                                        List.of(realCpu),
                                        List.of(realCpu)));
        assertTrue(ex.getMessage().contains("cpuset"));
        assertTrue(
                Files.readString(jvmLeaf.resolve("cgroup.procs"), StandardCharsets.UTF_8)
                        .contains(Long.toString(selfPid)));
        assertEquals(
                "+cpuset",
                Files.readString(
                                selfDir.resolve("cgroup.subtree_control"), StandardCharsets.UTF_8)
                        .trim());
    }

    @Test
    public void strictModeFailsClosedOnMissingSysfsTopologyOrExcludedSmtSibling() throws Exception {
        int realCpu = detectRealAllowedOnlineCpu();
        int missingSibling = realCpu + 100;
        Path sysfs = tempDir.resolve("sys-cpu");
        Files.createDirectories(sysfs.resolve("cpu" + realCpu + "/topology"));
        // Missing core_id / physical_package_id / thread_siblings_list
        LocalCpuExclusivity.TopologyReader partialSysfsReader =
                new LocalCpuExclusivity.LinuxTopologyReader(sysfs);
        SimulatedCgroupController simController = new SimulatedCgroupController(tempDir);

        LocalJobMonitor monitorA = new LocalJobMonitor();
        LocalJobMonitor monitorB = new LocalJobMonitor();
        LocalBackend incompleteBackend =
                new LocalBackend(
                        new StaticCpuReader(List.of(realCpu), List.of(realCpu)),
                        () -> Map.of(realCpu, List.of(realCpu)),
                        LocalCpuExclusivity.IsolationMode.STRICT,
                        partialSysfsReader,
                        simController,
                        System::nanoTime,
                        50L,
                        0L,
                        10_000_000L,
                        monitorA);

        try (MockedStatic<EnvironmentConfig> env = mockStatic(EnvironmentConfig.class);
                MockedStatic<Cluster> cluster = mockStatic(Cluster.class)) {
            env.when(EnvironmentConfig::getLocalCoreList).thenReturn(Integer.toString(realCpu));
            cluster.when(() -> Cluster.getNodeIdByName(any())).thenReturn(1);

            IllegalStateException incompleteEx =
                    assertThrows(
                            IllegalStateException.class,
                            () -> incompleteBackend.initialize(tempDir.toString()));
            assertTrue(incompleteEx.getMessage().contains("sysfs topology"));

            // Now populate full sysfs topology declaring SMT sibling pair, while effectiveAffinity
            // omits missingSibling.
            Path topoDir = sysfs.resolve("cpu" + realCpu + "/topology");
            Files.writeString(topoDir.resolve("core_id"), "3\n", StandardCharsets.UTF_8);
            Files.writeString(
                    topoDir.resolve("physical_package_id"), "0\n", StandardCharsets.UTF_8);
            Files.writeString(
                    topoDir.resolve("thread_siblings_list"),
                    realCpu + "," + missingSibling + "\n",
                    StandardCharsets.UTF_8);

            LocalBackend excludedSiblingBackend =
                    new LocalBackend(
                            new StaticCpuReader(
                                    List.of(realCpu), List.of(realCpu, missingSibling)),
                            () -> Map.of(realCpu, List.of(realCpu, missingSibling)),
                            LocalCpuExclusivity.IsolationMode.STRICT,
                            partialSysfsReader,
                            simController,
                            System::nanoTime,
                            50L,
                            0L,
                            10_000_000L,
                            monitorB);

            IllegalStateException excludedEx =
                    assertThrows(
                            IllegalStateException.class,
                            () -> excludedSiblingBackend.initialize(tempDir.toString()));
            assertTrue(excludedEx.getMessage().contains("SMT siblings"));
        }
    }

    @Test
    public void strictModeFailsClosedWhenPartitionProbeStaysMember() throws Exception {
        int realCpu = detectRealAllowedOnlineCpu();
        SimulatedCgroupController nonIsolatingController = new SimulatedCgroupController(tempDir);
        nonIsolatingController.forceProbePartition("member");

        LocalBackend backend =
                createStrictBackend(
                        List.of(realCpu),
                        new StaticCpuReader(List.of(realCpu), List.of(realCpu)),
                        Map.of(realCpu, new LocalCpuExclusivity.CoreTopology(realCpu, 0, 0, List.of(realCpu))),
                        nonIsolatingController,
                        new LocalJobMonitor());

        try (MockedStatic<EnvironmentConfig> env = mockStatic(EnvironmentConfig.class);
                MockedStatic<Cluster> cluster = mockStatic(Cluster.class)) {
            env.when(EnvironmentConfig::getLocalCoreList).thenReturn(Integer.toString(realCpu));
            cluster.when(() -> Cluster.getNodeIdByName(any())).thenReturn(1);

            IllegalStateException ex =
                    assertThrows(
                            IllegalStateException.class,
                            () -> backend.initialize(tempDir.toString()));
            assertTrue(ex.getMessage().contains("partition"));
        }
    }

    @Test
    public void strictRunReservesAllSiblingsInIsolatedSlotAndRecordsAttestedEvidence()
            throws Exception {
        int realCpu = detectRealAllowedOnlineCpu();
        int smtSibling = realCpu + 100;
        String expectedSiblingCpuset = realCpu + "," + smtSibling;

        SimulatedCgroupController simController = new SimulatedCgroupController(tempDir);
        LocalJobMonitor monitor = new LocalJobMonitor();
        LocalCpuExclusivity.CoreTopology topology =
                new LocalCpuExclusivity.CoreTopology(
                        realCpu, 4, 1, List.of(realCpu, smtSibling));

        LocalBackend backend =
                createStrictBackend(
                        List.of(realCpu),
                        new StaticCpuReader(
                                List.of(realCpu, smtSibling), List.of(realCpu, smtSibling)),
                        Map.of(realCpu, topology),
                        simController,
                        monitor);

        int pairId = 601;
        Path outDir = Files.createDirectory(tempDir.resolve("strict-out"));
        Path workDir = Files.createDirectory(tempDir.resolve("strict-work"));
        Path cgroupMembershipProof = tempDir.resolve("cgroup-procs-at-exec.txt");
        Path script =
                writeCompleteSolverScript(
                        outDir,
                        pairId,
                        "cat \""
                                + simController.getSlotsRoot()
                                + "\"/*/cgroup.procs > '"
                                + cgroupMembershipProof
                                + "' 2>/dev/null || true; sleep 0.05");
        Object job = newLocalJob(1, pairId, 1, script, workDir, outDir.resolve("job.log"));

        try (MockedStatic<EnvironmentConfig> env = mockStatic(EnvironmentConfig.class);
                MockedStatic<Cluster> cluster = mockStatic(Cluster.class);
                MockedStatic<JobPairs> db = mockStatic(JobPairs.class)) {
            env.when(EnvironmentConfig::getLocalCoreList).thenReturn(Integer.toString(realCpu));
            cluster.when(() -> Cluster.getNodeIdByName(any())).thenReturn(1);
            stubHappyDatabase(db, pairId);

            backend.initialize(tempDir.toString());
            try {
                invokeExecuteJob(backend, job);
                monitor.pollOnceForTesting();

                assertFalse(monitor.isIngestionBlocked(pairId));
                assertEquals(0, simController.getActiveSlotCount(), "slot cgroup must be cleaned up");
                assertTrue(
                        Files.exists(cgroupMembershipProof)
                                && !Files.readString(cgroupMembershipProof).trim().isEmpty(),
                        "solver must start inside the isolated slot cgroup.procs");

                ArgumentCaptor<CpuAttestationRecord> captor =
                        ArgumentCaptor.forClass(CpuAttestationRecord.class);
                db.verify(() -> JobPairs.recordAttemptCpuAttestation(captor.capture()));
                CpuAttestationRecord rec = captor.getValue();
                assertEquals(CpuAttestationRecord.Status.ATTESTED, rec.getAttestationStatus());
                assertEquals("strict", rec.getIsolationMode());
                assertEquals("cpuset-v2", rec.getIsolationMechanism());
                assertTrue(rec.isPhysicalExclusivityAttested());
                assertEquals(Integer.toString(realCpu), rec.getLeasedCpus());
                assertEquals(expectedSiblingCpuset, rec.getReservedSiblingCpus());
                assertEquals(expectedSiblingCpuset, rec.getInitialEffectiveCpuset());
                assertEquals(expectedSiblingCpuset, rec.getTerminalEffectiveCpuset());
                assertEquals(Integer.valueOf(4), rec.getTopologyCoreId());
                assertEquals(Integer.valueOf(1), rec.getTopologyPackageId());
                assertEquals(expectedSiblingCpuset, rec.getTopologyThreadSiblings());
                assertNull(rec.getViolationReason());
            } finally {
                backend.destroyIf();
            }
        }
    }

    @Test
    public void strictRuntimePartitionDowngradeAbortsAndBlocksIngestion() throws Exception {
        int realCpu = detectRealAllowedOnlineCpu();
        int smtSibling = realCpu + 100;

        int pairId = 602;
        Path outDir = Files.createDirectory(tempDir.resolve("downgrade-out"));
        Path workDir = Files.createDirectory(tempDir.resolve("downgrade-work"));
        Path statusFile = outDir.resolve("status.json");
        Path statusTmp = outDir.resolve("status.json.tmp");

        SimulatedCgroupController simController = new SimulatedCgroupController(tempDir);
        simController.downgradeOnceFileExists("member", statusFile);
        LocalJobMonitor monitor = new LocalJobMonitor();
        LocalCpuExclusivity.CoreTopology topology =
                new LocalCpuExclusivity.CoreTopology(
                        realCpu, 2, 0, List.of(realCpu, smtSibling));

        LocalBackend backend =
                createStrictBackend(
                        List.of(realCpu),
                        new StaticCpuReader(
                                List.of(realCpu, smtSibling), List.of(realCpu, smtSibling)),
                        Map.of(realCpu, topology),
                        simController,
                        monitor);

        Path script =
                writeCompleteSolverScript(
                        outDir,
                        pairId,
                        "cat > '"
                                + statusTmp
                                + "' <<'EOF'\n"
                                + "{\"pairId\":"
                                + pairId
                                + ",\"status\":"
                                + StatusCode.STATUS_COMPLETE.getVal()
                                + ",\"stageNumber\":1,\"timestamp\":1}\n"
                                + "EOF\n"
                                + "mv '"
                                + statusTmp
                                + "' '"
                                + statusFile
                                + "'\n"
                                + "sleep 5");
        Object job = newLocalJob(2, pairId, 1, script, workDir, outDir.resolve("job.log"));

        try (MockedStatic<EnvironmentConfig> env = mockStatic(EnvironmentConfig.class);
                MockedStatic<Cluster> cluster = mockStatic(Cluster.class);
                MockedStatic<JobPairs> db = mockStatic(JobPairs.class)) {
            env.when(EnvironmentConfig::getLocalCoreList).thenReturn(Integer.toString(realCpu));
            cluster.when(() -> Cluster.getNodeIdByName(any())).thenReturn(1);
            stubHappyDatabase(db, pairId);

            backend.initialize(tempDir.toString());
            try {
                invokeExecuteJob(backend, job);
                monitor.pollOnceForTesting();

                assertTrue(monitor.isIngestionBlocked(pairId));
                assertEquals(0, simController.getActiveSlotCount());
                db.verify(
                        () ->
                                JobPairs.setPairStatusPreciseResult(
                                        anyInt(), anyInt(), anyInt(), anyInt(), anyBoolean(), any()),
                        never());
                db.verify(
                        () ->
                                JobPairs.setPairLevelStatusResult(
                                        anyInt(), anyInt(), anyInt(), any()),
                        never());
                db.verify(
                        () ->
                                JobPairs.updateRunSolverStats(
                                        anyInt(),
                                        any(),
                                        anyDouble(),
                                        anyDouble(),
                                        anyDouble(),
                                        anyDouble(),
                                        anyDouble(),
                                        anyLong(),
                                        anyInt(),
                                        anyLong(),
                                        any()),
                        never());

                ArgumentCaptor<CpuAttestationRecord> captor =
                        ArgumentCaptor.forClass(CpuAttestationRecord.class);
                db.verify(() -> JobPairs.recordAttemptCpuAttestation(captor.capture()), atLeastOnce());
                CpuAttestationRecord rec = captor.getValue();
                assertEquals(CpuAttestationRecord.Status.VIOLATED, rec.getAttestationStatus());
                assertEquals("strict", rec.getIsolationMode());
                assertEquals("cpuset-v2", rec.getIsolationMechanism());
                assertFalse(rec.isPhysicalExclusivityAttested());
                assertTrue(rec.getViolationReason().contains("member"));
            } finally {
                backend.destroyIf();
            }
        }
    }

    @Test
    public void permissiveModeWorksWithoutCgroupDelegationAndMarksUnqualified() throws Exception {
        int realCpu = detectRealAllowedOnlineCpu();
        Path missingSysfs = tempDir.resolve("no-sysfs");
        LocalCpuExclusivity.CgroupCpusetController failingStrictController =
                new LocalCpuExclusivity.LinuxCgroupCpusetController(
                        tempDir.resolve("no-cgroup"),
                        tempDir.resolve("no-proc-cgroup"),
                        tempDir.resolve("no-proc"));
        LocalJobMonitor monitor = new LocalJobMonitor();
        LocalBackend backend =
                new LocalBackend(
                        new StaticCpuReader(List.of(realCpu), List.of(realCpu)),
                        () -> Map.of(realCpu, List.of(realCpu)),
                        LocalCpuExclusivity.IsolationMode.PERMISSIVE,
                        new LocalCpuExclusivity.LinuxTopologyReader(missingSysfs),
                        failingStrictController,
                        System::nanoTime,
                        50L,
                        0L,
                        10_000_000L,
                        monitor);

        int pairId = 603;
        Path outDir = Files.createDirectory(tempDir.resolve("perm-out"));
        Path workDir = Files.createDirectory(tempDir.resolve("perm-work"));
        Path script = writeCompleteSolverScript(outDir, pairId, "sleep 0.02");
        Object job = newLocalJob(3, pairId, 1, script, workDir, outDir.resolve("job.log"));

        try (MockedStatic<EnvironmentConfig> env = mockStatic(EnvironmentConfig.class);
                MockedStatic<Cluster> cluster = mockStatic(Cluster.class);
                MockedStatic<JobPairs> db = mockStatic(JobPairs.class)) {
            env.when(EnvironmentConfig::getLocalCoreList).thenReturn(Integer.toString(realCpu));
            cluster.when(() -> Cluster.getNodeIdByName(any())).thenReturn(1);
            stubHappyDatabase(db, pairId);

            backend.initialize(tempDir.toString());
            try {
                invokeExecuteJob(backend, job);
                monitor.pollOnceForTesting();

                assertFalse(monitor.isIngestionBlocked(pairId));
                ArgumentCaptor<CpuAttestationRecord> captor =
                        ArgumentCaptor.forClass(CpuAttestationRecord.class);
                db.verify(() -> JobPairs.recordAttemptCpuAttestation(captor.capture()));
                CpuAttestationRecord rec = captor.getValue();
                assertEquals(CpuAttestationRecord.Status.UNQUALIFIED, rec.getAttestationStatus());
                assertEquals("permissive", rec.getIsolationMode());
                assertEquals("taskset", rec.getIsolationMechanism());
                assertFalse(rec.isPhysicalExclusivityAttested());
            } finally {
                backend.destroyIf();
            }
        }
    }

    @Test
    public void realLinuxDelegatedCgroupV2StrictIntegration() throws Exception {
        Assumptions.assumeTrue(
                isRealStrictCgroupV2Available(),
                "Host does not delegate writable cgroup v2 cpuset isolated partitions to current user");

        int realCpu = detectRealAllowedOnlineCpu();
        LocalJobMonitor monitor = new LocalJobMonitor();
        LocalBackend backend =
                new LocalBackend(
                        LocalCpuAttestation.LinuxCpuEnvironmentReader.DEFAULT,
                        () -> CpuPartitionManager.readThreadSiblings(
                                LocalCpuExclusivity.DEFAULT_SYSFS_CPU_PATH),
                        LocalCpuExclusivity.IsolationMode.STRICT,
                        LocalCpuExclusivity.LinuxTopologyReader.DEFAULT,
                        LocalCpuExclusivity.LinuxCgroupCpusetController.DEFAULT,
                        System::nanoTime,
                        50L,
                        0L,
                        250_000_000L,
                        monitor);

        int pairId = 604;
        Path outDir = Files.createDirectory(tempDir.resolve("real-strict-out"));
        Path workDir = Files.createDirectory(tempDir.resolve("real-strict-work"));
        Path procCgroupOut = tempDir.resolve("real-child-cgroup.txt");
        Path script =
                writeCompleteSolverScript(
                        outDir, pairId, "cat /proc/self/cgroup > '" + procCgroupOut + "'; sleep 0.05");
        Object job = newLocalJob(4, pairId, 1, script, workDir, outDir.resolve("job.log"));

        try (MockedStatic<EnvironmentConfig> env = mockStatic(EnvironmentConfig.class);
                MockedStatic<Cluster> cluster = mockStatic(Cluster.class);
                MockedStatic<JobPairs> db = mockStatic(JobPairs.class)) {
            env.when(EnvironmentConfig::getLocalCoreList).thenReturn(Integer.toString(realCpu));
            cluster.when(() -> Cluster.getNodeIdByName(any())).thenReturn(1);
            stubHappyDatabase(db, pairId);

            backend.initialize(tempDir.toString());
            try {
                invokeExecuteJob(backend, job);
                monitor.pollOnceForTesting();

                assertFalse(monitor.isIngestionBlocked(pairId));
                assertTrue(
                        Files.readString(procCgroupOut, StandardCharsets.UTF_8)
                                .contains("starexec-slot-exec4-pair" + pairId));

                ArgumentCaptor<CpuAttestationRecord> captor =
                        ArgumentCaptor.forClass(CpuAttestationRecord.class);
                db.verify(() -> JobPairs.recordAttemptCpuAttestation(captor.capture()));
                CpuAttestationRecord rec = captor.getValue();
                assertEquals(CpuAttestationRecord.Status.ATTESTED, rec.getAttestationStatus());
                assertEquals("strict", rec.getIsolationMode());
                assertEquals("cpuset-v2", rec.getIsolationMechanism());
                assertTrue(rec.isPhysicalExclusivityAttested());
            } finally {
                backend.destroyIf();
            }
        }
    }

    private static LocalBackend createStrictBackend(
            List<Integer> allowedCores,
            LocalCpuAttestation.CpuEnvironmentReader reader,
            Map<Integer, LocalCpuExclusivity.CoreTopology> topologies,
            LocalCpuExclusivity.CgroupCpusetController controller,
            LocalJobMonitor monitor) {
        Map<Integer, List<Integer>> siblingMap = new LinkedHashMap<>();
        for (int cpu : allowedCores) {
            LocalCpuExclusivity.CoreTopology topo = topologies.get(cpu);
            siblingMap.put(
                    cpu,
                    topo != null && !topo.getSiblingCpus().isEmpty()
                            ? topo.getSiblingCpus()
                            : List.of(cpu));
        }
        return new LocalBackend(
                reader,
                () -> siblingMap,
                LocalCpuExclusivity.IsolationMode.STRICT,
                cpu -> topologies.get(cpu),
                controller,
                System::nanoTime,
                50L,
                0L,
                2_000_000_000L,
                monitor);
    }

    private static boolean isRealStrictCgroupV2Available() {
        try {
            if (!Files.exists(LocalCpuExclusivity.DEFAULT_CGROUP_ROOT.resolve("cgroup.controllers"))
                    || !Files.exists(LocalCpuExclusivity.DEFAULT_PROC_SELF_CGROUP)) {
                return false;
            }
            int cpu = detectRealAllowedOnlineCpu();
            LocalCpuExclusivity.CoreTopology topo =
                    LocalCpuExclusivity.LinuxTopologyReader.DEFAULT.readCoreTopology(cpu);
            if (!topo.isCompleteStrictTopology()) {
                return false;
            }
            LocalCpuExclusivity.LinuxCgroupCpusetController.DEFAULT.verifyStrictHostSupport(
                    List.of(cpu),
                    Map.of(cpu, topo),
                    LocalCpuAttestation.LinuxCpuEnvironmentReader.DEFAULT.readEffectiveAffinity(),
                    LocalCpuAttestation.LinuxCpuEnvironmentReader.DEFAULT.readOnlineCpus());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int detectRealAllowedOnlineCpu() {
        try {
            List<Integer> allowed =
                    LocalCpuAttestation.LinuxCpuEnvironmentReader.DEFAULT.readEffectiveAffinity();
            List<Integer> online =
                    LocalCpuAttestation.LinuxCpuEnvironmentReader.DEFAULT.readOnlineCpus();
            for (int cpu : allowed) {
                if (online.contains(cpu)) {
                    return cpu;
                }
            }
        } catch (LocalCpuAttestation.CpuStateReadException ignored) {
            // Fall through to 0
        }
        return 0;
    }

    private static Path writeCompleteSolverScript(Path outDir, int pairId, String body)
            throws IOException {
        Path script = outDir.resolve("run-" + pairId + ".sh");
        String content =
                "#!/usr/bin/env bash\n"
                        + "set -euo pipefail\n"
                        + "mkdir -p '"
                        + outDir.resolve("stage-stats")
                        + "' '"
                        + outDir.resolve("stage-attributes")
                        + "'\n"
                        + "cat > '"
                        + outDir.resolve("stage-stats/1.json")
                        + "' <<'EOF'\n"
                        + "{\"pairId\":"
                        + pairId
                        + ",\"stageNumber\":1,\"wallclockTime\":0.1,"
                        + "\"cpuTime\":0.05,\"userTime\":0.04,\"systemTime\":0.01,"
                        + "\"maxVirtualMemory\":1024,\"maxResidentSetSize\":512,"
                        + "\"diskSize\":16,\"hostname\":\"local-node\"}\n"
                        + "EOF\n"
                        + "printf 'answer=sat\\n' > '"
                        + outDir.resolve("stage-attributes/1.txt")
                        + "'\n"
                        + body
                        + "\n"
                        + "cat > '"
                        + outDir.resolve("status.json")
                        + "' <<'EOF'\n"
                        + "{\"pairId\":"
                        + pairId
                        + ",\"status\":"
                        + StatusCode.STATUS_COMPLETE.getVal()
                        + ",\"stageNumber\":1,\"timestamp\":1}\n"
                        + "EOF\n";
        Files.writeString(script, content, StandardCharsets.UTF_8);
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }

    private static Object newLocalJob(
            int execId, int pairId, int attemptNo, Path script, Path workDir, Path logFile)
            throws Exception {
        Class<?> localJobClass =
                Class.forName("org.starexec.backend.LocalBackend$LocalJob");
        Constructor<?> ctor =
                localJobClass.getDeclaredConstructor(
                        int.class, int.class, String.class, String.class, String.class);
        ctor.setAccessible(true);
        Object job =
                ctor.newInstance(
                        execId,
                        pairId,
                        script.toString(),
                        workDir.toString(),
                        logFile.toString());
        var field = localJobClass.getDeclaredField("attemptNo");
        field.setAccessible(true);
        field.set(job, attemptNo);
        return job;
    }

    private static void invokeExecuteJob(LocalBackend backend, Object job) throws Exception {
        Class<?> localJobClass =
                Class.forName("org.starexec.backend.LocalBackend$LocalJob");
        Method m = LocalBackend.class.getDeclaredMethod("executeJob", localJobClass);
        m.setAccessible(true);
        m.invoke(backend, job);
    }

    private static void stubHappyDatabase(MockedStatic<JobPairs> db, int pairId) {
        db.when(() -> JobPairs.getCurrentAttemptNo(pairId)).thenReturn(1);
        db.when(() -> JobPairs.getStageNumbers(pairId)).thenReturn(Set.of(1));
        db.when(() -> JobPairs.setStartTime(pairId)).thenReturn(true);
        db.when(
                        () ->
                                JobPairs.setPairStatusPreciseResult(
                                        anyInt(), anyInt(), anyInt(), anyInt(), anyBoolean(), any()))
                .thenReturn(PairStatusResult.APPLIED);
        db.when(() -> JobPairs.setPairLevelStatusResult(anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(PairStatusResult.APPLIED);
        db.when(() -> JobPairs.setEarlierStageStatuses(anyInt(), anyMap(), any()))
                .thenReturn(StageStatusBatchResult.APPLIED);
        db.when(
                        () ->
                                JobPairs.updateRunSolverStats(
                                        anyInt(),
                                        any(),
                                        anyDouble(),
                                        anyDouble(),
                                        anyDouble(),
                                        anyDouble(),
                                        anyDouble(),
                                        anyLong(),
                                        anyInt(),
                                        anyLong(),
                                        any()))
                .thenReturn(true);
        db.when(() -> JobPairs.addJobPairAttributes(anyInt(), anyInt(), any(), org.mockito.ArgumentMatchers.<Integer>any()))
                .thenReturn(true);
        db.when(() -> JobPairs.recordAttemptCpuAttestation(any())).thenReturn(true);
    }

    private static final class StaticCpuReader
            implements LocalCpuAttestation.CpuEnvironmentReader {
        private final List<Integer> affinity;
        private final List<Integer> online;

        StaticCpuReader(List<Integer> affinity, List<Integer> online) {
            this.affinity = affinity;
            this.online = online;
        }

        @Override
        public List<Integer> readEffectiveAffinity() {
            return affinity;
        }

        @Override
        public List<Integer> readOnlineCpus() {
            return online;
        }

        @Override
        public Optional<List<Integer>> readCgroupEffectiveCpuset() {
            return Optional.of(affinity);
        }

        @Override
        public Optional<List<Integer>> readProcessAllowedCpus(long pid)
                throws LocalCpuAttestation.CpuStateReadException {
            return LocalCpuAttestation.LinuxCpuEnvironmentReader.DEFAULT.readProcessAllowedCpus(
                    pid);
        }
    }

    private static final class SimulatedCgroupController
            implements LocalCpuExclusivity.CgroupCpusetController {
        private final Path slotsRoot;
        private final ConcurrentHashMap<String, LocalCpuExclusivity.SlotReservation> activeSlots =
                new ConcurrentHashMap<>();
        private String forcedProbePartition = "isolated";
        private String downgradePartitionOnRead;
        private Path downgradeTriggerFile;
        private int readCallsAfterAcquire;

        SimulatedCgroupController(Path baseDir) throws IOException {
            this.slotsRoot = baseDir.resolve("sim-cgroups");
            Files.createDirectories(slotsRoot);
        }

        void forceProbePartition(String partition) {
            this.forcedProbePartition = partition;
        }

        void downgradeOnRead(String partition) {
            this.downgradePartitionOnRead = partition;
            this.downgradeTriggerFile = null;
        }

        void downgradeOnceFileExists(String partition, Path triggerFile) {
            this.downgradePartitionOnRead = partition;
            this.downgradeTriggerFile = triggerFile;
        }

        Path getSlotsRoot() {
            return slotsRoot;
        }

        int getActiveSlotCount() {
            return activeSlots.size();
        }

        @Override
        public void verifyStrictHostSupport(
                List<Integer> configuredCores,
                Map<Integer, LocalCpuExclusivity.CoreTopology> topologies,
                List<Integer> effectiveAffinity,
                List<Integer> onlineCpus) {
            if (!"isolated".equals(forcedProbePartition)) {
                throw new IllegalStateException(
                        "Probe cpuset.cpus.partition reported '"
                                + forcedProbePartition
                                + "' instead of 'isolated'");
            }
        }

        @Override
        public LocalCpuExclusivity.SlotReservation acquireSlot(
                int execId, int pairId, LocalCpuExclusivity.CoreTopology topology)
                throws IOException {
            String slotId = "sim-slot-" + execId + "-" + pairId;
            Path slotDir = slotsRoot.resolve(slotId);
            Files.createDirectories(slotDir);
            Files.writeString(slotDir.resolve("cgroup.procs"), "", StandardCharsets.UTF_8);
            LocalCpuExclusivity.SlotReservation reservation =
                    new LocalCpuExclusivity.SlotReservation(
                            topology.getCpu(),
                            slotId,
                            slotDir,
                            "/" + slotId,
                            topology.getSiblingCpuset(),
                            topology.getSiblingCpus());
            activeSlots.put(slotId, reservation);
            readCallsAfterAcquire = 0;
            return reservation;
        }

        @Override
        public synchronized void assignPid(LocalCpuExclusivity.SlotReservation reservation, long pid)
                throws IOException {
            Path procs = reservation.getCgroupProcsPath();
            String existing = Files.exists(procs) ? Files.readString(procs, StandardCharsets.UTF_8) : "";
            if (!existing.contains(Long.toString(pid))) {
                Files.writeString(
                        procs,
                        pid + "\n",
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            }
        }

        @Override
        public LocalCpuExclusivity.SlotCgroupState readSlotState(
                LocalCpuExclusivity.SlotReservation reservation) {
            readCallsAfterAcquire++;
            String partition = "isolated";
            if (downgradePartitionOnRead != null) {
                boolean triggered = downgradeTriggerFile != null
                        ? Files.exists(downgradeTriggerFile)
                        : readCallsAfterAcquire > 1;
                if (triggered) {
                    partition = downgradePartitionOnRead;
                }
            }
            return new LocalCpuExclusivity.SlotCgroupState(
                    reservation.getReservedSiblingCpus(), partition, "0");
        }

        @Override
        public Optional<Boolean> isPidInSlot(
                LocalCpuExclusivity.SlotReservation reservation, long pid)
                throws LocalCpuAttestation.CpuStateReadException {
            Path procs = reservation.getCgroupProcsPath();
            if (!Files.exists(procs)) {
                return Optional.empty();
            }
            try {
                String content = Files.readString(procs, StandardCharsets.UTF_8);
                return Optional.of(content.contains(Long.toString(pid)));
            } catch (IOException e) {
                throw new LocalCpuAttestation.CpuStateReadException(e.getMessage(), e);
            }
        }

        @Override
        public void releaseSlot(LocalCpuExclusivity.SlotReservation reservation) {
            activeSlots.remove(reservation.getSlotId());
            try {
                Files.deleteIfExists(reservation.getCgroupProcsPath());
                Files.deleteIfExists(reservation.getSlotCgroupPath());
            } catch (IOException ignored) {
                // Best-effort cleanup
            }
        }
    }
}
