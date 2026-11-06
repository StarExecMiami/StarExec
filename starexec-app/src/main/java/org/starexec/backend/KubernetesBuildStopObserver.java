package org.starexec.backend;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.to.SolverBuildStopReceipt;

/** Conserves an admitted build's observed controller and container termination. */
final class KubernetesBuildStopObserver {
    static final String ARTIFACT_LABEL = "starexec.org/build-artifact";

    record Identity(long artifactId, int pairId, int attemptNo, ExecutionRef execution) {
        String engine(String namespace) {
            return "kubernetes:" + namespace + ":" + execution.execId() + ":" + execution.jobUid();
        }
    }

    private KubernetesBuildStopObserver() { }

    static Identity identify(String namespace, Job job) {
        require(job != null && job.getMetadata() != null, "Job identity is unavailable");
        var metadata = job.getMetadata(); var labels = metadata.getLabels();
        if (labels == null || !labels.containsKey(ARTIFACT_LABEL)) return null;
        require(namespace != null && namespace.equals(metadata.getNamespace()), "Build namespace differs");
        require("true".equals(labels.get("starexec.org/managed")), "Build is not managed by this backend");
        long artifact = Long.parseLong(labels.get(ARTIFACT_LABEL));
        int pair = Integer.parseInt(labels.get("starexec.org/pair-id"));
        int attempt = Integer.parseInt(labels.get("starexec.org/attempt-no"));
        int execution = Integer.parseInt(labels.get("starexec.org/exec-id"));
        require(artifact > 0 && pair > 0 && attempt > 0 && execution > 0, "Build ownership must be positive");
        ExecutionRef ref = ExecutionRef.fromJob(execution, job);
        require(ref != null, "Build Job has no immutable identity");
        return new Identity(artifact, pair, attempt, ref);
    }

    static SolverBuildStopReceipt record(String namespace, Identity expected, Job job, PodList pods) throws SQLException {
        require(expected != null && expected.equals(identify(namespace, job)), "Build Job differs from its admitted identity");
        String terminal = terminalCondition(job);
        require(terminal != null, "Build controller can still create Pods");
        require(pods != null && pods.getItems() != null && !pods.getItems().isEmpty(),
                "Positive build Pod termination observations are unavailable");
        require(job.getMetadata().getResourceVersion() != null && !job.getMetadata().getResourceVersion().isBlank(),
                "Build Job resource version is unavailable");
        var ordered = new ArrayList<>(pods.getItems());
        require(ordered.stream().allMatch(p -> p != null && p.getMetadata() != null
                && p.getMetadata().getUid() != null), "Build Pod identity is unavailable");
        ordered.sort(Comparator.comparing(p -> p.getMetadata().getUid()));
        JsonArray observations = new JsonArray(); var uids = new HashSet<String>();
        for (Pod pod : ordered) {
            require(uids.add(pod.getMetadata().getUid()), "Build Pod listing repeats a UID");
            observations.add(observePod(namespace, expected, pod));
        }
        var artifact = registered(namespace, expected);
        String engine = expected.engine(namespace);
        var prior = SolverBuildArtifacts.findStopReceipt(expected.pairId(), expected.attemptNo());
        String cancelRequest = KubernetesBuildCancellation.isRequested(job)
                ? KubernetesBuildCancellation.acknowledgedVersion(namespace, expected, job) : null;
        SolverBuildStopReceipt receipt;
        if (prior.isPresent()) {
            receipt = prior.get().receipt();
            require(matches(namespace, expected, receipt), "Conserved build stop receipt names another Job");
            requireCancellationMatches(namespace, expected, job, receipt);
        } else {
            JsonObject facts = new JsonObject();
            facts.addProperty("namespace", namespace); facts.addProperty("job_name", expected.execution().jobName());
            facts.addProperty("job_uid", expected.execution().jobUid());
            facts.addProperty("job_resource_version", job.getMetadata().getResourceVersion());
            facts.addProperty("terminal_condition", terminal); facts.add("pods", observations);
            if (cancelRequest != null) {
                KubernetesBuildCancellation.requireOwnedPods(namespace, expected, pods);
                require(pods.getItems().stream().allMatch(p -> p.getMetadata().getFinalizers() != null
                        && p.getMetadata().getFinalizers().contains(KubernetesBuildPodRetention.FINALIZER)),
                        "Build cancellation evidence was not retained before its first receipt");
                facts.addProperty("disposition", "cancelled");
                facts.addProperty("cancel_request_resource_version", cancelRequest);
                facts.addProperty("cancel_deadline_seconds", KubernetesBuildCancellation.DEADLINE_SECONDS);
                facts.addProperty("pod_list_resource_version", pods.getMetadata().getResourceVersion());
            }
            receipt = SolverBuildStopReceipt.observed("kubernetes", expected.artifactId(), expected.pairId(),
                    expected.attemptNo(), engine, expected.execution().jobUid(), Instant.now(), facts);
        }
        persist(namespace, expected, artifact, receipt);
        return receipt;
    }

    /** Absence may reuse an already committed observation, but cannot create a new one. */
    static boolean hasConservedReceipt(String namespace, Identity identity) throws SQLException {
        return conservedReceipt(namespace, identity) != null;
    }

    static SolverBuildStopReceipt conservedReceipt(String namespace, Identity identity) throws SQLException {
        registered(namespace, identity);
        return findConserved(namespace, identity);
    }

    /** Reuses existing physical proof and acknowledges atomic cancellation rejection. */
    static SolverBuildStopReceipt conserveExisting(String namespace, Identity identity) throws SQLException {
        var artifact = registered(namespace, identity);
        SolverBuildStopReceipt receipt = findConserved(namespace, identity);
        if (receipt != null && isCancelled(receipt)) persist(namespace, identity, artifact, receipt);
        return receipt;
    }

    static boolean isCancelled(SolverBuildStopReceipt receipt) {
        return receipt != null && textEquals(receipt.facts(), "disposition", "cancelled");
    }

    static void requireCancellationMatches(String namespace, Identity identity, Job job, SolverBuildStopReceipt receipt) {
        if (!KubernetesBuildCancellation.isRequested(job)) return;
        String request = KubernetesBuildCancellation.acknowledgedVersion(namespace, identity, job);
        require(isCancelled(receipt) && (!receipt.facts().has("cancel_request_resource_version")
                || textEquals(receipt.facts(), "cancel_request_resource_version", request)),
                "Build cancellation differs from its conserved stop outcome");
    }

    static Identity restoreCancelledIdentity(String namespace, SolverBuildArtifacts.Artifact artifact,
                                              SolverBuildArtifacts.StoredStopReceipt stored) {
        require(artifact.stoppedAt() != null && stored.recordedAt() != null && isCancelled(stored.receipt()),
                "Registered cancellation proof is unavailable");
        String prefix = "kubernetes:" + namespace + ":";
        require(artifact.engineIdentity() != null && artifact.engineIdentity().startsWith(prefix),
                "Build cancellation belongs to another backend or namespace");
        String[] engine = artifact.engineIdentity().substring(prefix.length()).split(":", 2);
        require(engine.length == 2 && !engine[1].isBlank(), "Build cancellation engine identity is invalid");
        int execution = Integer.parseInt(engine[0]);
        require(execution > 0, "Build cancellation execution must be positive");
        var facts = stored.receipt().facts();
        require(nonblank(facts, "job_name"), "Build cancellation Job name is unavailable");
        var identity = new Identity(artifact.id(), artifact.pairId(), artifact.attemptNo(),
                new ExecutionRef(execution, facts.get("job_name").getAsString(), engine[1]));
        require(matches(namespace, identity, stored.receipt()), "Build cancellation receipt differs from its registered identity");
        return identity;
    }

    private static SolverBuildStopReceipt findConserved(String namespace, Identity identity) throws SQLException {
        var stored = SolverBuildArtifacts.findStopReceipt(identity.pairId(), identity.attemptNo());
        return stored.isPresent() && matches(namespace, identity, stored.get().receipt()) ? stored.get().receipt() : null;
    }

    private static void persist(String namespace, Identity identity, SolverBuildArtifacts.Artifact artifact,
                                 SolverBuildStopReceipt receipt) throws SQLException {
        boolean committed = isCancelled(receipt)
                ? SolverBuildArtifacts.recordCancelled(identity.pairId(), identity.attemptNo(), artifact.executionToken(), identity.engine(namespace), receipt)
                : SolverBuildArtifacts.recordStopReceipt(identity.pairId(), identity.attemptNo(), artifact.executionToken(), identity.engine(namespace), receipt);
        require(committed, "Could not commit the build stop receipt and outcome");
    }

    private static SolverBuildArtifacts.Artifact registered(String namespace, Identity identity) throws SQLException {
        var artifact = SolverBuildArtifacts.find(identity.pairId(), identity.attemptNo()).orElseThrow(
                () -> new IllegalStateException("Build artifact registration is unavailable"));
        require(artifact.id() == identity.artifactId() && artifact.executionToken() != null
                && identity.engine(namespace).equals(artifact.engineIdentity()), "Build does not match the admitted execution");
        return artifact;
    }

    private static boolean matches(String namespace, Identity identity, SolverBuildStopReceipt receipt) {
        if (!receipt.matches(identity.artifactId(), identity.pairId(), identity.attemptNo(), identity.engine(namespace))
                || !"kubernetes".equals(receipt.backend()) || !identity.execution().jobUid().equals(receipt.resourceId())) return false;
        JsonObject facts = receipt.facts();
        return (!facts.has("disposition") || isCancelled(receipt))
                && textEquals(facts, "namespace", namespace) && textEquals(facts, "job_uid", identity.execution().jobUid())
                && textEquals(facts, "job_name", identity.execution().jobName())
                && (textEquals(facts, "terminal_condition", "Complete") || textEquals(facts, "terminal_condition", "Failed"))
                && nonblank(facts, "job_resource_version") && validStoredPods(facts) && validCancellationProvenance(facts);
    }

    private static boolean validCancellationProvenance(JsonObject facts) {
        if (!facts.has("cancel_request_resource_version") && !facts.has("cancel_deadline_seconds")
                && !facts.has("pod_list_resource_version")) return true;
        if (!textEquals(facts, "disposition", "cancelled") || !nonblank(facts, "cancel_request_resource_version")
                || !nonblank(facts, "pod_list_resource_version") || !facts.has("cancel_deadline_seconds")
                || !facts.get("cancel_deadline_seconds").isJsonPrimitive()
                || !facts.getAsJsonPrimitive("cancel_deadline_seconds").isNumber()
                || facts.get("cancel_request_resource_version").equals(facts.get("job_resource_version"))) return false;
        try { return facts.get("cancel_deadline_seconds").getAsBigDecimal().longValueExact() == KubernetesBuildCancellation.DEADLINE_SECONDS; }
        catch (RuntimeException invalid) { return false; }
    }

    private static boolean validStoredPods(JsonObject facts) {
        if (!facts.has("pods") || !facts.get("pods").isJsonArray() || facts.getAsJsonArray("pods").isEmpty()) return false;
        var uids = new HashSet<String>();
        for (var value : facts.getAsJsonArray("pods")) {
            if (!value.isJsonObject()) return false;
            var pod = value.getAsJsonObject();
            if (!nonblank(pod, "uid") || !nonblank(pod, "name") || !nonblank(pod, "node")
                    || !uids.add(pod.get("uid").getAsString())
                    || !(textEquals(pod, "phase", "Succeeded") || textEquals(pod, "phase", "Failed"))) return false;
            for (String group : List.of("containers", "init_containers", "ephemeral_containers")) {
                if (!pod.has(group) || !pod.get(group).isJsonArray()) return false;
                if (group.equals("containers") && pod.getAsJsonArray(group).isEmpty()) return false;
                var names = new HashSet<String>();
                for (var item : pod.getAsJsonArray(group)) {
                    if (!item.isJsonObject()) return false;
                    var container = item.getAsJsonObject();
                    if (!nonblank(container, "name") || !names.add(container.get("name").getAsString())
                            || !nonblank(container, "container_id") || !nonblank(container, "image_id")
                            || !nonblank(container, "finished_at") || !container.has("exit_code")
                            || !container.get("exit_code").isJsonPrimitive()
                            || !container.getAsJsonPrimitive("exit_code").isNumber()) return false;
                    try {
                        container.get("exit_code").getAsBigDecimal().intValueExact();
                        if (!Instant.parse(container.get("finished_at").getAsString()).isAfter(Instant.EPOCH)) return false;
                    } catch (RuntimeException invalid) { return false; }
                }
            }
        }
        return true;
    }

    private static boolean nonblank(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.getAsJsonPrimitive(key).isString()
                && !object.get(key).getAsString().isBlank();
    }

    private static boolean textEquals(JsonObject facts, String key, String expected) {
        return facts.has(key) && facts.get(key).isJsonPrimitive() && facts.getAsJsonPrimitive(key).isString()
                && expected.equals(facts.get(key).getAsString());
    }

    static String terminalCondition(Job job) {
        if (job.getStatus() == null || job.getStatus().getConditions() == null) return null;
        for (var condition : job.getStatus().getConditions()) {
            if (condition != null && "True".equals(condition.getStatus())
                    && ("Complete".equals(condition.getType()) || "Failed".equals(condition.getType()))) return condition.getType();
        }
        return null;
    }

    static JsonObject observePod(String namespace, Identity identity, Pod pod) {
        var metadata = pod.getMetadata(); var spec = pod.getSpec(); var status = pod.getStatus();
        require(namespace.equals(metadata.getNamespace()) && metadata.getUid() != null && !metadata.getUid().isBlank()
                && metadata.getName() != null && !metadata.getName().isBlank(), "Build Pod namespace or identity differs");
        var owners = metadata.getOwnerReferences();
        require(owners != null && owners.stream().filter(o -> o != null && Boolean.TRUE.equals(o.getController())).count() == 1
                && owners.stream().anyMatch(o -> o != null && Boolean.TRUE.equals(o.getController()) && "Job".equals(o.getKind())
                    && "batch/v1".equals(o.getApiVersion()) && identity.execution().jobUid().equals(o.getUid())
                    && identity.execution().jobName().equals(o.getName())), "Build Pod has a different controller");
        require(spec != null && !Boolean.TRUE.equals(spec.getHostPID()) && "Never".equals(spec.getRestartPolicy()),
                "Build Pod process isolation or restart policy is unverified");
        require(spec.getNodeName() != null && !spec.getNodeName().isBlank(), "Build Pod node observation is missing");
        require(status != null && ("Succeeded".equals(status.getPhase()) || "Failed".equals(status.getPhase())),
                "Build Pod phase is not terminal");
        require(spec.getContainers() != null && !spec.getContainers().isEmpty(), "Build Pod has no declared containers");
        var containers = new HashMap<String, SecurityContext>();
        spec.getContainers().forEach(c -> addContainer(containers, c.getName(), c.getSecurityContext()));
        JsonObject facts = new JsonObject(); facts.addProperty("name", metadata.getName());
        facts.addProperty("uid", metadata.getUid()); facts.addProperty("node", spec.getNodeName());
        facts.addProperty("phase", status.getPhase());
        facts.add("containers", observeContainers(containers, status.getContainerStatuses()));
        containers.clear();
        if (spec.getInitContainers() != null) spec.getInitContainers().forEach(c -> addContainer(containers, c.getName(), c.getSecurityContext()));
        facts.add("init_containers", observeContainers(containers, status.getInitContainerStatuses()));
        containers.clear();
        if (spec.getEphemeralContainers() != null) spec.getEphemeralContainers().forEach(c -> addContainer(containers, c.getName(), c.getSecurityContext()));
        facts.add("ephemeral_containers", observeContainers(containers, status.getEphemeralContainerStatuses()));
        return facts;
    }

    private static void addContainer(Map<String, SecurityContext> specs, String name, SecurityContext security) {
        require(name != null && !name.isBlank() && !specs.containsKey(name), "Build container identity is invalid");
        require(security != null && !Boolean.TRUE.equals(security.getPrivileged())
                && Boolean.FALSE.equals(security.getAllowPrivilegeEscalation()) && security.getCapabilities() != null
                && security.getCapabilities().getDrop() != null && security.getCapabilities().getDrop().contains("ALL")
                && (security.getCapabilities().getAdd() == null || security.getCapabilities().getAdd().isEmpty()),
                "Build container process isolation is unverified");
        specs.put(name, security);
    }

    private static JsonArray observeContainers(Map<String, SecurityContext> specs, List<ContainerStatus> statuses) {
        List<ContainerStatus> observed = statuses == null ? List.of() : statuses;
        require(specs.size() == observed.size(), "Build container termination observations are incomplete");
        var names = new HashSet<String>(); var ordered = new ArrayList<>(observed);
        require(ordered.stream().allMatch(s -> s != null && s.getName() != null), "Build container status identity is missing");
        ordered.sort(Comparator.comparing(ContainerStatus::getName));
        JsonArray facts = new JsonArray();
        for (ContainerStatus status : ordered) {
            require(specs.containsKey(status.getName()) && names.add(status.getName()), "Build container status names differ");
            var state = status.getState();
            require(state != null && state.getTerminated() != null && state.getRunning() == null && state.getWaiting() == null,
                    "Build container has no positive termination observation");
            var stopped = state.getTerminated();
            require(status.getContainerID() != null && !status.getContainerID().isBlank()
                    && status.getContainerID().equals(stopped.getContainerID()) && stopped.getExitCode() != null,
                    "Build container runtime identity is missing or different");
            require(status.getImageID() != null && !status.getImageID().isBlank(), "Build container image identity is unavailable");
            require(stopped.getFinishedAt() != null && Instant.parse(stopped.getFinishedAt()).isAfter(Instant.EPOCH),
                    "Build container finish time is unavailable");
            JsonObject item = new JsonObject(); item.addProperty("name", status.getName());
            item.addProperty("container_id", status.getContainerID()); item.addProperty("image_id", status.getImageID());
            item.addProperty("exit_code", stopped.getExitCode()); item.addProperty("finished_at", stopped.getFinishedAt());
            facts.add(item);
        }
        return facts;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
