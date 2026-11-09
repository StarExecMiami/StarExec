package org.starexec.data.database;

import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Durable ordinary-job storage ownership; a lease is never engine launch permission. */
public final class JobStorageLeases {
    private JobStorageLeases() { }

    public record Lease(UUID token, int pairId, int attemptNo, int ownerId, Integer priorExecutionId,
                        String resourceKey, String resourceIdentity, Path root, long limitBytes, long inodeLimit,
                        Instant createdAt, Instant useStartedAt, Instant releasedAt) {
        public Path staging() { return root.resolve("pair-" + token); }
    }

    /** Historical locator only; its presence does not establish current ownership or physical state. */
    public static Optional<Lease> find(Connection connection, UUID token) throws SQLException {
        return find(connection, token, false);
    }

    private static Optional<Lease> find(Connection connection, UUID token, boolean lock) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT c.execution_token,c.origin_pair_id,c.attempt_no,c.origin_owner_id,c.prior_execution_id,"
                + "r.resource_key,r.observed_identity,r.root_path,c.limit_bytes,r.inode_limit,"
                + "c.created_at,c.use_started_at,c.released_at FROM starexec.execution_storage_claims c"
                + " JOIN starexec.execution_storage_resources r ON r.resource_key=c.resource_key"
                + " WHERE c.execution_token=? AND c.lease_kind='JOB'" + (lock ? " FOR UPDATE OF c NOWAIT" : ""))) {
            query.setObject(1, token);
            try (var row = query.executeQuery()) {
                if (!row.next()) return Optional.empty();
                var use = row.getTimestamp(12);
                var release = row.getTimestamp(13);
                return Optional.of(new Lease(row.getObject(1, UUID.class), row.getInt(2), row.getInt(3), row.getInt(4),
                        row.getObject(5, Integer.class), row.getString(6), row.getString(7), Path.of(row.getString(8)),
                        row.getLong(9), row.getLong(10), row.getTimestamp(11).toInstant(),
                        use == null ? null : use.toInstant(), release == null ? null : release.toInstant()));
            }
        }
    }

    /** Books the complete resource before the first directory write; retries require the same token. */
    public static boolean reserve(Connection connection, int pairId, int attemptNo, UUID token,
                                  DedicatedStorageResource resource) throws SQLException {
        return transact(connection, tx -> {
            resource.requireUnchanged();
            if (!call(tx, "SELECT starexec.reserve_job_storage(?,?,?,?,?)", pairId, attemptNo, token,
                    resource.filesystemKey(), resource.identity())) return false;
            var lease = find(tx, token, true).orElse(null);
            if (lease == null || lease.pairId() != pairId || lease.attemptNo() != attemptNo
                    || !matches(lease, resource)) return false;
            requireEmpty(resource);
            return true;
        });
    }

    /** Consumes a one-way storage-use marker before any write, without granting engine admission. */
    public static boolean beginUse(Connection connection, UUID token, DedicatedStorageResource resource) throws SQLException {
        return transact(connection, tx -> {
            var lease = find(tx, token, true).orElse(null);
            if (lease == null || !matches(lease, resource) || lease.releasedAt() != null || lease.useStartedAt() != null) return false;
            requireEmpty(resource);
            return call(tx, "SELECT starexec.begin_job_storage_use(?,?,?)", token, resource.filesystemKey(), resource.identity());
        });
    }

    /** Reclaims only a never-used empty resource; uncertain or started work stays charged. */
    public static boolean releaseUnstarted(Connection connection, UUID token, DedicatedStorageResource resource) throws SQLException {
        return transact(connection, tx -> {
            var lease = find(tx, token, true).orElse(null);
            if (lease == null || !matches(lease, resource)) return false;
            // A settled retry acknowledges history without inspecting or affecting its next owner.
            if (lease.releasedAt() != null) return true;
            if (lease.useStartedAt() != null) return false;
            requireEmpty(resource);
            return call(tx, "SELECT starexec.release_unstarted_job_storage(?,?,?)", token,
                    resource.filesystemKey(), resource.identity());
        });
    }

    private static boolean matches(Lease lease, DedicatedStorageResource resource) {
        return lease.resourceKey().equals(resource.filesystemKey()) && lease.resourceIdentity().equals(resource.identity())
                && lease.root().equals(resource.root()) && lease.limitBytes() == resource.capacityBytes()
                && lease.inodeLimit() == resource.inodeLimit();
    }

    private static void requireEmpty(DedicatedStorageResource resource) throws IOException {
        resource.requireUnchanged();
        try (var directory = DescriptorDirectory.open(resource.root())) {
            try (var entries = Files.newDirectoryStream(resource.root())) {
                if (entries.iterator().hasNext()) throw new IOException("Unused execution storage is not empty");
            }
            directory.force();
            resource.requireUnchanged();
        }
    }

    private static boolean transact(Connection connection, Common.TransactionalWork<Boolean> work) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("Job storage cannot use a foreign transaction");
        try {
            return Common.runTransactional(connection, tx -> {
                if (!work.run(tx)) throw new Refused();
                return true;
            });
        } catch (Refused refused) {
            if (refused.getSuppressed().length != 0) throw refused;
            return false;
        }
    }

    private static boolean call(Connection connection, String sql, Object... arguments) throws SQLException {
        try (var query = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) query.setObject(i + 1, arguments[i]);
            try (var result = query.executeQuery()) { return result.next() && result.getBoolean(1); }
        }
    }

    private static final class Refused extends SQLException {
        private static final long serialVersionUID = 1L;
        private Refused() { super("Job storage ownership was refused"); }
    }
}
