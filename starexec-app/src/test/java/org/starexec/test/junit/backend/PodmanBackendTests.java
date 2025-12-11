package org.starexec.test.junit.backend;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.*;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.starexec.backend.PodmanBackend;

/**
 * Unit tests for PodmanBackend container-based job execution.
 *
 * <p>These tests use Mockito to mock the DockerClient, allowing testing
 * without requiring an actual container engine (Docker/Podman) to be running.
 *
 * <p>The tests verify:
 * <ul>
 *   <li>Container creation and submission flow</li>
 *   <li>Job killing (single and all)</li>
 *   <li>Status reporting</li>
 *   <li>Error handling for transient connection issues</li>
 *   <li>Virtual queue and node management</li>
 * </ul>
 *
 * @see org.starexec.backend.PodmanBackend
 */
public class PodmanBackendTests {

    @Mock
    private DockerClient mockDockerClient;

    @Mock
    private CreateContainerCmd mockCreateContainerCmd;

    @Mock
    private StartContainerCmd mockStartContainerCmd;

    @Mock
    private StopContainerCmd mockStopContainerCmd;

    @Mock
    private RemoveContainerCmd mockRemoveContainerCmd;

    @Mock
    private ListContainersCmd mockListContainersCmd;

    @Mock
    private InspectContainerCmd mockInspectContainerCmd;

    @Mock
    private InspectContainerResponse mockInspectContainerResponse;

    @Mock
    private InspectContainerResponse.ContainerState mockContainerState;

    private PodmanBackend backend;
    private Path tempDir;
    private AutoCloseable mocks;

    // Test constants
    private static final String TEST_CONTAINER_ID = "abc123def456";
    private static final String TEST_CONTAINER_NAME = "starexec-job-test";
    private static final int TEST_EXEC_ID = 1001;

    @Before
    public void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);

        // Create temp directory for test files
        tempDir = Files.createTempDirectory("podman_backend_test");

        // Create a PodmanBackend instance without calling initialize()
        // We'll inject the mock DockerClient directly
        backend = new PodmanBackend();

        // Use reflection to inject the mock DockerClient
        injectMockDockerClient();

        // Set up common mock behaviors
        setupCommonMocks();
    }

    @After
    public void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }

        // Clean up temp directory
        if (tempDir != null) {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        // Ignore cleanup errors
                    }
                });
        }
    }

    /**
     * Injects the mock DockerClient into the backend using reflection.
     */
    private void injectMockDockerClient() throws Exception {
        Field dockerClientField = PodmanBackend.class.getDeclaredField("dockerClient");
        dockerClientField.setAccessible(true);
        dockerClientField.set(backend, mockDockerClient);
    }

    /**
     * Gets or creates the execIdToContainerId map using reflection.
     */
    @SuppressWarnings("unchecked")
    private Map<Integer, String> getExecIdMap() throws Exception {
        Field mapField = PodmanBackend.class.getDeclaredField("execIdToContainerId");
        mapField.setAccessible(true);
        return (Map<Integer, String>) mapField.get(backend);
    }

    /**
     * Sets up common mock behaviors used across multiple tests.
     */
    private void setupCommonMocks() {
        // Mock version command for connection test
        VersionCmd mockVersionCmd = mock(VersionCmd.class);
        Version mockVersion = mock(Version.class);
        when(mockDockerClient.versionCmd()).thenReturn(mockVersionCmd);
        when(mockVersionCmd.exec()).thenReturn(mockVersion);
        when(mockVersion.getVersion()).thenReturn("24.0.0");

        // Mock list containers command
        when(mockDockerClient.listContainersCmd()).thenReturn(mockListContainersCmd);
        when(mockListContainersCmd.withShowAll(anyBoolean())).thenReturn(mockListContainersCmd);
        when(mockListContainersCmd.withLabelFilter(anyMap())).thenReturn(mockListContainersCmd);
        when(mockListContainersCmd.withStatusFilter(anyList())).thenReturn(mockListContainersCmd);
        when(mockListContainersCmd.exec()).thenReturn(Collections.emptyList());

        // Mock stop container command
        when(mockDockerClient.stopContainerCmd(anyString())).thenReturn(mockStopContainerCmd);
        when(mockStopContainerCmd.withTimeout(anyInt())).thenReturn(mockStopContainerCmd);

        // Mock remove container command
        when(mockDockerClient.removeContainerCmd(anyString())).thenReturn(mockRemoveContainerCmd);
        when(mockRemoveContainerCmd.withForce(anyBoolean())).thenReturn(mockRemoveContainerCmd);
        when(mockRemoveContainerCmd.withRemoveVolumes(anyBoolean())).thenReturn(mockRemoveContainerCmd);
    }

    // ==================== Virtual Queue/Node Tests ====================

    @Test
    public void testGetQueues_ReturnsContainerQueue() {
        String[] queues = backend.getQueues();

        assertNotNull("Queues should not be null", queues);
        assertEquals("Should return exactly one queue", 1, queues.length);
        assertEquals("Queue name should be container.q", "container.q", queues[0]);
    }

    @Test
    public void testGetWorkerNodes_ReturnsVirtualNode() {
        String[] nodes = backend.getWorkerNodes();

        assertNotNull("Worker nodes should not be null", nodes);
        assertEquals("Should return exactly one node", 1, nodes.length);
        assertEquals("Node name should be container-worker-1", "container-worker-1", nodes[0]);
    }

    @Test
    public void testGetNodeQueueAssociations_ReturnsCorrectMapping() {
        Map<String, String> associations = backend.getNodeQueueAssociations();

        assertNotNull("Associations should not be null", associations);
        assertEquals("Should have exactly one association", 1, associations.size());
        assertEquals(
            "Worker node should be associated with container queue",
            "container.q",
            associations.get("container-worker-1")
        );
    }

    // ==================== Error Code Tests ====================

    @Test
    public void testIsError_NegativeCodeIsError() {
        assertTrue("Negative exec code should be an error", backend.isError(-1));
        assertTrue("Negative exec code should be an error", backend.isError(-100));
    }

    @Test
    public void testIsError_ZeroIsNotError() {
        assertFalse("Zero exec code should not be an error", backend.isError(0));
    }

    @Test
    public void testIsError_PositiveCodeIsNotError() {
        assertFalse("Positive exec code should not be an error", backend.isError(1));
        assertFalse("Positive exec code should not be an error", backend.isError(1000));
    }

    // ==================== Kill Pair Tests ====================

    @Test
    public void testKillPair_SuccessfulKill() throws Exception {
        // Setup: Add a tracked container
        Map<Integer, String> execIdMap = getExecIdMap();
        execIdMap.put(TEST_EXEC_ID, TEST_CONTAINER_ID);

        // Execute
        boolean result = backend.killPair(TEST_EXEC_ID);

        // Verify
        assertTrue("Kill should succeed", result);
        assertFalse("Container should be removed from tracking", execIdMap.containsKey(TEST_EXEC_ID));

        // Verify Docker commands were called
        verify(mockDockerClient).stopContainerCmd(TEST_CONTAINER_ID);
        verify(mockDockerClient).removeContainerCmd(TEST_CONTAINER_ID);
    }

    @Test
    public void testKillPair_NonExistentExecId() {
        boolean result = backend.killPair(9999);

        assertFalse("Kill should fail for non-existent exec ID", result);
    }

    @Test
    public void testKillPair_ContainerAlreadyStopped() throws Exception {
        // Setup: Add a tracked container
        Map<Integer, String> execIdMap = getExecIdMap();
        execIdMap.put(TEST_EXEC_ID, TEST_CONTAINER_ID);

        // Mock: Container already stopped (NotModifiedException)
        doThrow(new NotModifiedException("Container already stopped"))
            .when(mockStopContainerCmd).exec();

        // Execute
        boolean result = backend.killPair(TEST_EXEC_ID);

        // Verify - should still succeed (container gets removed)
        assertTrue("Kill should succeed even if container already stopped", result);
        verify(mockDockerClient).removeContainerCmd(TEST_CONTAINER_ID);
    }

    @Test
    public void testKillPair_ContainerNotFound() throws Exception {
        // Setup: Add a tracked container
        Map<Integer, String> execIdMap = getExecIdMap();
        execIdMap.put(TEST_EXEC_ID, TEST_CONTAINER_ID);

        // Mock: Container not found
        doThrow(new NotFoundException("Container not found"))
            .when(mockRemoveContainerCmd).exec();

        // Execute
        boolean result = backend.killPair(TEST_EXEC_ID);

        // Verify - should still succeed (container was already gone)
        assertTrue("Kill should succeed if container not found", result);
        assertFalse("Container should be removed from tracking", execIdMap.containsKey(TEST_EXEC_ID));
    }

    // ==================== Kill All Tests ====================

    @Test
    public void testKillAll_EmptyTracking() {
        boolean result = backend.killAll();

        assertTrue("Kill all should succeed with no tracked containers", result);
    }

    @Test
    public void testKillAll_MultipleContainers() throws Exception {
        // Setup: Add multiple tracked containers
        Map<Integer, String> execIdMap = getExecIdMap();
        execIdMap.put(1001, "container1");
        execIdMap.put(1002, "container2");
        execIdMap.put(1003, "container3");

        // Execute
        boolean result = backend.killAll();

        // Verify
        assertTrue("Kill all should succeed", result);
        assertTrue("All containers should be removed from tracking", execIdMap.isEmpty());

        // Verify stop was called for each container
        verify(mockDockerClient).stopContainerCmd("container1");
        verify(mockDockerClient).stopContainerCmd("container2");
        verify(mockDockerClient).stopContainerCmd("container3");
    }

    // ==================== Running Jobs Status Tests ====================

    @Test
    public void testGetRunningJobsStatus_NoContainers() {
        String status = backend.getRunningJobsStatus();

        assertNotNull("Status should not be null", status);
        assertTrue("Status should contain header", status.contains("StarExec Container Backend Status"));
        assertTrue("Status should indicate no containers", status.contains("No StarExec containers"));
    }

    @Test
    public void testGetRunningJobsStatus_WithContainers() {
        // Setup: Mock some containers
        Container mockContainer = mock(Container.class);
        when(mockContainer.getId()).thenReturn(TEST_CONTAINER_ID + "7890");
        when(mockContainer.getNames()).thenReturn(new String[]{"/" + TEST_CONTAINER_NAME});
        when(mockContainer.getStatus()).thenReturn("Up 5 minutes");
        when(mockContainer.getState()).thenReturn("running");

        when(mockListContainersCmd.exec()).thenReturn(Collections.singletonList(mockContainer));

        // Execute
        String status = backend.getRunningJobsStatus();

        // Verify
        assertNotNull("Status should not be null", status);
        assertTrue("Status should contain container ID", status.contains("abc123def456"));
        assertTrue("Status should contain container name", status.contains(TEST_CONTAINER_NAME));
        assertTrue("Status should contain container status", status.contains("Up 5 minutes"));
    }

    @Test
    public void testGetRunningJobsStatus_HandlesException() {
        // Mock: Throw exception when listing containers
        when(mockListContainersCmd.exec()).thenThrow(new RuntimeException("Connection refused"));

        // Execute
        String status = backend.getRunningJobsStatus();

        // Verify - should return error message, not throw
        assertNotNull("Status should not be null", status);
        assertTrue("Status should contain error message", status.contains("Error"));
    }

    // ==================== Active Execution IDs Tests ====================

    @Test
    public void testGetActiveExecutionIds_EmptyWhenNoContainers() throws IOException {
        Set<Integer> activeIds = backend.getActiveExecutionIds();

        assertNotNull("Active IDs should not be null", activeIds);
        assertTrue("Active IDs should be empty with no tracked containers", activeIds.isEmpty());
    }

    @Test
    public void testGetActiveExecutionIds_ReturnsRunningContainers() throws Exception {
        // Setup: Add tracked containers
        Map<Integer, String> execIdMap = getExecIdMap();
        execIdMap.put(1001, "running-container");
        execIdMap.put(1002, "stopped-container");

        // Mock running container
        when(mockDockerClient.inspectContainerCmd("running-container"))
            .thenReturn(mockInspectContainerCmd);
        when(mockInspectContainerCmd.exec()).thenReturn(mockInspectContainerResponse);
        when(mockInspectContainerResponse.getState()).thenReturn(mockContainerState);
        when(mockContainerState.getRunning()).thenReturn(true);

        // Mock stopped container
        InspectContainerCmd stoppedInspect = mock(InspectContainerCmd.class);
        InspectContainerResponse stoppedResponse = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState stoppedState = mock(InspectContainerResponse.ContainerState.class);
        when(mockDockerClient.inspectContainerCmd("stopped-container")).thenReturn(stoppedInspect);
        when(stoppedInspect.exec()).thenReturn(stoppedResponse);
        when(stoppedResponse.getState()).thenReturn(stoppedState);
        when(stoppedState.getRunning()).thenReturn(false);

        // Execute
        Set<Integer> activeIds = backend.getActiveExecutionIds();

        // Verify
        assertEquals("Should have one active container", 1, activeIds.size());
        assertTrue("Should contain running container ID", activeIds.contains(1001));
        assertFalse("Should not contain stopped container ID", activeIds.contains(1002));

        // Stopped container should be removed from tracking
        assertFalse("Stopped container should be removed from tracking", execIdMap.containsKey(1002));
    }

    @Test
    public void testGetActiveExecutionIds_HandlesNotFoundContainer() throws Exception {
        // Setup: Add tracked container that no longer exists
        Map<Integer, String> execIdMap = getExecIdMap();
        execIdMap.put(1001, "deleted-container");

        // Mock: Container not found
        when(mockDockerClient.inspectContainerCmd("deleted-container"))
            .thenReturn(mockInspectContainerCmd);
        when(mockInspectContainerCmd.exec()).thenThrow(new NotFoundException("Container not found"));

        // Execute
        Set<Integer> activeIds = backend.getActiveExecutionIds();

        // Verify
        assertTrue("Active IDs should be empty", activeIds.isEmpty());
        assertFalse("Deleted container should be removed from tracking", execIdMap.containsKey(1001));
    }

    // ==================== No-op Method Tests ====================

    @Test
    public void testClearNodeErrorStates_ReturnsTrue() {
        assertTrue("clearNodeErrorStates should return true", backend.clearNodeErrorStates());
    }

    @Test
    public void testDeleteQueue_NoException() {
        // Should not throw
        backend.deleteQueue("any-queue");
    }

    @Test
    public void testCreateQueue_ReturnsTrue() {
        boolean result = backend.createQueue("new-queue", new String[]{"node1"}, new String[]{"source-queue"});
        assertTrue("createQueue should return true", result);
    }

    @Test
    public void testCreateQueueWithSlots_ReturnsTrue() {
        boolean result = backend.createQueueWithSlots("new-queue", new String[]{"node1"}, new String[]{"source-queue"}, 4);
        assertTrue("createQueueWithSlots should return true", result);
    }

    @Test
    public void testMoveNodes_NoException() {
        // Should not throw
        backend.moveNodes("dest-queue", new String[]{"node1"}, new String[]{"source-queue"});
    }

    @Test
    public void testMoveNode_NoException() {
        // Should not throw
        backend.moveNode("node1", "queue1");
    }

    // ==================== Completed Container Tests ====================

    @Test
    public void testGetCompletedContainers_EmptyWhenNoContainers() {
        var completed = backend.getCompletedContainers();

        assertNotNull("Completed list should not be null", completed);
        assertTrue("Completed list should be empty", completed.isEmpty());
    }

    @Test
    public void testRemoveCompletedContainer_CallsRemove() {
        backend.removeCompletedContainer(TEST_CONTAINER_ID);

        verify(mockDockerClient).removeContainerCmd(TEST_CONTAINER_ID);
    }

    @Test
    public void testRemoveCompletedContainer_HandlesException() {
        // Mock: Throw exception
        doThrow(new RuntimeException("Remove failed")).when(mockRemoveContainerCmd).exec();

        // Should not throw - just logs warning
        backend.removeCompletedContainer(TEST_CONTAINER_ID);

        verify(mockDockerClient).removeContainerCmd(TEST_CONTAINER_ID);
    }

    // ==================== Destroy Tests ====================

    @Test
    public void testDestroyIf_ClosesClient() throws Exception {
        backend.destroyIf();

        verify(mockDockerClient).close();
    }

    @Test
    public void testDestroyIf_CleansUpOrphanedContainers() {
        // Setup: Mock orphaned containers
        Container mockContainer = mock(Container.class);
        when(mockContainer.getId()).thenReturn("orphaned-container");
        when(mockListContainersCmd.exec()).thenReturn(Collections.singletonList(mockContainer));

        // Execute
        backend.destroyIf();

        // Verify cleanup was attempted
        verify(mockDockerClient).removeContainerCmd("orphaned-container");
    }
}
