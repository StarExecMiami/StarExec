<%@page contentType="text/html" pageEncoding="UTF-8"
        import="org.starexec.data.database.Analytics, org.starexec.util.SessionUtil, org.starexec.util.Util" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%
	try {
		String reference = request.getParameter("ref");
		if (reference == null) {
			//there was no ref parameter, so try and use the actual referer
			reference = request.getHeader("referer");
			int argIndex = reference.indexOf('?');
			//first, get the referring URL down to just the path without any arguments
			if (argIndex >= 0) {
				reference = reference.substring(0, argIndex);
			}
			//next, get rid of the ".jsp" and replace it with ".help"
			reference = reference.substring(0, reference.length() - 4) + ".help";
			reference = reference.substring(reference.indexOf("/secure/") + 1);
			reference = Util.docRoot(reference);
		}
		if (reference != null) {
			request.setAttribute("ref", reference);
		}
	} catch (Exception e) {
		//if we can't find the referrer, that's fine. Just load up the main help page
	}

	int userId = SessionUtil.getUserId(request);
	Analytics.JOB_ATTRIBUTES.record(userId);
%>
<star:template title="Help Center"
               js="lib/jquery.dataTables.min, lib/jquery.jstree, help/help, lib/jquery.qtip.min, lib/jquery.heatcolor.0.0.1.min, lib/jquery.ba-throttle-debounce.min"
               css="help/support-hub, components/card, components/badge">

	<span id="reference" href="${ref}"></span>

	<main role="main" class="support-hub">
		<!-- Hero Section -->
		<section class="support-hero">
			<div class="container">
				<div class="hero-content">
					<h1 class="hero-title">Help Center</h1>
					<p class="hero-description">
						Contextual documentation and guides for using StarExec effectively
					</p>
					
					<!-- Search Bar -->
					<div class="search-wrapper">
						<div class="search-box">
							<svg class="search-icon" width="20" height="20" viewBox="0 0 20 20" fill="none">
								<path d="M9 17A8 8 0 109 1a8 8 0 000 16zM18 18l-4-4" 
									stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
							</svg>
							<input type="search" 
								class="search-input" 
								id="helpSearch"
								placeholder="Search help topics..."
								aria-label="Search help topics"/>
						</div>
					</div>
				</div>
			</div>
		</section>

		<div class="container">
			<!-- Legacy Help Topics Navigation (Hidden by default, shown when JS loads content) -->
			<div id="helpTopics" style="display: none;">
				<ul id="topicList">
					<li class="topicHeader">Exploring</li>
					<li class="subject"><a href="${starexecRoot}/secure/explore/spaces.help">Exploring spaces</a></li>
					<li class="subject"><a href="${starexecRoot}/secure/explore/communities.help">Exploring communities</a></li>
					<li class="subject"><a href="${starexecRoot}/secure/explore/cluster.help">Checking cluster status</a></li>
					<li class="subject"><a href="${starexecRoot}/secure/explore/statistics.help">Viewing community statistics</a></li>
					<li class="topicHeader">Benchmarks</li>
					<li class="subject"><a href="${starexecRoot}/secure/add/benchmarks.help">Adding benchmarks</a></li>
					<li class="subject"><a href="${starexecRoot}/secure/details/benchmark.help">Viewing benchmarks</a></li>
					<li class="subject"><a href="${starexecRoot}/secure/edit/benchmark.help">Editing benchmarks</a></li>
					<li class="topicHeader">Solvers</li>
					<li class="subject"><a href="${starexecRoot}/secure/add/solver.help">Adding solvers</a></li>
					<li class="subject"><a href="${starexecRoot}/secure/details/solver.help">Viewing solvers</a></li>
					<li class="subject"><a href="${starexecRoot}/secure/edit/solver.help">Editing solvers</a></li>
					<li class="topicHeader">Jobs</li>
					<li class="subject"><a href="${starexecRoot}/secure/add/job.help">Running jobs</a></li>
					<li class="subject"><a href="${starexecRoot}/secure/add/quickJob.help">Quick jobs</a></li>
					<li class="subject"><a href="${starexecRoot}/secure/details/job.help">Viewing results</a></li>
					<li class="topicHeader">Users</li>
					<li class="subject"><a href="${starexecRoot}/secure/edit/account.help">Managing your account</a></li>
					<li class="subject"><a href="${starexecRoot}/secure/edit/spacePermissions.help">Editing permissions</a></li>
					<li class="topicHeader">General</li>
					<li class="subject"><a href="${starexecRoot}/secure/help/input-restrictions.help">Input restrictions</a></li>
					<li class="subject"><a href="${starexecRoot}/secure/help/linking-copying.help">Linking and copying</a></li>
					<li class="subject"><a href="${starexecRoot}/secure/help/removing-recycling-deleting.help">Removing, recycling, and deleting primitives</a></li>
				</ul>
			</div>

			<!-- Modern UI: Category Cards -->
			<section class="quick-links help-categories">
				<h2 class="section-title">
					<svg class="section-icon" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
						<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"></path>
						<path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"></path>
					</svg>
					Browse by Category
				</h2>
				
				<div class="links-grid">
					<!-- Exploring -->
					<div class="category-card link-card">
						<div class="link-card__icon link-card__icon--exploring">
							<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
								<path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0118 0z" stroke="white" stroke-width="2"/>
								<circle cx="12" cy="10" r="3" stroke="white" stroke-width="2"/>
							</svg>
						</div>
						<div class="link-card__content">
							<h3 class="link-card__title">Exploring</h3>
							<p class="link-card__description">Navigate spaces, communities, and cluster status</p>
							<ul class="topic-list">
								<li><a href="${starexecRoot}/secure/explore/spaces.help">Exploring spaces</a></li>
								<li><a href="${starexecRoot}/secure/explore/communities.help">Exploring communities</a></li>
								<li><a href="${starexecRoot}/secure/explore/cluster.help">Cluster status</a></li>
								<li><a href="${starexecRoot}/secure/explore/statistics.help">Community statistics</a></li>
							</ul>
						</div>
					</div>

					<!-- Benchmarks -->
					<div class="category-card link-card">
						<div class="link-card__icon link-card__icon--benchmarks">
							<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
								<path d="M9 19v-6a2 2 0 012-2h2a2 2 0 012 2v6a2 2 0 01-2 2h-2a2 2 0 01-2-2z" stroke="white" stroke-width="2"/>
								<path d="M9 11V6l-4 6h6l-2 6" stroke="white" stroke-width="2"/>
							</svg>
						</div>
						<div class="link-card__content">
							<h3 class="link-card__title">Benchmarks</h3>
							<p class="link-card__description">Upload, view, and manage test problems</p>
							<ul class="topic-list">
								<li><a href="${starexecRoot}/secure/add/benchmarks.help">Adding benchmarks</a></li>
								<li><a href="${starexecRoot}/secure/details/benchmark.help">Viewing benchmarks</a></li>
								<li><a href="${starexecRoot}/secure/edit/benchmark.help">Editing benchmarks</a></li>
							</ul>
						</div>
					</div>

					<!-- Solvers -->
					<div class="category-card link-card">
						<div class="link-card__icon link-card__icon--solvers">
							<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
								<path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" stroke="white" stroke-width="2"/>
							</svg>
						</div>
						<div class="link-card__content">
							<h3 class="link-card__title">Solvers</h3>
							<p class="link-card__description">Configure and deploy logic solvers</p>
							<ul class="topic-list">
								<li><a href="${starexecRoot}/secure/add/solver.help">Adding solvers</a></li>
								<li><a href="${starexecRoot}/secure/details/solver.help">Viewing solvers</a></li>
								<li><a href="${starexecRoot}/secure/edit/solver.help">Editing solvers</a></li>
							</ul>
						</div>
					</div>

					<!-- Jobs -->
					<div class="category-card link-card">
						<div class="link-card__icon link-card__icon--jobs">
							<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
								<path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z" fill="white" stroke="white" stroke-width="2"/>
							</svg>
						</div>
						<div class="link-card__content">
							<h3 class="link-card__title">Jobs</h3>
							<p class="link-card__description">Create, run, and analyze computational jobs</p>
							<ul class="topic-list">
								<li><a href="${starexecRoot}/secure/add/job.help">Running jobs</a></li>
								<li><a href="${starexecRoot}/secure/add/quickJob.help">Quick jobs</a></li>
								<li><a href="${starexecRoot}/secure/details/job.help">Viewing results</a></li>
							</ul>
						</div>
					</div>

					<!-- Account & Permissions -->
					<div class="category-card link-card">
						<div class="link-card__icon link-card__icon--users">
							<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
								<path d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" stroke="white" stroke-width="2"/>
							</svg>
						</div>
						<div class="link-card__content">
							<h3 class="link-card__title">Users & Permissions</h3>
							<p class="link-card__description">Manage your account and access control</p>
							<ul class="topic-list">
								<li><a href="${starexecRoot}/secure/edit/account.help">Managing your account</a></li>
								<li><a href="${starexecRoot}/secure/edit/spacePermissions.help">Editing permissions</a></li>
							</ul>
						</div>
					</div>

					<!-- General Concepts -->
					<div class="category-card link-card">
						<div class="link-card__icon link-card__icon--general">
							<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
								<circle cx="12" cy="12" r="10" stroke="white" stroke-width="2"/>
								<path d="M12 16v-4M12 8h.01" stroke="white" stroke-width="2" stroke-linecap="round"/>
							</svg>
						</div>
						<div class="link-card__content">
							<h3 class="link-card__title">General Concepts</h3>
							<p class="link-card__description">Core StarExec concepts and guidelines</p>
							<ul class="topic-list">
								<li><a href="${starexecRoot}/secure/help/input-restrictions.help">Input restrictions</a></li>
								<li><a href="${starexecRoot}/secure/help/linking-copying.help">Linking and copying</a></li>
								<li><a href="${starexecRoot}/secure/help/removing-recycling-deleting.help">Removing & deleting</a></li>
							</ul>
						</div>
					</div>
				</div>
			</section>

			<!-- Detail Panel (for dynamic content loading) -->
			<section id="detailPanel" class="help-detail-panel" style="display: none;">
				<!-- Content loaded dynamically via help.js -->
			</section>

			<!-- Quick Links to Public Resources -->
			<section class="community-section">
				<h2 class="section-title">
					<svg class="section-icon" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
						<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"></path>
						<path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"></path>
					</svg>
					Additional Resources
				</h2>
				
				<div class="community-grid">
					<a href="${starexecRoot}/public/quickReference.jsp" class="community-card">
						<div class="community-card__icon">
							<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
								<path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z" fill="currentColor"/>
							</svg>
						</div>
						<div class="community-card__content">
							<h3 class="community-card__title">Quick Reference</h3>
							<p class="community-card__description">Essential tasks and workflows</p>
						</div>
						<svg class="external-link-icon" width="16" height="16" viewBox="0 0 24 24">
							<path d="M10 14L21 3M15 3h6v6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
						</svg>
					</a>

					<a href="${starexecRoot}/public/StarExecUserGuide.pdf" 
						class="community-card"
						target="_blank"
						rel="noopener noreferrer">
						<div class="community-card__icon">
							<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
								<path d="M4 19.5A2.5 2.5 0 016.5 17H20M4 19.5A2.5 2.5 0 016.5 22H20V2H6.5A2.5 2.5 0 004 4.5v15z" 
									stroke="currentColor" stroke-width="2"/>
							</svg>
						</div>
						<div class="community-card__content">
							<h3 class="community-card__title">User Guide</h3>
							<p class="community-card__description">Comprehensive PDF documentation</p>
						</div>
						<svg class="external-link-icon" width="16" height="16" viewBox="0 0 24 24">
							<path d="M18 13v6a2 2 0 01-2 2H5a2 2 0 01-2-2V8a2 2 0 012-2h6M15 3h6v6M10 14L21 3" 
								stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
						</svg>
					</a>

					<a href="${starexecRoot}/public/starexeccommand.jsp" class="community-card">
						<div class="community-card__icon">
							<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
								<path d="M4 17l6-6-6-6M12 19h8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
							</svg>
						</div>
						<div class="community-card__content">
							<h3 class="community-card__title">CLI Tool</h3>
							<p class="community-card__description">Command-line interface docs</p>
						</div>
						<svg class="external-link-icon" width="16" height="16" viewBox="0 0 24 24">
							<path d="M10 14L21 3M15 3h6v6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
						</svg>
					</a>
				</div>
			</section>
		</div>
	</main>
</star:template>
