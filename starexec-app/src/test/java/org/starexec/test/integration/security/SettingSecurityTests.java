package org.starexec.test.integration.security;

import org.junit.jupiter.api.Assertions;
import org.starexec.constants.R;
import org.starexec.data.security.SettingSecurity;
import org.starexec.data.to.DefaultSettings;
import org.starexec.data.to.User;
import org.starexec.test.TestUtil;
import org.starexec.test.integration.StarexecTest;
import org.starexec.test.integration.TestSequence;
import org.starexec.util.Util;

import java.sql.SQLException;

public class SettingSecurityTests extends TestSequence {
	User u=null;
	User u2=null;
	User admin=null;
	DefaultSettings s=null;
	@Override
	protected String getTestName() {
		return "SettingSecurityTests";
	}
	
	@StarexecTest
	private void canModifySettingsTest() {
		try {
			Assertions.assertTrue(SettingSecurity.canModifySettings(s.getId(), u.getId()).isSuccess());
			Assertions.assertTrue(SettingSecurity.canModifySettings(s.getId(), admin.getId()).isSuccess());
			Assertions.assertFalse(SettingSecurity.canModifySettings(s.getId(), u2.getId()).isSuccess());
			Assertions.assertFalse(SettingSecurity.canModifySettings(-1, u2.getId()).isSuccess());
		} catch (SQLException e) {
			Assertions.fail("Caught SQLException: " + Util.getStackTrace(e));
		}
	}
	
	@StarexecTest
	private void canUpdateSettingsTest() {
		//SettingSecurity.canUpdateSettings(id, attribute, newValue, userId)
	}

	@Override
	protected void setup() throws Exception {
		u=loader.loadUserIntoDatabase();
		u2=loader.loadUserIntoDatabase();
		s=loader.loadDefaultSettingsProfileIntoDatabase(u.getId());
		admin=loader.loadUserIntoDatabase(TestUtil.getRandomAlphaString(10),TestUtil.getRandomAlphaString(10),TestUtil.getRandomPassword(),TestUtil.getRandomPassword(),"The University of Miami",R.ADMIN_ROLE_NAME);
	}

	@Override
	protected void teardown() throws Exception {
		loader.deleteAllPrimitives();
	}

}
