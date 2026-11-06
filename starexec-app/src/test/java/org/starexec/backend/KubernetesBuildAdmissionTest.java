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
    private Job replacement;

    private Job created() {
        return new JobBuilder().withNewMetadata().withName("build-job").withNamespace("starexec")
                .withUid("uid-original").withResourceVersion("17").endMetadata()
                .withNewSpec().withSuspend(true).endSpec().build();
    }

    @BeforeEach @SuppressWarnings({"unchecked", "rawtypes"})
    void setup() throws Exception {
        for (var entry : java.util.Map.of("namespace", (Object) "starexec", "kubernetesClient", client).entrySet()) {
            var field = KubernetesNativeBackend.class.getDeclaredField(entry.getKey());
            field.setAccessible(true); field.set(backend, entry.getValue());
        }
        ScalableResource<Job> resource = mock(ScalableResource.class);
        locked = mock(ReplaceDeletable.class);
        BatchAPIGroupDSL batch = mock(BatchAPIGroupDSL.class);
        V1BatchAPIGroupDSL v1 = mock(V1BatchAPIGroupDSL.class);
        MixedOperation jobs = mock(MixedOperation.class);
        NonNamespaceOperation namespaced = mock(NonNamespaceOperation.class);
        when(client.batch()).thenReturn(batch);
        when(batch.v1()).thenReturn(v1);
        when(v1.jobs()).thenReturn(jobs);
        when(jobs.inNamespace("starexec")).thenReturn(namespaced);
        doAnswer(call -> { replacement = call.getArgument(0); return resource; })
                .when(namespaced).resource(any(Job.class));
        when(resource.lockResourceVersion("17")).thenReturn(locked);
        when(locked.replace()).thenAnswer(call -> { events.add("resume"); return replacement; });
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
        verify(locked, times(1)).replace();
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

    @Test void anAmbiguousUpdateIsNotRetriedAgainstAnotherJobVersion() {
        RuntimeException failure = new RuntimeException("ambiguous update");
        when(locked.replace()).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> resume(created())));
        verify(locked, times(1)).replace();
        assertEquals("17", replacement.getMetadata().getResourceVersion());
        assertEquals("uid-original", replacement.getMetadata().getUid());
    }

    @ParameterizedTest @ValueSource(strings = { "uid", "name" })
    void anotherResourceCannotSatisfyTheResumeReceipt(String changed) {
        when(locked.replace()).thenAnswer(call -> {
            Job other = new JobBuilder(replacement).build();
            if (changed.equals("uid")) other.getMetadata().setUid("different-uid");
            else other.getMetadata().setName("different-name");
            return other;
        });
        assertThrows(IllegalStateException.class, () -> resume(created()));
        verify(locked, times(1)).replace();
    }

    @Test void anEmptyResumeResponseIsUnresolved() {
        when(locked.replace()).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> resume(created()));
        verify(locked, times(1)).replace();
    }

    @Test void aStillSuspendedResumeResponseIsUnresolved() {
        when(locked.replace()).thenReturn(created());
        assertThrows(IllegalStateException.class, () -> resume(created()));
        verify(locked, times(1)).replace();
    }
}
