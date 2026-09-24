package org.starexec.test.junit.security;

import org.junit.Test;
import org.starexec.app.CsrfFilter;
import org.starexec.util.CsrfUtil;

import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CsrfFilterTests {

	private static final String CONTEXT = "/starexec";
	private static final String DELETE_USER = CONTEXT + "/services/delete/user/42";
	private static final String TOKEN = "session-csrf-token";

	private HttpServletRequest postRequest(String uri) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpSession session = mock(HttpSession.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn(uri);
		when(request.getContextPath()).thenReturn(CONTEXT);
		when(request.getSession(false)).thenReturn(session);
		when(session.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn(TOKEN);
		return request;
	}

	@Test
	public void servicePostWithoutTokenIsRejectedBeforeTheEndpointRuns() throws Exception {
		HttpServletRequest request = postRequest(DELETE_USER);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		new CsrfFilter().doFilter(request, response, chain);

		verify(response).sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid or missing CSRF token");
		verify(chain, never()).doFilter((ServletRequest) request, (ServletResponse) response);
	}

	@Test
	public void otherRestMutationWithoutTokenIsAlsoRejected() throws Exception {
		HttpServletRequest request = postRequest(CONTEXT + "/services/spaces/1/users");
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		new CsrfFilter().doFilter(request, response, chain);

		verify(response).sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid or missing CSRF token");
		verify(chain, never()).doFilter((ServletRequest) request, (ServletResponse) response);
	}

	@Test
	public void userDeletionPostWithSessionTokenReachesTheEndpoint() throws Exception {
		HttpServletRequest request = postRequest(DELETE_USER);
		when(request.getHeader(CsrfFilter.CSRF_HEADER)).thenReturn(TOKEN);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		new CsrfFilter().doFilter(request, response, chain);

		verify(chain).doFilter((ServletRequest) request, (ServletResponse) response);
	}

	@Test
	public void commandClientRetainsTheDocumentedCsrfExemption() throws Exception {
		HttpServletRequest request = postRequest(DELETE_USER);
		when(request.getHeader("StarExecCommand")).thenReturn("StarExecCommand");
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		new CsrfFilter().doFilter(request, response, chain);

		verify(chain).doFilter((ServletRequest) request, (ServletResponse) response);
	}
}
