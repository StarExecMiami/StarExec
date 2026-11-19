package org.starexec.test.integration;

/**
 * Selects the legacy {@link TestSequence} suite: {@link LegacyTestSequenceEngine} runs it when this
 * class is selected, which the opt-in {@code legacy-test-sequences} Maven profile does.
 */
public final class LegacyTestSequenceSuite {
	private LegacyTestSequenceSuite() {
	}
}
