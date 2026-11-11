package org.starexec.backend;

import com.google.gson.JsonObject;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Retains build Pod observations until their matching stop receipt is durable. */
final class KubernetesBuildPodRetention {
    static final String FINALIZER = "starexec.org/build-stop-evidence";
    private static final List<String> UID_LABELS = List.of("batch.kubernetes.io/controller-uid", "controller-uid");

    private KubernetesBuildPodRetention() { }

    static boolean isRetained(Job job) {
        return job != null && job.getSpec() != null && job.getSpec().getTemplate() != null
                && job.getSpec().getTemplate().getMetadata() != null
                && job.getSpec().getTemplate().getMetadata().getFinalizers() != null
                && job.getSpec().getTemplate().getMetadata().getFinalizers().contains(FINALIZER);
    }

    static PodList observe(KubernetesClient client, String namespace,
                           KubernetesBuildStopObserver.Identity expected, Job job) throws SQLException {
        PodList pods = list(client, namespace, expected, job);
        var prior = KubernetesBuildStopObserver.conservedReceipt(namespace, expected);
        if (prior == null) {
            require(pods.getItems().stream().allMatch(p -> p != null && p.getMetadata() != null
                    && p.getMetadata().getFinalizers() != null && p.getMetadata().getFinalizers().contains(FINALIZER)),
                    "Build Pod evidence retention is missing before its first receipt");
        } else {
            requireConservedPods(namespace, expected, pods, prior.facts());
        }
        return pods;
    }

    static void release(KubernetesClient client, String namespace,
                        KubernetesBuildStopObserver.Identity expected, Job job) throws SQLException {
        require(job != null && KubernetesBuildStopObserver.terminalCondition(job) != null,
                "Build controller can still create Pods");
        var receipt = KubernetesBuildStopObserver.conservedReceipt(namespace, expected);
        require(receipt != null, "Build Pod evidence cannot be released before its stop receipt commits");
        PodList pods = list(client, namespace, expected, job);
        requireConservedPods(namespace, expected, pods, receipt.facts());
        for (Pod pod : pods.getItems()) {
            List<String> finalizers = pod.getMetadata().getFinalizers();
            if (finalizers == null || !finalizers.contains(FINALIZER)) continue;
            String version = pod.getMetadata().getResourceVersion();
            require(nonblank(version), "Build Pod resource version is unavailable");
            List<String> remaining = finalizers.stream().filter(f -> !FINALIZER.equals(f)).toList();
            Pod replacement = new PodBuilder(pod).editMetadata().withFinalizers(remaining).endMetadata().build();
            Pod updated = client.pods().inNamespace(namespace).resource(replacement).lockResourceVersion(version).update();
            require(updated != null && updated.getMetadata() != null
                    && pod.getMetadata().getUid().equals(updated.getMetadata().getUid())
                    && pod.getMetadata().getName().equals(updated.getMetadata().getName())
                    && namespace.equals(updated.getMetadata().getNamespace())
                    && nonblank(updated.getMetadata().getResourceVersion())
                    && !version.equals(updated.getMetadata().getResourceVersion())
                    && sameFinalizers(remaining, updated.getMetadata().getFinalizers()),
                    "Build Pod evidence release was not acknowledged for its observed identity");
        }
    }

    static Map<String, String> validate(String namespace, KubernetesBuildStopObserver.Identity expected, Job job) {
        require(expected != null && expected.equals(KubernetesBuildStopObserver.identify(namespace, job)) && isRetained(job),
                "Build Job differs from its retained identity");
        var spec = job.getSpec();
        require(!Boolean.TRUE.equals(spec.getManualSelector()) && spec.getManagedBy() == null
                && spec.getAdditionalProperties().get("managedBy") == null
                && spec.getSelector() != null && spec.getSelector().getMatchLabels() != null
                && (spec.getSelector().getMatchExpressions() == null || spec.getSelector().getMatchExpressions().isEmpty()),
                "Build controller selector is unverified");
        Map<String, String> selector = spec.getSelector().getMatchLabels();
        require(UID_LABELS.stream().anyMatch(key -> expected.execution().jobUid().equals(selector.get(key)))
                && UID_LABELS.stream().allMatch(key -> !selector.containsKey(key)
                    || expected.execution().jobUid().equals(selector.get(key)))
                && selector.entrySet().stream().allMatch(e -> nonblank(e.getKey()) && nonblank(e.getValue())),
                "Build selector is not bound to its actual Job UID");
        var labels = spec.getTemplate().getMetadata().getLabels();
        require(labels != null && selector.entrySet().stream().allMatch(e -> e.getValue().equals(labels.get(e.getKey()))),
                "Build Pod template differs from its selector");
        return selector;
    }

    private static PodList list(KubernetesClient client, String namespace,
                                KubernetesBuildStopObserver.Identity expected, Job job) {
        Map<String, String> selector = validate(namespace, expected, job);
        PodList pods = client.pods().inNamespace(namespace).withLabels(selector).list();
        require(pods != null && pods.getItems() != null && pods.getMetadata() != null
                && nonblank(pods.getMetadata().getResourceVersion())
                && (pods.getMetadata().getContinue() == null || pods.getMetadata().getContinue().isEmpty())
                && (pods.getMetadata().getRemainingItemCount() == null || pods.getMetadata().getRemainingItemCount() == 0),
                "Complete retained build Pod observations are unavailable");
        return pods;
    }

    private static void requireConservedPods(String namespace, KubernetesBuildStopObserver.Identity expected,
                                             PodList pods, JsonObject facts) {
        Map<String, JsonObject> conserved = new HashMap<>();
        for (var item : facts.getAsJsonArray("pods")) {
            var pod = item.getAsJsonObject();
            conserved.put(pod.get("uid").getAsString(), pod);
        }
        var seen = new HashSet<String>();
        for (Pod pod : pods.getItems()) {
            require(pod != null && pod.getMetadata() != null && nonblank(pod.getMetadata().getUid())
                    && seen.add(pod.getMetadata().getUid()), "Build Pod listing has an invalid or repeated identity");
            JsonObject observed = KubernetesBuildStopObserver.observePod(namespace, expected, pod);
            require(observed.equals(conserved.get(pod.getMetadata().getUid())),
                    "Build Pod termination differs from its conserved receipt");
        }
    }

    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
    private static boolean sameFinalizers(List<String> expected, List<String> observed) {
        List<String> actual = observed == null ? List.of() : observed;
        return expected.size() == actual.size() && new HashSet<>(expected).equals(new HashSet<>(actual));
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
