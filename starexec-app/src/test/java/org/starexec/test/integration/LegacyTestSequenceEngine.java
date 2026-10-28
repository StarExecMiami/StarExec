package org.starexec.test.integration;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.ClassSelector;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.opentest4j.MultipleFailuresError;

/**
 * Runs the legacy {@link TestSequence} classes on the JUnit Platform. It contributes tests only
 * when {@link LegacyTestSequenceSuite} is selected, which only the opt-in
 * {@code legacy-test-sequences} Maven profile does.
 *
 * <p>Each sequence runs as one unit (setup, every method, teardown) through
 * {@link TestSequence#execute(TestSequence.ExecutionListener)}, and each method is reported as a
 * test. A failed construction, setup or teardown fails the sequence. Methods that never started
 * because setup failed are not reported, as with a failed {@code @BeforeAll} in Jupiter. The
 * inventory preflight runs during discovery, before any sequence is instantiated, and a refusal
 * fails the suite.
 */
public final class LegacyTestSequenceEngine implements TestEngine {
	static final String ENGINE_ID = "starexec-legacy-sequences";

	private final Supplier<List<TestSequenceDiscovery.DiscoveredSequence>> sequences;

	/** Used by the JUnit Platform, which finds the engine through {@code META-INF/services}. */
	public LegacyTestSequenceEngine() {
		this(LegacyTestSequenceEngine::discoverReadySequences);
	}

	LegacyTestSequenceEngine(Supplier<List<TestSequenceDiscovery.DiscoveredSequence>> sequences) {
		this.sequences = sequences;
	}

	private static List<TestSequenceDiscovery.DiscoveredSequence> discoverReadySequences() {
		List<TestSequenceDiscovery.DiscoveredSequence> discovered = TestSequenceDiscovery.discover();
		LegacyTestLayerInventory.load().validateReady(discovered);
		return discovered;
	}

	@Override
	public String getId() {
		return ENGINE_ID;
	}

	@Override
	public TestDescriptor discover(EngineDiscoveryRequest request, UniqueId uniqueId) {
		EngineDescriptor engine = new EngineDescriptor(uniqueId, "StarExec legacy TestSequences");
		boolean suiteSelected = request.getSelectorsByType(ClassSelector.class).stream()
				.anyMatch(selector -> selector.getClassName().equals(LegacyTestSequenceSuite.class.getName()));
		if (suiteSelected) {
			engine.addChild(SuiteDescriptor.discover(uniqueId, sequences));
		}
		return engine;
	}

	@Override
	public void execute(ExecutionRequest request) {
		EngineExecutionListener listener = request.getEngineExecutionListener();
		TestDescriptor engine = request.getRootTestDescriptor();
		listener.executionStarted(engine);
		for (TestDescriptor suite : engine.getChildren()) {
			((SuiteDescriptor) suite).execute(listener);
		}
		listener.executionFinished(engine, TestExecutionResult.successful());
	}

	private static final class SuiteDescriptor extends AbstractTestDescriptor {
		/** Why the suite cannot run, or null when it can. */
		private final RuntimeException refusal;

		private SuiteDescriptor(UniqueId uniqueId, RuntimeException refusal) {
			super(uniqueId, LegacyTestSequenceSuite.class.getName(), ClassSource.from(LegacyTestSequenceSuite.class));
			this.refusal = refusal;
		}

		static SuiteDescriptor discover(
				UniqueId engineId,
				Supplier<List<TestSequenceDiscovery.DiscoveredSequence>> sequences) {
			UniqueId suiteId = engineId.append("suite", LegacyTestSequenceSuite.class.getName());
			List<TestSequenceDiscovery.DiscoveredSequence> discovered;
			try {
				discovered = sequences.get();
			} catch (RuntimeException refusal) {
				return new SuiteDescriptor(suiteId, refusal);
			}
			if (TestSequenceDiscovery.countMethods(discovered) == 0) {
				return new SuiteDescriptor(suiteId,
						new IllegalStateException("Legacy TestSequence engine received zero methods"));
			}
			SuiteDescriptor suite = new SuiteDescriptor(suiteId, null);
			for (TestSequenceDiscovery.DiscoveredSequence sequence : discovered) {
				suite.addChild(new SequenceDescriptor(suiteId, sequence));
			}
			return suite;
		}

		@Override
		public Type getType() {
			// A suite that cannot run has no children. As a plain container the launcher would
			// prune it and Surefire would skip its class, hiding the refusal; as a test it is
			// kept and fails.
			return refusal == null ? Type.CONTAINER : Type.CONTAINER_AND_TEST;
		}

		void execute(EngineExecutionListener listener) {
			listener.executionStarted(this);
			if (refusal != null) {
				listener.executionFinished(this, TestExecutionResult.failed(refusal));
				return;
			}
			for (TestDescriptor sequence : getChildren()) {
				((SequenceDescriptor) sequence).execute(listener);
			}
			listener.executionFinished(this, TestExecutionResult.successful());
		}
	}

	private static final class SequenceDescriptor extends AbstractTestDescriptor {
		private final TestSequenceDiscovery.DiscoveredSequence sequence;
		private final Map<Method, MethodDescriptor> methods = new HashMap<>();

		private SequenceDescriptor(UniqueId suiteId, TestSequenceDiscovery.DiscoveredSequence sequence) {
			super(suiteId.append("sequence", sequence.sequenceClass().getName()),
					sequence.sequenceClass().getName(), ClassSource.from(sequence.sequenceClass()));
			this.sequence = sequence;
			for (Method method : sequence.testMethods()) {
				MethodDescriptor descriptor = new MethodDescriptor(getUniqueId(), sequence.sequenceClass(), method);
				methods.put(method, descriptor);
				addChild(descriptor);
			}
		}

		@Override
		public Type getType() {
			return Type.CONTAINER;
		}

		void execute(EngineExecutionListener listener) {
			listener.executionStarted(this);
			TestSequence instance;
			try {
				instance = TestSequenceDiscovery.instantiate(sequence);
			} catch (Throwable failure) {
				listener.executionFinished(this, TestExecutionResult.failed(
						new LegacyLifecycleException(sequence.sequenceClass(), "construction", failure)));
				return;
			}
			List<Throwable> failures = new ArrayList<>();
			Map<Method, Throwable> methodFailures = new HashMap<>();
			try {
				instance.execute(new TestSequence.ExecutionListener() {
					@Override
					public void testStarted(Method method) {
						listener.executionStarted(descriptorFor(method));
					}

					@Override
					public void testFailure(Method method, Throwable failure) {
						methodFailures.put(method, failure);
					}

					@Override
					public void testFinished(Method method) {
						Throwable failure = methodFailures.remove(method);
						listener.executionFinished(descriptorFor(method), failure == null
								? TestExecutionResult.successful()
								: TestExecutionResult.failed(failure));
					}

					@Override
					public void lifecycleFailure(String phase, Throwable failure) {
						failures.add(new LegacyLifecycleException(sequence.sequenceClass(), phase, failure));
					}
				});
			} catch (RuntimeException failure) {
				// Setup, methods and teardown report their own failures; this is a fault of the engine.
				failures.add(new LegacyLifecycleException(sequence.sequenceClass(), "execution", failure));
			}
			listener.executionFinished(this, resultOf(failures));
		}

		private MethodDescriptor descriptorFor(Method method) {
			MethodDescriptor descriptor = methods.get(method);
			if (descriptor == null) {
				throw new IllegalStateException("No descriptor for discovered legacy test: "
						+ TestSequenceDiscovery.identity(sequence.sequenceClass(), method));
			}
			return descriptor;
		}

		private static TestExecutionResult resultOf(List<Throwable> failures) {
			if (failures.isEmpty()) {
				return TestExecutionResult.successful();
			}
			if (failures.size() == 1) {
				return TestExecutionResult.failed(failures.get(0));
			}
			return TestExecutionResult.failed(new MultipleFailuresError(null, failures));
		}
	}

	private static final class MethodDescriptor extends AbstractTestDescriptor {
		private MethodDescriptor(UniqueId sequenceId, Class<? extends TestSequence> sequenceClass, Method method) {
			super(sequenceId.append("method", method.getName()), method.getName(),
					MethodSource.from(sequenceClass, method));
		}

		@Override
		public Type getType() {
			return Type.TEST;
		}
	}

	private static final class LegacyLifecycleException extends Exception {
		private static final long serialVersionUID = 1L;

		private LegacyLifecycleException(Class<? extends TestSequence> sequenceClass, String phase, Throwable cause) {
			super(sequenceClass.getName() + "#" + phase + " failed: " + failureMessage(cause), cause);
		}

		private static String failureMessage(Throwable failure) {
			return failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage();
		}
	}
}
