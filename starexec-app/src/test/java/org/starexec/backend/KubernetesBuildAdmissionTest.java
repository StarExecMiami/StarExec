package org.starexec.backend;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ReplaceDeletable;
import io.fabric8.kubernetes.client.dsl.ScalableResource;
import io.fabric8.kubernetes.client.dsl.BatchAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.V1BatchAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.starexec.data.database.SolverBuildArtifacts;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KubernetesBuildAdmissionTest {
    private final KubernetesNativeBackend backend = new KubernetesNativeBackend();
    private final KubernetesClient client = mock(KubernetesClient.class);
    private final BuildExecutionContext build = new BuildExecutionContext(5, 42, 1, UUID.randomUUID(),
            Path.of("/inputs/source"), Path.of("/outputs/build"));
    private final ExecutionRef execution = new ExecutionRef(7, "build-job", "uid-original");
    private final List<String> events = new ArrayList<>();
    private MockedStatic<SolverBuildArtifacts> artifacts;
    private ReplaceDeletable<Job> locked;
    private ScalableResource<Job> resource;
    private ScalableResource<Job> observation;
    private Job current;
    private Job replacement;

    private Job created() {
        return new JobBuilder().withNewMetadata().withName("build-job").withNamespace("starexec")
                .withUid("uid-original").withResourceVersion("17").withGeneration(1L)
                .withLabels(Map.of("starexec.org/managed", "true", "starexec.org/exec-id", "7",
                        "starexec.org/pair-id", "42", "starexec.org/attempt-no", "1",
                        KubernetesBuildStopObserver.ARTIFACT_LABEL, "5")).endMetadata()
                .withNewSpec().withSuspend(true)
                .withNewSelector().withMatchLabels(Map.of("batch.kubernetes.io/controller-uid", "uid-original")).endSelector()
                .withNewTemplate().withNewMetadata()
                    .withLabels(Map.of("batch.kubernetes.io/controller-uid", "uid-original"))
                    .withFinalizers(KubernetesBuildPodRetention.FINALIZER).endMetadata().endTemplate()
                .endSpec().build();
    }

    @BeforeEach @SuppressWarnings({"unchecked", "rawtypes"})
    void setup() throws Exception {
        for (var entry : java.util.Map.of("namespace", (Object) "starexec", "kubernetesClient", client).entrySet()) {
            var field = KubernetesNativeBackend.class.getDeclaredField(entry.getKey());
            field.setAccessible(true); field.set(backend, entry.getValue());
        }
        resource = mock(ScalableResource.class);
        observation = mock(ScalableResource.class);
        current = created();
        locked = mock(ReplaceDeletable.class);
        BatchAPIGroupDSL batch = mock(BatchAPIGroupDSL.class);
        V1BatchAPIGroupDSL v1 = mock(V1BatchAPIGroupDSL.class);
        MixedOperation jobs = mock(MixedOperation.class);
        NonNamespaceOperation namespaced = mock(NonNamespaceOperation.class);
        when(client.batch()).thenReturn(batch);
        when(batch.v1()).thenReturn(v1);
        when(v1.jobs()).thenReturn(jobs);
        when(jobs.inNamespace("starexec")).thenReturn(namespaced);
        when(namespaced.withName("build-job")).thenReturn(observation);
        when(observation.get()).thenAnswer(call -> current);
        doAnswer(call -> { replacement = call.getArgument(0); return resource; })
                .when(namespaced).resource(any(Job.class));
        when(resource.lockResourceVersion("17")).thenReturn(locked);
        when(locked.update()).thenAnswer(call -> { events.add("resume"); return replacement; });
        clearInvocations(client);
        artifacts = mockStatic(SolverBuildArtifacts.class);
        artifacts.when(() -> SolverBuildArtifacts.admitExecution(build, "kubernetes:starexec:7:uid-original"))
                .thenAnswer(call -> { events.add("commit"); return true; });
    }

    @AfterEach void close() { if (artifacts != null) artifacts.close(); }

    private void resume(Job job) throws Exception {
        Method method = KubernetesNativeBackend.class.getDeclaredMethod("resumeAdmittedBuild",
                BuildExecutionContext.class, ExecutionRef.class, Job.class);
        method.setAccessible(true);
        try { method.invoke(backend, build, execution, job); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw (Error) failure.getCause();
        }
    }

    @Test void commitsAdmissionBeforeResumingTheExactObservedVersionAndUid() throws Exception {
        Job observed = created();
        resume(observed);
        assertEquals(List.of("commit", "resume"), events);
        assertEquals("17", replacement.getMetadata().getResourceVersion());
        assertEquals("uid-original", replacement.getMetadata().getUid());
        assertEquals("build-job", replacement.getMetadata().getName());
        assertEquals("starexec", replacement.getMetadata().getNamespace());
        assertEquals(Boolean.FALSE, replacement.getSpec().getSuspend());
        assertEquals(Boolean.TRUE, observed.getSpec().getSuspend(), "the receipt remains unchanged");
        verify(locked, times(1)).update();
    }

    @Test void refusedAdmissionNeverSendsAResumeRequest() {
        artifacts.when(() -> SolverBuildArtifacts.admitExecution(build, "kubernetes:starexec:7:uid-original"))
                .thenReturn(false);
        assertThrows(IllegalStateException.class, () -> resume(created()));
        verify(client, never()).batch();
        verifyNoInteractions(locked);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = { " " })
    void anUnversionedCreationReceiptCannotAuthorizeResume(String version) {
        Job job = created(); job.getMetadata().setResourceVersion(version);
        assertThrows(IllegalStateException.class, () -> resume(job));
        artifacts.verifyNoInteractions(); verify(client, never()).batch();
    }

    @Test void anUnsuspendedCreationReceiptCannotAuthorizeResume() {
        Job job = created(); job.getSpec().setSuspend(false);
        assertThrows(IllegalStateException.class, () -> resume(job));
        artifacts.verifyNoInteractions(); verify(client, never()).batch();
    }

    @Test void aLaunchFencedJobCannotBeResumedByAnotherAdmissionAttempt() {
        Job job = created();
        job.getMetadata().setAnnotations(java.util.Map.of(KubernetesBuildLaunchFence.ANNOTATION, "16"));
        assertThrows(IllegalStateException.class, () -> resume(job));
        artifacts.verifyNoInteractions(); verify(client, never()).batch();
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "16"})
    void aCancellationMarkerPreventsAnotherAdmissionAttempt(String marker) {
        Job job = created();
        job.getMetadata().setAnnotations(java.util.Map.of(KubernetesBuildCancellation.ANNOTATION, marker));
        assertThrows(IllegalStateException.class, () -> resume(job));
        artifacts.verifyNoInteractions(); verify(client, never()).batch();
    }

    @Test void anAmbiguousUpdateIsNotRetriedAgainstAnotherJobVersion() {
        RuntimeException failure = new RuntimeException("ambiguous update");
        when(locked.update()).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> resume(created())));
        verify(locked, times(1)).update();
        assertEquals("17", replacement.getMetadata().getResourceVersion());
        assertEquals("uid-original", replacement.getMetadata().getUid());
    }

    @ParameterizedTest @ValueSource(strings = { "uid", "name" })
    void anotherResourceCannotSatisfyTheResumeReceipt(String changed) {
        when(locked.update()).thenAnswer(call -> {
            Job other = new JobBuilder(replacement).build();
            if (changed.equals("uid")) other.getMetadata().setUid("different-uid");
            else other.getMetadata().setName("different-name");
            return other;
        });
        assertThrows(IllegalStateException.class, () -> resume(created()));
        verify(locked, times(1)).update();
    }

    @Test void anEmptyResumeResponseIsUnresolved() {
        when(locked.update()).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> resume(created()));
        verify(locked, times(1)).update();
    }

    @Test void aStillSuspendedResumeResponseIsUnresolved() {
        when(locked.update()).thenReturn(created());
        assertThrows(IllegalStateException.class, () -> resume(created()));
        verify(locked, times(1)).update();
    }

    @Test void observesControllerStatusBeforeTheFirstResumeWrite() throws Exception {
        Job receipt = created();
        current.getMetadata().setResourceVersion("23");
        current.setStatus(new io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder()
                .addNewCondition().withType("Suspended").withStatus("True").endCondition().build());
        when(observation.get()).thenAnswer(call -> { events.add("observe"); return current; });
        when(resource.lockResourceVersion("23")).thenReturn(locked);

        resume(receipt);

        assertEquals(List.of("commit", "observe", "resume"), events);
        assertEquals("23", replacement.getMetadata().getResourceVersion());
        assertEquals("17", receipt.getMetadata().getResourceVersion());
        assertEquals(Boolean.TRUE, current.getSpec().getSuspend());
        assertEquals(current.getStatus(), replacement.getStatus());
        verify(observation, times(1)).get();
        verify(locked, times(1)).update();
        verify(resource, never()).lockResourceVersion("17");
    }

    @ParameterizedTest @ValueSource(strings = {"uid", "name", "namespace", "artifact", "pair", "attempt",
            "execution", "unmanaged", "missing", "metadata", "version", "blank-version", "generation",
            "later-generation", "deleting", "enabled", "spec", "selector", "template", "retention",
            "delegated", "manual", "started"})
    void aChangedCurrentJobCannotBeResumed(String changed) {
        switch (changed) {
            case "uid" -> current.getMetadata().setUid("another-uid");
            case "name" -> current.getMetadata().setName("another-job");
            case "namespace" -> current.getMetadata().setNamespace("another-namespace");
            case "artifact", "pair", "attempt", "execution", "unmanaged" -> {
                var labels = new java.util.HashMap<>(current.getMetadata().getLabels());
                String key = switch (changed) {
                    case "artifact" -> KubernetesBuildStopObserver.ARTIFACT_LABEL;
                    case "pair" -> "starexec.org/pair-id";
                    case "attempt" -> "starexec.org/attempt-no";
                    case "execution" -> "starexec.org/exec-id";
                    default -> "starexec.org/managed";
                };
                labels.put(key, changed.equals("unmanaged") ? "false" : "99");
                current.getMetadata().setLabels(labels);
            }
            case "missing" -> current = null;
            case "metadata" -> current.setMetadata(null);
            case "version" -> current.getMetadata().setResourceVersion(null);
            case "blank-version" -> current.getMetadata().setResourceVersion(" ");
            case "generation" -> current.getMetadata().setGeneration(null);
            case "later-generation" -> current.getMetadata().setGeneration(2L);
            case "deleting" -> current.getMetadata().setDeletionTimestamp("2026-01-01T00:00:00Z");
            case "enabled" -> current.getSpec().setSuspend(false);
            case "spec" -> current.setSpec(null);
            case "selector" -> current.getSpec().getSelector().setMatchLabels(Map.of("batch.kubernetes.io/controller-uid", "another-uid"));
            case "template" -> current.getSpec().getTemplate().getMetadata().setLabels(Map.of());
            case "retention" -> current.getSpec().getTemplate().getMetadata().setFinalizers(List.of());
            case "delegated" -> current.getSpec().setManagedBy("another.example/controller");
            case "manual" -> current.getSpec().setManualSelector(true);
            case "started" -> current.setStatus(new io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder()
                    .withStartTime("2026-01-01T00:00:00Z").build());
            default -> fail("Unknown fixture change");
        }
        assertThrows(IllegalStateException.class, () -> resume(created()));
        verify(observation, times(1)).get();
        verifyNoInteractions(locked);
    }

    @ParameterizedTest @ValueSource(strings = {"launch", "launch-empty", "cancel", "cancel-empty"})
    void aCurrentStopMarkerCannotBeOverwritten(String marker) {
        String key = marker.startsWith("launch") ? KubernetesBuildLaunchFence.ANNOTATION : KubernetesBuildCancellation.ANNOTATION;
        current.getMetadata().setAnnotations(Map.of(key, marker.endsWith("empty") ? "" : "16"));
        assertThrows(IllegalStateException.class, () -> resume(created()));
        verify(observation, times(1)).get();
        verifyNoInteractions(locked);
    }

    @Test void aFailedInitialObservationCannotSendAResumeRequest() {
        RuntimeException failure = new RuntimeException("Initial Job observation unavailable");
        when(observation.get()).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> resume(created())));
        verify(observation, times(1)).get();
        verifyNoInteractions(locked);
    }

    @Test void aConflictAtTheObservedVersionIsNotRetried() {
        current.getMetadata().setResourceVersion("23");
        when(resource.lockResourceVersion("23")).thenReturn(locked);
        var conflict = new io.fabric8.kubernetes.client.KubernetesClientException("Job changed", 409, null);
        when(locked.update()).thenThrow(conflict);

        assertSame(conflict, assertThrows(io.fabric8.kubernetes.client.KubernetesClientException.class, () -> resume(created())));
        assertEquals("23", replacement.getMetadata().getResourceVersion());
        verify(observation, times(1)).get();
        verify(locked, times(1)).update();
    }
}
