package org.starexec.servlets;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.security.UploadSecurity;
import org.starexec.util.SessionUtil;
import org.starexec.util.Util;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies legacy upload servlets sanitize unexpected exception messages before calling sendError.
 */
public class UploadServletErrorSanitizationTest {

	@Test
	public void uploadSpaceXmlSanitizesUnexpectedExceptionMessage() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getContentType()).thenReturn("multipart/form-data; boundary=test");

		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<Util> util = Mockito.mockStatic(Util.class)) {
			session.when(() -> SessionUtil.getUserId(request)).thenReturn(7);
			util.when(() -> Util.parseMultipartRequest(request))
					.thenThrow(new RuntimeException("jdbc:postgresql://secret-host:5432/starexec"));

			Method doPost = UploadSpaceXML.class.getDeclaredMethod(
					"doPost", HttpServletRequest.class, HttpServletResponse.class);
			doPost.setAccessible(true);
			doPost.invoke(new UploadSpaceXML(), request, response);

			verify(response).sendError(
					eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR),
					eq("Failed to upload Space XML"));
		}
	}

	@Test
	public void uploadConfigurationSanitizesUnexpectedExceptionMessage() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getContentType()).thenReturn("multipart/form-data; boundary=test");

		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<UploadSecurity> uploadSecurity = Mockito.mockStatic(UploadSecurity.class);
			 MockedStatic<Util> util = Mockito.mockStatic(Util.class)) {
			session.when(() -> SessionUtil.getUserId(request)).thenReturn(7);
			uploadSecurity.when(UploadSecurity::uploadsFrozen).thenReturn(false);
			util.when(() -> Util.parseMultipartRequest(request))
					.thenThrow(new RuntimeException("internal-path=/var/lib/starexec/secrets"));

			Method doPost = UploadConfiguration.class.getDeclaredMethod(
					"doPost", HttpServletRequest.class, HttpServletResponse.class);
			doPost.setAccessible(true);
			doPost.invoke(new UploadConfiguration(), request, response);

			verify(response).sendError(
					eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR),
					eq("Internal error adding configuration"));
		}
	}
}
