package org.starexec.data.database;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;
import org.starexec.constants.R;
import org.starexec.data.to.Solver;
import org.starexec.data.to.Solver.ExecutableType;
import org.starexec.data.to.SolverBuildStatus;
import org.starexec.data.to.User;
import org.starexec.util.Util;
import org.starexec.util.Validator;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.starexec.test.TemporaryFolders.newFolder;

/**
 * A solver upload or copy creates new directories for the solver and, if the solver is not
 * added, deletes them again (#275).
 *
 * <p>The files are real, under a temporary solver root. The owner's existence, the solver insert
 * and the error log table are replaced at their static boundaries.
 */
public class SolverDirectoryCleanupTest {

	private static final int OWNER = 5;
	private static final String DATE = "20261018-10.00.00.000";

	@TempDir
	public File tmp;

	private final AtomicBoolean ownerExists = new AtomicBoolean(true);
	private final AtomicInteger inserts = new AtomicInteger();
	private File root;

	/** A copy validates its configuration names; the application compiles the patterns at startup. */
	@BeforeAll
	public static void compileValidatorPatterns() {
		Validator.initialize();
	}

	@BeforeEach
	public void solverRoot() throws IOException {
		root = newFolder(tmp, "Solvers");
	}

	@Test
	public void aSourceDirectoryIsNeverReused() throws Exception {
		File dir = Util.claimNewDirectory(solverDir("z3"));

		File src = Solvers.claimSourceDirectory(dir);

		assertEquals(new File(dir.getPath() + "_src"), src);
		assertTrue(src.isDirectory());
		assertThrows(FileAlreadyExistsException.class, () -> Solvers.claimSourceDirectory(dir));
	}

	@Test
	public void anUnaddedSolversDirectoriesAreDeletedAndNothingElse() throws Exception {
		File other = Util.claimNewDirectory(solverDir("z3"));
		write(new File(other, "bin/starexec_run_default"));
		File dir = Util.claimNewDirectory(solverDir("z3"));
		File src = Solvers.claimSourceDirectory(dir);
		write(new File(dir, "bin/starexec_run_default"));
		write(new File(src, "src/main.c"));

		deleteUnadded(dir, src);

		assertFalse(dir.exists());
		assertFalse(src.exists());
		assertTrue(new File(other, "bin/starexec_run_default").isFile(),
				"another solver of the same name is untouched");
	}

	/**
	 * A concurrent upload of the same name may have created the parent and be about to create its
	 * own directory in it, so the parents of a live owner's solver are not touched.
	 */
	@Test
	public void theDirectoriesAboveAreLeftForAnOwnerWhoExists() throws Exception {
		File dir = Util.claimNewDirectory(solverDir("z3"));

		deleteUnadded(dir);

		assertFalse(dir.exists());
		assertTrue(dir.getParentFile().isDirectory());
	}

	/** Users.deleteUser removed the owner's directory; the upload created it again. */
	@Test
	public void forADeletedOwnerTheEmptyDirectoriesAboveAreDeletedToo() throws Exception {
		File neighbour = new File(root, "6/cvc5/" + DATE + "/bin/starexec_run_default");
		write(neighbour);
		File buildOutput = new File(root, "buildoutput/7.txt");
		write(buildOutput);
		File dir = Util.claimNewDirectory(solverDir("z3"));
		File src = Solvers.claimSourceDirectory(dir);
		write(new File(src, "src/main.c"));
		ownerExists.set(false);

		deleteUnadded(dir, src);

		assertFalse(new File(root, "" + OWNER).exists());
		assertTrue(neighbour.isFile(), "another user's solver is untouched");
		assertTrue(buildOutput.isFile(), "build output is untouched");
	}

	@Test
	public void theSolverRootIsNeverDeleted() throws Exception {
		File dir = Util.claimNewDirectory(solverDir("z3"));
		ownerExists.set(false);

		deleteUnadded(dir);

		assertArrayEquals(new String[0], root.list());
		assertTrue(root.isDirectory());
	}

	/** Another upload of the deleted owner is still running: its directory stays, and so do its parents. */
	@Test
	public void aDirectoryAboveThatIsNotEmptyIsKept() throws Exception {
		File other = Util.claimNewDirectory(solverDir("z3"));
		write(new File(other, "solver.zip"));
		File dir = Util.claimNewDirectory(solverDir("z3"));
		ownerExists.set(false);

		deleteUnadded(dir);

		assertFalse(dir.exists());
		assertTrue(new File(other, "solver.zip").isFile());
	}

	/** A parent that is already gone does not stop the walk: the one above may still be empty. */
	@Test
	public void aMissingParentIsSkipped() throws Exception {
		File ownerDir = new File(root, "" + OWNER);
		assertTrue(ownerDir.mkdir());
		ownerExists.set(false);

		deleteUnadded(solverDir("z3"));

		assertFalse(ownerDir.exists());
	}

	@Test
	public void directoriesOutsideTheSolverRootAreNeverDeleted() throws Exception {
		File outside = newFolder(tmp, "elsewhere", "5", "z3");
		File dir = Util.claimNewDirectory(new File(outside, DATE));
		ownerExists.set(false);

		deleteUnadded(dir);

		assertFalse(dir.exists());
		assertTrue(outside.isDirectory());
	}

	@Test
	public void nothingClaimedMeansNoLookup() throws Exception {
		try (MockedStatic<Users> users = mockStatic(Users.class);
		     MockedStatic<R> r = solverRootAt(root);
		     MockedStatic<ErrorLogs> errorLogs = mockStatic(ErrorLogs.class)) {
			Solvers.deleteDirectoriesOfUnaddedSolver(OWNER, null, null);

			users.verify(() -> Users.get(anyInt()), never());
		}
	}

	@Test
	public void aCopyThatIsNotAddedLeavesNoDirectory() throws Exception {
		Solver original = originalWithSources();

		int id = copy(original, invocation -> {
			throw new SQLException("synthetic insert failure");
		});

		assertEquals(-1, id);
		assertEquals(1, inserts.get(), "the copy got as far as the insert");
		assertArrayEquals(new String[0], new File(root, OWNER + "/z3").list());
		assertTrue(new File(original.getPath(), "bin/starexec_run_default").isFile(), "the original is untouched");
		assertTrue(new File(original.getPath() + "_src", "main.c").isFile(), "the original's sources are untouched");
	}

	@Test
	public void aCopyThatIsAddedKeepsItsFiles() throws Exception {
		Solver original = originalWithSources();

		int id = copy(original, invocation -> 77);

		assertEquals(77, id);
		File[] copies = new File(root, OWNER + "/z3").listFiles(File::isDirectory);
		assertEquals(2, copies.length);
		File copy = copies[0].getName().endsWith("_src") ? copies[1] : copies[0];
		assertTrue(new File(copy, "bin/starexec_run_default").isFile());
		assertTrue(new File(copy.getPath() + "_src", "main.c").isFile());
	}

	private File solverDir(String name) {
		return Solvers.solverDirectory(root, OWNER, name, DATE);
	}

	private void deleteUnadded(File... claimed) {
		try (MockedStatic<Users> users = owner();
		     MockedStatic<R> r = solverRootAt(root);
		     MockedStatic<ErrorLogs> errorLogs = mockStatic(ErrorLogs.class)) {
			Solvers.deleteDirectoriesOfUnaddedSolver(OWNER, claimed);
		}
	}

	/** Copies the solver to OWNER, with Solvers.add answered by addAnswer. */
	private int copy(Solver original, Answer<Object> addAnswer) {
		try (MockedStatic<Users> users = owner();
		     MockedStatic<R> r = solverRootAt(root);
		     MockedStatic<ErrorLogs> errorLogs = mockStatic(ErrorLogs.class);
		     MockedStatic<Solvers> solvers = mockStatic(Solvers.class, invocation -> {
			     if (invocation.getMethod().getName().equals("add") && invocation.getArguments().length == 2) {
				     inserts.incrementAndGet();
				     return addAnswer.answer(invocation);
			     }
			     return invocation.callRealMethod();
		     })) {
			return Solvers.copySolver(original, OWNER, 3);
		}
	}

	/** A solver of another user, built on StarExec, so it has a sources directory too. */
	private Solver originalWithSources() throws IOException {
		File path = newFolder(tmp, "Solvers-of-user-6", "z3", DATE);
		write(new File(path, "bin/starexec_run_default"));
		write(new File(path.getPath() + "_src", "main.c"));
		Solver original = new Solver();
		original.setName("z3");
		original.setPath(path.getAbsolutePath());
		original.setType(ExecutableType.SOLVER);
		SolverBuildStatus status = new SolverBuildStatus();
		status.setCode(1);
		original.setBuildStatus(status);
		return original;
	}

	private MockedStatic<Users> owner() {
		MockedStatic<Users> users = mockStatic(Users.class);
		users.when(() -> Users.get(OWNER)).thenAnswer(invocation -> ownerExists.get() ? new User() : null);
		return users;
	}

	/** R with only the solver root moved, so no other path is affected. */
	private static MockedStatic<R> solverRootAt(File root) {
		return mockStatic(R.class, invocation -> invocation.getMethod().getName().equals("getSolverPath")
				? root.getAbsolutePath() : invocation.callRealMethod());
	}

	private static void write(File file) throws IOException {
		Files.createDirectories(file.getParentFile().toPath());
		Files.writeString(file.toPath(), "synthetic\n");
	}
}
