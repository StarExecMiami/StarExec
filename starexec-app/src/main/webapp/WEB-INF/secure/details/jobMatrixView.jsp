<%@page contentType="text/html" pageEncoding="UTF-8"%>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<star:template title="${job.name}"
               js="util/sortButtons, util/jobDetailsUtilityFunctions, common/delaySpinner, lib/jquery.jstree, lib/jquery.dataTables.min, details/jobMatrixView, lib/jquery.ba-throttle-debounce.min, lib/jquery.qtip.min, lib/jquery.heatcolor.0.0.1.min, lib/dataTables.fixedColumns.min"
               css="details/jobMatrixView, common/table, common/dataTables.fixedColumns">
	<div id="matrixPanel" data-job-id="${job.id}" data-job-space-id="${jobSpaceId}" data-stage="${stage}">
		<h2 class="jobSpaceName">Matrix for job space
			&ldquo;${matrix.getJobSpaceName()}&rdquo; <span class="sr-only">with</span>
			<small class="jobSpaceMeta">id=${matrix.getJobSpaceId()}</small>
		</h2>

		<%-- Legend: metric format + status color key --%>
		<div class="matrixLegend" role="region" aria-label="Legend">
			<p class="matrixTextLegend">
				<span class="bold">Legend:</span>
				<span class="wallclock">wallclock</span>
				<span class="cpuTimeWallclockDivider"> / </span>
				<span class="memUsageWallclockDivider" hidden> / </span>
				<span class="cpuTime">cpu</span>
				<span class="cpuTimeMemUsageDivider"> / </span>
				<span class="memUsage">memory</span>
			</p>
			<div class="legendColorTable" role="list" aria-label="Status color key">
				<span class="legendColor solved" role="listitem"><span class="legendIcon statusIcon" aria-hidden="true"></span>Solved</span>
				<span class="legendColor incomplete" role="listitem"><span class="legendIcon statusIcon" aria-hidden="true"></span>Incomplete</span>
				<span class="legendColor unknown" role="listitem"><span class="legendIcon statusIcon" aria-hidden="true"></span>Unknown</span>
				<span class="legendColor resource" role="listitem"><span class="legendIcon statusIcon" aria-hidden="true"></span>Out Of Resource</span>
				<span class="legendColor failed" role="listitem"><span class="legendIcon statusIcon" aria-hidden="true"></span>Failed</span>
				<span class="legendColor wrong" role="listitem"><span class="legendIcon statusIcon" aria-hidden="true"></span>Wrong</span>
			</div>
		</div>

		<%-- Controls: metric toggles + stage selector --%>
		<div class="matrixControls">
			<fieldset class="matrixLegendSelection">
				<legend>Visible metrics</legend>
				<label class="metric-toggle metric-toggle--active">
					<input class="wallclockCheckbox" type="checkbox" checked
					       aria-label="Show runtime (wallclock)">
					<span class="metric-toggle__indicator" aria-hidden="true"></span>
					Wallclock
				</label>
				<label class="metric-toggle metric-toggle--active">
					<input class="cpuTimeCheckbox" type="checkbox" checked
					       aria-label="Show CPU usage">
					<span class="metric-toggle__indicator" aria-hidden="true"></span>
					CPU
				</label>
				<label class="metric-toggle metric-toggle--active">
					<input class="memUsageCheckbox" type="checkbox" checked
					       aria-label="Show max virtual memory">
					<span class="metric-toggle__indicator" aria-hidden="true"></span>
					Memory
				</label>
			</fieldset>
			<c:if test="${matrix.hasMultipleStages()}">
				<form class="matrixStageSelection">
					<label for="selectStageInput">Stage:</label>
					<input id="selectStageInput" type="number" name="stage"
					       value="${stage}" min="1" step="1"
					       aria-label="Stage number">
					<button id="selectStageButton" type="button">Show Stage</button>
					<span id="selectStageError" class="error-text hidden"
					      role="alert">Stage must be a positive integer.</span>
				</form>
			</c:if>
		</div>

		<%-- Matrix table --%>
		<table id="jobMatrix" role="grid"
		       aria-label="Solver results matrix for job space ${matrix.getJobSpaceName()}">
			<thead>
			<tr class="matrixHeaderRow">
				<th class="solverHeader benchmarksColumnHeader" scope="col">
					Benchmark
				</th>
				<c:forEach var="solverConfig" varStatus="headerIndex"
				           items="${matrix.getSolverConfigsByColumn()}">
					<th class="solverHeader" scope="col"
					    title="${solverConfig.getLeft().getName()} (${solverConfig.getRight().getName()})">
						<a href="${starexecRoot}/secure/details/solver.jsp?id=${solverConfig.getLeft().getId()}"
						   target="_blank"
						   rel="noopener noreferrer">
							${matrix.getTruncatedColumnHeader(headerIndex.getIndex())}
							<span class="sr-only">(opens in new tab)</span>
						</a>
					</th>
				</c:forEach>
			</tr>
			</thead>
			<tbody>
			<c:forEach var="matrixRow" varStatus="rowIndex"
			           items="${matrix.getInternalMatrixRepresentation()}">
				<tr class="matrixBodyRow">
					<th class="benchmarkHeader row${rowIndex.getIndex()}" scope="row">
						<a href="${starexecRoot}/secure/details/benchmark.jsp?id=${matrix.getBenchmarksByRow().get(rowIndex.getIndex()).getId()}"
						   target="_blank"
						   rel="noopener noreferrer"
						   title="${matrix.getBenchmarksByRow().get(rowIndex.getIndex()).getName()}">
							${matrix.getBenchmarksByRow().get(rowIndex.getIndex()).getName()}
							<span class="sr-only">(opens in new tab)</span>
						</a>
					</th>
					<c:forEach var="matrixElement" varStatus="columnIndex"
					           items="${matrixRow}">
						<c:choose>
							<c:when test="${matrixElement != null}">
								<td id="${matrixElement.getUniqueIdentifier()}"
								    class="jobMatrixCell ${matrixElement.getStatus()} row${rowIndex.getIndex()} column${columnIndex.getIndex()}"
								    data-status="${matrixElement.getStatus()}"
								    title="Wallclock: ${matrixElement.getWallclock()}s | CPU: ${matrixElement.getCpuTime()}s | Memory: ${matrixElement.getMemUsage()}">
									<a href="${starexecRoot}/secure/details/pair.jsp?id=${matrixElement.getJobPairId()}">
										<span class="statusIcon" aria-hidden="true"></span>
										<span class="wallclock">${matrixElement.getWallclock()}</span>
										<span class="cpuTimeWallclockDivider"> / </span>
										<span class="memUsageWallclockDivider"
										      hidden> / </span>
										<span class="cpuTime">${matrixElement.getCpuTime()}</span>
										<span class="cpuTimeMemUsageDivider"> / </span>
										<span class="memUsage">${matrixElement.getMemUsage()}</span>
									</a>
								</td>
							</c:when>
							<c:otherwise>
								<td class="jobMatrixCell"
								    aria-label="No data"></td>
							</c:otherwise>
						</c:choose>
					</c:forEach>
				</tr>
			</c:forEach>
			</tbody>
		</table>
	</div>
</star:template>
