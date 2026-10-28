package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.data.database.Benchmarks;
import org.starexec.data.database.Common;
import org.starexec.data.to.Benchmark;
import org.starexec.test.util.DatabaseTestSupport;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Does a refused upload say which include failed and where it was looked for?
 *
 * <p>On Quokka (2026-10-15) a 34-file upload was refused twice with only "Benchmark
 * dependencies could not be resolved for this upload". The cause was one axiom,
 * {@code Axioms/DAT001_0.ax}, missing from the dependency root space, and only the server
 * log said so. Resolution walks real spaces, so this runs against the database.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*}
 * variables at a <strong>disposable</strong> database.
 */
public class UploadDependencyReasonSqlTest extends Common {
	private static final String TAG = "depreason-probe";
	private static final int USER_ID = 90801;
	/** The space an uploader picks when the include's first segment names a subspace. */
	private static final int ROOT_SPACE = 90801;
	/** Its "Axioms" subspace, which an uploader picks with "linked" set. */
	private static final int AXIOM_SPACE = 90802;
	private static final int AXIOM_BENCH = 90801;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("UploadDependencyReasonSqlTest");
		Common.initialize();
	}

	@Before
	public void seed() throws Exception {
		cleanUp();
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("INSERT INTO starexec.users (id,email,first_name,last_name,institution,"
					+ "created,password,disk_quota,disk_size) VALUES (" + USER_ID + ",'"
					+ TAG + "@test','A','P','I',NOW(),'x',1000000000,0)");
			s.execute("INSERT INTO starexec.spaces (id,name,created,locked) VALUES ("
					+ ROOT_SPACE + ",'" + TAG + "',NOW(),false)");
			s.execute("INSERT INTO starexec.spaces (id,name,created,locked) VALUES ("
					+ AXIOM_SPACE + ",'Axioms',NOW(),false)");
			s.execute("INSERT INTO starexec.set_assoc (space_id,child_id) VALUES ("
					+ ROOT_SPACE + "," + AXIOM_SPACE + ")");
			s.execute("INSERT INTO starexec.benchmarks (id,user_id,name,uploaded,path,description,"
					+ "downloadable,disk_size) VALUES (" + AXIOM_BENCH + "," + USER_ID
					+ ",'AGT001+0.ax',NOW(),'/tmp/" + TAG + "','d',false,0)");
			s.execute("INSERT INTO starexec.bench_assoc (space_id,bench_id) VALUES ("
					+ AXIOM_SPACE + "," + AXIOM_BENCH + ")");
		}
	}

	@After
	public void cleanUp() throws Exception {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("DELETE FROM starexec.bench_assoc WHERE space_id IN (" + ROOT_SPACE + "," + AXIOM_SPACE + ")");
			s.execute("DELETE FROM starexec.set_assoc WHERE space_id = " + ROOT_SPACE);
			s.execute("DELETE FROM starexec.benchmarks WHERE id = " + AXIOM_BENCH);
			s.execute("DELETE FROM starexec.spaces WHERE id IN (" + ROOT_SPACE + "," + AXIOM_SPACE + ")");
			s.execute("DELETE FROM starexec.users WHERE id = " + USER_ID);
		}
	}

	/** A benchmark as the processor leaves it: one include, named in its attributes. */
	private static Benchmark including(String name, String includePath) {
		Benchmark b = new Benchmark();
		b.setName(name);
		Map<String, String> attributes = new HashMap<>();
		attributes.put("starexec-valid", "true");
		attributes.put("starexec-dependencies", "1");
		attributes.put("starexec-dependency-1", includePath);
		b.setAttributes(attributes);
		return b;
	}

	private static String validateDependencies(List<Benchmark> benchmarks, int rootSpace, boolean linked)
			throws Exception {
		Method m = Benchmarks.class.getDeclaredMethod(
				"validateDependencies", List.class, Integer.class, Boolean.class, Integer.class);
		m.setAccessible(true);
		return (String) m.invoke(null, benchmarks, rootSpace, linked, null);
	}

	/** Geoff's case: the root space he picked is an "Axioms" space without that axiom. */
	@Test
	public void aMissingAxiomIsNamedWithTheSpaceItWasLookedFor() throws Exception {
		Benchmark problem = including("DAT013_1.p", "Axioms/DAT001_0.ax");

		String reason = validateDependencies(Collections.singletonList(problem), AXIOM_SPACE, true);

		assertEquals("DAT013_1.p includes Axioms/DAT001_0.ax, which was not found under the"
				+ " dependency root space (id " + AXIOM_SPACE + ")", reason);
	}

	@Test
	public void anIncludeFoundInTheLinkedRootResolves() throws Exception {
		Benchmark problem = including("AGT001+1.p", "Axioms/AGT001+0.ax");

		assertNull(validateDependencies(Collections.singletonList(problem), AXIOM_SPACE, true));
		assertEquals(1, problem.getDependencies().size());
		assertEquals(AXIOM_BENCH, problem.getDependencies().get(0).getSecondaryBench().getId());
	}

	@Test
	public void anUnlinkedRootResolvesThroughItsSubspace() throws Exception {
		Benchmark problem = including("AGT001+1.p", "Axioms/AGT001+0.ax");

		assertNull(validateDependencies(Collections.singletonList(problem), ROOT_SPACE, false));
		assertEquals(1, problem.getDependencies().size());
	}

	@Test
	public void anUnlinkedRootNamesItselfWhenTheAxiomIsMissing() throws Exception {
		Benchmark problem = including("DAT013_1.p", "Axioms/DAT001_0.ax");

		String reason = validateDependencies(Collections.singletonList(problem), ROOT_SPACE, false);

		assertEquals("DAT013_1.p includes Axioms/DAT001_0.ax, which was not found under the"
				+ " dependency root space (id " + ROOT_SPACE + ")", reason);
	}

	/** One file stops the batch; the reason must name that file, not the batch or its first file. */
	@Test
	public void theFailingFileIsTheOneNamed() throws Exception {
		List<Benchmark> batch = Arrays.asList(
				including("AGT001+1.p", "Axioms/AGT001+0.ax"),
				including("DAT013_1.p", "Axioms/DAT001_0.ax"));

		String reason = validateDependencies(batch, AXIOM_SPACE, true);

		assertEquals("DAT013_1.p includes Axioms/DAT001_0.ax, which was not found under the"
				+ " dependency root space (id " + AXIOM_SPACE + ")", reason);
	}
}
