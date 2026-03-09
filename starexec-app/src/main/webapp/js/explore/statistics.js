var statsTable;
var communityInfo;
var currentChart = null;
var lastUpdate = null;
var loadingMessage = "Please wait while the information is retrieved…";
var usersMessage = "a user is counted in a community if he/she is a member of at least one of that community's subspaces";
var solversMessage = "a solver is counted as part of a community if it appears in at least one of that community's subspaces, whether or not it appears in a community is based on id so if a solver is copied, it's counted as a new solver because it has a new id";
var benchesMessage = "a benchmark is counted as part of a community if it appears in at least one of that community's subspaces, whether or not it appears in a community is based on id so if a benchmark is copied, it's counted as a new benchmark because it has a new id";
var jobsMessage = "a job is counted as part of a community if it appears in at least one of that community's subspaces";
var jobPairsMessage = "a job pair is counted as part of a community if it appears in at least one of that community's subspaces";
var diskUseMessage = "disk use = space used by solvers + space used by benchmarks, where solvers and benchmarks belong to the given community";

// Human-readable labels for each graph type, used in chart title, x-axis, and dataset label
var labelMap = {
	users:      'Users',
	solvers:    'Solvers',
	benchmarks: 'Benchmarks',
	jobs:       'Jobs',
	job_pairs:  'Job Pairs',
	disk_usage: 'Disk Use (bytes)'
};

// Maps graph type to the corresponding button element ID for active-state management
var typeToButtonId = {
	users:      'compareUsers',
	solvers:    'compareSolvers',
	benchmarks: 'compareBenches',
	jobs:       'compareJobs',
	job_pairs:  'compareJobPairs',
	disk_usage: 'compareDiskUse'
};

function debounce(fn, delay) {
	var timer = null;
	return function() {
		var context = this, args = arguments;
		clearTimeout(timer);
		timer = setTimeout(function() {
			fn.apply(context, args);
		}, delay);
	};
}

function setActiveCompareBtn(type) {
	$('.compareBtn').removeClass('active').attr('aria-pressed', 'false');
	var btnId = typeToButtonId[type];
	if (btnId) {
		$('#' + btnId).addClass('active').attr('aria-pressed', 'true');
	}
}

jQuery(function($) {
	statsTable = $('#statsTable').dataTable(new star.DataTableConfig({
		"columnDefs": {"searchable": false, "type": "num"},
		"columns": [
			{"searchable": true, "type": "string"},
			null,
			null,
			null,
			null,
			null,
			{"sortable": false, "type": "num-fmt"}
		]
	}));

	$('.compareBtn').attr('aria-pressed', 'false');

	$('.expd').parent().expandable(true);

	$('#benchHeader').qtip({
		content: benchesMessage,
		show: "mouseover",
		hide: "mouseout"
	});

	$('.compareBtn').button({
		icons: { secondary: "ui-icon-refresh" }
	});

	// 16ms debounce = one animation frame at 60fps; absorbs accidental double-clicks
	// without introducing perceptible latency
	var debouncedChange = debounce(function(type) {
		changeCommunityOverviewGraph(type);
	}, 16);

	$('#compareBenches')
	.qtip({ content: benchesMessage, show: "mouseover", hide: "mouseout" })
	.click(function() { debouncedChange('benchmarks'); });

	$('#compareDiskUse')
	.qtip({ content: diskUseMessage, show: "mouseover", hide: "mouseout" })
	.click(function() { debouncedChange('disk_usage'); });

	$('#compareJobs')
	.qtip({ content: jobsMessage, show: "mouseover", hide: "mouseout" })
	.click(function() { debouncedChange('jobs'); });

	$('#compareJobPairs')
	.qtip({ content: jobPairsMessage, show: "mouseover", hide: "mouseout" })
	.click(function() { debouncedChange('job_pairs'); });

	$('#compareSolvers')
	.qtip({ content: solversMessage, show: "mouseover", hide: "mouseout" })
	.click(function() { debouncedChange('solvers'); });

	$('#compareUsers')
	.qtip({ content: usersMessage, show: "mouseover", hide: "mouseout" })
	.click(function() { debouncedChange('users'); });

	$('#diskUseHeader').qtip({ content: diskUseMessage, show: "mouseover", hide: "mouseout" });
	$('#solverHeader').qtip({ content: solversMessage, show: "mouseover", hide: "mouseout" });
	$('#jobHeader').qtip({ content: jobsMessage, show: "mouseover", hide: "mouseout" });
	$('#jobPairHeader').qtip({ content: jobPairsMessage, show: "mouseover", hide: "mouseout" });
	$('#userHeader').qtip({ content: usersMessage, show: "mouseover", hide: "mouseout" });

	$("#lastUpdate").text(loadingMessage);
	updateCommunityOverview();
});

function updateCommunityStatsTable(info) {
	var rows = [];
	$.each(info, function(key, value) {
		rows.push([
			key,
			value.users,
			value.solvers,
			value.benchmarks,
			value.jobs,
			value.job_pairs,
			value.disk_usage
		]);
	});
	statsTable.api().clear().rows.add(rows).draw();
}

function updateCommunityOverview() {
	$.ajax({
		url: starexecRoot + "services/secure/explore/community/overview",
		type: "POST",
		dataType: "text",
		success: function(data, status, xhr) {
			var contentType = xhr.getResponseHeader("Content-Type") || "";

			// Proxy/gateway errors (502, 504, etc.) typically return HTML, not JSON.
			// Bail early before attempting to parse to avoid a confusing SyntaxError.
			if (!contentType.includes("application/json")) {
				showMessage('error',
					"Unexpected server response. Please reload the page.",
					5000);
				$("#lastUpdate").text("There was a problem loading data. Please try reloading the page.");
				return;
			}

			var parsed;
			try {
				parsed = JSON.parse(data);
			} catch (e) {
				showMessage('error',
					"Invalid response from server. Please reload the page.",
					5000);
				$("#lastUpdate").text("There was a problem loading data. Please try reloading the page.");
				return;
			}

			if (parsed.success === false) {
				showMessage('error', parsed.message || "An error occurred. Please try again.", 5000);
				if (currentChart) {
					currentChart.destroy();
					currentChart = null;
				}
				$("#graph").hide();
				return;
			}

			communityInfo = parsed.info;
			updateCommunityStatsTable(parsed.info);
			changeCommunityOverviewGraph("users");
			$("#lastUpdate").text("Last Updated: " + parsed.date);
			$("#graph").show();
			$("#statsTableField").show();
		},
		error: function(xhr) {
			var msg = "An internal error occurred. Please try again.";
			if (xhr.status === 0) {
				msg = "Network error. Please check your connection and try again.";
			} else if (xhr.status >= 500) {
				msg = "Server error (" + xhr.status + "). Please try again later.";
			} else if (xhr.status === 403) {
				msg = "You do not have permission to view this data.";
			}
			showMessage('error', msg, 5000);
			$("#lastUpdate").text("There was a problem loading data. Please try reloading the page.");
		}
	});
}

function changeCommunityOverviewGraph(type) {
	if (!communityInfo) return;

	// disk_usage_bytes_str is a String representation of a Java Long, added to avoid
	// IEEE 754 precision loss for values > Number.MAX_SAFE_INTEGER (~9 PB).
	// Falls back to the numeric disk_usage_bytes field for older server responses.
	var dataProperty = (type === 'disk_usage') ? 'disk_usage_bytes_str' : type;

	var dataList = [];
	$.each(communityInfo, function(name, value) {
		var raw = value[dataProperty];
		if (raw === undefined) {
			raw = value['disk_usage_bytes'];
		}
		var numVal = (typeof raw === 'string') ? Number(raw) : (raw || 0);
		dataList.push({ name: name, val: isNaN(numVal) ? 0 : numVal });
	});

	dataList.sort(function(a, b) { return b.val - a.val; });

	if (dataList.length === 0) {
		if (currentChart) {
			currentChart.destroy();
			currentChart = null;
		}
		// Toggle visibility without destroying the canvas node. Destroying the canvas
		// would break subsequent Chart.js instantiation on the same element.
		$("#communityOverviewCanvas").hide();
		$("#communityEmptyState").show();
		setActiveCompareBtn(type);
		return;
	}

	$("#communityEmptyState").hide();
	$("#communityOverviewCanvas").show();

	var labels = [];
	var data = [];
	var otherSum = 0;
	for (var i = 0; i < dataList.length; i++) {
		if (i < 10) {
			labels.push(dataList[i].name);
			data.push(dataList[i].val);
		} else {
			otherSum += dataList[i].val;
		}
	}
	if (dataList.length > 10) {
		labels.push("Other");
		data.push(otherSum);
	}

	// Read colors from design tokens. getPropertyValue returns "" if the CSSOM is not
	// yet resolved (race condition on first paint), so explicit fallbacks are mandatory.
	// accentColor is expected to be a 6-char hex (#rrggbb); appending '80' produces a
	// valid 8-char hex (#rrggbbaa) with ~50% opacity, supported by Chart.js v4.
	var style = getComputedStyle(document.documentElement);
	var textColor   = style.getPropertyValue('--color-text-primary').trim() || '#f8f8f8';
	var accentColor = style.getPropertyValue('--color-accent-blue').trim()  || '#3b82f6';
	var barBg       = accentColor + '80';

	var friendlyLabel = labelMap[type] || type;
	var titleText = 'Comparing Communities by ' + friendlyLabel;

	if (currentChart) {
		currentChart.destroy();
	}

	var ctx = document.getElementById('communityOverviewCanvas').getContext('2d');
	currentChart = new Chart(ctx, {
		type: 'bar',
		data: {
			labels: labels,
			datasets: [{
				label: friendlyLabel,
				data: data,
				backgroundColor: barBg,
				borderColor: accentColor,
				borderWidth: 1
			}]
		},
		options: {
			indexAxis: 'y',
			maintainAspectRatio: false,
			responsive: true,
			plugins: {
				legend: { display: false },
				title: {
					display: true,
					text: titleText,
					color: textColor
				}
			},
			scales: {
				x: {
					beginAtZero: true,
					ticks: { color: textColor },
					title: {
						display: true,
						text: friendlyLabel,
						color: textColor
					}
				},
				y: {
					ticks: { color: textColor },
					title: {
						display: true,
						text: 'Community',
						color: textColor
					}
				}
			}
		}
	});

	setActiveCompareBtn(type);
}
