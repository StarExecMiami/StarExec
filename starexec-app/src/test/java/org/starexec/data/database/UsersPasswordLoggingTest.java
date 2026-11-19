package org.starexec.data.database;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.starexec.data.to.User;
import org.starexec.logger.StarLevel;
import org.starexec.util.PasswordHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Protects account credentials while retaining safe creation failure diagnostics. */
public class UsersPasswordLoggingTest {

	private static final String PASSWORD = "synthetic-account-password";
	private static final String VERIFIER = "synthetic-account-verifier";

	@Test
	public void successfulCreationBindsVerifierWithoutLoggingCredentials() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.check(common -> {
				assertEquals(42, Users.add(fixture.user));
				assertEquals(42, fixture.user.getId());
				verify(fixture.connection).prepareStatement("SELECT AddUserAuthorized(?, ?, ?, ?, ?, ?, ?, ?)");
				verify(fixture.statement).setString(1, fixture.user.getFirstName());
				verify(fixture.statement).setString(2, fixture.user.getLastName());
				verify(fixture.statement).setString(3, fixture.user.getEmail());
				verify(fixture.statement).setString(4, fixture.user.getInstitution());
				verify(fixture.statement).setString(5, VERIFIER);
				verify(fixture.statement).setLong(6, fixture.user.getDiskQuota());
				verify(fixture.statement).setString(7, fixture.user.getRole());
				verify(fixture.statement).setInt(8, fixture.user.getPairQuota());
				verify(fixture.statement).executeQuery();
				common.verify(() -> Common.doRollback(fixture.connection), never());
				fixture.verifyResourceCleanup(common);
				assertTrue(fixture.persisted.isEmpty(), "Successful creation should not persist an error");
			});
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"23505", "42P01", "08006"})
	public void databaseFailureRetainsSqlStateWithoutCredentialEcho(String sqlState) throws Exception {
		try (Fixture fixture = new Fixture()) {
			SQLException failure = new SQLException(PASSWORD, sqlState, new IllegalStateException(VERIFIER));
			failure.addSuppressed(new IllegalArgumentException(PASSWORD + VERIFIER));
			failure.setNextException(new SQLException(VERIFIER));
			when(fixture.statement.executeQuery()).thenThrow(failure);
			fixture.check(common -> {
				assertEquals(-1, Users.add(fixture.user));
				fixture.assertDiagnostic("SQLException", sqlState);
				verify(fixture.statement).setString(5, VERIFIER);
				common.verify(() -> Common.doRollback(fixture.connection));
				common.verify(() -> Common.safeClose(fixture.connection));
				common.verify(() -> Common.safeClose(fixture.statement));
			});
		}
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"23", "235050", "ab123", "23505\r\n" + PASSWORD, VERIFIER})
	public void absentOrMalformedSqlStateCannotInjectLogData(String sqlState) throws Exception {
		try (Fixture fixture = new Fixture()) {
			when(fixture.statement.executeQuery()).thenThrow(new SQLException(PASSWORD + VERIFIER, sqlState));
			fixture.check(common -> {
				assertEquals(-1, Users.add(fixture.user));
				fixture.assertDiagnostic("SQLException", "unknown");
				assertTrue(fixture.persisted.stream().noneMatch(message -> message.contains("\r") || message.contains("\n")),
						"Invalid SQLSTATE must not inject additional log lines");
			});
		}
	}

	@Test
	public void connectionFailureDoesNotPersistDriverEchoOfCredentials() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.check(common -> {
				common.when(Common::getConnection).thenThrow(new SQLException(PASSWORD + VERIFIER, "08006"));
				assertEquals(-1, Users.add(fixture.user));
				fixture.assertDiagnostic("SQLException", "08006");
				verifyNoInteractions(fixture.connection, fixture.statement, fixture.results);
				common.verify(() -> Common.doRollback(null));
			});
		}
	}

	@Test
	public void hashingFailureRetainsExceptionTypeWithoutCredentialEcho() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.hashingFailure = new IllegalStateException(PASSWORD, new IllegalArgumentException(VERIFIER));
			fixture.check(common -> {
				assertEquals(-1, Users.add(fixture.user));
				fixture.assertDiagnostic("IllegalStateException", "unknown");
				verifyNoInteractions(fixture.statement, fixture.results);
				common.verify(() -> Common.doRollback(fixture.connection));
				common.verify(() -> Common.safeClose(fixture.connection));
			});
		}
	}

	@Test
	public void nullUserReturnsFailureWithSafeDiagnosticsAndConnectionCleanup() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.check(common -> {
				assertEquals(-1, Users.add(null));
				fixture.assertDiagnostic("NullPointerException", "unknown");
				verifyNoInteractions(fixture.statement, fixture.results);
				common.verify(() -> Common.doRollback(fixture.connection));
				common.verify(() -> Common.safeClose(fixture.connection));
			});
		}
	}

	@Test
	public void nullPasswordDatabaseFailureStillRollsBackAndClosesResources() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.user.setPassword(null);
			when(fixture.statement.executeQuery()).thenThrow(new SQLException(VERIFIER, "23502"));
			fixture.check(common -> {
				assertEquals(-1, Users.add(fixture.user));
				verify(fixture.statement).setString(5, null);
				fixture.assertDiagnostic("SQLException", "23502");
				common.verify(() -> Common.doRollback(fixture.connection));
				common.verify(() -> Common.safeClose(fixture.connection));
				common.verify(() -> Common.safeClose(fixture.statement));
			});
		}
	}

	private static final class Fixture implements AutoCloseable {
		private final User user = new User();
		private final Connection connection = mock(Connection.class);
		private final PreparedStatement statement = mock(PreparedStatement.class);
		private final ResultSet results = mock(ResultSet.class);
		private final List<String> persisted = new ArrayList<>();
		private final Logger logger = (Logger) LoggerFactory.getLogger(Users.class);
		private final Level level = logger.getLevel();
		private final boolean additive = logger.isAdditive();
		private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		private RuntimeException hashingFailure;

		private Fixture() throws SQLException {
			user.setPassword(PASSWORD);
			user.setFirstName("Synthetic");
			user.setLastName("Account");
			user.setEmail("synthetic-account@example.invalid");
			user.setInstitution("Test institution");
			user.setDiskQuota(1024L);
			user.setRole("user");
			user.setPairQuota(7);
			when(connection.prepareStatement(anyString())).thenReturn(statement);
			when(statement.executeQuery()).thenReturn(results);
			when(results.next()).thenReturn(true);
			when(results.getInt(1)).thenReturn(42);
			appender.start();
			logger.setLevel(Level.DEBUG);
			logger.setAdditive(false);
			logger.addAppender(appender);
		}

		private void check(Operation operation) throws Exception {
			try (MockedStatic<Common> common = mockStatic(Common.class);
			     MockedStatic<PasswordHasher> hasher = mockStatic(PasswordHasher.class);
			     MockedStatic<ErrorLogs> errors = mockStatic(ErrorLogs.class)) {
				common.when(Common::getConnection).thenReturn(connection);
				if (hashingFailure == null) {
					hasher.when(() -> PasswordHasher.hash(PASSWORD)).thenReturn(VERIFIER);
				} else {
					hasher.when(() -> PasswordHasher.hash(PASSWORD)).thenThrow(hashingFailure);
				}
				errors.when(() -> ErrorLogs.add(anyString(), any(StarLevel.class))).thenAnswer(invocation -> {
					persisted.add(invocation.getArgument(0, String.class));
					return Optional.of(1);
				});
				operation.run(common);
				assertFalse(appender.list.isEmpty(), "Expected account creation log events");
				for (String secret : List.of(PASSWORD, VERIFIER)) {
					assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains(secret)),
							"Authentication material appeared in a log message");
					assertTrue(appender.list.stream().noneMatch(event -> throwableContains(event.getThrowableProxy(), secret)),
							"Authentication material appeared in a logged exception");
					assertTrue(persisted.stream().noneMatch(message -> message.contains(secret)),
							"Authentication material appeared in a persisted error");
				}
			}
		}

		private void assertDiagnostic(String exceptionType, String sqlState) {
			assertFalse(persisted.isEmpty(), "Expected the creation failure to be persisted");
			assertTrue(persisted.stream().allMatch(message -> message.contains("add") && message.contains(exceptionType)
					&& message.contains("SQLState: " + sqlState)), "Expected safe operation/type/SQLSTATE diagnostics");
			assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR),
					"Expected the failure to remain visible at ERROR level");
		}

		private void verifyResourceCleanup(MockedStatic<Common> common) {
			common.verify(() -> Common.safeClose(connection));
			common.verify(() -> Common.safeClose(results));
			common.verify(() -> Common.safeClose(statement));
		}

		@Override
		public void close() {
			logger.detachAppender(appender);
			logger.setLevel(level);
			logger.setAdditive(additive);
			appender.stop();
		}
	}

	private static boolean throwableContains(IThrowableProxy throwable, String value) {
		if (throwable == null) {
			return false;
		}
		if ((throwable.getMessage() != null && throwable.getMessage().contains(value))
				|| throwableContains(throwable.getCause(), value)) {
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

	@FunctionalInterface
	private interface Operation {
		void run(MockedStatic<Common> common) throws Exception;
	}
}
