package org.starexec.backend;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.google.gson.JsonObject;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.to.SolverBuildStopReceipt;

/** Records a stopped build while the engine's immutable resource identity still exists. */
final class PodmanBuildStopObserver {
    static final String ARTIFACT_LABEL = "starexec.build.artifact";

    private record Identity(long artifact, int pair, int attempt, int execution) {
        String engine(String id) { return "podman:" + execution + ":" + id; }
    }

    private PodmanBuildStopObserver() { }

    static boolean isBuild(InspectContainerResponse inspection) {
        require(inspection != null && inspection.getConfig() != null, "Container ownership inspection is unavailable");
        return inspection.getConfig().getLabels() != null
                && inspection.getConfig().getLabels().containsKey(ARTIFACT_LABEL);
    }

    static Integer buildExecution(InspectContainerResponse inspection, String containerId) {
        return isBuild(inspection) ? identity(inspection, containerId).execution() : null;
    }

    static void recordIfBuild(InspectContainerResponse inspection, String containerId) throws SQLException {
        recordIfBuild(inspection,containerId,false);
    }

    static void recordCancelledIfBuild(InspectContainerResponse inspection, String containerId) throws SQLException {
        recordIfBuild(inspection,containerId,true);
    }

    private static void recordIfBuild(InspectContainerResponse inspection, String containerId, boolean cancellationRequested) throws SQLException {
        if (!isBuild(inspection)) return;
        Identity identity = identity(inspection, containerId);
        var state = inspection.getState();
        require(state != null && ("exited".equals(state.getStatus()) || "dead".equals(state.getStatus())),
                "Build container has not reached a terminal engine state");
        requireStoppedFlags(inspection);
        require(Instant.parse(state.getFinishedAt()).isAfter(Instant.EPOCH), "Build finish time is missing");
        Instant.parse(state.getStartedAt());
        require(state.getExitCodeLong() != null, "Build exit code is missing");
        require(inspection.getImageId() != null && !inspection.getImageId().isBlank(), "Build image identity is missing");
        var artifact = SolverBuildArtifacts.find(identity.pair(), identity.attempt()).orElseThrow(
                () -> new IllegalStateException("Build artifact registration is unavailable"));
        String engine = identity.engine(containerId);
        require(artifact.id() == identity.artifact() && artifact.executionToken() != null
                && engine.equals(artifact.engineIdentity()), "Build container differs from its admitted execution");

        JsonObject facts = new JsonObject();
        facts.addProperty("status", state.getStatus()); facts.addProperty("pid", state.getPidLong());
        facts.addProperty("running", state.getRunning()); facts.addProperty("paused", state.getPaused());
        facts.addProperty("restarting", state.getRestarting());
        facts.addProperty("restart_policy", inspection.getHostConfig().getRestartPolicy().getName());
        facts.addProperty("private_pid_namespace", true); facts.addProperty("privileged", false);
        facts.addProperty("image_id", inspection.getImageId());
        facts.addProperty("started_at", state.getStartedAt()); facts.addProperty("finished_at", state.getFinishedAt());
        facts.addProperty("exit_code", state.getExitCodeLong());
        var existing = SolverBuildArtifacts.findStopReceipt(identity.pair(), identity.attempt());
        boolean cancelled=cancellationRequested || existing.isPresent()
                && existing.get().receipt().facts().has("disposition")
                && "cancelled".equals(existing.get().receipt().facts().get("disposition").getAsString());
        if (cancelled) facts.addProperty("disposition","cancelled");
        SolverBuildStopReceipt receipt;
        if (existing.isPresent()) {
            receipt = existing.get().receipt();
            require(receipt.matches(identity.artifact(), identity.pair(), identity.attempt(), engine)
                    && "podman".equals(receipt.backend()) && containerId.equals(receipt.resourceId())
                    && facts.equals(receipt.facts()), "Stored build stop receipt differs from this engine observation");
        } else {
            receipt = SolverBuildStopReceipt.observed("podman", identity.artifact(), identity.pair(),
                    identity.attempt(), engine, containerId, Instant.now(), facts);
        }
        boolean conserved=cancelled
                ? SolverBuildArtifacts.recordCancelled(identity.pair(),identity.attempt(),artifact.executionToken(),engine,receipt)
                : SolverBuildArtifacts.recordStopReceipt(identity.pair(),identity.attempt(),artifact.executionToken(),engine,receipt);
        require(conserved, "Could not conserve the build stop receipt");
    }

    /** A refused launch can discard a positively observed never-started resource without claiming completion. */
    static void requireNeverStarted(InspectContainerResponse inspection, String containerId,
                                    BuildExecutionContext build, int execution) {
        Identity identity = identity(inspection, containerId);
        require(identity.artifact() == build.artifactId() && identity.pair() == build.pairId()
                && identity.attempt() == build.attemptNo() && identity.execution() == execution,
                "Refused container differs from its build submission");
        require(inspection.getState() != null && "created".equals(inspection.getState().getStatus()),
                "Refused build may already have started");
        requireStoppedFlags(inspection);
        require(Instant.parse("0001-01-01T00:00:00Z").equals(Instant.parse(inspection.getState().getStartedAt())),
                "Refused build has a previous start");
    }

    /** Positive created-state observation; persistence must bind and reject atomically, never admit launch. */
    static SolverBuildStopReceipt cancelledCreationReceipt(InspectContainerResponse inspection,String containerId,
                                                            BuildExecutionContext build,int execution) throws SQLException {
        requireNeverStarted(inspection,containerId,build,execution);
        require(inspection.getImageId()!=null && inspection.getImageId().matches("sha256:[0-9a-f]{64}"),
                "Created build image identity is unavailable");
        var state=inspection.getState();var facts=new JsonObject();
        facts.addProperty("disposition","cancelled");facts.addProperty("reason","admission_refused");
        facts.addProperty("status",state.getStatus());facts.addProperty("pid",state.getPidLong());
        facts.addProperty("running",state.getRunning());facts.addProperty("paused",state.getPaused());facts.addProperty("restarting",state.getRestarting());
        facts.addProperty("started_at",state.getStartedAt());facts.addProperty("restart_policy",inspection.getHostConfig().getRestartPolicy().getName());
        facts.addProperty("private_pid_namespace",true);facts.addProperty("privileged",false);facts.addProperty("image_id",inspection.getImageId());
        String engine="podman:"+execution+":"+containerId;
        var stored=SolverBuildArtifacts.findStopReceipt(build.pairId(),build.attemptNo());
        if (stored.isPresent()) {
            var receipt=stored.get().receipt();
            require(receipt.matches(build.artifactId(),build.pairId(),build.attemptNo(),engine) && "podman".equals(receipt.backend())
                    && containerId.equals(receipt.resourceId()) && facts.equals(receipt.facts()),"Created cancellation conflicts with the conserved receipt");
            return receipt;
        }
        return SolverBuildStopReceipt.observed("podman",build.artifactId(),build.pairId(),build.attemptNo(),engine,containerId,Instant.now(),facts);
    }

    private static Identity identity(InspectContainerResponse inspection, String containerId) {
        require(isBuild(inspection) && containerId != null && containerId.equals(inspection.getId()),
                "Build inspection resource identity differs");
        Map<String, String> labels = inspection.getConfig().getLabels();
        require("true".equals(labels.get("starexec.managed")) && "2".equals(labels.get("starexec.label.version"))
                && "job-pair".equals(labels.get("starexec.kind")), "Build ownership labels are invalid");
        long artifact = Long.parseLong(labels.get(ARTIFACT_LABEL));
        int pair = Integer.parseInt(labels.get("starexec.pair.id"));
        int attempt = Integer.parseInt(labels.get("starexec.attempt"));
        int execution = Integer.parseInt(labels.get("starexec.exec.id"));
        require(artifact > 0 && pair > 0 && attempt > 0 && execution > 0, "Build ownership must be positive");
        return new Identity(artifact, pair, attempt, execution);
    }

    private static void requireStoppedFlags(InspectContainerResponse inspection) {
        var state = inspection.getState(); var host = inspection.getHostConfig();
        require(Boolean.FALSE.equals(state.getRunning()) && Boolean.FALSE.equals(state.getPaused())
                && Boolean.FALSE.equals(state.getRestarting()) && Long.valueOf(0).equals(state.getPidLong()),
                "Build process termination is unverified");
        // docker-java's noRestart() uses an empty name; the engine can return the explicit "no" name.
        require(host != null && host.getRestartPolicy() != null && ("no".equals(host.getRestartPolicy().getName())
                || "".equals(host.getRestartPolicy().getName())),
                "Build restart policy is unverified");
        require(Boolean.FALSE.equals(host.getPrivileged()) && (host.getPidMode() == null
                || host.getPidMode().isEmpty() || "private".equals(host.getPidMode()))
                && (host.getCapAdd() == null || host.getCapAdd().length == 0),
                "Build process namespace isolation is unverified");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
