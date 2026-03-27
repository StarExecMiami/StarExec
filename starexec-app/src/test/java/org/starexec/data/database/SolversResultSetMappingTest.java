package org.starexec.data.database;

import org.junit.Test;
import org.mockito.Mockito;
import org.starexec.data.to.Solver;
import org.starexec.data.to.SolverBuildStatus;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;

public class SolversResultSetMappingTest {

	@Test
	public void resultSetToSolverResolvesQuotedDotColumnsForConflictingResults() throws Exception {
		// Tests resolution against GetSolverConfigResultsForBenchmarkInJob RETURNS TABLE ("s." prefix)
		Timestamp uploaded = Timestamp.valueOf("2026-03-26 15:56:54");
		ResultSet results = mockResultSet(new LinkedHashMap<String, Object>() {{
			put("s.id", 42);
			put("s.user_id", 7);
			put("s.name", "solver-a");
			put("s.description", "matrix description");
			put("s.uploaded", uploaded);
			put("s.path", "/tmp/solver-a");
			put("s.downloadable", true);
			put("s.disk_size", 2048L);
			put("executable_type", 0);
			put("recycled", false);
			put("deleted", false);
			put("s.build_status", 1);
		}});

		Solver solver = Solvers.resultSetToSolver(results, "s");

		assertEquals(42, solver.getId());
		assertEquals(7, solver.getUserId());
		assertEquals("solver-a", solver.getName());
		assertEquals("matrix description", solver.getDescription());
		assertEquals(uploaded, solver.getUploadDate());
		assertEquals("/tmp/solver-a", solver.getPath());
		assertTrue(solver.isDownloadable());
		assertFalse(solver.isDeleted());
		assertFalse(solver.isRecycled());
		assertEquals(2048L, solver.getDiskSize());
		assertEquals(SolverBuildStatus.SolverBuildStatusCode.BUILT, solver.buildStatus().getCode());
	}

	@Test
	public void resultSetToSolverResolvesBarePrefixForDirectQueries() throws Exception {
		// Tests resolution with empty prefix (direct solver queries like GetSolverById)
		Timestamp uploaded = Timestamp.valueOf("2026-03-26 15:56:56");
		ResultSet results = mockResultSet(new LinkedHashMap<String, Object>() {{
			put("id", 63);
			put("user_id", 14);
			put("name", "solver-c");
			put("uploaded", uploaded);
			put("path", "/tmp/solver-c");
			put("description", "test desc");
			put("downloadable", false);
			put("disk_size", 0L);
			put("executable_type", null);
			put("recycled", null);
			put("deleted", null);
			put("build_status", 3);
		}});

		Solver solver = Solvers.resultSetToSolver(results, "");

		assertEquals(63, solver.getId());
		assertEquals(14, solver.getUserId());
		assertEquals("solver-c", solver.getName());
		assertEquals(uploaded, solver.getUploadDate());
		assertEquals(SolverBuildStatus.SolverBuildStatusCode.BUILD_FAILED, solver.buildStatus().getCode());
	}

	@Test
	public void resultSetToSolverResolvesQuotedDottedColumnsForPendingPairs() throws Exception {
		Timestamp uploaded = Timestamp.valueOf("2026-03-26 15:56:55");
		ResultSet results = mockResultSet(new LinkedHashMap<String, Object>() {{
			put("solvers.id", 84);
			put("solvers.user_id", 12);
			put("solvers.name", "solver-b");
			put("solvers.uploaded", uploaded);
			put("solvers.path", "/tmp/solver-b");
			put("solvers.description", "pending description");
			put("solvers.downloadable", false);
			put("solvers.disk_size", 4096L);
			put("executable_type", 1);
			put("recycled", true);
			put("deleted", true);
			put("build_status", 2);
		}});

		Solver solver = Solvers.resultSetToSolver(results, "solvers");

		assertEquals(84, solver.getId());
		assertEquals(12, solver.getUserId());
		assertEquals("solver-b", solver.getName());
		assertEquals(uploaded, solver.getUploadDate());
		assertEquals("/tmp/solver-b", solver.getPath());
		assertEquals("pending description", solver.getDescription());
		assertFalse(solver.isDownloadable());
		assertEquals(4096L, solver.getDiskSize());
		assertTrue(solver.isRecycled());
		assertTrue(solver.isDeleted());
		assertEquals(Solver.ExecutableType.SOLVER, solver.getType());
		assertEquals(SolverBuildStatus.SolverBuildStatusCode.BUILT_BY_STAREXEC, solver.buildStatus().getCode());
	}

	private ResultSet mockResultSet(LinkedHashMap<String, Object> columns) throws Exception {
		ResultSet results = Mockito.mock(ResultSet.class);
		ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
		List<Map.Entry<String, Object>> entries = new ArrayList<>(columns.entrySet());
		AtomicBoolean wasNull = new AtomicBoolean(false);

		Mockito.when(results.getMetaData()).thenReturn(metaData);
		Mockito.when(metaData.getColumnCount()).thenReturn(entries.size());
		for (int i = 0; i < entries.size(); i++) {
			int columnIndex = i + 1;
			String label = entries.get(i).getKey();
			Mockito.when(metaData.getColumnLabel(columnIndex)).thenReturn(label);
			Mockito.when(metaData.getColumnName(columnIndex)).thenReturn(label);
		}

		Mockito.when(results.wasNull()).thenAnswer(invocation -> wasNull.get());
		Mockito.when(results.getInt(anyInt())).thenAnswer(invocation -> {
			Object value = entries.get(invocation.getArgument(0, Integer.class) - 1).getValue();
			wasNull.set(value == null);
			return value == null ? 0 : ((Number) value).intValue();
		});
		Mockito.when(results.getLong(anyInt())).thenAnswer(invocation -> {
			Object value = entries.get(invocation.getArgument(0, Integer.class) - 1).getValue();
			wasNull.set(value == null);
			return value == null ? 0L : ((Number) value).longValue();
		});
		Mockito.when(results.getBoolean(anyInt())).thenAnswer(invocation -> {
			Object value = entries.get(invocation.getArgument(0, Integer.class) - 1).getValue();
			wasNull.set(value == null);
			return value != null && (Boolean) value;
		});
		Mockito.when(results.getString(anyInt())).thenAnswer(invocation -> {
			Object value = entries.get(invocation.getArgument(0, Integer.class) - 1).getValue();
			wasNull.set(value == null);
			return value == null ? null : value.toString();
		});
		Mockito.when(results.getTimestamp(anyInt())).thenAnswer(invocation -> {
			Object value = entries.get(invocation.getArgument(0, Integer.class) - 1).getValue();
			wasNull.set(value == null);
			return (Timestamp) value;
		});

		return results;
	}
}
