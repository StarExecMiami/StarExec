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

		// Space id for "back" link: new system has getSpaceId(), legacy has getSpaceId()
		Integer returnSpaceId = null;
		if (isNewSystem && newJob != null) {
			returnSpaceId = newJob.getSpaceId();
		} else if (oldStatus != null) {
			returnSpaceId = oldStatus.getSpaceId();
		}
		request.setAttribute("returnSpaceId", returnSpaceId);

	} catch (NumberFormatException nfe) {
		response.sendError(HttpServletResponse.SC_BAD_REQUEST, "The given upload status id was in an invalid format");
		return;
	} catch (Exception e) {
		response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
		return;
	}
%>

<star:template title="Upload Status"
               js="common/delaySpinner, details/shared, lib/jquery.dataTables.min"
               css="details/shared, common/table, details/uploadStatus">
	


	<%-- NEW SYSTEM: Modern async UI with JavaScript polling --%>
	<c:if test="${isNewSystem}">
		<script type="text/javascript">
			var uploadJobId = ${newJob.id};
			var pollInterval = null;
			var pollIntervalMs = 5000;
			var minPollIntervalMs = 5000;
			var maxPollIntervalMs = 30000;
			var lastProcessed = -1;
			
			class VelocityTracker {
				constructor(windowSize = 5) {
					this.windowSize = windowSize;
					this.dataPoints = []; // Array of { timestamp, filesProcessed }
				}

				addDataPoint(filesProcessed, timestampMs = Date.now()) {
					this.dataPoints.push({ files: filesProcessed, time: timestampMs });
					
					if (this.dataPoints.length > this.windowSize) {
						this.dataPoints.shift();
					}
				}

				getVelocity() {
					if (this.dataPoints.length < 2) {
						return null; // Not enough data to establish a derivative
					}

					const oldest = this.dataPoints[0];
					const newest = this.dataPoints[this.dataPoints.length - 1];
					
					const deltaTimeSec = (newest.time - oldest.time) / 1000;
					const deltaFiles = newest.files - oldest.files;

					if (deltaTimeSec === 0) return 0.00; 

					return Math.max(0, (deltaFiles / deltaTimeSec));
				}
			}

			var velocityTracker = new VelocityTracker(5);

			// Format elapsed time (milliseconds to mm:ss)
			function formatElapsed(ms) {
				var totalSeconds = Math.floor(ms / 1000);
				var minutes = Math.floor(totalSeconds / 60);
				var seconds = totalSeconds % 60;
				return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
			}

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
						$('#uploadPercentageText').text(progress + '%');
						
						// Update details table
						$('#totalFoundValue').text(found);
						$('#processedValue').text(processed);
						$('#spacesCreatedValue').text(data.totalSpacesCreated || 0);
						
						// Time Estimation & Velocity
						var elapsedMs = data.elapsedTimeMs || 0;
						$('#elapsedTimeValue').text(formatElapsed(Math.max(0, elapsedMs)));
						
						if (status !== 'COMPLETED' && status !== 'COMPLETED_WITH_ERRORS' && status !== 'FAILED') {
							velocityTracker.addDataPoint(processed);
							var velocity = velocityTracker.getVelocity();
							
							if (velocity !== null) {
								if (velocity === 0 && !data.isStuck && found > 0) {
									$('#velocityValue').text('(Processing...)');
								} else {
									$('#velocityValue').text(velocity.toFixed(2) + ' files/s');
								}
							}
						}
						
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
								$('#pulseIndicator').css({'background': 'red', 'border-radius': '0'}); // Square
								$('#pulseIndicator').addClass('pulse-stuck');
								$('#lastActiveValue').css('color', 'red');
								$('#lastActiveTime').text(timeStr + ' (STALLED)');
							} else if (secondsAgo < 60) {
								$('#pulseIndicator').css({'background': '#4CAF50', 'border-radius': '50%'}); // Circle
								$('#pulseIndicator').removeClass('pulse-stuck');
								$('#pulseIndicator').addClass('pulse-active');
								$('#lastActiveValue').css('color', 'inherit');
							} else {
								$('#pulseIndicator').css({'background': '#FFC107', 'border-radius': '50%'}); // Circle
								$('#pulseIndicator').removeClass('pulse-stuck pulse-active');
								$('#lastActiveValue').css('color', 'inherit');
							}
						}

						if (status === 'COMPLETED') {
							clearInterval(pollInterval);
							$('#uploadPhaseText').text('Upload complete!');
							$('#uploadPhaseText').css('color', 'green');
							$('#uploadPercentageText').text('100%');
							$('#uploadProgressBarFill').css('background', '#4CAF50');
							$('#uploadProgressBarFill').css('width', '100%');
							$('#velocityValue').text('Done');
						} else if (status === 'COMPLETED_WITH_ERRORS') {
							clearInterval(pollInterval);
							$('#uploadPhaseText').text('Upload finished with some errors.');
							$('#uploadPhaseText').css('color', '#ff9800');
							$('#uploadPercentageText').text('100%');
							$('#uploadProgressBarFill').css('background', '#ff9800');
							$('#uploadProgressBarFill').css('width', '100%');
							$('#velocityValue').text('Done');
							$('#errorMessageRow').show();
							$('#errorMessageValue').text(data.errorMessage || 'Some files were skipped. Check below for details.');
						} else if (status === 'FAILED') {
							clearInterval(pollInterval);
							$('#uploadPhaseText').text('Upload failed.');
							$('#uploadPhaseText').css('color', 'red');
							$('#velocityValue').text('Failed');
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
								$('#uploadPhaseText').text('Scanning archive...');
							} else {
								$('#uploadPhaseText').text(processed + ' of ' + found + ' benchmarks');
							}
						}
					}
				).fail(function() {
					$('#uploadPhaseText').text('Connection lost, retrying...');
					$('#uploadPhaseText').css('color', 'orange');
					// If request fails, slow down polling
					pollIntervalMs = Math.min(pollIntervalMs + 5000, maxPollIntervalMs);
					startPolling();
				});
			}
		</script>
		
	<%-- New System: Modern Progress Display --%>
	<h3 class="upload-section-title">Upload Progress</h3>
	<div class="upload-table-wrapper">
		<table class="shaded">
			<thead>
			<tr>
				<th>Attribute</th>
				<th>Value</th>
			</tr>
			</thead>
			<tbody>
			<tr>
				<td>progress</td>
				<td>
					<div id="uploadPhaseText">Loading...</div>
					<div id="uploadProgressBar">
						<div id="uploadProgressBarFill"></div>
						<div id="uploadPercentageText">0%</div>
					</div>
				</td>
			</tr>
			<tr>
				<td>last active (pulse)</td>
				<td id="lastActiveValue">
					<span id="pulseIndicator"></span>
					<strong id="lastActiveTime">Checking...</strong>
				</td>
			</tr>
			<tr>
				<td>elapsed time / velocity</td>
				<td>
					<span id="elapsedTimeValue">0s</span>
					<span class="upload-separator">|</span>
					<span id="velocityValue" class="upload-velocity">(Calculating...)</span>
				</td>
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
				<td>created at</td>
				<td><fmt:formatDate pattern="MMM dd yyyy HH:mm" value="${newJob.createdAt}"/></td>
			</tr>
			<tr id="errorMessageRow" style="${(not empty newJob.errorMessage or newJob.status eq 'COMPLETED_WITH_ERRORS') ? '' : 'display: none;'}">
				<td>error log</td>
				<td id="errorMessageValue">${newJob.errorMessage}</td>
			</tr>
			</tbody>
		</table>
	</div>
	</c:if>
	
	<%-- OLD SYSTEM: Legacy static table --%>
	<c:if test="${not isNewSystem}">
		<h3 class="upload-section-title">Upload Progress (Legacy)</h3>
		<div class="upload-table-wrapper">
			<table class="shaded">
				<thead>
				<tr>
					<th>Attribute</th>
					<th>Value</th>
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
		</div>
		<c:if test="${not empty badBenches}">
			<h3 class="upload-section-title upload-section-title--error">Failed Benchmarks</h3>
			<div class="upload-table-wrapper">
				<table class="shaded">
					<thead>
					<tr>
						<th>Name</th>
						<th>Output</th>
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
			</div>
		</c:if>
	</c:if>

	<div class="upload-footer">
		<a id="returnLink" href="${starexecRoot}/secure/explore/spaces.jsp<c:if test="${returnSpaceId != null && returnSpaceId > 0}">?id=${returnSpaceId}</c:if>">back</a>
		<span class="upload-footer-hint">(Upload continues in the background &mdash; safe to navigate away)</span>
	</div>

</star:template>
