package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.data.database.Common;
import org.starexec.data.database.Spaces;
import org.starexec.data.database.Users;
import org.starexec.data.to.Space;
import org.starexec.exceptions.UserDeletionBlockedException;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression tests for user deletion.
 *
 * <p>Covers the defects behind "the user's files were deleted but the user is
 * still in the User list":</p>
 * <ul>
 *   <li>starexec.DeleteUser did not clear analytics_users, whose FK to users is
 *       ON DELETE RESTRICT, so deleting any user who had triggered an analytics
 *       event aborted with SQLSTATE 23503.</li>
 *   <li>The personal-space removal committed on its own connection before the
 *       user delete ran on another, so that abort left the user in place with
 *       their whole space subtree already destroyed.</li>
 *   <li>Common.endTransaction swallows a failed commit, so the caller believed
 *       it had committed and went on to delete files for a user that was still
 *       in the database.</li>
 *   <li>permissions rows reached through user_assoc.permission were never
 *       reaped, leaking one row per space membership.</li>
 * </ul>
 */
public class UsersDeletionSqlRegressionTest extends Common {

	/** Unique, identifier-safe suffix so fixtures and DDL never collide. */
	private static final String TAG = UUID.randomUUID().toString().replace("-", "");

	private Integer usersParentSpaceId;
	private Integer personalSpaceId;
	private Integer communitySpaceId;
	private Integer victimId;
	private Integer bystanderId;
	private String installedTrigger;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("UsersDeletionSqlRegressionTest");
		Common.initialize();
	}

	@After
	public void cleanUp() throws Exception {
		// Guaranteed teardown: DDL first, so a failed assertion can never leave a
		// trigger armed on starexec.users for other tests.
		dropInjectedTrigger();
		try (Connection con = Common.getConnection(); Statement st = con.createStatement()) {
			st.execute("DELETE FROM starexec.logins WHERE user_id IN "
					+ "(SELECT id FROM starexec.users WHERE email LIKE 'udel-" + TAG + "-%')");
			st.execute("DELETE FROM starexec.analytics_users WHERE user_id IN "
					+ "(SELECT id FROM starexec.users WHERE email LIKE 'udel-" + TAG + "-%')");
			st.execute("DELETE FROM starexec.user_assoc WHERE user_id IN "
					+ "(SELECT id FROM starexec.users WHERE email LIKE 'udel-" + TAG + "-%')");
			st.execute("DELETE FROM starexec.users WHERE email LIKE 'udel-" + TAG + "-%'");
			st.execute("DELETE FROM starexec.set_assoc WHERE space_id IN "
					+ "(SELECT id FROM starexec.spaces WHERE name LIKE 'udel_" + TAG + "_%') "
					+ "OR child_id IN (SELECT id FROM starexec.spaces WHERE name LIKE 'udel_" + TAG + "_%')");
			st.execute("DELETE FROM starexec.spaces WHERE name LIKE 'udel_" + TAG + "_%'");
		}
	}

	// ------------------------------------------------------------------ fixtures

	private int newPermission(Connection con, boolean leader) throws Exception {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.permissions(is_leader) VALUES (?) RETURNING id")) {
			ps.setBoolean(1, leader);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private int newSpace(Connection con, String suffix) throws Exception {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.spaces(name) VALUES (?) RETURNING id")) {
			ps.setString(1, "udel_" + TAG + "_" + suffix);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private int newUser(Connection con, String suffix, String first, String last) throws Exception {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.users(email, first_name, last_name, institution, created,"
						+ " password, disk_quota, job_pair_quota, disk_size)"
						+ " VALUES (?, ?, ?, 'test', now(), 'x', 1, 1, 0) RETURNING id")) {
			ps.setString(1, "udel-" + TAG + "-" + suffix + "@example.invalid");
			ps.setString(2, first);
			ps.setString(3, last);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private void join(Connection con, int userId, int spaceId, int permissionId) throws Exception {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.user_assoc(user_id, space_id, permission) VALUES (?, ?, ?)")) {
			ps.setInt(1, userId);
			ps.setInt(2, spaceId);
			ps.setInt(3, permissionId);
			ps.executeUpdate();
		}
	}

	/**
	 * A victim with a personal space under a "Users" parent, plus an
	 * analytics_users row - the row that used to make deletion impossible.
	 *
	 * @return the permission id backing the victim's personal-space membership
	 */
	private int buildVictimWithAnalytics() throws Exception {
		try (Connection con = Common.getConnection()) {
			usersParentSpaceId = newSpace(con, "Usersparent");
			try (PreparedStatement ps = con.prepareStatement(
					"UPDATE starexec.spaces SET name = 'Users' WHERE id = ?")) {
				ps.setInt(1, usersParentSpaceId);
				ps.executeUpdate();
			}
			personalSpaceId = newSpace(con, "personal");
			try (PreparedStatement ps = con.prepareStatement(
					"INSERT INTO starexec.set_assoc(space_id, child_id) VALUES (?, ?)")) {
				ps.setInt(1, usersParentSpaceId);
				ps.setInt(2, personalSpaceId);
				ps.executeUpdate();
			}

			victimId = newUser(con, "victim", "Udel", "Victim");
			int perm = newPermission(con, true);
			join(con, victimId, personalSpaceId, perm);

			try (PreparedStatement ps = con.prepareStatement(
					"INSERT INTO starexec.analytics_users(event_id, date_recorded, user_id)"
							+ " SELECT event_id, current_date, ? FROM starexec.analytics_events LIMIT 1")) {
				ps.setInt(1, victimId);
				ps.executeUpdate();
			}
			return perm;
		}
	}

	// ------------------------------------------------------------- DDL injection

	/**
	 * Arms a deliberate failure on deletion of exactly one user id.
	 *
	 * <p>These are real, named database objects, not session-local ones -
	 * PostgreSQL has no TEMP TRIGGER for a permanent table - so the name carries
	 * a unique tag, the WHEN clause is pinned to the throwaway id, and
	 * {@link #cleanUp()} drops them unconditionally.</p>
	 *
	 * @param deferred true for a CONSTRAINT TRIGGER that is DEFERRABLE INITIALLY
	 *                 DEFERRED, so the failure surfaces at COMMIT rather than
	 *                 during the DELETE statement
	 */
	private void injectDeletionFailure(int userId, boolean deferred) throws Exception {
		String fn = "udel_fail_" + TAG;
		String trg = "udel_trg_" + TAG;
		try (Connection con = Common.getConnection(); Statement st = con.createStatement()) {
			st.execute("CREATE OR REPLACE FUNCTION starexec." + fn + "() RETURNS trigger AS $$"
					+ " BEGIN RAISE EXCEPTION 'INJECTED DELETION FAILURE'; END; $$ LANGUAGE plpgsql");
			if (deferred) {
				st.execute("CREATE CONSTRAINT TRIGGER " + trg
						+ " AFTER DELETE ON starexec.users DEFERRABLE INITIALLY DEFERRED"
						+ " FOR EACH ROW WHEN (OLD.id = " + userId + ")"
						+ " EXECUTE FUNCTION starexec." + fn + "()");
			} else {
				st.execute("CREATE TRIGGER " + trg
						+ " BEFORE DELETE ON starexec.users"
						+ " FOR EACH ROW WHEN (OLD.id = " + userId + ")"
						+ " EXECUTE FUNCTION starexec." + fn + "()");
			}
		}
		installedTrigger = trg;
	}

	private void dropInjectedTrigger() {
		if (installedTrigger == null) {
			return;
		}
		try (Connection con = Common.getConnection(); Statement st = con.createStatement()) {
			st.execute("DROP TRIGGER IF EXISTS " + installedTrigger + " ON starexec.users");
			st.execute("DROP FUNCTION IF EXISTS starexec.udel_fail_" + TAG + "() CASCADE");
		} catch (Exception e) {
			// best effort; the object name is unique to this run
		} finally {
			installedTrigger = null;
		}
	}

	// ------------------------------------------------------------------- queries

	private int countWhere(String sql, int id) throws Exception {
		try (Connection con = Common.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
			ps.setInt(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private int countUsers(int id) throws Exception {
		return countWhere("SELECT count(*) FROM starexec.users WHERE id = ?", id);
	}

	private int countSpaces(int id) throws Exception {
		return countWhere("SELECT count(*) FROM starexec.spaces WHERE id = ?", id);
	}

	private int countPermissions(int id) throws Exception {
		return countWhere("SELECT count(*) FROM starexec.permissions WHERE id = ?", id);
	}

	// --------------------------------------------------------------------- tests

	/**
	 * The headline defect: a user carrying analytics_users rows could not be
	 * deleted at all. Before the fix this failed with SQLSTATE 23503.
	 */
	@Test
	public void deletingAUserWithAnalyticsRowsSucceedsAndReapsItsPermissions() throws Exception {
		int perm = buildVictimWithAnalytics();

		assertTrue("deleteUser should succeed for a user with analytics rows",
				Users.deleteUser(victimId));

		assertEquals("user row must be gone", 0, countUsers(victimId));
		assertEquals("analytics_users rows must be gone", 0,
				countWhere("SELECT count(*) FROM starexec.analytics_users WHERE user_id = ?", victimId));
		assertEquals("personal space must be gone", 0, countSpaces(personalSpaceId));
		assertEquals("the membership's permission row must not leak", 0, countPermissions(perm));
	}

	/**
	 * A failure raised while the DELETE statement runs must roll the personal
	 * space back. Previously the space removal had already committed on a
	 * separate connection, so it could not be undone.
	 */
	@Test
	public void statementTimeFailureRollsBackThePersonalSpace() throws Exception {
		int perm = buildVictimWithAnalytics();
		injectDeletionFailure(victimId, false);

		assertFalse("deleteUser must report failure, not success", Users.deleteUser(victimId));

		assertEquals("user must survive", 1, countUsers(victimId));
		assertEquals("personal space must survive the rolled-back attempt", 1, countSpaces(personalSpaceId));
		assertEquals("membership permission must survive", 1, countPermissions(perm));
		assertEquals("membership must survive", 1,
				countWhere("SELECT count(*) FROM starexec.user_assoc WHERE user_id = ?", victimId));
	}

	/**
	 * The commit-time case, which a normal trigger cannot reach: a DEFERRABLE
	 * INITIALLY DEFERRED constraint trigger fails inside COMMIT itself. This is
	 * the path Common.endTransaction used to swallow, letting the caller proceed
	 * to irreversible filesystem cleanup for a user that still existed.
	 */
	@Test
	public void commitTimeFailureIsNotSwallowedAndRollsEverythingBack() throws Exception {
		int perm = buildVictimWithAnalytics();
		injectDeletionFailure(victimId, true);

		assertFalse("a failure raised during COMMIT must be reported as failure",
				Users.deleteUser(victimId));

		assertEquals("user must survive a failed commit", 1, countUsers(victimId));
		assertEquals("personal space must survive a failed commit", 1, countSpaces(personalSpaceId));
		assertEquals("membership permission must survive", 1, countPermissions(perm));
	}

	/**
	 * The connection must go back to the pool usable: autoCommit restored and no
	 * transaction left open. A later borrower would otherwise inherit it.
	 */
	@Test
	public void connectionIsUsableAfterAFailedDeletion() throws Exception {
		buildVictimWithAnalytics();
		injectDeletionFailure(victimId, false);
		assertFalse(Users.deleteUser(victimId));
		dropInjectedTrigger();

		try (Connection con = Common.getConnection()) {
			assertTrue("pooled connection must have autoCommit restored", con.getAutoCommit());
			try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
				assertTrue("a subsequent request must be able to use the pool", rs.next());
			}
		}
	}

	/**
	 * Renaming a user must not strand their personal space. The old lookup
	 * rebuilt "first_last" from the users row, which stopped matching after a
	 * rename, so deletion skipped the space and orphaned the subtree.
	 */
	@Test
	public void personalSpaceStillResolvesAfterTheUserIsRenamed() throws Exception {
		buildVictimWithAnalytics();
		try (Connection con = Common.getConnection();
			 PreparedStatement ps = con.prepareStatement(
					 "UPDATE starexec.users SET first_name = 'Totally', last_name = 'Renamed' WHERE id = ?")) {
			ps.setInt(1, victimId);
			ps.executeUpdate();
		}

		Space resolved = Spaces.getPersonalSpace(victimId);
		assertNotNull("personal space must still resolve after a rename", resolved);
		assertEquals("and must be the right space", personalSpaceId.intValue(), resolved.getId());
	}

	/** A user with no personal space resolves to null rather than throwing. */
	@Test
	public void aUserWithNoPersonalSpaceResolvesToNull() throws Exception {
		try (Connection con = Common.getConnection()) {
			bystanderId = newUser(con, "nospace", "Udel", "Nospace");
		}
		assertNull("no personal space should resolve to null", Spaces.getPersonalSpace(bystanderId));
	}

	/**
	 * Two equally valid candidates must be reported, never silently picked -
	 * guessing would delete the wrong subtree.
	 */
	@Test
	public void ambiguousPersonalSpaceIsRefusedRatherThanGuessed() throws Exception {
		buildVictimWithAnalytics();
		try (Connection con = Common.getConnection()) {
			int second = newSpace(con, "personal2");
			try (PreparedStatement ps = con.prepareStatement(
					"INSERT INTO starexec.set_assoc(space_id, child_id) VALUES (?, ?)")) {
				ps.setInt(1, usersParentSpaceId);
				ps.setInt(2, second);
				ps.executeUpdate();
			}
			join(con, victimId, second, newPermission(con, true));
		}

		try {
			Spaces.getPersonalSpace(victimId);
			fail("an ambiguous personal space must raise instead of guessing");
		} catch (UserDeletionBlockedException expected) {
			// The administrator-useful detail lives in STRUCTURED fields, not in the
			// message, so no caller has to parse text to render it.
			assertEquals("the blocker must be typed",
					UserDeletionBlockedException.AMBIGUOUS_PERSONAL_SPACE, expected.getReasonCode());
			assertEquals("both candidate spaces must be reported",
					2, expected.getBlockingSpaceIds().size());
			assertEquals("candidate names must accompany the ids",
					2, expected.getBlockingSpaceNames().size());
			assertTrue("the resolved personal space must be among the candidates",
					expected.getBlockingSpaceIds().contains(personalSpaceId));
		}
	}



	/**
	 * A refusal to act must reach the caller. deleteUser used to catch every
	 * exception and return a bare false, which made "I will not guess which
	 * subtree to delete" indistinguishable from any other internal error.
	 */
	@Test
	public void ambiguityPropagatesOutOfDeleteUserInsteadOfBecomingABareFalse() throws Exception {
		buildVictimWithAnalytics();
		try (Connection con = Common.getConnection()) {
			int second = newSpace(con, "personal2");
			try (PreparedStatement ps = con.prepareStatement(
					"INSERT INTO starexec.set_assoc(space_id, child_id) VALUES (?, ?)")) {
				ps.setInt(1, usersParentSpaceId);
				ps.setInt(2, second);
				ps.executeUpdate();
			}
			join(con, victimId, second, newPermission(con, true));
		}

		try {
			Users.deleteUser(victimId);
			fail("deleteUser must propagate the ambiguity rather than returning false");
		} catch (UserDeletionBlockedException expected) {
			assertEquals("the blocker must be typed",
					UserDeletionBlockedException.AMBIGUOUS_PERSONAL_SPACE, expected.getReasonCode());
			assertEquals("the admin must be told which spaces are ambiguous",
					2, expected.getBlockingSpaceIds().size());
		}

		// and it must have refused before touching anything
		assertEquals("user must be untouched after a refusal", 1, countUsers(victimId));
		assertEquals("personal space must be untouched after a refusal", 1, countSpaces(personalSpaceId));
	}

	/**
	 * Deleting one user must not touch an unrelated user's state. This pins the
	 * blast radius of the cleanup added to DeleteUser and RemoveSubspace: a
	 * permission still referenced by a surviving membership, and one serving as a
	 * space default, must both survive, as must the bystander's own rows in the
	 * two RESTRICT tables.
	 */
	@Test
	public void deletingOneUserLeavesAnUnrelatedUsersStateUntouched() throws Exception {
		buildVictimWithAnalytics();
		final int sharedPerm;
		final int defaultPerm;
		final int defaultSpace;
		try (Connection con = Common.getConnection()) {
			bystanderId = newUser(con, "isolation", "Udel", "Bystander");
			try (PreparedStatement ps = con.prepareStatement(
					"INSERT INTO starexec.logins(user_id, login_date, ip_address, browser_agent)"
							+ " VALUES (?, now(), '127.0.0.1', 'test')")) {
				ps.setInt(1, bystanderId);
				ps.executeUpdate();
			}
			try (PreparedStatement ps = con.prepareStatement(
					"INSERT INTO starexec.analytics_users(event_id, date_recorded, user_id)"
							+ " SELECT event_id, current_date, ? FROM starexec.analytics_events LIMIT 1")) {
				ps.setInt(1, bystanderId);
				ps.executeUpdate();
			}
			sharedPerm = newPermission(con, false);
			join(con, victimId, newSpace(con, "isoshared"), sharedPerm);
			join(con, bystanderId, newSpace(con, "isoother"), sharedPerm);

			defaultPerm = newPermission(con, false);
			defaultSpace = newSpace(con, "isodefault");
			try (PreparedStatement ps = con.prepareStatement(
					"UPDATE starexec.spaces SET default_permission = ? WHERE id = ?")) {
				ps.setInt(1, defaultPerm);
				ps.setInt(2, defaultSpace);
				ps.executeUpdate();
			}
			join(con, victimId, defaultSpace, defaultPerm);
		}

		assertTrue("deleteUser should succeed", Users.deleteUser(victimId));

		assertEquals("target must be gone", 0, countUsers(victimId));
		assertEquals("bystander user must survive", 1, countUsers(bystanderId));
		assertEquals("bystander login must survive", 1,
				countWhere("SELECT count(*) FROM starexec.logins WHERE user_id = ?", bystanderId));
		assertEquals("bystander analytics row must survive", 1,
				countWhere("SELECT count(*) FROM starexec.analytics_users WHERE user_id = ?", bystanderId));
		assertEquals("bystander membership must survive", 1,
				countWhere("SELECT count(*) FROM starexec.user_assoc WHERE user_id = ?", bystanderId));
		assertEquals("a permission still used by another membership must survive",
				1, countPermissions(sharedPerm));
		assertEquals("a space default_permission must survive", 1, countPermissions(defaultPerm));
		assertEquals("the space default must still be set", 1,
				countWhere("SELECT count(*) FROM starexec.spaces WHERE id = ?"
						+ " AND default_permission IS NOT NULL", defaultSpace));
	}
}
