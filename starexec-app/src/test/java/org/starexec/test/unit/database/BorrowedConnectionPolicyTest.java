package org.starexec.test.unit.database;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * On the job-creation path, a method that takes a {@code Connection} borrows it.
 *
 * <p>Atomicity is a property of the whole path, not of any one method, so it cannot be
 * asserted by calling something: one helper that commits on its own is enough to break it,
 * and its writes survive the owner's rollback silently. That is what happened here --
 * pipeline creation opened its own connection in autoCommit and committed a pipeline before
 * the job referencing it was attempted, and {@code Jobs.add} committed the job row before
 * opening the transaction that wrote its pairs. Both looked like ordinary code.
 *
 * <p>So this reads the source and asserts the shape instead. A borrowed-connection method
 * must not acquire a connection, begin, commit, roll back, close, or change autoCommit. The
 * owner variants -- the overloads that take no connection -- still do all of that, which is
 * exactly what makes them owners.
 *
 * <p>Structural, and therefore blunt: it proves no method on this list manages a connection
 * it was handed. It does not prove the path is otherwise correct, which is what
 * {@code TransactionOwnershipTest} and the rollback cases in {@code StoredRoutineSmokeIT}
 * are for.
 */
public class BorrowedConnectionPolicyTest {

	private static final Path SOURCE = Path.of("src/main/java/org/starexec/data/database");

	/** Everything reached from the borrowed {@code Jobs.add(Job, int, Connection)}. */
	private static final Map<String, List<String>> PATH = new LinkedHashMap<>();

	static {
		PATH.put("Jobs.java", Arrays.asList("add", "addJob", "createSpacesForPairs",
				"createJobSpacesForPairs", "updatePrimarySpace", "associate",
				"addJobStageAttributes", "resume"));
		PATH.put("JobPairs.java", Arrays.asList("addJobPairs", "addJobPairStages",
				"addJobPairInputs", "incrementTotalJobPairsForJob"));
		PATH.put("Pipelines.java", Arrays.asList("addPipelineToDatabase",
				"addPipelineStageToDatabase", "addDependencyToDatabase"));
		PATH.put("Reports.java", Arrays.asList("addToEventOccurrences",
				"addToEventOccurrencesForQueue", "addToEventOccurrencesNotRelatedToQueue"));
	}

	private static final Pattern LIFECYCLE = Pattern.compile(
			"Common\\.getConnection\\(\\)"
			+ "|Common\\.beginTransaction"
			+ "|Common\\.endTransaction"
			+ "|Common\\.doRollback"
			+ "|Common\\.enableAutoCommit"
			+ "|\\.setAutoCommit\\("
			+ "|\\bcon\\.commit\\(\\)"
			+ "|\\bcon\\.rollback\\(\\)"
			+ "|\\bcon\\.close\\(\\)"
			+ "|Common\\.safeClose\\(con\\)");

	@Test
	public void noBorrowedMethodOnTheJobCreationPathManagesTheConnection() throws Exception {
		List<String> violations = new ArrayList<>();
		int borrowedSeen = 0;

		for (Map.Entry<String, List<String>> file : PATH.entrySet()) {
			for (Method method : methodsIn(SOURCE.resolve(file.getKey()))) {
				if (!file.getValue().contains(method.name) || !method.borrowsConnection()) {
					continue;
				}
				borrowedSeen++;
				Matcher hit = LIFECYCLE.matcher(method.body);
				if (hit.find()) {
					violations.add(file.getKey() + ":" + method.name
							+ " manages a connection it borrowed: " + hit.group());
				}
			}
		}

		assertTrue("the path is no longer being read; check the method names in PATH",
				borrowedSeen >= PATH.values().stream().mapToInt(List::size).sum());
		assertEquals("borrowed-connection methods must leave the transaction to its owner",
				List.of(), violations);
	}

	/** The owners must still exist, or "nothing manages the connection" is trivially true. */
	@Test
	public void theOwnersStillOwn() throws Exception {
		List<String> owners = new ArrayList<>();
		for (Method method : methodsIn(SOURCE.resolve("Common.java"))) {
			if (method.name.equals("runTransactional") || method.name.equals("inTransaction")) {
				owners.add(method.name);
			}
		}
		assertTrue("Common must provide the transaction owner: " + owners,
				owners.contains("runTransactional") && owners.contains("inTransaction"));
	}

	// ------------------------------------------------------------------ parsing

	private static final Pattern DECLARATION = Pattern.compile(
			"^[ \\t]*(?:public|private|protected|static|final|synchronized|\\s)+"
			+ "[\\w<>\\[\\],\\s?.]+\\s+(\\w+)\\s*\\(([^;{]*)\\)\\s*(?:throws [\\w,\\s.]+)?\\{",
			Pattern.MULTILINE);

	private static final class Method {
		final String name;
		final String parameters;
		final String body;

		Method(String name, String parameters, String body) {
			this.name = name;
			this.parameters = parameters;
			this.body = body;
		}

		boolean borrowsConnection() {
			return parameters.contains("Connection ");
		}
	}

	/** Method bodies by brace matching, which is enough for this file set. */
	private List<Method> methodsIn(Path file) throws Exception {
		String source = Files.readString(file);
		List<Method> methods = new ArrayList<>();
		Matcher m = DECLARATION.matcher(source);
		while (m.find()) {
			int open = source.indexOf('{', m.start());
			int depth = 0;
			int i = open;
			for (; i < source.length(); i++) {
				if (source.charAt(i) == '{') {
					depth++;
				} else if (source.charAt(i) == '}' && --depth == 0) {
					break;
				}
			}
			methods.add(new Method(m.group(1), m.group(2), source.substring(open, Math.min(i + 1, source.length()))));
		}
		return methods;
	}
}
