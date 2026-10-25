package org.starexec.jobs;

import org.starexec.data.database.Benchmarks;
import org.starexec.data.database.Solvers;
import org.starexec.data.to.FinishedSolverBuild;
import org.starexec.data.to.SolverBuildStatus.SolverBuildStatusCode;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.logger.StarLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Applies the outcome of finished solver builds to their solvers.
 *
 * <p>A solver uploaded with a {@code starexec_build} script is built by a build job, and stays "being built"
 * until that job's outcome is applied: a rebuild is refused, and no job that uses the solver is dispatched.
 * The job script used to apply the outcome itself, through a database connection it does not have in
 * container mode, so on the Local, Podman and Kubernetes backends nothing applied it. A periodic task now
 * does, on every backend, once per build job: applying a job deletes its pair's helper benchmark.
 */
public final class SolverBuildRecorder {
	private static final StarLogger log = StarLogger.getLogger(SolverBuildRecorder.class);

	/** Build jobs already reported as not applicable for now, so that each is reported once, not every pass. */
	private static final Set<Integer> reported = ConcurrentHashMap.newKeySet();

	/** What a finished build job does to its solver. */
	enum Outcome {
		/** The solver is built, at its path without "_src". */
		BUILT,
		/** The build failed. The solver keeps its source path, so it can be rebuilt. */
		FAILED,
		/** Nothing. The job is only marked applied. */
		NONE
	}

	private SolverBuildRecorder() {
	}

	/**
	 * What a finished build job does to its solver. Only the newest build job of a solver that waits for a
	 * build decides: one that is unbuilt, or failed and being rebuilt, with a path that ends in "_src". The
	 * build worked if its pair completed and something was copied to the built path; the job script copies
	 * the solver back before it reports the pair complete.
	 *
	 * @param build a build job with a final result
	 * @param builtSolverHasFiles whether the build's built path holds anything
	 * @return the outcome to apply
	 */
	static Outcome outcome(FinishedSolverBuild build, boolean builtSolverHasFiles) {
		if (!build.getPairStatus().isTerminalExecutionResult() || !build.isNewestBuildOfSolver()
				|| build.getBuiltPath() == null) {
			return Outcome.NONE;
		}
		SolverBuildStatusCode status = build.getSolverBuildStatus();
		if (status != SolverBuildStatusCode.UNBUILT && status != SolverBuildStatusCode.BUILD_FAILED) {
			return Outcome.NONE;
		}
		return build.getPairStatus() == StatusCode.STATUS_COMPLETE && builtSolverHasFiles ? Outcome.BUILT
				: Outcome.FAILED;
	}

	/** Applies every finished build job that has not been applied yet, oldest first. */
	public static void recordFinishedBuilds() {
		List<FinishedSolverBuild> builds;
		try {
			builds = Solvers.getFinishedBuildsToRecord();
		} catch (SQLException e) {
			log.error("recordFinishedBuilds", "could not read the finished build jobs", e);
			return;
		}
		for (FinishedSolverBuild build : builds) {
			try {
				record(build);
			} catch (Exception e) {
				// One build that cannot be applied must not hold up the others. It is tried again next pass.
				if (reported.add(build.getJobId())) {
					log.error("recordFinishedBuilds", "could not apply " + build + "; retrying every pass", e);
				}
			}
		}
	}

	private static void record(FinishedSolverBuild build) throws Exception {
		if (!build.getPairStatus().isTerminalExecutionResult()) {
			// The query reads final results only. Anything else is left alone: applying it would retire the
			// job before it has an outcome.
			return;
		}
		Outcome outcome = outcome(build, true);
		if (outcome == Outcome.BUILT) {
			boolean copied;
			try {
				copied = hasFiles(Path.of(build.getBuiltPath()));
			} catch (IOException e) {
				// Not "nothing was copied": a shared file system that cannot be read now says nothing about
				// the build, so it is left for a later pass rather than recorded as failed.
				if (reported.add(build.getJobId())) {
					log.warn("recordFinishedBuilds", "cannot read " + build.getBuiltPath() + " for " + build
							+ "; retrying every pass", e);
				}
				return;
			}
			outcome = outcome(build, copied);
		}

		if (outcome == Outcome.NONE) {
			if (build.isNewestBuildOfSolver() && build.getBuiltPath() == null && waitsForBuild(build)) {
				log.warn("recordFinishedBuilds", "solver " + build.getSolverId() + " waits for a build, but its path "
						+ build.getSolverPath() + " does not end in _src; " + build + " is not applied to it");
			}
		} else {
			String builtPath = outcome == Outcome.BUILT ? build.getBuiltPath() : null;
			if (Solvers.recordBuildOutcome(build.getSolverId(), build.getSolverPath(), builtPath)) {
				log.info("recordFinishedBuilds", "recorded " + build + ": "
						+ (outcome == Outcome.BUILT ? "built, at " + builtPath : "failed"));
			} else {
				log.info("recordFinishedBuilds", "solver " + build.getSolverId() + " changed while " + build
						+ " was read, so it is not applied to it");
			}
		}

		// Last, so that a pass that fails before this applies the job again. That is safe: the solver is only
		// changed while it still waits for a build at the same path.
		if (!Benchmarks.delete(build.getBenchmarkId())) {
			log.warn("recordFinishedBuilds", "the helper benchmark " + build.getBenchmarkId() + " of " + build
					+ " could not be deleted completely");
		}
		reported.remove(build.getJobId());
	}

	private static boolean waitsForBuild(FinishedSolverBuild build) {
		return build.getSolverBuildStatus() == SolverBuildStatusCode.UNBUILT
				|| build.getSolverBuildStatus() == SolverBuildStatusCode.BUILD_FAILED;
	}

	/**
	 * Whether {@code dir} is a directory with anything in it.
	 *
	 * @throws IOException if that cannot be told
	 */
	static boolean hasFiles(Path dir) throws IOException {
		BasicFileAttributes attributes;
		try {
			attributes = Files.readAttributes(dir, BasicFileAttributes.class);
		} catch (NoSuchFileException e) {
			return false;
		}
		if (!attributes.isDirectory()) {
			return false;
		}
		try (Stream<Path> entries = Files.list(dir)) {
			return entries.findAny().isPresent();
		}
	}
}
