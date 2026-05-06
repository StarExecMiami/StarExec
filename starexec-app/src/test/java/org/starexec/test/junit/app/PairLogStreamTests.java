package org.starexec.test.junit.app;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Answers;
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
    public void resetStreamCounter() throws Exception {
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

    private static final class QueuingScheduledExecutorService extends AbstractExecutorService implements ScheduledExecutorService {
        private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        private final AtomicBoolean shutdown = new AtomicBoolean(false);

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            if (shutdown.get()) {
                throw new RejectedExecutionException("Executor has been shut down");
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
            return tasks.poll(5, TimeUnit.SECONDS);
        }

        private boolean isEmpty() {
            return tasks.isEmpty();
        }
    }
}
