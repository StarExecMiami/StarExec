package org.starexec.test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runners.model.InitializationError;

public class LegacyTestSequenceRunnerTest {
	private static final IllegalStateException METHOD_FAILURE = new IllegalStateException("method exploded");

	@BeforeEach
	public void resetSequences() {
		RecordingSequence.reset();
		SetupFailureSequence.reset();
		TeardownFailureSequence.reset();
		DoubleLifecycleFailureSequence.reset();
	}

	@Test
	public void reportsEveryMethodAndContinuesAfterFailure() throws Exception {
		RunOutcome outcome = run(RecordingSequence.class);

		assertEquals(List.of(
				"setup",
				"alphaPasses", "after:alphaPasses",
				"betaFails", "after:betaFails",
				"gammaPasses", "after:gammaPasses",
				"teardown"), RecordingSequence.calls);
		assertEquals(3, outcome.result.getRunCount());
		assertEquals(1, outcome.result.getFailureCount());
		assertEquals(0, outcome.result.getIgnoreCount());
		assertEquals(0, outcome.result.getAssumptionFailureCount());
		assertEquals(List.of("alphaPasses", "betaFails", "gammaPasses"), outcome.startedMethods);
		assertEquals(outcome.startedMethods, outcome.finishedMethods);
		assertTrue(outcome.ignored.isEmpty());

		Failure failure = outcome.result.getFailures().get(0);
		assertEquals(RecordingSequence.class.getName(), failure.getDescription().getClassName());
		assertEquals("betaFails", failure.getDescription().getMethodName());
		assertSame(METHOD_FAILURE, failure.getException());

		RecordingSequence sequence = RecordingSequence.lastInstance;
		assertEquals(2, sequence.getTestsPassed());
		assertEquals(1, sequence.getTestsFailed());
		assertEquals(TestStatus.TestStatusCode.STATUS_FAILED, sequence.getStatus().getCode());
		assertEquals(List.of("alphaPasses", "betaFails", "gammaPasses"),
				sequence.getTestResults().stream().map(TestResult::getName).toList());
	}

	@Test
	public void setupFailureIsVisibleAndTeardownStillRuns() throws Exception {
		RunOutcome outcome = run(SetupFailureSequence.class);

		assertEquals(List.of("setup", "teardown"), SetupFailureSequence.calls);
		assertEquals(0, outcome.result.getRunCount());
		assertEquals(1, outcome.result.getFailureCount());
		assertEquals(0, outcome.result.getIgnoreCount());
		assertTrue(outcome.startedMethods.isEmpty());
		assertTrue(outcome.result.getFailures().get(0).getMessage().contains("#setup"));
		assertTrue(outcome.result.getFailures().get(0).getMessage().contains("setup exploded"));
	}

	@Test
	public void teardownFailureIsVisibleAfterACompletedMethod() throws Exception {
		RunOutcome outcome = run(TeardownFailureSequence.class);

		assertEquals(List.of("setup", "onlyTest", "teardown"), TeardownFailureSequence.calls);
		assertEquals(1, outcome.result.getRunCount());
		assertEquals(1, outcome.result.getFailureCount());
		assertEquals(List.of("onlyTest"), outcome.startedMethods);
		assertEquals(outcome.startedMethods, outcome.finishedMethods);
		assertTrue(outcome.result.getFailures().get(0).getMessage().contains("#teardown"));
		assertTrue(outcome.result.getFailures().get(0).getMessage().contains("teardown exploded"));
	}

	@Test
	public void setupAndTeardownFailuresAreBothReportedWithoutSkips() throws Exception {
		RunOutcome outcome = run(DoubleLifecycleFailureSequence.class);

		assertEquals(List.of("setup", "teardown"), DoubleLifecycleFailureSequence.calls);
		assertEquals(0, outcome.result.getRunCount());
		assertEquals(2, outcome.result.getFailureCount());
		assertEquals(0, outcome.result.getIgnoreCount());
		assertEquals(2, outcome.result.getFailures().size());
		assertTrue(outcome.result.getFailures().stream().anyMatch(failure -> failure.getMessage().contains("#setup")));
		assertTrue(outcome.result.getFailures().stream().anyMatch(failure -> failure.getMessage().contains("#teardown")));
	}

	@Test
	public void rejectsASequenceWithZeroMethods() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> TestSequenceDiscovery.inspectClasses(List.of(EmptySequence.class)));

		assertTrue(error.getMessage().contains(EmptySequence.class.getName()));
		assertTrue(error.getMessage().contains("zero @StarexecTest methods"));
	}

	@Test
	public void runnerDescriptionHasOneOrderedLeafPerMethod() throws Exception {
		LegacyTestSequenceRunner runner = runnerFor(RecordingSequence.class);
		Description sequence = runner.getDescription().getChildren().get(0);

		assertEquals(3, runner.testCount());
		assertEquals(RecordingSequence.class.getName(), sequence.getDisplayName());
		assertEquals(List.of("alphaPasses", "betaFails", "gammaPasses"),
				sequence.getChildren().stream().map(Description::getMethodName).toList());
	}

	private static RunOutcome run(Class<? extends TestSequence> sequenceClass) throws Exception {
		JUnitCore core = new JUnitCore();
		RunOutcome outcome = new RunOutcome();
		core.addListener(outcome);
		outcome.result = core.run(runnerFor(sequenceClass));
		return outcome;
	}

	private static LegacyTestSequenceRunner runnerFor(Class<? extends TestSequence> sequenceClass)
			throws InitializationError {
		return new LegacyTestSequenceRunner(
				LegacyTestSequenceRunnerTest.class,
				TestSequenceDiscovery.inspectClasses(List.of(sequenceClass)));
	}

	private static final class RunOutcome extends RunListener {
		private Result result;
		private final List<String> startedMethods = new ArrayList<>();
		private final List<String> finishedMethods = new ArrayList<>();
		private final List<String> ignored = new ArrayList<>();

		@Override
		public void testStarted(Description description) {
			startedMethods.add(description.getMethodName());
		}

		@Override
		public void testFinished(Description description) {
			finishedMethods.add(description.getMethodName());
		}

		@Override
		public void testIgnored(Description description) {
			ignored.add(description.getDisplayName());
		}
	}

	private abstract static class InMemorySequence extends TestSequence {
		@Override
		protected String getTestName() {
			return getClass().getSimpleName();
		}
	}

	private static final class RecordingSequence extends InMemorySequence {
		private static final List<String> calls = new ArrayList<>();
		private static RecordingSequence lastInstance;

		private RecordingSequence() {
			lastInstance = this;
		}

		private static void reset() {
			calls.clear();
			lastInstance = null;
		}

		@Override
		protected void setup() {
			calls.add("setup");
		}

		@StarexecTest
		private void gammaPasses() {
			calls.add("gammaPasses");
		}

		@StarexecTest
		private void betaFails() {
			calls.add("betaFails");
			throw METHOD_FAILURE;
		}

		@StarexecTest
		private void alphaPasses() {
			calls.add("alphaPasses");
		}

		@StarexecAfter
		private void after(Method method) {
			calls.add("after:" + method.getName());
		}

		@Override
		protected void teardown() {
			calls.add("teardown");
		}
	}

	private static final class SetupFailureSequence extends InMemorySequence {
		private static final List<String> calls = new ArrayList<>();

		private static void reset() {
			calls.clear();
		}

		@Override
		protected void setup() {
			calls.add("setup");
			throw new IllegalStateException("setup exploded");
		}

		@StarexecTest
		private void shouldNotRun() {
			calls.add("shouldNotRun");
		}

		@Override
		protected void teardown() {
			calls.add("teardown");
		}
	}

	private static final class TeardownFailureSequence extends InMemorySequence {
		private static final List<String> calls = new ArrayList<>();

		private static void reset() {
			calls.clear();
		}

		@Override
		protected void setup() {
			calls.add("setup");
		}

		@StarexecTest
		private void onlyTest() {
			calls.add("onlyTest");
		}

		@Override
		protected void teardown() {
			calls.add("teardown");
			throw new IllegalStateException("teardown exploded");
		}
	}

	private static final class DoubleLifecycleFailureSequence extends InMemorySequence {
		private static final List<String> calls = new ArrayList<>();

		private static void reset() {
			calls.clear();
		}

		@Override
		protected void setup() {
			calls.add("setup");
			throw new IllegalStateException("setup exploded");
		}

		@StarexecTest
		private void shouldNotRun() {
			calls.add("shouldNotRun");
		}

		@Override
		protected void teardown() {
			calls.add("teardown");
			throw new IllegalStateException("teardown exploded");
		}
	}

	private static final class EmptySequence extends InMemorySequence {
		@Override
		protected void setup() {
		}

		@Override
		protected void teardown() {
		}
	}
}
