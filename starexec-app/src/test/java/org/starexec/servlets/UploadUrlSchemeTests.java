package org.starexec.servlets;

import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.constants.R;
import org.starexec.data.security.UploadSecurity;
import org.starexec.data.to.Permission;
import org.starexec.util.SessionUtil;
import org.starexec.util.Util;
import org.starexec.util.Validator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;

/**
 * Solver and processor uploads that name their archive by URL are validated for an http or https
 * URL, and anything else is answered with a 400 naming the problem.
 *
 * <p>The multipart parse, the upload freeze and the session are replaced at their static
 * boundaries. The user has no rights in the space, so a request that passes validation stops at
 * the permission check: nothing is downloaded or written either way.
 */
public class UploadUrlSchemeTests {

	private static final String URL_REQUIRED = "Archive URLs must be valid http or https URLs";
	private static final String NAME_REQUIRED = "Archive URLs must end in an archive file name, such as solver.zip";
	private static final String SOLVER_TYPE_REQUIRED = "Archives need to have an extension of .zip, .tar, or .tgz";
	private static final String PROCESSOR_TYPE_REQUIRED = "Uploaded archives must be a .zip, .tar, or .tgz";
	private static final String NOT_AUTHORIZED = "You are not authorized to add solvers to this space";

	/** The request validator's patterns are compiled at application startup. */
	@BeforeClass
	public static void compileValidatorPatterns() {
		Validator.initialize();
	}

	@Test
	public void aSolverFileUrlIsRefused() throws Exception {
		postSolver("file:///srv/starexec/solver.zip");

		verifyBadRequest(URL_REQUIRED);
	}

	@Test
	public void aSolverJarUrlIsRefused() throws Exception {
		postSolver("jar:file:///srv/starexec/solvers.zip!/solver.zip");

		verifyBadRequest(URL_REQUIRED);
	}

	/** Guard: an https URL passes validation and stops at the permission check. */
	@Test
	public void aSolverHttpsUrlPassesValidation() throws Exception {
		postSolver("HTTPS://example.org/solver.zip");

		verifyBadRequest(NOT_AUTHORIZED);
	}

	/** The archive is named by the last segment of the URL's path. */
	@Test
	public void aSolverUrlEndingInADirectoryIsRefused() throws Exception {
		postSolver("https://example.org/solvers/");

		verifyBadRequest(NAME_REQUIRED);
	}

	@Test
	public void aSolverUrlEndingInAParentSegmentIsRefused() throws Exception {
		postSolver("https://example.org/solvers/..");

		verifyBadRequest(NAME_REQUIRED);
	}

	/** Guard: a query string is not part of the archive name. */
	@Test
	public void aSolverUrlWithAQueryIsNamedByItsPath() throws Exception {
		postSolver("https://example.org/solver.zip?version=2");

		verifyBadRequest(NOT_AUTHORIZED);
	}

	/** An archive extension in the query does not make the path an archive. */
	@Test
	public void aSolverUrlWithTheArchiveOnlyInItsQueryIsRefused() throws Exception {
		postSolver("https://example.org/download?file=solver.zip");

		verifyBadRequest(SOLVER_TYPE_REQUIRED);
	}

	@Test
	public void aProcessorUrlEndingInADirectoryIsRefused() throws Exception {
		postProcessor("http://example.org/processors/");

		verifyBadRequest(NAME_REQUIRED);
	}

	@Test
	public void aProcessorUrlWithTheArchiveOnlyInItsQueryIsRefused() throws Exception {
		postProcessor("http://example.org/download?file=processor.zip");

		verifyBadRequest(PROCESSOR_TYPE_REQUIRED);
	}

	@Test
	public void aProcessorFileUrlIsRefused() throws Exception {
		postProcessor("file:///srv/starexec/processor.zip");

		verifyBadRequest(URL_REQUIRED);
	}

	/** Guard: an http URL passes validation and stops at the community leader check. */
	@Test
	public void aProcessorHttpUrlPassesValidation() throws Exception {
		postProcessor("http://example.org/processor.zip");

		Mockito.verify(response).sendError(Mockito.eq(HttpServletResponse.SC_FORBIDDEN), Mockito.anyString());
		Mockito.verify(response, Mockito.never()).sendError(Mockito.eq(HttpServletResponse.SC_BAD_REQUEST),
				Mockito.anyString());
	}

	private final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
	private final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

	private void postSolver(String url) throws Exception {
		HashMap<String, Object> form = new HashMap<>();
		form.put("upMethod", "URL");
		form.put("url", url);
		form.put("execType", "1");
		form.put("descMethod", "text");
		form.put("desc", "a solver");
		form.put("dlable", "true");
		form.put("sn", "solver");
		form.put("runTestJob", "false");
		form.put(R.SPACE, "7");
		post(form, () -> new UploadSolver().doPost(request, response));
	}

	private void postProcessor(String url) throws Exception {
		HashMap<String, Object> form = new HashMap<>();
		form.put("action", "add");
		form.put("uploadMethod", "URL");
		form.put("processorUrl", url);
		form.put("name", "processor");
		form.put("desc", "a processor");
		form.put("com", "3");
		form.put("type", "post");
		post(form, () -> new ProcessorManager().doPost(request, response));
	}

	private interface Post {
		void run() throws Exception;
	}

	private void post(HashMap<String, Object> form, Post post) throws Exception {
		Mockito.when(request.getMethod()).thenReturn("POST");
		Mockito.when(request.getContentType()).thenReturn("multipart/form-data; boundary=upload-boundary");
		Mockito.when(request.getHeader("Content-Type")).thenReturn("multipart/form-data; boundary=upload-boundary");

		try (MockedStatic<UploadSecurity> security = Mockito.mockStatic(UploadSecurity.class);
				MockedStatic<Util> util = Mockito.mockStatic(Util.class, Mockito.CALLS_REAL_METHODS);
				MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class)) {
			security.when(UploadSecurity::uploadsFrozen).thenReturn(false);
			util.when(() -> Util.parseMultipartRequest(request)).thenReturn(form);
			session.when(() -> SessionUtil.getPermission(Mockito.eq(request), Mockito.anyInt()))
					.thenReturn(new Permission(false));
			session.when(() -> SessionUtil.getUserId(request)).thenReturn(5);

			post.run();

			util.verify(() -> Util.copyFileFromURLUsingProxy(Mockito.any(), Mockito.any()), Mockito.never());
			util.verify(() -> Util.copyFileFromURLUsingProxy(Mockito.any(), Mockito.any(), Mockito.anyLong()),
					Mockito.never());
		}
	}

	private void verifyBadRequest(String message) throws Exception {
		Mockito.verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST, message);
		Mockito.verify(response, Mockito.never()).sendError(Mockito.eq(HttpServletResponse.SC_FORBIDDEN),
				Mockito.anyString());
		Mockito.verify(response, Mockito.never()).sendError(
				Mockito.eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR), Mockito.anyString());
	}
}
