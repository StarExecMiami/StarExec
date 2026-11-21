var type;
var defaultQueueId;
var curQueueId;

jQuery(function($) {
	"use strict";

	// Set the path to the css theme for the jstree plugin

	var reloadOnSucess = function(returnCode) {
		if (parseReturnCode(returnCode)) {
			setTimeout(function() {document.location.reload(true);}, 1000);
		}
	};
	var id = -1;
	curQueueId = -1;

	var $confirmRemove = $("<div title='confirm removal' class='hiddenDialog'>")
		.append("<p><span class='ui-icon ui-icon-alert'></span></p>")
		.appendTo("body")
	;
	var $confirmRemoveMessage = $("<span>")
		.appendTo($confirmRemove.contents("p"))
	;

	star.mountTree($("#exploreList").on("ready.jstree refresh.jstree", addNodeCountsToTree),
		starexecRoot + "services/cluster/queues", {
			rootId: -1,
			queues: true,
			dots: false,
			select: function(node) {
				var queue = node.data;
				window.type = queue.rel;
				defaultQueueId = queue.defaultQueueId;
				updateActionId(queue.id, queue.rel, queue.global);
			}
		});

	$("#clearErrorStates a")
	.button({
		icons: {
			primary: "ui-icon-pencil"
		}
	})
	.click(function() {
		$.post(
			starexecRoot + "services/cluster/clearerrors",
			parseReturnCode,
			"json"
		).fail(function() {
			showMessage("error", "Failed to clear error states", 5000);
		});
	})
	;

	$("#CommunityAssoc a").button({
		icons: {
			primary: "ui-icon-clipboard"
		}
	});

	$("#editQueue a")
	.button({
		icons: {
			primary: "ui-icon-pencil"
		}
	})
	.click(function() {
		window.open(starexecRoot + "secure/edit/queue.jsp?id=" + curQueueId);
	})
	;

	$("#makeGlobal a")
	.button({
		icons: {
			primary: "ui-icon-unlocked"
		}
	})
	.click(function() {
		$confirmRemoveMessage.text(
			'are you sure you want to give global access to this queue?');
		$confirmRemove.dialog({
			modal: true,
			width: 380,
			buttons: {
				'OK': function() {
					log('user confirmed giving global access.');
					$confirmRemove.dialog('close');
					$.post(
						starexecRoot + "services/queue/global/" + curQueueId,
						reloadOnSucess,
						"json"
					).fail(function() {
						showMessage("error", "Failed to grant global queue access", 5000);
					});
				},
				"cancel": function() {
					log('user canceled giving global access');
					$confirmRemove.dialog("close");
				}
			}
		});
	})
	;

	$("#makeTest a")
	.button({
		icons: {
			primary: "ui-icon-clipboard"
		}
	})
	.click(function() {
		$.post(
			starexecRoot + "services/test/queue/" + curQueueId,
			parseReturnCode,
			"json"
		).fail(function() {
			showMessage("error", "Failed to update test queue status", 5000);
		});
	})
	;

	$("#manageNodes")
	.button({
		icons: {
			primary: "ui-icon-clipboard"
		}
	})
	;

	$("#moveNodes a")
	.button({
		icons: {
			primary: "ui-icon-locked"
		}
	})
	;

	$("#newQueue")
	.button({
		icons: {
			primary: "ui-icon-plusthick"
		}
	})
	;

	$("#removeGlobal a")
	.button({
		icons: {
			primary: "ui-icon-locked"
		}
	})
	.click(function() {
		$confirmRemoveMessage.text(
			'are you sure you want to remove global access from this queue?');
		$confirmRemove.dialog({
			modal: true,
			width: 380,
			buttons: {
				'OK': function() {
					log('user confirmed removing global access.');
					$confirmRemove.dialog('close');
					$.post(
						starexecRoot + "services/queue/global/remove/" + curQueueId,
						reloadOnSucess,
						"json"
					).fail(function() {
						showMessage("error", "Failed to remove global queue access", 5000);
					});
				},
				"cancel": function() {
					log('user canceled removing global access');
					$confirmRemove.dialog("close");
				}
			}
		});
	})
	;

	$("#removeQueue a")
	.button({
		icons: {
			primary: "ui-icon-minusthick"
		}
	})
	.click(function() {
		$confirmRemoveMessage.text('are you sure you want to remove this queue?');
		$confirmRemove.dialog({
			modal: true,
			width: 380,
			buttons: {
				'OK': function() {
					log('user confirmed queue removal.');
					$confirmRemove.dialog('close');
					$.post(
						starexecRoot + "services/remove/queue/" + curQueueId,
						reloadOnSucess,
						"json"
					).fail(function() {
						showMessage("error", "Failed to remove queue", 5000);
					});
				},
				"cancel": function() {
					log('user canceled queue removal');
					$confirmRemove.dialog("close");
				}
			}
		});
	})
	;

	// Setup the DataTable objects
	$('#qreserves')
	.dataTable(new star.DataTableConfig({
		"bServerSide": true,
		"sAjaxSource": starexecRoot + "services/queues/pending/pagination",
		"fnServerData": fnPaginationHandler,
		"language": { "emptyTable": "No queue reservation requests." }
	}))
	;

});

function updateActionId(id, type, global) {
	curQueueId = id;
	if (id == -1) {
		$("#removeQueue").hide();
		$("#moveNodes").hide();
		$("#CommunityAssoc").hide();
		$("#makeGlobal").hide();
		$("#makeTest").hide();
		$("#removeGlobal").hide();
		$("#editQueue").hide();
	} else {
		$("#editQueue").show();
	}

	if (type == "active_queue" || type == "inactive_queue") {
		$("#makeTest").show();

		if (id == defaultQueueId) {
			$("#removeQueue").hide();
			$("#moveNodes").show();
			$("#CommunityAssoc").hide();
			$("#makeGlobal").hide();
			$("#removeGlobal").hide();
		} else {
			$("#removeQueue").show();
			$("#moveNodes").show();

			if (global) {
				$("#makeGlobal").hide();
				$("#removeGlobal").show();
				$("#CommunityAssoc").hide();
			} else {
				$("#makeGlobal").show();
				$("#removeGlobal").hide();
				$("#CommunityAssoc").show();
			}
		}
	} else {
		$("#removeQueue").hide();
		$("#moveNodes").hide();
		$("#CommunityAssoc").hide();
		$("#makeGlobal").hide();
		$("#removeGlobal").hide();
	}

	$('#moveNodes a')
	.attr('href', starexecRoot + "secure/admin/moveNodes.jsp?id=" + id);
	$('#CommunityAssoc a')
	.attr('href', starexecRoot + "secure/admin/assocCommunity.jsp?id=" + id);

}

function fnPaginationHandler(sSource, aoData, fnCallback) {
	// Request the next page of primitives from the server via AJAX
	$.post(
		sSource,
		aoData,
		function(nextDataTablePage) {
			if (parseReturnCode(nextDataTablePage)) {
				// Replace the current page with the newly received page
				fnCallback(nextDataTablePage);
			}
		},
		"json"
	).fail(function(_jqXHR, textStatus) {
		handleAjaxError(textStatus);
	});
}
