var leaderTable;
var memberTable;
requestsTable = null;
var requestsTableInitializedOnce = false;
var latestCommunityDetailsRequest;

var commName; // Current community's name

// When the document is ready to be executed on
$(document).ready(function() {
	var id = -1;
	var userId = $('#userId').attr('value');
	log('User Id is: ' + userId);

	var dataTableConfig = new star.DataTableConfig({
		"language": { "emptyTable": "No members or leaders to display." }
	});
	memberTable = $('#members').dataTable(dataTableConfig);
	leaderTable = $('#leaders').dataTable(dataTableConfig);

	initCommunityTree(function(selectedId) {
		id = selectedId;
		updateActionId(id);
		getCommunityDetails(id);
		// immediately hide the community requests table so unauthorized users wont get a glimpse of it.
		$('#communityField').fadeOut('fast');
		if (requestsTableInitializedOnce) {
			// Destroy the commRequests table if it's been built.
			requestsTable.fnDestroy();
		} else {
			requestsTableInitializedOnce = true;
		}
		requestsTable = initCommunityRequestsTable('#commRequests', false, id);
	});

	$("#members").on("click", "tr", function() {
		$(this).toggleClass("row_selected");
	});

	// Make leaders and members expandable
	$('.expd').parent().expandable(true);

	// Hide all buttons initially
	$("#joinComm").fadeOut('fast');
	$("#leaveComm").fadeOut('fast');
	$("#editComm").fadeOut('fast');
	$("#downloadPostProcessors").fadeOut('fast');
	$("#downloadBenchProcessors").fadeOut('fast');
	$("#downloadPreProcessors").fadeOut('fast');
	$("#downloadUpdateProcessors").fadeOut('fast');

	$('#joinComm').button({
		icons: {
			secondary: "ui-icon-plus"
		}
	});

	$('#leaveComm').button({
		icons: {
			secondary: "ui-icon-close"
		}
	});

	$('#editComm').button({
		icons: {
			secondary: "ui-icon-pencil"
		}
	});

	$('#downloadPostProcessors').button({
		icons: {
			secondary: "ui-icon-arrowthick-1-s"
		}
	});

	$("#downloadBenchProcessors").button({
		icons: {
			secondary: "ui-icon-arrowthick-1-s"
		}
	});
	$("#downloadPreProcessors").button({
		icons: {
			secondary: "ui-icon-arrowthick-1-s"
		}
	});
	$("#downloadUpdateProcessors").button({
		icons: {
			secondary: "ui-icon-arrowthick-1-s"
		}
	});

	setupHandlersForCommunityRequestAcceptDeclineButtons();

	$("#leaveComm").click(function() {
		$('#dialog-confirm-leave-txt')
		.text('are you sure you want to leave ' + commName + '? This will remove you from every space in the communiity. Your primitives will not be affected.');

		// Display the confirmation dialog
		$('#dialog-confirm-leave').dialog({
			modal: true,
			buttons: {
				'yes': function() {
					// If the user actually confirms, close the dialog right away
					$('#dialog-confirm-leave').dialog('close');
					leaveCommunity(id);
				},
				"cancel": function() {
					$(this).dialog("close");
				}
			}
		});
	});

	$("#downloadPostProcessors").click(function() {
		downloadProcs(id, "post");
	});
	$("#downloadBenchProcessors").click(function() {
		downloadProcs(id, "bench");
	});
	$("#downloadPreProcessors").click(function() {
		downloadProcs(id, "pre");
	});
	$("#downloadUpdateProcessors").click(function() {
		downloadProcs(id, "update");
	});
});

function communityTreeNodes(response) {
	if (!Array.isArray(response)) {
		return null;
	}
	var nodes = [];
	for (var community of response) {
		if (!community || !community.attr || !Number.isInteger(community.attr.id)
			|| community.attr.id <= 0 || typeof community.data !== 'string') {
			return null;
		}
		nodes.push({
			id: String(community.attr.id),
			text: community.data,
			li_attr: { rel: 'space' }
		});
	}
	return nodes.sort(function(a, b) {
		var left = a.text.toLowerCase();
		var right = b.text.toLowerCase();
		return left < right ? -1 : left > right ? 1 : 0;
	});
}

function initCommunityTree(onSelect) {
	var $tree = $('#exploreList');
	var $status = $('#community-tree-status');
	var $retry = $('#community-tree-retry');
	var selectionCookie = 'starexec-community-selection';

	function showLoadError() {
		$status.text('Could not load communities.');
		$retry.prop('hidden', false);
	}

	function load() {
		$status.text('Loading communities…');
		$retry.prop('hidden', true);
		$.getJSON(starexecRoot + 'services/communities/all').done(function(response) {
			var nodes = communityTreeNodes(response);
			if (!nodes) {
				showLoadError();
				return;
			}
			if (!nodes.length) {
				$status.text('No communities available.');
				return;
			}
			$tree.one('ready.jstree', function(event, data) {
				var legacySelection = $.cookie('jstree_select') || '';
				var savedIds = [$.cookie(selectionCookie)].concat(legacySelection.split(',').map(function(id) {
					return id.replace(/^#/, '');
				}));
				var savedId = savedIds.find(function(id) {
					return id && /^[1-9]\d*$/.test(id) && data.instance.get_node(id);
				});
				if (savedId) {
					data.instance.select_node(savedId);
					$tree.attr('aria-activedescendant', savedId);
				}
				$status.text('');
			}).on('select_node.jstree', function(event, data) {
				$.cookie(selectionCookie, data.node.id);
				onSelect(data.node.id);
			}).jstree({
				core: {
					data: nodes,
					force_text: true,
					multiple: false,
					worker: false,
					themes: { name: 'default-dark', dots: false, icons: true }
				}
			});
		}).fail(showLoadError);
	}

	$retry.on('click', function() {
		if (!$retry.prop('hidden')) {
			$status.trigger('focus');
			load();
		}
	});
	load();
}

/**
 * Populates the community details panel with information on the given community
 */
function getCommunityDetails(id) {
	log('getting community details for selected community.');
	var request = { id: id };
	latestCommunityDetailsRequest = request;
	$('#loader').show();

	$.get(
		starexecRoot + "services/communities/details/" + id,
		function(data) {
			if (request === latestCommunityDetailsRequest) {
				log('successfully got community details');
				populateDetails(data);
			}
		},
		"json"
	).fail(function() {
		if (request === latestCommunityDetailsRequest) {
			$('#loader').hide();
			showMessage('error', "Internal error getting community details", 5000);
		}
	});
}

/**
 * Takes in a json  response and populates the details panel with information
 * @param jsonData the json data to populate the details page with
 */
function populateDetails(jsonData) {
	log('populating community details.');
	commName = jsonData.space.name;
	// Populate space defaults
	$('#commName').fadeOut('fast', function() {
		$('#commName').text(jsonData.space.name).fadeIn('fast');
	});
	$('#commDesc').fadeOut('fast', function() {
		$('#commDesc').text(jsonData.space.description).fadeIn('fast');
	});
	// Populate members table

	$('#memberField legend')
	.children('span:first-child')
	.text(jsonData.space.users.length);
	memberTable.fnClearTable();

	$.each(jsonData.space.users, function(i, user) {

		var hiddenUserId = '<input type="hidden" value="' + user.id + '" >';
		var fullName = user.firstName + ' ' + user.lastName;
		var userLink = '<a href="' + starexecRoot + 'secure/details/user.jsp?id=' + user.id + '" target="_blank" rel="noopener noreferrer">' + fullName + '<span class="ext-link-icon"></span></a>' + hiddenUserId;
		var emailLink = '<a href="mailto:' + user.email + '">' + user.email + '<span class="ext-link-icon"></span></a>';
		if (!user.isPublic) {
			memberTable.fnAddData([userLink, user.institution, emailLink]);
		} else {
			$('#memberField legend')
			.children('span:first-child')
			.text(jsonData.space.users.length - 1);
		}

	});
	// Populate leaders table
	$('#leaderField legend')
	.children('span:first-child')
	.text(jsonData.leaders.length);
	leaderTable.fnClearTable();
	$.each(jsonData.leaders, function(i, user) {
		var fullName = user.firstName + ' ' + user.lastName;
		var userLink = '<a href="' + starexecRoot + 'secure/details/user.jsp?id=' + user.id + '" target="_blank" rel="noopener noreferrer">' + fullName + '<span class="ext-link-icon"></span></a>';
		var emailLink = '<a href="mailto:' + user.email + '">' + user.email + '<span class="ext-link-icon"></span></a>';
		leaderTable.fnAddData([userLink, user.institution, emailLink]);
	});

	// Check the new permissions for the loaded space
	checkPermissions(jsonData.perm, jsonData.isMember);
	// Done loading, hide the loader
	$('#loader').hide();
}

/**
 * Checks the permissions for the current community and hides/shows buttons based on
 * the user's permissions
 * @param perms The JSON permission object representing permissions for the current space
 */
function checkPermissions(perms, isMember) {
	log('checking permissions for user');
	//we can have permissions even if we are not a member, if the community is public
	if (perms == null) {
		log('perms was null');
		$('#downloadPostProcessors').fadeOut('fast');
		$('#downloadBenchProcessors').fadeOut('fast');
		$('#downloadPreProcessors').fadeOut('fast');
		$('#downloadUpdateProcessors').fadeOut('fast');
		$('#communityField').fadeOut('fast');
		//return;
	} else {
		$('#downloadPostProcessors').fadeIn('fast');
		$('#downloadBenchProcessors').fadeIn('fast');
		$('#downloadPreProcessors').fadeIn('fast');
		$('#downloadUpdateProcessors').fadeIn('fast');
		if (perms.isLeader || $('#hasAdminReadPrivileges')
			.attr('value') === 'true') {
			log('User is leader for this community');
			$('#editComm').fadeIn('fast');
			$('#communityField').fadeIn('fast');
			$('#commRequests').fadeIn('fast');
		} else {
			log('User is not leader for this community');
			$('#communityField').fadeOut('fast');
			$('#editComm').fadeOut('fast');
			$('#commRequests').fadeOut('fast');
		}
	}
	if (!isMember) {
		$('#joinComm').fadeIn('fast');
		$('#leaveComm').fadeOut('fast');
	} else {
		$('#joinComm').fadeOut('fast');
		$('#leaveComm').fadeIn('fast');
	}

}

/**
 * Updates the URLs to perform actions on the current community
 * @param id The id of the current community
 */
function updateActionId(id) {
	$('#joinComm')
	.attr('href', starexecRoot + "secure/add/to_community.jsp?cid=" + id);
	$('#editComm')
	.attr('href', starexecRoot + "secure/edit/community.jsp?cid=" + id);
}

function downloadProcs(id, procClass) {
	createDialog(
		"Processing your download requeste, please wait. This will take some time for large processors.");
	token = Math.floor(Math.random() * 1000000000);
	window.location.href = starexecRoot + "secure/download?type=proc&procClass=" + procClass + "&id=" + id + "&token=" + token;
	destroyOnReturn(token);
}

/**
 * Sends an AJAX request for a user to leave a given community
 * @param id the id of the community the user wants to leave
 */
function leaveCommunity(id) {
	$.post(
		starexecRoot + "services/leave/space/" + id,
		function(returnCode) {
			if (parseReturnCode(returnCode) && latestCommunityDetailsRequest && latestCommunityDetailsRequest.id === id) {
				getCommunityDetails(id);
			}
		},
		"json"
	).fail(function() {
		showMessage('error', "Internal error leaving community", 5000);
	});

	// Redraw the two tables to prevent the case where
	// a member was removed but the tables weren't updated
	leaderTable.fnDraw();
	memberTable.fnDraw();
}

/**
 * For a given dataTable, this extracts the id's of the rows that have been
 * selected by the user
 *
 * @param dataTable the particular dataTable to extract the id's from
 * @returns {Array} list of id values for the selected rows
 */
function getSelectedRows(dataTable) {
	var idArray = [];
	var rows = $(dataTable).children('tbody').children('tr.row_selected');
	$.each(rows, function(i, row) {
		idArray.push($(this).children('td:first').children('input').val());
	});
	return idArray;
}

/**
 * This handles the showing and hiding of selection-specific buttons
 *
 * @param dataTable the dataTable to check for selected items
 * @param button the button to handle
 */
function updateButton(dataTable, button) {
	var selectedRows = $(dataTable)
	.children('tbody')
	.children('tr.row_selected');
	var btnTxt = $(button).children('.ui-button-text');

	if (selectedRows.length == 0) {
		$(button).fadeOut('fast');
	}
	else if (selectedRows.length >= 2) {
		$(button).fadeIn('fast');
		if ($(btnTxt).text()[$(btnTxt).text().length - 1] != "s") {
			$(btnTxt).text($(btnTxt).text() + "s").append();
		}
	} else {
		$(button).fadeIn('fast');
		if ($(btnTxt).text()[$(btnTxt).text().length - 1] == "s") {
			$(btnTxt)
			.text($(btnTxt).text().substring(0, $(btnTxt).text().length - 1))
			.append();
		}
	}
}
