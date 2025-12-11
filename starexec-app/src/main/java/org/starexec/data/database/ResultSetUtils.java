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
 */
public final class ResultSetUtils {
	private static final StarLogger log = StarLogger.getLogger(ResultSetUtils.class);

	private ResultSetUtils() {
	}

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
			String normalized = normalize(candidate);
			Integer columnIndex = columnsByName.get(normalized);
			if (columnIndex != null) {
				String actualLabel = metaData.getColumnLabel(columnIndex);
				if (!candidate.equals(actualLabel)) {
					log.debug(String.format("ResultSetUtils resolved column '%s' to actual label '%s'", candidate, actualLabel));
				}
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
