package org.starexec.data.database;

import org.apache.tomcat.jdbc.pool.PooledConnection;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class UploadArtifactCleanupRepositoryTest {

	@Test
	public void releaseCleanupLockDiscardsConnectionOnUnlockSqlFailure() throws Exception {
		Connection con = mock(Connection.class);
		PooledConnection pooled = mock(PooledConnection.class);
		when(con.unwrap(PooledConnection.class)).thenReturn(pooled);
		PreparedStatement unlockPs = mock(PreparedStatement.class);
		when(con.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(unlockPs);
		when(unlockPs.executeQuery()).thenThrow(new SQLException("connection broken"));

		UploadArtifactCleanupRepository.releaseCleanupLock(con);

		verify(pooled).setDiscarded(true);
		verify(con).abort(any(Executor.class));
	}

	@Test
	public void withCleanupLockDiscardsConnectionWhenWorkTimesOut() throws Exception {
		Connection con = mock(Connection.class);
		PooledConnection pooled = mock(PooledConnection.class);
		when(con.unwrap(PooledConnection.class)).thenReturn(pooled);

		PreparedStatement lockPs = mock(PreparedStatement.class);
		ResultSet lockRs = mock(ResultSet.class);
		when(lockRs.next()).thenReturn(true);
		when(lockRs.getBoolean(1)).thenReturn(true);
		when(lockPs.executeQuery()).thenReturn(lockRs);
		when(con.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(lockPs);

		PreparedStatement unlockPs = mock(PreparedStatement.class);
		when(con.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(unlockPs);

		try (MockedStatic<Common> common = Mockito.mockStatic(Common.class, Mockito.CALLS_REAL_METHODS)) {
			common.when(Common::getConnection).thenReturn(con);

			assertThrows(SQLException.class, () -> UploadArtifactCleanupRepository.withCleanupLock(c -> {
				throw new SQLException("timed out", new SocketTimeoutException("Read timed out"));
			}));

			verify(unlockPs, never()).executeQuery();
			verify(pooled).setDiscarded(true);
			verify(con).abort(any(Executor.class));
			verify(con).close();
		}
	}
}
