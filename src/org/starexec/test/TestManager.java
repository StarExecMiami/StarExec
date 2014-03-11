package org.starexec.test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.log4j.Logger;
import org.jfree.util.Log;
import org.starexec.data.database.Communities;
import org.starexec.test.StateTests.IntroStateTests;
import org.starexec.test.database.PermissionsTests;
import org.starexec.test.database.SolverTests;
import org.starexec.test.database.SpaceTests;
import org.starexec.test.database.UserTests;
import org.starexec.test.security.BenchmarkSecurityTests;
import org.starexec.test.security.CacheSecurityTests;
import org.starexec.test.security.GeneralSecurityTests;
import org.starexec.test.security.JobSecurityTests;
import org.starexec.test.security.QueueSecurityTests;
import org.starexec.test.security.SolverSecurityTests;
import org.starexec.test.security.UserSecurityTests;
import org.starexec.test.security.ValidatorTests;


public class TestManager {
	private static final Logger log = Logger.getLogger(TestManager.class);

	//this should never be modified outside of the initializeTests method
	private final static List<TestSequence> tests=new ArrayList<TestSequence>();
	private static boolean isRunning=false;
	//all test sequences need to be initialized here
	public static void initializeTests() {
		tests.add(new SolverTests());
		tests.add(new SpaceTests());
		tests.add(new StarexecCommandTests());
		tests.add(new SolverSecurityTests());
		tests.add(new ValidatorTests());
		tests.add(new UserSecurityTests());
		tests.add(new QueueSecurityTests());
		tests.add(new GeneralSecurityTests());
		//tests.add(new JobSecurityTests());
		tests.add(new BenchmarkSecurityTests());
		tests.add(new CacheSecurityTests());
		tests.add(new UserTests());
		tests.add(new PermissionsTests());
		tests.add(new IntroStateTests());
		tests.add(new UtilTests());
	}
	
	/**
	 * Executes every test sequence in tests
	 */
	public static void executeAllTestSequences() {
		final ExecutorService threadPool = Executors.newCachedThreadPool();
		//we want to return here, not wait until all the tests finish, which is why we spin off a new threads
		threadPool.execute(new Runnable() {
			@Override
			public void run(){
				//don't do anything if the tests are already running
				//TODO: it's possible two threads could hit this at exactly the same time. What should the solution be?
				if (isRunning) {
					return;
				}
				isRunning=true;
				//we want to clear all the results first, so it's obvious to the user what is left to be run
				for (TestSequence t : tests) {
					t.clearResults();
				}
				
				for (TestSequence t : tests) {
					t.execute();
				}
				isRunning=false;
			}
		});	
		
	}
	
	public static List<TestSequence> getAllTestSequences() {
		return tests;
	}
	public static List<TestResult> getAllTestResults(String sequenceName) {
		return TestManager.getTestSequence(sequenceName).getTestResults();
	}
	/**
	 * Executes the test that has the given name. If no such test exists, 
	 * returns false
	 * @param testName
	 * @return True if the test could be found, false otherwise
	 */
	public static boolean executeTest(String testName) {
		final String t=testName;
		final ExecutorService threadPool = Executors.newCachedThreadPool();
		//we want to return here, not wait until all the tests finish, which is why we spin off a new thread
		threadPool.execute(new Runnable() {
			@Override
			public void run(){
				if (isRunning) {
					return;
				}
				isRunning=true;
				
				TestSequence test = getTestSequence(t);
				
				executeTest(test);
				isRunning=false;
			}
		});	
		
		return true;
	}
	
	/**
	 * Executes the given test sequence
	 * @param test
	 */
	public static void executeTest(TestSequence test) {
		test.execute();
	}
	
	public static List<String> getTestNames() {
		List<String> names=new ArrayList<String>();
		for (TestSequence t : tests) {
			names.add(t.getName());
		}
		return names;
	}
	
	public static TestStatus getTestStatus(String testName) {
		TestSequence t = getTestSequence(testName);
		if (t==null) {
			return null;
		}
		return t.getStatus();
	}
	
	public static String getTestMessage(String testName) {
		TestSequence t = getTestSequence(testName);
		if (t==null) {
			return null;
		}
		return t.getMessage();
	}
	
	private static TestSequence getTestSequence(String name) {		
		for (TestSequence t : tests) {
			if (t.getName().equals(name)) {
				return t;
			}
		}
		return null;
	}
}
