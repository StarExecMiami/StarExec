package org.starexec.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.database.SolverBuildCreations;
import org.starexec.data.to.SolverBuildStopReceipt;

class PodmanPendingCreationRecoveryTest {
    @TempDir Path files;
    private final String container="a".repeat(64),image="sha256:"+"b".repeat(64);
    private final UUID token=UUID.randomUUID();
    private PodmanBackend backend;
    private DockerClient client;
    private InspectContainerResponse inspection;
    private InspectContainerResponse.ContainerState state;
    private HostConfig host;
    private ListContainersCmd list;
    private RemoveContainerCmd remove;
    private SolverBuildCreations.Creation creation;
    private BuildExecutionContext build;
    private MockedStatic<SolverBuildCreations> creations;
    private MockedStatic<SolverBuildArtifacts> artifacts;
    private MockedStatic<JobPairs> pairs;
    private MockedStatic<PodmanBuildCreationRecovery> recovery;
    private final AtomicBoolean removed=new AtomicBoolean();
    private final AtomicReference<SolverBuildStopReceipt> receipt=new AtomicReference<>();

    private Object field(String name) throws Exception {
        var f=PodmanBackend.class.getDeclaredField(name);f.setAccessible(true);return f.get(backend);
    }
    private void field(String name,Object value) throws Exception {
        var f=PodmanBackend.class.getDeclaredField(name);f.setAccessible(true);f.set(backend,value);
    }
    private void reconcile() throws Exception {
        var method=PodmanBackend.class.getDeclaredMethod("reconcileOrphanedPairs");method.setAccessible(true);method.invoke(backend);
    }

    @BeforeEach void setup() throws Exception {
        backend=new PodmanBackend();client=mock(DockerClient.class);
        field("dockerClient",client);field("partitions",List.of(new CpuPartition(0,"2",null)));
        field("partitionActiveSlots",new int[]{0});field("partitionMaxSlots",new int[]{1});
        field("partitionSlotLocks",new Object[]{new Object()});field("partitionNodeIds",new int[]{-1});
        build=new BuildExecutionContext(5,42,1,token,files.resolve("source"),files.resolve("staging"));
        Map<String,String> labels=Map.of("starexec.managed","true","starexec.label.version","2","starexec.kind","job-pair",
                "starexec.build.artifact","5","starexec.build.layout","private-v1","starexec.pair.id","42",
                "starexec.attempt","1","starexec.exec.id","1001","starexec.partition.index","0");
        host=new HostConfig().withMemory(1024L).withMemorySwap(1024L).withPidsLimit(16L).withCpusetCpus("2")
                .withNetworkMode("none").withReadonlyRootfs(true).withPrivileged(false).withRestartPolicy(RestartPolicy.noRestart())
                .withMounts(List.of(new Mount().withType(MountType.BIND).withSource(files.resolve("source").toString())
                        .withTarget("/execution/source").withReadOnly(true)));
        var payload=new LinkedHashMap<String,Object>();payload.put("Image",image);payload.put("HostConfig",host);
        payload.put("Labels",labels);payload.put("User","0:0");payload.put("StopSignal","SIGTERM");
        payload.put("Entrypoint",List.of("/bin/bash"));payload.put("Cmd",List.of("hold"));payload.put("Env",List.of());
        payload.put("WorkingDir","/execution");payload.put("Healthcheck",Map.of("Test",List.of("NONE")));
        String json=new ObjectMapper().writeValueAsString(payload);
        creation=new SolverBuildCreations.Creation(build,"abcd","synthetic-resource",1001,"starexec-build-5",image,
                SolverBuildCreations.Transport.JAVA,json,SolverBuildCreations.digest(json));
        inspection=mock(InspectContainerResponse.class);state=mock(InspectContainerResponse.ContainerState.class);
        var configNode=(com.fasterxml.jackson.databind.node.ObjectNode)new ObjectMapper().readTree(json);
        configNode.remove(List.of("HostConfig","StopSignal"));
        var config=new ObjectMapper().treeToValue(configNode,ContainerConfig.class);
        when(inspection.getId()).thenReturn(container);when(inspection.getName()).thenReturn("/"+creation.name());
        when(inspection.getConfig()).thenReturn(config);when(inspection.getHostConfig()).thenReturn(host);when(inspection.getImageId()).thenReturn(image);
        when(inspection.getRawValues()).thenReturn(Map.of("Config",Map.of("StopSignal","SIGTERM")));
        when(inspection.getState()).thenReturn(state);when(state.getStatus()).thenReturn("created");
        when(state.getRunning()).thenReturn(false);when(state.getPaused()).thenReturn(false);when(state.getRestarting()).thenReturn(false);
        when(state.getPidLong()).thenReturn(0L);when(state.getStartedAt()).thenReturn("0001-01-01T00:00:00Z");
        when(inspection.getMounts()).thenReturn(List.of(new InspectContainerResponse.Mount()
                .withSource(files.resolve("source").toString()).withDestination(new Volume("/execution/source")).withRw(false)));
        var inspect=mock(InspectContainerCmd.class);when(client.inspectContainerCmd(container)).thenReturn(inspect);
        when(inspect.exec()).thenAnswer(call->{if(removed.get()) throw new NotFoundException("Owned resource absent");return inspection;});
        remove=mock(RemoveContainerCmd.class);when(client.removeContainerCmd(container)).thenReturn(remove);
        when(remove.withForce(false)).thenReturn(remove);when(remove.withRemoveVolumes(true)).thenReturn(remove);
        doAnswer(call->{assertNotNull(receipt.get());removed.set(true);return null;}).when(remove).exec();
        var listed=mock(Container.class);when(listed.getId()).thenReturn(container);when(listed.getNames()).thenReturn(new String[]{"/"+creation.name()});when(listed.getLabels()).thenReturn(labels);
        list=mock(ListContainersCmd.class);when(client.listContainersCmd()).thenReturn(list);when(list.withShowAll(true)).thenReturn(list);
        when(list.withNameFilter(anyCollection())).thenReturn(list);when(list.withLabelFilter(anyMap())).thenReturn(list);
        when(list.exec()).thenAnswer(call->removed.get()?List.of():List.of(listed));
        creations=mockStatic(SolverBuildCreations.class,CALLS_REAL_METHODS);
        creations.when(SolverBuildCreations::pending).thenAnswer(call->receipt.get()==null?List.of(creation):List.of());
        creations.when(SolverBuildCreations::maximumExecutionId).thenReturn(1001);
        creations.when(()->SolverBuildCreations.find(5)).thenReturn(Optional.of(creation));
        artifacts=mockStatic(SolverBuildArtifacts.class);
        artifacts.when(SolverBuildArtifacts::refusedPodmanCreations).thenAnswer(call->receipt.get()==null?List.of():List.of(
                new SolverBuildArtifacts.Artifact(5,42,1,7,7,8,1,build.source().toString(),build.staging().toString(),
                        null,null,token,"podman:1001:"+container,Instant.now(),"REJECTED")));
        artifacts.when(()->SolverBuildArtifacts.findStopReceipt(42,1)).thenAnswer(call->receipt.get()==null?Optional.empty():
                Optional.of(new SolverBuildArtifacts.StoredStopReceipt(receipt.get(),Instant.now())));
        artifacts.when(()->SolverBuildArtifacts.retryRefusedPodmanCreation(eq(build),eq(1001),eq(container),any()))
                .thenReturn(JobPairs.NeverStartedRetryResult.REQUEUED);
        pairs=mockStatic(JobPairs.class);pairs.when(()->JobPairs.getPairIdsByStatusCode(2)).thenReturn(List.of(42));
        pairs.when(()->JobPairs.getPairIdsByStatusCode(4)).thenReturn(List.of());
        recovery=mockStatic(PodmanBuildCreationRecovery.class,CALLS_REAL_METHODS);
        recovery.when(()->PodmanBuildCreationRecovery.cancel(eq(client),nullable(String.class),eq(inspection),eq(container),eq(creation)))
                .thenAnswer(call->{PodmanBuildCreationRecovery.requireCreated(inspection,container,creation);
                    receipt.set(PodmanBuildStopObserver.unacknowledgedCreationReceipt(inspection,container,creation));return true;});
    }

    @AfterEach void close() {
        if(recovery!=null) recovery.close();if(pairs!=null) pairs.close();if(artifacts!=null) artifacts.close();if(creations!=null) creations.close();
    }

    @Test void aNewControllerCancelsOnlyItsExactCreatedResourceAndSettlesItsRecoveredSlotOnce() throws Exception {
        reconcile();reconcile();
        assertNotNull(receipt.get());assertEquals("creation_unacknowledged",receipt.get().facts().get("reason").getAsString());
        assertEquals(creation.sha256(),receipt.get().facts().get("request_sha256").getAsString());
        verify(remove).exec();verify(remove).withForce(false);verify(remove,never()).withForce(true);
        verify(client,never()).createContainerCmd(anyString());verify(client,never()).startContainerCmd(anyString());
        assertEquals(0,((int[])field("partitionActiveSlots"))[0]);assertTrue(((Set<?>)field("pendingCreationExecutions")).isEmpty());
        assertEquals(1002,field("nextExecId"));assertTrue(backend.getActiveExecutionIds().isEmpty());
        pairs.verify(()->JobPairs.tryResetEnqueuedToPending(anyInt()),never());
    }

    @ParameterizedTest @ValueSource(strings={"absent","lookup-error","policy-error","commit-error","refused"})
    void unknownOrRefusedObservationRetainsTheSameExecutionAndCapacity(String fault) throws Exception {
        switch(fault) {
            case "absent" -> when(list.exec()).thenReturn(List.of());
            case "lookup-error" -> when(list.exec()).thenThrow(new IllegalStateException("Synthetic lookup loss"));
            case "policy-error" -> when(inspection.getImageId()).thenReturn("sha256:"+"c".repeat(64));
            case "commit-error" -> recovery.when(()->PodmanBuildCreationRecovery.cancel(any(),nullable(String.class),any(),anyString(),any()))
                    .thenThrow(new SQLException("Synthetic cancellation response loss"));
            case "refused" -> recovery.when(()->PodmanBuildCreationRecovery.cancel(any(),nullable(String.class),any(),anyString(),any())).thenReturn(false);
            default -> throw new AssertionError(fault);
        }
        reconcile();reconcile();
        assertEquals(Set.of(1001),backend.getActiveExecutionIds());assertEquals(1,((int[])field("partitionActiveSlots"))[0]);
        verify(client,never()).removeContainerCmd(anyString());verify(client,never()).createContainerCmd(anyString());
        verify(client,never()).startContainerCmd(anyString());pairs.verify(()->JobPairs.tryResetEnqueuedToPending(anyInt()),never());
    }

    @Test void aLostCancellationAcknowledgementCanRecoverFromTheReceiptWithoutResending() throws Exception {
        recovery.when(()->PodmanBuildCreationRecovery.cancel(any(),nullable(String.class),any(),anyString(),any()))
                .thenAnswer(call->{receipt.set(PodmanBuildStopObserver.unacknowledgedCreationReceipt(inspection,container,creation));
                    throw new SQLException("Synthetic lost acknowledgement after conservation");});
        reconcile();
        assertNotNull(receipt.get());verify(remove).exec();assertTrue(backend.getActiveExecutionIds().isEmpty());
        assertEquals(0,((int[])field("partitionActiveSlots"))[0]);verify(client,never()).createContainerCmd(anyString());
    }

    @Test void aLiveSubmissionFrameIsObservedWithoutCancellationOrAnotherSlot() throws Exception {
        ((Set<Integer>)field("inFlightBuildExecutions")).add(1001);
        reconcile();
        verify(client,never()).removeContainerCmd(anyString());assertNull(receipt.get());
        assertEquals(0,((int[])field("partitionActiveSlots"))[0]);
        recovery.verify(()->PodmanBuildCreationRecovery.cancel(any(),nullable(String.class),any(),anyString(),any()),never());
    }

    @Test void aFiniteObservedDefaultProcessLimitIsRequiredWhenTheRequestInheritsIt() throws Exception {
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)new ObjectMapper().readTree(creation.payload());
        ((com.fasterxml.jackson.databind.node.ObjectNode)payload.get("HostConfig")).putNull("PidsLimit");
        String json=payload.toString();
        var inherited=new SolverBuildCreations.Creation(build,creation.resourceKey(),creation.resourceIdentity(),creation.execution(),
                creation.name(),image,creation.transport(),json,SolverBuildCreations.digest(json));
        PodmanBuildCreationRecovery.requireCreated(inspection,container,inherited);
        host.withPidsLimit(-1L);
        assertThrows(java.io.IOException.class,()->PodmanBuildCreationRecovery.requireCreated(inspection,container,inherited));
    }

    @Test void aBoundHistoricalCreationStillAdvancesTheAllocatorAfterRestart() throws Exception {
        creations.when(SolverBuildCreations::pending).thenReturn(List.of());
        creations.when(SolverBuildCreations::maximumExecutionId).thenReturn(3003);
        reconcile();assertEquals(3004,field("nextExecId"));
        assertEquals(0,((int[])field("partitionActiveSlots"))[0]);verify(client,never()).removeContainerCmd(anyString());
    }

    @Test void anExhaustedIdentityRangeCannotAcknowledgeStartupRecovery() throws Exception {
        creations.when(SolverBuildCreations::maximumExecutionId).thenReturn(Integer.MAX_VALUE);
        reconcile();
        assertFalse((Boolean)field("creationCatalogRecovered"),"Failed identity recovery cannot initialize the backend");
        verify(client,never()).createContainerCmd(anyString());verify(client,never()).startContainerCmd(anyString());
    }
}
