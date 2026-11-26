package org.starexec.data.database;

import org.apache.tomcat.jdbc.pool.PooledConnection;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CommonLongOperationTimeoutTest {

	private Connection statefulConnection(AtomicBoolean autoCommit) throws SQLException {
		Connection con = mock(Connection.class);
		PooledConnection pooled = mock(PooledConnection.class);
		when(con.unwrap(PooledConnection.class)).thenReturn(pooled);
		doAnswer(inv -> {
			autoCommit.set(inv.getArgument(0));
			return null;
		}).when(con).setAutoCommit(anyBoolean());
		when(con.getAutoCommit()).thenAnswer(inv -> autoCommit.get());
		when(con.getNetworkTimeout()).thenReturn(30_000);
		return con;
	}

	@Test
	public void inLongTransactionExtendsTimeoutsCommitsAndRestores() throws Exception {
		AtomicBoolean autoCommit = new AtomicBoolean(true);
		Connection con = statefulConnection(autoCommit);
		PreparedStatement stmtTimeoutPs = mock(PreparedStatement.class);
		PreparedStatement idleTimeoutPs = mock(PreparedStatement.class);
		when(con.prepareStatement("SELECT set_config('statement_timeout', ?, true)")).thenReturn(stmtTimeoutPs);
		when(con.prepareStatement("SELECT set_config('idle_in_transaction_session_timeout', ?, true)"))
				.thenReturn(idleTimeoutPs);

		try (MockedStatic<Common> common = Mockito.mockStatic(Common.class, Mockito.CALLS_REAL_METHODS)) {
			common.when(Common::getConnection).thenReturn(con);

			String res = Common.inLongTransaction(c -> "ok");
			assertEquals("ok", res);

			var order = inOrder(con, stmtTimeoutPs, idleTimeoutPs);
			order.verify(con).setNetworkTimeout(any(Executor.class), eq(Common.LONG_OPERATION_SOCKET_TIMEOUT_MS));
			order.verify(con).setAutoCommit(false);
			order.verify(stmtTimeoutPs).setString(1, Common.LONG_OPERATION_STATEMENT_TIMEOUT_EXTENDED);
			order.verify(stmtTimeoutPs).execute();
			order.verify(idleTimeoutPs).setString(1, Common.LONG_OPERATION_IDLE_TX_TIMEOUT);
			order.verify(idleTimeoutPs).execute();
			order.verify(con).commit();
			order.verify(con).setAutoCommit(true);
			order.verify(con).setNetworkTimeout(any(Executor.class), eq(30_000));
			verify(con, never()).abort(any(Executor.class));
			verify(con).close();
		}
	}

	@Test
	public void inLongTransactionAbortsAndDiscardsConnectionOnSocketTimeout() throws Exception {
		AtomicBoolean autoCommit = new AtomicBoolean(true);
		Connection con = statefulConnection(autoCommit);
		PooledConnection pooled = con.unwrap(PooledConnection.class);
		PreparedStatement stmtTimeoutPs = mock(PreparedStatement.class);
		PreparedStatement idleTimeoutPs = mock(PreparedStatement.class);
		when(con.prepareStatement("SELECT set_config('statement_timeout', ?, true)")).thenReturn(stmtTimeoutPs);
		when(con.prepareStatement("SELECT set_config('idle_in_transaction_session_timeout', ?, true)"))
				.thenReturn(idleTimeoutPs);

		try (MockedStatic<Common> common = Mockito.mockStatic(Common.class, Mockito.CALLS_REAL_METHODS)) {
			common.when(Common::getConnection).thenReturn(con);

			assertThrows(SQLException.class, () -> Common.inLongTransaction(c -> {
				throw new PSQLException("Read timed out", PSQLState.COMMUNICATION_ERROR,
						new SocketTimeoutException("Read timed out"));
			}));

			verify(con, never()).rollback();
			verify(pooled).setDiscarded(true);
			verify(con).abort(any(Executor.class));
			verify(con, never()).setNetworkTimeout(any(Executor.class), eq(30_000));
			verify(con).close();
		}
	}

	@Test
	public void withLongConnectionResetsSessionTimeoutAndDiscardsOnSocketTimeout() throws Exception {
		AtomicBoolean autoCommit = new AtomicBoolean(true);
		Connection con = statefulConnection(autoCommit);
		PreparedStatement sessionTimeoutPs = mock(PreparedStatement.class);
		Statement resetStmt = mock(Statement.class);
		when(con.prepareStatement("SELECT set_config('statement_timeout', ?, false)")).thenReturn(sessionTimeoutPs);
		when(con.createStatement()).thenReturn(resetStmt);

		try (MockedStatic<Common> common = Mockito.mockStatic(Common.class, Mockito.CALLS_REAL_METHODS)) {
			common.when(Common::getConnection).thenReturn(con);

			Integer value = Common.withLongConnection(c -> 42);
			assertEquals(42, value);

			verify(sessionTimeoutPs).setString(1, Common.LONG_OPERATION_STATEMENT_TIMEOUT_EXTENDED);
			verify(resetStmt).execute("RESET statement_timeout");
			verify(con).setNetworkTimeout(any(Executor.class), eq(30_000));
			verify(con, never()).abort(any(Executor.class));
		}

		Connection timedOutCon = statefulConnection(new AtomicBoolean(true));
		PooledConnection timedOutPooled = timedOutCon.unwrap(PooledConnection.class);
		PreparedStatement timedOutPs = mock(PreparedStatement.class);
		when(timedOutCon.prepareStatement("SELECT set_config('statement_timeout', ?, false)")).thenReturn(timedOutPs);

		try (MockedStatic<Common> common = Mockito.mockStatic(Common.class, Mockito.CALLS_REAL_METHODS)) {
			common.when(Common::getConnection).thenReturn(timedOutCon);

			assertThrows(SQLException.class, () -> Common.withLongConnection(c -> {
				throw new PSQLException("Read timed out", PSQLState.COMMUNICATION_ERROR,
						new SocketTimeoutException("Read timed out"));
			}));

			verify(timedOutCon, never()).createStatement();
			verify(timedOutPooled).setDiscarded(true);
			verify(timedOutCon).abort(any(Executor.class));
		}
	}
}
