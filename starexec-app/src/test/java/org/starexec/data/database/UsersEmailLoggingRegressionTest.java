package org.starexec.data.database;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.postgresql.util.PSQLException;
import org.slf4j.LoggerFactory;
import org.starexec.exceptions.StarExecDatabaseException;
import org.starexec.logger.StarLevel;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Regression coverage for email-address handling in the user database layer. */
public class UsersEmailLoggingRegressionTest {

	@Test
	public void lookupBindsAddressWithoutLoggingIt() throws Exception {
		String address = "synthetic-lookup@example.invalid";
		Connection connection = mock(Connection.class);
		PreparedStatement statement = mock(PreparedStatement.class);
		ResultSet results = mock(ResultSet.class);
		when(connection.prepareStatement(anyString())).thenReturn(statement);
		when(statement.executeQuery()).thenReturn(results);
		when(results.next()).thenReturn(true);

		try (MockedStatic<Common> common = mockStatic(Common.class)) {
			common.when(Common::getConnection).thenReturn(connection);
			assertNoLeak(address, false, () -> assertTrue(Users.getUserByEmail(address)));
			verify(statement).setString(1, address);
		}
	}

	@Test
	public void lookupFailureDoesNotLogDatabaseEchoOfAddress() throws Exception {
		String address = "synthetic-lookup-failure@example.invalid";
		Connection connection = mock(Connection.class);
		PreparedStatement statement = mock(PreparedStatement.class);
		when(connection.prepareStatement(anyString())).thenReturn(statement);
		when(statement.executeQuery()).thenThrow(new SQLException("Database parameter: " + address));

		try (MockedStatic<Common> common = mockStatic(Common.class)) {
			common.when(Common::getConnection).thenReturn(connection);
			assertNoLeak(address, true, () -> assertFalse(Users.getUserByEmail(address)));
			verify(statement).setString(1, address);
		}
	}

	@Test
	public void updateBindsAddressWithoutLoggingIt() throws Exception {
		String address = "synthetic-update@example.invalid";
		Connection connection = mock(Connection.class);
		PreparedStatement statement = mock(PreparedStatement.class);
		when(connection.prepareStatement(anyString())).thenReturn(statement);

		try (MockedStatic<Common> common = mockStatic(Common.class)) {
			common.when(Common::getConnection).thenReturn(connection);
			assertNoLeak(address, false, () -> Users.updateEmail(42, address));
			verify(statement).setInt(1, 42);
			verify(statement).setString(2, address);
			verify(statement).execute();
		}
	}

	@Test
	public void updateFailureDoesNotLogDatabaseEchoOfAddress() throws Exception {
		String address = "synthetic-update-failure@example.invalid";
		Connection connection = mock(Connection.class);
		PreparedStatement statement = mock(PreparedStatement.class);
		when(connection.prepareStatement(anyString())).thenReturn(statement);
		when(statement.execute()).thenThrow(new SQLException("Database parameter: " + address));

		try (MockedStatic<Common> common = mockStatic(Common.class)) {
			common.when(Common::getConnection).thenReturn(connection);
			assertNoLeak(address, true, () -> Users.updateEmail(42, address));
			verify(statement).setString(2, address);
		}
	}

	@Test
	public void missingUserFailureDoesNotPropagateDatabaseEchoOfAddress() throws Exception {
		String address = "synthetic-missing-user@example.invalid";
		Connection connection = mock(Connection.class);
		PreparedStatement statement = mock(PreparedStatement.class);
		PSQLException echoed = mock(PSQLException.class);
		when(echoed.getSQLState()).thenReturn("P0002");
		when(echoed.getMessage()).thenReturn("Database parameter: " + address);
		when(connection.prepareStatement(anyString())).thenReturn(statement);
		when(statement.execute()).thenThrow(echoed);

		try (MockedStatic<Common> common = mockStatic(Common.class)) {
			common.when(Common::getConnection).thenReturn(connection);
			StarExecDatabaseException thrown = null;
			try {
				Users.updateEmail(42, address);
				fail("Expected a missing user to retain the database-error contract");
			} catch (StarExecDatabaseException e) {
				thrown = e;
			}

			assertNotNull(thrown);
			assertFalse("Address leaked through exception or cause", throwableContains(thrown, address));
			verify(statement).setString(2, address);
		}
	}

	private static void assertNoLeak(String address, boolean expectPersistedFailure, ThrowingRunnable operation)
			throws Exception {
		List<String> persistedMessages = new ArrayList<>();
		try (LogCapture logs = new LogCapture(Users.class);
		     MockedStatic<ErrorLogs> errors = mockStatic(ErrorLogs.class)) {
			errors.when(() -> ErrorLogs.add(anyString(), any(StarLevel.class))).thenAnswer(invocation -> {
				persistedMessages.add(invocation.getArgument(0, String.class));
				return Optional.of(1);
			});
			operation.run();

			if (expectPersistedFailure) {
				assertFalse("Expected the database failure to be logged", logs.events.isEmpty());
				assertFalse("Expected the database failure to be persisted", persistedMessages.isEmpty());
			}
			assertFalse("Address leaked into a formatted log message",
					logs.events.stream().anyMatch(event -> contains(event.getFormattedMessage(), address)));
			assertFalse("Address leaked through a logged throwable",
					logs.events.stream().anyMatch(event -> throwableContains(event.getThrowableProxy(), address)));
			assertFalse("Address leaked through ErrorLogs",
					persistedMessages.stream().anyMatch(message -> contains(message, address)));
		}
	}

	private static boolean throwableContains(Throwable throwable, String value) {
		if (throwable == null) {
			return false;
		}
		if (contains(throwable.getMessage(), value) || contains(throwable.toString(), value)
				|| throwableContains(throwable.getCause(), value)) {
			return true;
		}
		for (Throwable suppressed : throwable.getSuppressed()) {
			if (throwableContains(suppressed, value)) {
				return true;
			}
		}
		return false;
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
		private final Level level;
		private final List<ILoggingEvent> events = appender.list;

		private LogCapture(Class<?> loggerClass) {
			logger = (Logger) LoggerFactory.getLogger(loggerClass);
			additive = logger.isAdditive();
			level = logger.getLevel();
			appender.start();
			logger.setLevel(Level.DEBUG);
			logger.setAdditive(false);
			logger.addAppender(appender);
		}

		@Override
		public void close() {
			logger.detachAppender(appender);
			logger.setAdditive(additive);
			logger.setLevel(level);
			appender.stop();
		}
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
