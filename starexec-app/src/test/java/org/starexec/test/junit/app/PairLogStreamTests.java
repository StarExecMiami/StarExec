package org.starexec.test.junit.app;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.app.RESTServices;
import org.starexec.data.database.JobPairs;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.Status;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

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
}
