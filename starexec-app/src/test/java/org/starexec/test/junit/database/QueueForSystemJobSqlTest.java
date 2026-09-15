package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.data.database.Common;
import org.starexec.data.database.Queues;
import org.starexec.data.to.Queue;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@code Queues.getQueueForSystemJob} against the real queue-access SQL (#204).
 *
 * <p>The PodmanBackend stack that exposed #204 has {@code all.q} INACTIVE and set as the test queue,
 * while {@code PodmanBackend}'s own partition queue is created, made global
 * ({@code Queues.makeGlobal}) and set ACTIVE ({@code Queues.setStatus}) at startup. The fixture
 * reproduces that shape with its own two global queues -- one INACTIVE and configured as the test
 * queue, one ACTIVE -- and an ordinary user, who reaches global queues through
 * {@code GetQueuesForUser}.
 *
 * <p>Other queues may already exist in the database, so the inactive case asserts what the answer
 * must be (a queue that is not the inactive one, ACTIVE, and usable by the user) rather than which
 * id it is. The configured test queue is restored afterwards.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*} variables
 * at a <strong>disposable</strong> database: these tests insert and delete rows.
 */
public class QueueForSystemJobSqlTest extends Common {

	private final String tag = "sysjobq-" + UUID.randomUUID().toString().substring(0, 8);
	private int userId;
	private int inactiveQueue;
	private int activeQueue;
	private int originalTestQueue;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("QueueForSystemJobSqlTest");
		Common.initialize();
	}

	@Before
	public void seed() throws SQLException {
		originalTestQueue = Queues.getTestQueue();
		try (Connection con = Common.getConnection()) {
			userId = returningId(con,
					"INSERT INTO starexec.users (email, first_name, last_name, institution, created,"
							+ " password, disk_quota, disk_size) VALUES (?, 'Q', 'User', 'I', NOW(), 'x',"
							+ " 1000000, 0) RETURNING id",
					tag + "@test");
			inactiveQueue = returningId(con,
					"INSERT INTO starexec.queues (name, status, global_access) VALUES (?, 'INACTIVE', true)"
							+ " RETURNING id",
					tag + "-inactive.q");
			activeQueue = returningId(con,
					"INSERT INTO starexec.queues (name, status, global_access) VALUES (?, 'ACTIVE', true)"
							+ " RETURNING id",
					tag + "-active.q");
		}
	}

	@After
	public void dropFixture() throws SQLException {
		if (originalTestQueue > 0) {
			Queues.setTestQueue(originalTestQueue);
		}
		try (Connection con = Common.getConnection()) {
			delete(con, "DELETE FROM starexec.queues WHERE id = ?", inactiveQueue);
			delete(con, "DELETE FROM starexec.queues WHERE id = ?", activeQueue);
			delete(con, "DELETE FROM starexec.users WHERE id = ?", userId);
		}
	}

	/** The E2E stack's shape: the configured test queue is inactive. */
	@Test
	public void anInactiveTestQueueGivesWayToAnActiveQueueTheUserCanUse() {
		assertTrue(Queues.setTestQueue(inactiveQueue));

		int chosen = Queues.getQueueForSystemJob(userId);

		assertNotEquals("the inactive test queue is never scheduled", inactiveQueue, chosen);
		assertTrue("a queue was chosen", chosen > 0);
		assertEquals("ACTIVE", Queues.get(chosen).getStatus());
		assertTrue("the user can use it", usable(chosen));
		assertTrue("the fixture's active global queue is usable, so something is",
				usable(activeQueue));
	}

	@Test
	public void anActiveTestQueueTheUserCanUseIsKept() {
		assertTrue(Queues.setTestQueue(activeQueue));

		assertEquals(activeQueue, Queues.getQueueForSystemJob(userId));
	}

	private boolean usable(int queueId) {
		for (Queue q : Queues.getUserQueues(userId)) {
			if (q.getId() == queueId) {
				return true;
			}
		}
		return false;
	}

	private static int returningId(Connection con, String sql, String param) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			ps.setString(1, param);
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) {
					throw new IllegalStateException("No id returned: " + sql);
				}
				return rs.getInt(1);
			}
		}
	}

	private static void delete(Connection con, String sql, int id) throws SQLException {
		if (id <= 0) {
			return;
		}
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			ps.setInt(1, id);
			ps.executeUpdate();
		}
	}
}
