package org.starexec.backend.storage;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.starexec.config.EnvironmentConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrivateUploadExtractionTest {
    @TempDir Path files;
    private Path root;
    private Path runner;

    @BeforeEach void setup() throws Exception {
        root = Files.createDirectory(files.resolve("uploads"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        runner = Files.createDirectory(files.resolve("runner"));
    }

    private void archive(Path path, String suffix, String entry, byte[] bytes) throws IOException {
        if (suffix.equals(".zip")) {
            try (var out = new ZipOutputStream(Files.newOutputStream(path))) {
                out.putNextEntry(new ZipEntry(entry));
                out.write(bytes);
                out.closeEntry();
            }
        } else {
            try (OutputStream raw = Files.newOutputStream(path);
                 OutputStream compressed = suffix.equals(".tar") ? raw : new GzipCompressorOutputStream(raw);
                 var out = new TarArchiveOutputStream(compressed)) {
                var item = new TarArchiveEntry(entry);
                item.setSize(bytes.length);
                out.putArchiveEntry(item);
                out.write(bytes);
                out.closeArchiveEntry();
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {".zip", ".tar", ".tgz", ".tar.gz"})
    void supportedArchivesRemainPrivateAndSeparateFromContent(String suffix) throws Exception {
        Path owned;
        byte[] bytes = "<spaces>legitimate UTF-8</spaces>".getBytes(StandardCharsets.UTF_8);
        try (var stage = PrivateUploadStage.create(root, List.of(runner))) {
            owned = stage.directory();
            Path input = stage.archivePath("client-name" + suffix);
            assertEquals("archive" + suffix, input.getFileName().toString());
            assertEquals(owned.resolve("source"), input.getParent());
            archive(input, suffix, "nested/spaces.xml", bytes);
            Path content = stage.extractArchive();
            assertEquals(owned.resolve("content"), content);
            assertArrayEquals(bytes, Files.readAllBytes(content.resolve("nested/spaces.xml")));
            assertFalse(Files.exists(content.resolve(input.getFileName())));
            assertEquals(content, stage.content());
            assertFalse(content.startsWith(runner));
            assertThrows(IOException.class, stage::extractArchive);
        }
        assertFalse(Files.exists(owned));
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "a.txt", "../a.zip", "a/b.zip", "a\\b.zip"})
    void unsupportedNamesNeverChooseAClientPath(String name) throws Exception {
        try (var stage = PrivateUploadStage.create(root, List.of(runner))) {
            assertThrows(IOException.class, () -> stage.archivePath(name));
            try (var children = Files.list(stage.directory())) { assertEquals(0, children.count()); }
        }
    }

    @Test void nullNameAndRepeatedSelectionAreRefused() throws Exception {
        try (var stage = PrivateUploadStage.create(root, List.of(runner))) {
            assertThrows(IOException.class, () -> stage.archivePath(null));
            stage.archivePath("first.zip");
            assertThrows(IOException.class, () -> stage.archivePath("second.zip"));
            assertThrows(IOException.class, stage::content);
        }
    }

    @ParameterizedTest @ValueSource(strings = {".zip", ".tar", ".tgz", ".tar.gz"})
    void parentTraversalCannotChangeAnOutsideSentinel(String suffix) throws Exception {
        Path sentinel = Files.writeString(root.resolve("outside"), "keep");
        Files.setPosixFilePermissions(sentinel, PosixFilePermissions.fromString("rw-------"));
        try (var stage = PrivateUploadStage.create(root, List.of(runner))) {
            archive(stage.archivePath("input" + suffix), suffix, "../../outside", new byte[]{1,2,3});
            assertThrows(IOException.class, stage::extractArchive);
            assertThrows(IOException.class, stage::content);
            assertThrows(IOException.class, stage::extractArchive);
        }
        assertEquals("keep", Files.readString(sentinel));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(sentinel));
    }

    @ParameterizedTest @ValueSource(strings = {"symbolic", "hard"})
    void tarLinksAreRejectedWithoutTouchingTheirOutsideTarget(String type) throws Exception {
        Path sentinel = Files.writeString(root.resolve("outside"), "keep");
        Files.setPosixFilePermissions(sentinel, PosixFilePermissions.fromString("rw-------"));
        try (var stage = PrivateUploadStage.create(root, List.of(runner))) {
            Path input = stage.archivePath("input.tar");
            try (var out = new TarArchiveOutputStream(Files.newOutputStream(input))) {
                var entry = new TarArchiveEntry("processor", type.equals("symbolic") ? TarConstants.LF_SYMLINK : TarConstants.LF_LINK);
                entry.setLinkName(sentinel.toString());
                out.putArchiveEntry(entry);
                out.closeArchiveEntry();
            }
            assertThrows(IOException.class, stage::extractArchive);
        }
        assertEquals("keep", Files.readString(sentinel));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(sentinel));
        assertEquals(1, ((Number) Files.getAttribute(sentinel, "unix:nlink")).intValue());
    }

    @Test void absoluteArchiveEntryIsRejected() throws Exception {
        Path sentinel = Files.writeString(root.resolve("outside"), "keep");
        try (var stage = PrivateUploadStage.create(root, List.of(runner))) {
            archive(stage.archivePath("input.zip"), ".zip", sentinel.toString(), new byte[]{1});
            assertThrows(IOException.class, stage::extractArchive);
        }
        assertEquals("keep", Files.readString(sentinel));
    }

    @Test void invalidArchiveCannotPublishAnEmptySuccess() throws Exception {
        Path owned;
        try (var stage = PrivateUploadStage.create(root, List.of(runner))) {
            owned = stage.directory();
            Files.writeString(stage.archivePath("input.zip"), "not a ZIP archive");
            IOException failure = assertThrows(IOException.class, stage::extractArchive);
            assertEquals("Could not safely extract the uploaded archive", failure.getMessage());
            assertNotNull(failure.getCause());
            assertThrows(IOException.class, stage::content);
        }
        assertFalse(Files.exists(owned));
    }

    @Test void linkedInputIsNotOpened() throws Exception {
        Path sentinel = Files.writeString(root.resolve("outside"), "keep");
        try (var stage = PrivateUploadStage.create(root, List.of(runner))) {
            Path input = stage.archivePath("input.zip");
            Files.createSymbolicLink(input, sentinel);
            assertThrows(IOException.class, stage::extractArchive);
        }
        assertEquals("keep", Files.readString(sentinel));
    }

    @Test void configuredByteCapIsKeptDuringStreaming() throws Exception {
        try (var stage = PrivateUploadStage.create(root, List.of(runner));
             var config = mockStatic(EnvironmentConfig.class, CALLS_REAL_METHODS)) {
            archive(stage.archivePath("input.zip"), ".zip", "large.xml", new byte[1024]);
            config.when(EnvironmentConfig::getUploadExtractionMaxUncompressedBytes).thenReturn(16L);
            config.when(EnvironmentConfig::getUploadExtractionTimeoutSeconds).thenReturn(30);
            assertThrows(IOException.class, stage::extractArchive);
            assertThrows(IOException.class, stage::content);
        }
    }

    @Test void existingCompressionRatioLimitIsNotDropped() throws Exception {
        try (var stage = PrivateUploadStage.create(root, List.of(runner))) {
            archive(stage.archivePath("input.zip"), ".zip", "large.xml", new byte[8 * 1024 * 1024]);
            IOException failure = assertThrows(IOException.class, stage::extractArchive);
            assertEquals("Upload archive exceeds the compression-ratio limit", failure.getCause().getMessage());
            assertThrows(IOException.class, stage::content);
        }
    }
}
