package org.starexec.data.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Responsible for issuing queries and updates to the pairs_rerun table.
 */
public class PairsRerun {
	/**
	 * Checks whether or not a pair has been rerun.
	 *
	 * @param pairId the id of the pair to check.
	 * @return true if the pair has been rerun, false otherwise.
	 * @throws SQLException on database error.
	 */
	public static boolean hasPairBeenRerun(int pairId) throws SQLException {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.HasPairBeenRerun(?)");
			ps.setInt(1, pairId);
			rs = ps.executeQuery();
			return rs.next();
		} finally {
			Common.safeClose(rs);
			Common.safeClose(ps);
			Common.safeClose(con);
		}
	}

	/**
	 * Checks whether or not a pair has been rerun.
	 *
	 * @param con an open database connection to use for the procedure.
	 * @param pairId the id of the pair to check.
	 * @return true if the pair has been rerun, false otherwise.
	 * @throws SQLException on database error.
	 */
	public static boolean pairHasBeenRerun(Connection con, int pairId) throws SQLException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = con.prepareStatement("SELECT * FROM starexec.HasPairBeenRerun(?)");
			ps.setInt(1, pairId);
			rs = ps.executeQuery();
			return rs.next();
		} finally {
			Common.safeClose(rs);
			Common.safeClose(ps);
		}
	}

	/**
	 * Marks a pair as having been rerun in the pairs_rerun table.
	 *
	 * @param pairId the id of the pair to mark as having been rerun.
	 * @throws SQLException on database error.
	 */
	public static void markPairAsRerun(int pairId) throws SQLException {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.MarkPairAsRerun(?)");
			ps.setInt(1, pairId);
			Common.executeAndDrain(ps);
		} finally {
			Common.safeClose(ps);
			Common.safeClose(con);
		}
	}

	/**
	 * Marks a pair as having been rerun in the pairs_rerun table.
	 *
	 * @param con an open database connection to use for the procedure.
	 * @param pairId the id of the pair to mark as having been rerun.
	 * @throws SQLException on database error.
	 */
	public static void markPairAsRerun(Connection con, int pairId) throws SQLException {
		PreparedStatement ps = null;
		try {
			ps = con.prepareStatement("SELECT starexec.MarkPairAsRerun(?)");
			ps.setInt(1, pairId);
			Common.executeAndDrain(ps);
		} finally {
			Common.safeClose(ps);
		}
	}

	// Currently only used for tests.
	public static void unmarkPairAsRerun(int pairId) throws SQLException {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.UnmarkPairAsRerun(?)");
			ps.setInt(1, pairId);
			Common.executeAndDrain(ps);
		} finally {
			Common.safeClose(ps);
			Common.safeClose(con);
		}
	}
}
