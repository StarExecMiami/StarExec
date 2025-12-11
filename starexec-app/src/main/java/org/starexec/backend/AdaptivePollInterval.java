package org.starexec.backend;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.starexec.config.EnvironmentConfig;
import org.starexec.logger.StarLogger;

/**
 * Utility class for adaptive polling interval management.
 *
 * <p>This class provides adaptive polling logic that reduces CPU usage during idle
 * periods by gradually increasing the poll interval when no work is found, and
 * immediately resetting to the base interval when new work arrives.</p>
 *
 * <h2>Behavior</h2>
 * <ul>
 *   <li>Starts at base interval (default: 1000ms)</li>
 *   <li>After idle threshold consecutive idle polls, starts backing off</li>
 *   <li>Each backoff multiplies interval by backoff multiplier (default: 1.5)</li>
 *   <li>Caps at max interval (default: 10000ms)</li>
 *   <li>Resets to base interval immediately when {@link #resetToBase()} is called</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <p>All parameters are configurable via environment variables:</p>
 * <ul>
 *   <li>{@code STAREXEC_POLL_BASE_INTERVAL_MS} - Base/minimum interval (default: 1000)</li>
 *   <li>{@code STAREXEC_POLL_MAX_INTERVAL_MS} - Maximum interval (default: 10000)</li>
 *   <li>{@code STAREXEC_POLL_BACKOFF_MULTIPLIER} - Backoff multiplier (default: 1.5)</li>
 *   <li>{@code STAREXEC_POLL_IDLE_THRESHOLD} - Idle polls before backoff (default: 3)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All state modifications use atomic operations.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * AdaptivePollInterval poller = new AdaptivePollInterval("MyMonitor");
 *
 * // In your polling loop:
 * void doPoll() {
 *     int workFound = checkForWork();
 *     if (workFound > 0) {
 *         poller.recordWorkFound(workFound);
 *     } else {
 *         poller.recordIdle();
 *     }
 *     // Schedule next poll
 *     scheduler.schedule(this::doPoll, poller.getCurrentInterval(), TimeUnit.MILLISECONDS);
 * }
 *
 * // When new jobs are registered:
 * void registerJob(...) {
 *     // ... registration logic ...
 *     poller.resetToBase();  // Ensure responsive polling for new work
 * }
 * }</pre>
 *
 * @author StarExec Team
 * @see org.starexec.backend.LocalJobMonitor
 * @see org.starexec.backend.ContainerJobMonitor
 */
public class AdaptivePollInterval {

    private static final StarLogger log = StarLogger.getLogger(
        AdaptivePollInterval.class
    );

    // Configuration (immutable after construction)
    private final String monitorName;
    private final long baseIntervalMs;
    private final long maxIntervalMs;
    private final double backoffMultiplier;
    private final int idleThreshold;

    // State (thread-safe via atomics)
    private final AtomicLong currentIntervalMs;
    private final AtomicInteger consecutiveIdlePolls;
    private final AtomicLong totalPolls;
    private final AtomicLong totalWorkItems;
    private final AtomicInteger backoffCount;

    /**
     * Creates an AdaptivePollInterval with default configuration from environment variables.
     *
     * @param monitorName Name for logging purposes (e.g., "LocalJobMonitor", "ContainerJobMonitor")
     */
    public AdaptivePollInterval(String monitorName) {
        this(
            monitorName,
            EnvironmentConfig.getAdaptivePollBaseInterval(),
            EnvironmentConfig.getAdaptivePollMaxInterval(),
            EnvironmentConfig.getAdaptivePollBackoffMultiplier(),
            EnvironmentConfig.getAdaptivePollIdleThreshold()
        );
    }

    /**
     * Creates an AdaptivePollInterval with explicit configuration.
     * Primarily for testing; production code should use {@link #AdaptivePollInterval(String)}.
     *
     * @param monitorName       Name for logging purposes
     * @param baseIntervalMs    Base (minimum) polling interval in milliseconds
     * @param maxIntervalMs     Maximum polling interval in milliseconds
     * @param backoffMultiplier Multiplier applied on each backoff (e.g., 1.5)
     * @param idleThreshold     Number of consecutive idle polls before backoff starts
     */
    public AdaptivePollInterval(
        String monitorName,
        long baseIntervalMs,
        long maxIntervalMs,
        double backoffMultiplier,
        int idleThreshold
    ) {
        // Validate parameters
        if (baseIntervalMs <= 0) {
            throw new IllegalArgumentException(
                "baseIntervalMs must be positive: " + baseIntervalMs
            );
        }
        if (maxIntervalMs < baseIntervalMs) {
            throw new IllegalArgumentException(
                "maxIntervalMs (" +
                    maxIntervalMs +
                    ") must be >= baseIntervalMs (" +
                    baseIntervalMs +
                    ")"
            );
        }
        if (backoffMultiplier <= 1.0) {
            throw new IllegalArgumentException(
                "backoffMultiplier must be > 1.0: " + backoffMultiplier
            );
        }
        if (idleThreshold < 0) {
            throw new IllegalArgumentException(
                "idleThreshold must be non-negative: " + idleThreshold
            );
        }

        this.monitorName = monitorName;
        this.baseIntervalMs = baseIntervalMs;
        this.maxIntervalMs = maxIntervalMs;
        this.backoffMultiplier = backoffMultiplier;
        this.idleThreshold = idleThreshold;

        // Initialize state
        this.currentIntervalMs = new AtomicLong(baseIntervalMs);
        this.consecutiveIdlePolls = new AtomicInteger(0);
        this.totalPolls = new AtomicLong(0);
        this.totalWorkItems = new AtomicLong(0);
        this.backoffCount = new AtomicInteger(0);

        log.info(
            String.format(
                "[%s] AdaptivePollInterval initialized: base=%dms, max=%dms, multiplier=%.2f, idleThreshold=%d",
                monitorName,
                baseIntervalMs,
                maxIntervalMs,
                backoffMultiplier,
                idleThreshold
            )
        );
    }

    /**
     * Returns the current polling interval in milliseconds.
     * Use this value when scheduling the next poll.
     *
     * @return Current interval in milliseconds
     */
    public long getCurrentInterval() {
        return currentIntervalMs.get();
    }

    /**
     * Returns the base (minimum) interval in milliseconds.
     *
     * @return Base interval in milliseconds
     */
    public long getBaseInterval() {
        return baseIntervalMs;
    }

    /**
     * Returns the maximum interval in milliseconds.
     *
     * @return Maximum interval in milliseconds
     */
    public long getMaxInterval() {
        return maxIntervalMs;
    }

    /**
     * Records that work was found during the last poll.
     * Resets the interval to base and clears the idle counter.
     *
     * @param workItemCount Number of work items found (for metrics)
     */
    public void recordWorkFound(int workItemCount) {
        totalPolls.incrementAndGet();
        totalWorkItems.addAndGet(workItemCount);

        long oldInterval = currentIntervalMs.get();
        int oldIdleCount = consecutiveIdlePolls.getAndSet(0);

        if (oldInterval != baseIntervalMs) {
            currentIntervalMs.set(baseIntervalMs);
            log.debug(
                String.format(
                    "[%s] Work found (%d items), reset interval: %dms -> %dms (was idle for %d polls)",
                    monitorName,
                    workItemCount,
                    oldInterval,
                    baseIntervalMs,
                    oldIdleCount
                )
            );
        }
    }

    /**
     * Records that no work was found during the last poll.
     * Increments idle counter and potentially increases the interval.
     *
     * <p>This method is designed to never throw exceptions. If an error occurs
     * during interval calculation (e.g., overflow), it falls back to the base interval.</p>
     */
    public void recordIdle() {
        totalPolls.incrementAndGet();
        int idleCount = consecutiveIdlePolls.incrementAndGet();

        if (idleCount > idleThreshold) {
            long oldInterval = currentIntervalMs.get();
            if (oldInterval < maxIntervalMs) {
                try {
                    // Calculate new interval with backoff, with overflow protection
                    double rawNewInterval = oldInterval * backoffMultiplier;

                    // Guard against overflow or invalid values
                    long newInterval;
                    if (
                        Double.isNaN(rawNewInterval) ||
                        Double.isInfinite(rawNewInterval) ||
                        rawNewInterval < 0
                    ) {
                        // Fallback to max on calculation error
                        log.warn(
                            String.format(
                                "[%s] Backoff calculation produced invalid value (%.2f), falling back to max interval",
                                monitorName,
                                rawNewInterval
                            )
                        );
                        newInterval = maxIntervalMs;
                    } else if (rawNewInterval > Long.MAX_VALUE) {
                        // Overflow protection
                        newInterval = maxIntervalMs;
                    } else {
                        newInterval = Math.min(
                            (long) rawNewInterval,
                            maxIntervalMs
                        );
                    }

                    // Atomic compare-and-set to avoid race conditions
                    if (
                        currentIntervalMs.compareAndSet(
                            oldInterval,
                            newInterval
                        )
                    ) {
                        backoffCount.incrementAndGet();
                        log.debug(
                            String.format(
                                "[%s] Idle for %d polls (threshold=%d), backoff: %dms -> %dms",
                                monitorName,
                                idleCount,
                                idleThreshold,
                                oldInterval,
                                newInterval
                            )
                        );
                    }
                } catch (Exception e) {
                    // Defensive catch-all: if anything goes wrong, fall back to base interval
                    // This ensures the polling chain never breaks due to calculation errors
                    log.warn(
                        String.format(
                            "[%s] Error during backoff calculation, falling back to base interval: %s",
                            monitorName,
                            e.getMessage()
                        )
                    );
                    currentIntervalMs.set(baseIntervalMs);
                }
            }
        }
    }

    /**
     * Resets the interval to base immediately.
     * Call this when new work is registered to ensure responsive polling.
     */
    public void resetToBase() {
        long oldInterval = currentIntervalMs.getAndSet(baseIntervalMs);
        consecutiveIdlePolls.set(0);

        if (oldInterval != baseIntervalMs) {
            log.debug(
                String.format(
                    "[%s] Reset to base interval: %dms -> %dms",
                    monitorName,
                    oldInterval,
                    baseIntervalMs
                )
            );
        }
    }

    /**
     * Returns whether the poller is currently in a backed-off state.
     *
     * @return true if current interval is above base interval
     */
    public boolean isBackedOff() {
        return currentIntervalMs.get() > baseIntervalMs;
    }

    /**
     * Returns whether the poller is at maximum backoff.
     *
     * @return true if current interval equals max interval
     */
    public boolean isAtMaxBackoff() {
        return currentIntervalMs.get() >= maxIntervalMs;
    }

    /**
     * Returns the number of consecutive idle polls.
     *
     * @return Consecutive idle poll count
     */
    public int getConsecutiveIdlePolls() {
        return consecutiveIdlePolls.get();
    }

    /**
     * Returns statistics about this poller for monitoring/logging.
     *
     * @return Formatted statistics string
     */
    public String getStats() {
        return String.format(
            "%s{current=%dms, base=%dms, max=%dms, idle=%d/%d, polls=%d, workItems=%d, backoffs=%d}",
            monitorName,
            currentIntervalMs.get(),
            baseIntervalMs,
            maxIntervalMs,
            consecutiveIdlePolls.get(),
            idleThreshold,
            totalPolls.get(),
            totalWorkItems.get(),
            backoffCount.get()
        );
    }

    /**
     * Returns the total number of polls executed.
     *
     * @return Total poll count
     */
    public long getTotalPolls() {
        return totalPolls.get();
    }

    /**
     * Returns the total number of work items processed.
     *
     * @return Total work item count
     */
    public long getTotalWorkItems() {
        return totalWorkItems.get();
    }

    /**
     * Returns the number of times backoff has occurred.
     *
     * @return Backoff count
     */
    public int getBackoffCount() {
        return backoffCount.get();
    }

    @Override
    public String toString() {
        return getStats();
    }
}
