<%@page contentType="text/html" pageEncoding="UTF-8" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<star:template title="Community Statistics"
               js="common/delaySpinner, lib/jquery.dataTables.min, lib/jquery.jstree, lib/chart.min, explore/statistics"
               css="common/delaySpinner, common/table, details/shared, explore/common, explore/statistics">
	<div id="mainPanel">
		<fieldset id="statistics">
			<legend>statistics</legend>
			<p id="lastUpdate" class="accent"></p>
			<fieldset id="statsTableField" hidden class="expdContainer">
				<details class="metric-definitions">
					<summary>How community statistics are calculated</summary>
					<dl>
						<dt>Users</dt>
						<dd>Users who are members of at least one subspace of the community.</dd>
						<dt>Solvers</dt>
						<dd>Solvers in at least one subspace of the community, counted by ID. A copy with a new ID counts separately.</dd>
						<dt>Benchmarks</dt>
						<dd>Benchmarks in at least one subspace of the community, counted by ID. A copy with a new ID counts separately.</dd>
						<dt>Jobs</dt>
						<dd>Jobs in at least one subspace of the community with at least one finished pair.</dd>
						<dt>Job pairs</dt>
						<dd>Finished pairs from jobs in those subspaces.</dd>
						<dt>Disk use</dt>
						<dd>The total space used by the solvers and benchmarks of the community.</dd>
					</dl>
				</details>
				<table id="statsTable">
					<thead>
					<tr>
						<th>name</th>
						<th>users</th>
						<th>solvers</th>
						<th>benchmarks</th>
						<th>jobs</th>
						<th>job<br>pairs</th>
						<th>disk use</th>
					</tr>
					</thead>
				</table>
			</fieldset>

			<fieldset id="graph" hidden>
				<legend>graphs</legend>
				<div id="communityOverviewContainer" class="chart-container" style="position: relative; height: 400px; width: 100%;">
					<canvas id="communityOverviewCanvas" aria-hidden="true" role="presentation"></canvas>
					<div id="communityEmptyState" hidden>No community data available.</div>
				</div>
				<ul class="actionList">
					<li>
						<button class="compareBtn" id="compareUsers"
						        type="button" aria-pressed="false">users
						</button>
					</li>
					<li>
						<button class="compareBtn" id="compareSolvers"
						        type="button" aria-pressed="false">solvers
						</button>
					</li>
					<li>
						<button class="compareBtn" id="compareBenches"
						        type="button" aria-pressed="false">benchmarks
						</button>
					</li>
					<li>
						<button class="compareBtn" id="compareJobs"
						        type="button" aria-pressed="false">jobs
						</button>
					</li>
					<li>
						<button class="compareBtn" id="compareJobPairs"
						        type="button" aria-pressed="false">job pairs
						</button>
					</li>
					<li>
						<button class="compareBtn" id="compareDiskUse"
						        type="button" aria-pressed="false">disk use
						</button>
					</li>
				</ul>
			</fieldset>

		</fieldset>

	</div>
</star:template>
