var jobPairTable;
var qid = 0; // ID of the selected queue, or the queue that owns the selected node
var selectedId = 0;  // ID of the selected primitive
var qIdForQueueGraph = 1; // queue id used to select which queuegraph image is displayed; default is 1 because
                          // all.q always exists

/**
 * StarExec QueueGraph Module
 * Handles periodic updates and display of the enqueued pairs graph.
 */
star.QueueGraph = (function($) {
    var config = {
        elementId: "#queuegraph",
        initialInterval: 5000,     // Start at 5s
        minInterval: 5000,         // Min: 5s
        maxInterval: 60000,        // Max: 60s (1 minute)
        backoffMultiplier: 1.5,
        idleThreshold: 3,
        chart: null,
        dataPoints: 24             // Default time window in hours
    };

    var state = {
        queueId: 1,
        currentInterval: config.initialInterval,
        consecutiveEmpty: 0,
        timer: null,
        isUpdating: false
    };

    function fetchData() {
        return $.ajax({
            url: starexecRoot + "services/cluster/queues/" + state.queueId + "/metrics/history",
            data: { windowHours: config.dataPoints }, // Pass the selected time window to the backend
            dataType: "json"
        });
    }

    function updateChart() {
        if (state.isUpdating) return;
        state.isUpdating = true;

        fetchData()
            .done(function(response) {
                var hasData = response.data && response.data.length > 0;

                if (hasData) {
                    state.consecutiveEmpty = 0;
                    state.currentInterval = config.initialInterval;
                    renderChart(response.data);
                } else {
                    state.consecutiveEmpty++;
                    if (state.consecutiveEmpty > config.idleThreshold) {
                        // Backoff: increase interval
                        state.currentInterval = Math.min(
                            state.currentInterval * config.backoffMultiplier,
                            config.maxInterval
                        );
                    }
                }
            })
            .always(function() {
                state.isUpdating = false;
                scheduleNext();
            });
    }

    function scheduleNext() {
        clearTimeout(state.timer);
        state.timer = setTimeout(updateChart, state.currentInterval);
    }

    function renderChart(dataPoints) {
        var ctx = $(config.elementId)[0].getContext('2d');
        var labels = dataPoints.map(function(p) {
            return new Date(p.time).toLocaleTimeString();
        });
        var sizes = dataPoints.map(function(p) { return p.size; });

        if (config.chart) {
            config.chart.data.labels = labels;
            config.chart.data.datasets[0].data = sizes;
            config.chart.update('none'); // No animation for performance
        } else {
            // StarExec dark theme colors from _colors.scss
            var textColor = '#f8f8f8';
            var axisColor = '#999999';
            var gridColor = '#2d2d2d';
            var accentColor = '#fa621b'; // $accent-orange
            var accentFill = 'rgba(250, 98, 27, 0.2)';

            // Make the Chart background transparent so the dark container shows through
            Chart.defaults.backgroundColor = 'transparent';

            config.chart = new Chart(ctx, {
                type: 'line',
                data: {
                    labels: labels,
                    datasets: [{
                        label: 'Enqueued Pairs',
                        data: sizes,
                        borderColor: accentColor,
                        tension: 0.3,
                        fill: true,
                        backgroundColor: accentFill,
                        pointBackgroundColor: accentColor,
                        pointBorderColor: '#0a0a0a',
                        pointRadius: 3,
                        pointHoverRadius: 6,
                        pointHoverBackgroundColor: '#fff'
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    animation: { duration: 0 },
                    color: textColor,
                    layout: {
                        padding: 10
                    },
                    plugins: {
                        legend: { 
                            display: true,
                            labels: { color: textColor }
                        },
                        tooltip: { 
                            mode: 'index', 
                            intersect: false,
                            backgroundColor: 'rgba(31, 31, 31, 0.9)', // $foreground-color
                            titleColor: textColor,
                            bodyColor: textColor,
                            borderColor: gridColor,
                            borderWidth: 1
                        }
                    },
                    scales: {
                        x: {
                            display: true,
                            title: {
                                display: true,
                                text: 'Time',
                                color: axisColor
                            },
                            ticks: { 
                                maxTicksLimit: 10,
                                color: axisColor 
                            },
                            grid: {
                                color: gridColor,
                                drawBorder: false
                            }
                        },
                        y: {
                            display: true,
                            beginAtZero: true,
                            title: {
                                display: true,
                                text: 'Number of Enqueued Pairs',
                                color: axisColor
                            },
                            ticks: { 
                                color: axisColor 
                            },
                            grid: {
                                color: gridColor,
                                drawBorder: false
                            }
                        }
                    }
                }
            });
        }
    }

    return {
        init: function(queueId) {
            if (queueId) state.queueId = queueId;
            
            // Add event listener to static select menu
            $("#timeWindowSelect").off("change").on("change", function() {
                config.dataPoints = parseInt($(this).val());
                state.currentInterval = config.initialInterval;
                clearTimeout(state.timer);
                updateChart();
            });
            
            updateChart();
        },
        setQueue: function(queueId) {
            if (state.queueId !== queueId) {
                state.queueId = queueId;
                state.currentInterval = config.initialInterval;
                clearTimeout(state.timer);
                updateChart();
            }
        },
        refresh: function() {
            state.currentInterval = config.initialInterval;
            updateChart();
        }
    };
})(jQuery);

// Compatibility function for existing calls
function updateQueueGraph() {
    star.QueueGraph.setQueue(qIdForQueueGraph);
}

// When the document is ready to be executed on
$(document).ready(function() {
    // Start the queue graph updates
    star.QueueGraph.init(qIdForQueueGraph);

	initDataTables();

	//Set up row click to send to pair details page
	$('#details tbody').on("click", "a", function(event) {
		event.stopPropagation();
	});

	$("#details tbody").on("click", "tr", function(event) {
		if (jobPairTable.DataTable().data().length > 0) {
			var pairId = $(this).find('input').val();
			var url = starexecRoot + "secure/details/pair.jsp?id=" + pairId;
			if (event.ctrlKey || event.metaKey) {
				window.open(url, "_blank").focus();
			} else {
				window.location.assign(url);
			}
		}
	});

	// Build left-hand side of page (cluster explorer)
	initClusterExplorer();
	loadQstatOutput();

	$("#refreshQstat")
	.button({
		icons: {
			primary: "ui-icon-refresh"
		}
	})
	.click(
		loadQstatOutput
	)
	;

	$("#refreshLoads")
	.button({
		icons: {
			primary: "ui-icon-refresh"
		}
	})
	.click(
		loadQueueLoads
	)
	;

	$("#qstatField").expandable(true);
	$("#loadsField").expandable(true);
	$("#detailField").expandable(true);

	setInterval(function() {
		jobPairTable.fnDraw(false);
	}, 10000);
});

function initClusterExplorer() {
	// Set the path to the css theme for the jstree plugin
	$.jstree._themes = starexecRoot + "css/jstree/";

	$("#exploreList").bind("loaded.jstree", function(e, data) {
		addNodeCountsToTree();
		// Sync graph with current selection (e.g. from cookie) so initial view matches selected queue
		var sel = $("#exploreList").jstree("get_selected", true);
		if (sel && sel.length > 0) {
			var node = sel[0];
			var rel = node.attr ? node.attr("rel") : null;
			if (rel === "active_queue" || rel === "inactive_queue") {
				qIdForQueueGraph = node.attr("id");
			} else if (node.parent && node.parent !== "#") {
				qIdForQueueGraph = node.parent;
			}
			updateQueueGraph();
		}
	}).jstree({
		// Initialize the jstree plugin for the explorer list
		"json_data": {
			"ajax": {
				"url": starexecRoot + "services/cluster/queues",	// Where we will be getting json data from
				"data": function(n) {
					return {id: n.attr ? n.attr("id") : -1}; // What the default space id should be
				}
			}
		},
		"themes": {
			"theme": "default",
			"dots": true,
			"icons": true
		},
		"types": {
			"max_depth": -2,    // -2 means disable max_children checking in the tree
			"max_children": -2, // -2 means disable max_depth checking in the tree
			"valid_children": ["queue"],
			"types": {
				"active_queue": {
					"valid_children": ["enabled_node", "disabled_node"],
					"icon": {
						"image": starexecRoot + "images/jstree/on.png"
					}
				},
				"inactive_queue": {
					"valid_children": ["enabled_node", "disabled_node"],
					"icon": {
						"image": starexecRoot + "images/jstree/off.png"
					}
				},
				"enabled_node": {
					"valid_children": [],
					"icon": {
						"image": starexecRoot + "images/jstree/on.png"
					}
				},
				"disabled_node": {
					"valid_children": [],
					"icon": {
						"image": starexecRoot + "images/jstree/off.png"
					}
				}
			}
		},
		"plugins": ["types", "themes", "json_data", "ui", "cookies"],
		"core": {animation: 200}
	}).bind("select_node.jstree", function(event, data) {
		// When a node is clicked, get its ID and display the info in the details pane
		id = data.rslt.obj.attr("id");
		parent_node = $.jstree._reference('#exploreList')
		._get_parent(data.rslt.obj);
		getDetails(id, data.rslt.obj.attr("rel"), parent_node);

        // ---------------------------------------------------------------------------------------------------------- //
		// when an item is selected in Active Queues (exploreList), update the queuegraph
		// Alexander Brown, 11/20

		// if the selected item is a queue, set qIdForQueueGraph to that item's id
        if ( data.rslt.obj.attr("rel") == 'active_queue' || data.rslt.obj.attr("rel") == 'inactive_queue' ) {
            qIdForQueueGraph = id;
        } else {
        // if the selected item is not a queue, then it is a node; set qIdForQueueGraph to that item's parent's id
            qIdForQueueGraph = parent_node.attr("id");
        }

        // call updateQueueGraph() to immediately change the queuegraph image instead of waiting for the periodic update
        updateQueueGraph();
        // ---------------------------------------------------------------------------------------------------------- //

	}).on("click", "a", function(event, data) { event.preventDefault(); });	// This just disable's links in the node title
}

function initDataTables() {
	jobPairTable = $('#details').dataTable(new star.DataTableConfig({
		"bServerSide": true,
		"bFilter": false,
		"sAjaxSource": starexecRoot + "services/cluster/",
		"fnServerData": fnPaginationHandler,
		"language": { "emptyTable": "No job pairs in this queue or on this node." },
		"columns": [
			{
				"title": "Created",
				"width": "12.5%"
			},
			{
				"title": "Job",
				"width": "12.5%"
			},
			{
				"title": "User",
				"width": "12.5%"
			},
			{
				"title": "Benchmark",
				"width": "25%"
			},
			{
				"title": "Solver",
				"width": "12.5%"
			},
			{
				"title": "Config",
				"width": "12.5%"
			},
			{
				"title": "Path",
				"width": "12.5%"
			},
		]
	}));

	var formatName = function(row, type, val) {
		return star.format.jobLink(val);
	};

	var formatComplete = function(row, type, val) {
		return star.format.heatcolor((val["totalPairs"] - val["pendingPairs"]) * 100 / val["totalPairs"]);
	};

	var formatPending = function(row, type, val) {
		return val["pendingPairs"];
	};

	var formatUser = function(row, type, val) {
		return star.format.userLink(val["user"]);
	};

	var formatTime = function(row, type, val) {
		return star.format.timestamp(val["created"]);
	};

	var $jobs = $("#jobs");
	$jobs.dataTable(new star.DataTableConfig({
		"sServerMethod": "GET",
		"bServerSide": false,
		"bFilter": false,
		"language": { "emptyTable": "No running jobs in this queue." },
		"order": [
			[4, "desc"],
			[0, "asc"]
		],
		"columns": [
			{
				"title": "Job",
				"className": "dt-left",
				"render": formatName
			},
			{
				"title": "User",
				"render": formatUser
			},
			{
				"title": "Pending",
				"render": formatPending,
				"className": "dt-right",
				"width": "80px"
			},
			{
				"title": "Status",
				"render": formatComplete,
				"width": "60px"
			},
			{
				"title": "Created",
				"className": "dt-right",
				"width": "8em",
				"render": formatTime
			},
		]
	}));
}

function fnPaginationHandler(sSource, aoData, fnCallback) {
	var id = $('#exploreList').find('.jstree-clicked').parent().attr("id");
	//If we can't find the id of the queue/node from the DOM, get it from the cookie instead
	if (id == null || typeof id == 'undefined') {
		id = $.cookie("jstree_select");
		// If we also can't find the cookie, then just set the space selected to be the root space
		if (id == null || typeof id == 'undefined') {
			$('#exploreList').jstree('select_node', '#1', true);
			id = 1;
		} else {
			id = id[1];
		}
	}

	//In case the paginate happens before type is set
	if (typeof type == 'undefined') {
		window['type'] = 'queues';
	}
	//we have no pagination for inactive queues
	$.get(
		sSource + window['type'] + "/" + id + "/pagination",
		aoData,
		function(nextDataTablePage) {
			s = parseReturnCode(nextDataTablePage);
			if (s) {
				fnCallback(nextDataTablePage);
			}
		},
		"json"
	).fail(function() {
		showMessage('error', "Internal error populating table", 5000);
	});
}

/**
 * Populates the node details panel with information on the given node
 */
function getDetails(id, type, parent_node) {
	var url = '';
	selectedId = id;
	jobPairTable.fnClearTable();	//immediately get rid of the current data, which makes it look more responsive
	//get the queue, not that the global is set later so we can't use it
	if (!(id === undefined)) {
		$.get(starexecRoot + "services/queue/" + id + "/getDesc").done(
			function(data) {
				if (data === undefined) {
					$("#queueDescriptionText").text("")
				}
				else {
					$("#queueDescriptionText").text(data);
				}

			}
		).fail(function () {
			$("#queueDescriptionText").text("There was an error fetching the description")
		});
	} 
	
	
	if (type == 'active_queue' || type == 'inactive_queue') {
		var $jobs = $("#jobs");
		$("#clusterExpd").html("<span class='list-count'/> Enqueued Job Pairs <span>(+)</span>");
		$("#jobsContainer").show();
		url = starexecRoot + "services/cluster/queues/details/" + id;
		qid = id;
		$jobs.dataTable()
		.api()
		.ajax
		.url(starexecRoot + "services/cluster/queues/jobs/" + id)
		.load();
		window['type'] = 'queues';
		if (star.JobTableRefresh === undefined) {
			star.JobTableRefresh = window.setInterval($jobs.DataTable().ajax.reload,
				10000);
		}
		$("#detailField .expdContainer").css("display", "none");
		$("#descriptionContainer").show();
	} else if (type == 'enabled_node' || type == 'disabled_node') {
		$("#clusterExpd").html("<span class='list-count'/> Running Job Pairs");
		$("#jobsContainer").hide();
		url = starexecRoot + "services/cluster/nodes/details/" + id;
		qid = parent_node.attr("id");
		window['type'] = 'nodes';
		if (star.JobTableRefresh !== undefined) {
			window.clearInterval(star.JobTableRefresh);
			delete star.JobTableRefresh;
		}
		$("#detailField .expdContainer").css("display", "block");
		//hide the queue desc
		$("#descriptionContainer").hide();
	} else {
		showMessage('error', "Invalid node type", 5000);
		return;
	}
	loadQueueLoads();

	$('#loader').show();	

	jobPairTable.fnDraw();
	$.get(
		url,
		populateAttributes,
		"json"
	).fail(function() {
		showMessage('error', "Internal error getting node details", 5000);
	});
}

function loadQstatOutput() {
	$.get(
		starexecRoot + "services/cluster/qstat",
		{},
		function(data) {
			$("#qstatOutput").val(data);
		},
		"text"
	);
}

function loadQueueLoads() {
	$.get(
		starexecRoot + "services/cluster/loads/" + qid,
		{},
		function(data) {
			$("#loadOutput").val(data);
		},
		"text"
	);
}

/**
 * Takes in a json  response and populates the details panel with information
 * @param jsonData the json data to populate the details page with
 */
function populateAttributes(jsonData) {
	// Populate node details
	$('#workerName').text(jsonData.name.split('.')[0]);
	$('#queueID').text("id = " + selectedId);

	if (jsonData.cpuTimeout != null) {
		//We're dealing with a queue
		$('#activeStatus').hide();

	} else {
		//It is a node
		$('#activeStatus').show();

		if (jsonData.status == 'ACTIVE') {
			$('#activeStatus').text('[ACTIVE]');
			$('#activeStatus').css('color', '#008d03');
		} else {
			$('#activeStatus').text('[INACTIVE]');
			$('#activeStatus').css('color', '#ae0000');
		}
	}

	// Done loading, hide the loader
	$('#loader').hide();
}
