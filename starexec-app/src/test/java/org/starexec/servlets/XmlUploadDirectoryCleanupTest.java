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
	private boolean poolRejects;
	/** The XML files that space or job creation was given, as they were at the time. */
	private final List<String> seen = new ArrayList<>();
	private RuntimeException creationFails;
	private boolean archiveHoldsADirectory;
	/** Whether a space XML upload's files were still there when the upload was marked complete. */
	private Boolean filesLeftAtCompletion;

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
		poolRejects = true;

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
				     if (poolRejects) {
					     throw new RejectedExecutionException("synthetic: the pool is shut down");
				     }
				     tasks.add(invocation.getArgument(0));
				     return null;
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
				extract(new File((String) invocation.getArgument(0)));
				return null;
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

	/** What creating spaces or jobs from xml does here: note the file, then fail or succeed. */
	private List<Integer> create(File xml) {
		seen.add(xml.getName() + (xml.isFile() ? "" : " (missing)"));
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
