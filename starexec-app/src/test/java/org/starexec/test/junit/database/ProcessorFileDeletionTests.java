package org.starexec.test.junit.database;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.starexec.data.database.Processors;

/**
 * Tests the file cleanup half of processor deletion. These tests use no database and no Spring
 * context: they only exercise {@link Processors#deleteProcessorFiles(Path, String)}.
 */
public class ProcessorFileDeletionTests {

    @Rule
    public TemporaryFolder processorRoot = new TemporaryFolder();

    @Rule
    public TemporaryFolder outsideRoot = new TemporaryFolder();

    @Test
    public void deletesProcessorDirectoryWithFilesAndReturnsTrue() throws Exception {
        Path processorDir = createProcessorDir("solver");
        Files.createDirectories(processorDir.resolve("bin"));
        Files.writeString(processorDir.resolve("process"), "#!/bin/sh\n");
        Files.writeString(processorDir.resolve("bin/helper"), "binary");

        boolean deleted = deleteProcessorFiles(processorDir.toString());

        assertTrue("the deletion must be reported as successful", deleted);
        assertFalse("the processor directory must be gone", Files.exists(processorDir));
    }

    @Test
    public void removesEmptyTimestampParentButKeepsCommunityDirectory() throws Exception {
        Path processorDir = createProcessorDir("solver");
        Files.writeString(processorDir.resolve("process"), "#!/bin/sh\n");
        Path timestampDir = processorDir.getParent();
        Path communityDir = timestampDir.getParent();

        boolean deleted = deleteProcessorFiles(processorDir.toString());

        assertTrue(deleted);
        assertFalse(
            "the now-empty timestamp directory must be removed",
            Files.exists(timestampDir)
        );
        assertTrue(
            "only the timestamp directory is cleaned up",
            Files.exists(communityDir)
        );
    }

    @Test
    public void keepsTimestampParentThatStillHoldsAnotherProcessor() throws Exception {
        Path first = createProcessorDir("solver-one");
        Path second = createProcessorDir("solver-two");
        Files.writeString(first.resolve("process"), "one");
        Files.writeString(second.resolve("process"), "two");

        boolean deleted = deleteProcessorFiles(first.toString());

        assertTrue(deleted);
        assertFalse(Files.exists(first));
        assertTrue(
            "the timestamp directory is not empty",
            Files.exists(second.getParent())
        );
        assertTrue(
            "the other processor must survive",
            Files.exists(second.resolve("process"))
        );
    }

    @Test
    public void refusesRelativePathThatEscapesTheRoot() throws Exception {
        Path outsideDir = outsideRoot.newFolder("outside").toPath();
        Path outsideFile = outsideDir.resolve("keep.txt");
        Files.writeString(outsideFile, "keep");
        Path workingDirectory = Paths.get(".").toAbsolutePath().normalize();
        String escapingPath = workingDirectory
            .relativize(outsideDir.toAbsolutePath().normalize())
            .toString();

        boolean deleted = deleteProcessorFiles(escapingPath);

        assertFalse(
            "a path outside the processor root must be refused",
            deleted
        );
        assertTrue(
            "the outside directory must be untouched",
            Files.exists(outsideDir)
        );
        assertTrue(
            "the outside file must be untouched",
            Files.exists(outsideFile)
        );
    }

    @Test
    public void refusesLiteralParentTraversalPath() throws Exception {
        boolean deleted = deleteProcessorFiles("../../etc");

        assertFalse("a parent traversal path must be refused", deleted);
        assertTrue(
            "the processor root must be untouched",
            Files.exists(processorRoot.getRoot().toPath())
        );
    }

    @Test
    public void refusesAbsolutePathOutsideTheRoot() throws Exception {
        Path outsideDir = outsideRoot.newFolder("elsewhere").toPath();
        Path outsideFile = outsideDir.resolve("keep.txt");
        Files.writeString(outsideFile, "keep");

        boolean deleted = deleteProcessorFiles(outsideDir.toString());

        assertFalse(
            "an absolute path outside the root must be refused",
            deleted
        );
        assertTrue(Files.exists(outsideDir));
        assertTrue(Files.exists(outsideFile));
    }

    @Test
    public void refusesTheProcessorRootItself() throws Exception {
        Path root = processorRoot.getRoot().toPath();
        Path keep = root.resolve("keep.txt");
        Files.writeString(keep, "keep");

        boolean deleted = deleteProcessorFiles(root.toString());

        assertFalse("the root itself must be refused", deleted);
        assertTrue("the root must be untouched", Files.exists(keep));
    }

    @Test
    public void removesSymlinkInsideProcessorWithoutFollowingIt() throws Exception {
        Path processorDir = createProcessorDir("solver");
        Path outsideDir = outsideRoot.newFolder("symlink-target").toPath();
        Path outsideFile = outsideDir.resolve("outside.txt");
        Files.writeString(outsideFile, "outside");
        Path link = processorDir.resolve("link-to-outside");
        createSymbolicLinkOrSkip(link, outsideDir);

        boolean deleted = deleteProcessorFiles(processorDir.toString());

        assertTrue(deleted);
        assertFalse(Files.exists(processorDir));
        assertFalse(
            "the symlink itself must be removed",
            Files.exists(link, LinkOption.NOFOLLOW_LINKS)
        );
        assertTrue(
            "the symlink target directory must survive",
            Files.exists(outsideDir)
        );
        assertTrue(
            "the symlink target file must survive",
            Files.exists(outsideFile)
        );
    }

    @Test
    public void removesSymlinkTargetAsLinkOnly() throws Exception {
        Path outsideDir = outsideRoot.newFolder("target").toPath();
        Path outsideFile = outsideDir.resolve("outside.txt");
        Files.writeString(outsideFile, "outside");
        Path link = processorRoot
            .getRoot()
            .toPath()
            .resolve("3/20260916-01.06.50.701/solver");
        Files.createDirectories(link.getParent());
        createSymbolicLinkOrSkip(link, outsideDir);

        boolean deleted = deleteProcessorFiles(link.toString());

        assertTrue(deleted);
        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
        assertTrue(
            "the symlink target must survive",
            Files.exists(outsideDir)
        );
        assertTrue(Files.exists(outsideFile));
    }

    @Test
    public void refusesTargetReachedThroughIntermediateSymlink() throws Exception {
        Path outsideCommunity = outsideRoot.newFolder("outside-community").toPath();
        Path outsideProcessor = outsideCommunity.resolve("solver");
        Files.createDirectories(outsideProcessor);
        Path outsideFile = outsideProcessor.resolve("keep.txt");
        Files.writeString(outsideFile, "keep");

        Path linkedCommunity = processorRoot.getRoot().toPath().resolve("3");
        createSymbolicLinkOrSkip(linkedCommunity, outsideCommunity);
        Path escapedTarget = linkedCommunity.resolve("solver");

        boolean deleted = deleteProcessorFiles(escapedTarget.toString());

        assertFalse(
            "a target reached through an intermediate symlink must be refused",
            deleted
        );
        assertTrue(
            "the intermediate symlink must be retained when deletion is refused",
            Files.exists(linkedCommunity, LinkOption.NOFOLLOW_LINKS)
        );
        assertTrue("the outside directory must survive", Files.exists(outsideProcessor));
        assertTrue("the outside file must survive", Files.exists(outsideFile));
    }

    @Test
    public void deletesPlainFileAndReturnsTrue() throws Exception {
        Path file = processorRoot
            .getRoot()
            .toPath()
            .resolve("3/20260916-01.06.50.701/solver");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "plain");

        boolean deleted = deleteProcessorFiles(file.toString());

        assertTrue(deleted);
        assertFalse(Files.exists(file));
    }

    @Test
    public void nullPathReturnsTrue() {
        assertTrue(
            Processors.deleteProcessorFiles(
                processorRoot.getRoot().toPath(),
                null
            )
        );
    }

    @Test
    public void emptyPathReturnsTrue() {
        assertTrue(
            Processors.deleteProcessorFiles(
                processorRoot.getRoot().toPath(),
                ""
            )
        );
    }

    @Test
    public void nonExistentPathInsideRootReturnsTrue() throws Exception {
        Path missing = processorRoot
            .getRoot()
            .toPath()
            .resolve("3/20260916-01.06.50.701/solver");
        assertFalse(Files.exists(missing, LinkOption.NOFOLLOW_LINKS));

        assertTrue(deleteProcessorFiles(missing.toString()));
    }

    private boolean deleteProcessorFiles(String processorPath) {
        return Processors.deleteProcessorFiles(
            processorRoot.getRoot().toPath(),
            processorPath
        );
    }

    private Path createProcessorDir(String name) throws IOException {
        Path directory = processorRoot
            .getRoot()
            .toPath()
            .resolve("3")
            .resolve("20260916-01.06.50.701")
            .resolve(name);
        Files.createDirectories(directory);
        return directory;
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target)
        throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            Assume.assumeNoException(
                "file store does not support symlinks",
                e
            );
        }
    }
}
