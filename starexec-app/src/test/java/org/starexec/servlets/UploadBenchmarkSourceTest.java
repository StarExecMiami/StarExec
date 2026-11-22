package org.starexec.servlets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.constants.R;
import org.starexec.data.database.UploadJobQueue;
import org.starexec.data.security.UploadSecurity;
import org.starexec.data.to.Permission;
import org.starexec.util.SessionUtil;
import org.starexec.util.Util;
import org.starexec.util.Validator;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies #139 upload source handling: Git sources are refused cleanly with HTTP 400 (not 500),
 * while archive URL validation remains intact.
 */
public class UploadBenchmarkSourceTest {

	@BeforeAll
	public static void compileValidatorPatterns() {
		Validator.initialize();
	}

	@Test
	public void gitSourceIsRefusedWithBadRequestAndStatusCookie() throws Exception {
		HashMap<String, Object> form = baseForm("Git");
		form.put("git", "https://example.org/benchmarks.git");
		assertRefused(form, UploadBenchmark.UNSUPPORTED_GIT_SOURCE);
	}

	@Test
	public void unknownSourceIsRefusedWithBadRequest() throws Exception {
		HashMap<String, Object> form = baseForm("ftp");
		assertRefused(form, "Please select a file source (local or URL)");
	}

	@Test
	public void gitUrlHelperStillEnforcesHttpAndRejectsCredentials() {
		assertEquals("Git URLs must use http or https", UploadBenchmark.gitUrlProblem("file:///etc/passwd.git"));
		assertEquals("Git URLs must not contain a user name or password",
				UploadBenchmark.gitUrlProblem("https://user:secret@example.org/repo.git"));
		assertNull(UploadBenchmark.gitUrlProblem("https://example.org/benchmarks.git"));
	}

	private static HashMap<String, Object> baseForm(String source) {
		HashMap<String, Object> form = new HashMap<>();
		form.put("benchType", "1");
		form.put(R.SPACE, "7");
		form.put("download", "true");
		form.put("upMethod", "dump");
		form.put("localOrURLOrGit", source);
		return form;
	}

	private static void assertRefused(HashMap<String, Object> form, String expectedMessage) throws Exception {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

		try (MockedStatic<UploadSecurity> security = Mockito.mockStatic(UploadSecurity.class);
			 MockedStatic<Util> util = Mockito.mockStatic(Util.class, Mockito.CALLS_REAL_METHODS);
			 MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<UploadJobQueue> queue = Mockito.mockStatic(UploadJobQueue.class)) {
			security.when(UploadSecurity::uploadsFrozen).thenReturn(false);
			util.when(() -> Util.parseMultipartRequest(request)).thenReturn(form);
			session.when(() -> SessionUtil.getPermission(Mockito.eq(request), Mockito.anyInt()))
					.thenReturn(new Permission(true));
			session.when(() -> SessionUtil.getUserId(request)).thenReturn(5);

			new UploadBenchmark().doPost(request, response);

			Mockito.verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST, expectedMessage);
			Mockito.verify(response, Mockito.never())
					.sendError(Mockito.eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR), Mockito.anyString());
			ArgumentCaptor<Cookie> cookie = ArgumentCaptor.forClass(Cookie.class);
			Mockito.verify(response).addCookie(cookie.capture());
			assertEquals(R.STATUS_MESSAGE_COOKIE, cookie.getValue().getName());
			assertNotNull(cookie.getValue().getValue());
			assertTrue(!cookie.getValue().getValue().isEmpty());
			queue.verifyNoInteractions();
		}
	}
}
