<%@page isErrorPage="true" contentType="text/html" pageEncoding="UTF-8"
%>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%
	String desc = "";

	switch (pageContext.getErrorData().getStatusCode()) {
	case 400:
		desc = "bad request";
		break;
	case 403:
		desc = "forbidden";
		break;
	case 404:
		desc = "not found";
		break;
	case 405:
		desc = "method not allowed";
		break;
	case 500:
		desc = "internal server error";
		request.setAttribute("didLog", true);
		break;
	case 503:
		desc = "service currently unavailable";
		break;
	default:
		break;
	}

	request.setAttribute("errorDesc", desc);
%>

<star:template title="It seems an error has occurred..."
               css="error">
	<main role="main" class="error-container">
		<section aria-labelledby="error-heading">
			<h1 id="error-heading" class="error-title">It seems an error has occurred...</h1>
			<div role="alert" class="error-details">
				<p><c:out value="(http ${pageContext.errorData.statusCode} - ${errorDesc})"/></p>
				<p><c:out value="${requestScope['javax.servlet.error.message']}"/></p>
			</div>
			<div id="actions" class="starexecErrorPage">
				<a href="#" onclick="history.go(-1);return false;" class="btn btn-primary">try again</a>
				<a href="mailto:${contactEmail}?subject=[Starexec] Error Report" class="btn btn-secondary">report error</a>
			</div>
		</section>
	</main>
</star:template>
