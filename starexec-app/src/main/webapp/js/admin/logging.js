"use strict";

jQuery(function($) {
	var $levelTable = $('#tableLevels');
	$levelTable.dataTable(new window.star.DataTableConfig({
		"bSort": false,
		"bPaginate": true
	}));

	var getSelectedRowValue = function() {
		return $('.row_selected').attr('value');
	};

	$("#applyAll")
	.button({
		icons: {
			primary: "ui-icon-check"
		}
	})
	.click(function() {
		var value = getSelectedRowValue();
		$.post(
			starexecRoot + "services/logging/" + value,
			{},
			parseReturnCode,
			"json"
		).fail(function() {
			showMessage("error", "Failed to update logging level", 5000);
		});
	})
	;

	$("#applyToClass")
	.button({
		icons: {
			primary: "ui-icon-check"
		}
	})
	.click(function() {
		var value = getSelectedRowValue();
		var className = $("#className").val();
		$.post(
			starexecRoot + "services/logging/" + value + "/" + className,
			{},
			parseReturnCode,
			"json"
		).fail(function() {
			showMessage("error", "Failed to update class logging level", 5000);
		});
	})
	;

	$("#applyToClassAllOthersOff")
	.button({
		icons: {
			primary: "ui-icon-check"
		}
	})
	.click(function() {
		var value = getSelectedRowValue();
		var className = $("#className").val();
		$.post(
			starexecRoot + "services/logging/allOffExcept/" + value + "/" + className,
			{},
			parseReturnCode,
			"json"
		).fail(function() {
			showMessage("error", "Failed to update logging configuration", 5000);
		});
	})
	;

	$("#tableLevels").on("click", "tr", function() {
		if (!$(this).hasClass("row_selected")) {
			$levelTable.find("tr").each(function() {
				$(this).removeClass("row_selected");
			});
		}
		$(this).toggleClass("row_selected");
	});

});
