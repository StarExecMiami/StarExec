<%@page contentType="text/html" pageEncoding="UTF-8"
        import="org.starexec.data.database.*, org.starexec.data.security.GeneralSecurity,org.starexec.data.to.Benchmark, org.starexec.data.to.DefaultSettings, org.starexec.data.to.Processor,org.starexec.data.to.User, org.starexec.data.to.Website.WebsiteType, org.starexec.data.to.enums.ProcessorType, org.starexec.util.SessionUtil, org.starexec.logger.StarLogger"
        session="true" %>
<%@ page import="org.starexec.util.Util" %>
<%@ page import="java.util.HashMap, java.util.List, java.util.Map" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%
	final StarLogger log = StarLogger.getLogger(getClass());
	try {
		int userId = -1;
		try {
			userId = Integer.parseInt(request.getParameter("id"));
		} catch (Exception e) {
			// if we can't get it from the URL, try to just use the current user ID
			userId = SessionUtil.getUserId(request);
		}
		User t_user = Users.get(userId);
		int visiting_userId = SessionUtil.getUserId(request);

		if (t_user != null) {
			long disk_usage = Users.getDiskUsage(t_user.getId());

			boolean owner = true;
			boolean hasAdminReadPrivileges =
					GeneralSecurity.hasAdminReadPrivileges(visiting_userId);
			boolean hasAdminWritePrivileges =
					GeneralSecurity.hasAdminWritePrivileges(visiting_userId);
			// The user can be deleted if the visting user has admin write privileges and the user being deleted is NOT an admin.
			boolean canDeleteUser =
					hasAdminWritePrivileges && !Users.isAdmin(userId);
			if ((visiting_userId != userId) && !hasAdminReadPrivileges) {
				owner = false;
				response.sendError(
						HttpServletResponse.SC_NOT_FOUND,
						"Must be the administrator to access this page"
				);
				return;
			} else {
				List<DefaultSettings> listOfDefaultSettings =
						Settings.getDefaultSettingsVisibleByUser(userId);

				Map<Integer, List<Benchmark>> idToBenchmarks =
						new HashMap<Integer, List<Benchmark>>();
				for (DefaultSettings setting : listOfDefaultSettings) {
					idToBenchmarks.put(setting.getId(),
					                   Settings.getDefaultBenchmarks(
							                   setting.getId())
					);
				}

				request.setAttribute(
						"settingIdToDefaultBenchmarks", idToBenchmarks);

				request.setAttribute("userId", userId);
				request.setAttribute(
						"diskQuota", Util.byteCountToDisplaySize(
								t_user.getDiskQuota()));
				request.setAttribute(
						"diskUsage", Util.byteCountToDisplaySize(disk_usage));
			request.setAttribute("sites", Websites.getAllForHTML(userId,
			                                                     WebsiteType.USER
			));
			request.setAttribute("settings", listOfDefaultSettings);

			// Get current default profile for UI state management
			Integer defaultProfileId = Settings.getDefaultProfileForUser(userId);
			request.setAttribute("defaultProfileId", defaultProfileId);

			List<Processor> ListOfPostProcessors =
						Processors.getByUser(userId, ProcessorType.POST);
				List<Processor> ListOfPreProcessors =
						Processors.getByUser(userId, ProcessorType.PRE);
				List<Processor> ListOfBenchProcessors =
						Processors.getByUser(userId, ProcessorType.BENCH);
				request.setAttribute("postProcs", ListOfPostProcessors);
				request.setAttribute("preProcs", ListOfPreProcessors);
				request.setAttribute("benchProcs", ListOfBenchProcessors);
				request.setAttribute("pairQuota", t_user.getPairQuota());
				request.setAttribute("pairUsage",
				                     Jobs.countPairsByUser(t_user.getId())
				);
			}

			request.setAttribute("owner", owner);
			request.setAttribute("canDeleteUser", canDeleteUser);
			request.setAttribute(
					"hasAdminReadPrivileges", hasAdminReadPrivileges);
			request.setAttribute(
					"hasAdminWritePrivileges", hasAdminWritePrivileges);
			request.setAttribute("user", t_user);
		}
	} catch (Exception e) {
		String errorId = java.util.UUID.randomUUID().toString().substring(0, 8);
		log.error("Error loading account data [errorId=" + errorId + "]", e);
		response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
			"Unable to load account data. Reference ID: " + errorId);
		return;
	}
%>
<star:template title="Edit Account"
               css="common/table, edit/account"
               js="common/defaultSettings,lib/jquery.validate.min, lib/jquery.validate.password, edit/account, lib/jquery.dataTables.min">
	<main role="main" class="account-edit">
		<c:forEach items="${settings}" var="setting">
			<star:settings setting="${setting}"/>
		</c:forEach>

		<div id="popDialog" role="dialog" aria-labelledby="popDialog-title" aria-modal="true" aria-hidden="true">
			<h2 id="popDialog-title" class="sr-only">Profile picture</h2>
			<img id="popImage" src="" alt="Profile picture"/>
		</div>

		<p class="page-description">Review and edit your account details here.</p>

		<section class="personal-information">
			<h2>Personal Information</h2>
			<table id="infoTable" data-user-id="${userId}" role="presentation">
				<tr>
					<star:picSection thumbSrc="${starexecRoot}/secure/get/pictures?Id=${userId}&type=uthn"
					                 enlargeSrc="${starexecRoot}/secure/get/pictures?Id=${userId}&type=uorg"
					                 altText="User profile picture"
					                 showChangeLink="true"
					                 changeLinkUrl="${starexecRoot}/secure/add/picture.jsp?type=user&Id=${userId}"
					                 changeLinkLabel="Change Picture"
					                 useDataEnlarge="true" />
					<td id="userDetail">
						<table id="personal" class="shaded" role="table" aria-label="Personal information">
							<thead>
							<tr>
							<th class="label" scope="col">Attribute</th>
							<th scope="col">Current value</th>
							</tr>
							</thead>
							<tbody>
							<tr>
								<td>First Name</td>
								<td id="editfirstname" data-editable="true">${fn:escapeXml(user.firstName)}</td>
							</tr>
							<tr>
								<td>Last Name</td>
								<td id="editlastname" data-editable="true">${fn:escapeXml(user.lastName)}</td>
							</tr>
							<tr>
								<td>Institution</td>
								<td id="editinstitution" data-editable="true">${fn:escapeXml(user.institution)}</td>
							</tr>
							<tr>
								<td>Email</td>
								<td id="editemail" data-editable="true">${fn:escapeXml(user.email)}</td>
							</tr>
							</tbody>
						</table>
					</td>
				</tr>
			</table>
			<p class="help-text">(Click on a value to edit it, then click Save or Cancel to confirm)</p>
		</section>

		<c:if test="${hasAdminReadPrivileges}">
			<section class="user-quotas">
				<h2>User Quotas</h2>
				<table id="diskUsageTable" class="shaded" data-user-id="${userId}" role="table" aria-label="User quotas">
					<thead>
					<tr>
						<th scope="col">Attribute</th>
						<th scope="col">Value</th>
					</tr>
					</thead>
					<tbody>
					<tr>
						<td>Disk Quota <span class="help-text">Format: [Integer] [Units(B, KB, MB, GB)]</span></td>
						<td id="editdiskquota" data-editable="true">${diskQuota}</td>
					</tr>
					<tr>
						<td>Current Disk Usage</td>
						<td>${diskUsage}</td>
					</tr>
					<tr>
						<td>Job Pair Quota</td>
						<td id="editpairquota" data-editable="true">${pairQuota}</td>
					</tr>
					<tr>
						<td>Job Pairs Owned</td>
						<td>${pairUsage}</td>
					</tr>
					</tbody>
				</table>
			</section>
		</c:if>

		<section class="site-settings">
			<h2>Site Settings</h2>
			<table id="siteSettingTable" role="table" aria-label="Site settings">
				<thead>
				<tr>
				<th scope="col">Setting</th>
				<th scope="col">Current value</th>
				</tr>
				</thead>
				<tbody>
				<tr>
					<td>Table Entries Per Page</td>
					<td data-type="int" id="editpagesize" data-editable="true">${pagesize}</td>
				</tr>
				</tbody>
			</table>
		</section>
		<section class="associated-websites">
			<h2>Associated Websites</h2>
			<table id="websites" class="shaded" role="table" aria-label="Associated websites">
				<thead>
				<tr>
					<th scope="col">Link</th>
					<th scope="col">Action</th>
				</tr>
				</thead>
				<tbody>
				<c:forEach items="${sites}" var="s">
					<tr>
						<td><a href="${fn:escapeXml(s.url)}" rel="external">${fn:escapeXml(s.name)}<img class="extLink"
						                                     src="${starexecRoot}/images/external.png" alt="External link"/></a>
						</td>
						<td><button class="btn btn-secondary delWebsite" data-id="${s.id}" type="button">Delete</button></td>
					</tr>
				</c:forEach>
				</tbody>
			</table>

			<button id="toggleWebsite" class="btn btn-secondary caption" type="button" aria-expanded="false" aria-controls="new_website">
				<span aria-hidden="true">+</span> Add New Website
			</button>
			<div id="new_website" class="hidden" aria-hidden="true">
				<label for="website_name">Name:</label>
				<input type="text" id="website_name" aria-describedby="website-name-desc"/>
				<span id="website-name-desc" class="sr-only">Enter the name of the website</span>

			<label for="website_url">URL:</label>
			<input type="url" id="website_url" placeholder="https://example.com" aria-describedby="website-url-desc"/>
			<span id="website-url-desc" class="sr-only">Enter the URL of the website, e.g. https://example.com</span>

				<button id="addWebsite" type="button" class="btn btn-secondary">Add Website</button>
			</div>
		</section>

		<section class="password-change">
			<h2>Change Password</h2>
			<form id="changePassForm" method="post" novalidate>
				<!-- Hidden username field for accessibility (browsers use this for password managers) -->
				<input type="text" name="username" value="${fn:escapeXml(user.email)}" style="display: none;" aria-hidden="true" autocomplete="username"/>
				<table id="passwordTable" class="shaded" role="table" aria-label="Password change form">
					<thead>
					<tr>
						<th scope="col">Attribute</th>
						<th scope="col">Value</th>
					</tr>
					</thead>
					<tbody>
						<tr>
							<td><label for="current_pass">Current Password</label></td>
							<td><input type="password" id="current_pass" name="current_pass" required aria-describedby="current-pass-desc" autocomplete="current-password"/>
								<span id="current-pass-desc" class="sr-only">Enter your current password</span></td>
						</tr>
						<tr>
							<td><label for="password">New Password</label></td>
							<td>
								<input type="password" id="password" name="pwd" required aria-describedby="password-desc" autocomplete="new-password"/>
								<span id="password-desc" class="sr-only">Enter your new password</span>
							<div class="password-meter" id="pwd-meter">
							<div class="password-meter-message" aria-live="assertive" aria-atomic="true"></div>
								<div class="password-meter-bg">
									<div class="password-meter-bar"></div>
								</div>
							</div>
						</td>
					</tr>
						<tr>
							<td><label for="confirm_pass">Re-enter New Password</label></td>
							<td><input type="password" id="confirm_pass" name="confirm_pass" required aria-describedby="confirm-pass-desc" autocomplete="new-password"/>
								<span id="confirm-pass-desc" class="sr-only">Re-enter your new password for confirmation</span></td>
						</tr>
					<tr>
						<td class="notShaded" colspan="2">
							<button id="changePass" class="btn btn-secondary" type="submit">Change Password</button>
						</td>
					</tr>
					</tbody>
				</table>
			</form>
		</section>

		<section class="default-settings">
			<h2>Default Settings</h2>
			<label for="settingProfile">Select Profile:</label>
			<select id="settingProfile" aria-describedby="profile-select-desc">
				<c:if test="${empty settings}">
					<option value="">No profiles available</option>
				</c:if>
				<c:forEach var="setting" items="${settings}">
					<c:set var="isDefault" value="${defaultProfileId != null && defaultProfileId == setting.getId()}" />
					<option class="settingOption" value="${setting.getId()}"
					        data-type="${setting.getTypeString()}"
					        ${isDefault ? 'selected="selected"' : ''}>
						${setting.name}<c:if test="${isDefault}"> (default)</c:if>
					</option>
				</c:forEach>
			</select>
			<span id="profile-select-desc" class="sr-only">Choose a settings profile to edit</span>

			<table id="settings" class="shaded" role="table" aria-label="Default settings">
				<thead>
				<tr class="headerRow">
					<th class="label" scope="col">Name</th>
					<th scope="col">Values</th>
				</tr>
				</thead>
				<tbody>
				<tr>
					<td title="The pre processor that will be selected by default for new jobs">
						Pre Processor
					</td>
					<td>
						<label for="editPreProcess" class="sr-only">Select pre processor</label>
						<select class="preProcessSetting" id="editPreProcess"
						        name="editPreProcess" default="${defaultPreProcId}">
							<option value="-1">None</option>
							<c:forEach var="proc" items="${preProcs}">
								<option value="${proc.id}">${proc.name}</option>
							</c:forEach>
						</select>
					</td>
				</tr>

				<tr>
					<td title="The benchmark processor that will be selected by default for new jobs">
						Bench Processor
					</td>
					<td>
						<label for="editBenchProcess" class="sr-only">Select benchmark processor</label>
						<select class="benchProcessSetting" id="editBenchProcess"
						        name="editBenchProcess" default="${defaultBPId}">
							<option value="-1">None</option>
							<c:forEach var="proc" items="${benchProcs}">
								<option value="${proc.id}">${proc.name}</option>
							</c:forEach>
						</select>
					</td>
				</tr>

				<tr>
					<td title="The post processor that will be selected by default for new jobs">
						Post Processor
					</td>
					<td>
						<label for="editPostProcess" class="sr-only">Select post processor</label>
						<select class="postProcessSetting" id="editPostProcess"
						        name="editPostProcess" default="${defaultPPId}">
							<option value="-1">None</option>
							<c:forEach var="proc" items="${postProcs}">
								<option value="${proc.id}">${proc.name}</option>
							</c:forEach>
						</select>
					</td>
				</tr>
				<star:benchmarkingFrameworkRow/>
				<tr>
				<td title="The wallclock timeout that will be selected by default for new jobs">
					Wallclock Timeout
				</td>
				<td id="editClockTimeout">
					<label for="wallclockTimeout" class="sr-only">Wallclock timeout in seconds</label>
					<input type="number" name="wallclockTimeout" id="wallclockTimeout" min="1" aria-describedby="wallclock-desc"/>
					<span class="input-unit" aria-hidden="true">s</span>
					<span id="wallclock-desc" class="sr-only">Enter timeout in seconds</span>
				</td>
			</tr>
			<tr>
				<td title="The cpu timeout that will be selected by default for new jobs">
					CPU Timeout
				</td>
				<td id="editCpuTimeout">
					<label for="cpuTimeout" class="sr-only">CPU timeout in seconds</label>
					<input type="number" name="cpuTimeout" id="cpuTimeout" min="1" aria-describedby="cpu-desc"/>
					<span class="input-unit" aria-hidden="true">s</span>
					<span id="cpu-desc" class="sr-only">Enter timeout in seconds</span>
				</td>
			</tr>
			<tr>
				<td title="The maximum memory that will be selected by default for new jobs">
					Maximum Memory
				</td>
				<td id="editMaxMem">
					<label for="maxMem" class="sr-only">Maximum memory in MB</label>
					<input type="number" name="maxMem" id="maxMem" min="1" aria-describedby="memory-desc"/>
					<span class="input-unit" aria-hidden="true">MB</span>
					<span id="memory-desc" class="sr-only">Enter memory limit in MB</span>
				</td>
			</tr>
				<tr>
					<td>Dependencies Enabled</td>
					<td>
						<label for="editDependenciesEnabled" class="sr-only">Enable dependencies</label>
						<select class="dependencySetting" id="editDependenciesEnabled" name="editDependenciesEnabled">
							<option value="true">True</option>
							<option value="false">False</option>
						</select>
					</td>
				</tr>
				<tr id="defaultSolverRow">
					<td>Default Solver</td>
					<td id="solver">
						<p id="solverNameField"></p>
						<button class="btn btn-secondary selectPrim clearSolver" type="button">Clear Solver</button>
					</td>
				</tr>
				</tbody>
			</table>

			<section class="setting-actions">
				<h3>Profile Actions</h3>
				<div class="action-buttons">
					<button id="saveProfile" class="btn btn-secondary" type="button">Save Profile Changes</button>
					<button id="createProfile" class="btn btn-secondary" type="button">Create New Profile</button>
					<button id="setDefaultProfile" class="btn btn-secondary" type="button"
					        title="Setting a profile as a default means it will be selected automatically when visiting the job creation page">
						Set Profile as Default
					</button>
					<c:set var="hasDefault" value="${defaultProfileId != null && defaultProfileId > 0}" />
					<button id="clearDefaultProfile" class="btn btn-secondary" type="button"
					        title="Remove your currently selected default profile"
					        ${hasDefault ? '' : 'disabled="disabled"'}>
						Clear Default Profile
					</button>
					<button id="deleteProfile" class="btn btn-secondary" type="button">Delete Selected Profile</button>
				</div>
			</section>
		</section>

		<section class="solvers-section">
			<h2>Solvers</h2>
			<table id="solverList" role="table" aria-label="User solvers">
				<thead>
				<tr>
					<th scope="col">Name</th>
					<th scope="col">Description</th>
					<th scope="col">Type</th>
				</tr>
				</thead>
				<tbody>
				<!-- Will be populated using AJAX -->
				</tbody>
			</table>
			<button id="useSolver" class="btn btn-secondary" type="button">Use Selected Solver</button>
		</section>

		<section class="benchmarks-section">
			<h2>Benchmarks</h2>
			<table id="benchmarkList" role="table" aria-label="User benchmarks">
				<thead>
				<tr>
					<th scope="col">Name</th>
					<th scope="col">Type</th>
				</tr>
				</thead>
				<tbody>
				<!-- Will be populated using AJAX -->
				</tbody>
			</table>
			<button id="useBenchmark" class="btn btn-secondary" type="button">Use Selected Benchmark</button>
		</section>

		<c:if test="${canDeleteUser}">
			<section class="danger-zone">
				<h2>Delete User</h2>
				<p class="warning">This action cannot be undone.</p>
				<button id="deleteUser" type="button" class="btn btn-secondary">Delete User</button>
			</section>
		</c:if>

		<!-- Dialogs -->
		<div id="dialog-confirm-delete" title="Confirm Delete" class="hiddenDialog" role="dialog" aria-modal="true" aria-labelledby="dialog-confirm-delete-title">
			<h2 id="dialog-confirm-delete-title" class="sr-only">Confirm Delete</h2>
			<p><span class="ui-icon ui-icon-alert" aria-hidden="true"></span><span
					id="dialog-confirm-delete-txt"></span></p>
		</div>

		<div id="dialog-createSettingsProfile" title="Create Settings Profile" class="hiddenDialog" role="dialog" aria-modal="true" aria-labelledby="dialog-createSettingsProfile-title">
			<h2 id="dialog-createSettingsProfile-title" class="sr-only">Create Settings Profile</h2>
			<p><span id="dialog-createSettingsProfile-txt"></span></p>
			<div>
				<label for="settingName">Profile Name:</label>
				<input id="settingName" type="text" aria-describedby="setting-name-desc"/>
				<span id="setting-name-desc" class="sr-only">Enter a name for the new settings profile</span>
			</div>
		</div>
	</main>
</star:template>
