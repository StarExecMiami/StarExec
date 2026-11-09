package org.starexec.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.BindOptions;
import com.github.dockerjava.api.model.BindPropagation;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.RestartPolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.starexec.backend.storage.BuildExecutionMounts;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.data.database.ExecutionStorageLeases;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.to.SolverBuildStopReceipt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the production fallback serializer without launching curl or an engine resource. */
class PodmanPrivateBuildDispatchTest {
    @TempDir Path files;

    @Test void zeroSwapGuardRemainsInTheProgramBeforePositionalTargets() {
        var original=org.starexec.backend.storage.BuildExecutionRuntime.observationCommand();
        var runtime=mock(org.starexec.backend.storage.BuildExecutionRuntime.class);
        when(runtime.observationCommandForBindings()).thenReturn(original);
        for (var command:List.of(PodmanPrivateBuild.observationCommand(),PodmanPrivateBuild.observationCommand(runtime))) {
            assertEquals(original.subList(0,5),command.subList(0,5));
            assertEquals(original.subList(6,original.size()),command.subList(6,command.size()));
            assertTrue(command.get(5).startsWith("IFS= read -r starexec_swap_limit < /sys/fs/cgroup/memory.swap.max || exit 76\n"));
            assertTrue(command.get(5).contains("[[ \"$starexec_swap_limit\" == 0 ]] || exit 76\n"));
            assertTrue(command.get(5).endsWith(original.get(5)));
        }
    }

    @ParameterizedTest @ValueSource(strings={"SIGTERM","TERM","15"})
    void providerNormalizationPreservesTheTerminationPolicy(String signal) {
        assertDoesNotThrow(()->PodmanPrivateBuild.requireStopPolicy(signal));
    }

    @ParameterizedTest @ValueSource(strings={"SIGKILL","9","SIGUSR2","12","0"," 15 ","+15","15.0",""})
    void anotherOrAmbiguousProviderSignalCannotReplaceTermination(String signal) {
        assertThrows(IOException.class,()->PodmanPrivateBuild.requireStopPolicy(signal));
    }

    @Test void numericProviderSignalStillRequiresExactTermination() throws Exception {
        PodmanPrivateBuild.requireStopPolicy(Integer.valueOf(15));
        assertThrows(IOException.class,()->PodmanPrivateBuild.requireStopPolicy((Object)null));
        assertThrows(IOException.class,()->PodmanPrivateBuild.requireStopPolicy(15.0));
        assertThrows(IOException.class,()->PodmanPrivateBuild.requireStopPolicy(9));
    }

    @ParameterizedTest @ValueSource(strings={"\"15\"","15","\"SIGTERM\"","\"TERM\""})
    void providerStopPolicyUsesTheOriginalInspectionEnvelope(String signal) throws Exception {
        var response=com.github.dockerjava.core.DockerClientConfig.getDefaultObjectMapper().readValue(
                "{\"Config\":{\"StopSignal\":"+signal+"}}",com.github.dockerjava.api.command.InspectContainerResponse.class);
        assertDoesNotThrow(()->PodmanPrivateBuild.requireStopPolicy(response));
    }

    @ParameterizedTest @ValueSource(strings={"{}","{\"Config\":{}}","{\"Config\":{\"StopSignal\":\"KILL\"}}",
            "{\"Config\":{\"StopSignal\":15.0}}","{\"Config\":{\"StopSignal\":null}}"})
    void unavailableOrUnrelatedStopPolicyIsNotInferredFromDefaults(String json) throws Exception {
        var response=com.github.dockerjava.core.DockerClientConfig.getDefaultObjectMapper().readValue(
                json,com.github.dockerjava.api.command.InspectContainerResponse.class);
        assertThrows(IOException.class,()->PodmanPrivateBuild.requireStopPolicy(response));
    }

    @Test void stoppedPrivateOutputUsesItsConservedProfileInsteadOfRunnerEnvironment() throws Exception {
        checkStoppedOutput("valid");
    }

    @ParameterizedTest @ValueSource(strings={"unknown-layout","missing-artifact","other-artifact","missing-stop",
            "cancelled","other-image","missing-resource","missing-lease","cleanup-started","released-lease",
            "missing-release","other-release-owner","linked-output"})
    void stoppedPrivateOutputRefusesUnverifiedOwnership(String condition) throws Exception {
        checkStoppedOutput(condition);
    }

    private void checkStoppedOutput(String condition) throws Exception {
        var build=new BuildExecutionContext(5,42,1,UUID.randomUUID(),files.resolve("source"),files.resolve("staging"));
        Files.createDirectory(build.staging());
        Path output=BuildExecutionMounts.hostOutputDirectory(build);
        if (condition.equals("linked-output")) {
            Files.createSymbolicLink(output,Files.createDirectory(files.resolve("foreign")));
        } else Files.createDirectory(output);
        String container="c".repeat(64),engine="podman:1001:"+container,image="sha256:"+"a".repeat(64);
        var inspection=mock(com.github.dockerjava.api.command.InspectContainerResponse.class);
        when(inspection.getId()).thenReturn(container);when(inspection.getImageId()).thenReturn(image);
        var config=new com.github.dockerjava.api.model.ContainerConfig().withLabels(Map.of(
                "starexec.managed","true","starexec.label.version","2","starexec.kind","job-pair",
                "starexec.pair.id","42","starexec.attempt","1","starexec.exec.id","1001",
                PodmanBuildStopObserver.ARTIFACT_LABEL,"5",PodmanPrivateBuild.LAYOUT_LABEL,
                condition.equals("unknown-layout")?"unknown":PodmanPrivateBuild.LAYOUT))
                .withEnv(new String[]{"STAREXEC_OUTPUT_DIR="+files.resolve("foreign")});
        when(inspection.getConfig()).thenReturn(config);
        var artifact=new SolverBuildArtifacts.Artifact(condition.equals("other-artifact")?6:5,42,1,7,7,9,1,
                build.source().toString(),build.staging().toString(),null,null,build.token(),engine,Instant.now(),"STAGED");
        var facts=new com.google.gson.JsonObject();
        facts.addProperty("image_id",condition.equals("other-image")?"other-image":image);
        if (condition.equals("cancelled")) facts.addProperty("disposition","cancelled");
        var receipt=SolverBuildStopReceipt.observed("podman",5,42,1,engine,container,Instant.now(),facts);
        var registration=mock(ExecutionStorageLeases.RegisteredResource.class);
        var resource=mock(DedicatedStorageResource.class);
        when(registration.inspect(anyList())).thenReturn(resource);
        var cleanup=new ExecutionStorageLeases.StagingCleanup(condition.equals("cleanup-started")?UUID.randomUUID():null,
                null,null,null,condition.equals("released-lease")?Instant.now():null);
        var release=new ExecutionStorageLeases.ReleaseDecision(UUID.randomUUID(),
                condition.equals("other-release-owner")?"podman:1002:other":engine,"conserved","digest",Instant.now());
        try (var artifacts=mockStatic(SolverBuildArtifacts.class);var leases=mockStatic(ExecutionStorageLeases.class)) {
            artifacts.when(()->SolverBuildArtifacts.find(42,1)).thenReturn(condition.equals("missing-artifact")?Optional.empty():Optional.of(artifact));
            artifacts.when(()->SolverBuildArtifacts.findStopReceipt(42,1)).thenReturn(condition.equals("missing-stop")?Optional.empty()
                    :Optional.of(new SolverBuildArtifacts.StoredStopReceipt(receipt,Instant.now())));
            leases.when(()->ExecutionStorageLeases.registeredResource(build)).thenReturn(condition.equals("missing-resource")?Optional.empty():Optional.of(registration));
            leases.when(()->ExecutionStorageLeases.findStagingCleanup(build,resource)).thenReturn(condition.equals("missing-lease")?Optional.empty():Optional.of(cleanup));
            leases.when(()->ExecutionStorageLeases.findReleaseDecision(build,resource)).thenReturn(condition.equals("missing-release")?Optional.empty():Optional.of(release));
            if (condition.equals("valid")) {
                assertEquals(output,PodmanPrivateBuild.stoppedOutputDirectory(inspection,container));
                verify(resource).requireUnchanged();
            } else assertThrows(java.io.IOException.class,()->PodmanPrivateBuild.stoppedOutputDirectory(inspection,container));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mounts", "ReadonlyRootfs", "CapDrop", "SecurityOpt", "PidsLimit",
            "NetworkMode", "ShmSize", "MemorySwap", "AutoRemove"})
    void fallbackPreservesTheCompleteConfiguredHostPolicy(String property) throws Exception {
        HostConfig host = new HostConfig().withMemory(128L * 1024 * 1024).withMemorySwap(128L * 1024 * 1024)
                .withPidsLimit(64L).withNetworkMode("none").withReadonlyRootfs(true).withPrivileged(false)
                .withCapDrop(Capability.ALL).withSecurityOpts(List.of("no-new-privileges"))
                .withRestartPolicy(RestartPolicy.noRestart()).withAutoRemove(false).withShmSize(1024L * 1024)
                .withMounts(List.of(new Mount().withType(MountType.BIND).withSource("/synthetic/input")
                        .withTarget("/starexec/inputs/source_src").withReadOnly(true)
                        .withBindOptions(new BindOptions().withPropagation(BindPropagation.R_PRIVATE))));
        ObjectMapper json = new ObjectMapper();
        JsonNode expected = json.readTree(json.writeValueAsString(host));
        assertNotNull(expected.get(property), "the SDK must declare the expected API property");
        String id = "c".repeat(64);
        var process=response("{\"Id\":\""+id+"\",\"Warnings\":[]}");
        List<List<String>> commands = new ArrayList<>();
        assertEquals(id,create(host,process,commands,false));
        assertEquals(1, commands.size());
        List<String> command = commands.get(0);
        assertEquals("curl", command.get(0));
        int data = command.indexOf("-d");
        assertTrue(data >= 0);
        JsonNode actual = json.readTree(command.get(data + 1));
        assertEquals(expected.get(property), actual.path("HostConfig").get(property),
                "fallback must preserve the same HostConfig as the Java transport");
    }

    @ParameterizedTest @ValueSource(strings={"malformed-id","duplicate-id","trailing-json","excessive-response"})
    void fallbackRefusesAnUnverifiableCreateResponse(String failure) throws Exception {
        String id="c".repeat(64),json="{\"Id\":\""+id+"\"}";
        switch (failure) {
            case "malformed-id" -> json="{\"Id\":\"not-a-container\"}";
            case "duplicate-id" -> json="{\"Id\":\""+id+"\",\"Id\":\""+"d".repeat(64)+"\"}";
            case "trailing-json" -> json+="{}";
            case "excessive-response" -> json+=" ".repeat(128*1024);
            default -> throw new AssertionError(failure);
        }
        var process=response(json);
        assertThrows(IOException.class,()->create(new HostConfig(),process,new ArrayList<>(),true));
    }

    @Test void fallbackDeadlineStopsOnlyItsOwnProcessWithoutAnUnboundedWait() throws Exception {
        var process=response("{\"Id\":\""+"c".repeat(64)+"\"}");
        when(process.waitFor()).thenThrow(new AssertionError("An unbounded provider wait is forbidden"));
        when(process.waitFor(anyLong(),eq(TimeUnit.SECONDS))).thenReturn(false);
        assertThrows(IOException.class,()->create(new HostConfig(),process,new ArrayList<>(),true));
        verify(process).destroyForcibly();
    }

    @Test void privateFallbackPreservesTheLauncherAndUsesBoundedLocalTransport() throws Exception {
        var commands=new ArrayList<List<String>>();
        assertEquals("c".repeat(64),create(new HostConfig(),response("{\"Id\":\""+"c".repeat(64)+"\"}"),commands,true));
        var command=commands.get(0);
        for (String option:List.of("-q","--fail","--max-time","--connect-timeout","--noproxy")) assertTrue(command.contains(option),option);
        var payload=new ObjectMapper().readTree(command.get(command.indexOf("-d")+1));
        assertEquals("0:0",payload.path("User").asText());assertEquals("SIGTERM",payload.path("StopSignal").asText());
        assertEquals("NONE",payload.path("Healthcheck").path("Test").get(0).asText());
        assertEquals("/bin/bash",payload.path("Entrypoint").get(0).asText());
        assertEquals("/starexec/control/jobscript",payload.path("Cmd").get(0).asText());
    }

    private Process response(String json) throws Exception {
        var process=mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        when(process.waitFor()).thenReturn(0);
        when(process.waitFor(anyLong(),eq(TimeUnit.SECONDS))).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        return process;
    }

    private String create(HostConfig host,Process process,List<List<String>> commands,boolean privateBuild) throws Exception {
        PodmanBackend backend = new PodmanBackend();
        Field socket = PodmanBackend.class.getDeclaredField("containerSocketPath");
        socket.setAccessible(true);
        socket.set(backend, "/synthetic/container.sock");
        Method create = PodmanBackend.class.getDeclaredMethod("createContainerWithCurl",String.class,String.class,
                HostConfig.class,List.class,Map.class,String.class,String.class,List.class,List.class,boolean.class);
        create.setAccessible(true);
        try (var builders = mockConstruction(ProcessBuilder.class, (builder, context) -> {
            assertEquals(1, context.arguments().size());
            commands.add(Arrays.asList((String[]) context.arguments().get(0)));
            when(builder.redirectErrorStream(true)).thenReturn(builder);
            when(builder.start()).thenReturn(process);
        })) {
            try { return (String)create.invoke(backend,"synthetic-build","synthetic-image",host,
                    List.of("CONTAINER_MODE=true"),Map.of("starexec.managed","true"),
                    "synthetic-worker","/starexec/execution/work",List.of("/bin/bash"),
                    List.of("/starexec/control/jobscript"),privateBuild); }
            catch (InvocationTargetException failed) {
                if (failed.getCause() instanceof Exception exception) throw exception;
                throw (Error)failed.getCause();
            }
        }
    }
}
