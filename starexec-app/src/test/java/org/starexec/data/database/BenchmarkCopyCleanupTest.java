package org.starexec.data.database;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.constants.R;
import org.starexec.data.to.Benchmark;
import org.starexec.data.to.Processor;
import org.starexec.data.to.User;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * A benchmark copy that is not added leaves no file behind, including when its user is deleted
 * while a copy of several benchmarks is under way (#275).
 *
 * <p>The database is replaced at its static boundary; the files are real, under a temporary
 * benchmark root. A user deletion is simulated the way Users.deleteUser ends: the user's row is
 * gone, so the insert fails, and the user's directory under the benchmark root is deleted.
 */
public class BenchmarkCopyCleanupTest {

	private static final int OWNER = 5;
	private static final int SPACE = 3;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final AtomicBoolean ownerExists = new AtomicBoolean(true);
	private final AtomicInteger inserts = new AtomicInteger();
	private File root;
	private File neighbour;
	/** The id the n-th insert returns (counting from 1), or -1 for a failed insert. */
	private IntUnaryOperator insert = n -> 90 + n;

	@Before
	public void setUp() throws IOException {
		root = tmp.newFolder("Benchmarks");
		neighbour = new File(root, "6/20261018-10.00.00.000/other.smt2");
		write(neighbour, "(check-sat)\n");
	}

	@Test
	public void aCopyThatIsAddedKeepsItsFile() throws Exception {
		Benchmark original = original(33, "a.smt2");

		int id = copy(original);

		assertEquals(91, id);
		File[] dirs = ownerDirectories();
		assertEquals(1, dirs.length);
		assertTrue(new File(dirs[0], "33/a.smt2").isFile());
	}

	@Test
	public void aCopyThatIsNotAddedLeavesNoFile() throws Exception {
		insert = n -> -1;
		Benchmark original = original(33, "a.smt2");

		int id = copy(original);

		assertEquals(-1, id);
		assertEquals(1, inserts.get());
		assertArrayEquals("no directory is left", new File[0], ownerDirectories());
		assertTrue("the original is untouched", new File(original.getPath()).isFile());
	}

	@Test
	public void aCopyWhoseFileCannotBeCopiedLeavesNoDirectory() throws Exception {
		Benchmark original = original(33, "a.smt2");
		// A directory where the file should be: the copy itself fails.
		File unreadable = new File(tmp.getRoot(), "originals/b.smt2");
		Files.createDirectories(unreadable.toPath());
		original.setPath(unreadable.getAbsolutePath());

		int id = copy(original);

		assertEquals(-1, id);
		assertEquals("nothing is inserted", 0, inserts.get());
		assertArrayEquals("no directory is left", new File[0], ownerDirectories());
	}

	/**
	 * The race from #275: the user is deleted while the first of two benchmarks is being added.
	 * The copy of the second creates the user's directory again.
	 */
	@Test
	public void aUserDeletedDuringACopyOfSeveralLeavesNothing() throws Exception {
		insert = n -> {
			if (n == 1) {
				deleteOwner();
			}
			return -1;
		};
		List<Benchmark> originals = Arrays.asList(original(33, "a.smt2"), original(34, "b.smt2"));

		List<Integer> ids = copy(originals);

		assertEquals(Arrays.asList(-1, -1), ids);
		assertFalse("nothing is left under the deleted user's benchmark directory",
				new File(root, "" + OWNER).exists());
		assertTrue("another user's benchmark is untouched", neighbour.isFile());
	}

	/** Users.deleteUser's end: the row is gone and the user's benchmark directory is deleted. */
	private void deleteOwner() {
		ownerExists.set(false);
		try {
			FileUtils.deleteDirectory(new File(root, "" + OWNER));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** A benchmark of another user, outside the owner's directory. */
	private Benchmark original(int id, String name) throws IOException {
		File file = new File(tmp.getRoot(), "originals/" + id + "/" + name);
		write(file, "(check-sat)\n");
		Processor type = new Processor();
		type.setId(1);
		Benchmark b = new Benchmark();
		b.setId(id);
		b.setName(name);
		b.setPath(file.getAbsolutePath());
		b.setType(type);
		b.setDiskSize(file.length());
		b.setDownloadable(true);
		b.setAttributes(new HashMap<>());
		return b;
	}

	private int copy(Benchmark original) throws Exception {
		return copy(Collections.singletonList(original)).get(0);
	}

	private List<Integer> copy(List<Benchmark> originals) throws Exception {
		User owner = new User();
		owner.setId(OWNER);
		try (MockedStatic<R> r = mockStatic(R.class, invocation ->
				     invocation.getMethod().getName().equals("getBenchmarkPath")
						     ? root.getAbsolutePath() : invocation.callRealMethod());
		     MockedStatic<Users> users = mockStatic(Users.class, invocation -> {
			     switch (invocation.getMethod().getName()) {
			     case "get":
				     return ownerExists.get() ? owner : null;
			     case "exists":
				     return ownerExists.get();
			     default:
				     return Mockito.RETURNS_DEFAULTS.answer(invocation);
			     }
		     });
		     MockedStatic<Benchmarks> benchmarks = mockStatic(Benchmarks.class, invocation -> {
			     switch (invocation.getMethod().getName()) {
			     case "addAndAssociate":
				     int n = inserts.incrementAndGet();
				     // benchmarks.user_id references users: the insert fails once the row is gone.
				     int result = insert.applyAsInt(n);
				     return ownerExists.get() ? result : -1;
			     case "getBenchDependencies":
				     return Collections.emptyList();
			     default:
				     return invocation.callRealMethod();
			     }
		     })) {
			return Benchmarks.copyBenchmarks(originals, OWNER, SPACE);
		}
	}

	/** The directories under the owner's benchmark directory. */
	private File[] ownerDirectories() {
		File[] dirs = new File(root, "" + OWNER).listFiles();
		return dirs == null ? new File[0] : dirs;
	}

	private static void write(File file, String content) throws IOException {
		Files.createDirectories(file.getParentFile().toPath());
		Files.writeString(file.toPath(), content);
	}
}
