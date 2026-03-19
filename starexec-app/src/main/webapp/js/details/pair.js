jQuery(function($) {
	PR.prettyPrint();

	$('#pairTbl').dataTable({
		"sDom": getDataTablesDom()
	});

	$('#detailTable').dataTable({
		"sDom": 'rt<"bottom"f><"clear">',
		"aaSorting": [],
		"bPaginate": false,
		"bSort": true
	});

	$('#pairAttrs').dataTable({
		"sDom": 'rt<"bottom"f><"clear">',
		"bPaginate": false
	});

	$('#downLink').button({
		icons: {
			secondary: "ui-icon-arrowthick-1-s"
		}
	});

	$("#rerunPair").button({
		icons: {
			primary: "ui-icon-arrowrefresh-1-e"
		}
	});

	$("#rerunPair").click(function() {
		$.post(
			starexecRoot + "services/jobs/pairs/rerun/" + $("#pairId")
			.attr("value"),
			parseReturnCode,
			"json"
		);
	});

	$('#fieldDetails').expandable(false);
	$('.fieldStats').expandable(true);
	$('.fieldAttrs').expandable(true);
	$("#fieldActions").expandable(true);
	$('.fieldOutput').expandable(true);
	$('.stageStats').expandable(true);
	$('#fieldLog').expandable(true);

	// Hide loading images by default
	$('legend img').hide();

	initLiveJobLogStream();
});

function initLiveJobLogStream() {
	var pairIdElement = document.getElementById("pairId");
	var logElement = document.getElementById("jobLogContent");
	var badgeElement = document.getElementById("liveLogBadge");
	var autoScrollCheckbox = document.getElementById("liveLogAutoScroll");

	if (!pairIdElement || !logElement || !badgeElement || !autoScrollCheckbox) {
		return;
	}

	if (typeof window.EventSource === "undefined") {
		setLiveBadgeState(badgeElement, "stopped", "Live: unsupported");
		return;
	}

	var pairId = pairIdElement.getAttribute("value");
	if (!pairId) {
		setLiveBadgeState(badgeElement, "stopped", "Live: unavailable");
		return;
	}

	var streamUrl = starexecRoot + "services/jobs/pairs/" + encodeURIComponent(pairId) + "/log/stream";
	var eventSource = new EventSource(streamUrl);

	setLiveBadgeState(badgeElement, "reconnecting", "Live: connecting");

	eventSource.addEventListener("open", function() {
		setLiveBadgeState(badgeElement, "live", "Live: on");
	});

	eventSource.addEventListener("chunk", function(event) {
		var payload = safeJsonParse(event.data);
		if (!payload || typeof payload.text !== "string" || payload.text.length === 0) {
			return;
		}
		appendLogText(logElement, payload.text, autoScrollCheckbox.checked);
	});

	eventSource.addEventListener("complete", function() {
		setLiveBadgeState(badgeElement, "stopped", "Live: complete");
		eventSource.close();
	});

	eventSource.addEventListener("reset", function() {
		logElement.textContent = "";
		appendLogText(logElement, "[log stream reset]\n", autoScrollCheckbox.checked);
	});

	eventSource.addEventListener("error", function() {
		if (eventSource.readyState === EventSource.CLOSED) {
			setLiveBadgeState(badgeElement, "stopped", "Live: unavailable");
			return;
		}
		setLiveBadgeState(badgeElement, "reconnecting", "Live: reconnecting");
	});
}

function appendLogText(logElement, text, autoScroll) {
	logElement.textContent += text;
	if (autoScroll) {
		logElement.scrollTop = logElement.scrollHeight;
	}
}

function safeJsonParse(input) {
	try {
		return JSON.parse(input);
	} catch (e) {
		return null;
	}
}

function setLiveBadgeState(badgeElement, state, text) {
	badgeElement.classList.remove(
		"liveLogBadge--idle",
		"liveLogBadge--live",
		"liveLogBadge--reconnecting",
		"liveLogBadge--stopped"
	);
	badgeElement.classList.add("liveLogBadge--" + state);
	badgeElement.textContent = text;
}
