
<%@page contentType="text/html" pageEncoding="UTF-8"
        import="org.starexec.constants.R,org.starexec.data.database.Users,org.starexec.data.to.User, org.starexec.util.SessionUtil, org.starexec.util.Util" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<%
	User user = SessionUtil.getUser(request);
	// User should never be null here as security constraints require authentication

	int userId = user.getId();
	String userRole = user.getRole();

	boolean isUnauthorized = R.UNAUTHORIZED_ROLE_NAME.equals(userRole);
	boolean isSuspended = R.SUSPENDED_ROLE_NAME.equals(userRole);

	if (!isUnauthorized && !isSuspended) {
		String redirectURL = Util.docRoot("secure/explore/spaces.jsp");
		response.sendRedirect(redirectURL);
		return;
	}

	request.setAttribute("isUnauthorized", isUnauthorized);
	request.setAttribute("isSuspended", isSuspended);
	request.setAttribute("userRole", userRole);
%>
<star:template title="Starexec Preview">
	<main role="main" class="starexec-preview">
		<c:if test="${isUnauthorized}">
			<div class="alert alert--info alert--spacious" role="alert" aria-labelledby="unauthorized-heading">
				<div class="alert__icon">
					<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
						<circle cx="12" cy="12" r="10"></circle>
						<line x1="12" y1="16" x2="12" y2="12"></line>
						<line x1="12" y1="8" x2="12.01" y2="8"></line>
					</svg>
				</div>
				<div class="alert__content">
					<h1 id="unauthorized-heading" class="alert__title">Authorization Pending</h1>
					<div class="alert__message">
						<p>
							<strong>You have not yet been authorized to use the StarExec services.</strong>
						</p>
						<p>The leaders of the community you selected during registration will be notified of your request to join shortly.</p>
						<p>Once a leader of that community has approved your request, you will receive an email from us. At that point your registration will be complete and you will be free to login and begin using our service. You may login as a guest user to explore our public offerings.</p>
						<p>Thanks for your patience!</p>
						<p class="auto-logout-notice" role="status" aria-live="polite" style="margin-top: 1rem; font-weight: bold;">You will be automatically logged out in <span id="countdown">20</span> seconds.</p>
					</div>
				</div>
			</div>
		</c:if>
		<c:if test="${isSuspended}">
			<div class="alert alert--error alert--spacious" role="alert" aria-labelledby="suspended-heading">
				<div class="alert__icon">
					<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
						<circle cx="12" cy="12" r="10"></circle>
						<line x1="15" y1="9" x2="9" y2="15"></line>
						<line x1="9" y1="9" x2="15" y2="15"></line>
					</svg>
				</div>
				<div class="alert__content">
					<h1 id="suspended-heading" class="alert__title">Account Suspended</h1>
					<div class="alert__message">
						<p>
							<strong>You have been suspended and have indefinitely lost access to StarExec services.</strong>
						</p>
						<p class="auto-logout-notice" role="status" aria-live="polite" style="margin-top: 1rem; font-weight: bold;">You will be automatically logged out in <span id="countdown-suspended">20</span> seconds.</p>
					</div>
				</div>
			</div>
		</c:if>
		<c:if test="${!isUnauthorized && !isSuspended}">
			<section class="resources-section" aria-labelledby="resources-heading">
				<h2 id="resources-heading">Helpful Resources</h2>
				<nav>
					<ul class="resource-links">
						<li><a href="${starexecRoot}/public/quickReference.jsp" rel="external">Quick Reference</a></li>
						<li><a href="${starexecRoot}/public/StarExecUserGuide.pdf" target="_blank" rel="noopener noreferrer">User Guide</a></li>
						<li><a href="http://wiki.uiowa.edu/display/stardev/Home" target="_blank" rel="noopener noreferrer">Public Dev Wiki</a></li>
						<li><a href="mailto:${contactEmail}?subject=[Starexec ${buildVersion}] Feedback">Give Feedback</a></li>
						<li><a href="mailto:${contactEmail}?subject=[Starexec ${buildVersion}] Bug Report">Report Bug</a></li>
					</ul>
				</nav>
				<footer class="build-info">
					<p>This build was last updated on ${buildDate}</p>
				</footer>
			</section>
		</c:if>
	</main>
	<script>
		(function() {
			'use strict';

			function updateCountdown(elementId, seconds) {
				var element = document.getElementById(elementId);
				if (!element) return;

				var interval = setInterval(function() {
					element.textContent = seconds;
					seconds--;
					if (seconds < 0) {
						clearInterval(interval);
						logout();
					}
				}, 1000);
			}

			function logout() {
				window.location.href = '${pageContext.request.contextPath}/Logout';
			}

			// Initialize countdowns if elements exist
			if (document.getElementById('countdown')) {
				updateCountdown('countdown', 20);
			}
			if (document.getElementById('countdown-suspended')) {
				updateCountdown('countdown-suspended', 20);
			}
		})();
	</script>
</star:template>
