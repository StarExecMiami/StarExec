debugModeActive = false;
$(document).ready(function() {
	debugModeActive = parseBoolean($("#toggleDebugMode").attr("value"));
	initUI();
});

function setDebugText() {
	if (debugModeActive) {
		setJqueryButtonText("#toggleDebugMode", "Exit Debug Mode")
	} else {
		setJqueryButtonText("#toggleDebugMode", "Enable Debug Mode")
	}
}

function initUI() {
	$("#restartStarExec").button({
		icons: {
			primary: "ui-icon-power"
		}
	});

	$("#clearStatsCache").button({
		icons: {
			primary: "ui-icon-check"
		}
	});

	$("#toggleDebugMode").button({
		icons: {
			primary: "ui-icon-pencil"
		}
	});

	$("#manageCache").button({
		icons: {
			primary: "ui-icon-document"
		}
	});

	$("#manageLogging").button({
		icons: {
			primary: "ui-icon-document"
		}
	});

	$("#clearLoadData").button({
		icons: {
			primary: "ui-icon-trash"
		}
	});

	$("#clearSolverCacheData").button({
		icons: {
			primary: "ui-icon-trash"
		}
	});

	$("#manageStatus").button({
		icons: {
			primary: "ui-icon-document"
		}
	});

	$("#restartStarExec").click(function() {
		$('#dialog-confirm-restart-txt')
		.text('are you sure you want to restart StarExec?');

		$('#dialog-confirm-restart').dialog({
			modal: true,
			width: 380,
			buttons: {
				'OK': function() {
					log('user confirmed starexec restart.');
					$('#dialog-confirm-restart').dialog('close');
					$.post(
						starexecRoot + "services/restart/starexec/",
						function(returnCode) {
							s = parseReturnCode(returnCode);
							if (s) {
								window.location = starexecRoot + 'secure/explore/spaces.jsp';
							}
						},
						"json"
					).fail(function() {
						showMessage("error", "Failed to restart StarExec", 5000);
					});
				},
				"cancel": function() {
					log('user canceled StarExec restart');
					$(this).dialog("close");
				}
			}
		});
	});

	$("#clearStatsCache").click(function() {
		$.post(
			starexecRoot + "services/cache/clearStats",
			{},
			function(returnCode) {
				parseReturnCode(returnCode);

			},
			"json"
		).fail(function() {
			showMessage("error", "Failed to clear stats cache", 5000);
		});
	});

	$("#clearLoadData").click(function() {
		$.post(
			starexecRoot + "services/jobs/clearloadbalance/",
			parseReturnCode,
			"json"
		).fail(function() {
			showMessage("error", "Failed to clear load balance data", 5000);
		});
	});

	$("#clearSolverCacheData").click(function() {
		$.post(
			starexecRoot + "services/jobs/clearsolvercache/",
			parseReturnCode,
			"json"
		).fail(function() {
			showMessage("error", "Failed to clear solver cache", 5000);
		});
	});

	$("#toggleDebugMode").click(function() {
		$.post(
			starexecRoot + "services/starexec/debugmode/" + !debugModeActive,
			function(returnCode) {
				if (parseReturnCode(returnCode)) {

					debugModeActive = !debugModeActive;
					setDebugText();
				}
			},
			"json"
		).fail(function() {
			showMessage("error", "Failed to toggle debug mode", 5000);
		});
	});

	$("#toggleFreezePrimitives")
	.button({
		icons: {
			primary: "ui-icon-circle-close"
		}
	})
	.click(function() {
		$.post(
			starexecRoot + "services/admin/freezePrimitives",
			{"frozen": !star.freezePrimitives},
			function(returnCode) {
				if (parseReturnCode(returnCode)) {
					setTimeout(function() {document.location.reload(true)}, 1000);
				}
			},
			"json"
		).fail(function() {
			showMessage("error", "Failed to toggle freeze primitives mode", 5000);
		});
	});

	$("#toggleReadOnly").button({
		icons: {
			primary: "ui-icon-circle-close"
		}
	}).click(function() {
		$.post(
			starexecRoot + "services/admin/readOnly",
			{"readOnly": !star.readOnly},
			function(returnCode) {
				if (parseReturnCode(returnCode)) {
					setTimeout(function() {document.location.reload(true)}, 1000);
				}
			},
			"json"
		).fail(function() {
			showMessage("error", "Failed to toggle read-only mode", 5000);
		});
	});

	setDebugText();
}


