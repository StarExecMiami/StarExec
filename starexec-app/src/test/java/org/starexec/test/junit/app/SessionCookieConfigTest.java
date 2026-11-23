package org.starexec.test.junit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.servlet.ServletContext;
import javax.servlet.SessionCookieConfig;
import javax.servlet.http.Cookie;

import org.apache.tomcat.util.http.Rfc6265CookieProcessor;
import org.junit.jupiter.api.Test;
import org.starexec.app.Starexec;
import org.starexec.config.EnvironmentConfig;
import org.starexec.util.Util;

/**
 * Verifies session and application cookie security policy for HTTPS production
 * versus local HTTP development (#241).
 */
public class SessionCookieConfigTest {

	@Test
	public void secureCookiesFollowsExplicitOverrideOrHttpsUrlPrefix() {
		assertFalse(EnvironmentConfig.resolveSecureCookies(null, "http"));
		assertFalse(EnvironmentConfig.resolveSecureCookies("", "http"));
		assertTrue(EnvironmentConfig.resolveSecureCookies(null, "https"));
		assertTrue(EnvironmentConfig.resolveSecureCookies("  ", "HTTPS"));
		assertTrue(EnvironmentConfig.resolveSecureCookies("true", "http"));
		assertFalse(EnvironmentConfig.resolveSecureCookies("false", "https"));
	}

	@Test
	public void productionModeSetsHttpOnlyAndSecureWhilePreservingSameSiteLax() {
		ServletContext context = mock(ServletContext.class);
		SessionCookieConfig sessionCookieConfig = new StubSessionCookieConfig();
		when(context.getSessionCookieConfig()).thenReturn(sessionCookieConfig);

		Starexec.configureSessionCookieConfig(context, true);

		assertTrue(sessionCookieConfig.isHttpOnly());
		assertTrue(sessionCookieConfig.isSecure());

		Rfc6265CookieProcessor processor = new Rfc6265CookieProcessor();
		processor.setSameSiteCookies("lax");

		Cookie jsessionid = new Cookie("JSESSIONID", "ABC123");
		jsessionid.setPath("/starexec");
		jsessionid.setHttpOnly(sessionCookieConfig.isHttpOnly());
		jsessionid.setSecure(sessionCookieConfig.isSecure());

		String header = processor.generateHeader(jsessionid, null);
		assertTrue(header.contains("HttpOnly"), "Expected HttpOnly in " + header);
		assertTrue(header.contains("Secure"), "Expected Secure in " + header);
		assertTrue(header.contains("SameSite=Lax"), "Expected SameSite=Lax in " + header);

		Cookie appCookie = Util.withSecurityAttributes(new Cookie("New_ID", "42"), true);
		String appHeader = processor.generateHeader(appCookie, null);
		assertTrue(appHeader.contains("HttpOnly"), "Expected HttpOnly in " + appHeader);
		assertTrue(appHeader.contains("Secure"), "Expected Secure in " + appHeader);
		assertTrue(appHeader.contains("SameSite=Lax"), "Expected SameSite=Lax in " + appHeader);
	}

	@Test
	public void localHttpModeKeepsHttpOnlyAndSameSiteLaxWithoutForcingSecure() {
		ServletContext context = mock(ServletContext.class);
		SessionCookieConfig sessionCookieConfig = new StubSessionCookieConfig();
		when(context.getSessionCookieConfig()).thenReturn(sessionCookieConfig);

		Starexec.configureSessionCookieConfig(context, false);

		assertTrue(sessionCookieConfig.isHttpOnly());
		assertFalse(sessionCookieConfig.isSecure());

		Rfc6265CookieProcessor processor = new Rfc6265CookieProcessor();
		processor.setSameSiteCookies("lax");

		Cookie jsessionid = new Cookie("JSESSIONID", "ABC123");
		jsessionid.setPath("/starexec");
		jsessionid.setHttpOnly(sessionCookieConfig.isHttpOnly());
		jsessionid.setSecure(sessionCookieConfig.isSecure());

		String header = processor.generateHeader(jsessionid, null);
		assertTrue(header.contains("HttpOnly"), "Expected HttpOnly in " + header);
		assertFalse(header.contains("Secure"), "Did not expect Secure in " + header);
		assertTrue(header.contains("SameSite=Lax"), "Expected SameSite=Lax in " + header);

		Cookie appCookie = Util.withSecurityAttributes(new Cookie("New_ID", "42"), false);
		assertFalse(appCookie.getSecure());
		assertTrue(appCookie.isHttpOnly());
	}

	@Test
	public void configureSessionCookieConfigHandlesNullSafely() {
		Starexec.configureSessionCookieConfig(null, true);
		ServletContext context = mock(ServletContext.class);
		when(context.getSessionCookieConfig()).thenReturn(null);
		Starexec.configureSessionCookieConfig(context, true);
		verify(context).getSessionCookieConfig();
		assertEquals(false, EnvironmentConfig.resolveSecureCookies("false", "https"));
	}

	private static final class StubSessionCookieConfig implements SessionCookieConfig {
		private String name;
		private String domain;
		private String path;
		private String comment;
		private boolean httpOnly;
		private boolean secure;
		private int maxAge = -1;

		@Override public void setName(String name) { this.name = name; }
		@Override public String getName() { return name; }
		@Override public void setDomain(String domain) { this.domain = domain; }
		@Override public String getDomain() { return domain; }
		@Override public void setPath(String path) { this.path = path; }
		@Override public String getPath() { return path; }
		@Override public void setComment(String comment) { this.comment = comment; }
		@Override public String getComment() { return comment; }
		@Override public void setHttpOnly(boolean httpOnly) { this.httpOnly = httpOnly; }
		@Override public boolean isHttpOnly() { return httpOnly; }
		@Override public void setSecure(boolean secure) { this.secure = secure; }
		@Override public boolean isSecure() { return secure; }
		@Override public void setMaxAge(int maxAge) { this.maxAge = maxAge; }
		@Override public int getMaxAge() { return maxAge; }
	}
}
