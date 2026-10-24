package org.starexec.jobs;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * When the dispatcher reports a condition that keeps a queue from dispatching.
 *
 * <p>The dispatcher runs every {@code STAREXEC_JOB_SUBMISSION_PERIOD} seconds (20 by default), and
 * StarLogger writes every WARN and ERROR to the error_logs table. Reported on every pass, a queue
 * whose backend could not accept work wrote three rows a minute for as long as that lasted. A
 * condition is now reported when it starts, again every {@link #REPEAT_MILLIS} while it lasts,
 * and its end once.
 *
 * <p>A queue has at most one condition of each {@link Kind}. Another cause of the same kind (the
 * queue still has no nodes, but now the backend says it could run it) is a new condition and is
 * reported at once, because it is new information.
 */
final class QueueConditions {

    /** How long a condition that lasts goes before it is reported again: the stuck-job report's interval. */
    static final long REPEAT_MILLIS = TimeUnit.HOURS.toMillis(6);

    /** What keeps the queue from dispatching. */
    enum Kind {
        /** The backend says the queue cannot take work now. */
        CANNOT_ACCEPT_WORK,
        /** StarExec counts no node for the queue. */
        NO_NODES
    }

    private static final class Condition {
        final String cause;
        final long since;
        long lastReported;

        Condition(String cause, long now) {
            this.cause = cause;
            this.since = now;
            this.lastReported = now;
        }
    }

    private final Map<String, Condition> current = new ConcurrentHashMap<>();

    private static String key(int queueId, Kind kind) {
        return queueId + ":" + kind;
    }

    /**
     * Records that the queue is in a condition of this kind, with this cause, at {@code now}.
     *
     * @return -1 when no report is due; otherwise the minutes the condition has lasted, which is 0
     *         when it starts or its cause changes
     */
    long report(int queueId, Kind kind, String cause, long now) {
        long[] due = {-1};
        current.compute(key(queueId, kind), (k, condition) -> {
            if (condition == null || !condition.cause.equals(cause)) {
                due[0] = 0;
                return new Condition(cause, now);
            }
            if (now - condition.lastReported >= REPEAT_MILLIS) {
                condition.lastReported = now;
                due[0] = TimeUnit.MILLISECONDS.toMinutes(now - condition.since);
            }
            return condition;
        });
        return due[0];
    }

    /**
     * Records that the queue is in no condition of this kind at {@code now}.
     *
     * @return the minutes the condition lasted, or -1 when there was none
     */
    long end(int queueId, Kind kind, long now) {
        Condition condition = current.remove(key(queueId, kind));
        return condition == null ? -1 : TimeUnit.MILLISECONDS.toMinutes(now - condition.since);
    }
}
