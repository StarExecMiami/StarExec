package org.starexec.data.database;

import org.starexec.backend.BuildExecutionContext;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/** Durable resource exclusivity; capacity and physical cleanup remain independently observed. */
public final class ExecutionStorageLeases {
    private ExecutionStorageLeases() { }

    public static boolean register(Connection connection, DedicatedStorageResource resource) throws SQLException {
        return transact(connection, tx -> {
            resource.requireUnchanged();
            return call(tx, "SELECT starexec.register_execution_storage_resource(?,?,?,?,?)",
                    resource.filesystemKey(), resource.root().toString(), resource.identity(),
                    resource.capacityBytes(), resource.inodeLimit());
        });
    }

    /** Commits an exclusive lease; closing a connection never returns that lease to the pool. */
    public static boolean reserve(Connection connection, BuildExecutionContext build,
                                  DedicatedStorageResource resource) throws SQLException {
        return transact(connection, tx -> {
            resource.requireUnchanged();
            return call(tx, "SELECT starexec.reserve_solver_build_storage_lease(?,?,?,?,?,?,?,?)",
                    build.artifactId(), build.pairId(), build.attemptNo(), build.token(),
                    build.source().toString(), build.staging().toString(), resource.filesystemKey(), resource.identity());
        });
    }

    /** Capacity is reobserved before committing both the lease and exact engine admission. */
    public static boolean admit(Connection connection, BuildExecutionContext build,
                                DedicatedStorageResource resource, String engine) throws SQLException {
        return transact(connection, tx -> {
            resource.requireUnchanged();
            return call(tx, "SELECT starexec.admit_leased_solver_build_execution(?,?,?,?,?,?,?,?,?)",
                    build.artifactId(), build.pairId(), build.attemptNo(), build.token(),
                    build.source().toString(), build.staging().toString(), resource.filesystemKey(), resource.identity(), engine);
        });
    }

    /** No resource is released until registered artifact cleanup and all accounting have completed. */
    public static boolean release(Connection connection, BuildExecutionContext build,
                                  DedicatedStorageResource resource) throws SQLException {
        return transact(connection, tx -> {
            resource.requireUnchanged();
            Path root = resource.root();
            try (DescriptorDirectory directory = DescriptorDirectory.open(root)) {
                try (var entries = Files.newDirectoryStream(root)) {
                    if (entries.iterator().hasNext()) throw new IOException("Execution storage is not empty after cleanup");
                }
                directory.force();
                resource.requireUnchanged();
                return call(tx, "SELECT starexec.release_solver_build_storage_lease(?,?,?,?)",
                        build.artifactId(), build.token(), resource.filesystemKey(), resource.identity());
            }
        });
    }

    private static boolean transact(Connection connection, Common.TransactionalWork<Boolean> work) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("Storage leases cannot use a foreign transaction");
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
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) statement.setObject(i + 1, arguments[i]);
            try (var result = statement.executeQuery()) { return result.next() && result.getBoolean(1); }
        }
    }

    private static final class Refused extends SQLException {
        private static final long serialVersionUID = 1L;
        private Refused() { super("Execution storage lease was refused"); }
    }
}
