package org.starexec.servlets;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.starexec.constants.R;
import org.starexec.data.database.ErrorLogs;
import org.starexec.data.database.Permissions;
import org.starexec.data.database.Uploads;
import org.starexec.data.to.Permission;
import org.starexec.util.ArchiveUtil;
import org.starexec.util.BatchUtil;
import org.starexec.util.JobUtil;
import org.starexec.util.PartWrapper;
import org.starexec.util.SessionUtil;
import org.starexec.util.Util;
import org.starexec.util.Validator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A space or job XML upload extracts its archive into a directory of its own, and deletes that
 * directory once it has created the spaces or jobs or failed to. Before, every upload left its
 * directory behind, and two uploads by one user in the same millisecond shared one.
 *
 * <p>The files are real, under a temporary upload root. The session, the database, the thread
 * pool, the archive extraction and the creation of spaces and jobs are replaced at their
 * boundaries.
 */
public class XmlUploadDirectoryCleanupTest {

	private static final int OWNER = 5;
	private static final int SPACE = 3;
	private static final int STATUS = 11;
	/** The millisecond that every upload in these tests starts in. */
	private static final String DATE = "20261018-10.00.00.000";

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private File root;
	private HashMap<String, Object> form;
	/** The tasks that space XML uploads gave the thread pool. The tests run them. */
	private final List<Runnable> tasks = new ArrayList<>();
	private Pool pool = Pool.QUEUES;
	/** What another upload does after a task that ran in the request's thread, before the request cleans up. */
	private Body betweenTaskAndRequest = () -> { };
	/** The XML files that space or job creation was given, as they were at the time. */
	private final List<String> seen = new ArrayList<>();
	private RuntimeException creationFails;
	private Error creationError;
	private boolean archiveHoldsADirectory;
	/** Whether a space XML upload's files were still there when the upload was marked complete. */
	private Boolean filesLeftAtCompletion;
	/** Whether extracting an archive fails, as it does for a damaged one. */
	private boolean extractionFails;
	/** The error messages that space XML uploads recorded on their status. */
	private final List<String> xmlErrors = new ArrayList<>();
	/** Whether a space XML upload was marked as received: "file upload complete" on its status page. */
	private boolean fileUploadComplete;
	/** Whether a job XML upload gets the real JobUtil: for uploads that never reach job creation. */
	private boolean realJobUtil;

	/** The job XML request validation checks the archive name; the application compiles the patterns at startup. */
	@BeforeClass
	public static void compileValidatorPatterns() {
		Validator.initialize();
	}

	@Before
	public void uploadRoot() throws IOException {
		root = tmp.newFolder("uploads");
	}

	@Test
	public void aSpaceXmlUploadDeletesItsDirectoryWhenItIsDone() throws Exception {
		withBoundaries(() -> {
			uploadSpaceXml(archive("spaces.zip"));
			assertEquals(1, tasks.size());
			runTasks();
		});

		assertEquals(List.of("spaces.xml"), seen);
		assertArrayEquals(new String[0], left(spaceUploads()));
		assertEquals("deleted before the upload is marked complete", Boolean.FALSE, filesLeftAtCompletion);
	}

	@Test
	public void aSpaceXmlUploadThatFailsDeletesItsDirectoryToo() throws Exception {
		creationFails = new IllegalStateException("synthetic failure while creating the spaces");

		withBoundaries(() -> {
			uploadSpaceXml(archive("spaces.zip"));
			runTasks();
		});

		assertEquals(List.of("spaces.xml"), seen);
		assertArrayEquals(new String[0], left(spaceUploads()));
		assertEquals("the upload is still marked complete", Boolean.FALSE, filesLeftAtCompletion);
	}

	@Test
	public void aSpaceXmlUploadThatCannotStartDeletesItsDirectory() throws Exception {
		pool = Pool.REFUSES;

		withBoundaries(() -> uploadSpaceXml(archive("spaces.zip")));

		assertTrue(tasks.isEmpty());
		assertArrayEquals(new String[0], left(spaceUploads()));
	}

	@Test
	public void aSpaceXmlArchiveThatCannotBeSavedLeavesNoDirectory() throws Exception {
		PartWrapper archive = archive("spaces.zip");
		doThrow(new IOException("synthetic: the disk is full")).when(archive).write(any(File.class));

		withBoundaries(() -> uploadSpaceXml(archive));

		assertTrue(tasks.isEmpty());
		assertArrayEquals(new String[0], left(spaceUploads()));
	}

	/** Each upload processes its own XML, and deletes only its own directory. */
	@Test
	public void twoSpaceXmlUploadsInOneMillisecondKeepTheirFilesApart() throws Exception {
		withBoundaries(() -> {
			uploadSpaceXml(archive("first.zip"));
			uploadSpaceXml(archive("second.zip"));
			assertArrayEquals(new String[] {DATE, DATE + "-1"}, left(spaceUploads()));

			tasks.get(0).run();
			assertEquals(List.of("first.xml"), seen);
			assertArrayEquals(new String[] {"second.zip"}, left(new File(spaceUploads(), DATE + "-1")));

			tasks.get(1).run();
		});

		assertEquals(List.of("first.xml", "second.xml"), seen);
		assertArrayEquals(new String[0], left(spaceUploads()));
	}

	/** An upload that gets no directory must not delete one that belongs to another upload. */
	@Test
	public void aSpaceXmlUploadThatGetsNoDirectoryDeletesNoOtherUpload() throws Exception {
		List<File> others = takeEveryName(spaceUploads());

		withBoundaries(() -> uploadSpaceXml(archive("spaces.zip")));

		assertTrue(tasks.isEmpty());
		for (File other : others) {
			assertTrue(other + " is untouched", new File(other, "other.xml").isFile());
		}
	}

	/** When the pool's queue is full, the task runs in the request's thread. */
	@Test
	public void aSpaceXmlTaskThatRunsInTheRequestDeletesItsDirectory() throws Exception {
		pool = Pool.RUNS_IN_THE_REQUEST;

		withBoundaries(() -> uploadSpaceXml(archive("spaces.zip")));

		assertEquals(List.of("spaces.xml"), seen);
		assertArrayEquals(new String[0], left(spaceUploads()));
		assertEquals("deleted before the upload is marked complete", Boolean.FALSE, filesLeftAtCompletion);
	}

	/**
	 * The task ran in the request's thread, deleted its directory and threw an Error into the request. By then
	 * another upload has claimed the same name, so the request must not delete it again.
	 */
	@Test
	public void aSpaceXmlTaskThatThrowsInTheRequestDeletesNoOtherUpload() throws Exception {
		pool = Pool.RUNS_IN_THE_REQUEST;
		creationError = new SyntheticError("synthetic: an Error while creating the spaces");
		List<File> other = new ArrayList<>();
		betweenTaskAndRequest = () -> other.add(claimAsAnotherUpload(spaceUploads(), "other.xml"));

		assertThrows(SyntheticError.class, () -> withBoundaries(() -> uploadSpaceXml(archive("spaces.zip"))));

		assertEquals("the other upload got the name this one released", List.of(new File(spaceUploads(), DATE)), other);
		assertTrue("the other upload's file is untouched", new File(other.get(0), "other.xml").isFile());
	}

	/**
	 * The pool queued the task and then failed, so the request deleted the directory. The task runs later, when
	 * another upload has the name, and must leave that upload alone, though its archive has the same name.
	 */
	@Test
	public void aSpaceXmlTaskQueuedByAFailedHandOffTouchesNoOtherUpload() throws Exception {
		pool = Pool.QUEUES_THEN_FAILS;

		assertThrows(SyntheticError.class, () -> withBoundaries(() -> uploadSpaceXml(archive("spaces.zip"))));
		assertArrayEquals("the request deleted the directory", new String[0], left(spaceUploads()));
		File other = claimAsAnotherUpload(spaceUploads(), "spaces.zip");

		withBoundaries(this::runTasks);

		assertTrue("no spaces are created from the other upload's archive", seen.isEmpty());
		assertTrue("the other upload's archive is untouched", new File(other, "spaces.zip").isFile());
	}

	/** A damaged archive is reported on the upload's status, instead of passing for one that holds no XML. */
	@Test
	public void aSpaceXmlArchiveThatCannotBeExtractedIsReported() throws Exception {
		extractionFails = true;

		withBoundaries(() -> {
			uploadSpaceXml(archive("spaces.zip"));
			runTasks();
		});

		assertEquals(List.of(ArchiveUtil.NOT_EXTRACTED), xmlErrors);
		assertTrue("the file was received, as a benchmark upload marks it before it extracts", fileUploadComplete);
		assertTrue("no spaces are created", seen.isEmpty());
		assertArrayEquals(new String[0], left(spaceUploads()));
		assertEquals("the upload is marked complete, with no file left", Boolean.FALSE, filesLeftAtCompletion);
	}

	@Test
	public void aJobXmlUploadDeletesItsDirectoryWhenItIsDone() throws Exception {
		HttpServletResponse response = postJobXml(archive("jobs.zip"));

		verify(response).sendRedirect(endsWith("secure/explore/spaces.jsp"));
		assertEquals(List.of("jobs.xml"), seen);
		assertArrayEquals(new String[0], left(jobUploads()));
	}

	@Test
	public void aJobXmlUploadThatFailsDeletesItsDirectoryToo() throws Exception {
		creationFails = new IllegalStateException("synthetic failure while creating the jobs");

		HttpServletResponse response = postJobXml(archive("jobs.zip"));

		verify(response).sendError(eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR), anyString());
		assertEquals(List.of("jobs.xml"), seen);
		assertArrayEquals(new String[0], left(jobUploads()));
	}

	/** An archive that holds a directory is refused, and its files are deleted all the same. */
	@Test
	public void aRefusedJobXmlArchiveLeavesNoDirectory() throws Exception {
		archiveHoldsADirectory = true;

		HttpServletResponse response = postJobXml(archive("jobs.zip"));

		verify(response, never()).sendRedirect(anyString());
		assertTrue("no job is created", seen.isEmpty());
		assertArrayEquals(new String[0], left(jobUploads()));
	}

	/** A damaged archive is refused with the reason, not with an empty message. */
	@Test
	public void aJobXmlArchiveThatCannotBeExtractedIsRefusedWithTheReason() throws Exception {
		extractionFails = true;
		realJobUtil = true;

		HttpServletResponse response = postJobXml(archive("jobs.zip"));

		verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST,
				"Failed to upload Job XML:\n" + ArchiveUtil.NOT_EXTRACTED);
		assertTrue("no job is created", seen.isEmpty());
		assertArrayEquals(new String[0], left(jobUploads()));
	}

	@Test
	public void aJobXmlUploadThatGetsNoDirectoryDeletesNoOtherUpload() throws Exception {
		List<File> others = takeEveryName(jobUploads());

		HttpServletResponse response = postJobXml(archive("jobs.zip"));

		verify(response).sendError(eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR), anyString());
		assertTrue("no job is created", seen.isEmpty());
		for (File other : others) {
			assertTrue(other + " is untouched", new File(other, "other.xml").isFile());
		}
	}

	private interface Body {
		void run() throws Exception;
	}

	/** How the thread pool takes a space XML task. */
	private enum Pool {
		/** Queues it, for the test to run. */
		QUEUES,
		/** Runs it at once in the request's thread, as CallerRunsPolicy does when the queue is full. */
		RUNS_IN_THE_REQUEST,
		/** Refuses it with an exception. */
		REFUSES,
		/** Queues it, then fails with an Error, as starting a worker thread can. */
		QUEUES_THEN_FAILS
	}

	/** An Error, which the servlets' catch blocks do not catch. */
	private static final class SyntheticError extends Error {
		SyntheticError(String message) {
			super(message);
		}
	}

	/** Runs body with the session, database, error log, thread pool and archive extraction replaced. */
	private void withBoundaries(Body body) throws Exception {
		try (MockedStatic<R> r = mockStatic(R.class, invocation ->
				invocation.getMethod().getName().equals("getBatchSpaceXMLDir")
						? root.getAbsolutePath() : invocation.callRealMethod());
		     MockedStatic<Util> util = mockStatic(Util.class, invocation -> {
			     switch (invocation.getMethod().getName()) {
			     case "pathDate":
				     return DATE;
			     case "parseMultipartRequest":
				     return form;
			     case "threadPoolExecute":
				     return handOff(invocation.getArgument(0));
			     default:
				     return invocation.callRealMethod();
			     }
		     });
		     MockedStatic<ArchiveUtil> archives = mockStatic(ArchiveUtil.class);
		     MockedStatic<Uploads> uploads = mockStatic(Uploads.class);
		     MockedStatic<SessionUtil> session = mockStatic(SessionUtil.class);
		     MockedStatic<Permissions> permissions = mockStatic(Permissions.class);
		     MockedStatic<ErrorLogs> errorLogs = mockStatic(ErrorLogs.class)) {
			archives.when(() -> ArchiveUtil.extractArchive(anyString())).thenAnswer(invocation -> {
				if (extractionFails) {
					return false;
				}
				extract(new File((String) invocation.getArgument(0)));
				return true;
			});
			uploads.when(() -> Uploads.setXMLErrorMessage(eq(STATUS), anyString())).thenAnswer(invocation -> {
				xmlErrors.add(invocation.getArgument(1));
				return true;
			});
			uploads.when(() -> Uploads.XMLFileUploadComplete(STATUS)).thenAnswer(invocation -> {
				fileUploadComplete = true;
				return true;
			});
			uploads.when(() -> Uploads.XMLEverythingComplete(STATUS)).thenAnswer(invocation -> {
				filesLeftAtCompletion = left(spaceUploads()).length > 0;
				return true;
			});
			session.when(() -> SessionUtil.getUserId(any(HttpServletRequest.class))).thenReturn(OWNER);
			permissions.when(() -> Permissions.get(OWNER, SPACE)).thenReturn(new Permission(true));
			body.run();
		}
	}

	private void uploadSpaceXml(PartWrapper archive) throws Exception {
		form = new HashMap<>();
		form.put("f", archive);
		BatchUtil batchUtil = mock(BatchUtil.class);
		when(batchUtil.createSpacesFromFile(any(File.class), eq(OWNER), eq(SPACE), eq(STATUS)))
				.thenAnswer(invocation -> create(invocation.getArgument(0)));
		new UploadSpaceXML().handleXMLFile(OWNER, SPACE, form, batchUtil, STATUS);
	}

	private HttpServletResponse postJobXml(PartWrapper archive) throws Exception {
		form = new HashMap<>();
		form.put("f", archive);
		form.put(R.SPACE, String.valueOf(SPACE));
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getContentType()).thenReturn("multipart/form-data; boundary=synthetic");
		HttpServletResponse response = mock(HttpServletResponse.class);
		withBoundaries(() -> {
			if (realJobUtil) {
				new UploadJobXML().doPost(request, response);
				return;
			}
			try (MockedConstruction<JobUtil> jobUtils = mockConstruction(JobUtil.class, (jobUtil, context) -> {
				when(jobUtil.createJobsFromFile(any(File.class), eq(OWNER), eq(SPACE), any(), any()))
						.thenAnswer(invocation -> create(invocation.getArgument(0)));
				when(jobUtil.getJobCreationSuccess()).thenReturn(true);
			})) {
				new UploadJobXML().doPost(request, response);
			}
		});
		return response;
	}

	/** What the thread pool does with a space XML task, as pool says. */
	private Object handOff(Runnable task) throws Exception {
		switch (pool) {
		case REFUSES:
			throw new RejectedExecutionException("synthetic: the pool refuses the task");
		case RUNS_IN_THE_REQUEST:
			try {
				task.run();
			} finally {
				betweenTaskAndRequest.run();
			}
			return null;
		case QUEUES_THEN_FAILS:
			tasks.add(task);
			throw new SyntheticError("synthetic: no worker thread could be started");
		default:
			tasks.add(task);
			return null;
		}
	}

	/** What creating spaces or jobs from xml does here: note the file, then fail or succeed. */
	private List<Integer> create(File xml) {
		seen.add(xml.getName() + (xml.isFile() ? "" : " (missing)"));
		if (creationError != null) {
			throw creationError;
		}
		if (creationFails != null) {
			throw creationFails;
		}
		return List.of(1);
	}

	/** What extracting an archive leaves beside it: its XML file, without the archive. */
	private void extract(File archive) throws IOException {
		Files.delete(archive.toPath());
		String name = archive.getName();
		write(new File(archive.getParentFile(), name.substring(0, name.lastIndexOf('.')) + ".xml"), "<synthetic/>\n");
		if (archiveHoldsADirectory) {
			Files.createDirectory(new File(archive.getParentFile(), "nested").toPath());
		}
	}

	private void runTasks() {
		for (Runnable task : List.copyOf(tasks)) {
			task.run();
		}
	}

	private File spaceUploads() {
		return new File(root, OWNER + "/TEMP_XML_FOLDER_");
	}

	private File jobUploads() {
		return new File(root, "Job" + OWNER + "/TEMP_JOB_XML_FOLDER_");
	}

	/** Gives every name that a claim can try to another upload, so that the claim fails. */
	private static List<File> takeEveryName(File uploads) throws IOException {
		List<File> others = new ArrayList<>();
		for (int suffix = 0; suffix <= Util.MAX_DIRECTORY_SUFFIX; suffix++) {
			File other = new File(uploads, suffix == 0 ? DATE : DATE + "-" + suffix);
			write(new File(other, "other.xml"), "<other/>\n");
			others.add(other);
		}
		return others;
	}

	/** Claims the name that an upload in DATE gets, as another upload would, and puts one file in it. */
	private static File claimAsAnotherUpload(File uploads, String file) throws IOException {
		File dir = Util.claimNewDirectory(new File(uploads, DATE));
		write(new File(dir, file), "theirs\n");
		return dir;
	}

	/** An uploaded archive. Saving it, like the real one, needs its directory to exist. */
	private static PartWrapper archive(String name) throws IOException {
		PartWrapper archive = mock(PartWrapper.class);
		when(archive.getName()).thenReturn(name);
		doAnswer(invocation -> {
			Files.writeString(((File) invocation.getArgument(0)).toPath(), "synthetic archive");
			return null;
		}).when(archive).write(any(File.class));
		return archive;
	}

	/** The names in dir, sorted; none if dir does not exist. */
	private static String[] left(File dir) {
		String[] names = dir.list();
		if (names == null) {
			return new String[0];
		}
		Arrays.sort(names);
		return names;
	}

	private static void write(File file, String content) throws IOException {
		Files.createDirectories(file.getParentFile().toPath());
		Files.writeString(file.toPath(), content);
	}
}
