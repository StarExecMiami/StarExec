/* Set Status Message
 */

jQuery(function($) {
	var setUrl = starexecRoot + "services/admin/setStatusMessage";
	var getUrl = starexecRoot + "services/admin/getStatusMessage";

	$.get(
		getUrl,
		{},
		function(json) {
			log(json);
			$("#enabled").prop("checked", json.enabled);
			$("#message").val(json.message);
			$("#url").val(json.url);
		},
		"json"
	).fail(function() {
		showMessage("error", "Failed to load status message", 5000);
	});

	$("form").on("submit", function(event) {
		event.preventDefault();
		var json = {
			"enabled": $("#enabled").prop("checked"),
			"message": $("#message").val(),
			"url"    : $("#url").val()
		};
		$.post(setUrl, json, parseReturnCode, "json").fail(function() {
			showMessage("error", "Failed to save status message", 5000);
		});
		return false;
	});
});
