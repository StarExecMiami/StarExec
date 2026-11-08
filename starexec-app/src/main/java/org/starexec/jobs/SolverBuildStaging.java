package org.starexec.jobs;

import org.starexec.backend.BuildExecutionContext;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.data.database.Common;
import org.starexec.data.database.ExecutionStorageLeases;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.List;
import java.util.UUID;

/** Durable staging allocation for a dispatcher that exclusively owns the pair claim. */
public final class SolverBuildStaging {
    private static final Set<PosixFilePermission> PRIVATE = PosixFilePermissions.fromString("rwx------");

    private SolverBuildStaging() { }

    /** Selects an existing, freshly verified registration; a retry must preserve its exact committed lease. */
    public static BuildExecutionContext prepareRegistered(Connection connection, int pairId, List<Path> protectedRoots)
            throws SQLException, IOException {
        if (!connection.getAutoCommit()) throw new SQLException("Build selection requires a standalone connection");
        var artifact = SolverBuildArtifacts.findCurrent(connection, pairId)
                .orElseThrow(() -> new SQLException("Current build artifact is unavailable"));
        if (artifact.engineIdentity() != null || artifact.stoppedAt() != null
                || !(artifact.state().equals("PLANNED") || artifact.state().equals("STAGED"))) {
            throw new IOException("Registered build requires recovery rather than another launch");
        }
        if (artifact.executionToken() != null) {
            if (artifact.stagingPath() == null) throw new IOException("Prepared build staging is unavailable");
            var build = new BuildExecutionContext(artifact.id(),pairId,artifact.attemptNo(),artifact.executionToken(),
                    Path.of(artifact.sourcePath()),Path.of(artifact.stagingPath()));
            var registration = ExecutionStorageLeases.registeredResource(connection,build)
                    .orElseThrow(() -> new IOException("Prepared build has no conserved dedicated resource"));
            var resource = registration.inspect(protectedRoots);
            return prepareLeased(connection,pairId,artifact.attemptNo(),resource,registeredAllowance(connection,build));
        }
        if (artifact.stagingPath() != null || artifact.storageRoot() != null || artifact.solverPath() != null
                || !"PLANNED".equals(artifact.state())) {
            throw new IOException("Build history cannot fall back to a new unbound allocation");
        }
        IOException firstObservationFailure = null;
        for (var registration : ExecutionStorageLeases.availableResources(connection,artifact.ownerId())) {
            final DedicatedStorageResource resource;
            try {
                resource = registration.inspect(protectedRoots);
            } catch (IOException unavailable) {
                if (firstObservationFailure == null) firstObservationFailure = unavailable;
                continue;
            }
            // Never try another resource after preparation: a failed response may follow a committed reservation.
            return prepareLeased(connection,pairId,artifact.attemptNo(),resource,resource.capacityBytes());
        }
        throw new IOException("No verified dedicated execution resource is available",firstObservationFailure);
    }

    private static long registeredAllowance(Connection connection, BuildExecutionContext build) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT c.limit_bytes FROM starexec.solver_build_storage_charges c"
                + " JOIN starexec.solver_build_artifacts a ON a.id=c.artifact_id"
                + " WHERE a.id=? AND a.execution_token=? AND c.storage_kind='STAGING'"
                + " AND c.owner_id=a.origin_owner_id AND c.path=? AND c.released_at IS NULL"
                + " AND c.limit_bytes>0 AND c.charged_bytes=c.limit_bytes")) {
            statement.setLong(1,build.artifactId());statement.setObject(2,build.token());
            statement.setString(3,build.staging().toString());
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Prepared build allowance is unavailable");
                return rows.getLong(1);
            }
        }
    }

    /** Commits the verified resource, allowance and exclusive lease before directory creation. */
    public static BuildExecutionContext prepareLeased(Connection con, int pairId, int attemptNo,
                                                 DedicatedStorageResource resource, long maxBytes)
            throws SQLException, IOException {
        if (maxBytes <= 0) throw new IllegalArgumentException("Leased staging requires a positive byte budget");
        java.util.Objects.requireNonNull(resource, "resource");
        return prepare(con, pairId, attemptNo, resource.root(), Long.valueOf(maxBytes), resource);
    }

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
        return prepare(con, pairId, attemptNo, parent, maxBytes, null);
    }

    private static BuildExecutionContext prepare(Connection con, int pairId, int attemptNo, Path parent,
                                                  Long maxBytes, DedicatedStorageResource resource)
            throws SQLException, IOException {
        if (!con.getAutoCommit()) throw new SQLException("Staging registration must commit before execution");
        if (parent == null || !parent.isAbsolute() || parent.getParent() == null || !parent.equals(parent.normalize())) {
            throw new IOException("Staging parent must be an absolute normalized non-root directory");
        }
        try (DescriptorDirectory parentDirectory = DescriptorDirectory.open(parent)) {
            requirePrivateDirectory(parent);
            if (resource != null) resource.requireUnchanged();
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
            if (resource != null) {
                var prior = ExecutionStorageLeases.findStagingCleanup(con, context, resource).orElse(null);
                if (prior != null && (prior.releasedAt() != null || prior.token() != null)) {
                    throw new IOException("Registered staging is no longer available for preparation");
                }
                if (prior == null && present(staging)) {
                    throw new IOException("Preexisting staging without a conserved lease cannot be adopted");
                }
                try (var contents = Files.newDirectoryStream(parent)) {
                    for (Path entry : contents) {
                        if (!registered || !entry.equals(staging)) {
                            throw new IOException("Execution resource contains unregistered contents");
                        }
                    }
                }
            }
            // This statement commits before any filesystem allocation or backend execution.
            Common.runTransactional(con, transaction -> {
                if (resource != null && !ExecutionStorageLeases.registerInTransaction(transaction, resource)) {
                    throw new SQLException("Build resource registration was refused");
                }
                if (!SolverBuildArtifacts.prepare(transaction, pairId, attemptNo, token, staging)) {
                    throw new SQLException("Build ownership changed before staging registration");
                }
                if (maxBytes != null && !SolverBuildArtifacts.reserveStorageBytes(transaction, context, "STAGING", staging, maxBytes)) {
                    throw new SQLException("Build staging byte budget was refused");
                }
                if (resource != null && !ExecutionStorageLeases.reserveInTransaction(transaction, context, resource)) {
                    throw new SQLException("Build storage lease was refused");
                }
                return true;
            });
            if (resource != null) resource.requireUnchanged();
            try {
                Files.createDirectory(staging, PosixFilePermissions.asFileAttribute(PRIVATE));
            } catch (FileAlreadyExistsException e) {
                if (!registered) throw new IOException("New staging reservation already exists", e);
            }
            try (DescriptorDirectory checked = DescriptorDirectory.open(staging)) {
                requirePrivateDirectory(staging);
            }
            if (resource != null) {
                parentDirectory.force();
                resource.requireUnchanged();
            }
            return context;
        }
    }

    private static void requirePrivateDirectory(Path path) throws IOException {
        if (!Files.getPosixFilePermissions(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).equals(PRIVATE)) {
            throw new IOException("Build staging requires owner-only directory access");
        }
    }

    private static boolean present(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException absent) { return false; }
    }
}
