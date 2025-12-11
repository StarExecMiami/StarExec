<%@page contentType="text/html" pageEncoding="UTF-8" %>
<%@page trimDirectiveWhitespaces="true" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>

<star:template title="StarExec Support" css="help/support-hub, components/card, components/badge" js="public/help">
	<main role="main" class="support-hub">
		<!-- Hero Section -->
		<section class="support-hero">
			<div class="container">
				<div class="hero-content">
					<h1 class="hero-title">Welcome to StarExec Support</h1>
					<p class="hero-description">
						Everything you need to get started with StarExec. 
						Find documentation, tools, and community resources.
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
								placeholder="Search documentation..."
								aria-label="Search documentation"/>
						</div>
					</div>
				</div>
			</div>
		</section>

		<div class="container">
			<!-- Quick Links Grid -->
			<section class="quick-links" aria-labelledby="quick-links-title">
				<h2 id="quick-links-title" class="section-title">
					<svg class="section-icon" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
						<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon>
					</svg>
					Quick Start
				</h2>
				
				<div class="links-grid">
					<!-- Getting Started -->
					<a href="${starexecRoot}/public/quickReference.jsp" class="link-card card--interactive">
						<div class="link-card__icon link-card__icon--quick-start">
							<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
								<path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z" 
									fill="white" stroke="white" stroke-width="2"/>
							</svg>
						</div>
						<div class="link-card__content">
							<h3 class="link-card__title">Quick Reference</h3>
							<p class="link-card__description">
								Essential tasks and workflows for getting started
							</p>
							<span class="link-card__arrow">→</span>
						</div>
					</a>

					<!-- User Guide -->
					<a href="${starexecRoot}/public/StarExecUserGuide.pdf" 
						class="link-card card--interactive"
						target="_blank"
						rel="noopener noreferrer">
						<div class="link-card__icon link-card__icon--user-guide">
							<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
								<path d="M4 19.5A2.5 2.5 0 016.5 17H20M4 19.5A2.5 2.5 0 016.5 22H20V2H6.5A2.5 2.5 0 004 4.5v15z" 
									stroke="white" stroke-width="2"/>
							</svg>
						</div>
						<div class="link-card__content">
							<h3 class="link-card__title">
								User Guide
								<span class="badge badge--success">PDF</span>
							</h3>
							<p class="link-card__description">
								Comprehensive guide to all StarExec features
							</p>
							<span class="link-card__arrow">→</span>
						</div>
					</a>

					<!-- On-Page Help -->
					<a href="${starexecRoot}/secure/help.jsp" class="link-card card--interactive">
						<div class="link-card__icon link-card__icon--contextual">
							<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
								<circle cx="12" cy="12" r="10" stroke="white" stroke-width="2"/>
								<path d="M12 16v-4M12 8h.01" stroke="white" stroke-width="2" stroke-linecap="round"/>
							</svg>
						</div>
						<div class="link-card__content">
							<h3 class="link-card__title">
								Contextual Help
								<span class="badge badge--info">Members</span>
							</h3>
							<p class="link-card__description">
								In-app documentation and tooltips
							</p>
							<span class="link-card__arrow">→</span>
						</div>
					</a>
				</div>
			</section>

			<!-- Developer Tools -->
			<section class="developer-tools" aria-labelledby="tools-title">
				<h2 id="tools-title" class="section-title">
					<svg class="section-icon" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
						<path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"></path>
					</svg>
					Developer Tools
				</h2>
				
				<div class="tools-grid">
					<!-- CLI Tool -->
					<div class="tool-card">
						<div class="tool-card__header">
							<div class="tool-icon">
								<svg width="32" height="32" viewBox="0 0 24 24" fill="none">
									<path d="M4 17l6-6-6-6M12 19h8" 
										stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
								</svg>
							</div>
							<div>
								<h3 class="tool-card__title">StarExecCommand</h3>
								<p class="tool-card__subtitle">Command-line interface</p>
							</div>
						</div>
						<p class="tool-card__description">
							Automate workflows and integrate StarExec into your development pipeline 
							with our powerful CLI tool.
						</p>
						<div class="tool-card__features">
							<span class="feature-tag">Batch Operations</span>
							<span class="feature-tag">Scripting</span>
							<span class="feature-tag">Job Management</span>
						</div>
						<div class="tool-card__actions">
							<a href="${starexecRoot}/public/starexeccommand.jsp" 
								class="btn btn--primary">
								<svg width="16" height="16" viewBox="0 0 24 24" fill="none">
									<path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4M7 10l5 5 5-5M12 15V3" 
										stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
								</svg>
								Documentation & Download
							</a>
						</div>
					</div>

					<!-- Web API -->
					<div class="tool-card">
						<div class="tool-card__header">
							<div class="tool-icon">
								<svg width="32" height="32" viewBox="0 0 24 24" fill="none">
									<rect x="2" y="3" width="20" height="14" rx="2" 
										stroke="currentColor" stroke-width="2"/>
									<path d="M8 21h8M12 17v4" 
										stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
								</svg>
							</div>
							<div>
								<h3 class="tool-card__title">Web Interface API</h3>
								<p class="tool-card__subtitle">HTTP endpoints</p>
							</div>
						</div>
						<p class="tool-card__description">
							Make direct HTTP calls to StarExec for custom integrations 
							and automation.
						</p>
						<div class="tool-card__features">
							<span class="feature-tag">REST API</span>
							<span class="feature-tag">Authentication</span>
							<span class="feature-tag">Webhooks</span>
						</div>
						<div class="tool-card__actions">
							<a href="${starexecRoot}/public/WebInterface.pdf" 
								class="btn btn--secondary"
								target="_blank"
								rel="noopener noreferrer">
								<svg width="16" height="16" viewBox="0 0 24 24" fill="none">
									<path d="M4 19.5A2.5 2.5 0 016.5 17H20M4 19.5A2.5 2.5 0 016.5 22H20V2H6.5A2.5 2.5 0 004 4.5v15z" 
										stroke="currentColor" stroke-width="2"/>
								</svg>
								API Documentation
							</a>
						</div>
					</div>
				</div>
			</section>

			<!-- Community Resources -->
			<section class="community-section" aria-labelledby="community-title">
				<h2 id="community-title" class="section-title">
					<svg class="section-icon" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
						<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"></path>
						<circle cx="9" cy="7" r="4"></circle>
						<path d="M23 21v-2a4 4 0 0 0-3-3.87"></path>
						<path d="M16 3.13a4 4 0 0 1 0 7.75"></path>
					</svg>
					Community & Learning
				</h2>
				
				<div class="community-grid">
					<!-- GitHub -->
					<a href="https://github.com/StarExec/StarExec" 
						class="community-card"
						target="_blank"
						rel="noopener noreferrer">
						<div class="community-card__icon">
							<svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
								<path d="M12 2C6.477 2 2 6.477 2 12c0 4.42 2.865 8.17 6.839 9.49.5.092.682-.217.682-.482 0-.237-.008-.866-.013-1.7-2.782.603-3.369-1.34-3.369-1.34-.454-1.156-1.11-1.463-1.11-1.463-.908-.62.069-.608.069-.608 1.003.07 1.531 1.03 1.531 1.03.892 1.529 2.341 1.087 2.91.831.092-.646.35-1.086.636-1.336-2.22-.253-4.555-1.11-4.555-4.943 0-1.091.39-1.984 1.029-2.683-.103-.253-.446-1.27.098-2.647 0 0 .84-.269 2.75 1.025A9.578 9.578 0 0112 6.836c.85.004 1.705.114 2.504.336 1.909-1.294 2.747-1.025 2.747-1.025.546 1.377.203 2.394.1 2.647.64.699 1.028 1.592 1.028 2.683 0 3.842-2.339 4.687-4.566 4.935.359.309.678.919.678 1.852 0 1.336-.012 2.415-.012 2.743 0 .267.18.578.688.48C19.138 20.167 22 16.418 22 12c0-5.523-4.477-10-10-10z"/>
							</svg>
						</div>
						<div class="community-card__content">
							<h3 class="community-card__title">GitHub Repository</h3>
							<p class="community-card__description">
								Report bugs, request features, and contribute to development
							</p>
						</div>
						<svg class="external-link-icon" width="16" height="16" viewBox="0 0 24 24">
							<path d="M18 13v6a2 2 0 01-2 2H5a2 2 0 01-2-2V8a2 2 0 012-2h6M15 3h6v6M10 14L21 3" 
								stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
						</svg>
					</a>

					<!-- Video Tutorials -->
					<a href="https://www.youtube.com/channel/UCoYhHKXD5agIia60z-RiX2A" 
						class="community-card"
						target="_blank"
						rel="noopener noreferrer">
						<div class="community-card__icon">
							<svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
								<path d="M23.498 6.186a3.016 3.016 0 00-2.122-2.136C19.505 3.545 12 3.545 12 3.545s-7.505 0-9.377.505A3.017 3.017 0 00.502 6.186C0 8.07 0 12 0 12s0 3.93.502 5.814a3.016 3.016 0 002.122 2.136c1.871.505 9.376.505 9.376.505s7.505 0 9.377-.505a3.015 3.015 0 002.122-2.136C24 15.93 24 12 24 12s0-3.93-.502-5.814zM9.545 15.568V8.432L15.818 12l-6.273 3.568z"/>
							</svg>
						</div>
						<div class="community-card__content">
							<h3 class="community-card__title">Video Tutorials</h3>
							<p class="community-card__description">
								Step-by-step guides and walkthroughs on YouTube
							</p>
						</div>
						<svg class="external-link-icon" width="16" height="16" viewBox="0 0 24 24">
							<path d="M18 13v6a2 2 0 01-2 2H5a2 2 0 01-2-2V8a2 2 0 012-2h6M15 3h6v6M10 14L21 3" 
								stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
						</svg>
					</a>
				</div>
			</section>

			<!-- System Status Footer -->
			<footer class="system-status">
				<div class="status-container">
					<div class="status-header">
						<h3 class="status-title">System Information</h3>
						<span class="status-indicator">
							<span class="status-dot status-dot--online"></span>
							All Systems Operational
						</span>
					</div>
					<div class="status-details">
						<div class="status-item">
							<span class="status-label">Version</span>
							<code class="status-value">${fn:escapeXml(buildVersion)}</code>
						</div>
						<div class="status-item">
							<span class="status-label">Build Date</span>
							<time datetime="${buildDate}" class="status-value">
								${fn:escapeXml(buildDate)}
							</time>
						</div>
						<div class="status-item">
							<span class="status-label">Built By</span>
							<span class="status-value">${fn:escapeXml(buildUser)}</span>
						</div>
					</div>
				</div>
			</footer>
		</div>
	</main>
</star:template>