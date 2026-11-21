jQuery(function($) {
	star.mountTree($("#exploreList"), starexecRoot + "services/communities/all", {
		dots: false,
		stateKey: "admin-communities",
		select: function(node) {
			$("#removeCommLeader, #promoteCommLeader")
				.attr("href", starexecRoot + "secure/edit/community.jsp?cid=" + node.id).show();
		}
	});

	$("#newCommunity").button({
		icons: {
			primary: "ui-icon-plusthick"
		}
	});

	$("#removeCommLeader").button({
		icons: {
			primary: "ui-icon-minusthick"
		}
	});

	$("#promoteCommLeader").button({
		icons: {
			primary: "ui-icon-circle-arrow-n"
		}
	});

	setupHandlersForCommunityRequestAcceptDeclineButtons();

	$('#newCommunity')
	.attr('href', starexecRoot + "secure/add/space.jsp?sid=1");

	$("#removeCommLeader, #promoteCommLeader").hide();

	initCommunityRequestsTable('#commRequests', true);

	star.startPagePolling(function() {
		$("#commRequests").DataTable().ajax.reload(null, false);
	}, 10000);
});
