package org.starexec.data.database;

import org.apache.commons.io.FileUtils;
import org.starexec.constants.R;
import org.starexec.data.to.Processor;
import org.starexec.data.to.enums.ProcessorType;
import org.starexec.logger.StarLogger;
import org.starexec.util.Util;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.lang.NumberFormatException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Handles all database interaction for bench, pre and post processors
 */
public class Processors {
	private static final StarLogger log = StarLogger.getLogger(Processors.class);

	/**
	 * Given a result set where the current row points to a  processor, return the processor
	 *
	 * @param results
	 * @param prefix The table alias given to the processor table in this query. Empty means no prefix.
	 * @return The processor if it exists
	 * @throws SQLException If the ResultSet does not contain a required processor attribute
	 */
	public static Processor resultSetToProcessor(ResultSet results, String prefix) throws SQLException {
		Processor t = new Processor();
		t.setId(readRequiredInt(results, prefix, "id"));
		t.setCommunityId(readRequiredInt(results, prefix, "community"));
		t.setDescription(ResultSetUtils.getString(results, columnCandidates(prefix, "description")));
		t.setName(ResultSetUtils.getString(results, columnCandidates(prefix, "name")));
		t.setFilePath(ResultSetUtils.getString(results, columnCandidates(prefix, "path")));
		Long diskSize = ResultSetUtils.getLong(results, columnCandidates(prefix, "disk_size"));
		t.setDiskSize(diskSize == null ? 0L : diskSize);
		t.setType(ProcessorType.valueOf(readRequiredInt(results, prefix, "processor_type")));
		Integer timeLimit = ResultSetUtils.getInt(results, columnCandidates(prefix, "time_limit"));
		t.setTimeLimit(timeLimit == null ? 0 : timeLimit);
		Integer syntaxId = ResultSetUtils.getInt(results, columnCandidates(prefix, "syntax_id"));
		t.setSyntax(syntaxId == null ? 0 : syntaxId);

		return t;
	}

	private static int readRequiredInt(ResultSet results, String prefix, String column) throws SQLException {
		Integer value = ResultSetUtils.getInt(results, columnCandidates(prefix, column));
		if (value == null) {
			throw new SQLException("Column " + column + " is null in result set");
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

	private static Processor resultSetToProcessor(ResultSet results) throws SQLException {
		results.next();
		return resultSetToProcessor(results, null);
	}

	/**
	 * @param results
	 * @return a List of Processors from results
	 */
	private static List<Processor> resultSetToProcessors(ResultSet results) throws SQLException {
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
			
			stmt = con.prepareStatement("SELECT AddProcessor(?, ?, ?, ?, ?, ?, ?)");
			stmt.setString(1, processor.getName());
			stmt.setString(2, processor.getDescription());
			stmt.setString(3, processor.getFilePath());
			stmt.setInt(4, processor.getCommunityId());
			stmt.setShort(5, (short) processor.getType().getVal());
			stmt.setLong(6, FileUtils.sizeOf(new File(processor.getFilePath())));
			stmt.setShort(7, (short) processor.getTimeLimit());
			
			rs = stmt.executeQuery();
			rs.next();
			int procId = rs.getInt(1);
			
			Common.endTransaction(con);
			log.debug("the new processor has the ID = " + procId + " and community id = " + processor.getCommunityId());
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
			File processorFile = null;
			try {
				con = Common.getConnection();
				ps = con.prepareStatement("SELECT starexec.DeleteProcessor(?)");
				ps.setInt(1, processorId);
				rs = ps.executeQuery();
				String path = null;
				if (rs.next()) {
					path = rs.getString(1);
				}
				processorFile = new File(path == null ? "" : path);
			} finally {
				Common.safeClose(rs);
				Common.safeClose(ps);
				Common.safeClose(con);
			}
			message = String.format("Removal of processor [id=%d] was successful.", processorId);
			log.debug(method, message);

			// Try and delete file referenced by processor_path and its parent directory
			if (processorFile.exists()) {
				if (processorFile.delete()) {
					message =
							String.format("File [%s] was deleted at [%s] because it was not inter referenced " +
									              "anywhere.", processorFile.getName(), processorFile.getAbsolutePath()
							);
					log.debug(method, message);
				}
				if (processorFile.getParentFile() != null) {
					if (processorFile.getParentFile().delete()) {
						message = String.format("Directory [%s] was deleted because it was empty.",
						                        processorFile.getParentFile().getAbsolutePath()
						);
						log.debug(method, message);
					}
				}
			}
			return true;
		} catch (SQLException e) {
			log.debug(method, String.format("Removal of processor [id=%d] failed.", processorId), e);
		}
		return false;
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
	public static Processor get(int processorId, Connection con) throws NumberFormatException, SQLException {
		if (processorId == 0) {
			return null;
		}
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			ps = con.prepareStatement("SELECT * FROM starexec.GetProcessorById(?)");
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
			ps = con.prepareStatement("SELECT * FROM starexec.GetAllProcessors(?)");
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
	public static List<Processor> getByCommunity(int communityId, ProcessorType type) {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetProcessorsByCommunity(?,?)");
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
			ps = con.prepareStatement("SELECT * FROM starexec.GetProcessorsByUser(?,?)");
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
		ResultSet rs = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.UpdateProcessorDescription(?,?)");
			ps.setInt(1, processorId);
			ps.setString(2, newDesc);
			boolean hasResultSet = ps.execute();
			if (hasResultSet) {
				rs = ps.getResultSet();
				// Consume the result set to avoid cursor leaks
				while (rs.next()) {
					// Do nothing, just consume
				}
			}
			return true;
		} catch (SQLException e) {
			if ("P0002".equals(e.getSQLState())) {
				log.warn("Processor " + processorId + " not found");
			} else {
				log.error("updateDescription", e.getMessage(), e);
			}
		} finally {
			Common.safeClose(rs);
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
			ps = con.prepareStatement("SELECT starexec.UpdateProcessorFilePath(?,?)");
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
			ps = con.prepareStatement("SELECT starexec.UpdateProcessorName(?,?)");
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

	public static boolean updateTimeLimit(int processorId, int timeLimit) {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.UpdateProcessorTimeLimit(?,?)");
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

	public static void updateSyntax(int processorId, int syntaxId) throws SQLException {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.UpdateProcessorSyntax(?,?)");
			ps.setInt(1, processorId);
			ps.setInt(2, syntaxId);
			ps.execute();
		} finally {
			Common.safeClose(ps);
			Common.safeClose(con);
		}
	}
}
