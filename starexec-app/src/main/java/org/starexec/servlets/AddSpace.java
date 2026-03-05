package org.starexec.servlets;

import org.starexec.constants.R;
import org.starexec.data.database.*;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.*;
import org.starexec.logger.StarLogger;
import org.starexec.util.SessionUtil;
import org.starexec.util.Util;
import org.starexec.util.Validator;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Servlet which handles incoming requests adding new spaces
 *
 * @author Tyler Jensen
 */
@SuppressWarnings("JavadocReference")
public class AddSpace extends HttpServlet {
	private static final StarLogger log = StarLogger.getLogger(AddSpace.class);

	// Request attributes
	private static final String parentSpace = "parent";
	private static final String name = "name";
	private static final String description = "desc";
	private static final String locked = "locked";
	private static final String users = "users";
	private static final String solvers = "solvers";
	private static final String benchmarks = "benchmarks";
	private static final String addSolver = "addSolver";
	private static final String addBench = "addBench";
	private static final String addUser = "addUser";
	private static final String addSpace = "addSpace";
	private static final String addJob = "addJob";
	private static final String removeSolver = "removeSolver";
	private static final String removeBench = "removeBench";
	private static final String removeUser = "removeUser";
	private static final String removeSpace = "removeSpace";
	private static final String removeJob = "removeJob";
	private static final String stickyLeaders = "sticky";

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
			// Make sure the request is valid
			ValidatorStatusCode status = isValid(request);
			if (!status.isSuccess()) {
				//attach the message as a cookie so we don't need to be parsing HTML in StarexecCommand
				// Encode to satisfy RFC 6265 (no spaces or illegal chars in cookie value)
				String rawMsg = status.getMessage() == null ? "" : status.getMessage();
				String encodedMsg = URLEncoder.encode(rawMsg, StandardCharsets.UTF_8.name());
				Cookie statusCookie = new Cookie(R.STATUS_MESSAGE_COOKIE, encodedMsg);
				statusCookie.setPath(request.getContextPath().isEmpty() ? "/" : request.getContextPath());
				response.addCookie(statusCookie);
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, status.getMessage());
				return;
			}

			int spaceId = Integer.parseInt((String) request.getParameter(parentSpace));
			int userId = SessionUtil.getUserId(request);

			// Make the space to be added and set it's basic information
			Space s = new Space();
			s.setName((String) request.getParameter(name));
			s.setDescription((String) request.getParameter(description));
			s.setLocked(Boolean.parseBoolean((String) request.getParameter(locked)));
			s.setStickyLeaders(Boolean.parseBoolean((String) request.getParameter(stickyLeaders)));
			s.setParentSpace(spaceId);

			// Make the default permissions for the space to be added
			Permission p = new Permission();
			p.setAddBenchmark(request.getParameter(addBench) != null);
			p.setAddSolver(request.getParameter(addSolver) != null);
			p.setAddSpace(request.getParameter(addSpace) != null);
			p.setAddUser(request.getParameter(addUser) != null);
			p.setAddJob(request.getParameter(addJob) != null);

			p.setRemoveBench(request.getParameter(removeBench) != null);
			p.setRemoveSolver(request.getParameter(removeSolver) != null);
			p.setRemoveSpace(request.getParameter(removeSpace) != null);
			p.setRemoveUser(request.getParameter(removeUser) != null);
			p.setRemoveJob(request.getParameter(removeJob) != null);
			p.setLeader(false);
			// Set the default permission on the space
			s.setPermission(p);

			int newSpaceId = Spaces.add(s, userId);

			//Inherit Users
			boolean inheritUsers = Boolean.parseBoolean((String) request.getParameter(users));
			log.debug("inheritUsers = " + inheritUsers);
			if (inheritUsers) {
				log.debug("Adding inherited users");
				List<User> users = Spaces.getUsers(spaceId);
				log.debug("parent users = " + users);
				for (User u : users) {
					log.debug("users = " + u.getFirstName());
					int tempId = u.getId();
					Users.associate(tempId, newSpaceId);
				}
			}

			boolean inheritSolvers = Boolean.parseBoolean((String) request.getParameter(solvers));
			log.debug("inheritSolvers = " + inheritSolvers);
			if (inheritSolvers) {
				log.debug("Adding inherited solvers");
				List<Solver> solvers = Solvers.getBySpace(spaceId);
				log.debug("parent solvers = " + solvers);
				log.debug("parent solvers size = " + solvers.size());
				for (Solver solver : solvers) {
					log.debug("solvers = " + solver.getName());
					Solvers.associate(solver.getId(), newSpaceId);
				}
			}

			boolean inheritBenchmarks = Boolean.parseBoolean((String) request.getParameter(benchmarks));
			log.debug("inheritBenchmarks = " + inheritBenchmarks);
			if (inheritBenchmarks) {
				log.debug("Adding inherited benchmarks");
				List<Benchmark> benchmarks = Benchmarks.getBySpace(spaceId);
				log.debug("parent benchmarks = " + benchmarks);
				log.debug("parent benchmarks size = " + benchmarks.size());
				for (Benchmark benchmark : benchmarks) {
					log.debug("benchmarks = " + benchmark.getName());
					Benchmarks.associate(benchmark.getId(), newSpaceId);
				}
			}


		//adds sticky users from ancestor spaces
		log.debug("adding leaders from parent spaces");
		Set<Integer> stickyUsers = Spaces.getStickyLeaders(newSpaceId);
		Permission perm = Permissions.getFullPermission();
		for (Integer id : stickyUsers) {
			Users.associate(id, newSpaceId);
			Permissions.set(id, newSpaceId, perm);
		}
		
		// Also add ALL leaders from the direct parent space
		// This ensures that community leaders can see subspaces created after their promotion
		log.debug("adding leaders from parent space (spaceId=" + spaceId + ")");
		List<User> parentLeaders = Spaces.getLeaders(spaceId);
		for (User leader : parentLeaders) {
			int leaderId = leader.getId();
			// Only add if not already added by sticky leaders logic
			if (!stickyUsers.contains(leaderId)) {
				log.debug("Adding parent leader " + leader.getEmail() + " to new subspace");
				Users.associate(leaderId, newSpaceId);
				Permissions.set(leaderId, newSpaceId, perm);
			}
		} try {
			if (Communities.isCommunity(newSpaceId)) {
				if (Communities.isCommunity(newSpaceId)) {
					Communities.createUsersSpace(newSpaceId);
				}
			}
		} catch (Exception e) {
			log.error("doPost", "Error creating Users subspace", e);
			}

			if (newSpaceId <= 0) {
				// If it failed, notify an error
				response.sendError(
						HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
						"There was an internal error adding the space to the starexec database"
				);
			} else {
				// On success, redirect to the space explorer on the new space so they can see changes
				response.addCookie(new Cookie("New_ID", String.valueOf(newSpaceId)));
				response.sendRedirect(Util.docRoot("secure/explore/spaces.jsp?id=" + newSpaceId));
			}
		} catch (Exception e) {
			log.warn("Caught Exception in AddSpace.doPost", e);
			throw e;
		}
	}

	/**
	 * Uses the Validate util to ensure the incoming request is valid. This checks for illegal characters and content
	 * length requirements to ensure it is not malicious.
	 *
	 * @return True if the request is ok to act on, false otherwise
	 */
	private ValidatorStatusCode isValid(HttpServletRequest request) {
		try {
			final String methodName = "isValid";
			log.entry(methodName);
			// Make sure the parent space id is a int
			if (!Validator.isValidPosInteger(request.getParameter(parentSpace))) {
				log.warn(methodName, "Invalid parent space ID: " + request.getParameter(parentSpace));
				return new ValidatorStatusCode(false, "The space ID needs to be an integer");
			}
			int spaceId = Integer.parseInt(request.getParameter(parentSpace));
			log.debug(methodName, "Parent space ID: " + spaceId);

			// Get name parameter early and validate null/blank before calling Validator (prevents NPE)
			String n = request.getParameter(name);
			log.debug(methodName, "Space name: " + n);
			if (n == null || n.trim().isEmpty()) {
				log.warn(methodName, "Space name is null or empty");
				return new ValidatorStatusCode(false, "A name is required for the new space");
			}
			if (!Validator.isValidSpaceName(n)) {
				log.warn(methodName, "Invalid space name: " + n);
				return new ValidatorStatusCode(
						false, "The given name is invalid-- please reference the help pages to see valid space names");
			}

			// Description: allow empty but avoid passing null to validator
			String desc = request.getParameter(description);
			log.debug(methodName, "Space description: " + desc);
			if (desc == null) {
				desc = "";
			}
			if (!Validator.isValidPrimDescription(desc)) {
				log.warn(methodName, "Invalid description: " + desc);
				return new ValidatorStatusCode(
						false,
						"The given description is invalid-- please reference the help pages to see valid description names"
				);
			}

			// Ensure the isLocked value is a parseable boolean
			String lockVal = request.getParameter(locked);
			log.debug(methodName, "Locked value: " + lockVal);
			if (lockVal == null || (!lockVal.equals("true") && !lockVal.equals("false"))) {
				log.warn(methodName, "Invalid locked value: " + lockVal);
				return new ValidatorStatusCode(false, "The 'locked' attribute needs to be either true or false");
			}

			// sticky should also be a parsable boolean
			String sticky = request.getParameter(stickyLeaders);
			log.debug(methodName, "Sticky leaders value: " + sticky);
			if (sticky != null) {
				if (!sticky.equals("true") && !sticky.equals("false")) {
					log.warn(methodName, "Invalid sticky leaders value: " + sticky);
					return new ValidatorStatusCode(
							false, "The 'sticky leaders' attribute needs to be either true or false");
				}
				// subspaces of the root can not have sticky leaders enabled
				if (spaceId == 1 && sticky.equals("true")) {
					log.warn(methodName, "Attempted to enable sticky leaders on a community");
					return new ValidatorStatusCode(false, "Communities may not enable the sticky leaders option");
				}
			}

			// Verify this user can add spaces to this space
			Permission p = SessionUtil.getPermission(request, spaceId);
			if (p == null || !p.canAddSpace()) {
				log.warn(methodName, "User does not have permission to add a space here.");
				return new ValidatorStatusCode(false, "You do not have permission to add a new space here");
			}

			// Ensure subspace name uniqueness
			if (Spaces.getSubSpaceIDbyName(spaceId, n) != -1) {
				log.warn(methodName, "Subspace name is not unique: " + n);
				return new ValidatorStatusCode(false,
											   "The subspace should have a unique name in the space. It is possible a private subspace you are not authorized to see has the same name.");
			}

			// Passed all checks
			log.info(methodName, "Validation successful");
			return new ValidatorStatusCode(true);
		} catch (Exception e) {
			log.warn("Validation error in AddSpace.isValid for parentSpace=" + request.getParameter(parentSpace) + ": " +
					 (e.getMessage()==null?e.getClass().getName():e.getMessage()), e);
		}

		// Return false if control flow is broken and ends up here
		return new ValidatorStatusCode(false, "Internal error processing request");
	}
}
