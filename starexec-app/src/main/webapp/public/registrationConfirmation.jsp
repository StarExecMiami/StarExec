<%@page contentType="text/html" pageEncoding="UTF-8" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<star:template title="Registration Confirmation">
	<main role="main" class="confirmation-container">
		<section aria-labelledby="confirmation-heading">
			<h1 id="confirmation-heading" class="sr-only">Registration Confirmation</h1>
			<c:choose>
			<c:when test="${not empty param.result and param.result == 'email_failed'}">
			<div role="alert">
				<p>
					Thank you for your registration! Your account was created, but the
					activation message could not be sent to the email address you provided.
				</p>
				<p>
					Please contact <a href="mailto:<c:out value='${contactEmail}'/>"><c:out value="${contactEmail}"/></a> for help
					finishing your registration.
				</p>
			</div>
			</c:when>
			<c:otherwise>
			<div role="status" aria-live="polite">
				<p>
					Thank you for your registration! You should receive an activation
					message at the email address you provided.
				</p>

				<p>
					Please click the activation link provided in the message to activate
					your account. After your account has been
					activated, leaders of the community you requested to join will be
					notified of your request to join their community.
				</p>

				<p>
					They will still need to approve you before you are added to your
					requested community.
				</p>
			</div>
			</c:otherwise>
			</c:choose>
		</section>
	</main>
</star:template>
