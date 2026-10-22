package org.starexec.test.junit.backend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.VersionCmd;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.core.DockerClientImpl;
import java.net.ConnectException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
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

    @Before
    public void captureLog() {
        logger = (Logger) LoggerFactory.getLogger(PodmanBackend.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @After
    public void releaseLog() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test(timeout = 30_000)
    public void aFailureAfterTheEngineAnsweredIsNotReportedAsAConnectionFailure() {
        DockerClient engine = engineAnswering("5.2.1");
        InspectImageCmd inspect = mock(InspectImageCmd.class);
        when(engine.inspectImageCmd(any())).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new InternalServerErrorException("image store is corrupt"));

        IllegalStateException failure = initializeExpectingFailure(engine);

        assertFalse(
            "the engine answered, so the failure is not a connection failure: " + failure.getMessage(),
            failure.getMessage().contains("Could not connect")
        );
        assertTrue(
            "the message must name the step's own error: " + failure.getMessage(),
            failure.getMessage().contains("image store is corrupt")
        );
        assertTrue(
            "the message must say that the backend did not start: " + failure.getMessage(),
            failure.getMessage().contains("Podman backend not started")
        );
        assertFalse("start-up failed, so it must not be logged as a success", logged("initialized successfully"));
        assertTrue("the engine connection itself is still logged, with its version", logged("5.2.1"));
    }

    @Test(timeout = 30_000)
    public void anEngineThatDoesNotAnswerIsStillReportedAsAConnectionFailure() {
        DockerClient engine = mock(DockerClient.class);
        VersionCmd version = mock(VersionCmd.class);
        when(engine.versionCmd()).thenReturn(version);
        when(version.exec()).thenThrow(new RuntimeException(new ConnectException("Connection refused")));

        IllegalStateException failure = initializeExpectingFailure(engine);

        assertTrue(
            "an unreachable engine keeps the socket advice: " + failure.getMessage(),
            failure.getMessage().contains("Could not connect to the container engine at")
        );
        assertFalse("start-up failed, so it must not be logged as a success", logged("initialized successfully"));
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
        return appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains(text));
    }
}
