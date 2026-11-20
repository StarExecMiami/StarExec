<%@tag description="A clickable link to benchmark" import="org.starexec.data.to.*"%>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%@attribute name="value" required="true" description="The benchmark object to link to" type="org.starexec.data.to.Benchmark" %>

<a title="${fn:escapeXml(value.description)}" href="${starexecRoot}/secure/details/benchmark.jsp?id=${value.id}" target="_blank" rel="noopener noreferrer"><c:out value="${value.name}"/><span class="ext-link-icon"></span></a>
