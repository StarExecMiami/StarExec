package org.starexec.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.RestartPolicy;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.to.SolverBuildStopReceipt;

class PodmanBuildStopObserverTest {
    private final String id = "owned-build-container";
    private final String engine = "podman:1001:" + id;
    private final UUID token = UUID.randomUUID();
    private InspectContainerResponse inspection;
    private InspectContainerResponse.ContainerState state;
    private ContainerConfig config;
    private HostConfig host;
    private Map<String, String> labels;

    @BeforeEach void setup() {
        inspection = mock(InspectContainerResponse.class);
        state = mock(InspectContainerResponse.ContainerState.class);
        config = mock(ContainerConfig.class);
        labels = new HashMap<>(Map.of(PodmanBuildStopObserver.ARTIFACT_LABEL, "5", "starexec.pair.id", "42",
                "starexec.attempt", "1", "starexec.exec.id", "1001", "starexec.managed", "true",
                "starexec.label.version", "2", "starexec.kind", "job-pair"));
        host = HostConfig.newHostConfig().withPrivileged(false).withRestartPolicy(RestartPolicy.noRestart());
        when(inspection.getId()).thenReturn(id); when(inspection.getConfig()).thenReturn(config);
        when(config.getLabels()).thenReturn(labels); when(inspection.getHostConfig()).thenReturn(host);
        when(inspection.getState()).thenReturn(state); when(inspection.getImageId()).thenReturn("sha256:runner-image");
        when(state.getStatus()).thenReturn("exited"); when(state.getRunning()).thenReturn(false);
        when(state.getPaused()).thenReturn(false); when(state.getRestarting()).thenReturn(false);
        when(state.getPidLong()).thenReturn(0L); when(state.getExitCodeLong()).thenReturn(0L);
        when(state.getStartedAt()).thenReturn("2026-01-01T00:00:00Z");
        when(state.getFinishedAt()).thenReturn("2026-01-01T00:01:00Z");
    }

    private MockedStatic<SolverBuildArtifacts> registered() throws Exception {
        var dao = mockStatic(SolverBuildArtifacts.class);
        dao.when(() -> SolverBuildArtifacts.find(42, 1)).thenReturn(Optional.of(artifact(5, engine)));
        dao.when(() -> SolverBuildArtifacts.findStopReceipt(42, 1)).thenReturn(Optional.empty());
        dao.when(() -> SolverBuildArtifacts.recordStopReceipt(eq(42), eq(1), eq(token), eq(engine), any()))
                .thenReturn(true);
        return dao;
    }

    private SolverBuildArtifacts.Artifact artifact(long artifactId, String admittedEngine) {
        return new SolverBuildArtifacts.Artifact(artifactId, 42, 1, 7, 7, 8, 1, "/data/source",
                "/data/staging", null, null, token, admittedEngine, null, "PLANNED");
    }

    private record Killing(PodmanBackend backend,RemoveContainerCmd remove) { }

    private Killing killing() throws Exception {
        var backend=new PodmanBackend();var client=mock(DockerClient.class);
        Field clientField=PodmanBackend.class.getDeclaredField("dockerClient");clientField.setAccessible(true);clientField.set(backend,client);
        Field tracked=PodmanBackend.class.getDeclaredField("execIdToContainerId");tracked.setAccessible(true);
        ((Map<Integer,String>)tracked.get(backend)).put(1001,id);
        var initial=mock(InspectContainerResponse.class);var running=mock(InspectContainerResponse.ContainerState.class);
        when(initial.getState()).thenReturn(running);when(running.getRunning()).thenReturn(true);
        var inspect=mock(InspectContainerCmd.class);when(client.inspectContainerCmd(id)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(initial,inspection);
        var stop=mock(com.github.dockerjava.api.command.StopContainerCmd.class);
        when(client.stopContainerCmd(id)).thenReturn(stop);when(stop.withTimeout(10)).thenReturn(stop);
        var remove=mock(RemoveContainerCmd.class);when(client.removeContainerCmd(id)).thenReturn(remove);
        when(remove.withForce(true)).thenReturn(remove);when(remove.withRemoveVolumes(true)).thenReturn(remove);
        return new Killing(backend,remove);
    }

    @Test void stoppedCancellationIsPersistedBeforeExactContainerRemoval() throws Exception {
        var target=killing();var backend=target.backend();var remove=target.remove();
        AtomicReference<SolverBuildStopReceipt> conserved=new AtomicReference<>();
        try (var dao=registered()) {
            dao.when(()->SolverBuildArtifacts.recordCancelled(eq(42),eq(1),eq(token),eq(engine),any()))
                    .thenAnswer(call->{conserved.set(call.getArgument(4));return true;});
            doAnswer(call->{assertNotNull(conserved.get(),"Cancellation must be conserved before removing its provider evidence");return null;}).when(remove).exec();
            assertTrue(backend.killPair(1001));
            assertEquals("cancelled",conserved.get().facts().get("disposition").getAsString());
            assertEquals(id,conserved.get().resourceId());
            dao.verify(()->SolverBuildArtifacts.recordStopReceipt(anyInt(),anyInt(),any(),anyString(),any()),never());
            verify(remove).exec();
        }
    }

    @Test void unknownCancellationCommitConservesTheProviderAndTracking() throws Exception {
        var target=killing();
        try (var dao=registered()) {
            dao.when(()->SolverBuildArtifacts.recordCancelled(anyInt(),anyInt(),any(),anyString(),any()))
                    .thenThrow(new SQLException("Commit outcome is unknown"));
            assertFalse(target.backend().killPair(1001));verify(target.remove(),never()).exec();
            Field tracked=PodmanBackend.class.getDeclaredField("execIdToContainerId");tracked.setAccessible(true);
            assertEquals(id,((Map<?,?>)tracked.get(target.backend())).get(1001));
        }
    }

    @Test void normalObservationReusesAnAlreadyConservedCancellation() throws Exception {
        AtomicReference<SolverBuildStopReceipt> first=new AtomicReference<>();
        try (var dao=registered()) {
            dao.when(()->SolverBuildArtifacts.recordCancelled(eq(42),eq(1),eq(token),eq(engine),any()))
                    .thenAnswer(call->{first.compareAndSet(null,call.getArgument(4));return true;});
            PodmanBuildStopObserver.recordCancelledIfBuild(inspection,id);
            var receipt=first.get();assertNotNull(receipt);
            dao.when(()->SolverBuildArtifacts.findStopReceipt(42,1)).thenReturn(Optional.of(new SolverBuildArtifacts.StoredStopReceipt(receipt,Instant.now())));
            PodmanBuildStopObserver.recordIfBuild(inspection,id);
            dao.verify(()->SolverBuildArtifacts.recordCancelled(42,1,token,engine,receipt),times(2));
            dao.verify(()->SolverBuildArtifacts.recordStopReceipt(anyInt(),anyInt(),any(),anyString(),any()),never());
        }
    }

    @Test void cancellationCannotRewriteAnOrdinaryCompletionReceipt() throws Exception {
        AtomicReference<SolverBuildStopReceipt> first=new AtomicReference<>();
        try (var dao=registered()) {
            dao.when(()->SolverBuildArtifacts.recordStopReceipt(eq(42),eq(1),eq(token),eq(engine),any()))
                    .thenAnswer(call->{first.set(call.getArgument(4));return true;});
            PodmanBuildStopObserver.recordIfBuild(inspection,id);var receipt=first.get();assertNotNull(receipt);
            dao.when(()->SolverBuildArtifacts.findStopReceipt(42,1)).thenReturn(Optional.of(new SolverBuildArtifacts.StoredStopReceipt(receipt,Instant.now())));
            assertThrows(IllegalStateException.class,()->PodmanBuildStopObserver.recordCancelledIfBuild(inspection,id));
            assertFalse(receipt.facts().has("disposition"));
            dao.verify(()->SolverBuildArtifacts.recordCancelled(anyInt(),anyInt(),any(),anyString(),any()),never());
        }
    }

    @Test void deletionMustFollowTheDurableMatchingReceipt() throws Exception {
        var backend = new PodmanBackend(); var client = mock(DockerClient.class);
        Field field = PodmanBackend.class.getDeclaredField("dockerClient"); field.setAccessible(true); field.set(backend, client);
        var inspect = mock(InspectContainerCmd.class); var remove = mock(RemoveContainerCmd.class);
        when(client.inspectContainerCmd(id)).thenReturn(inspect); when(inspect.exec()).thenReturn(inspection);
        when(client.removeContainerCmd(id)).thenReturn(remove);
        when(remove.withForce(false)).thenReturn(remove); when(remove.withRemoveVolumes(true)).thenReturn(remove);
        try (var dao = registered()) {
            doAnswer(call -> {
                dao.verify(() -> SolverBuildArtifacts.recordStopReceipt(eq(42), eq(1), eq(token), eq(engine), any()));
                return null;
            }).when(remove).exec();
            backend.removeCompletedContainer(id);
            verify(remove).exec();
        }
    }

    @Test void aPositiveInspectionPreservesOnlyFilteredPhysicalFacts() throws Exception {
        try (var dao = registered()) {
            PodmanBuildStopObserver.recordIfBuild(inspection, id);
            var receipt = org.mockito.ArgumentCaptor.forClass(SolverBuildStopReceipt.class);
            dao.verify(() -> SolverBuildArtifacts.recordStopReceipt(eq(42), eq(1), eq(token), eq(engine), receipt.capture()));
            assertTrue(receipt.getValue().matches(5, 42, 1, engine));
            assertEquals(id, receipt.getValue().resourceId()); assertEquals("podman", receipt.getValue().backend());
            assertEquals("exited", receipt.getValue().facts().get("status").getAsString());
            assertEquals("sha256:runner-image", receipt.getValue().facts().get("image_id").getAsString());
            verify(config, never()).getEnv();
        }
    }

    @Test void aRetryReusesTheExactFirstObservationInsteadOfChangingItsTime() throws Exception {
        AtomicReference<SolverBuildStopReceipt> first = new AtomicReference<>();
        try (var dao = registered()) {
            dao.when(() -> SolverBuildArtifacts.recordStopReceipt(eq(42), eq(1), eq(token), eq(engine), any()))
                    .thenAnswer(call -> { first.compareAndSet(null, call.getArgument(4)); return true; });
            PodmanBuildStopObserver.recordIfBuild(inspection, id);
            var existing = first.get(); assertNotNull(existing);
            dao.when(() -> SolverBuildArtifacts.findStopReceipt(42, 1)).thenReturn(Optional.of(
                    new SolverBuildArtifacts.StoredStopReceipt(existing, Instant.now())));
            PodmanBuildStopObserver.recordIfBuild(inspection, id);
            dao.verify(() -> SolverBuildArtifacts.recordStopReceipt(42, 1, token, engine, existing), times(2));
            when(state.getFinishedAt()).thenReturn("2026-01-01T00:02:00Z");
            assertThrows(IllegalStateException.class, () -> PodmanBuildStopObserver.recordIfBuild(inspection, id));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"running", "created", "paused", "restarting", "removing", ""})
    void nonterminalStatesCannotBecomeStopReceipts(String status) throws Exception {
        when(state.getStatus()).thenReturn(status);
        try (var dao = registered()) {
            assertThrows(IllegalStateException.class, () -> PodmanBuildStopObserver.recordIfBuild(inspection, id));
            dao.verify(() -> SolverBuildArtifacts.recordStopReceipt(anyInt(), anyInt(), any(), anyString(), any()), never());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"running", "paused", "restarting", "pid", "missing-pid", "unknown-running"})
    void terminalTextCannotOverrideUnknownOrActiveProcessFlags(String flag) throws Exception {
        switch (flag) {
            case "running" -> when(state.getRunning()).thenReturn(true);
            case "paused" -> when(state.getPaused()).thenReturn(true);
            case "restarting" -> when(state.getRestarting()).thenReturn(true);
            case "pid" -> when(state.getPidLong()).thenReturn(25L);
            case "missing-pid" -> when(state.getPidLong()).thenReturn(null);
            case "unknown-running" -> when(state.getRunning()).thenReturn(null);
        }
        try (var dao = registered()) {
            assertThrows(IllegalStateException.class, () -> PodmanBuildStopObserver.recordIfBuild(inspection, id));
            dao.verifyNoInteractions();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"host", "container:other"})
    void sharedProcessNamespacesCannotEstablishBuildTermination(String mode) throws Exception {
        host.withPidMode(mode);
        try (var dao = registered()) {
            assertThrows(IllegalStateException.class, () -> PodmanBuildStopObserver.recordIfBuild(inspection, id));
            dao.verifyNoInteractions();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"privileged", "capability", "restart"})
    void unsafeEffectiveHostConfigurationDoesNotQualify(String setting) throws Exception {
        if (setting.equals("privileged")) host.withPrivileged(true);
        if (setting.equals("capability")) host.withCapAdd(Capability.SYS_ADMIN);
        if (setting.equals("restart")) host.withRestartPolicy(RestartPolicy.alwaysRestart());
        try (var dao = registered()) {
            assertThrows(IllegalStateException.class, () -> PodmanBuildStopObserver.recordIfBuild(inspection, id));
            dao.verifyNoInteractions();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"starexec.managed", "starexec.label.version", "starexec.kind",
            "starexec.pair.id", "starexec.attempt", "starexec.exec.id", "starexec.build.artifact"})
    void malformedOwnershipDoesNotReadOrUpdateTheCatalog(String label) throws Exception {
        labels.put(label, "invalid");
        try (var dao = registered()) {
            assertThrows(RuntimeException.class, () -> PodmanBuildStopObserver.recordIfBuild(inspection, id));
            dao.verifyNoInteractions();
        }
    }

    @Test void aDifferentContainerIdIsNotTheAdmittedResource() throws Exception {
        try (var dao = registered()) {
            assertThrows(IllegalStateException.class, () -> PodmanBuildStopObserver.recordIfBuild(inspection, "another"));
            dao.verifyNoInteractions();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"unbound", "other-engine", "other-artifact"})
    void observationCannotBindAnUnadmittedOrDifferentExecution(String mismatch) throws Exception {
        try (var dao = registered()) {
            var artifact = artifact(mismatch.equals("other-artifact") ? 6 : 5,
                    mismatch.equals("unbound") ? null : mismatch.equals("other-artifact") ? engine : "other-engine");
            dao.when(() -> SolverBuildArtifacts.find(42, 1)).thenReturn(Optional.of(artifact));
            assertThrows(IllegalStateException.class, () -> PodmanBuildStopObserver.recordIfBuild(inspection, id));
            dao.verify(() -> SolverBuildArtifacts.recordStopReceipt(anyInt(), anyInt(), any(), anyString(), any()), never());
        }
    }

    @Test void databaseFailureOrRefusalCannotBeReportedAsAConservedStop() throws Exception {
        try (var dao = registered()) {
            dao.when(() -> SolverBuildArtifacts.recordStopReceipt(eq(42), eq(1), eq(token), eq(engine), any()))
                    .thenReturn(false).thenThrow(new SQLException("unavailable"));
            assertThrows(IllegalStateException.class, () -> PodmanBuildStopObserver.recordIfBuild(inspection, id));
            assertThrows(SQLException.class, () -> PodmanBuildStopObserver.recordIfBuild(inspection, id));
        }
    }

    @Test void ordinaryContainersNeedNoBuildCatalogAccess() throws Exception {
        labels.remove(PodmanBuildStopObserver.ARTIFACT_LABEL);
        try (var dao = registered()) { PodmanBuildStopObserver.recordIfBuild(inspection, id); dao.verifyNoInteractions(); }
    }

    @Test void missingOwnershipInspectionIsNotTreatedAsAnOrdinaryContainer() {
        when(inspection.getConfig()).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> PodmanBuildStopObserver.recordIfBuild(inspection, id));
    }

    @Test void theSdkNoRestartEncodingIsRecognizedWithoutAcceptingAnAbsentPolicy() throws Exception {
        assertEquals("", RestartPolicy.noRestart().getName());
        assertEquals(RestartPolicy.noRestart(), RestartPolicy.parse("no"));
        try (var dao = registered()) {
            PodmanBuildStopObserver.recordIfBuild(inspection, id);
            host.withRestartPolicy(null);
            assertThrows(IllegalStateException.class, () -> PodmanBuildStopObserver.recordIfBuild(inspection, id));
        }
    }

    @Test void refusedCreatedResourceCleanupDoesNotInventACompletedReceipt() throws Exception {
        var build = new BuildExecutionContext(5, 42, 1, token, java.nio.file.Path.of("/data/source"),
                java.nio.file.Path.of("/data/staging"));
        when(state.getStatus()).thenReturn("created"); when(state.getStartedAt()).thenReturn("0001-01-01T00:00:00Z");
        try (var dao = registered()) {
            PodmanBuildStopObserver.requireNeverStarted(inspection, id, build, 1001); dao.verifyNoInteractions();
            when(state.getStartedAt()).thenReturn("2026-01-01T00:00:00Z");
            assertThrows(IllegalStateException.class,
                    () -> PodmanBuildStopObserver.requireNeverStarted(inspection, id, build, 1001));
        }
    }
}
