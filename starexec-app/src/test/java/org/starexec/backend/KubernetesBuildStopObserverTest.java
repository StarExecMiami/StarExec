package org.starexec.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.to.SolverBuildStopReceipt;

class KubernetesBuildStopObserverTest {
    private final UUID token = UUID.randomUUID();
    private final String engine = "kubernetes:starexec:7:job-uid";

    static Job finishedJob() {
        return new JobBuilder().withNewMetadata().withName("build-job").withNamespace("starexec")
                .withUid("job-uid").withResourceVersion("17")
                .withLabels(Map.of(KubernetesBuildStopObserver.ARTIFACT_LABEL, "5", "starexec.org/managed", "true",
                    "starexec.org/exec-id", "7", "starexec.org/pair-id", "42", "starexec.org/attempt-no", "1"))
                .endMetadata().withNewSpec().withSuspend(false).endSpec().withNewStatus()
                .addNewCondition().withType("Complete").withStatus("True").endCondition().endStatus().build();
    }

    static Pod finishedPod() {
        return new PodBuilder().withNewMetadata().withNamespace("starexec").withName("build-pod").withUid("pod-uid")
                .addNewOwnerReference().withApiVersion("batch/v1").withKind("Job").withName("build-job")
                    .withUid("job-uid").withController(true).endOwnerReference().endMetadata()
                .withNewSpec().withNodeName("worker").withHostPID(false).withRestartPolicy("Never")
                .addNewContainer().withName("job-runner").withImage("configured-runner")
                    .withSecurityContext(new KubernetesNativeBackend().jobContainerSecurityContext()).endContainer().endSpec()
                .withNewStatus().withPhase("Succeeded").addNewContainerStatus().withName("job-runner")
                    .withContainerID("containerd://actual-container").withImageID("sha256:actual-image").withRestartCount(0)
                    .withNewState().withNewTerminated().withContainerID("containerd://actual-container").withExitCode(0)
                    .withFinishedAt("2026-01-01T00:00:00Z").endTerminated().endState().endContainerStatus().endStatus().build();
    }

    private KubernetesBuildStopObserver.Identity identity() { return KubernetesBuildStopObserver.identify("starexec", finishedJob()); }

    private PodList listing(Pod... pods) { return new PodListBuilder().withItems(pods).build(); }

    private MockedStatic<SolverBuildArtifacts> registered() throws Exception {
        var dao = mockStatic(SolverBuildArtifacts.class);
        dao.when(() -> SolverBuildArtifacts.find(42, 1)).thenReturn(Optional.of(artifact(5, engine)));
        dao.when(() -> SolverBuildArtifacts.findStopReceipt(42, 1)).thenReturn(Optional.empty());
        dao.when(() -> SolverBuildArtifacts.recordStopReceipt(eq(42), eq(1), eq(token), eq(engine), any())).thenReturn(true);
        return dao;
    }

    private SolverBuildArtifacts.Artifact artifact(long id, String binding) {
        return new SolverBuildArtifacts.Artifact(id, 42, 1, 9, 9, 10, 1, "/data/source", "/data/staging",
                null, null, token, binding, null, "PLANNED");
    }

    @ParameterizedTest @ValueSource(strings = {"Complete", "Failed"})
    void capturesActualResourceAndContainerIdentityRatherThanConfiguredImage(String terminal) throws Exception {
        Job job = finishedJob(); job.getStatus().getConditions().get(0).setType(terminal);
        Pod pod = finishedPod(); pod.getStatus().setPhase(terminal.equals("Complete") ? "Succeeded" : "Failed");
        pod.getStatus().getContainerStatuses().get(0).getState().getTerminated().setExitCode(137);
        try (var dao = registered()) {
            KubernetesBuildStopObserver.record("starexec", identity(), job, listing(pod));
            var capture = org.mockito.ArgumentCaptor.forClass(SolverBuildStopReceipt.class);
            dao.verify(() -> SolverBuildArtifacts.recordStopReceipt(eq(42), eq(1), eq(token), eq(engine), capture.capture()));
            var receipt = capture.getValue(); assertTrue(receipt.matches(5, 42, 1, engine));
            assertEquals("kubernetes", receipt.backend()); assertEquals("job-uid", receipt.resourceId());
            var facts = receipt.facts().getAsJsonArray("pods").get(0).getAsJsonObject()
                    .getAsJsonArray("containers").get(0).getAsJsonObject();
            assertEquals("containerd://actual-container", facts.get("container_id").getAsString());
            assertEquals("sha256:actual-image", facts.get("image_id").getAsString());
            assertEquals(137, facts.get("exit_code").getAsInt());
            assertFalse(receipt.payload().contains("configured-runner"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"SuccessCriteriaMet", "FailureTarget", "Suspended", "false", "counters"})
    void nonterminalControllersCannotBeQualifiedFromPodCountersOrSuspension(String kind) throws Exception {
        Job job = finishedJob(); var condition = job.getStatus().getConditions().get(0);
        if (kind.equals("false")) condition.setStatus("False");
        else if (kind.equals("counters")) { job.getStatus().setConditions(List.of()); job.getStatus().setSucceeded(1); }
        else condition.setType(kind);
        job.getSpec().setSuspend(true);
        try (var dao = registered()) {
            assertThrows(IllegalStateException.class, () -> KubernetesBuildStopObserver.record("starexec", identity(), job, listing(finishedPod())));
            dao.verifyNoInteractions();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"namespace", "uid", "name", "pair", "attempt", "artifact", "execution"})
    void aDifferentJobCannotUseTheExpectedBuildIdentity(String field) throws Exception {
        Job job = finishedJob();
        switch (field) {
            case "namespace" -> job.getMetadata().setNamespace("elsewhere");
            case "uid" -> job.getMetadata().setUid("another-uid");
            case "name" -> job.getMetadata().setName("another-name");
            case "pair" -> job.getMetadata().getLabels().put("starexec.org/pair-id", "43");
            case "attempt" -> job.getMetadata().getLabels().put("starexec.org/attempt-no", "2");
            case "artifact" -> job.getMetadata().getLabels().put(KubernetesBuildStopObserver.ARTIFACT_LABEL, "6");
            case "execution" -> job.getMetadata().getLabels().put("starexec.org/exec-id", "8");
        }
        try (var dao = registered()) {
            assertThrows(IllegalStateException.class, () -> KubernetesBuildStopObserver.record("starexec", identity(), job, listing(finishedPod())));
            dao.verifyNoInteractions();
        }
    }

    @Test void missingOrEmptyPodListingsCannotCreateStopEvidence() throws Exception {
        try (var dao = registered()) {
            assertThrows(IllegalStateException.class, () -> KubernetesBuildStopObserver.record("starexec", identity(), finishedJob(), null));
            assertThrows(IllegalStateException.class, () -> KubernetesBuildStopObserver.record("starexec", identity(), finishedJob(), listing()));
            dao.verifyNoInteractions();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"Running", "Pending", "Unknown"})
    void controllerCompletionDoesNotOverrideAnUnsafePodPhase(String phase) throws Exception {
        Pod pod = finishedPod(); pod.getStatus().setPhase(phase);
        try (var dao = registered()) {
            assertThrows(IllegalStateException.class, () -> KubernetesBuildStopObserver.record("starexec", identity(), finishedJob(), listing(pod)));
            dao.verifyNoInteractions();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"foreign-owner", "ownerless", "namespace", "host-pid", "privileged", "privilege-escalation", "capability"})
    void unsafeOwnershipOrProcessIsolationCannotQualify(String scenario) throws Exception {
        Pod pod = finishedPod(); var security = pod.getSpec().getContainers().get(0).getSecurityContext();
        switch (scenario) {
            case "foreign-owner" -> pod.getMetadata().getOwnerReferences().get(0).setUid("other-job");
            case "ownerless" -> pod.getMetadata().setOwnerReferences(List.of());
            case "namespace" -> pod.getMetadata().setNamespace("other-namespace");
            case "host-pid" -> pod.getSpec().setHostPID(true);
            case "privileged" -> security.setPrivileged(true);
            case "privilege-escalation" -> security.setAllowPrivilegeEscalation(true);
            case "capability" -> security.getCapabilities().setAdd(List.of("SYS_ADMIN"));
        }
        try (var dao = registered()) {
            assertThrows(IllegalStateException.class, () -> KubernetesBuildStopObserver.record("starexec", identity(), finishedJob(), listing(pod)));
            dao.verifyNoInteractions();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"running", "waiting", "absent", "runtime-id", "terminated-id", "image-id", "finished-at"})
    void aFailedPodPhaseCannotOverrideMissingPositiveContainerTermination(String fault) throws Exception {
        Pod pod = finishedPod(); pod.getStatus().setPhase("Failed"); var status = pod.getStatus().getContainerStatuses().get(0);
        switch (fault) {
            case "running" -> status.setState(new ContainerStateBuilder().withNewRunning().endRunning().build());
            case "waiting" -> status.setState(new ContainerStateBuilder().withNewWaiting().withReason("ContainerStatusUnknown").endWaiting().build());
            case "absent" -> status.setState(null);
            case "runtime-id" -> status.setContainerID(null);
            case "terminated-id" -> status.getState().getTerminated().setContainerID("other-container");
            case "image-id" -> status.setImageID(null);
            case "finished-at" -> status.getState().getTerminated().setFinishedAt(null);
        }
        try (var dao = registered()) {
            assertThrows(IllegalStateException.class, () -> KubernetesBuildStopObserver.record("starexec", identity(), finishedJob(), listing(pod)));
            dao.verifyNoInteractions();
        }
    }

    @Test void everyContainerIncludingInitAndEphemeralMustBeAccountedFor() throws Exception {
        Pod pod = finishedPod(); var security = pod.getSpec().getContainers().get(0).getSecurityContext();
        pod.getSpec().setInitContainers(List.of(new ContainerBuilder().withName("initializer").withSecurityContext(security).build()));
        try (var dao = registered()) {
            assertThrows(IllegalStateException.class, () -> KubernetesBuildStopObserver.record("starexec", identity(), finishedJob(), listing(pod)));
            pod.getSpec().setInitContainers(List.of());
            pod.getSpec().setEphemeralContainers(List.of(new EphemeralContainerBuilder().withName("debugger").withSecurityContext(security).build()));
            assertThrows(IllegalStateException.class, () -> KubernetesBuildStopObserver.record("starexec", identity(), finishedJob(), listing(pod)));
            dao.verifyNoInteractions();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"unbound", "different-engine", "different-artifact"})
    void aReceiptCannotBindOrChangeCatalogExecution(String kind) throws Exception {
        try (var dao = registered()) {
            dao.when(() -> SolverBuildArtifacts.find(42, 1)).thenReturn(Optional.of(artifact(kind.equals("different-artifact") ? 6 : 5,
                    kind.equals("unbound") ? null : kind.equals("different-engine") ? "other-engine" : engine)));
            assertThrows(IllegalStateException.class, () -> KubernetesBuildStopObserver.record("starexec", identity(), finishedJob(), listing(finishedPod())));
            dao.verify(() -> SolverBuildArtifacts.recordStopReceipt(anyInt(), anyInt(), any(), anyString(), any()), never());
        }
    }

    @Test void retriesReuseTheFirstExactReceiptAndDoNotInventEvidenceAfterDeletion() throws Exception {
        var first = new AtomicReference<SolverBuildStopReceipt>();
        try (var dao = registered()) {
            assertFalse(KubernetesBuildStopObserver.hasConservedReceipt("starexec", identity()));
            dao.when(() -> SolverBuildArtifacts.recordStopReceipt(eq(42), eq(1), eq(token), eq(engine), any()))
                .thenAnswer(call -> { first.compareAndSet(null, call.getArgument(4)); return true; });
            KubernetesBuildStopObserver.record("starexec", identity(), finishedJob(), listing(finishedPod()));
            var receipt = first.get(); assertNotNull(receipt);
            dao.when(() -> SolverBuildArtifacts.findStopReceipt(42, 1)).thenReturn(Optional.of(
                    new SolverBuildArtifacts.StoredStopReceipt(receipt, Instant.now())));
            assertTrue(KubernetesBuildStopObserver.hasConservedReceipt("starexec", identity()));
            KubernetesBuildStopObserver.record("starexec", identity(), finishedJob(), listing(finishedPod()));
            dao.verify(() -> SolverBuildArtifacts.recordStopReceipt(42, 1, token, engine, receipt), times(2));
        }
    }

    @Test void aFailedWriteDoesNotAcknowledgeDurableStop() throws Exception {
        try (var dao = registered()) {
            dao.when(() -> SolverBuildArtifacts.recordStopReceipt(eq(42), eq(1), eq(token), eq(engine), any()))
                    .thenReturn(false).thenThrow(new SQLException("unavailable"));
            assertThrows(IllegalStateException.class, () -> KubernetesBuildStopObserver.record("starexec", identity(), finishedJob(), listing(finishedPod())));
            assertThrows(SQLException.class, () -> KubernetesBuildStopObserver.record("starexec", identity(), finishedJob(), listing(finishedPod())));
        }
    }

    @Test void duplicatePodUidsCannotAppearAsTwoIndependentObservations() throws Exception {
        try (var dao = registered()) {
            assertThrows(IllegalStateException.class, () -> KubernetesBuildStopObserver.record("starexec", identity(), finishedJob(), listing(finishedPod(), finishedPod())));
            dao.verifyNoInteractions();
        }
    }

    @Test void correctlyTerminatedInitAndEphemeralContainersAreIncludedInTheReceipt() throws Exception {
        Pod pod = finishedPod(); var security = pod.getSpec().getContainers().get(0).getSecurityContext();
        pod.getSpec().setInitContainers(List.of(new ContainerBuilder().withName("initializer").withSecurityContext(security).build()));
        pod.getSpec().setEphemeralContainers(List.of(new EphemeralContainerBuilder().withName("debugger").withSecurityContext(security).build()));
        pod.getStatus().setInitContainerStatuses(List.of(new ContainerStatusBuilder(pod.getStatus().getContainerStatuses().get(0))
                .withName("initializer").build()));
        pod.getStatus().setEphemeralContainerStatuses(List.of(new ContainerStatusBuilder(pod.getStatus().getContainerStatuses().get(0))
                .withName("debugger").build()));
        pod.getStatus().getInitContainerStatuses().get(0).setContainerID("containerd://initializer");
        pod.getStatus().getInitContainerStatuses().get(0).getState().getTerminated().setContainerID("containerd://initializer");
        pod.getStatus().getEphemeralContainerStatuses().get(0).setContainerID("containerd://debugger");
        pod.getStatus().getEphemeralContainerStatuses().get(0).getState().getTerminated().setContainerID("containerd://debugger");
        try (var dao = registered()) {
            KubernetesBuildStopObserver.record("starexec", identity(), finishedJob(), listing(pod));
            var capture = org.mockito.ArgumentCaptor.forClass(SolverBuildStopReceipt.class);
            dao.verify(() -> SolverBuildArtifacts.recordStopReceipt(eq(42), eq(1), eq(token), eq(engine), capture.capture()));
            var observation = capture.getValue().facts().getAsJsonArray("pods").get(0).getAsJsonObject();
            assertEquals(1, observation.getAsJsonArray("init_containers").size());
            assertEquals(1, observation.getAsJsonArray("ephemeral_containers").size());
            assertEquals("containerd://initializer", observation.getAsJsonArray("init_containers").get(0).getAsJsonObject().get("container_id").getAsString());
            assertEquals("containerd://debugger", observation.getAsJsonArray("ephemeral_containers").get(0).getAsJsonObject().get("container_id").getAsString());
        }
    }

    @Test void aStoredEnvelopeWithMissingContainerProofCannotAuthorizeCleanupAfterDeletion() throws Exception {
        var first = new AtomicReference<SolverBuildStopReceipt>();
        try (var dao = registered()) {
            dao.when(() -> SolverBuildArtifacts.recordStopReceipt(eq(42), eq(1), eq(token), eq(engine), any()))
                .thenAnswer(call -> { first.set(call.getArgument(4)); return true; });
            KubernetesBuildStopObserver.record("starexec", identity(), finishedJob(), listing(finishedPod()));
            var facts = first.get().facts(); facts.getAsJsonArray("pods").get(0).getAsJsonObject().remove("containers");
            var malformed = SolverBuildStopReceipt.observed("kubernetes", 5, 42, 1, engine, "job-uid", Instant.now(), facts);
            dao.when(() -> SolverBuildArtifacts.findStopReceipt(42, 1)).thenReturn(Optional.of(
                    new SolverBuildArtifacts.StoredStopReceipt(malformed, Instant.now())));
            assertFalse(KubernetesBuildStopObserver.hasConservedReceipt("starexec", identity()));
        }
    }
}
