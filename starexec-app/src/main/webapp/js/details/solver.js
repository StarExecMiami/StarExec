var solverId;
$(document).ready(function() {
	initUI();
	attachButtonActions();

	$("#dialog-confirm-copy").css("display", "none");

	$('img').click(function(event) {
		popUp($(this).attr('enlarge'));
	});

	$('.confirmation').on('click', function() {
		return confirm('Are you sure?');
	});
});

function initUI() {
	$('#downLink3').button({
		icons: {
			secondary: "ui-icon-arrowthick-1-s"
		}
	});

	$('#srcLink').button({
		icons: {
			secondary: "ui-icon-arrowthick-1-s"
		}
	});

	registerAnonymousLinkButtonEventHandler();

	$('#fieldSites').expandable(true);
	$('#actions').expandable(true);

	// Setup datatable of configurations
	$('#tblSolverConfig').dataTable(new star.DataTableConfig());

	$('#downBuildInfo').button({
		icons: {
			secondary: "ui-icon-newwin"
		}
	});
	// Setup button icons

	$('#uploadConfig, #uploadConfigMargin').button({
		icons: {
			primary: "ui-icon-arrowthick-1-n"
		}
	});

	$('#uploadPicture').button({
		icons: {
			primary: "ui-icon-gear"
		}
	});

	//Warn if there was some error during upload (no configs, running test job failed).
	var msg = getParameterByName("msg");
	if (stringExists(msg)) {
		$('#dialog-warning-txt').text(msg);

		$('#dialog-warning').dialog({
			modal: true,
			width: 700,
			buttons: {
				'OK': function() {
					$('#dialog-warning').dialog('close');
				}
			}
		});
	}

	//Display feedback if the solver is being built on starexec.
	var msg = getParameterByName("buildmsg");
	if (stringExists(msg)) {
		$('#dialog-building-job').text(msg);
		$('#dialog-building-job').dialog({
			modal: true,
			width: 380,
			buttons: {
				'OK': function() {
					$('#dialog-building-job').dialog('close');
				}
			}
		});
	}
}

function attachButtonActions() {
	$("#srcLink").click(function() {
		var token = Math.floor(Math.random() * 100000000);
		window.location.href = starexecRoot + "secure/download?token=" + token + "&type=solverSrc&id=" + $(
			"#solverId").attr("value");
		destroyOnReturn(token);
	});
	$("#downLink3").click(function() {
		$('#dialog-confirm-copy-txt')
		.text('How would you like to download the solver?');
		$('#dialog-confirm-copy').dialog({
			modal: true,
			width: 380,
			buttons: {
				'default': function() {
					log("default selected");
					$(this).dialog("close");
					createDialog(
						"Processing your download request, please wait. This will take some time for large solvers.");
					var token = Math.floor(Math.random() * 100000000);
					if ($('#isAnonymousPage').attr('value') === 'true') {
						var anonId = getParameterByName('anonId');
						window.location.href = starexecRoot + "secure/download?token=" + token + "&type=solver&anonId=" + anonId;
					} else {
						window.location.href = starexecRoot + "secure/download?token=" + token + "&type=solver&id=" + $(
							"#solverId").attr("value");
					}
					destroyOnReturn(token);
				},
				'Re-upload': function() {
					log("upload selected");
					$(this).dialog("close");
					createDialog(
						"Processing your download request, please wait. This will take some time for large solvers.");
					token = Math.floor(Math.random() * 100000000);
					if ($('#isAnonymousPage').attr('value') === 'true') {
						var anonId = getParameterByName('anonId');
						window.location.href = starexecRoot + "secure/download?token=" + token + "&reupload=true&type=solver&anonId=" + anonId;
					} else {
						window.location.href = starexecRoot + "secure/download?token=" + token + "&reupload=true&type=solver&id=" + $(
							"#solverId").attr("value");
					}
					destroyOnReturn(token);
				},
				"cancel": function() {
					log('user canceled copy action');
					$(this).dialog("close");
				}
			}
		});
	});
	$("#rebuildSolver")
	.button({
		icons: {
			primary: "ui-icon-arrowrefresh-1-e"
		}
	})
	.click(function() {
		'use strict';
		var $dialog = $("<div>");
		$dialog
		.text(
			"Are you sure you want to recompile this solver from source?"
		)
		.dialog({
			modal: true,
			show: false,
			title: "Rebuild this solver?",
			close: function() {
				$dialog.dialog('destroy').remove();
			},
			buttons: {
				"Rebuild solver": function() {
					var solverId = getParameterByName("id");
					$dialog.dialog('close');
					createDialog("Creating build job. Please wait.");
					$.ajax({
						"accepts": "application/json",
						"url": starexecRoot + "secure/solver/rebuild",
						"method": "POST",
						"data": {"id": solverId},
						"dataType": "json",
						"complete": function(jqXHR) {
							destroyDialog();
							var parsed = null;
							if (jqXHR && typeof jqXHR.responseText === "string" && jqXHR.responseText.trim().length > 0) {
								try {
									parsed = JSON.parse(jqXHR.responseText);
								} catch (e) {
									showMessage("error", "Invalid server response while rebuilding solver", 5000);
									return;
								}
							} else if (jqXHR && jqXHR.responseJSON && typeof jqXHR.responseJSON === "object") {
								parsed = jqXHR.responseJSON;
							}
							var isHttpError = !jqXHR || (typeof jqXHR.status === "number" && (jqXHR.status < 200 || jqXHR.status >= 300));
							if (!parsed) {
								showMessage("error", "Internal error rebuilding solver", 5000);
								return;
							}
							var s = parseReturnCode(parsed);
							if (isHttpError) {
								if (s) {
									showMessage("error", "Internal error rebuilding solver", 5000);
								}
								return;
							}
							if (s) {
								var msg="Rebuilding solver";
								window.location.replace("?id="+solverId+"&buildmsg="+encodeURIComponent(msg));
							}
						},
					});
				},
				"cancel": function() {
					$dialog.dialog('close');
				}
			}
		})
		return false;
	});
}

function popUp(uri) {
	imageDialog = $("#popDialog");
	imageTag = $("#popImage");

	imageTag.attr('src', uri);

	imageTag.on("load", function() {
		$('#popDialog').dialog({
			dialogClass: "popup",
			modal: true,
			resizable: false,
			draggable: false,
			height: 'auto',
			width: 'auto'
		});
	});
}

function registerAnonymousLinkButtonEventHandler() {
	'use strict';
	$('#anonymousLink').off('click');
	$('#anonymousLink').click(function() {
		$('#dialog-confirm-anonymous-link')
		.text("Do you want the solver's name to be hidden on the linked page?");
		$('#dialog-confirm-anonymous-link').dialog({
			modal: true,
			width: 600,
			buttons: {
				'yes': function() {
					$(this).dialog('close');
					makeAnonymousLinkPost('solver',
						$('#solverId').attr('value'),
						'all');
				},
				'no': function() {
					$(this).dialog('close');
					makeAnonymousLinkPost('solver',
						$('#solverId').attr('value'),
						'none');
				}
			}
		});
	});
}
