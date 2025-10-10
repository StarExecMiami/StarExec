package org.starexec.app;

import org.starexec.constants.R;
import org.starexec.data.database.*;
import org.starexec.data.security.GeneralSecurity;
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
 * This class is responsible for intercepting all requests to protected resources
 * and checking if the user has the appropriate session variables set to continue
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
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
		try {
			final String method = "doFilter";
			// Cast the servlet request to an httpRequest so we have access to the session
			HttpServletRequest httpRequest = (HttpServletRequest) request;
			log.debug(method, "Request URI: "+httpRequest.getRequestURI());

			boolean isCommandRequest = isFromCommand(httpRequest);
			if (isCommandRequest) {
				log.debug(method, "isFromCommand: true");
			}

			HttpSession session = httpRequest.getSession();

			// Allow access to public resources
			if (httpRequest.getRequestURI().startsWith(httpRequest.getContextPath() + "/public/") ||
				httpRequest.getRequestURI().startsWith(httpRequest.getContextPath() + "/login") ||
				httpRequest.getRequestURI().startsWith(httpRequest.getContextPath() + "/j_security_check") ||
				httpRequest.getRequestURI().startsWith(httpRequest.getContextPath() + "/assets/")) {
				chain.doFilter(request, response);
				return;
			}

			HttpServletResponse httpResponse = (HttpServletResponse) response;

			// Bridge between container-managed security and application's session management
			if (SessionUtil.getUser(httpRequest) == null && httpRequest.getRemoteUser() != null) {
				String username = httpRequest.getRemoteUser();
				log.debug(method, "User is authenticated by container but not in session. Initializing session for: " + username);
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

			// If the user is logged in...
			if (SessionUtil.getUser(httpRequest) != null && SessionUtil.getUser(httpRequest).getId() != R.PUBLIC_USER_ID) {
				User user = SessionUtil.getUser(httpRequest);
				String userEmail = user.getEmail();
				// Check if they have the necessary user SessionUtil stored in their session
				int userId = user.getId();
				log.debug(method, "User Id of request was: " + userId);
				log.debug(method, "User email of request was: " + userEmail);

				if (R.DEBUG_MODE_ACTIVE) {
					log.debug(method, "Debug mode is active.");
					if (!GeneralSecurity.hasAdminReadPrivileges(userId)) {
						log.debug(method, "User does not have admin read privileges, redirecting to index...");
						httpRequest.getSession().invalidate();
						httpResponse.sendRedirect(Util.docRoot(""));
						return;
					}
				}
				log.debug(method, "User role was found to be "+user.getRole());
				//suspended and unauthorized users cannot utilize the system: always place them back on the index page
				//whenever they try to access anything secure.
				if (user.getRole().equals(R.SUSPENDED_ROLE_NAME) || user.getRole().equals(R.UNAUTHORIZED_ROLE_NAME)) {
					if (!httpRequest.getRequestURI().equals("/" + R.STAREXEC_APPNAME + "/")) {
						log.debug(method, "Redirecting "+user.getRole()+" user to index.");
						httpResponse.sendRedirect(Util.docRoot(""));
					}
				}
			} else {
				// User not logged in - let Tomcat's security system handle authentication
				// Do NOT redirect manually, as this interferes with j_security_check
				log.debug(method, "User not logged in, letting container handle authentication.");
				// Continue with the filter chain to allow Tomcat's authentication to work
			}

			// Be nice and pass on the request to the next filter
			chain.doFilter(request, response);
		} catch (Throwable t) {
			log.debug("Caught throwable in doFilter. ", t);
			throw t;
		}
	}

	/**
	 * Adds a record to the database that represents the login
	 * @param user The user that just logged in
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
		// Get the number of unique logins that have occurred since the last report was sent and
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
