package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.data.database.Common;
import org.starexec.data.database.Queues;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

/**
 * Regression tests for #232 and #233, against the real SQL.
 *
 * <p>#232: {@code GetPendingDeveloperJobs(_queueId)} mixed AND and OR without parentheses, so the
 * queue filter applied only to one of the two roles and developer jobs were handed to the wrong
 * queue. #233: {@code Queues.developerJobsExist()} returned after inspecting only the first active
 * queue instead of continuing to the next one.
 *
 * <p>The fixture creates two ACTIVE queues whose names sort before and after every other queue
 * ({@code 0-...} and {@code zzzz-...}), plus one user per role and a job with one pending pair
 * (status 1) per test as needed.
 *
 * <p>Skips unless a PostgreSQL instance is configured, following the convention in
 * {@link QueueForSystemJobSqlTest}. Point the {@code STAREXEC_DB_*} variables at a
 * <strong>disposable</strong> database: these tests insert and delete rows.
 */
public class DeveloperJobsQueueScopeSqlTest extends Common {

	private static final int PENDING = 1;

	private final String tag = "devq-" + UUID.randomUUID().toString().substring(0, 8);
	private int firstQueue;
	private int lastQueue;
	private int developerId;
	private int adminId;
	private int plainUserId;
	private final List<Integer> jobIds = new ArrayList<>();

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("DeveloperJobsQueueScopeSqlTest");
		Common.initialize();
	}

	@Before
	public void seed() throws SQLException {
		try (Connection con = Common.getConnection()) {
			firstQueue = insertQueue(con, "0-" + tag + ".q");
			lastQueue = insertQueue(con, "zzzz-" + tag + ".q");
			developerId = insertUser(con, "developer");
			adminId = insertUser(con, "admin");
			plainUserId = insertUser(con, "user");
		}
	}

	@After
	public void dropFixture() throws SQLException {
		try (Connection con = Common.getConnection()) {
			for (int jobId : jobIds) {
				exec(con, "DELETE FROM starexec.job_pairs WHERE job_id = ?", jobId);
				exec(con, "DELETE FROM starexec.jobs WHERE id = ?", jobId);
			}
			exec(con, "DELETE FROM starexec.users WHERE id = ?", developerId);
			exec(con, "DELETE FROM starexec.users WHERE id = ?", adminId);
			exec(con, "DELETE FROM starexec.users WHERE id = ?", plainUserId);
			exec(con, "DELETE FROM starexec.queues WHERE id = ?", firstQueue);
			exec(con, "DELETE FROM starexec.queues WHERE id = ?", lastQueue);
		}
	}

	/** #232, developer branch: a job on queue B is not a pending developer job of queue A. */
	@Test
	public void aDeveloperJobIsReturnedOnlyForItsOwnQueue() throws SQLException {
		int jobId = insertPendingJob(developerId, lastQueue);

		assertFalse("job on the last queue leaked into the first queue",
				pendingDeveloperJobs(firstQueue).contains(jobId));
		assertTrue(pendingDeveloperJobs(lastQueue).contains(jobId));
	}

	/** #232, admin branch: the OR between the roles must not detach the queue filter. */
	@Test
	public void anAdminJobIsReturnedOnlyForItsOwnQueue() throws SQLException {
		int jobId = insertPendingJob(adminId, lastQueue);

		assertFalse("admin job on the last queue leaked into the first queue",
				pendingDeveloperJobs(firstQueue).contains(jobId));
		assertTrue(pendingDeveloperJobs(lastQueue).contains(jobId));
	}

	/** Roles other than developer and admin never produce developer jobs. */
	@Test
	public void anOrdinaryUsersJobIsNeverADeveloperJob() throws SQLException {
		int jobId = insertPendingJob(plainUserId, lastQueue);

		assertFalse(pendingDeveloperJobs(lastQueue).contains(jobId));
		assertFalse(pendingDeveloperJobs(firstQueue).contains(jobId));
	}

	/** A developer job without a pending pair is not pending. */
	@Test
	public void aDeveloperJobWithoutPendingPairsIsNotReturned() throws SQLException {
		int jobId = insertJob(developerId, lastQueue, 5);

		assertFalse(pendingDeveloperJobs(lastQueue).contains(jobId));
	}

	/** #233: the only pending developer job is on a queue that is not first by name. */
	@Test
	public void developerJobsExistLooksPastTheFirstActiveQueue() throws SQLException {
		insertPendingJob(developerId, lastQueue);

		assertTrue(Queues.developerJobsExist());
	}

	/** #233: same, through the admin role. */
	@Test
	public void developerJobsExistCountsAnAdminJobOnALaterQueue() throws SQLException {
		insertPendingJob(adminId, lastQueue);

		assertTrue(Queues.developerJobsExist());
	}

	@Test
	public void developerJobsExistIsFalseWhenNoActiveQueueHasOne() throws SQLException {
		// Other data in a shared database could legitimately make the answer true.
		assumeFalse("database already holds a pending developer job", anyPendingDeveloperJob());
		insertPendingJob(plainUserId, lastQueue);

		assertFalse(Queues.developerJobsExist());
	}

	private boolean anyPendingDeveloperJob() throws SQLException {
		try (Connection con = Common.getConnection()) {
			for (int queueId : activeQueueIds(con)) {
				if (!pendingDeveloperJobs(con, queueId).isEmpty()) {
					return true;
				}
			}
		}
		return false;
	}

	private static List<Integer> activeQueueIds(Connection con) throws SQLException {
		List<Integer> ids = new ArrayList<>();
		try (PreparedStatement ps = con.prepareStatement(
				"SELECT id FROM starexec.queues WHERE status = 'ACTIVE'");
				ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				ids.add(rs.getInt(1));
			}
		}
		return ids;
	}

	private static List<Integer> pendingDeveloperJobs(int queueId) throws SQLException {
		try (Connection con = Common.getConnection()) {
			return pendingDeveloperJobs(con, queueId);
		}
	}

	private static List<Integer> pendingDeveloperJobs(Connection con, int queueId)
			throws SQLException {
		List<Integer> ids = new ArrayList<>();
		try (PreparedStatement ps = con.prepareStatement(
				"SELECT id FROM starexec.GetPendingDeveloperJobs(?)")) {
			ps.setInt(1, queueId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					ids.add(rs.getInt(1));
				}
			}
		}
		return ids;
	}

	private int insertPendingJob(int userId, int queueId) throws SQLException {
		return insertJob(userId, queueId, PENDING);
	}

	private int insertJob(int userId, int queueId, int pairStatus) throws SQLException {
		try (Connection con = Common.getConnection()) {
			int jobId;
			try (PreparedStatement ps = con.prepareStatement(
					"INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size, queue_id)"
							+ " VALUES (?, ?, 1, 0, ?) RETURNING id")) {
				ps.setInt(1, userId);
				ps.setString(2, tag + "-job");
				ps.setInt(3, queueId);
				jobId = firstInt(ps);
			}
			jobIds.add(jobId);
			try (PreparedStatement ps = con.prepareStatement(
					"INSERT INTO starexec.job_pairs (job_id, status_code, primary_jobpair_data)"
							+ " VALUES (?, ?, 1)")) {
				ps.setInt(1, jobId);
				ps.setInt(2, pairStatus);
				ps.executeUpdate();
			}
			return jobId;
		}
	}

	private int insertQueue(Connection con, String name) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.queues (name, status, global_access) VALUES (?, 'ACTIVE', true)"
						+ " RETURNING id")) {
			ps.setString(1, name);
			return firstInt(ps);
		}
	}

	private int insertUser(Connection con, String role) throws SQLException {
		String email = tag + "-" + role + "@test";
		int id;
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.users (email, first_name, last_name, institution, created,"
						+ " password, disk_quota, disk_size) VALUES (?, 'D', 'User', 'I', NOW(), 'x',"
						+ " 1000000, 0) RETURNING id")) {
			ps.setString(1, email);
			id = firstInt(ps);
		}
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.user_roles (email, role) VALUES (?, ?)")) {
			ps.setString(1, email);
			ps.setString(2, role);
			ps.executeUpdate();
		}
		return id;
	}

	private static int firstInt(PreparedStatement ps) throws SQLException {
		try (ResultSet rs = ps.executeQuery()) {
			if (!rs.next()) {
				throw new IllegalStateException("No row returned");
			}
			return rs.getInt(1);
		}
	}

	private static void exec(Connection con, String sql, int id) throws SQLException {
		if (id <= 0) {
			return;
		}
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			ps.setInt(1, id);
			ps.executeUpdate();
		}
	}
}
