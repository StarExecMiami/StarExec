package org.starexec.test.integration;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * when a request selects {@link LegacyTestSequenceSuite}: the {@code legacy-test-sequences}
 * Maven profile does, while Surefire's default includes do not.
 *
 * <p>Supports layer selection via {@code -Dstarexec.legacy.layers=COMPONENT_DAO,...}, enforces
 * the layer inventory before instantiation, initializes PostgreSQL/fixtures once when running the
 * live suite, and emits SHA-bound JSON and CSV reports.
 */
public final class LegacyTestSequenceEngine implements TestEngine {
	static final String ENGINE_ID = "starexec-legacy-sequences";
	static final String LAYERS_PROPERTY = "starexec.legacy.layers";
	static final String REPORT_DIR_PROPERTY = "starexec.legacy.reportDir";
	static final String DEFAULT_REPORT_DIR = "target/legacy-sequence-reports";

	private final Supplier<List<TestSequenceDiscovery.DiscoveredSequence>> sequences;
	private final Supplier<LegacyTestLayerInventory> inventorySupplier;
	private final Supplier<Set<LegacyTestLayerInventory.Layer>> layersSupplier;
	private final Runnable bootstrap;
	private final Path reportDir;

	/** Used by the JUnit Platform, which finds the engine through {@code META-INF/services}. */
	public LegacyTestSequenceEngine() {
		this(
				() -> {
					LegacyTestLayerInventory inventory = LegacyTestLayerInventory.load();
					Set<LegacyTestLayerInventory.Layer> selectedLayers =
							LegacyTestLayerInventory.parseLayerFilter(System.getProperty(LAYERS_PROPERTY));
					return inventory.filter(TestSequenceDiscovery.discover(), selectedLayers);
				},
				LegacyTestLayerInventory::load,
				() -> LegacyTestLayerInventory.parseLayerFilter(System.getProperty(LAYERS_PROPERTY)),
				LegacySequenceBootstrap::initializeForLiveSuite,
				Path.of(System.getProperty(REPORT_DIR_PROPERTY, DEFAULT_REPORT_DIR)));
	}

	LegacyTestSequenceEngine(Supplier<List<TestSequenceDiscovery.DiscoveredSequence>> sequences) {
		this(sequences, null, null, () -> {}, null);
	}

	LegacyTestSequenceEngine(
			Supplier<List<TestSequenceDiscovery.DiscoveredSequence>> sequences,
			Supplier<LegacyTestLayerInventory> inventorySupplier,
			Supplier<Set<LegacyTestLayerInventory.Layer>> layersSupplier,
			Runnable bootstrap,
			Path reportDir) {
		this.sequences = sequences;
		this.inventorySupplier = inventorySupplier;
		this.layersSupplier = layersSupplier;
		this.bootstrap = bootstrap != null ? bootstrap : () -> {};
		this.reportDir = reportDir;
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
			engine.addChild(SuiteDescriptor.discover(
					uniqueId, sequences, inventorySupplier, layersSupplier, bootstrap, reportDir));
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
		private final Supplier<LegacyTestLayerInventory> inventorySupplier;
		private final Supplier<Set<LegacyTestLayerInventory.Layer>> layersSupplier;
		private final Runnable bootstrap;
		private final Path reportDir;

		private SuiteDescriptor(
				UniqueId uniqueId,
				RuntimeException refusal,
				Supplier<LegacyTestLayerInventory> inventorySupplier,
				Supplier<Set<LegacyTestLayerInventory.Layer>> layersSupplier,
				Runnable bootstrap,
				Path reportDir) {
			super(uniqueId, LegacyTestSequenceSuite.class.getName(), ClassSource.from(LegacyTestSequenceSuite.class));
			this.refusal = refusal;
			this.inventorySupplier = inventorySupplier;
			this.layersSupplier = layersSupplier;
			this.bootstrap = bootstrap;
			this.reportDir = reportDir;
		}

		static SuiteDescriptor discover(
				UniqueId engineId,
				Supplier<List<TestSequenceDiscovery.DiscoveredSequence>> sequences,
				Supplier<LegacyTestLayerInventory> inventorySupplier,
				Supplier<Set<LegacyTestLayerInventory.Layer>> layersSupplier,
				Runnable bootstrap,
				Path reportDir) {
			UniqueId suiteId = engineId.append("suite", LegacyTestSequenceSuite.class.getName());
			List<TestSequenceDiscovery.DiscoveredSequence> discovered;
			try {
				discovered = sequences.get();
			} catch (RuntimeException refusal) {
				return new SuiteDescriptor(suiteId, refusal, inventorySupplier, layersSupplier, bootstrap, reportDir);
			}
			if (TestSequenceDiscovery.countMethods(discovered) == 0) {
				return new SuiteDescriptor(
						suiteId,
						new IllegalStateException("Legacy TestSequence engine received zero methods"),
						inventorySupplier,
						layersSupplier,
						bootstrap,
						reportDir);
			}
			SuiteDescriptor suite = new SuiteDescriptor(
					suiteId, null, inventorySupplier, layersSupplier, bootstrap, reportDir);
			for (TestSequenceDiscovery.DiscoveredSequence sequence : discovered) {
				suite.addChild(new SequenceDescriptor(suiteId, sequence));
			}
			return suite;
		}

		@Override
		public Type getType() {
			return refusal == null ? Type.CONTAINER : Type.CONTAINER_AND_TEST;
		}

		void execute(EngineExecutionListener listener) {
			listener.executionStarted(this);
			if (refusal != null) {
				listener.executionFinished(this, TestExecutionResult.failed(refusal));
				return;
			}
			try {
				bootstrap.run();
			} catch (Throwable failure) {
				listener.executionFinished(this, TestExecutionResult.failed(failure));
				return;
			}

			LegacyTestLayerInventory inventory = inventorySupplier != null ? inventorySupplier.get() : null;
			Set<LegacyTestLayerInventory.Layer> selectedLayers = layersSupplier != null
					? layersSupplier.get()
					: Set.of(LegacyTestLayerInventory.Layer.COMPONENT_DAO);

			List<LegacySequenceReport.SequenceRecord> records = new ArrayList<>();
			int executedMethods = 0;
			for (TestDescriptor child : getChildren()) {
				SequenceDescriptor seq = (SequenceDescriptor) child;
				LegacySequenceReport.SequenceRecord rec = seq.execute(listener, inventory);
				records.add(rec);
				executedMethods += rec.methods.size();
			}

			Throwable suiteFailure = null;
			boolean anySequenceFailed = records.stream().anyMatch(r -> !"PASSED".equals(r.status));
			if (executedMethods == 0 && !anySequenceFailed) {
				suiteFailure = new IllegalStateException("Legacy TestSequence suite executed zero methods");
			}
			if (reportDir != null) {
				try {
					Map<LegacyTestLayerInventory.Layer, Integer> counts = inventory != null
							? inventory.countByLayer()
							: Map.of();
					LegacySequenceReport.writeReports(
							reportDir,
							LegacySequenceReport.resolveCommitSha(),
							selectedLayers,
							counts,
							records);
				} catch (IOException e) {
					if (suiteFailure == null) {
						suiteFailure = new IllegalStateException("Failed to write legacy sequence report", e);
					} else {
						suiteFailure.addSuppressed(e);
					}
				}
			}

			listener.executionFinished(
					this,
					suiteFailure == null ? TestExecutionResult.successful() : TestExecutionResult.failed(suiteFailure));
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

		LegacySequenceReport.SequenceRecord execute(
				EngineExecutionListener listener, LegacyTestLayerInventory inventory) {
			long seqStarted = System.currentTimeMillis();
			listener.executionStarted(this);
			TestSequence instance;
			try {
				instance = TestSequenceDiscovery.instantiate(sequence);
			} catch (Throwable failure) {
				Throwable wrapped = new LegacyLifecycleException(sequence.sequenceClass(), "construction", failure);
				listener.executionFinished(this, TestExecutionResult.failed(wrapped));
				LegacySequenceReport.SequenceRecord failedRec = new LegacySequenceReport.SequenceRecord(
						sequence.sequenceClass().getName(),
						"FAILED",
						System.currentTimeMillis() - seqStarted);
				failedRec.lifecycleFailures.add("construction: " + failure.getMessage());
				return failedRec;
			}
			List<Throwable> failures = new ArrayList<>();
			Map<Method, Throwable> methodFailures = new HashMap<>();
			Map<Method, Long> methodStarts = new HashMap<>();
			List<LegacySequenceReport.MethodRecord> methodRecords = new ArrayList<>();
			List<String> lifecycleErrors = new ArrayList<>();
			Set<Method> selectedMethods = new LinkedHashSet<>(sequence.testMethods());

			try {
				instance.execute(new TestSequence.ExecutionListener() {
					@Override
					public void testStarted(Method method) {
						methodStarts.put(method, System.currentTimeMillis());
						listener.executionStarted(descriptorFor(method));
					}

					@Override
					public void testFailure(Method method, Throwable failure) {
						methodFailures.put(method, failure);
					}

					@Override
					public void testFinished(Method method) {
						long started = methodStarts.getOrDefault(method, System.currentTimeMillis());
						long duration = System.currentTimeMillis() - started;
						Throwable failure = methodFailures.remove(method);
						String identity = TestSequenceDiscovery.identity(sequence.sequenceClass(), method);
						String layerName = inventory != null
								? safeLayer(inventory, identity)
								: "UNCLASSIFIED";
						methodRecords.add(new LegacySequenceReport.MethodRecord(
								identity,
								method.getName(),
								layerName,
								failure == null ? "PASSED" : "FAILED",
								duration,
								failure == null ? null : failureMessage(failure)));
						listener.executionFinished(descriptorFor(method), failure == null
								? TestExecutionResult.successful()
								: TestExecutionResult.failed(failure));
					}

					@Override
					public void lifecycleFailure(String phase, Throwable failure) {
						lifecycleErrors.add(phase + ": " + failureMessage(failure));
						failures.add(new LegacyLifecycleException(sequence.sequenceClass(), phase, failure));
					}
				}, selectedMethods);
			} catch (RuntimeException failure) {
				lifecycleErrors.add("execution: " + failureMessage(failure));
				failures.add(new LegacyLifecycleException(sequence.sequenceClass(), "execution", failure));
			}

			boolean sequencePassed = failures.isEmpty()
					&& methodRecords.stream().allMatch(rec -> "PASSED".equals(rec.status))
					&& methodRecords.size() == selectedMethods.size();
			if (failures.isEmpty() && methodRecords.size() != selectedMethods.size()) {
				failures.add(new IllegalStateException(
						"Method execution count mismatch in " + sequence.sequenceClass().getName()
								+ ": expected " + selectedMethods.size()
								+ ", executed " + methodRecords.size()));
			}
			listener.executionFinished(this, resultOf(failures));

			LegacySequenceReport.SequenceRecord rec = new LegacySequenceReport.SequenceRecord(
					sequence.sequenceClass().getName(),
					sequencePassed ? "PASSED" : "FAILED",
					System.currentTimeMillis() - seqStarted);
			rec.lifecycleFailures.addAll(lifecycleErrors);
			rec.methods.addAll(methodRecords);
			return rec;
		}

		private static String safeLayer(LegacyTestLayerInventory inventory, String identity) {
			try {
				return inventory.layerOf(identity).name();
			} catch (RuntimeException ignored) {
				return "UNCLASSIFIED";
			}
		}

		private static String failureMessage(Throwable failure) {
			return failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage();
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
