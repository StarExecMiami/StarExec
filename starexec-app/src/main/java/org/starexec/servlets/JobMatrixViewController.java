package org.starexec.servlets;

import org.starexec.data.database.Spaces;
import org.starexec.data.to.Job;
import org.starexec.data.to.JobSpace;
import org.starexec.exceptions.StarExecException;
import org.starexec.logger.StarLogger;
import org.starexec.util.SessionUtil;
import org.starexec.util.matrixView.Matrix;
import org.starexec.util.matrixView.MatrixViewUtil;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Controller for the job matrix view. Loads job and matrix data in the servlet layer
 * and forwards to a view-only JSP under WEB-INF, avoiding any data access from the view
 * (TD-001: prevents connection pool risk from exceptions during JSP render).
 * <p>
 * TODO (technical debt): This servlet is mapped to /secure/details/jobMatrixView.jsp for
 * backward compatibility with existing JS and tests. A future refactor should standardise
 * URLs to a RESTful scheme (e.g. /secure/details/jobMatrix) and update all references.
 */
public class JobMatrixViewController extends HttpServlet {

	private static final StarLogger log = StarLogger.getLogger(JobMatrixViewController.class);

	private static final String PARAM_STAGE = "stage";
	private static final String PARAM_JOB_SPACE_ID = "jobSpaceId";
	private static final String VIEW_PATH = "/WEB-INF/secure/details/jobMatrixView.jsp";

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		final String method = "doGet";
		log.entry(method);

		int stageNumber;
		int jobSpaceId;
		try {
			String stageParam = request.getParameter(PARAM_STAGE);
			String jobSpaceParam = request.getParameter(PARAM_JOB_SPACE_ID);
			if (stageParam == null || stageParam.isEmpty() || jobSpaceParam == null || jobSpaceParam.isEmpty()) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, "stage and jobSpaceId are required");
				return;
			}
			stageNumber = Integer.parseInt(stageParam);
			jobSpaceId = Integer.parseInt(jobSpaceParam);
		} catch (NumberFormatException e) {
			log.warn(method, "Invalid stage or jobSpaceId", e);
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "The given job id was in an invalid format");
			return;
		}

		int userId;
		try {
			userId = SessionUtil.getUserId(request);
		} catch (Exception e) {
			log.warn(method, "Could not get user from session", e);
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Not authenticated");
			return;
		}

		JobSpace space = Spaces.getJobSpace(jobSpaceId);
		if (space == null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, "Job space not found");
			return;
		}

		try {
			Job job = MatrixViewUtil.getJobIfAvailableToUser(space.getJobId(), userId, response);
			if (job == null) {
				// MatrixViewUtil already sent the appropriate error response
				return;
			}

			Matrix matrix = Matrix.getMatrixForJobSpaceFromJobAndStageNumber(job, jobSpaceId, stageNumber);
			request.setAttribute("matrix", matrix);
			request.setAttribute("job", job);
			request.setAttribute("jobSpaceId", jobSpaceId);
			request.setAttribute("stage", stageNumber);
			request.getRequestDispatcher(VIEW_PATH).forward(request, response);
			log.exit(method);
		} catch (StarExecException e) {
			log.warn(method, "Error building matrix or loading job for jobSpaceId=" + jobSpaceId + ", stage=" + stageNumber, e);
			String message = e.getMessage() != null ? e.getMessage() : "Error loading matrix";
			if (message.contains("must be initialized") || message.contains("could not be obtained")) {
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, message);
			} else {
				response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, message);
			}
			return;
		} catch (Exception e) {
			log.error(method, "Unexpected error loading job matrix view", e);
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "An unexpected error occurred. Please try again or report the error.");
			return;
		}
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
	}
}
