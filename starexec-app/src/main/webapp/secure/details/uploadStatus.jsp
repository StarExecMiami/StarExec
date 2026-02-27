<%@page import="org.starexec.data.database.Uploads" %>
<%@page import="org.starexec.data.database.UploadJobQueue" %>
<%@page import="org.starexec.data.security.BenchmarkSecurity" %>
<%@page import="org.starexec.data.security.UploadJobSecurity" %>
<%@page import="org.starexec.data.to.Benchmark" %>
<%@page import="org.starexec.data.to.UploadJob" %>
<%@page contentType="text/html" pageEncoding="UTF-8"
        import="org.starexec.data.to.BenchmarkUploadStatus,org.starexec.util.SessionUtil, java.util.List, java.util.Optional" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%
	int userId = SessionUtil.getUserId(request);
	String idParam = request.getParameter("id");
	
	// Default: assume legacy system
	boolean isNewSystem = false;
	UploadJob newJob = null;
	BenchmarkUploadStatus oldStatus = null;
	List<Benchmark> badBenches = null;
	
	try {
		if (idParam == null || idParam.isEmpty()) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "No upload ID provided");
			return;
		}
		
		// Server-side routing: Try new system first, then fall back to old
		long jobId = Long.parseLong(idParam);
		
		// Check if user can access this job
		if (UploadJobSecurity.canUserSeeUploadJob(jobId, userId)) {
			Optional<UploadJob> jobOpt = UploadJobQueue.getJob(jobId);
			if (jobOpt.isPresent()) {
				newJob = jobOpt.get();
				isNewSystem = true;
			}
		}
		
		// Fall back to old system if not found in new
		if (!isNewSystem) {
			int statusId = (int) jobId;
			if (BenchmarkSecurity.canUserSeeBenchmarkStatus(statusId, userId)) {
				oldStatus = Uploads.getBenchmarkStatus(statusId);
				badBenches = Uploads.getFailedBenches(statusId);
			}
		}
		
		// If neither system has the job, return 404
		if (newJob == null && oldStatus == null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, "Upload Status does not exist or is restricted");
			return;
		}
		
		// Set request attributes for the view
		if (isNewSystem) {
			request.setAttribute("newJob", newJob);
		} else {
			request.setAttribute("status", oldStatus);
			request.setAttribute("badBenches", badBenches);
			if (!oldStatus.isEverythingComplete()) {
				response.setIntHeader("Refresh", 10);
			}
		}
		
		request.setAttribute("isNewSystem", isNewSystem);
		
	} catch (NumberFormatException nfe) {
		response.sendError(HttpServletResponse.SC_BAD_REQUEST, "The given upload status id was in an invalid format");
		return;
	} catch (Exception e) {
		response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
		return;
	}
%>

<star:template title="upload status"
               js="common/delaySpinner, details/shared, lib/jquery.dataTables.min"
               css="details/shared, common/table, details/uploadStatus">
	
	<style>
		@keyframes pulse-red {
			0% { opacity: 1; transform: scale(1); }
			50% { opacity: 0.5; transform: scale(1.2); }
			100% { opacity: 1; transform: scale(1); }
		}
		.pulse-stuck {
			animation: pulse-red 1s infinite;
			box-shadow: 0 0 5px red;
		}
	</style>

	<%-- NEW SYSTEM: Modern async UI with JavaScript polling --%>
	<c:if test="${isNewSystem}">
		<script type="text/javascript">
			var uploadJobId = ${newJob.id};
			var pollInterval = null;
			var pollIntervalMs = 5000;
			var minPollIntervalMs = 5000;
			var maxPollIntervalMs = 30000;
			var lastProcessed = -1;
			
			// Helper function to properly join URL paths (avoids double slashes)
			function joinPath(base, path) {
				if (!base) return path;
				if (!path) return base;
				base = base.replace(/\/$/, '');
				path = path.replace(/^\//, '');
				return base + '/' + path;
			}
			
			$(document).ready(function() {
				updateUploadProgress();
				startPolling();
			});

			function startPolling() {
				if (pollInterval) clearInterval(pollInterval);
				pollInterval = setInterval(updateUploadProgress, pollIntervalMs);
			}
			
			function updateUploadProgress() {
				$.getJSON(
					joinPath(starexecRoot, '/services/uploads/jobs/') + uploadJobId,
					function(data) {
						var progress = data.progressPercentage;
						var processed = data.totalFilesProcessed;
						var found = data.totalFilesFound;
						var status = data.status;
						
						// Update progress bar
						$('#uploadProgressBarFill').css('width', progress + '%');
						$('#uploadProgressText').text(progress + '%');
						
						// Update details table
						$('#statusValue').text(status);
						$('#totalFoundValue').text(found);
						$('#processedValue').text(processed);
						$('#spacesCreatedValue').text((data.totalSpacesCreated || 0) + ' (directories scanned)');
						
						// Update Pulse (Heartbeat)
						if (data.lastHeartbeat) {
							var lastActive = new Date(data.lastHeartbeat);
							var secondsAgo = Math.floor((new Date() - lastActive) / 1000);
							var timeStr = secondsAgo < 5 ? 'Just now' : secondsAgo + 's ago';
							if (secondsAgo > 60) {
								var mins = Math.floor(secondsAgo / 60);
								timeStr = mins + 'm ' + (secondsAgo % 60) + 's ago';
							}
							$('#lastActiveTime').text(timeStr);
							
							// Color code the pulse
							if (data.isStuck) {
								$('#pulseIndicator').css('background', 'red');
								$('#pulseIndicator').addClass('pulse-stuck');
								$('#lastActiveValue').css('color', 'red');
								$('#lastActiveTime').text(timeStr + ' (STALLED)');
							} else if (secondsAgo < 60) {
								$('#pulseIndicator').css('background', '#4CAF50'); // Green
								$('#pulseIndicator').removeClass('pulse-stuck');
								$('#lastActiveValue').css('color', 'inherit');
							} else {
								$('#pulseIndicator').css('background', '#FFC107'); // Amber
								$('#pulseIndicator').removeClass('pulse-stuck');
								$('#lastActiveValue').css('color', 'inherit');
							}
						}

						if (status === 'COMPLETED') {
							clearInterval(pollInterval);
							$('#statusValue').css('color', 'green');
							$('#uploadProgressText').text('Upload complete!');
							$('#uploadProgressBarFill').css('background', '#4CAF50');
						} else if (status === 'COMPLETED_WITH_ERRORS') {
							clearInterval(pollInterval);
							$('#statusValue').css('color', '#ff9800');
							$('#statusValue').text('COMPLETED (WITH ERRORS)');
							$('#uploadProgressText').text('Upload finished with some errors.');
							$('#uploadProgressBarFill').css('background', '#ff9800');
							$('#errorMessageRow').show();
							$('#errorMessageValue').text(data.errorMessage || 'Some files were skipped. Check below for details.');
						} else if (status === 'FAILED') {
							clearInterval(pollInterval);
							$('#statusValue').css('color', 'red');
							$('#errorMessageRow').show();
							$('#errorMessageValue').text(data.errorMessage || 'Unknown error');
							$('#uploadProgressBarFill').css('background', '#f44336');
						} else {
							// Adaptive polling logic
							if (processed === lastProcessed) {
								// No progress: increase interval (exponential backoff)
								if (pollIntervalMs < maxPollIntervalMs) {
									pollIntervalMs = Math.min(pollIntervalMs * 1.5, maxPollIntervalMs);
									startPolling();
								}
							} else {
								// Progress made: reset to baseline for responsiveness
								if (pollIntervalMs !== minPollIntervalMs) {
									pollIntervalMs = minPollIntervalMs;
									startPolling();
								}
							}
							lastProcessed = processed;
							
							if (found === 0) {
								$('#uploadProgressText').text('Scanning archive...');
							} else {
								$('#uploadProgressText').text(processed + ' of ' + found + ' benchmarks');
							}
						}
					}
				).fail(function() {
					$('#statusValue').text('OFFLINE (retrying...)');
					$('#statusValue').css('color', 'orange');
					// If request fails, slow down polling
					pollIntervalMs = Math.min(pollIntervalMs + 5000, maxPollIntervalMs);
					startPolling();
				});
			}
		</script>
		
		<%-- New System: Modern Progress Display --%>
		<fieldset>
			<legend>upload progress</legend>
			<div style="margin: 20px 0;">
				<div id="uploadProgressBar" style="width: 100%; height: 30px; border: 1px solid #ccc; background: #fff; border-radius: 4px; overflow: hidden;">
					<div id="uploadProgressBarFill" style="height: 100%; width: 0%; background: #2196F3; transition: width 0.3s;"></div>
				</div>
				<div id="uploadProgressText" style="text-align: center; margin-top: 10px; font-weight: bold;">Loading...</div>
			</div>
		</fieldset>
		
		<fieldset>
			<legend>details</legend>
			<table class="shaded">
				<thead>
				<tr>
					<th>attribute</th>
					<th>value</th>
				</tr>
				</thead>
				<tbody>
				<tr>
					<td>status</td>
					<td id="statusValue">${newJob.status}</td>
				</tr>
				<tr>
					<td>total files found</td>
					<td id="totalFoundValue">${newJob.totalFilesFound}</td>
				</tr>
				<tr>
					<td>files processed</td>
					<td id="processedValue">${newJob.totalFilesProcessed}</td>
				</tr>
				<tr>
					<td>directories scanned</td>
					<td id="spacesCreatedValue">${newJob.totalSpacesCreated}</td>
				</tr>
				<tr>
					<td>last active (pulse)</td>
					<td id="lastActiveValue">
						<span id="pulseIndicator" style="display:inline-block; width:10px; height:10px; border-radius:50%; background:#ccc; margin-right:5px;"></span>
						<span id="lastActiveTime">Checking...</span>
					</td>
				</tr>
				<tr>
					<td>created at</td>
					<td><fmt:formatDate pattern="MMM dd yyyy HH:mm" value="${newJob.createdAt}"/></td>
				</tr>
				<tr id="errorMessageRow" style="${(not empty newJob.errorMessage or newJob.status eq 'COMPLETED_WITH_ERRORS') ? '' : 'display: none;'}">
					<td style="color: #f44336;">error log</td>
					<td id="errorMessageValue" style="color: #f44336; white-space: pre-wrap;">${newJob.errorMessage}</td>
				</tr>
				</tbody>
			</table>
		</fieldset>
	</c:if>
	
	<%-- OLD SYSTEM: Legacy static table --%>
	<c:if test="${not isNewSystem}">
		<fieldset>
			<legend>details</legend>
			<table class="shaded">
				<thead>
				<tr>
					<th>attribute</th>
					<th>value</th>
				</tr>
				</thead>
				<tbody>
				<tr>
					<td>upload date</td>
					<td><fmt:formatDate pattern="MMM dd yyyy"
					                    value="${status.uploadDate}"/></td>
				</tr>
				<tr>
					<td>file upload complete</td>
					<td>${status.fileUploadComplete}</td>
				</tr>
				<tr>
					<td>file extraction complete</td>
					<td>${status.fileExtractionComplete}</td>
				</tr>
				<tr>
					<td>begun validating</td>
					<td>${status.processingBegun}</td>
				</tr>
				<tr>
					<td>total benchmarks</td>
					<td>${status.totalBenchmarks}</td>
				</tr>
				<tr>
					<td>validated benchmarks</td>
					<td>${status.validatedBenchmarks}</td>
				</tr>
				<tr>
					<td>benchmarks failing validation</td>
					<td>${status.failedBenchmarks}</td>
				</tr>
				<tr>
					<td>completed benchmarks</td>
					<td>${status.completedBenchmarks}</td>
				</tr>
				<tr>
					<td>total spaces</td>
					<td>${status.totalSpaces}</td>
				</tr>
				<tr>
					<td>completed spaces</td>
					<td>${status.completedSpaces}</td>
				</tr>
				<tr>
					<td>entire upload complete</td>
					<td>${status.everythingComplete}</td>
				</tr>
				<tr>
					<td>upload error message</td>
					<td>${status.errorMessage}</td>
				</tr>
				</tbody>
			</table>
		</fieldset>
		<c:if test="${not empty badBenches}">
			<fieldset>
				<legend>failed benchmarks</legend>
				<table class="shaded">
					<thead>
					<tr>
						<th>name</th>
						<th>output</th>
					</tr>
					</thead>
					<tbody>
					<c:forEach var="bench" items="${badBenches}">
						<tr>
							<td>${bench.name}</td>
							<td>
								<a href="${starexecRoot}/services/uploads/stdout/${bench.id}">view</a>
							</td>
						</tr>
					</c:forEach>
					</tbody>
				</table>
			</fieldset>
		</c:if>
	</c:if>

	<a id="returnLink" href="${starexecRoot}/secure/explore/spaces.jsp">back</a>

</star:template>
