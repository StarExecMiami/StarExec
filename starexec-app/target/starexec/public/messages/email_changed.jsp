<%@page contentType="text/html" pageEncoding="UTF-8" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%
	try {
		String email = request.getParameter("email");
		request.setAttribute("email", email);
	} catch (Exception e) {
		response.sendError(
				HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
		return;
	}
%>
<%@taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<star:template title="email changed">
	<main role="main" class="message-container">
		<section aria-labelledby="email-changed-heading">
			<h1 id="email-changed-heading" class="sr-only">Email Changed Successfully</h1>
			<div role="status" aria-live="polite">
				<p>You have successfully changed your email to ${fn:escapeXml(email)}</p>
				<p>You must now use this e-mail when logging in to StarExec.</p>
			</div>
		</section>
	</main>
</star:template>
