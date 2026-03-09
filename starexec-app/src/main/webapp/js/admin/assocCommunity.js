$(document).ready(function() {
	initUI();

	// Remove all unselected rows from the DOM before submitting
	$('#addForm').submit(function() {
		// Ensure CSRF token is present (defensive: capture-phase listener in master.js may not run before this handler in some edge cases)
		var token = $('meta[name="csrf-token"]').attr("content");
		if (token && $('#addForm').find('input[name="csrfToken"]').length === 0) {
			$('#addForm').prepend($("<input>", { type: "hidden", name: "csrfToken", value: token }));
		}
		$('#tblCommunities tbody')
		.children('tr')
		.not('.row_selected')
		.find('input')
		.remove();
	});

});

function initUI() {

	$("#btnDone").button({
		icons: {
			primary: "ui-icon-locked"
		}
	});

	// Set up datatables
	$('#tblCommunities').dataTable({
		"sDom": 'rt<"bottom"f><"clear">',
		"bPaginate": false,
		"bSort": true
	});

	$("#tblCommunities").on("click", "tr", function() {
		$(this).toggleClass("row_selected");
	});

}

