package org.starexec.backend.storage;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.BindPropagation;
import com.github.dockerjava.api.model.Volume;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.starexec.backend.BuildExecutionContext;
import org.starexec.data.database.ExecutionStorageLeases;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.jobs.SolverArtifactTree;
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real file observations; database authorization and dedicated capacity are synthetic. */
class BuildExecutionMountsTest {
    @TempDir Path files;
    private Connection connection;
    private DedicatedStorageResource resource;
    private BuildExecutionContext build;
    private BuildExecutionMounts.Controls controls;
    private ExecutionStorageLeases.DispatchInputs inputs;

    private Path privateDirectory(String name) throws Exception {
        return Files.createDirectory(files.resolve(name),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    @BeforeEach void seed() throws Exception {
        Path source=privateDirectory("source_src");
        Files.writeString(source.resolve("starexec_build"),"#!/bin/sh\nexit 0\n");
        Path helper=Files.writeString(files.resolve("starexec_build"),"helper");
        Path script=Files.writeString(files.resolve("jobscript"),"script");
        Path functions=Files.writeString(files.resolve("functions.bash"),"functions");
        Path status=Files.writeString(files.resolve("status_codes.bash"),"status codes");
        controls=new BuildExecutionMounts.Controls(script,functions,status);
        Path slot=privateDirectory("slot"); UUID token=UUID.randomUUID();
        Path staging=Files.createDirectory(slot.resolve("build-"+token),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        build=new BuildExecutionContext(7,11,1,token,source,staging);
        connection=mock(Connection.class);
        resource=mock(DedicatedStorageResource.class);
        when(resource.root()).thenReturn(slot);
        when(resource.capacityBytes()).thenReturn(1024L*1024);
        when(resource.inodeLimit()).thenReturn(2048L);
        inputs=new ExecutionStorageLeases.DispatchInputs(13,1,source,
                new SolverBuildArtifacts.Helper(17,13,helper.toString(),Files.size(helper),null,null));
    }

    private MockedStatic<ExecutionStorageLeases> authorization() throws Exception {
        var guard=mockStatic(ExecutionStorageLeases.class);
        guard.when(()->ExecutionStorageLeases.findDispatchInputs(connection,build,resource)).thenReturn(Optional.of(inputs));
        return guard;
    }

    private BuildExecutionMounts prepare() throws Exception {
        return BuildExecutionMounts.prepare(connection,build,resource,controls,1024,64);
    }

    @Test void aNewObservationRestoresOnlyTheSameOriginalFingerprint() throws Exception {
        try(var guard=authorization()) {
            var original=prepare();String fingerprint=original.fingerprint();assertTrue(fingerprint.matches("[0-9a-f]{64}"));
            var restored=BuildExecutionMounts.restore(connection,build,resource,controls,1024,64,fingerprint);
            assertEquals(fingerprint,restored.fingerprint());assertEquals(original.bindings(),restored.bindings());
        }
    }

    @Test void restartCannotRecreateAMissingPrivateDirectory() throws Exception {
        try(var guard=authorization()) {
            var original=prepare();String fingerprint=original.fingerprint();Path work=build.staging().resolve("work");
            Files.delete(work);
            assertThrows(IOException.class,()->BuildExecutionMounts.restore(connection,build,resource,controls,1024,64,fingerprint));
            assertFalse(Files.exists(work));
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"source","helper","script","functions","status","permissions","replaced-control","replaced-directory","relocated-control"})
    void restartCannotAdoptChangedInputsOrPhysicalRoles(String change) throws Exception {
        try(var guard=authorization()) {
            var original=prepare();String fingerprint=original.fingerprint();var recoveredControls=controls;
            switch(change) {
                case "source" -> Files.writeString(build.source().resolve("starexec_build"),"changed source\n");
                case "helper" -> Files.writeString(Path.of(inputs.helper().path()),"HELPER");
                case "script" -> Files.writeString(controls.script(),"SCRIPT");
                case "functions" -> Files.writeString(controls.functions(),"FUNCTIONS");
                case "status" -> Files.writeString(controls.statusCodes(),"STATUS CODES");
                case "permissions" -> Files.setPosixFilePermissions(controls.functions(),PosixFilePermissions.fromString("rwx------"));
                case "replaced-control" -> {
                    Path replacement=Files.writeString(files.resolve("replacement"),Files.readString(controls.script()));
                    Files.setLastModifiedTime(replacement,Files.getLastModifiedTime(controls.script()));
                    Files.move(replacement,controls.script(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                case "replaced-directory" -> {
                    Path work=build.staging().resolve("work");Files.delete(work);
                    Files.createDirectory(work,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                }
                case "relocated-control" -> recoveredControls=new BuildExecutionMounts.Controls(
                        Files.copy(controls.script(),files.resolve("relocated")),controls.functions(),controls.statusCodes());
                default -> throw new AssertionError(change);
            }
            var requested=recoveredControls;
            assertThrows(IOException.class,()->BuildExecutionMounts.restore(connection,build,resource,requested,1024,64,fingerprint));
        }
    }

    @Test void anUnknownOriginalFingerprintCannotObserveOrMaterializeRecovery() throws Exception {
        try(var guard=authorization()) {
            assertThrows(IOException.class,()->BuildExecutionMounts.restore(connection,build,resource,controls,1024,64,null));
            assertThrows(IOException.class,()->BuildExecutionMounts.restore(connection,build,resource,controls,1024,64,"unknown"));
            guard.verify(()->ExecutionStorageLeases.findDispatchInputs(any(),any(),any()),never());
            assertFalse(Files.exists(build.staging().resolve("work")));
        }
    }

    @AfterEach void restoreOwnedDirectories() throws Exception {
        Files.walkFileTree(files,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private HostConfig limits() {
        return new HostConfig().withMemory(64L*1024*1024).withMemorySwap(64L*1024*1024)
                .withCpusetCpus("2").withCpusetMems("0").withNetworkMode("none");
    }

    private Map<Path,Path> providerPaths(BuildExecutionMounts layout) {
        Map<Path,Path> paths=new LinkedHashMap<>();
        for (var binding:layout.bindings()) {
            paths.put(binding.source(),Path.of("/provider-view").resolve(
                    binding.source().getRoot().relativize(binding.source())));
        }
        return paths;
    }

    @Test void explicitProviderPathsPreserveApplicationAndRunnerLocators() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare();var original=List.copyOf(layout.bindings());
            var paths=providerPaths(layout);
            var configured=layout.toPodmanHostConfig(limits().withPidsLimit(64L),paths);
            assertEquals(original.size(),configured.getMounts().size());
            for (int i=0;i<original.size();i++) {
                var binding=original.get(i);var actual=configured.getMounts().get(i);
                assertEquals(paths.get(binding.source()).toString(),actual.getSource());
                assertEquals(binding.target().toString(),actual.getTarget());
                assertEquals(binding.readOnly(),actual.getReadOnly());
            }
            assertEquals(original,layout.bindings());
            assertEquals(build.staging().resolve("monitor"),layout.hostOutputDirectory());
            assertEquals(layout.outputDirectory().toString(),layout.environment().get("STAREXEC_OUTPUT_DIR"));
            assertFalse(String.join(" ",layout.environment().values()).contains("/provider-view"));
            assertEquals(64L,configured.getPidsLimit());
            assertEquals("2",configured.getCpusetCpus());
        }
    }

    @Test void incompleteOrAdditionalProviderMappingsHaveNoImplicitFallback() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare();var missing=providerPaths(layout);
            missing.remove(layout.bindings().get(0).source());
            assertThrows(IllegalArgumentException.class,()->layout.toPodmanHostConfig(limits(),missing));
            var extra=providerPaths(layout);
            extra.put(files.resolve("undeclared"),Path.of("/provider-view/undeclared"));
            assertThrows(IllegalArgumentException.class,()->layout.toPodmanHostConfig(limits(),extra));
        }
    }

    @Test void providerPathsRequireNormalizedAbsoluteNonrootLocations() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare();
            for (Path path:List.of(Path.of("relative"),Path.of("/provider/../outside"),Path.of("/"))) {
                var paths=providerPaths(layout);paths.put(build.source(),path);
                assertThrows(IllegalArgumentException.class,()->layout.toPodmanHostConfig(limits(),paths));
            }
        }
    }

    @Test void providerMappingsCannotContainNullPaths() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare();
            assertThrows(IllegalArgumentException.class,()->layout.toPodmanHostConfig(limits(),null));
            var nullValue=providerPaths(layout);nullValue.put(build.source(),null);
            assertThrows(IllegalArgumentException.class,()->layout.toPodmanHostConfig(limits(),nullValue));
            var nullKey=providerPaths(layout);nullKey.put(null,Path.of("/provider-view/extra"));
            assertThrows(IllegalArgumentException.class,()->layout.toPodmanHostConfig(limits(),nullKey));
        }
    }

    @Test void providerMappingsCannotMergeDistinctSources() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare();var paths=providerPaths(layout);
            paths.put(layout.bindings().get(0).source(),paths.get(layout.bindings().get(1).source()));
            assertThrows(IllegalArgumentException.class,()->layout.toPodmanHostConfig(limits(),paths));
        }
    }

    @Test void providerMappingsCannotIntroduceReadonlyWritableOverlap() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare();
            for (boolean parent:List.of(false,true)) {
                var paths=providerPaths(layout);Path writable=paths.get(build.staging());
                paths.put(build.source(),parent?writable.getParent():writable.resolve("input"));
                assertThrows(IllegalArgumentException.class,()->layout.toPodmanHostConfig(limits(),paths));
            }
        }
    }

    @Test void providerScratchCannotLeaveOrMoveWithinItsStagingTree() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare();
            for (String child:List.of("tmp","run","var-tmp")) {
                var outside=providerPaths(layout);
                outside.put(build.staging().resolve(child),Path.of("/outside-provider").resolve(child));
                assertThrows(IllegalArgumentException.class,()->layout.toPodmanHostConfig(limits(),outside));
                var renamed=providerPaths(layout);
                renamed.put(build.staging().resolve(child),renamed.get(build.staging()).resolve("other-"+child));
                assertThrows(IllegalArgumentException.class,()->layout.toPodmanHostConfig(limits(),renamed));
            }
        }
    }

    @Test void independentReadonlyProviderLocationsRemainSupported() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare();var paths=providerPaths(layout);
            Path helper=Path.of(inputs.helper().path());
            paths.put(helper,Path.of("/separate-readonly-helper/starexec_build"));
            var configured=layout.toPodmanHostConfig(limits(),paths);
            assertEquals(paths.get(helper).toString(),configured.getMounts().get(1).getSource());
            assertEquals(Boolean.TRUE,configured.getMounts().get(1).getReadOnly());
        }
    }

    @Test void identityProviderMappingPreservesTheExistingRequest() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare();Map<Path,Path> paths=new LinkedHashMap<>();
            for (var binding:layout.bindings()) paths.put(binding.source(),binding.source());
            var json=new ObjectMapper();
            assertEquals(json.valueToTree(layout.toPodmanHostConfig(limits())),
                    json.valueToTree(layout.toPodmanHostConfig(limits(),paths)));
        }
    }

    private long stagingEntries() throws Exception {
        try (var contents=Files.list(build.staging())) { return contents.count(); }
    }

    @Test void legacyBroadWritableMountsCannotSurvivePrivateBuildRendering() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare();
            HostConfig legacy=new HostConfig()
                    .withBinds(new Bind(files.resolve("application-data").toString(),new Volume("/data")))
                    .withMemory(64L*1024*1024).withMemorySwap(64L*1024*1024)
                    .withCpusetCpus("2").withCpusetMems("0").withNetworkMode("none");
            HostConfig actual=layout.toPodmanHostConfig(legacy);
            assertTrue(actual.getBinds()==null || actual.getBinds().length==0);
            assertNotNull(actual.getMounts());
            assertEquals(9,actual.getMounts().size());
            assertEquals(Boolean.TRUE,actual.getReadonlyRootfs());
            assertEquals(Boolean.FALSE,actual.getPrivileged());
            assertEquals(legacy.getMemory(),actual.getMemory()); assertEquals(legacy.getMemorySwap(),actual.getMemorySwap());
            assertEquals(legacy.getCpusetCpus(),actual.getCpusetCpus()); assertEquals(legacy.getCpusetMems(),actual.getCpusetMems());
        }
    }

    @Test void readonlyInputsHaveNoWritableHostOrNamespaceAlias() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare();
            for (var input:layout.bindings().stream().filter(BuildExecutionMounts.Binding::readOnly).toList()) {
                for (var writable:layout.bindings().stream().filter(binding->!binding.readOnly()).toList()) {
                    assertFalse(input.source().startsWith(writable.source()) || writable.source().startsWith(input.source()));
                    assertFalse(input.target().startsWith(writable.target()) || writable.target().startsWith(input.target()));
                }
            }
            assertEquals(5,layout.bindings().stream().filter(BuildExecutionMounts.Binding::readOnly).count());
            assertEquals(4,layout.bindings().stream().filter(binding->!binding.readOnly()).count());
            assertThrows(UnsupportedOperationException.class,()->layout.bindings().clear());
            assertEquals(build,layout.build());
        }
    }

    @Test void providerDefaultScratchPathsUseTheReservedFilesystem() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare();
            for (var entry:Map.of("/tmp","tmp","/run","run","/var/tmp","var-tmp").entrySet()) {
                var binding=layout.bindings().stream().filter(value->value.target().equals(Path.of(entry.getKey())))
                        .findFirst().orElseThrow(()->new AssertionError("Unreserved provider scratch path: "+entry.getKey()));
                assertFalse(binding.readOnly());
                assertEquals(build.staging().resolve(entry.getValue()),binding.source());
                assertTrue(Files.isDirectory(binding.source()));
                assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(binding.source()));
            }
        }
    }

    @Test void providerRequestsSerializeReadonlyMountsAndPrivatePropagation() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare();
            var configured=layout.toPodmanHostConfig(limits().withPrivileged(true).withTmpFs(Map.of("/scratch","size=0")));
            var json=new ObjectMapper().valueToTree(configured);
            assertTrue(json.path("ReadonlyRootfs").asBoolean());
            assertFalse(json.path("Privileged").asBoolean(true));
            assertTrue(json.path("RestartPolicy").isObject());
            assertTrue(json.path("RestartPolicy").has("Name"));
            assertTrue(List.of("","no").contains(json.path("RestartPolicy").path("Name").asText()));
            assertTrue(json.path("RestartPolicy").has("MaximumRetryCount"));
            assertEquals(0,json.path("RestartPolicy").path("MaximumRetryCount").asInt(-1));
            assertTrue(configured.getTmpFs()==null || configured.getTmpFs().isEmpty());
            assertTrue(configured.getVolumesFrom()==null || configured.getVolumesFrom().length==0);
            assertEquals(List.of(Capability.ALL),List.of(configured.getCapDrop()));
            assertTrue(configured.getSecurityOpts().contains("no-new-privileges"));
            for (int i=0;i<layout.bindings().size();i++) {
                var expected=layout.bindings().get(i);
                var serialized=json.path("Mounts").get(i);
                assertEquals(expected.source().toString(),serialized.path("Source").asText());
                assertEquals(expected.target().toString(),serialized.path("Target").asText());
                assertEquals(expected.readOnly(),serialized.path("ReadOnly").asBoolean());
                assertEquals("rprivate",serialized.path("BindOptions").path("Propagation").asText());
                assertEquals(BindPropagation.R_PRIVATE,configured.getMounts().get(i).getBindOptions().getPropagation());
            }
        }
    }

    @Test void configuredCpuAndMemoryLimitsArePreserved() throws Exception {
        try (var guard=authorization()) {
            var requested=limits().withCpuPeriod(100000L).withCpuQuota(200000L).withCpuShares(256)
                    .withMemoryReservation(32L*1024*1024).withMemorySwappiness(0L).withShmSize(16L*1024*1024)
                    .withKernelMemory(8L*1024*1024);
            var actual=prepare().toPodmanHostConfig(requested);
            assertEquals(100000L,actual.getCpuPeriod()); assertEquals(200000L,actual.getCpuQuota());
            assertEquals(256,actual.getCpuShares()); assertEquals("2",actual.getCpusetCpus()); assertEquals("0",actual.getCpusetMems());
            assertEquals(requested.getMemory(),actual.getMemory()); assertEquals(requested.getMemorySwap(),actual.getMemorySwap());
            assertEquals(32L*1024*1024,actual.getMemoryReservation()); assertEquals(0L,actual.getMemorySwappiness());
            assertEquals(16L*1024*1024,actual.getShmSize()); assertEquals(8L*1024*1024,actual.getKernelMemory());
        }
    }

    @Test void requestedProcessLimitMustSurvivePrivateRendering() throws Exception {
        try (var guard=authorization()) {
            var actual=prepare().toPodmanHostConfig(limits().withPidsLimit(64L));
            assertEquals(64L,actual.getPidsLimit(),"Private rendering dropped the configured process bound");
        }
    }

    @ParameterizedTest @ValueSource(strings={"host","container:other","ns:/run/other-network"})
    void sharedNetworkNamespacesAreRefused(String network) throws Exception {
        try (var guard=authorization()) {
            var layout=prepare();
            assertThrows(IllegalArgumentException.class,()->layout.toPodmanHostConfig(limits().withNetworkMode(network)));
        }
    }

    @ParameterizedTest @ValueSource(longs={0,-1})
    void nonpositiveMemoryLimitsAreRefused(long bytes) throws Exception {
        try (var guard=authorization()) {
            var layout=prepare();
            assertThrows(IllegalArgumentException.class,()->layout.toPodmanHostConfig(limits().withMemory(bytes)));
        }
    }

    @Test void unknownMemoryLimitIsRefused() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare();
            assertThrows(IllegalArgumentException.class,()->layout.toPodmanHostConfig(new HostConfig()));
        }
    }

    @Test void runnerEnvironmentUsesOnlyItsPrivateNamespace() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare(); var environment=layout.environment();
            assertEquals("1",environment.get(BuildExecutionMounts.PRIVATE_VARIABLE));
            assertEquals(BuildExecutionMounts.EXECUTION.toString(),new String(Base64.getDecoder()
                    .decode(environment.get(BuildExecutionContext.OUTPUT_VARIABLE)),StandardCharsets.UTF_8));
            assertEquals(BuildExecutionMounts.SOURCE.toString(),environment.get("STAREXEC_BUILD_SOURCE_DIR"));
            assertEquals(BuildExecutionMounts.HELPER.toString(),environment.get("STAREXEC_BUILD_HELPER_FILE"));
            assertEquals(BuildExecutionMounts.CONTROL.toString(),environment.get("STAREXEC_BUILD_CONTROL_DIR"));
            assertEquals(layout.workingDirectory().toString(),environment.get("STAREXEC_BUILD_WORK_DIR"));
            assertEquals(layout.outputDirectory().toString(),environment.get("STAREXEC_OUTPUT_DIR"));
            assertEquals(BuildExecutionMounts.CONTROL.resolve("jobscript"),layout.script());
            assertEquals(build.staging().resolve("monitor"),layout.hostOutputDirectory());
            assertFalse(String.join(" ",environment.values()).contains(build.staging().toString()));
            assertThrows(UnsupportedOperationException.class,()->environment.clear());
        }
    }

    @Test void missingAuthorizationCannotCreatePrivateDirectories() throws Exception {
        try (var guard=authorization()) {
            guard.when(()->ExecutionStorageLeases.findDispatchInputs(connection,build,resource)).thenReturn(Optional.empty());
            assertThrows(SQLException.class,this::prepare);
            assertEquals(0,stagingEntries());
        }
    }

    @Test void revokedAuthorizationAfterPreparationCannotReachAdmission() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare();
            guard.when(()->ExecutionStorageLeases.findDispatchInputs(connection,build,resource)).thenReturn(Optional.empty());
            assertThrows(SQLException.class,()->layout.requireCurrent(connection));
            assertEquals(6,stagingEntries());
        }
    }

    @Test void failedPostCreationAuthorizationRetainsDirectoriesForSafeRetry() throws Exception {
        try (var guard=authorization()) {
            guard.when(()->ExecutionStorageLeases.findDispatchInputs(connection,build,resource))
                    .thenReturn(Optional.of(inputs),Optional.empty());
            assertThrows(SQLException.class,this::prepare);
            assertEquals(6,stagingEntries());
            guard.when(()->ExecutionStorageLeases.findDispatchInputs(connection,build,resource)).thenReturn(Optional.of(inputs));
            assertEquals(build,prepare().build());
            assertEquals(6,stagingEntries());
        }
    }

    @Test void changedInputOwnershipCannotCreateDirectories() throws Exception {
        try (var guard=authorization()) {
            var wrong=new ExecutionStorageLeases.DispatchInputs(19,inputs.generation(),inputs.source(),inputs.helper());
            guard.when(()->ExecutionStorageLeases.findDispatchInputs(connection,build,resource)).thenReturn(Optional.of(wrong));
            assertThrows(IOException.class,this::prepare);
            assertEquals(0,stagingEntries());
        }
    }

    @Test void helperBytesMustMatchTheConservedCharge() throws Exception {
        try (var guard=authorization()) {
            Files.writeString(Path.of(inputs.helper().path()),"changed helper");
            assertThrows(IOException.class,this::prepare);
            assertEquals(0,stagingEntries());
        }
    }

    @Test void helperChangesWithTheSameSizeAndTimestampAreRefused() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare(); Path helper=Path.of(inputs.helper().path()); var modified=Files.getLastModifiedTime(helper);
            Files.writeString(helper,"HELPER"); Files.setLastModifiedTime(helper,modified);
            assertThrows(IOException.class,()->layout.requireCurrent(connection));
        }
    }

    @ParameterizedTest @ValueSource(strings={"script","functions","status"})
    void changedControlBytesCannotBeHiddenByRestoringTheTimestamp(String role) throws Exception {
        try (var guard=authorization()) {
            var layout=prepare();
            Path control=role.equals("script")?controls.script():role.equals("functions")?controls.functions():controls.statusCodes();
            var modified=Files.getLastModifiedTime(control); long size=Files.size(control);
            Files.writeString(control,"Z".repeat((int)size)); Files.setLastModifiedTime(control,modified);
            assertThrows(IOException.class,()->layout.requireCurrent(connection));
        }
    }

    @Test void sourceChangesWithTheSameSizeAndTimestampAreRefused() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare(); Path input=build.source().resolve("starexec_build"); var modified=Files.getLastModifiedTime(input);
            Files.writeString(input,"#!/bin/sh\nexit 1\n"); Files.setLastModifiedTime(input,modified);
            assertThrows(IOException.class,()->layout.requireCurrent(connection));
        }
    }

    @Test void inputObservationPreservesModesAndDoesNotWeakenPublicationChecks() throws Exception {
        try (var guard=authorization()) {
            var before=Files.getPosixFilePermissions(build.source());
            var observed=SolverArtifactTree.inspectInputDirectory(build.source(),1024,64);
            prepare();
            assertEquals(before,Files.getPosixFilePermissions(build.source()));
            assertThrows(IOException.class,()->SolverArtifactTree.verify(build.source(),observed));
            Path copy=files.resolve("published");
            var published=SolverArtifactTree.copy(build.source(),copy,1024,64);
            assertDoesNotThrow(()->SolverArtifactTree.verify(copy,published));
        }
    }

    @ParameterizedTest @ValueSource(strings={"absolute","relative","hard"})
    void inputLinksCannotExposeForeignData(String kind) throws Exception {
        try (var guard=authorization()) {
            Path foreign=Files.writeString(files.resolve("foreign"),"foreign bytes");
            Path link=build.source().resolve("alias");
            if (kind.equals("hard")) Files.createLink(link,build.source().resolve("starexec_build"));
            else Files.createSymbolicLink(link,kind.equals("absolute")?foreign:Path.of("../foreign"));
            assertThrows(IOException.class,this::prepare);
            assertEquals("foreign bytes",Files.readString(foreign));
            assertEquals(0,stagingEntries());
        }
    }

    @Test void aConfinedInternalInputLinkIsPreserved() throws Exception {
        try (var guard=authorization()) {
            Path link=Files.createSymbolicLink(build.source().resolve("alias"),Path.of("starexec_build"));
            assertNotNull(prepare());
            assertTrue(Files.isSymbolicLink(link)); assertEquals(Path.of("starexec_build"),Files.readSymbolicLink(link));
        }
    }

    @Test void specialInputFilesAreRefusedWithoutOpeningThem() throws Exception {
        try (var guard=authorization();var socket=ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            socket.bind(UnixDomainSocketAddress.of(build.source().resolve("socket")));
            assertThrows(IOException.class,this::prepare);
            assertEquals(0,stagingEntries());
        }
    }

    @Test void aControlSymlinkCannotExposeItsTarget() throws Exception {
        try (var guard=authorization()) {
            Path foreign=Files.writeString(files.resolve("foreign"),"foreign bytes");
            Files.delete(controls.functions()); Files.createSymbolicLink(controls.functions(),foreign);
            assertThrows(IOException.class,this::prepare);
            assertEquals("foreign bytes",Files.readString(foreign));
            assertEquals(0,stagingEntries());
        }
    }

    @Test void controlsCannotBeReachedThroughTheWritableStagingAlias() throws Exception {
        try (var guard=authorization()) {
            Path alias=Files.writeString(build.staging().resolve("functions.bash"),"foreign control");
            controls=new BuildExecutionMounts.Controls(controls.script(),alias,controls.statusCodes());
            assertThrows(IOException.class,this::prepare);
            assertEquals("foreign control",Files.readString(alias));
        }
    }

    @Test void nestedInputMountsAreRefusedBeforeCreatingDirectories() throws Exception {
        var nested=new DedicatedStorageResource.Mount(7,"8:2",Path.of("/"),build.source().resolve("nested"),
                "ext4",List.of("rw"),List.of("rw"));
        try (var guard=authorization();var mounts=mockStatic(DedicatedStorageResource.class,CALLS_REAL_METHODS)) {
            mounts.when(DedicatedStorageResource::mounts).thenReturn(List.of(nested));
            assertThrows(IOException.class,this::prepare);
            assertEquals(0,stagingEntries());
        }
    }

    @Test void unregisteredStagingDataIsPreservedAndRefusesLaunch() throws Exception {
        try (var guard=authorization()) {
            Path marker=Files.writeString(build.staging().resolve("keep"),"unregistered bytes");
            assertThrows(IOException.class,this::prepare);
            assertEquals("unregistered bytes",Files.readString(marker));
            assertEquals(1,stagingEntries());
        }
    }

    @Test void newStagingDataAfterPreparationRefusesAdmission() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare(); Files.writeString(build.staging().resolve("keep"),"unregistered bytes");
            assertThrows(IOException.class,()->layout.requireCurrent(connection));
            assertEquals("unregistered bytes",Files.readString(build.staging().resolve("keep")));
        }
    }

    @Test void unregisteredResourceSiblingsCannotBeExposedOrRemoved() throws Exception {
        try (var guard=authorization()) {
            Path sibling=Files.writeString(resource.root().resolve("foreign"),"foreign bytes");
            assertThrows(IOException.class,this::prepare);
            assertEquals("foreign bytes",Files.readString(sibling)); assertEquals(0,stagingEntries());
        }
    }

    @ParameterizedTest @ValueSource(strings={"work","tmp","home","monitor","run","var-tmp"})
    void nonemptyWorkspaceCannotBeAdoptedBeforeLaunch(String directoryName) throws Exception {
        try (var guard=authorization()) {
            Path work=Files.createDirectory(build.staging().resolve(directoryName),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path marker=Files.writeString(work.resolve("keep"),"preexisting bytes");
            assertThrows(IOException.class,this::prepare);
            assertEquals("preexisting bytes",Files.readString(marker)); assertEquals(1,stagingEntries());
        }
    }

    @ParameterizedTest @ValueSource(strings={"work","tmp","home","monitor","run","var-tmp"})
    void aWorkspaceSymlinkCannotModifyItsTarget(String directoryName) throws Exception {
        try (var guard=authorization()) {
            Path target=privateDirectory("foreign"); Path marker=Files.writeString(target.resolve("keep"),"foreign bytes");
            Files.createSymbolicLink(build.staging().resolve(directoryName),target);
            assertThrows(IOException.class,this::prepare);
            assertEquals("foreign bytes",Files.readString(marker));
        }
    }

    @ParameterizedTest @ValueSource(strings={"work","tmp","home","monitor","run","var-tmp"})
    void replacedPrivateDirectoriesCannotBeAdoptedByThePreparedLayout(String directoryName) throws Exception {
        try (var guard=authorization()) {
            var layout=prepare(); Path work=build.staging().resolve(directoryName);
            Files.move(work,files.resolve("original-work"));
            Files.createDirectory(work,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            assertThrows(IOException.class,()->layout.requireCurrent(connection));
            assertTrue(Files.isDirectory(work));
        }
    }

    @Test void aForceFailurePreservesOwnedDirectoriesForRetry() throws Exception {
        try (var guard=authorization()) {
            try (var directories=mockStatic(DescriptorDirectory.class,invocation->{
                Object opened=invocation.callRealMethod();
                if (opened instanceof DescriptorDirectory directory && build.staging().equals(invocation.getArgument(0))) {
                    var failing=spy(directory);doThrow(new IOException("injected private directory force failure")).when(failing).force();return failing;
                }
                return opened;
            })) { assertThrows(IOException.class,this::prepare); }
            assertEquals(6,stagingEntries());
            assertNotNull(prepare()); assertEquals(6,stagingEntries());
        }
    }

    @Test void unavailableResourceObservationRefusesAdmission() throws Exception {
        try (var guard=authorization()) {
            var layout=prepare();doThrow(new IOException("unavailable resource")).when(resource).requireUnchanged();
            assertThrows(IOException.class,()->layout.requireCurrent(connection));
            assertEquals(6,stagingEntries());
        }
    }

    @Test void inputByteAndEntryLimitsRefuseBeforeDirectoryCreation() throws Exception {
        try (var guard=authorization()) {
            assertThrows(IOException.class,()->BuildExecutionMounts.prepare(connection,build,resource,controls,1,64));
            assertThrows(IOException.class,()->BuildExecutionMounts.prepare(connection,build,resource,controls,1024,1));
            assertThrows(IllegalArgumentException.class,()->BuildExecutionMounts.prepare(connection,build,resource,controls,0,64));
            assertThrows(IllegalArgumentException.class,()->BuildExecutionMounts.prepare(connection,build,resource,controls,1024,0));
            assertEquals(0,stagingEntries());
        }
    }
}
