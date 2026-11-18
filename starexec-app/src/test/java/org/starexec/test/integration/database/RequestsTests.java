package org.starexec.test.integration.database;

import org.junit.jupiter.api.Assertions;
import org.starexec.constants.R;
import org.starexec.data.database.Requests;
import org.starexec.data.database.Users;
import org.starexec.data.to.CommunityRequest;
import org.starexec.data.to.Space;
import org.starexec.data.to.User;
import org.starexec.exceptions.StarExecDatabaseException;
import org.starexec.test.TestUtil;
import org.starexec.test.integration.StarexecTest;
import org.starexec.test.integration.TestSequence;
import org.starexec.util.DataTablesQuery;

import java.util.UUID;

/**
 * Tests for org.starexec.data.database.Requests.java
 * @author Eric
 */
public class RequestsTests extends TestSequence {

	User registeredUser=null;
	// requestedUser and requestedUser2 have requests for comm
	User requestedUser=null;
	// requestedUser's request
	CommunityRequest request=null;
	
	User admin=null;
	Space comm=null;
	Space comm2=null;
	
	
	@StarexecTest
	private void getCommunityRequestByUserTest() {
		CommunityRequest test=Requests.getCommunityRequest(request.getUserId());
		Assertions.assertEquals(request.getCode(), test.getCode());
		Assertions.assertEquals(request.getUserId(),test.getUserId());
		Assertions.assertEquals(request.getMessage(),test.getMessage());
	}
	
	@StarexecTest
	private void getCommunityRequestByCodeTest() {
		CommunityRequest test=Requests.getCommunityRequest(request.getCode());
		Assertions.assertEquals(request.getCode(), test.getCode());
		Assertions.assertEquals(request.getUserId(),test.getUserId());
		Assertions.assertEquals(request.getMessage(),test.getMessage());
	}

	@StarexecTest
	private void getCommunityRequestForCommunityTest() {
		int commRequestsSizeBefore = 0;
		int comm2RequestsSizeBefore = 0;
		int commRequestsSizeAfter = 0;
		int comm2RequestsSizeAfter = 0;
		DataTablesQuery query = new DataTablesQuery();
		query.setStartingRecord(0);
		query.setNumRecords(10);
		try {
			commRequestsSizeBefore = Requests.getPendingCommunityRequestsForCommunity(query, comm.getId()).size();
			comm2RequestsSizeBefore = Requests.getPendingCommunityRequestsForCommunity(query, comm2.getId()).size();
			User tempUser=loader.loadUserIntoDatabase();
			loader.loadCommunityRequestIntoDatabase(tempUser.getId(), comm.getId());
			commRequestsSizeAfter = Requests.getPendingCommunityRequestsForCommunity(query, comm.getId()).size();
			comm2RequestsSizeAfter = Requests.getPendingCommunityRequestsForCommunity(query, comm2.getId()).size();

			Assertions.assertTrue(Users.deleteUser(tempUser.getId()));
		} catch (StarExecDatabaseException e) {
			Assertions.fail("Requests.getPendingCommunityRequestsForCommunity threw an exception: "+e.getMessage());
		}

		Assertions.assertEquals(commRequestsSizeBefore, commRequestsSizeAfter-1);
		Assertions.assertEquals(comm2RequestsSizeBefore, comm2RequestsSizeAfter);
	}

	
	@StarexecTest
	private void getCommunityRequestCountTest() {
		try {
			Assertions.assertTrue(Requests.getCommunityRequestCount()>0);
		} catch (StarExecDatabaseException e) {
			Assertions.fail("getCommunityRequestCount threw an exception."+e.getMessage());
		}
	}
	
	@StarexecTest
	private void approveCommunityRequestTest() throws StarExecDatabaseException {
		User tempUser=loader.loadUserIntoDatabase();
		CommunityRequest tempRequest=loader.loadCommunityRequestIntoDatabase(tempUser.getId(), comm.getId());
		Assertions.assertFalse(Users.isMemberOfCommunity(tempUser.getId(), comm.getId()));

		Assertions.assertTrue(Requests.approveCommunityRequest(tempUser.getId(), comm.getId()));
		Assertions.assertNull(Requests.getCommunityRequest(tempRequest.getCode()));
		Assertions.assertTrue(Users.isMemberOfCommunity(tempUser.getId(), comm.getId()));
		Assertions.assertTrue(Users.deleteUser(tempUser.getId()));
	}
	
	@StarexecTest
	private void declineCommunityRequestTest() throws StarExecDatabaseException {
		User tempUser=loader.loadUserIntoDatabase();
		CommunityRequest tempRequest=loader.loadCommunityRequestIntoDatabase(tempUser.getId(), comm.getId());
		Assertions.assertFalse(Users.isMemberOfCommunity(tempUser.getId(), comm.getId()));

		Assertions.assertTrue(Requests.declineCommunityRequest(tempUser.getId(), comm.getId()));
		Assertions.assertNull(Requests.getCommunityRequest(tempRequest.getCode()));
		Assertions.assertFalse(Users.isMemberOfCommunity(tempUser.getId(), comm.getId()));
		Assertions.assertTrue(Users.deleteUser(tempUser.getId()));
	}
	
	@StarexecTest
	private void addAndRedeemPassResetRequestTest() {
		String randomCode=UUID.randomUUID().toString();
		Assertions.assertTrue(Requests.addPassResetRequest(registeredUser.getId(), randomCode));
		int userId=Requests.redeemPassResetRequest(randomCode);
		Assertions.assertEquals(registeredUser.getId(),userId);
	}
	
	@StarexecTest
	private void addCommunityRequestTest() {
		String randomMessage=TestUtil.getRandomAlphaString(30);
		String randomCode=UUID.randomUUID().toString();
		Assertions.assertTrue(Requests.addCommunityRequest(registeredUser, comm.getId(), randomCode, randomMessage));
		Assertions.assertNotNull(Requests.getCommunityRequest(randomCode));
	}
	
	
	@Override
	protected String getTestName() {
		return "RequestsTests";
	}

	@Override
	protected void setup() throws Exception {
		registeredUser=loader.loadUserIntoDatabase();
		requestedUser=loader.loadUserIntoDatabase();
   
		admin=loader.loadUserIntoDatabase(TestUtil.getRandomAlphaString(10),TestUtil.getRandomAlphaString(10),TestUtil.getRandomPassword(),TestUtil.getRandomPassword(),"The University of Miami",R.ADMIN_ROLE_NAME);
		comm=loader.loadSpaceIntoDatabase(admin.getId(), 1);
		comm2=loader.loadSpaceIntoDatabase(admin.getId(), 1);
		request= loader.loadCommunityRequestIntoDatabase(requestedUser.getId(), comm.getId());
	}

	@Override
	protected void teardown() throws Exception {
		loader.deleteAllPrimitives();
		
	}

}
