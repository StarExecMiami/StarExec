<%@page contentType="text/html" pageEncoding="UTF-8"
        import="org.starexec.data.database.Spaces,org.starexec.data.security.GeneralSecurity, org.starexec.data.to.Permission, org.starexec.util.SessionUtil" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%
	try {
		// Get parent space info for display
		int spaceId = Integer.parseInt(request.getParameter("sid"));
		int userId = SessionUtil.getUserId(request);

		// Verify this user can add spaces to this space
		Permission userPerm = SessionUtil.getPermission(request, spaceId);
		if (GeneralSecurity.hasAdminReadPrivileges(userId) ||
				userPerm.canAddSpace()) {
			request.setAttribute("space", Spaces.get(spaceId));
		} else {
			response.sendError(
					HttpServletResponse.SC_FORBIDDEN,
					"You do not have permission to add spaces here"
			);
			return;
		}
	} catch (NumberFormatException nfe) {
		response.sendError(
				HttpServletResponse.SC_BAD_REQUEST,
				"The parent space id was not in the correct format"
		);
		return;
	} catch (Exception e) {
		response.sendError(
				HttpServletResponse.SC_NOT_FOUND,
				"You do not have permission to upload spaces to this space or the space does not exist"
		);
		return;
	}
%>
<%
	request.setAttribute("csrfToken", org.starexec.util.CsrfUtil.getOrCreateToken(request));
%>

<star:template
		title="upload XML representation of space hierarchy to ${space.name}"
		css="common/delaySpinner, add/batchSpace"
		js="common/delaySpinner, lib/jquery.validate.min, add/batchSpace">
	<form method="POST" enctype="multipart/form-data"
	      action="${starexecRoot}/secure/upload/space?csrfToken=${csrfToken}" id="upForm"
	      aria-labelledby="upload-legend">
		<input type="hidden" name="space" value="${space.id}"/>
		<fieldset>
			<legend id="upload-legend">upload space hierarchy XML</legend>
			<table id="tblXML" class="shaded contentTbl">
				<thead>
				<tr>
					<th scope="col">attribute</th>
					<th scope="col">value</th>
				</tr>
				</thead>
				<tbody>
				<tr>
					<td><label for="fileUpload">file location</label></td>
					<td>
						<input id="fileUpload" name="f" type="file"
						       accept=".xml,.zip,.tar,.tar.gz,.tgz,.txt"
						       aria-describedby="file-hint"/>
						<p id="file-hint" class="field-hint">
							Accepted formats: .xml, .zip, .tar, .tar.gz, .tgz, .txt
						</p>
					</td>
				</tr>
				</tbody>
			</table>
			<div class="form-actions">
				<button id="btnUpload" type="submit" class="btn-primary">
					Upload Configuration
				</button>
			</div>
			<div class="help-links">
				<a id="viewSchema" href="../../public/batchSpaceSchema.xsd" class="help-link">
					<span class="ui-icon ui-icon-document" aria-hidden="true"></span>
					View XML Schema
				</a>
				<a id="viewExample" href="../../public/ExampleSpace.xml" class="help-link">
					<span class="ui-icon ui-icon-document" aria-hidden="true"></span>
					View Example File
				</a>
			</div>
		</fieldset>
	</form>
</star:template>
