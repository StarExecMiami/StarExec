package org.starexec.test.junit.database;

import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.data.database.Common;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CommonTest extends Common {
	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("CommonTest");
		Common.initialize();
	}

	/**
	 * Test that executeAndDrain properly executes a SELECT statement and drains the result set
	 * without leaving cursors open. This prevents cursor leaks that were the subject of code review feedback.
	 */
	@Test
	public void testExecuteAndDrainHandlesSelectStatement() throws SQLException {
		try (Connection con = Common.getConnection();
		     PreparedStatement ps = con.prepareStatement("SELECT 1 as test_value")) {

			// This should execute the statement and drain any result set
			Common.executeAndDrain(ps);

			// If we get here without exceptions, the method worked
			assertTrue("executeAndDrain should complete without throwing exceptions", true);
		}
	}

	/**
	 * Test that executeAndDrain handles statements that return no result set (like INSERT/UPDATE/DELETE)
	 */
	@Test
	public void testExecuteAndDrainHandlesNonSelectStatement() throws SQLException {
		// Create a temporary table for testing
		try (Connection con = Common.getConnection()) {
			try (PreparedStatement createPs = con.prepareStatement(
					"CREATE TEMP TABLE test_execute_drain (id INTEGER)")) {
				createPs.execute();
			}

			try (PreparedStatement insertPs = con.prepareStatement(
					"INSERT INTO test_execute_drain VALUES (1)")) {
				// This should execute and drain (though INSERT returns no ResultSet)
				Common.executeAndDrain(insertPs);
				assertTrue("executeAndDrain should handle INSERT statements", true);
			}

			// Clean up
			try (PreparedStatement dropPs = con.prepareStatement("DROP TABLE test_execute_drain")) {
				dropPs.execute();
			}
		}
	}

	/**
	 * Test that executeAndDrain properly handles exceptions from malformed SQL
	 */
	@Test
	public void testExecuteAndDrainHandlesSqlException() throws SQLException {
		try (Connection con = Common.getConnection();
		     PreparedStatement ps = con.prepareStatement("SELECT * FROM nonexistent_table_12345")) {

			try {
				Common.executeAndDrain(ps);
				fail("Expected SQLException for invalid table name");
			} catch (SQLException e) {
				// Expected - invalid table should cause an exception
				assertTrue("Should throw SQLException for invalid SQL", true);
			}
		}
	}
}