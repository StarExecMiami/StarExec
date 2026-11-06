package org.starexec.backend;

import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.List;
import java.util.Map;

/** Prevents the initial launch of a build whose Job has never been enabled. */
final class KubernetesBuildLaunchFence {
    static final String ANNOTATION = "starexec.org/build-launch-fence";
    private static final List<String> CONTROLLER_UID_LABELS =
            List.of("batch.kubernetes.io/controller-uid", "controller-uid");

    record Observation(Job job, PodList pods) { }

    private KubernetesBuildLaunchFence() { }

    static boolean isFenced(Job job) {
        return job != null && job.getMetadata() != null && job.getMetadata().getAnnotations() != null
                && job.getMetadata().getAnnotations().containsKey(ANNOTATION);
    }

    static Observation preventStart(KubernetesClient client, String namespace,
                                     KubernetesBuildStopObserver.Identity expected, Job observed) {
        Map<String, String> selector = validate(namespace, expected, observed);
        PodList pods = client.pods().inNamespace(namespace).withLabels(selector).list();
        requireEmpty(pods);
        if (isFenced(observed)) {
            requireValidFence(observed);
            return new Observation(observed, pods);
        }
        String version = observed.getMetadata().getResourceVersion();
        Job replacement = new JobBuilder(observed).editMetadata().addToAnnotations(ANNOTATION, version).endMetadata().build();
        Job fenced = client.batch().v1().jobs().inNamespace(namespace)
                .resource(replacement).lockResourceVersion(version).replace();
        require(selector.equals(validate(namespace, expected, fenced)), "Build selector changed during launch fencing");
        require(isFenced(fenced) && version.equals(fenced.getMetadata().getAnnotations().get(ANNOTATION)),
                "Build launch fence was not acknowledged");
        requireValidFence(fenced);
        // A late create/admission can only resume the old version. The fixed-version
        // replace invalidates that request; a fresh snapshot must reject the marker.
        pods = client.pods().inNamespace(namespace).withLabels(selector).list();
        requireEmpty(pods);
        return new Observation(fenced, pods);
    }

    static Map<String, String> validate(String namespace, KubernetesBuildStopObserver.Identity expected, Job job) {
        return validate(namespace, expected, job, false);
    }

    /** Reuses an already committed fence, including while its Job is being removed. */
    static Observation observeConserved(KubernetesClient client, String namespace,
                                        KubernetesBuildStopObserver.Identity expected, Job job, String previousVersion) {
        Map<String, String> selector = validate(namespace, expected, job, true);
        require(isFenced(job) && previousVersion.equals(job.getMetadata().getAnnotations().get(ANNOTATION)),
                "Build Job does not retain its conserved launch fence");
        requireValidFence(job);
        PodList pods = client.pods().inNamespace(namespace).withLabels(selector).list();
        requireEmpty(pods);
        return new Observation(job, pods);
    }

    private static Map<String, String> validate(String namespace, KubernetesBuildStopObserver.Identity expected,
                                                Job job, boolean removing) {
        require(expected != null && expected.equals(KubernetesBuildStopObserver.identify(namespace, job)),
                "Build Job differs from its expected identity");
        var metadata = job.getMetadata(); var spec = job.getSpec();
        require(Long.valueOf(1).equals(metadata.getGeneration()) && (removing || metadata.getDeletionTimestamp() == null),
                "Build Job is not an intact initial generation");
        require(nonblank(metadata.getResourceVersion()), "Build Job resource version is unavailable");
        require(spec != null && Boolean.TRUE.equals(spec.getSuspend()) && !Boolean.TRUE.equals(spec.getManualSelector())
                && spec.getAdditionalProperties().get("managedBy") == null,
                "Build initial suspended controller is unverified");
        require(spec.getSelector() != null && spec.getSelector().getMatchLabels() != null
                && (spec.getSelector().getMatchExpressions() == null || spec.getSelector().getMatchExpressions().isEmpty()),
                "Build Job selector is unavailable");
        Map<String, String> selector = spec.getSelector().getMatchLabels();
        require(CONTROLLER_UID_LABELS.stream().anyMatch(key -> expected.execution().jobUid().equals(selector.get(key))),
                "Build Job selector is not bound to its UID");
        require(CONTROLLER_UID_LABELS.stream().allMatch(key -> !selector.containsKey(key)
                || expected.execution().jobUid().equals(selector.get(key))), "Build selector contains conflicting controller UIDs");
        require(selector.entrySet().stream().allMatch(e -> nonblank(e.getKey()) && nonblank(e.getValue())),
                "Build Job selector is invalid");
        require(spec.getTemplate() != null && spec.getTemplate().getMetadata() != null
                && spec.getTemplate().getMetadata().getLabels() != null
                && selector.entrySet().stream().allMatch(e -> e.getValue().equals(spec.getTemplate().getMetadata().getLabels().get(e.getKey()))),
                "Build Pod template differs from its selector");
        var status = job.getStatus();
        if (status != null) {
            require(status.getStartTime() == null && status.getCompletionTime() == null
                    && zero(status.getActive()) && zero(status.getSucceeded()) && zero(status.getFailed())
                    && zero(status.getReady()) && zero(status.getTerminating())
                    && !nonblank(status.getCompletedIndexes()) && !nonblank(status.getFailedIndexes()),
                    "Build Job has execution observations");
            var uncounted = status.getUncountedTerminatedPods();
            require(uncounted == null || (uncounted.getSucceeded() == null || uncounted.getSucceeded().isEmpty())
                    && (uncounted.getFailed() == null || uncounted.getFailed().isEmpty()),
                    "Build Job has uncounted terminated Pods");
            require(status.getConditions() == null || status.getConditions().stream().allMatch(c -> c != null
                    && "Suspended".equals(c.getType()) && "True".equals(c.getStatus())),
                    "Build Job conditions do not describe an initial suspension");
        }
        return Map.copyOf(selector);
    }

    private static void requireValidFence(Job job) {
        String version = job.getMetadata().getAnnotations().get(ANNOTATION);
        require(nonblank(version) && !version.equals(job.getMetadata().getResourceVersion()),
                "Build launch fence did not advance the observed resource version");
    }

    private static void requireEmpty(PodList pods) {
        require(pods != null && pods.getItems() != null && pods.getItems().isEmpty() && pods.getMetadata() != null
                && nonblank(pods.getMetadata().getResourceVersion())
                && (pods.getMetadata().getContinue() == null || pods.getMetadata().getContinue().isEmpty())
                && (pods.getMetadata().getRemainingItemCount() == null || pods.getMetadata().getRemainingItemCount() == 0),
                "A complete empty build Pod census is unavailable");
    }

    private static boolean zero(Integer count) { return count == null || count == 0; }
    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
