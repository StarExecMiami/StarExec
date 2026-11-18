package org.starexec.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Util#claimNewDirectory} gives every upload or copy a directory of its own. */
public class ClaimNewDirectoryTest {

	@TempDir
	public File tmp;

	@Test
	public void aClaimCreatesTheDirectoryAndItsParents() throws Exception {
		File dir = uploadDir();

		assertEquals(dir, Util.claimNewDirectory(dir));
		assertTrue(dir.isDirectory());
	}

	/** Two uploads by one user in the same millisecond must not share a directory. */
	@Test
	public void aDirectoryThatExistsIsNeverReused() throws Exception {
		File dir = uploadDir();
		File first = Util.claimNewDirectory(dir);
		write(new File(first, "theirs"));

		File second = Util.claimNewDirectory(dir);

		assertEquals(new File(dir.getPath() + "-1"), second);
		assertArrayEquals(new String[0], second.list());
		assertTrue(new File(first, "theirs").isFile(), "the other upload's file is untouched");
		assertEquals(new File(dir.getPath() + "-2"), Util.claimNewDirectory(dir));
	}

	@Test
	public void aClaimGivesUpAfterTheLastSuffix() throws Exception {
		File dir = uploadDir();
		for (int i = 0; i <= Util.MAX_DIRECTORY_SUFFIX; i++) {
			Util.claimNewDirectory(dir);
		}

		assertThrows(FileAlreadyExistsException.class, () -> Util.claimNewDirectory(dir));
	}

	/** A directory named the way uploads name theirs, whose parents do not exist yet. */
	private File uploadDir() {
		return new File(tmp, "uploads/5/20261018-10.00.00.000");
	}

	private static void write(File file) throws IOException {
		Files.writeString(file.toPath(), "synthetic\n");
	}
}
