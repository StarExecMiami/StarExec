package org.starexec.test.integration;

/**
 * Minimal stub to satisfy main code imports. Real implementations live in test sources.
 */
public class TestResult {
    public String getName() { return ""; }
    public TestStatus getStatus() { return new TestStatus(); }
    public String getAllMessages() { return ""; }
    public String getErrorTrace() { return ""; }
    public double getTime() { return 0.0; }
}
