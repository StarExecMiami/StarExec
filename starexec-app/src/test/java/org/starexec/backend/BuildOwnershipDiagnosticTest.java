package org.starexec.backend;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerConfig;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.starexec.data.database.SolverBuildArtifacts;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuildOwnershipDiagnosticTest {
    @ParameterizedTest
    @ValueSource(strings = {"starexec.build.artifact", "starexec.pair.id", "starexec.attempt", "starexec.exec.id"})
    void malformedPodmanNumbersHaveAnOwnershipDiagnosticBeforeCatalogAccess(String field) {
        for (String value : new String[]{"invalid", "9223372036854775808", null}) {
            var labels = podmanLabels();
            labels.put(field, value);
            var inspection = inspection(labels);
            try (var catalog = mockStatic(SolverBuildArtifacts.class)) {
                var failure = assertThrows(IllegalStateException.class,
                        () -> PodmanBuildStopObserver.recordIfBuild(inspection, "build-container"));
                assertEquals("Build identity contains invalid numeric metadata", failure.getMessage());
                assertInstanceOf(NumberFormatException.class, failure.getCause());
                catalog.verifyNoInteractions();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {KubernetesBuildStopObserver.ARTIFACT_LABEL, "starexec.org/pair-id", "starexec.org/attempt-no", "starexec.org/exec-id"})
    void malformedKubernetesNumbersHaveAnOwnershipDiagnosticBeforeCatalogAccess(String field) {
        for (String value : new String[]{"invalid", "9223372036854775808", null}) {
            var labels = kubernetesLabels();
            labels.put(field, value);
            var job = new JobBuilder().withNewMetadata().withNamespace("starexec")
                    .withName("build-job").withUid("build-uid").withLabels(labels).endMetadata().build();
            try (var catalog = mockStatic(SolverBuildArtifacts.class)) {
                var failure = assertThrows(IllegalStateException.class,
                        () -> KubernetesBuildStopObserver.identify("starexec", job));
                assertEquals("Build identity contains invalid numeric metadata", failure.getMessage());
                assertInstanceOf(NumberFormatException.class, failure.getCause());
                catalog.verifyNoInteractions();
            }
        }
    }

    @Test
    void validPodmanNumbersKeepTheirExecutionIdentity() {
        assertEquals(1001, PodmanBuildStopObserver.buildExecution(inspection(podmanLabels()), "build-container").intValue());
    }

    @Test
    void validKubernetesNumbersKeepTheirExecutionIdentity() {
        var job = new JobBuilder().withNewMetadata().withNamespace("starexec")
                .withName("build-job").withUid("build-uid").withLabels(kubernetesLabels()).endMetadata().build();
        var identity = KubernetesBuildStopObserver.identify("starexec", job);
        assertEquals(5, identity.artifactId());
        assertEquals(42, identity.pairId());
        assertEquals(1, identity.attemptNo());
        assertEquals(1001, identity.execution().execId());
    }

    private static InspectContainerResponse inspection(Map<String, String> labels) {
        var inspection = mock(InspectContainerResponse.class);
        when(inspection.getId()).thenReturn("build-container");
        when(inspection.getConfig()).thenReturn(new ContainerConfig().withLabels(labels));
        return inspection;
    }

    private static Map<String, String> podmanLabels() {
        return new HashMap<>(Map.of("starexec.build.artifact", "5", "starexec.pair.id", "42",
                "starexec.attempt", "1", "starexec.exec.id", "1001", "starexec.managed", "true",
                "starexec.label.version", "2", "starexec.kind", "job-pair"));
    }

    private static Map<String, String> kubernetesLabels() {
        return new HashMap<>(Map.of(KubernetesBuildStopObserver.ARTIFACT_LABEL, "5", "starexec.org/pair-id", "42",
                "starexec.org/attempt-no", "1", "starexec.org/exec-id", "1001", "starexec.org/managed", "true"));
    }
}
