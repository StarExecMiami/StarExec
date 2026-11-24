package org.starexec.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mockStatic;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.Cluster;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.JobPairs.PairStatusLookupResult;
import org.starexec.data.database.JobPairs.PairStatusLookupState;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.database.StageStatusBatchResult;
import org.starexec.data.to.Status.StatusCode;

/**
 * Unit tests for {@link KubernetesCpuAttestation}, {@link KubernetesJobMonitor} attestation wiring,
 * and {@code cpu-topology.json} evaluation rules (#286).
 */
public class KubernetesCpuAttestationTest {

    @TempDir Path tempDir;

    @Test
    public void attestsCompletePhysicalCoresWhenStrictContractSatisfied() throws Exception {
        Path outDir = Files.createDirectory(tempDir.resolve("attested-2cpu"));
        Files.writeString(
                outDir.resolve("cpu-topology.json"),
                "{\"pairId\":701,\"nodeName\":\"n021\","
                        + "\"cpusAllowedList\":\"2-3\","
                        + "\"cgroupEffectiveCpuset\":\"2-3\","
                        + "\"cgroupHierarchy\":\"v2\","
                        + "\"onlineCpus\":\"0-15\","
                        + "\"numaNodes\":\"0\","
                        + "\"cpus\":["
                        + "{\"cpu\":2,\"coreId\":1,\"packageId\":0,\"numaNode\":0,\"threadSiblings\":\"2-3\"},"
                        + "{\"cpu\":3,\"coreId\":1,\"packageId\":0,\"numaNode\":0,\"threadSiblings\":\"2-3\"}"
                        + "]}\n",
                StandardCharsets.UTF_8);

        CpuAttestationRecord record =
                KubernetesCpuAttestation.evaluate(outDir, 701, 1, "fallback-node", true, "2");
        assertNotNull(record);
        assertEquals(CpuAttestationRecord.Status.ATTESTED, record.getAttestationStatus());
        assertEquals("kubernetes", record.getBackendType());
        assertEquals("n021", record.getNodeName());
        assertEquals("strict", record.getIsolationMode());
        assertEquals(KubernetesCpuAttestation.MECHANISM_STATIC, record.getIsolationMechanism());
        assertTrue(record.isPhysicalExclusivityAttested());
        assertEquals("2-3", record.getLeasedCpus());
        assertEquals("2-3", record.getReservedSiblingCpus());
        assertEquals("2-3", record.getInitialEffectiveCpuset());
        assertEquals("2-3", record.getTerminalEffectiveCpuset());
        assertEquals("2-3", record.getObservedProcessCpuset());
        assertEquals(Integer.valueOf(1), record.getTopologyCoreId());
        assertEquals(Integer.valueOf(0), record.getTopologyPackageId());
        assertEquals("2-3", record.getTopologyThreadSiblings());
        assertTrue(record.getTopologySummary().contains("numa=0"));
        assertTrue(record.getTopologySummary().contains("cores=0:1"));
        assertNull(record.getViolationReason());
    }

    @Test
    public void attestsMultipleWholePhysicalCores() throws Exception {
        Path outDir = Files.createDirectory(tempDir.resolve("attested-4cpu"));
        Files.writeString(
                outDir.resolve("cpu-topology.json"),
                "{\"pairId\":702,\"nodeName\":\"n022\","
                        + "\"cpusAllowedList\":\"2-5\","
                        + "\"cgroupEffectiveCpuset\":\"2-5\","
                        + "\"cgroupHierarchy\":\"v2\","
                        + "\"onlineCpus\":\"0-15\","
                        + "\"numaNodes\":\"0\","
                        + "\"cpus\":["
                        + "{\"cpu\":2,\"coreId\":1,\"packageId\":0,\"numaNode\":0,\"threadSiblings\":\"2-3\"},"
                        + "{\"cpu\":3,\"coreId\":1,\"packageId\":0,\"numaNode\":0,\"threadSiblings\":\"2-3\"},"
                        + "{\"cpu\":4,\"coreId\":2,\"packageId\":0,\"numaNode\":0,\"threadSiblings\":\"4-5\"},"
                        + "{\"cpu\":5,\"coreId\":2,\"packageId\":0,\"numaNode\":0,\"threadSiblings\":\"4-5\"}"
                        + "]}\n",
                StandardCharsets.UTF_8);

        CpuAttestationRecord record =
                KubernetesCpuAttestation.evaluate(outDir, 702, 1, null, true, "4");
        assertNotNull(record);
        assertEquals(CpuAttestationRecord.Status.ATTESTED, record.getAttestationStatus());
        assertTrue(record.isPhysicalExclusivityAttested());
        assertNull(record.getTopologyCoreId());
        assertEquals(Integer.valueOf(0), record.getTopologyPackageId());
        assertTrue(record.getTopologySummary().contains("cores=0:1,0:2"));
    }

    @Test
    public void marksSharedNodeCpusetUnqualifiedInStrictMode() throws Exception {
        Path outDir = Files.createDirectory(tempDir.resolve("shared-cpuset"));
        Files.writeString(
                outDir.resolve("cpu-topology.json"),
                "{\"pairId\":703,\"nodeName\":\"n023\","
                        + "\"cpusAllowedList\":\"0-15\","
                        + "\"cgroupEffectiveCpuset\":\"0-15\","
                        + "\"cgroupHierarchy\":\"v2\","
                        + "\"onlineCpus\":\"0-15\","
                        + "\"cpus\":[]}\n",
                StandardCharsets.UTF_8);

        CpuAttestationRecord record =
                KubernetesCpuAttestation.evaluate(outDir, 703, 1, null, true, "2");
        assertNotNull(record);
        assertEquals(CpuAttestationRecord.Status.UNQUALIFIED, record.getAttestationStatus());
        assertEquals("strict", record.getIsolationMode());
        assertEquals(KubernetesCpuAttestation.MECHANISM_SHARED, record.getIsolationMechanism());
        assertFalse(record.isPhysicalExclusivityAttested());
        assertTrue(record.getViolationReason().contains("cpuManagerPolicy=none"));
    }

    @Test
    public void marksUnreservedAllOnlineCpusUnqualified() throws Exception {
        Path outDir = Files.createDirectory(tempDir.resolve("no-reserved"));
        Files.writeString(
                outDir.resolve("cpu-topology.json"),
                "{\"pairId\":704,\"nodeName\":\"n024\","
                        + "\"cpusAllowedList\":\"0-3\","
                        + "\"cgroupEffectiveCpuset\":\"0-3\","
                        + "\"cgroupHierarchy\":\"v2\","
                        + "\"onlineCpus\":\"0-3\","
                        + "\"cpus\":["
                        + "{\"cpu\":0,\"coreId\":0,\"packageId\":0,\"numaNode\":0,\"threadSiblings\":\"0-1\"},"
                        + "{\"cpu\":1,\"coreId\":0,\"packageId\":0,\"numaNode\":0,\"threadSiblings\":\"0-1\"},"
                        + "{\"cpu\":2,\"coreId\":1,\"packageId\":0,\"numaNode\":0,\"threadSiblings\":\"2-3\"},"
                        + "{\"cpu\":3,\"coreId\":1,\"packageId\":0,\"numaNode\":0,\"threadSiblings\":\"2-3\"}"
                        + "]}\n",
                StandardCharsets.UTF_8);

        CpuAttestationRecord record =
                KubernetesCpuAttestation.evaluate(outDir, 704, 1, null, true, "4");
        assertNotNull(record);
        assertEquals(CpuAttestationRecord.Status.UNQUALIFIED, record.getAttestationStatus());
        assertFalse(record.isPhysicalExclusivityAttested());
        assertTrue(record.getViolationReason().contains("reserved system CPUs"));
    }

    @Test
    public void marksPartialSmtAllocationUnqualified() throws Exception {
        Path outDir = Files.createDirectory(tempDir.resolve("partial-smt"));
        Files.writeString(
                outDir.resolve("cpu-topology.json"),
                "{\"pairId\":705,\"nodeName\":\"n025\","
                        + "\"cpusAllowedList\":\"0,2\","
                        + "\"cgroupEffectiveCpuset\":\"0,2\","
                        + "\"cgroupHierarchy\":\"v2\","
                        + "\"onlineCpus\":\"0-7\","
                        + "\"cpus\":["
                        + "{\"cpu\":0,\"coreId\":0,\"packageId\":0,\"numaNode\":0,\"threadSiblings\":\"0-1\"},"
                        + "{\"cpu\":2,\"coreId\":1,\"packageId\":0,\"numaNode\":0,\"threadSiblings\":\"2-3\"}"
                        + "]}\n",
                StandardCharsets.UTF_8);

        CpuAttestationRecord record =
                KubernetesCpuAttestation.evaluate(outDir, 705, 1, null, true, "2");
        assertNotNull(record);
        assertEquals(CpuAttestationRecord.Status.UNQUALIFIED, record.getAttestationStatus());
        assertFalse(record.isPhysicalExclusivityAttested());
        assertTrue(record.getViolationReason().contains("Partial SMT"));
        assertTrue(record.getViolationReason().contains("full-pcpus-only"));
    }

    @Test
    public void marksMissingAndMalformedTopologyArtifactUnqualified() throws Exception {
        Path missingDir = Files.createDirectory(tempDir.resolve("missing-artifact"));
        CpuAttestationRecord missingRec =
                KubernetesCpuAttestation.evaluate(missingDir, 706, 1, "n021", true, "2");
        assertNotNull(missingRec);
        assertEquals(CpuAttestationRecord.Status.UNQUALIFIED, missingRec.getAttestationStatus());
        assertFalse(missingRec.isPhysicalExclusivityAttested());
        assertEquals("n021", missingRec.getNodeName());
        assertTrue(missingRec.getViolationReason().contains("Missing cpu-topology.json"));

        Path badDir = Files.createDirectory(tempDir.resolve("bad-artifact"));
        Files.writeString(badDir.resolve("cpu-topology.json"), "{not-valid-json", StandardCharsets.UTF_8);
        CpuAttestationRecord badRec =
                KubernetesCpuAttestation.evaluate(badDir, 707, 1, "n021", true, "2");
        assertNotNull(badRec);
        assertEquals(CpuAttestationRecord.Status.UNQUALIFIED, badRec.getAttestationStatus());
        assertFalse(badRec.isPhysicalExclusivityAttested());
        assertTrue(badRec.getViolationReason().contains("Malformed cpu-topology.json"));
    }

    @Test
    public void marksNonStrictModeUnqualifiedWithSharedMechanism() throws Exception {
        Path outDir = Files.createDirectory(tempDir.resolve("non-strict"));
        Files.writeString(
                outDir.resolve("cpu-topology.json"),
                "{\"pairId\":708,\"nodeName\":\"n021\","
                        + "\"cpusAllowedList\":\"2-3\",\"cgroupEffectiveCpuset\":\"2-3\"}\n",
                StandardCharsets.UTF_8);

        CpuAttestationRecord record =
                KubernetesCpuAttestation.evaluate(outDir, 708, 1, null, false, "1500m");
        assertNotNull(record);
        assertEquals(CpuAttestationRecord.Status.UNQUALIFIED, record.getAttestationStatus());
        assertEquals("permissive", record.getIsolationMode());
        assertEquals(KubernetesCpuAttestation.MECHANISM_SHARED, record.getIsolationMechanism());
        assertFalse(record.isPhysicalExclusivityAttested());
        assertTrue(record.getViolationReason().contains("disabled"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void completionCallbackIngestsResultAndPersistsCpuAttestation() throws Exception {
        Path outDir = Files.createDirectory(tempDir.resolve("callback-ingest"));
        Files.createDirectories(outDir.resolve("stage-stats"));
        Files.createDirectories(outDir.resolve("stage-attributes"));
        int pairId = 709;
        int execId = 901;
        Files.writeString(
                outDir.resolve("status.json"),
                "{\"pairId\":" + pairId + ",\"status\":" + StatusCode.STATUS_COMPLETE.getVal()
                        + ",\"stageNumber\":1,\"timestamp\":1}\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                outDir.resolve("stage-stats/1.json"),
                "{\"pairId\":" + pairId + ",\"stageNumber\":1,\"wallclockTime\":0.1,"
                        + "\"cpuTime\":0.05,\"userTime\":0.04,\"systemTime\":0.01,"
                        + "\"maxVirtualMemory\":1024,\"maxResidentSetSize\":512,"
                        + "\"diskSize\":16,\"hostname\":\"n021\"}\n",
                StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("stage-attributes/1.txt"), "answer=sat\n", StandardCharsets.UTF_8);
        Files.writeString(
                outDir.resolve("cpu-topology.json"),
                "{\"pairId\":" + pairId + ",\"nodeName\":\"n021\","
                        + "\"cpusAllowedList\":\"0-7\",\"cgroupEffectiveCpuset\":\"0-7\","
                        + "\"onlineCpus\":\"0-7\",\"cpus\":[]}\n",
                StandardCharsets.UTF_8);

        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "cpuLimit", "2");
        setField(backend, "strictOnePairPerCpu", true);
        setField(backend, "namespace", "starexec");

        KubernetesJobMonitor monitor =
                new KubernetesJobMonitor(
                        Mockito.mock(KubernetesClient.class), "starexec", null);
        setField(backend, "jobMonitor", monitor);

        Job job =
                new JobBuilder()
                        .withNewMetadata()
                        .withName("starexec-job-" + execId)
                        .withUid("uid-" + execId)
                        .endMetadata()
                        .build();
        ExecutionRef execution = ExecutionRef.fromJob(execId, job);

        getMap(backend, "execIdToPairId").put(execId, pairId);
        getMap(backend, "execIdToJobName").put(execId, "starexec-job-" + execId);
        getMap(backend, "execIdToOutputDir").put(execId, outDir);
        getMap(backend, "execIdToAttemptNo").put(execId, 1);

        Constructor<?> ctor =
                Class.forName(
                                "org.starexec.backend.KubernetesNativeBackend$KubernetesJobCompletionCallback")
                        .getDeclaredConstructor(KubernetesNativeBackend.class);
        ctor.setAccessible(true);
        KubernetesJobMonitor.JobCompletionCallback callback =
                (KubernetesJobMonitor.JobCompletionCallback) ctor.newInstance(backend);

        Constructor<PairStatusLookupResult> lookupCtor =
                PairStatusLookupResult.class.getDeclaredConstructor(
                        PairStatusLookupState.class, int.class);
        lookupCtor.setAccessible(true);
        PairStatusLookupResult runningLookup =
                lookupCtor.newInstance(
                        PairStatusLookupState.FOUND, StatusCode.STATUS_RUNNING.getVal());

        try (MockedStatic<JobPairs> db = mockStatic(JobPairs.class);
                MockedStatic<Cluster> cluster = mockStatic(Cluster.class)) {
            cluster.when(() -> Cluster.getNodeIdByName("n021")).thenReturn(21);
            db.when(() -> JobPairs.getPairStatusLookup(pairId)).thenReturn(runningLookup);
            db.when(() -> JobPairs.getStageNumbers(pairId)).thenReturn(Set.of(1));
            db.when(() -> JobPairs.getCurrentAttemptNo(pairId)).thenReturn(1);
            db.when(
                            () ->
                                    JobPairs.setPairStatusPreciseResult(
                                            anyInt(), anyInt(), anyInt(), anyInt(), anyBoolean(), any()))
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
            db.when(
                            () ->
                                    JobPairs.addJobPairAttributes(
                                            anyInt(),
                                            anyInt(),
                                            any(),
                                            org.mockito.ArgumentMatchers.<Integer>any()))
                    .thenReturn(true);
            db.when(() -> JobPairs.recordAttemptCpuAttestation(any())).thenReturn(true);

            // Even on an unqualified shared-cpuset cluster (like Quokka prior to Ansible static
            // rollout), scientific status + stats ingest normally while recording UNQUALIFIED.
            assertTrue(callback.onJobComplete(execution));

            ArgumentCaptor<CpuAttestationRecord> captor =
                    ArgumentCaptor.forClass(CpuAttestationRecord.class);
            db.verify(() -> JobPairs.recordAttemptCpuAttestation(captor.capture()));
            CpuAttestationRecord recorded = captor.getValue();
            assertEquals(CpuAttestationRecord.Status.UNQUALIFIED, recorded.getAttestationStatus());
            assertEquals(pairId, recorded.getPairId());
            assertEquals(Integer.valueOf(1), recorded.getAttemptNo());
            assertEquals("n021", recorded.getNodeName());
            assertFalse(recorded.isPhysicalExclusivityAttested());
        }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = KubernetesNativeBackend.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> getMap(Object target, String fieldName) throws Exception {
        Field field = KubernetesNativeBackend.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (ConcurrentHashMap<Object, Object>) field.get(target);
    }
}
