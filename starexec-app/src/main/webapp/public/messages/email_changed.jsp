<%@page contentType="text/html" pageEncoding="UTF-8" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<star:template title="Email Changed">
	<main role="main" class="message-container">
		<section aria-labelledby="email-changed-heading">
			<h1 id="email-changed-heading" class="sr-only">Email Changed Successfully</h1>
			<div role="status" aria-live="polite">
				<p>You have successfully changed your email address.</p>
				<p>You must now use this e-mail when logging in to StarExec.</p>
			</div>
		</section>
	</main>
</star:template>
