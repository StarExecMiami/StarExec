package org.starexec.test.junit.backend;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.starexec.backend.ContainerJobMonitor;
import org.starexec.backend.CpuPartition;
import org.starexec.backend.PodmanBackend;
import org.starexec.backend.exception.BackendTransientException;
import org.starexec.data.database.JobPairs;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.jobs.JobManager;

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

    @BeforeEach
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

    @AfterEach
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

        // These tests stand in for initialize() by injecting the client, so mark the backend
        // initialized: one that is not refuses every submission (PodmanBackendInitGuardTest).
        Field initializedField = PodmanBackend.class.getDeclaredField("initialized");
        initializedField.setAccessible(true);
        initializedField.set(backend, true);
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
            Files.exists(outputDir.resolve("status.json")),
            "valid terminal evidence must remain available for metadata retry"
        );
        assertTrue(getSlotHolderSet().isEmpty(),
            "exited reconciliation must not reserve a submission slot");
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
        setBackendField("containerDataPath", "/app/data");
        setBackendField("hostDataPath", "");
    }

    @SuppressWarnings("unchecked")
    private List<String> createEnvironmentVariables(
        long pairId,
        String workingDirectory,
        String outputDirectory
    ) throws Exception {
        Method method = PodmanBackend.class.getDeclaredMethod(
            "createEnvironmentVariables",
            long.class,
            String.class,
            String.class
        );
        method.setAccessible(true);
        return (List<String>) method.invoke(
            backend,
            pairId,
            workingDirectory,
            outputDirectory
        );
    }

    private HostConfig createHostConfig(
        String workingDirectory,
        String logPath,
        CpuPartition partition
    ) throws Exception {
        Method method = PodmanBackend.class.getDeclaredMethod(
            "createHostConfig",
            String.class,
            String.class,
            CpuPartition.class
        );
        method.setAccessible(true);
        return (HostConfig) method.invoke(
            backend,
            workingDirectory,
            logPath,
            partition
        );
    }

    @Test
    public void pairBudgetsComeFromTheGeneratedJobscriptNotContainerDefaults()
        throws Exception {
        configureBackendForSubmitScript();

        List<String> environment = createEnvironmentVariables(
            42L,
            "/app/data/jobin/job_1",
            "/app/data/joboutput/job_1"
        );

        assertFalse(
            environment.stream().anyMatch(value ->
                value.startsWith("STAREXEC_CPU_LIMIT=")),
            "the container environment must not invent a CPU-time budget that the"
                + " generated jobscript immediately replaces"
        );
        assertFalse(
            environment.stream().anyMatch(value ->
                value.startsWith("STAREXEC_WALLCLOCK_LIMIT=")),
            "the container environment must not invent a wallclock budget that the"
                + " generated jobscript immediately replaces"
        );

        String jobscript = Files.readString(
            Path.of("src/main/java/org/starexec/config/sge/jobscript")
        );
        String generated = JobManager.addParametersToJobscript(
            jobscript,
            Map.of("$$MAX_CPUTIME$$", "137", "$$MAX_RUNTIME$$", "251")
        );
        assertTrue(
            generated.contains("export STAREXEC_CPU_LIMIT='137'"),
            "the per-pair CPU budget must remain a generated jobscript value"
        );
        assertTrue(
            generated.contains("export STAREXEC_WALLCLOCK_LIMIT='251'"),
            "the per-pair wallclock budget must remain a generated jobscript value"
        );
    }

    @Test
    public void containerMemoryDefaultStillBoundsHostConfigAndIsExposed()
        throws Exception {
        configureBackendForSubmitScript();

        HostConfig hostConfig = createHostConfig(
            "/app/data/jobin/job_1",
            tempDir.resolve("job.log").toString(),
            new CpuPartition(0, "0", "0")
        );
        long expectedBytes = 2048L * 1024L * 1024L;
        assertEquals(Long.valueOf(expectedBytes), hostConfig.getMemory());
        assertEquals(Long.valueOf(expectedBytes), hostConfig.getMemorySwap());

        List<String> environment = createEnvironmentVariables(
            42L,
            "/app/data/jobin/job_1",
            "/app/data/joboutput/job_1"
        );
        assertTrue(
            environment.contains("STAREXEC_MEM_LIMIT=2048"),
            "GetComputerInfo must still receive the cgroup memory ceiling"
        );
    }

    /**
     * Each pair runs in a container of its own, so an OOM kill counted in that container's cgroup is
     * this pair's. The job script only attributes such a kill to the pair when told so: under the
     * local backend every pair shares the application's cgroup.
     */
    @Test
    public void eachPairsContainerTellsTheJobScriptItsCgroupIsThePairsOwn()
        throws Exception {
        configureBackendForSubmitScript();

        List<String> environment = createEnvironmentVariables(
            42L,
            "/app/data/jobin/job_1",
            "/app/data/joboutput/job_1"
        );
        assertTrue(
            environment.contains("STAREXEC_PAIR_HAS_OWN_CGROUP=true"),
            "the job script must be told the container's cgroup is the pair's own"
        );
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

        assertNotNull(queues, "Queues should not be null");
        assertEquals(1, queues.length, "Should return exactly one queue");
        assertEquals("container.q", queues[0], "Queue name should be container.q");
    }

    @Test
    public void testGetWorkerNodes_ReturnsVirtualNode() {
        String[] nodes = backend.getWorkerNodes();

        assertNotNull(nodes, "Worker nodes should not be null");
        assertEquals(1, nodes.length, "Should return exactly one node");
        assertEquals("container-worker-1", nodes[0], "Node name should be container-worker-1");
    }

    @Test
    public void testGetNodeQueueAssociations_ReturnsCorrectMapping() {
        Map<String, String> associations = backend.getNodeQueueAssociations();

        assertNotNull(associations, "Associations should not be null");
        assertEquals(1, associations.size(), "Should have exactly one association");
        assertEquals(
            "container.q",
            associations.get("container-worker-1"),
            "Worker node should be associated with container queue"
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
            new String[]{"container.q"},
            queues,
            "Partition workers should share the same container queue"
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
            new String[]{"container-worker-partition-0", "container-worker-partition-1"},
            nodes,
            "Partition nodes should follow active CPU partition order"
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

        assertNotNull(associations, "Associations should not be null");
        assertEquals(2, associations.size(), "Should have one mapping per partition");
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
        assertTrue(backend.isError(-1), "Negative exec code should be an error");
        assertTrue(backend.isError(-100), "Negative exec code should be an error");
    }

    @Test
    public void testIsError_ZeroIsNotError() {
        assertFalse(backend.isError(0), "Zero exec code should not be an error");
    }

    @Test
    public void testIsError_PositiveCodeIsNotError() {
        assertFalse(backend.isError(1), "Positive exec code should not be an error");
        assertFalse(backend.isError(1000), "Positive exec code should not be an error");
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
        assertTrue(result, "Kill should succeed");
        assertFalse(execIdMap.containsKey(TEST_EXEC_ID), "Container should be removed from tracking");
        assertFalse(getSlotHolderSet().contains(TEST_EXEC_ID), "Submission slot holder should be released");
        assertEquals(0, getActiveSubmissionSlots(), "Active submission slots should be decremented");

        // Verify Docker commands were called
        verify(mockDockerClient).stopContainerCmd(TEST_CONTAINER_ID);
        verify(mockDockerClient).removeContainerCmd(TEST_CONTAINER_ID);
        verify(mockRemoveContainerCmd).withRemoveVolumes(true);
    }

    @Test
    public void testKillPair_NonExistentExecId() {
        boolean result = backend.killPair(9999);

        assertFalse(result, "Kill should fail for non-existent exec ID");
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
        assertTrue(result, "Kill should succeed even if container already stopped");
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
        assertTrue(result, "Kill should succeed if container not found");
        assertFalse(execIdMap.containsKey(TEST_EXEC_ID), "Container should be removed from tracking");
    }

    // ==================== Confirmed Kill Tests ====================
    // killPairConfirmed gates every rerun, job deletion and user deletion. Before it was
    // overridden here the interface default answered UNPROVEN for every execution, so none
    // of those could ever proceed on this backend.

    private static Map<String, String> pairContainerLabels(int execId, Integer attemptNo) {
        Map<String, String> labels = new HashMap<>();
        labels.put("starexec.managed", "true");
        labels.put("starexec.exec.id", String.valueOf(execId));
        labels.put("starexec.pair.id", "42");
        labels.put("starexec.kind", "job-pair");
        labels.put("starexec.label.version", "2");
        if (attemptNo != null) {
            labels.put("starexec.attempt", String.valueOf(attemptNo));
        }
        return labels;
    }

    private static Container listedContainer(String containerId, Map<String, String> labels) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(containerId);
        when(container.getLabels()).thenReturn(labels);
        return container;
    }

    private void stubInspectState(
        String containerId, String status, Boolean running, Boolean paused, Boolean restarting
    ) {
        InspectContainerCmd command = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state =
            mock(InspectContainerResponse.ContainerState.class);
        when(mockDockerClient.inspectContainerCmd(containerId)).thenReturn(command);
        when(command.exec()).thenReturn(response);
        when(response.getState()).thenReturn(state);
        when(state.getStatus()).thenReturn(status);
        when(state.getRunning()).thenReturn(running);
        when(state.getPaused()).thenReturn(paused);
        when(state.getRestarting()).thenReturn(restarting);
    }

    private void censusReturns(Container... containers) {
        when(mockListContainersCmd.exec()).thenReturn(Arrays.asList(containers));
    }

    @Test
    public void testKillPairConfirmed_FinishedUntrackedExecutionIsSafe() {
        // A COMPLETE pair: its container was ingested and removed, so nothing tracks it and
        // the engine has nothing for its exec id. This is every rerun of a finished pair.
        assertEquals(
            PodmanBackend.KillOutcome.CONFIRMED_SAFE,
            backend.killPairConfirmed(TEST_EXEC_ID),
            "An execution with no container left is confirmed stopped"
        );
    }

    @Test
    public void testKillPairConfirmed_CensusSelectsByExecIdLabelAcrossAllStates() {
        backend.killPairConfirmed(TEST_EXEC_ID);

        verify(mockListContainersCmd).withShowAll(true);
        verify(mockListContainersCmd).withLabelFilter(
            Collections.singletonMap("starexec.exec.id", String.valueOf(TEST_EXEC_ID))
        );
    }

    @Test
    public void testKillPairConfirmed_StopsTrackedRunningContainerThenConfirms() throws Exception {
        Map<Integer, String> execIdMap = getExecIdMap();
        execIdMap.put(TEST_EXEC_ID, TEST_CONTAINER_ID);
        markSubmissionSlotHeld(TEST_EXEC_ID);

        // After the stop and forced removal the engine lists nothing for the exec id.
        assertEquals(
            PodmanBackend.KillOutcome.CONFIRMED_SAFE,
            backend.killPairConfirmed(TEST_EXEC_ID)
        );
        verify(mockDockerClient).stopContainerCmd(TEST_CONTAINER_ID);
        verify(mockDockerClient).removeContainerCmd(TEST_CONTAINER_ID);
        assertFalse(execIdMap.containsKey(TEST_EXEC_ID), "Tracking is released");
        assertEquals(0, getActiveSubmissionSlots(), "The slot is released");
    }

    @Test
    public void testKillPairConfirmed_UntrackedRunningContainerIsUnprovenAndLeftAlone() {
        // Exec ids restart with each application lifetime, so a running container that this
        // backend does not track may belong to another pair: report it, never stop it.
        censusReturns(listedContainer("other-lifetime", pairContainerLabels(TEST_EXEC_ID, 1)));
        stubInspectState("other-lifetime", "running", true, false, false);

        assertEquals(
            PodmanBackend.KillOutcome.UNPROVEN,
            backend.killPairConfirmed(TEST_EXEC_ID)
        );
        verify(mockDockerClient, never()).stopContainerCmd(anyString());
        verify(mockDockerClient, never()).removeContainerCmd(anyString());
    }

    @Test
    public void testKillPairConfirmed_ContainerThatSurvivedTheStopIsUnproven() throws Exception {
        Map<Integer, String> execIdMap = getExecIdMap();
        execIdMap.put(TEST_EXEC_ID, TEST_CONTAINER_ID);
        doThrow(new RuntimeException("engine timeout")).when(mockRemoveContainerCmd).exec();
        censusReturns(listedContainer(TEST_CONTAINER_ID, pairContainerLabels(TEST_EXEC_ID, 1)));
        stubInspectState(TEST_CONTAINER_ID, "running", true, false, false);

        assertEquals(
            PodmanBackend.KillOutcome.UNPROVEN,
            backend.killPairConfirmed(TEST_EXEC_ID)
        );
        assertTrue(execIdMap.containsKey(TEST_EXEC_ID), "A failed kill keeps the container tracked");
    }

    @Test
    public void testKillPairConfirmed_ListingFailureIsUnproven() {
        when(mockListContainersCmd.exec()).thenThrow(new RuntimeException("connection refused"));

        assertEquals(
            PodmanBackend.KillOutcome.UNPROVEN,
            backend.killPairConfirmed(TEST_EXEC_ID)
        );
    }

    @Test
    public void testKillPairConfirmed_ListingWithNoBodyIsUnproven() {
        when(mockListContainersCmd.exec()).thenReturn(null);

        assertEquals(
            PodmanBackend.KillOutcome.UNPROVEN,
            backend.killPairConfirmed(TEST_EXEC_ID)
        );
    }

    @Test
    public void testKillPairConfirmed_NoEngineClientIsUnproven() throws Exception {
        setBackendField("dockerClient", null);

        assertEquals(
            PodmanBackend.KillOutcome.UNPROVEN,
            backend.killPairConfirmed(TEST_EXEC_ID)
        );
    }

    @Test
    public void testKillPairConfirmed_ExitedContainerFencedOnItsAttemptIsSafe() {
        // A late ingestion carries attempt 3; a rerun has moved the pair past it, so the
        // write is refused (#185).
        censusReturns(listedContainer("exited-fenced", pairContainerLabels(TEST_EXEC_ID, 3)));
        stubInspectState("exited-fenced", "exited", false, false, false);

        assertEquals(
            PodmanBackend.KillOutcome.CONFIRMED_SAFE,
            backend.killPairConfirmed(TEST_EXEC_ID)
        );
    }

    @Test
    public void testKillPairConfirmed_ExitedUnfencedContainerIsUnprovenEvenWhenQuarantined()
        throws Exception {
        censusReturns(listedContainer("exited-unfenced", pairContainerLabels(TEST_EXEC_ID, null)));
        stubInspectState("exited-unfenced", "exited", false, false, false);

        assertEquals(
            PodmanBackend.KillOutcome.UNPROVEN,
            backend.killPairConfirmed(TEST_EXEC_ID),
            "An uningested result with no attempt label could land on the replacement"
        );

        // The quarantine is held in memory: after a restart the monitor can ingest the
        // container again, unfenced, into the attempt a rerun started.
        ContainerJobMonitor monitor = mock(ContainerJobMonitor.class);
        when(monitor.isQuarantined("exited-unfenced")).thenReturn(true);
        setJobMonitor(monitor);
        assertEquals(
            PodmanBackend.KillOutcome.UNPROVEN,
            backend.killPairConfirmed(TEST_EXEC_ID),
            "A quarantine does not outlive the application, so it proves nothing"
        );
    }

    @Test
    public void testKillPairConfirmed_AttemptLabelThatDoesNotParseLeavesItUnfenced() {
        // Ingestion fences only on a positive attempt (parseAttemptLabel); any other value
        // leaves the late write unfenced.
        for (String attempt : new String[] {"not-a-number", "", "0", "-3"}) {
            Map<String, String> labels = pairContainerLabels(TEST_EXEC_ID, null);
            labels.put("starexec.attempt", attempt);
            String id = "attempt-" + attempt;
            censusReturns(listedContainer(id, labels));
            stubInspectState(id, "exited", false, false, false);
            assertEquals(
                PodmanBackend.KillOutcome.UNPROVEN,
                backend.killPairConfirmed(TEST_EXEC_ID),
                "attempt label '" + attempt + "' fences nothing"
            );
        }
    }

    @Test
    public void testKillPairConfirmed_OneUnsafeContainerOutweighsSafeOnes() {
        stubInspectState("fenced", "exited", false, false, false);
        stubInspectState("never-started", "created", false, false, false);
        stubInspectState("still-running", "running", true, false, false);
        Container fenced = listedContainer("fenced", pairContainerLabels(TEST_EXEC_ID, 2));
        Container neverStarted =
            listedContainer("never-started", pairContainerLabels(TEST_EXEC_ID, 2));
        Container stillRunning =
            listedContainer("still-running", pairContainerLabels(TEST_EXEC_ID, 2));

        censusReturns(fenced, stillRunning);
        assertEquals(
            PodmanBackend.KillOutcome.UNPROVEN,
            backend.killPairConfirmed(TEST_EXEC_ID),
            "A safe container listed first does not decide"
        );

        censusReturns(stillRunning, fenced);
        assertEquals(PodmanBackend.KillOutcome.UNPROVEN, backend.killPairConfirmed(TEST_EXEC_ID));

        censusReturns(fenced, neverStarted);
        assertEquals(
            PodmanBackend.KillOutcome.CONFIRMED_SAFE,
            backend.killPairConfirmed(TEST_EXEC_ID),
            "Every container is safe, so the execution is"
        );
    }

    @Test
    public void testKillPairConfirmed_NeverStartedContainerIsSafe() {
        censusReturns(listedContainer("created-only", pairContainerLabels(TEST_EXEC_ID, null)));
        stubInspectState("created-only", "created", false, false, false);

        assertEquals(
            PodmanBackend.KillOutcome.CONFIRMED_SAFE,
            backend.killPairConfirmed(TEST_EXEC_ID)
        );
    }

    @Test
    public void testKillPairConfirmed_PausedRestartingOrUnknownStateIsUnproven() {
        censusReturns(listedContainer("paused", pairContainerLabels(TEST_EXEC_ID, 1)));
        stubInspectState("paused", "paused", false, true, false);
        assertEquals(PodmanBackend.KillOutcome.UNPROVEN, backend.killPairConfirmed(TEST_EXEC_ID));

        censusReturns(listedContainer("restarting", pairContainerLabels(TEST_EXEC_ID, 1)));
        stubInspectState("restarting", "restarting", false, false, true);
        assertEquals(PodmanBackend.KillOutcome.UNPROVEN, backend.killPairConfirmed(TEST_EXEC_ID));

        censusReturns(listedContainer("removing", pairContainerLabels(TEST_EXEC_ID, 1)));
        stubInspectState("removing", "removing", false, false, false);
        assertEquals(PodmanBackend.KillOutcome.UNPROVEN, backend.killPairConfirmed(TEST_EXEC_ID));

        // An unset flag is no report of a stop, even beside an exited status and an attempt
        // label that would otherwise make the container safe.
        censusReturns(listedContainer("no-running-flag", pairContainerLabels(TEST_EXEC_ID, 1)));
        stubInspectState("no-running-flag", "exited", null, false, false);
        assertEquals(PodmanBackend.KillOutcome.UNPROVEN, backend.killPairConfirmed(TEST_EXEC_ID));

        censusReturns(listedContainer("no-paused-flag", pairContainerLabels(TEST_EXEC_ID, 1)));
        stubInspectState("no-paused-flag", "exited", false, null, false);
        assertEquals(PodmanBackend.KillOutcome.UNPROVEN, backend.killPairConfirmed(TEST_EXEC_ID));

        censusReturns(listedContainer("no-restarting-flag", pairContainerLabels(TEST_EXEC_ID, 1)));
        stubInspectState("no-restarting-flag", "exited", false, false, null);
        assertEquals(PodmanBackend.KillOutcome.UNPROVEN, backend.killPairConfirmed(TEST_EXEC_ID));
    }

    @Test
    public void testKillPairConfirmed_InspectionFailureIsUnproven() {
        censusReturns(listedContainer("uninspectable", pairContainerLabels(TEST_EXEC_ID, 1)));
        InspectContainerCmd command = mock(InspectContainerCmd.class);
        when(mockDockerClient.inspectContainerCmd("uninspectable")).thenReturn(command);
        when(command.exec()).thenThrow(new RuntimeException("engine timeout"));

        assertEquals(
            PodmanBackend.KillOutcome.UNPROVEN,
            backend.killPairConfirmed(TEST_EXEC_ID)
        );
    }

    @Test
    public void testKillPairConfirmed_ContainerRemovedAfterListingIsSafe() {
        censusReturns(listedContainer("just-removed", pairContainerLabels(TEST_EXEC_ID, 1)));
        InspectContainerCmd command = mock(InspectContainerCmd.class);
        when(mockDockerClient.inspectContainerCmd("just-removed")).thenReturn(command);
        when(command.exec()).thenThrow(new NotFoundException("No such container"));

        assertEquals(
            PodmanBackend.KillOutcome.CONFIRMED_SAFE,
            backend.killPairConfirmed(TEST_EXEC_ID)
        );
    }

    @Test
    public void testKillPairConfirmed_IgnoresContainersOfOtherExecutionsAndUnmanagedOnes() {
        // An engine that ignored the filter would return everything: only this exec id's
        // managed containers may decide.
        Map<String, String> unmanaged = pairContainerLabels(TEST_EXEC_ID, 1);
        unmanaged.remove("starexec.managed");
        censusReturns(
            listedContainer("other-exec", pairContainerLabels(TEST_EXEC_ID + 1, 1)),
            listedContainer("unmanaged", unmanaged),
            listedContainer("no-labels", null)
        );
        stubInspectState("other-exec", "running", true, false, false);
        stubInspectState("unmanaged", "running", true, false, false);

        assertEquals(
            PodmanBackend.KillOutcome.CONFIRMED_SAFE,
            backend.killPairConfirmed(TEST_EXEC_ID)
        );
    }

    @Test
    public void testKillPairConfirmed_ExitedMaintenanceContainerIsSafe() {
        Map<String, String> maintenance = pairContainerLabels(TEST_EXEC_ID, null);
        maintenance.put("starexec.kind", "maintenance");
        maintenance.put("starexec.pair.id", "0");
        censusReturns(listedContainer("maintenance", maintenance));
        stubInspectState("maintenance", "exited", false, false, false);

        assertEquals(
            PodmanBackend.KillOutcome.CONFIRMED_SAFE,
            backend.killPairConfirmed(TEST_EXEC_ID),
            "The monitor records no pair result from a maintenance container"
        );
    }

    @Test
    public void testKillPairConfirmed_ExitedContainerIsJudgedByItsPairIdAsIngestionIs() {
        // Ingestion keys on the pair id label, not on starexec.kind: a positive pair id with
        // no kind label could still be ingested, so without an attempt it is not safe.
        Map<String, String> noKind = pairContainerLabels(TEST_EXEC_ID, null);
        noKind.remove("starexec.kind");
        censusReturns(listedContainer("no-kind", noKind));
        stubInspectState("no-kind", "exited", false, false, false);
        assertEquals(PodmanBackend.KillOutcome.UNPROVEN, backend.killPairConfirmed(TEST_EXEC_ID));

        Map<String, String> noPair = pairContainerLabels(TEST_EXEC_ID, null);
        noPair.remove("starexec.pair.id");
        censusReturns(listedContainer("no-pair", noPair));
        stubInspectState("no-pair", "exited", false, false, false);
        assertEquals(
            PodmanBackend.KillOutcome.CONFIRMED_SAFE,
            backend.killPairConfirmed(TEST_EXEC_ID),
            "Ingestion skips a container with no pair id"
        );

        Map<String, String> malformedPair = pairContainerLabels(TEST_EXEC_ID, null);
        malformedPair.put("starexec.pair.id", "not-a-number");
        censusReturns(listedContainer("malformed-pair", malformedPair));
        stubInspectState("malformed-pair", "exited", false, false, false);
        assertEquals(
            PodmanBackend.KillOutcome.CONFIRMED_SAFE,
            backend.killPairConfirmed(TEST_EXEC_ID),
            "Ingestion skips a container whose pair id does not parse"
        );
    }

    // ==================== Kill All Tests ====================

    @Test
    public void testKillAll_EmptyTracking() {
        boolean result = backend.killAll();

        assertTrue(result, "Kill all should succeed with no tracked containers");
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
        assertTrue(result, "Kill all should succeed");
        assertTrue(execIdMap.isEmpty(), "All containers should be removed from tracking");

        // Verify stop was called for each container
        verify(mockDockerClient).stopContainerCmd("container1");
        verify(mockDockerClient).stopContainerCmd("container2");
        verify(mockDockerClient).stopContainerCmd("container3");
    }

    // ==================== Running Jobs Status Tests ====================

    @Test
    public void testGetRunningJobsStatus_NoContainers() {
        String status = backend.getRunningJobsStatus();

        assertNotNull(status, "Status should not be null");
        assertTrue(status.contains("StarExec Container Backend Status"), "Status should contain header");
        assertTrue(status.contains("No StarExec containers"), "Status should indicate no containers");
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
        assertNotNull(status, "Status should not be null");
        assertTrue(status.contains("abc123def456"), "Status should contain container ID");
        assertTrue(status.contains(TEST_CONTAINER_NAME), "Status should contain container name");
        assertTrue(status.contains("Up 5 minutes"), "Status should contain container status");
    }

    @Test
    public void testGetRunningJobsStatus_HandlesException() {
        // Mock: Throw exception when listing containers
        when(mockListContainersCmd.exec()).thenThrow(new RuntimeException("Connection refused"));

        // Execute
        String status = backend.getRunningJobsStatus();

        // Verify - should return error message, not throw
        assertNotNull(status, "Status should not be null");
        assertTrue(status.contains("Error"), "Status should contain error message");
    }

    // ==================== Active Execution IDs Tests ====================

    @Test
    public void testGetActiveExecutionIds_EmptyWhenNoContainers() throws IOException {
        Set<Integer> activeIds = backend.getActiveExecutionIds();

        assertNotNull(activeIds, "Active IDs should not be null");
        assertTrue(activeIds.isEmpty(), "Active IDs should be empty with no tracked containers");
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
        assertTrue(activeIds.contains(1001), "Should contain running container ID");
        // An exited container that still exists has a result the monitor has not ingested yet
        // (#324): it stays active so the broken-pair sweep cannot file it as a submit failure.
        assertTrue(activeIds.contains(1002), "An exited, uningested container stays active");

        // It no longer holds an execution slot, so it leaves slot tracking
        assertFalse(execIdMap.containsKey(1002), "Stopped container should be removed from tracking");
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
        assertTrue(activeIds.contains(TEST_EXEC_ID), "Its result is still owed, so it stays active");
        assertFalse(getSlotHolderSet().contains(TEST_EXEC_ID), "Slot holder should be released");
        assertEquals(0, getActiveSubmissionSlots(), "Active submission slots should be decremented");
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
        assertTrue(activeIds.isEmpty(), "Active IDs should be empty");
        assertFalse(execIdMap.containsKey(1001), "Deleted container should be removed from tracking");
    }

    /** Once the monitor has ingested and removed the container, the execution is over. */
    @Test
    public void testGetActiveExecutionIds_IngestedContainerIsNoLongerActive() throws Exception {
        givenExitedTrackedContainer(1003, "ingested-container");
        assertTrue(backend.getActiveExecutionIds().contains(1003));

        backend.removeCompletedContainer("ingested-container");

        assertFalse(backend.getActiveExecutionIds().contains(1003));
    }

    /** A container the monitor quarantined will never be ingested: the sweep may act. */
    @Test
    public void testGetActiveExecutionIds_QuarantinedContainerIsNotActive() throws Exception {
        givenExitedTrackedContainer(1004, "quarantined-container");
        assertTrue(backend.getActiveExecutionIds().contains(1004));
        ContainerJobMonitor monitor = mock(ContainerJobMonitor.class);
        when(monitor.isQuarantined("quarantined-container")).thenReturn(true);
        Field monitorField = PodmanBackend.class.getDeclaredField("jobMonitor");
        monitorField.setAccessible(true);
        monitorField.set(backend, monitor);

        assertFalse(backend.getActiveExecutionIds().contains(1004));
    }

    /** An exited container that disappeared without ingestion is not kept alive. */
    @Test
    public void testGetActiveExecutionIds_VanishedExitedContainerIsNotActive() throws Exception {
        InspectContainerCmd inspect = givenExitedTrackedContainer(1005, "vanished-container");
        assertTrue(backend.getActiveExecutionIds().contains(1005));
        when(inspect.exec()).thenThrow(new NotFoundException("gone"));

        assertFalse(backend.getActiveExecutionIds().contains(1005));
    }

    /** The retry path releases the slot of an exited container; its result is still owed. */
    @Test
    public void testReleaseSlotForCompletedContainer_KeepsTheExecutionActive() throws Exception {
        Map<Integer, String> execIdMap = getExecIdMap();
        execIdMap.put(1006, "retrying-container");
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(mockDockerClient.inspectContainerCmd("retrying-container")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(mock(InspectContainerResponse.class));

        backend.releaseSlotForCompletedContainer("retrying-container");

        assertFalse(execIdMap.containsKey(1006));
        assertTrue(backend.getActiveExecutionIds().contains(1006));
    }

    private InspectContainerCmd givenExitedTrackedContainer(int execId, String containerId)
            throws Exception {
        getExecIdMap().put(execId, containerId);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(mockDockerClient.inspectContainerCmd(containerId)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);
        when(response.getState()).thenReturn(state);
        when(state.getRunning()).thenReturn(false);
        return inspect;
    }

    // ==================== No-op Method Tests ====================

    @Test
    public void testClearNodeErrorStates_ReturnsTrue() {
        assertTrue(backend.clearNodeErrorStates(), "clearNodeErrorStates should return true");
    }

    @Test
    public void testDeleteQueue_NoException() {
        // Should not throw
        backend.deleteQueue("any-queue");
    }

    @Test
    public void testCreateQueue_ReturnsTrue() {
        boolean result = backend.createQueue("new-queue", new String[]{"node1"}, new String[]{"source-queue"});
        assertTrue(result, "createQueue should return true");
    }

    @Test
    public void testCreateQueueWithSlots_ReturnsTrue() {
        boolean result = backend.createQueueWithSlots("new-queue", new String[]{"node1"}, new String[]{"source-queue"}, 4);
        assertTrue(result, "createQueueWithSlots should return true");
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

        assertNotNull(completed, "Completed list should not be null");
        assertTrue(completed.isEmpty(), "Completed list should be empty");
    }

    @Test
    public void testGetCompletedContainers_ListFailureThrowsTransientException() {
        RuntimeException brokenPipe = new RuntimeException("Broken pipe");
        when(mockListContainersCmd.exec()).thenThrow(brokenPipe);

        try {
            backend.getCompletedContainers();
            fail("Expected transient exception when listing completed containers fails");
        } catch (BackendTransientException e) {
            assertSame(brokenPipe, e.getCause(), "Original exception should be preserved as cause");
            assertEquals("podman", e.getBackendType(), "Backend type should identify podman");
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
                nonTransientFailure,
                e,
                "Non-transient list failures should not be reclassified as transient"
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
            assertSame(brokenPipe, e.getCause(), "Original inspect exception should be preserved as cause");
            assertEquals("podman", e.getBackendType(), "Backend type should identify podman");
        }
    }

    @Test
    public void testRemoveCompletedContainer_CallsRemove() {
        backend.removeCompletedContainer(TEST_CONTAINER_ID);

        verify(mockDockerClient).removeContainerCmd(TEST_CONTAINER_ID);
        verify(mockRemoveContainerCmd).withRemoveVolumes(true);
    }

    @Test
    public void discardCompletedContainerRemovesOnlyAnInspectedExitedContainer() throws Exception {
        when(mockDockerClient.inspectContainerCmd(TEST_CONTAINER_ID))
            .thenReturn(mockInspectContainerCmd);
        when(mockInspectContainerCmd.exec()).thenReturn(mockInspectContainerResponse);
        when(mockInspectContainerResponse.getState()).thenReturn(mockContainerState);
        when(mockContainerState.getRunning()).thenReturn(false);

        assertTrue(backend.discardCompletedContainer(TEST_CONTAINER_ID));

        verify(mockDockerClient).removeContainerCmd(TEST_CONTAINER_ID);
        verify(mockRemoveContainerCmd).withForce(false);
        verify(mockRemoveContainerCmd).withRemoveVolumes(true);
    }

    @Test
    public void discardCompletedContainerRefusesWhenRunningStateIsUnknownOrActive() throws Exception {
        when(mockDockerClient.inspectContainerCmd(TEST_CONTAINER_ID))
            .thenReturn(mockInspectContainerCmd);
        when(mockInspectContainerCmd.exec()).thenReturn(mockInspectContainerResponse);
        when(mockInspectContainerResponse.getState()).thenReturn(mockContainerState);
        when(mockContainerState.getRunning()).thenReturn(true);

        assertFalse(backend.discardCompletedContainer(TEST_CONTAINER_ID));

        verify(mockDockerClient, never()).removeContainerCmd(TEST_CONTAINER_ID);
    }

    @Test
    public void testRemoveCompletedContainer_ReleasesSubmissionSlotForTrackedExec() throws Exception {
        // Setup: tracked container with held submission slot
        Map<Integer, String> execIdMap = getExecIdMap();
        execIdMap.put(TEST_EXEC_ID, TEST_CONTAINER_ID);
        markSubmissionSlotHeld(TEST_EXEC_ID);

        backend.removeCompletedContainer(TEST_CONTAINER_ID);

        assertFalse(execIdMap.containsKey(TEST_EXEC_ID), "Tracked exec should be removed after completion");
        assertFalse(getSlotHolderSet().contains(TEST_EXEC_ID), "Slot holder should be released");
        assertEquals(0, getActiveSubmissionSlots(), "Active submission slots should be decremented");
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
                startFailure,
                e.getCause(),
                "Original start exception should be rethrown"
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
            1,
            removedCount,
            "Only the stale unprotected exited container should be removed"
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
            assertTrue(execId > 0, "Submission should succeed");
            assertEquals(
                TEST_CONTAINER_ID,
                getExecIdMap().get(execId),
                "Execution should be tracked against created container"
            );
            verify(mockDockerClient, times(1)).createContainerCmd(anyString());
            verify(mockStartContainerCmd, times(1)).exec();
            verify(mockDockerClient, never()).removeContainerCmd(TEST_CONTAINER_ID);
            jobPairsMock.verify(() -> JobPairs.trySetPairRunning(42), times(1));
        }
    }

    @Test
    public void testSubmitScript_AttemptReadFails_DefersAndReleasesSlot()
        throws Exception {
        // A transient database error while reading the pair's attempt (#185) is not the
        // pair's fault. Returning -1 would become a terminal ERROR_SGE_REJECT in JobManager;
        // the pair must be deferred so it stays queued, and its slot must be freed.
        configureBackendForSubmitScript();
        configureCreateContainerSuccess(TEST_CONTAINER_ID);

        String workingDir = "/app/data/jobin/job_5";
        String scriptPath = "/app/data/jobin/job_5/run.sh";
        String logPath = tempDir.resolve("out").resolve("job5.log").toString();

        try (MockedStatic<JobPairs> jobPairsMock = mockStatic(JobPairs.class)) {
            jobPairsMock
                .when(() -> JobPairs.getCurrentAttemptNo(55))
                .thenThrow(new java.sql.SQLException("connection reset"));

            try {
                int execId = backend.submitScript(55, scriptPath, workingDir, logPath);
                fail("a failed attempt read must defer, not return " + execId);
            } catch (org.starexec.backend.exception.SubmissionDeferredException expected) {
                assertTrue(expected.getMessage().contains("55"),
                    "names the pair: " + expected.getMessage());
                assertTrue(expected.getMessage().contains("connection reset"),
                    "names the cause: " + expected.getMessage());
            }
        }

        verify(mockDockerClient, never()).createContainerCmd(anyString());
        verify(mockStartContainerCmd, never()).exec();
        assertTrue(getExecIdMap().isEmpty(), "no execution may be registered");
        assertTrue(getSlotHolderSet().isEmpty(), "the partition slot must be released");
        assertEquals(0, getActiveSubmissionSlots());
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
            assertTrue(execId > 0, "Submission should succeed despite monitor failure");
            assertEquals(
                TEST_CONTAINER_ID,
                getExecIdMap().get(execId),
                "Execution should still be tracked"
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
            assertTrue(execId > 0, "Submission should still succeed when running status update fails");
            assertEquals(
                TEST_CONTAINER_ID,
                getExecIdMap().get(execId),
                "Execution should still be tracked"
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
            assertTrue(execId > 0, "Submission should still succeed when running status is stale");
            assertEquals(
                TEST_CONTAINER_ID,
                getExecIdMap().get(execId),
                "Execution should still be tracked"
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

        assertEquals("0-7", hostConfig.getCpusetCpus(), "the cpuset must be applied");
        assertEquals("0", hostConfig.getCpusetMems());
        assertNull(
            hostConfig.getCpuQuota(),
            "a container pinned to a cpuset must not also carry a CFS quota: the cpuset"
                + " already bounds it, and a mismatched quota throttles parallel solvers"
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
            quota != null && quota <= 100000L,
            "a pair given 8 CPUs must not be limited to one core's bandwidth"
        );
    }

    @Test
    public void unpartitionedContainerIsNotSilentlyCappedAtOneCore() throws Exception {
        // The old 1-core quota must not survive as an "safe" default here either: that
        // is the throttling bug, and keeping it would preserve the regression for
        // precisely the deployments that have no isolation to begin with.
        HostConfig hostConfig = new HostConfig();

        applyCpuLimits(hostConfig, null);

        assertNull(hostConfig.getCpusetCpus(),
            "no cpuset is expected when no partition is configured");
        Long quota = hostConfig.getCpuQuota();
        assertFalse(
            quota != null && quota <= 100000L,
            "an unpartitioned container must not inherit the old hardcoded 1-core quota"
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
            quota != null && quota <= 100000L,
            "the no-pinning partition must behave like no partition, not like a 1-core cap"
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

            assertTrue(execId > 0, "the submission succeeds on the container that exists");
            assertEquals(REUSED_CONTAINER_ID, getExecIdMap().get(execId));
            verify(mockDockerClient, times(1)).startContainerCmd(REUSED_CONTAINER_ID);
            verify(mockDockerClient, times(1)).createContainerCmd(anyString());
            assertNotNull(created[0], "the create was attempted under a name");
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
        assertTrue(warnings.contains("java.lang.RuntimeException: create failed"),
            "the outer message is logged: " + warnings);
        assertTrue(warnings.contains("java.io.IOException: Broken pipe while sending"),
            "the cause is logged: " + warnings);
        assertTrue(warnings.contains("STAREXEC_OUTPUT_DIR=<redacted>"),
            "a STAREXEC_ value is redacted: " + warnings);
        assertTrue(warnings.contains("CONTAINER_MODE=<redacted>"),
            "a value of the container's environment is redacted: " + warnings);
        assertFalse(warnings.contains("/private/out"),
            "no environment value survives: " + warnings);
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
