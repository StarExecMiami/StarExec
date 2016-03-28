package org.starexec.test.integration.security;

import org.junit.Assert;
import org.starexec.constants.R;
import org.starexec.data.database.Solvers;
import org.starexec.data.database.Spaces;
import org.starexec.data.database.Users;
import org.starexec.data.database.Websites;
import org.starexec.data.security.WebsiteSecurity;
import org.starexec.data.to.*;
import org.starexec.data.to.Website.WebsiteType;
import org.starexec.test.integration.StarexecTest;
import org.starexec.test.integration.TestSequence;
import org.starexec.test.resources.ResourceLoader;

/**
 * Contains tests for the class WebsiteSecurity.java
 * @author Eric
 *
 */
public class WebsiteSecurityTests extends TestSequence {
	User owner=null;
	User nonOwner=null;
	User admin=null;
	Space space=null;
	Solver solver = null;
	@StarexecTest
	private void canAddWebsiteBadNameTest() {
		Assert.assertFalse(WebsiteSecurity.canUserAddWebsite(space.getId(),R.SPACE, owner.getId(),"<script>","http://www.fake.com").isSuccess());
		Assert.assertFalse(WebsiteSecurity.canUserAddWebsite(space.getId(),R.SPACE, admin.getId(),"<script>","http://www.fake.com").isSuccess());
	}
	
	@StarexecTest
	private void canAddWebsiteBadURLTest() {
		Assert.assertFalse(WebsiteSecurity.canUserAddWebsite(space.getId(),R.SPACE, owner.getId(),"new","<script>").isSuccess());
		Assert.assertFalse(WebsiteSecurity.canUserAddWebsite(space.getId(),R.SPACE, admin.getId(),"new","<script>").isSuccess());
	}
	
	@StarexecTest
	private void canAddWebsiteBadTypeTest() {
		Assert.assertFalse(WebsiteSecurity.canUserAddWebsite(space.getId(),R.JOB, owner.getId(),"new","http://www.fake.com").isSuccess());
		Assert.assertFalse(WebsiteSecurity.canUserAddWebsite(space.getId(),R.JOB, admin.getId(),"new","http://www.fake.com").isSuccess());
	}
	
	@StarexecTest
	private void CanAssociateSpaceWebsiteTest() {
		Assert.assertTrue(WebsiteSecurity.canUserAddWebsite(space.getId(),R.SPACE, owner.getId(),"new","http://www.fake.com").isSuccess());
		Assert.assertTrue(WebsiteSecurity.canUserAddWebsite(space.getId(),R.SPACE, admin.getId(),"new","http://www.fake.com").isSuccess());
		
		Assert.assertFalse(WebsiteSecurity.canUserAddWebsite(space.getId(),R.SPACE, nonOwner.getId(),"new","http://www.fake.com").isSuccess());
	}
	
	@StarexecTest
	private void CanDeleteSpaceWebsiteTest() {
		Websites.add(space.getId(), "https://www.fake.edu", "new", WebsiteType.SPACE);
		int websiteId=Websites.getAll(space.getId(), WebsiteType.SPACE).get(0).getId();
		Assert.assertTrue(WebsiteSecurity.canUserDeleteWebsite(websiteId, owner.getId()).isSuccess());
		Assert.assertTrue(WebsiteSecurity.canUserDeleteWebsite(websiteId, admin.getId()).isSuccess());

		Assert.assertFalse(WebsiteSecurity.canUserDeleteWebsite(websiteId, nonOwner.getId()).isSuccess());
		
		Assert.assertFalse(WebsiteSecurity.canUserDeleteWebsite(-1, owner.getId()).isSuccess());
		Assert.assertFalse(WebsiteSecurity.canUserDeleteWebsite(-1, admin.getId()).isSuccess());
	}
	
	@StarexecTest
	private void CanAssociateSolverWebsiteTest() {
		Assert.assertTrue(WebsiteSecurity.canUserAddWebsite(solver.getId(),R.SOLVER, owner.getId(),"new","http://www.fake.com").isSuccess());
		Assert.assertTrue(WebsiteSecurity.canUserAddWebsite(solver.getId(),R.SOLVER, admin.getId(),"new","http://www.fake.com").isSuccess());
		
		Assert.assertFalse(WebsiteSecurity.canUserAddWebsite(solver.getId(),R.SOLVER, nonOwner.getId(),"new","http://www.fake.com").isSuccess());
	}
	
	@StarexecTest
	private void CanDeleteSolverWebsiteTest() {
		Websites.add(solver.getId(), "https://www.fake.edu", "new", WebsiteType.SOLVER);
		int websiteId=Websites.getAll(solver.getId(), WebsiteType.SOLVER).get(0).getId();
		Assert.assertTrue(WebsiteSecurity.canUserDeleteWebsite(websiteId, owner.getId()).isSuccess());
		Assert.assertTrue(WebsiteSecurity.canUserDeleteWebsite(websiteId, admin.getId()).isSuccess());
		
		Assert.assertFalse(WebsiteSecurity.canUserDeleteWebsite(websiteId, nonOwner.getId()).isSuccess());
		Assert.assertFalse(WebsiteSecurity.canUserDeleteWebsite(-1, owner.getId()).isSuccess());
		Assert.assertFalse(WebsiteSecurity.canUserDeleteWebsite(-1, admin.getId()).isSuccess());
	}
	
	@StarexecTest
	private void CanAssociateUserWebsiteTest() {
		Assert.assertTrue(WebsiteSecurity.canUserAddWebsite(owner.getId(),R.USER, owner.getId(),"new","http://www.fake.com").isSuccess());
		Assert.assertTrue(WebsiteSecurity.canUserAddWebsite(owner.getId(),R.USER, admin.getId(),"new","http://www.fake.com").isSuccess());
		
		Assert.assertFalse(WebsiteSecurity.canUserAddWebsite(owner.getId(),R.USER, nonOwner.getId(),"new","http://www.fake.com").isSuccess());
	}
	
	@StarexecTest
	private void CanDeleteUserWebsiteTest() {
		Websites.add(owner.getId(), "https://www.fake.edu", "new", WebsiteType.USER);
		int websiteId=Websites.getAll(owner.getId(), WebsiteType.USER).get(0).getId();
		Assert.assertTrue(WebsiteSecurity.canUserDeleteWebsite(websiteId, owner.getId()).isSuccess());
		Assert.assertTrue(WebsiteSecurity.canUserDeleteWebsite(websiteId, admin.getId()).isSuccess());
		
		Assert.assertFalse(WebsiteSecurity.canUserDeleteWebsite(websiteId, nonOwner.getId()).isSuccess());
		Assert.assertFalse(WebsiteSecurity.canUserDeleteWebsite(-1, owner.getId()).isSuccess());
		Assert.assertFalse(WebsiteSecurity.canUserDeleteWebsite(-1, admin.getId()).isSuccess());
	}
	
	@Override
	protected String getTestName() {
		return "WebsiteSecurityTests";
	}

	@Override
	protected void setup() throws Exception {
		owner=ResourceLoader.loadUserIntoDatabase();
		nonOwner=ResourceLoader.loadUserIntoDatabase();
		admin=Users.getAdmins().get(0);
		space=ResourceLoader.loadSpaceIntoDatabase(owner.getId(), 1);
		solver=ResourceLoader.loadSolverIntoDatabase(space.getId(), owner.getId());
	}

	@Override
	protected void teardown() throws Exception {
		Solvers.deleteAndRemoveSolver(solver.getId());
		Users.deleteUser(owner.getId());
		Users.deleteUser(nonOwner.getId());
		Spaces.removeSubspace(space.getId());
	}

}
