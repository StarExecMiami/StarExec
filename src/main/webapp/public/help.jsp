<%@page contentType="text/html" pageEncoding="UTF-8" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<star:template title="StarExec Support">
	<main role="main" class="help-container">
		<section aria-labelledby="help-heading">
			<h1 id="help-heading" class="sr-only">StarExec Support Resources</h1>
			<div id="support">
				<ul class="help-links">
					<li>
						<a href="${starexecRoot}/secure/help.jsp" rel="help">On-page documentation</a>,
						also available (if logged in) via the Help link at the top of the page
					</li>
					<li>
						<a href="${starexecRoot}/public/quickReference.jsp" rel="help">Quick Reference</a>
					</li>
					<li>
						<a href="${starexecRoot}/public/StarExecUserGuide.pdf" rel="help">User Guide</a>,
						detailed description of StarExec features and functionality
					</li>
					<li>
						<a href="https://github.com/StarExec/StarExec" rel="external">StarExec on GitHub</a>,
						for bug reports and feature requests
					</li>
					<li>
						<a href="${starexecRoot}/public/WebInterface.pdf" rel="help">Web Interface Documentation</a>
						for making direct HTTP calls to Starexec
					</li>
					<li>
						<a href="https://www.youtube.com/channel/UCoYhHKXD5agIia60z-RiX2A" rel="external">Video Tutorials</a>
					</li>
				</ul>
				<footer class="build-info">
					<p>Starexec revision ${fn:escapeXml(buildVersion)} built ${fn:escapeXml(buildDate)} by ${fn:escapeXml(buildUser)}</p>
				</footer>
			</div>
		</section>
	</main>
</star:template>
