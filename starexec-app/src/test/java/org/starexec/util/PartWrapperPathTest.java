package org.starexec.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PartWrapperPathTest {
    @TempDir Path files;

    private Part part(String filename) throws Exception {
        Part part = mock(Part.class);
        when(part.getHeader("content-disposition"))
            .thenReturn("form-data; name=\"f\"; filename=\"" + filename + "\"");
        when(part.getInputStream()).thenAnswer(ignored ->
            new ByteArrayInputStream("uploaded".getBytes(StandardCharsets.UTF_8)));
        return part;
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.txt", "sub/../../outside.txt", "/outside.txt", "..\\outside.txt",
        "C:\\outside.txt", "C:outside.txt", "\\\\server\\share\\outside.txt", "..", ".", "a..b.zip", "bad\u0000.zip"})
    void refusesPathSyntaxBeforeExposingTheFilename(String filename) throws Exception {
        Part part = part(filename);
        assertThrows(IllegalArgumentException.class, () -> new PartWrapper(part));
        verify(part, never()).getInputStream();
    }

    @Test void traversalCannotOverwriteASiblingFile() throws Exception {
        Path root = Files.createDirectory(files.resolve("upload"));
        Path outside = Files.writeString(files.resolve("outside.txt"), "original");
        Part part = part("../outside.txt");
        assertThrows(IllegalArgumentException.class, () -> {
            PartWrapper wrapper = new PartWrapper(part);
            wrapper.write(root.resolve(wrapper.getFilePath()).toFile());
        });
        assertEquals("original", Files.readString(outside));
        verify(part, never()).getInputStream();
    }

    @ParameterizedTest @ValueSource(strings = {"solver-3.3.3.tar.gz", "my benchmarks.zip", "résultats.tgz"})
    void preservesOrdinaryBasenamesAndTheirBytes(String filename) throws Exception {
        PartWrapper wrapper = new PartWrapper(part(filename));
        assertTrue(wrapper.isFile());
        assertEquals(filename, wrapper.getFilePath());
        assertEquals(filename, wrapper.getName());
        Path target = files.resolve(wrapper.getName());
        wrapper.write(target.toFile());
        assertEquals("uploaded", Files.readString(target));
    }

    @Test void preservesNonFileFormFields() {
        Part part = mock(Part.class);
        when(part.getHeader("content-disposition")).thenReturn("form-data; name=\"description\"");
        PartWrapper wrapper = new PartWrapper(part);
        assertFalse(wrapper.isFile());
        assertNull(wrapper.getFilePath());
    }
}
