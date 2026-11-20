<%@tag description="A clickable link to another user" import="org.starexec.data.to.*"%>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@attribute name="value" required="true" description="The community to link to" type="org.starexec.data.to.Space" %>

<a href="${starexecRoot}/secure/explore/communities.jsp"><c:out value="${value.name}"/><span class="ext-link-icon"></span></a>
