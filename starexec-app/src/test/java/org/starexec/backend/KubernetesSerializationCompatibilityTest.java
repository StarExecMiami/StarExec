package org.starexec.backend;

import io.fabric8.kubernetes.api.model.FieldsV1;
import io.fabric8.kubernetes.api.model.ManagedFieldsEntry;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Protects API read-modify-write operations that preserve server-managed field maps. */
class KubernetesSerializationCompatibilityTest {
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
    void aNodeWithManagedFieldsCanBeCloned() {
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
    void aJobWithManagedFieldsRoundTripsThroughJson() {
        KubernetesSerialization serialization = new KubernetesSerialization();
        Job job = new JobBuilder()
            .withNewMetadata()
                .withName("starexec-job-77")
                .addToAnnotations("starexec.org/output-dir", "/app/data/logs/5/9/pair_77")
                .addToManagedFields(managedFields())
            .endMetadata()
            .build();

        Job back = serialization.unmarshal(serialization.asJson(job), Job.class);

        assertEquals("/app/data/logs/5/9/pair_77", back.getMetadata().getAnnotations().get("starexec.org/output-dir"));
        assertEquals(1, back.getMetadata().getManagedFields().size());
    }
}
