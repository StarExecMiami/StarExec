package org.starexec.servlets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.constants.R;
import org.starexec.data.database.AnonymousLinks;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Permissions;
import org.starexec.data.database.Solvers;
import org.starexec.data.security.SolverSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.Status;
import org.starexec.data.to.User;
import org.starexec.util.SessionUtil;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;

public class DownloadTests {

	private static String[] solverDownloadTypes() { return new String[] {R.SOLVER, R.SOLVER_SOURCE}; }

	@ParameterizedTest @MethodSource("solverDownloadTypes")
	public void solverDownloadAuthorizationRunsBeforeResolvingOrReadingFiles(String type) throws Exception {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
		User user = new User(); user.setId(7);
		Mockito.when(request.getParameter("type")).thenReturn(type);
		Mockito.when(request.getParameter("id")).thenReturn("9");
		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
				MockedStatic<SolverSecurity> security = Mockito.mockStatic(SolverSecurity.class);
				MockedStatic<Solvers> solvers = Mockito.mockStatic(Solvers.class)) {
			session.when(() -> SessionUtil.getUser(request)).thenReturn(user);
			session.when(() -> SessionUtil.getUserId(request)).thenReturn(7);
			security.when(() -> SolverSecurity.canUserDownloadSolver(9, 7))
					.thenReturn(new ValidatorStatusCode(false, "No source permission", HttpServletResponse.SC_FORBIDDEN));
			new Download().doGet(request, response);
			Mockito.verify(response).sendError(HttpServletResponse.SC_FORBIDDEN, "No source permission");
			solvers.verifyNoInteractions(); Mockito.verify(response, Mockito.never()).getOutputStream();
		}
	}

	@Test
	public void doGetSendsHttp500WhenJobDownloadFailsBeforeResponseCommit() throws Exception {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

		User user = new User();
		user.setId(7);

		Mockito.when(request.getParameter("type")).thenReturn(R.JOB);
		Mockito.when(request.getParameter("id")).thenReturn("1");
		Mockito.when(request.getParameter("getcompleted")).thenReturn("false");
		Mockito.when(request.getParameter("returnids")).thenReturn("false");
		Mockito.when(response.isCommitted()).thenReturn(false);

		try (MockedStatic<SessionUtil> sessionUtilMock = Mockito.mockStatic(SessionUtil.class);
				 MockedStatic<Permissions> permissionsMock = Mockito.mockStatic(Permissions.class);
				 MockedStatic<Jobs> jobsMock = Mockito.mockStatic(Jobs.class)) {
			sessionUtilMock.when(() -> SessionUtil.getUser(request)).thenReturn(user);
			sessionUtilMock.when(() -> SessionUtil.getUserId(request)).thenReturn(7);
			permissionsMock.when(() -> Permissions.canUserSeeJob(anyInt(), anyInt()))
					.thenReturn(new ValidatorStatusCode(true));
			jobsMock.when(() -> Jobs.get(1)).thenReturn(null);

			new Download().doGet(request, response);

			Mockito.verify(response).reset();
			Mockito.verify(response).sendError(
					HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"Failed to generate download"
			);
		}
	}

	@Test
	public void doGetRejectsMixedAnonIdAndSequentialId() throws Exception {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

		User user = new User();
		user.setId(7);

		Mockito.when(request.getParameter("anonId")).thenReturn("some-uuid");
		Mockito.when(request.getParameter("id")).thenReturn("1");

		try (MockedStatic<SessionUtil> sessionUtilMock = Mockito.mockStatic(SessionUtil.class)) {
			sessionUtilMock.when(() -> SessionUtil.getUser(request)).thenReturn(user);

			new Download().doGet(request, response);

			Mockito.verify(response).sendError(
					HttpServletResponse.SC_BAD_REQUEST,
					"Invalid request: cannot combine anonId with sequential id"
			);
		}
	}

	@Test
	public void anonymousDownloadOfJobOutputsIsForbidden() throws Exception {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

		User user = new User();
		user.setId(1);

		Mockito.when(request.getParameter("type")).thenReturn(R.JOB_OUTPUT);
		Mockito.when(request.getParameter("anonId")).thenReturn("some-uuid");

		try (MockedStatic<SessionUtil> sessionUtilMock = Mockito.mockStatic(SessionUtil.class)) {
			sessionUtilMock.when(() -> SessionUtil.getUser(request)).thenReturn(user);

			new Download().doGet(request, response);

			Mockito.verify(response).sendError(
					HttpServletResponse.SC_FORBIDDEN,
					"Anonymous downloads are not supported for this download type."
			);
		}
	}

	@Test
	public void anonymousDownloadWithUnknownSolverLinkIsNotFound() throws Exception {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

		User user = new User();
		user.setId(1);

		Mockito.when(request.getParameter("type")).thenReturn(R.SOLVER);
		Mockito.when(request.getParameter("anonId")).thenReturn("missing-uuid");

		try (MockedStatic<SessionUtil> sessionUtilMock = Mockito.mockStatic(SessionUtil.class);
				 MockedStatic<AnonymousLinks> anonymousLinksMock = Mockito.mockStatic(AnonymousLinks.class)) {
			sessionUtilMock.when(() -> SessionUtil.getUser(request)).thenReturn(user);
			anonymousLinksMock.when(() -> AnonymousLinks.getIdOfSolverAssociatedWithLink("missing-uuid"))
					.thenReturn(Optional.empty());

			new Download().doGet(request, response);

			Mockito.verify(response).sendError(
					HttpServletResponse.SC_NOT_FOUND,
					"Solver not found."
			);
		}
	}

	@Test
	public void flatZipUsesPairIdsAndDoesNotMutateZipFileNameAcrossMissingFiles(@TempDir Path tempDir)
			throws Exception {
		Path pair1Stdout = tempDir.resolve("11.txt");
		Files.writeString(pair1Stdout, "out-11", StandardCharsets.UTF_8);
		Path pair2MissingStdout = tempDir.resolve("22.txt");
		Path pair2ExtraDir = tempDir.resolve("22_output");
		Files.createDirectory(pair2ExtraDir);
		Files.writeString(pair2ExtraDir.resolve("extra.log"), "extra-22", StandardCharsets.UTF_8);

		Status complete = new Status();
		complete.setCode(Status.StatusCode.STATUS_COMPLETE.getVal());

		JobPair pair1 = new JobPair();
		pair1.setId(11);
		pair1.setStatus(complete);

		JobPair pair2 = new JobPair();
		pair2.setId(22);
		pair2.setStatus(complete);

		ByteArrayOutputStream body = new ByteArrayOutputStream();
		HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
		Mockito.when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
			@Override public boolean isReady() { return true; }
			@Override public void setWriteListener(WriteListener writeListener) {}
			@Override public void write(int b) { body.write(b); }
		});

		try (MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
			jobPairs.when(() -> JobPairs.getOutputPaths(pair1)).thenReturn(List.of(pair1Stdout.toFile()));
			jobPairs.when(() -> JobPairs.getOutputPaths(pair2))
					.thenReturn(List.of(pair2MissingStdout.toFile(), pair2ExtraDir.toFile()));

			Method m = Download.class.getDeclaredMethod(
					"addJobPairsToZipOutput",
					List.class, HttpServletResponse.class, String.class, boolean.class, Long.class);
			m.setAccessible(true);
			m.invoke(null, List.of(pair1, pair2), response, "Job9_output", false, null);
		}

		Set<String> entryNames = new HashSet<>();
		try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(body.toByteArray()))) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				entryNames.add(entry.getName().replace('\\', '/'));
				assertFalse(entry.getName().startsWith("/"), "Zip entry must not start with slash: " + entry.getName());
				assertFalse(entry.getName().contains(".txt/"), "Missing file suffix must not pollute directory entry");
			}
		}

		assertTrue(entryNames.contains("Job9_output/11/11.txt"), "Entries were: " + entryNames);
		assertTrue(entryNames.contains("Job9_output/22.txt"), "Entries were: " + entryNames);
		assertTrue(entryNames.contains("Job9_output/22/22_output/extra.log"), "Entries were: " + entryNames);
		assertEquals(3, entryNames.size());
	}
}
