package org.starexec.util;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.apache.commons.io.FileUtils;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.starexec.data.database.ErrorLogs;
import org.starexec.data.database.RunscriptErrors;
import org.starexec.data.to.User;
import org.starexec.logger.StarLevel;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Regression coverage for recipient privacy in mail diagnostics. */
public class MailRecipientLoggingRegressionTest {

	@Test
	public void mailSuccessDoesNotLogRecipient() throws Exception {
		String address = "synthetic-mail-success@example.invalid";
		try (MockedConstruction<SimpleEmail> emails = mockConstruction(SimpleEmail.class)) {
			assertNoLeak(address, false, () -> Mail.mail("Synthetic body", "Synthetic subject", address));
			assertEquals(1, emails.constructed().size());
			verify(emails.constructed().get(0)).addTo(address);
			verify(emails.constructed().get(0)).send();
		}
	}

	@Test
	public void mailFailureDoesNotLogRecipientOrTransportEcho() throws Exception {
		String address = "synthetic-mail-failure@example.invalid";
		try (MockedConstruction<SimpleEmail> emails = mockConstruction(SimpleEmail.class,
				(mock, context) -> when(mock.send()).thenThrow(new EmailException("Rejected recipient: " + address)))) {
			assertNoLeak(address, true, () -> Mail.mail("Synthetic body", "Synthetic subject", address));
			assertEquals(1, emails.constructed().size());
			verify(emails.constructed().get(0)).addTo(address);
		}
	}

	@Test
	public void mailCollectionFailureDoesNotAttachRecipientEcho() throws Exception {
		String address = "synthetic-collection-failure@example.invalid";
		AbstractCollection<String> recipients = new AbstractCollection<>() {
			@Override
			public Iterator<String> iterator() {
				return List.of(address).iterator();
			}

			@Override
			public int size() {
				throw new IllegalStateException("Recipient: " + address);
			}
		};

		assertNoLeak(address, true, () -> Mail.mail("Synthetic body", "Synthetic subject", recipients));
	}

	@Test
	public void activationSuccessDoesNotLogRecipient() throws Exception {
		String address = "synthetic-activation-success@example.invalid";
		User user = syntheticUser(address);
		try (MockedStatic<FileUtils> files = mockStatic(FileUtils.class);
		     MockedConstruction<SimpleEmail> emails = mockConstruction(SimpleEmail.class)) {
			files.when(() -> FileUtils.readFileToString(any(File.class), eq(StandardCharsets.UTF_8)))
					.thenReturn("Activate using $$LINK$$ for $$USER$$");
			assertNoLeak(address, false, () -> Mail.sendActivationCode(user, "synthetic-code"));
			verify(emails.constructed().get(0)).addTo(address);
		}
	}

	@Test
	public void activationTemplateFailureDoesNotAttachRecipientEcho() throws Exception {
		String address = "synthetic-activation-failure@example.invalid";
		User user = syntheticUser(address);
		try (MockedStatic<FileUtils> files = mockStatic(FileUtils.class)) {
			files.when(() -> FileUtils.readFileToString(any(File.class), eq(StandardCharsets.UTF_8)))
					.thenThrow(new IOException("Recipient: " + address));
			assertNoLeak(address, true, () -> Mail.sendActivationCode(user, "synthetic-code"));
		}
	}

	@Test
	public void emailChangeSuccessDoesNotLogRecipient() throws Exception {
		String address = "synthetic-change-success@example.invalid";
		try (MockedStatic<FileUtils> files = mockStatic(FileUtils.class);
		     MockedConstruction<SimpleEmail> emails = mockConstruction(SimpleEmail.class)) {
			files.when(() -> FileUtils.readFileToString(any(File.class), eq(StandardCharsets.UTF_8)))
					.thenReturn("Change using $$LINK$$");
			assertNoLeak(address, false, () -> Mail.sendEmailChangeValidation(address, "synthetic-code"));
			verify(emails.constructed().get(0)).addTo(address);
		}
	}

	@Test
	public void emailChangeTemplateFailureDoesNotAttachRecipientEcho() throws Exception {
		String address = "synthetic-change-failure@example.invalid";
		try (MockedStatic<FileUtils> files = mockStatic(FileUtils.class)) {
			files.when(() -> FileUtils.readFileToString(any(File.class), eq(StandardCharsets.UTF_8)))
					.thenThrow(new IOException("Recipient: " + address));
			assertNoLeak(address, true, () -> Mail.sendEmailChangeValidation(address, "synthetic-code"));
		}
	}

	@Test
	public void errorReportNotificationDoesNotLogRecipients() throws Exception {
		String address = "synthetic-report@example.invalid";
		User user = syntheticUser(address);
		try (MockedStatic<RunscriptErrors> errors = mockStatic(RunscriptErrors.class);
		     MockedConstruction<SimpleEmail> emails = mockConstruction(SimpleEmail.class)) {
			errors.when(() -> RunscriptErrors.getCount(any(), any())).thenReturn(0);
			assertNoLeak(address, false, () -> Mail.sendErrorLogEmails(List.of(), List.of(user)));
			verify(emails.constructed().get(0)).addTo(address);
		}
	}

	private static User syntheticUser(String address) {
		User user = mock(User.class);
		when(user.getId()).thenReturn(42);
		when(user.getFullName()).thenReturn("Synthetic User");
		when(user.getEmail()).thenReturn(address);
		return user;
	}

	private static void assertNoLeak(String address, boolean expectPersistedFailure, ThrowingRunnable operation)
			throws Exception {
		List<String> persistedMessages = new ArrayList<>();
		try (LogCapture logs = new LogCapture(Mail.class);
		     MockedStatic<ErrorLogs> errors = mockStatic(ErrorLogs.class)) {
			errors.when(() -> ErrorLogs.add(any(String.class), any(StarLevel.class))).thenAnswer(invocation -> {
				persistedMessages.add(invocation.getArgument(0, String.class));
				return Optional.of(1);
			});
			operation.run();

			assertFalse("Expected a mail diagnostic", logs.events.isEmpty());
			if (expectPersistedFailure) {
				assertFalse("Expected the mail failure to be persisted", persistedMessages.isEmpty());
			}
			assertFalse("Recipient leaked into a formatted log message",
					logs.events.stream().anyMatch(event -> contains(event.getFormattedMessage(), address)));
			assertFalse("Recipient leaked through a logged throwable",
					logs.events.stream().anyMatch(event -> throwableContains(event.getThrowableProxy(), address)));
			assertFalse("Recipient leaked through ErrorLogs",
					persistedMessages.stream().anyMatch(message -> contains(message, address)));
		}
	}

	private static boolean throwableContains(IThrowableProxy throwable, String value) {
		if (throwable == null) {
			return false;
		}
		if (contains(throwable.getMessage(), value) || throwableContains(throwable.getCause(), value)) {
			return true;
		}
		IThrowableProxy[] suppressed = throwable.getSuppressed();
		if (suppressed != null) {
			for (IThrowableProxy candidate : suppressed) {
				if (throwableContains(candidate, value)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean contains(String text, String value) {
		return text != null && text.contains(value);
	}

	private static final class LogCapture implements AutoCloseable {
		private final Logger logger;
		private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		private final boolean additive;
		private final List<ILoggingEvent> events = appender.list;

		private LogCapture(Class<?> loggerClass) {
			logger = (Logger) LoggerFactory.getLogger(loggerClass);
			additive = logger.isAdditive();
			appender.start();
			logger.setAdditive(false);
			logger.addAppender(appender);
		}

		@Override
		public void close() {
			logger.detachAppender(appender);
			logger.setAdditive(additive);
			appender.stop();
		}
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
