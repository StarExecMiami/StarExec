package org.starexec.jobs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
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

    @Before
    public void setUp() {
        originalBackend = R.BACKEND;
        backend = mock(Backend.class);
        R.BACKEND = backend;
        logger = (Logger) LoggerFactory.getLogger(JobManager.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @After
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
        try (MockedStatic<Jobs> jobs = mockStatic(Jobs.class);
                MockedStatic<Queues> queues = mockStatic(Queues.class);
                MockedStatic<Common> common = mockStatic(Common.class)) {
            queues.when(Queues::getAllActive).thenReturn(Collections.singletonList(q));
            queues.when(() -> Queues.getNodes(q.getId())).thenReturn(nodes);
            queues.when(() -> Queues.getSizeOfQueue(q.getId())).thenReturn(0);
            queues.when(() -> Queues.getPendingJobs(q.getId())).thenReturn(Collections.emptyList());
            JobManager.checkPendingJobs();
        }
    }

    @Test
    public void aQueueThatCannotAcceptWorkIsReportedOnceNotOnEveryPass() {
        Queue q = queue("container.q");
        when(backend.isQueueDispatchable(q.getName())).thenReturn(false);
        for (int pass = 0; pass < PASSES; pass++) {
            JobManager.submitJobs(Collections.emptyList(), q, 0, 1);
        }
        assertEquals("one WARN, so one error_logs row, for " + PASSES + " passes",
            1, logged(Level.WARN, DEFERRED));
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
        assertEquals("one WARN per queue", 2, logged(Level.WARN, DEFERRED));
    }

    @Test
    public void aQueueWithNoNodesIsReportedOnceNotOnEveryPass() {
        Queue q = queue("all.q");
        when(backend.isQueueDispatchable(q.getName())).thenReturn(false);
        for (int pass = 0; pass < PASSES; pass++) {
            dispatch(q, Collections.emptyList());
        }
        assertEquals("one WARN for " + PASSES + " passes", 1, logged(Level.WARN, NO_NODES));
    }

    /** The backend says it can run the queue's pairs, but StarExec counts no node for it. */
    @Test
    public void aNodeTableMismatchIsReportedOnceNotOnEveryPass() {
        Queue q = queue("container.q");
        when(backend.isQueueDispatchable(q.getName())).thenReturn(true);
        for (int pass = 0; pass < PASSES; pass++) {
            dispatch(q, Collections.emptyList());
        }
        assertEquals("one ERROR for " + PASSES + " passes", 1, logged(Level.ERROR, NO_NODES));
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
        assertEquals("the end of the condition, once", 1, logged(Level.INFO, "has nodes again"));
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
        assertEquals("each refusal that starts", 2, logged(Level.WARN, DEFERRED));
        assertEquals("the end, once, with when the refusal was first seen", 1,
            logged(Level.INFO, "accepts work again; it was first seen refusing work 3 minute(s) ago"));
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
        assertEquals("each report says when the next one comes",
            2, logged(Level.WARN, "Reported again every 6 hours while it lasts, and at INFO when it ends."));
    }
}
