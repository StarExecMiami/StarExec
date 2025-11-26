<%@page contentType="text/html" pageEncoding="UTF-8"
        import="org.starexec.constants.DB, org.starexec.data.database.Communities"
        session="false" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%
	request.setAttribute("coms", Communities.getAll());
	request.setAttribute("firstNameLen", DB.USER_FIRST_LEN);
	request.setAttribute("lastNameLen", DB.USER_LAST_LEN);
	request.setAttribute("institutionLen", DB.INSTITUTION_LEN);
	request.setAttribute("emailLen", DB.EMAIL_LEN);
	request.setAttribute("passwordLen", DB.PASSWORD_LEN);
	request.setAttribute("msgLen", DB.MSG_LEN);
%>

<star:template title="User registration"
               css="common/pass_strength_meter, accounts/registration"
               js="lib/jquery.validate.min, lib/jquery.validate.password, accounts/registration">
	<main role="main" class="registration-container">
		<section aria-labelledby="registration-heading">
			<h1 id="registration-heading" class="registration">Create a new user account</h1>
			<div id="javascriptDisabled" role="alert">Javascript is required for most features in
				StarExec, please enable it and reload this page
			</div>
			<form method="POST" action="${starexecRoot}/public/registration/manager"
				  id="regForm" class="registration">
				<fieldset>
					<legend class="registration">User information</legend>
					<table class="shaded" role="presentation">
						<thead>
						<tr>
							<th scope="col">attribute</th>
							<th scope="col">value</th>
						</tr>
						</thead>
						<tbody>
						<tr>
							<td class="label"><label for="firstname">First name</label></td>
							<td><input id="firstname" type="text" name="fn"
									   maxlength="${firstNameLen}" required aria-required="true"/></td>
						</tr>
						<tr>
							<td class="label"><label for="lastname">Last name</label></td>
							<td><input id="lastname" type="text" name="ln"
									   maxlength="${lastNameLen}" required aria-required="true"/></td>
						</tr>
						<tr>
							<td class="label"><label for="email">Email</label></td>
							<td><input id="email" type="text" name="em"
									   maxlength="${emailLen}" required aria-required="true"/></td>
						</tr>
						<tr>
							<td class="label"><label for="institution">Institution</label></td>
							<td><input id="institution" type="text" name="inst"
									   maxlength="${institutionLen}" required aria-required="true"/></td>
						</tr>
						<tr>
							<td class="label"><label for="password">Password</label></td>
							<td>
								<input id="password" type="password" name="pwd"
									   length="${passwordLen}" required aria-required="true" aria-describedby="pwd-meter"/>
								<div class="password-meter" id="pwd-meter" aria-live="polite">
									<div class="password-meter-message" aria-live="assertive"></div>
									<div class="password-meter-bg">
										<div class="password-meter-bar"></div>
									</div>
								</div>
							</td>
						</tr>
						<tr>
							<td class="label"><label for="confirm_password">Confirm password</label></td>
							<td><input id="confirm_password" type="password"
									   name="confirm_password" required aria-required="true"/></td>
						</tr>
						</tbody>
					</table>
				</fieldset>
				<fieldset>
					<legend>Community information</legend>
					<table class="shaded" role="presentation">
						<thead>
						<tr>
							<th scope="col">attribute</th>
							<th id="value_header" scope="col">value</th>
						</tr>
						</thead>
						<tbody>
						<tr>
							<td class="label"><label for="community">Community</label></td>
							<td>
								<select id="community" name="cm" class="styled" required aria-required="true">
									<option value="">Select a community</option>
									<c:forEach var="com" items="${coms}">
										<option value="${com.id}">${com.name}</option>
									</c:forEach>
								</select>
							</td>
						</tr>
						<tr>
							<td class="label"><label for="reason">Reason for joining</label></td>
							<td><textarea name="msg" id="reason"
										  length="${msgLen}" aria-label="Reason for joining"></textarea></td>
						</tr>
						<tr>
							<td class="label"><label for="uc">New user?</label></td>
							<td><input type="checkbox" id="uc" name="uc" value="uc"></td>
						</tr>
						<tr>
							<td class="label">
								Confirm that you have read and agree with our
								<a href="/starexec/public/TermsOfService2019.pdf" target="_blank" rel="external" style="text-decoration:underline">Terms of Service</a>
								and
								<a href="https://welcome.miami.edu/privacy-and-legal/index.html" target="_blank" rel="external" style="text-decoration:underline">Legal Notice</a>.
							</td>
							<td>
								<input type="checkbox"
									   name="termsOfService"
									   value="termsOfService"
									   aria-label="I agree to the Terms of Service and Legal Notice"
									   oninput="$('#submit').button({disabled: !this.checked});">
							</td>
						</tr>
						<tr>
							<td colspan="3">
								<button type="submit" id="submit" value="Submit" disabled>
									Register
								</button>
							</td>
						</tr>
						</tbody>
					</table>
				</fieldset>
			</form>
			<c:if test="${not empty param.result and param.result == 'regSuccess'}">
				<div class='success message' role="status">Registration successful - an email was sent
					to you to activate your account
				</div>
			</c:if>
			<c:if test="${not empty param.result and param.result == 'regFail'}">
				<div class='error message' role="alert">Registration unsuccessful - a user already
					exists under this email address
				</div>
			</c:if>
		</section>
	</main>
</star:template>
