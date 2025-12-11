<%@page contentType="text/html" pageEncoding="UTF-8" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>

<star:template title="account activation">
	<main role="main" class="message-container">
		<section aria-labelledby="activation-heading">
			<h1 id="activation-heading" class="sr-only">Account Activation Successful</h1>
			<div role="status" aria-live="polite">
				<p>You have successfully activated your account!</p>
				<p>The leaders of the community you selected during registration will be
					notified of your request to join shortly.</p>
				<p>Once a leader of that community has approved your request, you will
					receive an email from us. At that point your registration will be
					complete and you will be free to login and begin using our service.</p>
				<p>Thank you for your patience!</p>
			</div>
		</section>
	</main>
</star:template>
