package org.starexec.test.integration.database;

import org.junit.jupiter.api.Assertions;
import org.starexec.constants.R;
import org.starexec.data.database.*;
import org.starexec.data.to.*;
import org.starexec.exceptions.StarExecDatabaseException;
import org.starexec.test.TestUtil;
import org.starexec.test.integration.StarexecTest;
import org.starexec.test.integration.TestSequence;
import org.starexec.test.resources.ResourceLoader;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for org.starexec.data.database.Permissions.java
 * @author Eric
 */
public class PermissionsTests extends TestSequence {
	User owner=null;		// user that owns primitives created in this test
	User spaceMember=null;  // user that shares a space with prims.
	User noPerms=null; 		// user does not share any space with prims
	User admin=null;
	User developer = null;
	
	Space space=null;
	
	Solver solver=null;
	Solver solver2=null;
	List<Integer> benchmarks=null;
	Benchmark benchmarkDownloadable=null; //these two benchmarks are the first and second benchmark from the "benchmarks" array
	Benchmark benchmarkNoDownload=null;
	Job job = null;
	@StarexecTest
	private void CanSeeBenchmarkTest() {
		int benchId=benchmarks.get(0);
		Assertions.assertTrue(Permissions.canUserSeeBench(benchId, owner.getId()));
		Assertions.assertTrue(Permissions.canUserSeeBench(benchId, admin.getId()));
		Assertions.assertTrue(Permissions.canUserSeeBench(benchId, spaceMember.getId()));
		Assertions.assertTrue(Permissions.canUserSeeBench(benchId, developer.getId()));

		Assertions.assertFalse(Permissions.canUserSeeBench(benchId, noPerms.getId()));
	}
	
	@StarexecTest
	private void CanSeeBenchmarksTest() {
		Assertions.assertTrue(Permissions.canUserSeeBenchs(benchmarks, owner.getId()));
		Assertions.assertTrue(Permissions.canUserSeeBenchs(benchmarks, admin.getId()));
		Assertions.assertTrue(Permissions.canUserSeeBenchs(benchmarks, spaceMember.getId()));
		Assertions.assertTrue(Permissions.canUserSeeBenchs(benchmarks, developer.getId()));

		Assertions.assertFalse(Permissions.canUserSeeBenchs(benchmarks, noPerms.getId()));
	}
	
	@StarexecTest
	private void CanSeeSolverTest() {
		int solverId=solver.getId();
		Assertions.assertTrue(Permissions.canUserSeeSolver(solverId, owner.getId()));
		Assertions.assertTrue(Permissions.canUserSeeSolver(solverId, admin.getId()));
		Assertions.assertTrue(Permissions.canUserSeeSolver(solverId, spaceMember.getId()));
		Assertions.assertTrue(Permissions.canUserSeeSolver(solverId, developer.getId()));

		Assertions.assertFalse(Permissions.canUserSeeSolver(solverId, noPerms.getId()));
	}
	
	@StarexecTest 
	private void CanSeeSolversTest() {
		List<Integer> solvers= new ArrayList<>();
		solvers.add(solver.getId());
		solvers.add(solver2.getId());
		Assertions.assertTrue(Permissions.canUserSeeSolvers(solvers, owner.getId()));
		Assertions.assertTrue(Permissions.canUserSeeSolvers(solvers, admin.getId()));
		Assertions.assertTrue(Permissions.canUserSeeSolvers(solvers, spaceMember.getId()));
		Assertions.assertTrue(Permissions.canUserSeeSolvers(solvers, developer.getId()));

		Assertions.assertFalse(Permissions.canUserSeeSolvers(solvers, noPerms.getId()));
	}
	
	@StarexecTest
	private void canSeeJobTest() {
		Assertions.assertTrue(Permissions.canUserSeeJob(job.getId(), owner.getId()).isSuccess());
		Assertions.assertTrue(Permissions.canUserSeeJob(job.getId(), spaceMember.getId()).isSuccess());
		Assertions.assertTrue(Permissions.canUserSeeJob(job.getId(), admin.getId()).isSuccess());
		Assertions.assertTrue(Permissions.canUserSeeJob(job.getId(), developer.getId()).isSuccess());

		Assertions.assertFalse(Permissions.canUserSeeJob(job.getId(), noPerms.getId()).isSuccess());
	}
	
	@StarexecTest
	private void canSeeSpaceTest() {
		Assertions.assertTrue(Permissions.canUserSeeSpace(space.getId(), owner.getId()));
		Assertions.assertTrue(Permissions.canUserSeeSpace(space.getId(), spaceMember.getId()));
		Assertions.assertTrue(Permissions.canUserSeeSpace(space.getId(), admin.getId()));
		Assertions.assertTrue(Permissions.canUserSeeSpace(space.getId(), developer.getId()));
		
		Assertions.assertFalse(Permissions.canUserSeeSpace(space.getId(), noPerms.getId()));
	}
	
	@StarexecTest
	private void canSeeRootTest() {
		Assertions.assertTrue(Permissions.canUserSeeSpace(1, noPerms.getId()));
	}
	
	@StarexecTest
	private void canSeePublicSpaceTest() {
		Spaces.setPublicSpace(space.getId(), owner.getId(), true, false);
		Assertions.assertTrue(Permissions.canUserSeeSpace(space.getId(), noPerms.getId()));
		Spaces.setPublicSpace(space.getId(), owner.getId(), false, false);
	}
	
	
	@StarexecTest
	private void GetSpaceDefaultTest() throws StarExecDatabaseException {
		space.getPermission().setAddSolver(true);
		space.getPermission().setAddSpace(false);
		Spaces.updateDetails(owner.getId(), space);
		Permission p=Permissions.getSpaceDefault(space.getId());
		Assertions.assertNotNull(p);
		Assertions.assertFalse(p.isLeader());
		Assertions.assertTrue(p.canAddSolver());
		Assertions.assertFalse(p.canAddSpace());
	}
	
	
	@StarexecTest
	private void FullPermissionsTest() {
		Permission p=Permissions.getFullPermission();
		Assertions.assertTrue(p.canAddBenchmark());
		Assertions.assertTrue(p.canAddSolver());
		Assertions.assertTrue(p.canAddUser());
		Assertions.assertTrue(p.canAddJob());
		Assertions.assertTrue(p.canAddSpace());
		Assertions.assertTrue(p.canRemoveBench());
		Assertions.assertTrue(p.canRemoveSolver());
		Assertions.assertTrue(p.canRemoveJob());
		Assertions.assertTrue(p.canRemoveSpace());
		Assertions.assertTrue(p.canRemoveUser());
		Assertions.assertTrue(p.isLeader());
	}
	
	@StarexecTest
	private void EmptyPermissionsTest() {
		Permission p=Permissions.getEmptyPermission();
		Assertions.assertFalse(p.canAddBenchmark());
		Assertions.assertFalse(p.canAddSolver());
		Assertions.assertFalse(p.canAddUser());
		Assertions.assertFalse(p.canAddJob());
		Assertions.assertFalse(p.canAddSpace());
		Assertions.assertFalse(p.canRemoveBench());
		Assertions.assertFalse(p.canRemoveSolver());
		Assertions.assertFalse(p.canRemoveJob());
		Assertions.assertFalse(p.canRemoveSpace());
		Assertions.assertFalse(p.canRemoveUser());
		Assertions.assertFalse(p.isLeader());
	}
	
	@StarexecTest
	private void setPermissionsTest() {
		Permission oldPerms = Permissions.get(spaceMember.getId(), space.getId());
		Permission p = Permissions.getFullPermission();
		p.setAddJob(false);
		p.setRemoveBench(false);
		Assertions.assertTrue(Permissions.set(spaceMember.getId(), space.getId(), p));
		Permission newPerms = Permissions.get(spaceMember.getId(), space.getId());
		Assertions.assertFalse(newPerms.canAddJob());
		Assertions.assertFalse(newPerms.canRemoveBench());
		Assertions.assertEquals(2, newPerms.getOffPermissions().size());
		Assertions.assertTrue(Permissions.set(spaceMember.getId(), space.getId(), oldPerms));
	}

	@StarexecTest
	private void permissionUpsertBehaviorTest() {
		// Test that SetUserPermissions2 correctly handles INSERT...ON CONFLICT for both new and existing records
		User testUser = null;
		try {
			testUser = Users.get(owner.getId()); // Use existing user
			
			// Set permissions first time (should work with INSERT...ON CONFLICT)
			Permission perm1 = Permissions.getFullPermission();
			boolean firstSet = Permissions.set(testUser.getId(), space.getId(), perm1);
			Assertions.assertTrue(firstSet, "First permission set should succeed");
			
			// Retrieve and verify
			Permission retrieved1 = Permissions.get(testUser.getId(), space.getId());
			Assertions.assertNotNull(retrieved1, "Permission should exist after first set");
			Assertions.assertTrue(retrieved1.isLeader(), "Should have leader permission");
			
			// Update permissions second time (should work with INSERT...ON CONFLICT DO UPDATE)
			Permission perm2 = Permissions.getFullPermission();
			perm2.setAddJob(false);
			boolean secondSet = Permissions.set(testUser.getId(), space.getId(), perm2);
			Assertions.assertTrue(secondSet, "Second permission set should succeed");
			
			// Retrieve and verify update persisted
			Permission retrieved2 = Permissions.get(testUser.getId(), space.getId());
			Assertions.assertNotNull(retrieved2, "Permission should exist after update");
			Assertions.assertFalse(retrieved2.canAddJob(), "Updated permission should persist");
		} catch (Exception e) {
			Assertions.fail("Upsert test failed: " + e.getMessage());
		}
	}

	@StarexecTest
	private void permissionUpdateReturnValueTest() {
		// Test that permission update return values are properly handled
		User testUser = null;
		try {
			testUser = Users.get(spaceMember.getId());
			
			// Update permissions and verify return value
			Permission newPerm = Permissions.getFullPermission();
			newPerm.setRemoveSolver(false);
			
			boolean updateResult = Permissions.set(testUser.getId(), space.getId(), newPerm);
			Assertions.assertTrue(updateResult, "Permission update should return true on success");
			
			// Verify the update actually persisted
			Permission retrieved = Permissions.get(testUser.getId(), space.getId());
			Assertions.assertFalse(retrieved.canRemoveSolver(), "Permission modification should persist in database");
		} catch (Exception e) {
			Assertions.fail("Permission update return value test failed: " + e.getMessage());
		}
	}

	@StarexecTest
	private void multiplePermissionUpdatesTest() {
		// Test that multiple sequential updates to same user/space work correctly
		try {
			User testUser = Users.get(spaceMember.getId());
			
			// First update
			Permission perm1 = Permissions.getFullPermission();
			perm1.setAddJob(true);
			perm1.setAddSolver(false);
			Permissions.set(testUser.getId(), space.getId(), perm1);
			
			Permission retrieved1 = Permissions.get(testUser.getId(), space.getId());
			Assertions.assertTrue(retrieved1.canAddJob(), "First update: canAddJob");
			Assertions.assertFalse(retrieved1.canAddSolver(), "First update: !canAddSolver");
			
			// Second update to different state
			Permission perm2 = Permissions.getFullPermission();
			perm2.setAddJob(false);
			perm2.setAddSolver(true);
			Permissions.set(testUser.getId(), space.getId(), perm2);
			
			Permission retrieved2 = Permissions.get(testUser.getId(), space.getId());
			Assertions.assertFalse(retrieved2.canAddJob(), "Second update: !canAddJob");
			Assertions.assertTrue(retrieved2.canAddSolver(), "Second update: canAddSolver");
			
			// Reset to original state
			Permission original = Permissions.getFullPermission();
			Permissions.set(testUser.getId(), space.getId(), original);
		} catch (Exception e) {
			Assertions.fail("Multiple updates test failed: " + e.getMessage());
		}
	}

	@StarexecTest
	private void canUserSeePublicJobTest() {
		// This try-with-resources will automatically call .close() on tempLoader at the end.
		try (ResourceLoader tempLoader = new ResourceLoader()) {
			User tempUser = tempLoader.loadUserIntoDatabase();
			Space publicSpace = tempLoader.loadSpaceIntoDatabase(spaceMember.getId(), space.getId());
			Spaces.makeSingleSpacePublic(publicSpace.getId(), spaceMember.getId());
			assertTrue(Spaces.isPublicSpace(publicSpace.getId()), "Space was not public.");
			Job tempJob = tempLoader.loadJobIntoDatabase(publicSpace.getId(), spaceMember.getId(), solver.getId(), benchmarks);
			assertTrue(Jobs.isPublic(tempJob.getId()), "Job was not public.");
			assertTrue(Permissions.canUserSeeJob(tempJob.getId(), spaceMember.getId()).isSuccess(), "Job owner could not see public job.");
			assertTrue(Permissions.canUserSeeJob(tempJob.getId(), admin.getId()).isSuccess(), "Admin could not see public job.");
			assertTrue(Permissions.canUserSeeJob(tempJob.getId(), tempUser.getId()).isSuccess(), "User could not see public job.");
		}
	}
	
	@Override
	protected String getTestName() {
		return "PermissionsTests";
	}

	@Override
	protected void setup() throws Exception {
		owner=loader.loadUserIntoDatabase();
		spaceMember=loader.loadUserIntoDatabase();
		noPerms=loader.loadUserIntoDatabase();
		
		admin=loader.loadUserIntoDatabase(TestUtil.getRandomAlphaString(10),TestUtil.getRandomAlphaString(10),TestUtil.getRandomPassword(),TestUtil.getRandomPassword(),"The University of Miami",R.ADMIN_ROLE_NAME);
		
		space=loader.loadSpaceIntoDatabase(owner.getId(), Communities.getTestCommunity().getId());
		
		Users.associate(spaceMember.getId(), space.getId());
		
		solver=loader.loadSolverIntoDatabase("CVC4.zip", space.getId(), owner.getId());
		solver2=loader.loadSolverIntoDatabase("CVC4.zip", space.getId(), owner.getId());
		benchmarks=loader.loadBenchmarksIntoDatabase("benchmarks.zip", space.getId(), owner.getId());
		benchmarkDownloadable=Benchmarks.get(benchmarks.get(0));
		Benchmarks.updateDetails(benchmarkDownloadable.getId(), benchmarkDownloadable.getName(), 
				benchmarkDownloadable.getDescription(), true, benchmarkDownloadable.getType().getId());
		
		benchmarkNoDownload=Benchmarks.get(benchmarks.get(1));
		Benchmarks.updateDetails(benchmarkNoDownload.getId(), benchmarkNoDownload.getName(), 
				benchmarkNoDownload.getDescription(), false, benchmarkNoDownload.getType().getId());
		job = loader.loadJobIntoDatabase(space.getId(), owner.getId(), solver.getId(), benchmarks);
		developer = loader.loadUserIntoDatabase("test", "test-dev", "test-dev@uiowa.edu", "pass", "Iowa", R.DEVELOPER_ROLE_NAME);

	}

	@Override
	protected void teardown() throws Exception {
		loader.deleteAllPrimitives();
	}

}
