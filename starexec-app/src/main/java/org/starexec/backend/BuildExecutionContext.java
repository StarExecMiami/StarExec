package org.starexec.backend;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Application-owned identity and output location for one build attempt. */
public record BuildExecutionContext(long artifactId, int pairId, int attemptNo,
                                    UUID token, Path source, Path staging) {
    public static final String OUTPUT_VARIABLE = "STAREXEC_BUILD_OUTPUT_DIR";

    public BuildExecutionContext {
        if (artifactId <= 0 || pairId <= 0 || attemptNo <= 0) {
            throw new IllegalArgumentException("Build identity must name an artifact and pair attempt");
        }
        Objects.requireNonNull(token, "token");
        requireAbsolute(source);
        requireAbsolute(staging);
        if (source.startsWith(staging) || staging.startsWith(source)) {
            throw new IllegalArgumentException("Build output must be separate from its source");
        }
    }

    private static void requireAbsolute(Path path) {
        if (path == null || !path.isAbsolute() || path.getParent() == null || !path.equals(path.normalize())) {
            throw new IllegalArgumentException("Build paths must be absolute, normalized non-root paths");
        }
    }

    /** Check the independently captured attempt before creating an execution. */
    public void requireMatches(int submittedPair, Integer capturedAttempt) {
        if (submittedPair != pairId || capturedAttempt == null || capturedAttempt != attemptNo) {
            throw new IllegalArgumentException("Build context does not match the submitted pair attempt");
        }
    }

    /** Execution tokens stay in the control plane; the runner receives only its encoded output path. */
    public Map<String, String> environment() {
        return Map.of(OUTPUT_VARIABLE, Base64.getEncoder().encodeToString(
                staging.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Override public String toString() {
        return "BuildExecutionContext[pairId=" + pairId + ", attemptNo=" + attemptNo + "]";
    }
}
