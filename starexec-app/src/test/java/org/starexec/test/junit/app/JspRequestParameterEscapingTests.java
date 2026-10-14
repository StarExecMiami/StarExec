package org.starexec.test.junit.app;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Static scan of JSP source (there is no JSP renderer in the unit-test classpath): request-derived values written
 * into HTML attributes must go through {@code c:out}, never a bare EL expression.
 */
public class JspRequestParameterEscapingTests {
	private static final Path WEBAPP = Paths.get("src/main/webapp/secure");

	private static String read(String name) throws IOException {
		return new String(Files.readAllBytes(WEBAPP.resolve(name)), StandardCharsets.UTF_8);
	}

	@Test
	public void solverDetailsEscapesContextSpaceId() throws IOException {
		String jsp = read("details/solver.jsp");
		assertFalse(jsp.contains("contextSpaceId=${param.contextSpaceId}"));
		assertTrue(jsp.contains("contextSpaceId=<c:out value=\"${param.contextSpaceId}\"/>"));
	}

	@Test
	public void benchmarkDetailsEscapesContextSpaceId() throws IOException {
		String jsp = read("details/benchmark.jsp");
		assertFalse(jsp.contains("contextSpaceId=${param.contextSpaceId}"));
		assertTrue(jsp.contains("contextSpaceId=<c:out value=\"${param.contextSpaceId}\"/>"));
	}

	@Test
	public void helpPageEscapesTheRefParameter() throws IOException {
		String jsp = read("help.jsp");
		assertFalse("raw ref must not be written into markup", jsp.contains("href=\"${ref}\""));
		assertTrue(jsp.contains("<c:out value='${ref}'/>"));
		assertTrue("c taglib must be declared", jsp.contains("prefix=\"c\""));
	}
}
