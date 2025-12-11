<%@page contentType="text/html" pageEncoding="UTF-8"
        import="org.starexec.util.JspHelpers" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%
	try {
		JspHelpers.handleAnonymousJobKeyPage(request, response);
	} catch (Exception e) {
		response.sendError(
				HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
		return;
	}
%>
<star:template title="${job.name}"
               js="util/sortButtons, util/jobDetailsUtilityFunctions, common/delaySpinner, lib/jquery.jstree, lib/jquery.dataTables.min, details/shared, details/anonymousJobPageKey, lib/jquery.ba-throttle-debounce.min, lib/jquery.qtip.min, lib/jquery.heatcolor.0.0.1.min"
               css="common/table, common/delaySpinner, explore/common, details/shared, details/anonymousJobPageKey">
	<div class="alert alert--info" role="status">
		<span class="ui-icon ui-icon-info" aria-hidden="true"></span>
		<div class="alert-content">
			<strong>Note:</strong> Only the owner of the job can see this page.
		</div>
	</div>
	<fieldset id="solverNameKeyFieldset">
		<legend>Solver Name Mapping</legend>
		<table id="solverNameKeyTable" class="shaded" aria-label="Solver name to anonymized name mapping">
			<thead>
			<tr>
				<th scope="col">Solver Name</th>
				<th scope="col">Anonymized Name</th>
			</tr>
			</thead>
			<tbody>
			<c:forEach var="solverTriple" items="${solverTripleList}">
				<tr>
					<td>
						<a href="${starexecRoot}/secure/details/solver.jsp?id=${solverTriple.getRight()}">${solverTriple.getLeft()}</a>
						<img class="extLink"
						     src="${starexecRoot}/images/external.png">
					</td>
					<td>${solverTriple.getMiddle()}</td>
				</tr>
			</c:forEach>
			</tbody>
		</table>
	</fieldset>
</star:template>
