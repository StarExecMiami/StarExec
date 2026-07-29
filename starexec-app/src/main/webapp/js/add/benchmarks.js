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

var resumableUploadState = null;
var RESUMABLE_UPLOAD_STORAGE_PREFIX = 'starexec-benchmark-upload:';

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

function isLocalUploadSelected() {
	return $("#radioLocal").is(":checked");
}

function formatBytes(bytes) {
	if (bytes < 1024) {
		return bytes + ' B';
	}
	if (bytes < 1024 * 1024) {
		return (bytes / 1024).toFixed(1) + ' KB';
	}
	if (bytes < 1024 * 1024 * 1024) {
		return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
	}
	return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}

function getSelectedFile() {
	var input = document.getElementById('benchFile');
	if (!input || !input.files || input.files.length === 0) {
		return null;
	}
	return input.files[0];
}

function buildResumableUploadPayload() {
	var hasDependencies = $('input[name="dependency"]:checked').val() === 'true';
	var depRootValue = hasDependencies ? $('#depRoot').val() : null;
	return {
		fileName: getSelectedFile().name,
		totalBytes: getSelectedFile().size,
		spaceId: parseInt($('input[name="space"]').val(), 10),
		uploadMethod: $('input[name="upMethod"]:checked').val(),
		benchmarkTypeId: parseInt($('#benchType').val(), 10),
		downloadable: $('input[name="download"]:checked').val() === 'true',
		hasDependencies: hasDependencies,
		depRootSpaceId: depRootValue ? parseInt(depRootValue, 10) : null,
		linked: hasDependencies && $('input[name="linked"]:checked').val() === 'true'
	};
}

function buildUploadFingerprint(file, payload) {
	return [
		file.name,
		file.size,
		file.lastModified,
		payload.spaceId,
		payload.uploadMethod,
		payload.benchmarkTypeId,
		payload.downloadable,
		payload.hasDependencies,
		payload.depRootSpaceId || '',
		payload.linked
	].join('|');
}

function getSessionStorageKey(fingerprint) {
	return RESUMABLE_UPLOAD_STORAGE_PREFIX + fingerprint;
}

function readStoredSession(key) {
	try {
		var raw = window.localStorage.getItem(key);
		return raw ? JSON.parse(raw) : null;
	} catch (e) {
		return null;
	}
}

function persistStoredSession(state) {
	try {
		window.localStorage.setItem(state.storageKey, JSON.stringify({
			sessionId: state.sessionId,
			fingerprint: state.fingerprint,
			fileName: state.file.name,
			totalBytes: state.file.size
		}));
	} catch (e) {
		// Ignore localStorage failures and continue with the live upload.
	}
}

function clearStoredSession(state) {
	if (!state || !state.storageKey) {
		return;
	}
	try {
		window.localStorage.removeItem(state.storageKey);
	} catch (e) {
		// Ignore localStorage failures.
	}
}

function getErrorMessage(xhr, fallback) {
	try {
		var response = JSON.parse(xhr.responseText);
		return response.message || fallback;
	} catch (e) {
		return fallback;
	}
}

function showResumableUploadError(message) {
	destroyDialog();
	alert(message);
}

function ensureUploadProgressDialog() {
	createDialog('Uploading file to server...');
	$('#uploadProgressContainer').remove();
	var $container = $('<div id="uploadProgressContainer" style="padding: 10px; text-align: center; width: 100%; box-sizing: border-box;">' +
		'<div id="uploadProgressText">Preparing upload...</div>' +
		'<div id="uploadProgressDetail" style="margin-top: 6px; color: #666;"></div>' +
		'<div id="uploadProgressBar" style="width: 100%; height: 20px; border: 1px solid #ccc; margin-top: 10px; background: #fff; border-radius: 3px; box-sizing: border-box; overflow: hidden;">' +
			'<div id="uploadProgressBarFill" style="height: 100%; width: 0%; background: #4CAF50; transition: width 0.2s;"></div>' +
		'</div>' +
		'<div id="uploadControlRow" style="margin-top: 12px; display: flex; gap: 8px; justify-content: center;">' +
			'<button id="uploadPauseBtn" type="button">Pause</button>' +
			'<button id="uploadResumeBtn" type="button" style="display: none;">Resume</button>' +
			'<button id="uploadStopBtn" type="button">Stop</button>' +
		'</div>' +
		'</div>');
	setTimeout(function() {
		$('.ui-dialog-content').append($container);
		$('#uploadPauseBtn').off('click').on('click', pauseResumableUpload);
		$('#uploadResumeBtn').off('click').on('click', resumeResumableUpload);
		$('#uploadStopBtn').off('click').on('click', stopResumableUpload);
	}, 50);
}

function updateUploadProgressUi(message, detail, percent) {
	$('#uploadProgressText').text(message);
	$('#uploadProgressDetail').text(detail || '');
	$('#uploadProgressBarFill').css('width', Math.max(0, Math.min(100, percent)) + '%');
}

function updateUploadControlState() {
	if (!resumableUploadState) {
		return;
	}
	var isPaused = resumableUploadState.isPaused;
	var isFinalizing = resumableUploadState.isFinalizing;
	$('#uploadPauseBtn').toggle(!isPaused && !isFinalizing);
	$('#uploadResumeBtn').toggle(isPaused && !isFinalizing);
	$('#uploadStopBtn').prop('disabled', isFinalizing);
}

function syncStateFromSession(session) {
	resumableUploadState.sessionId = session.id;
	resumableUploadState.chunkSize = session.chunkSize;
	resumableUploadState.totalChunks = session.totalChunks;
	resumableUploadState.nextChunkIndex = session.nextChunkIndex;
	resumableUploadState.bytesReceived = session.bytesReceived;
	persistStoredSession(resumableUploadState);
}

function updateUiFromSession(session, overrideMessage) {
	var message = overrideMessage || 'Uploading archive...';
	if (session.status === 'READY') {
		message = 'Upload finished. Finalizing on server...';
	} else if (session.status === 'COMPLETE' && session.jobId) {
		message = 'Upload transferred. Redirecting to processing status...';
	}
	updateUploadProgressUi(
		message,
		'Chunk ' + session.nextChunkIndex + ' of ' + session.totalChunks + ' | ' +
			formatBytes(session.bytesReceived) + ' of ' + formatBytes(session.totalBytes),
		session.progressPercentage
	);
}

function fetchUploadSession(sessionId) {
	return $.getJSON(joinPath(starexecRoot, '/services/uploads/sessions/') + sessionId);
}

function createUploadSession(payload) {
	return $.ajax({
		url: joinPath(starexecRoot, '/services/uploads/sessions'),
		type: 'POST',
		data: JSON.stringify(payload),
		contentType: 'application/json',
		dataType: 'json'
	});
}

function finalizeUploadSessionRequest(sessionId) {
	return $.ajax({
		url: joinPath(starexecRoot, '/services/uploads/sessions/') + sessionId + '/finalize',
		type: 'POST',
		dataType: 'json'
	});
}

function abortUploadSessionRequest(sessionId) {
	return $.ajax({
		url: joinPath(starexecRoot, '/services/uploads/sessions/') + sessionId + '/abort',
		type: 'POST',
		dataType: 'json'
	});
}

function resumeOrCreateUploadSession() {
	var stored = readStoredSession(resumableUploadState.storageKey);
	if (!stored || !stored.sessionId) {
		return createUploadSession(resumableUploadState.payload);
	}

	return fetchUploadSession(stored.sessionId).then(function(session) {
		if (!session.success || session.totalBytes !== resumableUploadState.file.size) {
			clearStoredSession(resumableUploadState);
			return createUploadSession(resumableUploadState.payload);
		}
		return session;
	}, function() {
		clearStoredSession(resumableUploadState);
		return createUploadSession(resumableUploadState.payload);
	});
}

function finalizeResumableUpload() {
	if (!resumableUploadState || resumableUploadState.isFinalizing) {
		return;
	}
	resumableUploadState.isFinalizing = true;
	updateUploadControlState();
	updateUploadProgressUi(
		'Upload finished. Finalizing on server...',
		'Preparing async processing job for ' + resumableUploadState.file.name,
		100
	);
	finalizeUploadSessionRequest(resumableUploadState.sessionId)
		.done(function(response) {
			if (!response.success || !response.jobId) {
				resumableUploadState.isFinalizing = false;
				updateUploadControlState();
				showResumableUploadError(response.message || 'Failed to finalize upload session');
				return;
			}
			clearStoredSession(resumableUploadState);
			updateUploadProgressUi('Upload transferred. Redirecting...', 'Opening processing status page', 100);
			window.location.href = joinPath(starexecRoot, '/secure/details/uploadStatus.jsp?id=' + response.jobId);
		})
		.fail(function(xhr) {
			resumableUploadState.isFinalizing = false;
			updateUploadControlState();
			showResumableUploadError(getErrorMessage(xhr, 'Failed to finalize upload session'));
		});
}

function uploadNextChunk() {
	if (!resumableUploadState || resumableUploadState.isPaused || resumableUploadState.isStopped) {
		return;
	}
	if (resumableUploadState.nextChunkIndex >= resumableUploadState.totalChunks) {
		finalizeResumableUpload();
		return;
	}

	var chunkIndex = resumableUploadState.nextChunkIndex;
	var start = chunkIndex * resumableUploadState.chunkSize;
	var end = Math.min(resumableUploadState.file.size, start + resumableUploadState.chunkSize);
	var chunk = resumableUploadState.file.slice(start, end);

	updateUploadProgressUi(
		'Uploading chunk ' + (chunkIndex + 1) + ' of ' + resumableUploadState.totalChunks + '...',
		formatBytes(start) + ' of ' + formatBytes(resumableUploadState.file.size) + ' uploaded',
		Math.round((start * 100) / resumableUploadState.file.size)
	);
	updateUploadControlState();

	resumableUploadState.xhr = $.ajax({
		url: joinPath(starexecRoot, '/services/uploads/sessions/') +
			resumableUploadState.sessionId + '/chunks/' + chunkIndex,
		type: 'PUT',
		data: chunk,
		processData: false,
		contentType: 'application/octet-stream',
		dataType: 'json',
		xhr: function() {
			var xhr = new XMLHttpRequest();
			xhr.upload.addEventListener('progress', function(e) {
				if (!e.lengthComputable || !resumableUploadState) {
					return;
				}
				var uploadedBytes = Math.min(resumableUploadState.file.size, start + e.loaded);
				updateUploadProgressUi(
					'Uploading chunk ' + (chunkIndex + 1) + ' of ' + resumableUploadState.totalChunks + '...',
					formatBytes(uploadedBytes) + ' of ' + formatBytes(resumableUploadState.file.size) + ' uploaded',
					Math.round((uploadedBytes * 100) / resumableUploadState.file.size)
				);
			});
			return xhr;
		}
	}).done(function(session) {
		resumableUploadState.xhr = null;
		if (!session.success) {
			showResumableUploadError(session.message || 'Upload chunk failed');
			return;
		}
		syncStateFromSession(session);
		updateUiFromSession(session);
		if (session.status === 'READY' || resumableUploadState.nextChunkIndex >= resumableUploadState.totalChunks) {
			finalizeResumableUpload();
		} else {
			uploadNextChunk();
		}
	}).fail(function(xhr, status) {
		resumableUploadState.xhr = null;
		if (status === 'abort' && (resumableUploadState.isPaused || resumableUploadState.isStopped)) {
			return;
		}
		fetchUploadSession(resumableUploadState.sessionId)
			.done(function(session) {
				if (session.success) {
					syncStateFromSession(session);
					updateUiFromSession(session, resumableUploadState.isPaused ? 'Upload paused.' : 'Resuming upload...');
					if (!resumableUploadState.isPaused && !resumableUploadState.isStopped) {
						if (session.status === 'READY') {
							finalizeResumableUpload();
						} else {
							uploadNextChunk();
						}
					}
				} else {
					showResumableUploadError(session.message || 'Upload failed while recovering session state');
				}
			})
			.fail(function() {
				showResumableUploadError(getErrorMessage(xhr, 'Upload failed while sending a chunk'));
			});
	});
}

function pauseResumableUpload() {
	if (!resumableUploadState || !resumableUploadState.sessionId) {
		return;
	}
	resumableUploadState.isPaused = true;
	if (resumableUploadState.xhr) {
		resumableUploadState.xhr.abort();
	}
	updateUploadProgressUi(
		'Upload paused.',
		'Resume to continue from chunk ' + (resumableUploadState.nextChunkIndex + 1),
		Math.round((resumableUploadState.bytesReceived * 100) / resumableUploadState.file.size)
	);
	updateUploadControlState();
}

function resumeResumableUpload() {
	if (!resumableUploadState || !resumableUploadState.sessionId) {
		return;
	}
	fetchUploadSession(resumableUploadState.sessionId)
		.done(function(session) {
			if (!session.success) {
				showResumableUploadError(session.message || 'Could not resume upload session');
				return;
			}
			resumableUploadState.isPaused = false;
			syncStateFromSession(session);
			updateUiFromSession(session, 'Resuming upload...');
			updateUploadControlState();
			if (session.status === 'READY') {
				finalizeResumableUpload();
			} else {
				uploadNextChunk();
			}
		})
		.fail(function(xhr) {
			showResumableUploadError(getErrorMessage(xhr, 'Could not resume upload session'));
		});
}

function stopResumableUpload() {
	if (!resumableUploadState) {
		return;
	}
	resumableUploadState.isStopped = true;
	if (resumableUploadState.xhr) {
		resumableUploadState.xhr.abort();
	}
	if (!resumableUploadState.sessionId) {
		clearStoredSession(resumableUploadState);
		destroyDialog();
		alert('Upload stopped.');
		return;
	}
	abortUploadSessionRequest(resumableUploadState.sessionId).always(function() {
		clearStoredSession(resumableUploadState);
		destroyDialog();
		alert('Upload stopped.');
	});
}

function submitLegacyUpload(form) {
	var formData = new FormData(form);
	$.ajax({
		url: form.action,
		type: 'POST',
		data: formData,
		processData: false,
		contentType: false,
		dataType: 'json',
		xhr: function() {
			var xhr = new XMLHttpRequest();
			xhr.upload.addEventListener('progress', function(e) {
				if (e.lengthComputable) {
					var percent = Math.round((e.loaded / e.total) * 100);
					updateUploadProgressUi('Uploading...', percent + '%', percent);
				}
			});
			return xhr;
		},
		success: function(response) {
			destroyDialog();
			if (response && response.jobId) {
				window.location.href = joinPath(starexecRoot, '/secure/details/uploadStatus.jsp?id=' + response.jobId);
			} else {
				alert('Upload failed: No job ID returned');
			}
		},
		error: function(xhr, status, error) {
			destroyDialog();
			alert(getErrorMessage(xhr, 'Upload failed: ' + error));
		}
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

	//initially hide URL/Git inputs since local file is the default
	$('#fileURL').fadeOut(0);
	$('#gitURL').fadeOut(0);

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

	ensureUploadProgressDialog();

	if (!isLocalUploadSelected()) {
		updateUploadProgressUi('Uploading file to server...', 'Using legacy single-request flow for URL/Git uploads', 0);
		submitLegacyUpload(form);
		return false;
	}

	var file = getSelectedFile();
	if (!file) {
		destroyDialog();
		alert('Please select a local archive to upload.');
		return false;
	}

	var payload = buildResumableUploadPayload();
	var fingerprint = buildUploadFingerprint(file, payload);
	resumableUploadState = {
		file: file,
		payload: payload,
		fingerprint: fingerprint,
		storageKey: getSessionStorageKey(fingerprint),
		sessionId: null,
		chunkSize: 0,
		totalChunks: 0,
		nextChunkIndex: 0,
		bytesReceived: 0,
		xhr: null,
		isPaused: false,
		isStopped: false,
		isFinalizing: false
	};

	updateUploadProgressUi('Preparing resumable upload...', file.name + ' (' + formatBytes(file.size) + ')', 0);
	updateUploadControlState();
	resumeOrCreateUploadSession()
		.done(function(session) {
			if (!session.success) {
				destroyDialog();
				alert(session.message || 'Failed to create upload session');
				return;
			}
			syncStateFromSession(session);
			updateUiFromSession(session, session.nextChunkIndex > 0 ? 'Resuming previous upload...' : 'Starting upload...');
			if (session.status === 'COMPLETE' && session.jobId) {
				clearStoredSession(resumableUploadState);
				window.location.href = joinPath(starexecRoot, '/secure/details/uploadStatus.jsp?id=' + session.jobId);
				return;
			}
			if (session.status === 'READY') {
				finalizeResumableUpload();
				return;
			}
			uploadNextChunk();
		})
		.fail(function(xhr) {
			destroyDialog();
			alert(getErrorMessage(xhr, 'Failed to start upload session'));
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
