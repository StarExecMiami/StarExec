package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.data.database.Common;
import org.starexec.data.database.UploadJobQueue;
import org.starexec.data.to.UploadJob;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * At most one upload by URL is claimed at a time, against a real database (#139).
 *
 * <p>An upload by URL holds its worker for as long as its download takes, up to the downloader's
 * deadline. Without the limit, a few slow downloads could take every worker and leave uploaded
 * files waiting behind them. The claim query enforces it, so this drives the real query.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*}
 * variables at a <strong>disposable</strong> database.
 */
public class UploadJobQueueClaimSqlTest extends Common {
	private static final String TAG = "upload-claim-probe";
	private static final int USER_ID = 90951;
	private static final int SPACE_ID = 90951;
	// Above any priority the application assigns, so these rows are claimed before anything else
	// the database may hold.
	private static final int PRIORITY = 1_000_000;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("UploadJobQueueClaimSqlTest");
		Common.initialize();
	}

	@Before
	public void seed() throws SQLException {
		cleanUp();
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("INSERT INTO starexec.users (id,email,first_name,last_name,institution,"
					+ "created,password,disk_quota,disk_size) VALUES (" + USER_ID + ",'"
					+ TAG + "@test','A','P','I',NOW(),'x',1000000000,0)");
			s.execute("INSERT INTO starexec.spaces (id,name,created,locked) VALUES ("
					+ SPACE_ID + ",'" + TAG + "',NOW(),false)");
			try (ResultSet rs = s.executeQuery("SELECT count(*) FROM starexec.upload_jobs WHERE status = "
					+ "'PROCESSING' AND source_url IS NOT NULL")) {
				rs.next();
				assertEquals("an upload by URL from outside this test is already running; use a "
						+ "disposable database", 0, rs.getInt(1));
			}
		}
	}

	@After
	public void cleanUp() throws SQLException {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("DELETE FROM starexec.upload_jobs WHERE user_id = " + USER_ID);
			s.execute("DELETE FROM starexec.spaces WHERE id = " + SPACE_ID);
			s.execute("DELETE FROM starexec.users WHERE id = " + USER_ID);
		}
	}

	/** Another upload by URL waits while one runs; an uploaded file does not. */
	@Test
	public void whileOneUrlUploadRunsAnotherWaits() throws SQLException {
		insertJob("PROCESSING", "https://example.org/running.tgz", 5);
		long waiting = insertJob("PENDING", "https://example.org/next.tgz", 1);
		long file = insertJob("PENDING", null, 2);

		assertEquals("the uploaded file is claimed although the URL upload is smaller", file, claimed());
		assertEquals("PENDING", status(waiting));
	}

	/** The limit holds for a URL upload this claim itself started, not only for an older one. */
	@Test
	public void aClaimedUrlUploadBlocksTheNextOne() throws SQLException {
		long first = insertJob("PENDING", "https://example.org/first.tgz", 1);
		long file = insertJob("PENDING", null, 3);
		assertEquals(first, claimed());

		long second = insertJob("PENDING", "https://example.org/second.tgz", 0);
		assertEquals(file, claimed());
		assertEquals("PENDING", status(second));
	}

	/** Once the running URL upload ends, the next one is claimed. */
	@Test
	public void aFinishedUrlUploadNoLongerBlocks() throws SQLException {
		insertJob("COMPLETED", "https://example.org/done.tgz", 5);
		insertJob("FAILED", "https://example.org/failed.tgz", 5);
		long next = insertJob("PENDING", "https://example.org/next.tgz", 1);

		assertEquals(next, claimed());
		assertEquals("PROCESSING", status(next));
	}

	private long insertJob(String status, String sourceUrl, long archiveSize) throws SQLException {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement();
			 ResultSet rs = s.executeQuery("INSERT INTO starexec.upload_jobs (archive_path, user_id, "
					 + "space_id, upload_method, benchmark_type_id, priority, archive_size, status, source_url) "
					 + "VALUES ('/tmp/" + TAG + "', " + USER_ID + ", " + SPACE_ID + ", 'convert', 1, "
					 + PRIORITY + ", " + archiveSize + ", '" + status + "', "
					 + (sourceUrl == null ? "NULL" : "'" + sourceUrl + "'") + ") RETURNING id")) {
			rs.next();
			return rs.getLong(1);
		}
	}

	private static long claimed() {
		Optional<UploadJob> job = UploadJobQueue.claimJob();
		assertTrue("a job was claimed", job.isPresent());
		return job.get().getId();
	}

	private static String status(long id) throws SQLException {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement();
			 ResultSet rs = s.executeQuery("SELECT status FROM starexec.upload_jobs WHERE id = " + id)) {
			rs.next();
			return rs.getString(1);
		}
	}
}
