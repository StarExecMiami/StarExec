package org.starexec.backend;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.util.Objects;

/**
 * One concrete Kubernetes execution: the legacy StarExec execution id together with the
 * Kubernetes object that actually carries the work.
 *
 * <p>The execution id alone does not identify anything. {@code KubernetesNativeBackend}
 * allocates it from an in-memory counter that restarts at 1 in every application lifetime,
 * while {@code job_pairs.sge_id} values written by earlier lifetimes survive in the database.
 * The same integer therefore names different executions at different times, and on
 * 2026-09-05 a cancellation recorded against historical execution 2 discarded the callbacks
 * of a live Job that had been given the same number thirty-five minutes later.
 *
 * <p>The Job UID is what separates them. Kubernetes assigns it at creation and never reuses
 * it, so it distinguishes two objects that share a name as well as two that share a label —
 * and the name is not a substitute: {@code generateJobName} builds
 * {@code starexec-job-<execId>-<millis % 100000>}, which repeats every hundred seconds.
 *
 * <p>Deliberately no equality on {@code execId} alone, and no constructor that will accept a
 * missing UID. Both would reintroduce exactly the aliasing this type exists to remove, and
 * would do it silently.
 *
 * <p>This is in-process identity. It says which Kubernetes object an event came from; it does
 * not say which logical attempt of a pair may receive a result, which is durable state and a
 * separate concern — see {@code docs/k8s-execution-identity.md}.
 */
public final class ExecutionRef {

    private final int execId;
    private final String jobName;
    private final String jobUid;

    /**
     * @param execId  the legacy StarExec execution id, kept as a compatibility handle
     * @param jobName the Kubernetes Job's {@code metadata.name}
     * @param jobUid  the Kubernetes Job's {@code metadata.uid}
     * @throws IllegalArgumentException if any component is absent or blank
     */
    public ExecutionRef(int execId, String jobName, String jobUid) {
        if (jobName == null || jobName.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "ExecutionRef requires a Job name (execId " + execId + ")"
            );
        }
        if (jobUid == null || jobUid.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "ExecutionRef requires a Job UID (execId " + execId + ", job " + jobName + ")"
            );
        }
        this.execId = execId;
        this.jobName = jobName;
        this.jobUid = jobUid;
    }

    /**
     * The identity of a Job read back from the API server, or null if it does not carry one.
     *
     * <p>Null is a refusal, not a default. An object the API server has accepted always has a
     * UID, so its absence means the response is not the created object — and acting on an
     * execution whose identity cannot be established is how one execution's result lands on
     * another's pair.
     *
     * @param execId the execution id the caller has already parsed from the Job's label
     */
    public static ExecutionRef fromJob(int execId, Job job) {
        if (job == null) {
            return null;
        }
        ObjectMeta metadata = job.getMetadata();
        if (metadata == null) {
            return null;
        }
        String name = metadata.getName();
        String uid = metadata.getUid();
        if (name == null || name.trim().isEmpty() || uid == null || uid.trim().isEmpty()) {
            return null;
        }
        return new ExecutionRef(execId, name, uid);
    }

    /** The legacy execution id. A handle for existing interfaces, never an identity. */
    public int execId() {
        return execId;
    }

    public String jobName() {
        return jobName;
    }

    public String jobUid() {
        return jobUid;
    }

    /** Whether this reference names the same Kubernetes object as {@code other}. */
    public boolean sameJobAs(ExecutionRef other) {
        return other != null && jobUid.equals(other.jobUid);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExecutionRef)) {
            return false;
        }
        ExecutionRef that = (ExecutionRef) other;
        return execId == that.execId
            && jobName.equals(that.jobName)
            && jobUid.equals(that.jobUid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(execId, jobName, jobUid);
    }

    /** Diagnostic only: the three identity components, and nothing else. */
    @Override
    public String toString() {
        return "execId " + execId + " (job " + jobName + ", uid " + jobUid + ")";
    }
}
