package org.starexec.test.unit.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.starexec.test.TemporaryFolders.newFolder;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;

/**
 * Guards where the production {@code logback.xml} writes the application log file.
 *
 * <p>The file was configured at build time as {@code /usr/local/tomcat/logs/starexec.log}, a
 * path that does not exist in the image: Tomcat lives in {@code /opt/tomcat}. Every start logged
 * a {@code FileNotFoundException} and the file was never written, while the image creates
 * {@code /var/log/starexec} for it and the observability and security guides send operators
 * there. The location is now resolved at runtime from {@code STAREXEC_LOG_DIR}.
 *
 * <p>These tests load the copy Maven filters onto the classpath, not {@code logback-test.xml},
 * each into a private {@link LoggerContext} so the suite's own logging is untouched.
 */
public class ProductionLogFileTest {

    @TempDir
    public File tmp;

    private static URL productionConfig() {
        URL config = ProductionLogFileTest.class.getResource("/logback.xml");
        assertNotNull(config, "the production logback.xml must be on the test classpath");
        return config;
    }

    private static LoggerContext configure(String logDir) throws Exception {
        LoggerContext context = new LoggerContext();
        if (logDir != null) {
            context.putProperty("STAREXEC_LOG_DIR", logDir);
        }
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(productionConfig());
        return context;
    }

    private static RollingFileAppender<?> fileAppender(LoggerContext context) {
        Appender<?> appender = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("STAREXEC");
        assertTrue(appender instanceof RollingFileAppender,
                "STAREXEC must be a rolling file appender: " + appender);
        return (RollingFileAppender<?>) appender;
    }

    @Test
    public void theFileIsWrittenUnderStarexecLogDir() throws Exception {
        File dir = newFolder(tmp, "logs");
        File expected = new File(dir, "starexec.log");
        LoggerContext context = configure(dir.getAbsolutePath());
        try {
            assertEquals(expected.getAbsolutePath(), fileAppender(context).getFile());
            context.getLogger("org.starexec.test").info("written by ProductionLogFileTest");
            assertTrue(expected.isFile(), "the appender must have created its file");
        } finally {
            context.stop();
        }
    }

    @Test
    public void withoutTheVariableItUsesTheDirectoryTheImageCreates() throws Exception {
        assumeTrue(System.getenv("STAREXEC_LOG_DIR") == null,
                "STAREXEC_LOG_DIR is set in this environment and would take precedence");
        LoggerContext context = configure(null);
        try {
            assertEquals("/var/log/starexec/starexec.log", context.getProperty("STAREXEC_LOG_FILE"));
        } finally {
            context.stop();
        }
    }

    /**
     * The file sits in the container's writable layer and {@code org.starexec} logs at DEBUG,
     * so rotation by age alone has no ceiling on disk use.
     */
    @Test
    public void rotationIsBoundedBySizeAsWellAsAge() throws Exception {
        LoggerContext context = configure(newFolder(tmp, "bounded").getAbsolutePath());
        try {
            Object policy = fileAppender(context).getRollingPolicy();
            assertTrue(policy instanceof SizeAndTimeBasedRollingPolicy,
                    "rotation must cap size, not only age: " + policy);
        } finally {
            context.stop();
        }
        String text;
        try (InputStream in = productionConfig().openStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(text.contains("<totalSizeCap>"), "logback.xml must cap the total size of the archive");
        assertTrue(text.contains("<maxFileSize>"), "logback.xml must cap the size of each file");
    }
}
