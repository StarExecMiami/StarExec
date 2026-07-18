package org.starexec.data.security;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class GeneralSecurityEncodingTests {

	@Test
	public void htmlEncodingUsesExpectedEntitiesForSpecialCharacters() {
		assertEquals("&amp;&lt;&gt;&quot;&#x27;&#x5c;",
				GeneralSecurity.getHTMLSafeString("&<>\"'\\"));
	}

	@Test
	public void htmlAttributeEncodingEscapesWhitespaceAndBreakoutCharacters() {
		assertEquals("&#x20;&amp;&lt;&gt;&quot;&#x27;&#x5c;",
				GeneralSecurity.getHTMLAttributeSafeString(" &<>\"'\\"));
	}

	@Test
	public void javascriptEncodingEscapesControlAndLineSeparatorCharacters() {
		assertEquals("\\x22\\x27\\x5C\\x0A\\u2028\\u2029\\x26",
				GeneralSecurity.getJavascriptSafeString("\"'\\\n\u2028\u2029&"));
	}

	@Test
	public void encodersPreserveEmptyInputAndReturnNullForNullInput() {
		assertEquals("", GeneralSecurity.getHTMLSafeString(""));
		assertEquals("", GeneralSecurity.getHTMLAttributeSafeString(""));
		assertEquals("", GeneralSecurity.getJavascriptSafeString(""));

		assertNull(GeneralSecurity.getHTMLSafeString(null));
		assertNull(GeneralSecurity.getHTMLAttributeSafeString(null));
		assertNull(GeneralSecurity.getJavascriptSafeString(null));
	}
}
