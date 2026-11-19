package org.starexec.backend;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.BindPropagation;
import com.github.dockerjava.api.model.HostConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.starexec.config.EnvironmentConfig;
import org.starexec.data.database.ExecutionStorageLeases;
import org.starexec.data.database.PublishedSolverInputs;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.jobs.SolverArtifactTree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real immutable files and catalog snapshots; the engine transport is simulated. */
class PublishedSolverMountsTest {
    @TempDir Path files;
    private static final String IMAGE="sha256:"+"a".repeat(64), CONTAINER="c".repeat(64);
    private Path store,output,solver;
    private SolverBuildArtifacts.Artifact artifact;
    private SolverBuildArtifacts.OutputManifest manifest;
    private DockerClient client;
    private InspectImageResponse inspected;
    private ContainerConfig imageConfig;
    private MockedStatic<EnvironmentConfig> configuration;
    private MockedStatic<PublishedSolverInputs> inputs;
    private MockedStatic<ExecutionStorageLeases> leases;
    private MockedStatic<PodmanPrivateBuild> provider;

    @BeforeEach void fixture() throws Exception {
        store=Files.createDirectory(files.resolve("artifacts"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path source=Files.createDirectory(files.resolve("source"));
        Files.writeString(Files.createDirectory(source.resolve("solver")).resolve("program"),"built\n");
        Files.writeString(Files.createDirectory(source.resolve("logs")).resolve("build.log"),"log\n");
        UUID token=UUID.randomUUID();output=store.resolve("build-5-"+token);solver=output.resolve("solver");
        var snapshot=SolverArtifactTree.copy(source,output,1024,20);
        manifest=new SolverBuildArtifacts.OutputManifest(snapshot.payload(),snapshot.sha256(),snapshot.byteSize());
        artifact=new SolverBuildArtifacts.Artifact(5,42,1,7,7,9,1,source.toString(),files.resolve("stage").toString(),
                output.toString(),solver.toString(),token,"podman:1001:"+CONTAINER,Instant.now(),"PUBLISHED");
        configuration=mockStatic(EnvironmentConfig.class,CALLS_REAL_METHODS);
        configuration.when(EnvironmentConfig::getBuildArtifactDirectory).thenReturn(store.toString());
        inputs=mockStatic(PublishedSolverInputs.class);
        inputs.when(()->PublishedSolverInputs.forPair(42,1)).thenReturn(List.of(new PublishedSolverInputs.Input(artifact,manifest)));
        leases=mockStatic(ExecutionStorageLeases.class);
        leases.when(ExecutionStorageLeases::registeredRootPaths).thenReturn(List.of(files.resolve("execution")));
        provider=mockStatic(PodmanPrivateBuild.class,CALLS_REAL_METHODS);
        provider.when(()->PodmanPrivateBuild.requireLocalRootlessProvider(any(),anyString())).thenAnswer(v->null);
        client=mock(DockerClient.class);var inspection=mock(InspectImageCmd.class);inspected=mock(InspectImageResponse.class);
        imageConfig=new ContainerConfig();when(inspected.getConfig()).thenReturn(imageConfig);when(inspected.getId()).thenReturn(IMAGE);
        when(client.inspectImageCmd("runner")).thenReturn(inspection);when(inspection.exec()).thenReturn(inspected);
    }

    @AfterEach void restoreAndClose() throws Exception {
        if (provider!=null) provider.close();if (leases!=null) leases.close();if (inputs!=null) inputs.close();
        if (configuration!=null) configuration.close();
        if (files!=null) try (var paths=Files.walk(files)) {
            for (Path path:paths.filter(Files::isDirectory).toList()) {
                Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));
            }
        }
    }

    private PublishedSolverMounts plan() throws Exception {
        return PublishedSolverMounts.prepare(client,"unix:///synthetic/socket","runner",42,1,List.of(files.resolve("runner")));
    }

    @Test void mapsOnlyTheSolverSubtreeReadOnlyAndPreservesConfiguredLimits() throws Exception {
        var plan=plan();var host=new HostConfig().withMemory(134217728L).withMemorySwap(134217728L)
                .withPidsLimit(64L).withCpusetCpus("2").withNetworkMode("none");
        assertSame(host,plan.hostConfig(host));assertEquals(IMAGE,plan.image());
        assertEquals(1,host.getMounts().size());var mount=host.getMounts().get(0);
        assertEquals(solver.toString(),mount.getSource());assertEquals(solver.toString(),mount.getTarget());
        assertEquals(Boolean.TRUE,mount.getReadOnly());assertEquals(BindPropagation.R_PRIVATE,mount.getBindOptions().getPropagation());
        assertEquals(134217728L,host.getMemory());assertEquals(134217728L,host.getMemorySwap());
        assertEquals(64L,host.getPidsLimit());assertEquals("2",host.getCpusetCpus());assertEquals("none",host.getNetworkMode());
        assertFalse(host.getPrivileged());assertFalse(host.getAutoRemove());assertTrue(host.getSecurityOpts().contains("no-new-privileges"));
        assertEquals(List.of("/bin/bash","--noprofile","--norc","-p","-c"),plan.entrypoint().subList(0,5));
        assertTrue(plan.entrypoint().contains(solver.toString()));assertFalse(plan.entrypoint().contains(output.resolve("logs").toString()));
    }

    @Test void emptyCatalogPreservesLegacyContainersWithoutRequiringTheNewRoot() throws Exception {
        inputs.when(()->PublishedSolverInputs.forPair(42,1)).thenReturn(List.of());
        configuration.when(EnvironmentConfig::getBuildArtifactDirectory).thenReturn("");
        assertNull(plan());verifyNoInteractions(client);
    }

    @ParameterizedTest @ValueSource(strings={"missing-root","wrong-root","writable-root","tampered-bytes","linked-solver", "image-volumes","image-id","remote-provider"})
    void refusesUnverifiedInputsBeforeAContainerCanBeCreated(String condition) throws Exception {
        switch(condition) {
            case "missing-root" -> configuration.when(EnvironmentConfig::getBuildArtifactDirectory).thenReturn("");
            case "wrong-root" -> configuration.when(EnvironmentConfig::getBuildArtifactDirectory).thenReturn(files.resolve("source").toString());
            case "writable-root" -> Files.setPosixFilePermissions(store,PosixFilePermissions.fromString("rwxrwx---"));
            case "tampered-bytes" -> {
                Files.setPosixFilePermissions(solver.resolve("program"),PosixFilePermissions.fromString("rw-------"));
                Files.writeString(solver.resolve("program"),"tampered\n");
            }
            case "linked-solver" -> {
                Files.setPosixFilePermissions(output,PosixFilePermissions.fromString("rwx------"));
                Files.move(solver,output.resolve("other"));Files.createSymbolicLink(solver,output.resolve("other"));
            }
            case "image-volumes" -> imageConfig.withVolumes(Map.of("/declared",Map.of()));
            case "image-id" -> when(inspected.getId()).thenReturn("runner:latest");
            case "remote-provider" -> provider.when(()->PodmanPrivateBuild.requireLocalRootlessProvider(any(),anyString())).thenThrow(new IOException("Remote peer"));
            default -> throw new AssertionError(condition);
        }
        assertThrows(IOException.class,this::plan);verify(client,never()).createContainerCmd(anyString());
    }

    @Test void refusesAnExplicitPrivilegeDowngradeAndAReplacedStore() throws Exception {
        var plan=plan();
        assertThrows(IOException.class,()->plan.hostConfig(new HostConfig().withSecurityOpts(List.of("no-new-privileges:false"))));
        Files.move(store,files.resolve("replaced"));Files.createDirectory(store,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        assertThrows(IOException.class,()->plan.hostConfig(new HostConfig()));
    }

    private InspectContainerResponse created(PublishedSolverMounts plan,String fault) throws Exception {
        var labels=new LinkedHashMap<>(Map.of("starexec.managed","true","starexec.label.version","2","starexec.pair.id","42",
                "starexec.attempt","1","starexec.exec.id","1001",PublishedSolverMounts.LABEL,PublishedSolverMounts.LAYOUT));
        var config=new LinkedHashMap<String,Object>();config.put("Labels",labels);config.put("Entrypoint",plan.entrypoint());
        config.put("Cmd",List.of("/runner/jobscript"));config.put("User","0:0");config.put("Healthcheck",Map.of("Test",List.of("NONE")));config.put("StopSignal","15");
        var state=new LinkedHashMap<String,Object>(Map.of("Status","created","Running",false,"Restarting",false,"Paused",false,"Pid",0,"StartedAt","0001-01-01T00:00:00Z"));
        var host=new LinkedHashMap<String,Object>(Map.of("Privileged",false,"PidMode","private"));
        var response=new LinkedHashMap<String,Object>(Map.of("Id",CONTAINER,"Image",IMAGE,"Config",config,"State",state,"HostConfig",host));
        switch(fault) {
            case "valid" -> { }
            case "container" -> response.put("Id","d".repeat(64));
            case "image" -> response.put("Image","sha256:"+"b".repeat(64));
            case "attempt" -> labels.put("starexec.attempt","2");
            case "execution" -> labels.put("starexec.exec.id","1002");
            case "layout" -> labels.remove(PublishedSolverMounts.LABEL);
            case "entrypoint" -> config.put("Entrypoint",List.of("/bin/bash"));
            case "command" -> config.put("Cmd",List.of("/other"));
            case "user" -> config.put("User","1000");
            case "healthcheck" -> config.remove("Healthcheck");
            case "stop" -> config.put("StopSignal","KILL");
            case "running" -> state.put("Running",true);
            case "started" -> state.put("StartedAt","2026-01-01T00:00:00Z");
            case "pid" -> state.put("Pid",44);
            case "privileged" -> host.put("Privileged",true);
            case "host-pid" -> host.put("PidMode","host");
            default -> throw new AssertionError(fault);
        }
        var json=com.github.dockerjava.core.DockerClientConfig.getDefaultObjectMapper();
        return json.readValue(json.writeValueAsBytes(response),InspectContainerResponse.class);
    }

    @Test void verifiesTheExactUnstartedConsumerBeforeStartingIt() throws Exception {
        var plan=plan();assertDoesNotThrow(()->plan.requireCreated(created(plan,"valid"),CONTAINER,42,1,1001,List.of("/runner/jobscript")));
    }

    @ParameterizedTest @ValueSource(strings={"container","image","attempt","execution","layout","entrypoint","command","user","healthcheck","stop","running","started","pid","privileged","host-pid"})
    void rejectsChangedConsumerIdentityOrStartupPolicy(String fault) throws Exception {
        var plan=plan();var observation=created(plan,fault);
        assertThrows(IOException.class,()->plan.requireCreated(observation,CONTAINER,42,1,1001,List.of("/runner/jobscript")));
    }
}
