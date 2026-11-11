package org.starexec.backend;

import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.sql.SQLException;
import java.util.HashSet;
import org.starexec.data.database.SolverBuildArtifacts;

/** Requests controller termination while retaining the Pods needed for stop evidence. */
final class KubernetesBuildCancellation {
    static final String ANNOTATION = "starexec.org/build-cancel-request";
    static final long DEADLINE_SECONDS = 1L;

    private KubernetesBuildCancellation() { }

    static boolean isRequested(Job job) {
        return job != null && job.getMetadata() != null && job.getMetadata().getAnnotations() != null
                && job.getMetadata().getAnnotations().containsKey(ANNOTATION);
    }

    static Job request(KubernetesClient client, String namespace,
                       KubernetesBuildStopObserver.Identity expected, Job observed) throws SQLException {
        validate(namespace, expected, observed);
        require(observed.getMetadata().getDeletionTimestamp() == null
                && KubernetesBuildStopObserver.terminalCondition(observed) == null,
                "Build cancellation requires an intact enabled controller");
        var prior = SolverBuildArtifacts.findStopReceipt(expected.pairId(), expected.attemptNo());
        require(prior.isEmpty() || isRequested(observed) && KubernetesBuildStopObserver.isCancelled(
                KubernetesBuildStopObserver.conservedReceipt(namespace, expected)),
                "Build already has a different conserved stop outcome");
        requireOwnedPods(namespace, expected, KubernetesBuildPodRetention.observe(client, namespace, expected, observed));
        if (isRequested(observed)) {
            acknowledgedVersion(namespace, expected, observed);
            return observed;
        }
        String version = observed.getMetadata().getResourceVersion();
        Job replacement = new JobBuilder(observed).editMetadata().addToAnnotations(ANNOTATION, version).endMetadata()
                .editSpec().withActiveDeadlineSeconds(DEADLINE_SECONDS).endSpec().build();
        Job updated = client.batch().v1().jobs().inNamespace(namespace).resource(replacement)
                .lockResourceVersion(version).update();
        require(version.equals(acknowledgedVersion(namespace, expected, updated))
                && replacement.getSpec().equals(updated.getSpec())
                && Long.valueOf(Math.addExact(observed.getMetadata().getGeneration(),
                    observed.getSpec().equals(replacement.getSpec()) ? 0L : 1L)).equals(updated.getMetadata().getGeneration()),
                "Build cancellation was not acknowledged for its observed version and specification");
        requireOwnedPods(namespace, expected, KubernetesBuildPodRetention.observe(client, namespace, expected, updated));
        return updated;
    }

    static String acknowledgedVersion(String namespace, KubernetesBuildStopObserver.Identity expected, Job job) {
        validate(namespace, expected, job);
        String requested = isRequested(job) ? job.getMetadata().getAnnotations().get(ANNOTATION) : null;
        require(requested != null && !requested.isBlank()
                && !requested.equals(job.getMetadata().getResourceVersion())
                && Long.valueOf(DEADLINE_SECONDS).equals(job.getSpec().getActiveDeadlineSeconds()),
                "Build cancellation marker or deadline is unacknowledged");
        return requested;
    }

    private static void validate(String namespace, KubernetesBuildStopObserver.Identity expected, Job job) {
        KubernetesBuildPodRetention.validate(namespace, expected, job);
        require(job.getMetadata().getGeneration() != null && job.getMetadata().getGeneration() > 1
                && job.getMetadata().getResourceVersion() != null && !job.getMetadata().getResourceVersion().isBlank()
                && Boolean.FALSE.equals(job.getSpec().getSuspend()) && !KubernetesBuildLaunchFence.isFenced(job),
                "Build cancellation requires an admitted enabled generation without an initial launch fence");
    }

    static void requireOwnedPods(String namespace, KubernetesBuildStopObserver.Identity expected, PodList pods) {
        require(pods != null && pods.getMetadata() != null && pods.getMetadata().getResourceVersion() != null
                && !pods.getMetadata().getResourceVersion().isBlank() && pods.getItems() != null
                && (pods.getMetadata().getContinue() == null || pods.getMetadata().getContinue().isEmpty())
                && (pods.getMetadata().getRemainingItemCount() == null || pods.getMetadata().getRemainingItemCount() == 0),
                "Complete build cancellation Pod observations are unavailable");
        var seen = new HashSet<String>();
        for (var pod : pods.getItems()) {
            require(pod != null && pod.getMetadata() != null && namespace.equals(pod.getMetadata().getNamespace())
                    && pod.getMetadata().getUid() != null && !pod.getMetadata().getUid().isBlank()
                    && seen.add(pod.getMetadata().getUid()) && pod.getMetadata().getName() != null
                    && !pod.getMetadata().getName().isBlank(), "Build cancellation Pod identity differs");
            var owners = pod.getMetadata().getOwnerReferences();
            require(owners != null && owners.stream().filter(o -> o != null && Boolean.TRUE.equals(o.getController())).count() == 1
                    && owners.stream().anyMatch(o -> o != null && Boolean.TRUE.equals(o.getController())
                        && "batch/v1".equals(o.getApiVersion()) && "Job".equals(o.getKind())
                        && expected.execution().jobUid().equals(o.getUid())
                        && expected.execution().jobName().equals(o.getName())),
                    "Build cancellation Pod belongs to a different controller");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
