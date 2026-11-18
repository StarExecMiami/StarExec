package org.starexec.servlets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.constants.R;
import org.starexec.data.database.Users;
import org.starexec.util.UploadArtifactPathGuard;
import org.starexec.util.Util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mockStatic;
import static org.starexec.test.TemporaryFolders.newFolder;

/**
 * The directories benchmarks are placed in: each upload, copy or add gets one of its own, and
 * deleting it after a failure deletes nothing else (#275).
 */
public class BenchmarkUploadDirectoryTest {

	private static final int OWNER = 5;
	private static final LocalDateTime START = LocalDateTime.of(2026, 10, 23, 14, 5, 9, 17_000_000);
	private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern(R.PATH_DATE_FORMAT);

	@TempDir
	public File tmp;

	private File root;
	private boolean ownerExists = true;
	private boolean existsFails;

	@BeforeEach
	public void setUp() throws IOException {
		root = newFolder(tmp, "Benchmarks");
	}

	@Test
	public void aDirectoryIsNamedByTheTimeItWasAskedFor() throws Exception {
		File dir = claim(null, START);

		assertEquals(new File(root, OWNER + "/" + NAME.format(START)), dir);
		assertTrue(dir.isDirectory());
		assertTrue(UploadArtifactPathGuard.isUploadDirectoryName(dir.getName()),
				"the upload path guard accepts the name");
	}

	/** A second request in the same millisecond gets the next one, still a time the guard accepts. */
	@Test
	public void aDirectoryThatExistsIsNeverUsedAgain() throws Exception {
		File first = claim(null, START);
		File second = claim(null, START);

		assertEquals(NAME.format(START.plus(1, ChronoUnit.MILLIS)), second.getName());
		assertFalse(first.equals(second));
		assertTrue(UploadArtifactPathGuard.isUploadDirectoryName(second.getName()));
	}

	/** Anything already there, a file too, is passed over; nothing in it is touched. */
	@Test
	public void aFileWithTheNameIsPassedOverAndKept() throws Exception {
		File taken = new File(root, OWNER + "/" + NAME.format(START));
		Files.createDirectories(taken.getParentFile().toPath());
		Files.writeString(taken.toPath(), "not a directory");

		File dir = claim(null, START);

		assertEquals(NAME.format(START.plus(1, ChronoUnit.MILLIS)), dir.getName());
		assertEquals("not a directory", Files.readString(taken.toPath()));
	}

	@Test
	public void theNamedDirectoryIsInsideANewOne() throws Exception {
		claim(null, START);

		File dir = claim("upload-session-x", START);

		assertEquals("upload-session-x", dir.getName());
		assertEquals(NAME.format(START.plus(1, ChronoUnit.MILLIS)), dir.getParentFile().getName());
		assertTrue(dir.isDirectory());
	}

	/** A name that is not one path component is refused, and the directory made for it removed. */
	@Test
	public void aNameThatLeavesItsDirectoryIsRefusedAndLeavesNothing() throws Exception {
		try {
			claim("../escape", START);
			fail("a name that leaves its directory is refused");
		} catch (IllegalArgumentException expected) {
			// refused
		}

		assertEquals(0, new File(root, "" + OWNER).list().length, "no directory is left");
		assertFalse(new File(root, OWNER + "/escape").exists());
	}

	@Test
	public void claimingGivesUpAfterTheLastMillisecondItTries() throws Exception {
		for (int i = 0; i <= Util.MAX_DIRECTORY_SUFFIX; i++) {
			Files.createDirectories(new File(root, OWNER + "/" + NAME.format(START.plus(i, ChronoUnit.MILLIS))).toPath());
		}

		try {
			claim(null, START);
			fail("no directory is left to claim");
		} catch (IOException expected) {
			// gave up
		}
		assertEquals(Util.MAX_DIRECTORY_SUFFIX + 1, new File(root, "" + OWNER).list().length);
	}

	@Test
	public void deletingRemovesTheWholeClaimedDirectoryAndKeepsTheUsers() throws Exception {
		File dir = claim("33", START);
		Files.writeString(new File(dir, "a.smt2").toPath(), "(check-sat)\n");
		File other = claim(null, START);

		delete(dir);

		assertFalse(dir.getParentFile().exists(), "the time-named directory goes with the named one");
		assertTrue(new File(root, "" + OWNER).isDirectory(), "the user still exists, so their directory is kept");
		assertTrue(other.isDirectory(), "another claimed directory is untouched");
	}

	@Test
	public void deletingForADeletedUserAlsoRemovesTheirEmptyDirectory() throws Exception {
		File dir = claim(null, START);
		ownerExists = false;

		delete(dir);

		assertFalse(new File(root, "" + OWNER).exists());
		assertTrue(root.isDirectory(), "the benchmark root is kept");
	}

	/** A failed lookup is not taken for a deleted user. */
	@Test
	public void aFailedLookupKeepsTheUsersDirectory() throws Exception {
		File dir = claim(null, START);
		existsFails = true;

		delete(dir);

		assertFalse(dir.exists());
		assertTrue(new File(root, "" + OWNER).isDirectory());
	}

	/** Only a directory this class claimed for that user is ever deleted. */
	@Test
	public void deletingRefusesAnythingElse() throws Exception {
		File mine = claim(null, START);
		File usersDir = new File(root, "" + OWNER);
		File othersUpload = new File(root, "6/" + NAME.format(START));
		Files.createDirectories(othersUpload.toPath());
		File notTimeNamed = new File(root, OWNER + "/keep");
		Files.createDirectories(notTimeNamed.toPath());
		File outside = newFolder(tmp, "outside");

		delete(usersDir);
		delete(othersUpload);
		delete(notTimeNamed);
		delete(outside);
		delete(root);

		assertTrue(mine.isDirectory());
		assertTrue(othersUpload.isDirectory());
		assertTrue(notTimeNamed.isDirectory());
		assertTrue(outside.isDirectory());
	}

	@Test
	public void theClaimedDirectoryOfAPathIsItsTimeNamedAncestor() {
		Path r = root.toPath();
		String time = NAME.format(START);

		assertEquals(r.resolve(OWNER + "/" + time),
				UploadBenchmark.claimedDirectory(r, OWNER, r.resolve(OWNER + "/" + time + "/upload-session-x/a.part")));
		assertEquals(r.resolve(OWNER + "/" + time), UploadBenchmark.claimedDirectory(r, OWNER, r.resolve(OWNER + "/" + time)));
		assertNull(UploadBenchmark.claimedDirectory(r, OWNER, r.resolve("" + OWNER)));
		assertNull(UploadBenchmark.claimedDirectory(r, OWNER, r.resolve("6/" + time)));
		assertNull(UploadBenchmark.claimedDirectory(r, OWNER, r.resolve(OWNER + "/" + time + "-1")));
		assertNull(UploadBenchmark.claimedDirectory(r, OWNER, r.resolveSibling("elsewhere/" + OWNER + "/" + time)));
	}

	@Test
	public void emptyParentsAreDeletedNearestFirstAndNeverTheRoot() throws Exception {
		Path r = root.toPath();
		Path deep = Files.createDirectories(r.resolve("5/a/b/c"));
		Files.writeString(r.resolve("5/keep.txt"), "kept");

		Util.deleteEmptyParents(r, deep.resolve("file-that-was-there"));

		assertFalse(Files.exists(r.resolve("5/a")));
		assertTrue(Files.isDirectory(r.resolve("5")), "a directory with a file in it stays");

		Files.delete(r.resolve("5/keep.txt"));
		Path empty = Files.createDirectories(r.resolve("5/d"));
		Util.deleteEmptyParents(r, empty.resolve("x"));
		assertFalse(Files.exists(r.resolve("5")));
		assertTrue(Files.isDirectory(r), "the root is never deleted");
	}

	@Test
	public void emptyParentsOutsideTheRootAreLeftAlone() throws Exception {
		Path outside = Files.createDirectories(tmp.toPath().resolve("outside/empty"));

		Util.deleteEmptyParents(root.toPath(), outside.resolve("x"));

		assertTrue(Files.isDirectory(outside));
	}

	private File claim(String name, LocalDateTime start) throws Exception {
		return withRoot(() -> UploadBenchmark.getDirectoryForBenchmarkUpload(OWNER, name, start));
	}

	private void delete(File dir) throws Exception {
		withRoot(() -> {
			UploadBenchmark.deleteDirectoryForBenchmarkUpload(OWNER, dir);
			return null;
		});
	}

	private <T> T withRoot(Callable<T> call) throws Exception {
		try (MockedStatic<R> r = mockStatic(R.class, invocation ->
				     invocation.getMethod().getName().equals("getBenchmarkPath")
						     ? root.getAbsolutePath() : invocation.callRealMethod());
		     MockedStatic<Users> users = mockStatic(Users.class, invocation -> {
			     if (!invocation.getMethod().getName().equals("exists")) {
				     return Mockito.RETURNS_DEFAULTS.answer(invocation);
			     }
			     if (existsFails) {
				     throw new SQLException("synthetic connection failure");
			     }
			     return ownerExists;
		     })) {
			return call.call();
		}
	}
}
