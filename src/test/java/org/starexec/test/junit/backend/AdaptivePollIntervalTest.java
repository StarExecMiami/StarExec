package org.starexec.test.junit.backend;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.starexec.backend.AdaptivePollInterval;

/**
 * Unit tests for {@link AdaptivePollInterval}.
 *
 * <p>These tests verify the adaptive polling behavior including:
 * <ul>
 *   <li>Initial state and configuration</li>
 *   <li>Backoff behavior after idle polls</li>
 *   <li>Reset behavior when work is found</li>
 *   <li>Thread safety under concurrent access</li>
 *   <li>Edge cases and boundary conditions</li>
 * </ul>
 *
 * @see AdaptivePollInterval
 */
public class AdaptivePollIntervalTest {

    // Test configuration constants
    private static final String TEST_MONITOR_NAME = "TestMonitor";
    private static final long BASE_INTERVAL_MS = 100;
    private static final long MAX_INTERVAL_MS = 1000;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final int IDLE_THRESHOLD = 2;

    private AdaptivePollInterval poller;

    @Before
    public void setUp() {
        poller = new AdaptivePollInterval(
            TEST_MONITOR_NAME,
            BASE_INTERVAL_MS,
            MAX_INTERVAL_MS,
            BACKOFF_MULTIPLIER,
            IDLE_THRESHOLD
        );
    }

    // ==========================================================================
    // Initialization Tests
    // ==========================================================================

    @Test
    public void testInitialState() {
        assertEquals("Initial interval should be base interval",
            BASE_INTERVAL_MS, poller.getCurrentInterval());
        assertEquals("Base interval getter should return configured value",
            BASE_INTERVAL_MS, poller.getBaseInterval());
        assertEquals("Max interval getter should return configured value",
            MAX_INTERVAL_MS, poller.getMaxInterval());
        assertEquals("Initial idle polls should be 0",
            0, poller.getConsecutiveIdlePolls());
        assertEquals("Initial total polls should be 0",
            0, poller.getTotalPolls());
        assertEquals("Initial total work items should be 0",
            0, poller.getTotalWorkItems());
        assertEquals("Initial backoff count should be 0",
            0, poller.getBackoffCount());
        assertFalse("Should not be backed off initially",
            poller.isBackedOff());
        assertFalse("Should not be at max backoff initially",
            poller.isAtMaxBackoff());
    }

    @Test
    public void testDefaultConstructor() {
        // Uses environment config - just verify it doesn't throw
        AdaptivePollInterval defaultPoller = new AdaptivePollInterval("DefaultTest");
        assertNotNull(defaultPoller);
        assertTrue("Base interval should be positive",
            defaultPoller.getBaseInterval() > 0);
        assertTrue("Max interval should be >= base interval",
            defaultPoller.getMaxInterval() >= defaultPoller.getBaseInterval());
    }

    // ==========================================================================
    // Parameter Validation Tests
    // ==========================================================================

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidBaseInterval_Zero() {
        new AdaptivePollInterval(TEST_MONITOR_NAME, 0, MAX_INTERVAL_MS, BACKOFF_MULTIPLIER, IDLE_THRESHOLD);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidBaseInterval_Negative() {
        new AdaptivePollInterval(TEST_MONITOR_NAME, -100, MAX_INTERVAL_MS, BACKOFF_MULTIPLIER, IDLE_THRESHOLD);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidMaxInterval_LessThanBase() {
        new AdaptivePollInterval(TEST_MONITOR_NAME, 1000, 500, BACKOFF_MULTIPLIER, IDLE_THRESHOLD);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidBackoffMultiplier_One() {
        new AdaptivePollInterval(TEST_MONITOR_NAME, BASE_INTERVAL_MS, MAX_INTERVAL_MS, 1.0, IDLE_THRESHOLD);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidBackoffMultiplier_LessThanOne() {
        new AdaptivePollInterval(TEST_MONITOR_NAME, BASE_INTERVAL_MS, MAX_INTERVAL_MS, 0.5, IDLE_THRESHOLD);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidIdleThreshold_Negative() {
        new AdaptivePollInterval(TEST_MONITOR_NAME, BASE_INTERVAL_MS, MAX_INTERVAL_MS, BACKOFF_MULTIPLIER, -1);
    }

    @Test
    public void testValidIdleThreshold_Zero() {
        // Threshold of 0 is valid - backoff starts immediately after first idle poll
        AdaptivePollInterval zeroThreshold = new AdaptivePollInterval(
            TEST_MONITOR_NAME, BASE_INTERVAL_MS, MAX_INTERVAL_MS, BACKOFF_MULTIPLIER, 0);
        assertNotNull(zeroThreshold);
    }

    // ==========================================================================
    // Idle Polling and Backoff Tests
    // ==========================================================================

    @Test
    public void testIdleCountIncrementsButNoBackoffBeforeThreshold() {
        // Record idle polls up to threshold - should not backoff yet
        for (int i = 1; i <= IDLE_THRESHOLD; i++) {
            poller.recordIdle();
            assertEquals("Idle count should increment", i, poller.getConsecutiveIdlePolls());
            assertEquals("Interval should remain at base before threshold exceeded",
                BASE_INTERVAL_MS, poller.getCurrentInterval());
        }
        assertFalse("Should not be backed off at threshold", poller.isBackedOff());
    }

    @Test
    public void testBackoffStartsAfterThresholdExceeded() {
        // Record idle polls past threshold
        for (int i = 0; i <= IDLE_THRESHOLD; i++) {
            poller.recordIdle();
        }
        
        long expectedInterval = (long)(BASE_INTERVAL_MS * BACKOFF_MULTIPLIER); // 200ms
        assertEquals("First backoff should double the interval",
            expectedInterval, poller.getCurrentInterval());
        assertTrue("Should be backed off", poller.isBackedOff());
        assertEquals("Backoff count should be 1", 1, poller.getBackoffCount());
    }

    @Test
    public void testProgressiveBackoff() {
        // First backoff: 100 -> 200
        for (int i = 0; i <= IDLE_THRESHOLD; i++) {
            poller.recordIdle();
        }
        assertEquals(200, poller.getCurrentInterval());
        
        // Second backoff: 200 -> 400
        poller.recordIdle();
        assertEquals(400, poller.getCurrentInterval());
        
        // Third backoff: 400 -> 800
        poller.recordIdle();
        assertEquals(800, poller.getCurrentInterval());
        
        // Fourth backoff: 800 -> 1000 (capped at max)
        poller.recordIdle();
        assertEquals(MAX_INTERVAL_MS, poller.getCurrentInterval());
        assertTrue("Should be at max backoff", poller.isAtMaxBackoff());
    }

    @Test
    public void testBackoffCapsAtMaxInterval() {
        // Record many idle polls
        for (int i = 0; i < 20; i++) {
            poller.recordIdle();
        }
        
        assertEquals("Interval should be capped at max",
            MAX_INTERVAL_MS, poller.getCurrentInterval());
        assertTrue("Should be at max backoff", poller.isAtMaxBackoff());
    }

    @Test
    public void testNoFurtherBackoffAtMax() {
        // Get to max
        for (int i = 0; i < 20; i++) {
            poller.recordIdle();
        }
        int backoffCountAtMax = poller.getBackoffCount();
        
        // Record more idle polls
        for (int i = 0; i < 5; i++) {
            poller.recordIdle();
        }
        
        // Backoff count should not increase when already at max
        assertEquals("Interval should remain at max", MAX_INTERVAL_MS, poller.getCurrentInterval());
        assertEquals("Backoff count should not increase at max",
            backoffCountAtMax, poller.getBackoffCount());
    }

    // ==========================================================================
    // Work Found and Reset Tests
    // ==========================================================================

    @Test
    public void testRecordWorkFoundResetsInterval() {
        // Get into backed off state
        for (int i = 0; i < 10; i++) {
            poller.recordIdle();
        }
        assertTrue("Should be backed off", poller.isBackedOff());
        
        // Record work found
        poller.recordWorkFound(5);
        
        assertEquals("Interval should reset to base", BASE_INTERVAL_MS, poller.getCurrentInterval());
        assertEquals("Idle count should reset to 0", 0, poller.getConsecutiveIdlePolls());
        assertFalse("Should no longer be backed off", poller.isBackedOff());
    }

    @Test
    public void testRecordWorkFoundUpdatesMetrics() {
        poller.recordWorkFound(10);
        poller.recordWorkFound(5);
        
        assertEquals("Total polls should be 2", 2, poller.getTotalPolls());
        assertEquals("Total work items should be 15", 15, poller.getTotalWorkItems());
    }

    @Test
    public void testResetToBase() {
        // Get into backed off state
        for (int i = 0; i < 10; i++) {
            poller.recordIdle();
        }
        
        poller.resetToBase();
        
        assertEquals("Interval should be base", BASE_INTERVAL_MS, poller.getCurrentInterval());
        assertEquals("Idle count should be 0", 0, poller.getConsecutiveIdlePolls());
    }

    @Test
    public void testResetToBaseFromBaseIntervalIsNoOp() {
        // Reset when already at base - should work fine
        long initialInterval = poller.getCurrentInterval();
        poller.resetToBase();
        assertEquals("Interval should remain at base", initialInterval, poller.getCurrentInterval());
    }

    // ==========================================================================
    // Metrics and Statistics Tests
    // ==========================================================================

    @Test
    public void testTotalPollsIncludeBothIdleAndWorkPolls() {
        poller.recordIdle();
        poller.recordIdle();
        poller.recordWorkFound(1);
        poller.recordIdle();
        poller.recordWorkFound(2);
        
        assertEquals("Total polls should include all poll types", 5, poller.getTotalPolls());
        assertEquals("Total work items should sum correctly", 3, poller.getTotalWorkItems());
    }

    @Test
    public void testGetStatsFormat() {
        String stats = poller.getStats();
        assertNotNull(stats);
        assertTrue("Stats should contain monitor name", stats.contains(TEST_MONITOR_NAME));
        assertTrue("Stats should contain 'current'", stats.contains("current="));
        assertTrue("Stats should contain 'base'", stats.contains("base="));
        assertTrue("Stats should contain 'max'", stats.contains("max="));
    }

    @Test
    public void testToStringMatchesGetStats() {
        assertEquals("toString should equal getStats", poller.getStats(), poller.toString());
    }

    // ==========================================================================
    // Edge Cases Tests
    // ==========================================================================

    @Test
    public void testMaxIntervalEqualsBaseInterval() {
        // When max = base, no backoff should occur
        AdaptivePollInterval noBackoff = new AdaptivePollInterval(
            TEST_MONITOR_NAME, 1000, 1000, 2.0, 2);
        
        for (int i = 0; i < 10; i++) {
            noBackoff.recordIdle();
        }
        
        assertEquals("Interval should remain at base/max",
            1000, noBackoff.getCurrentInterval());
    }

    @Test
    public void testHighBackoffMultiplier() {
        AdaptivePollInterval highMultiplier = new AdaptivePollInterval(
            TEST_MONITOR_NAME, 100, 10000, 10.0, 1);
        
        // Threshold = 1, so first backoff after 2 idle polls
        highMultiplier.recordIdle(); // idle = 1
        highMultiplier.recordIdle(); // idle = 2, backoff: 100 -> 1000
        
        assertEquals("High multiplier should jump significantly", 1000, highMultiplier.getCurrentInterval());
    }

    @Test
    public void testZeroWorkItems() {
        // Recording 0 work items should still reset interval
        for (int i = 0; i < 10; i++) {
            poller.recordIdle();
        }
        
        poller.recordWorkFound(0);
        
        assertEquals("Interval should reset even with 0 work items",
            BASE_INTERVAL_MS, poller.getCurrentInterval());
    }

    // ==========================================================================
    // Thread Safety Tests
    // ==========================================================================

    @Test
    public void testConcurrentRecordIdle() throws InterruptedException {
        int numThreads = 4;
        int iterationsPerThread = 25;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < iterationsPerThread; i++) {
                        poller.recordIdle();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue("All threads should complete", latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // Total polls should equal all iterations
        assertEquals("Total polls should be thread-safe",
            numThreads * iterationsPerThread, poller.getTotalPolls());
    }

    @Test
    public void testConcurrentMixedOperations() throws InterruptedException {
        int numThreads = 6;
        int iterationsPerThread = 25;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger totalExpectedWork = new AtomicInteger(0);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < iterationsPerThread; i++) {
                        if (threadId % 3 == 0) {
                            poller.recordIdle();
                        } else if (threadId % 3 == 1) {
                            poller.recordWorkFound(1);
                            totalExpectedWork.incrementAndGet();
                        } else {
                            poller.resetToBase();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue("All threads should complete", latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // Verify no exceptions occurred and state is consistent
        assertTrue("Interval should be within bounds",
            poller.getCurrentInterval() >= BASE_INTERVAL_MS &&
            poller.getCurrentInterval() <= MAX_INTERVAL_MS);
        
        // Threads 0, 3 do recordIdle (2 threads * 25 = 50 idle polls that count)
        // Threads 1, 4 do recordWorkFound (2 threads * 25 = 50 work polls)
        // Threads 2, 5 do resetToBase (doesn't affect poll count)
        // Total = 50 + 50 = 100 polls
        assertEquals("Total polls should match expected",
            100, poller.getTotalPolls());
        assertEquals("Total work items should match expected",
            totalExpectedWork.get(), poller.getTotalWorkItems());
    }

    @Test
    public void testConcurrentResetDuringBackoff() throws InterruptedException {
        int numThreads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 25; i++) {
                        if (threadId % 2 == 0) {
                            poller.recordIdle();
                        } else {
                            poller.resetToBase();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue("All threads should complete", latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // State should be consistent - interval within bounds
        assertTrue("Interval should be within bounds",
            poller.getCurrentInterval() >= BASE_INTERVAL_MS &&
            poller.getCurrentInterval() <= MAX_INTERVAL_MS);
    }

    // ==========================================================================
    // Integration-Style Tests
    // ==========================================================================

    @Test
    public void testTypicalPollingCycle() {
        // Simulate a typical polling scenario
        
        // Initial work burst
        for (int i = 0; i < 5; i++) {
            poller.recordWorkFound(3);
        }
        assertEquals("Should stay at base during work", BASE_INTERVAL_MS, poller.getCurrentInterval());
        assertEquals("Should have processed 15 work items", 15, poller.getTotalWorkItems());
        
        // Work dries up, go idle
        for (int i = 0; i <= IDLE_THRESHOLD; i++) {
            poller.recordIdle();
        }
        assertTrue("Should back off after idle threshold", poller.isBackedOff());
        
        // More idle time
        poller.recordIdle();
        poller.recordIdle();
        long backedOffInterval = poller.getCurrentInterval();
        assertTrue("Should be significantly backed off", backedOffInterval > BASE_INTERVAL_MS);
        
        // New work arrives - external trigger
        poller.resetToBase();
        assertEquals("Should reset to base for new work", BASE_INTERVAL_MS, poller.getCurrentInterval());
        
        // Process the new work
        poller.recordWorkFound(10);
        assertEquals("Should stay at base", BASE_INTERVAL_MS, poller.getCurrentInterval());
        assertEquals("Total work should be 25", 25, poller.getTotalWorkItems());
    }

    @Test
    public void testBackoffRecoveryMultipleTimes() {
        // Test that backoff/recovery works correctly through multiple cycles
        for (int cycle = 0; cycle < 3; cycle++) {
            // Go to max backoff
            for (int i = 0; i < 15; i++) {
                poller.recordIdle();
            }
            assertTrue("Should be at max backoff in cycle " + cycle, poller.isAtMaxBackoff());
            
            // Recover
            poller.recordWorkFound(1);
            assertEquals("Should recover to base in cycle " + cycle,
                BASE_INTERVAL_MS, poller.getCurrentInterval());
            assertFalse("Should not be backed off after recovery", poller.isBackedOff());
        }
    }
}
