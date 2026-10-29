package org.starexec.servlets;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.constants.R;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Permissions;
import org.starexec.data.database.Pipelines;
import org.starexec.data.database.Spaces;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.Job;
import org.starexec.data.to.Permission;
import org.starexec.data.to.Space;
import org.starexec.data.to.User;
import org.starexec.data.to.enums.BenchmarkingFramework;
import org.starexec.util.SessionUtil;
import org.starexec.util.XMLUtil;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The job CSV, space XML and job XML downloads build their file before zipping it. That file must live in a
 * private temporary directory, one per export, removed once the archive is sent: never in the web application's
 * own document root, never in the JVM's working directory, where same-named exports would share one file, and
 * not in the group-writable sandbox the solver users share. A stored name that is not a plain file name must not
 * move the file anywhere else.
 */
public class DownloadExportLocationTest {
	private static final int USER = 7;
	private static final Set<String> SCHEMAS = Set.of(
			new File(R.JOB_SCHEMA_LOCATION).getPath(),
			new File(R.SPACE_XML_SCHEMA_RELATIVE_LOC.replaceFirst("^/", "")).getPath());

	@TempDir
	Path tmp;

	private String originalRoot;
	private File webRoot;

	@BeforeEach
	public void useAnEmptyWebRoot() throws Exception {
		originalRoot = R.STAREXEC_ROOT;
		webRoot = Files.createDirectory(tmp.resolve("webapp")).toFile();
		R.STAREXEC_ROOT = webRoot.getAbsolutePath();
		for (String schema : SCHEMAS) { // the two schemas the XML downloads put next to the export
			File f = new File(webRoot, schema);
			Files.createDirectories(f.getParentFile().toPath());
			Files.writeString(f.toPath(), "<schema/>");
		}
	}

	@AfterEach
	public void restoreTheWebRoot() {
		R.STAREXEC_ROOT = originalRoot;
	}

	@Test
	public void jobCsvIsBuiltInItsOwnDirectoryAndNotLeftBehind() throws Exception {
		Job job = new Job();
		job.setId(41);
		job.setName("private_job");
		job.setPrimarySpace(5);

		Result result;
		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
				 MockedStatic<Permissions> permissions = Mockito.mockStatic(Permissions.class);
				 MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class)) {
			HttpServletRequest request = signedIn(session, Map.of(
					"type", R.JOB, "id", "41", "returnids", "true", "getcompleted", "false"));
			permissions.when(() -> Permissions.canUserSeeJob(41, USER)).thenReturn(new ValidatorStatusCode(true));
			jobs.when(() -> Jobs.get(41)).thenReturn(job);
			result = download(request);
		}

		assertTrue(result.succeeded());
		assertEquals(Set.of("Job41/Job41_info.csv"), result.entries.keySet());
		assertTrue(result.entries.get("Job41/Job41_info.csv").startsWith("pair id,benchmark,benchmark id,solver,"));
		assertNothingLeftBehind(result);
	}

	@Test
	public void spaceXmlIsBuiltInItsOwnDirectoryAndNotLeftBehind() throws Exception {
		Result result = downloadSpaceXml("Axioms");

		assertTrue(result.succeeded());
		assertEquals(Set.of("Axioms_XML/Axioms.xml", "Axioms_XML/batchSpaceSchema.xsd"), result.entries.keySet());
		assertTrue(result.entries.get("Axioms_XML/Axioms.xml").contains("name=\"Axioms\""));
		assertNothingLeftBehind(result);
	}

	@Test
	public void jobXmlIsBuiltInItsOwnDirectoryAndNotLeftBehind() throws Exception {
		String name = "export_location_" + System.nanoTime();
		Result result = downloadJobXml(name);

		assertTrue(result.succeeded());
		assertEquals(Set.of("Job42_XML/" + name + ".xml", "Job42_XML/batchJobSchema.xsd"), result.entries.keySet());
		assertTrue(result.entries.get("Job42_XML/" + name + ".xml").contains("name=\"" + name + "\""));
		assertNothingLeftBehind(result);
	}

	@Test
	public void aSpaceNameWithAPathIsRefusedAndWritesNothingOutside() throws Exception {
		String escaped = "escaped_space_" + System.nanoTime();
		Result result = downloadSpaceXml("../" + escaped);

		assertFalse(result.succeeded(), "the download fails instead");
		assertEquals(1, result.exportDirectories.size());
		assertFalse(new File(result.exportDirectories.get(0).getParentFile(), escaped + ".xml").exists());
		assertNothingLeftBehind(result);
	}

	@Test
	public void aJobNameWithAPathIsRefusedAndWritesNothingOutside() throws Exception {
		String escaped = "escaped_job_" + System.nanoTime();
		Result result = downloadJobXml("../" + escaped);

		assertFalse(result.succeeded(), "the download fails instead");
		assertEquals(1, result.exportDirectories.size());
		assertFalse(new File(result.exportDirectories.get(0).getParentFile(), escaped + ".xml").exists());
		assertNothingLeftBehind(result);
	}

	@Test
	public void aRenameBetweenTheTwoReadsCannotNameAnEntryOutsideTheArchive() throws Exception {
		Result result = downloadSpaceXml("../outside", "Safe");

		assertTrue(result.succeeded());
		assertEquals(Set.of("Safe_XML/Safe.xml", "Safe_XML/batchSpaceSchema.xsd"), result.entries.keySet());
		assertNothingLeftBehind(result);
	}

	@Test
	public void anExportDirectoryIsPrivateToTheServerAndOutsideTheWebRoot() throws Exception {
		File dir = Download.newExportDirectory();
		File other = Download.newExportDirectory();
		try {
			assertEquals(new File(System.getProperty("java.io.tmpdir")).getCanonicalFile(), dir.getParentFile().getCanonicalFile());
			assertEquals("rwx------",
					PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.toPath())), "only the server's own user may enter it");
			assertFalse(dir.getCanonicalFile().toPath().startsWith(webRoot.getCanonicalFile().toPath()),
					"outside the web root, compared element by element");
			assertNotEquals(dir, other, "each export gets its own directory");
		} finally {
			FileUtils.deleteQuietly(dir);
			FileUtils.deleteQuietly(other);
		}
	}

	@Test
	public void fileInAcceptsPlainNamesAndRefusesPaths() throws Exception {
		File dir = Files.createDirectory(tmp.resolve("export")).toFile();
		assertEquals(new File(dir, "Axioms.xml"), XMLUtil.fileIn(dir, "Axioms.xml"));
		assertEquals(new File(dir, "..xml"), XMLUtil.fileIn(dir, "..xml"));
		for (String bad : List.of("../x.xml", "a/b.xml", "/etc/x.xml", "..\\x.xml", "a\\b.xml", "..", ".")) {
			assertThrows(IOException.class, () -> XMLUtil.fileIn(dir, bad), bad);
		}
	}

	private Result downloadSpaceXml(String spaceName) throws Exception {
		return downloadSpaceXml(spaceName, spaceName);
	}

	/** The servlet reads the space twice; a rename between the reads gives each read its own name. */
	private Result downloadSpaceXml(String firstRead, String detailsRead) throws Exception {
		Space space = space(firstRead);
		Space details = space(detailsRead);
		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
				 MockedStatic<Permissions> permissions = Mockito.mockStatic(Permissions.class);
				 MockedStatic<Spaces> spaces = Mockito.mockStatic(Spaces.class)) {
			HttpServletRequest request = signedIn(session, Map.of(
					"type", R.SPACE_XML, "id", "110", "includeattrs", "true"));
			permissions.when(() -> Permissions.canUserSeeSpace(110, USER)).thenReturn(true);
			spaces.when(() -> Spaces.get(110)).thenReturn(space);
			spaces.when(() -> Spaces.getDetails(110, USER)).thenReturn(details);
			return download(request);
		}
	}

	private static Space space(String name) {
		Space space = new Space();
		space.setId(110);
		space.setName(name);
		space.setDescription("private space");
		space.setPermission(new Permission(true));
		return space;
	}

	private Result downloadJobXml(String jobName) throws Exception {
		Job job = new Job();
		job.setId(42);
		job.setName(jobName);
		job.setDescription("private job");
		job.setBenchmarkingFramework(BenchmarkingFramework.RUNSOLVER);
		File stray = new File(System.getProperty("user.dir"), jobName + ".xml");
		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
				 MockedStatic<Permissions> permissions = Mockito.mockStatic(Permissions.class);
				 MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class);
				 MockedStatic<Pipelines> pipelines = Mockito.mockStatic(Pipelines.class)) {
			HttpServletRequest request = signedIn(session, Map.of("type", R.JOB_XML, "id", "42"));
			permissions.when(() -> Permissions.canUserSeeJob(42, USER)).thenReturn(new ValidatorStatusCode(true));
			jobs.when(() -> Jobs.get(42)).thenReturn(job);
			return download(request);
		} finally {
			boolean leaked = stray.exists();
			FileUtils.deleteQuietly(stray); // a failing run must not leave the file in the checkout
			assertFalse(leaked, "the job XML must not be written into the working directory");
		}
	}

	private void assertNothingLeftBehind(Result result) {
		assertEquals(SCHEMAS, webRootFiles(), "nothing may be left in the web root");
		assertEquals(1, result.exportDirectories.size(), "one directory per export");
		for (File dir : result.exportDirectories) {
			assertFalse(dir.exists(), "the export's directory is removed: " + dir);
		}
	}

	private static HttpServletRequest signedIn(MockedStatic<SessionUtil> session, Map<String, String> params) {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		params.forEach((k, v) -> Mockito.when(request.getParameter(k)).thenReturn(v));
		User user = new User();
		user.setId(USER);
		session.when(() -> SessionUtil.getUser(request)).thenReturn(user);
		session.when(() -> SessionUtil.getUserId(request)).thenReturn(USER);
		return request;
	}

	/** Runs the servlet; records each directory an export was built in, and the archive it streamed. */
	private static Result download(HttpServletRequest request) throws Exception {
		HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
		Captured out = new Captured();
		Mockito.when(response.getOutputStream()).thenReturn(out);
		List<File> created = new ArrayList<>();
		// Every static method of Download runs for real; the ones newExportDirectory returns are recorded. A default
		// answer rather than when(...) stubbing, which would itself call the real method once and leak a directory.
		try (MockedStatic<Download> ignored = Mockito.mockStatic(Download.class, invocation -> {
			Object value = invocation.callRealMethod();
			if (invocation.getMethod().getName().equals("newExportDirectory")) {
				created.add((File) value);
			}
			return value;
		})) {
			new Download().doGet(request, response);
		}
		Map<String, String> entries = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(out.bytes.toByteArray()))) {
			for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
				entries.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
		}
		boolean failed = Mockito.mockingDetails(response).getInvocations().stream()
				.anyMatch(i -> i.getMethod().getName().equals("sendError"));
		return new Result(entries, created, !failed);
	}

	private Set<String> webRootFiles() {
		Set<String> files = new TreeSet<>();
		for (File f : FileUtils.listFiles(webRoot, null, true)) {
			files.add(webRoot.toPath().relativize(f.toPath()).toString());
		}
		return files;
	}

	private static final class Result {
		final Map<String, String> entries;
		final List<File> exportDirectories;
		private final boolean ok;

		Result(Map<String, String> entries, List<File> exportDirectories, boolean ok) {
			this.entries = entries;
			this.exportDirectories = exportDirectories;
			this.ok = ok;
		}

		boolean succeeded() {
			return ok;
		}
	}

	private static final class Captured extends ServletOutputStream {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		@Override
		public void write(int b) {
			bytes.write(b);
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setWriteListener(WriteListener listener) {
		}
	}
}
