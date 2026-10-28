package org.starexec.servlets;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Creating a user must never shorten the administrator's own session.
 *
 * <p>Registration set the interval to a flat 30 minutes and called it an extension. The
 * deployment configures 60 (web.xml session-timeout), so every user an admin created halved
 * their own session, and an admin who then spent half an hour on a long upload or job form was
 * silently logged out and lost it.
 *
 * <p>In the servlet's own package because the helper is package-private: it exists so the
 * decision can be tested without driving doPost's whole success path.
 */
public class AdminSessionNotShortenedTest {

	/** The deployed case: 60 minutes configured, 30 wanted. The longer one must survive. */
	@Test
	public void aLongerConfiguredTimeoutIsKept() {
		assertEquals(60 * 60,
				Registration.atLeastWithoutShortening(60 * 60, Registration.ADMIN_MINIMUM_SESSION_SECONDS));
	}

	/** The intent is kept for a deployment configured below the minimum. */
	@Test
	public void aShorterConfiguredTimeoutIsRaisedToTheMinimum() {
		assertEquals(Registration.ADMIN_MINIMUM_SESSION_SECONDS,
				Registration.atLeastWithoutShortening(10 * 60, Registration.ADMIN_MINIMUM_SESSION_SECONDS));
	}

	/** Equal is not a change either way. */
	@Test
	public void anEqualTimeoutIsLeftAlone() {
		assertEquals(Registration.ADMIN_MINIMUM_SESSION_SECONDS,
				Registration.atLeastWithoutShortening(
						Registration.ADMIN_MINIMUM_SESSION_SECONDS, Registration.ADMIN_MINIMUM_SESSION_SECONDS));
	}

	/**
	 * Pins the call SHAPE rather than the behaviour: driving doPost's success path needs a
	 * request, a session, a registration result and an isAdmin determination, which costs more
	 * than this whole change and would prove little, since such a test passes whatever the call
	 * site happens to do. Every setMaxInactiveInterval in this servlet must go through the
	 * helper, never a literal, which is exactly how the defect was written.
	 *
	 * <p>If this ever becomes noise, move the assertion rather than loosen it.
	 */
	@Test
	public void theServletSetsTheIntervalOnlyThroughTheHelper() throws Exception {
		List<String> lines = Files.readAllLines(
				Path.of("src/main/java/org/starexec/servlets/Registration.java"));
		boolean called = false;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.trim().startsWith("*") || line.trim().startsWith("//")) {
				continue;
			}
			if (!line.contains("setMaxInactiveInterval(")) {
				continue;
			}
			called = true;
			String argument = line.substring(line.indexOf("setMaxInactiveInterval(")).trim();
			assertTrue("Registration.java:" + (i + 1) + " must set the interval through"
							+ " atLeastWithoutShortening, not directly: " + argument,
					argument.contains("atLeastWithoutShortening") || argument.endsWith("setMaxInactiveInterval("));
		}
		assertTrue("Registration must still set a session interval, or this test is vacuous", called);
	}

	/** A container default of zero, meaning "never expires", must not be cut to 30 minutes. */
	@Test
	public void aSessionThatNeverExpiresIsNotGivenAnExpiry() {
		assertEquals("a non-positive interval means the session never expires; shortening it to the"
						+ " minimum would impose one",
				0, Registration.atLeastWithoutShortening(0, Registration.ADMIN_MINIMUM_SESSION_SECONDS));
	}
}
