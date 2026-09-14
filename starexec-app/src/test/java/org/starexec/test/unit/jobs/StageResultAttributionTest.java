package org.starexec.test.unit.jobs;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.backend.ContainerJobMonitor;
import org.starexec.backend.ExecutionRef;
import org.starexec.backend.KubernetesNativeBackend;
import org.starexec.backend.LocalBackend;
import org.starexec.backend.LocalJobMonitor;
import org.starexec.backend.PodmanBackend;
import org.starexec.backend.StageStatusSnapshots;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.JobPairs.PairStatusLookupState;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.database.StageStatusBatchResult;
import org.starexec.data.to.Status.StatusCode;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Which stage a multi-stage pair's post-processor attributes are recorded against, from the real
 * {@code functions.bash} through each container-mode monitor's real ingestion (#179).
 *
 * <p>Reproduces the qualification finding on integrated jobs 2-4 (two E stages): stage 1's output
 * said Theorem, yet the database held the final stage's SZS UNK under stage 1 and nothing under
 * stage 2.
 *
 * <h2>How faithful the producer is</h2>
 *
 * The shipped helper, not a model of it. The pair starts through {@code sendNode}, which is where
 * the helper declares the per-stage protocol; each stage reuses the one {@code $WORKING_DIR/output}
 * the real job script uses and runs the real {@code copyOutput} with a real post-processor, and
 * the real {@code cleanForNextStage} empties that directory between stages. Stage outcomes are
 * sent with the calls the jobscript makes. Only {@code copyOutputNoStats} is stubbed: it saves
 * solver output into the pair's output tree, which is not under test.
 *
 * <h2>How faithful the database is</h2>
 *
 * {@code JobPairs} is mocked, and the mock refuses what the database refuses: a precise status
 * for a stage the pair does not have ({@code REJECTED_INVALID_STAGE}, which covers stage 0), and
 * an earlier-stage batch naming such a stage ({@code REJECTED_UNKNOWN_STAGE}). Every attribute
 * write is recorded as a separate call, so a double or partial write is visible, and each stage's
 * attributes are compared as an exact map.
 *
 * <p>Every test runs once per backend. Local polls while a pair runs, so the mid-run tests are
 * Local's alone; Podman and Kubernetes ingest once, after the job has exited.
 */
@RunWith(Parameterized.class)
public class StageResultAttributionTest {

	private static final Path SGE = Path.of("src/main/java/org/starexec/config/sge");

	private static final int PAIR = 4242;

	/** Two keys per stage, distinct values, so a leaked or stale key cannot hide. */
	private static final String THEOREM = "starexec-result=Theorem\nSZSStatus=THM\n";
	private static final String UNKNOWN = "starexec-result=Unknown\nSZSStatus=UNK\nSZSOutput=None\n";
	private static final String SATISFIABLE = "starexec-result=Satisfiable\nSZSStatus=SAT\n";

	enum Backend { LOCAL, CONTAINER, KUBERNETES }

	@Parameterized.Parameters(name = "{0}")
	public static Object[] backends() {
		return Backend.values();
	}

	@Parameterized.Parameter
	public Backend backend;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	// ------------------------------------------------------------------ placement (W1, W2)

	/** The finding itself: each stage's own set, on its own stage, written once. */
	@Test
	public void eachStageKeepsItsOwnAttributes() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);

		assertRecorded(ingest(pair, 1, 2), Map.of(1, THEOREM, 2, UNKNOWN));
	}

	/**
	 * Stage numbers are identities, not positions. No-op pipeline stages own no row, so a pair
	 * can have stages 1 and 3; a writer keyed on the loop index would file stage 3 as stage 2.
	 */
	@Test
	public void nonContiguousStagesKeepTheirOwnNumbers() throws Exception {
		Pair pair = new Pair(1, 3);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(3, SATISFIABLE).complete(3);
		pair.run(0);

		assertRecorded(ingest(pair, 1, 3), Map.of(1, THEOREM, 3, SATISFIABLE));
	}

	/**
	 * A final stage with no post-processor leaves the previous stage's attributes.txt behind.
	 * Recording that file against the terminal stage is exactly what Kubernetes used to do.
	 */
	@Test
	public void aStageWithoutAPostProcessorGetsNoAttributes() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, null).complete(2);
		pair.run(0);

		assertRecorded(ingest(pair, 1, 2), Map.of(1, THEOREM));
	}

	/** A post-processor that prints nothing gives its stage no attributes, and is not an error. */
	@Test
	public void emptyPostProcessorOutputGivesItsStageNoAttributes() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, "").complete(2);
		pair.run(0);

		Ingested result = ingest(pair, 1, 2);
		assertTrue("an empty result is not a failure: " + result.failure, result.failure == null);
		assertRecorded(result, Map.of(1, THEOREM));
	}

	// ------------------------------------------------------------- legacy disabled (W6)

	/**
	 * With the protocol declared, attributes.txt is never read -- not even when it disagrees with
	 * every per-stage file. Bogus must not reach the database on any backend.
	 */
	@Test
	public void theLegacyFileIsNeverReadUnderTheProtocol() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);
		Files.writeString(pair.out.resolve("attributes.txt"), "starexec-result=Bogus\nBogus=1\n");

		Ingested result = ingest(pair, 1, 2);
		assertNoValue(result, "Bogus");
		assertRecorded(result, Map.of(1, THEOREM, 2, UNKNOWN));
	}

	/** Declared, but no stage published anything: nothing is recorded, not the legacy file. */
	@Test
	public void theMarkerWithNoFilesRecordsNothing() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);
		deleteTree(pair.out.resolve("stage-attributes"));
		Files.createDirectories(pair.out.resolve("stage-attributes"));

		assertRecorded(ingest(pair, 1, 2), Map.of());
	}

	// ------------------------------------------------------------- single stage (W7)

	/** Single-stage pairs record exactly what they always did, with the protocol ... */
	@Test
	public void aSingleStagePairIsUnchangedWithTheProtocol() throws Exception {
		Pair pair = new Pair(1);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.run(0);

		assertTrue("the helper declares the protocol",
				Files.isDirectory(pair.out.resolve("stage-attributes")));
		assertRecorded(ingest(pair, 1), Map.of(1, THEOREM));
	}

	/** ... and from a helper that predates it, whose only output is attributes.txt. */
	@Test
	public void aSingleStagePairIsUnchangedFromAnOlderHelper() throws Exception {
		Pair pair = new Pair(1);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.run(0);
		pair.asOlderHelper();

		assertRecorded(ingest(pair, 1), Map.of(1, THEOREM));
	}

	/**
	 * An older helper's multi-stage attributes.txt names no stage, and neither stage 1 nor the
	 * terminal stage is a safe guess, so it is not recorded at all.
	 */
	@Test
	public void aMultiStagePairFromAnOlderHelperRecordsNothing() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);
		pair.asOlderHelper();

		assertRecorded(ingest(pair, 1, 2), Map.of());
	}

	// ------------------------------------------------------------------ gate (W5)

	/** A stage whose snapshot is missing has not been shown to finish, so it is not consumed. */
	@Test
	public void aStageWithoutASnapshotIsNotConsumed() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);
		Files.delete(pair.out.resolve("stage-status").resolve("1.json"));

		assertRecorded(ingest(pair, 1, 2), Map.of(2, UNKNOWN));
	}

	/** Local polls mid-stage-1: stage 1's snapshot still says RUNNING, so nothing is consumed. */
	@Test
	public void aLocalPollDuringStageOneRecordsNothing() throws Exception {
		Assume.assumeTrue(backend == Backend.LOCAL);
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM);
		pair.run(0);

		Ingested poll = ingestLocal(pair, false, 1, 2);
		assertIngested(poll);
		assertFalse("the pair is still running", poll.terminal);
		assertRecorded(poll, Map.of());
	}

	/**
	 * Local polls mid-stage-2, with stage 1 finished and attributes.txt holding stage 1's set:
	 * status.json still names stage 1 as running, so nothing is consumed yet -- and once the pair
	 * completes, both stages are recorded from their own files.
	 */
	@Test
	public void aLocalPollDuringStageTwoRecordsNothingUntilThePairCompletes() throws Exception {
		Assume.assumeTrue(backend == Backend.LOCAL);
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN);
		pair.run(0);

		Local local = new Local(pair);
		try {
			Ingested midStage = local.poll(1, 2);
			assertIngested(midStage);
			assertFalse("the pair is still running", midStage.terminal);
			assertRecorded(midStage, Map.of());

			pair.append("sendStageStatus \"$STATUS_COMPLETE\" 2\n");
			pair.append("sendStatus \"$STATUS_COMPLETE\" 2\n");
			pair.run(0);
			Ingested done = local.poll(1, 2);
			assertIngested(done);
			assertTrue("the pair is complete", done.terminal);
			assertRecorded(done, Map.of(1, THEOREM, 2, UNKNOWN));
		} finally {
			local.close();
		}
	}

	// ------------------------------------------------------------------ edge cases

	/**
	 * A stage that errors after its post-processor ran keeps its attributes, as a single-stage
	 * pair's attributes.txt always was recorded whatever the pair's status. Here runsolver's
	 * watch file is missing: copyOutput runs, then markRunscriptError (jobscript:537-540).
	 */
	@Test
	public void aStageThatErrorsAfterPostProcessingKeepsItsAttributes() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN);
		pair.append("markRunscriptError 2\n");
		pair.run(0);

		assertRecorded(ingest(pair, 1, 2), Map.of(1, THEOREM, 2, UNKNOWN));
	}

	/**
	 * The exitJobscript catch-all reports on the pair-level channel, stage 0, and writes no
	 * snapshot. No stage is named, so nothing may be recorded for any of them (#145's shape).
	 */
	@Test
	public void aPairLevelFailureRecordsNothing() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN);
		pair.append("sendStatus \"$ERROR_BENCHMARK\"\n");
		pair.run(0);

		// Local and Podman refuse the stage-0 result outright; Kubernetes reports it handled.
		// Either way no attribute may be written.
		Ingested result = ingest(pair, 1, 2);
		assertTrue("a refusal, if any, is the invalid-stage one: " + result.failure,
				result.failure == null
						|| result.failure instanceof StageStatusSnapshots.InvalidSnapshotException);
		assertWritten(result, Map.of());
	}

	/**
	 * A post-processor that fails part-way leaves partial output in the sandbox, and none of it
	 * may be published: its stage gets nothing, and stage 1 keeps its own set.
	 */
	@Test
	public void aFailedPostProcessorPublishesNothingForItsStage() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.failingStage(2, "starexec-result=Partial\n");
		pair.run(1);

		assertFalse("the partial output must not be published",
				Files.exists(pair.out.resolve("stage-attributes").resolve("2.txt")));
		Ingested result = ingest(pair, 1, 2);
		assertNoValue(result, "Partial");
		assertRecorded(result, Map.of(1, THEOREM));
	}

	/**
	 * A rerun that dies in stage 1 must not pick up the previous attempt's stage-2 file. Local
	 * and Kubernetes clear the directory before the attempt starts, as they clear stage-status;
	 * the gate alone already keeps stage 2 out, because the new attempt never reached it.
	 */
	@Test
	public void aRerunThatDiesInStageOneIgnoresThePreviousAttempt() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);
		assertTrue(Files.exists(pair.out.resolve("stage-attributes").resolve("2.txt")));

		clearForRerun(pair.out);

		Pair rerun = pair.rerun();
		rerun.start();
		rerun.stage(1, SATISFIABLE);
		rerun.append("markRunscriptError 1\n");
		rerun.run(0);

		assertRecorded(ingest(rerun, 1, 2), Map.of(1, SATISFIABLE));
	}

	/** Rollback: an older monitor reads attributes.txt, which the new helper still publishes. */
	@Test
	public void theLegacyFileIsStillPublishedForAnOlderMonitor() throws Exception {
		Assume.assumeTrue(backend == Backend.LOCAL); // a property of the helper alone
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);

		assertEquals(UNKNOWN, Files.readString(pair.out.resolve("attributes.txt")));
	}

	// --------------------------------------------------------------------- assertions

	private static void assertRecorded(Ingested result, Map<Integer, String> expected) {
		assertIngested(result);
		assertWritten(result, expected);
	}

	private static void assertWritten(Ingested result, Map<Integer, String> expected) {
		Map<Integer, Map<String, String>> want = new TreeMap<>();
		for (Map.Entry<Integer, String> e : expected.entrySet()) {
			want.put(e.getKey(), keyValues(e.getValue()));
		}
		Map<Integer, Map<String, String>> got = new TreeMap<>();
		for (Write w : result.writes) {
			assertFalse("stage " + w.stage + " was written more than once: " + result.writes,
					got.containsKey(w.stage));
			assertFalse("stage " + w.stage + " was written with no attributes: " + result.writes,
					w.attributes.isEmpty());
			got.put(w.stage, w.attributes);
		}
		assertEquals("attributes by stage (ingestion outcome: "
				+ (result.failure == null ? "ok" : result.failure) + ")", want, got);
	}

	/** "Nothing was recorded" means something only if the ingestion itself went through. */
	private static void assertIngested(Ingested result) {
		if (result.failure != null) {
			throw new AssertionError("the ingestion failed rather than completing", result.failure);
		}
	}

	private static void assertNoValue(Ingested result, String value) {
		for (Write w : result.writes) {
			assertFalse("'" + value + "' reached the database: " + w,
					w.attributes.containsValue(value) || w.attributes.containsKey(value));
		}
	}

	private static Map<String, String> keyValues(String lines) {
		Map<String, String> map = new TreeMap<>();
		for (String line : lines.split("\n")) {
			if (!line.isEmpty()) {
				int eq = line.indexOf('=');
				map.put(line.substring(0, eq), line.substring(eq + 1));
			}
		}
		return map;
	}

	// ----------------------------------------------------------------------- database

	private static final class Write {
		final int stage;
		final Map<String, String> attributes;

		Write(int stage, Map<String, String> attributes) {
			this.stage = stage;
			this.attributes = attributes;
		}

		@Override
		public String toString() {
			return stage + "=" + attributes;
		}
	}

	private static final class Ingested {
		final List<Write> writes = new ArrayList<>();
		boolean terminal;
		Exception failure;
	}

	/** A database for one pair that has exactly {@code stages}, refusing what the real one does. */
	private static Ingested stubDatabase(MockedStatic<JobPairs> db, Set<Integer> stages)
			throws Exception {
		Ingested result = new Ingested();
		db.when(() -> JobPairs.getStageNumbers(PAIR)).thenReturn(new TreeSet<>(stages));
		db.when(() -> JobPairs.setEarlierStageStatuses(Mockito.anyInt(), Mockito.anyMap()))
				.thenAnswer(inv -> {
					Map<?, ?> batch = inv.getArgument(1);
					return stages.containsAll(batch.keySet())
							? StageStatusBatchResult.APPLIED
							: StageStatusBatchResult.REJECTED_UNKNOWN_STAGE;
				});
		db.when(() -> JobPairs.setPairStatusPreciseResult(Mockito.anyInt(), Mockito.anyInt(),
						Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
				.thenAnswer(inv -> stages.contains((Integer) inv.getArgument(1))
						? PairStatusResult.APPLIED
						: PairStatusResult.REJECTED_INVALID_STAGE);
		db.when(() -> JobPairs.addJobPairAttributes(Mockito.anyInt(), Mockito.anyInt(),
						Mockito.any(Properties.class)))
				.thenAnswer(inv -> {
					Properties p = inv.getArgument(2);
					Map<String, String> copy = new TreeMap<>();
					for (String key : p.stringPropertyNames()) {
						copy.put(key, p.getProperty(key));
					}
					result.writes.add(new Write(inv.getArgument(1), copy));
					return true;
				});
		db.when(() -> JobPairs.updateRunSolverStats(Mockito.anyInt(), Mockito.anyString(),
						Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyDouble(),
						Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyLong(),
						Mockito.anyInt(), Mockito.anyLong()))
				.thenReturn(true);
		Constructor<JobPairs.PairStatusLookupResult> lookup =
				JobPairs.PairStatusLookupResult.class.getDeclaredConstructor(
						PairStatusLookupState.class, int.class);
		lookup.setAccessible(true);
		JobPairs.PairStatusLookupResult running =
				lookup.newInstance(PairStatusLookupState.FOUND, StatusCode.STATUS_RUNNING.getVal());
		db.when(() -> JobPairs.getPairStatusLookup(PAIR)).thenReturn(running);
		return result;
	}

	// ---------------------------------------------------------------------- ingestion

	/** Ingests the finished pair once, through this test's backend. */
	private Ingested ingest(Pair pair, Integer... stages) throws Exception {
		switch (backend) {
			case LOCAL:
				return ingestLocal(pair, true, stages);
			case CONTAINER:
				return ingestContainer(pair, stages);
			default:
				return ingestKubernetes(pair, stages);
		}
	}

	private Ingested ingestLocal(Pair pair, boolean expectTerminal, Integer... stages)
			throws Exception {
		Local local = new Local(pair);
		try {
			Ingested result = local.poll(stages);
			if (expectTerminal && result.failure == null) {
				assertTrue("the finished pair must be ingested as terminal", result.terminal);
			}
			return result;
		} finally {
			local.close();
		}
	}

	/** One registered Local monitor, polled as many times as a test needs. */
	private static final class Local {
		final LocalJobMonitor monitor = new LocalJobMonitor();
		final Object state;
		final Method process;

		Local(Pair pair) throws Exception {
			// Not started, so registering schedules no poll: the polls are the test's own.
			monitor.registerJob(pair.out.toString(), PAIR);
			Field pairs = LocalJobMonitor.class.getDeclaredField("pairs");
			pairs.setAccessible(true);
			state = ((Map<?, ?>) pairs.get(monitor)).get(PAIR);
			process = LocalJobMonitor.class.getDeclaredMethod(
					"processCompletedJob", int.class, state.getClass());
			process.setAccessible(true);
		}

		Ingested poll(Integer... stages) throws Exception {
			try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
				Ingested result = stubDatabase(db, Set.of(stages));
				try {
					result.terminal = (boolean) process.invoke(monitor, PAIR, state);
				} catch (InvocationTargetException e) {
					result.failure = (Exception) e.getCause();
				}
				return result;
			}
		}

		void close() {
			monitor.stop();
		}
	}

	private Ingested ingestContainer(Pair pair, Integer... stages) throws Exception {
		ContainerJobMonitor monitor = new ContainerJobMonitor(null);
		PodmanBackend.CompletedContainerInfo info = new PodmanBackend.CompletedContainerInfo(
				"c-attribution", PAIR, pair.out.toString(), 0);
		Method process = ContainerJobMonitor.class.getDeclaredMethod(
				"processCompletedJob", PodmanBackend.CompletedContainerInfo.class);
		process.setAccessible(true);
		try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
			Ingested result = stubDatabase(db, Set.of(stages));
			try {
				process.invoke(monitor, info);
				result.terminal = true;
			} catch (InvocationTargetException e) {
				result.failure = (Exception) e.getCause();
			}
			return result;
		}
	}

	@SuppressWarnings("unchecked")
	private Ingested ingestKubernetes(Pair pair, Integer... stages) throws Exception {
		KubernetesNativeBackend k8s = new KubernetesNativeBackend();
		int execId = 17;
		String jobName = "job-attribution";
		for (String name : new String[]{"execIdToJobName", "execIdToPairId", "execIdToOutputDir"}) {
			Field f = KubernetesNativeBackend.class.getDeclaredField(name);
			f.setAccessible(true);
			Map<Integer, Object> map = (Map<Integer, Object>) f.get(k8s);
			map.put(execId, name.endsWith("JobName") ? jobName
					: name.endsWith("PairId") ? (Object) PAIR : pair.out);
		}
		Class<?> callbackClass = Class.forName(
				"org.starexec.backend.KubernetesNativeBackend$KubernetesJobCompletionCallback");
		Constructor<?> ctor = callbackClass.getDeclaredConstructor(KubernetesNativeBackend.class);
		ctor.setAccessible(true);
		Object callback = ctor.newInstance(k8s);
		Method onComplete = callbackClass.getDeclaredMethod("onJobComplete", ExecutionRef.class);
		onComplete.setAccessible(true);

		try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
			Ingested result = stubDatabase(db, Set.of(stages));
			try {
				result.terminal = (boolean) onComplete.invoke(
						callback, new ExecutionRef(execId, jobName, "uid-" + jobName));
			} catch (InvocationTargetException e) {
				result.failure = (Exception) e.getCause();
			}
			return result;
		}
	}

	/** What this backend does to a pair's output before a rerun, through its real method. */
	private void clearForRerun(Path out) throws Exception {
		switch (backend) {
			case LOCAL: {
				Method m = LocalBackend.class.getDeclaredMethod(
						"cleanupPreviousRunArtifacts", File.class);
				m.setAccessible(true);
				assertTrue("cleanup must succeed",
						(boolean) m.invoke(new LocalBackend(), out.toFile()));
				break;
			}
			case KUBERNETES: {
				Method m = KubernetesNativeBackend.class.getDeclaredMethod(
						"clearStaleAttemptArtifacts", Path.class, int.class);
				m.setAccessible(true);
				assertTrue("cleanup must succeed",
						(boolean) m.invoke(new KubernetesNativeBackend(), out, PAIR));
				break;
			}
			default:
				// PodmanBackend clears nothing before a rerun -- not status.json, not
				// stage-status -- so neither is this directory cleared. The gate is what keeps
				// the previous attempt's later stages out here.
				return;
		}
		assertFalse("the per-stage attributes must be cleared before the attempt",
				Files.exists(out.resolve("stage-attributes")));
	}

	// ------------------------------------------------------------------------ producer

	/** A pair run through the shipped helper, built up one jobscript step at a time. */
	private final class Pair {
		final Path dir;
		final Path out;
		final int[] stageNumbers;
		private final StringBuilder body = new StringBuilder();
		private int generation;

		Pair(int... stageNumbers) throws Exception {
			this(folder.newFolder("pair-" + System.nanoTime()).toPath(), stageNumbers);
		}

		private Pair(Path dir, int... stageNumbers) throws Exception {
			this.dir = dir;
			this.out = dir.resolve("out");
			this.stageNumbers = stageNumbers;
			Files.createDirectories(out);
			Files.copy(SGE.resolve("functions.bash"), dir.resolve("functions.bash"),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			Files.copy(SGE.resolve("status_codes.bash"), dir.resolve("status_codes.bash"),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}

		/** The next attempt, in the same output directory. */
		Pair rerun() throws Exception {
			Pair next = new Pair(dir, stageNumbers);
			next.generation = generation + 1;
			return next;
		}

		/** The helper's preamble and the pair start the jobscript performs (initSandbox). */
		void start() {
			StringBuilder b = body;
			b.append("export SCRIPT_DIR='").append(dir).append("'\n");
			b.append("export STAREXEC_OUTPUT_DIR='").append(out).append("'\n");
			b.append("export CONTAINER_MODE=true\n");
			b.append("export PAIR_ID=").append(PAIR).append('\n');
			b.append("export SHARED_DIR='").append(dir).append("/shared'\n");
			b.append("export WORKING_DIR_BASE='").append(dir).append("/work'\n");
			b.append("export BENCH_PATH=\"$(printf '/bench/primary.p' | base64 -w0)\"\n");
			b.append("export PAIR_OUTPUT_DIRECTORY=\"$(printf '/out/pair' | base64 -w0)\"\n");
			b.append("SOLVER_PATHS=()\n");
			b.append(". \"$SCRIPT_DIR/functions.bash\"\n");
			// jobscript:26-27 export these before sourcing; set here after, since only read.
			b.append("RUNSOLVER=RUNSOLVER\nBENCHEXEC=BENCHEXEC\n");
			b.append("STAREXEC_WALLCLOCK_LIMIT=100\nSTAREXEC_CPU_LIMIT=100\nDISK_QUOTA_EXCEEDED=0\n");
			b.append("REPORT_HOST=localhost\n");
			b.append("NUM_STAGES=").append(stageNumbers.length).append('\n');
			b.append("STAGE_NUMBERS=(");
			for (int n : stageNumbers) {
				b.append(n).append(' ');
			}
			b.append(")\n");
			// The one workspace the real job script reuses for every stage.
			b.append("WORKING_DIR='").append(dir).append("/work/attempt").append(generation)
					.append("'\n");
			b.append("OUT_DIR=\"$WORKING_DIR/output\"\n");
			b.append("LOCAL_SOLVER_DIR=\"$WORKING_DIR/solver\"\n");
			b.append("LOCAL_BENCH_PATH=\"$WORKING_DIR/benchmark/theBenchmark.p\"\n");
			b.append("VARFILE=\"$OUT_DIR/var.out\"\nWATCHFILE=\"$OUT_DIR/watcher.out\"\n");
			b.append("STDOUT_FILE=\"$OUT_DIR/stdout.txt\"\n");
			b.append("mkdir -p \"$OUT_DIR\" \"$LOCAL_SOLVER_DIR\"\n");
			// Saving solver output into the pair's output tree is not under test.
			b.append("function copyOutputNoStats { :; }\n");
			b.append("STAGE_INDEX=0\n");
			// initSandbox's call: where the helper declares the per-stage protocol.
			b.append("sendNode \"$HOSTNAME\" 1\n");
		}

		/** A stage whose solver ran, then copyOutput with this post-processor output. */
		Pair stage(int number, String postProcessorOutput) throws Exception {
			beginStage(number);
			body.append("POST_PROCESSOR_PATH='")
					.append(postProcessorOutput == null ? "" : postProcessor(number, postProcessorOutput, 0))
					.append("'\n");
			// jobscript:557.
			body.append("copyOutput ").append(number).append(" 1 1 \"$RUNSOLVER\"\n");
			body.append("cd \"$WORKING_DIR\"\n");
			return this;
		}

		/** A stage whose post-processor prints {@code partial} and then fails. */
		void failingStage(int number, String partial) throws Exception {
			beginStage(number);
			body.append("POST_PROCESSOR_PATH='").append(postProcessor(number, partial, 3)).append("'\n");
			body.append("copyOutput ").append(number).append(" 1 1 \"$RUNSOLVER\"\n");
		}

		/** The success branch at jobscript:620-626, then the cleanForNextStage at :647. */
		Pair complete(int number) {
			body.append("sendStageStatus \"$STATUS_COMPLETE\" ").append(number).append('\n');
			if (number == stageNumbers[stageNumbers.length - 1]) {
				body.append("sendStatus \"$STATUS_COMPLETE\" ").append(number).append('\n');
			} else {
				body.append("cleanForNextStage\n");
			}
			return this;
		}

		void append(String line) {
			body.append(line);
		}

		/** Leaves only what a helper predating the protocol would have written. */
		void asOlderHelper() throws Exception {
			deleteTree(out.resolve("stage-attributes"));
		}

		private void beginStage(int number) throws Exception {
			int index = Arrays.stream(stageNumbers).boxed().toList().indexOf(number);
			body.append("STAGE_INDEX=").append(index).append('\n');
			body.append("CURRENT_STAGE_NUMBER=").append(number).append('\n');
			body.append("mkdir -p \"$OUT_DIR/output_files\" \"$(dirname \"$LOCAL_BENCH_PATH\")\"\n");
			body.append("printf 'fof(a, conjecture, $true).\\n' > \"$LOCAL_BENCH_PATH\"\n");
			body.append("printf '# SZS status Done\\n' > \"$STDOUT_FILE\"\n");
			body.append("printf 'WCTIME=1.5\\nCPUTIME=1.25\\nUSERTIME=1\\nSYSTEMTIME=0\\nMAXVM=1\\n"
					+ "TIMEOUT=false\\nMEMOUT=false\\n' > \"$VARFILE\"\n");
			body.append("printf 'Child status: 0\\n' > \"$WATCHFILE\"\n");
			body.append("POST_PROCESSOR_TIME_LIMIT=1\n");
		}

		/** A post-processor directory whose process script prints {@code output}. */
		private String postProcessor(int stage, String output, int exit) throws Exception {
			Path pp = dir.resolve("pp-" + generation + "-" + stage);
			Files.createDirectories(pp);
			Files.writeString(pp.resolve("result.txt"), output);
			Files.writeString(pp.resolve("process"),
					"#!/bin/bash\ncat result.txt\nexit " + exit + "\n");
			return pp.toString();
		}

		/**
		 * Runs everything appended so far, from the top: each call replays the whole script,
		 * which is deterministic, so a later call simply continues the same pair further.
		 */
		void run(int expectedExit) throws Exception {
			File script = dir.resolve("generated-" + generation + ".sh").toFile();
			Files.writeString(script.toPath(), body.toString());
			Result parse = exec(Bash.PATH, "-n", script.getAbsolutePath());
			assertEquals("generated script must parse:\n" + parse.out, 0, parse.exit);
			resetOutput();
			Result result = exec(Bash.PATH, script.getAbsolutePath());
			assertEquals("the helper exit status:\n" + result.out, expectedExit, result.exit);
		}

		/** A replay starts from the output this attempt found, not from its own earlier run. */
		private void resetOutput() throws Exception {
			if (generation == 0) {
				deleteTree(out);
				Files.createDirectories(out);
			}
			deleteTree(dir.resolve("work").resolve("attempt" + generation));
		}
	}

	private static void deleteTree(Path root) throws Exception {
		if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (var walk = Files.walk(root)) {
			walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}

	private static Result exec(String... command) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(command);
		// updateStats formats runsolver's decimals with printf "%.0f", which rejects "1.5" under
		// a comma-decimal LC_NUMERIC -- reported separately, and not this test's subject. Job
		// containers run in the C locale.
		pb.environment().put("LC_ALL", "C");
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue("command must not hang", p.waitFor(60, TimeUnit.SECONDS));
		return new Result(p.exitValue(), output);
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
