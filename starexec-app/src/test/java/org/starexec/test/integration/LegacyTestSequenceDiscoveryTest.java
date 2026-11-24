package org.starexec.test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

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
	public void testManagerInitializesAllVerifiedSequencesOnceIdempotently() {
		TestManager.initializeTests();
		List<TestSequence> first = TestManager.getAllTestSequences();
		TestManager.initializeTests();
		List<TestSequence> second = TestManager.getAllTestSequences();

		assertEquals(44, first.size());
		assertSame(first, second);
		Set<String> classNames = first.stream()
				.map(seq -> seq.getClass().getName())
				.collect(Collectors.toSet());
		assertTrue(classNames.containsAll(Set.of(
				MATRIX_TESTS,
				PAIRS_RERUN_TESTS,
				SETTING_SECURITY_TESTS)));
	}

	@Test
	public void testManagerValidatesBeforeInstantiatingDiscoveredSequences() {
		BlockedManagerSequence.reset();
		List<TestSequenceDiscovery.DiscoveredSequence> sequences =
				TestSequenceDiscovery.inspectClasses(List.of(BlockedManagerSequence.class));
		String identity = TestSequenceDiscovery.identity(
				BlockedManagerSequence.class, sequences.get(0).testMethods().get(0));
		LegacyTestLayerInventory inventory = LegacyTestLayerInventory.of(
				Map.of(identity, LegacyTestLayerInventory.Layer.UNVERIFIED));

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> TestManager.instantiateReadySequences(sequences, inventory));

		assertTrue(error.getMessage().contains("1 UNVERIFIED"));
		assertEquals(0, BlockedManagerSequence.constructions);
		assertEquals(0, BlockedManagerSequence.executions);
	}

	@Test
	public void rejectsEmptyOrUnknownLayerSelectionsAndUnverifiedFilter() {
		assertThrows(IllegalStateException.class, () -> LegacyTestLayerInventory.parseLayerFilter(","));
		assertThrows(IllegalStateException.class, () -> LegacyTestLayerInventory.parseLayerFilter("NO_SUCH_LAYER"));
		assertThrows(IllegalStateException.class, () -> LegacyTestLayerInventory.parseLayerFilter("UNVERIFIED"));
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
	public void inventoryClassifiesAllMethodsWithZeroUnverifiedAndFiltersByLayer() {
		List<TestSequenceDiscovery.DiscoveredSequence> sequences = TestSequenceDiscovery.discover();
		LegacyTestLayerInventory inventory = LegacyTestLayerInventory.load();

		inventory.validateReady(sequences);
		assertEquals(725, inventory.size());

		Map<LegacyTestLayerInventory.Layer, Integer> counts = inventory.countByLayer();
		assertEquals(0, counts.get(LegacyTestLayerInventory.Layer.UNVERIFIED));
		assertEquals(530, counts.get(LegacyTestLayerInventory.Layer.COMPONENT_DAO));
		assertEquals(81, counts.get(LegacyTestLayerInventory.Layer.BACKEND_INTEGRATION));
		assertEquals(114, counts.get(LegacyTestLayerInventory.Layer.DEPLOYED_E2E));

		List<TestSequenceDiscovery.DiscoveredSequence> daoOnly = inventory.filter(
				sequences, Set.of(LegacyTestLayerInventory.Layer.COMPONENT_DAO));
		assertEquals(530, TestSequenceDiscovery.countMethods(daoOnly));
		assertTrue(daoOnly.stream().anyMatch(s -> s.sequenceClass().getName().equals(MATRIX_TESTS)));
		assertTrue(daoOnly.stream().anyMatch(s -> s.sequenceClass().getName().equals(PAIRS_RERUN_TESTS)));
		assertTrue(daoOnly.stream().anyMatch(s -> s.sequenceClass().getName().equals(SETTING_SECURITY_TESTS)));

		List<TestSequenceDiscovery.DiscoveredSequence> backendOnly = inventory.filter(
				sequences, Set.of(LegacyTestLayerInventory.Layer.BACKEND_INTEGRATION));
		assertEquals(81, TestSequenceDiscovery.countMethods(backendOnly));
		assertEquals(4, backendOnly.stream()
				.filter(s -> s.sequenceClass().getName().equals("org.starexec.test.integration.database.JobTests"))
				.mapToInt(s -> s.testMethods().size())
				.sum());

		List<TestSequenceDiscovery.DiscoveredSequence> deployedOnly = inventory.filter(
				sequences, Set.of(LegacyTestLayerInventory.Layer.DEPLOYED_E2E));
		assertEquals(114, TestSequenceDiscovery.countMethods(deployedOnly));
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

	private static final class BlockedManagerSequence extends InMemorySequence {
		private static int constructions;
		private static int executions;

		private BlockedManagerSequence() {
			constructions++;
		}

		private static void reset() {
			constructions = 0;
			executions = 0;
		}

		@StarexecTest
		private void wouldExecute() {
			executions++;
		}
	}
}
