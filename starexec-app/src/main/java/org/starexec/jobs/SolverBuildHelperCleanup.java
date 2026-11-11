package org.starexec.jobs;

import org.starexec.constants.R;
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
import java.util.UUID;

/** Shared helper input cleanup after every registered reader has stopped. */
public final class SolverBuildHelperCleanup {
    private SolverBuildHelperCleanup() { }

    /** The trusted benchmark root and its ancestors must be excluded from untrusted writes. */
    public static boolean purge(Connection connection, int benchmark, Path benchmarkRoot) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("Helper cleanup cannot use a foreign transaction");
        var initial = SolverBuildArtifacts.findHelper(connection, benchmark).orElse(null);
        if (initial == null) return false;
        UUID token = initial.token() == null ? UUID.randomUUID() : initial.token();
        if (!Common.runTransactional(connection, tx -> SolverBuildArtifacts.claimHelperCleanup(tx, benchmark, token))) return false;
        return Common.runTransactional(connection, tx -> {
            if (!SolverBuildArtifacts.claimHelperCleanup(tx, benchmark, token)) return false;
            var helper = SolverBuildArtifacts.findHelper(tx, benchmark).orElseThrow();
            Path file = confined(benchmarkRoot, helper.path());
            if (helper.finishedAt() != null) { requireAbsent(file); return true; }
            try (DescriptorDirectory parent = DescriptorDirectory.open(file.getParent())) {
                parent.deleteFile(file.getFileName().toString());
                parent.force();
            }
            requireAbsent(file);
            if (!SolverBuildArtifacts.finishHelperCleanup(tx, benchmark, token)) {
                throw new SQLException("Build helper identity changed before settlement");
            }
            return true;
        });
    }

    private static Path confined(Path root, String value) throws IOException {
        if (root == null || !root.isAbsolute() || root.getParent() == null || !root.equals(root.normalize()) || value == null) {
            throw new IOException("Helper cleanup requires an explicit normalized benchmark root");
        }
        Path file = Path.of(value);
        if (!file.isAbsolute() || !file.equals(file.normalize()) || !file.startsWith(root) || file.equals(root)
                || !file.getFileName().toString().equals(R.SOLVER_BUILD_SCRIPT)) {
            throw new IOException("Registered helper input is outside its authorized root");
        }
        try (DescriptorDirectory ignored = DescriptorDirectory.open(root)) { }
        return file;
    }

    private static void requireAbsent(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            throw new IOException("Registered helper input remains present");
        } catch (NoSuchFileException absent) { }
    }
}
