package org.starexec.test.unit.jobs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Who owns the files an execution writes, driven through the real {@code functions.bash}.
 *
 * <h2>The invariant</h2>
 *
 * Every mutable artifact that can influence a pair's answer, status, measurements,
 * post-processing or ingestion belongs to exactly one execution attempt. Concurrent attempts
 * must not be able to read, overwrite, append to, delete or rename each other's.
 *
 * <h2>What it was</h2>
 *
 * {@code initWorkspaceVariables} derives every execution path from {@code WORKING_DIR}, and in
 * container mode that was {@code $WORKING_DIR_BASE/sandbox} for every pair. Under Podman and
 * Kubernetes each pair has its own container and its own {@code /app/work}, so that was true
 * enough. Under LocalBackend every pair is a process in one container, so they shared the
 * solver directory, the benchmark directory, the tmp directory and the whole output directory
 * -- stdout, var.out, watcher.out, attributes.txt. {@code copyOutput} then copied whatever was
 * in the shared output directory into each pair's own results, which is how a fast pair's
 * recorded output came to hold a slow pair's solver output.
 *
 * <h2>How these are written</h2>
 *
 * The paths are read back out of the shipped helper rather than recomputed here, so a test
 * cannot agree with a mistake by making the same one. The central assertion is deliberately
 * written against the invariant instead of against the naming scheme: it asks whether any
 * mutable path of one attempt is reachable from another, not whether the directory is called
 * what this change happens to call it.
 */
public class ExecutionWorkspaceIsolationTest {

	private static final Path SGE = Path.of("src/main/java/org/starexec/config/sge");

	/**
	 * The variables that name execution-mutated state. Every one is derived from
	 * {@code WORKING_DIR} and every one was shared before this change.
	 */
	private static final String[] MUTABLE = {
		"WORKING_DIR", "OUT_DIR", "STDOUT_FILE", "VARFILE", "WATCHFILE",
		"LOCAL_TMP_DIR", "LOCAL_SOLVER_DIR", "LOCAL_BENCH_DIR", "BENCH_INPUT_DIR",
		"LOCAL_PREPROCESSOR_DIR", "SAVED_OUTPUT_DIR", "PROCESSED_BENCH_PATH",
	};

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	// ------------------------------------------------------------------ the invariant

	/**
	 * The oracle. Its expectation comes from the ownership rule, not from the implementation:
	 * for two distinct attempts, no mutable path of one may equal, contain, or be contained by
	 * a mutable path of the other. Naming the expected directory would only prove the test and
	 * the code were written by the same hand.
	 */
	@Test
	public void noMutableArtifactIsReachableFromAnotherAttempt() throws Exception {
		Probe probe = new Probe(folder);
		Map<String, String> fast = probe.resolve(11, 7);
		Map<String, String> slow = probe.resolve(12, 8);

		for (String a : MUTABLE) {
			for (String b : MUTABLE) {
				String pa = fast.get(a);
				String pb = slow.get(b);
				assertFalse(
					"attempt A's " + a + " (" + pa + ") is reachable from attempt B's " + b
						+ " (" + pb + ")",
					pa.equals(pb) || isAncestorOf(pa, pb) || isAncestorOf(pb, pa));
			}
		}
	}

	/** And each attempt's own paths are still internally consistent -- all under its own root. */
	@Test
	public void everyMutableArtifactLivesUnderItsOwnAttemptRoot() throws Exception {
		Map<String, String> one = new Probe(folder).resolve(11, 7);
		String root = one.get("WORKING_DIR");

		for (String name : MUTABLE) {
			String p = one.get(name);
			assertTrue(name + " (" + p + ") must live under " + root,
					p.equals(root) || isAncestorOf(root, p));
		}
	}

	// ------------------------------------------------------- what must remain shared

	/**
	 * The solver cache must stay shared, and what keeps it shared is that only the *sandbox*
	 * became per-attempt, not the base it hangs off. Asserted that way round -- against the
	 * relationship between two values the helper produced -- because the cache path itself is
	 * assembled in {@code jobscript}, which this probe does not source: writing the expected
	 * path out here would be the test inventing the answer it then checks.
	 *
	 * <p>This is the assertion that fails if anyone takes the rejected shortcut of making
	 * {@code WORKING_DIR_BASE} per-pair, which would move the cache with it.
	 */
	@Test
	public void onlyTheSandboxIsPerAttemptSoTheCacheStaysShared() throws Exception {
		Probe probe = new Probe(folder);
		Map<String, String> fast = probe.resolve(11, 7);
		Map<String, String> slow = probe.resolve(12, 8);

		String base = fast.get("WORKING_DIR_BASE");
		assertEquals("the base must be the same for both attempts -- the cache hangs off it",
				base, slow.get("WORKING_DIR_BASE"));
		assertTrue(isAncestorOf(base, fast.get("WORKING_DIR")));
		assertTrue(isAncestorOf(base, slow.get("WORKING_DIR")));
		assertNotEquals("...and only the sandbox below it is per-attempt",
				fast.get("WORKING_DIR"), slow.get("WORKING_DIR"));
	}

	// ------------------------------------------------------ attempts, not just pairs

	/**
	 * A rerun is a different attempt of the same pair. It must not be handed the previous
	 * attempt's workspace, because a stale solver, benchmark or output file left there is
	 * indistinguishable from one this run produced.
	 */
	@Test
	public void aRerunOfTheSamePairDoesNotReuseThePreviousAttemptsWorkspace() throws Exception {
		Probe probe = new Probe(folder);
		Map<String, String> first = probe.resolve(11, 7);
		Map<String, String> rerun = probe.resolve(11, 9);

		assertNotEquals("a new execution id must mean a new workspace",
				first.get("WORKING_DIR"), rerun.get("WORKING_DIR"));
	}

	/**
	 * And the backstop for the case the identity alone does not cover: an execution id can be
	 * reused once its job is no longer active, so the workspace is emptied before use. A file
	 * planted by a dead attempt must not survive into the next one.
	 */
	@Test
	public void aStaleArtifactUnderAReusedIdentityIsNotInherited() throws Exception {
		Probe probe = new Probe(folder);
		Map<String, String> first = probe.resolve(11, 7);

		Path stale = Path.of(first.get("OUT_DIR"), "stdout.txt");
		Files.createDirectories(stale.getParent());
		Files.writeString(stale, "PREVIOUS ATTEMPT OUTPUT\n");
		assertTrue(Files.exists(stale));

		probe.resolve(11, 7);   // same pair, same execution id: the recycled case

		assertFalse("a previous attempt's output must not survive into the next attempt",
				Files.exists(stale));
	}

	// ------------------------------------------------------------- crossing, directly

	/**
	 * The defect as it was observed. Two attempts write their own marker to their own
	 * {@code STDOUT_FILE}; neither may end up holding the other's. Before the change both
	 * names resolved to the same file and the second write destroyed the first.
	 */
	@Test
	public void twoAttemptsCannotOverwriteEachOthersSolverOutput() throws Exception {
		Probe probe = new Probe(folder);
		Map<String, String> fast = probe.resolve(11, 7);
		Map<String, String> slow = probe.resolve(12, 8);

		writeMarker(fast.get("STDOUT_FILE"), "MARKER=E2E_FAST");
		writeMarker(slow.get("STDOUT_FILE"), "MARKER=E2E_SLOW");

		assertEquals("MARKER=E2E_FAST", Files.readString(Path.of(fast.get("STDOUT_FILE"))).trim());
		assertEquals("MARKER=E2E_SLOW", Files.readString(Path.of(slow.get("STDOUT_FILE"))).trim());
	}

	/**
	 * The same for runsolver's own verdict files. These are the inputs {@code stats.json} is
	 * computed from, so crossing them is how a measurement would be attributed to the wrong
	 * pair. Measurement crossing was never demonstrated at runtime -- it was a property of the
	 * shared inputs -- and this is the negative control for it.
	 */
	@Test
	public void runsolverVerdictFilesCannotCross() throws Exception {
		Probe probe = new Probe(folder);
		Map<String, String> fast = probe.resolve(11, 7);
		Map<String, String> slow = probe.resolve(12, 8);

		writeMarker(fast.get("VARFILE"), "WCTIME=0.20");
		writeMarker(slow.get("VARFILE"), "WCTIME=1.80");
		writeMarker(fast.get("WATCHFILE"), "FAST WATCHER");
		writeMarker(slow.get("WATCHFILE"), "SLOW WATCHER");

		assertEquals("WCTIME=0.20", Files.readString(Path.of(fast.get("VARFILE"))).trim());
		assertEquals("WCTIME=1.80", Files.readString(Path.of(slow.get("VARFILE"))).trim());
		assertEquals("FAST WATCHER", Files.readString(Path.of(fast.get("WATCHFILE"))).trim());
		assertEquals("SLOW WATCHER", Files.readString(Path.of(slow.get("WATCHFILE"))).trim());
	}

	// ----------------------------------------------------------------- cleanup safety
	//
	// The workspace is removed by removePrivateWorkspace, called from the EXIT trap, so these
	// drive a whole script to exit rather than calling cleanWorkspace directly. That placement
	// is deliberate and both properties below depend on it: the trap runs on every exit path,
	// and it runs after the pair's status has been decided.

	/**
	 * Cleanup reaches the attempt's own tree and nothing else -- neither a concurrent attempt
	 * nor the shared cache.
	 */
	@Test
	public void cleaningOneAttemptLeavesTheOtherAndTheCacheAlone() throws Exception {
		Probe probe = new Probe(folder);
		Map<String, String> victim = probe.resolve(11, 7);
		Map<String, String> bystander = probe.resolve(12, 8);

		Path bystanderFile = Path.of(bystander.get("OUT_DIR"), "stdout.txt");
		Files.createDirectories(bystanderFile.getParent());
		Files.writeString(bystanderFile, "MARKER=E2E_SLOW\n");

		Path cacheEntry = Path.of(victim.get("WORKING_DIR_BASE"), "solvercache", "1700000000", "42");
		Files.createDirectories(cacheEntry);
		Files.writeString(cacheEntry.resolve("solver.bin"), "cached solver\n");

		probe.runToExit(11, 7, "");

		assertFalse("the finished attempt's workspace is gone",
				Files.exists(Path.of(victim.get("WORKING_DIR"))));
		assertTrue("a concurrent attempt's output must survive", Files.exists(bystanderFile));
		assertEquals("MARKER=E2E_SLOW", Files.readString(bystanderFile).trim());
		assertTrue("the shared solver cache must survive",
				Files.exists(cacheEntry.resolve("solver.bin")));
	}

	/**
	 * The workspace is removed on paths that never reach {@code cleanWorkspace}. Before the
	 * removal moved into the EXIT trap it ran only when the stage loop finished, so every early
	 * exit -- a missing dependency, an unreadable watchfile, a processor failure, a limit breach
	 * -- left a directory behind with nothing to reclaim it.
	 */
	@Test
	public void theWorkspaceIsRemovedOnAnEarlyExitToo() throws Exception {
		Probe probe = new Probe(folder);
		Map<String, String> attempt = probe.resolve(11, 7);
		assertTrue(Files.isDirectory(Path.of(attempt.get("WORKING_DIR"))));

		// exit 1 without reaching cleanWorkspace, as every early failure path does.
		probe.runToExit(11, 7, "exit 1\n");

		assertFalse("an early exit must not leak its workspace",
				Files.exists(Path.of(attempt.get("WORKING_DIR"))));
	}

	/**
	 * The regression that matters most here. {@code cleanWorkspace 0} is called by the jobscript
	 * AFTER the pair has already reported STATUS_COMPLETE, and {@code sendStatus} does not set
	 * {@code STATUS_SENT} -- so under {@code set -e} any failing command in that window is turned
	 * into ERROR_BENCHMARK by the EXIT trap. A finished pair would be recorded as a benchmark
	 * error because a directory could not be deleted.
	 *
	 * <p>{@code rm -rf} fails there for ordinary reasons: an orphaned solver descendant still
	 * writing into the tree is enough. So removal must never be able to change a status.
	 */
	@Test
	public void aRemovalThatFailsCannotChangeAFinishedPairsStatus() throws Exception {
		Probe probe = new Probe(folder);

		// The obstruction is created inside the run, after initSandbox has prepared a fresh
		// workspace -- an orphaned solver descendant leaving a directory this process cannot
		// empty. Planting it beforehand would instead exercise the startup guard.
		probe.lastExit = probe.runToExit(11, 7,
				"mkdir -p \"$OUT_DIR/orphan\"\n"
				+ "echo x > \"$OUT_DIR/orphan/still-writing\"\n"
				+ "chmod 500 \"$OUT_DIR/orphan\"\n"
				+ "sendStatus \"$STATUS_COMPLETE\" 1\n");

		assertEquals(
				"a pair that finished must still read as finished, whatever cleanup managed",
				7, probe.statusFor(11));

		// The removal has to have actually failed, or this asserts nothing.
		assertTrue("this test is only meaningful if the removal failed:\n" + probe.lastOutput,
				probe.lastOutput.contains("could not remove"));

		// And the script must still exit 0. LocalBackend replaces the status of any pair whose
		// script exits non-zero with ERROR_GENERAL, so a cleanup failure that escapes as an exit
		// code corrupts the result through Java even though status.json is untouched -- which a
		// status-only assertion cannot see.
		assertEquals("a failed cleanup must not escape as a non-zero exit code",
				0, probe.lastExit);

		probe.chmodBackForCleanup();
	}

	/**
	 * The directory is writable by solver code, so cleanup runs over paths an adversary chose.
	 * {@code rm -rf} unlinks a symlink rather than descending it, and this pins that: a link
	 * planted inside the private tree must not take an external file with it.
	 *
	 * <p>Planted inside the same run, after {@code initSandbox} has created the workspace, so
	 * what removes them is the EXIT trap and not the {@code rm -rf} that prepares a fresh
	 * workspace at startup.
	 */
	@Test
	public void aSolverPlantedSymlinkCannotMakeCleanupEscapeTheWorkspace() throws Exception {
		Probe probe = new Probe(folder);
		Map<String, String> attempt = probe.resolve(11, 7);

		Path outside = folder.newFolder("outside").toPath();
		Path sentinel = outside.resolve("sentinel.txt");
		Files.writeString(sentinel, "MUST SURVIVE\n");

		probe.runToExit(11, 7,
				"mkdir -p \"$OUT_DIR\"\n"
				+ "ln -s \"" + outside + "\" \"$OUT_DIR/escape\"\n"
				+ "ln -s \"" + sentinel + "\" \"$OUT_DIR/escape-file\"\n");

		assertFalse(Files.exists(Path.of(attempt.get("WORKING_DIR"))));
		assertTrue("cleanup must not follow a solver's symlink out of its own tree",
				Files.exists(sentinel));
		assertEquals("MUST SURVIVE", Files.readString(sentinel).trim());
		assertTrue("the directory the link pointed at must still exist", Files.isDirectory(outside));
	}

	// -------------------------------------------------------------------- fail closed

	/**
	 * A pair that cannot get a private workspace must not fall back to a shared one, because
	 * that is the condition being removed. 11 is ERROR_RUNSCRIPT.
	 *
	 * <p>It exits 0 on purpose, as the "unable to secure any sandbox" branch beside it already
	 * does: the status has been reported, and LocalBackend replaces the status of any pair
	 * whose script exits non-zero with ERROR_GENERAL, so exiting 1 would race the monitor for
	 * which of the two the pair keeps. What is asserted is that execution stopped and that the
	 * recorded status is the specific one.
	 */
	@Test
	public void aWorkspaceThatCannotBeCreatedFailsClosed() throws Exception {
		Probe probe = new Probe(folder);
		probe.resolveExpectingFailure(11, 7);

		assertEquals("the pair must be recorded as a runscript error, not left to the trap",
				11, probe.statusFor(11));
	}

	// ------------------------------------------------------------------ CPU affinity

	/**
	 * Isolating the workspace must not have moved the core allocation. In container mode CORES
	 * is read from the kernel's own answer to "which CPUs may this process use", which under
	 * LocalBackend is the single core {@code taskset -c} was given from the lease. Two attempts
	 * therefore report whatever this test process is allowed -- the point is that the value is
	 * still derived from the affinity, not from the workspace.
	 */
	@Test
	public void coreAllocationIsUnchangedByWorkspaceIsolation() throws Exception {
		Probe probe = new Probe(folder);
		String cores = probe.resolve(11, 7).get("CORES");

		assertFalse("CORES must be set", cores.isEmpty());
		assertEquals("CORES must come from the process affinity, not the workspace",
				expectedAllowedCpus(), cores);
	}

	/**
	 * The independent oracle for the line above: read {@code Cpus_allowed_list} directly rather
	 * than trusting the helper's copy of the same logic.
	 */
	private static String expectedAllowedCpus() throws Exception {
		for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
			if (line.startsWith("Cpus_allowed_list:")) {
				return line.split("\\s+")[1];
			}
		}
		throw new AssertionError("this test needs /proc/self/status");
	}

	// ----------------------------------------------------------------------- helpers

	private static boolean isAncestorOf(String ancestor, String descendant) {
		return descendant.startsWith(ancestor.endsWith("/") ? ancestor : ancestor + "/");
	}

	private static void writeMarker(String path, String content) throws Exception {
		Path p = Path.of(path);
		Files.createDirectories(p.getParent());
		Files.writeString(p, content + "\n");
	}

	/** Runs the shipped helper and reports the paths it resolved. */
	private static final class Probe {

		private final Path root;
		private final Path sge;

		Probe(TemporaryFolder folder) throws Exception {
			this.root = folder.newFolder("probe-" + System.nanoTime()).toPath();
			this.sge = root.resolve("sge");
			Files.createDirectories(sge);
			Files.createDirectories(root.resolve("work"));
			for (String name : new String[]{
					"functions.bash", "status_codes.bash", "timestamper.bash"}) {
				Path from = SGE.resolve(name);
				if (Files.exists(from)) {
					Files.copy(from, sge.resolve(name));
				}
			}
		}

		private String preamble(int pairId, int execId, String workBase) {
			return "export SCRIPT_DIR=\"" + sge + "\"\n"
					+ "export STAREXEC_OUTPUT_DIR=\"" + root + "/out/" + pairId + "\"\n"
					+ "export CONTAINER_MODE=true\n"
					+ "export PAIR_ID=" + pairId + "\n"
					+ "export STAREXEC_JOB_ID=" + execId + "\n"
					+ "export WORKING_DIR_BASE=\"" + workBase + "\"\n"
					+ "export SHARED_DIR=\"" + root + "/shared\"\n"
					+ "export REPORT_HOST=localhost\n"
					+ "export BUILD_JOB=false\n"
					+ "export MAX_MEM=1000000000\n"
					+ "export NODE_MEM=8000000000\n"
					+ "export SANDBOX_USER_ONE=sandbox\n"
					+ "export SANDBOX_USER_TWO=sandbox2\n"
					+ "export HOSTNAME=\"${HOSTNAME:-testhost}\"\n"
					+ "export SCRIPT_PATH=\"" + root + "/script.bash\"\n"
					+ "export BENCH_PATH=\"$(printf '/bench/primary.p' | base64 -w0)\"\n"
					+ "export PAIR_OUTPUT_DIRECTORY=\"$(printf '/out/pair' | base64 -w0)\"\n"
					+ "mkdir -p \"$STAREXEC_OUTPUT_DIR\"\n"
					+ "SOLVER_PATHS=()\n"
					+ "STAGE_NUMBERS=(1)\n"
					+ "STAGE_INDEX=0\n"
					+ ". \"$SCRIPT_DIR/functions.bash\"\n";
		}

		/** initSandbox + initWorkspaceVariables, reporting every resolved path. */
		Map<String, String> resolve(int pairId, int execId) throws Exception {
			String body = preamble(pairId, execId, root + "/work")
					+ "initSandbox\n"
					+ "initWorkspaceVariables\n";
			for (String name : MUTABLE) {
				body += "echo \"" + name + "=$" + name + "\"\n";
			}
			body += "echo \"CORES=$CORES\"\n";
			body += "echo \"WORKING_DIR_BASE=$WORKING_DIR_BASE\"\n";

			Result r = run(body, "resolve-" + pairId + "-" + execId);
			assertEquals("the helper must not abort:\n" + r.out, 0, r.exit);
			return parse(r.out);
		}

		/** The same, against a working dir that cannot be created. */
		Result resolveExpectingFailure(int pairId, int execId) throws Exception {
			Path blocked = root.resolve("blocked");
			Files.writeString(blocked, "not a directory\n");
			String body = preamble(pairId, execId, blocked.toString())
					+ "initSandbox\n"
					+ "echo \"REACHED_EXECUTION=yes\"\n";
			Result r = run(body, "blocked-" + pairId);
			assertFalse("execution must not continue past a failed workspace:\n" + r.out,
					r.out.contains("REACHED_EXECUTION=yes"));
			return r;
		}

		/**
		 * Runs a whole script to exit with the jobscript's own EXIT trap installed, so the
		 * workspace removal under test is the one production uses. {@code body} is inserted
		 * after the workspace exists and before the script ends.
		 */
		int runToExit(int pairId, int execId, String body) throws Exception {
			String script = preamble(pairId, execId, root + "/work")
					+ "trap 'exitJobscript $?' EXIT\n"
					+ "initSandbox\n"
					+ "initWorkspaceVariables\n"
					+ body;
			Result r = run(script, "exit-" + pairId + "-" + execId + "-" + System.nanoTime());
			lastOutput = r.out;
			return r.exit;
		}

		String lastOutput = "";
		int lastExit;

		/** Restores permissions on anything a test deliberately made unremovable. */
		void chmodBackForCleanup() throws Exception {
			try (var walk = Files.walk(root)) {
				walk.forEach(f -> f.toFile().setWritable(true, true));
			} catch (Exception ignored) {
				// best effort; TemporaryFolder will report what it cannot remove
			}
		}

		int statusFor(int pairId) throws Exception {
			Path status = root.resolve("out").resolve(String.valueOf(pairId)).resolve("status.json");
			if (!Files.exists(status)) {
				return -1;
			}
			var m = java.util.regex.Pattern.compile("\"status\"\\s*:\\s*(-?\\d+)")
					.matcher(Files.readString(status));
			return m.find() ? Integer.parseInt(m.group(1)) : -1;
		}

		private Result run(String body, String name) throws Exception {
			File script = new File(root.toFile(), name + ".sh");
			Files.writeString(script.toPath(), body);
			assertEquals("generated script must parse", 0,
					exec(Bash.PATH, "-n", script.getAbsolutePath()).exit);
			return exec(Bash.PATH, script.getAbsolutePath());
		}

		private static Map<String, String> parse(String out) {
			Map<String, String> values = new LinkedHashMap<>();
			for (String line : out.split("\n")) {
				int eq = line.indexOf('=');
				if (eq > 0 && line.substring(0, eq).matches("[A-Z_]+")) {
					values.put(line.substring(0, eq), line.substring(eq + 1).trim());
				}
			}
			List<String> missing = new ArrayList<>();
			for (String name : MUTABLE) {
				if (!values.containsKey(name) || values.get(name).isEmpty()) {
					missing.add(name);
				}
			}
			assertTrue("the helper did not report " + missing + ":\n" + out, missing.isEmpty());
			return values;
		}
	}

	private static Result exec(String... command) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue("command must not hang", p.waitFor(60, TimeUnit.SECONDS));
		return new Result(p.exitValue(), out);
	}

	private static final class Result {
		final int exit;
		final String out;

		Result(int exit, String out) {
			this.exit = exit;
			this.out = out;
		}
	}
}
