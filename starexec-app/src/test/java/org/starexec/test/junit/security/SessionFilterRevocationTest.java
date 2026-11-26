package org.starexec.test.junit.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.app.CsrfFilter;
import org.starexec.app.RESTServices;
import org.starexec.app.SessionFilter;
import org.starexec.constants.R;
import org.starexec.data.database.Users;
import org.starexec.data.to.AuthenticatedUserState;
import org.starexec.data.to.User;
import org.starexec.util.CsrfUtil;
import org.starexec.util.SessionUtil;

import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

		assertEquals(0, chain.calls, "a deleted user must not reach the servlet");
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

		assertTrue(session.invalidated, "the session must be invalidated");
		assertFalse(session.attrs.containsKey(SessionUtil.USER), "invalidation must unbind the cached user");
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

		assertEquals(1, chain.calls, "an active user must be served exactly once");
		assertFalse(session.invalidated, "an active user's session must survive");
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

		assertSame(fresh,
				session.attrs.get(SessionUtil.USER), "the session must carry the freshly loaded user");
		assertEquals(R.DEFAULT_USER_ROLE_NAME,
				((User) session.attrs.get(SessionUtil.USER)).getRole(), "a demotion must take effect on the next request");
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

		assertEquals(0, chain.calls, "the baseline redirected but still served the request");
		assertFalse(session.invalidated, "a suspended account is not a deleted one");
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

		assertEquals(1, chain.calls, "the index exemption is preserved behaviour");
	}

	@ParameterizedTest
	@ValueSource(strings = {R.SUSPENDED_ROLE_NAME, R.UNAUTHORIZED_ROLE_NAME})
	public void deniedAccountCanLogOutWithCsrfToken(String role) throws Exception {
		User user = user(7, role);
		SessionDoubles.Session session = sessionHolding(user);
		HttpServletRequest request = SessionDoubles.request(
				session, CTX + "/services/session/logout", CTX, "Mozilla/5.0");
		HttpServletResponse response = mock(HttpServletResponse.class);
		String token = "test-csrf-value";
		session.attrs.put(CsrfUtil.CSRF_TOKEN_ATTR, token);
		when(request.getMethod()).thenReturn("POST");
		when(request.getServletPath()).thenReturn("/services");

		FilterChain logout = (req, res) -> new RESTServices().doInvalidateSession((HttpServletRequest) req);
		FilterChain authenticated = (req, res) -> new SessionFilter().doFilter(req, res, logout);
		try (MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			users.when(() -> Users.loadAuthenticatedUserState(anyInt()))
					.thenReturn(AuthenticatedUserState.denied(user, role));
			new CsrfFilter().doFilter(request, response, authenticated);
			verify(response).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
			assertFalse(session.invalidated);

			when(request.getHeader(CsrfFilter.CSRF_HEADER)).thenReturn(token);
			new CsrfFilter().doFilter(request, response, authenticated);
			assertTrue(session.invalidated);
		}
	}

	@Test
	public void deniedAccountCannotUseGetForTheLogoutException() throws Exception {
		String role = R.SUSPENDED_ROLE_NAME;
		User user = user(7, role);
		SessionDoubles.Session session = sessionHolding(user);
		HttpServletRequest request = SessionDoubles.request(
				session, CTX + "/services/session/logout", CTX, "Mozilla/5.0");
		when(request.getMethod()).thenReturn("GET");
		CountingChain chain = new CountingChain();
		try (MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			users.when(() -> Users.loadAuthenticatedUserState(anyInt()))
					.thenReturn(AuthenticatedUserState.denied(user, role));
			new SessionFilter().doFilter(request, mock(HttpServletResponse.class), chain);
		}
		assertEquals(0, chain.calls);
		assertFalse(session.invalidated);
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
		assertFalse(session.invalidated, "the account exists; do not tell the operator it was deleted");
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

		assertEquals(0, chain.calls, "an unverifiable request must not be served");
		assertFalse(session.invalidated, "an outage must not log every user out");
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
		assertEquals(1, recovered.calls, "recovery must not require a new login");
	}

	// ------------------------------------------------------------ scope

	@ParameterizedTest
	@ValueSource(strings = {"/css/common/table.css", "/images/favicon.svg", "/favicon.ico"})
	public void staticResourcesAreServedWithoutAnAccountLookup(String assetPath) throws Exception {
		SessionDoubles.Session session = sessionHolding(user(7, R.DEFAULT_USER_ROLE_NAME));
		HttpServletRequest req = SessionDoubles.request(session, CTX + assetPath, CTX, "Mozilla/5.0");
		CountingChain chain = new CountingChain();

		try (MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			new SessionFilter().doFilter(req, mock(HttpServletResponse.class), chain);
			users.verify(() -> Users.loadAuthenticatedUserState(anyInt()), never());
		}

		assertEquals(1, chain.calls, "static assets must not pay for revalidation: " + assetPath);
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

		assertEquals(1, chain.calls, "the container still owns anonymous authentication");
	}
}
