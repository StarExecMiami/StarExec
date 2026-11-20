"use strict";

jQuery(function($) {
	var currentJobSpaceId = getParameterByName('id');
	var jobId = $('#data').data('jobid'); // Initialize the explorer list
	var $table = $('#attributeTable');

	$('#attributeTotalsTable').dataTable({
		'bSort': true,
		"bPaginate": true
	});

	if ($table.length) {
		$table.dataTable({
			'bSort': false,
			'fixedColumns': true,
			'scrollY': '220px',
			'scrollX': '100%',
			'scrollCollapse': true,
			'paging': false
		});

		$(window).resize(function() {
			$table.fnDraw();
		});
	}

	initSpaceExplorer(currentJobSpaceId, jobId);
	setupChangeTimeButton();
});


function setupChangeTimeButton() {
	var isWallclock = true;
	var toggleTime = function() {
		if (isWallclock) {
			$('.changeTime .ui-button-text').html('Use wallclock time');
			isWallclock = false;
			$('.cpuSum').show();
			$('.wallclockSum').hide();
		} else {
			isWallclock = true;
			$('.changeTime .ui-button-text').html('Use CPU time');
			$('.wallclockSum').show();
			$('.cpuSum').hide();
		}
	};

	$('.cpuSum').hide();

	$(".changeTime")
	.button({
		icons: {
			primary: "ui-icon-refresh"
		}
	})
	.click(toggleTime);
}

function initSpaceExplorer(currentJobSpaceId, jobId) {
	star.mountTree($("#exploreList"), starexecRoot + "services/space/" + jobId + "/jobspaces/true", {
		rootId: 0,
		initial: currentJobSpaceId,
		select: function(node) {
			if (node.id !== currentJobSpaceId) {
				window.location.href = starexecRoot + 'secure/details/jobAttributes.jsp?id=' + node.id;
			}
		}
	});
}
