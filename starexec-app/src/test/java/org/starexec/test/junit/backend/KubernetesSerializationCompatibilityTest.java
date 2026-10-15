package org.starexec.test.junit.backend;

import io.fabric8.kubernetes.api.model.FieldsV1;
import io.fabric8.kubernetes.api.model.ManagedFieldsEntry;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * The Kubernetes client must be able to serialize what the API server returns.
 *
 * <p>fabric8 6.10 could not under the Jackson this build pins (2.21): cloning a Node, which
 * {@code edit()} does first, threw on its managedFields ("keySerializer" is null), and every
 * queue operation failed on Quokka (#352). 7.9 is built against Jackson 2.21. A later bump of
 * either that breaks the pairing again fails here, at build time, instead of in production.
 */
public class KubernetesSerializationCompatibilityTest {

    /** managedFields as the API server writes them: nested maps of field names. */
    private static ManagedFieldsEntry managedFields() {
        Map<String, Object> labels = new HashMap<>();
        labels.put("f:starexec/queue", new HashMap<>());
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("f:labels", labels);
        FieldsV1 fields = new FieldsV1();
        fields.setAdditionalProperty("f:metadata", metadata);
        ManagedFieldsEntry entry = new ManagedFieldsEntry();
        entry.setManager("kubectl-label");
        entry.setOperation("Update");
        entry.setApiVersion("v1");
        entry.setFieldsType("FieldsV1");
        entry.setFieldsV1(fields);
        return entry;
    }

    @Test
    public void aNodeWithManagedFieldsCanBeCloned() {
        Node node = new NodeBuilder()
            .withNewMetadata()
                .withName("n024")
                .addToLabels("starexec/queue", "kubernetes.q")
                .addToManagedFields(managedFields())
            .endMetadata()
            .build();

        Node copy = new KubernetesSerialization().clone(node);

        assertEquals("kubernetes.q", copy.getMetadata().getLabels().get("starexec/queue"));
        assertEquals(1, copy.getMetadata().getManagedFields().size());
        assertEquals(node.getMetadata().getManagedFields().get(0).getFieldsV1().getAdditionalProperties(),
            copy.getMetadata().getManagedFields().get(0).getFieldsV1().getAdditionalProperties());
    }

    @Test
    public void aJobWithManagedFieldsRoundTripsThroughJson() {
        KubernetesSerialization serialization = new KubernetesSerialization();
        Job job = new JobBuilder()
            .withNewMetadata()
                .withName("starexec-job-77")
                .addToAnnotations("starexec.org/output-dir", "/app/data/logs/5/9/pair_77")
                .addToManagedFields(managedFields())
            .endMetadata()
            .build();

        Job back = serialization.unmarshal(serialization.asJson(job), Job.class);

        assertEquals("/app/data/logs/5/9/pair_77",
            back.getMetadata().getAnnotations().get("starexec.org/output-dir"));
        assertEquals(1, back.getMetadata().getManagedFields().size());
    }
}
