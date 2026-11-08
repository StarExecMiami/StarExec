package org.starexec.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.to.SolverBuildStopReceipt;

class PodmanRefusedBuildRecoveryTest {
    @TempDir Path files;
    private final String container="a".repeat(64),image="sha256:"+"b".repeat(64);
    private final UUID token=UUID.randomUUID();
    private PodmanBackend backend;
    private DockerClient client;
    private RemoveContainerCmd remove;
    private InspectContainerResponse inspection;
    private InspectContainerResponse.ContainerState state;
    private HostConfig host;
    private Map<String,String> labels;
    private MockedStatic<SolverBuildArtifacts> artifacts;
    private MockedStatic<JobPairs> pairs;
    private MockedStatic<org.starexec.data.database.SolverBuildCreations> creations;
    private SolverBuildArtifacts.Artifact artifact;
    private SolverBuildStopReceipt receipt;
    private final AtomicBoolean removed=new AtomicBoolean();

    private static Object field(Object target,String name) throws Exception {
        Field f=PodmanBackend.class.getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    private static void field(Object target,String name,Object value) throws Exception {
        Field f=PodmanBackend.class.getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    @SuppressWarnings("unchecked") private Map<Integer,String> tracked() throws Exception {
        return (Map<Integer,String>)field(backend,"execIdToContainerId");
    }
    @SuppressWarnings("unchecked") private Map<Integer,Integer> slots() throws Exception {
        return (Map<Integer,Integer>)field(backend,"execIdToPartitionIndex");
    }
    private void reconcile() throws Exception {
        var method=PodmanBackend.class.getDeclaredMethod("reconcileOrphanedPairs");method.setAccessible(true);method.invoke(backend);
    }

    @BeforeEach void setup() throws Exception {
        creations=mockStatic(org.starexec.data.database.SolverBuildCreations.class,CALLS_REAL_METHODS);
        creations.when(org.starexec.data.database.SolverBuildCreations::pending).thenReturn(List.of());
        creations.when(org.starexec.data.database.SolverBuildCreations::maximumExecutionId).thenReturn(0);
        backend=new PodmanBackend();client=mock(DockerClient.class);
        field(backend,"dockerClient",client);field(backend,"partitions",List.of(new CpuPartition(0,"2",null)));
        field(backend,"partitionActiveSlots",new int[]{0});field(backend,"partitionMaxSlots",new int[]{2});
        field(backend,"partitionSlotLocks",new Object[]{new Object()});field(backend,"partitionNodeIds",new int[]{-1});
        inspection=mock(InspectContainerResponse.class);state=mock(InspectContainerResponse.ContainerState.class);
        host=HostConfig.newHostConfig().withPrivileged(false).withRestartPolicy(RestartPolicy.noRestart());
        labels=new HashMap<>(Map.of("starexec.managed","true","starexec.label.version","2","starexec.kind","job-pair",
                "starexec.build.artifact","5","starexec.pair.id","42","starexec.attempt","1","starexec.exec.id","1001"));
        var config=mock(ContainerConfig.class);when(config.getLabels()).thenReturn(labels);
        when(inspection.getConfig()).thenReturn(config);when(inspection.getState()).thenReturn(state);
        when(inspection.getHostConfig()).thenReturn(host);when(inspection.getId()).thenReturn(container);
        when(inspection.getImageId()).thenReturn(image);when(state.getStatus()).thenReturn("created");
        when(state.getRunning()).thenReturn(false);when(state.getPaused()).thenReturn(false);when(state.getRestarting()).thenReturn(false);
        when(state.getPidLong()).thenReturn(0L);when(state.getStartedAt()).thenReturn("0001-01-01T00:00:00Z");
        var inspect=mock(InspectContainerCmd.class);when(client.inspectContainerCmd(container)).thenReturn(inspect);
        when(inspect.exec()).thenAnswer(call->{if(removed.get()) throw new NotFoundException("Owned resource absent");return inspection;});
        remove=mock(RemoveContainerCmd.class);when(client.removeContainerCmd(container)).thenReturn(remove);
        when(remove.withForce(false)).thenReturn(remove);when(remove.withRemoveVolumes(true)).thenReturn(remove);
        doAnswer(call->{removed.set(true);return null;}).when(remove).exec();
        var listed=mock(Container.class);when(listed.getId()).thenReturn(container);when(listed.getLabels()).thenReturn(labels);
        var list=mock(ListContainersCmd.class);when(client.listContainersCmd()).thenReturn(list);
        when(list.withShowAll(true)).thenReturn(list);when(list.withLabelFilter(anyMap())).thenReturn(list);
        when(list.exec()).thenAnswer(call->removed.get()?List.of():List.of(listed));
        artifacts=mockStatic(SolverBuildArtifacts.class);pairs=mockStatic(JobPairs.class);
        artifact=new SolverBuildArtifacts.Artifact(5,42,1,7,7,8,1,files.resolve("source").toString(),
                files.resolve("purged-staging").toString(),null,null,token,"podman:1001:"+container,Instant.now(),"REJECTED");
        var build=new BuildExecutionContext(5,42,1,token,Path.of(artifact.sourcePath()),Path.of(artifact.stagingPath()));
        receipt=PodmanBuildStopObserver.cancelledCreationReceipt(inspection,container,build,1001);
        artifacts.when(SolverBuildArtifacts::refusedPodmanCreations).thenReturn(List.of(artifact));
        artifacts.when(()->SolverBuildArtifacts.findStopReceipt(42,1)).thenReturn(Optional.of(new SolverBuildArtifacts.StoredStopReceipt(receipt,Instant.now())));
        artifacts.when(()->SolverBuildArtifacts.retryRefusedPodmanCreation(eq(build),eq(1001),eq(container),any()))
                .thenReturn(JobPairs.NeverStartedRetryResult.REQUEUED);
        artifacts.when(()->SolverBuildArtifacts.hasManagedHistory(42)).thenReturn(true);
        pairs.when(()->JobPairs.getPairIdsByStatusCode(2)).thenReturn(List.of(42));
        pairs.when(()->JobPairs.getPairIdsByStatusCode(4)).thenReturn(List.of());
    }

    @AfterEach void close() { if(pairs!=null) pairs.close();if(artifacts!=null) artifacts.close();if(creations!=null) creations.close(); }

    @Test void coldStartupRetiresOnlyTheConservedCreatedResourceWithPurgedStaging() throws Exception {
        var restored=PodmanBuildStopObserver.restoreRefusedCreation(artifact);
        assertEquals(receipt.payload(),restored.receipt().payload());assertFalse(java.nio.file.Files.exists(restored.build().staging()));
        reconcile();reconcile();
        verify(remove).withForce(false);verify(remove,never()).withForce(true);verify(remove).exec();
        verify(client,never()).startContainerCmd(anyString());
        assertTrue(tracked().isEmpty());assertTrue(slots().isEmpty());
        assertEquals(0,((int[])field(backend,"partitionActiveSlots"))[0]);
        artifacts.verify(()->SolverBuildArtifacts.retryRefusedPodmanCreation(any(),eq(1001),eq(container),eq(receipt)),times(2));
        pairs.verify(()->JobPairs.tryResetEnqueuedToPending(anyInt()),never());
        artifacts.verify(()->SolverBuildArtifacts.recordCancelled(anyInt(),anyInt(),any(),anyString(),any()),never());
    }

    @Test void warmRecoverySettlesOnlyItsOwnSlotOnce() throws Exception {
        tracked().put(1001,container);tracked().put(2002,"other-resource");slots().put(1001,0);slots().put(2002,0);
        ((int[])field(backend,"partitionActiveSlots"))[0]=2;
        reconcile();reconcile();
        assertEquals(Map.of(2002,"other-resource"),tracked());assertEquals(Map.of(2002,0),slots());
        assertEquals(1,((int[])field(backend,"partitionActiveSlots"))[0]);
    }

    @Test void recoveredHistoryDoesNotReleaseAReusedExecutionSlot() throws Exception {
        tracked().put(1001,"new-resource");slots().put(1001,0);((int[])field(backend,"partitionActiveSlots"))[0]=1;
        reconcile();
        assertEquals("new-resource",tracked().get(1001));assertEquals(0,slots().get(1001));
        assertEquals(1,((int[])field(backend,"partitionActiveSlots"))[0]);
    }

    @ParameterizedTest @ValueSource(strings={"running","paused","pid","prior-start","image","restart","namespace","labels","status","receipt"})
    void changedOrUnverifiedRecoveryCannotRemoveResetOrIngest(String fault) throws Exception {
        switch(fault) {
            case "running" -> when(state.getRunning()).thenReturn(true);
            case "paused" -> when(state.getPaused()).thenReturn(true);
            case "pid" -> when(state.getPidLong()).thenReturn(44L);
            case "prior-start" -> when(state.getStartedAt()).thenReturn("2026-01-01T00:00:00Z");
            case "image" -> when(inspection.getImageId()).thenReturn("sha256:"+"c".repeat(64));
            case "restart" -> host.withRestartPolicy(RestartPolicy.alwaysRestart());
            case "namespace" -> host.withPidMode("host");
            case "labels" -> labels.put("starexec.attempt","2");
            case "status" -> when(state.getStatus()).thenReturn("exited");
            case "receipt" -> artifacts.when(()->SolverBuildArtifacts.findStopReceipt(42,1)).thenThrow(new SQLException("Invalid conserved receipt"));
            default -> throw new AssertionError(fault);
        }
        reconcile();
        verify(client,never()).removeContainerCmd(anyString());verify(client,never()).startContainerCmd(anyString());
        artifacts.verify(()->SolverBuildArtifacts.retryRefusedPodmanCreation(any(),anyInt(),anyString(),any()),never());
        pairs.verify(()->JobPairs.tryResetEnqueuedToPending(anyInt()),never());
        pairs.verify(()->JobPairs.setPairStatusPreciseResult(anyInt(),anyInt(),anyInt(),anyInt(),anyBoolean()),never());
    }

    @Test void lostRemovalResponseRetainsOwnershipUntilTheNextPositiveObservation() throws Exception {
        tracked().put(1001,container);slots().put(1001,0);((int[])field(backend,"partitionActiveSlots"))[0]=1;
        doAnswer(call->{removed.set(true);throw new IllegalStateException("Synthetic lost removal response");}).when(remove).exec();
        reconcile();assertEquals(container,tracked().get(1001));assertEquals(1,((int[])field(backend,"partitionActiveSlots"))[0]);
        artifacts.verify(()->SolverBuildArtifacts.retryRefusedPodmanCreation(any(),anyInt(),anyString(),any()),never());
        reconcile();assertTrue(tracked().isEmpty());assertTrue(slots().isEmpty());assertEquals(0,((int[])field(backend,"partitionActiveSlots"))[0]);
    }

    @Test void failedCensusCannotAuthorizeTheRetryOrReleaseCapacity() throws Exception {
        removed.set(true);tracked().put(1001,container);slots().put(1001,0);((int[])field(backend,"partitionActiveSlots"))[0]=1;
        when(client.listContainersCmd()).thenThrow(new IllegalStateException("Provider listing unavailable"));
        reconcile();assertEquals(container,tracked().get(1001));assertEquals(1,((int[])field(backend,"partitionActiveSlots"))[0]);
        artifacts.verify(()->SolverBuildArtifacts.retryRefusedPodmanCreation(any(),anyInt(),anyString(),any()),never());
    }
}
