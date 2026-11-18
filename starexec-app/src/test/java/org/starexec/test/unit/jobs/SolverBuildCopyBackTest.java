package org.starexec.test.unit.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.starexec.test.TemporaryFolders.newFolder;

/**
 * What a build job's helpers leave behind once {@code starexec_build} has run: the built solver and its
 * build log, and nothing else touched.
 *
 * <p>In container mode these helpers are all of a build that runs, because the database calls they made
 * are skipped there; the server records the outcome from the pair's status. They used to delete the
 * runsolver path, which copyDependencies points at the image's own {@code /usr/local/bin/runsolver} in
 * every mode. The application user cannot delete it, so under {@code set -e} every build ended there with
 * ERROR_RUNSCRIPT. These cases run the shipped {@code functions.bash} on disposable files.
 */
public class SolverBuildCopyBackTest {

	@TempDir
	public File folder;

	@Test
	public void theBuiltSolverIsCopiedBackWithoutItsBuildScript() throws Exception {
		Harness h = new Harness(folder, false);
		Result r = h.run("copySolverBack");

		r.assertFinished();
		assertEquals("BUILT", Files.readString(h.built.resolve("bin/probe-solver")));
		assertEquals("RUN", Files.readString(h.built.resolve("bin/starexec_run_default")));
		assertEquals("SOURCE", Files.readString(h.built.resolve("src/README")));
		assertFalse(Files.exists(h.built.resolve("starexec_build")), "the build script is not part of the built solver");
		assertEquals("BUILD SCRIPT", Files.readString(h.solverSrc.resolve("starexec_build")),
				"the uploaded source stays for a rebuild");
		assertFalse(Files.exists(h.bench), "the dummy benchmark's file is deleted");
	}

	@Test
	public void theImagesRunsolverIsLeftAlone() throws Exception {
		Harness h = new Harness(folder, false);
		Path image = h.runsolver.getParent();
		Files.setPosixFilePermissions(image, PosixFilePermissions.fromString("r-xr-xr-x"));
		try {
			Result r = h.run("copySolverBack");

			r.assertFinished();
			assertEquals("RUNSOLVER", Files.readString(h.runsolver));
		} finally {
			Files.setPosixFilePermissions(image, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
	}

	@Test
	public void theBuildLogIsSavedWhereTheSolverPageReadsIt() throws Exception {
		Harness h = new Harness(folder, false);
		h.run("copySolverBack").assertFinished();

		assertEquals("BUILD OUTPUT", Files.readString(h.buildLog));
	}

	@Test
	public void aRebuildReplacesTheBuildLogOfTheLastBuild() throws Exception {
		Harness h = new Harness(folder, false);
		Files.createDirectories(h.buildLog.getParent());
		Files.writeString(h.buildLog, "LAST BUILD");

		h.run("copySolverBack").assertFinished();

		assertEquals("BUILD OUTPUT", Files.readString(h.buildLog));
		assertEquals("BUILT", Files.readString(h.built.resolve("bin/probe-solver")));
	}

	@Test
	public void copyingBackMakesNoDatabaseCall() throws Exception {
		Harness h = new Harness(folder, false);
		Result r = h.run("copySolverBack");

		r.assertFinished();
		assertFalse(r.out.contains("T-DB|"), "the server records the build, not the job script:\n" + r.out);
	}

	@Test
	public void pathsWithSpacesAreCopiedBack() throws Exception {
		Harness h = new Harness(folder, true);
		h.run("copySolverBack").assertFinished();

		assertEquals("BUILT", Files.readString(h.built.resolve("bin/probe-solver")));
		assertFalse(Files.exists(h.built.resolve("starexec_build")));
		assertEquals("BUILD OUTPUT", Files.readString(h.buildLog));
		assertFalse(Files.exists(h.bench));
	}

	/**
	 * The upload creates the built path, but nothing keeps it there. Copied into a path that does not exist,
	 * several entries fail the copy, and a single one would be renamed to it instead of copied into it.
	 */
	@Test
	public void aMissingBuiltPathIsCreated() throws Exception {
		Harness h = new Harness(folder, false);
		Files.delete(h.built);

		h.run("copySolverBack").assertFinished();

		assertEquals("BUILT", Files.readString(h.built.resolve("bin/probe-solver")));
		assertEquals("SOURCE", Files.readString(h.built.resolve("src/README")));
	}

	/** Without "_src" there is no built path to copy to: the solver would be copied onto itself. */
	@Test
	public void aSolverPathWithoutSrcIsRefused() throws Exception {
		Harness h = new Harness(folder, false);
		h.solverPath = h.built;

		Result r = h.run("copySolverBack");

		assertNotEquals(0, r.exit, "the copy must fail the pair:\n" + r.out);
		assertFalse(r.out.contains("T-DONE"), r.out);
		assertTrue(r.out.contains("does not end in _src"), "the refusal must say why:\n" + r.out);
		try (var entries = Files.list(h.built)) {
			assertEquals(0, entries.count(), "nothing is copied");
		}
		assertTrue(Files.exists(h.bench), "nothing is deleted");
	}

	/**
	 * A run pair of the solver leaves a copy of it in the solver cache, under the key a build job of the same
	 * solver looks up too: the solver's id and its configurations' newest timestamp, which no build changes.
	 * A build job builds from the source at the solver's path. A cached copy is the built solver, or the
	 * source as a run pair copied it while the build had failed, and its path leaves copySolverBack nowhere
	 * to copy the build to.
	 */
	@Test
	public void aBuildJobBuildsFromItsSourceWhenTheSolverIsCached() throws Exception {
		Harness h = new Harness(folder, false);
		h.cacheSolver();

		Result r = h.run("checkCache; printf 'T-PATH|%s|%s\\n' \"$SOLVER_PATH\" \"$SOLVER_CACHED\"; copySolverBack");

		r.assertFinished();
		assertTrue(r.out.contains("T-PATH|" + h.solverSrc + "|0\n"),
				"the build must read its solver from the source:\n" + r.out);
		assertEquals("BUILT", Files.readString(h.built.resolve("bin/probe-solver")));
		assertEquals("CACHED RUN", Files.readString(h.cache.resolve("bin/starexec_run_default")),
				"the cached copy is left as it was");
	}

	/** A run pair still copies its solver from the cache. */
	@Test
	public void aRunPairStillCopiesItsSolverFromTheCache() throws Exception {
		Harness h = new Harness(folder, false);
		h.cacheSolver();
		h.buildJob = false;

		Result r = h.run("checkCache; printf 'T-PATH|%s|%s\\n' \"$SOLVER_PATH\" \"$SOLVER_CACHED\"");

		r.assertFinished();
		assertTrue(r.out.contains("T-PATH|" + h.cache + "|1\n"), "a run pair must use the cached copy:\n" + r.out);
	}

	@Test
	public void aFailedBuildSavesItsBuildLogAndMakesNoDatabaseCall() throws Exception {
		Harness h = new Harness(folder, false);
		Result r = h.run("cleanUpAfterKilledBuildJob");

		r.assertFinished();
		assertEquals("BUILD OUTPUT", Files.readString(h.buildLog),
				"a failed build's log is what its owner needs most");
		assertFalse(r.out.contains("T-DB|"), "the server records the build, not the job script:\n" + r.out);
		assertFalse(Files.exists(h.bench), "the dummy benchmark's file is deleted");
		try (var entries = Files.list(h.built)) {
			assertEquals(0, entries.count(), "a failed build copies no solver");
		}
	}

	/** Killed before its output was saved: there is no log to save, and the cleanup still ends. */
	@Test
	public void aBuildKilledBeforeItsOutputWasSavedStillCleansUp() throws Exception {
		Harness h = new Harness(folder, false);
		Files.delete(h.output);

		h.run("cleanUpAfterKilledBuildJob").assertFinished();

		assertFalse(Files.exists(h.buildLog));
		assertFalse(Files.exists(h.bench));
	}

	/**
	 * Saving the build log is best effort: a log that cannot be saved is reported, and the build goes on. The
	 * helpers run under {@code set -e}, and copySolverBack saves the log before it copies the solver, so a failure
	 * let through would end a build whose solver was built as ERROR_RUNSCRIPT, with nothing copied back. A file
	 * where the solver's build log directory goes makes {@code mkdir -p} fail, for root too.
	 */
	@Test
	public void aBuildLogDirectoryThatCannotBeCreatedStillLetsTheSolverBeCopiedBack() throws Exception {
		Harness h = new Harness(folder, false);
		Files.writeString(h.buildLog.getParent(), "NOT A DIRECTORY");

		Result r = h.run("copySolverBack");

		r.assertFinished();
		assertBuildLogNotSaved(h, r);
		assertEquals("BUILT", Files.readString(h.built.resolve("bin/probe-solver")));
		assertTrue(r.out.contains("T-LOG|solver copied back to " + h.built + "\n"), r.out);
		assertFalse(Files.exists(h.bench), "the dummy benchmark's file is deleted");
	}

	/** The copy fails instead: the log's path is a directory that holds a directory named as the copy would be. */
	@Test
	public void aBuildLogThatCannotBeCopiedStillLetsTheSolverBeCopiedBack() throws Exception {
		Harness h = new Harness(folder, false);
		Files.createDirectories(h.buildLog.resolve(h.output.getFileName()));

		Result r = h.run("copySolverBack");

		r.assertFinished();
		assertBuildLogNotSaved(h, r);
		assertEquals("BUILT", Files.readString(h.built.resolve("bin/probe-solver")));
		assertTrue(r.out.contains("T-LOG|solver copied back to " + h.built + "\n"), r.out);
		assertFalse(Files.exists(h.bench), "the dummy benchmark's file is deleted");
	}

	/** A failed build's cleanup saves its log the same way, and still deletes the build's helper benchmark. */
	@Test
	public void aFailedBuildWhoseLogCannotBeSavedStillCleansUp() throws Exception {
		Harness h = new Harness(folder, false);
		Files.writeString(h.buildLog.getParent(), "NOT A DIRECTORY");

		Result r = h.run("cleanUpAfterKilledBuildJob");

		r.assertFinished();
		assertBuildLogNotSaved(h, r);
		assertFalse(Files.exists(h.bench), "the dummy benchmark's file is deleted");
	}

	private static void assertBuildLogNotSaved(Harness h, Result r) {
		assertTrue(r.out.contains("T-LOG|the build log could not be saved in " + h.buildLog.getParent() + "\n"),
				"a build log that cannot be saved must be reported:\n" + r.out);
	}

	private static final class Harness {
		final Path root;
		final Path shared;
		final Path solverSrc;
		final Path built;
		final Path buildLog;
		final Path sandbox;
		final Path runsolver;
		final Path output;
		final Path bench;
		final Path cache;
		Path solverPath;
		boolean buildJob = true;

		/** Laid out as an upload and a finished starexec_build leave it, for solver 7 and pair 42. */
		Harness(File folder, boolean spaces) throws Exception {
			String s = spaces ? " " : "-";
			root = newFolder(folder, "fixture").toPath();
			shared = Files.createDirectory(root.resolve("shared" + s + "data"));
			Path solverDir = Files.createDirectories(shared.resolve("Solvers/3/probe" + s + "solver"));
			solverSrc = Files.createDirectories(solverDir.resolve("20261025-09.15.48.756_src"));
			Files.createDirectories(solverSrc.resolve("bin"));
			Files.writeString(solverSrc.resolve("starexec_build"), "BUILD SCRIPT");
			Files.writeString(solverSrc.resolve("bin/starexec_run_default"), "RUN");
			// The upload creates the built path empty, next to the source.
			built = Files.createDirectory(solverDir.resolve("20261025-09.15.48.756"));
			// The application creates the build log root when it starts.
			Path buildOutput = Files.createDirectories(shared.resolve("Solvers/buildoutput"));
			buildLog = buildOutput.resolve("7/starexec_build_log");
			solverPath = solverSrc;

			sandbox = Files.createDirectories(root.resolve("work" + s + "dir/solver"));
			Files.createDirectories(sandbox.resolve("bin"));
			Files.createDirectories(sandbox.resolve("src"));
			Files.writeString(sandbox.resolve("starexec_build"), "BUILD SCRIPT");
			Files.writeString(sandbox.resolve("bin/starexec_run_default"), "RUN");
			Files.writeString(sandbox.resolve("bin/probe-solver"), "BUILT");
			Files.writeString(sandbox.resolve("src/README"), "SOURCE");

			runsolver = Files.createDirectory(root.resolve("image" + s + "bin")).resolve("runsolver");
			Files.writeString(runsolver, "RUNSOLVER");

			output = Files.createDirectory(root.resolve("pair" + s + "output")).resolve("42.txt");
			Files.writeString(output, "BUILD OUTPUT");

			bench = Files.createDirectory(root.resolve("build" + s + "input")).resolve("starexec_build");
			Files.writeString(bench, "dummy benchmark");

			// Where the jobscript looks for the solver's cached copy; absent unless cacheSolver() runs.
			cache = root.resolve("solver" + s + "cache/7");
		}

		/** A run pair's copy of the solver in the cache, as copyDependencies leaves it. */
		void cacheSolver() throws Exception {
			Files.createDirectories(cache.resolve("bin"));
			Files.writeString(cache.resolve("bin/starexec_run_default"), "CACHED RUN");
			Files.createDirectory(cache.resolve("finished.lock"));
		}

		Result run(String operation) throws Exception {
			ProcessBuilder builder = new ProcessBuilder(Bash.PATH, "-c",
					"SOLVER_PATHS=(); source \"$SCRIPT_DIR/functions.bash\"; "
							+ "log() { printf 'T-LOG|%s\\n' \"$*\"; }; "
							+ "dbExec() { printf 'T-DB|%s\\n' \"$1\"; }; "
							+ operation + "; echo T-DONE");
			var env = builder.environment();
			env.put("SCRIPT_DIR", Path.of("src/main/java/org/starexec/config/sge").toAbsolutePath().toString());
			env.put("SHARED_DIR", shared.toString());
			env.put("WORKING_DIR_BASE", root.toString());
			env.put("BENCH_PATH", encode(bench));
			env.put("PAIR_OUTPUT_DIRECTORY", encode(output.getParent()));
			env.put("CONTAINER_MODE", "true");
			env.put("BUILD_JOB", buildJob ? "true" : "false");
			env.put("PAIR_ID", "42");
			env.put("SOLVER_ID", "7");
			env.put("BENCH_ID", "12");
			env.put("BENCH_NAME", "starexec_build");
			// As the jobscript and copyDependencies set them for a build job.
			env.put("SOLVER_PATH", solverPath.toString());
			env.put("LOCAL_SOLVER_DIR", sandbox.toString());
			env.put("LOCAL_CONFIG_PATH", sandbox.resolve("starexec_build").toString());
			env.put("LOCAL_RUNSOLVER_PATH", runsolver.toString());
			env.put("SOLVER_CACHE_PATH", cache.toString());
			env.put("SOLVER_CACHED", "0");
			Path log = root.resolve("run-" + System.nanoTime() + ".log");
			builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
			Process process = builder.start();
			try {
				assertTrue(process.waitFor(20, TimeUnit.SECONDS), "the helper must finish");
				return new Result(process.exitValue(), Files.readString(log));
			} finally {
				process.destroyForcibly();
				assertTrue(process.waitFor(5, TimeUnit.SECONDS), "the helper must be reaped");
			}
		}

		private static String encode(Path path) {
			return Base64.getEncoder().encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
		}
	}

	private static final class Result {
		final int exit;
		final String out;

		Result(int exit, String out) {
			this.exit = exit;
			this.out = out;
		}

		void assertFinished() {
			assertEquals(0, exit, "the helper must not fail:\n" + out);
			assertTrue(out.contains("T-DONE"), "the helper must return:\n" + out);
		}
	}
}
