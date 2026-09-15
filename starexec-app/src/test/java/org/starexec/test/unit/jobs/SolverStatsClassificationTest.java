package org.starexec.test.unit.jobs;

import org.junit.Test;
import org.starexec.constants.R;
import org.starexec.data.database.Jobs;
import org.starexec.data.to.SolverStats;
import org.starexec.data.to.Status;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.data.to.pipelines.JoblineStage;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * How one stage is counted in a job's per-solver summary (#198).
 *
 * <p>The summary's unknown column is {@code complete - (resourceOut + correct + incorrect)}, so it
 * is only right if every resource-out is also counted as complete, which is what
 * {@link StatusCode}'s {@code StatCompleteness.COMPLETE} and {@code SolverStats}' own field comment
 * ("any finished status except Starexec errors") say. Making the classification mutually exclusive
 * dropped resource-outs from complete, so every timeout lowered the unknown count by one and the
 * solved column's denominator shrank.
 *
 * <p>The expected sets are restated here rather than derived from {@link StatusCode}'s predicates,
 * which are what is under test; a status code added without a decision fails
 * {@link #everyStatusCodeHasADecidedClassification()}.
 */
public class SolverStatsClassificationTest {

	private static final String THEOREM = "Theorem";

	/** Finished runs the solver owns: a result, or a limit it ran into. */
	private static final Set<StatusCode> COMPLETE = EnumSet.of(
			StatusCode.STATUS_COMPLETE,
			StatusCode.EXCEED_RUNTIME, StatusCode.EXCEED_CPU,
			StatusCode.EXCEED_FILE_WRITE, StatusCode.EXCEED_MEM);

	private static final Set<StatusCode> RESOURCE_OUT = EnumSet.of(
			StatusCode.EXCEED_RUNTIME, StatusCode.EXCEED_CPU,
			StatusCode.EXCEED_FILE_WRITE, StatusCode.EXCEED_MEM);

	/** Platform and processor failures: counted once, as failed, and nowhere else. */
	private static final Set<StatusCode> FAILED = EnumSet.of(
			StatusCode.ERROR_SGE_REJECT, StatusCode.ERROR_SUBMIT_FAIL, StatusCode.ERROR_RESULTS,
			StatusCode.ERROR_RUNSCRIPT, StatusCode.ERROR_BENCHMARK,
			StatusCode.ERROR_DISK_QUOTA_EXCEEDED, StatusCode.ERROR_GENERAL,
			StatusCode.ERROR_BENCH_DEPENDENCY_MISSING, StatusCode.ERROR_PRE_PROCESSOR,
			StatusCode.ERROR_POST_PROCESSOR);

	private static final Set<StatusCode> INCOMPLETE = EnumSet.of(
			StatusCode.STATUS_UNKNOWN, StatusCode.STATUS_PENDING_SUBMIT, StatusCode.STATUS_ENQUEUED,
			StatusCode.STATUS_RUNNING, StatusCode.STATUS_PROCESSING_RESULTS,
			StatusCode.STATUS_PAUSED, StatusCode.STATUS_KILLED, StatusCode.STATUS_PROCESSING,
			StatusCode.STATUS_NOT_REACHED);

	@Test
	public void everyStatusCodeHasADecidedClassification() {
		for (StatusCode code : StatusCode.values()) {
			int buckets = (FAILED.contains(code) ? 1 : 0) + (INCOMPLETE.contains(code) ? 1 : 0)
					+ (COMPLETE.contains(code) ? 1 : 0);
			assertEquals(code + " must be exactly one of failed, incomplete or complete",
					1, buckets);
		}
	}

	/** The example in #198: 2 solved, 1 unknown result, 2 wallclock timeouts. */
	@Test
	public void timeoutsDoNotReduceTheUnknownCount() throws Exception {
		SolverStats stats = new SolverStats();
		add(stats, solved());
		add(stats, solved());
		add(stats, unknownResult());
		add(stats, stage(StatusCode.EXCEED_RUNTIME));
		add(stats, stage(StatusCode.EXCEED_RUNTIME));

		assertEquals("complete", 5, stats.getCompleteJobPairs());
		assertEquals("resource out", 2, stats.getResourceOutJobPairs());
		assertEquals("correct", 2, stats.getCorrectJobPairs());
		assertEquals("incorrect", 0, stats.getIncorrectJobPairs());
		assertEquals("unknown", 1, stats.getUnknown());
		assertEquals("solved column", "2/5", stats.getCorrectOverCompleted());
	}

	@Test
	public void resourceOutsAloneLeaveNoUnknown() throws Exception {
		SolverStats stats = new SolverStats();
		for (StatusCode code : RESOURCE_OUT) {
			add(stats, stage(code));
		}
		assertEquals("unknown", 0, stats.getUnknown());
		assertEquals("complete", RESOURCE_OUT.size(), stats.getCompleteJobPairs());
	}

	/** One stage for every status code, plus the three outcomes a completed stage can have. */
	@Test
	public void everyStatusIsCountedExactlyAsDecided() throws Exception {
		SolverStats stats = new SolverStats();
		for (StatusCode code : StatusCode.values()) {
			if (code != StatusCode.STATUS_COMPLETE) {
				add(stats, stage(code));
			}
		}
		add(stats, solved());
		add(stats, wrong());
		add(stats, unknownResult());

		int completedStages = 3;
		assertEquals("failed", FAILED.size(), stats.getFailedJobPairs());
		assertEquals("incomplete", INCOMPLETE.size(), stats.getIncompleteJobPairs());
		assertEquals("resource out", RESOURCE_OUT.size(), stats.getResourceOutJobPairs());
		assertEquals("complete", completedStages + RESOURCE_OUT.size(),
				stats.getCompleteJobPairs());
		assertEquals("correct", 1, stats.getCorrectJobPairs());
		assertEquals("incorrect", 1, stats.getIncorrectJobPairs());
		assertEquals("unknown", 1, stats.getUnknown());
		assertTrue("unknown is never negative", stats.getUnknown() >= 0);
	}

	private static JoblineStage stage(StatusCode code) {
		JoblineStage stage = new JoblineStage();
		Status status = new Status();
		status.setCode(code);
		stage.setStatus(status);
		return stage;
	}

	private static JoblineStage completed(String expected, String result) {
		JoblineStage stage = stage(StatusCode.STATUS_COMPLETE);
		Properties attributes = new Properties();
		if (expected != null) {
			attributes.setProperty(R.EXPECTED_RESULT, expected);
		}
		attributes.setProperty(R.STAREXEC_RESULT, result);
		stage.setAttributes(attributes);
		return stage;
	}

	private static JoblineStage solved() {
		return completed(THEOREM, THEOREM);
	}

	private static JoblineStage wrong() {
		return completed(THEOREM, "CounterSatisfiable");
	}

	private static JoblineStage unknownResult() {
		return completed(THEOREM, R.STAREXEC_UNKNOWN);
	}

	private static void add(SolverStats stats, JoblineStage stage) throws Exception {
		Method m = Jobs.class.getDeclaredMethod(
				"addStageToSolverStats", SolverStats.class, JoblineStage.class, boolean.class);
		m.setAccessible(true);
		try {
			m.invoke(null, stats, stage, false);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof Exception) {
				throw (Exception) e.getCause();
			}
			fail("addStageToSolverStats threw " + e.getCause());
		}
	}
}
