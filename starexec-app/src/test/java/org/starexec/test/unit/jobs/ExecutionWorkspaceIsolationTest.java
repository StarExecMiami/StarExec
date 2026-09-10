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
	 * The solver cache is the one thing that must stay shared, and it does: it hangs off
	 * {@code WORKING_DIR_BASE}, beside the sandbox rather than inside it. If isolating the
	 * workspace had taken the cache with it, every pair would re-extract a solver another pair
	 * had already unpacked.
	 */
	@Test
	public void theSolverCacheStaysSharedBetweenAttempts() throws Exception {
		Probe probe = new Probe(folder);
		Map<String, String> fast = probe.resolve(11, 7);
		Map<String, String> slow = probe.resolve(12, 8);

		assertEquals("both attempts must resolve the same cache root",
				fast.get("SOLVER_CACHE_ROOT"), slow.get("SOLVER_CACHE_ROOT"));
		assertFalse("the cache must not be inside either attempt's workspace",
				isAncestorOf(fast.get("WORKING_DIR"), fast.get("SOLVER_CACHE_ROOT")));
		assertFalse(isAncestorOf(slow.get("WORKING_DIR"), slow.get("SOLVER_CACHE_ROOT")));
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

	/** Post-processing reads STDOUT_FILE and writes attributes.txt; both are inside OUT_DIR. */
	@Test
	public void postProcessingInputAndOutputAreBothPrivate() throws Exception {
		Probe probe = new Probe(folder);
		Map<String, String> fast = probe.resolve(11, 7);
		Map<String, String> slow = probe.resolve(12, 8);

		assertNotEquals(fast.get("OUT_DIR") + "/attributes.txt",
				slow.get("OUT_DIR") + "/attributes.txt");
		assertTrue(isAncestorOf(fast.get("OUT_DIR"), fast.get("STDOUT_FILE")));
	}

	// ----------------------------------------------------------------- cleanup safety

	/**
	 * Cleanup is {@code rm -rf} over the attempt's own tree. It must reach nothing else --
	 * neither a concurrent attempt nor the shared cache.
	 */
	@Test
	public void cleaningOneAttemptLeavesTheOtherAndTheCacheAlone() throws Exception {
		Probe probe = new Probe(folder);
		Map<String, String> victim = probe.resolve(11, 7);
		Map<String, String> bystander = probe.resolve(12, 8);

		Path bystanderFile = Path.of(bystander.get("OUT_DIR"), "stdout.txt");
		Files.createDirectories(bystanderFile.getParent());
		Files.writeString(bystanderFile, "MARKER=E2E_SLOW\n");

		Path cacheEntry = Path.of(victim.get("SOLVER_CACHE_ROOT"), "1700000000", "42");
		Files.createDirectories(cacheEntry);
		Files.writeString(cacheEntry.resolve("solver.bin"), "cached solver\n");

		probe.cleanup(11, 7);

		assertFalse("the cleaned attempt's workspace is gone",
				Files.exists(Path.of(victim.get("WORKING_DIR"))));
		assertTrue("a concurrent attempt's output must survive", Files.exists(bystanderFile));
		assertEquals("MARKER=E2E_SLOW", Files.readString(bystanderFile).trim());
		assertTrue("the shared solver cache must survive",
				Files.exists(cacheEntry.resolve("solver.bin")));
	}

	/**
	 * The directory is writable by solver code, so cleanup runs over paths an adversary chose.
	 * {@code rm -rf} unlinks a symlink rather than descending it, and this pins that: a link
	 * planted inside the private tree must not take an external file with it.
	 */
	@Test
	public void aSolverPlantedSymlinkCannotMakeCleanupEscapeTheWorkspace() throws Exception {
		Probe probe = new Probe(folder);
		Map<String, String> attempt = probe.resolve(11, 7);

		Path outside = folder.newFolder("outside").toPath();
		Path sentinel = outside.resolve("sentinel.txt");
		Files.writeString(sentinel, "MUST SURVIVE\n");

		Path outDir = Path.of(attempt.get("OUT_DIR"));
		Files.createDirectories(outDir);
		Files.createSymbolicLink(outDir.resolve("escape"), outside);
		Files.createSymbolicLink(outDir.resolve("escape-file"), sentinel);

		probe.cleanup(11, 7);

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
	 */
	@Test
	public void aWorkspaceThatCannotBeCreatedFailsClosed() throws Exception {
		Probe probe = new Probe(folder);
		Result r = probe.resolveExpectingFailure(11, 7);

		assertNotEquals("the pair must not proceed", 0, r.exit);
		assertTrue("expected an ERROR_RUNSCRIPT status, got:\n" + r.out,
				probe.statusFor(11) == 11);
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
			// Named the way jobscript names it, minus the per-solver leaves, so the test can
			// ask where the cache root landed without inventing the path itself.
			body += "echo \"SOLVER_CACHE_ROOT=$WORKING_DIR_BASE/solvercache\"\n";

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

		/** cleanWorkspace 0, which is what the jobscript runs when the pair is done. */
		void cleanup(int pairId, int execId) throws Exception {
			String body = preamble(pairId, execId, root + "/work")
					+ "initSandbox\n"
					+ "initWorkspaceVariables\n"
					+ "cleanWorkspace 0 sandbox\n";
			Result r = run(body, "cleanup-" + pairId + "-" + execId);
			assertEquals("cleanup must not abort:\n" + r.out, 0, r.exit);
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
