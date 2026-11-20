package org.starexec.data.database;

import org.junit.jupiter.api.Test;
import org.starexec.data.security.GeneralSecurity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StatusMessageHtmlTest {

	@Test
	public void formatStatusHtmlEscapesMessageAndSafeUrl() {
		String rawUrl = "https://example.org/notice?a=1&b='x'";
		String html = StatusMessage.formatStatusHtml(
				"<script>alert(1)</script>Maintenance",
				rawUrl);
		assertFalse(html.contains("<script>"));
		assertFalse(html.contains("b='x'"));
		assertEquals(
				"<div class='status-message'><p>&lt;script&gt;alert&#x28;1&#x29;&lt;&#x2f;script&gt;Maintenance"
						+ "<a href='" + GeneralSecurity.getHTMLAttributeSafeString(rawUrl)
						+ "'>More Information</a></p></div>",
				html);
		assertTrue(html.contains("&#x27;x&#x27;"));
	}

	@Test
	public void formatStatusHtmlRejectsUnsafeUrlSchemes() {
		String html = StatusMessage.formatStatusHtml("Notice", "javascript:alert(1)");
		assertFalse(html.contains("javascript:"));
		assertFalse(html.contains("<a "));
		assertTrue(html.contains("<div class='status-message'><p>Notice</p></div>"));
	}

	@Test
	public void formatStatusHtmlHandlesNullInputs() {
		String html = StatusMessage.formatStatusHtml(null, null);
		assertTrue(html.contains("<div class='status-message'><p></p></div>"));
	}
}
