package org.starexec.test.junit.util;

import javax.servlet.http.Cookie;
import org.apache.tomcat.util.http.Rfc6265CookieProcessor;
import org.junit.jupiter.api.Test;
import org.starexec.constants.R;
import org.starexec.util.Util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The attributes every cookie this application sets must carry.
 *
 * <p>The id cookies were built with {@code new Cookie(...)} at eleven call sites, so none of
 * them was HttpOnly, none was scoped to the application's path, and none was marked Secure on
 * an HTTPS deployment. {@link Util#createIdCookie} applies the same attributes
 * {@link Util#createEncodedCookie} already applied, and these tests fail if a future change
 * drops one of them.
 *
 * <p>The value is URL-encoded. Five call sites pass a comma-separated id list, and RFC 6265 does
 * not allow a comma in a cookie value: Tomcat threw when the cookie was added, after the copy or
 * upload it reported had happened. {@code IdCookieRoundTripTest} checks that StarexecCommand reads
 * the encoded list back.
 */
public class IdCookieAttributesTest {

	@Test
	public void tomcatAcceptsAnIdList() {
		// The processor Tomcat 9 uses by default; it threw IllegalArgumentException on the raw list.
		new Rfc6265CookieProcessor().generateHeader(Util.createIdCookie("New_ID", "12,34,56"), null);
	}

	@Test
	public void aSingleIdIsUnchanged() {
		// What the other call sites send, and what StarexecCommand releases from before the
		// encoding still parse.
		assertEquals("7", Util.createIdCookie("New_ID", "7").getValue());
	}

	@Test
	public void anIdCookieIsHttpOnly() {
		assertTrue(Util.createIdCookie("New_ID", "7").isHttpOnly(),
				"nothing in the web UI reads this cookie; only StarexecCommand, from the"
						+ " response headers, which HttpOnly does not affect");
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
		assertEquals("a+message", encoded.getValue(), "the encoding path is unchanged");
	}
}
