<%@tag description="A clickable link to another user" import="org.starexec.data.to.*"%>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@attribute name="value" required="true" description="The user object to link to" type="org.starexec.data.to.User" %>

<a href="${starexecRoot}/secure/details/user.jsp?id=${value.id}" target="_blank" rel="noopener noreferrer"><c:out value="${value.fullName}"/><span class="ext-link-icon"></span></a>
