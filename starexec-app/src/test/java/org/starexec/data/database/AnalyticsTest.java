package org.starexec.data.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.to.AnalyticsResults;
import org.starexec.util.functionalInterfaces.ThrowingConsumer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AnalyticsTest {

	@BeforeEach
	@AfterEach
	public void resetAnalyticsState() {
		Analytics.resetPendingStateForTest();
	}

	@Test
	public void analyticsResultsMapsPostgresProcedureColumnNamesAndTrimsCharPadding() throws Exception {
		ResultSet rs = mock(ResultSet.class);
		when(rs.next()).thenReturn(true, true, false);
		when(rs.getString("event_name")).thenReturn("JOB_CREATE                      ", "PAGEVIEW_HELP");
		when(rs.getInt("event_count")).thenReturn(17, 4);
		when(rs.getInt("user_count")).thenReturn(3, 2);

		List<AnalyticsResults> results = AnalyticsResults.listFromResults(rs);

		assertEquals(2, results.size());
		assertEquals(Analytics.JOB_CREATE, results.get(0).event);
		assertEquals(17, results.get(0).count);
		assertEquals(3, results.get(0).users);
		assertEquals(Analytics.PAGEVIEW_HELP, results.get(1).event);
		assertEquals(4, results.get(1).count);
		assertEquals(2, results.get(1).users);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void analyticsSaveToDBResolvesEventIdDrainsCountAndRestoresOnFailure() throws Exception {
		AtomicInteger updateCalls = new AtomicInteger(0);
		AtomicInteger lastWrittenCount = new AtomicInteger(0);
		try (MockedStatic<Common> common = Mockito.mockStatic(Common.class)) {
			common.when(Common::isPoolInitialized).thenReturn(true);
			common.when(() -> Common.query(eq("{SELECT * FROM starexec.GetEventId(?)}"), any(), any()))
					.thenReturn(7);

			// Force first save attempt to fail, verifying delta is restored for next flush.
			CallableStatement cs = mock(CallableStatement.class);
			Mockito.doAnswer(inv -> {
				int idx = inv.getArgument(0);
				if (idx == 3) {
					lastWrittenCount.set(inv.getArgument(1));
				}
				return null;
			}).when(cs).setInt(eq(3), any(Integer.class));

			common.when(() -> Common.update(eq("{call starexec.RecordEvent(?,?,?)}"), any()))
					.thenAnswer(inv -> {
						int n = updateCalls.incrementAndGet();
						ThrowingConsumer<CallableStatement, SQLException> binder = inv.getArgument(1);
						binder.accept(cs);
						if (n == 1) {
							throw new SQLException("transient error");
						}
						return null;
					});
			common.when(() -> Common.update(eq("{call starexec.RecordEventUser(?,?,?)}"), any()))
					.thenAnswer(inv -> null);

			Analytics.PAGEVIEW_HELP.record(99);
			Analytics.PAGEVIEW_HELP.record(99);

			// First flush fails on RecordEvent -> restores delta (2)
			Analytics.saveToDB();
			assertEquals(1, updateCalls.get());
			assertEquals(2, lastWrittenCount.get());

			// Second flush drains and persists the restored delta cleanly
			Analytics.saveToDB();
			assertEquals(2, updateCalls.get());
			assertEquals(2, lastWrittenCount.get());

			// Third flush with zero pending count performs no redundant RecordEvent call
			Analytics.saveToDB();
			assertEquals(2, updateCalls.get());
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void analyticsSaveToDBDrainsUsersOnSuccessAndAvoidsReinsertingLater() throws Exception {
		List<Integer> recordedUsers = new ArrayList<>();
		try (MockedStatic<Common> common = Mockito.mockStatic(Common.class)) {
			common.when(Common::isPoolInitialized).thenReturn(true);
			common.when(() -> Common.query(eq("{SELECT * FROM starexec.GetEventId(?)}"), any(), any()))
					.thenReturn(11);

			common.when(() -> Common.update(eq("{call starexec.RecordEvent(?,?,?)}"), any()))
					.thenAnswer(inv -> null);

			CallableStatement cs = mock(CallableStatement.class);
			Mockito.doAnswer(inv -> {
				recordedUsers.add(inv.getArgument(1));
				return null;
			}).when(cs).setInt(eq(3), any(Integer.class));

			common.when(() -> Common.update(eq("{call starexec.RecordEventUser(?,?,?)}"), any()))
					.thenAnswer(inv -> {
						ThrowingConsumer<CallableStatement, SQLException> binder = inv.getArgument(1);
						binder.accept(cs);
						return null;
					});

			Analytics.JOB_CREATE.record(101);
			Analytics.JOB_CREATE.record(102);

			Analytics.saveToDB();
			assertEquals(List.of(101, 102), recordedUsers.stream().sorted().toList());

			// Subsequent flush with no new events must not re-attempt already-flushed users.
			Analytics.saveToDB();
			assertEquals(2, recordedUsers.size());
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void analyticsSaveToDBDiscardsForeignKeyViolationUserWithoutRetryLoop() throws Exception {
		List<Integer> attemptedUsers = new ArrayList<>();
		try (MockedStatic<Common> common = Mockito.mockStatic(Common.class)) {
			common.when(Common::isPoolInitialized).thenReturn(true);
			common.when(() -> Common.query(eq("{SELECT * FROM starexec.GetEventId(?)}"), any(), any()))
					.thenReturn(12);

			common.when(() -> Common.update(eq("{call starexec.RecordEvent(?,?,?)}"), any()))
					.thenAnswer(inv -> null);

			CallableStatement cs = mock(CallableStatement.class);
			final int[] currentUser = new int[1];
			Mockito.doAnswer(inv -> {
				currentUser[0] = inv.getArgument(1);
				attemptedUsers.add(currentUser[0]);
				return null;
			}).when(cs).setInt(eq(3), any(Integer.class));

			common.when(() -> Common.update(eq("{call starexec.RecordEventUser(?,?,?)}"), any()))
					.thenAnswer(inv -> {
						ThrowingConsumer<CallableStatement, SQLException> binder = inv.getArgument(1);
						binder.accept(cs);
						if (currentUser[0] == 9) {
							throw new SQLException(
									"ERROR: insert or update on table \"analytics_users\" violates foreign key constraint \"analytics_users_to_users\"",
									"23503");
						}
						if (currentUser[0] == 10) {
							SQLException wrapped = new SQLException("wrapper", "HY000");
							wrapped.setNextException(new SQLException("deleted user fk", "23503"));
							throw wrapped;
						}
						return null;
					});

			Analytics.JOB_PAUSE.record(9);
			Analytics.JOB_PAUSE.record(10);
			Analytics.JOB_PAUSE.record(11);

			// First flush attempts all three; user 11 succeeds while 9 and 10 fail with 23503 and are purged.
			Analytics.saveToDB();
			assertEquals(List.of(9, 10, 11), attemptedUsers.stream().sorted().toList());

			// Subsequent flushes must not retry any of the purged/persisted users.
			Analytics.saveToDB();
			Analytics.saveToDB();
			assertEquals(3, attemptedUsers.size());
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void analyticsSaveToDBRetainsUserOnTransientErrorAndRetriesNextFlush() throws Exception {
		AtomicInteger userAttempts = new AtomicInteger(0);
		List<Integer> attemptedUsers = new ArrayList<>();
		try (MockedStatic<Common> common = Mockito.mockStatic(Common.class)) {
			common.when(Common::isPoolInitialized).thenReturn(true);
			common.when(() -> Common.query(eq("{SELECT * FROM starexec.GetEventId(?)}"), any(), any()))
					.thenReturn(13);

			common.when(() -> Common.update(eq("{call starexec.RecordEvent(?,?,?)}"), any()))
					.thenAnswer(inv -> null);

			CallableStatement cs = mock(CallableStatement.class);
			Mockito.doAnswer(inv -> {
				attemptedUsers.add(inv.getArgument(1));
				return null;
			}).when(cs).setInt(eq(3), any(Integer.class));

			common.when(() -> Common.update(eq("{call starexec.RecordEventUser(?,?,?)}"), any()))
					.thenAnswer(inv -> {
						ThrowingConsumer<CallableStatement, SQLException> binder = inv.getArgument(1);
						binder.accept(cs);
						if (userAttempts.incrementAndGet() == 1) {
							throw new SQLException("connection reset", "08006");
						}
						return null;
					});

			Analytics.JOB_RESUME.record(55);

			// First flush fails transiently -> user 55 is retained for retry.
			Analytics.saveToDB();
			assertEquals(List.of(55), attemptedUsers);

			// Second flush retries user 55 and drains it upon success.
			Analytics.saveToDB();
			assertEquals(List.of(55, 55), attemptedUsers);

			// Third flush makes no further RecordEventUser calls.
			Analytics.saveToDB();
			assertEquals(2, attemptedUsers.size());
		}
	}

	@Test
	public void helpJspRecordsPageviewHelpEvent() throws Exception {
		String jsp = Files.readString(Path.of("src/main/webapp/secure/help.jsp"));
		assertTrue(jsp.contains("Analytics.PAGEVIEW_HELP.record(userId);"));
		assertFalse(jsp.contains("Analytics.JOB_ATTRIBUTES.record(userId);"));
	}
}
