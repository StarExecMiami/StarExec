package org.starexec.backend;

import com.google.gson.JsonObject;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.to.SolverBuildStopReceipt;

/** Conserves a provider launch barrier without treating non-execution as a build result. */
final class KubernetesBuildUnstartedObserver {
    private KubernetesBuildUnstartedObserver() { }

    static Job record(KubernetesClient client, String namespace, KubernetesBuildStopObserver.Identity expected,
                      Job observed) throws SQLException {
        var artifact = registered(namespace, expected);
        var prior = SolverBuildArtifacts.findStopReceipt(expected.pairId(), expected.attemptNo());
        KubernetesBuildLaunchFence.Observation observation;
        SolverBuildStopReceipt receipt;
        if (prior.isPresent()) {
            require(conserved(namespace, expected, artifact, prior.get()), "Build has no matching conserved non-execution receipt");
            receipt = prior.get().receipt();
            observation = KubernetesBuildLaunchFence.observeConserved(client, namespace, expected, observed,
                    receipt.facts().get("fenced_resource_version").getAsString());
        } else {
            require(Set.of("PLANNED", "STAGED", "REJECTED").contains(artifact.state()) && artifact.stoppedAt() == null,
                    "Build cannot acquire a new non-execution observation");
            observation = KubernetesBuildLaunchFence.preventStart(client, namespace, expected, observed);
            Job fenced = observation.job();
            JsonObject facts = new JsonObject(); facts.addProperty("disposition", "never_started");
            facts.addProperty("namespace", namespace); facts.addProperty("job_name", expected.execution().jobName());
            facts.addProperty("job_uid", expected.execution().jobUid()); facts.addProperty("job_generation", 1);
            facts.addProperty("suspended", true); facts.addProperty("pod_count", 0);
            facts.addProperty("job_resource_version", fenced.getMetadata().getResourceVersion());
            facts.addProperty("fenced_resource_version", fenced.getMetadata().getAnnotations().get(KubernetesBuildLaunchFence.ANNOTATION));
            facts.addProperty("pod_list_resource_version", observation.pods().getMetadata().getResourceVersion());
            receipt = SolverBuildStopReceipt.observed("kubernetes", expected.artifactId(), expected.pairId(), expected.attemptNo(),
                    expected.engine(namespace), expected.execution().jobUid(), Instant.now(), facts);
        }
        require(SolverBuildArtifacts.recordUnstarted(expected.pairId(), expected.attemptNo(), artifact.executionToken(),
                expected.engine(namespace), receipt), "Could not commit the build non-execution receipt");
        return observation.job();
    }

    /** Absence can only reuse a matching rejection and committed provider observation. */
    static boolean hasConservedReceipt(String namespace, KubernetesBuildStopObserver.Identity expected) throws SQLException {
        var artifact = registered(namespace, expected);
        var prior = SolverBuildArtifacts.findStopReceipt(expected.pairId(), expected.attemptNo());
        return prior.isPresent() && conserved(namespace, expected, artifact, prior.get());
    }

    static KubernetesBuildStopObserver.Identity restoreIdentity(String namespace, SolverBuildArtifacts.Artifact artifact,
                                                                SolverBuildArtifacts.StoredStopReceipt stored) {
        require(artifact.executionToken() != null && artifact.engineIdentity() != null, "Build execution binding is unavailable");
        JsonObject facts = stored.receipt().facts();
        require(nonblank(facts, "job_uid") && nonblank(facts, "job_name"), "Build Job identity is unavailable");
        String uid = facts.get("job_uid").getAsString();
        String prefix = "kubernetes:" + namespace + ":"; String suffix = ":" + uid;
        String engine = artifact.engineIdentity();
        require(engine.startsWith(prefix) && engine.endsWith(suffix) && engine.length() > prefix.length() + suffix.length(),
                "Build execution belongs to a different provider");
        int execution;
        try { execution = Integer.parseInt(engine.substring(prefix.length(), engine.length() - suffix.length())); }
        catch (NumberFormatException invalid) { throw new IllegalStateException("Build execution identifier is invalid", invalid); }
        require(execution > 0, "Build execution identifier must be positive");
        var identity = new KubernetesBuildStopObserver.Identity(artifact.id(), artifact.pairId(), artifact.attemptNo(),
                new ExecutionRef(execution, facts.get("job_name").getAsString(), uid));
        require(conserved(namespace, identity, artifact, stored), "Build non-execution receipt is invalid");
        return identity;
    }

    private static SolverBuildArtifacts.Artifact registered(String namespace, KubernetesBuildStopObserver.Identity expected)
            throws SQLException {
        require(expected != null, "Build identity is unavailable");
        var artifact = SolverBuildArtifacts.find(expected.pairId(), expected.attemptNo()).orElseThrow(
                () -> new IllegalStateException("Build artifact registration is unavailable"));
        require(artifact.id() == expected.artifactId() && artifact.pairId() == expected.pairId()
                && artifact.attemptNo() == expected.attemptNo() && artifact.executionToken() != null
                && (artifact.engineIdentity() == null || expected.engine(namespace).equals(artifact.engineIdentity())),
                "Build does not match its registered execution");
        return artifact;
    }

    private static boolean conserved(String namespace, KubernetesBuildStopObserver.Identity expected,
                                      SolverBuildArtifacts.Artifact artifact, SolverBuildArtifacts.StoredStopReceipt stored) {
        if (!Set.of("REJECTED", "DELETED").contains(artifact.state()) || artifact.stoppedAt() == null
                || !expected.engine(namespace).equals(artifact.engineIdentity()) || stored.recordedAt() == null) return false;
        SolverBuildStopReceipt receipt = stored.receipt();
        if (!receipt.matches(expected.artifactId(), expected.pairId(), expected.attemptNo(), expected.engine(namespace))
                || !"kubernetes".equals(receipt.backend()) || !expected.execution().jobUid().equals(receipt.resourceId())) return false;
        JsonObject facts = receipt.facts();
        return text(facts, "disposition", "never_started") && text(facts, "namespace", namespace)
                && text(facts, "job_name", expected.execution().jobName()) && text(facts, "job_uid", expected.execution().jobUid())
                && number(facts, "job_generation", 1) && number(facts, "pod_count", 0)
                && facts.has("suspended") && facts.get("suspended").isJsonPrimitive()
                && facts.getAsJsonPrimitive("suspended").isBoolean() && facts.get("suspended").getAsBoolean()
                && nonblank(facts, "job_resource_version") && nonblank(facts, "fenced_resource_version")
                && nonblank(facts, "pod_list_resource_version")
                && !facts.get("job_resource_version").getAsString().equals(facts.get("fenced_resource_version").getAsString());
    }

    private static boolean number(JsonObject facts, String key, int expected) {
        if (!facts.has(key) || !facts.get(key).isJsonPrimitive() || !facts.getAsJsonPrimitive(key).isNumber()) return false;
        try { return facts.get(key).getAsBigDecimal().compareTo(BigDecimal.valueOf(expected)) == 0; }
        catch (NumberFormatException invalid) { return false; }
    }

    private static boolean text(JsonObject facts, String key, String expected) {
        return nonblank(facts, key) && expected.equals(facts.get(key).getAsString());
    }
    private static boolean nonblank(JsonObject facts, String key) {
        return facts.has(key) && facts.get(key).isJsonPrimitive() && facts.getAsJsonPrimitive(key).isString()
                && !facts.get(key).getAsString().isBlank();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
