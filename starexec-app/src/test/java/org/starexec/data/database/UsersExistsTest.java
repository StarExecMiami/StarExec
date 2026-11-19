package org.starexec.data.database;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link Users#exists} decides whether a failed upload's files may be deleted because their owner is
 * gone. It runs here on the real query path with only {@link Common#getConnection} replaced, so the
 * statement, its parameter and each answer are the ones the database would see and give.
 */
public class UsersExistsTest {

	private static final String SQL = "SELECT 1 FROM starexec.users WHERE id = ?";

	private final Connection connection = mock(Connection.class);
	private final PreparedStatement statement = mock(PreparedStatement.class);
	private final ResultSet results = mock(ResultSet.class);

	@BeforeEach
	public void stubTheConnection() throws SQLException {
		when(connection.prepareStatement(anyString())).thenReturn(statement);
		when(statement.executeQuery()).thenReturn(results);
	}

	@Test
	public void aUserWhoseRowIsFoundExists() throws SQLException {
		when(results.next()).thenReturn(true);

		assertTrue(exists(42));
		verify(connection).prepareStatement(SQL);
		verify(statement).setInt(1, 42);
	}

	@Test
	public void aUserWhoseRowIsNotFoundDoesNotExist() throws SQLException {
		when(results.next()).thenReturn(false);

		assertFalse(exists(42));
		verify(connection).prepareStatement(SQL);
		verify(statement).setInt(1, 42);
	}

	@Test
	public void aQueryThatFailsIsThrownNotAnsweredWithNo() throws SQLException {
		SQLException failure = new SQLException("the connection was lost");
		when(statement.executeQuery()).thenThrow(failure);

		assertSame(failure, assertThrows(SQLException.class, () -> exists(42)));
	}

	@Test
	public void aConnectionThatCannotBeOpenedIsThrownNotAnsweredWithNo() {
		SQLException failure = new SQLException("no connection is available");
		try (MockedStatic<Common> common = mockStatic(Common.class, invocation -> {
			if (invocation.getMethod().getName().equals("getConnection")) {
				throw failure;
			}
			return invocation.callRealMethod();
		})) {
			assertSame(failure, assertThrows(SQLException.class, () -> Users.exists(42)));
		}
	}

	/** Users.exists with only Common.getConnection replaced: the query itself runs for real. */
	private boolean exists(int userId) throws SQLException {
		try (MockedStatic<Common> common = mockStatic(Common.class, invocation ->
				invocation.getMethod().getName().equals("getConnection")
						? connection : invocation.callRealMethod())) {
			return Users.exists(userId);
		}
	}
}
