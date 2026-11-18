package org.starexec.servlets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.Common;
import org.starexec.data.database.ErrorLogs;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@link UploadBenchmark#isDeletedUser} authorizes deleting a failed upload's files and the empty
 * directories above them. It runs here over the real {@code Users.exists} and query path, with only
 * the database connection replaced, so what each database answer leads to is pinned without mocking
 * the lookup. Warnings, which the logger also writes to the error log table, are recorded instead.
 */
public class DeletedUserLookupTest {

	private final Connection connection = mock(Connection.class);
	private final PreparedStatement statement = mock(PreparedStatement.class);
	private final ResultSet results = mock(ResultSet.class);
	private final List<String> reported = new ArrayList<>();

	@BeforeEach
	public void stubTheConnection() throws SQLException {
		when(connection.prepareStatement(anyString())).thenReturn(statement);
		when(statement.executeQuery()).thenReturn(results);
	}

	@Test
	public void aUserTheDatabaseFindsIsNotDeleted() throws SQLException {
		when(results.next()).thenReturn(true);

		assertFalse(isDeletedUser(7));
		assertEquals(List.of(), reported);
	}

	@Test
	public void aUserTheDatabaseDoesNotFindIsDeleted() throws SQLException {
		when(results.next()).thenReturn(false);

		assertTrue(isDeletedUser(7));
		assertEquals(List.of(), reported);
	}

	@Test
	public void aUserTheDatabaseCannotBeAskedAboutIsNotDeleted() throws SQLException {
		when(statement.executeQuery()).thenThrow(new SQLException("the connection was lost"));

		assertFalse(isDeletedUser(7));
		assertEquals(1, reported.size());
		assertTrue(reported.get(0).startsWith("WARN "), reported.get(0));
		assertTrue(reported.get(0).contains("Could not check whether user 7 exists"), reported.get(0));
	}

	/** isDeletedUser with only the connection replaced; each error log row is recorded, not written. */
	private boolean isDeletedUser(int userId) {
		try (MockedStatic<Common> common = mockStatic(Common.class, invocation ->
				invocation.getMethod().getName().equals("getConnection")
						? connection : invocation.callRealMethod());
		     MockedStatic<ErrorLogs> errorLogs = mockStatic(ErrorLogs.class, invocation -> {
			     if (!invocation.getMethod().getName().equals("add")) {
				     return Mockito.RETURNS_DEFAULTS.answer(invocation);
			     }
			     reported.add(invocation.getArgument(1) + " " + invocation.getArgument(0));
			     return Optional.empty();
		     })) {
			return UploadBenchmark.isDeletedUser(userId);
		}
	}
}
