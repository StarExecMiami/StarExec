package org.starexec.jobs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.starexec.data.to.FinishedSolverBuild;
import org.starexec.data.to.SolverBuildStatus.SolverBuildStatusCode;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.jobs.SolverBuildRecorder.Outcome;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What a finished build job does to its solver. Only the newest build job of a solver that is waiting for
 * a build gets an outcome; every other finished build job is only retired.
 */
public class SolverBuildRecorderTest {

	private static final String SOURCE = "/data/Solvers/3/probe/20261025-09.15.48.756_src";

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void aCompletedBuildWhoseSolverWasCopiedBackIsBuilt() {
		assertEquals(Outcome.BUILT, outcome(StatusCode.STATUS_COMPLETE, SolverBuildStatusCode.UNBUILT, true));
	}

	/** COMPLETE comes after the copy, so this is a pair recorded before that order, or a copy of nothing. */
	@Test
	public void aCompletedBuildWithNothingCopiedBackFailed() {
		assertEquals(Outcome.FAILED, outcome(StatusCode.STATUS_COMPLETE, SolverBuildStatusCode.UNBUILT, false));
	}

	@Test
	public void everyOtherFinalStatusIsAFailedBuild() {
		int checked = 0;
		for (StatusCode status : StatusCode.values()) {
			if (status.isTerminalExecutionResult() && status != StatusCode.STATUS_COMPLETE) {
				assertEquals(status.name(), Outcome.FAILED, outcome(status, SolverBuildStatusCode.UNBUILT, true));
				checked++;
			}
		}
		assertTrue("the final statuses must include failures", checked >= 10);
	}

	/** RebuildSolver leaves a failed solver's status alone, so its next build starts from BUILD_FAILED. */
	@Test
	public void aRebuildOfAFailedSolverIsRecorded() {
		assertEquals(Outcome.BUILT,
				outcome(StatusCode.STATUS_COMPLETE, SolverBuildStatusCode.BUILD_FAILED, true));
		assertEquals(Outcome.FAILED,
				outcome(StatusCode.ERROR_RUNSCRIPT, SolverBuildStatusCode.BUILD_FAILED, true));
	}

	@Test
	public void aSolverThatIsNotWaitingForABuildIsLeftAlone() {
		for (SolverBuildStatusCode status : new SolverBuildStatusCode[] {SolverBuildStatusCode.BUILT,
				SolverBuildStatusCode.BUILT_BY_STAREXEC, SolverBuildStatusCode.STATUS_UNKNOWN}) {
			assertEquals(status.name(), Outcome.NONE, outcome(StatusCode.STATUS_COMPLETE, status, true));
			assertEquals(status.name(), Outcome.NONE, outcome(StatusCode.ERROR_RUNSCRIPT, status, true));
		}
	}

	@Test
	public void anOlderBuildJobDoesNotDecideForANewerOne() {
		assertEquals(Outcome.NONE, SolverBuildRecorder.outcome(
				build(StatusCode.STATUS_COMPLETE, SolverBuildStatusCode.UNBUILT, SOURCE, false), true));
		assertEquals(Outcome.NONE, SolverBuildRecorder.outcome(
				build(StatusCode.ERROR_RUNSCRIPT, SolverBuildStatusCode.UNBUILT, SOURCE, false), true));
	}

	/** Without "_src" there is no built path: the outcome could only be wrong. */
	@Test
	public void aSolverWhosePathHasNoSourceSuffixIsLeftAlone() {
		String built = SOURCE.substring(0, SOURCE.length() - "_src".length());
		assertEquals(Outcome.NONE, SolverBuildRecorder.outcome(
				build(StatusCode.STATUS_COMPLETE, SolverBuildStatusCode.UNBUILT, built, true), true));
		assertEquals(Outcome.NONE, SolverBuildRecorder.outcome(
				build(StatusCode.ERROR_RUNSCRIPT, SolverBuildStatusCode.UNBUILT, built, true), true));
	}

	/** The query reads final results only; one that is not final must not be decided, or retired. */
	@Test
	public void aPairThatHasNotFinishedDecidesNothing() {
		for (StatusCode status : StatusCode.values()) {
			if (!status.isTerminalExecutionResult()) {
				assertEquals(status.name(), Outcome.NONE, outcome(status, SolverBuildStatusCode.UNBUILT, true));
			}
		}
	}

	@Test
	public void onlyADirectoryWithSomethingInItCountsAsCopiedBack() throws Exception {
		Path root = folder.getRoot().toPath();
		assertFalse("missing", SolverBuildRecorder.hasFiles(root.resolve("missing")));
		Path empty = Files.createDirectory(root.resolve("empty"));
		assertFalse("empty", SolverBuildRecorder.hasFiles(empty));
		Path file = Files.writeString(root.resolve("file"), "x");
		assertFalse("a file", SolverBuildRecorder.hasFiles(file));
		Path built = Files.createDirectory(root.resolve("built"));
		Files.createDirectory(built.resolve("bin"));
		assertTrue("a directory with something in it", SolverBuildRecorder.hasFiles(built));
	}

	@Test
	public void theBuiltPathIsTheSourcePathWithoutItsSuffix() {
		assertEquals("/data/Solvers/3/probe/20261025-09.15.48.756",
				build(StatusCode.STATUS_COMPLETE, SolverBuildStatusCode.UNBUILT, SOURCE, true).getBuiltPath());
		assertNull(build(StatusCode.STATUS_COMPLETE, SolverBuildStatusCode.UNBUILT, "/data/x", true).getBuiltPath());
		assertNull(build(StatusCode.STATUS_COMPLETE, SolverBuildStatusCode.UNBUILT, "_src", true).getBuiltPath());
		assertNull(build(StatusCode.STATUS_COMPLETE, SolverBuildStatusCode.UNBUILT, null, true).getBuiltPath());
	}

	private static Outcome outcome(StatusCode pair, SolverBuildStatusCode solver, boolean builtSolverHasFiles) {
		return SolverBuildRecorder.outcome(build(pair, solver, SOURCE, true), builtSolverHasFiles);
	}

	private static FinishedSolverBuild build(StatusCode pair, SolverBuildStatusCode solver, String path,
			boolean newest) {
		return new FinishedSolverBuild(11, 12, pair, 13, 14, path, solver, newest);
	}
}
