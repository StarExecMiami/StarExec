package org.starexec.test.junit.command;

import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
import org.apache.tomcat.util.http.Rfc6265CookieProcessor;
import org.junit.jupiter.api.Test;
import org.starexec.command.HTMLParser;
import org.starexec.constants.R;
import org.starexec.util.Util;

import javax.servlet.http.Cookie;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The cookies the server sets, as Tomcat writes them and as StarexecCommand reads them back.
 *
 * <p>Copying several benchmarks, solvers or spaces answers with a New_ID cookie listing every new
 * id, separated by commas. RFC 6265 does not allow a comma in a cookie value, and Tomcat's cookie
 * processor threw on it when the cookie was added, after the copy had happened. The user got a
 * server error for a copy that had succeeded. These tests write each cookie through the same
 * processor, then parse the header with the client's own parser.
 */
public class IdCookieRoundTripTest {

	private static Header[] written(Cookie cookie) {
		return new Header[] {
				new BasicHeader("Set-Cookie", new Rfc6265CookieProcessor().generateHeader(cookie, null)) };
	}

	@Test
	public void starexecCommandReadsAnIdListTheServerSends() {
		Header[] headers = written(Util.createIdCookie("New_ID", "12,34,56"));
		assertArrayEquals(new String[] { "12", "34", "56" },
				HTMLParser.extractMultipartCookie(headers, "New_ID"));
	}

	@Test
	public void starexecCommandReadsASingleId() {
		assertEquals("7", HTMLParser.extractCookie(written(Util.createIdCookie("New_ID", "7")), "New_ID"));
	}

	@Test
	public void starexecCommandShowsAStatusMessageAsWritten() {
		// Before decoding, the client printed "Please+select+a+file+source+%28local%2C+URL%2C+or+Git%29".
		String message = "Please select a file source (local, URL, or Git)";
		Header[] headers = written(Util.createEncodedCookie(R.STATUS_MESSAGE_COOKIE, message));
		assertEquals(message, HTMLParser.extractCookie(headers, R.STATUS_MESSAGE_COOKIE));
	}

	@Test
	public void anUnencodedListFromAnOlderServerStillParses() {
		Header[] headers = { new BasicHeader("Set-Cookie", "New_ID=12,34,56; Path=/starexec") };
		assertArrayEquals(new String[] { "12", "34", "56" },
				HTMLParser.extractMultipartCookie(headers, "New_ID"));
	}

	@Test
	public void aValueThatIsNotValidEncodingIsKeptAsSent() {
		Header[] headers = { new BasicHeader("Set-Cookie", "STATUS=quota at 100%; Path=/starexec") };
		assertEquals("quota at 100%", HTMLParser.extractCookie(headers, "STATUS"));
	}
}
