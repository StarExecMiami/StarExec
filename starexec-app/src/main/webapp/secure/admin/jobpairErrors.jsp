<%@page contentType="text/html" pageEncoding="UTF-8" %>
<%@page import="java.time.LocalDate"%>
<%@page import="java.time.format.DateTimeFormatter"%>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%
	LocalDate today = LocalDate.now();
	LocalDate yesterday = today.minusDays(1);
	DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	pageContext.setAttribute("todayStr", today.format(formatter));
	pageContext.setAttribute("yesterdayStr", yesterday.format(formatter));
%>
<star:template title="JobPair Errors"
               js=" common/format, lib/jquery.dataTables.min, lib/jquery.jstree, admin/jobpairErrors"
               css="common/table, admin/jobpairErrors">

	<!-- Screen reader announcement region -->
	<div id="jobpairerrors_live_region" aria-live="polite" aria-atomic="true" class="sr-only"></div>

	<!-- Loading indicator -->
	<div id="jobpairerrors_loading" class="jobpairerrors-loading" aria-hidden="true" style="display: none;">
		<div class="spinner"></div>
		<p>Loading error data...</p>
	</div>

	<!-- Error messages container -->
	<div id="jobpairerrors_error" role="alert" aria-live="assertive" class="jobpairerrors-error-container" style="display:none;"></div>

	<!-- Informational messages container -->
	<div id="jobpairerrors_info" role="status" aria-live="polite" class="jobpairerrors-info-container" style="display:none;"></div>

	<!-- Date selection form -->
	<form id="dateselector" method="post" action="#" role="search">
		<fieldset>
			<legend>Filter by date range</legend>

			<div class="form-group">
				<label for="dateselector_start">Start Date:</label>
				<input type="date" id="dateselector_start" name="start" class="form-input" value="${yesterdayStr}" aria-describedby="dateselector_start_help"/>
				<small id="dateselector_start_help" class="form-help">Format: YYYY-MM-DD</small>
			</div>

			<div class="form-group">
				<label for="dateselector_end">End Date:</label>
				<input type="date" id="dateselector_end" name="end" class="form-input" value="${todayStr}" aria-describedby="dateselector_end_help"/>
				<small id="dateselector_end_help" class="form-help">Format: YYYY-MM-DD</small>
			</div>

			<!-- Date preset buttons -->
			<div class="form-presets" role="group" aria-label="Date presets">
				<button type="button" class="btn btn-preset" data-jobpairerrors-preset="today" title="Show errors from today">
					Today
				</button>
				<button type="button" class="btn btn-preset" data-jobpairerrors-preset="last7days" title="Show errors from the last 7 days">
					Last 7 Days
				</button>
				<button type="button" class="btn btn-preset" data-jobpairerrors-preset="last30days" title="Show errors from the last 30 days">
					Last 30 Days
				</button>
				<button type="button" class="btn btn-preset" data-jobpairerrors-preset="last90days" title="Show errors from the last 90 days">
					Last 90 Days
				</button>
			</div>

			<input type="submit" value="Search" class="btn btn-primary"/>
		</fieldset>
	</form>

	<!-- Results table -->
	<fieldset class="results-fieldset">
		<legend>Error results</legend>
		<div class="table-wrapper">
			<table id="jobpairErrors" class="display" role="grid" aria-label="Job pair errors"></table>
		</div>
	</fieldset>

</star:template>

