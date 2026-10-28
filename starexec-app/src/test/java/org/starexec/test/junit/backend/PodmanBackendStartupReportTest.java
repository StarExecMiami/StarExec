package org.starexec.test.junit.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.read.ListAppender;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.VersionCmd;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.core.DockerClientImpl;
import java.net.ConnectException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.starexec.backend.PodmanBackend;

/**
 * What {@code PodmanBackend.initialize()} logs and throws must match how far it got.
 *
 * <p>Operators decide whether the Podman backend started from its start-up log, and
 * {@code BackendInitializer} logs the exception message when it did not. The backend logged
 * "PodmanBackend initialized successfully" as soon as the container engine answered, before
 * the image check, CPU partitions, queues, reconciliation and the job monitor, and it reported
 * any failure in those steps as "Could not connect to the container engine", with advice
 * about the socket. Both sent operators to the wrong place.
 *
 * <p>The engine is a Mockito double returned from {@code DockerClientImpl.getInstance}, the
 * factory {@code createDockerClient()} ends with, so no engine is needed.
 */
public class PodmanBackendStartupReportTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    public void captureLog() {
        logger = (Logger) LoggerFactory.getLogger(PodmanBackend.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    public void releaseLog() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @Timeout(value = 30_000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void aFailureAfterTheEngineAnsweredIsNotReportedAsAConnectionFailure() {
        DockerClient engine = engineAnswering("5.2.1");
        InspectImageCmd inspect = mock(InspectImageCmd.class);
        when(engine.inspectImageCmd(any())).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new InternalServerErrorException("image store is corrupt"));

        IllegalStateException failure = initializeExpectingFailure(engine);

        assertFalse(
            failure.getMessage().contains("Could not connect"),
            "the engine answered, so the failure is not a connection failure: " + failure.getMessage()
        );
        assertTrue(
            failure.getMessage().contains("image store is corrupt"),
            "the message must name the step's own error: " + failure.getMessage()
        );
        assertTrue(
            failure.getMessage().contains("Podman backend not started"),
            "the message must say that the backend did not start: " + failure.getMessage()
        );
        assertFalse(logged("initialized successfully"), "start-up failed, so it must not be logged as a success");
        assertTrue(logged("5.2.1"), "the engine connection itself is still logged, with its version");
    }

    @Test
    @Timeout(value = 30_000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void anEngineThatDoesNotAnswerIsStillReportedAsAConnectionFailure() {
        DockerClient engine = mock(DockerClient.class);
        VersionCmd version = mock(VersionCmd.class);
        when(engine.versionCmd()).thenReturn(version);
        when(version.exec()).thenThrow(new RuntimeException(new ConnectException("Connection refused")));

        IllegalStateException failure = initializeExpectingFailure(engine);

        assertTrue(
            failure.getMessage().contains("Could not connect to the container engine at"),
            "an unreachable engine keeps the socket advice: " + failure.getMessage()
        );
        assertFalse(logged("initialized successfully"), "start-up failed, so it must not be logged as a success");
    }

    @Test
    @Timeout(value = 60_000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void theSuccessLineIsLoggedOnlyByABackendThatTakesWork() {
        DockerClient engine = engineAnswering("5.2.1");
        // A mocked inspect returns normally, so the job image counts as present.
        when(engine.inspectImageCmd(any())).thenReturn(mock(InspectImageCmd.class));
        PodmanBackend backend = new PodmanBackend();
        ReadinessAtSuccessLine probe = new ReadinessAtSuccessLine(backend);
        probe.start();
        logger.addAppender(probe);
        try {
            // No database here: the queue, node and reconciliation steps log their errors
            // and carry on, as they do in production.
            try (MockedStatic<DockerClientImpl> factory = mockStatic(DockerClientImpl.class)) {
                factory.when(() -> DockerClientImpl.getInstance(any(), any())).thenReturn(engine);
                backend.initialize("unused");
            }

            assertTrue(backend.isQueueDispatchable("container.q"), "a backend that finished start-up takes work");
            assertEquals(1, probe.readiness.size(), "the success line is logged exactly once");
            assertTrue(
                probe.readiness.get(0),
                "when the success line is logged, the backend must already take work"
            );
            List<String> messages = messages();
            int monitorStarted = indexOf(messages, "ContainerJobMonitor started");
            int succeeded = indexOf(messages, "PodmanBackend initialized successfully. Engine version: 5.2.1");
            assertTrue(monitorStarted >= 0, "the job monitor start is logged");
            assertTrue(succeeded > monitorStarted, "success is logged after the job monitor started");
            assertTrue(
                indexOf(messages, "Connected to the container engine at") < succeeded &&
                    indexOf(messages, "Connected to the container engine at") >= 0,
                "the connection is logged as a connection, before the success line"
            );
        } finally {
            logger.detachAppender(probe);
            probe.stop();
            backend.destroyIf();
        }
    }

    /** Records, for each success line, whether the backend took work when it was logged. */
    private static final class ReadinessAtSuccessLine extends AppenderBase<ILoggingEvent> {
        private final PodmanBackend backend;
        private final List<Boolean> readiness = new CopyOnWriteArrayList<>();

        private ReadinessAtSuccessLine(PodmanBackend backend) {
            this.backend = backend;
        }

        @Override
        protected void append(ILoggingEvent event) {
            if (event.getFormattedMessage().contains("initialized successfully")) {
                readiness.add(backend.isQueueDispatchable("container.q"));
            }
        }
    }

    private static DockerClient engineAnswering(String engineVersion) {
        DockerClient engine = mock(DockerClient.class);
        VersionCmd versionCmd = mock(VersionCmd.class);
        Version version = mock(Version.class);
        when(engine.versionCmd()).thenReturn(versionCmd);
        when(versionCmd.exec()).thenReturn(version);
        when(version.getVersion()).thenReturn(engineVersion);
        return engine;
    }

    private static IllegalStateException initializeExpectingFailure(DockerClient engine) {
        try (MockedStatic<DockerClientImpl> factory = mockStatic(DockerClientImpl.class)) {
            factory.when(() -> DockerClientImpl.getInstance(any(), any())).thenReturn(engine);
            new PodmanBackend().initialize("unused");
        } catch (IllegalStateException e) {
            return e;
        }
        fail("initialize() must throw when a start-up step fails");
        return null;
    }

    private boolean logged(String text) {
        return messages().stream().anyMatch(message -> message.contains(text));
    }

    /** A snapshot: the job monitor's thread may still be logging through the same logger. */
    private List<String> messages() {
        synchronized (appender) {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
        }
    }

    private static int indexOf(List<String> messages, String text) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }
}
