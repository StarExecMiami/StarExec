package org.starexec.test.junit.database;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.app.RESTServices;
import org.starexec.data.database.Common;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.Jobs;
import org.starexec.data.security.JobSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.Job;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.data.to.pipelines.JoblineStage;
import org.starexec.test.util.DatabaseTestSupport;
import org.starexec.util.SessionUtil;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Real PostgreSQL persistence and REST serialization; job metadata is a controlled fixture. */
public class ManifestPayloadSqlTest extends Common {
    private static final int COMPLETE = StatusCode.STATUS_COMPLETE.getVal();
    private int userId;
    private int jobId;
    private int pairId;
    private JobPair pair;
    private MockedStatic<JobPairs> pairs;
    private MockedStatic<Jobs> jobs;

    @BeforeAll
    public static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("ManifestPayloadSqlTest");
        Common.initialize();
    }

    @BeforeEach
    public void fixture() throws Exception {
        try (Connection con = Common.getConnection()) {
            userId = insert(con, "INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,disk_quota,job_pair_quota,disk_size)"
                    + " VALUES (?,'Manifest','Test','test',now(),'x',10000,100,0) RETURNING id",
                    "manifest-" + UUID.randomUUID() + "@example.invalid");
            jobId = insert(con, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size) VALUES (?,'manifest-test',1,0) RETURNING id", userId);
            pairId = insert(con, "INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data) VALUES (?,?,1) RETURNING id", jobId, COMPLETE);
        }
        pair = new JobPair();
        pair.setId(pairId);
        pair.setJobId(jobId);
        pair.getStatus().setCode(COMPLETE);
        pair.getBench().setName("benchmark \u03bb <&> \"quoted\"");
        JoblineStage stage = new JoblineStage();
        stage.getSolver().setId(11);
        stage.getSolver().setName("solver fixture");
        stage.getConfiguration().setId(12);
        stage.getConfiguration().setName("configuration fixture");
        pair.getStages().add(stage);
        pairs = Mockito.mockStatic(JobPairs.class, invocation -> {
            if (invocation.getMethod().getName().equals("getPair") && invocation.getArguments().length == 1) {
                assertEquals(pairId, ((Integer) invocation.getArgument(0)).intValue());
                return pair;
            }
            return invocation.callRealMethod();
        });
        jobs = Mockito.mockStatic(Jobs.class);
        jobs.when(() -> Jobs.get(jobId)).thenReturn(new Job());
    }

    @AfterEach
    public void cleanup() throws Exception {
        if (jobs != null) jobs.close();
        if (pairs != null) pairs.close();
        try (Connection con = Common.getConnection()) {
            update(con, "DELETE FROM starexec.job_pairs WHERE id=?", pairId);
            update(con, "DELETE FROM starexec.jobs WHERE id=?", jobId);
            update(con, "DELETE FROM starexec.users WHERE id=?", userId);
        }
    }

    @Test
    public void collectingPayloadCanBeVerifiedAfterJsonbAndRestRoundTrip() throws Exception {
        assertTrue(JobPairs.upsertPairManifestCollecting(pairId));
        assertRetainedPayload(response(1));
    }

    @Test
    public void finalPayloadIsVerifiableForLatestAndExplicitAttempt() throws Exception {
        assertTrue(JobPairs.finalizePairManifest(pairId, 1, COMPLETE));
        JsonObject latest = response(null);
        assertRetainedPayload(latest);
        assertEquals(latest, response(1));
    }

    @Test
    public void repeatedFinalizationIgnoresGenerationTimeWithoutMutatingTheFinalRow() throws Exception {
        assertTrue(JobPairs.finalizePairManifest(pairId, 1, COMPLETE));
        String before = storedRow();
        Thread.sleep(20); // Force a different millisecond in the producer's generatedAt.
        assertTrue(JobPairs.finalizePairManifest(pairId, 1, COMPLETE));
        assertEquals(before, storedRow());
    }

    @Test
    public void changedContentIsRejectedAndFinalRowsRemainImmutable() throws Exception {
        assertTrue(JobPairs.finalizePairManifest(pairId, 1, COMPLETE));
        String before = storedRow();
        pair.getBench().setName("changed benchmark");
        assertFalse(JobPairs.finalizePairManifest(pairId, 1, COMPLETE));
        assertEquals(before, storedRow());
        try (Connection con = Common.getConnection()) {
            assertThrows(SQLException.class, () -> update(con,
                    "UPDATE starexec.job_pair_repro_manifests SET manifest_sha256=? WHERE pair_id=?", "0".repeat(64), pairId));
        }
        assertEquals(before, storedRow());
    }

    @Test
    public void adjacentLongValuesAboveDoublePrecisionAreNotIdempotent() throws Exception {
        Job job = new Job();
        job.setMaxMemory(9007199254740992L);
        jobs.when(() -> Jobs.get(jobId)).thenReturn(job);
        assertTrue(JobPairs.finalizePairManifest(pairId, 1, COMPLETE));
        String before = storedRow();

        job.setMaxMemory(9007199254740993L);
        assertFalse(JobPairs.finalizePairManifest(pairId, 1, COMPLETE));
        assertEquals(before, storedRow());
    }

    @Test
    public void adjacentLongDocumentValueCannotAuthenticateDifferentPayloadBytes() throws Exception {
        Job job = new Job();
        job.setMaxMemory(9007199254740992L);
        jobs.when(() -> Jobs.get(jobId)).thenReturn(job);
        assertTrue(JobPairs.upsertPairManifestCollecting(pairId));
        String original = response(1).get("manifestJson").getAsString();
        try (Connection con = Common.getConnection()) {
            update(con, "UPDATE starexec.job_pair_repro_manifests SET manifest_json="
                    + "jsonb_set(manifest_json,'{execution,maxMemory}','9007199254740993'::jsonb) WHERE pair_id=?", pairId);
        }

        JsonObject result = response(1);
        assertEquals("9007199254740993", result.getAsJsonObject("manifest")
                .getAsJsonObject("execution").get("maxMemory").getAsString());
        assertEquals(digest(original), result.get("manifestSha256").getAsString());
        assertFalse(result.get("manifestBytesAvailable").getAsBoolean());
        assertFalse(result.has("manifestJson"));
    }

    @Test
    public void equivalentDecimalDocumentRetainsTheOriginalDigestCoveredBytes() throws Exception {
        Job job = new Job();
        job.setMaxMemory(9007199254740992L);
        jobs.when(() -> Jobs.get(jobId)).thenReturn(job);
        assertTrue(JobPairs.upsertPairManifestCollecting(pairId));
        String original = response(1).get("manifestJson").getAsString();
        try (Connection con = Common.getConnection()) {
            update(con, "UPDATE starexec.job_pair_repro_manifests SET manifest_json="
                    + "jsonb_set(manifest_json,'{execution,maxMemory}','9007199254740992.000'::jsonb) WHERE pair_id=?", pairId);
        }

        JsonObject result = response(1);
        assertEquals(original, result.get("manifestJson").getAsString());
        assertRetainedPayload(result);
    }

    @Test
    public void derivedLegacyManifestAlsoRetainsVerifiableBytes() throws Exception {
        assertNotNull(JobPairs.getPairReproManifest(pairId, null));
        JsonObject result = response(null);
        assertEquals("FINAL_DERIVED", result.get("state").getAsString());
        assertRetainedPayload(result);
    }

    @Test
    public void completeProducerPayloadCanBeRecoveredAfterDroppingItsRetainedCopy() throws Exception {
        assertTrue(JobPairs.upsertPairManifestCollecting(pairId));
        String original = response(1).get("manifestJson").getAsString();
        try (Connection con = Common.getConnection()) {
            update(con, "UPDATE starexec.job_pair_repro_manifests SET manifest_payload=NULL WHERE pair_id=?", pairId);
        }
        JsonObject result = response(1);
        assertVerifiable(result);
        assertEquals(original, result.get("manifestJson").getAsString());
    }

    @Test
    public void rawBytesMatchingTheDigestMustAlsoMatchThePublishedDocument() throws Exception {
        assertTrue(JobPairs.upsertPairManifestCollecting(pairId));
        try (Connection con = Common.getConnection()) {
            update(con, "UPDATE starexec.job_pair_repro_manifests SET manifest_json=?::jsonb WHERE pair_id=?", legacyPayload(), pairId);
        }
        JsonObject result = response(1);
        assertFalse(result.get("manifestBytesAvailable").getAsBoolean());
        assertFalse(result.has("manifestJson"));
    }

    @Test
    public void recoverableHistoricalBytesArePublishedWithoutRewritingTheImmutableRow() throws Exception {
        String original = legacyPayload();
        insertLegacy(original, digest(original));
        String before = storedRow();
        JsonObject result = response(null);
        assertVerifiable(result);
        assertEquals(original, result.get("manifestJson").getAsString());
        assertEquals(before, storedRow());
    }

    @Test
    public void unrecoverableHistoricalDigestIsNeverPresentedAsVerifiable() throws Exception {
        insertLegacy(legacyPayload(), "0".repeat(64));
        String before = storedRow();
        JsonObject result = response(null);
        assertTrue(result.has("manifestBytesAvailable"));
        assertFalse(result.get("manifestBytesAvailable").getAsBoolean());
        assertTrue(!result.has("manifestJson") || result.get("manifestJson").isJsonNull());
        assertEquals("0".repeat(64), result.get("manifestSha256").getAsString());
        assertEquals(before, storedRow());
    }

    @Test
    public void olderWriterCannotMakeStaleRawBytesLookCurrent() throws Exception {
        assertTrue(JobPairs.upsertPairManifestCollecting(pairId));
        String replacement = legacyPayload().replace("\"finalized\":true", "\"finalized\":false");
        try (Connection con = Common.getConnection()) {
            // An older binary updates these columns without knowing manifest_payload.
            update(con, "UPDATE starexec.job_pair_repro_manifests SET manifest_json=?::jsonb,manifest_sha256=? WHERE pair_id=?",
                    replacement, digest(replacement), pairId);
        }
        JsonObject result = response(1);
        assertVerifiable(result);
        assertEquals(replacement, result.get("manifestJson").getAsString());
    }

    @Test
    public void unknownHistoricalSchemaIsNotReconstructedByGuessing() throws Exception {
        String payload = legacyPayload().replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        insertLegacy(payload, digest(payload));
        JsonObject result = response(1);
        assertFalse(result.get("manifestBytesAvailable").getAsBoolean());
        assertTrue(!result.has("manifestJson") || result.get("manifestJson").isJsonNull());
    }

    @Test
    public void unauthorizedRequestCannotReadTheStoredPayload() throws Exception {
        assertTrue(JobPairs.finalizePairManifest(pairId, 1, COMPLETE));
        HttpServletRequest request = mock(HttpServletRequest.class);
        try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
             MockedStatic<JobSecurity> security = Mockito.mockStatic(JobSecurity.class)) {
            session.when(() -> SessionUtil.getUserId(request)).thenReturn(userId);
            security.when(() -> JobSecurity.canUserSeeJobWithPair(pairId, userId)).thenReturn(new ValidatorStatusCode(false));
            Response result = new RESTServices().getPairReproducibilityManifest(pairId, null, request);
            assertEquals(403, result.getStatus());
            assertEquals("not available", result.getEntity());
            pairs.verify(() -> JobPairs.getPairReproManifest(pairId, null), Mockito.never());
        }
    }

    private JsonObject response(Integer attempt) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
             MockedStatic<JobSecurity> security = Mockito.mockStatic(JobSecurity.class)) {
            session.when(() -> SessionUtil.getUserId(request)).thenReturn(userId);
            security.when(() -> JobSecurity.canUserSeeJobWithPair(pairId, userId)).thenReturn(new ValidatorStatusCode(true));
            Response result = new RESTServices().getPairReproducibilityManifest(pairId, attempt, request);
            assertEquals(200, result.getStatus());
            return JsonParser.parseString(result.getEntity().toString()).getAsJsonObject();
        }
    }

    private static void assertVerifiable(JsonObject result) throws Exception {
        assertTrue(result.has("manifestJson"), "exact digest-covered bytes must be available");
        assertTrue(result.get("manifestBytesAvailable").getAsBoolean());
        String payload = result.get("manifestJson").getAsString();
        assertEquals(result.get("manifest"), JsonParser.parseString(payload));
        assertEquals(result.get("manifestSha256").getAsString(), digest(payload));
    }

    private void assertRetainedPayload(JsonObject result) throws Exception {
        assertVerifiable(result);
        try (Connection con = Common.getConnection(); PreparedStatement ps = con.prepareStatement(
                "SELECT manifest_payload FROM starexec.job_pair_repro_manifests WHERE pair_id=? AND attempt_no=1")) {
            ps.setInt(1, pairId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(result.get("manifestJson").getAsString(), rs.getString(1));
            }
        }
    }

    private String legacyPayload() {
        return "{\"manifestVersion\":\"1.0.0\",\"schemaVersion\":1,\"pairId\":" + pairId
                + ",\"attemptNo\":1,\"jobId\":" + jobId
                + ",\"provenance\":{\"type\":\"LIVE\",\"generatedAt\":\"2026-01-01 00:00:00.0\"}"
                + ",\"execution\":{\"backendType\":\"local\",\"pairStatusCode\":" + COMPLETE + ",\"finalized\":true}"
                + ",\"inputs\":{\"benchId\":0,\"benchName\":\"fixture\"},\"warnings\":[]}";
    }

    private void insertLegacy(String payload, String digest) throws Exception {
        try (Connection con = Common.getConnection()) {
            update(con, "INSERT INTO starexec.job_pair_repro_manifests(pair_id,attempt_no,state,provenance,schema_version,manifest_json,manifest_sha256,finalized_at)"
                    + " VALUES (?,1,2,0,?,?::jsonb,?,now())", pairId,
                    JsonParser.parseString(payload).getAsJsonObject().get("schemaVersion").getAsInt(), payload, digest);
        }
    }

    private String storedRow() throws Exception {
        try (Connection con = Common.getConnection(); PreparedStatement ps = con.prepareStatement(
                "SELECT row_to_json(m)::text FROM starexec.job_pair_repro_manifests m WHERE pair_id=?")) {
            ps.setInt(1, pairId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private static String digest(String input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static int insert(Connection con, String sql, Object... values) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getInt(1); }
        }
    }

    private static void update(Connection con, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
            ps.executeUpdate();
        }
    }
}
