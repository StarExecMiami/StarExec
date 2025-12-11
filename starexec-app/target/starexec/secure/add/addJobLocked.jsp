<%@page contentType="text/html" pageEncoding="UTF-8" %>
<%@page trimDirectiveWhitespaces="true" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>

<star:template title="Job Submission Unavailable" css="add/addJobLocked">
    <main role="main" class="locked-container">
        <section aria-labelledby="locked-heading">
            <div class="locked-icon" aria-hidden="true">
                <span class="ui-icon ui-icon-locked"></span>
            </div>
            <h1 id="locked-heading" class="locked-title">Job Submission Unavailable</h1>
            <div role="alert" class="locked-details">
                <p>Read-only mode is currently enabled on this system.</p>
                <p>New job submissions are temporarily disabled. Please try again later or contact the administrator if you believe this is an error.</p>
            </div>
            <div class="locked-actions">
                <a href="#" onclick="history.go(-1);return false;" class="btn btn-primary">
                    <span class="ui-icon ui-icon-arrowthick-1-w"></span> Go Back
                </a>
                <a href="${starexecRoot}/secure/explore/spaces.jsp" class="btn btn-secondary">
                    <span class="ui-icon ui-icon-home"></span> Return to Spaces
                </a>
            </div>
        </section>
    </main>
</star:template>