package org.starexec.servlets;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * These tests lean on a property of the unit-test JVM: {@code Common.dataPool} is
 * never initialized here, so {@code Common.isDatabaseReachable} is definitively
 * false. That makes the central assertion real rather than circular — liveness
 * must answer 200 in exactly the situation where readiness must answer 503. If
 * someone later "simplifies" liveness by giving it a database check, these fail.
 */
public class HealthCheckTests {

	private HealthCheck servlet;
	private HttpServletRequest request;
	private HttpServletResponse response;
	private StringWriter body;

	@Before
	public void setUp() throws IOException {
		servlet = new HealthCheck();
		request = Mockito.mock(HttpServletRequest.class);
		response = Mockito.mock(HttpServletResponse.class);
		body = new StringWriter();
		Mockito.when(response.getWriter()).thenReturn(new PrintWriter(body, true));
	}

	/** doGet is protected, so reach it the way the container would. */
	private void get(String pathInfo) throws Exception {
		Mockito.when(request.getPathInfo()).thenReturn(pathInfo);
		Method doGet = HealthCheck.class.getDeclaredMethod(
			"doGet", HttpServletRequest.class, HttpServletResponse.class);
		doGet.setAccessible(true);
		doGet.invoke(servlet, request, response);
	}

	@Test
	public void livenessSucceedsWithNoDatabase() throws Exception {
		get("/liveness");

		Mockito.verify(response).setStatus(HttpServletResponse.SC_OK);
		assertEquals("alive", body.toString().trim());
	}

	@Test
	public void readinessFailsWithNoDatabase() throws Exception {
		get("/readiness");

		Mockito.verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		assertEquals("not ready", body.toString().trim());
	}

	@Test
	public void unknownSubPathIsNotFound() throws Exception {
		get("/metrics");

		Mockito.verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
	}

	@Test
	public void nullPathInfoIsNotFound() throws Exception {
		// A request for /public/health with no trailing segment gives a null
		// pathInfo; that must not become a NullPointerException in a probe.
		get(null);

		Mockito.verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
	}

	@Test
	public void probeResponsesAreNotCacheable() throws Exception {
		get("/liveness");

		Mockito.verify(response).setHeader("Cache-Control", "no-store");
	}

	@Test
	public void responseLeaksNoDiagnosticDetail() throws Exception {
		// The path is unauthenticated. A failing readiness check must not hand an
		// anonymous caller the reason -- no hostnames, no driver or SQL text.
		get("/readiness");

		String text = body.toString().toLowerCase();
		assertEquals("not ready", text.trim());
		assertTrue("body must not name the database or driver",
			!text.contains("postgres") && !text.contains("jdbc") && !text.contains("sql"));
	}
}
