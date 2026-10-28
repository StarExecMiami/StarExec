package org.starexec.test.junit.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.starexec.data.database.Common;
import org.starexec.test.util.DatabaseTestSupport;
import org.starexec.util.PasswordHasher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Users migrated from the legacy deployment can log in after the wrapping script runs.
 *
 * <p>The legacy deployment stores the unsalted SHA-512 hex digest of each password; this
 * application verifies BCrypt only. sql/wrap-legacy-password-hashes.sql wraps each
 * digest in BCrypt. This runs that exact file against PostgreSQL and checks the result with the
 * application's own verification, so the SQL (pgcrypto) and Java (jBCrypt) sides cannot drift.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*} variables
 * at a <strong>disposable</strong> database: these tests insert and delete rows.
 */
public class LegacyPasswordWrappingSqlTest extends Common {

	private static final Path SCRIPT = Path.of("../sql/wrap-legacy-password-hashes.sql");

	/** SHA-512 hex of 'Correct-Horse-9': what the legacy deployment stores for that password. */
	private static final String LEGACY_HEX =
			"63b40d13b71279d42afdb91d709f75d7053d1639d9cf125b9c67c9471f022d96"
					+ "26fb5d962718da8e31f93da2564a3ac3d48a1cd58d7b35e3cd5be09b633748ca";

	private final List<Integer> users = new ArrayList<>();
	private int legacyUser;
	private int bcryptUser;
	private String bcryptHash;

	@BeforeAll
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("LegacyPasswordWrappingSqlTest");
		Common.initialize();
	}

	@BeforeEach
	public void seed() throws SQLException {
		legacyUser = insertUser("legacy", LEGACY_HEX);
		bcryptHash = PasswordHasher.hash("modern-password");
		bcryptUser = insertUser("bcrypt", bcryptHash);
	}

	@AfterEach
	public void dropFixture() throws SQLException {
		try (Connection con = Common.getConnection();
		     PreparedStatement ps = con.prepareStatement("DELETE FROM starexec.users WHERE id = ?")) {
			for (int id : users) {
				ps.setInt(1, id);
				ps.executeUpdate();
			}
		}
	}

	@Test
	public void aLegacyUserCanLogInWithTheirPasswordAfterWrapping() throws Exception {
		runScript();

		String stored = password(legacyUser);
		assertTrue(stored.startsWith(PasswordHasher.LEGACY_SHA512_PREFIX + "$2a$12$"), "wrapped and marked: " + stored);
		assertTrue(stored.length() <= 128, "fits users.password VARCHAR(128)");
		assertTrue(PasswordHasher.verify("Correct-Horse-9", stored));
		assertFalse(PasswordHasher.verify("Correct-Horse-8", stored));
		assertFalse(PasswordHasher.verify(LEGACY_HEX, stored), "the old digest is not a working password");
	}

	@Test
	public void bcryptUsersAreLeftAlone() throws Exception {
		runScript();

		assertEquals(bcryptHash, password(bcryptUser));
		assertTrue(PasswordHasher.verify("modern-password", password(bcryptUser)));
	}

	@Test
	public void rerunningTheScriptChangesNothing() throws Exception {
		runScript();
		String first = password(legacyUser);

		runScript();

		assertEquals(first, password(legacyUser), "a wrapped value is never wrapped again");
	}

	private static void runScript() throws Exception {
		String sql = Files.readString(SCRIPT, StandardCharsets.UTF_8);
		try (Connection con = Common.getConnection(); Statement st = con.createStatement()) {
			st.execute(sql);
		}
	}

	private int insertUser(String suffix, String password) throws SQLException {
		try (Connection con = Common.getConnection();
		     PreparedStatement ps = con.prepareStatement(
				     "INSERT INTO starexec.users(email, first_name, last_name, institution, created,"
						     + " password, disk_quota, job_pair_quota, disk_size)"
						     + " VALUES (?, 'Legacy', 'Wrap', 'test', now(), ?, 1, 1, 0) RETURNING id")) {
			ps.setString(1, "pwdwrap-" + suffix + "-" + System.nanoTime() + "@example.invalid");
			ps.setString(2, password);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				int id = rs.getInt(1);
				users.add(id);
				return id;
			}
		}
	}

	private static String password(int userId) throws SQLException {
		try (Connection con = Common.getConnection();
		     PreparedStatement ps = con.prepareStatement("SELECT password FROM starexec.users WHERE id = ?")) {
			ps.setInt(1, userId);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getString(1);
			}
		}
	}
}
