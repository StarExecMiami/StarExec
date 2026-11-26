$(document).ready(function() {
	var currentUserId = parseInt($('#userId').attr('value'));

	// Make a post to subscribe or unsubscribe the current user to reports
	var makeSubscribeOrUnsubscribePost = function(subscribeOrUnsubscribe) {
		$.post(
			starexecRoot + 'services/' + subscribeOrUnsubscribe + '/user/' + currentUserId,
			{},
			function(returnCode) {
				if (parseReturnCode(returnCode)) {
					setTimeout(function() {document.location.reload(true);}, 1000);
				}
			},
			'json'
		).fail(function() {
			showMessage('error', 'Internal error ' + (subscribeOrUnsubscribe === 'unsubscribe' ? 'unsubscribing' : 'subscribing') + ' user', 5000);
		});
	};

	var subscribeUser = function() {
		log('Subscribing user with id ' + currentUserId + ' to reports.');
		makeSubscribeOrUnsubscribePost('subscribe');
	};

	var unsubscribeUser = function() {
		log('Unsubscribing user with id ' + currentUserId + ' from reports.');
		makeSubscribeOrUnsubscribePost('unsubscribe');
	};

	// Register subscribe/unsubscribe button event handlers
	$('#subscribe').click(subscribeUser);
	$('#unsubscribe').click(unsubscribeUser);
});
