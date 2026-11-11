package org.starexec.backend.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.starexec.config.EnvironmentConfig;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrivateUploadStageTest {
    @TempDir Path files;
    private Path root;
    private Path runner;

    private Path directory(String name) throws IOException {
        return Files.createDirectory(files.resolve(name),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    @BeforeEach void setup() throws Exception {
        root = directory("uploads");
        runner = directory("runner");
    }

    @Test void claimsAreFreshPrivateAndCleanedIndependently() throws Exception {
        try (var first = PrivateUploadStage.create(root, List.of(runner));
             var second = PrivateUploadStage.create(root, List.of(runner))) {
            Path owned = first.directory();
            Path other = second.directory();
            assertNotEquals(owned, other);
            assertEquals(root, owned.getParent());
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(owned));
            Files.writeString(owned.resolve("payload"), "first");
            Files.writeString(other.resolve("payload"), "second");
            first.close();
            assertFalse(Files.exists(owned));
            assertEquals("second", Files.readString(second.directory().resolve("payload")));
            first.close();
            assertThrows(IOException.class, first::directory);
        }
        try (var children = Files.list(root)) { assertEquals(0, children.count()); }
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "relative/uploads", "/", "/shared/../uploads"})
    void invalidConfigurationDoesNotChooseSharedStorage(String setting) {
        try (var config = mockStatic(EnvironmentConfig.class, CALLS_REAL_METHODS)) {
            config.when(EnvironmentConfig::getUploadStagingDirectory).thenReturn(setting);
            assertThrows(IOException.class, PrivateUploadStage::configured);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"rwxr-xr-x", "rwxrwx---", "rwx-----T"})
    void rootMustRemainOwnerOnly(String permissions) throws Exception {
        if (permissions.endsWith("T")) Files.setAttribute(root, "unix:mode", 01700);
        else Files.setPosixFilePermissions(root, PosixFilePermissions.fromString(permissions));
        assertThrows(IOException.class, () -> PrivateUploadStage.create(root, List.of(runner)));
    }

    @Test void missingRootIsNotProvisioned() {
        Path missing = files.resolve("missing");
        assertThrows(IOException.class, () -> PrivateUploadStage.create(missing, List.of(runner)));
        assertFalse(Files.exists(missing));
    }

    @Test void runnerRootsCannotBeMissingOrOverlapInEitherDirection() throws Exception {
        assertThrows(IOException.class, () -> PrivateUploadStage.create(root, null));
        assertThrows(IOException.class, () -> PrivateUploadStage.create(root, List.of()));
        assertThrows(IOException.class, () -> PrivateUploadStage.create(root, List.of(files)));
        assertThrows(IOException.class, () -> PrivateUploadStage.create(root, List.of(root.resolve("future/child"))));
    }

    @Test void symbolicRootAndAncestorAreRefused() throws Exception {
        Path alias = Files.createSymbolicLink(files.resolve("alias"), root);
        assertThrows(IOException.class, () -> PrivateUploadStage.create(alias, List.of(runner)));
        Path child = Files.createDirectory(root.resolve("child"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        assertThrows(IOException.class, () -> PrivateUploadStage.create(alias.resolve(child.getFileName()), List.of(runner)));
    }

    @Test void untrustedWritableAncestorIsRefused() throws Exception {
        Files.setPosixFilePermissions(files, PosixFilePermissions.fromString("rwxrwx---"));
        assertThrows(IOException.class, () -> PrivateUploadStage.create(root, List.of(runner)));
    }

    @Test void physicalAliasIsRefusedEvenWithDifferentNames() throws Exception {
        var mounts = new ArrayList<>(DedicatedStorageResource.mounts());
        var selected = BuildExecutionRuntime.sourceMount(mounts, root);
        Path physical = selected.root().resolve(selected.point().relativize(root));
        mounts.add(new DedicatedStorageResource.Mount(999999, selected.device(), physical,
            runner, selected.type(), List.of("rw"), List.of("rw")));
        try (var kernel = mockStatic(DedicatedStorageResource.class, CALLS_REAL_METHODS)) {
            kernel.when(DedicatedStorageResource::mounts).thenReturn(mounts);
            assertThrows(IOException.class, () -> PrivateUploadStage.create(root, List.of(runner)));
        }
    }

    @Test void replacementClaimIsNotAdoptedOrDeleted() throws Exception {
        var stage = PrivateUploadStage.create(root, List.of(runner));
        Path owned = stage.directory();
        Path original = root.resolve("original");
        Files.move(owned, original);
        Files.createDirectory(owned);
        Files.writeString(owned.resolve("foreign"), "keep");
        assertThrows(IOException.class, stage::directory);
        assertThrows(IOException.class, stage::close);
        assertEquals("keep", Files.readString(owned.resolve("foreign")));
        Files.delete(owned.resolve("foreign"));
        Files.delete(owned);
        Files.move(original, owned);
        stage.close();
        assertFalse(Files.exists(owned));
    }

    @Test void replacementRootCannotReuseItsOldClaim() throws Exception {
        var stage = PrivateUploadStage.create(root, List.of(runner));
        Path owned = stage.directory();
        Path original = files.resolve("original-root");
        Files.move(root, original);
        Files.createDirectory(root);
        Files.writeString(root.resolve("foreign"), "keep");
        assertThrows(IOException.class, stage::close);
        assertEquals("keep", Files.readString(root.resolve("foreign")));
        Files.delete(root.resolve("foreign"));
        Files.delete(root);
        Files.move(original, root);
        stage.close();
        assertFalse(Files.exists(owned));
    }

    @Test void cleanupUnlinksAliasesWithoutChangingTheirOutsideTargets() throws Exception {
        Path sentinel = Files.writeString(files.resolve("sentinel"), "outside");
        Files.setPosixFilePermissions(sentinel, PosixFilePermissions.fromString("rw-------"));
        Object identity = Files.readAttributes(sentinel, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        var stage = PrivateUploadStage.create(root, List.of(runner));
        Path owned = stage.directory();
        Files.createSymbolicLink(owned.resolve("symbolic"), sentinel);
        Files.createLink(owned.resolve("hard"), sentinel);
        stage.close();
        assertEquals("outside", Files.readString(sentinel));
        assertEquals(identity, Files.readAttributes(sentinel, java.nio.file.attribute.BasicFileAttributes.class).fileKey());
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(sentinel));
        assertEquals(1, ((Number) Files.getAttribute(sentinel, "unix:nlink")).intValue());
    }

    @Test void changedRootPermissionsRefuseCleanupUntilRestored() throws Exception {
        var stage = PrivateUploadStage.create(root, List.of(runner));
        Path owned = stage.directory();
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IOException.class, stage::close);
        assertTrue(Files.exists(owned));
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        stage.close();
    }

    @Test void mountReplacementInvalidatesTheClaim() throws Exception {
        var stage = PrivateUploadStage.create(root, List.of(runner));
        var old = DedicatedStorageResource.mounts();
        var selected = BuildExecutionRuntime.sourceMount(old, root);
        var changed = old.stream().map(m -> m.equals(selected)
            ? new DedicatedStorageResource.Mount(m.id() + 1, m.device(), m.root(), m.point(),
                m.type(), m.options(), m.superOptions()) : m).toList();
        try (var kernel = mockStatic(DedicatedStorageResource.class, CALLS_REAL_METHODS)) {
            kernel.when(DedicatedStorageResource::mounts).thenReturn(changed);
            assertThrows(IOException.class, stage::directory);
            assertThrows(IOException.class, stage::close);
        }
        stage.close();
    }

    @Test void aNestedMountCannotBeCleanedAsOwnedContent() throws Exception {
        var stage = PrivateUploadStage.create(root, List.of(runner));
        Path nested = Files.createDirectory(stage.directory().resolve("nested"));
        var mounts = new ArrayList<>(DedicatedStorageResource.mounts());
        var selected = BuildExecutionRuntime.sourceMount(mounts, root);
        mounts.add(new DedicatedStorageResource.Mount(999998, selected.device(), Path.of("/"),
            nested, selected.type(), List.of("rw"), List.of("rw")));
        try (var kernel = mockStatic(DedicatedStorageResource.class, CALLS_REAL_METHODS)) {
            kernel.when(DedicatedStorageResource::mounts).thenReturn(mounts);
            assertThrows(IOException.class, stage::close);
            assertTrue(Files.exists(nested));
        }
        stage.close();
    }

    @Test void failedParentForceCanRetryTheSameRemovedClaim() throws Exception {
        var stage = PrivateUploadStage.create(root, List.of(runner));
        Path owned = stage.directory();
        FileChannel failed = mock(FileChannel.class);
        doThrow(new IOException("injected directory force failure")).when(failed).force(true);
        try (var channels = mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
            channels.when(() -> FileChannel.open(root, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).thenReturn(failed);
            assertThrows(IOException.class, stage::close);
        }
        assertFalse(Files.exists(owned));
        stage.close();
        assertThrows(IOException.class, stage::directory);
    }

    @Test void forceRetryNeverAdoptsAReappearedDirectory() throws Exception {
        var stage = PrivateUploadStage.create(root, List.of(runner));
        Path owned = stage.directory();
        FileChannel failed = mock(FileChannel.class);
        doThrow(new IOException("injected directory force failure")).when(failed).force(true);
        try (var channels = mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
            channels.when(() -> FileChannel.open(root, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).thenReturn(failed);
            assertThrows(IOException.class, stage::close);
        }
        Files.createDirectory(owned);
        Files.writeString(owned.resolve("foreign"), "keep");
        assertThrows(IOException.class, stage::close);
        assertEquals("keep", Files.readString(owned.resolve("foreign")));
        Files.delete(owned.resolve("foreign"));
        Files.delete(owned);
        stage.close();
    }
}
