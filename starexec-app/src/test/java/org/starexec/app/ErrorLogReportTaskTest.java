package org.starexec.app;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.invocation.InvocationOnMock;
import org.starexec.data.database.Common;
import org.starexec.data.database.RunscriptErrors;
import org.starexec.data.database.Users;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.to.User;
import org.starexec.logger.StarLevel;
import org.starexec.util.Mail;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Once a day, PeriodicTasks' SEND_ERROR_LOGS task emails the error_logs table to the admins and developers
 * subscribed to it, then deletes what it sent. Here the table is a list behind a fake database connection, so
 * each test reads which lines the task left, whatever statements it used to delete them, and checks that it
 * closed every connection and statement it opened. Mail, the subscriber query, the admin check and the
 * runscript error count are replaced; the error log code runs for real.
 */
public class ErrorLogReportTaskTest {

	private static final Timestamp TWO_DAYS_AGO = Timestamp.from(Instant.now().minus(2, ChronoUnit.DAYS));

	private final FakeErrorLogTable table = new FakeErrorLogTable();
	private final List<User> subscribers = new ArrayList<>();
	private final Set<Integer> admins = new HashSet<>();
	private final List<List<String>> mailedTo = new ArrayList<>();
	private final List<String> reports = new ArrayList<>();
	/** How many of the addresses of a message the SMTP server accepts. */
	private int accepted;
	/** Runs while the report is being sent, as a line logged meanwhile would be. */
	private Runnable whileSending = () -> { };

	@Test
	public void aReportThatReachedNoOneKeepsItsLines() {
		twoOldLines();
		subscribe(7, "root@example.org", true);
		accepted = 0;

		runTheTask();

		assertEquals(List.of(List.of("root@example.org")), mailedTo);
		assertTrue(reports.get(0).contains("first old line")
				&& reports.get(0).contains("second old line"), "the report was built from both lines");
		assertTrue(table.messages().containsAll(List.of("first old line", "second old line")),
				"no one got them, so both lines wait for the next report");
		assertTrue(table.added().stream().anyMatch(m -> m.contains("reached no one") && m.contains("kept")),
				"the log says the lines were kept: " + table.added());
		assertRanThrough();
	}

	@Test
	public void aLineNoOneReceivedIsGivenUpAfterFiveDays() {
		table.insert("six days old", StarLevel.ERROR, Timestamp.from(Instant.now().minus(6, ChronoUnit.DAYS)));
		table.insert("four days old", StarLevel.ERROR, Timestamp.from(Instant.now().minus(4, ChronoUnit.DAYS)));
		subscribe(7, "root@example.org", true);
		accepted = 0;

		runTheTask();

		assertFalse(table.messages().contains("six days old"), "kept for five days, then given up");
		assertTrue(table.messages().contains("four days old"), "younger lines still wait for the next report");
		assertTrue(table.added().stream()
				.anyMatch(m -> m.contains("1 line(s) older than 5 days are deleted unsent")), "the log says what was given up: " + table.added());
		assertRanThrough();
	}

	@Test
	public void aDeliveredReportDeletesOnlyTheLinesItContained() {
		twoOldLines();
		subscribe(7, "root@example.org", true);
		accepted = 1;
		whileSending = () -> table.insert("written while sending", StarLevel.WARN,
				Timestamp.from(Instant.now().minusMillis(1)));

		runTheTask();

		assertEquals(List.of(List.of("root@example.org")), mailedTo);
		assertFalse(table.messages().contains("first old line"), "the report's lines are deleted");
		assertFalse(table.messages().contains("second old line"), "the report's lines are deleted");
		assertTrue(table.messages().contains("written while sending"),
				"a line the report did not contain waits for the next one");
		assertRanThrough();
	}

	@Test
	public void aReportSomeRecipientsGotDeletesItsLines() {
		twoOldLines();
		subscribe(7, "root@example.org", true);
		subscribe(8, "dev@example.org", true);
		accepted = 1;

		runTheTask();

		assertEquals(List.of(List.of("root@example.org", "dev@example.org")), mailedTo);
		assertFalse(table.messages().contains("first old line"));
		assertFalse(table.messages().contains("second old line"));
		assertRanThrough();
	}

	@Test
	public void withNoSubscriberTheLinesAreDeleted() {
		twoOldLines();
		accepted = 0;

		runTheTask();

		assertFalse(table.messages().contains("first old line"), "no one asked for them");
		assertFalse(table.messages().contains("second old line"), "no one asked for them");
		assertRanThrough();
	}

	@Test
	public void aSubscriberWhoIsNotAnAdminIsNamedByIdAndNotEmailed() {
		twoOldLines();
		subscribe(7, "root@example.org", true);
		subscribe(9, "former-admin@example.org", false);
		accepted = 1;

		runTheTask();

		assertEquals(List.of(List.of("root@example.org")), mailedTo, "only admins and developers get the report");
		assertTrue(table.added().stream().anyMatch(m -> m.toLowerCase().contains("user 9")),
				"the log names the user: " + table.added());
		assertTrue(table.added().stream().noneMatch(m -> m.contains("former-admin@example.org")),
				"the log does not print the address: " + table.added());
		assertRanThrough();
	}

	@Test
	public void withoutALineOlderThanADayNothingIsSentOrDeleted() {
		table.insert("recent line", StarLevel.ERROR, Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));
		subscribe(7, "root@example.org", true);
		accepted = 1;

		runTheTask();

		assertEquals(List.of(), mailedTo);
		assertEquals(List.of("recent line"), table.messages());
		assertRanThrough();
	}

	@Test
	public void aDeleteTheDatabaseRefusesKeepsTheLinesForTheNextReport() {
		twoOldLines();
		subscribe(7, "root@example.org", true);
		accepted = 1;
		table.refuseDeletes = 1;

		runTheTask();

		assertTrue(table.messages().containsAll(List.of("first old line", "second old line")),
				"nothing is deleted when the delete fails");
		assertTrue(table.added().stream()
				.anyMatch(m -> m.contains("the database refused the delete")), "the failure is logged: " + table.added());
		assertEquals(List.of(), table.unexpected);
		assertResourcesClosed();

		int loggedBefore = table.added().size();
		runTheTask();

		assertEquals(2, reports.size(), "the next report is sent");
		assertTrue(reports.get(1).contains("first old line")
				&& reports.get(1).contains("second old line"), "with the lines the failed delete kept");
		assertEquals(List.of(), table.messages(), "once it is delivered, they are deleted");
		assertRanThroughSince(loggedBefore);
	}

	/** The task neither failed, nor sent a statement the fake table does not know, nor left anything open. */
	private void assertRanThrough() {
		assertRanThroughSince(0);
	}

	/** {@link #assertRanThrough()}, reading only the lines logged after the first {@code from}. */
	private void assertRanThroughSince(int from) {
		assertEquals(List.of(), table.unexpected);
		List<String> logged = table.added().subList(from, table.added().size());
		assertTrue(logged.stream()
				.noneMatch(m -> m.contains("caught throwable") || m.contains("Failed to send error log emails")), "the task failed: " + logged);
		assertResourcesClosed();
	}

	private void assertResourcesClosed() {
		assertEquals(table.connections, table.closedConnections, "every connection the task opened is closed");
		assertEquals(table.statements, table.closedStatements, "every statement the task prepared is closed");
	}

	private void twoOldLines() {
		table.insert("first old line", StarLevel.ERROR, TWO_DAYS_AGO);
		table.insert("second old line", StarLevel.WARN, TWO_DAYS_AGO);
	}

	private void subscribe(int id, String email, boolean admin) {
		User user = new User();
		user.setId(id);
		user.setEmail(email);
		subscribers.add(user);
		if (admin) {
			admins.add(id);
		}
	}

	/** The task exactly as PeriodicTasks schedules it. */
	private void runTheTask() {
		try (MockedStatic<Common> common = mockStatic(Common.class, invocation ->
				invocation.getMethod().getName().equals("getConnection")
						? table.connection() : invocation.callRealMethod());
		     MockedStatic<Users> users = mockStatic(Users.class, invocation ->
				     invocation.getMethod().getName().equals("getUsersSubscribedToErrorLogs")
						     ? new ArrayList<>(subscribers) : invocation.callRealMethod());
		     MockedStatic<GeneralSecurity> security = mockStatic(GeneralSecurity.class, invocation ->
				     isAdminCheck(invocation) ? admins.contains(invocation.<Integer>getArgument(0))
						     : invocation.callRealMethod());
		     MockedStatic<RunscriptErrors> runscriptErrors = mockStatic(RunscriptErrors.class, invocation ->
				     invocation.getMethod().getName().equals("getCount") ? 0 : invocation.callRealMethod());
		     MockedStatic<Mail> mail = mockStatic(Mail.class, invocation ->
				     isMailToMany(invocation) ? send(invocation) : invocation.callRealMethod())) {
			PeriodicTasks.PeriodicTask.SEND_ERROR_LOGS.task.run();
		}
	}

	private static boolean isAdminCheck(InvocationOnMock invocation) {
		return invocation.getMethod().getName().equals("hasAdminReadPrivileges")
				&& invocation.getArguments().length == 1;
	}

	private static boolean isMailToMany(InvocationOnMock invocation) {
		return invocation.getMethod().getName().equals("mail")
				&& invocation.getMethod().getParameterTypes()[2] == Collection.class;
	}

	/** Mail.mail(message, subject, addresses): records the message, and answers how many were accepted. */
	private Object send(InvocationOnMock invocation) {
		Collection<String> to = invocation.getArgument(2);
		mailedTo.add(new ArrayList<>(to));
		reports.add(invocation.getArgument(0));
		whileSending.run();
		return Math.min(accepted, to.size());
	}

	/**
	 * The error_logs table as a list, behind a fake connection that runs the statements the error log code sends.
	 * A statement it does not know is recorded in {@link #unexpected} and answered with nothing. It counts the
	 * connections and statements it hands out and those closed, and refuses the next {@link #refuseDeletes}
	 * deletes with an SQLException, as a database that cannot delete would.
	 */
	private static final class FakeErrorLogTable {
		private final List<Row> rows = new ArrayList<>();
		private final List<String> added = new ArrayList<>();
		final List<String> unexpected = new ArrayList<>();
		private int nextId = 1;
		int refuseDeletes;
		int connections;
		int closedConnections;
		int statements;
		int closedStatements;

		private static final class Row {
			final int id;
			final String message;
			final String level;
			final Timestamp time;

			Row(int id, String message, String level, Timestamp time) {
				this.id = id;
				this.message = message;
				this.level = level;
				this.time = time;
			}
		}

		int insert(String message, StarLevel level, Timestamp time) {
			rows.add(new Row(nextId, message, level.toString(), time));
			return nextId++;
		}

		/** The messages of the lines in the table now, oldest id first. */
		List<String> messages() {
			return rows.stream().map(r -> r.message).collect(Collectors.toList());
		}

		/** Every line the code under test logged into the table, whether or not it is still there. */
		List<String> added() {
			return new ArrayList<>(added);
		}

		Connection connection() {
			connections++;
			boolean[] closed = {false};
			return mock(Connection.class, invocation -> {
				switch (invocation.getMethod().getName()) {
					case "prepareStatement":
						return statement(invocation.getArgument(0));
					case "createArrayOf":
						Object[] elements = invocation.getArgument(1);
						Array array = mock(Array.class, a ->
								a.getMethod().getName().equals("getArray") ? elements : null);
						return array;
					case "isClosed":
						return closed[0];
					case "close":
						if (!closed[0]) {
							closed[0] = true;
							closedConnections++;
						}
						return null;
					default:
						return defaultValue(invocation);
				}
			});
		}

		private PreparedStatement statement(String sql) {
			statements++;
			boolean[] closed = {false};
			Map<Integer, Object> parameters = new HashMap<>();
			return mock(PreparedStatement.class, invocation -> {
				String name = invocation.getMethod().getName();
				if (name.startsWith("set") && invocation.getArguments().length == 2) {
					parameters.put(invocation.getArgument(0), invocation.getArgument(1));
					return null;
				}
				switch (name) {
					case "executeQuery":
						return results(query(sql, parameters));
					case "execute":
						update(sql, parameters);
						return false;
					case "executeUpdate":
						return update(sql, parameters);
					case "getUpdateCount":
						return -1;
					case "isClosed":
						return closed[0];
					case "close":
						if (!closed[0]) {
							closed[0] = true;
							closedStatements++;
						}
						return null;
					default:
						return defaultValue(invocation);
				}
			});
		}

		private List<Map<String, Object>> query(String sql, Map<Integer, Object> parameters) {
			List<Map<String, Object>> answer = new ArrayList<>();
			if (sql.equals("SELECT * FROM starexec.GetAllErrorLogs()")) {
				rows.forEach(r -> answer.add(columns(r)));
			} else if (sql.equals("SELECT * FROM starexec.GetErrorLogsBefore(?)")) {
				Timestamp before = (Timestamp) parameters.get(1);
				rows.stream().filter(r -> r.time.before(before)).forEach(r -> answer.add(columns(r)));
			} else if (sql.equals("SELECT starexec.AddErrorLog(?, ?)")) {
				String message = (String) parameters.get(1);
				added.add(message);
				int id = insert(message, StarLevel.valueOf((String) parameters.get(2)), Timestamp.from(Instant.now()));
				answer.add(Map.of("1", id));
			} else {
				unexpected.add(sql);
			}
			return answer;
		}

		private int update(String sql, Map<Integer, Object> parameters) throws Exception {
			boolean delete = sql.equals("SELECT starexec.DeleteErrorLogsBefore(?)")
					|| sql.equals("DELETE FROM starexec.error_logs WHERE id = ANY(?)");
			if (delete && refuseDeletes > 0) {
				refuseDeletes--;
				throw new SQLException("the database refused the delete");
			}
			int before = rows.size();
			if (sql.equals("SELECT starexec.DeleteErrorLogsBefore(?)")) {
				Timestamp time = (Timestamp) parameters.get(1);
				rows.removeIf(r -> r.time.before(time));
			} else if (sql.equals("DELETE FROM starexec.error_logs WHERE id = ANY(?)")) {
				Set<Object> ids = new HashSet<>(Arrays.asList((Object[]) ((Array) parameters.get(1)).getArray()));
				rows.removeIf(r -> ids.contains(r.id));
			} else {
				unexpected.add(sql);
			}
			return before - rows.size();
		}

		private static Map<String, Object> columns(Row r) {
			return Map.of("id", r.id, "message", r.message, "level", r.level, "time", r.time);
		}

		private static ResultSet results(List<Map<String, Object>> answer) {
			int[] at = {-1};
			return mock(ResultSet.class, invocation -> {
				switch (invocation.getMethod().getName()) {
					case "next":
						return ++at[0] < answer.size();
					case "getInt":
					case "getString":
					case "getTimestamp":
						Object key = invocation.getArgument(0);
						return answer.get(at[0]).get(String.valueOf(key));
					default:
						return defaultValue(invocation);
				}
			});
		}

		private static Object defaultValue(InvocationOnMock invocation) {
			Class<?> type = invocation.getMethod().getReturnType();
			if (type == boolean.class) {
				return false;
			}
			if (type == int.class) {
				return 0;
			}
			return null;
		}
	}
}
