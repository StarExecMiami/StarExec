package org.starexec.logger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class LogRecordBoundaryTest {
    private LoggerContext context;
    private ListAppender<ILoggingEvent> events;
    private TestLogger logger;

    @BeforeEach
    void setUp() {
        context = new LoggerContext();
        var target = context.getLogger(LogRecordBoundaryTest.class);
        target.setLevel(Level.TRACE);
        target.setAdditive(false);
        events = new ListAppender<>();
        events.setContext(context);
        events.start();
        target.addAppender(events);
        logger = new TestLogger(target);
    }

    @AfterEach
    void stopContext() {
        context.stop();
    }

    @ParameterizedTest
    @EnumSource(value = StarLevel.class, names = "OFF", mode = EnumSource.Mode.EXCLUDE)
    void callerTextCannotCreateAnotherLogLine(StarLevel level) {
        logger.log(level, "upload\r\nforged-method", "input\r\nforged-event", null);
        assertEquals(1, events.list.size());
        assertEquals("upload\\r\\nforged-method - input\\r\\nforged-event", message());
    }

    @ParameterizedTest
    @EnumSource(value = StarLevel.class, names = "OFF", mode = EnumSource.Mode.EXCLUDE)
    void encodingPreservesTheOriginalThrowable(StarLevel level) {
        var failure = new IllegalArgumentException("request\r\nforged-event");
        logger.log(level, null, null, failure);
        assertEquals(1, events.list.size());
        assertEquals("request\\r\\nforged-event", message());
        assertSame(failure, ((ThrowableProxy) events.list.get(0).getThrowableProxy()).getThrowable());
    }

    @Test
    void entryAndExitEncodeCallerMethodNames() {
        logger.entry("upload\nforged");
        logger.exit("upload\rforged");
        assertEquals(2, events.list.size());
        assertEquals("upload\\nforged - Entering method upload\\nforged.", events.list.get(0).getFormattedMessage());
        assertEquals("upload\\rforged - Leaving method upload\\rforged.", events.list.get(1).getFormattedMessage());
    }

    @Test
    void ordinaryMessageFormattingIsUnchanged() {
        logger.info("upload", "  accepted\tinput  ");
        assertEquals("upload - accepted\tinput", message());
    }

    @Test
    void absentMessageStillUsesTheExistingFallback() {
        logger.info((String) null);
        assertEquals("<no message>", message());
    }

    @Test
    void offStillProducesNoEvent() {
        logger.log(StarLevel.OFF, "upload", "ignored\ntext", null);
        assertTrue(events.list.isEmpty());
    }

    private String message() {
        return events.list.get(0).getFormattedMessage();
    }

    private static final class TestLogger extends BaseStarLogger {
        private TestLogger(org.slf4j.Logger target) {
            super(target);
        }

        @Override
        protected void log(StarLevel level, String method, String message, Throwable failure) {
            sendToLogger(level, getMessage(method, message, failure), failure);
        }
    }
}
