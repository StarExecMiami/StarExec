package org.starexec.backend;

import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateWaiting;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.starexec.logger.StarLogger;

/**
 * An immutable snapshot of what Kubernetes knows about the pods behind StarExec's Jobs.
 *
 * <p>This exists because {@code JobStatus.active} cannot answer the only question that
 * matters. The Kubernetes API defines it as
 * <em>"the number of pending and running pods which are not terminating"</em>
 * ({@code staging/src/k8s.io/api/batch/v1/types.go}), so a pod that the scheduler has
 * never placed satisfies {@code active > 0} exactly like a pod executing a solver. Both
 * {@code KubernetesJobMonitor} and {@code KubernetesNativeBackend} used to test that
 * field and therefore reported a pair as RUNNING before anything had run.
 *
 * <p>The consequences were not cosmetic. A pair mislabelled RUNNING is invisible to
 * {@code GetPairsEnqueuedLongerThan}, which filters {@code status_code = 2} (ENQUEUED),
 * and to {@code Jobs.setBrokenPairsToErrorStatus}, because {@code getActiveExecutionIds}
 * excludes only succeeded and failed Jobs. Nothing in Kubernetes ends the wait either:
 * {@code backoffLimit} is 0, {@code restartPolicy} is Never, no {@code activeDeadlineSeconds}
 * is set, and {@code ttlSecondsAfterFinished} only reaps Jobs that have finished. The pair
 * waits forever while holding one of {@code maxConcurrentJobs} submission slots.
 *
 * <p>One shared classifier rather than a copy in each caller, for the same reason
 * {@link RunsolverVerdict} exists: two implementations of the same judgement drift, and
 * the drift is silent.
 *
 * <p>Instances are built from a single labelled pod listing per poll. The pod template
 * carries the same labels as the Job ({@code buildKubernetesJob} adds them to the template
 * metadata), so one call indexes every pod by execution id.
 */
public final class PodPhaseView {

    /** What a pod is actually doing, as opposed to what {@code JobStatus.active} implies. */
    public enum Phase {
        /** A pod is executing. Only this justifies moving a pair to STATUS_RUNNING. */
        RUNNING,
        /**
         * A pod exists but has not started: unschedulable, or unable to pull its image.
         * A pod stays in this phase for both, because phase does not become Running until
         * a container does.
         */
        PENDING,
        /**
         * No pod for this execution id, or one in a phase this view does not judge
         * (Succeeded, Failed, Unknown). Job-level completion detection handles those.
         */
        UNKNOWN,
    }

    /** Returned when a creation timestamp is absent or unparseable. */
    public static final long UNKNOWN_TIME = -1L;

    private static final StarLogger log = StarLogger.getLogger(PodPhaseView.class);

    /**
     * Set the first time a pod listing fails, so a cluster that never grants the
     * permission produces one line rather than one every five seconds.
     */
    private static final AtomicBoolean LISTING_WARNING_EMITTED = new AtomicBoolean(false);

    /** Emitted once, for a managed pod that names no controller. */
    private static final AtomicBoolean OWNERLESS_POD_WARNING_EMITTED = new AtomicBoolean(false);

    private static final PodPhaseView UNAVAILABLE = new PodPhaseView(
        Collections.emptyMap(),
        Collections.emptyList(),
        false
    );

    /**
     * A managed pod whose execution identity could not be recovered.
     *
     * <p>Kept rather than discarded. The pod was returned by a listing that selected on the
     * managed label alone, so it exists and may be executing; only its {@code exec-id} label
     * is missing, empty, non-numeric, or out of {@code int} range. Dropping it made a
     * running solver invisible to both slot accounting and admission control.
     *
     * <p>Carries the raw label value and the node name because an operator has to find the
     * object to resolve it, and no execution id can be invented for it.
     */
    public static final class UnidentifiedPod {
        private final String name;
        private final String nodeName;
        private final String phase;
        private final String rawExecIdLabel;
        private final PodSafety safety;

        private UnidentifiedPod(
            String name,
            String nodeName,
            String phase,
            String rawExecIdLabel,
            PodSafety safety
        ) {
            this.name = name;
            this.nodeName = nodeName;
            this.phase = phase;
            this.rawExecIdLabel = rawExecIdLabel;
            this.safety = safety;
        }

        public String name() {
            return name;
        }

        public String nodeName() {
            return nodeName;
        }

        public String phase() {
            return phase;
        }

        public String rawExecIdLabel() {
            return rawExecIdLabel;
        }

        public PodSafety safety() {
            return safety;
        }

        /** Whether this pod may still execute or write, or cannot be shown not to. */
        public boolean mayStillRun() {
            return safety != PodSafety.SAFE_TERMINATED;
        }

        @Override
        public String toString() {
            return "pod " + orUnknown(name) +
                " on node " + orUnknown(nodeName) +
                " phase " + orUnknown(phase) +
                " exec-id label " +
                (rawExecIdLabel == null ? "<absent>" : "'" + rawExecIdLabel + "'") +
                " safety " + safety;
        }
    }

    private final Map<ExecutionKey, Observation> byExecution;
    private final List<UnidentifiedPod> unidentified;
    private final boolean available;

    private PodPhaseView(
        Map<ExecutionKey, Observation> byExecution,
        List<UnidentifiedPod> unidentified,
        boolean available
    ) {
        this.byExecution = byExecution;
        this.unidentified = unidentified;
        this.available = available;
    }

    /**
     * Every managed pod in this view whose execution identity could not be parsed.
     *
     * <p>Empty when the listing failed — check {@link #isAvailable()} first, because an
     * unreadable listing disproves nothing and must not clear a degraded condition.
     */
    public List<UnidentifiedPod> unidentifiedPods() {
        return Collections.unmodifiableList(unidentified);
    }

    /**
     * The unidentified managed pods that may still be executing or writing.
     *
     * <p>A terminal phase is enough to exclude a pod here, matching {@link #safetyOf}: a
     * Succeeded or Failed pod has no running container. It is not enough to conclude the
     * <em>execution</em> is over — a controller may create the next pod — which is why the
     * caller still treats an unidentified object as a reason to hold admission rather than
     * as something it may delete or account for.
     */
    public List<UnidentifiedPod> unsafeUnidentifiedPods() {
        List<UnidentifiedPod> unsafe = new ArrayList<>();
        for (UnidentifiedPod pod : unidentified) {
            if (pod.mayStillRun()) {
                unsafe.add(pod);
            }
        }
        return unsafe;
    }

    /**
     * The view to use when pods could not be listed at all.
     *
     * <p>Distinct from an empty view: an empty listing means "no pods exist", which is a
     * fact, while this means "we do not know", which must never be acted on. The chart's
     * Role granted only {@code batch/jobs} before this feature existed, so a deployment
     * whose RBAC has not been updated lands here and keeps its previous behaviour.
     */
    public static PodPhaseView unavailable() {
        return UNAVAILABLE;
    }

    /**
     * Lists the managed pods and builds a view, degrading to {@link #unavailable()} on any
     * failure rather than propagating it.
     *
     * <p>Listing pods is a permission StarExec did not previously need: the chart's Role
     * granted {@code batch/jobs} alone. An installation whose RBAC has not been updated
     * therefore gets a 403 here, and must go on behaving exactly as it did before rather
     * than losing its monitor loop to an exception.
     */
    public static PodPhaseView list(
        KubernetesClient client,
        String namespace,
        String managedLabel,
        String execIdLabel
    ) {
        if (client == null) {
            return unavailable();
        }
        try {
            List<Pod> pods = client
                .pods()
                .inNamespace(namespace)
                .withLabel(managedLabel, "true")
                .list()
                .getItems();
            return of(pods, execIdLabel);
        } catch (Exception e) {
            if (LISTING_WARNING_EMITTED.compareAndSet(false, true)) {
                log.warn(
                    "Cannot list pods in namespace " +
                    namespace +
                    ", so a pod that never starts cannot be distinguished from one that is" +
                    " running: JobStatus.active counts pending and running pods alike." +
                    " Job-level behaviour is unchanged. Grant the service account" +
                    " get/list on pods to enable stale-pending detection.",
                    e
                );
            }
            return unavailable();
        }
    }

    // =====================================================================
    // Census: is one execution's pod capable of running or writing results?
    //
    // Separate from the snapshot above, and deliberately so. The snapshot answers
    // "what is this pod doing" for the monitor's routine classification; the census
    // answers "may I now release, publish, or replace this execution", which is a
    // safety question and has to fail closed.
    //
    // Three reasons it is not an accessor on a snapshot:
    //
    //  1. A snapshot is taken before a deletion; the invariant needs a reading taken
    //     after it.
    //  2. {@link #of} silently drops any pod whose exec-id label will not parse
    //     (see extractExecId), so a client-side bucket can report "no pod" for a pod
    //     that exists. The API server matches a selector itself, so a server-side
    //     query has no such hole.
    //  3. phaseFor collapses "no pod", Succeeded, Failed and Kubernetes' literal
    //     Unknown into one value, and those have opposite safety meanings.
    // =====================================================================

    /** Whether an execution's pods can still execute or write results. */
    public enum PodSafety {
        /** The listing succeeded and matched nothing. */
        SAFE_ABSENT,
        /**
         * Every matching pod is Succeeded or Failed. Kubernetes defines both as "all
         * containers have terminated and are not restarting", so such a pod cannot
         * consume CPU or write further output. Treating it as unsafe would let one
         * harmless historical object hold an execution's accounting forever.
         */
        SAFE_TERMINATED,
        /** At least one matching pod is Pending or Running. */
        MAY_RUN,
        /**
         * Safety could not be established: the listing failed, returned no body, or a
         * pod reports phase Unknown -- which means the node stopped reporting, so that
         * pod may well be executing right now. Never readable as safe.
         */
        UNDETERMINED,
    }

    /** The result of one targeted census, taken fresh, for one execution. */
    public static final class Census {

        private final PodSafety safety;
        private final int podCount;
        private final String detail;

        private Census(PodSafety safety, int podCount, String detail) {
            this.safety = safety;
            this.podCount = podCount;
            this.detail = detail;
        }

        public PodSafety safety() {
            return safety;
        }

        /** True only for a positively established safe state. */
        public boolean isSafe() {
            return safety == PodSafety.SAFE_ABSENT || safety == PodSafety.SAFE_TERMINATED;
        }

        public int podCount() {
            return podCount;
        }

        /** Pod names, phases and terminating flags, or why the listing failed. For logs. */
        public String describe() {
            return detail;
        }

        @Override
        public String toString() {
            return safety + " (" + podCount + " pod(s)): " + detail;
        }
    }

    /**
     * Counts the pods of one execution, identified by its execution id.
     *
     * <p>This is the authoritative identity. Prefer it whenever the execution id is
     * known.
     */
    public static Census censusByExecId(
        KubernetesClient client,
        String namespace,
        String managedLabel,
        String execIdLabel,
        int execId
    ) {
        return census(client, namespace, managedLabel, execIdLabel, String.valueOf(execId));
    }

    /**
     * Counts the pods of any attempt of one pair, identified by its pair id.
     *
     * <p>A conservative fallback for when the execution id cannot be recovered, and
     * <em>only</em> that. A pair id is stable across reruns, so this matches pods of
     * earlier attempts too: a safe answer here means "no pod of any attempt of this pair
     * can run", which is stronger than needed and therefore sound, while an unsafe answer
     * must never be reported as identifying the current execution.
     */
    public static Census censusByPairId(
        KubernetesClient client,
        String namespace,
        String managedLabel,
        String pairIdLabel,
        int pairId
    ) {
        return census(client, namespace, managedLabel, pairIdLabel, String.valueOf(pairId));
    }

    private static Census census(
        KubernetesClient client,
        String namespace,
        String managedLabel,
        String identityLabel,
        String identityValue
    ) {
        if (client == null) {
            return new Census(PodSafety.UNDETERMINED, 0, "no Kubernetes client");
        }
        Map<String, String> selector = new HashMap<>();
        selector.put(managedLabel, "true");
        selector.put(identityLabel, identityValue);
        try {
            io.fabric8.kubernetes.api.model.PodList listing = client
                .pods()
                .inNamespace(namespace)
                .withLabels(selector)
                .list();
            if (listing == null || listing.getItems() == null) {
                // A missing body is not an empty listing. Conflating the two is exactly
                // the fail-open this method exists to prevent.
                return new Census(
                    PodSafety.UNDETERMINED,
                    0,
                    "the pod listing returned no body for " + identityLabel + "=" + identityValue
                );
            }
            List<Pod> pods = listing.getItems();
            if (pods.isEmpty()) {
                return new Census(
                    PodSafety.SAFE_ABSENT,
                    0,
                    "no pod carries " + identityLabel + "=" + identityValue
                );
            }
            return new Census(worstSafety(pods), pods.size(), summarise(pods));
        } catch (Exception e) {
            // Deliberately NOT throttled through LISTING_WARNING_EMITTED. That latch is
            // set once per JVM by the monitor's poll loop, and reusing it here would hide
            // the evidence behind a decision to hold an execution's accounting.
            log.warn(
                "Could not list pods for " + identityLabel + "=" + identityValue +
                " in namespace " + namespace + "; pod safety cannot be established",
                e
            );
            return new Census(
                PodSafety.UNDETERMINED,
                0,
                e.getClass().getSimpleName() + ": " + e.getMessage()
            );
        }
    }

    /** The least safe verdict across the matched pods. One unsafe pod makes the set unsafe. */
    private static PodSafety worstSafety(List<Pod> pods) {
        PodSafety worst = PodSafety.SAFE_TERMINATED;
        for (Pod pod : pods) {
            PodSafety safety = safetyOf(pod);
            if (safety == PodSafety.UNDETERMINED) {
                return PodSafety.UNDETERMINED;
            }
            if (safety == PodSafety.MAY_RUN) {
                worst = PodSafety.MAY_RUN;
            }
        }
        return worst;
    }

    /**
     * The safety of one pod, decided by phase alone.
     *
     * <p>{@code deletionTimestamp} is reported but never decides: a pod being torn down
     * still reports Running until its containers actually stop, and it can still write
     * during its termination grace period.
     */
    private static PodSafety safetyOf(Pod pod) {
        PodStatus status = pod == null ? null : pod.getStatus();
        String phase = status == null ? null : status.getPhase();
        if (phase == null) {
            return PodSafety.UNDETERMINED;
        }
        switch (phase) {
            case "Succeeded":
            case "Failed":
                return PodSafety.SAFE_TERMINATED;
            case "Pending":
            case "Running":
                return PodSafety.MAY_RUN;
            default:
                // "Unknown", or a phase this version does not recognise. Kubernetes uses
                // Unknown when a pod's state cannot be obtained, typically because its
                // node stopped reporting -- the pod may be running.
                return PodSafety.UNDETERMINED;
        }
    }

    private static String summarise(List<Pod> pods) {
        StringBuilder sb = new StringBuilder();
        for (Pod pod : pods) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            String name = pod.getMetadata() == null ? "<unnamed>" : pod.getMetadata().getName();
            PodStatus status = pod.getStatus();
            boolean terminating =
                pod.getMetadata() != null && pod.getMetadata().getDeletionTimestamp() != null;
            sb
                .append(name)
                .append(" phase=")
                .append(status == null || status.getPhase() == null ? "<none>" : status.getPhase())
                .append(" terminating=")
                .append(terminating)
                .append(" node=")
                .append(pod.getSpec() == null || pod.getSpec().getNodeName() == null
                    ? "<none>"
                    : pod.getSpec().getNodeName());
        }
        return sb.toString();
    }

    /**
     * Builds a view from a labelled pod listing.
     *
     * @param pods         pods carrying {@code execIdLabel}; null is treated as empty
     * @param execIdLabel  the label key holding the StarExec execution id
     */
    public static PodPhaseView of(List<Pod> pods, String execIdLabel) {
        Map<ExecutionKey, Observation> observations = new HashMap<>();
        List<UnidentifiedPod> unidentified = new ArrayList<>();
        if (pods == null) {
            return new PodPhaseView(observations, unidentified, true);
        }

        for (Pod pod : pods) {
            Integer execId = extractExecId(pod, execIdLabel);
            if (execId == null) {
                // Kept, not dropped. This pod matched the managed selector server-side, so
                // it exists and may be executing; only its identity is unusable. Silently
                // discarding it here is what made an unidentifiable solver invisible to
                // both slot accounting and admission control.
                unidentified.add(describeUnidentified(pod, execIdLabel));
                continue;
            }
            // Grouped by the Job that owns the pod, not by the label. Two Jobs can carry
            // the same exec-id label -- the counter behind it restarts at 1 in every
            // application lifetime -- and merging their pods let one execution's Running
            // pod answer for another's that had never been scheduled. Replacement pods
            // under ONE Job still merge, which is the case preferred() is for.
            Observation observed = observe(pod);
            observations.merge(
                new ExecutionKey(execId, controllerUidOf(pod)),
                observed,
                PodPhaseView::preferred
            );
        }
        return new PodPhaseView(observations, unidentified, true);
    }

    /** Captures what an operator needs to find a pod whose execution id cannot be read. */
    private static UnidentifiedPod describeUnidentified(Pod pod, String execIdLabel) {
        String name = null;
        String nodeName = null;
        String raw = null;
        if (pod != null && pod.getMetadata() != null) {
            name = pod.getMetadata().getName();
            if (pod.getMetadata().getLabels() != null && execIdLabel != null) {
                raw = pod.getMetadata().getLabels().get(execIdLabel);
            }
        }
        if (pod != null && pod.getSpec() != null) {
            nodeName = pod.getSpec().getNodeName();
        }
        String phase = (pod == null || pod.getStatus() == null)
            ? null
            : pod.getStatus().getPhase();
        return new UnidentifiedPod(name, nodeName, phase, raw, safetyOf(pod));
    }

    /**
     * Whether this view carries information. False means the pod listing failed and every
     * other method returns a "do not know, do not act" answer.
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Every execution id this view observed a pod for.
     *
     * <p>Empty when the listing failed, which is why callers must check
     * {@link #isAvailable()} first: an unreadable listing and a cluster with no managed pods
     * are indistinguishable here, and only one of them means "nothing is running".
     */
    public java.util.Set<Integer> execIds() {
        Set<Integer> ids = new LinkedHashSet<>();
        for (ExecutionKey key : byExecution.keySet()) {
            ids.add(key.execId);
        }
        return java.util.Collections.unmodifiableSet(ids);
    }

    /** The phase of {@code execution}'s own pod, or {@link Phase#UNKNOWN}. */
    public Phase phaseFor(ExecutionRef execution) {
        Observation observation = observationFor(execution);
        return (observation == null) ? Phase.UNKNOWN : observation.phase;
    }

    /**
     * The phase behind a bare execution id.
     *
     * <p>Answers only while the id names one thing: if two Jobs in this view carry it,
     * the question has no answer and {@link Phase#UNKNOWN} is returned rather than one of
     * the two. Callers that hold a Job should pass {@link ExecutionRef} and get a reading
     * in the colliding case too.
     */
    public Phase phaseFor(int execId) {
        Observation observation = soleObservationFor(execId);
        return (observation == null) ? Phase.UNKNOWN : observation.phase;
    }

    /**
     * When the pod behind {@code execId} was created, in epoch milliseconds, or
     * {@link #UNKNOWN_TIME} if that is not known.
     *
     * <p>Deliberately {@code metadata.creationTimestamp} and not {@code status.startTime}:
     * the Kubernetes reference says of the latter <em>"on first transition into the Running
     * state, the system records the startTime of the Pod"</em>, so it is null for exactly
     * the pods this class exists to find.
     */
    public long createdAtMillis(ExecutionRef execution) {
        Observation observation = observationFor(execution);
        return (observation == null) ? UNKNOWN_TIME : observation.createdAtMillis;
    }

    /** As above for a bare execution id; UNKNOWN_TIME when two Jobs carry it. */
    public long createdAtMillis(int execId) {
        Observation observation = soleObservationFor(execId);
        return (observation == null) ? UNKNOWN_TIME : observation.createdAtMillis;
    }

    /**
     * Kubernetes' own account of why the pod has not started, for an operator to read.
     *
     * <p>Reported, never branched on. Scheduler and kubelet messages are prose that
     * changes between releases; a control-flow decision resting on their wording is the
     * same mistake as parsing a solver's stdout for its exit condition.
     */
    public String describeWhyPending(ExecutionRef execution) {
        Observation observation = observationFor(execution);
        return (observation == null) ? "no pod found for this execution" : observation.reason;
    }

    /** As above for a bare execution id. */
    public String describeWhyPending(int execId) {
        Observation observation = soleObservationFor(execId);
        return (observation == null) ? "no pod found for this execution id" : observation.reason;
    }

    // =========================================================================
    // Internals
    // =========================================================================

    /**
     * Chooses between two observations of the same execution id.
     *
     * <p>A running pod always wins, so a Job is never treated as stuck while one of its
     * pods executes. Otherwise the newer pod wins: its creation time is the age of the
     * current attempt, which is the conservative reading — an older timestamp would make
     * a threshold fire sooner.
     */
    /**
     * The observation belonging to this execution's own Job.
     *
     * <p>The owning Job must match. A pod whose controller cannot be read answers for
     * nothing: it carries the exec-id label, which is the identity this class exists to
     * stop trusting, and a second Job holding that label is exactly the case where letting
     * it answer would report one execution's pod as another's. The Job controller stamps a
     * controller ownerReference on every pod it creates, so a managed pod without one is a
     * cluster anomaly and is warned about once rather than guessed at.
     */
    private Observation observationFor(ExecutionRef execution) {
        if (execution == null) {
            return null;
        }
        return byExecution.get(new ExecutionKey(execution.execId(), execution.jobUid()));
    }

    /** The one observation for {@code execId}, or null when none or more than one. */
    private Observation soleObservationFor(int execId) {
        Observation found = null;
        for (Map.Entry<ExecutionKey, Observation> entry : byExecution.entrySet()) {
            if (entry.getKey().execId != execId) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = entry.getValue();
        }
        return found;
    }

    /**
     * The UID of the Job that created this pod, or null if it does not name one.
     *
     * <p>Null is warned about once per process. Kubernetes' Job controller sets a controller
     * ownerReference on every pod it creates, so an absent one means the pod cannot be
     * attributed to an execution -- and an execution whose pod cannot be found is neither
     * marked running nor escalated as stuck, which is a silent degradation worth a line in
     * the log.
     */
    private static String controllerUidOf(Pod pod) {
        if (pod == null || pod.getMetadata() == null) {
            return null;
        }
        List<OwnerReference> owners = pod.getMetadata().getOwnerReferences();
        if (owners == null) {
            return null;
        }
        for (OwnerReference owner : owners) {
            if (owner == null || !Boolean.TRUE.equals(owner.getController())) {
                continue;
            }
            String uid = owner.getUid();
            if (uid != null && !uid.trim().isEmpty()) {
                return uid;
            }
        }
        if (OWNERLESS_POD_WARNING_EMITTED.compareAndSet(false, true)) {
            log.warn(
                "Managed pod " +
                (pod.getMetadata() == null ? "<unnamed>" : pod.getMetadata().getName()) +
                " carries no controller ownerReference, so it cannot be attributed to a" +
                " Kubernetes Job. Executions whose pods look like this are neither marked" +
                " running nor escalated as stuck. OPERATOR ACTION: check whether an" +
                " admission webhook is stripping ownerReferences."
            );
        }
        return null;
    }

    /** One execution's pods: the legacy id plus the Job that owns them. */
    private static final class ExecutionKey {

        private final int execId;
        private final String ownerUid;

        private ExecutionKey(int execId, String ownerUid) {
            this.execId = execId;
            this.ownerUid = ownerUid;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ExecutionKey)) {
                return false;
            }
            ExecutionKey that = (ExecutionKey) other;
            return execId == that.execId && Objects.equals(ownerUid, that.ownerUid);
        }

        @Override
        public int hashCode() {
            return Objects.hash(execId, ownerUid);
        }
    }

    private static Observation preferred(Observation existing, Observation candidate) {
        if (existing.phase == Phase.RUNNING) {
            return existing;
        }
        if (candidate.phase == Phase.RUNNING) {
            return candidate;
        }
        if (existing.createdAtMillis == UNKNOWN_TIME) {
            return candidate;
        }
        if (candidate.createdAtMillis == UNKNOWN_TIME) {
            return existing;
        }
        return (candidate.createdAtMillis > existing.createdAtMillis) ? candidate : existing;
    }

    private static Integer extractExecId(Pod pod, String execIdLabel) {
        if (pod == null
                || execIdLabel == null
                || pod.getMetadata() == null
                || pod.getMetadata().getLabels() == null) {
            return null;
        }
        String raw = pod.getMetadata().getLabels().get(execIdLabel);
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Observation observe(Pod pod) {
        long createdAt = parseTimestamp(
            (pod.getMetadata() == null) ? null : pod.getMetadata().getCreationTimestamp()
        );

        PodStatus status = pod.getStatus();
        String phase = (status == null) ? null : status.getPhase();

        if ("Running".equalsIgnoreCase(phase)) {
            return new Observation(Phase.RUNNING, createdAt, "pod is running");
        }
        if ("Pending".equalsIgnoreCase(phase)) {
            return new Observation(Phase.PENDING, createdAt, describePending(status));
        }
        return new Observation(
            Phase.UNKNOWN,
            createdAt,
            "pod phase is " + ((phase == null) ? "not reported" : phase)
        );
    }

    /**
     * Parses an RFC 3339 timestamp, failing to {@link #UNKNOWN_TIME} rather than throwing.
     *
     * <p>A malformed timestamp must not be able to abort a poll, and it must not be able
     * to make a pair look older than it is. An unknown creation time therefore reads as
     * "cannot judge", which every caller treats as "do not act".
     */
    private static long parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.trim().isEmpty()) {
            return UNKNOWN_TIME;
        }
        String trimmed = timestamp.trim();
        try {
            return Instant.parse(trimmed).toEpochMilli();
        } catch (RuntimeException e) {
            // Kubernetes emits UTC with a Z suffix, but accept an explicit offset too
            // rather than lose the reading over a format the API is allowed to use.
            try {
                return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli();
            } catch (RuntimeException ignored) {
                return UNKNOWN_TIME;
            }
        }
    }

    private static String describePending(PodStatus status) {
        if (status == null) {
            return "pod is Pending; no status reported";
        }

        List<PodCondition> conditions = status.getConditions();
        if (conditions != null) {
            for (PodCondition condition : conditions) {
                if (condition == null || !"PodScheduled".equalsIgnoreCase(condition.getType())) {
                    continue;
                }
                if (!"False".equalsIgnoreCase(condition.getStatus())) {
                    continue;
                }
                return "not scheduled (" +
                    orUnknown(condition.getReason()) +
                    "): " +
                    orUnknown(condition.getMessage());
            }
        }

        List<ContainerStatus> containers = status.getContainerStatuses();
        if (containers != null) {
            for (ContainerStatus container : containers) {
                if (container == null) {
                    continue;
                }
                ContainerState state = container.getState();
                ContainerStateWaiting waiting = (state == null) ? null : state.getWaiting();
                if (waiting == null) {
                    continue;
                }
                return "container waiting (" +
                    orUnknown(waiting.getReason()) +
                    "): " +
                    orUnknown(waiting.getMessage());
            }
        }

        return "pod is Pending; no scheduling condition or container state reported";
    }

    private static String orUnknown(String value) {
        return (value == null || value.trim().isEmpty()) ? "unknown" : value.trim();
    }

    private static final class Observation {

        private final Phase phase;
        private final long createdAtMillis;
        private final String reason;

        private Observation(Phase phase, long createdAtMillis, String reason) {
            this.phase = phase;
            this.createdAtMillis = createdAtMillis;
            this.reason = reason;
        }
    }
}
