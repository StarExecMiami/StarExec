package org.starexec.test.junit.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.starexec.data.database.Benchmarks;
import org.starexec.data.database.Common;
import org.starexec.data.to.Benchmark;
import org.starexec.test.util.DatabaseTestSupport;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dependency resolution walks from the dependency root into subspaces named by an include. It
 * must enter only subspaces the uploader belongs to, as it did before ba5a5b38a (2026-03), which
 * resolved as user 0 and so took GetSubSpaceByName's unfiltered branch.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*}
 * variables at a <strong>disposable</strong> database.
 */
public class DependencyResolutionVisibilitySqlTest extends Common {
	private static final String TAG = "depvis-probe";
	private static final int OWNER = 90901;
	private static final int OUTSIDER = 90902;
	private static final int ROOT_SPACE = 90901;
	/** A subspace only OWNER belongs to. */
	private static final int PRIVATE_SPACE = 90902;
	private static final int AXIOM = 90901;

	@BeforeAll
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("DependencyResolutionVisibilitySqlTest");
		Common.initialize();
	}

	@BeforeEach
	public void seed() throws Exception {
		cleanUp();
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			for (int user : new int[]{OWNER, OUTSIDER}) {
				s.execute("INSERT INTO starexec.users (id,email,first_name,last_name,institution,"
						+ "created,password,disk_quota,disk_size) VALUES (" + user + ",'" + TAG + user
						+ "@test','A','P','I',NOW(),'x',1000000000,0)");
			}
			s.execute("INSERT INTO starexec.spaces (id,name,created,locked) VALUES ("
					+ ROOT_SPACE + ",'" + TAG + "',NOW(),false)");
			s.execute("INSERT INTO starexec.spaces (id,name,created,locked) VALUES ("
					+ PRIVATE_SPACE + ",'Private',NOW(),false)");
			s.execute("INSERT INTO starexec.set_assoc (space_id,child_id) VALUES ("
					+ ROOT_SPACE + "," + PRIVATE_SPACE + ")");
			// As AddSpace leaves them: each space is its own ancestor, and the root is the private one's.
			s.execute("INSERT INTO starexec.closure (ancestor,descendant) VALUES (" + ROOT_SPACE + ","
					+ ROOT_SPACE + "),(" + ROOT_SPACE + "," + PRIVATE_SPACE + "),(" + PRIVATE_SPACE + ","
					+ PRIVATE_SPACE + ")");
			s.execute("INSERT INTO starexec.user_assoc (user_id,space_id) VALUES (" + OWNER + ","
					+ ROOT_SPACE + "),(" + OWNER + "," + PRIVATE_SPACE + ")");
			s.execute("INSERT INTO starexec.benchmarks (id,user_id,name,uploaded,path,description,"
					+ "downloadable,disk_size) VALUES (" + AXIOM + "," + OWNER
					+ ",'secret.ax',NOW(),'/tmp/" + TAG + "','d',false,0)");
			s.execute("INSERT INTO starexec.bench_assoc (space_id,bench_id) VALUES ("
					+ PRIVATE_SPACE + "," + AXIOM + ")");
		}
	}

	@AfterEach
	public void cleanUp() throws Exception {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			String spaces = "(" + ROOT_SPACE + "," + PRIVATE_SPACE + ")";
			s.execute("DELETE FROM starexec.bench_assoc WHERE space_id IN " + spaces);
			s.execute("DELETE FROM starexec.user_assoc WHERE space_id IN " + spaces);
			s.execute("DELETE FROM starexec.closure WHERE ancestor IN " + spaces + " OR descendant IN " + spaces);
			s.execute("DELETE FROM starexec.set_assoc WHERE space_id IN " + spaces);
			s.execute("DELETE FROM starexec.benchmarks WHERE id = " + AXIOM);
			s.execute("DELETE FROM starexec.spaces WHERE id IN " + spaces);
			s.execute("DELETE FROM starexec.users WHERE id IN (" + OWNER + "," + OUTSIDER + ")");
		}
	}

	/** A benchmark uploaded by {@code uploader}, including the private axiom through its subspace. */
	private static Benchmark uploadedBy(int uploader) {
		Benchmark b = new Benchmark();
		b.setName("problem.p");
		b.setUserId(uploader);
		Map<String, String> attributes = new HashMap<>();
		attributes.put("starexec-valid", "true");
		attributes.put("starexec-dependencies", "1");
		attributes.put("starexec-dependency-1", "Private/secret.ax");
		b.setAttributes(attributes);
		return b;
	}

	private static String resolve(Benchmark b) throws Exception {
		Method m = Benchmarks.class.getDeclaredMethod(
				"validateDependencies", List.class, Integer.class, Boolean.class, Integer.class);
		m.setAccessible(true);
		return (String) m.invoke(null, Collections.singletonList(b), ROOT_SPACE, false, null);
	}

	@Test
	public void aMemberResolvesThroughTheSubspace() throws Exception {
		Benchmark b = uploadedBy(OWNER);

		assertNull(resolve(b));
		assertEquals(AXIOM, b.getDependencies().get(0).getSecondaryBench().getId());
	}

	@Test
	public void aNonMemberDoesNotResolveThroughTheSubspace() throws Exception {
		Benchmark b = uploadedBy(OUTSIDER);

		String reason = resolve(b);

		assertNotNull(reason, "resolved an include through a space the uploader does not belong to");
		assertTrue(reason.startsWith("problem.p includes Private/secret.ax, which was not found"), reason);
		assertTrue(b.getDependencies().isEmpty());
	}
}
