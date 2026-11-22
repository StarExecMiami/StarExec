// Global object for explore/spaces. Future globals should go here.
var EXP_SP = {
	permissionSelection: {},
	permissionRequest: null,
	copySpaceDialog: '#dialog-confirm-space-copy',
	copySpaceDialogText: '#dialog-confirm-space-copy-txt',
	hierarchyDialogConfig: {
		open: function() {
			$('#hierarchy-action-container').removeClass('hidden');
			$('#applyActionInHierarchy').prop('checked', false);
		},
		close: function() {
			$('#hierarchy-action-container').addClass('hidden');
		}
	}
};

/** Global Variables */
var userTable;
var benchTable;
var solverTable;
var spaceTable;
var resultTable;
var jobTable;
var spaceId;      // id of the current space
var spaceName;    // name of the current space
var currentUserId;
var isLeafSpace = false;
var userIsDeveloper = false;
$(document).ready(function() {
	currentUserId = parseInt($("#userId").attr("value"));

	determineIfUserIsDeveloper();
	initSpacePermissionDialog();

	// Build left-hand side of page (space explorer)
	initSpaceExplorer();

	// Build right-hand side of page (space details)
	initSpaceDetails();
});

// Set the userIsDeveloper variable using a GET
function determineIfUserIsDeveloper() {
	'use strict';
	$.get(
		starexecRoot + 'services/users/isDeveloper',
		'',
		function(data) {
			userIsDeveloper = data;
		},
		'json'
	).fail(function() {
		userIsDeveloper = false;
	});
}

/**
 * Convenience method for determining if a given fieldset has been expanded or not
 *
 * @param fieldset the id of the fieldset to check for expansion (first letter must be a '#' symbol)
 * @returns {Boolean} true iff the given fieldset has been expanded
 * @author Todd Elvers
 */
function isFieldsetOpen(fieldset) {
	return $(fieldset + ' span:last-child').text() != ' (+)';
}

/**
 * Sets up the 'space details' that consumes the right-hand side of the page
 */
function initSpaceDetails() {
	// builds the DataTable objects and enables multi-select on them
	initDataTables();

	// Set up jQuery button UI
	initButtonUI();

	// This hides the action list if the space is root space or we aren't looking at a space
	if (spaceId == 1 || spaceId == undefined) {
		$('.actionList').hide();
	}

	pbc = false;
}

/**
 * Basic initialization for jQuery UI buttons (sets style and icons)
 */
function initButtonUI() {
	attachSortButtonFunctions();
	log('jQuery UI buttons initialized');
}

/**
 * Initializes a table so that elements can be dragged out of it and onto a space name
 * @param table The table to make draggable
 * @author Tyler Jensen & Todd Elvers
 */
function initDraggable(table) {
	makeTableDraggable(table, onDragStart, getDragClone);
	// Make the trash can in the explorer list be a droppable target
	$('#trashcan').droppable({
		drop: onTrashDrop,
		tolerance: 'touch',  // Use the pointer to determine drop position instead of the middle of the drag clone element
		hoverClass: 'hover',  // Class applied to the space element when something is being dragged over it
		activeClass: 'active'  // Class applied to the space element when something is being dragged
	});
	$("#trashcan").click(function() {
		window.location.href = starexecRoot + "secure/details/recycleBin.jsp";
	});
	//log($(table).attr('id') + ' table initialized as draggable');
}

/**
 * Called when any item is starting to be dragged within the browser
 */
function onDragStart(event, ui) {
	log('drag started');

	// Make each space in the explorer list be a droppable target; moving this from the initDraggable()
	// fixed the bug where spaces that were expanded after initDraggable() was called would not be
	// recognized as a viable drop target
	$('#exploreList').find('a').droppable({
		drop: onSpaceDrop,
		tolerance: 'pointer',// Use the pointer to determine drop position instead of the middle of the drag clone element
		activeClass: 'active'// Class applied to the space element when something is being dragged
	});
}

/**
 * Called when a draggable item (primitive) is dropped on the trash can
 * @author Todd Elvers
 */
function onTrashDrop(event, ui) {
	// Collect the selected elements from the table being dragged from
	var ids = getSelectedRows($(ui.draggable).parents('table:first'));
	ownsAll = userCanDeleteAll($(ui.draggable).parents('table:first'));
	if (ids.length < 2) {
		// If 0 or 1 things are selected in the table, just use the element that is being dragged
		ids = [ui.draggable.data('id')];
	}

	// Call the appropriate primitive removal function
	switch (ui.draggable.data('type')) {
		case "user":
			removeUsers(ids);
			break;
		case "solver":
			removeSolvers(ids, ownsAll);
			break;
		case "space":
			removeSubspaces(ids);//actual Remove called within here
			break;
		case "benchmark":
			removeBenchmarks(ids, ownsAll);
			break;
		case "job":
			removeJobs(ids, ownsAll);
			break;
	}
}

/**
 * Called when a draggable item (primitive) is dropped on a space
 */
function onSpaceDrop(event, ui) {
	// Collect the selected elements from the table being dragged from
	var ids = getSelectedRows($(ui.draggable).parents('table:first'));

	// Get the destination space id and name
	var destSpace = $(event.target).parent().attr('id');
	var destName = $(event.target).text();
	var destIsLeafSpace = spaceIsLeaf(destSpace);
	var allSpacesBeingCopiedAreLeaves = null;

	log(ids.length + ' rows dropped onto ' + destName);

	$("#hier-copy-options").on("change", function() {
		if ($(this).val() === "move") {
			$('.copy-primitives-options').hide();
		} else {
			$('.copy-primitives-options').show();
		}
	});

	if (ids.length < 2) {
		log("ids.length was < 2");
		// If 0 or 1 things are selected in the table, just use the element that is being dragged
		ids = [ui.draggable.data('id')];

		// Customize the confirmation message for the copy operation to the primitives/spaces involved
		switch (ui.draggable.data('type')) {
			case "space":
				allSpacesBeingCopiedAreLeaves = ids.every(spaceIsLeaf);
				$('#copy-primitives-options')
				.removeClass('copy-options-hidden');
				if (allSpacesBeingCopiedAreLeaves) {
					$("#hier-copy-options option[value='true']").attr("disabled","disabled");
					$("#hier-copy-options option[value='move']").text("move space");
				} else {
					$("#hier-copy-options option[value='true']").removeAttr("disabled");
					$("#hier-copy-options option[value='move']").text("move hierarchy");
				}
				$(EXP_SP.copySpaceDialogText).text(ui.draggable.data('name') + ' ⇒ ' + destName);
				$('#hier-copy-options').removeClass('copy-options-hidden');
				break;
			case "solver":
				if (destIsLeafSpace) {
					$('#dialog-confirm-copy-txt')
					.text('do you want to copy or link ' + ui.draggable.data(
						'name') + ' to' + destName + '?');

				} else {
					$('#dialog-confirm-copy-txt').text(
						'do you want to copy ' + ui.draggable.data('name') + ' to' + destName +
						' and all of its subspaces or just to' + destName + '?');
				}
				break;
			case "user":
				if (destIsLeafSpace) {
					$('#dialog-confirm-copy-txt')
					.text('do you want to copy ' + ui.draggable.data('name') + ' to' + destName + '?');

				} else {
					$('#dialog-confirm-copy-txt').text(
						'do you want to copy ' + ui.draggable.data('name') + ' to' + destName +
						' and all of its subspaces or just to' + destName + '?');
				}
				break;
			case "job":
				$('#dialog-confirm-copy-txt')
				.text('do you want to link ' + ui.draggable.data('name') + ' in' + destName + '?');
				break;
			default:
				$('#dialog-confirm-copy-txt')
				.text('do you want to copy or link ' + ui.draggable.data('name') + ' to' + destName + '?');
		}
	} else {
		switch (ui.draggable.data('type')) {
			case "space":
				$('#copy-primitives-options').removeClass('copy-options-hidden');
				allSpacesBeingCopiedAreLeaves = ids.every(spaceIsLeaf);
				if (allSpacesBeingCopiedAreLeaves) {
					$("#hier-copy-options option[value='true']").attr("disabled","disabled");
					$("#hier-copy-options option[value='move']").text("move spaces");
				} else {
					$("#hier-copy-options option[value='true']").removeAttr("disabled");
					$("#hier-copy-options option[value='move']").text("move hierarchies");
					$('#hier-copy-options').removeClass('copy-options-hidden');
				}
				$(EXP_SP.copySpaceDialogText).text(ids.length + ' spaces ⇒ ' + destName);
				break;
			case "solver":
			case "user":
				$('#dialog-confirm-copy-txt')
				.text('do you want to copy the ' + ids.length + ' selected ' + ui.draggable.data(
					'type') + 's to' + destName + ' and all of its subspaces or just to' + destName + '?');
				break;
			case "job":
				$('#dialog-confirm-copy-txt')
				.text('do you want to link the ' + ids.length + ' selected ' + ui.draggable.data(
					'type') + 's in' + destName + '?');
				break;
			default:
				$('#dialog-confirm-copy-txt')
				.text('do you want to copy or link the ' + ids.length + ' selected ' + ui.draggable.data(
					'type') + 's to' + destName + '?');
		}
	}

	switch (ui.draggable.data('type')) {
		case "solver":
			var solverCopyDialogButtons = {
				'link in space': function() {
					$('#dialog-confirm-copy').dialog('close');
					doSolverCopyPost(ids, destSpace, spaceId, false, false);
				},
				'copy to space': function() {
					$('#dialog-confirm-copy').dialog('close');
					doSolverCopyPost(ids, destSpace, spaceId, false, true);
				},
				"cancel": function() {
					$(this).dialog("close");
				}
			};
			if (!spaceIsLeaf(destSpace)) {
				solverCopyDialogButtons['link in space hierarchy'] = function() {
					$('#dialog-confirm-copy').dialog('close');
					doSolverCopyPost(ids, destSpace, spaceId, true, false);
				};
				solverCopyDialogButtons['copy to space hierarchy'] = function() {
					$('#dialog-confirm-copy').dialog('close');
					doSolverCopyPost(ids, destSpace, spaceId, true, true);
				};
			}
			// Display the confirmation dialog
			$('#dialog-confirm-copy').dialog({
				modal: true,
				width: 600,
				//depending on what the user
				buttons: solverCopyDialogButtons
			});
			break;
		case "user":
			setupUserCopyDialog(ids, destSpace, destName, ui, destIsLeafSpace);
			break;
		case "space":
			setupSpaceCopyDialog(ids, destSpace, destName);
			break;
		case "benchmark":
			// Display the confirmation dialog
			$('#dialog-confirm-copy').dialog({
				modal: true,
				buttons: {
					'copy': function() {
						// If the user actually confirms, close the dialog right away
						$('#dialog-confirm-copy').dialog('close');
						doBenchmarkCopyPost(ids,
							destSpace,
							spaceId,
							true,
							destName);
					},
					'link': function() {
						$('#dialog-confirm-copy').dialog('close');
						doBenchmarkCopyPost(ids,
							destSpace,
							spaceId,
							false,
							destName);
					},
					"cancel": function() {
						$(this).dialog("close");
					}
				}
			});
			break;
		default:
			// Display the confirmation dialog
			$('#dialog-confirm-copy').dialog({
				modal: true,
				width: 500,
				buttons: {
					'yes': function() {
						log('user confirmed copy action');
						// If the user actually confirms, close the dialog right away
						$('#dialog-confirm-copy').dialog('close');
						// Make the request to the server
						$.post(
							starexecRoot + 'services/spaces/' + destSpace + '/add/job',
							{selectedIds: ids, fromSpace: spaceId},
							function(returnCode) {
								parseReturnCode(returnCode);
							},
							"json"
						).fail(function() {
							showMessage('error',
								"Internal error copying jobs",
								5000);
						});
					},
					"cancel": function() {
						log('user canceled copy action');
						$(this).dialog("close");
					}
				}
			});
	}
}

function displayOrHideSampleRate() {
	var sampleBenchmarksValue = 'NO_JOBS_LINK_SOLVERS_SAMPLE_BENCHMARKS';
	if ($('#copyPrimitives')
		.find(':selected')
		.val() === sampleBenchmarksValue) {
		$('#sampleRateDiv').show();
	} else {
		$('#sampleRateDiv').hide();
	}
}

function setupSpaceCopyDialog(ids, destSpace, destName) {
	'use strict';

	displayOrHideSampleRate();
	$('#copyPrimitives').on("change", function() {
		displayOrHideSampleRate();
	});

	// True if every space in ids is a leaf.
	var allSpacesBeingCopiedAreLeaves = ids.every(spaceIsLeaf);

	var spaceCopyDialogButtons = {};

	spaceCopyDialogButtons['confirm'] = function() {
		var copyHierOption = $("#hier-copy-options").find(":selected").val();
		$(EXP_SP.copySpaceDialog).dialog('close');
		if (copyHierOption === "move") {
			return doSpaceMovePost(ids, destSpace);
		} else if (allSpacesBeingCopiedAreLeaves) {
			copyHierOption = false;
			log('Copying single space');
		} else {
			log('copyHierOption: ' + copyHierOption);
			$('#hier-copy-options').addClass('copy-options-hidden');
		}
		doSpaceCopyPost(ids, destSpace, copyHierOption, destName);
	};

	spaceCopyDialogButtons['cancel'] = function() {
		log('user canceled copy action');
		$('#hier-copy-options').addClass('copy-options-hidden');
		$('#copy-primitives-options').addClass('copy-options-hidden');
		$(this).dialog('close');
	};

	// Display the confirmation dialog
	$(EXP_SP.copySpaceDialog).dialog({
		modal: true,
		width: 700,
		buttons: spaceCopyDialogButtons
	});
}

function setupUserCopyDialog(ids, destSpace, destName, ui, destIsLeafSpace) {
	var userCopyDialogButtons = {};
	if (!destIsLeafSpace) {
		userCopyDialogButtons['space hierarchy'] = function() {
			// If the user actually confirms, close the dialog right away
			$('#dialog-confirm-copy').dialog('close');
			// Make the request to the server
			doUserCopyPost(ids, destSpace, true, destName, ui);
		};
		userCopyDialogButtons['space'] = function() {
			// If the user actually confirms, close the dialog right away
			$('#dialog-confirm-copy').dialog('close');
			doUserCopyPost(ids, destSpace, false, destName, ui);
		};
	} else {
		userCopyDialogButtons['confirm'] = function() {
			// If the user actually confirms, close the dialog right away
			$('#dialog-confirm-copy').dialog('close');
			doUserCopyPost(ids, destSpace, false, destName, ui);
		};
	}
	userCopyDialogButtons["cancel"] = function() {
		log('user canceled copy action');
		$(this).dialog("close");
	};
	$('#dialog-confirm-copy').dialog({
		modal: true,
		width: 500,
		buttons: userCopyDialogButtons
	});
}

function doSpaceCopyPost(ids, destSpace, copyHierarchy, destName) {
	//var copyPrimitives = $("input[type='radio'][name='copyPrimitives']:checked").val();
	var copyPrimitives = $("#copyPrimitives").find(":selected").val();
	log('copyPrimitives: ' + copyPrimitives);
	log('copyHierarchy: ' + copyHierarchy);
	var sampleRate = $('#sampleRate').val();
	log('Sample rate is: ' + sampleRate);
	$('#copy-primitives-options').addClass('copy-options-hidden');
	$.post(
		starexecRoot + 'services/spaces/' + destSpace + '/copySpace',
		{
			selectedIds: ids,
			copyHierarchy: copyHierarchy,
			copyPrimitives: copyPrimitives,
			sampleRate: sampleRate
		},
		function(returnCode) {
			s = parseReturnCode(returnCode);
			if (s) {
				$('#exploreList').jstree("refresh");
			}
		},
		"json"
	).fail(function() {
		showMessage('error', "Internal error copying spaces", 5000);
	});
}

function doSpaceMovePost(ids, destSpace) {
	$.post(
		starexecRoot + "services/move/space",
		{"selectedIds": ids, "parent": destSpace},
		function(returnCode) {
			if (parseReturnCode(returnCode)) {
				$("#exploreList").jstree("refresh");
			}
		},
		"json"
	).fail(function() {
		showMessage("error", "Internal error moving spaces", 5000);
	});
}

function doUserCopyPost(ids, destSpace, copyToSubspaces, destName, ui) {
	$.post(
		starexecRoot + 'services/spaces/' + destSpace + '/add/user',
		{selectedIds: ids, copyToSubspaces: copyToSubspaces},
		function(returnCode) {
			parseReturnCode(returnCode);
		},
		"json"
	).fail(function() {
		showMessage('error', "Internal error copying users", 5000);
	});
}

//adds the space id to the url as a parameter
function setURL(i) {
	current = window.location.pathname;
	newURL = current.substring(0, current.indexOf("?"));
	window.history.replaceState("object or string", "", newURL + "?id=" + i);
}

/**
 * Sends a copy benchmark request to the server
 * @param ids The IDs of the benchmarks to copy
 * @param destSpace The ID of the destination space
 * @param spaceId The ID of the from space
 * @param copy A boolean indicating whether to copy (true) or link (false).
 * @param destName The name of the destination space
 * @author Eric Burns
 */

function doBenchmarkCopyPost(ids, destSpace, spaceId, copy, destName) {
	// Make the request to the server

	$.post(
		starexecRoot + 'services/spaces/' + destSpace + '/add/benchmark', // We use the type to denote copying a benchmark/job
		{selectedIds: ids, fromSpace: spaceId, copy: copy},
		function(returnCode) {
			parseReturnCode(returnCode);
		},
		"json"
	).fail(function() {
		showMessage('error', "Internal error copying benchmarks", 5000);
	});
}

/**
 * Sends a copy solver request to the server
 * @param ids The IDs of the solvers to copy
 * @param destSpace The ID of the destination space
 * @param spaceId The ID of the from space
 * @param copy A boolean indicating whether to copy (true) or link (false).
 * @author Eric Burns
 */

function doSolverCopyPost(ids, destSpace, spaceId, hierarchy, copy) {
	// Make the request to the server
	$.post(
		starexecRoot + 'services/spaces/' + destSpace + '/add/solver',
		{
			selectedIds: ids,
			fromSpace: spaceId,
			copyToSubspaces: hierarchy,
			copy: copy
		},
		function(returnCode) {
			parseReturnCode(returnCode);
		},
		"json"
	).fail(function() {
		showMessage('error', "Internal error copying solvers", 5000);
	});
}

function spaceIsLeaf(spaceId) {
	var tree = $('#exploreList').jstree(true);
	return Boolean(tree && tree.is_leaf(String(spaceId)));
}

/**
 * Creates the space explorer tree for the left-hand side of the page.
 * @author Tyler Jensen & Todd Elvers & Skylar Stark
 */
function initSpaceExplorer() {
	makeSpaceTree("#exploreList", {
		stateKey: "spaces",
		chain: "#spaceChain",
		select: function(node, tree) {
			var id = node.id;
			/**
			 * The URL linking directly to this space
			 * @const {string}
			 */
			var permalink = "?id=" + String(id);

			// Update global variable `isLeafSpace`
			window.isLeafSpace = tree.is_leaf(node);
			log('Selected space isLeafSpace=' + isLeafSpace);
			log('Space explorer node ' + id + ' was clicked');

			EXP_SP.permissionSelection = {};
			$('#space-permission-dialog').dialog('close');
			$('#users .space-permission-trigger').remove();
			updateButtonIds(id);
			getSpaceDetails(id);

			// Replace the current URL with a URL linking directly to this space
			// We replace rather than push so the Back button still navigates away
			//   from the Space Explorer
			window.history.replaceState(null, "Space Explorer", permalink);
		}
	});

	var $copyPrimitivesOptions = $("#copy-primitives-options");
	$("#hier-copy-options").on("change", function(e) {
		switch (e.target.value) {
		case "move":
			$copyPrimitivesOptions.hide();
			break;
		default:
			$copyPrimitivesOptions.show();
		}
	});
	log('Space explorer node list initialized');
}





/**
 * Handles removal of benchmark(s) from a space
 * @author Todd Elvers
 */
function removeBenchmarks(selectedBenches, ownsAll) {
	$('#dialog-confirm-delete-txt')
	.text('How do you want to remove the selected benchmark(s) from ' 
		+ spaceName 
		+ '? Note that you cannot move benchmarks to your trash if you do not own them all');

	// Display the confirmation dialog
	$('#dialog-confirm-delete').dialog({
		modal: true,
		width: 800,
		open: EXP_SP.hierarchyDialogConfig.open,
		close: EXP_SP.hierarchyDialogConfig.close,
		buttons: [
			{
				'text': "Unlink from space",
				'click': function() {
					if($('#applyActionInHierarchy').is(':checked')){
						alert("Unlinking benchmarks in hierarchy is not supported yet.")
					}
					else{
						log('user confirmed benchmark removal');
						// If the user actually confirms, close the dialog right away
						$('#dialog-confirm-delete').dialog('close');
	
						$.post(
							starexecRoot + "services/remove/benchmark/" + spaceId,
							{selectedIds: selectedBenches},
							function(returnCode) {
								s = parseReturnCode(returnCode);
								if (s) {
									updateTable(benchTable);
								}
							},
							"json"
						).fail(function() {
							showMessage('error',
								"Internal error removing benchmarks",
								5000);
						});
					}
				}
			},
			{
				'text': "Move to Trash Bin",
				'disabled': !ownsAll,
				'click': function() {
					if($('#applyActionInHierarchy').is(':checked')){
						alert("Moving benchmarks to trash hierarchically is not supported yet.")
					}
					else{
						// If the user actually confirms, close the dialog right away
						$('#dialog-confirm-delete').dialog('close');
	
						$.post(
							starexecRoot + "services/recycleandremove/benchmark/" + spaceId,
							{selectedIds: selectedBenches},
							function(returnCode) {
								s = parseReturnCode(returnCode);
								if (s) {
									updateTable(benchTable);
								}
							},
							"json"
						).fail(function() {
							showMessage('error',
								"Internal error removing benchmarks",
								5000);
						});
					}
				}
			},
			{
				'text': "Delete permanently",
				'disabled': true
			},
			{
				'text': "Cancel",
				"click": function() {
					log('user canceled benchmark deletion');
					$(this).dialog("close");
				}
			}
		]
	});
}

function removeUsersFromSpace(selectedUsers) {
	log('user confirmed user deletion');
	// If the user actually confirms, close the dialog right away
	$('#dialog-confirm-delete').dialog('close');

	$.post(
		starexecRoot + "services/remove/user/" + spaceId,
		{selectedIds: selectedUsers, hierarchy: false},
		function(returnCode) {
			s = parseReturnCode(returnCode);
			if (s) {
				updateTable(userTable);
			}
		},
		"json"
	).fail(function() {
		showMessage('error', "Internal error removing users", 5000);
	});
}

/**
 * Handles removal of user(s) from a space
 * @author Todd Elvers & Skylar Stark
 */
function removeUsers(selectedUsers) {
	$('#dialog-confirm-delete-txt').text("How do you want to remove the user(s) from " + spaceName + "?");

	// Display the confirmation dialog
	$('#dialog-confirm-delete').dialog({
		modal: true,
		width: 800,
		open: EXP_SP.hierarchyDialogConfig.open,
		close: EXP_SP.hierarchyDialogConfig.close,
		buttons: [
			{
				'text': "Unlink from space",
				'click': function(){
					if($('#applyActionInHierarchy').is(':checked')){
						log('user confirmed user deletion from space and its hierarchy');
						// If the user actually confirms, close the dialog right away
						$('#dialog-confirm-delete').dialog('close');
		
						$.post(
							starexecRoot + "services/remove/user/" + spaceId,
							{selectedIds: selectedUsers, hierarchy: true},
							function(returnCode) {
								s = parseReturnCode(returnCode);
								if (s) {
									updateTable(userTable);
								}
							},
							"json"
						).fail(function() {
							showMessage('error', "Internal error removing users", 5000);
						});
					}
					else{
						removeUsersFromSpace(selectedUsers);
					}
				}
			},
			{
				'text': "Move to Trash Bin",
				'disabled': true
			},
			{
				'text': "Delete Permanently",
				'disabled': true
			},
			{
				'text': "Cancel",
				'click': function(){$('#dialog-confirm-delete').dialog('close');}
			},
		]
	});
}

function removeSolversFromSpaceHierarchy(selectedSolvers) {
	log('user confirmed solver removal from space and its hierarchy');
	// If the user actually confirms, close the dialog right away
	$('#dialog-confirm-delete').dialog('close');

	$.post(
		starexecRoot + "services/remove/solver/" + spaceId,
		{selectedIds: selectedSolvers, hierarchy: true},
		function(returnCode) {
			s = parseReturnCode(returnCode);
			if (s) {
				updateTable(solverTable);
			}
		},
		"json"
	).fail(function() {
		showMessage('error', "Internal error removing solvers", 5000);
	});
}

function removeSolversFromSpace(selectedSolvers) {
	log('user confirmed solver removal');
	// If the user actually confirms, close the dialog right away
	$('#dialog-confirm-delete').dialog('close');

	$.post(
		starexecRoot + "services/remove/solver/" + spaceId,
		{selectedIds: selectedSolvers, hierarchy: false},
		function(returnCode) {
			s = parseReturnCode(returnCode);
			if (s) {
				updateTable(solverTable);
			}
		},
		"json"
	).fail(function() {
		showMessage('error', "Internal error removing solvers", 5000);
	});
}

function moveSolversToRecycleBin(selectedSolvers) {
	// If the user actually confirms, close the dialog right away
	$('#dialog-confirm-delete').dialog('close');

	$.post(
		starexecRoot + "services/recycleandremove/solver/" + spaceId,
		{selectedIds: selectedSolvers, hierarchy: true},
		function(returnCode) {
			s = parseReturnCode(returnCode);
			if (s) {
				updateTable(solverTable);
			}
		},
		"json"
	).fail(function() {
		showMessage('error', "Internal error removing solvers", 5000);
	});
}

/**
 * Handles removal of solver(s) from a space
 * @author Todd Elvers & Skylar Stark
 */
function removeSolvers(selectedSolvers, ownsAll) {
	$('#dialog-confirm-delete-txt').text("How do you want to remove the solver(s) from " + spaceName + "?");

	// Display the confirmation dialog
	$('#dialog-confirm-delete').dialog({
		modal: true,
		width: 800,
		open: EXP_SP.hierarchyDialogConfig.open,
		close: EXP_SP.hierarchyDialogConfig.close,
		buttons: [
			{
				'text': "Unlink from space",
				'click': function() {
					if($('#applyActionInHierarchy').is(':checked')){
						removeSolversFromSpaceHierarchy(selectedSolvers);
					}
					else{
						removeSolversFromSpace(selectedSolvers);
					}
				}
			},
			{
				'text': "Move to Trash Bin",
				'disabled': !ownsAll,
				'click': function() {
					if($('#applyActionInHierarchy').is(':checked')){
						// alert('Moving solvers to trash hierarchically is not supported yet.');
						moveSolversToRecycleBin(selectedSolvers);
						removeSolversFromSpaceHierarchy(selectedSolvers);
					}
					else{
						moveSolversToRecycleBin(selectedSolvers);
					}
				}
			},
			{
				'text': "Delete Permanently",
				'disabled': true
			},
			{
				'text': "Cancel",
				'click': function(){$(this).dialog("close");}
			},
		]
	});
}

var pendingJobRemovals = 0;

function requestJobRemoval(action, selectedJobs) {
	var status = $('#job-removal-status');
	var selection = EXP_SP.permissionSelection;
	pendingJobRemovals++;
	status.text('Processing job removal…');
	$('#dialog-confirm-delete').dialog('close');

	$.post(starexecRoot + 'services/' + action + '/job/' + spaceId,
		{selectedIds: selectedJobs}, null, 'json')
	.always(function() {
		pendingJobRemovals--;
		if (!pendingJobRemovals) {
			status.empty();
		}
	})
	.done(function(returnCode) {
		if (parseReturnCode(returnCode) && EXP_SP.permissionSelection === selection) {
			updateTable(jobTable);
		}
	})
	.fail(function() {
		showMessage('error', 'Internal error removing jobs', 5000);
	});
}

/**
 * Handles removal of job(s) from a space
 * @author Todd Elvers
 */
function removeJobs(selectedJobs, ownsAll) {
	$('#dialog-confirm-delete-txt')
	.text('How do you want to remove the selected job(s) from ' 
		+ spaceName 
		+ '? Note that you cannot delete them permanently if you do not own them all.');

	// Display the confirmation dialog
	$('#dialog-confirm-delete').dialog({
		modal: true,
		width: 800,
		open: EXP_SP.hierarchyDialogConfig.open,
		close: EXP_SP.hierarchyDialogConfig.close,
		buttons: [
			{
				'text': "Unlink from space",
				'click': function() {
					if($('#applyActionInHierarchy').is(':checked')){
						alert("Unlinking jobs hierarchically is not supported yet.")
					}
					else{
						requestJobRemoval('remove', selectedJobs);
					}
				}
			},
			{
				'text': "Move to Trash",
				'disabled': true
			},
			{
				'text': "Delete Permanently",
				'disabled': !ownsAll,
				'click': function() {
					if($('#applyActionInHierarchy').is(':checked')){
						alert("Permanently deleting jobs hierarchically is not supported yet.")
					}
					else{
						requestJobRemoval('deleteandremove', selectedJobs);
					}
				}
			},
			{
				'text': "Cancel",
				"click": function() {
					log('user canceled job deletion');
					$(this).dialog("close");
				}
			}
		]
	});
}

/**
 * Handles removal of subspace(s) from a space
 * @author Todd Elvers
 */
function removeSubspaces(selectedSubspaces) {
	$('#dialog-confirm-delete-txt')
	.text('Do you want to move the solvers and benchmarks to the trash, and delete the jobs in the selected subspace(s), and all their subspaces, or do you only want to remove the selected subspace(s) from ' + spaceName + '?'); // Display the confirmation dialog
	$('#dialog-confirm-delete').dialog({
		modal: true,
		width: 800,
		buttons: {
			"Remove subspace(s) only": function() {
				log('user confirmed subspace deletion');
				// If the user actually confirms, close the dialog right away
				$('#dialog-confirm-delete').dialog('close');
				makeRemoveSubspacesPost(selectedSubspaces, false);
			},
			'Remove subspace(s), and move primitives to trash': function() {
				log('user confirmed subspace deletion');
				// If the user actually confirms, close the dialog right away
				$('#dialog-confirm-delete').dialog('close');
				makeRemoveSubspacesPost(selectedSubspaces, true);
			},
			"Cancel": function() {
				log('user canceled subspace deletion');
				$(this).dialog("close");
			}
		}
	});
}

function makeRemoveSubspacesPost(selectedSubspaces, recyclePrims) {
	$.post(
		starexecRoot + "services/remove/subspace",
		{selectedIds: selectedSubspaces, recyclePrims: recyclePrims},
		function(result) {
			if (parseReturnCode(result)) {
				$('#exploreList').jstree('refresh');
			}
		},
		"json"
	).fail(function() {
		log('remove subspace error');
	});
}

// DataTables supplies the draw number and paging parameters. The endpoint still
// speaks the legacy pagination contract shared by the explorer tables.
function explorerTableAjax(tableName) {
	var latestDraw;
	var refreshTimer;
	return function(request, draw) {
		var selection = EXP_SP.permissionSelection;
		var tree = $('#exploreList').jstree(true);
		var selectedSpace = String(tree && tree.get_selected()[0] || '1');
		var order = request.order[0];
		latestDraw = request.draw;
		if (tableName === 'jobs') {
			window.clearTimeout(refreshTimer);
		}
		var data = {
			sEcho: request.draw,
			iDisplayStart: request.start,
			iDisplayLength: request.length,
			sSearch: request.search.value
		};
		// The server sorts unsorted job requests by creation time.
		if (order || tableName !== 'jobs') {
			data.iSortCol_0 = order ? order.column : 0;
			data.sSortDir_0 = order ? order.dir : 'asc';
		}
		if (tableName === 'benchmarks' && sortOverride != null) {
			data.sort_by = getSelectedSort();
			data.sort_dir = isASC();
		}

		function isCurrent() {
			return latestDraw === request.draw && EXP_SP.permissionSelection === selection;
		}

		function showLoadError() {
			if (isCurrent()) {
				draw({draw: request.draw, recordsTotal: 0, recordsFiltered: 0, data: []});
				showMessage('error', 'Could not load ' + tableName + ' for this space.', 5000);
			}
		}

		return $.ajax({
			type: 'POST',
			url: starexecRoot + 'services/space/' + selectedSpace + '/' + tableName + '/pagination',
			dataType: 'json',
			data: data
		}).done(function(page) {
			if (!isCurrent()) {
				return;
			}
			if (!page || !parseReturnCode(page, false) || page.sEcho !== request.draw
				|| !Array.isArray(page.aaData)) {
				showLoadError();
				return;
			}
			draw({
				draw: page.sEcho,
				recordsTotal: page.iTotalRecords,
				recordsFiltered: page.iTotalDisplayRecords,
				data: page.aaData
			});
			if (tableName === 'users' && selection.spaceId === selectedSpace) {
				addSpacePermissionButtons(selection);
			}
			if (tableName === 'jobs') {
				colorizeJobStatistics();
			}
			initDraggable('#' + tableName);
		}).fail(function(xhr, status) {
			if (status !== 'abort') {
				showLoadError();
			}
		}).always(function() {
			if (tableName === 'jobs' && selectedSpace !== '1' && isCurrent()) {
				refreshTimer = window.setTimeout(function() {
					if (isCurrent() && !$('#jobs tbody tr.row_selected').length) {
						jobTable.api().draw(false);
					}
				}, 10000);
			}
		});
	};
}

/**
 * Colorize the job statistics in the jobTable
 */
function colorizeJobStatistics() {
	// Colorize the statistics in the job table for completed pairs
	$("#jobs p.asc").heatcolor(
		function() {
			// Return the floating point value of the stat
			var value = $(this).text();
			return parseInt(value.slice(0, -1));
		},
		{
			maxval: 100,
			minval: 0,
			colorStyle: 'greentored',
			lightness: 0
		}
	);
	//colorize the unchanging totals
	$("#jobs p.static").heatcolor(
		function() {
			// Return the floating point value of the stat
			return 1;
		},
		{
			maxval: 1,
			minval: 0,
			colorStyle: 'greentored',
			lightness: 0
		}
	);
	// Colorize the statistics in the job table (for pending and error which use reverse color schemes)
	$("#jobs p.desc").heatcolor(
		function() {
			var value = $(this).text();
			return parseInt(value.slice(0, -1));
		},
		{
			maxval: 100,
			minval: 0,
			colorStyle: 'greentored',
			reverseOrder: true,
			lightness: 0
		}
	);
}

/**
 * Initializes the DataTable objects and adds multi-select to them
 */
function initDataTables() {

	function initTable(name, options) {
		return $('#' + name).dataTable(new star.DataTable2Config(Object.assign({
			serverSide: true,
			searchDelay: 400,
			ajax: explorerTableAjax(name)
		}, options)));
	}

	// Setup the DataTable objects
	userTable = initTable('users');
	solverTable = initTable('solvers');
	benchTable = initTable('benchmarks');
	spaceTable = initTable('spaces');
	jobTable = initTable('jobs', {order: []});

	setSortTable(benchTable);

	$("#benchmarks thead").click(function() {
		resetSortButtons();
	});

	var tables = ["#users", "#solvers", "#benchmarks", "#jobs", "#spaces"];

	function unselectAll(except) {
		var tables = ["#users", "#solvers", "#benchmarks", "#jobs", "#spaces"];
		for (x = 0; x < 6; x++) {
			if (except == tables[x]) {
				continue;
			}
			$(tables[x]).find("tr").removeClass("row_selected");
		}
	}

	for (x = 0; x < 6; x++) {
		$(tables[x]).on("mousedown", "tr", function(event) {
			if ($(event.target).closest('.space-permission-trigger').length) {
				return;
			}
			unselectAll("#" + $(this).parent().parent().attr("id"));
			$(this).toggleClass("row_selected");
		});
	}
	linkButtons()

	// Set all fieldsets as expandable (except for action fieldset)
	$('fieldset.expd').expandable(true);

	var advancedActionsCollapsed = $.cookie('advancedActions') != 'false';
	$('fieldset.advancedActions')
	.expandable(advancedActionsCollapsed)
	.children('legend:first')
	.click(function() {
		var advancedActionsCollapsed = !$(this).data('open');
		$.cookie('advancedActions',
			advancedActionsCollapsed,
			{expires: 10000, path: '/'});
	});

	log('all datatables initialized');
}

/*
* Links all the all/none buttons that were broken before.
* This one function handles jobs, benchmarks, solvers, users tables 
* @author aguo2
*/
function linkButtons() {
	$('.selectAllJobs').click(
		function() {
			selectAllInTable('#jobs');
		}
	)
		$('.selectAllSolvers').click(
		function() {
			selectAllInTable('#solvers');
		}
	)
	$('.selectAllBenchmarks').click(
		function() {
			selectAllInTable('#benchmarks');
		}
	)
	$('.selectAllUsers').click(
		function() {
			selectAllInTable('#users');
		}
	)
	$('.unselectAllJobs').click(
		function() {
			selectNoneInTable('#jobs');
		}
	)
		$('.unselectAllSolvers').click(
		function() {
			selectNoneInTable('#solvers');
		}
	)
	$('.unselectAllBenchmarks').click(
		function() {
			selectNoneInTable('#benchmarks');
		}
	)
	$('.unselectAllUsers').click(
		function() {
			selectNoneInTable('#users');
		}
	)


}
function redrawAllTables() {
	benchTable.api().draw();
	jobTable.api().draw();
	userTable.api().draw();
	solverTable.api().draw();
	spaceTable.api().draw();
}

/**
 * Checks the permissions for the current space and hides/shows buttons based on
 * the user's permissions
 * @param perms The JSON permission object representing permissions for the current space
 */
function checkPermissions(perms, id) {
	EXP_SP.permissionSelection.spaceId = String(id);
	EXP_SP.permissionSelection.isLeader = Boolean(perms && perms.isLeader);
	// Check for no permission and hide entire action list if not present
	// Don't hide if user is developer
	if (userIsDeveloper) {
		$('.actionList').show();
		return;
	} else if (perms == null) {
		log('no permissions found, hiding action bar');
		$('.actionList').hide();
		return;
	} else {
		$('.actionList').show();
	}

	if (perms.isLeader) {
		$('#editSpace').fadeIn('fast');
		$('#editSpacePermissions').fadeIn('fast');

	} else {
		$('#editSpace').fadeOut('fast');
		$('#editSpacePermissions').fadeOut('fast');
	}

	log('perms.addSpace=' + perms.addSpace);
	if (perms.addSpace) {
		$('#addSpace').fadeIn('fast');
		$('#uploadXML').fadeIn('fast');
	} else {
		$('#addSpace').fadeOut('fast');
		$('#uploadXML').fadeOut('fast');
	}

	if (perms.addBenchmark) {
		$('#uploadBench').fadeIn('fast');
	} else {
		$('#uploadBench').fadeOut('fast');
	}

	if (perms.addSolver) {
		$('#uploadSolver').fadeIn('fast');
	} else {
		$('#uploadSolver').fadeOut('fast');
	}

	if (perms.addJob) {
		$('#addJob').fadeIn('fast');
		$("#addQuickJob").fadeIn('fast');
		$('#uploadJobXML').fadeIn('fast');
	} else {
		$('#addJob').fadeOut('fast');
		$('#uploadJobXML').fadeOut('fast');
		$("#addQuickJob").fadeOut('fast');
	}

	log('permissions checked and processed');
}

function addSpacePermissionButtons(selection) {
	var $users = $('#users');
	if ($users.attr('data-permission-viewer') !== 'true') {
		return;
	}
	var canViewOthers = selection.isLeader || $users.attr('data-permission-viewer-all') === 'true';
	$users.find('tbody tr').each(function() {
		var $cell = $(this).children('td').first();
		var userId = Number($cell.children('input[prim="user"]').val());
		if (!Number.isInteger(userId) || userId <= 0 || (!canViewOthers && userId !== currentUserId)
			|| $cell.children('.space-permission-trigger').length) {
			return;
		}
		var name = $cell.children('a').first().text().trim();
		$('<button>', {
			type: 'button',
			class: 'btn btn-secondary space-permission-trigger',
			'aria-haspopup': 'dialog',
			'aria-label': 'View permissions for ' + name,
			text: 'View permissions'
		}).data('permissionTarget', { selection: selection, userId: userId, name: name }).appendTo($cell);
	});
}

function initSpacePermissionDialog() {
	var $dialog = $('#space-permission-dialog').dialog({
		autoOpen: false,
		modal: true,
		draggable: false,
		resizable: false,
		close: cancelSpacePermissionRequest
	});
	$dialog.dialog('widget').attr('aria-modal', 'true').removeAttr('aria-describedby');

	$('#users').on('click', '.space-permission-trigger', function() {
		var target = $(this).data('permissionTarget');
		if (target.selection !== EXP_SP.permissionSelection) {
			return;
		}
		// A pointer click does not focus buttons in every browser.
		this.focus();
		$dialog.dialog('option', {
			title: 'Permissions for ' + target.name,
			width: Math.min(420, window.innerWidth - 32)
		}).dialog('open');
		loadSpacePermissions(target);
	});

	$('#space-permission-retry').on('click', function() {
		if (EXP_SP.permissionRequest) {
			$dialog.dialog('widget').find('.ui-dialog-titlebar-close').trigger('focus');
			loadSpacePermissions(EXP_SP.permissionRequest.target);
		}
	});
}

function cancelSpacePermissionRequest() {
	var request = EXP_SP.permissionRequest;
	EXP_SP.permissionRequest = null;
	if (request && request.xhr) {
		request.xhr.abort();
	}
}

function loadSpacePermissions(target) {
	cancelSpacePermissionRequest();
	var request = { target: target };
	EXP_SP.permissionRequest = request;
	var selection = target.selection;
	var $status = $('#space-permission-status').text('Loading permissions…');
	var $results = $('#space-permission-results').empty();
	var $retry = $('#space-permission-retry').prop('hidden', true);
	var $edit = $('#space-permission-edit').prop('hidden', true).removeAttr('href');

	function showLoadError() {
		$status.text('Could not load permissions. Try again.');
		$retry.prop('hidden', false);
	}

	request.xhr = $.ajax({
		url: starexecRoot + 'services/permissions/details/' + target.userId + '/' + selection.spaceId,
		dataType: 'json',
		cache: false
	}).done(function(response) {
		if (request !== EXP_SP.permissionRequest) {
			return;
		}
		if (response && response.success === false) {
			$status.text('You cannot view these permissions.');
			return;
		}
		var perms = response && response.perm;
		if (!perms || typeof perms.isLeader !== 'boolean') {
			showLoadError();
			return;
		}

		$status.text(perms.isLeader ? 'Space leader.' : 'Permissions loaded.');
		$results.append(buildSpacePermissionTable(perms));
		var isAdmin = response.requester && response.requester.role === 'admin';
		if (isAdmin || (selection.isLeader && !(response.isCommunity && perms.isLeader))) {
			$edit.attr('href', starexecRoot + 'secure/edit/spacePermissions.jsp?id=' + selection.spaceId)
				.prop('hidden', false);
		}
	}).fail(function(xhr) {
		if (request !== EXP_SP.permissionRequest) {
			return;
		}
		if (xhr.status === 403) {
			$status.text('You cannot view these permissions.');
		} else {
			showLoadError();
		}
	});
}

function buildSpacePermissionTable(perms) {
	var $table = $('<table aria-label="Permission settings">');
	$table.append('<thead><tr><th scope="col">Resource</th><th scope="col">Add</th><th scope="col">Remove</th></tr></thead>');
	var $body = $('<tbody>').appendTo($table);
	[
		['Jobs', 'addJob', 'removeJob'],
		['Solvers', 'addSolver', 'removeSolver'],
		['Benchmarks', 'addBenchmark', 'removeBench'],
		['Users', 'addUser', 'removeUser'],
		['Spaces', 'addSpace', 'removeSpace']
	].forEach(function(resource) {
		$('<tr>').append(
			$('<th scope="row">').text(resource[0]),
			$('<td>').text(perms[resource[1]] === true ? 'Yes' : 'No'),
			$('<td>').text(perms[resource[2]] === true ? 'Yes' : 'No')
		).appendTo($body);
	});
	return $table;
}

/**
 * Updates the URLs to perform actions on the current space
 * @param id The id of the current space
 */
function updateButtonIds(id) {
	$('#editSpace')
	.attr('href', starexecRoot + "secure/edit/space.jsp?id=" + id);
	$('#editSpacePermissions')
	.attr('href', starexecRoot + "secure/edit/spacePermissions.jsp?id=" + id);
	$('#addSpace')
	.attr('href', starexecRoot + "secure/add/space.jsp?sid=" + id);
	$('#uploadBench')
	.attr('href', starexecRoot + "secure/add/benchmarks.jsp?sid=" + id);
	$('#uploadSolver')
	.attr('href', starexecRoot + "secure/add/solver.jsp?sid=" + id);
	$('#addJob').attr('href', starexecRoot + "secure/add/job.jsp?sid=" + id);
	$('#addQuickJob')
	.attr('href', starexecRoot + "secure/add/quickJob.jsp?sid=" + id);
	$("#processBenchmarks")
	.attr("href", starexecRoot + "secure/edit/processBenchmarks.jsp?sid=" + id);
	$("#downloadXML").off("click");
	$('#downloadXML').click(function(e) {
		$('#dialog-spacexml-attributes-txt')
		.text('Do you want benchmark attributes included in the XML?');
		$('#dialog-spacexml').dialog({
			modal: true,
			width: 380,
			buttons: {
				"download": function() {
					var attVal = $('input[name=att]:checked').val();
					attBool = attVal == "true";
					createDownloadSpaceXMLRequest(attBool, false, -1, id);
					$(this).dialog("close");
				},
				"cancel": function() {
					$(this).dialog("close");
				}
			}
		});
	});

	$('#showUpdateDialog').click(function() {
		$("#dialog-spaceUpdateXml").dialog();
		$('#dialog-spacexml').dialog("close");
		$('#dialog-spacexml-updates-txt')
		.text('Enter default update processor id');
		$('#dialog-spaceUpdateXml').dialog({
			modal: true,
			width: 380,
			buttons: {
				"download": function() {
					var updatePID = $('#updateID').val();
					createDownloadSpaceXMLRequest(false, true, updatePID, id);
					$(this).dialog("close");
				},
				"cancel": function() {
					$(this).dialog("close");
				}
			}
		});
	});

	$('#uploadJobXML')
	.attr('href', starexecRoot + "secure/add/batchJob.jsp?sid=" + id);
	$('#uploadXML')
	.attr('href', starexecRoot + "secure/add/batchSpace.jsp?sid=" + id);
	$("#downloadSpace").off("click");
	$("#downloadSpace").click(function() {
		// Display the confirmation dialog
		$("#downloadBoth").prop("checked", "checked");
		$('#noIdDirectories').prop('checked', 'checked');
		if (isLeafSpace) {
			$('#downloadHierarchyOptionContainer').hide();
		} else {
			$('#downloadHierarchyOptionContainer').show();
		}
		$('#dialog-download-space').dialog({
			modal: true,
			width: 380,
			buttons: {
				'submit': function() {
					createDownloadSpacePost(id);
					$(this).dialog("close");
				}
			}
		});
	});
	log('updated action button space ids to ' + id);
}

function createDownloadSpaceXMLRequest(includeAttrs, updates, upid, id) {
	createDialog(
		"Processing your download request, please wait. This will take some time for large spaces.");
	token = Math.floor(Math.random() * 100000000);
	myhref = starexecRoot + "secure/download?token=" + token + "&type=spaceXML&id=" + id + "&includeattrs=" + includeAttrs + "&updates=" + updates + "&upid=" + upid;
	destroyOnReturn(token);
	window.location.href = myhref;
}

function createDownloadSpacePost(id) {
	var hierarchy = $('#downloadSpaceHierarchy').prop("checked");
	var downloadSolvers = ($("#downloadSolvers").prop("checked") || $(
		"#downloadBoth").prop("checked"));
	var downloadBenchmarks = ($("#downloadBenchmarks").prop("checked") || $(
		"#downloadBoth").prop("checked"));
	var useIdDirectories = $('#yesIdDirectories').prop('checked');
	log('hierarchy: ' + hierarchy);
	log('useIdDirectories: ' + useIdDirectories);
	log('downloadSolvers: ' + downloadSolvers);
	log('downloadBenchmarks: ' + downloadBenchmarks);
	log('useIdDirectories: ' + useIdDirectories);
	createDialog(
		"Processing your download request, please wait. This will take some time for large spaces.");
	token = Math.floor(Math.random() * 100000000);
	window.location.href = starexecRoot + "secure/download?includesolvers=" + downloadSolvers + "&includebenchmarks=" + downloadBenchmarks +
		"&useIdDirectories=" + useIdDirectories + "&token=" + token + "&type=space&hierarchy=" + hierarchy + "&id=" + id;
	destroyOnReturn(token);
}

/**
 * For a given dataTable, this returns true if the user is allowed to delete
 * every selected primitive. This occurs if they own all of them and none of them
 * have already been recycled / deleted
 *
 * @param dataTable the particular dataTable the selections are in
 * @author Eric Burns
 */
function userCanDeleteAll(dataTable) {
	allMatch = true;
	var rows = $(dataTable).children('tbody').children('tr.row_selected');
	$.each(rows, function(i, row) {
		if (!allMatch) {
			return;
		}
		input = $(this).children('td:first').children("input");
		if (parseInt(input.attr("userId")) != currentUserId) {
			allMatch = false;
		} else if (parseBoolean(input.attr("recycled")) || parseBoolean(input.attr(
				"deleted"))) {
			allMatch = false;
		}
	});
	return allMatch;
}

// Reload from the server after removal. Reset paging because the last page
// may no longer exist and use the returned total for the fieldset count.
function updateTable(dataTable) {
	dataTable.api().draw();
}
