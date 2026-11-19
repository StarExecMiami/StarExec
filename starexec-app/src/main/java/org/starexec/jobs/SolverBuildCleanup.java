package org.starexec.jobs;

import org.starexec.data.database.Common;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Trusted, retryable removal of exactly registered stopped-build storage. */
public final class SolverBuildCleanup {
    private SolverBuildCleanup() { }

    /**
     * Parents must be application-controlled and excluded from runner writes.
     * Claims commit before filesystem work; identity/reference locks span removal
     * and settlement. Failures preserve the claim, original charge and resource proof.
     * Entry limits apply to each tree. The caller closes the autoCommit connection.
     */
    public static boolean purge(Connection connection, long artifact, Path stagingParent,
                                Path outputParent, Path sourceParent, int maxEntries) throws SQLException {
        if (maxEntries < 1) throw new IllegalArgumentException("Cleanup requires a positive entry limit");
        if (!connection.getAutoCommit()) throw new SQLException("Cleanup cannot use a foreign transaction");
        var initial = SolverBuildArtifacts.findCleanup(connection, artifact).orElse(null);
        if (initial == null) return false;
        UUID token = initial.token() == null ? UUID.randomUUID() : initial.token();
        if (!Common.runTransactional(connection, tx -> SolverBuildArtifacts.claimCleanup(tx, artifact, token))) return false;
        return Common.runTransactional(connection, tx -> {
            if (!SolverBuildArtifacts.claimCleanup(tx, artifact, token)) return false;
            var cleanup = SolverBuildArtifacts.findCleanup(tx, artifact).orElseThrow();
            List<Path> paths = paths(cleanup, stagingParent, outputParent, sourceParent);
            // Validate every parent before the first irreversible removal.
            for (Path path : paths) {
                try (DescriptorDirectory ignored = DescriptorDirectory.open(path.getParent())) { }
            }
            if (cleanup.finishedAt() != null) {
                for (Path path : paths) requireAbsent(path);
                return true;
            }
            for (Path path : paths) {
                try (DescriptorDirectory parent = DescriptorDirectory.open(path.getParent())) {
                    parent.deleteTree(path.getFileName().toString(), maxEntries);
                    parent.force();
                }
                requireAbsent(path);
            }
            if (!SolverBuildArtifacts.finishCleanup(tx, artifact, token)) {
                throw new SQLException("Cleanup identity changed before settlement");
            }
            return true;
        });
    }

    private static List<Path> paths(SolverBuildArtifacts.Cleanup cleanup, Path stagingParent,
                                    Path outputParent, Path sourceParent) throws IOException {
        List<Path> result = new ArrayList<>();
        Path staging = confined(stagingParent, cleanup.stagingPath());
        if (!staging.getParent().equals(stagingParent)
                || !staging.getFileName().toString().equals("build-" + cleanup.executionToken())) {
            throw new IOException("Staging cleanup requires its exact registered execution directory");
        }
        result.add(staging);
        if (cleanup.storageRoot() != null) {
            Path output = confined(outputParent, cleanup.storageRoot());
            if (!output.getParent().equals(outputParent) || !output.getFileName().toString()
                    .equals("build-" + cleanup.artifactId() + "-" + cleanup.executionToken())) {
                throw new IOException("Output cleanup requires its exact registered artifact directory");
            }
            result.add(output);
        }
        if (cleanup.removedSourcePath() != null) result.add(confined(sourceParent, cleanup.removedSourcePath()));
        for (int i = 0; i < result.size(); i++) {
            for (int j = i + 1; j < result.size(); j++) {
                if (result.get(i).startsWith(result.get(j)) || result.get(j).startsWith(result.get(i))) {
                    throw new IOException("Registered cleanup trees overlap");
                }
            }
        }
        return result;
    }

    private static Path confined(Path parent, String value) throws IOException {
        if (parent == null || !parent.isAbsolute() || parent.getParent() == null || !parent.equals(parent.normalize())
                || value == null) throw new IOException("Cleanup requires explicit normalized non-root parents");
        Path path = Path.of(value);
        if (!path.isAbsolute() || !path.equals(path.normalize()) || path.equals(parent) || !path.startsWith(parent)) {
            throw new IOException("Registered cleanup path is outside its authorized parent");
        }
        try (DescriptorDirectory ignored = DescriptorDirectory.open(parent)) { }
        return path;
    }

    private static void requireAbsent(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            throw new IOException("Registered cleanup bytes remain present");
        } catch (NoSuchFileException absent) { }
    }
}
