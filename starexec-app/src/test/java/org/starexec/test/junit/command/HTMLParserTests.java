package org.starexec.test.junit.command;

import org.apache.http.Header;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.starexec.command.HTMLParser;

import java.util.HashMap;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class HTMLParserTests {

	private Header getMockHeader(String name, String value) {
		Header h = mock(Header.class);
		when(h.getName()).thenReturn(name);
		when(h.getValue()).thenReturn(value);
		return h;
	}

	@Test
	public void testExtractCookie() {
		Header h = getMockHeader("Set-Cookie", "first=fake cookie;second=another cookie");
		Header[] headers = new Header[3];
		headers[0] = getMockHeader("a", "b");
		headers[1] = h;
		headers[2] = getMockHeader("c", "d");
		Assertions.assertEquals("fake cookie", HTMLParser.extractCookie(headers, "first"));
		Assertions.assertEquals("another cookie", HTMLParser.extractCookie(headers, "second"));
		Assertions.assertNull(HTMLParser.extractCookie(headers, "third"));
		Assertions.assertNull(HTMLParser.extractCookie(null, "first"));
	}

	@Test
	public void testExtractMultipartCookie() {
		Header h = getMockHeader("Set-Cookie", "first=a,b,c;second=d");
		Header[] headers = new Header[3];
		headers[0] = getMockHeader("a", "b");
		headers[1] = h;
		headers[2] = getMockHeader("c", "d");
		Assertions.assertArrayEquals(new String[] { "a", "b", "c" }, HTMLParser.extractMultipartCookie(headers, "first"));
		Assertions.assertArrayEquals(new String[] { "d" }, HTMLParser.extractMultipartCookie(headers, "second"));
		Assertions.assertArrayEquals(null, HTMLParser.extractMultipartCookie(headers, "third"));
		Assertions.assertArrayEquals(null, HTMLParser.extractMultipartCookie(null, "first"));
	}

	@Test
	public void testURLEncode() {
		HashMap<String, String> params = new HashMap<>();
		String baseURL = "http://www.test.com/";

		Assertions.assertEquals(baseURL, HTMLParser.URLEncode(baseURL, params));
		params.put("name", "value");
		Assertions.assertEquals(baseURL + "?name=value", HTMLParser.URLEncode(baseURL, params));
		params.put("next", "param");
		String url = HTMLParser.URLEncode(baseURL, params);
		Assertions.assertTrue(url.contains("name=value") && url.contains("next=param"));
		Assertions.assertTrue(url.contains("&name") ^ url.contains("&next"));
	}

	@Test
	public void testExtractNameFromJson() {
		Assertions.assertNull(HTMLParser.extractNameFromJson("", "spaces"));
		Assertions.assertNull(HTMLParser.extractNameFromJson("", "solvers"));

		Assertions.assertEquals("SPACE", HTMLParser.extractNameFromJson("<a onclick=\"openSpace()\">SPACE</a>", "spaces"));
		Assertions.assertEquals("SOLVER", HTMLParser.extractNameFromJson("<a target=\"_blank\">SOLVER</a>", "solvers"));
	}

	@Test
	public void testExtractIDFromJson() {
		Assertions.assertNull(HTMLParser.extractIDFromJson(""));
		Assertions.assertNull(HTMLParser.extractIDFromJson(null));
		Assertions.assertNull(HTMLParser.extractIDFromJson("< name=one >"));
		Assertions.assertNull(HTMLParser.extractIDFromJson("<value=\"1\">< type=\"hidden\" >"));

		Assertions.assertEquals(3,
				(int) HTMLParser.extractIDFromJson("<input value=\"1\"><input value=\"3\"type=\"hidden\" >"));
	}
}
