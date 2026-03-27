package org.starexec.data.database;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.starexec.logger.StarLogger;

/**
 * Utility helpers for resolving {@link ResultSet} columns without relying on brittle aliases.
 *
 * <p>Two usage patterns are supported:</p>
 * <ul>
 *   <li><b>Static methods</b> — convenience for single-row queries or low-frequency calls.
 *       The column label map is rebuilt per call (acceptable overhead).</li>
 *   <li><b>{@link ColumnIndex}</b> — for hot loops ({@code while(results.next())}). The map
 *       is built once at construction and reused across all rows. Callers control its
 *       lifecycle explicitly (stack-scoped, no GC dependency, no synchronization).</li>
 * </ul>
 */
public final class ResultSetUtils {
	private static final StarLogger log = StarLogger.getLogger(ResultSetUtils.class);

	private ResultSetUtils() {
	}

	/**
	 * Pre-computed column label → index mapping for a single {@link ResultSet}.
	 *
	 * <p>Construct one before entering your {@code while(results.next())} loop and
	 * let it fall out of scope when the loop exits. This is a plain, unsynchronized
	 * {@code HashMap} — no monitors, no GC dependency, deterministic lifecycle.</p>
	 *
	 * <pre>{@code
	 * ResultSetUtils.ColumnIndex cols = new ResultSetUtils.ColumnIndex(results);
	 * while (results.next()) {
	 *     int id = cols.getInt(results, "id");
	 *     String name = cols.getString(results, "name");
	 * }
	 * }</pre>
	 */
	public static final class ColumnIndex {
		private final Map<String, Integer> columns;

		public ColumnIndex(ResultSet results) throws SQLException {
			ResultSetMetaData metaData = results.getMetaData();
			columns = new HashMap<>();
			for (int i = 1; i <= metaData.getColumnCount(); i++) {
				String label = metaData.getColumnLabel(i);
				if (label == null || label.isBlank()) {
					label = metaData.getColumnName(i);
				}
				columns.put(normalize(label), i);
			}
		}

		public Integer getInt(ResultSet results, String... candidates) throws SQLException {
			int idx = resolve(candidates);
			int value = results.getInt(idx);
			return results.wasNull() ? null : value;
		}

		public String getString(ResultSet results, String... candidates) throws SQLException {
			int idx = resolve(candidates);
			return results.getString(idx);
		}

		public Boolean getBoolean(ResultSet results, String... candidates) throws SQLException {
			int idx = resolve(candidates);
			boolean value = results.getBoolean(idx);
			return results.wasNull() ? null : value;
		}

		public Double getDouble(ResultSet results, String... candidates) throws SQLException {
			int idx = resolve(candidates);
			double value = results.getDouble(idx);
			return results.wasNull() ? null : value;
		}

		public Long getLong(ResultSet results, String... candidates) throws SQLException {
			int idx = resolve(candidates);
			long value = results.getLong(idx);
			return results.wasNull() ? null : value;
		}

		public Timestamp getTimestamp(ResultSet results, String... candidates) throws SQLException {
			int idx = resolve(candidates);
			return results.getTimestamp(idx);
		}

		private int resolve(String... candidates) throws SQLException {
			for (String candidate : candidates) {
				if (candidate == null || candidate.isBlank()) {
					continue;
				}
				Integer columnIndex = columns.get(normalize(candidate));
				if (columnIndex != null) {
					return columnIndex;
				}
			}
			log.warn(String.format("ResultSet column not found. Candidates=%s Available=%s", Arrays.toString(candidates), columns.keySet()));
			throw new SQLException("Column not found for candidates " + Arrays.toString(candidates));
		}
	}

	// --- Static convenience methods (rebuild map per call; fine for single-row queries) ---

	public static Integer getInt(ResultSet results, String... candidates) throws SQLException {
		int columnIndex = resolveColumnIndex(results, candidates);
		int value = results.getInt(columnIndex);
		return results.wasNull() ? null : value;
	}

	public static String getString(ResultSet results, String... candidates) throws SQLException {
		int columnIndex = resolveColumnIndex(results, candidates);
		return results.getString(columnIndex);
	}

	public static Boolean getBoolean(ResultSet results, String... candidates) throws SQLException {
		int columnIndex = resolveColumnIndex(results, candidates);
		boolean value = results.getBoolean(columnIndex);
		return results.wasNull() ? null : value;
	}

	public static Double getDouble(ResultSet results, String... candidates) throws SQLException {
		int columnIndex = resolveColumnIndex(results, candidates);
		double value = results.getDouble(columnIndex);
		return results.wasNull() ? null : value;
	}

	public static Long getLong(ResultSet results, String... candidates) throws SQLException {
		int columnIndex = resolveColumnIndex(results, candidates);
		long value = results.getLong(columnIndex);
		return results.wasNull() ? null : value;
	}

	public static Timestamp getTimestamp(ResultSet results, String... candidates) throws SQLException {
		int columnIndex = resolveColumnIndex(results, candidates);
		return results.getTimestamp(columnIndex);
	}

	private static int resolveColumnIndex(ResultSet results, String... candidates) throws SQLException {
		ResultSetMetaData metaData = results.getMetaData();
		Map<String, Integer> columnsByName = new HashMap<>();
		for (int i = 1; i <= metaData.getColumnCount(); i++) {
			String label = metaData.getColumnLabel(i);
			if (label == null || label.isBlank()) {
				label = metaData.getColumnName(i);
			}
			columnsByName.put(normalize(label), i);
		}

		for (String candidate : candidates) {
			if (candidate == null || candidate.isBlank()) {
				continue;
			}
			Integer columnIndex = columnsByName.get(normalize(candidate));
			if (columnIndex != null) {
				return columnIndex;
			}
		}

		log.warn(String.format("ResultSet column not found. Candidates=%s Available=%s", Arrays.toString(candidates), columnsByName.keySet()));
		throw new SQLException("Column not found for candidates " + Arrays.toString(candidates));
	}

	private static String normalize(String value) {
		return value.toLowerCase(Locale.ROOT);
	}
}
