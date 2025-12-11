<%@page contentType="text/html" pageEncoding="UTF-8"
        import="org.starexec.util.SessionUtil, org.starexec.util.Validator" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%
	try {
		int userId = SessionUtil.getUserId(request);

		String type = request.getParameter("type").toString();
		String Id = request.getParameter("Id").toString();

		if (Validator.isValidPictureType(type) &&
				Validator.isValidInteger(Id)) {
			request.setAttribute("userId", userId);
			request.setAttribute("Id", Id);
			request.setAttribute("type", type);
		} else {
			response.sendError(
					HttpServletResponse.SC_BAD_REQUEST,
					"The image parameters were invalid"
			);
			return;
		}
	} catch (Exception e) {
		response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
		return;
	}
%>

<star:template title="upload a picture" css="add/picture"
               js="lib/jquery.validate.min, add/picture, lib/jquery.qtip.min, common/delaySpinner">
	<form method="POST" enctype="multipart/form-data"
	      action="${starexecRoot}/secure/upload/pictures" id="upForm"
	      aria-labelledby="upload-legend">
		<input type="hidden" name="type" value="${type}"/>
		<input type="hidden" name="Id" value="${Id}"/>
		<fieldset>
			<legend id="upload-legend">upload picture</legend>
			<table id="tblPicture" class="shaded contentTbl">
				<thead>
				<tr>
					<th scope="col">attribute</th>
					<th scope="col">value</th>
				</tr>
				</thead>
				<tbody>
				<tr>
					<td><label for="uploadPic">picture file</label></td>
					<td>
						<input id="uploadPic" name="f" type="file"
						       accept=".jpg,.jpeg,.png,.gif,.bmp"
						       aria-describedby="file-hint"/>
						<p id="file-hint" class="field-hint">
							Accepted formats: .jpg, .jpeg, .png, .gif, .bmp
						</p>
					</td>
				</tr>
				</tbody>
			</table>
			<div class="form-actions">
				<button id="btnUpload" type="submit" class="btn-primary">
					Upload Picture
				</button>
			</div>
		</fieldset>
	</form>
</star:template>
