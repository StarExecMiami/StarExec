package org.starexec.test.integration.security;

import org.junit.jupiter.api.Assertions;
import org.starexec.constants.DB;
import org.starexec.constants.R;
import org.starexec.data.database.Processors;
import org.starexec.data.database.Users;
import org.starexec.data.security.ProcessorSecurity;
import org.starexec.data.to.Processor;
import org.starexec.data.to.Space;
import org.starexec.data.to.User;
import org.starexec.data.to.enums.ProcessorType;
import org.starexec.test.TestUtil;
import org.starexec.test.integration.StarexecTest;
import org.starexec.test.integration.TestSequence;

public class ProcessorSecurityTests extends TestSequence {
	User admin = null;
	User owner = null;
	User otherUser = null;
	User notInCommunity = null;

	Space community = null;
	Processor p = null;

	@StarexecTest
	private void canUserSeeProcessorTest() {
		Assertions.assertTrue(ProcessorSecurity.canUserSeeProcessor(p.getId(), admin.getId()).isSuccess());
		Assertions.assertTrue(ProcessorSecurity.canUserSeeProcessor(p.getId(), owner.getId()).isSuccess());
		Assertions.assertTrue(ProcessorSecurity.canUserSeeProcessor(p.getId(), otherUser.getId()).isSuccess());
		Assertions.assertFalse(ProcessorSecurity.canUserSeeProcessor(p.getId(), notInCommunity.getId()).isSuccess());

	}

	@StarexecTest
	private void canUserSeeNoTypeProcessorTest() {
		Processor noType = Processors.getNoTypeProcessor();
		Assertions.assertTrue(ProcessorSecurity.canUserSeeProcessor(noType.getId(), admin.getId()).isSuccess());
		Assertions.assertTrue(ProcessorSecurity.canUserSeeProcessor(noType.getId(), owner.getId()).isSuccess());
		Assertions.assertTrue(ProcessorSecurity.canUserSeeProcessor(noType.getId(), otherUser.getId()).isSuccess());
		Assertions.assertTrue(ProcessorSecurity.canUserSeeProcessor(noType.getId(), notInCommunity.getId()).isSuccess());
	}

	@StarexecTest
	private void doesUserOwnProcessorTest() {
		Assertions.assertTrue(ProcessorSecurity.doesUserOwnProcessor(p.getId(), admin.getId()).isSuccess());
		Assertions.assertTrue(ProcessorSecurity.doesUserOwnProcessor(p.getId(), owner.getId()).isSuccess());
		Assertions.assertFalse(ProcessorSecurity.doesUserOwnProcessor(p.getId(), otherUser.getId()).isSuccess());
		Assertions.assertFalse(ProcessorSecurity.doesUserOwnProcessor(p.getId(), notInCommunity.getId()).isSuccess());
	}

	@StarexecTest
	private void canUserEditProcessorGoodAttrsTest() {
		Assertions.assertTrue(ProcessorSecurity.canUserEditProcessor(p.getId(), admin.getId(), "test", "test").isSuccess());
		Assertions.assertTrue(ProcessorSecurity.canUserEditProcessor(p.getId(), owner.getId(), "test", "test").isSuccess());
		Assertions.assertFalse(ProcessorSecurity.canUserEditProcessor(p.getId(), otherUser.getId(), "test", "test").isSuccess());
		Assertions.assertFalse(ProcessorSecurity.canUserEditProcessor(p.getId(), notInCommunity.getId(), "test", "test").isSuccess());
	}

	@StarexecTest
	private void canUserEditProcessorBadNameTest() {
		Assertions.assertFalse(ProcessorSecurity.canUserEditProcessor(p.getId(), admin.getId(), "", "test").isSuccess());
		Assertions.assertFalse(ProcessorSecurity.canUserEditProcessor(p.getId(), owner.getId(), "", "test").isSuccess());
		Assertions.assertFalse(ProcessorSecurity.canUserEditProcessor(p.getId(), otherUser.getId(), "", "test").isSuccess());
		Assertions.assertFalse(ProcessorSecurity.canUserEditProcessor(p.getId(), notInCommunity.getId(), "", "test").isSuccess());
	}

	@StarexecTest
	private void canUserEditProcessorBadDescTest() {
		Assertions.assertFalse(ProcessorSecurity.canUserEditProcessor(p.getId(), admin.getId(), "test", TestUtil.getRandomAlphaString(DB.SPACE_DESC_LEN+1)).isSuccess());
		Assertions.assertFalse(ProcessorSecurity.canUserEditProcessor(p.getId(), owner.getId(), "test", TestUtil.getRandomAlphaString(DB.SPACE_DESC_LEN+1)).isSuccess());
		Assertions.assertFalse(ProcessorSecurity.canUserEditProcessor(p.getId(), otherUser.getId(), "test", TestUtil.getRandomAlphaString(DB.SPACE_DESC_LEN+1)).isSuccess());
		Assertions.assertFalse(ProcessorSecurity.canUserEditProcessor(p.getId(), notInCommunity.getId(), "test", TestUtil.getRandomAlphaString(DB.SPACE_DESC_LEN+1)).isSuccess());
	}

	@Override
	protected String getTestName() {
		return "ProcessorSecurityTests";
	}

	@Override
	protected void setup() throws Exception {
		admin=loader.loadUserIntoDatabase(TestUtil.getRandomAlphaString(10),TestUtil.getRandomAlphaString(10),TestUtil.getRandomPassword(),TestUtil.getRandomPassword(),"The University of Miami",R.ADMIN_ROLE_NAME);
		owner = loader.loadUserIntoDatabase();
		otherUser = loader.loadUserIntoDatabase();
		notInCommunity = loader.loadUserIntoDatabase();
		community=loader.loadSpaceIntoDatabase(owner.getId(), 1);
		Users.associate(otherUser.getId(),community.getId());
		p = loader.loadProcessorIntoDatabase(ProcessorType.POST, community.getId());

	}

	@Override
	protected void teardown() throws Exception {
		loader.deleteAllPrimitives();
	}

}
