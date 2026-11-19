package org.starexec.servlets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.starexec.constants.R;
import org.starexec.data.database.Benchmarks;
import org.starexec.data.database.UploadJobQueue;
import org.starexec.data.database.Users;
import org.starexec.data.security.UploadSecurity;
import org.starexec.data.to.Benchmark;
import org.starexec.data.to.Permission;
import org.starexec.data.to.UploadJob;
import org.starexec.data.to.User;
import org.starexec.util.PartWrapper;
import org.starexec.util.SessionUtil;
import org.starexec.util.Util;
import org.starexec.util.Validator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.starexec.test.TemporaryFolders.newFolder;

/**
 * A benchmark upload, or a benchmark added from text or from a file, leaves no files behind when it
 * is not queued or added, including when its user is deleted while it is under way (#275).
 *
 * <p>Uploads are posted through doPost. The database, the session and the upload queue are replaced
 * at their static boundaries. The files are real, under a temporary benchmark root. A user deletion
 * is simulated the way Users.deleteUser ends: the user's row is gone, so an insert that names the
 * user fails, and the user's directory under the benchmark root is deleted.
 */
public class BenchmarkUploadCleanupTest {

	private static final int OWNER = 5;
	private static final long JOB_ID = 91;
	private static final int BENCHMARK_ID = 77;
	private static final String OWNER_GONE = "Could not find the user uploading these benchmarks";

	@TempDir
	public File tmp;

	private final AtomicBoolean ownerExists = new AtomicBoolean(true);
	private final List<UploadJob.UploadJobRequest> queued = new ArrayList<>();
	private File root;
	private File neighbour;
	private HttpServletRequest request;
	private HttpServletResponse response;
	private HashMap<String, Object> form;

	/** Runs while the multipart body is still arriving. */
	private Runnable duringTransfer = () -> { };
	/** Runs once, the first time the benchmark root is asked for: just before a directory is made in it. */
	private Runnable beforeDirectory = () -> { };
	private boolean enqueueFails;

	@BeforeAll
	public static void compileValidatorPatterns() {
		Validator.initialize();
	}

	@BeforeEach
	public void form() throws IOException {
		root = newFolder(tmp, "Benchmarks");
		neighbour = new File(root, "6/20261018-10.00.00.000/other.smt2");
		write(neighbour, "(check-sat)\n");

		request = mock(HttpServletRequest.class);
		response = mock(HttpServletResponse.class);
		when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

		PartWrapper archive = mock(PartWrapper.class);
		when(archive.getName()).thenReturn("bench.zip");
		doAnswer(invocation -> {
			// As PartWrapper.write does: the directory must exist already.
			Files.writeString(((File) invocation.getArgument(0)).toPath(), "synthetic archive");
			return null;
		}).when(archive).write(any(File.class));

		form = new HashMap<>();
		form.put("benchType", "1");
		form.put(R.SPACE, "3");
		form.put("download", "true");
		form.put("upMethod", "dump");
		form.put("localOrURLOrGit", "local");
		form.put("benchFile", archive);
	}

	@Test
	public void anUploadIsQueuedWithItsArchive() throws Exception {
		post();

		verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
		assertEquals(1, queued.size());
		File archive = new File(queued.get(0).getArchivePath());
		assertTrue(archive.isFile(), "the archive is kept for the worker");
		assertEquals(new File(root, "" + OWNER),
				archive.getParentFile().getParentFile(), "the archive is in a directory of its own under the owner's");
	}

	/** The race from #275: the user is deleted while the archive is still arriving. */
	@Test
	public void aUserDeletedDuringTheTransferIsRefusedAndLeavesNothing() throws Exception {
		duringTransfer = this::deleteOwner;

		post();

		assertNothingLeftForTheOwner();
		assertEquals(0, queued.size(), "nothing is queued");
		verify(response).sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, OWNER_GONE);
		verify(response, never()).setStatus(HttpServletResponse.SC_ACCEPTED);
	}

	/** The deletion lands after the upload looked its user up, just before it makes its directory. */
	@Test
	public void aUserDeletedJustBeforeTheDirectoryIsMadeLeavesNothing() throws Exception {
		beforeDirectory = this::deleteOwner;

		post();

		assertNothingLeftForTheOwner();
		verify(response).sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, OWNER_GONE);
	}

	@Test
	public void anUploadThatIsNotQueuedLeavesNoFiles() throws Exception {
		enqueueFails = true;

		post();

		verify(response).sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to enqueue upload job");
		assertArrayEquals(new File[0], uploadDirectories(), "no upload directory is left");
		assertTrue(neighbour.isFile(), "another user's benchmark is untouched");
	}

	@Test
	public void anUploadByUrlThatIsNotQueuedLeavesNoDirectory() throws Exception {
		form.put("localOrURLOrGit", "URL");
		form.put("url", "http://example.org/archives/bench.zip");
		form.remove("benchFile");
		enqueueFails = true;

		post();

		verify(response).sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to enqueue upload job");
		assertArrayEquals(new File[0], uploadDirectories(), "no upload directory is left");
	}

	/**
	 * Directories are named by the time to the millisecond. Two producers that ask in the same
	 * millisecond must not share one: the text and file adds register every file in theirs, and a
	 * failed upload deletes its own. A burst of 100 requests is answered within a few milliseconds,
	 * and at most 101 can share one millisecond and still all get a directory.
	 */
	@Test
	public void directoriesAskedForInTheSameMillisecondAreNeverShared() throws Exception {
		Set<File> dirs = new HashSet<>();
		try (MockedStatic<R> r = mockBenchmarkRoot()) {
			for (int i = 0; i < 100; i++) {
				dirs.add(UploadBenchmark.getDirectoryForBenchmarkUpload(OWNER, null));
			}
		}
		assertEquals(100, dirs.size(), "100 calls, 100 directories");
	}

	@Test
	public void aBenchmarkFromTextThatIsAddedKeepsItsFile() throws Exception {
		Integer id = withBenchmarks(benchmarks -> Collections.singletonList(BENCHMARK_ID),
				() -> UploadBenchmark.addBenchmarkFromText("(check-sat)", "quick.smt2", OWNER, 1, false));

		assertEquals(Integer.valueOf(BENCHMARK_ID), id);
		File[] dirs = uploadDirectories();
		assertEquals(1, dirs.length);
		assertTrue(new File(dirs[0], "quick.smt2").isFile());
	}

	@Test
	public void aBenchmarkFromTextThatIsNotAddedLeavesNoFile() throws Exception {
		Integer id = withBenchmarks(benchmarks -> null,
				() -> UploadBenchmark.addBenchmarkFromText("(check-sat)", "quick.smt2", OWNER, 1, false));

		assertNull(id);
		assertArrayEquals(new File[0], uploadDirectories(), "no directory is left");
	}

	@Test
	public void aBenchmarkFromAFileThatIsNotAddedLeavesNoFile() throws Exception {
		File source = new File(tmp, "renamed/output.smt2");
		write(source, "(check-sat)\n");

		Integer id = withBenchmarks(benchmarks -> null,
				() -> UploadBenchmark.addBenchmarkFromFile(source, OWNER, 1, false));

		assertNull(id);
		assertArrayEquals(new File[0], uploadDirectories(), "no directory is left");
		assertTrue(source.isFile(), "the source file is not touched");
	}

	/**
	 * The user is deleted just before the add makes its directory: the add creates the user's
	 * directory again, and its insert fails, as it does once the user's row is gone.
	 */
	@Test
	public void aBenchmarkFromTextWhoseUserIsDeletedLeavesNothing() throws Exception {
		beforeDirectory = this::deleteOwner;

		Integer id = withBenchmarks(
				benchmarks -> ownerExists.get() ? Collections.singletonList(BENCHMARK_ID) : null,
				() -> UploadBenchmark.addBenchmarkFromText("(check-sat)", "quick.smt2", OWNER, 1, false));

		assertNull(id);
		assertNothingLeftForTheOwner();
	}

	/** Users.deleteUser's end: the row is gone and the user's benchmark directory is deleted. */
	private void deleteOwner() {
		ownerExists.set(false);
		try {
			org.apache.commons.io.FileUtils.deleteDirectory(new File(root, "" + OWNER));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private void post() throws Exception {
		User owner = new User();
		owner.setId(OWNER);
		try (MockedStatic<UploadSecurity> security = mockStatic(UploadSecurity.class);
		     MockedStatic<SessionUtil> session = mockStatic(SessionUtil.class);
		     MockedStatic<Users> users = mockUsers(owner);
		     MockedStatic<R> r = mockBenchmarkRoot();
		     MockedStatic<UploadJobQueue> queue = mockStatic(UploadJobQueue.class, invocation -> {
			     switch (invocation.getMethod().getName()) {
			     case "enqueueJob":
				     // upload_jobs.user_id references users: the insert fails once the row is gone.
				     if (enqueueFails || !ownerExists.get()) {
					     return -1L;
				     }
				     queued.add(invocation.getArgument(0));
				     return JOB_ID;
			     default:
				     return Mockito.RETURNS_DEFAULTS.answer(invocation);
			     }
		     });
		     MockedStatic<Util> util = mockStatic(Util.class, invocation -> {
			     if (invocation.getMethod().getName().equals("parseMultipartRequest")) {
				     duringTransfer.run();
				     return form;
			     }
			     return invocation.callRealMethod();
		     })) {
			security.when(UploadSecurity::uploadsFrozen).thenReturn(false);
			session.when(() -> SessionUtil.getUserId(request)).thenReturn(OWNER);
			session.when(() -> SessionUtil.getPermission(eq(request), anyInt())).thenReturn(new Permission(true));

			new UploadBenchmark().doPost(request, response);
		}
	}

	/**
	 * Runs an add with the benchmark root in place and Benchmarks.processAndAdd replaced. The rest of
	 * Benchmarks is real: extractSpacesAndBenchmarks only lists the directory.
	 */
	private Integer withBenchmarks(Function<List<Benchmark>, List<Integer>> processAndAdd, Add add) throws Exception {
		User owner = new User();
		owner.setId(OWNER);
		try (MockedStatic<Users> users = mockUsers(owner);
		     MockedStatic<R> r = mockBenchmarkRoot();
		     MockedStatic<Benchmarks> benchmarks = mockStatic(Benchmarks.class, invocation -> {
			     if (invocation.getMethod().getName().equals("processAndAdd")) {
				     return processAndAdd.apply(invocation.getArgument(0));
			     }
			     return invocation.callRealMethod();
		     })) {
			return add.run();
		}
	}

	private interface Add {
		Integer run() throws Exception;
	}

	/** Users by method name, so the answer does not depend on which lookup the code under test uses. */
	private MockedStatic<Users> mockUsers(User owner) {
		return mockStatic(Users.class, (InvocationOnMock invocation) -> {
			switch (invocation.getMethod().getName()) {
			case "get":
				return ownerExists.get() && invocation.getArgument(0).equals(OWNER) ? owner : null;
			case "exists":
				return ownerExists.get();
			default:
				return Mockito.RETURNS_DEFAULTS.answer(invocation);
			}
		});
	}

	private MockedStatic<R> mockBenchmarkRoot() {
		AtomicBoolean asked = new AtomicBoolean();
		return mockStatic(R.class, invocation -> {
			if (invocation.getMethod().getName().equals("getBenchmarkPath")) {
				if (!asked.getAndSet(true)) {
					beforeDirectory.run();
				}
				return root.getAbsolutePath();
			}
			return invocation.callRealMethod();
		});
	}

	private void assertNothingLeftForTheOwner() {
		assertFalse(new File(root, "" + OWNER).exists(),
				"nothing is left under the deleted user's benchmark directory");
		assertTrue(neighbour.isFile(), "another user's benchmark is untouched");
	}

	/** The directories under the owner's benchmark directory. */
	private File[] uploadDirectories() {
		File[] dirs = new File(root, "" + OWNER).listFiles();
		return dirs == null ? new File[0] : dirs;
	}

	private static void write(File file, String content) throws IOException {
		Files.createDirectories(file.getParentFile().toPath());
		Files.writeString(file.toPath(), content);
	}
}
