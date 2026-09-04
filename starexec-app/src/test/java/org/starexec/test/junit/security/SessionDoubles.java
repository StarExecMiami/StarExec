package org.starexec.test.junit.security;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Servlet doubles with real attribute semantics.
 *
 * <p>A plain Mockito {@code HttpSession} would let {@code invalidate()} do
 * nothing, so a test could "pass" while the session it claimed to revoke still
 * held its user. The session here really stores attributes and really discards
 * them on invalidate, which is what the servlet specification requires and what
 * the revocation behaviour depends on.</p>
 */
final class SessionDoubles {

	private SessionDoubles() {
	}

	/** A session backed by a real map, with observable invalidation. */
	static final class Session {
		final HttpSession mock;
		final Map<String, Object> attrs = new HashMap<>();
		boolean invalidated;

		Session() {
			mock = mock(HttpSession.class);
			when(mock.getAttribute(anyString())).thenAnswer(i -> attrs.get(i.<String>getArgument(0)));
			doAnswer(i -> {
				attrs.put(i.getArgument(0), i.getArgument(1));
				return null;
			}).when(mock).setAttribute(anyString(), any());
			doAnswer(i -> {
				attrs.remove(i.<String>getArgument(0));
				return null;
			}).when(mock).removeAttribute(anyString());
			doAnswer(i -> {
				attrs.clear();
				invalidated = true;
				return null;
			}).when(mock).invalidate();
		}
	}

	static HttpServletRequest request(Session session, String uri, String contextPath, String userAgent) {
		HttpServletRequest r = mock(HttpServletRequest.class);
		HttpSession s = session == null ? null : session.mock;
		when(r.getSession(false)).thenReturn(s);
		when(r.getSession(true)).thenReturn(s);
		when(r.getSession()).thenReturn(s);
		when(r.getRequestURI()).thenReturn(uri);
		when(r.getContextPath()).thenReturn(contextPath);
		when(r.getRemoteUser()).thenReturn(null);
		when(r.getHeader("User-Agent")).thenReturn(userAgent);
		when(r.getHeader("user-agent")).thenReturn(userAgent);
		return r;
	}
}
