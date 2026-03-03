<%@page contentType="text/html" pageEncoding="UTF-8" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<star:template title="Thank You!">
	<main role="main" class="message-container">
		<section aria-labelledby="response-heading">
			<h1 id="response-heading" class="sr-only">Request Processed</h1>
			<div role="status" aria-live="polite">
				<p> You have successfully processed the request to join your community
					and the user who placed the request will be notified of your
					decision very shortly. </p>
				<c:if test="${not empty param.result and param.result == 'dupLeaderResponse'}">
					<div class='warn message' role="alert">another leader has already handled this
						request
					</div>
				</c:if>
			</div>
		</section>
	</main>
</star:template>
