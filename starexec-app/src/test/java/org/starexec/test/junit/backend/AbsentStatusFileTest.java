package org.starexec.test.junit.backend;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.backend.ContainerJobMonitor;
import org.starexec.backend.ExecutionRef;
import org.starexec.backend.KubernetesNativeBackend;
import org.starexec.backend.LocalJobMonitor;
import org.starexec.backend.PodmanBackend;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.JobPairs.PairStatusLookupState;
import org.starexec.data.database.PairStatusResult;
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

/**
 * A pair that produced nothing must not be recorded as a completed solver run.
 *
 * <h2>What was wrong</h2>
 *
 * {@code STATUS_COMPLETE} was a bare fallback. {@code ContainerJobMonitor.determineStatus}
 * reached it whenever the run was not a limit breach and had not exited non-zero without a
 * {@code var.out}; {@code KubernetesNativeBackend.readTerminalStatus} returned its caller's
 * default, and {@code onJobComplete} passes {@code STATUS_COMPLETE} as that default. So a
 * container that exited 0 having produced nothing at all was recorded as a successful solver
 * run, stamped with an {@code end_time} and a completion row, and became indistinguishable
 * from a genuine result in every downstream query, ranking and export.
 *
 * <h2>Why an absent status.json means nothing ran</h2>
 *
 * All three backends run the job script with {@code CONTAINER_MODE=true}, and in that mode
 * {@code status.json} is written before any stage runs: {@code jobscript:201} calls
 * {@code initSandbox}, whose every exit writes the file -- the three failure paths and the
 * success path {@code sendNode}, which calls {@code sendStatus STATUS_RUNNING}. There is no
 * {@code exit} in the job script before that point, and the EXIT trap writes a status on any
 * non-zero exit. So an absent {@code status.json} means the script never reached
 * {@code initSandbox}: nothing ran.
 *
 * <h2>Why ERROR_RUNSCRIPT and not a hold</h2>
 *
 * It is StarExec's bounded-retry channel rather than a claim about a run script.
 * {@code PeriodicTasks:342} selects exactly that code for {@code RERUN_FAILED_PAIRS},
 * {@code GetJobPairIdsWithStatusNotRerunAfterDate} excludes anything already in
 * {@code pairs_rerun} so the retry happens once, and
 * {@code JobPairs.tryMarkRunningAsFailed:3284} already records a pair with no results this
 * exact way. Nothing ran, so a retry cannot contaminate a measurement -- and the pair
 * recovers without an operator.
 */
public class AbsentStatusFileTest {

	@Rule public TemporaryFolder folder = new TemporaryFolder();
	private static final int PAIR = 4242;
	private static final int COMPLETE = StatusCode.STATUS_COMPLETE.getVal();
	private static final int RUNSCRIPT = StatusCode.ERROR_RUNSCRIPT.getVal();

	// ------------------------------------------------------------------ the invariant

	private int containerStatusFor(Path dir, int exitCode) throws Exception {
		ContainerJobMonitor monitor = new ContainerJobMonitor(null);
		PodmanBackend.CompletedContainerInfo info =
				new PodmanBackend.CompletedContainerInfo("c", PAIR, dir.toString(), exitCode);
		Method m = ContainerJobMonitor.class.getDeclaredMethod(
				"processCompletedJob", PodmanBackend.CompletedContainerInfo.class);
		m.setAccessible(true);
		try (MockedStatic<JobPairs> jp = Mockito.mockStatic(JobPairs.class)) {
			jp.when(() -> JobPairs.setPairStatusPreciseResult(
					Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
					Mockito.anyInt(), Mockito.anyBoolean()))
			  .thenReturn(PairStatusResult.APPLIED);
			try { m.invoke(monitor, info); } catch (Exception ignored) { }
			ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
			jp.verify(() -> JobPairs.setPairStatusPreciseResult(
					Mockito.eq(PAIR), Mockito.anyInt(), status.capture(),
					Mockito.anyInt(), Mockito.anyBoolean()));
			return status.getValue();
		}
	}

	/** The defect: exit 0, nothing produced, no status.json. */
	@Test
	public void containerDoesNotRecordAnEmptyRunAsComplete() throws Exception {
		int status = containerStatusFor(folder.newFolder().toPath(), 0);
		assertEquals("an exit-0 container that produced nothing is not a completed solver run",
				RUNSCRIPT, status);
	}

	@Test
	public void kubernetesDoesNotRecordAnEmptyRunAsComplete() throws Exception {
		Path empty = folder.newFolder().toPath();
		KubernetesNativeBackend b = new KubernetesNativeBackend();
		Object cb = callback(b, 7, "job-7", PAIR, empty);
		ExecutionRef ex = new ExecutionRef(7, "job-7", "uid-job-7");
		try (MockedStatic<JobPairs> jp = Mockito.mockStatic(JobPairs.class)) {
			stubLookup(jp);
			jp.when(() -> JobPairs.setPairStatusPreciseResult(
					Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
					Mockito.anyInt(), Mockito.anyBoolean()))
			  .thenReturn(PairStatusResult.APPLIED);
			Method m = cb.getClass().getDeclaredMethod("onJobComplete", ExecutionRef.class);
			m.setAccessible(true);
			m.invoke(cb, ex);
			ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
			jp.verify(() -> JobPairs.setPairStatusPreciseResult(
					Mockito.eq(PAIR), Mockito.anyInt(), status.capture(),
					Mockito.anyInt(), Mockito.anyBoolean()));
			assertEquals("an execution that produced nothing is not a completed solver run",
					RUNSCRIPT, status.getValue().intValue());
		}
	}

	// ------------------------------------------------------------- the positive controls

	/**
	 * The control that proves the gate keys on evidence rather than on status.json's absence.
	 * A real runsolver run leaves var.out, so it is still COMPLETE even with no status.json.
	 */
	@Test
	public void aRunsolverRunWithVarOutIsStillComplete() throws Exception {
		Path dir = folder.newFolder().toPath();
		Files.writeString(dir.resolve("var.out"), "WCTIME=1.0\nCPUTIME=1.0\n");
		assertEquals(COMPLETE, containerStatusFor(dir, 0));
	}

	/**
	 * The BenchExec control. That framework does not produce runsolver's var.out, so gating on
	 * var.out alone would have recorded every BenchExec run as a failure. {@code updateStats}
	 * writes stats.json after the framework branch, for both frameworks, so it is the signal
	 * that keeps BenchExec working.
	 */
	@Test
	public void aBenchexecRunWithStatsButNoVarOutIsStillComplete() throws Exception {
		Path dir = folder.newFolder().toPath();
		Files.writeString(dir.resolve("stats.json"), "{\"pairId\":4242,\"stageNumber\":1}");
		assertEquals(COMPLETE, containerStatusFor(dir, 0));
	}

	/** Unchanged: a non-zero exit with no evidence was already ERROR_RUNSCRIPT. */
	@Test
	public void aNonZeroExitWithNoEvidenceIsStillErrorRunscript() throws Exception {
		assertEquals(RUNSCRIPT, containerStatusFor(folder.newFolder().toPath(), 1));
	}

	/**
	 * The exit-code test must survive alongside the evidence gate, and this is the case that
	 * proves it: evidence IS present, so the gate passes, and only the exit-code term can
	 * refuse. An earlier revision of this change replaced that term instead of adding to it,
	 * and a container OOM-killed after {@code updateStats} had written stats.json -- or one
	 * whose {@code cp} of var.out failed under {@code set -e} -- was recorded as a completed
	 * solver run. The previous version of this test used an empty directory, so it passed
	 * through the evidence gate and never reached the condition it was named for.
	 */
	@Test
	public void aNonZeroExitWithStatsButNoVarOutIsStillErrorRunscript() throws Exception {
		Path dir = folder.newFolder().toPath();
		Files.writeString(dir.resolve("stats.json"), "{\"pairId\":4242,\"stageNumber\":1}");
		assertEquals("a container that exited non-zero is not complete just because"
						+ " updateStats had already run",
				RUNSCRIPT, containerStatusFor(dir, 137));
	}

	/**
	 * And the same for a killed run that got as far as watcher.out but not var.out. The
	 * content is deliberately neutral: runsolver's own limit verdict outranks both gates, so
	 * a watcher.out reporting a breach would be classified as that breach and would not
	 * exercise the exit-code term at all.
	 */
	@Test
	public void aNonZeroExitWithWatcherButNoVarOutIsStillErrorRunscript() throws Exception {
		Path dir = folder.newFolder().toPath();
		Files.writeString(dir.resolve("watcher.out"), "Solver just ended. Dumping statistics\n");
		assertEquals(RUNSCRIPT, containerStatusFor(dir, 1));
	}

	/**
	 * attributes.txt is deliberately not evidence. It is the one artifact
	 * {@code clearStaleAttemptArtifacts} does not remove, so a previous attempt's copy would
	 * survive into a rerun and make an empty attempt look complete. It also adds nothing:
	 * {@code copyOutput} runs {@code updateStats} before the post-processor branch, so a real
	 * attributes.txt always has a stats.json beside it.
	 */
	@Test
	public void aStaleAttributesFileIsNotEvidenceOfThisAttempt() throws Exception {
		Path dir = folder.newFolder().toPath();
		Files.writeString(dir.resolve("attributes.txt"), "starexec-result=sat\n");
		assertFalse("attributes.txt survives a rerun, so it must not stand in for a run",
				hasRunEvidence(dir));
		assertEquals(RUNSCRIPT, containerStatusFor(dir, 0));
	}

	/**
	 * A directory that cannot be read is not a directory that is empty. Turning a storage
	 * fault into a verdict about a solver is the mistake this gate exists to stop, so the
	 * IOException propagates instead of being read as "nothing ran".
	 */
	@Test
	public void anUnreadableDirectoryIsNotReportedAsNoEvidence() throws Exception {
		Path dir = folder.newFolder().toPath();
		java.io.File f = dir.toFile();
		org.junit.Assume.assumeTrue("needs a filesystem that honours chmod", f.setReadable(false, false));
		org.junit.Assume.assumeFalse("running as root defeats the permission bit", f.canRead());
		try {
			hasRunEvidence(dir);
			// Some filesystems still allow the stat; only assert when it genuinely failed.
		} catch (java.io.IOException expected) {
			assertFalse("an IOException must not be silently read as 'no evidence'",
					expected instanceof java.nio.file.NoSuchFileException);
		} finally {
			f.setReadable(true, false);
		}
	}

	// --------------------------------------------------------------- the evidence predicate

	/**
	 * The job script's own log is not evidence. {@code log()} appends to it on every call from
	 * the first line, long before {@code initSandbox}, so it shows only that the script
	 * started -- which is exactly the case this has to catch.
	 */
	@Test
	public void thePairLogIsNotEvidenceThatARunHappened() throws Exception {
		Path dir = folder.newFolder().toPath();
		Files.writeString(dir.resolve("4242.txt"), "09/10/26: starting\n");
		assertFalse(hasRunEvidence(dir));
		assertEquals("a log file alone must not make this a completed run",
				RUNSCRIPT, containerStatusFor(dir, 0));
	}

	@Test
	public void everyRunArtifactCountsAsEvidence() throws Exception {
		for (String artifact : new String[]{"var.out", "watcher.out", "stats.json"}) {
			Path dir = folder.newFolder().toPath();
			Files.writeString(dir.resolve(artifact), "x");
			assertTrue(artifact + " must count as evidence a run happened", hasRunEvidence(dir));
		}
		assertFalse("an empty directory is not evidence", hasRunEvidence(folder.newFolder().toPath()));
	}

	// ------------------------------------------------------------------------ Local

	/**
	 * Local already recorded ERROR_RUNSCRIPT for an absent file, so this change leaves it
	 * alone -- the three backends now agree. Pinned so that agreement is not lost silently.
	 */
	@Test
	public void localAlreadyRecordsErrorRunscriptAndIsUnchanged() throws Exception {
		LocalJobMonitor monitor = new LocalJobMonitor();
		try {
			Method m = LocalJobMonitor.class.getDeclaredMethod("readStatusFile", Path.class, int.class);
			m.setAccessible(true);
			Object ss = m.invoke(monitor, folder.newFolder().toPath(), PAIR);
			Field st = ss.getClass().getDeclaredField("status"); st.setAccessible(true);
			assertEquals(StatusCode.ERROR_RUNSCRIPT, st.get(ss));
		} finally { monitor.stop(); }
	}

	// ------------------------------------------------------------------------ helpers

	private static boolean hasRunEvidence(Path dir) throws Exception {
		Class<?> c = Class.forName("org.starexec.backend.FinalStatusStage");
		Method m = c.getDeclaredMethod("hasRunEvidence", Path.class);
		m.setAccessible(true);
		return (boolean) m.invoke(null, dir);
	}

	private static void stubLookup(MockedStatic<JobPairs> jp) throws Exception {
		Constructor<JobPairs.PairStatusLookupResult> lk =
				JobPairs.PairStatusLookupResult.class.getDeclaredConstructor(
						PairStatusLookupState.class, int.class);
		lk.setAccessible(true);
		jp.when(() -> JobPairs.getPairStatusLookup(PAIR))
		  .thenReturn(lk.newInstance(PairStatusLookupState.FOUND, StatusCode.STATUS_RUNNING.getVal()));
	}

	@SuppressWarnings("unchecked")
	private Object callback(KubernetesNativeBackend b, int id, String job, int pair, Path dir)
			throws Exception {
		for (String f : new String[]{"execIdToJobName", "execIdToPairId", "execIdToOutputDir"}) {
			Field fl = KubernetesNativeBackend.class.getDeclaredField(f);
			fl.setAccessible(true);
			Map<Integer, Object> map = (Map<Integer, Object>) fl.get(b);
			map.put(id, f.endsWith("JobName") ? job : f.endsWith("PairId") ? (Object) pair : dir);
		}
		Class<?> c = Class.forName(
				"org.starexec.backend.KubernetesNativeBackend$KubernetesJobCompletionCallback");
		Constructor<?> k = c.getDeclaredConstructor(KubernetesNativeBackend.class);
		k.setAccessible(true);
		return k.newInstance(b);
	}
}
