package org.starexec.test.junit.backend;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.backend.ContainerJobMonitor;
import org.starexec.backend.KubernetesNativeBackend;
import org.starexec.backend.LocalJobMonitor;
import org.starexec.backend.PodmanBackend;
import org.starexec.backend.StageStatusSnapshots;
import org.starexec.backend.ExecutionRef;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.JobPairs.PairStatusLookupState;
import org.starexec.data.to.Status.StatusCode;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A final {@code status.json} must say which stage produced the result, and the monitors must
 * refuse it rather than invent one when it does not.
 *
 * <h2>Why this matters more than a parse detail</h2>
 *
 * The stage number read here is handed to {@code UpdatePairStatusPrecise} as an authoritative
 * identity: the terminal status goes to that stage and {@code STATUS_NOT_REACHED} to every
 * stage above it. A substituted 1 therefore attributes the result to a stage that may not have
 * run and marks the rest of the pair not reached -- confidently, with no error anywhere.
 *
 * <h2>What the three readers used to do, measured</h2>
 *
 * Against gson 2.10.1, the three expressions disagreed with each other on identical bytes.
 * {@code null} threw in Local, was swallowed into 1 by Container, and became 1 in Kubernetes;
 * {@code "99"} was coerced to 99; {@code 1.5} truncated to 1; {@code [1]} unwrapped to 1; and
 * {@code 9999999999999} wrapped to {@code 1316134911} -- a positive stage no pair has. Seven
 * distinct shapes reached stage 1.
 *
 * <p>{@link #everyShapeThatUsedToBecomeStageOne} pins that whole table, so the tests below
 * assert against a coercion set that has been enumerated rather than assumed.
 */
public class FinalStatusStageTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final int PAIR = 4242;
	private static final int COMPLETE = StatusCode.STATUS_COMPLETE.getVal();

	private static JsonObject json(String body) {
		return JsonParser.parseString(body).getAsJsonObject();
	}

	/** The real parser, reached the way every reader reaches it. */
	private static int parse(String body) throws Exception {
		Class<?> c = Class.forName("org.starexec.backend.FinalStatusStage");
		Method m = c.getDeclaredMethod("require", JsonObject.class, String.class);
		m.setAccessible(true);
		try {
			return (int) m.invoke(null, json(body), "pair " + PAIR);
		} catch (java.lang.reflect.InvocationTargetException e) {
			throw (Exception) e.getCause();
		}
	}

	private static void assertRejected(String body, String why) throws Exception {
		try {
			int got = parse(body);
			fail(why + " -- but it parsed as stage " + got + ": " + body);
		} catch (StageStatusSnapshots.InvalidSnapshotException expected) {
			assertTrue("the refusal must name the field it refused: " + expected.getMessage(),
					expected.getMessage().contains("stageNumber"));
		}
	}

	/** {@code IngestionOutcome} is package-private; its classification is the assertion. */
	private static String classify(Throwable failure) throws Exception {
		Class<?> c = Class.forName("org.starexec.backend.IngestionOutcome");
		Method m = c.getDeclaredMethod("classify", Throwable.class);
		m.setAccessible(true);
		return String.valueOf(m.invoke(null, failure));
	}

	// ------------------------------------------------------------- A. the shared parser

	/**
	 * The whole coercion table, in one place. Each of these produced stage 1 in at least one
	 * reader before this change; none may now.
	 */
	@Test
	public void everyShapeThatUsedToBecomeStageOne() throws Exception {
		assertRejected("{\"status\":7}", "an absent stageNumber names no stage");
		assertRejected("{\"status\":7,\"stageNumber\":null}", "a null stageNumber names no stage");
		assertRejected("{\"status\":7,\"stageNumber\":\"abc\"}", "a non-numeric string is not a stage");
		assertRejected("{\"status\":7,\"stageNumber\":true}", "a boolean is not a stage");
		assertRejected("{\"status\":7,\"stageNumber\":1.5}", "truncating a fraction invents a stage");
		assertRejected("{\"status\":7,\"stageNumber\":[1]}", "a single-element array is not a stage");
		assertRejected("{\"status\":7,\"stageNumber\":{\"a\":1}}", "an object is not a stage");
	}

	/** Quoted numbers are the coercion most likely to look harmless. They are still refused. */
	@Test
	public void aQuotedNumberIsNotAStageIdentity() throws Exception {
		assertRejected("{\"status\":7,\"stageNumber\":\"1\"}", "a quoted 1 is a string, not a number");
		assertRejected("{\"status\":7,\"stageNumber\":\"99\"}", "a quoted 99 is a string, not a number");
	}

	/**
	 * Overflow is the shape that mattered most: it did not fail, it silently produced a
	 * different, positive stage number. {@code 9999999999999} wrapped to {@code 1316134911},
	 * which no pair has -- a valid-looking identity for a stage that does not exist.
	 */
	@Test
	public void aValueOutsideTheIntegerRangeIsRefusedRatherThanWrapped() throws Exception {
		assertRejected("{\"status\":7,\"stageNumber\":2147483648}", "one past INT_MAX must not wrap to a negative");
		assertRejected("{\"status\":7,\"stageNumber\":9999999999999}", "a far overflow must not wrap to another stage");
		assertRejected("{\"status\":7,\"stageNumber\":-2147483649}", "one past INT_MIN must not wrap");
	}

	/** Range invalidity is refused here too, so it never reaches the database at all. */
	@Test
	public void zeroAndNegativesAreRefused() throws Exception {
		assertRejected("{\"status\":7,\"stageNumber\":0}", "stage numbers start at 1");
		assertRejected("{\"status\":7,\"stageNumber\":-1}", "a negative names no stage");
	}

	/** The controls. Without these a parser that refused everything would pass the above. */
	@Test
	public void realStageIdentitiesAreAccepted() throws Exception {
		assertEquals(1, parse("{\"status\":7,\"stageNumber\":1}"));
		assertEquals(2, parse("{\"status\":7,\"stageNumber\":2}"));
		assertEquals(99, parse("{\"status\":7,\"stageNumber\":99}"));
		assertEquals(Integer.MAX_VALUE,
				parse("{\"status\":7,\"stageNumber\":2147483647}"));
		assertEquals("a value written with a decimal point but no fractional part is still an"
						+ " integer, and refusing it would reject a legitimate producer",
				3, parse("{\"status\":7,\"stageNumber\":3.0}"));
	}

	// ------------------------------------------------------- B. LocalJobMonitor lifecycle

	private Path statusDir(String body) throws Exception {
		Path dir = folder.newFolder().toPath();
		Files.writeString(dir.resolve("status.json"), body);
		return dir;
	}

	private Object readStatusFile(Path dir) throws Throwable {
		LocalJobMonitor monitor = new LocalJobMonitor();
		try {
			Method m = LocalJobMonitor.class.getDeclaredMethod(
					"readStatusFile", Path.class, int.class);
			m.setAccessible(true);
			try {
				return m.invoke(monitor, dir, PAIR);
			} catch (java.lang.reflect.InvocationTargetException e) {
				throw e.getCause();
			}
		} finally {
			monitor.stop();
		}
	}

	/**
	 * Local's contract for a deterministic content defect is
	 * {@code InvalidSnapshotException}, which {@code IngestionOutcome} classifies BLOCKED:
	 * the pair is held with its output and an operator is told, rather than retried against
	 * bytes that will not change or given a status it did not earn.
	 */
	@Test
	public void localRefusesATerminalStatusWithNoStageAndIsBlocked() throws Throwable {
		try {
			readStatusFile(statusDir("{\"pairId\":4242,\"status\":7}"));
			fail("a terminal status with no stage must not be accepted");
		} catch (StageStatusSnapshots.InvalidSnapshotException expected) {
			assertEquals("must be classified BLOCKED, not retried",
					"BLOCKED", classify(expected));
		}
	}

	/** The refusal must not be mistaken for the "caught mid-write, try again" signal. */
	@Test
	public void localDoesNotTreatARefusalAsNotReadableYet() throws Throwable {
		try {
			Object result = readStatusFile(statusDir("{\"pairId\":4242,\"status\":7}"));
			fail("returning " + result + " would send this back round the retry loop forever");
		} catch (StageStatusSnapshots.InvalidSnapshotException expected) {
			// as intended
		}
	}

	/** The positive control: a well-formed file still ingests. */
	@Test
	public void localStillAcceptsAWellFormedStatus() throws Throwable {
		Object ss = readStatusFile(statusDir("{\"pairId\":4242,\"status\":7,\"stageNumber\":2}"));
		Field stage = ss.getClass().getDeclaredField("stageNumber");
		stage.setAccessible(true);
		assertEquals(2, stage.getInt(ss));
	}

	// --------------------------------------------------- C. ContainerJobMonitor lifecycle

	/**
	 * Container's fallback for an unrecognised failure records {@code ERROR_RUNSCRIPT} against
	 * a hardcoded stage 1 and then deletes the container. A status file with no stage must not
	 * reach it -- that would invent the stage and the solver outcome together, and destroy the
	 * evidence. The refusal is thrown as the type the poll loop holds on.
	 */
	@Test
	public void containerRefusesATerminalStatusWithNoStage() throws Throwable {
		Path dir = statusDir("{\"pairId\":4242,\"status\":7}");
		ContainerJobMonitor monitor = new ContainerJobMonitor(null);
		PodmanBackend.CompletedContainerInfo info =
				new PodmanBackend.CompletedContainerInfo("c1", PAIR, dir.toString(), 0);

		Method m = ContainerJobMonitor.class.getDeclaredMethod(
				"processCompletedJob", PodmanBackend.CompletedContainerInfo.class);
		m.setAccessible(true);
		try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
			try {
				m.invoke(monitor, info);
				fail("a status with no stage must not be ingested");
			} catch (java.lang.reflect.InvocationTargetException e) {
				if (!(e.getCause() instanceof StageStatusSnapshots.InvalidSnapshotException)) {
					throw e.getCause();
				}
			}
			jobPairsMock.verify(
					() -> JobPairs.setPairStatusPreciseResult(
							Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
							Mockito.anyInt(), Mockito.anyBoolean()),
					Mockito.never());
			jobPairsMock.verify(
					() -> JobPairs.setPairStatusPrecise(
							Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
							Mockito.anyInt()),
					Mockito.never());
		}
	}

	/**
	 * The absent-file case, which must keep working: a container that produced no status.json
	 * has nothing to misattribute, and refusing it would block every such pair. This is the
	 * case an earlier revision of this change got wrong -- it left the stage at 0, which is
	 * not a refusal but the very identity {@code UpdatePairStatusPrecise} spreads NOT_REACHED
	 * across every stage of the pair.
	 */
	@Test
	public void containerStillIngestsWhenThereIsNoStatusFileAtAll() throws Throwable {
		Path dir = folder.newFolder().toPath();
		ContainerJobMonitor monitor = new ContainerJobMonitor(null);
		PodmanBackend.CompletedContainerInfo info =
				new PodmanBackend.CompletedContainerInfo("c2", PAIR, dir.toString(), 0);

		Method m = ContainerJobMonitor.class.getDeclaredMethod(
				"processCompletedJob", PodmanBackend.CompletedContainerInfo.class);
		m.setAccessible(true);
		try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
			try {
				m.invoke(monitor, info);
			} catch (java.lang.reflect.InvocationTargetException e) {
				if (e.getCause() instanceof StageStatusSnapshots.InvalidSnapshotException) {
					fail("no status.json is not a malformed status.json: " + e.getCause());
				}
				// any other failure is the absent database further down, which is fine here
			}
			jobPairsMock.verify(
					() -> JobPairs.setPairStatusPreciseResult(
							Mockito.anyInt(), Mockito.intThat(s -> s < 1), Mockito.anyInt(),
							Mockito.anyInt(), Mockito.anyBoolean()),
					Mockito.never());
			jobPairsMock.verify(
					() -> JobPairs.setPairStatusPrecise(
							Mockito.anyInt(), Mockito.intThat(s -> s < 1), Mockito.anyInt(),
							Mockito.anyInt()),
					Mockito.never());
		}
	}

	/** A file that exists and is not a status record is refused, not defaulted. */
	@Test
	public void containerRefusesAStatusFileItCannotParse() throws Throwable {
		Path dir = statusDir("{ this is not json");
		ContainerJobMonitor monitor = new ContainerJobMonitor(null);
		PodmanBackend.CompletedContainerInfo info =
				new PodmanBackend.CompletedContainerInfo("c3", PAIR, dir.toString(), 0);

		Method m = ContainerJobMonitor.class.getDeclaredMethod(
				"processCompletedJob", PodmanBackend.CompletedContainerInfo.class);
		m.setAccessible(true);
		try (MockedStatic<JobPairs> ignored = Mockito.mockStatic(JobPairs.class)) {
			try {
				m.invoke(monitor, info);
				fail("an unparsable status.json must not be ingested");
			} catch (java.lang.reflect.InvocationTargetException e) {
				assertTrue("expected the invalid-snapshot refusal, got " + e.getCause(),
						e.getCause() instanceof StageStatusSnapshots.InvalidSnapshotException);
			}
		}
	}

	// ----------------------------------------------- D. KubernetesNativeBackend lifecycle

	@SuppressWarnings("unchecked")
	private Object registerExecution(KubernetesNativeBackend backend, int execId, String jobName,
			int pairId, Path outputDir) throws Exception {
		for (String name : new String[]{"execIdToJobName", "execIdToPairId", "execIdToOutputDir"}) {
			Field f = KubernetesNativeBackend.class.getDeclaredField(name);
			f.setAccessible(true);
			Map<Integer, Object> map = (Map<Integer, Object>) f.get(backend);
			map.put(execId, name.endsWith("JobName") ? jobName
					: name.endsWith("PairId") ? (Object) pairId : outputDir);
		}
		Class<?> callbackClass = Class.forName(
				"org.starexec.backend.KubernetesNativeBackend$KubernetesJobCompletionCallback");
		Constructor<?> c = callbackClass.getDeclaredConstructor(KubernetesNativeBackend.class);
		c.setAccessible(true);
		return c.newInstance(backend);
	}

	private static JobPairs.PairStatusLookupResult foundLookup(int statusCode) throws Exception {
		Constructor<JobPairs.PairStatusLookupResult> c =
				JobPairs.PairStatusLookupResult.class.getDeclaredConstructor(
						PairStatusLookupState.class, int.class);
		c.setAccessible(true);
		return c.newInstance(PairStatusLookupState.FOUND, statusCode);
	}

	/**
	 * Kubernetes owns its execution lifecycle, so its safe refusal looks different from the
	 * other two: nothing is written, the execution is reported handled so the same job is not
	 * reprocessed forever, and accounting is released exactly as every other exit from the
	 * callback releases it.
	 */
	@Test
	public void kubernetesRefusesATerminalStatusWithNoStageWithoutWritingOrLooping()
			throws Exception {
		Path dir = statusDir("{\"pairId\":4242,\"status\":7}");
		KubernetesNativeBackend backend = new KubernetesNativeBackend();
		Object callback = registerExecution(backend, 7, "job-7", PAIR, dir);
		ExecutionRef execution = new ExecutionRef(7, "job-7", "uid-job-7");

		try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
			jobPairsMock.when(() -> JobPairs.getPairStatusLookup(PAIR))
					.thenReturn(foundLookup(StatusCode.STATUS_RUNNING.getVal()));

			Method onComplete = callback.getClass().getDeclaredMethod(
					"onJobComplete", ExecutionRef.class);
			onComplete.setAccessible(true);
			boolean handled = (boolean) onComplete.invoke(callback, execution);

			assertTrue("returning false would reprocess the same job on every poll, forever",
					handled);
			jobPairsMock.verify(
					() -> JobPairs.setPairStatusPreciseResult(
							Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
							Mockito.anyInt(), Mockito.anyBoolean()),
					Mockito.never());
			jobPairsMock.verify(
					() -> JobPairs.setPairStatusPrecise(
							Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
							Mockito.anyInt()),
					Mockito.never());
			jobPairsMock.verify(() -> JobPairs.setEndTime(Mockito.anyInt()), Mockito.never());
		}
	}

	/**
	 * A pod that never started writes no status.json at all. That is a different situation --
	 * nothing was produced, so there is nothing to misattribute -- and it must keep working,
	 * or every stuck-pending pair is stranded instead of being made rerunnable.
	 */
	@Test
	public void kubernetesStillUsesItsDefaultWhenNoStatusFileExistsAtAll() throws Exception {
		Path empty = folder.newFolder().toPath();
		KubernetesNativeBackend backend = new KubernetesNativeBackend();
		Object callback = registerExecution(backend, 8, "job-8", PAIR, empty);
		ExecutionRef execution = new ExecutionRef(8, "job-8", "uid-job-8");

		Method read = callback.getClass().getDeclaredMethod(
				"readStageNumber", ExecutionRef.class, int.class);
		read.setAccessible(true);
		assertEquals("an absent file keeps the caller's default", 1,
				(int) (Integer) read.invoke(callback, execution, 1));
	}

	/** And the positive control: a file that does name a stage is used as given. */
	@Test
	public void kubernetesUsesTheStageTheFileNames() throws Exception {
		Path dir = statusDir("{\"pairId\":4242,\"status\":7,\"stageNumber\":3}");
		KubernetesNativeBackend backend = new KubernetesNativeBackend();
		Object callback = registerExecution(backend, 9, "job-9", PAIR, dir);
		ExecutionRef execution = new ExecutionRef(9, "job-9", "uid-job-9");

		Method read = callback.getClass().getDeclaredMethod(
				"readStageNumber", ExecutionRef.class, int.class);
		read.setAccessible(true);
		assertEquals(3, (int) (Integer) read.invoke(callback, execution, 1));
	}

	/** A file that exists but cannot be parsed at all is also not a stage identity. */
	@Test
	public void kubernetesRefusesAStatusFileItCannotParse() throws Exception {
		Path dir = statusDir("{ this is not json");
		KubernetesNativeBackend backend = new KubernetesNativeBackend();
		Object callback = registerExecution(backend, 10, "job-10", PAIR, dir);
		ExecutionRef execution = new ExecutionRef(10, "job-10", "uid-job-10");

		Method read = callback.getClass().getDeclaredMethod(
				"readStageNumber", ExecutionRef.class, int.class);
		read.setAccessible(true);
		try {
			read.invoke(callback, execution, 1);
			fail("an unparsable file must not silently become stage 1");
		} catch (java.lang.reflect.InvocationTargetException e) {
			assertTrue("expected the invalid-snapshot refusal, got " + e.getCause(),
					e.getCause() instanceof StageStatusSnapshots.InvalidSnapshotException);
		}
	}

	// --------------------------------------------------------------------------- shared

	/**
	 * The producer writes the field on every path, so the strict rule never rejects it for
	 * being absent.
	 *
	 * <p>It does <em>not</em> follow that every value it writes is accepted:
	 * {@code containerWriteStatus} defaults its stage argument to {@code 0}
	 * ({@code local STAGE_NUMBER=${2:-0}}), which is the job script's deliberate pair-level
	 * channel -- a status about the pair rather than about any stage. Those records are
	 * refused by this parser, which is correct as far as a <em>precise stage</em> write goes
	 * (0 identifies no stage) but means the pair is held rather than recorded through
	 * {@code UpdatePairStatus}, the stageless routine that exists for exactly them.
	 *
	 * <p>Asserted rather than left implicit so the trade-off is visible in the suite instead
	 * of being discovered in production. See the follow-up issue referenced in the PR.
	 */
	@Test
	public void theShippedProducerAlwaysWritesTheFieldButDefaultsItToZero() throws Exception {
		Path functions = Path.of("src/main/java/org/starexec/config/sge/functions.bash");
		String body = Files.readString(functions);
		int start = body.indexOf("function containerWriteStatus");
		assertTrue("containerWriteStatus must exist to be checked", start >= 0);
		String fn = body.substring(start, body.indexOf("\n}", start));

		assertTrue("containerWriteStatus must always write stageNumber, or the strict parse"
						+ " would reject its own producer for omitting it: " + fn,
				fn.contains("\\\"stageNumber\\\":"));

		// The real default, quoted exactly. The previous version of this test looked for a
		// literal that has never existed in this file and so asserted nothing.
		assertTrue("containerWriteStatus is expected to default its stage argument to 0 --"
						+ " if that changes, the pair-level trade-off below changes with it: "
						+ fn,
				fn.contains("local STAGE_NUMBER=${2:-0}"));
		assertRejected("{\"status\":7,\"stageNumber\":0}",
				"and 0, the pair-level channel, identifies no stage");
	}
}
