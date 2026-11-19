package org.starexec.test.integration.security;

import org.junit.jupiter.api.Assertions;
import org.starexec.data.security.UserSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.User;
import org.starexec.test.integration.StarexecTest;
import org.starexec.test.integration.TestSequence;

public class UserSecurityTests extends TestSequence {
	User user1=null;
	User user2=null;
	User dev = null;
	User admin=null;

	@StarexecTest
	private void canUpdateFirstNameTest() {
		Assertions.assertTrue(UserSecurity.canUpdateData(user1.getId(), user1.getId(), "firstname","test").isSuccess());
		Assertions.assertTrue(UserSecurity.canUpdateData(user1.getId(), admin.getId(), "firstname","test").isSuccess());
		Assertions.assertFalse(UserSecurity.canUpdateData(admin.getId(), user1.getId(), "firstname","test").isSuccess());
		Assertions.assertFalse(UserSecurity.canUpdateData(user1.getId(), user2.getId(), "firstname","test").isSuccess());
	}

	@StarexecTest
	private void canUserSubscribeOrUnsubscribeUserToErrorLogsTest() {
		Assertions.assertFalse(UserSecurity.canUserSubscribeOrUnsubscribeUserToErrorLogs(user2.getId(), user1.getId()).isSuccess());
	}

	@StarexecTest
	private void canUserSubscribeOrUnsubscribeSelfToErrorLogsTest() {
		Assertions.assertFalse(UserSecurity.canUserSubscribeOrUnsubscribeUserToErrorLogs(user1.getId(), user1.getId()).isSuccess());
	}

	@StarexecTest
	private void canUserSubscribeOrUnsubscribeDevToErrorLogsTest() {
		Assertions.assertFalse(UserSecurity.canUserSubscribeOrUnsubscribeUserToErrorLogs(dev.getId(), user1.getId()).isSuccess());
	}

	@StarexecTest
	private void canUserSubscribeOrUnsubscribeAdminToErrorLogsTest() {
		Assertions.assertFalse(UserSecurity.canUserSubscribeOrUnsubscribeUserToErrorLogs(admin.getId(), user1.getId()).isSuccess());
	}

	@StarexecTest
	private void canDevSubscribeOrUnsubscribeUserToErrorLogsTest() {
		Assertions.assertFalse(UserSecurity.canUserSubscribeOrUnsubscribeUserToErrorLogs(user1.getId(), dev.getId()).isSuccess());
	}

	@StarexecTest
	private void canDevSubscribeOrUnsubscribeAdminToErrorLogsTest() {
		Assertions.assertFalse(UserSecurity.canUserSubscribeOrUnsubscribeUserToErrorLogs(admin.getId(), dev.getId()).isSuccess());
	}

	@StarexecTest
	private void canDevSubscribeOrUnsubscribeSelfToErrorLogsTest() {
		ValidatorStatusCode status = UserSecurity.canUserSubscribeOrUnsubscribeUserToErrorLogs(dev.getId(), dev.getId());
		Assertions.assertTrue(status.isSuccess(), status.getMessage());
	}

	@StarexecTest
	private void canAdminSubscribeOrUnsubscribeUserToErrorLogsTest() {
		Assertions.assertFalse(UserSecurity.canUserSubscribeOrUnsubscribeUserToErrorLogs(user1.getId(), admin.getId()).isSuccess());
	}

	@StarexecTest
	private void canAdminSubscribeOrUnsubscribeSelfToErrorLogsTest() {
		Assertions.assertTrue(UserSecurity.canUserSubscribeOrUnsubscribeUserToErrorLogs(admin.getId(), admin.getId()).isSuccess());
	}

	@StarexecTest
	private void canAdminSubscribeOrUnsubscribeDevToErrorLogsTest() {
		Assertions.assertTrue(UserSecurity.canUserSubscribeOrUnsubscribeUserToErrorLogs(dev.getId(), admin.getId()).isSuccess());
	}

	@StarexecTest
	private void CanDeleteUserTest() {
		Assertions.assertTrue(UserSecurity.canDeleteUser(user1.getId(), admin.getId()).isSuccess());
		Assertions.assertTrue(UserSecurity.canDeleteUser(user2.getId(), admin.getId()).isSuccess());

		Assertions.assertFalse(UserSecurity.canDeleteUser(user1.getId(), user1.getId()).isSuccess());
		Assertions.assertFalse(UserSecurity.canDeleteUser(admin.getId(), user1.getId()).isSuccess());
		Assertions.assertFalse(UserSecurity.canDeleteUser(admin.getId(), admin.getId()).isSuccess());
		Assertions.assertFalse(UserSecurity.canDeleteUser(user1.getId(), user2.getId()).isSuccess());
	}

	@StarexecTest
	private void canUpdateLastNameTest() {
		Assertions.assertTrue(UserSecurity.canUpdateData(user1.getId(), user1.getId(), "lastname","test").isSuccess());
		Assertions.assertTrue(UserSecurity.canUpdateData(user1.getId(), admin.getId(), "lastname","test").isSuccess());
		Assertions.assertFalse(UserSecurity.canUpdateData(admin.getId(), user1.getId(), "lastname","test").isSuccess());
		Assertions.assertFalse(UserSecurity.canUpdateData(user1.getId(), user2.getId(), "lastname","test").isSuccess());
	}

	@StarexecTest
	private void canUpdateInstitutionTest() {
		Assertions.assertTrue(UserSecurity.canUpdateData(user1.getId(), user1.getId(), "institution","Iowa").isSuccess());
		Assertions.assertTrue(UserSecurity.canUpdateData(user1.getId(), admin.getId(), "institution","Iowa").isSuccess());
		Assertions.assertFalse(UserSecurity.canUpdateData(admin.getId(), user1.getId(), "institution","Iowa").isSuccess());
		Assertions.assertFalse(UserSecurity.canUpdateData(user1.getId(), user2.getId(), "institution","Iowa").isSuccess());
	}

	@StarexecTest
	private void canUpdateDiskQuotaTest() {
		//only admins can update anyone's disk quota
		Assertions.assertTrue(UserSecurity.canUpdateData(user1.getId(), admin.getId(), "diskquota","10").isSuccess());
		Assertions.assertFalse(UserSecurity.canUpdateData(admin.getId(), user1.getId(), "diskquota","10").isSuccess());
		Assertions.assertFalse(UserSecurity.canUpdateData(user1.getId(), user2.getId(), "diskquota","10").isSuccess());
		Assertions.assertFalse(UserSecurity.canUpdateData(user1.getId(), user1.getId(), "diskquota","10").isSuccess());
	}

	@StarexecTest
	private void canViewUserPrimitives() {
		Assertions.assertTrue(UserSecurity.canViewUserPrimitives(user1.getId(),user1.getId()).isSuccess());
		Assertions.assertTrue(UserSecurity.canViewUserPrimitives(user1.getId(),admin.getId()).isSuccess());
		Assertions.assertFalse(UserSecurity.canViewUserPrimitives(admin.getId(),user2.getId()).isSuccess());
		Assertions.assertFalse(UserSecurity.canViewUserPrimitives(user1.getId(),user2.getId()).isSuccess());
	}

	@Override
	protected String getTestName() {
		return "UserSecurityTests";
	}

	@Override
	protected void setup() throws Exception {
		user1=loader.loadUserIntoDatabase();
		user2=loader.loadUserIntoDatabase();

		admin=loader.loadAdminIntoDatabase();
		dev = loader.loadDevIntoDatabase();
		Assertions.assertNotNull(user1);
		Assertions.assertNotNull(user2);
		Assertions.assertNotNull(admin);
	}

	@Override
	protected void teardown() throws Exception {
		loader.deleteAllPrimitives();
	}
}
