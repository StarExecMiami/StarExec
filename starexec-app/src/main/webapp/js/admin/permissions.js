var space_id;

$(document).ready(function() {

	$("#editPermissions").button({
		icons: {
			primary: "ui-icon-pencil"
		}
	});

	$("#makeMember").button({
		icons: {
			primary: "ui-icon-pencil"
		}
	});

	$("#editPermissions").hide();

	function getUrlVars() {
		var vars = {};
		var parts = window.location.href.replace(/[?&]+([^=&]+)=([^&]*)/gi,
			function(m, key, value) {
				vars[key] = value;
			});
		return vars;
	}

	var user_id = getUrlVars()["id"];

	star.mountTree($("#exploreList"), starexecRoot + "services/space/subspaces", {
		rootId: -1,
		dots: false,
		stateKey: "admin-user-permissions",
		select: function(node) {
			space_id = node.id;
			getPermissionDetails(user_id, space_id);
			initUI(user_id, space_id);
		}
	});

});

function populateDetails(data) {
	if (data.perm == null) {
		$('#fieldStep1').hide();
		$('#fieldStep2').show();
	} else {
		$('#fieldStep2').hide();
		$('#fieldStep1').show();
	}
	var addSolver = data.perm.addSolver;
	var addBench = data.perm.addBenchmark;
	var addUser = data.perm.addUser;
	var addSpace = data.perm.addSpace;
	var addJob = data.perm.addJob;
	var removeSolver = data.perm.removeSolver;
	var removeBench = data.perm.removeBench;
	var removeUser = data.perm.removeUser;
	var removeSpace = data.perm.removeSpace;
	var removeJob = data.perm.removeJob;
	var isLeader = data.perm.isLeader;

	checkBoxes("addSolver", addSolver);
	checkBoxes("addBench", addBench);
	checkBoxes("addUser", addUser);
	checkBoxes("addSpace", addSpace);
	checkBoxes("addJob", addJob);
	checkBoxes("removeSolver", removeSolver);
	checkBoxes("removeBench", removeBench);
	checkBoxes("removeUser", removeUser);
	checkBoxes("removeJob", removeJob);
	checkBoxes("removeSpace", removeSpace);
	checkBoxes("isLeader", isLeader);

}

function checkBoxes(name, value) {
	$("#" + name).prop('checked', value==true);
}

function initUI(userId, spaceId) {

	$("#editPermissions").show();

	$("#editPermissions").click(function() {
		$("#dialog-confirm-update-txt")
		.text(
			"are you sure you want to edit this user's permissions for this space?");

		$("#dialog-confirm-update").dialog({
			modal: true,
			width: 380,
			buttons: {
				'OK': function() {
					$('#dialog-confirm-update').dialog('close');
					var data =
						{
							addBench: $("#addBench").is(':checked'),
							addJob: $("#addJob").is(':checked'),
							addSolver: $("#addSolver").is(':checked'),
							addSpace: $("#addSpace").is(':checked'),
							addUser: $("#addUser").is(':checked'),
							removeBench: $("#removeBench").is(':checked'),
							removeJob: $("#removeJob").is(':checked'),
							removeSolver: $("#removeSolver").is(':checked'),
							removeSpace: $("#removeSpace").is(':checked'),
							removeUser: $("#removeUser").is(':checked'),
							isLeader: $("#isLeader").is(':checked')
						};
					// Pass data to server via AJAX
					$.post(
						starexecRoot + "services/space/" + spaceId + "/edit/perm/" + userId,
						data,
						function(returnCode) {
							parseReturnCode(returnCode);

						},
						"json"
					).fail(function() {
						showMessage("error", "Failed to update space permissions", 5000);
					});
				},
				"cancel": function() {
					log('user canceled StarExec restart');
					$(this).dialog("close");
				}
			}
		});
	});

	$("#makeMember").click(function() {
		$("#dialog-confirm-update-txt")
		.text("are you sure you want to make the user a member of this space?");

		$("#dialog-confirm-update").dialog({
			modal: true,
			width: 380,
			buttons: {
				'OK': function() {
					$('#dialog-confirm-update').dialog('close');
					$.post(
						starexecRoot + "services/spaces/" + spaceId + "/add/user",
						{selectedIds: [userId], copyToSubspaces: false},
						function(returnCode) {
							s = parseReturnCode(returnCode);
							if (s) {
								setTimeout(function() {
									document.location.reload(true);
								}, 1000);

							}
						},
						"json"
					).fail(function() {
						showMessage("error", "Failed to add user to space", 5000);
					});
				},
				"cancel": function() {
					log('user canceled StarExec restart');
					$(this).dialog("close");
				}
			}
		});
	});
}


