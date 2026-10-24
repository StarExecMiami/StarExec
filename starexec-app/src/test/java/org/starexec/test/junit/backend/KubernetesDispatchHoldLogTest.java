package org.starexec.test.junit.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.NodeListBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.starexec.backend.KubernetesNativeBackend;

/**
 * How often the Kubernetes backend logs why it holds dispatch for a queue.
 *
 * <p>JobManager asks {@code isQueueDispatchable} on every pass, every 20 seconds by default, for
 * each queue with pairs waiting, and StarLogger writes every WARN and ERROR to the error_logs
 * table. The backend logged its reason on every call, so a hold that lasted (a drained queue, a
 * CPU request that no node can fit) wrote three rows a minute per queue, and a node listing that
 * kept failing wrote one, with its stack trace, on every check. Each is now logged when it
 * starts or its reason changes, again every six hours while it lasts, and its end once at INFO.
 *
 * <p>Time comes from the backend's injectable clock, and the node view is set as a successful
 * listing leaves it, so no cluster is needed.
 */
public class KubernetesDispatchHoldLogTest {

    private static final String Q = "casc.q";
    private static final int PASSES = 3;
    private static final long SIX_HOURS = TimeUnit.HOURS.toMillis(6);
    private static final String REPEATED =
        "Reported again every 6 hours while it lasts, and at INFO when it ends.";
    private static final String ENDED = "no longer holds dispatch for queue";
    private static final String REFRESH_FAILED = "Could not refresh the Kubernetes queue view";

    /** About 83 days ahead of the real clock, so the old code's TTL reads the view as fresh too. */
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @Before
    public void setUp() {
        logger = (Logger) LoggerFactory.getLogger(KubernetesNativeBackend.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @After
    public void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    // ---------------------------------------------------------------------
    // Holds
    // ---------------------------------------------------------------------

    /** Each reason the backend can hold a queue for, and the line it logs for it. */
    @Test
    public void eachHoldIsLoggedWhenItStartsNotOnEveryPass() throws Exception {
        for (Hold hold : holds()) {
            appender.list.clear();
            KubernetesNativeBackend backend = hold.setup.build();
            for (int i = 0; i < PASSES; i++) {
                assertFalse(hold.name + ": the queue is held, as before", backend.isQueueDispatchable(Q));
            }

            List<ILoggingEvent> lines = lines(hold.level, hold.text);
            assertEquals(hold.name + ": logged once, when the hold starts", 1, lines.size());
            String line = lines.get(0).getFormattedMessage();
            assertTrue(hold.name + ": says when it is reported again: " + line, line.contains(REPEATED));
            assertFalse(hold.name + ": no age when it starts: " + line, line.contains("first seen"));
        }
    }

    @Test
    public void eachHoldIsLoggedAgainAfterSixHoursWithItsAge() throws Exception {
        for (Hold hold : holds()) {
            appender.list.clear();
            KubernetesNativeBackend backend = hold.setup.build();
            assertFalse(backend.isQueueDispatchable(Q));
            advance(backend, SIX_HOURS - 1);
            assertFalse(backend.isQueueDispatchable(Q));
            assertEquals(hold.name + ": not again before six hours", 1, lines(hold.level, hold.text).size());

            advance(backend, 1);
            assertFalse(backend.isQueueDispatchable(Q));
            List<ILoggingEvent> lines = lines(hold.level, hold.text);
            assertEquals(hold.name + ": again at six hours", 2, lines.size());
            String again = lines.get(1).getFormattedMessage();
            assertTrue(hold.name + ": with its age: " + again, again.contains("(first seen 360 minutes ago)"));
            assertTrue(hold.name + ": and when it is reported again: " + again, again.contains(REPEATED));
        }
    }

    @Test
    public void aHoldIsKeptPerQueue() throws Exception {
        KubernetesNativeBackend backend = degraded(backend(set("a.q", "b.q"), set("a.q", "b.q"), "1", 32_000L));
        for (int i = 0; i < PASSES; i++) {
            assertFalse(backend.isQueueDispatchable("a.q"));
            assertFalse(backend.isQueueDispatchable("b.q"));
        }

        assertEquals(1, lines(Level.WARN, "Deferring dispatch for queue 'a.q'").size());
        assertEquals("one queue's hold does not silence another's", 1,
            lines(Level.WARN, "Deferring dispatch for queue 'b.q'").size());
    }

    @Test
    public void theEndOfAHoldIsLoggedOnceAtInfo() throws Exception {
        KubernetesNativeBackend backend = backend(set(Q), set(), "1", 32_000L);
        assertFalse(backend.isQueueDispatchable(Q));
        advance(backend, TimeUnit.MINUTES.toMillis(5));
        // The node is uncordoned.
        view(backend, set(Q), set(Q), 32_000L);
        for (int i = 0; i < PASSES; i++) {
            assertTrue("dispatches again, as before", backend.isQueueDispatchable(Q));
        }

        List<ILoggingEvent> ended = lines(Level.INFO, ENDED + " '" + Q + "'");
        assertEquals(1, ended.size());
        String line = ended.get(0).getFormattedMessage();
        assertTrue("names the hold: " + line, line.contains("(no node is schedulable)"));
        assertTrue("and when it was first seen: " + line, line.contains("5 minute(s) ago"));
    }

    @Test
    public void aNewReasonIsLoggedAtOnceAndIsNotAnEnd() throws Exception {
        KubernetesNativeBackend backend = backend(set(Q), set(), "32", 32_000L);
        assertFalse(backend.isQueueDispatchable(Q));
        // The node is back, but it cannot fit a 32-CPU pod.
        view(backend, set(Q), set(Q), 32_000L);
        for (int i = 0; i < PASSES; i++) {
            assertFalse(backend.isQueueDispatchable(Q));
        }

        assertEquals(1, lines(Level.WARN, "none are schedulable right now").size());
        assertEquals("a new reason is new information: logged at once, and once", 1,
            lines(Level.ERROR, "Impossible CPU configuration").size());
        assertEquals("the queue is still held", 0, lines(Level.INFO, ENDED).size());
    }

    @Test
    public void aQueueThatNoNodeCarriesAnyMoreEndsItsHold() throws Exception {
        KubernetesNativeBackend backend = backend(set(Q), set(), "1", 32_000L);
        assertFalse(backend.isQueueDispatchable(Q));
        // The queue label is gone from every node.
        view(backend, set(), set(), 32_000L);

        assertTrue("left to be refused at submission, as before", backend.isQueueDispatchable(Q));
        assertEquals(1, lines(Level.INFO, ENDED + " '" + Q + "'").size());
    }

    /**
     * The capacity gate kept one "blocked" note for all queues, so with one queue held and another
     * dispatching, it logged that the gate had cleared for the second on every pass.
     */
    @Test
    public void oneQueuesCapacityHoldDoesNotClearAnother() throws Exception {
        KubernetesNativeBackend backend = backend(set("small.q", "big.q"), set("small.q", "big.q"), "31", null);
        Map<String, Long> capacity = new HashMap<>();
        capacity.put("small.q", 8_000L);
        capacity.put("big.q", 64_000L);
        setField(backend, "queueMaxAllocatableCpuMillis", capacity);
        for (int i = 0; i < PASSES; i++) {
            assertFalse(backend.isQueueDispatchable("small.q"));
            assertTrue(backend.isQueueDispatchable("big.q"));
        }

        assertEquals("big.q was never held, so nothing about it ends: " + messages(), 0,
            lines(Level.INFO, "'big.q'").size());
        assertEquals(1, lines(Level.ERROR, "queue 'small.q'").size());
    }

    /** A repeat starts six quiet hours again, and the end still counts from the first sighting. */
    @Test
    public void aRepeatIsFollowedBySixQuietHoursAndTheEndCountsFromTheStart() throws Exception {
        KubernetesNativeBackend backend = backend(set(Q), set(), "1", 32_000L);
        assertFalse(backend.isQueueDispatchable(Q));
        advance(backend, SIX_HOURS);
        assertFalse(backend.isQueueDispatchable(Q));
        advance(backend, TimeUnit.SECONDS.toMillis(20));
        assertFalse(backend.isQueueDispatchable(Q));
        assertEquals("the start and the repeat, not the pass after the repeat", 2,
            lines(Level.WARN, "none are schedulable right now").size());

        advance(backend, TimeUnit.MINUTES.toMillis(5));
        view(backend, set(Q), set(Q), 32_000L);
        assertTrue(backend.isQueueDispatchable(Q));
        List<ILoggingEvent> ended = lines(Level.INFO, ENDED + " '" + Q + "'");
        assertEquals(1, ended.size());
        String line = ended.get(0).getFormattedMessage();
        assertTrue("6 h 5 min 20 s after the start, not 5 min after the repeat: " + line,
            line.contains("first seen 365 minute(s) ago"));
    }

    @Test
    public void aNewReasonCountsItsAgeFromWhenItStarted() throws Exception {
        KubernetesNativeBackend backend = backend(set(Q), set(), "32", 32_000L);
        assertFalse(backend.isQueueDispatchable(Q));
        advance(backend, TimeUnit.HOURS.toMillis(1));
        // The node is back, but it cannot fit a 32-CPU pod.
        view(backend, set(Q), set(Q), 32_000L);
        assertFalse(backend.isQueueDispatchable(Q));
        advance(backend, SIX_HOURS);
        assertFalse(backend.isQueueDispatchable(Q));

        List<ILoggingEvent> impossible = lines(Level.ERROR, "Impossible CPU configuration");
        assertEquals(2, impossible.size());
        String again = impossible.get(1).getFormattedMessage();
        assertTrue("six hours since this reason began, not seven since the hold did: " + again,
            again.contains("(first seen 360 minutes ago)"));
    }

    /** The two CPU reasons are different holds: the request may fit once the capacity is known. */
    @Test
    public void aCapacityThatBecomesKnownButTooSmallIsLoggedAtOnce() throws Exception {
        KubernetesNativeBackend backend = backend(set(Q), set(Q), "32", null);
        assertFalse(backend.isQueueDispatchable(Q));
        // The node's allocatable CPU can be read now: 32 CPUs, too few for a 32-CPU pod.
        view(backend, set(Q), set(Q), 32_000L);
        assertFalse(backend.isQueueDispatchable(Q));

        assertEquals(1, lines(Level.WARN, "Cannot establish CPU capacity").size());
        assertEquals("a different reason, so logged at once", 1,
            lines(Level.ERROR, "Impossible CPU configuration").size());
    }

    @Test
    public void theEndOfAHoldAfterTheClockSteppedBackSaysZeroMinutes() throws Exception {
        KubernetesNativeBackend backend = backend(set(Q), set(), "1", 32_000L);
        assertFalse(backend.isQueueDispatchable(Q));
        // The wall clock steps back 7 minutes, then the node is uncordoned.
        advance(backend, -TimeUnit.MINUTES.toMillis(7));
        view(backend, set(Q), set(Q), 32_000L);
        assertTrue(backend.isQueueDispatchable(Q));

        List<ILoggingEvent> ended = lines(Level.INFO, ENDED + " '" + Q + "'");
        assertEquals(1, ended.size());
        String line = ended.get(0).getFormattedMessage();
        assertTrue("never a negative age: " + line, line.contains("first seen 0 minute(s) ago"));
    }

    @Test
    public void aDispatchableQueueLogsNothing() throws Exception {
        KubernetesNativeBackend backend = backend(set(Q), set(Q), "1", 32_000L);
        // The constructor logs that it ran; only the checks are under test.
        appender.list.clear();
        for (int i = 0; i < PASSES; i++) {
            assertTrue(backend.isQueueDispatchable(Q));
        }

        assertEquals("nothing at INFO or above: " + messages(), 0,
            appender.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO)).count());
    }

    // ---------------------------------------------------------------------
    // The node listing behind the view
    // ---------------------------------------------------------------------

    @Test
    public void aNodeListingThatFailsIsLoggedOnceWithItsCause() throws Exception {
        KubernetesNativeBackend backend = listing(backend(set(Q), set(Q), "1", 32_000L),
            new RuntimeException("API server unreachable"), null);
        for (int i = 0; i < PASSES; i++) {
            assertTrue("the previous view is reused, as before", backend.isQueueDispatchable(Q));
        }

        List<ILoggingEvent> failed = lines(Level.WARN, REFRESH_FAILED);
        assertEquals(1, failed.size());
        assertNotNull("with the failure itself", failed.get(0).getThrowableProxy());
        assertEquals("API server unreachable", failed.get(0).getThrowableProxy().getMessage());
        assertTrue(failed.get(0).getFormattedMessage().contains(REPEATED));
    }

    @Test
    public void aNodeListingThatKeepsFailingIsLoggedAgainAfterSixHours() throws Exception {
        KubernetesNativeBackend backend = listing(backend(set(Q), set(Q), "1", 32_000L),
            new RuntimeException("API server unreachable"), null);
        assertTrue(backend.isQueueDispatchable(Q));
        // A failed listing leaves the view stale, so every call lists again.
        now.addAndGet(SIX_HOURS - 1);
        assertTrue(backend.isQueueDispatchable(Q));
        assertEquals("not again before six hours", 1, lines(Level.WARN, REFRESH_FAILED).size());

        now.addAndGet(1);
        assertTrue(backend.isQueueDispatchable(Q));
        List<ILoggingEvent> failed = lines(Level.WARN, REFRESH_FAILED);
        assertEquals(2, failed.size());
        String again = failed.get(1).getFormattedMessage();
        assertTrue("with its age: " + again, again.contains("(first seen 360 minutes ago)"));
    }

    @Test
    public void aNodeListingThatWorksAgainIsLoggedOnceAtInfo() throws Exception {
        KubernetesNativeBackend backend = listing(backend(set(Q), set(Q), "1", 32_000L),
            new RuntimeException("API server unreachable"), schedulableNode("n021", Q, "32"));
        assertTrue(backend.isQueueDispatchable(Q));
        now.addAndGet(TimeUnit.MINUTES.toMillis(2));
        for (int i = 0; i < PASSES; i++) {
            // The first lists the node; the others reuse the fresh view.
            assertTrue(backend.isQueueDispatchable(Q));
        }

        List<ILoggingEvent> back = lines(Level.INFO, "Refreshed the Kubernetes queue view again");
        assertEquals(1, back.size());
        assertTrue(back.get(0).getFormattedMessage(), back.get(0).getFormattedMessage().contains("2 minute(s) ago"));
        assertEquals(1, lines(Level.WARN, REFRESH_FAILED).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void aNodeListingThatWorksAgainAfterARepeatCountsFromTheFirstFailure() throws Exception {
        KubernetesNativeBackend backend = backend(set(Q), set(Q), "1", 32_000L);
        Mockito.when(nodeListing(backend).list())
            .thenThrow(new RuntimeException("API server unreachable"))
            .thenThrow(new RuntimeException("API server unreachable"))
            .thenReturn(new NodeListBuilder().withItems(node("n021", Q, "32", false)).build());
        setField(backend, "queueViewRefreshedAt", 0L);
        assertTrue(backend.isQueueDispatchable(Q));
        now.addAndGet(SIX_HOURS);
        assertTrue(backend.isQueueDispatchable(Q));
        now.addAndGet(TimeUnit.MINUTES.toMillis(2));
        assertTrue(backend.isQueueDispatchable(Q));

        assertEquals("the failure and its repeat", 2, lines(Level.WARN, REFRESH_FAILED).size());
        List<ILoggingEvent> back = lines(Level.INFO, "Refreshed the Kubernetes queue view again");
        assertEquals(1, back.size());
        String line = back.get(0).getFormattedMessage();
        assertTrue("362 minutes after the first failure, not 2 after the repeat: " + line,
            line.contains("first seen failing 362 minute(s) ago"));
    }

    /** The view's age is read on the backend's clock, the one its holds are timed with. */
    @Test
    @SuppressWarnings("unchecked")
    public void theViewIsListedAgainOnceItIsTenSecondsOldOnTheBackendsClock() throws Exception {
        KubernetesNativeBackend backend = backend(set(Q), set(Q), "1", 32_000L);
        // Since the last listing, the queue's only node has been cordoned.
        Mockito.when(nodeListing(backend).list())
            .thenReturn(new NodeListBuilder().withItems(node("n021", Q, "32", true)).build());
        now.addAndGet(TimeUnit.SECONDS.toMillis(10));

        assertFalse("the view was listed again, and shows the node cordoned", backend.isQueueDispatchable(Q));
        assertEquals(1, lines(Level.WARN, "none are schedulable right now").size());
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    @FunctionalInterface
    private interface Setup {
        KubernetesNativeBackend build() throws Exception;
    }

    private static final class Hold {
        final String name;
        final Level level;
        final String text;
        final Setup setup;

        Hold(String name, Level level, String text, Setup setup) {
            this.name = name;
            this.level = level;
            this.text = text;
            this.setup = setup;
        }
    }

    private List<Hold> holds() {
        return Arrays.asList(
            new Hold("drained", Level.WARN, "none are schedulable right now",
                () -> backend(set(Q), set(), "1", 32_000L)),
            new Hold("CPU request too large", Level.ERROR, "Impossible CPU configuration",
                () -> backend(set(Q), set(Q), "32", 32_000L)),
            new Hold("CPU capacity unknown", Level.WARN, "Cannot establish CPU capacity",
                () -> backend(set(Q), set(Q), "31", null)),
            new Hold("view never loaded", Level.WARN, "has never loaded", () -> {
                KubernetesNativeBackend backend = backend(set(), set(), "1", null);
                setField(backend, "queueViewLoaded", false);
                return backend;
            }),
            new Hold("admission degraded", Level.WARN, "admission is degraded",
                () -> degraded(backend(set(Q), set(Q), "1", 32_000L)))
        );
    }

    /** An initialized backend whose view is loaded and fresh, and whose clock is {@link #now}. */
    private KubernetesNativeBackend backend(Set<String> labelled, Set<String> schedulable, String cpuLimit,
            Long maxAllocatableMillis) throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend();
        setField(backend, "clock", (LongSupplier) now::get);
        setField(backend, "initialized", true);
        setField(backend, "cpuLimit", cpuLimit);
        view(backend, labelled, schedulable, maxAllocatableMillis);
        return backend;
    }

    /** Sets the view as a successful listing at {@link #now} leaves it. */
    private void view(KubernetesNativeBackend backend, Set<String> labelled, Set<String> schedulable,
            Long maxAllocatableMillis) throws Exception {
        Map<String, Long> capacity = new HashMap<>();
        if (maxAllocatableMillis != null) {
            for (String queue : schedulable) {
                capacity.put(queue, maxAllocatableMillis);
            }
        }
        setField(backend, "labelledQueues", labelled);
        setField(backend, "schedulableQueues", schedulable);
        setField(backend, "queueMaxAllocatableCpuMillis", capacity);
        setField(backend, "queueViewLoaded", true);
        setField(backend, "queueViewRefreshedAt", now.get());
    }

    /**
     * Moves the clock on, with the view still fresh, as a listing that keeps succeeding every 10
     * seconds leaves it. Without that, the backend would try to list nodes with no client.
     */
    private void advance(KubernetesNativeBackend backend, long millis) throws Exception {
        now.addAndGet(millis);
        setField(backend, "queueViewRefreshedAt", now.get());
    }

    private static KubernetesNativeBackend degraded(KubernetesNativeBackend backend) throws Exception {
        ((AtomicBoolean) getField(backend, "admissionDegraded")).set(true);
        setField(backend, "admissionDegradedDetail", "stray-1 (Running)");
        return backend;
    }

    /**
     * Gives the backend a client whose node listing throws {@code failure}, then returns {@code node}
     * if one is given, and makes the view stale so that the next call lists.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static KubernetesNativeBackend listing(KubernetesNativeBackend backend, RuntimeException failure,
            Node node) throws Exception {
        FilterWatchListDeletable workers = nodeListing(backend);
        if (node == null) {
            Mockito.when(workers.list()).thenThrow(failure);
        } else {
            NodeList list = new NodeListBuilder().withItems(node).build();
            Mockito.when(workers.list()).thenThrow(failure).thenReturn(list);
        }
        setField(backend, "queueViewRefreshedAt", 0L);
        return backend;
    }

    /** Gives the backend a client, and returns its worker-node listing for the caller to stub. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static FilterWatchListDeletable nodeListing(KubernetesNativeBackend backend) throws Exception {
        setField(backend, "queueLabelKey", "starexec.org/queue");
        setField(backend, "workerNodeSelectorKey", "starexec.org/worker");
        setField(backend, "workerNodeSelectorValue", "true");
        KubernetesClient client = Mockito.mock(KubernetesClient.class);
        NonNamespaceOperation nodes = Mockito.mock(NonNamespaceOperation.class);
        FilterWatchListDeletable workers = Mockito.mock(FilterWatchListDeletable.class);
        Mockito.when(client.nodes()).thenReturn(nodes);
        Mockito.when(nodes.withLabel("starexec.org/worker", "true")).thenReturn(workers);
        setField(backend, "kubernetesClient", client);
        return workers;
    }

    private static Node schedulableNode(String name, String queue, String cpus) {
        return node(name, queue, cpus, false);
    }

    /** A Ready worker node of {@code queue}, cordoned or not. */
    private static Node node(String name, String queue, String cpus, boolean cordoned) {
        Map<String, String> labels = new HashMap<>();
        labels.put("starexec.org/worker", "true");
        labels.put("starexec.org/queue", queue);
        return new NodeBuilder()
            .withNewMetadata().withName(name).withLabels(labels).endMetadata()
            .withNewSpec().withUnschedulable(cordoned).endSpec()
            .withNewStatus()
            .addNewCondition().withType("Ready").withStatus("True").endCondition()
            .addToAllocatable("cpu", new Quantity(cpus))
            .endStatus()
            .build();
    }

    private List<ILoggingEvent> lines(Level level, String text) {
        return appender.list.stream()
            .filter(e -> e.getLevel() == level && e.getFormattedMessage().contains(text))
            .collect(Collectors.toList());
    }

    private String messages() {
        return appender.list.stream()
            .map(e -> e.getLevel() + " " + e.getFormattedMessage())
            .collect(Collectors.joining(" | "));
    }

    private static Set<String> set(String... queues) {
        return new HashSet<>(Arrays.asList(queues));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = KubernetesNativeBackend.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = KubernetesNativeBackend.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
