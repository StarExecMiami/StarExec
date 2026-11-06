package org.starexec.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.to.SolverBuildStopReceipt;

class KubernetesBuildUnstartedObserverTest {
    private final UUID token = UUID.randomUUID();
    private final String engine = "kubernetes:starexec:7:job-uid";
    private final KubernetesBuildStopObserver.Identity identity = KubernetesBuildStopObserver.identify(
            "starexec", KubernetesBuildLaunchFenceTest.initialJob());
    private SolverBuildArtifacts.Artifact row;
    private SolverBuildArtifacts.StoredStopReceipt stored;
    private MockedStatic<SolverBuildArtifacts> dao;
    private String failure;
    private int records;

    private SolverBuildArtifacts.Artifact artifact(long id, UUID executionToken, String boundEngine, Instant stopped, String state) {
        return new SolverBuildArtifacts.Artifact(id, 42, 1, 9, 9, 10, 1, "/inputs/source", "/outputs/build",
                null, null, executionToken, boundEngine, stopped, state);
    }

    @BeforeEach void setup() throws Exception {
        row = artifact(5, token, null, null, "PLANNED");
        dao = mockStatic(SolverBuildArtifacts.class);
        dao.when(() -> SolverBuildArtifacts.find(42, 1)).thenAnswer(call -> Optional.ofNullable(row));
        dao.when(() -> SolverBuildArtifacts.findStopReceipt(42, 1)).thenAnswer(call -> Optional.ofNullable(stored));
        dao.when(() -> SolverBuildArtifacts.recordUnstarted(eq(42), eq(1), eq(token), eq(engine), any())).thenAnswer(call -> {
            records++;
            if ("refused".equals(failure)) return false;
            if ("database".equals(failure)) throw new SQLException("unavailable");
            SolverBuildStopReceipt receipt = call.getArgument(4);
            if (stored != null) return stored.receipt().payload().equals(receipt.payload());
            Instant committed = Instant.parse("2026-01-01T00:00:00Z");
            row = artifact(5, token, engine, committed, "REJECTED");
            stored = new SolverBuildArtifacts.StoredStopReceipt(receipt, committed);
            if ("lost-reply".equals(failure)) throw new SQLException("commit acknowledgement lost");
            return true;
        });
    }

    @AfterEach void close() { dao.close(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void conservesTheAcknowledgedBarrierBeforeReturningTheCleanupVersion(boolean admitted) throws Exception {
        if (admitted) row = artifact(5, token, engine, null, "PLANNED");
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client()) {
            var fenced = KubernetesBuildUnstartedObserver.record(client, "starexec", identity, api.current);
            assertEquals("18", fenced.getMetadata().getResourceVersion());
            assertEquals(1, records); assertEquals("REJECTED", row.state());
            assertTrue(KubernetesBuildUnstartedObserver.hasConservedReceipt("starexec", identity));
            JsonObject facts = stored.receipt().facts();
            assertEquals("never_started", facts.get("disposition").getAsString());
            assertEquals("17", facts.get("fenced_resource_version").getAsString());
            assertEquals("18", facts.get("job_resource_version").getAsString());
            assertEquals("41", facts.get("pod_list_resource_version").getAsString());
            assertTrue(facts.get("suspended").getAsBoolean()); assertEquals(0, facts.get("pod_count").getAsInt());
            assertFalse(facts.has("containers"));
            dao.verify(() -> SolverBuildArtifacts.recordStopReceipt(anyInt(), anyInt(), any(), anyString(), any()), never());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "artifact", "token", "engine", "validated", "stopped"})
    void aConflictingRegistrationCannotChangeTheProvider(String conflict) throws Exception {
        switch (conflict) {
            case "missing" -> row = null;
            case "artifact" -> row = artifact(6, token, null, null, "PLANNED");
            case "token" -> row = artifact(5, null, null, null, "PLANNED");
            case "engine" -> row = artifact(5, token, "another-engine", null, "PLANNED");
            case "validated" -> row = artifact(5, token, null, null, "VALIDATED");
            case "stopped" -> row = artifact(5, token, null, Instant.now(), "PLANNED");
            default -> throw new AssertionError(conflict);
        }
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client()) {
            assertThrows(IllegalStateException.class, () -> KubernetesBuildUnstartedObserver.record(client, "starexec", identity, api.current));
            assertEquals(0, api.writes.get()); assertEquals(0, records);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"refused", "database", "lost-reply"})
    void anUnacknowledgedCatalogWriteCannotAuthorizeCleanup(String kind) throws Exception {
        failure = kind;
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client()) {
            assertThrows(Exception.class, () -> KubernetesBuildUnstartedObserver.record(client, "starexec", identity, api.current));
            assertTrue(KubernetesBuildLaunchFence.isFenced(api.current));
            assertEquals("lost-reply".equals(kind), KubernetesBuildUnstartedObserver.hasConservedReceipt("starexec", identity));
            if (kind.equals("lost-reply")) {
                failure = null; String first = stored.receipt().payload();
                assertNotNull(KubernetesBuildUnstartedObserver.record(client, "starexec", identity, api.current));
                assertEquals(first, stored.receipt().payload()); assertEquals(1, api.writes.get());
            }
        }
    }

    @Test void retriesTheExactCommittedBytesWhileTheJobIsBeingRemoved() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client()) {
            KubernetesBuildUnstartedObserver.record(client, "starexec", identity, api.current);
            String first = stored.receipt().payload(); Instant timestamp = stored.recordedAt();
            api.current.getMetadata().setResourceVersion("19");
            api.current.getMetadata().setDeletionTimestamp("2026-01-01T00:00:00Z");
            var retry = KubernetesBuildUnstartedObserver.record(client, "starexec", identity, api.current);
            assertEquals("19", retry.getMetadata().getResourceVersion()); assertEquals(2, records);
            assertEquals(first, stored.receipt().payload()); assertEquals(timestamp, stored.recordedAt());
            assertEquals(1, api.writes.get());
        }
    }

    @Test void aProviderConflictCannotRecordNonExecution() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client()) {
            var original = api.current;
            var enabled = new io.fabric8.kubernetes.api.model.batch.v1.JobBuilder(original).editSpec().withSuspend(false).endSpec().build();
            client.batch().v1().jobs().inNamespace("starexec").resource(enabled).lockResourceVersion("17").replace();
            assertThrows(io.fabric8.kubernetes.client.KubernetesClientException.class,
                    () -> KubernetesBuildUnstartedObserver.record(client, "starexec", identity, original));
            assertEquals(0, records); assertNull(stored);
        }
    }

    @Test void anAbsentJobCannotSupplyTheFirstProof() throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client()) {
            assertThrows(IllegalStateException.class, () -> KubernetesBuildUnstartedObserver.record(client, "starexec", identity, null));
            assertFalse(KubernetesBuildUnstartedObserver.hasConservedReceipt("starexec", identity));
            assertEquals(0, api.writes.get()); assertEquals(0, records);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"disposition", "namespace", "job_uid", "job_generation", "suspended", "pod_count", "fenced_resource_version", "pod_list_resource_version"})
    void restoredProofMustKeepItsTypedProviderFacts(String field) throws Exception {
        try (var api = new KubernetesBuildLaunchFenceTest.Api(); var client = api.client()) {
            KubernetesBuildUnstartedObserver.record(client, "starexec", identity, api.current);
            JsonObject body = JsonParser.parseString(stored.receipt().payload()).getAsJsonObject();
            var facts = body.getAsJsonObject("facts");
            if (field.equals("pod_count")) facts.addProperty(field, 0.5);
            else if (field.equals("job_generation")) facts.addProperty(field, 1.5);
            else if (field.equals("fenced_resource_version")) facts.addProperty(field, facts.get("job_resource_version").getAsString());
            else if (field.equals("pod_list_resource_version")) facts.addProperty(field, "");
            else facts.addProperty(field, "wrong");
            String payload = body.toString();
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
            stored = new SolverBuildArtifacts.StoredStopReceipt(SolverBuildStopReceipt.restored(payload, digest), stored.recordedAt());
            assertFalse(KubernetesBuildUnstartedObserver.hasConservedReceipt("starexec", identity));
            assertThrows(IllegalStateException.class, () -> KubernetesBuildUnstartedObserver.record(client, "starexec", identity, api.current));
            assertEquals(1, records);
        }
    }
}
