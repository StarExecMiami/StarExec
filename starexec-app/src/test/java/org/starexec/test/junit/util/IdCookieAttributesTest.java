package org.starexec.test.junit.util;

import javax.servlet.http.Cookie;
import org.junit.Test;
import org.starexec.constants.R;
import org.starexec.util.Util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The attributes every cookie this application sets must carry.
 *
 * <p>The id cookies were built with {@code new Cookie(...)} at eleven call sites, so none of
 * them was HttpOnly, none was scoped to the application's path, and none was marked Secure on
 * an HTTPS deployment. {@link Util#createIdCookie} applies the same attributes
 * {@link Util#createEncodedCookie} already applied, and these tests fail if a future change
 * drops one of them.
 *
 * <p>The value is deliberately left unencoded: StarexecCommand reads these cookies from the
 * response headers and splits them on commas, so encoding the separator would change what the
 * client parses. {@code theValueIsNotEncoded} pins that.
 */
public class IdCookieAttributesTest {

	@Test
	public void theValueIsNotEncoded() {
		// A comma-separated id list is what four of the eleven call sites pass.
		assertEquals("12,34,56", Util.createIdCookie("New_ID", "12,34,56").getValue());
	}

	@Test
	public void anIdCookieIsHttpOnly() {
		assertTrue("nothing in the web UI reads this cookie; only StarexecCommand, from the"
						+ " response headers, which HttpOnly does not affect",
				Util.createIdCookie("New_ID", "7").isHttpOnly());
	}

	@Test
	public void anIdCookieIsScopedToTheApplication() {
		assertEquals("/" + R.STAREXEC_APPNAME, Util.createIdCookie("New_ID", "7").getPath());
	}

	@Test
	public void anIdCookieIsSecureExactlyWhenTheDeploymentIsHttps() {
		// Asserted against the deployment's own scheme rather than a fixed expectation, so this
		// holds however the test environment is configured.
		assertEquals("https".equalsIgnoreCase(R.STAREXEC_URL_PREFIX),
				Util.createIdCookie("New_ID", "7").getSecure());
	}

	@Test
	public void anEncodedCookieKeepsTheSameAttributes() {
		Cookie encoded = Util.createEncodedCookie(R.STATUS_MESSAGE_COOKIE, "a message");
		assertTrue(encoded.isHttpOnly());
		assertEquals("/" + R.STAREXEC_APPNAME, encoded.getPath());
		assertEquals("https".equalsIgnoreCase(R.STAREXEC_URL_PREFIX), encoded.getSecure());
		assertEquals("the encoding path is unchanged", "a+message", encoded.getValue());
	}
}
