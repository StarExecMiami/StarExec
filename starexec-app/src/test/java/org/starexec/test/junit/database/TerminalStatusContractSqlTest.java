package org.starexec.test.junit.database;

import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.data.database.Common;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Java and the database must agree on what counts as a stage's final result.
 *
 * <p>They did not, and the gap was exploitable. The monitor used
 * {@code StatusCode.finishedRunning()} -- literally {@code val >= 7} -- while the database
 * used {@code starexec.IsTerminalPairStatus}. The two disagree on
 * {@code STATUS_PROCESSING_RESULTS(19)}, {@code STATUS_PAUSED(20)} and
 * {@code STATUS_PROCESSING(22)}, all of which mean work is still owed. A stage left at 22 is
 * selected by the periodic post-processing task, which then sets the whole PAIR to
 * {@code STATUS_COMPLETE} -- so a container could write 22 into one of its own stage records
 * and have its timeout laundered into a clean completion.
 *
 * <p>{@code isTerminalExecutionResult()} now carries the Java half. A hand-maintained copy of
 * a database predicate drifts, so this compares them value by value against the live routine
 * rather than against a second copy of the list. Adding a status without deciding both sides
 * fails here.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*}
 * variables at a <strong>disposable</strong> database.
 */
public class TerminalStatusContractSqlTest extends Common {

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("TerminalStatusContractSqlTest");
		Common.initialize();
	}

	@Test
	public void javaAndTheDatabaseAgreeOnEveryStatusCode() throws SQLException {
		List<String> disagreements = new ArrayList<>();
		int checked = 0;

		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement(
						"SELECT starexec.IsTerminalPairStatus(?)")) {
			for (StatusCode code : StatusCode.values()) {
				ps.setInt(1, code.getVal());
				boolean db;
				try (ResultSet rs = ps.executeQuery()) {
					assertTrue("IsTerminalPairStatus returned no row for " + code, rs.next());
					db = rs.getBoolean(1);
				}
				boolean java = code.isTerminalExecutionResult();
				checked++;
				if (java != db) {
					disagreements.add(code + "(" + code.getVal() + "): java=" + java
							+ " db=" + db);
				}
			}
		}

		assertTrue("every status must be checked", checked >= 20);
		assertEquals("Java and SQL disagree on: " + disagreements,
				0, disagreements.size());
	}

	/**
	 * The three that were actually wrong, named so a regression says which contract broke
	 * rather than only that one did.
	 */
	@Test
	public void theStatesThatMeanWorkIsStillOwedAreNotResults() {
		assertTrue("PROCESSING_RESULTS is not a result",
				!StatusCode.STATUS_PROCESSING_RESULTS.isTerminalExecutionResult());
		assertTrue("PAUSED is not a result",
				!StatusCode.STATUS_PAUSED.isTerminalExecutionResult());
		assertTrue("PROCESSING is not a result -- post-processing turns it into COMPLETE",
				!StatusCode.STATUS_PROCESSING.isTerminalExecutionResult());
	}

	/** And the predicate this replaced, kept visible so the difference is not re-litigated. */
	@Test
	public void finishedRunningIsNotTheSamePredicateAndMustNotBeUsedForResults() {
		List<StatusCode> divergent = new ArrayList<>();
		for (StatusCode code : StatusCode.values()) {
			if (code.finishedRunning() != code.isTerminalExecutionResult()) {
				divergent.add(code);
			}
		}
		assertEquals("finishedRunning() differs on exactly the three non-result states: "
				+ divergent, 3, divergent.size());
	}
}
