package org.starexec.test.junit.backend;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    @BeforeEach
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
        assertEquals(BASE_INTERVAL_MS,
            poller.getCurrentInterval(), "Initial interval should be base interval");
        assertEquals(BASE_INTERVAL_MS,
            poller.getBaseInterval(), "Base interval getter should return configured value");
        assertEquals(MAX_INTERVAL_MS,
            poller.getMaxInterval(), "Max interval getter should return configured value");
        assertEquals(0,
            poller.getConsecutiveIdlePolls(), "Initial idle polls should be 0");
        assertEquals(0,
            poller.getTotalPolls(), "Initial total polls should be 0");
        assertEquals(0,
            poller.getTotalWorkItems(), "Initial total work items should be 0");
        assertEquals(0,
            poller.getBackoffCount(), "Initial backoff count should be 0");
        assertFalse(poller.isBackedOff(),
            "Should not be backed off initially");
        assertFalse(poller.isAtMaxBackoff(),
            "Should not be at max backoff initially");
    }

    @Test
    public void testDefaultConstructor() {
        // Uses environment config - just verify it doesn't throw
        AdaptivePollInterval defaultPoller = new AdaptivePollInterval("DefaultTest");
        assertNotNull(defaultPoller);
        assertTrue(defaultPoller.getBaseInterval() > 0,
            "Base interval should be positive");
        assertTrue(defaultPoller.getMaxInterval() >= defaultPoller.getBaseInterval(),
            "Max interval should be >= base interval");
    }

    // ==========================================================================
    // Parameter Validation Tests
    // ==========================================================================

    @Test
    public void testInvalidBaseInterval_Zero() {
        assertThrows(IllegalArgumentException.class, () -> {
            new AdaptivePollInterval(TEST_MONITOR_NAME, 0, MAX_INTERVAL_MS, BACKOFF_MULTIPLIER, IDLE_THRESHOLD);
        });
    }

    @Test
    public void testInvalidBaseInterval_Negative() {
        assertThrows(IllegalArgumentException.class, () -> {
            new AdaptivePollInterval(TEST_MONITOR_NAME, -100, MAX_INTERVAL_MS, BACKOFF_MULTIPLIER, IDLE_THRESHOLD);
        });
    }

    @Test
    public void testInvalidMaxInterval_LessThanBase() {
        assertThrows(IllegalArgumentException.class, () -> {
            new AdaptivePollInterval(TEST_MONITOR_NAME, 1000, 500, BACKOFF_MULTIPLIER, IDLE_THRESHOLD);
        });
    }

    @Test
    public void testInvalidBackoffMultiplier_One() {
        assertThrows(IllegalArgumentException.class, () -> {
            new AdaptivePollInterval(TEST_MONITOR_NAME, BASE_INTERVAL_MS, MAX_INTERVAL_MS, 1.0, IDLE_THRESHOLD);
        });
    }

    @Test
    public void testInvalidBackoffMultiplier_LessThanOne() {
        assertThrows(IllegalArgumentException.class, () -> {
            new AdaptivePollInterval(TEST_MONITOR_NAME, BASE_INTERVAL_MS, MAX_INTERVAL_MS, 0.5, IDLE_THRESHOLD);
        });
    }

    @Test
    public void testInvalidIdleThreshold_Negative() {
        assertThrows(IllegalArgumentException.class, () -> {
            new AdaptivePollInterval(TEST_MONITOR_NAME, BASE_INTERVAL_MS, MAX_INTERVAL_MS, BACKOFF_MULTIPLIER, -1);
        });
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
            assertEquals(i, poller.getConsecutiveIdlePolls(), "Idle count should increment");
            assertEquals(BASE_INTERVAL_MS,
                poller.getCurrentInterval(), "Interval should remain at base before threshold exceeded");
        }
        assertFalse(poller.isBackedOff(), "Should not be backed off at threshold");
    }

    @Test
    public void testBackoffStartsAfterThresholdExceeded() {
        // Record idle polls past threshold
        for (int i = 0; i <= IDLE_THRESHOLD; i++) {
            poller.recordIdle();
        }
        
        long expectedInterval = (long)(BASE_INTERVAL_MS * BACKOFF_MULTIPLIER); // 200ms
        assertEquals(expectedInterval,
            poller.getCurrentInterval(), "First backoff should double the interval");
        assertTrue(poller.isBackedOff(), "Should be backed off");
        assertEquals(1, poller.getBackoffCount(), "Backoff count should be 1");
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
        assertTrue(poller.isAtMaxBackoff(), "Should be at max backoff");
    }

    @Test
    public void testBackoffCapsAtMaxInterval() {
        // Record many idle polls
        for (int i = 0; i < 20; i++) {
            poller.recordIdle();
        }
        
        assertEquals(MAX_INTERVAL_MS,
            poller.getCurrentInterval(), "Interval should be capped at max");
        assertTrue(poller.isAtMaxBackoff(), "Should be at max backoff");
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
        assertEquals(MAX_INTERVAL_MS, poller.getCurrentInterval(), "Interval should remain at max");
        assertEquals(backoffCountAtMax,
            poller.getBackoffCount(), "Backoff count should not increase at max");
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
        assertTrue(poller.isBackedOff(), "Should be backed off");
        
        // Record work found
        poller.recordWorkFound(5);
        
        assertEquals(BASE_INTERVAL_MS, poller.getCurrentInterval(), "Interval should reset to base");
        assertEquals(0, poller.getConsecutiveIdlePolls(), "Idle count should reset to 0");
        assertFalse(poller.isBackedOff(), "Should no longer be backed off");
    }

    @Test
    public void testRecordWorkFoundUpdatesMetrics() {
        poller.recordWorkFound(10);
        poller.recordWorkFound(5);
        
        assertEquals(2, poller.getTotalPolls(), "Total polls should be 2");
        assertEquals(15, poller.getTotalWorkItems(), "Total work items should be 15");
    }

    @Test
    public void testResetToBase() {
        // Get into backed off state
        for (int i = 0; i < 10; i++) {
            poller.recordIdle();
        }
        
        poller.resetToBase();
        
        assertEquals(BASE_INTERVAL_MS, poller.getCurrentInterval(), "Interval should be base");
        assertEquals(0, poller.getConsecutiveIdlePolls(), "Idle count should be 0");
    }

    @Test
    public void testResetToBaseFromBaseIntervalIsNoOp() {
        // Reset when already at base - should work fine
        long initialInterval = poller.getCurrentInterval();
        poller.resetToBase();
        assertEquals(initialInterval, poller.getCurrentInterval(), "Interval should remain at base");
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
        
        assertEquals(5, poller.getTotalPolls(), "Total polls should include all poll types");
        assertEquals(3, poller.getTotalWorkItems(), "Total work items should sum correctly");
    }

    @Test
    public void testGetStatsFormat() {
        String stats = poller.getStats();
        assertNotNull(stats);
        assertTrue(stats.contains(TEST_MONITOR_NAME), "Stats should contain monitor name");
        assertTrue(stats.contains("current="), "Stats should contain 'current'");
        assertTrue(stats.contains("base="), "Stats should contain 'base'");
        assertTrue(stats.contains("max="), "Stats should contain 'max'");
    }

    @Test
    public void testToStringMatchesGetStats() {
        assertEquals(poller.getStats(), poller.toString(), "toString should equal getStats");
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
        
        assertEquals(1000,
            noBackoff.getCurrentInterval(), "Interval should remain at base/max");
    }

    @Test
    public void testHighBackoffMultiplier() {
        AdaptivePollInterval highMultiplier = new AdaptivePollInterval(
            TEST_MONITOR_NAME, 100, 10000, 10.0, 1);
        
        // Threshold = 1, so first backoff after 2 idle polls
        highMultiplier.recordIdle(); // idle = 1
        highMultiplier.recordIdle(); // idle = 2, backoff: 100 -> 1000
        
        assertEquals(1000, highMultiplier.getCurrentInterval(), "High multiplier should jump significantly");
    }

    @Test
    public void testZeroWorkItems() {
        // Recording 0 work items should still reset interval
        for (int i = 0; i < 10; i++) {
            poller.recordIdle();
        }
        
        poller.recordWorkFound(0);
        
        assertEquals(BASE_INTERVAL_MS,
            poller.getCurrentInterval(), "Interval should reset even with 0 work items");
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
        
        assertTrue(latch.await(10, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // Total polls should equal all iterations
        assertEquals(numThreads * iterationsPerThread,
            poller.getTotalPolls(), "Total polls should be thread-safe");
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
        
        assertTrue(latch.await(10, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // Verify no exceptions occurred and state is consistent
        assertTrue(poller.getCurrentInterval() >= BASE_INTERVAL_MS &&
            poller.getCurrentInterval() <= MAX_INTERVAL_MS,
            "Interval should be within bounds");
        
        // Threads 0, 3 do recordIdle (2 threads * 25 = 50 idle polls that count)
        // Threads 1, 4 do recordWorkFound (2 threads * 25 = 50 work polls)
        // Threads 2, 5 do resetToBase (doesn't affect poll count)
        // Total = 50 + 50 = 100 polls
        assertEquals(100,
            poller.getTotalPolls(), "Total polls should match expected");
        assertEquals(totalExpectedWork.get(),
            poller.getTotalWorkItems(), "Total work items should match expected");
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
        
        assertTrue(latch.await(10, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // State should be consistent - interval within bounds
        assertTrue(poller.getCurrentInterval() >= BASE_INTERVAL_MS &&
            poller.getCurrentInterval() <= MAX_INTERVAL_MS,
            "Interval should be within bounds");
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
        assertEquals(BASE_INTERVAL_MS, poller.getCurrentInterval(), "Should stay at base during work");
        assertEquals(15, poller.getTotalWorkItems(), "Should have processed 15 work items");
        
        // Work dries up, go idle
        for (int i = 0; i <= IDLE_THRESHOLD; i++) {
            poller.recordIdle();
        }
        assertTrue(poller.isBackedOff(), "Should back off after idle threshold");
        
        // More idle time
        poller.recordIdle();
        poller.recordIdle();
        long backedOffInterval = poller.getCurrentInterval();
        assertTrue(backedOffInterval > BASE_INTERVAL_MS, "Should be significantly backed off");
        
        // New work arrives - external trigger
        poller.resetToBase();
        assertEquals(BASE_INTERVAL_MS, poller.getCurrentInterval(), "Should reset to base for new work");
        
        // Process the new work
        poller.recordWorkFound(10);
        assertEquals(BASE_INTERVAL_MS, poller.getCurrentInterval(), "Should stay at base");
        assertEquals(25, poller.getTotalWorkItems(), "Total work should be 25");
    }

    @Test
    public void testBackoffRecoveryMultipleTimes() {
        // Test that backoff/recovery works correctly through multiple cycles
        for (int cycle = 0; cycle < 3; cycle++) {
            // Go to max backoff
            for (int i = 0; i < 15; i++) {
                poller.recordIdle();
            }
            assertTrue(poller.isAtMaxBackoff(), "Should be at max backoff in cycle " + cycle);
            
            // Recover
            poller.recordWorkFound(1);
            assertEquals(BASE_INTERVAL_MS,
                poller.getCurrentInterval(), "Should recover to base in cycle " + cycle);
            assertFalse(poller.isBackedOff(), "Should not be backed off after recovery");
        }
    }
}
