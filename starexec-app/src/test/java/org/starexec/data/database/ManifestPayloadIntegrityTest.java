package org.starexec.data.database;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.app.RESTServices;
import org.starexec.data.security.JobSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.util.SessionUtil;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Unit tests for manifest byte retention, digest verification, and REST serialization without a DB. */
class ManifestPayloadIntegrityTest {

    @Test
    void retainedPayloadMatchesSha256DespiteJsonbReordering() throws Exception {
        String exactPayload = "{\"manifestVersion\":\"1.0.0\",\"schemaVersion\":1,\"pairId\":42,\"attemptNo\":1,\"jobId\":7,"
                + "\"provenance\":{\"type\":\"LIVE\",\"generatedAt\":\"2026-01-01 00:00:00.0\"},"
                + "\"execution\":{\"backendType\":\"local\",\"pairStatusCode\":7,\"finalized\":true,\"maxMemory\":9007199254740992},"
                + "\"inputs\":{\"benchId\":1,\"benchName\":\"b\"},\"warnings\":[]}";
        String jsonbReordered = "{\"inputs\": {\"benchId\": 1, \"benchName\": \"b\"}, \"jobId\": 7, "
                + "\"execution\": {\"finalized\": true, \"maxMemory\": 9007199254740992, \"backendType\": \"local\", \"pairStatusCode\": 7}, "
                + "\"pairId\": 42, \"warnings\": [], \"attemptNo\": 1, "
                + "\"provenance\": {\"generatedAt\": \"2026-01-01 00:00:00.0\", \"type\": \"LIVE\"}, "
                + "\"schemaVersion\": 1, \"manifestVersion\": \"1.0.0\"}";
        String expectedSha = sha256(exactPayload);

        JobPairs.PairReproManifestResult manifest = new JobPairs.PairReproManifestResult(
                42, 1, 2, 0, 1, jsonbReordered, exactPayload, expectedSha, 7,
                new Timestamp(1000L), new Timestamp(2000L), new Timestamp(3000L));

        assertEquals(exactPayload, manifest.getVerifiedManifestJson());

        HttpServletRequest request = mock(HttpServletRequest.class);
        try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
             MockedStatic<JobSecurity> security = Mockito.mockStatic(JobSecurity.class);
             MockedStatic<JobPairs> pairs = Mockito.mockStatic(JobPairs.class)) {
            session.when(() -> SessionUtil.getUserId(request)).thenReturn(9);
            security.when(() -> JobSecurity.canUserSeeJobWithPair(42, 9)).thenReturn(new ValidatorStatusCode(true));
            pairs.when(() -> JobPairs.getPairReproManifest(42, 1)).thenReturn(manifest);
            pairs.when(() -> JobPairs.getManifestStateName(2)).thenCallRealMethod();
            pairs.when(() -> JobPairs.getManifestProvenanceName(0)).thenCallRealMethod();

            Response response = new RESTServices().getPairReproducibilityManifest(42, 1, request);
            assertEquals(200, response.getStatus());
            JsonObject body = JsonParser.parseString(response.getEntity().toString()).getAsJsonObject();
            assertTrue(body.get("manifestBytesAvailable").getAsBoolean());
            String published = body.get("manifestJson").getAsString();
            assertEquals(exactPayload, published);
            assertEquals(expectedSha, sha256(published));
        }
    }

    @Test
    void historicalV1PayloadIsRecoveredWhenDigestMatchesProducerOrder() throws Exception {
        String original = "{\"manifestVersion\":\"1.0.0\",\"schemaVersion\":1,\"pairId\":42,\"attemptNo\":1,\"jobId\":7,"
                + "\"provenance\":{\"type\":\"LIVE\",\"generatedAt\":\"2026-01-01 00:00:00.0\"},"
                + "\"execution\":{\"backendType\":\"local\",\"pairStatusCode\":7,\"finalized\":true},"
                + "\"inputs\":{\"benchId\":1,\"benchName\":\"b\"},\"warnings\":[]}";
        String jsonbReordered = "{\"warnings\":[],\"inputs\":{\"benchName\":\"b\",\"benchId\":1},\"jobId\":7,"
                + "\"execution\":{\"finalized\":true,\"pairStatusCode\":7,\"backendType\":\"local\"},"
                + "\"pairId\":42,\"attemptNo\":1,\"provenance\":{\"generatedAt\":\"2026-01-01 00:00:00.0\",\"type\":\"LIVE\"},"
                + "\"schemaVersion\":1,\"manifestVersion\":\"1.0.0\"}";
        String expectedSha = sha256(original);

        JobPairs.PairReproManifestResult manifest = new JobPairs.PairReproManifestResult(
                42, 1, 2, 0, 1, jsonbReordered, null, expectedSha, 7,
                new Timestamp(1000L), new Timestamp(2000L), new Timestamp(3000L));

        assertEquals(original, manifest.getVerifiedManifestJson());
    }

    @Test
    void mismatchedDigestOrDocumentReturnsNullPayload() throws Exception {
        String payload = "{\"manifestVersion\":\"1.0.0\",\"schemaVersion\":1,\"pairId\":42}";
        String mutatedDoc = "{\"manifestVersion\":\"1.0.0\",\"schemaVersion\":1,\"pairId\":43}";
        JobPairs.PairReproManifestResult mismatch = new JobPairs.PairReproManifestResult(
                42, 1, 2, 0, 1, mutatedDoc, payload, sha256(payload), 7,
                new Timestamp(1000L), new Timestamp(2000L), new Timestamp(3000L));

        assertNull(mismatch.getVerifiedManifestJson());
    }

    @Test
    void sameManifestContentIgnoresGeneratedAtOnlyAndDistinguishesAdjacentLongs() {
        String a = "{\"provenance\":{\"type\":\"LIVE\",\"generatedAt\":\"2026-01-01 00:00:00.1\"},\"execution\":{\"maxMemory\":9007199254740992}}";
        String b = "{\"execution\":{\"maxMemory\":9007199254740992},\"provenance\":{\"generatedAt\":\"2026-01-01 00:00:05.9\",\"type\":\"LIVE\"}}";
        String c = "{\"execution\":{\"maxMemory\":9007199254740993},\"provenance\":{\"generatedAt\":\"2026-01-01 00:00:00.1\",\"type\":\"LIVE\"}}";

        assertTrue(JobPairs.sameManifestContent(a, b));
        assertFalse(JobPairs.sameManifestContent(a, c));
    }

    private static String sha256(String raw) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
