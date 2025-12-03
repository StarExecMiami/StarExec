<%@page import="org.starexec.constants.DB, org.starexec.util.CsrfUtil" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%
	request.setAttribute("firstNameLen", DB.USER_FIRST_LEN);
	request.setAttribute("lastNameLen", DB.USER_LAST_LEN);
	request.setAttribute("emailLen", DB.EMAIL_LEN);
	// Generate CSRF token for form protection
	request.setAttribute("csrfToken", CsrfUtil.getOrCreateToken(request));
%>
<star:template title="Password reset" css="accounts/password_reset"
               js="lib/jquery.validate.min, accounts/password_reset">
	<main role="main" class="password-reset-container">
		<section aria-labelledby="reset-heading">
			<h1 id="reset-heading" class="sr-only">Password Reset</h1>
			<p>Enter your credentials to reset your password</p>
			<form method="POST" action="${starexecRoot}/public/reset_password" id="resetForm">
				<input type="hidden" name="csrfToken" value="${csrfToken}"/>
				<fieldset>
					<legend>Credentials</legend>
					<div class="form-grid">
						<div class="form-field">
							<label for="firstname">First name:</label>
							<input id="firstname" type="text" name="fn" maxlength="${firstNameLen}" required aria-required="true"/>
						</div>
						<div class="form-field">
							<label for="lastname">Last name:</label>
							<input id="lastname" type="text" name="ln" maxlength="${lastNameLen}" required aria-required="true"/>
						</div>
						<div class="form-field">
							<label for="email">Email:</label>
							<input id="email" type="email" name="em" maxlength="${emailLen}" required aria-required="true" autocomplete="email" inputmode="email"/>
						</div>
						<div class="form-actions">
							<button type="submit" id="submit" value="Submit" class="btn btn-primary">Reset</button>
						</div>
					</div>
				</fieldset>
			</form>
			<c:if test="${not empty param.result and param.result == 'success'}">
				<div class='success message' role="status">An email has been sent to you to complete
					the password reset process
				</div>
			</c:if>
			<c:if test="${not empty param.result and param.result == 'noUserFound'}">
				<div class='error message' role="alert">Sorry, those credentials do not match our
					records
				</div>
			</c:if>
			<c:if test="${not empty param.result and param.result == 'expired'}">
				<div class='error message' role="alert">Sorry, the link you are trying to access has
					expired and no longer exists
				</div>
			</c:if>
		</section>
	</main>
</star:template>
