<%@page contentType="text/html" pageEncoding="UTF-8" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<star:template title="Login" css="accounts/login">
	<main role="main" class="guest-login-container">
		<section aria-labelledby="guest-login-heading">
			<h1 id="guest-login-heading" class="sr-only">Guest Login</h1>
			<form method="POST" action="j_security_check" id="loginForm">
				<fieldset>
					<legend>Credentials</legend>
					<table role="presentation">
						<tr>
							<td class="label"><label for="j_userName">Email</label></td>
							<td><input id="j_userName" type="text" name="j_username"
									   value="public" aria-required="true" required/></td>
						</tr>
						<tr>
							<td class="label"><label for="j_password">Password</label></td>
							<td><input id="j_password" type="password" value="public"
									   name="j_password" aria-required="true" required/></td>
						</tr>
						<tr>
							<td></td>
							<td>
								<button type="submit" class="btn btn-primary">Login</button>
							</td>
						</tr>
					</table>
				</fieldset>
			</form>
			<c:if test="${not empty param.result and param.result == 'failed'}">
				<div class='error message' role="alert">Invalid username or password</div>
			</c:if>
		</section>
	</main>
</star:template>