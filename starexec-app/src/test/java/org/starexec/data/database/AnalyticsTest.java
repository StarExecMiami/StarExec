package org.starexec.data.database;

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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AnalyticsTest {

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
	public void helpJspRecordsPageviewHelpEvent() throws Exception {
		String jsp = Files.readString(Path.of("src/main/webapp/secure/help.jsp"));
		assertTrue(jsp.contains("Analytics.PAGEVIEW_HELP.record(userId);"));
		assertFalse(jsp.contains("Analytics.JOB_ATTRIBUTES.record(userId);"));
	}
}
