$(document).ready(function() {
	initUI();
	attachFormValidation();
	DepEnb = $("#selectDep").attr("default");
	if (DepEnb == "1") {
		$('#depSpaces').show();
		$('#depLinked').show();
		$("#radioDependency").attr("checked", "checked");
	} else {

		$('#depSpaces').hide();
		$('#depLinked').hide();
		$("#radioNoDependency").attr("checked", "checked");
	}
});

var uploadJobId = null;
var pollInterval = null;
var pollIntervalMs = 2000;
var maxPollIntervalMs = 10000;

// Helper function to properly join URL paths (avoids double slashes)
function joinPath(base, path) {
	if (!base) return path;
	if (!path) return base;
	// Remove trailing slash from base, leading slash from path
	base = base.replace(/\/$/, '');
	path = path.replace(/^\//, '');
	var result = base + '/' + path;
	console.log('joinPath: base="' + base + '", path="' + path + '", result="' + result + '"');
	return result;
}

function startUploadProgressPolling(jobId) {
	uploadJobId = jobId;
	window.uploadPollFailures = 0; // Reset failure counter
	// Small delay before first poll to allow job to be committed to database
	setTimeout(function() {
		updateUploadProgress();
		pollInterval = setInterval(function() {
			updateUploadProgress();
		}, pollIntervalMs);
	}, 500);
}

function updateUploadProgress() {
	$.getJSON(
		joinPath(starexecRoot, '/services/uploads/jobs/') + uploadJobId,
		function(data) {
			window.uploadPollFailures = 0; // Reset on successful response
			var progress = data.progressPercentage;
			var processed = data.totalFilesProcessed;
			var found = data.totalFilesFound;
			var status = data.status;

			if (found === 0) {
				$('#uploadProgressText').text('Scanning archive...');
				$('#uploadProgressBarFill').css('width', '0%');
			} else {
				$('#uploadProgressText').text(
					processed + ' of ' + found + ' benchmarks validated (' + progress + '%)'
				);
				$('#uploadProgressBarFill').css('width', progress + '%');
			}

			if (status === 'COMPLETED') {
				clearInterval(pollInterval);
				$('#uploadProgressText').text('Upload complete! ' + processed + ' benchmarks validated.');
				$('#uploadProgressBarFill').css('width', '100%');
				setTimeout(function() {
					window.location.href = joinPath(starexecRoot, '/secure/details/uploadStatus.jsp?id=' + uploadJobId);
				}, 1500);
			} else if (status === 'FAILED') {
				clearInterval(pollInterval);
				$('#uploadProgressText').html(
					'<span style="color: red;">Upload failed: ' + data.errorMessage + '</span>'
				);
				$('#uploadProgressText').append('<br/><br/>Please fix the issue and try again.');
			} else if (status === 'PENDING') {
				$('#uploadProgressText').text('Waiting for upload to start...');
			} else {
				if (found > 1000 && pollIntervalMs < maxPollIntervalMs) {
					pollIntervalMs = Math.min(pollIntervalMs + 500, maxPollIntervalMs);
					clearInterval(pollInterval);
					pollInterval = setInterval(updateUploadProgress, pollIntervalMs);
				}
			}
		}
	).fail(function(xhr) {
		// Handle transient errors gracefully - job may not be visible yet due to timing
		// Only fail after multiple consecutive failures
		if (typeof window.uploadPollFailures === 'undefined') {
			window.uploadPollFailures = 0;
		}
		window.uploadPollFailures++;
		
		// Allow up to 5 transient failures before showing error
		if (window.uploadPollFailures <= 5) {
			// Continue polling - job should become visible shortly
			return;
		}
		
		// After too many failures, show error
		clearInterval(pollInterval);
		window.uploadPollFailures = 0;
		$('#uploadProgressText').html('<span style="color: red;">Error retrieving upload status.</span>');
	});
}

/**
 * Attach validation to the benchmark upload form
 */
function attachFormValidation() {

	// Add 'regex' method to JQuery validator
	addValidators();

	$("#radioLocal").change(function() {
		if ($("#radioLocal").is(":checked")) {
			$("#fileURL").stop(true, true, true);
			$("#gitURL").stop(true, true, true);
			$("#fileURL").fadeOut('fast', function() {
				if ($("#radioLocal").is(":checked")) {
					$("#benchFile").fadeIn('fast');
					$("#uploadForm").validate().element("#fileURL");
				}
			});
			$("#gitURL").fadeOut('fast', function() {
				if ($("#radioLocal").is(":checked")) {
					$("#benchFile").fadeIn('fast');
					$("#uploadForm").validate().element("#gitURL");
				}
			});

		}
	});

	$("#radioURL").change(function() {
		if ($("#radioURL").is(":checked")) {
			$("#benchFile").stop(true, true, true);
			$("#gitURL").stop(true, true, true);
			$("#benchFile").fadeOut('fast', function() {
				if ($("#radioURL").is(":checked")) {
					$("#fileURL").fadeIn('fast');
					$("#uploadForm").validate().element("#benchFile");
				}
			});
			$("#gitURL").fadeOut('fast', function() {
				if ($("#radioURL").is(":checked")) {
					$("#fileURL").fadeIn('fast');
					$("#uploadForm").validate().element("#gitURL");
				}
			});

		}
	});
	//button for git
	$("#radioGit").change(function() {
		if ($("#radioGit").is(":checked")) {
			$("#fileURL").stop(true, true, true);
			$("#benchFile").stop(true, true, true);
			$("#benchFile").fadeOut('fast', function() {
				if ($("#radioGit").is(":checked")) {
					$("#gitURL").fadeIn('fast');
					$("#uploadForm").validate().element("#benchFile");
				}
			});
			$("#fileURL").fadeOut('fast', function() {
				if ($("#radioGit").is(":checked")) {
					$("#gitURL").fadeIn('fast');
					$("#uploadForm").validate().element("#fileURL");
				}
			});
		}
	});

	// Re-validate the 'file location' field when it loses focus
	$("#benchFile").change(function() {
		$("#benchFile").blur().focus();
	});

	//initially hide dependency related fields
	$('#depSpaces').fadeOut(0);
	$('#depLinked').fadeOut(0);

	// Form validation rules/messages
	$("#uploadForm").validate({
		rules: {
			benchFile: {
				required: "#radioLocal:checked",
				regex: "(\.tgz$)|(\.zip$)|(\.tar(\.gz)?$)"
			},
			url: {
				required: "#radioURL:checked",
				regex: "(\.tgz$)|(\.zip$)|(\.tar(\.gz)?$)"
			},
			git: {
				required: "#radioGit:checked",
				regex: "(\.git$)"
			}
		},
		messages: {
			benchFile: {
				required: "please select a file",
				regex: ".zip, .tar and .tar.gz only"
			},
			url: {
				required: "please enter a URL",
				regex: "URL must be .zip, .tar, or .tar.gz"
			},
			git: {
				required: "please enter a URL of a Git repository ",
				regex: "URL must be .git"
			}
		}
	});
}

/**
 * Direct handler for upload button click - bypasses jQuery Validate
 */
function handleUploadClick(event) {
	event.preventDefault();
	
	var form = document.getElementById('uploadForm');
	
	// Validate using jQuery Validate if available
	if ($('#uploadForm').validate && !$('#uploadForm').valid()) {
		return false;
	}
	
	// Create progress dialog
	createDialog('Uploading file to server...');
	
	// Add progress bar to the dialog
	$('#uploadProgressContainer').remove();
	var $container = $('<div id="uploadProgressContainer" style="padding: 10px; text-align: center; width: 100%; box-sizing: border-box;">' +
		'<div id="uploadProgressText">Preparing upload...</div>' +
		'<div id="uploadProgressBar" style="width: 100%; height: 20px; border: 1px solid #ccc; margin-top: 10px; background: #fff; border-radius: 3px; box-sizing: border-box; overflow: hidden;">' +
		'<div id="uploadProgressBarFill" style="height: 100%; width: 0%; background: #4CAF50; transition: width 0.3s;"></div>' +
		'</div>' +
		'</div>');
	
	// Wait for dialog to be created, then add our content
	setTimeout(function() {
		$('.ui-dialog-content').append($container);
	}, 100);

	var formData = new FormData(form);

	$.ajax({
		url: form.action,
		type: 'POST',
		data: formData,
		processData: false,
		contentType: false,
		dataType: 'json',
		// Track file upload progress
		xhr: function() {
			var xhr = new XMLHttpRequest();
			xhr.upload.addEventListener('progress', function(e) {
				if (e.lengthComputable) {
					var percent = Math.round((e.loaded / e.total) * 100);
					$('#uploadProgressBarFill').css('width', percent + '%');
					$('#uploadProgressText').text('Uploading... ' + percent + '%');
				}
			});
			return xhr;
		},
		success: function(response) {
			console.log('Upload response:', response);
			destroyDialog();
			if (response && response.jobId) {
				// Redirect to upload status page for cleaner UX
				// The status page will handle polling for progress
				window.location.href = joinPath(starexecRoot, '/secure/details/uploadStatus.jsp?id=' + response.jobId);
			} else {
				alert('Upload failed: No job ID returned');
			}
		},
		error: function(xhr, status, error) {
			destroyDialog();
			console.log('Upload error:', status, error, xhr.responseText);
			var msg = 'Upload failed: ' + error;
			try {
				var response = JSON.parse(xhr.responseText);
				msg += ' - ' + (response.message || xhr.statusText);
			} catch (e) {
				msg += ' - ' + xhr.statusText;
			}
			alert(msg);
		}
	});

	return false;
}

/**
 * Initializes user-interface
 */
function initUI() {
	$('#radioConvert').change(function() {
		if ($('#radioConvert').is(':checked')) {
			$('#permRow').fadeIn('fast');
		}
	});

	$('#radioDump').change(function() {
		if ($('#radioDump').is(':checked')) {
			$('#permRow').fadeOut('fast');
		}
	});

	$('#radioDependency').change(function() {
		if ($('#radioDependency').is(':checked')) {
			$('#depSpaces').fadeIn('fast');
			$('#depLinked').fadeIn('fast');
		}
	});

	$('#radioNoDependency').change(function() {
		if ($('#radioNoDependency').is(':checked')) {
			$('#depSpaces').fadeOut('fast');
			$('#depLinked').fadeOut('fast');
		}
	});

	$('#btnUpload').button({
		icons: {
			secondary: "ui-icon-arrowthick-1-n"
		}
	});

	$('#btnPrev').button({
		icons: {
			primary: "ui-icon-arrowthick-1-w"
		}
	}).click(function(e) {
		e.preventDefault();
		history.back(-1);
	});

}
