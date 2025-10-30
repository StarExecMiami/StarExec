package org.starexec.data.database;

import org.starexec.logger.StarLogger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
 

/**
 * Class for accessing the logins table.
 */
public class Logins {

	private static final StarLogger log = StarLogger.getLogger(Logins.class);

	/**
	 * Gets the number of unique user logins in the logins table.
	 *
	 * @return number of unique user logins, null if an Exception occurs.
	 * @author Albert Giegerich
	 */
	public static Integer getNumberOfUniqueLogins() {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetNumberOfUniqueLogins()");
			results = ps.executeQuery();
			if (results.next()) {
				return results.getInt("count");
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(results);
			Common.safeClose(ps);
			Common.safeClose(con);
		}
		return null;
	}

	/**
	 * Clears all data in the logins table.
	 *
	 * @author Albert Giegerich
	 */
	public static void resetLogins() {
		try {
			java.sql.Connection con = null;
			java.sql.PreparedStatement ps = null;
			try {
				con = Common.getConnection();
				ps = con.prepareStatement("SELECT starexec.ResetLogins()");
				ps.execute();
			} finally {
				Common.safeClose(ps);
				Common.safeClose(con);
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}
}
