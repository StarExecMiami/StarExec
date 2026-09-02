package org.starexec.test.junit.security;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.app.SessionFilter;
import org.starexec.constants.R;
import org.starexec.data.database.Users;
import org.starexec.data.to.AuthenticatedUserState;
import org.starexec.data.to.User;
import org.starexec.util.SessionUtil;

import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Behaviour of the SessionFilter revalidation gate.
 *
 * <p>The security property under test is the number of times the filter chain is
 * invoked. A denial that logs, redirects or sets a status but still calls
 * {@code chain.doFilter} has not denied anything: the servlet runs and its
 * side effects commit. Every test therefore counts chain invocations rather
 * than inspecting the response alone.</p>
 *
 * <p>No database is required; {@link Users#loadAuthenticatedUserState(int)} is
 * stubbed so each account state can be driven independently.</p>
 */
public class SessionFilterRevocationTest {

	private static final String CTX = "/starexec";
	private static final String SECURE = CTX + "/secure/edit/account.jsp";
	private static final String SERVICE = CTX + "/services/spaces/1/users";
	private static final String INDEX = "/" + R.STAREXEC_APPNAME + "/";

	/** Counts invocations; a denial must leave this at zero. */
	private static final class CountingChain implements FilterChain {
		int calls;

		@Override
		public void doFilter(ServletRequest request, ServletResponse response) {
			calls++;
		}
	}

	private static User user(int id, String role) {
		User u = new User();
		u.setId(id);
		u.setRole(role);
		u.setEmail("u" + id + "@example.invalid");
		u.setFirstName("Test");
		u.setLastName("User");
		return u;
	}

	private static SessionDoubles.Session sessionHolding(User u) {
		SessionDoubles.Session s = new SessionDoubles.Session();
		s.attrs.put(SessionUtil.USER, u);
		return s;
	}

	// ------------------------------------------------------------ ABSENT

	@Test
	public void deletedUserDoesNotReachTheChain() throws Exception {
		User stale = user(7, R.DEFAULT_USER_ROLE_NAME);
		SessionDoubles.Session session = sessionHolding(stale);
		HttpServletRequest req = SessionDoubles.request(session, SECURE, CTX, "Mozilla/5.0");
		HttpServletResponse res = mock(HttpServletResponse.class);
		CountingChain chain = new CountingChain();

		try (MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			users.when(() -> Users.loadAuthenticatedUserState(anyInt()))
					.thenReturn(AuthenticatedUserState.absent());
			new SessionFilter().doFilter(req, res, chain);
		}

		assertEquals("a deleted user must not reach the servlet", 0, chain.calls);
	}

	@Test
	public void deletedUserSessionIsInvalidated() throws Exception {
		SessionDoubles.Session session = sessionHolding(user(7, R.DEFAULT_USER_ROLE_NAME));
		HttpServletRequest req = SessionDoubles.request(session, SECURE, CTX, "Mozilla/5.0");
		CountingChain chain = new CountingChain();

		try (MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			users.when(() -> Users.loadAuthenticatedUserState(anyInt()))
					.thenReturn(AuthenticatedUserState.absent());
			new SessionFilter().doFilter(req, mock(HttpServletResponse.class), chain);
		}

		assertTrue("the session must be invalidated", session.invalidated);
		assertFalse("invalidation must unbind the cached user", session.attrs.containsKey(SessionUtil.USER));
	}

	@Test
	public void deletedUserOnServiceEndpointGetsUnauthorizedNotRedirect() throws Exception {
		SessionDoubles.Session session = sessionHolding(user(7, R.DEFAULT_USER_ROLE_NAME));
		HttpServletRequest req = SessionDoubles.request(session, SERVICE, CTX, "Mozilla/5.0");
		HttpServletResponse res = mock(HttpServletResponse.class);
		CountingChain chain = new CountingChain();

		try (MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			users.when(() -> Users.loadAuthenticatedUserState(anyInt()))
					.thenReturn(AuthenticatedUserState.absent());
			new SessionFilter().doFilter(req, res, chain);
		}

		assertEquals(0, chain.calls);
		verify(res).sendError(Mockito.eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
		verify(res, never()).sendRedirect(anyString());
	}

	// ------------------------------------------------------------ ACTIVE

	@Test
	public void activeUserReachesTheChainExactlyOnce() throws Exception {
		User stale = user(7, R.DEFAULT_USER_ROLE_NAME);
		User fresh = user(7, R.DEFAULT_USER_ROLE_NAME);
		SessionDoubles.Session session = sessionHolding(stale);
		HttpServletRequest req = SessionDoubles.request(session, SECURE, CTX, "Mozilla/5.0");
		CountingChain chain = new CountingChain();

		try (MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			users.when(() -> Users.loadAuthenticatedUserState(anyInt()))
					.thenReturn(AuthenticatedUserState.active(fresh, R.DEFAULT_USER_ROLE_NAME));
			new SessionFilter().doFilter(req, mock(HttpServletResponse.class), chain);
		}

		assertEquals("an active user must be served exactly once", 1, chain.calls);
		assertFalse("an active user's session must survive", session.invalidated);
	}

	@Test
	public void activeUserSessionObjectIsReplacedWithTheFreshOne() throws Exception {
		User stale = user(7, R.ADMIN_ROLE_NAME);
		User fresh = user(7, R.DEFAULT_USER_ROLE_NAME);
		SessionDoubles.Session session = sessionHolding(stale);
		HttpServletRequest req = SessionDoubles.request(session, SECURE, CTX, "Mozilla/5.0");

		try (MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			users.when(() -> Users.loadAuthenticatedUserState(anyInt()))
					.thenReturn(AuthenticatedUserState.active(fresh, R.DEFAULT_USER_ROLE_NAME));
			new SessionFilter().doFilter(req, mock(HttpServletResponse.class), new CountingChain());
		}

		assertSame("the session must carry the freshly loaded user",
				fresh, session.attrs.get(SessionUtil.USER));
		assertEquals("a demotion must take effect on the next request",
				R.DEFAULT_USER_ROLE_NAME, ((User) session.attrs.get(SessionUtil.USER)).getRole());
	}

	// ------------------------------------------------------------ DENIED

	@Test
	public void suspendedUserDoesNotReachTheChain() throws Exception {
		User u = user(7, R.SUSPENDED_ROLE_NAME);
		SessionDoubles.Session session = sessionHolding(u);
		HttpServletRequest req = SessionDoubles.request(session, SECURE, CTX, "Mozilla/5.0");
		HttpServletResponse res = mock(HttpServletResponse.class);
		CountingChain chain = new CountingChain();

		try (MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			users.when(() -> Users.loadAuthenticatedUserState(anyInt()))
					.thenReturn(AuthenticatedUserState.denied(u, R.SUSPENDED_ROLE_NAME));
			new SessionFilter().doFilter(req, res, chain);
		}

		assertEquals("the baseline redirected but still served the request", 0, chain.calls);
		assertFalse("a suspended account is not a deleted one", session.invalidated);
		verify(res).sendRedirect(anyString());
	}

	@Test
	public void unauthorizedUserDoesNotReachTheChain() throws Exception {
		User u = user(7, R.UNAUTHORIZED_ROLE_NAME);
		SessionDoubles.Session session = sessionHolding(u);
		HttpServletRequest req = SessionDoubles.request(session, SECURE, CTX, "Mozilla/5.0");
		CountingChain chain = new CountingChain();

		try (MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			users.when(() -> Users.loadAuthenticatedUserState(anyInt()))
					.thenReturn(AuthenticatedUserState.denied(u, R.UNAUTHORIZED_ROLE_NAME));
			new SessionFilter().doFilter(req, mock(HttpServletResponse.class), chain);
		}

		assertEquals(0, chain.calls);
	}

	@Test
	public void suspendedUserMayStillViewTheIndexPage() throws Exception {
		User u = user(7, R.SUSPENDED_ROLE_NAME);
		SessionDoubles.Session session = sessionHolding(u);
		HttpServletRequest req = SessionDoubles.request(session, INDEX, CTX, "Mozilla/5.0");
		CountingChain chain = new CountingChain();

		try (MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			users.when(() -> Users.loadAuthenticatedUserState(anyInt()))
					.thenReturn(AuthenticatedUserState.denied(u, R.SUSPENDED_ROLE_NAME));
			new SessionFilter().doFilter(req, mock(HttpServletResponse.class), chain);
		}

		assertEquals("the index exemption is preserved behaviour", 1, chain.calls);
	}

	// ------------------------------------------------------------ MALFORMED

	@Test
	public void malformedAccountIsRefusedWithoutBeingReportedAsDeleted() throws Exception {
		SessionDoubles.Session session = sessionHolding(user(7, R.DEFAULT_USER_ROLE_NAME));
		HttpServletRequest req = SessionDoubles.request(session, SECURE, CTX, "Mozilla/5.0");
		HttpServletResponse res = mock(HttpServletResponse.class);
		CountingChain chain = new CountingChain();

		try (MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			users.when(() -> Users.loadAuthenticatedUserState(anyInt()))
					.thenReturn(AuthenticatedUserState.malformed("2 role rows"));
			new SessionFilter().doFilter(req, res, chain);
		}

		assertEquals(0, chain.calls);
		assertFalse("the account exists; do not tell the operator it was deleted", session.invalidated);
		verify(res).sendError(Mockito.eq(HttpServletResponse.SC_FORBIDDEN), anyString());
	}

	// ------------------------------------------------------------ ERROR

	@Test
	public void databaseErrorFailsClosedAndKeepsTheSession() throws Exception {
		SessionDoubles.Session session = sessionHolding(user(7, R.DEFAULT_USER_ROLE_NAME));
		HttpServletRequest req = SessionDoubles.request(session, SECURE, CTX, "Mozilla/5.0");
		HttpServletResponse res = mock(HttpServletResponse.class);
		CountingChain chain = new CountingChain();

		try (MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			users.when(() -> Users.loadAuthenticatedUserState(anyInt()))
					.thenReturn(AuthenticatedUserState.error("user lookup failed"));
			new SessionFilter().doFilter(req, res, chain);
		}

		assertEquals("an unverifiable request must not be served", 0, chain.calls);
		assertFalse("an outage must not log every user out", session.invalidated);
		verify(res).sendError(Mockito.eq(HttpServletResponse.SC_SERVICE_UNAVAILABLE), anyString());
	}

	@Test
	public void theSameSessionProceedsOnceTheDatabaseRecovers() throws Exception {
		User fresh = user(7, R.DEFAULT_USER_ROLE_NAME);
		SessionDoubles.Session session = sessionHolding(user(7, R.DEFAULT_USER_ROLE_NAME));
		HttpServletRequest req = SessionDoubles.request(session, SECURE, CTX, "Mozilla/5.0");
		CountingChain outage = new CountingChain();
		CountingChain recovered = new CountingChain();

		try (MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			users.when(() -> Users.loadAuthenticatedUserState(anyInt()))
					.thenReturn(AuthenticatedUserState.error("user lookup failed"));
			new SessionFilter().doFilter(req, mock(HttpServletResponse.class), outage);
		}
		try (MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			users.when(() -> Users.loadAuthenticatedUserState(anyInt()))
					.thenReturn(AuthenticatedUserState.active(fresh, R.DEFAULT_USER_ROLE_NAME));
			new SessionFilter().doFilter(req, mock(HttpServletResponse.class), recovered);
		}

		assertEquals(0, outage.calls);
		assertEquals("recovery must not require a new login", 1, recovered.calls);
	}

	// ------------------------------------------------------------ scope

	@Test
	public void staticResourcesAreServedWithoutAnAccountLookup() throws Exception {
		SessionDoubles.Session session = sessionHolding(user(7, R.DEFAULT_USER_ROLE_NAME));
		HttpServletRequest req = SessionDoubles.request(session, CTX + "/css/common/table.css", CTX, "Mozilla/5.0");
		CountingChain chain = new CountingChain();

		try (MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			new SessionFilter().doFilter(req, mock(HttpServletResponse.class), chain);
			users.verify(() -> Users.loadAuthenticatedUserState(anyInt()), never());
		}

		assertEquals("static assets must not pay for revalidation", 1, chain.calls);
	}

	@Test
	public void anonymousRequestsAreNotRevalidated() throws Exception {
		SessionDoubles.Session session = new SessionDoubles.Session();
		HttpServletRequest req = SessionDoubles.request(session, SECURE, CTX, "Mozilla/5.0");
		CountingChain chain = new CountingChain();

		try (MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			new SessionFilter().doFilter(req, mock(HttpServletResponse.class), chain);
			users.verify(() -> Users.loadAuthenticatedUserState(anyInt()), never());
		}

		assertEquals("the container still owns anonymous authentication", 1, chain.calls);
	}
}
