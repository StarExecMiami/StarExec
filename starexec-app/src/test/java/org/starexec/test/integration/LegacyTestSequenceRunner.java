package org.starexec.test.integration;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.InitializationError;

/** JUnit 4 adapter for the legacy sequence lifecycle, executed by Vintage. */
public final class LegacyTestSequenceRunner extends Runner {
	private final List<TestSequenceDiscovery.DiscoveredSequence> sequences;
	private final Description rootDescription;
	private final Map<Class<? extends TestSequence>, Description> sequenceDescriptions = new LinkedHashMap<>();
	private final Map<String, Description> methodDescriptions = new LinkedHashMap<>();

	public LegacyTestSequenceRunner(Class<?> suiteClass) throws InitializationError {
		this(suiteClass, discoverReadySequences());
	}

	LegacyTestSequenceRunner(
			Class<?> suiteClass,
			List<TestSequenceDiscovery.DiscoveredSequence> sequences) throws InitializationError {
		if (TestSequenceDiscovery.countMethods(sequences) == 0) {
			throw new InitializationError("Legacy TestSequence runner received zero methods");
		}
		this.sequences = List.copyOf(sequences);
		this.rootDescription = Description.createSuiteDescription(suiteClass);
		buildDescriptionTree();
	}

	@Override
	public Description getDescription() {
		return rootDescription;
	}

	@Override
	public void run(RunNotifier notifier) {
		notifier.fireTestSuiteStarted(rootDescription);
		try {
			for (TestSequenceDiscovery.DiscoveredSequence discovered : sequences) {
				runSequence(discovered, notifier);
			}
		} finally {
			notifier.fireTestSuiteFinished(rootDescription);
		}
	}

	private static List<TestSequenceDiscovery.DiscoveredSequence> discoverReadySequences()
			throws InitializationError {
		try {
			List<TestSequenceDiscovery.DiscoveredSequence> discovered = TestSequenceDiscovery.discover();
			LegacyTestLayerInventory.load().validateReady(discovered);
			return discovered;
		} catch (RuntimeException e) {
			throw new InitializationError(e);
		}
	}

	private void buildDescriptionTree() {
		for (TestSequenceDiscovery.DiscoveredSequence sequence : sequences) {
			Description sequenceDescription = Description.createSuiteDescription(sequence.sequenceClass());
			sequenceDescriptions.put(sequence.sequenceClass(), sequenceDescription);
			for (Method method : sequence.testMethods()) {
				String identity = TestSequenceDiscovery.identity(sequence.sequenceClass(), method);
				Description methodDescription = Description.createTestDescription(
						sequence.sequenceClass().getName(), method.getName(), identity);
				sequenceDescription.addChild(methodDescription);
				methodDescriptions.put(identity, methodDescription);
			}
			rootDescription.addChild(sequenceDescription);
		}
	}

	private void runSequence(
			TestSequenceDiscovery.DiscoveredSequence discovered,
			RunNotifier notifier) {
		Description sequenceDescription = sequenceDescriptions.get(discovered.sequenceClass());
		notifier.fireTestSuiteStarted(sequenceDescription);
		try {
			TestSequence sequence;
			try {
				sequence = TestSequenceDiscovery.instantiate(discovered);
			} catch (Throwable failure) {
				notifier.fireTestFailure(new Failure(
						sequenceDescription,
						new LegacyLifecycleException(discovered.sequenceClass(), "construction", failure)));
				return;
			}

			sequence.execute(new TestSequence.ExecutionListener() {
				@Override
				public void testStarted(Method method) {
					notifier.fireTestStarted(methodDescription(discovered, method));
				}

				@Override
				public void testFailure(Method method, Throwable failure) {
					notifier.fireTestFailure(new Failure(methodDescription(discovered, method), failure));
				}

				@Override
				public void testFinished(Method method) {
					notifier.fireTestFinished(methodDescription(discovered, method));
				}

				@Override
				public void lifecycleFailure(String phase, Throwable failure) {
					notifier.fireTestFailure(new Failure(
							sequenceDescription,
							new LegacyLifecycleException(discovered.sequenceClass(), phase, failure)));
				}
			});
		} finally {
			notifier.fireTestSuiteFinished(sequenceDescription);
		}
	}

	private Description methodDescription(
			TestSequenceDiscovery.DiscoveredSequence sequence,
			Method method) {
		String identity = TestSequenceDiscovery.identity(sequence.sequenceClass(), method);
		Description description = methodDescriptions.get(identity);
		if (description == null) {
			throw new IllegalStateException("No JUnit description for discovered legacy test: " + identity);
		}
		return description;
	}

	private static final class LegacyLifecycleException extends Exception {
		private static final long serialVersionUID = 1L;

		private LegacyLifecycleException(
				Class<? extends TestSequence> sequenceClass,
				String phase,
				Throwable cause) {
			super(sequenceClass.getName() + "#" + phase + " failed: " + failureMessage(cause), cause);
		}

		private static String failureMessage(Throwable failure) {
			return failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage();
		}
	}
}
