package org.starexec.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.GracePeriodConfigurable;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.*;
import io.fabric8.kubernetes.client.extension.ExtensibleResource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.starexec.data.database.SolverBuildArtifacts;

class KubernetesBuildStopCleanupTest {
    private final KubernetesNativeBackend backend = new KubernetesNativeBackend();
    private final KubernetesClient client = mock(KubernetesClient.class);
    private Job job;
    private Pod pod;
    private GracePeriodConfigurable<Deletable> deletion;
    private ExtensibleResource<Job> buildDeletion;
    private boolean unavailablePods;

    @BeforeEach @SuppressWarnings({"rawtypes", "unchecked"})
    void setup() throws Exception {
        job = KubernetesBuildStopObserverTest.finishedJob(); pod = KubernetesBuildStopObserverTest.finishedPod();
        for (var entry : java.util.Map.of("namespace", (Object) "starexec", "kubernetesClient", client).entrySet()) {
            var field = KubernetesNativeBackend.class.getDeclaredField(entry.getKey());
            field.setAccessible(true); field.set(backend, entry.getValue());
        }
        BatchAPIGroupDSL batch = mock(BatchAPIGroupDSL.class); V1BatchAPIGroupDSL v1 = mock(V1BatchAPIGroupDSL.class);
        MixedOperation jobs = mock(MixedOperation.class); NonNamespaceOperation scope = mock(NonNamespaceOperation.class);
        ScalableResource<Job> resource = mock(ScalableResource.class);
        when(client.batch()).thenReturn(batch); when(batch.v1()).thenReturn(v1); when(v1.jobs()).thenReturn(jobs);
        when(jobs.inNamespace("starexec")).thenReturn(scope); when(scope.withName("build-job")).thenReturn(resource);
        when(scope.withLabel("starexec.org/managed", "true")).thenReturn(scope);
        when(scope.withoutLabel(KubernetesBuildStopObserver.ARTIFACT_LABEL)).thenReturn(scope);
        when(scope.list()).thenAnswer(call -> new io.fabric8.kubernetes.api.model.batch.v1.JobListBuilder().withItems(job).build());
        when(resource.get()).thenAnswer(call -> job);
        deletion = mock(GracePeriodConfigurable.class);
        doReturn(deletion).when(resource).withPropagationPolicy(DeletionPropagation.FOREGROUND);
        doReturn(mock(GracePeriodConfigurable.class)).when(scope).withPropagationPolicy(DeletionPropagation.FOREGROUND);
        ScalableResource<Job> guarded = mock(ScalableResource.class, withSettings().extraInterfaces(ExtensibleResource.class));
        buildDeletion = (ExtensibleResource<Job>) guarded;
        when(scope.resource(any(Job.class))).thenReturn(guarded);
        when(buildDeletion.withPropagationPolicy(DeletionPropagation.FOREGROUND)).thenReturn(buildDeletion);
        when(buildDeletion.lockResourceVersion("17")).thenReturn(buildDeletion);
        MixedOperation pods = mock(MixedOperation.class); NonNamespaceOperation podScope = mock(NonNamespaceOperation.class);
        when(client.pods()).thenReturn(pods); when(pods.inNamespace("starexec")).thenReturn(podScope);
        when(podScope.withLabels(anyMap())).thenReturn(podScope);
        when(podScope.list()).thenAnswer(call -> unavailablePods ? null : new PodListBuilder().withItems(pod).build());
    }

    private void delete() throws Exception {
        Method method = KubernetesNativeBackend.class.getDeclaredMethod("deleteKubernetesJob", Job.class);
        method.setAccessible(true);
        try { method.invoke(backend, job); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw (Error) failure.getCause();
        }
    }

    @Test void cleanupCannotDeleteBeforeTheMatchingStopReceiptWasCommitted() throws Exception {
        UUID token = UUID.randomUUID(); AtomicBoolean committed = new AtomicBoolean();
        try (var dao = mockStatic(SolverBuildArtifacts.class)) {
            var artifact = new SolverBuildArtifacts.Artifact(5, 42, 1, 9, 9, 10, 1, "/data/source", "/data/staging",
                    null, null, token, "kubernetes:starexec:7:job-uid", null, "PLANNED");
            dao.when(() -> SolverBuildArtifacts.find(42, 1)).thenReturn(Optional.of(artifact));
            dao.when(() -> SolverBuildArtifacts.findStopReceipt(42, 1)).thenReturn(Optional.empty());
            dao.when(() -> SolverBuildArtifacts.recordStopReceipt(eq(42), eq(1), eq(token),
                    eq("kubernetes:starexec:7:job-uid"), any())).thenAnswer(call -> { committed.set(true); return true; });
            doAnswer(call -> { assertTrue(committed.get(), "stop receipt must commit before Job deletion"); return List.of(); })
                    .when(buildDeletion).delete();
            delete();
            verify(buildDeletion).delete(); assertTrue(committed.get());
            verify(deletion, never()).delete();
            verify(buildDeletion).lockResourceVersion("17");
        }
    }

    @Test void failedPodPhaseWithRunningContainerCannotAuthorizeDestructiveCleanup() throws Exception {
        pod.getStatus().setPhase("Failed");
        pod.getStatus().getContainerStatuses().get(0).setState(new ContainerStateBuilder().withNewRunning().endRunning().build());
        try (var dao = mockStatic(SolverBuildArtifacts.class)) {
            delete();
            verify(deletion, never()).delete();
            verify(buildDeletion, never()).delete();
            dao.verify(() -> SolverBuildArtifacts.recordStopReceipt(anyInt(), anyInt(), any(), anyString(), any()), never());
        }
    }

    @Test void failedPersistenceKeepsTheVersionedJobAndNeverUsesLegacyDeletion() throws Exception {
        try (var dao = mockStatic(SolverBuildArtifacts.class)) {
            dao.when(() -> SolverBuildArtifacts.find(42, 1)).thenThrow(new java.sql.SQLException("unavailable"));
            delete();
            verify(deletion, never()).delete(); verify(buildDeletion, never()).delete();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name) throws Exception {
        var field = KubernetesNativeBackend.class.getDeclaredField(name); field.setAccessible(true); return (T) field.get(backend);
    }

    private void primeBuild() throws Exception {
        job.getMetadata().setAnnotations(java.util.Map.of("starexec.org/output-dir", "/outputs/build"));
        var remember = KubernetesNativeBackend.class.getDeclaredMethod("rememberBuild", Job.class);
        remember.setAccessible(true); remember.invoke(backend, job);
        this.<java.util.Set<Integer>>field("jobsHoldingSlot").add(7);
        this.<AtomicInteger>field("activeJobCount").set(1);
    }

    @ParameterizedTest @ValueSource(strings = {"running", "missing", "pods", "database"})
    void unresolvedBuildStopRetainsCapacityAndReconstructsItsFullTracking(String reason) throws Exception {
        primeBuild();
        if (reason.equals("running")) job.getStatus().getConditions().get(0).setStatus("False");
        if (reason.equals("missing")) job = null;
        if (reason.equals("pods")) unavailablePods = true;
        try (var dao = mockStatic(SolverBuildArtifacts.class)) {
            dao.when(() -> SolverBuildArtifacts.find(42, 1)).thenThrow(new java.sql.SQLException("unavailable"));
            var release = KubernetesNativeBackend.class.getDeclaredMethod("releaseSubmissionSlot", int.class);
            release.setAccessible(true); release.invoke(backend, 7);
            assertEquals(1, this.<AtomicInteger>field("activeJobCount").get());
            assertTrue(this.<java.util.Set<Integer>>field("jobsHoldingSlot").contains(7));
            assertEquals("build-job", this.<java.util.Map<Integer, String>>field("execIdToJobName").get(7));
            assertEquals("job-uid", this.<java.util.Map<Integer, String>>field("execIdToJobUid").get(7));
            assertEquals(42, this.<java.util.Map<Integer, Integer>>field("execIdToPairId").get(7));
            assertEquals(1, this.<java.util.Map<Integer, Integer>>field("execIdToAttemptNo").get(7));
            assertEquals(java.nio.file.Path.of("/outputs/build"), this.<java.util.Map<Integer, java.nio.file.Path>>field("execIdToOutputDir").get(7));
        }
    }

    @Test void aCompletionCannotWriteScientificMetadataBeforePhysicalStopWasRecorded() throws Exception {
        primeBuild();
        this.<java.util.Map<Integer, String>>field("execIdToJobName").put(7, "build-job");
        this.<java.util.Map<Integer, String>>field("execIdToJobUid").put(7, "job-uid");
        this.<java.util.Map<Integer, Integer>>field("execIdToPairId").put(7, 42);
        this.<java.util.Map<Integer, Integer>>field("execIdToAttemptNo").put(7, 1);
        pod.getStatus().getContainerStatuses().get(0).setState(new ContainerStateBuilder().withNewRunning().endRunning().build());
        Class<?> type = Class.forName("org.starexec.backend.KubernetesNativeBackend$KubernetesJobCompletionCallback");
        var ctor = type.getDeclaredConstructor(KubernetesNativeBackend.class); ctor.setAccessible(true);
        var completion = type.getDeclaredMethod("onJobComplete", ExecutionRef.class); completion.setAccessible(true);
        try (var pairs = mockStatic(org.starexec.data.database.JobPairs.class);
             var dao = mockStatic(SolverBuildArtifacts.class)) {
            assertEquals(false, completion.invoke(ctor.newInstance(backend), new ExecutionRef(7, "build-job", "job-uid")));
            pairs.verifyNoInteractions();
            verify(buildDeletion, never()).delete();
            assertEquals(1, this.<AtomicInteger>field("activeJobCount").get());
        }
    }

    @Test void anUntrackedMarkedBuildCannotBeReportedStoppedByBulkDeletion() throws Exception {
        job.getStatus().getConditions().get(0).setStatus("False");
        assertFalse(backend.killAll());
        assertEquals(1, this.<AtomicInteger>field("activeJobCount").get());
        assertEquals("build-job", this.<java.util.Map<Integer, String>>field("execIdToJobName").get(7));
        verify(buildDeletion, never()).delete(); verify(deletion, never()).delete();
    }

    @Test void shutdownAccountingResetDoesNotCreatePhysicalStopEvidence() throws Exception {
        primeBuild();
        try (var dao = mockStatic(SolverBuildArtifacts.class)) {
            var reset = KubernetesNativeBackend.class.getDeclaredMethod("releaseAllSlots"); reset.setAccessible(true);
            reset.invoke(backend);
            assertEquals(0, this.<AtomicInteger>field("activeJobCount").get());
            assertTrue(this.<java.util.Set<Integer>>field("jobsHoldingSlot").isEmpty());
            assertTrue(this.<java.util.Set<ExecutionRef>>field("conservedBuilds").isEmpty());
            dao.verifyNoInteractions();
        }
    }
}
