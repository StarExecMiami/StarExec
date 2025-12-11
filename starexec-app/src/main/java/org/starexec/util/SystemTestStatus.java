package org.starexec.util;

/**
 * value holder for system status flags related to testing.
 * This allows production code to check test status without importing test
 * classes.
 */
public class SystemTestStatus {
    private static volatile boolean stressTestRunning = false;
    private static volatile boolean testsRunning = false;

    public static boolean isStressTestRunning() {
        return stressTestRunning;
    }

    public static void setStressTestRunning(boolean running) {
        stressTestRunning = running;
    }

    public static boolean areTestsRunning() {
        return testsRunning;
    }

    public static void setTestsRunning(boolean running) {
        testsRunning = running;
    }
}
