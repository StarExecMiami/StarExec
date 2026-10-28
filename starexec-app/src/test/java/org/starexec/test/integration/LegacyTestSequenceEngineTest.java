package org.starexec.test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.TestExecutionResult.Status.FAILED;
import static org.junit.platform.engine.TestExecutionResult.Status.SUCCESSFUL;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.opentest4j.MultipleFailuresError;

/** Runs the engine through the JUnit Platform launcher, the way Surefire and Failsafe do. */
public class LegacyTestSequenceEngineTest {
	private static final IllegalStateException METHOD_FAILURE = new IllegalStateException("method exploded");
	private static final String ENGINE_NAME = "StarExec legacy TestSequences";
	private static final String SUITE = LegacyTestSequenceSuite.class.getName();

	@BeforeEach
	public void resetSequences() {
		RecordingSequence.reset();
		SetupFailureSequence.reset();
		TeardownFailureSequence.reset();
		DoubleLifecycleFailureSequence.reset();
	}

	@Test
	public void reportsEveryMethodAndContinuesAfterFailure() {
		Run run = run(RecordingSequence.class);

		assertEquals(List.of(
				"setup",
				"alphaPasses", "after:alphaPasses",
				"betaFails", "after:betaFails",
				"gammaPasses", "after:gammaPasses",
				"teardown"), RecordingSequence.calls);
		assertEquals(List.of("alphaPasses", "betaFails", "gammaPasses"), run.startedTests);
		assertEquals(run.startedTests, run.finishedTests);
		assertTrue(run.skipped.isEmpty());
		assertEquals(List.of("betaFails"), run.failed());
		assertEquals(SUCCESSFUL, run.result("alphaPasses").getStatus());
		assertEquals(SUCCESSFUL, run.result("gammaPasses").getStatus());
		assertSame(METHOD_FAILURE, run.result("betaFails").getThrowable().orElseThrow());
		MethodSource source = (MethodSource) run.identifier("betaFails").getSource().orElseThrow();
		assertEquals(RecordingSequence.class.getName(), source.getClassName());
		assertEquals("betaFails", source.getMethodName());

		RecordingSequence sequence = RecordingSequence.lastInstance;
		assertEquals(2, sequence.getTestsPassed());
		assertEquals(1, sequence.getTestsFailed());
		assertEquals(TestStatus.TestStatusCode.STATUS_FAILED, sequence.getStatus().getCode());
		assertEquals(List.of("alphaPasses", "betaFails", "gammaPasses"),
				sequence.getTestResults().stream().map(TestResult::getName).toList());
	}

	@Test
	public void setupFailureIsVisibleAndTeardownStillRuns() {
		Run run = run(SetupFailureSequence.class);

		assertEquals(List.of("setup", "teardown"), SetupFailureSequence.calls);
		assertTrue(run.startedTests.isEmpty());
		assertTrue(run.skipped.isEmpty());
		assertEquals(List.of(SetupFailureSequence.class.getName()), run.failed());
		String message = run.failureOf(SetupFailureSequence.class.getName()).getMessage();
		assertTrue(message.contains("#setup"), message);
		assertTrue(message.contains("setup exploded"), message);
	}

	@Test
	public void teardownFailureIsVisibleAfterACompletedMethod() {
		Run run = run(TeardownFailureSequence.class);

		assertEquals(List.of("setup", "onlyTest", "teardown"), TeardownFailureSequence.calls);
		assertEquals(List.of("onlyTest"), run.startedTests);
		assertEquals(run.startedTests, run.finishedTests);
		assertEquals(SUCCESSFUL, run.result("onlyTest").getStatus());
		assertEquals(List.of(TeardownFailureSequence.class.getName()), run.failed());
		String message = run.failureOf(TeardownFailureSequence.class.getName()).getMessage();
		assertTrue(message.contains("#teardown"), message);
		assertTrue(message.contains("teardown exploded"), message);
	}

	@Test
	public void setupAndTeardownFailuresAreBothReportedWithoutSkips() {
		Run run = run(DoubleLifecycleFailureSequence.class);

		assertEquals(List.of("setup", "teardown"), DoubleLifecycleFailureSequence.calls);
		assertTrue(run.startedTests.isEmpty());
		assertTrue(run.skipped.isEmpty());
		assertEquals(List.of(DoubleLifecycleFailureSequence.class.getName()), run.failed());
		MultipleFailuresError failure = assertInstanceOf(MultipleFailuresError.class,
				run.failureOf(DoubleLifecycleFailureSequence.class.getName()));
		assertEquals(2, failure.getFailures().size());
		assertTrue(failure.getFailures().get(0).getMessage().contains("#setup"));
		assertTrue(failure.getFailures().get(1).getMessage().contains("#teardown"));
	}

	@Test
	public void constructionFailureFailsTheSequenceWithoutRunningIt() {
		Run run = run(ConstructionFailureSequence.class);

		assertTrue(run.startedTests.isEmpty());
		assertEquals(List.of(ConstructionFailureSequence.class.getName()), run.failed());
		String message = run.failureOf(ConstructionFailureSequence.class.getName()).getMessage();
		assertTrue(message.contains("#construction"), message);
		assertTrue(message.contains("constructor exploded"), message);
	}

	@Test
	public void aRefusedSuiteIsKeptAndFailsAsOneTest() {
		IllegalStateException refusal = new IllegalStateException("inventory refused");
		Supplier<List<TestSequenceDiscovery.DiscoveredSequence>> refused = () -> {
			throw refusal;
		};

		// Surefire runs a class only if its test plan contains a test, so this must hold.
		assertTrue(launcher(new LegacyTestSequenceEngine(refused)).discover(suiteRequest()).containsTests());
		Run run = run(refused);
		assertEquals(Set.of(ENGINE_NAME, SUITE), run.results.keySet());
		assertEquals(List.of(SUITE), run.failed());
		assertSame(refusal, run.failureOf(SUITE));
	}

	@Test
	public void aSuiteWithoutMethodsFails() {
		Run run = run(List::of);

		assertEquals(List.of(SUITE), run.failed());
		assertTrue(run.failureOf(SUITE).getMessage().contains("zero methods"));
	}

	@Test
	public void rejectsASequenceWithZeroMethods() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> TestSequenceDiscovery.inspectClasses(List.of(EmptySequence.class)));

		assertTrue(error.getMessage().contains(EmptySequence.class.getName()));
		assertTrue(error.getMessage().contains("zero @StarexecTest methods"));
	}

	@Test
	public void discoveryHasOneOrderedLeafPerMethod() {
		LegacyTestSequenceEngine engine = engineFor(RecordingSequence.class);
		TestDescriptor root = engine.discover(suiteRequest(), UniqueId.forEngine(LegacyTestSequenceEngine.ENGINE_ID));

		TestDescriptor suite = root.getChildren().iterator().next();
		TestDescriptor sequence = suite.getChildren().iterator().next();
		assertEquals(1, root.getChildren().size());
		assertEquals(1, suite.getChildren().size());
		assertEquals(RecordingSequence.class.getName(), sequence.getDisplayName());
		assertEquals(List.of("alphaPasses", "betaFails", "gammaPasses"),
				sequence.getChildren().stream().map(TestDescriptor::getDisplayName).toList());
		assertEquals(3, launcher(engine).discover(suiteRequest()).countTestIdentifiers(TestIdentifier::isTest));
	}

	@Test
	public void contributesNothingUnlessTheSuiteIsSelected() {
		AtomicInteger discoveries = new AtomicInteger();
		LegacyTestSequenceEngine engine = new LegacyTestSequenceEngine(() -> {
			discoveries.incrementAndGet();
			return TestSequenceDiscovery.inspectClasses(List.of(RecordingSequence.class));
		});

		TestPlan plan = launcher(engine).discover(
				LauncherDiscoveryRequestBuilder.request().selectors(selectClass(getClass())).build());

		assertFalse(plan.containsTests());
		assertEquals(0, discoveries.get());
	}

	@Test
	public void theLiveSuiteStopsAtTheInventoryPreflight() {
		Run run = run(new LegacyTestSequenceEngine());

		assertEquals(Set.of(ENGINE_NAME, SUITE), run.results.keySet());
		assertEquals(List.of(SUITE), run.failed());
		assertTrue(run.failureOf(SUITE).getMessage().contains("725 UNVERIFIED"));
	}

	private static LegacyTestSequenceEngine engineFor(Class<? extends TestSequence> sequenceClass) {
		return new LegacyTestSequenceEngine(() -> TestSequenceDiscovery.inspectClasses(List.of(sequenceClass)));
	}

	private static Run run(Class<? extends TestSequence> sequenceClass) {
		return run(engineFor(sequenceClass));
	}

	private static Run run(Supplier<List<TestSequenceDiscovery.DiscoveredSequence>> sequences) {
		return run(new LegacyTestSequenceEngine(sequences));
	}

	private static Run run(LegacyTestSequenceEngine engine) {
		Run run = new Run();
		launcher(engine).execute(suiteRequest(), run);
		assertEquals(SUCCESSFUL, run.result(ENGINE_NAME).getStatus(), "the engine itself must not fail");
		return run;
	}

	/** A launcher that sees only the given engine, not the ones registered on the classpath. */
	private static Launcher launcher(LegacyTestSequenceEngine engine) {
		return LauncherFactory.create(LauncherConfig.builder()
				.enableTestEngineAutoRegistration(false)
				.enableLauncherSessionListenerAutoRegistration(false)
				.enableLauncherDiscoveryListenerAutoRegistration(false)
				.enablePostDiscoveryFilterAutoRegistration(false)
				.enableTestExecutionListenerAutoRegistration(false)
				.addTestEngines(engine)
				.build());
	}

	private static LauncherDiscoveryRequest suiteRequest() {
		return LauncherDiscoveryRequestBuilder.request().selectors(selectClass(LegacyTestSequenceSuite.class)).build();
	}

	/** What the launcher reported, by display name. */
	private static final class Run implements TestExecutionListener {
		private final List<String> startedTests = new ArrayList<>();
		private final List<String> finishedTests = new ArrayList<>();
		private final List<String> skipped = new ArrayList<>();
		private final Map<String, TestIdentifier> identifiers = new LinkedHashMap<>();
		private final Map<String, TestExecutionResult> results = new LinkedHashMap<>();

		@Override
		public void executionStarted(TestIdentifier identifier) {
			if (identifier.isTest() && identifier.getSource().orElse(null) instanceof MethodSource) {
				startedTests.add(identifier.getDisplayName());
			}
		}

		@Override
		public void executionSkipped(TestIdentifier identifier, String reason) {
			skipped.add(identifier.getDisplayName());
		}

		@Override
		public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
			if (identifier.isTest() && identifier.getSource().orElse(null) instanceof MethodSource) {
				finishedTests.add(identifier.getDisplayName());
			}
			identifiers.put(identifier.getDisplayName(), identifier);
			results.put(identifier.getDisplayName(), result);
		}

		TestIdentifier identifier(String displayName) {
			TestIdentifier identifier = identifiers.get(displayName);
			assertNotNull(identifier, "not reported: " + displayName);
			return identifier;
		}

		TestExecutionResult result(String displayName) {
			TestExecutionResult result = results.get(displayName);
			assertNotNull(result, "not reported: " + displayName);
			return result;
		}

		/** Display names of everything that failed, in the order it finished. */
		List<String> failed() {
			return results.entrySet().stream()
					.filter(entry -> entry.getValue().getStatus() == FAILED)
					.map(Map.Entry::getKey)
					.collect(Collectors.toList());
		}

		Throwable failureOf(String displayName) {
			TestExecutionResult result = result(displayName);
			assertEquals(FAILED, result.getStatus(), displayName);
			return result.getThrowable().orElseThrow();
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

	private static final class ConstructionFailureSequence extends InMemorySequence {
		private ConstructionFailureSequence() {
			throw new IllegalStateException("constructor exploded");
		}

		@Override
		protected void setup() {
		}

		@StarexecTest
		private void neverRuns() {
		}

		@Override
		protected void teardown() {
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
