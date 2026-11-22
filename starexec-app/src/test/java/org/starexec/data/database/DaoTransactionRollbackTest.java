package org.starexec.data.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.AnonymousLinks.PrimitivesToAnonymize;
import org.starexec.data.to.Benchmark;
import org.starexec.data.to.Processor;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DaoTransactionRollbackTest {

	@TempDir
	public File tmp;

	private Connection statefulConnection(AtomicBoolean autoCommit) throws SQLException {
		Connection con = mock(Connection.class);
		doAnswer(inv -> {
			autoCommit.set(inv.getArgument(0));
			return null;
		}).when(con).setAutoCommit(anyBoolean());
		when(con.getAutoCommit()).thenAnswer(inv -> autoCommit.get());
		return con;
	}

	@Test
	public void getAnonymousLinkCodeExecutesSelectOnceWithoutTransaction() throws Exception {
		AtomicBoolean autoCommit = new AtomicBoolean(true);
		Connection con = statefulConnection(autoCommit);
		PreparedStatement ps = mock(PreparedStatement.class);
		ResultSet rs = mock(ResultSet.class);

		when(con.prepareStatement(anyString())).thenReturn(ps);
		when(ps.executeQuery()).thenReturn(rs);
		when(rs.next()).thenReturn(true);
		when(rs.getString("unique_id")).thenReturn("uuid-123");

		try (MockedStatic<Common> common = Mockito.mockStatic(Common.class, Mockito.CALLS_REAL_METHODS)) {
			common.when(Common::getConnection).thenReturn(con);

			Optional<String> code = AnonymousLinks.getAnonymousLinkCode("job", 42, PrimitivesToAnonymize.NONE);

			assertEquals(Optional.of("uuid-123"), code);
			verify(ps, times(1)).executeQuery();
			verify(con, never()).setAutoCommit(false);
			verify(con, never()).commit();
			verify(con, never()).rollback();
			verify(con).close();
			verify(ps).close();
			verify(rs).close();
		}
	}

	@Test
	public void setQueueCommunityAccessRollsBackOnFailure() throws Exception {
		AtomicBoolean autoCommit = new AtomicBoolean(true);
		Connection con = statefulConnection(autoCommit);
		PreparedStatement ps = mock(PreparedStatement.class);

		when(con.prepareStatement(anyString())).thenReturn(ps);
		when(ps.getUpdateCount()).thenReturn(-1);
		when(ps.execute()).thenReturn(false).thenThrow(new SQLException("boom"));

		try (MockedStatic<Common> common = Mockito.mockStatic(Common.class, Mockito.CALLS_REAL_METHODS)) {
			common.when(Common::getConnection).thenReturn(con);

			boolean ok = Queues.setQueueCommunityAccess(Arrays.asList(1, 2), 9);

			assertFalse(ok);
			assertTrue(autoCommit.get(), "autoCommit must be restored after rollback");
			verify(con, times(1)).rollback();
			verify(con, never()).commit();
			verify(con, Mockito.atLeastOnce()).close();
		}
	}

	@Test
	public void benchmarksAddRollsBackWhenAttributesAreInvalid() throws Exception {
		File benchFile = new File(tmp, "b.smt2");
		Files.writeString(benchFile.toPath(), "(set-logic QF_LIA)\n");

		Benchmark b = new Benchmark();
		b.setName("b.smt2");
		b.setPath(benchFile.getAbsolutePath());
		b.setUserId(7);
		Processor type = new Processor();
		type.setId(1);
		b.setType(type);
		b.setAttributes(new HashMap<>()); // missing valid attribute triggers abort

		AtomicBoolean autoCommit = new AtomicBoolean(true);
		Connection con = statefulConnection(autoCommit);
		PreparedStatement ps = mock(PreparedStatement.class);
		ResultSet rs = mock(ResultSet.class);

		when(con.prepareStatement(anyString())).thenReturn(ps);
		when(ps.executeQuery()).thenReturn(rs);
		when(rs.next()).thenReturn(true);
		when(rs.getInt(1)).thenReturn(88);

		try (MockedStatic<Uploads> uploads = Mockito.mockStatic(Uploads.class)) {
			Benchmark added = Benchmarks.add(b, null, con);

			assertNull(added);
			assertTrue(autoCommit.get(), "autoCommit must be restored on validation abort");
			verify(con, times(1)).rollback();
			verify(con, never()).commit();
			verify(rs).close();
			verify(ps).close();
		}
	}

	@Test
	public void spacesRemoveBenchesAndJobsAssociateRollBackOnInnerSqlFailure() throws Exception {
		AtomicBoolean autoCommit = new AtomicBoolean(true);
		Connection con = statefulConnection(autoCommit);
		PreparedStatement ps = mock(PreparedStatement.class);

		when(con.prepareStatement(anyString())).thenReturn(ps);
		when(ps.getUpdateCount()).thenReturn(-1);
		when(ps.execute()).thenReturn(false).thenThrow(new SQLException("inner failure"));

		try (MockedStatic<Common> common = Mockito.mockStatic(Common.class, Mockito.CALLS_REAL_METHODS)) {
			common.when(Common::getConnection).thenReturn(con);

			assertFalse(Spaces.removeBenches(Arrays.asList(1, 2), 5));
			assertTrue(autoCommit.get());
			verify(con, times(1)).rollback();
			verify(con, never()).commit();

			assertFalse(Jobs.associate(Arrays.asList(10, 11), 5));
			assertTrue(autoCommit.get());
			verify(con, times(2)).rollback();
			verify(con, never()).commit();
		}
	}

	@Test
	public void commonUpdateAndRunInTransactionDoNotSwallowCommitOrPoisonPoolOnRestoreFailure() throws Exception {
		AtomicBoolean autoCommit = new AtomicBoolean(true);
		Connection con = statefulConnection(autoCommit);
		java.sql.CallableStatement cs = mock(java.sql.CallableStatement.class);
		when(con.prepareCall(anyString())).thenReturn(cs);
		doThrow(new SQLException("commit failed")).when(con).commit();

		try (MockedStatic<Common> common = Mockito.mockStatic(Common.class, Mockito.CALLS_REAL_METHODS)) {
			common.when(Common::getConnection).thenReturn(con);

			assertThrows(SQLException.class, () -> Common.update("{CALL test_proc()}", stmt -> {}));
			verify(con, times(1)).rollback();
			assertTrue(autoCommit.get());
		}

		// Commit succeeds, then setAutoCommit(true) fails -> connection must be aborted (discarded) and NOT rolled back
		AtomicBoolean autoCommit2 = new AtomicBoolean(true);
		Connection con2 = mock(Connection.class);
		doAnswer(inv -> {
			boolean target = inv.getArgument(0);
			if (target && !autoCommit2.get()) {
				throw new SQLException("restore autoCommit failed");
			}
			autoCommit2.set(target);
			return null;
		}).when(con2).setAutoCommit(anyBoolean());
		when(con2.getAutoCommit()).thenAnswer(inv -> autoCommit2.get());

		try (MockedStatic<Common> common = Mockito.mockStatic(Common.class, Mockito.CALLS_REAL_METHODS)) {
			common.when(Common::getConnection).thenReturn(con2);

			String res = Common.runInTransaction(c -> "ok");
			assertEquals("ok", res);
			verify(con2, times(1)).commit();
			verify(con2, never()).rollback();
			verify(con2, times(1)).abort(any());
		}
	}

	@Test
	public void uploadChunksAndPairStatusPropagateCommitFailuresInsteadOfReturningTrue() throws Exception {
		AtomicBoolean autoCommit = new AtomicBoolean(true);
		Connection con = statefulConnection(autoCommit);
		PreparedStatement ps = mock(PreparedStatement.class);
		when(ps.getUpdateCount()).thenReturn(-1);
		when(ps.executeUpdate()).thenReturn(1);
		when(con.prepareStatement(anyString())).thenReturn(ps);
		doThrow(new SQLException("commit failed")).when(con).commit();

		try (MockedStatic<Common> common = Mockito.mockStatic(Common.class, Mockito.CALLS_REAL_METHODS)) {
			common.when(Common::getConnection).thenReturn(con);

			assertFalse(UploadSessions.recordChunkIfAbsent(55L, 0, 1024));
			assertTrue(autoCommit.get());
			verify(con, times(1)).rollback();

			assertFalse(JobPairs.setPairStatus(77, 1));
			assertTrue(autoCommit.get());
			verify(con, times(2)).rollback();
		}
	}
}
