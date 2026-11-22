package org.starexec.backend;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.to.SolverBuildStopReceipt;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

public class KubernetesSuspendedBuildFailureTest {

	private final KubernetesNativeBackend backend = new KubernetesNativeBackend();
	private final UUID token = UUID.randomUUID();
	private final BuildExecutionContext build = new BuildExecutionContext(
			5L, 42, 1, token, Path.of("/inputs/source"), Path.of("/outputs/build"));
	private final ExecutionRef execution = new ExecutionRef(7, "build-job", "job-uid");

	@SuppressWarnings("unchecked")
	private <T> T field(String name) throws Exception {
		Field f = KubernetesNativeBackend.class.getDeclaredField(name);
		f.setAccessible(true);
		return (T) f.get(backend);
	}

	private void setField(String name, Object value) throws Exception {
		Field f = KubernetesNativeBackend.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(backend, value);
	}

	private void primeTracking(KubernetesClient client) throws Exception {
		setField("namespace", "starexec");
		setField("kubernetesClient", client);
		this.<Set<Integer>>field("jobsHoldingSlot").add(7);
		this.<AtomicInteger>field("activeJobCount").set(1);
		this.<Map<Integer, String>>field("execIdToJobName").put(7, "build-job");
		this.<Map<Integer, String>>field("execIdToJobUid").put(7, "job-uid");
		this.<Map<Integer, Integer>>field("execIdToPairId").put(7, 42);
		this.<Map<Integer, Integer>>field("execIdToAttemptNo").put(7, 1);
		this.<Map<Integer, Path>>field("execIdToOutputDir").put(7, Path.of("/outputs/build"));
	}

	@Test
	public void postCreateFailureOnSuspendedBuildCancelsAndSettlesNeverStartedPair() throws Exception {
		try (var api = new KubernetesBuildLaunchFenceTest.Api();
			 var client = api.client();
			 MockedStatic<SolverBuildArtifacts> artifacts = mockStatic(SolverBuildArtifacts.class);
			 MockedStatic<JobPairs> pairs = mockStatic(JobPairs.class)) {
			AtomicReference<SolverBuildArtifacts.Artifact> artifact = new AtomicReference<>(
					new SolverBuildArtifacts.Artifact(5, 42, 1, 9, 9, 10, 1, "/inputs/source", "/outputs/build",
							null, null, token, null, null, "PLANNED"));
			AtomicReference<SolverBuildArtifacts.StoredStopReceipt> receipt = new AtomicReference<>();

			artifacts.when(() -> SolverBuildArtifacts.find(42, 1)).thenAnswer(inv -> Optional.of(artifact.get()));
			artifacts.when(() -> SolverBuildArtifacts.findCurrent(42)).thenAnswer(inv -> Optional.of(artifact.get()));
			artifacts.when(() -> SolverBuildArtifacts.findStopReceipt(42, 1))
					.thenAnswer(inv -> Optional.ofNullable(receipt.get()));
			artifacts.when(() -> SolverBuildArtifacts.recordUnstarted(eq(42), eq(1), eq(token),
					eq("kubernetes:starexec:7:job-uid"), any()))
					.thenAnswer(inv -> {
						SolverBuildStopReceipt value = inv.getArgument(4);
						Instant now = Instant.now();
						artifact.set(new SolverBuildArtifacts.Artifact(5, 42, 1, 9, 9, 10, 1,
								"/inputs/source", "/outputs/build", null, null, token,
								"kubernetes:starexec:7:job-uid", now, "REJECTED"));
						receipt.set(new SolverBuildArtifacts.StoredStopReceipt(value, now));
						return true;
					});
			pairs.when(() -> JobPairs.retryNeverStartedPair(42, 7, 1))
					.thenReturn(JobPairs.NeverStartedRetryResult.REQUEUED);

			primeTracking(client);

			Method handleFailure = KubernetesNativeBackend.class.getDeclaredMethod(
					"handlePostCreateBuildFailure", BuildExecutionContext.class, ExecutionRef.class,
					int.class, Integer.class, Exception.class);
			handleFailure.setAccessible(true);
			int result = (int) handleFailure.invoke(backend, build, execution, 42, 1,
					new IllegalStateException("resume refused"));

			assertEquals(7, result);
			assertTrue(api.deleted, "suspended build Job must be deleted after non-execution proof commits");
			assertEquals(0, this.<AtomicInteger>field("activeJobCount").get());
			assertTrue(this.<Map<?, ?>>field("ambiguousSubmissions").isEmpty());
			pairs.verify(() -> JobPairs.retryNeverStartedPair(42, 7, 1));
		}
	}

	@Test
	public void postCreateFailureRetainsTrackingWhenServerAlreadyResumedJob() throws Exception {
		try (var api = new KubernetesBuildLaunchFenceTest.Api();
			 var client = api.client()) {
			Job resumed = new JobBuilder(KubernetesBuildLaunchFenceTest.initialJob())
					.editMetadata().withGeneration(2L).endMetadata()
					.editSpec().withSuspend(false).endSpec().build();
			api.current = resumed;
			primeTracking(client);

			Method handleFailure = KubernetesNativeBackend.class.getDeclaredMethod(
					"handlePostCreateBuildFailure", BuildExecutionContext.class, ExecutionRef.class,
					int.class, Integer.class, Exception.class);
			handleFailure.setAccessible(true);
			int result = (int) handleFailure.invoke(backend, build, execution, 42, 1,
					new RuntimeException("lost update reply"));

			assertEquals(7, result);
			assertFalse(api.deleted, "already-resumed Job must not be deleted");
			assertEquals("job-uid", this.<Map<Integer, String>>field("execIdToJobUid").get(7));
			assertEquals(1, this.<AtomicInteger>field("activeJobCount").get());
		}
	}
}
