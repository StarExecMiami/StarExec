package org.starexec.data.database;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.starexec.data.to.Configuration;
import org.starexec.data.to.Solver;
import org.starexec.data.to.Solver.ExecutableType;
import org.starexec.data.to.SolverBuildStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Solvers.add commits a solver's row, its space association and its configurations together, or
 * none of them (#275). An upload or copy whose add throws deletes the solver's files, so a row
 * committed before the failure would be left pointing at a directory that no longer exists.
 *
 * <p>The connection is a mock that reports back the auto-commit mode it was last given, as a real
 * one does; Common's transaction helpers run for real against it. Each statement records the
 * auto-commit mode it ran under: one that runs with auto-commit on is committed at once, whatever
 * happens after it, so a later rollback could not undo it.
 */
public class SolversAddTransactionTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final AtomicBoolean autoCommit = new AtomicBoolean(true);
	private final List<Boolean> autoCommitWhenRun = new ArrayList<>();
	private final AtomicInteger connections = new AtomicInteger();
	private Connection con;
	private PreparedStatement addSolver;
	private PreparedStatement associate;
	private PreparedStatement addConfiguration;

	@Before
	public void connection() throws Exception {
		con = mock(Connection.class);
		addSolver = returningId(42);
		associate = mock(PreparedStatement.class);
		when(associate.execute()).thenAnswer(invocation -> {
			autoCommitWhenRun.add(autoCommit.get());
			return true;
		});
		addConfiguration = returningId(7);
		when(con.prepareStatement(contains("AddSolver("))).thenReturn(addSolver);
		when(con.prepareStatement(contains("AddSolverAssociation("))).thenReturn(associate);
		when(con.prepareStatement(contains("AddConfiguration("))).thenReturn(addConfiguration);
		doAnswer(invocation -> {
			autoCommit.set(invocation.getArgument(0));
			return null;
		}).when(con).setAutoCommit(anyBoolean());
		when(con.getAutoCommit()).thenAnswer(invocation -> autoCommit.get());
	}

	@Test
	public void aSolverIsCommittedWithItsAssociationAndConfigurations() throws Exception {
		assertEquals(42, add());

		InOrder order = inOrder(con, addSolver, associate, addConfiguration);
		order.verify(con).setAutoCommit(false);
		order.verify(addSolver).executeQuery();
		order.verify(associate).execute();
		order.verify(addConfiguration).executeQuery();
		order.verify(con).commit();
		verify(con, never()).rollback();
		assertEquals("each statement runs inside the transaction",
				Arrays.asList(false, false, false), autoCommitWhenRun);
		assertTrue("auto-commit is restored before the connection goes back to the pool", autoCommit.get());
		assertEquals("one connection carries every statement", 1, connections.get());
		verify(con).close();
	}

	@Test
	public void aFailedSpaceAssociationCommitsNothing() throws Exception {
		doThrow(new SQLException("synthetic association failure")).when(associate).execute();

		assertNothingCommitted();
	}

	@Test
	public void aFailedConfigurationCommitsNothing() throws Exception {
		doThrow(new SQLException("synthetic configuration failure")).when(addConfiguration).executeQuery();

		assertNothingCommitted();
	}

	/**
	 * Common.endTransaction would swallow this. add must throw instead: the caller would otherwise
	 * keep the files of a solver that was never stored.
	 */
	@Test
	public void aFailedCommitIsReported() throws Exception {
		doThrow(new SQLException("synthetic commit failure")).when(con).commit();

		assertThrows(SQLException.class, this::add);

		verify(con).rollback();
		assertEquals("each statement runs inside the transaction",
				Arrays.asList(false, false, false), autoCommitWhenRun);
		assertTrue("auto-commit is restored before the connection goes back to the pool", autoCommit.get());
		assertEquals("one connection carries every statement", 1, connections.get());
		verify(con).close();
	}

	private void assertNothingCommitted() throws Exception {
		assertThrows(SQLException.class, this::add);

		InOrder order = inOrder(con, addSolver);
		order.verify(con).setAutoCommit(false);
		order.verify(addSolver).executeQuery();
		verify(con, never()).commit();
		verify(con).rollback();
		assertFalse("AddSolver ran", autoCommitWhenRun.isEmpty());
		assertFalse("no statement runs outside the transaction", autoCommitWhenRun.contains(true));
		assertTrue("auto-commit is restored before the connection goes back to the pool", autoCommit.get());
		assertEquals("one connection carries every statement", 1, connections.get());
		verify(con).close();
	}

	/** Adds a solver with one configuration to space 3, through the mocked connection. */
	private int add() throws Exception {
		Solver solver = new Solver();
		solver.setUserId(5);
		solver.setName("synthetic-solver");
		solver.setPath(tmp.newFolder().getAbsolutePath());
		solver.setType(ExecutableType.SOLVER);
		SolverBuildStatus status = new SolverBuildStatus();
		status.setCode(1);
		solver.setBuildStatus(status);
		Configuration configuration = new Configuration();
		configuration.setName("default");
		solver.addConfiguration(configuration);

		// Only getConnection is replaced; the transaction helpers run for real. Warnings and
		// errors are not written to the error log table, which needs a database.
		try (MockedStatic<Common> common = mockStatic(Common.class, invocation -> {
			if (invocation.getMethod().getName().equals("getConnection")) {
				connections.incrementAndGet();
				return con;
			}
			return invocation.callRealMethod();
		});
		     MockedStatic<ErrorLogs> errorLogs = mockStatic(ErrorLogs.class)) {
			return Solvers.add(solver, 3);
		}
	}

	/** A statement whose query returns one row holding id, recording the auto-commit mode it ran under. */
	private PreparedStatement returningId(int id) throws SQLException {
		ResultSet results = mock(ResultSet.class);
		when(results.next()).thenReturn(true, false);
		when(results.getInt(1)).thenReturn(id);
		PreparedStatement statement = mock(PreparedStatement.class);
		when(statement.executeQuery()).thenAnswer(invocation -> {
			autoCommitWhenRun.add(autoCommit.get());
			return results;
		});
		return statement;
	}
}
