
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
			<section class="unauthorized-message" aria-labelledby="unauthorized-heading">
				<h1 id="unauthorized-heading">Authorization Pending</h1>
				<p>
					<strong>You have not yet been authorized to use the StarExec services.</strong>
				</p>
				<p>The leaders of the community you selected during registration will be notified of your request to join shortly.</p>
				<p>Once a leader of that community has approved your request, you will receive an email from us. At that point your registration will be complete and you will be free to login and begin using our service. You may login as a guest user to explore our public offerings.</p>
				<p>Thanks for your patience!</p>
				<p class="auto-logout-notice" role="status" aria-live="polite">You will be automatically logged out in <span id="countdown">20</span> seconds.</p>
			</section>
		</c:if>
		<c:if test="${isSuspended}">
			<section class="suspended-message" aria-labelledby="suspended-heading">
				<h1 id="suspended-heading">Account Suspended</h1>
				<p>
					<strong>You have been suspended and have indefinitely lost access to StarExec services.</strong>
				</p>
				<p class="auto-logout-notice" role="status" aria-live="polite">You will be automatically logged out in <span id="countdown-suspended">20</span> seconds.</p>
			</section>
		</c:if>
		<c:if test="${!isUnauthorized && !isSuspended}">
			<section class="resources-section" aria-labelledby="resources-heading">
				<h2 id="resources-heading">Helpful Resources</h2>
				<nav>
					<ul class="resource-links">
						<li><a href="http://starexec.cs.uiowa.edu/starexec/public/quickReference.jsp" rel="external">Quick Reference</a></li>
						<li><a href="http://wiki.uiowa.edu/display/stardev/User+Guide" rel="external">User Guide</a></li>
						<li><a href="http://wiki.uiowa.edu/display/stardev/Home" rel="external">Public Dev Wiki</a></li>
						<li><a href="mailto:${contactEmail}?subject=[Starexec ${buildVersion}] Feedback" rel="external">Give Feedback</a></li>
						<li><a href="mailto:${contactEmail}?subject=[Starexec ${buildVersion}] Bug Report" rel="external">Report Bug</a></li>
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
