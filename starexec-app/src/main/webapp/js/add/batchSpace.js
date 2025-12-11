$(document).ready(function() {
	initUI();
	attachFormValidation();
});

/**
 * Initialize user-interface buttons/actions
 */
function initUI() {
	$('#btnUpload').button({
		icons: {
			secondary: "ui-icon-arrowthick-1-n"
		}
	});

	$("#viewSchema").button({
		icons: {
			secondary: "ui-icon-document"
		}
	});

	$("#viewExample").button({
		icons: {
			secondary: "ui-icon-document"
		}
	});

}

/**
 * Attaches form validation to the file uploading field
 */
function attachFormValidation() {

	// Adds regular expression handling to the validator
	addValidators();

	// Re-validate the 'file location' field when it loses focus
	$("#fileUpload").change(function() {
		$("#fileUpload").blur().focus();
	});

	// Form validation rules/messages
	$("#upForm").validate({
		rules: {
			f: {
				required: true,
				regexIgnoreCase: "\\.(tgz|zip|tar(\\.gz)?|xml|txt)$"
			}
		},
		messages: {
			f: {
				required: "please select a file",
				regexIgnoreCase: ".zip, .tar, .tar.gz, .tgz, .xml, and .txt only"
			}
		},
		submitHandler: function(form) {
			createDialog(
				"Uploading XML, please wait. This will take some time for large files.");
			form.submit();
		}
	});
}
