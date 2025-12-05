"use strict";

var dialog = null;

$(document).ready(function() {

	initUI();
	attachFormValidation();

	$('#radioNoPause').attr('checked', 'checked');

});

function getMaxCpuTimeout() {
	var maxtime = $("#workerQueue option:selected").attr("cpumax");
	return parseInt(maxtime);
}

function getMaxWallTimeout() {
	var maxtime = $("#workerQueue option:selected").attr("wallmax");
	return parseInt(maxtime);
}

function getCpuTimeoutErrorMessage() {
	var timeout = getMaxCpuTimeout();
	if (isNaN(timeout)) {
		return "please select a queue";
	}
	return timeout + " second max timeout";
}

function getClockTimeoutErrorMessage() {
	var timeout = getMaxWallTimeout();
	if (isNaN(timeout)) {
		return "please select a queue";
	}
	return timeout + " second max timeout";
}

/**
 * Attach validation to the job creation form
 */
function attachFormValidation() {
	// Add regular expression capabilities to the validator
	
	addValidators();

	// Set up form validation
	$("#addForm").validate({
		submitHandler: function(form) {
			createDialog(
				"Creating your job, please wait. This will take some time for large jobs.");

			form.submit();
		},
		ignore: "", //don't ignore hidden inputs
		rules: {
			name: {
				required: true,
				minlength: 1,
				maxlength: $("#txtJobName").attr("length"),
				jspregex: "DUMMY REGEX"
			},
			desc: {
				required: false,
				maxlength: $("#txtDesc").attr("length"),
				regex: getPrimDescRegex()
			},
			cpuTimeout: {
				required: true,
				max: getMaxCpuTimeout(),
				min: 1
			},
			wallclockTimeout: {
				required: true,
				max: getMaxWallTimeout(),
				min: 1
			},
			maxMem: {
				required: true,
				min: 0
			},
			queue: {
				required: true
			},
			benchName: {
				required: true,
				minlength: 1,
				maxlength: $("#txtBenchName").attr("length"),
				jspregex: "DUMMY REGEX"
			},
			bench: {
				required: true,
				minlength: 1
			},
			solver: {
				required: true,
				min: 1
			},
			benchProcess: {
				required: true
			},
			postProcess: {
				required: true

			},
			preProcess: {
				required: true

			}
		},
		messages: {
			name: {
				required: "enter a job name",
				minlength: "1 character minimum",
				maxlength: $("#txtJobName")
				.attr("length") + " characters maximum",
				jspregex: "invalid character(s)"
			},
			benchName: {
				required: "enter a benchmark name",
				minlength: "1 character minimum",
				maxlength: $("#txtBenchName")
				.attr("length") + " characters maximum",
				jspregex: "invalid character(s)"
			},
			bench: {
				required: "enter a benchmark",
				minlength: "1 character minimum"
			},
			desc: {
				required: "enter a job description",
				maxlength: $("#txtDesc").attr("length") + " characters maximum",
				regex: "invalid character(s)"
			},
			cpuTimeout: {
				required: "enter a timeout",
				max: getCpuTimeoutErrorMessage(),
				min: "1 second minimum timeout"
			},
			solver: {
				required: "choose a solver",
				min: "choose a solver"
			},
			wallclockTimeout: {
				required: "enter a timeout",
				max: getClockTimeoutErrorMessage(),
				min: "1 second minimum timeout"
			},
			maxMem: {
				required: "enter a maximum memory",
				max: "100 gigabytes maximum"
			},
			queue: {
				required: "error - no worker queues"
			},
			benchProcess: {
				required: "choose a benchmark processor"
			},
			postProcess: {
				required: "choose a postprocessor"
			},
			preProcess: {
				required: "choose a preprocessor"
			}
		}
	});

	//when we change queues, we need to refresh the validation to use the new timeouts
	$("#workerQueue").change(function() {
		var settings = $('#addForm').validate().settings;
		settings.rules.cpuTimeout = {
			required: true,
			max: getMaxCpuTimeout(),
			min: 1
		};

		settings.rules.wallclockTimeout = {
			required: true,
			max: getMaxWallTimeout(),
			min: 1
		};

		settings.messages.cpuTimeout = {
			required: "enter a timeout",
			max: getMaxCpuTimeout() + " second max timeout",
			min: "1 second minimum timeout"
		};

		settings.messages.wallclockTimeout = {
			required: "enter a timeout",
			max: getMaxWallTimeout() + " second max timeout",
			min: "1 second minimum timeout"
		};
		$("#addForm").valid(); //revalidate now that we have new rules

	});

	$("#useSolver").button({
		icons: {
			primary: "ui-icon-check"
		}
	});
	$("#useSolver").click(function(e) {
		useSelectedSolver();
		e.preventDefault();
	});
}

/**
 * Sets up the jQuery button style and attaches click handlers to those buttons.
 */
function initUI() {

	//there must be some bench processor selected, so make sure we are using one
	$("#benchProcess").find("option").first().attr("selected", "selected");

	$('#btnBack').button({
		icons: {
			primary: "ui-icon-arrowthick-1-w"
		}
	}).click(function() {

		history.back(-1);
	});

	var advancedJobOptionsCollapsed = $.cookie('advancedJobOptions') != 'false';
	$('#advancedSettings')
	.expandable(advancedJobOptionsCollapsed)
	.children('legend:first')
	.click(function() {
		var advancedJobOptionsCollapsed = !$(this).data('open');
		$.cookie('advancedJobOptions',
			advancedJobOptionsCollapsed,
			{expires: 10000, path: '/'});
	});

	$("#solverField").expandable(true);
	$('#btnDone').button({
		icons: {
			secondary: "ui-icon-check"
		}
	});

	$("#solverList").dataTable({
		"sDom": getDataTablesDom(),
		"iDisplayStart": 0,
		"iDisplayLength": defaultPageSize,
		"bServerSide": true,
		"sAjaxSource": starexecRoot + "services/",
		"sServerMethod": 'POST',
		"fnServerData": fnPaginationHandler
	});

	$("#solverList").on("mousedown", "tr", function() {
		if ($(this).hasClass("row_selected")) {
			$(this).removeClass("row_selected");
		} else {
			unselectAll();
			$(this).addClass("row_selected");
		}
	});
}

function unselectAll() {
	$("#solverList").find("tr").removeClass("row_selected");
}

function fnPaginationHandler(sSource, aoData, fnCallback) {
	// Get the space ID from the data attribute and parse once
	var spaceIdRaw = $("#spaceIdField").data("space-id");
	var spaceId = parseInt(spaceIdRaw, 10);
	var isValidSpaceId = !isNaN(spaceId) && spaceId > 0;
	
	// Use space-specific endpoint if space ID is valid, otherwise fall back to user solvers
	var endpoint;
	if (isValidSpaceId) {
		endpoint = sSource + "space/" + spaceId + "/solver/pagination/";
	} else {
		endpoint = sSource + "users/solvers/pagination";
	}
	
	// Request the next page of primitives from the server via AJAX
	$.post(
		endpoint,
		aoData,
		function(nextDataTablePage) {
			var s = parseReturnCode(nextDataTablePage);
			if (s) {
				// Check if no solvers are available
				if (nextDataTablePage.iTotalRecords === 0 && isValidSpaceId) {
					showNoSolversWarning();
				}
				// Replace the current page with the newly received page
				fnCallback(nextDataTablePage);
			}
		},
		"json"
	);
}

function showNoSolversWarning() {
	// Get the space ID from the data attribute
	var spaceIdRaw = $("#spaceIdField").data("space-id");
	// Validate spaceId is a positive integer to prevent XSS
	var spaceId = parseInt(spaceIdRaw, 10);
	if (isNaN(spaceId) || spaceId <= 0) {
		return;
	}
	
	var $warning = $("#noSolversWarning");
	if ($warning.length === 0) {
		// Build alert using jQuery DOM methods for safety and readability
		var $alert = $("<div>", {
			id: "noSolversWarning",
			"class": "alert alert--warning",
			role: "alert"
		});
		
		var $icon = $("<span>", {
			"class": "ui-icon ui-icon-alert alert-icon"
		});
		
		var $content = $("<div>", {
			"class": "alert-content"
		});
		
		// Use encodeURIComponent for URL safety
		var $link = $("<a>", {
			href: starexecRoot + "secure/explore/spaces.jsp?id=" + encodeURIComponent(spaceId),
			text: "Add solvers to the space"
		});
		
		$content.append(
			document.createTextNode("No solvers are available in this space. "),
			$link,
			document.createTextNode(" or select a different space.")
		);
		
		$alert.append($icon, $content);
		$("#solverField legend").after($alert);
		
		// Disable the use solver button and update solver display
		$("#useSolver").button("disable");
		$("#solverNameField").html($("<em>", {
			"class": "text-muted",
			text: "No solvers available"
		}));
	}
}
