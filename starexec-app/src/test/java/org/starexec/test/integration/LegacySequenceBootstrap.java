package org.starexec.test.integration;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.starexec.constants.PaginationQueries;
import org.starexec.constants.R;
import org.starexec.data.database.Common;
import org.starexec.test.util.DatabaseTestSupport;
import org.starexec.util.Util;
import org.starexec.util.Validator;

/**
 * Initializes the PostgreSQL connection pool, validator regexes, pagination SQL,
 * data directories, and required #141 ZIP fixtures once before live legacy
 * {@link TestSequence} execution.
 */
final class LegacySequenceBootstrap {
	private static final List<String> REQUIRED_FIXTURES = List.of(
			"/org/starexec/test/resources/CVC4.zip",
			"/org/starexec/test/resources/benchmarks.zip",
			"/org/starexec/test/resources/benchproc.zip",
			"/org/starexec/test/resources/postproc.zip",
			"/org/starexec/test/resources/smallsolver.zip");
	private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

	private LegacySequenceBootstrap() {
	}

	static void initializeForLiveSuite() {
		verifyRequiredFixturesPresent();
		if (INITIALIZED.compareAndSet(false, true)) {
			if (!DatabaseTestSupport.isDatabaseConfigured()) {
				INITIALIZED.set(false);
				throw new IllegalStateException(
						"PostgreSQL is not reachable at " + R.POSTGRES_URL
								+ " for user '" + R.POSTGRES_USERNAME
								+ "'. Configure STAREXEC_DB_* environment variables against a migrated PostgreSQL instance.");
			}
			Common.initialize();
			Validator.initialize();
			try {
				PaginationQueries.loadPaginationQueries();
			} catch (IOException e) {
				throw new IllegalStateException("Failed to load PaginationQueries for legacy suite", e);
			}
			Util.initializeDataDirectories();
			java.io.File downloadDir = new java.io.File(R.STAREXEC_ROOT, R.DOWNLOAD_FILE_DIR);
			if (!downloadDir.isDirectory() || !downloadDir.canWrite()) {
				INITIALIZED.set(false);
				throw new IllegalStateException(
						"Download directory is not writable: " + downloadDir.getAbsolutePath()
								+ ". Configure STAREXEC_WEB_BASE_DIR (and STAREXEC_DATA_DIR) to writable directories before running legacy tests.");
			}
		}
	}

	static void verifyRequiredFixturesPresent() {
		for (String resource : REQUIRED_FIXTURES) {
			try (InputStream in = LegacySequenceBootstrap.class.getResourceAsStream(resource)) {
				if (in == null || in.read() == -1) {
					throw new IllegalStateException(
							"Missing or empty #141 test fixture on classpath: " + resource);
				}
			} catch (IOException e) {
				throw new IllegalStateException("Failed to verify #141 test fixture: " + resource, e);
			}
		}
	}
}
