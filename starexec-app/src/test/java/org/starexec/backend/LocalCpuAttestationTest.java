package org.starexec.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.config.EnvironmentConfig;
import org.starexec.data.database.Cluster;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.database.StageStatusBatchResult;
import org.starexec.data.to.Status.StatusCode;

/**
 * Deterministic tests for runtime and ingestion-boundary CPU allocation attestation (#295).
 */
public class LocalCpuAttestationTest {

    @TempDir
    Path tempDir;

    private final List<LocalBackend> backendsToClose = new ArrayList<>();
    private final List<LocalJobMonitor> monitorsToClose = new ArrayList<>();

    @AfterEach
    public void tearDown() {
        for (LocalBackend backend : backendsToClose) {
            backend.destroyIf();
        }
        for (LocalJobMonitor monitor : monitorsToClose) {
            monitor.stop();
        }
    }

    private static int detectRealAllowedOnlineCpu() throws Exception {
        LocalCpuAttestation.SystemSnapshot real =
            LocalCpuAttestation.LinuxCpuEnvironmentReader.DEFAULT.readSystemSnapshot();
        for (int cpu : CpuPartitionManager.expandCpuset(real.getOnlineStr())) {
            if (real.isCpuValid(cpu)) {
                return cpu;
            }
        }
        throw new AssertionError("No valid online allowed CPU found on host");
    }

    private LocalBackend createBackend(
            String coreList,
            MutableCpuReader reader,
            Map<Integer, List<Integer>> siblingTopology,
            AtomicLong clockNanos,
            long startupGraceNanos,
            LocalJobMonitor monitor) {
        monitorsToClose.add(monitor);
        LocalBackend backend = new LocalBackend(
            reader,
            () -> siblingTopology,
            clockNanos::get,
            25L,
            0L,
            startupGraceNanos,
            monitor
        );
        try (MockedStatic<EnvironmentConfig> env = Mockito.mockStatic(EnvironmentConfig.class);
                MockedStatic<Cluster> cluster = Mockito.mockStatic(Cluster.class)) {
            env.when(EnvironmentConfig::getLocalCoreList).thenReturn(coreList);
            cluster.when(() -> Cluster.getNodeIdByName(anyString())).thenReturn(1);
            backend.initialize("");
        }
        backendsToClose.add(backend);
        return backend;
    }

    private static Object newLocalJob(
            int execId,
            int pairId,
            int attemptNo,
            Path script,
            Path workDir,
            Path logPath) throws Exception {
        Class<?> jobClass = Class.forName("org.starexec.backend.LocalBackend$LocalJob");
        Constructor<?> ctor = jobClass.getDeclaredConstructor(
            int.class, int.class, String.class, String.class, String.class
        );
        ctor.setAccessible(true);
        Object job = ctor.newInstance(
            execId, pairId, script.toString(), workDir.toString(), logPath.toString()
        );
        Field attemptField = jobClass.getDeclaredField("attemptNo");
        attemptField.setAccessible(true);
        attemptField.set(job, attemptNo);
        return job;
    }

    private static void invokeExecuteJob(LocalBackend backend, Object job) throws Exception {
        Method m = LocalBackend.class.getDeclaredMethod("executeJob", job.getClass());
        m.setAccessible(true);
        m.invoke(backend, job);
    }

    @SuppressWarnings("unchecked")
    private static BlockingQueue<Integer> availableCores(LocalBackend backend) throws Exception {
        Field f = LocalBackend.class.getDeclaredField("availableCores");
        f.setAccessible(true);
        return (BlockingQueue<Integer>) f.get(backend);
    }

    private Path writeCompleteSolverScript(Path outDir, int pairId, String extraBody)
            throws Exception {
        Path script = tempDir.resolve("solver-" + System.nanoTime() + ".sh");
        String body =
            "#!/bin/bash\n"
                + "set -euo pipefail\n"
                + extraBody + "\n"
                + "mkdir -p '" + outDir.resolve("stage-stats") + "' '"
                + outDir.resolve("stage-attributes") + "'\n"
                + "cat > '" + outDir.resolve("stage-stats/1.json") + "' <<'EOF'\n"
                + "{\"pairId\":" + pairId + ",\"stageNumber\":1,\"wallclockTime\":0.1,"
                + "\"cpuTime\":0.05,\"userTime\":0.04,\"systemTime\":0.01,"
                + "\"maxVirtualMemory\":1024,\"maxResidentSetSize\":512,"
                + "\"diskSize\":16,\"hostname\":\"local-node\"}\n"
                + "EOF\n"
                + "printf 'answer=sat\\n' > '" + outDir.resolve("stage-attributes/1.txt") + "'\n"
                + "cat > '" + outDir.resolve("status.json") + "' <<'EOF'\n"
                + "{\"pairId\":" + pairId + ",\"status\":"
                + StatusCode.STATUS_COMPLETE.getVal() + ",\"stageNumber\":1,\"timestamp\":1}\n"
                + "EOF\n";
        Files.writeString(script, body);
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }

    private static void stubHappyDatabase(MockedStatic<JobPairs> db, int pairId) {
        db.when(() -> JobPairs.getStageNumbers(pairId)).thenReturn(Set.of(1));
        db.when(() -> JobPairs.setEarlierStageStatuses(anyInt(), anyMap(), any()))
            .thenReturn(StageStatusBatchResult.APPLIED);
        db.when(() -> JobPairs.setPairStatusPreciseResult(
                anyInt(), anyInt(), anyInt(), anyInt(), anyBoolean(), any()))
            .thenReturn(PairStatusResult.APPLIED);
        db.when(() -> JobPairs.updateRunSolverStats(
                anyInt(), anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyLong(), anyInt(), anyLong(), any()))
            .thenReturn(true);
        db.when(() -> JobPairs.addJobPairAttributes(
                anyInt(), anyInt(), any(Properties.class), any(Integer.class)))
            .thenReturn(true);
        db.when(() -> JobPairs.recordAttemptCpuAttestation(any(CpuAttestationRecord.class)))
            .thenReturn(true);
    }

    private static void verifyNoScientificWrites(MockedStatic<JobPairs> db) {
        db.verify(
            () -> JobPairs.setEarlierStageStatuses(anyInt(), anyMap(), any()),
            never()
        );
        db.verify(
            () -> JobPairs.setPairStatusPreciseResult(
                anyInt(), anyInt(), anyInt(), anyInt(), anyBoolean(), any()),
            never()
        );
        db.verify(
            () -> JobPairs.setPairLevelStatusResult(anyInt(), anyInt(), anyInt(), any()),
            never()
        );
        db.verify(
            () -> JobPairs.updateRunSolverStats(
                anyInt(), anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyLong(), anyInt(), anyLong(), any()),
            never()
        );
        db.verify(
            () -> JobPairs.addJobPairAttributes(
                anyInt(), anyInt(), any(Properties.class), Mockito.<Integer>any()),
            never()
        );
    }

    @Test
    public void validRunRecordsAttestedEvidenceWithEffectiveCpuset() throws Exception {
        int realCpu = detectRealAllowedOnlineCpu();
        MutableCpuReader reader = new MutableCpuReader(
            List.of(realCpu), List.of(realCpu), Optional.of(List.of(realCpu)), null
        );
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        LocalJobMonitor monitor = new LocalJobMonitor();
        LocalBackend backend = createBackend(
            Integer.toString(realCpu),
            reader,
            Map.of(realCpu, List.of(realCpu)),
            clock,
            250_000_000L,
            monitor
        );

        int pairId = 501;
        Path outDir = Files.createDirectory(tempDir.resolve("valid-run"));
        Path workDir = Files.createDirectory(tempDir.resolve("valid-work"));
        Path script = writeCompleteSolverScript(outDir, pairId, "sleep 0.05");
        Object job = newLocalJob(1, pairId, 1, script, workDir, outDir.resolve("job.log"));

        try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
            stubHappyDatabase(db, pairId);
            invokeExecuteJob(backend, job);
            monitor.pollOnceForTesting();

            assertFalse(monitor.isTracking(pairId));
            assertFalse(monitor.isIngestionBlocked(pairId));
            db.verify(() -> JobPairs.setPairStatusPreciseResult(
                Mockito.eq(pairId),
                Mockito.eq(1),
                Mockito.eq(StatusCode.STATUS_COMPLETE.getVal()),
                Mockito.eq(StatusCode.STATUS_NOT_REACHED.getVal()),
                Mockito.eq(false),
                Mockito.eq(1)
            ));

            ArgumentCaptor<CpuAttestationRecord> captor =
                ArgumentCaptor.forClass(CpuAttestationRecord.class);
            db.verify(() -> JobPairs.recordAttemptCpuAttestation(captor.capture()));
            CpuAttestationRecord recorded = captor.getValue();
            assertEquals(CpuAttestationRecord.Status.ATTESTED, recorded.getAttestationStatus());
            assertEquals(Integer.toString(realCpu), recorded.getLeasedCpus());
            assertEquals(Integer.toString(realCpu), recorded.getInitialEffectiveCpuset());
            assertEquals(Integer.toString(realCpu), recorded.getTerminalEffectiveCpuset());
            assertEquals("taskset", recorded.getIsolationMode());
        }
    }

    @Test
    public void postLaunchOnlineCpuRemovalBlocksIngestionAndWithholdsInvalidCpu()
            throws Exception {
        int realCpu = detectRealAllowedOnlineCpu();
        int backupCpu = realCpu + 100;
        MutableCpuReader reader = new MutableCpuReader(
            List.of(realCpu, backupCpu),
            List.of(realCpu, backupCpu),
            Optional.of(List.of(realCpu, backupCpu)),
            null
        );
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        LocalJobMonitor monitor = new LocalJobMonitor();
        Map<Integer, List<Integer>> topology = new LinkedHashMap<>();
        topology.put(realCpu, List.of(realCpu));
        topology.put(backupCpu, List.of(backupCpu));
        LocalBackend backend = createBackend(
            realCpu + "," + backupCpu,
            reader,
            topology,
            clock,
            250_000_000L,
            monitor
        );

        int pairId = 502;
        Path outDir = Files.createDirectory(tempDir.resolve("offline-run"));
        Path workDir = Files.createDirectory(tempDir.resolve("offline-work"));
        Path script = writeCompleteSolverScript(outDir, pairId, "sleep 0.02");
        Object job = newLocalJob(2, pairId, 1, script, workDir, outDir.resolve("job.log"));

        try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
            stubHappyDatabase(db, pairId);
            invokeExecuteJob(backend, job);

            // Simulate CPU hot-unplug after execution finished and before monitor ingests.
            reader.setOnlineCpus(List.of(backupCpu));
            monitor.pollOnceForTesting();

            assertTrue(monitor.isIngestionBlocked(pairId));
            verifyNoScientificWrites(db);

            ArgumentCaptor<CpuAttestationRecord> captor =
                ArgumentCaptor.forClass(CpuAttestationRecord.class);
            db.verify(() -> JobPairs.recordAttemptCpuAttestation(captor.capture()));
            assertEquals(CpuAttestationRecord.Status.VIOLATED, captor.getValue().getAttestationStatus());
            assertTrue(captor.getValue().getViolationReason().contains("no longer valid"));
        }
    }

    @Test
    public void postLaunchRuntimeCpusetChangeKillsJobAndBlocksIngestion() throws Exception {
        int realCpu = detectRealAllowedOnlineCpu();
        int migratedCpu = realCpu + 200;
        MutableCpuReader reader = new MutableCpuReader(
            List.of(realCpu, migratedCpu),
            List.of(realCpu, migratedCpu),
            Optional.of(List.of(realCpu, migratedCpu)),
            null
        );
        AtomicBoolean shrinkAfterStart = new AtomicBoolean(true);
        reader.setOnProcessRead(() -> {
            if (shrinkAfterStart.compareAndSet(true, false)) {
                // Shrink cgroup cpuset away from realCpu while the solver is running.
                reader.setCgroupEffectiveCpuset(Optional.of(List.of(migratedCpu)));
            }
        });

        AtomicLong clock = new AtomicLong(1_000_000_000L);
        LocalJobMonitor monitor = new LocalJobMonitor();
        LocalBackend backend = createBackend(
            Integer.toString(realCpu),
            reader,
            Map.of(realCpu, List.of(realCpu)),
            clock,
            250_000_000L,
            monitor
        );

        int pairId = 503;
        Path outDir = Files.createDirectory(tempDir.resolve("cpuset-run"));
        Path workDir = Files.createDirectory(tempDir.resolve("cpuset-work"));
        Path script = writeCompleteSolverScript(outDir, pairId, "sleep 0.2");
        Object job = newLocalJob(3, pairId, 2, script, workDir, outDir.resolve("job.log"));

        try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
            stubHappyDatabase(db, pairId);
            invokeExecuteJob(backend, job);

            // Invalidated core must be withheld from availableCores.
            assertFalse(availableCores(backend).contains(realCpu));

            // Even if a result artifact landed on disk after migration, monitor blocks ingestion.
            Files.writeString(
                outDir.resolve("status.json"),
                "{\"pairId\":" + pairId + ",\"status\":"
                    + StatusCode.STATUS_COMPLETE.getVal() + ",\"stageNumber\":1,\"timestamp\":2}\n"
            );
            monitor.pollOnceForTesting();

            assertTrue(monitor.isIngestionBlocked(pairId));
            verifyNoScientificWrites(db);
            ArgumentCaptor<CpuAttestationRecord> captor =
                ArgumentCaptor.forClass(CpuAttestationRecord.class);
            db.verify(() -> JobPairs.recordAttemptCpuAttestation(captor.capture()));
            assertEquals(CpuAttestationRecord.Status.VIOLATED, captor.getValue().getAttestationStatus());
        }
    }

    @Test
    public void processAffinityWideningDuringExecutionLatchesViolationAndBlocksIngestion()
            throws Exception {
        int realCpu = detectRealAllowedOnlineCpu();
        MutableCpuReader reader = new MutableCpuReader(
            List.of(realCpu, realCpu + 1),
            List.of(realCpu, realCpu + 1),
            Optional.of(List.of(realCpu, realCpu + 1)),
            null
        );
        AtomicInteger processReads = new AtomicInteger(0);
        reader.setProcessAllowedOverride(pid -> {
            if (processReads.incrementAndGet() == 1) {
                return Optional.of(List.of(realCpu));
            }
            return Optional.of(List.of(realCpu, realCpu + 1));
        });

        AtomicLong clock = new AtomicLong(1_000_000_000L);
        LocalJobMonitor monitor = new LocalJobMonitor();
        LocalBackend backend = createBackend(
            Integer.toString(realCpu),
            reader,
            Map.of(realCpu, List.of(realCpu)),
            clock,
            0L,
            monitor
        );

        int pairId = 504;
        Path outDir = Files.createDirectory(tempDir.resolve("pid-widen"));
        Path workDir = Files.createDirectory(tempDir.resolve("pid-widen-work"));
        Path script = writeCompleteSolverScript(outDir, pairId, "sleep 0.2");
        Object job = newLocalJob(4, pairId, 1, script, workDir, outDir.resolve("job.log"));

        try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
            stubHappyDatabase(db, pairId);
            invokeExecuteJob(backend, job);

            Files.writeString(
                outDir.resolve("status.json"),
                "{\"pairId\":" + pairId + ",\"status\":"
                    + StatusCode.STATUS_COMPLETE.getVal() + ",\"stageNumber\":1,\"timestamp\":3}\n"
            );
            monitor.pollOnceForTesting();

            assertTrue(monitor.isIngestionBlocked(pairId));
            verifyNoScientificWrites(db);
            ArgumentCaptor<CpuAttestationRecord> captor =
                ArgumentCaptor.forClass(CpuAttestationRecord.class);
            db.verify(() -> JobPairs.recordAttemptCpuAttestation(captor.capture()));
            assertEquals(CpuAttestationRecord.Status.VIOLATED, captor.getValue().getAttestationStatus());
            assertTrue(captor.getValue().getViolationReason().contains("Cpus_allowed_list"));
        }
    }

    @Test
    public void transientReadFailureRetriesWithoutRemovingLeaseCapacity() throws Exception {
        int realCpu = detectRealAllowedOnlineCpu();
        MutableCpuReader reader = new MutableCpuReader(
            List.of(realCpu),
            List.of(realCpu),
            Optional.of(List.of(realCpu)),
            null
        );
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        LocalJobMonitor monitor = new LocalJobMonitor();
        Field nanoField = LocalJobMonitor.class.getDeclaredField("nanoTime");
        nanoField.setAccessible(true);
        nanoField.set(monitor, (java.util.function.LongSupplier) clock::get);

        LocalBackend backend = createBackend(
            Integer.toString(realCpu),
            reader,
            Map.of(realCpu, List.of(realCpu)),
            clock,
            250_000_000L,
            monitor
        );

        // 1. Pre-launch transient read failure recovers within bounded retries; core stays leased.
        reader.failNextGlobalReads(2);

        int pairId = 505;
        Path outDir = Files.createDirectory(tempDir.resolve("transient-run"));
        Path workDir = Files.createDirectory(tempDir.resolve("transient-work"));
        Path script = writeCompleteSolverScript(outDir, pairId, "sleep 0.02");
        Object job = newLocalJob(5, pairId, 1, script, workDir, outDir.resolve("job.log"));

        try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
            stubHappyDatabase(db, pairId);
            invokeExecuteJob(backend, job);
            assertEquals(1, availableCores(backend).size(),
                "transient read failure must never drop leased core capacity");
            assertTrue(availableCores(backend).contains(realCpu));

            // 2. Ingestion-time transient read failure causes RetryableIngestionException (backoff),
            // leaving the pair tracked and unblocked, then succeeds once reads recover.
            reader.failNextGlobalReads(1);
            monitor.pollOnceForTesting();
            assertTrue(monitor.isTracking(pairId));
            assertFalse(monitor.isIngestionBlocked(pairId));
            verifyNoScientificWrites(db);

            // Advance clock past base backoff (1000ms) and poll after read recovers.
            clock.addAndGet(TimeUnit.SECONDS.toNanos(5));
            monitor.pollOnceForTesting();

            assertFalse(monitor.isTracking(pairId));
            assertFalse(monitor.isIngestionBlocked(pairId));
            db.verify(() -> JobPairs.setPairStatusPreciseResult(
                Mockito.eq(pairId),
                Mockito.eq(1),
                Mockito.eq(StatusCode.STATUS_COMPLETE.getVal()),
                Mockito.eq(StatusCode.STATUS_NOT_REACHED.getVal()),
                Mockito.eq(false),
                Mockito.eq(1)
            ));
            db.verify(() -> JobPairs.recordAttemptCpuAttestation(any(CpuAttestationRecord.class)),
                atLeastOnce());
        }

        // 3. Persistent pre-launch transient read failures still return the lease to availableCores.
        reader.failNextGlobalReads(5);
        int pair2 = 506;
        Path outDir2 = Files.createDirectory(tempDir.resolve("transient-run-2"));
        Path workDir2 = Files.createDirectory(tempDir.resolve("transient-work-2"));
        Path script2 = writeCompleteSolverScript(outDir2, pair2, "sleep 0.01");
        Object job2 = newLocalJob(6, pair2, 1, script2, workDir2, outDir2.resolve("job.log"));
        try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
            stubHappyDatabase(db, pair2);
            invokeExecuteJob(backend, job2);
            assertEquals(1, availableCores(backend).size(),
                "persistent transient failure before launch must restore core to pool");
            assertTrue(availableCores(backend).contains(realCpu));
            verifyNoScientificWrites(db);
        }
    }

    @Test
    public void staleLeaseFailsOverToRemainingValidLeaseWithinDeadline() throws Exception {
        int realCpu = detectRealAllowedOnlineCpu();
        int badCpu = realCpu + 900;
        MutableCpuReader reader = new MutableCpuReader(
            List.of(badCpu, realCpu),
            List.of(badCpu, realCpu),
            Optional.of(List.of(badCpu, realCpu)),
            null
        );
        Map<Integer, List<Integer>> topology = new LinkedHashMap<>();
        topology.put(badCpu, List.of(badCpu));
        topology.put(realCpu, List.of(realCpu));

        AtomicLong clock = new AtomicLong(1_000_000_000L);
        LocalJobMonitor monitor = new LocalJobMonitor();
        LocalBackend backend = createBackend(
            badCpu + "," + realCpu,
            reader,
            topology,
            clock,
            250_000_000L,
            monitor
        );

        // Invalidate badCpu right before the accepted job acquires its lease; realCpu remains valid.
        reader.setOnlineCpus(List.of(realCpu));

        int pairId = 507;
        Path outDir = Files.createDirectory(tempDir.resolve("failover-run"));
        Path workDir = Files.createDirectory(tempDir.resolve("failover-work"));
        Path marker = tempDir.resolve("ran-on-valid-core.txt");
        Path script = writeCompleteSolverScript(
            outDir, pairId, "awk '/^Cpus_allowed_list:/ { print $2 }' /proc/self/status > '"
                + marker + "'"
        );
        Object job = newLocalJob(7, pairId, 1, script, workDir, outDir.resolve("job.log"));

        try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
            stubHappyDatabase(db, pairId);
            invokeExecuteJob(backend, job);
            monitor.pollOnceForTesting();

            assertTrue(Files.exists(marker), "job must continue on the remaining valid lease");
            assertEquals(Integer.toString(realCpu), Files.readString(marker).trim());
            assertFalse(availableCores(backend).contains(badCpu));
            assertTrue(availableCores(backend).contains(realCpu));
            assertFalse(monitor.isIngestionBlocked(pairId));

            ArgumentCaptor<CpuAttestationRecord> captor =
                ArgumentCaptor.forClass(CpuAttestationRecord.class);
            db.verify(() -> JobPairs.recordAttemptCpuAttestation(captor.capture()));
            assertEquals(CpuAttestationRecord.Status.ATTESTED, captor.getValue().getAttestationStatus());
            assertEquals(Integer.toString(realCpu), captor.getValue().getLeasedCpus());
        }
    }

    @Test
    public void allInvalidatedLeasesFailFastWithoutHangingDeadline() throws Exception {
        int realCpu = detectRealAllowedOnlineCpu();
        MutableCpuReader reader = new MutableCpuReader(
            List.of(realCpu),
            List.of(realCpu),
            Optional.of(List.of(realCpu)),
            null
        );
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        LocalJobMonitor monitor = new LocalJobMonitor();
        LocalBackend backend = createBackend(
            Integer.toString(realCpu),
            reader,
            Map.of(realCpu, List.of(realCpu)),
            clock,
            250_000_000L,
            monitor
        );

        reader.setOnlineCpus(List.of(realCpu + 50));
        int pairId = 508;
        Path outDir = Files.createDirectory(tempDir.resolve("all-bad-run"));
        Path workDir = Files.createDirectory(tempDir.resolve("all-bad-work"));
        Path script = writeCompleteSolverScript(outDir, pairId, "exit 0");
        Object job = newLocalJob(8, pairId, 1, script, workDir, outDir.resolve("job.log"));

        long startNanos = System.nanoTime();
        try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
            stubHappyDatabase(db, pairId);
            invokeExecuteJob(backend, job);
            verifyNoScientificWrites(db);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        assertTrue(elapsedMs < 2000L, "all-invalidated pool must fail fast, took " + elapsedMs + "ms");
        assertTrue(availableCores(backend).isEmpty());
    }

    @Test
    public void linuxEnvironmentReaderParsesFakeProcAndSysfsFiles() throws Exception {
        Path procSelf = tempDir.resolve("self_status");
        Path cpuOnline = tempDir.resolve("cpu_online");
        Path cgroupCpuset = tempDir.resolve("cpuset.cpus.effective");
        Path procRoot = Files.createDirectory(tempDir.resolve("proc"));
        Path pidDir = Files.createDirectory(procRoot.resolve("4242"));
        Path pidStatus = pidDir.resolve("status");

        Files.writeString(procSelf, "Name:\tjava\nCpus_allowed_list:\t2,4\n");
        Files.writeString(cpuOnline, "0-7\n");
        Files.writeString(cgroupCpuset, "2,4,6\n");
        Files.writeString(pidStatus, "Name:\tsolver\nCpus_allowed_list:\t4\n");

        LocalCpuAttestation.LinuxCpuEnvironmentReader reader =
            new LocalCpuAttestation.LinuxCpuEnvironmentReader(
                procSelf, cpuOnline, cgroupCpuset, procRoot
            );

        LocalCpuAttestation.SystemSnapshot snap = reader.readSystemSnapshot();
        assertTrue(snap.isCpuValid(2));
        assertTrue(snap.isCpuValid(4));
        assertFalse(snap.isCpuValid(6));
        assertEquals("2,4", snap.getCombinedEffectiveStr());
        assertEquals(Optional.of(List.of(4)), reader.readProcessAllowedCpus(4242L));
        assertEquals(Optional.empty(), reader.readProcessAllowedCpus(99999L));

        Files.writeString(cpuOnline, "");
        assertThrows(
            LocalCpuAttestation.CpuStateReadException.class,
            reader::readOnlineCpus
        );
    }

    /** Thread-safe controllable {@link LocalCpuAttestation.CpuEnvironmentReader} for tests. */
    private static final class MutableCpuReader implements LocalCpuAttestation.CpuEnvironmentReader {
        private final AtomicReference<List<Integer>> affinity;
        private final AtomicReference<List<Integer>> online;
        private final AtomicReference<Optional<List<Integer>>> cgroupCpuset;
        private final AtomicInteger remainingGlobalReadFailures = new AtomicInteger(0);
        private volatile Runnable onProcessRead;
        private volatile java.util.function.Function<Long, Optional<List<Integer>>> processOverride;

        MutableCpuReader(
                List<Integer> affinity,
                List<Integer> online,
                Optional<List<Integer>> cgroupCpuset,
                Runnable onProcessRead) {
            this.affinity = new AtomicReference<>(affinity);
            this.online = new AtomicReference<>(online);
            this.cgroupCpuset = new AtomicReference<>(cgroupCpuset);
            this.onProcessRead = onProcessRead;
        }

        void failNextGlobalReads(int count) {
            remainingGlobalReadFailures.set(count);
        }

        void setOnlineCpus(List<Integer> onlineCpus) {
            online.set(onlineCpus);
        }

        void setCgroupEffectiveCpuset(Optional<List<Integer>> cpuset) {
            cgroupCpuset.set(cpuset);
        }

        void setOnProcessRead(Runnable hook) {
            this.onProcessRead = hook;
        }

        void setProcessAllowedOverride(
                java.util.function.Function<Long, Optional<List<Integer>>> override) {
            this.processOverride = override;
        }

        @Override
        public List<Integer> readEffectiveAffinity()
                throws LocalCpuAttestation.CpuStateReadException {
            maybeFailGlobal();
            return affinity.get();
        }

        @Override
        public List<Integer> readOnlineCpus() throws LocalCpuAttestation.CpuStateReadException {
            return online.get();
        }

        @Override
        public Optional<List<Integer>> readCgroupEffectiveCpuset() {
            return cgroupCpuset.get();
        }

        @Override
        public Optional<List<Integer>> readProcessAllowedCpus(long pid)
                throws LocalCpuAttestation.CpuStateReadException {
            Runnable hook = onProcessRead;
            if (hook != null) {
                hook.run();
            }
            var override = processOverride;
            if (override != null) {
                return override.apply(pid);
            }
            return LocalCpuAttestation.LinuxCpuEnvironmentReader.DEFAULT.readProcessAllowedCpus(pid);
        }

        private void maybeFailGlobal() throws LocalCpuAttestation.CpuStateReadException {
            int remaining = remainingGlobalReadFailures.get();
            while (remaining > 0) {
                if (remainingGlobalReadFailures.compareAndSet(remaining, remaining - 1)) {
                    throw new LocalCpuAttestation.CpuStateReadException(
                        "simulated transient /proc or sysfs read failure"
                    );
                }
                remaining = remainingGlobalReadFailures.get();
            }
        }
    }
}
