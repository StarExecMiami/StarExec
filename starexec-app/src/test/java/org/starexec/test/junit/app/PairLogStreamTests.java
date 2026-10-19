package org.starexec.test.junit.app;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.app.RESTServices;
import org.starexec.config.EnvironmentConfig;
import org.starexec.data.database.JobPairs;
import org.starexec.data.security.JobSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.Status;
import org.starexec.test.TestUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.ws.rs.sse.OutboundSseEvent;
import javax.ws.rs.sse.Sse;
import javax.ws.rs.sse.SseEventSink;

public class PairLogStreamTests {

    @After
    public void resetStreamState() throws Exception {
        // Closes every session a test left open, such as a running pair's, which would otherwise stay
        // registered with its slot, and then puts a fresh production executor back.
        RESTServices.shutdownPairLogStreamExecutor();
        Method resetExecutorMethod = RESTServices.class.getDeclaredMethod("resetPairLogStreamExecutorForTesting");
        resetExecutorMethod.setAccessible(true);
        resetExecutorMethod.invoke(null);
        Field counterField = RESTServices.class.getDeclaredField("activePairLogStreams");
        counterField.setAccessible(true);
        AtomicInteger counter = (AtomicInteger) counterField.get(null);
        counter.set(0);
    }

    @Test
    public void resolveInitialOffsetUsesLastEventIdWhenValid() throws Exception {
        File tempFile = File.createTempFile("pair-log-stream", ".txt");
        Files.write(tempFile.toPath(), "abcdef".getBytes(StandardCharsets.UTF_8));

        Method resolveMethod = RESTServices.class.getDeclaredMethod("resolveInitialOffset", File.class, String.class);
        resolveMethod.setAccessible(true);

        long offset = (Long) resolveMethod.invoke(null, tempFile, "3");
        Assert.assertEquals(3L, offset);

        tempFile.delete();
    }

    @Test
    public void resolveInitialOffsetFallsBackToTailWhenHeaderInvalid() throws Exception {
        File tempFile = File.createTempFile("pair-log-stream", ".txt");
        Files.write(tempFile.toPath(), "abcdef".getBytes(StandardCharsets.UTF_8));

        Method resolveMethod = RESTServices.class.getDeclaredMethod("resolveInitialOffset", File.class, String.class);
        resolveMethod.setAccessible(true);

        long offset = (Long) resolveMethod.invoke(null, tempFile, "bad-offset");
        Assert.assertEquals(tempFile.length(), offset);

        tempFile.delete();
    }

    @Test
    public void resolveInitialOffsetFallsBackToTailWhenHeaderNegative() throws Exception {
        File tempFile = File.createTempFile("pair-log-stream", ".txt");
        Files.write(tempFile.toPath(), "abcdef".getBytes(StandardCharsets.UTF_8));

        Method resolveMethod = RESTServices.class.getDeclaredMethod("resolveInitialOffset", File.class, String.class);
        resolveMethod.setAccessible(true);

        long offset = (Long) resolveMethod.invoke(null, tempFile, "-10");
        Assert.assertEquals(tempFile.length(), offset);

        tempFile.delete();
    }

    @Test
    public void streamAvailableBytesEmitsResetWhenLogTruncated() throws Exception {
        File tempFile = File.createTempFile("pair-log-stream", ".txt");
        Files.write(tempFile.toPath(), "abc".getBytes(StandardCharsets.UTF_8));

        Method streamMethod = RESTServices.class.getDeclaredMethod(
                "streamAvailableBytes",
                int.class,
                java.io.OutputStream.class,
                File.class,
                long.class,
                int.class
        );
        streamMethod.setAccessible(true);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long returnedOffset = (Long) streamMethod.invoke(null, 42, output, tempFile, 10L, 1024);
        String events = output.toString(StandardCharsets.UTF_8.name());

        Assert.assertTrue(events.contains("event: reset"));
        Assert.assertTrue(events.contains("event: chunk"));
        Assert.assertEquals(3L, returnedOffset);

        tempFile.delete();
    }

    @Test
    public void streamAvailableBytesUsesChunkLimit() throws Exception {
        File tempFile = File.createTempFile("pair-log-stream", ".txt");
        try (RandomAccessFile raf = new RandomAccessFile(tempFile, "rw")) {
            raf.write("0123456789".getBytes(StandardCharsets.UTF_8));
        }

        Method streamMethod = RESTServices.class.getDeclaredMethod(
                "streamAvailableBytes",
                int.class,
                java.io.OutputStream.class,
                File.class,
                long.class,
                int.class
        );
        streamMethod.setAccessible(true);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long returnedOffset = (Long) streamMethod.invoke(null, 42, output, tempFile, 0L, 4);
        String events = output.toString(StandardCharsets.UTF_8.name());

        Assert.assertTrue(events.contains("event: chunk"));
        Assert.assertTrue(events.contains("\"offsetStart\":0"));
        Assert.assertTrue(events.contains("\"offsetEnd\":4"));
        Assert.assertEquals(4L, returnedOffset);

        tempFile.delete();
    }

    @Test
    public void tryAcquirePairLogStreamSlotHonorsLimit() throws Exception {
        Method acquireMethod = RESTServices.class.getDeclaredMethod("tryAcquirePairLogStreamSlot", int.class);
        acquireMethod.setAccessible(true);
        Method releaseMethod = RESTServices.class.getDeclaredMethod("releasePairLogStreamSlot");
        releaseMethod.setAccessible(true);

        boolean first = (Boolean) acquireMethod.invoke(null, 1);
        boolean second = (Boolean) acquireMethod.invoke(null, 1);

        Assert.assertTrue(first);
        Assert.assertFalse(second);

        releaseMethod.invoke(null);
        boolean third = (Boolean) acquireMethod.invoke(null, 1);
        Assert.assertTrue(third);

        releaseMethod.invoke(null);
    }

    @Test
    public void streamAvailableBytesReturnsSameOffsetWhenNoGrowth() throws Exception {
        File tempFile = File.createTempFile("pair-log-stream", ".txt");
        Files.write(tempFile.toPath(), "abcdef".getBytes(StandardCharsets.UTF_8));

        Method streamMethod = RESTServices.class.getDeclaredMethod(
                "streamAvailableBytes",
                int.class,
                java.io.OutputStream.class,
                File.class,
                long.class,
                int.class
        );
        streamMethod.setAccessible(true);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long returnedOffset = (Long) streamMethod.invoke(null, 42, output, tempFile, tempFile.length(), 1024);
        String events = output.toString(StandardCharsets.UTF_8.name());

        Assert.assertEquals(tempFile.length(), returnedOffset);
        Assert.assertTrue(events.isEmpty());

        tempFile.delete();
    }

    @Test
    public void writeSseEventWithIdProducesExpectedFrame() throws Exception {
        Method writeEventMethod = RESTServices.class.getDeclaredMethod(
                "writeSseEventWithId",
                java.io.OutputStream.class,
                String.class,
                String.class,
                String.class
        );
        writeEventMethod.setAccessible(true);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean success = (Boolean) writeEventMethod.invoke(
                null,
                output,
                "99",
                "chunk",
                "{\"k\":\"v\"}"
        );

        String frame = output.toString(StandardCharsets.UTF_8.name());
        Assert.assertTrue(success);
        Assert.assertTrue(frame.contains("id: 99\n"));
        Assert.assertTrue(frame.contains("event: chunk\n"));
        Assert.assertTrue(frame.contains("data: {\"k\":\"v\"}\n\n"));
    }

    @Test
    public void streamPairLogEventsEmitsErrorWhenLogPathMissing() throws Exception {
        Method streamEventsMethod = RESTServices.class.getDeclaredMethod(
                "streamPairLogEvents",
                int.class,
                java.io.OutputStream.class,
                String.class
        );
        streamEventsMethod.setAccessible(true);

        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock.when(() -> JobPairs.getLogPath(123)).thenReturn(null);

            streamEventsMethod.invoke(null, 123, output, null);
        }

        String payload = output.toString(StandardCharsets.UTF_8.name());
        Assert.assertTrue(payload.contains("event: error"));
        Assert.assertTrue(payload.contains("\"code\":\"NOT_AVAILABLE\""));
    }

    @Test
    public void streamPairLogEventsEmitsCompleteForTerminalPair() throws Exception {
        Method streamEventsMethod = RESTServices.class.getDeclaredMethod(
                "streamPairLogEvents",
                int.class,
                java.io.OutputStream.class,
                String.class
        );
        streamEventsMethod.setAccessible(true);

        File tempFile = File.createTempFile("pair-log-stream", ".txt");
        Files.write(tempFile.toPath(), "abc".getBytes(StandardCharsets.UTF_8));

        JobPair terminalPair = new JobPair();
        Status status = new Status();
        status.setCode(Status.StatusCode.STATUS_COMPLETE);
        terminalPair.setStatus(status);

        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class)) {
            jobPairsMock.when(() -> JobPairs.getLogPath(123)).thenReturn(tempFile.getAbsolutePath());
            jobPairsMock.when(() -> JobPairs.getPair(123)).thenReturn(terminalPair);

            streamEventsMethod.invoke(null, 123, output, null);
        }

        String payload = output.toString(StandardCharsets.UTF_8.name());
        Assert.assertTrue(payload.contains("event: complete"));

        tempFile.delete();
    }

    @Test
    public void streamJobPairLogAsyncClosesSinkForTerminalPair() throws Exception {
        RESTServices services = new RESTServices();
        File tempFile = File.createTempFile("pair-log-stream", ".txt");
        Files.write(tempFile.toPath(), "abc".getBytes(StandardCharsets.UTF_8));

        JobPair terminalPair = new JobPair();
        Status status = new Status();
        status.setCode(Status.StatusCode.STATUS_COMPLETE);
        terminalPair.setStatus(status);

        SseEventSink eventSink = Mockito.mock(SseEventSink.class);
        Sse sse = Mockito.mock(Sse.class);
        OutboundSseEvent.Builder builder = Mockito.mock(OutboundSseEvent.Builder.class, Answers.RETURNS_SELF);
        OutboundSseEvent event = Mockito.mock(OutboundSseEvent.class);
        QueuingScheduledExecutorService queueExecutor = new QueuingScheduledExecutorService();

        Mockito.when(sse.newEventBuilder()).thenReturn(builder);
        Mockito.when(builder.build()).thenReturn(event);
        Mockito.when(eventSink.send(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));

        Method setExecutorMethod = RESTServices.class.getDeclaredMethod(
                "setPairLogStreamExecutorForTesting",
                java.util.concurrent.ScheduledExecutorService.class
        );
        setExecutorMethod.setAccessible(true);
        Method resetExecutorMethod = RESTServices.class.getDeclaredMethod("resetPairLogStreamExecutorForTesting");
        resetExecutorMethod.setAccessible(true);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class);
             MockedStatic<JobSecurity> jobSecurityMock = Mockito.mockStatic(JobSecurity.class);
             MockedStatic<EnvironmentConfig> configMock = Mockito.mockStatic(EnvironmentConfig.class)) {
            setExecutorMethod.invoke(null, queueExecutor);
            jobPairsMock.when(() -> JobPairs.getLogPath(123)).thenReturn(tempFile.getAbsolutePath());
            jobPairsMock.when(() -> JobPairs.getPair(123)).thenReturn(terminalPair);
            jobSecurityMock.when(() -> JobSecurity.canUserSeeJobWithPair(Mockito.anyInt(), Mockito.anyInt()))
                    .thenReturn(new ValidatorStatusCode(true));
            configMock.when(EnvironmentConfig::isPairLogStreamEnabled).thenReturn(true);
            configMock.when(EnvironmentConfig::getPairLogStreamMaxActive).thenReturn(1);
            configMock.when(EnvironmentConfig::getPairLogStreamHeartbeatSeconds).thenReturn(Long.MAX_VALUE);

            services.streamJobPairLog(123, TestUtil.getMockHttpRequest(7), eventSink, sse);

            Runnable task = queueExecutor.pollTask();
            Assert.assertNotNull("Expected the stream task to be queued", task);
            task.run();

            Mockito.verify(eventSink, Mockito.atLeast(1)).send(Mockito.any());
            Mockito.verify(eventSink).close();
            Assert.assertTrue("Expected no follow-up task for terminal pair", queueExecutor.isEmpty());
        } finally {
            queueExecutor.shutdownNow();
            resetExecutorMethod.invoke(null);
        }

        tempFile.delete();
    }

    @Test
    public void streamJobPairLogAsyncEmitsErrorWhenLogPathMissing() throws Exception {
        // [REVIEW-FIX] Async path must match legacy behaviour — immediate NOT_AVAILABLE.
        RESTServices services = new RESTServices();

        SseEventSink eventSink = Mockito.mock(SseEventSink.class);
        Sse sse = Mockito.mock(Sse.class);
        OutboundSseEvent.Builder builder = Mockito.mock(OutboundSseEvent.Builder.class, Answers.RETURNS_SELF);
        OutboundSseEvent event = Mockito.mock(OutboundSseEvent.class);
        QueuingScheduledExecutorService queueExecutor = new QueuingScheduledExecutorService();

        Mockito.when(sse.newEventBuilder()).thenReturn(builder);
        Mockito.when(builder.build()).thenReturn(event);
        Mockito.when(eventSink.send(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));

        Method setExecutorMethod = RESTServices.class.getDeclaredMethod(
                "setPairLogStreamExecutorForTesting",
                java.util.concurrent.ScheduledExecutorService.class);
        setExecutorMethod.setAccessible(true);
        Method resetExecutorMethod = RESTServices.class.getDeclaredMethod("resetPairLogStreamExecutorForTesting");
        resetExecutorMethod.setAccessible(true);

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class);
             MockedStatic<JobSecurity> jobSecurityMock = Mockito.mockStatic(JobSecurity.class);
             MockedStatic<EnvironmentConfig> configMock = Mockito.mockStatic(EnvironmentConfig.class)) {
            setExecutorMethod.invoke(null, queueExecutor);
            jobPairsMock.when(() -> JobPairs.getLogPath(123)).thenReturn(null);
            jobPairsMock.when(() -> JobPairs.getPair(123)).thenReturn(new JobPair());
            jobSecurityMock.when(() -> JobSecurity.canUserSeeJobWithPair(Mockito.anyInt(), Mockito.anyInt()))
                    .thenReturn(new ValidatorStatusCode(true));
            configMock.when(EnvironmentConfig::isPairLogStreamEnabled).thenReturn(true);
            configMock.when(EnvironmentConfig::getPairLogStreamMaxActive).thenReturn(1);

            services.streamJobPairLog(123, TestUtil.getMockHttpRequest(7), eventSink, sse);

            Runnable task = queueExecutor.pollTask();
            Assert.assertNotNull("Expected the stream task to be queued", task);
            task.run();

            Mockito.verify(eventSink).close();
            Assert.assertTrue("Expected no follow-up task for missing log path", queueExecutor.isEmpty());
        } finally {
            queueExecutor.shutdownNow();
            resetExecutorMethod.invoke(null);
        }
    }

    /**
     * A finished pair's log that spans several chunks must arrive whole, in order, before {@code complete}.
     * Before the fix the terminal path sent one chunk and then {@code complete}, so every log longer than
     * STAREXEC_PAIR_LOG_STREAM_READ_CHUNK_BYTES was cut off.
     */
    @Test
    public void streamPairLogEventsSendsTheWholeLogBeforeCompleteForTerminalPair() throws Exception {
        String log = multiChunkLog();
        LegacyEvents events = runLegacyStreamOfTerminalPair(log.getBytes(StandardCharsets.UTF_8));

        assertStreamedWholeLog(log, events.names, events.data, true);
    }

    /**
     * The legacy wrapper's first poll of a running pair must send everything the log holds: every chunk comes
     * before the heartbeat that ends that poll. Before the fix the first poll sent one chunk, and the rest
     * trickled out one chunk per poll interval. The pair finishes at the next status poll, about a second in.
     */
    @Test
    public void streamPairLogEventsSendsEverythingAvailableInTheFirstPollForRunningPair() throws Exception {
        Method streamEventsMethod = RESTServices.class.getDeclaredMethod(
                "streamPairLogEvents",
                int.class,
                java.io.OutputStream.class,
                String.class
        );
        streamEventsMethod.setAccessible(true);

        String log = multiChunkLog();
        File tempFile = File.createTempFile("pair-log-stream", ".txt");
        Files.write(tempFile.toPath(), log.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class);
             MockedStatic<EnvironmentConfig> configMock = Mockito.mockStatic(EnvironmentConfig.class)) {
            configMock.when(EnvironmentConfig::getPairLogStreamReadChunkBytes).thenReturn(CHUNK);
            configMock.when(EnvironmentConfig::getPairLogStreamMaxDurationSeconds).thenReturn(60L);
            jobPairsMock.when(() -> JobPairs.getLogPath(123)).thenReturn(tempFile.getAbsolutePath());
            jobPairsMock.when(() -> JobPairs.getPair(123)).thenReturn(
                    pairWithStatus(Status.StatusCode.STATUS_RUNNING),
                    pairWithStatus(Status.StatusCode.STATUS_COMPLETE));

            streamEventsMethod.invoke(null, 123, output, null);
        } finally {
            tempFile.delete();
        }

        List<String> events = new ArrayList<>();
        StringBuilder streamed = new StringBuilder();
        for (String frame : output.toString(StandardCharsets.UTF_8.name()).split("\n\n")) {
            if (frame.startsWith(":")) {
                events.add("heartbeat");
            } else if (frame.contains("event: chunk\n")) {
                events.add("chunk");
                String data = frame.substring(frame.indexOf("data: ") + "data: ".length());
                streamed.append(JsonParser.parseString(data).getAsJsonObject().get("text").getAsString());
            } else if (frame.contains("event: ")) {
                events.add(frame.substring(frame.indexOf("event: ") + "event: ".length()).split("\n")[0]);
            }
        }
        Assert.assertEquals(log, streamed.toString());
        Assert.assertTrue("Expected several chunks, got " + events, events.indexOf("chunk") != events.lastIndexOf("chunk"));
        Assert.assertTrue("Expected a heartbeat after the first poll, got " + events, events.contains("heartbeat"));
        Assert.assertTrue("Every chunk must come before the first poll's heartbeat: " + events,
                events.lastIndexOf("chunk") < events.indexOf("heartbeat"));
        Assert.assertEquals("complete", events.get(events.size() - 1));
    }

    /** The live endpoint's session must also send the whole log of a finished pair before {@code complete}. */
    @Test
    public void streamJobPairLogAsyncSendsTheWholeLogBeforeCompleteForTerminalPair() throws Exception {
        String log = multiChunkLog();
        AsyncTick tick = runOneAsyncTick(log, Status.StatusCode.STATUS_COMPLETE);

        assertStreamedWholeLog(log, tick.names, tick.data, true);
        Mockito.verify(tick.eventSink).close();
        Assert.assertFalse("Expected no follow-up task for a terminal pair", tick.followUpQueued);
    }

    /**
     * A running pair's stream must not fall behind its log: one poll sends everything the log holds. Before
     * the fix each poll sent one chunk, so a log growing faster than one chunk per poll interval was never
     * caught up with, and the terminal path then cut it off.
     */
    @Test
    public void streamJobPairLogAsyncSendsEverythingAvailableInOneTickForRunningPair() throws Exception {
        String log = multiChunkLog();
        AsyncTick tick = runOneAsyncTick(log, Status.StatusCode.STATUS_RUNNING);

        assertStreamedWholeLog(log, tick.names, tick.data, false);
        Mockito.verify(tick.eventSink, Mockito.never()).close();
        Assert.assertTrue("Expected the next poll to be scheduled for a running pair", tick.followUpQueued);
    }

    /**
     * A chunk must end on a whole character. Each chunk is decoded on its own, so a character split between
     * two chunks arrived as two U+FFFD, in the copy that replaces the one the pair page rendered.
     */
    @Test
    public void streamJobPairLogAsyncNeverSplitsACharacterBetweenChunks() throws Exception {
        String log = splitCharacterLog();
        AsyncTick tick = runOneAsyncTick(log.getBytes(StandardCharsets.UTF_8), Status.StatusCode.STATUS_COMPLETE);

        assertStreamedWholeLog(log, tick.names, tick.data, true);
    }

    /** The legacy wrapper's chunks must end on whole characters too. */
    @Test
    public void streamPairLogEventsNeverSplitsACharacterBetweenChunks() throws Exception {
        String log = splitCharacterLog();
        LegacyEvents events = runLegacyStreamOfTerminalPair(log.getBytes(StandardCharsets.UTF_8));

        assertStreamedWholeLog(log, events.names, events.data, true);
    }

    /**
     * Bytes that hold no whole character are sent as they are, so that the stream still reaches the end of a
     * log that stops inside a character. Holding them back would never send them.
     */
    @Test
    public void streamJobPairLogAsyncSendsAMalformedEndAsItIs() throws Exception {
        byte[] log = {'a', 'b', 'c', (byte) 0xC3};
        AsyncTick tick = runOneAsyncTick(log, Status.StatusCode.STATUS_COMPLETE);

        Assert.assertEquals(List.of("chunk", "chunk", "complete"), tick.names);
        JsonObject whole = JsonParser.parseString(tick.data.get(0)).getAsJsonObject();
        JsonObject rest = JsonParser.parseString(tick.data.get(1)).getAsJsonObject();
        Assert.assertEquals("abc", whole.get("text").getAsString());
        Assert.assertEquals(3L, whole.get("offsetEnd").getAsLong());
        Assert.assertEquals("\uFFFD", rest.get("text").getAsString());
        Assert.assertEquals(4L, rest.get("offsetEnd").getAsLong());
    }

    /**
     * One tick sends at most the cap's chunks: each send waits for the client, and the session shares its
     * worker threads with every other stream. A finished pair's stream the cap leaves behind its log ticks
     * again at once, without reading the pair's status again, and sends complete only after the last byte.
     */
    @Test
    public void streamJobPairLogAsyncSendsALongFinishedLogOverSeveralTicks() throws Exception {
        int cap = maxChunksPerPoll();
        String log = longerThanOnePollLog(cap);
        // The pause outlasts the session's shortest status poll interval (1 s), so a second read would happen.
        AsyncTick ticks = runAsyncTicks(log.getBytes(StandardCharsets.UTF_8), Status.StatusCode.STATUS_COMPLETE, 5,
                1100L);

        Assert.assertEquals("the first tick sends the cap's chunks and no complete", Long.valueOf(cap),
                ticks.eventsAfterTick.get(0));
        Assert.assertEquals("a stream behind its log ticks again at once", Long.valueOf(0L),
                ticks.scheduleDelays.get(1));
        Assert.assertTrue("Expected several ticks, got " + ticks.eventsAfterTick, ticks.eventsAfterTick.size() > 1);
        Assert.assertEquals("a finished pair's status is not read again after the first tick",
                ticks.statusReadsAfterTick.get(0),
                ticks.statusReadsAfterTick.get(ticks.statusReadsAfterTick.size() - 1));
        assertStreamedWholeLog(log, ticks.names, ticks.data, true);
        Mockito.verify(ticks.eventSink).close();
        Assert.assertFalse("Expected no tick after complete", ticks.followUpQueued);
    }

    /** The legacy wrapper sends a long finished log one capped poll at a time too, and then complete. */
    @Test
    public void streamPairLogEventsSendsALongFinishedLogOverSeveralPolls() throws Exception {
        int cap = maxChunksPerPoll();
        String log = longerThanOnePollLog(cap);
        LegacyEvents events = runLegacyStreamOfTerminalPair(log.getBytes(StandardCharsets.UTF_8));

        assertStreamedWholeLog(log, events.names, events.data, true);
        Assert.assertEquals("the first poll's heartbeat must follow exactly the cap's chunks: " + events.order,
                cap, events.order.indexOf("heartbeat"));
    }

    /**
     * A tick schedules the next one only once it has released its guard against overlapping ticks. A pool
     * thread can start a tick that is due at once before the tick that scheduled it has returned, and a tick
     * that found the guard still held would put itself off by a whole poll interval.
     */
    @Test
    public void streamJobPairLogAsyncCatchUpTickThatStartsAtOnceIsNotPutOff() throws Exception {
        String log = longerThanOnePollLog(maxChunksPerPoll());
        AsyncTick ticks = runAsyncTicks(log.getBytes(StandardCharsets.UTF_8), Status.StatusCode.STATUS_COMPLETE, 1,
                0L, new QueuingScheduledExecutorService(true));

        Assert.assertFalse("Expected no tick put off by a poll interval; delays were " + ticks.scheduleDelays,
                ticks.followUpQueued);
        assertStreamedWholeLog(log, ticks.names, ticks.data, true);
        Mockito.verify(ticks.eventSink).close();
    }

    /**
     * A tick that sends nothing is not behind its log, even when the log's size says there is more: a size can be
     * stale, as NFS caches it, and ticking again at once would then spin until it changed. A sysfs attribute plays
     * that log, since its size is a page however little it holds.
     */
    @Test
    public void streamJobPairLogAsyncDoesNotTickAtOnceAfterSendingNothing() throws Exception {
        File attribute = new File("/sys/devices/system/cpu/online");
        Assume.assumeTrue("needs a Linux sysfs attribute",
                attribute.isFile() && Files.readAllBytes(attribute.toPath()).length < attribute.length());
        AsyncTick ticks = runAsyncTicks(attribute, Status.StatusCode.STATUS_RUNNING, 2, 0L,
                new QueuingScheduledExecutorService());

        // The first tick, the catch-up tick its chunk earns, and then the poll interval's floor of 100 ms,
        // since the test leaves the interval unset.
        Assert.assertEquals(Arrays.asList(0L, 0L, 100L), ticks.scheduleDelays);
    }

    private static int maxChunksPerPoll() throws Exception {
        Field cap = RESTServices.class.getDeclaredField("PAIR_LOG_STREAM_MAX_CHUNKS_PER_POLL");
        cap.setAccessible(true);
        return cap.getInt(null);
    }

    /** An ASCII log of more chunks than one poll sends. */
    private static String longerThanOnePollLog(int cap) {
        StringBuilder log = new StringBuilder();
        for (int line = 1; log.length() < CHUNK * (cap + 8); line++) {
            log.append(String.format("line %05d of the pair log\n", line));
        }
        return log.toString();
    }

    /** The chunk size the drain tests force: the smallest one the stream accepts. */
    private static final int CHUNK = 256;

    /**
     * A log in which é (2 bytes), 中 (3 bytes) and 😀 (4 bytes) each straddle the end of a read of
     * {@link #CHUNK} bytes, once every chunk before it ends on a whole character: é at bytes 255-256,
     * 中 at 765-767 and 😀 at 1018-1021. The read cuts 中 after E4 B8, so the bytes held back include a
     * continuation byte from 0xA0-0xBF as well as ones from 0x80-0x9F.
     */
    private static String splitCharacterLog() {
        return "a".repeat(255) + "\u00E9" + "b".repeat(254) + "c".repeat(254) + "\u4E2D" + "d".repeat(250)
                + "\uD83D\uDE00" + "e".repeat(300) + "\n";
    }

    /** An ASCII log of about eight chunks, so byte offsets equal character offsets. */
    private static String multiChunkLog() {
        StringBuilder log = new StringBuilder();
        for (int line = 1; log.length() < CHUNK * 7 + 100; line++) {
            log.append(String.format("line %05d of the pair log\n", line));
        }
        return log.toString();
    }

    private static JobPair pairWithStatus(Status.StatusCode code) {
        JobPair pair = new JobPair();
        Status status = new Status();
        status.setCode(code);
        pair.setStatus(status);
        return pair;
    }

    /**
     * Asserts that the events are contiguous chunks of at most {@link #CHUNK} bytes whose text is exactly
     * {@code log}, followed by {@code complete} when {@code expectComplete}, and by nothing otherwise.
     */
    private static void assertStreamedWholeLog(String log, List<String> names, List<String> data,
                                               boolean expectComplete) {
        int chunks = expectComplete ? names.size() - 1 : names.size();
        Assert.assertTrue("Expected the log to need several chunks, got events " + names, chunks > 1);
        if (expectComplete) {
            Assert.assertEquals("complete must follow the last chunk: " + names, "complete",
                    names.get(names.size() - 1));
        }
        StringBuilder streamed = new StringBuilder();
        long expectedStart = 0L;
        for (int i = 0; i < chunks; i++) {
            Assert.assertEquals("event " + i + " of " + names, "chunk", names.get(i));
            JsonObject chunk = JsonParser.parseString(data.get(i)).getAsJsonObject();
            long start = chunk.get("offsetStart").getAsLong();
            long end = chunk.get("offsetEnd").getAsLong();
            Assert.assertEquals("chunk " + i + " must start where the previous one ended", expectedStart, start);
            Assert.assertTrue("chunk " + i + " must be at most one chunk long", end - start <= CHUNK);
            streamed.append(chunk.get("text").getAsString());
            expectedStart = end;
        }
        Assert.assertEquals("the chunks must cover the whole log", log.getBytes(StandardCharsets.UTF_8).length,
                expectedStart);
        Assert.assertEquals(log, streamed.toString());
    }

    /** What the ticks of the live endpoint's stream session sent. */
    private static final class AsyncTick {
        private final SseEventSink eventSink;
        private final List<String> names;
        private final List<String> data;
        private final boolean followUpQueued;
        /** How many events had been sent when each tick ended. */
        private final List<Long> eventsAfterTick;
        /** The delay, in milliseconds, of every tick the session scheduled, the first one included. */
        private final List<Long> scheduleDelays;
        /** How often the pair's status had been read when each tick ended, the endpoint's own read included. */
        private final List<Integer> statusReadsAfterTick;

        private AsyncTick(SseEventSink eventSink, List<String> names, List<String> data, boolean followUpQueued,
                          List<Long> eventsAfterTick, List<Long> scheduleDelays, List<Integer> statusReadsAfterTick) {
            this.eventSink = eventSink;
            this.names = names;
            this.data = data;
            this.followUpQueued = followUpQueued;
            this.eventsAfterTick = eventsAfterTick;
            this.scheduleDelays = scheduleDelays;
            this.statusReadsAfterTick = statusReadsAfterTick;
        }
    }

    private static AsyncTick runOneAsyncTick(String log, Status.StatusCode code) throws Exception {
        return runOneAsyncTick(log.getBytes(StandardCharsets.UTF_8), code);
    }

    /** Opens the live endpoint for pair 123 with {@code log} as its log file and runs exactly one tick. */
    private static AsyncTick runOneAsyncTick(byte[] log, Status.StatusCode code) throws Exception {
        return runAsyncTicks(log, code, 1, 0L);
    }

    /**
     * Opens the live endpoint for pair 123 with {@code log} as its log file and runs its ticks, at most
     * {@code maxTicks}, until it schedules no more, waiting {@code pauseMillis} before each tick after the first.
     */
    private static AsyncTick runAsyncTicks(byte[] log, Status.StatusCode code, int maxTicks, long pauseMillis)
            throws Exception {
        return runAsyncTicks(log, code, maxTicks, pauseMillis, new QueuingScheduledExecutorService());
    }

    /** As above, with the ticks scheduled on {@code queueExecutor}. */
    private static AsyncTick runAsyncTicks(byte[] log, Status.StatusCode code, int maxTicks, long pauseMillis,
                                           QueuingScheduledExecutorService queueExecutor) throws Exception {
        File tempFile = File.createTempFile("pair-log-stream", ".txt");
        try {
            Files.write(tempFile.toPath(), log);
            return runAsyncTicks(tempFile, code, maxTicks, pauseMillis, queueExecutor);
        } finally {
            tempFile.delete();
        }
    }

    /** As above, with {@code logFile} itself as the pair's log. */
    private static AsyncTick runAsyncTicks(File logFile, Status.StatusCode code, int maxTicks, long pauseMillis,
                                           QueuingScheduledExecutorService queueExecutor) throws Exception {
        RESTServices services = new RESTServices();

        SseEventSink eventSink = Mockito.mock(SseEventSink.class);
        Sse sse = Mockito.mock(Sse.class);
        OutboundSseEvent.Builder builder = Mockito.mock(OutboundSseEvent.Builder.class, Answers.RETURNS_SELF);
        OutboundSseEvent event = Mockito.mock(OutboundSseEvent.class);

        Mockito.when(sse.newEventBuilder()).thenReturn(builder);
        Mockito.when(builder.build()).thenReturn(event);
        Mockito.when(eventSink.send(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));

        Method setExecutorMethod = RESTServices.class.getDeclaredMethod(
                "setPairLogStreamExecutorForTesting",
                java.util.concurrent.ScheduledExecutorService.class);
        setExecutorMethod.setAccessible(true);
        Method resetExecutorMethod = RESTServices.class.getDeclaredMethod("resetPairLogStreamExecutorForTesting");
        resetExecutorMethod.setAccessible(true);

        boolean followUpQueued;
        List<Long> eventsAfterTick = new ArrayList<>();
        List<Integer> statusReadsAfterTick = new ArrayList<>();
        AtomicInteger statusReads = new AtomicInteger();
        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class);
             MockedStatic<JobSecurity> jobSecurityMock = Mockito.mockStatic(JobSecurity.class);
             MockedStatic<EnvironmentConfig> configMock = Mockito.mockStatic(EnvironmentConfig.class)) {
            setExecutorMethod.invoke(null, queueExecutor);
            jobPairsMock.when(() -> JobPairs.getLogPath(123)).thenReturn(logFile.getAbsolutePath());
            jobPairsMock.when(() -> JobPairs.getPair(123)).thenAnswer(invocation -> {
                statusReads.incrementAndGet();
                return pairWithStatus(code);
            });
            jobSecurityMock.when(() -> JobSecurity.canUserSeeJobWithPair(Mockito.anyInt(), Mockito.anyInt()))
                    .thenReturn(new ValidatorStatusCode(true));
            configMock.when(EnvironmentConfig::isPairLogStreamEnabled).thenReturn(true);
            configMock.when(EnvironmentConfig::getPairLogStreamMaxActive).thenReturn(1);
            configMock.when(EnvironmentConfig::getPairLogStreamReadChunkBytes).thenReturn(CHUNK);
            configMock.when(EnvironmentConfig::getPairLogStreamMaxDurationSeconds).thenReturn(60L);

            services.streamJobPairLog(123, TestUtil.getMockHttpRequest(7), eventSink, sse);

            for (int tick = 1; tick <= maxTicks; tick++) {
                if (tick > 1) {
                    Thread.sleep(pauseMillis);
                }
                Runnable task = queueExecutor.pollTask();
                Assert.assertNotNull("Expected tick " + tick + " of the stream to be queued", task);
                task.run();
                eventsAfterTick.add(Mockito.mockingDetails(builder).getInvocations().stream()
                        .filter(invocation -> invocation.getMethod().getName().equals("name")).count());
                statusReadsAfterTick.add(statusReads.get());
                if (queueExecutor.isEmpty()) {
                    break;
                }
            }
            followUpQueued = !queueExecutor.isEmpty();
        } finally {
            queueExecutor.shutdownNow();
            resetExecutorMethod.invoke(null);
        }

        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(builder, Mockito.atLeastOnce()).name(names.capture());
        Mockito.verify(builder, Mockito.atLeastOnce()).data(Mockito.eq(String.class), data.capture());
        List<String> dataText = new ArrayList<>();
        for (Object value : data.getAllValues()) {
            dataText.add((String) value);
        }
        return new AsyncTick(eventSink, names.getAllValues(), dataText, followUpQueued, eventsAfterTick,
                queueExecutor.delays, statusReadsAfterTick);
    }

    private static final class QueuingScheduledExecutorService extends AbstractExecutorService implements ScheduledExecutorService {
        private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        private final AtomicBoolean shutdown = new AtomicBoolean(false);
        private final List<Long> delays = Collections.synchronizedList(new ArrayList<>());
        /**
         * Runs a task due at once inside the schedule call, when a task this executor handed out schedules it,
         * the way an idle pool thread can start that task before the one that scheduled it has returned.
         */
        private final boolean startsDueTasksAtOnce;
        private volatile boolean inTask;

        private QueuingScheduledExecutorService() {
            this(false);
        }

        private QueuingScheduledExecutorService(boolean startsDueTasksAtOnce) {
            this.startsDueTasksAtOnce = startsDueTasksAtOnce;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            if (shutdown.get()) {
                throw new RejectedExecutionException("Executor has been shut down");
            }
            delays.add(unit.toMillis(delay));
            if (startsDueTasksAtOnce && inTask && delay == 0L) {
                command.run();
                return null;
            }
            tasks.add(command);
            return null; // test doesn't need cancellation
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
            shutdown.set(true);
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown.set(true);
            List<Runnable> pending = new ArrayList<>();
            tasks.drainTo(pending);
            return pending;
        }

        @Override
        public boolean isShutdown() {
            return shutdown.get();
        }

        @Override
        public boolean isTerminated() {
            return shutdown.get() && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (!isTerminated() && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }
            return isTerminated();
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown.get()) {
                throw new RejectedExecutionException("Executor has been shut down");
            }
            tasks.add(command);
        }

        private Runnable pollTask() throws InterruptedException {
            Runnable task = tasks.poll(5, TimeUnit.SECONDS);
            if (task == null) {
                return null;
            }
            return () -> {
                inTask = true;
                try {
                    task.run();
                } finally {
                    inTask = false;
                }
            };
        }

        private boolean isEmpty() {
            return tasks.isEmpty();
        }
    }

    /** The events the legacy wrapper wrote, by name, with each one's data. */
    private static final class LegacyEvents {
        private final List<String> names = new ArrayList<>();
        private final List<String> data = new ArrayList<>();
        /** Every frame in order: an event's name, or "heartbeat" for a comment. */
        private final List<String> order = new ArrayList<>();
    }

    /** Streams {@code log} through the legacy wrapper for a finished pair 123 and parses the frames. */
    private static LegacyEvents runLegacyStreamOfTerminalPair(byte[] log) throws Exception {
        Method streamEventsMethod = RESTServices.class.getDeclaredMethod(
                "streamPairLogEvents",
                int.class,
                java.io.OutputStream.class,
                String.class
        );
        streamEventsMethod.setAccessible(true);

        File tempFile = File.createTempFile("pair-log-stream", ".txt");
        Files.write(tempFile.toPath(), log);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (MockedStatic<JobPairs> jobPairsMock = Mockito.mockStatic(JobPairs.class);
             MockedStatic<EnvironmentConfig> configMock = Mockito.mockStatic(EnvironmentConfig.class)) {
            configMock.when(EnvironmentConfig::getPairLogStreamReadChunkBytes).thenReturn(CHUNK);
            configMock.when(EnvironmentConfig::getPairLogStreamMaxDurationSeconds).thenReturn(60L);
            jobPairsMock.when(() -> JobPairs.getLogPath(123)).thenReturn(tempFile.getAbsolutePath());
            jobPairsMock.when(() -> JobPairs.getPair(123)).thenReturn(pairWithStatus(Status.StatusCode.STATUS_COMPLETE));

            streamEventsMethod.invoke(null, 123, output, null);
        } finally {
            tempFile.delete();
        }

        LegacyEvents events = new LegacyEvents();
        for (String frame : output.toString(StandardCharsets.UTF_8.name()).split("\n\n")) {
            if (frame.startsWith(":")) {
                events.order.add("heartbeat");
                continue;
            }
            String name = null;
            String payload = null;
            for (String line : frame.split("\n")) {
                if (line.startsWith("event: ")) {
                    name = line.substring("event: ".length());
                } else if (line.startsWith("data: ")) {
                    payload = line.substring("data: ".length());
                }
            }
            if (name != null) {
                events.names.add(name);
                events.data.add(payload);
                events.order.add(name);
            }
        }
        return events;
    }
}
