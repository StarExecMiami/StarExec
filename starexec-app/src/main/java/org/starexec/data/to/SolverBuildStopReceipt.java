package org.starexec.data.to;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

/** Conserved exact bytes of an observation made by a trusted engine observer. */
public final class SolverBuildStopReceipt {
    private final String payload;
    private final String sha256;
    private final JsonObject envelope;

    private SolverBuildStopReceipt(String payload, String sha256) {
        if (payload == null || sha256 == null || !digest(payload).equals(sha256)) {
            throw new IllegalArgumentException("Build stop receipt digest differs");
        }
        try {
            envelope = JsonParser.parseString(payload).getAsJsonObject();
            if (positiveNumber(envelope, "version") != 1) throw new IllegalArgumentException("Unknown receipt version");
            positiveNumber(envelope, "artifact_id"); positiveNumber(envelope, "pair_id");
            positiveNumber(envelope, "attempt_no");
            if (!Set.of("local", "podman", "kubernetes").contains(text(envelope, "backend"))) {
                throw new IllegalArgumentException("Unknown build stop backend");
            }
            text(envelope, "engine_identity"); text(envelope, "resource_id");
            Instant.parse(text(envelope, "observed_at"));
            if (!envelope.get("facts").isJsonObject() || envelope.getAsJsonObject("facts").size() == 0) {
                throw new IllegalArgumentException("Build stop receipt has no engine facts");
            }
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("Invalid build stop receipt envelope", malformed);
        }
        this.payload = payload;
        this.sha256 = sha256;
    }

    /** The caller must validate provider-specific physical evidence before encoding it. */
    public static SolverBuildStopReceipt observed(String backend, long artifactId, int pairId, int attemptNo,
                                                 String engineIdentity, String resourceId, Instant observedAt,
                                                 JsonObject facts) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("version", 1); envelope.addProperty("backend", backend);
        envelope.addProperty("artifact_id", artifactId); envelope.addProperty("pair_id", pairId);
        envelope.addProperty("attempt_no", attemptNo); envelope.addProperty("engine_identity", engineIdentity);
        envelope.addProperty("resource_id", resourceId);
        envelope.addProperty("observed_at", observedAt == null ? null : observedAt.toString());
        envelope.add("facts", facts == null ? null : facts.deepCopy());
        String payload = new Gson().toJson(envelope);
        return new SolverBuildStopReceipt(payload, digest(payload));
    }

    /** Checks stored integrity and identity structure; it does not establish a new physical observation. */
    public static SolverBuildStopReceipt restored(String payload, String sha256) {
        return new SolverBuildStopReceipt(payload, sha256);
    }

    public String payload() { return payload; }
    public String sha256() { return sha256; }
    public String backend() { return envelope.get("backend").getAsString(); }
    public String resourceId() { return envelope.get("resource_id").getAsString(); }
    public JsonObject facts() { return envelope.getAsJsonObject("facts").deepCopy(); }

    public boolean matches(long artifactId, int pairId, int attemptNo, String engineIdentity) {
        return positiveNumber(envelope, "artifact_id") == artifactId
                && positiveNumber(envelope, "pair_id") == pairId
                && positiveNumber(envelope, "attempt_no") == attemptNo
                && text(envelope, "engine_identity").equals(engineIdentity);
    }

    private static long positiveNumber(JsonObject object, String key) {
        var value = object.getAsJsonPrimitive(key);
        if (!value.isNumber()) throw new IllegalArgumentException("Receipt identity must be numeric");
        long number = value.getAsBigDecimal().longValueExact();
        if (number <= 0) throw new IllegalArgumentException("Receipt identity must be positive");
        return number;
    }

    private static String text(JsonObject object, String key) {
        var value = object.getAsJsonPrimitive(key);
        if (!value.isString() || value.getAsString().isBlank()) throw new IllegalArgumentException("Missing receipt identity");
        return value.getAsString();
    }

    private static String digest(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }
}
