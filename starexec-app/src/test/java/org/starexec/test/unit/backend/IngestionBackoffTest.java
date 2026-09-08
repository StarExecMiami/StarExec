package org.starexec.test.unit.backend;

import org.junit.Test;
import org.starexec.backend.LocalJobMonitor;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The timing layer on its own, with no database in the way.
 *
 * <p>Worth isolating because the first version of this was wrong in a way the integration
 * tests could only show indirectly: the delay doubled past 512 seconds and never capped,
 * because the ceiling was "ten times the poller's maximum interval" and that maximum defaults
 * to 120000ms, not the 10000ms {@code AdaptivePollInterval}'s own javadoc claims. Arithmetic
 * that is only exercised through a DB test is arithmetic nobody checks.
 */
public class IngestionBackoffTest {

	private static long delayFor(int failures) throws Exception {
		LocalJobMonitor monitor = new LocalJobMonitor();
		Method m = LocalJobMonitor.class.getDeclaredMethod("ingestionBackoffMillis", int.class);
		m.setAccessible(true);
		return (Long) m.invoke(monitor, failures);
	}

	private static long cap() throws Exception {
		// Whatever the configured ceiling resolves to; asserted against, not assumed.
		return delayFor(Integer.MAX_VALUE);
	}

	/** The first failure waits one poll cycle, not zero and not a full cap. */
	@Test
	public void theFirstFailureWaitsTheBaseInterval() throws Exception {
		long first = delayFor(1);
		assertTrue("first delay must be positive, was " + first, first > 0);
		assertTrue("and must be well below the cap, was " + first, first < cap());
	}

	/** Growth is monotonic until it caps, and never decreases. */
	@Test
	public void delayGrowsMonotonicallyThenHoldsAtTheCap() throws Exception {
		long cap = cap();
		long previous = 0;
		boolean reachedCap = false;

		for (int failures = 1; failures <= 25; failures++) {
			long delay = delayFor(failures);
			assertTrue("delay must never decrease at " + failures
					+ ": " + previous + " -> " + delay, delay >= previous);
			assertTrue("delay must never exceed the cap at " + failures + ": " + delay,
					delay <= cap);
			if (delay == cap) {
				reachedCap = true;
			}
			previous = delay;
		}

		assertTrue("the cap must actually be reachable, not theoretical", reachedCap);
	}

	/**
	 * A shift distance is masked to {@code n & 63} in Java, so an unclamped failure count
	 * would not error -- it would silently produce an enormous or negative delay.
	 */
	@Test
	public void anAbsurdFailureCountCannotOverflowOrWrap() throws Exception {
		for (int failures : new int[]{63, 64, 65, 1000, 100000, Integer.MAX_VALUE}) {
			long delay = delayFor(failures);
			assertTrue("delay must stay positive at " + failures + ", was " + delay, delay > 0);
			assertEquals("and must sit exactly at the cap at " + failures, cap(), delay);
		}
	}

	/** A zero or negative count is nonsensical but must still yield a sane delay. */
	@Test
	public void aNonPositiveFailureCountDegradesToTheBaseDelay() throws Exception {
		long base = delayFor(1);
		assertEquals(base, delayFor(0));
		assertEquals(base, delayFor(-5));
		assertEquals(base, delayFor(Integer.MIN_VALUE));
	}
}
