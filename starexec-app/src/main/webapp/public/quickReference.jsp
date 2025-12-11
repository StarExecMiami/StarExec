<%@page contentType="text/html" pageEncoding="UTF-8" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<star:template title="Quick Reference Guide" css="public/quick-ref, components/tabs, components/accordion" js="public/quickReference">
	<main role="main" class="quick-ref-guide">
		<!-- Header -->
		<div class="guide-header">
			<div class="container">
				<div class="header-content">
					<div class="breadcrumb">
						<a href="${starexecRoot}/public/help.jsp" class="breadcrumb-link">Support</a>
						<span class="breadcrumb-separator">/</span>
						<span class="breadcrumb-current">Quick Reference</span>
					</div>
					
					<h1 class="page-title">Quick Reference Guide</h1>
					<p class="page-subtitle">
						Master the essentials of StarExec with this concise, task-oriented guide
					</p>
					
					<div class="header-actions">
						<a href="http://wiki.uiowa.edu/display/stardev/User+Guide" 
							class="btn btn--secondary"
							target="_blank"
							rel="noopener noreferrer">
							<svg width="16" height="16" viewBox="0 0 24 24" fill="none">
								<path d="M4 19.5A2.5 2.5 0 016.5 17H20M4 19.5A2.5 2.5 0 016.5 22H20V2H6.5A2.5 2.5 0 004 4.5v15z" 
									stroke="currentColor" stroke-width="2"/>
							</svg>
							Full User Guide
						</a>
						<button class="btn btn--ghost" id="printGuide">
							<svg width="16" height="16" viewBox="0 0 24 24" fill="none">
								<path d="M6 9V2h12v7M6 18H4a2 2 0 01-2-2v-5a2 2 0 012-2h16a2 2 0 012 2v5a2 2 0 01-2 2h-2M6 14h12v8H6v-8z" 
									stroke="currentColor" stroke-width="2"/>
							</svg>
							Print
						</button>
					</div>
				</div>
			</div>
		</div>

		<div class="container">
			<div class="guide-layout">
				<!-- Sticky Navigation -->
				<nav class="guide-nav" aria-label="Guide sections">
					<div class="nav-header">
						<h2 class="nav-title">On this page</h2>
					</div>
					<ul class="nav-list">
						<li class="nav-item">
							<a href="#space-explorer" class="nav-link active">
								<svg class="nav-icon" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
									<circle cx="12" cy="12" r="10"></circle>
									<polygon points="16.24 7.76 14.12 14.12 7.76 16.24 9.88 9.88 16.24 7.76"></polygon>
								</svg>
								Space Explorer
							</a>
						</li>
						<li class="nav-item">
							<a href="#space-info" class="nav-link">
								<svg class="nav-icon" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
									<line x1="18" y1="20" x2="18" y2="10"></line>
									<line x1="12" y1="20" x2="12" y2="4"></line>
									<line x1="6" y1="20" x2="6" y2="14"></line>
								</svg>
								Space Information
							</a>
						</li>
						<li class="nav-item">
							<a href="#drag-drop" class="nav-link">
								<svg class="nav-icon" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
									<path d="M18 11V6a2 2 0 0 0-2-2v0a2 2 0 0 0-2 2v0"></path>
									<path d="M14 10V4a2 2 0 0 0-2-2v0a2 2 0 0 0-2 2v2"></path>
									<path d="M10 10.5V6a2 2 0 0 0-2-2v0a2 2 0 0 0-2 2v8"></path>
									<path d="M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15"></path>
								</svg>
								Drag & Drop
							</a>
						</li>
						<li class="nav-item">
							<a href="#actions" class="nav-link">
								<svg class="nav-icon" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
									<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon>
								</svg>
								Action Buttons
							</a>
						</li>
						<li class="nav-item">
							<a href="#solver-upload" class="nav-link">
								<svg class="nav-icon" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
									<circle cx="12" cy="12" r="3"></circle>
									<path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
								</svg>
								Solver Upload
							</a>
						</li>
					</ul>
					
					<div class="nav-footer">
						<a href="#" class="scroll-to-top">
							↑ Back to top
						</a>
					</div>
				</nav>

				<!-- Main Content -->
				<div class="guide-content">
					<!-- Space Explorer Section -->
					<section id="space-explorer" class="content-section">
						<div class="section-header">
							<h2 class="section-title">
								<svg class="section-icon" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
									<circle cx="12" cy="12" r="10"></circle>
									<polygon points="16.24 7.76 14.12 14.12 7.76 16.24 9.88 9.88 16.24 7.76"></polygon>
								</svg>
								Space Explorer
							</h2>
							<span class="difficulty-badge badge--beginner">Beginner</span>
						</div>
						
						<div class="section-intro">
							<p>The Space Explorer is your central hub for navigating and managing StarExec resources. 
							Access it from <strong>Spaces → Explore</strong> in the main navigation.</p>
						</div>

						<div class="feature-grid">
							<div class="feature-card">
								<div class="feature-header">
									<div class="feature-icon feature-icon--left">
										<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
											<path d="M3 3h7v7H3zM14 3h7v7h-7zM14 14h7v7h-7zM3 14h7v7H3z" 
												stroke="currentColor" stroke-width="2"/>
										</svg>
									</div>
									<h3 class="feature-title">Space Tree (Left Panel)</h3>
								</div>
								<p class="feature-description">
									Hierarchical view of all spaces you have access to. 
									Click any space to view its contents on the right panel.
								</p>
							</div>

							<div class="feature-card">
								<div class="feature-header">
									<div class="feature-icon feature-icon--right">
										<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
											<rect x="3" y="3" width="18" height="18" rx="2" 
												stroke="currentColor" stroke-width="2"/>
											<path d="M3 9h18M9 21V9" 
												stroke="currentColor" stroke-width="2"/>
										</svg>
									</div>
									<h3 class="feature-title">Details Panel (Right)</h3>
								</div>
								<p class="feature-description">
									Displays comprehensive information about the selected space, 
									including jobs, solvers, benchmarks, and users.
								</p>
							</div>
						</div>

						<div class="tip-box">
							<div class="tip-icon">💡</div>
							<div class="tip-content">
								<strong>Pro Tip:</strong> Use keyboard shortcuts for faster navigation: 
								<kbd>↑</kbd> and <kbd>↓</kbd> to move between spaces, 
								<kbd>→</kbd> to expand, <kbd>←</kbd> to collapse.
							</div>
						</div>
					</section>

					<!-- Space Information Section -->
					<section id="space-info" class="content-section">
						<div class="section-header">
							<h2 class="section-title">
								<svg class="section-icon" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
									<line x1="18" y1="20" x2="18" y2="10"></line>
									<line x1="12" y1="20" x2="12" y2="4"></line>
									<line x1="6" y1="20" x2="6" y2="14"></line>
								</svg>
								Space Information
							</h2>
							<span class="difficulty-badge badge--beginner">Beginner</span>
						</div>

						<div class="section-intro">
							<p>Each space contains multiple resource types organized in interactive tables. 
							Click any row to view detailed information.</p>
						</div>

						<div class="resource-grid">
							<div class="resource-card">
								<div class="resource-icon resource-icon--jobs">
									<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
										<path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z" 
											fill="white"/>
									</svg>
								</div>
								<h3 class="resource-title">Jobs</h3>
								<p class="resource-description">Running and completed computational tasks</p>
							</div>

							<div class="resource-card">
								<div class="resource-icon resource-icon--solvers">
									<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
										<path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" 
											stroke="white" stroke-width="2"/>
									</svg>
								</div>
								<h3 class="resource-title">Solvers</h3>
								<p class="resource-description">Logic solving tools and configurations</p>
							</div>

							<div class="resource-card">
								<div class="resource-icon resource-icon--benchmarks">
									<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
										<path d="M9 19v-6a2 2 0 012-2h2a2 2 0 012 2v6a2 2 0 01-2 2h-2a2 2 0 01-2-2z" 
											stroke="white" stroke-width="2"/>
										<path d="M9 11V6l-4 6h6l-2 6" 
											stroke="white" stroke-width="2"/>
									</svg>
								</div>
								<h3 class="resource-title">Benchmarks</h3>
								<p class="resource-description">Test problems and datasets</p>
							</div>

							<div class="resource-card">
								<div class="resource-icon resource-icon--users">
									<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
										<path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2M9 11a4 4 0 100-8 4 4 0 000 8zM23 21v-2a4 4 0 00-3-3.87M16 3.13a4 4 0 010 7.75" 
											stroke="white" stroke-width="2"/>
									</svg>
								</div>
								<h3 class="resource-title">Users</h3>
								<p class="resource-description">Space members and permissions</p>
							</div>

							<div class="resource-card">
								<div class="resource-icon resource-icon--subspaces">
									<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
										<path d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" 
											stroke="white" stroke-width="2"/>
									</svg>
								</div>
								<h3 class="resource-title">Subspaces</h3>
								<p class="resource-description">Nested organizational units</p>
							</div>
						</div>
					</section>

					<!-- Drag and Drop Section -->
					<section id="drag-drop" class="content-section">
						<div class="section-header">
							<h2 class="section-title">
								<svg class="section-icon" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
									<path d="M18 11V6a2 2 0 0 0-2-2v0a2 2 0 0 0-2 2v0"></path>
									<path d="M14 10V4a2 2 0 0 0-2-2v0a2 2 0 0 0-2 2v2"></path>
									<path d="M10 10.5V6a2 2 0 0 0-2-2v0a2 2 0 0 0-2 2v8"></path>
									<path d="M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15"></path>
								</svg>
								Drag and Drop Operations
							</h2>
							<span class="difficulty-badge badge--intermediate">Intermediate</span>
						</div>

						<div class="section-intro">
							<p>StarExec supports intuitive drag-and-drop operations for managing resources. 
							All drags move from the <strong>right panel → left space tree</strong>.</p>
						</div>

						<div class="operation-list">
							<div class="operation-card operation-card--add">
								<div class="operation-header">
									<div class="operation-icon">
										<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
											<path d="M12 5v14M5 12h14" 
												stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
										</svg>
									</div>
									<h3 class="operation-title">Add Resources</h3>
								</div>
								<p class="operation-description">
									Drag benchmarks, solvers, or users from the right panel to any space 
									in the left tree to add them to that space.
								</p>
								<div class="operation-steps">
									<div class="step">
										<span class="step-number">1</span>
										<span>Select item(s) in right panel</span>
									</div>
									<div class="step">
										<span class="step-number">2</span>
										<span>Drag to target space in tree</span>
									</div>
									<div class="step">
										<span class="step-number">3</span>
										<span>Release to add</span>
									</div>
								</div>
							</div>

							<div class="operation-card operation-card--delete">
								<div class="operation-header">
									<div class="operation-icon">
										<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
											<path d="M3 6h18M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2" 
												stroke="currentColor" stroke-width="2"/>
										</svg>
									</div>
									<h3 class="operation-title">Delete Resources</h3>
								</div>
								<p class="operation-description">
									Drag items to the trash can icon that appears near the top of the screen 
									to remove them from the current space.
								</p>
								<div class="warning-box">
									<svg width="16" height="16" viewBox="0 0 24 24" fill="none">
										<path d="M12 9v4M12 17h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" 
											stroke="currentColor" stroke-width="2"/>
									</svg>
									<span>This action removes the association but doesn't delete the resource permanently</span>
								</div>
							</div>
						</div>
					</section>

					<!-- Action Buttons Section -->
					<section id="actions" class="content-section">
						<div class="section-header">
							<h2 class="section-title">
								<svg class="section-icon" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
									<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon>
								</svg>
								Action Buttons
							</h2>
							<span class="difficulty-badge badge--intermediate">Intermediate</span>
						</div>

						<div class="section-intro">
							<p>Located at the bottom of the right panel, these buttons provide quick access 
							to common operations.</p>
						</div>

						<div class="action-list">
							<div class="action-item">
								<div class="action-number">1</div>
								<div class="action-content">
									<h3 class="action-title">Create Job</h3>
									<p class="action-description">
										Launch a multi-step wizard to configure and submit computational jobs. 
										You'll select parameters, choose benchmarks and solvers, then submit 
										for execution.
									</p>
									<div class="action-workflow">
										<span class="workflow-step">Parameters</span>
										<span class="workflow-arrow">→</span>
										<span class="workflow-step">Benchmarks</span>
										<span class="workflow-arrow">→</span>
										<span class="workflow-step">Solvers</span>
										<span class="workflow-arrow">→</span>
										<span class="workflow-step">Submit</span>
									</div>
								</div>
							</div>

							<div class="action-item">
								<div class="action-number">2</div>
								<div class="action-content">
									<h3 class="action-title">Add Subspace</h3>
									<p class="action-description">
										Create a new organizational unit within the current space for better 
										resource management and access control.
									</p>
								</div>
							</div>

							<div class="action-item">
								<div class="action-number">3</div>
								<div class="action-content">
									<h3 class="action-title">Upload Benchmarks</h3>
									<p class="action-description">
										Upload a zipped directory of benchmarks. The directory structure 
										automatically creates a corresponding space hierarchy.
									</p>
								</div>
							</div>

							<div class="action-item">
								<div class="action-number">4</div>
								<div class="action-content">
									<h3 class="action-title">XML Import/Export</h3>
									<p class="action-description">
										Upload or download XML representations of space hierarchies using 
										existing benchmarks and solvers.
									</p>
								</div>
							</div>

							<div class="action-item">
								<div class="action-number">5</div>
								<div class="action-content">
									<h3 class="action-title">Edit Space</h3>
									<p class="action-description">
										Modify basic space information including name, description, and permissions.
									</p>
								</div>
							</div>
						</div>
					</section>

					<!-- Solver Upload Section -->
					<section id="solver-upload" class="content-section">
						<div class="section-header">
							<h2 class="section-title">
								<svg class="section-icon" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
									<circle cx="12" cy="12" r="3"></circle>
									<path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
								</svg>
								Solver Upload Requirements
							</h2>
							<span class="difficulty-badge badge--advanced">Advanced</span>
						</div>

						<div class="section-intro">
							<p>Uploading a solver requires specific file structure and naming conventions. 
							Follow these requirements carefully to ensure successful deployment.</p>
						</div>

						<div class="requirement-list">
							<div class="requirement-item">
								<div class="requirement-header">
									<div class="requirement-badge">1</div>
									<h3 class="requirement-title">Configuration Files</h3>
								</div>
								<div class="requirement-content">
									<p>Configuration files (run scripts) must start with the prefix:</p>
									<div class="code-block">
										<code>starexec_run_*</code>
									</div>
									<p class="requirement-note">
										Everything after the prefix becomes the configuration name in StarExec
									</p>
								</div>
							</div>

							<div class="requirement-item">
								<div class="requirement-header">
									<div class="requirement-badge">2</div>
									<h3 class="requirement-title">Directory Structure</h3>
								</div>
								<div class="requirement-content">
									<p>Place all configurations in a <code>/bin</code> folder at the top level:</p>
									<div class="directory-tree">
										<div class="tree-item tree-item--folder">
											📁 solver-archive/
											<div class="tree-children">
												<div class="tree-item tree-item--folder">
													📁 bin/
													<div class="tree-children">
														<div class="tree-item">📄 starexec_run_default</div>
														<div class="tree-item">📄 starexec_run_optimized</div>
													</div>
												</div>
												<div class="tree-item tree-item--folder">📁 lib/</div>
												<div class="tree-item">📄 README.md</div>
											</div>
										</div>
									</div>
								</div>
							</div>

							<div class="requirement-item">
								<div class="requirement-header">
									<div class="requirement-badge">3</div>
									<h3 class="requirement-title">Script Arguments</h3>
								</div>
								<div class="requirement-content">
									<p>Your run script has access to these variables:</p>
									<div class="variable-list">
										<div class="variable-item">
											<code class="variable-name">$1</code>
											<span class="variable-description">Absolute path to benchmark input file</span>
										</div>
										<div class="variable-item">
											<code class="variable-name">$STAREXEC_WALLCLOCK</code>
											<span class="variable-description">Wallclock timeout in seconds</span>
										</div>
										<div class="variable-item">
											<code class="variable-name">$STAREXEC_CPU_LIMIT</code>
											<span class="variable-description">CPU timeout in seconds</span>
										</div>
									</div>
								</div>
							</div>
						</div>

						<div class="example-box">
							<div class="example-header">
								<svg width="20" height="20" viewBox="0 0 24 24" fill="none">
									<path d="M9 12h6M9 16h6M17 21H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" 
										stroke="currentColor" stroke-width="2"/>
								</svg>
								<span>Example: starexec_run_default</span>
							</div>
							<pre class="example-code">#!/bin/bash
# StarExec configuration script
# Runs solver with benchmark from $1

SOLVER_BIN="./bin/my-solver"
BENCHMARK="$1"
TIMEOUT="$STAREXEC_CPU_LIMIT"

# Execute solver
$SOLVER_BIN --input "$BENCHMARK" --timeout "$TIMEOUT"</pre>
						</div>
					</section>
				</div>
			</div>
		</div>
	</main>

</star:template>