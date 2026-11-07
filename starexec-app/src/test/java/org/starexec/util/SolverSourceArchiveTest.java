package org.starexec.util;

import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SolverSourceArchiveTest {
    @TempDir Path directory;
    private record Archived(String contents, int mode, boolean link) { }

    private Map<String, Archived> read(byte[] bytes) throws IOException {
        Map<String, Archived> entries = new HashMap<>();
        Path file = Files.write(Files.createTempFile(directory, "source-archive-", ".zip"), bytes);
        try (ZipFile archive = ZipFile.builder().setPath(file).get()) {
            var iterator = archive.getEntries();
            while (iterator.hasMoreElements()) {
                var entry = iterator.nextElement();
                String contents = "";
                if (!entry.isDirectory()) {
                    try (var input = archive.getInputStream(entry)) { contents = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
                }
                entries.put(entry.getName(), new Archived(contents, entry.getUnixMode(), entry.isUnixSymlink()));
            }
        }
        return entries;
    }

    private Map<String, Archived> write(Path source) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        SolverSourceArchive.write(source, bytes, "solver");
        return read(bytes.toByteArray());
    }

    @Test void preservesExactUtf8BytesExecutableModesAndEmptyDirectories() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path script = Files.writeString(source.resolve("run_\u00e9"), "#!/bin/sh\necho original\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-x---"));
        Files.createDirectory(source.resolve("empty"));
        var entries = write(source);
        assertEquals("#!/bin/sh\necho original\n", entries.get("solver/run_\u00e9").contents());
        assertEquals(0750, entries.get("solver/run_\u00e9").mode() & 0777);
        assertTrue(entries.containsKey("solver/empty/"));
        assertEquals(2, entries.size());
        assertEquals(PosixFilePermissions.fromString("rwxr-x---"), Files.getPosixFilePermissions(script));
        assertEquals("#!/bin/sh\necho original\n", Files.readString(script));
    }

    @Test void preservesConfinedFileAndDirectoryLinksWithoutExpandingTheirTargets() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(Files.createDirectory(source.resolve("target")).resolve("file"), "SOURCE");
        Files.createSymbolicLink(source.resolve("directory-alias"), Path.of("target"));
        Files.createSymbolicLink(source.resolve("file-alias"), Path.of("target/file"));
        var entries = write(source);
        assertTrue(entries.get("solver/directory-alias").link());
        assertEquals("target", entries.get("solver/directory-alias").contents());
        assertTrue(entries.get("solver/file-alias").link());
        assertEquals("target/file", entries.get("solver/file-alias").contents());
        assertFalse(entries.containsKey("solver/directory-alias/file"));
        assertEquals("SOURCE", entries.get("solver/target/file").contents());
    }

    @ParameterizedTest @ValueSource(strings = {"absolute-outside", "relative-outside", "absolute-inside"})
    void refusesEscapingAndAbsoluteLinksWithoutArchivingThem(String condition) throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path outside = Files.writeString(directory.resolve("outside"), "EXTERNAL");
        Path inside = Files.writeString(source.resolve("inside"), "INTERNAL");
        Path target = condition.equals("absolute-inside") ? inside : condition.equals("relative-outside") ? Path.of("../outside") : outside;
        Files.createSymbolicLink(source.resolve("escape"), target);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> SolverSourceArchive.write(source, bytes, "solver"));
        assertFalse(read(bytes.toByteArray()).containsKey("solver/escape"));
        assertEquals("EXTERNAL", Files.readString(outside));
    }

    @Test void refusesHardLinkedSourceFiles() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path outside = Files.writeString(directory.resolve("outside"), "KEEP");
        Files.createLink(source.resolve("alias"), outside);
        assertThrows(IOException.class, () -> write(source)); assertEquals("KEEP", Files.readString(outside));
    }

    @Test void refusesSpecialFilesBeforeOpeningThem() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        try (ServerSocketChannel socket = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            socket.bind(UnixDomainSocketAddress.of(source.resolve("socket")));
            assertThrows(IOException.class, () -> write(source));
        }
    }

    @Test void refusesSymbolicAncestorsAndPortableNameEscapes() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path link = Files.createSymbolicLink(directory.resolve("alias"), source);
        assertThrows(IOException.class, () -> write(link));
        Files.writeString(source.resolve("..\\escape"), "KEEP");
        assertThrows(IOException.class, () -> write(source));
    }

    @ParameterizedTest @ValueSource(strings = {"", "..", "../escape", "a/b", "a\\b"})
    void refusesUnsafeArchiveRoots(String name) throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> SolverSourceArchive.write(source, bytes, name));
        assertEquals(0, bytes.size());
    }

    @Test void refusesFilesystemRootAndRelativeSourceBeforeWalking() {
        assertThrows(IOException.class, () -> write(Path.of("/")));
        assertThrows(IOException.class, () -> write(Path.of("relative")));
    }
}
