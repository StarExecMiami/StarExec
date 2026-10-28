package org.starexec.test.integration.database;

import org.junit.jupiter.api.Assertions;
import org.starexec.constants.R;
import org.starexec.data.database.*;
import org.starexec.data.to.*;
import org.starexec.data.to.enums.ProcessorType;
import org.starexec.exceptions.StarExecDatabaseException;
import org.starexec.test.TestUtil;
import org.starexec.test.integration.StarexecTest;
import org.starexec.test.integration.TestSequence;
import org.starexec.util.PasswordHasher;
import org.starexec.util.Util;

import java.io.File;
import java.sql.SQLException;
import java.util.*;

/**
 * Tests for org.starexec.data.database.Users.java
 * 
 * @author Eric
 */
public class UserTests extends TestSequence {
	private User testUser = null;
	private User user1 = null;
	private User user2 = null;
	private User user3 = null;
	private User admin = null;
	private Processor postProc = null;
	private Space space = null;
	private Space subspace = null;
	private Space comm = null;
	private final int wallclockTimeout = 100;
	private final int cpuTimeout = 100;
	private final int gbMemory = 1;
	private final String BENCH_ARCHIVE = "benchmarks.zip";

	private boolean removeUserFromSpace(User user, Space space) {
		List<Integer> userId = new ArrayList<>();
		userId.add(user.getId());

		return Spaces.removeUsers(userId, space.getId());
	}

	@StarexecTest
	private void getUserByEmailTest() {
		Assertions.assertFalse(Users.getUserByEmail(TestUtil.getRandomEmail()));
		Assertions.assertTrue(Users.getUserByEmail(user1.getEmail()));
		Assertions.assertTrue(Users.getUserByEmail(admin.getEmail()));

	}

	@StarexecTest
	private void setPasswordTest() {
		String randomPass = TestUtil.getRandomPassword();
		try {
			Assertions.assertTrue(Users.updatePassword(user1.getId(), randomPass));
			String dbHash = Users.getPassword(user1.getId());
			Assertions.assertTrue(PasswordHasher.verify(randomPass, dbHash));
			user1.setPassword(randomPass);
		} catch (StarExecDatabaseException e) {
			Assertions.fail("Caught a StarExecDatabaseException: " + Util.getStackTrace(e));
		}
	}

	@StarexecTest
	private void getCommunitiesTest() {
		List<Integer> commIds = Users.getCommunities(user1.getId());
		Assertions.assertEquals(0, commIds.size());
		commIds = Users.getCommunities(admin.getId());
		Assertions.assertTrue(!commIds.isEmpty());
		boolean found = false;
		for (Integer i : commIds) {
			found = found || comm.getId() == i;
		}
		Assertions.assertTrue(found);
	}

	@StarexecTest
	private void subscribeToErrorLogsTest() {
		Assertions.assertFalse(testUser.isSubscribedToErrorLogs(),
				"Test user shouldn't be subscribed to error reports yet.");
		try {
			Users.subscribeToErrorLogs(testUser.getId());
			User test = Users.get(testUser.getId());
			Assertions.assertTrue(test.isSubscribedToErrorLogs(), "Test user should be subscribed to error reports.");
			Users.unsubscribeUserFromErrorLogs(test.getId());
			test = Users.get(test.getId());
			Assertions.assertFalse(test.isSubscribedToErrorLogs(),
					"Test user should not be subscribed to error reports any more.");
		} catch (StarExecDatabaseException | SQLException e) {
			Assertions.fail("Caught an exception: " + Util.getStackTrace(e));
		}

	}

	@StarexecTest
	private void getUsersSubscribedToErrorLogsTest() {
		try {
			Users.subscribeToErrorLogs(testUser.getId());
			Users.subscribeToErrorLogs(user1.getId());
			Users.subscribeToErrorLogs(user2.getId());

			List<User> users = Users.getUsersSubscribedToErrorLogs();

			Assertions.assertTrue(users.stream().anyMatch(u -> u.getId() == testUser.getId()),
					"testUser should be subscribed to error logs.");
			Assertions.assertTrue(users.stream().anyMatch(u -> u.getId() == user1.getId()),
					"user1 should be subscribed to error logs.");
			Assertions.assertTrue(users.stream().anyMatch(u -> u.getId() == user2.getId()),
					"user2 should be subscribed to error logs.");

			Users.unsubscribeUserFromErrorLogs(testUser.getId());
			Users.unsubscribeUserFromErrorLogs(user1.getId());
			Users.unsubscribeUserFromErrorLogs(user2.getId());

		} catch (StarExecDatabaseException | SQLException e) {
			Assertions.fail("Caught an exception: " + Util.getStackTrace(e));
		}
	}

	@StarexecTest
	private void registerUserTest() {
		User u = new User();
		u.setFirstName(TestUtil.getRandomUserName());
		u.setLastName(TestUtil.getRandomUserName());
		u.setEmail(TestUtil.getRandomEmail());
		u.setPassword(TestUtil.getRandomPassword());
		u.setInstitution(TestUtil.getRandomAlphaString(10));
		String randomCode = UUID.randomUUID().toString();
		String randomMessage = TestUtil.getRandomAlphaString(20);
		Assertions.assertTrue(Users.register(u, comm.getId(), randomCode, randomMessage));
		Assertions.assertNotNull(Users.get(u.getId()));
		Assertions.assertTrue(Requests.declineCommunityRequest(u.getId(), comm.getId()));
		Assertions.assertNull(Users.get(u.getId()));
	}

	@StarexecTest
	private void AssociateOneUserOneSpace() {
		Assertions.assertFalse(Users.isMemberOfSpace(user1.getId(), space.getId()));
		Assertions.assertFalse(Users.isMemberOfSpace(user2.getId(), space.getId()));
		Assertions.assertFalse(Users.isMemberOfSpace(user3.getId(), space.getId()));

		Assertions.assertTrue(Users.associate(user1.getId(), space.getId()));

		Assertions.assertTrue(Users.isMemberOfSpace(user1.getId(), space.getId()));
		Assertions.assertFalse(Users.isMemberOfSpace(user2.getId(), space.getId()));
		Assertions.assertFalse(Users.isMemberOfSpace(user3.getId(), space.getId()));
		Assertions.assertTrue(removeUserFromSpace(user1, space));
	}

	@StarexecTest
	private void AssociateManyUsersOneSpace() {
		Assertions.assertFalse(Users.isMemberOfSpace(user1.getId(), space.getId()));
		Assertions.assertFalse(Users.isMemberOfSpace(user2.getId(), space.getId()));
		Assertions.assertFalse(Users.isMemberOfSpace(user3.getId(), space.getId()));

		List<Integer> ids = new ArrayList<>();
		ids.add(user1.getId());
		ids.add(user2.getId());
		ids.add(user3.getId());

		Assertions.assertTrue(Users.associate(ids, space.getId()));
		Assertions.assertTrue(Users.isMemberOfSpace(user1.getId(), space.getId()));
		Assertions.assertTrue(Users.isMemberOfSpace(user2.getId(), space.getId()));
		Assertions.assertTrue(Users.isMemberOfSpace(user3.getId(), space.getId()));
		Assertions.assertTrue(removeUserFromSpace(user1, space));
		Assertions.assertTrue(removeUserFromSpace(user2, space));
		Assertions.assertTrue(removeUserFromSpace(user3, space));
	}

	@StarexecTest
	private void AssociateManyUsersSpaceHierarchy() {
		Assertions.assertFalse(Users.isMemberOfSpace(user1.getId(), space.getId()));
		Assertions.assertFalse(Users.isMemberOfSpace(user1.getId(), subspace.getId()));
		Assertions.assertFalse(Users.isMemberOfSpace(user2.getId(), space.getId()));
		Assertions.assertFalse(Users.isMemberOfSpace(user2.getId(), subspace.getId()));

		List<Integer> ids = new ArrayList<>();
		ids.add(user1.getId());
		ids.add(user2.getId());
		Assertions.assertTrue(Users.associate(ids, space.getId(), true, testUser.getId()));
		Assertions.assertTrue(Users.isMemberOfSpace(user1.getId(), space.getId()));
		Assertions.assertTrue(Users.isMemberOfSpace(user1.getId(), subspace.getId()));
		Assertions.assertTrue(Users.isMemberOfSpace(user2.getId(), space.getId()));
		Assertions.assertTrue(Users.isMemberOfSpace(user2.getId(), subspace.getId()));

		Assertions.assertTrue(removeUserFromSpace(user1, space));
		Assertions.assertTrue(removeUserFromSpace(user1, subspace));
		Assertions.assertTrue(removeUserFromSpace(user2, space));
		Assertions.assertTrue(removeUserFromSpace(user2, subspace));
	}

	@StarexecTest
	private void AssociateManyUsersManySpaces() {
		Assertions.assertFalse(Users.isMemberOfSpace(user1.getId(), space.getId()));
		Assertions.assertFalse(Users.isMemberOfSpace(user1.getId(), subspace.getId()));
		Assertions.assertFalse(Users.isMemberOfSpace(user2.getId(), space.getId()));
		Assertions.assertFalse(Users.isMemberOfSpace(user2.getId(), subspace.getId()));

		List<Integer> ids = new ArrayList<>();
		List<Integer> spaceIds = new ArrayList<>();
		ids.add(user1.getId());
		ids.add(user2.getId());

		spaceIds.add(space.getId());
		spaceIds.add(subspace.getId());

		Assertions.assertTrue(Users.associate(ids, spaceIds));
		Assertions.assertTrue(Users.isMemberOfSpace(user1.getId(), space.getId()));
		Assertions.assertTrue(Users.isMemberOfSpace(user1.getId(), subspace.getId()));
		Assertions.assertTrue(Users.isMemberOfSpace(user2.getId(), space.getId()));
		Assertions.assertTrue(Users.isMemberOfSpace(user2.getId(), subspace.getId()));

		Assertions.assertTrue(removeUserFromSpace(user1, space));
		Assertions.assertTrue(removeUserFromSpace(user1, subspace));
		Assertions.assertTrue(removeUserFromSpace(user2, space));
		Assertions.assertTrue(removeUserFromSpace(user2, subspace));

	}

	@StarexecTest
	private void DeleteUserTest() {
		try {
			User temp = loader.loadUserIntoDatabase();
			Assertions.assertNotNull(Users.get(temp.getId()));

			Assertions.assertTrue(Users.deleteUser(temp.getId()));

			Assertions.assertNull(Users.get(temp.getId()));
		} catch (StarExecDatabaseException e) {
			Assertions.fail("Caught a StarExecDatabaseException: " + Util.getStackTrace(e));
		}
	}

	/**
	 * Tests that a user's solvers are deleted when the user is deleted.
	 * 
	 * @author Albert Giegerich
	 */
	@StarexecTest
	private void DeleteUserDeletesUsersSolversTest() {
		try {
			User tempUser = loader.loadUserIntoDatabase();
			Solver tempSolver = loader.loadSolverIntoDatabase(space.getId(), tempUser.getId());

			Users.deleteUser(tempUser.getId());

			Assertions.assertNull(Solvers.get(tempSolver.getId()));
		} catch (StarExecDatabaseException e) {
			Assertions.fail("Caught a StarExecDatabaseException: " + Util.getStackTrace(e));
		}
	}

	/**
	 * Tests that a user's solver directory is deleted when the user is deleted.
	 * 
	 * @author Albert Giegerich
	 */
	@StarexecTest
	private void DeleteUserDeletesUsersSolverDirectoryTest() {
		try {
			User tempUser = loader.loadUserIntoDatabase();
			loader.loadSolverIntoDatabase(space.getId(), tempUser.getId());
			File tempUsersSolverDirectory = new File(R.getSolverPath() + "/" + tempUser.getId());
			Assertions.assertTrue(tempUsersSolverDirectory.exists());

			Users.deleteUser(tempUser.getId());

			Assertions.assertFalse(tempUsersSolverDirectory.exists());
		} catch (StarExecDatabaseException e) {
			Assertions.fail("Caught a StarExecDatabaseException: " + Util.getStackTrace(e));
		}
	}

	/**
	 * Tests that a user's benchmarks are deleted when the user is deleted.
	 * 
	 * @author Albert Giegerich
	 */
	@StarexecTest
	private void DeleteUserDeletesUsersBenchmarkTest() {
		try {
			User tempUser = loader.loadUserIntoDatabase();
			List<Integer> tempBenchmarkIds = loader.loadBenchmarksIntoDatabase(BENCH_ARCHIVE, space.getId(),
					tempUser.getId());

			Users.deleteUser(tempUser.getId());

			for (Integer benchmarkId : tempBenchmarkIds) {
				Assertions.assertNotNull(benchmarkId);
				Assertions.assertNull(Benchmarks.get(benchmarkId));
			}
		} catch (StarExecDatabaseException e) {
			Assertions.fail("Caught a StarExecDatabaseException: " + Util.getStackTrace(e));
		}
	}

	/**
	 * Tests that a user's benchmark directory is deleted when the user is deleted.
	 * 
	 * @author Albert Giegerich
	 */
	@StarexecTest
	private void DeleteUserDeletesUsersBenchmarkDirectoryTest() {
		try {
			User tempUser = loader.loadUserIntoDatabase();
			loader.loadBenchmarksIntoDatabase(BENCH_ARCHIVE, space.getId(), tempUser.getId());
			File tempUsersBenchmarkDirectory = new File(R.getBenchmarkPath() + "/" + tempUser.getId());
			Assertions.assertTrue(tempUsersBenchmarkDirectory.exists());

			Users.deleteUser(tempUser.getId());

			Assertions.assertFalse(tempUsersBenchmarkDirectory.exists());
		} catch (StarExecDatabaseException e) {
			Assertions.fail("Caught a StarExecDatabaseException: " + Util.getStackTrace(e));
		}
	}

	/**
	 * Tests that deleting a user also deletes that user's jobs.
	 * 
	 * @author Albert Giegerich
	 */
	@StarexecTest
	private void DeleteUserDeletesUsersJobsTest() {
		try {
			User tempUser = loader.loadUserIntoDatabase();
			Solver tempSolver = loader.loadSolverIntoDatabase(space.getId(), tempUser.getId());
			List<Integer> tempSolverIds = Collections.singletonList(tempSolver.getId());
			List<Integer> tempBenchmarkIds = loader.loadBenchmarksIntoDatabase(BENCH_ARCHIVE, space.getId(),
					tempUser.getId());

			Job tempJob = loader.loadJobIntoDatabase(
					space.getId(), tempUser.getId(), -1, postProc.getId(), tempSolverIds, tempBenchmarkIds, cpuTimeout,
					wallclockTimeout, gbMemory);
			Assertions.assertNotNull(tempJob);

			Users.deleteUser(tempUser.getId());

			Assertions.assertNull(Jobs.get(tempJob.getId()));
		} catch (StarExecDatabaseException e) {
			Assertions.fail("Caught a StarExecDatabaseException: " + Util.getStackTrace(e));
		}
	}

	/**
	 * Asserts that job pairs are deleted from the database successfully.
	 * 
	 * @author Albert Giegerich
	 */
	@StarexecTest
	private void DeleteUserDeletesJobPairsTest() {
		try {
			User tempUser = loader.loadUserIntoDatabase();
			Solver tempSolver = loader.loadSolverIntoDatabase(space.getId(), tempUser.getId());
			List<Integer> tempSolverIds = Collections.singletonList(tempSolver.getId());
			List<Integer> tempBenchmarkIds = loader.loadBenchmarksIntoDatabase(BENCH_ARCHIVE, space.getId(),
					tempUser.getId());

			Job tempJob = loader.loadJobIntoDatabase(
					space.getId(), tempUser.getId(), -1, postProc.getId(), tempSolverIds, tempBenchmarkIds, cpuTimeout,
					wallclockTimeout, gbMemory);
			Assertions.assertNotNull(tempJob);

			Assertions.assertNotNull(tempJob.getJobPairs());
			Assertions.assertTrue(!tempJob.getJobPairs().isEmpty());

			for (JobPair pair : tempJob.getJobPairs()) {
				Assertions.assertNotNull(pair);
			}
			Users.deleteUser(tempUser.getId());

			for (JobPair pair : tempJob.getJobPairs()) {
				JobPair currentPair = JobPairs.getPair(pair.getId());
				Assertions.assertNull(currentPair);
			}
		} catch (StarExecDatabaseException e) {
			Assertions.fail("Caught a StarExecDatabaseException: " + Util.getStackTrace(e));
		}
	}

	@StarexecTest
	private void DeleteUserDeletesJobDirectoriesTest() {
		try {
			User tempUser = loader.loadUserIntoDatabase();
			Solver tempSolver = loader.loadSolverIntoDatabase(space.getId(), tempUser.getId());
			List<Integer> tempSolverIds = Collections.singletonList(tempSolver.getId());
			List<Integer> tempBenchmarkIds = loader.loadBenchmarksIntoDatabase(BENCH_ARCHIVE, space.getId(),
					tempUser.getId());

			Job tempJob = loader.loadJobIntoDatabase(
					space.getId(), tempUser.getId(), -1, postProc.getId(), tempSolverIds, tempBenchmarkIds, cpuTimeout,
					wallclockTimeout, gbMemory);
			Assertions.assertNotNull(tempJob);

			File jobDirectory = new File(Jobs.getDirectory(tempJob.getId()));

			Assertions.assertTrue(jobDirectory.exists(), "Job directory was not created.");

			Users.deleteUser(tempUser.getId());

			Assertions.assertFalse(jobDirectory.exists(), "Job directory still exists.");
		} catch (StarExecDatabaseException e) {
			Assertions.fail("Caught a StarExecDatabaseException: " + Util.getStackTrace(e));
		}
	}

	@StarexecTest
	private void GetUserTest() {
		User temp = Users.get(user1.getId());

		Assertions.assertNotNull(temp);

		Assertions.assertEquals(user1.getId(), temp.getId());
		Assertions.assertEquals(user1.getFirstName(), temp.getFirstName());

		// should get null for a non-user
		Assertions.assertNull(Users.get(-1));
	}

	@StarexecTest
	private void GetUserByEmailTest() {
		User temp = Users.get(user1.getEmail());

		Assertions.assertNotNull(temp);

		Assertions.assertEquals(user1.getId(), temp.getId());
		Assertions.assertEquals(user1.getFirstName(), temp.getFirstName());

		// should get null for a non-user
		Assertions.assertNull(Users.get(-1));

	}

	@StarexecTest
	private void GetAdminsTest() {
		List<User> admins = Users.getAdmins();
		for (User u : admins) {
			Assertions.assertTrue(Users.get(u.getId()).getRole().equals("admin"));
			Assertions.assertTrue(Users.isAdmin(u.getId()));
			Assertions.assertFalse(Users.isNormalUser(u.getId()));
		}
	}

	@StarexecTest
	private void GetCountTest() {
		try {
			int count = Users.getCount();
			Assertions.assertNotEquals(0, count);
			User temp = loader.loadUserIntoDatabase();

			// this might fail if another user is added to the system at exactly this time,
			// but that would be atypical, and failure is not highly costly
			Assertions.assertEquals(count + 1, Users.getCount());
			Assertions.assertTrue(Users.deleteUser(temp.getId()));
		} catch (StarExecDatabaseException e) {
			Assertions.fail("Caught a StarExecDatabaseException: " + Util.getStackTrace(e));
		}
	}

	@StarexecTest
	private void GetCountInSpaceTest() {
		int count = Spaces.getUsers(space.getId()).size();
		Assertions.assertEquals(count, Users.getCountInSpace(space.getId()));
	}

	@StarexecTest
	private void GetCountInSpaceQueryTest() {
		Assertions.assertEquals(1, Users.getCountInSpace(space.getId(), testUser.getFirstName()));
	}

	@StarexecTest
	private void GetDiskUsageTest() {
		try {
			Assertions.assertEquals(0, Users.getDiskUsage(user1.getId()));
			Solver solver = loader.loadSolverIntoDatabase("CVC4.zip", space.getId(), user1.getId());

			long size = Solvers.get(solver.getId()).getDiskSize();
			List<Integer> benchmarkIds = loader.loadBenchmarksIntoDatabase("benchmarks.zip", space.getId(),
					user1.getId());
			for (Integer i : benchmarkIds) {
				size += Benchmarks.get(i).getDiskSize();
			}
			Assertions.assertEquals(size, Users.getDiskUsage(user1.getId()));
			Assertions.assertTrue(Solvers.deleteAndRemoveSolver(solver.getId()));
			for (Integer i : benchmarkIds) {
				Assertions.assertTrue(Benchmarks.deleteAndRemoveBenchmark(i));
			}
		} catch (StarExecDatabaseException e) {
			Assertions.fail("Caught a StarExecDatabaseException: " + Util.getStackTrace(e));
		}
	}

	@StarexecTest
	private void SetDiskQuotaTest() {
		long quota = Users.get(user1.getId()).getDiskQuota();
		Assertions.assertTrue(Users.setDiskQuota(user1.getId(), quota + 1));
		Assertions.assertEquals(quota + 1, Users.get(user1.getId()).getDiskQuota());

	}

	@StarexecTest
	private void SetAndGetDefaultPageSize() {
		try {
			int pageSize = Users.getDefaultPageSize(user1.getId());
			Assertions.assertTrue(Users.setDefaultPageSize(user1.getId(), pageSize + 1));
			Assertions.assertEquals(pageSize + 1, Users.getDefaultPageSize(user1.getId()));
		} catch (StarExecDatabaseException e) {
			Assertions.fail("Caught a StarExecDatabaseException: " + Util.getStackTrace(e));
		}
		Assertions.assertFalse(Users.isAdmin(user3.getId()));
		Assertions.assertFalse(Users.isAdmin(testUser.getId()));
		List<Integer> admins = new ArrayList<>();
		for (Integer i : admins) {
			Assertions.assertTrue(Users.isAdmin(i));
		}
	}

	@StarexecTest
	private void updateFirstNameTest() {
		try {
			String originalName = user1.getFirstName();
			String newName = TestUtil.getRandomUserName();
			Assertions.assertEquals(originalName, Users.get(user1.getId()).getFirstName());
			Assertions.assertTrue(Users.updateFirstName(user1.getId(), newName));
			Assertions.assertEquals(newName, Users.get(user1.getId()).getFirstName());
			user1.setFirstName(newName);
		} catch (StarExecDatabaseException e) {
			Assertions.fail("Caught a StarExecDatabaseException: " + Util.getStackTrace(e));
		}
	}

	@StarexecTest
	private void updateLastNameTest() {
		try {
			String originalName = user1.getLastName();
			String newName = TestUtil.getRandomUserName();
			Assertions.assertEquals(originalName, Users.get(user1.getId()).getLastName());
			Assertions.assertTrue(Users.updateLastName(user1.getId(), newName));
			Assertions.assertEquals(newName, Users.get(user1.getId()).getLastName());
			user1.setLastName(newName);
		} catch (StarExecDatabaseException e) {
			Assertions.fail("Caught a StarExecDatabaseException: " + Util.getStackTrace(e));
		}
	}

	@StarexecTest
	private void updateInstitutionTest() {
		try {
			String originalInst = user1.getInstitution();
			String newInst = TestUtil.getRandomUserName();
			Assertions.assertEquals(originalInst, Users.get(user1.getId()).getInstitution());
			Assertions.assertTrue(Users.updateInstitution(user1.getId(), newInst));
			Assertions.assertEquals(newInst, Users.get(user1.getId()).getInstitution());
			user1.setInstitution(newInst);
		} catch (StarExecDatabaseException e) {
			Assertions.fail("Caught a StarExecDatabaseException: " + Util.getStackTrace(e));
		}
	}

	@StarexecTest
	private void SuspendAndReinstateTest() {
		Assertions.assertFalse(Users.isSuspended(user1.getId()));
		Assertions.assertTrue(Users.suspend(user1.getId()));
		Assertions.assertTrue(Users.isSuspended(user1.getId()));
		Assertions.assertTrue(Users.reinstate(user1.getId()));
		Assertions.assertFalse(Users.isSuspended(user1.getId()));
	}

	@StarexecTest
	private void createNewDefaultSettingsTest() {
		DefaultSettings d = new DefaultSettings();
		d.setCpuTimeout(101);
		d.setWallclockTimeout(2421);
		int id = Users.createNewDefaultSettings(d);
		try {
			Assertions.assertEquals(d, Settings.getProfileById(id));
		} catch (SQLException e) {
			Assertions.fail("Caught SQLException: " + Util.getStackTrace(e));
		}
	}

	@StarexecTest
	private void subscribeAndUnsubscribeTest() {
		Assertions.assertTrue(Users.subscribeToReports(user1.getId()));
		boolean found = false;
		for (User u : Users.getAllUsersSubscribedToReports()) {
			found = found || u.getId() == user1.getId();
		}

		Assertions.assertTrue(found);

		Assertions.assertTrue(Users.unsubscribeFromReports(user1.getId()));
		found = false;
		for (User u : Users.getAllUsersSubscribedToReports()) {
			found = found || u.getId() == user1.getId();
		}
		Assertions.assertFalse(found);
	}

	@StarexecTest
	private void getUnregisteredTest() {
		Assertions.assertNull(Users.getUnregistered(user1.getId()));
		User unregisteredUser = loader.loadUserIntoDatabase("test", "user", "temp@fake.com", "abc", "Iowa",
				R.UNAUTHORIZED_ROLE_NAME);
		Assertions.assertEquals(unregisteredUser.getId(), Users.getUnregistered(unregisteredUser.getId()).getId());
	}

	@Override
	protected String getTestName() {
		return "UserTests";
	}

	@Override
	protected void setup() throws Exception {
		user1 = loader.loadUserIntoDatabase();
		user2 = loader.loadUserIntoDatabase();
		user3 = loader.loadUserIntoDatabase();
		postProc = loader.loadProcessorIntoDatabase("postproc.zip", ProcessorType.POST,
				Communities.getTestCommunity().getId());
		testUser = loader.loadUserIntoDatabase();
		admin = loader.loadUserIntoDatabase(TestUtil.getRandomAlphaString(10), TestUtil.getRandomAlphaString(10),
				TestUtil.getRandomPassword(), TestUtil.getRandomPassword(), "The University of Miami",
				R.ADMIN_ROLE_NAME);
		space = loader.loadSpaceIntoDatabase(testUser.getId(), Communities.getTestCommunity().getId());
		subspace = loader.loadSpaceIntoDatabase(testUser.getId(), space.getId());
		comm = loader.loadSpaceIntoDatabase(admin.getId(), 1);
		Users.associate(admin.getId(), space.getId());
	}

	@Override
	protected void teardown() throws Exception {
		loader.deleteAllPrimitives();
	}

}
