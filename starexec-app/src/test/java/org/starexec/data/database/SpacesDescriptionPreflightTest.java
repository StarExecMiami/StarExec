package org.starexec.data.database;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.starexec.constants.R;
import org.starexec.util.Validator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SpacesDescriptionPreflightTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @BeforeClass
    public static void initializeValidator() {
        Validator.initialize();
    }

    @Test
    public void validatesAllSubspaceDescriptionsBeforeTraversalWrites() throws Exception {
        Path root = temporaryFolder.newFolder("benchmark-tree").toPath();
        Path validSubspace = Files.createDirectory(root.resolve("valid-space"));
        Files.writeString(
            validSubspace.resolve(R.BENCHMARK_DESC_PATH),
            "C++ solver - 2017-05-22",
            StandardCharsets.UTF_8
        );
        Spaces.validateBenchmarkDescriptionFiles(root);

        Path invalidSubspace = Files.createDirectory(root.resolve("invalid-space"));
        Files.writeString(
            invalidSubspace.resolve(R.BENCHMARK_DESC_PATH),
            "<script>stored-xss</script>",
            StandardCharsets.UTF_8
        );

        try {
            Spaces.validateBenchmarkDescriptionFiles(root);
            fail("Expected invalid nested description to abort preflight validation");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("invalid-space"));
            assertFalse(expected.getMessage().contains(root.toAbsolutePath().toString()));
        }
    }

    @Test
    public void rejectsSymbolicLinkDescriptionFiles() throws Exception {
        Path root = temporaryFolder.newFolder("symlink-tree").toPath();
        Path subspace = Files.createDirectory(root.resolve("linked-space"));
        Path target = temporaryFolder.newFile("external-description.txt").toPath();
        Files.writeString(target, "apparently safe", StandardCharsets.UTF_8);
        Files.createSymbolicLink(subspace.resolve(R.BENCHMARK_DESC_PATH), target);

        try {
            Spaces.validateBenchmarkDescriptionFiles(root);
            fail("Expected a symbolic-link description to abort preflight validation");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("regular file"));
        }
    }

    @Test
    public void rejectsOversizedDescriptionsWithABoundedRead() throws Exception {
        Path root = temporaryFolder.newFolder("oversized-tree").toPath();
        Path subspace = Files.createDirectory(root.resolve("oversized-space"));
        Files.writeString(
            subspace.resolve(R.BENCHMARK_DESC_PATH),
            "a".repeat(1_000_000),
            StandardCharsets.UTF_8
        );

        try {
            Spaces.validateBenchmarkDescriptionFiles(root);
            fail("Expected an oversized description to abort preflight validation");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("exceeds 1024 characters"));
        }
    }
}
