package org.starexec.servlets;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.starexec.data.database.Requests;
import org.starexec.data.database.Users;
import org.starexec.util.Mail;
import org.starexec.util.SessionUtil;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Regression coverage for address-free email-change confirmations. */
public class VerifyEmailChangePrivacyTest {

	@Test
	public void successfulChangeRedirectContainsNoAddressOrQuery() throws Exception {
		String address = "synthetic-redirect@example.invalid";
		String code = "synthetic-change-code";
		int userId = 42;
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(request.getParameter(Mail.CHANGE_EMAIL_CODE)).thenReturn(code);

		try (MockedStatic<SessionUtil> sessions = mockStatic(SessionUtil.class);
		     MockedStatic<Requests> requests = mockStatic(Requests.class);
		     MockedStatic<Users> users = mockStatic(Users.class)) {
			sessions.when(() -> SessionUtil.getUserId(request)).thenReturn(userId);
			requests.when(() -> Requests.changeEmailRequestExists(userId)).thenReturn(true);
			requests.when(() -> Requests.getChangeEmailRequest(userId)).thenReturn(Pair.of(address, code));

			new Verify().doGet(request, response);

			ArgumentCaptor<String> redirect = ArgumentCaptor.forClass(String.class);
			verify(response).sendRedirect(redirect.capture());
			String location = redirect.getValue();
			assertTrue(location.endsWith("public/messages/email_changed.jsp"));
			assertFalse("Redirect must not disclose the changed address", location.contains(address));
			assertFalse("Confirmation redirect must have no query", location.contains("?"));
			users.verify(() -> Users.updateEmail(userId, address));
			requests.verify(() -> Requests.deleteChangeEmailRequest(userId));
		}
	}

	@Test
	public void confirmationPageDoesNotReadOrRenderAnAddressParameter() throws Exception {
		Path page = Path.of("src/main/webapp/public/messages/email_changed.jsp");
		String source = Files.readString(page, StandardCharsets.UTF_8);

		assertFalse(source.contains("getParameter(\"email\")"));
		assertFalse(source.contains("escapeXml(email)"));
		assertFalse(source.contains("${email}"));
		assertTrue(source.contains("successfully changed your email address"));
	}
}
