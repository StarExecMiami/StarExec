package org.starexec.test.integration.database;

import org.junit.jupiter.api.Assertions;
import org.starexec.data.database.Communities;
import org.starexec.data.database.Solvers;
import org.starexec.data.database.Spaces;
import org.starexec.data.to.*;
import org.starexec.exceptions.StarExecDatabaseException;
import org.starexec.exceptions.StarExecSecurityException;
import org.starexec.test.TestUtil;
import org.starexec.test.integration.StarexecTest;
import org.starexec.test.integration.TestSequence;
import org.starexec.util.Util;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tests for org.starexec.data.database.Solvers.java
 * @author Eric
 */
public class SolverTests extends TestSequence {
	private Solver solver; // placed in space1
	private Configuration config;
	private Space space1, space2, testCommunity; // spaces owned by testUser. Space2 a subspace of space1
	User testUser;
	List<Integer> benchmarkIds = null; 
	Job job = null; // uses solver and benchmarks
	
	@StarexecTest
	private void GetSolverTest() {
		Solver cs=Solvers.get(solver.getId());
		Assertions.assertNotNull(cs);
		Assertions.assertEquals(cs.getId(),solver.getId());
	}
	@StarexecTest
	private void GetSolverListTest() {
		List<Integer> list= new ArrayList<>();
		list.add(solver.getId());
		List<Solver> cs=Solvers.get(list);
		Assertions.assertEquals(1,cs.size());
		Assertions.assertEquals(solver.getId(), cs.get(0).getId());
	}

	@StarexecTest
	private void getSolversInSpacesAndJobsContainsConfigs() {
		Set<Integer> spaces = new HashSet<>();
		spaces.add(space1.getId());
		try {
			List<Solver> solvers = Solvers.getSolversInSpacesAndJob(job.getId(), spaces);
			for (Solver s : solvers) {
				Assertions.assertTrue(
						!s.getConfigurations().isEmpty(),
						"Solver had no configurations. This method must return detailed solvers."
				);
			}
		} catch (SQLException e) {
			Assertions.fail("Caught SQLException: "+ Util.getStackTrace(e));
		}
	}
	
	@StarexecTest 
	private void recycleAndRestoreTest() {
		Assertions.assertFalse(Solvers.isSolverRecycled(solver.getId()));
		Assertions.assertTrue(Solvers.recycle(solver.getId()));
		Assertions.assertTrue(Solvers.isSolverRecycled(solver.getId()));
		Assertions.assertTrue(Solvers.restore(solver.getId()));
		Assertions.assertFalse(Solvers.isSolverRecycled(solver.getId()));
	}
	
	@StarexecTest
	private void changeNameTest() throws StarExecDatabaseException {
		String curName=solver.getName();
		Solver cs=Solvers.get(solver.getId());
		Assertions.assertEquals(curName, cs.getName());
		String newName=TestUtil.getRandomSolverName();
		Assertions.assertTrue(Solvers.updateDetails(solver.getId(),newName,solver.getDescription(),solver.isDownloadable()));
		cs=Solvers.get(solver.getId());
		Assertions.assertEquals(newName, cs.getName());
		solver.setName(newName);
	}
	
	@StarexecTest
	private void getConfigTest() {
		Configuration c=Solvers.getConfiguration(config.getId());
		Assertions.assertNotNull(c);
		Assertions.assertEquals(config.getName(),c.getName());
	}
	
	@StarexecTest
	private void getConfigsBySolver() {
		List<Configuration> configs=Solvers.getConfigsForSolver(solver.getId());
		Assertions.assertNotNull(configs);
		Assertions.assertTrue(configs.size()>=1);
		boolean foundConfig=false;
		for (Configuration c : configs) {
			if (c.getId()==config.getId()) {
				foundConfig=true;
				break;
			}
		}
		Assertions.assertTrue(foundConfig);
	}
	
	@StarexecTest
	private void getWithConfig() {
		Solver s=Solvers.getWithConfig(solver.getId(), config.getId());
		Assertions.assertNotNull(s);
		Assertions.assertEquals(1,s.getConfigurations().size());
		Configuration c=s.getConfigurations().get(0);
		Assertions.assertEquals(config.getName(),c.getName());
		
	}
	
	@StarexecTest 
	private void updateConfigName() throws StarExecDatabaseException {
		String oldName=config.getName();
		Assertions.assertEquals(Solvers.getConfiguration(config.getId()).getName(),oldName);
		String newName=TestUtil.getRandomSolverName();
		Assertions.assertTrue(Solvers.updateConfigDetails(config.getId(), newName, ""));
		Assertions.assertEquals(Solvers.getConfiguration(config.getId()).getName(),newName);
		config.setName(newName);
	}
	
	@StarexecTest
	private void updateConfigDescription() throws StarExecDatabaseException {
		String oldDesc=config.getDescription();
		Assertions.assertEquals(Solvers.getConfiguration(config.getId()).getDescription(),oldDesc);
		String newDesc=TestUtil.getRandomSolverName();
		Assertions.assertTrue(Solvers.updateConfigDetails(config.getId(), config.getName(), newDesc));
		Assertions.assertEquals(Solvers.getConfiguration(config.getId()).getDescription(),newDesc);
		config.setDescription(newDesc);
	}
	@StarexecTest 
	private void deleteConfigTest() throws StarExecDatabaseException {
		Configuration c=loader.loadConfigurationFileIntoDatabase("CVC4Config.txt", solver.getId());
		Assertions.assertNotNull(c);
		Assertions.assertNotNull(Solvers.getConfiguration(c.getId()));
		Assertions.assertTrue(Solvers.deleteConfiguration(c.getId()));
		Assertions.assertNull(Solvers.getConfiguration(c.getId()));
	}
	
	@StarexecTest
	private void changeDescTest() throws StarExecDatabaseException {
		String curDesc=solver.getDescription();
		Solver cs=Solvers.get(solver.getId());
		Assertions.assertEquals(curDesc, cs.getDescription());
		String newDesc=TestUtil.getRandomSolverName();
		Assertions.assertTrue(Solvers.updateDetails(solver.getId(),solver.getName(),newDesc,solver.isDownloadable()));
		cs=Solvers.get(solver.getId());
		Assertions.assertEquals(newDesc, cs.getDescription());
		solver.setDescription(newDesc);
	}
	
	@StarexecTest
	private void changeDownloadableTest() throws StarExecDatabaseException {
		Solver cs=Solvers.get(solver.getId());
		Assertions.assertEquals(solver.isDownloadable(), cs.isDownloadable());
		Assertions.assertTrue(Solvers.updateDetails(solver.getId(),solver.getName(),solver.getDescription(),!solver.isDownloadable()));
		cs=Solvers.get(solver.getId());
		Assertions.assertNotEquals(solver.isDownloadable(), cs.isDownloadable());
		solver.setDownloadable(!solver.isDownloadable());
	}
	
	@StarexecTest
	private void associateTest() {
		Assertions.assertTrue(Solvers.associate(solver.getId(), space2.getId()));
		boolean found = false;
		for (Solver sol : Spaces.getDetails(space2.getId(), testUser.getId()).getSolvers()) {
			found = found || sol.getId()==solver.getId();
		}
		Assertions.assertTrue(found);
		List<Integer> ids = new ArrayList<>();
		ids.add(solver.getId());
		Spaces.removeSolvers(ids, space2.getId());
	}
	
	@StarexecTest
	private void associateInHierarchyTest() {
		List<Integer> ids = new ArrayList<>();
		ids.add(solver.getId());
		
		Assertions.assertTrue(Solvers.associate(ids,space1.getId(),true,testUser.getId(),false));
		boolean found = false;
		for (Solver sol : Spaces.getDetails(space2.getId(), testUser.getId()).getSolvers()) {
			found = found || sol.getId()==solver.getId();
		}
		Assertions.assertTrue(found);
		
		Spaces.removeSolvers(ids, space2.getId());
	}
	
	@StarexecTest
	private void copySolverTest() throws StarExecDatabaseException {
		int id = Solvers.copySolver(Solvers.get(solver.getId()), testUser.getId(), space2.getId());
		Assertions.assertTrue(id>0);
		Solver newSolver = Solvers.get(id);
		Assertions.assertNotEquals(newSolver.getId(), solver.getId());
		Assertions.assertEquals(solver.getName(), newSolver.getName());
		Assertions.assertTrue(new File(newSolver.getPath()).exists());
		Solvers.deleteAndRemoveSolver(newSolver.getId());
	}
	
	@StarexecTest
	private void copySolversTest() throws StarExecDatabaseException {
		List<Solver> sols = new ArrayList<>();
		sols.add(Solvers.get(solver.getId()));
		List<Integer> ids = Solvers.copySolvers(sols, testUser.getId(), space2.getId());
		Assertions.assertEquals(1, ids.size());
		int id = ids.get(0);
		Assertions.assertTrue(id>0);
		Solver newSolver = Solvers.get(id);
		Assertions.assertNotEquals(newSolver.getId(), solver.getId());
		Assertions.assertEquals(solver.getName(), newSolver.getName());
		Assertions.assertTrue(new File(newSolver.getPath()).exists());
		Solvers.deleteAndRemoveSolver(newSolver.getId());
	}
	
	@StarexecTest
	private void cleanOrphanedDeletedSolversTest() {
		Solver s = loader.loadSolverIntoDatabase(space1.getId(), testUser.getId());
		Solvers.delete(s.getId());
		Assertions.assertTrue(Solvers.cleanOrphanedDeletedSolvers());
		Assertions.assertNotNull(Solvers.getIncludeDeleted(s.getId()));
		List<Integer> ids = new ArrayList<>();
		ids.add(s.getId());
		Spaces.removeSolvers(ids, space1.getId());
		Assertions.assertTrue(Solvers.cleanOrphanedDeletedSolvers());
		Assertions.assertNull(Solvers.getIncludeDeleted(s.getId()));
	}
	
	@StarexecTest
	private void deleteSolverTest() throws StarExecDatabaseException {
		Solver s = loader.loadSolverIntoDatabase(space1.getId(), testUser.getId());
		Assertions.assertTrue(Solvers.delete(s.getId()));
		s = Solvers.getIncludeDeleted(s.getId());
		Assertions.assertTrue(s.isDeleted());
		Assertions.assertFalse(new File(s.getPath()).exists());
		Solvers.deleteAndRemoveSolver(s.getId());
	}
	
	@StarexecTest
	private void findConfigsEmptyDirTest() {
		Assertions.assertEquals(0, Solvers.findConfigs("/starexec/fake/test/directory").size());
	}
	
	@StarexecTest
	private void findConfigsTest() throws IOException {
		List<String> strs = loader.getTestConfigDirectory();
		List<Configuration> configurations = Solvers.findConfigs(strs.get(0));
		Assertions.assertEquals(2, configurations.size());
		Assertions.assertTrue(strs.contains(configurations.get(0).getName()));
		Assertions.assertTrue(strs.contains(configurations.get(1).getName()));
	}
	
	@StarexecTest
	private void getAssociatedSpaceIdsTest() {
		List<Integer> ids = Solvers.getAssociatedSpaceIds(solver.getId());
		Assertions.assertEquals(1, ids.size());
		Assertions.assertEquals((Integer)space1.getId(), ids.get(0));
	}
	
	@StarexecTest
	private void getByConfigIdTest() {
		Assertions.assertEquals(solver.getId(), Solvers.getByConfigId(config.getId()).getId());
	}
	
	@StarexecTest
	private void getSolversInSharedSpacesTest() {
		List<Solver> solvers = Solvers.getSolversInSharedSpaces(testUser.getId());
		Assertions.assertEquals(1, solvers.size());
		Assertions.assertEquals(solver.getId(), solvers.get(0).getId());
	}
	
	@StarexecTest
	private void getBySpaceTest() {
		List<Solver> solvers = Solvers.getBySpace(space1.getId());
		Assertions.assertEquals(1, solvers.size());
		Assertions.assertEquals(solver.getId(), solvers.get(0).getId());
	}
	
	@StarexecTest
	private void getBySpaceDetailedTest() {
		List<Solver> solvers = Solvers.getBySpaceDetailed(space1.getId());
		Assertions.assertEquals(1, solvers.size());
		Assertions.assertEquals(solver.getId(), solvers.get(0).getId());
		Assertions.assertEquals(solver.getConfigurations().size(), solvers.get(0).getConfigurations().size());
	}
	
	@StarexecTest
	private void getBySpaceHierarchyTest() throws StarExecDatabaseException {
		Solver newSolver = loader.loadSolverIntoDatabase(space2.getId(), testUser.getId());
		Solvers.associate(newSolver.getId(), space2.getId());
		List<Solver> solvers = Solvers.getBySpaceHierarchy(space1.getId(), testUser.getId());
		Assertions.assertEquals(2, solvers.size());
		for (Solver s : solvers) {
			Assertions.assertTrue(s.getId()==newSolver.getId() || s.getId()==solver.getId());
		}
		
		Solvers.deleteAndRemoveSolver(newSolver.getId());
	}
	
	@StarexecTest
	private void getByOwnerTest() {
		List<Solver> solvers = Solvers.getByOwner(testUser.getId());
		Assertions.assertEquals(1, solvers.size());
		Assertions.assertEquals(solver.getId(), solvers.get(0).getId());
	}
	
	@StarexecTest
	private void getByUserTest() {
		List<Solver> solvers = Solvers.getByUser(testUser.getId());
		boolean found = false;
		for (Solver s : solvers) {
			found = found || s.getId()==solver.getId();
			Assertions.assertTrue(Solvers.isPublic(s.getId()) || s.getId()==solver.getId());
		}
		Assertions.assertTrue(found);
	}
	
	@StarexecTest
	private void getByJobSimpleWithConfigsTest() throws SQLException {
		List<Solver> solvers = Solvers.getByJobSimpleWithConfigs(job.getId());
		Assertions.assertEquals(1, solvers.size());
		Assertions.assertEquals(solver.getId(), solvers.get(0).getId());
	}
	
	@StarexecTest
	private void getConfigIdSetByJobTest() throws SQLException {
		Set<Integer> configIds = Solvers.getConfigIdSetByJob(job.getId());
		Assertions.assertEquals(1, configIds.size());
		Assertions.assertEquals((Integer)config.getId(), configIds.iterator().next());
	}
	
	@StarexecTest
	private void getCountInSpaceTest() {
		Assertions.assertEquals(1, Solvers.getCountInSpace(space1.getId()));
	}
	
	@StarexecTest
	private void getCountInSpaceQueryTest() {
		Assertions.assertEquals(1, Solvers.getCountInSpace(space1.getId(), ""));
		Assertions.assertEquals(1, Solvers.getCountInSpace(space1.getId(), solver.getName()));
		Assertions.assertEquals(0, Solvers.getCountInSpace(space1.getId(), TestUtil.getRandomAlphaString(100)));
	}
	
	@StarexecTest
	private void getPublicSolversTest() {
		boolean found = false;
		boolean isPublic = Spaces.isPublicSpace(space1.getId());
		for (Solver s : Solvers.getPublicSolvers()) {
			found = found || s.getId()==solver.getId();
		}
		Assertions.assertEquals(isPublic, found);
		found = false;
		Spaces.setPublicSpace(space1.getId(), testUser.getId(), !isPublic, false);
		for (Solver s : Solvers.getPublicSolvers()) {
			found = found || s.getId()==solver.getId();
		}
		Assertions.assertNotEquals(isPublic, found);
		Spaces.setPublicSpace(space1.getId(), testUser.getId(), isPublic, false);
		
	}
	
	@Override
	protected void setup() throws Exception {
		testUser=loader.loadUserIntoDatabase();
		testCommunity=Communities.getTestCommunity();
		space1=loader.loadSpaceIntoDatabase(testUser.getId(),testCommunity.getId());
		space2=loader.loadSpaceIntoDatabase(testUser.getId(), space1.getId());
		solver=loader.loadSolverIntoDatabase("CVC4.zip", space1.getId(), testUser.getId());
		config=solver.getConfigurations().get(0);
		benchmarkIds = loader.loadBenchmarksIntoDatabase(space1.getId(), testUser.getId());
		job = loader.loadJobIntoDatabase(space1.getId(), testUser.getId(), solver.getId(), benchmarkIds);
	}

	@Override
	protected void teardown() throws StarExecSecurityException {
		loader.deleteAllPrimitives();
	}
		
	
	@Override
	protected String getTestName() {
		return "SolverTestSequence";
	}

}
