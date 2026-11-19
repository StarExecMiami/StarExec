package org.starexec.test.integration.security;

import org.junit.jupiter.api.Assertions;
import org.starexec.constants.R;
import org.starexec.data.security.QueueSecurity;
import org.starexec.data.to.User;
import org.starexec.test.TestUtil;
import org.starexec.test.integration.StarexecTest;
import org.starexec.test.integration.TestSequence;

public class QueueSecurityTests extends TestSequence {
	User user1=null;
	User user2=null;
	User admin=null;

	@StarexecTest
	private void canUserMakeQueueTest() {
		String randomName=TestUtil.getRandomQueueName();
		Assertions.assertTrue(QueueSecurity.canUserMakeQueue(admin.getId(), randomName).isSuccess());

		Assertions.assertFalse(QueueSecurity.canUserMakeQueue(user1.getId(),randomName).isSuccess());
		Assertions.assertFalse(QueueSecurity.canUserMakeQueue(user2.getId(),randomName).isSuccess());
	}

	@StarexecTest
	private void canEditQueueTest() {
		Assertions.assertTrue(QueueSecurity.canUserEditQueue(admin.getId(), 50, 20).isSuccess());

		Assertions.assertFalse(QueueSecurity.canUserEditQueue(user1.getId(), 50, 20).isSuccess());
		Assertions.assertFalse(QueueSecurity.canUserEditQueue(admin.getId(), -1, 20).isSuccess());
		Assertions.assertFalse(QueueSecurity.canUserEditQueue(admin.getId(), 50, 0).isSuccess());
	}

	@Override
	protected String getTestName() {
		return "QueueSecurityTest";
	}

	@Override
	protected void setup() throws Exception {
		user1=loader.loadUserIntoDatabase();
		user2=loader.loadUserIntoDatabase();
		admin=loader.loadUserIntoDatabase(TestUtil.getRandomAlphaString(10),TestUtil.getRandomAlphaString(10),TestUtil.getRandomPassword(),TestUtil.getRandomPassword(),"The University of Miami",R.ADMIN_ROLE_NAME);
		Assertions.assertNotNull(user1);
		Assertions.assertNotNull(user2);
		Assertions.assertNotNull(admin);
	}

	@Override
	protected void teardown() throws Exception {
		loader.deleteAllPrimitives();
	}
}
