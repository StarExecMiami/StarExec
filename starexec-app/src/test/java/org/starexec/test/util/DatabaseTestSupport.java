package org.starexec.test.util;

import org.junit.Assume;
import org.starexec.constants.R;
import org.starexec.util.Util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Utility helpers for integration tests that need a live PostgreSQL instance.
 * They allow JVMs without starexec-config credentials to skip DB-heavy tests
 * instead of failing with authentication errors.
 */
public final class DatabaseTestSupport {
	private DatabaseTestSupport() {
	}

	/**
	 * @return true only when credentials are present AND a TCP connection
	 *         to PostgreSQL can actually be established.
	 */
	public static boolean isDatabaseConfigured() {
		if (Util.isNullOrEmpty(R.POSTGRES_URL)
			|| Util.isNullOrEmpty(R.POSTGRES_USERNAME)
			|| Util.isNullOrEmpty(R.POSTGRES_PASSWORD)) {
			return false;
		}
		// Attempt a real connection — credentials being set is not enough.
		try (Connection c = DriverManager.getConnection(
				R.POSTGRES_URL, R.POSTGRES_USERNAME, R.POSTGRES_PASSWORD)) {
			return true;
		} catch (SQLException e) {
			return false;
		}
	}

	/**
	 * Skips the invoking test if the database is not reachable.
	 *
	 * @param testName used in the skip message so developers know what to configure.
	 */
	public static void assumeDatabaseAvailable(String testName) {
		Assume.assumeTrue(
			String.format(
				"%s requires a reachable PostgreSQL instance. Set POSTGRES_* values in starexec-config.xml and ensure the DB is running.",
				testName
			),
			isDatabaseConfigured()
		);
	}
}
