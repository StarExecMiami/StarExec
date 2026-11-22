package org.starexec.backend;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.database.SolverBuildCreations;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PodmanConsumerAndBuildFailureTest {

	private PodmanBackend backend;
	private DockerClient client;
	private InspectContainerResponse inspection;
	private InspectContainerResponse.ContainerState state;
	private RemoveContainerCmd removeCmd;

	@SuppressWarnings("unchecked")
	private <T> T field(String name) throws Exception {
		Field f = PodmanBackend.class.getDeclaredField(name);
		f.setAccessible(true);
		return (T) f.get(backend);
	}

	private void setField(String name, Object value) throws Exception {
		Field f = PodmanBackend.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(backend, value);
	}

	@BeforeEach
	public void setup() throws Exception {
		backend = new PodmanBackend();
		client = mock(DockerClient.class);
		setField("dockerClient", client);
		setField("partitions", List.of(new CpuPartition(0, "2", null)));
		setField("partitionActiveSlots", new int[]{1});
		setField("partitionMaxSlots", new int[]{2});
		setField("partitionSlotLocks", new Object[]{new Object()});
		setField("partitionNodeIds", new int[]{-1});

		inspection = mock(InspectContainerResponse.class);
		state = mock(InspectContainerResponse.ContainerState.class);
		ContainerConfig config = mock(ContainerConfig.class);
		when(config.getLabels()).thenReturn(Map.of());
		when(inspection.getConfig()).thenReturn(config);
		when(inspection.getState()).thenReturn(state);

		ListContainersCmd listCmd = mock(ListContainersCmd.class);
		when(client.listContainersCmd()).thenReturn(listCmd);
		when(listCmd.withShowAll(true)).thenReturn(listCmd);
		when(listCmd.withLabelFilter(anyMap())).thenReturn(listCmd);
		when(listCmd.exec()).thenReturn(List.of());
	}

	@Test
	public void createdConsumerContainerIsNotMisclassifiedAsAwaitingIngestionOrReleased() throws Exception {
		String containerId = "a".repeat(64);
		this.<Map<Integer, String>>field("execIdToContainerId").put(1001, containerId);
		this.<Map<Integer, Integer>>field("execIdToPartitionIndex").put(1001, 0);

		InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
		when(client.inspectContainerCmd(containerId)).thenReturn(inspectCmd);
		when(inspectCmd.exec()).thenReturn(inspection);
		when(state.getStatus()).thenReturn("created");
		when(state.getRunning()).thenReturn(false);

		Set<Integer> active = backend.getActiveExecutionIds();

		assertTrue(active.contains(1001), "unstarted container must remain active, not awaiting ingestion");
		assertTrue(this.<Map<?, ?>>field("awaitingIngestion").isEmpty());
		assertEquals(1, ((int[]) field("partitionActiveSlots"))[0]);
	}

	@Test
	public void cancelUnstartedConsumerContainerRemovesCreatedContainerAndTracking() throws Exception {
		String containerId = "b".repeat(64);
		this.<Map<Integer, String>>field("execIdToContainerId").put(1001, containerId);
		AtomicBoolean removed = new AtomicBoolean(false);

		InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
		when(client.inspectContainerCmd(containerId)).thenReturn(inspectCmd);
		when(inspectCmd.exec()).thenAnswer(inv -> {
			if (removed.get()) {
				throw new NotFoundException("removed");
			}
			return inspection;
		});
		when(state.getStatus()).thenReturn("created");
		when(state.getRunning()).thenReturn(false);
		when(state.getPidLong()).thenReturn(0L);

		removeCmd = mock(RemoveContainerCmd.class);
		when(client.removeContainerCmd(containerId)).thenReturn(removeCmd);
		when(removeCmd.withForce(false)).thenReturn(removeCmd);
		when(removeCmd.withRemoveVolumes(true)).thenReturn(removeCmd);
		doAnswer(inv -> {
			removed.set(true);
			return null;
		}).when(removeCmd).exec();

		Method cancel = PodmanBackend.class.getDeclaredMethod(
				"tryCancelUnstartedConsumerContainer", int.class, String.class, boolean.class);
		cancel.setAccessible(true);

		boolean result = (boolean) cancel.invoke(backend, 1001, containerId, true);

		assertTrue(result);
		verify(removeCmd).exec();
		assertFalse(this.<Map<?, ?>>field("execIdToContainerId").containsKey(1001));
	}

	@Test
	public void cancelUnstartedConsumerContainerRetainsRunningOrExitedContainer() throws Exception {
		String containerId = "c".repeat(64);
		this.<Map<Integer, String>>field("execIdToContainerId").put(1001, containerId);

		InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
		when(client.inspectContainerCmd(containerId)).thenReturn(inspectCmd);
		when(inspectCmd.exec()).thenReturn(inspection);
		when(state.getStatus()).thenReturn("exited");
		when(state.getRunning()).thenReturn(false);

		Method cancel = PodmanBackend.class.getDeclaredMethod(
				"tryCancelUnstartedConsumerContainer", int.class, String.class, boolean.class);
		cancel.setAccessible(true);

		boolean result = (boolean) cancel.invoke(backend, 1001, containerId, false);

		assertFalse(result);
		verify(client, never()).removeContainerCmd(containerId);
		assertTrue(this.<Map<?, ?>>field("execIdToContainerId").containsKey(1001));
	}

	@Test
	public void getActiveExecutionIdsReconcilesRefusedBuildWhenBuildExecutionsNonEmptyAndPendingArtifactsEmpty()
			throws Exception {
		String containerId = "d".repeat(64);
		this.<Map<Integer, String>>field("buildExecutions").put(1001, containerId);
		this.<Map<Integer, String>>field("execIdToContainerId").put(1001, containerId);
		this.<Map<Integer, Integer>>field("execIdToPartitionIndex").put(1001, 0);

		InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
		when(client.inspectContainerCmd(containerId)).thenReturn(inspectCmd);
		when(inspectCmd.exec()).thenReturn(inspection);
		when(inspection.getId()).thenReturn(containerId);
		ContainerConfig buildConfig = mock(ContainerConfig.class);
		when(buildConfig.getLabels()).thenReturn(Map.of(
				"starexec.managed", "true",
				"starexec.label.version", "2",
				"starexec.kind", "job-pair",
				"starexec.build.artifact", "1",
				"starexec.pair.id", "1",
				"starexec.attempt", "1",
				"starexec.exec.id", "1001"
		));
		when(inspection.getConfig()).thenReturn(buildConfig);
		when(state.getStatus()).thenReturn("created");
		when(state.getRunning()).thenReturn(false);

		try (MockedStatic<SolverBuildCreations> creations = mockStatic(SolverBuildCreations.class);
			 MockedStatic<SolverBuildArtifacts> artifacts = mockStatic(SolverBuildArtifacts.class)) {
			creations.when(SolverBuildCreations::unreleased).thenReturn(List.of());
			creations.when(SolverBuildCreations::pending).thenReturn(List.of());
			creations.when(SolverBuildCreations::maximumExecutionId).thenReturn(1001);
			artifacts.when(SolverBuildArtifacts::refusedPodmanCreations).thenReturn(List.of());

			backend.getActiveExecutionIds();

			artifacts.verify(SolverBuildArtifacts::refusedPodmanCreations);
		}
	}
}
