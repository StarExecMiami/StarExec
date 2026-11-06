package org.starexec.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DescriptorDirectoryTest {

    @TempDir
    public Path temporary;

    @Test
    public void replacementAfterOpenCannotRedirectCreation() throws Exception {
        Path original = Files.createDirectory(temporary.resolve("original"));
        Path retained = temporary.resolve("retained");
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        try (DescriptorDirectory directory = DescriptorDirectory.open(original)) {
            Files.move(original, retained);
            Files.createSymbolicLink(original, outside);
            directory.writeNewText("benchmark", "synthetic text");
        }
        assertEquals("synthetic text", Files.readString(retained.resolve("benchmark")));
        assertFalse(Files.exists(outside.resolve("benchmark")));
    }

    @Test
    public void linkedAncestorsAreRefused() throws Exception {
        Path actual = Files.createDirectory(temporary.resolve("actual"));
        Files.createDirectory(actual.resolve("child"));
        Path alias = Files.createSymbolicLink(temporary.resolve("alias"), actual);
        assertThrows(IOException.class, () -> DescriptorDirectory.open(alias.resolve("child")));
    }

    @Test
    public void dotSegmentsCannotHideASymbolicLinkAncestor() throws Exception {
        Path actual = Files.createDirectory(temporary.resolve("actual"));
        Path inner = Files.createDirectory(actual.resolve("inner"));
        Files.createDirectory(actual.resolve("target"));
        Files.createDirectory(temporary.resolve("target"));
        Path alias = Files.createSymbolicLink(temporary.resolve("alias"), inner);
        assertThrows(IOException.class, () -> DescriptorDirectory.open(alias.resolve("..").resolve("target")));
    }

    @Test
    public void existingFilesAndLinksRemainUnchanged() throws Exception {
        Path original = Files.writeString(temporary.resolve("original"), "original contents");
        Files.createSymbolicLink(temporary.resolve("alias"), original);
        try (DescriptorDirectory directory = DescriptorDirectory.open(temporary)) {
            assertThrows(IOException.class, () -> directory.writeNewText("original", "replacement"));
            assertThrows(IOException.class, () -> directory.writeNewText("alias", "replacement"));
        }
        assertEquals("original contents", Files.readString(original));
    }

    @Test
    public void relativeNamesCannotEscapeTheOpenDirectory() throws Exception {
        try (DescriptorDirectory directory = DescriptorDirectory.open(temporary)) {
            for (String name : List.of("", ".", "..", "../escape", "nested/name", "nested\\name")) {
                assertThrows(IOException.class, () -> directory.writeNewText(name, "synthetic text"));
            }
            assertThrows(IOException.class, () -> directory.writeNewText(null, "synthetic text"));
        }
        try (var files = Files.list(temporary)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    public void unsupportedProvidersAreExplicitlyRefused() throws Exception {
        Path archive = temporary.resolve("owned.zip");
        try (var filesystem = FileSystems.newFileSystem(URI.create("jar:" + archive.toUri()), Map.of("create", "true"))) {
            IOException failure = assertThrows(IOException.class,
                    () -> DescriptorDirectory.open(filesystem.getPath("/")));
            assertTrue(failure.getMessage().contains("does not support descriptor-relative"));
        }
    }
}
