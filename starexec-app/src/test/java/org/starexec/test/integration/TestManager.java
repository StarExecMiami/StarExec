package org.starexec.test.integration;

import org.apache.commons.io.FileUtils;
import org.starexec.constants.R;
import org.starexec.logger.StarLogger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class maintains a list of all TestSequences and handles requests
 * for running those sequences. It prevents multiple instances of the same
 * TestSequence from being run simultaneously.
 * 
 * @author Eric Burns
 *
 */
public class TestManager {
	private static final StarLogger log = StarLogger.getLogger(TestManager.class);
	private final static AtomicBoolean isRunning = new AtomicBoolean(false);
	private final static AtomicBoolean isRunningStress = new AtomicBoolean(false);
	private static volatile List<TestSequence> tests = List.of();

	/**
	 * Discovers and instantiates all test sequences once. Repeated startup callbacks
	 * retain the same ordered instances and cannot duplicate the registry.
	 */
	public static synchronized void initializeTests() {
		if (!tests.isEmpty()) {
			return;
		}
		List<TestSequenceDiscovery.DiscoveredSequence> sequences = TestSequenceDiscovery.discover();
		tests = instantiateReadySequences(sequences, LegacyTestLayerInventory.load());
	}

	static List<TestSequence> instantiateReadySequences(
			List<TestSequenceDiscovery.DiscoveredSequence> sequences,
			LegacyTestLayerInventory inventory) {
		inventory.validateReady(sequences);
		List<TestSequence> discovered = new ArrayList<>();
		for (TestSequenceDiscovery.DiscoveredSequence sequence : sequences) {
			discovered.add(TestSequenceDiscovery.instantiate(sequence));
		}
		return List.copyOf(discovered);
	}

	/**
	 *
	 * @return Whether any tests are currently running. Only one sequence can run at
	 *         once.
	 */
	public static boolean areTestsRunning() {
		return org.starexec.util.SystemTestStatus.areTestsRunning();
	}

	private static void setTestsRunning(boolean running) {
		org.starexec.util.SystemTestStatus.setTestsRunning(running);
	}

	/**
	 *
	 * @return Whether a stress test is currently running, meaning whether things
	 *         are still being added to the database.
	 */

	public static boolean isStressTestRunning() {
		return org.starexec.util.SystemTestStatus.isStressTestRunning();
	}

	/**
	 * Executes every test sequence in tests
	 * 
	 * @return True if the tests were started, and false if they were not for some
	 *         reason
	 */
	public static boolean executeAllTestSequences() {
		if (!R.ALLOW_TESTING) {
			return false; // right now, don't run anything on production
		}
		initializeTests();
		// don't do anything if the tests are already running
		if (!isRunning.compareAndSet(false, true)) {
			return false;
		}
		final ExecutorService threadPool = Executors.newCachedThreadPool();

		// we want to return here, not wait until all the tests finish, which is why we
		// spin off a new threads
		threadPool.execute(() -> {

			// we want to clear all the results first, so it's obvious to the user what is
			// left to be run
			for (TestSequence t : tests) {
				t.clearResults();
			}

			for (TestSequence t : tests) {
				t.execute();
			}
			isRunning.set(false);
		});
		return true;
	}

	/**
	 * @return all TestSequences registered during initializeTests
	 */
	public static List<TestSequence> getAllTestSequences() {
		return tests;
	}

	/**
	 * @param sequenceName
	 * @return results for all tests in the sequence with the given name
	 */
	public static List<TestResult> getAllTestResults(String sequenceName) {
		TestSequence seq = TestManager.getTestSequence(sequenceName);
		if (seq == null) {
			return null;
		}
		return seq.getTestResults();
	}

	/**
	 * Executes the tests that have the given name.
	 * 
	 * @param testNames The names of the test sequences that should be run
	 * @return True if the test could be found, false otherwise
	 */
	public static boolean executeTests(String[] testNames) {
		if (!R.ALLOW_TESTING) {
			return false; // right now, don't run anything on production
		}
		initializeTests();
		// don't run anything if we are already going
		if (!isRunning.compareAndSet(false, true)) {
			return false;
		}

		final String[] t = testNames;
		final ExecutorService threadPool = Executors.newCachedThreadPool();
		// we want to return here, not wait until all the tests finish, which is why we
		// spin off a new thread
		threadPool.execute(() -> {

			for (String s : t) {
				TestSequence test = getTestSequence(s);
				if (test != null) {
					test.clearResults();

					executeTest(test);
				}

			}
			isRunning.set(false);
		});

		return true;
	}

	/**
	 * Runs a stress test using the given parameters
	 * 
	 * @param userCount             How many new users will be created
	 * @param spaceCount            How many new spaces to create
	 * @param jobCount              How many new jobs to create
	 * @param minUsersPerSpace
	 * @param maxUsersPerSpace
	 * @param minSolversPerSpace
	 * @param maxSolversPerSpace
	 * @param minBenchmarksPerSpace
	 * @param maxBenchmarksPerSpace
	 * @param spacesPerJobCount
	 * @return True if the test was started and false if it was not
	 */
	public static boolean executeStressTest(final int userCount, final int spaceCount, final int jobCount,
			final int minUsersPerSpace, final int maxUsersPerSpace, final int minSolversPerSpace,
			final int maxSolversPerSpace, final int minBenchmarksPerSpace, final int maxBenchmarksPerSpace,
			final int spacesPerJobCount) {
		if (!R.ALLOW_TESTING) {
			return false; // right now, don't run anything on production
		}
		// don't run anything if we are already going
		if (!isRunningStress.compareAndSet(false, true)) {
			return false;
		}

		final ExecutorService threadPool = Executors.newCachedThreadPool();
		// we want to return here, not wait until all the tests finish, which is why we
		// spin off a new thread
		threadPool.execute(() -> {
			StressTest.execute(userCount, spaceCount, minUsersPerSpace, maxUsersPerSpace, minSolversPerSpace,
					maxSolversPerSpace,
					minBenchmarksPerSpace, maxBenchmarksPerSpace, jobCount, spacesPerJobCount);
			isRunningStress.set(false);
		});

		return true;
	}

	/**
	 * Executes the given test sequence
	 * 
	 * @param test
	 */
	public static void executeTest(TestSequence test) {
		initializeTests();
		test.execute();
	}

	/**
	 * @return the names of all TestSequences known to the manager
	 */
	public static List<String> getTestNames() {
		List<String> names = new ArrayList<>();
		for (TestSequence t : tests) {
			names.add(t.getName());
		}
		return names;
	}

	/**
	 * @param testName The name of the TestSequence of interest
	 * @return the status of a TestSequence given its name
	 */
	public static TestStatus getTestStatus(String testName) {
		TestSequence t = getTestSequence(testName);
		if (t == null) {
			return null;
		}
		return t.getStatus();
	}

	/**
	 * @param testName
	 * @return the message contained by a given TestSequence
	 */
	public static String getTestMessage(String testName) {
		TestSequence t = getTestSequence(testName);
		if (t == null) {
			return null;
		}
		return t.getMessage();
	}

	/**
	 * Returns a TestSequence object
	 * 
	 * @param name
	 * @return
	 */
	private static TestSequence getTestSequence(String name) {
		for (TestSequence t : tests) {
			if (t.getName().equals(name)) {
				return t;
			}
		}
		return null;
	}

	/**
	 * Completely deletes all job output. This is ONLY for use on Stardev-- we
	 * should NEVER
	 * be running this on production!
	 */
	public static void emptyJobOutputDirectory() {
		if (!R.ALLOW_TESTING) {
			return;
		}
		final ExecutorService threadPool = Executors.newCachedThreadPool();
		log.debug("trying to empty the job output directory");
		threadPool.execute(() -> {
			File file = new File(R.JOB_OUTPUT_DIRECTORY);
			log.debug("calling deleteQuietly on job output");

			FileUtils.deleteQuietly(file);
			log.debug("finished calling deleteQuietly on job output");

			file.mkdir();
		});
	}

}
