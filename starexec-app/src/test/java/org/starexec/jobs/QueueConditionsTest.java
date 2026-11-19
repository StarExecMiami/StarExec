package org.starexec.jobs;

import org.junit.jupiter.api.Test;
import org.starexec.jobs.QueueConditions.Kind;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(-1,
            conditions.report(1, Kind.CANNOT_ACCEPT_WORK, "a", T0 + REPEAT - 1), "just before the interval");
        assertEquals(360,
            conditions.report(1, Kind.CANNOT_ACCEPT_WORK, "a", T0 + REPEAT), "at the interval, with the minutes it has lasted");
        assertEquals(-1,
            conditions.report(1, Kind.CANNOT_ACCEPT_WORK, "a", T0 + REPEAT + PASS), "the pass after that");
        assertEquals(-1,
            conditions.report(1, Kind.CANNOT_ACCEPT_WORK, "a", T0 + 2 * REPEAT - 1), "the interval counts from the last report");
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
        assertEquals(-1, conditions.end(1, Kind.NO_NODES, T0 + 6 * MINUTE), "it has ended already");
    }

    @Test
    public void aConditionNeverReportedHasNoEnd() {
        assertEquals(-1, new QueueConditions().end(1, Kind.NO_NODES, T0));
    }

    /**
     * The wall clock can step back while a condition lasts. Its end is still reported, as lasting
     * 0 minutes: a negative count would read as no condition, and the end would not be logged.
     */
    @Test
    public void anEndAfterTheClockSteppedBackIsStillReported() {
        QueueConditions conditions = new QueueConditions();
        conditions.report(1, Kind.CANNOT_ACCEPT_WORK, "a", T0 + 10 * MINUTE);
        assertEquals(0, conditions.end(1, Kind.CANNOT_ACCEPT_WORK, T0 + 3 * MINUTE));
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
        assertEquals(0, conditions.report(2, Kind.NO_NODES, "a", T0), "another queue");
        assertEquals(0, conditions.report(1, Kind.CANNOT_ACCEPT_WORK, "a", T0), "another kind");
        assertEquals(3, conditions.end(1, Kind.NO_NODES, T0 + 3 * MINUTE), "ending one kind");
        assertEquals(-1, conditions.report(1, Kind.CANNOT_ACCEPT_WORK, "a", T0 + 4 * MINUTE), "leaves the other");
        assertEquals(-1, conditions.report(2, Kind.NO_NODES, "a", T0 + 4 * MINUTE), "and the other queue");
    }
}
