package org.starexec.jobs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.starexec.data.database.Benchmarks;
import org.starexec.data.database.ErrorLogs;
import org.starexec.data.database.Solvers;
import org.starexec.data.to.FinishedSolverBuild;
import org.starexec.data.to.SolverBuildStatus.SolverBuildStatusCode;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.jobs.SolverBuildRecorder.Outcome;
import org.starexec.logger.StarLevel;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.starexec.test.TemporaryFolders.newFolder;

/**
 * What a finished build job does to its solver. Only the newest build job of a solver that is waiting for
 * a build gets an outcome; every other finished build job is only retired.
 *
 * <p>The passes at the end run against Mockito doubles of the database: what a pass does when a read, an
 * update or a deletion fails. Their job ids differ from test to test, because the recorder remembers in a
 * static set which jobs it has reported.
 */
public class SolverBuildRecorderTest {

	private static final String SOURCE = "/data/Solvers/3/probe/20261025-09.15.48.756_src";

	private static int nextJobId = 880_000;

	@TempDir
	public File folder;

	private Logger logger;
	private ListAppender<ILoggingEvent> appender;

	@BeforeEach
	public void captureLog() {
		logger = (Logger) LoggerFactory.getLogger(SolverBuildRecorder.class);
		appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach
	public void releaseLog() {
		logger.detachAppender(appender);
		appender.stop();
	}

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
				assertEquals(Outcome.FAILED, outcome(status, SolverBuildStatusCode.UNBUILT, true), status.name());
				checked++;
			}
		}
		assertTrue(checked >= 10, "the final statuses must include failures");
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
			assertEquals(Outcome.NONE, outcome(StatusCode.STATUS_COMPLETE, status, true), status.name());
			assertEquals(Outcome.NONE, outcome(StatusCode.ERROR_RUNSCRIPT, status, true), status.name());
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
				assertEquals(Outcome.NONE, outcome(status, SolverBuildStatusCode.UNBUILT, true), status.name());
			}
		}
	}

	@Test
	public void onlyADirectoryWithSomethingInItCountsAsCopiedBack() throws Exception {
		Path root = folder.toPath();
		assertFalse(SolverBuildRecorder.hasFiles(root.resolve("missing")), "missing");
		Path empty = Files.createDirectory(root.resolve("empty"));
		assertFalse(SolverBuildRecorder.hasFiles(empty), "empty");
		Path file = Files.writeString(root.resolve("file"), "x");
		assertFalse(SolverBuildRecorder.hasFiles(file), "a file");
		Path built = Files.createDirectory(root.resolve("built"));
		Files.createDirectory(built.resolve("bin"));
		assertTrue(SolverBuildRecorder.hasFiles(built), "a directory with something in it");
	}

	@Test
	public void theBuiltPathIsTheSourcePathWithoutItsSuffix() {
		assertEquals("/data/Solvers/3/probe/20261025-09.15.48.756",
				build(StatusCode.STATUS_COMPLETE, SolverBuildStatusCode.UNBUILT, SOURCE, true).getBuiltPath());
		assertNull(build(StatusCode.STATUS_COMPLETE, SolverBuildStatusCode.UNBUILT, "/data/x", true).getBuiltPath());
		assertNull(build(StatusCode.STATUS_COMPLETE, SolverBuildStatusCode.UNBUILT, "_src", true).getBuiltPath());
		assertNull(build(StatusCode.STATUS_COMPLETE, SolverBuildStatusCode.UNBUILT, null, true).getBuiltPath());
	}

	@Test
	public void aPassWhoseReadFailsAppliesNothing() {
		try (Database db = new Database()) {
			db.solvers.when(Solvers::getFinishedBuildsToRecord).thenThrow(new SQLException("connection refused"));

			SolverBuildRecorder.recordFinishedBuilds();

			db.solvers.verify(() -> Solvers.recordBuildOutcome(any(), any()), never());
			db.benchmarks.verify(() -> Benchmarks.delete(anyInt()), never());
			assertEquals(1, logged(Level.ERROR, "could not read the finished build jobs"));
		}
	}

	@Test
	public void aBuildThatCannotBeAppliedDoesNotHoldUpTheNextOne() throws Exception {
		FinishedSolverBuild failing = waitingBuild();
		FinishedSolverBuild next = waitingBuild();
		try (Database db = new Database()) {
			db.solvers.when(Solvers::getFinishedBuildsToRecord).thenReturn(List.of(failing, next));
			db.solvers.when(() -> Solvers.recordBuildOutcome(eq(failing), any()))
					.thenThrow(new SQLException("deadlock detected"));
			db.solvers.when(() -> Solvers.recordBuildOutcome(eq(next), any())).thenReturn(true);

			SolverBuildRecorder.recordFinishedBuilds();

			db.solvers.verify(() -> Solvers.recordBuildOutcome(next, next.getBuiltPath()));
			db.benchmarks.verify(() -> Benchmarks.delete(next.getBenchmarkId()));
			db.benchmarks.verify(() -> Benchmarks.delete(failing.getBenchmarkId()), never());
		}
	}

	@Test
	public void aBuildThatCannotBeAppliedIsTriedEveryPassAndReportedOnce() throws Exception {
		FinishedSolverBuild build = waitingBuild();
		try (Database db = new Database()) {
			db.solvers.when(Solvers::getFinishedBuildsToRecord).thenReturn(List.of(build));
			db.solvers.when(() -> Solvers.recordBuildOutcome(eq(build), any()))
					.thenThrow(new SQLException("deadlock detected"))
					.thenThrow(new SQLException("deadlock detected"))
					.thenReturn(true);

			SolverBuildRecorder.recordFinishedBuilds();
			SolverBuildRecorder.recordFinishedBuilds();
			db.benchmarks.verify(() -> Benchmarks.delete(anyInt()), never());
			SolverBuildRecorder.recordFinishedBuilds();

			db.solvers.verify(() -> Solvers.recordBuildOutcome(eq(build), any()), times(3));
			db.benchmarks.verify(() -> Benchmarks.delete(build.getBenchmarkId()));
			assertEquals(1, logged(Level.ERROR, "could not apply " + build), "reported once, not every pass");
			assertEquals(1, logged(Level.INFO, "recorded " + build + ": built"));
		}
	}

	@Test
	public void aHelperBenchmarkThatCannotBeDeletedIsReported() throws Exception {
		FinishedSolverBuild build = waitingBuild();
		try (Database db = new Database()) {
			db.solvers.when(Solvers::getFinishedBuildsToRecord).thenReturn(List.of(build));
			db.solvers.when(() -> Solvers.recordBuildOutcome(eq(build), any())).thenReturn(true);
			db.benchmarks.when(() -> Benchmarks.delete(build.getBenchmarkId())).thenReturn(false);

			SolverBuildRecorder.recordFinishedBuilds();

			db.solvers.verify(() -> Solvers.recordBuildOutcome(build, build.getBuiltPath()));
			assertEquals(1, logged(Level.WARN, "the helper benchmark " + build.getBenchmarkId() + " of " + build));
		}
	}

	/** Its solver or its build jobs changed after the read. Retired now, the job would never be decided. */
	@Test
	public void aBuildWhoseSolverChangedAfterTheReadIsReadAgainOnTheNextPass() throws Exception {
		FinishedSolverBuild build = waitingBuild();
		try (Database db = new Database()) {
			db.solvers.when(Solvers::getFinishedBuildsToRecord).thenReturn(List.of(build));
			db.solvers.when(() -> Solvers.recordBuildOutcome(eq(build), any())).thenReturn(false);

			SolverBuildRecorder.recordFinishedBuilds();

			db.benchmarks.verify(() -> Benchmarks.delete(anyInt()), never());
			assertEquals(1, logged(Level.INFO, "changed while " + build + " was read"));
		}
	}

	/** The database, as Mockito doubles: the recorder's reads, updates and deletions, and StarLogger's rows. */
	private static final class Database implements AutoCloseable {
		final MockedStatic<Solvers> solvers = mockStatic(Solvers.class);
		final MockedStatic<Benchmarks> benchmarks = mockStatic(Benchmarks.class);
		final MockedStatic<ErrorLogs> errorLogs = mockStatic(ErrorLogs.class);

		Database() {
			errorLogs.when(() -> ErrorLogs.add(anyString(), any(StarLevel.class))).thenReturn(Optional.of(1));
			benchmarks.when(() -> Benchmarks.delete(anyInt())).thenReturn(true);
		}

		@Override
		public void close() {
			errorLogs.close();
			benchmarks.close();
			solvers.close();
		}
	}

	/** A completed build of a solver that waits for it, copied back to its built path. */
	private FinishedSolverBuild waitingBuild() throws IOException {
		int job = nextJobId;
		nextJobId += 10;
		Path built = Files.createDirectory(newFolder(folder).toPath().resolve("20261025-12.00.00.000"));
		Files.writeString(built.resolve("probe-solver"), "BUILT");
		return new FinishedSolverBuild(job, job + 1, StatusCode.STATUS_COMPLETE, job + 2, job + 3, built + "_src",
				SolverBuildStatusCode.UNBUILT, true);
	}

	private long logged(Level level, String text) {
		return appender.list.stream()
				.filter(e -> e.getLevel() == level && e.getFormattedMessage().contains(text))
				.count();
	}

	private static Outcome outcome(StatusCode pair, SolverBuildStatusCode solver, boolean builtSolverHasFiles) {
		return SolverBuildRecorder.outcome(build(pair, solver, SOURCE, true), builtSolverHasFiles);
	}

	private static FinishedSolverBuild build(StatusCode pair, SolverBuildStatusCode solver, String path,
			boolean newest) {
		return new FinishedSolverBuild(11, 12, pair, 13, 14, path, solver, newest);
	}
}
