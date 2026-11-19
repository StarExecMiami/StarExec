package org.starexec.data.database;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deleting a space cleans up the processor files its cascade orphaned.
 *
 * <p>The gathering half already worked: {@code removeSubspaces} collects the paths before the
 * transaction and recurses into subspaces. The cleaning half did not. It called
 * {@code File.delete()} on a path that names a <em>directory</em>, which always fails for a
 * non-empty one, so the files were left behind on every space and user deletion. That is the
 * same defect as {@code Processors.delete} had, and it is fixed the same way.
 */
public class SpaceProcessorCleanupTests {

	@TempDir
	public File processorRoot;

	@TempDir
	public File outsideRoot;

	/** A processor directory with files in it, as ProcessorManager leaves one. */
	private Path processorAt(String relative) throws Exception {
		Path directory = processorRoot.toPath().resolve(relative);
		Files.createDirectories(directory);
		Files.writeString(directory.resolve("process"), "#!/bin/bash\n");
		Files.writeString(directory.resolve("processor.zip"), "archive");
		return directory;
	}

	@Test
	public void aGatheredProcessorDirectoryIsRemoved() throws Exception {
		Path processor = processorAt("2/20260916-01.06.50.701/bench_v1");

		Spaces.cleanProcessorFiles(
				Collections.singletonList(processor.toString()),
				processorRoot.toPath());

		assertFalse(Files.exists(processor), "the processor directory must be gone");
	}

	@Test
	public void everyGatheredProcessorIsRemovedEvenWhenOneIsRefused() throws Exception {
		Path first = processorAt("2/20260916-01.06.50.701/bench_v1");
		Path second = processorAt("3/20260916-01.07.00.000/post_v1");
		Path outside = outsideRoot.toPath().resolve("keep");
		Files.createDirectories(outside);
		Files.writeString(outside.resolve("file"), "keep");

		Spaces.cleanProcessorFiles(
				Arrays.asList(first.toString(), outside.toString(), second.toString()),
				processorRoot.toPath());

		assertFalse(Files.exists(first), "the first processor must be gone");
		assertFalse(Files.exists(second), "a refused path must not stop the rest");
		assertTrue(Files.exists(outside), "a path outside the processor root must be untouched");
	}

	@Test
	public void aPathOutsideTheProcessorRootIsRefused() throws Exception {
		Path outside = outsideRoot.toPath().resolve("other");
		Files.createDirectories(outside);
		Files.writeString(outside.resolve("file"), "keep");

		Spaces.cleanProcessorFiles(
				Collections.singletonList(outside.toString()),
				processorRoot.toPath());

		assertTrue(Files.exists(outside), "a path outside the processor root must survive");
	}

	@Test
	public void anEmptyOrNullListIsHandled() {
		Spaces.cleanProcessorFiles(null, processorRoot.toPath());
		Spaces.cleanProcessorFiles(Collections.emptyList(), processorRoot.toPath());
	}

	@Test
	public void aDanglingSymlinkAtTheProcessorPathIsRemoved() throws Exception {
		Path gone = outsideRoot.toPath().resolve("never-existed");
		Path link = processorRoot.toPath().resolve("2/20260916-01.06.50.701/bench_v1");
		Files.createDirectories(link.getParent());
		try {
			Files.createSymbolicLink(link, gone);
		} catch (UnsupportedOperationException | java.io.IOException e) {
			Assumptions.abort("this file store cannot create symlinks: " + e);
		}

		Spaces.cleanProcessorFiles(
				Collections.singletonList(link.toString()),
				processorRoot.toPath());

		assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS),
				"a dangling symlink must not be left behind");
	}

	@Test
	public void aSymlinkInsideTheProcessorIsRemovedWithoutItsTarget() throws Exception {
		Path processor = processorAt("2/20260916-01.06.50.701/bench_v1");
		Path outsideDir = outsideRoot.toPath().resolve("target");
		Files.createDirectories(outsideDir);
		Path outsideFile = outsideDir.resolve("outside.txt");
		Files.writeString(outsideFile, "outside");
		Path link = processor.resolve("escape");
		try {
			Files.createSymbolicLink(link, outsideDir);
		} catch (UnsupportedOperationException | java.io.IOException e) {
			Assumptions.abort("this file store cannot create symlinks: " + e);
		}

		Spaces.cleanProcessorFiles(
				Collections.singletonList(processor.toString()),
				processorRoot.toPath());

		assertFalse(Files.exists(processor), "the processor directory must be gone");
		assertTrue(Files.exists(outsideDir), "the symlink's target directory must survive");
		assertTrue(Files.exists(outsideFile), "the symlink's target file must survive");
	}
}
