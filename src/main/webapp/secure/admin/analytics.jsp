<%@page contentType="text/html" pageEncoding="UTF-8"%>
<%@page import="java.time.LocalDate"%>
<%@page import="java.time.format.DateTimeFormatter"%>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%
	LocalDate today = LocalDate.now();
	LocalDate yesterday = today.minusDays(1);
	DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	pageContext.setAttribute("todayStr", today.format(formatter));
	pageContext.setAttribute("yesterdayStr", yesterday.format(formatter));
%>
<star:template title="Analytics"
               js="lib/jquery.dataTables.min, lib/jquery.jstree, admin/analytics"
               css="common/table, admin/analytics">

	<!-- Live region for screen reader announcements -->
	<div id="analytics_live_region" class="sr-only" aria-live="polite" aria-atomic="true"></div>

	<!-- Loading indicator -->
	<div id="analytics_loading" class="analytics-loading" aria-hidden="true" style="display: none;">
		<div class="spinner"></div>
		<p>Loading analytics data...</p>
	</div>

	<!-- Error message container -->
	<div id="analytics_error" class="analytics-error-container" style="display: none;" role="alert">
		<!-- Errors populated by JavaScript -->
	</div>

	<!-- Info message container -->
	<div id="analytics_info" class="analytics-info-container" style="display: none;" role="status">
		<!-- Info messages populated by JavaScript -->
	</div>

	<!-- Date filter form -->
	<form id="dateselector" method="get" action="#" role="search">
		<fieldset>
			<legend>Date Range</legend>

			<div class="form-group">
				<label for="analytics_start">Start Date:</label>
				<input
					id="analytics_start"
					type="date"
					name="start"
					class="form-input"
					value="${yesterdayStr}"
					aria-describedby="analytics_help_start"/>
				<small id="analytics_help_start" class="form-help">Format: YYYY-MM-DD</small>
			</div>

			<div class="form-group">
				<label for="analytics_end">End Date:</label>
				<input
					id="analytics_end"
					type="date"
					name="end"
					class="form-input"
					value="${todayStr}"
					aria-describedby="analytics_help_end"/>
				<small id="analytics_help_end" class="form-help">Format: YYYY-MM-DD</small>
			</div>

			<!-- Date preset buttons -->
			<div class="form-presets" role="group" aria-label="Date presets">
				<button type="button" class="btn btn-preset" data-analytics-preset="today" title="Show data from today">
					Today
				</button>
				<button type="button" class="btn btn-preset" data-analytics-preset="last7days" title="Show data from the last 7 days">
					Last 7 Days
				</button>
				<button type="button" class="btn btn-preset" data-analytics-preset="last30days" title="Show data from the last 30 days">
					Last 30 Days
				</button>
				<button type="button" class="btn btn-preset" data-analytics-preset="last90days" title="Show data from the last 90 days">
					Last 90 Days
				</button>
			</div>

			<input type="submit" value="Update" class="btn btn-primary"/>
		</fieldset>
	</form>

	<!-- Results table -->
	<fieldset class="results-fieldset">
		<legend>Event Totals</legend>
		<div class="table-wrapper">
			<table id="analytics_results" class="display" role="grid" aria-label="Analytics events summary"></table>
		</div>
	</fieldset>

</star:template>

