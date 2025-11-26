<%@page trimDirectiveWhitespaces="true" %>
<%@page contentType="text/html" pageEncoding="UTF-8"
	import="org.starexec.constants.R" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<%
	request.setAttribute("debug_mode", R.DEBUG_MODE_ACTIVE);
%>

<star:template title="Login" css="public/login">
	<main role="main" class="login-container">
		<section aria-labelledby="login-title">
			<!-- Accessible heading hidden from sighted users -->
			<h1 id="login-title" class="sr-only">Login to StarExec</h1>

			<!-- Maintenance/Debug Warning -->
			<c:if test="${debug_mode}">
				<div role="alert" class="warning-message" aria-live="polite">
					<p>Notice: StarExec is currently down for maintenance. Logging in will not be possible until StarExec is back online. Please try again later.</p>
				</div>
			</c:if>

			<!-- Login Error Message -->
			<c:if test="${param.error == 'true'}">
				<div class="error message" role="alert" aria-live="assertive">
					Invalid username or password. Please try again.
				</div>
			</c:if>

			<!-- Login Form -->
			<form method="POST" action="${pageContext.request.contextPath}/j_security_check" id="loginForm" class="login-form" novalidate>
				<fieldset id="loginFieldset">
					<legend>Sign In</legend>

					<!-- Username Field -->
					<div class="form-group">
						<label for="j_username" class="form-label">Username</label>
						<input
							type="text"
							id="j_username"
							name="j_username"
							class="form-control"
							placeholder="Enter your username"
							required
							aria-required="true"
							autofocus
							autocomplete="username"
						/>
					</div>

					<!-- Password Field -->
					<div class="form-group">
						<label for="j_password" class="form-label">Password</label>
						<input
							type="password"
							id="j_password"
							name="j_password"
							class="form-control"
							placeholder="Enter your password"
							required
							aria-required="true"
							autocomplete="current-password"
						/>
					</div>

					<!-- Form Actions -->
					<div class="form-actions">
						<button type="submit" id="loginButton" class="btn btn-primary">
							Sign in
						</button>
					</div>

					<!-- Hidden Field for Cookie Check -->
					<input type="hidden" id="cookieexists" name="cookieexists" value="false" />
				</fieldset>

				<!-- Separator -->
				<div class="divider"></div>

				<!-- Additional Links -->
				<div class="login-links">
					<p class="forgot-password">
						<a href="${pageContext.request.contextPath}/public/password_reset.jsp">
							Forgot your password?
						</a>
					</p>
					<p class="new-user">
						<a href="${pageContext.request.contextPath}/public/registration.jsp">
							New user? Create an account
						</a>
					</p>
				</div>
			</form>

			<!-- Unique Tag Placeholder -->
			<span id="uniqueLoginTag"></span>
		</section>
	</main>

	<!-- Initialize jQuery UI Button Plugin -->
	<script>
		$(document).ready(function() {
			// Enhance submit button with jQuery UI styling
			$('#loginButton').button();

			// Handle form validation and submission
			$('#loginForm').on('submit', function(e) {
				// Browser native HTML5 validation will trigger before this
				if (!this.checkValidity()) {
					e.preventDefault();
					e.stopPropagation();
					this.classList.add('was-validated');
					return false;
				}
				
				// Set cookie check value
				$('#cookieexists').val(document.cookie.length > 0 ? 'true' : 'false');
			});

			// Focus management for accessibility
			$('#loginForm').find('input:first').focus();
		});
	</script>
</star:template>