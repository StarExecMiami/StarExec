package org.starexec.test.integration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal stub to satisfy main code imports. Real implementations live in test sources.
 */
public final class TestManager {
    private TestManager() {}

    public static void initializeTests() {
        // no-op
    }

    public static boolean areTestsRunning() {
        return false;
    }

    public static boolean isStressTestRunning() {
        return false;
    }

    public static void executeTests(String[] testNames) {
        // no-op
    }

    public static boolean executeAllTestSequences() {
        return true;
    }

    public static List<TestSequence> getAllTestSequences() {
        return Collections.emptyList();
    }

    public static List<TestResult> getAllTestResults(String name) {
        return new ArrayList<>();
    }
}
