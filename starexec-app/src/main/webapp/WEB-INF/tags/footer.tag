<%@tag description="Standard footer for all starexec pages"%>
<%@tag import="org.starexec.data.database.StatusMessage"%>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<%@taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions"%>

<footer id="pageFooter" role="contentinfo">
	<c:if test="${!isLocalJobPage}">
		<nav aria-label="Footer navigation">
			<ul>
			<c:if test="${not empty sessionScope.user}">
			<li><a target="_blank" rel="noopener"
				href="${starexecRoot}/secure/details/user.jsp?id=${sessionScope.user.id}">${fn:escapeXml(sessionScope.user.fullName)}</a></li>
			<li aria-hidden="true">|</li>
			<li><button type="button" id="footerLogoutLink" class="footer-link-button">Logout</button></li>
			</c:if>
			<c:if test="${empty sessionScope.user}">
				<li><a id="loginLink" href="${starexecRoot}/secure/index.jsp">Login</a></li>
			</c:if>
				<li aria-hidden="true">|</li>
				<li><a id="about" href="${starexecRoot}/public/about.jsp">About</a></li>
				<li aria-hidden="true">|</li>
				<li><a id="help" href="${starexecRoot}/public/help.jsp">Support</a></li>
				<li aria-hidden="true">|</li>
				<li><a id="starexeccommand" href="${starexecRoot}/public/starexeccommand.jsp">StarExec Command</a></li>
			</ul>
		</nav>
	</c:if>
</footer>

<%= StatusMessage.getAsHtml() %>
