package org.starexec.test.integration.database;

import org.junit.jupiter.api.Assertions;
import org.starexec.constants.R;
import org.starexec.data.database.Communities;
import org.starexec.data.database.Settings;
import org.starexec.data.to.*;
import org.starexec.test.TestUtil;
import org.starexec.test.integration.StarexecTest;
import org.starexec.test.integration.TestSequence;
import org.starexec.util.Util;

import java.sql.SQLException;
import java.util.*;

/**
 * Tests for org.starexec.data.database.Settings.java
 * @author Eric
 */
public class DefaultSettingsTests extends TestSequence {
	DefaultSettings settings=null;
	DefaultSettings settings2=null;
    DefaultSettings settingsWithDefaultBenchmarks=null;
	User u=null;
	User u2=null;
    User u3=null;

	List<Integer> benchIds;

	Space space = null;

	User admin=null;
	@Override
	protected String getTestName() {
		return "DefaultSettingsTests";
	}

	@StarexecTest
    private void addAndDeleteDefaultBenchmarkTest() {
        try {
            int benchId = benchIds.get(0);
            int settingId = settings.getId();
            DefaultSettings s = Settings.getProfileById(settingId);

            Assertions.assertFalse(s.getBenchIds().contains(benchId), "Benchmark was already in settings.");

            Settings.addDefaultBenchmark(settingId, benchId);

            s = Settings.getProfileById(settingId);

            Assertions.assertTrue(s.getBenchIds().contains(benchId), "Default benchmark was not added.");

            Settings.deleteDefaultBenchmark(settingId, benchId);

            s = Settings.getProfileById(settingId);

            Assertions.assertFalse(s.getBenchIds().contains(benchId), "Benchmark not deleted from settings.");

        } catch (SQLException e) {
            Assertions.fail("Caught SQLException: " + Util.getStackTrace(e));
        }
    }

	@StarexecTest
	private void addSettingsTest() {
		DefaultSettings newSettings = DefaultSettings.copy(settings);

        newSettings.setBenchIds(benchIds);

		int id = Settings.addNewSettingsProfile(newSettings);

        Assertions.assertNotEquals(id, -1, "Error while adding new settings.");

        try {
            DefaultSettings dbSettings = Settings.getProfileById(newSettings.getId());

            assertDefaultSettingsEqual(newSettings, dbSettings);
        } catch(SQLException e) {
            Assertions.fail("Caught SQLException: " + Util.getStackTrace(e));
        } finally {
            Settings.deleteProfile(newSettings.getId());
        }

	}

	@StarexecTest
    private void getDefaultBenchmarksTest() {
        int settingsId = settingsWithDefaultBenchmarks.getId();
        try {
            List<Benchmark> dbBenchmarks = Settings.getDefaultBenchmarks(settingsId);
            dbBenchmarks.sort(Comparator.comparingInt(Identifiable::getId));
            List<Integer> settingsBenchmarks = settingsWithDefaultBenchmarks.getBenchIds();
            Collections.sort(settingsBenchmarks);

            Assertions.assertTrue(settingsBenchmarks.size() == dbBenchmarks.size());

            for (int i = 0; i < settingsBenchmarks.size(); i++) {
                Assertions.assertEquals(settingsBenchmarks.get(i), (Integer)dbBenchmarks.get(i).getId());
            }
        } catch (SQLException e) {
            Assertions.fail("SQLException thrown: " + Util.getStackTrace(e));
        }
    }

    @StarexecTest
    private void getDefaultBenchmarkIdsTest() {
        int settingsId = settingsWithDefaultBenchmarks.getId();
        try {
            List<Integer> dbBenchmarks = Settings.getDefaultBenchmarkIds(settingsId);
            Collections.sort(dbBenchmarks);
            List<Integer> settingsBenchmarks = settingsWithDefaultBenchmarks.getBenchIds();
            Collections.sort(settingsBenchmarks);

            Assertions.assertTrue(settingsBenchmarks.size() == dbBenchmarks.size());

            for (int i = 0; i < settingsBenchmarks.size(); i++) {
                Assertions.assertEquals(settingsBenchmarks.get(i), dbBenchmarks.get(i));
            }
        } catch (SQLException e) {
            Assertions.fail("SQLException thrown: " + Util.getStackTrace(e));
        }
    }

    @StarexecTest
    private void updateDefaultSettingsTest() {
        DefaultSettings newSettings = DefaultSettings.copy(settingsWithDefaultBenchmarks);
        Settings.addNewSettingsProfile(newSettings);

        newSettings.setMaxMemory(newSettings.getMaxMemory()+1);
        newSettings.setCpuTimeout(newSettings.getCpuTimeout()+1);
        newSettings.setWallclockTimeout(newSettings.getWallclockTimeout()+1);
        newSettings.setDependenciesEnabled(!newSettings.isDependenciesEnabled());
        newSettings.setBenchIds(new ArrayList<>(Collections.singletonList(newSettings.getBenchIds().get(0))));

        // TODO: need to change all other fields of setting except name, primId, and type (these are immutable)

        boolean success = Settings.updateDefaultSettings(newSettings);
        Assertions.assertTrue(success, "Database call failed.");
        try {
            DefaultSettings dbSettings = Settings.getProfileById(newSettings.getId());
            assertDefaultSettingsEqual(newSettings, dbSettings);
        } catch (SQLException e) {
            Assertions.fail("Caught SQLException: " + Util.getStackTrace(e));
        } finally {
            Settings.deleteProfile(newSettings.getId());
        }
    }

    private void assertDefaultSettingsEqual(DefaultSettings newSettings, DefaultSettings dbSettings) {

        Assertions.assertNotNull(newSettings, "The new setting object was null.");
        Assertions.assertNotNull(dbSettings, "The DB setting object was null.");


        Assertions.assertEquals(newSettings.getPrimId(), dbSettings.getPrimId(), "PrimId was not equal.");
        Assertions.assertEquals(newSettings.getPreProcessorId(), dbSettings.getPreProcessorId(), "PreProcessorId was not equal.");
        Assertions.assertEquals(newSettings.getPostProcessorId(), dbSettings.getPostProcessorId(), "PostProcessorId was not equal.");
        Assertions.assertEquals(newSettings.getBenchProcessorId(), dbSettings.getBenchProcessorId(), "BenchProcessorId was not equal.");
        Assertions.assertEquals(newSettings.getSolverId(), dbSettings.getSolverId(), "SolverId was not equal.");
        Assertions.assertEquals(newSettings.getWallclockTimeout(), dbSettings.getWallclockTimeout(), "WallclockTimeout was not equal.");
        Assertions.assertEquals(newSettings.getCpuTimeout(), dbSettings.getCpuTimeout(), "CpuTimeout was not equal.");
        Assertions.assertEquals(newSettings.getMaxMemory(), dbSettings.getMaxMemory(), "MaxMemory was not equal.");
        Assertions.assertEquals(newSettings.isDependenciesEnabled(), dbSettings.isDependenciesEnabled(), "DependenciesEnabled was not equal.");
        Assertions.assertEquals(newSettings.getName(), dbSettings.getName(), "Name was not equal.");
        Assertions.assertEquals(newSettings.getType(), dbSettings.getType(), "Type was not equal.");
        Assertions.assertEquals(newSettings.getBenchmarkingFramework(), dbSettings.getBenchmarkingFramework(), "Benchmarking framework was not equal.");

        List<Integer> settingsBenchIds = newSettings.getBenchIds();
        List<Integer> dbSettingsBenchIds = dbSettings.getBenchIds();

        final String benchIdsNotEqual = "Bench ids were not equal.";
        Assertions.assertEquals(settingsBenchIds.size(), dbSettingsBenchIds.size(), benchIdsNotEqual);

        Collections.sort(settingsBenchIds);
        Collections.sort(dbSettingsBenchIds);
        for (int i = 0; i < settingsBenchIds.size(); i++) {
            Assertions.assertEquals(settingsBenchIds.get(i), dbSettingsBenchIds.get(i), benchIdsNotEqual);
        }
    }

	@StarexecTest
	private void getSettingsByUser() {
		List<DefaultSettings> settingsList=Settings.getDefaultSettingsOwnedByUser(u.getId());
		Assertions.assertEquals(settingsList.size(), 1);
		Assertions.assertTrue(settingsList.get(0).equals(settings));
	}

	@StarexecTest
	private void deleteSettingsTest() {
		DefaultSettings temp=loader.loadDefaultSettingsProfileIntoDatabase(u.getId());
		Assertions.assertTrue(Settings.deleteProfile(temp.getId()));
		try {
			Assertions.assertNull(Settings.getProfileById(temp.getId()));
		} catch (SQLException e) {
			Assertions.fail("Caught SQLException: "+ Util.getStackTrace(e));
		}
	}

	@StarexecTest
	private void getSettingsByNameTest() {
		List<DefaultSettings> settingsList=Settings.getDefaultSettingsByPrimIdAndType(settings.getPrimId(), settings.getType());

		Assertions.assertEquals(settingsList.size(),1);
		Assertions.assertTrue(settingsList.get(0).equals(settings));
	}


	@StarexecTest
	private void getSettingsTest() {
		try {
			DefaultSettings temp = Settings.getProfileById(settings.getId());
			Assertions.assertNotNull(temp);
			Assertions.assertTrue(temp.equals(settings));
		} catch (SQLException e) {
			Assertions.fail("Caught SQLException: " + Util.getStackTrace(e));
		}
	}

	@StarexecTest
	private void updateDefaultMemoryLimitTest() {


		Assertions.assertTrue(Settings.setDefaultMaxMemory(settings.getId(), settings.getMaxMemory()+1));
		try {
			Assertions.assertEquals(settings.getMaxMemory() + 1, Settings.getProfileById(settings.getId()).getMaxMemory());
			Assertions.assertTrue(Settings.setDefaultMaxMemory(settings.getId(), settings.getMaxMemory()));
		} catch (SQLException e) {
			Assertions.fail("Caught SQLException: " + Util.getStackTrace(e));
		}
	}

	@StarexecTest
	private void setDefaultProfileForUserTest() {
		try {
			// User should have no default initially (or it will be overwritten)
			
			// Set a default profile
			boolean success = Settings.setDefaultProfileForUser(u.getId(), settings.getId());
			Assertions.assertTrue(success, "Failed to set default profile for user");
			
			// Verify it was set
			Integer newDefault = Settings.getDefaultProfileForUser(u.getId());
			Assertions.assertNotNull(newDefault, "Default profile should not be null after setting");
			Assertions.assertEquals(newDefault.intValue(), settings.getId(), "Default profile ID should match");
		} catch (Exception e) {
			Assertions.fail("Caught exception: " + Util.getStackTrace(e));
		}
	}

	@StarexecTest
	private void clearDefaultProfileForUserTest() {
		try {
			// First set a default profile
			boolean setSuccess = Settings.setDefaultProfileForUser(u.getId(), settings.getId());
			Assertions.assertTrue(setSuccess, "Failed to set default profile");
			
			// Verify it was set
			Integer beforeClear = Settings.getDefaultProfileForUser(u.getId());
			Assertions.assertNotNull(beforeClear, "Default should be set before clearing");
			Assertions.assertEquals(beforeClear.intValue(), settings.getId(), "Default should match what we set");
			
			// Now clear it
			boolean clearSuccess = Settings.clearDefaultProfileForUser(u.getId());
			Assertions.assertTrue(clearSuccess, "Failed to clear default profile for user");
			
			// Verify it was cleared
			Integer afterClear = Settings.getDefaultProfileForUser(u.getId());
			Assertions.assertNull(afterClear, "Default profile should be null after clearing");
		} catch (Exception e) {
			Assertions.fail("Caught exception: " + Util.getStackTrace(e));
		}
	}

	@StarexecTest
	private void clearDefaultProfileWhenAlreadyNullTest() {
		try {
			// Ensure user has no default
			Settings.clearDefaultProfileForUser(u2.getId());
			Integer beforeClear = Settings.getDefaultProfileForUser(u2.getId());
			Assertions.assertNull(beforeClear, "User should have no default");
			
			// Clearing again should still succeed (idempotent operation)
			boolean clearSuccess = Settings.clearDefaultProfileForUser(u2.getId());
			Assertions.assertTrue(clearSuccess, "Clearing already-null default should succeed");
			
			// Verify still null
			Integer afterClear = Settings.getDefaultProfileForUser(u2.getId());
			Assertions.assertNull(afterClear, "Default should still be null");
		} catch (Exception e) {
			Assertions.fail("Caught exception: " + Util.getStackTrace(e));
		}
	}

	@StarexecTest
	private void setAndClearMultipleTimesTest() {
		try {
			// Set default to settings1
			Settings.setDefaultProfileForUser(u.getId(), settings.getId());
			Integer default1 = Settings.getDefaultProfileForUser(u.getId());
			Assertions.assertEquals(default1.intValue(), settings.getId(), "Should be set to first profile");
			
			// Clear it
			Settings.clearDefaultProfileForUser(u.getId());
			Integer cleared1 = Settings.getDefaultProfileForUser(u.getId());
			Assertions.assertNull(cleared1, "Should be null after clear");
			
			// Set to different profile
			Settings.setDefaultProfileForUser(u.getId(), settings2.getId());
			Integer default2 = Settings.getDefaultProfileForUser(u.getId());
			Assertions.assertEquals(default2.intValue(), settings2.getId(), "Should be set to second profile");
			
			// Clear again
			Settings.clearDefaultProfileForUser(u.getId());
			Integer cleared2 = Settings.getDefaultProfileForUser(u.getId());
			Assertions.assertNull(cleared2, "Should be null after second clear");
		} catch (Exception e) {
			Assertions.fail("Caught exception: " + Util.getStackTrace(e));
		}
	}

	@Override
	protected void setup() throws Exception {
		u=loader.loadUserIntoDatabase();
		u2=loader.loadUserIntoDatabase();
        u3=loader.loadUserIntoDatabase();

		space=loader.loadSpaceIntoDatabase(u.getId(), Communities.getTestCommunity().getId());

		benchIds = loader.loadBenchmarksIntoDatabase(space.getId() ,u.getId());

		settings=loader.loadDefaultSettingsProfileIntoDatabase(u.getId());
		settings2=loader.loadDefaultSettingsProfileIntoDatabase(u2.getId());
        settingsWithDefaultBenchmarks=loader.loadDefaultSettingsProfileIntoDatabaseWithDefaultBenchmarks(u3.getId(), benchIds);

		admin=loader.loadUserIntoDatabase(TestUtil.getRandomAlphaString(10),TestUtil.getRandomAlphaString(10),TestUtil.getRandomPassword(),TestUtil.getRandomPassword(),"The University of Miami",R.ADMIN_ROLE_NAME);
	}

	@Override
	protected void teardown() throws Exception {
		loader.deleteAllPrimitives();
	}

}
