package org.starexec.jobs;

import com.google.gson.JsonParser;
import org.starexec.backend.BuildExecutionContext;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/** Trusted promotion of a stopped build's output into separately reserved immutable storage. */
public final class SolverBuildPublication {
    private SolverBuildPublication() { }

    /**
     * The application must exclude the destination and its ancestors from runner writes.
     * A conserved provider receipt must establish stopped writers. This operation never
     * removes input or an unvalidated reserved destination; unresolved copies retain their
     * catalogue record for trusted recovery. The caller closes the autoCommit connection.
     */
    public static boolean publish(Connection connection, BuildExecutionContext build, Path parent,
                                  long maxBytes, int maxEntries) throws SQLException, IOException {
        if (!connection.getAutoCommit()) throw new SQLException("Output reservation must commit before copying");
        if (maxBytes < 0 || maxEntries < 1) throw new IllegalArgumentException("Invalid artifact limits");
        if (parent == null || !parent.isAbsolute() || parent.getParent() == null || !parent.equals(parent.normalize())) {
            throw new IOException("Artifact parent must be an absolute normalized non-root directory");
        }
        var artifact = SolverBuildArtifacts.findCurrent(connection, build.pairId()).orElse(null);
        if (artifact == null || artifact.id() != build.artifactId() || artifact.pairId() != build.pairId()
                || artifact.attemptNo() != build.attemptNo() || !build.token().equals(artifact.executionToken())
                || !build.source().toString().equals(artifact.sourcePath())
                || !build.staging().toString().equals(artifact.stagingPath()) || artifact.liveSolverId() == null
                || artifact.engineIdentity() == null || artifact.stoppedAt() == null
                || !Set.of("PLANNED", "STAGED", "VALIDATED", "PUBLISHED").contains(artifact.state())) return false;
        var stored = SolverBuildArtifacts.findStopReceipt(connection, build.pairId(), build.attemptNo()).orElse(null);
        if (stored == null || !Set.of("podman", "kubernetes").contains(stored.receipt().backend())
                || !artifact.engineIdentity().startsWith(stored.receipt().backend() + ":")
                || !stored.receipt().matches(build.artifactId(), build.pairId(), build.attemptNo(), artifact.engineIdentity())
                || stored.receipt().facts().has("disposition") || !completed(connection, build.pairId())) return false;

        Path root = parent.resolve("build-" + build.artifactId() + "-" + build.token());
        if (root.startsWith(build.staging().normalize()) || root.startsWith(build.source().normalize())) {
            throw new IOException("Immutable output must be outside build input and staging");
        }
        if (artifact.storageRoot() != null && !root.toString().equals(artifact.storageRoot())) return false;
        try (DescriptorDirectory ignored = DescriptorDirectory.open(parent)) {
            if (!Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS)
                    .equals(PosixFilePermissions.fromString("rwx------"))) {
                throw new IOException("Artifact parent requires owner-only permissions");
            }
            SolverArtifactTree.Snapshot snapshot;
            if (artifact.state().equals("VALIDATED") || artifact.state().equals("PUBLISHED")) {
                var manifest = SolverBuildArtifacts.findOutputManifest(connection, artifact.id())
                        .orElseThrow(() -> new IOException("Validated artifact manifest is unavailable"));
                snapshot = restore(manifest);
                if (snapshot.byteSize() > maxBytes || snapshot.entryCount() > maxEntries) return false;
                SolverArtifactTree.verify(root, snapshot);
                requireLayout(root, snapshot);
            } else {
                requireDirectories(build.staging());
                if (!SolverBuildArtifacts.reserveOutput(connection, build, artifact.engineIdentity(), stored.receipt().sha256(), root)) return false;
                if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unvalidated artifact destination already exists; recovery must preserve its ownership");
                }
                snapshot = SolverArtifactTree.copyReserved(build.staging(), root, maxBytes, maxEntries);
                requireLayout(root, snapshot);
                var manifest = new SolverBuildArtifacts.OutputManifest(snapshot.payload(), snapshot.sha256(), snapshot.byteSize());
                if (!SolverBuildArtifacts.validateOutput(connection, build, artifact.engineIdentity(), stored.receipt().sha256(), root, manifest)) return false;
            }
            return SolverBuildArtifacts.publishOutput(connection, artifact.id());
        }
    }

    private static boolean completed(Connection connection, int pair) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT status_code=7 FROM starexec.job_pairs WHERE id=?")) {
            statement.setInt(1, pair);
            try (var result = statement.executeQuery()) { return result.next() && result.getBoolean(1); }
        }
    }

    private static SolverArtifactTree.Snapshot restore(SolverBuildArtifacts.OutputManifest manifest) throws IOException {
        try {
            var object = JsonParser.parseString(manifest.payload()).getAsJsonObject();
            if (object.get("version").getAsInt() != 1 || !object.get("entries").isJsonArray()) {
                throw new IOException("Unsupported artifact manifest");
            }
            return new SolverArtifactTree.Snapshot(manifest.payload(), manifest.sha256(), manifest.byteSize(), object.getAsJsonArray("entries").size());
        } catch (RuntimeException malformed) { throw new IOException("Invalid stored artifact manifest", malformed); }
    }

    private static void requireDirectories(Path root) throws IOException {
        if (!Files.isDirectory(root.resolve("solver"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Build output requires a solver directory");
        }
        Path logs = root.resolve("logs");
        if (Files.exists(logs, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(logs, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Saved build logs require a separate directory");
        }
    }

    private static void requireLayout(Path root, SolverArtifactTree.Snapshot snapshot) throws IOException {
        requireDirectories(root);
        var entries = JsonParser.parseString(snapshot.payload()).getAsJsonObject().getAsJsonArray("entries");
        for (var value : entries) {
            var entry = value.getAsJsonObject();
            if ("file".equals(entry.get("kind").getAsString()) && entry.get("path").getAsString().startsWith("solver/")) return;
        }
        throw new IOException("Build output has no regular solver files");
    }
}
