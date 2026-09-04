package org.starexec.test.junit.security;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.app.SessionFilter;
import org.starexec.constants.R;
import org.starexec.data.database.Common;
import org.starexec.data.to.User;
import org.starexec.test.util.DatabaseTestSupport;
import org.starexec.util.SessionUtil;

import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The primary regression: a deleted user holding a live session must not be able
 * to commit a state change.
 *
 * <p>Nothing is stubbed. A real account is created, a real session is built
 * around it, the account is really deleted, and the filter is driven with the
 * real database-backed loader. The chain double stands in for the servlet and
 * performs the write a servlet would - inserting a processor row, which has no
 * foreign key to {@code users} and so survives its creator with no record of
 * who made it.</p>
 *
 * <p>Both legs are measured. The pre-delete leg must serve the request and
 * commit the row, otherwise a zero in the post-delete leg would prove only that
 * the fixture was broken.</p>
 */
public class StaleSessionProcessorRevocationTest extends Common {

	private static final String TAG = UUID.randomUUID().toString().replace("-", "");
	private static final String CTX = "/starexec";
	private static final String TARGET = CTX + "/secure/processors/add";

	private Integer communityId;
	private Integer userId;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("StaleSessionProcessorRevocationTest");
		Common.initialize();
	}

	@After
	public void cleanUp() throws Exception {
		try (Connection con = Common.getConnection(); Statement st = con.createStatement()) {
			st.execute("DELETE FROM starexec.processors WHERE name LIKE 'ssrev_" + TAG + "_%'");
			st.execute("DELETE FROM starexec.user_roles WHERE email LIKE 'ssrev-" + TAG + "-%'");
			st.execute("DELETE FROM starexec.users WHERE email LIKE 'ssrev-" + TAG + "-%'");
			st.execute("DELETE FROM starexec.spaces WHERE name LIKE 'ssrev_" + TAG + "_%'");
		}
	}

	/** Stands in for the servlet: counts entries and commits the state change. */
	private final class WritingChain implements FilterChain {
		int calls;

		@Override
		public void doFilter(ServletRequest request, ServletResponse response) {
			calls++;
			try (Connection con = Common.getConnection();
					PreparedStatement ps = con.prepareStatement(
							"INSERT INTO starexec.processors(name, path, community, disk_size)"
									+ " VALUES (?, '/tmp/ssrev', ?, 0)")) {
				ps.setString(1, "ssrev_" + TAG + "_proc" + calls);
				ps.setInt(2, communityId);
				ps.executeUpdate();
			} catch (Exception e) {
				throw new IllegalStateException("chain double could not write", e);
			}
		}
	}

	private int processorRows() throws Exception {
		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement(
						"SELECT count(*) FROM starexec.processors WHERE name LIKE ?")) {
			ps.setString(1, "ssrev_" + TAG + "_%");
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private void buildFixture() throws Exception {
		try (Connection con = Common.getConnection()) {
			try (PreparedStatement ps = con.prepareStatement(
					"INSERT INTO starexec.spaces(name) VALUES (?) RETURNING id")) {
				ps.setString(1, "ssrev_" + TAG + "_community");
				try (ResultSet rs = ps.executeQuery()) {
					rs.next();
					communityId = rs.getInt(1);
				}
			}
			try (PreparedStatement ps = con.prepareStatement(
					"INSERT INTO starexec.users(email, first_name, last_name, institution, created,"
							+ " password, disk_quota, job_pair_quota, disk_size)"
							+ " VALUES (?, 'Stale', 'Session', 'test', now(), 'x', 1, 1, 0) RETURNING id")) {
				ps.setString(1, "ssrev-" + TAG + "-victim@example.invalid");
				try (ResultSet rs = ps.executeQuery()) {
					rs.next();
					userId = rs.getInt(1);
				}
			}
			try (PreparedStatement ps = con.prepareStatement(
					"INSERT INTO starexec.user_roles(email, role) VALUES (?, ?)")) {
				ps.setString(1, "ssrev-" + TAG + "-victim@example.invalid");
				ps.setString(2, R.DEFAULT_USER_ROLE_NAME);
				ps.executeUpdate();
			}
		}
	}

	private void deleteTheAccount() throws Exception {
		try (Connection con = Common.getConnection(); Statement st = con.createStatement()) {
			st.execute("DELETE FROM starexec.user_roles WHERE email = 'ssrev-" + TAG + "-victim@example.invalid'");
			st.execute("DELETE FROM starexec.users WHERE id = " + userId);
		}
	}

	@Test
	public void aDeletedUserCannotCommitAStateChangeThroughAStaleSession() throws Exception {
		buildFixture();

		User cached = new User();
		cached.setId(userId);
		cached.setEmail("ssrev-" + TAG + "-victim@example.invalid");
		cached.setRole(R.DEFAULT_USER_ROLE_NAME);
		cached.setFirstName("Stale");
		cached.setLastName("Session");

		SessionDoubles.Session session = new SessionDoubles.Session();
		session.attrs.put(SessionUtil.USER, cached);
		HttpServletRequest request = SessionDoubles.request(session, TARGET, CTX, "Mozilla/5.0");

		// Leg 1: while the account exists, the request must be served and the
		// state change must land. Without this the post-delete zero proves nothing.
		WritingChain before = new WritingChain();
		new SessionFilter().doFilter(request, mock(HttpServletResponse.class), before);
		System.out.println("SSREV|pre.chainInvocations|" + before.calls);
		System.out.println("SSREV|pre.processorRowsCommitted|" + processorRows());
		assertEquals("fixture check: a live user must be served", 1, before.calls);
		assertEquals("fixture check: the servlet double must really write", 1, processorRows());

		// The account is deleted. The session object is untouched and still holds
		// the user, exactly as a logged-in browser would.
		deleteTheAccount();
		int rowsAtDeletion = processorRows();

		WritingChain after = new WritingChain();
		new SessionFilter().doFilter(request, mock(HttpServletResponse.class), after);

		int rowsAfter = processorRows();
		System.out.println("SSREV|post.chainInvocations|" + after.calls);
		System.out.println("SSREV|post.processorRowsCommitted|" + (rowsAfter - rowsAtDeletion));
		System.out.println("SSREV|post.sessionInvalidated|" + session.invalidated);

		assertEquals("the deleted user must not reach the servlet", 0, after.calls);
		assertEquals("no state change may commit after deletion", rowsAtDeletion, rowsAfter);
		assertTrue("the stale session must be invalidated", session.invalidated);
	}
}
