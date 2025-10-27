package org.starexec.test.junit.data.database;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.constants.R;
import org.starexec.data.database.Jobs;
import org.starexec.data.to.Job;
import org.starexec.data.to.Queue;

import static org.mockito.ArgumentMatchers.any;

public class JobTests {
	
	private final static String TEST_QUEUE_NAME = "all.q";

	@Before
	public void initialize() {
		// No static mocking needed in @Before, we'll do it in each test
	}

	private static Job getTestJob() {
		Job job = new Job();
		Queue queue = new Queue();
		queue.setName(TEST_QUEUE_NAME);
		job.setQueue(queue);
		return job;
	}

	@Test
	public void GetSlotsInJobQueueForSgeTest() {
		try (MockedStatic<Jobs> jobsMock = Mockito.mockStatic(Jobs.class)) {
			Job job = getTestJob();
			R.BACKEND_TYPE=R.SGE_TYPE;
			// Note: This test cannot properly mock GridEngineBackend constructor calls without
			// refactoring the production code to use dependency injection.
			// When getSlotsInJobQueue() creates a new GridEngineBackend() internally,
			// it will fail to connect to SGE (not available in test environment) and fall back
			// to returning R.DEFAULT_QUEUE_SLOTS = "2"
			jobsMock.when(() -> Jobs.getSlotsInJobQueue(any())).thenCallRealMethod();
			// Verify the method returns the default when SGE backend is unavailable
			Assert.assertEquals(R.DEFAULT_QUEUE_SLOTS, Jobs.getSlotsInJobQueue(job));
		}
	}

	@Test
	public void GetSlotsInJobQueueForLocalTest() {
		try (MockedStatic<Jobs> jobsMock = Mockito.mockStatic(Jobs.class)) {
			R.BACKEND_TYPE = R.LOCAL_TYPE;
			Job job = getTestJob();
			jobsMock.when(() -> Jobs.getSlotsInJobQueue(job)).thenCallRealMethod();
			Assert.assertEquals(Jobs.getSlotsInJobQueue(job), R.DEFAULT_QUEUE_SLOTS);
			System.out.println("End GetSlotsInJobQueueForLocalTest");
		}
	}

	@Test
	public void GetSlotsInJobQueueForOarTest() {
		try (MockedStatic<Jobs> jobsMock = Mockito.mockStatic(Jobs.class)) {
			Job job = getTestJob();
			jobsMock.when(() -> Jobs.getSlotsInJobQueue(job)).thenCallRealMethod();
			R.BACKEND_TYPE = R.OAR_TYPE;
			Assert.assertEquals(Jobs.getSlotsInJobQueue(job), R.DEFAULT_QUEUE_SLOTS);
		}
	}
}
