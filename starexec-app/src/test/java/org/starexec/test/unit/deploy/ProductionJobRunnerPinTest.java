package org.starexec.test.unit.deploy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

/**
 * Guards the production job-runner reference in the Helm values.
 *
 * <p>The compute workers are isolated from the public registry and are provisioned with the
 * job-runner image directly, so production has to name that image by a digest it already
 * holds. A tag cannot express that: it is mutable, and Kubernetes derives {@code Always} as
 * the pull policy from {@code :latest}, which asks an isolated node to reach a registry it
 * cannot see.
 *
 * <p>This is a regression guard rather than a style check. The pin previously existed only
 * as a live Helm override while the chart file kept {@code :latest}, so an upgrade whose
 * values omitted it reverted the reference with nothing failing. Recording the digest in the
 * file fixed that; this test is what stops the file drifting back.
 *
 * <p>Paths are resolved from the module directory, matching the existing script tests.
 */
public class ProductionJobRunnerPinTest {

    private static final Path PROD_VALUES = Path.of("../charts/starexec/values-prod.yaml");

    /** The linux/amd64 manifest inside the index that {@code :latest} resolves to. */
    private static final String PINNED_DIGEST =
        "sha256:63ae82d539f7c26bc70f42036f968897fc110416043d2fedc8a917b828537cdd";

    private List<String> settingLines(String key) throws IOException {
        assertTrue(
            PROD_VALUES + " must exist; it is the authoritative production values file",
            Files.isRegularFile(PROD_VALUES)
        );
        return Files.readAllLines(PROD_VALUES, StandardCharsets.UTF_8).stream()
            .map(String::trim)
            .filter(line -> line.startsWith(key + ":"))
            .collect(Collectors.toList());
    }

    @Test
    public void productionPinsTheJobRunnerByDigest() throws Exception {
        List<String> lines = settingLines("jobImage");
        assertTrue("values-prod.yaml must set kubernetes.jobImage", lines.size() == 1);

        String value = lines.get(0);
        assertTrue(
            "production must reference the validated linux/amd64 manifest, but found: " + value,
            value.contains("@" + PINNED_DIGEST)
        );
        assertFalse(
            "production must not reference a mutable tag; isolated workers cannot re-resolve"
                + " it, and it makes Kubernetes derive Always. Found: " + value,
            value.contains("starexec-job-runner:latest")
        );
    }

    @Test
    public void productionStatesThePullPolicyItReliesOn() throws Exception {
        List<String> lines = settingLines("jobImagePullPolicy");
        assertTrue("values-prod.yaml must set kubernetes.jobImagePullPolicy", lines.size() == 1);
        assertTrue(
            "isolated workers must be allowed to use the image they already hold, but found: "
                + lines.get(0),
            lines.get(0).endsWith("IfNotPresent")
        );
    }
}
