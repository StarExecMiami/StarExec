package org.starexec.backend;

import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateWaiting;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static final PodPhaseView UNAVAILABLE = new PodPhaseView(
        Collections.emptyMap(),
        false
    );

    private final Map<Integer, Observation> byExecId;
    private final boolean available;

    private PodPhaseView(Map<Integer, Observation> byExecId, boolean available) {
        this.byExecId = byExecId;
        this.available = available;
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

    /**
     * Builds a view from a labelled pod listing.
     *
     * @param pods         pods carrying {@code execIdLabel}; null is treated as empty
     * @param execIdLabel  the label key holding the StarExec execution id
     */
    public static PodPhaseView of(List<Pod> pods, String execIdLabel) {
        Map<Integer, Observation> observations = new HashMap<>();
        if (pods == null) {
            return new PodPhaseView(observations, true);
        }

        for (Pod pod : pods) {
            Integer execId = extractExecId(pod, execIdLabel);
            if (execId == null) {
                continue;
            }
            Observation observed = observe(pod);
            observations.merge(execId, observed, PodPhaseView::preferred);
        }
        return new PodPhaseView(observations, true);
    }

    /**
     * Whether this view carries information. False means the pod listing failed and every
     * other method returns a "do not know, do not act" answer.
     */
    public boolean isAvailable() {
        return available;
    }

    /** The phase of the pod behind {@code execId}, or {@link Phase#UNKNOWN}. */
    public Phase phaseFor(int execId) {
        Observation observation = byExecId.get(execId);
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
    public long createdAtMillis(int execId) {
        Observation observation = byExecId.get(execId);
        return (observation == null) ? UNKNOWN_TIME : observation.createdAtMillis;
    }

    /**
     * Kubernetes' own account of why the pod has not started, for an operator to read.
     *
     * <p>Reported, never branched on. Scheduler and kubelet messages are prose that
     * changes between releases; a control-flow decision resting on their wording is the
     * same mistake as parsing a solver's stdout for its exit condition.
     */
    public String describeWhyPending(int execId) {
        Observation observation = byExecId.get(execId);
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
