<%@page contentType="text/html" pageEncoding="UTF-8"
        import="org.starexec.constants.DB,org.starexec.constants.R,org.starexec.data.database.Permissions, org.starexec.data.database.Solvers,org.starexec.data.database.Websites, org.starexec.data.security.GeneralSecurity, org.starexec.data.security.SpaceSecurity, org.starexec.data.to.Solver, org.starexec.data.to.Website.WebsiteType, org.starexec.util.SessionUtil" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%@taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%
	try {
		int userId = SessionUtil.getUserId(request);
		int solverId = Integer.parseInt(request.getParameter("id"));
		request.setAttribute("solverNameLen", DB.SOLVER_NAME_LEN);
		request.setAttribute("solverDescLen", DB.SOLVER_DESC_LEN);
		request.setAttribute("nameRegex", R.PRIMITIVE_NAME_PATTERN);
		Solver s = null;
		if (Permissions.canUserSeeSolver(solverId, userId)) {
			s = Solvers.get(solverId);
		}

		if (s != null) {
			// Ensure the user visiting this page is the owner of the solver or has admin read privileges
			if (userId == s.getUserId() ||
					GeneralSecurity.hasAdminReadPrivileges(userId)) {
				request.setAttribute("solver", s);
				request.setAttribute("sites", Websites.getAllForHTML(solverId,
				                                                     WebsiteType.SOLVER
				));
				// Validate contextSpaceId for "back" link (IDOR: only expose if user can see that space)
				String contextSpaceIdParam = request.getParameter("contextSpaceId");
				if (contextSpaceIdParam != null && !contextSpaceIdParam.trim().isEmpty()) {
					try {
						int contextSpaceId = Integer.parseInt(contextSpaceIdParam.trim());
						if (contextSpaceId > 0 && SpaceSecurity.canUserSeeSpace(contextSpaceId, userId).isSuccess()) {
							request.setAttribute("contextSpaceId", contextSpaceId);
						}
					} catch (NumberFormatException ignored) { }
				}
				if (s.isDownloadable()) {
					request.setAttribute("isDownloadable", "checked");
					request.setAttribute("isNotDownloadable", "");
				} else {
					request.setAttribute("isDownloadable", "");
					request.setAttribute("isNotDownloadable", "checked");
				}
			} else {
				response.sendError(
						HttpServletResponse.SC_FORBIDDEN,
						"Only the owner of this solver can edit details about it."
				);
				return;
			}
		} else {
			if (Solvers.isSolverDeleted(solverId)) {
				response.sendError(
						HttpServletResponse.SC_NOT_FOUND,
						"This solver has been deleted. You likely want to remove it from your spaces."
				);
				return;
			} else {
				response.sendError(
						HttpServletResponse.SC_NOT_FOUND,
						"Solver does not exist or is restricted"
				);
				return;
			}
		}
	} catch (NumberFormatException nfe) {
		response.sendError(
				HttpServletResponse.SC_BAD_REQUEST,
				"The given solver id was in an invalid format"
		);
		return;
	} catch (Exception e) {
		response.sendError(
				HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
		return;
	}
%>

<star:template title="Edit ${fn:escapeXml(solver.name)}"
               js="lib/jquery.validate.min, edit/solver"
               css="edit/shared, edit/solver">
	<form id="editSolverForm">
		<c:if test="${not empty contextSpaceId}"><input type="hidden" name="contextSpaceId" value="${contextSpaceId}"/></c:if>
		<fieldset>
			<legend>solver details</legend>
			<table id="solverDetails" class="shaded">
				<thead>
				<tr>
					<th>attribute</th>
					<th>value</th>
				</tr>
				</thead>
				<tbody>
				<tr>
					<td class="label">solver name</td>
					<td>
						<input id="name" type="text" name="name" pattern="${nameRegex}"
						       value="${fn:escapeXml(solver.name)}">

					</td>
				</tr>
				<tr>
					<td class="label">description</td>
					<td>
						<textarea id="description" name="description"
						          length="${solverDescLen}">${fn:escapeXml(solver.description)}</textarea>
					</td>
				</tr>
				<tr>
					<td>downloadable</td>
					<td>
						<input id="downloadable" type="radio"
						       name="downloadable"
						       value="true"  ${isDownloadable}>yes
						<input id="downloadable" type="radio"
						       name="downloadable"
						       value="false" ${isNotDownloadable}>no
					</td>
				</tr>
				</tbody>
			</table>
			<button type="button" id="delete">recycle</button>
			<button type="button" id="update">update</button>
		</fieldset>
	</form>
	<fieldset>
		<legend>associated websites</legend>
		<table id="websites" class="shaded">
			<thead>
			<tr>
				<th>link</th>
				<th>action</th>
			</tr>
			</thead>
			<tbody>
			<c:forEach items="${sites}" var="s">
				<tr>
					<td><a href="${s.url}">${s.name}<img class="extLink"
					                                     src="${starexecRoot}/images/external.png"/></a>
					</td>
					<td><a class="delWebsite" id="${s.id}">delete</a></td>
				</tr>
			</c:forEach>
			</tbody>
		</table>
		<span id="toggleWebsite" class="caption"><span>+</span> add new</span>
		<div id="new_website">
			name: <input type="text" id="website_name"/>
			url: <input type="text" id="website_url"/>
			<button id="addWebsite">add</button>
		</div>
	</fieldset>
	<div id="dialog-confirm-delete" title="confirm delete" class="hiddenDialog">
		<p><span class="ui-icon ui-icon-alert" aria-hidden="true"></span><span
				id="dialog-confirm-delete-txt"></span></p>
	</div>
</star:template>
