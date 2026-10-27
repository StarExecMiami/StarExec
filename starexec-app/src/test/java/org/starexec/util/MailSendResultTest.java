package org.starexec.util;

import org.apache.commons.io.FileUtils;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.starexec.data.database.ErrorLogs;
import org.starexec.data.to.User;
import org.starexec.logger.StarLevel;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A page can tell a person an email was sent only if Mail says it was.
 *
 * <p>Mail.mail() returned nothing and swallowed every failure, so the password reset page said "An
 * email has been sent" and the registration page said to expect an activation email even when the
 * SMTP server could not be reached. These tests pin what each method now reports. "Sent" means the
 * SMTP server accepted the message: commons-email's send() throws when it does not.
 */
public class MailSendResultTest {

	private static final String TO = "synthetic-result@example.invalid";

	@Test
	public void anAcceptedMessageIsReportedSent() throws Exception {
		try (MockedStatic<ErrorLogs> logs = quietErrorLogs();
		     MockedConstruction<SimpleEmail> emails = mockConstruction(SimpleEmail.class)) {
			assertTrue(Mail.mail("body", "subject", TO));
			verify(emails.constructed().get(0)).send();
		}
	}

	/** Only send() decides: a recipient list that fails once the message is accepted changes nothing. */
	@Test
	public void anAcceptedMessageIsReportedSentWhateverTheRecipientListDoesAfterwards() throws Exception {
		try (MockedStatic<ErrorLogs> logs = quietErrorLogs();
		     MockedConstruction<SimpleEmail> emails = mockConstruction(SimpleEmail.class)) {
			assertEquals(1, Mail.mail("body", "subject", sizeWorksOnce(TO)));
			verify(emails.constructed().get(0)).send();
		}
	}

	@Test
	public void aRefusedMessageIsReportedNotSent() {
		try (MockedStatic<ErrorLogs> logs = quietErrorLogs();
		     MockedConstruction<SimpleEmail> emails = refusingEmails()) {
			assertFalse(Mail.mail("body", "subject", TO));
			assertEquals(1, emails.constructed().size());
		}
	}

	@Test
	public void noRecipientsIsReportedNotSent() {
		try (MockedConstruction<SimpleEmail> emails = mockConstruction(SimpleEmail.class)) {
			assertEquals(0, Mail.mail("body", "subject", List.of()));
			assertTrue("nothing may be built for no recipients", emails.constructed().isEmpty());
		}
	}

	@Test
	public void activationReportsWhetherItWasSent() {
		try (MockedStatic<ErrorLogs> logs = quietErrorLogs();
		     MockedStatic<FileUtils> files = template("Activate $$USER$$ at $$LINK$$")) {
			try (MockedConstruction<SimpleEmail> emails = mockConstruction(SimpleEmail.class)) {
				assertTrue(Mail.sendActivationCode(user(), "code"));
			}
			try (MockedConstruction<SimpleEmail> emails = refusingEmails()) {
				assertFalse(Mail.sendActivationCode(user(), "code"));
			}
		}
	}

	@Test
	public void activationWithAnUnreadableTemplateIsReportedNotSent() {
		try (MockedStatic<ErrorLogs> logs = quietErrorLogs();
		     MockedStatic<FileUtils> files = unreadableTemplate();
		     MockedConstruction<SimpleEmail> emails = mockConstruction(SimpleEmail.class)) {
			assertFalse(Mail.sendActivationCode(user(), "code"));
			assertTrue(emails.constructed().isEmpty());
		}
	}

	@Test
	public void newUserPasswordReportsWhetherItWasSent() {
		try (MockedStatic<ErrorLogs> logs = quietErrorLogs();
		     MockedStatic<FileUtils> files = template("Welcome $$USER$$: $$PASS$$")) {
			try (MockedConstruction<SimpleEmail> emails = mockConstruction(SimpleEmail.class)) {
				assertTrue(Mail.sendPassword(user(), "synthetic-password"));
			}
			try (MockedConstruction<SimpleEmail> emails = refusingEmails()) {
				assertFalse(Mail.sendPassword(user(), "synthetic-password"));
			}
		}
	}

	@Test
	public void newUserPasswordWithAnUnreadableTemplateIsReportedNotSent() {
		try (MockedStatic<ErrorLogs> logs = quietErrorLogs();
		     MockedStatic<FileUtils> files = unreadableTemplate();
		     MockedConstruction<SimpleEmail> emails = mockConstruction(SimpleEmail.class)) {
			assertFalse(Mail.sendPassword(user(), "synthetic-password"));
			assertTrue(emails.constructed().isEmpty());
		}
	}

	@Test
	public void passwordResetReportsWhetherItWasSent() throws IOException {
		try (MockedStatic<ErrorLogs> logs = quietErrorLogs();
		     MockedStatic<FileUtils> files = template("Reset $$USER$$ at $$LINK$$")) {
			try (MockedConstruction<SimpleEmail> emails = mockConstruction(SimpleEmail.class)) {
				assertTrue(Mail.sendPasswordReset(user(), "code"));
			}
			try (MockedConstruction<SimpleEmail> emails = refusingEmails()) {
				assertFalse(Mail.sendPasswordReset(user(), "code"));
			}
		}
	}

	/** One address whose size() answers once and then throws, so only a call after send() can fail. */
	private static Collection<String> sizeWorksOnce(String address) {
		return new AbstractCollection<String>() {
			private int sizeCalls;

			@Override
			public boolean isEmpty() {
				return false;
			}

			@Override
			public int size() {
				if (++sizeCalls > 1) {
					throw new IllegalStateException("synthetic failure after the first size()");
				}
				return 1;
			}

			@Override
			public Iterator<String> iterator() {
				return List.of(address).iterator();
			}
		};
	}

	private static MockedConstruction<SimpleEmail> refusingEmails() {
		return mockConstruction(SimpleEmail.class,
				(mock, context) -> when(mock.send()).thenThrow(new EmailException("synthetic refusal")));
	}

	private static MockedStatic<FileUtils> template(String text) {
		MockedStatic<FileUtils> files = mockStatic(FileUtils.class);
		files.when(() -> FileUtils.readFileToString(any(File.class), eq(StandardCharsets.UTF_8))).thenReturn(text);
		return files;
	}

	private static MockedStatic<FileUtils> unreadableTemplate() {
		MockedStatic<FileUtils> files = mockStatic(FileUtils.class);
		files.when(() -> FileUtils.readFileToString(any(File.class), eq(StandardCharsets.UTF_8)))
				.thenThrow(new IOException("synthetic unreadable template"));
		return files;
	}

	/** A WARN is persisted through ErrorLogs, which needs a database; keep it out of a unit test. */
	private static MockedStatic<ErrorLogs> quietErrorLogs() {
		MockedStatic<ErrorLogs> errors = mockStatic(ErrorLogs.class);
		errors.when(() -> ErrorLogs.add(any(String.class), any(StarLevel.class))).thenReturn(Optional.of(1));
		return errors;
	}

	private static User user() {
		User user = mock(User.class);
		when(user.getId()).thenReturn(42);
		when(user.getFullName()).thenReturn("Synthetic User");
		when(user.getEmail()).thenReturn(TO);
		return user;
	}
}
