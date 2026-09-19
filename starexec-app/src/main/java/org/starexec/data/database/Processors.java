package org.starexec.data.database;

import java.io.File;
import java.io.IOException;
import java.lang.NumberFormatException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.starexec.constants.R;
import org.starexec.data.to.Processor;
import org.starexec.data.to.enums.ProcessorType;
import org.starexec.logger.StarLogger;
import org.starexec.util.Util;

/**
 * Handles all database interaction for bench, pre and post processors
 */
public class Processors {

    private static final StarLogger log = StarLogger.getLogger(
        Processors.class
    );

    /**
     * Given a result set where the current row points to a  processor, return the processor
     *
     * @param results
     * @param prefix The table alias given to the processor table in this query. Empty means no prefix.
     * @return The processor if it exists
     * @throws SQLException If the ResultSet does not contain a required processor attribute
     */
    public static Processor resultSetToProcessor(
        ResultSet results,
        String prefix
    ) throws SQLException {
        Integer id = ResultSetUtils.getInt(
            results,
            columnCandidates(prefix, "id")
        );
        if (id == null) {
            return null;
        }
        Processor t = new Processor();
        t.setId(id);
        t.setCommunityId(readRequiredInt(results, prefix, "community"));
        t.setDescription(
            ResultSetUtils.getString(
                results,
                columnCandidates(prefix, "description")
            )
        );
        t.setName(
            ResultSetUtils.getString(results, columnCandidates(prefix, "name"))
        );
        t.setFilePath(
            ResultSetUtils.getString(results, columnCandidates(prefix, "path"))
        );
        Long diskSize = ResultSetUtils.getLong(
            results,
            columnCandidates(prefix, "disk_size")
        );
        t.setDiskSize(diskSize == null ? 0L : diskSize);
        t.setType(
            ProcessorType.valueOf(
                readRequiredInt(results, prefix, "processor_type")
            )
        );
        Integer timeLimit = ResultSetUtils.getInt(
            results,
            columnCandidates(prefix, "time_limit")
        );
        t.setTimeLimit(timeLimit == null ? 0 : timeLimit);
        Integer syntaxId = ResultSetUtils.getInt(
            results,
            columnCandidates(prefix, "syntax_id")
        );
        t.setSyntax(syntaxId == null ? 0 : syntaxId);

        return t;
    }

    private static int readRequiredInt(
        ResultSet results,
        String prefix,
        String column
    ) throws SQLException {
        Integer value = ResultSetUtils.getInt(
            results,
            columnCandidates(prefix, column)
        );
        if (value == null) {
            throw new SQLException(
                "Column " + column + " is null in result set"
            );
        }
        return value;
    }

    private static String[] columnCandidates(String prefix, String column) {
        List<String> names = new ArrayList<>();
        if (!Util.isNullOrEmpty(prefix)) {
            names.add(prefix + "." + column);
            names.add(prefix + "_" + column);
            names.add(prefix + column);
        }
        names.add(column);
        names.add(column.replace('.', '_'));
        return names.toArray(new String[0]);
    }

    private static Processor resultSetToProcessor(ResultSet results)
        throws SQLException {
        results.next();
        return resultSetToProcessor(results, null);
    }

    /**
     * @param results
     * @return a List of Processors from results
     */
    private static List<Processor> resultSetToProcessors(ResultSet results)
        throws SQLException {
        List<Processor> processors = new LinkedList<>();
        while (results.next()) {
            processors.add(resultSetToProcessor(results, null));
        }
        return processors;
    }

    /**
     * Inserts a processor into the database
     *
     * @param processor The processor to add to the database
     * @return The positive integer ID of the new processor if successful, -1 otherwise
     * @author Tyler Jensen
     */
    public static int add(Processor processor) {
        Connection con = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            con = Common.getConnection();
            Common.beginTransaction(con);

            stmt = con.prepareStatement(
                "SELECT AddProcessor(?, ?, ?, ?, ?, ?, ?)"
            );
            stmt.setString(1, processor.getName());
            stmt.setString(2, processor.getDescription());
            stmt.setString(3, processor.getFilePath());
            stmt.setInt(4, processor.getCommunityId());
            stmt.setShort(5, (short) processor.getType().getVal());
            stmt.setLong(
                6,
                FileUtils.sizeOf(new File(processor.getFilePath()))
            );
            stmt.setShort(7, (short) processor.getTimeLimit());

            rs = stmt.executeQuery();
            rs.next();
            int procId = rs.getInt(1);

            Common.endTransaction(con);
            log.debug(
                "the new processor has the ID = " +
                    procId +
                    " and community id = " +
                    processor.getCommunityId()
            );
            return procId;
        } catch (SQLException e) {
            log.error(e.getMessage(), e);
            Common.doRollback(con);
        } finally {
            Common.safeClose(con);
            Common.safeClose(rs);
            Common.safeClose(stmt);
        }
        return -1;
    }

    /**
     * Deletes a given processor from a space
     *
     * @param processorId the id of the processor to delete
     * @return True if the operation was a success, false otherwise
     * @author Todd Elvers
     */
    public static boolean delete(int processorId) {
        final String method = "delete";
        String message;

        if (processorId == R.NO_TYPE_PROC_ID) {
            log.debug(method, "Cannot delete 'no type' processor");
            return false; // the no type processor is required for the system
        }
        if (!processorExists(processorId)) {
            log.debug(method, "Cannot find processor id: " + processorId);
            return true;
        }
        try {
            // Get processor_path of processor via PostgreSQL function that returns TEXT
            Connection con = null;
            PreparedStatement ps = null;
            ResultSet rs = null;
            String path = null;
            try {
                con = Common.getConnection();
                ps = con.prepareStatement("SELECT starexec.DeleteProcessor(?)");
                ps.setInt(1, processorId);
                rs = ps.executeQuery();
                if (rs.next()) {
                    path = rs.getString(1);
                }
            } finally {
                Common.safeClose(rs);
                Common.safeClose(ps);
                Common.safeClose(con);
            }
            message = String.format(
                "Removal of processor [id=%d] was successful.",
                processorId
            );
            log.debug(method, message);

            // The row is already gone, so a file cleanup failure must not turn this into a
            // failed delete: the return value of this method still means "the row was deleted",
            // and only the warning below reports the files left behind.
            if (
                !deleteProcessorFiles(
                    Paths.get(R.getProcessorDir()),
                    path
                )
            ) {
                log.warn(
                    method,
                    String.format(
                        "Processor [id=%d] was removed from the database, but its files at [%s] " +
                            "could not be fully cleaned up.",
                        processorId,
                        path
                    )
                );
            }
            return true;
        } catch (SQLException e) {
            log.debug(
                method,
                String.format(
                    "Removal of processor [id=%d] failed.",
                    processorId
                ),
                e
            );
        }
        return false;
    }

    /**
     * Deletes a processor's directory, refusing anything outside the processor root.
     *
     * <p>processor_path names a directory, not a file, so the tree must be walked instead of
     * calling {@link File#delete()}. Symbolic links are removed as links and never followed, and
     * the parent directory is removed only when it is empty and strictly inside the root.
     *
     * @param processorRoot the root every processor path must be strictly inside
     * @param processorPath the path stored for the processor; null, empty or unparseable means
     *     there is nothing to delete
     * @return true when nothing remains at processorPath, false when the deletion was refused or
     *     failed
     */
    public static boolean deleteProcessorFiles(
        Path processorRoot,
        String processorPath
    ) {
        final String method = "deleteProcessorFiles";

        if (Util.isNullOrEmpty(processorPath)) {
            log.debug(method, "Processor has no path to delete.");
            return true;
        }

        Path target;
        try {
            target = Paths.get(processorPath).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            log.debug(
                method,
                "Processor path [" + processorPath + "] cannot be parsed."
            );
            return true;
        }

        if (processorRoot == null) {
            log.warn(
                method,
                "Refusing to delete [" + target + "]: no processor root was given."
            );
            return false;
        }

        Path root;
        try {
            root = processorRoot.toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            log.warn(
                method,
                "Refusing to delete [" + target + "]: the processor root cannot be parsed."
            );
            return false;
        }

        final boolean targetExists = Files.exists(
            target,
            LinkOption.NOFOLLOW_LINKS
        );
        if (targetExists) {
            // Resolve links in the root and in the components above the target so that a
            // symlinked intermediate directory cannot be used to escape the root. The final
            // component is deliberately not resolved: a symlink at processorPath must be
            // removed as a link.
            try {
                root = root.toRealPath();
                target = target.toRealPath(LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                log.warn(
                    method,
                    "Refusing to delete [" + target + "] because it could not be resolved: " +
                        e.getMessage()
                );
                return false;
            }
        }

        if (target.equals(root) || !target.startsWith(root)) {
            log.warn(
                method,
                "Refusing to delete [" + target + "]: it is not strictly inside [" + root + "]."
            );
            return false;
        }

        if (!targetExists) {
            return true;
        }

        try {
            deletePathWithoutFollowingLinks(target);
            removeParentIfEmpty(target.getParent(), root);
        } catch (IOException e) {
            log.warn(
                method,
                "Failed to delete processor files at [" + target + "]: " + e.getMessage(),
                e
            );
            return false;
        }

        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            log.warn(
                method,
                "Processor files at [" + target + "] still exist after the delete."
            );
            return false;
        }
        return true;
    }

    /**
     * Deletes a file, a symbolic link or a directory tree. Links are deleted as links and
     * symlinked directories are never descended into.
     *
     * @param path the file or directory to delete
     * @throws IOException if any entry cannot be deleted
     */
    private static void deletePathWithoutFollowingLinks(Path path)
        throws IOException {
        if (
            Files.isSymbolicLink(path) ||
            !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        ) {
            Files.deleteIfExists(path);
            return;
        }
        Files.walkFileTree(
            path,
            new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attrs
                ) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(
                    Path directory,
                    IOException failure
                ) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            }
        );
    }

    /**
     * Removes a deleted processor's parent directory when it is empty and strictly inside the
     * processor root. This is what cleans up the timestamp directory a processor was stored in.
     *
     * @param parent the directory that held the deleted processor
     * @param root the processor root; the parent is never removed when it equals the root
     * @throws IOException if the parent cannot be inspected or removed
     */
    private static void removeParentIfEmpty(Path parent, Path root)
        throws IOException {
        if (parent == null || parent.equals(root) || !parent.startsWith(root)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
            if (entries.iterator().hasNext()) {
                return;
            }
        } catch (NoSuchFileException e) {
            // The parent is already gone, so there is nothing left to clean up.
            return;
        }
        try {
            Files.delete(parent);
        } catch (NoSuchFileException e) {
            // Another actor removed the parent after the emptiness check.
        } catch (DirectoryNotEmptyException e) {
            // Another actor put something in the parent after the emptiness check. The
            // processor's own directory is already gone, which is what this call is for,
            // so a parent that now has other contents is nothing left to tidy -- and must
            // not be reported as a failure to clean up the processor.
        }
    }

    /**
     * @param processorId The id of the bench processor to retrieve
     * @return The corresponding processor
     * @author Tyler Jensen
     */
    public static Processor get(int processorId) {
        Connection con = null;
        try {
            con = Common.getConnection();
            return get(processorId, con);
        } catch (Exception e) {
            log.error("get", e.getMessage(), e);
            return null;
        } finally {
            Common.safeClose(con);
        }
    }

    /**
     * @param processorId The id of the bench processor to retrieve
     * @return The corresponding processor
     * @author Tyler Jensen
     */
    public static Processor get(int processorId, Connection con)
        throws NumberFormatException, SQLException {
        if (processorId == 0) {
            return null;
        }
        PreparedStatement ps = null;
        ResultSet results = null;
        try {
            ps = con.prepareStatement(
                "SELECT * FROM starexec.GetProcessorById(?)"
            );
            ps.setInt(1, processorId);
            results = ps.executeQuery();
            return Processors.resultSetToProcessor(results);
        } finally {
            Common.safeClose(results);
            Common.safeClose(ps);
        }
    }

    /**
     * Gets the list of processors
     *
     * @param type The type of processors to filter by
     * @return the list of processors
     * @author Todd Elvers
     */
    public static List<Processor> getAll(ProcessorType type) {
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet results = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT * FROM starexec.GetAllProcessors(?)"
            );
            ps.setShort(1, (short) type.getVal());
            results = ps.executeQuery();
            return Processors.resultSetToProcessors(results);
        } catch (SQLException e) {
            log.error("getAll", e.getMessage(), e);
        } finally {
            Common.safeClose(results);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
        return null;
    }

    /**
     * @return the system NoType benchmark processor, which is applied when the user has no processor.
     */
    public static Processor getNoTypeProcessor() {
        return Processors.get(R.NO_TYPE_PROC_ID);
    }

    /**
     * @param communityId The id of the community to retrieve all processors for
     * @param type The type of processors to get for the community
     * @return A list of all processors of the given type that the community owns
     * @author Tyler Jensen
     */
    public static List<Processor> getByCommunity(
        int communityId,
        ProcessorType type
    ) {
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet results = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT * FROM starexec.GetProcessorsByCommunity(?,?)"
            );
            ps.setInt(1, communityId);
            ps.setShort(2, (short) type.getVal());
            results = ps.executeQuery();
            return Processors.resultSetToProcessors(results);
        } catch (SQLException e) {
            log.error("getByCommunity", e.getMessage(), e);
        } finally {
            Common.safeClose(results);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
        return null;
    }

    /**
     * Gets all processors that a user can see because they share a community
     *
     * @param userId the user to retrieve post processors for
     * @param type The type of processors to get
     * @return A list of all unique processors of the given type that the user can see
     * @author Eric Burns
     */
    public static List<Processor> getByUser(int userId, ProcessorType type) {
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet results = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT * FROM starexec.GetProcessorsByUser(?,?)"
            );
            ps.setInt(1, userId);
            ps.setShort(2, (short) type.getVal());
            results = ps.executeQuery();
            return Processors.resultSetToProcessors(results);
        } catch (SQLException e) {
            log.error("getByUser", e.getMessage(), e);
        } finally {
            Common.safeClose(results);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
        return null;
    }

    /**
     * Updates the description of a processor with the given processor id
     *
     * @param processorId the id of the processor to update
     * @param newDesc the new description to update the processor with
     * @return True if the operation was a success, false otherwise
     * @author Tyler Jensen
     */
    public static boolean updateDescription(int processorId, String newDesc) {
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT starexec.UpdateProcessorDescription(?,?)"
            );
            ps.setInt(1, processorId);
            ps.setString(2, newDesc);
            Common.executeAndDrain(ps);
            return true;
        } catch (SQLException e) {
            if ("P0002".equals(e.getSQLState())) {
                log.warn("Processor " + processorId + " not found");
            } else {
                log.error("updateDescription", e.getMessage(), e);
            }
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
        return false;
    }

    /**
     * Makes sure that a processor with the given id exists.
     *
     * @param processorId The id of a processor.
     * @return true if the the processor exists, otherwise false.
     * @author Albert Giegerich
     */
    public static boolean processorExists(int processorId) {
        Processor processor = Processors.get(processorId);
        return (processor != null);
    }

    /**
     * Updates the file path of a processor with the given processor id
     *
     * @param processorId the id of the processor to update
     * @param newPath the new path to the directory containing this processor
     * @return True if the operation was a success, false otherwise
     * @author Eric Burns
     */
    public static boolean updateFilePath(int processorId, String newPath) {
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT starexec.UpdateProcessorFilePath(?,?)"
            );
            ps.setInt(1, processorId);
            ps.setString(2, newPath);
            ps.execute();
            return true;
        } catch (SQLException e) {
            if ("P0002".equals(e.getSQLState())) {
                log.warn("Processor " + processorId + " not found");
            } else {
                log.error("updateFilePath", e.getMessage(), e);
            }
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
        return false;
    }

    /**
     * Updates the name of a processor with the given processor id
     *
     * @param processorId the id of the processor to update
     * @param newName the new name to update the processor with
     * @return True if the operation was a success, false otherwise
     * @author Tyler Jensen
     */
    public static boolean updateName(int processorId, String newName) {
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT starexec.UpdateProcessorName(?,?)"
            );
            ps.setInt(1, processorId);
            ps.setString(2, newName);
            ps.execute();
            return true;
        } catch (SQLException e) {
            if ("P0002".equals(e.getSQLState())) {
                log.warn("Processor " + processorId + " not found");
            } else {
                log.error("updateName", e.getMessage(), e);
            }
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
        return false;
    }

    /**
     * Whether a time limit can be stored as written.
     *
     * <p>{@code processors.time_limit} is {@code SMALLINT} and
     * {@code starexec.UpdateProcessorTimeLimit} takes a {@code SMALLINT}, so anything above
     * {@link Short#MAX_VALUE} cannot be represented. It used to be narrowed with a cast, which
     * wraps: 40000 became -25536 and the processor was given a negative limit while the request
     * reported success.
     *
     * <p>Zero is storable and is deliberately allowed. The column is nullable, and the row
     * mapper reads a NULL {@code time_limit} as 0 ({@link #resultSetToProcessor}), so a
     * processor can already hold it. Downstream, {@code functions.bash} runs the processor
     * under {@code timeout --signal=SIGKILL $((LIMIT))m}, and a duration of 0 disables the
     * timeout -- so 0 means "no limit", not "no time". Refusing it here would reject a value
     * the system already produces for itself.
     *
     * <p>Negative values are refused: no caller produces one except the truncation this
     * method exists to prevent, and {@code timeout -1m} is not a command.
     *
     * <p>This answers only whether the value can be stored. Whether it is a sensible limit
     * for a human to pick is a separate question, and belongs at the request boundary --
     * {@code secure/edit/processor.jsp} offers 1..60 minutes.
     *
     * @param timeLimit the requested limit
     * @return true if the column can hold this value
     */
    public static boolean isStorableTimeLimit(int timeLimit) {
        return timeLimit >= 0 && timeLimit <= Short.MAX_VALUE;
    }

    public static boolean updateTimeLimit(int processorId, int timeLimit) {
        // Before the connection: a value the column cannot hold is not a database failure, and
        // must not be silently narrowed into one that fits.
        if (!isStorableTimeLimit(timeLimit)) {
            log.warn(
                "updateTimeLimit",
                String.format(
                    "Refusing to set processor [id=%d] time limit to [%d]: the column holds " +
                        "0..%d.",
                    processorId,
                    timeLimit,
                    (int) Short.MAX_VALUE
                )
            );
            return false;
        }
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT starexec.UpdateProcessorTimeLimit(?,?)"
            );
            ps.setInt(1, processorId);
            ps.setShort(2, (short) timeLimit);
            ps.execute();
            return true;
        } catch (SQLException e) {
            if ("P0002".equals(e.getSQLState())) {
                log.warn("Processor " + processorId + " not found");
            } else {
                log.error("updateTimeLimit", e.getMessage(), e);
            }
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
        return false;
    }

    public static void updateSyntax(int processorId, int syntaxId)
        throws SQLException {
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT starexec.UpdateProcessorSyntax(?,?)"
            );
            ps.setInt(1, processorId);
            ps.setInt(2, syntaxId);
            ps.execute();
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }
}
