<%@tag description="Standard header content for all starexec pages (not the same as head.tag!)"%>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<header id="pageHeader">
	<div id="starexecLogoWrapper">
		<c:choose>
		<c:when test="${!isLocalJobPage}">
			<a href="${starexecRoot}/secure/index.jsp"><img src="${starexecRoot}/images/starlogo.png" alt="StarExec Logo"></a>
		</c:when>
		<c:otherwise>
			<img src="${starexecRoot}/images/starlogo.png" alt="StarExec Logo">
		</c:otherwise>
		</c:choose> 
		
	</div>
	<c:if test="${!isLocalJobPage}">
		<c:if test="${empty user || (user.role != 'unauthorized' && user.role != 'suspended')}">
		<div id="starexecNavWrapper">
			<nav role="navigation" aria-label="Main navigation">
				<ul role="menubar">
					<c:if test="${user.role == 'admin' || user.role == 'developer'}">
						<li role="none">
							<a href="#" role="menuitem" aria-haspopup="true" aria-expanded="false">Admin</a>
							<ul class="subnav" role="menu" aria-label="Admin submenu">
								<li role="none"><a role="menuitem" href="${starexecRoot}/secure/admin/user.jsp">Users</a></li>
								<li role="none"><a role="menuitem" href="${starexecRoot}/secure/admin/cluster.jsp">Cluster</a></li>
								<li role="none"><a role="menuitem" href="${starexecRoot}/secure/admin/job.jsp">Jobs</a></li>
								<li role="none"><a role="menuitem" href="${starexecRoot}/secure/admin/jobpairErrors.jsp">JobPair Errors</a></li>
								<li role="none"><a role="menuitem" href="${starexecRoot}/secure/admin/community.jsp">Communities</a></li>
								<li role="none"><a role="menuitem" href="${starexecRoot}/secure/admin/testing.jsp">Testing</a></li>
								<li role="none"><a role="menuitem" href="${starexecRoot}/secure/admin/analytics.jsp">Analytics</a></li>
								<li role="none"><a role="menuitem" href="${starexecRoot}/secure/admin/starexec.jsp">StarExec</a></li>
							</ul>
						</li>
					</c:if>
					<c:if test="${not empty user}">
						<li role="none">
							<a href="#" role="menuitem" aria-haspopup="true" aria-expanded="false">Account</a>
							<ul class="subnav" role="menu" aria-label="Account submenu">
								<li role="none"><a role="menuitem" href="${starexecRoot}/secure/details/user.jsp?id=${user.id}">Profile</a></li>
								<li role="none"><button type="button" role="menuitem" id="logoutLink" class="menu-button">Logout</button></li>
							</ul>
						</li>
					</c:if>
					<li role="none">
						<a href="#" role="menuitem" aria-haspopup="true" aria-expanded="false">Spaces</a>
						<ul class="subnav" role="menu" aria-label="Spaces submenu">
							<li role="none"><a role="menuitem" href="${starexecRoot}/secure/explore/spaces.jsp">Explore</a></li>
							<li role="none"><a role="menuitem" href="${starexecRoot}/secure/explore/communities.jsp">Communities</a></li>
							<li role="none"><a role="menuitem" href="${starexecRoot}/secure/explore/statistics.jsp">Statistics</a></li>
							<li role="none"><a role="menuitem" href="${starexecRoot}/secure/explore/reports.jsp">Reports</a></li>
						</ul>
					</li>
					<li role="none">
						<a href="#" role="menuitem" aria-haspopup="true" aria-expanded="false">Cluster</a>
						<ul class="subnav" role="menu" aria-label="Cluster submenu">
							<li role="none"><a role="menuitem" href="${starexecRoot}/secure/explore/cluster.jsp">Status</a></li>
						</ul>
					</li>
					<li role="none" id="helpTab"><a id="helpTag" role="menuitem" href="${starexecRoot}/secure/help.jsp">Help</a></li>
				</ul>
			</nav>
		</div>
	</c:if>
	</c:if>
</header>
