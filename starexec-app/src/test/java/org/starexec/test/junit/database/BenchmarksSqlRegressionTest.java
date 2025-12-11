package org.starexec.test.junit.database;

import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.data.database.Common;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BenchmarksSqlRegressionTest extends Common {
	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("BenchmarksSqlRegressionTest");
		Common.initialize();
	}

	@Test
	public void testGetBenchmarkByIdIncludesAllProcessorColumns() throws Exception {
		withFixture(fixture -> {
			try (PreparedStatement ps = fixture.con.prepareStatement("SELECT * FROM starexec.GetBenchmarkById(?)")) {
				ps.setInt(1, fixture.benchId);
				try (ResultSet rs = ps.executeQuery()) {
					assertTrue("Should return a row", rs.next());
					assertProcessorColumnsPresent(rs);
				}
			}
		});
	}

	@Test
	public void testGetBenchmarksByOwnerIncludesAllProcessorColumns() throws Exception {
		withFixture(fixture -> {
			try (PreparedStatement ps = fixture.con.prepareStatement("SELECT * FROM starexec.GetBenchmarksByOwner(?)")) {
				ps.setInt(1, fixture.userId);
				try (ResultSet rs = ps.executeQuery()) {
					assertBenchPresentInResult(rs, fixture.benchId, "id");
				}
			}
		});
	}

	@Test
	public void testGetSpaceBenchmarksByIdIncludesAllProcessorColumns() throws Exception {
		withFixture(fixture -> {
			try (PreparedStatement ps = fixture.con.prepareStatement("SELECT * FROM starexec.GetSpaceBenchmarksById(?)")) {
				ps.setInt(1, fixture.spaceId);
				try (ResultSet rs = ps.executeQuery()) {
					assertBenchPresentInResult(rs, fixture.benchId, "bench_id");
				}
			}
		});
	}

	@Test
	public void testGetPublicBenchmarksIncludesAllProcessorColumns() throws Exception {
		withFixture(fixture -> {
			try (PreparedStatement ps = fixture.con.prepareStatement("SELECT * FROM starexec.GetPublicBenchmarks()")) {
				try (ResultSet rs = ps.executeQuery()) {
					assertBenchPresentInResult(rs, fixture.benchId, "id");
				}
			}
		});
	}

	@Test
	public void testGetBenchmarksInSharedSpacesIncludesAllProcessorColumns() throws Exception {
		withFixture(fixture -> {
			try (PreparedStatement ps = fixture.con.prepareStatement("SELECT * FROM starexec.GetBenchmarksInSharedSpaces(?)")) {
				ps.setInt(1, fixture.userId);
				try (ResultSet rs = ps.executeQuery()) {
					assertBenchPresentInResult(rs, fixture.benchId, "id");
				}
			}
		});
	}

	private interface SqlFixtureConsumer {
		void accept(Fixture fixture) throws Exception;
	}

	private static final class Fixture {
		private final Connection con;
		private final int userId;
		private final int spaceId;
		private final int benchId;

		private Fixture(Connection con, int userId, int spaceId, int benchId) {
			this.con = con;
			this.userId = userId;
			this.spaceId = spaceId;
			this.benchId = benchId;
		}
	}

	private void withFixture(SqlFixtureConsumer consumer) throws Exception {
		try (Connection con = Common.getConnection()) {
			con.setAutoCommit(false);
			Fixture fixture = createFixture(con);
			try {
				consumer.accept(fixture);
			} finally {
				con.rollback();
			}
		}
	}

	private Fixture createFixture(Connection con) throws SQLException {
		int userId = selectSingleInt(con, "SELECT id FROM users WHERE email='admin' LIMIT 1");
		int spaceId = selectSingleInt(con, "SELECT id FROM spaces WHERE name='root' LIMIT 1");
		int processorId = insertProcessor(con, spaceId);
		int benchId = insertBenchmark(con, userId, processorId);
		associateBenchmark(con, benchId, spaceId);
		return new Fixture(con, userId, spaceId, benchId);
	}

	private static int insertProcessor(Connection con, int communityId) throws SQLException {
		String sql = "INSERT INTO processors (name, description, path, community, processor_type, disk_size, preserve_input, syntax_id, time_limit) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			ps.setString(1, "proc-" + UUID.randomUUID());
			ps.setString(2, "regression processor");
			ps.setString(3, "/tmp/proc-" + UUID.randomUUID());
			ps.setInt(4, communityId);
			ps.setShort(5, (short) 0);
			ps.setLong(6, 1024L);
			ps.setBoolean(7, true);
			ps.setInt(8, 1);
			ps.setShort(9, (short) 15);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private static int insertBenchmark(Connection con, int userId, int processorId) throws SQLException {
		String sql = "INSERT INTO benchmarks (user_id, name, bench_type, uploaded, path, description, downloadable, disk_size, deleted, recycled) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			ps.setInt(1, userId);
			ps.setString(2, "bench-" + UUID.randomUUID());
			ps.setInt(3, processorId);
			ps.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
			ps.setString(5, "/tmp/bench-" + UUID.randomUUID());
			ps.setString(6, "regression benchmark");
			ps.setBoolean(7, true);
			ps.setLong(8, 2048L);
			ps.setBoolean(9, false);
			ps.setBoolean(10, false);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private static void associateBenchmark(Connection con, int benchId, int spaceId) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement("INSERT INTO bench_assoc (space_id, bench_id) VALUES (?, ?)")) {
			ps.setInt(1, spaceId);
			ps.setInt(2, benchId);
			ps.executeUpdate();
		}
	}

	private static int selectSingleInt(Connection con, String sql) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
			if (!rs.next()) {
				throw new IllegalStateException("Query returned no rows: " + sql);
			}
			return rs.getInt(1);
		}
	}

	private static void assertBenchPresentInResult(ResultSet rs, int expectedBenchId, String columnLabel) throws SQLException {
		boolean found = false;
		while (rs.next()) {
			if (rs.getInt(columnLabel) == expectedBenchId) {
				assertProcessorColumnsPresent(rs);
				found = true;
				break;
			}
		}
		assertTrue("Expected to find bench " + expectedBenchId + " in result set", found);
	}

	private static void assertProcessorColumnsPresent(ResultSet rs) throws SQLException {
		assertNotNull("types_id missing", rs.getObject("types_id"));
		assertNotNull("types_community missing", rs.getObject("types_community"));
		assertNotNull("types_name missing", rs.getObject("types_name"));
		assertNotNull("types_description missing", rs.getObject("types_description"));
		assertNotNull("types_path missing", rs.getObject("types_path"));
		assertNotNull("types_disk_size missing", rs.getObject("types_disk_size"));
		assertNotNull("types_processor_type missing", rs.getObject("types_processor_type"));
		assertNotNull("types_time_limit missing", rs.getObject("types_time_limit"));
		assertNotNull("types_syntax_id missing", rs.getObject("types_syntax_id"));
	}
}
