package org.starexec.jobs;

import org.starexec.backend.BuildExecutionContext;
import org.starexec.data.database.Common;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

/** Durable staging allocation for a dispatcher that exclusively owns the pair claim. */
public final class SolverBuildStaging {
    private static final Set<PosixFilePermission> PRIVATE = PosixFilePermissions.fromString("rwx------");

    private SolverBuildStaging() { }

    /**
     * The parent must be application-only and excluded from runner mounts. A recorded
     * directory is retained on failure so a retry never guesses ownership or deletes output.
     */
    public static BuildExecutionContext prepare(Connection con, int pairId, int attemptNo, Path parent)
            throws SQLException, IOException {
        return prepare(con, pairId, attemptNo, parent, null);
    }

    /** Books a bounded private staging allowance before creating its directory. */
    public static BuildExecutionContext prepare(Connection con, int pairId, int attemptNo, Path parent, long maxBytes)
            throws SQLException, IOException {
        if (maxBytes < 0) throw new IllegalArgumentException("Invalid staging byte budget");
        return prepare(con, pairId, attemptNo, parent, Long.valueOf(maxBytes));
    }

    private static BuildExecutionContext prepare(Connection con, int pairId, int attemptNo, Path parent, Long maxBytes)
            throws SQLException, IOException {
        if (!con.getAutoCommit()) throw new SQLException("Staging registration must commit before execution");
        if (parent == null || !parent.isAbsolute() || parent.getParent() == null || !parent.equals(parent.normalize())) {
            throw new IOException("Staging parent must be an absolute normalized non-root directory");
        }
        try (DescriptorDirectory ignored = DescriptorDirectory.open(parent)) {
            requirePrivateDirectory(parent);
            var artifact = SolverBuildArtifacts.find(con, pairId, attemptNo)
                    .orElseThrow(() -> new SQLException("No registered artifact for this build attempt"));
            if (artifact.engineIdentity() != null || artifact.stoppedAt() != null
                    || !(artifact.state().equals("PLANNED") || artifact.state().equals("STAGED"))) {
                throw new IOException("Build execution already bound or artifact is no longer dispatchable");
            }
            boolean registered = artifact.stagingPath() != null;
            UUID token = registered ? artifact.executionToken() : UUID.randomUUID();
            Path staging = registered ? Path.of(artifact.stagingPath()) : parent.resolve("build-" + token);
            if (!parent.equals(staging.getParent())) throw new IOException("Registered staging has a different parent");
            BuildExecutionContext context = new BuildExecutionContext(artifact.id(), pairId, attemptNo,
                    token, Path.of(artifact.sourcePath()), staging);
            // This statement commits before any filesystem allocation or backend execution.
            Common.runTransactional(con, transaction -> {
                if (!SolverBuildArtifacts.prepare(transaction, pairId, attemptNo, token, staging)) {
                    throw new SQLException("Build ownership changed before staging registration");
                }
                if (maxBytes != null && !SolverBuildArtifacts.reserveStorageBytes(transaction, context, "STAGING", staging, maxBytes)) {
                    throw new SQLException("Build staging byte budget was refused");
                }
                return true;
            });
            try {
                Files.createDirectory(staging, PosixFilePermissions.asFileAttribute(PRIVATE));
            } catch (FileAlreadyExistsException e) {
                if (!registered) throw new IOException("New staging reservation already exists", e);
            }
            try (DescriptorDirectory checked = DescriptorDirectory.open(staging)) {
                requirePrivateDirectory(staging);
            }
            return context;
        }
    }

    private static void requirePrivateDirectory(Path path) throws IOException {
        if (!Files.getPosixFilePermissions(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).equals(PRIVATE)) {
            throw new IOException("Build staging requires owner-only directory access");
        }
    }
}
