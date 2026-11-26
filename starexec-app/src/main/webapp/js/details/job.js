"use strict";

var summaryTable;
var pairTable;
var curSpaceId; //stores the ID of the job space that is currently selected from the space viewer
var curSolverId = null; //stores the selected solver ID when filtering pairs across an entire job
var curSolverName = null; //stores the selected solver name for pair-table status text
var jobId; //the ID of the job being viewed
var lastValidSelectOption;
var panelArray = [];

var useWallclock = true;
var syncResults = false;
var DETAILS_JOB = {};
var selectedJobSpaceId = null;
var getPanelTableInitializer;
// contains requests that have been sent to the server to update pairs, stats, or graphs that
// have not yet returned. If the user clicks on a new job space, these requests will all
// be aborted, as they will no longer be useful.
var openAjaxRequests = [];
var includeUnknown = false;
var solverComparisonPendingLoad = false;
var suppressNextComparisonDialog = false;
var jobChartInstances = {};
var latestChartData = {
	pairTime: null,
	spaceOverview: null,
	solverComparison: null
};
var JOB_CHART_SERIES_COLORS = ['#fa621b', '#3b82f6', '#10b981', '#8b5cf6', '#f39c12'];
var JOB_CHART_THEME = {
	text: '#f8f8f8',
	axis: '#999999',
	grid: '#2d2d2d',
	tooltipBg: 'rgba(31, 31, 31, 0.94)',
	tooltipBorder: '#404040',
	accent: '#fa621b',
	accentFill: 'rgba(250, 98, 27, 0.2)',
	scatterPointBg: 'rgba(250, 98, 27, 0.78)'
};

function isGraphPanelVisible() {
	var $graphField = $("#graphField");
	return $graphField.length > 0 && $graphField.is(":visible");
}

function getResponsiveDialogConfig(preferredWidth) {
	var viewportWidth = Math.max(document.documentElement.clientWidth || 0, window.innerWidth || 0);
	var viewportHeight = Math.max(document.documentElement.clientHeight || 0, window.innerHeight || 0);
	var maxWidth = Math.max(320, viewportWidth - 32);
	return {
		width: Math.min(preferredWidth, maxWidth),
		maxWidth: maxWidth,
		height: 'auto',
		maxHeight: Math.max(260, Math.floor(viewportHeight * 0.9))
	};
}

$(document).ready(function() {
    initializeGlobalPageVariables();
	initUI();
	initSpaceExplorer();
	initDataTables();

	if (!isLocalJobPage) {
		//update the tables every 30 seconds
		star.startPagePolling(function() {
			pairTable.api().draw(false);
		}, 30000);
	}
	//puts data into the data tables
	reloadTables($("#spaceId").attr("value"));

	$("#solverSummaryField").hide()
	$("#graphField").hide()
	setupEverythingForUnknownStatusWrapper();
	
});

// Initializes the fields of the global DETAILS_JOB object.
function initializeGlobalPageVariables() {
	// Set these first since get functions may depend no them
	DETAILS_JOB.isAnonymousPage = $('#isAnonymousPage')
	.attr('value') === 'true';
	jobId = $('#jobId').attr('value');
	DETAILS_JOB.starexecUrl = $('#starexecUrl').attr('value');
	DETAILS_JOB.rootJobSpaceId = $('#spaceId').attr('value');
	DETAILS_JOB.primitivesToAnonymize = $('#primitivesToAnonymize')
	.attr('value');
	DETAILS_JOB.anonymousLinkUuid = getParameterByName('anonId');
	DETAILS_JOB.spaceExplorerJsonData = getSpaceExplorerJsonData();	
	

	log("starexecUrl: " + DETAILS_JOB.starexecUrl);
	log("isLocalJobPage: " + isLocalJobPage);
	log("starexecRoot: " + starexecRoot);
	log("isAnonymousPage: " + DETAILS_JOB.isAnonymousPage);
	log("primitivesToAnonymize: " + DETAILS_JOB.primitivesToAnonymize);
}

// Gets the JSON representation of the space explorer from the hidden <span> containing it.
function getSpaceExplorerJsonData() {
	if (isLocalJobPage) {
		try {
			var items = JSON.parse($('#jobSpaceTreeJson').attr('value'));
			return Array.isArray(items) ? items : [];
		} catch (error) {
			log("Could not parse the offline job space tree.");
			return [];
		}
	}
	if (DETAILS_JOB.isAnonymousPage) {
		return starexecRoot + "services/space/anonymousLink/" + DETAILS_JOB.anonymousLinkUuid
			+ "/jobspaces/true/" + DETAILS_JOB.primitivesToAnonymize;
	}
	return starexecRoot + "services/space/" + jobId + "/jobspaces/true";
}

function setSyncResultsText() {
	if (syncResults) {
		$("#syncResults .ui-button-text").html("un-synchronize results");
	} else {
		$("#syncResults .ui-button-text").html("synchronize results");
	}
}

function isFilteringPairsBySolver() {
	return curSolverId !== null && !DETAILS_JOB.isAnonymousPage;
}

function updatePairFilterUI() {
	var $status = $("#pairFilterStatus");
	var $clearButton = $("#clearPairFilter");
	var $syncButton = $("#syncResults");

	if (!$status.length) {
		return;
	}

	if (isFilteringPairsBySolver()) {
		$status.text("showing all job pairs for solver " + curSolverName);
		$clearButton.show();
		if ($syncButton.length) {
			$syncButton.hide();
		}
	} else {
		$status.text("");
		$clearButton.hide();
		if ($syncButton.length) {
			$syncButton.show();
		}
	}
}

function clearPairFilter(shouldRedraw) {
	curSolverId = null;
	curSolverName = null;
	updatePairFilterUI();
	if (shouldRedraw && pairTable) {
		pairTable.api().draw(false);
	}
}

function applyPairFilterForSelectedSolver(rowData) {
	if (!rowData || rowData.length < 3) {
		clearPairFilter(true);
		return;
	}

	curSolverId = rowData[0];
	curSolverName = rowData[2];
	if (syncResults) {
		syncResults = false;
		setSyncResultsText();
	}
	updatePairFilterUI();
	if (pairTable) {
		pairTable.api().draw(false);
	}
}

/* 
* This function takes the json and returns a 2d array representing the data we need for our datatable
* but alex, what if we had a million job pairs, wouldn't this be slow? If we had that amout, the user
* problably won't download the local job page. We warned the users that this might take a while to 
* download for large jobs when the user tries to download it...
* @author aguo2
*/
function parseStatsForLJP(json, isShortStats) {
	/* as of right now, the last 8 items are excluded in short stats. 
	   we need to CHANGE THIS IF THE API CHANGES OR IT WILL BREAK!
	   This choice for the api was originaly made to reduce the data
	   sent from the backend
	*/
	if (isShortStats) {
		var array = json.aaData;
		for (var i = 0; i < array.length; i ++ ) {
			for (var j = 0; j < 8; j++) {
				array[i].pop();
			}
		}
		return array;
	}
	else {
		//we want the full stats, just return the aadata
		return json.aaData;
	}

}

/* 
* goes through each of the subspace summaries and reloads the data based on if
* this is a local job page
* @author aguo2 and pathawks
*/
function refreshPanels() {
	if (!isLocalJobPage) {
		for (var i = 0; i < panelArray.length; i++) {
			panelArray[i].api().ajax.reload(null, true);
		}
	}
	else {
		//go through all the subspace sumaries and refresh it
		for (var i = 0; i < panelArray.length; i ++) {
				
			//the simplest way is to just parse the element out of the id.
			let id = parseInt(panelArray[i].attr("id").replace('panel',''));
			//get the json and parse for current pannel
			let json = JSON.parse($(getIDStringForJSON(id)).attr("value"));
			/*console.log has some weird problem as shown in the link, this is why was do 
			stringify to make sure it works. This took me an hour to figure out...
			let this be a WARNING to the next person that sees this
			https://stackoverflow.com/questions/23392111/console-log-async-or-sync
			*/
			//console.log(JSON.stringify(json));
			var newDataArray = parseStatsForLJP(json, true);
			//console.log(JSON.stringify(json));
			panelArray[i].DataTable().clear().rows.add(newDataArray).draw();
			}
			loadStatsIntoSolveTbl();
	}
}

function refreshStats(id) {
	var updateGraphs = function() {
		if (summaryTable.api().page.info().recordsTotal == 0) {
			solverComparisonPendingLoad = false;
			$("#graphField").hide();
		} else {
			/* This is can be one of the most intense loops on this page,
			 * so we are waving goodbye to jQuery and dropping down to
			 * manipulating the DOM directly. I am sorry if this makes the
			 * code more difficult to read.
			 */
			var $spaceOverviewSelections = $("#spaceOverviewSelections"),
				solverOptions = document.createDocumentFragment(),
				rows = summaryTable.api().data().toArray();
			for (var i = 0; i < rows.length; ++i) {
				var row = rows[i],
					solverName = row[2],
					configName = row[3],
					configId = row[1];

				var option = document.createElement("option");
				option.value = configId;
				option.text = solverName + "/" + configName;

				solverOptions.appendChild(option);
			}
			/* We need to make a deep clone of `solverOptions` if we want to add
			 * this fragment to multiple places in our document. So, we will clone
			 * when we append to `solverChoice`` and `solverChoice2`, and we will
			 * just append the original to `$spaceOverviewSelections` since we will
			 * not need it anymore
			 */
			document.getElementById("solverChoice1")
			.appendChild(solverOptions.cloneNode(true));

			document.getElementById("solverChoice2")
			.appendChild(solverOptions.cloneNode(true));

			$spaceOverviewSelections.empty().append(solverOptions);
			// $("#graphField").show();
			$("#graphField").hide();

			//select first five solver/ configuration pairs
			$spaceOverviewSelections.children("option:lt(5)")
			.prop("selected", true);
			lastValidSelectOption = $spaceOverviewSelections.val();
			// updateSpaceOverviewGraph();
			// updatePairJobTimeGraph();
			if (summaryTable.api().page.info().recordsTotal > 1) {
				$("#solverComparison").show();
				$("#solverChoice1")
				.children("option:first")
				.prop("selected", true);
				$("#solverChoice2")
				.children("option:nth-child(2)")
				.prop("selected", true);
				if (isGraphPanelVisible()) {
					solverComparisonPendingLoad = false;
					updateSolverComparison();
				} else {
					solverComparisonPendingLoad = true;
				}
			} else {
				solverComparisonPendingLoad = false;
				$("#solverComparison").hide();
				$("#solverComparisonOptionField").hide();
			}
		}
	};
	summaryTable.api().ajax.reload(updateGraphs, true);
}

function createDownloadRequest(item, type, returnIds, getCompleted) {
	createDialog(
		"Processing your download request, please wait. This will take some time for large jobs.");
	var token = Math.floor(Math.random() * 100000000);
	var href = DETAILS_JOB.starexecUrl + "secure/download?token=" + token + "&type=" + type + "&id=" + $(
		"#jobId").attr("value");
	if (typeof returnIds != 'undefined') {
		href = href + "&returnids=" + returnIds;
	}
	if (typeof getCompleted != 'undefined') {
		href = href + "&getcompleted=" + getCompleted;
	}
	$(item).attr('href', href);
	destroyOnReturn(token);		//when we see the download token as a cookie, destroy the dialog box
	window.location.href = href;
}

function initOfflinePairNavigation() {
	$("#pairTblField").on("click", ".offline-pair-table tbody tr", function(event) {
		if (event.target.closest("a")) {
			return;
		}
		var link = this.querySelector(".offline-pair-link");
		if (link) {
			window.location.assign(link.href);
		}
	});
}

function initSpaceExplorer() {
	star.mountTree($("#exploreList"), DETAILS_JOB.spaceExplorerJsonData, {
		rootId: 0,
		initial: DETAILS_JOB.rootJobSpaceId,
		selectParentOnClose: true,
		select: function(node) {
			var id = node.id;
			if (selectedJobSpaceId == null) {
				selectedJobSpaceId = id;
			}
			if (selectedJobSpaceId !== id) {
				killAjaxRequests();
				selectedJobSpaceId = id;
				setMaxStagesDropdown(node.data.maxStages);
				$(".spaceName").text(node.text);
				$("#displayJobSpaceID").text("job space id  = " + id);
				$("#compareSolvers").hide();
				reloadTables(id);
				if (isLocalJobPage) {
					loadStatsIntoSolveTbl();
				}
			}
		}
	});
}

function killAjaxRequests() {
	log('killing ajax requests');
	for (var i = 0; i < openAjaxRequests.length; i++) {
		openAjaxRequests[i].abort();
	}
	openAjaxRequests = [];
}

function clearPanels() {
	for (var i = 0; i < panelArray.length; ++i) {
		var panel = panelArray[i];
		if (panel.data("panelRefreshInterval") !== undefined) {
			window.clearInterval(panel.data("panelRefreshInterval"));
		}
		panel.api().destroy();
		panel.remove();
	}
	$(".panelField").remove();
	panelArray = [];
}

function reloadTables(id) {
	//we only need to update if we've actually selected a new space
	if (curSpaceId != id) {
		curSpaceId = id;
		// Seed the current tree selection so the first real explorer click
		// is treated as a navigation event instead of the initial load state.
		selectedJobSpaceId = id;
		clearPairFilter(false);
		clearPanels();
		if (!isLocalJobPage) {
			// summaryTable.fnClearTable();	//immediately get rid of the current data, which makes it look more responsive
			pairTable.fnClearTable();

			//clear out the graphs
			solverComparisonPendingLoad = false;
			$("#solverChoice1").empty();
			$("#solverChoice2").empty();
			$("#spaceOverviewSelections").empty();
			resetAllChartsToLoading();

			//tell the tables to display a "loading" indicator
			summaryTable.fnProcessingIndicator(true);
			pairTable.fnProcessingIndicator(true);

			refreshStats(id);

		} else {
			//if its a local job page:
			$('[id$=pairTbl_wrapper]').hide();
			$('#pairTblField').show();
			//we should hide the pair table field if there are no elements in the table. 
			//IE when we have a job with multiple subspaces and we are at the root
			if ($('#' + id + 'pairTbl').length == 0) {
				$('#pairTblField').hide();
			}
			else {
				$('#' + id + 'pairTbl_wrapper').show();
			}
			
		}
		initializePanels();
	}
}

function makeJobNameUneditable() {
	// Gets rid of the '(click to edit)' part of the text.
	$('#jobNameTitle').text('name');
	$('#editJobNameWrapper').hide();
}

function makeJobDescriptionUneditable() {
	$('#jobDescriptionTitle').text('description');
	$('#editJobDescriptionWrapper').hide();
}

//
//  Initializes the user-interface
//
function initUI() {
	$("#errorField").hide();
	$("#statsErrorField").hide();
	$("#pairTblField").toggleClass("show-cpu-time", !useWallclock);
	$("#clearPairFilter").hide();
	$("#pairFilterStatus").text("");

	$("#spaceOverview").show();
	$("#solverComparison300").hide();
	$("#pairTimeGraph").hide();	
	$("#spaceOverviewOptionField").show();
	$("#solverComparisonOptionField").hide();
	$("#pairTimeOptionField").hide();

	$("#addJobPairs")
	;

	$("#rerunPairs")
	;

	$("#compareSolvers")
	.hide().click(function() {
		var c1 = $(".first_selected").find(".configLink").attr("id");
		var c2 = $(".second_selected").find(".configLink").attr("id");
		window.open(DETAILS_JOB.starexecUrl + "secure/details/solverComparison.jsp?sid=" + curSpaceId + "&c1=" + c1 + "&c2=" + c2);
	});

	$('#clearCache')
	.click(function() {
		$("#dialog-warning-txt")
		.text('Are you sure you want to clear the cache for this primitive?');
		$("#dialog-warning").dialog({
			modal: true,
			width: 380,
			buttons: {
				'clear cache': function() {
					$(this).dialog("close");
					$.post(
						starexecRoot + "services/cache/clear/stats/" + jobId + "/",
						parseReturnCode
					).fail(function() {
						showMessage("error", "Internal error clearing job stats cache", 5000);
					});
				},
				"cancel": function() {
					$(this).dialog("close");
				}
			}
		});
	});

	$('#recompileSpaces')
	.click(function() {
		$.get(
			starexecRoot + "services/recompile/" + jobId,
			parseReturnCode
		).fail(function() {
			showMessage("error", "Internal error recompiling spaces", 5000);
		});
	});

	$("#downloadJobPageButton")
	.click(function() {
		createDownloadRequest("#downloadJobPageButton", "job_page");
	});

	// I think this code can be removed
	$('#setLowPriority')
	;

	$("#matrixViewButton")
	.click(function() {
		var url = DETAILS_JOB.starexecUrl + 'secure/details/jobMatrixView.jsp?stage=1&jobSpaceId=' + curSpaceId;
		if (isLocalJobPage) {
			window.location.href = url;
		} else {
			popup(url);
		}
	});

	$("#jobPairAttributes")
	.click(function() {
		var url = DETAILS_JOB.starexecUrl + 'secure/details/jobAttributes.jsp?id=' + curSpaceId;
		if (isLocalJobPage) {
			window.location.href = url;
		} else {
			popup(url);
		}
	});

	$("#solverNameKeyButton")
	.click(function() {
		var url = DETAILS_JOB.starexecUrl + 'secure/details/anonymousJobPageKey.jsp?anonId=' + DETAILS_JOB.anonymousLinkUuid;
		popup(url);
	});

	$("#syncResults")
	.click(function() {
		//just change the sync results boolean and update the button text.
		syncResults = !syncResults;
		setSyncResultsText();
		pairTable.api().draw(false);
	});

	$("#clearPairFilter")
	.click(function() {
		var $solveTbl = $("#solveTbl");
		$solveTbl.find(".row_selected")
		.removeClass("row_selected first_selected second_selected");
		$("#compareSolvers").hide();
		clearPairFilter(true);
	});

	$("#popoutPanels")
	.click(function() {
		// default to primary stage
		window.open(DETAILS_JOB.starexecUrl + "secure/details/jobPanelView.jsp?spaceid=" + curSpaceId + "&stage=1");
	});

	$("#collapsePanels")
	.click(function() {
		$(".panelField").each(function() {
			var legend = $(this).children('legend:first');
			var isOpen = $(legend).data('open');
			if (isOpen) {
				$(legend).trigger("click");
			}
		});
	});

	$("#openPanels")
	.click(function() {
		$(".panelField").each(function() {
			var legend = $(this).children('legend:first');
			var isOpen = $(legend).data('open');

			if (!isOpen) {
				$(legend).trigger("click");
			}
		});
	});

	$(".changeTime")
	.click(function() {
		useWallclock = !useWallclock;
		$("#pairTblField").toggleClass("show-cpu-time", !useWallclock);
		setTimeButtonText();
		refreshPanels();
		if (!isLocalJobPage) {
			refreshStats(curSpaceId);
			pairTable.api().draw(false);
		}
		else {
			refreshPanels();
			loadStatsIntoSolveTbl();
		}
	});

	$("#jobDownload")
	.off("click").on("click", function(e) {
		e.preventDefault();
		$('#dialog-return-ids-txt')
		.text(
			'do you want ids for job pairs, solvers, and benchmarks to be included in the CSV?');
		var returnIdsDialogConfig = getResponsiveDialogConfig(460);

		$('#dialog-return-ids').dialog({
			modal: true,
			width: returnIdsDialogConfig.width,
			maxWidth: returnIdsDialogConfig.maxWidth,
			height: returnIdsDialogConfig.height,
			maxHeight: returnIdsDialogConfig.maxHeight,
			buttons: {
				'download': function() {
					$('#dialog-return-ids').dialog('close');
					createDownloadRequest("#jobDownload",
						"job",
						$("#includeids").prop("checked"),
						$("#getcompleted").prop("checked"));
				},
				"cancel": function() {
					$(this).dialog("close");
				}
			}
		});
	});

	$("#jobXMLDownload")
	.off("click").on("click", function(e) {
		e.preventDefault();
		createDownloadRequest("#jobXMLDownload", "jobXML");
	});

	$("#jobOutputDownload")
	.off("click").on("click", function(e) {
		e.preventDefault();
		createDownloadRequest("#jobOutputDownload", "j_outputs");
	});

	if (isLocalJobPage) {
		$('#actionField').hide();
		$('#advancedActionField').hide();
		$('#matrixViewButton').hide();
		$('#jobPairAttributes').hide();
		$('#downloadJobPageButton').hide();
		$('#anonymousLink').hide();
		makeJobNameUneditable();
		makeJobDescriptionUneditable();
	} else {
		setupJobNameAndDescriptionEditing('#jobNameText',
			'#editJobName',
			'#editJobNameButton',
			'#editJobNameWrapper',
			'name');
		setupJobNameAndDescriptionEditing('#jobDescriptionText',
			'#editJobDescription',
			'#editJobDescriptionButton',
			'#editJobDescriptionWrapper',
			'description');
		registerAnonymousLinkButtonEventHandler();
		$(".stageSelector")
		.on("change", function() {
			//set the value of all .stageSelectors to this one to sync them.
			//this does not trigger the change event, which is good because it would loop forever
			$(".stageSelector").val($(this).val());
			pairTable.api().draw(false);
			refreshPanels();
			refreshStats(curSpaceId);
		});
		$("#selectSpaceOverview")
		.click(function() {
			$("#solverComparison300").hide();
			$("#pairTimeGraph").hide();
			$("#spaceOverview").show();
			$("#solverComparisonOptionField").hide();
			$("#pairTimeOptionField").hide();
			$("#spaceOverviewOptionField").show();
		});
                $("#selectSolverComparison")
                .click(function() {
                        $("#spaceOverview").hide();
                        $("#pairTimeGraph").hide();
                        $("#solverComparison300").show();
			$("#spaceOverviewOptionField").hide();
                        $("#pairTimeOptionField").hide();
                        $("#solverComparisonOptionField").show();
                        if (solverComparisonPendingLoad && isGraphPanelVisible()) {
                                solverComparisonPendingLoad = false;
                                updateSolverComparison();
                        }
                });
                $("#selectPairTimeGraph")
                .click(function() {
                        $("#spaceOverview").hide();
                        $("#solverComparison300").hide();
                        $("#pairTimeGraph").show();
			$("#spaceOverviewOptionField").hide();
			$("#solverComparisonOptionField").hide();
                        $("#pairTimeOptionField").show();
                });

		$("#spaceOverviewUpdate")
		.click(function() {
			updateSpaceOverviewGraph(true);
		});
		$("#solverComparisonUpdate")
		.click(function() {
			solverComparisonPendingLoad = false;
			updateSolverComparison(true);
		});
		$("#pairTimeUpdate")
		.click(function() {
			updatePairJobTimeGraph(true);
		});

		if (star.isUserSubscribedToJob !== undefined) {
			(function() {
				var $notificationButton = $("<a href='#' class='btn btn-secondary action-btn'>")
					.html("<span class='ui-icon ui-icon-mail-closed'></span> <span class='btn-text'></span>");
				var toggleSubscribe = function() {
					var url = starexecRoot + (
						star.isUserSubscribedToJob ?
						"services/jobs/notifications/unsubscribe" :
						"services/jobs/notifications/subscribe"
					);
					var status = !star.isUserSubscribedToJob;
					$.ajax({
						type: "POST",
						url: url,
						data: {"id": jobId},
						dataType: "json",
						success: function() {
							window.star.isUserSubscribedToJob = status;
							updateLabel();
							$notificationButton.removeClass("disabled").css("pointer-events", "");
						},
						error: function() {
							$notificationButton.removeClass("disabled").css("pointer-events", "");
							var message = "Unable to " +
								$notificationButton.find(".btn-text").text();
							showMessage("error", message, 5000);
						}
					});
				};
				var updateLabel = function() {
					var label = star.isUserSubscribedToJob ?
					            "unsubscribe from email updates" :
					            "subscribe to email updates";
					$notificationButton.find(".btn-text").text(label);
				};
				var notificationClick = function(e) {
					e.preventDefault();
					if ($notificationButton.hasClass("disabled")) return;
					$notificationButton.addClass("disabled").css("pointer-events", "none");
					toggleSubscribe();
				};

				updateLabel();
				$notificationButton
				.click(notificationClick)
				.wrapAll("<li>")
				.parent()
				.appendTo("#actionList")
				;
			})();
		}
	}

	setupSetHighPriorityButton();
	setupSetLowPriorityButton();
	setupDeleteJobButton();
	setupPauseJobButton();
	setupResumeJobButton();
	setupChangeQueueButton();
	setupPostProcessButton();
	attachSortButtonFunctions();


	//set the two default solvers to compare
	var defaultSolver1 = $('#solverChoice1').attr('default');
	$('#solverChoice1 option[value=' + defaultSolver1 + ']')
	.prop('selected', true);

	var defaultSolver2 = $('#solverChoice2').attr('default');
	$('#solverChoice2 option[value=' + defaultSolver2 + ']')
	.prop('selected', true);

	//set all fieldsets as expandable
	$('#solverSummaryField').expandable(false);
	$("#pairTblField").expandable(false);
	$("#graphField").expandable(false);
	$("#errorField").expandable(false);
	$("#statsErrorField").expandable(false);
	$("#optionField").expandable(true);
	$("#detailField").expandable(true);

	var advancedJobActionsCollapsed = $.cookie('advancedJobActions') != 'false';
	$('#advancedActionField')
	.expandable(advancedJobActionsCollapsed)
	.children('legend:first')
	.click(function() {
		var advancedJobActionsCollapsed = !$(this).data('open');
		$.cookie('advancedJobActions',
			advancedJobActionsCollapsed,
			{expires: 10000, path: '/'});
	});

	$("#subspaceSummaryField").expandable(false);

	lastValidSelectOption = $("#spaceOverviewSelections").val();

	$("#spaceOverviewSelections").on("change", function() {
		if ($(this).val().length > 5) {
			showMessage('error',
				"You may only choose a maximum of 5 solver / configuration pairs to display at one time",
				5000);
			$(this).val(lastValidSelectOption);
		} else {
			lastValidSelectOption = $(this).val();
		}
	});

	bindChartDialogTrigger("#solverComparison300", "#dialog-solverComparison", "#solverComparison800",
		"solverComparison", renderSolverComparisonChart, function() {
			return !suppressNextComparisonDialog;
		});

	bindChartDialogTrigger("#spaceOverview", "#dialog-spaceOverview", "#bigSpaceOverview",
		"spaceOverview", renderSpaceOverviewChart);

	bindChartDialogTrigger("#pairTimeGraph", "#dialog-pairTimeGraph", "#bigPairTimeGraph",
		"pairTime", renderPairTimeChart);

	setTimeButtonText();
}

function setupDeleteJobButton() {
	$('#deleteJob').click(function() {
		$('#dialog-confirm-delete-txt')
		.text('are you sure you want to delete this job?');

		$('#dialog-confirm-delete').dialog({
			modal: true,
			width: 380,
			buttons: {
				'OK': function() {
					log('user confirmed job deletion.');
					$('#dialog-confirm-delete').dialog('close');

					killAjaxRequests();
					window.stop();
					$.post(
						starexecRoot + "services/delete/job",
						{selectedIds: [getParameterByName("id")]},
						function(returnCode) {
							var s = parseReturnCode(returnCode);
							if (s) {
								var url = starexecRoot + 'secure/explore/spaces.jsp';
								var spaceId = typeof DETAILS_JOB !== 'undefined' && DETAILS_JOB.rootJobSpaceId;
								if (spaceId) {
									url += '?id=' + encodeURIComponent(spaceId);
								}
								window.location = url;
							}
						},
						"json"
					).fail(function() {
						showMessage("error", "Internal error deleting job", 5000);
					});
				},
				"cancel": function() {
					log('user canceled job deletion');
					$(this).dialog("close");
				}
			}
		});
	});
}

/* 
* this sets up everything related to getting stats for solvers with unknown satus. This 
* includes all the hash maps for state, and the buttons
*/
function setupEverythingForUnknownStatus(subspaces) {
	var button = $("#includeUnknown");
	if (!isLocalJobPage) {
		button.click(
			function () {
				includeUnknown = !includeUnknown;
				setUnknownButtonText();
				//go through all the subspace sumaries and refresh it
				refreshPanels();
				$("#solveTbl").DataTable().ajax.reload();
			}
		)
	}
	else {
		loadStatsIntoSolveTbl();
		button.click(
			function () {
				includeUnknown = !includeUnknown;
				setUnknownButtonText();
				refreshPanels();
				loadStatsIntoSolveTbl();
				}
				
			
		)
		
	}
	 

}

function setupSetHighPriorityButton() {
	var setHighPrioritySelector = '#setHighPriority';
	log('setting up high priority button.');
	$(setHighPrioritySelector).click(function() {
		log('set high priority button clicked.');
		$.post(
			starexecRoot + 'services/jobs/setHighPriority/' + jobId,
			'',
			function(statusCode) {
				log('got something back.');
				log(statusCode);
				if (statusCode.success) {
					log('reloading page');
					document.location.reload(true);
				} else {
					parseReturnCode(statusCode);
				}
			},
			'json'
		).fail(function() {
			showMessage("error", "Internal error setting high priority", 5000);
		});
	});
}

function setupSetLowPriorityButton() {
	var setLowPrioritySelector = '#setLowPriority';
	$(setLowPrioritySelector).click(function() {
		log('set low priority button clicked.');
		$.post(
			starexecRoot + 'services/jobs/setLowPriority/' + jobId,
			'',
			function(statusCode) {
				if (statusCode.success) {
					log('reloading page');
					document.location.reload(true);
				} else {
					parseReturnCode(statusCode);
				}
			},
			'json'
		).fail(function() {
			showMessage("error", "Internal error setting low priority", 5000);
		});
	});
}

function setupPauseJobButton() {
	$('#pauseJob').click(function() {
		var pauseJobUrl = starexecRoot + "services/pause/job/" + getParameterByName("id");
		var pauseJobRequestTimeoutMs = 30000;
		var pauseJobReloadDelayMs = 1000;

		createDialog("Pausing Job");
		killAjaxRequests(); // Since we are reloading the page anyway...
		window.stop();
		$.ajax({
			type: "POST",
			url: pauseJobUrl,
			dataType: "json",
			timeout: pauseJobRequestTimeoutMs
		}).done(function(returnCode) {
			var s = parseReturnCode(returnCode);
			if (s) {
				document.location.reload(true);
			} else {
				destroyDialog();
			}
		}).fail(function(xhr, textStatus, errorThrown) {
			log('pause job request failed: ' + textStatus + (errorThrown ? ' (' + errorThrown + ')' : ''));
			destroyDialog();

			if (textStatus === 'timeout') {
				showMessage('info',
					'Pause request timed out. Refreshing the page to check the latest job state.',
					5000);
				setTimeout(function() {
					document.location.reload(true);
				}, pauseJobReloadDelayMs);
				return;
			}

			showMessage('error', 'Pause request did not complete cleanly. Please refresh the page.', 5000);
		});
	});
}

function setupResumeJobButton() {
	$('#resumeJob').click(function() {
		createDialog("Resuming Job");
		killAjaxRequests(); // Since we are reloading the page anyway...
		window.stop();
		$.post(
			starexecRoot + "services/resume/job/" + getParameterByName("id"),
			function(returnCode) {
				var s = parseReturnCode(returnCode);
				if (s) {
					document.location.reload(true);
				} else {
					destroyDialog();
				}
			},
			"json"
		).fail(function() {
			destroyDialog();
			showMessage("error", "Internal error resuming job", 5000);
		});
	});
}

function setupChangeQueueButton() {
	$('#changeQueue').click(function() {
		$('#dialog-changeQueue-txt')
		.text('Please select a new queue to use for this job.');

		$('#dialog-changeQueue').dialog({
			modal: true,
			width: 380,
			buttons: {
				'OK': function() {
					$('#dialog-changeQueue').dialog('close');
					killAjaxRequests();
					window.stop();
					$.post(
						starexecRoot + "services/changeQueue/job/" + getParameterByName(
						"id") + "/" + $("#changeQueueSelection").val(),
						function(returnCode) {
							var s = parseReturnCode(returnCode);
							if (s) {
								setTimeout(
									function() {document.location.reload(true)},
									1000
								);
							}
						},
						"json"
					).fail(function() {
						showMessage("error", "Internal error changing job queue", 5000);
					});
				},
				"cancel": function() {
					$(this).dialog("close");
				}
			}
		});
	});
}

function setupPostProcessButton() {
	$("#postProcess").click(function() {
		$('#dialog-postProcess-txt')
		.text('Please select a post-processor to use for this job.');

		$('#dialog-postProcess').dialog({
			modal: true,
			width: 380,
			buttons: {
				'OK': function() {
					$('#dialog-postProcess').dialog('close');
					showMessage("info",
						"Beginning job pair processing. ",
						3000);
					$.post(
						starexecRoot + "services/postprocess/job/" + getParameterByName(
						"id") + "/" + $("#postProcessorSelection")
						.val() + "/" + getSelectedStage(),
						parseReturnCode,
						"json"
					).fail(function() {
						showMessage("error", "Internal error post-processing job", 5000);
					});
				},
				"cancel": function() {
					$(this).dialog("close");
				}
			}
		});
	});
}

// Parses structured JSON chart responses (or status refusals returned by the REST endpoints).
function parseGraphResponse(text) {
	if (text && typeof text === "object") {
		return text;
	}
	if (typeof text === "string" && text.charAt(0) === "{") {
		try {
			return JSON.parse(text);
		} catch (e) {
			return text;
		}
	}
	return text;
}

// Nothing aborts a graph request when the user asks for the same graph again, so two can be in
// flight and the older one can answer last. Only the latest request for a graph may draw it, clear
// it or say why it could not be drawn: an older answer would replace or clear the newer chart.
var latestGraphRequests = {};

// Starts a request for graph; the function returned tells whether it is still the latest one.
function startGraphRequest(graph) {
	var request = (latestGraphRequests[graph] || 0) + 1;
	latestGraphRequests[graph] = request;
	return function() {
		return latestGraphRequests[graph] === request;
	};
}

// An HTTP error never reaches the success callback, which left the previous chart up.
// Only a graph the user asked for (announce) says why it is missing: the page also draws the
// solver comparison by itself whenever its statistics load, and that draw must stay silent.
function onGraphRequestError(isLatest, clearGraph, announce) {
	return function(xhr, textStatus) {
		if (textStatus === "abort" || !isLatest()) {
			return;
		}
		clearGraph();
		if (announce) {
			showMessage("error", "The graph could not be drawn (HTTP " + xhr.status + ").", 5000);
		}
	};
}

function setChartState($container, state) {
	if (!$container || !$container.length) {
		return;
	}
	$container.removeClass("is-loading is-empty is-ready").addClass("is-" + state);
	var $stateImg = $container.find(".job-chart__state");
	if ($stateImg.length) {
		if (state === "loading") {
			$stateImg.attr("src", starexecRoot + "images/loadingGraph.svg");
		} else if (state === "empty") {
			$stateImg.attr("src", starexecRoot + "images/noDisplayGraph.svg");
		}
	}
}

function destroyChartForContainer($container) {
	if (!$container || !$container.length) {
		return;
	}
	var $canvas = $container.find("canvas");
	if (!$canvas.length) {
		return;
	}
	var canvasId = $canvas[0].id || $container[0].id;
	if (canvasId && jobChartInstances[canvasId]) {
		jobChartInstances[canvasId].destroy();
		delete jobChartInstances[canvasId];
	}
}

function renderChartIntoContainer(containerSelector, buildConfig, data, isDialog) {
	var $container = $(containerSelector);
	if (!$container.length) {
		return null;
	}
	if (!data) {
		destroyChartForContainer($container);
		setChartState($container, "empty");
		return null;
	}
	var config = buildConfig(data, isDialog === true);
	destroyChartForContainer($container);
	var $canvas = $container.find("canvas");
	var canvas = $canvas.length ? $canvas[0] : null;
	setChartState($container, "ready");
	if (typeof Chart === "undefined" || !canvas || typeof canvas.getContext !== "function") {
		return config;
	}
	var ctx = canvas.getContext("2d");
	var canvasId = canvas.id || containerSelector;
	jobChartInstances[canvasId] = new Chart(ctx, config);
	return jobChartInstances[canvasId];
}

function resetAllChartsToLoading() {
	latestChartData.pairTime = null;
	latestChartData.spaceOverview = null;
	latestChartData.solverComparison = null;
	destroyChartForContainer($("#spaceOverview"));
	destroyChartForContainer($("#bigSpaceOverview"));
	destroyChartForContainer($("#solverComparison300"));
	destroyChartForContainer($("#solverComparison800"));
	destroyChartForContainer($("#pairTimeGraph"));
	destroyChartForContainer($("#bigPairTimeGraph"));
	setChartState($("#spaceOverview"), "loading");
	setChartState($("#solverComparison300"), "loading");
	setChartState($("#pairTimeGraph"), "loading");
	setChartState($("#bigSpaceOverview"), "empty");
	setChartState($("#solverComparison800"), "empty");
	setChartState($("#bigPairTimeGraph"), "empty");
}

function bindChartDialogTrigger(triggerSelector, dialogSelector, bigContainerSelector, chartKey, renderFn, shouldOpenFn) {
	function openChartDialog() {
		if (typeof shouldOpenFn === "function" && !shouldOpenFn()) {
			return;
		}
		var cfg = getResponsiveDialogConfig(850);
		$(dialogSelector).dialog({
			modal: true,
			width: cfg.width,
			maxWidth: cfg.maxWidth,
			height: Math.min(820, cfg.maxHeight),
			maxHeight: cfg.maxHeight,
			open: function() {
				var cached = latestChartData[chartKey];
				if (cached) {
					renderFn(bigContainerSelector, cached, true);
				} else {
					destroyChartForContainer($(bigContainerSelector));
					setChartState($(bigContainerSelector), $(triggerSelector).hasClass("is-loading") ? "loading" : "empty");
				}
			}
		});
	}

	$(triggerSelector).click(openChartDialog).on("keydown", function(event) {
		if (event.key === "Enter" || event.key === " ") {
			event.preventDefault();
			openChartDialog();
		}
	});
}

function baseChartPlugins(titleText, showLegend, legendPosition) {
	return {
		title: {
			display: Boolean(titleText),
			text: titleText || "",
			color: JOB_CHART_THEME.text,
			font: { size: 14, weight: "600" }
		},
		legend: {
			display: Boolean(showLegend),
			position: legendPosition || "bottom",
			labels: {
				color: JOB_CHART_THEME.text,
				boxWidth: 12
			}
		},
		tooltip: {
			backgroundColor: JOB_CHART_THEME.tooltipBg,
			titleColor: JOB_CHART_THEME.text,
			bodyColor: JOB_CHART_THEME.text,
			borderColor: JOB_CHART_THEME.tooltipBorder,
			borderWidth: 1
		}
	};
}

function buildPairTimeChartConfig(data) {
	var points = Array.isArray(data.points) ? data.points : [];
	var plugins = baseChartPlugins(data.title || "# Pairs Completed vs Time", false);
	plugins.tooltip.callbacks = {
		title: function(items) {
			if (!items || !items.length) {
				return "";
			}
			var x = items[0].parsed.x;
			return typeof x === "number" ? new Date(x).toLocaleString() : "";
		},
		label: function(ctx) {
			return "Completed pairs: " + ctx.parsed.y;
		}
	};
	return {
		type: "line",
		data: {
			datasets: [{
				label: data.yLabel || "# of Completed Pairs",
				data: points,
				borderColor: JOB_CHART_THEME.accent,
				backgroundColor: JOB_CHART_THEME.accentFill,
				fill: true,
				tension: 0,
				borderWidth: 2,
				pointRadius: points.length > 100 ? 0 : 2.5,
				pointHoverRadius: 4,
				pointHitRadius: 6
			}]
		},
		options: {
			responsive: true,
			maintainAspectRatio: false,
			animation: false,
			color: JOB_CHART_THEME.text,
			plugins: plugins,
			scales: {
				x: {
					type: "linear",
					title: {
						display: true,
						text: data.xLabel || "Time",
						color: JOB_CHART_THEME.axis
					},
					ticks: { display: false },
					grid: { color: JOB_CHART_THEME.grid }
				},
				y: {
					type: "linear",
					min: 0,
					max: Math.max(1, data.totalPairs || points.length || 1),
					title: {
						display: true,
						text: data.yLabel || "# of Completed Pairs",
						color: JOB_CHART_THEME.axis
					},
					ticks: { color: JOB_CHART_THEME.axis },
					grid: { color: JOB_CHART_THEME.grid }
				}
			}
		}
	};
}

function renderPairTimeChart(containerSelector, data, isDialog) {
	return renderChartIntoContainer(containerSelector, buildPairTimeChartConfig, data, isDialog);
}

function buildSpaceOverviewChartConfig(data, isDialog) {
	var series = Array.isArray(data.series) ? data.series : [];
	var totalPoints = 0;
	for (var i = 0; i < series.length; i++) {
		totalPoints += Array.isArray(series[i].points) ? series[i].points.length : 0;
	}
	var pointRadius = totalPoints > 100 ? 0 : 2.5;
	var datasets = series.map(function(entry, idx) {
		var color = JOB_CHART_SERIES_COLORS[idx % JOB_CHART_SERIES_COLORS.length];
		return {
			label: entry.label || ("Series " + (idx + 1)),
			data: Array.isArray(entry.points) ? entry.points : [],
			borderColor: color,
			backgroundColor: color,
			fill: false,
			showLine: true,
			tension: 0,
			borderWidth: 2,
			pointRadius: pointRadius,
			pointHoverRadius: 4,
			pointHitRadius: 6
		};
	});
	var yScale = {
		type: data.logY ? "logarithmic" : "linear",
		title: {
			display: true,
			text: data.yLabel || "time (s)",
			color: JOB_CHART_THEME.axis
		},
		ticks: { color: JOB_CHART_THEME.axis },
		grid: { color: JOB_CHART_THEME.grid }
	};
	if (!data.logY) {
		yScale.min = 0;
		yScale.suggestedMax = data.maxY || 0.1;
	}
	var xScale = {
		type: data.logX ? "logarithmic" : "linear",
		title: {
			display: true,
			text: data.xLabel || "# solved",
			color: JOB_CHART_THEME.axis
		},
		ticks: { color: JOB_CHART_THEME.axis },
		grid: { color: JOB_CHART_THEME.grid }
	};
	if (!data.logX) {
		xScale.min = 0;
	}
	return {
		type: "line",
		data: { datasets: datasets },
		options: {
			responsive: true,
			maintainAspectRatio: false,
			animation: false,
			color: JOB_CHART_THEME.text,
			plugins: baseChartPlugins(data.title || "Space Overview Plot", true, isDialog ? "top" : "bottom"),
			scales: {
				x: xScale,
				y: yScale
			}
		}
	};
}

function renderSpaceOverviewChart(containerSelector, data, isDialog) {
	return renderChartIntoContainer(containerSelector, buildSpaceOverviewChartConfig, data, isDialog);
}

function getClickableBenchmarkId(point, data) {
	if (!data || data.benchmarksAnonymized || !point || point.benchmarkId === undefined || point.benchmarkId === null) {
		return null;
	}
	return point.benchmarkId;
}

function buildSolverComparisonChartConfig(data, isDialog) {
	var points = Array.isArray(data.points) ? data.points : [];
	var maxAxis = typeof data.maxAxis === "number" && data.maxAxis > 0 ? data.maxAxis : 0.1;
	var plugins = baseChartPlugins(data.title || "Solver Comparison Plot", false);
	plugins.tooltip.callbacks = {
		title: function(items) {
			if (!items || !items.length) {
				return "";
			}
			var raw = items[0].raw;
			return raw && raw.name ? raw.name : "";
		},
		label: function(ctx) {
			var lines = ["x: " + ctx.parsed.x + " s, y: " + ctx.parsed.y + " s"];
			if (getClickableBenchmarkId(ctx.raw, data) !== null) {
				lines.push("Click to open benchmark");
			}
			return lines;
		}
	};
	return {
		type: "scatter",
		data: {
			datasets: [{
				label: "Benchmarks",
				data: points,
				backgroundColor: JOB_CHART_THEME.scatterPointBg,
				borderColor: JOB_CHART_THEME.accent,
				borderWidth: 1,
				pointRadius: isDialog ? 4.5 : 3.5,
				pointHoverRadius: isDialog ? 7 : 6,
				pointHitRadius: 5
			}]
		},
		options: {
			responsive: true,
			maintainAspectRatio: true,
			aspectRatio: 1,
			animation: false,
			color: JOB_CHART_THEME.text,
			plugins: plugins,
			onHover: function(event, elements, chart) {
				if (!chart || !chart.canvas) {
					return;
				}
				var hasLink = false;
				if (elements && elements.length > 0) {
					var el = elements[0];
					var raw = chart.data.datasets[el.datasetIndex].data[el.index];
					hasLink = getClickableBenchmarkId(raw, data) !== null;
				}
				chart.canvas.style.cursor = hasLink ? "pointer" : (isDialog ? "default" : "zoom-in");
			},
			onClick: function(event, elements, chart) {
				if (!elements || !elements.length || !chart) {
					return;
				}
				var el = elements[0];
				var raw = chart.data.datasets[el.datasetIndex].data[el.index];
				var benchmarkId = getClickableBenchmarkId(raw, data);
				if (benchmarkId === null) {
					return;
				}
				suppressNextComparisonDialog = true;
				setTimeout(function() {
					suppressNextComparisonDialog = false;
				}, 0);
				if (event && event.native && typeof event.native.stopPropagation === "function") {
					event.native.stopPropagation();
				}
				window.location.assign(starexecRoot + "secure/details/benchmark.jsp?id=" + encodeURIComponent(benchmarkId));
			},
			scales: {
				x: {
					type: "linear",
					min: 0,
					max: maxAxis,
					title: {
						display: true,
						text: data.xLabel || "",
						color: JOB_CHART_THEME.axis
					},
					ticks: { color: JOB_CHART_THEME.axis },
					grid: { color: JOB_CHART_THEME.grid }
				},
				y: {
					type: "linear",
					min: 0,
					max: maxAxis,
					title: {
						display: true,
						text: data.yLabel || "",
						color: JOB_CHART_THEME.axis
					},
					ticks: { color: JOB_CHART_THEME.axis },
					grid: { color: JOB_CHART_THEME.grid }
				}
			}
		}
	};
}

function renderSolverComparisonChart(containerSelector, data, isDialog) {
	return renderChartIntoContainer(containerSelector, buildSolverComparisonChartConfig, data, isDialog);
}

function clearPairTimeGraph() {
	latestChartData.pairTime = null;
	destroyChartForContainer($("#pairTimeGraph"));
	destroyChartForContainer($("#bigPairTimeGraph"));
	setChartState($("#pairTimeGraph"), "empty");
	setChartState($("#bigPairTimeGraph"), "empty");
}

function clearSpaceOverviewGraph() {
	latestChartData.spaceOverview = null;
	destroyChartForContainer($("#spaceOverview"));
	destroyChartForContainer($("#bigSpaceOverview"));
	setChartState($("#spaceOverview"), "empty");
	setChartState($("#bigSpaceOverview"), "empty");
}

function clearSolverComparison() {
	latestChartData.solverComparison = null;
	destroyChartForContainer($("#solverComparison300"));
	destroyChartForContainer($("#solverComparison800"));
	setChartState($("#solverComparison300"), "empty");
	setChartState($("#solverComparison800"), "empty");
}

// announce: true when the user pressed Update, so that a graph that cannot be drawn says why.
function updatePairJobTimeGraph(announce) {
	var postUrl = null;

	if(DETAILS_JOB.isAnonymousPage) {
		postUrl = starexecRoot + 'services/jobs/anonymousLink/' + DETAILS_JOB.anonymousLinkUuid + '/' + jobId + '/graphs/pairTime';
	} else {
		postUrl = starexecRoot + 'services/jobs/' + jobId + '/graphs/pairTime';
	}
	log('updatePairJobTimeGraph postUrl: ' + postUrl);

	setChartState($("#pairTimeGraph"), "loading");
	var isLatest = startGraphRequest("pairTime");
	var xhr = $.post(
			postUrl,
			{},
			function(returnCode) {
				if (!isLatest()) {
					return;
				}
				var parsed = parseGraphResponse(returnCode);
				var s = parseReturnCode(parsed, announce === true);
				if (s && parsed && typeof parsed === "object" && parsed.chartType === "pairTime") {
					latestChartData.pairTime = parsed;
					renderPairTimeChart("#pairTimeGraph", parsed, false);
					if ($("#dialog-pairTimeGraph").is(":visible")) {
						renderPairTimeChart("#bigPairTimeGraph", parsed, true);
					}
				} else {
					clearPairTimeGraph();
				}
			},
			"text"
		);
		xhr.fail(onGraphRequestError(isLatest, clearPairTimeGraph, announce === true));
		openAjaxRequests.push(xhr);
}


// announce: true when the user pressed Update, so that a graph that cannot be drawn says why.
function updateSpaceOverviewGraph(announce) {
	var configs = [];
	var logY = false;
	$("#spaceOverviewSelections option:selected").each(function() {
		configs.push($(this).attr("value"));
	});
	if ($("#logScale").prop("checked")) {
		logY = true;
	}

	var postUrl = null;
	if (DETAILS_JOB.isAnonymousPage) {
		postUrl = starexecRoot + 'services/jobs/anonymousLink/' + DETAILS_JOB.anonymousLinkUuid + '/' + curSpaceId +
			'/graphs/spaceOverview/' + getSelectedStage() + '/' + DETAILS_JOB.primitivesToAnonymize;
	} else {
		postUrl = starexecRoot + 'services/jobs/' + curSpaceId + '/graphs/spaceOverview/' + getSelectedStage();
	}
	log('updateSpaceOverviewGraph postUrl: ' + postUrl);

	setChartState($("#spaceOverview"), "loading");
	var isLatest = startGraphRequest("spaceOverview");
	var xhr = $.post(
		postUrl,
		{logY: logY, selectedIds: configs},
		function(returnCode) {
			if (!isLatest()) {
				return;
			}
			var parsed = parseGraphResponse(returnCode);
			var s = parseReturnCode(parsed, announce === true);
			if (s && parsed && typeof parsed === "object" && parsed.chartType === "spaceOverview") {
				var currentConfigs = [];
				$("#spaceOverviewSelections option:selected").each(function() {
					currentConfigs.push($(this).attr("value"));
				});
				if (!(currentConfigs > configs) && !(currentConfigs < configs)) {
					latestChartData.spaceOverview = parsed;
					renderSpaceOverviewChart("#spaceOverview", parsed, false);
					if ($("#dialog-spaceOverview").is(":visible")) {
						renderSpaceOverviewChart("#bigSpaceOverview", parsed, true);
					}
				}
			} else {
				clearSpaceOverviewGraph();
			}
		},
		"text"
	);
	xhr.fail(onGraphRequestError(isLatest, clearSpaceOverviewGraph, announce === true));
	openAjaxRequests.push(xhr);
}

// announce: true when the user pressed Update, so that a comparison that cannot be drawn says why;
// the draw that follows each statistics load passes nothing and waits until #graphField is visible.
function updateSolverComparison(announce) {
	if (announce !== true && !isGraphPanelVisible()) {
		solverComparisonPendingLoad = true;
		return;
	}
	solverComparisonPendingLoad = false;
	var isLatest = startGraphRequest("solverComparison");
	var config1 = $("#solverChoice1 option:selected").attr("value");
	var config2 = $("#solverChoice2 option:selected").attr("value");

	var postUrl = '';
	if (DETAILS_JOB.isAnonymousPage) {
		postUrl = starexecRoot + "services/jobs/anonymousLink/" + DETAILS_JOB.anonymousLinkUuid + "/" + curSpaceId + "/graphs/solverComparison/" + config1 +
			"/" + config2 + "/" + getSelectedStage() + "/" + DETAILS_JOB.primitivesToAnonymize;
	} else {
		postUrl = starexecRoot + "services/jobs/" + curSpaceId + "/graphs/solverComparison/" + config1 + "/" + config2 + "/" + getSelectedStage();
	}

	setChartState($("#solverComparison300"), "loading");
	var xhr = $.post(
		postUrl,
		{},
		function(returnCode) {
			if (!isLatest()) {
				return;
			}
			var parsed = parseGraphResponse(returnCode);
			var s = parseReturnCode(parsed, announce === true);
			if (s && parsed && typeof parsed === "object" && parsed.chartType === "solverComparison") {
				latestChartData.solverComparison = parsed;
				renderSolverComparisonChart("#solverComparison300", parsed, false);
				if ($("#dialog-solverComparison").is(":visible")) {
					renderSolverComparisonChart("#solverComparison800", parsed, true);
				}
			} else {
				clearSolverComparison();
			}
		},
		"text"
	);

	xhr.fail(onGraphRequestError(isLatest, clearSolverComparison, announce === true));
	openAjaxRequests.push(xhr);
}

function setupJobNameAndDescriptionEditing(
	textSelector, inputSelector, buttonSelector, wrapperSelector,
	nameOrDescription) {
	var $text = $(textSelector);
	var $input = $(inputSelector);
	var $button = $(buttonSelector);
	var $wrapper = $(wrapperSelector);
	var saving = false;
	var isName = nameOrDescription === 'name';

	function closeEditor() {
		$wrapper.hide();
		$text.show();
	}

	function reportSaveError() {
		showMessage('error', 'Could not save the job ' + nameOrDescription + '. Try again.', 5000);
	}

	$wrapper.hide();
	$text.on('click', function() {
		$text.hide();
		$wrapper.show();
		$input.select();
	});

	// Keep pointer focus on the input until the Save click is dispatched.
	$button.on('mousedown', function(event) {
		if (event.button === 0) {
			event.preventDefault();
		}
	}).on('click', function(event) {
		event.preventDefault();
		if (saving) {
			return;
		}
		var value = $input.val();
		var pattern = new RegExp(isName ? getPrimNameRegex() : getPrimDescRegex());
		if (!pattern.test(value)) {
			showMessage('error', 'The given ' + nameOrDescription + ' contains illegal characters.', 5000);
			return;
		}
		var url = starexecRoot + 'services/job/edit/' + nameOrDescription + '/' + jobId;
		var data = {};
		if (isName) {
			url += '/' + encodeURIComponent(value);
		} else {
			data.description = value;
		}
		saving = true;
		$input.prop('readOnly', true);
		$button.prop('disabled', true);

		$.post(url, data, null, 'json').done(function(response) {
			if (!response || !parseReturnCode(response)) {
				if (!response || !response.message) {
					reportSaveError();
				}
				return;
			}
			$text.text(value);
			if (isName) {
				$('#mainTemplateHeader').text(value);
			}
			closeEditor();
		}).fail(reportSaveError).always(function() {
			$input.prop('readOnly', false);
			$button.prop('disabled', false);
			saving = false;
		});
	});

	$wrapper.on('focusout', function(event) {
		if (!saving && !this.contains(event.relatedTarget)) {
			closeEditor();
		}
	}).on('keydown', function(event) {
		if (!saving && event.key === 'Escape') {
			$input.val($text.text());
			closeEditor();
		}
	});
}

function openSpace(childId) {
	var tree = $("#exploreList").jstree(true);
	if (tree) {
		star.openTreePath(tree, [String(curSpaceId), String(childId)], function(id) {
			star.selectTreeNode(tree, id);
		});
	}
}

function getPanelTable(space) {
	var spaceName = space.attr("name");
	var spaceId = parseInt(space.attr("id"));
	return "<fieldset class=\"panelField\">" +
		"<legend class=\"panelHeader\">" + spaceName + "</legend>" +
		"<table id=panel" + spaceId + " spaceId=\"" + spaceId + "\" class=\"panel\"><thead>" +
		"<tr class=\"viewSubspace\"><th colspan=\"4\" >Go To Subspace</th></tr>" +
		"<tr><th class=\"solverHead\">solver</th><th class=\"configHead\">config</th> " +
		"<th class=\"solvedHead\" title=\"Number of job pairs for which the result matched the expected result, or those attributes are undefined, over the number of job pairs that completed without any system errors\">solved</th> " +
		"<th class=\"timeHead\" title=\"total wallclock or cpu time for all job pairs run that were solved correctly\">time</th> </tr>" +
		"</thead>" +
		"<tbody></tbody> </table></fieldset>";
}

/* 
* only set up everything once.
*/
function setupEverythingForUnknownStatusWrapper() {
	if (isLocalJobPage) {
		var panelJson = JSON.parse($("#subspacePanelJson" + curSpaceId)
		.attr("value"));
		setupEverythingForUnknownStatus(panelJson);

	} else if (DETAILS_JOB.isAnonymousPage) {
		$.getJSON(starexecRoot + "services/space/anonymousLink/" + DETAILS_JOB.anonymousLinkUuid + "/jobspaces/false/" + DETAILS_JOB.primitivesToAnonymize + "?id=" + curSpaceId,
		setupEverythingForUnknownStatus).fail(function() {
			showMessage("error", "Internal error loading subspace status", 5000);
		});
	} else {
		$.getJSON(starexecRoot + "services/space/" + jobId + "/jobspaces/false?id=" + curSpaceId,
		setupEverythingForUnknownStatus).fail(function() {
			showMessage("error", "Internal error loading subspace status", 5000);
		});
	}
}

function initializePanels() {
	var spaceId = curSpaceId;
	if (isLocalJobPage) {
		var panelJson = JSON.parse($("#subspacePanelJson" + spaceId).attr("value"));
		handleSpacesData(panelJson);
		return;
	}

	// Identify the request, not just the space: users can navigate A → B → A.
	var request = {};
	DETAILS_JOB.panelRequest = request;
	var baseUrl = starexecRoot + "services/space/";
	var url = DETAILS_JOB.isAnonymousPage
		? baseUrl + "anonymousLink/" + DETAILS_JOB.anonymousLinkUuid + "/jobspaces/false/" + DETAILS_JOB.primitivesToAnonymize
		: baseUrl + jobId + "/jobspaces/false";
	$.getJSON(url + "?id=" + spaceId, function(spaces) {
		if (DETAILS_JOB.panelRequest === request) {
			handleSpacesData(spaces);
		}
	}).fail(function(xhr, status) {
		if (DETAILS_JOB.panelRequest === request && status !== "abort") {
			showMessage("error", "Could not load the subspace summary.", 5000);
		}
	});
}

function handleSpacesData(spaces) {
	log("SPACES JSON: " + spaces);
	panelArray = [];
	if (spaces.length == 0) {
		$("#subspaceSummaryField").hide();
	} else {
		$("#subspaceSummaryField").show();
	}


	/* I'm so sorry, but because JavaScript is a Function scoped language, I
	 * need to wrap the body of this loop inside an anonymous function so that
	 * the variables we create are limited to the body of the loop.
	 * Yay for JavaScript!
	 */
	for (var i = 0; i < spaces.length; ++i) {
		(function() {
			var space = $(spaces[i]),
				spaceId = parseInt(space.attr("id")),
				child = getPanelTable(space);

			$("#panelActions").after(child); //put the table after the panelActions fieldset
			//if this is a local job page, the getPanelTableInitializer gets the json for the space and parses it.
			var panelTableInitializer = getPanelTableInitializer(jobId,
				spaceId);
			var $panel = $("#panel" + spaceId);
			//for each panel attach the following event handler to be executed when the fieldset is opened
			//then, immediately get rid of the event listener
			$panel.parents(".panelField").one("open.expandable", function() {
				var $this = $(this);
				panelArray.push($panel.dataTable(panelTableInitializer));

				//only reload the subspace summary if it's not a local job page
				if (!isLocalJobPage) {
					/* We want each panel to reload its contents every 30 seconds while
					* it is open. If it is closed, it should not reload its contents. Upon
					* being opened, it should immediately refresh its contents because we
					* don't know how long it has been closed and we do not want to show
					* stale data for 30 seconds.
					* We can keep track of the interval handle internally as
					* `panelRefreshInterval`, but we must also expose it to `clearPanels`.
					* For this reason, we will let jQuery save the handle also so that we
					* can clear the interval if the panel is cleared.
					*/
					var panelRefreshInterval;
					var reload = $panel.dataTable().api().ajax.reload;
					$this.on("open.expandable", function() {
						reload();
						panelRefreshInterval = window.setInterval(reload, 30000);
						$panel.data("panelRefreshInterval", panelRefreshInterval);
					}).on("close.expandable", function() {
						window.clearInterval(panelRefreshInterval);
					});
				}
			});
		})();
	}
	$(".viewSubspace").click(function() {
		var spaceId = $(this).parents("table.panel").attr("spaceId");
		openSpace(spaceId);
	});

	$(".panelField").expandable(true);
}

//
//  Initializes the DataTable objects
//
function initDataTables() {
	if (!isLocalJobPage) {
		extendDataTableFunctions();
	}
	//summary table

	var $pairTbl = $('#pairTbl'),
		$solveTbl = $("#solveTbl"),
		$compareSolvers = $("#compareSolvers");

	if (isLocalJobPage) {
		$('[id$=solveTbl]').dataTable(getSolverTableInitializer());
	} else {
		summaryTable = $solveTbl.dataTable(getSolverTableInitializer());
	}

	$solveTbl.on("mousedown", "tr", function() {
		var $this = $(this);

		if (!$this.hasClass("row_selected")) {
			$solveTbl.find(".second_selected")
			.removeClass("second_selected row_selected");
			$solveTbl.find(".first_selected")
			.removeClass("first_selected")
			.addClass("second_selected");
			$this.addClass("first_selected row_selected");
		} else {
			$this.removeClass("row_selected first_selected second_selected");
			$solveTbl.find(".second_selected")
			.removeClass("second_selected")
			.addClass("first_selected");
		}

		if ($solveTbl.find(".second_selected").length > 0) {
			$compareSolvers.show();
		} else {
			$compareSolvers.hide();
		}

		if (!isLocalJobPage && !DETAILS_JOB.isAnonymousPage) {
			var $primarySelection = $solveTbl.find(".first_selected").first();
			if ($primarySelection.length > 0) {
				applyPairFilterForSelectedSolver(summaryTable.api().row($primarySelection[0]).data() || null);
			} else {
				clearPairFilter(true);
			}
		}
	});

	// Job pairs table
	if (isLocalJobPage) {
		$('[id$=pairTbl]').dataTable(getPairTableInitializer());
	} else {
		pairTable = $("#pairTbl").dataTable(getPairTableInitializer());
	}

	setSortTable(pairTable);

	$pairTbl.find("thead").click(function() {
		resetSortButtons();
	});

	$('#detailTbl').dataTable({
		"dom": 'rt<"bottom"f><"clear">',
		"aaSorting": [],
		"paging": false,
		"bSort": true
	});

	$pairTbl.find("tbody").on("click", "a", function(event) {
		event.stopPropagation();
	});

	//Set up row click to send to pair details page
	if (!DETAILS_JOB.isAnonymousPage) {
		if (!isLocalJobPage) {
            //if its not a local job page, redirect to the server page.
            $pairTbl.find("tbody").on("click", "tr", function() {
                var pairId = $(this).find('input').val();
                window.location.assign(DETAILS_JOB.starexecUrl + "secure/details/pair.jsp?id=" + pairId);
            });
        }
		else {
			initOfflinePairNavigation();

		}}

	// Change the filter so that it only queries the server when the user stops typing
	if (!isLocalJobPage) {
		$pairTbl.dataTable().fnFilterOnDoneTyping();
	}
}

/* 
* loads the solver stats for the long form solverstats table
* @author aguo2
*/
function loadStatsIntoSolveTbl() {
	//get the datatable api
	var id = parseInt(curSpaceId);
	var $solveTbl = $("#solveTbl").DataTable()
	let json = JSON.parse($(getIDStringForJSON(id)).attr("value"));
	var newDataArray = parseStatsForLJP(json, false);
	$solveTbl.clear().rows.add(newDataArray).draw()
}

/*
* For the local job page, we have json that's embedded into the html.
* Given the space id, produce the string that gets the correct json.
*/
function getIDStringForJSON(id) {
	var str = "#solverStats"
	if (useWallclock) {
		str += "WallTime"
	}
	else {
		str += "CpuTime"
	}
	if (includeUnknown) {
		str += "IncludeUnknowns"
	}
	else {
		str += "ExcludeUnknowns"
	}
	str += id;
	return str; 
}

//
//Adds fnProcessingIndicator and fnFilterOnDoneTyping to dataTables api
//
function extendDataTableFunctions() {
	// Allows manually turning on and off of the processing indicator (used for jobs table)
	addProcessingIndicator();
	addFilterOnDoneTyping();
}

function getPairTableInitializer() {
	var pairTableInitializer = new window.star.DataTableConfig();

	if (!isLocalJobPage) {
		pairTableInitializer["sAjaxSource"] = starexecRoot + "services/jobs/";
		pairTableInitializer["bServerSide"] = true;
		pairTableInitializer["fnServerData"] = fnPaginationHandler;
	}
	return pairTableInitializer;
}

// see ./StarExec/src/org/starexec/app/RESTHelpers.java:convertSolverStatsToJsonObject()
function getSolverTableInitializer() {
	var SOLVER_ID = 0,
		CONFIG_ID = 1,
		SOLVER_NAME = 2,
		CONFIG_NAME = 3,
		SOLVED = 4,
		TIME = 5,
		STAGE = 6,
		WRONG = 7,
		RESOURCED = 8,
		FAILED = 9,
		UNKNOWN = 10,
		INCOMPLETE = 11,
		CONFLICTS = 12,
		CONFIG_DELETED = 13;

	var linkTemplate = document.createElement("a");
	linkTemplate.target = "_blank";

	var link = function(url, text) {
		linkTemplate.href = url;
		linkTemplate.textContent = text;
		return linkTemplate.outerHTML;
	};

	var pairsTemplate = [
		"pairsInSpace.jsp?type=",
		null,
		"&sid=",
		DETAILS_JOB.rootJobSpaceId,
		"&configid=",
		null,
		"&stagenum=",
		null
	];
	var getPairsInSpaceLink = function(type, configId, stageNumber) {
		pairsTemplate[1] = type;
		pairsTemplate[5] = configId;
		pairsTemplate[7] = stageNumber;
		return pairsTemplate.join("");
	};

	var conflictsTemplate = [
		"conflictingBenchmarks.jsp?jobId=",
		null,
		"&configId=",
		null,
		"&stageNumber=",
		null
	];
	var getConflictingLink = function(configId, stageNumber) {
		conflictsTemplate[1] = jobId;
		conflictsTemplate[3] = configId;
		conflictsTemplate[5] = stageNumber;
		return conflictsTemplate.join("");
	};

	var solverTemplate = ["solver.jsp?id=", null];
	var getSolverLink = function(solver) {
		solverTemplate[1] = solver;
		return solverTemplate.join("");
	};

// changed configTemplate so that href= is also null to start; now gets populated depending on
// if the config is marked as deleted or not in table configurations; Alexander Brown 9/2020
	var configTemplate = [
		"<a target='_blank' class='configLink' href='",
		null,
		"?id=",
		null,
		"' id='",
		null,
		"'>",
		null,
		"</a>"
	];

	var formatConfig = function(row, type, val) {
		var rawDeleted = val[CONFIG_DELETED];
		var isConfigDeleted = rawDeleted === true || rawDeleted === 1;
		if (typeof rawDeleted === "string") {
			var normalized = rawDeleted.toLowerCase();
			isConfigDeleted = isConfigDeleted || normalized === "1" || normalized === "true";
		}
		if (isConfigDeleted) {
			configTemplate[1] = "configDeleted.jsp";
		} else {
			configTemplate[1] = "configuration.jsp";
		}
		configTemplate[3] = val[CONFIG_ID];
		configTemplate[5] = val[CONFIG_ID];
		configTemplate[7] = val[CONFIG_NAME];
		return configTemplate.join("");
	};

	var hideLink = function(href, text) {return text;};
	var noOp = function() {}; // NOOP; no need to create URLs we won't use
	if (DETAILS_JOB.primitivesToAnonymize === "all"
		|| DETAILS_JOB.primitivesToAnonymize === "allButBench") {
		link = hideLink;
		getSolverLink = noOp;
		getPairsInSpaceLink = noOp;
		getConflictingLink = noOp;

		formatConfig = function(row, type, val) {
			return val[CONFIG_NAME];
		}
	}

	var formatSolver = function(row, type, val) {
		var href = getSolverLink(val[SOLVER_ID]);
		return link(href, val[SOLVER_NAME]);
	};
	var formatSolved = function(row, type, val) {
		var href = getPairsInSpaceLink("solved", val[CONFIG_ID], val[STAGE]);
		return link(href, val[SOLVED]);
	};
	var formatSolvedText = function(row, type, val) {
		return val[SOLVED];
	};
	var formatWrong = function(row, type, val) {
		var href = getPairsInSpaceLink("wrong", val[CONFIG_ID], val[STAGE]);
		return link(href, val[WRONG]);
	};
	var formatOut = function(row, type, val) {
		var href = getPairsInSpaceLink("resource", val[CONFIG_ID], val[STAGE]);
		return link(href, val[RESOURCED]);
	};
	var formatFailed = function(row, type, val) {
		var href = getPairsInSpaceLink("failed", val[CONFIG_ID], val[STAGE]);
		return link(href, val[FAILED]);
	};
	var formatUnknown = function(row, type, val) {
		var href = getPairsInSpaceLink("unknown", val[CONFIG_ID], val[STAGE]);
		return link(href, val[UNKNOWN]);
	};
	var formatIncomplete = function(row, type, val) {
		var href = getPairsInSpaceLink("incomplete",
			val[CONFIG_ID],
			val[STAGE]);
		return link(href, val[INCOMPLETE]);
	};
	var formatTime = function(row, type, val) {
		return (val[TIME] / 100).toFixed(1);
	};
	var formatConflicts = function(row, type, val) {
		var href = getConflictingLink(val[CONFIG_ID], val[STAGE]);
		return link(href, val[CONFLICTS]);
	};

	var panelTableInitializer;
	if (!isLocalJobPage) {
		panelTableInitializer = new window.star.DataTableConfig({
			"dom": 'rt<"clear">',
			"pageLength": 1000, // make sure we show every entry
			"aoColumns": [
				{"mRender": formatSolver},
				{"mRender": formatConfig},
				{"mRender": formatSolvedText},
				{"mRender": formatTime},
			]
		})
	}
	else {
		//if its a local job page, take out the links when rendering...
		
		formatSolver = function(row, type, val) {
			var href = getSolverLink(val[SOLVER_ID]);
			return val[SOLVER_NAME];
		};

		formatConfig = function(row, type, val) {
			return val[CONFIG_NAME];
		}
		panelTableInitializer = new window.star.DataTableConfig({
			"dom": 'rt<"clear">',
			"pageLength": 1000, // make sure we show every entry
			"aoColumns": [
				{"mRender": formatSolver},
				{"mRender": formatConfig},
				{"mRender": formatSolvedText},
				{"mRender": formatTime},
			]
		})
	}

	if (isLocalJobPage) {
		window.getPanelTableInitializer = function(jobId, spaceId) {
			return $.extend({},
				JSON.parse($(getIDStringForJSON(spaceId))
				.attr('value')),
				panelTableInitializer
			);
		}
	} else {
		var paginationUrlTemplate;
		if (DETAILS_JOB.isAnonymousPage) {
			paginationUrlTemplate = [
				starexecRoot + 'services/jobs/solvers/anonymousLink/pagination/',
				null,
				'/' + DETAILS_JOB.anonymousLinkUuid + '/' + DETAILS_JOB.primitivesToAnonymize + '/true/'
			];
		} else {
			paginationUrlTemplate = [
				starexecRoot + 'services/jobs/solvers/pagination/',
				null,
				'/true/'
			];
		}

		var getPaginationUrl = function(spaceId) {
			paginationUrlTemplate[1] = spaceId;
			return paginationUrlTemplate.join("");
		};

		panelTableInitializer["fnServerData"] = fnShortStatsPaginationHandler;
		window.getPanelTableInitializer = function(jobId, spaceId) {
			var data = $.extend({}, panelTableInitializer); // deep copy
			data["sAjaxSource"] = getPaginationUrl(spaceId);
			return data;
		}
	}

// solverTableInitializer is used to construct the solver summary page in the job space view
	var solverTableInitializer = new window.star.DataTableConfig({
		"bSort": true,
		"bPaginate": true,
		"aoColumns": [
			{"mRender": formatSolver},
			{"mRender": formatConfig},
			{"mRender": formatSolved},
			{"mRender": formatWrong},
			{"mRender": formatOut},
			{"mRender": formatFailed},
			{"mRender": formatUnknown},
			{"mRender": formatIncomplete},
			{"mRender": formatTime},
			{"mRender": formatConflicts},
		]
	});

	if (!isLocalJobPage) {
		solverTableInitializer["sAjaxSource"] = starexecRoot + "services/jobs/";
		solverTableInitializer["fnServerData"] = fnStatsPaginationHandler;
	}

	return solverTableInitializer;
}

function fnShortStatsPaginationHandler(sSource, aoData, fnCallback) {
	/* 
	I'm so sorry, but because the way that this is build, we need to parse the spaceid out of the url
	there is no other way to get it cleanly without having to rewrite all of it :(
	*/
	var spaceId = parseInt(sSource.split("/")[6]);
	var xhr = $.post(
		sSource + useWallclock + "/" + getSelectedStage() + "/" + includeUnknown,
		aoData,
		function(nextDataTablePage) {
			//if the user has clicked on a different space since this was called, we want those results, not these
			var s = parseReturnCode(nextDataTablePage);
			if (s) {
				fnCallback(nextDataTablePage);
			}
		},
		"json"
	).fail(function(code, textStatus) {
		handleAjaxError(textStatus);
	});

	openAjaxRequests.push(xhr);
}

function fnStatsPaginationHandler(sSource, aoData, fnCallback) {
	var echo;
	for (var i = 0; i < aoData.length; i++) {
		if (aoData[i].name === "sEcho") { echo = aoData[i].value; }
	}
	var request = {};
	fnStatsPaginationHandler.currentRequest = request;
	var space = curSpaceId, stage = getSelectedStage(), wallclock = useWallclock, unknown = includeUnknown;
	var settled = false;
	function finish(page) {
		if (settled) { return; }
		settled = true;
		fnCallback(page || {sEcho: echo, iTotalRecords: 0, iTotalDisplayRecords: 0, aaData: []});
	}
	function current() {
		return fnStatsPaginationHandler.currentRequest === request && space === curSpaceId &&
			stage === getSelectedStage() && wallclock === useWallclock && unknown === includeUnknown;
	}
	if (typeof space === "undefined") { finish(); return; }
	var postUrl;
	if (DETAILS_JOB.isAnonymousPage) {
		postUrl = sSource + "solvers/anonymousLink/pagination/" + space + "/" + getParameterByName("anonId") +
			"/" + DETAILS_JOB.primitivesToAnonymize + "/false/" + wallclock + "/" + stage + "/" + unknown;
	} else {
		postUrl = sSource + "solvers/pagination/" + space + "/false/" + wallclock + "/" + stage + "/" + unknown;
	}
	function validCount(value) {
		return typeof value === "number" && isFinite(value) && value >= 0 && Math.floor(value) === value;
	}
	var xhr = $.post(postUrl, aoData, function(page) {
		if (settled) { return; }
		if (!current()) { finish(); return; }
		if (page && typeof page === "object" && !parseReturnCode(page)) {
			$("#statsErrorField").hide();
			$("#graphField").hide();
			finish();
			return;
		}
		if (!page || typeof page !== "object" ||
			!Array.isArray(page.aaData) || !validCount(page.iTotalRecords) || !validCount(page.iTotalDisplayRecords)) {
			$("#statsErrorField").hide();
			$("#graphField").hide();
			handleAjaxError("invalid response");
			finish();
			return;
		}
		// Use this draw's echo, never an absent or unrelated server echo.
		page.sEcho = echo;
		$("#solverSummaryField").show();
		$("#graphField").show();
		$("#statsErrorField").hide();
		finish(page);
	}, "json").fail(function(code, textStatus) {
		if (settled) { return; }
		if (current() && textStatus !== "abort") {
			$("#statsErrorField").hide();
			$("#graphField").hide();
			handleAjaxError(textStatus);
		}
		finish();
	});
	openAjaxRequests.push(xhr);
}

//
//  Handles querying for pages in a given DataTable object
//
//  @param sSource the "sAjaxSource" of the calling table
//  @param aoData the parameters of the DataTable object to send to the server
//  @param fnCallback the function that actually maps the returned page to the DataTable object
//
function fnPaginationHandler(sSource, aoData, fnCallback) {
	if (typeof curSpaceId == 'undefined') {
		return;
	}
	if (sortOverride != null) {
		aoData.push({'name': 'sort_by', 'value': getSelectedSort()});
		aoData.push({'name': 'sort_dir', 'value': isASC()});
	}

	var postUrl = null;
	if (DETAILS_JOB.isAnonymousPage) {
		postUrl = sSource + 'pairs/pagination/anonymousLink/' + DETAILS_JOB.anonymousLinkUuid + '/' + curSpaceId +
			'/' + useWallclock + '/' + syncResults + '/' + getSelectedStage() + '/' + DETAILS_JOB.primitivesToAnonymize;
	} else if (isFilteringPairsBySolver()) {
		postUrl = sSource + 'pairs/solver/' + jobId + '/' + curSolverId + '/' + useWallclock + '/' + getSelectedStage();
	} else {
		postUrl = sSource + 'pairs/pagination/' + curSpaceId + '/' + useWallclock + '/' + syncResults + '/' + getSelectedStage();
	}

	var xhr = $.post(
		postUrl,
		aoData,
		function(nextDataTablePage) {
			var s = parseReturnCode(nextDataTablePage);
			if (s) {
				pairTable.fnProcessingIndicator(false);
				fnCallback(nextDataTablePage);
				$("#errorField").hide();
				if (pairTable.api().page.info().recordsTotal == 0) {
					$("#pairTblField").hide();
				} else {
					$("#pairTblField").show();
				}
			} else {
				//if we weren't successful, we need to check to see if it was because there are too many pairs
				var code = getStatusCode(nextDataTablePage);
				if (code == 1) {
					$("#pairTblField").hide();
					$("#errorField").show();
				}
			}
		},
		"json"
	).fail(function(code, textStatus) {
		handleAjaxError(textStatus);
	});

	openAjaxRequests.push(xhr);
}

function popup(url) {
	var win = window.open(url, '_blank');
	if (win) {
		// Browser allowed opening of popup.
		win.focus();
	}
}

function registerAnonymousLinkButtonEventHandler() {
	$('#anonymousLink').off('click');
	$('#anonymousLink').click(function() {
		$('#dialog-confirm-anonymous-link').text(
			"Do you want the job, benchmark, solver, and configuration names to be hidden on the linked page?");
		$('#dialog-confirm-anonymous-link').dialog({
			modal: true,
			width: 600,
			buttons: {
				'everything': function() {
					$(this).dialog('close');
					makeAnonymousLinkPost('job', jobId, 'all');
				},
				'everything except benchmarks': function() {
					$(this).dialog('close');
					makeAnonymousLinkPost('job', jobId, 'allButBench');
				},
				'nothing': function() {
					$(this).dialog('close');
					makeAnonymousLinkPost('job', jobId, 'none');
				}
			}
		});
	});
}
