<%@tag description="Picture section for user/solver detail: thumbnail with optional change link" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@attribute name="thumbSrc" required="true" type="java.lang.String" description="URL of thumbnail image" %>
<%@attribute name="enlargeSrc" required="true" type="java.lang.String" description="URL of full-size image for popup" %>
<%@attribute name="altText" required="true" type="java.lang.String" description="Alt text for the image" %>
<%@attribute name="showChangeLink" required="false" type="java.lang.Boolean" description="Whether to show the change/upload picture link" %>
<%@attribute name="changeLinkUrl" required="false" type="java.lang.String" description="URL for the change picture link" %>
<%@attribute name="changeLinkLabel" required="false" type="java.lang.String" description="Label for the change link (default: Change picture)" %>
<%@attribute name="useDataEnlarge" required="false" type="java.lang.Boolean" description="Use data-enlarge instead of enlarge attribute (for account page JS)" %>

<c:set var="label" value="${empty changeLinkLabel ? 'Change picture' : changeLinkLabel}" />
<td id="picSection">
	<c:choose>
		<c:when test="${useDataEnlarge}">
			<img id="showPicture"
			     src="${thumbSrc}"
			     alt="${altText}"
			     data-enlarge="${enlargeSrc}"
			     role="button"
			     tabindex="0"
			     title="Click to enlarge">
		</c:when>
		<c:otherwise>
			<img id="showPicture"
			     src="${thumbSrc}"
			     alt="${altText}"
			     enlarge="${enlargeSrc}"
			     role="button"
			     tabindex="0"
			     title="Click to enlarge">
		</c:otherwise>
	</c:choose>
	<c:if test="${!useDataEnlarge}"><br></c:if>
	<c:if test="${showChangeLink && not empty changeLinkUrl}">
		<c:choose>
			<c:when test="${useDataEnlarge}">
				<nav aria-label="Picture actions">
					<ul>
						<li><a class="btn btn-secondary" id="uploadPicture" href="${changeLinkUrl}">${label}</a></li>
					</ul>
				</nav>
			</c:when>
			<c:otherwise>
				<a id="uploadPicture" href="${changeLinkUrl}">${label}</a>
			</c:otherwise>
		</c:choose>
	</c:if>
</td>
