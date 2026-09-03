package org.starexec.app;

import org.starexec.constants.R;
import org.starexec.data.database.*;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.to.AuthenticatedUserState;
import org.starexec.data.to.User;
import org.starexec.logger.StarLogger;
import org.starexec.util.SessionUtil;
import org.starexec.util.Util;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * This class is responsible for intercepting all requests to protected
 * resources
 * and checking if the user has the appropriate session variables set to
 * continue
 * using the website. As a side-effect, this is where newly logged in users
 * are detected and logged.
 *
 * @author Tyler Jensen
 */
public class SessionFilter implements Filter {
	private static final StarLogger log = StarLogger.getLogger(SessionFilter.class);

	/**
	 * This RegEx is used to match known StarExecCommand User-Agent headers.
	 * Until r26351 StarExecCommand was not setting a User-Agent header, so it
	 * would just send the default "Apache-HttpClient" string. For now, we may
	 * as well just check for either of them.
	 */
	private static final Pattern StarExecCommand = Pattern.compile("\\AStarExecCommand|\\AApache-HttpClient/");

	/**
	 * Detects requests originating from StarExecCommand
	 * 
	 * @param request HTTP Request
	 * @return true if request is from StarExecCommand, false otherwise
	 */
	private static boolean isFromCommand(HttpServletRequest request) {
		final String userAgent = request.getHeader("User-Agent");
		return userAgent != null
				&& StarExecCommand.matcher(userAgent).find();
	}

	/** This RegEx is used to match known Python API User-Agent headers. */
	private static final Pattern PythonUserAgent = Pattern.compile("starexec\\.py");

	/**
	 * Detects requests originating from StarExecCommand
	 * 
	 * @param request HTTP Request
	 * @return true if request is from StarExecCommand, false otherwise
	 */
	private static boolean isFromPython(HttpServletRequest request) {
		final String userAgent = request.getHeader("User-Agent");
		return userAgent != null
				&& PythonUserAgent.matcher(userAgent).find();
	}

	@Override
	public void destroy() {
		// Do nothing
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		try {
			final String method = "doFilter";
			// Cast the servlet request to an httpRequest so we have access to the session
			HttpServletRequest httpRequest = (HttpServletRequest) request;
			log.trace(method, "Request URI: " + httpRequest.getRequestURI());

			boolean isCommandRequest = isFromCommand(httpRequest);
			if (isCommandRequest) {
				log.trace(method, "isFromCommand: true");
			}

			// Allow access to public resources and authentication endpoints FIRST
			// Do NOT touch the session for j_security_check or login pages to avoid
			// interfering with Tomcat's FormAuthenticator
			String requestURI = httpRequest.getRequestURI();
			String contextPath = httpRequest.getContextPath();

			// Use explicit paths for static resources instead of file extensions
			// to prevent accidentally bypassing auth for protected resources
			if (requestURI.startsWith(contextPath + "/public/") ||
					requestURI.startsWith(contextPath + "/login") ||
					requestURI.startsWith(contextPath + "/j_security_check") ||
					requestURI.startsWith(contextPath + "/assets/") ||
					requestURI.startsWith(contextPath + "/css/") ||
					requestURI.startsWith(contextPath + "/js/") ||
					requestURI.startsWith(contextPath + "/images/")) {
				chain.doFilter(request, response);
				return;
			}

			// Do not create a session eagerly for every request. Creating a session
			// before the container's FormAuthenticator has a chance to save the
			// original request can lead to a session-id mismatch during FORM
			// authentication (observed as HTTP 408 "login timeout"). Use
			// getSession(false) and only create a session when we need to bridge
			// container-managed authentication into the application's session.
			HttpSession session = httpRequest.getSession(false);
			HttpServletResponse httpResponse = (HttpServletResponse) response;

			// Bridge between container-managed security and application's session
			// management
			if (SessionUtil.getUser(httpRequest) == null && httpRequest.getRemoteUser() != null) {
				String username = httpRequest.getRemoteUser();
				log.debug(method,
						"User is authenticated by container but not in session. Initializing session for: " + username);
				User user = Users.get(username);
				if (user != null) {
					if (session == null) {
						session = httpRequest.getSession(true);
					}
					session.setAttribute(SessionUtil.USER, user);
					logUserLogin(user, httpRequest);
				} else {
					log.error(method, "Could not find user in database for authenticated user: " + username);
				}
			}

			// Revalidate the session against the database before the request is
			// allowed to reach any servlet. The session caches a User object at
			// login and, before this gate existed, never consulted the database
			// again: deleting or suspending an account left every open session of
			// that account fully authorised until the user chose to log out.
			//
			// This provides next-request revocation. A request already admitted
			// when the deletion commits is not interrupted and may complete
			// afterwards; closing that window needs transaction-level revocation,
			// which is deliberately out of scope here.
			User sessionUser = SessionUtil.getUser(httpRequest);
			if (sessionUser != null && sessionUser.getId() != R.PUBLIC_USER_ID) {
				HttpSession live = httpRequest.getSession(false);
				AuthenticatedUserState account = Users.loadAuthenticatedUserState(sessionUser.getId());
				log.trace(method, "Account state for id=" + sessionUser.getId() + " is " + account.getState());

				switch (account.getState()) {
					case ACTIVE:
						// Replace the cached object, so a role change that does not
						// deny access still takes effect on the next request.
						if (live != null) {
							live.setAttribute(SessionUtil.USER, account.getUser());
						}
						break;

					case DENIED:
						if (live != null) {
							live.setAttribute(SessionUtil.USER, account.getUser());
						}
						// Preserved product behaviour: a suspended or unauthorized
						// user is returned to the index page rather than logged out,
						// and is still allowed to view that page.
						if (!isIndexPage(httpRequest)) {
							log.debug(method, "Denying " + account.getRole() + " account id=" + sessionUser.getId());
							rejectDeniedAccount(httpRequest, httpResponse, account.getRole());
							return;
						}
						break;

					case ABSENT:
						// Established by a successful query: the account is gone.
						log.info(method, "Revoking session for deleted user id=" + sessionUser.getId());
						if (live != null) {
							live.invalidate();
						}
						rejectUnauthenticated(httpRequest, httpResponse);
						return;

					case MALFORMED:
						// The row exists but its role data is unusable. Do not
						// invalidate: that would report the account as deleted.
						log.info(method, "Refusing malformed account id=" + sessionUser.getId()
								+ " (" + account.getDiagnostic() + ")");
						httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN,
								"This account's role configuration is invalid. Please contact an administrator.");
						return;

					case ERROR:
					default:
						// The state could not be established, so entitlement has not
						// been established either. Fail closed for this request only
						// and keep the session, so recovery needs no new login.
						log.info(method, "Account state indeterminate for id=" + sessionUser.getId()
								+ " (" + account.getDiagnostic() + ")");
						httpResponse.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
								"Account verification is temporarily unavailable. Please retry.");
						return;
				}
			}

			// If the user is logged in...
			if (SessionUtil.getUser(httpRequest) != null
					&& SessionUtil.getUser(httpRequest).getId() != R.PUBLIC_USER_ID) {
				User user = SessionUtil.getUser(httpRequest);
				String userEmail = user.getEmail();
				// Check if they have the necessary user SessionUtil stored in their session
				int userId = user.getId();
				log.trace(method, "User Id of request was: " + userId);
				log.trace(method, "User email of request was: " + userEmail);

				if (R.DEBUG_MODE_ACTIVE) {
					log.trace(method, "Debug mode is active.");
					if (!GeneralSecurity.hasAdminReadPrivileges(userId)) {
						log.debug(method, "User does not have admin read privileges, redirecting to index...");
						httpRequest.getSession().invalidate();
						httpResponse.sendRedirect(Util.docRoot(""));
						return;
					}
				}
				log.trace(method, "User role was found to be " + user.getRole());
				// Suspended and unauthorized users are handled by the DENIED branch
				// of the revalidation gate above, which terminates the chain. The
				// check that used to live here called sendRedirect without
				// returning, so the request continued to the servlet anyway.
			} else {
				// User not logged in - let Tomcat's security system handle authentication
				// Do NOT redirect manually, as this interferes with j_security_check
				log.trace(method, "User not logged in, letting container handle authentication.");
				// Continue with the filter chain to allow Tomcat's authentication to work
			}

			// Be nice and pass on the request to the next filter
			chain.doFilter(request, response);
		} catch (Throwable t) {
			log.debug("Caught throwable in doFilter. ", t);
			throw t;
		}
	}

	/** The index page, which a denied account is still permitted to view. */
	private static boolean isIndexPage(HttpServletRequest request) {
		return request.getRequestURI().equals("/" + R.STAREXEC_APPNAME + "/");
	}

	/** Requests under /services/ are API calls; a redirect would be parsed as data. */
	private static boolean isServiceRequest(HttpServletRequest request) {
		return request.getRequestURI().startsWith(request.getContextPath() + "/services/");
	}

	/** StarExecCommand and starexec.py cannot follow an HTML redirect meaningfully. */
	private static boolean isProgrammaticClient(HttpServletRequest request) {
		return isFromCommand(request) || isFromPython(request);
	}

	/**
	 * The account behind this session no longer exists. The session has already
	 * been invalidated by the caller.
	 */
	private void rejectUnauthenticated(HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (isProgrammaticClient(request) || isServiceRequest(request)) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "This account no longer exists.");
			return;
		}
		response.addCookie(Util.createEncodedCookie(R.STATUS_MESSAGE_COOKIE,
				"This account no longer exists. Please contact an administrator if that is unexpected."));
		response.sendRedirect(Util.docRoot(""));
	}

	/** The account exists but its role forbids use of the system. */
	private void rejectDeniedAccount(HttpServletRequest request, HttpServletResponse response, String role)
			throws IOException {
		if (isProgrammaticClient(request) || isServiceRequest(request)) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "This account is " + role + ".");
			return;
		}
		response.sendRedirect(Util.docRoot(""));
	}

	/**
	 * Adds a record to the database that represents the login
	 * 
	 * @param user    The user that just logged in
	 * @param request The request containing data required to log
	 */
	private void logUserLogin(User user, HttpServletRequest request) {
		// Log the regular application log
		log.info(String.format("%s [%s] logged in.", user.getFullName(), user.getEmail()));

		if (isFromCommand(request)) {
			Analytics.STAREXECCOMMAND_LOGIN.record(user.getId());
		}

		if (isFromPython(request)) {
			Analytics.PYTHON_API_LOGIN.record(user.getId());
		}

		String ip = request.getRemoteAddr();
		String rawBrowser = request.getHeader("user-agent");

		// Also save in the database to maintain a historical record
		Common.addLoginRecord(user.getId(), ip, rawBrowser);
		// Get the number of unique logins that have occurred since the last report was
		// sent and
		// record it in the reports table.
		Integer uniqueLogins = Logins.getNumberOfUniqueLogins();
		if (uniqueLogins != null) {
			log.debug("Number of unique logins: " + uniqueLogins);
			Reports.setEventOccurrencesNotRelatedToQueue("unique logins", uniqueLogins);
		} else {
			log.error("Could not get number of unique logins from logins table.");
		}
	}

	@Override
	public void init(FilterConfig args) throws ServletException {
		// Do nothing
	}
}
