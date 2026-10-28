package org.starexec.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.starexec.backend.Backend;
import org.starexec.constants.R;
import org.starexec.data.database.Common;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Queues;
import org.starexec.data.to.Queue;
import org.starexec.data.to.WorkerNode;

/**
 * How often the dispatcher reports a queue it cannot use.
 *
 * <p>The dispatcher runs every 20 seconds. It reported a queue whose backend could not accept
 * work, and a queue with no nodes, on every pass, and StarLogger writes every WARN and ERROR to
 * the error_logs table. A Podman backend that did not start therefore wrote three rows a minute
 * for as long as StarExec ran. A queue's condition is reported when it starts, and its end once
 * at INFO.
 *
 * <p>The database answers of a pass are Mockito doubles, so no database is needed. The queue ids
 * differ from test to test because JobManager keeps its state in static fields.
 */
public class QueueDispatchLogTest {

    private static final int PASSES = 3;
    private static final String DEFERRED = "cannot accept work at the moment";
    private static final String NO_NODES = "no nodes";

    private static int nextQueueId = 990_000;

    private Backend originalBackend;
    private Backend backend;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    public void setUp() {
        originalBackend = R.BACKEND;
        backend = mock(Backend.class);
        R.BACKEND = backend;
        logger = (Logger) LoggerFactory.getLogger(JobManager.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    public void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        R.BACKEND = originalBackend;
    }

    private static Queue queue(String name) {
        Queue q = new Queue();
        q.setId(nextQueueId++);
        q.setName(name);
        return q;
    }

    private long logged(Level level, String text) {
        return appender.list.stream()
            .filter(e -> e.getLevel() == level && e.getFormattedMessage().contains(text))
            .count();
    }

    /** One dispatcher pass over one active queue that has the given nodes and nothing pending. */
    private static void dispatch(Queue q, List<WorkerNode> nodes) {
        dispatch(q, nodes, 0);
    }

    /**
     * One dispatcher pass over one active queue that has the given nodes, no pair enqueued, and
     * {@code pendingPairs} pairs waiting to be submitted.
     */
    private static void dispatch(Queue q, List<WorkerNode> nodes, int pendingPairs) {
        try (MockedStatic<Jobs> jobs = mockStatic(Jobs.class);
                MockedStatic<Queues> queues = mockStatic(Queues.class);
                MockedStatic<Common> common = mockStatic(Common.class)) {
            queues.when(Queues::getAllActive).thenReturn(Collections.singletonList(q));
            queues.when(() -> Queues.getNodes(q.getId())).thenReturn(nodes);
            queues.when(() -> Queues.getSizeOfQueue(q.getId())).thenReturn(0);
            queues.when(() -> Queues.countPendingPairs(q.getId())).thenReturn(pendingPairs);
            queues.when(() -> Queues.getPendingJobs(q.getId())).thenReturn(Collections.emptyList());
            JobManager.checkPendingJobs();
        }
    }

    /**
     * Reports queue {@code q} as having no nodes at {@code now}, with {@code enqueued} pairs
     * enqueued and {@code pending} pairs waiting to be submitted (-1: the count failed).
     */
    private static void noNodes(Queue q, int enqueued, int pending, long now) {
        try (MockedStatic<Queues> queues = mockStatic(Queues.class);
                MockedStatic<Common> common = mockStatic(Common.class)) {
            queues.when(() -> Queues.countPendingPairs(q.getId())).thenReturn(pending);
            JobManager.logQueueHasNoNodes(q, enqueued, now);
        }
    }

    /** The one message logged at {@code level} that contains {@code text}. */
    private String message(Level level, String text) {
        List<String> found = appender.list.stream()
            .filter(e -> e.getLevel() == level && e.getFormattedMessage().contains(text))
            .map(ILoggingEvent::getFormattedMessage)
            .collect(Collectors.toList());
        assertEquals(1, found.size(), found.toString());
        return found.get(0);
    }

    /**
     * A queue with no nodes never enqueues a pair, so the pairs waiting for it are pending, not
     * enqueued. The line counted only enqueued pairs, and said "0 pair(s) waiting" while 24 pairs
     * waited for the queue.
     */
    @Test
    public void aQueueWithNoNodesCountsThePairsWaitingForIt() {
        Queue q = queue("all.q");
        when(backend.isQueueDispatchable(q.getName())).thenReturn(false);
        dispatch(q, Collections.emptyList(), 24);
        String line = message(Level.WARN, NO_NODES);
        assertTrue(line.contains(
            "Queue all.q has no nodes available (24 pair(s) waiting to be submitted, 0 enqueued);"
                + " the backend confirms it cannot accept work."), line);
    }

    /** The same for the node table mismatch, which the line reports as an ERROR. */
    @Test
    public void aNodeTableMismatchCountsThePairsWaitingForTheQueue() {
        Queue q = queue("container.q");
        when(backend.isQueueDispatchable(q.getName())).thenReturn(true);
        dispatch(q, Collections.emptyList(), 1);
        String line = message(Level.ERROR, NO_NODES);
        assertTrue(line.contains(
            "Queue container.q has no nodes associated with it (1 pair(s) waiting to be submitted,"
                + " 0 enqueued), yet the backend reports it can accept work."), line);
    }

    /**
     * Pairs enqueued before the queue lost its nodes are counted apart from the pairs that wait to
     * be submitted, and a count of 0 is a count, not an unknown one.
     */
    @Test
    public void aQueueWithNoNodesCountsItsEnqueuedPairsApart() {
        Queue q = queue("all.q");
        when(backend.isQueueDispatchable(q.getName())).thenReturn(false);
        noNodes(q, 3, 0, 5_000_000L);
        String line = message(Level.WARN, NO_NODES);
        assertTrue(line.contains("(0 pair(s) waiting to be submitted, 3 enqueued)"), line);
    }

    /** A count that failed says so; the line does not print the -1 it is given. */
    @Test
    public void aCountThatFailedIsReportedAsUnknown() {
        Queue q = queue("all.q");
        when(backend.isQueueDispatchable(q.getName())).thenReturn(false);
        noNodes(q, 0, -1, 5_000_000L);
        String line = message(Level.WARN, NO_NODES);
        assertTrue(line.contains(
            "(an unknown number of pairs waiting to be submitted, 0 enqueued)"), line);
    }

    /**
     * The count is a query over job_pairs. It runs when a line is due, not on every 20 s pass
     * while the queue has no nodes.
     */
    @Test
    public void thePairsAreCountedOnlyWhenALineIsDue() {
        Queue q = queue("all.q");
        long t0 = 5_000_000L;
        when(backend.isQueueDispatchable(q.getName())).thenReturn(false);
        try (MockedStatic<Queues> queues = mockStatic(Queues.class);
                MockedStatic<Common> common = mockStatic(Common.class)) {
            queues.when(() -> Queues.countPendingPairs(q.getId())).thenReturn(24);
            for (int pass = 0; pass < PASSES; pass++) {
                JobManager.logQueueHasNoNodes(q, 0, t0 + pass * 20_000L);
            }
            queues.verify(() -> Queues.countPendingPairs(q.getId()), times(1));
        }
        assertEquals(1, logged(Level.WARN, NO_NODES));
    }

    @Test
    public void aQueueThatCannotAcceptWorkIsReportedOnceNotOnEveryPass() {
        Queue q = queue("container.q");
        when(backend.isQueueDispatchable(q.getName())).thenReturn(false);
        for (int pass = 0; pass < PASSES; pass++) {
            JobManager.submitJobs(Collections.emptyList(), q, 0, 1);
        }
        assertEquals(1,
            logged(Level.WARN, DEFERRED), "one WARN, so one error_logs row, for " + PASSES + " passes");
    }

    @Test
    public void eachQueueThatCannotAcceptWorkIsReportedOnItsOwn() {
        Queue first = queue("first.q");
        Queue second = queue("second.q");
        when(backend.isQueueDispatchable(first.getName())).thenReturn(false);
        when(backend.isQueueDispatchable(second.getName())).thenReturn(false);
        for (int pass = 0; pass < PASSES; pass++) {
            JobManager.submitJobs(Collections.emptyList(), first, 0, 1);
            JobManager.submitJobs(Collections.emptyList(), second, 0, 1);
        }
        assertEquals(2, logged(Level.WARN, DEFERRED), "one WARN per queue");
    }

    @Test
    public void aQueueWithNoNodesIsReportedOnceNotOnEveryPass() {
        Queue q = queue("all.q");
        when(backend.isQueueDispatchable(q.getName())).thenReturn(false);
        for (int pass = 0; pass < PASSES; pass++) {
            dispatch(q, Collections.emptyList());
        }
        assertEquals(1, logged(Level.WARN, NO_NODES), "one WARN for " + PASSES + " passes");
    }

    /** The backend says it can run the queue's pairs, but StarExec counts no node for it. */
    @Test
    public void aNodeTableMismatchIsReportedOnceNotOnEveryPass() {
        Queue q = queue("container.q");
        when(backend.isQueueDispatchable(q.getName())).thenReturn(true);
        for (int pass = 0; pass < PASSES; pass++) {
            dispatch(q, Collections.emptyList());
        }
        assertEquals(1, logged(Level.ERROR, NO_NODES), "one ERROR for " + PASSES + " passes");
    }

    @Test
    public void aQueueThatGetsANodeSaysSoOnce() {
        Queue q = queue("container.q");
        when(backend.isQueueDispatchable(q.getName())).thenReturn(false);
        dispatch(q, Collections.emptyList());
        for (int pass = 1; pass < PASSES; pass++) {
            dispatch(q, Collections.singletonList(new WorkerNode("container-worker-1")));
        }
        assertEquals(1, logged(Level.WARN, NO_NODES));
        assertEquals(1, logged(Level.INFO, "has nodes again"), "the end of the condition, once");
    }

    /** A different cause is new information, so it is reported even though the queue still has no nodes. */
    @Test
    public void aQueueWhoseCauseChangesIsReportedAgain() {
        Queue q = queue("container.q");
        when(backend.isQueueDispatchable(q.getName())).thenReturn(false, true);
        dispatch(q, Collections.emptyList());
        dispatch(q, Collections.emptyList());
        assertEquals(1, logged(Level.WARN, NO_NODES));
        assertEquals(1, logged(Level.ERROR, NO_NODES));
    }

    @Test
    public void aBackendThatCannotSayWhetherAQueueWithNoNodesCanRunIsReportedOnce() {
        Queue q = queue("container.q");
        when(backend.isQueueDispatchable(q.getName())).thenThrow(new IllegalStateException("no client"));
        for (int pass = 0; pass < PASSES; pass++) {
            dispatch(q, Collections.emptyList());
        }
        assertEquals(1, logged(Level.WARN, "Could not ask the backend about queue container.q"));
    }

    @Test
    public void aQueueThatAcceptsWorkAgainSaysSoOnceAndIsReportedAgainIfItStopsAgain() {
        Queue q = queue("container.q");
        long t0 = 5_000_000L;
        when(backend.isQueueDispatchable(q.getName())).thenReturn(false, true, true, false);
        assertFalse(JobManager.queueAcceptsWork(q, t0));
        assertTrue(JobManager.queueAcceptsWork(q, t0 + 3 * 60_000L));
        assertTrue(JobManager.queueAcceptsWork(q, t0 + 4 * 60_000L));
        assertFalse(JobManager.queueAcceptsWork(q, t0 + 5 * 60_000L));
        assertEquals(2, logged(Level.WARN, DEFERRED), "each refusal that starts");
        assertEquals(1, logged(Level.INFO, "accepts work again; it was first seen refusing work 3 minute(s) ago"),
            "the end, once, with when the refusal was first seen");
    }

    @Test
    public void aRefusalThatLastsIsReportedAgainWithItsLength() {
        Queue q = queue("container.q");
        long t0 = 5_000_000L;
        when(backend.isQueueDispatchable(q.getName())).thenReturn(false);
        JobManager.queueAcceptsWork(q, t0);
        JobManager.queueAcceptsWork(q, t0 + QueueConditions.REPEAT_MILLIS - 1);
        JobManager.queueAcceptsWork(q, t0 + QueueConditions.REPEAT_MILLIS);
        assertEquals(2, logged(Level.WARN, DEFERRED));
        assertEquals(1, logged(Level.WARN, DEFERRED + " (first seen 360 minutes ago)"));
        assertEquals(2,
            logged(Level.WARN, "Reported again every 6 hours while it lasts, and at INFO when it ends."), "each report says when the next one comes");
    }

    @Test
    public void aQueueWithNoNodesThatLastsIsReportedAgainWithItsLength() {
        Queue q = queue("container.q");
        long t0 = 5_000_000L;
        when(backend.isQueueDispatchable(q.getName())).thenReturn(false);
        noNodes(q, 0, 0, t0);
        noNodes(q, 0, 0, t0 + QueueConditions.REPEAT_MILLIS);
        assertEquals(2, logged(Level.WARN, "no nodes available"));
        assertEquals(1, logged(Level.WARN, "no nodes available (first seen 360 minutes ago)"));
    }

    @Test
    public void aNodeTableMismatchThatLastsIsReportedAgainWithItsLength() {
        Queue q = queue("container.q");
        long t0 = 5_000_000L;
        when(backend.isQueueDispatchable(q.getName())).thenReturn(true);
        noNodes(q, 0, 0, t0);
        noNodes(q, 0, 0, t0 + QueueConditions.REPEAT_MILLIS);
        assertEquals(2, logged(Level.ERROR, "no nodes associated with it"));
        assertEquals(1, logged(Level.ERROR, "associated with it (first seen 360 minutes ago)"));
    }

    @Test
    public void aBackendThatCannotAnswerIsReportedAgainWithItsLength() {
        Queue q = queue("container.q");
        long t0 = 5_000_000L;
        when(backend.isQueueDispatchable(q.getName())).thenThrow(new IllegalStateException("no client"));
        JobManager.logQueueHasNoNodes(q, 0, t0);
        JobManager.logQueueHasNoNodes(q, 0, t0 + QueueConditions.REPEAT_MILLIS);
        assertEquals(2, logged(Level.WARN, "Could not ask the backend about queue container.q"));
        assertEquals(1, logged(Level.WARN,
            "Could not ask the backend about queue container.q (first seen 360 minutes ago)"));
    }
}
