package org.starexec.data.database;

import org.junit.Test;
import org.mockito.Mockito;
import org.starexec.data.to.Benchmark;

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

public class BenchmarksResultSetMappingTest {

	@Test
	public void resultToBenchmarkWithPrefixResolvesDetailedPairAliases() throws Exception {
		// Tests resolution against GetBenchmarkById RETURNS TABLE columns (bench_* prefix)
		Timestamp uploaded = Timestamp.valueOf("2026-03-26 15:56:54");
		ResultSet results = mockResultSet(new LinkedHashMap<String, Object>() {{
			put("bench_id", 77);
			put("bench_user_id", 9);
			put("bench_name", "bench-a");
			put("bench_uploaded", uploaded);
			put("bench_path", "/tmp/bench-a");
			put("bench_description", "benchmark description");
			put("bench_downloadable", true);
			put("bench_disk_size", 1024L);
			put("bench_recycled", false);
			put("bench_deleted", false);
		}});

		Benchmark benchmark = Benchmarks.resultToBenchmarkWithPrefix(results, "bench");

		assertEquals(77, benchmark.getId());
		assertEquals(9, benchmark.getUserId());
		assertEquals("bench-a", benchmark.getName());
		assertEquals("benchmark description", benchmark.getDescription());
		assertEquals(uploaded, benchmark.getUploadDate());
		assertEquals("/tmp/bench-a", benchmark.getPath());
		assertTrue(benchmark.isDownloadable());
		assertFalse(benchmark.isDeleted());
		assertFalse(benchmark.isRecycled());
		assertEquals(1024L, benchmark.getDiskSize());
	}

	@Test
	public void resultToBenchmarkWithPrefixResolvesStandardUnderscoreAliases() throws Exception {
		Timestamp uploaded = Timestamp.valueOf("2026-03-26 15:56:55");
		ResultSet results = mockResultSet(new LinkedHashMap<String, Object>() {{
			put("bench_id", 88);
			put("bench_user_id", 5);
			put("bench_name", "bench-b");
			put("bench_uploaded", uploaded);
			put("bench_path", "/tmp/bench-b");
			put("bench_description", "bench details");
			put("bench_downloadable", false);
			put("bench_disk_size", 8192L);
			put("bench_recycled", true);
			put("bench_deleted", true);
		}});

		Benchmark benchmark = Benchmarks.resultToBenchmarkWithPrefix(results, "bench");

		assertEquals(88, benchmark.getId());
		assertEquals(5, benchmark.getUserId());
		assertEquals("bench-b", benchmark.getName());
		assertEquals(uploaded, benchmark.getUploadDate());
		assertEquals("/tmp/bench-b", benchmark.getPath());
		assertEquals("bench details", benchmark.getDescription());
		assertFalse(benchmark.isDownloadable());
		assertEquals(8192L, benchmark.getDiskSize());
		assertTrue(benchmark.isRecycled());
		assertTrue(benchmark.isDeleted());
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
