package org.starexec.test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.runners.model.InitializationError;

public class LegacyTestSequenceDiscoveryTest {
	private static final String MATRIX_TESTS =
			"org.starexec.test.integration.util.matrixView.MatrixTests";
	private static final String PAIRS_RERUN_TESTS =
			"org.starexec.test.integration.database.PairsRerunTests";
	private static final String SETTING_SECURITY_TESTS =
			"org.starexec.test.integration.security.SettingSecurityTests";

	@Test
	public void discoversTheExactLegacySuiteInDeterministicOrder() {
		List<TestSequenceDiscovery.DiscoveredSequence> sequences = TestSequenceDiscovery.discover();

		assertEquals(44, sequences.size());
		assertEquals(725, TestSequenceDiscovery.countMethods(sequences));

		List<String> classNames = sequences.stream()
				.map(sequence -> sequence.sequenceClass().getName())
				.collect(Collectors.toList());
		List<String> sortedClassNames = new ArrayList<>(classNames);
		sortedClassNames.sort(String::compareTo);
		assertEquals(sortedClassNames, classNames);
		assertTrue(classNames.containsAll(Set.of(
				MATRIX_TESTS,
				PAIRS_RERUN_TESTS,
				SETTING_SECURITY_TESTS)));
		assertEquals(5, sequences.stream()
				.filter(sequence -> Set.of(
						MATRIX_TESTS,
						PAIRS_RERUN_TESTS,
						SETTING_SECURITY_TESTS).contains(sequence.sequenceClass().getName()))
				.mapToInt(sequence -> sequence.testMethods().size())
				.sum());

		for (TestSequenceDiscovery.DiscoveredSequence sequence : sequences) {
			List<String> methodNames = sequence.testMethods().stream()
					.map(method -> method.getName())
					.collect(Collectors.toList());
			List<String> sortedMethodNames = new ArrayList<>(methodNames);
			sortedMethodNames.sort(String::compareTo);
			assertEquals(sortedMethodNames, methodNames, sequence.sequenceClass().getName());
		}
	}

	@Test
	public void testManagerInitializesFromDiscoveryOnlyOnce() {
		List<Class<? extends TestSequence>> discoveredClasses = TestSequenceDiscovery.discover().stream()
				.map(TestSequenceDiscovery.DiscoveredSequence::sequenceClass)
				.collect(Collectors.toList());

		TestManager.initializeTests();
		List<TestSequence> firstInitialization = TestManager.getAllTestSequences();
		TestManager.initializeTests();
		List<TestSequence> secondInitialization = TestManager.getAllTestSequences();

		assertSame(firstInitialization, secondInitialization);
		assertEquals(discoveredClasses,
				firstInitialization.stream().map(TestSequence::getClass).collect(Collectors.toList()));
	}

	@Test
	public void rejectsAnInvalidMethodSignatureWithItsStableIdentity() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> TestSequenceDiscovery.inspectClasses(List.of(InvalidSignatureSequence.class)));

		assertTrue(error.getMessage().contains(InvalidSignatureSequence.class.getName() + "#hasArgument"));
		assertTrue(error.getMessage().contains("no arguments"));
	}

	@Test
	public void rejectsANonVoidMethodWithItsStableIdentity() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> TestSequenceDiscovery.inspectClasses(List.of(InvalidReturnSequence.class)));

		assertTrue(error.getMessage().contains(InvalidReturnSequence.class.getName() + "#returnsValue"));
		assertTrue(error.getMessage().contains("return void"));
	}

	@Test
	public void rejectsDuplicateMethodNamesBeforeTheyCanCollideInResults() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> TestSequenceDiscovery.inspectClasses(List.of(DuplicateNameSequence.class)));

		assertTrue(error.getMessage().contains(DuplicateNameSequence.class.getName() + "#duplicate"));
		assertTrue(error.getMessage().contains("duplicate"));
	}

	@Test
	public void inventoryCoversEveryMethodButBlocksExecutionUntilLayersAreVerified() {
		List<TestSequenceDiscovery.DiscoveredSequence> sequences = TestSequenceDiscovery.discover();
		LegacyTestLayerInventory inventory = LegacyTestLayerInventory.load();

		inventory.validateCoverage(sequences);
		assertEquals(725, inventory.size());
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> inventory.validateReady(sequences));
		assertTrue(error.getMessage().contains("725 UNVERIFIED"));
		assertTrue(error.getMessage().contains("COMPONENT_DAO"));
		assertTrue(error.getMessage().contains("BACKEND_INTEGRATION"));
		assertTrue(error.getMessage().contains("DEPLOYED_E2E"));
	}

	@Test
	public void liveSuiteRunnerStopsAtTheInventoryPreflight() {
		InitializationError error = assertThrows(InitializationError.class,
				() -> new LegacyTestSequenceRunner(LegacyTestSequenceSuite.class));

		assertTrue(error.getCauses().stream()
				.map(Throwable::getMessage)
				.anyMatch(message -> message != null && message.contains("725 UNVERIFIED")));
	}

	private abstract static class InMemorySequence extends TestSequence {
		@Override
		protected String getTestName() {
			return getClass().getSimpleName();
		}

		@Override
		protected void setup() {
		}

		@Override
		protected void teardown() {
		}
	}

	private static final class InvalidSignatureSequence extends InMemorySequence {
		@StarexecTest
		private void hasArgument(String value) {
		}
	}

	private static final class DuplicateNameSequence extends InMemorySequence {
		@StarexecTest
		private void duplicate() {
		}

		@StarexecTest
		private void duplicate(String value) {
		}
	}

	private static final class InvalidReturnSequence extends InMemorySequence {
		@StarexecTest
		private int returnsValue() {
			return 1;
		}
	}
}
