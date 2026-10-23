package org.starexec.servlets;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.starexec.constants.R;
import org.starexec.data.database.ErrorLogs;
import org.starexec.data.database.Reports;
import org.starexec.data.database.Solvers;
import org.starexec.data.database.Users;
import org.starexec.data.security.UploadSecurity;
import org.starexec.data.to.Permission;
import org.starexec.data.to.User;
import org.starexec.data.to.tuples.UploadSolverResult.UploadSolverStatus;
import org.starexec.jobs.JobManager;
import org.starexec.util.ArchiveUtil;
import org.starexec.util.PartWrapper;
import org.starexec.util.SessionUtil;
import org.starexec.util.Util;
import org.starexec.util.Validator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A solver upload that does not add its solver leaves no files behind, including when its user
 * is deleted while the upload is under way (#275).
 *
 * <p>Each test posts the upload form through doPost. The database, the session, the archive
 * extraction and the sandbox permissions are replaced at their static boundaries. The files are
 * real, under a temporary solver root. A user deletion is simulated the way Users.deleteUser
 * ends: the user's row is gone, so AddSolver refuses the insert, and the user's directory under
 * the solver root is deleted.
 */
public class SolverUploadCleanupTest {

	private static final int OWNER = 5;
	private static final int SOLVER_ID = 77;
	private static final String NAME = "synthetic-solver";

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final AtomicBoolean ownerExists = new AtomicBoolean(true);
	private final AtomicInteger inserts = new AtomicInteger();
	private final AtomicInteger extractions = new AtomicInteger();
	private File root;
	private File sandbox;
	private File neighbour;
	private HttpServletRequest request;
	private HttpServletResponse response;
	private HashMap<String, Object> form;

	/** What happens while the archive is received, extracted, and when the solver is inserted. */
	private Runnable duringTransfer = () -> { };
	private Runnable duringExtraction = () -> { };
	private boolean insertFails;
	private boolean buildOnStarExec;
	private String descriptionInArchive;
	private long diskQuota = 1_000_000;

	@BeforeClass
	public static void compileValidatorPatterns() {
		Validator.initialize();
	}

	@Before
	public void upload() throws IOException {
		root = tmp.newFolder("Solvers");
		sandbox = tmp.newFolder("sandbox");
		neighbour = new File(root, "6/cvc5/20261018-10.00.00.000/bin/starexec_run_default");
		write(neighbour, "#!/bin/sh\n");

		request = mock(HttpServletRequest.class);
		response = mock(HttpServletResponse.class);
		when(request.getHeader("Content-Type")).thenReturn("multipart/form-data; boundary=synthetic");

		PartWrapper archive = mock(PartWrapper.class);
		when(archive.getName()).thenReturn("solver.zip");
		doAnswer(invocation -> {
			write(invocation.getArgument(0), "synthetic archive");
			return null;
		}).when(archive).write(any(File.class));

		form = new HashMap<>();
		form.put("upMethod", "local");
		form.put("f", archive);
		form.put(R.SPACE, "3");
		form.put("sn", NAME);
		form.put("dlable", "true");
		form.put("descMethod", "text");
		form.put("desc", "A synthetic solver");
		form.put("execType", "1");
		form.put("runTestJob", "false");
	}

	@Test
	public void anUploadKeepsTheSolverItAdded() throws Exception {
		post();

		verify(response).sendRedirect(endsWith("secure/details/solver.jsp?id=" + SOLVER_ID));
		assertTrue(new File(onlySolverDirectory(), "bin/starexec_run_default").isFile());
		assertFalse("the sandbox is deleted", sandbox.exists());
	}

	@Test
	public void anUploadToBuildOnStarExecKeepsBothDirectories() throws Exception {
		buildOnStarExec = true;

		post();

		verify(response).sendRedirect(endsWith(
				"secure/details/solver.jsp?id=" + SOLVER_ID + "&buildmsg=Building Solver On Starexec"));
		File[] dirs = solverDirectories();
		assertEquals(2, dirs.length);
		File src = dirs[0].getName().endsWith("_src") ? dirs[0] : dirs[1];
		assertTrue(new File(src, "bin/starexec_run_default").isFile());
	}

	/**
	 * The race from #275: the user is deleted after the upload created its directory, and the
	 * copy out of the sandbox creates the user's directory again. Before the fix the solver's
	 * files stayed there, owned by no user.
	 */
	@Test
	public void aUserDeletedDuringExtractionLeavesNothingBehind() throws Exception {
		duringExtraction = this::deleteOwner;

		post();

		verifyInternalError();
		assertEquals("the insert was attempted and refused", 1, inserts.get());
		assertNothingLeftForTheOwner();
	}

	@Test
	public void aUserDeletedDuringExtractionOfABuildUploadLeavesNothingBehind() throws Exception {
		buildOnStarExec = true;
		duringExtraction = this::deleteOwner;

		post();

		verifyInternalError();
		assertEquals("the archive was extracted, so the deletion ran mid-upload", 1, extractions.get());
		assertNothingLeftForTheOwner();
	}

	/** The deletion ran while the archive was being received, before the upload created anything. */
	@Test
	public void aUserDeletedDuringTheTransferIsRefusedAndLeavesNothing() throws Exception {
		duringTransfer = this::deleteOwner;

		post();

		verify(response).sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
				"Could not find the user uploading this solver");
		assertEquals("no insert is attempted", 0, inserts.get());
		assertNothingLeftForTheOwner();
	}

	@Test
	public void aFailedInsertLeavesNoFiles() throws Exception {
		insertFails = true;

		post();

		verifyInternalError();
		assertEquals(1, inserts.get());
		assertArrayEquals(new File[0], solverDirectories());
	}

	/** An upload to build on StarExec has two directories: the solver's, and its sources. */
	@Test
	public void aFailedInsertOfABuildUploadLeavesNoFiles() throws Exception {
		buildOnStarExec = true;
		insertFails = true;

		post();

		verifyInternalError();
		assertEquals(1, inserts.get());
		assertArrayEquals(new File[0], solverDirectories());
	}

	@Test
	public void aRefusedDescriptionLeavesNoFiles() throws Exception {
		form.put("descMethod", "upload");
		descriptionInArchive = "<b>not a valid description</b>";

		post();

		verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST,
				UploadSolverStatus.DESCRIPTION_MALFORMED.message);
		assertEquals("the files were copied before the description was read", 1, extractions.get());
		assertEquals(0, inserts.get());
		assertArrayEquals(new File[0], solverDirectories());
	}

	@Test
	public void anUploadOverQuotaLeavesNoDirectory() throws Exception {
		diskQuota = 1;

		post();

		verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST, UploadSolverStatus.EXCEED_QUOTA.message);
		assertArrayEquals(new File[0], solverDirectories());
	}

	/** Users.deleteUser's end: the row is gone and the user's solver directory is deleted. */
	private void deleteOwner() {
		ownerExists.set(false);
		try {
			FileUtils.deleteDirectory(new File(root, "" + OWNER));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private void post() throws Exception {
		User owner = new User();
		owner.setDiskQuota(diskQuota);
		owner.setDiskUsage(0);
		try (MockedStatic<UploadSecurity> security = mockStatic(UploadSecurity.class);
		     MockedStatic<SessionUtil> session = mockStatic(SessionUtil.class);
		     MockedStatic<Users> users = mockStatic(Users.class);
		     MockedStatic<ArchiveUtil> archives = mockStatic(ArchiveUtil.class);
		     MockedStatic<Reports> reports = mockStatic(Reports.class);
		     MockedStatic<JobManager> jobs = mockStatic(JobManager.class);
		     MockedStatic<ErrorLogs> errorLogs = mockStatic(ErrorLogs.class);
		     MockedStatic<R> r = mockStatic(R.class, invocation ->
				     invocation.getMethod().getName().equals("getSolverPath")
						     ? root.getAbsolutePath() : invocation.callRealMethod());
		     MockedStatic<Util> util = mockStatic(Util.class, invocation -> {
			     switch (invocation.getMethod().getName()) {
			     case "parseMultipartRequest":
				     duringTransfer.run();
				     return form;
			     case "getRandomSandboxDirectory":
				     return sandbox;
			     case "sandboxChmodDirectoryDirect":
			     case "sandboxChmodDirectory":
				     return null;
			     default:
				     return invocation.callRealMethod();
			     }
		     });
		     MockedStatic<Solvers> solvers = mockStatic(Solvers.class, invocation -> {
			     if (!invocation.getMethod().getName().equals("add") || invocation.getArguments().length != 2) {
				     return invocation.callRealMethod();
			     }
			     // AddSolver refuses an owner who no longer exists, as the database does.
			     inserts.incrementAndGet();
			     if (!ownerExists.get()) {
				     throw new SQLException("User " + OWNER + " not found when adding solver", "P0002");
			     }
			     if (insertFails) {
				     throw new SQLException("synthetic insert failure");
			     }
			     return SOLVER_ID;
		     })) {
			security.when(UploadSecurity::uploadsFrozen).thenReturn(false);
			session.when(() -> SessionUtil.getUserId(request)).thenReturn(OWNER);
			session.when(() -> SessionUtil.getPermission(eq(request), anyInt())).thenReturn(new Permission(true));
			users.when(() -> Users.get(OWNER)).thenAnswer(invocation -> ownerExists.get() ? owner : null);
			archives.when(() -> ArchiveUtil.getArchiveSize(anyString())).thenReturn(17L);
			archives.when(() -> ArchiveUtil.extractArchiveAsSandbox(anyString(), anyString())).thenAnswer(invocation -> {
				extractions.incrementAndGet();
				extract(new File((String) invocation.getArgument(0)), new File((String) invocation.getArgument(1)));
				duringExtraction.run();
				return true;
			});

			new UploadSolver().doPost(request, response);
		}
	}

	/** What extracting the archive leaves in the sandbox: the solver's files, without the archive. */
	private void extract(File archive, File into) throws IOException {
		Files.delete(archive.toPath());
		write(new File(into, "bin/starexec_run_default"), "#!/bin/sh\necho synthetic\n");
		if (buildOnStarExec) {
			write(new File(into, R.SOLVER_BUILD_SCRIPT), "#!/bin/sh\n");
		}
		if (descriptionInArchive != null) {
			write(new File(into, R.SOLVER_DESC_PATH), descriptionInArchive);
		}
	}

	private void verifyInternalError() throws IOException {
		verify(response).sendError(eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR), any());
		verify(response, never()).sendRedirect(anyString());
	}

	private void assertNothingLeftForTheOwner() {
		assertFalse("nothing is left under the deleted user's solver directory",
				new File(root, "" + OWNER).exists());
		assertTrue("another user's solver is untouched", neighbour.isFile());
		assertFalse("the sandbox is deleted", sandbox.exists());
	}

	/** The directories under the owner's directory for the solver's name. */
	private File[] solverDirectories() {
		File[] dirs = new File(root, OWNER + "/" + NAME).listFiles();
		return dirs == null ? new File[0] : dirs;
	}

	private File onlySolverDirectory() {
		File[] dirs = solverDirectories();
		assertEquals(1, dirs.length);
		return dirs[0];
	}

	private static void write(File file, String content) throws IOException {
		Files.createDirectories(file.getParentFile().toPath());
		Files.writeString(file.toPath(), content);
	}
}
