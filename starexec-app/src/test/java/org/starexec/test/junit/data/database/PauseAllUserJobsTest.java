package org.starexec.test.junit.data.database;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.Jobs;

import java.util.Arrays;

/**
 * {@link Jobs#pauseAllUserJobs} is how the job scheduler pauses every running job of a user
 * whose disk quota is exceeded.
 *
 * <p>It used {@code success = success && Jobs.pause(i)}. Once one pause failed, the
 * short-circuit skipped every later call, so the user's remaining jobs were never paused.
 * Pausing is mocked here; no database is needed.
 */
public class PauseAllUserJobsTest {

	private static final int USER = 7;

	@Test
	public void aFailedPauseDoesNotStopTheRest() {
		try (MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class)) {
			jobs.when(() -> Jobs.pauseAllUserJobs(USER)).thenCallRealMethod();
			jobs.when(() -> Jobs.getRunningJobs(USER)).thenReturn(Arrays.asList(11, 12, 13));
			jobs.when(() -> Jobs.pause(11)).thenReturn(false);
			jobs.when(() -> Jobs.pause(12)).thenReturn(true);
			jobs.when(() -> Jobs.pause(13)).thenReturn(true);

			Jobs.pauseAllUserJobs(USER);

			jobs.verify(() -> Jobs.pause(11));
			jobs.verify(() -> Jobs.pause(12));
			jobs.verify(() -> Jobs.pause(13));
		}
	}

	@Test
	public void anUnreadableJobListPausesNothingAndDoesNotThrow() {
		try (MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class)) {
			jobs.when(() -> Jobs.pauseAllUserJobs(USER)).thenCallRealMethod();
			// getRunningJobs returns null when its query fails.
			jobs.when(() -> Jobs.getRunningJobs(USER)).thenReturn(null);

			Jobs.pauseAllUserJobs(USER);

			jobs.verify(() -> Jobs.pause(Mockito.anyInt()), Mockito.never());
		}
	}
}
