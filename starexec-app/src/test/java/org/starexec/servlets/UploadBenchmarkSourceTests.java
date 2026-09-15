package org.starexec.servlets;

import org.junit.BeforeClass;
import org.junit.Test;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Benchmarks are uploaded from an archive the user sends; a URL or Git source is refused with a
 * clear 400, not an HTTP 500 (#139).
 *
 * <p>{@code UploadBenchmark} accepted {@code localOrURLOrGit} values of {@code URL} and {@code Git}
 * in validation, then threw {@code UnsupportedOperationException} from the asynchronous upload
 * path, which {@code doPost} turned into an HTTP 500 with a generic message. Fetching a user's URL
 * or cloning their repository server-side is not reintroduced here: that is a server-side request
 * forgery and git-transport ({@code file://}, {@code ext::}) surface that needs its own design. The
 * request is refused instead, with a message StarExecCommand receives through the status cookie.
 *
 * <p>The multipart parse, the upload freeze, the session and the upload queue are replaced at their
 * static boundaries; the user may add benchmarks to the space, so only the source is refused.
 */
public class UploadBenchmarkSourceTests {

	private static final String REFUSAL =
			"Uploading benchmarks from a URL or a Git repository is not supported;"
					+ " upload a .zip, .tar or .tgz archive";

	/** The request validator's patterns are compiled at application startup. */
	@BeforeClass
	public static void compileValidatorPatterns() {
		Validator.initialize();
	}

	@Test
	public void aUrlSourceIsRefusedWithABadRequest() throws Exception {
		HashMap<String, Object> form = form("URL");
		form.put("url", "https://example.org/benchmarks.zip");
		assertRefused(form);
	}

	@Test
	public void aGitSourceIsRefusedWithABadRequest() throws Exception {
		HashMap<String, Object> form = form("Git");
		form.put("git", "https://example.org/benchmarks.git");
		assertRefused(form);
	}

	/** A value the page never sends is not a way around the refusal. */
	@Test
	public void anyOtherSourceIsRefusedToo() throws Exception {
		HashMap<String, Object> form = form("ftp");
		form.put("git", "https://example.org/benchmarks.git");
		assertRefused(form);
	}

	private static HashMap<String, Object> form(String source) {
		HashMap<String, Object> form = new HashMap<>();
		form.put("benchType", "1");
		form.put(R.SPACE, "7");
		form.put("download", "true");
		form.put("upMethod", "dump");
		form.put("localOrURLOrGit", source);
		return form;
	}

	private static void assertRefused(HashMap<String, Object> form) throws Exception {
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

			Mockito.verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST, REFUSAL);
			Mockito.verify(response, Mockito.never())
					.sendError(Mockito.eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR), Mockito.anyString());
			ArgumentCaptor<Cookie> cookie = ArgumentCaptor.forClass(Cookie.class);
			Mockito.verify(response).addCookie(cookie.capture());
			assertEquals("StarExecCommand reads the refusal from the status cookie",
					R.STATUS_MESSAGE_COOKIE, cookie.getValue().getName());
			assertTrue("the cookie carries a value", cookie.getValue().getValue() != null
					&& !cookie.getValue().getValue().isEmpty());
			queue.verifyNoInteractions();
		}
	}
}
