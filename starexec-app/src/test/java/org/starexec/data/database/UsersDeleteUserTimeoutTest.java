package org.starexec.data.database;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.starexec.constants.R;
import org.starexec.data.to.Benchmark;
import org.starexec.data.to.Job;
import org.starexec.data.to.Solver;
import org.starexec.data.to.Space;

import java.net.SocketTimeoutException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class UsersDeleteUserTimeoutTest {

	private Connection statefulConnection(AtomicBoolean autoCommit) throws SQLException {
		Connection con = mock(Connection.class);
		doAnswer(inv -> {
			autoCommit.set(inv.getArgument(0));
			return null;
		}).when(con).setAutoCommit(anyBoolean());
		when(con.getAutoCommit()).thenAnswer(inv -> autoCommit.get());
		when(con.getNetworkTimeout()).thenReturn(30_000);
		Array emptyArray = mock(Array.class);
		when(con.createArrayOf(eq("TEXT"), any())).thenReturn(emptyArray);
		return con;
	}

	@Test
	public void deleteUserExtendsTimeoutsAndAbortsPoisonedConnectionOnSocketTimeout() throws Exception {
		AtomicBoolean autoCommit = new AtomicBoolean(true);
		Connection con = statefulConnection(autoCommit);

		PreparedStatement timeoutPs = mock(PreparedStatement.class);
		PreparedStatement lockLifecyclePs = mock(PreparedStatement.class);
		ResultSet lockLifecycleRs = mock(ResultSet.class);
		when(lockLifecyclePs.executeQuery()).thenReturn(lockLifecycleRs);
		when(lockLifecycleRs.next()).thenReturn(false);

		PreparedStatement userExistsPs = mock(PreparedStatement.class);
		ResultSet userExistsRs = mock(ResultSet.class);
		when(userExistsPs.executeQuery()).thenReturn(userExistsRs);
		when(userExistsRs.next()).thenReturn(true);

		PreparedStatement lockStoragePs = mock(PreparedStatement.class);
		PreparedStatement assertStoragePs = mock(PreparedStatement.class);
		PreparedStatement deleteUserPs = mock(PreparedStatement.class);
		when(deleteUserPs.execute()).thenThrow(new PSQLException(
				"An I/O error occurred while sending to the backend.",
				PSQLState.COMMUNICATION_ERROR,
				new SocketTimeoutException("Read timed out")));

		when(con.prepareStatement("SELECT set_config('statement_timeout', ?, true)")).thenReturn(timeoutPs);
		when(con.prepareStatement("SELECT starexec.LockUserJobLifecycle(?)")).thenReturn(lockLifecyclePs);
		when(con.prepareStatement("SELECT id FROM starexec.users WHERE id = ?")).thenReturn(userExistsPs);
		when(con.prepareStatement("SELECT starexec.lock_user_solver_storage_for_deletion(?)")).thenReturn(lockStoragePs);
		when(con.prepareStatement("SELECT starexec.assert_user_solver_storage_deletable(?,?)")).thenReturn(assertStoragePs);
		when(con.prepareStatement("SELECT starexec.DeleteUser(?)")).thenReturn(deleteUserPs);

		try (MockedStatic<Common> common = Mockito.mockStatic(Common.class, Mockito.CALLS_REAL_METHODS);
			 MockedStatic<Spaces> spaces = Mockito.mockStatic(Spaces.class);
			 MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class);
			 MockedStatic<Solvers> solvers = Mockito.mockStatic(Solvers.class);
			 MockedStatic<Benchmarks> benchmarks = Mockito.mockStatic(Benchmarks.class);
			 MockedStatic<R> constants = Mockito.mockStatic(R.class)) {
			common.when(Common::getConnection).thenReturn(con);
			spaces.when(() -> Spaces.getPersonalSpace(44)).thenReturn(null);
			jobs.when(() -> Jobs.getByUserId(44)).thenReturn(Collections.<Job>emptyList());
			solvers.when(() -> Solvers.getByUser(44)).thenReturn(Collections.<Solver>emptyList());
			benchmarks.when(() -> Benchmarks.getByUser(44)).thenReturn(Collections.<Benchmark>emptyList());
			constants.when(R::getSolverPath).thenReturn("/nonexistent/solvers");
			constants.when(R::getBenchmarkPath).thenReturn("/nonexistent/benchmarks");
			constants.when(R::getPicturePath).thenReturn("/nonexistent/pictures");

			boolean result = Users.deleteUser(44);
			assertFalse(result);

			var order = inOrder(con, timeoutPs, deleteUserPs);
			order.verify(con).setNetworkTimeout(any(Executor.class), eq(180_000));
			order.verify(con).setAutoCommit(false);
			order.verify(timeoutPs).setString(1, "150s");
			order.verify(timeoutPs).execute();
			order.verify(deleteUserPs).execute();

			verify(deleteUserPs).cancel();
			verify(con, never()).rollback();
			verify(con).abort(any(Executor.class));
			verify(con, never()).setNetworkTimeout(any(Executor.class), eq(30_000));
			verify(con, Mockito.atLeastOnce()).close();
		}
	}

	@Test
	public void deleteUserFallsBackTo25sStatementTimeoutAndRestoresNetworkTimeoutOnSuccess() throws Exception {
		AtomicBoolean autoCommit = new AtomicBoolean(true);
		Connection con = statefulConnection(autoCommit);
		doThrow(new SQLFeatureNotSupportedExceptionSafe()).when(con).setNetworkTimeout(any(), anyInt());

		PreparedStatement timeoutPs = mock(PreparedStatement.class);
		PreparedStatement lockLifecyclePs = mock(PreparedStatement.class);
		ResultSet lockLifecycleRs = mock(ResultSet.class);
		when(lockLifecyclePs.executeQuery()).thenReturn(lockLifecycleRs);
		when(lockLifecycleRs.next()).thenReturn(false);

		PreparedStatement userExistsPs = mock(PreparedStatement.class);
		ResultSet userExistsRs = mock(ResultSet.class);
		when(userExistsPs.executeQuery()).thenReturn(userExistsRs);
		when(userExistsRs.next()).thenReturn(true);

		PreparedStatement lockStoragePs = mock(PreparedStatement.class);
		PreparedStatement assertStoragePs = mock(PreparedStatement.class);
		PreparedStatement deleteUserPs = mock(PreparedStatement.class);

		when(con.prepareStatement("SELECT set_config('statement_timeout', ?, true)")).thenReturn(timeoutPs);
		when(con.prepareStatement("SELECT starexec.LockUserJobLifecycle(?)")).thenReturn(lockLifecyclePs);
		when(con.prepareStatement("SELECT id FROM starexec.users WHERE id = ?")).thenReturn(userExistsPs);
		when(con.prepareStatement("SELECT starexec.lock_user_solver_storage_for_deletion(?)")).thenReturn(lockStoragePs);
		when(con.prepareStatement("SELECT starexec.assert_user_solver_storage_deletable(?,?)")).thenReturn(assertStoragePs);
		when(con.prepareStatement("SELECT starexec.DeleteUser(?)")).thenReturn(deleteUserPs);

		try (MockedStatic<Common> common = Mockito.mockStatic(Common.class, Mockito.CALLS_REAL_METHODS);
			 MockedStatic<Spaces> spaces = Mockito.mockStatic(Spaces.class);
			 MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class);
			 MockedStatic<Solvers> solvers = Mockito.mockStatic(Solvers.class);
			 MockedStatic<Benchmarks> benchmarks = Mockito.mockStatic(Benchmarks.class);
			 MockedStatic<R> constants = Mockito.mockStatic(R.class)) {
			common.when(Common::getConnection).thenReturn(con);
			spaces.when(() -> Spaces.getPersonalSpace(50)).thenReturn(null);
			jobs.when(() -> Jobs.getByUserId(50)).thenReturn(Collections.<Job>emptyList());
			solvers.when(() -> Solvers.getByUser(50)).thenReturn(Collections.<Solver>emptyList());
			benchmarks.when(() -> Benchmarks.getByUser(50)).thenReturn(Collections.<Benchmark>emptyList());
			constants.when(R::getSolverPath).thenReturn("/nonexistent/solvers");
			constants.when(R::getBenchmarkPath).thenReturn("/nonexistent/benchmarks");
			constants.when(R::getPicturePath).thenReturn("/nonexistent/pictures");

			boolean result = Users.deleteUser(50);
			assertTrue(result);

			verify(timeoutPs).setString(1, "25s");
			verify(con).commit();
			verify(con, never()).abort(any(Executor.class));
			verify(con, Mockito.atLeastOnce()).close();
		}
	}

	private static final class SQLFeatureNotSupportedExceptionSafe extends SQLException {
		private static final long serialVersionUID = 1L;
	}
}
