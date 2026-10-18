package org.starexec.servlets;

import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.starexec.data.database.Communities;
import org.starexec.data.database.Requests;
import org.starexec.data.database.Users;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.User;
import org.starexec.util.CsrfUtil;
import org.starexec.util.Mail;
import org.starexec.util.SessionUtil;
import org.starexec.util.Validator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The flows that tell a person to look for an email must not say so when the email was not sent.
 *
 * <p>Registration already had an "email_failed" outcome, but Mail swallowed every failure, so a
 * failed send never reached it, and the reset page always said "An email has been sent". Each
 * flow is driven with Mail reporting both outcomes, so the test fails if the result is ignored.
 */
public class MailFailureReportedTest {

	private static final String EMAIL = "synthetic-flow@example.invalid";
	private static final int COMMUNITY = 7;

	/** Both flows validate their form first; the application compiles the patterns at startup. */
	@BeforeClass
	public static void compilePatterns() {
		Validator.initialize();
	}

	@Test
	public void aResetWhoseEmailWasNotSentIsNotReportedSent() throws Exception {
		assertTrue(resetRedirect(false).endsWith("/public/password_reset.jsp?result=email_failed"));
	}

	@Test
	public void aResetWhoseEmailWasSentIsReportedSent() throws Exception {
		assertTrue(resetRedirect(true).endsWith("/public/password_reset.jsp?result=success"));
	}

	@Test
	public void aRegistrationWhoseActivationEmailWasNotSentSaysSo() throws Exception {
		ValidatorStatusCode status = register(false, false);
		assertTrue("the account was created", status.isSuccess());
		assertEquals("email_failed", status.getMessage());
	}

	@Test
	public void aRegistrationWhoseActivationEmailWasSentDoesNot() throws Exception {
		ValidatorStatusCode status = register(false, true);
		assertTrue(status.isSuccess());
		assertNotEquals("email_failed", status.getMessage());
	}

	@Test
	public void anAdminCreatedUserWhoseEmailWasNotSentSaysSo() throws Exception {
		ValidatorStatusCode status = register(true, false);
		assertTrue("the account was created", status.isSuccess());
		assertEquals("email_failed", status.getMessage());
	}

	@Test
	public void anAdminCreatedUserWhoseEmailWasSentDoesNot() throws Exception {
		ValidatorStatusCode status = register(true, true);
		assertTrue(status.isSuccess());
		assertNotEquals("email_failed", status.getMessage());
	}

	private static String resetRedirect(boolean sent) throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(request.getParameter(Registration.USER_FIRSTNAME)).thenReturn("Synthetic");
		when(request.getParameter(Registration.USER_LASTNAME)).thenReturn("User");
		when(request.getParameter(Registration.USER_EMAIL)).thenReturn(EMAIL);
		User user = mock(User.class);
		when(user.getId()).thenReturn(42);
		when(user.getFirstName()).thenReturn("Synthetic");
		when(user.getLastName()).thenReturn("User");
		try (MockedStatic<CsrfUtil> csrf = mockStatic(CsrfUtil.class);
		     MockedStatic<Users> users = mockStatic(Users.class);
		     MockedStatic<Requests> requests = mockStatic(Requests.class);
		     MockedStatic<Mail> mail = mockStatic(Mail.class)) {
			csrf.when(() -> CsrfUtil.validateToken(request)).thenReturn(true);
			users.when(() -> Users.get(EMAIL)).thenReturn(user);
			requests.when(() -> Requests.addPassResetRequest(eq(42), anyString())).thenReturn(true);
			mail.when(() -> Mail.sendPasswordReset(eq(user), anyString())).thenReturn(sent);
			new PasswordReset().doPost(request, response);
			mail.verify(() -> Mail.sendPasswordReset(eq(user), anyString()));
		}
		ArgumentCaptor<String> location = ArgumentCaptor.forClass(String.class);
		verify(response).sendRedirect(location.capture());
		return location.getValue();
	}

	private static ValidatorStatusCode register(boolean byAdmin, boolean sent) throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getParameter(Registration.USER_PASSWORD)).thenReturn("synthetic-Passw0rd");
		when(request.getParameter(Registration.USER_COMMUNITY)).thenReturn(Integer.toString(COMMUNITY));
		when(request.getParameter(Registration.USER_FIRSTNAME)).thenReturn("Synthetic");
		when(request.getParameter(Registration.USER_LASTNAME)).thenReturn("User");
		when(request.getParameter(Registration.USER_EMAIL)).thenReturn(EMAIL);
		when(request.getParameter(Registration.USER_INSTITUTION)).thenReturn("Synthetic Institute");
		when(request.getParameter(Registration.USER_MESSAGE)).thenReturn("Synthetic request message");
		try (MockedStatic<SessionUtil> sessions = mockStatic(SessionUtil.class);
		     MockedStatic<GeneralSecurity> security = mockStatic(GeneralSecurity.class);
		     MockedStatic<Users> users = mockStatic(Users.class);
		     MockedStatic<Communities> communities = mockStatic(Communities.class);
		     MockedStatic<Mail> mail = mockStatic(Mail.class)) {
			sessions.when(() -> SessionUtil.getUserId(request)).thenReturn(byAdmin ? 1 : -1);
			security.when(() -> GeneralSecurity.hasAdminWritePrivileges(anyInt())).thenReturn(byAdmin);
			users.when(() -> Users.getUserByEmail(EMAIL)).thenReturn(false);
			users.when(() -> Users.add(any(User.class))).thenReturn(99);
			users.when(() -> Users.associate(99, COMMUNITY)).thenReturn(true);
			users.when(() -> Users.register(any(User.class), eq(COMMUNITY), anyString(), anyString())).thenReturn(true);
			communities.when(() -> Communities.createPersonalSubspace(eq(COMMUNITY), any(User.class))).thenReturn(5);
			mail.when(() -> Mail.sendPassword(any(User.class), anyString())).thenReturn(sent);
			mail.when(() -> Mail.sendActivationCode(any(User.class), anyString())).thenReturn(sent);
			ValidatorStatusCode status = Registration.register(request);
			if (byAdmin) {
				mail.verify(() -> Mail.sendPassword(any(User.class), eq("synthetic-Passw0rd")));
			} else {
				mail.verify(() -> Mail.sendActivationCode(any(User.class), anyString()));
			}
			return status;
		}
	}
}
