package org.starexec.test.junit.util;

import javax.servlet.http.HttpServletRequest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.starexec.util.JspHelpers;
import org.starexec.util.Util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * The root the job page builds its links on: matrix view, attributes, solver comparison,
 * panel view, downloads, and each pair's details.
 *
 * <p>It was built from {@code X-Forwarded-Host} and {@code X-Forwarded-Proto}. Each proxy in
 * a chain appends to those, so behind Quokka's two they arrived as
 * "quokka.acorn.miami.edu, quokka.acorn.miami.edu" and "https, https", and every one of those
 * links read {@code https://quokka.acorn.miami.edu, quokka.acorn.miami.edu/starexec/...}.
 * These tests send exactly those headers, and a spoofed host, and fail if the root is ever
 * derived from them again.
 */
public class JobPageRootTest {

	private static final String DOUBLED_HOST = "quokka.acorn.miami.edu, quokka.acorn.miami.edu";

	private HttpServletRequest request;

	@Before
	public void requestBehindTwoProxies() {
		request = Mockito.mock(HttpServletRequest.class);
		Mockito.when(request.getContextPath()).thenReturn("/starexec");
		Mockito.when(request.getHeader("X-Forwarded-Host")).thenReturn(DOUBLED_HOST);
		Mockito.when(request.getHeader("X-Forwarded-Proto")).thenReturn("https, https");
		Mockito.when(request.getHeader("Host")).thenReturn(DOUBLED_HOST);
	}

	@Test
	public void theLivePageLinksRelativeToTheOriginTheBrowserUsed() {
		assertEquals("/starexec/", JspHelpers.jobPageRoot(request, false));
	}

	@Test
	public void aSpoofedForwardedHostDoesNotReachTheLinks() {
		Mockito.when(request.getHeader("X-Forwarded-Host")).thenReturn("attacker.example");
		Mockito.when(request.getHeader("X-Forwarded-Proto")).thenReturn("http");
		assertEquals("/starexec/", JspHelpers.jobPageRoot(request, false));
		assertFalse(JspHelpers.jobPageRoot(request, true).contains("attacker.example"));
	}

	/**
	 * The offline copy DoJobPage saves opens the live server from its matrix and attribute
	 * buttons, so its root is absolute -- the configured address, the one DoJobPage fetched
	 * the page from, whatever headers that fetch passed through.
	 */
	@Test
	public void theOfflineCopyLinksToTheConfiguredServer() {
		assertEquals(Util.url(""), JspHelpers.jobPageRoot(request, true));
		assertFalse(JspHelpers.jobPageRoot(request, true).contains(","));
		assertFalse(JspHelpers.jobPageRoot(request, true).contains(" "));
	}
}
