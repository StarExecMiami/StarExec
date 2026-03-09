<%@page contentType="text/html" pageEncoding="UTF-8" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%
	request.setAttribute("columnWidth", "100px");
%>
<star:template title="Users Admin"
               js="admin/user, lib/jquery-ui.min, lib/jquery.dataTables.min"
               css="admin/user, common/table, explore/common, admin/admin, jqueryui/jquery-ui">
	<star:panel title="users" withCount="true" expandable="false">
		<c:if test="${sessionScope.regSuccess}">
			<c:remove var="regSuccess" scope="session" />
			<div class="alert alert--success" role="alert">
				<span class="alert-icon" aria-hidden="true">
					<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
						<polyline points="20 6 9 17 4 12"></polyline>
					</svg>
				</span>
				<div class="alert-content">
					<strong>Success:</strong> User was created successfully
				</div>
			</div>
		</c:if>
		<ul id="actionList">
			<li><a id="addUser"
			       href="${starexecRoot}/secure/admin/addUser.jsp">Create New
				User</a></li>
		</ul>
		<table id="users" role="table" aria-label="User administration table">
			<thead>
			<tr>
				<th scope="col" style="width:${columnWidth};">name</th>
				<th scope="col" style="width:${columnWidth};">institution</th>
				<th scope="col" style="width:200px;">email</th>
				<th scope="col" style="width:${columnWidth};">permissions</th>
				<th scope="col" style="width:${columnWidth};">suspend</th>
				<th scope="col" style="width:${columnWidth};">reports</th>
				<th scope="col" style="width:${columnWidth};">developer</th>
			</tr>
			</thead>
		</table>
	</star:panel>
</star:template>
