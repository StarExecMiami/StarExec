<%@page contentType="text/html" pageEncoding="UTF-8"
        import="org.starexec.constants.DB, org.starexec.data.database.Communities" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%
	String password = "Password0410!!";

	request.setAttribute("coms", Communities.getAll());
	request.setAttribute("firstNameLen", DB.USER_FIRST_LEN);
	request.setAttribute("lastNameLen", DB.USER_LAST_LEN);
	request.setAttribute("institutionLen", DB.INSTITUTION_LEN);
	request.setAttribute("emailLen", DB.EMAIL_LEN);
	request.setAttribute("passwordLen", DB.PASSWORD_LEN);
	request.setAttribute("password", password);
	request.setAttribute("msgLen", DB.MSG_LEN);
%>

<star:template title="user registration"
               css="common/table, explore/common, admin/admin, jqueryui/jquery-ui"
               js="lib/jquery.validate.min, lib/jquery-ui.min, lib/jquery.dataTables.min, lib/jquery.jstree, lib/jquery.qtip.min, lib/jquery.heatcolor.0.0.1.min, lib/jquery.ba-throttle-debounce.min, add/user">
	<p class="registration">create a new user account</p>
	<form method="POST" action="${starexecRoot}/public/registration/manager"
	      id="regForm" class="add" autocomplete="off">
		<fieldset>
			<legend>user information</legend>
			<table class="shaded">
				<thead>
				<tr>
					<th scope="col">attribute</th>
					<th scope="col">value</th>
				</tr>
				</thead>
				<tbody>
				<tr>
					<td><label for="firstname">first name</label></td>
					<td><input id="firstname" type="text" name="fn"
					           maxlength="${firstNameLen}" autocomplete="given-name" required /></td>
				</tr>
				<tr>
					<td><label for="lastname">last name</label></td>
					<td><input id="lastname" type="text" name="ln"
					           maxlength="${lastNameLen}" autocomplete="family-name" required /></td>
				</tr>
				<tr>
					<td><label for="email">email</label></td>
					<td><input id="email" type="email" name="em"
					           maxlength="${emailLen}" autocomplete="email" required /></td>
				</tr>
				<tr>
					<td><label for="institution">institution</label></td>
					<td><input id="institution" type="text" name="inst"
					           maxlength="${institutionLen}" autocomplete="organization" required /></td>
				</tr>
				<tr>
					<td><label for="password">password</label></td>
					<td>
						<input id="password" type="password" name="pwd"
						       maxlength="${passwordLen}" autocomplete="new-password" required />
					</td>
				</tr>
				</tbody>
			</table>
		</fieldset>
		<fieldset>
			<legend>community information</legend>
			<table class="shaded">
				<thead>
				<tr>
					<th scope="col">attribute</th>
					<th scope="col" id="value_header">value</th>
				</tr>
				</thead>
				<tbody>
				<tr>
					<td><label for="community">community</label></td>
					<td>
						<select id="community" name="cm" class="styled" required>
							<option value="">-- select community --</option>
							<c:forEach var="com" items="${coms}">
								<option value="${com.id}">${com.name}</option>
							</c:forEach>
						</select>
					</td>
				</tr>
				<tr>
					<td colspan="2">
						<button type="submit" id="submit">
							register
						</button>
					</td>
				</tr>
				</tbody>
			</table>
		</fieldset>
	</form>
	<c:if test="${not empty param.result and param.result == 'regSuccess'}">
		<div class="success message" role="alert">User was created successfully</div>
	</c:if>
	<c:if test="${not empty param.result and param.result == 'regFail'}">
		<div class="error message" role="alert">User creation was unsuccessful -- please try
			again
		</div>
	</c:if>
</star:template>
