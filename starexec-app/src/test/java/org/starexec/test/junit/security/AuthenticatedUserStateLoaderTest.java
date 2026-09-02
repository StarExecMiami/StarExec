package org.starexec.test.junit.security;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.constants.R;
import org.starexec.data.database.Common;
import org.starexec.data.database.Users;
import org.starexec.data.to.AuthenticatedUserState;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The account-state loader, against a real schema.
 *
 * <p>Two properties matter more than the happy path:</p>
 * <ul>
 *   <li>role cardinality is measured, not assumed. {@code user_roles} is keyed by
 *       {@code (email, role)}, so a join that returns a scalar role is choosing a
 *       row by accident. Zero rows and two rows must both be MALFORMED, and the
 *       verdict must not depend on insertion order.</li>
 *   <li>a failure is never reported as a deletion. Every read, including the
 *       third one that goes through {@code Users.get} - which catches
 *       {@code Exception} and returns {@code null} - must yield ERROR rather
 *       than ABSENT.</li>
 * </ul>
 */
public class AuthenticatedUserStateLoaderTest extends Common {

	private static final String TAG = UUID.randomUUID().toString().replace("-", "");

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("AuthenticatedUserStateLoaderTest");
		Common.initialize();
	}

	@After
	public void cleanUp() throws Exception {
		final String fixtures = "aus-" + TAG + "-%";
		try (Connection con = Common.getConnection()) {
			try (PreparedStatement ps = con.prepareStatement(
					"DELETE FROM starexec.user_roles WHERE email LIKE ?")) {
				ps.setString(1, fixtures);
				ps.executeUpdate();
			}
			try (PreparedStatement ps = con.prepareStatement(
					"DELETE FROM starexec.users WHERE email LIKE ?")) {
				ps.setString(1, fixtures);
				ps.executeUpdate();
			}
		}
	}

	// ------------------------------------------------------------ fixtures

	private String emailFor(String suffix) {
		return "aus-" + TAG + "-" + suffix + "@example.invalid";
	}

	private int newUser(Connection con, String suffix) throws Exception {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.users(email, first_name, last_name, institution, created,"
						+ " password, disk_quota, job_pair_quota, disk_size)"
						+ " VALUES (?, 'Test', 'User', 'test', now(), 'x', 1, 1, 0) RETURNING id")) {
			ps.setString(1, emailFor(suffix));
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private void addRole(Connection con, String suffix, String role) throws Exception {
		try (PreparedStatement ps = con.prepareStatement(
				"INSERT INTO starexec.user_roles(email, role) VALUES (?, ?)")) {
			ps.setString(1, emailFor(suffix));
			ps.setString(2, role);
			ps.executeUpdate();
		}
	}

	/** A user with exactly the roles given, in the order given. */
	private int userWithRoles(String suffix, String... roles) throws Exception {
		try (Connection con = Common.getConnection()) {
			int id = newUser(con, suffix);
			for (String role : roles) {
				addRole(con, suffix, role);
			}
			return id;
		}
	}

	// ------------------------------------------------------------ ACTIVE / DENIED

	@Test
	public void aNormalUserIsActive() throws Exception {
		int id = userWithRoles("active", R.DEFAULT_USER_ROLE_NAME);
		AuthenticatedUserState state = Users.loadAuthenticatedUserState(id);

		assertEquals(AuthenticatedUserState.State.ACTIVE, state.getState());
		assertEquals(R.DEFAULT_USER_ROLE_NAME, state.getRole());
		assertNotNull(state.getUser());
		assertEquals(id, state.getUser().getId());
	}

	@Test
	public void anAdministratorIsActive() throws Exception {
		int id = userWithRoles("admin", R.ADMIN_ROLE_NAME);
		AuthenticatedUserState state = Users.loadAuthenticatedUserState(id);

		assertEquals(AuthenticatedUserState.State.ACTIVE, state.getState());
		assertEquals(R.ADMIN_ROLE_NAME, state.getRole());
	}

	@Test
	public void aSuspendedUserIsDenied() throws Exception {
		int id = userWithRoles("susp", R.SUSPENDED_ROLE_NAME);
		AuthenticatedUserState state = Users.loadAuthenticatedUserState(id);

		assertEquals(AuthenticatedUserState.State.DENIED, state.getState());
		assertEquals(R.SUSPENDED_ROLE_NAME, state.getRole());
		assertNotNull("a denied account is still an account", state.getUser());
	}

	@Test
	public void anUnauthorizedUserIsDenied() throws Exception {
		int id = userWithRoles("unauth", R.UNAUTHORIZED_ROLE_NAME);
		AuthenticatedUserState state = Users.loadAuthenticatedUserState(id);

		assertEquals(AuthenticatedUserState.State.DENIED, state.getState());
	}

	// ------------------------------------------------------------ ABSENT

	@Test
	public void aDeletedUserIsAbsent() throws Exception {
		int id = userWithRoles("gone", R.DEFAULT_USER_ROLE_NAME);
		try (Connection con = Common.getConnection()) {
			try (PreparedStatement ps = con.prepareStatement(
					"DELETE FROM starexec.user_roles WHERE email = ?")) {
				ps.setString(1, emailFor("gone"));
				ps.executeUpdate();
			}
			try (PreparedStatement ps = con.prepareStatement(
					"DELETE FROM starexec.users WHERE id = ?")) {
				ps.setInt(1, id);
				ps.executeUpdate();
			}
		}

		AuthenticatedUserState state = Users.loadAuthenticatedUserState(id);

		assertEquals(AuthenticatedUserState.State.ABSENT, state.getState());
		assertNull(state.getUser());
	}

	// ------------------------------------------------------------ cardinality

	@Test
	public void aUserWithNoRoleRowIsMalformed() throws Exception {
		int id = userWithRoles("zero");
		AuthenticatedUserState state = Users.loadAuthenticatedUserState(id);

		assertEquals("a user row with no role is not a deleted user",
				AuthenticatedUserState.State.MALFORMED, state.getState());
		assertEquals("0 role rows", state.getDiagnostic());
	}

	@Test
	public void aUserWithTwoRoleRowsIsMalformed() throws Exception {
		int id = userWithRoles("two", R.DEFAULT_USER_ROLE_NAME, R.ADMIN_ROLE_NAME);
		AuthenticatedUserState state = Users.loadAuthenticatedUserState(id);

		assertEquals(AuthenticatedUserState.State.MALFORMED, state.getState());
		assertEquals("2 role rows", state.getDiagnostic());
		assertNull("no role may be invented from an ambiguous set", state.getRole());
	}

	@Test
	public void theMultiRoleVerdictDoesNotDependOnInsertionOrder() throws Exception {
		int ascending = userWithRoles("orderA", R.ADMIN_ROLE_NAME, R.DEFAULT_USER_ROLE_NAME);
		int descending = userWithRoles("orderB", R.DEFAULT_USER_ROLE_NAME, R.ADMIN_ROLE_NAME);

		AuthenticatedUserState a = Users.loadAuthenticatedUserState(ascending);
		AuthenticatedUserState b = Users.loadAuthenticatedUserState(descending);

		assertEquals(AuthenticatedUserState.State.MALFORMED, a.getState());
		assertEquals(AuthenticatedUserState.State.MALFORMED, b.getState());
		assertEquals("the verdict must be a property of the data, not of row order",
				a.getState(), b.getState());
	}

	// ------------------------------------------------------------ read 1 and 2 failures

	@Test
	public void aFailureOnTheExistenceReadIsAnErrorNotAnAbsence() throws Exception {
		Connection broken = mock(Connection.class);
		when(broken.prepareStatement(anyString())).thenThrow(new SQLException("injected"));

		AuthenticatedUserState state = Users.loadAuthenticatedUserState(broken, 1);

		assertEquals(AuthenticatedUserState.State.ERROR, state.getState());
		assertEquals("user lookup failed", state.getDiagnostic());
	}

	@Test
	public void aFailureOnTheRoleReadIsAnErrorNotAnAbsence() throws Exception {
		int id = userWithRoles("roleerr", R.DEFAULT_USER_ROLE_NAME);
		try (Connection real = Common.getConnection()) {
			Connection partial = mock(Connection.class);
			when(partial.prepareStatement(anyString())).thenAnswer(i -> {
				String sql = i.getArgument(0);
				if (sql.contains("user_roles")) {
					throw new SQLException("injected failure in the role read");
				}
				return real.prepareStatement(sql);
			});

			AuthenticatedUserState state = Users.loadAuthenticatedUserState(partial, id);

			assertEquals(AuthenticatedUserState.State.ERROR, state.getState());
			assertEquals("role lookup failed", state.getDiagnostic());
		}
	}

	// ------------------------------------------------------ read 3 failures (the swallowing helper)

	@Test
	public void aThrownFailureInTheFinalUserConstructionIsAnError() throws Exception {
		int id = userWithRoles("thirdthrow", R.DEFAULT_USER_ROLE_NAME);
		try (Connection real = Common.getConnection()) {
			Connection partial = mock(Connection.class);
			when(partial.prepareStatement(anyString())).thenAnswer(i -> {
				String sql = i.getArgument(0);
				if (sql.contains("GetUserById")) {
					throw new SQLException("injected failure in the third read");
				}
				return real.prepareStatement(sql);
			});

			AuthenticatedUserState state = Users.loadAuthenticatedUserState(partial, id);

			assertEquals("Users.get swallows the exception and returns null; that is not a deletion",
					AuthenticatedUserState.State.ERROR, state.getState());
			assertEquals("user construction indeterminate", state.getDiagnostic());
			assertNull(state.getUser());
		}
	}

	@Test
	public void anEmptyFinalUserConstructionIsAnErrorNotAnAbsence() throws Exception {
		int id = userWithRoles("thirdempty", R.DEFAULT_USER_ROLE_NAME);
		try (Connection real = Common.getConnection()) {
			Connection partial = mock(Connection.class);
			when(partial.prepareStatement(anyString())).thenAnswer(i -> {
				String sql = i.getArgument(0);
				if (sql.contains("GetUserById")) {
					// Same shape, same one parameter, zero rows: the row vanished
					// between reads, or the NATURAL JOIN found nothing.
					return real.prepareStatement("SELECT * FROM starexec.GetUserById(?) WHERE FALSE");
				}
				return real.prepareStatement(sql);
			});

			AuthenticatedUserState state = Users.loadAuthenticatedUserState(partial, id);

			assertEquals("existence was already established, so an empty third read is indeterminate",
					AuthenticatedUserState.State.ERROR, state.getState());
			assertEquals("user construction indeterminate", state.getDiagnostic());
		}
	}

	@Test
	public void aRoleThatChangesBetweenReadsIsAnError() throws Exception {
		int id = userWithRoles("mismatch", R.DEFAULT_USER_ROLE_NAME);
		try (Connection real = Common.getConnection()) {
			Connection partial = mock(Connection.class);
			when(partial.prepareStatement(anyString())).thenAnswer(i -> {
				String sql = i.getArgument(0);
				if (sql.contains("GetUserById")) {
					// The account was promoted after the role read committed.
					return real.prepareStatement(
							"SELECT u.id, u.email, u.first_name, u.last_name, u.institution, u.created,"
									+ " u.password, u.disk_quota, u.disk_size, u.subscribed_to_reports,"
									+ " u.subscribed_to_error_logs, u.default_page_size, u.job_pair_quota,"
									+ " 'admin'::varchar(24) AS role"
									+ " FROM starexec.users u WHERE u.id = ?");
				}
				return real.prepareStatement(sql);
			});

			AuthenticatedUserState state = Users.loadAuthenticatedUserState(partial, id);

			assertEquals("the session must not carry a role the loader never validated",
					AuthenticatedUserState.State.ERROR, state.getState());
			assertEquals("role changed during refresh", state.getDiagnostic());
			assertNull(state.getUser());
		}
	}
}
