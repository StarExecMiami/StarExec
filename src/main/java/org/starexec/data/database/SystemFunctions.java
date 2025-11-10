package org.starexec.data.database;

import org.starexec.data.to.AnalyticsResults;
import org.starexec.logger.StarLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Date;
import java.util.List;

/**
 * Thin DAO wrappers for miscellaneous system-level SQL functions.
 */
public class SystemFunctions {
    private static final StarLogger log = StarLogger.getLogger(SystemFunctions.class);

    public static void rebuildSolver(int solverId) throws SQLException {
        final String method = "rebuildSolver";
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement("SELECT starexec.RebuildSolver(?)");
            ps.setInt(1, solverId);
            boolean hasResultSet = ps.execute();
            if (hasResultSet) {
                rs = ps.getResultSet();
                // Consume the result set to avoid cursor leaks
                while (rs.next()) {
                    // Do nothing, just consume
                }
            }
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            log.error(method, e.getMessage(), e);
            throw new SQLException(e);
        } finally {
            Common.safeClose(rs);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    public static boolean freezePrimitives() throws SQLException {
        final String method = "freezePrimitives";
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet results = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement("SELECT * FROM starexec.GetFreezePrimitives()");
            results = ps.executeQuery();
            if (results.next()) {
                return results.getBoolean("freeze_primitives");
            }
            return false;
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            log.error(method, e.getMessage(), e);
            throw new SQLException(e);
        } finally {
            Common.safeClose(results);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    public static void setReadOnly(boolean readOnly) throws SQLException {
        final String method = "setReadOnly";
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement("SELECT starexec.SetReadOnly(?)");
            ps.setBoolean(1, readOnly);
            boolean hasResultSet = ps.execute();
            if (hasResultSet) {
                ResultSet rs = ps.getResultSet();
                while (rs.next()) {
                    // consume the result set
                }
                Common.safeClose(rs);
            }
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            log.error(method, e.getMessage(), e);
            throw new SQLException(e);
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    public static boolean getReadOnly() throws SQLException {
        final String method = "getReadOnly";
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet results = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement("SELECT * FROM starexec.GetReadOnly()");
            results = ps.executeQuery();
            if (results.next()) {
                return results.getBoolean("read_only");
            }
            return false;
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            log.error(method, e.getMessage(), e);
            throw new SQLException(e);
        } finally {
            Common.safeClose(results);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    public static void setFreezePrimitives(boolean frozen) throws SQLException {
        final String method = "setFreezePrimitives";
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement("SELECT starexec.SetFreezePrimitives(?)");
            ps.setBoolean(1, frozen);
            boolean hasResultSet = ps.execute();
            if (hasResultSet) {
                ResultSet rs = ps.getResultSet();
                while (rs.next()) {
                    // consume the result set
                }
                Common.safeClose(rs);
            }
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            log.error(method, e.getMessage(), e);
            throw new SQLException(e);
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    public static List<AnalyticsResults> getAnalyticsForDateRange(Date start, Date end) throws SQLException {
        final String method = "getAnalyticsForDateRange";
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet results = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement("SELECT * FROM starexec.GetAnalyticsForDateRange(?, ?)");
            ps.setDate(1, start);
            ps.setDate(2, end);
            results = ps.executeQuery();
            return AnalyticsResults.listFromResults(results);
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            log.error(method, e.getMessage(), e);
            throw new SQLException(e);
        } finally {
            Common.safeClose(results);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }
}
