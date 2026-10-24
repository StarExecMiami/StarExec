package org.starexec.jobs;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.starexec.jobs.QueueConditions.Kind;

/**
 * When a condition that keeps a queue from dispatching is due a report: when it starts, every
 * {@link QueueConditions#REPEAT_MILLIS} while it lasts, and its end once.
 */
public class QueueConditionsTest {

    private static final long T0 = 1_000_000L;
    private static final long PASS = 20_000L;
    private static final long MINUTE = 60_000L;
    private static final long REPEAT = QueueConditions.REPEAT_MILLIS;

    @Test
    public void aConditionIsReportedWhenItStartsAndNotOnTheNextPass() {
        QueueConditions conditions = new QueueConditions();
        assertEquals(0, conditions.report(1, Kind.NO_NODES, "a", T0));
        assertEquals(-1, conditions.report(1, Kind.NO_NODES, "a", T0 + PASS));
    }

    @Test
    public void aConditionThatLastsIsReportedAgainEveryRepeatInterval() {
        QueueConditions conditions = new QueueConditions();
        conditions.report(1, Kind.CANNOT_ACCEPT_WORK, "a", T0);
        assertEquals("just before the interval",
            -1, conditions.report(1, Kind.CANNOT_ACCEPT_WORK, "a", T0 + REPEAT - 1));
        assertEquals("at the interval, with the minutes it has lasted",
            360, conditions.report(1, Kind.CANNOT_ACCEPT_WORK, "a", T0 + REPEAT));
        assertEquals("the pass after that",
            -1, conditions.report(1, Kind.CANNOT_ACCEPT_WORK, "a", T0 + REPEAT + PASS));
        assertEquals("the interval counts from the last report",
            -1, conditions.report(1, Kind.CANNOT_ACCEPT_WORK, "a", T0 + 2 * REPEAT - 1));
        assertEquals(720, conditions.report(1, Kind.CANNOT_ACCEPT_WORK, "a", T0 + 2 * REPEAT));
    }

    @Test
    public void aNewCauseIsReportedAtOnceAndItsDurationStartsThen() {
        QueueConditions conditions = new QueueConditions();
        conditions.report(1, Kind.NO_NODES, "a", T0);
        assertEquals(0, conditions.report(1, Kind.NO_NODES, "b", T0 + MINUTE));
        assertEquals(-1, conditions.report(1, Kind.NO_NODES, "b", T0 + 2 * MINUTE));
        assertEquals(4, conditions.end(1, Kind.NO_NODES, T0 + 5 * MINUTE));
    }

    @Test
    public void theEndIsReportedOnceWithTheMinutesItLasted() {
        QueueConditions conditions = new QueueConditions();
        conditions.report(1, Kind.NO_NODES, "a", T0);
        assertEquals(5, conditions.end(1, Kind.NO_NODES, T0 + 5 * MINUTE));
        assertEquals("it has ended already", -1, conditions.end(1, Kind.NO_NODES, T0 + 6 * MINUTE));
    }

    @Test
    public void aConditionNeverReportedHasNoEnd() {
        assertEquals(-1, new QueueConditions().end(1, Kind.NO_NODES, T0));
    }

    @Test
    public void aConditionThatComesBackAfterItsEndIsReportedAtOnce() {
        QueueConditions conditions = new QueueConditions();
        conditions.report(1, Kind.NO_NODES, "a", T0);
        conditions.end(1, Kind.NO_NODES, T0 + MINUTE);
        assertEquals(0, conditions.report(1, Kind.NO_NODES, "a", T0 + 2 * MINUTE));
    }

    @Test
    public void queuesAndKindsAreKeptApart() {
        QueueConditions conditions = new QueueConditions();
        assertEquals(0, conditions.report(1, Kind.NO_NODES, "a", T0));
        assertEquals("another queue", 0, conditions.report(2, Kind.NO_NODES, "a", T0));
        assertEquals("another kind", 0, conditions.report(1, Kind.CANNOT_ACCEPT_WORK, "a", T0));
        assertEquals("ending one kind", 3, conditions.end(1, Kind.NO_NODES, T0 + 3 * MINUTE));
        assertEquals("leaves the other", -1, conditions.report(1, Kind.CANNOT_ACCEPT_WORK, "a", T0 + 4 * MINUTE));
        assertEquals("and the other queue", -1, conditions.report(2, Kind.NO_NODES, "a", T0 + 4 * MINUTE));
    }
}
