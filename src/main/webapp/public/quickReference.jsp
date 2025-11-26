<%@page contentType="text/html" pageEncoding="UTF-8" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<star:template title="Quick Reference Guide" css="explore/quickRef">
	<main role="main" class="quick-ref-container">
		<section aria-labelledby="quick-ref-heading">
			<h1 id="quick-ref-heading" class="sr-only">Quick Reference Guide</h1>
			<div id="support">
				<p>This a quick reference to help you accomplish basic tasks in
					StarExec.
					For a more extensive guide, see the <a
							href="http://wiki.uiowa.edu/display/stardev/User+Guide" rel="help">User
						Guide</a>.
					This guide is organized by the method one uses to accomplish tasks.
				</p>

				<section aria-labelledby="space-explorer-heading">
					<h2 id="space-explorer-heading">Space Explorer</h2>
					<p>The main page on StarExec is the Space Explorer. This page is
						accessed
						by selecting the Spaces tab in the upper right and selecting Explore
						in the
						dropdown menu. The left hand side of the Space Explorer is a
						representation
						of the space hierarchy that you have access to.
						When you click on a space on the space tree, you can see information
						about it
						on the right hand side.
					</p>
				</section>

				<section aria-labelledby="space-info-heading">
					<h2 id="space-info-heading">Space Information</h2>
					<p>
						All of the following are represented in tables with rows that can be
						clicked
						on for additional information:
					</p>
					<ul class="support-list">
						<li>Jobs</li>
						<li>Solvers</li>
						<li>Benchmarks</li>
						<li>Users</li>
						<li>Subspaces</li>
					</ul>
				</section>

				<section aria-labelledby="drag-drop-heading">
					<h2 id="drag-drop-heading">Dragging and Dropping</h2>
					<p>
						All drags are from the right hand side of the Space Explorer page to
						the left
					</p>

					<h3>Drag and Drop uses</h3>
					<ul class="support-list">
						<li>Add benchmarks, users, or solvers to a space by dragging them to
							the space in the space explorer tree
						</li>
						<li>Delete a space, benchmark or user by dragging to a trash can
							icon that appears near the top of the screen
						</li>
					</ul>
				</section>

				<section aria-labelledby="action-buttons-heading">
					<h2 id="action-buttons-heading">Action buttons</h2>
					<p>At the bottom of the right hand side</p>
					<ul class="support-list">
						<li>create job - this will take you to a sequence of pages where you
							enter job parameters, select benchmarks and solvers, and submit
							the job
						</li>
						<li>add subspace to the currently selected space</li>
						<li>upload benchmarks - typically you will be creating a space
							hierarchy corresponding to the directory structure of a zipped
							directory of benchmarks
						</li>
						<li>upload and download xml representations of space hierarchies -
							an upload will create the space hierarchy
							using already existing benchmarks and solvers.
						</li>
						<li>edit space - edit basic information about the space</li>
						<li>upload solver
							<ul class="support-sublist">
								<li>upload a compressed directory that contains at least one
									configuration(run script) that will tell starexec how to
									run your
									solver on the execution nodes
								</li>
								<li>configuration file name must have the prefix
									"starexec_run_". Everything after the prefix will be the
									name of the configuration
									within starexec
								</li>
								<li>configurations must be placed in a /bin folder in the
									top level directory of the uploaded archive
								</li>
								<li>Your script has access to a few different arguments and
									environmental variables, the most important being $1,
									the
									absolute path the the benchmark input file
								</li>
							</ul>
						</li>
					</ul>
				</section>
			</div>
		</section>
	</main>
</star:template>
