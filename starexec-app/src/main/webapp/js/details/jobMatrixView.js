// =============================================================================
// JOB MATRIX VIEW — JavaScript Controller
// =============================================================================
// Manages metric visibility toggles, stage navigation, and live polling
// for in-progress job pair updates.
// =============================================================================

"use strict";

// Ordered list of checkbox classes for metric toggles
var orderedCheckboxClasses = [
	'.cpuTimeCheckbox',
	'.memUsageCheckbox',
	'.wallclockCheckbox'
];

// Maps each checkbox class to the content class it controls visibility of
var classControlledByCheckbox = {
	'.cpuTimeCheckbox': '.cpuTime',
	'.memUsageCheckbox': '.memUsage',
	'.wallclockCheckbox': '.wallclock'
};

// Tracks enabled/disabled state of each checkbox
var checkboxEnabled = {
	'.cpuTimeCheckbox': true,
	'.memUsageCheckbox': true,
	'.wallclockCheckbox': true
};

var jobId;
var jobSpaceId;
var stageNumber;

// =============================================================================
// ENTRY POINT
// =============================================================================

$(document).ready(function() {
	var $matrixPanel = $('#matrixPanel');
	jobId = $matrixPanel.data('job-id');
	jobSpaceId = $matrixPanel.data('job-space-id');
	stageNumber = $matrixPanel.data('stage');

	registerCheckboxEventHandlers();

	var table = $('#jobMatrix').dataTable({
		'bSort': false,
		'scrollY': '220px',
		'scrollX': '100%',
		'scrollCollapse': true,
		'paging': false
	});

	moveSearchControlToControls();

	new $.fn.dataTable.FixedColumns(table);

	$(window).resize(function() {
		table.fnDraw();
	});

	// Stage navigation
	$('#selectStageButton').click(function() {
		log('Select stage button clicked.');
		var stageToRedirectTo = $('#selectStageInput').val();
		log('Input value is ' + stageToRedirectTo);
		if (isInt(stageToRedirectTo)) {
			log('Input value is an integer, redirecting.');
			window.location.replace(
				starexecRoot + 'secure/details/jobMatrixView.jsp?jobSpaceId=' +
				jobSpaceId + '&stage=' + stageToRedirectTo
			);
		} else {
			log('Input value is not an integer, showing error message.');
			$('#selectStageError').removeClass('hidden');
		}
	});

	// Allow Enter key in stage input
	$('#selectStageInput').keypress(function(e) {
		if (e.which === 13) {
			e.preventDefault();
			$('#selectStageButton').click();
		}
	});

	// Start polling for live updates
	getFinishedJobPairsFromServer(false, table);
});

/**
 * Moves DataTables' generated search control into the matrix control bar,
 * so metric toggles and search are grouped in one coherent UI row.
 */
function moveSearchControlToControls() {
	var $filter = $('#jobMatrix_filter');
	var $controls = $('.matrixControls');

	if ($filter.length && $controls.length) {
		$filter.detach().appendTo($controls);
	}
}

// =============================================================================
// LIVE POLLING — Fetches updated job pair data from the server
// =============================================================================

function getFinishedJobPairsFromServer(done, dataTable) {
	if (!done) {
		$.get(
			starexecRoot + 'services/matrix/finished/' + jobSpaceId + '/' + stageNumber,
			'',
			function(data) {
				log(data);
				setTimeout(function() {
					updateMatrix(data.benchSolverConfigElementMap, dataTable);
					getFinishedJobPairsFromServer(data.done, dataTable);
				}, 5000);
			},
			'json'
		);
	}
}

function updateMatrix(jobPairData, dataTable) {
	for (var key in jobPairData) {
		if (jobPairData.hasOwnProperty(key)) {
			var selector = '#' + key;
			var pair = jobPairData[key];

			$(selector + ' .wallclock').text(pair.wallclock);
			$(selector + ' .memUsage').text(pair.memUsage);
			$(selector + ' .cpuTime').text(pair.cpuTime);
			$(selector).removeClass('incomplete');
			$(selector).addClass(pair.status);
			$(selector).attr('data-status', pair.status);

			// Update tooltip with new values
			$(selector).attr('title',
				'Wallclock: ' + pair.wallclock + 's | CPU: ' +
				pair.cpuTime + 's | Memory: ' + pair.memUsage
			);
		}
	}
	// Redraw the table
	dataTable.fnDraw(false);
}

// =============================================================================
// INPUT VALIDATION
// =============================================================================

function isInt(value) {
	var intRegex = /^[1-9][0-9]*$/;
	return intRegex.test(value);
}

// =============================================================================
// METRIC TOGGLE CHECKBOXES
// =============================================================================

/**
 * Updates the visibility of slash dividers between metrics based on
 * which checkboxes are currently enabled. This ensures dividers only
 * appear between two visible adjacent metrics.
 */
function updateDividers() {
	var cpu = checkboxEnabled['.cpuTimeCheckbox'];
	var mem = checkboxEnabled['.memUsageCheckbox'];
	var wall = checkboxEnabled['.wallclockCheckbox'];

	// Show divider between cpu and memory only if both visible
	$('.cpuTimeMemUsageDivider').toggle(cpu && mem);

	// Show divider between memory and wallclock only if both visible
	$('.memUsageWallclockDivider').toggle(mem && wall);

	// Show divider between cpu and wallclock only if both visible
	// AND memory is hidden (otherwise the other dividers handle it)
	$('.cpuTimeWallclockDivider').toggle(cpu && !mem && wall);
}

/**
 * Toggles the visibility of the metric controlled by the given checkbox class
 * and updates its tracking state.
 */
function toggleCheckbox(checkboxClass) {
	$(classControlledByCheckbox[checkboxClass]).toggle();
	checkboxEnabled[checkboxClass] = !checkboxEnabled[checkboxClass];
}

/**
 * Updates the visual state of the toggle pill label to reflect
 * whether its checkbox is checked.
 */
function updateTogglePillState($checkbox) {
	var $label = $checkbox.closest('.metric-toggle');
	if ($checkbox.is(':checked')) {
		$label.addClass('metric-toggle--active');
	} else {
		$label.removeClass('metric-toggle--active');
	}
}

/**
 * Registers click event handlers for each metric toggle checkbox.
 * When a checkbox is clicked, toggles the corresponding metric visibility
 * and updates all dividers on the page.
 */
function registerCheckboxEventHandlers() {
	orderedCheckboxClasses.forEach(function(checkboxClass) {
		$(checkboxClass).on('change', function() {
			toggleCheckbox(checkboxClass);
			updateDividers();
			updateTogglePillState($(this));
		});
	});
}
