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

    /** The caller must conserve the catalog identity and keep this store excluded from runner writes. */
    public static void verifyPublishedInput(SolverBuildArtifacts.Artifact artifact,
                                             SolverBuildArtifacts.OutputManifest manifest) throws IOException {
        if (artifact==null || !"PUBLISHED".equals(artifact.state()) || artifact.storageRoot()==null || manifest==null) {
            throw new IOException("Published input has no conserved immutable output");
        }
        verifyConservedOutput(Path.of(artifact.storageRoot()),manifest,false,Integer.MAX_VALUE);
    }

    /**
     * The application must exclude the destination and its ancestors from runner writes.
     * A conserved provider receipt must establish stopped writers. This operation never
     * removes input or an unvalidated reserved destination; unresolved copies retain their
     * catalogue record for trusted recovery. The caller closes the autoCommit connection.
     */
    public static boolean publish(Connection connection, BuildExecutionContext build, Path parent,
                                  long maxBytes, int maxEntries) throws SQLException, IOException {
        return conserve(connection, build, parent, maxBytes, maxEntries, false, false);
    }

    /** Conserves unsuccessful build logs and records failure without replacing the original source. */
    public static boolean recordFailure(Connection connection, BuildExecutionContext build, Path parent,
                                        long maxBytes, int maxEntries) throws SQLException, IOException {
        return conserve(connection, build, parent, maxBytes, maxEntries, true, false);
    }

    /** Keeps diagnostics when cancellation follows a conserved ordinary stop; it does not record solver failure. */
    public static boolean recordCancelledLogs(Connection connection, BuildExecutionContext build, Path parent,
                                              long maxBytes, int maxEntries) throws SQLException, IOException {
        return conserve(connection,build,parent,maxBytes,maxEntries,true,true);
    }

    /** Read back the conserved copy before its stopped execution staging is discarded. */
    static void verifyConservedOutput(Path root, SolverBuildArtifacts.OutputManifest manifest,
                                      boolean failed, int maxEntries) throws IOException {
        SolverArtifactTree.Snapshot snapshot = restore(manifest);
        if (snapshot.entryCount() > maxEntries) throw new IOException("Conserved output exceeds its verification entry limit");
        if (failed) { SolverArtifactTree.verifyFailureLogs(root,snapshot); requireFailureLayout(snapshot); }
        else { SolverArtifactTree.verify(root,snapshot); requireLayout(root,snapshot); }
    }

    private static boolean conserve(Connection connection, BuildExecutionContext build, Path parent,
                                    long maxBytes, int maxEntries, boolean failed,boolean cancelled) throws SQLException, IOException {
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
                || !(failed ? Set.of("PLANNED", "STAGED", "REJECTED")
                            : Set.of("PLANNED", "STAGED", "VALIDATED", "PUBLISHED")).contains(artifact.state())) return false;
        var stored = SolverBuildArtifacts.findStopReceipt(connection, build.pairId(), build.attemptNo()).orElse(null);
        if (stored == null || !Set.of("podman", "kubernetes").contains(stored.receipt().backend())
                || !artifact.engineIdentity().startsWith(stored.receipt().backend() + ":")
                || !stored.receipt().matches(build.artifactId(), build.pairId(), build.attemptNo(), artifact.engineIdentity())
                || stored.receipt().facts().has("disposition") || !eligiblePair(connection, build.pairId(), failed,cancelled)) return false;

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
            var conserved = SolverBuildArtifacts.findOutputManifest(connection, artifact.id());
            if (failed && artifact.state().equals("REJECTED") && conserved.isEmpty()) return false;
            if (!failed && artifact.state().equals("STAGED") && conserved.isPresent()) return false;
            if (failed && conserved.isPresent() || artifact.state().equals("VALIDATED") || artifact.state().equals("PUBLISHED")) {
                var manifest = conserved
                        .orElseThrow(() -> new IOException("Validated artifact manifest is unavailable"));
                snapshot = restore(manifest);
                if (snapshot.byteSize() > maxBytes || snapshot.entryCount() > maxEntries) return false;
                if (failed) { SolverArtifactTree.verifyFailureLogs(root, snapshot); requireFailureLayout(snapshot); }
                else { SolverArtifactTree.verify(root, snapshot); requireLayout(root, snapshot); }
            } else {
                if (!failed) requireDirectories(build.staging());
                boolean reserved = SolverBuildArtifacts.reserveOutput(connection, build, artifact.engineIdentity(), stored.receipt().sha256(), root, maxBytes);
                if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unvalidated artifact destination already exists; recovery must preserve its ownership");
                }
                if (!reserved) return false;
                snapshot = failed ? SolverArtifactTree.copyFailureLogs(build.staging(), root, maxBytes, maxEntries)
                        : SolverArtifactTree.copyReserved(build.staging(), root, maxBytes, maxEntries);
                if (failed) requireFailureLayout(snapshot); else requireLayout(root, snapshot);
                var manifest = new SolverBuildArtifacts.OutputManifest(snapshot.payload(), snapshot.sha256(), snapshot.byteSize());
                boolean saved = failed
                        ? SolverBuildArtifacts.validateFailureLogs(connection, build, artifact.engineIdentity(), stored.receipt().sha256(), root, manifest)
                        : SolverBuildArtifacts.validateOutput(connection, build, artifact.engineIdentity(), stored.receipt().sha256(), root, manifest);
                if (!saved) return false;
            }
            return cancelled?SolverBuildArtifacts.recordCancelledLogs(connection,build,artifact.engineIdentity(),stored.receipt().sha256())
                    :failed ? SolverBuildArtifacts.recordFailedOutput(connection, build, artifact.engineIdentity(), stored.receipt().sha256())
                    : SolverBuildArtifacts.publishOutput(connection, artifact.id());
        }
    }

    private static boolean eligiblePair(Connection connection, int pair, boolean failed,boolean cancelled) throws SQLException {
        String predicate=cancelled?"p.status_code=21":failed
                ?"starexec.IsTerminalPairStatus(p.status_code) AND p.status_code NOT IN (7,21)":"p.status_code=7";
        try (var statement = connection.prepareStatement("SELECT " + predicate
                +" FROM starexec.job_pairs p JOIN starexec.jobs j ON j.id=p.job_id"
                +" WHERE p.id=? AND j.buildjob AND j.deleted IS NOT TRUE"
                +(cancelled?"":" AND j.killed IS NOT TRUE"))) {
            statement.setInt(1, pair);
            try (var result = statement.executeQuery()) { return result.next() && result.getBoolean(1); }
        }
    }

    private static void requireFailureLayout(SolverArtifactTree.Snapshot snapshot) throws IOException {
        var entries = JsonParser.parseString(snapshot.payload()).getAsJsonObject().getAsJsonArray("entries");
        for (var value : entries) {
            var entry = value.getAsJsonObject();
            String path = entry.get("path").getAsString();
            if (!(path.equals("logs") && "directory".equals(entry.get("kind").getAsString()) || path.startsWith("logs/"))) {
                throw new IOException("Failed output must contain only saved build logs");
            }
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
        Path executable = root.resolve("solver").toRealPath();
        boolean regularSolverFile = false;
        var entries = JsonParser.parseString(snapshot.payload()).getAsJsonObject().getAsJsonArray("entries");
        for (var value : entries) {
            var entry = value.getAsJsonObject();
            String path = entry.get("path").getAsString();
            if (!path.startsWith("solver/")) continue;
            String kind = entry.get("kind").getAsString();
            if ("link".equals(kind) && !root.resolve(path).toRealPath().startsWith(executable)) {
                throw new IOException("Executable link escapes the solver subtree");
            }
            if ("file".equals(kind)) regularSolverFile = true;
        }
        if (!regularSolverFile) throw new IOException("Build output has no regular solver files");
    }
}
