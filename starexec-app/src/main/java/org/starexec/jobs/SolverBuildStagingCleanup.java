package org.starexec.jobs;

import org.starexec.backend.BuildExecutionContext;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.data.database.Common;
import org.starexec.data.database.ExecutionStorageLeases;
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
import java.util.Set;
import java.util.UUID;

/** Reclaims only a stopped execution's staging; published output and its charge remain retained. */
public final class SolverBuildStagingCleanup {
    private SolverBuildStagingCleanup() { }
    private enum Claim { REFUSED, COMPLETE, COMMITTED }
    private record Claimed(Claim state, String stagingIdentity) { }

    /**
     * Resource ancestors and immutable output must remain protected from untrusted writes.
     * A claim commits before removal; a failed copy, force or settlement preserves its
     * identity for retry. Completed retries never adopt or remove reappeared contents.
     */
    public static boolean purge(Connection connection, BuildExecutionContext build,
                                 DedicatedStorageResource resource, int maxEntries) throws SQLException {
        if (maxEntries < 1) throw new IllegalArgumentException("Staging cleanup requires a positive entry limit");
        if (!connection.getAutoCommit()) throw new SQLException("Staging cleanup cannot use a foreign transaction");
        Path root = resource.root();
        if (root == null || !root.isAbsolute() || root.getParent() == null || !root.equals(root.normalize())
                || !root.equals(build.staging().getParent())
                || !build.staging().getFileName().toString().equals("build-" + build.token())) {
            throw new IllegalArgumentException("Staging cleanup requires the exact registered resource child");
        }
        var initial = ExecutionStorageLeases.findStagingCleanup(connection,build,resource).orElse(null);
        if (initial == null) return false;
        UUID token = initial.token() == null ? UUID.randomUUID() : initial.token();
        Claimed claim = Common.runTransactional(connection, tx -> {
            resource.requireUnchanged();
            var current = ExecutionStorageLeases.findStagingCleanup(tx,build,resource).orElse(null);
            if (current == null) return new Claimed(Claim.REFUSED,null);
            if (current.releasedAt() != null) {
                requireAbsent(build.staging());
                return new Claimed(Claim.COMPLETE,current.stagingIdentity());
            }
            String identity;
            try (DescriptorDirectory parent = DescriptorDirectory.open(root)) {
                identity = current.token() == null ? stagingIdentity(build.staging()) : current.stagingIdentity();
            }
            return new Claimed(ExecutionStorageLeases.claimStagingCleanup(tx,build,resource,token,identity)
                    ? Claim.COMMITTED : Claim.REFUSED,identity);
        });
        if (claim.state() != Claim.COMMITTED) return claim.state() == Claim.COMPLETE;
        return Common.runTransactional(connection, tx -> {
            resource.requireUnchanged();
            if (!ExecutionStorageLeases.claimStagingCleanup(tx,build,resource,token,claim.stagingIdentity())) return false;
            var current = ExecutionStorageLeases.findStagingCleanup(tx,build,resource).orElseThrow();
            if (current.releasedAt() != null) { requireAbsent(build.staging()); return true; }
            verifyOutput(tx,build,maxEntries);
            try (DescriptorDirectory parent = DescriptorDirectory.open(root)) {
                String observed = stagingIdentity(build.staging());
                if (!observed.equals("ABSENT") && !observed.equals(claim.stagingIdentity())) {
                    throw new IOException("Registered staging identity changed after cleanup claim");
                }
                parent.deleteTree(build.staging().getFileName().toString(),maxEntries);
                parent.force();
                requireAbsent(build.staging());
                try (var contents = Files.newDirectoryStream(root)) {
                    if (contents.iterator().hasNext()) throw new IOException("Execution resource retains unregistered contents");
                }
                resource.requireUnchanged();
                if (!ExecutionStorageLeases.finishStagingCleanup(tx,build,resource,token,claim.stagingIdentity())) {
                    throw new SQLException("Staging cleanup identity changed before settlement");
                }
            }
            return true;
        });
    }

    private static String stagingIdentity(Path path) throws IOException {
        try {
            var attributes = Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.fileKey() == null
                    || attributes.creationTime().toMillis() <= 0) {
                throw new IOException("Staging cleanup requires an identifiable physical directory");
            }
            return attributes.fileKey() + ":" + attributes.creationTime();
        } catch (NoSuchFileException absent) { return "ABSENT"; }
    }

    private static void verifyOutput(Connection connection, BuildExecutionContext build, int maxEntries)
            throws SQLException, IOException {
        var artifact = SolverBuildArtifacts.find(connection,build.pairId(),build.attemptNo()).orElseThrow();
        if (artifact.id() != build.artifactId() || !build.token().equals(artifact.executionToken())) {
            throw new IOException("Staging cleanup lost its conserved artifact identity");
        }
        if (artifact.state().equals("DELETED")) return;
        var receipt = SolverBuildArtifacts.findStopReceipt(connection,build.pairId(),build.attemptNo()).orElseThrow();
        var disposition = receipt.receipt().facts().get("disposition");
        if (disposition != null && artifact.state().equals("REJECTED")
                && Set.of("cancelled","never_started").contains(disposition.getAsString())) return;
        var manifest = SolverBuildArtifacts.findOutputManifest(connection,artifact.id())
                .orElseThrow(() -> new IOException("Build output has not been conserved before staging cleanup"));
        SolverBuildPublication.verifyConservedOutput(Path.of(artifact.storageRoot()),manifest,
                artifact.state().equals("REJECTED"),maxEntries);
    }

    private static void requireAbsent(Path path) throws IOException {
        try {
            Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            throw new IOException("Registered staging has reappeared after cleanup");
        } catch (NoSuchFileException absent) { }
    }
}
