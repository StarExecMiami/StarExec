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

    @Test
    public void quiescentReadsUseUtf8ByteBoundsAndPreserveEmptyFiles() throws Exception {
        String text="value \u00e9\n";
        Files.writeString(temporary.resolve("record"),text);
        Files.createFile(temporary.resolve("empty"));
        int bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        try (DescriptorDirectory directory=DescriptorDirectory.open(temporary)) {
            assertEquals(text,directory.readQuiescentText("record",bytes));
            assertThrows(DescriptorDirectory.InvalidFileException.class,()->directory.readQuiescentText("record",bytes-1));
            assertEquals("",directory.readQuiescentText("empty",1));
        }
    }

    @Test
    public void replacementAfterOpenCannotRedirectARead() throws Exception {
        Path original=Files.createDirectory(temporary.resolve("original"));
        Files.writeString(original.resolve("record"),"owned");
        Path outside=Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("record"),"foreign");
        try (DescriptorDirectory directory=DescriptorDirectory.open(original)) {
            Files.move(original,temporary.resolve("retained"));
            Files.createSymbolicLink(original,outside);
            assertEquals("owned",directory.readQuiescentText("record",32));
        }
        assertEquals("foreign",Files.readString(outside.resolve("record")));
    }

    @Test
    public void linkedOrNonregularLeavesAreRefusedBeforeRead() throws Exception {
        Path original=Files.writeString(temporary.resolve("original"),"owned");
        Files.createSymbolicLink(temporary.resolve("alias"),original);
        Files.createDirectory(temporary.resolve("directory"));
        try (DescriptorDirectory directory=DescriptorDirectory.open(temporary)) {
            assertThrows(DescriptorDirectory.InvalidFileException.class,()->directory.readQuiescentText("alias",32));
            assertThrows(DescriptorDirectory.InvalidFileException.class,()->directory.readQuiescentText("directory",32));
        }
    }

    @Test
    public void readsDoNotRequireWritePermissions() throws Exception {
        Path record=Files.writeString(temporary.resolve("record"),"owned");
        var permissions=java.nio.file.attribute.PosixFilePermissions.fromString("r--------");
        Files.setPosixFilePermissions(record,permissions);
        try (DescriptorDirectory directory=DescriptorDirectory.open(temporary)) {
            assertEquals("owned",directory.readQuiescentText("record",32));
            assertEquals(permissions,Files.getPosixFilePermissions(record));
        }
    }

    @Test
    public void malformedUtf8IsRefusedWithoutReplacementCharacters() throws Exception {
        Files.write(temporary.resolve("record"),new byte[]{(byte)0xc3,(byte)0x28});
        try (DescriptorDirectory directory=DescriptorDirectory.open(temporary)) {
            assertThrows(DescriptorDirectory.InvalidFileException.class,()->directory.readQuiescentText("record",32));
        }
    }

    @Test
    public void readNamesAndLimitsCannotEscapeOrAllocateUnboundedBuffers() throws Exception {
        try (DescriptorDirectory directory=DescriptorDirectory.open(temporary)) {
            for (String name:List.of("", ".", "..", "../escape", "nested/name", "nested\\name", "nul\0name")) {
                assertThrows(IOException.class,()->directory.readQuiescentText(name,32));
            }
            assertThrows(IOException.class,()->directory.readQuiescentText(null,32));
            assertThrows(IOException.class,()->directory.readQuiescentText("record",0));
            assertThrows(IOException.class,()->directory.readQuiescentText("record",Integer.MAX_VALUE));
        }
    }

    @Test
    public void missingReadsRemainDistinguishableFromUntrustedFiles() throws Exception {
        try (DescriptorDirectory directory=DescriptorDirectory.open(temporary)) {
            assertThrows(java.nio.file.NoSuchFileException.class,()->directory.readQuiescentText("missing",32));
        }
    }

    @Test
    public void publishedReadsAcceptAtomicReplacementBetweenPolls() throws Exception {
        Path record = Files.writeString(temporary.resolve("record"), "first");
        try (DescriptorDirectory directory = DescriptorDirectory.open(temporary)) {
            assertEquals("first", directory.readPublishedText("record", 32));
            Path next = Files.writeString(temporary.resolve("next"), "second");
            Files.move(next, record, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            assertEquals("second", directory.readPublishedText("record", 32));
        }
    }

    @Test
    public void publishedReadsKeepByteAndLinkGuardsAndEncodingRetries() throws Exception {
        Path record = Files.writeString(temporary.resolve("record"), "owned");
        Files.createSymbolicLink(temporary.resolve("alias"), record);
        Files.write(temporary.resolve("malformed"), new byte[]{(byte) 0xc3, (byte) 0x28});
        try (DescriptorDirectory directory = DescriptorDirectory.open(temporary)) {
            assertThrows(DescriptorDirectory.InvalidFileException.class,
                    () -> directory.readPublishedText("record", 4));
            assertThrows(DescriptorDirectory.InvalidFileException.class,
                    () -> directory.readPublishedText("alias", 32));
            IOException malformed = assertThrows(IOException.class,
                    () -> directory.readPublishedText("malformed", 32));
            assertFalse(malformed instanceof DescriptorDirectory.InvalidFileException);
        }
    }

    @Test
    public void listingAndPublishedReadsRemainBoundToTheOpenedDirectory() throws Exception {
        Path original = Files.createDirectory(temporary.resolve("original"));
        Files.writeString(original.resolve("owned"), "owned");
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("foreign"), "foreign");
        try (DescriptorDirectory directory = DescriptorDirectory.open(original)) {
            Files.move(original, temporary.resolve("retained"));
            Files.createSymbolicLink(original, outside);
            assertEquals(List.of("owned"), directory.childNames(1));
            assertEquals("owned", directory.readPublishedText("owned", 32));
        }
    }

    @Test
    public void directoryListingCountsAllNamesAgainstItsLimit() throws Exception {
        Files.createFile(temporary.resolve("record"));
        Files.createFile(temporary.resolve("record.tmp"));
        try (DescriptorDirectory directory = DescriptorDirectory.open(temporary)) {
            assertThrows(DescriptorDirectory.InvalidFileException.class,
                    () -> directory.childNames(0));
            assertThrows(DescriptorDirectory.InvalidFileException.class,
                    () -> directory.childNames(1));
        }
    }
}
