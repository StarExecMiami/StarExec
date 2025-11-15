package org.starexec.test.util;

import org.junit.Assume;
import org.starexec.constants.R;
import org.starexec.util.Util;

/**
 * Utility helpers for integration tests that need a live PostgreSQL instance.
 * They allow JVMs without starexec-config credentials to skip DB-heavy tests
 * instead of failing with authentication errors.
 */
public final class DatabaseTestSupport {
	private DatabaseTestSupport() {
	}

	/**
	 * @return true when the essential PostgreSQL properties are present
	 * (URL, username, and password).
	 */
	public static boolean isDatabaseConfigured() {
		return !Util.isNullOrEmpty(R.POSTGRES_URL)
			&& !Util.isNullOrEmpty(R.POSTGRES_USERNAME)
			&& !Util.isNullOrEmpty(R.POSTGRES_PASSWORD);
	}

	/**
	 * Skips the invoking test if the database credentials are missing.
	 *
	 * @param testName used in the skip message so developers know what to configure.
	 */
	public static void assumeDatabaseAvailable(String testName) {
		Assume.assumeTrue(
			String.format(
				"%s requires a configured PostgreSQL instance. Set POSTGRES_* values in starexec-config.xml or env before running.",
				testName
			),
			isDatabaseConfigured()
		);
	}
}
