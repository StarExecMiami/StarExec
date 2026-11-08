package org.starexec.backend.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.starexec.config.EnvironmentConfig;
import org.starexec.data.database.SolverBuildArtifacts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuildArtifactStoreTest {
    @TempDir Path files;
    private Path root;
    private Path runner;

    private Path directory(String name) throws IOException {
        return Files.createDirectory(files.resolve(name),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    @BeforeEach void setup() throws Exception { root=directory("artifacts");runner=directory("runner"); }

    @Test void distinctPrivateStoreCanBeReobservedWithoutCreatingFiles() throws Exception {
        var store=BuildArtifactStore.inspect(root,List.of(runner));
        assertEquals(root,store.root());store.requireUnchanged();
        try (var children=Files.list(root)) { assertEquals(0,children.count()); }
    }

    @ParameterizedTest @ValueSource(strings={""," ","relative/store","/","/shared/../store"})
    void missingAndInvalidConfigurationCannotSelectAFallback(String setting) {
        try (var config=mockStatic(EnvironmentConfig.class,CALLS_REAL_METHODS)) {
            config.when(EnvironmentConfig::getBuildArtifactDirectory).thenReturn(setting);
            assertThrows(IOException.class,()->BuildArtifactStore.configured(List.of(runner)));
        }
    }

    @ParameterizedTest @ValueSource(strings={"rwxr-xr-x","rwxrwx---","rwx------T"})
    void anotherAccessPolicyCannotReplaceOwnerOnlyStorage(String permissions) throws Exception {
        if (permissions.endsWith("T")) Files.setAttribute(root,"unix:mode",01700);
        else Files.setPosixFilePermissions(root,PosixFilePermissions.fromString(permissions));
        assertThrows(IOException.class,()->BuildArtifactStore.inspect(root,List.of(runner)));
    }

    @Test void linkedRootAndAncestorAreRefused() throws Exception {
        Path alias=Files.createSymbolicLink(files.resolve("alias"),root);
        assertThrows(IOException.class,()->BuildArtifactStore.inspect(alias,List.of(runner)));
        Path child=Files.createDirectory(root.resolve("child"));
        assertThrows(IOException.class,()->BuildArtifactStore.inspect(alias.resolve("child"),List.of(runner)));
        assertTrue(Files.exists(child));
    }

    @Test void missingRootIsNotCreated() {
        Path missing=files.resolve("missing");
        assertThrows(IOException.class,()->BuildArtifactStore.inspect(missing,List.of(runner)));
        assertFalse(Files.exists(missing));
    }

    @Test void equalityAndEitherAncestorDirectionAreRefused() throws Exception {
        for (Path excluded:List.of(root,root.resolve("future-child"),files)) {
            assertThrows(IOException.class,()->BuildArtifactStore.inspect(root,List.of(excluded)));
        }
    }

    @Test void aSiblingPrefixIsNotAnOverlap() throws Exception {
        Path sibling=directory("artifacts-extra");
        assertNotNull(BuildArtifactStore.inspect(root,List.of(sibling)));
    }

    @Test void missingDeclaredRunnerDescendantsStillParticipateInExclusion() throws Exception {
        assertNotNull(BuildArtifactStore.inspect(root,List.of(runner.resolve("future/child"))));
        assertThrows(IOException.class,()->BuildArtifactStore.inspect(root,List.of(root.resolve("future/child"))));
    }

    @Test void aPhysicalAliasCannotHideBehindAnotherPath() throws Exception {
        var mounts=new ArrayList<>(DedicatedStorageResource.mounts());
        var physical=BuildExecutionRuntime.sourceMount(mounts,root);
        Path position=physical.root().resolve(physical.point().relativize(root));
        mounts.add(new DedicatedStorageResource.Mount(999999,physical.device(),position,runner,physical.type(),List.of("rw"),List.of("rw")));
        try (var kernel=mockStatic(DedicatedStorageResource.class,CALLS_REAL_METHODS)) {
            kernel.when(DedicatedStorageResource::mounts).thenReturn(mounts);
            assertThrows(IOException.class,()->BuildArtifactStore.inspect(root,List.of(runner)));
        }
    }

    @Test void replacedRootCannotReuseItsPreviousObservation() throws Exception {
        var store=BuildArtifactStore.inspect(root,List.of(runner));
        Files.move(root,files.resolve("old"));
        directory("artifacts");
        assertThrows(IOException.class,store::requireUnchanged);
    }

    @Test void changedPermissionsInvalidateTheObservation() throws Exception {
        var store=BuildArtifactStore.inspect(root,List.of(runner));
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IOException.class,store::requireUnchanged);
    }

    @Test void anUntrustedWritableAncestorIsRefused() throws Exception {
        Files.setPosixFilePermissions(files,PosixFilePermissions.fromString("rwxrwx---"));
        assertThrows(IOException.class,()->BuildArtifactStore.inspect(root,List.of(runner)));
    }

    @Test void aNewMountIncarnationCannotReuseTheRootIdentity() throws Exception {
        var store=BuildArtifactStore.inspect(root,List.of(runner));
        var old=DedicatedStorageResource.mounts();
        var selected=BuildExecutionRuntime.sourceMount(old,root);
        var changed=old.stream().map(m->m.equals(selected)?new DedicatedStorageResource.Mount(m.id()+1,m.device(),m.root(),m.point(),m.type(),m.options(),m.superOptions()):m).toList();
        try (var kernel=mockStatic(DedicatedStorageResource.class,CALLS_REAL_METHODS)) {
            kernel.when(DedicatedStorageResource::mounts).thenReturn(changed);
            assertThrows(IOException.class,store::requireUnchanged);
        }
    }

    @Test void onlyTheExactPublishedSolverCanBeLocated() throws Exception {
        UUID token=UUID.randomUUID();
        Path destination=Files.createDirectory(root.resolve("build-5-"+token));
        Path solver=Files.createDirectory(destination.resolve("solver"));
        var artifact=new SolverBuildArtifacts.Artifact(5,7,1,11,11,13,1,runner.resolve("source").toString(),
                runner.resolve("staging").toString(),destination.toString(),solver.toString(),token,"podman:1:synthetic",null,"PUBLISHED");
        assertEquals(solver,BuildArtifactStore.inspect(root,List.of(runner)).solver(artifact));
        var wrong=new SolverBuildArtifacts.Artifact(6,7,1,11,11,13,1,artifact.sourcePath(),artifact.stagingPath(),
                artifact.storageRoot(),artifact.solverPath(),token,artifact.engineIdentity(),null,"PUBLISHED");
        assertThrows(IOException.class,()->BuildArtifactStore.inspect(root,List.of(runner)).solver(wrong));
    }
}
