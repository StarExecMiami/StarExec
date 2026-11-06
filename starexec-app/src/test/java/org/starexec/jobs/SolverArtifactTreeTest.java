package org.starexec.jobs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class SolverArtifactTreeTest {
    @TempDir Path folder;
    private Path source;
    private Path target;

    @BeforeEach
    void fixture() throws Exception {
        source = Files.createDirectory(folder.resolve("source"));
        target = folder.resolve("artifact");
        Files.writeString(source.resolve("solver"), "BUILT");
    }

    @AfterEach
    void restoreOwnedDirectoryPermissions() throws Exception {
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private SolverArtifactTree.Snapshot copy() throws IOException {
        return SolverArtifactTree.copy(source, target, 1000, 20);
    }

    @Test
    void copiesAndVerifiesExactBytesIntoNewFiles() throws Exception {
        var snapshot = copy();
        assertEquals("BUILT", Files.readString(target.resolve("solver")));
        assertEquals(5, snapshot.byteSize());
        assertEquals(1, snapshot.entryCount());
        assertEquals(PosixFilePermissions.fromString("r--r--r--"), Files.getPosixFilePermissions(target.resolve("solver")));
        assertNotEquals(Files.getAttribute(source.resolve("solver"), "unix:ino"),
                Files.getAttribute(target.resolve("solver"), "unix:ino"));
        SolverArtifactTree.verify(target, snapshot);
        Files.writeString(source.resolve("solver"), "OLDER");
        assertEquals("BUILT", Files.readString(target.resolve("solver")), "source aliases cannot mutate the copy");
        SolverArtifactTree.verify(target, snapshot);
    }

    @Test
    void preservesExecutableScriptsAndUtf8Paths() throws Exception {
        Path bin = Files.createDirectory(source.resolve("bin"));
        Path executable = Files.writeString(bin.resolve("starexec_run_é"), "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwxr-x---"));
        var snapshot = copy();
        assertTrue(snapshot.payload().contains("starexec_run_é"));
        assertEquals(PosixFilePermissions.fromString("r-xr-xr-x"),
                Files.getPosixFilePermissions(target.resolve("bin/starexec_run_é")));
        SolverArtifactTree.verify(target, snapshot);
    }

    @Test
    void preservesRelativeLinksConfinedToTheArtifact() throws Exception {
        Path lib = Files.createDirectory(source.resolve("lib"));
        Files.writeString(lib.resolve("library.so.1"), "LIBRARY");
        Files.createSymbolicLink(lib.resolve("library.so"), Path.of("library.so.1"));
        Files.createSymbolicLink(source.resolve("lib64"), Path.of("lib"));
        var snapshot = copy();
        assertEquals("LIBRARY", Files.readString(target.resolve("lib64/library.so")));
        assertEquals(Path.of("library.so.1"), Files.readSymbolicLink(target.resolve("lib/library.so")));
        SolverArtifactTree.verify(target, snapshot);
    }

    @Test
    void refusesAnAbsoluteLinkEvenToItsOwnInput() throws Exception {
        Files.createSymbolicLink(source.resolve("alias"), source.resolve("solver"));
        assertThrows(IOException.class, this::copy);
        assertFalse(Files.exists(target));
        assertEquals("BUILT", Files.readString(source.resolve("solver")));
    }

    @Test
    void refusesRelativeLinksOutsideItsInput() throws Exception {
        Path outside = Files.writeString(folder.resolve("outside"), "UNRELATED");
        Files.createSymbolicLink(source.resolve("alias"), Path.of("../outside"));
        assertThrows(IOException.class, this::copy);
        assertFalse(Files.exists(target));
        assertEquals("UNRELATED", Files.readString(outside));
    }

    @Test
    void refusesDanglingLinksAndCycles() throws Exception {
        Files.createSymbolicLink(source.resolve("loop"), Path.of("loop"));
        assertThrows(IOException.class, this::copy);
        assertFalse(Files.exists(target));
        Files.delete(source.resolve("loop"));
        Files.createSymbolicLink(source.resolve("missing"), Path.of("absent"));
        assertThrows(IOException.class, this::copy);
        assertFalse(Files.exists(target));
    }

    @Test
    void refusesHardLinks() throws Exception {
        Files.createLink(source.resolve("alias"), source.resolve("solver"));
        assertThrows(IOException.class, this::copy);
        assertFalse(Files.exists(target));
        assertEquals("BUILT", Files.readString(source.resolve("solver")));
    }

    @Test
    void refusesSpecialFilesWithoutOpeningThem() throws Exception {
        try (ServerSocketChannel socket = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            socket.bind(UnixDomainSocketAddress.of(source.resolve("socket")));
            assertThrows(IOException.class, this::copy);
            assertFalse(Files.exists(target));
        }
    }

    @Test
    void neverReusesAnExistingDestination() throws Exception {
        Files.createDirectory(target);
        Files.writeString(target.resolve("unrelated"), "KEEP");
        assertThrows(IOException.class, this::copy);
        assertEquals("KEEP", Files.readString(target.resolve("unrelated")));
    }

    @Test
    void removesItsNewDirectoryWhenClosingAnOpenedParentFails() throws Exception {
        DescriptorDirectory input = mock(DescriptorDirectory.class);
        DescriptorDirectory parent = mock(DescriptorDirectory.class);
        doThrow(new IOException("Injected directory close failure")).when(parent).close();
        try (MockedStatic<DescriptorDirectory> directories = mockStatic(DescriptorDirectory.class)) {
            directories.when(() -> DescriptorDirectory.open(source.toAbsolutePath())).thenReturn(input);
            directories.when(() -> DescriptorDirectory.open(target.toAbsolutePath().getParent())).thenReturn(parent);
            assertThrows(IOException.class, this::copy);
            assertFalse(Files.exists(target), "a failure after allocation must clean up its owned directory");
        }
        assertEquals("BUILT", Files.readString(source.resolve("solver")));
    }

    @Test
    void neverFollowsASourceOrDestinationParentLink() throws Exception {
        Path link = Files.createSymbolicLink(folder.resolve("linked"), source);
        assertThrows(IOException.class, () -> SolverArtifactTree.copy(link, target, 1000, 20));
        assertFalse(Files.exists(target));
        Path other = Files.createDirectory(folder.resolve("other"));
        Path parent = Files.createSymbolicLink(folder.resolve("parent"), other);
        assertThrows(IOException.class, () -> SolverArtifactTree.copy(source, parent.resolve("artifact"), 1000, 20));
        assertFalse(Files.exists(other.resolve("artifact")));
    }

    @Test
    void rejectsNestedDestinationBeforeCreatingIt() throws Exception {
        assertThrows(IOException.class, () -> SolverArtifactTree.copy(source, source.resolve("nested"), 1000, 20));
        assertFalse(Files.exists(source.resolve("nested")));
    }

    @Test
    void boundsBytesAndRemovesOnlyItsFailedCopy() throws Exception {
        assertThrows(IOException.class, () -> SolverArtifactTree.copy(source, target, 4, 20));
        assertFalse(Files.exists(target));
        assertEquals("BUILT", Files.readString(source.resolve("solver")));
    }

    @Test
    void boundsEntriesIncludingEmptyDirectories() throws Exception {
        Files.createDirectory(source.resolve("extra"));
        assertThrows(IOException.class, () -> SolverArtifactTree.copy(source, target, 1000, 1));
        assertFalse(Files.exists(target));
    }

    @Test
    void refusesAnEmptyArtifactAndInvalidLimits() throws Exception {
        Files.delete(source.resolve("solver"));
        assertThrows(IOException.class, this::copy);
        assertFalse(Files.exists(target));
        assertThrows(IllegalArgumentException.class, () -> SolverArtifactTree.copy(source, target, -1, 20));
        assertThrows(IllegalArgumentException.class, () -> SolverArtifactTree.copy(source, target, 10, 0));
    }

    @Test
    void detectsContentAndManifestCorruption() throws Exception {
        var snapshot = copy();
        assertThrows(IOException.class, () -> SolverArtifactTree.verify(target,
                new SolverArtifactTree.Snapshot(snapshot.payload() + " ", snapshot.sha256(), snapshot.byteSize(), snapshot.entryCount())));
        Files.setPosixFilePermissions(target.resolve("solver"), PosixFilePermissions.fromString("rw-r--r--"));
        Files.writeString(target.resolve("solver"), "OLDER");
        Files.setPosixFilePermissions(target.resolve("solver"), PosixFilePermissions.fromString("r--r--r--"));
        assertThrows(IOException.class, () -> SolverArtifactTree.verify(target, snapshot));
    }

    @Test
    void detectsAddedWritePermissions() throws Exception {
        var snapshot = copy();
        Files.setPosixFilePermissions(target.resolve("solver"), PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IOException.class, () -> SolverArtifactTree.verify(target, snapshot));
    }

    @Test
    void refusesIncompleteManifests() throws Exception {
        var snapshot = copy();
        assertThrows(IOException.class, () -> SolverArtifactTree.verify(target, null));
        assertThrows(IOException.class, () -> SolverArtifactTree.verify(target,
                new SolverArtifactTree.Snapshot(null, snapshot.sha256(), 5, 1)));
        assertThrows(IOException.class, () -> SolverArtifactTree.verify(target,
                new SolverArtifactTree.Snapshot(snapshot.payload(), null, 5, 1)));
        assertThrows(IOException.class, () -> SolverArtifactTree.verify(target,
                new SolverArtifactTree.Snapshot(snapshot.payload(), snapshot.sha256(), -1, 1)));
        assertThrows(IOException.class, () -> SolverArtifactTree.verify(target,
                new SolverArtifactTree.Snapshot(snapshot.payload(), snapshot.sha256(), 5, 0)));
    }
}
