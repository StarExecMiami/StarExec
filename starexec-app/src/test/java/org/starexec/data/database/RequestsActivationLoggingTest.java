package org.starexec.data.database;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.starexec.data.to.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class RequestsActivationLoggingTest {

	@Test public void addingCodeDoesNotLogCode() throws Exception { check(false, false); }
	@Test public void redeemingCodeDoesNotLogCode() throws Exception { check(true, false); }
	@Test public void failedAddDoesNotLogDatabaseEchoOfCode() throws Exception { check(false, true); }
	@Test public void failedRedemptionDoesNotLogDatabaseEchoOfCode() throws Exception { check(true, true); }

	private void check(boolean redeem, boolean failure) throws Exception {
		String sentinel = "SYNTHETIC-NONSECRET-LOGGING-SENTINEL";
		Connection con = mock(Connection.class);
		PreparedStatement stmt = mock(PreparedStatement.class);
		ResultSet rs = mock(ResultSet.class);
		User user = mock(User.class);
		when(user.getId()).thenReturn(42);
		when(user.getFullName()).thenReturn("Synthetic Audit User");
		when(con.prepareStatement(anyString())).thenReturn(stmt);
		when(stmt.executeQuery()).thenReturn(rs);
		when(rs.next()).thenReturn(true);
		when(rs.getInt(1)).thenReturn(42);
		Logger logger = (Logger) LoggerFactory.getLogger(Requests.class);
		ListAppender<ILoggingEvent> capture = new ListAppender<>();
		capture.start();
		boolean additive = logger.isAdditive();
		logger.setAdditive(false);
		logger.addAppender(capture);
		try (MockedStatic<Common> common = mockStatic(Common.class);
		     MockedStatic<ErrorLogs> errors = mockStatic(ErrorLogs.class)) {
			common.when(Common::getConnection).thenReturn(con);
			errors.when(() -> ErrorLogs.add(anyString(), any())).thenReturn(Optional.of(1));
			if (failure) {
				SQLException echoed = new SQLException("Database parameter: " + sentinel);
				if (redeem) when(stmt.executeQuery()).thenThrow(echoed);
				else common.when(() -> Common.executeAndDrain(stmt)).thenThrow(echoed);
			}
			if (redeem) assertEquals(failure ? -1 : 42, Requests.redeemActivationCode(sentinel));
			else assertEquals(!failure, Requests.addActivationCode(con, user, sentinel));
			verify(stmt).setString(redeem ? 1 : 2, sentinel);
			assertFalse("Expected an audit event", capture.list.isEmpty());
			for (ILoggingEvent event : capture.list) {
				assertFalse("Activation value leaked into log", event.getFormattedMessage().contains(sentinel));
				assertNull("Token-bearing exception must not be attached", event.getThrowableProxy());
			}
			if (failure) errors.verify(() -> ErrorLogs.add(argThat(message -> !message.contains(sentinel)), any()));
		} finally {
			logger.detachAppender(capture);
			logger.setAdditive(additive);
			capture.stop();
		}
	}
}
