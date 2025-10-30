package org.starexec.data.database;

import org.starexec.data.to.Benchmark;
import org.starexec.data.to.DefaultSettings;
import org.starexec.data.to.DefaultSettings.SettingType;
import org.starexec.data.to.Space;
import org.starexec.data.to.enums.BenchmarkingFramework;
import org.starexec.logger.StarLogger;

import java.sql.PreparedStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class Settings {
	private static final StarLogger log = StarLogger.getLogger(Settings.class);

	public static int addNewSettingsProfile(DefaultSettings settings) {
		Connection con = null;
			PreparedStatement ps = null;
		try {
			con = Common.getConnection();
				ps = con.prepareStatement("SELECT starexec.CreateDefaultSettings(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
				ps.setInt(1, settings.getPrimId());
				ps.setObject(2, settings.getPostProcessorId());
				ps.setInt(3, settings.getCpuTimeout());
				ps.setInt(4, settings.getWallclockTimeout());
				ps.setBoolean(5, settings.isDependenciesEnabled());
				ps.setLong(6, settings.getMaxMemory()); //memory initialized to 1 gigabyte
				ps.setObject(7, settings.getSolverId());
				ps.setObject(8, settings.getBenchProcessorId());
				ps.setObject(9, settings.getPreProcessorId());
				ps.setInt(10, settings.getType().getValue());
				ps.setString(11, settings.getName());
				ps.setString(12, settings.getBenchmarkingFramework().toString());
				ResultSet rs = ps.executeQuery();
				if (rs.next()) {
					settings.setId(rs.getInt(1));
				}

			for (Integer benchId : settings.getBenchIds()) {
				addDefaultBenchmark(settings.getId(), benchId);
			}

			return settings.getId();
			} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
				Common.safeClose(con);
				Common.safeClose(ps);
		}
		return -1;
	}

	/**
	 * Adds a default benchmark for a given setting.
	 *
	 * @param settingId the id of the setting to add a default benchmark to.
	 * @param benchId the id of the benchmark to add to the setting.
	 * @throws SQLException on database error.
	 */
	public static void addDefaultBenchmark(final int settingId, final int benchId) throws SQLException {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.AddDefaultBenchmark(?, ?)");
			ps.setInt(1, settingId);
			ps.setInt(2, benchId);
			ps.execute();
		} finally {
			Common.safeClose(ps);
			Common.safeClose(con);
		}
	}

	/**
	 * Deletes a default benchmark from a setting.
	 *
	 * @param settingId the id of the DefaultSetting
	 * @param benchId the id of the benchmark to delete.s
	 * @throws SQLException on database error.
	 */
	public static void deleteDefaultBenchmark(final int settingId, final int benchId) throws SQLException {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.DeleteDefaultBenchmark(?, ?)");
			ps.setInt(1, settingId);
			ps.setInt(2, benchId);
			ps.execute();
		} finally {
			Common.safeClose(ps);
			Common.safeClose(con);
		}
	}

	/**
	 * Deletes all the default benchmarks for a given setting.
	 *
	 * @param settingId the id of the setting for which to delete all default benchmarks.
	 * @throws SQLException on database error.
	 */
	protected static void deleteAllDefaultBenchmarks(final int settingId) throws SQLException {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.DeleteAllDefaultBenchmarks(?)");
			ps.setInt(1, settingId);
			ps.execute();
		} finally {
			Common.safeClose(ps);
			Common.safeClose(con);
		}
	}

	/**
	 * Deletes all the default benchmarks for a given setting using a Connection.
	 *
	 * @param con the database connections to use to call the procedure.
	 * @param settingId the id of the setting for which to delete all default benchmarks.
	 * @throws SQLException on database error.
	 */
	protected static void deleteAllDefaultBenchmarks(final Connection con, final int settingId) throws SQLException {
		PreparedStatement ps = null;
		try {
			ps = con.prepareStatement("SELECT starexec.DeleteAllDefaultBenchmarks(?)");
			ps.setInt(1, settingId);
			ps.execute();
		} finally {
			Common.safeClose(ps);
		}
	}

	/**
	 * Adds a default benchmark for a given setting using a connection.
	 *
	 * @param con the database connection to use for the update.
	 * @param settingId the id of the setting to add a default benchmark to.
	 * @param benchId the id of the benchmark to add to the setting.
	 * @throws SQLException on database error.
	 */
	protected static void addDefaultBenchmark(
			final Connection con, final int settingId, final int benchId
	) throws SQLException {
		PreparedStatement ps = null;
		try {
			ps = con.prepareStatement("SELECT starexec.AddDefaultBenchmark(?, ?)");
			ps.setInt(1, settingId);
			ps.setInt(2, benchId);
			ps.execute();
		} finally {
			Common.safeClose(ps);
		}
	}

	/**
	 * Gets all the default benchmarks for a given setting.
	 *
	 * @param settingId the id of the setting to get the default benchmarks for.
	 * @return all the default benchmarks for a setting.
	 * @throws SQLException on database error.
	 */
	public static List<Benchmark> getDefaultBenchmarks(final int settingId) throws SQLException {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetDefaultBenchmarksForSetting(?)");
			ps.setInt(1, settingId);
			results = ps.executeQuery();
			return Settings.resultsToBenchmarks(results);
		} finally {
			Common.safeClose(results);
			Common.safeClose(ps);
			Common.safeClose(con);
		}
	}

	/**
	 * Gets all the default benchmarks for a given setting using a Connection.
	 *
	 * @param con the connection to the database to use for the query.
	 * @param settingId the id of the setting to get the default benchmarks for.
	 * @return all the default benchmarks for a setting.
	 * @throws SQLException on database error.
	 */
	protected static List<Benchmark> getDefaultBenchmarks(Connection con, final int settingId) throws SQLException {
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			ps = con.prepareStatement("SELECT * FROM starexec.GetDefaultBenchmarksForSetting(?)");
			ps.setInt(1, settingId);
			results = ps.executeQuery();
			return Settings.resultsToBenchmarks(results);
		} finally {
			Common.safeClose(results);
			Common.safeClose(ps);
		}
	}

	private static List<Benchmark> resultsToBenchmarks(ResultSet results) throws SQLException {
		List<Benchmark> benchmarks = new ArrayList<>();

		while (results.next()) {
			benchmarks.add(Benchmarks.resultToBenchmark(results));
		}

		return benchmarks;
	}

	/**
	 * Gets the default benchmark ids for a given default setting.
	 *
	 * @param settingId the id of the setting to get the default benchmark ids for.
	 * @return a list of benchmark ids.
	 * @throws SQLException on database error.
	 */
	public static List<Integer> getDefaultBenchmarkIds(final int settingId) throws SQLException {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetDefaultBenchmarkIdsForSetting(?)");
			ps.setInt(1, settingId);
			results = ps.executeQuery();
			return Settings.resultsToBenchmarkIds(results);
		} finally {
			Common.safeClose(results);
			Common.safeClose(ps);
			Common.safeClose(con);
		}
	}

	/**
	 * Gets the default benchmark ids for a given default setting.
	 *
	 * @param settingId the id of the setting to get the default benchmark ids for.
	 * @return a list of benchmark ids.
	 * @throws SQLException on database error.
	 */
	public static List<Integer> getDefaultBenchmarkIds(Connection con, final int settingId) throws SQLException {
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			ps = con.prepareStatement("SELECT * FROM starexec.GetDefaultBenchmarkIdsForSetting(?)");
			ps.setInt(1, settingId);
			results = ps.executeQuery();
			return Settings.resultsToBenchmarkIds(results);
		} finally {
			Common.safeClose(results);
			Common.safeClose(ps);
		}
	}

	private static List<Integer> resultsToBenchmarkIds(ResultSet results) throws SQLException {
		List<Integer> benchmarkIds = new ArrayList<>();
		while (results.next()) {
			benchmarkIds.add(results.getInt("default_bench_id"));
		}
		return benchmarkIds;
	}

	/**
	 * Given a DefaultSettings object with all of its fields set, including id, updates the default settings profile in
	 * the database with all of the new fields. Does not update name, prim id, or type, which are immutable
	 *
	 * @param settings
	 * @return
	 */
	public static boolean updateDefaultSettings(DefaultSettings settings) {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			Common.beginTransaction(con);

			ps = con.prepareStatement("SELECT starexec.UpdateDefaultSettings(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
			ps.setObject(1, settings.getPostProcessorId());
			ps.setInt(2, settings.getCpuTimeout());
			ps.setInt(3, settings.getWallclockTimeout());
			ps.setBoolean(4, settings.isDependenciesEnabled());
			ps.setLong(5, settings.getMaxMemory()); //memory initialized to 1 gigabyte
			ps.setObject(6, settings.getSolverId());
			ps.setObject(7, settings.getBenchProcessorId());
			ps.setObject(8, settings.getPreProcessorId());
			ps.setString(9, settings.getBenchmarkingFramework().toString());
			ps.setInt(10, settings.getId());
			ps.execute();

			Settings.deleteAllDefaultBenchmarks(con, settings.getId());
			for (Integer bid : settings.getBenchIds()) {
				Settings.addDefaultBenchmark(con, settings.getId(), bid);
			}

			Common.endTransaction(con);
			return true;
		} catch (Exception e) {
			Common.doRollback(con);
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}
		return false;
	}

	/**
	 * Given an open ResultSet currently pointing to a row containing a DefaultSettings object, returns the object
	 *
	 * @param results
	 * @return
	 */
	protected static DefaultSettings resultsToSettings(ResultSet results) throws SQLException {
		final String methodName = "resultsToSettings";
		try {
			DefaultSettings settings = new DefaultSettings();
			settings.setId(results.getInt("id"));
			settings.setPreProcessorId(results.getInt("pre_processor"));
			if (results.wasNull()) {
				settings.setPreProcessorId(null);
			}
			settings.setWallclockTimeout(results.getInt("clock_timeout"));
			settings.setCpuTimeout(results.getInt("cpu_timeout"));
			settings.setPostProcessorId(results.getInt("post_processor"));
			if (results.wasNull()) {
				settings.setPostProcessorId(null);
			}
			settings.setDependenciesEnabled(results.getBoolean("dependencies_enabled"));
			settings.setSolverId(results.getInt("default_solver"));
			if (results.wasNull()) {
				settings.setSolverId(null);
			}
			settings.setBenchProcessorId(results.getInt("bench_processor"));
			if (results.wasNull()) {
				settings.setBenchProcessorId(null);
			}
			settings.setMaxMemory(results.getLong("maximum_memory"));
			settings.setName(results.getString("name"));
			settings.setPrimId(results.getInt("prim_id"));
			settings.setType(results.getInt("setting_type"));
			settings.setBenchmarkingFramework(
					BenchmarkingFramework.valueOf(results.getString("benchmarking_framework")));
			return settings;
		} catch (SQLException e) {
			log.error(methodName, "Caught SQL exception while getting results. Throwing...", e);
			throw e;
		}
	}

	/**
	 * Given the ID of a primitive and the type of that primitive (user or community), returns all of the
	 * defaultsettings profiles associated with that primitive
	 *
	 * @param id
	 * @param type
	 * @return
	 */
	public static List<DefaultSettings> getDefaultSettingsByPrimIdAndType(int id, SettingType type) {

		Connection con = null;

		try {
			con = Common.getConnection();
			return getDefaultSettingsByPrimIdAndType(con, id, type);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
		}
		return null; //error;
	}

	protected static List<DefaultSettings> getDefaultSettingsByPrimIdAndType(Connection con, int id, SettingType
			type) {

		PreparedStatement ps = null;
		ResultSet results = null;

		try {
			List<DefaultSettings> settings = new ArrayList<>();
			ps = con.prepareStatement("SELECT * FROM starexec.GetDefaultSettingsByIdAndType(?,?)");
			ps.setInt(1, id);
			ps.setInt(2, type.getValue());
			results = ps.executeQuery();
			while (results.next()) {
				DefaultSettings setting = resultsToSettings(results);
				setting.setBenchIds(Settings.getDefaultBenchmarkIds(con, setting.getId()));
				settings.add(setting);
			}
			return settings;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(ps);
			Common.safeClose(results);
		}
		return null; //error;
	}

	/**
	 * Returns every DefaultSettings profile a user has access to, either by community or individually
	 *
	 * @param userId
	 * @return
	 */
	public static List<DefaultSettings> getDefaultSettingsVisibleByUser(int userId) {
		List<DefaultSettings> listOfDefaultSettings = new ArrayList<>();
		List<Space> comms = Communities.getAllCommunitiesUserIsIn(userId);
		if (comms != null) {
			for (Space comm : comms) {
				DefaultSettings s = Communities.getDefaultSettings(comm.getId());
				if (s != null) {
					listOfDefaultSettings.add(s);
				}
			}
		}
		List<DefaultSettings> userSettings = Settings.getDefaultSettingsOwnedByUser(userId);
		if (userSettings != null) {
			listOfDefaultSettings.addAll(userSettings);
		}
		return listOfDefaultSettings;
	}

	public static List<DefaultSettings> getDefaultSettingsVisibleByUser(Connection con, int userId) {
		List<DefaultSettings> listOfDefaultSettings = new ArrayList<>();
		List<Space> comms = Communities.getAllCommunitiesUserIsIn(con, userId);
		if (comms != null) {
			for (Space comm : comms) {
				DefaultSettings s = Communities.getDefaultSettings(con, comm.getId());
				if (s != null) {
					listOfDefaultSettings.add(s);
				}
			}
		}
		List<DefaultSettings> userSettings = Settings.getDefaultSettingsOwnedByUser(con, userId);
		if (userSettings != null) {
			listOfDefaultSettings.addAll(userSettings);
		}
		return listOfDefaultSettings;
	}

	/**
	 * Gets all of the defaultSettings profiles that this user has
	 *
	 * @param userId
	 * @return
	 */
	public static List<DefaultSettings> getDefaultSettingsOwnedByUser(int userId) {
		return getDefaultSettingsByPrimIdAndType(userId, SettingType.USER);
	}

	protected static List<DefaultSettings> getDefaultSettingsOwnedByUser(Connection con, int userId) {
		return getDefaultSettingsByPrimIdAndType(con, userId, SettingType.USER);
	}

	/**
	 * Checks whether the given user has access to the given solver through a settings profile
	 *
	 * @param userId
	 * @param solverId
	 * @return
	 */
	public static boolean canUserSeeSolverInSettings(int userId, int solverId) {
		// final String methodName = "canUserSeeSolverInSettings";
		Connection con = null;
		try {
			con = Common.getConnection();
			return canUserSeeSolverInSettings(con, userId, solverId);
		} catch (Exception e) {
			log.error("Caught exception", e);
		} finally {
			Common.safeClose(con);
		}
		return false;
	}

	public static boolean canUserSeeSolverInSettings(Connection con, int userId, int solverId) {
		List<DefaultSettings> settings = Settings.getDefaultSettingsVisibleByUser(con, userId);
		for (DefaultSettings s : settings) {
			if (s.getSolverId() == null) {
				continue;
			}
			if (s.getSolverId() == solverId) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks whether the given user has access to the given benchmark through a settings profile
	 *
	 * @param userId
	 * @param benchId
	 * @return
	 */
	public static boolean canUserSeeBenchmarkInSettings(int userId, int benchId) {
		List<DefaultSettings> settings = Settings.getDefaultSettingsVisibleByUser(userId);
		for (DefaultSettings s : settings) {
			if (s.getBenchIds().isEmpty()) {
				continue;
			}
			if (s.getBenchIds().contains(benchId)) {
				return true;
			}
		}
		return false;
	}

	public static boolean canUserSeeBenchmarkInSettings(Connection con, int userId, int benchId) {
		List<DefaultSettings> settings = Settings.getDefaultSettingsVisibleByUser(con, userId);
		for (DefaultSettings s : settings) {
			if (s.getBenchIds().isEmpty()) {
				continue;
			}
			if (s.getBenchIds().contains(benchId)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Deletes the DefaultSettings profile with the given ID
	 *
	 * @param id
	 * @return True on success and false otherwise
	 */
	public static boolean deleteProfile(int id) {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.DeleteDefaultSettings(?)");
			ps.setInt(1, id);
			ps.execute();
			return true;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}
		return false; //error;
	}

	/**
	 * Gets the DefaultSettings profile with the given id
	 *
	 * @param id
	 * @return
	 */
	public static DefaultSettings getProfileById(int id) throws SQLException {
		final String methodName = "getProfileById";
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.getProfileById(?)");
			ps.setInt(1, id);
			results = ps.executeQuery();
			if (results.next()) {
				DefaultSettings settings = resultsToSettings(results);
				settings.setBenchIds(Settings.getDefaultBenchmarkIds(con, settings.getId()));
				return settings;
			}
		} catch (SQLException e) {
			log.error(methodName, "SQLException caught while querying database.", e);
			throw e;
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
			Common.safeClose(results);
		}
		return null;
	}

	/**
	 * Updates the maximum memory setting of the given settings object
	 *
	 * @param id
	 * @param bytes
	 * @return
	 */
	public static boolean setDefaultMaxMemory(int id, long bytes) {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.SetMaximumMemorySetting(?, ?)");
			ps.setInt(1, id);
			ps.setLong(2, bytes);
			ps.execute();
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}

		return true;
	}

	/**
	 * Sets the default settings profile for a given user. This is the profile that will show up initially on job
	 * creation
	 *
	 * @param userId The ID of the user having their default updated
	 * @param settingId The setting ID to use
	 * @return True on success and false on error
	 */
	public static boolean setDefaultProfileForUser(int userId, int settingId) {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.SetDefaultProfileForUser(?,?)");
			ps.setInt(1, userId);
			ps.setInt(2, settingId);
			ps.execute();
			return true;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}
		return false;
	}

	/**
	 * Gets the default settings profile for a given user. This is the profile that will show up initially on job
	 * creation
	 *
	 * @param userId
	 * @return The id of the settings profile a user has as their default, OR null if the user has no default settings
	 * profile. -1 is returned on error
	 */
	public static Integer getDefaultProfileForUser(int userId) {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetDefaultProfileForUser(?)");
			ps.setInt(1, userId);
			results = ps.executeQuery();
			if (results.next()) {
				int result = results.getInt("default_settings_profile");
				//a value of 0 means the field is null in SQL
				if (result == 0) {
					return null;
				}
				return result;
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
			Common.safeClose(results);
		}
		return -1;
	}

	/**
	 * Set the default settings for a community given by the id.
	 *
	 * @param id The ID of the DefaultSettings object
	 * @param num Indicates which attribute needs to be set 1 = post_processor_id 2 = cpu_timeout 3 = wallclock_timeout
	 * 4 = dependencies_enabled 5 = default_benchmark_id 6 = pre_processor_id 7 = default_solver_id 8 =
	 * bench_processor_id
	 * @param setting The new value of the setting
	 * @return True if the operation is successful
	 * @author Ruoyu Zhang
	 */
	public static boolean updateSettingsProfile(int id, int num, long setting) {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.SetDefaultSettingsById(?, ?, ?)");
			ps.setInt(1, id);
			ps.setInt(2, num);
			//if we are setting one of the IDs and it is -1, this means there is no setting
			//and we should use null
			if ((num == 1 || num == 5 || num == 6 || num == 7 || num == 8) && setting == -1) {
				ps.setObject(3, null);
			} else {
				ps.setInt(3, (int) setting);
			}

			ps.execute();
			return true;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}

		return false;
	}
}
