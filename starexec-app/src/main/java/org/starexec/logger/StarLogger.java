package org.starexec.logger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.starexec.data.database.ErrorLogs;
import org.starexec.util.Util;

import java.util.Optional;

/**
 * The application's logger, which also PERSISTS warnings and errors to the database.
 *
 * <h2>Choosing WARN or ERROR chooses a database write</h2>
 *
 * <p>{@link #log} routes every WARN and ERROR through {@code reportError} into
 * {@code ErrorLogs.add}, which opens a connection and inserts an {@code error_logs} row. That is
 * deliberate -- it is how an operator finds problems without shell access to the logs -- but it
 * is invisible at the call site: nothing about {@code log.warn("...")} suggests a round-trip,
 * and there is no dedup or rate limiting on the insert.
 *
 * <p>So: <b>prefer INFO for anything that can fire per poll, per pair or per file.</b> INFO and
 * DEBUG are not persisted. A WARN inside a monitor's poll loop, a per-file loop, or a retry path
 * is one insert per iteration, and it will keep inserting for as long as the condition lasts.
 *
 * <p>The corollary matters for design, not just performance: a class documented as holding no
 * database connection cannot log at WARN. If such a class needs to report something, it should
 * return it and let a caller that may touch the database do the logging -- as
 * {@code StageStatusSnapshots} does with {@code lastContradiction()}.
 */
public class StarLogger extends BaseStarLogger {

    private StarLogger(Class<?> clazz) { super(clazz); }
    private StarLogger(String name) { super(name); }
    private StarLogger(Logger logger) { super(logger); }

    public static StarLogger getLogger(Class<?> clazz) { return new StarLogger(clazz); }
    public static StarLogger getLogger(String name) { return new StarLogger(name); }
    public static StarLogger getRootLogger() { return new StarLogger(LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)); }

    @Override
    protected void log(StarLevel level, final String method, final String message, final Throwable t) {
        String msg = getMessage(method, message, t);
        sendToLogger(level, msg, t);
        if (level == StarLevel.ERROR || level == StarLevel.WARN) {
            reportError(level, msg, t);
        }
    }

    private void reportError(final StarLevel level, final String message, final Throwable t) {
        String messageAndTrace = message == null ? "<no message>" : message;
        messageAndTrace = "(" + log.getName() + ") - " + messageAndTrace;
        if (t != null) {
            messageAndTrace += "\nStack Trace:\n" + Util.getStackTrace(t);
        }
        Optional<Integer> logId = ErrorLogs.add(messageAndTrace, level);
        if (!logId.isPresent()) {
            log.error("Failed to add error message to logs.");
        }
    }
}
