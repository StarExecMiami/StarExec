package org.starexec.test.junit.app;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.app.RESTServices;
import org.starexec.constants.R;
import org.starexec.data.database.UploadSessions;
import org.starexec.data.security.UploadSessionSecurity;
import org.starexec.data.security.UploadSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.UploadJob;
import org.starexec.data.to.UploadSession;
import org.starexec.util.SessionUtil;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RESTServicesUploadSessionTests {

	@Test
	public void createUploadSessionRejectsOversizedRequestBody() throws Exception {
		RESTServices services = new RESTServices();
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);

		byte[] oversized = new byte[(64 * 1024) + 1];
		for (int i = 0; i < oversized.length; i++) {
			oversized[i] = 'a';
		}

		ByteArrayInputStream bais = new ByteArrayInputStream(oversized);
		ServletInputStream sis = new ServletInputStream() {
			@Override
			public int read() {
				return bais.read();
			}

			@Override
			public boolean isFinished() {
				return bais.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener readListener) {
				// Not used in tests.
			}
		};

		Mockito.when(request.getInputStream()).thenReturn(sis);

		try (MockedStatic<UploadSecurity> uploadSecurityMock = Mockito.mockStatic(UploadSecurity.class)) {
			uploadSecurityMock.when(UploadSecurity::uploadsFrozen).thenReturn(false);
			String response = services.createUploadSession(request);
			assertTrue(response.contains("\"success\":false"));
			assertTrue(response.contains("Invalid request body"));
		}
	}

	@Test
	public void uploadSessionChunkAcceptsGeneratedTimestampDirectory() throws Exception {
		RESTServices services = new RESTServices();
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		Path sessionDir = benchmarkRoot().resolve(
			"41/20260618-13.22.18.964/upload-session-generated"
		);
		Files.createDirectories(sessionDir);
		UploadSession session = new UploadSession();
		session.setId(41L);
		session.setUserId(41);
		session.setFileName("AllProblems.tgz");
		session.setStagingPath(sessionDir.resolve("AllProblems.tgz.part").toString());
		session.setTotalBytes(4L);
		session.setChunkSize(4);
		session.setTotalChunks(1);
		session.setStatus("UPLOADING");

		Mockito.when(request.getContentLengthLong()).thenReturn(4L);
		Mockito.when(request.getInputStream()).thenReturn(servletInputStream("data".getBytes(StandardCharsets.UTF_8)));

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<UploadSessionSecurity> security = Mockito.mockStatic(UploadSessionSecurity.class);
			 MockedStatic<UploadSessions> uploadSessions = Mockito.mockStatic(UploadSessions.class)) {
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(41);
			security.when(() -> UploadSessionSecurity.canUserManageUploadSession(41L, 41)).thenReturn(true);
			uploadSessions.when(() -> UploadSessions.getSession(41L)).thenReturn(Optional.of(session));
			uploadSessions.when(() -> UploadSessions.isChunkRecorded(41L, 0)).thenReturn(false);
			uploadSessions.when(() -> UploadSessions.recordChunkIfAbsent(41L, 0, 4)).thenReturn(true);

			String response = services.uploadSessionChunk(41L, 0, request);

			assertTrue(response.contains("\"success\":true"));
			assertFalse(response.contains("Upload session path is invalid"));
			assertTrue(Files.exists(sessionDir.resolve("AllProblems.tgz.part.chunks/chunk_0.bin")));
		} finally {
			org.apache.commons.io.FileUtils.deleteQuietly(sessionDir.toFile());
		}
	}

	@Test
	public void finalizeUploadSessionReturnsInProgressForConcurrentFinalizer() {
		RESTServices services = new RESTServices();
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		UploadSession session = new UploadSession();
		session.setId(12L);
		session.setUserId(7);
		session.setStatus("FINALIZING");
		session.setBytesReceived(10L);
		session.setTotalBytes(10L);
		session.setNextChunkIndex(1);
		session.setTotalChunks(1);

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<UploadSessionSecurity> security = Mockito.mockStatic(UploadSessionSecurity.class);
			 MockedStatic<UploadSessions> uploadSessions = Mockito.mockStatic(UploadSessions.class)) {
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(7);
			security.when(() -> UploadSessionSecurity.canUserManageUploadSession(12L, 7)).thenReturn(true);
			uploadSessions.when(() -> UploadSessions.getSession(12L)).thenReturn(Optional.of(session));
			uploadSessions.when(() -> UploadSessions.markFinalizing(12L)).thenReturn(false);

			String response = services.finalizeUploadSession(12L, request);

			assertTrue(response.contains("\"success\":false"));
			assertTrue(response.contains("Upload finalization already in progress"));
			uploadSessions.verify(
				() -> UploadSessions.failSession(Mockito.eq(12L), Mockito.anyString()),
				Mockito.never()
			);
		}
	}

	@Test
	public void finalizeUploadSessionRejectsUnauthorizedUser() {
		RESTServices services = new RESTServices();
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<UploadSessionSecurity> security = Mockito.mockStatic(UploadSessionSecurity.class);
			 MockedStatic<UploadSessions> uploadSessions = Mockito.mockStatic(UploadSessions.class)) {
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(33);
			security.when(() -> UploadSessionSecurity.canUserManageUploadSession(33L, 33)).thenReturn(false);

			String response = services.finalizeUploadSession(33L, request);

			assertTrue(response.contains("\"success\":false"));
			assertTrue(response.contains("You do not have permission to finalize this upload session"));
			uploadSessions.verify(() -> UploadSessions.getSession(Mockito.anyLong()), Mockito.never());
		}
	}

	@Test
	public void finalizeUploadSessionRejectsMissingSession() {
		RESTServices services = new RESTServices();
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<UploadSessionSecurity> security = Mockito.mockStatic(UploadSessionSecurity.class);
			 MockedStatic<UploadSessions> uploadSessions = Mockito.mockStatic(UploadSessions.class)) {
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(34);
			security.when(() -> UploadSessionSecurity.canUserManageUploadSession(34L, 34)).thenReturn(true);
			uploadSessions.when(() -> UploadSessions.getSession(34L)).thenReturn(Optional.empty());

			String response = services.finalizeUploadSession(34L, request);

			assertTrue(response.contains("\"success\":false"));
			assertTrue(response.contains("Upload session not found"));
			uploadSessions.verify(() -> UploadSessions.markFinalizing(Mockito.anyLong()), Mockito.never());
		}
	}

	@Test
	public void finalizeUploadSessionReturnsExistingJobForAlreadyCompleteSession() {
		RESTServices services = new RESTServices();
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		UploadSession complete = new UploadSession();
		complete.setId(35L);
		complete.setUserId(35);
		complete.setStatus("COMPLETE");
		complete.setJobId(77L);

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<UploadSessionSecurity> security = Mockito.mockStatic(UploadSessionSecurity.class);
			 MockedStatic<UploadSessions> uploadSessions = Mockito.mockStatic(UploadSessions.class)) {
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(35);
			security.when(() -> UploadSessionSecurity.canUserManageUploadSession(35L, 35)).thenReturn(true);
			uploadSessions.when(() -> UploadSessions.getSession(35L)).thenReturn(Optional.of(complete));

			String response = services.finalizeUploadSession(35L, request);

			assertTrue(response.contains("\"success\":true"));
			assertTrue(response.contains("\"jobId\":77"));
			uploadSessions.verify(() -> UploadSessions.markFinalizing(Mockito.anyLong()), Mockito.never());
		}
	}

	@Test
	public void finalizeUploadSessionRejectsIncompleteUpload() {
		RESTServices services = new RESTServices();
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		UploadSession incomplete = new UploadSession();
		incomplete.setId(36L);
		incomplete.setUserId(36);
		incomplete.setStatus("READY");
		incomplete.setBytesReceived(3L);
		incomplete.setTotalBytes(4L);
		incomplete.setNextChunkIndex(0);
		incomplete.setTotalChunks(1);

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<UploadSessionSecurity> security = Mockito.mockStatic(UploadSessionSecurity.class);
			 MockedStatic<UploadSessions> uploadSessions = Mockito.mockStatic(UploadSessions.class)) {
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(36);
			security.when(() -> UploadSessionSecurity.canUserManageUploadSession(36L, 36)).thenReturn(true);
			uploadSessions.when(() -> UploadSessions.getSession(36L)).thenReturn(Optional.of(incomplete));

			String response = services.finalizeUploadSession(36L, request);

			assertTrue(response.contains("\"success\":false"));
			assertTrue(response.contains("Upload is incomplete and cannot be finalized"));
			uploadSessions.verify(() -> UploadSessions.markFinalizing(Mockito.anyLong()), Mockito.never());
		}
	}

	@Test
	public void finalizeUploadSessionAssemblesArchiveQueuesJobAndCleansChunks() throws Exception {
		RESTServices services = new RESTServices();
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		Path tempDir = benchmarkRoot().resolve("21/20260705/upload-session-happy");
		Path chunksDir = tempDir.resolve("archive.tgz.part.chunks");
		Files.createDirectories(chunksDir);
		Files.write(chunksDir.resolve("chunk_0.bin"), "data".getBytes(StandardCharsets.UTF_8));
		UploadSession session = readySession(21L, 21, tempDir, 4L);

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<UploadSessionSecurity> security = Mockito.mockStatic(UploadSessionSecurity.class);
			 MockedStatic<UploadSessions> uploadSessions = Mockito.mockStatic(UploadSessions.class, Mockito.CALLS_REAL_METHODS)) {
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(21);
			security.when(() -> UploadSessionSecurity.canUserManageUploadSession(21L, 21)).thenReturn(true);
			uploadSessions.when(() -> UploadSessions.getSession(21L)).thenReturn(Optional.of(session));
			uploadSessions.when(() -> UploadSessions.markFinalizing(21L)).thenReturn(true);
			uploadSessions.when(() -> UploadSessions.finalizeSessionWithUploadJob(
				Mockito.eq(21L),
				Mockito.anyString(),
				Mockito.<UploadJob.UploadJobRequest>any()
			)).thenReturn(Optional.of(55L));

			String response = services.finalizeUploadSession(21L, request);

			assertTrue(response.contains("\"success\":true"));
			assertTrue(response.contains("\"jobId\":55"));
			assertFalse(Files.exists(chunksDir));
			assertTrue(Files.exists(tempDir.resolve("archive.tgz")));
		} finally {
			org.apache.commons.io.FileUtils.deleteQuietly(tempDir.toFile());
		}
	}

	@Test
	public void finalizeUploadSessionReturnsExistingJobForConcurrentCompletedFinalizer() {
		RESTServices services = new RESTServices();
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		UploadSession initial = new UploadSession();
		initial.setId(22L);
		initial.setUserId(22);
		initial.setStatus("READY");
		initial.setBytesReceived(4L);
		initial.setTotalBytes(4L);
		initial.setNextChunkIndex(1);
		initial.setTotalChunks(1);
		UploadSession complete = new UploadSession();
		complete.setId(22L);
		complete.setUserId(22);
		complete.setStatus("COMPLETE");
		complete.setJobId(66L);

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<UploadSessionSecurity> security = Mockito.mockStatic(UploadSessionSecurity.class);
			 MockedStatic<UploadSessions> uploadSessions = Mockito.mockStatic(UploadSessions.class)) {
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(22);
			security.when(() -> UploadSessionSecurity.canUserManageUploadSession(22L, 22)).thenReturn(true);
			uploadSessions.when(() -> UploadSessions.getSession(22L))
				.thenReturn(Optional.of(initial), Optional.of(complete));
			uploadSessions.when(() -> UploadSessions.markFinalizing(22L)).thenReturn(false);

			String response = services.finalizeUploadSession(22L, request);

			assertTrue(response.contains("\"success\":true"));
			assertTrue(response.contains("\"jobId\":66"));
			uploadSessions.verify(
				() -> UploadSessions.finalizeSessionWithUploadJob(
					Mockito.eq(22L),
					Mockito.anyString(),
					Mockito.<UploadJob.UploadJobRequest>any()
				),
				Mockito.never()
			);
		}
	}

	@Test
	public void finalizeUploadSessionCleansArtifactsWhenSchedulingFails() throws Exception {
		RESTServices services = new RESTServices();
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		Path tempDir = benchmarkRoot().resolve("23/20260705/upload-session-schedule-fail");
		Path chunksDir = tempDir.resolve("archive.tgz.part.chunks");
		Path finalArchive = tempDir.resolve("archive.tgz");
		Files.createDirectories(chunksDir);
		Files.write(chunksDir.resolve("chunk_0.bin"), "data".getBytes(StandardCharsets.UTF_8));
		UploadSession session = readySession(23L, 23, tempDir, 4L);

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<UploadSessionSecurity> security = Mockito.mockStatic(UploadSessionSecurity.class);
			 MockedStatic<UploadSessions> uploadSessions = Mockito.mockStatic(UploadSessions.class, Mockito.CALLS_REAL_METHODS)) {
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(23);
			security.when(() -> UploadSessionSecurity.canUserManageUploadSession(23L, 23)).thenReturn(true);
			uploadSessions.when(() -> UploadSessions.getSession(23L)).thenReturn(Optional.of(session));
			uploadSessions.when(() -> UploadSessions.markFinalizing(23L)).thenReturn(true);
			uploadSessions.when(() -> UploadSessions.finalizeSessionWithUploadJob(
				Mockito.eq(23L),
				Mockito.anyString(),
				Mockito.<UploadJob.UploadJobRequest>any()
			)).thenReturn(Optional.empty());
			uploadSessions.when(() -> UploadSessions.failSession(Mockito.eq(23L), Mockito.anyString())).thenReturn(true);

			String response = services.finalizeUploadSession(23L, request);

			assertTrue(response.contains("\"success\":false"));
			assertTrue(response.contains("Failed to schedule upload processing"));
			assertFalse(Files.exists(chunksDir));
			assertFalse(Files.exists(finalArchive));
			uploadSessions.verify(() -> UploadSessions.failSession(23L, "Failed to schedule upload processing"));
		} finally {
			org.apache.commons.io.FileUtils.deleteQuietly(tempDir.toFile());
		}
	}

	@Test
	public void moveWithAtomicFallbackNoReplaceDoesNotOverwriteTarget() throws Exception {
		Path tempDir = Files.createTempDirectory("rest-services-upload-session-tests");
		Path source = tempDir.resolve("source.bin");
		Path target = tempDir.resolve("target.bin");
		Files.write(source, "source-data".getBytes(StandardCharsets.UTF_8));
		Files.write(target, "target-data".getBytes(StandardCharsets.UTF_8));

		Method method = RESTServices.class.getDeclaredMethod(
				"moveWithAtomicFallbackNoReplace",
				java.nio.file.Path.class,
				java.nio.file.Path.class);
		method.setAccessible(true);

		boolean threw = false;
		try {
			method.invoke(null, source, target);
		} catch (InvocationTargetException e) {
			threw = e.getCause() instanceof java.nio.file.FileAlreadyExistsException;
		}

		assertTrue("Expected FileAlreadyExistsException when target exists", threw);
		assertTrue("Target file should remain present", Files.exists(target));
		assertTrue("Source file should remain present after failed move", Files.exists(source));
		assertTrue(new String(Files.readAllBytes(target), StandardCharsets.UTF_8).equals("target-data"));

		Files.deleteIfExists(source);
		Files.deleteIfExists(target);
		Files.deleteIfExists(tempDir);
	}

	@Test
	public void cleanupUploadSessionChunksDeletesChunkDirectory() throws Exception {
		RESTServices services = new RESTServices();
		Path tempDir = benchmarkRoot().resolve("11/20260705/upload-session-cleanup");
		Path chunksDir = tempDir.resolve("archive.tgz.part.chunks");
		Files.createDirectories(chunksDir);
		Files.write(chunksDir.resolve("chunk_0.bin"), "data".getBytes(StandardCharsets.UTF_8));

		UploadSession session = new UploadSession();
		session.setId(11L);
		session.setUserId(11);
		session.setFileName("archive.tgz");
		session.setStagingPath(tempDir.resolve("archive.tgz.part").toString());

		Method method = RESTServices.class.getDeclaredMethod(
				"cleanupUploadSessionChunks",
				UploadSession.class,
				java.nio.file.Path.class);
		method.setAccessible(true);
		method.invoke(services, session, chunksDir);

		assertFalse(Files.exists(chunksDir));
		Files.deleteIfExists(tempDir);
	}

	@Test
	public void assembleUploadSessionChunksRemovesAssemblingFileWhenSizeMismatchOccurs() throws Exception {
		RESTServices services = new RESTServices();
		Path tempDir = benchmarkRoot().resolve("12/20260705/upload-session-assemble");
		Path chunksDir = tempDir.resolve("archive.tgz.part.chunks");
		Files.createDirectories(chunksDir);
		Files.write(chunksDir.resolve("chunk_0.bin"), "data".getBytes(StandardCharsets.UTF_8));

		UploadSession session = new UploadSession();
		session.setId(12L);
		session.setUserId(12);
		session.setFileName("archive.tgz");
		session.setStagingPath(tempDir.resolve("archive.tgz.part").toString());
		session.setTotalChunks(1);
		session.setTotalBytes(10L);

		Path finalArchive = tempDir.resolve("archive.tgz");

		Method method = RESTServices.class.getDeclaredMethod(
				"assembleUploadSessionChunks",
				UploadSession.class,
				java.nio.file.Path.class,
				java.nio.file.Path.class);
		method.setAccessible(true);
		ValidatorStatusCode status = (ValidatorStatusCode)method.invoke(services, session, chunksDir, finalArchive);

		assertFalse(status.isSuccess());
		assertFalse(Files.exists(Path.of(session.getStagingPath() + ".assembling")));
		assertFalse(Files.exists(finalArchive));
		org.apache.commons.io.FileUtils.deleteQuietly(tempDir.toFile());
	}

	private Path benchmarkRoot() throws Exception {
		Path root = Path.of(R.getBenchmarkPath()).toAbsolutePath().normalize();
		Files.createDirectories(root);
		return root;
	}

	private ServletInputStream servletInputStream(byte[] content) {
		ByteArrayInputStream input = new ByteArrayInputStream(content);
		return new ServletInputStream() {
			@Override
			public int read() {
				return input.read();
			}

			@Override
			public boolean isFinished() {
				return input.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener readListener) {
				// Not used in tests.
			}
		};
	}

	private UploadSession readySession(long sessionId, int userId, Path sessionDir, long totalBytes) {
		UploadSession session = new UploadSession();
		session.setId(sessionId);
		session.setUserId(userId);
		session.setSpaceId(4);
		session.setFileName("archive.tgz");
		session.setStagingPath(sessionDir.resolve("archive.tgz.part").toString());
		session.setStatus("READY");
		session.setBytesReceived(totalBytes);
		session.setTotalBytes(totalBytes);
		session.setNextChunkIndex(1);
		session.setTotalChunks(1);
		session.setUploadMethod("dump");
		session.setBenchmarkTypeId(1);
		return session;
	}
}
