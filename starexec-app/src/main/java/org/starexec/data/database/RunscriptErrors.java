package org.starexec.data.database;

import org.starexec.logger.StarLogger;
import org.starexec.constants.R;
import org.starexec.data.to.RunscriptError;

import java.sql.Date;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.ArrayList;

/**
 *
 */
public class RunscriptErrors {
	private RunscriptErrors() {} // Class cannot be instantiated

	// private static final StarLogger log = StarLogger.getLogger(Analytics.class);

	public static int getCount(Date begin, Date end) throws SQLException {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetRunscriptErrorsCount(?,?)");
			ps.setDate(1, begin);
			ps.setDate(2, end);
			results = ps.executeQuery();
			if (results.next()) {
				return results.getInt("count");
			} else {
				return 0;
			}
		} finally {
			Common.safeClose(results);
			Common.safeClose(ps);
			Common.safeClose(con);
		}
	}

	public static List<RunscriptError> getInRange(Date begin, Date end) throws SQLException {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetRunscriptErrors(?,?)");
			ps.setDate(1, begin);
			ps.setDate(2, end);
			results = ps.executeQuery();
			final List<RunscriptError> l = new ArrayList<>();
			while (results.next()) {
				java.util.Date time = results.getTimestamp("time");
				String node = results.getString("node");
				int jobPair = results.getInt("job_pair_id");
				l.add(new RunscriptError(time, node, jobPair));
			}
			return l;
		} finally {
			Common.safeClose(results);
			Common.safeClose(ps);
			Common.safeClose(con);
		}
	}

	public static String getUrl(Date begin, Date end) {
		return R.STAREXEC_ROOT
			+ "secure/admin/jobpairErrors.jsp?start="
			+ begin.toString()
			+ "&end="
			+ end.toString();
	}
}
