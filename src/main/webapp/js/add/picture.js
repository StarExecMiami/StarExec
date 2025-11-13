$(document).ready(function() {
	initUI();
	attachFormValidation();
});

/**
 * Initializes user-interface
 */
function initUI() {
	$('#btnUpload').button({
		icons: {
			secondary: "ui-icon-arrowthick-1-n"
		}
	});
}

/**
 * Attaches form validation to the picture upload field
 */
function attachFormValidation() {

	// Add regular expressions to the validator
	addValidators();

	// Re-validate the 'picture location' field when it loses focus
	$("#uploadPic").change(function() {
		$("#uploadPic").blur().focus();
	});

	// Form validation rules/messages
	$("#upForm").validate({
		rules: {
			f: {
				required: true,
				regexIgnoreCase: "\\.(jpe?g|png|gif|bmp)$"
			}
		},
		messages: {
			f: {
				required: "please select a file",
				regexIgnoreCase: "Please upload an image file (.jpg, .jpeg, .png, .gif, or .bmp)"
			}
		}
	});
}

