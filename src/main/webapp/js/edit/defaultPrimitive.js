var primType;
$(document).ready(function() {
	primType = $("#primType").attr("value");

	initUI();
	attachFormValidation();
});

/**
 * Initializes the user-interface
 */
function initUI() {
	//initialize primitive table
	primTable = $('#prims').dataTable({
		"sDom": getDataTablesDom(),
		"iDisplayStart": 0,
		"iDisplayLength": defaultPageSize,
		"bServerSide": true,
		"sAjaxSource": starexecRoot + "services/space/",
		"sServerMethod": "POST",
		"fnServerData": fnPaginationHandler,
		// Preserve the special "none" row when DataTables replaces table body
		"fnDrawCallback": function(oSettings) {
			attachRowSelectionHandlers();
		}
	});

	attachRowSelectionHandlers();

	// Attach icons
	$('#cancel').button({
		icons: {
			secondary: "ui-icon-closethick"
		}
	});
	$('#update').button({
		icons: {
			secondary: "ui-icon-check"
		}
	});

	$("#cancel").click(function() {
		navBack();
	});

}

/**
 * Attaches row selection event handlers to all table rows.
 * This is called both on initial page load and after DataTables pagination.
 */
function attachRowSelectionHandlers() {
	$("#prims").on("mousedown", "tr", function() {
		if ($(this).hasClass("row_selected")) {
			$(this).removeClass("row_selected");
		} else {
			unselectAll();
			$(this).addClass("row_selected");
		}
	});
}

function navBack() {
	if (isSpaceSetting()) {
		window.location = starexecRoot + 'secure/edit/community.jsp?cid=' + $(
			"#primId").attr("value");
	} else {
		window.location = starexecRoot + 'secure/edit/account.jsp?id=' + $(
			"#primId").attr("value");
	}
}

/**
 * Returns the id of the selected primitive or -1 if there is not one.
 * @author Eric Burns
 */
function primSelected() {
	row = $("#prims").find(".row_selected");
	if (row.length == 0) { //means no primitive is selected
		return -1;
	}
	input = row.find("input");
	id = input.attr("value");
	return id;
}

/**
 * returns true if this is the defaultsettings profile of a space and false otherwise
 * @returns {Boolean}
 */
function isSpaceSetting() {
	return $("#settingType").attr("value") == "comm";
}

/**
 * Validates that a user has selected a primitive when update is clicked.
 * Accepts -1 as a valid value to reset/clear the setting to "none".
 */
function attachFormValidation() {

	$("#update").click(function() {
		var selectedPrim = primSelected();

		// Accept -1 (clear/none) or positive IDs (actual primitives)
		if (selectedPrim >= -1) {
			createDialog("Updating default " + primType + ", please wait");
			$.post(
				starexecRoot + "services/edit/defaultSettings/" + "default" + primType + "/" + $(
				"#settingId").attr("value"),
				{val: selectedPrim},
				function(returnCode) {
					s = parseReturnCode(returnCode);
					if (s) {
						navBack();
					}

				},
				"json"
			);
		} else {
			showMessage('error', "Select a " + primType + " or clear the selection to proceed",
				"5000");
		}
	});
}

function unselectAll() {
	$("#prims").find("tr").removeClass("row_selected");
}

/**
 * Handles querying for pages in a given DataTable object
 *
 * @param sSource the "sAjaxSource" of the calling table
 * @param aoData the parameters of the DataTable object to send to the server
 * @param fnCallback the function that actually maps the returned page to the DataTable object
 * @author Todd Elvers
 */
function fnPaginationHandler(sSource, aoData, fnCallback) {
	// Extract the id of the currently selected space from the DOM
	if (isSpaceSetting()) {
		var idOfSelectedSpace = $("#primId").attr("value");
		// Request the next page of primitives from the server via AJAX
		$.post(
			sSource + idOfSelectedSpace + "/" + primType + "s" + "/pagination",
			aoData,
			function(nextDataTablePage) {
				s = parseReturnCode(nextDataTablePage);
				if (s) {
					// Replace the current page with the newly received page
					fnCallback(nextDataTablePage);
				}
			},
			"json"
		);
	}

}
