package org.starexec.data.to;

import org.starexec.data.to.SolverBuildStatus.SolverBuildStatusCode;
import org.starexec.data.to.Status.StatusCode;

/**
 * A build job whose pair has a final result that has not been applied to its solver yet, with the solver as
 * it is now. A build job counts as applied once its pair's helper benchmark is deleted.
 */
public class FinishedSolverBuild {
	private static final String SOURCE_SUFFIX = "_src";

	private final int jobId;
	private final int pairId;
	private final StatusCode pairStatus;
	private final int benchmarkId;
	private final int solverId;
	private final String solverPath;
	private final SolverBuildStatusCode solverBuildStatus;
	private final boolean newestBuildOfSolver;

	/**
	 * @param jobId the build job
	 * @param pairId its pair
	 * @param pairStatus the pair's final status
	 * @param benchmarkId the pair's helper benchmark
	 * @param solverId the solver the job builds
	 * @param solverPath the solver's path now
	 * @param solverBuildStatus the solver's build status now
	 * @param newestBuildOfSolver whether no later build job exists for the solver
	 */
	public FinishedSolverBuild(int jobId, int pairId, StatusCode pairStatus, int benchmarkId, int solverId,
			String solverPath, SolverBuildStatusCode solverBuildStatus, boolean newestBuildOfSolver) {
		this.jobId = jobId;
		this.pairId = pairId;
		this.pairStatus = pairStatus;
		this.benchmarkId = benchmarkId;
		this.solverId = solverId;
		this.solverPath = solverPath;
		this.solverBuildStatus = solverBuildStatus;
		this.newestBuildOfSolver = newestBuildOfSolver;
	}

	public int getJobId() {
		return jobId;
	}

	public int getPairId() {
		return pairId;
	}

	public StatusCode getPairStatus() {
		return pairStatus;
	}

	public int getBenchmarkId() {
		return benchmarkId;
	}

	public int getSolverId() {
		return solverId;
	}

	public String getSolverPath() {
		return solverPath;
	}

	public SolverBuildStatusCode getSolverBuildStatus() {
		return solverBuildStatus;
	}

	public boolean isNewestBuildOfSolver() {
		return newestBuildOfSolver;
	}

	/**
	 * Where the job script copies the built solver: the solver's path without "_src". A solver to be built
	 * keeps its uploaded files in a directory with that suffix.
	 *
	 * @return the built solver's path, or null if the solver's path does not end in "_src"
	 */
	public String getBuiltPath() {
		if (solverPath == null || !solverPath.endsWith(SOURCE_SUFFIX)
				|| solverPath.length() == SOURCE_SUFFIX.length()) {
			return null;
		}
		return solverPath.substring(0, solverPath.length() - SOURCE_SUFFIX.length());
	}

	@Override
	public String toString() {
		return "build job " + jobId + " (pair " + pairId + ", status " + pairStatus.getVal() + ") of solver "
				+ solverId + " (build status " + solverBuildStatus.getVal() + ")";
	}
}
