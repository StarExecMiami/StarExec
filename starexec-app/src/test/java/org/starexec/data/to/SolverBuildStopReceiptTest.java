package org.starexec.data.to;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class SolverBuildStopReceiptTest {
    private SolverBuildStopReceipt receipt(JsonObject facts) {
        return SolverBuildStopReceipt.observed("podman", 5, 42, 1, "podman:7:container", "container",
                Instant.parse("2026-01-01T00:00:00Z"), facts);
    }

    private JsonObject facts() {
        JsonObject facts = new JsonObject(); facts.addProperty("status", "exited");
        facts.addProperty("note", "Unicode \u03b1 and quoted input"); return facts;
    }

    private String digest(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void theDigestCoversExactUtf8BytesRatherThanReserialization() throws Exception {
        SolverBuildStopReceipt original = receipt(facts());
        assertEquals(digest(original.payload()), original.sha256());
        String spaced = " " + original.payload() + "\n";
        SolverBuildStopReceipt stored = SolverBuildStopReceipt.restored(spaced, digest(spaced));
        assertEquals(spaced, stored.payload());
        assertNotEquals(original.sha256(), stored.sha256());
        assertTrue(stored.matches(5, 42, 1, "podman:7:container"));
    }

    @Test void laterCallerMutationsCannotChangeTheConservedObservation() {
        JsonObject facts = facts(); SolverBuildStopReceipt stored = receipt(facts);
        String payload = stored.payload(); facts.addProperty("status", "running");
        stored.facts().addProperty("status", "running");
        assertEquals(payload, stored.payload()); assertEquals("exited", stored.facts().get("status").getAsString());
    }

    @Test void identityMatchingDoesNotCoerceAnotherExecution() {
        var stored = receipt(facts());
        assertTrue(stored.matches(5, 42, 1, "podman:7:container"));
        assertFalse(stored.matches(6, 42, 1, "podman:7:container"));
        assertFalse(stored.matches(5, 43, 1, "podman:7:container"));
        assertFalse(stored.matches(5, 42, 2, "podman:7:container"));
        assertFalse(stored.matches(5, 42, 1, "podman:8:container"));
    }

    @Test void changedPayloadOrDigestCannotBeRestored() {
        var stored = receipt(facts());
        assertThrows(IllegalArgumentException.class, () -> SolverBuildStopReceipt.restored(stored.payload() + " ", stored.sha256()));
        assertThrows(IllegalArgumentException.class, () -> SolverBuildStopReceipt.restored(stored.payload(), "0".repeat(64)));
    }

    @ParameterizedTest @ValueSource(strings = { "version", "artifact_id", "pair_id", "attempt_no" })
    void missingOrStringIdentityIsRejected(String field) throws Exception {
        JsonObject object = JsonParser.parseString(receipt(facts()).payload()).getAsJsonObject();
        object.addProperty(field, "1"); String payload = object.toString();
        assertThrows(IllegalArgumentException.class, () -> SolverBuildStopReceipt.restored(payload, digest(payload)));
        object.remove(field); String missing = object.toString();
        assertThrows(IllegalArgumentException.class, () -> SolverBuildStopReceipt.restored(missing, digest(missing)));
    }

    @ParameterizedTest @ValueSource(strings = { "backend", "engine_identity", "resource_id", "observed_at" })
    void missingOrBlankTextIsRejected(String field) throws Exception {
        JsonObject object = JsonParser.parseString(receipt(facts()).payload()).getAsJsonObject();
        object.addProperty(field, " "); String payload = object.toString();
        assertThrows(IllegalArgumentException.class, () -> SolverBuildStopReceipt.restored(payload, digest(payload)));
        object.remove(field); String missing = object.toString();
        assertThrows(IllegalArgumentException.class, () -> SolverBuildStopReceipt.restored(missing, digest(missing)));
    }

    @ParameterizedTest @ValueSource(strings = { "{}", "[]", "null", "invalid", "{\"version\":2}" })
    void incompleteOrMalformedPayloadCannotBeRestored(String payload) {
        assertThrows(IllegalArgumentException.class, () -> SolverBuildStopReceipt.restored(payload, digest(payload)));
    }

    @Test void absentFactsOrObservedTimeCannotBeEncoded() {
        assertThrows(IllegalArgumentException.class, () -> receipt(new JsonObject()));
        assertThrows(IllegalArgumentException.class, () -> receipt(null));
        assertThrows(IllegalArgumentException.class, () -> SolverBuildStopReceipt.observed("podman", 5, 42, 1,
                "engine", "container", null, facts()));
    }
}
