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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.starexec.backend.ContainerJobMonitor;
import org.starexec.backend.CpuPartition;
import org.starexec.backend.PodmanBackend;
import org.starexec.backend.exception.BackendTransientException;
import org.starexec.data.database.JobPairs;
import org.starexec.data.to.Status.StatusCode;

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

    @SuppressWarnings("unchecked")
    private Set<Integer> getSlotHolderSet() throws Exception {
        return getSlotPartitionMap().keySet();
    }

    private int getActiveSubmissionSlots() throws Exception {
        ensurePartitionStateReady();
        int activeSlots = 0;
        for (int slotCount : getPartitionActiveSlots()) {
            activeSlots += slotCount;
        }
        return activeSlots;
    }

    private void setActiveSubmissionSlots(int value) throws Exception {
        ensurePartitionStateReady();
        int[] slots = getPartitionActiveSlots();
        slots[0] = value;
    }

    private void markSubmissionSlotHeld(int execId) throws Exception {
        Map<Integer, Integer> holders = getSlotPartitionMap();
        holders.put(execId, 0);
        setActiveSubmissionSlots(holders.size());
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, Integer> getSlotPartitionMap() throws Exception {
        Field holdersField = PodmanBackend.class.getDeclaredField("execIdToPartitionIndex");
        holdersField.setAccessible(true);
        return (Map<Integer, Integer>) holdersField.get(backend);
    }

    private int[] getPartitionActiveSlots() throws Exception {
        Field slotsField = PodmanBackend.class.getDeclaredField("partitionActiveSlots");
        slotsField.setAccessible(true);
        return (int[]) slotsField.get(backend);
    }

    private void ensurePartitionStateReady() throws Exception {
        Method method = PodmanBackend.class.getDeclaredMethod("ensurePartitionStateReady");
        method.setAccessible(true);
        method.invoke(backend);
    }

    private void invokeStartContainerWithVerification(String containerId)
        throws Exception {
        Method method = PodmanBackend.class.getDeclaredMethod(
            "startContainerWithVerification",
            String.class
        );
        method.setAccessible(true);
        method.invoke(backend, containerId);
    }

    private void invokeNotifyMonitorNewWorkSafely() throws Exception {
        Method method = PodmanBackend.class.getDeclaredMethod(
            "notifyMonitorNewWorkSafely"
        );
        method.setAccessible(true);
        method.invoke(backend);
    }

    private void invokeReconcileOrphanedPairs() throws Exception {
        Method method = PodmanBackend.class.getDeclaredMethod(
            "reconcileOrphanedPairs"
        );
        method.setAccessible(true);
        method.invoke(backend);
    }

    private void invokeProcessReconciledContainer(int pairId, String containerId)
        throws Exception {
        Method method = PodmanBackend.class.getDeclaredMethod(
            "processReconciledContainerThroughMonitor", int.class, String.class
        );
        method.setAccessible(true);
        method.invoke(backend, pairId, containerId);
    }

    private void setJobMonitor(ContainerJobMonitor monitor) throws Exception {
        Field monitorField = PodmanBackend.class.getDeclaredField("jobMonitor");
        monitorField.setAccessible(true);
        monitorField.set(backend, monitor);
    }

    @Test
    public void reconciliationInspectionFailureRetainsContainerWithoutInventingResult()
        throws Exception {
        String containerId = "reconcile-uninspectable";

        try (MockedStatic<JobPairs> jobPairs = mockStatic(JobPairs.class)) {
            invokeProcessReconciledContainer(4242, containerId);

            jobPairs.verify(() -> JobPairs.setPairStatusPreciseResult(
                    anyInt(), anyInt(), anyInt(), anyInt(), anyBoolean()),
                never());
        }
        verify(mockDockerClient, never()).removeContainerCmd(containerId);
    }

    @Test
    public void reconciliationMissingRunningContainerLeavesScientificResultUnresolved()
        throws Exception {
        int pairId = 4242;

        try (MockedStatic<JobPairs> jobPairs = mockStatic(JobPairs.class)) {
            jobPairs.when(() -> JobPairs.getPairIdsByStatusCode(
                    StatusCode.STATUS_ENQUEUED.getVal()))
                .thenReturn(Collections.emptyList());
            jobPairs.when(() -> JobPairs.getPairIdsByStatusCode(
                    StatusCode.STATUS_RUNNING.getVal()))
                .thenReturn(Collections.singletonList(pairId));

            invokeReconcileOrphanedPairs();

            jobPairs.verify(() -> JobPairs.tryMarkRunningAsFailed(anyInt()), never());
            jobPairs.verify(() -> JobPairs.setPairStatusPreciseResult(
                    anyInt(), anyInt(), anyInt(), anyInt(), anyBoolean()),
                never());
        }
        verify(mockDockerClient, never()).removeContainerCmd(anyString());
    }

    @Test
    public void startupReconciliationRetainsContainerAfterPostStatusIngestionFailure()
        throws Exception {
        int pairId = 4242;
        int stalePairId = 5151;
        String containerId = "reconcile-post-status-failure";
        String staleContainerId = "reconcile-stale-terminal";
        Path outputDir = Files.createDirectories(tempDir.resolve("reconcile-output"));
        Files.writeString(
            outputDir.resolve("status.json"),
            "{\"pairId\":" + pairId +
                ",\"status\":" + StatusCode.STATUS_COMPLETE.getVal() +
                ",\"stageNumber\":2,\"timestamp\":1788818872}\n"
        );
        Files.writeString(outputDir.resolve("attributes.txt"), "answer=sat\n");

        Container exitedContainer = mock(Container.class);
        Map<String, String> labels = new HashMap<>();
        labels.put("starexec.managed", "true");
        labels.put("starexec.label.version", "2");
        labels.put("starexec.kind", "job-pair");
        labels.put("starexec.pair.id", Integer.toString(pairId));
        when(exitedContainer.getId()).thenReturn(containerId);
        when(exitedContainer.getLabels()).thenReturn(labels);

        Container staleTerminalContainer = mock(Container.class);
        Map<String, String> staleLabels = new HashMap<>(labels);
        staleLabels.put("starexec.pair.id", Integer.toString(stalePairId));
        when(staleTerminalContainer.getId()).thenReturn(staleContainerId);
        when(staleTerminalContainer.getLabels()).thenReturn(staleLabels);
        when(mockListContainersCmd.exec()).thenReturn(
            Arrays.asList(exitedContainer, staleTerminalContainer)
        );

        ContainerConfig config = mock(ContainerConfig.class);
        when(mockDockerClient.inspectContainerCmd(containerId))
            .thenReturn(mockInspectContainerCmd);
        when(mockDockerClient.inspectContainerCmd(staleContainerId))
            .thenReturn(mockInspectContainerCmd);
        when(mockInspectContainerCmd.exec()).thenReturn(mockInspectContainerResponse);
        when(mockInspectContainerResponse.getState()).thenReturn(mockContainerState);
        when(mockContainerState.getRunning()).thenReturn(false);
        when(mockContainerState.getExitCodeLong()).thenReturn(0L);
        when(mockInspectContainerResponse.getConfig()).thenReturn(config);
        when(config.getEnv()).thenReturn(new String[]{
            "STAREXEC_OUTPUT_DIR=" + outputDir
        });

        setJobMonitor(new ContainerJobMonitor(backend));

        try (MockedStatic<JobPairs> jobPairs = mockStatic(JobPairs.class)) {
            jobPairs.when(() -> JobPairs.getPairIdsByStatusCode(
                    StatusCode.STATUS_ENQUEUED.getVal()))
                .thenReturn(Collections.emptyList());
            jobPairs.when(() -> JobPairs.getPairIdsByStatusCode(
                    StatusCode.STATUS_RUNNING.getVal()))
                .thenReturn(Collections.singletonList(pairId));
            jobPairs.when(() -> JobPairs.setEarlierStageStatuses(
                    pairId, Collections.emptyMap()))
                .thenReturn(org.starexec.data.database.StageStatusBatchResult.APPLIED);
            jobPairs.when(() -> JobPairs.setPairStatusPreciseResult(
                    pairId,
                    2,
                    StatusCode.STATUS_COMPLETE.getVal(),
                    StatusCode.STATUS_NOT_REACHED.getVal(),
                    false))
                .thenReturn(org.starexec.data.database.PairStatusResult.APPLIED);
            jobPairs.when(() -> JobPairs.getStageNumbers(pairId))
                .thenThrow(new IllegalStateException("metadata database unavailable"));

            JobPairs.PairStatusLookupResult terminalLookup =
                mock(JobPairs.PairStatusLookupResult.class);
            when(terminalLookup.isMissing()).thenReturn(false);
            when(terminalLookup.isError()).thenReturn(false);
            when(terminalLookup.getStatusCode())
                .thenReturn(StatusCode.STATUS_COMPLETE.getVal());
            jobPairs.when(() -> JobPairs.getPairStatusLookup(pairId))
                .thenReturn(terminalLookup);
            jobPairs.when(() -> JobPairs.getPairStatusLookup(stalePairId))
                .thenReturn(terminalLookup);

            invokeReconcileOrphanedPairs();

            jobPairs.verify(() -> JobPairs.setPairStatusPreciseResult(
                pairId,
                2,
                StatusCode.STATUS_COMPLETE.getVal(),
                StatusCode.STATUS_NOT_REACHED.getVal(),
                false));
            jobPairs.verify(() -> JobPairs.setPairStatusPrecise(
                    anyInt(), anyInt(), anyInt(), anyInt()),
                never());
        }

        verify(mockDockerClient, never()).removeContainerCmd(containerId);
        verify(mockDockerClient).removeContainerCmd(staleContainerId);
        assertTrue(
            "valid terminal evidence must remain available for metadata retry",
            Files.exists(outputDir.resolve("status.json"))
        );
        assertTrue("exited reconciliation must not reserve a submission slot",
            getSlotHolderSet().isEmpty());
        assertEquals(0, getActiveSubmissionSlots());
    }

    private void setBackendField(String fieldName, Object value) throws Exception {
        Field field = PodmanBackend.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(backend, value);
    }

    private void configureBackendForSubmitScript() throws Exception {
        setBackendField("usePrebuiltImage", true);
        setBackendField(
            "jobImage",
            "ghcr.io/starexecmiami/starexec-job-runner:latest"
        );
        setBackendField("defaultMemoryMb", 2048L);
        setBackendField("defaultCpuLimit", 600);
        setBackendField("defaultWallclockLimit", 600);
        setBackendField("containerDataPath", "/app/data");
        setBackendField("hostDataPath", "");
    }

    private void configureCreateContainerSuccess(String containerId) {
        CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
        when(createResponse.getId()).thenReturn(containerId);

        when(mockDockerClient.createContainerCmd(anyString()))
            .thenReturn(mockCreateContainerCmd);
        when(mockCreateContainerCmd.withName(anyString()))
            .thenReturn(mockCreateContainerCmd);
        when(mockCreateContainerCmd.withHostName(anyString()))
            .thenReturn(mockCreateContainerCmd);
        when(mockCreateContainerCmd.withHostConfig(any(HostConfig.class)))
            .thenReturn(mockCreateContainerCmd);
        when(mockCreateContainerCmd.withEnv(anyList()))
            .thenReturn(mockCreateContainerCmd);
        when(mockCreateContainerCmd.withLabels(anyMap()))
            .thenReturn(mockCreateContainerCmd);
        when(mockCreateContainerCmd.withEntrypoint(any(String[].class)))
            .thenReturn(mockCreateContainerCmd);
        when(mockCreateContainerCmd.withCmd(anyString()))
            .thenReturn(mockCreateContainerCmd);
        when(mockCreateContainerCmd.withWorkingDir(anyString()))
            .thenReturn(mockCreateContainerCmd);
        when(mockCreateContainerCmd.exec()).thenReturn(createResponse);
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

        // Mock start container command
        when(mockDockerClient.startContainerCmd(anyString())).thenReturn(mockStartContainerCmd);

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

    @Test
    public void testGetQueues_ReturnsSharedContainerQueueWhenPartitionsConfigured()
        throws Exception {
        setBackendField(
            "partitions",
            Arrays.asList(
                new CpuPartition(0, "0-7", "0"),
                new CpuPartition(1, "8-15", "0")
            )
        );

        String[] queues = backend.getQueues();

        assertArrayEquals(
            "Partition workers should share the same container queue",
            new String[]{"container.q"},
            queues
        );
    }

    @Test
    public void testGetWorkerNodes_ReturnsPartitionNodesWhenConfigured() throws Exception {
        setBackendField(
            "partitions",
            Arrays.asList(
                new CpuPartition(0, "0-7", "0"),
                new CpuPartition(1, "8-15", "0")
            )
        );

        String[] nodes = backend.getWorkerNodes();

        assertArrayEquals(
            "Partition nodes should follow active CPU partition order",
            new String[]{"container-worker-partition-0", "container-worker-partition-1"},
            nodes
        );
    }

    @Test
    public void testGetNodeQueueAssociations_ReturnsPartitionMappingsWhenConfigured()
        throws Exception {
        setBackendField(
            "partitions",
            Arrays.asList(
                new CpuPartition(0, "0-7", "0"),
                new CpuPartition(1, "8-15", "0")
            )
        );

        Map<String, String> associations = backend.getNodeQueueAssociations();

        assertNotNull("Associations should not be null", associations);
        assertEquals("Should have one mapping per partition", 2, associations.size());
        assertEquals(
            "container.q",
            associations.get("container-worker-partition-0")
        );
        assertEquals(
            "container.q",
            associations.get("container-worker-partition-1")
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
        markSubmissionSlotHeld(TEST_EXEC_ID);

        // Execute
        boolean result = backend.killPair(TEST_EXEC_ID);

        // Verify
        assertTrue("Kill should succeed", result);
        assertFalse("Container should be removed from tracking", execIdMap.containsKey(TEST_EXEC_ID));
        assertFalse("Submission slot holder should be released", getSlotHolderSet().contains(TEST_EXEC_ID));
        assertEquals("Active submission slots should be decremented", 0, getActiveSubmissionSlots());

        // Verify Docker commands were called
        verify(mockDockerClient).stopContainerCmd(TEST_CONTAINER_ID);
        verify(mockDockerClient).removeContainerCmd(TEST_CONTAINER_ID);
        verify(mockRemoveContainerCmd).withRemoveVolumes(true);
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
    public void testGetActiveExecutionIds_ReleasesSlotForStoppedContainer() throws Exception {
        // Setup: One stopped tracked container that still holds a submission slot
        Map<Integer, String> execIdMap = getExecIdMap();
        execIdMap.put(TEST_EXEC_ID, "stopped-container");
        markSubmissionSlotHeld(TEST_EXEC_ID);

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
        assertTrue("Stopped container should not be active", activeIds.isEmpty());
        assertFalse("Slot holder should be released", getSlotHolderSet().contains(TEST_EXEC_ID));
        assertEquals("Active submission slots should be decremented", 0, getActiveSubmissionSlots());
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
    public void testGetCompletedContainers_EmptyWhenNoContainers() throws Exception {
        var completed = backend.getCompletedContainers();

        assertNotNull("Completed list should not be null", completed);
        assertTrue("Completed list should be empty", completed.isEmpty());
    }

    @Test
    public void testGetCompletedContainers_ListFailureThrowsTransientException() {
        RuntimeException brokenPipe = new RuntimeException("Broken pipe");
        when(mockListContainersCmd.exec()).thenThrow(brokenPipe);

        try {
            backend.getCompletedContainers();
            fail("Expected transient exception when listing completed containers fails");
        } catch (BackendTransientException e) {
            assertSame("Original exception should be preserved as cause", brokenPipe, e.getCause());
            assertEquals("Backend type should identify podman", "podman", e.getBackendType());
        }
    }

    @Test
    public void testGetCompletedContainers_ListFailureThrowsOriginalRuntimeWhenNonTransient()
        throws Exception {
        IllegalArgumentException nonTransientFailure = new IllegalArgumentException(
            "Invalid filter"
        );
        when(mockListContainersCmd.exec()).thenThrow(nonTransientFailure);

        try {
            backend.getCompletedContainers();
            fail("Expected non-transient list failure to propagate");
        } catch (IllegalArgumentException e) {
            assertSame(
                "Non-transient list failures should not be reclassified as transient",
                nonTransientFailure,
                e
            );
        }
    }

    @Test
    public void testGetCompletedContainers_InspectFailureThrowsTransientException()
        throws Exception {
        Container completedContainer = mock(Container.class);
        Map<String, String> labels = new HashMap<>();
        labels.put("starexec.pair.id", "17");

        when(completedContainer.getId()).thenReturn(TEST_CONTAINER_ID);
        when(completedContainer.getLabels()).thenReturn(labels);
        when(mockListContainersCmd.exec()).thenReturn(
            Collections.singletonList(completedContainer)
        );
        when(mockDockerClient.inspectContainerCmd(TEST_CONTAINER_ID))
            .thenReturn(mockInspectContainerCmd);

        RuntimeException brokenPipe = new RuntimeException("Broken pipe");
        when(mockInspectContainerCmd.exec()).thenThrow(brokenPipe);

        try {
            backend.getCompletedContainers();
            fail("Expected transient exception when inspecting completed container fails");
        } catch (BackendTransientException e) {
            assertSame("Original inspect exception should be preserved as cause", brokenPipe, e.getCause());
            assertEquals("Backend type should identify podman", "podman", e.getBackendType());
        }
    }

    @Test
    public void testRemoveCompletedContainer_CallsRemove() {
        backend.removeCompletedContainer(TEST_CONTAINER_ID);

        verify(mockDockerClient).removeContainerCmd(TEST_CONTAINER_ID);
        verify(mockRemoveContainerCmd).withRemoveVolumes(true);
    }

    @Test
    public void testRemoveCompletedContainer_ReleasesSubmissionSlotForTrackedExec() throws Exception {
        // Setup: tracked container with held submission slot
        Map<Integer, String> execIdMap = getExecIdMap();
        execIdMap.put(TEST_EXEC_ID, TEST_CONTAINER_ID);
        markSubmissionSlotHeld(TEST_EXEC_ID);

        backend.removeCompletedContainer(TEST_CONTAINER_ID);

        assertFalse("Tracked exec should be removed after completion", execIdMap.containsKey(TEST_EXEC_ID));
        assertFalse("Slot holder should be released", getSlotHolderSet().contains(TEST_EXEC_ID));
        assertEquals("Active submission slots should be decremented", 0, getActiveSubmissionSlots());
    }

    @Test
    public void testRemoveCompletedContainer_HandlesException() {
        // Mock: Throw exception
        doThrow(new RuntimeException("Remove failed")).when(mockRemoveContainerCmd).exec();

        // Should not throw - just logs warning
        backend.removeCompletedContainer(TEST_CONTAINER_ID);

        verify(mockDockerClient).removeContainerCmd(TEST_CONTAINER_ID);
    }

    @Test
    public void testStartContainerWithVerification_StartThrowsButContainerRunning_TreatedAsSuccess()
        throws Exception {
        // Arrange
        RuntimeException startFailure = new RuntimeException("socket timeout");
        doThrow(startFailure).when(mockStartContainerCmd).exec();

        when(mockDockerClient.inspectContainerCmd(TEST_CONTAINER_ID))
            .thenReturn(mockInspectContainerCmd);
        when(mockInspectContainerCmd.exec()).thenReturn(mockInspectContainerResponse);
        when(mockInspectContainerResponse.getState()).thenReturn(mockContainerState);
        when(mockContainerState.getRunning()).thenReturn(true);

        // Act + Assert (no throw)
        invokeStartContainerWithVerification(TEST_CONTAINER_ID);

        verify(mockDockerClient, never()).removeContainerCmd(TEST_CONTAINER_ID);
    }

    @Test
    public void testStartContainerWithVerification_StartThrowsAndContainerNotRunning_RemovesAndRethrows()
        throws Exception {
        // Arrange
        RuntimeException startFailure = new RuntimeException("connection reset");
        doThrow(startFailure).when(mockStartContainerCmd).exec();

        when(mockDockerClient.inspectContainerCmd(TEST_CONTAINER_ID))
            .thenReturn(mockInspectContainerCmd);
        when(mockInspectContainerCmd.exec()).thenReturn(mockInspectContainerResponse);
        when(mockInspectContainerResponse.getState()).thenReturn(mockContainerState);
        when(mockContainerState.getRunning()).thenReturn(false);

        // Act + Assert
        try {
            invokeStartContainerWithVerification(TEST_CONTAINER_ID);
            fail("Expected startContainerWithVerification to rethrow start failure");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertSame(
                "Original start exception should be rethrown",
                startFailure,
                e.getCause()
            );
        }

        verify(mockDockerClient).removeContainerCmd(TEST_CONTAINER_ID);
        verify(mockRemoveContainerCmd).withRemoveVolumes(true);
    }

    @Test
    public void testCleanupStaleExitedContainers_RemovesOnlyOldUnprotectedExitedContainers()
        throws Exception {
        long nowEpochSeconds = System.currentTimeMillis() / 1000L;
        setBackendField("exitedContainerCleanupAgeSeconds", 60L);

        Container staleContainer = mock(Container.class);
        when(staleContainer.getId()).thenReturn("stale-container");
        when(staleContainer.getCreated()).thenReturn(nowEpochSeconds - 120L);

        Container freshContainer = mock(Container.class);
        when(freshContainer.getId()).thenReturn("fresh-container");
        when(freshContainer.getCreated()).thenReturn(nowEpochSeconds - 10L);

        Container protectedContainer = mock(Container.class);
        when(protectedContainer.getId()).thenReturn("protected-container");
        when(protectedContainer.getCreated()).thenReturn(nowEpochSeconds - 120L);

        when(mockListContainersCmd.exec()).thenReturn(
            Arrays.asList(staleContainer, freshContainer, protectedContainer)
        );

        InspectContainerCmd staleInspect = mock(InspectContainerCmd.class);
        InspectContainerResponse staleInspectResponse = mock(
            InspectContainerResponse.class
        );
        InspectContainerResponse.ContainerState staleState = mock(
            InspectContainerResponse.ContainerState.class
        );

        when(mockDockerClient.inspectContainerCmd("stale-container"))
            .thenReturn(staleInspect);
        when(staleInspect.exec()).thenReturn(staleInspectResponse);
        when(staleInspectResponse.getState()).thenReturn(staleState);
        when(staleState.getRunning()).thenReturn(false);

        int removedCount = backend.cleanupStaleExitedContainers(
            Collections.singleton("protected-container")
        );

        assertEquals(
            "Only the stale unprotected exited container should be removed",
            1,
            removedCount
        );
        verify(mockDockerClient).removeContainerCmd("stale-container");
        verify(mockDockerClient, never()).removeContainerCmd("fresh-container");
        verify(mockDockerClient, never()).removeContainerCmd("protected-container");
        verify(mockDockerClient).inspectContainerCmd("stale-container");
        verify(mockRemoveContainerCmd).withRemoveVolumes(true);
    }

    @Test
    public void testNotifyMonitorNewWorkSafely_MonitorThrows_DoesNotPropagate()
        throws Exception {
        // Arrange
        ContainerJobMonitor monitor = mock(ContainerJobMonitor.class);
        doThrow(new RuntimeException("monitor down"))
            .when(monitor)
            .notifyNewWorkSubmitted();
        setJobMonitor(monitor);

        // Act + Assert (no throw)
        invokeNotifyMonitorNewWorkSafely();

        verify(monitor).notifyNewWorkSubmitted();
    }

    @Test
    public void testSubmitScript_StartThrowsButContainerRunning_ReturnsExecIdWithoutRetry()
        throws Exception {
        // Arrange
        configureBackendForSubmitScript();
        configureCreateContainerSuccess(TEST_CONTAINER_ID);

        RuntimeException startFailure = new RuntimeException("socket timeout");
        doThrow(startFailure).when(mockStartContainerCmd).exec();

        when(mockDockerClient.inspectContainerCmd(TEST_CONTAINER_ID))
            .thenReturn(mockInspectContainerCmd);
        when(mockInspectContainerCmd.exec()).thenReturn(mockInspectContainerResponse);
        when(mockInspectContainerResponse.getState()).thenReturn(mockContainerState);
        when(mockContainerState.getRunning()).thenReturn(true);

        String workingDir = "/app/data/jobin/job_1";
        String scriptPath = "/app/data/jobin/job_1/run.sh";
        String logPath = tempDir.resolve("out").resolve("job.log").toString();

        try (MockedStatic<JobPairs> jobPairsMock = mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.trySetPairRunning(42))
                .thenReturn(JobPairs.ConditionalPairUpdateResult.UPDATED);

            // Act
            int execId = backend.submitScript(42, scriptPath, workingDir, logPath);

            // Assert
            assertTrue("Submission should succeed", execId > 0);
            assertEquals(
                "Execution should be tracked against created container",
                TEST_CONTAINER_ID,
                getExecIdMap().get(execId)
            );
            verify(mockDockerClient, times(1)).createContainerCmd(anyString());
            verify(mockStartContainerCmd, times(1)).exec();
            verify(mockDockerClient, never()).removeContainerCmd(TEST_CONTAINER_ID);
            jobPairsMock.verify(() -> JobPairs.trySetPairRunning(42), times(1));
        }
    }

    @Test
    public void testSubmitScript_MonitorThrows_StillReturnsExecIdWithoutRetry()
        throws Exception {
        // Arrange
        configureBackendForSubmitScript();
        configureCreateContainerSuccess(TEST_CONTAINER_ID);

        ContainerJobMonitor monitor = mock(ContainerJobMonitor.class);
        doThrow(new RuntimeException("monitor down"))
            .when(monitor)
            .notifyNewWorkSubmitted();
        setJobMonitor(monitor);

        String workingDir = "/app/data/jobin/job_2";
        String scriptPath = "/app/data/jobin/job_2/run.sh";
        String logPath = tempDir.resolve("out").resolve("job2.log").toString();

        try (MockedStatic<JobPairs> jobPairsMock = mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.trySetPairRunning(99))
                .thenReturn(JobPairs.ConditionalPairUpdateResult.UPDATED);

            // Act
            int execId = backend.submitScript(99, scriptPath, workingDir, logPath);

            // Assert
            assertTrue("Submission should succeed despite monitor failure", execId > 0);
            assertEquals(
                "Execution should still be tracked",
                TEST_CONTAINER_ID,
                getExecIdMap().get(execId)
            );
            verify(monitor, times(1)).notifyNewWorkSubmitted();
            verify(mockDockerClient, times(1)).createContainerCmd(anyString());
            verify(mockStartContainerCmd, times(1)).exec();
            jobPairsMock.verify(() -> JobPairs.trySetPairRunning(99), times(1));
        }
    }

    @Test
    public void testSubmitScript_RunningStatusUpdateFailure_DoesNotFailSubmission()
        throws Exception {
        // Arrange
        configureBackendForSubmitScript();
        configureCreateContainerSuccess(TEST_CONTAINER_ID);

        String workingDir = "/app/data/jobin/job_3";
        String scriptPath = "/app/data/jobin/job_3/run.sh";
        String logPath = tempDir.resolve("out").resolve("job3.log").toString();

        try (MockedStatic<JobPairs> jobPairsMock = mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.trySetPairRunning(77))
                .thenReturn(JobPairs.ConditionalPairUpdateResult.ERROR);

            // Act
            int execId = backend.submitScript(77, scriptPath, workingDir, logPath);

            // Assert
            assertTrue("Submission should still succeed when running status update fails", execId > 0);
            assertEquals(
                "Execution should still be tracked",
                TEST_CONTAINER_ID,
                getExecIdMap().get(execId)
            );
            jobPairsMock.verify(() -> JobPairs.trySetPairRunning(77), times(1));
        }
    }

    @Test
    public void testSubmitScript_RunningStatusUpdateStale_DoesNotFailSubmission()
        throws Exception {
        // Arrange
        configureBackendForSubmitScript();
        configureCreateContainerSuccess(TEST_CONTAINER_ID);

        String workingDir = "/app/data/jobin/job_4";
        String scriptPath = "/app/data/jobin/job_4/run.sh";
        String logPath = tempDir.resolve("out").resolve("job4.log").toString();

        try (MockedStatic<JobPairs> jobPairsMock = mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.trySetPairRunning(78))
                .thenReturn(JobPairs.ConditionalPairUpdateResult.STALE);

            // Act
            int execId = backend.submitScript(78, scriptPath, workingDir, logPath);

            // Assert
            assertTrue("Submission should still succeed when running status is stale", execId > 0);
            assertEquals(
                "Execution should still be tracked",
                TEST_CONTAINER_ID,
                getExecIdMap().get(execId)
            );
            jobPairsMock.verify(() -> JobPairs.trySetPairRunning(78), times(1));
        }
    }

    @Test
    public void testReconcileOrphanedPairs_MarksRunningContainersAsRunning()
        throws Exception {
        Container runningContainer = mock(Container.class);
        Map<String, String> labels = new HashMap<>();
        labels.put("starexec.managed", "true");
        labels.put("starexec.label.version", "2");
        labels.put("starexec.kind", "job-pair");
        labels.put("starexec.pair.id", "17");
        labels.put("starexec.exec.id", "1001");

        when(runningContainer.getId()).thenReturn(TEST_CONTAINER_ID);
        when(runningContainer.getLabels()).thenReturn(labels);
        when(mockListContainersCmd.exec()).thenReturn(Collections.singletonList(runningContainer));

        when(mockDockerClient.inspectContainerCmd(TEST_CONTAINER_ID))
            .thenReturn(mockInspectContainerCmd);
        when(mockInspectContainerCmd.exec()).thenReturn(mockInspectContainerResponse);
        when(mockInspectContainerResponse.getState()).thenReturn(mockContainerState);
        when(mockContainerState.getRunning()).thenReturn(true);

        try (MockedStatic<JobPairs> jobPairsMock = mockStatic(JobPairs.class)) {
            JobPairs.PairStatusLookupResult lookup =
                mock(JobPairs.PairStatusLookupResult.class);
            when(lookup.isMissing()).thenReturn(false);
            when(lookup.isError()).thenReturn(false);
            when(lookup.getStatusCode()).thenReturn(StatusCode.STATUS_RUNNING.getVal());

            jobPairsMock
                .when(() -> JobPairs.getPairIdsByStatusCode(StatusCode.STATUS_ENQUEUED.getVal()))
                .thenReturn(Collections.singletonList(17));
            jobPairsMock
                .when(() -> JobPairs.getPairIdsByStatusCode(StatusCode.STATUS_RUNNING.getVal()))
                .thenReturn(Collections.emptyList());
            jobPairsMock.when(() -> JobPairs.getPairStatusLookup(17)).thenReturn(lookup);
            jobPairsMock
                .when(() -> JobPairs.trySetPairRunning(17))
                .thenReturn(JobPairs.ConditionalPairUpdateResult.UPDATED);

            invokeReconcileOrphanedPairs();

            jobPairsMock.verify(() -> JobPairs.trySetPairRunning(17), times(1));
            verify(mockDockerClient, never()).removeContainerCmd(TEST_CONTAINER_ID);
        }
    }

    // ==================== Destroy Tests ====================

    @Test
    public void testDestroyIf_ClosesClient() throws Exception {
        backend.destroyIf();

        verify(mockDockerClient).close();
    }

    @Test
    public void testDestroyIf_DrainsMonitorAndPreservesContainers()
        throws Exception {
        // Setup: inject a mock monitor whose drainAndStop() is tracked
        ContainerJobMonitor mockMonitor = mock(ContainerJobMonitor.class);
        setJobMonitor(mockMonitor);

        // Setup: orphaned containers exist (but should NOT be removed)
        Container mockContainer = mock(Container.class);
        when(mockContainer.getId()).thenReturn("orphaned-container");
        when(mockListContainersCmd.exec()).thenReturn(Collections.singletonList(mockContainer));

        // Execute
        backend.destroyIf();

        // Verify: monitor is drained before stop
        verify(mockMonitor).drainAndStop();
        // Verify: client is closed
        verify(mockDockerClient).close();
        // Verify: containers are NOT removed during normal shutdown
        verify(mockDockerClient, never()).removeContainerCmd(anyString());
    }

    // ---------------------------------------------------------------------
    // CPU limits.
    //
    // createHostConfig used to hardcode withCpuPeriod(100000L) and
    // withCpuQuota(100000L) -- one CPU core, regardless of how wide the
    // container's cpuset was. A job pair is given a whole socket and a parallel
    // solver is expected to use all of it, so the solver saw N cores and was then
    // throttled by CFS to one core's worth of bandwidth: wallclock inflated
    // roughly N x, and the recorded CPU time was measured against a limit it
    // reached at a different rate. Parallel tracks that run correctly under SGE
    // were silently mis-measured on this backend.
    // ---------------------------------------------------------------------

    private void applyCpuLimits(HostConfig hostConfig, CpuPartition partition)
        throws Exception {
        Method m = PodmanBackend.class.getDeclaredMethod(
            "applyCpuLimits", HostConfig.class, CpuPartition.class);
        m.setAccessible(true);
        m.invoke(null, hostConfig, partition);
    }

    @Test
    public void pinnedContainerGetsNoCfsQuota() throws Exception {
        HostConfig hostConfig = new HostConfig();

        applyCpuLimits(hostConfig, new CpuPartition(0, "0-7", "0"));

        assertEquals("the cpuset must be applied", "0-7", hostConfig.getCpusetCpus());
        assertEquals("0", hostConfig.getCpusetMems());
        assertNull(
            "a container pinned to a cpuset must not also carry a CFS quota: the cpuset"
                + " already bounds it, and a mismatched quota throttles parallel solvers",
            hostConfig.getCpuQuota()
        );
        assertNull(hostConfig.getCpuPeriod());
    }

    /**
     * The specific regression. A pair is handed a whole 8-CPU socket; the old code then
     * capped the container at 1.0 CPU, so eight solver threads timeshared one core.
     */
    @Test
    public void aWholeSocketIsNotThrottledToOneCore() throws Exception {
        HostConfig hostConfig = new HostConfig();

        applyCpuLimits(hostConfig, new CpuPartition(0, "0-7", "0"));

        Long quota = hostConfig.getCpuQuota();
        assertFalse(
            "a pair given 8 CPUs must not be limited to one core's bandwidth",
            quota != null && quota <= 100000L
        );
    }

    @Test
    public void unpartitionedContainerIsNotSilentlyCappedAtOneCore() throws Exception {
        // The old 1-core quota must not survive as an "safe" default here either: that
        // is the throttling bug, and keeping it would preserve the regression for
        // precisely the deployments that have no isolation to begin with.
        HostConfig hostConfig = new HostConfig();

        applyCpuLimits(hostConfig, null);

        assertNull("no cpuset is expected when no partition is configured",
            hostConfig.getCpusetCpus());
        Long quota = hostConfig.getCpuQuota();
        assertFalse(
            "an unpartitioned container must not inherit the old hardcoded 1-core quota",
            quota != null && quota <= 100000L
        );
    }

    /** A partition object carrying no cpuset means no pinning, so it is not pinned. */
    @Test
    public void partitionWithoutACpusetIsTreatedAsUnpinned() throws Exception {
        HostConfig hostConfig = new HostConfig();

        applyCpuLimits(hostConfig, new CpuPartition(0, null, null));

        assertNull(hostConfig.getCpusetCpus());
        Long quota = hostConfig.getCpuQuota();
        assertFalse(
            "the no-pinning partition must behave like no partition, not like a 1-core cap",
            quota != null && quota <= 100000L
        );
    }

    // ============ Java client create failures: the cause, and no second container (#208) ============
    //
    // doSubmitScript falls back to curl when the Java client's create fails with a transient
    // connection error. The warning named only the exception's class, so the failure could not be
    // diagnosed; and if the engine had already created the container before the client call
    // failed, curl's create was a second request for the same name.

    private static final String REUSED_CONTAINER_ID = "reused0000container";

    /**
     * The client call fails after the engine created the container: the existing container,
     * found by its exact name and this execution's label, is started and tracked; nothing else is
     * created.
     */
    @Test
    public void aContainerCreatedBeforeTheClientFailedIsReusedNotCreatedAgain() throws Exception {
        configureBackendForSubmitScript();
        setBackendField("containerSocketPath", "unix:///nonexistent/starexec-test.sock");
        String[] created = configureCreateFailsAfterTheEngineCreated(
            new RuntimeException("create failed", new IOException("Broken pipe"))
        );

        try (MockedStatic<JobPairs> jobPairsMock = mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.trySetPairRunning(42))
                .thenReturn(JobPairs.ConditionalPairUpdateResult.UPDATED);

            int execId = backend.submitScript(
                42, "/app/data/jobin/job_1/run.sh", "/app/data/jobin/job_1",
                tempDir.resolve("out").resolve("job.log").toString());

            assertTrue("the submission succeeds on the container that exists", execId > 0);
            assertEquals(REUSED_CONTAINER_ID, getExecIdMap().get(execId));
            verify(mockDockerClient, times(1)).startContainerCmd(REUSED_CONTAINER_ID);
            verify(mockDockerClient, times(1)).createContainerCmd(anyString());
            assertNotNull("the create was attempted under a name", created[0]);
        }
    }

    /**
     * The warning carries the exception's message and its cause chain, with the container's
     * environment values redacted.
     */
    @Test
    public void aClientCreateFailureLogsItsCauseChainWithoutEnvironmentValues() throws Exception {
        configureBackendForSubmitScript();
        setBackendField("containerSocketPath", "unix:///nonexistent/starexec-test.sock");
        configureCreateFailsAfterTheEngineCreated(new RuntimeException(
            "create failed",
            new IOException("Broken pipe while sending STAREXEC_OUTPUT_DIR=/private/out CONTAINER_MODE=true")
        ));

        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        ch.qos.logback.classic.Logger podmanLog = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(PodmanBackend.class);
        podmanLog.addAppender(appender);
        try (MockedStatic<JobPairs> jobPairsMock = mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.trySetPairRunning(43))
                .thenReturn(JobPairs.ConditionalPairUpdateResult.UPDATED);

            backend.submitScript(
                43, "/app/data/jobin/job_2/run.sh", "/app/data/jobin/job_2",
                tempDir.resolve("out").resolve("job2.log").toString());
        } finally {
            podmanLog.detachAppender(appender);
        }

        String warnings = "";
        for (ch.qos.logback.classic.spi.ILoggingEvent event : appender.list) {
            if (event.getLevel() == ch.qos.logback.classic.Level.WARN) {
                warnings += event.getFormattedMessage() + "\n";
            }
        }
        assertTrue("the outer message is logged: " + warnings,
            warnings.contains("java.lang.RuntimeException: create failed"));
        assertTrue("the cause is logged: " + warnings,
            warnings.contains("java.io.IOException: Broken pipe while sending"));
        assertTrue("a STAREXEC_ value is redacted: " + warnings,
            warnings.contains("STAREXEC_OUTPUT_DIR=<redacted>"));
        assertTrue("a value of the container's environment is redacted: " + warnings,
            warnings.contains("CONTAINER_MODE=<redacted>"));
        assertFalse("no environment value survives: " + warnings,
            warnings.contains("/private/out"));
    }

    /**
     * Only the container this call named, carrying this execution's label, is taken: the name
     * filter matches substrings, and a leftover "-retryN" container or another execution's must
     * not be adopted.
     */
    @Test
    public void onlyTheExactNameWithThisExecutionsLabelIsReused() throws Exception {
        when(mockListContainersCmd.withNameFilter(anyCollection())).thenReturn(mockListContainersCmd);
        Method find = PodmanBackend.class.getDeclaredMethod(
            "findCreatedContainer", String.class, int.class);
        find.setAccessible(true);

        // Built before stubbing the list: each stubs its own mock.
        Container retry = listed("retry", "/starexec-job-1-retry2", "7");
        Container otherExecution = listed("other", "/starexec-job-1", "8");
        Container mine = listed("mine", "/starexec-job-1", "7");

        when(mockListContainersCmd.exec()).thenReturn(Arrays.asList(retry, otherExecution));
        assertNull(find.invoke(backend, "starexec-job-1", 7));

        when(mockListContainersCmd.exec()).thenReturn(Arrays.asList(retry, mine));
        assertEquals("mine", find.invoke(backend, "starexec-job-1", 7));
    }


    /**
     * The client's create throws, but the engine made the container: a name lookup returns it,
     * with the name and labels the create was given. Returns the name the create used.
     */
    private String[] configureCreateFailsAfterTheEngineCreated(RuntimeException failure) {
        final String[] name = new String[1];
        final Map<?, ?>[] labels = new Map<?, ?>[1];
        when(mockDockerClient.createContainerCmd(anyString())).thenReturn(mockCreateContainerCmd);
        when(mockCreateContainerCmd.withName(anyString())).thenAnswer(call -> {
            name[0] = call.getArgument(0);
            return mockCreateContainerCmd;
        });
        when(mockCreateContainerCmd.withHostName(anyString())).thenReturn(mockCreateContainerCmd);
        when(mockCreateContainerCmd.withHostConfig(any(HostConfig.class))).thenReturn(mockCreateContainerCmd);
        when(mockCreateContainerCmd.withEnv(anyList())).thenReturn(mockCreateContainerCmd);
        when(mockCreateContainerCmd.withLabels(anyMap())).thenAnswer(call -> {
            labels[0] = call.getArgument(0);
            return mockCreateContainerCmd;
        });
        when(mockCreateContainerCmd.withEntrypoint(any(String[].class))).thenReturn(mockCreateContainerCmd);
        when(mockCreateContainerCmd.withCmd(anyString())).thenReturn(mockCreateContainerCmd);
        when(mockCreateContainerCmd.withWorkingDir(anyString())).thenReturn(mockCreateContainerCmd);
        when(mockCreateContainerCmd.exec()).thenThrow(failure);

        when(mockListContainersCmd.withNameFilter(anyCollection())).thenReturn(mockListContainersCmd);
        when(mockListContainersCmd.exec()).thenAnswer(call -> {
            if (name[0] == null || labels[0] == null) {
                return Collections.emptyList();
            }
            Container existing = mock(Container.class);
            when(existing.getId()).thenReturn(REUSED_CONTAINER_ID);
            when(existing.getNames()).thenReturn(new String[] {"/" + name[0]});
            @SuppressWarnings("unchecked")
            Map<String, String> existingLabels = (Map<String, String>) labels[0];
            when(existing.getLabels()).thenReturn(existingLabels);
            return Collections.singletonList(existing);
        });
        return name;
    }

    private Container listed(String id, String name, String execId) {
        Container c = mock(Container.class);
        when(c.getId()).thenReturn(id);
        when(c.getNames()).thenReturn(new String[] {name});
        when(c.getLabels()).thenReturn(Collections.singletonMap("starexec.exec.id", execId));
        return c;
    }
}
