package org.starexec.data.database;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.starexec.app.RESTServices;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.security.UserSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.User;
import org.starexec.exceptions.StarExecDatabaseException;
import org.starexec.logger.StarLevel;
import org.starexec.util.SessionUtil;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Regression specifications for one-time request values echoed by database failures.
 * All values are deterministic synthetic sentinels and are not real credentials.
 */
public class RequestsBearerLoggingRegressionTest {

	@Test
	public void addingPasswordResetRequestDoesNotLogDatabaseEchoOfCode() throws Exception {
		String code = "10000000-0000-4000-8000-000000000001";
		FailureFixture fixture = new FailureFixture(code);
		try (MockedStatic<Common> common = mockStatic(Common.class)) {
			fixture.stubConnection(common);
			common.when(() -> Common.executeAndDrain(fixture.statement)).thenThrow(fixture.failure);
			assertFailureDoesNotLeak(Requests.class, () -> code,
					() -> assertFalse(Requests.addPassResetRequest(42, code)));
		}
	}

	@Test
	public void redeemingPasswordResetRequestDoesNotLogDatabaseEchoOfCode() throws Exception {
		String code = "10000000-0000-4000-8000-000000000002";
		FailureFixture fixture = new FailureFixture(code);
		when(fixture.statement.executeQuery()).thenThrow(fixture.failure);
		try (MockedStatic<Common> common = mockStatic(Common.class)) {
			fixture.stubConnection(common);
			assertFailureDoesNotLeak(Requests.class, () -> code,
					() -> assertEquals(-1, Requests.redeemPassResetRequest(code)));
		}
	}

	@Test
	public void addingCommunityRequestDoesNotLogDatabaseEchoOfCode() throws Exception {
		String code = "10000000-0000-4000-8000-000000000003";
		FailureFixture fixture = new FailureFixture(code);
		User user = mock(User.class);
		when(user.getId()).thenReturn(42);
		try (MockedStatic<Common> common = mockStatic(Common.class)) {
			fixture.stubConnection(common);
			common.when(() -> Common.executeAndDrain(fixture.statement)).thenThrow(fixture.failure);
			assertFailureDoesNotLeak(Requests.class, () -> code,
					() -> assertFalse(Requests.addCommunityRequest(user, 7, code, "Synthetic request")));
		}
	}

	@Test
	public void gettingCommunityRequestDoesNotLogDatabaseEchoOfCode() throws Exception {
		String code = "10000000-0000-4000-8000-000000000004";
		FailureFixture fixture = new FailureFixture(code);
		when(fixture.statement.executeQuery()).thenThrow(fixture.failure);
		try (MockedStatic<Common> common = mockStatic(Common.class)) {
			fixture.stubConnection(common);
			assertFailureDoesNotLeak(Requests.class, () -> code,
					() -> assertNull(Requests.getCommunityRequest(code)));
		}
	}

	@Test
	public void reachableEmailChangeRouteDoesNotLogDatabaseEchoOfCode() throws Exception {
		int userId = 42;
		String newEmail = "synthetic-audit@example.invalid";
		AtomicReference<String> generatedCode = new AtomicReference<>();
		Connection connection = mock(Connection.class);
		PreparedStatement lookupStatement = mock(PreparedStatement.class);
		PreparedStatement claimStatement = mock(PreparedStatement.class);
		ResultSet lookupResults = mock(ResultSet.class);
		when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
			String sql = invocation.getArgument(0, String.class);
			return sql.contains("GetUserByEmail") ? lookupStatement : claimStatement;
		});
		when(lookupStatement.executeQuery()).thenReturn(lookupResults);
		when(lookupResults.next()).thenReturn(false);
		doAnswer(invocation -> {
			if (invocation.getArgument(0, Integer.class) == 3) {
				generatedCode.set(invocation.getArgument(1, String.class));
			}
			return null;
		}).when(claimStatement).setString(anyInt(), anyString());
		when(claimStatement.executeQuery()).thenAnswer(invocation -> {
			String code = generatedCode.get();
			if (code == null) {
				throw new AssertionError("Change-email code was not bound before execution");
			}
			throw new SQLException("Database parameters: " + code + " " + newEmail);
		});

		HttpServletRequest request = requestWithBody(
				"{\"attribute\":\"email\",\"value\":\"" + newEmail + "\"}");
		try (MockedStatic<SessionUtil> sessions = mockStatic(SessionUtil.class);
		     MockedStatic<UserSecurity> userSecurity = mockStatic(UserSecurity.class);
		     MockedStatic<GeneralSecurity> generalSecurity = mockStatic(GeneralSecurity.class);
		     MockedStatic<Common> common = mockStatic(Common.class)) {
			sessions.when(() -> SessionUtil.getUserId(request)).thenReturn(userId);
			userSecurity.when(() -> UserSecurity.canUpdateData(userId, userId, "email", newEmail))
					.thenReturn(new ValidatorStatusCode(true));
			generalSecurity.when(() -> GeneralSecurity.hasAdminWritePrivileges(userId)).thenReturn(false);
			common.when(Common::getConnection).thenReturn(connection);

			assertFailureDoesNotLeak(List.of(RESTServices.class, Users.class),
					List.of(generatedCode::get, () -> newEmail), () -> {
				String response = new RESTServices().editUserInfoFromBody(userId, request);
				assertTrue(response.contains("verification link has been sent"));
				assertNotNull(generatedCode.get(), "Expected the production route to generate a bearer code");
				verify(lookupStatement).setString(1, newEmail);
				verify(claimStatement).setString(2, newEmail);
				verify(claimStatement).setString(3, generatedCode.get());
			});
		}
	}

	@Test
	public void directEmailChangeRequestDoesNotExposeBoundValuesOrDatabaseCause() throws Exception {
		int userId = 42;
		String newEmail = "synthetic-direct@example.invalid";
		String code = "10000000-0000-4000-8000-000000000005";
		Connection connection = mock(Connection.class);
		PreparedStatement statement = mock(PreparedStatement.class);
		SQLException echoed = new SQLException("Database parameters: " + newEmail + " " + code);
		when(connection.prepareStatement(anyString())).thenReturn(statement);

		try (MockedStatic<Common> common = mockStatic(Common.class)) {
			common.when(Common::getConnection).thenReturn(connection);
			common.when(() -> Common.executeAndDrain(statement)).thenThrow(echoed);

			StarExecDatabaseException thrown = null;
			try {
				Requests.addChangeEmailRequest(userId, newEmail, code);
				fail("Expected addChangeEmailRequest to preserve its database-error contract");
			} catch (StarExecDatabaseException e) {
				thrown = e;
			}

			assertNotNull(thrown);
			assertFalse(contains(thrown.getMessage(), newEmail), "New email leaked into exception message");
			assertFalse(contains(thrown.getMessage(), code), "Verification code leaked into exception message");
			assertNull(thrown.getCause(), "A database cause can echo bound values");
			verify(statement).setInt(1, userId);
			verify(statement).setString(2, newEmail);
			verify(statement).setString(3, code);
		}
	}

	private static void assertFailureDoesNotLeak(
			Class<?> loggerClass, Supplier<String> bearerSupplier, ThrowingRunnable operation
	) throws Exception {
		assertFailureDoesNotLeak(loggerClass, List.of(bearerSupplier), operation);
	}

	private static void assertFailureDoesNotLeak(
			Class<?> loggerClass, List<Supplier<String>> bearerSuppliers, ThrowingRunnable operation
	) throws Exception {
		assertFailureDoesNotLeak(List.of(loggerClass), bearerSuppliers, operation);
	}

	private static void assertFailureDoesNotLeak(
			List<Class<?>> loggerClasses, List<Supplier<String>> bearerSuppliers, ThrowingRunnable operation
	) throws Exception {
		List<String> persistedMessages = new ArrayList<>();
		try (LogCapture logs = new LogCapture(loggerClasses);
		     MockedStatic<ErrorLogs> errors = mockStatic(ErrorLogs.class)) {
			errors.when(() -> ErrorLogs.add(anyString(), any(StarLevel.class))).thenAnswer(invocation -> {
				persistedMessages.add(invocation.getArgument(0, String.class));
				return Optional.of(1);
			});
			operation.run();
			assertFalse(logs.events.isEmpty(), "Expected the failure to be logged");
			assertFalse(persistedMessages.isEmpty(), "Expected the failure to be recorded through ErrorLogs.add");

			for (Supplier<String> bearerSupplier : bearerSuppliers) {
				String bearer = bearerSupplier.get();
				assertNotNull(bearer, "Expected a synthetic bearer value");
				List<String> leakChannels = new ArrayList<>();
				if (logs.events.stream().anyMatch(event -> contains(event.getFormattedMessage(), bearer))) {
					leakChannels.add("formatted-message");
				}
				if (logs.events.stream().anyMatch(event -> throwableContains(event.getThrowableProxy(), bearer))) {
					leakChannels.add("throwable-or-cause");
				}
				if (persistedMessages.stream().anyMatch(message -> contains(message, bearer))) {
					leakChannels.add("ErrorLogs.add");
				}
				assertTrue(leakChannels.isEmpty(), "Synthetic bearer leaked through " + leakChannels);
			}
		}
	}

	private static boolean throwableContains(IThrowableProxy throwable, String bearer) {
		if (throwable == null) {
			return false;
		}
		if (contains(throwable.getMessage(), bearer) || throwableContains(throwable.getCause(), bearer)) {
			return true;
		}
		IThrowableProxy[] suppressed = throwable.getSuppressed();
		if (suppressed != null) {
			for (IThrowableProxy candidate : suppressed) {
				if (throwableContains(candidate, bearer)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean contains(String text, String expected) {
		return text != null && text.contains(expected);
	}

	private static HttpServletRequest requestWithBody(String body) throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		ByteArrayInputStream input = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
		ServletInputStream stream = new ServletInputStream() {
			@Override public int read() { return input.read(); }
			@Override public boolean isFinished() { return input.available() == 0; }
			@Override public boolean isReady() { return true; }
			@Override public void setReadListener(ReadListener readListener) { }
		};
		when(request.getInputStream()).thenReturn(stream);
		return request;
	}

	private static final class FailureFixture {
		private final Connection connection = mock(Connection.class);
		private final PreparedStatement statement = mock(PreparedStatement.class);
		private final SQLException failure;

		private FailureFixture(String bearer) throws Exception {
			failure = new SQLException("Database parameter: " + bearer);
			when(connection.prepareStatement(anyString())).thenReturn(statement);
		}

		private void stubConnection(MockedStatic<Common> common) {
			common.when(Common::getConnection).thenReturn(connection);
		}
	}

	private static final class LogCapture implements AutoCloseable {
		private final List<Logger> loggers = new ArrayList<>();
		private final List<Boolean> additiveStates = new ArrayList<>();
		private final List<Level> levels = new ArrayList<>();
		private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		private final List<ILoggingEvent> events = appender.list;

		private LogCapture(Class<?> loggerClass) {
			this(List.of(loggerClass));
		}

		private LogCapture(List<Class<?>> loggerClasses) {
			appender.start();
			for (Class<?> loggerClass : loggerClasses) {
				Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
				loggers.add(logger);
				additiveStates.add(logger.isAdditive());
				levels.add(logger.getLevel());
				logger.setLevel(Level.DEBUG);
				logger.setAdditive(false);
				logger.addAppender(appender);
			}
		}

		@Override
		public void close() {
			for (int i = 0; i < loggers.size(); i++) {
				loggers.get(i).detachAppender(appender);
				loggers.get(i).setAdditive(additiveStates.get(i));
				loggers.get(i).setLevel(levels.get(i));
			}
			appender.stop();
		}
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
