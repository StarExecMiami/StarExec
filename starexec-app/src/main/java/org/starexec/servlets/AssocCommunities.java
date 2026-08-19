package org.starexec.servlets;

import org.starexec.constants.R;
import org.starexec.data.database.Queues;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.logger.StarLogger;
import org.starexec.util.SessionUtil;
import org.starexec.util.Util;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * Servlet that handles requests for assocating queues with particular communities
 *
 * @author Eric
 */
public class AssocCommunities extends HttpServlet {
	private static final StarLogger log = StarLogger.getLogger(AssocCommunities.class);

	// Request attributes
	private static final String name = "name";
	private static final String communities = "community";

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		try {
			int userId = SessionUtil.getUserId(request);
			if (!GeneralSecurity.hasAdminWritePrivileges(userId)) {
				String message = "You do not have permission to perform this operation";
				response.addCookie(Util.createEncodedCookie(R.STATUS_MESSAGE_COOKIE, message));
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, message);
				// sendError commits a response; it does not end the method. Without this
				// return the admin check decided nothing: execution fell straight through
				// to setQueueCommunityAccess, letting any authenticated user rewrite the
				// queue-to-community access list while being told they were forbidden.
				return;
			}
			String queue_name = (String) request.getParameter(name);
			int queue_id = Queues.getIdByName(queue_name);
			List<Integer> community_ids = Util.toIntegerList(request.getParameterValues(communities));
			boolean result = Queues.setQueueCommunityAccess(community_ids, queue_id);
			if (result) {
				response.sendRedirect(Util.docRoot("secure/admin/cluster.jsp"));
			} else {
				response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			}
		} catch (Exception e) {
			log.warn("Caught Exception in AssocCommunities.doPost.", e);
			throw e;
		}
	}
}
